package eu.kanade.translation.engines.translator

/** Credential-wide Batch sub-limit (DR-D): one request per credential per rolling 60 s, nested beneath the shared provider governor; interactive never enters; RPM-only v1. */
object BatchProviderSublimit {

    /** The aggregate Batch allowance (requests per rolling 60 s window). */
    const val BATCH_REQUESTS_PER_MINUTE = 15

    /** Credential-wide, model-agnostic sub-limit key (DR-D): same backend + credential scope, no model dimension. */
    fun batchSublimitKey(key: ProviderRequestKey): ProviderRequestKey =
        ProviderRequestKey(
            backend = key.backend,
            model = null,
            credentialScope = key.credentialScope,
        )

    /** Rolling-window pacing only (`minimumSpacingMs = 0`); `maxInFlight = 1` serializes Batch admission all-or-nothing. */
    val policy: ProviderQuotaPolicy = ProviderQuotaPolicy(
        requestsPerMinute = BATCH_REQUESTS_PER_MINUTE,
        tokensPerMinute = Int.MAX_VALUE,
        minimumSpacingMs = 0L,
        maxInFlight = 1,
        maxForegroundWaitMs = 15_000L,
        windowMs = 60_000L,
    )
}

/** Bucket-2 gate (DR-D): Batch sub-limit first, then shared provider bucket; all-or-nothing - a bucket-2 permit is released before a bucket-1 pause propagates; interactive skips this gate. */
// `open` so the coordinator can wrap the gate with the overlap scheduler's
// remote-window signalling subclass. Admission semantics remain unchanged;
// default construction uses this implementation directly.
open class BatchRequestSublimitGate(
    private val clock: ProviderRequestClock = SystemProviderRequestClock,
) {

    private val sublimitGovernor = ProviderRequestGovernor(
        policy = { BatchProviderSublimit.policy },
        clock = clock,
    )

    open suspend fun <T> executeBatch(
        metadata: ProviderRequestMetadata,
        block: suspend () -> T,
    ): T {
        val sublimitMetadata = metadata.copy(key = BatchProviderSublimit.batchSublimitKey(metadata.key))
        val decision = sublimitGovernor.admit(sublimitMetadata)
        val permit = when (decision) {
            is ProviderAdmissionDecision.Admitted -> decision.permit
            is ProviderAdmissionDecision.Deferred -> throw ProviderRequestPausedException(
                key = metadata.key,
                nextEligibleRetryAtEpochMs = decision.nextEligibleRetryAtEpochMs,
                reason = "Batch sub-limit deferred: ${decision.reason}",
            )
        }
        // The permit is ALWAYS given back when this call site exits - success, pause
        // (released before the pause propagates), or cancellation; `maxInFlight = 1`
        // keeps permits short-lived, with sub-limit quota accounting living in the reservation.
        try {
            return block()
        } finally {
            sublimitGovernor.releaseAdmitted(permit, sublimitMetadata)
        }
    }
}
