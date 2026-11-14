package eu.kanade.translation

import android.content.Context
import eu.kanade.translation.engines.coloring.MangaLightHybridColorizationEngine
import eu.kanade.translation.engines.coloring.NpuMangaColorizationEngine
import eu.kanade.translation.engines.coloring.PageColorizationEngine
import eu.kanade.translation.engines.upscaling.PageUpscalingEngine
import eu.kanade.translation.engines.upscaling.UpscalingModelCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.TranslationPreferences

/**
 * Owns the manager's colorization and upscaling engines: model selection by
 * preference, live swap (attach before close), and the preference watchers.
 * Test constructions keep engines detached so fakes never colorize.
 */
internal class TranslationManagerColorization(
    private val context: Context,
    private val preferences: TranslationPreferences,
    private val attachColorization: (PageColorizationEngine) -> Unit,
    private val attachUpscaling: (PageUpscalingEngine) -> Unit,
) {

    var engine: PageColorizationEngine? = null

    var upscaling: PageUpscalingEngine? = null

    fun currentModel(): ColorizationModel =
        preferences.translationColorizationModel().get()

    fun buildColorizationEngine(model: ColorizationModel): PageColorizationEngine = when (model) {
        ColorizationModel.MANGA_COLORIZATION_V2_NPU_W8A8 -> NpuMangaColorizationEngine(context, preferences)
        ColorizationModel.MANGA_LIGHT_SAM_NPU_GPU -> MangaLightHybridColorizationEngine(context, preferences)
    }

    /**
     * Swaps the engine live when the user picks another colorization model;
     * the old engine's own run lock serializes the close against an
     * in-flight COLOR stage.
     */
    fun swapColorizationEngine(model: ColorizationModel) {
        val previous = engine
        val next = buildColorizationEngine(model)
        attachColorization(next)
        engine = next
        previous?.close()
    }

    fun attachInitialEngines() {
        engine = buildColorizationEngine(currentModel()).also(attachColorization)
        upscaling = UpscalingModelCatalog.createEngine(context).also(attachUpscaling)
    }

    fun watchPreferenceChanges(scope: CoroutineScope) {
        scope.launch {
            preferences.translationColorizationModel().changes()
                .distinctUntilChanged()
                .drop(1)
                .collect(::swapColorizationEngine)
        }
        scope.launch {
            preferences.preferFfdnetDenoising().changes()
                .distinctUntilChanged()
                .drop(1)
                .collect { swapColorizationEngine(currentModel()) }
        }
    }
}
