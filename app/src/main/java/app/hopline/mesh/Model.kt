package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.Names
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class Identity(val id: String, var name: String)

class Group(code: String, var name: String, nameAt: Long = 0, nameV: Int = 0) {
    val code: String = app.hopline.core.Words.normalise(code)
    /**
     * How many renames [name] comes after: each rename is one more than the newest it saw, so a
     * rename made after another always wins — whatever anyone's clock says. 0 = never renamed
     * (a name typed at join, a QR's name, or a 2.1-era group).
     */
    var nameV: Int = nameV
    /** When [name] was set, on this phone's clock as best it can tell: only breaks a tie between
     *  two renames made without seeing each other. 0 = unknown. */
    var nameAt: Long = nameAt
    val key: ByteArray = Crypto.groupKey(this.code)
    val fingerprint: String = Crypto.fingerprint(key)
}

/** One signed, flood-routed unit. Everything that crosses a link (except link handshakes) is an Envelope. */
class Envelope(val json: JSONObject) {
    val id: String get() = json.getString("id")
    val kind: String get() = json.getString("k")
    val origin: String get() = json.getString("o")
    val originName: String get() = json.optString("on", "")
    val ts: Long get() = json.getLong("ts")
    val to: String? get() = json.optString("to", "").ifEmpty { null }
    var hops: Int
        get() = json.optInt("h", 0)
        set(v) { json.put("h", v) }
    val payload: JSONObject get() = json.getJSONObject("p")
    val sig: String get() = json.optString("s", "")

    fun signable(): String = "$id|$kind|$origin|$originName|$ts|${to ?: ""}|${Crypto.canonical(payload)}"
    fun sign(key: ByteArray) { json.put("s", Crypto.hmacHex(key, signable())) }
    fun verify(key: ByteArray): Boolean = Crypto.constantTimeEquals(sig, Crypto.hmacHex(key, signable()))
    fun bytes(): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    fun copy(): Envelope = Envelope(JSONObject(json.toString()))

    companion object {
        const val CHAT = "chat"      // group message
        const val DM = "dm"          // private message, carried by everyone, shown only to `to`
        const val RECEIPT = "rcpt"   // "my phone has message X"
        const val PRESENCE = "pres"  // "I'm alive, here's my name, do I have internet"
        const val ERRAND = "errand"  // "someone with internet, please do this"
        const val ERRAND_RESULT = "errres"
        /** "My phone is on your request" / "I can't, someone else take it". Live only, never carried:
         *  2.x phones relay unknown kinds without storing them. */
        const val ERRAND_ACK = "erak"
        const val FILE = "file"      // a photo/file message: caption + attachment meta (name, size, chunk count, thumb)
        const val CHUNK = "fchk"     // one piece of a file's data; id is deterministic: f.<fid>.<index>
        const val REACT = "reac"     // an emoji on message X; 2.0 clients relay but don't carry or show it

        /** Kinds that are stored and handed to phones that missed them (chunks are carried separately, on disk). */
        val CARRIED = setOf(CHAT, DM, RECEIPT, ERRAND, ERRAND_RESULT, FILE, REACT)
        // Ceiling on the LIVE flood only: a dense crowd has a tiny diameter (each phone holds
        // several links) and even a single-file line of thirty phones stays under this. Store-and-
        // forward backlog is deduped by id, not by hops, so gap-fill deliberately does NOT spend
        // this budget — a message carried across many hand-offs over 48 h must not die at the cap.
        const val MAX_HOPS = 32

        fun chunkId(fid: String, index: Int): String = "f.$fid.$index"
    }
}

/**
 * What one file message carries in its envelope: everything a phone needs to show a placeholder
 * (name, size, a tiny thumbnail) and to know when it has all the pieces.
 */
class Attachment(val json: JSONObject) {
    /** Empty when a crafted/buggy sender left it out — such a file is refused before it is shown. */
    val fid: String get() = json.optString("fid", "")
    val name: String get() = json.optString("name", "file")
    val mime: String get() = json.optString("mime", "application/octet-stream")
    val size: Long get() = json.optLong("size", 0)
    val chunks: Int get() = json.optInt("n", 0)
    val width: Int get() = json.optInt("w", 0)
    val height: Int get() = json.optInt("h", 0)
    val thumb: String get() = json.optString("tb", "")   // tiny base64 JPEG, shown while pieces arrive
    val dur: Int get() = json.optInt("dur", 0).coerceIn(0, 3600)   // seconds, for voice notes
    val isImage: Boolean get() = mime.startsWith("image/")
    /** A photo this app can draw inline: one we shrank ourselves (it carries its size), or a format
     *  Android's decoder reads. An SVG/TIFF/RAW picked as a file stays a file and opens elsewhere. */
    val isInlineImage: Boolean get() = isImage && ((width > 0 && height > 0) || mime.lowercase() in INLINE_MIMES)
    val isAudio: Boolean get() = mime.startsWith("audio/")

    companion object {
        private val INLINE_MIMES = setOf("image/jpeg", "image/jpg", "image/png", "image/webp", "image/gif", "image/bmp")
        /** File ids come from Crypto.randomId — anything else is a crafted envelope (and a file path). */
        private val FID = Regex("^[a-z0-9]{6,24}$")
        fun validFid(fid: String): Boolean = FID.matches(fid)

        fun make(fid: String, name: String, mime: String, size: Long, chunks: Int, w: Int, h: Int, thumb: String, dur: Int = 0): Attachment =
            Attachment(JSONObject().apply {
                put("fid", fid); put("name", name); put("mime", mime); put("size", size); put("n", chunks)
                if (w > 0) put("w", w); if (h > 0) put("h", h); if (thumb.isNotEmpty()) put("tb", thumb)
                if (dur > 0) put("dur", dur)
            })
    }
}

/**
 * What a reply points back at. The name and a snippet travel WITH the reply, so the quote block
 * renders even when the original hasn't hopped in yet (or already expired from the 48 h carry).
 */
class Quote(val id: String, val name: String, val text: String, val origin: String = "") {
    /** "o" (who wrote the original) is new in 2.2; older clients ignore it. */
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("n", name); put("t", text); if (origin.isNotEmpty()) put("o", origin) }
    companion object {
        const val MAX_SNIPPET = 120
        /** [name] is who the quoted person is NOW (they may have renamed since the message was sent). */
        fun of(m: Message, name: String = m.fromName): Quote {
            val snippet = when {
                m.loc != null -> "📍 " + m.loc.label.ifEmpty { "Location" }
                m.att?.isAudio == true -> "🎤 Voice note"
                m.att?.isImage == true -> "📷 " + m.text.ifEmpty { "Photo" }
                m.att != null -> "📎 " + m.att.name
                else -> m.text
            }
            return Quote(m.id, name, snippet.take(MAX_SNIPPET), m.from)
        }
        fun fromJson(j: JSONObject?): Quote? {
            if (j == null) return null
            val id = j.optString("id"); if (id.isEmpty() || id.length > 40) return null
            val o = j.optString("o", "").takeIf { it.length <= 40 } ?: ""
            return Quote(id, Names.clean(j.optString("n", "")), j.optString("t", "").take(MAX_SNIPPET), o)
        }
    }
}

/**
 * A shared place, riding inside a normal chat/DM payload. Coordinates are integer microdegrees:
 * signing canonicalises numbers, and integers serialise identically on every JVM — doubles don't.
 * Old clients ignore the "loc" field and show the message text, which carries a maps link.
 */
class Loc(val latE6: Long, val lngE6: Long, val acc: Int, val label: String) {
    val lat: Double get() = latE6 / 1e6
    val lng: Double get() = lngE6 / 1e6

    fun toJson(): JSONObject = JSONObject().apply {
        put("lat", latE6); put("lng", lngE6)
        if (acc > 0) put("acc", acc); if (label.isNotEmpty()) put("lbl", label)
    }

    /** "12.97160, 77.59460" — enough decimals to stand on the exact spot. */
    fun pretty(): String = String.format(Locale.US, "%.5f, %.5f", lat, lng)
    fun mapsUrl(): String = String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", lat, lng)
    /** What a 1.x client (and a copy) shows: a line that Google Maps opens. */
    fun fallbackText(): String = "📍 " + (if (label.isEmpty()) "" else "$label — ") + mapsUrl()

    companion object {
        const val MAX_LABEL = 60

        /** Build after validating — a crafted client must not put a pin on lat 999. */
        fun of(lat: Double, lng: Double, acc: Int = 0, label: String = ""): Loc? {
            if (lat.isNaN() || lng.isNaN() || lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
            return Loc(Math.round(lat * 1e6), Math.round(lng * 1e6), acc.coerceIn(0, 100_000), label.take(MAX_LABEL))
        }

        fun fromJson(j: JSONObject?): Loc? {
            if (j == null) return null
            val lat = j.optLong("lat", Long.MIN_VALUE); val lng = j.optLong("lng", Long.MIN_VALUE)
            if (lat !in -90_000_000L..90_000_000L || lng !in -180_000_000L..180_000_000L) return null
            return Loc(lat, lng, j.optInt("acc", 0).coerceIn(0, 100_000), j.optString("lbl", "").take(MAX_LABEL))
        }

        private val PAIR = Regex("""(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([NSns])?\s*[,;\s]\s*(-?\d{1,3}(?:\.\d+)?)\s*°?\s*([EWew])?""")

        /**
         * Read coordinates out of whatever people paste: "12.97, 77.59", a geo: URI, or a Google
         * Maps link (q= / query= / ll= / destination= / @lat,lng). Returns null if nothing sane.
         */
        fun parse(raw: String): Loc? {
            val text = raw.trim().replace("%2C", ",", ignoreCase = true)
            for (candidate in listOfNotNull(
                Regex("""geo:(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""").find(text)?.let { it.groupValues[1] + "," + it.groupValues[2] },
                Regex("""[?&](?:q|query|ll|destination)=(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""").find(text)?.let { it.groupValues[1] + "," + it.groupValues[2] },
                Regex("""@(-?\d{1,3}(?:\.\d+)?),(-?\d{1,3}(?:\.\d+)?)""").find(text)?.let { it.groupValues[1] + "," + it.groupValues[2] },
                text,
            )) {
                for (m in PAIR.findAll(candidate)) {
                    var lat = m.groupValues[1].toDoubleOrNull() ?: continue
                    var lng = m.groupValues[3].toDoubleOrNull() ?: continue
                    if (m.groupValues[2].equals("S", ignoreCase = true)) lat = -Math.abs(lat)
                    if (m.groupValues[4].equals("W", ignoreCase = true)) lng = -Math.abs(lng)
                    return of(lat, lng) ?: continue
                }
            }
            return null
        }

        // -------- pure geometry, so "1.2 km away · north-east" is unit-testable --------

        fun distanceMeters(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat); val dLng = Math.toRadians(bLng - aLng)
            val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.sin(dLng / 2) * Math.sin(dLng / 2)
            return 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
        }

        fun bearingDeg(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
            val dLng = Math.toRadians(bLng - aLng)
            val y = Math.sin(dLng) * Math.cos(Math.toRadians(bLat))
            val x = Math.cos(Math.toRadians(aLat)) * Math.sin(Math.toRadians(bLat)) -
                Math.sin(Math.toRadians(aLat)) * Math.cos(Math.toRadians(bLat)) * Math.cos(dLng)
            return (Math.toDegrees(Math.atan2(y, x)) + 360.0) % 360.0
        }

        private val COMPASS = arrayOf("north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west")
        fun compass(bearing: Double): String = COMPASS[(Math.round(bearing / 45.0).toInt()) % 8]

        fun prettyDistance(meters: Double): String = when {
            meters < 1000 -> "${Math.round(meters)} m"
            meters < 10_000 -> String.format(Locale.US, "%.1f km", meters / 1000)
            else -> "${Math.round(meters / 1000)} km"
        }
    }
}

class Message(
    val id: String,
    val kind: String,          // Envelope.CHAT / DM / FILE, or SYSTEM
    val from: String,
    val fromName: String,
    val to: String?,           // DM target, else null
    val text: String,          // for FILE messages this is the caption (may be empty)
    val ts: Long,
    val att: Attachment? = null,
    val loc: Loc? = null,
    val quote: Quote? = null,
    val mentions: List<String> = emptyList(),   // node ids named with @ in the text
) {
    var status: String = SENT            // only meaningful for my own messages
    /** When THIS phone got it (its own clock). Unread badges use this — sender clocks drift. */
    var arrivedAt: Long = ts
    /** Where it sits in the chat: the sender's time, but never more than a few minutes past the
     *  moment it reached us — a phone with a wild clock (or a crafted stamp) can't pin a message
     *  under everything said after it. */
    val sortKey: Long get() = minOf(ts, arrivedAt + 5 * 60_000L)
    val reached: MutableSet<String> = LinkedHashSet()   // node ids whose phone confirmed it
    var errandId: String? = null

    /** Who reacted with what. One reaction per person; changing it replaces, empty removes. */
    val reactions = LinkedHashMap<String, String>()     // origin node id -> emoji
    private val reactionTs = HashMap<String, Long>()    // last-write-wins across the flood

    /** True for a group-chat-visible message (not a DM). */
    val isGroup: Boolean get() = to == null
    /** A centred line about the chat itself ("Asha renamed the group"), not something someone said. */
    val isNotice: Boolean get() = kind == NOTICE
    /** Internet answers and notices: no reply, no reactions, no ticks, no unread badge for notices. */
    val isPersonal: Boolean get() = kind != SYSTEM && kind != NOTICE

    /**
     * Apply one person's reaction. Envelopes arrive in any order and are re-received from carry,
     * so only a strictly newer timestamp may replace what we have. Returns true if it changed.
     * A crafted client inventing endless origins must not balloon one message: new reactors stop
     * at MAX_REACTORS (updates and removals always land).
     */
    fun applyReaction(origin: String, emoji: String, ts: Long): Boolean {
        val old = reactionTs[origin]
        if (old != null && old >= ts) return false
        if (old == null && reactions.size >= MAX_REACTORS && emoji.isNotEmpty()) return false
        // A removal that outran its add still needs a tombstone so the add loses — but tombstones
        // from invented origins must not grow without bound either.
        if (old == null && emoji.isEmpty() && reactionTs.size >= MAX_REACTORS * 2) return false
        reactionTs[origin] = ts
        val had = reactions[origin]
        if (emoji.isEmpty()) reactions.remove(origin) else reactions[origin] = emoji
        return had != emoji.ifEmpty { null }
    }

    /** Each emoji with how many people chose it, most popular first (ties: whichever came first). */
    fun reactionCounts(): List<Pair<String, Int>> {
        if (reactions.isEmpty()) return emptyList()
        val counts = LinkedHashMap<String, Int>()
        for (e in reactions.values) counts[e] = (counts[e] ?: 0) + 1
        return counts.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }

    /** When [origin]'s current reaction (or its removal) was made, by their clock. */
    fun reactionTsOf(origin: String): Long? = reactionTs[origin]

    /** "👍 3  ❤️ 1" — a stable fingerprint of the reactions, for redraw decisions. */
    fun reactionSummary(): String =
        reactionCounts().joinToString("  ") { (e, n) -> if (n == 1) e else "$e $n" }

    /**
     * What the pill under a bubble shows, WhatsApp-style: up to three emoji, most popular first,
     * then the total when more than one person reacted — "👍❤️😂 7".
     */
    fun reactionPill(): String {
        val counts = reactionCounts()
        if (counts.isEmpty()) return ""
        val total = counts.sumOf { it.second }
        val faces = counts.take(3).joinToString("") { it.first }
        return if (total > 1) "$faces $total" else faces
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("from", from); put("fromName", fromName)
        if (to != null) put("to", to); put("text", text); put("ts", ts); put("status", status)
        put("at", arrivedAt)
        put("reached", JSONArray(reached.toList())); if (errandId != null) put("errandId", errandId)
        if (att != null) put("att", att.json)
        if (loc != null) put("loc", loc.toJson())
        if (quote != null) put("re", quote.toJson())
        if (mentions.isNotEmpty()) put("mn", JSONArray(mentions))
        if (reactions.isNotEmpty()) {
            val r = JSONObject()
            for ((who, e) in reactions) r.put(who, JSONObject().put("e", e).put("ts", reactionTs[who] ?: 0L))
            put("reac", r)
        }
    }

    companion object {
        const val SYSTEM = "system"
        const val NOTICE = "notice"        // local rendering of a chat-level event, e.g. a group rename
        const val QUEUED = "queued"        // nobody has taken it off my phone yet
        const val SENT = "sent"            // at least one other phone has it
        const val DELIVERED = "delivered"  // the recipient's phone has it (DMs)
        const val MAX_MENTIONS = 20
        /** UTF-16 units. 2.1 clipped at 8; 16 fits skin-toned ZWJ sequences and flag tags. */
        const val MAX_EMOJI = 16
        const val MAX_REACTORS = 500       // per message; far beyond any honest group

        fun mentionsFromJson(a: JSONArray?): List<String> {
            if (a == null) return emptyList()
            val out = ArrayList<String>(minOf(a.length(), MAX_MENTIONS))
            for (i in 0 until minOf(a.length(), MAX_MENTIONS)) {
                val id = a.optString(i, ""); if (id.isNotEmpty() && id.length <= 40) out.add(id)
            }
            return out
        }

        fun fromJson(j: JSONObject): Message = Message(
            j.getString("id"), j.getString("kind"), j.getString("from"), Names.clean(j.optString("fromName", "")),
            j.optString("to", "").ifEmpty { null }, j.getString("text"), j.getLong("ts"),
            j.optJSONObject("att")?.let { Attachment(it) },
            Loc.fromJson(j.optJSONObject("loc")),
            Quote.fromJson(j.optJSONObject("re")),
            mentionsFromJson(j.optJSONArray("mn")),
        ).also { m ->
            m.status = j.optString("status", SENT)
            m.arrivedAt = j.optLong("at", m.ts)
            val r = j.optJSONArray("reached"); if (r != null) for (i in 0 until r.length()) m.reached.add(r.getString(i))
            m.errandId = j.optString("errandId", "").ifEmpty { null }
            j.optJSONObject("reac")?.let { reac ->
                for (who in reac.keys()) {
                    val v = reac.optJSONObject(who) ?: continue
                    m.applyReaction(who, v.optString("e", "").take(MAX_EMOJI), v.optLong("ts", 0))
                }
            }
        }
    }
}

class Person(val id: String) {
    var name: String = ""
    /** The sender-clock time of the envelope that set [name]; older envelopes never overwrite it. */
    var nameAt: Long = 0
    var lastSeen: Long = 0     // last moment we know their phone was alive
    var hasInternet: Boolean = false
    var hops: Int = 99         // how many phones away, from their latest presence
    var direct: Boolean = false
    var battery: Int = -1
    /** Live location, while they share it. Rides presence, so it clears itself when they stop.
     *  Deliberately not persisted — a position from before a restart is a lie. */
    var loc: Loc? = null
    var locAt: Long = 0        // their clock, from the presence envelope that carried it (ordering only)
    /** How far their clock runs ahead of ours (negative: behind), measured from their latest
     *  presence — never carried, so it arrives within seconds. 0 until heard. Not persisted. */
    var skew: Long = 0
    var skewKnown: Boolean = false
    var locHeardAt: Long = 0   // OUR clock when that beacon arrived (freshness — their clock may be off)
    /** Highest protocol version this phone ever showed us. A 4+ phone never gets to fall back to
     *  the old, relayable link proof. Persisted. */
    var ver: Int = 0
    /** Shared-internet protocol and what their phone can do right now (bitmask of Errand.CAP_*).
     *  From presence only — a capability from before a restart is stale, so not persisted. */
    var ev: Int = 0
    var cap: Int = 0

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("nameAt", nameAt); put("lastSeen", lastSeen); put("hasInternet", hasInternet)
        put("hops", hops); put("battery", battery)
        if (ver > 0) put("ver", ver)
    }
    companion object {
        fun fromJson(j: JSONObject): Person = Person(j.getString("id")).also {
            it.name = Names.clean(j.optString("name", "")); it.nameAt = j.optLong("nameAt", 0); it.lastSeen = j.optLong("lastSeen", 0)
            it.hasInternet = j.optBoolean("hasInternet", false); it.hops = j.optInt("hops", 99)
            it.battery = j.optInt("battery", -1)
            it.ver = j.optInt("ver", 0)
        }
    }
}

/**
 * A request for the internet, run by whichever phone in the group has signal. Lives on the asker's
 * phone (with its answer) and, while open, on helpers' phones (so they can pick it up later).
 */
class Errand(
    val id: String,
    val type: String,          // read | find | wx | send
    val args: JSONObject,
    val from: String,
    val fromName: String,
    val ts: Long,
) {
    var helper: String? = null   // the phone that is on it (claimed), or was asked
    var helperName: String = ""
    var status: String = WAITING
    /** Plain-text answer: a 2.x helper's public result, or a short summary of a private answer. */
    var result: String? = null
    var title: String = ""
    /** The private answer's body, gzip+base64 as it arrived (decoded on demand — see [answer]). */
    var answerZ: String = ""
    var answeredAt: Long = 0     // OUR clock
    var answeredBy: String = ""  // the helper's name
    var cost: Int = 0            // bytes of the helper's data it used
    var why: String = ""         // short reason code when it failed
    var part: Int = 1
    var parts: Int = 1
    /** The asker understands private answers (2.2+). A 2.x asker gets the classic public answer. */
    var rv: Int = 1
    /** Local-clock deadline after which nobody should start it. */
    var exp: Long = 0
    /** Helpers that already had a go (claimed then went quiet, or said no). */
    val tried = LinkedHashSet<String>()
    /** Old-version helpers we asked directly after the asker agreed to a public answer. */
    val legacyAsked = LinkedHashSet<String>()
    var allowPublic = false
    /** Local time until which [helper]'s claim holds; 0 = no live claim. */
    var leaseUntil: Long = 0
    /** Envelope ts of the newest dispatch applied — an older one replayed by gap-fill loses. */
    var dispatchTs: Long = 0
    var lastDispatchAt: Long = 0 // OUR clock, last time the asker (re)sent it
    /** Ids of helpers the asker preferred, in order. */
    var pick: List<String> = emptyList()

    val isOpen: Boolean get() = status == WAITING || status == ASKED || status == CLAIMED

    /** The private answer, decoded. Null for a public/legacy answer or a corrupt body. */
    fun answer(): JSONObject? = if (answerZ.isEmpty()) null else Gz.unpackJson(answerZ)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("args", args); put("from", from); put("fromName", fromName); put("ts", ts)
        put("helper", helper ?: ""); put("helperName", helperName); put("status", status); put("result", result ?: "")
        if (title.isNotEmpty()) put("title", title)
        if (answerZ.isNotEmpty()) put("z", answerZ)
        if (answeredAt > 0) put("answeredAt", answeredAt)
        if (answeredBy.isNotEmpty()) put("answeredBy", answeredBy)
        if (cost > 0) put("cost", cost)
        if (why.isNotEmpty()) put("why", why)
        if (parts > 1) { put("part", part); put("parts", parts) }
        put("rv", rv); put("exp", exp)
        if (tried.isNotEmpty()) put("tried", JSONArray(tried.toList()))
        if (legacyAsked.isNotEmpty()) put("legacyAsked", JSONArray(legacyAsked.toList()))
        if (allowPublic) put("allowPublic", true)
        if (dispatchTs > 0) put("dispatchTs", dispatchTs)
        // Local-clock times: a restart must not make a live claim look lapsed (someone else would
        // start the same fetch, or text the same family), nor forget that the group has the request.
        if (leaseUntil > 0) put("lease", leaseUntil)
        if (lastDispatchAt > 0) put("lastDispatchAt", lastDispatchAt)
        if (pick.isNotEmpty()) put("pick", JSONArray(pick))
    }

    companion object {
        const val WAITING = "waiting"     // nobody with signal is around yet — it travels with the group
        const val ASKED = "asked"         // phones that can do it have it
        const val CLAIMED = "claimed"     // one of them said "on it"
        const val DONE = "done"
        const val FAILED = "failed"       // a helper tried and it can't be done (bad link, not a text page…)
        const val EXPIRED = "expired"     // nobody got signal in time
        const val CANCELLED = "cancelled"

        const val READ = "read"           // a web page, as text
        const val FIND = "find"           // look something up
        const val WX = "wx"               // weather for a place
        const val SEND = "send"           // an SMS or email, sent by a person with signal

        /** What a phone can do for the group right now, advertised in presence "cap". */
        const val CAP_READ = 1
        const val CAP_FIND = 2
        const val CAP_WX = 4
        const val CAP_SMS = 8             // has mobile service: can text by hand
        const val CAP_MAIL = 16           // has data: can open an email for someone

        /** Shared-internet protocol version announced in presence "ev". 2 = open requests, claims, private answers. */
        const val EV = 2

        fun isEmailTarget(args: JSONObject): Boolean = args.optString("to").contains('@')

        fun capFor(type: String, args: JSONObject): Int = when (type) {
            READ -> CAP_READ
            FIND -> CAP_FIND
            WX -> CAP_WX
            SEND -> if (isEmailTarget(args)) CAP_MAIL else CAP_SMS
            else -> 0
        }

        fun fromJson(j: JSONObject): Errand = Errand(
            j.getString("id"), j.getString("type"), j.optJSONObject("args") ?: JSONObject(), j.getString("from"),
            Names.clean(j.optString("fromName", "")), j.getLong("ts"),
        ).also {
            it.helper = j.optString("helper", "").ifEmpty { null }; it.helperName = Names.clean(j.optString("helperName", ""))
            it.status = j.optString("status", WAITING); it.result = j.optString("result", "").ifEmpty { null }
            it.title = j.optString("title", "")
            it.answerZ = j.optString("z", "")
            it.answeredAt = j.optLong("answeredAt", 0)
            it.answeredBy = j.optString("answeredBy", "")
            it.cost = j.optInt("cost", 0)
            it.why = j.optString("why", "")
            it.part = j.optInt("part", 1); it.parts = j.optInt("parts", 1)
            it.rv = j.optInt("rv", 1)
            it.exp = j.optLong("exp", 0)
            j.optJSONArray("tried")?.let { a -> for (i in 0 until a.length()) it.tried.add(a.optString(i)) }
            j.optJSONArray("legacyAsked")?.let { a -> for (i in 0 until a.length()) it.legacyAsked.add(a.optString(i)) }
            it.allowPublic = j.optBoolean("allowPublic", false)
            it.dispatchTs = j.optLong("dispatchTs", 0)
            it.leaseUntil = j.optLong("lease", 0)
            it.lastDispatchAt = j.optLong("lastDispatchAt", 0)
            j.optJSONArray("pick")?.let { a -> it.pick = (0 until minOf(a.length(), 3)).map { i -> a.optString(i) }.filter { id -> id.isNotEmpty() } }
        }
    }
}

/** gzip + URL-safe base64 for answers that ride one radio frame. Bounded on the way in (no zip bombs). */
object Gz {
    const val MAX_INFLATED = 512 * 1024

    fun pack(json: JSONObject): String {
        val bos = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(bos).use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray())
    }

    fun unpackJson(z: String): JSONObject? = try {
        val bytes = java.util.Base64.getUrlDecoder().decode(z)
        val out = java.io.ByteArrayOutputStream()
        java.util.zip.GZIPInputStream(java.io.ByteArrayInputStream(bytes)).use { ins ->
            val buf = ByteArray(8192)
            while (true) {
                val n = ins.read(buf); if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > MAX_INFLATED) return null
            }
        }
        JSONObject(String(out.toByteArray(), Charsets.UTF_8))
    } catch (e: Exception) { null }
}
