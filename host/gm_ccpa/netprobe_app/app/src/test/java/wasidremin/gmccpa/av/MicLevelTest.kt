package wasidremin.gmccpa.av

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MicLevelTest {
    private fun s16le(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
        samples.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test fun zeroChunksAreCountedAndAllZeroIsSilence() {
        val m = MicLevel()
        m.add(s16le(0, 0, 0, 0), 8)
        m.add(s16le(0, 0, 0, 0), 8)
        assertEquals(2, m.zeroChunks)
        assertTrue(m.allZero)
        assertEquals("-inf", m.dbfs())
    }

    @Test fun oneNoisySampleIsNotSilence() {
        val m = MicLevel()
        m.add(s16le(0, 0, 0, 0), 8)
        m.add(s16le(0, 3, 0, 0), 8)
        assertEquals(1, m.zeroChunks)
        assertFalse(m.allZero)
        assertEquals(3, m.peak)
    }

    @Test fun negativeSamplesDecodeAsSigned() {
        val m = MicLevel()
        m.add(s16le(-1200, 400, -32768), 6)
        assertEquals(32768, m.peak)
        assertEquals("0", m.dbfs())
    }

    @Test fun peakIsTheMaximumAcrossChunks() {
        val m = MicLevel()
        m.add(s16le(100, -50), 4)
        m.add(s16le(-16384, 20), 4)
        m.add(s16le(7, 7), 4)
        assertEquals(16384, m.peak)
        assertEquals("-6", m.dbfs())
    }

    @Test fun nothingAddedIsNotAllZero() {
        assertFalse(MicLevel().allZero)
    }
}
