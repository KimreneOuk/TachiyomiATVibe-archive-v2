package eu.kanade.translation.coordinator

import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CopyOnWriteArrayList

/** Retains reader and batch jobs after a timed-out switch join, so retries cannot mistake timeout for quiescence. */
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
