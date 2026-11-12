package wasidremin.gmccpa.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.upload.DataMeter.Ledger
import wasidremin.gmccpa.upload.DataMeter.Stream
import java.util.TimeZone

class DataMeterTest {
    private val mb = 1024L * 1024L
    private val chicago = TimeZone.getTimeZone("America/Chicago")

    @Test fun `the month is the driver's calendar month, not UTC's`() {
        // 2026-12-01 03:00 UTC is still 30 November in Chicago.
        val t = 1_796_094_000_000L
        assertEquals("2026-12", DataMeter.monthKey(t, TimeZone.getTimeZone("UTC")))
        assertEquals("2026-11", DataMeter.monthKey(t, chicago))
    }

    @Test fun `a new month starts from zero and the same month keeps its tally`() {
        val nov = Ledger("2026-11").plus(Stream.TELEMETRY, 40 * mb, onWifi = false)
        assertEquals(40 * mb, nov.rolledTo("2026-11").carTotal)
        assertEquals(Ledger("2026-12"), nov.rolledTo("2026-12"))
    }

    @Test fun `only what is not sent over Wi-Fi counts against the car`() {
        val l = Ledger("2026-11")
            .plus(Stream.TELEMETRY, 10 * mb, onWifi = false)
            .plus(Stream.LOGS, 5 * mb, onWifi = false)
            .plus(Stream.TELEMETRY, 300 * mb, onWifi = true)
            .plus(Stream.TELEMETRY, 2 * mb, onWifi = false)
        assertEquals(17 * mb, l.carTotal)
        assertEquals(12 * mb, l.car[Stream.TELEMETRY])
        assertEquals(300 * mb, l.wifi)
        assertEquals(l, l.plus(Stream.OTHER, 0L, onWifi = false))
    }

    @Test fun `the limit trips at the limit and zero means none`() {
        val at99 = Ledger("2026-11").plus(Stream.TELEMETRY, 100 * mb - 1, false)
        assertFalse(DataMeter.overLimit(at99, 100))
        assertTrue(DataMeter.overLimit(at99.plus(Stream.LOGS, 1, false), 100))
        assertTrue(DataMeter.overLimit(at99, 50))
        assertFalse(DataMeter.overLimit(at99.plus(Stream.LOGS, 900 * mb, false), 0))
    }

    @Test fun `megabytes read plainly`() {
        assertEquals("0 MB", DataMeter.formatMb(0))
        assertEquals("under 0.1 MB", DataMeter.formatMb(40_000))
        assertEquals("12.5 MB", DataMeter.formatMb(12 * mb + mb / 2))
        assertEquals("250 MB", DataMeter.formatMb(250 * mb))
        assertEquals("No limit", DataMeter.limitLabel(0))
        assertEquals("100 MB", DataMeter.limitLabel(100))
    }

    @Test fun `the settings line names the total, the split and the state`() {
        val l = Ledger("2026-11")
            .plus(Stream.TELEMETRY, 30 * mb, false).plus(Stream.LOGS, 12 * mb, false).plus(Stream.OTHER, 10_000, false)
        assertEquals(
            "42.0 MB of 100 MB sent over the car's connection (telemetry 30.0 MB, logs 12.0 MB). " +
                "Telemetry uploads pause at the limit.",
            DataMeter.describe(l, 100),
        )
        assertTrue(DataMeter.describe(l, 25).endsWith("The limit is reached: telemetry uploads are paused until next month or Wi-Fi."))
        assertEquals(
            "0 MB sent over the car's connection. 8.0 MB more over Wi-Fi, which is not counted. Background telemetry is not limited.",
            DataMeter.describe(Ledger("2026-11").plus(Stream.LOGS, 8 * mb, true), 0),
        )
    }

    @Test fun `the default limit is one of the choices`() {
        assertTrue(DataMeter.DEFAULT_LIMIT_MB in DataMeter.LIMIT_CHOICES_MB)
    }
}
