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
        const val SOS = "sos"
        const val ERRAND = "errand"  // "someone with internet, please do this"
        const val ERRAND_RESULT = "errres"

        /** Kinds that are stored and handed to phones that missed them. */
        val CARRIED = setOf(CHAT, DM, RECEIPT, SOS, ERRAND, ERRAND_RESULT)
        const val MAX_HOPS = 16
    }
}

class Message(
    val id: String,
    val kind: String,          // Envelope.CHAT / DM / SOS, or SYSTEM
    val from: String,
    val fromName: String,
    val to: String?,           // DM target, else null
    val text: String,
    val ts: Long,
) {
    var status: String = SENT            // only meaningful for my own messages
    val reached: MutableSet<String> = LinkedHashSet()   // node ids whose phone confirmed it
    var errandId: String? = null

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("kind", kind); put("from", from); put("fromName", fromName)
        if (to != null) put("to", to); put("text", text); put("ts", ts); put("status", status)
        put("reached", JSONArray(reached.toList())); if (errandId != null) put("errandId", errandId)
    }

    companion object {
        const val SYSTEM = "system"
        const val QUEUED = "queued"        // nobody has taken it off my phone yet
        const val SENT = "sent"            // at least one other phone has it
        const val DELIVERED = "delivered"  // the recipient's phone has it (DMs)

        fun fromJson(j: JSONObject): Message = Message(
            j.getString("id"), j.getString("kind"), j.getString("from"), j.optString("fromName", ""),
            j.optString("to", "").ifEmpty { null }, j.getString("text"), j.getLong("ts"),
        ).also { m ->
            m.status = j.optString("status", SENT)
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
        const val WEATHER = "weather"
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
