package wasidremin.gmccpa.logging

/**
 * Long log text split into lines a reader can scan. The upload's redactor ([LogExport]) works line by
 * line and masks only a whole address, so a line break inside one lets the rest of it out: the adapter's
 * `MGMT_INFO`, cut every 110 characters, carried part of a Bluetooth address unmasked in 30 of its 64
 * `"devices"` lines from 2026-11-21 to 11-26. Breaks fall after a comma or a space instead.
 */
object LogWrap {
    /**
     * Pure: [text] in lines of at most [width] characters, each ending just after a `,` or a space where
     * one falls inside the line. A run with neither longer than [width] is kept whole on a line of its
     * own, longer than [width], rather than cut.
     */
    fun lines(text: String, width: Int = 110): List<String> {
        require(width > 0)
        val out = ArrayList<String>()
        var start = 0
        while (start < text.length) {
            if (text.length - start <= width) { out += text.substring(start); break }
            var cut = -1
            for (i in start + width - 1 downTo start) if (text[i] == ',' || text[i] == ' ') { cut = i + 1; break }
            if (cut == -1) {
                cut = start + width
                while (cut < text.length && text[cut - 1] != ',' && text[cut - 1] != ' ') cut++
            }
            out += text.substring(start, cut)
            start = cut
        }
        return out
    }
}
