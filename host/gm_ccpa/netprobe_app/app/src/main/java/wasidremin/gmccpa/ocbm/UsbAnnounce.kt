package wasidremin.gmccpa.ocbm

/**
 * The Advanced-page view of `/script/ocbm.conf`.
 *
 * The boot script defaults to announce on and an 8 MiB FAT image when the file or the key is
 * absent. A missing file must therefore show Image, never Off. Dwell, retry delay, and
 * `usb_announce_retries` stay out of the app; [rewrite] copies every other line through unchanged.
 */
object UsbAnnounce {
    const val PATH = "/script/ocbm.conf"

    enum class Mode { OFF, IMAGE, NONE }

    fun parse(text: String?): Mode {
        if (text == null) return Mode.IMAGE
        var announce: String? = null
        var medium: String? = null
        for (line in text.split('\n')) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = t.indexOf('=')
            if (eq <= 0) continue
            val key = t.substring(0, eq).trim()
            val value = t.substring(eq + 1).trim()
            when (key) {
                "usb_announce" -> announce = value
                "usb_announce_medium" -> medium = value
            }
        }
        if (announce == "0") return Mode.OFF
        if (medium == "none") return Mode.NONE
        return Mode.IMAGE
    }

    /** Replace only the two announce keys. Every other line is copied as read. */
    fun rewrite(existing: String?, mode: Mode): String {
        val lines = if (existing == null) mutableListOf()
        else existing.split('\n').toMutableList()
        if (lines.isNotEmpty() && lines.last().isEmpty()) lines.removeAt(lines.lastIndex)
        fun setKey(key: String, value: String) {
            var hit = false
            for (i in lines.indices) {
                val t = lines[i].trim()
                if (t.isEmpty() || t.startsWith("#") || !t.contains("=")) continue
                if (t.substringBefore("=").trim() == key) {
                    lines[i] = "$key=$value"
                    hit = true
                }
            }
            if (!hit) lines.add("$key=$value")
        }
        when (mode) {
            Mode.OFF -> setKey("usb_announce", "0")
            Mode.IMAGE -> {
                setKey("usb_announce", "1")
                setKey("usb_announce_medium", "image")
            }
            Mode.NONE -> {
                setKey("usb_announce", "1")
                setKey("usb_announce_medium", "none")
            }
        }
        return lines.joinToString("\n") + "\n"
    }

    fun index(mode: Mode): Int = when (mode) {
        Mode.OFF -> 0
        Mode.IMAGE -> 1
        Mode.NONE -> 2
    }

    fun modeAt(index: Int): Mode = when (index) {
        0 -> Mode.OFF
        2 -> Mode.NONE
        else -> Mode.IMAGE
    }
}
