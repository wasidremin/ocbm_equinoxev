package wasidremin.gmccpa.telemetry

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Reads packaged probe segments off disk into the shapes the in-app Insights page charts,
 * plus derived session/drive/charging/signal analytics matching `evinsights` (`derive.py`).
 *
 * A segment is one `<telemetry>/<yyyyMMdd-HHmmss-SSS>/` directory holding `report.json` (census
 * reads in `headlines[].areas[].initialRead.value`, per-property callback statistics in
 * `subscriptionStatisticsByPropertyArea`, plus optional embedded `rawLiveEvents`) and
 * `events.ndjson` (one JSON object per line; live `PROPERTY` and `LOCATION_RAW_PRIVACY_SENSITIVE`
 * callbacks with `elapsedRealtimeNanos` and `data`).
 *
 * Framework-free (`java.io` + `org.json` only) so JVM unit tests cover it without Android.
 */
object InsightsData {
    /** Featured property ids, matching [ProbeModel.compileIds] names. */
    const val INFO_MAKE = 286261505
    const val INFO_MODEL = 286261506
    const val INFO_MODEL_YEAR = 289407235
    const val CAPACITY_WH = 291504390         // INFO_EV_BATTERY_CAPACITY, Wh
    const val BATTERY_WH = 291504905          // EV_BATTERY_LEVEL, Wh (not percent)
    const val CHARGE_MW = 291504908           // EV_BATTERY_INSTANTANEOUS_CHARGE_RATE, mW
    const val PORT_OPEN = 287310602           // EV_CHARGE_PORT_OPEN, Boolean
    const val PORT_CONNECTED = 287310603      // EV_CHARGE_PORT_CONNECTED, Boolean
    const val CHARGE_STATE = 289410881        // EV_CHARGE_STATE, enum
    const val CHARGE_TIME_REM = 289410883     // EV_CHARGE_TIME_REMAINING, s
    const val REGEN_STATE = 289410884         // EV_REGENERATIVE_BRAKING_STATE
    const val CHARGE_SWITCH = 287313730       // EV_CHARGE_SWITCH, Boolean
    const val CHARGE_LIMIT_A = 291508031      // EV_CHARGE_CURRENT_DRAW_LIMIT, A
    const val CHARGE_LIMIT_PCT = 291508032    // EV_CHARGE_PERCENT_LIMIT, %
    const val RANGE_M = 291504904             // RANGE_REMAINING, m
    const val SPEED_MPS = 291504647           // PERF_VEHICLE_SPEED, m/s signed
    const val SPEED_DISPLAY_MPS = 291504648   // PERF_VEHICLE_SPEED_DISPLAY, m/s
    const val WHEEL_TICK = 290521862          // WHEEL_TICK, [reset, fl, fr, rr, rl]
    const val ODOMETER_KM = 291504644         // PERF_ODOMETER, km
    const val OUTSIDE_C = 291505923           // ENV_OUTSIDE_TEMPERATURE, Celsius
    const val GEAR = 289408000                // GEAR_SELECTION
    const val CURRENT_GEAR = 289408001        // CURRENT_GEAR
    const val IGNITION = 289408009            // IGNITION_STATE
    const val PARKING_BRAKE = 287310850       // PARKING_BRAKE_ON, Boolean
    const val NIGHT_MODE = 287310855          // NIGHT_MODE, Boolean
    const val FUEL_LOW = 287310853            // FUEL_LEVEL_LOW, Boolean
    const val DISTANCE_UNITS = 289408512      // DISTANCE_DISPLAY_UNITS (36 = MILES)
    const val TEMP_UNITS = 289408270          // HVAC_TEMPERATURE_DISPLAY_UNITS (49 = FAHRENHEIT)
    const val SPEED_UNITS = 289408516         // VEHICLE_SPEED_DISPLAY_UNITS (144 = MPH)
    const val BATTERY_UNITS = 289408515       // EV_BATTERY_DISPLAY_UNITS (101 = WH)
    const val TIRE_PRESSURE = 392168201       // TIRE_PRESSURE, kPa

    /** Ids drawn as within-segment time series on the Insights page. */
    val SERIES_IDS = listOf(BATTERY_WH, RANGE_M, SPEED_MPS, OUTSIDE_C, CHARGE_MW)

    /** Upper bound on points kept per series: stride-sampled, never truncated to a prefix. */
    const val MAX_POINTS = 2000
    /** Upper bound on event lines scanned per segment; the tail holds the latest values. */
    const val MAX_LINES = 60_000

    /** Maximum gap between consecutive samples when integrating speed/power/wheel ticks. */
    private const val MAX_GAP_S = 90.0

    enum class UnitSystem { METRIC, IMPERIAL }

    data class SeriesPoint(val tSec: Double, val value: Double)

    data class GpsPoint(
        val tSec: Double,
        val lat: Double,
        val lon: Double,
        val accuracyM: Double,
        val speedMps: Double?,
    )

    data class SpeedBands(
        val lowSec: Double = 0.0,     // 0 - 5 m/s (< 18 km/h / 11 mph)
        val lowM: Double = 0.0,
        val citySec: Double = 0.0,    // 5 - 15 m/s (18 - 54 km/h / 11 - 34 mph)
        val cityM: Double = 0.0,
        val ruralSec: Double = 0.0,   // 15 - 25 m/s (54 - 90 km/h / 34 - 56 mph)
        val ruralM: Double = 0.0,
        val highwaySec: Double = 0.0, // 25+ m/s (90+ km/h / 56+ mph)
        val highwayM: Double = 0.0,
    ) {
        val totalSec: Double get() = lowSec + citySec + ruralSec + highwaySec
        val totalM: Double get() = lowM + cityM + ruralM + highwayM
    }

    data class SignalStat(
        val id: Int,
        val name: String,
        val units: String,
        val changeMode: String,
        val result: String,
        val callbacks: Long,
        val observedHz: Double,
        val minVal: Double?,
        val maxVal: Double?,
        val lastText: String,
    )

    data class PermissionStat(
        val name: String,
        val classification: String,
        val granted: Boolean,
    )

    data class VehicleProfile(
        val make: String = "Chevrolet",
        val model: String = "Equinox EV",
        val modelCode: String = "",
        val year: Int = 2025,
        val capacityWh: Double = 84_590.0,
        val chargeLimitPct: Double? = null,
        val chargeLimitAmps: Double? = null,
        val wheelUmPerTick: List<Double> = listOf(24145.0, 24145.0, 24145.0, 24145.0),
        val preferredUnits: UnitSystem = UnitSystem.IMPERIAL,
    ) {
        val displayTitle: String
            get() {
                val cleanModel = when (model) {
                    "C223" -> "Equinox EV (C223)"
                    else -> if (modelCode.isNotEmpty() && modelCode != model) "$model ($modelCode)" else model
                }
                return "$year $make $cleanModel".trim()
            }
    }

    data class SegmentInsight(
        val name: String,
        val bootId: String,
        val startWall: String,
        val endWall: String?,
        val stopReason: String?,
        val startElapsedNanos: Long,
        val endElapsedNanos: Long,
        val durationSec: Double,
        /** Total callbacks in this segment (live event count, or stat sum fallback). */
        val events: Long,
        /** Cumulative session callbacks summed from subscriptionStatisticsByPropertyArea. */
        val cumulativeStatEvents: Long,
        /** Last numeric callback value per property id within this segment (all ids). */
        val lastValues: Map<Int, Double>,
        /** First numeric callback value per property id within this segment. */
        val firstValues: Map<Int, Double>,
        /** Census-time initial reads per property id (fallback when nothing called back). */
        val censusValues: Map<Int, Double>,
        /** Last boolean callback or census value per property id. */
        val boolValues: Map<Int, Boolean>,
        /** Within-segment time series for [SERIES_IDS] only, seconds since segment start. */
        val series: Map<Int, List<SeriesPoint>>,
        /** GPS trace points within this segment (if raw location was opted in). */
        val gpsPoints: List<GpsPoint> = emptyList(),
        // ---- Derived segment metrics ----
        val distanceWheelM: Double? = null,
        val distanceSpeedM: Double? = null,
        val distanceGpsM: Double? = null,
        val distanceOdometerM: Double? = null,
        val startWheelAvgTicks: Double? = null,
        val endWheelAvgTicks: Double? = null,
        val wheelResetCount: Long? = null,
        val wheelUmPerTick: Double = 24145.0,
        val grossDischargeWh: Double = 0.0,
        val grossRegenWh: Double = 0.0,
        val peakDischargeKw: Double = 0.0,
        val peakChargeKw: Double = 0.0,
        val maxSpeedMps: Double = 0.0,
        val movingSec: Double = 0.0,
        val stopCount: Int = 0,
        val speedBands: SpeedBands = SpeedBands(),
        val isCharging: Boolean = false,
        val portConnected: Boolean = false,
        val portOpen: Boolean = false,
        val vehicleProfile: VehicleProfile = VehicleProfile(),
        val signals: List<SignalStat> = emptyList(),
        val permissions: List<PermissionStat> = emptyList(),
    ) {
        /** Chart/display value: live last callback wins, census read otherwise. */
        fun value(id: Int): Double? = lastValues[id] ?: censusValues[id]
        fun startValue(id: Int): Double? = firstValues[id] ?: censusValues[id]

        /** Best distance in meters using vehicle priority: wheel -> speed -> GPS -> odometer. */
        val preferredDistanceM: Double?
            get() = listOfNotNull(distanceWheelM, distanceSpeedM, distanceGpsM, distanceOdometerM)
                .firstOrNull { it > 0.0 }
                ?: distanceWheelM ?: distanceSpeedM ?: distanceGpsM ?: distanceOdometerM

        val preferredDistanceSource: String
            get() = when {
                distanceWheelM != null && distanceWheelM > 0.0 -> "WHEEL_TICK"
                distanceSpeedM != null && distanceSpeedM > 0.0 -> "PERF_VEHICLE_SPEED"
                distanceGpsM != null && distanceGpsM > 0.0 -> "GPS"
                distanceOdometerM != null && distanceOdometerM > 0.0 -> "PERF_ODOMETER"
                distanceWheelM != null -> "WHEEL_TICK"
                distanceSpeedM != null -> "PERF_VEHICLE_SPEED"
                else -> "NONE"
            }

        val startBatteryWh: Double? get() = startValue(BATTERY_WH)
        val endBatteryWh: Double? get() = value(BATTERY_WH)
        /** Positive when energy was consumed (discharged), negative when net charged. */
        val netEnergyWh: Double?
            get() {
                val s = startBatteryWh ?: return null
                val e = endBatteryWh ?: return null
                return s - e
            }

        val startRangeM: Double? get() = startValue(RANGE_M)
        val endRangeM: Double? get() = value(RANGE_M)
    }

    enum class SessionKind { DRIVE, CHARGE, PARKED }

    /**
     * A contiguous vehicle session (grouped by `bootId` or time proximity), aggregating one or
     * more 60-second probe segments into a full drive, charging session, or parked observation.
     */
    data class SessionSummary(
        val bootId: String,
        val kind: SessionKind,
        val segments: List<SegmentInsight>,
        val startWall: String,
        val endWall: String,
        val durationSec: Double,
        val movingSec: Double,
        val distanceWheelM: Double?,
        val distanceSpeedM: Double?,
        val distanceGpsM: Double?,
        val preferredDistanceM: Double?,
        val preferredDistanceSource: String,
        val startBatteryWh: Double?,
        val endBatteryWh: Double?,
        val capacityWh: Double,
        /** Positive = discharged (used), negative = gained (charged). */
        val netEnergyWh: Double?,
        val grossDischargeWh: Double,
        val grossRegenWh: Double,
        val peakDischargeKw: Double,
        val peakChargeKw: Double,
        val avgMovingSpeedMps: Double?,
        val maxSpeedMps: Double,
        val stopCount: Int,
        val speedBands: SpeedBands,
        val startRangeM: Double?,
        val endRangeM: Double?,
        val tempMinC: Double?,
        val tempMaxC: Double?,
        val tempAvgC: Double?,
        val totalEvents: Long,
        /** Concatenated time series across all segments in this session (seconds from session start). */
        val series: Map<Int, List<SeriesPoint>>,
        val gpsPoints: List<GpsPoint>,
    ) {
        val startSocPct: Double? get() = startBatteryWh?.let { (it / capacityWh) * 100.0 }
        val endSocPct: Double? get() = endBatteryWh?.let { (it / capacityWh) * 100.0 }
        val deltaSocPct: Double?
            get() {
                val s = startSocPct ?: return null
                val e = endSocPct ?: return null
                return e - s
            }

        /** Wh per km on drives with >= 500 m distance and positive net energy used. */
        val whPerKm: Double?
            get() {
                val dKm = (preferredDistanceM ?: return null) / 1000.0
                val net = netEnergyWh ?: return null
                if (dKm < 0.5 || net <= 0.0) return null
                return net / dKm
            }

        /** Miles per kWh on drives with >= 500 m distance and positive net energy used. */
        val miPerKwh: Double?
            get() {
                val wpk = whPerKm ?: return null
                if (wpk <= 0.0) return null
                return 621.371 / wpk
            }

        /** Regenerative braking recovery ratio: grossRegen / grossDischarge (0..1). */
        val regenRecoveryRatio: Double?
            get() = if (grossDischargeWh > 50.0) (grossRegenWh / grossDischargeWh).coerceIn(0.0, 1.0) else null

        /** Range delta minus actual distance driven: positive means GOM dropped more than driven. */
        val rangeDeltaM: Double?
            get() {
                val s = startRangeM ?: return null
                val e = endRangeM ?: return null
                return e - s
            }

        /** Ratio of actual distance driven to GOM range consumed (>1.0 = beat EPA/GOM estimate). */
        val rangeEfficiencyRatio: Double?
            get() {
                val d = preferredDistanceM ?: return null
                val s = startRangeM ?: return null
                val e = endRangeM ?: return null
                val gomUsed = s - e
                if (d < 1000.0 || gomUsed <= 500.0) return null
                return d / gomUsed
            }
    }

    /** Complete fleet/vehicle summary across all loaded segments on disk. */
    data class FleetSummary(
        val vehicle: VehicleProfile,
        val segments: List<SegmentInsight>,
        val sessions: List<SessionSummary>,
        val totalDistanceM: Double,
        val totalDrivingSec: Double,
        val totalDischargedKwh: Double,
        val totalRegenKwh: Double,
        val totalChargedKwh: Double,
        val overallWhPerKm: Double?,
        val overallMiPerKwh: Double?,
        val overallRegenRatio: Double?,
        val speedBands: SpeedBands,
        val totalEvents: Long,
        val totalGpsFixes: Int,
    ) {
        val drives: List<SessionSummary> get() = sessions.filter { it.kind == SessionKind.DRIVE }
        val charges: List<SessionSummary> get() = sessions.filter { it.kind == SessionKind.CHARGE }
    }

    /** All segments under [dataRoot], oldest first. Malformed segments are skipped, never fatal. */
    fun loadSegments(dataRoot: File): List<SegmentInsight> {
        val dirs = dataRoot.listFiles { f -> f.isDirectory }?.sortedBy { it.name } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            runCatching { loadSegment(dir) }.getOrNull()
        }
    }

    /** Build full [FleetSummary] from [segments]. */
    fun summarize(segments: List<SegmentInsight>): FleetSummary {
        val profile = segments.lastOrNull()?.vehicleProfile ?: VehicleProfile()
        val sessions = buildSessions(segments, profile.capacityWh)
        val drives = sessions.filter { it.kind == SessionKind.DRIVE }
        val charges = sessions.filter { it.kind == SessionKind.CHARGE }

        val totalDistM = drives.sumOf { it.preferredDistanceM ?: 0.0 }
        val totalDriveSec = drives.sumOf { it.durationSec }
        val driveNetWh = drives.sumOf { (it.netEnergyWh ?: 0.0).coerceAtLeast(0.0) }
        val totalDisKwh = sessions.sumOf { it.grossDischargeWh } / 1000.0
        val totalRegenKwh = sessions.sumOf { it.grossRegenWh } / 1000.0
        val totalChargedKwh = charges.sumOf {
            val gain = -(it.netEnergyWh ?: 0.0)
            max(gain, it.grossRegenWh)
        } / 1000.0

        val overallWhPerKm = if (totalDistM >= 1000.0 && driveNetWh > 0.0) {
            driveNetWh / (totalDistM / 1000.0)
        } else null
        val overallMiPerKwh = overallWhPerKm?.takeIf { it > 0.0 }?.let { 621.371 / it }
        val overallRegenRatio = if (totalDisKwh > 0.1) (totalRegenKwh / totalDisKwh).coerceIn(0.0, 1.0) else null

        var bands = SpeedBands()
        for (s in sessions) {
            bands = SpeedBands(
                lowSec = bands.lowSec + s.speedBands.lowSec,
                lowM = bands.lowM + s.speedBands.lowM,
                citySec = bands.citySec + s.speedBands.citySec,
                cityM = bands.cityM + s.speedBands.cityM,
                ruralSec = bands.ruralSec + s.speedBands.ruralSec,
                ruralM = bands.ruralM + s.speedBands.ruralM,
                highwaySec = bands.highwaySec + s.speedBands.highwaySec,
                highwayM = bands.highwayM + s.speedBands.highwayM,
            )
        }

        return FleetSummary(
            vehicle = profile,
            segments = segments,
            sessions = sessions,
            totalDistanceM = totalDistM,
            totalDrivingSec = totalDriveSec,
            totalDischargedKwh = totalDisKwh,
            totalRegenKwh = totalRegenKwh,
            totalChargedKwh = totalChargedKwh,
            overallWhPerKm = overallWhPerKm,
            overallMiPerKwh = overallMiPerKwh,
            overallRegenRatio = overallRegenRatio,
            speedBands = bands,
            totalEvents = segments.sumOf { it.events },
            totalGpsFixes = segments.sumOf { it.gpsPoints.size },
        )
    }

    /** Group segments by `bootId` into contiguous sessions and compute session-level metrics. */
    fun buildSessions(
        segments: List<SegmentInsight>,
        defaultCapacityWh: Double = 84_590.0,
    ): List<SessionSummary> {
        if (segments.isEmpty()) return emptyList()
        val byBoot = linkedMapOf<String, MutableList<SegmentInsight>>()
        for (seg in segments) {
            val key = seg.bootId.ifEmpty { seg.name }
            byBoot.getOrPut(key) { mutableListOf() }.add(seg)
        }
        return byBoot.map { (bootId, segs) ->
            buildSession(bootId, segs, segs.last().vehicleProfile.capacityWh.takeIf { it > 0 } ?: defaultCapacityWh)
        }
    }

    private fun buildSession(
        bootId: String,
        segs: List<SegmentInsight>,
        capacityWh: Double,
    ): SessionSummary {
        val firstSeg = segs.first()
        val lastSeg = segs.last()
        val sessionStartNanos = firstSeg.startElapsedNanos
        val sessionEndNanos = lastSeg.endElapsedNanos.takeIf { it > sessionStartNanos }
            ?: (firstSeg.startElapsedNanos + (segs.sumOf { it.durationSec } * 1e9).toLong())
        val durationSec = if (sessionStartNanos > 0 && sessionEndNanos > sessionStartNanos) {
            (sessionEndNanos - sessionStartNanos) / 1e9
        } else {
            segs.sumOf { it.durationSec }
        }

        // Wheel distance across the session: sum per-segment wheel distances PLUS inter-segment tick deltas
        var wheelDistM = 0.0
        var hasWheel = false
        var prevEndTicks: Double? = null
        var prevReset: Long? = null
        for (s in segs) {
            if (s.distanceWheelM != null) {
                wheelDistM += s.distanceWheelM
                hasWheel = true
            }
            if (prevEndTicks != null && s.startWheelAvgTicks != null &&
                prevReset != null && prevReset == s.wheelResetCount
            ) {
                val gapDelta = abs(s.startWheelAvgTicks - prevEndTicks) * (s.wheelUmPerTick / 1e6)
                if (gapDelta.isFinite() && gapDelta < 5_000.0) {
                    wheelDistM += gapDelta
                }
            }
            if (s.endWheelAvgTicks != null) {
                prevEndTicks = s.endWheelAvgTicks
                prevReset = s.wheelResetCount
            }
        }

        val speedDistM = segs.mapNotNull { it.distanceSpeedM }.takeIf { it.isNotEmpty() }?.sum()
        val gpsDistM = segs.mapNotNull { it.distanceGpsM }.takeIf { it.isNotEmpty() }?.sum()
        val odoStart = segs.firstNotNullOfOrNull { it.startValue(ODOMETER_KM) }
        val odoEnd = segs.lastOrNull { it.value(ODOMETER_KM) != null }?.value(ODOMETER_KM)
        val odoDistM = if (odoStart != null && odoEnd != null && odoEnd >= odoStart) (odoEnd - odoStart) * 1000.0 else null

        val prefDistM = when {
            hasWheel && wheelDistM > 0.0 -> wheelDistM
            speedDistM != null && speedDistM > 0.0 -> speedDistM
            gpsDistM != null && gpsDistM > 0.0 -> gpsDistM
            odoDistM != null && odoDistM > 0.0 -> odoDistM
            hasWheel -> wheelDistM
            else -> speedDistM ?: gpsDistM ?: odoDistM
        }
        val prefSource = when {
            hasWheel && wheelDistM > 0.0 -> "WHEEL_TICK"
            speedDistM != null && speedDistM > 0.0 -> "PERF_VEHICLE_SPEED"
            gpsDistM != null && gpsDistM > 0.0 -> "GPS"
            odoDistM != null && odoDistM > 0.0 -> "PERF_ODOMETER"
            hasWheel -> "WHEEL_TICK"
            else -> "PERF_VEHICLE_SPEED"
        }

        val startBatt = segs.firstNotNullOfOrNull { it.startBatteryWh }
        val endBatt = segs.lastOrNull { it.endBatteryWh != null }?.endBatteryWh
        val netEnergyWh = if (startBatt != null && endBatt != null) startBatt - endBatt else null

        val grossDisWh = segs.sumOf { it.grossDischargeWh }
        val grossRegenWh = segs.sumOf { it.grossRegenWh }
        val peakDisKw = segs.maxOfOrNull { it.peakDischargeKw } ?: 0.0
        val peakChgKw = segs.maxOfOrNull { it.peakChargeKw } ?: 0.0
        val movingSec = segs.sumOf { it.movingSec }
        val maxSpeedMps = segs.maxOfOrNull { it.maxSpeedMps } ?: 0.0
        val avgMovingSpeedMps = if (movingSec > 5.0 && (prefDistM ?: 0.0) > 10.0) {
            (prefDistM ?: 0.0) / movingSec
        } else null
        val stopCount = segs.sumOf { it.stopCount }

        var bands = SpeedBands()
        for (s in segs) {
            bands = SpeedBands(
                lowSec = bands.lowSec + s.speedBands.lowSec,
                lowM = bands.lowM + s.speedBands.lowM,
                citySec = bands.citySec + s.speedBands.citySec,
                cityM = bands.cityM + s.speedBands.cityM,
                ruralSec = bands.ruralSec + s.speedBands.ruralSec,
                ruralM = bands.ruralM + s.speedBands.ruralM,
                highwaySec = bands.highwaySec + s.speedBands.highwaySec,
                highwayM = bands.highwayM + s.speedBands.highwayM,
            )
        }

        val startRangeM = segs.firstNotNullOfOrNull { it.startRangeM }
        val endRangeM = segs.lastOrNull { it.endRangeM != null }?.endRangeM

        val temps = segs.flatMap { s ->
            val pts = s.series[OUTSIDE_C]?.map { it.value } ?: emptyList()
            if (pts.isNotEmpty()) pts else listOfNotNull(s.value(OUTSIDE_C))
        }
        val tempMinC = temps.minOrNull()
        val tempMaxC = temps.maxOrNull()
        val tempAvgC = if (temps.isNotEmpty()) temps.average() else null

        // Build stitched session time-series (downsampled to MAX_POINTS)
        val stitchedSeries = mutableMapOf<Int, List<SeriesPoint>>()
        var runningOffsetSec = 0.0
        val segOffsets = segs.map { seg ->
            val off = if (sessionStartNanos > 0 && seg.startElapsedNanos >= sessionStartNanos) {
                (seg.startElapsedNanos - sessionStartNanos) / 1e9
            } else runningOffsetSec
            runningOffsetSec = off + seg.durationSec.coerceAtLeast(1.0)
            off
        }
        for (id in SERIES_IDS) {
            val allPts = mutableListOf<SeriesPoint>()
            for ((idx, seg) in segs.withIndex()) {
                val off = segOffsets[idx]
                val segPts = seg.series[id]
                if (!segPts.isNullOrEmpty()) {
                    for (p in segPts) allPts.add(SeriesPoint(off + p.tSec, p.value))
                } else {
                    seg.value(id)?.let { v -> allPts.add(SeriesPoint(off, v)) }
                }
            }
            val stride = (allPts.size / MAX_POINTS).coerceAtLeast(1)
            stitchedSeries[id] = if (stride == 1) allPts else allPts.filterIndexed { i, _ -> i % stride == 0 || i == allPts.lastIndex }
        }

        val allGps = mutableListOf<GpsPoint>()
        for ((idx, seg) in segs.withIndex()) {
            val off = segOffsets[idx]
            for (g in seg.gpsPoints) {
                allGps.add(g.copy(tSec = off + g.tSec))
            }
        }
        val gpsStride = (allGps.size / MAX_POINTS).coerceAtLeast(1)
        val downsampledGps = if (gpsStride == 1) allGps else allGps.filterIndexed { i, _ -> i % gpsStride == 0 || i == allGps.lastIndex }

        val anyCharging = segs.any { it.isCharging || it.portConnected } ||
            (netEnergyWh != null && netEnergyWh < -200.0 && (prefDistM ?: 0.0) < 100.0)
        val kind = when {
            (prefDistM ?: 0.0) >= 100.0 || maxSpeedMps >= 2.0 -> SessionKind.DRIVE
            anyCharging -> SessionKind.CHARGE
            else -> SessionKind.PARKED
        }

        return SessionSummary(
            bootId = bootId,
            kind = kind,
            segments = segs,
            startWall = firstSeg.startWall,
            endWall = lastSeg.endWall ?: lastSeg.startWall,
            durationSec = durationSec,
            movingSec = movingSec,
            distanceWheelM = if (hasWheel) wheelDistM else null,
            distanceSpeedM = speedDistM,
            distanceGpsM = gpsDistM,
            preferredDistanceM = prefDistM,
            preferredDistanceSource = prefSource,
            startBatteryWh = startBatt,
            endBatteryWh = endBatt,
            capacityWh = capacityWh,
            netEnergyWh = netEnergyWh,
            grossDischargeWh = grossDisWh,
            grossRegenWh = grossRegenWh,
            peakDischargeKw = peakDisKw,
            peakChargeKw = peakChgKw,
            avgMovingSpeedMps = avgMovingSpeedMps,
            maxSpeedMps = maxSpeedMps,
            stopCount = stopCount,
            speedBands = bands,
            startRangeM = startRangeM,
            endRangeM = endRangeM,
            tempMinC = tempMinC,
            tempMaxC = tempMaxC,
            tempAvgC = tempAvgC,
            totalEvents = segs.sumOf { it.events },
            series = stitchedSeries,
            gpsPoints = downsampledGps,
        )
    }

    private fun loadSegment(dir: File): SegmentInsight? {
        val reportFile = File(dir, "report.json")
        if (!reportFile.isFile) return null
        val report = JSONObject(reportFile.readText())
        val startNanos = report.optLong("captureStartElapsedRealtimeNanos",
            report.optLong("startElapsedRealtimeNanos", -1L))
        var endNanos = report.optLong("captureEndElapsedRealtimeNanos", -1L)
        val bootId = report.optString("bootId", "").ifEmpty { dir.name }

        // Extract vehicle profile and wheel scale from properties / headlines
        val profile = parseVehicleProfile(report)
        val wheelUm = profile.wheelUmPerTick.firstOrNull { it > 0.0 } ?: 24145.0

        val census = mutableMapOf<Int, Double>()
        val boolValues = mutableMapOf<Int, Boolean>()
        report.optJSONArray("headlines")?.let { headlines ->
            for (i in 0 until headlines.length()) {
                val h = headlines.optJSONObject(i) ?: continue
                val id = h.optInt("id", -1)
                val areas = h.optJSONArray("areas") ?: continue
                for (a in 0 until areas.length()) {
                    val read = areas.optJSONObject(a)?.optJSONObject("initialRead") ?: continue
                    if (read.optString("result") != "READABLE") continue
                    val rawVal = read.opt("value")
                    (rawVal as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let {
                        census.putIfAbsent(id, it)
                    }
                    if (rawVal is Boolean) {
                        boolValues.putIfAbsent(id, rawVal)
                    }
                }
            }
        }
        report.optJSONArray("properties")?.let { props ->
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                val id = p.optInt("id", -1)
                val areas = p.optJSONArray("areas") ?: continue
                for (a in 0 until areas.length()) {
                    val read = areas.optJSONObject(a)?.optJSONObject("initialRead") ?: continue
                    if (read.optString("result") != "READABLE") continue
                    val rawVal = read.opt("value")
                    (rawVal as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let {
                        census.putIfAbsent(id, it)
                    }
                    if (rawVal is Boolean) {
                        boolValues.putIfAbsent(id, rawVal)
                    }
                }
            }
        }

        val firstValues = mutableMapOf<Int, Double>()
        val lastValues = mutableMapOf<Int, Double>()
        val series = mutableMapOf<Int, MutableList<SeriesPoint>>()
        for (id in SERIES_IDS) series[id] = mutableListOf()

        // Raw event stream: prefer events.ndjson on disk; fallback to report.json's rawLiveEvents array
        val rawEventObjects = mutableListOf<JSONObject>()
        val eventsFile = File(dir, "events.ndjson")
        if (eventsFile.isFile) {
            for (line in tailLines(eventsFile, MAX_LINES)) {
                runCatching { JSONObject(line) }.getOrNull()?.let { rawEventObjects.add(it) }
            }
        } else {
            report.optJSONArray("rawLiveEvents")?.let { arr ->
                val startIdx = (arr.length() - MAX_LINES).coerceAtLeast(0)
                for (i in startIdx until arr.length()) {
                    arr.optJSONObject(i)?.let { rawEventObjects.add(it) }
                }
            }
        }

        val effectiveStartNanos = if (startNanos >= 0) startNanos else {
            rawEventObjects.firstOrNull()?.optLong("elapsedRealtimeNanos", 0L) ?: 0L
        }

        val stride = (rawEventObjects.size / MAX_POINTS).coerceAtLeast(1)
        val speedSamples = mutableListOf<Pair<Double, Double>>() // tSec to m/s
        val powerSamples = mutableListOf<Pair<Double, Double>>() // tSec to mW
        val wheelSamples = mutableListOf<Pair<Double, LongArray>>() // tSec to [reset, fl, fr, rr, rl]
        val gpsPoints = mutableListOf<GpsPoint>()
        var liveEventCount = 0L

        for ((li, e) in rawEventObjects.withIndex()) {
            val eNanos = e.optLong("elapsedRealtimeNanos", effectiveStartNanos)
            if (eNanos > endNanos) endNanos = eNanos
            val tSec = ((eNanos - effectiveStartNanos) / 1e9).coerceAtLeast(0.0)
            val kind = e.optString("kind")
            if (kind == "PROPERTY") {
                val data = e.optJSONObject("data") ?: continue
                if (data.optInt("status", -1) != 0) continue
                liveEventCount++
                val id = data.optInt("id", -1)
                val rawVal = data.opt("value")
                if (rawVal is Boolean) {
                    boolValues[id] = rawVal
                } else if (rawVal is Number) {
                    val v = rawVal.toDouble()
                    if (!v.isFinite()) continue
                    firstValues.putIfAbsent(id, v)
                    lastValues[id] = v
                    if (id == SPEED_MPS) speedSamples.add(tSec to v)
                    if (id == CHARGE_MW) powerSamples.add(tSec to v)
                    if (id in series && (li % stride == 0 || li == rawEventObjects.lastIndex)) {
                        series[id]!!.add(SeriesPoint(tSec, v))
                    }
                } else if (id == WHEEL_TICK && rawVal is JSONArray && rawVal.length() == 5) {
                    val ticks = LongArray(5)
                    var ok = true
                    for (k in 0..4) {
                        val n = rawVal.opt(k) as? Number
                        if (n == null) { ok = false; break }
                        ticks[k] = n.toLong()
                    }
                    if (ok) wheelSamples.add(tSec to ticks)
                }
            } else if (kind == "LOCATION_RAW_PRIVACY_SENSITIVE") {
                val data = e.optJSONObject("data") ?: continue
                val lat = (data.opt("latitude") as? Number)?.toDouble() ?: continue
                val lon = (data.opt("longitude") as? Number)?.toDouble() ?: continue
                val acc = (data.opt("accuracyMeters") as? Number)?.toDouble() ?: 999.0
                val spd = (data.opt("speedMps") as? Number)?.toDouble()
                if (lat.isFinite() && lon.isFinite()) {
                    gpsPoints.add(GpsPoint(tSec, lat, lon, acc, spd))
                }
            }
        }

        // Integrate speed -> distance, moving time, max speed, stop count, speed bands
        var speedDistM = 0.0
        var movingSec = 0.0
        var maxSpeedMps = 0.0
        var stopCount = 0
        var lowSec = 0.0; var lowM = 0.0
        var citySec = 0.0; var cityM = 0.0
        var ruralSec = 0.0; var ruralM = 0.0
        var hwySec = 0.0; var hwyM = 0.0

        for ((_, spd) in speedSamples) {
            val s = abs(spd)
            if (s > maxSpeedMps) maxSpeedMps = s
        }
        for (i in 1 until speedSamples.size) {
            val (t0, v0) = speedSamples[i - 1]
            val (t1, v1) = speedSamples[i]
            val s0 = abs(v0)
            val s1 = abs(v1)
            if (s0 > 0.5 && s1 <= 0.5) stopCount++
            val dt = t1 - t0
            if (dt <= 0.0 || dt > MAX_GAP_S) continue
            val avgS = (s0 + s1) / 2.0
            val dm = avgS * dt
            speedDistM += dm
            if (avgS > 0.5) movingSec += dt
            when {
                avgS < 5.0 -> { lowSec += dt; lowM += dm }
                avgS < 15.0 -> { citySec += dt; cityM += dm }
                avgS < 25.0 -> { ruralSec += dt; ruralM += dm }
                else -> { hwySec += dt; hwyM += dm }
            }
        }

        // Integrate power (mW) -> gross discharge Wh, gross regen Wh, peak kW
        var grossDischargeWh = 0.0
        var grossRegenWh = 0.0
        var peakDischargeKw = 0.0
        var peakChargeKw = 0.0
        for ((_, mw) in powerSamples) {
            if (mw < 0) {
                val disKw = -mw / 1e6
                if (disKw > peakDischargeKw) peakDischargeKw = disKw
            } else if (mw > 0) {
                val chgKw = mw / 1e6
                if (chgKw > peakChargeKw) peakChargeKw = chgKw
            }
        }
        for (i in 1 until powerSamples.size) {
            val (t0, mw0) = powerSamples[i - 1]
            val (t1, mw1) = powerSamples[i]
            val dt = t1 - t0
            if (dt <= 0.0 || dt > MAX_GAP_S) continue
            val disW0 = max(-mw0, 0.0) / 1000.0
            val disW1 = max(-mw1, 0.0) / 1000.0
            grossDischargeWh += ((disW0 + disW1) / 2.0) * (dt / 3600.0)
            val regW0 = max(mw0, 0.0) / 1000.0
            val regW1 = max(mw1, 0.0) / 1000.0
            grossRegenWh += ((regW0 + regW1) / 2.0) * (dt / 3600.0)
        }

        // Wheel ticks -> distance
        var wheelDistM: Double? = null
        var startWheelAvg: Double? = null
        var endWheelAvg: Double? = null
        var wheelReset: Long? = null
        if (wheelSamples.size >= 2) {
            val firstVec = wheelSamples.first().second
            val lastVec = wheelSamples.last().second
            wheelReset = lastVec[0]
            startWheelAvg = (firstVec[1] + firstVec[2] + firstVec[3] + firstVec[4]) / 4.0
            endWheelAvg = (lastVec[1] + lastVec[2] + lastVec[3] + lastVec[4]) / 4.0
            var totalTicks = 0.0
            var validPairs = 0
            for (i in 1 until wheelSamples.size) {
                val (t0, a) = wheelSamples[i - 1]
                val (t1, b) = wheelSamples[i]
                val dt = t1 - t0
                if (dt <= 0.0 || dt > MAX_GAP_S || a[0] != b[0]) continue
                val stepAvg = (abs(b[1] - a[1]) + abs(b[2] - a[2]) + abs(b[3] - a[3]) + abs(b[4] - a[4])) / 4.0
                totalTicks += stepAvg
                validPairs++
            }
            if (validPairs > 0) {
                wheelDistM = totalTicks * (wheelUm / 1e6)
            }
        }

        // GPS distance (haversine on accurate fixes <= 50 m)
        var gpsDistM: Double? = null
        val accurateGps = gpsPoints.filter { it.accuracyM <= 50.0 }
        if (accurateGps.size >= 2) {
            var sumM = 0.0
            for (i in 1 until accurateGps.size) {
                val a = accurateGps[i - 1]
                val b = accurateGps[i]
                val dt = b.tSec - a.tSec
                if (dt > 0.0 && dt <= MAX_GAP_S) {
                    sumM += haversineM(a.lat, a.lon, b.lat, b.lon)
                }
            }
            gpsDistM = sumM
        }

        val odoStart = firstValues[ODOMETER_KM] ?: census[ODOMETER_KM]
        val odoEnd = lastValues[ODOMETER_KM] ?: census[ODOMETER_KM]
        val odoDistM = if (odoStart != null && odoEnd != null && odoEnd >= odoStart && odoSamplesPresent(firstValues, lastValues)) {
            (odoEnd - odoStart) * 1000.0
        } else null

        var statEvents = 0L
        report.optJSONObject("subscriptionStatisticsByPropertyArea")?.let { stats ->
            for (k in stats.keys()) {
                val obj = stats.optJSONObject(k) ?: continue
                statEvents += obj.optLong("callbacks", obj.optLong("count", 0L))
            }
        }

        val durationSec = if (effectiveStartNanos > 0 && endNanos > effectiveStartNanos) {
            (endNanos - effectiveStartNanos) / 1e9
        } else {
            speedSamples.lastOrNull()?.first ?: 0.0
        }

        val portConn = boolValues[PORT_CONNECTED] == true
        val portOp = boolValues[PORT_OPEN] == true
        val chgState = (lastValues[CHARGE_STATE] ?: census[CHARGE_STATE])?.toInt()
        val isCharging = portConn || chgState == 1 || (peakChargeKw > 0.5 && maxSpeedMps < 0.5)

        val signals = parseSignals(report, lastValues, census, boolValues)
        val perms = parsePermissions(report)

        return SegmentInsight(
            name = dir.name,
            bootId = bootId,
            startWall = report.optString("captureStartWallTime", report.optString("startWallTime", dir.name)),
            endWall = report.optString("captureEndWallTime", null).takeIf { !it.isNullOrEmpty() },
            stopReason = report.optString("captureStopReason", null).takeIf { !it.isNullOrEmpty() },
            startElapsedNanos = effectiveStartNanos.coerceAtLeast(0L),
            endElapsedNanos = endNanos.coerceAtLeast(effectiveStartNanos.coerceAtLeast(0L)),
            durationSec = durationSec,
            events = if (liveEventCount > 0L) liveEventCount else statEvents,
            cumulativeStatEvents = statEvents,
            lastValues = lastValues,
            firstValues = firstValues,
            censusValues = census,
            boolValues = boolValues,
            series = series.mapValues { it.value.toList() },
            gpsPoints = gpsPoints,
            distanceWheelM = wheelDistM,
            distanceSpeedM = if (speedSamples.size >= 2) speedDistM else null,
            distanceGpsM = gpsDistM,
            distanceOdometerM = odoDistM,
            startWheelAvgTicks = startWheelAvg,
            endWheelAvgTicks = endWheelAvg,
            wheelResetCount = wheelReset,
            wheelUmPerTick = wheelUm,
            grossDischargeWh = grossDischargeWh,
            grossRegenWh = grossRegenWh,
            peakDischargeKw = peakDischargeKw,
            peakChargeKw = peakChargeKw,
            maxSpeedMps = maxSpeedMps,
            movingSec = movingSec,
            stopCount = stopCount,
            speedBands = SpeedBands(lowSec, lowM, citySec, cityM, ruralSec, ruralM, hwySec, hwyM),
            isCharging = isCharging,
            portConnected = portConn,
            portOpen = portOp,
            vehicleProfile = profile,
            signals = signals,
            permissions = perms,
        )
    }

    private fun odoSamplesPresent(first: Map<Int, Double>, last: Map<Int, Double>): Boolean =
        ODOMETER_KM in first && ODOMETER_KM in last

    private fun parseVehicleProfile(report: JSONObject): VehicleProfile {
        var make = report.optString("manufacturer", "Chevrolet").ifEmpty { "Chevrolet" }
        var model = report.optString("model", "Equinox EV").ifEmpty { "Equinox EV" }
        var modelCode = ""
        var year = 2025
        var capacityWh = 84_590.0
        var chargeLimitPct: Double? = null
        var chargeLimitAmps: Double? = null
        var distUnit = 36 // default US Miles on Equinox EV
        val wheelScales = mutableListOf<Double>()

        fun inspectProp(id: Int, rawVal: Any?, cfgArr: JSONArray?) {
            when (id) {
                INFO_MAKE -> (rawVal as? String)?.takeIf { it.isNotBlank() }?.let { make = it }
                INFO_MODEL -> (rawVal as? String)?.takeIf { it.isNotBlank() }?.let {
                    modelCode = it
                    if (it == "C223") model = "Equinox EV" else model = it
                }
                INFO_MODEL_YEAR -> (rawVal as? Number)?.toInt()?.takeIf { it in 2000..2100 }?.let { year = it }
                CAPACITY_WH -> (rawVal as? Number)?.toDouble()?.takeIf { it > 1000.0 }?.let { capacityWh = it }
                CHARGE_LIMIT_PCT -> (rawVal as? Number)?.toDouble()?.takeIf { it in 1.0..100.0 }?.let { chargeLimitPct = it }
                CHARGE_LIMIT_A -> (rawVal as? Number)?.toDouble()?.takeIf { it > 0.0 }?.let { chargeLimitAmps = it }
                DISTANCE_UNITS -> (rawVal as? Number)?.toInt()?.let { distUnit = it }
                WHEEL_TICK -> if (cfgArr != null && cfgArr.length() >= 5 && wheelScales.isEmpty()) {
                    for (k in 1..4) {
                        val s = (cfgArr.opt(k) as? Number)?.toDouble() ?: 24145.0
                        wheelScales.add(s)
                    }
                }
            }
        }

        report.optJSONArray("properties")?.let { props ->
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                val id = p.optInt("id", -1)
                val cfg = p.optJSONArray("configurationArray")
                val areas = p.optJSONArray("areas")
                val firstRead = areas?.optJSONObject(0)?.optJSONObject("initialRead")?.opt("value")
                inspectProp(id, firstRead, cfg)
            }
        }
        report.optJSONArray("headlines")?.let { headlines ->
            for (i in 0 until headlines.length()) {
                val h = headlines.optJSONObject(i) ?: continue
                val id = h.optInt("id", -1)
                val areas = h.optJSONArray("areas")
                val firstRead = areas?.optJSONObject(0)?.optJSONObject("initialRead")?.opt("value")
                inspectProp(id, firstRead, null)
            }
        }
        // VehicleUnit.MILES = 36 (0x24), KILOMETERS = 35 (0x23)
        val unitSys = if (distUnit == 35) UnitSystem.METRIC else UnitSystem.IMPERIAL
        return VehicleProfile(
            make = make,
            model = model,
            modelCode = modelCode,
            year = year,
            capacityWh = capacityWh,
            chargeLimitPct = chargeLimitPct,
            chargeLimitAmps = chargeLimitAmps,
            wheelUmPerTick = if (wheelScales.isNotEmpty()) wheelScales else listOf(24145.0, 24145.0, 24145.0, 24145.0),
            preferredUnits = unitSys,
        )
    }

    private fun parseSignals(
        report: JSONObject,
        lastValues: Map<Int, Double>,
        censusValues: Map<Int, Double>,
        boolValues: Map<Int, Boolean>,
    ): List<SignalStat> {
        val out = mutableListOf<SignalStat>()
        val statsObj = report.optJSONObject("subscriptionStatisticsByPropertyArea") ?: JSONObject()
        val seenIds = mutableSetOf<Int>()

        report.optJSONArray("properties")?.let { props ->
            for (i in 0 until props.length()) {
                val p = props.optJSONObject(i) ?: continue
                val id = p.optInt("id", -1)
                if (id == -1) continue
                seenIds.add(id)
                val name = p.optString("name", ProbeModel.hex(id))
                val units = p.optString("units", "")
                val changeMode = p.optString("changeModeName", "STATIC")
                val areas = p.optJSONArray("areas")
                val initialRead = areas?.optJSONObject(0)?.optJSONObject("initialRead")
                val res = initialRead?.optString("result", "UNKNOWN") ?: "UNKNOWN"
                val st = statsObj.optJSONObject("$id:0")
                val cb = st?.optLong("callbacks", st.optLong("count", 0L)) ?: 0L
                val hz = st?.optDouble("observedHz", 0.0)?.takeIf { it.isFinite() } ?: 0.0
                val minV = (st?.opt("min") as? Number)?.toDouble()?.takeIf { it.isFinite() }
                val maxV = (st?.opt("max") as? Number)?.toDouble()?.takeIf { it.isFinite() }
                val lastVal = lastValues[id] ?: censusValues[id]
                val lastBool = boolValues[id]
                val lastText = when {
                    lastVal != null -> fmtSignalValue(id, lastVal)
                    lastBool != null -> if (lastBool) "true" else "false"
                    initialRead != null && initialRead.has("value") && !initialRead.isNull("value") ->
                        initialRead.opt("value").toString()
                    else -> "—"
                }
                out.add(SignalStat(
                    id = id,
                    name = name,
                    units = units,
                    changeMode = changeMode,
                    result = if (cb > 0) "SUBSCRIBABLE" else res,
                    callbacks = cb,
                    observedHz = hz,
                    minVal = minV,
                    maxVal = maxV,
                    lastText = lastText,
                ))
            }
        }
        // Include any headline properties that were absent from the visible property list
        report.optJSONArray("headlines")?.let { headlines ->
            for (i in 0 until headlines.length()) {
                val h = headlines.optJSONObject(i) ?: continue
                val id = h.optInt("id", -1)
                if (id == -1 || id in seenIds) continue
                out.add(SignalStat(
                    id = id,
                    name = h.optString("name", ProbeModel.hex(id)),
                    units = h.optString("units", ""),
                    changeMode = "ABSENT",
                    result = h.optString("result", "ABSENT_FROM_PROPERTY_LIST"),
                    callbacks = 0L,
                    observedHz = 0.0,
                    minVal = null,
                    maxVal = null,
                    lastText = "—",
                ))
            }
        }
        return out.sortedWith(compareByDescending<SignalStat> { it.callbacks }.thenBy { it.name })
    }

    private fun parsePermissions(report: JSONObject): List<PermissionStat> {
        val out = mutableListOf<PermissionStat>()
        val arr = report.optJSONArray("permissions") ?: return out
        for (i in 0 until arr.length()) {
            val p = arr.optJSONObject(i) ?: continue
            out.add(PermissionStat(
                name = p.optString("name", "").removePrefix("android.car.permission.").removePrefix("android.permission."),
                classification = p.optString("classification", "UNKNOWN"),
                granted = p.optBoolean("granted", false),
            ))
        }
        return out
    }

    private fun fmtSignalValue(id: Int, v: Double): String = when (id) {
        BATTERY_WH, CAPACITY_WH -> "${"%,.0f".format(Locale.US, v)} Wh"
        RANGE_M -> "${"%.1f".format(Locale.US, v / 1000.0)} km"
        SPEED_MPS, SPEED_DISPLAY_MPS -> "${"%.1f".format(Locale.US, v * 3.6)} km/h"
        OUTSIDE_C -> "${"%.1f".format(Locale.US, v)} °C"
        CHARGE_MW -> "${"%.2f".format(Locale.US, v / 1e6)} kW"
        GEAR, CURRENT_GEAR -> gearName(v.toInt())
        IGNITION -> ignitionName(v.toInt())
        else -> if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "${"%.2f".format(Locale.US, v)}"
    }

    fun gearName(code: Int?): String = when (code) {
        1 -> "Neutral"
        2 -> "Reverse"
        4 -> "Park"
        8 -> "Drive"
        16 -> "Low / L"
        else -> if (code == null) "—" else "Gear $code"
    }

    fun ignitionName(code: Int?): String = when (code) {
        0 -> "Undefined"
        1 -> "Lock"
        2 -> "Off"
        3 -> "Accessory"
        4 -> "On (Run)"
        5 -> "Start"
        else -> if (code == null) "—" else "State $code"
    }

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val rad = Math.PI / 180.0
        val dLat = (lat2 - lat1) * rad
        val dLon = (lon2 - lon1) * rad
        val a = sin(dLat / 2.0) * sin(dLat / 2.0) +
            cos(lat1 * rad) * cos(lat2 * rad) * sin(dLon / 2.0) * sin(dLon / 2.0)
        return 6_371_000.0 * 2.0 * asin(min(1.0, sqrt(a)))
    }

    /** Last [max] lines of [file]; the head holds stale chunks, the tail the latest values. */
    internal fun tailLines(file: File, max: Int): List<String> {
        if (max <= 0) return emptyList()
        val buf = ArrayDeque<String>(max)
        file.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null) {
                if (buf.size >= max) buf.removeFirst()
                buf.addLast(line)
                line = reader.readLine()
            }
        }
        return buf.toList()
    }

    /** Per-segment last value of [id] across [segments]: the "over time" overview trend. */
    fun trend(segments: List<SegmentInsight>, id: Int): List<Pair<String, Double>> =
        segments.mapNotNull { s -> s.value(id)?.let { s.name to it } }

    // ---- display formatting (Metric & US Imperial) -----------------------------------------------

    fun formatBattery(wh: Double?): String =
        if (wh == null) "—" else "${"%,.0f".format(Locale.US, wh)} Wh"

    fun formatBatteryWithSoc(wh: Double?, capacityWh: Double = 84_590.0): String {
        if (wh == null) return "—"
        val kwh = wh / 1000.0
        return if (capacityWh > 1000.0) {
            val pct = (wh / capacityWh * 100.0).coerceIn(0.0, 100.0)
            "${"%.1f".format(Locale.US, pct)}% (${"%.1f".format(Locale.US, kwh)} kWh)"
        } else {
            "${"%.1f".format(Locale.US, kwh)} kWh"
        }
    }

    fun formatSoc(wh: Double?, capacityWh: Double = 84_590.0): String {
        if (wh == null || capacityWh <= 1000.0) return "—"
        val pct = (wh / capacityWh * 100.0).coerceIn(0.0, 100.0)
        return "${"%.1f".format(Locale.US, pct)}%"
    }

    fun formatEnergyKwh(wh: Double?, signed: Boolean = false): String {
        if (wh == null) return "—"
        val kwh = wh / 1000.0
        val prefix = if (signed && kwh > 0) "+" else ""
        return "$prefix${"%.2f".format(Locale.US, kwh)} kWh"
    }

    fun formatRange(m: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (m == null) return "—"
        return when (units) {
            UnitSystem.METRIC -> "${"%,.0f".format(Locale.US, m / 1000.0)} km"
            UnitSystem.IMPERIAL -> "${"%,.0f".format(Locale.US, m / 1609.344)} mi"
        }
    }

    fun formatDistance(m: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (m == null) return "—"
        return when (units) {
            UnitSystem.METRIC -> {
                val km = m / 1000.0
                if (km >= 100.0) "${"%,.0f".format(Locale.US, km)} km"
                else "${"%.1f".format(Locale.US, km)} km"
            }
            UnitSystem.IMPERIAL -> {
                val mi = m / 1609.344
                if (mi >= 100.0) "${"%,.0f".format(Locale.US, mi)} mi"
                else "${"%.1f".format(Locale.US, mi)} mi"
            }
        }
    }

    fun formatSpeed(mps: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (mps == null) return "—"
        return when (units) {
            UnitSystem.METRIC -> "${"%.0f".format(Locale.US, abs(mps) * 3.6)} km/h"
            UnitSystem.IMPERIAL -> "${"%.0f".format(Locale.US, abs(mps) * 2.236936)} mph"
        }
    }

    fun formatTemp(c: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (c == null) return "—"
        return when (units) {
            UnitSystem.METRIC -> "${"%.1f".format(Locale.US, c)} °C"
            UnitSystem.IMPERIAL -> "${"%.0f".format(Locale.US, c * 9.0 / 5.0 + 32.0)} °F"
        }
    }

    fun formatCharge(mw: Double?): String = when {
        mw == null -> "—"
        mw >= 0 -> "+${"%.1f".format(Locale.US, mw / 1e6)} kW"
        else -> "${"%.1f".format(Locale.US, mw / 1e6)} kW"
    }

    fun formatOdometer(km: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (km == null) return "—"
        return when (units) {
            UnitSystem.METRIC -> "${"%,.0f".format(Locale.US, km)} km"
            UnitSystem.IMPERIAL -> "${"%,.0f".format(Locale.US, km * 0.621371)} mi"
        }
    }

    fun formatEfficiency(whPerKm: Double?, units: UnitSystem = UnitSystem.METRIC): String {
        if (whPerKm == null || whPerKm <= 0.0 || !whPerKm.isFinite()) return "—"
        return when (units) {
            UnitSystem.METRIC -> "${"%.0f".format(Locale.US, whPerKm)} Wh/km"
            UnitSystem.IMPERIAL -> {
                val miPerKwh = 621.371 / whPerKm
                "${"%.2f".format(Locale.US, miPerKwh)} mi/kWh"
            }
        }
    }

    fun formatDuration(sec: Double?): String {
        if (sec == null || !sec.isFinite() || sec < 0.0) return "—"
        val totalSec = sec.roundToInt()
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return when {
            h > 0 -> "${h}h ${m}m"
            m > 0 -> "${m}m ${s}s"
            else -> "${s}s"
        }
    }

    fun formatWallShort(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        // e.g. 2026-10-19T14:21:03.758-04:00 -> Oct 19 · 14:21
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso) ?: return iso.take(16)
        val monthNames = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val monIdx = (m.groupValues[2].toIntOrNull() ?: 1).coerceIn(1, 12) - 1
        val day = m.groupValues[3].trimStart('0').ifEmpty { "0" }
        return "${monthNames[monIdx]} $day · ${m.groupValues[4]}:${m.groupValues[5]}"
    }
}
