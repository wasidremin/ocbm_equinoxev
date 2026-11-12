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
import wasidremin.gmccpa.telemetry.Trip
import wasidremin.gmccpa.telemetry.TripStats
import wasidremin.gmccpa.telemetry.TripStore
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The drive in progress on the driver display ([ClusterLayout.Slot.DRIVE]), from the car's own
 * drive log ([DriveOutlook]).
 *
 * With an Apple Maps route it shows the battery the car will arrive with. Without one it shows this
 * drive's efficiency against what the car normally does, and until there is enough history to say,
 * the distance, time and energy so far. Like [MediaCard] it opens for [OPEN_MS] when what it says
 * changes, then folds to a ring with a few words beside it. An arrival under
 * [DriveOutlook.LOW_ARRIVAL_PCT] keeps it open, in amber.
 *
 * Everything is worked out on the car. The log lines carry numbers only.
 */
object DriveCard {
    enum class Mode { HIDDEN, CHIP, OPEN }
    enum class Kind { ARRIVAL, PACE, TRIP }
    enum class Tone { PLAIN, GOOD, WARN }

    /** The line along the card's bottom edge. */
    sealed class Bar {
        /** The pack: what it holds now and what is left on arrival, each 0..1. */
        data class Battery(val now: Float, val arrival: Float) : Bar()
        /** Worst to best for drives like this one, 0..1: where this drive sits and where normal is. */
        data class Range(val at: Float, val normal: Float) : Bar()
    }

    /** Everything the card shows at one moment. */
    data class Face(
        val kind: Kind,
        /** Inside the ring: "62%", "+8%", "3.4". */
        val ring: String,
        /** How much of the ring is drawn, 0..1. */
        val ringShare: Float,
        val tone: Tone,
        val title: String,
        val detail: String,
        /** Beside the ring when the card is folded. */
        val chip: String,
        val bar: Bar?,
        /** Keeps the card open. */
        val warn: Boolean = false,
        /** The arrival percentage, for deciding whether the figure moved enough to open again. */
        val value: Int = 0,
    )

    const val OPEN_MS = 8_000L
    /** The card does not open more often than this, however the figure moves. */
    const val MIN_REOPEN_MS = 90_000L
    /** An arrival figure this many points from the one last shown open is news. */
    const val ARRIVAL_STEP = 3

    /** Bench only (`--es drive_face arrive|low|short|better|worse|usual|trip`): the Settings sample. */
    @Volatile var benchFace: String? = null

    /** Pure: what the card is at this moment. */
    fun mode(hasFace: Boolean, warn: Boolean, sinceOpenedMs: Long): Mode = when {
        !hasFace -> Mode.HIDDEN
        warn || sinceOpenedMs < OPEN_MS -> Mode.OPEN
        else -> Mode.CHIP
    }

    /** Pure: [now] says something [shown], the face the card last opened for, did not. */
    fun changed(shown: Face?, now: Face): Boolean = shown == null || shown.kind != now.kind || shown.warn != now.warn ||
        shown.tone != now.tone || (now.kind == Kind.ARRIVAL && abs(now.value - shown.value) >= ARRIVAL_STEP)

    // ---------------------------------------------------------------------------------------------
    // Words and figures
    // ---------------------------------------------------------------------------------------------

    internal fun distance(m: Double, metric: Boolean): String {
        val v = if (metric) m / 1000.0 else m / TripStats.M_PER_MILE
        val unit = if (metric) "km" else "mi"
        return if (v < 10) "${"%.1f".format(Locale.US, v)} $unit" else "${v.roundToInt()} $unit"
    }

    internal fun energy(wh: Double): String = "${"%.1f".format(Locale.US, wh / 1000.0)} kWh"

    internal fun efficiency(whPerKm: Double, metric: Boolean): String =
        if (metric) "${whPerKm.roundToInt()}" else "%.1f".format(Locale.US, TripStats.milesPerKwh(whPerKm))

    internal fun efficiencyUnit(metric: Boolean) = if (metric) "Wh/km" else "mi/kWh"

    internal fun duration(seconds: Double): String {
        val min = (seconds / 60).roundToInt()
        return if (min < 60) "$min min" else "${min / 60} h ${"%02d".format(Locale.US, min % 60)}"
    }

    private fun usual(basis: DriveOutlook.Basis) = when (basis) {
        DriveOutlook.Basis.ROUTE -> "usual"
        DriveOutlook.Basis.SIMILAR -> "similar"
        else -> "average"
    }

    /**
     * Pure: the card for [o]. Null when there is nothing to say: no route and no drive under way.
     * [aheadM] is the phone's distance remaining, null with no route.
     */
    fun face(o: DriveOutlook.Outlook, drive: Trip?, batteryWh: Double?, capWh: Double?, aheadM: Double?, metric: Boolean): Face? {
        val a = o.arrival
        if (a != null && aheadM != null && batteryWh != null && capWh != null) {
            val left = "${distance(aheadM, metric)} left"
            val now = (batteryWh / capWh).toFloat().coerceIn(0f, 1f)
            if (a.pct <= 0.0) {
                return Face(Kind.ARRIVAL, "0%", 0f, Tone.WARN, "Charge on the way",
                    "${energy(a.energyWh - batteryWh)} short · $left", "charge needed", Bar.Battery(now, 0f), warn = true)
            }
            val pct = a.pct.roundToInt().coerceIn(0, 100)
            return Face(Kind.ARRIVAL, "$pct%", pct / 100f, if (a.low) Tone.WARN else Tone.PLAIN, "Arrive with $pct%",
                "${energy(a.energyWh)} · $left", "on arrival", Bar.Battery(now, pct / 100f), warn = a.low, value = pct)
        }
        val d = drive ?: return null
        val rate = o.whPerKm
        val v = o.verdict
        if (v != null && rate != null) {
            val n = (v.fraction * 100).roundToInt()
            val than = usual(o.baseline.basis)
            val span = o.baseline.worst - o.baseline.best
            val k = o.kNow
            val bar = if (k != null && span > 0.05) Bar.Range(
                ((o.baseline.worst - k) / span).toFloat().coerceIn(0f, 1f),
                ((o.baseline.worst - o.baseline.k) / span).toFloat().coerceIn(0f, 1f)) else null
            val title = "${efficiency(rate, metric)} ${efficiencyUnit(metric)}"
            return when (v.standing) {
                TripStats.Standing.BETTER -> Face(Kind.PACE, "+$n%", 1f, Tone.GOOD, title, "$n% better than $than", "vs $than", bar)
                TripStats.Standing.WORSE -> Face(Kind.PACE, "−$n%", 1f, Tone.WARN, title, "$n% worse than $than", "vs $than", bar)
                TripStats.Standing.TYPICAL -> Face(Kind.PACE, "≈", 1f, Tone.PLAIN, title, "about your $than", "your $than", bar)
            }
        }
        val used = energy(d.netWh.coerceAtLeast(0.0))
        return Face(Kind.TRIP, rate?.let { efficiency(it, metric) } ?: "—", 1f, Tone.PLAIN,
            "${distance(d.distM, metric)} · ${duration(d.durationS)}",
            if (rate != null) "$used · ${efficiency(rate, metric)} ${efficiencyUnit(metric)}" else used,
            efficiencyUnit(metric), null)
    }

    /** The Settings sample, and the bench's other faces. */
    internal fun sampleFace(name: String?): Face = when (name) {
        "low" -> Face(Kind.ARRIVAL, "7%", 0.07f, Tone.WARN, "Arrive with 7%", "24.6 kWh · 84 mi left", "on arrival", Bar.Battery(0.36f, 0.07f), warn = true, value = 7)
        "short" -> Face(Kind.ARRIVAL, "0%", 0f, Tone.WARN, "Charge on the way", "3.1 kWh short · 62 mi left", "charge needed", Bar.Battery(0.18f, 0f), warn = true)
        "better" -> Face(Kind.PACE, "+8%", 1f, Tone.GOOD, "3.4 mi/kWh", "8% better than usual", "vs usual", Bar.Range(0.7f, 0.5f))
        "worse" -> Face(Kind.PACE, "−12%", 1f, Tone.WARN, "2.9 mi/kWh", "12% worse than similar", "vs similar", Bar.Range(0.2f, 0.5f))
        "usual" -> Face(Kind.PACE, "≈", 1f, Tone.PLAIN, "3.2 mi/kWh", "about your average", "your average", Bar.Range(0.5f, 0.5f))
        "trip" -> Face(Kind.TRIP, "3.4", 1f, Tone.PLAIN, "12.4 mi · 24 min", "3.6 kWh · 3.4 mi/kWh", "mi/kWh", null)
        else -> Face(Kind.ARRIVAL, "62%", 0.62f, Tone.PLAIN, "Arrive with 62%", "9.4 kWh · 31 mi left", "on arrival", Bar.Battery(0.71f, 0.62f), value = 62)
    }

    // ---------------------------------------------------------------------------------------------
    // The car's readings and the drive log
    // ---------------------------------------------------------------------------------------------

    private val log by lazy { ProbeLog.sub("cluster") }
    private val io by lazy { Executors.newSingleThreadExecutor { r -> Thread(r, "drive-history").apply { isDaemon = true } } }
    @Volatile private var history: DriveOutlook.History? = null
    @Volatile private var historyStamp: String? = null
    private var historyCheckedAt = 0L

    /** The drive log as the estimate reads it, reloaded off the main thread when the file has grown. */
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
    private var loggedStanding: TripStats.Standing? = null

    /** What the card should show now, or null. Main thread; it also writes the estimate's log lines. */
    fun current(ctx: Context): Face? {
        val live = DriveLive.fresh(System.currentTimeMillis())
        val route = ClusterNav.route.snapshot
        val ahead = route.distanceRemainingM?.toDouble()
            ?.takeIf { route.routeActive && route.state != RouteGuidance.ARRIVED && it > 0.0 }
        if (live == null) { routeOver(); return null }
        val o = DriveOutlook.outlook(DriveOutlook.Input(live.drive, live.batteryWh, live.capWh, live.tempC,
            live.speedMps, ahead, route.timeRemainingSec?.toDouble()), history(ctx))
        val a = o.arrival
        val cap = live.capWh
        val battery = live.batteryWh
        if (a != null && ahead != null && cap != null && battery != null) {
            val pct = a.pct.roundToInt()
            lastAheadM = ahead
            lastBatteryPct = (battery / cap * 100).roundToInt()
            if (firstEstimate == null) firstEstimate = pct
            if (loggedEstimate == null || abs(pct - loggedEstimate!!) >= ARRIVAL_STEP) {
                loggedEstimate = pct
                log.i("drive card: arrival $pct% (battery $lastBatteryPct%, ${ahead.roundToInt()} m ahead at " +
                    "${"%.1f".format(Locale.US, a.speedMps)} m/s, ${a.whPerKm.roundToInt()} Wh/km, " +
                    "basis ${o.baseline.basis} runs=${o.baseline.runs} k=${"%.2f".format(Locale.US, o.baseline.k)}" +
                    " now=${o.kNow?.let { "%.2f".format(Locale.US, it) } ?: "-"})")
            }
        } else {
            if (battery != null && cap != null) lastBatteryPct = (battery / cap * 100).roundToInt()
            routeOver()
        }
        val standing = o.verdict?.standing
        if (standing != null && standing != loggedStanding) {
            loggedStanding = standing
            log.i("drive card: pace $standing by ${(o.verdict!!.fraction * 100).roundToInt()}% (basis ${o.baseline.basis} " +
                "runs=${o.baseline.runs} k=${"%.2f".format(Locale.US, o.baseline.k)} now=${"%.2f".format(Locale.US, o.kNow ?: 0.0)})")
        }
        if (live.drive == null) loggedStanding = null
        return face(o, live.drive, battery, cap, ahead, live.metric)
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
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val bold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { isFakeBoldText = true }
        private val plain = TextPaint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        private var face: Face? = null
        private var openedFor: Face? = null
        private var openedAt = 0L
        private var mode = Mode.HIDDEN
        private var preview = false
        private var night = false
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
            val f = if (ClusterLayout.drive) current(context) else null
            if (f == null) openedFor = null
            else if (changed(openedFor, f) && (openedFor == null || now - openedAt >= MIN_REOPEN_MS)) { openedFor = f; openedAt = now }
            val nextMode = mode(f != null, f?.warn == true, now - openedAt)
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
            val folded = fitted(f.chip, plain, b.width - chip - pad * 1.4f)
            val chipW = chip + pad * 0.2f + plain.measureText(folded) + pad * 1.2f
            val cw = chipW + (b.width - chipW) * t
            val ch = chip + (h - chip) * t
            val left = if (rightSide) b.left + b.width - cw else b.left
            val radius = chip / 2f + (h * 0.18f - chip / 2f) * t
            fill.color = pal.card
            r.set(left, b.top, left + cw, b.top + ch)
            canvas.drawRoundRect(r, radius, radius, fill)

            // The ring, where the now-playing card has its cover.
            val side = chip * 0.80f + (h * 0.66f - chip * 0.80f) * t
            val inset = (chip - chip * 0.80f) / 2f + (pad - (chip - chip * 0.80f) / 2f) * t
            val cx = left + inset + side / 2f
            val cy = b.top + ch / 2f
            val color = toneColor(f.tone, pal)
            ring.strokeWidth = side * 0.10f
            val rr = side / 2f - ring.strokeWidth / 2f
            r.set(cx - rr, cy - rr, cx + rr, cy + rr)
            ring.color = pal.track
            canvas.drawOval(r, ring)
            if (f.ringShare > 0f) {
                ring.color = color
                if (f.ringShare >= 1f) canvas.drawOval(r, ring) else canvas.drawArc(r, -90f, 360f * f.ringShare, false, ring)
            }
            bold.color = pal.text; bold.alpha = 255; bold.textSize = side * 0.34f
            val inside = side - ring.strokeWidth * 2 - side * 0.10f
            val need = bold.measureText(f.ring)
            if (need > inside) bold.textSize *= inside / need
            canvas.drawText(f.ring, cx - bold.measureText(f.ring) / 2f, cy + bold.textSize * 0.35f, bold)

            if (t < 0.5f) {
                plain.color = pal.text; plain.alpha = (255 * (1f - 2f * t)).toInt().coerceIn(0, 255)
                canvas.drawText(folded, left + chip + pad * 0.2f, b.top + chip / 2f + plain.textSize * 0.35f, plain)
                return
            }
            val alpha = ((t - 0.5f) * 2f).coerceIn(0f, 1f)
            val textLeft = left + inset + side + pad * 0.85f
            val textWidth = left + cw - pad - textLeft
            bold.color = pal.text; bold.alpha = (255 * alpha).toInt(); bold.textSize = h * 0.20f
            plain.color = pal.dim; plain.alpha = (255 * alpha).toInt(); plain.textSize = h * 0.16f
            val top = b.top + pad
            canvas.drawText(fitted(f.title, bold, textWidth), textLeft, top + h * 0.24f, bold)
            canvas.drawText(fitted(f.detail, plain, textWidth), textLeft, top + h * 0.47f, plain)

            val bh = h * 0.045f
            val y = b.top + ch - pad - bh
            when (val bar = f.bar) {
                is Bar.Battery -> {
                    fill.color = pal.track; fill.alpha = (Color.alpha(pal.track) * alpha).toInt()
                    r.set(textLeft, y, textLeft + textWidth, y + bh)
                    canvas.drawRoundRect(r, bh / 2, bh / 2, fill)
                    // What the pack holds now, faint, with what is left on arrival solid over it.
                    fill.color = color; fill.alpha = (90 * alpha).toInt()
                    r.right = textLeft + textWidth * bar.now
                    canvas.drawRoundRect(r, bh / 2, bh / 2, fill)
                    fill.alpha = (255 * alpha).toInt()
                    r.right = textLeft + textWidth * bar.arrival
                    if (bar.arrival > 0f) canvas.drawRoundRect(r, bh / 2, bh / 2, fill)
                }
                is Bar.Range -> {
                    fill.color = pal.track; fill.alpha = (Color.alpha(pal.track) * alpha).toInt()
                    r.set(textLeft, y, textLeft + textWidth, y + bh)
                    canvas.drawRoundRect(r, bh / 2, bh / 2, fill)
                    // A tick where normal is, a dot where this drive is; better is to the right.
                    fill.color = pal.dim; fill.alpha = (255 * alpha).toInt()
                    val nx = textLeft + textWidth * bar.normal
                    r.set(nx - bh * 0.25f, y - bh * 0.7f, nx + bh * 0.25f, y + bh * 1.7f)
                    canvas.drawRect(r, fill)
                    fill.color = color; fill.alpha = (255 * alpha).toInt()
                    canvas.drawCircle((textLeft + textWidth * bar.at).coerceIn(textLeft + bh, textLeft + textWidth - bh), y + bh / 2, bh * 1.25f, fill)
                }
                null -> {}
            }
        }
    }
}
