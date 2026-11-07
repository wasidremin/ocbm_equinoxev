package wasidremin.gmccpa.lyrics

import android.content.Context
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Ported from enigy/carlink `media/LyricsDiskCache.kt` (Unlicense).
 *
 * Lyrics kept on the head unit, so a song heard before shows its words without asking
 * LRCLIB, which answered HTTP 503 for most songs of one of enigy's drives. One file per song (its title, artist
 * and length, as [LyricsKey] names it), written whole, a temp file renamed into place, with the SHA-256
 * of its words: a file a power loss cut short is never taken for lyrics, since a read checks it and
 * deletes what fails. Only found lyrics are kept; "none" stays in memory ([LrcLib]), so a song LRCLIB
 * lacks today is asked again on a later drive.
 *
 * Entries are kept with no time limit until the folder holds more than [maxBytes] (100 MB, some 25,000
 * songs); then the least played go first, the longest unplayed first among equals, until it is back to
 * [LOW_WATER_PERCENT] of that. Each play
 * counts ([noteUse]). The folder is tidied the first time it is used in a process and after each write.
 * Blocking file IO: call off the main thread.
 */
class LyricsDiskCache(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val lock = Any()
    private var tidied = false

    /** The words kept for [key]: a whole entry for this song. Null otherwise, and a bad entry is deleted. */
    fun read(key: LyricsKey): String? =
        synchronized(lock) {
            tidyOnce()
            entryFor(key)?.optString("lrc")
        }

    /** Keep [lrc] for [key], played no times yet; false when it could not be written (and nothing half-written is left). */
    fun write(
        key: LyricsKey,
        lrc: String,
    ): Boolean =
        synchronized(lock) {
            tidyOnce()
            val now = clock()
            val entry =
                JSONObject()
                    .put("v", VERSION)
                    .put("title", key.title)
                    .put("artist", key.artist)
                    .put("durationSec", key.durationSec)
                    .put("fetchedAt", now)
                    .put("uses", 0)
                    .put("usedAt", now)
                    .put("sha256", sha256(lrc))
                    .put("lrc", lrc)
            val written = save(fileFor(key), entry, now)
            prune(keep = fileFor(key))
            written
        }

    /**
     * One play of [key]'s song with its lyrics: counted, and the time noted, so the songs played most and
     * latest are kept longest. Nothing when the song is not kept.
     */
    fun noteUse(key: LyricsKey) {
        synchronized(lock) {
            val entry = entryFor(key) ?: return
            val now = clock()
            entry.put("uses", uses(entry) + 1).put("usedAt", now)
            save(fileFor(key), entry, now)
        }
    }

    /** What Settings ▸ Lyrics shows: how many songs are kept, and the bytes they take (what [maxBytes] is held against). */
    data class Usage(
        val songs: Int,
        val bytes: Long,
    )

    fun usage(): Usage =
        synchronized(lock) {
            val files = dir.listFiles().orEmpty()
            Usage(files.count { it.name.endsWith(EXT) }, files.sumOf { it.length() })
        }

    /** Delete every kept song (Settings' Clear): each is looked up again the next time it plays. Returns how many went. */
    fun clear(): Int =
        synchronized(lock) {
            val files = dir.listFiles().orEmpty()
            files.forEach { it.delete() }
            files.count { it.name.endsWith(EXT) }
        }

    /**
     * Stray temp files go. Past [maxBytes], entries go, the least played first and the longest unplayed
     * first among equals, until the folder is down to [LOW_WATER_PERCENT] of it, so a full folder is read
     * through once per thousands of new songs rather than at each. [keep] (the entry just written) stays.
     */
    internal fun prune(keep: File? = null) {
        synchronized(lock) {
            val files = dir.listFiles()?.toMutableList() ?: return
            val stray = files.filter { it.name.endsWith(TMP) }
            stray.forEach { it.delete() }
            files.removeAll(stray)
            var total = files.sumOf { it.length() }
            if (total <= maxBytes) return
            val target = maxBytes / 100 * LOW_WATER_PERCENT
            val byPlays = files.filter { it != keep }.map { it to plays(it) }.sortedWith(compareBy({ it.second.first }, { it.second.second }))
            for ((f, _) in byPlays) {
                if (total <= target) break
                total -= f.length()
                f.delete()
            }
        }
    }

    private fun tidyOnce() {
        if (tidied) return
        tidied = true
        prune()
    }

    /** [key]'s entry when it is whole and for [key]; null otherwise, and a bad file is deleted. */
    private fun entryFor(key: LyricsKey): JSONObject? {
        val file = fileFor(key)
        if (!file.isFile) return null
        val entry =
            try {
                JSONObject(file.readText()).takeIf { whole(it, key) }
            } catch (_: IOException) {
                null
            } catch (_: JSONException) {
                null
            }
        if (entry == null) file.delete()
        return entry
    }

    /** Write [entry] to [file] whole, through a temp file; false (and no temp file left) when it could not. */
    private fun save(
        file: File,
        entry: JSONObject,
        now: Long,
    ): Boolean {
        val tmp = File(dir, file.name + TMP)
        val written =
            try {
                dir.mkdirs()
                tmp.writeText(entry.toString())
                val moved = tmp.renameTo(file)
                if (moved) file.setLastModified(now)
                moved
            } catch (_: IOException) {
                false
            }
        if (!written) tmp.delete()
        return written
    }

    private fun fileFor(key: LyricsKey): File = File(dir, sha256("${key.title}\n${key.artist}\n${key.durationSec}") + EXT)

    companion object {
        /** 100 MB as Settings shows sizes (1 MB = 1,000,000 bytes), then the least played make room. An entry is a few KB. */
        const val MAX_BYTES = 100L * 1000 * 1000

        /** A full folder is pruned to this share of [MAX_BYTES]. */
        const val LOW_WATER_PERCENT = 90

        private const val VERSION = 1
        private const val EXT = ".json"
        private const val TMP = ".tmp"
        private const val FOLDER = "lyrics"

        @Volatile private var instance: LyricsDiskCache? = null

        /** The app's cache, in its no-backup files folder: kept across drives, never copied off the head unit. */
        fun get(context: Context): LyricsDiskCache =
            instance ?: synchronized(this) {
                instance ?: LyricsDiskCache(File(context.applicationContext.noBackupFilesDir, FOLDER)).also { instance = it }
            }

        internal fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { (it.toInt() and BYTE).toString(HEX).padStart(2, '0') }

        private const val BYTE = 0xFF
        private const val HEX = 16

        /** An entry's plays and last play, to order what goes first; an unreadable file goes before any. */
        private fun plays(file: File): Pair<Int, Long> =
            try {
                val entry = JSONObject(file.readText())
                uses(entry) to entry.optLong("usedAt", entry.optLong("fetchedAt", file.lastModified()))
            } catch (_: IOException) {
                -1 to 0L
            } catch (_: JSONException) {
                -1 to 0L
            }

        /** Its plays; an entry with no count was played at least once. */
        private fun uses(entry: JSONObject): Int = entry.optInt("uses", 1)

        /** The entry's words are whole and for [key]. */
        private fun whole(
            entry: JSONObject,
            key: LyricsKey,
        ): Boolean {
            val forKey = entry.optString("title") == key.title && entry.optString("artist") == key.artist && entry.optLong("durationSec", -1) == key.durationSec
            val lrc = entry.optString("lrc")
            val intact = lrc.isNotEmpty() && sha256(lrc) == entry.optString("sha256")
            return entry.optInt("v") == VERSION && forKey && intact && SyncedLyrics.parse(lrc) != null
        }
    }
}
