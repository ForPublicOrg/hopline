package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject

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

        /** Kinds that are stored and handed to phones that missed them (chunks are carried separately, on disk). */
        val CARRIED = setOf(CHAT, DM, RECEIPT, ERRAND, ERRAND_RESULT, FILE)
        // Generous ceiling: a dense crowd has a tiny diameter (each phone holds several links),
        // and even a single-file line of thirty phones stays under this.
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
    val isImage: Boolean get() = mime.startsWith("image/")

    companion object {
        fun make(fid: String, name: String, mime: String, size: Long, chunks: Int, w: Int, h: Int, thumb: String): Attachment =
            Attachment(JSONObject().apply {
                put("fid", fid); put("name", name); put("mime", mime); put("size", size); put("n", chunks)
                if (w > 0) put("w", w); if (h > 0) put("h", h); if (thumb.isNotEmpty()) put("tb", thumb)
            })
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
) {
    var status: String = SENT            // only meaningful for my own messages
    /** When THIS phone got it (its own clock). Unread badges use this — sender clocks drift. */
    var arrivedAt: Long = ts
    val reached: MutableSet<String> = LinkedHashSet()   // node ids whose phone confirmed it
    var errandId: String? = null

    /** True for a group-chat-visible message (not a DM). */
    val isGroup: Boolean get() = to == null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("from", from); put("fromName", fromName)
        if (to != null) put("to", to); put("text", text); put("ts", ts); put("status", status)
        put("at", arrivedAt)
        put("reached", JSONArray(reached.toList())); if (errandId != null) put("errandId", errandId)
        if (att != null) put("att", att.json)
    }

    companion object {
        const val SYSTEM = "system"
        const val QUEUED = "queued"        // nobody has taken it off my phone yet
        const val SENT = "sent"            // at least one other phone has it
        const val DELIVERED = "delivered"  // the recipient's phone has it (DMs)

        fun fromJson(j: JSONObject): Message = Message(
            j.getString("id"), j.getString("kind"), j.getString("from"), j.optString("fromName", ""),
            j.optString("to", "").ifEmpty { null }, j.getString("text"), j.getLong("ts"),
            j.optJSONObject("att")?.let { Attachment(it) },
        ).also { m ->
            m.status = j.optString("status", SENT)
            m.arrivedAt = j.optLong("at", m.ts)
            val r = j.optJSONArray("reached"); if (r != null) for (i in 0 until r.length()) m.reached.add(r.getString(i))
            m.errandId = j.optString("errandId", "").ifEmpty { null }
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
