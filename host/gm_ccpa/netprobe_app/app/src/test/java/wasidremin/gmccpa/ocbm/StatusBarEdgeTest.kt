package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T22: the status bar edge in the adapter's document, and T8's bench switch. The box side is
 * `status_bar_edge_parses_onto_device_config`. (renderAdapter draws the OEM icon, which needs a real
 * Bitmap, so these use the document body it is built from.)
 */
class StatusBarEdgeTest {
    private fun doc(edge: String = "auto", probe: Boolean = false) =
        VehicleConfigYaml.render(VehicleConfigSpec(width = 2470, height = 960, statusBarEdge = edge, mainBufferedProbe = probe))

    @Test fun autoEmitsNothing() {
        assertFalse(doc().contains("statusBarEdge"))
        assertEquals(VehicleConfigYaml.render(VehicleConfigSpec(width = 2470, height = 960)), doc())
    }

    @Test fun driverAndBottomGoOnTheFirstMainViewArea() {
        for (edge in listOf("driver", "bottom")) {
            val d = doc(edge)
            assertTrue(d, d.contains("      drawUIOutsideSafeArea: false\n      statusBarEdge: $edge\n"))
            assertEquals(1, Regex("statusBarEdge").findAll(d).count())
            assertNull(VehicleConfigYaml.structuralError(d + "wifi_ap: true\nwifi_ssid: ccpa\nwifi_pass: adapterpass1\nwifi_channel: 149\n", true))
        }
        assertEquals(listOf("auto", "bottom", "driver"), VehicleConfigYaml.STATUS_BAR_EDGES)
    }

    /** The T8 bench switch flips one flag and nothing else. */
    @Test fun theBufferedAudioProbeFlipsOneLine() {
        val off = doc()
        assertTrue(off.contains("  enablesMainBufferedAudio: false\n"))
        assertEquals(off.replace("  enablesMainBufferedAudio: false\n", "  enablesMainBufferedAudio: true\n"), doc(probe = true))
    }
}
