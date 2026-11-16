package eu.kanade.translation.models.imports

import eu.kanade.translation.models.ModelPackCatalog
import eu.kanade.translation.models.ModelPackDescriptor
import eu.kanade.translation.models.ModelPackManager
import eu.kanade.translation.models.PackInstallState
import eu.kanade.translation.models.store.PackStoreIo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Per-feature model options with an explicit, persisted user selection. The
 * choice never silently reverts: if the selected variant disappears, [selected]
 * reports Unavailable with recovery actions left to the caller.
 */
class FeatureModelRegistry(
    private val manager: ModelPackManager,
    private val imports: ImportedVariantManager,
    private val catalog: () -> List<ModelPackDescriptor> = { ModelPackCatalog.defaultEmbedded() },
    selectionFile: File,
) {

    @Serializable
    data class Ref(val kind: String, val variantId: String? = null) {
        companion object {
            val BUNDLED = Ref("BUNDLED_DEFAULT")
            fun imported(variantId: String) = Ref("IMPORTED", variantId)
            fun catalogPack(packId: String) = Ref("CATALOG_PACK", packId)
        }
    }

    enum class OptionKind { BUNDLED_DEFAULT, CATALOG_PACK, IMPORTED }

    data class ModelOption(
        val ref: Ref,
        val label: String,
        val kind: OptionKind,
        val trustSummary: String?,
        val selectable: Boolean,
        val notSelectableReason: String?,
    )

    sealed interface Selection {
        data object BundledDefault : Selection
        data class VariantChosen(val variantId: String, val version: String) : Selection
        data class Unavailable(val ref: Ref, val reason: String) : Selection
    }

    sealed interface SelectionResult {
        data class Selected(val ref: Ref) : SelectionResult
        data class Refused(val reason: String) : SelectionResult
    }

    @Serializable
    private data class PersistedSelections(val byFeature: Map<String, Ref> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private val selectionFile: File = selectionFile
    private val selections = MutableStateFlow(loadSelections())

    fun options(featureSeam: String): List<ModelOption> {
        val bundled = ModelOption(
            ref = Ref.BUNDLED,
            label = "Bundled default",
            kind = OptionKind.BUNDLED_DEFAULT,
            trustSummary = "Built into the app",
            selectable = true,
            notSelectableReason = null,
        )
        val packs = catalog().filter { it.featureSeam == featureSeam }.map { descriptor ->
            val installed = manager.statuses.value.firstOrNull { it.id == descriptor.id }?.installState
            ModelOption(
                ref = Ref.catalogPack(descriptor.id),
                label = descriptor.title,
                kind = OptionKind.CATALOG_PACK,
                trustSummary = if (installed is PackInstallState.Installed) "Installed · verified" else "Not installed",
                selectable = installed is PackInstallState.Installed,
                notSelectableReason = if (installed is PackInstallState.Installed) null else "Install the pack first",
            )
        }
        val imported = imports.variantStatuses.value
            .filter { it.featureSeam == featureSeam }
            .map { status ->
                ModelOption(
                    ref = Ref.imported(status.variantId),
                    label = status.originDisplayName,
                    kind = OptionKind.IMPORTED,
                    trustSummary = trustSummary(status),
                    selectable = status.trustState == TrustState.VALIDATED,
                    notSelectableReason = when (status.trustState) {
                        TrustState.VALIDATED -> null
                        TrustState.REJECTED -> "Rejected by validation — see report"
                        TrustState.IMPORTED -> "L1 unverified; selection requires explicit experimental acknowledgement"
                        else -> "Not yet validated"
                    },
                )
            }
        return listOf(bundled) + packs + imported
    }

    suspend fun selected(featureSeam: String): Selection {
        val ref = selections.value.byFeature[featureSeam] ?: return Selection.BundledDefault
        return when (ref.kind) {
            "BUNDLED_DEFAULT" -> Selection.BundledDefault
            "CATALOG_PACK" -> {
                val installed = manager.statuses.value.firstOrNull { it.id == ref.variantId }?.installState
                when (installed) {
                    is PackInstallState.Installed -> Selection.VariantChosen(ref.variantId!!, installed.version)
                    else -> Selection.Unavailable(
                        ref,
                        "Selected pack ${ref.variantId} is not active (${installed}). Recovery: reinstall the pack or restore the bundled default.",
                    )
                }
            }
            else -> {
                val status = imports.variantStatuses.value.firstOrNull { it.variantId == ref.variantId }
                    ?: return Selection.Unavailable(
                        ref,
                        "Selected variant ${ref.variantId} is gone. Recovery: re-import it, or restore the bundled default.",
                    )
                val installed = manager.statuses.value.firstOrNull { it.id == status.variantId }?.installState
                when (installed) {
                    is PackInstallState.Installed -> Selection.VariantChosen(status.variantId, installed.version)
                    else -> Selection.Unavailable(
                        ref,
                        "Selected variant ${ref.variantId} is present but not active (${installed}). Recovery: re-validate or restore default.",
                    )
                }
            }
        }
    }

    suspend fun select(featureSeam: String, ref: Ref, acknowledgeUnverified: Boolean = false): SelectionResult {
        val option = options(featureSeam).firstOrNull { it.ref == ref }
            ?: return SelectionResult.Refused("Unknown option for feature $featureSeam")
        if (option.selectable) {
            persist(featureSeam, ref)
            return SelectionResult.Selected(ref)
        }
        if (ref.kind == "IMPORTED") {
            val report = imports.validate(ref.variantId!!, ValidationLevel.L1_CONTRACT_CHECKED)
            val unverified = report.l1Unverified() && report.trustState() == TrustState.IMPORTED
            if (unverified && acknowledgeUnverified) {
                persist(featureSeam, ref)
                return SelectionResult.Selected(ref)
            }
        }
        return SelectionResult.Refused(option.notSelectableReason ?: "Trust requirements not met")
    }

    suspend fun rollback(featureSeam: String) {
        persist(featureSeam, Ref.BUNDLED)
    }

    // ------------------------------------------------------------------ store

    private fun trustSummary(status: VariantStatus): String = buildString {
        append(when (status.trustState) {
            TrustState.VALIDATED -> "Validated L0+L1"
            TrustState.IMPORTED -> "Imported; L1 unverified"
            TrustState.REJECTED -> "Rejected"
            TrustState.UNTRUSTED -> "Unvalidated"
            TrustState.QUARANTINED -> "Quarantined"
        })
        if (status.publisherDigestsProvided) {
            append(if (status.publisherDigestsMatched) " · publisher hash match" else " · publisher hash MISMATCH")
        }
    }

    private fun loadSelections(): PersistedSelections = if (selectionFile.isFile) {
        try {
            json.decodeFromString<PersistedSelections>(selectionFile.readText())
        } catch (_: Exception) {
            PersistedSelections()
        }
    } else {
        PersistedSelections()
    }

    private fun persist(featureSeam: String, ref: Ref) {
        val updated = selections.value.copy(byFeature = selections.value.byFeature + (featureSeam to ref))
        selections.value = updated
        PackStoreIo.writeTextAtomic(selectionFile, json.encodeToString(updated))
    }
}
