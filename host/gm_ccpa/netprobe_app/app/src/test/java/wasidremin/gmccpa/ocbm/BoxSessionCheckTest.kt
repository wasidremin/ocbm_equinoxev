package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** MGMT_INFO shapes from the car log; addresses and names replaced. */
class BoxSessionCheckTest {
    private fun info(transport: String) =
        "{\"bt_mac\":\"\",\"wifi_mac\":\"\",\"serial\":\"S\",\"name\":\"CarLink-0000\",\"uptime_s\":9," +
            "\"rootfs_pct\":67,\"rootfs_free_kb\":4336,\"ssp\":false,\"hci_up\":false,\"wlan_ap\":false," +
            "\"transport\":\"$transport\",\"host_present\":false,\"phone_present\":false," +
            "\"daemons\":{\"ocbmd\":true,\"iap2d\":false,\"airplayd\":false,\"carplay_wireless\":false}," +
            "\"host_name\":\"h\",\"phone_order\":true,\"devices\":[]}"

    @Test fun anEmptyTransportIsNoSession() {
        // 2026-11-06 17:44:10, before the phone connected.
        assertEquals(false, BoxSessionCheck.transportLive(info("")))
    }

    @Test fun theWirelessArmOwningTheBoxIsASession() {
        assertEquals(true, BoxSessionCheck.transportLive(info("wireless")))
    }

    @Test fun aReplyWithoutTheFieldSaysNothing() {
        assertNull(BoxSessionCheck.transportLive("{\"bt_mac\":\"\",\"uptime_s\":9}"))
        assertNull(BoxSessionCheck.transportLive(""))
    }
}
