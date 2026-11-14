package eu.kanade.translation.engines.coloring

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.ColorCombineMode
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class OriginalColorizationResizeTest {
    @Test
    fun originalUsesLanczosAndKeepsInputOwnedByCaller() {
        val source = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        val work = Bitmap.createBitmap(intArrayOf(-0x1000000, -0x1, -0x1, -0x1000000), 2, 2, Bitmap.Config.ARGB_8888)
        try {
            val output = MangaColorizationImageProcessing.applyLook(source, work, ColorCombineMode.ORIGINAL)
            try {
                assertEquals(4, output.width)
                assertEquals(4, output.height)
                assertTrue(abs((output.getPixel(1, 0) and 0xff) - 40) <= 1)
                assertTrue(abs((output.getPixel(1, 1) and 0xff) - 87) <= 1)
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
