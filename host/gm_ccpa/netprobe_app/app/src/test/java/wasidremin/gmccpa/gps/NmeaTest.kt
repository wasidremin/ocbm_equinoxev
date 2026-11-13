package wasidremin.gmccpa.gps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The sentences the adapter forwards to the iPhone (T6). Coordinates here are the NMEA textbook's. */
class NmeaTest {
    // 1994-03-23 12:35:19 UTC, the date of the textbook GPRMC example.
    private val utc = 764426119000L
    private val fix = Nmea.Fix(
        utcMs = utc, latDeg = 48.0 + 7.038 / 60, lonDeg = 11.0 + 31.0 / 60, altitudeM = 545.4,
        speedMps = 22.4 / 1.943844, courseDeg = 84.4, accuracyM = 4.5, satellites = 8,
    )

    /** The adapter's own rule (`is_well_formed_sentence`): `$`, printable, `*HH` that matches, no line ending. */
    private fun wellFormed(s: String): Boolean {
        val star = s.lastIndexOf('*')
        return s.startsWith("$") && star == s.length - 3 && s.all { it in ' '..'~' } &&
            Nmea.checksum(s.substring(1, star)) == s.substring(star + 1)
    }

    @Test fun theChecksumMatchesTheTextbook() {
        assertEquals("47", Nmea.checksum("GPGGA,123519,4807.038,N,01131.000,E,1,08,0.9,545.4,M,46.9,M,,"))
        assertEquals("6A", Nmea.checksum("GPRMC,123519,A,4807.038,N,01131.000,E,022.4,084.4,230394,003.1,W"))
    }

    @Test fun theFixSentenceCarriesTimePositionAndAltitude() {
        assertEquals("\$GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,0.9,545.4,M,,M,,*" +
            Nmea.checksum("GPGGA,123519.00,4807.0380,N,01131.0000,E,1,08,0.9,545.4,M,,M,,"), Nmea.gga(fix))
        assertTrue(wellFormed(Nmea.gga(fix)))
    }

    @Test fun theTransitSentenceCarriesSpeedCourseAndDate() {
        val rmc = Nmea.rmc(fix)
        assertTrue(rmc, rmc.startsWith("\$GPRMC,123519.00,A,4807.0380,N,01131.0000,E,22.4,84.4,230394,,,A*"))
        assertTrue(wellFormed(rmc))
        // No speed or course from the receiver: the fields stay empty, the sentence stays valid.
        val bare = Nmea.rmc(fix.copy(speedMps = null, courseDeg = null))
        assertTrue(bare, bare.contains(",E,,,230394,"))
        assertTrue(wellFormed(bare))
    }

    @Test fun southAndWestAreHemispheresNotSigns() {
        val gga = Nmea.gga(fix.copy(latDeg = -33.5, lonDeg = -70.25))
        assertTrue(gga, gga.contains(",3330.0000,S,07015.0000,W,"))
        // 59.99996 minutes rounds up into the next degree; it must never print as 60 minutes.
        val edge = Nmea.gga(fix.copy(latDeg = 10.0 + 59.99996 / 60))
        assertTrue(edge, edge.contains(",1100.0000,N,"))
    }

    @Test fun headingIsSentOnlyWhileMoving() {
        assertTrue(wellFormed(Nmea.hdt(fix)!!))
        assertTrue(Nmea.hdt(fix)!!.startsWith("\$GPHDT,84.4,T*"))
        assertTrue(Nmea.hdt(fix.copy(courseDeg = -90.0))!!.startsWith("\$GPHDT,270.0,T*"))
        assertNull(Nmea.hdt(fix.copy(speedMps = 0.3)))
        assertNull(Nmea.hdt(fix.copy(courseDeg = null)))
    }

    /** Apple's wheel-speed sentence: sensor seconds, combined speed, gear, no slip, one sample at offset 0. */
    @Test fun wheelSpeedFollowsApplesLayout() {
        assertEquals("\$PASCD,12.345,C,D,0,1,0.00,5.200*" + Nmea.checksum("PASCD,12.345,C,D,0,1,0.00,5.200"),
            Nmea.pascd(5.2, Nmea.Gear.DRIVE, 12_345))
        // The sensor clock wraps every day; reversing is a gear, and the speed is never negative.
        assertTrue(Nmea.pascd(-1.5, Nmea.Gear.REVERSE, 86_400_000L + 2_000)!!.startsWith("\$PASCD,2.000,C,R,0,1,0.00,1.500*"))
        assertTrue(Nmea.pascd(-1.5, Nmea.Gear.UNKNOWN, 0)!!.contains(",C,R,"))
        assertTrue(Nmea.pascd(0.0, Nmea.Gear.PARK, 0)!!.contains(",C,P,0,1,0.00,0.000*"))
        assertNull(Nmea.pascd(Double.NaN, Nmea.Gear.DRIVE, 0))
        assertTrue(wellFormed(Nmea.pascd(31.25, Nmea.Gear.DRIVE, 999_999)!!))
    }

    @Test fun aFixBecomesOneLinePerSentence() {
        val lines = Nmea.sentences(fix, 11.5, Nmea.Gear.DRIVE, 5_000).split("\n")
        assertEquals(listOf("\$GPGGA", "\$GPRMC", "\$GPHDT", "\$PASCD"), lines.map { it.substring(0, 6) })
        assertTrue(lines.all(::wellFormed))
        // Standing still with no wheel speed: the fix and the transit sentence only.
        val parked = Nmea.sentences(fix.copy(speedMps = 0.0), null, Nmea.Gear.PARK, 5_000).split("\n")
        assertEquals(listOf("\$GPGGA", "\$GPRMC"), parked.map { it.substring(0, 6) })
        // The whole frame fits the adapter's limit with room to spare.
        assertTrue(Nmea.sentences(fix, 11.5, Nmea.Gear.DRIVE, 5_000).length < 400)
    }

    /** No fix, no sentences: an empty or impossible position must never reach the phone. */
    @Test fun anUnusableFixSendsNothing() {
        assertEquals("", Nmea.sentences(fix.copy(latDeg = 0.0, lonDeg = 0.0), 5.0, Nmea.Gear.DRIVE, 0))
        assertEquals("", Nmea.sentences(fix.copy(latDeg = Double.NaN), 5.0, Nmea.Gear.DRIVE, 0))
        assertEquals("", Nmea.sentences(fix.copy(latDeg = 91.0), 5.0, Nmea.Gear.DRIVE, 0))
        assertEquals("", Nmea.sentences(fix.copy(utcMs = 0), 5.0, Nmea.Gear.DRIVE, 0))
    }

    /** A decimal comma would break every field. */
    @Test fun theCarsLanguageDoesNotChangeTheNumbers() {
        val saved = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertTrue(Nmea.sentences(fix, 11.5, Nmea.Gear.DRIVE, 5_000).split("\n").all(::wellFormed))
            assertTrue(Nmea.gga(fix).contains(",545.4,M,"))
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
