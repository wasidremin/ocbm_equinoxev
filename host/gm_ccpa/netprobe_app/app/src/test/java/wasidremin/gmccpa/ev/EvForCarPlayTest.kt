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
        assertEquals(listOf("j1772", "ccs1"), EvForCarPlay.connectorNames(intArrayOf(1, 4)))
        assertEquals(listOf("nacsdc", "nacsac"), EvForCarPlay.connectorNames(intArrayOf(8, 7, 8)))
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
}
