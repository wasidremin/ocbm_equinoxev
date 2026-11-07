package wasidremin.gmccpa.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import wasidremin.gmccpa.GmTopBarView
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors

/**
 * The line being sung, in the custom top bar's free middle ([GmTopBarView.lyricLine]).
 *
 * Settings ▸ Playback ▸ Lyrics in the top bar, off by default: it sends each song's title, artist,
 * album and length to lrclib.net ([LrcLib]). Shown only while the car is in Park, as enigy/carlink
 * shows its lyrics card only while parked by default; the line clears as soon as the gear leaves Park.
 *
 * Fed by [onSnapshot] from the now-playing merge on every metadata change and playback tick (about
 * 2 Hz). Between ticks the position is extrapolated while playing, so the line changes on time.
 */
object BarLyrics {
    private val log = ProbeLog.sub("lyrics")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bar-lyrics").apply { isDaemon = true } }

    private const val PREFS = "lyrics"
    private const val KEY_ENABLED = "top_bar"
    private const val TICK_MS = 250L

    @Volatile var enabled = false
        private set
    @Volatile private var loaded = false
    private var disk: LyricsDiskCache? = null

    /** The car is in Park; null until the gear is known ([wasidremin.gmccpa.av.VehicleStateWatcher]). */
    @Volatile var parked: Boolean? = null
        set(v) { field = v; main.post { push() } }

    @Volatile private var key: LyricsKey? = null
    @Volatile private var lyrics: SyncedLyrics? = null
    @Volatile private var anchorMs = 0L
    @Volatile private var anchorAt = 0L
    @Volatile private var playing = false

    // Main thread only.
    private val bars = LinkedHashSet<GmTopBarView>()
    private var shownIndex = -2
    private val tick = object : Runnable {
        override fun run() {
            push()
            if (bars.isNotEmpty() && lyrics != null) main.postDelayed(this, TICK_MS)
        }
    }

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val app = ctx.applicationContext
            enabled = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)
            disk = LyricsDiskCache.get(app)
            loaded = true
        }
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        load(ctx)
        enabled = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        log.i("lyrics: top bar ${if (on) "ON" else "off"} (Settings)")
        val k = key
        key = null
        lyrics = null
        if (on && k != null) startLookup(k)
        main.post { push() }
    }

    /** What Settings shows under the switch. */
    fun usageText(ctx: Context): String {
        load(ctx)
        val u = disk?.usage() ?: return ""
        return if (u.songs == 0) "No songs kept yet." else "%d songs kept on the head unit (%.1f MB).".format(u.songs, u.bytes / 1_000_000.0)
    }

    /** Settings' Clear: every kept song and every answer this process holds. */
    fun clear(ctx: Context): Int {
        load(ctx)
        LrcLib.forget()
        return disk?.clear() ?: 0
    }

    /** Any thread. One now-playing snapshot: the song, where it is, and whether it plays. */
    fun onSnapshot(title: String?, artist: String?, album: String?, durationMs: Long, elapsedMs: Long, elapsedKnown: Boolean, isPlaying: Boolean) {
        if (elapsedKnown) {
            anchorMs = elapsedMs
            anchorAt = SystemClock.uptimeMillis()
        }
        playing = isPlaying
        val k = LyricsKey.of(title, artist, album, durationMs)
        if (k != key) {
            key = k
            lyrics = null
            if (k != null && enabled) startLookup(k)
            main.post { push() }
        }
    }

    private fun startLookup(k: LyricsKey) {
        key = k
        worker.execute {
            if (key != k || !enabled) return@execute
            val answer = LrcLib.find(k, disk) ?: return@execute
            if (key != k) return@execute
            answer.lyrics?.let { disk?.noteUse(k) }
            lyrics = answer.lyrics
            main.post { push(); if (lyrics != null) { main.removeCallbacks(tick); main.post(tick) } }
        }
    }

    /** Main thread. */
    fun attach(bar: GmTopBarView) {
        bars.add(bar)
        shownIndex = -2
        push()
        if (lyrics != null) { main.removeCallbacks(tick); main.post(tick) }
    }

    /** Main thread. */
    fun detach(bar: GmTopBarView) {
        bars.remove(bar)
        if (bars.isEmpty()) main.removeCallbacks(tick)
    }

    /** Main thread: the line for now, onto every bar. */
    private fun push() {
        val l = lyrics
        val line = if (!enabled || l == null || parked != true) null else {
            val pos = anchorMs + if (playing) SystemClock.uptimeMillis() - anchorAt else 0L
            val i = l.indexAt(pos)
            if (i == shownIndex && bars.all { it.lyricLine != null }) return
            shownIndex = i
            if (i < 0) null else l.lines[i].text.takeIf { it.isNotBlank() }
        }
        if (line == null) shownIndex = -2
        bars.forEach { it.lyricLine = line }
    }
}
