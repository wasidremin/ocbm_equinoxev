package wasidremin.gmccpa

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import wasidremin.gmccpa.telemetry.InsightsData
import wasidremin.gmccpa.telemetry.InsightsData.UnitSystem
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The "Insights" settings page: an in-car EV telemetry workbench built from this head unit's own
 * probe captures (`report.json` + `events.ndjson`), bringing the `evinsights` analytics engine
 * directly onto the 17.7" Equinox EV display.
 *
 * Four sub-tabs:
 *   0. Overview — live vehicle snapshot (SoC% + kWh, range, charge/regen power, speed, temp, gear),
 *      battery gauge with 80% target marker, lifetime fleet rollup, speed-band distribution bar,
 *      distance-source cross-validation (WHEEL_TICK vs PERF_VEHICLE_SPEED vs GPS), and SoC trend.
 *   1. Drives & Charging — every contiguous drive and charging session (grouped by `bootId`) with
 *      full efficiency (`Wh/km` & `mi/kWh`), regen recovery %, GOM range accuracy, and interactive
 *      session time-series charts (SoC & Range, Speed & Power, GPS route trace).
 *   2. Signals — VHAL signal health & frequency census across all 37 vehicle properties and 15
 *      automotive permissions (callbacks, observed Hz, min/max, live status).
 *   3. Segments — individual 60-second probe chunks with upload status and one-tap inspection into
 *      the segment's high-resolution time series.
 *
 * Loading runs off the UI thread ([refresh]); chart geometry math ([chartGeom], [routeGeom]) is
 * pure for JVM tests.
 */
class InsightsPage(
    private val act: Activity,
    private val dataRoot: File,
) {
    val view = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    private val subtitleView = EqTheme.text(act, 17f, EqTheme.TEXT_DIM)
    private val subtabControl: EqSegmented
    private val unitControl: EqSegmented
    private val contentHost = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }

    private var currentSubtab = 0
    private var units: UnitSystem = loadSavedUnits()
    private var unitAutoInitialized = hasSavedUnits()
    private var fleet: InsightsData.FleetSummary? = null
    private var selectedSessionBootId: String? = null
    private var selectedSegmentName: String? = null
    private var refreshing = false

    init {
        // Header bar: vehicle subtitle on the left, Metric/US switcher + Refresh on the right
        val topBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, EqTheme.px(4f), 0, EqTheme.px(16f))
        }
        topBar.addView(subtitleView.apply {
            text = "Loading head-unit telemetry captures…"
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = EqTheme.px(16f) })

        unitControl = EqSegmented(act, listOf("US (mi · °F)", "Metric (km · °C)"),
            if (units == UnitSystem.IMPERIAL) 0 else 1
        ) { idx ->
            units = if (idx == 0) UnitSystem.IMPERIAL else UnitSystem.METRIC
            saveUnits(units)
            unitAutoInitialized = true
            renderCurrent()
        }
        topBar.addView(unitControl, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = EqTheme.px(12f) })
        topBar.addView(eqButton(act, "Refresh", EqButtonStyle.SECONDARY) { refresh() }.apply {
            minWidth = EqTheme.px(140f)
            minHeight = EqTheme.px(56f)
            setPadding(EqTheme.px(24f), 0, EqTheme.px(24f), 0)
        })
        view.addView(topBar, LinearLayout.LayoutParams(-1, -2))

        // Sub-navigation segmented bar
        subtabControl = EqSegmented(
            act,
            listOf("Overview", "Drives & Charging", "Signals", "Segments"),
            currentSubtab,
        ) { idx ->
            currentSubtab = idx
            renderCurrent()
        }
        view.addView(subtabControl, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = EqTheme.px(12f)
        })

        view.addView(contentHost, LinearLayout.LayoutParams(-1, -2))
        refresh()
    }

    fun selectSubtab(index: Int) {
        val clamped = index.coerceIn(0, 3)
        currentSubtab = clamped
        subtabControl.setSelected(clamped, animate = false)
        renderCurrent()
    }

    fun refresh() {
        if (refreshing) return
        refreshing = true
        subtitleView.text = "Scanning telemetry segments…"
        thread(name = "insights-load", isDaemon = true) {
            val segs = InsightsData.loadSegments(dataRoot)
            val summary = InsightsData.summarize(segs)
            act.runOnUiThread {
                refreshing = false
                if (act.isFinishing) return@runOnUiThread
                fleet = summary
                if (!unitAutoInitialized && segs.isNotEmpty()) {
                    units = summary.vehicle.preferredUnits
                    unitControl.setSelected(if (units == UnitSystem.IMPERIAL) 0 else 1, animate = false)
                    unitAutoInitialized = true
                }
                if (selectedSessionBootId == null || summary.sessions.none { it.bootId == selectedSessionBootId }) {
                    // Default to most recent DRIVE session, or last session if none
                    selectedSessionBootId = summary.drives.lastOrNull()?.bootId
                        ?: summary.sessions.lastOrNull()?.bootId
                }
                if (selectedSegmentName == null || summary.segments.none { it.name == selectedSegmentName }) {
                    selectedSegmentName = summary.segments.lastOrNull()?.name
                }
                renderCurrent()
            }
        }
    }

    private fun renderCurrent() {
        contentHost.removeAllViews()
        val f = fleet
        if (f == null || f.segments.isEmpty()) {
            subtitleView.text = "No vehicle telemetry segments captured yet"
            val emptyCard = EqCard(act)
            emptyCard.addRow(EqRow(
                act,
                "No probe segments on disk yet",
                "Enable 'EV Insights auto-upload' in Advanced or run a Vehicle Telemetry Probe when parked, then tap Refresh.",
            ))
            contentHost.addView(emptyCard)
            return
        }

        val v = f.vehicle
        val capKwh = "${"%.1f".format(Locale.US, v.capacityWh / 1000.0)} kWh pack"
        val limitStr = v.chargeLimitPct?.let { " · ${it.roundToInt()}% charge target" } ?: ""
        subtitleView.text = "${v.displayTitle} · $capKwh$limitStr · ${f.segments.size} segments (${f.sessions.size} sessions)"

        when (currentSubtab) {
            0 -> renderOverview(f)
            1 -> renderDrivesAndCharging(f)
            2 -> renderSignals(f)
            3 -> renderSegments(f)
        }
    }

    // =============================================================================================
    // Subtab 0: OVERVIEW
    // =============================================================================================

    private fun renderOverview(f: InsightsData.FleetSummary) {
        val latest = f.segments.last()
        val capWh = f.vehicle.capacityWh
        val battWh = latest.value(InsightsData.BATTERY_WH)
        val rangeM = latest.value(InsightsData.RANGE_M)
        val speedMps = latest.value(InsightsData.SPEED_MPS)
        val chargeMw = latest.value(InsightsData.CHARGE_MW)
        val tempC = latest.value(InsightsData.OUTSIDE_C)
        val gearCode = latest.value(InsightsData.GEAR)?.toInt()

        contentHost.addView(eqSectionLabel(act, "Latest Vehicle State — ${InsightsData.formatWallShort(latest.startWall)}"))
        val stateCard = EqCard(act)

        // Battery SoC progress gauge at top of state card
        if (battWh != null && capWh > 1000.0) {
            val socPct = (battWh / capWh * 100.0).coerceIn(0.0, 100.0)
            val targetPct = f.vehicle.chargeLimitPct ?: 80.0
            stateCard.addRow(SocGaugeView(
                act,
                socPct = socPct,
                energyKwh = battWh / 1000.0,
                capacityKwh = capWh / 1000.0,
                targetPct = targetPct,
                rangeText = InsightsData.formatRange(rangeM, units),
                powerKw = (chargeMw ?: 0.0) / 1e6,
                portConnected = latest.portConnected,
            ))
        }

        val gearText = buildString {
            append(InsightsData.gearName(gearCode))
            if (latest.boolValues[InsightsData.PARKING_BRAKE] == true) append(" · Park Brake")
            if (latest.portConnected) append(" · Plugged In")
            else if (latest.portOpen) append(" · Port Open")
        }
        val latestSession = f.sessions.lastOrNull()
        val sessionDistText = latestSession?.preferredDistanceM?.let { InsightsData.formatDistance(it, units) } ?: "—"
        val sessionEffText = latestSession?.whPerKm?.let { InsightsData.formatEfficiency(it, units) } ?: "—"

        stateCard.addRow(kpiGrid(listOf(
            KpiItem("STATE OF CHARGE", InsightsData.formatSoc(battWh, capWh),
                "${InsightsData.formatEnergyKwh(battWh)} / ${"%.1f".format(Locale.US, capWh / 1000.0)} kWh", EqTheme.ACCENT),
            KpiItem("EST. RANGE (GOM)", InsightsData.formatRange(rangeM, units),
                if (battWh != null && battWh > 1000 && rangeM != null && rangeM > 0)
                    "Implied ${InsightsData.formatEfficiency(battWh / (rangeM / 1000.0), units)}"
                else "Vehicle estimate", EqTheme.OK),
            KpiItem("INSTANT POWER", InsightsData.formatCharge(chargeMw),
                when {
                    (chargeMw ?: 0.0) > 500_000 -> "Charging / Regen (+)"
                    (chargeMw ?: 0.0) < -500_000 -> "Discharging (-)"
                    else -> "Idle / Standby"
                }, if ((chargeMw ?: 0.0) >= 0) EqTheme.OK else EqTheme.WARN),
            KpiItem("SPEED & GEAR", InsightsData.formatSpeed(speedMps, units), gearText, EqTheme.TEXT),
            KpiItem("OUTSIDE TEMP", InsightsData.formatTemp(tempC, units),
                latestSession?.let { s ->
                    if (s.tempMinC != null && s.tempMaxC != null)
                        "Session ${InsightsData.formatTemp(s.tempMinC, units)} … ${InsightsData.formatTemp(s.tempMaxC, units)}"
                    else "Exterior ambient"
                } ?: "Exterior ambient", EqTheme.TEXT),
            KpiItem("LATEST SESSION", sessionDistText,
                "Efficiency: $sessionEffText", EqTheme.ACCENT),
        ), columns = 3))
        contentHost.addView(stateCard)

        // All-Time / Multi-Session Rollup
        contentHost.addView(eqSectionLabel(act, "Captured Fleet Summary (${f.drives.size} Drives · ${f.charges.size} Charges)"))
        val rollupCard = EqCard(act)
        val regenPctStr = f.overallRegenRatio?.let { "${"%.1f".format(Locale.US, it * 100.0)}% recovered" } ?: "—"
        val alternateEff = f.overallWhPerKm?.let { wpk ->
            if (units == UnitSystem.IMPERIAL) "${"%.0f".format(Locale.US, wpk)} Wh/km"
            else "${"%.2f".format(Locale.US, 621.371 / wpk)} mi/kWh"
        } ?: "Across all drives"

        rollupCard.addRow(kpiGrid(listOf(
            KpiItem("TOTAL DISTANCE", InsightsData.formatDistance(f.totalDistanceM, units),
                "${f.drives.size} drive sessions · ${InsightsData.formatDuration(f.totalDrivingSec)}", EqTheme.ACCENT),
            KpiItem("AVG EFFICIENCY", InsightsData.formatEfficiency(f.overallWhPerKm, units),
                alternateEff, EqTheme.OK),
            KpiItem("ENERGY DISCHARGED", "${"%.1f".format(Locale.US, f.totalDischargedKwh)} kWh",
                "Gross traction & accessory draw", EqTheme.WARN),
            KpiItem("REGEN RECOVERED", "${"%.1f".format(Locale.US, f.totalRegenKwh)} kWh",
                regenPctStr, EqTheme.OK),
            KpiItem("PLUGGED CHARGING", "${"%.1f".format(Locale.US, f.totalChargedKwh)} kWh",
                "${f.charges.size} charge session(s)", EqTheme.ACCENT),
            KpiItem("TELEMETRY SAMPLES", "${"%,d".format(Locale.US, f.totalEvents)}",
                "${f.segments.size} segments · ${f.totalGpsFixes} GPS fixes", EqTheme.TEXT),
        ), columns = 3))
        contentHost.addView(rollupCard)

        // Speed Band Distribution Bar across all drives
        if (f.speedBands.totalM > 100.0) {
            contentHost.addView(eqSectionLabel(act, "Driving Speed Distribution"))
            val bandCard = EqCard(act)
            bandCard.addRow(SpeedBandsView(act, f.speedBands, units))
            contentHost.addView(bandCard)
        }

        // Multi-segment trend chart: Battery SoC% & Range across all captured segments
        val battTrend = f.segments.mapIndexedNotNull { idx, s ->
            s.value(InsightsData.BATTERY_WH)?.let { wh ->
                InsightsData.SeriesPoint(idx.toDouble(), (wh / capWh * 100.0).coerceIn(0.0, 100.0))
            }
        }
        val rangeTrend = f.segments.mapIndexedNotNull { idx, s ->
            s.value(InsightsData.RANGE_M)?.let { m ->
                val v = if (units == UnitSystem.IMPERIAL) m / 1609.344 else m / 1000.0
                InsightsData.SeriesPoint(idx.toDouble(), v)
            }
        }
        if (battTrend.size >= 2) {
            contentHost.addView(eqSectionLabel(act, "State of Charge & Range Across All Segments (${f.segments.size})"))
            val trendCard = EqCard(act)
            val rUnit = if (units == UnitSystem.IMPERIAL) "mi" else "km"
            trendCard.addRow(chartBlock(
                title = "SoC (%) & Estimated Range ($rUnit) by Segment",
                subtitle = "From ${InsightsData.formatWallShort(f.segments.first().startWall)} to ${InsightsData.formatWallShort(f.segments.last().startWall)}",
                a = battTrend,
                b = rangeTrend,
                labelA = "SoC %",
                labelB = "Range ($rUnit)",
                xUnitSuffix = "seg",
            ))
            contentHost.addView(trendCard)
        }

        // Distance Source Cross-Validation Card (explaining why WHEEL_TICK is primary on Equinox EV)
        val totalWheelM = f.drives.sumOf { it.distanceWheelM ?: 0.0 }
        val totalSpeedM = f.drives.sumOf { it.distanceSpeedM ?: 0.0 }
        val totalGpsM = f.drives.sumOf { it.distanceGpsM ?: 0.0 }
        if (totalWheelM > 0.0 || totalSpeedM > 0.0) {
            contentHost.addView(eqSectionLabel(act, "Distance Sensor Cross-Validation"))
            val distCard = EqCard(act)
            val divPct = if (totalWheelM > 0 && totalSpeedM > 0) {
                abs(totalWheelM - totalSpeedM) / max(totalWheelM, totalSpeedM) * 100.0
            } else 0.0
            distCard.addRow(EqRow(
                act,
                "Wheel ticks (WHEEL_TICK · 24.145 mm/tick)",
                "Primary distance source · 4-wheel average · ${"%.2f".format(Locale.US, divPct)}% divergence vs speed integral",
                trailing = EqTheme.text(act, 20f, EqTheme.OK, EqTheme.Weight.MEDIUM).apply {
                    text = InsightsData.formatDistance(totalWheelM, units)
                },
            ))
            distCard.addRow(EqRow(
                act,
                "Speed integral (PERF_VEHICLE_SPEED)",
                "Trapezoidal integration of signed vehicle speed callbacks",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT, EqTheme.Weight.REGULAR).apply {
                    text = InsightsData.formatDistance(totalSpeedM, units)
                },
            ))
            distCard.addRow(EqRow(
                act,
                "GPS haversine (ACCESS_FINE_LOCATION)",
                if (totalGpsM > 0) "Filtered to fixes with accuracy ≤ 50 m" else "No raw GPS fixes in current segments",
                trailing = EqTheme.text(act, 20f, EqTheme.TEXT_DIM, EqTheme.Weight.REGULAR).apply {
                    text = if (totalGpsM > 0) InsightsData.formatDistance(totalGpsM, units) else "—"
                },
            ))
            distCard.addRow(EqRow(
                act,
                "Hardware odometer (PERF_ODOMETER)",
                "Locked by GM signature permission CAR_MILEAGE on 3rd-party apps; replaced by WHEEL_TICK",
                trailing = EqTheme.text(act, 16f, EqTheme.WARN, EqTheme.Weight.MEDIUM).apply {
                    text = "PRIVILEGED"
                },
            ))
            contentHost.addView(distCard)
        }
    }

    // =============================================================================================
    // Subtab 1: DRIVES & CHARGING
    // =============================================================================================

    private fun renderDrivesAndCharging(f: InsightsData.FleetSummary) {
        val activeSession = f.sessions.firstOrNull { it.bootId == selectedSessionBootId }
            ?: f.drives.lastOrNull()
            ?: f.sessions.lastOrNull()

        if (activeSession != null) {
            selectedSessionBootId = activeSession.bootId
            renderSelectedSessionDetail(activeSession)
        }

        // List of all sessions (newest first) so user can tap any drive or charge session to inspect
        contentHost.addView(eqSectionLabel(act, "All Sessions (${f.sessions.size}) — Tap any session to inspect charts"))
        val sessionListCard = EqCard(act)
        for (s in f.sessions.reversed()) {
            val isSelected = s.bootId == activeSession?.bootId
            val badge = when (s.kind) {
                InsightsData.SessionKind.DRIVE -> "DRIVE"
                InsightsData.SessionKind.CHARGE -> "CHARGE"
                InsightsData.SessionKind.PARKED -> "PARKED"
            }
            val title = buildString {
                append("[$badge]  ${InsightsData.formatWallShort(s.startWall)}")
                if (s.kind == InsightsData.SessionKind.DRIVE) {
                    append("  ·  ${InsightsData.formatDistance(s.preferredDistanceM, units)}")
                } else if (s.kind == InsightsData.SessionKind.CHARGE) {
                    val gained = -(s.netEnergyWh ?: 0.0)
                    append("  ·  +${"%.1f".format(Locale.US, max(gained, s.grossRegenWh) / 1000.0)} kWh")
                }
            }
            val sub = buildString {
                append("${InsightsData.formatDuration(s.durationSec)} (${s.segments.size} segs)")
                val s0 = s.startSocPct
                val s1 = s.endSocPct
                if (s0 != null && s1 != null) {
                    append(" · SoC ${"%.1f".format(Locale.US, s0)}% → ${"%.1f".format(Locale.US, s1)}%")
                }
                if (s.kind == InsightsData.SessionKind.DRIVE) {
                    s.whPerKm?.let { append(" · ${InsightsData.formatEfficiency(it, units)}") }
                    append(" · max ${InsightsData.formatSpeed(s.maxSpeedMps, units)}")
                } else if (s.peakChargeKw > 0.2) {
                    append(" · peak +${"%.1f".format(Locale.US, s.peakChargeKw)} kW")
                }
            }
            val rightLabel = if (isSelected) "ACTIVE" else "Inspect"
            val rightColor = if (isSelected) EqTheme.ACCENT else EqTheme.TEXT_DIM
            sessionListCard.addRow(EqRow(
                act,
                title = title,
                subtitle = sub,
                titleColor = if (isSelected) EqTheme.ACCENT else EqTheme.TEXT,
                trailing = EqTheme.text(act, 16f, rightColor, EqTheme.Weight.MEDIUM).apply { text = rightLabel },
                onClick = {
                    selectedSessionBootId = s.bootId
                    renderCurrent()
                },
            ))
        }
        contentHost.addView(sessionListCard)
    }

    private fun renderSelectedSessionDetail(s: InsightsData.SessionSummary) {
        val kindLabel = when (s.kind) {
            InsightsData.SessionKind.DRIVE -> "Drive Session"
            InsightsData.SessionKind.CHARGE -> "Charging Session"
            InsightsData.SessionKind.PARKED -> "Parked Session"
        }
        contentHost.addView(eqSectionLabel(
            act,
            "Selected $kindLabel — ${InsightsData.formatWallShort(s.startWall)} (${s.segments.size} segments)",
        ))
        val detailCard = EqCard(act)

        val socSpan = if (s.startSocPct != null && s.endSocPct != null) {
            val delta = s.endSocPct!! - s.startSocPct!!
            val sign = if (delta > 0) "+" else ""
            "${"%.1f".format(Locale.US, s.startSocPct)}% → ${"%.1f".format(Locale.US, s.endSocPct)}% ($sign${"%.1f".format(Locale.US, delta)}%)"
        } else "—"

        val netEnergyStr = s.netEnergyWh?.let { net ->
            if (net >= 0) "${"%.2f".format(Locale.US, net / 1000.0)} kWh used"
            else "+${"%.2f".format(Locale.US, -net / 1000.0)} kWh gained"
        } ?: "—"

        val gomAccuracyStr = s.rangeEfficiencyRatio?.let { ratio ->
            "${"%.0f".format(Locale.US, ratio * 100.0)}% of GOM (${if (ratio >= 1.0) "beat est." else "under est."})"
        } ?: s.rangeDeltaM?.let { d ->
            "Range Δ ${InsightsData.formatDistance(d, units)}"
        } ?: "—"

        val regenStr = buildString {
            append("${"%.2f".format(Locale.US, s.grossRegenWh / 1000.0)} kWh")
            s.regenRecoveryRatio?.let { r -> append(" (${"%.0f".format(Locale.US, r * 100.0)}%)") }
        }

        detailCard.addRow(kpiGrid(listOf(
            KpiItem("DISTANCE (${s.preferredDistanceSource})", InsightsData.formatDistance(s.preferredDistanceM, units),
                "Duration ${InsightsData.formatDuration(s.durationSec)} · Moving ${InsightsData.formatDuration(s.movingSec)}", EqTheme.ACCENT),
            KpiItem("EFFICIENCY", InsightsData.formatEfficiency(s.whPerKm, units),
                s.whPerKm?.let { wpk ->
                    if (units == UnitSystem.IMPERIAL) "${"%.0f".format(Locale.US, wpk)} Wh/km"
                    else "${"%.2f".format(Locale.US, 621.371 / wpk)} mi/kWh"
                } ?: "Requires ≥ 0.5 km drive", EqTheme.OK),
            KpiItem("BATTERY Δ", socSpan, netEnergyStr, EqTheme.ACCENT),
            KpiItem("TRACTION & REGEN", "-${"%.2f".format(Locale.US, s.grossDischargeWh / 1000.0)} kWh",
                "Regen: +$regenStr", EqTheme.WARN),
            KpiItem("SPEED & STOPS", "Max ${InsightsData.formatSpeed(s.maxSpeedMps, units)}",
                "Avg moving ${InsightsData.formatSpeed(s.avgMovingSpeedMps, units)} · ${s.stopCount} stops", EqTheme.TEXT),
            KpiItem("PEAK POWER & GOM", "-${"%.1f".format(Locale.US, s.peakDischargeKw)} / +${"%.1f".format(Locale.US, s.peakChargeKw)} kW",
                gomAccuracyStr, EqTheme.TEXT),
        ), columns = 3))

        if (s.speedBands.totalM > 50.0) {
            detailCard.addRow(SpeedBandsView(act, s.speedBands, units))
        }
        contentHost.addView(detailCard)

        // Session time-series charts
        val chartsCard = EqCard(act)
        val capWh = s.capacityWh.coerceAtLeast(1000.0)
        val socSeries = (s.series[InsightsData.BATTERY_WH] ?: emptyList()).map {
            it.copy(value = (it.value / capWh * 100.0).coerceIn(0.0, 100.0))
        }
        val rUnit = if (units == UnitSystem.IMPERIAL) "mi" else "km"
        val rangeSeries = (s.series[InsightsData.RANGE_M] ?: emptyList()).map {
            it.copy(value = if (units == UnitSystem.IMPERIAL) it.value / 1609.344 else it.value / 1000.0)
        }
        chartsCard.addRow(chartBlock(
            title = "State of Charge (%) & Estimated Range ($rUnit)",
            subtitle = "Continuous session trace across ${s.segments.size} segment(s)",
            a = socSeries,
            b = rangeSeries,
            labelA = "SoC %",
            labelB = "Range ($rUnit)",
            xUnitSuffix = "s",
        ))

        val sUnit = if (units == UnitSystem.IMPERIAL) "mph" else "km/h"
        val speedSeries = (s.series[InsightsData.SPEED_MPS] ?: emptyList()).map {
            it.copy(value = abs(it.value) * if (units == UnitSystem.IMPERIAL) 2.236936 else 3.6)
        }
        val powerSeries = (s.series[InsightsData.CHARGE_MW] ?: emptyList()).map {
            it.copy(value = it.value / 1e6) // kW
        }
        chartsCard.addRow(chartBlock(
            title = "Vehicle Speed ($sUnit) & Pack Power (kW)",
            subtitle = "Negative kW = traction discharge · Positive kW = regen / charging",
            a = speedSeries,
            b = powerSeries,
            labelA = "Speed ($sUnit)",
            labelB = "Power (kW)",
            xUnitSuffix = "s",
        ))

        if (s.gpsPoints.size >= 2) {
            chartsCard.addRow(routeBlock(s))
        }
        contentHost.addView(chartsCard)
    }

    // =============================================================================================
    // Subtab 2: SIGNALS & PERMISSIONS
    // =============================================================================================

    private fun renderSignals(f: InsightsData.FleetSummary) {
        val latest = f.segments.last()
        val signals = latest.signals
        val perms = latest.permissions

        val subscribable = signals.count { it.result == "SUBSCRIBABLE" }
        val readable = signals.count { it.result == "READABLE" || it.result == "SUBSCRIBABLE" }
        val grantedPerms = perms.count { it.granted }

        contentHost.addView(eqSectionLabel(act, "VHAL Census & Signal Health (${latest.name})"))
        val summaryCard = EqCard(act)
        summaryCard.addRow(kpiGrid(listOf(
            KpiItem("VISIBLE PROPERTIES", "${signals.Count { it.changeMode != "ABSENT" }}",
                "$readable readable · $subscribable actively streaming", EqTheme.ACCENT),
            KpiItem("GRANTED PERMISSIONS", "$grantedPerms / ${perms.size}",
                "${perms.size - grantedPerms} OEM signature-locked", EqTheme.OK),
            KpiItem("SESSION CALLBACKS", "${"%,d".format(Locale.US, latest.cumulativeStatEvents)}",
                "In segment ${latest.name}: ${"%,d".format(Locale.US, latest.events)} events", EqTheme.TEXT),
        ), columns = 3))
        contentHost.addView(summaryCard)

        contentHost.addView(eqSectionLabel(act, "Active & Readable Vehicle Signals (${signals.size})"))
        val sigCard = EqCard(act)
        for (sig in signals) {
            val badgeColor = when (sig.result) {
                "SUBSCRIBABLE" -> EqTheme.OK
                "READABLE" -> EqTheme.ACCENT
                "ABSENT_FROM_PROPERTY_LIST" -> EqTheme.WARN
                else -> EqTheme.TEXT_FAINT
            }
            val hzStr = if (sig.observedHz >= 0.05) "${"%.2f".format(Locale.US, sig.observedHz)} Hz"
            else if (sig.callbacks > 0) "${sig.callbacks} cb"
            else sig.changeMode
            val rangeStr = if (sig.minVal != null && sig.maxVal != null) {
                " · min/max ${fmtShort(sig.minVal)}…${fmtShort(sig.maxVal)}"
            } else ""
            val sub = "${ProbeModelHex(sig.id)} · ${sig.result} · $hzStr$rangeStr"

            val rightBox = LinearLayout(act).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                addView(EqTheme.text(act, 18f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply {
                    text = sig.lastText.take(28)
                })
                addView(EqTheme.text(act, 13f, badgeColor, EqTheme.Weight.MEDIUM).apply {
                    text = if (sig.callbacks > 0) "${"%,d".format(Locale.US, sig.callbacks)} callbacks" else sig.changeMode
                })
            }
            sigCard.addRow(EqRow(act, sig.name, sub, trailing = rightBox))
        }
        contentHost.addView(sigCard)

        if (perms.isNotEmpty()) {
            contentHost.addView(eqSectionLabel(act, "Automotive Permission Census (${perms.size})"))
            val permCard = EqCard(act)
            for (p in perms) {
                val color = if (p.granted) EqTheme.OK else EqTheme.WARN
                permCard.addRow(EqRow(
                    act,
                    p.name,
                    p.classification,
                    trailing = EqTheme.text(act, 16f, color, EqTheme.Weight.MEDIUM).apply {
                        text = if (p.granted) "GRANTED" else "PRIVILEGED"
                    },
                ))
            }
            contentHost.addView(permCard)
        }
    }

    private inline fun <T> List<T>.Count(predicate: (T) -> Boolean): Int = this.count(predicate)
    private fun ProbeModelHex(id: Int): String = String.format(Locale.ROOT, "0x%08X", id)

    // =============================================================================================
    // Subtab 3: SEGMENTS
    // =============================================================================================

    private fun renderSegments(f: InsightsData.FleetSummary) {
        val activeSeg = f.segments.firstOrNull { it.name == selectedSegmentName } ?: f.segments.last()
        selectedSegmentName = activeSeg.name

        contentHost.addView(eqSectionLabel(act, "Selected Segment — ${activeSeg.name}"))
        val segCard = EqCard(act)
        val capWh = f.vehicle.capacityWh
        val battWh = activeSeg.value(InsightsData.BATTERY_WH)
        segCard.addRow(kpiGrid(listOf(
            KpiItem("SEGMENT WINDOW", InsightsData.formatWallShort(activeSeg.startWall),
                "Duration ${InsightsData.formatDuration(activeSeg.durationSec)} · ${activeSeg.stopReason ?: "active/completed"}", EqTheme.ACCENT),
            KpiItem("SEGMENT DISTANCE", InsightsData.formatDistance(activeSeg.preferredDistanceM, units),
                "Source: ${activeSeg.preferredDistanceSource} · Max ${InsightsData.formatSpeed(activeSeg.maxSpeedMps, units)}", EqTheme.OK),
            KpiItem("BATTERY & POWER", InsightsData.formatBatteryWithSoc(battWh, capWh),
                "Peak -${"%.1f".format(Locale.US, activeSeg.peakDischargeKw)} / +${"%.1f".format(Locale.US, activeSeg.peakChargeKw)} kW", EqTheme.TEXT),
        ), columns = 3))

        val rUnit = if (units == UnitSystem.IMPERIAL) "mi" else "km"
        val sUnit = if (units == UnitSystem.IMPERIAL) "mph" else "km/h"
        segCard.addRow(chartBlock(
            title = "Segment Battery (Wh) & Range ($rUnit)",
            subtitle = "${activeSeg.events} live callbacks in ${activeSeg.name}",
            a = activeSeg.series[InsightsData.BATTERY_WH] ?: emptyList(),
            b = (activeSeg.series[InsightsData.RANGE_M] ?: emptyList()).map {
                it.copy(value = if (units == UnitSystem.IMPERIAL) it.value / 1609.344 else it.value / 1000.0)
            },
            labelA = "Battery (Wh)",
            labelB = "Range ($rUnit)",
            xUnitSuffix = "s",
        ))
        segCard.addRow(chartBlock(
            title = "Segment Speed ($sUnit) & Power (kW)",
            subtitle = "High-resolution 1–5 Hz callback stream",
            a = (activeSeg.series[InsightsData.SPEED_MPS] ?: emptyList()).map {
                it.copy(value = abs(it.value) * if (units == UnitSystem.IMPERIAL) 2.236936 else 3.6)
            },
            b = (activeSeg.series[InsightsData.CHARGE_MW] ?: emptyList()).map {
                it.copy(value = it.value / 1e6)
            },
            labelA = "Speed ($sUnit)",
            labelB = "Power (kW)",
            xUnitSuffix = "s",
        ))
        contentHost.addView(segCard)

        val recent = f.segments.takeLast(30).reversed()
        contentHost.addView(eqSectionLabel(act, "Recent Segments (Showing ${recent.size} of ${f.segments.size}) — Tap to inspect"))
        val listCard = EqCard(act)
        for (s in recent) {
            val isSel = s.name == activeSeg.name
            val segDir = File(dataRoot, s.name)
            val uploadState = when {
                File(segDir, ".uploaded").isFile -> "Uploaded"
                File(segDir, ".pending_upload").isFile -> "Pending"
                else -> "Local"
            }
            val sub = "${InsightsData.formatWallShort(s.startWall)} · ${InsightsData.formatDuration(s.durationSec)} · " +
                "${s.events} events · ${InsightsData.formatDistance(s.preferredDistanceM, units)} · " +
                "SoC ${InsightsData.formatSoc(s.value(InsightsData.BATTERY_WH), capWh)} · $uploadState"
            listCard.addRow(EqRow(
                act,
                title = s.name,
                subtitle = sub,
                titleColor = if (isSel) EqTheme.ACCENT else EqTheme.TEXT,
                trailing = EqTheme.text(act, 16f, if (isSel) EqTheme.ACCENT else EqTheme.TEXT_DIM, EqTheme.Weight.MEDIUM).apply {
                    text = if (isSel) "ACTIVE" else "View"
                },
                onClick = {
                    selectedSegmentName = s.name
                    renderCurrent()
                },
            ))
        }
        contentHost.addView(listCard)
    }

    // =============================================================================================
    // Reusable UI building blocks
    // =============================================================================================

    private data class KpiItem(
        val label: String,
        val primary: String,
        val secondary: String,
        val valueColor: Int = EqTheme.TEXT,
    )

    private fun kpiGrid(items: List<KpiItem>, columns: Int = 3): LinearLayout =
        LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(EqTheme.px(28f), EqTheme.px(16f), EqTheme.px(24f), EqTheme.px(16f))
            for (row in items.chunked(columns)) {
                addView(LinearLayout(act).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (item in row) {
                        addView(LinearLayout(act).apply {
                            orientation = LinearLayout.VERTICAL
                            setPadding(0, EqTheme.px(8f), EqTheme.px(20f), EqTheme.px(8f))
                            addView(EqTheme.text(act, 13f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
                                text = item.label
                                letterSpacing = 0.12f
                            })
                            addView(EqTheme.text(act, 25f, item.valueColor, EqTheme.Weight.LIGHT).apply {
                                text = item.primary
                            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(4f) })
                            addView(EqTheme.text(act, 14f, EqTheme.TEXT_DIM, EqTheme.Weight.REGULAR).apply {
                                text = item.secondary
                            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(2f) })
                        }, LinearLayout.LayoutParams(0, -2, 1f))
                    }
                    // Pad incomplete trailing row
                    repeat((columns - row.size).coerceAtLeast(0)) {
                        addView(View(act), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                })
            }
        }

    private fun chartBlock(
        title: String,
        subtitle: String,
        a: List<InsightsData.SeriesPoint>,
        b: List<InsightsData.SeriesPoint>,
        labelA: String,
        labelB: String,
        xUnitSuffix: String = "s",
    ): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(EqTheme.px(28f), EqTheme.px(18f), EqTheme.px(28f), EqTheme.px(20f))

        val header = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titles = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            addView(EqTheme.text(act, 20f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply { text = title })
            addView(EqTheme.text(act, 14f, EqTheme.TEXT_DIM).apply { text = subtitle },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(2f) })
        }
        header.addView(titles, LinearLayout.LayoutParams(0, -2, 1f))

        val legendBox = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            if (a.isNotEmpty()) {
                addView(EqTheme.text(act, 14f, EqTheme.ACCENT, EqTheme.Weight.MEDIUM).apply {
                    text = "● $labelA (${fmtShort(a.minOf { it.value })}…${fmtShort(a.maxOf { it.value })})"
                })
            }
            if (b.isNotEmpty()) {
                addView(EqTheme.text(act, 14f, EqTheme.OK, EqTheme.Weight.MEDIUM).apply {
                    text = "● $labelB (${fmtShort(b.minOf { it.value })}…${fmtShort(b.maxOf { it.value })})"
                    setPadding(EqTheme.px(18f), 0, 0, 0)
                })
            }
        }
        header.addView(legendBox, LinearLayout.LayoutParams(-2, -2))
        addView(header, LinearLayout.LayoutParams(-1, -2))

        addView(
            InsightChartView(act).apply { setData(a, b, xUnitSuffix) },
            LinearLayout.LayoutParams(-1, EqTheme.px(270f)).apply { topMargin = EqTheme.px(10f) },
        )
    }

    private fun routeBlock(s: InsightsData.SessionSummary): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(EqTheme.px(28f), EqTheme.px(18f), EqTheme.px(28f), EqTheme.px(20f))
        addView(EqTheme.text(act, 20f, EqTheme.TEXT, EqTheme.Weight.MEDIUM).apply {
            text = "GPS Route Geometry (${s.gpsPoints.size} fixes)"
        })
        addView(EqTheme.text(act, 14f, EqTheme.TEXT_DIM).apply {
            text = "Colored by speed · Green = start · Blue = end · Distance ${InsightsData.formatDistance(s.distanceGpsM, units)}"
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = EqTheme.px(2f) })
        addView(
            RouteTraceView(act, s.gpsPoints),
            LinearLayout.LayoutParams(-1, EqTheme.px(240f)).apply { topMargin = EqTheme.px(10f) },
        )
    }

    private fun hasSavedUnits(): Boolean =
        act.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).contains(KEY_UNITS)

    private fun loadSavedUnits(): UnitSystem {
        val raw = act.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_UNITS, null)
        return if (raw == "METRIC") UnitSystem.METRIC else UnitSystem.IMPERIAL
    }

    private fun saveUnits(u: UnitSystem) {
        act.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_UNITS, u.name).apply()
    }

    companion object {
        private const val PREFS_NAME = "ev_insights_ui_prefs"
        private const val KEY_UNITS = "unit_system"
    }
}

// =================================================================================================
// Custom Canvas Views for Insights
// =================================================================================================

/** Horizontal battery State-of-Charge bar with 80% target tick and live range/power readout. */
internal class SocGaugeView(
    ctx: Context,
    private val socPct: Double,
    private val energyKwh: Double,
    private val capacityKwh: Double,
    private val targetPct: Double,
    private val rangeText: String,
    private val powerKw: Double,
    private val portConnected: Boolean,
) : View(ctx) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1FFFFFFF }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val targetPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.WARN
        strokeWidth = EqTheme.pxf(2.5f)
        style = Paint.Style.STROKE
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val subPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_DIM
        typeface = EqTheme.Weight.REGULAR.face
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(EqTheme.px(800f), widthMeasureSpec)
        val h = EqTheme.px(104f)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val padX = EqTheme.pxf(28f)
        val topY = EqTheme.pxf(18f)
        val w = width.toFloat()

        titlePaint.textSize = EqTheme.pxf(20f)
        subPaint.textSize = EqTheme.pxf(15f)

        val leftLabel = "${"%.1f".format(Locale.US, socPct)}% SoC  ·  ${"%.1f".format(Locale.US, energyKwh)} / ${"%.1f".format(Locale.US, capacityKwh)} kWh"
        canvas.drawText(leftLabel, padX, topY + EqTheme.pxf(18f), titlePaint)

        val statusRight = buildString {
            append("Est. $rangeText")
            if (portConnected || abs(powerKw) >= 0.2) {
                val sign = if (powerKw >= 0) "+" else ""
                append("  ·  $sign${"%.1f".format(Locale.US, powerKw)} kW")
            }
            if (targetPct in 50.0..100.0) {
                append("  ·  Target ${targetPct.roundToInt()}%")
            }
        }
        val rw = subPaint.measureText(statusRight)
        canvas.drawText(statusRight, w - padX - rw, topY + EqTheme.pxf(17f), subPaint)

        // Bar geometry
        val barTop = topY + EqTheme.pxf(34f)
        val barBottom = barTop + EqTheme.pxf(28f)
        val barLeft = padX
        val barRight = w - padX
        val barW = (barRight - barLeft).coerceAtLeast(1f)
        val r = (barBottom - barTop) / 2f

        rect.set(barLeft, barTop, barRight, barBottom)
        canvas.drawRoundRect(rect, r, r, trackPaint)

        val frac = (socPct / 100.0).toFloat().coerceIn(0.02f, 1f)
        val fillRight = barLeft + barW * frac
        val startCol = if (socPct < 20.0) EqTheme.DANGER else EqTheme.ACCENT
        val endCol = if (portConnected || powerKw > 0.5) EqTheme.OK else 0xFF68B5FF.toInt()
        fillPaint.shader = LinearGradient(barLeft, barTop, fillRight, barTop, startCol, endCol, Shader.TileMode.CLAMP)
        rect.set(barLeft, barTop, fillRight, barBottom)
        canvas.drawRoundRect(rect, r, r, fillPaint)

        // Draw target percentage marker line (e.g. 80%)
        if (targetPct in 10.0..99.0) {
            val tx = barLeft + barW * (targetPct / 100.0).toFloat()
            canvas.drawLine(tx, barTop - EqTheme.pxf(4f), tx, barBottom + EqTheme.pxf(4f), targetPaint)
        }
    }
}

/** Stacked horizontal bar showing distance & time across the 4 speed bands. */
internal class SpeedBandsView(
    ctx: Context,
    private val bands: InsightsData.SpeedBands,
    private val units: UnitSystem,
) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_DIM
        typeface = EqTheme.Weight.REGULAR.face
    }
    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize(EqTheme.px(800f), widthMeasureSpec)
        val h = EqTheme.px(108f)
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val padX = EqTheme.pxf(28f)
        val w = width.toFloat()
        val barLeft = padX
        val barRight = w - padX
        val barW = (barRight - barLeft).coerceAtLeast(1f)
        val barTop = EqTheme.pxf(18f)
        val barBottom = barTop + EqTheme.pxf(24f)
        val r = (barBottom - barTop) / 2f

        val totalM = bands.totalM.takeIf { it > 0.0 } ?: 1.0
        val segments = listOf(
            Triple(if (units == UnitSystem.IMPERIAL) "Low (<11 mph)" else "Low (<18 km/h)", bands.lowM, 0xFF5B6576.toInt()),
            Triple(if (units == UnitSystem.IMPERIAL) "City (11–34 mph)" else "City (18–54 km/h)", bands.cityM, EqTheme.ACCENT),
            Triple(if (units == UnitSystem.IMPERIAL) "Rural (34–56 mph)" else "Rural (54–90 km/h)", bands.ruralM, EqTheme.OK),
            Triple(if (units == UnitSystem.IMPERIAL) "Hwy (56+ mph)" else "Hwy (90+ km/h)", bands.highwayM, EqTheme.WARN),
        )

        // Clip rounded track and draw proportional segments
        canvas.save()
        val clipPath = Path().apply {
            rect.set(barLeft, barTop, barRight, barBottom)
            addRoundRect(rect, r, r, Path.Direction.CW)
        }
        canvas.clipPath(clipPath)
        var curX = barLeft
        for ((_, distM, col) in segments) {
            val segW = (distM / totalM).toFloat() * barW
            if (segW > 0.5f) {
                paint.color = col
                canvas.drawRect(curX, barTop, curX + segW + 1f, barBottom, paint)
                curX += segW
            }
        }
        canvas.restore()

        // Legend row below bar
        textPaint.textSize = EqTheme.pxf(14f)
        dimPaint.textSize = EqTheme.pxf(13f)
        val colW = barW / 4f
        val legY1 = barBottom + EqTheme.pxf(24f)
        val legY2 = legY1 + EqTheme.pxf(19f)
        for ((idx, seg) in segments.withIndex()) {
            val (label, distM, col) = seg
            val x = barLeft + idx * colW
            val pct = (distM / totalM * 100.0).roundToInt()
            textPaint.color = col
            canvas.drawText("● $label", x, legY1, textPaint)
            canvas.drawText("$pct% (${InsightsData.formatDistance(distM, units)})", x + EqTheme.pxf(14f), legY2, dimPaint)
        }
    }
}

/** Dual-normalised line chart with translucent area gradient, gridlines, and axis labels. */
internal class InsightChartView(ctx: Context) : View(ctx) {
    private var a: List<InsightsData.SeriesPoint> = emptyList()
    private var b: List<InsightsData.SeriesPoint> = emptyList()
    private var xSuffix: String = "s"

    private val paintA = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4C9BFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val paintB = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4CC38A.toInt()
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(3.2f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaintA = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fillPaintB = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val paintGrid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x18FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        pathEffect = DashPathEffect(floatArrayOf(8f, 8f), 0f)
    }
    private val paintFrame = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x26FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val paintTextLeft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF78B4FF.toInt()
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val paintTextRight = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6CD9A4.toInt()
        typeface = EqTheme.Weight.MEDIUM.face
    }
    private val paintTextDim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.TEXT_FAINT
        typeface = EqTheme.Weight.REGULAR.face
    }
    private val linePath = Path()
    private val areaPath = Path()

    fun setData(
        a: List<InsightsData.SeriesPoint>,
        b: List<InsightsData.SeriesPoint>,
        xUnitSuffix: String = "s",
    ) {
        this.a = a
        this.b = b
        this.xSuffix = xUnitSuffix
        contentDescription = "Chart: ${a.size} and ${b.size} samples"
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = EqTheme.pxf(36f)
        val g = chartGeom(a, b, w, h, pad)

        paintTextLeft.textSize = EqTheme.pxf(13f)
        paintTextRight.textSize = EqTheme.pxf(13f)
        paintTextDim.textSize = EqTheme.pxf(12f)

        val top = g.pad
        val bottom = h - g.pad
        val left = g.pad
        val right = w - g.pad

        // Horizontal grid lines (25%, 50%, 75%)
        for (frac in listOf(0.25f, 0.5f, 0.75f)) {
            val y = top + (bottom - top) * frac
            canvas.drawLine(left, y, right, y, paintGrid)
        }
        // Axes frame
        canvas.drawLine(left, top, left, bottom, paintFrame)
        canvas.drawLine(left, bottom, right, bottom, paintFrame)

        if (g.ptsA.isEmpty() && g.ptsB.isEmpty()) {
            val msg = "No time-series samples in this selection"
            val mw = paintTextDim.measureText(msg)
            canvas.drawText(msg, (w - mw) / 2f, h / 2f, paintTextDim)
            return
        }

        // Area fill + stroke for Series A (blue)
        if (g.ptsA.size >= 2) {
            fillPaintA.shader = LinearGradient(0f, top, 0f, bottom, 0x384C9BFF, 0x044C9BFF, Shader.TileMode.CLAMP)
            drawPathAndArea(canvas, g.ptsA, bottom, fillPaintA, paintA)
        }
        // Area fill + stroke for Series B (green)
        if (g.ptsB.size >= 2) {
            fillPaintB.shader = LinearGradient(0f, top, 0f, bottom, 0x2E4CC38A, 0x034CC38A, Shader.TileMode.CLAMP)
            drawPathAndArea(canvas, g.ptsB, bottom, fillPaintB, paintB)
        }

        // Top min…max range badges
        if (g.labelA.isNotEmpty()) {
            canvas.drawText(g.labelA, left, top - EqTheme.pxf(8f), paintTextLeft)
        }
        if (g.labelB.isNotEmpty()) {
            val bw = paintTextRight.measureText(g.labelB)
            canvas.drawText(g.labelB, right - bw, top - EqTheme.pxf(8f), paintTextRight)
        }

        // Bottom X-axis time span labels
        val all = a + b
        if (all.isNotEmpty()) {
            val tMin = all.minOf { it.tSec }
            val tMax = all.maxOf { it.tSec }
            val startTxt = if (xSuffix == "seg") "#1" else InsightsData.formatDuration(tMin)
            val endTxt = if (xSuffix == "seg") "#${all.size}" else InsightsData.formatDuration(tMax)
            canvas.drawText(startTxt, left, bottom + EqTheme.pxf(20f), paintTextDim)
            val ew = paintTextDim.measureText(endTxt)
            canvas.drawText(endTxt, right - ew, bottom + EqTheme.pxf(20f), paintTextDim)
        }
    }

    private fun drawPathAndArea(
        canvas: Canvas,
        pts: List<Pair<Float, Float>>,
        bottomY: Float,
        fillPaint: Paint,
        strokePaint: Paint,
    ) {
        linePath.reset()
        areaPath.reset()
        val first = pts.first()
        val last = pts.last()
        linePath.moveTo(first.first, first.second)
        areaPath.moveTo(first.first, bottomY)
        areaPath.lineTo(first.first, first.second)
        for (i in 1 until pts.size) {
            val (x, y) = pts[i]
            linePath.lineTo(x, y)
            areaPath.lineTo(x, y)
        }
        areaPath.lineTo(last.first, bottomY)
        areaPath.close()
        canvas.drawPath(areaPath, fillPaint)
        canvas.drawPath(linePath, strokePaint)
    }
}

/** 2D GPS route polyline view with start/end markers, aspect-ratio preserving projection. */
internal class RouteTraceView(
    ctx: Context,
    private val pts: List<InsightsData.GpsPoint>,
) : View(ctx) {
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x10FFFFFF
        style = Paint.Style.FILL
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = EqTheme.ACCENT
        style = Paint.Style.STROKE
        strokeWidth = EqTheme.pxf(4f)
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = EqTheme.pxf(28f)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, EqTheme.pxf(16f), EqTheme.pxf(16f), bgPaint)

        val projected = routeGeom(pts, w, h, pad)
        if (projected.size < 2) return
        for (i in 1 until projected.size) {
            val (x0, y0) = projected[i - 1]
            val (x1, y1) = projected[i]
            val spd = pts[i].speedMps ?: 0.0
            routePaint.color = when {
                spd >= 25.0 -> EqTheme.WARN
                spd >= 12.0 -> EqTheme.OK
                else -> EqTheme.ACCENT
            }
            canvas.drawLine(x0, y0, x1, y1, routePaint)
        }
        // Start marker (Green) & End marker (Blue)
        val start = projected.first()
        val end = projected.last()
        markerPaint.color = EqTheme.OK
        canvas.drawCircle(start.first, start.second, EqTheme.pxf(7f), markerPaint)
        markerPaint.color = Color.WHITE
        canvas.drawCircle(end.first, end.second, EqTheme.pxf(8f), markerPaint)
        markerPaint.color = EqTheme.ACCENT
        canvas.drawCircle(end.first, end.second, EqTheme.pxf(5f), markerPaint)
    }
}

internal data class ChartGeom(
    val ptsA: List<Pair<Float, Float>>,
    val ptsB: List<Pair<Float, Float>>,
    val labelA: String,
    val labelB: String,
    val pad: Float,
)

/** Pure chart geometry: normalise each series onto its own min/max inside [w]×[h] minus [pad]. */
internal fun chartGeom(
    a: List<InsightsData.SeriesPoint>,
    b: List<InsightsData.SeriesPoint>,
    w: Float,
    h: Float,
    pad: Float,
): ChartGeom {
    fun norm(
        s: List<InsightsData.SeriesPoint>,
        tMin: Double,
        tSpan: Double,
    ): List<Pair<Float, Float>> {
        if (s.isEmpty()) return emptyList()
        val vMin = s.minOf { it.value }
        val vSpan = s.maxOf { it.value } - vMin
        return s.map { p ->
            val x = pad + ((p.tSec - tMin) / tSpan * (w - 2 * pad)).toFloat()
            // A flat series parks mid-box: bottom would read as "zero", which it is not.
            val y = if (vSpan <= 0) h / 2f
                else (h - pad - (p.value - vMin) / vSpan * (h - 2 * pad)).toFloat()
            x to y
        }
    }
    fun label(s: List<InsightsData.SeriesPoint>): String {
        if (s.isEmpty()) return ""
        val lo = s.minOf { it.value }
        val hi = s.maxOf { it.value }
        return "${fmtShort(lo)}…${fmtShort(hi)}"
    }
    val all = a + b
    val tMin = all.minOfOrNull { it.tSec } ?: 0.0
    val tSpan = ((all.maxOfOrNull { it.tSec } ?: 0.0) - tMin).takeIf { it > 0 } ?: 1.0
    return ChartGeom(norm(a, tMin, tSpan), norm(b, tMin, tSpan), label(a), label(b), pad)
}

/** Pure aspect-preserving 2D projection of GPS coordinates into [w]×[h] minus [pad]. */
internal fun routeGeom(
    pts: List<InsightsData.GpsPoint>,
    w: Float,
    h: Float,
    pad: Float,
): List<Pair<Float, Float>> {
    if (pts.isEmpty()) return emptyList()
    val minLat = pts.minOf { it.lat }
    val maxLat = pts.maxOf { it.lat }
    val minLon = pts.minOf { it.lon }
    val maxLon = pts.maxOf { it.lon }
    val midLatRad = ((minLat + maxLat) / 2.0) * Math.PI / 180.0
    val cosLat = kotlin.math.cos(midLatRad).coerceAtLeast(0.1)
    val spanLat = (maxLat - minLat).coerceAtLeast(1e-5)
    val spanLonScaled = ((maxLon - minLon) * cosLat).coerceAtLeast(1e-5)

    val availW = (w - 2 * pad).coerceAtLeast(1f)
    val availH = (h - 2 * pad).coerceAtLeast(1f)
    val scale = min(availW / spanLonScaled, availH / spanLat)
    val usedW = (spanLonScaled * scale).toFloat()
    val usedH = (spanLat * scale).toFloat()
    val offX = pad + (availW - usedW) / 2f
    val offY = pad + (availH - usedH) / 2f

    return pts.map { p ->
        val x = offX + (((p.lon - minLon) * cosLat) * scale).toFloat()
        val y = offY + usedH - ((p.lat - minLat) * scale).toFloat()
        x to y
    }
}

internal fun fmtShort(v: Double): String = when {
    v.isNaN() || v.isInfinite() -> "—"
    abs(v) >= 10000 -> "${"%.1f".format(Locale.US, v / 1000)}k"
    v == v.roundToInt().toDouble() && abs(v) < 1e6 -> v.roundToInt().toString()
    abs(v) >= 1 -> "${"%.1f".format(Locale.US, v)}"
    else -> "${"%.3f".format(Locale.US, v)}"
}
