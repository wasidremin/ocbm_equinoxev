package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.InsightsData

/**
 * [chartGeom] and [routeGeom]: pure geometry tests without Android views.
 */
class InsightsChartTest {
    private fun pts(vararg v: Double) = v.mapIndexed { i, x -> InsightsData.SeriesPoint(i.toDouble(), x) }

    @Test fun eachSeriesUsesItsOwnRange() {
        val g = chartGeom(pts(60000.0, 59000.0), pts(100.0, 200.0), 1000f, 500f, 50f)
        // Battery falls: first point at the top, second at the bottom.
        assertTrue(g.ptsA[0].second < g.ptsA[1].second)
        // Range rises: first point at the bottom, second at the top.
        assertTrue(g.ptsB[0].second > g.ptsB[1].second)
        // Both share the time axis: same x for the same index.
        assertEquals(g.ptsA[0].first, g.ptsB[0].first, 0.001f)
        assertEquals(g.ptsA[1].first, g.ptsB[1].first, 0.001f)
    }

    @Test fun flatSeriesParksMidBox() {
        val g = chartGeom(pts(5.0, 5.0), emptyList(), 1000f, 500f, 50f)
        assertEquals(2, g.ptsA.size)
        // Zero span must not NaN: both points land on the same scanline inside the box.
        assertEquals(g.ptsA[0].second, g.ptsA[1].second, 0.001f)
        assertTrue(g.ptsA[0].second > 50f && g.ptsA[0].second < 450f)
        assertTrue(g.ptsB.isEmpty())
        assertEquals("", g.labelB)
    }

    @Test fun labelsSummariseRanges() {
        val g = chartGeom(pts(60000.0, 59000.0), emptyList(), 1000f, 500f, 50f)
        assertTrue(g.labelA.contains("59.0k") && g.labelA.contains("60.0k"))
    }

    @Test fun routeGeomProjectsInsideBounds() {
        val gps = listOf(
            InsightsData.GpsPoint(0.0, 42.3314, -83.0458, 5.0, 10.0),
            InsightsData.GpsPoint(10.0, 42.3414, -83.0358, 5.0, 15.0),
            InsightsData.GpsPoint(20.0, 42.3514, -83.0258, 5.0, 20.0),
        )
        val proj = routeGeom(gps, 800f, 400f, 30f)
        assertEquals(3, proj.size)
        for ((x, y) in proj) {
            assertTrue(x in 29.9f..770.1f)
            assertTrue(y in 29.9f..370.1f)
        }
        // Northward (higher lat) maps toward top (smaller y); eastward (less negative lon) maps right (larger x).
        assertTrue(proj.first().first < proj.last().first)
        assertTrue(proj.first().second > proj.last().second)
    }

    @Test fun fmtShortStaysCompact() {
        assertEquals("60.0k", fmtShort(60000.0))
        assertEquals("27.8", fmtShort(27.8))
        assertEquals("1500", fmtShort(1500.0))
        assertEquals("—", fmtShort(Double.NaN))
    }
}
