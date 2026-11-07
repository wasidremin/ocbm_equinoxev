package wasidremin.gmccpa.ocbm

/**
 * Reads the box's own answer to "is a projection session live" out of MGMT_INFO.
 *
 * `transport` is `/tmp/carplay_transport`, which the box's wireless arm writes as `wireless` while it
 * owns a session and clears when the session ends ("wireless session ended (transport flag
 * cleared)"). `CT_PROJ_MODE` mirrors the same ownership, so this is the way to ask for the value when
 * its push did not arrive.
 */
object BoxSessionCheck {
    private val TRANSPORT = Regex("\"transport\"\\s*:\\s*\"([^\"]*)\"")

    /** True when a transport owns the box, false when none does, null when the field is missing. */
    fun transportLive(json: String): Boolean? =
        TRANSPORT.find(json)?.groupValues?.get(1)?.let { it.isNotBlank() }
}
