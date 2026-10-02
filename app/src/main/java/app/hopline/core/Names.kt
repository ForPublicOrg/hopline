package app.hopline.core

/**
 * Names arrive from other phones, so they are data, not trusted text. One cleaner for every place
 * a person's or a group's name enters this phone: no control characters, no bidi overrides that
 * make "‮ecila" render as "alice", no line breaks that turn a sender label into a wall, and a
 * hard length cap that never splits an emoji or a surrogate pair. ZWJ/ZWNJ stay — emoji sequences
 * and Indic/Persian scripts need them.
 */
object Names {
    const val MAX_PERSON = 25
    const val MAX_GROUP = 40

    private fun dropped(cp: Int): Boolean = when {
        cp < 0x20 || cp == 0x7F -> true                 // C0 controls, DEL (newlines included)
        cp in 0x80..0x9F -> true                         // C1 controls
        cp == 0x061C || cp == 0x200E || cp == 0x200F -> true
        cp in 0x202A..0x202E -> true                     // embeddings / overrides
        cp in 0x2066..0x2069 -> true                     // isolates
        cp == 0x2028 || cp == 0x2029 -> true             // line / paragraph separators
        cp == 0xFEFF -> true                             // BOM / zero-width no-break space
        cp in 0xFFF9..0xFFFB -> true                     // interlinear annotation controls
        else -> false
    }

    fun clean(raw: String?, max: Int = MAX_PERSON): String {
        if (raw.isNullOrEmpty()) return ""
        val sb = StringBuilder(minOf(raw.length, max * 2))
        var i = 0
        var lastSpace = true   // swallow leading whitespace
        var count = 0
        while (i < raw.length && count < max) {
            val cp = raw.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
                if (!lastSpace) { sb.append(' '); lastSpace = true; count++ }
                continue
            }
            if (dropped(cp)) continue
            sb.appendCodePoint(cp)
            lastSpace = false
            count++
        }
        return sb.toString().trimEnd()
    }
}
