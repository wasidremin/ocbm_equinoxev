package wasidremin.gmccpa.usbbridge

import org.junit.Assert.assertEquals
import org.junit.Test

class CallerCheckTest {
    @Test
    fun theShippedPinsAllParse() {
        val certs = BuildConfig.CALLER_CERTS.split(',')
        assertEquals(certs.size, CallerCheck.parseCerts(BuildConfig.CALLER_CERTS).size)
    }

    @Test
    fun aMalformedPinIsDroppedAndTheRestKept() {
        val good = "33:1E:1E:89:DB:DF:78:E4:C1:71:47:D2:D9:49:38:E1:2A:B4:83:31:32:31:02:C3:1F:34:4F:C0:7A:EE:2E:2B"
        val pins = CallerCheck.parseCerts("$good,33:1E,ZZ${good.drop(2)}")
        assertEquals(1, pins.size)
        assertEquals(0x33.toByte(), pins[0][0])
        assertEquals(0x2B.toByte(), pins[0][31])
    }
}
