package app.hopline.core

/**
 * Texts sent home through a friend's phone. One stray em dash or curly quote switches an SMS to
 * UCS-2 and halves what fits — the friend pays for two messages instead of one. So the text is
 * folded to the GSM 7-bit alphabet where that changes nothing a reader would notice, and the
 * segment count shown to the sender is the one the carrier will bill.
 */
object SmsText {
    const val MAX_SEGMENTS = 3

    private const val GSM_BASIC = "@£\$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà"
    private const val GSM_EXT = "^{}\\[~]|€\u000C"

    private val FOLD = mapOf(
        '‘' to "'", '’' to "'", '‚' to "'", '′' to "'", '“' to "\"", '”' to "\"", '„' to "\"", '″' to "\"",
        '—' to "-", '–' to "-", '‐' to "-", '−' to "-", '…' to "...", '•' to "*", '·' to ".",
        ' ' to " ", ' ' to " ", ' ' to " ", ' ' to " ", ' ' to " ", '\t' to " ",
        '×' to "x", '°' to " deg",
    )

    /** Fold look-alike punctuation to GSM characters; letters of other scripts are left alone. */
    fun toGsm(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(FOLD[c] ?: c.toString())
        return sb.toString()
    }

    fun isGsm(s: String): Boolean = s.all { GSM_BASIC.indexOf(it) >= 0 || GSM_EXT.indexOf(it) >= 0 }

    /** What the carrier counts: GSM characters (€, [, ] … take two) or, for other scripts, UTF-16 code units (UCS-2). */
    fun units(s: String): Int = if (isGsm(s)) s.sumOf { if (GSM_EXT.indexOf(it) >= 0) 2L else 1L }.toInt() else s.length

    /** How many SMS this text is billed as. */
    fun segments(s: String): Int {
        if (s.isEmpty()) return 1
        val units = units(s)
        return if (isGsm(s)) { if (units <= 160) 1 else (units + 152) / 153 }
        else { if (units <= 70) 1 else (units + 66) / 67 }
    }

    /** How many [units] fit in this text's [segments] — the "/160" of a counter. */
    fun capacity(s: String): Int {
        val segs = segments(s)
        return if (isGsm(s)) { if (segs == 1) 160 else 153 * segs } else { if (segs == 1) 70 else 67 * segs }
    }

    /**
     * A phone number someone can actually text: + and 8–15 digits, or 10–15 digits. Short codes
     * (premium-rate services) are refused — nobody in the group can make a friend's phone text them.
     */
    fun normaliseNumber(raw: String): String? {
        val t = raw.trim()
        val plus = t.startsWith("+") || t.startsWith("00")
        val digits = t.filter { it.isDigit() }.let { if (t.startsWith("00")) it.drop(2) else it }
        if (t.any { !it.isDigit() && it !in " +-(). /" }) return null
        return when {
            plus && digits.length in 8..15 -> "+$digits"
            !plus && digits.length in 10..15 -> digits
            else -> null
        }
    }

    fun looksLikeEmail(s: String): Boolean =
        Regex("""^[A-Za-z0-9._%+\-]{1,64}@[A-Za-z0-9.\-]{1,253}\.[A-Za-z]{2,24}$""").matches(s.trim())
}
