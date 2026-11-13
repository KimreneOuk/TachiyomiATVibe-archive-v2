package eu.kanade.translation.engines.translator

enum class ProviderFailureKind {
    NETWORK,
    RATE_LIMIT,
    QUOTA_EXHAUSTED,
    SERVER,
    AUTHENTICATION,
    REFUSAL,
    PROTOCOL,
    CONFIGURATION,
    SOURCE,
}

enum class ProviderFailureRetryability {
    RETRY_NOW,
    RETRY_AFTER,
    PAUSE,
    TERMINAL,
}

/** Safe, provider-neutral failure metadata. It never carries prompts or response bodies. */
data class ProviderFailure(
    val kind: ProviderFailureKind,
    val retryability: ProviderFailureRetryability,
    val statusCode: Int? = null,
    val providerCode: String? = null,
    val retryAfterMillis: Long? = null,
    val retryAfterAtEpochMs: Long? = null,
    val safeSummary: String,
    val requestId: String? = null,
    val attempt: Int? = null,
)

open class ProviderFailureException(
    val failure: ProviderFailure,
    cause: Throwable? = null,
) : Exception(failure.safeSummary, cause)
