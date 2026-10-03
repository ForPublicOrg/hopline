package app.hopline.mesh

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a group's saved state becomes when this phone leaves the group: the chat stays, exactly as
 * it was, and everything that was only there to serve the group goes. JSON in, JSON out — no
 * router needed, so the same rule works for the group on the radio (its last snapshot) and for a
 * paused one sleeping in its file. No Android in here, so the JVM tests can pin it down.
 *
 * Kept: every message as it is (words, reactions, quotes, places, attachment records, ticks),
 * people's names, what was deleted here or filed in the history (so neither comes back on a
 * rejoin), which requests are already answered (so none is run twice), my own requests — the
 * finished ones with their answers, and the ones still open — and anything this version doesn't
 * know, untouched: a key that is passed on can still be dropped later; history that is dropped
 * is gone.
 *
 * Why my open requests stay: leaving says nothing on the radio, so the group goes on carrying a
 * request of mine and a phone with signal may still do it — send the text, fetch the page. If
 * this phone had forgotten asking, a rejoin would throw the answer away unread, and the person,
 * finding no trace of the request, would ask again: a family texted twice. Kept, a request
 * simply sits in the archive (nothing there is ever ticked); after a rejoin it is treated like one
 * on a phone that was switched off meanwhile — the answer handed back is taken, or the request
 * is said to have run out, once.
 *
 * Dropped:
 *  - the store-and-forward backlog — other people's private messages, receipts, reactions,
 *    requests — except the envelopes of messages this chat shows that phones are still passing
 *    round: carrying those on means a rejoin within 48 h isn't offered the same messages again at
 *    every sync. (The rest the group hands back on a rejoin, and the router carries it again —
 *    what is this phone's own, or of a message deleted here, without reading it a second time;)
 *  - the envelope of any message of mine that never left this phone. It reads "Not sent" from now
 *    on, and nothing sends it later without my say — not even a rejoin;
 *  - every request that isn't mine (their details are phone numbers and other people's texts),
 *    and the work in progress.
 */
object Archive {
    /** Envelopes that became a line in this phone's chat: messages, private messages, files. */
    private val SHOWN_KINDS = setOf(Envelope.CHAT, Envelope.DM, Envelope.FILE)
    /** Rebuilt below, or left behind; every other key is handed through as it is. */
    private val HANDLED = setOf("messages", "carry", "born", "errands", "running", "sendOpened")

    /**
     * [state] as an archive: see the class comment. Also notes "You left" in the chat, once per
     * leaving ([leftAt] names it), so the gap that follows explains itself after a rejoin. The
     * line is placed after the chat's last "You rejoined" whatever the clock says: a clock put
     * back since then must not file this leaving above the rejoin before it — the next rejoin
     * would find its line already "answered", and say nothing.
     *
     * [state] itself is not changed, and neither is any message in it: the result holds the very
     * same message objects in the same order. Stripping an archive again (same [leftAt], same
     * [now]) gives the same archive, so a tidy-up interrupted half-way can simply be run again.
     * A state from an older version (missing keys) and bad records are taken in stride.
     */
    fun strip(state: JSONObject, meId: String, meName: String, leftAt: Long, now: Long): JSONObject {
        val messages = JSONArray()
        val shown = HashSet<String>()    // what this chat shows
        val unsent = HashSet<String>()   // mine, and never off this phone
        var lastNote = 0L                // where the chat's newest "You left" / "You rejoined" sorts
        state.optJSONArray("messages")?.let { a ->
            for (i in 0 until a.length()) {
                val item = a.opt(i) ?: continue
                messages.put(item)
                val m = item as? JSONObject ?: continue
                val id = m.optString("id", "")
                if (id.isEmpty()) continue
                shown.add(id)
                if (m.optString("from", "") == meId && m.optString("status", Message.SENT) == Message.QUEUED) unsent.add(id)
                val kind = m.optString("kind", "")
                if (kind == Message.LEFT || kind == Message.REJOINED) lastNote = maxOf(lastNote, sortKeyOf(m))
            }
        }
        val notice = "local.${Message.LEFT}.$leftAt"
        if (leftAt > 0 && notice !in shown && !listed(state, "hidden", notice) && !listed(state, "spilled", notice)) {
            messages.put(Message(notice, Message.LEFT, meId, meName, null, "", maxOf(leftAt, lastNote + 1)).toJson())
        }

        val born = state.optJSONObject("born")
        val carry = JSONArray()
        val carryBorn = JSONObject()
        state.optJSONArray("carry")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val id = e.optString("id", "")
                if (id !in shown || id in unsent || e.optString("k", "") !in SHOWN_KINDS) continue
                // The same reckoning Router.restore makes: its saved start, else the sender's stamp.
                val bornAt = born?.optLong(id, 0)?.takeIf { it > 0 } ?: minOf(e.optLong("ts", 0), now)
                if (now - bornAt > Router.CARRY_MS) continue
                carry.put(e)
                born?.opt(id)?.let { carryBorn.put(id, it) }
            }
        }

        val errands = JSONArray()
        state.optJSONArray("errands")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                // Mine stay, open or finished. (A record with no id is no request any router could take back.)
                if (e.optString("from", "") != meId || e.optString("id", "").isEmpty()) continue
                errands.put(e)
            }
        }

        val out = JSONObject()
        out.put("messages", messages)
        out.put("carry", carry)
        out.put("born", carryBorn)
        out.put("errands", errands)
        for (key in state.keys()) if (key !in HANDLED) out.put(key, state.opt(key))
        return out
    }

    /**
     * The other end of [strip]: [r] has been restored from a group's state and is going on the
     * radio. If the chat's last word on the matter is "You left", it gets its "You rejoined" —
     * once: from then on that line is the last word, however often the app starts. Going by the
     * chat itself needs no flag to keep, so it holds through a kill, or a detour to grant
     * permissions, between joining again and the radio coming up; and a group this phone never
     * left has no such line to answer. Returns the line it added, or null.
     *
     * "Last" is by the chat's own order, which goes by each line's stamp — so the answer is
     * stamped just after the "You left" it answers, never before it. Stamped by the clock alone, a
     * rejoin made after the clock was put back (it ran fast with no network, and was corrected)
     * would sort above the leaving: "You left" would stay the last word, and every start of the
     * app would add one more "You rejoined".
     */
    fun rejoined(r: Router): Message? {
        val last = r.messages.lastOrNull { it.kind == Message.LEFT || it.kind == Message.REJOINED } ?: return null
        return if (last.kind == Message.LEFT) r.addLocalNotice(Message.REJOINED, notBefore = last.sortKey + 1) else null
    }

    private fun listed(state: JSONObject, map: String, id: String): Boolean = state.optJSONObject(map)?.has(id) == true

    /** [Message.sortKey], read straight off a saved message. */
    private fun sortKeyOf(m: JSONObject): Long {
        val ts = m.optLong("ts", 0)
        return minOf(ts, m.optLong("at", ts) + 5 * 60_000L)
    }
}
