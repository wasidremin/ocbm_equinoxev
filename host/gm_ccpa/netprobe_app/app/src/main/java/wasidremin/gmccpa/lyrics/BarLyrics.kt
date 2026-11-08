package wasidremin.gmccpa.lyrics

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import wasidremin.gmccpa.GmTopBarView
import wasidremin.gmccpa.ProbeLog
import java.util.concurrent.Executors

/**
 * Timed lyrics for the song playing now, for the media pane ([wasidremin.gmccpa.pane.MediaPaneView])
 * and the custom top bar's free middle ([GmTopBarView.lyricLine]).
 *
 * Two switches (Settings ▸ Playback):
 *  - Look up lyrics, off by default: it sends each song's title, artist, album and length to
 *    lrclib.net ([LrcLib]). With it off nothing leaves the head unit and there are no lyrics anywhere.
 *  - Lyrics in the top bar: the line being sung, only while the car is in Park (the top bar is in the
 *    driver's eyeline). The media pane is for the passenger and shows lyrics in any gear.
 *
 * Fed by [onSnapshot] from the now-playing merge on every metadata change and playback tick (about
 * 2 Hz). Between ticks the position is extrapolated while playing, so the line changes on time.
 */
object BarLyrics {
    private val log = ProbeLog.sub("lyrics")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bar-lyrics").apply { isDaemon = true } }

    private const val PREFS = "lyrics"
    private const val KEY_LOOKUP = "lookup"
    private const val KEY_TOP_BAR = "top_bar"
    private const val TICK_MS = 250L

    /** Look up lyrics on lrclib.net. */
    @Volatile var lookup = false
        private set
    /** Show the line being sung in the top bar (Park only). */
    @Volatile var topBar = false
        private set
    @Volatile private var loaded = false
    private var disk: LyricsDiskCache? = null

    /** The car is in Park; null until the gear is known ([wasidremin.gmccpa.av.VehicleStateWatcher]). */
    @Volatile var parked: Boolean? = null
        set(v) { field = v; main.post { push() } }

    /** Where a song's lyrics stand, for the pane's note. */
    enum class Status { OFF, NO_SONG, LOOKING, FOUND, NONE, UNREACHABLE }

    @Volatile private var key: LyricsKey? = null
    @Volatile private var lyrics: SyncedLyrics? = null
    @Volatile var status: Status = Status.OFF
        private set
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
            val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            lookup = p.getBoolean(KEY_LOOKUP, false)
            topBar = p.getBoolean(KEY_TOP_BAR, false)
            disk = LyricsDiskCache.get(app)
            status = if (lookup) Status.NO_SONG else Status.OFF
            loaded = true
        }
    }

    fun setLookup(ctx: Context, on: Boolean) {
        load(ctx)
        lookup = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_LOOKUP, on).apply()
        log.i("lyrics: look up ${if (on) "ON" else "off"} (Settings)")
        val k = key
        key = null
        lyrics = null
        status = if (!on) Status.OFF else if (k == null) Status.NO_SONG else Status.LOOKING
        if (on && k != null) startLookup(k)
        main.post { push() }
    }

    fun setTopBar(ctx: Context, on: Boolean) {
        load(ctx)
        topBar = on
        ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_TOP_BAR, on).apply()
        log.i("lyrics: top bar ${if (on) "ON" else "off"} (Settings)")
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
            status = when {
                !lookup -> Status.OFF
                k == null -> Status.NO_SONG
                else -> Status.LOOKING
            }
            if (k != null && lookup) startLookup(k)
            main.post { push() }
        }
    }

    private fun startLookup(k: LyricsKey) {
        key = k
        worker.execute {
            if (key != k || !lookup) return@execute
            val answer = LrcLib.find(k, disk)
            if (key != k) return@execute
            if (answer == null) {
                status = Status.UNREACHABLE
                return@execute
            }
            answer.lyrics?.let { disk?.noteUse(k) }
            lyrics = answer.lyrics
            status = if (answer.lyrics != null) Status.FOUND else Status.NONE
            main.post { push(); if (lyrics != null) { main.removeCallbacks(tick); main.post(tick) } }
        }
    }

    /** Where the song is now, extrapolated between the phone's ticks while it plays. */
    fun positionMs(): Long = anchorMs + if (playing) SystemClock.uptimeMillis() - anchorAt else 0L

    /** The song's timed lines, or null; and the index of the one being sung (-1 before the first). */
    fun current(): Pair<SyncedLyrics, Int>? {
        val l = lyrics ?: return null
        return l to l.indexAt(positionMs())
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

    /** Main thread: the line for now, onto every top bar. */
    private fun push() {
        val l = lyrics
        val line = if (!lookup || !topBar || l == null || parked != true) null else {
            val i = l.indexAt(positionMs())
            if (i == shownIndex && bars.all { it.lyricLine != null }) return
            shownIndex = i
            if (i < 0) null else l.lines[i].text.takeIf { it.isNotBlank() }
        }
        if (line == null) shownIndex = -2
        bars.forEach { it.lyricLine = line }
    }
}
