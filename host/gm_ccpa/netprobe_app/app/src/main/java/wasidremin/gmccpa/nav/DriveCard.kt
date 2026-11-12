package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.text.TextPaint
import android.text.TextUtils
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.CarNight
import wasidremin.gmccpa.telemetry.DriveLive
import wasidremin.gmccpa.telemetry.DriveOutlook
import wasidremin.gmccpa.telemetry.RollingEfficiency
import wasidremin.gmccpa.telemetry.TripStore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The drive in progress on the driver display ([ClusterLayout.Slot.DRIVE]): how much energy the last
 * mile took against the last ten, this month and the car's lifetime ([RollingEfficiency], the drive
 * log). Redesigned 2026-11-12 after the owner's first drive with it: the arrival figure barely moves
 * when the estimate is right, so it went to the turn card ([arrival], drawn by [TurnCard]) and this
 * card shows something that changes every tenth of a mile.
 *
 * The ring is a gauge from 0 to [scale] in mi/kWh (km/kWh in metric, so fuller is always better).
 * Its arc is the last mile, green when that beats the last ten by more than [TREND_BAND], amber when
 * it is that much worse. Folded it carries one tick, the last ten; open it carries three: the last
 * ten, this month and lifetime, with a bar per mile underneath, scaled to the bars' own range.
 *
 * It opens when it is safe and useful to read: while the car stands ([STOPPED_MS]), with no route
 * (no turn card then), and for [OPEN_MS] when the trend flips between better and worse, at most
 * every [FLIP_EVERY_MS]. Otherwise, during a route, it is folded.
 *
 * Everything is worked out on the car. The log lines carry numbers only.
 */
object DriveCard {
    enum class Mode { HIDDEN, CHIP, OPEN }
    enum class Tone { PLAIN, GOOD, WARN }

    /** Everything the card shows at one moment, in the figure's own unit (mi/kWh or km/kWh). */
    data class Face(
        /** The last mile (km); null until the first one is done. */
        val now: Double?,
        /** The last ten, or the drive so far when it is shorter ([tenIsSoFar]). */
        val ten: Double?,
        val tenIsSoFar: Boolean,
        val month: Double?,
        val monthLabel: String,
        val lifetime: Double?,
        /** One figure per whole mile (km) of the last ten, oldest first. */
        val bars: List<Double>,
        val metric: Boolean,
    ) {
        val tone: Tone get() = tone(now, ten)
        val scale: Double get() = if (metric) 8.0 else 5.0
        val unitWord: String get() = if (metric) "km" else "mi"
        val rate: String get() = if (metric) "km/kWh" else "mi/kWh"
    }

    /** The battery on arrival, drawn at the foot of the turn card while a route runs. */
    data class Arrival(val pct: Int, val low: Boolean, val short: Boolean)

    const val OPEN_MS = 8_000L
    /** Standing this long opens the card: a red light or traffic, when it can be read. */
    const val STOPPED_MS = 3_000L
    const val STOPPED_MPS = 0.5
    /** A flip of the trend opens the card at most this often. */
    const val FLIP_EVERY_MS = 180_000L
    /** The last mile is "better" or "worse" than the last ten beyond this share of it. */
    const val TREND_BAND = 0.05
    /** The arrival estimate is logged again when it moves this many points. */
    const val ARRIVAL_STEP = 3
    /** A route that reports none for this long has ended; a reroute passes through "no route" for a moment. */
    const val ROUTE_GONE_MS = 15_000L

    /** Bench only (`--es drive_face good|worse|first|low|short`): the Settings sample, and the turn card's arrival sample. */
    @Volatile var benchFace: String? = null

    /** The battery on arrival now, or null with no route; read by [TurnCard]. */
    @Volatile var arrival: Arrival? = null
        private set

    // ---------------------------------------------------------------------------------------------
    // Pure decisions
    // ---------------------------------------------------------------------------------------------

    fun tone(now: Double?, ten: Double?): Tone = when {
        now == null || ten == null || ten <= 0.0 -> Tone.PLAIN
        now >= ten * (1 + TREND_BAND) -> Tone.GOOD
        now <= ten * (1 - TREND_BAND) -> Tone.WARN
        else -> Tone.PLAIN
    }

    /** Pure: what the card is at this moment. */
    fun mode(hasFace: Boolean, routeActive: Boolean, stoppedMs: Long, sinceFlipMs: Long): Mode = when {
        !hasFace -> Mode.HIDDEN
        !routeActive || stoppedMs >= STOPPED_MS || sinceFlipMs < OPEN_MS -> Mode.OPEN
        else -> Mode.CHIP
    }

    /** Pure: the trend went from better to worse or back, since the last time it was either. */
    fun flipped(lastSignificant: Tone?, tone: Tone): Boolean =
        tone != Tone.PLAIN && lastSignificant != null && lastSignificant != Tone.PLAIN && tone != lastSignificant

    /** Pure: the card for [marks] and the log's averages (Wh/km). Null before the drive has gone anywhere. */
    fun face(marks: List<RollingEfficiency.Mark>, monthWhPerKm: Double?, lifetimeWhPerKm: Double?,
             monthLabel: String, metric: Boolean): Face? {
        if (marks.isEmpty()) return null
        val unit = if (metric) 1000.0 else wasidremin.gmccpa.telemetry.TripStats.M_PER_MILE
        val last = RollingEfficiency.span(marks, unit)?.takeIf { it.distM >= unit * 0.95 }
        val ten = RollingEfficiency.span(marks, unit * 10)?.takeIf { it.distM >= unit * 0.5 }
        fun shown(whPerKm: Double?) = whPerKm?.let { RollingEfficiency.perKwh(it, metric) }
        return Face(
            now = shown(last?.whPerKm),
            ten = shown(ten?.whPerKm),
            tenIsSoFar = ten != null && ten.distM < unit * 9.5,
            month = shown(monthWhPerKm),
            monthLabel = monthLabel,
            lifetime = shown(lifetimeWhPerKm),
            bars = RollingEfficiency.perUnit(marks, unit, 10).map { RollingEfficiency.perKwh(it, metric) },
            metric = metric,
        )
    }

    internal fun figure(v: Double?): String = v?.let {
        if (it >= RollingEfficiency.CAP) ">9.9" else "%.1f".format(Locale.US, it)
    } ?: "—"

    /** Folded, beside the ring: how the last mile compares with the last ten. */
    internal fun chipText(f: Face): String {
        val now = f.now ?: return "first ${f.unitWord}…"
        val ten = f.ten ?: return f.rate
        val pct = ((now / ten - 1) * 100).roundToInt()
        return when (f.tone) {
            Tone.GOOD -> "▲$pct% vs 10 ${f.unitWord}"
            Tone.WARN -> "▼${-pct}% vs 10 ${f.unitWord}"
            Tone.PLAIN -> "≈ 10 ${f.unitWord} avg"
        }
    }

    internal fun title(f: Face): String =
        f.now?.let { "${figure(it)} ${f.rate} · last ${f.unitWord}" } ?: "Measuring the first ${f.unitWord}"

    /** Open, the second line: the three marks on the ring, in its colours. */
    internal fun legend(f: Face): List<Pair<String, Double?>> = listOf(
        (if (f.tenIsSoFar) "so far" else "10 ${f.unitWord}") to f.ten,
        f.monthLabel to f.month,
        "all" to f.lifetime,
    )

    /** The Settings sample, and the bench's other faces. */
    internal fun sampleFace(name: String?): Face = when (name) {
        "worse" -> Face(2.6, 3.1, false, 3.2, "Nov", 3.0, listOf(3.3, 3.2, 3.4, 3.0, 2.9, 3.1, 3.3, 2.8, 2.7, 2.6), false)
        "first" -> Face(null, 2.4, true, 3.2, "Nov", 3.0, emptyList(), false)
        else -> Face(3.4, 3.0, false, 3.2, "Nov", 3.1, listOf(2.8, 2.9, 3.1, 2.7, 2.9, 3.0, 3.2, 3.1, 3.3, 3.4), false)
    }

    internal fun sampleArrival(name: String?): Arrival = when (name) {
        "low" -> Arrival(7, low = true, short = false)
        "short" -> Arrival(0, low = true, short = true)
        else -> Arrival(62, low = false, short = false)
    }

    // ---------------------------------------------------------------------------------------------
    // The car's readings and the drive log
    // ---------------------------------------------------------------------------------------------

    private val log by lazy { ProbeLog.sub("cluster") }
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "drive-history").apply { isDaemon = true } } }
    @Volatile private var history: DriveOutlook.History? = null
    @Volatile private var historyStamp: String? = null
    private var historyCheckedAt = 0L

    /** The drive log, reloaded off the main thread when the file has grown. */
    private fun history(ctx: Context): DriveOutlook.History? {
        val now = SystemClock.elapsedRealtime()
        if (historyCheckedAt == 0L || now - historyCheckedAt >= HISTORY_CHECK_MS) {
            historyCheckedAt = now
            val dir = TripStore.dirUnder(ctx.applicationContext.getExternalFilesDir(null) ?: ctx.applicationContext.filesDir)
            io.execute {
                runCatching {
                    val f = File(dir, "trips.ndjson")
                    val stamp = "${f.length()}:${f.lastModified()}"
                    if (stamp != historyStamp) {
                        val h = DriveOutlook.History(TripStore(dir).load())
                        history = h
                        historyStamp = stamp
                        log.i("drive card: drive log read, ${h.samples.size} drives usable, ${h.index.repeated.size} repeated routes")
                    }
                }.onFailure { log.w("drive card: drive log unreadable (${it.javaClass.simpleName})") }
            }
        }
        return history
    }

    private var firstEstimate: Int? = null
    private var loggedEstimate: Int? = null
    private var lastAheadM = 0.0
    private var lastBatteryPct = 0
    private var noRouteSince = 0L
    private var loggedUnits = 0

    /** Whether a route is running, as the arrival sees it; read by the card's fold rule. */
    @Volatile var routeActive = false
        private set

    /** What the card should show now, or null; updates [arrival] too. Main thread; writes the log lines. */
    fun current(ctx: Context): Face? {
        val nowMs = System.currentTimeMillis()
        val live = DriveLive.fresh(nowMs)
        val route = ClusterNav.route.snapshot
        routeActive = route.routeActive && route.state != RouteGuidance.ARRIVED
        val ahead = route.distanceRemainingM?.toDouble()?.takeIf { routeActive && it > 0.0 }
        val h = history(ctx)
        val a = live?.let {
            DriveOutlook.outlook(DriveOutlook.Input(it.drive, it.batteryWh, it.capWh, it.tempC, it.speedMps, ahead,
                route.timeRemainingSec?.toDouble()), h).arrival
        }
        val cap = live?.capWh
        val battery = live?.batteryWh
        if (a != null && ahead != null && cap != null && battery != null) {
            noRouteSince = 0L
            val pct = a.pct.roundToInt()
            arrival = Arrival(pct.coerceIn(0, 100), a.low, pct <= 0)
            lastAheadM = ahead
            lastBatteryPct = (battery / cap * 100).roundToInt()
            if (firstEstimate == null) firstEstimate = pct
            if (loggedEstimate == null || abs(pct - loggedEstimate!!) >= ARRIVAL_STEP) {
                loggedEstimate = pct
                log.i("drive card: arrival $pct% (battery $lastBatteryPct%, ${ahead.roundToInt()} m ahead at " +
                    "${"%.1f".format(Locale.US, a.speedMps)} m/s, ${a.whPerKm.roundToInt()} Wh/km)")
            }
        } else {
            arrival = null
            if (battery != null && cap != null) lastBatteryPct = (battery / cap * 100).roundToInt()
            // A reroute passes through "no route" for a moment; only a lasting one ends the route.
            if (noRouteSince == 0L) noRouteSince = nowMs
            if (nowMs - noRouteSince >= ROUTE_GONE_MS) routeOver()
        }
        if (live == null) { loggedUnits = 0; return null }
        val tz = java.util.TimeZone.getDefault().getOffset(nowMs) / 60_000
        val month = h?.let { DriveOutlook.average(it.drives, DriveOutlook.monthStart(nowMs, tz)) }
        val label = java.text.SimpleDateFormat("MMM", Locale.getDefault()).format(java.util.Date(nowMs))
        val f = face(live.marks, month, h?.let { DriveOutlook.average(it.drives) }, label, live.metric)
        if (f == null) { loggedUnits = 0; return null }
        // Once per whole mile (km) completed: the figures the card is showing, numbers only.
        val unitM = if (live.metric) 1000.0 else wasidremin.gmccpa.telemetry.TripStats.M_PER_MILE
        val units = (live.marks.last().distM / unitM).toInt()
        if (units > loggedUnits) {
            loggedUnits = units
            log.i("drive card: last ${f.unitWord} ${figure(f.now)} ${f.rate}, ${if (f.tenIsSoFar) "so far" else "10 ${f.unitWord}"} " +
                "${figure(f.ten)}, ${f.monthLabel} ${figure(f.month)}, all ${figure(f.lifetime)}")
        }
        return f
    }

    /** The route ended: say how the estimate did, so its error can be read off the car's log. */
    private fun routeOver() {
        val first = firstEstimate ?: return
        log.i("drive card: route over ${lastAheadM.roundToInt()} m from its end, battery $lastBatteryPct% — " +
            "last estimate $loggedEstimate%, first estimate $first%")
        firstEstimate = null
        loggedEstimate = null
    }

    private const val HISTORY_CHECK_MS = 60_000L

    /** The month and lifetime ticks; the last ten's tick is the card's text colour. */
    const val MONTH_TICK = 0xFFBF5AF2.toInt()
    const val LIFETIME_TICK = 0xFF8E8E93.toInt()

    class View(ctx: Context) : android.view.View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
        private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        private var face: Face? = null
        private var mode = Mode.HIDDEN
        private var preview = false
        private var night = false
        private var stoppedSince = 0L
        private var lastSignificant: Tone? = null
        private var flipOpenedAt = -FLIP_EVERY_MS
        /** 0 the ring alone, 1 the open card; eased toward [mode]. */
        private var open = 1f
        private var lastFrame = 0L

        private val tick = object : Runnable {
            override fun run() {
                refresh()
                postDelayed(this, 1000)
            }
        }

        private fun refresh() {
            val now = SystemClock.elapsedRealtime()
            val showPreview = DriverDisplay.previewUntil > now
            val f = if (ClusterLayout.drive) current(context) else { arrival = null; null }
            val speed = DriveLive.fresh(System.currentTimeMillis())?.speedMps
            stoppedSince = if (speed != null && speed < STOPPED_MPS) (if (stoppedSince == 0L) now else stoppedSince) else 0L
            if (f != null) {
                val t = f.tone
                if (flipped(lastSignificant, t) && now - flipOpenedAt >= FLIP_EVERY_MS) flipOpenedAt = now
                if (t != Tone.PLAIN) lastSignificant = t
            } else lastSignificant = null
            val nextMode = mode(f != null, routeActive, if (stoppedSince == 0L) 0L else now - stoppedSince, now - flipOpenedAt)
            val wasHidden = mode == Mode.HIDDEN && !preview
            if (nextMode != mode || f != face || showPreview != preview || night != CarNight.night) {
                if (wasHidden) open = if (nextMode == Mode.CHIP) 0f else 1f
                mode = nextMode; face = f; preview = showPreview; night = CarNight.night
                invalidate()
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        /** [text] on one line in [width]: shrunk by up to a fifth, then cut with an ellipsis. */
        private fun fitted(text: String, paint: TextPaint, width: Float): String {
            val w = maxOf(0f, width)
            val need = paint.measureText(text)
            if (need > w && need > 0f) paint.textSize *= maxOf(0.8f, w / need)
            return TextUtils.ellipsize(text, paint, w, TextUtils.TruncateAt.END).toString()
        }

        private fun toneColor(tone: Tone, pal: ClusterLayout.Palette): Int = when (tone) {
            Tone.PLAIN -> pal.accent
            Tone.GOOD -> if (night) ClusterLayout.GREEN_NIGHT else ClusterLayout.GREEN
            Tone.WARN -> if (night) ClusterLayout.AMBER_NIGHT else ClusterLayout.AMBER
        }

        /** A mark across the ring at [v] on the gauge. */
        private fun tickAt(canvas: Canvas, cx: Float, cy: Float, rr: Float, v: Double?, scale: Double, color: Int, alpha: Int) {
            v ?: return
            val a = Math.toRadians(-90.0 + 360.0 * (v / scale).coerceIn(0.0, 1.0))
            val half = ring.strokeWidth * 0.95f
            val cos = Math.cos(a).toFloat(); val sin = Math.sin(a).toFloat()
            tickPaint.color = color; tickPaint.alpha = alpha
            tickPaint.strokeWidth = ring.strokeWidth * 0.32f
            canvas.drawLine(cx + (rr - half) * cos, cy + (rr - half) * sin, cx + (rr + half) * cos, cy + (rr + half) * sin, tickPaint)
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            // Parked in Settings with no drive under way: a sample, so the place can be judged.
            val sample = mode == Mode.HIDDEN && preview && ClusterLayout.drive
            if (mode == Mode.HIDDEN && !sample) return
            val f = (if (sample) sampleFace(benchFace) else face) ?: return
            val b = ClusterLayout.box(context, width, height, ClusterLayout.Slot.DRIVE)
            val pal = ClusterLayout.palette(night)
            val h = b.height

            val now = SystemClock.uptimeMillis()
            val target = if (mode == Mode.CHIP || (sample && MediaCard.benchFolded)) 0f else 1f
            if (open != target) {
                val step = ((now - lastFrame).coerceIn(0, 50)) / 380f
                open = if (open < target) minOf(target, open + step) else maxOf(target, open - step)
                postInvalidateOnAnimation()
            }
            lastFrame = now
            val t = open * open * (3 - 2 * open)

            // Folded, the card is the ring with a few words beside it, hugging its side of the display.
            val chip = h * 0.74f
            val rightSide = b.left + b.width / 2f > width / 2f
            val pad = h * 0.13f
            plain.textSize = h * 0.17f
            val folded = fitted(chipText(f), plain, b.width - chip - pad * 1.4f)
            val chipW = chip + pad * 0.2f + plain.measureText(folded) + pad * 1.2f
            val cw = chipW + (b.width - chipW) * t
            val ch = chip + (h - chip) * t
            val left = if (rightSide) b.left + b.width - cw else b.left
            val radius = chip / 2f + (h * 0.18f - chip / 2f) * t
            fill.color = pal.card
            r.set(left, b.top, left + cw, b.top + ch)
            canvas.drawRoundRect(r, radius, radius, fill)

            // The ring: a gauge whose arc is the last mile, with the averages as ticks on its rim.
            val side = chip * 0.80f + (h * 0.66f - chip * 0.80f) * t
            val inset = (chip - chip * 0.80f) / 2f + (pad - (chip - chip * 0.80f) / 2f) * t
            val cx = left + inset + side / 2f
            val cy = b.top + ch / 2f
            ring.strokeWidth = side * 0.10f
            val rr = side / 2f - ring.strokeWidth
            r.set(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.color = pal.track
            canvas.drawOval(r, ring)
            f.now?.let { v ->
                ring.color = toneColor(f.tone, pal)
                canvas.drawArc(r, -90f, (360.0 * (v / f.scale).coerceIn(0.0, 1.0)).toFloat(), false, ring)
            }
            val tickAlpha = 255
            tickAt(canvas, cx, cy, rr, f.ten, f.scale, pal.text, tickAlpha)
            if (t >= 0.5f) {
                val a = (255 * ((t - 0.5f) * 2f)).toInt().coerceIn(0, 255)
                tickAt(canvas, cx, cy, rr, f.month, f.scale, MONTH_TICK, a)
                tickAt(canvas, cx, cy, rr, f.lifetime, f.scale, LIFETIME_TICK, a)
            }
            val label = figure(f.now)
            bold.color = pal.text; bold.alpha = 255; bold.textSize = side * 0.30f
            val inside = side - ring.strokeWidth * 4f
            val need = bold.measureText(label)
            if (need > inside) bold.textSize *= inside / need
            canvas.drawText(label, cx - bold.measureText(label) / 2f, cy + bold.textSize * 0.35f, bold)

            if (t < 0.5f) {
                plain.color = pal.text; plain.alpha = (255 * (1f - 2f * t)).toInt().coerceIn(0, 255)
                canvas.drawText(folded, left + chip + pad * 0.2f, b.top + chip / 2f + plain.textSize * 0.35f, plain)
                return
            }
            val alpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
            val textLeft = left + inset + side + pad * 0.85f
            val textWidth = left + cw - pad - textLeft
            bold.color = pal.text; bold.alpha = (255 * alpha).toInt(); bold.textSize = h * 0.19f
            val top = b.top + pad
            canvas.drawText(fitted(title(f), bold, textWidth), textLeft, top + h * 0.20f, bold)

            // The legend: a dot in each tick's colour, its label and its figure.
            plain.textSize = h * 0.145f
            val parts = legend(f)
            val dot = plain.textSize * 0.32f
            val gap = plain.textSize * 0.55f
            val texts = parts.map { (name, v) -> "$name ${figure(v)}" }
            val need2 = texts.sumOf { plain.measureText(it).toDouble() }.toFloat() + parts.size * (dot * 2 + dot) + gap * (parts.size - 1)
            if (need2 > textWidth) plain.textSize *= maxOf(0.75f, textWidth / need2)
            var x = textLeft
            val base = top + h * 0.42f
            val colors = listOf(pal.text, MONTH_TICK, LIFETIME_TICK)
            for ((i, s) in texts.withIndex()) {
                fill.color = colors[i]; fill.alpha = (255 * alpha).toInt()
                canvas.drawCircle(x + dot, base - plain.textSize * 0.33f, dot, fill)
                x += dot * 3
                plain.color = pal.dim; plain.alpha = (255 * alpha).toInt()
                canvas.drawText(s, x, base, plain)
                x += plain.measureText(s) + gap
            }

            // A bar per mile of the last ten. Scaled to their own range, not the ring's, so that a hill
            // or a motorway stretch shows as a shape: on 0..5 mi/kWh a mile at 2.8 and one at 3.4 differ
            // by a few pixels (emulator, 2026-11-12).
            val barsTop = top + h * 0.50f
            val barsBottom = b.top + ch - pad * 0.9f
            val slots = 10
            val seen = f.bars + listOfNotNull(f.ten)
            val lo = (seen.minOrNull() ?: 0.0) * 0.85
            val hi = maxOf((seen.maxOrNull() ?: f.scale) * 1.03, lo + 0.5)
            fun level(v: Double) = ((v - lo) / (hi - lo)).coerceIn(0.08, 1.0)
            val slotW = textWidth / slots
            val bw = slotW * 0.62f
            for (i in 0 until slots) {
                val v = f.bars.getOrNull(f.bars.size - slots + i)
                val x0 = textLeft + slotW * i + (slotW - bw) / 2f
                if (v == null) {
                    fill.color = pal.track; fill.alpha = (Color.alpha(pal.track) * alpha).toInt()
                    r.set(x0, barsBottom - h * 0.02f, x0 + bw, barsBottom)
                    canvas.drawRoundRect(r, bw / 3f, bw / 3f, fill)
                    continue
                }
                val hh = ((barsBottom - barsTop) * level(v)).toFloat()
                val newest = i == slots - 1
                fill.color = if (newest) toneColor(f.tone, pal) else pal.dim
                fill.alpha = ((if (newest) 255 else 150) * alpha).toInt()
                r.set(x0, barsBottom - hh, x0 + bw, barsBottom)
                canvas.drawRoundRect(r, bw / 3f, bw / 3f, fill)
            }
            // The last ten's level across the bars.
            f.ten?.let { v ->
                val y = (barsBottom - (barsBottom - barsTop) * level(v)).toFloat()
                tickPaint.color = pal.text; tickPaint.alpha = (200 * alpha).toInt(); tickPaint.strokeWidth = h * 0.012f
                canvas.drawLine(textLeft, y, textLeft + textWidth, y, tickPaint)
            }
        }
    }
}
