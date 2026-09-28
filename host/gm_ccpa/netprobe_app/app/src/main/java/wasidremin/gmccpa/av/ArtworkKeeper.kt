package wasidremin.gmccpa.av

/**
 * Album-art decisions for one now-playing picture.
 *
 * Plain Kotlin: no Android, no JSON, no logcat. [NowPlayingState] feeds it merged records and
 * JPEG transfers and copies [artworkId] / [artwork] back onto its snapshot. The 2 s id-mismatch
 * timer is scheduled through [afterMs] so the callback never runs under the caller's monitor.
 *
 * iOS sends one JPEG per session and does not resend it when a chapter keeps the same id. A title
 * change therefore keeps the previous JPEG unless the new record names a different id.
 */
class ArtworkKeeper(
    private val log: (String) -> Unit,
    private val afterMs: (delayMs: Long, fire: () -> Boolean) -> Unit,
) {
    var artworkId: Int = -1
        private set
    var artwork: ByteArray? = null
        private set

    private val cacheIds = ArrayDeque<Int>()
    private val cacheBytes = HashMap<Int, ByteArray>()
    private val seenThisTrack = HashSet<Int>()
    private var transfersThisTrack = 0
    private var onlyTransferId = -1
    private var generation = 0

    /**
     * One now-playing record.
     *
     * [trackChanged] is a different non-empty title, including the first title. [recordArtworkId]
     * is null when the record omitted the key (elapsed ticks do). A missing key is not a new id.
     */
    fun onRecord(trackChanged: Boolean, recordArtworkId: Int?) {
        if (trackChanged) {
            generation++
            transfersThisTrack = 0
            onlyTransferId = -1
            seenThisTrack.clear()
        }
        val prevId = artworkId
        val prevArt = artwork
        val newId = when {
            trackChanged && (recordArtworkId == null || recordArtworkId == prevId) -> prevId
            recordArtworkId != null -> recordArtworkId
            else -> prevId
        }
        val newArt = when {
            newId < 0 -> null
            newId == prevId && prevArt != null -> prevArt
            else -> cacheBytes[newId]
        }
        if (newId != prevId) log("np: record artworkId $prevId -> $newId")
        if (trackChanged && prevArt != null && newArt == null) {
            log("np: art dropped on title change (id $prevId -> $newId)")
        }
        artworkId = newId
        artwork = newArt
        if (artwork == null && artworkId >= 0 && (trackChanged || recordArtworkId != null)) {
            generation++
            val gen = generation
            afterMs(MISMATCH_MS) { tryMismatch(gen) }
        }
    }

    /**
     * One JPEG transfer. Always cached (last [CACHE_MAX]). Applied when the id matches, or when
     * the picture has no id and no art yet (adopt). Anything else waits for the mismatch timer.
     *
     * @return `accepted`, `rejected`, or `duplicate`
     */
    fun onTransfer(id: Int, jpeg: ByteArray, titlePresent: Boolean): String {
        val snapId = artworkId
        if (jpeg.isEmpty()) {
            log("np: art id=$id ${jpeg.size}B — rejected (empty) (artworkId=$snapId title=$titlePresent)")
            return "rejected"
        }
        store(id, jpeg)
        if (seenThisTrack.add(id)) {
            transfersThisTrack++
            if (transfersThisTrack == 1) onlyTransferId = id
        }
        if (artworkId == id && artwork != null && artwork!!.contentEquals(jpeg)) {
            log("np: art id=$id ${jpeg.size}B — duplicate (artworkId=$snapId title=$titlePresent)")
            return "duplicate"
        }
        val adopt = artworkId < 0 && artwork == null
        if (adopt || id == artworkId) {
            artwork = jpeg
            artworkId = id
            generation++
            log("np: art id=$id ${jpeg.size}B — accepted (artworkId=$snapId title=$titlePresent)")
            return "accepted"
        }
        log("np: art id=$id ${jpeg.size}B — rejected (id does not match) (artworkId=$snapId title=$titlePresent)")
        return "rejected"
    }

    fun clear() {
        generation++
        cacheIds.clear()
        cacheBytes.clear()
        seenThisTrack.clear()
        transfersThisTrack = 0
        onlyTransferId = -1
        artworkId = -1
        artwork = null
    }

    private fun tryMismatch(gen: Int): Boolean {
        if (gen != generation) return false
        if (artwork != null || artworkId < 0) return false
        if (transfersThisTrack != 1) return false
        val id = onlyTransferId
        val jpeg = cacheBytes[id] ?: return false
        artwork = jpeg
        if (id != artworkId) {
            log("np: art id mismatch — record $artworkId, transfer $id; using the transfer")
        }
        generation++
        return true
    }

    private fun store(id: Int, jpeg: ByteArray) {
        cacheBytes[id] = jpeg
        cacheIds.remove(id)
        cacheIds.addLast(id)
        while (cacheIds.size > CACHE_MAX) {
            cacheBytes.remove(cacheIds.removeFirst())
        }
    }

    private companion object {
        const val CACHE_MAX = 4
        const val MISMATCH_MS = 2_000L
    }
}
