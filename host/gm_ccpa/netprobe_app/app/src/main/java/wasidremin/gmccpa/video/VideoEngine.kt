package wasidremin.gmccpa.video

// Ported from enigy/carlink `app/src/main/kotlin/com/carlink/video/VideoEngine.kt` at a04f4c0 (Unlicense; NOTICE.md).

/**
 * What route A's play queue ([VideoPlayback]) needs of a player: the phone's commands, and the state its
 * `playbackInfo` answers report. [ExoVideoEngine] is the real one; tests use their own.
 */
interface VideoEngine {
    /** Load [url] to start at [startS] seconds, paused until [setRate] asks for more. */
    fun load(
        url: String,
        startS: Double,
    )

    /** Play at [rate] (1 = normal speed), or pause at 0. */
    fun setRate(rate: Double)

    /** Move to [toS] seconds. */
    fun seek(toS: Double)

    /** Unload the item and free the player. */
    fun stop()

    /** Where playback stands now. Any thread. */
    fun snapshot(): Snapshot

    /** Told on the main thread when the player fails, with the error's code name (never the address). */
    var onFailed: ((code: String) -> Unit)?

    companion object {
        /** [onFailed]'s code for a FairPlay-protected item, declined once its playlist says so. */
        const val FAIRPLAY = "FAIRPLAY"
    }

    /** Playback as `playbackInfo` reports it, in seconds. Nothing loaded is all zeros and not ready. */
    data class Snapshot(
        val ready: Boolean = false,
        val rate: Double = 0.0,
        val positionS: Double = 0.0,
        val durationS: Double = 0.0,
        val bufferedS: Double = 0.0,
        val buffering: Boolean = false,
        val failed: Boolean = false,
    )
}
