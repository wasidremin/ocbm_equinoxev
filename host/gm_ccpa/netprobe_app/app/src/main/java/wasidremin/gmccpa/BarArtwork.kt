package wasidremin.gmccpa

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors

/**
 * The album art the top bar's Now Playing tile shows (owner's request, 2026-11-02). Process-wide,
 * like `CarPlayActivity.nowPlaying`, because the bar lives in both activities.
 *
 * Fed from `onMetadataChanged` on the `cp-meta` thread, which must never block, so each JPEG is
 * decoded once on its own thread and handed to every attached bar on the main thread. A title
 * change reaches the seam 25–124 ms ahead of its picture (doc 08 T13), so when the art goes away the
 * old picture stays up for [HOLD_MS] before the tile falls back to its glyph; a new picture inside
 * that window replaces it directly, with no blink in between.
 */
internal object BarArtwork {
    /** The media card's own wait for a picture (`holdForArt`, `ART_WAIT_MS`). */
    const val HOLD_MS = 500L
    /** Decode bound: the tile is 107–157 px on the Equinox. */
    private const val MAX_PX = 256

    private val log = ProbeLog.sub("barart")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bar-art").apply { isDaemon = true } }

    /** The JPEG the bars should be showing; identity, as the merge hands the same array on. */
    @Volatile private var wanted: ByteArray? = null
    // Main thread only.
    private val bars = LinkedHashSet<GmTopBarView>()
    private var shown: Bitmap? = null
    private val dropArt = Runnable { publish(null) }

    /** Any thread. */
    fun onArtwork(jpeg: ByteArray?) {
        if (jpeg === wanted) return
        wanted = jpeg
        if (jpeg == null) {
            main.post { if (wanted == null) { main.removeCallbacks(dropArt); main.postDelayed(dropArt, HOLD_MS) } }
            return
        }
        worker.execute {
            if (wanted !== jpeg) return@execute   // superseded before it was decoded
            val bm = runCatching { decode(jpeg) }.getOrNull()
            if (bm == null) log.w("tile art (${jpeg.size} B) failed to decode — tile keeps its glyph")
            main.post {
                if (wanted !== jpeg) return@post
                main.removeCallbacks(dropArt)
                publish(bm)
            }
        }
    }

    /** Main thread. A bar shows the current art from the moment it is attached. */
    fun attach(bar: GmTopBarView) {
        bars.add(bar)
        bar.nowPlayingArt = shown
    }

    /** Main thread. */
    fun detach(bar: GmTopBarView) {
        bars.remove(bar)
    }

    private fun publish(bm: Bitmap?) {
        shown = bm
        bars.forEach { it.nowPlayingArt = bm }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (probe.outWidth / (sample * 2) >= MAX_PX && probe.outHeight / (sample * 2) >= MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/**
 * The centred square of a [w]x[h] picture, as left, top, right, bottom: the tile is square and a
 * podcast's art need not be.
 */
internal fun centreSquare(w: Int, h: Int): IntArray {
    val side = minOf(w, h)
    val left = (w - side) / 2
    val top = (h - side) / 2
    return intArrayOf(left, top, left + side, top + side)
}
