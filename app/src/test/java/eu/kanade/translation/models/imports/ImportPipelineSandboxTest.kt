package eu.kanade.translation.models.imports

import eu.kanade.translation.models.ModelPackManagerImpl
import eu.kanade.translation.models.PackInstallState
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.security.MessageDigest

/**
 * Sandbox proof for the bring-your-own-model import pipeline: real ONNX bytes
 * (hand-encoded minimal protobuf plus a real repository artifact), the L0/L1
 * trust ladder, no-silent-revert selection semantics, and the traversal guard.
 */
class ImportPipelineSandboxTest {

    @TempDir
    lateinit var tmp: File

    // ------------------------------------------------------- ONNX byte builder

    private object OnnxBytes {
        private fun varint(value: Long): ByteArray {
            var v = value
            val out = ArrayList<Byte>()
            while (v and 0x7FL.inv() != 0L) {
                out.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            out.add(v.toByte())
            return out.toByteArray()
        }

        private fun tag(field: Int, wire: Int): ByteArray = varint(((field shl 3) or wire).toLong())

        private fun lenDelimited(field: Int, payload: ByteArray): ByteArray =
            tag(field, 2) + varint(payload.size.toLong()) + payload

        private fun vint(field: Int, value: Long): ByteArray = tag(field, 0) + varint(value)

        private fun valueInfo(name: String, shape: List<Long>): ByteArray {
            val dims = shape.map { lenDelimited(2, vint(1, it)) }.reduce { a, b -> a + b }
            val tensorType = vint(1, 1) + lenDelimited(2, dims) // elem_type FLOAT + shape
            val type = lenDelimited(9, tensorType) // TypeProto.tensor_type
            return lenDelimited(1, name.toByteArray(Charsets.UTF_8)) + lenDelimited(2, type)
        }

        /** TensorProto carrying an external_data entry + data_location=EXTERNAL. */
        fun externalTensor(file: String): ByteArray {
            val entry = lenDelimited(1, file.toByteArray()) + lenDelimited(2, file.toByteArray())
            return lenDelimited(10, entry) + vint(11, 1)
        }

        /** Minimal but real ONNX ModelProto: graph inputs/outputs only, exactly what L1 reads. */
        fun buildModel(
            inputName: String,
            inputShape: List<Long>,
            outputName: String,
            outputShape: List<Long>,
            initializer: ByteArray? = null,
        ): ByteArray {
            val initializerField = initializer?.let { lenDelimited(5, it) } ?: ByteArray(0)
            val graph = initializerField +
                lenDelimited(11, valueInfo(inputName, inputShape)) +
                lenDelimited(12, valueInfo(outputName, outputShape))
            return lenDelimited(7, graph)
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    // ---------------------------------------------------------------- fixtures

    private class Fixture(
        val manager: ModelPackManagerImpl,
        val imports: ImportedVariantManagerImpl,
        val registry: FeatureModelRegistry,
        val catalog: MutableCatalog,
        val sourceRoot: File,
        val packRoot: File,
    )

    private fun setup(trusted: FeatureContract?): Fixture {
        val sourceRoot = File(tmp, "src").apply { mkdirs() }
        File(sourceRoot, "model.onnx").writeBytes(OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415)))
        File(sourceRoot, "vocab.txt").writeText("vocab")
        val packRoot = File(tmp, "packs")
        val catalog = MutableCatalog()
        val manager = ModelPackManagerImpl(
            rootDir = packRoot,
            bundledCacheRoot = File(tmp, "cache"),
            catalogProvider = { catalog.snapshot() },
            sourceFor = { ImportSources.active[it.id] },
        )
        val imports = ImportedVariantManagerImpl(
            manager = manager,
            rootDir = packRoot,
            catalog = catalog,
            featureContracts = trusted?.let { mapOf(it.featureSeam to it) } ?: emptyMap(),
        )
        val registry = FeatureModelRegistry(
            manager = manager,
            imports = imports,
            catalog = { catalog.snapshot() },
            selectionFile = File(tmp, "selection.json"),
        )
        return Fixture(manager, imports, registry, catalog, sourceRoot, packRoot)
    }

    private fun contract(
        seam: String = "TEST_ONNX",
        format: String = "ONNX",
        trustedOutputShape: List<Long?> = listOf(1L, 9415L),
        publisher: Map<String, String> = emptyMap(),
        customOps: List<String> = emptyList(),
    ) = ModelContract(
        featureSeam = seam,
        artifactFile = "model.onnx",
        format = format,
        inputs = listOf(TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32")),
        outputs = listOf(TensorSpec("logits", trustedOutputShape, "float32")),
        runtime = RuntimeRequirements(customOps = customOps),
        publisherSha256 = publisher,
    )

    private fun sources(f: Fixture): Map<String, VariantImportSource> = mapOf(
        "model.onnx" to LocalDirectoryImportSource(f.sourceRoot, "model.onnx"),
        "vocab.txt" to LocalDirectoryImportSource(f.sourceRoot, "vocab.txt"),
    )

    // ----------------------------------------------------------- parser checks

    @Test
    fun `parses minimal real onnx io metadata`() {
        val bytes = OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415))
        val io = OnnxTensorMetadataParser.parse(bytes)
        io.inputs shouldBe listOf(OnnxTensorMetadataParser.ParsedTensor("x", listOf(1L, 1L, 224L, 224L), "float32"))
        io.outputs shouldBe listOf(OnnxTensorMetadataParser.ParsedTensor("logits", listOf(1L, 9415L), "float32"))
    }

    @Test
    fun `parses a real repository onnx artifact when present`() {
        val real = File("..", "test_conv_seq.onnx")
        assumeTrue(real.isFile, "repository fixture not present")
        val io = OnnxTensorMetadataParser.parse(real)
        assertTrue(io.inputs.isNotEmpty(), "real artifact must expose graph inputs")
        assertTrue(io.outputs.isNotEmpty(), "real artifact must expose graph outputs")
    }

    // ------------------------------------------------------------ import rules

    @Test
    fun `custom op declarations are rejected before any store write`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        val outcome = f.imports.import("TEST_ONNX", contract(customOps = listOf("libfoo.so")), sources(f))
        outcome.shouldBeInstanceOf<ImportOutcome.Rejected>()
        outcome.reason shouldContain "custom op"
        assertTrue(f.packRoot.listFiles()?.isEmpty() ?: true, "no store writes may happen on rejection")
    }

    @Test
    fun `path traversal file names are rejected`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        val bad = mapOf("../evil.onnx" to LocalDirectoryImportSource(f.sourceRoot, "model.onnx"))
        val outcome = f.imports.import("TEST_ONNX", contract(artifactFileOverride = "../evil.onnx"), bad)
        outcome.shouldBeInstanceOf<ImportOutcome.Rejected>()
        outcome.reason shouldContain "Invalid file name"
    }

    @Test
    fun `valid onnx import passes L0 and L1 and becomes selectable`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true, inputs = listOf(TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32")), outputs = listOf(TensorSpec("logits", listOf(1L, 9415L), "float32"))))
        val publisher = mapOf("model.onnx" to sha256(File(f.sourceRoot, "model.onnx")))
        val outcome = f.imports.import("TEST_ONNX", contract(publisher = publisher), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        imported.report.levels[ValidationLevel.L0_IMPORTED] shouldBe StageResult.Passed
        imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED] shouldBe StageResult.Passed
        imported.report.trustState() shouldBe TrustState.VALIDATED

        val status = f.imports.variantStatuses.value.single()
        status.publisherDigestsProvided shouldBe true
        status.publisherDigestsMatched shouldBe true

        val option = f.registry.options("TEST_ONNX").first { it.ref == FeatureModelRegistry.Ref.imported(imported.variantId) }
        option.selectable shouldBe true

        f.registry.select("TEST_ONNX", option.ref) shouldBe FeatureModelRegistry.SelectionResult.Selected(option.ref)
        f.registry.selected("TEST_ONNX").shouldBeInstanceOf<FeatureModelRegistry.Selection.VariantChosen>()

        // Persistence: a fresh registry over the same files keeps the explicit choice.
        val registry2 = FeatureModelRegistry(f.manager, f.imports, { emptyList() }, File(f.packRoot.parentFile, "selection.json"))
        registry2.selected("TEST_ONNX").shouldBeInstanceOf<FeatureModelRegistry.Selection.VariantChosen>()

        f.registry.rollback("TEST_ONNX")
        f.registry.selected("TEST_ONNX") shouldBe FeatureModelRegistry.Selection.BundledDefault
    }

    @Test
    fun `contract mismatch is rejected and never selectable`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true, outputs = listOf(TensorSpec("logits", listOf(1L, 100L), "float32"))))
        val outcome = f.imports.import("TEST_ONNX", contract(), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Rejected>()
        l1.reason shouldContain "shape"
        imported.report.trustState() shouldBe TrustState.REJECTED
        val option = f.registry.options("TEST_ONNX").last()
        option.selectable shouldBe false
        f.registry.select("TEST_ONNX", option.ref).shouldBeInstanceOf<FeatureModelRegistry.SelectionResult.Refused>()
    }

    @Test
    fun `tflite stays unverified and selection requires explicit acknowledgement`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        val outcome = f.imports.import("TEST_ONNX", contract(format = "TFLITE"), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Unverified>()
        l1.reason shouldContain "No metadata parser"
        imported.report.trustState() shouldBe TrustState.IMPORTED

        val ref = FeatureModelRegistry.Ref.imported(imported.variantId)
        f.registry.select("TEST_ONNX", ref).shouldBeInstanceOf<FeatureModelRegistry.SelectionResult.Refused>()
        f.registry.select("TEST_ONNX", ref, acknowledgeUnverified = true)
            .shouldBeInstanceOf<FeatureModelRegistry.SelectionResult.Selected>()
    }

    @Test
    fun `seam without trusted contract stays unverified`() = runBlocking<Unit> {
        val f = setup(trusted = null)
        val outcome = f.imports.import("OCR_MANGAOCR", contract(seam = "OCR_MANGAOCR"), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Unverified>()
        l1.reason shouldContain "no trusted contract"
    }

    @Test
    fun `deleting the selected variant surfaces unavailable instead of silent revert`() = runBlocking<Unit> {
        // The variant must be VALIDATED (trusted contract + matching publisher digests)
        // so the selection is accepted without unverified acknowledgement.
        val f = setup(
            FeatureContract(
                "TEST_ONNX",
                declared = true,
                inputs = listOf(TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32")),
                outputs = listOf(TensorSpec("logits", listOf(1L, 9415L), "float32")),
            ),
        )
        val publisher = mapOf("model.onnx" to sha256(File(f.sourceRoot, "model.onnx")))
        val outcome = f.imports.import("TEST_ONNX", contract(publisher = publisher), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val ref = FeatureModelRegistry.Ref.imported(imported.variantId)
        f.registry.select("TEST_ONNX", ref).shouldBeInstanceOf<FeatureModelRegistry.SelectionResult.Selected>()

        f.imports.delete(imported.variantId)
        val selection = f.registry.selected("TEST_ONNX")
        selection.shouldBeInstanceOf<FeatureModelRegistry.Selection.Unavailable>()
        selection.reason shouldContain "Recovery"
        f.registry.rollback("TEST_ONNX")
        f.registry.selected("TEST_ONNX") shouldBe FeatureModelRegistry.Selection.BundledDefault
    }

    @Test
    fun `publisher digest mismatch fails the import`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        val outcome = f.imports.import("TEST_ONNX", contract(publisher = mapOf("model.onnx" to "00")), sources(f))
        outcome.shouldBeInstanceOf<ImportOutcome.Rejected>()
        outcome.reason shouldContain "SHA-256 mismatch"
    }

    @Test
    fun `installed pack survives manager process death and imports never bind to consumers`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        val outcome = f.imports.import("TEST_ONNX", contract(), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        // Simulate process death: fresh manager over the same disk state.
        val manager2 = ModelPackManagerImpl(
            rootDir = f.packRoot,
            bundledCacheRoot = File(tmp, "cache"),
            catalogProvider = { f.catalog.snapshot() },
            sourceFor = { ImportSources.active[it.id] },
        )
        manager2.refresh()
        manager2.verify(imported.variantId) shouldBe eu.kanade.translation.models.VerifyResult.Verified
        manager2.statuses.value.single().installState shouldBe PackInstallState.Installed("1.0.0", null)
        // Named invariant: acquired leases are for catalog packs only; imports stay unbindable until L2/L3 exists.
        manager2.acquire(imported.variantId) shouldBe null
    }

    @Test
    fun `external data tensors are rejected as non-self-contained`() = runBlocking<Unit> {
        val f = setup(FeatureContract("TEST_ONNX", declared = true))
        // Overwrite the fixture model AFTER setup: setup writes a plain model.
        val tensor = OnnxBytes.externalTensor("weights.bin")
        val bytes = OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415), initializer = tensor)
        File(f.sourceRoot, "model.onnx").writeBytes(bytes)
        val outcome = f.imports.import("TEST_ONNX", contract(), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Rejected>()
        l1.reason shouldContain "External data tensors"
    }

    @Test
    fun `parser flags external data at the wire-format level`() {
        val tensor = OnnxBytes.externalTensor("weights.bin")
        val bytes = OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415), initializer = tensor)
        OnnxTensorMetadataParser.parse(bytes).externalDataDetected shouldBe true
        val plain = OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415))
        OnnxTensorMetadataParser.parse(plain).externalDataDetected shouldBe false
    }

    @Test
    fun `oversized artifacts are rejected honestly without loading`() {
        shouldThrow<OnnxTensorMetadataParser.ParseException> {
            OnnxTensorMetadataParser.requireParsableSize(OnnxTensorMetadataParser.MAX_PARSE_BYTES + 1, "big.onnx")
        }
        // Boundary: exactly the limit is accepted.
        OnnxTensorMetadataParser.requireParsableSize(OnnxTensorMetadataParser.MAX_PARSE_BYTES, "ok.onnx")
    }

    @Test
    fun `truncated and malformed protobuf fails parsing instead of guessing`() {
        val bytes = OnnxBytes.buildModel("x", listOf(1, 1, 224, 224), "logits", listOf(1, 9415))
        shouldThrow<OnnxTensorMetadataParser.ParseException> {
            OnnxTensorMetadataParser.parse(bytes.copyOfRange(0, bytes.size - 2))
        }
        // Field 1 with group wire type (3) is unsupported legacy protobuf.
        shouldThrow<OnnxTensorMetadataParser.ParseException> { OnnxTensorMetadataParser.parse(byteArrayOf(0x0B)) }
        // Truncated varint: continuation byte with no terminator.
        shouldThrow<OnnxTensorMetadataParser.ParseException> { OnnxTensorMetadataParser.parse(byteArrayOf(0x80.toByte())) }
    }

    @Test
    fun `trusted contract with unmet required tensor is rejected`() = runBlocking<Unit> {
        val f = setup(
            FeatureContract(
                "TEST_ONNX",
                declared = true,
                inputs = listOf(
                    TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32"),
                    TensorSpec("mask", listOf(1L, 224L, 224L), "float32"),
                ),
                outputs = listOf(TensorSpec("logits", listOf(1L, 9415L), "float32")),
            ),
        )
        val outcome = f.imports.import("TEST_ONNX", contract(), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Rejected>()
        l1.reason shouldContain "missing from artifact"
    }

    @Test
    fun `duplicate trusted contract names are rejected`() = runBlocking<Unit> {
        val spec = TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32")
        val f = setup(FeatureContract("TEST_ONNX", declared = true, inputs = listOf(spec, spec)))
        val outcome = f.imports.import("TEST_ONNX", contract(), sources(f))
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Rejected>()
        l1.reason shouldContain "duplicated in contract"
    }

    @Test
    fun `artifact not matching its own declared contract is rejected even without trusted`() = runBlocking<Unit> {
        val f = setup(trusted = null)
        val outcome = f.imports.import(
            "OCR_MANGAOCR",
            contract(seam = "OCR_MANGAOCR", trustedOutputShape = listOf(1L, 100L)),
            sources(f),
        )
        val imported = outcome.shouldBeInstanceOf<ImportOutcome.Imported>()
        val l1 = imported.report.levels[ValidationLevel.L1_CONTRACT_CHECKED]
        l1.shouldBeInstanceOf<StageResult.Rejected>()
        l1.reason shouldContain "shape"
    }
}

private fun contract(
    seam: String = "TEST_ONNX",
    format: String = "ONNX",
    trustedOutputShape: List<Long?> = listOf(1L, 9415L),
    publisher: Map<String, String> = emptyMap(),
    customOps: List<String> = emptyList(),
    artifactFileOverride: String? = null,
) = ModelContract(
    featureSeam = seam,
    artifactFile = artifactFileOverride ?: "model.onnx",
    format = format,
    inputs = listOf(TensorSpec("x", listOf(1L, 1L, 224L, 224L), "float32")),
    outputs = listOf(TensorSpec("logits", trustedOutputShape, "float32")),
    runtime = RuntimeRequirements(customOps = customOps),
    publisherSha256 = publisher,
)
