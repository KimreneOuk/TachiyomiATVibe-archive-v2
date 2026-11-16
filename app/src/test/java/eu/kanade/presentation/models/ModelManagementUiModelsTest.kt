package eu.kanade.presentation.models

import eu.kanade.translation.models.DeleteBlockReason
import eu.kanade.translation.models.ExecutionTarget
import eu.kanade.translation.models.ModelFileSpec
import eu.kanade.translation.models.ModelFormat
import eu.kanade.translation.models.ModelPackDescriptor
import eu.kanade.translation.models.ModelVariantDescriptor
import eu.kanade.translation.models.PackFailure
import eu.kanade.translation.models.PackInstallState
import eu.kanade.translation.models.PackProgress
import eu.kanade.translation.models.PackRole
import eu.kanade.translation.models.PackSourceKind
import eu.kanade.translation.models.PackStatus
import eu.kanade.translation.models.Portability
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

private fun status(
    installState: PackInstallState,
    qualification: Portability.Qualification = Portability.Qualification.Unqualified,
) = PackStatus(
    id = "mangaocr",
    title = "MangaOCR (runtime pack)",
    role = PackRole.ESSENTIAL,
    installState = installState,
    storageBytes = 64_758_045L,
    sourceKind = PackSourceKind.ASSET_MIRROR,
    portability = Portability(ModelFormat.ONNX, qualification),
)

private fun descriptor(deviceConstraint: String? = null) = ModelPackDescriptor(
    id = "mangaocr",
    version = "1.0.0",
    role = PackRole.ESSENTIAL,
    title = "MangaOCR (runtime pack)",
    licenseNote = "test",
    sourceKind = PackSourceKind.ASSET_MIRROR,
    portability = Portability(ModelFormat.ONNX, Portability.Qualification.Unqualified),
    variants = listOf(
        ModelVariantDescriptor(
            target = ExecutionTarget.CPU,
            format = ModelFormat.ONNX,
            deviceConstraint = deviceConstraint,
            files = listOf(ModelFileSpec("encoder.onnx", 17_070_003L, null, "models/ocr/encoder.onnx")),
        ),
    ),
)

class ModelManagementUiModelsTest {

    @Test
    fun `installable state maps to install-only actions`() {
        val ui = modelPackUi(status(PackInstallState.Installable), descriptor())
        ui.state shouldBe PackUiState.INSTALLABLE
        val actions = modelPackActions(ui)
        actions.install.shouldBeTrue()
        actions.cancel.shouldBeFalse()
        actions.verify.shouldBeFalse()
        actions.delete.shouldBeFalse()
    }

    @Test
    fun `installing state exposes progress and only cancel`() {
        val progress = PackProgress(doneBytes = 17_070_003L, totalBytes = 64_758_045L, filesDone = 1, filesTotal = 4)
        val ui = modelPackUi(status(PackInstallState.Installing(progress)), descriptor())
        ui.state shouldBe PackUiState.INSTALLING
        ui.progress shouldBe progress
        val actions = modelPackActions(ui)
        actions.cancel.shouldBeTrue()
        actions.install.shouldBeFalse()
        actions.delete.shouldBeFalse()
    }

    @Test
    fun `installed state carries version, retention and verify-delete actions`() {
        val ui = modelPackUi(status(PackInstallState.Installed("1.0.0", "0.9.0")), descriptor())
        ui.state shouldBe PackUiState.INSTALLED
        ui.version shouldBe "1.0.0"
        ui.previousVersionKept shouldBe "0.9.0"
        val actions = modelPackActions(ui)
        actions.verify.shouldBeTrue()
        actions.delete.shouldBeTrue()
        actions.install.shouldBeFalse()
    }

    @Test
    fun `failed state re-offers install only when retryable`() {
        val retryable = modelPackActions(
            modelPackUi(status(PackInstallState.Failed(PackFailure.SourceUnavailable("offline"))), descriptor()),
        )
        retryable.install.shouldBeTrue()
        val terminal = modelPackActions(
            modelPackUi(status(PackInstallState.Failed(PackFailure.Internal("boom"))), descriptor()),
        )
        terminal.install.shouldBeFalse()
        terminal.verify.shouldBeFalse()
        terminal.delete.shouldBeFalse()
    }

    @Test
    fun `not installable disables every action`() {
        val actions = modelPackActions(modelPackUi(status(PackInstallState.NotInstallable), descriptor()))
        listOf(actions.install, actions.cancel, actions.verify, actions.delete).forEach { flag ->
            withClue("action must be disabled") { flag.shouldBeFalse() }
        }
    }

    @Test
    fun `device constraint and qualification surface as honest badges`() {
        val locked = modelPackUi(status(PackInstallState.Installable), descriptor(deviceConstraint = "SM8650"))
        locked.deviceLocked.shouldBeTrue()
        val unlocked = modelPackUi(status(PackInstallState.Installable), descriptor())
        unlocked.deviceLocked.shouldBeFalse()

        val qualified = modelPackUi(
            status(
                PackInstallState.Installable,
                Portability.Qualification.DeviceQualified("SM8650", "bench/2026-q3"),
            ),
            descriptor(),
        )
        qualified.qualified.shouldBeTrue()
        modelPackUi(status(PackInstallState.Installable), descriptor()).qualified.shouldBeFalse()
    }

    @Test
    fun `delete block reasons map to distinct ui explanations`() {
        deleteBlockUi(DeleteBlockReason.LIVE_LEASES) shouldBe DeleteBlockUi.LIVE_LEASES
        deleteBlockUi(DeleteBlockReason.ESSENTIAL_ACK_REQUIRED) shouldBe DeleteBlockUi.ESSENTIAL_ACK
        deleteBlockUi(DeleteBlockReason.NOT_INSTALLED) shouldBe DeleteBlockUi.NOT_INSTALLED
    }

    @Test
    fun `byte formatting stays human readable and honest for unknown sizes`() {
        formatBytesForUi(-1L) shouldBe "?"
        formatBytesForUi(512L) shouldBe "512 B"
        formatBytesForUi(37_193L) shouldBe "36.3 KB"
        formatBytesForUi(64_758_045L) shouldBe "61.8 MB"
        formatBytesForUi(3_221_225_472L) shouldBe "3.00 GB"
    }
}
