package wasidremin.gmccpa.sound

import org.junit.Assert.assertEquals
import org.junit.Test
import wasidremin.gmccpa.sound.SpeedLoudness.Gains
import wasidremin.gmccpa.sound.SpeedLoudness.Level

class SpeedLoudnessTest {
    @Test fun nothingInTownAllOfItOnTheHighway() {
        assertEquals(Gains(0.0, 0.0), SpeedLoudness.target(30.0, Level.GENTLE))
        assertEquals(Gains(4.0, 2.5), SpeedLoudness.target(120.0, Level.GENTLE))
        assertEquals(Gains(3.5, 2.0), SpeedLoudness.target(75.0, Level.STRONG))
        assertEquals(Gains(0.0, 0.0), SpeedLoudness.target(120.0, Level.OFF))
    }

    @Test fun theLiftGlidesAtHalfADecibelASecond() {
        val from = Gains(0.0, 0.0); val to = Gains(4.0, 2.5)
        assertEquals(Gains(0.5, 0.5), SpeedLoudness.glide(from, to, 1.0))
        assertEquals(Gains(4.0, 2.5), SpeedLoudness.glide(Gains(3.9, 2.4), to, 1.0))
        // A long gap (a paused stream) moves it no more than one second's worth.
        assertEquals(Gains(0.5, 0.5), SpeedLoudness.glide(from, to, 30.0))
        // Down as smoothly as up: braking to a stop never drops the bass in one step.
        assertEquals(Gains(3.5, 2.0), SpeedLoudness.glide(to, from, 1.0))
    }

    @Test fun anUnknownSettingIsTheDefault() {
        assertEquals(Level.GENTLE, SpeedLoudness.fromKey(null))
        assertEquals(Level.STRONG, SpeedLoudness.fromKey("strong"))
    }

    @Test fun theLogLineIsNumbersOnly() {
        assertEquals("loudness by speed (gentle): 105 km/h, bass +3.7 dB, treble +2.3 dB",
            SpeedLoudness.describe(105.0, Gains(3.7, 2.3), Level.GENTLE))
    }
}
