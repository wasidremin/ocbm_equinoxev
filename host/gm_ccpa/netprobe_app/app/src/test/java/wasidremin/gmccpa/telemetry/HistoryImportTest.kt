package wasidremin.gmccpa.telemetry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryImportTest {
    private val hour = 3_600_000L
    private fun drive(t0: Long, minutes: Int = 30, lat: Double? = null) =
        Trip(Trip.Kind.DRIVE, t0, t0 + minutes * 60_000L, -300, 60_000.0, 57_000.0, lat0 = lat, lon0 = lat, distM = 20_000.0, movingS = 1500.0)
    private fun charge(t0: Long) = Trip(Trip.Kind.CHARGE, t0, t0 + 2 * hour, -300, 40_000.0, 60_000.0, capWh = 85_000.0)

    @Test fun onlyRecordsTheCarDidNotRecordItselfAreAdded() {
        val own = listOf(drive(100 * hour), charge(110 * hour))
        val offered = listOf(
            drive(10 * hour), charge(20 * hour),
            drive(100 * hour + 60_000),   // the same drive, replayed a minute off
            charge(110 * hour),
            drive(111 * hour),            // a drive during the car's own charge record is still a drive
        )
        val add = HistoryImport.fresh(own, offered)
        assertEquals(listOf(10 * hour, 20 * hour, 111 * hour), add.map { it.t0 })
    }

    @Test fun aFileOfferedTwiceAddsNothingTheSecondTime() {
        val offered = listOf(drive(10 * hour), drive(10 * hour), charge(20 * hour))
        val first = HistoryImport.fresh(emptyList(), offered)
        assertEquals(2, first.size)
        assertEquals(0, HistoryImport.fresh(first, offered).size)
    }

    @Test fun theFileIsOneRecordALineAndBrokenLinesAreSkipped() {
        val text = drive(10 * hour).toJson().toString() + "\n\nnot json\n{\"k\":\"x\"}\n" + charge(20 * hour).toJson().toString() + "\n"
        val got = HistoryImport.parse(text)
        assertEquals(2, got.size)
        assertEquals(Trip.Kind.CHARGE, got[1].kind)
        assertNull(got[0].lat0)
    }

    @Test fun theAddressIsTheReceiversHistoryPath() {
        assertEquals("https://example.org/base/history?car=0123456789abcdef",
            HistoryImport.endpointFor(" https://example.org/base/ ", "0123456789abcdef"))
    }
}
