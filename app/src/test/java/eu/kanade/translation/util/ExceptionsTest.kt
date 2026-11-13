package eu.kanade.translation.util

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ExceptionsTest {

    @Test
    fun `isFatal returns true for VirtualMachineError and ThreadDeath`() {
        OutOfMemoryError().isFatal() shouldBe true
        StackOverflowError().isFatal() shouldBe true
        ThreadDeath().isFatal() shouldBe true
    }

    @Test
    fun `isFatal returns false for regular exceptions`() {
        RuntimeException("test").isFatal() shouldBe false
        IllegalArgumentException().isFatal() shouldBe false
        IllegalStateException().isFatal() shouldBe false
        Exception().isFatal() shouldBe false
    }
}
