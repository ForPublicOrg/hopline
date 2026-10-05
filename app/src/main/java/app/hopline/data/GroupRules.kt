package app.hopline.data

import app.hopline.core.Crypto
import app.hopline.core.Names
import app.hopline.core.Words
import org.json.JSONArray

/**
 * The plain rules behind the saved-group list: which groups this phone is in, which it has left,
 * and which one the radio serves next. Pure functions over the list and the active code, with the
 * time passed in — no Android in here, so the JVM tests can pin them down. [Store] only reads and
 * writes what these return.
 *
 * The rule everything else leans on: a group that was left STAYS in the list until the person
 * deletes it, so its chat can still be opened. Every function therefore takes and returns the
 * FULL list, members and left groups alike — saving a filtered list would quietly erase every
 * left group, and with it the only way back to its history. Nothing here changes the list it was
 * given: a changed entry is a copy.
 */
object GroupRules {
    /** The full list after a change, and the code of the group the radio serves (null = none). */
    data class Result(val groups: List<SavedGroup>, val active: String?)

    /** One entry that won't parse is skipped on its own — it must never cost the other groups. */
    fun parse(raw: String?): List<SavedGroup> {
        if (raw.isNullOrEmpty()) return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> try { SavedGroup.fromJson(a.getJSONObject(i)) } catch (e: Exception) { null } }
        } catch (e: Exception) { emptyList() }
    }

    fun encode(list: List<SavedGroup>): String = JSONArray(list.map { it.toJson() }).toString()

    /** Groups this phone is in: the one on the radio and the paused ones it can switch to. */
    fun members(list: List<SavedGroup>): List<SavedGroup> = list.filter { !it.left }

    /** Groups this phone left, whose chats are kept to read — the most recently left first. */
    fun left(list: List<SavedGroup>): List<SavedGroup> = list.filter { it.left }.sortedByDescending { it.leftAt }

    /**
     * Where the radio goes when its group is taken away: the group this phone is still IN that it
     * used last. Never a left group — leaving one must not quietly put the phone back on another
     * it had also left. Null when there is no such group.
     */
    fun nextActive(list: List<SavedGroup>, excluding: String? = null): String? {
        val skip = excluding?.let { Words.normalise(it) }
        return list.filter { !it.left && it.code != skip }.maxByOrNull { it.lastActive }?.code
    }

    /**
     * Add a group and make it the active one. [nameAt] is non-zero only when this phone CREATES
     * the group — a name from an invite link is a hint that a real rename beats, and it never
     * overwrites a name this phone already knows. A code this phone already has is not added
     * twice: a paused group simply becomes active, and a left one is joined again exactly as
     * [rejoin] would — its name, its first-joined date and its chat are what they were.
     *
     * A new group gets a storage id of its own from [newSid], never one another saved group has:
     * two codes can share their old 8-hex fingerprint, and must never share a chat file. [mk] is
     * its master key (base64url), worked out beforehand; an entry that has none yet takes it.
     */
    fun add(list: List<SavedGroup>, active: String?, code: String, name: String, nameAt: Long, now: Long, mk: String = "",
            newSid: () -> String = { Crypto.randomId(SID_LENGTH) }): Result {
        val norm = Words.normalise(code)
        if (norm.isEmpty()) return Result(list, active)
        val clean = Names.clean(name, Names.MAX_GROUP)
        val existing = list.firstOrNull { it.code == norm }
        val groups = when {
            existing == null -> {
                val taken = list.map { it.sid }.toHashSet()
                var sid = newSid()
                while (sid in taken) sid = newSid()
                list + SavedGroup(norm, clean, now, now, if (clean.isEmpty()) 0 else nameAt, sid, mk)
            }
            existing.left -> list.changing(norm) { it.leftAt = 0; it.sealed = false; it.lastActive = now; if (it.mk.isEmpty()) it.mk = mk }
            else -> list.changing(norm) {
                if (it.name.isEmpty() && clean.isNotEmpty()) it.name = clean
                it.lastActive = now
                if (it.mk.isEmpty()) it.mk = mk
            }
        }
        return Result(groups, norm)
    }

    /**
     * Keep a group's master key ([mk], base64url) once it has been worked out. A group that has
     * one already, or a code this phone doesn't have, changes nothing: the very list comes back.
     */
    fun setKey(list: List<SavedGroup>, code: String, mk: String): List<SavedGroup> {
        val norm = Words.normalise(code)
        val g = list.firstOrNull { it.code == norm }
        if (g == null || g.mk.isNotEmpty() || mk.isEmpty()) return list
        return list.changing(norm) { it.mk = mk }
    }

    /** Point the radio at a group this phone is in. A code it doesn't have, or has left, changes nothing. */
    fun setActive(list: List<SavedGroup>, active: String?, code: String, now: Long): Result {
        val norm = Words.normalise(code)
        val g = list.firstOrNull { it.code == norm }
        if (g == null || g.left) return Result(list, active)
        return Result(list.changing(norm) { it.lastActive = now }, norm)
    }

    /**
     * Leave a group: it stays in the list, marked with when it was left, and the tidy-up of its
     * files starts over ([SavedGroup.sealed] = false). If the radio was on it, the radio moves to
     * [nextActive] — or to nothing, when that was the last group this phone was in.
     */
    fun leave(list: List<SavedGroup>, active: String?, code: String, now: Long): Result {
        val norm = Words.normalise(code)
        val g = list.firstOrNull { it.code == norm } ?: return Result(list, active)
        // Already left: the date it was left (and the tidy-up done since) stand.
        val groups = if (g.left) list else list.changing(norm) { it.leftAt = now.coerceAtLeast(1); it.sealed = false }
        return Result(groups, if (active == norm) nextActive(groups) else active)
    }

    /** Join a left group again and put the radio on it. Anything but a left group changes nothing. */
    fun rejoin(list: List<SavedGroup>, active: String?, code: String, now: Long): Result {
        val norm = Words.normalise(code)
        val g = list.firstOrNull { it.code == norm }
        if (g == null || !g.left) return Result(list, active)
        return Result(list.changing(norm) { it.leftAt = 0; it.sealed = false; it.lastActive = now }, norm)
    }

    /**
     * Note that the tidy-up after leaving is done. Only a left group that isn't sealed yet changes;
     * for anything else — a group joined again meanwhile above all — the very list comes back.
     */
    fun seal(list: List<SavedGroup>, code: String): List<SavedGroup> {
        val norm = Words.normalise(code)
        val g = list.firstOrNull { it.code == norm }
        if (g == null || !g.left || g.sealed) return list
        return list.changing(norm) { it.sealed = true }
    }

    /** Take a group out of the list for good. If the radio was on it, it moves to [nextActive]. */
    fun remove(list: List<SavedGroup>, active: String?, code: String): Result {
        val norm = Words.normalise(code)
        val groups = list.filter { it.code != norm }
        return Result(groups, if (active == norm) nextActive(groups) else active)
    }

    /**
     * The active code as it should be: unchanged while it names a group this phone is in;
     * otherwise (it names a left group, or one that is gone) the group to fall back to, or null.
     */
    fun heal(list: List<SavedGroup>, active: String?): String? =
        if (active != null && list.any { it.code == active && !it.left }) active else nextActive(list)

    fun rename(list: List<SavedGroup>, code: String, name: String, at: Long): List<SavedGroup> {
        val norm = Words.normalise(code)
        if (list.none { it.code == norm }) return list
        return list.changing(norm) { it.name = Names.clean(name, Names.MAX_GROUP); it.nameAt = at }
    }

    /**
     * The per-group preference keys: read marks, mutes, the unread count kept while paused, and
     * replies typed into a notification while the group was still starting. Leaving keeps the
     * first two (a rejoin must not count the whole chat as unread, or forget a mute); deleting the
     * group removes them all.
     */
    fun prefKeysFor(fp: String): List<String> = listOf("read-$fp", "mute-$fp", "unread-$fp", "outbox-$fp")

    /** Letters in a new group's storage id: 60 random bits, so no two groups on a phone ever share one. */
    const val SID_LENGTH = 12

    private fun List<SavedGroup>.changing(code: String, change: (SavedGroup) -> Unit): List<SavedGroup> =
        map { if (it.code == code) it.copy().also(change) else it }
}
