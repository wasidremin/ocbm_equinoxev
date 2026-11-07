package wasidremin.gmccpa.lyrics

import kotlin.math.roundToLong

/** One timed line: shown from [atMs] into the song until the next line's time. Blank for an instrumental gap. */
data class LyricLine(
    val atMs: Long,
    val text: String,
)

/** A song's lyrics in time with it ([LrcLib]), lines in time order. Ported from enigy/carlink `media/SyncedLyrics.kt` (Unlicense). */
class SyncedLyrics(
    val lines: List<LyricLine>,
) {
    /** The line being sung at [positionMs]: the last one that has started, -1 before the first. */
    fun indexAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].atMs <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    companion object {
        /**
         * The timed lines of an LRC text (`[mm:ss.xx] words`, LRCLIB's `syncedLyrics`), or null when it has
         * none. A line may carry several stamps (a chorus sung twice) and is placed at each; tags such as
         * `[ar:…]` and lines without a stamp are dropped.
         */
        fun parse(lrc: String): SyncedLyrics? {
            val lines = mutableListOf<LyricLine>()
            for (raw in lrc.lineSequence()) {
                var rest = raw.trim()
                val stamps = mutableListOf<Long>()
                while (true) {
                    val m = STAMP.matchAt(rest, 0) ?: break
                    val minutes = m.groupValues[1].toLong()
                    val seconds = m.groupValues[2].toDouble()
                    stamps += minutes * MS_PER_MINUTE + (seconds * MS_PER_SECOND).roundToLong()
                    rest = rest.substring(m.range.last + 1)
                }
                stamps.mapTo(lines) { LyricLine(it, rest.trim()) }
            }
            return if (lines.isEmpty()) null else SyncedLyrics(lines.sortedBy { it.atMs })
        }

        /** `[mm:ss]`, `[mm:ss.x]` to `[mm:ss.xxx]`. Both brackets escaped: Android's ICU engine is stricter than the JVM's. */
        private val STAMP = Regex("""\[(\d{1,3}):(\d{1,2}(?:\.\d{1,3})?)\]""")
        private const val MS_PER_MINUTE = 60_000L
        private const val MS_PER_SECOND = 1_000.0
    }
}
