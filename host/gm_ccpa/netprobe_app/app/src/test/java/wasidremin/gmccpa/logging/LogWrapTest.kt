package wasidremin.gmccpa.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LogWrap] on made-up adapter info; the addresses are invented. */
class LogWrapTest {
    private val mac = Regex("([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}")

    @Test fun noAddressIsCutAcrossTwoLines() {
        val devices = (1..6).joinToString(",") { "\"0${it}:11:22:33:44:5$it\"" }
        val info = "{\"fw\":\"9f91fb58dc51\",\"fw_pending\":false,\"fw_prev\":true,\"phone_order\":true,\"devices\":[$devices]}"
        // Every cut point the old chunked(110) could hit lands somewhere in this text.
        for (pad in 0 until 40) {
            val text = "x".repeat(pad) + info
            val wrapped = LogWrap.lines(text)
            assertEquals(text, wrapped.joinToString(""))
            assertEquals(mac.findAll(text).count(), wrapped.sumOf { mac.findAll(it).count() })
            assertTrue(wrapped.all { it.length <= 110 })
        }
    }

    @Test fun aRunWithNoBreakIsKeptWholeRatherThanCut() {
        val run = "a".repeat(150)
        assertEquals(listOf("one, ", run + ",", " two"), LogWrap.lines("one, $run, two", 20))
    }

    @Test fun shortTextIsOneLine() {
        assertEquals(listOf("{\"fw\":\"x\"}"), LogWrap.lines("{\"fw\":\"x\"}"))
        assertEquals(emptyList<String>(), LogWrap.lines(""))
    }
}
