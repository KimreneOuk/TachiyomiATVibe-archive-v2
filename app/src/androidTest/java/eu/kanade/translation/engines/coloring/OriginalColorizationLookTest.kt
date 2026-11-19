package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OriginalColorizationLookTest {
    @Test
    fun originalCombinesSourceInkWithModelChroma() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        source.eraseColor(-0x1) // white paper
        source.setPixel(1, 0, -0x1000000) // black ink stroke
        val work = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        work.eraseColor(-0x10000) // opaque red model output
        try {
            val output = MangaColorizationImageProcessing.applyLook(source, work)
            try {
                assertTrue("output is source-sized", output.width == 4 && output.height == 4)
                val ink = output.getPixel(1, 0)
                assertTrue("ink stays dark: r=${ink shr 16 and 0xff}", (ink shr 16 and 0xff) <= 12)
                assertTrue("ink stays dark: g=${ink shr 8 and 0xff}", (ink shr 8 and 0xff) <= 12)
                assertTrue("ink stays dark: b=${ink and 0xff}", (ink and 0xff) <= 12)
                val paper = output.getPixel(2, 1)
                val paperR = paper shr 16 and 0xff
                assertTrue("paper takes model chroma: r=$paperR", paperR >= 100)
                assertTrue("paper is red-tinted: r=$paperR g=${paper shr 8 and 0xff}", paperR > (paper shr 8 and 0xff))
                assertFalse(work.isRecycled)
            } finally {
                output.recycle()
            }
        } finally {
            source.recycle()
            work.recycle()
        }
    }
}
