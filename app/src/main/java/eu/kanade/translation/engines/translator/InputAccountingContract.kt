package eu.kanade.translation.engines.translator

import eu.kanade.translation.engines.translator.contextual.TranslationContextChunkPlanner
import kotlin.math.ceil

/**
 * Accounting mode for final model request input tokens.
 */
enum class AccountingMode {
    EXACT,
    CERTIFIED_BOUND,
}

/**
 * Transport-layer input accounting contract for AI dispatches.
 * Supplies the final model request input-token estimate for quota governance.
 */
interface InputAccountingContract {
    val providerBackend: String
    val model: String?
    val isCertified: Boolean
    val accountingMode: AccountingMode
    fun countFinalTokens(payload: String): Int
}

/**
 * Closed set of verified provider profiles and empirical conservative bounds.
 */
internal enum class CertifiedAccountingProfile(
    val backend: String,
    val boundFactor: Double,
    val boundConstant: Int,
) {
    LM_STUDIO("lm_studio", 1.40, 64),
    DEEPSEEK("deepseek", 1.15, 32),
    OPENROUTER("openrouter", 1.35, 64),
    GEMINI("gemini", 1.10, 32);
}

/**
 * Certified conservative bound: ceil(CL100K_BASE(payload) * factor) + constant.
 * Private constructor restricts certification solely to closed profile factory.
 */
class CertifiedBoundInputAccountingContract private constructor(
    override val providerBackend: String,
    private val boundFactor: Double,
    private val boundConstant: Int,
    override val model: String? = null,
    override val isCertified: Boolean,
) : InputAccountingContract {
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        val base = TranslationContextChunkPlanner.estimateTokens(payload)
        return ceil(base * boundFactor).toInt() + boundConstant
    }

    internal companion object {
        fun certified(
            profile: CertifiedAccountingProfile,
            model: String? = null,
        ): CertifiedBoundInputAccountingContract = CertifiedBoundInputAccountingContract(
            providerBackend = profile.backend,
            boundFactor = profile.boundFactor,
            boundConstant = profile.boundConstant,
            model = model,
            isCertified = true,
        )

        fun uncertified(
            profile: CertifiedAccountingProfile,
            model: String? = null,
        ): CertifiedBoundInputAccountingContract = CertifiedBoundInputAccountingContract(
            profile.backend,
            profile.boundFactor,
            profile.boundConstant,
            model,
            isCertified = false,
        )
    }
}

/**
 * Uncertified placeholder contract for testing or unverified providers.
 */
class UncertifiedInputAccountingContract(
    override val providerBackend: String,
    override val model: String? = null,
) : InputAccountingContract {
    override val isCertified: Boolean = false
    override val accountingMode: AccountingMode = AccountingMode.CERTIFIED_BOUND

    override fun countFinalTokens(payload: String): Int {
        return TranslationContextChunkPlanner.estimateTokens(payload)
    }
}
