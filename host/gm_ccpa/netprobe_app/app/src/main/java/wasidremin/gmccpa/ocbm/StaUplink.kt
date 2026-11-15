package wasidremin.gmccpa.ocbm

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog

/**
 * The adapter joins the car's hotspot (docs/ops/08_FUTURE_TASKS.md T15 route B, 2026-11-15): the
 * first half of reaching it with no USB claim. Settings ▸ Advanced ▸ Join the car's hotspot, off by
 * default.
 *
 * On, and with an adapter whose `MGMT_INFO` says `"sta_uplink":true` (it carries a Wi-Fi client),
 * the app writes `/script/sta_uplink.conf` with the vehicle hotspot's name and passphrase (Settings
 * ▸ Advanced ▸ Vehicle hotspot) at every connect where it differs. The adapter's radio layer then
 * joins that network before raising its own access point, and puts the access point on the same
 * channel. Off, the app writes `enabled=0` once, and the adapter does what it always did.
 *
 * Interface names: `wlan0` is the driver's access-point interface and `sta0` its client on this
 * owner's adapter (NXP IW416, `drv_mode=3`), measured on the bench. The adapter checks both exist
 * and are wireless, and does nothing otherwise.
 *
 * Nothing here carries the connection yet: CarPlay still runs over USB, so the Allow tap stays
 * until the network transport is built (step 4).
 */
object StaUplink {
    private val log by lazy { ProbeLog.sub("usb") }
    const val PATH = "/script/sta_uplink.conf"
    private const val PREFS = "sta_uplink"
    private const val KEY_ON = "enabled"
    const val AP_IFACE = "wlan0"
    const val STA_IFACE = "sta0"

    fun enabled(ctx: Context): Boolean =
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        log.i("sta-uplink: ${if (on) "ON" else "off"} (Settings; from the next connect)")
    }

    /** Pure: whether this adapter can join a network. */
    fun supported(mgmtInfo: String?): Boolean =
        runCatching { JSONObject(mgmtInfo ?: return false).optBoolean("sta_uplink", false) }.getOrDefault(false)

    /** Pure: the file for the adapter. A name or passphrase that cannot be written safely turns it off. */
    fun conf(on: Boolean, ssid: String?, psk: String?): String {
        val ok = on && !ssid.isNullOrEmpty() && safe(ssid) && (psk.isNullOrEmpty() || (psk.length in 8..63 && safe(psk)))
        if (!ok) return "enabled=0\n"
        return "enabled=1\nap_iface=$AP_IFACE\nsta_iface=$STA_IFACE\nssid=$ssid\npsk=${psk.orEmpty()}\n"
    }

    /** Pure: printable, and nothing that would end a line or a quoted string in the adapter's files. */
    internal fun safe(s: String): Boolean = s.all { it.code in 0x20..0x7E && it != '"' && it != '\\' }

    /**
     * On a connect: write the file if what the adapter has differs. Blocking; the caller is off the
     * main thread. Logs whether it was written, never the name or passphrase.
     */
    fun sync(ctx: Context, c: OcbmClient, mgmtInfo: String?) {
        if (!supported(mgmtInfo)) return
        // Settings ▸ Advanced ▸ Vehicle hotspot (Ui's CRED_PREFS / KEY_SSID / KEY_PASS).
        val p = ctx.applicationContext.getSharedPreferences("hotspot_credentials", Context.MODE_PRIVATE)
        val want = conf(enabled(ctx), p.getString("ssid", null), p.getString("pass", null))
        val have = c.fileRead(PATH).bytes?.toString(Charsets.UTF_8)
        // Off, and nothing there: nothing to write.
        if (have == want || (have == null && want == "enabled=0\n")) return
        val ok = c.filePush(PATH, want.toByteArray(Charsets.UTF_8), 384)
        val on = want.startsWith("enabled=1")
        log.i("sta-uplink: adapter set ${if (on) "to join the vehicle hotspot" else "off"} written=$ok (applies at its next Wi-Fi start)")
        if (enabled(ctx) && !on) log.w("sta-uplink: on, but the vehicle hotspot's name or passphrase is missing or unusable; left off")
    }
}
