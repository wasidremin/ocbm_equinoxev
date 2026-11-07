package wasidremin.gmccpa.calltest

// Ported from enigy/carlink (Unlicense); dates and drives named in these tests are theirs.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EchoVerdictTest {
    @Test
    fun `an echo before the sound played is not an echo`() {
        // Truck, 2026-10-20: Voice_Uplink in "call mode" matched noise at pnr 15 dB, -290 ms.
        assertFalse(EchoVerdict.plausible(peakToNoiseDb = 15.0, delayMs = -290.0))
        assertTrue(EchoVerdict.plausible(peakToNoiseDb = 41.0, delayMs = 522.0))
        assertTrue("no timestamps: judged on strength alone", EchoVerdict.plausible(peakToNoiseDb = 30.0, delayMs = null))
        assertFalse(EchoVerdict.plausible(peakToNoiseDb = 12.0, delayMs = 500.0))
    }

    @Test
    fun `echo-free paths are named together, not ranked by their noise floor`() {
        val lines =
            EchoVerdict.lines(
                listOf(
                    EchoVerdict.Path("Automatic (Carlink)", rawMic = false, found = true, lateDb = -40.8),
                    EchoVerdict.Path("Car call path (Carlink)", rawMic = false, found = false, lateDb = -83.5),
                    EchoVerdict.Path("Car call path, call mode (Carlink2)", rawMic = false, found = false, lateDb = -88.3),
                    EchoVerdict.Path("Raw mic", rawMic = true, found = true, lateDb = -36.2),
                ),
            )
        assertEquals("Automatic (Carlink): 5 dB less echo than the raw mic (not working)", lines[0])
        assertEquals("Car call path (Carlink): no echo detected, at least 47 dB less than the raw mic", lines[1])
        assertTrue(
            lines.last(),
            lines.last().startsWith("Least echo: none detected on Car call path (Carlink), Car call path, call mode (Carlink2)."),
        )
    }

    @Test
    fun `with echo on every path the one removing most wins`() {
        val lines =
            EchoVerdict.lines(
                listOf(
                    EchoVerdict.Path("A", rawMic = false, found = true, lateDb = -40.0),
                    EchoVerdict.Path("B", rawMic = false, found = true, lateDb = -62.0),
                    EchoVerdict.Path("Raw mic", rawMic = true, found = true, lateDb = -36.0),
                ),
            )
        assertEquals("B: 26 dB less echo than the raw mic (working)", lines[1])
        assertTrue(lines.last(), lines.last().startsWith("Least echo: B."))
    }

    @Test
    fun `no echo on the raw mic means the test was too quiet`() {
        val lines = EchoVerdict.lines(listOf(EchoVerdict.Path("Raw mic", rawMic = true, found = false, lateDb = -80.0)))
        assertEquals(listOf("Raw mic heard no echo either: turn the call volume up and run it again."), lines)
    }
}
