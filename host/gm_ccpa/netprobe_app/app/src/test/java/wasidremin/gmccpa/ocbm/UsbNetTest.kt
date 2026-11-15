package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** T15 route A: the probe's pure parts ([UsbNet]). */
class UsbNetTest {
    private val info = UsbNet.Info("eth", "192.168.50.2", "fe80::1", 5010)

    @Test fun theAdapterSaysWhetherItPresentsTheLink() {
        assertEquals(info, UsbNet.parseInfo("""{"fw":"x","usb_net":{"style":"eth","v4":"192.168.50.2","ll":"fe80::1","port":5010},"devices":[]}"""))
        assertNull(UsbNet.parseInfo("""{"fw":"x","devices":[]}"""))
        assertNull(UsbNet.parseInfo("""{"usb_net":{"style":"eth","v4":"192.168.50.2","ll":"","port":0}}"""))
        assertNull(UsbNet.parseInfo("not json"))
    }

    @Test fun armingTouchesOnlyItsOwnKey() {
        assertEquals("usb_announce=1\nusb_net=eth-once\n", UsbNet.armTrial("usb_announce=1\nusb_net=0\n"))
        assertEquals("usb_net=eth-once\n", UsbNet.armTrial(null))
        assertEquals("# note\nusb_announce=1\nusb_net=eth-once\n", UsbNet.armTrial("# note\nusb_announce=1\n usb_net = eth\n"))
    }

    @Test fun theLinkIsLookedForOnUsbLikeInterfacesFirst() {
        val ifs = listOf(
            UsbNet.Iface("lo", true, true, true, "127.0.0"),
            UsbNet.Iface("wlan0", true, false, true, "10.0.0"),
            UsbNet.Iface("usb0", true, false, true, null),
            UsbNet.Iface("eth1", true, false, true, "192.168.50"),
            UsbNet.Iface("rmnet0", false, false, true, null),
        )
        assertEquals(listOf("eth1", "usb0", "wlan0"), UsbNet.candidates(ifs, info).map { it.name })
    }
}
