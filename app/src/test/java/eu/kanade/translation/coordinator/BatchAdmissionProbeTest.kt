package eu.kanade.translation.coordinator

import eu.kanade.tachiyomi.source.model.Page
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class BatchAdmissionProbeTest {
    @Test
    fun `probe distinguishes complete partial and unknown totals`() {
        val pages = listOf(Page(0), Page(1))

        BatchAdmissionProbe.evaluate(2, pages) shouldBe BatchAdmissionDecision.Complete
        BatchAdmissionProbe.evaluate(1, pages) shouldBe BatchAdmissionDecision.Partial(
            expectedSourcePageCount = 2,
            downloadedPageCount = 1,
        )
        BatchAdmissionProbe.evaluate(1, null) shouldBe BatchAdmissionDecision.UnknownCount
        BatchAdmissionProbe.evaluate(1, emptyList()) shouldBe BatchAdmissionDecision.UnknownCount
    }
}
