package wasidremin.gmccpa.pane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import wasidremin.gmccpa.EqTheme
import wasidremin.gmccpa.av.NowPlayingState
import wasidremin.gmccpa.lyrics.BarLyrics

/**
 * The glass right of the CarPlay picture, under the top bar: a tap shows or hides the media pane
 * ([SplitPane]). On the Equinox the picture ends where the narrowest row of glass does (x 2630 of
 * 2914), so this strip is the swooping right side, which reaches further on some rows than others.
 *
 * It draws one mark, at the row where the glass reaches furthest ([handleAt], in this view's
 * coordinates; mid-strip with no traced outline). While the pane is hidden the mark is the round
 * now-playing chip ([MediaChip]: the cover inside a ring that fills as the song plays), so the pane
 * is seen to be there; while the pane is up, or on glass too narrow for the chip, it is a faint handle.
 */
class PaneStrip(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val chip = MediaChip()
    private var hx = -1f
    private var hy = -1f
    private var glass = 0f
    private var shown = NowPlayingState.Snapshot()
    private var progress: Float? = null
    /** 0 the handle, 1 the chip; eased toward whichever the pane's state calls for. */
    private var chipT = -1f
    private var lastFrame = 0L

    /** Lit while the pane is up, faint while it is hidden. */
    var lit: Boolean = false
        set(v) { if (v != field) { field = v; invalidate() } }

    /** The decoded cover of the song playing, or null. */
    var cover: Bitmap? = null
        set(v) { if (v !== field) { field = v; invalidate() } }

    private val tick = object : Runnable {
        override fun run() {
            refreshProgress()
            postDelayed(this, TICK_MS)
        }
    }

    init {
        isClickable = true
        contentDescription = "Show or hide the media pane"
    }

    /** Main thread. What is playing: only its position is drawn here. */
    fun bind(s: NowPlayingState.Snapshot) {
        shown = s
        refreshProgress()
    }

    private fun refreshProgress() {
        val s = shown
        val p = if (s.durationMs > 0 && s.elapsedKnown) {
            // To the ring's nearest half degree, so a tick redraws only when the arc moves.
            (BarLyrics.positionMs().coerceIn(0, s.durationMs).toFloat() / s.durationMs * 720f).toInt() / 720f
        } else null
        if (p != progress) { progress = p; if (!lit) invalidate() }
    }

    /**
     * The mark's centre and the width of glass it sits on, or a negative x for none (no glass wide
     * enough to hold it).
     */
    fun handleAt(x: Float, y: Float, glassPx: Float = 0f) {
        if (x == hx && y == hy && glassPx == glass) return
        hx = x
        hy = y
        glass = glassPx
        invalidate()
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }
    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(c: Canvas) {
        if (hx < 0f) return
        val d = minOf(EqTheme.pxf(CHIP_DP), glass - EqTheme.pxf(16f))
        val target = if (!lit && d >= EqTheme.pxf(MIN_CHIP_DP)) 1f else 0f
        val now = SystemClock.uptimeMillis()
        if (chipT < 0f) chipT = target
        else if (chipT != target) {
            val step = (now - lastFrame).coerceIn(0, 50) / MORPH_MS
            chipT = if (chipT < target) minOf(target, chipT + step) else maxOf(target, chipT - step)
            postInvalidateOnAnimation()
        }
        lastFrame = now
        val t = chipT * chipT * (3 - 2 * chipT)

        if (t < 1f) {
            val w = EqTheme.pxf(6f)
            val h = EqTheme.pxf(72f) * (1f - 0.5f * t)
            paint.color = if (lit) 0x66FFFFFF else 0x33FFFFFF
            paint.alpha = (paint.alpha * (1f - t)).toInt()
            r.set(hx - w / 2f, hy - h / 2f, hx + w / 2f, hy + h / 2f)
            c.drawRoundRect(r, w / 2f, w / 2f, paint)
        }
        if (t > 0f && d > 0f) {
            // Grows out of the handle; a touch presses it in.
            val size = d * (0.6f + 0.4f * t) * (if (isPressed) 0.93f else 1f)
            // The widest row of glass is often its last: the chip stays whole, a little above it.
            val room = d / 2f + EqTheme.pxf(8f)
            val cy = if (height > room * 2) hy.coerceIn(room, height - room) else hy
            val layer = if (t < 1f) c.saveLayerAlpha(hx - d, cy - d, hx + d, cy + d, (255 * t).toInt()) else -1
            chip.draw(c, hx, cy, size, cover, progress)
            if (layer >= 0) c.restoreToCount(layer)
        }
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val MORPH_MS = 240f
        /** The chip's size, and the smallest it is drawn at before the handle stands in for it. */
        private const val CHIP_DP = 84f
        private const val MIN_CHIP_DP = 36f

        /** Narrower glass than this holds no mark, though the strip still takes a tap. */
        fun minGlassPx(): Int = EqTheme.px(16f)
    }
}
