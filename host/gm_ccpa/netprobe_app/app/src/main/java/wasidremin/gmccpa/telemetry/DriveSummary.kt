package wasidremin.gmccpa.telemetry

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The after-drive summary (T28): on Park, the drive just finished against the usual, where its
 * energy went, and how it sits among the last drives and the month. The owner chose the "card and
 * month" layout from four mockups (2026-11-24), shown for [SHOW_MS] after Park.
 *
 * The drive is the recorder's open one ([DriveLive]): it ends at its last moving sample and closes
 * only [TripRecorder.IDLE_CLOSE_MS] later, so at the moment of Park it is still open and not yet in
 * the drive log. Everything here is pure; [AfterDrive] does the gear, the file and the timing.
 */
object DriveSummary {
    /** T28: "after a drive of a mile or more". */
    const val MIN_DRIVE_M = TripStats.M_PER_MILE
    /** How long it stays up (owner, 2026-11-24: "Park, 30 s"). */
    const val SHOW_MS = 30_000L
    /** Drives in the row of bars, this one last. */
    const val RECENT = 10

    class Summary(
        val t0: Long,
        val t1: Long,
        val tzMin: Int,
        val distM: Double,
        val whPerKm: Double,
        val tempC: Double?,
        /** Battery percent at the start and end; null without the pack's capacity. */
        val pct0: Int?,
        val pct1: Int?,
        val usual: TripStats.Expectation?,
        val verdict: TripStats.Verdict?,
        val split: TripStats.EnergySplit,
        /** Wh/km of up to [RECENT] drives, oldest first, ending with this one. */
        val recent: List<Double>,
        /** This month's drives, this one included. */
        val month: TripStats.Rollup,
        val lifetimeWhPerKm: Double?,
    ) {
        val durationS: Double get() = (t1 - t0) / 1000.0
    }

    /** Pure: the gear moved into Park from a gear that was known not to be Park. Unknown readings are skipped by the caller. */
    fun parkedAfterDriving(wasParked: Boolean?, parked: Boolean): Boolean = parked && wasParked == false

    /** Pure: whether [drive] earns a summary: a drive of [MIN_DRIVE_M] that has not had one. */
    fun worthShowing(drive: Trip?, shownT0: Long?): Boolean =
        drive != null && drive.kind == Trip.Kind.DRIVE && drive.distM >= MIN_DRIVE_M && drive.t0 != shownT0 &&
            TripStats.whPerKm(drive) != null

    /**
     * Pure: the summary of [drive] against [stored], the drive log as read from the file (which may
     * already hold [drive] if it closed while the summary was built). [capWh] is the pack's usable
     * capacity, for percentages.
     */
    fun of(drive: Trip, stored: List<Trip>, capWh: Double?, monthStartMs: Long): Summary {
        val d = drive.copy(open = false)
        val all = (stored.filter { it.kind == Trip.Kind.DRIVE && !it.open && it.t0 != d.t0 } + d).sortedBy { it.t1 }
        val rate = TripStats.whPerKm(d)!!
        val usual = TripStats.expected(d, all, TripStats.routes(all))
        val cap = capWh?.takeIf { it > 1000.0 }
        fun pct(wh: Double) = cap?.let { (wh / it * 100.0).roundToInt().coerceIn(0, 100) }
        return Summary(
            t0 = d.t0, t1 = d.t1, tzMin = d.tzMin, distM = d.distM, whPerKm = rate, tempC = d.tempC,
            pct0 = pct(d.b0), pct1 = pct(d.b1),
            usual = usual,
            verdict = usual?.let { TripStats.verdict(rate, it.whPerKm) },
            split = TripStats.split(d),
            recent = all.filter { it.distM >= TripRecorder.MIN_DRIVE_M }.mapNotNull { TripStats.whPerKm(it) }
                .filter { it > 0.0 }.takeLast(RECENT),
            month = TripStats.rollup(all.filter { it.t1 >= monthStartMs }),
            lifetimeWhPerKm = DriveOutlook.average(all),
        )
    }

    /**
     * Pure: a drawn drive for the screen's preview, through [of] like a real one: 12.4 mi at 2.9
     * mi/kWh on a 41 °F evening against nine earlier runs of about 3.5 (the car, 2026-11-24).
     */
    fun sample(nowMs: Long, tzMin: Int = 0): Summary {
        val km = 12.4 * TripStats.M_PER_MILE / 1000.0
        fun run(t1: Long, whPerKm: Double, minutes: Long = 15) = Trip(Trip.Kind.DRIVE, t1 - minutes * 60_000L, t1, tzMin,
            60_000.0, 60_000.0 - km * whPerKm, distM = km * 1000.0, movingS = minutes * 60.0 - 120.0, tempC = 8.0)
        val day = 86_400_000L
        val usual = listOf(176.0, 182.0, 170.0, 190.0, 178.0, 165.0, 186.0, 174.0, 180.0)
        val history = usual.mapIndexed { i, w -> run(nowMs - (usual.size - i) * day, w) }
        val net = km * 214.0
        val drive = run(nowMs, 214.0).copy(b0 = 67_400.0, b1 = 67_400.0 - net, tempC = 5.0, disWh = net + 600.0,
            regWh = 600.0, standWh = 200.0, warmM = 3000.0, warmWh = 3.0 * 214.0 + 500.0)
        return of(drive, history, 86_380.0, nowMs - 23 * day)
    }

    // ---------------------------------------------------------------------------------------------
    // Words. Pure, so the card and its tests read the same text.
    // ---------------------------------------------------------------------------------------------

    fun rate(whPerKm: Double, metric: Boolean): String = "%.1f".format(Locale.US, RollingEfficiency.perKwh(whPerKm, metric))
    fun rateUnit(metric: Boolean): String = if (metric) "km/kWh" else "mi/kWh"
    fun distance(m: Double, metric: Boolean, digits: Int = 1): String =
        "%.${digits}f %s".format(Locale.US, if (metric) m / 1000.0 else m / TripStats.M_PER_MILE, if (metric) "km" else "mi")
    fun kwh(wh: Double): String = "%.1f kWh".format(Locale.US, wh / 1000.0)
    fun temperature(c: Double, metric: Boolean): String =
        if (metric) "${c.roundToInt()}°C" else "${(c * 9.0 / 5.0 + 32.0).roundToInt()}°F"
    fun minutes(s: Double): String = "${(s / 60.0).roundToInt().coerceAtLeast(1)} min"

    /** "5:53 – 6:08 PM" (one AM/PM when both ends share it), in the car's offset at the time. */
    fun clockRange(t0: Long, t1: Long, tzMin: Int): String {
        val off = ZoneOffset.ofTotalSeconds(tzMin * 60)
        val a = Instant.ofEpochMilli(t0).atOffset(off)
        val b = Instant.ofEpochMilli(t1).atOffset(off)
        val hm = DateTimeFormatter.ofPattern("h:mm", Locale.US)
        val ap = DateTimeFormatter.ofPattern("a", Locale.US)
        val end = "${b.format(hm)} ${b.format(ap)}"
        return if (a.format(ap) == b.format(ap)) "${a.format(hm)} – $end" else "${a.format(hm)} ${a.format(ap)} – $end"
    }

    /** "▼ 17% below your usual 3.5" and the like, or null with nothing to compare against. */
    fun versus(s: Summary, metric: Boolean): String? {
        val u = s.usual ?: return null
        val v = s.verdict ?: return null
        val whose = when (u.basis) {
            TripStats.Basis.ROUTE_AT_TEMPERATURE, TripStats.Basis.ROUTE -> "this route's usual"
            TripStats.Basis.LENGTH -> "your usual"
        }
        val pc = (v.fraction * 100).roundToInt()
        val usual = rate(u.whPerKm, metric)
        return when (v.standing) {
            TripStats.Standing.TYPICAL -> "about $whose $usual"
            TripStats.Standing.BETTER -> "▲ $pc% better than $whose $usual"
            TripStats.Standing.WORSE -> "▼ $pc% below $whose $usual"
        }
    }

    /** The line under the figure: distance, energy, time, temperature, battery. */
    fun facts(s: Summary, metric: Boolean): String = buildList {
        add(distance(s.distM, metric))
        add(kwh(s.split.netWh))
        add(minutes(s.durationS))
        s.tempC?.let { add(temperature(it, metric)) }
        if (s.pct0 != null && s.pct1 != null) add("${s.pct0}% → ${s.pct1}%")
    }.joinToString("  ·  ")

    class Part(val label: String, val wh: Double)

    /**
     * Where the energy went, the parts adding up to the drive's net use; a part under 50 Wh is left
     * out, since the battery reads in 10 Wh steps. Regeneration is [regen], already inside "Driving".
     */
    fun parts(s: Summary): List<Part> = listOf(
        Part("Driving", s.split.drivingWh),
        Part("Warm-up", s.split.warmupWh),
        Part("Stopped", s.split.standingWh),
    ).filter { it.label == "Driving" || it.wh >= 50.0 }

    fun regen(s: Summary): String? = s.split.regenWh.takeIf { it >= 50.0 }?.let { "Regen put back ${kwh(it)}" }

    /** "November  3.5 mi/kWh  ·  312 mi  ·  89 kWh", or null before a month has a rate. */
    fun monthLine(s: Summary, monthName: String, metric: Boolean): String? {
        val r = s.month.whPerKm?.takeIf { it > 0.0 } ?: return null
        return "$monthName  ${rate(r, metric)} ${rateUnit(metric)}  ·  ${distance(s.month.distM, metric, 0)}" +
            "  ·  %.0f kWh".format(Locale.US, s.month.netWh / 1000.0)
    }

    fun lifetimeLine(s: Summary, metric: Boolean): String? =
        s.lifetimeWhPerKm?.takeIf { it > 0.0 }?.let { "All time  ${rate(it, metric)} ${rateUnit(metric)}" }

    /** One log line, numbers only. */
    fun logLine(s: Summary): String = "drive summary: ${s.distM.roundToInt()} m, ${s.split.netWh.roundToInt()} Wh, " +
        "${s.whPerKm.roundToInt()} Wh/km" +
        (s.usual?.let { ", usual ${it.whPerKm.roundToInt()} (${it.basis.name.lowercase()}, ${it.runs} runs)" } ?: ", no usual yet") +
        ", driving/warm-up/stopped ${s.split.drivingWh.roundToInt()}/${s.split.warmupWh.roundToInt()}/${s.split.standingWh.roundToInt()} Wh" +
        ", regen ${s.split.regenWh.roundToInt()} Wh, ${s.recent.size} recent, month ${s.month.drives} drives"
}
