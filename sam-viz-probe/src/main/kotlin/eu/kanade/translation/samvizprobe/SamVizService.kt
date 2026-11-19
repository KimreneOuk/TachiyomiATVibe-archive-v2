package eu.kanade.translation.samvizprobe

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import fi.iki.elonen.NanoHTTPD

/** Foreground service owning the viz pipeline: opens the model runner, hosts the
 *  HTTP server on port [PORT], and holds a bounded PARTIAL wakelock so inference
 *  survives screen-off. */
class SamVizService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var server: VizServer? = null
    private var inferenceService: InferenceService? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat(buildNotification("Starting…"))
        acquireWakeLock()
        if (server != null) {
            VizBus.post("already running on port $PORT")
            return START_NOT_STICKY
        }
        val service = InferenceService(applicationContext)
        inferenceService = service
        Thread({
            try {
                VizBus.post("verifying models and opening engine stages…")
                service.openAll()
                VizBus.post("engines open; starting server on port $PORT…")
                val started = VizServer(
                    PORT,
                    serviceProvider = {
                        synchronized(this@SamVizService) { inferenceService }
                    },
                    statusProvider = { VizBus.status.get() },
                )
                started.start(SOCKET_READ_TIMEOUT_MS, true)
                server = started
                val detail = listOfNotNull(
                    service.mangaLight?.let { "mangalight ready" } ?: "mangalight: ${service.mangaLightError}",
                    service.mcV2?.let { "mcv2 ready" } ?: "mcv2: ${service.mcV2Error}",
                ).joinToString("; ")
                VizBus.post("listening on port $PORT — $detail")
            } catch (failure: Throwable) {
                android.util.Log.e(VizBus.TAG, "startup failed", failure)
                VizBus.post("FAILED: ${failure.message}")
                runCatching { service.closeAll() }
                inferenceService = null
                stopSelf()
            }
        }, "samviz-start").start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { server?.stop() }
        server = null
        synchronized(this) {
            inferenceService?.closeAll()
            inferenceService = null
        }
        runCatching { wakeLock?.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG)
            .apply {
                setReferenceCounted(false)
                // Bounded lease: self-expires even if release() were missed.
                acquire(WAKELOCK_MAX_LEASE_MS)
            }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(text: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "SAM viz server", NotificationManager.IMPORTANCE_LOW),
            )
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle("SAM Viz Probe")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "sam_viz"
        const val NOTIFICATION_ID = 4721
        const val PORT = 18765
        const val SOCKET_READ_TIMEOUT_MS = 120_000
        const val WAKELOCK_TAG = "samvizprobe:server"
        const val WAKELOCK_MAX_LEASE_MS = 2L * 60L * 60L * 1000L
    }
}
