package app.hopline.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Paying a shop when there is no internet. Every UPI app needs data; the one way to pay any UPI
 * ID over plain phone signal is NPCI's *99# menu, and Android gives an app no way to answer a
 * USSD menu (an accessibility service could, and that alone gets a sideloaded app blocked by Play
 * Protect). So Hopline only gets the payment ready: it reads the shop's QR code, checks the amount
 * against what *99# will send, copies the UPI ID and opens the Phone app on one of two fixed
 * codes. The person presses call, pastes the UPI ID, sees the payee's name as their bank has it,
 * and types the amount and then their UPI PIN — into the carrier's box, never into Hopline.
 *
 * A QR code is a sticker anyone can print and paste over another, so nothing in it is trusted:
 * the name it gives is only a claim (the name the bank shows in the *99# box is the one to
 * believe), and nothing read from a code or typed by a person ever reaches a dial string — the
 * app dials [CODE_MENU] or [CODE_SEND] and nothing else ([dialable]).
 *
 * Two kinds of code are read: NPCI's upi://pay link, which most shops print, and the Bharat QR
 * (EMVCo) code some banks print, which carries a UPI ID inside it. No Android in here, so the JVM
 * tests can pin it down; and nothing in here throws, whatever the camera saw.
 */
object Upi {
    const val MIN_PAISE = 100L          // ₹1
    const val MAX_PAISE = 500_000L      // ₹5,000: the most *99# sends at a time
    const val MAX_ID = 160              // the longest reply the *99# box accepts
    const val MAX_RECENT = 6
    const val CODE_MENU = "*99#"        // the full menu, and first-time setup
    const val CODE_SEND = "*99*1*3#"    // straight to Send Money > UPI ID

    private const val MAX_TEXT = 4096   // a payment code is a few hundred characters; this is something else
    private const val MAX_NAME = 60

    /**
     * May [code] be put in the Phone app? Only the two codes above, character for character.
     * Whatever a QR code or a person wrote never becomes something dialled: a code that slipped
     * in "*401*<number>#" would forward this phone's calls to a stranger.
     */
    fun dialable(code: String): Boolean = code == CODE_MENU || code == CODE_SEND

    /**
     * Whom a code says to pay. [id] is the UPI ID exactly as written (its case kept). [name] is the
     * name the code CLAIMS — unchecked, maybe empty, and never to be believed over the one the bank
     * shows in the *99# box. [paise] is the amount the code carries (0: none, the person types it);
     * [floor] the least it accepts (0: no least). [fixed]: the code sets the amount and it may not
     * be changed. [oneBill]: the code was made for one bill (a "dynamic" code), which *99# often
     * cannot pay, or pays without the shop being able to match the money to the bill.
     */
    data class Payee(val id: String, val name: String, val paise: Long, val floor: Long, val fixed: Boolean, val oneBill: Boolean)

    /** Why a scanned code can't be paid through *99#. */
    enum class Flaw {
        /** Not a payment code at all: a web link, a Hopline invite, a bus ticket. */
        NOT_UPI,
        /** A UPI code, but not one that pays someone: setting up autopay (a mandate), or a request for money (collect). */
        NOT_PAY,
        /** A payment code with no UPI ID in it to pay: a card-only Bharat QR, or an "ID" that isn't one. */
        NO_ID,
        /** It asks for another currency; *99# pays in rupees only. */
        FOREIGN,
        /** A kind of payment *99# can't make: cash from an ATM, a payment abroad, autopay, a wallet top-up. */
        SPECIAL,
        /** Recognised, but damaged or tampered with: a checksum that fails, a field cut short, two payees or two amounts in one code. */
        BROKEN,
    }

    /** What [read] found: a [payee] to pay, or the [flaw] that stops it. Exactly one of the two is set. */
    class Read(val payee: Payee?, val flaw: Flaw?)

    private fun no(flaw: Flaw) = Read(null, flaw)

    /**
     * What a scanned or pasted [text] asks to be paid: a [Payee], or the [Flaw] that stops it.
     * Anything that is neither a upi: link nor a Bharat QR (text starting 000201) is
     * [Flaw.NOT_UPI]; one of those that can't be read is [Flaw.BROKEN]. Never throws.
     */
    fun read(text: String): Read = try {
        // A byte-order mark in front is invisible, and not part of the code.
        val t = text.trim().removePrefix("\uFEFF").trim()
        when {
            t.length > MAX_TEXT -> no(Flaw.NOT_UPI)
            t.take(4).lowercase() == "upi:" -> try { link(t) } catch (e: Exception) { no(Flaw.BROKEN) }
            t.startsWith("000201") -> try { bharat(t) } catch (e: Exception) { no(Flaw.BROKEN) }
            else -> no(Flaw.NOT_UPI)
        }
    } catch (e: Exception) { no(Flaw.NOT_UPI) }

    /**
     * Can [text] be paid through *99# at all: a payee, and an amount it fixes (or a least amount it
     * sets) inside what *99# sends? Asked before a code scanned somewhere else is offered for paying,
     * so the offer is never made for a code the Pay screen could only turn away.
     */
    fun payable(text: String): Boolean {
        val p = read(text).payee ?: return false
        val least = if (p.fixed) p.paise else p.floor
        return least <= MAX_PAISE && !(p.fixed && p.paise < MIN_PAISE)
    }

    // ------------------------------------------------------------------ upi://pay links

    /**
     * NPCI's link, as most shops print it: upi://pay?pa=shop@okaxis&pn=Shop&am=250. Read by hand
     * rather than with java.net.URI: real codes carry what it rejects (spaces, a bare %, a raw + in
     * a timestamp) and still pay in every UPI app.
     */
    private fun link(t: String): Read {
        // "upi:" without "//" is no link; after it the host runs to the first ? or /.
        if (!t.startsWith("//", 4)) return no(Flaw.NOT_PAY)
        val hostEnd = t.indexOfAny(charArrayOf('?', '/'), 6).let { if (it < 0) t.length else it }
        if (t.substring(6, hostEnd).lowercase() != "pay") return no(Flaw.NOT_PAY)   // mandate (autopay), collect, …
        val q = t.indexOf('?')
        val raw = HashMap<String, String>()
        if (q >= 0) for (piece in t.substring(q + 1).split('&')) {
            if (piece.isEmpty()) continue
            val eq = piece.indexOf('=')          // the FIRST =: a value (a signature, say) may hold more
            val key = (if (eq < 0) piece else piece.substring(0, eq)).lowercase()
            val value = if (eq < 0) "" else piece.substring(eq + 1)
            val first = raw[key]
            if (first == null) raw[key] = value
            // Two payees or two amounts in one code is a trick, not a typo: which would a UPI app pay?
            else if ((key == "pa" || key == "am") && decode(first, false) != decode(value, false)) return no(Flaw.BROKEN)
        }
        fun value(key: String): String? = raw[key]?.let { decode(it, plusIsSpace = key == "pn") }

        val cu = value("cu")?.trim().orEmpty()
        if (cu.isNotEmpty() && cu.lowercase() != "inr") return no(Flaw.FOREIGN)
        // Purposes 00 to 10 are everyday payments (a shop, travel, a hospital, a school fee, a gift…);
        // the others are kinds *99# can't make — cash from an ATM, abroad, autopay, a wallet — or
        // ones Hopline doesn't know, which is no better.
        val purpose = value("purpose")?.trim().orEmpty()
        if (purpose.isNotEmpty() && !(PURPOSE.matches(purpose) && purpose.toInt() <= 10)) return no(Flaw.SPECIAL)
        val payTo = id(value("pa").orEmpty()) ?: return no(Flaw.NO_ID)
        val amount = paise(value("am").orEmpty()) ?: 0L
        val least = paise(value("mam").orEmpty()) ?: 0L        // mam=null, mam= and mam=0 all mean no least amount
        // tr is the shop's reference for one bill. Everything else (mode, sign, orgid, mc, QRexpire, …)
        // decides nothing: a signature can only be checked with keys Hopline doesn't have.
        val oneBill = amount > 0 && !value("tr").isNullOrBlank()
        return Read(Payee(payTo, claimed(value("pn")), amount, least, fixed = amount > 0 && least == 0L, oneBill = oneBill), null)
    }

    private val PURPOSE = Regex("[0-9]{1,2}")

    /**
     * The name a code claims, fit to show — or "" when it isn't a name at all, and the UPI ID that is
     * really paid stands in for it. Cleaned like every name from outside ([Names.clean]); then a
     * "name" with an @ in it is dropped, since on screen it passes for a UPI ID — a sticker can say
     * pn=sharmastores@okaxis over a code that pays someone else. So is one with nothing visible in it
     * (zero-width spaces, a Hangul filler, a blank braille cell), which would leave the screen
     * naming nobody.
     */
    private fun claimed(raw: String?): String {
        val name = Names.clean(raw, MAX_NAME)
        if (name.any { it == '@' || it == '＠' || it == '﹫' }) return ""
        return if (name.codePoints().anyMatch { visible(it) }) name else ""
    }

    /** Letters, digits, punctuation and symbols — but not the few of them that draw nothing. */
    private fun visible(cp: Int): Boolean =
        cp != 0x115F && cp != 0x1160 && cp != 0x3164 && cp != 0xFFA0 && cp != 0x2800 && Character.getType(cp).toByte() in SHOWN

    private val SHOWN = setOf(Character.UPPERCASE_LETTER, Character.LOWERCASE_LETTER, Character.TITLECASE_LETTER,
        Character.MODIFIER_LETTER, Character.OTHER_LETTER, Character.DECIMAL_DIGIT_NUMBER, Character.LETTER_NUMBER,
        Character.OTHER_NUMBER, Character.CONNECTOR_PUNCTUATION, Character.DASH_PUNCTUATION, Character.START_PUNCTUATION,
        Character.END_PUNCTUATION, Character.INITIAL_QUOTE_PUNCTUATION, Character.FINAL_QUOTE_PUNCTUATION,
        Character.OTHER_PUNCTUATION, Character.MATH_SYMBOL, Character.CURRENCY_SYMBOL, Character.MODIFIER_SYMBOL,
        Character.OTHER_SYMBOL)

    /**
     * A link's value, decoded the way UPI apps decode it: %XX is a byte, and bytes in a row are
     * UTF-8 (anything malformed becomes U+FFFD); a % not followed by two hex digits is a space — an old
     * form NPCI tells apps to accept; and + is a space only where [plusIsSpace] (the name). Not
     * java.net.URLDecoder: it throws on a bare %, and makes every + a space — in a timestamp like
     * 17:48:19+05:30, or a signature, that is a different value.
     */
    internal fun decode(raw: String, plusIsSpace: Boolean): String {
        val out = StringBuilder(raw.length)
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            val hi = if (c == '%' && i + 2 < raw.length) hex(raw[i + 1]) else -1
            val lo = if (hi >= 0) hex(raw[i + 2]) else -1
            if (lo >= 0) { bytes.write(hi * 16 + lo); i += 3; continue }
            if (bytes.size() > 0) { out.append(String(bytes.toByteArray(), Charsets.UTF_8)); bytes.reset() }
            out.append(if (c == '%' || c == '+' && plusIsSpace) ' ' else c)
            i++
        }
        if (bytes.size() > 0) out.append(String(bytes.toByteArray(), Charsets.UTF_8))
        return out.toString()
    }

    // Plain ASCII hex only: Character.digit would also take full-width and other scripts' digits.
    private fun hex(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ------------------------------------------------------------------ Bharat QR

    /**
     * The EMVCo merchant code ("Bharat QR") some banks print: a two-digit ID, a two-digit length,
     * the value, over and over, sealed with a CRC in field 63. UPI rides inside it as a template
     * (one of fields 26 to 51) whose sub-field 00 is NPCI's A000000524 and sub-field 01 the UPI ID;
     * a code with no such template is for cards only. The CRC is checked: a code that doesn't add
     * up was damaged, or edited by someone who didn't know to redo it.
     */
    private fun bharat(t: String): Read {
        val all = fields(t) ?: return no(Flaw.BROKEN)
        val (lastId, seal) = all.last()
        // The seal covers everything before its own four characters, "6304" included.
        if (lastId != "63" || !HEX4.matches(seal) || crc(t.substring(0, t.length - 4)) != seal.uppercase()) return no(Flaw.BROKEN)
        val top = firstWins(all)
        if (top["53"].let { it != null && it != "356" }) return no(Flaw.FOREIGN)    // 356: rupees
        var payTo: String? = null
        var leastText: String? = null
        var reference = false
        for (n in 26..51) {
            val sub = firstWins(fields(top[n.toString()] ?: continue) ?: continue)
            if (sub["00"]?.lowercase() != "a000000524") continue                   // a card network's, not UPI's
            val one = sub["01"].orEmpty()
            val upi = id(one)
            // In a UPI template that isn't the payee, 01 is the shop's reference for one bill ("***"
            // asks the payer to type one, so it is none).
            if (upi == null) { if (one.isNotBlank() && one.trim() != "***") reference = true }
            else if (payTo == null) { payTo = upi; leastText = sub["02"] }
        }
        if (payTo == null) return no(Flaw.NO_ID)
        val amount = paise(top["54"].orEmpty()) ?: 0L
        val least = paise(leastText.orEmpty()) ?: 0L
        // Field 01 is "11" on a code printed once for every customer and "12" on one made for a
        // single payment; a bill's reference in a UPI template says the same.
        val oneBill = amount > 0 && (top["01"] == "12" || reference)
        return Read(Payee(payTo, claimed(top["59"]), amount, least, fixed = amount > 0 && least == 0L, oneBill = oneBill), null)
    }

    private val HEX4 = Regex("[0-9A-Fa-f]{4}")

    /**
     * The fields of [s] in order, or null unless they run exactly to its end. A length counts
     * characters, not bytes: a shop's name in Chinese or Hindi takes one per letter.
     */
    private fun fields(s: String): List<Pair<String, String>>? {
        val out = ArrayList<Pair<String, String>>()
        var i = 0
        while (i < s.length) {
            if (i + 4 > s.length || (i until i + 4).any { s[it] !in '0'..'9' }) return null
            var left = s.substring(i + 2, i + 4).toInt()
            var end = i + 4
            while (left > 0) {
                if (end >= s.length) return null
                end += Character.charCount(s.codePointAt(end))
                left--
            }
            out.add(s.substring(i, i + 2) to s.substring(i + 4, end))
            i = end
        }
        return out
    }

    /** By ID; when an ID comes twice, the first one counts. */
    private fun firstWins(fields: List<Pair<String, String>>): Map<String, String> =
        HashMap<String, String>().also { m -> for ((k, v) in fields) if (k !in m) m[k] = v }

    /** CRC-16/CCITT-FALSE (polynomial 0x1021, start 0xFFFF, nothing reflected) of [s]'s UTF-8 bytes, as four hex digits. */
    private fun crc(s: String): String {
        var c = 0xFFFF
        for (b in s.toByteArray(Charsets.UTF_8)) {
            c = c xor ((b.toInt() and 0xFF) shl 8)
            repeat(8) { c = if ((c and 0x8000) != 0) ((c shl 1) xor 0x1021) and 0xFFFF else (c shl 1) and 0xFFFF }
        }
        return c.toString(16).uppercase().padStart(4, '0')
    }

    // ------------------------------------------------------------------ UPI IDs and amounts

    private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]*@[A-Za-z][A-Za-z0-9]+")

    /**
     * A UPI ID as typed, pasted or read from a code — trimmed, its case kept — or null when it isn't
     * one. NPCI publishes no rule for what an ID may look like, so this is Hopline's own safety
     * filter: wide enough for the IDs seen in the wild (9876543210@ybl, shop.name-1@okaxis,
     * MAB.037135005900242@AXISBANK), narrow enough that nothing else gets through — no spaces, no
     * line breaks, no * or # a USSD menu would act on, and no longer than the *99# box takes.
     */
    fun id(typed: String): String? = typed.trim().takeIf { it.isNotEmpty() && it.length <= MAX_ID && ID.matches(it) }

    private val PHONE = Regex("""(\+91|91|0)?[6-9][0-9]{9}""")

    /** Is [typed] an Indian mobile number? Only so the screen can say something kinder than "not a UPI ID". */
    fun isPhone(typed: String): Boolean = PHONE.matches(typed.trim().filter { it != ' ' && it != '-' })

    // [0-9], not \d: on Android \d also takes digits of other scripts, and *99# takes only these.
    private val AMOUNT = Regex("""([0-9]{1,9})(?:\.([0-9]+))?""")

    /**
     * Rupees, with paise if any, as paise: "250", "250.5", "250.50". Digits after the second
     * decimal place must all be 0 — "250.555" is not an amount anyone can pay. No sign, no commas,
     * no ₹ and no dot at either end. Null when [text] is not an amount.
     */
    fun paise(text: String): Long? {
        val m = AMOUNT.matchEntire(text.trim()) ?: return null
        val decimals = m.groupValues[2]
        if (decimals.drop(2).any { it != '0' }) return null
        return m.groupValues[1].toLong() * 100 + decimals.take(2).padEnd(2, '0').toLong()
    }

    /** Seven figures before the dot (more than *99# ever sends, so a too-big amount gets said, not swallowed), two after. */
    private val AMOUNT_TYPING = Regex("[0-9]{0,7}(\\.[0-9]{0,2})?")

    /**
     * The amount field after an edit — [before] with [start] until [end] replaced by [typed] — or
     * null when the edit is refused and the field stays exactly as it was. Deleting is always
     * allowed. Otherwise only an amount can be typed: digits, one dot and two places after it. A
     * digit of another script (a Marathi keypad's ५) is written 0 to 9, which is all *99# takes. A
     * comma is what it is in India, the mark that groups thousands — "2,500" is two thousand five
     * hundred, never 2.50 — so it is left out, as are spaces and a ₹ in pasted text; only on a phone
     * whose language writes a comma for the decimal point ([commaIsDecimal]) is it the dot. An edit
     * that leaves nothing of what was typed is refused whole, so a comma typed over a selection
     * never deletes it.
     */
    fun amountEdit(before: String, start: Int, end: Int, typed: CharSequence, commaIsDecimal: Boolean = false): String? {
        if (start < 0 || end < start || end > before.length) return null
        if (typed.isEmpty()) return before.substring(0, start) + before.substring(end)
        val clean = StringBuilder(typed.length)
        for (c in typed) {
            val d = Character.digit(c, 10)
            when {
                d >= 0 -> clean.append('0' + d)
                c == '.' || c == ',' && commaIsDecimal -> clean.append('.')
                c == ',' || c == '₹' || Character.isWhitespace(c) || Character.isSpaceChar(c) -> {}
                else -> return null   // a letter, a sign, anything else: not part of an amount
            }
        }
        if (clean.isEmpty()) return null
        val after = before.substring(0, start) + clean + before.substring(end)
        return if (AMOUNT_TYPING.matches(after)) after else null
    }

    /**
     * What the person types into *99#: "250" for whole rupees, "250.50" otherwise. Plain 0 to 9
     * always: never String.format, which writes other digits on a phone set to some languages.
     */
    fun rupees(paise: Long): String {
        val p = paise.coerceAtLeast(0)
        return if (p % 100 == 0L) (p / 100).toString() else "${p / 100}." + (p % 100).toString().padStart(2, '0')
    }

    /** An amount on screen, grouped the Indian way: ₹250, ₹1,250.50, ₹1,25,000. */
    fun shown(paise: Long): String {
        val p = paise.coerceAtLeast(0)
        val whole = (p / 100).toString()
        // The last three digits, then twos: lakhs and crores, not millions.
        val grouped = if (whole.length <= 3) whole else whole.dropLast(3).reversed().chunked(2).joinToString(",").reversed() + "," + whole.takeLast(3)
        return "₹" + grouped + (if (p % 100 == 0L) "" else "." + (p % 100).toString().padStart(2, '0'))
    }

    /** Why an amount can't be sent yet. */
    enum class AmountFlaw {
        /** Nothing typed, or nothing that reads as an amount. */
        EMPTY,
        /** Under ₹1. */
        TOO_SMALL,
        /** Over ₹5,000, the most *99# sends at a time. */
        TOO_BIG,
        /** Under the least amount the code accepts. */
        UNDER_FLOOR,
    }

    /** What is wrong with [paise] (null: nothing typed that reads as an amount) for [payee], first things first; null when it can be sent. */
    fun amountFlaw(paise: Long?, payee: Payee): AmountFlaw? = when {
        paise == null -> AmountFlaw.EMPTY
        paise < MIN_PAISE -> AmountFlaw.TOO_SMALL
        paise > MAX_PAISE -> AmountFlaw.TOO_BIG
        payee.floor > 0 && paise < payee.floor -> AmountFlaw.UNDER_FLOOR
        else -> null
    }

    // ------------------------------------------------------------------ people paid before

    /** Someone paid from this phone before: their UPI ID, the name their code gave, the amount, and when ([at], ms). */
    data class Recent(val id: String, val name: String, val paise: Long, val at: Long)

    /**
     * The recent payees kept in [json] (what [json] wrote), newest first. Read tolerantly: nothing,
     * or junk, is no one; an entry whose ID isn't one is skipped without losing the rest; names are
     * cleaned again, since anyone's QR code wrote them.
     */
    fun recents(json: String?): List<Recent> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val a = JSONArray(json)
            val out = ArrayList<Recent>()
            for (i in 0 until a.length()) {
                val o = a.optJSONObject(i) ?: continue
                val who = id(text(o, "id")) ?: continue
                if (out.any { it.id.equals(who, ignoreCase = true) }) continue
                out.add(Recent(who, claimed(text(o, "name")), o.optLong("paise").coerceAtLeast(0), o.optLong("at").coerceAtLeast(0)))
                if (out.size == MAX_RECENT) break
            }
            out
        } catch (e: Exception) { emptyList() }
    }

    /** A JSON null is no text at all — not the word "null", which Android's parser would hand back. */
    private fun text(j: JSONObject, key: String): String = if (j.isNull(key)) "" else j.optString(key)

    /** [list] with [r] first, and once: SHOP@ybl and shop@ybl are one entry, the newest spelling kept. */
    fun remember(list: List<Recent>, r: Recent): List<Recent> =
        (listOf(r) + list.filterNot { it.id.equals(r.id, ignoreCase = true) }).take(MAX_RECENT)

    fun forget(list: List<Recent>, id: String): List<Recent> = list.filterNot { it.id.equals(id, ignoreCase = true) }

    /** [list] as the text [recents] reads back. */
    fun json(list: List<Recent>): String = JSONArray().also { a ->
        for (r in list.take(MAX_RECENT)) a.put(JSONObject().put("id", r.id).put("name", r.name).put("paise", r.paise).put("at", r.at))
    }.toString()

    // ------------------------------------------------------------------ which phones it works on

    // Reliance Jio's network codes (MCC 405): 840, and 854 to 874.
    private val JIO = setOf("405840") + (854..874).map { "405$it" }

    /**
     * Is this SIM on Jio, where *99# doesn't work at all? By the operator's name, or by its network
     * code ([mccMnc], as Android gives it: "405857"). Not knowing is not Jio — the person can still
     * try, and the carrier will say if it can't.
     */
    fun jio(operatorName: String?, mccMnc: String?): Boolean =
        operatorName?.lowercase()?.contains("jio") == true || mccMnc != null && mccMnc.trim() in JIO

    /**
     * Should Pay without internet be offered at all? *99# is Indian. The SIM's country decides
     * when the SIM says one ("in", any case); when it doesn't, it is offered unless the phone is
     * plainly on a network abroad.
     */
    fun offered(simCountry: String?, networkCountry: String?): Boolean {
        val sim = simCountry?.trim()?.lowercase().orEmpty()
        val net = networkCountry?.trim()?.lowercase().orEmpty()
        return if (sim.isNotEmpty()) sim == "in" else net.isEmpty() || net == "in"
    }
}
