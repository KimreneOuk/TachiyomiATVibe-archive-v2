package eu.kanade.tachiyomi.ui.reader.setting

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.InMemoryPreferenceStore

class ReadingModeTest {
    @Test
    fun `legacy modes resolve to the two supported viewers`() {
        ReadingMode.fromPreference(0) shouldBe ReadingMode.DEFAULT
        ReadingMode.fromPreference(1) shouldBe ReadingMode.LEFT_TO_RIGHT
        ReadingMode.fromPreference(2) shouldBe ReadingMode.LEFT_TO_RIGHT
        ReadingMode.fromPreference(3) shouldBe ReadingMode.WEBTOON
        ReadingMode.fromPreference(4) shouldBe ReadingMode.WEBTOON
        ReadingMode.fromPreference(5) shouldBe ReadingMode.WEBTOON
        ReadingMode.fromPreference(null) shouldBe ReadingMode.DEFAULT
        ReadingMode.fromPreference(99) shouldBe ReadingMode.DEFAULT
        ReadingMode.defaultFromPreference(0) shouldBe ReadingMode.LEFT_TO_RIGHT
        ReadingMode.defaultFromPreference(5) shouldBe ReadingMode.WEBTOON
        ReadingMode.entries shouldBe listOf(ReadingMode.DEFAULT, ReadingMode.LEFT_TO_RIGHT, ReadingMode.WEBTOON)
    }

    @Test
    fun `default preference displays and writes supported modes`() {
        val raw = InMemoryPreferenceStore().getInt("reading-mode", 2)
        val preference = ReadingModePreference(raw)

        preference.get() shouldBe ReadingMode.LEFT_TO_RIGHT.flagValue
        preference.set(5)
        raw.get() shouldBe ReadingMode.WEBTOON.flagValue
        preference.get() shouldBe ReadingMode.WEBTOON.flagValue
        preference.set(0)
        preference.get() shouldBe ReadingMode.LEFT_TO_RIGHT.flagValue
    }
}
