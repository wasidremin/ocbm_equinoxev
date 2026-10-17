package wasidremin.gmccpa.av

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class CarPlayFrameGrabberTest {
    @Test fun madComputesMeanAbsoluteDifference() {
        val a = FloatArray(CarPlayFrameGrabber.THUMB_W * CarPlayFrameGrabber.THUMB_H) { 0.25f }
        val b = FloatArray(CarPlayFrameGrabber.THUMB_W * CarPlayFrameGrabber.THUMB_H) { 0.25f }
        assertEquals(0f, CarPlayFrameGrabber.meanAbsoluteDifference(a, b), 1e-6f)

        val c = FloatArray(CarPlayFrameGrabber.THUMB_W * CarPlayFrameGrabber.THUMB_H) { 0.25f + (1.5f / 255f) }
        val mad = CarPlayFrameGrabber.meanAbsoluteDifference(a, c)
        assertTrue("expected mad < threshold, got $mad", mad < CarPlayFrameGrabber.DEFAULT_SETTLE_THRESHOLD)
    }

    @Test fun metadataSidecarRoundTripsAllRequiredFields() {
        val meta = CarPlayFrameGrabber.CaptureMetadata(
            captureId = "20261014-120000-000",
            timestampIso = "2026-10-14T12:00:00.000Z",
            appVersion = "4.0+abcdef0",
            gitSha = "abcdef0",
            iosVersion = "iOS 18.1",
            negotiatedWidth = 2400,
            negotiatedHeight = 960,
            displayDensity = 2.0f,
            displayDensityDpi = 320,
            buttonProduced = "capture_home",
            classification = CarPlayFrameGrabber.ScreenClassification.HOME_GRID,
            homePressesUsed = 2,
            msToStableFrame = 342L,
            framesSampled = 4,
            settleMad = 0.0041f,
            windowWidth = 2629,
            windowHeight = 967,
        )
        val json = meta.toJson()
        val parsed = CarPlayFrameGrabber.CaptureMetadata.fromJson(JSONObject(json.toString()))
        assertEquals(meta.captureId, parsed.captureId)
        assertEquals(meta.timestampIso, parsed.timestampIso)
        assertEquals(meta.appVersion, parsed.appVersion)
        assertEquals(meta.gitSha, parsed.gitSha)
        assertEquals(meta.iosVersion, parsed.iosVersion)
        assertEquals(meta.negotiatedWidth, parsed.negotiatedWidth)
        assertEquals(meta.negotiatedHeight, parsed.negotiatedHeight)
        assertEquals(meta.displayDensity, parsed.displayDensity, 1e-4f)
        assertEquals(meta.displayDensityDpi, parsed.displayDensityDpi)
        assertEquals(meta.buttonProduced, parsed.buttonProduced)
        assertEquals(meta.classification, parsed.classification)
        assertEquals(meta.homePressesUsed, parsed.homePressesUsed)
        assertEquals(meta.msToStableFrame, parsed.msToStableFrame)
        assertEquals(meta.framesSampled, parsed.framesSampled)
        assertEquals(meta.settleMad, parsed.settleMad, 1e-5f)
        assertEquals(meta.windowWidth, parsed.windowWidth)
        assertEquals(meta.windowHeight, parsed.windowHeight)
    }

    @Test fun metadataWithoutWindowKeysParsesToZero() {
        // Sidecars written before windowWidth/windowHeight existed must still parse.
        val obj = JSONObject()
            .put("captureId", "20261014-120000-000")
            .put("button", "capture_current")
        val parsed = CarPlayFrameGrabber.CaptureMetadata.fromJson(obj)
        assertEquals(0, parsed.windowWidth)
        assertEquals(0, parsed.windowHeight)
    }

    @Test fun retentionCapDeletesOldestFirstAt50() {
        val tmpRoot = createTempDirectory("captures_test").toFile()
        try {
            for (i in 1..55) {
                val dir = File(tmpRoot, "20261014-1200%02d-000".format(i))
                dir.mkdirs()
                File(dir, "capture.png").writeBytes(byteArrayOf(1, 2, 3))
                File(dir, "metadata.json").writeText("{}")
            }
            CarPlayFrameGrabber.enforceRetentionCap(tmpRoot, 50)
            val remaining = tmpRoot.listFiles()?.filter { it.isDirectory }?.map { it.name }?.sorted() ?: emptyList()
            assertEquals(50, remaining.size)
            assertEquals("20261014-120006-000", remaining.first())
            assertEquals("20261014-120055-000", remaining.last())
        } finally {
            tmpRoot.deleteRecursively()
        }
    }
}
