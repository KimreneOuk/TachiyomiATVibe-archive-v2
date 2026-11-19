package eu.kanade.translation.samvizprobe

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Minimal control screen: start/stop the viz server and live status. */
class SamVizActivity : Activity() {

    private lateinit var statusView: TextView
    private lateinit var infoView: TextView
    private val handler = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            statusView.text = VizBus.status.get()
            infoView.text = buildInfo()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Auto-start: adb launch (am start) brings the server up hands-free.
        startForegroundService(Intent(this, SamVizService::class.java))

        val title = TextView(this).apply {
            text = "SAM Viz Probe"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        statusView = TextView(this).apply { textSize = 16f }
        infoView = TextView(this).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setPadding(0, 24, 0, 0)
        }
        val startButton = Button(this).apply {
            text = "Start server"
            setOnClickListener {
                startForegroundService(Intent(this@SamVizActivity, SamVizService::class.java))
            }
        }
        val stopButton = Button(this).apply {
            text = "Stop server"
            setOnClickListener {
                stopService(Intent(this@SamVizActivity, SamVizService::class.java))
            }
        }
        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(startButton)
            addView(stopButton)
        }
        val root = ScrollView(this).apply {
            addView(
                LinearLayout(this@SamVizActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(48, 64, 48, 48)
                    addView(title)
                    addView(statusView)
                    addView(buttons)
                    addView(infoView)
                },
            )
        }
        setContentView(root)
    }

    override fun onStart() {
        super.onStart()
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        super.onStop()
    }

    private fun buildInfo(): String {
        val sb = StringBuilder()
        val precheck = qnnDispatchPrecheck(java.io.File(applicationInfo.nativeLibraryDir))
        sb.append("QNN dispatch libs: ").append(if (precheck == null) "ok" else precheck).append('\n')
        sb.append("Models dir: ").append(MangaLightRunner.modelsDir(this).absolutePath).append('\n')
        MangaLightRunner.ARTIFACTS.forEach { artifact ->
            val file = java.io.File(MangaLightRunner.modelsDir(this), artifact.name)
            val marker = when {
                !file.isFile -> "MISSING"
                file.length() != artifact.bytes -> "WRONG SIZE (${file.length()})"
                else -> "ok"
            }
            sb.append("  ").append(artifact.name).append(": ").append(marker).append('\n')
        }
        val mcV2File = java.io.File(MangaLightRunner.modelsDir(this), McV2Runner.ARTIFACT.name)
        sb.append("  ").append(McV2Runner.ARTIFACT.name).append(": ").append(
            when {
                !mcV2File.isFile -> "MISSING"
                mcV2File.length() != McV2Runner.ARTIFACT.bytes -> "WRONG SIZE (${mcV2File.length()})"
                else -> "ok"
            },
        ).append('\n')
        sb.append("Requests served: ").append(VizBus.requestCount.get())
        return sb.toString()
    }
}
