package wasidremin.gmccpa.logging

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How much of the capture an operator-triggered log upload covers.
 *
 * Whole-OS capture on this head unit measures ~227 MB/h (see [Budget]), so "upload the logs" stopped
 * being a bounded action: a single tap could push the whole 768 MB directory through a vehicle
 * hotspot. The window is the operator's answer to "how much of this do I actually need", and it
 * defaults to the shortest one — the drive that just went wrong is almost always the last hour, and a
 * longer range is one tap away when it is not.
 *
 * The enum is deliberately Android-free: [LogExport.upload] takes one, and the cutoff/filter helpers
 * below are pure so the boundary arithmetic is unit-tested rather than reasoned about.
 */
enum class UploadWindow(val label: String, val phrase: String) {
    /** Default. The last 60 minutes. */
    LAST_HOUR("1 hour", "the last hour"),
    LAST_6_HOURS("6 hours", "the last 6 hours"),
    /** Since local midnight — the driver's day, not a rolling 24 h. */
    TODAY("Today", "today"),
    /** Every capture still on disk (retention keeps that bounded; see [Budget.retentionMs]). */
    ALL("All", "every capture on disk");

    companion object {
        /** Shortest useful range: the drive that just failed. Persisted default is this. */
        val DEFAULT = LAST_HOUR

        /** Unknown/absent persisted value falls back to [DEFAULT] rather than throwing. */
        fun fromName(name: String?): UploadWindow =
            values().firstOrNull { it.name == name } ?: DEFAULT
    }
}

private const val HOUR_MS = 3_600_000L

/**
 * Epoch millis of the oldest log line [window] keeps, or null for "no cutoff".
 *
 * [zone] only matters for [UploadWindow.TODAY], and it is the DEVICE's zone on purpose: "today" means
 * the driver's day. The capture timestamps are UTC (`LogCapture.resolveFormat` passes `-v UTC`), so
 * the caller must convert this instant back to a UTC stamp — [LogTimeFilter.forCutoff] does.
 *
 * A negative hour is never returned for the rolling windows: a clock set before the epoch would
 * otherwise produce a cutoff in the far future and drop everything.
 */
internal fun uploadCutoffMs(
    window: UploadWindow,
    nowMs: Long,
    zone: TimeZone = TimeZone.getDefault(),
): Long? = when (window) {
    UploadWindow.LAST_HOUR -> nowMs - HOUR_MS
    UploadWindow.LAST_6_HOURS -> nowMs - 6 * HOUR_MS
    UploadWindow.TODAY -> startOfDayMs(nowMs, zone)
    UploadWindow.ALL -> null
}

/**
 * Local midnight preceding [nowMs]. Calendar arithmetic rather than `nowMs % 86_400_000`: a DST fold
 * makes the local day 23 or 25 hours long, and modulo arithmetic would land an hour either side of
 * midnight — which for a "today" upload is either a lost hour or a stray one.
 */
internal fun startOfDayMs(nowMs: Long, zone: TimeZone): Long =
    Calendar.getInstance(zone).apply {
        timeInMillis = nowMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

/**
 * Decides whether one captured line is inside an upload window.
 *
 * ## Why a string compare and not a parsed date
 * [LogCapture] captures `logcat -v threadtime -v year -v UTC`, so every line begins with a
 * fixed-width, zero-padded UTC stamp — `2026-10-22 11:22:33.123  1234  1234 I Tag  : msg`. In that
 * form lexicographic order IS chronological order, so the filter compares 19 characters instead of
 * parsing a date per line. A whole-OS capture is ~1.4 M lines per hour; `SimpleDateFormat` per line
 * would dominate the upload's CPU on a head unit.
 *
 * ## Degraded format, and why it is still handled
 * `LogCapture.resolveFormat` falls back to bare `threadtime` (`MM-DD HH:MM:SS`, LOCAL time) when the
 * device's logcat rejects `-v year`/`-v UTC`. A line's own format therefore tells us which clock it
 * is in: a full stamp means UTC, a bare one means local. The filter carries both renderings of the
 * cutoff and picks by the line's shape, so the fallback path is approximate only across a year
 * boundary rather than being off by the zone offset.
 *
 * ## Lines with no stamp are KEPT
 * Capture headers (`LogFiles.header`), seam notices ("SEAM RESYNC GAVE UP…"), and
 * `--------- beginning of main` separators carry no timestamp. They are a handful of lines and they
 * are exactly the context that makes the rest of the file readable, so an unrecognised prefix is
 * never a reason to drop a line. Dropping them would also make the boundary rules impossible to
 * audit from the uploaded artifact itself.
 */
internal class LogTimeFilter private constructor(
    private val utcCutoff: String,
    private val localBareCutoff: String,
) {
    /** True when [line] is inside the window. */
    fun accepts(line: CharSequence): Boolean {
        val stamp = stampedPrefix(line) ?: return true
        return if (stamp.length == FULL_STAMP_LEN) stamp >= utcCutoff else stamp >= localBareCutoff
    }

    companion object {
        /** `yyyy-MM-dd HH:mm:ss` */
        internal const val FULL_STAMP_LEN = 19
        /** `MM-dd HH:mm:ss` */
        internal const val BARE_STAMP_LEN = 14

        /**
         * Filter for [cutoffMs], or null when there is no cutoff ([UploadWindow.ALL]) — in which case
         * the caller must not filter at all rather than build a filter that accepts everything.
         */
        fun forCutoff(cutoffMs: Long?, zone: TimeZone = TimeZone.getDefault()): LogTimeFilter? {
            if (cutoffMs == null) return null
            val instant = Date(cutoffMs)
            // `-v UTC` output is compared against a UTC rendering; bare `threadtime` is local time.
            val utc = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val local = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).apply { timeZone = zone }
            return LogTimeFilter(utc.format(instant), local.format(instant))
        }

        /** The stamp at the head of [line], or null when the line does not start with one. */
        internal fun stampedPrefix(line: CharSequence): String? = when {
            line.length >= FULL_STAMP_LEN && looksLikeFullStamp(line) -> line.subSequence(0, FULL_STAMP_LEN).toString()
            line.length >= BARE_STAMP_LEN && looksLikeBareStamp(line) -> line.subSequence(0, BARE_STAMP_LEN).toString()
            else -> null
        }

        private fun looksLikeFullStamp(s: CharSequence): Boolean =
            digits(s, 0, 4) && s[4] == '-' && digits(s, 5, 2) && s[7] == '-' && digits(s, 8, 2) &&
                s[10] == ' ' && digits(s, 11, 2) && s[13] == ':' && digits(s, 14, 2) &&
                s[16] == ':' && digits(s, 17, 2)

        private fun looksLikeBareStamp(s: CharSequence): Boolean =
            digits(s, 0, 2) && s[2] == '-' && digits(s, 3, 2) && s[5] == ' ' && digits(s, 6, 2) &&
                s[8] == ':' && digits(s, 9, 2) && s[11] == ':' && digits(s, 12, 2)

        private fun digits(s: CharSequence, from: Int, count: Int): Boolean {
            for (i in from until from + count) if (s[i] !in '0'..'9') return false
            return true
        }
    }
}
