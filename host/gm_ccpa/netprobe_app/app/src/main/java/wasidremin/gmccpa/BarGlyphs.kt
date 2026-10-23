package wasidremin.gmccpa

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.sin

/** Intent extra: the settings tab (0..3) [MainActivity] should show when it comes forward. */
internal const val EXTRA_OPEN_TAB = "gmccpa.open_tab"
/** Intent extra: a [BarTarget] name the CarPlay screen should run once it is in front. */
internal const val EXTRA_BAR_TARGET = "gmccpa.bar_target"

/**
 * Which family a tile belongs to. [OURS] tiles keep you inside this app (the CarPlay surfaces it
 * projects and its own pages); [CAR] tiles leave for the car's native AAOS apps. The bar draws
 * all [OURS] first, then a divider, then all [CAR], and badges the [CAR] tiles — so the two
 * families must stay contiguous in [BarTarget]'s declaration order (asserted in BarTilesTest).
 */
enum class BarGroup { OURS, CAR }

/**
 * What a top-bar tile does. One enum for both activities that host the bar so a tap means the
 * same thing from the CarPlay picture and from settings.
 *
 * Declaration order IS the on-bar order (left to right), and [group] splits it into the two
 * families the bar separates. Named for where they GO, not what they look like: CP_* drive the
 * CarPlay UI this app projects, APP_SETTINGS / INSIGHTS are this app's own pages (both [OURS]),
 * and AAOS_* / CHARGING leave for the car's own apps ([CAR]). Owner's order (2026-10-23): our
 * app's tiles first — CarPlay home/maps/Spotify/phone, then this app's settings and insights —
 * then the car's own home, charging and settings.
 */
enum class BarTarget(val description: String, val group: BarGroup) {
    CP_HOME("CarPlay home", BarGroup.OURS),
    CP_MAPS("CarPlay Maps", BarGroup.OURS),
    CP_SPOTIFY("CarPlay Spotify", BarGroup.OURS),
    CP_PHONE("CarPlay Phone", BarGroup.OURS),
    APP_SETTINGS("App settings", BarGroup.OURS),
    INSIGHTS("EV insights", BarGroup.OURS),
    AAOS_HOME("Car home screen", BarGroup.CAR),
    CHARGING("Charging", BarGroup.CAR),
    AAOS_SETTINGS("Car settings", BarGroup.CAR),
}

/**
 * CarPlay-style app tiles for the custom top bar: a rounded square with a soft vertical
 * gradient and a solid white (or app-coloured) glyph, like the icons on the iPhone's CarPlay
 * grid. Drawn in code (framework-only, no assets), on a 24-unit grid scaled to the tile.
 *
 * Not thread-safe: owns reusable Paints and Paths for one View's draw pass.
 */
internal class BarGlyphs {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val work = Path()

    private class Look(val top: Int, val bottom: Int)

    private fun look(t: BarTarget): Look = when (t) {
        BarTarget.AAOS_HOME -> Look(0xFF4C8DF6.toInt(), 0xFF1D55CF.toInt())
        BarTarget.CHARGING -> Look(0xFF3DD16B.toInt(), 0xFF1C9B44.toInt())
        BarTarget.CP_HOME -> Look(0xFF48484A.toInt(), 0xFF1C1C1E.toInt())
        BarTarget.CP_MAPS -> Look(0xFFFAFAFC.toInt(), 0xFFD7D9E0.toInt())
        BarTarget.CP_SPOTIFY -> Look(0xFF262626.toInt(), 0xFF0B0B0B.toInt())
        BarTarget.AAOS_SETTINGS -> Look(0xFFA0A0A8.toInt(), 0xFF6E6E76.toInt())
        BarTarget.CP_PHONE -> Look(0xFF3FD35E.toInt(), 0xFF22963F.toInt())
        BarTarget.APP_SETTINGS -> Look(0xFF7A78F2.toInt(), 0xFF433FB8.toInt())
        BarTarget.INSIGHTS -> Look(0xFFFFB02E.toInt(), 0xFFE0580C.toInt())
    }

    /**
     * Draws [t]'s tile with its top-left at ([x], [y]) and edge [s]. [selected] adds a ring;
     * [badge] stamps the small car mark in the top-right corner that flags a tile as leaving for
     * the car's own app (the caller passes `t.group == BarGroup.CAR`).
     */
    fun drawTile(c: Canvas, t: BarTarget, x: Float, y: Float, s: Float, selected: Boolean = false, badge: Boolean = false) {
        val l = look(t)
        val rad = s * 0.225f
        rect.set(x, y, x + s, y + s)
        fill.shader = LinearGradient(0f, y, 0f, y + s, l.top, l.bottom, Shader.TileMode.CLAMP)
        c.drawRoundRect(rect, rad, rad, fill)
        fill.shader = null
        // Hairline highlight, like the glass edge on CarPlay's own tiles.
        stroke.strokeWidth = (s * 0.012f).coerceAtLeast(1f)
        stroke.color = 0x33FFFFFF
        rect.inset(stroke.strokeWidth / 2f, stroke.strokeWidth / 2f)
        c.drawRoundRect(rect, rad, rad, stroke)
        if (selected) {
            stroke.strokeWidth = s * 0.05f
            stroke.color = 0xFFFFFFFF.toInt()
            rect.set(x - s * 0.04f, y - s * 0.04f, x + s + s * 0.04f, y + s + s * 0.04f)
            c.drawRoundRect(rect, rad * 1.15f, rad * 1.15f, stroke)
        }
        glyph(c, t, x, y, s)
        if (badge) carBadge(c, x + s, y, s)
    }

    /**
     * Small car mark pinned to a tile's top-right corner ([cornerX], [cornerY] is the tile's
     * top-right point), on a dark translucent disc so it reads over any tile colour. Flags a CAR
     * tile as "opens the car's own app". A side-view car: rounded body, cabin, two wheels.
     */
    private fun carBadge(c: Canvas, cornerX: Float, cornerY: Float, s: Float) {
        val d = s * 0.42f
        val cx = cornerX - d * 0.48f
        val cy = cornerY + d * 0.48f
        fill.color = 0xE60B1220.toInt()
        c.drawCircle(cx, cy, d * 0.5f, fill)
        stroke.color = 0x55FFFFFF
        stroke.strokeWidth = (d * 0.04f).coerceAtLeast(1f)
        c.drawCircle(cx, cy, d * 0.5f, stroke)
        // car on a 10-unit grid centred in the disc
        c.save()
        c.translate(cx - d * 0.34f, cy - d * 0.34f)
        val k = d * 0.068f
        c.scale(k, k)
        fill.color = WHITE
        rect.set(0.6f, 4.6f, 9.4f, 7.4f)          // body
        c.drawRoundRect(rect, 1.2f, 1.2f, fill)
        work.reset()                               // cabin
        work.moveTo(2.6f, 4.8f)
        work.lineTo(3.7f, 2.9f)
        work.lineTo(6.6f, 2.9f)
        work.lineTo(7.4f, 4.8f)
        work.close()
        c.drawPath(work, fill)
        fill.color = 0xE60B1220.toInt()            // wheels (cut into the body colour)
        c.drawCircle(3.1f, 7.6f, 1.25f, fill)
        c.drawCircle(6.9f, 7.6f, 1.25f, fill)
        fill.color = WHITE
        c.drawCircle(3.1f, 7.6f, 0.62f, fill)
        c.drawCircle(6.9f, 7.6f, 0.62f, fill)
        c.restore()
    }

    private fun glyph(c: Canvas, t: BarTarget, x: Float, y: Float, s: Float) {
        c.save()
        c.translate(x, y)
        val k = s / 24f
        c.scale(k, k)
        when (t) {
            BarTarget.AAOS_HOME -> solid(c, HOUSE, WHITE)
            BarTarget.CHARGING -> {
                solid(c, BOLT, WHITE)
            }
            BarTarget.CP_HOME -> grid(c)
            BarTarget.CP_MAPS -> maps(c)
            BarTarget.CP_SPOTIFY -> spotify(c)
            BarTarget.AAOS_SETTINGS -> {
                solid(c, GEAR, WHITE)
            }
            BarTarget.CP_PHONE -> solid(c, PHONE, WHITE)
            BarTarget.APP_SETTINGS -> sliders(c)
            BarTarget.INSIGHTS -> bars(c)
        }
        c.restore()
    }

    private fun solid(c: Canvas, p: Path, color: Int) {
        fill.color = color
        c.drawPath(p, fill)
    }

    /** CarPlay's Home glyph: a 3x3 grid of rounded squares. */
    private fun grid(c: Canvas) {
        fill.color = WHITE
        val cell = 4.4f
        val gap = 1.7f
        val origin = 12f - (3 * cell + 2 * gap) / 2f
        for (r in 0 until 3) for (q in 0 until 3) {
            val left = origin + q * (cell + gap)
            val top = origin + r * (cell + gap)
            rect.set(left, top, left + cell, top + cell)
            c.drawRoundRect(rect, 1.1f, 1.1f, fill)
        }
    }

    /** Apple-Maps-like: a blue navigation arrow over a pale map with a green park and a road. */
    private fun maps(c: Canvas) {
        // park (top-left wedge) and road, clipped to the tile's inner area
        fill.color = 0xFFBFE8C4.toInt()
        rect.set(2.2f, 2.2f, 12.4f, 10.6f)
        c.drawRoundRect(rect, 2f, 2f, fill)
        stroke.color = 0xFFFFD66B.toInt()
        stroke.strokeWidth = 2.2f
        work.reset()
        work.moveTo(2.4f, 17.6f)
        work.cubicTo(8f, 16.8f, 12f, 12.6f, 21.6f, 11.4f)
        c.drawPath(work, stroke)
        solid(c, ARROW, 0xFF0A84FF.toInt())
    }

    /** Spotify mark: green disc with three dark sound arcs, on its black tile. */
    private fun spotify(c: Canvas) {
        fill.color = 0xFF1ED760.toInt()
        c.drawCircle(12f, 12f, 9.6f, fill)
        stroke.color = 0xFF0B0B0B.toInt()
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeWidth = 1.7f
        c.drawPath(SPOT1, stroke)
        stroke.strokeWidth = 1.45f
        c.drawPath(SPOT2, stroke)
        stroke.strokeWidth = 1.2f
        c.drawPath(SPOT3, stroke)
    }

    /** Three tuning sliders. */
    private fun sliders(c: Canvas) {
        stroke.color = WHITE
        stroke.strokeWidth = 1.9f
        fill.color = WHITE
        val ys = floatArrayOf(6.4f, 12f, 17.6f)
        val ks = floatArrayOf(15.5f, 8.2f, 13.2f)
        for (i in 0..2) {
            c.drawLine(4.4f, ys[i], 19.6f, ys[i], stroke)
            fill.color = look(BarTarget.APP_SETTINGS).bottom
            c.drawCircle(ks[i], ys[i], 3.0f, fill)
            fill.color = WHITE
            c.drawCircle(ks[i], ys[i], 2.2f, fill)
        }
    }

    /** Ascending bar chart with a baseline. */
    private fun bars(c: Canvas) {
        fill.color = WHITE
        for (i in 0..3) {
            val left = 4.2f + i * 4.1f
            val top = 17.6f - (3.4f + i * 3.0f)
            rect.set(left, top, left + 2.9f, 17.6f)
            c.drawRoundRect(rect, 0.9f, 0.9f, fill)
        }
        stroke.color = 0x99FFFFFF.toInt()
        stroke.strokeWidth = 1.3f
        c.drawLine(3.4f, 19.9f, 20.6f, 19.9f, stroke)
    }

    companion object {
        private const val WHITE = 0xFFFFFFFF.toInt()

        /** Minimal SVG path parser: absolute M L H V C Z only (every glyph string uses only these). */
        fun svg(d: String): Path {
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

        /** Filled house with a door notch. */
        val HOUSE: Path = svg("M12 3.4L21.2 11.4H18.6V20.4H14.2V14.4H9.8V20.4H5.4V11.4H2.8Z")
        val BOLT: Path = svg("M13.6 2.4L5.2 13.6H11.4L10.2 21.6L18.8 10.2H12.6Z")
        /** Filled handset (phone.fill). */
        val PHONE: Path = svg(
            "M7.2 3.2H9.6L11.2 7.6L9.1 9.2C10.2 11.7 12.3 13.8 14.8 14.9L16.4 12.8L20.8 14.4V16.8" +
                "C20.8 18.9 19.1 20.6 17 20.6C9.6 20.1 3.9 14.4 3.4 7C3.4 4.9 5.1 3.2 7.2 3.2Z")
        /** Navigation arrow (location.fill). */
        val ARROW: Path = svg("M20.6 3.4L13.4 20.8L11.2 12.8L3.2 10.6Z")
        val SPOT1: Path = svg("M6.4 9.2C10.2 8 14.8 8.4 18.2 10.4")
        val SPOT2: Path = svg("M7.1 12.5C10.2 11.6 13.8 11.9 16.6 13.6")
        val SPOT3: Path = svg("M7.9 15.6C10.4 14.9 13.1 15.2 15.4 16.6")

        /** Eight-tooth gear with a centre hole, even-odd so the hole is cut out. */
        val GEAR: Path = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            val teeth = 8
            val outer = 10.2f
            val inner = 7.7f
            val half = (Math.PI / teeth).toFloat() * 0.52f
            for (i in 0 until teeth) {
                val a = (2 * Math.PI * i / teeth).toFloat() - (Math.PI / 2).toFloat()
                val pts = floatArrayOf(
                    a - half * 1.15f, inner, a - half * 0.7f, outer, a + half * 0.7f, outer, a + half * 1.15f, inner,
                )
                for (j in 0 until 4) {
                    val ang = pts[j * 2]
                    val r = pts[j * 2 + 1]
                    val px = 12f + r * cos(ang)
                    val py = 12f + r * sin(ang)
                    if (i == 0 && j == 0) moveTo(px, py) else lineTo(px, py)
                }
            }
            close()
            addCircle(12f, 12f, 3.6f, Path.Direction.CCW)
        }
    }
}
