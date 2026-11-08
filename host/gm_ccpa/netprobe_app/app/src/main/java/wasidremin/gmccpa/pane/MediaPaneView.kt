package wasidremin.gmccpa.pane

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import wasidremin.gmccpa.EqTheme
import wasidremin.gmccpa.av.NowPlayingState
import wasidremin.gmccpa.lyrics.BarLyrics
import java.util.concurrent.Executors

/**
 * The media pane ([SplitPane]): what is playing, its controls, and its lyrics, for the passenger. Laid
 * over the right of the CarPlay picture, or over the strip iOS leaves black outside a split's view area.
 *
 * Feature set from enigy/carlink's pane (`ui/pane/MediaCard.kt`, `CardLyrics.kt`; Unlicense), drawn
 * with this app's views: cover art, title and artist, a progress line, previous / play-pause / next
 * (sent to CarPlay as media buttons), and the song's timed lyrics with the line being sung bright.
 * Lyrics show in any gear (the pane is for the passenger) whenever Settings ▸ Playback ▸ Look up
 * lyrics is on. The button at the top hides the pane (for a split, gives CarPlay the whole panel);
 * a tap on the strip right of CarPlay brings it back.
 *
 * Everything that sends goes through [actions], so this view never touches the OCBM link itself.
 */
class MediaPaneView(ctx: Context, private val actions: Actions) : LinearLayout(ctx) {

    interface Actions {
        fun mediaButton(index: Byte)
        fun hide()
    }

    /** A split's pane: its hide button reads "CarPlay full screen" rather than "close". */
    var split: Boolean = false
        set(v) {
            if (v == field) return
            field = v
            hideButton.kind = if (v) Glyph.Kind.EXPAND else Glyph.Kind.CLOSE
            hideButton.contentDescription = if (v) "CarPlay full screen" else "Hide the pane"
        }

    private val main = Handler(Looper.getMainLooper())
    private val decoder = Executors.newSingleThreadExecutor { r -> Thread(r, "pane-art").apply { isDaemon = true } }

    private val appName = EqTheme.text(ctx, 17f, EqTheme.TEXT_DIM).apply { singleLine() }
    private val art = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = EqTheme.rounded(0x14FFFFFF, 14f)
    }
    private val title = EqTheme.text(ctx, 27f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply { singleLine() }
    private val artist = EqTheme.text(ctx, 20f, EqTheme.TEXT_DIM).apply { singleLine() }
    private val progress = ProgressLine(ctx)
    private val elapsed = EqTheme.text(ctx, 15f, EqTheme.TEXT_FAINT)
    private val remaining = EqTheme.text(ctx, 15f, EqTheme.TEXT_FAINT).apply { gravity = Gravity.END }
    private val playPause = Glyph(ctx, Glyph.Kind.PLAY)
    private val hideButton = Glyph(ctx, Glyph.Kind.CLOSE).apply {
        contentDescription = "Hide the pane"
        setOnClickListener { actions.hide() }
    }
    private val lyricViews = List(LYRIC_ROWS) { i ->
        EqTheme.text(ctx, if (i == CURRENT_ROW) 25f else 20f, EqTheme.TEXT_FAINT,
            if (i == CURRENT_ROW) EqTheme.Weight.MEDIUM else EqTheme.Weight.REGULAR).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            // The line being sung may wrap; the others keep to one so all five fit a short panel.
            maxLines = if (i == CURRENT_ROW) 2 else 1
            ellipsize = TextUtils.TruncateAt.END
        }
    }
    private val lyricNote = EqTheme.text(ctx, 17f, EqTheme.TEXT_FAINT).apply { gravity = Gravity.CENTER_HORIZONTAL }

    private var shown: NowPlayingState.Snapshot = NowPlayingState.Snapshot()
    private var artBytes: ByteArray? = null
    private var shownLyricIndex = Int.MIN_VALUE
    private var shownLyricStatus: BarLyrics.Status? = null

    private val tick = object : Runnable {
        override fun run() {
            refreshPosition()
            refreshLyrics()
            main.postDelayed(this, TICK_MS)
        }
    }

    init {
        orientation = VERTICAL
        setBackgroundColor(BG)
        isClickable = true   // touches on the pane never fall through to the CarPlay picture
        val pad = EqTheme.px(22f)
        setPadding(pad, EqTheme.px(16f), pad, EqTheme.px(16f))

        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(appName, LayoutParams(0, -2, 1f))
            addView(hideButton, LayoutParams(EqTheme.px(56f), EqTheme.px(56f)))
        }, LayoutParams(-1, -2))
        addView(art, LayoutParams(0, 0).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = EqTheme.px(8f) })
        addView(title, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(14f) })
        addView(artist, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(4f) })
        addView(progress, LayoutParams(-1, EqTheme.px(14f)).apply { topMargin = EqTheme.px(14f) })
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            addView(elapsed, LayoutParams(0, -2, 1f))
            addView(remaining, LayoutParams(0, -2, 1f))
        }, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(4f) })
        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val size = EqTheme.px(64f)
            addView(Glyph(ctx, Glyph.Kind.PREVIOUS).apply {
                contentDescription = "Previous"
                setOnClickListener { actions.mediaButton(wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PREV) }
            }, LayoutParams(size, size))
            addView(playPause.apply {
                contentDescription = "Play or pause"
                setOnClickListener { actions.mediaButton(wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_PLAY_PAUSE) }
            }, LayoutParams(size, size).apply { marginStart = EqTheme.px(36f); marginEnd = EqTheme.px(36f) })
            addView(Glyph(ctx, Glyph.Kind.NEXT).apply {
                contentDescription = "Next"
                setOnClickListener { actions.mediaButton(wasidremin.gmccpa.ocbm.Ocbm.MEDIA_BTN_NEXT) }
            }, LayoutParams(size, size))
        }, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(8f) })
        addView(LinearLayout(ctx).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER
            lyricViews.forEach { addView(it, LayoutParams(-1, -2).apply { topMargin = EqTheme.px(8f) }) }
            addView(lyricNote, LayoutParams(-1, -2))
        }, LayoutParams(-1, 0, 1f).apply { topMargin = EqTheme.px(10f) })
        bind(shown)
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        // The art is square: the pane's width, but never more than a third of its height, so the
        // lyrics keep room on a short panel.
        val side = minOf(w - paddingLeft - paddingRight, (h * 0.34f).toInt()).coerceAtLeast(0)
        val lp = art.layoutParams as LayoutParams
        if (lp.width != side) {
            lp.width = side
            lp.height = side
            post { art.layoutParams = lp }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean = true

    /** Main thread. A new now-playing picture: text, art and the play/pause glyph. */
    fun bind(s: NowPlayingState.Snapshot) {
        shown = s
        appName.text = s.appName ?: ""
        title.text = s.title ?: "Nothing playing"
        artist.text = listOfNotNull(s.artist, s.album).joinToString(" · ")
        playPause.kind = if (s.playing) Glyph.Kind.PAUSE else Glyph.Kind.PLAY
        val jpeg = s.artwork
        if (jpeg !== artBytes) {
            artBytes = jpeg
            if (jpeg == null) art.setImageBitmap(null) else decoder.execute {
                val bm = runCatching { decode(jpeg) }.getOrNull()
                main.post { if (artBytes === jpeg) art.setImageBitmap(bm) }
            }
        }
        refreshPosition()
        shownLyricIndex = Int.MIN_VALUE
        refreshLyrics()
    }

    private fun refreshPosition() {
        val s = shown
        val dur = s.durationMs
        val pos = if (s.elapsedKnown) BarLyrics.positionMs().coerceIn(0, if (dur > 0) dur else Long.MAX_VALUE) else 0L
        progress.fraction = if (dur > 0) pos.toFloat() / dur else 0f
        elapsed.text = if (dur > 0) clock(pos) else ""
        remaining.text = if (dur > 0) "-" + clock(dur - pos) else ""
    }

    private fun refreshLyrics() {
        val cur = BarLyrics.current()
        val status = BarLyrics.status
        val i = cur?.second ?: Int.MIN_VALUE + 1
        if (i == shownLyricIndex && status == shownLyricStatus) return
        shownLyricIndex = i
        shownLyricStatus = status
        if (cur == null) {
            lyricViews.forEach { it.text = "" }
            lyricNote.text = when (status) {
                BarLyrics.Status.OFF -> "Lyrics are off. Settings ▸ Playback ▸ Look up lyrics."
                BarLyrics.Status.LOOKING -> "Looking up lyrics…"
                BarLyrics.Status.NONE -> "No timed lyrics for this song."
                BarLyrics.Status.UNREACHABLE -> "Lyrics unavailable: lrclib.net could not be reached."
                BarLyrics.Status.NO_SONG, BarLyrics.Status.FOUND -> ""
            }
            return
        }
        lyricNote.text = ""
        val (lyrics, at) = cur
        for ((row, v) in lyricViews.withIndex()) {
            val line = lyrics.lines.getOrNull(at + row - CURRENT_ROW)
            v.text = line?.text?.ifBlank { "♪" } ?: ""
            v.setTextColor(if (row == CURRENT_ROW && at >= 0) EqTheme.TEXT else EqTheme.TEXT_FAINT)
        }
    }

    private fun decode(jpeg: ByteArray): Bitmap? {
        val probe = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, probe)
        if (probe.outWidth <= 0 || probe.outHeight <= 0) return null
        var sample = 1
        while (probe.outWidth / (sample * 2) >= ART_MAX_PX && probe.outHeight / (sample * 2) >= ART_MAX_PX) sample *= 2
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    private fun TextView.singleLine() {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    /** The thin progress line under the title. */
    private class ProgressLine(ctx: Context) : View(ctx) {
        var fraction = 0f
            set(v) { val c = v.coerceIn(0f, 1f); if (c != field) { field = c; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val h = height * 0.4f
            val y = (height - h) / 2f
            paint.color = 0x33FFFFFF
            r.set(0f, y, width.toFloat(), y + h)
            c.drawRoundRect(r, h / 2f, h / 2f, paint)
            paint.color = EqTheme.TEXT
            r.set(0f, y, width * fraction, y + h)
            c.drawRoundRect(r, h / 2f, h / 2f, paint)
        }
    }

    /** A drawn control glyph (no font or image dependency). */
    private class Glyph(ctx: Context, kind: Kind) : View(ctx) {
        enum class Kind { PREVIOUS, PLAY, PAUSE, NEXT, EXPAND, CLOSE }
        var kind = kind
            set(v) { if (v != field) { field = v; invalidate() } }
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = EqTheme.TEXT }
        private val path = Path()

        init {
            isClickable = true
            background = EqTheme.ripple(null, 32f)
        }

        override fun onDraw(c: Canvas) {
            val s = minOf(width, height).toFloat()
            val cx = width / 2f
            val cy = height / 2f
            val u = s * 0.18f
            path.reset()
            paint.style = Paint.Style.FILL
            when (kind) {
                Kind.PLAY -> triangle(cx - u * 0.8f, cy, u * 1.9f, right = true)
                Kind.PAUSE -> {
                    c.drawRect(cx - u * 1.1f, cy - u * 1.3f, cx - u * 0.3f, cy + u * 1.3f, paint)
                    c.drawRect(cx + u * 0.3f, cy - u * 1.3f, cx + u * 1.1f, cy + u * 1.3f, paint)
                }
                Kind.NEXT -> {
                    triangle(cx - u * 1.6f, cy, u * 1.3f, right = true)
                    triangle(cx - u * 0.3f, cy, u * 1.3f, right = true)
                    c.drawRect(cx + u * 1.0f, cy - u * 1.1f, cx + u * 1.3f, cy + u * 1.1f, paint)
                }
                Kind.PREVIOUS -> {
                    triangle(cx + u * 1.6f, cy, u * 1.3f, right = false)
                    triangle(cx + u * 0.3f, cy, u * 1.3f, right = false)
                    c.drawRect(cx - u * 1.3f, cy - u * 1.1f, cx - u * 1.0f, cy + u * 1.1f, paint)
                }
                Kind.EXPAND -> {
                    // Two corner arrows pointing out: "give CarPlay the whole screen".
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = s * 0.06f
                    paint.strokeCap = Paint.Cap.ROUND
                    val a = u * 1.4f
                    val b = u * 0.6f
                    path.moveTo(cx - a + b, cy - a); path.lineTo(cx - a, cy - a); path.lineTo(cx - a, cy - a + b)
                    path.moveTo(cx - a, cy - a); path.lineTo(cx - u * 0.3f, cy - u * 0.3f)
                    path.moveTo(cx + a - b, cy + a); path.lineTo(cx + a, cy + a); path.lineTo(cx + a, cy + a - b)
                    path.moveTo(cx + a, cy + a); path.lineTo(cx + u * 0.3f, cy + u * 0.3f)
                }
                Kind.CLOSE -> {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = s * 0.06f
                    paint.strokeCap = Paint.Cap.ROUND
                    val a = u * 1.1f
                    path.moveTo(cx - a, cy - a); path.lineTo(cx + a, cy + a)
                    path.moveTo(cx + a, cy - a); path.lineTo(cx - a, cy + a)
                }
            }
            c.drawPath(path, paint)
        }

        /** A triangle with its flat side at [x], pointing right or left, [w] wide. */
        private fun triangle(x: Float, cy: Float, w: Float, right: Boolean) {
            val tip = if (right) x + w else x - w
            path.moveTo(x, cy - w * 0.62f)
            path.lineTo(tip, cy)
            path.lineTo(x, cy + w * 0.62f)
            path.close()
        }
    }

    companion object {
        const val BG = 0xFF0B1220.toInt()
        private const val TICK_MS = 250L
        private const val ART_MAX_PX = 512
        /** One line before the one being sung, then three after it. */
        private const val LYRIC_ROWS = 5
        private const val CURRENT_ROW = 1

        fun clock(ms: Long): String {
            val s = (ms.coerceAtLeast(0) + 500) / 1000
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
