package wasidremin.gmccpa.av

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import wasidremin.gmccpa.ProbeLog

/**
 * Keeps the process at foreground priority for the life of a CarPlay session.
 *
 * **Why (C3).** The receiver session — OCBM link, USB transport, heartbeat, the Rust control server,
 * the A/V seams and the audio player — lives in this app's process. With no foreground component the
 * moment the user opens another app / a GM dialog / reverse gear backgrounds the task, the process
 * drops to cached and Low-Memory-Killer reclaims it minutes later, taking the live session with it.
 * A started foreground service pins the whole process at foreground priority, so a *backgrounded*
 * (stopped, not destroyed) session survives.
 *
 * **Scope / honest limit.** This raises process priority; it does NOT yet relocate session OWNERSHIP
 * off the Activity. If the Activity is *destroyed* (not merely stopped), its `onDestroy` still tears
 * the seams down. Moving the session objects into this service so they outlive Activity destruction is
 * the larger, hardware-gated slice tracked in docs/11 R2 (T2.2). Start with the priority win, which is
 * safe and covers the common backgrounding case, and add ownership relocation under a flag once it can
 * be verified on the truck.
 */
class CarPlaySessionService : Service() {

    private val log = ProbeLog.sub("cpsvc")

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promote()
        // START_STICKY would relaunch us with a null intent after an LMK kill, but a session cannot be
        // resumed without the phone re-dialing anyway, so do NOT auto-restart into an empty session.
        return START_NOT_STICKY
    }

    /**
     * Pin the process without taking the session down.
     *
     * The manifest used to declare only `microphone`. On API 34+ `startForeground` then requires
     * a granted `RECORD_AUDIO`. A Play install does not grant that (it is a runtime permission;
     * `adb install -g` is the path the mic comment assumed). The resulting `SecurityException`
     * is thrown on the main thread from [onStartCommand], which is the first thing
     * [CarPlayActivity] does when the video key arrives — the process dies and the driver sees
     * a force-close the moment CarPlay connects. Equinox 2026-09-22, versionCode 34 and 35.
     *
     * `mediaPlayback` and `connectedDevice` need no runtime grant (`CHANGE_NETWORK_STATE` is
     * already declared for the latter). The microphone type is added only when the permission
     * is actually held. A refusal is logged and retried with the playback type alone, because
     * an uncaught throw here is the crash, and failing to call `startForeground` at all is the
     * system's follow-up kill.
     */
    private fun promote() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT < 29) {
            startForeground(NOTIF_ID, n)
            log.i("foreground session service started (process pinned to foreground priority)")
            return
        }
        val micGranted =
            checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (micGranted && Build.VERSION.SDK_INT >= 30) {
            types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else if (!micGranted) {
            log.w("RECORD_AUDIO not granted — session stays up without the microphone type. " +
                "Siri and calls stay silent until the permission is granted.")
        }
        try {
            startForeground(NOTIF_ID, n, types)
            log.i("foreground session service started (types=0x${types.toString(16)})")
        } catch (e: Exception) {
            log.e("startForeground(types=0x${types.toString(16)}) refused: $e — retrying as media playback only")
            try {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                log.i("foreground session service started (media playback only)")
            } catch (e2: Exception) {
                log.e("startForeground(mediaPlayback) refused: $e2 — the system may reclaim this process")
            }
        }
    }

    override fun onDestroy() {
        log.i("foreground session service stopped")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // minSdk 26: channels are mandatory and createNotificationChannel is idempotent.
        mgr.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "CarPlay session", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("CarPlay active")
            .setContentText("Wireless CarPlay session running")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "carplay_session"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            ctx.startForegroundService(Intent(ctx, CarPlaySessionService::class.java))
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, CarPlaySessionService::class.java))
        }
    }
}
