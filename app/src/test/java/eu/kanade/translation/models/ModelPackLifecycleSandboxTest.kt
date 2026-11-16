package eu.kanade.translation.models

import eu.kanade.translation.models.source.LocalDirectoryPackSource
import eu.kanade.translation.models.source.ModelPackSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Test-only lifecycle proof of the crash-consistent pack store: install →
 * verify → activate → lease → consume → release → delete, plus interruption,
 * upgrade retention, and TOCTOU race invariants. Uses a locally generated
 * fixture whose digests are computed from real bytes — no invented hashes.
 */
class ModelPackLifecycleSandboxTest {

    @TempDir
    lateinit var tmp: File

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    private fun materializeFixture(sourceRoot: File, version: String) {
        sourceRoot.mkdirs()
        File(sourceRoot, "fixture-model-$version.bin").writeBytes(ByteArray(1024) { (it % 251).toByte() })
        File(sourceRoot, "fixture-vocab-$version.txt").writeText("vocab-$version")
    }

    private fun fixtureDescriptor(version: String, sourceRoot: File, pinOverride: Map<String, String> = emptyMap()) =
        ModelPackDescriptor(
            id = "fixture",
            version = version,
            role = PackRole.OPTIONAL,
            title = "Fixture pack (test-only sandbox)",
            licenseNote = "local test fixture",
            sourceKind = PackSourceKind.LOCAL_DIR,
            portability = Portability(ModelFormat.ONNX, Portability.Qualification.Unqualified),
            variants = listOf(
                ModelVariantDescriptor(
                    target = ExecutionTarget.CPU,
                    format = ModelFormat.ONNX,
                    deviceConstraint = null,
                    files = listOf(
                        ModelFileSpec(
                            "model.bin",
                            1024L,
                            pinOverride["model.bin"] ?: sha256(File(sourceRoot, "fixture-model-$version.bin")),
                            "fixture-model-$version.bin",
                        ),
                        ModelFileSpec(
                            "vocab.txt",
                            -1L,
                            pinOverride["vocab.txt"] ?: sha256(File(sourceRoot, "fixture-vocab-$version.txt")),
                            "fixture-vocab-$version.txt",
                        ),
                    ),
                ),
            ),
        )

    private class CountingSource(private val delegate: ModelPackSource) : ModelPackSource by delegate {
        var copies = 0
        override suspend fun copyInto(spec: ModelFileSpec, target: File): Long {
            copies++
            return delegate.copyInto(spec, target)
        }
    }

    /** Failure injection: the volume gives out mid-copy. */
    private class FailingSource : ModelPackSource {
        override val kind = PackSourceKind.LOCAL_DIR

        override suspend fun copyInto(spec: ModelFileSpec, target: File): Long =
            throw java.io.IOException("No space left on device (injected)")
    }

    private fun newManager(
        root: File,
        sourceRoot: File?,
        counting: CountingSource? = null,
        sourceOverride: ModelPackSource? = null,
        catalog: () -> List<ModelPackDescriptor>,
    ): ModelPackManagerImpl = ModelPackManagerImpl(
        rootDir = root,
        bundledCacheRoot = File(root.parent, "bundled-cache"),
        catalogProvider = catalog,
        sourceFor = { descriptor ->
            sourceOverride ?: when {
                counting != null && descriptor.sourceKind == PackSourceKind.LOCAL_DIR -> counting
                sourceRoot != null && descriptor.sourceKind == PackSourceKind.LOCAL_DIR ->
                    LocalDirectoryPackSource(sourceRoot)
                else -> null
            }
        },
    )

    @Test
    fun `lifecycle install acquire consume release delete`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        val root = File(tmp, "packs")
        val manager = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }

        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")

        val lease = manager.acquire("fixture")
        lease shouldNotBe null
        // Fake consumer: re-hash leased bytes against the recorded identity digest.
        lease!!.files().forEach { leased -> sha256(leased.file) shouldBe leased.sha256 }
        lease.filesByName()["model.bin"]!!.length() shouldBe 1024L

        manager.release(lease)
        val deleted = manager.delete("fixture")
        assertTrue(deleted is DeleteResult.Deleted, "delete should succeed once unleased")
        manager.acquire("fixture") shouldBe null
    }

    @Test
    fun `interrupted staging is swept and active version survives process death`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        val root = File(tmp, "packs")
        val manager = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }
        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")

        val junk = File(root, "fixture/staging-junk").apply { mkdirs() }
        File(junk, "model.bin.part").writeBytes(ByteArray(10))
        File(root, "fixture/active.json.tmp").writeText("{ truncated garbage")

        val manager2 = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }
        manager2.refresh()
        File(root, "fixture/staging-junk").exists() shouldBe false
        manager2.verify("fixture") shouldBe VerifyResult.Verified
        manager2.acquire("fixture")!!.version shouldBe "1.0.0"
    }

    @Test
    fun `failed verification leaves previous active version untouched`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        materializeFixture(sourceRoot, "2.0.0")
        val root = File(tmp, "packs")
        val catalogRef = mutableListOf(fixtureDescriptor("1.0.0", sourceRoot))
        val manager = newManager(root, sourceRoot) { catalogRef.toList() }

        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")
        catalogRef.clear()
        catalogRef.add(fixtureDescriptor("2.0.0", sourceRoot, pinOverride = mapOf("model.bin" to "00")))
        val result = manager.install("fixture")
        assertTrue(result is InstallResult.Failed, "wrong pinned digest must fail install")
        manager.verify("fixture") shouldBe VerifyResult.Verified
        manager.acquire("fixture")!!.version shouldBe "1.0.0"
    }

    @Test
    fun `upgrade retains previous version while leased and reclaims after release`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        materializeFixture(sourceRoot, "2.0.0")
        val root = File(tmp, "packs")
        val catalogRef = mutableListOf(fixtureDescriptor("1.0.0", sourceRoot))
        val manager = newManager(root, sourceRoot) { catalogRef.toList() }

        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")
        val lease = manager.acquire("fixture")!!

        catalogRef.clear()
        catalogRef.add(fixtureDescriptor("2.0.0", sourceRoot))
        manager.install("fixture") shouldBe InstallResult.Installed("2.0.0")
        File(root, "fixture/versions/1.0.0").exists() shouldBe true
        manager.statuses.value.first().installState shouldBe PackInstallState.Installed("2.0.0", "1.0.0")

        manager.release(lease)
        manager.refresh()
        File(root, "fixture/versions/1.0.0").exists() shouldBe false
        manager.acquire("fixture")!!.version shouldBe "2.0.0"
    }

    @Test
    fun `delete is refused while a lease is live`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        val root = File(tmp, "packs")
        val manager = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }
        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")

        val lease = manager.acquire("fixture")!!
        manager.delete("fixture") shouldBe DeleteResult.Blocked(DeleteBlockReason.LIVE_LEASES)
        manager.release(lease)
        assertTrue(manager.delete("fixture") is DeleteResult.Deleted)
    }

    @Test
    fun `acquire and delete race never produce mixed state`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        repeat(10) { iteration ->
            val root = File(tmp, "race-$iteration")
            val manager = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }
            manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")
            val acquireJob = async(Dispatchers.Default) { manager.acquire("fixture") }
            val deleteJob = async(Dispatchers.Default) { manager.delete("fixture", acknowledgeEssential = true) }
            val lease = acquireJob.await()
            val result = deleteJob.await()
            if (lease != null) {
                result shouldBe DeleteResult.Blocked(DeleteBlockReason.LIVE_LEASES)
                assertTrue(File(root, "fixture").exists(), "pack must survive while leased")
                manager.release(lease)
            } else {
                assertTrue(result is DeleteResult.Deleted, "delete must succeed when it wins the race")
            }
        }
    }

    @Test
    fun `same version reinstall is idempotent`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        val root = File(tmp, "packs")
        val manager = newManager(root, sourceRoot) { listOf(fixtureDescriptor("1.0.0", sourceRoot)) }
        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")
        manager.install("fixture") shouldBe InstallResult.AlreadyInstalled("1.0.0")
    }

    @Test
    fun `staging io failure is reported and leaves no active state`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        val root = File(tmp, "packs-io")
        val manager = newManager(root, null, sourceOverride = FailingSource()) {
            listOf(fixtureDescriptor("1.0.0", sourceRoot))
        }
        val result = manager.install("fixture")
        result.shouldBeInstanceOf<InstallResult.Failed>()
        (result as InstallResult.Failed).failure.message shouldContain "injected"
        // Staging swept, nothing active, nothing leasable.
        manager.verify("fixture") shouldBe VerifyResult.NotInstalled
        manager.acquire("fixture") shouldBe null
        root.walk().any { it.name.startsWith("staging-") } shouldBe false
    }

    @Test
    fun `commit failure keeps previous active readable`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        materializeFixture(sourceRoot, "2.0.0")
        val root = File(tmp, "packs-commit")
        val catalogRef = mutableListOf(fixtureDescriptor("1.0.0", sourceRoot))
        val manager = newManager(root, sourceRoot) { catalogRef.toList() }
        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")

        // Sabotage the commit temp path so the atomic replace cannot even start.
        File(root, "fixture/active.json.tmp").mkdirs()
        catalogRef.clear()
        catalogRef.add(fixtureDescriptor("2.0.0", sourceRoot))
        val result = manager.install("fixture")
        result.shouldBeInstanceOf<InstallResult.Failed>()

        // Previous commit remains readable and verified; the version-dir
        // leftover stays adoptable, never a partial commit.
        manager.verify("fixture") shouldBe VerifyResult.Verified
        manager.acquire("fixture")!!.version shouldBe "1.0.0"
        File(root, "fixture/active.json.tmp").delete()
    }

    @Test
    fun `unknown file sizes clamp total bytes to a non-negative bound`() {
        val descriptor = ModelPackDescriptor(
            id = "fixture",
            version = "1.0.0",
            role = PackRole.OPTIONAL,
            title = "t",
            licenseNote = "l",
            sourceKind = PackSourceKind.LOCAL_DIR,
            portability = Portability(ModelFormat.ONNX, Portability.Qualification.Unqualified),
            variants = listOf(
                ModelVariantDescriptor(
                    target = ExecutionTarget.CPU,
                    format = ModelFormat.ONNX,
                    deviceConstraint = null,
                    files = listOf(
                        ModelFileSpec("a.bin", -1L, null, "a"),
                        ModelFileSpec("b.bin", -1L, null, "b"),
                    ),
                ),
            ),
        )
        descriptor.totalBytes shouldBe 0L
    }

    @Test
    fun `metered HTTP install requires explicit confirmation before copying`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "metered-source")
        materializeFixture(sourceRoot, "1.0.0")
        val descriptor = fixtureDescriptor("1.0.0", sourceRoot).let { original ->
            original.copy(
                sourceKind = PackSourceKind.HTTP,
                variants = original.variants.map { variant ->
                    variant.copy(files = variant.files.map { spec ->
                        spec.copy(sizeBytes = File(sourceRoot, spec.sourceRef!!).length())
                    })
                },
            )
        }
        val source = CountingSource(LocalDirectoryPackSource(sourceRoot))
        val manager = ModelPackManagerImpl(
            rootDir = File(tmp, "metered-packs"),
            bundledCacheRoot = File(tmp, "metered-cache"),
            catalogProvider = { listOf(descriptor) },
            sourceFor = { source },
            networkMeteredProvider = { true },
        )

        manager.install("fixture") shouldBe InstallResult.Failed(PackFailure.MeteredConfirmationRequired)
        source.copies shouldBe 0
        manager.install("fixture", confirmMetered = true) shouldBe InstallResult.Installed("1.0.0")
        source.copies shouldBe 2
    }

    /**
     * Mirrors HttpPackSource's per-read ensureActive check in a drip source,
     * then exercises the manager path: cancel(packId) mid-copy must surface
     * CancellationException, sweep staging, and leave nothing active or
     * leasable, with the failure recorded as PackFailure.Cancelled.
     */
    @Test
    fun `cancel mid-stream sweeps staging and never activates`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "cancel-src")
        materializeFixture(sourceRoot, "1.0.0")
        val descriptor = fixtureDescriptor("1.0.0", sourceRoot)
        val firstByte = CompletableDeferred<Unit>()
        val source = object : ModelPackSource {
            override val kind = PackSourceKind.LOCAL_DIR

            override suspend fun copyInto(spec: ModelFileSpec, target: File): Long = withContext(Dispatchers.IO) {
                target.outputStream().use { out ->
                    for (i in 0 until 4096) {
                        currentCoroutineContext().ensureActive()
                        if (i == 1) firstByte.complete(Unit)
                        out.write(0x42)
                        Thread.sleep(5)
                    }
                }
                4096L
            }
        }
        val root = File(tmp, "cancel-packs")
        val manager = newManager(root, null, sourceOverride = source) {
            listOf(descriptor)
        }

        val install = async { manager.install("fixture") }
        firstByte.await()
        manager.cancel("fixture")
        val cancelled = try {
            install.await()
            false
        } catch (e: CancellationException) {
            true
        }
        assertTrue(cancelled, "install must surface CancellationException after cancel(packId)")

        assertTrue(
            root.walk().none { it.name.startsWith("staging-") },
            "incomplete staging must be swept after cancellation",
        )
        manager.verify("fixture") shouldBe VerifyResult.NotInstalled
        manager.acquire("fixture") shouldBe null
        manager.statuses.value.first().installState shouldBe PackInstallState.Failed(PackFailure.Cancelled)
    }

    @Test
    fun `refresh does not sweep staging while an install is active`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "refresh-source")
        materializeFixture(sourceRoot, "1.0.0")
        val delegate = LocalDirectoryPackSource(sourceRoot)
        val started = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val source = object : ModelPackSource by delegate {
            override suspend fun copyInto(spec: ModelFileSpec, target: File): Long {
                val written = delegate.copyInto(spec, target)
                if (!started.isCompleted) {
                    started.complete(Unit)
                    resume.await()
                }
                return written
            }
        }
        val root = File(tmp, "refresh-packs")
        val manager = newManager(root, null, sourceOverride = source) {
            listOf(fixtureDescriptor("1.0.0", sourceRoot))
        }
        val install = async { manager.install("fixture") }
        started.await()
        val refresh = async { manager.refresh() }
        withTimeoutOrNull(250) { refresh.await() } shouldBe null
        root.walk().any { it.name.startsWith("staging-") } shouldBe true
        resume.complete(Unit)

        install.await() shouldBe InstallResult.Installed("1.0.0")
        refresh.await()
        manager.verify("fixture") shouldBe VerifyResult.Verified
    }

    @Test
    fun `verified local recovery needs no metered consent`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "recovery-source")
        materializeFixture(sourceRoot, "1.0.0")
        val descriptor = fixtureDescriptor("1.0.0", sourceRoot).copy(sourceKind = PackSourceKind.HTTP)
        val root = File(tmp, "recovery-packs")
        val leftover = File(root, "fixture/versions/1.0.0").apply { mkdirs() }
        File(sourceRoot, "fixture-model-1.0.0.bin").copyTo(File(leftover, "model.bin"))
        File(sourceRoot, "fixture-vocab-1.0.0.txt").copyTo(File(leftover, "vocab.txt"))
        val source = CountingSource(LocalDirectoryPackSource(sourceRoot))
        val manager = ModelPackManagerImpl(
            rootDir = root,
            bundledCacheRoot = File(tmp, "recovery-cache"),
            catalogProvider = { listOf(descriptor) },
            sourceFor = { source },
            networkMeteredProvider = { true },
        )

        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")
        source.copies shouldBe 0
    }

    @Test
    fun `verified pack survives app update and adopts interrupted commit leftover without re-copy`() = runBlocking<Unit> {
        val sourceRoot = File(tmp, "src")
        materializeFixture(sourceRoot, "1.0.0")
        materializeFixture(sourceRoot, "2.0.0")
        val root = File(tmp, "packs")
        val v1 = fixtureDescriptor("1.0.0", sourceRoot)
        val v2 = fixtureDescriptor("2.0.0", sourceRoot)
        val manager = newManager(root, sourceRoot) { listOf(v1) }
        manager.install("fixture") shouldBe InstallResult.Installed("1.0.0")

        // Crash after the version dir landed but before the active.json commit:
        // leftover versions/2.0.0 is present, active stays v1.
        val leftover = File(root, "fixture/versions/2.0.0").apply { mkdirs() }
        File(sourceRoot, "fixture-model-2.0.0.bin").copyTo(File(leftover, "model.bin"))
        File(sourceRoot, "fixture-vocab-2.0.0.txt").copyTo(File(leftover, "vocab.txt"))

        val counting = CountingSource(LocalDirectoryPackSource(sourceRoot))
        val manager2 = newManager(root, null, counting) { listOf(v2) }
        manager2.install("fixture") shouldBe InstallResult.Installed("2.0.0")
        counting.copies shouldBe 0

        // App update: fresh instance, nothing re-downloaded or invalidated.
        val manager3 = newManager(root, null) { listOf(v2) }
        manager3.verify("fixture") shouldBe VerifyResult.Verified
        manager3.acquire("fixture")!!.version shouldBe "2.0.0"
    }
}
