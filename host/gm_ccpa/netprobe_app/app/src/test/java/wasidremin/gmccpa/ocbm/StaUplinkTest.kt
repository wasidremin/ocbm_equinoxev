package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15 route B: what the app tells the adapter about joining the car's hotspot ([StaUplink]). */
class StaUplinkTest {
    @Test fun onWithANameAndPassphraseTheAdapterIsToldToJoin() {
        assertEquals("enabled=1\nap_iface=wlan0\nsta_iface=sta0\nssid=Equinox 1234\npsk=abcdefgh\n",
            StaUplink.conf(true, "Equinox 1234", "abcdefgh"))
        // An open network has no passphrase.
        assertEquals("enabled=1\nap_iface=wlan0\nsta_iface=sta0\nssid=Open\npsk=\n", StaUplink.conf(true, "Open", null))
    }

    @Test fun offOrUnusableIsOff() {
        for ((on, ssid, psk) in listOf(
            Triple(false, "Car", "abcdefgh"),
            Triple(true, null, "abcdefgh"),
            Triple(true, "", "abcdefgh"),
            Triple(true, "Car", "short"),
            Triple(true, "Car\"; rm", "abcdefgh"),
            Triple(true, "Car", "abc\ndefgh"),
            Triple(true, "Car", "x".repeat(64)),
        )) assertEquals("$on $ssid", "enabled=0\n", StaUplink.conf(on, ssid, psk))
    }

    @Test fun onlyAnAdapterThatSaysSoIsWrittenTo() {
        assertTrue(StaUplink.supported("""{"sta_uplink":true,"devices":[]}"""))
        assertFalse(StaUplink.supported("""{"sta_uplink":false}"""))
        assertFalse(StaUplink.supported("""{"fw":"x"}"""))
        assertFalse(StaUplink.supported(null))
        assertFalse(StaUplink.supported("garbage"))
    }
}
