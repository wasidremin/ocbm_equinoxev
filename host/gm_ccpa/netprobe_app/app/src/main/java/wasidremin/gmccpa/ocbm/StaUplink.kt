package wasidremin.gmccpa.ocbm

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings ▸ Advanced ▸ Join the car's hotspot (docs/ops/08_FUTURE_TASKS.md T15 route B), off by
 * default. From 2026-11-21 the link carries the app's connection to the adapter ([WifiLink]): the
 * adapter's keeper (`radio_hal.sh sta_keep`) holds it from boot.
 *
 * On, with an adapter whose `MGMT_INFO` says `"sta_uplink":true`, the app writes
 * `/script/sta_uplink.conf` with the car hotspot's name and, when it has a usable one, its
 * passphrase. The radio layer (`radio_hal.sh`, "THE CAR'S HOTSPOT") joins before any access point
 * is up and starts the access point on the joined channel; while one is up a client that is not
 * joined is stopped. Joining after the access point was up took the radio away from the phone's
 * handoff (bench 2026-11-16). Without a passphrase it is the scan-only diagnostic of 2026-11-16 (Play 256-258). The app
 * also logs when the car's own hotspot interface comes up after the app starts ([watchHotspot]).
 *
 * Off, the app writes `enabled=0`. Changing the setting or the name reaches a connected adapter at
 * once ([pushNow]), not only at the next connect: a Restart Wi-Fi on 2026-11-15 ran with the old file.
 *
 * History: the 2026-11-15 builds (254, 255) made the adapter join and move its access point; a
 * failed join in the car left the client scanning on the one radio and the phone never connected.
 * That path is gone from the adapter; see T15.
 */
object StaUplink {
    private val log by lazy { ProbeLog.sub("usb") }
    const val PATH = "/script/sta_uplink.conf"
    private const val PREFS = "sta_uplink"
    private const val KEY_ON = "enabled"
    /** The adapter's client-capable interface (NXP IW416, `drv_mode=3`), measured on the bench. */
    const val STA_IFACE = "sta0"
    /** How long the app looks for the car's hotspot coming up. */
    const val WATCH_MS = 240_000L
    private val watching = AtomicBoolean(false)

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        log.i("hotspot-check: ${if (on) "ON" else "off"} (Settings)")
        pushNow(ctx)
    }

    /** Pure: whether this adapter carries the Wi-Fi client the scan needs. */
    fun supported(mgmtInfo: String?): Boolean =
        runCatching { JSONObject(mgmtInfo ?: return false).optBoolean("sta_uplink", false) }.getOrDefault(false)

    /**
     * Pure: the file for the adapter. A name that cannot be written safely turns it off; a
     * passphrase that cannot (or none) leaves it a scan, which never joins.
     */
    fun conf(on: Boolean, ssid: String?, psk: String? = null): String {
        if (!on || ssid.isNullOrEmpty() || !safe(ssid) || ssid.length > 32) return "enabled=0\n"
        val key = psk?.takeIf { it.length in 8..63 && safe(it) }?.let { "psk=$it\n" } ?: ""
        return "enabled=1\nsta_iface=$STA_IFACE\nssid=$ssid\n$key"
    }

    /** Pure: printable, and nothing that would end a line or a quoted string in the adapter's files. */
    internal fun safe(s: String): Boolean = s.all { it.code in 0x20..0x7E && it != '"' && it != '\\' }

    // Settings ▸ Advanced ▸ Vehicle hotspot (Ui's CRED_PREFS, KEY_SSID and KEY_PASS).
    private fun creds(ctx: Context) = ctx.applicationContext.getSharedPreferences("hotspot_credentials", Context.MODE_PRIVATE)

    /** The file this app wants on the adapter now ([conf] of the setting and the Vehicle hotspot fields). */
    fun wanted(ctx: Context): String =
        conf(enabled(ctx), creds(ctx).getString("ssid", null), creds(ctx).getString("pass", null))

    /** Pure: whether [file] makes the adapter join (not only scan): what the Wi-Fi link ([WifiLink]) rides. */
    fun joins(file: String): Boolean = file.startsWith("enabled=1") && file.contains("\npsk=")

    /**
     * Write the file if what the adapter has differs. Blocking; off the main thread. Logs whether it
     * was written, never the name or the passphrase.
     */
    fun sync(ctx: Context, c: OcbmClient, mgmtInfo: String?) {
        if (enabled(ctx)) watchHotspot()
        if (!supported(mgmtInfo)) return
        val want = wanted(ctx)
        val have = c.fileRead(PATH).bytes?.toString(Charsets.UTF_8)
        if (have == want || (have == null && want == "enabled=0\n")) return
        val ok = c.filePush(PATH, want.toByteArray(Charsets.UTF_8), 384)
        val on = want.startsWith("enabled=1")
        val join = joins(want)
        log.i("hotspot-check: adapter set ${if (!on) "off" else if (join) "to join the car's hotspot at its next Wi-Fi start" else "to scan for the car's hotspot at its next boot (no usable password)"} written=$ok")
        if (enabled(ctx) && !on) log.w("hotspot-check: on, but the hotspot name is missing or unusable; left off")
    }

    /** The setting or the hotspot name changed: tell a connected adapter now. */
    fun pushNow(ctx: Context) {
        val c = AdapterSession.client ?: return
        Thread({ runCatching { sync(ctx, c, c.mgmtGetInfo(3_000)) } }, "hotspot-check").apply { isDaemon = true }.start()
    }

    /** Pure: an interface name that is the car's own hotspot, not its Wi-Fi client (`wlan0`). */
    fun isHotspotName(name: String): Boolean {
        val n = name.lowercase()
        return n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("br") || n.contains("softap")
    }

    /**
     * Once per process: log when the car's hotspot interface has an address, in seconds after this
     * app's process started, checking every 2 s for [WATCH_MS]. Not after the head unit started: it
     * sleeps rather than restarts when the car is off, and read +600,892 s on the car (2026-11-16).
     * Names and counts only.
     */
    fun watchHotspot() {
        if (!watching.compareAndSet(false, true)) return
        Thread({
            val start = SystemClock.elapsedRealtime()
            var seen = false
            while (SystemClock.elapsedRealtime() - start < WATCH_MS) {
                val up = runCatching {
                    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                        .filter { it.isUp && isHotspotName(it.name) && it.inetAddresses.toList().any { a -> a is Inet4Address } }
                        .map { it.name }
                }.getOrDefault(emptyList())
                if (up.isNotEmpty()) {
                    log.i("hotspot-check: the car's hotspot is up (${up.joinToString(",")}) at +${(SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime()) / 1000}s after the app started")
                    seen = true
                    break
                }
                Thread.sleep(2_000)
            }
            if (!seen) log.w("hotspot-check: the car's hotspot did not come up within ${WATCH_MS / 1000}s of checking")
        }, "hotspot-watch").apply { isDaemon = true }.start()
    }
}
