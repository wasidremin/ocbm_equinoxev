package wasidremin.gmccpa.lyrics

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import wasidremin.gmccpa.ProbeLog
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/** What LRCLIB is asked by: the song as the iPhone names it, its length in whole seconds (0 = unknown). */
data class LyricsKey(
    val title: String,
    val artist: String,
    val album: String,
    val durationSec: Long,
) {
    companion object {
        /** The key for a song, or null when the iPhone has not named it and its artist. */
        fun of(title: String?, artist: String?, album: String?, durationMs: Long): LyricsKey? {
            val t = title?.trim().orEmpty()
            val a = artist?.trim().orEmpty()
            if (t.isEmpty() || a.isEmpty()) return null
            return LyricsKey(t, a, album?.trim().orEmpty(), (durationMs + 500L) / 1_000L)
        }
    }
}

/**
 * Timed lyrics from LRCLIB (lrclib.net): free, no key, crowd-sourced, looked up by the song's title,
 * artist, album and length, which is all the iPhone sends that names it (iAP2 now-playing carries no
 * lyrics). Only timed lyrics are kept: untimed ones cannot follow the song on one line.
 *
 * Ported from enigy/carlink `media/LrcLib.kt` (Unlicense), with their lookup order: LRCLIB's own records
 * first (`/api/get-cached`, matching the length within a couple of seconds), then a search by title
 * and first artist taking the timed hit whose length is closest within [MATCH_SECONDS] (Spotify names
 * live cuts and remasters by album), and only then `/api/get`, which also asks LRCLIB's outside
 * sources and answered HTTP 503 for most songs on one of their drives. Not ported: their registered
 * LRCLIB application ID, which is theirs; this client names itself in the User-Agent, as LRCLIB asks.
 *
 * Answers, found or not, are kept for the process ([CACHE_SIZE] songs), and found lyrics on the head
 * unit too ([LyricsDiskCache], read before LRCLIB is asked); a failed lookup is not an answer ([find]
 * returns null) and the caller asks again. Blocking: call off the main thread.
 */
object LrcLib {
    private val log = ProbeLog.sub("lyrics")

    /** A finished lookup: [lyrics] null when LRCLIB has none for the song. */
    class Answer(val lyrics: SyncedLyrics?)

    /** LRCLIB reached, answering an HTTP error rather than a body or a 404. */
    internal class HttpError(val code: Int, path: String) : IOException("HTTP $code from /api/$path")

    /** No step found lyrics and one could not be reached. */
    internal class Unreachable(message: String) : IOException(message)

    /** How LRCLIB is reached: the body of `GET /api/[path]?[query]`, null for a 404; [IOException] for anything else. */
    fun interface Api {
        fun get(path: String, query: List<Pair<String, String>>): String?
    }

    private val cache = object : LinkedHashMap<LyricsKey, Answer>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LyricsKey, Answer>?): Boolean = size > CACHE_SIZE
    }

    /** The answer already in hand for [key], or null when it has not been looked up. */
    fun cached(key: LyricsKey): Answer? = synchronized(cache) { cache[key] }

    /** Forget every answer this process holds (Settings' Clear, with [LyricsDiskCache.clear]). */
    fun forget() {
        synchronized(cache) { cache.clear() }
    }

    /**
     * LRCLIB's answer for the song, or null when LRCLIB could not be reached or answered unreadably,
     * which is not an answer: the caller asks again. Lyrics kept on the head unit ([disk]) are taken
     * first, and found ones are kept there.
     */
    fun find(key: LyricsKey, disk: LyricsDiskCache? = null, api: Api = Net): Answer? {
        cached(key)?.let { return it }
        disk?.read(key)?.let { lrc ->
            SyncedLyrics.parse(lrc)?.let { lyrics ->
                log.i("lyrics: ${lyrics.lines.size} timed lines, kept on the head unit")
                return Answer(lyrics).also { synchronized(cache) { cache[key] = it } }
            }
        }
        val lrc = try {
            lookUp(key, api)
        } catch (e: IOException) {
            log.w("lyrics: LRCLIB unreachable: ${e.message}")
            return null
        } catch (e: JSONException) {
            log.w("lyrics: LRCLIB answer unreadable: ${e.message}")
            return null
        }
        val answer = Answer(lrc?.let(SyncedLyrics::parse))
        // Counts only: song names stay out of the log, which is uploaded.
        log.i("lyrics: ${answer.lyrics?.let { "${it.lines.size} timed lines" } ?: "none"} for a ${key.durationSec}s song")
        synchronized(cache) { cache[key] = answer }
        if (lrc != null && answer.lyrics != null && disk?.write(key, lrc) == false) log.w("lyrics: could not keep them on the head unit")
        return answer
    }

    /**
     * The LRC text: from LRCLIB's own records (the exact match, else the closest search hit), else from
     * the lookup that also asks its outside sources. Null when none has timed lyrics; [Unreachable] when
     * none had them and a step could not be reached, since that one might have.
     */
    internal fun lookUp(key: LyricsKey, api: Api): String? {
        val exact = listOf("track_name" to key.title, "artist_name" to key.artist, "album_name" to key.album, "duration" to key.durationSec.toString())
        val missed = mutableListOf<String>()
        val ask = { path: String, query: List<Pair<String, String>> ->
            try {
                api.get(path, query)
            } catch (e: IOException) {
                missed += e.message ?: "/api/$path failed"
                null
            }
        }
        ask("get-cached", exact)?.let { synced(JSONObject(it)) }?.let { return it }
        ask("search", listOf("track_name" to key.title, "artist_name" to firstArtist(key.artist)))?.let { pick(JSONArray(it), key.durationSec) }?.let { return it }
        ask("get", exact)?.let { synced(JSONObject(it)) }?.let { return it }
        if (missed.isNotEmpty()) throw Unreachable(missed.joinToString("; "))
        return null
    }

    /** Of search [hits], the timed lyrics whose length is closest to [durationSec], within [MATCH_SECONDS]; with the length unknown, the first timed hit. */
    internal fun pick(hits: JSONArray, durationSec: Long): String? {
        val timed = (0 until hits.length()).mapNotNull { i -> hits.optJSONObject(i)?.let { hit -> synced(hit)?.let { hit to it } } }
        if (durationSec <= 0) return timed.firstOrNull()?.second
        return timed
            .map { (hit, lrc) -> abs(hit.optDouble("duration", Double.NaN) - durationSec) to lrc }
            .filter { (off, _) -> off <= MATCH_SECONDS }
            .minByOrNull { (off, _) -> off }
            ?.second
    }

    /** A record's timed lyrics; null for an instrumental, or a JSON null or blank `syncedLyrics`. */
    internal fun synced(record: JSONObject): String? {
        if (record.optBoolean("instrumental", false) || record.isNull("syncedLyrics")) return null
        return record.optString("syncedLyrics").takeIf { it.isNotBlank() }
    }

    /** Spotify joins a song's artists with commas; LRCLIB files most under the first. */
    internal fun firstArtist(artist: String): String = artist.substringBefore(", ").trim()

    private object Net : Api {
        override fun get(path: String, query: List<Pair<String, String>>): String? {
            val q = query.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8.name())}" }
            val conn = URL("$BASE/$path?$q").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                // LRCLIB asks every client to name itself.
                conn.setRequestProperty("User-Agent", "GM-CCPA (https://github.com/wasidremin/ocbm_equinoxev)")
                return when (val code = conn.responseCode) {
                    HttpURLConnection.HTTP_OK -> conn.inputStream.bufferedReader().use { it.readText() }
                    HttpURLConnection.HTTP_NOT_FOUND -> null
                    else -> throw HttpError(code, path)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private const val BASE = "https://lrclib.net/api"
    private const val TIMEOUT_MS = 8_000
    private const val MATCH_SECONDS = 3.0
    private const val CACHE_SIZE = 64
}
