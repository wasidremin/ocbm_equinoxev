package wasidremin.gmccpa.telemetry

import org.junit.Assert.*
import org.junit.Test

class ProbeUploadTest {
    @Test fun endpointAppendsProbePath() {
        assertEquals(
            "https://wasidremin.ddnsgeek.com/cloudbridge/probe",
            ProbeUpload.endpointFor("https://wasidremin.ddnsgeek.com/cloudbridge/"),
        )
        assertEquals("https://h/probe", ProbeUpload.endpointFor("https://h"))
    }
    @Test fun namesAreSanitized() {
        assertEquals("20261014-120000-000", ProbeUpload.sanitizeName("20261014-120000-000"))
        assertEquals("a_b_c", ProbeUpload.sanitizeName("a/b\\c"))
        assertEquals("report", ProbeUpload.sanitizeName("../.."))
        assertEquals("report", ProbeUpload.sanitizeName(null))
        assertTrue(ProbeUpload.sanitizeName("x".repeat(200)).length <= 64)
    }
    @Test fun cleartextIsRefused() {
        try {
            ProbeUpload.checkHttps("http://192.168.1.2:8780")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
        ProbeUpload.checkHttps("https://wasidremin.ddnsgeek.com/cloudbridge")
    }
    @Test fun missingFileFailsBeforeNetworking() {
        try {
            ProbeUpload.uploadSync(java.io.File("/nonexistent/report.json"), "x", "https://example.com")
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) { }
    }

    // ---- the queue: one marker per segment, a ceiling on the backlog ----

    private fun segment(root: java.io.File, name: String, marker: String? = null): java.io.File {
        val dir = java.io.File(root, name).apply { mkdirs() }
        java.io.File(dir, "report.json").writeText("{\"properties\":[]}")
        marker?.let { java.io.File(dir, it).writeText("x") }
        return dir
    }
    private fun tempRoot() = java.nio.file.Files.createTempDirectory("probe-queue").toFile()

    @Test fun onlyMarkedSegmentsArePending() {
        val root = tempRoot()
        try {
            segment(root, "20261111-080000-000", ProbeUpload.PENDING_MARKER)
            segment(root, "20261111-080100-000", ProbeUpload.UPLOADED_MARKER)
            segment(root, "20261111-080200-000", ProbeUpload.SKIPPED_MARKER)
            segment(root, "20261111-080300-000")   // being written, or orphaned by a killed process
            assertEquals(listOf("20261111-080000-000"), ProbeUpload.listPendingReportDirs(root).map { it.name })
        } finally { root.deleteRecursively() }
    }
    @Test fun aSegmentIsInOneStateAtATime() {
        val root = tempRoot()
        try {
            val dir = segment(root, "a")
            fun has(m: String) = java.io.File(dir, m).isFile
            assertTrue(ProbeUpload.markReportPending(dir))
            ProbeUpload.markReportSkipped(dir, "parked")
            assertTrue(has(ProbeUpload.SKIPPED_MARKER)); assertFalse(has(ProbeUpload.PENDING_MARKER))
            assertTrue(ProbeUpload.markReportPending(dir))
            assertTrue(has(ProbeUpload.PENDING_MARKER)); assertFalse(has(ProbeUpload.SKIPPED_MARKER))
            ProbeUpload.markReportUploaded(dir, "ref")
            assertTrue(has(ProbeUpload.UPLOADED_MARKER)); assertFalse(has(ProbeUpload.PENDING_MARKER))
        } finally { root.deleteRecursively() }
    }
    @Test fun pruningSparesWhatIsWaitingAndWhatIsBeingWritten() {
        val root = tempRoot()
        try {
            val sent = segment(root, "01", ProbeUpload.UPLOADED_MARKER)
            val skipped = segment(root, "02", ProbeUpload.SKIPPED_MARKER)
            val orphan = segment(root, "03")
            val waiting = segment(root, "04", ProbeUpload.PENDING_MARKER)
            val live = segment(root, "05")
            val newer = segment(root, "06", ProbeUpload.UPLOADED_MARKER)
            ProbeUpload.pruneOldReports(root, maxKeep = 2, keep = live)
            assertFalse(sent.exists()); assertFalse(skipped.exists()); assertFalse(orphan.exists())
            assertTrue(waiting.exists()); assertTrue(live.exists())
            // Four had to go to reach two, and only four were removable: the newest sent one went too.
            assertFalse(newer.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun pruningLeavesAShortFolderAlone() {
        val root = tempRoot()
        try {
            val a = segment(root, "01", ProbeUpload.SKIPPED_MARKER)
            ProbeUpload.pruneOldReports(root, maxKeep = 2, keep = null)
            assertTrue(a.exists())
        } finally { root.deleteRecursively() }
    }
    @Test fun aBacklogIsCappedByAgeAndByCount() {
        val hour = 3_600_000L
        val now = 100 * hour
        // Oldest first: two from last week, then 70 from the last hour.
        val pending = listOf("w1" to now - 150 * hour, "w2" to now - 25 * hour) +
            (1..70).map { "m$it" to now - hour + it * 1000L }
        val (send, drop) = ProbeUpload.selectBacklog(pending, now)
        assertEquals(ProbeUpload.MAX_BACKLOG_SEGMENTS, send.size)
        assertEquals("m11", send.first()); assertEquals("m70", send.last())
        assertEquals(listOf("w1", "w2") + (1..10).map { "m$it" }, drop)
    }
    @Test fun aFreshShortQueueGoesWhole() {
        val now = 1_000_000_000L
        val pending = listOf("a" to now - 5000, "b" to now - 1000)
        assertEquals(listOf("a", "b") to emptyList<String>(), ProbeUpload.selectBacklog(pending, now))
        assertEquals(emptyList<String>() to emptyList<String>(), ProbeUpload.selectBacklog(emptyList<Pair<String, Long>>(), now))
    }
    @Test fun flushNoLongerDependsOnTheUploadSwitch() {
        assertTrue(ProbeModel.shouldFlushChunk(capturing = true, eventCount = 10, elapsedSinceFlushMs = 60_000L))
        assertFalse(ProbeModel.shouldFlushChunk(capturing = true, eventCount = 9, elapsedSinceFlushMs = 60_000L))
        assertFalse(ProbeModel.shouldFlushChunk(capturing = false, eventCount = 50, elapsedSinceFlushMs = 600_000L))
        assertTrue(ProbeModel.shouldFlushChunk(capturing = true, eventCount = 0, elapsedSinceFlushMs = 0L, forceFlush = true))
    }
}
