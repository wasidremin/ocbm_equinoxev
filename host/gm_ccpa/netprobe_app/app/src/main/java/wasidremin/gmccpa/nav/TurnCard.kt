package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The turn card drawn over CarPlay's map on the driver display, from the route guidance the phone
 * already sends ([ClusterNav.route]). CarPlay's cluster map stream carries the map only, and GM's own
 * turn display needs an app navigating, which takes GM's map page away (2026-11-09 evening: the page
 * came back only while no turns ran). Drawing the card ourselves keeps GM out of it.
 *
 * Nothing here is logged: road names and arrival times say where someone is going.
 */
object TurnCard {
    /** What the card shows. Pure, so it is tested without a screen. */
    data class Content(
        val icon: String,
        val distance: String?,
        val road: String?,
        val arrival: String?,
    )

    // Apple DistanceUnit (metadata crate types.rs), as NavDistance reads it.
    private const val APPLE_KM = 0
    private const val APPLE_MILES = 1
    private const val APPLE_METERS = 2
    private const val APPLE_YARDS = 3
    private const val APPLE_FEET = 4
    private val IMPERIAL_COUNTRIES = setOf("US", "GB", "MM", "LR")

    /**
     * The card for [s], or null when there is no turn to show. [clock] formats the arrival time in the
     * car's 12/24-hour setting.
     */
    fun content(s: RouteGuidance.Snapshot, nowMs: Long, clock: (Instant, ZoneId) -> String, locale: Locale = Locale.getDefault()): Content? {
        if (!s.showable) return null
        val cur = s.current ?: return null
        val distance = distance(s.distanceToManeuverText, s.distanceToManeuverUnits, s.distanceToManeuverM, locale)
            ?: distance(cur.distanceText, cur.distanceUnits, null, locale)
        val arrival = s.timeRemainingSec?.takeIf { it >= 0 }?.let { left ->
            val zone: ZoneId = s.destTimeZoneOffsetMin?.let { ZoneOffset.ofTotalSeconds(it * 60) } ?: ZoneId.systemDefault()
            "${clock(Instant.ofEpochMilli(nowMs).plusSeconds(left), zone)} · ${duration(left)}"
        }
        return Content(icon(cur.type, cur.drivingSide), distance, cur.cue?.trim()?.takeIf { it.isNotEmpty() }, arrival)
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

    fun duration(sec: Long): String {
        val min = (sec + 30) / 60
        return if (min < 60) "$min min" else "${min / 60} h ${String.format(Locale.US, "%02d", min % 60)} min"
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

    /** Shown during the directions-area preview when no route is up, so the card's place can be judged parked. */
    val SAMPLE = Content("nav_turn_right", "500 ft", "Turn card", "arrival time · 12 min")

    /**
     * Draws the card in the top-left corner of the directions area. Polls [ClusterNav.route] twice a
     * second and redraws only on a new snapshot or a new minute.
     */
    class View(ctx: Context, private val area: () -> android.graphics.Rect) : android.view.View(ctx) {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 16, 18, 22) }
        private val big = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; isFakeBoldText = true }
        private val mid = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(0xB8, 0xC2, 0xCC) }
        private val timeFormat = android.text.format.DateFormat.getTimeFormat(ctx)
        private var shown: Content? = null
        private var lastSnapshot: RouteGuidance.Snapshot? = null
        private var lastMinute = -1L

        private val tick = object : Runnable {
            override fun run() {
                val s = ClusterNav.route.snapshot
                val minute = System.currentTimeMillis() / 60_000
                val preview = DriverDisplay.previewUntil > SystemClock.elapsedRealtime()
                if (s !== lastSnapshot || minute != lastMinute || preview || shown === SAMPLE) {
                    lastSnapshot = s
                    lastMinute = minute
                    val c = if (DriverDisplay.turnCard) content(s, System.currentTimeMillis(), ::clock) else null
                    val next = c ?: if (preview && DriverDisplay.turnCard) SAMPLE else null
                    if (next != shown) { shown = next; invalidate() }
                }
                postDelayed(this, 500)
            }
        }

        private fun clock(at: Instant, zone: ZoneId): String {
            timeFormat.timeZone = java.util.TimeZone.getTimeZone(zone)
            return timeFormat.format(java.util.Date(at.toEpochMilli()))
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        override fun onDraw(canvas: Canvas) {
            val c = shown ?: return
            if (width == 0 || height == 0) return
            val a = area()
            val h = height * 0.25f
            val pad = h * 0.12f
            val w = minOf(a.width().toFloat() - pad * 2, h * 2.9f)
            if (w <= h) return
            val r = RectF(a.left + pad, a.top + pad, a.left + pad + w, a.top + pad + h)
            canvas.drawRoundRect(r, h * 0.14f, h * 0.14f, bg)
            val iconSize = (h * 0.62f).toInt()
            val iconLeft = (r.left + pad).toInt()
            val iconTop = (r.top + (h - iconSize) / 2f).toInt()
            val id = resources.getIdentifier(c.icon, "drawable", context.packageName)
            if (id != 0) runCatching {
                context.getDrawable(id)?.let { d -> d.setBounds(iconLeft, iconTop, iconLeft + iconSize, iconTop + iconSize); d.draw(canvas) }
            }
            val textLeft = iconLeft + iconSize + pad
            val textWidth = r.right - pad - textLeft
            big.textSize = h * 0.30f
            mid.textSize = h * 0.17f
            small.textSize = h * 0.14f
            var y = r.top + pad + big.textSize * 0.92f
            canvas.drawText(c.distance ?: "", textLeft, y, big)
            c.road?.let {
                y += mid.textSize * 1.35f
                canvas.drawText(TextUtils.ellipsize(it, mid, textWidth, TextUtils.TruncateAt.END).toString(), textLeft, y, mid)
            }
            c.arrival?.let {
                canvas.drawText(TextUtils.ellipsize(it, small, textWidth, TextUtils.TruncateAt.END).toString(), textLeft, r.bottom - pad, small)
            }
        }
    }
}
