package wasidremin.gmccpa.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.logging.LogExport
import wasidremin.gmccpa.logging.UploadWindow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A log upload the driver asked for that waits for Wi-Fi.
 *
 * An hour of whole-OS capture is tens of megabytes even gzipped, which is a large share of the
 * car's monthly data. Off Wi-Fi the driver is offered this instead of sending at once: the request
 * (range and the moment of the tap) is remembered, and it goes out by itself the next time the
 * head unit's default network is Wi-Fi - home, or a phone hotspot. The range stays anchored to the
 * tap, so "the last hour" still means the hour before the fault, plus whatever followed.
 *
 * One request at a time: a second tap replaces the first, keeping the earlier start.
 */
object LogsOnWifi {
    /** A waiting request: the range picked and when the driver tapped. */
    data class Request(val window: UploadWindow, val atMs: Long)

    /** The capture directory is pruned by age and size, so an old request has nothing left to send. */
    const val EXPIRES_AFTER_MS = 3L * 24 * 60 * 60 * 1000

    fun isExpired(request: Request, nowMs: Long): Boolean =
        nowMs - request.atMs > EXPIRES_AFTER_MS || request.atMs > nowMs + 60 * 60 * 1000L

    /** A new tap while one waits: keep whichever reaches further back. */
    fun merge(waiting: Request?, tapped: Request): Request {
        if (waiting == null) return tapped
        val wider = if (tapped.window.ordinal >= waiting.window.ordinal) tapped.window else waiting.window
        return Request(wider, minOf(waiting.atMs, tapped.atMs))
    }

    /**
     * Pure: what still waits after the driver's own send of [sent]. Sent, the waiting request goes too,
     * unless it reaches further back; failed, [sent] waits for Wi-Fi with it. 2026-11-27: a waiting
     * "Today" went out 10 s after the driver had sent "Today" by hand, and the server got it twice.
     */
    fun afterManual(waiting: Request?, sent: Request, ok: Boolean): Request? =
        if (ok) waiting?.takeIf { it.window.ordinal > sent.window.ordinal } else merge(waiting, sent)

    /** The line shown while a request waits. Numbers only. */
    fun describe(request: Request): String = "Logs from ${request.window.phrase} are waiting for Wi-Fi"

    private const val PREFS = "upload_center_prefs"
    private const val KEY_WINDOW = "deferred_log_window"
    private const val KEY_AT = "deferred_log_at"
    private val log by lazy { ProbeLog.sub("lwifi") }
    private val sending = AtomicBoolean(false)
    @Volatile private var attached = false

    fun pending(ctx: Context): Request? {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val name = p.getString(KEY_WINDOW, null) ?: return null
        val at = p.getLong(KEY_AT, 0L)
        if (at <= 0L) return null
        return Request(UploadWindow.fromName(name), at)
    }

    fun defer(ctx: Context, window: UploadWindow, nowMs: Long = System.currentTimeMillis()) {
        val appCtx = ctx.applicationContext
        val request = merge(pending(appCtx)?.takeUnless { isExpired(it, nowMs) }, Request(window, nowMs))
        save(appCtx, request)
        log.i("log upload (range=${request.window.label}) will wait for Wi-Fi")
        attach(appCtx)
        UploadCenter.notifyQueueChanged(appCtx)
        flushIfOnWifi(appCtx)
    }

    fun clear(ctx: Context) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_WINDOW).remove(KEY_AT).apply()
        UploadCenter.notifyQueueChanged(ctx.applicationContext)
    }

    /** The driver sends logs by hand. False while the waiting upload is itself going out. */
    fun beginManual(): Boolean = sending.compareAndSet(false, true)

    /** The hand send of [sent] ended; see [afterManual]. */
    fun endManual(ctx: Context, sent: Request, ok: Boolean) {
        val appCtx = ctx.applicationContext
        val next = afterManual(pending(appCtx), sent, ok)
        sending.set(false)
        if (next == null) { clear(appCtx); return }
        save(appCtx, next)
        if (!ok) log.i("log upload (range=${next.window.label}) will wait for Wi-Fi")
        UploadCenter.notifyQueueChanged(appCtx)
    }

    private fun save(ctx: Context, request: Request) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_WINDOW, request.window.name).putLong(KEY_AT, request.atMs).apply()
    }

    /** Watch the default network. Safe to call more than once. */
    fun attach(ctx: Context) {
        val appCtx = ctx.applicationContext
        if (attached) { flushIfOnWifi(appCtx); return }
        attached = true
        runCatching {
            appCtx.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(
                object : ConnectivityManager.NetworkCallback() {
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                        noteLink(appCtx)
                        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                        ) flushIfOnWifi(appCtx)
                    }

                    override fun onLost(network: Network) = noteLink(appCtx)
                }
            )
        }.onFailure { attached = false; log.w("cannot watch the network: ${it.javaClass.simpleName}") }
        flushIfOnWifi(appCtx)
    }

    @Volatile private var lastLink: DataMeter.Link? = null

    /** Settings' connection line follows the network; capability callbacks come often, so only on a change. */
    private fun noteLink(ctx: Context) {
        val now = DataMeter.link(ctx)
        if (now == lastLink) return
        lastLink = now
        UploadCenter.notifyQueueChanged(ctx)
    }

    private fun flushIfOnWifi(ctx: Context) {
        val request = pending(ctx) ?: return
        if (isExpired(request, System.currentTimeMillis())) {
            log.w("dropping a log upload that waited more than 3 days for Wi-Fi")
            clear(ctx)
            return
        }
        if (!DataMeter.onWifi(ctx)) return
        if (!sending.compareAndSet(false, true)) return
        log.i("on Wi-Fi: sending the waiting log upload (range=${request.window.label})")
        LogExport.upload(ctx, window = request.window, anchorMs = request.atMs) { result ->
            sending.set(false)
            result.onSuccess {
                // Only clear what was sent: a newer tap made meanwhile stays queued.
                if (pending(ctx) == request) clear(ctx)
                log.i("waiting log upload sent over Wi-Fi: ${it.lines} lines, ${it.wireBytes}B")
            }.onFailure {
                // Kept for the next time Wi-Fi comes up, unless there is nothing left on disk for it.
                if (it.message?.startsWith("no captured lines") == true) clear(ctx)
                log.w("waiting log upload failed: ${it.javaClass.simpleName}: ${it.message?.take(120)}")
            }
        }
    }
}
