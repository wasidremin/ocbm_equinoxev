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
import kotlin.math.min

/**
 * Look-alike of the Equinox EV (GM AAOS 14) top bar, drawn in code. Framework-only.
 *
 * Measured from a photo of the car's own bar:
 *  - 7 left icons, thin outline, pale blue-white, evenly spaced, starting near the left edge:
 *    car, apps, controls, music, navigation (pin), phone, energy (bolt).
 *  - Right cluster: profile initial in a dark circle, bell, "5G" over signal bars, small pin,
 *    clock, outside temperature. Smaller than the left icons, indented from the edge, and hung
 *    from the panel-slope clearance like the car's own bar — centering in the rect puts it on
 *    missing glass where the top edge slopes away.
 *  - Background: near-black navy, faint top-to-bottom gradient; a thin blue hairline at the bottom.
 *
 * Left icons are tappable and call [onButton]. The right cluster is display only.
 * Everything scales from the view height, so one height value sets the whole bar.
 */
class GmTopBarView(ctx: Context) : View(ctx) {

    enum class Button { CAR, APPS, CONTROLS, MUSIC, NAV, PHONE, ENERGY }

    var onButton: ((Button) -> Unit)? = null
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
    /** Which left icon to draw highlighted (white), or null. In the photo none is selected. */
    var selected: Button? = null
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
    private val iconHi = 0xFFFFFFFF.toInt()
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
    private val hitRects = Array(Button.values().size) { RectF() }

    // ---- 24-unit outline glyphs (car / music / nav / phone / energy) ----------------------------
    // Previewed against the existing apps/controls glyphs on 2026-10-15. Drawn at 1.2x the icon box
    // because the 24-unit art carries ~2.5 units of padding.

    private class Glyph(
        val strokes: List<Path>,
        val fills: List<Path> = emptyList(),
        /** Filled with the bar background, then stroked — covers body lines (car wheels). */
        val knockouts: List<Path> = emptyList(),
    )

    private fun circle(cx: Float, cy: Float, r: Float) = Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }

    /** Minimal SVG path parser: absolute M L H V C Z only (all glyph strings below use only these). */
    private fun svg(d: String): Path {
        val p = Path()
        val tokens = Regex("[MLHVCZ]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0
        var cmd = 'M'
        var x = 0f
        var y = 0f
        fun num(): Float = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.length == 1 && t[0].isLetter()) {
                cmd = t[0]
                i++
                if (cmd == 'Z') {
                    p.close()
                    continue
                }
            }
            when (cmd) {
                'M' -> { x = num(); y = num(); p.moveTo(x, y); cmd = 'L' }
                'L' -> { x = num(); y = num(); p.lineTo(x, y) }
                'H' -> { x = num(); p.lineTo(x, y) }
                'V' -> { y = num(); p.lineTo(x, y) }
                'C' -> {
                    val x1 = num(); val y1 = num(); val x2 = num(); val y2 = num()
                    x = num(); y = num()
                    p.cubicTo(x1, y1, x2, y2, x, y)
                }
                else -> throw IllegalArgumentException("unsupported path command $cmd in \"$d\"")
            }
        }
        return p
    }

    private val glyphCar = Glyph(
        strokes = listOf(
            svg("M2.5 16.5V12.8L4.6 11.6L7.4 6.5H16.6L19.4 11.6L21.5 12.8V16.5Z"),
            svg("M6.4 11.4H17.6"),
        ),
        knockouts = listOf(circle(7f, 16.8f, 2.3f), circle(17f, 16.8f, 2.3f)),
    )
    private val glyphMusic = Glyph(
        strokes = listOf(svg("M9.6 17.3V5.8L19.4 3.8V15.3"), svg("M9.6 9.2L19.4 7.2")),
        fills = listOf(circle(7.3f, 17.3f, 2.4f), circle(17.1f, 15.3f, 2.4f)),
    )
    private val glyphNav = Glyph(
        strokes = listOf(
            svg("M12 21.5C12 21.5 5 14.8 5 9.6C5 5.7 8.1 2.6 12 2.6C15.9 2.6 19 5.7 19 9.6C19 14.8 12 21.5 12 21.5Z"),
            circle(12f, 9.6f, 2.6f),
        ),
    )
    private val glyphPhone = Glyph(
        strokes = listOf(
            svg("M7.2 3.2H9.6L11.2 7.6L9.1 9.2C10.2 11.7 12.3 13.8 14.8 14.9L16.4 12.8L20.8 14.4V16.8C20.8 18.9 19.1 20.6 17 20.6C9.6 20.1 3.9 14.4 3.4 7C3.4 4.9 5.1 3.2 7.2 3.2Z"),
        ),
    )
    private val glyphBolt = Glyph(strokes = listOf(svg("M13.6 2.6L5.6 13.4H11.6L10.4 21.4L18.4 10.6H12.4Z")))

    private fun drawGlyph(c: Canvas, g: Glyph, cx: Float, cy: Float, s: Float) {
        val size = s * 1.2f
        val k = size / 24f
        val oldW = stroke.strokeWidth
        c.save()
        c.translate(cx - size / 2f, cy - size / 2f)
        c.scale(k, k)
        stroke.strokeWidth = oldW / k
        for (p in g.strokes) c.drawPath(p, stroke)
        for (p in g.fills) c.drawPath(p, fill)
        for (p in g.knockouts) {
            val col = fill.color
            fill.color = bgTop
            c.drawPath(p, fill)
            fill.color = col
            c.drawPath(p, stroke)
        }
        c.restore()
        stroke.strokeWidth = oldW
    }

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

        // ---- left icons ----
        // A level row that clears the panel slope at its LAST (rightmost) icon: on the
        // Equinox wedge (m = 0.06352) the 7th icon's box top would otherwise sit ~30px under
        // the slope. Zero shift on a flat panel.
        val icon = h * 0.46f                 // icon box size
        val pitch = h * 0.88f                // centre-to-centre spacing (photo: ~0.88 × bar height)
        var cx = h * 0.95f                   // first icon centre
        val cy = h * 0.47f
        val margin = h * 0.05f               // glass to spare above content and below it
        val cyL = cy + DisplayGeometry.levelShift(cy - icon / 2f,
            cx + 6 * pitch, slopeM, margin)
        stroke.strokeWidth = h * 0.028f
        for ((i, b) in Button.values().withIndex()) {
            val col = if (b == selected) iconHi else iconCol
            stroke.color = col
            fill.color = col
            hitRects[i].set(cx - pitch / 2, 0f, cx + pitch / 2, h)
            drawIcon(c, b, cx, cyL, icon)
            cx += pitch
        }

        // ---- right cluster: right-justified to usable glass, never overlapping left ----
        // Justified placement, solved per frame — see DisplayGeometry.topBarLayout. The left
        // block above is left-justified to its measured start (the car's own spacing); this
        // cluster is right-justified to the slope-solved usable-glass edge (on the vehicle
        // near x 1600, where ~50px of glass exists — NOT the window edge x 2540, where 7px
        // exists). The middle gap absorbs the remainder, with half a bar height held sacred
        // so the groups never touch: when the slot is tight the cluster shrinks rather than
        // closing the gap. Like the car's own bar the right side runs SMALLER than the left
        // icons and hangs from the slope clearance instead of centering in the rect. Zero
        // slope keeps the centered layout. Left icons keep their measured size.
        val leftEnd = cx - pitch / 2f
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
        val layout = DisplayGeometry.topBarLayout(
            w, h, rightInsetPx, slopeM, contentH, margin, leftEnd, natural)
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
                if (rect.contains(e.x, e.y)) { onButton?.invoke(Button.values()[i]); performClick(); break }
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    // ---------------------------------------------------------------- icons

    private fun drawIcon(c: Canvas, b: Button, cx: Float, cy: Float, s: Float) = when (b) {
        Button.CAR -> drawGlyph(c, glyphCar, cx, cy, s)
        Button.APPS -> drawApps(c, cx, cy, s)
        Button.CONTROLS -> drawControls(c, cx, cy, s)
        Button.MUSIC -> drawGlyph(c, glyphMusic, cx, cy, s)
        Button.NAV -> drawGlyph(c, glyphNav, cx, cy, s)
        Button.PHONE -> drawGlyph(c, glyphPhone, cx, cy, s)
        Button.ENERGY -> drawGlyph(c, glyphBolt, cx, cy, s)
    }

    /** Tall panel on the left, two stacked panels on the right. */
    private fun drawApps(c: Canvas, cx: Float, cy: Float, s: Float) {
        val half = s * 0.46f; val gap = s * 0.10f; val rad = s * 0.06f
        r.set(cx - half, cy - half, cx - gap / 2, cy + half); c.drawRoundRect(r, rad, rad, stroke)
        r.set(cx + gap / 2, cy - half, cx + half, cy - gap / 2); c.drawRoundRect(r, rad, rad, stroke)
        r.set(cx + gap / 2, cy + gap / 2, cx + half, cy + half); c.drawRoundRect(r, rad, rad, stroke)
    }

    /** Two pill toggles: top knob on the right, bottom knob on the left. */
    private fun drawControls(c: Canvas, cx: Float, cy: Float, s: Float) {
        val w = s * 0.95f; val ph = s * 0.36f; val rad = ph / 2
        // (knob on the right?, vertical offset)
        for ((knobRight, dir) in listOf(true to -1f, false to 1f)) {
            val pcY = cy + dir * s * 0.24f
            r.set(cx - w / 2, pcY - ph / 2, cx + w / 2, pcY + ph / 2)
            c.drawRoundRect(r, rad, rad, stroke)
            val kx = if (knobRight) cx + w / 2 - rad else cx - w / 2 + rad
            c.drawCircle(kx, pcY, rad * 0.62f, fill)
        }
    }

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
    }
}
