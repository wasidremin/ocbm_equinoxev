package wasidremin.gmccpa.match

import org.junit.Assert.*
import org.junit.Test

/**
 * v101 diagnostics tests for [ScreenModel.surfaceDetailed]: the detailed result must
 * preserve the legacy [ScreenModel.surface] classification exactly, both glyph scores
 * must come from the same supplied frame, and missing templates must read as explicit
 * unavailable scores rather than a silent classification.
 */
class SurfaceEvidenceTest {

    private val homeGrid by lazy { ScreenModelTest.loadForTest("cap2_home_grid.png") }
    private val dashboard by lazy { ScreenModelTest.loadForTest("cap3_dashboard.png") }

    private fun glyphs(): Pair<IconMatcher.IconTemplate, IconMatcher.IconTemplate> {
        val dash = ScreenModel.cutTemplate(dashboard, ScreenModel.Kind.GLYPH, "dashboard", 0.029f, 0.915f, "cap3", null)
        val other = ScreenModel.cutTemplate(homeGrid, ScreenModel.Kind.GLYPH, "other", 0.029f, 0.915f, "cap2", null)
        return dash to other
    }

    @Test fun detailedPreservesLegacyClassification() {
        val (dash, other) = glyphs()
        for (frame in listOf(homeGrid, dashboard, ScreenModelTest.loadForTest("cap1_in_app.png"))) {
            val ev = ScreenModel.surfaceDetailed(frame, dash, other)
            assertEquals(
                "classification diverged on ${frame.width}x${frame.height}",
                ScreenModel.surface(frame, dash, other),
                ev.surface,
            )
        }
    }

    @Test fun homeGridAndDashboardSeparate() {
        val (dash, other) = glyphs()
        assertEquals(ScreenModel.Surface.NOT_DASHBOARD, ScreenModel.surfaceDetailed(homeGrid, dash, other).surface)
        assertEquals(ScreenModel.Surface.DASHBOARD, ScreenModel.surfaceDetailed(dashboard, dash, other).surface)
    }

    @Test fun scoresComeFromTheSameSuppliedFrame() {
        val (dash, other) = glyphs()
        val ev = ScreenModel.surfaceDetailed(homeGrid, dash, other)
        val geo = ScreenModel.geometry(homeGrid)
        val d = ScreenModel.find(homeGrid, dash, ScreenModel.Kind.GLYPH, geo)
        val o = ScreenModel.find(homeGrid, other, ScreenModel.Kind.GLYPH, geo)
        assertNotNull(ev.dashboardScore)
        assertNotNull(ev.otherScore)
        assertEquals(d.score, ev.dashboardScore!!, 1e-6f)
        assertEquals(o.score, ev.otherScore!!, 1e-6f)
        assertTrue(ev.dashboardScore!!.isFinite())
        assertTrue(ev.otherScore!!.isFinite())
    }

    @Test fun missingTemplatesAreExplicitUnknown() {
        val (dash, other) = glyphs()
        val noDash = ScreenModel.surfaceDetailed(homeGrid, null, other)
        assertEquals(ScreenModel.Surface.UNKNOWN, noDash.surface)
        assertNull(noDash.dashboardScore)
        assertNull(noDash.otherScore)
        val noOther = ScreenModel.surfaceDetailed(homeGrid, dash, null)
        assertEquals(ScreenModel.Surface.UNKNOWN, noOther.surface)
        assertNull(noOther.dashboardScore)
        assertNull(noOther.otherScore)
        val neither = ScreenModel.surfaceDetailed(homeGrid, null, null)
        assertEquals(ScreenModel.Surface.UNKNOWN, neither.surface)
    }
}
