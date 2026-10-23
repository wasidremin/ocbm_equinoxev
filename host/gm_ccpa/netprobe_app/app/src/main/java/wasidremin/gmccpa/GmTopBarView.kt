package wasidremin.gmccpa

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import java.util.Date

/**
 * The custom top bar, drawn in code. Framework-only.
 *
 * Left: nine CarPlay-style app tiles ([BarTarget]) in two families — our app first (CarPlay
 * home / maps / Spotify / phone, then this app's settings and EV insights), then a divider, then
 * the car's own apps (home, charging, settings), each flagged with a small corner car badge.
 * Sized by [DisplayGeometry.topBarTiles] so the whole level row is on visible glass whatever the
 * panel slope, with an extra gap ([GROUP_GAP_FRAC]) holding the divider between the families.
 *
 * The right cluster is modelled on the car's own bar (measured from a photo):
 *  - Right cluster: profile initial in a dark circle, bell, "5G" over signal bars, small pin,
 *    clock, outside temperature. Smaller than the left icons, indented from the edge, and hung
 *    from the panel-slope clearance like the car's own bar — centering in the rect puts it on
 *    missing glass where the top edge slopes away.
 *  - Background: near-black navy, faint top-to-bottom gradient; a thin blue hairline at the bottom.
 *
 * Tiles are tappable and call [onButton]. The right cluster is display only.
 * Everything scales from the view height, so one height value sets the whole bar.
 */
class GmTopBarView(ctx: Context) : View(ctx) {

    var onButton: ((BarTarget) -> Unit)? = null
    var profileInitial: String = "J"
        set(v) { field = v; invalidate() }
    /** Outside temperature already in the user's units, e.g. "68°". Null hides it. */
    var temperature: String? = null
        set(v) { field = v; invalidate() }
    /** 0..4 bars, or -1 to hide the 5G/signal block. */
    var signalBars: Int = -1
        set(v) { field = v; invalidate() }
    var networkLabel: String = "5G"
        set(v) { field = v; invalidate() }
    /** Which tile to ring, or null. */
    var selected: BarTarget? = null
        set(v) { field = v; invalidate() }
    /**
     * Window pixels of display cutout hiding the bar's right end (live
     * `displayCutout.safeInsetRight`; 285 on the Equinox EV). The bar background still spans
     * the full width, but the right cluster starts this far left of the historical end margin
     * so the clock + temperature — the rightmost items — land on glass instead of under the
     * cutout. Zero keeps the historical right-aligned layout.
     */
    var rightInsetPx: Int = 0
        set(v) { field = v.coerceAtLeast(0); invalidate() }
    /**
     * Slope of the panel's top-edge cutout wedge (glass starts at y = slopeM * x; 0.06352 on
     * the Equinox EV, 0 on a rectangular panel). The bar's bottom row is on glass almost
     * everywhere while its top is progressively missing, so content hangs from slope-clearing
     * tops near the bottom instead of centering in the rect. Zero keeps the centered layout.
     */
    var slopeM: Float = 0f
        set(v) { field = v.coerceAtLeast(0f); invalidate() }

    // Colours sampled from the photo.
    private val bgTop = 0xFF0B1220.toInt()
    private val bgBottom = 0xFF060A14.toInt()
    private val iconCol = 0xFFC3CCDB.toInt()     // pale blue-white outline
    private val textCol = 0xFFE9EDF4.toInt()
    private val hairline = 0xFF2E6BD8.toInt()     // bottom divider
    private val avatarFill = 0xFF2A2F3A.toInt()

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.LEFT
    }
    private val bg = Paint()
    private val path = Path()
    private val r = RectF()
    private val hitRects = Array(BarTarget.values().size) { RectF() }
    private val glyphs = BarGlyphs()

    private val tick = object : Runnable {
        override fun run() { invalidate(); postDelayed(this, 15_000) }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), bgTop, bgBottom, Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        c.drawRect(0f, 0f, w, h, bg)

        // Bottom hairline, fading out at both ends like the car's.
        fill.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(Color.TRANSPARENT, hairline, hairline, Color.TRANSPARENT),
            floatArrayOf(0f, 0.04f, 0.96f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, h - h * 0.025f, w, h, fill)
        fill.shader = null

        // ---- right cluster measurements (needed first: they bound the tile row) ----
        val cy = h * 0.47f
        val margin = h * 0.05f               // glass to spare above content and below it
        val clock = DateFormat.getTimeFormat(context).format(Date())
        val baseText = h * 0.24f
        val baseSmall = h * 0.24f
        val baseAvatarR = h * 0.14f
        val baseSignalW = h * 0.28f
        val contentH = maxOf(baseSmall, baseAvatarR * 2f, baseText)
        text.textSize = baseText
        var natural = text.measureText(clock) + h * 0.30f +
            baseSmall + h * 0.22f +
            baseSmall + h * 0.28f +
            baseAvatarR * 2f
        temperature?.let { natural += text.measureText(it) + h * 0.30f }
        if (signalBars >= 0) natural += baseSignalW + h * 0.26f

        // ---- left tiles ----
        // One level row of CarPlay-style tiles, bottom-justified and sized so the LAST tile
        // still clears the panel slope (DisplayGeometry.topBarTiles). If the right cluster
        // would be crushed below 80% of its natural width the tiles shrink a little instead.
        val targets = BarTarget.values()
        val breakAfter = targets.indexOfFirst { it.group == BarGroup.CAR }.let { if (it < 0) 0 else it }
        val row = DisplayGeometry.topBarTiles(
            barHeight = h, slopeM = slopeM, count = targets.size, x0 = h * 0.42f, margin = margin,
            breakAfter = breakAfter, groupGapFrac = GROUP_GAP_FRAC,
            rightClusterRoom = { end ->
                DisplayGeometry.topBarLayout(w, h, rightInsetPx, slopeM, contentH, margin, end, natural).rightScale
            })
        for ((i, t) in targets.withIndex()) {
            val tx = row.tileLeft(i)
            hitRects[i].set(tx - (row.pitch - row.size) / 2f, 0f, tx + row.size + (row.pitch - row.size) / 2f, h)
            glyphs.drawTile(c, t, tx, row.top, row.size, selected = t == selected, badge = t.group == BarGroup.CAR)
        }
        // Car-vs-ours divider, in the extra gap between the two tile families.
        if (row.dividerX > 0f) {
            val dh = row.size * 0.62f
            val dw = (row.size * 0.045f).coerceAtLeast(2f)
            val top = row.top + (row.size - dh) / 2f
            fill.color = hairline
            r.set(row.dividerX - dw / 2f, top, row.dividerX + dw / 2f, top + dh)
            c.drawRoundRect(r, dw / 2f, dw / 2f, fill)
        }

        // ---- right cluster: right-justified to usable glass, never overlapping left ----
        // Justified placement, solved per frame — see DisplayGeometry.topBarLayout. This
        // cluster is right-justified to the slope-solved usable-glass edge (on the vehicle
        // near x 1600, where ~50px of glass exists — NOT the window edge x 2540, where 7px
        // exists). The middle gap absorbs the remainder, with half a bar height held sacred
        // so the groups never touch: when the slot is tight the cluster shrinks rather than
        // closing the gap. Like the car's own bar the right side runs SMALLER than the tiles
        // and hangs from the slope clearance instead of centering in the rect. Zero
        // slope keeps the centered layout.
        val layout = DisplayGeometry.topBarLayout(
            w, h, rightInsetPx, slopeM, contentH, margin, row.end, natural)
        val edge = layout.rightEdge
        val fit = layout.rightScale
        if (fit > 0f) {
            val ts = baseText * fit
            val small = baseSmall * fit
            val scaledH = contentH * fit
            // Anchor the TOP on the slope clearance at the edge (worst slope in the span),
            // grow downward; shrinking only pulls the bottom up, so visibility is monotonic.
            val top = maxOf(cy - scaledH / 2f, slopeM * edge + margin)
            val mid = top + scaledH / 2f
            text.textSize = ts
            text.color = textCol
            var x = edge
            temperature?.let { t ->
                x -= text.measureText(t)
                c.drawText(t, x, mid + ts * 0.36f, text)
                x -= h * 0.30f * fit
            }
            x -= text.measureText(clock)
            c.drawText(clock, x, mid + ts * 0.36f, text)
            x -= h * 0.30f * fit

            stroke.color = iconCol; fill.color = iconCol
            stroke.strokeWidth = h * 0.022f * fit
            drawPin(c, x - small / 2, mid, small, filled = false)
            x -= small + h * 0.22f * fit

            if (signalBars >= 0) {
                val blockW = baseSignalW * fit
                text.textSize = h * 0.12f * fit
                text.color = iconCol
                c.drawText(networkLabel, x - blockW, mid + text.textSize * 0.3f, text)
                val barW = blockW / 5.5f
                for (k in 0 until 4) {
                    fill.color = if (k < signalBars) iconCol else 0x55C3CCDB
                    val bx = x - blockW + k * barW * 1.35f
                    c.drawRect(bx, mid + h * 0.03f * fit, bx + barW, mid + h * 0.08f * fit, fill)
                }
                x -= blockW + h * 0.26f * fit
            }

            stroke.color = iconCol
            drawBell(c, x - small / 2, mid, small)
            x -= small + h * 0.28f * fit

            val ar = baseAvatarR * fit
            fill.color = avatarFill
            c.drawCircle(x - ar, mid, ar, fill)
            text.textSize = h * 0.18f * fit
            text.color = textCol
            text.textAlign = Paint.Align.CENTER
            c.drawText(profileInitial, x - ar, mid + text.textSize * 0.36f, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) {
            for ((i, rect) in hitRects.withIndex()) {
                if (rect.contains(e.x, e.y)) { onButton?.invoke(BarTarget.values()[i]); performClick(); break }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------- icons

    /** Map pin. Filled with a cut-out dot for the bar button; outline for the small status pin. */
    private fun drawPin(c: Canvas, cx: Float, cy: Float, s: Float, filled: Boolean) {
        val rad = s * 0.34f
        val top = cy - s * 0.12f
        path.reset()
        path.addCircle(cx, top, rad, Path.Direction.CW)
        path.moveTo(cx - rad * 0.86f, top + rad * 0.5f)
        path.lineTo(cx, cy + s * 0.50f)
        path.lineTo(cx + rad * 0.86f, top + rad * 0.5f)
        if (filled) {
            c.drawPath(path, fill)
            val old = fill.color
            fill.color = bgTop
            c.drawCircle(cx, top, rad * 0.40f, fill)
            fill.color = old
        } else {
            c.drawCircle(cx, top, rad, stroke)
            c.drawLine(cx - rad * 0.86f, top + rad * 0.5f, cx, cy + s * 0.50f, stroke)
            c.drawLine(cx + rad * 0.86f, top + rad * 0.5f, cx, cy + s * 0.50f, stroke)
            c.drawCircle(cx, top, rad * 0.30f, stroke)
        }
    }

    private fun drawBell(c: Canvas, cx: Float, cy: Float, s: Float) {
        val w = s * 0.42f
        path.reset()
        path.moveTo(cx - w, cy + s * 0.25f)
        path.lineTo(cx - w * 0.75f, cy + s * 0.10f)
        path.lineTo(cx - w * 0.75f, cy - s * 0.10f)
        path.cubicTo(cx - w * 0.75f, cy - s * 0.48f, cx + w * 0.75f, cy - s * 0.48f, cx + w * 0.75f, cy - s * 0.10f)
        path.lineTo(cx + w * 0.75f, cy + s * 0.10f)
        path.lineTo(cx + w, cy + s * 0.25f)
        path.close()
        c.drawPath(path, stroke)
        c.drawLine(cx - s * 0.10f, cy + s * 0.40f, cx + s * 0.10f, cy + s * 0.40f, stroke)
    }

    companion object {
        /** Bar height as a fraction of the panel height, from the photo (~80 px of 960). */
        const val HEIGHT_FRACTION = 0.083f
        /** Extra gap (in tile widths) between the our-app and car-app tile families. */
        const val GROUP_GAP_FRAC = 0.6f
    }
}
