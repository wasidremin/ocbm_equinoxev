package wasidremin.gmccpa.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import wasidremin.gmccpa.ev.EvForCarPlay
import wasidremin.gmccpa.nav.ClusterContent
import wasidremin.gmccpa.ocbm.Ocbm
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml

/** The subscribe-document half of the car's GPS (T6), and the driver display's content choice (T23). */
class CarGpsTest {
    /** Byte for byte what the adapter's `the_gm_apps_location_block_resolves` parses. */
    private val block = "iapConfig:\n  locationInformation:\n    sentences: [gpgga, gprmc, gphdt, pascd]\n"

    @Test fun theLocationBlockIsPinned() {
        assertEquals(block, EvForCarPlay.withLocation("", CarGps.blockLines()))
    }

    /** One `iapConfig:` key whichever of the two features is on. */
    @Test fun theTwoHalvesShareOneKey() {
        val ev = EvForCarPlay.iapConfigYaml(EvForCarPlay.FALLBACK_CONNECTORS)
        assertEquals(ev, EvForCarPlay.withLocation(ev, ""))
        assertEquals("", EvForCarPlay.withLocation("", ""))
        val both = EvForCarPlay.withLocation(ev, CarGps.blockLines())
        assertEquals(ev + CarGps.blockLines(), both)
        assertEquals(1, Regex("(?m)^iapConfig:").findAll(both).count())
    }

    @Test fun theBlockKeepsTheSubscribeDocumentValid() {
        val tail = "wifi_ap: true\nwifi_ssid: ccpa\nwifi_pass: adapterpass1\nwifi_channel: 149\n"
        val ev = EvForCarPlay.iapConfigYaml(EvForCarPlay.FALLBACK_CONNECTORS, routing = true, mapsName = "Chevrolet Equinox EV")
        for (iap in listOf(block, EvForCarPlay.withLocation(ev, CarGps.blockLines()))) {
            assertNull(VehicleConfigYaml.structuralError(VehicleConfigYaml.render(VehicleConfigSpec()) + iap + tail, true))
        }
    }

    /** Each choice sends the command the adapter already knows; an unknown saved value is the map. */
    @Test fun eachContentChoiceHasItsCommandAndUrl() {
        assertEquals(Ocbm.CMD_NAV_START, ClusterContent.Choice.MAP.command)
        assertEquals(Ocbm.CMD_NAV_CARD, ClusterContent.Choice.CARD.command)
        assertEquals(Ocbm.CMD_NAV_APP, ClusterContent.Choice.APP.command)
        for (c in ClusterContent.Choice.values()) {
            assertEquals(true, c.url.startsWith(Ocbm.CLUSTER_URL_PREFIX))
            assertEquals(c, ClusterContent.fromKey(c.key))
        }
        assertEquals(ClusterContent.Choice.MAP, ClusterContent.fromKey(null))
        assertEquals(ClusterContent.Choice.MAP, ClusterContent.fromKey("something else"))
    }
}
