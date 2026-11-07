package wasidremin.gmccpa.av

/**
 * CarPlay app URLs for `requestUI` ([wasidremin.gmccpa.ocbm.Ocbm.CMD_REQUEST_UI_URL]). enigy/ocbm benched
 * them in their truck on 2026-10-26: these three bring their app forward on the main screen, and
 * `music:` does nothing, so Spotify and CarPlay Home still go through the vision launcher.
 */
object CarPlayUrls {
    const val MAPS = "maps:"

    /** The app that is playing now (Spotify, Music, …), on its Now Playing screen. */
    const val NOW_PLAYING = "nowplaying:"

    const val PHONE = "mobilephone:"

    /** The URL for a top-bar launch role, or null when the role has none. */
    fun forRole(role: String): String? = when (role) {
        "maps" -> MAPS
        wasidremin.gmccpa.match.ScreenModel.ROLE_NOW_PLAYING -> NOW_PLAYING
        "phone" -> PHONE
        else -> null
    }
}
