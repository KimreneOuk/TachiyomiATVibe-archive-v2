package eu.kanade.tachiyomi.ui.manga

import eu.kanade.presentation.more.settings.widget.AiModelListState
import eu.kanade.tachiyomi.ui.reader.TranslationSettingsState
import eu.kanade.translation.coordinator.BatchQueuePauseState
import eu.kanade.translation.engines.translator.providers.AiModelFetcher
import eu.kanade.translation.engines.vision.ocr.OcrModelCatalog
import eu.kanade.translation.settings.TranslationSettingsPreferenceWriter
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tachiyomi.domain.translation.AiEngine
import tachiyomi.domain.translation.ColorCombineMode
import tachiyomi.domain.translation.ColorizationModel
import tachiyomi.domain.translation.NeuralInpaintModel
import tachiyomi.domain.translation.OcrModel
import tachiyomi.domain.translation.StandardEngine
import tachiyomi.domain.translation.TranslationEngineCategory
import tachiyomi.domain.translation.TranslationPreferences

internal enum class BatchTranslationSettingsPhase {
    PRE_START,
    PAUSE_REQUESTED,
    PAUSED,
    BATCH_RUNNING,
    BATCH_ENDED,
}

internal fun resolveBatchTranslationSettingsPhase(
    isPreStart: Boolean,
    pauseState: BatchQueuePauseState,
    hasResumableBatchWork: Boolean,
): BatchTranslationSettingsPhase = when {
    isPreStart -> BatchTranslationSettingsPhase.PRE_START
    pauseState == BatchQueuePauseState.REQUESTED -> BatchTranslationSettingsPhase.PAUSE_REQUESTED
    !hasResumableBatchWork -> BatchTranslationSettingsPhase.BATCH_ENDED
    pauseState == BatchQueuePauseState.PAUSED -> BatchTranslationSettingsPhase.PAUSED
    else -> BatchTranslationSettingsPhase.BATCH_RUNNING
}

internal interface BatchTranslationSettingsActions {
    fun setTranslateFromLanguage(language: String)
    fun setTranslateToLanguage(language: String)
    fun setOcrModel(model: OcrModel)
    fun setTranslationInpaintingMode(mode: String)
    fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel)
    fun setTranslationColorizationEnabled(enabled: Boolean)
    fun setTranslationColorizationModel(model: ColorizationModel)
    fun setTranslationColorCombineMode(mode: ColorCombineMode)
    fun setTranslationUpscalingEnabled(enabled: Boolean)
    fun setTranslationEngineCategory(category: TranslationEngineCategory)
    fun setTranslationStandardEngine(engine: StandardEngine)
    fun setTranslationDeeplApiKey(key: String)
    fun setTranslationAiEngine(engine: AiEngine)
    fun setTranslationAiApiKey(key: String)
    fun setTranslationAiBaseUrl(url: String)
    fun setTranslationAiModel(model: String)
    fun fetchAiModels()
}

internal class BatchTranslationSettingsModel(
    private val preferences: TranslationPreferences,
    private val scope: CoroutineScope,
) : BatchTranslationSettingsActions {
    private val writer = TranslationSettingsPreferenceWriter(preferences)
    private val aiModelFetchState = MutableStateFlow<AiModelListState>(AiModelListState.Idle)

    private val languageFlow = combine(
        preferences.translateFromLanguage().changes(),
        preferences.translateToLanguage().changes(),
        preferences.translationRecentLanguagesFrom().changes(),
        preferences.translationRecentLanguagesTo().changes(),
    ) { from, to, recentFrom, recentTo ->
        LanguageSettings(from, to, recentFrom, recentTo)
    }

    private val colorizationFlow = combine(
        preferences.translationColorizationModel().changes(),
        preferences.translationUpscalingEnabled().changes(),
        preferences.colorCombineMode().changes(),
    ) { model, upscalingEnabled, combineMode ->
        ColorizationSettings(model, upscalingEnabled, combineMode)
    }

    private val imageFlow = combine(
        preferences.translationInpaintingMode().changes(),
        preferences.translationInpaintingNeuralModel().changes(),
        preferences.translationColorizationEnabled().changes(),
        colorizationFlow,
    ) { inpaintingMode, neuralModel, colorizationEnabled, colorization ->
        ImageSettings(inpaintingMode, neuralModel, colorizationEnabled, colorization)
    }

    private val engineFlow = combine(
        preferences.translationEngineCategory().changes(),
        preferences.translationStandardEngine().changes(),
        preferences.translationDeeplApiKey().changes(),
        preferences.translationAiEngine().changes(),
    ) { category, standardEngine, deeplApiKey, aiEngine ->
        EngineSettings(category, standardEngine, deeplApiKey, aiEngine)
    }

    private val aiEnginePreferencesFlow: Flow<AiEngineSettings> =
        preferences.translationAiEngine().changes().flatMapLatest { engine ->
            val baseUrlFlow = preferences.translationAiBaseUrl(engine)?.changes() ?: flowOf("")
            combine(
                preferences.translationAiApiKey(engine).changes(),
                baseUrlFlow,
                preferences.translationAiModel(engine).changes(),
                preferences.translationAiRecentModels(engine).changes(),
            ) { apiKey, baseUrl, model, recentModels ->
                AiEngineSettings(apiKey, baseUrl, model, recentModels)
            }
        }

    private val ocrModelFlow = preferences.translateFromLanguage().changes().flatMapLatest { from ->
        val language = OcrModelCatalog.languageFromPreference(from)
        OcrModelCatalog.preferenceFor(preferences, language).changes().map { model ->
            OcrModelCatalog.coerce(model, language) to OcrModelCatalog.labelsFor(language)
        }
    }

    private val settingsInputsFlow = combine(languageFlow, imageFlow) { language, image ->
        SettingsInputs(language, image)
    }

    val state: StateFlow<TranslationSettingsState> = combine(
        settingsInputsFlow,
        engineFlow,
        ocrModelFlow,
        aiEnginePreferencesFlow,
        aiModelFetchState,
    ) { inputs, engine, ocrInfo, aiSettings, fetchState ->
        TranslationSettingsState(
            translateFromLanguage = inputs.language.from,
            translateToLanguage = inputs.language.to,
            translationRecentLanguagesFrom = TranslationPreferences.decodeRecentLanguages(
                inputs.language.recentFrom,
            ).toImmutableList(),
            translationRecentLanguagesTo = TranslationPreferences.decodeRecentLanguages(
                inputs.language.recentTo,
            ).toImmutableList(),
            ocrModel = ocrInfo.first,
            ocrModelEntries = ocrInfo.second,
            inpaintingMode = inputs.image.inpaintingMode,
            inpaintingNeuralModel = inputs.image.neuralModel,
            colorizationEnabled = inputs.image.colorizationEnabled,
            colorizationModel = inputs.image.colorization.model,
            upscalingEnabled = inputs.image.colorization.upscalingEnabled,
            colorCombineMode = inputs.image.colorization.combineMode,
            engineCategory = engine.category,
            standardEngine = engine.standardEngine,
            deeplApiKey = engine.deeplApiKey,
            aiEngine = engine.aiEngine,
            aiApiKey = aiSettings.apiKey,
            aiBaseUrl = aiSettings.baseUrl,
            aiModel = aiSettings.model,
            aiRecentModels = TranslationPreferences.decodeRecentModels(aiSettings.recentModels).toImmutableList(),
            aiModelFetchState = fetchState,
        )
    }.stateIn(
        scope,
        SharingStarted.WhileSubscribed(5_000),
        currentSettings(),
    )

    override fun setTranslateFromLanguage(language: String) = writer.setTranslateFromLanguage(language)
    override fun setTranslateToLanguage(language: String) = writer.setTranslateToLanguage(language)
    override fun setOcrModel(model: OcrModel) = writer.setOcrModel(model)
    override fun setTranslationInpaintingMode(mode: String) = writer.setTranslationInpaintingMode(mode)
    override fun setTranslationInpaintingNeuralModel(model: NeuralInpaintModel) = writer.setTranslationInpaintingNeuralModel(model)
    override fun setTranslationColorizationEnabled(enabled: Boolean) = writer.setTranslationColorizationEnabled(enabled)
    override fun setTranslationColorizationModel(model: ColorizationModel) = writer.setTranslationColorizationModel(model)
    override fun setTranslationColorCombineMode(mode: ColorCombineMode) = writer.setTranslationColorCombineMode(mode)
    override fun setTranslationUpscalingEnabled(enabled: Boolean) = writer.setTranslationUpscalingEnabled(enabled)
    override fun setTranslationEngineCategory(category: TranslationEngineCategory) = writer.setTranslationEngineCategory(category)
    override fun setTranslationStandardEngine(engine: StandardEngine) = writer.setTranslationStandardEngine(engine)
    override fun setTranslationDeeplApiKey(key: String) = writer.setTranslationDeeplApiKey(key)
    override fun setTranslationAiEngine(engine: AiEngine) = writer.setTranslationAiEngine(engine)
    override fun setTranslationAiApiKey(key: String) = writer.setTranslationAiApiKey(key)
    override fun setTranslationAiBaseUrl(url: String) = writer.setTranslationAiBaseUrl(url)
    override fun setTranslationAiModel(model: String) = writer.setTranslationAiModel(model)

    override fun fetchAiModels() {
        val engine = preferences.translationAiEngine().get()
        val apiKey = preferences.translationAiApiKey(engine).get()
        val baseUrl = preferences.translationAiBaseUrl(engine)?.get().orEmpty()
        aiModelFetchState.value = AiModelListState.Loading(if (engine == AiEngine.LMSTUDIO) baseUrl else apiKey)
        scope.launch {
            try {
                aiModelFetchState.value = when (val result = AiModelFetcher.fetch(engine, apiKey, baseUrl)) {
                    is AiModelFetcher.Result.Success -> AiModelListState.Loaded(result.models)
                    is AiModelFetcher.Result.InvalidKey -> AiModelListState.Failed("Invalid or expired API key")
                    is AiModelFetcher.Result.NoModels -> AiModelListState.Loaded(emptyList())
                    is AiModelFetcher.Result.Error -> AiModelListState.Failed(result.message)
                }
            } catch (error: Exception) {
                aiModelFetchState.value = AiModelListState.Failed(error.message ?: "Unknown error")
            }
        }
    }

    private fun currentSettings(): TranslationSettingsState {
        val from = preferences.translateFromLanguage().get()
        val to = preferences.translateToLanguage().get()
        val language = OcrModelCatalog.languageFromPreference(from)
        val aiEngine = preferences.translationAiEngine().get()
        return TranslationSettingsState(
            translateFromLanguage = from,
            translateToLanguage = to,
            translationRecentLanguagesFrom = TranslationPreferences.decodeRecentLanguages(
                preferences.translationRecentLanguagesFrom().get(),
            ).toImmutableList(),
            translationRecentLanguagesTo = TranslationPreferences.decodeRecentLanguages(
                preferences.translationRecentLanguagesTo().get(),
            ).toImmutableList(),
            ocrModel = OcrModelCatalog.coerce(
                preferences.translationOcrModel(language.name).get(),
                language,
            ),
            ocrModelEntries = OcrModelCatalog.labelsFor(language),
            inpaintingMode = preferences.translationInpaintingMode().get(),
            inpaintingNeuralModel = preferences.translationInpaintingNeuralModel().get(),
            colorizationEnabled = preferences.translationColorizationEnabled().get(),
            colorizationModel = preferences.translationColorizationModel().get(),
            colorCombineMode = preferences.colorCombineMode().get(),
            upscalingEnabled = preferences.translationUpscalingEnabled().get(),
            engineCategory = preferences.translationEngineCategory().get(),
            standardEngine = preferences.translationStandardEngine().get(),
            deeplApiKey = preferences.translationDeeplApiKey().get(),
            aiEngine = aiEngine,
            aiApiKey = preferences.translationAiApiKey(aiEngine).get(),
            aiBaseUrl = preferences.translationAiBaseUrl(aiEngine)?.get().orEmpty(),
            aiModel = preferences.translationAiModel(aiEngine).get(),
            aiRecentModels = TranslationPreferences.decodeRecentModels(
                preferences.translationAiRecentModels(aiEngine).get(),
            ).toImmutableList(),
        )
    }

    private data class LanguageSettings(
        val from: String,
        val to: String,
        val recentFrom: String,
        val recentTo: String,
    )

    private data class ColorizationSettings(
        val model: ColorizationModel,
        val upscalingEnabled: Boolean,
        val combineMode: ColorCombineMode,
    )

    private data class ImageSettings(
        val inpaintingMode: String,
        val neuralModel: NeuralInpaintModel,
        val colorizationEnabled: Boolean,
        val colorization: ColorizationSettings,
    )

    private data class EngineSettings(
        val category: TranslationEngineCategory,
        val standardEngine: StandardEngine,
        val deeplApiKey: String,
        val aiEngine: AiEngine,
    )

    private data class AiEngineSettings(
        val apiKey: String,
        val baseUrl: String,
        val model: String,
        val recentModels: String,
    )

    private data class SettingsInputs(
        val language: LanguageSettings,
        val image: ImageSettings,
    )
}
