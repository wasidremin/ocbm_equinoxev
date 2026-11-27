package wasidremin.gmccpa.upload

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.logging.UploadWindow

class LogsOnWifiTest {
    private val hour = 60L * 60 * 1000
    private val tap = 1_796_094_000_000L

    @Test fun aRequestWaitsThreeDaysAndNoLonger() {
        val r = LogsOnWifi.Request(UploadWindow.LAST_HOUR, tap)
        assertFalse(LogsOnWifi.isExpired(r, tap + 71 * hour))
        assertTrue(LogsOnWifi.isExpired(r, tap + 73 * hour))
    }

    @Test fun aRequestStampedInTheFutureIsDropped() {
        // The head unit's clock is corrected after boot; a stamp far ahead of now can never be met.
        assertTrue(LogsOnWifi.isExpired(LogsOnWifi.Request(UploadWindow.LAST_HOUR, tap + 2 * hour), tap))
    }

    @Test fun theFirstTapIsKeptAsIs() {
        val r = LogsOnWifi.Request(UploadWindow.TODAY, tap)
        assertEquals(r, LogsOnWifi.merge(null, r))
    }

    @Test fun aSecondTapKeepsTheEarlierStartAndTheWiderRange() {
        val waiting = LogsOnWifi.Request(UploadWindow.LAST_6_HOURS, tap)
        val merged = LogsOnWifi.merge(waiting, LogsOnWifi.Request(UploadWindow.LAST_HOUR, tap + hour))
        assertEquals(LogsOnWifi.Request(UploadWindow.LAST_6_HOURS, tap), merged)
        val wider = LogsOnWifi.merge(waiting, LogsOnWifi.Request(UploadWindow.ALL, tap + hour))
        assertEquals(LogsOnWifi.Request(UploadWindow.ALL, tap), wider)
    }

    @Test fun theStatusLineNamesTheRange() {
        assertEquals(
            "Logs from the last hour are waiting for Wi-Fi",
            LogsOnWifi.describe(LogsOnWifi.Request(UploadWindow.LAST_HOUR, tap)),
        )
    }

    @Test fun logsSentByHandTakeTheWaitingUploadWithThem() {
        // 2026-11-27: "Today" sent by hand, and the "Today" waiting since earlier taps went 10 s later.
        val waiting = LogsOnWifi.Request(UploadWindow.TODAY, 1_000L)
        val sent = LogsOnWifi.Request(UploadWindow.TODAY, 5_000L)
        assertEquals(null, LogsOnWifi.afterManual(waiting, sent, ok = true))
        assertEquals(null, LogsOnWifi.afterManual(null, sent, ok = true))
        // A waiting request that reaches further back than what was sent still goes.
        val wide = LogsOnWifi.Request(UploadWindow.TODAY, 1_000L)
        assertEquals(wide, LogsOnWifi.afterManual(wide, LogsOnWifi.Request(UploadWindow.LAST_HOUR, 5_000L), ok = true))
    }

    @Test fun logsThatFailedToGoByHandWaitForWiFi() {
        val sent = LogsOnWifi.Request(UploadWindow.LAST_HOUR, 5_000L)
        assertEquals(sent, LogsOnWifi.afterManual(null, sent, ok = false))
        assertEquals(LogsOnWifi.Request(UploadWindow.TODAY, 1_000L),
            LogsOnWifi.afterManual(LogsOnWifi.Request(UploadWindow.TODAY, 1_000L), sent, ok = false))
    }
}
