package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import wasidremin.gmccpa.av.CallPhase
import wasidremin.gmccpa.av.CarNight
import wasidremin.gmccpa.av.CarPlayActivity
import wasidremin.gmccpa.av.NowPlayingState

/**
 * Now playing on the driver display, and the call that takes its place ([ClusterLayout.Slot.MEDIA]).
 *
 * With no route the card stays open: cover, title, artist and a progress line. During a route it
 * opens for [OPEN_MS] when the song changes and then folds to a pill: the cover inside a progress
 * ring with the title small beside it, so the map keeps its room. A ringing or active call shows in the card's place, with who it
 * is and how long it has run. The driver display takes no touch, so there are no buttons: the
 * steering-wheel keys and the centre screen do that. Docked in the bottom-right panel
 * ([ClusterLayout.docked]) it stays open, in the gauges' look.
 *
 * Nothing here is logged: titles and callers are shown, never written down.
 */
object MediaCard {
    enum class Mode { HIDDEN, CHIP, OPEN }

    /** How long the card stays open after the song changes, during a route. */
    /** Bench only (`--ez folded true`): the Settings sample drawn folded, to judge the pill. */
    @Volatile var benchFolded = false

    const val OPEN_MS = 8_000L

    /** Pure: what the now-playing card is at this moment. */
    fun mode(hasContent: Boolean, routeActive: Boolean, sinceSongChangeMs: Long): Mode = when {
        !hasContent -> Mode.HIDDEN
        !routeActive || sinceSongChangeMs < OPEN_MS -> Mode.OPEN
        else -> Mode.CHIP
    }

    /** Pure: the share of the song played, 0..1; null when the phone gave no length. */
    fun progress(elapsedMs: Long, durationMs: Long): Float? =
        if (durationMs <= 0) null else (elapsedMs.toFloat() / durationMs).coerceIn(0f, 1f)

    /** Pure: a running call's length as m:ss, or h:mm:ss past the hour. */
    fun callTime(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val mmss = String.format(java.util.Locale.US, "%d:%02d", s % 3600 / 60, s % 60)
        return if (s >= 3600) "${s / 3600}:${mmss.padStart(5, '0')}" else mmss
    }

    class View(ctx: Context) : android.view.View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val art = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
        private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val clip = Path()

        private var song: String? = null
        private var songChangedAt = 0L
        private var title: String? = null
        private var artist: String? = null
        private var played: Float? = null
        private var mode = Mode.HIDDEN
        private var call: NowPlayingState.CallCard? = null
        private var callLine: String? = null
        private var preview = false
        private var night = false
        private var cover: Bitmap? = null
        private var coverOf: ByteArray? = null
        /** 0 the cover alone, 1 the open card; eased toward [mode]. */
        private var open = 1f
        private var lastFrame = 0L

        private val tick = object : Runnable {
            override fun run() {
                refresh()
                postDelayed(this, 500)
            }
        }

        private fun refresh() {
            val now = SystemClock.elapsedRealtime()
            val np = CarPlayActivity.nowPlaying
            val s = np.snapshot
            val showPreview = DriverDisplay.previewUntil > now
            val key = if (s.hasContent) "${s.title}\u0000${s.artist}" else null
            if (key != song) { song = key; songChangedAt = now }
            val nextMode = if (!ClusterLayout.media) Mode.HIDDEN
                else mode(s.hasContent, ClusterNav.route.snapshot.routeActive, now - songChangedAt)
            val c = np.callCard().takeIf { ClusterLayout.call && it.phase != CallPhase.NONE }
            val line = c?.let { if (it.phase == CallPhase.RINGING) "Incoming call" else callTime(now - it.activeSinceMs) }
            val p = progress(s.elapsedMs, s.durationMs)
            if (s.artwork !== coverOf) {
                coverOf = s.artwork
                cover = s.artwork?.let { bytes ->
                    runCatching {
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var sample = 1
                        while (maxOf(o.outWidth, o.outHeight) / sample > 512) sample *= 2
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                    }.getOrNull()
                }
                invalidate()
            }
            val wasHidden = mode == Mode.HIDDEN && call == null && !preview
            if (nextMode != mode || c != call || line != callLine || s.title != title || s.artist != artist ||
                p != played || showPreview != preview || night != CarNight.night) {
                if (wasHidden) open = if (nextMode == Mode.CHIP) 0f else 1f
                mode = nextMode; call = c; callLine = line; title = s.title; artist = s.artist
                played = p; preview = showPreview; night = CarNight.night
                invalidate()
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        private fun ellipsized(text: String, paint: TextPaint, width: Float): String =
            TextUtils.ellipsize(text, paint, maxOf(0f, width), TextUtils.TruncateAt.END).toString()

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            val b = ClusterLayout.box(context, width, height, ClusterLayout.Slot.MEDIA)
            val onPanel = ClusterLayout.docked == ClusterLayout.Slot.MEDIA
            val pal = if (onPanel) ClusterLayout.GAUGE else ClusterLayout.palette(night)
            // Parked in Settings with nothing playing: a sample, so the place can be judged.
            val sample = mode == Mode.HIDDEN && preview && ClusterLayout.media
            if (call == null && mode == Mode.HIDDEN && !sample) return
            if (onPanel) CardParts.dockPanel(canvas, b, width.toFloat(), height.toFloat())
            call?.let { drawCall(canvas, it, b, pal, onPanel); return }
            if (onPanel) { drawDocked(canvas, b, pal, sample); return }
            val h = b.height

            val now = SystemClock.uptimeMillis()
            val target = if (mode == Mode.CHIP || (sample && benchFolded)) 0f else 1f
            if (open != target) {
                val step = ((now - lastFrame).coerceIn(0, 50)) / 380f
                open = if (open < target) minOf(target, open + step) else maxOf(target, open - step)
                postInvalidateOnAnimation()
            }
            lastFrame = now
            val t = open * open * (3 - 2 * open)

            // The chip hugs whichever side of the display the card sits on.
            val chip = h * 0.74f
            val rightSide = b.left + b.width / 2f > width / 2f
            // Folded, the title stays beside the cover, small and on one line.
            val pad = h * 0.13f
            plain.textSize = h * 0.17f
            val folded = (if (sample) "Now playing" else title)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { ellipsized(it, plain, b.width - chip - pad * 1.4f) } ?: ""
            val chipW = if (folded.isEmpty()) chip else chip + pad * 0.2f + plain.measureText(folded) + pad * 1.2f
            val cw = chipW + (b.width - chipW) * t
            val ch = chip + (h - chip) * t
            val left = if (rightSide) b.left + b.width - cw else b.left
            val radius = chip / 2f + (h * 0.18f - chip / 2f) * t
            fill.color = pal.card
            r.set(left, b.top, left + cw, b.top + ch)
            canvas.drawRoundRect(r, radius, radius, fill)

            // The cover: a disc inside the ring when folded, a rounded square when open.
            val side = chip * 0.80f + (h - pad * 2 - chip * 0.80f) * t
            val inset = (chip - chip * 0.80f) / 2f + (pad - (chip - chip * 0.80f) / 2f) * t
            val ax = left + inset
            val ay = b.top + inset
            val ar = side / 2f + (side * 0.14f - side / 2f) * t
            r.set(ax, ay, ax + side, ay + side)
            clip.rewind(); clip.addRoundRect(r, ar, ar, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val bmp = cover
            if (bmp != null && !sample) canvas.drawBitmap(bmp, null, r, art)
            else {
                fill.color = pal.track
                canvas.drawRect(r, fill)
                bold.color = pal.dim; bold.textSize = side * 0.5f
                canvas.drawText("♪", r.centerX() - bold.measureText("♪") / 2f, r.centerY() + bold.textSize * 0.35f, bold)
            }
            canvas.restore()

            val p = if (sample) 0.4f else played
            if (t < 0.5f) {
                // Folded: the ring around the cover is the progress.
                if (p != null) {
                    ring.strokeWidth = chip * 0.055f
                    val o = ring.strokeWidth
                    r.set(left + o, b.top + o, left + chip - o, b.top + chip - o)
                    ring.color = pal.track
                    canvas.drawOval(r, ring)
                    ring.color = pal.accent
                    canvas.drawArc(r, -90f, 360f * p, false, ring)
                }
                if (folded.isNotEmpty()) {
                    plain.color = pal.text; plain.alpha = (255 * (1f - 2f * t)).toInt().coerceIn(0, 255)
                    canvas.drawText(folded, left + chip + pad * 0.2f, b.top + chip / 2f + plain.textSize * 0.35f, plain)
                }
                return
            }
            val alpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
            val textLeft = ax + side + pad
            val textWidth = left + cw - pad - textLeft
            bold.color = pal.text; bold.alpha = (255 * alpha).toInt(); bold.textSize = h * 0.21f
            plain.color = pal.dim; plain.alpha = (255 * alpha).toInt(); plain.textSize = h * 0.17f
            val top = b.top + pad
            canvas.drawText(ellipsized(if (sample) "Now playing" else title ?: "", bold, textWidth), textLeft, top + h * 0.24f, bold)
            canvas.drawText(ellipsized(if (sample) "Artist" else artist ?: "", plain, textWidth), textLeft, top + h * 0.47f, plain)
            if (p != null) {
                val y = b.top + ch - pad - h * 0.04f
                fill.color = pal.track; fill.alpha = (Color.alpha(pal.track) * alpha).toInt()
                r.set(textLeft, y, textLeft + textWidth, y + h * 0.04f)
                canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, fill)
                fill.color = pal.accent; fill.alpha = (255 * alpha).toInt()
                r.right = textLeft + textWidth * p
                canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, fill)
            }
        }

        /** On the bottom-right panel: the cover, title and artist, and the progress as the gauges draw a bar. */
        private fun drawDocked(canvas: Canvas, b: ClusterLayout.Box, pal: ClusterLayout.Palette, sample: Boolean) {
            val h = b.height
            val pad = h * 0.14f
            val side = h * 0.72f
            val ax = b.left + pad
            val ay = b.top + h * 0.12f
            r.set(ax, ay, ax + side, ay + side)
            clip.rewind(); clip.addRoundRect(r, side * 0.14f, side * 0.14f, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(clip)
            val bmp = cover
            if (bmp != null && !sample) canvas.drawBitmap(bmp, null, r, art)
            else {
                fill.color = pal.track
                canvas.drawRect(r, fill)
                bold.color = pal.dim; bold.alpha = 255; bold.textSize = side * 0.5f
                canvas.drawText("♪", r.centerX() - bold.measureText("♪") / 2f, r.centerY() + bold.textSize * 0.35f, bold)
            }
            canvas.restore()
            val textLeft = ax + side + pad * 0.9f
            val textWidth = b.left + b.width - pad - textLeft
            bold.color = pal.text; bold.alpha = 255; bold.textSize = h * 0.21f
            plain.color = pal.dim; plain.alpha = 255; plain.textSize = h * 0.17f
            canvas.drawText(ellipsized(if (sample) "Now playing" else title ?: "", bold, textWidth), textLeft, b.top + h * 0.36f, bold)
            canvas.drawText(ellipsized(if (sample) "Artist" else artist ?: "", plain, textWidth), textLeft, b.top + h * 0.60f, plain)
            val p = (if (sample) 0.4f else played) ?: return
            val y = b.top + h * 0.78f
            val weight = h * 0.045f
            fill.color = pal.track
            r.set(textLeft, y, textLeft + textWidth, y + weight)
            canvas.drawRect(r, fill)
            fill.color = ClusterLayout.GAUGE_BAR
            r.right = textLeft + textWidth * p
            canvas.drawRect(r, fill)
            // The orange mark the gauges put at the end of their own bar.
            fill.color = pal.accent
            r.set(r.right - weight * 0.35f, y - weight * 0.6f, r.right + weight * 0.35f, y + weight * 1.6f)
            canvas.drawRect(r, fill)
        }

        private fun drawCall(canvas: Canvas, c: NowPlayingState.CallCard, b: ClusterLayout.Box, pal: ClusterLayout.Palette, onPanel: Boolean) {
            val h = b.height
            val pad = h * 0.13f
            if (!onPanel) {
                fill.color = pal.card
                r.set(b.left, b.top, b.left + b.width, b.top + h)
                canvas.drawRoundRect(r, h * 0.18f, h * 0.18f, fill)
            }
            // A green disc with a handset: an arc with a heavier lump at each end.
            val d = h - pad * 2
            val cx = b.left + pad + d / 2f
            val cy = b.top + h / 2f
            fill.color = ClusterLayout.GREEN
            canvas.drawCircle(cx, cy, d / 2f, fill)
            ring.color = Color.WHITE
            ring.strokeWidth = d * 0.09f
            r.set(cx - d * 0.26f, cy - d * 0.10f, cx + d * 0.26f, cy + d * 0.42f)
            canvas.drawArc(r, 200f, 140f, false, ring)
            ring.strokeWidth = d * 0.17f
            canvas.drawArc(r, 200f, 22f, false, ring)
            canvas.drawArc(r, 318f, 22f, false, ring)

            val textLeft = b.left + pad + d + pad
            val textWidth = b.left + b.width - pad - textLeft
            bold.color = pal.text; bold.textSize = h * 0.22f
            plain.color = pal.dim; plain.textSize = h * 0.18f
            val name = c.name?.trim()?.takeIf { it.isNotEmpty() } ?: "Phone call"
            canvas.drawText(ellipsized(name, bold, textWidth), textLeft, b.top + h * 0.44f, bold)
            canvas.drawText(callLine ?: "", textLeft, b.top + h * 0.72f, plain)
        }
    }
}
