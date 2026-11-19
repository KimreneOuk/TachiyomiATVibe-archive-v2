package eu.kanade.translation.samvizprobe

import android.util.Log
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Service/activity status bus: service posts stages, activity polls. */
object VizBus {
    const val TAG = "SamVizProbe"
    val status = AtomicReference("idle")
    val requestCount = AtomicInteger()

    fun post(text: String) {
        status.set(text)
        Log.i(TAG, text)
    }
}
