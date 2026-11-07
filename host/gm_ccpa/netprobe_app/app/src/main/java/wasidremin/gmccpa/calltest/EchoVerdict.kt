package wasidremin.gmccpa.calltest

// Ported from enigy/carlink `audio/` (Unlicense).

/**
 * The echo test's conclusions, kept free of Android types so they can be unit-tested.
 *
 * First run on the truck (2026-10-20) picked "Car call path, call mode" as having the least echo
 * because a noise peak just over the match threshold was read as an echo arriving 290 ms BEFORE the
 * sweep played. Two rules stop that: a match must come after the sound ([plausible]), and paths
 * with no echo at all are named together rather than ranked by the depth of their noise floor.
 */
internal object EchoVerdict {
    /** One path's result, as far as the verdict needs it. */
    data class Path(
        val label: String,
        val rawMic: Boolean,
        val found: Boolean,
        val lateDb: Double?,
    )

    /**
     * Whether a correlation peak counts as an echo: strong enough, and not earlier than the sound
     * it would be an echo of. [delayMs] is null when the platform gave no timestamps to check.
     */
    fun plausible(
        peakToNoiseDb: Double,
        delayMs: Double?,
    ): Boolean = peakToNoiseDb >= FOUND_PNR_DB && (delayMs == null || delayMs >= MIN_DELAY_MS)

    /** One line per path against the raw mic, then which path leaves the least echo. */
    fun lines(paths: List<Path>): List<String> {
        val raw = paths.firstOrNull { it.rawMic } ?: return emptyList()
        if (!raw.found) return listOf("Raw mic heard no echo either: turn the call volume up and run it again.")
        val rawLate = raw.lateDb ?: return emptyList()
        val scored = paths.filter { !it.rawMic }.mapNotNull { p -> p.lateDb?.let { p to rawLate - it } }
        if (scored.isEmpty()) return emptyList()
        val perPath =
            scored.map { (p, removed) ->
                if (p.found) {
                    "${p.label}: ${fmt(removed)} dB less echo than the raw mic (${grade(removed)})"
                } else {
                    "${p.label}: no echo detected, at least ${fmt(removed)} dB less than the raw mic"
                }
            }
        val clean = scored.filter { !it.first.found }.map { it.first.label }
        val least =
            if (clean.isNotEmpty()) {
                "Least echo: none detected on ${clean.joinToString(", ")}."
            } else {
                "Least echo: ${scored.maxBy { it.second }.first.label}."
            }
        return perPath + "$least Run the voice test too: a path can be quiet because it hears nothing."
    }

    private fun grade(removedDb: Double): String =
        when {
            removedDb >= WORKING_DB -> "working"
            removedDb >= PARTIAL_DB -> "partial"
            else -> "not working"
        }

    private fun fmt(v: Double): String = "%.0f".format(v)

    const val FOUND_PNR_DB = 15.0

    /** Less than this from playing to hearing is not an echo: the speaker-to-mic leg alone measured 190 ms. */
    const val MIN_DELAY_MS = 20.0
    private const val WORKING_DB = 20.0
    private const val PARTIAL_DB = 6.0
}
