package wasidremin.gmccpa.ev

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml

/**
 * The app half of the EV contract. The box half is `iap2_core::config` tests
 * (`the_gm_apps_ev_block_resolves`) and `iap2_core::vehicle_status` tests, which parse these exact
 * strings — change one side, change the other.
 */
class EvForCarPlayTest {
    /** Byte for byte what the box's contract test parses. */
    private val equinoxBlock =
        "iapConfig:\n" +
            "  vehicleInfo:\n" +
            "    engineTypes: [electric]\n" +
            "    chargingConnectors:\n" +
            "    - type: j1772\n" +
            "    - type: ccs1\n" +
            "  vehicleStatus:\n" +
            "    capabilities: [range, outsideTemperature, rangeWarning, rangeElectric, electricChargeInfo]\n"

    @Test fun theEquinoxBlockIsPinned() {
        assertEquals(equinoxBlock, EvForCarPlay.iapConfigYaml(EvForCarPlay.FALLBACK_CONNECTORS))
    }

    /** The block appended the way renderAdapter appends it must pass the subscribe validator. */
    @Test fun theBlockKeepsTheSubscribeDocumentValid() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec()) + equinoxBlock +
            "wifi_ap: true\nwifi_ssid: ccpa\nwifi_pass: adapterpass1\nwifi_channel: 149\n"
        assertNull(VehicleConfigYaml.structuralError(doc, true))
    }

    @Test fun connectorTypesMapToApplesNames() {
        // What the Equinox EV reports (probe 2026-10-14): J1772 + CCS1 in EvChargingConnectorType numbers.
        assertEquals(listOf("j1772", "ccs1"), EvForCarPlay.connectorNames(intArrayOf(1, 5)))
        assertEquals(listOf("chademo", "ccs2"), EvForCarPlay.connectorNames(intArrayOf(4, 6, 3)))
        assertEquals(listOf("nacsdc", "nacsac"), EvForCarPlay.connectorNames(intArrayOf(9, 8, 7, 9)))
        // Unknown or unreported types fall back to the Equinox EV's own ports, never to nothing.
        assertEquals(EvForCarPlay.FALLBACK_CONNECTORS, EvForCarPlay.connectorNames(intArrayOf(101)))
        assertEquals(EvForCarPlay.FALLBACK_CONNECTORS, EvForCarPlay.connectorNames(null))
    }

    @Test fun theStatusFileUsesTheBoxUnits() {
        val text = EvForCarPlay.statusText(EvForCarPlay.Sample(
            rangeMeters = 312_400f, batteryWh = 52_004f, capacityWh = 84_590f,
            charging = false, outsideC = -3.6f, lowBattery = false,
        ))
        assertEquals(
            "range_km=312\noutside_c=-4\nrange_warning=0\ncurrent_wh=52000\nmax_wh=84590\n" +
                "percent_milli=61500\ncharging=0\n",
            text,
        )
    }

    /** A property the car did not answer is left out, never written as zero. */
    @Test fun missingReadingsAreOmitted() {
        assertEquals("", EvForCarPlay.statusText(EvForCarPlay.Sample()))
        assertEquals("range_km=10\n", EvForCarPlay.statusText(EvForCarPlay.Sample(rangeMeters = 10_000f)))
        // Energy without capacity gives no percentage.
        assertEquals("current_wh=500\n", EvForCarPlay.statusText(EvForCarPlay.Sample(batteryWh = 500f)))
        assertEquals("", EvForCarPlay.statusText(EvForCarPlay.Sample(rangeMeters = Float.NaN, outsideC = 400f)))
    }

    /** Byte for byte what the box's contract test parses (`the_gm_apps_ev_routing_block_resolves`). */
    private val routingBlock =
        "iapConfig:\n" +
            "  vehicleInfo:\n" +
            "    engineTypes: [electric]\n" +
            "    chargingConnectors:\n" +
            "    - type: j1772\n" +
            "      powerWatts: 11500\n" +
            "    - type: ccs1\n" +
            "      powerWatts: 150000\n" +
            "    mapsDisplayName: \"Chevrolet Equinox EV\"\n" +
            "  vehicleStatus:\n" +
            "    capabilities: [range, outsideTemperature, rangeWarning, rangeElectric, electricChargeInfo, maxRangeInfo]\n"

    @Test fun theRoutingBlockIsPinned() {
        assertEquals(routingBlock,
            EvForCarPlay.iapConfigYaml(EvForCarPlay.FALLBACK_CONNECTORS, routing = true, mapsName = "Chevrolet Equinox EV"))
        // Without the adapter's say-so nothing changes, whatever name is offered.
        assertEquals(equinoxBlock, EvForCarPlay.iapConfigYaml(EvForCarPlay.FALLBACK_CONNECTORS, mapsName = "Chevrolet Equinox EV"))
    }

    @Test fun theRoutingBlockKeepsTheSubscribeDocumentValid() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec()) + routingBlock +
            "wifi_ap: true\nwifi_ssid: ccpa\nwifi_pass: adapterpass1\nwifi_channel: 149\n"
        assertNull(VehicleConfigYaml.structuralError(doc, true))
    }

    private val sample = EvForCarPlay.Sample(
        rangeMeters = 312_400f, batteryWh = 52_004f, capacityWh = 84_590f,
        charging = false, outsideC = -3.6f, lowBattery = false,
    )

    /** The four routing keys follow the older ones, which stay exactly as they were. */
    @Test fun routingAddsItsKeysAfterTheOlderOnes() {
        val routing = EvForCarPlay.Routing("{\"model_id\":12582922}", "{\"model_id\":12582921}", 540)
        assertEquals(
            EvForCarPlay.statusText(sample) +
                "min_wh=0\nmax_range_km=540\ncharging_json={\"model_id\":12582922}\n" +
                "consumption_json={\"model_id\":12582921}\n",
            EvForCarPlay.statusText(sample, routing),
        )
        // No full-charge range yet: that one key is left out.
        val noRange = EvForCarPlay.statusText(sample, routing.copy(fullRangeKm = null))
        assertEquals(false, noRange.contains("max_range_km"))
        assertEquals(true, noRange.contains("charging_json="))
        // Nothing from the car: no file at all, models or not.
        assertEquals("", EvForCarPlay.statusText(EvForCarPlay.Sample(), routing))
    }

    /** A model that is not one printable line, or a file an older adapter would ignore, sends the old file. */
    @Test fun routingThatCannotBeSentIsLeftOutWhole() {
        val base = EvForCarPlay.statusText(sample)
        val ok = "{\"model_id\":1}"
        for (bad in listOf("", "{\n}", "{\"a\":\"\u00e9\"}", "{" + "x".repeat(EvForCarPlay.MAX_STATUS_BYTES) + "}")) {
            assertEquals(base, EvForCarPlay.statusText(sample, EvForCarPlay.Routing(bad, ok, 540)))
            assertEquals(base, EvForCarPlay.statusText(sample, EvForCarPlay.Routing(ok, bad, 540)))
        }
    }

    /** The real file, with both real models, is well inside what any adapter firmware reads. */
    @Test fun theRealFileFitsEveryAdapter() {
        val routing = EvForCarPlay.Routing(
            EvRoutingModel.chargingJson(86_500), EvRoutingModel.consumptionJson(4_000), 1_000)
        val text = EvForCarPlay.statusText(sample, routing)
        assertEquals(true, text.contains("consumption_json="))
        assertEquals(true, text.length < 2_000)
        assertEquals(text.length, text.toByteArray(Charsets.UTF_8).size)
    }

    @Test fun theLogNamesTheModelsByLength() {
        val routing = EvForCarPlay.Routing("{\"model_id\":12582922}", "{\"model_id\":12582921}", 540)
        val line = EvForCarPlay.logLine(EvForCarPlay.statusText(sample, routing))
        assertEquals(
            "range_km=312 outside_c=-4 range_warning=0 current_wh=52000 max_wh=84590 percent_milli=61500 " +
                "charging=0 min_wh=0 max_range_km=540 charging_json=<21 B> consumption_json=<21 B>",
            line,
        )
    }
}
