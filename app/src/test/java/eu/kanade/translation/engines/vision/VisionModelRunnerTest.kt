package eu.kanade.translation.engines.vision

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class VisionModelRunnerTest {

    @Test
    fun `buffer pool acquires and releases buffer`() {
        val runner = VisionModelRunner("TestRunner", inputFloatsCount = 100, maxPoolSize = 2)
        val buf1 = runner.acquireBuffer()
        buf1.capacity() shouldBe 100
        runner.releaseBuffer(buf1)
        val buf2 = runner.acquireBuffer()
        buf2 shouldBe buf1
        runner.releaseBuffer(buf2)
        runner.reclaimPooledMemory()
        runner.forceReleaseNativeBuffers()
        runner.close()
    }

    @Test
    fun `handleLiteRtFailure invokes fallback callback`() {
        val runner = VisionModelRunner("TestRunner")
        var fallbackCalled = false
        runner.handleLiteRtFailure({ fallbackCalled = true }, RuntimeException("test error"))
        fallbackCalled shouldBe true
    }

    @Test
    fun `handleLiteRtFailure throws when fallback is null`() {
        val runner = VisionModelRunner("TestRunner")
        shouldThrow<IllegalStateException> {
            runner.handleLiteRtFailure(null, IllegalStateException("must throw"))
        }
    }
}
