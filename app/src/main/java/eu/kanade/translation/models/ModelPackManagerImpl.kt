package eu.kanade.translation.models

import eu.kanade.translation.models.source.ModelPackSource
import eu.kanade.translation.models.source.PackSourceException
import eu.kanade.translation.models.store.ActivePackRecord
import eu.kanade.translation.models.store.PackStoreIo
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val STAGING_PREFIX = "staging-"
private const val VERSIONS_DIR = "versions"
private const val ACTIVE_FILE = "active.json"

/** Imported variants live under this id prefix; they never bind to consumers until L2/L3 exists. */
const val IMPORT_ID_PREFIX = "import."

/**
 * Crash-consistent pack store. Acquisition, activation and deletion run under
 * one per-pack mutex; active.json is the only commit point; sweeps touch
 * staging dirs only — active and retained-previous versions are never deleted
 * on a missing or corrupt index, and never while live leases pin the pack.
 */
class ModelPackManagerImpl(
    private val rootDir: File,
    private val bundledCacheRoot: File,
    private val catalogProvider: () -> List<ModelPackDescriptor>,
    private val sourceFor: (ModelPackDescriptor) -> ModelPackSource?,
    private val deviceClassProvider: () -> String? = { null },
    private val networkMeteredProvider: () -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
) : ModelPackManager {

    private val packLocks = ConcurrentHashMap<PackId, Mutex>()
    private val installJobs = ConcurrentHashMap<PackId, Job>()
    private val leasesByPack = ConcurrentHashMap<PackId, ConcurrentHashMap<String, PackLeaseImpl>>()
    private val lastFailures = ConcurrentHashMap<PackId, PackFailure>()
    private val leaseCounter = AtomicLong(0)
    private val attemptCounter = AtomicLong(0)

    private val _statuses = MutableStateFlow<List<PackStatus>>(emptyList())
    override val statuses: StateFlow<List<PackStatus>> = _statuses.asStateFlow()

    init {
        sweepStagingDirs()
        rebuildStatuses()
    }

    override suspend fun refresh() = withContext(Dispatchers.IO) {
        rootDir.listFiles()?.filter { it.isDirectory }?.forEach { packDir ->
            lockFor(packDir.name).withLock { sweepStagingDir(packDir) }
        }
        catalogProvider().forEach { descriptor ->
            lockFor(descriptor.id).withLock {
                gcUnreferencedVersions(descriptor.id)
            }
        }
        rebuildStatuses()
    }

    override suspend fun install(packId: PackId, confirmMetered: Boolean): InstallResult = withContext(Dispatchers.IO) {
        lockFor(packId).withLock {
            val descriptor = catalogProvider().firstOrNull { it.id == packId }
                ?: return@withLock InstallResult.Failed(PackFailure.SourceUnavailable("Unknown pack id: $packId"))
            val job = currentCoroutineContext()[Job]
            if (job != null) installJobs[packId] = job
            try {
                installLocked(descriptor, confirmMetered)
            } catch (e: java.io.IOException) {
                // Commit-point failures (e.g. atomic replace unsupported, temp
                // unwritable) must surface as a Failed result, never crash the
                // caller — the previous active record stays intact and readable.
                lastFailures[packId] = PackFailure.SourceUnavailable(
                    "Install aborted without changing the active version: ${e.message}",
                )
                InstallResult.Failed(lastFailures[packId]!!)
            } finally {
                installJobs.remove(packId)
                rebuildStatuses()
            }
        }
    }

    override suspend fun cancel(packId: PackId) {
        installJobs[packId]?.cancel()
    }

    override suspend fun delete(packId: PackId, acknowledgeEssential: Boolean): DeleteResult = withContext(Dispatchers.IO) {
        lockFor(packId).withLock {
            val packDir = File(rootDir, packId)
            val freed = PackStoreIo.sizeRecursively(packDir)
            val active = readActive(packId)
            when {
                active == null && !packDir.exists() -> DeleteResult.Blocked(DeleteBlockReason.NOT_INSTALLED)
                active != null && hasLiveLeases(packId) -> DeleteResult.Blocked(DeleteBlockReason.LIVE_LEASES)
                active != null && catalogRole(packId) == PackRole.ESSENTIAL && !acknowledgeEssential ->
                    DeleteResult.Blocked(DeleteBlockReason.ESSENTIAL_ACK_REQUIRED)
                else -> {
                    lastFailures.remove(packId)
                    DeleteResult.Deleted(PackStoreIo.deleteRecursively(packDir).ifZeroUse(freed))
                }
            }.also { rebuildStatuses() }
        }
    }

    override suspend fun verify(packId: PackId): VerifyResult = withContext(Dispatchers.IO) {
        lockFor(packId).withLock {
            val record = readActive(packId) ?: return@withLock VerifyResult.NotInstalled
            val versionDir = versionDir(packId, record.version)
            val problems = record.files.mapNotNull { entry ->
                val file = File(versionDir, entry.path)
                when {
                    !file.isFile -> "${entry.path}: missing"
                    file.length() != entry.sizeBytes -> "${entry.path}: size ${file.length()} != ${entry.sizeBytes}"
                    PackStoreIo.sha256Of(file) != entry.sha256 -> "${entry.path}: sha256 mismatch"
                    else -> null
                }
            }
            if (problems.isEmpty()) VerifyResult.Verified else VerifyResult.Corrupt(problems)
        }
    }

    override suspend fun clearExtractedCache(cachedFileNames: List<String>): CacheClearResult {
        val anyLiveLease = leasesByPack.values.any { it.isNotEmpty() }
        if (anyLiveLease) return CacheClearResult.Blocked("Model packs are in use by active sessions")
        var freed = 0L
        cachedFileNames.forEach { name ->
            val file = File(bundledCacheRoot, name)
            if (file.isFile) freed += file.length().also { file.delete() }
        }
        return CacheClearResult.Cleared(freed)
    }

    override suspend fun acquire(packId: PackId, owner: Job?): PackLease? =
        lockFor(packId).withLock {
            // Named invariant (adversarial review): user imports never bind to
            // consumers until isolated-process runtime validation (L2/L3) exists.
            if (packId.startsWith(IMPORT_ID_PREFIX)) return@withLock null
            val record = readActive(packId) ?: return@withLock null
            val versionDir = versionDir(packId, record.version)
            val files = record.files.map { spec ->
                val file = File(versionDir, spec.path)
                if (!file.isFile || file.length() != spec.sizeBytes) return@withLock null
                LeasedFile(spec.path, file, spec.sha256)
            }
            lastFailures.remove(packId)
            PackLeaseImpl(packId, record.version, files, owner)
        }

    override suspend fun release(lease: PackLease) {
        lease.release()
        if (lease is PackLeaseImpl) {
            lockFor(lease.packId).withLock {
                gcUnreferencedVersions(lease.packId)
                rebuildStatuses()
            }
        }
    }

    override suspend fun digestsFor(packId: PackId): Map<String, String>? =
        readActive(packId)?.files?.associate { it.path to it.sha256 }

    // ---------------------------------------------------------------- install

    private suspend fun installLocked(descriptor: ModelPackDescriptor, confirmMetered: Boolean): InstallResult {
        val packId = descriptor.id
        val source = sourceFor(descriptor)
            ?: return InstallResult.Failed(
                PackFailure.SourceUnavailable(
                    "No usable source for ${descriptor.sourceKind} on this build; packs install from the bundled asset mirror",
                ),
            )
        val active = readActive(packId)
        if (active != null && active.version == descriptor.version) {
            val check = verifyActiveFiles(packId, active)
            if (check == null) {
                lastFailures.remove(packId)
                return InstallResult.AlreadyInstalled(descriptor.version)
            }
            lastFailures[packId] = PackFailure.Internal(
                "Active version ${descriptor.version} failed verification (${check.first()}); delete the pack before reinstalling",
            )
            return InstallResult.Failed(lastFailures[packId]!!)
        }
        descriptor.primaryVariant.deviceConstraint?.let { constraint ->
            val deviceClass = deviceClassProvider()
            if (deviceClass == null || !deviceClass.contains(constraint, ignoreCase = true)) {
                val failure = PackFailure.DeviceIncompatible(
                    constraint,
                    "Pack requires device class $constraint; this build reports ${deviceClass ?: "unknown"}",
                )
                lastFailures[packId] = failure
                return InstallResult.Failed(failure)
            }
        }
        descriptor.fileSpecs().firstOrNull { it.sha256 == null && descriptor.sourceKind == PackSourceKind.HTTP }?.let {
            val failure = PackFailure.Internal("HTTP sources require pinned digests (${it.path})")
            lastFailures[packId] = failure
            return InstallResult.Failed(failure)
        }
        // Crash-before-commit recovery: a leftover version dir matching the
        // pinned specs is adopted without re-copying bytes. Unpinned specs
        // cannot be verified on disk and take the staging path below.
        val existingVersionDir = File(File(rootDir, packId), VERSIONS_DIR + File.separator + descriptor.version)
        if (existingVersionDir.isDirectory && descriptor.fileSpecs().all { it.sha256 != null }) {
            val adoptable = descriptor.fileSpecs().all { spec ->
                val file = File(existingVersionDir, spec.path)
                file.isFile && (spec.sizeBytes < 0 || file.length() == spec.sizeBytes) &&
                    spec.sha256 == PackStoreIo.sha256Of(file)
            }
            if (adoptable) {
                val digests = descriptor.fileSpecs()
                    .associate { it.path to PackStoreIo.sha256Of(File(existingVersionDir, it.path)) }
                val sizes = descriptor.fileSpecs()
                    .associate { it.path to File(existingVersionDir, it.path).length() }
                return writeActiveRecord(descriptor, digests, sizes, active)
            }
        }

        val available = PackStoreIo.availableBytes(rootDir)
        if (available >= 0 && available < descriptor.totalBytes) {
            val failure = PackFailure.StorageInsufficient(descriptor.totalBytes, available)
            lastFailures[packId] = failure
            return InstallResult.Failed(failure)
        }
        if (descriptor.sourceKind == PackSourceKind.HTTP && networkMeteredProvider() && !confirmMetered) {
            lastFailures[packId] = PackFailure.MeteredConfirmationRequired
            return InstallResult.Failed(PackFailure.MeteredConfirmationRequired)
        }

        val packDir = File(rootDir, packId)
        val staging = File(packDir, "$STAGING_PREFIX${now()}-${attemptCounter.incrementAndGet()}")
        staging.mkdirs()
        val digests = HashMap<String, String>()
        val sizes = HashMap<String, Long>()
        try {
            val specs = descriptor.fileSpecs()
            var doneBytes = 0L
            for (spec in specs) {
                currentCoroutineContext().ensureActive()
                val part = File(staging, spec.path + ".part")
                val written = try {
                    source.copyInto(spec, part)
                } catch (e: PackSourceException) {
                    lastFailures[packId] = PackFailure.SourceUnavailable(e.message ?: "source unavailable")
                    return InstallResult.Failed(lastFailures[packId]!!)
                } catch (e: java.io.IOException) {
                    // Disk-full or write failure while staging: cleanup happens in
                    // the finally sweep; the active version is untouched.
                    lastFailures[packId] = PackFailure.SourceUnavailable(
                        "Staging failed for ${spec.path}: ${e.message}",
                    )
                    return InstallResult.Failed(lastFailures[packId]!!)
                }
                if (spec.sizeBytes >= 0 && written != spec.sizeBytes) {
                    lastFailures[packId] = PackFailure.VerificationMismatch(
                        spec.path,
                        "Size mismatch: got $written bytes, expected ${spec.sizeBytes}",
                    )
                    return InstallResult.Failed(lastFailures[packId]!!)
                }
                val digest = PackStoreIo.sha256Of(part)
                spec.sha256?.takeIf { it != digest }?.let {
                    lastFailures[packId] = PackFailure.VerificationMismatch(spec.path, "SHA-256 mismatch against pinned digest")
                    return InstallResult.Failed(lastFailures[packId]!!)
                }
                digests[spec.path] = digest
                sizes[spec.path] = written
                PackStoreIo.moveReplacing(part, File(staging, spec.path))
                doneBytes += written
                publishProgress(packId, PackProgress(doneBytes, descriptor.totalBytes, digests.size, specs.size))
                // Known-remaining guard: unknown sizes make totalBytes a lower
                // bound, so this only fires when we can prove the volume is short.
                val remaining = descriptor.totalBytes - doneBytes
                if (remaining > 0) {
                    val available = PackStoreIo.availableBytes(rootDir)
                    if (available in 0 until remaining) {
                        lastFailures[packId] = PackFailure.StorageInsufficient(remaining, available)
                        return InstallResult.Failed(lastFailures[packId]!!)
                    }
                }
            }
            return commitLocked(descriptor, staging, digests, sizes, active)
        } catch (e: kotlinx.coroutines.CancellationException) {
            lastFailures[packId] = PackFailure.Cancelled
            throw e
        } finally {
            // Retry restarts the download; incomplete staging is never resumed (honest behavior).
            if (staging.exists()) PackStoreIo.deleteRecursively(staging)
        }
    }

    /** Moves the fully verified staging dir into versions/ and flips the single active.json commit point. */
    private fun commitLocked(
        descriptor: ModelPackDescriptor,
        staging: File,
        digests: Map<String, String>,
        sizes: Map<String, Long>,
        previous: ActivePackRecord?,
    ): InstallResult {
        val packId = descriptor.id
        val versionsDir = File(packDir(packId), VERSIONS_DIR)
        val target = File(versionsDir, descriptor.version)
        val specs = descriptor.fileSpecs()
        when {
            !target.exists() -> PackStoreIo.moveIntoDir(staging, versionsDir, descriptor.version)
            else -> {
                val adoptable = specs.all { spec ->
                    val file = File(target, spec.path)
                    file.isFile && sizes.getValue(spec.path) == file.length() &&
                        digests[spec.path] == PackStoreIo.sha256Of(file)
                }
                if (!adoptable) {
                    val failure = PackFailure.Internal(
                        "Version dir ${descriptor.version} exists but is not a verified match; it is left untouched",
                    )
                    lastFailures[packId] = failure
                    return InstallResult.Failed(failure)
                }
                // Adopting a fully verified leftover from an interrupted commit; no bytes are copied.
                PackStoreIo.deleteRecursively(staging)
            }
        }
        return writeActiveRecord(descriptor, digests, sizes, previous)
    }

    /** Flips the single commit point (active.json) and reclaims unreferenced versions. */
    private fun writeActiveRecord(
        descriptor: ModelPackDescriptor,
        digests: Map<String, String>,
        sizes: Map<String, Long>,
        previous: ActivePackRecord?,
    ): InstallResult {
        val record = ActivePackRecord(
            packId = descriptor.id,
            version = descriptor.version,
            previousVersion = previous?.version,
            activatedAtEpochMs = now(),
            sourceKind = descriptor.sourceKind,
            // The record describes the bytes actually on disk — specs with
            // unknown size (-1) must never leak the sentinel into identity data.
            files = descriptor.fileSpecs().map {
                ActivePackRecord.Entry(it.path, sizes.getValue(it.path), digests.getValue(it.path))
            },
        )
        PackStoreIo.writeTextAtomic(File(packDir(descriptor.id), ACTIVE_FILE), ActivePackRecord.encode(record))
        lastFailures.remove(descriptor.id)
        gcUnreferencedVersions(descriptor.id)
        return InstallResult.Installed(descriptor.version)
    }

    // ----------------------------------------------------------------- leases

    private inner class PackLeaseImpl(
        override val packId: PackId,
        override val version: String,
        private val resolved: List<LeasedFile>,
        private val ownerJob: Job? = null,
    ) : PackLease {
        private val leaseId = "lease-${leaseCounter.incrementAndGet()}"
        private val released = AtomicBoolean(false)

        init {
            leasesByPack.getOrPut(packId) { ConcurrentHashMap() }[leaseId] = this
        }

        override fun files(): List<LeasedFile> = resolved

        override fun filesByName(): Map<String, File> = resolved.associate { it.logicalPath to it.file }

        override fun release() {
            if (!released.compareAndSet(false, true)) return
            leasesByPack[packId]?.remove(leaseId)
        }
    }

    // ------------------------------------------------------------------ store

    private fun lockFor(packId: PackId): Mutex = packLocks.getOrPut(packId) { Mutex() }

    private fun packDir(packId: PackId) = File(rootDir, packId)

    private fun versionDir(packId: PackId, version: String) = File(File(packDir(packId), VERSIONS_DIR), version)

    private fun readActive(packId: PackId): ActivePackRecord? {
        val file = File(packDir(packId), ACTIVE_FILE)
        if (!file.isFile) return null
        return ActivePackRecord.decode(file.readText())
    }

    private fun verifyActiveFiles(packId: PackId, record: ActivePackRecord): List<String>? {
        val versionDir = versionDir(packId, record.version)
        val problems = record.files.mapNotNull { entry ->
            val file = File(versionDir, entry.path)
            when {
                !file.isFile -> "${entry.path}: missing"
                file.length() != entry.sizeBytes -> "${entry.path}: size mismatch"
                PackStoreIo.sha256Of(file) != entry.sha256 -> "${entry.path}: sha256 mismatch"
                else -> null
            }
        }
        return problems.ifEmpty { null }
    }

    private fun catalogRole(packId: PackId): PackRole =
        catalogProvider().firstOrNull { it.id == packId }?.role ?: PackRole.OPTIONAL

    /**
     * Fail-closed: any unreleased lease blocks deletion and GC regardless of
     * owner state — coroutine cancellation is not proof that native inference
     * finished. Owner jobs are recorded for diagnostics; quiescence-aware
     * sweeping waits for a real engine-side closed-sessions signal, not a timer.
     */
    private fun hasLiveLeases(packId: PackId): Boolean =
        leasesByPack[packId]?.values?.isNotEmpty() == true

    /** Startup/refresh recovery: incomplete staging is swept; versions and active.json are never touched here. */
    private fun sweepStagingDirs() {
        val packs = rootDir.listFiles() ?: return
        packs.filter { it.isDirectory }.forEach(::sweepStagingDir)
    }

    private fun sweepStagingDir(packDir: File) {
        packDir.listFiles()?.filter { it.isDirectory && it.name.startsWith(STAGING_PREFIX) }?.forEach {
            PackStoreIo.deleteRecursively(it)
        }
    }

    /** Deletes only version dirs that are neither active nor retained-previous, and never under live leases. */
    private fun gcUnreferencedVersions(packId: PackId) {
        if (hasLiveLeases(packId)) return
        val record = readActive(packId) ?: return
        // previousVersion is recorded in active.json for diagnostics only; its
        // files are reclaimable as soon as no lease pins them.
        val keep = setOf(record.version)
        val versionsDir = File(packDir(packId), VERSIONS_DIR)
        versionsDir.listFiles()?.filter { it.isDirectory && it.name !in keep }?.forEach {
            PackStoreIo.deleteRecursively(it)
        }
    }

    // --------------------------------------------------------------- statuses

    private fun publishProgress(packId: PackId, progress: PackProgress) {
        rebuildStatusesLockedView(packId, PackInstallState.Installing(progress))
    }

    private fun rebuildStatuses() {
        val descriptors = catalogProvider()
        _statuses.value = descriptors.map { descriptor ->
            statusFor(descriptor, null)
        }
    }

    private fun rebuildStatusesLockedView(packId: PackId, override: PackInstallState) {
        val descriptors = catalogProvider()
        _statuses.value = descriptors.map { descriptor ->
            if (descriptor.id == packId) statusFor(descriptor, override) else statusFor(descriptor, null)
        }
    }

    private fun statusFor(descriptor: ModelPackDescriptor, override: PackInstallState?): PackStatus {
        val packId = descriptor.id
        val installState = when {
            override != null -> override
            lastFailures.containsKey(packId) -> PackInstallState.Failed(lastFailures.getValue(packId))
            else -> {
                val active = readActive(packId)
                val sourceUsable = sourceFor(descriptor) != null
                when {
                    active != null -> PackInstallState.Installed(active.version, active.previousVersion)
                    sourceUsable -> PackInstallState.Installable
                    else -> PackInstallState.NotInstallable
                }
            }
        }
        val active = readActive(packId)
        val storage = if (active == null) {
            0L
        } else {
            PackStoreIo.sizeRecursively(versionDir(packId, active.version)) +
                (active.previousVersion?.let { PackStoreIo.sizeRecursively(versionDir(packId, it)) } ?: 0L)
        }
        return PackStatus(
            id = packId,
            title = descriptor.title,
            role = descriptor.role,
            installState = installState,
            storageBytes = storage,
            sourceKind = descriptor.sourceKind,
            portability = descriptor.portability,
        )
    }

    private fun Long.ifZeroUse(fallback: Long): Long = if (this == 0L) fallback else this
}
