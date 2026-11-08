package wasidremin.gmccpa.av

/**
 * CarPlay app URLs for `requestUI` ([wasidremin.gmccpa.ocbm.Ocbm.CMD_REQUEST_UI_URL]).
 *
 * Benched against the owner's iPhone (iOS 27.2) on 2026-11-07 with `ocbm-host avdec`, which sends the
 * URL and records the CarPlay picture that follows: `nowplaying:`, `maps:`, `music:` (Apple Music),
 * `spotify:` (Spotify, on its Now Playing screen), `podcasts:`, `messages:` and `mobilephone:` bring
 * their app forward. `audiobooks:`, `videoplayback:`, `calendar:`, `home:`, `carplay:` and
 * `springboard:` do nothing, so CarPlay Home still goes through the vision launcher. enigy/ocbm found
 * `music:` doing nothing on their phone; on this one it opened Music. A web search (2026-11-07) found
 * no published list.
 */
object CarPlayUrls {
    const val MAPS = "maps:"

    /** The app that is playing now (Spotify, Music, …), on its Now Playing screen. */
    const val NOW_PLAYING = "nowplaying:"

    const val PHONE = "mobilephone:"
    const val SPOTIFY = "spotify:"
    const val MUSIC = "music:"
    const val PODCASTS = "podcasts:"
    const val MESSAGES = "messages:"

    /** The URL for a top-bar launch role, or null when the role has none. */
    fun forRole(role: String): String? = when (role) {
        "maps" -> MAPS
        wasidremin.gmccpa.match.ScreenModel.ROLE_NOW_PLAYING -> NOW_PLAYING
        "phone" -> PHONE
        "spotify" -> SPOTIFY
        "music" -> MUSIC
        else -> null
    }
}
