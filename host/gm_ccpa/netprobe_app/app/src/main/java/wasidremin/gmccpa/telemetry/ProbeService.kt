package wasidremin.gmccpa.telemetry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import wasidremin.gmccpa.ProbeLog

/** Bound by the diagnostic Activity or started automatically for continuous EV Insights telemetry. */
class ProbeService : Service() {
    inner class LocalBinder : Binder() { val service: ProbeService get() = this@ProbeService }
    private val log = ProbeLog.sub("probe")
    private val binder = LocalBinder()
    private val main = Handler(Looper.getMainLooper())
    private var recorder: VehicleProbe? = null
    val probe: VehicleProbe? get() = recorder
    var observer: (() -> Unit)? = null
    var unavailable = "Car service initialization pending"
        private set

    override fun onCreate() {
        super.onCreate()
        try {
            recorder = VehicleProbe(applicationContext,
                { main.post { observer?.invoke() } },
                { main.post { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); observer?.invoke() } })
            recorder!!.open()
            // Drain any unuploaded reports from prior drives
            ProbeUpload.enqueueAndDrain(applicationContext)
        } catch (t: Exception) { unavailable = "${t.javaClass.name}: ${t.message}" }
        catch (t: LinkageError) { unavailable = "android.car unavailable: ${t.javaClass.name}: ${t.message}" }
    }
    override fun onBind(intent: Intent?): IBinder = binder
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val p = recorder
        val action = intent?.action ?: AUTO_START
        if (action == FLUSH_NOW) {
            p?.flushChunkNow("manual/session flush")
            ProbeUpload.enqueueAndDrain(applicationContext, activeDir = p?.latestDirectory)
            return START_NOT_STICKY
        }
        if (action == STOP) {
            p?.close()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (p == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val isAuto = action == AUTO_START
        if (!isAuto && (action != START || !p.parked)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (isAuto && !ProbeUpload.isAutoUploadEnabled(applicationContext)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val notifications = getSystemService(NotificationManager::class.java)
            notifications.createNotificationChannel(NotificationChannel(CHANNEL, "Vehicle telemetry capture", NotificationManager.IMPORTANCE_LOW))
            val open = PendingIntent.getActivity(this, 0, Intent(this, ProbeActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val notification = Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.ic_menu_info_details)
                .setContentTitle(if (isAuto) "EV Insights live telemetry active" else "Vehicle telemetry capture active")
                .setContentText(if (isAuto) "Streaming & auto-uploading vehicle telemetry to EV Insights" else "Return to Vehicle Telemetry Probe while parked to stop/export.")
                .setContentIntent(open).setOngoing(true).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(NOTIFICATION_ID, notification)
            if (isAuto) {
                val rawLoc = intent?.getBooleanExtra("raw_location", ProbeUpload.isAutoRawLocationEnabled(applicationContext))
                    ?: ProbeUpload.isAutoRawLocationEnabled(applicationContext)
                p.startAutoCapture(rawLoc)
                log.i("automatic EV Insights telemetry stream armed (rawLocation=$rawLoc)")
            } else {
                p.startCapture(intent?.getIntExtra("seconds", 60) ?: 60,
                    intent?.getBooleanExtra("raw_location", false) ?: false)
            }
        } catch (t: Exception) {
            unavailable = "Capture could not start: ${t.javaClass.name}: ${t.message}"
            log.w("probe service start failed: $unavailable")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            observer?.invoke()
        }
        return START_NOT_STICKY
    }
    override fun onDestroy() {
        observer = null
        recorder?.close()
        recorder = null
        super.onDestroy()
    }
    // NOTE: Service.onTimeout(int, int) exists only from API 35 (Android 15 dataSync FGS time
    // limits). This app compiles against the head unit's android-32 platform (see tools/build_apk.sh),
    // where the base method does not exist, so an `override fun onTimeout` is a compile error here.
    // The head unit is API 32 and never delivers that callback, so there is nothing to handle.
    companion object {
        const val START = "vehicle_probe_start_capture"
        const val AUTO_START = "vehicle_probe_auto_stream"
        const val FLUSH_NOW = "vehicle_probe_flush_now"
        const val STOP = "vehicle_probe_stop"
        private const val CHANNEL = "vehicle_telemetry_probe"
        private const val NOTIFICATION_ID = 7612

        fun ensureAutoStarted(ctx: Context) {
            val appCtx = ctx.applicationContext
            ProbeUpload.enqueueAndDrain(appCtx)
            if (!ProbeUpload.isAutoUploadEnabled(appCtx)) return
            runCatching {
                appCtx.startForegroundService(
                    Intent(appCtx, ProbeService::class.java)
                        .setAction(AUTO_START)
                        .putExtra("raw_location", ProbeUpload.isAutoRawLocationEnabled(appCtx)),
                )
            }
        }

        fun stopAuto(ctx: Context) {
            val appCtx = ctx.applicationContext
            runCatching {
                appCtx.startService(Intent(appCtx, ProbeService::class.java).setAction(STOP))
            }
        }
    }
}
