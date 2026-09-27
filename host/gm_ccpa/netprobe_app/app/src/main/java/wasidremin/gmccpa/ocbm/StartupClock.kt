package wasidremin.gmccpa.ocbm

import android.os.SystemClock
import wasidremin.gmccpa.ProbeLog

/**
 * One clock per link attempt, zeroed at the first adapter attach (or at launch, when
 * nothing was attached). Each stage logs once. The summary line is printed once, at the
 * first video frame.
 *
 * Box-side stages are stamped when the batched box log is parsed, so they are late by
 * about half a second. That slop is on the line.
 */
object StartupClock {
    private val log = ProbeLog.sub("startup")
    private val lock = Any()
    private var origin = 0L
    private var open = false
    private var summarized = false
    private val at = LinkedHashMap<String, Long>()

    private val ORDER = listOf(
        "attach", "permission", "hello", "subscribe",
        "bt-converged", "discoverable", "bt-connected", "wifi-handoff",
        "auth-setup-ok", "keyed", "first-video",
    )
    private val BOX = setOf(
        "bt-converged", "discoverable", "bt-connected", "wifi-handoff", "auth-setup-ok",
    )

    /**
     * A new attach or launch. Drops a clock left open by an attempt that never reached a
     * frame, then [begin]s. [begin] itself stays idempotent for the rest of that attempt.
     */
    fun beginAttempt() {
        close()
        begin()
    }

    /** Start the clock. A second call during the same attempt does nothing. */
    fun begin() {
        val fresh = synchronized(lock) {
            if (open && origin != 0L) return
            origin = SystemClock.elapsedRealtime()
            open = true
            summarized = false
            at.clear()
            at["attach"] = 0L
            true
        }
        if (fresh) log.i("startup: attach +0")
    }

    /** Milliseconds since [begin], or 0 when the clock is not running. */
    fun elapsedMs(): Long = synchronized(lock) {
        if (origin == 0L) 0L else SystemClock.elapsedRealtime() - origin
    }

    /** Record [stage] once. [box] marks a time read out of a batched box log. */
    fun note(stage: String, box: Boolean = false) {
        val ms = synchronized(lock) {
            if (!open || origin == 0L) return
            if (at.containsKey(stage)) return
            val n = SystemClock.elapsedRealtime() - origin
            at[stage] = n
            n
        }
        val slop = if (box) " ±0.5s (box log batching)" else ""
        log.i("startup: $stage +${ms}ms$slop")
    }

    /** Box-log text. `[old]` backfill must not be passed in. */
    fun noteBoxLine(text: String) {
        when {
            "BT converged" in text -> note("bt-converged", box = true)
            "discoverable as" in text -> note("discoverable", box = true)
            "DEVICE_CONNECTED" in text -> note("bt-connected", box = true)
            "WIFI_HANDOFF" in text -> note("wifi-handoff", box = true)
            "auth-setup (MFi-SAP) OK" in text -> note("auth-setup-ok", box = true)
        }
    }

    /**
     * First rendered video frame. Prints the one summary line for this attempt and
     * closes the clock so a later session can [begin] again.
     */
    fun onFirstVideo() {
        note("first-video")
        val line = synchronized(lock) {
            if (!open || summarized) return
            summarized = true
            open = false
            ORDER.joinToString(" | ") { stage ->
                val v = at[stage]
                val body = if (v == null) "$stage —" else "$stage +${v}ms"
                if (stage in BOX && v != null) "$body ±0.5s (box log batching)" else body
            }
        }
        log.i("startup: $line")
    }

    /** The attempt ended without a frame. The next [begin] starts a new clock. */
    fun close() {
        synchronized(lock) { open = false }
    }
}
