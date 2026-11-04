package wasidremin.gmccpa.ev

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The pure half of telling CarPlay the car is electric (docs/ops/08_FUTURE_TASKS.md T7): the
 * `iapConfig:` block the adapter turns into Identify params 20/21, and the status file it turns
 * into `VehicleStatusUpdate` (0xA101). No Android types, so both are unit-tested on the JVM.
 *
 * The box side reads these in `iap2_core::config` (the block) and `iap2_core::vehicle_status` (the
 * file). Keep the key names in step with those two readers.
 */
object EvForCarPlay {
    /**
     * Apple's VehicleStatusComponent capabilities we can source from this car (Identify param 21).
     * ElectricChargeInfo covers the battery-energy, percentage and charging fields. MaxRangeInfo is
     * left out: the car reports range now, not range at full charge. Only the unified rangeWarning:
     * Apple's spec forbids declaring it together with a per-engine rangeWarning*, and the box drops
     * the per-engine one if both arrive.
     */
    val CAPABILITIES = listOf(
        "range", "outsideTemperature", "rangeWarning", "rangeElectric", "electricChargeInfo",
    )

    /** North-American Equinox EV ports, used when the car does not report INFO_EV_CONNECTOR_TYPE. */
    val FALLBACK_CONNECTORS = listOf("j1772", "ccs1")

    /**
     * INFO_EV_CONNECTOR_TYPE values to Apple's connector names, as `iap2_core::config::connector_enum`
     * spells them. Types Apple has no name for are dropped. CarPropertyManager returns
     * `android.car.hardware.property.EvChargingConnectorType`, not the VHAL's `EvConnectorType`:
     * the Java list has IEC_TYPE_3_AC at 3, so from there on it is one higher. Read with the VHAL
     * numbers, the Equinox's `[1, 5]` (J1772 + CCS1) went to the phone as J1772 + CCS2 (2026-11-03).
     */
    fun connectorNames(types: IntArray?): List<String> {
        val names = (types ?: IntArray(0)).toList().mapNotNull {
            when (it) {
                1 -> "j1772"        // IEC_TYPE_1_AC
                2 -> "mennekes"     // IEC_TYPE_2_AC
                4 -> "chademo"      // IEC_TYPE_4_DC
                5 -> "ccs1"         // IEC_TYPE_1_CCS_DC
                6 -> "ccs2"         // IEC_TYPE_2_CCS_DC
                8 -> "nacsac"       // TESLA_HPWC
                9 -> "nacsdc"       // TESLA_SUPERCHARGER
                10 -> "gbtac"       // GBT_AC
                11 -> "gbtdc"       // GBT_DC
                else -> null        // 3 IEC_TYPE_3_AC, 7 TESLA_ROADSTER, 101 OTHER
            }
        }.distinct()
        return names.ifEmpty { FALLBACK_CONNECTORS }
    }

    /** The `iapConfig:` block, appended to the adapter's subscribe document. Ends with a newline. */
    fun iapConfigYaml(connectors: List<String>): String = buildString {
        append("iapConfig:\n")
        append("  vehicleInfo:\n")
        append("    engineTypes: [electric]\n")
        append("    chargingConnectors:\n")
        for (c in connectors) append("    - type: ").append(c).append('\n')
        append("  vehicleStatus:\n")
        append("    capabilities: [").append(CAPABILITIES.joinToString(", ")).append("]\n")
    }

    /**
     * One reading of the car's own properties. Null means the car did not answer for that one; it is
     * then left out of the file rather than sent as zero.
     */
    data class Sample(
        val rangeMeters: Float? = null,
        val batteryWh: Float? = null,
        val capacityWh: Float? = null,
        val charging: Boolean? = null,
        val outsideC: Float? = null,
        val lowBattery: Boolean? = null,
    )

    /**
     * The status file, `key=value` per line, in the units `iap2_core::vehicle_status` expects.
     * Percentage is rounded to 0.1 % and energy to 10 Wh so the file changes only when something
     * the driver could notice changes.
     */
    fun statusText(s: Sample): String = buildString {
        s.rangeMeters?.takeIf { it.isFinite() && it >= 0f }?.let {
            val km = (it / 1000f).roundToInt()
            if (km <= 65_535) append("range_km=").append(km).append('\n')
        }
        s.outsideC?.takeIf { it.isFinite() && it in -100f..100f }?.let {
            append("outside_c=").append(it.roundToInt()).append('\n')
        }
        s.lowBattery?.let { append("range_warning=").append(if (it) 1 else 0).append('\n') }
        val wh = s.batteryWh?.takeIf { it.isFinite() && it >= 0f }
        val cap = s.capacityWh?.takeIf { it.isFinite() && it > 0f }
        wh?.let { append("current_wh=").append(((it / 10f).roundToLong()) * 10).append('\n') }
        cap?.let { append("max_wh=").append(it.roundToLong()).append('\n') }
        if (wh != null && cap != null) {
            val milli = ((wh / cap) * 1000f).roundToLong().coerceIn(0, 1000) * 100
            append("percent_milli=").append(milli).append('\n')
        }
        s.charging?.let { append("charging=").append(if (it) 1 else 0).append('\n') }
    }
}
