package wasidremin.gmccpa.ocbm

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog

/**
 * The Wi-Fi link to the car's hotspot (docs/ops/08_FUTURE_TASKS.md T15), removed on 2026-11-25. In the car
 * it made every start wait up to 15 s for the hotspot before Bluetooth came up, and its join was a start
 * behind the hotspot's channel (149, 153, then 149 again on 2026-11-25).
 *
 * Firmware from before the removal still joins at every start while `/script/sta_uplink.conf` says
 * `enabled=1`, and holds the link key. Updated firmware deletes both (`session_supervisor.sh`); until
 * the owner updates the adapter, each connect turns the join off and clears the key here. Firmware
 * without the link says neither `"sta_uplink"` nor `"wifi_link"` in `MGMT_INFO`, and this does nothing.
 * Logs say what was undone, never the hotspot's name or passphrase.
 */
object RetiredWifiLink {
    private val log by lazy { ProbeLog.sub("usb") }
    const val STA_CONF = "/script/sta_uplink.conf"
    const val STA_OFF = "enabled=0\n"

    /** What to undo on one adapter. */
    data class Undo(val join: Boolean, val key: Boolean)

    /**
     * Pure: from the start's `MGMT_INFO`, whether the adapter carries the old join (the file is then read
     * to see if it is on) and whether it holds a link key (a non-empty `wifi_link.id`).
     */
    fun undo(mgmtInfo: String?): Undo = runCatching {
        val j = JSONObject(mgmtInfo ?: return Undo(join = false, key = false))
        Undo(
            join = j.optBoolean("sta_uplink", false),
            key = j.optJSONObject("wifi_link")?.optString("id", "").orEmpty().isNotEmpty(),
        )
    }.getOrDefault(Undo(join = false, key = false))

    /** Pure: whether the adapter's join file (null: none) still makes it join or scan. */
    fun joinIsOn(file: String?): Boolean = file?.startsWith("enabled=1") == true

    /** On each connect, with the start's `MGMT_INFO`. Blocking; on the session thread. */
    fun atConnect(c: OcbmClient, mgmtInfo: String?) {
        val u = undo(mgmtInfo)
        if (u.join && joinIsOn(c.fileRead(STA_CONF).bytes?.toString(Charsets.UTF_8))) {
            val ok = c.filePush(STA_CONF, STA_OFF.toByteArray(Charsets.UTF_8), 384)
            log.i("retired Wi-Fi link: the adapter's join of the car's hotspot turned off written=$ok")
        }
        if (u.key) {
            val status = c.mgmtAction(Ocbm.MGMT_WIFI_LINK, ByteArray(0), 5_000)
            log.i("retired Wi-Fi link: the adapter's link key cleared status=$status")
        }
    }

    /** The app's own settings for it (2026-11-25): the join switch, and the link key and last address. */
    fun forgetSettings(ctx: Context) {
        for (name in listOf("sta_uplink", "wifi_link")) ctx.applicationContext.deleteSharedPreferences(name)
    }
}
