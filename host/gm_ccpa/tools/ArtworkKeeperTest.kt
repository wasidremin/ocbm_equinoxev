package wasidremin.gmccpa.av

import kotlin.system.exitProcess

/**
 * Plain Kotlin checks for [ArtworkKeeper]. No Android.
 *
 *   kotlinc ArtworkKeeper.kt ArtworkKeeperTest.kt -include-runtime -d /tmp/ArtworkKeeperTest.jar
 *   java -jar /tmp/ArtworkKeeperTest.jar
 */
fun main() {
    sameIdKeepsArt()
    absentIdKeepsArt()
    differentIdClears()
    differentIdUsesCache()
    artBeforeRecord()
    artAfterRecord()
    idMismatch()
    transferBeforeRecordDoesNotSatisfyMismatch()
    println("ArtworkKeeperTest OK")
}

private class Harness {
    val lines = mutableListOf<String>()
    val jobs = mutableListOf<Pair<Long, () -> Boolean>>()
    val keeper = ArtworkKeeper(
        log = { lines += it },
        afterMs = { delay, fire -> jobs += delay to fire },
    )

    fun fire() {
        val due = jobs.toList()
        jobs.clear()
        due.forEach { (delay, fire) ->
            check(delay == 2_000L, "mismatch delay $delay")
            fire()
        }
    }
}

private fun sameIdKeepsArt() {
    val h = Harness()
    val jpeg = byteArrayOf(1, 2, 3, 4)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    check(h.keeper.onTransfer(10, jpeg, titlePresent = true) == "accepted", "same-id accept")
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    check(h.keeper.artworkId == 10, "same id kept")
    check(sameBytes(h.keeper.artwork, jpeg), "same-id jpeg kept")
    check(h.lines.none { it.contains("art dropped") }, "same id must not drop art")
}

private fun absentIdKeepsArt() {
    val h = Harness()
    val jpeg = byteArrayOf(9, 9, 9)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    h.keeper.onTransfer(10, jpeg, titlePresent = true)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = null)
    check(h.keeper.artworkId == 10, "absent id keeps the previous id")
    check(sameBytes(h.keeper.artwork, jpeg), "absent id keeps the jpeg")
    check(h.lines.none { it.contains("art dropped") }, "absent id must not drop art")
}

private fun differentIdClears() {
    val h = Harness()
    val jpeg = byteArrayOf(4, 5, 6)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    h.keeper.onTransfer(10, jpeg, titlePresent = true)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 11)
    check(h.keeper.artworkId == 11, "different id is stored")
    check(h.keeper.artwork == null, "different id clears art")
    check(h.lines.any { it == "np: record artworkId 10 -> 11" }, "artworkId change logged: ${h.lines}")
    check(h.lines.any { it == "np: art dropped on title change (id 10 -> 11)" }, "drop logged: ${h.lines}")
}

private fun differentIdUsesCache() {
    val h = Harness()
    val first = byteArrayOf(1)
    val second = byteArrayOf(2, 2)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 7)
    h.keeper.onTransfer(7, first, titlePresent = true)
    check(h.keeper.onTransfer(8, second, titlePresent = true) == "rejected", "early transfer is cached, not shown")
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 8)
    check(h.keeper.artworkId == 8, "cache hit id")
    check(sameBytes(h.keeper.artwork, second), "cache hit jpeg")
    check(h.lines.none { it.contains("art dropped") }, "cache hit is not a drop")
}

private fun artBeforeRecord() {
    val h = Harness()
    val jpeg = byteArrayOf(7, 7, 7, 7)
    check(h.keeper.onTransfer(129, jpeg, titlePresent = false) == "accepted", "adopt before a record")
    check(h.lines.any { it.contains("accepted") && it.contains("artworkId=-1") && it.contains("title=false") }, h.lines.toString())
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 129)
    check(h.keeper.artworkId == 129, "record keeps the adopted id")
    check(sameBytes(h.keeper.artwork, jpeg), "record keeps the adopted jpeg")
}

private fun artAfterRecord() {
    val h = Harness()
    val jpeg = byteArrayOf(3, 3, 3)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 129)
    check(h.keeper.artwork == null, "record alone has no jpeg")
    check(h.keeper.onTransfer(129, jpeg, titlePresent = true) == "accepted", "transfer after record")
    check(sameBytes(h.keeper.artwork, jpeg), "jpeg applied")
    check(h.keeper.artworkId == 129, "id stays the record id")
    check(h.lines.any { it.contains("accepted") && it.contains("artworkId=129") && it.contains("title=true") }, h.lines.toString())
}

private fun idMismatch() {
    val h = Harness()
    val jpeg = byteArrayOf(8, 1, 8, 1)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    check(h.keeper.onTransfer(129, jpeg, titlePresent = true) == "rejected", "mismatched transfer is not applied yet")
    check(h.lines.any { it.contains("rejected (id does not match)") && it.contains("artworkId=10") }, h.lines.toString())
    h.fire()
    check(h.keeper.artworkId == 10, "mismatch keeps the record id")
    check(sameBytes(h.keeper.artwork, jpeg), "mismatch uses the transfer jpeg")
    check(
        h.lines.any { it == "np: art id mismatch — record 10, transfer 129; using the transfer" },
        "mismatch log: ${h.lines}",
    )
}

private fun transferBeforeRecordDoesNotSatisfyMismatch() {
    val h = Harness()
    val jpeg = byteArrayOf(1, 2)
    h.keeper.onTransfer(129, jpeg, titlePresent = false)
    h.keeper.onRecord(trackChanged = true, recordArtworkId = 10)
    h.fire()
    check(h.keeper.artworkId == 10, "record id wins")
    check(h.keeper.artwork == null, "a transfer from before the track is not the mismatch fallback")
}

private fun check(cond: Boolean, msg: String) {
    if (!cond) {
        System.err.println("FAIL $msg")
        exitProcess(1)
    }
}

private fun sameBytes(got: ByteArray?, want: ByteArray): Boolean =
    got != null && got.size == want.size && got.indices.all { got[it] == want[it] }
