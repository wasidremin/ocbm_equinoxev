package wasidremin.gmccpa.lyrics

// Ported from enigy/carlink (Unlicense); dates and drives named in these tests are theirs.

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The head unit's lyrics: kept whole, found by song, kept however old, and held under its size by dropping the least played. */
class LyricsDiskCacheTest {
    @get:Rule val tmp = TemporaryFolder()

    private var now = 1_761_700_000_000L // 2025-10-29, any fixed instant
    private val day = 24L * 60 * 60 * 1000
    private val song = LyricsKey("Dream Team", "Of Monsters and Men", "Fever Dream", 189)
    private val lrc = "[00:12.30] We're the dream team\n[00:15.80] Hold on tight"

    private fun cache(
        maxBytes: Long = LyricsDiskCache.MAX_BYTES,
        dir: File = File(tmp.root, "lyrics"),
    ) = LyricsDiskCache(dir, clock = { now }, maxBytes = maxBytes)

    private fun entries(dir: File = File(tmp.root, "lyrics")) = dir.listFiles()?.filter { it.name.endsWith(".json") }.orEmpty()

    @Test
    fun `words kept for a song are found for that song only`() {
        val c = cache()
        assertTrue(c.write(song, lrc))
        assertEquals(lrc, c.read(song))
        assertNull("another length is another recording", c.read(song.copy(durationSec = 300)))
        assertNull(c.read(song.copy(artist = "Chris Taylor")))
        assertEquals("the album is not part of the song's name here", lrc, c.read(song.copy(album = "Dream Team (Live)")))
        assertTrue("one file per song", entries().size == 1)
    }

    @Test
    fun `a new process finds what an earlier one kept`() {
        cache().write(song, lrc)
        assertEquals(lrc, cache().read(song))
    }

    @Test
    fun `an entry cut short or changed is not taken, and goes`() {
        val c = cache()
        c.write(song, lrc)
        val file = entries().single()
        file.writeText(file.readText().dropLast(20))
        assertNull("cut short: not JSON any more", c.read(song))
        assertFalse(file.exists())

        c.write(song, lrc)
        val entry = JSONObject(entries().single().readText()).put("lrc", "$lrc\n[00:20.00] one line more")
        entries().single().writeText(entry.toString())
        assertNull("its words no longer match their SHA-256", c.read(song))
        assertTrue(entries().isEmpty())
    }

    @Test
    fun `words with no timed lines are not lyrics`() {
        val c = cache()
        assertTrue(c.write(song, "just words, no times"))
        assertNull(c.read(song))
    }

    @Test
    fun `entries are kept however old they get`() {
        // Owner, 10-31: kept with no time limit (before, each went after 30 days).
        val c = cache()
        c.write(song, lrc)
        now += 400 * day
        c.prune()
        assertEquals(lrc, cache().read(song))
    }

    @Test
    fun `each play is counted and kept with the entry`() {
        val c = cache()
        c.write(song, lrc)
        assertEquals("written, not yet played", 0, JSONObject(entries().single().readText()).getInt("uses"))
        now += day
        c.noteUse(song)
        c.noteUse(song)
        val entry = JSONObject(entries().single().readText())
        assertEquals(2, entry.getInt("uses"))
        assertEquals(now, entry.getLong("usedAt"))
        assertEquals("still whole", lrc, c.read(song))
        c.noteUse(song.copy(title = "Never Kept"))
        assertEquals("a song not kept is not written", 1, entries().size)
    }

    @Test
    fun `past its size the least played go first, the longest unplayed first among equals`() {
        val probe = cache(dir = File(tmp.root, "probe"))
        probe.write(song.copy(title = "Song 0"), lrc)
        val one = probe.usage().bytes
        // Room for three; a full folder is pruned to 90 %, which also leaves three.
        val c = cache(maxBytes = one * 3 + one / 2)
        val s = (1..5).map { song.copy(title = "Song $it") }
        (0..2).forEach { write(c, s[it]) }
        play(c, s[0])
        play(c, s[0])
        play(c, s[2])
        write(c, s[3])
        assertNull("never played: the first to go, though not the oldest", c.read(s[1]))
        assertEquals("written last: kept, though never played", lrc, c.read(s[3]))
        play(c, s[3])
        write(c, s[4])
        assertNull("played as often as the fourth, but longer ago", c.read(s[2]))
        listOf(s[0], s[3], s[4]).forEach { assertEquals(lrc, c.read(it)) }
        assertTrue(c.usage().bytes <= one * 3 + one / 2)
    }

    /** [s] written to [c] a minute before the next step. */
    private fun write(
        c: LyricsDiskCache,
        s: LyricsKey,
    ) {
        c.write(s, lrc)
        now += 60_000
    }

    /** [s] played once a minute before the next step. */
    private fun play(
        c: LyricsDiskCache,
        s: LyricsKey,
    ) {
        c.noteUse(s)
        now += 60_000
    }

    @Test
    fun `an entry kept before plays were counted counts as played once`() {
        val probe = cache(dir = File(tmp.root, "probe"))
        probe.write(song.copy(title = "Song 0"), lrc)
        val one = probe.usage().bytes
        val c = cache(maxBytes = one * 2 + one / 2)
        val old = song.copy(title = "Song 1")
        c.write(old, lrc)
        // As 2.02-2.04 wrote it: no plays, no last play.
        val file = entries().single()
        val entry = JSONObject(file.readText())
        entry.remove("uses")
        entry.remove("usedAt")
        file.writeText(entry.toString())
        now += day
        val fresh = song.copy(title = "Song 2")
        c.write(fresh, lrc)
        now += 60_000
        c.write(song.copy(title = "Song 3"), lrc)
        assertNull("never played since it was written: goes before the older entry", c.read(fresh))
        assertEquals(lrc, c.read(old))
    }

    @Test
    fun `Settings sees how many songs are kept and their size, and Clear deletes them all`() {
        val c = cache()
        assertEquals(LyricsDiskCache.Usage(0, 0), c.usage())
        c.write(song, lrc)
        c.write(song.copy(title = "Little Talks", durationSec = 266), lrc)
        val kept = c.usage()
        assertEquals(2, kept.songs)
        assertEquals(File(tmp.root, "lyrics").listFiles()!!.sumOf { it.length() }, kept.bytes)
        assertEquals(2, c.clear())
        assertEquals(LyricsDiskCache.Usage(0, 0), c.usage())
        assertNull(c.read(song))
    }

    @Test
    fun `a temp file a power loss left behind is not an entry, and is tidied away`() {
        val dir = File(tmp.root, "lyrics").apply { mkdirs() }
        File(dir, "0123.json.tmp").writeText("{\"v\":1,\"lrc\":\"[00:01.00] half")
        val c = cache()
        assertNull(c.read(song))
        assertFalse("the first use tidies the folder", File(dir, "0123.json.tmp").exists())
    }
}
