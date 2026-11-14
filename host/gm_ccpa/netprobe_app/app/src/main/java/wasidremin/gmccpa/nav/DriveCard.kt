package wasidremin.gmccpa.nav

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.text.TextPaint
import wasidremin.gmccpa.ProbeLog
import wasidremin.gmccpa.av.CarNight
import wasidremin.gmccpa.telemetry.DriveLive
import wasidremin.gmccpa.telemetry.DriveOutlook
import wasidremin.gmccpa.telemetry.LivePower
import wasidremin.gmccpa.telemetry.RollingEfficiency
import wasidremin.gmccpa.telemetry.TripStore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * The drive in progress on the driver display ([ClusterLayout.Slot.DRIVE]): how far a kWh is taking
 * the car right now, and how the last ten miles went.
 *
 * Third design (owner, 2026-11-14, after two drives with a ring and a bar per mile: the ring moved
 * once every few seconds and "doesn't feel connected to the driving experience", and bars say less
 * than a line). `host/gm_ccpa/docs/14_LESSONS_LEARNED.md` §13.6 has the reasoning and the sources.
 *
 * - **The figure** is mi/kWh (km/kWh in metric) at this moment, from [LivePower], eased every frame
 *   so it follows the pedal. Standing still it is the last mile instead, and says so.
 * - **The line** is the last ten miles, smoothed ([RollingEfficiency.trace]). A new drive starts with
 *   the end of the drive before in grey, and a ring on the line marks where this one began. A dashed
 *   line is the usual (this month, else lifetime). The dot at its right end is now.
 * - **The power line** along the bottom runs right while the car uses power and left, in green, while
 *   it regenerates, with the kW beside it.
 *
 * Free on the map it opens when it is safe and useful to read: while the car stands ([STOPPED_MS]),
 * with no route (no turn card then), and for [OPEN_MS] when the last mile flips between better and
 * worse than usual, at most every [FLIP_EVERY_MS]; otherwise it folds to a pill. Docked in the
 * bottom-right panel ([ClusterLayout.docked]) it is always open, in the gauges' look, and carries
 * the battery on arrival. Free, it carries that figure on a tab when this app's turn card, which
 * otherwise has it, is not showing.
 *
 * Everything is worked out on the car. The log lines carry numbers only.
 */
object DriveCard {
    enum class Mode { HIDDEN, CHIP, OPEN }
    enum class Tone { PLAIN, GOOD, WARN }

    /** A point of the line as drawn: [x] miles (km) from now, 0 at now; [y] in the card's figure. */
    data class Pt(val x: Float, val y: Float)

    /** Everything the card shows that changes with distance, in the figure's own unit (mi/kWh or km/kWh). */
    data class Face(
        /** The last mile (km); null until the first one is done. */
        val last: Double?,
        /** The last ten, or the drive so far when it is shorter ([recentIsSoFar]). */
        val recent: Double?,
        val recentIsSoFar: Boolean,
        val month: Double?,
        val monthLabel: String,
        val lifetime: Double?,
        /** The end of the drive before, oldest first; empty when there was none or this drive fills the span. */
        val past: List<Pt>,
        /** This drive, oldest first; empty for its first few hundred metres. */
        val line: List<Pt>,
        /** Where this drive began on the line, when the drive before is showing. */
        val startX: Float?,
        val metric: Boolean,
    ) {
        /** What the dashed line is: this month, or lifetime before the month has a drive. */
        val usual: Double? get() = month ?: lifetime
        /** The stretch compared with the usual: the last mile, or the drive so far before it has one. */
        val compared: Double? get() = last ?: recent
        val tone: Tone get() = tone(compared, usual)
        val unitWord: String get() = if (metric) "km" else "mi"
        val rate: String get() = if (metric) "km/kWh" else "mi/kWh"
    }

    /** The battery on arrival, drawn with the turn card or with this card ([arrivalHere]). */
    data class Arrival(val pct: Int, val low: Boolean, val short: Boolean)

    /** The line covers this many miles (km). */
    const val SPAN = 10.0
    /** Folded, the pill's small line covers this many. */
    const val PILL_SPAN = 3.0
    const val OPEN_MS = 8_000L
    /** Standing this long opens the card: a red light or traffic, when it can be read. */
    const val STOPPED_MS = 3_000L
    const val STOPPED_MPS = 0.5
    /** A flip of the trend opens the card at most this often. */
    const val FLIP_EVERY_MS = 180_000L
    /** The last mile is "better" or "worse" than usual beyond this share of it. */
    const val TREND_BAND = 0.05
    /** The arrival estimate is logged again when it moves this many points. */
    const val ARRIVAL_STEP = 3
    /** A route that reports none for this long has ended; a reroute passes through "no route" for a moment. */
    const val ROUTE_GONE_MS = 15_000L
    /** The power line is full at this much: the Equinox EV's front motor is 157 kW. */
    const val POWER_FULL_W = 150_000.0
    const val REGEN_FULL_W = 80_000.0
    /** The power line and the figure ease toward a new reading over about this long. */
    const val POWER_EASE_MS = 350f
    const val FIGURE_EASE_MS = 1600f

    /** Bench only (`--es drive_face good|worse|first|low|short`): the Settings sample, and the arrival sample. */
    @Volatile var benchFace: String? = null

    /** The battery on arrival now, or null with no route; read by [TurnCard]. */
    @Volatile var arrival: Arrival? = null
        private set

    // ---------------------------------------------------------------------------------------------
    // Pure decisions
    // ---------------------------------------------------------------------------------------------

    fun tone(now: Double?, usual: Double?): Tone = when {
        now == null || usual == null || usual <= 0.0 -> Tone.PLAIN
        now >= usual * (1 + TREND_BAND) -> Tone.GOOD
        now <= usual * (1 - TREND_BAND) -> Tone.WARN
        else -> Tone.PLAIN
    }

    /** Pure: what the free card is at this moment. */
    fun mode(hasFace: Boolean, routeActive: Boolean, stoppedMs: Long, sinceFlipMs: Long): Mode = when {
        !hasFace -> Mode.HIDDEN
        !routeActive || stoppedMs >= STOPPED_MS || sinceFlipMs < OPEN_MS -> Mode.OPEN
        else -> Mode.CHIP
    }

    /** Pure: the trend went from better to worse or back, since the last time it was either. */
    fun flipped(lastSignificant: Tone?, tone: Tone): Boolean =
        tone != Tone.PLAIN && lastSignificant != null && lastSignificant != Tone.PLAIN && tone != lastSignificant

    /**
     * Pure: the card for this drive's [marks], the [tail] the drives before left, and the log's
     * averages (Wh/km). Null with no drive under way.
     */
    fun face(marks: List<RollingEfficiency.Mark>, tail: List<RollingEfficiency.Mark>, monthWhPerKm: Double?,
             lifetimeWhPerKm: Double?, monthLabel: String, metric: Boolean): Face? {
        if (marks.isEmpty()) return null
        val unit = if (metric) 1000.0 else wasidremin.gmccpa.telemetry.TripStats.M_PER_MILE
        val last = RollingEfficiency.span(marks, unit)?.takeIf { it.distM >= unit * 0.95 }
        val ten = RollingEfficiency.span(marks, unit * 10)?.takeIf { it.distM >= unit * 0.5 }
        fun shown(whPerKm: Double?) = whPerKm?.let { RollingEfficiency.perKwh(it, metric) }
        val trace = RollingEfficiency.trace(marks, tail, unit, SPAN)
        fun pts(l: List<RollingEfficiency.Point>) = l.map { Pt(it.x.toFloat(), RollingEfficiency.perKwh(it.whPerKm, metric).toFloat()) }
        return Face(
            last = shown(last?.whPerKm),
            recent = shown(ten?.whPerKm),
            recentIsSoFar = ten != null && ten.distM < unit * 9.5,
            month = shown(monthWhPerKm),
            monthLabel = monthLabel,
            lifetime = shown(lifetimeWhPerKm),
            past = pts(trace.past),
            line = pts(trace.now),
            startX = trace.startX?.toFloat(),
            metric = metric,
        )
    }

    internal fun figure(v: Double?): String = v?.let {
        if (it >= RollingEfficiency.CAP) ">9.9" else "%.1f".format(Locale.US, it)
    } ?: "—"

    /** Pure: the figure for this moment, or null standing still or before [LivePower] has settled. */
    fun nowFigure(powerW: Double?, speedMps: Double, metric: Boolean): Double? {
        val rate = LivePower.whPerKm(powerW ?: return null, speedMps) ?: return null
        return RollingEfficiency.perKwh(rate, metric)
    }

    /** Pure: the small word over the figure, which says what the figure is. */
    internal fun figureLabel(f: Face, live: Boolean): String = when {
        live -> "NOW"
        f.last != null -> "LAST ${f.unitWord.uppercase(Locale.US)}"
        f.recent != null -> "SO FAR"
        else -> "THIS DRIVE"
    }

    /** Pure: how the last mile compares with the usual; empty with nothing to compare. */
    internal fun vsText(f: Face): String {
        val now = f.compared ?: return ""
        val usual = f.usual ?: return ""
        val pct = ((now / usual - 1) * 100).roundToInt()
        return when (f.tone) {
            Tone.GOOD -> "▲ $pct% vs usual"
            Tone.WARN -> "▼ ${-pct}% vs usual"
            Tone.PLAIN -> "≈ usual"
        }
    }

    /**
     * Pure: where the power line ends, -1 (full regeneration) to 1 (full power). The square root gives
     * the 5 to 30 kW of ordinary driving most of the line; on a straight scale they would be a sliver.
     */
    fun powerFraction(watts: Double): Float {
        val full = if (watts >= 0) POWER_FULL_W else REGEN_FULL_W
        return (sign(watts) * sqrt((abs(watts) / full).coerceAtMost(1.0))).toFloat()
    }

    internal fun kwText(watts: Double): String {
        val kw = (watts / 1000.0).roundToInt()
        return if (kw < 0) "−${-kw} kW" else "$kw kW"
    }

    /** Pure: the line's bottom and top in the figure's unit, with room around what it has to show. */
    fun range(f: Face): Pair<Float, Float> {
        val seen = (f.past + f.line).map { it.y } + listOfNotNull(f.usual?.toFloat())
        val lo = (seen.minOrNull() ?: 2f) * 0.88f
        val hi = (seen.maxOrNull() ?: 4f) * 1.06f
        val least = if (f.metric) 1.6f else 1f
        return if (hi - lo >= least) lo to hi else ((lo + hi - least) / 2f) to ((lo + hi + least) / 2f)
    }

    /** A made-up line for the Settings sample: [from]..[to] miles from now, bending by [lift] at its end. */
    private fun samplePts(from: Float, to: Float, lift: Float): List<Pt> {
        val n = ((to - from) / 0.1f).roundToInt().coerceAtLeast(1)
        return List(n + 1) { i ->
            val x = from + (to - from) * i / n
            val along = i / n.toFloat()
            val y = 3.1 + 0.55 * Math.sin(x * 0.9 + 0.6) + 0.3 * Math.sin(x * 2.3 + 1.4) + lift * along * along
            Pt(x, y.toFloat())
        }
    }

    /** The Settings sample, and the bench's other faces. */
    internal fun sampleFace(name: String?): Face = when (name) {
        "worse" -> Face(2.6, 3.1, false, 3.2, "Nov", 3.0, samplePts(-10f, -6f, 0f), samplePts(-6f, -0.1f, -0.7f), -6f, false)
        "first" -> Face(null, null, true, 3.2, "Nov", 3.0, samplePts(-10f, 0f, 0f), emptyList(), 0f, false)
        else -> Face(3.6, 3.2, false, 3.2, "Nov", 3.1, samplePts(-10f, -6f, 0f), samplePts(-6f, -0.1f, 0.5f), -6f, false)
    }

    internal fun sampleArrival(name: String?): Arrival = when (name) {
        "low" -> Arrival(7, low = true, short = false)
        "short" -> Arrival(0, low = true, short = true)
        else -> Arrival(62, low = false, short = false)
    }

    /**
     * Pure: whether this card draws the battery on arrival. The turn card has it while it is this
     * app's and this card is free; docked, this card has it, and it has it when the turn card is
     * Apple's or off, which would otherwise leave the figure nowhere.
     */
    fun arrivalHere(docked: Boolean, turn: ClusterContent.Turn): Boolean = docked || turn != ClusterContent.Turn.OURS

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
        val f = face(live.marks, live.tail, month, h?.let { DriveOutlook.average(it.drives) }, label, live.metric)
        if (f == null) { loggedUnits = 0; return null }
        // Once per whole mile (km) completed: the figures the card is showing, numbers only.
        val unitM = if (live.metric) 1000.0 else wasidremin.gmccpa.telemetry.TripStats.M_PER_MILE
        val units = (live.marks.last().distM / unitM).toInt()
        if (units > loggedUnits) {
            loggedUnits = units
            log.i("drive card: last ${f.unitWord} ${figure(f.last)} ${f.rate}, ${if (f.recentIsSoFar) "so far" else "10 ${f.unitWord}"} " +
                "${figure(f.recent)}, ${f.monthLabel} ${figure(f.month)}, all ${figure(f.lifetime)}, now " +
                (live.powerW?.let { kwText(it) } ?: "no power figure") + ", line ${f.past.size}+${f.line.size} points")
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

    class View(ctx: Context) : android.view.View(ctx) {
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
        private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()
        private val path = Path()
        private val area = Path()
        private var dash: DashPathEffect? = null
        private var dashFor = 0f

        /** The colours of one line: its low and high ends, the drive before, and the dot at now. */
        private class Ink(val lo: Int, val hi: Int, val past: Int, val head: Int, val ground: Int)

        private var face: Face? = null
        private var mode = Mode.HIDDEN
        private var preview = false
        private var night = false
        private var docked = false
        private var shownArrival: Arrival? = null
        private var stoppedSince = 0L
        private var lastSignificant: Tone? = null
        private var flipOpenedAt = -FLIP_EVERY_MS
        /** 0 the pill, 1 the open card; eased toward [mode]. */
        private var open = 1f
        private var lastFrame = 0L
        /** Milliseconds since the frame before, at most 100: what every easing here steps by. */
        private var frameMs = 0f

        // The car's reading, and what is drawn of it: eased every frame toward the reading.
        private var powerW: Double? = null
        private var speedMps = 0.0
        private var metric = false
        private var shownW = 0f
        private var figureW = 0f
        private var figureV = 0f
        /** This frame draws the Settings sample, not the car. */
        private var sampling = false
        private val shaders = LinkedHashMap<Long, LinearGradient>()

        /** A top-to-bottom fade from [c0] at [y0] to [c1] at [y1], kept while the same one is asked for. */
        private fun fade(y0: Float, y1: Float, c0: Int, c1: Int): LinearGradient {
            val key = (y0.toBits().toLong() * 31 + y1.toBits()) * 31 + c0.toLong() * 131 + c1
            shaders[key]?.let { return it }
            if (shaders.size >= 12) shaders.remove(shaders.keys.first())
            return LinearGradient(0f, y0, 0f, y1, c0, c1, Shader.TileMode.CLAMP).also { shaders[key] = it }
        }

        private val tick = object : Runnable {
            override fun run() {
                refresh()
                postDelayed(this, 500)
            }
        }

        private fun refresh() {
            val now = SystemClock.elapsedRealtime()
            val showPreview = DriverDisplay.previewUntil > now
            val f = if (ClusterLayout.drive) current(context) else { arrival = null; null }
            val live = DriveLive.fresh(System.currentTimeMillis())
            val speed = live?.speedMps
            stoppedSince = if (speed != null && speed < STOPPED_MPS) (if (stoppedSince == 0L) now else stoppedSince) else 0L
            if (f != null) {
                val t = f.tone
                if (flipped(lastSignificant, t) && now - flipOpenedAt >= FLIP_EVERY_MS) flipOpenedAt = now
                if (t != Tone.PLAIN) lastSignificant = t
            } else lastSignificant = null
            val nextMode = mode(f != null, routeActive, if (stoppedSince == 0L) 0L else now - stoppedSince, now - flipOpenedAt)
            val nextDocked = ClusterLayout.docked == ClusterLayout.Slot.DRIVE
            val arr = arrival.takeIf { arrivalHere(nextDocked, ClusterContent.turn) }
            val nextW = live?.powerW?.takeIf { f != null }
            if (nextW != null && powerW == null) {
                // The first reading after none: start from it, not from a sweep up from zero.
                shownW = nextW.toFloat(); figureW = shownW; figureV = (speed ?: 0.0).toFloat()
            }
            val changed = nextW != powerW || (speed ?: 0.0) != speedMps
            powerW = nextW; speedMps = speed ?: 0.0; metric = live?.metric ?: false
            val wasHidden = mode == Mode.HIDDEN && !preview
            if (nextMode != mode || f != face || showPreview != preview || night != CarNight.night || nextDocked != docked ||
                arr != shownArrival || changed) {
                if (wasHidden) open = if (nextMode == Mode.CHIP) 0f else 1f
                mode = nextMode; face = f; preview = showPreview; night = CarNight.night; docked = nextDocked; shownArrival = arr
                invalidate()
            }
        }

        override fun onAttachedToWindow() { super.onAttachedToWindow(); post(tick) }
        override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

        private fun toneColor(tone: Tone, pal: ClusterLayout.Palette, bright: Boolean): Int = when (tone) {
            Tone.PLAIN -> pal.dim
            Tone.GOOD -> if (bright) ClusterLayout.GREEN_NIGHT else ClusterLayout.GREEN
            Tone.WARN -> if (bright) ClusterLayout.AMBER_NIGHT else ClusterLayout.AMBER
        }

        private fun withAlpha(color: Int, alpha: Float): Int =
            (color and 0x00FFFFFF) or (((color ushr 24) * alpha.coerceIn(0f, 1f)).toInt() shl 24)

        /** Ease what is drawn toward the car's reading; true while it is still moving. */
        private fun ease(sample: Boolean): Boolean {
            val now = SystemClock.uptimeMillis()
            val dt = (now - lastFrame).coerceIn(0, 100).toFloat()
            lastFrame = now
            frameMs = dt
            if (sample) { shownW = 18_000f; figureW = shownW; figureV = 24.6f; return false }
            val target = (powerW ?: 0.0).toFloat()
            shownW += (target - shownW) * (1 - exp(-dt / POWER_EASE_MS))
            figureW += (target - figureW) * (1 - exp(-dt / FIGURE_EASE_MS))
            figureV += (speedMps.toFloat() - figureV) * (1 - exp(-dt / FIGURE_EASE_MS))
            return abs(target - shownW) > 120f || abs(target - figureW) > 120f || abs(speedMps.toFloat() - figureV) > 0.05f
        }

        /** The Settings sample's figure for now: where its own line ends. */
        private fun sampleNow(f: Face): Double = f.last ?: f.usual ?: 3.2

        /** The figure and the word over it, for this frame. */
        private fun reading(f: Face, sample: Boolean): Pair<String, String> {
            val live = if (sample) sampleNow(f) else if (powerW == null) null else nowFigure(figureW.toDouble(), figureV.toDouble(), f.metric)
            return figure(live ?: f.last ?: f.recent) to figureLabel(f, live != null)
        }

        override fun onDraw(canvas: Canvas) {
            if (width == 0 || height == 0) return
            // Parked in Settings with no drive under way: a sample, so the place can be judged.
            val sample = mode == Mode.HIDDEN && preview && ClusterLayout.drive
            if (mode == Mode.HIDDEN && !sample) return
            val f = (if (sample) sampleFace(benchFace) else face) ?: return
            sampling = sample
            val moving = ease(sample)
            val b = ClusterLayout.box(context, width, height, ClusterLayout.Slot.DRIVE)
            val arr = if (sample && arrivalHere(docked, ClusterContent.turn)) sampleArrival(benchFace) else shownArrival
            if (docked) drawDocked(canvas, f, b, sample, arr) else drawFree(canvas, f, b, sample, arr)
            if (moving) postInvalidateOnAnimation()
        }

        // -----------------------------------------------------------------------------------------
        // Free on the map: the open card, folding to a pill
        // -----------------------------------------------------------------------------------------

        private fun drawFree(canvas: Canvas, f: Face, b: ClusterLayout.Box, sample: Boolean, arr: Arrival?) {
            val pal = ClusterLayout.palette(night)
            val h = b.height
            val target = if (mode == Mode.CHIP || (sample && MediaCard.benchFolded)) 0f else 1f
            if (open != target) {
                val step = minOf(frameMs, 50f) / 380f
                open = if (open < target) minOf(target, open + step) else maxOf(target, open - step)
                postInvalidateOnAnimation()
            }
            val t = open * open * (3 - 2 * open)

            // Folded, the card is a pill hugging its side of the display.
            val chip = h * 0.56f
            val chipW = chip * 2.95f
            val rightSide = b.left + b.width / 2f > width / 2f
            val cw = chipW + (b.width - chipW) * t
            val ch = chip + (h - chip) * t
            val left = if (rightSide) b.left + b.width - cw else b.left
            val top = b.top
            val radius = chip / 2f + (h * 0.18f - chip / 2f) * t
            fill.color = pal.card
            r.set(left, top, left + cw, top + ch)
            canvas.drawRoundRect(r, radius, radius, fill)
            val ink = Ink(pal.accent, if (night) ClusterLayout.GREEN_NIGHT else ClusterLayout.GREEN, pal.dim,
                if (night) ClusterLayout.GREEN_NIGHT else ClusterLayout.GREEN, pal.card or 0xFF000000.toInt())
            val (figureText, label) = reading(f, sample)

            if (t < 0.5f) {
                val a = 1f - 2f * t
                bold.color = withAlpha(pal.text, a); bold.textSize = chip * 0.39f
                canvas.drawText(figureText, left + chip * 0.30f, top + chip * 0.55f, bold)
                plain.color = withAlpha(pal.dim, a); plain.textSize = chip * 0.115f
                canvas.drawText(f.rate, left + chip * 0.32f, top + chip * 0.75f, plain)
                val lx = left + chip * 0.30f + maxOf(bold.measureText(figureText), chip * 0.72f) + chip * 0.16f
                drawLine(canvas, f, lx, top + chip * 0.22f, left + chipW - chip * 0.40f - lx, chip * 0.42f, pal, ink, a, mini = true)
                drawPower(canvas, left + chip * 0.43f, top + chip * 0.875f, chipW - chip * 0.86f, chip * 0.035f, pal, a, label = false)
            } else {
                val a = (t - 0.5f) * 2f
                val x0 = left + h * 0.16f
                plain.color = withAlpha(pal.dim, a); plain.textSize = h * 0.082f; plain.letterSpacing = 0.12f
                canvas.drawText(label, x0, top + h * 0.235f, plain)
                plain.letterSpacing = 0f
                bold.color = withAlpha(pal.text, a); bold.textSize = h * 0.31f
                canvas.drawText(figureText, x0 - h * 0.01f, top + h * 0.535f, bold)
                plain.textSize = h * 0.09f
                canvas.drawText(f.rate, x0 + h * 0.01f, top + h * 0.665f, plain)
                val vs = vsText(f)
                if (vs.isNotEmpty()) {
                    bold.color = withAlpha(toneColor(f.tone, pal, night), a); bold.textSize = h * 0.088f
                    canvas.drawText(vs, x0 + h * 0.01f, top + h * 0.815f, bold)
                }
                val gx = left + h * 0.89f
                drawLine(canvas, f, gx, top + h * 0.155f, left + cw - h * 0.17f - gx, h * 0.60f, pal, ink, a, mini = false)
                drawPower(canvas, x0, top + h * 0.918f, cw - h * 0.32f, h * 0.018f, pal, a, label = true, textSize = h * 0.082f)
            }
            if (arr != null) {
                val tabTop = if (top + ch + h * 0.36f <= height) top + ch + h * 0.06f else top - h * 0.36f
                val aw = CardParts.arrival(null, arr, 0f, 0f, b.width, h, pal, onPanel = false, night = night)
                CardParts.arrival(canvas, arr, if (rightSide) left + cw - aw else left, tabTop, b.width, h, pal, onPanel = false, night = night)
            }
        }

        // -----------------------------------------------------------------------------------------
        // Docked: on the bottom-right panel, in the gauges' look
        // -----------------------------------------------------------------------------------------

        private fun drawDocked(canvas: Canvas, f: Face, b: ClusterLayout.Box, sample: Boolean, arr: Arrival?) {
            CardParts.dockPanel(canvas, b, width.toFloat(), height.toFloat())
            val pal = ClusterLayout.GAUGE
            val h = b.height
            val x0 = b.left + h * 0.14f
            val y0 = b.top
            val ink = Ink(ClusterLayout.GAUGE_BAR, ClusterLayout.GAUGE_BAR_BRIGHT, pal.dim, pal.accent, pal.card)
            val (figureText, label) = reading(f, sample)
            plain.color = ClusterLayout.GAUGE_UNIT; plain.textSize = h * 0.085f; plain.letterSpacing = 0.12f
            canvas.drawText(label, x0, y0 + h * 0.17f, plain)
            plain.letterSpacing = 0f
            bold.color = pal.text; bold.textSize = h * 0.40f
            canvas.drawText(figureText, x0 - h * 0.01f, y0 + h * 0.56f, bold)
            plain.textSize = h * 0.105f
            canvas.drawText(f.rate, x0 + h * 0.01f, y0 + h * 0.72f, plain)
            val gx = x0 + maxOf(bold.measureText(figureText), h * 0.72f) + h * 0.16f
            val right = b.left + b.width - h * 0.14f
            drawLine(canvas, f, gx, y0 + h * 0.10f, right - gx, h * 0.62f, pal, ink, 1f, mini = false)
            drawPower(canvas, x0, y0 + h * 0.90f, right - x0, h * 0.022f, pal, 1f, label = true, labelColor = ClusterLayout.GAUGE_UNIT,
                textSize = h * 0.095f)
            if (arr != null) CardParts.arrival(canvas, arr, b.left + h * 0.04f, y0 + h * 1.03f, b.width, h, pal, onPanel = true, night = true)
        }

        // -----------------------------------------------------------------------------------------
        // The line and the power line
        // -----------------------------------------------------------------------------------------

        /** [pts] as a curve through the midpoints of its neighbours, so no corner shows at a point. */
        private fun curve(out: Path, pts: List<Pt>, px: (Float) -> Float, py: (Float) -> Float) {
            out.rewind()
            if (pts.isEmpty()) return
            out.moveTo(px(pts[0].x), py(pts[0].y))
            for (i in 1 until pts.size) {
                val ax = px(pts[i - 1].x); val ay = py(pts[i - 1].y)
                val bx = px(pts[i].x); val by = py(pts[i].y)
                out.quadTo(ax, ay, (ax + bx) / 2f, (ay + by) / 2f)
            }
            out.lineTo(px(pts.last().x), py(pts.last().y))
        }

        private fun under(out: Path, line: Path, x0: Float, x1: Float, base: Float) {
            out.set(line)
            out.lineTo(x1, base); out.lineTo(x0, base); out.close()
        }

        /**
         * The line in the box at [x], [y]: the drive before, this drive, the usual, where this drive
         * began and the dot at now. [mini] is the pill's: the last [PILL_SPAN] only, and no words.
         */
        private fun drawLine(canvas: Canvas, f: Face, x: Float, y: Float, w: Float, h: Float, pal: ClusterLayout.Palette,
                             ink: Ink, alpha: Float, mini: Boolean) {
            if (w <= 0f || h <= 0f) return
            val span = (if (mini) PILL_SPAN else SPAN).toFloat()
            val past = f.past.filter { it.x >= -span }
            val line = f.line.filter { it.x >= -span }
            val (lo, hi) = range(f)
            val px = { v: Float -> x + w * (1f + v / span) }
            val py = { v: Float -> y + h * (1f - ((v - lo) / (hi - lo)).coerceIn(0f, 1f)) }
            val base = y + h
            val weight = h * (if (mini) 0.07f else 0.034f)

            if (past.isNotEmpty()) {
                curve(path, past, px, py)
                under(area, path, px(past.first().x), px(past.last().x), base)
                fill.shader = fade(y, base, withAlpha(ink.past, 0.16f * alpha), withAlpha(ink.past, 0f))
                canvas.drawPath(area, fill)
                fill.shader = null
                stroke.color = withAlpha(ink.past, 0.6f * alpha); stroke.strokeWidth = weight * 0.72f
                canvas.drawPath(path, stroke)
            }
            f.usual?.takeIf { !mini }?.let { u ->
                val uy = py(u.toFloat())
                if (dashFor != h) { dash = DashPathEffect(floatArrayOf(h * 0.045f, h * 0.05f), 0f); dashFor = h }
                stroke.color = withAlpha(pal.text, 0.42f * alpha); stroke.strokeWidth = h * 0.012f; stroke.pathEffect = dash
                canvas.drawLine(x, uy, x + w, uy, stroke)
                stroke.pathEffect = null
                plain.color = withAlpha(pal.dim, alpha); plain.textSize = h * 0.135f
                // Under the line at the left, clear of a curve that starts above it; over it otherwise.
                val firstY = (past.firstOrNull() ?: line.firstOrNull())?.let { py(it.y) } ?: y
                val ty = if (firstY < uy) uy + plain.textSize * 1.25f else uy - plain.textSize * 0.5f
                canvas.drawText("usual ${figure(u)}", x + h * 0.02f, ty.coerceIn(y + plain.textSize, base), plain)
            }
            // Now: the live figure, or with none the line's own end.
            val live = if (sampling) sampleNow(f) else if (powerW == null) null else nowFigure(figureW.toDouble(), figureV.toDouble(), f.metric)
            val endPt = line.lastOrNull()
            val headY = py((live?.toFloat() ?: endPt?.y ?: past.lastOrNull()?.y ?: ((lo + hi) / 2f)))
            val headX = x + w
            if (line.isNotEmpty()) {
                curve(path, line, px, py)
                val lx = px(line.last().x)
                val ly = py(line.last().y)
                path.cubicTo((lx + headX) / 2f, ly, (lx + headX) / 2f, headY, headX, headY)
                under(area, path, px(line.first().x), headX, base)
                fill.shader = fade(y, base, withAlpha(ink.hi, 0.30f * alpha), withAlpha(ink.lo, 0f))
                canvas.drawPath(area, fill)
                fill.shader = null
                stroke.shader = fade(base, y, withAlpha(ink.lo, alpha), withAlpha(ink.hi, alpha))
                stroke.color = withAlpha(0xFFFFFFFF.toInt(), alpha); stroke.strokeWidth = weight
                canvas.drawPath(path, stroke)
                stroke.shader = null
            }
            // Where this drive began: a hairline the height of the box and a ring on the line.
            f.startX?.takeIf { it >= -span }?.let { sx ->
                val mx = px(sx)
                val my = line.firstOrNull()?.let { py(it.y) } ?: past.lastOrNull()?.let { py(it.y) } ?: headY
                stroke.color = withAlpha(pal.text, 0.34f * alpha); stroke.strokeWidth = h * 0.010f
                canvas.drawLine(mx, y, mx, base, stroke)
                val ring = h * (if (mini) 0.075f else 0.042f)
                fill.color = withAlpha(ink.ground, alpha)
                canvas.drawCircle(mx, my, ring, fill)
                stroke.color = withAlpha(pal.text, alpha); stroke.strokeWidth = ring * 0.42f
                canvas.drawCircle(mx, my, ring, stroke)
                if (!mini) {
                    plain.color = withAlpha(pal.dim, alpha); plain.textSize = h * 0.135f
                    val word = "this drive"
                    val tx = if (mx + h * 0.06f + plain.measureText(word) <= x + w) mx + h * 0.06f else mx - h * 0.06f - plain.measureText(word)
                    canvas.drawText(word, tx, y + plain.textSize, plain)
                }
            }
            val dot = h * (if (mini) 0.09f else 0.05f)
            fill.color = withAlpha(ink.head, 0.24f * alpha)
            canvas.drawCircle(headX, headY, dot * 2f, fill)
            fill.color = withAlpha(ink.head, alpha)
            canvas.drawCircle(headX, headY, dot, fill)
            stroke.color = withAlpha(ink.ground, alpha); stroke.strokeWidth = dot * 0.36f
            canvas.drawCircle(headX, headY, dot, stroke)
        }

        /**
         * The power line from [x] across [w] at [y]: a track, a tick at zero three tenths along, and
         * the power from the tick, right in the text colour or left in green, with the kW over its
         * right end when [label]. Before [LivePower] has a figure it is the track and tick alone.
         */
        private fun drawPower(canvas: Canvas, x: Float, y: Float, w: Float, weight: Float, pal: ClusterLayout.Palette, alpha: Float,
                              label: Boolean, labelColor: Int = pal.dim, textSize: Float = 0f) {
            if (w <= 0f) return
            val zero = x + w * 0.30f
            stroke.strokeWidth = weight
            stroke.color = withAlpha(pal.track, alpha)
            canvas.drawLine(x, y, x + w, y, stroke)
            val watts = if (powerW != null || sampling) shownW.toDouble() else null
            if (watts != null) {
                val fr = powerFraction(watts)
                val end = if (fr >= 0) zero + (x + w - zero) * fr else zero + (zero - x) * fr
                stroke.color = withAlpha(if (fr >= 0) pal.text else ClusterLayout.GREEN_NIGHT, alpha)
                canvas.drawLine(zero, y, end, y, stroke)
            }
            stroke.color = withAlpha(pal.dim, alpha); stroke.strokeWidth = weight * 0.5f
            canvas.drawLine(zero, y - weight * 1.7f, zero, y + weight * 1.7f, stroke)
            if (label && watts != null) {
                plain.color = withAlpha(labelColor, alpha); plain.textSize = textSize
                val text = kwText(watts)
                canvas.drawText(text, x + w - plain.measureText(text), y - weight * 2.4f, plain)
            }
        }
    }
}
