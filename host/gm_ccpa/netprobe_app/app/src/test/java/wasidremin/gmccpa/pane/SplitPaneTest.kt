package wasidremin.gmccpa.pane

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.ocbm.PixelRect
import wasidremin.gmccpa.ocbm.VehicleConfigSpec
import wasidremin.gmccpa.ocbm.VehicleConfigYaml
import wasidremin.gmccpa.ocbm.ViewAreaRule

/** The split layout's CarPlay area and the document that declares it. */
class SplitPaneTest {
    @Test fun thirtyPercentOnTheRightLeavesCarPlayTheLeftSeventy() {
        assertEquals(PixelRect(0, 0, 1680, 960), SplitPane.carPlayArea(2400, 960, SplitPane.Side.RIGHT, 30))
        assertEquals(PixelRect(720, 0, 1680, 960), SplitPane.carPlayArea(2400, 960, SplitPane.Side.LEFT, 30))
    }

    /** The Equinox advertises a wider, odd-hundreds panel: the width rounds down to even. */
    @Test fun everyValueIsEven() {
        val a = SplitPane.carPlayArea(2914, 966, SplitPane.Side.RIGHT, 30)!!
        assertNull(ViewAreaRule.teardownVerdict(a, 2914, 966))
        assertEquals(0, a.width % 2)
        assertEquals(0, a.height % 2)
        val l = SplitPane.carPlayArea(2914, 966, SplitPane.Side.LEFT, 40)!!
        assertEquals(2914, l.right)
        assertEquals(0, l.x % 2)
    }

    @Test fun offAndIllegalAreasAreNotDeclared() {
        assertNull(SplitPane.carPlayArea(2400, 960, SplitPane.Side.OFF, 30))
        // Over CarPlay is drawn by the app alone; the phone is told nothing.
        assertNull(SplitPane.carPlayArea(2400, 960, SplitPane.Side.OVER, SplitPane.OVER_PERCENT))
        // Below the 800 x 480 floor: CarPlay would lock the area out black.
        assertNull(SplitPane.carPlayArea(1000, 600, SplitPane.Side.RIGHT, 40))
        assertNull(SplitPane.carPlayArea(2400, 960, SplitPane.Side.RIGHT, 0))
    }

    @Test fun theDefaultDocumentStaysPinned() {
        assertTrue(VehicleConfigYaml.defaultIsPinned())
        assertTrue("one view area: ${VehicleConfigYaml.PINNED_DEFAULT}", "initial: true" !in VehicleConfigYaml.PINNED_DEFAULT)
    }

    @Test fun aSplitDocumentDeclaresTheSecondAreaInsideTheListAndPassesTheStructureCheck() {
        val area = SplitPane.carPlayArea(2400, 960, SplitPane.Side.RIGHT, 30)!!
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(viewArea2 = area, viewAreaAnimMs = SplitPane.TRANSITION_MS))
        assertTrue(doc, doc.contains("view_area_anim_ms: 500\n"))
        assertTrue(doc, doc.contains("  enablesViewAreas: true\n"))
        val second = doc.indexOf("    - viewArea:", doc.indexOf("    - viewArea:") + 1)
        assertTrue("a second list item", second > 0)
        assertTrue("before hidConfig", second < doc.indexOf("    hidConfig:"))
        assertTrue(doc.contains("        width: 1680\n"))
        assertTrue(doc.contains("      initial: true\n"))
        val adapter = doc + "wifi_ap: true\nwifi_ssid: ccpa-fe01\nwifi_pass: adapterpass1\nwifi_channel: 36\n"
        assertNull(VehicleConfigYaml.structuralError(adapter, true))
    }

    /** The pane was hidden at the end of the last drive: the area is still declared, the session starts full screen. */
    @Test fun aHiddenPaneStartsFullScreen() {
        val area = SplitPane.carPlayArea(2400, 960, SplitPane.Side.RIGHT, 30)!!
        val doc = VehicleConfigYaml.render(VehicleConfigSpec(viewArea2 = area, viewArea2Initial = false, viewAreaAnimMs = SplitPane.TRANSITION_MS))
        assertTrue(doc, doc.contains("  enablesViewAreas: true\n"))
        assertTrue(doc.contains("        width: 1680\n"))
        assertTrue(doc, "initial: true" !in doc)
        val adapter = doc + "wifi_ap: true\nwifi_ssid: ccpa-fe01\nwifi_pass: adapterpass1\nwifi_channel: 36\n"
        assertNull(VehicleConfigYaml.structuralError(adapter, true))
    }

    @Test fun overCarPlayIsTheDefaultAndOffIsLast() {
        assertEquals(SplitPane.Side.OVER, SplitPane.CHOICES.first().side)
        assertEquals(SplitPane.Side.OFF, SplitPane.CHOICES.last().side)
        assertEquals("Over CarPlay", SplitPane.CHOICES.first().label)
        assertEquals("Right, 30%", SplitPane.CHOICES[1].label)
    }

    @Test fun anIllegalSecondAreaIsRefusedBeforeItReachesTheWire() {
        val odd = PixelRect(0, 0, 1681, 960)
        val e = runCatching { VehicleConfigSpec(viewArea2 = odd) }.exceptionOrNull()
        assertNotNull(e)
        val outside = PixelRect(800, 0, 1680, 960)
        assertNotNull(runCatching { VehicleConfigSpec(viewArea2 = outside) }.exceptionOrNull())
    }
}
