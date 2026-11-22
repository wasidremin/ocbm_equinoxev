package wasidremin.gmccpa.ocbm

/**
 * "Why didn't CarPlay connect?" (docs/ops/08_FUTURE_TASKS.md T27): from the connect steps [StartupClock] has
 * stamped, the step that is overdue and one sentence on what to do. The allowances are about three times the
 * step's usual time in the car (`[startup]` lines, 20 Oct to 22 Nov 2026: with the Wi-Fi link, subscribe to
 * discoverable 3.6 to 4.2 s, to Bluetooth connected 6.4 to 7.5 s, to the Wi-Fi handoff 2.8 to 4.1 s, to
 * auth-setup 2.0 to 2.9 s, then under 2 s to the picture), so a sentence means something is wrong, not slow.
 */
object ConnectStall {
    /** [after] is the last step reached, [waitedS] the seconds since it, [text] what the driver reads. */
    data class Stall(val after: String, val waitedS: Long, val text: String)

    private class Rule(val after: String, val allowS: Long, val text: String)

    /** In connect order. A step not listed here (bt-converged) does not count as progress. */
    private val RULES = listOf(
        // The app looks on the hotspot until 30 s after the adapter's USB device comes up (WifiLink.BOOT_FIND_MS),
        // which also restarts this clock (2026-11-22), so the adapter gets that long and the Allow tap more.
        Rule("attach", 45, "The adapter isn't answering. Check it's plugged in, or unplug it for ten seconds."),
        Rule("permission", 15, "The adapter isn't answering. Unplug it for ten seconds."),
        Rule("hello", 15, "The adapter answered but CarPlay didn't start. Restart the app."),
        Rule("subscribe", 30, "The adapter's Bluetooth didn't come up. Unplug the adapter for ten seconds."),
        Rule(
            "discoverable", 30,
            "Your iPhone hasn't answered the adapter. Check its Bluetooth is on, or move it to the top in " +
                "Settings ▸ CarPlay ▸ Phones.",
        ),
        Rule(
            "bt-connected", 15,
            "Your iPhone is connected by Bluetooth but hasn't joined the adapter's Wi-Fi. Check its Wi-Fi is on.",
        ),
        Rule(
            "wifi-handoff", 20,
            "Your iPhone joined the adapter's Wi-Fi but CarPlay hasn't started. If this keeps happening, forget " +
                "the adapter on the iPhone and pair again.",
        ),
        Rule("auth-setup-ok", 15, "CarPlay started but no picture has arrived. Restart the app."),
        Rule("keyed", 15, "CarPlay started but no picture has arrived. Restart the app."),
    )
    private val ORDER = RULES.map { it.after } + "first-video"

    /**
     * Pure: the overdue step, or null while every step is on time or the picture has arrived. [reached] maps a
     * step to its milliseconds since the attempt began; [nowMs] is the same clock now.
     */
    fun of(reached: Map<String, Long>, nowMs: Long): Stall? {
        if ("first-video" in reached) return null
        val last = ORDER.lastOrNull { it in reached } ?: return null
        val rule = RULES.firstOrNull { it.after == last } ?: return null
        val waited = (nowMs - (reached[last] ?: return null)) / 1000
        return if (waited >= rule.allowS) Stall(last, waited, rule.text) else null
    }
}
