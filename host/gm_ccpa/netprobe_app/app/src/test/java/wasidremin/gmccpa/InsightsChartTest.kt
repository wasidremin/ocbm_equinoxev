package wasidremin.gmccpa

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import wasidremin.gmccpa.telemetry.InsightsData

/**
 * [chartGeom]: each series normalises onto its own min/max inside the padded box, sharing the
 * time axis. Pure math — no views.
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

    @Test fun fmtShortStaysCompact() {
        assertEquals("60.0k", fmtShort(60000.0))
        assertEquals("27.8", fmtShort(27.8))
        assertEquals("1500", fmtShort(1500.0))
        assertEquals("—", fmtShort(Double.NaN))
    }
}
