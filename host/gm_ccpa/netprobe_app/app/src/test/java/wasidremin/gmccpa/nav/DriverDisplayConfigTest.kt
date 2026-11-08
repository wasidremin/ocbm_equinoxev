package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.ocbm.PixelSize
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml

/** The cluster screen the driver display's map declares at subscribe, in Apple's template shape. */
class DriverDisplayConfigTest {
    private val wifi = "wifi_ap: true\nwifi_ssid: ccpa-fe01\nwifi_pass: adapterpass1\nwifi_channel: 36\n"

    @Test fun aClusterScreenIsApplesInstrumentClusterPair() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(width = 2470, height = 960, cluster = PixelSize(2000, 810)))
        assertTrue(doc, doc.contains(
            "  altDisplayPanels:\n" +
                "  - displayPanelID: DisplayPanel.Alt1\n" +
                "    pixelDimensions:\n" +
                "      width: 2000\n" +
                "      height: 810\n" +
                "    displayProperties:\n" +
                "    - showsInstruments\n" +
                "videoStreamsConfig:\n"))
        assertTrue(doc, doc.contains(
            "    primaryInput: Touchpad\n" +
                "  altVideoStreams:\n" +
                "  - videoStreamID: VideoStream.Alt1\n" +
                "    pixelDimensions:\n" +
                "      width: 2000\n" +
                "      height: 810\n" +
                "    maxFPS: 30\n"))
        // The line the box's glued-initialURL guard is about: its own, ending in a newline, before accessoryConfig.
        assertTrue(doc, doc.contains("    initialURL: maps:/car/instrumentcluster/map\naccessoryConfig:\n"))
        // The main stream still has exactly one viewAreas list; the cluster's is not counted under it.
        assertNull(VehicleConfigYaml.structuralError(doc + wifi, true))
    }

    @Test fun noClusterKeepsBothListsEmptyAndThePinStands() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(width = 2470, height = 960))
        assertTrue(doc.contains("  altDisplayPanels: []\n"))
        assertTrue(doc.contains("  altVideoStreams: []\n"))
        assertTrue(VehicleConfigYaml.defaultIsPinned())
        assertTrue("VideoStream.Alt1" !in VehicleConfigYaml.PINNED_DEFAULT)
    }

    /** The marker OcbmProbe reads back from the sent document to know the cluster really went out. */
    @Test fun theSentDocumentNamesTheClusterStream() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(cluster = PixelSize(1920, 720)))
        assertTrue(doc.contains("\n  - videoStreamID: VideoStream.Alt1\n"))
        assertTrue("\n  - videoStreamID: VideoStream.Alt1\n" !in VehicleConfigYaml.render(VehicleConfigSpec()))
    }

    @Test fun anOddOrHugeClusterIsRefusedBeforeTheWire() {
        assertNotNull(runCatching { VehicleConfigSpec(cluster = PixelSize(2001, 810)) }.exceptionOrNull())
        assertNotNull(runCatching { VehicleConfigSpec(cluster = PixelSize(2000, 1700)) }.exceptionOrNull())
        assertEquals(PixelSize(2000, 810), VehicleConfigSpec(cluster = PixelSize(2000, 810)).cluster)
    }
}
