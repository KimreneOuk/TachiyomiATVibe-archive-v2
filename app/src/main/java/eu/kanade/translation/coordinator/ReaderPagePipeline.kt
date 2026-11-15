package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageRejectionKind
import eu.kanade.translation.model.PageStage
import eu.kanade.translation.model.PageTranslation
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.detachedCopy
import eu.kanade.translation.persistence.ChapterTranslationStore
import eu.kanade.translation.persistence.LeaseAcquisition
import eu.kanade.translation.persistence.PageWriteOrigin
import eu.kanade.translation.processing.TranslationProcessor
import eu.kanade.translation.processing.TranslationStageSignal
import eu.kanade.translation.processing.adaptive.DevicePermitPriority
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Single-page lease acquisition, processor translation, guarded atomic store update, attempt-fenced stage signals. */
internal class ReaderPagePipeline(
    private val processor: TranslationProcessor,
    private val stageBoard: TranslationStageSignalBoard,
) {

    suspend fun processRequest(request: ReaderTranslationCoordinator.PageRequest): SinglePageOutcome {
        val stream = request.streamFn ?: return SinglePageOutcome.Failed(request.pageKey, "Page image stream is unavailable")
        val origin = if (request.isManual) PageWriteOrigin.MANUAL else PageWriteOrigin.AUTO
        val store = request.session.store
        val lease = when (val acquired = store.tryAcquirePageStageLease(request.pageKey, PageStage.Ocr, origin)) {
            is LeaseAcquisition.Granted -> acquired.lease
            is LeaseAcquisition.Denied -> return SinglePageOutcome.Rejected(
                owner = acquired.owner,
                kind = PageRejectionKind.ALREADY_OWNED,
                diagnosticDetail = acquired.reason,
            )
        }
        // TachiyomiAT: transient stage signals for the reader status pill. The full key includes the
        // chapter key, so concurrent chapters can never bleed into each other's indicators; the attempt
        // token fences superseded attempt callbacks from overwriting a newer stage (spec §8 stale-callback rule).
        val fullStageKey = "${request.chapterKey}:${request.pageKey}"
        val stageAttempt = stageBoard.nextAttemptToken()
        try {
            // Fence against writes that happen while decode/native/remote work is running.
            // Refreshing this at commit time would let an older candidate overwrite them.
            val expected = store.snapshot(request.pageKey).toPrecondition().copy(leaseToken = lease.token)
            stageBoard.publish(fullStageKey, TranslationStageSignal.PREPARING, stageAttempt)
            val stageReporter = { stage: TranslationStageSignal ->
                stageBoard.publish(fullStageKey, stage, stageAttempt)
            }
            val result = processor.processSinglePage(
                pageKey = request.pageKey,
                streamFn = stream,
                recognitionEngine = processor.recognitionEngine,
                translator = processor.textTranslator,
                companionDir = processor.companionDirectory(
                    request.session.manga,
                    request.session.chapter,
                    request.session.source,
                ),
                chapterId = request.session.chapter.id,
                chapterName = request.session.chapter.name,
                priority = if (request.isManual) DevicePermitPriority.FOREGROUND else DevicePermitPriority.BACKGROUND,
                stageReporter = stageReporter,
            )
            val candidate = when (result) {
                is TranslationProcessor.ProcessResult.Success -> result.page
                is TranslationProcessor.ProcessResult.Failure -> result.page ?: PageTranslation(
                    sourceFileName = request.pageKey,
                ).apply {
                    ocrStatus = StageStatus.FAILED
                    errorMessage = result.errorMessage
                }
            }
            val write = store.updatePageGuarded(
                pageKey = request.pageKey,
                expected = expected,
                description = "reader:${result::class.simpleName}",
            ) { candidate.detachedCopy() }
            if (write is ChapterTranslationStore.PatchResult.Rejected) {
                return SinglePageOutcome.Rejected(
                    owner = origin,
                    kind = PageRejectionKind.PERSISTENCE_FAILED,
                    diagnosticDetail = write.reason,
                )
            }
            return when (result) {
                is TranslationProcessor.ProcessResult.Success -> SinglePageOutcome.Completed
                is TranslationProcessor.ProcessResult.Failure -> SinglePageOutcome.Failed(request.pageKey, result.errorMessage)
            }
        } finally {
            withContext(NonCancellable) {
                store.releasePageStageLease(request.pageKey, origin)
            }
            // TachiyomiAT: drop this attempt's stage signal so the pill does not
            // outlive the attempt. Removal only happens when the stored token
            // is still this attempt's — a retried attempt owns the entry then.
            stageBoard.clear("${request.chapterKey}:${request.pageKey}", stageAttempt)
        }
    }
}
