package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class Identity(val id: String, var name: String)

class Group(code: String, var name: String) {
    val code: String = app.hopline.core.Words.normalise(code)
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
    val fid: String get() = json.getString("fid")
    val name: String get() = json.optString("name", "file")
    val mime: String get() = json.optString("mime", "application/octet-stream")
    val size: Long get() = json.optLong("size", 0)
    val chunks: Int get() = json.optInt("n", 0)
    val width: Int get() = json.optInt("w", 0)
    val height: Int get() = json.optInt("h", 0)
    val thumb: String get() = json.optString("tb", "")   // tiny base64 JPEG, shown while pieces arrive
    val dur: Int get() = json.optInt("dur", 0).coerceIn(0, 3600)   // seconds, for voice notes
    val isImage: Boolean get() = mime.startsWith("image/")
    val isAudio: Boolean get() = mime.startsWith("audio/")

    companion object {
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
class Quote(val id: String, val name: String, val text: String) {
    fun toJson(): JSONObject = JSONObject().apply { put("id", id); put("n", name); put("t", text) }
    companion object {
        const val MAX_SNIPPET = 120
        fun of(m: Message): Quote {
            val snippet = when {
                m.loc != null -> "📍 " + m.loc.label.ifEmpty { "Location" }
                m.att?.isAudio == true -> "🎤 Voice note"
                m.att?.isImage == true -> "📷 " + m.text.ifEmpty { "Photo" }
                m.att != null -> "📎 " + m.att.name
                else -> m.text
            }
            return Quote(m.id, m.fromName, snippet.take(MAX_SNIPPET))
        }
        fun fromJson(j: JSONObject?): Quote? {
            if (j == null) return null
            val id = j.optString("id"); if (id.isEmpty() || id.length > 40) return null
            return Quote(id, j.optString("n", "").take(40), j.optString("t", "").take(MAX_SNIPPET))
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
    val reached: MutableSet<String> = LinkedHashSet()   // node ids whose phone confirmed it
    var errandId: String? = null

    /** Who reacted with what. One reaction per person; changing it replaces, empty removes. */
    val reactions = LinkedHashMap<String, String>()     // origin node id -> emoji
    private val reactionTs = HashMap<String, Long>()    // last-write-wins across the flood

    /** True for a group-chat-visible message (not a DM). */
    val isGroup: Boolean get() = to == null

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

    /** "👍 3  ❤️ 1" — what the pill under the bubble shows. */
    fun reactionSummary(): String {
        if (reactions.isEmpty()) return ""
        val counts = LinkedHashMap<String, Int>()
        for (e in reactions.values) counts[e] = (counts[e] ?: 0) + 1
        return counts.entries.joinToString("  ") { (e, n) -> if (n == 1) e else "$e $n" }
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
        const val QUEUED = "queued"        // nobody has taken it off my phone yet
        const val SENT = "sent"            // at least one other phone has it
        const val DELIVERED = "delivered"  // the recipient's phone has it (DMs)
        const val MAX_MENTIONS = 20
        const val MAX_EMOJI = 8            // UTF-16 units; the biggest real emoji sequences fit
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
            j.getString("id"), j.getString("kind"), j.getString("from"), j.optString("fromName", ""),
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
    var lastSeen: Long = 0     // last moment we know their phone was alive
    var hasInternet: Boolean = false
    var hops: Int = 99         // how many phones away, from their latest presence
    var direct: Boolean = false
    var battery: Int = -1
    /** Live location, while they share it. Rides presence, so it clears itself when they stop.
     *  Deliberately not persisted — a position from before a restart is a lie. */
    var loc: Loc? = null
    var locAt: Long = 0        // their clock, from the presence envelope that carried it

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("name", name); put("lastSeen", lastSeen); put("hasInternet", hasInternet)
        put("hops", hops); put("battery", battery)
    }
    companion object {
        fun fromJson(j: JSONObject): Person = Person(j.getString("id")).also {
            it.name = j.optString("name", ""); it.lastSeen = j.optLong("lastSeen", 0)
            it.hasInternet = j.optBoolean("hasInternet", false); it.hops = j.optInt("hops", 99)
            it.battery = j.optInt("battery", -1)
        }
    }
}

class Errand(
    val id: String,
    val type: String,          // weather | send | read
    val args: JSONObject,
    val from: String,
    val fromName: String,
    val ts: Long,
) {
    var helper: String? = null   // node id of the phone asked to do it
    var helperName: String = ""
    var status: String = WAITING
    var result: String? = null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type); put("args", args); put("from", from); put("fromName", fromName); put("ts", ts)
        put("helper", helper ?: ""); put("helperName", helperName); put("status", status); put("result", result ?: "")
    }
    companion object {
        const val WAITING = "waiting"   // nobody with internet yet
        const val ASKED = "asked"       // sent to a helper
        const val DONE = "done"
        const val SEND = "send"
        const val READ = "read"
        fun fromJson(j: JSONObject): Errand = Errand(
            j.getString("id"), j.getString("type"), j.getJSONObject("args"), j.getString("from"),
            j.optString("fromName", ""), j.getLong("ts"),
        ).also {
            it.helper = j.optString("helper", "").ifEmpty { null }; it.helperName = j.optString("helperName", "")
            it.status = j.optString("status", WAITING); it.result = j.optString("result", "").ifEmpty { null }
        }
    }
}
