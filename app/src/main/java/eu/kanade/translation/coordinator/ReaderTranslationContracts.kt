package eu.kanade.translation.coordinator

import eu.kanade.translation.model.PageTranslationView
import eu.kanade.translation.model.SinglePageOutcome
import eu.kanade.translation.model.StageStatus
import eu.kanade.translation.model.isTextlessTerminal
import kotlinx.coroutines.Deferred
import java.io.InputStream

enum class ReaderTrigger { MANUAL_TAP, AUTO_VIEWPORT }

data class ReaderSessionToken(val chapterIdentity: String, val readerGeneration: Long)

/**
 * Handle for one scheduled reader teardown. The epoch is assigned synchronously
 * at request time, so a session bound at or after it is newer than the stop:
 * background stops skip such chapters; terminal stops never skip.
 */
class ReaderStopTicket internal constructor(
    internal val epoch: Long,
    internal val terminal: Boolean,
    private val completion: Deferred<Unit>,
) {
    /** Joins the teardown coroutine; resolves normally even when every chapter was skipped. */
    suspend fun await() {
        completion.await()
    }
}

/** Typed result of the atomic store-resolution + session-binding admission transaction. */
sealed interface ReaderAdmission {
    data class Admitted(val token: ReaderSessionToken) : ReaderAdmission

    /** The chapter's translation store could not be resolved or opened. */
    data object StoreUnavailable : ReaderAdmission

    /** The session admission gate refused the bind (stop in flight, batch owns chapter, rekeying). */
    data object SessionDenied : ReaderAdmission
}

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
