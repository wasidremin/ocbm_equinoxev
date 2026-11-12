package wasidremin.gmccpa.upload

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import wasidremin.gmccpa.ProbeLog
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * What this app has sent over the car's own data connection this calendar month, and the limit at
 * which background uploads stop.
 *
 * Why it exists: the car's data plan has a monthly allowance, and in November 2026 this app's
 * telemetry stream alone used about 1.25 GB of it and the car cut every app off. Nothing in the app
 * knew how much it had sent. Now every upload is counted here, the total is on the Settings page,
 * and background telemetry stops at [limitMb] instead of at the car's cap.
 *
 * **What is counted.** The bytes this app itself puts on the wire: each request body plus
 * [REQUEST_OVERHEAD_BYTES] for headers and TLS. It is a floor, not a meter reading - Android's own
 * per-app counter cannot be used here because it also counts the gigabytes of CarPlay video that
 * cross the USB link to the adapter, which is a network interface but not mobile data.
 *
 * **What counts against the limit.** Everything not sent over Wi-Fi. The car's modem may appear to
 * Android as cellular or as something else, so only a connection that says it is Wi-Fi (a phone
 * hotspot, home Wi-Fi) is treated as free.
 */
object DataMeter {
    enum class Stream(val key: String, val label: String) {
        TELEMETRY("telemetry", "telemetry"),
        LOGS("logs", "logs"),
        SCREENSHOTS("screens", "screenshots"),
        OTHER("other", "other"),
    }

    /** Headers and TLS records for one request; a resumed handshake is about this much more. */
    const val REQUEST_OVERHEAD_BYTES = 1_500L
    const val DEFAULT_LIMIT_MB = 100
    /** The choices offered in Settings; 0 means no limit. */
    val LIMIT_CHOICES_MB = listOf(50, 100, 250, 0)

    /** One month's tally. Pure, so the rollover and the arithmetic are unit-tested. */
    data class Ledger(val month: String, val car: Map<Stream, Long> = emptyMap(), val wifi: Long = 0L) {
        val carTotal: Long get() = car.values.sum()

        /** The same ledger, or an empty one if the calendar month has changed. */
        fun rolledTo(currentMonth: String): Ledger = if (month == currentMonth) this else Ledger(currentMonth)

        fun plus(stream: Stream, bytes: Long, onWifi: Boolean): Ledger {
            if (bytes <= 0L) return this
            return if (onWifi) copy(wifi = wifi + bytes)
            else copy(car = car + (stream to ((car[stream] ?: 0L) + bytes)))
        }
    }

    fun monthKey(epochMs: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val c = Calendar.getInstance(tz).apply { timeInMillis = epochMs }
        return String.format(Locale.ROOT, "%04d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
    }

    fun overLimit(ledger: Ledger, limitMb: Int): Boolean =
        limitMb > 0 && ledger.carTotal >= limitMb.toLong() * MB

    fun formatMb(bytes: Long): String = when {
        bytes <= 0L -> "0 MB"
        bytes < MB / 10 -> "under 0.1 MB"
        bytes < 100 * MB -> String.format(Locale.ROOT, "%.1f MB", bytes / MB.toDouble())
        else -> String.format(Locale.ROOT, "%d MB", bytes / MB)
    }

    fun limitLabel(limitMb: Int): String = if (limitMb > 0) "$limitMb MB" else "No limit"

    /** The Settings row's line. Numbers only. */
    fun describe(ledger: Ledger, limitMb: Int): String = buildString {
        append(formatMb(ledger.carTotal))
        append(if (limitMb > 0) " of $limitMb MB sent over the car's connection" else " sent over the car's connection")
        val parts = Stream.values().mapNotNull { s ->
            (ledger.car[s] ?: 0L).takeIf { it >= MB / 10 }?.let { "${s.label} ${formatMb(it)}" }
        }
        if (parts.size > 1) append(" (").append(parts.joinToString(", ")).append(')')
        append('.')
        if (ledger.wifi >= MB / 10) append(" ${formatMb(ledger.wifi)} more over Wi-Fi, which is not counted.")
        append(
            when {
                limitMb <= 0 -> " Background telemetry is not limited."
                overLimit(ledger, limitMb) -> " The limit is reached: telemetry uploads are paused until next month or Wi-Fi."
                else -> " Telemetry uploads pause at the limit."
            }
        )
    }

    private const val MB = 1024L * 1024L
    private const val PREFS = "data_meter_prefs"
    private const val KEY_MONTH = "month"
    private const val KEY_WIFI = "wifi_bytes"
    private const val KEY_LIMIT = "limit_mb"
    private val log by lazy { ProbeLog.sub("data") }
    private val lock = Any()
    @Volatile private var appCtx: Context? = null
    private var lastWifi: Boolean? = null
    @Volatile private var lastAllowed: Boolean? = null

    /** Idempotent. Until some entry point has called this, [record] has nowhere to write. */
    fun attach(ctx: Context) {
        if (appCtx == null) appCtx = ctx.applicationContext
    }

    fun limitMb(ctx: Context): Int =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LIMIT, DEFAULT_LIMIT_MB)

    fun setLimitMb(ctx: Context, limitMb: Int) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LIMIT, limitMb.coerceAtLeast(0)).apply()
        log.i("monthly limit for background uploads set to ${limitLabel(limitMb)}")
    }

    fun ledger(ctx: Context): Ledger = synchronized(lock) { load(ctx.applicationContext) }

    /** True when the active network says it is Wi-Fi. Unknown counts as the car's connection. */
    fun onWifi(ctx: Context): Boolean = runCatching {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

    /** Short name of the active network's transport, for the log. */
    fun networkLabel(ctx: Context): String = runCatching {
        val cm = ctx.applicationContext.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.getNetworkCapabilities(cm.activeNetwork) ?: return@runCatching "none"
        when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            else -> "other"
        }
    }.getOrDefault("unknown")

    /**
     * May a BACKGROUND upload go out now? Always over Wi-Fi; otherwise only under the monthly limit.
     * Things the driver taps (log upload, a screenshot) are not gated by this - they show their size.
     */
    fun allowBackground(ctx: Context): Boolean {
        val allowed = onWifi(ctx) || !overLimit(ledger(ctx), limitMb(ctx))
        // One line per change, so an uploaded log explains a queue that is not moving.
        if (lastAllowed != allowed) {
            lastAllowed = allowed
            if (allowed) log.i("background telemetry uploads allowed (${networkLabel(ctx)})")
            else log.w("background telemetry uploads paused: the month's ${limitMb(ctx)} MB car-data limit is reached")
        }
        return allowed
    }

    /** Count one request's payload. Safe from any thread; a no-op before [attach]. */
    fun record(stream: Stream, payloadBytes: Long) {
        val ctx = appCtx ?: return
        if (payloadBytes < 0L) return
        val wifi = onWifi(ctx)
        synchronized(lock) {
            val before = load(ctx)
            val after = before.plus(stream, payloadBytes + REQUEST_OVERHEAD_BYTES, wifi)
            save(ctx, after)
            // One line when the connection type changes, so an uploaded log shows what the car's
            // modem looks like to Android; and one when the limit is crossed.
            if (lastWifi != wifi) {
                lastWifi = wifi
                log.i("uploads now go over ${networkLabel(ctx)} (${if (wifi) "not counted" else "counted against the monthly limit"})")
            }
            val limit = limitMb(ctx)
            if (!overLimit(before, limit) && overLimit(after, limit)) {
                log.w("monthly limit of $limit MB reached (${formatMb(after.carTotal)} this month) - background telemetry uploads paused")
            }
        }
    }

    private fun load(ctx: Context): Ledger {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val now = monthKey(System.currentTimeMillis())
        val stored = Ledger(
            month = p.getString(KEY_MONTH, now) ?: now,
            car = Stream.values().associateWith { p.getLong("car_${it.key}", 0L) }.filterValues { it > 0L },
            wifi = p.getLong(KEY_WIFI, 0L),
        )
        return stored.rolledTo(now)
    }

    private fun save(ctx: Context, ledger: Ledger) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_MONTH, ledger.month)
            Stream.values().forEach { putLong("car_${it.key}", ledger.car[it] ?: 0L) }
            putLong(KEY_WIFI, ledger.wifi)
        }.apply()
    }
}
