package wasidremin.gmccpa.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * The Logs page now offers "1 hour / 6 hours / Today / All" and defaults to the shortest. These are
 * the pieces that decide what an upload actually contains, so the boundaries are pinned here rather
 * than verified by uploading 227 MB from a head unit.
 *
 * Everything under test is pure (`java.*` only), so this runs on plain JVM JUnit like the rest of the
 * suite — no device, no Robolectric.
 */
class UploadWindowTest {

    private fun epoch(pattern: String, text: String, zone: TimeZone = utc): Long =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = zone }.parse(text)!!.time

    private fun stamp(line: String): String? = LogTimeFilter.stampedPrefix(line)

    // ---- the selector ---------------------------------------------------------------------

    @Test fun defaultIsOneHour() {
        assertEquals(UploadWindow.LAST_HOUR, UploadWindow.DEFAULT)
        // 227 MB/h of whole-OS capture makes "everything" an unusable default.
        assertTrue(UploadWindow.values().first() == UploadWindow.LAST_HOUR)
    }

    @Test fun labelsAreTheFourRangesTheOperatorSees() {
        assertEquals(
            listOf("1 hour", "6 hours", "Today", "All"),
            UploadWindow.values().map { it.label },
        )
    }

    @Test fun unknownPersistedValueFallsBackToDefault() {
        assertEquals(UploadWindow.LAST_HOUR, UploadWindow.fromName(null))
        assertEquals(UploadWindow.LAST_HOUR, UploadWindow.fromName("SINCE_MONDAY"))
        assertEquals(UploadWindow.TODAY, UploadWindow.fromName("TODAY"))
    }

    // ---- cutoff arithmetic ----------------------------------------------------------------

    @Test fun rollingWindowsAreExactHours() {
        val now = epoch("yyyy-MM-dd HH:mm:ss", "2026-10-22 14:30:00")
        assertEquals(now - 3_600_000L, uploadCutoffMs(UploadWindow.LAST_HOUR, now))
        assertEquals(now - 6 * 3_600_000L, uploadCutoffMs(UploadWindow.LAST_6_HOURS, now))
        assertNull(uploadCutoffMs(UploadWindow.ALL, now))
    }

    /** "Today" is local midnight, not a rolling 24 h and not UTC midnight. */
    @Test fun todayIsLocalMidnight() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")   // UTC+9, no DST
        val now = epoch("yyyy-MM-dd HH:mm:ss", "2026-10-22 09:00:00", tokyo)
        val cutoff = uploadCutoffMs(UploadWindow.TODAY, now, tokyo)!!
        assertEquals(epoch("yyyy-MM-dd HH:mm:ss", "2026-10-22 00:00:00", tokyo), cutoff)
        // …and the same instant in UTC is the previous day, which is why the zone is a parameter.
        assertEquals("2026-10-21 15:00:00", utcStamp(cutoff))
    }

    /** A DST fold makes one local day 25 h long; day arithmetic must not fall an hour short. */
    @Test fun todaySurvivesADstFold() {
        val ny = TimeZone.getTimeZone("America/New_York")
        val now = epoch("yyyy-MM-dd HH:mm:ss", "2026-11-01 12:00:00", ny)     // the 25 h day itself
        val cutoff = uploadCutoffMs(UploadWindow.TODAY, now, ny)!!
        assertEquals(epoch("yyyy-MM-dd HH:mm:ss", "2026-11-01 00:00:00", ny), cutoff)
        assertEquals(25 * 3_600_000L - 12 * 3_600_000L, now - cutoff)         // 13 h into a 25 h day
    }

    // ---- line acceptance ------------------------------------------------------------------

    @Test fun fullStampsCompareAsUtcStrings() {
        val cutoff = epoch("yyyy-MM-dd HH:mm:ss", "2026-10-22 13:00:00")
        val filter = LogTimeFilter.forCutoff(cutoff, utc)!!
        assertFalse(filter.accepts("2026-10-22 12:59:59.999  123  123 I Tag : older"))
        assertTrue(filter.accepts("2026-10-22 13:00:00.000  123  123 I Tag : exactly the boundary"))
        assertTrue(filter.accepts("2026-10-22 13:04:00.500  123  123 I Tag : newer"))
        // A line whose stamp is in the future (clock moved backwards) is kept, never dropped.
        assertTrue(filter.accepts("2026-10-22 18:00:00.000  123  123 I Tag : ahead"))
    }

    @Test fun unstampedLinesAreKept() {
        val filter = LogTimeFilter.forCutoff(0L, utc)!!
        // Capture headers, seam notices and logcat buffer separators carry no stamp; dropping them
        // would leave an uploaded artifact with no provenance and no readable boundary.
        assertTrue(filter.accepts("##### netprobe capture scope=WHOLE_OS version=4.0+210 #####"))
        assertTrue(filter.accepts("--------- beginning of main"))
        assertTrue(filter.accepts("SEAM RESYNC GAVE UP after 5000 lines"))
        assertTrue(filter.accepts(""))
    }

    /** The `-v year`/`-v UTC` probe can fall back to bare threadtime, whose stamps are LOCAL. */
    @Test fun bareStampsCompareAgainstLocalTime() {
        val tokyo = TimeZone.getTimeZone("Asia/Tokyo")
        // 2026-10-22 01:00 UTC == 10:00 in Tokyo.
        val cutoff = epoch("yyyy-MM-dd HH:mm:ss", "2026-10-22 01:00:00")
        val filter = LogTimeFilter.forCutoff(cutoff, tokyo)!!
        assertFalse(filter.accepts("10-22 09:59:00.000  123  123 I Tag : an hour before"))
        assertTrue(filter.accepts("10-22 10:00:00.000  123  123 I Tag : at local cutoff"))
        assertTrue(filter.accepts("10-22 10:30:00.123  123  123 I Tag : after"))
    }

    @Test fun allWindowHasNoFilter() {
        assertNull(LogTimeFilter.forCutoff(null))
    }

    @Test fun stampDetectionRecognisesBothFormats() {
        assertEquals("2026-10-22 13:00:00", stamp("2026-10-22 13:00:00.123  1  1 I Tag: x"))
        assertEquals("10-22 13:00:00", stamp("10-22 13:00:00.123  1  1 I Tag: x"))
        assertNull(stamp("##### header #####"))
        assertNull(stamp("2026-10-22T13:00:00Z odd shape"))
        assertNotNull(stamp("2026-10-22 13:00:00"))
    }

    private fun utcStamp(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
            .apply { timeZone = utc }
            .format(java.util.Date(ms))

    private companion object {
        val utc: TimeZone = TimeZone.getTimeZone("UTC")
    }
}
