package app.hopline.data

import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import org.json.JSONArray
import org.json.JSONObject

/**
 * A group's saved chat, made fit for this version. JSON in, JSON out — no Android, so the JVM
 * tests can pin it down; Store runs it on the writer thread inside every load of a group's state
 * (the group on the radio, a paused one, a left one), before any router restores it.
 *
 * 2.4 gave every phone a key pair, and a node id made from it — a NEW id for this phone. A state
 * saved before ("fmt" under 5) still names this phone by its old id, and carries envelopes no
 * phone takes any more. So:
 *  1. the group messages of mine that had not left this phone yet, and whose old envelope was
 *     still within its 48 h, are listed under "reissue": the router sends them again as new
 *     envelopes (Router.reissueQueued). A group this phone left gets no list — nothing is ever
 *     sent from one, not even after a rejoin (see Archive);
 *  2. every one of my former ids becomes my new id — wherever it stands as a value or a key,
 *     except in words people wrote (a name, a message, a title…) — here and in every page of the
 *     group's history ([page]), so my old messages are still mine;
 *  3. the old carry goes, and the state says which format and whose it is ("fmt", "me").
 *
 * Message ids, file ids and everything else stay exactly as they were. Running it again on what
 * it gave back changes nothing. The same id swap runs on a 2.4 state whose "me" is one of my
 * former ids (a phone that had to make a new key pair): its carry and its role ops are left as
 * they are, signed as they stand — so that phone stays an admin under its old id only.
 */
object Upgrade {
    /** Keys whose values are words people wrote: nothing in them is ever an id to swap. */
    val TEXT_KEYS = setOf("text", "t", "name", "fromName", "n", "title", "result", "lbl")

    /**
     * [state] as this version keeps it, or null when it is already: a 2.4 state that names me.
     * [state] itself is not changed. [left]: the group was left, so nothing in it is sent again.
     */
    fun state(state: JSONObject, me: String, formerIds: Set<String>, now: Long, left: Boolean): JSONObject? {
        val old = state.optInt("fmt", 0) < Router.FMT
        val savedMe = state.optString("me", "")
        if (!old && (savedMe == me || savedMe !in formerIds)) return null
        val swap = Swap(me, formerIds - me)
        val out = JSONObject()
        for (key in state.keys()) {
            if (old && (key == "carry" || key == "born" || key == "reissue")) continue
            // A 2.4 carry, and the role ops, are signed as they are: a changed id would make every phone refuse them.
            out.put(key, if (key == "carry" || key == "roles") state.get(key) else swap.of(state.get(key), key))
        }
        if (old && !left) out.put("reissue", JSONArray(reissue(state, formerIds - me, now)))
        out.put("fmt", Router.FMT)
        out.put("me", me)
        return out
    }

    /**
     * One page of the group's history (an array of saved messages) with my former ids swapped as
     * in [state]; null when nothing in it changes, so the page needs no writing.
     */
    fun page(records: JSONArray, me: String, formerIds: Set<String>): JSONArray? {
        val former = formerIds - me
        if (former.isEmpty()) return null
        val swap = Swap(me, former)
        val out = swap.of(records, null) as JSONArray
        return if (swap.changed) out else null
    }

    /**
     * The messages of mine to send again: my group messages and group files — never a private
     * one, whose recipient's old id no longer exists on the air — still waiting to leave this
     * phone, whose old envelope was still being carried and within its time.
     */
    fun reissue(state: JSONObject, mine: Set<String>, now: Long): List<String> {
        val born = state.optJSONObject("born")
        val carried = HashMap<String, Long>()
        state.optJSONArray("carry")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optString("id", "")
                if (id.isEmpty()) continue
                // The same reckoning Router.restore makes: its saved start, else the sender's stamp.
                carried[id] = born?.optLong(id, 0)?.takeIf { it > 0 } ?: minOf(e.optLong("ts", 0), now)
            }
        }
        val out = ArrayList<String>()
        state.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) {
                val m = a.optJSONObject(i) ?: continue
                val id = m.optString("id", "")
                if (id.isEmpty() || m.optString("from", "") !in mine) continue
                if (m.optString("status", Message.SENT) != Message.QUEUED || m.optString("to", "").isNotEmpty()) continue
                val kind = m.optString("kind", "")
                if (kind != Envelope.CHAT && !(kind == Envelope.FILE && m.optJSONObject("att") != null)) continue
                val bornAt = carried[id] ?: continue
                if (now - bornAt > Router.CARRY_MS) continue
                out.add(id)
            }
        }
        return out
    }

    /** A copy of a JSON value with every id in [former] made [me]; [changed] says whether any was. */
    private class Swap(val me: String, val former: Set<String>) {
        var changed = false

        fun of(v: Any?, key: String?): Any? = when {
            key != null && key in TEXT_KEYS -> v
            v is String -> if (v in former) { changed = true; me } else v
            v is JSONObject -> JSONObject().also { o ->
                for (k in v.keys()) {
                    val name = if (k in former) { changed = true; me } else k
                    o.put(name, of(v.get(k), k))
                }
            }
            v is JSONArray -> JSONArray().also { a -> for (i in 0 until v.length()) a.put(of(v.get(i), null)) }
            else -> v
        }
    }
}
