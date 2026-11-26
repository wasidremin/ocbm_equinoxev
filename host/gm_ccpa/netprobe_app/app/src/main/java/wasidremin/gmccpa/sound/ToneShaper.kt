package wasidremin.gmccpa.sound

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * A bass shelf and a treble shelf over CarPlay's media PCM (16-bit little-endian, interleaved), shaped in
 * the app before the car's amplifier: T36 (loudness by speed) sets the two gains, T37 (the equalizer)
 * will add its own. Pure: no Android, so the response is tested on the JVM with sine waves.
 *
 * Filters are the RBJ Audio EQ Cookbook shelves (slope 1), one state per channel, recomputed only when a
 * gain moves by more than [RECOMPUTE_DB]. Boosting can push a mastered track past full scale, so the input
 * is lowered by half the larger boost ([preGainDb]) and the output goes through a soft knee above
 * [KNEE] of full scale instead of wrapping or hard-clipping. With both gains under [BYPASS_DB] the bytes
 * pass untouched.
 */
class ToneShaper(private val sampleRate: Int, private val channels: Int) {
    private val low = Shelf(LOW_HZ, high = false)
    private val high = Shelf(HIGH_HZ, high = true)
    private var lowDb = 0.0
    private var highDb = 0.0
    private var pre = 1.0

    val bassDb: Double get() = lowDb
    val trebleDb: Double get() = highDb

    fun setGains(bassDb: Double, trebleDb: Double) {
        if (abs(bassDb - lowDb) < RECOMPUTE_DB && abs(trebleDb - highDb) < RECOMPUTE_DB) return
        lowDb = bassDb; highDb = trebleDb
        low.design(bassDb); high.design(trebleDb)
        pre = 10.0.pow(preGainDb(bassDb, trebleDb) / 20.0)
    }

    /** In place over [len] bytes of [pcm]. */
    fun process(pcm: ByteArray, len: Int) {
        if (abs(lowDb) < BYPASS_DB && abs(highDb) < BYPASS_DB) return
        val frames = len / (2 * channels)
        var i = 0
        for (f in 0 until frames) {
            for (ch in 0 until channels) {
                val s = ((pcm[i + 1].toInt() shl 8) or (pcm[i].toInt() and 0xFF)).toShort()
                var y = s / 32768.0 * pre
                y = low.step(ch, y)
                y = high.step(ch, y)
                val out = (softClip(y) * 32767.0).roundToInt().coerceIn(-32768, 32767)
                pcm[i] = (out and 0xFF).toByte()
                pcm[i + 1] = ((out shr 8) and 0xFF).toByte()
                i += 2
            }
        }
    }

    private inner class Shelf(private val f0: Double, private val high: Boolean) {
        private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0; private var a1 = 0.0; private var a2 = 0.0
        private val x1 = DoubleArray(channels); private val x2 = DoubleArray(channels)
        private val y1 = DoubleArray(channels); private val y2 = DoubleArray(channels)

        fun design(db: Double) {
            val a = 10.0.pow(db / 40.0)
            val w0 = 2.0 * PI * f0 / sampleRate
            val c = cos(w0)
            val alpha = sin(w0) / 2.0 * sqrt(2.0)
            val k = 2.0 * sqrt(a) * alpha
            val n0: Double; val n1: Double; val n2: Double; val d0: Double; val d1: Double; val d2: Double
            if (high) {
                n0 = a * ((a + 1) + (a - 1) * c + k); n1 = -2 * a * ((a - 1) + (a + 1) * c); n2 = a * ((a + 1) + (a - 1) * c - k)
                d0 = (a + 1) - (a - 1) * c + k; d1 = 2 * ((a - 1) - (a + 1) * c); d2 = (a + 1) - (a - 1) * c - k
            } else {
                n0 = a * ((a + 1) - (a - 1) * c + k); n1 = 2 * a * ((a - 1) - (a + 1) * c); n2 = a * ((a + 1) - (a - 1) * c - k)
                d0 = (a + 1) + (a - 1) * c + k; d1 = -2 * ((a - 1) + (a + 1) * c); d2 = (a + 1) + (a - 1) * c - k
            }
            b0 = n0 / d0; b1 = n1 / d0; b2 = n2 / d0; a1 = d1 / d0; a2 = d2 / d0
        }

        fun step(ch: Int, x: Double): Double {
            val y = b0 * x + b1 * x1[ch] + b2 * x2[ch] - a1 * y1[ch] - a2 * y2[ch]
            x2[ch] = x1[ch]; x1[ch] = x; y2[ch] = y1[ch]; y1[ch] = y
            return y
        }
    }

    companion object {
        const val LOW_HZ = 120.0
        const val HIGH_HZ = 5000.0
        const val BYPASS_DB = 0.05
        const val RECOMPUTE_DB = 0.05
        const val KNEE = 0.9

        /** Pure: the level change ahead of the shelves, half the larger boost (never a lift). */
        fun preGainDb(bassDb: Double, trebleDb: Double): Double = -0.5 * max(0.0, max(bassDb, trebleDb))

        /** Pure: unchanged up to [KNEE], then a smooth bend that never reaches full scale. */
        fun softClip(y: Double): Double {
            val m = abs(y)
            if (m <= KNEE) return y
            return sign(y) * (KNEE + (1.0 - KNEE) * tanh((m - KNEE) / (1.0 - KNEE)))
        }
    }
}
