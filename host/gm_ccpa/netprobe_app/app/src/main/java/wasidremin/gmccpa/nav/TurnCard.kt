package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import wasidremin.gmccpa.av.CarNight
import java.util.Locale

/**
 * The turn card drawn over CarPlay's map on the driver display, from the route guidance the phone
 * already sends ([ClusterNav.route]). CarPlay's cluster map stream carries the map only, and GM's own
 * turn display needs an app navigating, which takes GM's map page away (2026-11-09 evening: the page
 * came back only while no turns ran). Drawing the card ourselves keeps GM out of it.
 *
 * A small pill while the turn is far off; inside [NEAR_M] it grows to a full card with a bar that
 * fills as the turn comes up, the turn after it when the two are close together, and the lanes when
 * Settings asks the phone for them. A highway exit number shows as a green sign. The arrival time is
 * not here: Apple's map draws its own arrival strip.
 *
 * Nothing here is logged: road names say where someone is going.
 */
object TurnCard {
    /** What the card shows. Pure, so it is tested without a screen. */
    data class Content(
        val icon: String,
        val distance: String?,
        val road: String?,
        /** The highway exit number alone ("273"), for the green sign. */
        val exit: String? = null,
        /** Inside [NEAR_M]: the full card. */
        val near: Boolean = false,
        /** How much of the last [NEAR_M] is covered, 0..1; null when the phone sent no metres. */
        val approach: Float? = null,
        val thenIcon: String? = null,
        val thenRoad: String? = null,
        val lanes: List<RouteGuidance.Lane>? = null,
    )

    // Apple DistanceUnit (metadata crate types.rs), as NavDistance reads it.
    private const val APPLE_KM = 0
    private const val APPLE_MILES = 1
    private const val APPLE_METERS = 2
    private const val APPLE_YARDS = 3
    private const val APPLE_FEET = 4
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    /** The card grows inside this many metres of the turn (half a mile). */
    const val NEAR_M = 800L
    /** The turn after shows when it follows within this many metres (a quarter mile). */
    const val THEN_M = 400L
    const val MAX_LANES = 8

    /** The card for [s], or null when there is no turn to show. [lanes] is the Settings switch. */
    fun content(s: RouteGuidance.Snapshot, lanes: Boolean = false, locale: Locale = Locale.getDefault()): Content? {
        if (!s.showable) return null
        val cur = s.current ?: return null
        val distance = distance(s.distanceToManeuverText, s.distanceToManeuverUnits, s.distanceToManeuverM, locale)
            ?: distance(cur.distanceText, cur.distanceUnits, null, locale)
        val left = s.distanceToManeuverM?.takeIf { it >= 0 } ?: meters(s.distanceToManeuverText, s.distanceToManeuverUnits)
        val near = left != null && left <= NEAR_M
        val exit = exitNumber(cur.exitInfo)
        val road = (cur.afterRoadName ?: cur.description)?.trim()?.takeIf {
            it.isNotEmpty() && !it.equals(exit, ignoreCase = true) && !it.equals("Exit $exit", ignoreCase = true)
        }
        // DistanceBetweenManeuver on the following entry is how far it lies past this turn.
        val then = s.next?.takeIf { near && (meters(it.distanceText, it.distanceUnits) ?: Long.MAX_VALUE) <= THEN_M }
        return Content(
            icon = icon(cur.type, cur.drivingSide),
            distance = distance,
            road = road,
            exit = exit,
            near = near,
            approach = left?.takeIf { near }?.let { 1f - it.toFloat() / NEAR_M },
            thenIcon = then?.let { icon(it.type, it.drivingSide) },
            thenRoad = then?.let { RouteGuidance.exitCue(it.exitInfo, it.afterRoadName ?: it.description) },
            lanes = s.lanes?.takeIf { lanes && near && it.size in 2..MAX_LANES },
        )
    }

    /** "273" from "273" or "Exit 273"; null when the phone named no exit. */
    fun exitNumber(exitInfo: String?): String? {
        val t = exitInfo?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return (if (t.startsWith("exit", ignoreCase = true)) t.substring(4).trim() else t).takeIf { it.isNotEmpty() }
    }

    /** The phone's display figure in metres ("0.3" + miles -> 483); null when it cannot be read. */
    fun meters(text: String?, units: Int?): Long? {
        val v = text?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it >= 0 } ?: return null
        val per = when (units) {
            APPLE_KM -> 1000.0; APPLE_MILES -> 1609.344; APPLE_METERS -> 1.0; APPLE_YARDS -> 0.9144; APPLE_FEET -> 0.3048
            else -> return null
        }
        return Math.round(v * per)
    }

    /** The phone's own figure and unit ("0.3" + miles -> "0.3 mi"); metres only when it sent no text. */
    fun distance(text: String?, units: Int?, meters: Long?, locale: Locale = Locale.getDefault()): String? {
        val t = text?.trim()?.takeIf { it.isNotEmpty() }
        val unit = when (units) {
            APPLE_KM -> "km"; APPLE_MILES -> "mi"; APPLE_METERS -> "m"; APPLE_YARDS -> "yd"; APPLE_FEET -> "ft"
            else -> null
        }
        if (t != null) return if (unit != null) "$t $unit" else t
        val m = meters?.takeIf { it >= 0 } ?: return null
        val imperial = when (units) {
            APPLE_MILES, APPLE_YARDS, APPLE_FEET -> true
            APPLE_KM, APPLE_METERS -> false
            else -> locale.country in IMPERIAL_COUNTRIES
        }
        return if (imperial) {
            if (m < 305) "${(m / 0.3048 / 50).toLong() * 50} ft" else String.format(Locale.US, "%.1f mi", m / 1609.344)
        } else {
            if (m < 1000) "${m / 10 * 10} m" else String.format(Locale.US, "%.1f km", m / 1000.0)
        }
    }

    /**
     * Apple ManeuverType (0..53) -> one of the glyphs in src/carapp/res (the same set the cluster
     * session uses, `ManeuverMap.icon`). [drivingSide] 1 is left-hand traffic.
     */
    fun icon(appleType: Int?, drivingSide: Int?): String {
        val left = drivingSide == 1
        val uTurn = if (left) "nav_uturn_right" else "nav_uturn_left"
        val roundabout = if (left) "nav_roundabout_cw" else "nav_roundabout_ccw"
        return when (appleType) {
            1, 20 -> "nav_turn_left"
            2, 21 -> "nav_turn_right"
            4, 18, 26 -> uTurn
            6, 7, 19, in 28..46 -> roundabout
            8 -> if (left) "nav_slight_left" else "nav_slight_right"
            9 -> if (left) "nav_slight_left" else "nav_slight_right"
            10, 12, 24, 25, 27 -> "nav_destination"
            13, 22, 49, 52 -> "nav_slight_left"
            14, 23, 50, 51, 53 -> "nav_slight_right"
            15, 16, 17 -> "nav_ferry"
            47 -> "nav_sharp_left"
            48 -> "nav_sharp_right"
            else -> "nav_straight"
        }
    }

    /** Shown during a Settings preview when no route is up, so the card's place can be judged parked. */
    fun sample(lanes: Boolean) = Content(
        "nav_turn_right", "500 ft", "Turn card", exit = "12", near = true, approach = 0.6f,
        thenIcon = "nav_turn_left", thenRoad = "the turn after",
        lanes = if (lanes) listOf(
            RouteGuidance.Lane(0, 0, listOf(-90)), RouteGuidance.Lane(1, 1, listOf(0)),
            RouteGuidance.Lane(2, 2, listOf(0, 90), 90), RouteGuidance.Lane(3, 2, listOf(90), 90),
        ) else null,
    )

    /**
     * Draws the card at its place ([ClusterLayout.Slot.TURN]). Polls [ClusterNav.route] twice a second
     * and redraws on a new snapshot, a preview, or a change of day and night.
     */
    class View(ctx: Context) : android.view.View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
        private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val r = RectF()
        private val path = Path()
        private var shown: Content? = null
        private var lastSnapshot: RouteGuidance.Snapshot? = null
        private var night = false
        /** 0 the pill, 1 the full card; eased toward [Content.near]. */
        private var grown = 0f
        private var lastFrame = 0L
        private var lanesLogged: List<RouteGuidance.Lane>? = null

        private val tick = object : Runnable {
            override fun run() {
                val s = ClusterNav.route.snapshot
                val preview = DriverDisplay.previewUntil > SystemClock.elapsedRealtime()
                if (s !== lastSnapshot || preview || shown != null || night != CarNight.night) {
                    lastSnapshot = s
                    val on = DriverDisplay.turnCard
                    val next = (if (on) content(s, ClusterLayout.lanes) else null)
                        ?: if (preview && on) sample(ClusterLayout.lanes) else null
                    if (next != shown || night != CarNight.night) {
                        if (shown == null) grown = if (next?.near == true) 1f else 0f
                        shown = next; night = CarNight.night; invalidate()
                    }
                    logLanes(s)
                }
                postDelayed(this, 500)
            }
        }

        /**
         * Numbers only, once per junction: the turn's type next to the angle its lanes highlight. One
         * drive settles which sign of a lane angle is right, which no document here records.
         */
        private fun logLanes(s: RouteGuidance.Snapshot) {
            val l = s.lanes ?: return
            if (l == lanesLogged) return
            lanesLogged = l
            wasidremin.gmccpa.ProbeLog.sub("cluster").i(
                "lanes: ${l.size} at maneuverType=${s.current?.type} status=${l.map { it.status }} highlight=${l.map { it.highlight }}")
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        private fun icon(canvas: Canvas, name: String, left: Float, top: Float, size: Float, tint: Int) {
            val id = resources.getIdentifier(name, "drawable", context.packageName)
            if (id == 0) return
            runCatching {
                context.getDrawable(id)?.mutate()?.let { d ->
                    d.setTint(tint)
                    d.setBounds(left.toInt(), top.toInt(), (left + size).toInt(), (top + size).toInt())
                    d.draw(canvas)
                }
            }
        }

        private fun ellipsized(text: String, paint: TextPaint, width: Float): String =
            TextUtils.ellipsize(text, paint, maxOf(0f, width), TextUtils.TruncateAt.END).toString()

        override fun onDraw(canvas: Canvas) {
            val c = shown ?: return
            if (width == 0 || height == 0) return
            if (ClusterLayout.dock) { drawDocked(canvas, c); return }
            val b = ClusterLayout.box(context, width, height, ClusterLayout.Slot.TURN)
            val pal = ClusterLayout.palette(night)
            val h = b.height

            // Ease between the pill and the full card.
            val now = SystemClock.uptimeMillis()
            val target = if (c.near) 1f else 0f
            if (grown != target) {
                val step = ((now - lastFrame).coerceIn(0, 50)) / 320f
                grown = if (grown < target) minOf(target, grown + step) else maxOf(target, grown - step)
                postInvalidateOnAnimation()
            }
            lastFrame = now
            val t = grown * grown * (3 - 2 * grown)

            val full = t > 0.5f
            val pad = h * 0.14f
            fill.color = pal.card
            if (full) {
                val ch = h * (0.52f + 0.48f * t)
                r.set(b.left, b.top, b.left + b.width, b.top + ch)
                canvas.drawRoundRect(r, h * 0.18f, h * 0.18f, fill)
                val iconSize = h * 0.56f
                icon(canvas, c.icon, r.left + pad, r.top + (ch - iconSize) / 2f - h * 0.02f, iconSize, pal.text)
                val textLeft = r.left + pad + iconSize + pad * 0.8f
                val textWidth = r.right - pad - textLeft
                bold.color = pal.text; bold.textSize = h * 0.34f
                var y = r.top + h * 0.44f
                canvas.drawText(c.distance ?: "", textLeft, y, bold)
                y += h * 0.27f
                var x = textLeft
                plain.textSize = h * 0.18f
                c.exit?.let { exit ->
                    bold.textSize = h * 0.15f; bold.color = Color.WHITE
                    val label = "EXIT $exit"
                    val w = bold.measureText(label) + h * 0.12f
                    fill.color = ClusterLayout.GREEN
                    r.set(x, y - h * 0.17f, x + w, y + h * 0.06f)
                    canvas.drawRoundRect(r, h * 0.05f, h * 0.05f, fill)
                    canvas.drawText(label, x + h * 0.06f, y, bold)
                    x += w + h * 0.07f
                }
                plain.color = pal.dim
                c.road?.let { canvas.drawText(ellipsized(it, plain, textLeft + textWidth - x), x, y, plain) }
                c.approach?.let { a ->
                    val top = b.top + ch - pad * 0.62f
                    fill.color = pal.track
                    r.set(textLeft, top, textLeft + textWidth, top + h * 0.035f)
                    canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, fill)
                    fill.color = pal.accent
                    r.right = textLeft + textWidth * a.coerceIn(0f, 1f)
                    canvas.drawRoundRect(r, h * 0.02f, h * 0.02f, fill)
                }
                var below = b.top + ch + h * 0.06f
                if (c.thenIcon != null) below = drawThen(canvas, c, b.left, below, b.width, h, pal)
                c.lanes?.let { drawLanes(canvas, it, b.left, below, b.width, h, pal) }
            } else {
                val ch = h * (0.52f + 0.48f * t)
                val iconSize = h * 0.34f
                bold.color = pal.text; bold.textSize = h * 0.24f
                plain.color = pal.dim; plain.textSize = h * 0.17f
                val dist = c.distance ?: ""
                val distW = bold.measureText(dist)
                val gap = h * 0.08f
                val label = c.exit?.let { "Exit $it" } ?: c.road
                val room = b.width - pad * 2 - iconSize - gap - distW - gap
                val road = label?.let { ellipsized(it, plain, room) } ?: ""
                val natural = pad * 2 + iconSize + gap + distW + (if (road.isEmpty()) 0f else gap + plain.measureText(road))
                val cw = natural + (b.width - natural) * t
                r.set(b.left, b.top, b.left + cw, b.top + ch)
                canvas.drawRoundRect(r, h * 0.18f, h * 0.18f, fill)
                icon(canvas, c.icon, r.left + pad, r.top + (ch - iconSize) / 2f, iconSize, pal.text)
                val base = r.top + ch / 2f + bold.textSize * 0.35f
                val x = r.left + pad + iconSize + gap
                canvas.drawText(dist, x, base, bold)
                if (road.isNotEmpty()) canvas.drawText(road, x + distW + gap, base, plain)
            }
        }

        /**
         * Docked: a panel running off the bottom-right corner with the gauges' slanted inner edge,
         * black with white figures as they are. The lanes, or else the turn after, sit inside it under the bar.
         */
        private fun drawDocked(canvas: Canvas, c: Content) {
            val b = ClusterLayout.box(context, width, height, ClusterLayout.Slot.TURN)
            val pal = ClusterLayout.GAUGE
            val h = b.height
            val x0 = b.left; val y0 = b.top
            val w = width.toFloat(); val bottom = height.toFloat()
            val rad = h * 0.14f
            path.rewind()
            path.moveTo(x0 + rad, y0)
            path.lineTo(w, y0); path.lineTo(w, bottom)
            path.lineTo(x0 - ClusterLayout.DOCK_SLANT * (bottom - y0), bottom)
            path.lineTo(x0 - ClusterLayout.DOCK_SLANT * rad, y0 + rad)
            path.quadTo(x0, y0, x0 + rad, y0)
            path.close()
            fill.color = pal.card
            canvas.drawPath(path, fill)

            val pad = h * 0.14f
            val iconSize = h * 0.50f
            icon(canvas, c.icon, x0 + pad, y0 + h * 0.10f, iconSize, pal.text)
            val textLeft = x0 + pad + iconSize + pad * 0.7f
            val textWidth = b.width - (textLeft - x0) - pad
            // The figure large and the unit small beside it, as the speed reads on the left.
            val d = c.distance ?: ""
            val cut = d.lastIndexOf(' ')
            val figure = if (cut > 0) d.substring(0, cut) else d
            bold.color = pal.text; bold.textSize = h * 0.40f
            var y = y0 + h * 0.47f
            canvas.drawText(figure, textLeft, y, bold)
            if (cut > 0) {
                plain.color = ClusterLayout.GAUGE_UNIT; plain.textSize = h * 0.18f
                canvas.drawText(d.substring(cut + 1), textLeft + bold.measureText(figure) + h * 0.06f, y, plain)
            }
            y += h * 0.27f
            var x = textLeft
            c.exit?.let { exit ->
                bold.textSize = h * 0.15f; bold.color = Color.WHITE
                val label = "EXIT $exit"
                val lw = bold.measureText(label) + h * 0.12f
                fill.color = ClusterLayout.GREEN
                r.set(x, y - h * 0.17f, x + lw, y + h * 0.06f)
                canvas.drawRoundRect(r, h * 0.05f, h * 0.05f, fill)
                canvas.drawText(label, x + h * 0.06f, y, bold)
                x += lw + h * 0.07f
            }
            plain.color = pal.dim; plain.textSize = h * 0.18f
            c.road?.let { canvas.drawText(ellipsized(it, plain, textLeft + textWidth - x), x, y, plain) }
            c.approach?.let { a ->
                val top = y0 + h * 0.86f
                fill.color = pal.track
                r.set(textLeft, top, textLeft + textWidth, top + h * 0.045f)
                canvas.drawRect(r, fill)
                fill.color = pal.accent
                r.right = textLeft + textWidth * a.coerceIn(0f, 1f)
                canvas.drawRect(r, fill)
            }
            // One row under the bar, inside the panel: the lanes when there are any, else the turn after.
            val lanes = c.lanes
            if (lanes != null) drawLanes(canvas, lanes, x0, y0 + h * 0.98f, b.width, h, pal)
            else if (c.thenIcon != null) drawThen(canvas, c, x0, y0 + h * 1.0f, b.width, h, pal)
        }

        /** "Then" and the turn after, on a tab under the card. Returns where the next thing starts. */
        private fun drawThen(canvas: Canvas, c: Content, left: Float, top: Float, maxWidth: Float, h: Float, pal: ClusterLayout.Palette): Float {
            val th = h * 0.30f
            val pad = h * 0.10f
            val iconSize = h * 0.20f
            plain.textSize = h * 0.15f
            val lead = "Then"
            val leadW = plain.measureText(lead)
            val road = c.thenRoad?.let { ellipsized(it, plain, maxWidth - pad * 3 - leadW - iconSize - pad) } ?: ""
            val w = pad + leadW + pad * 0.6f + iconSize + (if (road.isEmpty()) 0f else pad * 0.6f + plain.measureText(road)) + pad
            fill.color = pal.card
            r.set(left, top, left + w, top + th)
            canvas.drawRoundRect(r, th * 0.42f, th * 0.42f, fill)
            val base = top + th / 2f + plain.textSize * 0.35f
            plain.color = pal.dim
            canvas.drawText(lead, left + pad, base, plain)
            val ix = left + pad + leadW + pad * 0.6f
            icon(canvas, c.thenIcon ?: return top, ix, top + (th - iconSize) / 2f, iconSize, pal.text)
            plain.color = pal.text
            if (road.isNotEmpty()) canvas.drawText(road, ix + iconSize + pad * 0.6f, base, plain)
            return top + th + h * 0.06f
        }

        /**
         * One arrow group per lane, left to right. The direction the route takes is drawn in full
         * colour on the lanes to use; every other direction, and every lane not to take, is faint.
         * A positive angle is drawn to the right ([logLanes] is how the car confirms it).
         */
        private fun drawLanes(canvas: Canvas, lanes: List<RouteGuidance.Lane>, left: Float, top: Float, maxWidth: Float, h: Float, pal: ClusterLayout.Palette) {
            val sh = h * 0.40f
            val cell = minOf(h * 0.36f, maxWidth / lanes.size)
            fill.color = pal.card
            r.set(left, top, left + cell * lanes.size, top + sh)
            canvas.drawRoundRect(r, h * 0.10f, h * 0.10f, fill)
            stroke.strokeWidth = cell * 0.11f
            lanes.forEachIndexed { i, lane ->
                val cx = left + cell * (i + 0.5f)
                val bottom = top + sh * 0.84f
                val knee = top + sh * 0.52f
                val reach = sh * 0.30f
                val angles = lane.angles.ifEmpty { listOf(0) }
                // Faint first, so the direction to take is drawn over the others.
                for (pass in 0..1) for (a in angles) {
                    val take = lane.status != 0 && (lane.highlight == null || a == lane.highlight)
                    if (take != (pass == 1)) continue
                    stroke.color = if (take) pal.text else pal.faint
                    val rad = Math.toRadians(a.coerceIn(-150, 150).toDouble())
                    val ex = cx + (reach * Math.sin(rad)).toFloat()
                    val ey = knee - (reach * Math.cos(rad)).toFloat()
                    path.rewind()
                    path.moveTo(cx, bottom); path.lineTo(cx, knee); path.lineTo(ex, ey)
                    // The arrowhead: two barbs back from the tip.
                    val barb = cell * 0.17f
                    for (side in intArrayOf(-1, 1)) {
                        val br = rad + Math.PI + side * 0.6
                        path.moveTo(ex, ey)
                        path.lineTo(ex + (barb * Math.sin(br)).toFloat(), ey - (barb * Math.cos(br)).toFloat())
                    }
                    canvas.drawPath(path, stroke)
                }
            }
        }
    }
}
