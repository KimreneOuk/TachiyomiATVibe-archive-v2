package eu.kanade.translation.models.imports

import eu.kanade.translation.models.DeleteResult
import eu.kanade.translation.models.ModelFileSpec
import eu.kanade.translation.models.ModelFormat
import eu.kanade.translation.models.ModelPackDescriptor
import eu.kanade.translation.models.ModelPackManager
import eu.kanade.translation.models.ModelVariantDescriptor
import eu.kanade.translation.models.PackInstallState
import eu.kanade.translation.models.PackRole
import eu.kanade.translation.models.PackSourceKind
import eu.kanade.translation.models.Portability
import eu.kanade.translation.models.ExecutionTarget
import eu.kanade.translation.models.VerifyResult
import eu.kanade.translation.models.source.ModelPackSource
import eu.kanade.translation.models.source.PackSourceException
import eu.kanade.translation.models.store.PackStoreIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Routes import-time sources to the pack manager for the duration of one install call. */
object ImportSources {
    internal val active = ConcurrentHashMap<String, ModelPackSource>()

    fun register(variantId: String, source: ModelPackSource) {
        active[variantId] = source
    }

    fun unregister(variantId: String) {
        active.remove(variantId)
    }
}

/** Shared descriptor registry: seed catalog + dynamically added imported variants. */
class MutableCatalog(seed: List<ModelPackDescriptor> = emptyList()) {
    private val descriptors = ConcurrentHashMap<String, ModelPackDescriptor>()

    init {
        seed.forEach { descriptors[it.id] = it }
    }

    fun add(descriptor: ModelPackDescriptor) {
        descriptors[descriptor.id] = descriptor
    }

    fun remove(id: String) {
        descriptors.remove(id)
    }

    fun snapshot(): List<ModelPackDescriptor> = descriptors.values.toList()
}

/** Single-file adapter: maps a spec's sourceRef (logical name) to its import source stream. */
internal class MappedImportSource(private val sources: Map<String, VariantImportSource>) : ModelPackSource {

    override val kind = PackSourceKind.USER_IMPORT

    override suspend fun copyInto(spec: eu.kanade.translation.models.ModelFileSpec, target: File): Long {
        val delegate = sources[spec.sourceRef] ?: throw PackSourceException("No import source provided for ${spec.path}")
        return try {
            delegate.copyInto(target)
        } catch (e: IOException) {
            throw PackSourceException(e.message ?: "Import source failed for ${delegate.displayName}")
        }
    }
}

interface ImportedVariantManager {
    val variantStatuses: StateFlow<List<VariantStatus>>

    suspend fun import(
        featureSeam: String,
        contract: ModelContract,
        sources: Map<String, VariantImportSource>,
    ): ImportOutcome

    suspend fun validate(variantId: String, upTo: ValidationLevel = ValidationLevel.L4_SEMANTIC_OK): ValidationReport

    suspend fun delete(variantId: String): DeleteResult
}

/**
 * Import pipeline over the crash-consistent pack store. L0 is fully working;
 * L1 compares REAL artifact metadata via the ONNX parser against the trusted
 * feature contract, and stays UNVERIFIED when the parser or trusted contract is
 * missing. L2–L4 are declared unimplemented and never fabricated.
 */
class ImportedVariantManagerImpl(
    private val manager: ModelPackManager,
    private val rootDir: File,
    private val catalog: MutableCatalog,
    private val featureContracts: Map<String, FeatureContract> = emptyMap(),
    private val now: () -> Long = System::currentTimeMillis,
) : ImportedVariantManager {

    private val reports = ConcurrentHashMap<String, ValidationReport>()
    private val importCounter = AtomicLong(0)
    private val _variantStatuses = MutableStateFlow<List<VariantStatus>>(emptyList())
    override val variantStatuses: StateFlow<List<VariantStatus>> = _variantStatuses.asStateFlow()

    override suspend fun import(
        featureSeam: String,
        contract: ModelContract,
        sources: Map<String, VariantImportSource>,
    ): ImportOutcome {
        contract.runtime.customOps.takeIf { it.isNotEmpty() }?.let { ops ->
            return ImportOutcome.Rejected(
                "Manifest declares custom op / native library requirements: ${ops.joinToString()}",
                "Re-export using only built-in runtime operators; executable payloads are never loaded",
            )
        }
        if (sources.isEmpty()) {
            return ImportOutcome.Rejected("No files provided", "Attach the model artifact and companion files")
        }
        if (contract.artifactFile !in sources) {
            return ImportOutcome.Rejected(
                "Manifest artifactFile '${contract.artifactFile}' is not among provided files",
                "Provide the artifact file named in the manifest",
            )
        }
        sources.keys.forEach { logical ->
            val invalid = logical.isEmpty() || logical == ".." || logical.startsWith(".") ||
                logical.contains('/') || logical.contains('\\') || File(logical).isAbsolute ||
                logical.any { it.code < 0x20 || it.code == 0x7F }
            if (invalid) {
                return ImportOutcome.Rejected(
                    "Invalid file name '$logical': must be a single path segment without separators or control characters",
                    "Rename files to simple names like model.onnx",
                )
            }
        }
        val caseFolded = sources.keys.map { it.lowercase() }
        if (caseFolded.size != caseFolded.distinct().size) {
            return ImportOutcome.Rejected(
                "Duplicate file names differing only by case",
                "Use unique, case-stable file names",
            )
        }
        val variantId = "import.${featureSeam.lowercase()}." +
            sanitize(sources.values.first().displayName) + "-" + importCounter.incrementAndGet()
        val descriptor = descriptorFor(variantId, featureSeam, contract, sources)
        ImportSources.register(variantId, MappedImportSource(sources))
        catalog.add(descriptor)
        val installResult = try {
            manager.install(variantId)
        } catch (e: Exception) {
            catalog.remove(variantId)
            throw e
        } finally {
            ImportSources.unregister(variantId)
        }
        val version = when (installResult) {
            is eu.kanade.translation.models.InstallResult.Installed -> descriptor.version
            is eu.kanade.translation.models.InstallResult.AlreadyInstalled -> descriptor.version
            is eu.kanade.translation.models.InstallResult.Failed -> {
                catalog.remove(variantId)
                val failure = installResult.failure
                return ImportOutcome.Rejected(
                    "Import failed: ${failure.message}",
                    if (failure.retryable) "Fix the source files and re-import" else null,
                )
            }
        }
        writeContract(variantId, version, contract)
        val report = validate(variantId, ValidationLevel.L1_CONTRACT_CHECKED)
        return ImportOutcome.Imported(variantId, version, report)
    }

    override suspend fun validate(variantId: String, upTo: ValidationLevel): ValidationReport {
        val contract = readContract(variantId)
        val levels = linkedMapOf<ValidationLevel, StageResult>()
        levels[ValidationLevel.L0_IMPORTED] = l0Result(variantId)
        levels[ValidationLevel.L1_CONTRACT_CHECKED] = contract?.let { l1Result(variantId, it) }
            ?: StageResult.Rejected("Imported manifest missing", "Re-import the variant")
        levels[ValidationLevel.L2_RUNTIME_LOAD] =
            StageResult.Unverified("Runtime load check not implemented in this prototype build")
        levels[ValidationLevel.L3_INFERENCE_OK] =
            StageResult.Unverified("Bounded inference probe not implemented in this prototype build")
        levels[ValidationLevel.L4_SEMANTIC_OK] =
            StageResult.Unverified("Semantic reference-sample validation not implemented in this prototype build")
        val report = ValidationReport(variantId, levels)
        reports[variantId] = report
        rebuildStatuses()
        return report
    }

    override suspend fun delete(variantId: String): DeleteResult {
        reports.remove(variantId)
        catalog.remove(variantId)
        return manager.delete(variantId, acknowledgeEssential = true).also { rebuildStatuses() }
    }

    // ------------------------------------------------------------------ stages

    private suspend fun l0Result(variantId: String): StageResult = when (manager.verify(variantId)) {
        VerifyResult.Verified -> StageResult.Passed
        is VerifyResult.Corrupt -> StageResult.Rejected("Stored bytes do not match recorded digests", "Re-import the variant")
        VerifyResult.NotInstalled -> StageResult.Rejected("Variant is not installed", null)
    }

    private fun l1Result(variantId: String, contract: ModelContract): StageResult {
        val version = manager.statuses.value
            .firstOrNull { it.id == variantId }?.installState as? PackInstallState.Installed
            ?: return StageResult.Unverified("Variant not active; contract check skipped")
        val artifact = versionDir(variantId, version.version, contract.artifactFile)
        if (!artifact.isFile) return StageResult.Rejected("Artifact file ${contract.artifactFile} missing", null)
        return when (contract.format.uppercase()) {
            "ONNX" -> onnxContractResult(variantId, contract, artifact)
            else -> StageResult.Unverified(
                "No metadata parser for format ${contract.format} in this prototype; declared contract stays DECLARED, not checked",
            )
        }
    }

    private fun onnxContractResult(variantId: String, contract: ModelContract, artifact: File): StageResult {
        val parsed = try {
            OnnxTensorMetadataParser.parse(artifact)
        } catch (e: OnnxTensorMetadataParser.ParseException) {
            return StageResult.Rejected(
                "Could not parse ONNX metadata: ${e.message}",
                "The file may be corrupt or not a valid ONNX model",
            )
        }
        if (parsed.externalDataDetected) {
            return StageResult.Rejected(
                "External data tensors detected; the runtime would open arbitrary files relative to the model path",
                "Re-export as a self-contained model (embedded weights) before importing",
            )
        }
        if (parsed.inputs.any { it.shape.contains(null) } || parsed.outputs.any { it.shape.contains(null) }) {
            return StageResult.Unverified(
                "Artifact has dynamic/symbolic shapes; static contract check cannot verify them",
            )
        }
        // Declared-contract check runs even without a trusted contract: the
        // artifact must at least match what the import itself claims.
        val declaredProblems = compare(parsed.inputs, contract.inputs, "input") +
            compare(parsed.outputs, contract.outputs, "output")
        val trusted = featureContracts[contract.featureSeam]
        if (trusted == null || !trusted.declared) {
            return if (declaredProblems.isEmpty()) {
                StageResult.Unverified(
                    "Artifact metadata parsed, but no trusted contract is declared for seam ${contract.featureSeam}; nothing to compare against",
                )
            } else {
                StageResult.Rejected(
                    declaredProblems.joinToString("; "),
                    "Re-export or re-declare the model so its metadata matches the import contract",
                )
            }
        }
        val problems = declaredProblems +
            compare(parsed.inputs, trusted.inputs, "input") +
            compare(parsed.outputs, trusted.outputs, "output")
        return if (problems.isEmpty()) {
            StageResult.Passed
        } else {
            StageResult.Rejected(
                problems.joinToString("; "),
                "Re-export the model to match the ${contract.featureSeam} seam contract",
            )
        }
    }

    /**
     * Exact required-name comparison in BOTH directions: unknown extras,
     * missing requireds, duplicates, dtype and shape. Comparing only what the
     * artifact declares would let a trusted contract silently pass when the
     * model drops a required tensor.
     */
    private fun compare(parsed: List<OnnxTensorMetadataParser.ParsedTensor>, trusted: List<TensorSpec>, role: String): List<String> {
        val problems = ArrayList<String>()
        trusted.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { name ->
            problems.add("$role '$name' duplicated in contract")
        }
        parsed.groupBy { it.name }.filterValues { it.size > 1 }.keys.forEach { name ->
            problems.add("artifact declares duplicate $role '$name'")
        }
        val trustedByName = trusted.associateBy { it.name }
        parsed.forEach { tensor ->
            val spec = trustedByName[tensor.name]
            if (spec == null) {
                problems.add("$role '${tensor.name}' not in contract")
                return@forEach
            }
            if (spec.shape.contains(null)) {
                problems.add("$role '${tensor.name}' contract has dynamic shape; cannot statically compare")
            } else if (spec.shape != tensor.shape) {
                problems.add("$role '${tensor.name}' shape ${tensor.shape} != contract ${spec.shape}")
            }
            if (spec.dtype != tensor.dtype) {
                problems.add("$role '${tensor.name}' dtype ${tensor.dtype} != contract ${spec.dtype}")
            }
        }
        trusted.forEach { spec ->
            if (spec.name !in parsed.map { it.name }.toSet()) {
                problems.add("$role '${spec.name}' required by contract but missing from artifact")
            }
        }
        return problems
    }

    // ------------------------------------------------------------------ store

    private fun descriptorFor(
        variantId: String,
        featureSeam: String,
        contract: ModelContract,
        sources: Map<String, VariantImportSource>,
    ): ModelPackDescriptor {
        val format = if (contract.format.equals("TFLITE", ignoreCase = true)) ModelFormat.TFLITE else ModelFormat.ONNX
        return ModelPackDescriptor(
            id = variantId,
            version = contract.variantVersion,
            role = PackRole.OPTIONAL,
            title = sources.values.first().displayName,
            licenseNote = contract.licenseNote ?: "User import — license not declared",
            sourceKind = PackSourceKind.USER_IMPORT,
            portability = Portability(format, Portability.Qualification.Unqualified),
            featureSeam = featureSeam,
            variants = listOf(
                ModelVariantDescriptor(
                    target = ExecutionTarget.CPU,
                    format = format,
                    deviceConstraint = contract.constraints.socClass,
                    files = sources.keys.map { logical ->
                        // Negative size = unknown until streamed; digests are computed from real bytes at install.
                        ModelFileSpec(logical, -1L, contract.publisherSha256[logical], logical)
                    },
                ),
            ),
        )
    }

    private fun writeContract(variantId: String, version: String, contract: ModelContract) {
        PackStoreIo.writeTextAtomic(File(File(rootDir, variantId), "contract-$version.json"), ModelContract.encode(contract))
    }

    private fun readContract(variantId: String): ModelContract? {
        val packDir = File(rootDir, variantId)
        val file = packDir.listFiles()?.firstOrNull { it.name.startsWith("contract-") } ?: return null
        return try {
            ModelContract.decode(file.readText())
        } catch (_: Exception) {
            null
        }
    }

    private fun versionDir(variantId: String, version: String, path: String): File =
        File(File(File(rootDir, variantId), "versions"), "$version/$path")

    private fun sanitize(raw: String): String =
        raw.substringAfterLast('/').replace(Regex("[^a-zA-Z0-9._-]"), "-").take(48).ifEmpty { "model" }

    // --------------------------------------------------------------- statuses

    private suspend fun rebuildStatuses() {
        val statuses = manager.statuses.value.filter { it.id.startsWith("import.") }
        _variantStatuses.value = statuses.mapNotNull { status ->
            val contract = readContract(status.id) ?: return@mapNotNull null
            val recordDigests = manager.digestsFor(status.id) ?: emptyMap()
            val provided = contract.publisherSha256.isNotEmpty()
            val matched = provided && contract.publisherSha256.all { (path, digest) -> recordDigests[path] == digest }
            VariantStatus(
                variantId = status.id,
                featureSeam = contract.featureSeam,
                version = status.installState.let { (it as? PackInstallState.Installed)?.version ?: contract.variantVersion },
                trustState = reports[status.id]?.trustState() ?: TrustState.UNTRUSTED,
                report = reports[status.id],
                storageBytes = status.storageBytes,
                originDisplayName = status.title,
                importedAtEpochMs = now(),
                fileDigests = recordDigests,
                publisherDigestsProvided = provided,
                publisherDigestsMatched = matched,
            )
        }
    }
}
