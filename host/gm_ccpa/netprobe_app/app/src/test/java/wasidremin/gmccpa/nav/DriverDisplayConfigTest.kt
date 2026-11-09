package wasidremin.gmccpa.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.ocbm.PixelRect
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

    /** The directions area: margins in percent of the driver display, each edge even. */
    @Test fun marginsBecomeAnEvenSafeAreaAndNoneKeepsTheWholeDisplay() {
        val size = PixelSize(2000, 810)
        assertNull(DriverDisplay.safeArea(size, DriverDisplay.Margins()))
        // 20% of 2000 = 400 left, 15% = 300 right; 10% of 810 = 81 -> 80 top.
        assertEquals(PixelRect(400, 80, 1300, 730), DriverDisplay.safeArea(size, DriverDisplay.Margins(left = 20, top = 10, right = 15)))
        // Over the per-edge cap is clamped, never an empty area.
        val a = DriverDisplay.safeArea(size, DriverDisplay.Margins(left = 90, right = 90))!!
        assertEquals(PixelRect(700, 0, 600, 810), a)
        for (v in listOf(a.x, a.y, a.width, a.height)) assertEquals(0, v % 2)
        // The GM preset lands within a few pixels of openautolink's 558,70–1694,668.
        assertEquals(PixelRect(560, 72, 1140, 602), DriverDisplay.safeArea(size, DriverDisplay.GM_LAYOUT))
    }

    @Test fun theClusterStreamCarriesTheDirectionsAreaAsItsSafeArea() {
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(width = 2470, height = 960,
            cluster = PixelSize(2000, 810), clusterSafe = PixelRect(400, 80, 1300, 730)))
        assertTrue(doc, doc.contains(
            "  - videoStreamID: VideoStream.Alt1\n" +
                "    pixelDimensions:\n" +
                "      width: 2000\n" +
                "      height: 810\n" +
                "    maxFPS: 30\n" +
                "    viewAreas:\n" +
                "    - viewArea:\n" +
                "        originX: 0\n" +
                "        originY: 0\n" +
                "        width: 2000\n" +
                "        height: 810\n" +
                "      safeArea:\n" +
                "        originX: 400\n" +
                "        originY: 80\n" +
                "        width: 1300\n" +
                "        height: 730\n" +
                "      drawUIOutsideSafeArea: false\n" +
                "    initialURL: maps:/car/instrumentcluster/map\n"))
        assertNull(VehicleConfigYaml.structuralError(doc + wifi, true))
        // Outside the screen, or without one, is refused before the wire.
        assertNotNull(runCatching { VehicleConfigSpec(cluster = PixelSize(2000, 810), clusterSafe = PixelRect(800, 0, 1300, 810)) }.exceptionOrNull())
        assertNotNull(runCatching { VehicleConfigSpec(clusterSafe = PixelRect(0, 0, 100, 100)) }.exceptionOrNull())
    }

    /** 2026-11-09 12:12:04: GM's side zone opening reopened the map, and GM hid it 1.2 s later. */
    @Test fun onlyTheMapPageBringsTheMapBack() {
        assertTrue("gm.cluster.action.FULL_MAP_SELECTED" in DriverDisplay.RETURN_ACTIONS)
        assertTrue("gm.cluster.action.ZONE3_SELECTED" !in DriverDisplay.RETURN_ACTIONS)
        assertTrue("gm.cluster.action.SERVICE_READY" !in DriverDisplay.RETURN_ACTIONS)
    }
}
