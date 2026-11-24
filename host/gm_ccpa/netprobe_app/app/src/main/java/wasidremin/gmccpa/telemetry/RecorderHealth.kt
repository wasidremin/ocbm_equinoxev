package wasidremin.gmccpa.telemetry

/**
 * Whether the EV Insights recorder ([VehicleProbe]) is alive, decided from a few clocks it keeps.
 *
 * Why (2026-11-23, Play 268): the recorder closed one segment two seconds after it started and then
 * logged nothing for two days of drives in the same process, so the Drive card, which draws only
 * from the recorder's live state, stayed hidden, and no EV Insights segment reached the server. The
 * process lived and nothing crashed, so its thread was most likely blocked; the recorder's faults
 * went into the segment's `errors`, which never left the car. `docs/ops/04_OPEN_ITEMS.md` "Drive
 * card missing". This names the state in the log, and [ProbeService]'s watchdog acts on it.
 *
 * All times are `SystemClock.uptimeMillis()`, which stops while the head unit sleeps, as the
 * recorder's own handler does: a parked night must not read as a stuck thread.
 */
object RecorderHealth {
    enum class Verdict {
        /** Capturing, and its thread turned over recently. */
        OK,
        /** Its thread has not run its beat for [STUCK_MS]: blocked or looping. */
        STUCK,
        /** Automatic recording is on and the car service connected, but no capture for [OFF_MS]. */
        OFF,
        /** Not meant to be capturing (no car service yet, or a manual capture finished). */
        IDLE,
    }

    /** The recorder's thread notes a beat this often. */
    const val BEAT_MS = 10_000L
    /**
     * Six missed beats. Registering for every property at start took over 30 s on the loaded AAOS
     * emulator (2026-11-24, the watchdog's first stack: `CarPropertyManager.registerCallback` in a
     * binder call), a slow step that finishes; on the car the first segment closed 1.4 s after
     * recording was armed (2026-11-23).
     */
    const val STUCK_MS = 60_000L
    /** Capture off this long while it should be on is restarted. */
    const val OFF_MS = 60_000L
    /** One rebuild at most this often, so a thread that blocks at once is not rebuilt in a loop. */
    const val REBUILD_EVERY_MS = 10 * 60_000L

    /**
     * Pure: the verdict at [nowMs]. [lastBeatMs] is the recorder thread's last beat; [offSinceMs] is
     * when capture last went off (0 while capturing or never started).
     */
    fun verdict(nowMs: Long, auto: Boolean, connected: Boolean, capturing: Boolean, lastBeatMs: Long, offSinceMs: Long): Verdict = when {
        lastBeatMs > 0 && nowMs - lastBeatMs >= STUCK_MS -> Verdict.STUCK
        capturing -> Verdict.OK
        auto && connected && offSinceMs > 0 && nowMs - offSinceMs >= OFF_MS -> Verdict.OFF
        else -> Verdict.IDLE
    }

    /** Pure: whether a stuck recorder may be rebuilt now; [lastRebuildMs] is 0 before the first. */
    fun mayRebuild(nowMs: Long, lastRebuildMs: Long): Boolean = lastRebuildMs == 0L || nowMs - lastRebuildMs >= REBUILD_EVERY_MS

    /** Pure: the board's words. Coarse on purpose: the board logs every change. [carHeardAgoMs] is null before the car has sent anything. */
    fun boardText(v: Verdict, capturing: Boolean, carHeardAgoMs: Long?, driveOpen: Boolean): String = when (v) {
        Verdict.STUCK -> "thread stuck — the Drive card has no data"
        Verdict.OFF -> "not capturing although the car is connected — restarting it"
        Verdict.IDLE -> if (capturing) "capturing" else "not capturing"
        Verdict.OK -> buildString {
            append("capturing")
            append(when {
                carHeardAgoMs == null -> ", nothing from the car yet"
                carHeardAgoMs < QUIET_MS -> ", the car heard in the last 5 min"
                else -> ", the car quiet 5 min"
            })
            append(if (driveOpen) ", drive open" else ", no drive open")
        }
    }

    /** Parked, some properties go minutes between changes; five without any is worth a word. */
    const val QUIET_MS = 5 * 60_000L

    /**
     * Pure: where a thread is, as `Class.method:line` frames, the first [max] of them. Class and
     * method names only, no values, so it may go in an uploaded log.
     */
    fun frames(stack: Array<StackTraceElement>, max: Int = 14): List<String> =
        stack.take(max).map { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
}
