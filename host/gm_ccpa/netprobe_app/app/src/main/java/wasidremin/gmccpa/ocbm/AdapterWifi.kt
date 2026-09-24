package wasidremin.gmccpa.ocbm

import android.content.Context
import java.security.SecureRandom

/**
 * Equinox switch. Off by default, so this build still leaves the Silverado on the vehicle hotspot.
 *
 * There is no model detector. The VCU drops the phone's inbound connection to the head unit; the
 * Silverado does not. The driver turns this on only on the car where the head unit cannot be the
 * receiver.
 *
 * The passphrase is generated once and kept. The phone learns it from the adapter's `0x5703`, not
 * from this screen. The channel stays 149 so the adapter is not on channel 36 beside the vehicle
 * hotspot.
 *
 * The SSID the phone is told is `ccpa-<4hex>`, the name the adapter beacons. A subscribe sends
 * that name once this process has read it (`/etc/carplay_ident`, or a box log). Until then the
 * document carries the placeholder [SSID_PLACEHOLDER], which only exists so a supervisor that
 * returns on an empty SSID still writes the channel and passphrase. Sending the placeholder again
 * after the access point is up makes `0x5703` name a network that is not on the air: the rename
 * runs only when hostapd is not already running. Equinox 2026-09-22, the reopen at 22:39.
 */
object AdapterWifi {
    private const val FILE = "adapter_wifi"
    private const val KEY_ON = "on"
    private const val KEY_PASS = "pass"
    private const val KEY_SSID = "ssid"

    /** Non-empty so `apply_host_wifi_creds` writes the channel. Not a network name. */
    const val SSID_PLACEHOLDER = "ccpa"
    const val CHANNEL = "149"

    /** The beacon name: `ccpa-` plus the four hex digits `radio_hal.sh` persists. */
    private val BOX_SSID = Regex("ccpa-[0-9a-f]{4}")
    private val BOX_SSID_IN_LOG = Regex("""(?:BT name=|ssid=["'])(ccpa-[0-9a-f]{4})""")

    fun enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_ON, on).apply()
    }

    /**
     * SSID to put in the next subscribe. The learned box name once we have one, otherwise the
     * placeholder. A new process keeps the name: it is written as soon as it is seen.
     */
    fun ssid(ctx: Context): String {
        val cur = prefs(ctx).getString(KEY_SSID, null) ?: return SSID_PLACEHOLDER
        return if (BOX_SSID.matches(cur)) cur else SSID_PLACEHOLDER
    }

    /** Remember a `ccpa-<4hex>` name. Anything else, including the placeholder, is ignored. */
    fun noteSsid(ctx: Context, name: String) {
        val n = name.trim()
        if (!BOX_SSID.matches(n)) return
        if (prefs(ctx).getString(KEY_SSID, null) == n) return
        prefs(ctx).edit().putString(KEY_SSID, n).apply()
    }

    /** Pull `ccpa-<4hex>` out of a box log line (`BT name=ccpa-fe01`, `ssid="ccpa-fe01"`). */
    fun observeBoxLine(ctx: Context, text: String) {
        val m = BOX_SSID_IN_LOG.find(text) ?: return
        noteSsid(ctx, m.groupValues[1])
    }

    /** At least 8 letters or digits. Created on first read and then stable across sessions. */
    fun passphrase(ctx: Context): String {
        val cur = prefs(ctx).getString(KEY_PASS, null)
        if (cur != null && cur.length >= 8 && cur.all { it.isLetterOrDigit() }) return cur
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789"
        val rnd = SecureRandom()
        val fresh = buildString(16) { repeat(16) { append(alphabet[rnd.nextInt(alphabet.length)]) } }
        prefs(ctx).edit().putString(KEY_PASS, fresh).apply()
        return fresh
    }

    private fun prefs(ctx: Context) =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
