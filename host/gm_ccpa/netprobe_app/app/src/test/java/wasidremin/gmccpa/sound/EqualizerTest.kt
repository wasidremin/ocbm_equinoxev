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

/** [Equalizer]'s presets and stored form, the bands in [ToneShaper] measured with sine waves, and the editor's geometry. */
class EqualizerTest {
    private val rate = 48_000

    private fun gainDb(hz: Double, bands: DoubleArray): Double {
        val n = rate
        val b = ByteArray(n * 4)
        for (i in 0 until n) {
            val v = (0.1 * 32767 * sin(2 * PI * hz * i / rate)).roundToInt()
            for (ch in 0..1) { val o = i * 4 + ch * 2; b[o] = (v and 0xFF).toByte(); b[o + 1] = (v shr 8).toByte() }
        }
        fun rms(x: ByteArray): Double {
            var s = 0.0; var c = 0
            for (i in n / 2 until n) { val v = ((x[i * 4 + 1].toInt() shl 8) or (x[i * 4].toInt() and 0xFF)).toShort() / 32768.0; s += v * v; c++ }
            return 20 * log10(sqrt(s / c))
        }
        val before = rms(b)
        ToneShaper(rate, 2).apply { setBands(bands); process(b, b.size) }
        return rms(b) - before
    }

    @Test fun aBandLiftsItsOwnOctaveAndLittleElse() {
        val g = DoubleArray(10).also { it[5] = 6.0 } // 1 kHz
        val pre = ToneShaper.preGainDb(0.0, 0.0, 6.0) // -3 dB ahead of the filters
        assertEquals(6.0 + pre, gainDb(1000.0, g), 0.3)
        assertEquals(pre, gainDb(62.0, g), 0.3)
        assertEquals(pre, gainDb(16000.0, g), 0.3)
    }

    @Test fun aCutLowersItsBandAndIsNeverALiftElsewhere() {
        val g = DoubleArray(10).also { it[2] = -6.0 } // 125 Hz
        assertEquals(-6.0, gainDb(125.0, g), 0.3)
        assertEquals(0.0, gainDb(2000.0, g), 0.2)
    }

    @Test fun flatPassesTheBytesUntouched() {
        val x = ByteArray(4800) { (it * 37).toByte() }; val y = x.copyOf()
        ToneShaper(rate, 2).apply { setBands(Equalizer.Preset.FLAT.gains!!.copyOf()); process(y, y.size) }
        assertArrayEquals(x, y)
    }

    @Test fun everyPresetIsTenBandsWithinRangeAndCustomHasNone() {
        for (p in Equalizer.Preset.values()) {
            if (p == Equalizer.Preset.CUSTOM) { assertEquals(null, p.gains); continue }
            assertEquals(p.key, 10, p.gains!!.size)
            assertTrue(p.key, p.gains.all { it in -Equalizer.MAX_DB..Equalizer.MAX_DB })
        }
        assertEquals(Equalizer.Preset.FLAT, Equalizer.presetFor(null))
        assertEquals(Equalizer.Preset.VOCAL, Equalizer.presetFor("vocal"))
    }

    @Test fun theCustomCurveSurvivesStorageAndAnythingMalformedIsFlat() {
        val g = doubleArrayOf(3.0, -1.5, 0.0, 0.0, 2.5, 0.0, 0.0, -12.0, 12.0, 0.5)
        assertArrayEquals(g, Equalizer.parse(Equalizer.format(g)), 1e-9)
        assertArrayEquals(DoubleArray(10), Equalizer.parse("1,2,3"), 0.0)
        assertArrayEquals(DoubleArray(10), Equalizer.parse("a,b,c,d,e,f,g,h,i,j"), 0.0)
        assertArrayEquals(DoubleArray(10), Equalizer.parse(null), 0.0)
        // Out of range or between steps on disk is brought back in.
        assertEquals(12.0, Equalizer.parse("40,0,0,0,0,0,0,0,0,0")[0], 0.0)
        assertEquals(1.5, Equalizer.snap(1.3), 0.0)
    }

    @Test fun theEditorMapsAFingerToABandAndAGain() {
        val g = EqualizerView.Geometry(1000f, 240f, 28f, 30f)
        assertEquals(0, g.bandAt(10f))
        assertEquals(9, g.bandAt(995f))
        assertEquals(5, g.bandAt(g.xFor(5) + 20f))
        assertEquals(12.0, g.dbAt(g.top), 0.0)
        assertEquals(-12.0, g.dbAt(g.bottom + 50f), 0.0)
        assertEquals(0.0, g.dbAt(g.yFor(0.0)), 0.0)
        assertEquals(6.0, g.dbAt(g.yFor(6.0)), 0.0)
    }

    @Test fun bandLabelsReadAsAHeadUnitWritesThem() {
        assertEquals(listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k"),
            Equalizer.BANDS_HZ.map { Equalizer.bandLabel(it) })
    }
}
