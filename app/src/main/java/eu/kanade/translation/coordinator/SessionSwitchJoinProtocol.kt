package eu.kanade.translation.coordinator

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Shared job-retention and bounded-join protocol for session switches between
 * batch and reader coordinators.
 *
 * Captures jobs while an active coordinator transitions to quiescent state. A
 * bounded join can time out while native work is still unwinding; keeping these
 * references lets the coordinator retry joining the same jobs across repeated
 * switch attempts instead of accidentally treating a timeout as quiescence.
 */
internal class SessionSwitchJoinProtocol {

    private val sessionSwitchJobs = CopyOnWriteArrayList<Job>()

    val hasPendingJobs: Boolean
        get() = sessionSwitchJobs.isNotEmpty()

    fun retain(job: Job) {
        if (sessionSwitchJobs.addIfAbsent(job)) {
            job.invokeOnCompletion { sessionSwitchJobs.remove(job) }
        }
    }

    suspend fun joinAll(timeoutMs: Long): Boolean {
        for (pendingJob in sessionSwitchJobs.toList()) {
            if (withTimeoutOrNull(timeoutMs) { pendingJob.join() } == null) {
                return false
            }
            sessionSwitchJobs.remove(pendingJob)
        }
        return true
    }

    fun clear() {
        sessionSwitchJobs.clear()
    }
}
