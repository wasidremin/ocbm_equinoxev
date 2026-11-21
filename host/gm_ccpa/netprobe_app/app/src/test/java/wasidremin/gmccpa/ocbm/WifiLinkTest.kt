package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15, the Wi-Fi link: when the app gives the adapter its key, when it looks on Wi-Fi first, and where ([WifiLink]). */
class WifiLinkTest {
    @Test fun firmwareWithoutTheWifiLinkSaysNothingAndFirmwareWithItSaysWhichKeyItHolds() {
        assertNull(WifiLink.adapterId("""{"sta_uplink":true}"""))
        assertNull(WifiLink.adapterId(null))
        assertNull(WifiLink.adapterId("not json"))
        assertEquals("", WifiLink.adapterId("""{"wifi_link":{"id":"","via":"usb"}}"""))
        assertEquals("493e225880a9eea9", WifiLink.adapterId("""{"wifi_link":{"id":"493e225880a9eea9","via":"wifi"}}"""))
    }

    @Test fun theKeyIsGivenOnlyWithTheJoinOnToFirmwareThatTakesOneAndHoldsAnother() {
        assertTrue(WifiLink.shouldProvision(true, "", "abc"))
        assertTrue(WifiLink.shouldProvision(true, "", null))
        assertTrue(WifiLink.shouldProvision(true, "other", "abc"))
        assertFalse("already ours", WifiLink.shouldProvision(true, "abc", "abc"))
        assertFalse("old firmware", WifiLink.shouldProvision(true, null, "abc"))
        assertFalse("the join is off", WifiLink.shouldProvision(false, "", "abc"))
    }

    @Test fun wifiComesFirstOnlyOnceTheAdapterHasSaidItHoldsOurKey() {
        assertTrue(WifiLink.wifiFirst(true, "abc", "abc"))
        assertFalse("never confirmed", WifiLink.wifiFirst(true, "abc", null))
        assertFalse("another key", WifiLink.wifiFirst(true, "abc", "other"))
        assertFalse("no key here", WifiLink.wifiFirst(true, null, null))
        assertFalse("the join is off: USB as before", WifiLink.wifiFirst(false, "abc", "abc"))
    }

    @Test fun aStartLooksUntilFifteenSecondsAfterTheHotspotIsUpAndNeverWaitsMoreThanThirtyForIt() {
        assertTrue(WifiLink.keepLooking(0, null))
        assertTrue(WifiLink.keepLooking(29_999, null))
        assertFalse(WifiLink.keepLooking(30_000, null))
        assertTrue(WifiLink.keepLooking(40_000, 14_999))
        assertFalse(WifiLink.keepLooking(1_000, 15_000))
    }

    @Test fun theSweepCoversTheRestOfTheSlash24NearestFirst() {
        val t = WifiLink.sweepTargets("192.168.230.1")
        assertEquals(253, t.size)
        assertEquals("192.168.230.2", t.first())
        assertFalse(t.contains("192.168.230.1"))
        assertFalse(t.contains("192.168.230.0"))
        assertFalse(t.contains("192.168.230.255"))
        assertEquals(setOf("10.0.0.9", "10.0.0.11"), WifiLink.sweepTargets("10.0.0.10").take(2).toSet())
        assertTrue(WifiLink.sweepTargets("fe80::1").isEmpty())
    }

    @Test fun aStoredPeerIsReadBackAndAnythingElseIsNot() {
        assertEquals("192.168.230.57" to 7020, WifiLink.parsePeer("192.168.230.57:7020"))
        assertNull(WifiLink.parsePeer(null))
        assertNull(WifiLink.parsePeer("192.168.230.57"))
        assertNull(WifiLink.parsePeer("host:0"))
    }

    @Test fun onlyAJoinWithAPasswordCarriesTheWifiLink() {
        assertTrue(StaUplink.joins(StaUplink.conf(true, "Car", "my car pass")))
        assertFalse("a scan only", StaUplink.joins(StaUplink.conf(true, "Car")))
        assertFalse(StaUplink.joins(StaUplink.conf(false, "Car", "my car pass")))
    }
}
