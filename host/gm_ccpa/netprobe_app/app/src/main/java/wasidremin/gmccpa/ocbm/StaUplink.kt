package wasidremin.gmccpa.ocbm

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Settings ▸ Advanced ▸ Check the car's hotspot (docs/ops/08_FUTURE_TASKS.md T15 route B), off by
 * default. A diagnostic, not the feature: it finds out whether the adapter joining the car's hotspot
 * can work at all before that is built.
 *
 * On, with an adapter whose `MGMT_INFO` says `"sta_uplink":true`, the app writes
 * `/script/sta_uplink.conf` with the car hotspot's name only (a scan needs no password). At the first
 * Wi-Fi start of each adapter boot the radio layer scans once and logs, numbers and flags only,
 * whether that network is visible, its band, channel and security, then stops the scan before
 * starting its own access point exactly as always (`radio_hal.sh` `hotspot_scan`). The app also logs
 * when the car's own hotspot interface comes up after the head unit starts ([watchHotspot]).
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
    /** How long after the head unit starts the app looks for the car's hotspot coming up. */
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

    /** Pure: the file for the adapter. A name that cannot be written safely turns it off. */
    fun conf(on: Boolean, ssid: String?): String {
        if (!on || ssid.isNullOrEmpty() || !safe(ssid) || ssid.length > 32) return "enabled=0\n"
        return "enabled=1\nsta_iface=$STA_IFACE\nssid=$ssid\n"
    }

    /** Pure: printable, and nothing that would end a line or a quoted string in the adapter's files. */
    internal fun safe(s: String): Boolean = s.all { it.code in 0x20..0x7E && it != '"' && it != '\\' }

    private fun hotspotName(ctx: Context): String? =
        // Settings ▸ Advanced ▸ Vehicle hotspot (Ui's CRED_PREFS / KEY_SSID).
        ctx.applicationContext.getSharedPreferences("hotspot_credentials", Context.MODE_PRIVATE).getString("ssid", null)

    /**
     * Write the file if what the adapter has differs. Blocking; off the main thread. Logs whether it
     * was written, never the name.
     */
    fun sync(ctx: Context, c: OcbmClient, mgmtInfo: String?) {
        if (enabled(ctx)) watchHotspot()
        if (!supported(mgmtInfo)) return
        val want = conf(enabled(ctx), hotspotName(ctx))
        val have = c.fileRead(PATH).bytes?.toString(Charsets.UTF_8)
        if (have == want || (have == null && want == "enabled=0\n")) return
        val ok = c.filePush(PATH, want.toByteArray(Charsets.UTF_8), 384)
        val on = want.startsWith("enabled=1")
        log.i("hotspot-check: adapter set ${if (on) "to scan for the car's hotspot at its next boot" else "off"} written=$ok")
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
     * Once per process: log when the car's hotspot interface has an address, in seconds after the
     * head unit started ([SystemClock.elapsedRealtime]), checking every 2 s for [WATCH_MS]. Names
     * and counts only.
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
                    log.i("hotspot-check: the car's hotspot is up (${up.joinToString(",")}) at +${SystemClock.elapsedRealtime() / 1000}s after the head unit started")
                    seen = true
                    break
                }
                Thread.sleep(2_000)
            }
            if (!seen) log.w("hotspot-check: the car's hotspot did not come up within ${WATCH_MS / 1000}s (head unit up ${SystemClock.elapsedRealtime() / 1000}s)")
        }, "hotspot-watch").apply { isDaemon = true }.start()
    }
}
