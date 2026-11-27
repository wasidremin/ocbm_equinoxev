package wasidremin.gmccpa.lyrics

import android.content.Context
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.upload.DataMeter
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import kotlin.math.abs

/**
 * The lyrics package: timed lyrics for a few thousand songs in one file, so the car shows lyrics
 * with no connection (owner, 2026-11-14: the car's data is short, and a lookup per song is data).
 *
 * The home server builds it (`host/gm_ccpa/tools/lyrics_pack.py`) from the owner's Spotify library and
 * Billboard's year-end top 100 of each year since 1980, asking lrclib.net there, and the receiver
 * offers it at `GET <base>/lyrics?car=<id>` to a car that has uploaded to it (the gate
 * [wasidremin.gmccpa.telemetry.HistoryImport] uses). The app takes it **only on Wi-Fi or a hotspot**
 * ([DataMeter.onWifi]), automatically at most once a day after an upload has reached the receiver, or
 * from Settings ▸ Screen & Sound ▸ Lyrics.
 *
 * A chart entry has no length and the car's song may be a remaster, so a song is not found by its
 * exact name. [baseTitle] drops what a release adds to a title, [sameArtist] asks for one word in
 * common between the two artist lines, and of what is left the version closest in length within
 * [MATCH_SECONDS] is taken: lyrics timed for a cut of another length would run early or late. The
 * same three rules are in the builder, which keeps up to three lengths of a chart song.
 */
class LyricsPack(private val dir: File) {
    /** One version of a song in the package: where its words are in `pack.dat`. */
    private class Entry(val artist: String, val durationSec: Long, val offset: Long, val length: Int)

    class Meta(val id: String, val songs: Int, val builtMs: Long, val bytes: Long, val fetchedMs: Long)

    private val lock = Any()
    private var index: Map<String, List<Entry>>? = null
    private val dat get() = File(dir, "pack.dat")
    private val idx get() = File(dir, "pack.idx")
    private val metaFile get() = File(dir, "pack.meta")

    /** What is held, or null with no package. */
    fun meta(): Meta? = runCatching {
        val o = JSONObject(metaFile.readText(Charsets.UTF_8))
        Meta(o.getString("id"), o.getInt("songs"), o.optLong("built"), o.optLong("bytes"), o.optLong("fetched"))
    }.getOrNull()

    /**
     * Replace the package with the gzip NDJSON on [gz]. The new files are written beside the old and
     * renamed into place only once the whole stream has been read, so a download cut short leaves
     * the package that was there. Returns what was imported.
     */
    fun importFrom(gz: InputStream, nowMs: Long = System.currentTimeMillis()): Meta {
        dir.mkdirs()
        val newDat = File(dir, "pack.dat.new")
        val newIdx = File(dir, "pack.idx.new")
        var head: JSONObject? = null
        var offset = 0L
        var versions = 0
        try {
            newDat.outputStream().buffered().use { data ->
                newIdx.bufferedWriter(Charsets.UTF_8).use { names ->
                    GZIPInputStream(gz).bufferedReader(Charsets.UTF_8).useLines { lines ->
                        for (line in lines) {
                            if (line.isBlank()) continue
                            val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            if (head == null && o.has("v")) { head = o; continue }
                            val title = o.optString("t")
                            val artist = o.optString("a")
                            val lrc = o.optString("lrc")
                            if (title.isBlank() || artist.isBlank() || lrc.isBlank()) continue
                            val bytes = lrc.toByteArray(Charsets.UTF_8)
                            data.write(bytes)
                            names.write("${baseTitle(title).replace('\t', ' ')}\t${plain(artist)}\t${o.optLong("d")}\t$offset\t${bytes.size}\n")
                            offset += bytes.size
                            versions++
                        }
                    }
                }
            }
            val h = head ?: throw java.io.IOException("no header line")
            if (versions == 0) throw java.io.IOException("no songs")
            val m = Meta(h.optString("id"), h.optInt("songs", versions), h.optLong("built"), offset, nowMs)
            synchronized(lock) {
                index = null
                if (!newDat.renameTo(dat) || !newIdx.renameTo(idx)) throw java.io.IOException("could not put the package in place")
                metaFile.writeText(JSONObject().put("id", m.id).put("songs", m.songs).put("built", m.builtMs)
                    .put("bytes", m.bytes).put("fetched", m.fetchedMs).toString(), Charsets.UTF_8)
            }
            return m
        } finally {
            newDat.delete(); newIdx.delete()
        }
    }

    private fun loadIndex(): Map<String, List<Entry>> {
        index?.let { return it }
        val map = HashMap<String, MutableList<Entry>>()
        runCatching {
            idx.forEachLine(Charsets.UTF_8) { line ->
                val p = line.split('\t')
                if (p.size != 5) return@forEachLine
                val d = p[2].toLongOrNull() ?: return@forEachLine
                val off = p[3].toLongOrNull() ?: return@forEachLine
                val len = p[4].toIntOrNull() ?: return@forEachLine
                map.getOrPut(p[0]) { ArrayList(1) } += Entry(p[1], d, off, len)
            }
        }
        return map.also { index = it }
    }

    /** The LRC text for [key], or null when the package has no version of it near its length. */
    fun find(key: LyricsKey): String? = synchronized(lock) {
        val versions = loadIndex()[baseTitle(key.title)] ?: return null
        val e = choose(versions.map { it.artist to it.durationSec }, key.artist, key.durationSec)?.let { versions[it] } ?: return null
        runCatching {
            RandomAccessFile(dat, "r").use { f ->
                if (e.offset + e.length > f.length()) return null
                val b = ByteArray(e.length)
                f.seek(e.offset)
                f.readFully(b)
                String(b, Charsets.UTF_8)
            }
        }.getOrNull()
    }

    fun clear() = synchronized(lock) {
        index = null
        dat.delete(); idx.delete(); metaFile.delete()
    }

    companion object {
        private val log by lazy { ProbeLog.sub("lyrics") }
        const val MATCH_SECONDS = 3L
        private const val PREFS = "lyrics_pack"
        private const val KEY_BASE = "base"
        private const val KEY_CAR = "car"
        private const val KEY_CHECKED = "checked_at"
        /** The receiver is asked whether it has a newer package at most this often. */
        const val CHECK_EVERY_MS = 24 * 3_600_000L
        private val busy = AtomicBoolean(false)
        @Volatile private var store: LyricsPack? = null

        fun get(ctx: Context): LyricsPack = store ?: synchronized(this) {
            store ?: LyricsPack(File(ctx.applicationContext.noBackupFilesDir, "lyrics_pack")).also { store = it }
        }

        /** Pure: lower case, accents off, everything but letters and digits a space. */
        internal fun plain(s: String): String {
            val n = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFKD)
            val out = StringBuilder(n.length)
            var i = 0
            while (i < n.length) {
                val cp = n.codePointAt(i)
                i += Character.charCount(cp)
                when (Character.getType(cp).toByte()) {
                    Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> {}
                    else -> if (Character.isLetterOrDigit(cp)) out.appendCodePoint(cp) else out.append(' ')
                }
            }
            return out.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** Pure: the title without what a release adds after it: "Song - Remastered 2012", "Song (feat. X)". */
        fun baseTitle(title: String): String {
            val t = title.trim()
            val cut = listOf(t.indexOf(" - "), t.indexOf('('), t.indexOf('[')).filter { it > 0 }.minOrNull()
            val head = if (cut != null) t.substring(0, cut) else t
            return plain(head).ifEmpty { plain(t) }.ifEmpty { t.lowercase(Locale.ROOT) }
        }

        private val STOP = setOf("the", "and", "feat", "featuring", "with", "vs", "ft")

        /** Pure: the words of an artist line that can tell one act from another. */
        internal fun artistTokens(artist: String): Set<String> =
            plain(artist).split(' ').filter { it.length >= 3 && it !in STOP }.toSet()

        /** Pure: two artist lines name the same act when they share a telling word. */
        fun sameArtist(a: String, b: String): Boolean {
            val ta = artistTokens(a)
            val tb = artistTokens(b)
            return if (ta.isEmpty() || tb.isEmpty()) plain(a) == plain(b) else ta.any { it in tb }
        }

        /**
         * Pure: which of the versions' lengths ([durations], seconds, 0 unknown) to take for a song of
         * [durationSec]: the closest within [MATCH_SECONDS]; with the song's length unknown, the first.
         */
        fun pick(durations: List<Long>, durationSec: Long): Int? {
            if (durations.isEmpty()) return null
            if (durationSec <= 0) return 0
            return durations.withIndex().filter { it.value > 0 && abs(it.value - durationSec) <= MATCH_SECONDS }
                .minByOrNull { abs(it.value - durationSec) }?.index
        }

        /**
         * Pure: which of a title's [versions] (artist, length in seconds) is the song. With [artist]
         * named, the closest length of that act's ([sameArtist], [pick]). With none, which the phone
         * does on some song changes ([LyricsKey.of]), the length must be known and every version within
         * [MATCH_SECONDS] of it must be one act's: a title alone can name several songs.
         */
        fun choose(versions: List<Pair<String, Long>>, artist: String, durationSec: Long): Int? {
            if (artist.isNotBlank()) {
                val mine = versions.indices.filter { sameArtist(versions[it].first, artist) }
                return pick(mine.map { versions[it].second }, durationSec)?.let { mine[it] }
            }
            if (durationSec <= 0) return null
            val near = versions.indices.filter { versions[it].second > 0 && abs(versions[it].second - durationSec) <= MATCH_SECONDS }
            if (near.isEmpty() || near.map { plain(versions[it].first) }.distinct().size != 1) return null
            return near.minByOrNull { abs(versions[it].second - durationSec) }
        }

        fun endpointFor(baseUrl: String, carId: String, have: String?): String =
            baseUrl.trim().trimEnd('/') + "/lyrics?car=" + carId + (have?.takeIf { it.isNotEmpty() }?.let { "&have=$it" } ?: "")

        /** Whether Settings can ask for the package: the receiver is known once an upload has reached it. */
        fun known(ctx: Context): Boolean =
            ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CAR, null) != null

        /**
         * An upload has reached the receiver at [baseUrl], which knows this car as [carId]: remember
         * both for Settings, and on Wi-Fi, once a day, ask for a newer package and look up the songs
         * that had to go without ([LyricsWanted]).
         */
        fun onReceiverReached(ctx: Context, baseUrl: String, carId: String) {
            val app = ctx.applicationContext
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            remember(app, baseUrl, carId)
            if (!BarLyrics.lookup || !DataMeter.onWifi(app)) return
            if (System.currentTimeMillis() - p.getLong(KEY_CHECKED, 0L) < CHECK_EVERY_MS) return
            refresh(app) { }
        }

        /** Keep the receiver's address and this car's id there, for [refresh]. Also the bench's way in. */
        fun remember(ctx: Context, baseUrl: String, carId: String) {
            val p = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (p.getString(KEY_BASE, null) != baseUrl || p.getString(KEY_CAR, null) != carId)
                p.edit().putString(KEY_BASE, baseUrl).putString(KEY_CAR, carId).apply()
        }

        /**
         * Fetch the package if the receiver has a newer one, then catch up on missed songs. Wi-Fi or
         * a hotspot only. [done] gets one line for Settings, on a background thread.
         */
        fun refresh(ctx: Context, done: (String) -> Unit) {
            val app = ctx.applicationContext
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val base = p.getString(KEY_BASE, null)
            val car = p.getString(KEY_CAR, null)
            when {
                base == null || car == null -> { done("Not yet: the app has to reach the upload server once first."); return }
                !base.startsWith("https://", ignoreCase = true) || !car.matches(Regex("[0-9a-f]{16}")) -> { done("The upload server's address is not one this can use."); return }
                !DataMeter.onWifi(app) -> { done("Waiting for Wi-Fi or a hotspot: the package is not downloaded over the car's data."); return }
                !busy.compareAndSet(false, true) -> { done("Already downloading."); return }
            }
            Thread({
                try {
                    val pack = get(app)
                    val have = pack.meta()?.id
                    val c = wasidremin.gmccpa.upload.Net.open(endpointFor(base!!, car!!, have)).apply {
                        connectTimeout = 10_000
                        readTimeout = 60_000
                        setRequestProperty("X-Device", "gmccpa")
                    }
                    try {
                        when (val code = c.responseCode) {
                            HttpURLConnection.HTTP_NOT_MODIFIED -> {
                                p.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                                log.i("lyrics package: up to date (${pack.meta()?.songs} songs)")
                                done("Up to date.")
                            }
                            HttpURLConnection.HTTP_OK -> {
                                val m = c.inputStream.use { pack.importFrom(it) }
                                DataMeter.record(DataMeter.Stream.OTHER, c.contentLengthLong.coerceAtLeast(0L))
                                p.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
                                LrcLib.forget()
                                log.i("lyrics package: ${m.songs} songs, ${m.bytes / 1024} KB of lyrics, downloaded ${c.contentLengthLong / 1024} KB over Wi-Fi")
                                done("Downloaded: ${m.songs} songs.")
                            }
                            else -> {
                                log.i("lyrics package: the receiver has none to give (HTTP $code)")
                                done("The server has no package yet (HTTP $code).")
                            }
                        }
                    } finally {
                        c.disconnect()
                    }
                    LyricsWanted.get(app).catchUp(app)
                } catch (e: Exception) {
                    log.i("lyrics package: not fetched (${e.javaClass.simpleName})")
                    done("Could not download (${e.javaClass.simpleName}). The package that was here is kept.")
                } finally {
                    busy.set(false)
                }
            }, "lyrics-pack").apply { isDaemon = true }.start()
        }

        /** What Settings shows under the row. */
        fun statusText(ctx: Context): String {
            val m = get(ctx).meta()
            val have = if (m == null) "No package yet." else {
                val day = java.text.SimpleDateFormat("d MMM", Locale.getDefault()).format(java.util.Date(m.builtMs))
                "%d songs on the head unit (%.1f MB), built %s.".format(Locale.US, m.songs, m.bytes / 1_000_000.0, day)
            }
            return "Timed lyrics for your Spotify library and each year's top 100 since 1980, built on your server, so " +
                "lyrics show with no connection. Downloaded only on Wi-Fi or a hotspot, and refreshed there by itself. $have"
        }
    }
}

/** When lrclib.net may be asked about a song the head unit has no lyrics for (Settings ▸ Look up online). */
enum class LyricsOnline(val key: String, val short: String) {
    ALWAYS("always", "Always"), WIFI("wifi", "Wi-Fi only"), NEVER("never", "Never");

    /** Pure: whether lrclib.net may be asked now. */
    fun mayAsk(onWifi: Boolean): Boolean = when (this) {
        ALWAYS -> true
        WIFI -> onWifi
        NEVER -> false
    }
}

/**
 * Songs that played with no lyrics because the car was not to ask, or could not: looked up the next
 * time it is on Wi-Fi ([catchUp]), so a song heard once offline has its lyrics the time after. A
 * list of names on the head unit, never in the log, which is uploaded.
 */
class LyricsWanted(private val file: File) {
    private val lock = Any()

    fun all(): List<LyricsKey> = synchronized(lock) {
        runCatching {
            file.readLines(Charsets.UTF_8).mapNotNull { line ->
                runCatching { JSONObject(line) }.getOrNull()?.let {
                    LyricsKey(it.optString("t"), it.optString("a"), it.optString("al"), it.optLong("d"))
                }?.takeIf { it.title.isNotEmpty() && it.artist.isNotEmpty() }
            }
        }.getOrDefault(emptyList())
    }

    private fun write(keys: List<LyricsKey>) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(keys.joinToString("") {
                JSONObject().put("t", it.title).put("a", it.artist).put("al", it.album).put("d", it.durationSec).toString() + "\n"
            }, Charsets.UTF_8)
        }
    }

    /** Remember [key]; the oldest go once there are more than [MAX]. */
    fun add(key: LyricsKey) = synchronized(lock) {
        val keys = all()
        if (key in keys) return@synchronized
        write((keys + key).takeLast(MAX))
    }

    fun remove(done: Collection<LyricsKey>) = synchronized(lock) {
        if (done.isEmpty()) return@synchronized
        write(all().filter { it !in done })
    }

    /**
     * Blocking, on Wi-Fi only: ask lrclib about what is listed, slowly, at most [PER_RUN] a run.
     * Found lyrics go to the head unit's own store; a song lrclib lacks leaves the list too.
     */
    fun catchUp(ctx: Context) {
        val app = ctx.applicationContext
        val keys = all().take(PER_RUN)
        if (keys.isEmpty() || !BarLyrics.lookup) return
        val disk = LyricsDiskCache.get(app)
        val answered = ArrayList<LyricsKey>()
        var found = 0
        for (k in keys) {
            if (!DataMeter.onWifi(app)) break
            val a = LrcLib.find(k, disk) ?: break   // unreachable: leave the rest for next time
            answered += k
            if (a.lyrics != null) found++
            Thread.sleep(PAUSE_MS)
        }
        remove(answered)
        ProbeLog.sub("lyrics").i("lyrics: caught up on ${answered.size} songs that went without, $found now have lyrics, ${all().size} still listed")
    }

    companion object {
        const val MAX = 300
        const val PER_RUN = 80
        private const val PAUSE_MS = 700L
        @Volatile private var store: LyricsWanted? = null

        fun get(ctx: Context): LyricsWanted = store ?: synchronized(this) {
            store ?: LyricsWanted(File(ctx.applicationContext.noBackupFilesDir, "lyrics_wanted.ndjson")).also { store = it }
        }
    }
}
