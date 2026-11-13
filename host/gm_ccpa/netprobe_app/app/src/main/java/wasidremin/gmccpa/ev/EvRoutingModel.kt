package wasidremin.gmccpa.ev

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The two battery models Apple Maps needs before it treats the car as electric
 * (docs/ops/08_FUTURE_TASKS.md T7, "Cause found (2026-11-13)"): how fast the car charges at each
 * state of charge, and how much energy it uses to move. They ride `VehicleStatusUpdate` ids 26 and
 * 27 as JSON. Without both, Maps logs "not connected to electric vehicle" and plans no charging.
 *
 * The shape and the two `model_id`s are facts taken from two public projects (augard/evmaps and
 * isaacwisdom/egmp-carplay-ev-routing; see NOTICE.md). Every number in here is this car's own.
 * They are first values, to be corrected by comparing what Maps predicts with what happened.
 *
 * Pure: no Android types, so the JVM tests cover all of it.
 */
object EvRoutingModel {
    /** The formula ids Apple Maps accepts from a CarPlay accessory. They name the formula, not the car. */
    const val CHARGING_MODEL_ID = 12582922      // 0x00C0000A, VehicleStatusUpdate id 26
    const val CONSUMPTION_MODEL_ID = 12582921   // 0x00C00009, VehicleStatusUpdate id 27

    // ---- Charging ---------------------------------------------------------------------------------

    /** The Equinox EV's pack, used when the car does not report its capacity. */
    const val NOMINAL_CAPACITY_WH = 85_000

    /**
     * The car's own capacity reading moves by a few hundred Wh from day to day (83.9 to 86.5 kWh over
     * a month). The model takes it in steps this size so the JSON does not change with every wobble.
     */
    const val CAPACITY_STEP_WH = 500

    /**
     * DC fast-charge power against state of charge: eleven points, each a fraction of the pack and the
     * power the charger shows there. The owner's drive log holds no fast charge yet (29 charges, all
     * under 7 kW), so this is a composite of published 10-to-80 % tests of the 85 kWh car: about 150
     * kW up to 35 %, a step to about 125 kW, a fall from 43 %, 70 to 80 kW through the 50s and 60s,
     * about 62 kW at 80 %. Read back, it gives 10 to 80 % in about 36 minutes at the battery, 39 with
     * [CHARGE_EFFICIENCY]; the tests measured 37 to 40.
     */
    val CHARGE_FRACTION = doubleArrayOf(0.0, 0.10, 0.35, 0.36, 0.43, 0.51, 0.68, 0.80, 0.90, 0.96, 1.0)
    val CHARGE_WATTS = intArrayOf(
        140_000, 150_000, 150_000, 125_000, 125_000, 80_000, 75_000, 62_000, 35_000, 15_000, 8_000,
    )

    /** Share of the charger's energy that ends up in the pack. */
    const val CHARGE_EFFICIENCY = 0.93

    /** How many entries `energy_w_per_h` has. It reads as the pack's energy at each 5 % of charge. */
    const val ENERGY_STEPS = 21

    /** Peak power by connector, for Identify's PowerForConnectorType subs. */
    const val DC_PEAK_W = 150_000
    const val AC_PEAK_W = 11_500

    // ---- Consumption ------------------------------------------------------------------------------

    /**
     * Energy per metre in ten driving conditions, Wh/m. What each position stands for is not public.
     * The pattern is the one a working model uses, moved onto this car's own fit
     * ([wasidremin.gmccpa.telemetry.DriveOutlook.cruise]) at two anchors: position 6 is steady
     * 90 km/h and position 9 is 130 km/h, each with [AUX_BASE_W] taken out because the model counts
     * auxiliary power separately. `EvRoutingModelTest` holds the anchors to the fit.
     */
    val CONSUMPTION_WH_PER_M = doubleArrayOf(
        0.2293, 0.1747, 0.1885, 0.2147, 0.1618, 0.1618, 0.1757, 0.2037, 0.2308, 0.2711,
    )
    const val ANCHOR_90_KMH = 6
    const val ANCHOR_130_KMH = 9

    /** Wh per metre climbed: mass times gravity, over the share of battery energy that reaches the wheels. */
    const val CLIMB_WH_PER_M = 7.0

    /** Wh per metre descended that comes back, at the arrival model's regeneration share. */
    const val DESCENT_WH_PER_M = 4.6

    /** Power the car draws whatever its speed: electronics, pumps, lights. */
    const val AUX_BASE_W = 500

    /**
     * Extra watts per °C below [wasidremin.gmccpa.telemetry.DriveOutlook.MILD_C]. The drive log's
     * cold term is 3.55 Wh/km per °C at a mean moving speed of 58 km/h, which is about 200 W.
     */
    const val AUX_W_PER_C = 200
    const val AUX_MAX_W = 4_000
    const val AUX_STEP_W = 50

    /** Below this share of charge the car's range figure is too small to scale up to a full pack. */
    const val MIN_FRACTION_FOR_FULL_RANGE = 0.2f
    const val FULL_RANGE_STEP_KM = 10

    // ---- Builders ---------------------------------------------------------------------------------

    /** The capacity the charging model is built for: the car's reading in [CAPACITY_STEP_WH] steps. */
    fun capacityFor(reportedWh: Float?): Int {
        val wh = reportedWh?.takeIf { it.isFinite() && it in 30_000f..200_000f } ?: return NOMINAL_CAPACITY_WH
        return (wh / CAPACITY_STEP_WH).roundToInt() * CAPACITY_STEP_WH
    }

    /** `ChargingParameter` (id 26): one line of JSON, printable ASCII. */
    fun chargingJson(capacityWh: Int): String {
        val energy = CHARGE_FRACTION.map { round50(capacityWh * it) }
        val steps = (0 until ENERGY_STEPS).map { round50(capacityWh * it / (ENERGY_STEPS - 1.0)) }
        return "{\"model_id\":$CHARGING_MODEL_ID,\"vehicle_parameters\":{" +
            "\"vehicle_energy_axis_wh\":${energy.joinToString(",", "[", "]")}," +
            "\"vehicle_charge_axis_w\":${CHARGE_WATTS.joinToString(",", "[", "]")}," +
            "\"energy_w_per_h\":${steps.joinToString(",", "[", "]")}," +
            "\"efficiency_factor\":${fixed(CHARGE_EFFICIENCY, 2)}}}"
    }

    /** `ConsumptionParameter` (id 27): one line of JSON, printable ASCII. */
    fun consumptionJson(auxWatts: Int): String =
        "{\"model_id\":$CONSUMPTION_MODEL_ID,\"vehicle_parameters\":{" +
            "\"vehicle_auxiliary_power_w\":${fixed(auxWatts.toDouble(), 1)}," +
            "\"vehicle_consumption_values_wh_per_m\":" +
            "${CONSUMPTION_WH_PER_M.joinToString(",", "[", "]") { fixed(it, 4) }}," +
            "\"vehicle_altitude_gain_consumption_wh_per_m\":${fixed(CLIMB_WH_PER_M, 2)}," +
            "\"vehicle_altitude_loss_consumption_wh_per_m\":${fixed(DESCENT_WH_PER_M, 2)}}}"

    /** Auxiliary power for the outside temperature: the base, plus heating when it is cold. */
    fun auxWatts(outsideC: Float?): Int {
        val t = outsideC?.takeIf { it.isFinite() && it in -60f..60f } ?: return AUX_BASE_W
        val below = (wasidremin.gmccpa.telemetry.DriveOutlook.MILD_C - t).coerceAtLeast(0.0)
        val w = (AUX_BASE_W + AUX_W_PER_C * below).coerceAtMost(AUX_MAX_W.toDouble())
        return (w / AUX_STEP_W).roundToInt() * AUX_STEP_W
    }

    /**
     * Range on a full pack, from the car's own range figure and how full the pack is. Null when the
     * pack is too empty for that to mean anything, or a reading is missing.
     */
    fun fullRangeKm(rangeMeters: Float?, batteryWh: Float?, capacityWh: Float?): Int? {
        val range = rangeMeters?.takeIf { it.isFinite() && it > 0f } ?: return null
        val wh = batteryWh?.takeIf { it.isFinite() && it > 0f } ?: return null
        val cap = capacityWh?.takeIf { it.isFinite() && it > 0f } ?: return null
        val fraction = (wh / cap).coerceAtMost(1f)
        if (fraction < MIN_FRACTION_FOR_FULL_RANGE) return null
        val km = range / 1000f / fraction
        val stepped = (km / FULL_RANGE_STEP_KM).roundToInt() * FULL_RANGE_STEP_KM
        return stepped.takeIf { it in 100..1500 }
    }

    /** Peak power for one of Apple's connector names, as [EvForCarPlay.connectorNames] spells them. */
    fun connectorWatts(name: String): Int = when (name) {
        "ccs1", "ccs2", "chademo", "nacsdc", "gbtdc" -> DC_PEAK_W
        else -> AC_PEAK_W
    }

    const val FALLBACK_NAME = "Chevrolet Equinox EV"
    private const val MAX_NAME_CHARS = 48

    /**
     * The name Apple Maps lists the car under, from the car's own make and model. Only letters,
     * digits, spaces, `.`, `-` and `&` survive: the name goes into the subscribe document inside
     * double quotes, and the adapter refuses control characters.
     */
    fun mapsName(make: String?, model: String?): String {
        val clean = listOfNotNull(make, model).joinToString(" ")
            .filter { it.isLetterOrDigit() || it in " .-&" }
            .trim().replace(Regex(" +"), " ")
        return clean.takeIf { it.any(Char::isLetter) && it.length <= MAX_NAME_CHARS } ?: FALLBACK_NAME
    }

    /**
     * Holds a value steady: a new candidate replaces the current one only when [gapMs] has passed
     * since the last change. Apple Maps plans routes from these models, and a project that changed
     * auxiliary power every few seconds saw it recalculate the route over and over.
     */
    class IntHold(private val gapMs: Long) {
        /** The value now in force, or null before the first one. */
        var current: Int? = null
            private set
        private var changedAtMs = 0L

        fun value(candidate: Int, nowMs: Long): Int {
            val held = current
            if (held == null || (candidate != held && nowMs - changedAtMs >= gapMs)) {
                current = candidate
                changedAtMs = nowMs
            }
            return current ?: candidate
        }
    }

    private fun round50(wh: Double): Int = (wh / 50.0).roundToInt() * 50

    /** A decimal with a dot whatever the car's language is set to. */
    private fun fixed(v: Double, places: Int): String = String.format(Locale.US, "%.${places}f", v)
}
