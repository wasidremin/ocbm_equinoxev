package wasidremin.gmccpa.sound

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** [ToneShaper] measured with sine waves, as an ear would: level in, level out, per frequency. */
class ToneShaperTest {
    private val rate = 48_000

    private fun sine(hz: Double, amp: Double, seconds: Double = 1.0): ByteArray {
        val n = (rate * seconds).toInt()
        val b = ByteArray(n * 4)
        for (i in 0 until n) {
            val v = (amp * 32767 * sin(2 * PI * hz * i / rate)).roundToInt()
            for (ch in 0..1) { val o = i * 4 + ch * 2; b[o] = (v and 0xFF).toByte(); b[o + 1] = (v shr 8).toByte() }
        }
        return b
    }

    /** RMS of the left channel over the last half (the filters have settled). */
    private fun rmsDb(b: ByteArray): Double {
        val n = b.size / 4; var sum = 0.0; var count = 0
        for (i in n / 2 until n) {
            val s = ((b[i * 4 + 1].toInt() shl 8) or (b[i * 4].toInt() and 0xFF)).toShort() / 32768.0
            sum += s * s; count++
        }
        return 20 * log10(sqrt(sum / count))
    }

    private fun gainDb(hz: Double, bass: Double, treble: Double): Double {
        val x = sine(hz, 0.1)
        val before = rmsDb(x)
        ToneShaper(rate, 2).apply { setGains(bass, treble); process(x, x.size) }
        return rmsDb(x) - before
    }

    @Test fun withNoLiftTheBytesPassUntouched() {
        val x = sine(440.0, 0.5); val y = x.copyOf()
        ToneShaper(rate, 2).apply { setGains(0.0, 0.0); process(y, y.size) }
        assertArrayEquals(x, y)
    }

    @Test fun theBassShelfLiftsTheLowEndAndLeavesTheMiddle() {
        // Pre-gain is half the larger boost, so the middle drops by that and the bass rises by the rest.
        assertEquals(6.0 - 3.0, gainDb(40.0, 6.0, 0.0), 0.5)
        assertEquals(-3.0, gainDb(1000.0, 6.0, 0.0), 0.5)
        assertEquals(-3.0, gainDb(12000.0, 6.0, 0.0), 0.3)
    }

    @Test fun theTrebleShelfLiftsTheHighEnd() {
        assertEquals(4.0 - 2.0, gainDb(14000.0, 0.0, 4.0), 0.5)
        assertEquals(-2.0, gainDb(300.0, 0.0, 4.0), 0.3)
    }

    @Test fun aFullScaleTrackIsBentNotWrappedOrClipped() {
        val x = sine(50.0, 1.0)
        ToneShaper(rate, 2).apply { setGains(7.0, 4.0); process(x, x.size) }
        // A wrapped sample shows as a jump of tens of thousands between neighbours; a 50 Hz wave moves
        // a few hundred per sample.
        var jump = 0
        var prev = 0
        for (i in 0 until x.size / 4) {
            val v = ((x[i * 4 + 1].toInt() shl 8) or (x[i * 4].toInt() and 0xFF)).toShort().toInt()
            if (i > 0) jump = maxOf(jump, kotlin.math.abs(v - prev))
            prev = v
        }
        assertTrue("largest step $jump", jump < 2000)
        assertEquals(0.5, ToneShaper.softClip(0.5), 0.0)
        assertTrue(ToneShaper.softClip(3.0) <= 1.0 && ToneShaper.softClip(1.2) < 1.0)
        assertTrue(ToneShaper.softClip(1.2) > ToneShaper.softClip(1.0) && ToneShaper.softClip(1.0) > ToneShaper.softClip(0.95))
        assertEquals(-ToneShaper.softClip(1.5), ToneShaper.softClip(-1.5), 1e-12)
    }

    @Test fun thePreGainIsHalfTheLargerBoostAndNeverALift() {
        assertEquals(-3.5, ToneShaper.preGainDb(7.0, 4.0), 1e-9)
        assertEquals(0.0, ToneShaper.preGainDb(-3.0, -1.0), 1e-9)
    }
}
