package wasidremin.gmccpa.sound

import android.content.Context
import wasidremin.gmccpa.ProbeLog
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The equalizer (T37, owner 2026-11-26: "the full fledged equalizer"): ten octave bands over CarPlay's
 * media audio, from presets picked with one tap at any time, or a custom curve set in Park. Shaped by
 * [ToneShaper] with T36's speed lift on top. Calls, Siri and directions are not touched.
 *
 * The presets, the stored form and the editor's geometry are pure and tested; [load]/[select]/[setCustom]
 * do the preferences.
 */
object Equalizer {
    /** Octave centres, 31 Hz to 16 kHz. */
    val BANDS_HZ: DoubleArray get() = ToneShaper.BANDS_HZ
    const val MAX_DB = 12.0
    const val STEP_DB = 0.5

    enum class Preset(val key: String, val label: String, val gains: DoubleArray?) {
        FLAT("flat", "Flat", DoubleArray(10)),
        BASS("bass", "Bass", doubleArrayOf(5.0, 5.0, 4.0, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)),
        VOCAL("vocal", "Vocal", doubleArrayOf(-3.0, -3.0, -2.0, 0.0, 2.0, 3.0, 3.0, 2.0, 0.0, -1.0)),
        TREBLE("treble", "Treble", doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 3.0, 4.0, 4.0)),
        LIVELY("lively", "Lively", doubleArrayOf(4.0, 3.0, 1.0, 0.0, -1.0, -1.0, 0.0, 2.0, 3.0, 3.0)),
        /** The owner's own curve, edited in Park; its gains are stored, not fixed. */
        CUSTOM("custom", "Custom", null),
    }

    val DEFAULT = Preset.FLAT

    fun presetFor(key: String?): Preset = Preset.values().firstOrNull { it.key == key } ?: DEFAULT

    /** Pure: the stored custom curve, `"0,1.5,…"`; anything malformed is flat. */
    fun parse(stored: String?): DoubleArray {
        val parts = stored?.split(',') ?: return DoubleArray(10)
        if (parts.size != 10) return DoubleArray(10)
        val v = parts.map { it.trim().toDoubleOrNull() ?: return DoubleArray(10) }
        return DoubleArray(10) { snap(v[it]) }
    }

    /** Pure: the curve as stored. */
    fun format(gains: DoubleArray): String = gains.joinToString(",") { String.format(Locale.US, "%.1f", it) }

    /** Pure: a gain kept within ±[MAX_DB] in [STEP_DB] steps. */
    fun snap(db: Double): Double = ((db.coerceIn(-MAX_DB, MAX_DB) / STEP_DB).roundToInt() * STEP_DB).let { if (it == 0.0) 0.0 else it }

    /** Pure: a band's frequency as the editor writes it. */
    fun bandLabel(hz: Double): String = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else hz.roundToInt().toString()

    /** Pure: the log line, numbers only. */
    fun describe(preset: Preset, gains: DoubleArray): String =
        "equalizer: ${preset.key} [${gains.joinToString(" ") { String.format(Locale.US, "%+.1f", it) }}]"

    /** Pure: true when the curve does nothing. */
    fun isFlat(gains: DoubleArray): Boolean = gains.all { abs(it) < ToneShaper.BYPASS_DB }

    private const val PREFS = "sound"
    private const val KEY_PRESET = "eq_preset"
    private const val KEY_CUSTOM = "eq_custom"
    private val log by lazy { ProbeLog.sub("sound") }

    @Volatile var preset: Preset = DEFAULT
        private set
    @Volatile var custom: DoubleArray = DoubleArray(10)
        private set
    /** What the audio thread applies; a new array on every change, so the shaper sees the change by identity. */
    @Volatile var bands: DoubleArray = DoubleArray(10)
        private set

    fun load(ctx: Context) {
        val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        custom = parse(p.getString(KEY_CUSTOM, null))
        preset = presetFor(p.getString(KEY_PRESET, null))
        bands = (preset.gains ?: custom).copyOf()
    }

    fun select(ctx: Context, p: Preset) {
        preset = p
        bands = (p.gains ?: custom).copyOf()
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_PRESET, p.key).apply()
        log.i(describe(p, bands))
    }

    /** An edit in the band editor: the curve becomes Custom. [persist] once the finger lifts. */
    fun setCustom(ctx: Context, gains: DoubleArray, persist: Boolean) {
        custom = gains.copyOf()
        preset = Preset.CUSTOM
        bands = gains.copyOf()
        if (persist) {
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY_PRESET, Preset.CUSTOM.key).putString(KEY_CUSTOM, format(gains)).apply()
            log.i(describe(Preset.CUSTOM, gains))
        }
    }
}
