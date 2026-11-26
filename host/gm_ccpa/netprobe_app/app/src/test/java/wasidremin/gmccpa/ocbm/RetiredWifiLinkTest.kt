package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [RetiredWifiLink]: what the app undoes on firmware from before the Wi-Fi link's removal. */
class RetiredWifiLinkTest {
    @Test fun firmwareFromBeforeTheRemovalHasItsJoinCheckedAndItsKeyCleared() {
        // The car's adapter on 2026-11-25 (fw ee0e29addd5c), as MGMT_INFO said it.
        val info = """{"fw":"ee0e29addd5c","sta_uplink":true,"wifi_link":{"id":"307180849afc4fd1","via":"usb"},"devices":[]}"""
        assertEquals(RetiredWifiLink.Undo(join = true, key = true), RetiredWifiLink.undo(info))
    }

    @Test fun anAdapterThatNeverHeldAKeyHasNoKeyToClear() {
        val info = """{"sta_uplink":true,"wifi_link":{"id":"","via":"usb"}}"""
        assertEquals(RetiredWifiLink.Undo(join = true, key = false), RetiredWifiLink.undo(info))
    }

    @Test fun firmwareWithoutTheLinkIsLeftAlone() {
        val none = RetiredWifiLink.Undo(join = false, key = false)
        assertEquals(none, RetiredWifiLink.undo("""{"fw":"abc","devices":[]}"""))
        assertEquals(none, RetiredWifiLink.undo(null))
        assertEquals(none, RetiredWifiLink.undo("not json"))
    }

    @Test fun onlyAJoinThatIsOnIsTurnedOff() {
        assertTrue(RetiredWifiLink.joinIsOn("enabled=1\nsta_iface=sta0\nssid=x\npsk=y\n"))
        // The scan-only form (no passphrase) also searched at every start.
        assertTrue(RetiredWifiLink.joinIsOn("enabled=1\nsta_iface=sta0\nssid=x\n"))
        assertFalse(RetiredWifiLink.joinIsOn(RetiredWifiLink.STA_OFF))
        assertFalse(RetiredWifiLink.joinIsOn(null))
    }
}
