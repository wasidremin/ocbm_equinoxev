package wasidremin.gmccpa.ocbm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectStallTest {
    /** The 15:08 connect of 2026-11-22 in the car, over the Wi-Fi link: on time at every step. */
    private val car = mapOf(
        "attach" to 0L, "hello" to 32L, "subscribe" to 320L, "discoverable" to 3_929L,
        "bt-connected" to 11_424L, "wifi-handoff" to 14_207L, "auth-setup-ok" to 16_400L, "keyed" to 17_615L,
    )

    @Test fun aConnectOnTimeSaysNothing() {
        for (stage in car.keys) {
            val upTo = car.filterValues { it <= car.getValue(stage) }
            assertNull(stage, ConnectStall.of(upTo, car.getValue(stage) + 2_000))
        }
        assertNull(ConnectStall.of(car + ("first-video" to 17_998L), 600_000))
    }

    @Test fun aPhoneThatNeverAnswersNamesBluetoothAndThePhonesOrder() {
        val s = ConnectStall.of(car.filterKeys { it in setOf("attach", "hello", "subscribe", "discoverable") }, 3_929 + 31_000)
        assertEquals("discoverable", s?.after)
        assertEquals(31L, s?.waitedS)
        assertTrue(s!!.text.contains("Settings ▸ CarPlay ▸ Phones"))
    }

    @Test fun anAdapterThatNeverAnswersIsGivenLongerThanTheHotspotLook() {
        assertNull(ConnectStall.of(mapOf("attach" to 0L), 40_000))
        assertEquals("attach", ConnectStall.of(mapOf("attach" to 0L), 45_000)?.after)
    }

    @Test fun aBluetoothStampThatIsNotAStepDoesNotResetTheWait() {
        val s = ConnectStall.of(mapOf("attach" to 0L, "hello" to 10L, "subscribe" to 300L, "bt-converged" to 25_000L), 30_400)
        assertEquals("subscribe", s?.after)
    }

    @Test fun nothingStampedSaysNothing() {
        assertNull(ConnectStall.of(emptyMap(), 100_000))
    }
}
