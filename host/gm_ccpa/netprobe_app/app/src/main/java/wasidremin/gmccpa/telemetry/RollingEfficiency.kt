package wasidremin.gmccpa.telemetry

/**
 * Energy use over the last stretch of the drive under way, for the driver display's Drive card
 * ([wasidremin.gmccpa.nav.DriveCard]): the last mile against the last ten, and one figure per mile.
 *
 * [TripRecorder] notes the battery every [STEP_M] of the drive ([Mark]). The car reports the battery
 * in 10 Wh steps, so a mile (about 300 Wh at motorway speed) is good to about 3%; a shorter window
 * would be mostly rounding. Pure Kotlin, so it is tested on the JVM.
 */
object RollingEfficiency {
    /** The battery, in Wh, when the drive had covered [distM]. */
    data class Mark(val distM: Double, val wh: Double)

    const val STEP_M = 100.0
    /** A little over ten miles is kept: the longest window the card shows. */
    const val KEEP_M = 17_000.0
    /** A figure above this many miles (or km) per kWh, including a stretch that gained energy, is shown as this. */
    const val CAP = 9.9

    /** The energy and distance between two marks. */
    class Span(val whPerKm: Double, val distM: Double)

    /** Pure: [marks] with [m] added when the drive has gone [STEP_M] since the last one, trimmed to [KEEP_M]. */
    fun add(marks: ArrayDeque<Mark>, m: Mark) {
        val last = marks.lastOrNull()
        if (last != null && m.distM - last.distM < STEP_M) return
        marks.addLast(m)
        while (marks.size > 2 && marks.last().distM - marks.first().distM > KEEP_M) marks.removeFirst()
    }

    /** Pure: the last [windowM] of the drive, or as much of it as [marks] cover. Null below 50 m. */
    fun span(marks: List<Mark>, windowM: Double): Span? {
        val last = marks.lastOrNull() ?: return null
        val start = marks.lastOrNull { it.distM <= last.distM - windowM + 1e-6 } ?: marks.first()
        val dist = last.distM - start.distM
        if (dist < 50.0) return null
        return Span((start.wh - last.wh) / (dist / 1000.0), dist)
    }

    /**
     * Pure: one figure, in Wh/km, for each of the last [count] whole units (miles or km) the drive
     * has completed, counted from its start; oldest first. A unit whose start the marks no longer
     * reach is left out.
     */
    fun perUnit(marks: List<Mark>, unitM: Double, count: Int): List<Double> {
        if (marks.size < 2) return emptyList()
        val out = ArrayList<Double>()
        val firstUnit = kotlin.math.ceil((marks.first().distM - 1e-6) / unitM).toLong()
        val lastUnit = kotlin.math.floor((marks.last().distM + 1e-6) / unitM).toLong()
        for (k in firstUnit until lastUnit) {
            val a = marks.firstOrNull { it.distM >= k * unitM - 1e-6 } ?: continue
            val b = marks.firstOrNull { it.distM >= (k + 1) * unitM - 1e-6 } ?: continue
            if (a.distM - k * unitM > STEP_M * 1.5) continue
            val d = b.distM - a.distM
            if (d <= 0.0) continue
            out += (a.wh - b.wh) / (d / 1000.0)
        }
        return out.takeLast(count)
    }

    /** Pure: [whPerKm] as the figure the card shows, mi/kWh or km/kWh, so that more is always better. */
    fun perKwh(whPerKm: Double, metric: Boolean): Double {
        if (whPerKm <= 0.0) return CAP
        val v = if (metric) 1000.0 / whPerKm else TripStats.milesPerKwh(whPerKm)
        return v.coerceAtMost(CAP)
    }
}
