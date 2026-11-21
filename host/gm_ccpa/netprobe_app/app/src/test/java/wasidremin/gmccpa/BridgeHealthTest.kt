package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Test
import wasidremin.gmccpa.ocbm.Ocbm

/** What makes [SessionSupervisor] call the adapter unhealthy ([missingForBridge]). */
class BridgeHealthTest {
    /** The car with 263 (2026-11-21): rootfs-ok clear after an update on a working adapter. */
    @Test fun lowFlashAloneDoesNotMakeTheAdapterUnhealthy() {
        assertEquals(0, missingForBridge(Ocbm.BH_HCI_PRESENT or Ocbm.BH_CARPLAY_WIRELESS))
    }

    @Test fun noBluetoothControllerDoes() {
        assertEquals(Ocbm.BH_HCI_PRESENT, missingForBridge(Ocbm.BH_ROOTFS_OK or Ocbm.BH_CARPLAY_WIRELESS))
    }
}
