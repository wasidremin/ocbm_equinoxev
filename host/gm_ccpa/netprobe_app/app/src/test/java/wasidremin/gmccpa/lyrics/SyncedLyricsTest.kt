package wasidremin.gmccpa.lyrics

// Ported from enigy/carlink (Unlicense); dates and drives named in these tests are theirs.

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncedLyricsTest {
    private val lrc =
        """
        [ar:Someone]
        [00:18.69] first line
        [00:22.5] second line
        [00:30.00]
        [00:31.123][01:05.00] chorus
        not timed
        """.trimIndent()

    @Test
    fun `timed lines are read, tags and untimed lines dropped, a twice-sung line placed twice`() {
        val l = SyncedLyrics.parse(lrc)!!
        assertEquals(listOf(18_690L, 22_500L, 30_000L, 31_123L, 65_000L), l.lines.map { it.atMs })
        assertEquals(listOf("first line", "second line", "", "chorus", "chorus"), l.lines.map { it.text })
    }

    @Test
    fun `the sung line is the last one started`() {
        val l = SyncedLyrics.parse(lrc)!!
        assertEquals("before the first", -1, l.indexAt(0L))
        assertEquals(0, l.indexAt(18_690L))
        assertEquals(0, l.indexAt(22_499L))
        assertEquals(1, l.indexAt(22_500L))
        assertEquals("an instrumental gap", 2, l.indexAt(30_500L))
        assertEquals(4, l.indexAt(300_000L))
    }

    @Test
    fun `text with no timed line is none`() {
        assertNull(SyncedLyrics.parse("just words\nno stamps"))
        assertNull(SyncedLyrics.parse(""))
    }
}
