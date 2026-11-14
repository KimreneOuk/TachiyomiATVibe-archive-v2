package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import java.io.InputStream

enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }

data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)

/** Captures a fresh-stream factory, never an open stream. Null means source-unavailable. */
data class ReaderPageWork(val pageKey: String, val readerPageIndex: Int, val streamFn: (() -> InputStream)?)

sealed interface ReaderSubmission {
    data class Queued(val pageKey: String) : ReaderSubmission
    data class Existing(val outcome: SinglePageOutcome?) : ReaderSubmission
    data class Rejected(val outcome: SinglePageOutcome.Rejected) : ReaderSubmission
}

/**
 * True when submitted page work may be skipped as already-final. A
 * colorize-only page is NEVER final for reader dispatch — manual or
 * automatic: its OCR/translation statuses are placeholders, and a translate
 * request (explicit tap or auto-translation demand) must rework it, running
 * detection/render. Only genuinely translated or textless-terminal states
 * finalize page work.
 */
internal fun isReaderPageWorkFinal(
    state: PageTranslationView?,
    force: Boolean,
): Boolean = !force &&
    (
        state?.translationStatus == StageStatus.READY ||
            state?.isTextlessTerminal == true
        )
