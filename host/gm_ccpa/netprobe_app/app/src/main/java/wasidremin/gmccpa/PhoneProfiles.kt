package wasidremin.gmccpa

import android.content.Context
import org.json.JSONObject

/**
 * Settings per iPhone (T39, owner 2026-11-26): the sound, the media pane and the screen layout follow
 * whichever phone drives, the owner's or their wife's.
 *
 * No setting is hooked. The settings stay where they always lived; when a different phone takes over,
 * the values in place are kept as the previous phone's ([save]) and the new phone's are put in place
 * ([load]). A phone seen for the first time keeps the values as they are, which then become its own.
 *
 * It runs twice per connect. Before `CT_SUBSCRIBE`, for the phone the adapter will page first
 * ([PhoneSchedule.expectedFirst]), so the layout and the pane's side, which the subscribe declares,
 * are that phone's from the first frame. And at `CT_PHONE_IDENT`, for the phone that came: if it is the
 * other one, the sound follows at once, and its layout and pane from its next connect, rather than a
 * restart of a working session. The log names phones by position in the adapter's list, never by name
 * or address.
 */
object PhoneProfiles {
    /** A stored setting that follows the phone: its preference file and key. */
    data class Key(val file: String, val key: String) {
        val id: String get() = "$file/$key"
    }

    /** What follows the phone. Each is read where it always was; see `SETTINGS_AUDIT.md`. */
    val KEYS = listOf(
        Key("sound", "eq_preset"), Key("sound", "eq_custom"), Key("sound", "speed_loudness"),
        Key("media_pane", "side"), Key("media_pane", "percent"), Key("media_pane", "shown"),
        Key("carplay_screen", "mode"),
    )

    /** What a change of phone does: keep the values in place as [saveAs]'s, put [loadFrom]'s in place. */
    data class Step(val saveAs: String?, val loadFrom: String?)

    /** Pure: from the phone whose values are in place ([current], null at first) to [next]. */
    fun step(current: String?, next: String, stored: Set<String>): Step =
        if (current == next) Step(null, null) else Step(current, next.takeIf { it in stored })

    /** Pure: the values as stored, by [Key.id]; a key with no value is left out. */
    fun encode(values: Map<String, Any?>): String =
        JSONObject().apply { values.forEach { (k, v) -> if (v != null) put(k, v) } }.toString()

    /** Pure: the stored values; unreadable is empty, so nothing is put in place. */
    fun decode(json: String?): Map<String, Any> = runCatching {
        val o = JSONObject(json ?: return emptyMap())
        o.keys().asSequence().associateWith { o.get(it) }.filterKeys { k -> KEYS.any { it.id == k } }
    }.getOrDefault(emptyMap())

    private const val PREFS = "phone_profiles"
    private const val KEY_CURRENT = "current"
    private const val PROFILE = "p."
    private val log by lazy { ProbeLog.sub("phone") }

    /** The adapter's phones at this connect, in its order, for the log's positions. */
    @Volatile private var bonded: List<String> = emptyList()

    /** Before `CT_SUBSCRIBE`, with the connect's `MGMT_INFO`: the phone the adapter will page first. */
    fun atConnect(ctx: Context, mgmtInfo: String?) {
        bonded = PhoneBook.devicesOf(mgmtInfo)
        val first = PhoneSchedule.expectedFirst(ctx, mgmtInfo) ?: return
        switchTo(ctx, first, bonded.indexOf(first) + 1, bonded.size, "expected first")
    }

    /** At `CT_PHONE_IDENT`, with the phone that came. */
    fun atIdentity(ctx: Context, mac: String) {
        val m = PhoneBook.normalize(mac)
        if (switchTo(ctx, m, bonded.indexOf(m) + 1, bonded.size, "connected")) {
            log.i("phone settings: the sound follows now; the layout and the pane's side from this phone's next connect")
        }
    }

    /**
     * [mac] is about to drive (or drives). [position] is its place in the adapter's list for the log;
     * [why] says which of the two moments this is. Returns true when its values were put in place.
     */
    @Synchronized private fun switchTo(ctx: Context, mac: String, position: Int, count: Int, why: String): Boolean {
        val app = ctx.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val next = PhoneBook.normalize(mac)
        val stored = p.all.keys.filter { it.startsWith(PROFILE) }.map { it.removePrefix(PROFILE) }.toSet()
        val s = step(p.getString(KEY_CURRENT, null), next, stored)
        if (s.saveAs == null && s.loadFrom == null && p.getString(KEY_CURRENT, null) == next) return false
        val edit = p.edit()
        s.saveAs?.let { edit.putString(PROFILE + it, encode(read(app))) }
        s.loadFrom?.let { write(app, decode(p.getString(PROFILE + it, null))) }
        edit.putString(KEY_CURRENT, next).apply()
        if (s.loadFrom != null) {
            wasidremin.gmccpa.sound.Equalizer.load(app)
            wasidremin.gmccpa.sound.SpeedLoudness.load(app)
        }
        log.i("phone settings ($why): phone $position of $count drives — " +
            if (s.loadFrom != null) "its sound, pane and layout put in place" else "first time here, the settings in place become its own")
        return s.loadFrom != null
    }

    /** A phone forgotten on the adapter takes its settings with it. */
    fun forget(ctx: Context, mac: String) {
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(PROFILE + PhoneBook.normalize(mac)).apply()
    }

    private fun read(app: Context): Map<String, Any?> =
        KEYS.associate { k -> k.id to app.getSharedPreferences(k.file, Context.MODE_PRIVATE).all[k.key] }

    private fun write(app: Context, values: Map<String, Any>) {
        for (k in KEYS) {
            val v = values[k.id] ?: continue
            val e = app.getSharedPreferences(k.file, Context.MODE_PRIVATE).edit()
            when (v) {
                is Boolean -> e.putBoolean(k.key, v)
                is Int -> e.putInt(k.key, v)
                is Long -> e.putInt(k.key, v.toInt())
                is Number -> e.putInt(k.key, v.toInt())
                else -> e.putString(k.key, v.toString())
            }
            e.apply()
        }
    }
}
