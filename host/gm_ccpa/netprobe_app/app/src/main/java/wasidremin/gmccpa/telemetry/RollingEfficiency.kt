package wasidremin.gmccpa.telemetry

/**
 * Energy use over the last stretch of the drive under way, for the driver display's Drive card
 * ([wasidremin.gmccpa.nav.DriveCard]): the last mile, the last ten, and the line of the last ten.
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

    // ---------------------------------------------------------------------------------------------
    // The card's line (2026-11-14): the last ten miles as a smoothed curve, the drive before included
    // ---------------------------------------------------------------------------------------------

    /** One point of the line: [x] miles (or km) from now, 0 at now and negative behind; the rate there. */
    data class Point(val x: Double, val whPerKm: Double)

    /** The line: the end of the drive before ([past]), this drive ([now]), and where this one began ([startX]). */
    class Trace(val past: List<Point>, val now: List<Point>, val startX: Double?) {
        val empty: Boolean get() = past.isEmpty() && now.isEmpty()
    }

    /** A point every this share of a mile (km). */
    const val LINE_STEP = 0.1
    /** Each point is the energy over this share of a mile (km) around it: 15 battery steps at motorway speed. */
    const val LINE_WINDOW = 0.5
    /** Nearer an end of the marks than this, a point would be a few battery steps and is left out. */
    const val LINE_EDGE_M = 150.0

    /** Pure: the battery at [distM], between the marks either side. */
    internal fun whAt(marks: List<Mark>, distM: Double): Double {
        var lo = 0
        var hi = marks.size - 1
        if (distM <= marks[lo].distM) return marks[lo].wh
        if (distM >= marks[hi].distM) return marks[hi].wh
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (marks[mid].distM <= distM) lo = mid else hi = mid
        }
        val a = marks[lo]
        val b = marks[hi]
        val span = b.distM - a.distM
        return if (span <= 0.0) b.wh else a.wh + (b.wh - a.wh) * (distM - a.distM) / span
    }

    /**
     * Pure: [marks] as a smoothed line over their last [spanUnits] miles (km), oldest first, ending
     * [endX] miles (km) before now. Each point is the energy over [LINE_WINDOW] around it (less near
     * the ends), and the points are then smoothed against their neighbours. The smoothing is done on
     * energy per distance, not on its inverse: a coasting stretch would otherwise swamp its neighbours.
     */
    fun line(marks: List<Mark>, unitM: Double, spanUnits: Double, endX: Double = 0.0): List<Point> {
        if (marks.size < 2 || spanUnits <= 0.0) return emptyList()
        val first = marks.first().distM
        val last = marks.last().distM
        val step = unitM * LINE_STEP
        val half = unitM * LINE_WINDOW / 2
        val xs = ArrayList<Double>()
        val rates = ArrayList<Double>()
        var d = last - LINE_EDGE_M
        while (d >= first + LINE_EDGE_M && last - d <= spanUnits * unitM) {
            val h = minOf(half, d - first, last - d)
            xs += endX - (last - d) / unitM
            rates += (whAt(marks, d - h) - whAt(marks, d + h)) / (2 * h / 1000.0)
            d -= step
        }
        if (rates.isEmpty()) return emptyList()
        xs.reverse(); rates.reverse()
        var r: List<Double> = rates
        repeat(2) {
            val prev = r
            r = List(prev.size) { i -> (prev[maxOf(i - 1, 0)] + 2 * prev[i] + prev[minOf(i + 1, prev.size - 1)]) / 4 }
        }
        return List(r.size) { Point(xs[it], r[it]) }
    }

    /**
     * Pure: the card's line over the last [spanUnits]: this drive's [marks], and before it as much of
     * [tail] (the marks the drives before left, [appended]) as the span still has room for. The two
     * are smoothed apart, so a drive's cold start does not bleed into the drive before.
     */
    fun trace(marks: List<Mark>, tail: List<Mark>, unitM: Double, spanUnits: Double = 10.0): Trace {
        val driven = if (marks.size >= 2) (marks.last().distM - marks.first().distM) / unitM else 0.0
        val now = line(marks, unitM, spanUnits)
        val room = spanUnits - driven
        val past = if (room > LINE_STEP) line(tail, unitM, room, endX = -driven) else emptyList()
        return Trace(past, now, if (past.isNotEmpty()) -driven else null)
    }

    /**
     * Pure: [tail] with a finished drive's [marks] joined on, as one run of distance and energy, so
     * the next drive's line can start with the end of this one. Trimmed to [KEEP_M].
     */
    fun appended(tail: List<Mark>, marks: List<Mark>): List<Mark> {
        if (marks.size < 2) return tail
        val d0 = marks.first().distM
        val w0 = marks.first().wh
        val dBase = tail.lastOrNull()?.distM ?: 0.0
        val wBase = tail.lastOrNull()?.wh ?: 0.0
        val out = ArrayList<Mark>(tail.size + marks.size)
        out += tail
        // The first mark of the drive is the last of the tail: the same place on the joined line.
        for (m in if (tail.isEmpty()) marks else marks.drop(1)) out += Mark(dBase + (m.distM - d0), wBase + (m.wh - w0))
        val end = out.last().distM
        return out.dropWhile { end - it.distM > KEEP_M }
    }

    /** Marks as a short string for the recorder's checkpoint: whole metres and whole Wh. */
    fun pack(marks: List<Mark>): String =
        marks.joinToString(";") { "${Math.round(it.distM)},${Math.round(it.wh)}" }

    fun unpack(s: String?): List<Mark> = s?.split(';')?.mapNotNull { pair ->
        val cut = pair.indexOf(',')
        if (cut <= 0) return@mapNotNull null
        val d = pair.substring(0, cut).toDoubleOrNull()
        val w = pair.substring(cut + 1).toDoubleOrNull()
        if (d == null || w == null) null else Mark(d, w)
    } ?: emptyList()

    /** Pure: [whPerKm] as the figure the card shows, mi/kWh or km/kWh, so that more is always better. */
    fun perKwh(whPerKm: Double, metric: Boolean): Double {
        if (whPerKm <= 0.0) return CAP
        val v = if (metric) 1000.0 / whPerKm else TripStats.milesPerKwh(whPerKm)
        return v.coerceAtMost(CAP)
    }
}
