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
 *    clock, outside temperature. Right-aligned, slightly brighter and larger text than the icons.
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
        val icon = h * 0.46f                 // icon box size
        val pitch = h * 0.88f                // centre-to-centre spacing (photo: ~0.88 × bar height)
        var cx = h * 0.95f                   // first icon centre
        val cy = h * 0.47f
        stroke.strokeWidth = h * 0.028f
        for ((i, b) in Button.values().withIndex()) {
            val col = if (b == selected) iconHi else iconCol
            stroke.color = col
            fill.color = col
            hitRects[i].set(cx - pitch / 2, 0f, cx + pitch / 2, h)
            drawIcon(c, b, cx, cy, icon)
            cx += pitch
        }

        // ---- right cluster (right-aligned) ----
        text.textSize = h * 0.30f
        text.color = textCol
        var x = w - h * 0.35f
        temperature?.let { t ->
            x -= text.measureText(t)
            c.drawText(t, x, cy + text.textSize * 0.36f, text)
            x -= h * 0.42f
        }
        val clock = DateFormat.getTimeFormat(context).format(Date())
        x -= text.measureText(clock)
        c.drawText(clock, x, cy + text.textSize * 0.36f, text)
        x -= h * 0.45f

        val small = h * 0.30f
        stroke.color = iconCol; fill.color = iconCol
        stroke.strokeWidth = h * 0.024f
        drawPin(c, x - small / 2, cy, small, filled = false)
        x -= small + h * 0.32f

        if (signalBars >= 0) {
            val blockW = h * 0.36f
            text.textSize = h * 0.17f
            text.color = iconCol
            c.drawText(networkLabel, x - blockW, cy, text)
            val barW = blockW / 5.5f
            for (k in 0 until 4) {
                fill.color = if (k < signalBars) iconCol else 0x55C3CCDB
                val bx = x - blockW + k * barW * 1.35f
                c.drawRect(bx, cy + h * 0.05f, bx + barW, cy + h * 0.10f, fill)
            }
            x -= blockW + h * 0.36f
        }

        stroke.color = iconCol
        drawBell(c, x - small / 2, cy, small)
        x -= small + h * 0.40f

        val ar = h * 0.20f
        fill.color = avatarFill
        c.drawCircle(x - ar, cy, ar, fill)
        text.textSize = h * 0.24f
        text.color = textCol
        text.textAlign = Paint.Align.CENTER
        c.drawText(profileInitial, x - ar, cy + text.textSize * 0.36f, text)
        text.textAlign = Paint.Align.LEFT
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
        Button.CAR -> drawCar(c, cx, cy, s)
        Button.APPS -> drawApps(c, cx, cy, s)
        Button.CONTROLS -> drawControls(c, cx, cy, s)
        Button.MUSIC -> drawMusic(c, cx, cy, s)
        Button.NAV -> drawPin(c, cx, cy, s, filled = true)
        Button.PHONE -> drawPhone(c, cx, cy, s)
        Button.ENERGY -> drawBolt(c, cx, cy, s)
    }

    /** 3/4 rear view of a crossover: roof slope, body block, wheel arch. */
    private fun drawCar(c: Canvas, cx: Float, cy: Float, s: Float) {
        val l = cx - s * 0.5f; val t = cy - s * 0.42f; val rr = cx + s * 0.5f; val b = cy + s * 0.45f
        path.reset()
        path.moveTo(l, b)
        path.lineTo(l, cy - s * 0.02f)
        path.lineTo(cx - s * 0.10f, t)               // roof front
        path.lineTo(rr - s * 0.12f, t + s * 0.10f)   // roof rear
        path.lineTo(rr, cy + s * 0.05f)
        path.lineTo(rr, b)
        path.close()
        c.drawPath(path, stroke)
        // body crease lines
        c.drawLine(l, cy + s * 0.12f, rr, cy + s * 0.18f, stroke)
        c.drawLine(l + s * 0.08f, cy + s * 0.28f, rr - s * 0.05f, cy + s * 0.32f, stroke)
        c.drawLine(cx - s * 0.10f, t, cx - s * 0.02f, cy + s * 0.12f, stroke)
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

    /** Beamed pair of eighth notes. */
    private fun drawMusic(c: Canvas, cx: Float, cy: Float, s: Float) {
        val hr = s * 0.15f
        val lx = cx - s * 0.30f; val rx = cx + s * 0.30f
        val ly = cy + s * 0.34f; val ry = cy + s * 0.24f
        r.set(lx - hr * 1.3f, ly - hr, lx + hr * 0.2f, ly + hr * 0.8f); c.drawOval(r, stroke)
        r.set(rx - hr * 1.3f, ry - hr, rx + hr * 0.2f, ry + hr * 0.8f); c.drawOval(r, stroke)
        c.drawLine(lx, ly - hr * 0.2f, lx, cy - s * 0.32f, stroke)
        c.drawLine(rx, ry - hr * 0.2f, rx, cy - s * 0.44f, stroke)
        c.drawLine(lx, cy - s * 0.32f, rx, cy - s * 0.44f, stroke)
        c.drawLine(lx, cy - s * 0.18f, rx, cy - s * 0.30f, stroke)
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

    /** Portrait phone outline. */
    private fun drawPhone(c: Canvas, cx: Float, cy: Float, s: Float) {
        r.set(cx - s * 0.27f, cy - s * 0.50f, cx + s * 0.27f, cy + s * 0.50f)
        c.drawRoundRect(r, s * 0.10f, s * 0.10f, stroke)
    }

    /** Thin lightning bolt. */
    private fun drawBolt(c: Canvas, cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx + s * 0.18f, cy - s * 0.52f)
        path.lineTo(cx - s * 0.22f, cy + s * 0.06f)
        path.lineTo(cx + s * 0.06f, cy + s * 0.04f)
        path.lineTo(cx - s * 0.16f, cy + s * 0.52f)
        c.drawPath(path, stroke)
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
