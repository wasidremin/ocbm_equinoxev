package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T15 route B: what the app tells the adapter ([StaUplink]). */
class StaUplinkTest {
    @Test fun onWithANameTheAdapterIsToldToScanForIt() {
        assertEquals("enabled=1\nsta_iface=sta0\nssid=Equinox 1234\n", StaUplink.conf(true, "Equinox 1234"))
    }

    @Test fun withAPasswordTheAdapterIsToldToJoin() {
        assertEquals("enabled=1\nsta_iface=sta0\nssid=Car\npsk=my car pass\n", StaUplink.conf(true, "Car", "my car pass"))
    }

    @Test fun aPasswordThatCannotBeWrittenSafelyLeavesAScanNotAJoin() {
        for (p in listOf(null, "", "short", "x".repeat(64), "has\"quote", "back\\slash", "new\nline"))
            assertEquals("$p", "enabled=1\nsta_iface=sta0\nssid=Car\n", StaUplink.conf(true, "Car", p))
    }

    @Test fun offSendsNoPassword() {
        assertEquals("enabled=0\n", StaUplink.conf(false, "Car", "my car pass"))
    }

    @Test fun offOrUnusableIsOff() {
        for ((on, ssid) in listOf(
            false to "Car", true to null, true to "", true to "Car\"; rm", true to "Car\nx", true to "x".repeat(33), true to "back\\slash",
        )) assertEquals("$on $ssid", "enabled=0\n", StaUplink.conf(on, ssid))
    }

    @Test fun onlyAnAdapterThatSaysSoIsWrittenTo() {
        assertTrue(StaUplink.supported("""{"sta_uplink":true,"devices":[]}"""))
        assertFalse(StaUplink.supported("""{"sta_uplink":false}"""))
        assertFalse(StaUplink.supported("""{"fw":"x"}"""))
        assertFalse(StaUplink.supported(null))
        assertFalse(StaUplink.supported("garbage"))
    }

    @Test fun theCarsHotspotIsTheAccessPointInterfaceNotItsWifiClient() {
        for (n in listOf("ap_br_swlan0", "swlan0", "swlan1", "br0", "softap0")) assertTrue(n, StaUplink.isHotspotName(n))
        for (n in listOf("wlan0", "eth0", "dummy0", "vt101", "rmnet0")) assertFalse(n, StaUplink.isHotspotName(n))
    }
}
