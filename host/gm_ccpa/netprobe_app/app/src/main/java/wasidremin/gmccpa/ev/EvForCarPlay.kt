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
     * ElectricChargeInfo covers the battery-energy, percentage and charging fields, and the two
     * EV-routing models. Only the unified rangeWarning: Apple's spec forbids declaring it together
     * with a per-engine rangeWarning*, and the box drops the per-engine one if both arrive.
     */
    val CAPABILITIES = listOf(
        "range", "outsideTemperature", "rangeWarning", "rangeElectric", "electricChargeInfo",
    )

    /**
     * Added when the adapter relays the EV-routing models: range at full charge, which
     * [EvRoutingModel.fullRangeKm] works out from the car's range and how full the pack is.
     */
    const val ROUTING_CAPABILITY = "maxRangeInfo"

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

    /**
     * The `iapConfig:` block, appended to the adapter's subscribe document. Ends with a newline.
     *
     * With [routing] off this is byte for byte the block every build since 223 has sent. With it
     * on, for an adapter that relays the EV-routing models, each connector carries its peak power,
     * the car gets the name Apple Maps lists it under, and range at full charge is declared.
     * [mapsName] must already be safe inside double quotes ([EvRoutingModel.mapsName]).
     */
    fun iapConfigYaml(connectors: List<String>, routing: Boolean = false, mapsName: String? = null): String =
        buildString {
            append("iapConfig:\n")
            append("  vehicleInfo:\n")
            append("    engineTypes: [electric]\n")
            append("    chargingConnectors:\n")
            for (c in connectors) {
                append("    - type: ").append(c).append('\n')
                if (routing) append("      powerWatts: ").append(EvRoutingModel.connectorWatts(c)).append('\n')
            }
            if (routing && mapsName != null) append("    mapsDisplayName: \"").append(mapsName).append("\"\n")
            append("  vehicleStatus:\n")
            val caps = if (routing) CAPABILITIES + ROUTING_CAPABILITY else CAPABILITIES
            append("    capabilities: [").append(caps.joinToString(", ")).append("]\n")
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
     * What Apple Maps needs on top of a [Sample] to plan charging: the two models as one line of
     * JSON each ([EvRoutingModel]), and range at full charge when it is known.
     */
    data class Routing(val chargingJson: String, val consumptionJson: String, val fullRangeKm: Int?)

    /**
     * Adapter firmware before 2026-11-13 ignores a status file larger than this, so the file stays
     * under it whatever the adapter is.
     */
    const val MAX_STATUS_BYTES = 4096

    /**
     * The status file, `key=value` per line, in the units `iap2_core::vehicle_status` expects.
     * Percentage is rounded to 0.1 % and energy to 10 Wh so the file changes only when something
     * the driver could notice changes. [routing] adds the four EV-routing keys; it is left out, whole,
     * if it would take the file past [MAX_STATUS_BYTES] or a model is not one printable line.
     */
    fun statusText(s: Sample, routing: Routing? = null): String {
        val base = baseText(s)
        if (routing == null || base.isEmpty()) return base
        val models = listOf(routing.chargingJson, routing.consumptionJson)
        if (models.any { m -> m.length < 2 || m.any { it !in ' '..'~' } }) return base
        val full = buildString {
            append(base)
            append("min_wh=0\n")
            routing.fullRangeKm?.takeIf { it in 1..65_535 }?.let { append("max_range_km=").append(it).append('\n') }
            append("charging_json=").append(routing.chargingJson).append('\n')
            append("consumption_json=").append(routing.consumptionJson).append('\n')
        }
        return if (full.length < MAX_STATUS_BYTES) full else base
    }

    /** [text] for the log: the models are a kilobyte that never changes in a drive, so by length. */
    fun logLine(text: String): String = text.trim().lineSequence().joinToString(" ") { line ->
        val key = line.substringBefore('=')
        if (key.endsWith("_json")) "$key=<${line.length - key.length - 1} B>" else line
    }

    private fun baseText(s: Sample): String = buildString {
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
