package wasidremin.gmccpa

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import wasidremin.gmccpa.telemetry.InsightsData
import wasidremin.gmccpa.telemetry.InsightsData.UnitSystem
import wasidremin.gmccpa.telemetry.Trip
import wasidremin.gmccpa.telemetry.TripStats
import wasidremin.gmccpa.telemetry.TripStore
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Currency
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The drive log and everything derived from it once, off the UI thread. */
internal class TripModel(val trips: List<Trip>, fallbackCapWh: Double?) {
    /** Every drive, the one still open included; [closed] leaves it out. */
    val drives: List<Trip> = trips.filter { it.kind == Trip.Kind.DRIVE }
    val closed: List<Trip> = drives.filter { !it.open }
    val index: TripStats.RouteIndex = TripStats.routes(closed)
    val sessions: List<TripStats.ChargeSession> = TripStats.sessions(trips)
    val capWh: Double? = trips.lastOrNull { it.capWh != null }?.capWh ?: fallbackCapWh
    private val expectations = HashMap<Long, TripStats.Expectation?>()
    fun expected(d: Trip): TripStats.Expectation? = expectations.getOrPut(d.t0) { TripStats.expected(d, closed, index) }
}

/**
 * The four Insights tabs drawn from the on-car drive log: the last drive's report card, repeated
 * routes, charging and cost, and the week in review. Nothing here leaves the head unit, and a
 * route is only ever a letter the owner can rename.
 */
internal class InsightsTrips(
    private val act: Activity,
    private val host: LinearLayout,
    private val store: TripStore,
    private val units: () -> UnitSystem,
    /** Draw the current tab again. */
    private val rerender: () -> Unit,
    /** Read the log again, then draw. */
    private val reload: () -> Unit,
    private val selectTab: (Int) -> Unit,
) {
    var model = TripModel(emptyList(), null)

    private var selectedDrive: Long? = null
    private var selectedRoute: String? = null
    private val prefs = act.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val currency: String = try {
        Currency.getInstance(Locale.getDefault()).getSymbol(Locale.getDefault())
    } catch (e: Exception) { "$" }
    private val clockFormat = DateTimeFormatter.ofPattern(if (DateFormat.is24HourFormat(act)) "H:mm" else "h:mm a", Locale.US)
    private val dayFormat = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)
    private val weekdayFormat = DateTimeFormatter.ofPattern("EEEE", Locale.US)

    fun subtitle(): String {
        val m = model
        if (m.trips.isEmpty()) return "Drive history starts with your next drive"
        val first = m.trips.first()
        return "${m.drives.size} drives · ${m.sessions.size} charges · since ${day(first.t0, first.tzMin)} · kept on this head unit"
    }

    fun render(tab: Int) {
        when (tab) {
            0 -> renderLastDrive()
            1 -> renderRoutes()
            2 -> renderCharging()
            else -> renderWeek()
        }
    }

    // =============================================================================================
    // Last drive
    // =============================================================================================

    private fun renderLastDrive() {
        val m = model
        val d = m.drives.firstOrNull { it.t0 == selectedDrive } ?: m.drives.lastOrNull() ?: return emptyState()
        val latest = d.t0 == m.drives.last().t0
        val heading = when {
            d.open && System.currentTimeMillis() - d.t1 < 90_000L -> "Drive in progress"
            latest -> "Last drive"
            else -> "Drive"
        }
        host.addView(eqSectionLabel(act, "$heading — ${dayClock(d.t0, d.tzMin)}"))
        val card = EqCard(act)
        card.addRow(hero(d, m))
        val split = TripStats.split(d)
        val stopped = max(0.0, d.durationS - d.movingS)
        val rangeDrop = if (d.range0M != null && d.range1M != null) d.range0M - d.range1M else null
        card.addRow(kpiGrid(act, listOf(
            KpiItem("DISTANCE", distance(d.distM),
                "average ${speed(if (d.durationS > 0) d.distM / d.durationS else null)} · top ${speed(d.vmaxMps)}"),
            KpiItem("ENERGY USED", energy(d.netWh),
                m.capWh?.let { "${"%.1f".format(Locale.US, d.netWh / it * 100)}% of the pack" } ?: ""),
            KpiItem("TIME", InsightsData.formatDuration(d.durationS),
                "moving ${InsightsData.formatDuration(d.movingS)} · stopped ${InsightsData.formatDuration(stopped)}"),
            KpiItem("OUTSIDE", InsightsData.formatTemp(d.tempC, units()),
                if (d.upM != null && d.downM != null) "climbed ${height(d.upM)}, descended ${height(d.downM)}" else ""),
            KpiItem("RANGE SHOWN", rangeDrop?.let { (if (it >= 0) "−" else "+") + distance(abs(it)) } ?: "—",
                "on the dash, over ${distance(d.distM)} driven"),
            KpiItem("REGENERATION", energy(split.regenWh),
                split.regenShare?.let { "${percent(it)} of what the car drew" } ?: ""),
        ), columns = 3))
        host.addView(card)

        host.addView(eqSectionLabel(act, "Where the energy went"))
        val where = EqCard(act)
        if (split.netWh > 0.0) {
            where.addRow(LinearLayout(act).apply {
                setPadding(EqTheme.px(28f), EqTheme.px(24f), EqTheme.px(24f), EqTheme.px(20f))
                addView(SplitBarView(act, listOf(split.drivingWh to C_DRIVING, split.warmupWh to C_WARMUP,
                    split.standingWh to C_STANDING)), LinearLayout.LayoutParams(-1, EqTheme.px(22f)))
            })
            where.addRow(legendRow(C_DRIVING, "Driving", "Moving the car once it was warm", split.drivingWh, split.netWh))
            where.addRow(legendRow(C_WARMUP, "Warm-up", when {
                split.warmupWh > 0.0 -> "Extra used in the first ${distance(d.warmM)}, compared with the rest of the drive"
                !TripStats.canSplitWarmUp(d) -> "Too short a drive to tell the warm-up apart from the driving"
                else -> "The first ${distance(d.warmM)} used no more than the rest"
            }, split.warmupWh, split.netWh))
            where.addRow(legendRow(C_STANDING, "Standing still", "Climate and electronics while stopped", split.standingWh, split.netWh))
        } else {
            where.addRow(EqRow(act, "This drive put back as much as it used",
                "The battery ended ${energy(-split.netWh)} above where it started."))
        }
        host.addView(where)

        host.addView(eqSectionLabel(act, "Recent drives"))
        val recent = EqCard(act)
        for (r in m.drives.takeLast(RECENT_DRIVES).reversed()) {
            val route = m.index.routeOf(r)
            recent.addRow(EqRow(
                act,
                dayClock(r.t0, r.tzMin) + (route?.let { " · ${routeName(it)}" } ?: ""),
                "${distance(r.distM)} · ${InsightsData.formatDuration(r.durationS)}" +
                    (r.tempC?.let { " · ${InsightsData.formatTemp(it, units())}" } ?: ""),
                trailing = efficiencyWithMark(r, m),
                titleColor = if (r.t0 == d.t0) EqTheme.ACCENT else EqTheme.TEXT,
                onClick = { selectedDrive = r.t0; rerender(); scrollToTop() },
            ))
        }
        host.addView(recent)

        val today = TripStats.rollup(TripStats.onDay(m.drives, nowDay()))
        host.addView(eqSectionLabel(act, "Today"))
        val todayCard = EqCard(act)
        if (today.drives == 0) {
            todayCard.addRow(EqRow(act, "No drives yet today"))
        } else {
            todayCard.addRow(kpiGrid(act, listOf(
                KpiItem("DRIVES", today.drives.toString(), "${today.shortTrips} under ${distance(TripStats.SHORT_TRIP_M)}"),
                KpiItem("DISTANCE", distance(today.distM), InsightsData.formatDuration(today.durationS)),
                KpiItem("ENERGY", energy(today.netWh), ratePerKwh()?.let { "${money(TripStats.cost(today.netWh, it))} at home rates" } ?: ""),
                KpiItem("EFFICIENCY", efficiency(today.whPerKm), today.whPerKm?.let { fuelEquivalent(it) } ?: ""),
            ), columns = 4))
        }
        host.addView(todayCard)
    }

    private fun hero(d: Trip, m: TripModel): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(EqTheme.px(28f), EqTheme.px(24f), EqTheme.px(24f), EqTheme.px(20f))
        val route = m.index.routeOf(d)
        addView(EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply {
            text = "${route?.let { routeName(it) } ?: "One-off drive"} · ${clock(d.t0, d.tzMin)} – ${clock(d.t1, d.tzMin)}"
        })
        val (number, unit) = efficiencyParts(TripStats.whPerKm(d))
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(EqTheme.text(act, 60f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = number })
            addView(EqTheme.text(act, 22f, EqTheme.TEXT_DIM).apply { text = unit },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(8f) })
        val v = judge(d, m)
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(EqTheme.text(act, 18f, v.color, EqTheme.Weight.MEDIUM).apply { text = v.glyph })
            addView(EqTheme.text(act, 18f, EqTheme.TEXT).apply { text = v.words },
                LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(10f) })
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(10f) })
        if (v.basis != null) addView(EqTheme.text(act, 15f, EqTheme.TEXT_DIM).apply { text = v.basis },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })
    }

    /** A drive against its expectation: a glyph and words, so the colour never carries it alone. */
    private class Judgement(val glyph: String, val color: Int, val words: String, val short: String, val basis: String?)

    private fun judge(d: Trip, m: TripModel): Judgement {
        val e = TripStats.whPerKm(d)
        val x = m.expected(d)
        val v = if (e != null && x != null) TripStats.verdict(e, x.whPerKm) else null
        if (x == null || v == null) {
            return Judgement("○", EqTheme.TEXT_FAINT, "Not enough history yet to say what is typical for this drive", "", null)
        }
        val route = m.index.routeOf(d)?.let { routeName(it) } ?: "this route"
        val against = when (x.basis) {
            TripStats.Basis.ROUTE_AT_TEMPERATURE -> "for $route at ${InsightsData.formatTemp(d.tempC, units())}"
            TripStats.Basis.ROUTE -> "for $route"
            TripStats.Basis.LENGTH -> "for a drive this length"
        }
        val basis = "Typical is ${efficiency(x.whPerKm)}, from ${x.runs} other ${if (x.basis == TripStats.Basis.LENGTH) "drives" else "runs"}"
        val pct = "${(v.fraction * 100).roundToInt()}%"
        return when (v.standing) {
            TripStats.Standing.BETTER -> Judgement("▲", EqTheme.OK, "$pct better than typical $against", pct, basis)
            TripStats.Standing.WORSE -> Judgement("▼", EqTheme.WARN, "$pct worse than typical $against", pct, basis)
            TripStats.Standing.TYPICAL -> Judgement("●", EqTheme.TEXT_DIM, "Typical $against", "", basis)
        }
    }

    private fun efficiencyWithMark(d: Trip, m: TripModel): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val v = judge(d, m)
        addView(EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = efficiency(TripStats.whPerKm(d)) })
        addView(EqTheme.text(act, 16f, v.color, EqTheme.Weight.MEDIUM).apply { text = v.glyph },
            LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(14f) })
        addView(EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply {
            text = v.short
            minWidth = EqTheme.px(48f)
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(6f) })
    }

    private fun legendRow(color: Int, title: String, subtitle: String, wh: Double, totalWh: Double): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(EqTheme.px(28f), EqTheme.px(16f), EqTheme.px(24f), EqTheme.px(16f))
            addView(View(act).apply { background = EqTheme.rounded(color, 4f) },
                LinearLayout.LayoutParams(EqTheme.px(16f), EqTheme.px(16f)).apply { marginEnd = EqTheme.px(18f) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                addView(EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = title })
                addView(EqTheme.text(act, 15f, EqTheme.TEXT_DIM).apply { text = subtitle },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(4f) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = EqTheme.px(24f) })
            addView(EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = energy(wh) })
            addView(EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply {
                text = if (totalWh > 0.0) percent(wh / totalWh) else ""
                gravity = Gravity.END
                minWidth = EqTheme.px(64f)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(12f) })
        }

    private fun emptyState() {
        host.addView(eqSectionLabel(act, "Drive history"))
        val card = EqCard(act)
        card.addRow(EqRow(act, "Your drive history starts now",
            "From this version on, each drive and each charge is kept as one small record on this head unit. " +
                "A year of them takes under 1 MB, and the log is never uploaded."))
        card.addRow(EqRow(act, "Recording is on",
            "The first report card appears here after your next drive. It records whether or not " +
                "'EV Insights auto-upload' is on."))
        host.addView(card)
    }

    // =============================================================================================
    // Routes
    // =============================================================================================

    private fun renderRoutes() {
        val m = model
        if (m.drives.isEmpty()) return emptyState()
        val routes = m.index.repeated
        if (routes.isEmpty()) {
            host.addView(eqSectionLabel(act, "Your routes"))
            val card = EqCard(act)
            if (m.closed.none { it.lat0 != null && it.lat1 != null }) {
                card.addRow(EqRow(act, "Routes need location",
                    "Repeated routes are told apart by where a drive starts and ends, which the car records only " +
                        "while 'Include route GPS in EV Insights' is on in Advanced. Routes show as letters, never as places."))
            } else {
                card.addRow(EqRow(act, "No repeated routes yet", "A route appears here the second time you drive it."))
            }
            host.addView(card)
            return
        }
        val r = routes.firstOrNull { it.key == selectedRoute } ?: routes.first()
        val runs = r.runs.sortedBy { it.t0 }
        val last = runs.last()
        host.addView(eqSectionLabel(act, "${routeName(r)} — ${runs.size} runs · ${distance(r.distM)}"))
        val card = EqCard(act)
        val lastVerdict = judge(last, m)
        card.addRow(kpiGrid(act, listOf(
            KpiItem("BEST", efficiency(r.bestWhPerKm), runs.minByOrNull { TripStats.whPerKm(it) ?: Double.MAX_VALUE }
                ?.let { day(it.t0, it.tzMin) } ?: ""),
            KpiItem("TYPICAL", efficiency(r.typicalWhPerKm), r.typicalWhPerKm?.let { fuelEquivalent(it) } ?: ""),
            KpiItem("WORST", efficiency(r.worstWhPerKm), runs.maxByOrNull { TripStats.whPerKm(it) ?: -Double.MAX_VALUE }
                ?.let { day(it.t0, it.tzMin) } ?: ""),
            KpiItem("LAST RUN", efficiency(TripStats.whPerKm(last)),
                "${day(last.t0, last.tzMin)} · ${lastVerdict.glyph} ${lastVerdict.short.ifEmpty { "typical" }}"),
        ), columns = 4))
        val points = runs.mapNotNull { run -> TripStats.whPerKm(run)?.takeIf { it > 0.0 }?.let { efficiencyNumber(it) } }
        if (points.size >= 2) {
            val typical = r.typicalWhPerKm?.takeIf { it > 0.0 }?.let { efficiencyNumber(it) }
            card.addRow(chartRow("Efficiency on each run (${efficiencyUnit()})", TrendChartView(act).apply {
                set(points, day(runs.first().t0, runs.first().tzMin), day(last.t0, last.tzMin), typical,
                    typical?.let { "typical ${shownEfficiency(it)}" }, ::shownEfficiency)
            }))
        }
        temperatureSentence(r)?.let { card.addRow(EqRow(act, it.first, it.second)) }
        val renamed = prefs.getString(KEY_ROUTE_NAME + r.key, null) != null
        card.addRow(EqRow(act, "Rename this route",
            if (renamed) "Shown as '${r.label}' until renamed. Clear the name to go back." else "The name stays on this head unit.",
            trailing = eqValueChevron(act, routeName(r)),
            onClick = {
                ask("Rename route", "Name", prefs.getString(KEY_ROUTE_NAME + r.key, null) ?: "", numeric = false) { name ->
                    prefs.edit().apply {
                        if (name.isBlank()) remove(KEY_ROUTE_NAME + r.key) else putString(KEY_ROUTE_NAME + r.key, name.take(32))
                    }.apply()
                    rerender()
                }
            }))
        host.addView(card)

        host.addView(eqSectionLabel(act, "Latest runs"))
        val runCard = EqCard(act)
        for (run in runs.takeLast(ROUTE_RUNS).reversed()) {
            runCard.addRow(EqRow(act, dayClock(run.t0, run.tzMin),
                InsightsData.formatDuration(run.durationS) + (run.tempC?.let { " · ${InsightsData.formatTemp(it, units())}" } ?: ""),
                trailing = efficiencyWithMark(run, m),
                onClick = { selectedDrive = run.t0; selectTab(0); scrollToTop() }))
        }
        host.addView(runCard)

        host.addView(eqSectionLabel(act, "Your routes (${routes.size})"))
        val list = EqCard(act)
        for (route in routes.take(ROUTE_LIST)) {
            list.addRow(EqRow(act, routeName(route),
                "${route.runs.size} runs · ${distance(route.distM)} · best ${efficiencyValue(route.bestWhPerKm)}, " +
                    "typical ${efficiencyValue(route.typicalWhPerKm)}, worst ${efficiencyValue(route.worstWhPerKm)} ${efficiencyUnit()}",
                trailing = EqChevron(act).also { it.layoutParams = ViewGroup.LayoutParams(EqTheme.px(28f), EqTheme.px(28f)) },
                titleColor = if (route.key == r.key) EqTheme.ACCENT else EqTheme.TEXT,
                onClick = { selectedRoute = route.key; rerender(); scrollToTop() }))
        }
        host.addView(list)
    }

    /** What colder weather costs on [r], when its runs span enough of it to say. */
    private fun temperatureSentence(r: TripStats.Route): Pair<String, String>? {
        val fit = TripStats.tempFit(r.runs) ?: return null
        val typical = r.typicalWhPerKm?.takeIf { it > 0.0 } ?: return null
        if (fit.slopeWhPerKmPerC >= 0.0) return null
        val us = units() == UnitSystem.IMPERIAL
        val stepC = if (us) 10.0 / 1.8 else 5.0
        val pct = (-fit.slopeWhPerKmPerC * stepC / typical * 100).roundToInt()
        if (pct < 1) return null
        return "Every ${if (us) "10 °F" else "5 °C"} colder costs about $pct% here" to
            "From ${fit.runs} runs between ${InsightsData.formatTemp(fit.minC, units())} and ${InsightsData.formatTemp(fit.maxC, units())}"
    }

    // =============================================================================================
    // Charging and cost
    // =============================================================================================

    private fun renderCharging() {
        val m = model
        val us = units() == UnitSystem.IMPERIAL
        val rate = ratePerKwh()
        val gas = gasPerGallon()
        val mpg = compareMpg()
        host.addView(eqSectionLabel(act, "Prices"))
        val prices = EqCard(act)
        prices.addRow(EqRow(act, "Electricity rate",
            "What you pay per kWh at home. Costs count about ${percent(1 - TripStats.CHARGER_EFFICIENCY)} lost in the charger.",
            trailing = eqValueChevron(act, rate?.let { "${price(it)} / kWh" } ?: "Not set"),
            onClick = {
                ask("Electricity rate", "$currency per kWh", rate?.let { plain(it) } ?: "", numeric = true) { s ->
                    savePrice(KEY_RATE, s.toDoubleOrNull())
                }
            }))
        prices.addRow(EqRow(act, "Gas price", "For the comparison with a gas car. Leave it blank to skip that.",
            trailing = eqValueChevron(act, gas?.let { if (us) "${price(it)} / gal" else "${price(it / L_PER_GALLON)} / L" } ?: "Not set"),
            onClick = {
                ask("Gas price", "$currency per ${if (us) "gallon" else "litre"}",
                    gas?.let { plain(if (us) it else it / L_PER_GALLON) } ?: "", numeric = true) { s ->
                    savePrice(KEY_GAS, s.toDoubleOrNull()?.let { if (us) it else it * L_PER_GALLON })
                }
            }))
        prices.addRow(EqRow(act, "Comparison car", "The gas car your driving is compared with",
            trailing = eqValueChevron(act, if (us) "${plain(mpg)} mpg" else "${plain(MPG_L100 / mpg)} L/100 km"),
            onClick = {
                ask("Comparison car", if (us) "Miles per gallon" else "Litres per 100 km",
                    plain(if (us) mpg else MPG_L100 / mpg), numeric = true) { s ->
                    val v = s.toDoubleOrNull()?.takeIf { it > 0.0 }
                    savePrice(KEY_MPG, v?.let { if (us) it else MPG_L100 / it })
                }
            }))
        host.addView(prices)

        val months = TripStats.months(m.sessions, m.closed)
        val now = LocalDate.ofEpochDay(nowDay())
        val cur = months.firstOrNull { it.year == now.year && it.month == now.monthValue }
        host.addView(eqSectionLabel(act, "This month — ${now.format(monthFormat)}"))
        val monthCard = EqCard(act)
        if (cur == null) {
            monthCard.addRow(EqRow(act, "Nothing recorded this month yet"))
        } else {
            val miles = cur.distM / TripStats.M_PER_MILE
            val perKm = if (cur.distM >= 100.0) cur.netWh / (cur.distM / 1000.0) else null
            val driveCost = rate?.let { TripStats.cost(cur.netWh, it) }
            val gasCost = gas?.let { TripStats.gasCost(cur.distM, it, mpg) }
            monthCard.addRow(kpiGrid(act, listOf(
                KpiItem("ADDED TO THE BATTERY", energy(cur.addedWh),
                    "about ${"%.0f".format(Locale.US, TripStats.wallKwh(cur.addedWh))} kWh from the wall · ${cur.sessions} charges"),
                KpiItem("CHARGING COST", rate?.let { money(TripStats.cost(cur.addedWh, it)) } ?: "—",
                    if (rate != null) "at ${price(rate)} per kWh" else "Set an electricity rate above"),
                KpiItem("COST TO DRIVE", if (driveCost != null && cur.distM > 0.0)
                    (if (us) "${money(driveCost / miles, 3)} / mi" else "${money(driveCost / (cur.distM / 1000.0), 3)} / km") else "—",
                    "${distance(cur.distM)} driven this month"),
                KpiItem("EFFICIENCY", efficiency(perKm), perKm?.let { fuelEquivalent(it) } ?: ""),
                KpiItem("AGAINST GAS", when {
                    gasCost == null -> "—"
                    driveCost != null -> (if (gasCost >= driveCost) "saved " else "cost ") + money(abs(gasCost - driveCost))
                    else -> money(gasCost)
                }, when {
                    gasCost == null -> "Set a gas price above to compare"
                    driveCost != null -> "the comparison car would have burned ${money(gasCost)} of fuel"
                    else -> "of fuel in the comparison car; set a rate to see the saving"
                }),
                KpiItem("USUALLY DONE BY", typicalFinish(m.sessions)?.let { clockOfMinute(it) } ?: "—",
                    "when a charge runs to the limit"),
            ), columns = 3))
        }
        host.addView(monthCard)

        if (m.sessions.isEmpty()) {
            host.addView(eqSectionLabel(act, "Charge log"))
            host.addView(EqCard(act).addRow(EqRow(act, "No charges recorded yet",
                "Each plug-in appears here with the energy added, the power and when it finished.")))
            return
        }

        if (months.size > 1) {
            host.addView(eqSectionLabel(act, "By month"))
            val list = EqCard(act)
            for (mo in months.take(MONTHS)) {
                val perKm = if (mo.distM >= 100.0) mo.netWh / (mo.distM / 1000.0) else null
                list.addRow(EqRow(act, LocalDate.of(mo.year, mo.month, 1).format(monthFormat),
                    "${mo.sessions} charges · ${distance(mo.distM)} driven · ${efficiency(perKm)}",
                    trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply {
                        text = energy(mo.addedWh) + (rate?.let { " · ${money(TripStats.cost(mo.addedWh, it))}" } ?: "")
                    }))
            }
            host.addView(list)
        }

        val stops = m.sessions.filter { it.reachedLimit }
        if (stops.size >= 3) {
            val limit = stops.last().limitPct?.roundToInt()
            host.addView(eqSectionLabel(act, "Where the ${limit?.let { "$it% " } ?: ""}limit stops"))
            val typical = TripStats.median(stops.map { it.b1 / 1000.0 })
            val fmt = { v: Double -> "%.1f".format(Locale.US, v) }
            host.addView(EqCard(act)
                .addRow(chartRow("Battery energy when each full charge stopped (kWh)", TrendChartView(act).apply {
                    set(stops.map { it.b1 / 1000.0 }, day(stops.first().t1, stops.first().tzMin), day(stops.last().t1, stops.last().tzMin),
                        typical, typical?.let { "typical ${fmt(it)}" }, fmt)
                }))
                .addRow(EqRow(act, "What this shows",
                    "The car's own energy reading at the moment a charge stopped at the limit. It moves a little " +
                        "from one charge to the next; a steady fall over many months would point to lost capacity.")))
        }

        host.addView(eqSectionLabel(act, "Charge log"))
        val log = EqCard(act)
        for (s in m.sessions.takeLast(CHARGE_LOG).reversed()) {
            val detail = buildList {
                s.powerKw?.let { add("${"%.1f".format(Locale.US, it)} kW average") }
                if (s.slept) add("began while the car was asleep")
                else add(InsightsData.formatDuration((s.t1 - s.t0) / 1000.0))
                val pct = s.capWh?.takeIf { it > 1000.0 }?.let { " at ${"%.0f".format(Locale.US, s.b1 / it * 100)}%" } ?: ""
                add(if (s.reachedLimit) "ran to the limit$pct" else "unplugged$pct")
            }
            log.addRow(EqRow(act, "${day(s.t1, s.tzMin)} · finished ${clock(s.t1, s.tzMin)}", detail.joinToString(" · "),
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply {
                    text = "+" + energy(s.addedWh) + (rate?.let { " · ${money(TripStats.cost(s.addedWh, it))}" } ?: "")
                }))
        }
        host.addView(log)
    }

    /** The median clock time a full charge finishes, as a minute of the day; nights wrap around noon. */
    private fun typicalFinish(sessions: List<TripStats.ChargeSession>): Int? {
        val done = sessions.filter { it.reachedLimit }.takeLast(30)
        if (done.size < 2) return null
        val shifted = done.map { ((TripStats.minuteOfDay(it.t1, it.tzMin) + 720) % 1440).toDouble() }
        return ((TripStats.median(shifted)!!.roundToInt() + 720) % 1440)
    }

    // =============================================================================================
    // Week in review
    // =============================================================================================

    private fun renderWeek() {
        val m = model
        if (m.drives.isEmpty()) return emptyState()
        val today = nowDay()
        val monday = TripStats.weekStart(today)
        val now = TripStats.rollup(TripStats.inWeek(m.drives, monday))
        val lastWeek = TripStats.inWeek(m.drives, monday - 7)
        val before = TripStats.rollup(lastWeek)
        // Totals are compared with the same days of last week, so a Wednesday is not held against
        // a whole week; rates (efficiency, short trips) use all of last week, which is steadier.
        val toDate = TripStats.rollup(lastWeek.filter { TripStats.dayOf(it) <= today - 7 })
        val whole = today - monday >= 6
        val byNow = if (whole) "last week" else "by ${LocalDate.ofEpochDay(today).format(weekdayFormat)} last week"
        val rate = ratePerKwh()
        fun last(value: String) = if (before.drives > 0) "$value $byNow" else "no drives last week"

        host.addView(eqSectionLabel(act, "This week so far — from ${dayOf(monday)}"))
        val card = EqCard(act)
        val nowEff = now.whPerKm
        val lastEff = before.whPerKm
        val effVerdict = if (nowEff != null && lastEff != null) TripStats.verdict(nowEff, lastEff) else null
        card.addRow(kpiGrid(act, listOf(
            KpiItem("DISTANCE", distance(now.distM), last(distance(toDate.distM))),
            KpiItem("ENERGY", energy(now.netWh),
                (rate?.let { "${money(TripStats.cost(now.netWh, it))} · " } ?: "") + last(energy(toDate.netWh))),
            KpiItem("EFFICIENCY", efficiency(nowEff), when (effVerdict?.standing) {
                TripStats.Standing.BETTER -> "▲ ${(effVerdict.fraction * 100).roundToInt()}% better than last week's ${efficiencyValue(lastEff)}"
                TripStats.Standing.WORSE -> "▼ ${(effVerdict.fraction * 100).roundToInt()}% worse than last week's ${efficiencyValue(lastEff)}"
                TripStats.Standing.TYPICAL -> "● about the same as last week's ${efficiencyValue(lastEff)}"
                null -> if (lastEff != null) "last week ${efficiencyValue(lastEff)}" else "no drives last week"
            }),
            KpiItem("DRIVES", now.drives.toString(), last(toDate.drives.toString())),
            KpiItem("SHORT TRIPS", now.shortShare?.let { percent(it) } ?: "—",
                "under ${distance(TripStats.SHORT_TRIP_M)}" + (before.shortShare?.let { " · last week ${percent(it)}" } ?: "")),
            KpiItem("TIME DRIVING", InsightsData.formatDuration(now.durationS), last(InsightsData.formatDuration(toDate.durationS))),
        ), columns = 3))
        host.addView(card)

        val strip = TripStats.dailyDistance(m.drives, monday + 6, 28)
        if (strip.any { it.second > 0.0 }) {
            host.addView(eqSectionLabel(act, "Distance each day, last four weeks"))
            val us = units() == UnitSystem.IMPERIAL
            host.addView(EqCard(act).addRow(chartRow(
                "Each bar is one day's driving (${if (us) "mi" else "km"}); this week is the bright one",
                DayBarsView(act).apply {
                    set(strip.map { if (us) it.second / TripStats.M_PER_MILE else it.second / 1000.0 },
                        (0 until 4).map { w -> if (w == 3) "This week" else dayOf(strip[w * 7].first) },
                        highlightFrom = 21, lastIndex = (today - strip.first().first).toInt())
                }, heightDp = 200f)))
        }

        val b = TripStats.bests(m.closed)
        host.addView(eqSectionLabel(act, "Personal bests"))
        val bests = EqCard(act)
        fun fresh(t: Trip) = if (TripStats.dayOf(t) >= monday) " · new this week" else ""
        fun where(t: Trip) = dayClock(t.t0, t.tzMin) + (m.index.routeOf(t)?.let { " · ${routeName(it)}" } ?: "")
        fun open(t: Trip): () -> Unit = { selectedDrive = t.t0; selectTab(0); scrollToTop() }
        b.efficiency?.let { t ->
            bests.addRow(EqRow(act, "Best efficiency", "${where(t)} · ${distance(t.distM)}${fresh(t)}",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = efficiency(TripStats.whPerKm(t)) }, onClick = open(t)))
        }
        b.longest?.let { t ->
            bests.addRow(EqRow(act, "Longest drive", "${where(t)} · ${InsightsData.formatDuration(t.durationS)}${fresh(t)}",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = distance(t.distM) }, onClick = open(t)))
        }
        b.regen?.let { t ->
            bests.addRow(EqRow(act, "Most regeneration", "${where(t)} · ${energy(t.regWh)} put back${fresh(t)}",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = percent(t.regWh / t.disWh) }, onClick = open(t)))
        }
        b.biggestDay?.let { (d, dist) ->
            bests.addRow(EqRow(act, "Biggest day", dayOf(d) + if (d >= monday) " · new this week" else "",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT).apply { text = distance(dist) }))
        }
        if (bests.childCount == 0) bests.addRow(EqRow(act, "No finished drives yet"))
        else bests.addRow(EqRow(act, "How these are picked",
            "Efficiency and regeneration count drives of ${distance(TripStats.SHORT_TRIP_M)} or more."))
        host.addView(bests)
    }

    // =============================================================================================
    // Diagnostics: the log itself
    // =============================================================================================

    fun renderHistoryCard() {
        val m = model
        host.addView(eqSectionLabel(act, "Drive history"))
        val card = EqCard(act)
        val first = m.trips.firstOrNull()
        card.addRow(EqRow(act, "On this head unit",
            if (first == null) "Nothing recorded yet. Drives are recorded while 'EV Insights auto-upload' is on."
            else "${m.closed.size} drives and ${m.trips.count { it.kind == Trip.Kind.CHARGE }} charge records since " +
                "${day(first.t0, first.tzMin)} · ${"%.0f".format(Locale.US, store.bytes() / 1024.0)} KB · never uploaded, " +
                "and kept when probe segments are cleared"))
        if (first != null) {
            card.addRow(EqRow(act, "Clear drive history",
                "Removes every recorded drive and charge. Routes, bests and charging totals start over.",
                titleColor = EqTheme.DANGER,
                onClick = {
                    EqDialog.confirm(act, "Clear drive history",
                        "Every recorded drive and charge is removed from this head unit. This cannot be undone.",
                        "Clear", true) {
                        store.clear()
                        selectedDrive = null
                        selectedRoute = null
                        reload()
                    }
                }))
        }
        host.addView(card)
    }

    // =============================================================================================
    // Building blocks
    // =============================================================================================

    private fun chartRow(title: String, chart: View, heightDp: Float = 230f): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(EqTheme.px(28f), EqTheme.px(20f), EqTheme.px(24f), EqTheme.px(18f))
        addView(EqTheme.text(act, 16f, EqTheme.TEXT_DIM).apply { text = title })
        addView(chart, LinearLayout.LayoutParams(-1, EqTheme.px(heightDp)).apply { topMargin = EqTheme.px(10f) })
    }

    private fun scrollToTop() {
        var p = host.parent
        while (p != null && p !is ScrollView) p = p.parent
        (p as? ScrollView)?.smoothScrollTo(0, 0)
    }

    /** One-field dialog in the app's look. [onSave] gets the trimmed text; an empty answer clears the value. */
    private fun ask(title: String, label: String, initial: String, numeric: Boolean, onSave: (String) -> Unit) {
        val dialog = Dialog(act)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dialog.window?.setDimAmount(0.6f)
        val field = EditText(act).apply {
            setText(initial)
            setSelection(text.length)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, EqTheme.sp(22f))
            setTextColor(EqTheme.TEXT)
            setHintTextColor(EqTheme.TEXT_FAINT)
            hint = if (numeric) "Not set" else "Default name"
            inputType = if (numeric) InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
            setSingleLine()
            background = null
        }
        val body = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            background = EqTheme.rounded(0xFF111826.toInt(), 28f, EqTheme.CARD_LINE)
            setPadding(EqTheme.px(40f), EqTheme.px(36f), EqTheme.px(40f), EqTheme.px(28f))
            addView(EqTheme.text(act, 28f, EqTheme.TEXT, EqTheme.Weight.LIGHT).apply { text = title })
            addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
                text = label.uppercase()
                letterSpacing = 0.12f
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(18f) })
            addView(field, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(6f) })
            addView(LinearLayout(act).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(eqButton(act, "Cancel", EqButtonStyle.SECONDARY) { dialog.dismiss() })
                addView(eqButton(act, "Save", EqButtonStyle.PRIMARY) {
                    onSave(field.text.toString().trim())
                    dialog.dismiss()
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = EqTheme.px(16f) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(32f) })
        }
        val width = min(EqTheme.px(720f), act.resources.displayMetrics.widthPixels - EqTheme.px(96f))
        dialog.setContentView(body, ViewGroup.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT))
        dialog.show()
    }

    // ---- settings ----

    private fun ratePerKwh(): Double? = positive(KEY_RATE)
    private fun gasPerGallon(): Double? = positive(KEY_GAS)
    private fun compareMpg(): Double = positive(KEY_MPG) ?: DEFAULT_MPG
    private fun positive(key: String): Double? =
        if (prefs.contains(key)) prefs.getFloat(key, 0f).toDouble().takeIf { it > 0.0 && it.isFinite() } else null

    private fun savePrice(key: String, value: Double?) {
        prefs.edit().apply {
            if (value == null || value <= 0.0 || !value.isFinite()) remove(key) else putFloat(key, value.toFloat())
        }.apply()
        rerender()
    }

    private fun routeName(r: TripStats.Route): String = prefs.getString(KEY_ROUTE_NAME + r.key, null) ?: r.label

    // ---- formatting ----

    private fun distance(m: Double?) = InsightsData.formatDistance(m, units())
    private fun speed(mps: Double?) = InsightsData.formatSpeed(mps, units())
    private fun efficiency(whPerKm: Double?) = InsightsData.formatEfficiency(whPerKm, units())
    private fun efficiencyUnit() = if (units() == UnitSystem.IMPERIAL) "mi/kWh" else "Wh/km"
    /** The number the page shows for an efficiency: mi/kWh in US units, Wh/km in metric. */
    private fun efficiencyNumber(whPerKm: Double): Double =
        if (units() == UnitSystem.IMPERIAL) TripStats.milesPerKwh(whPerKm) else whPerKm
    /** A number already in display units ([efficiencyNumber]) as text. */
    private fun shownEfficiency(shown: Double): String =
        if (units() == UnitSystem.IMPERIAL) "%.2f".format(Locale.US, shown) else "%.0f".format(Locale.US, shown)
    /** An efficiency in Wh/km as the bare display number, without its unit. */
    private fun efficiencyValue(whPerKm: Double?): String =
        if (whPerKm == null || whPerKm <= 0.0) "—" else shownEfficiency(efficiencyNumber(whPerKm))
    private fun efficiencyParts(whPerKm: Double?): Pair<String, String> =
        if (whPerKm == null || whPerKm <= 0.0 || !whPerKm.isFinite()) "—" to efficiencyUnit()
        else shownEfficiency(efficiencyNumber(whPerKm)) to efficiencyUnit()
    private fun fuelEquivalent(whPerKm: Double): String {
        if (whPerKm <= 0.0) return ""
        val mpge = TripStats.mpge(whPerKm)
        return if (units() == UnitSystem.IMPERIAL) "${"%.0f".format(Locale.US, mpge)} MPGe"
        else "${"%.1f".format(Locale.US, MPG_L100 / mpge)} L/100 km equivalent"
    }
    private fun energy(wh: Double): String =
        if (abs(wh) < 10_000.0) "${"%.2f".format(Locale.US, wh / 1000.0)} kWh" else "${"%.1f".format(Locale.US, wh / 1000.0)} kWh"
    private fun percent(fraction: Double) = "${(fraction * 100).roundToInt()}%"
    private fun height(m: Double): String =
        if (units() == UnitSystem.IMPERIAL) "${"%,.0f".format(Locale.US, m * 3.28084)} ft" else "${"%,.0f".format(Locale.US, m)} m"
    private fun money(v: Double, decimals: Int = 2) = currency + "%,.${decimals}f".format(Locale.US, v)
    /** A unit price: two decimals when that is exact, three when the rate has a third. */
    private fun price(v: Double): String = money(v, if (abs(v * 100 - (v * 100).roundToInt()) < 1e-6) 2 else 3)
    private fun plain(v: Double): String = "%.3f".format(Locale.US, v).trimEnd('0').trimEnd('.')

    private fun local(t: Long, tzMin: Int): OffsetDateTime =
        Instant.ofEpochMilli(t).atOffset(ZoneOffset.ofTotalSeconds(tzMin.coerceIn(-18 * 60, 18 * 60) * 60))
    private fun clock(t: Long, tzMin: Int): String = local(t, tzMin).format(clockFormat)
    private fun day(t: Long, tzMin: Int): String = local(t, tzMin).format(dayFormat)
    private fun dayClock(t: Long, tzMin: Int): String = "${day(t, tzMin)} · ${clock(t, tzMin)}"
    private fun dayOf(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(dayFormat)
    private fun clockOfMinute(minute: Int): String = java.time.LocalTime.of(minute / 60, minute % 60).format(clockFormat)
    private fun nowDay(): Long {
        val now = System.currentTimeMillis()
        return TripStats.epochDay(now, TimeZone.getDefault().getOffset(now) / 60_000)
    }

    companion object {
        private const val PREFS = "ev_insights_ui_prefs"
        private const val KEY_RATE = "rate_per_kwh"
        private const val KEY_GAS = "gas_per_gallon"
        private const val KEY_MPG = "compare_mpg"
        private const val KEY_ROUTE_NAME = "route_name_"
        private const val DEFAULT_MPG = 30.0
        private const val L_PER_GALLON = 3.785411784
        /** mpg × L/100 km. */
        private const val MPG_L100 = 235.214583
        private const val RECENT_DRIVES = 12
        private const val ROUTE_RUNS = 8
        private const val ROUTE_LIST = 12
        private const val MONTHS = 12
        private const val CHARGE_LOG = 12

        // The energy split's three parts: the reference palette's dark steps, checked with the
        // dataviz validator against this card surface (adjacent CVD ΔE 26, contrast ≥ 3:1).
        const val C_DRIVING = 0xFF3987E5.toInt()
        const val C_WARMUP = 0xFFD95926.toInt()
        const val C_STANDING = 0xFF9085E9.toInt()
        /** What a card looks like over the page gradient, for the ring that separates a dot from a line. */
        const val C_SURFACE = 0xFF141922.toInt()
    }
}

// =================================================================================================
// Canvas views
// =================================================================================================

/** One bar split into parts that add up to a whole: 2 px gaps, rounded outer ends. */
internal class SplitBarView(ctx: Context, private val parts: List<Pair<Double, Int>>) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clip = Path()

    override fun onDraw(canvas: Canvas) {
        val shown = parts.filter { it.first > 0.0 }
        val total = shown.sumOf { it.first }
        if (total <= 0.0 || width <= 0) return
        val gap = EqTheme.pxf(2f)
        val usable = width - gap * (shown.size - 1)
        clip.reset()
        clip.addRoundRect(RectF(0f, 0f, width.toFloat(), height.toFloat()), EqTheme.pxf(5f), EqTheme.pxf(5f), Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        var x = 0f
        for ((value, color) in shown) {
            val w = max(EqTheme.pxf(3f), (usable * value / total).toFloat())
            paint.color = color
            canvas.drawRect(x, 0f, x + w, height.toFloat(), paint)
            x += w + gap
        }
        canvas.restore()
    }
}

/**
 * One series over its runs: a thin line, a ringed dot per value, the last one larger and labelled,
 * and an optional dashed reference line with its own label. One axis, three faint grid lines.
 */
internal class TrendChartView(ctx: Context) : View(ctx) {
    private var values: List<Double> = emptyList()
    private var firstLabel = ""
    private var lastLabel = ""
    private var reference: Double? = null
    private var referenceLabel: String? = null
    private var format: (Double) -> String = { fmtShort(it) }

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = InsightsTrips.C_DRIVING
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(2f)
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InsightsTrips.C_DRIVING }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InsightsTrips.C_SURFACE }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x14FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(1f)
    }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_DIM
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(1.5f)
        pathEffect = DashPathEffect(floatArrayOf(EqTheme.pxf(6f), EqTheme.pxf(6f)), 0f)
    }
    private val axisText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_FAINT
        typeface = EqTheme.Weight.REGULAR.face
        textSize = EqTheme.pxf(13f)
    }
    private val noteText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_DIM
        typeface = EqTheme.Weight.REGULAR.face
        textSize = EqTheme.pxf(14f)
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT
        typeface = EqTheme.Weight.MEDIUM.face
        textSize = EqTheme.pxf(16f)
    }
    private val path = Path()

    fun set(values: List<Double>, firstLabel: String, lastLabel: String, reference: Double?, referenceLabel: String?,
            format: (Double) -> String) {
        this.values = values
        this.firstLabel = firstLabel
        this.lastLabel = lastLabel
        this.reference = reference
        this.referenceLabel = referenceLabel
        this.format = format
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (values.isEmpty()) return
        val all = values + listOfNotNull(reference)
        var lo = all.min()
        var hi = all.max()
        val pad = if (hi - lo < 1e-9) max(abs(hi) * 0.05, 0.5) else (hi - lo) * 0.12
        lo -= pad
        hi += pad
        val left = EqTheme.pxf(56f)
        // Room on the right for the last value, which sits beside its dot instead of on the line.
        val right = width - EqTheme.pxf(14f) - valueText.measureText(format(values.last())) - EqTheme.pxf(14f)
        val top = EqTheme.pxf(16f)
        val bottom = height - EqTheme.pxf(30f)
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()
        fun x(i: Int) = if (values.size == 1) (left + right) / 2 else left + (right - left) * i / (values.size - 1)

        axisText.textAlign = Paint.Align.RIGHT
        for (step in 0..2) {
            val v = lo + pad + (hi - lo - 2 * pad) * step / 2.0
            canvas.drawLine(left, y(v), right, y(v), grid)
            canvas.drawText(format(v), left - EqTheme.pxf(10f), y(v) + axisText.textSize * 0.35f, axisText)
        }
        reference?.let { r ->
            canvas.drawLine(left, y(r), right, y(r), dashed)
            referenceLabel?.let {
                noteText.textAlign = Paint.Align.LEFT
                canvas.drawText(it, left + EqTheme.pxf(6f), y(r) - EqTheme.pxf(7f), noteText)
            }
        }
        path.reset()
        values.forEachIndexed { i, v -> if (i == 0) path.moveTo(x(i), y(v)) else path.lineTo(x(i), y(v)) }
        canvas.drawPath(path, line)
        values.forEachIndexed { i, v ->
            val r = if (i == values.size - 1) EqTheme.pxf(7f) else EqTheme.pxf(4.5f)
            canvas.drawCircle(x(i), y(v), r + EqTheme.pxf(2f), ring)
            canvas.drawCircle(x(i), y(v), r, dot)
        }
        valueText.textAlign = Paint.Align.LEFT
        canvas.drawText(format(values.last()), right + EqTheme.pxf(14f), y(values.last()) + valueText.textSize * 0.35f, valueText)

        axisText.textAlign = Paint.Align.LEFT
        canvas.drawText(firstLabel, left, height - EqTheme.pxf(6f), axisText)
        axisText.textAlign = Paint.Align.RIGHT
        canvas.drawText(lastLabel, right, height - EqTheme.pxf(6f), axisText)
    }
}

/**
 * Four weeks of daily totals as bars grouped by week. The weeks before [highlightFrom] are the same
 * hue stepped back, so the current week reads first; the tallest bar carries the only value label.
 */
internal class DayBarsView(ctx: Context) : View(ctx) {
    private var values: List<Double> = emptyList()
    private var weekLabels: List<String> = emptyList()
    private var highlightFrom = 0
    private var lastIndex = 0

    private val bright = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = InsightsTrips.C_DRIVING }
    private val muted = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2C4D7C.toInt() }
    private val empty = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x24FFFFFF }
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x1FFFFFFF
        strokeWidth = EqTheme.pxf(1f)
    }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = EqTheme.Weight.REGULAR.face
        textSize = EqTheme.pxf(13f)
    }
    private val valueText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT
        typeface = EqTheme.Weight.MEDIUM.face
        textSize = EqTheme.pxf(15f)
        textAlign = Paint.Align.CENTER
    }
    private val bar = Path()

    /** [lastIndex] is today's slot; days after it are still to come and draw nothing. */
    fun set(values: List<Double>, weekLabels: List<String>, highlightFrom: Int, lastIndex: Int) {
        this.values = values
        this.weekLabels = weekLabels
        this.highlightFrom = highlightFrom
        this.lastIndex = lastIndex
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val weeks = (n + 6) / 7
        val weekGap = EqTheme.pxf(22f)
        val gap = EqTheme.pxf(4f)
        val slot = (width - weekGap * (weeks - 1)) / n
        val top = EqTheme.pxf(28f)
        val bottom = height - EqTheme.pxf(30f)
        val peak = values.max()
        val tallest = if (peak > 0.0) values.indexOf(peak) else -1
        val radius = min(EqTheme.pxf(4f), (slot - gap) / 2)
        canvas.drawLine(0f, bottom, width.toFloat(), bottom, base)
        for (i in 0 until n) {
            val x0 = i * slot + (i / 7) * weekGap + gap / 2
            val x1 = x0 + slot - gap
            if (i % 7 == 0) {
                labelText.color = if (i >= highlightFrom) EqTheme.TEXT_DIM else EqTheme.TEXT_FAINT
                labelText.textAlign = Paint.Align.LEFT
                canvas.drawText(weekLabels.getOrNull(i / 7) ?: "", x0, height - EqTheme.pxf(8f), labelText)
            }
            if (i > lastIndex) continue
            val v = values[i]
            if (v <= 0.0) {
                canvas.drawRect(x0, bottom - EqTheme.pxf(2f), x1, bottom, empty)
                continue
            }
            val y = max(top, (bottom - v / peak * (bottom - top)).toFloat())
            val r = min(radius, (bottom - y) / 2)
            bar.reset()
            bar.addRoundRect(RectF(x0, y, x1, bottom), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
            canvas.drawPath(bar, if (i >= highlightFrom) bright else muted)
            if (i == tallest) {
                val cx = ((x0 + x1) / 2).coerceIn(valueText.textSize, width - valueText.textSize)
                canvas.drawText(fmtShort(v), cx, y - EqTheme.pxf(8f), valueText)
            }
        }
    }
}
