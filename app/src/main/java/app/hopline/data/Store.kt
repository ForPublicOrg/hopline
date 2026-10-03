package app.hopline.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.util.AtomicFile
import app.hopline.core.Crypto
import app.hopline.core.Names
import app.hopline.core.Words
import app.hopline.mesh.Group
import app.hopline.mesh.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One saved group on this phone. The radio serves one group at a time; the others sleep on disk
 * with their history. Leaving a group does not forget it: the entry stays, marked with [leftAt],
 * and its chat stays readable until the person deletes the group by hand.
 */
class SavedGroup(val code: String, var name: String, val joinedAt: Long, var lastActive: Long, var nameAt: Long = 0) {
    /** 0 = this phone is in the group; otherwise when it left. */
    var leftAt: Long = 0
    /**
     * The tidy-up after leaving has finished: the state file holds only what the chat needs and
     * the file pieces carried for others are gone. Only meaningful while [left] — a phone killed
     * half-way through finds this still false at the next start and finishes the job.
     */
    var sealed: Boolean = false
    val left: Boolean get() = leftAt > 0

    /** Two hashes of the code — worked out once, because Home asks for it on every redraw. */
    val fingerprint: String by lazy { Crypto.fingerprint(Crypto.groupKey(code)) }

    fun copy(): SavedGroup = SavedGroup(code, name, joinedAt, lastActive, nameAt).also { it.leftAt = leftAt; it.sealed = sealed }

    /** A group this phone is in reads and writes exactly as 2.2 saved it; the marks appear only once left. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code); put("name", name); put("joinedAt", joinedAt); put("lastActive", lastActive); put("nameAt", nameAt)
        if (leftAt > 0) put("leftAt", leftAt)
        if (sealed) put("sealed", true)
    }
    companion object {
        fun fromJson(j: JSONObject) = SavedGroup(
            j.getString("code"), Names.clean(j.optString("name", ""), Names.MAX_GROUP), j.optLong("joinedAt", 0),
            j.optLong("lastActive", 0), j.optLong("nameAt", 0)).also {
            it.leftAt = j.optLong("leftAt", 0).coerceAtLeast(0)
            it.sealed = j.optBoolean("sealed", false)
        }
    }
}

/**
 * Tiny persistence: who I am, the groups I'm in and the ones I left, which one the radio serves,
 * what I've read, and (per group) the router's memory — messages, people, backlog.
 *
 * The group list itself is ruled by [GroupRules]; this class only reads and writes what those
 * rules return. Preferences may be read from any thread. A group's state file is different: it is
 * read and written on the app's one writer thread only (see Core), because reading a file while
 * it is being saved throws that save away.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("hopline", Context.MODE_PRIVATE)

    /** The saved list as it was last parsed — Home asks for it several times on every redraw. */
    private class Parsed(val raw: String, val list: List<SavedGroup>)
    @Volatile private var parsed: Parsed? = null
    /**
     * Every change to the group list reads it, changes it and writes it back while holding this:
     * the tidy-up after leaving finishes on a background thread, and must never write an older
     * list over a rejoin the person tapped a moment ago.
     */
    private val groupLock = Any()

    init { migrateSingleGroup(); repairActive() }

    val nodeId: String
        get() = prefs.getString("nodeId", null) ?: Crypto.randomId(8).also { prefs.edit().putString("nodeId", it).commit() }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", Names.clean(v, Names.MAX_PERSON)).apply()

    var permissionsDone: Boolean
        get() = prefs.getBoolean("permissionsDone", false)
        set(v) = prefs.edit().putBoolean("permissionsDone", v).apply()

    /** A hopline://join link tapped before onboarding finished — consumed right after it. */
    var pendingJoin: String?
        get() = prefs.getString("pendingJoin", null)
        set(v) = prefs.edit().apply { if (v == null) remove("pendingJoin") else putString("pendingJoin", v) }.apply()

    /**
     * "Share my internet when I have signal" is about this PHONE's data plan, so it is one switch
     * for every group (2.1 kept it per group, so a new group silently turned it back on).
     */
    var shareInternet: Boolean
        get() {
            if (!prefs.contains("shareInternet")) {
                // First run after the upgrade: inherit what the active group's 2.1 snapshot said.
                val legacy = activeGroup()?.let { peekState(it.fingerprint)?.optBoolean("shareInternet", true) } ?: true
                prefs.edit().putBoolean("shareInternet", legacy).apply()
                return legacy
            }
            return prefs.getBoolean("shareInternet", true)
        }
        set(v) = prefs.edit().putBoolean("shareInternet", v).apply()

    /** Help the group even on roaming data (off by default — roaming bills surprise people). */
    var shareWhileRoaming: Boolean
        get() = prefs.getBoolean("shareRoaming", false)
        set(v) = prefs.edit().putBoolean("shareRoaming", v).apply()

    /** Daily allowance of this phone's data for other people's requests, in MB (0 = no limit). */
    var shareBudgetMb: Int
        get() = prefs.getInt("shareBudgetMb", 5)
        set(v) = prefs.edit().putInt("shareBudgetMb", v.coerceAtLeast(0)).apply()

    val shareBudgetBytes: Long get() = shareBudgetMb * 1024L * 1024L

    private fun today(): String = java.text.SimpleDateFormat("yyyyMMdd", java.util.Locale.US).format(java.util.Date())

    /** Bytes of data this phone spent for others today (all groups together — it's one data plan). */
    fun shareUsedToday(): Long = if (prefs.getString("shareDay", "") == today()) prefs.getLong("shareUsed", 0) else 0

    fun addShareUsage(bytes: Long) {
        val d = today()
        val used = if (prefs.getString("shareDay", "") == d) prefs.getLong("shareUsed", 0) else 0
        prefs.edit().putString("shareDay", d).putLong("shareUsed", used + bytes.coerceAtLeast(0)).apply()
    }

    /** What this phone did for others recently (newest first): who, what, bytes, worked. */
    fun helpLog(): List<JSONObject> = try {
        val a = JSONArray(prefs.getString("helpLog", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    } catch (e: Exception) { emptyList() }

    fun addHelpLog(who: String, what: String, bytes: Int, ok: Boolean) {
        val list = helpLog().toMutableList()
        list.add(0, JSONObject().put("ts", System.currentTimeMillis()).put("who", Names.clean(who)).put("what", what.take(120))
            .put("bytes", bytes).put("ok", ok))
        prefs.edit().putString("helpLog", JSONArray(list.take(40)).toString()).apply()
    }

    /** People this phone texts home most ("Mom", "+91…"), for one-tap "Text home". Max 6. */
    fun homeContacts(): List<Pair<String, String>> = try {
        val a = JSONArray(prefs.getString("homeContacts", "[]") ?: "[]")
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { Names.clean(it.optString("name"), 40) to it.optString("to") }
            .filter { it.second.isNotEmpty() }
    } catch (e: Exception) { emptyList() }

    fun saveHomeContact(name: String, to: String) {
        val list = homeContacts().filter { it.second != to }.toMutableList()
        list.add(0, Names.clean(name, 40) to to.take(120))
        prefs.edit().putString("homeContacts", JSONArray(list.take(6).map { JSONObject().put("name", it.first).put("to", it.second) }).toString()).apply()
    }

    fun removeHomeContact(to: String) {
        val list = homeContacts().filter { it.second != to }
        prefs.edit().putString("homeContacts", JSONArray(list.map { JSONObject().put("name", it.first).put("to", it.second) }).toString()).apply()
    }

    fun identity(): Identity = Identity(nodeId, name)

    // ------------------------------------------------------------------ groups

    /**
     * Every saved group: the ones this phone is in AND the ones it left (their chats are kept until
     * deleted by hand). To read only — a change goes through the functions below, which always save
     * this FULL list. Saving [groups] instead would erase every left group, and its history with it.
     */
    fun allGroups(): List<SavedGroup> {
        val raw = prefs.getString("groups", null) ?: return emptyList()
        parsed?.let { if (it.raw == raw) return it.list }
        return GroupRules.parse(raw).also { parsed = Parsed(raw, it) }
    }

    /** The groups this phone is IN: the one on the radio and the paused ones it can switch to. */
    fun groups(): List<SavedGroup> = GroupRules.members(allGroups())

    /** The groups this phone left, most recently left first. Readable, never on the radio. */
    fun leftGroups(): List<SavedGroup> = GroupRules.left(allGroups())

    /** A saved group by its fingerprint — left ones too. */
    fun findGroup(fp: String): SavedGroup? = allGroups().firstOrNull { it.fingerprint == fp }

    private fun rawActive(): String? = prefs.getString("activeCode", null)

    /** The code of the group the radio serves, or null. Never a left group's. */
    val activeCode: String? get() = activeGroup()?.code

    fun activeGroup(): SavedGroup? = rawActive()?.let { c -> allGroups().firstOrNull { it.code == c && !it.left } }

    /** The group the radio serves right now, as the mesh sees it. */
    fun group(): Group? = activeGroup()?.let { Group(it.code, it.name, it.nameAt) }

    /** Is this code saved on this phone at all? A left group counts: a new group must never be given its code. */
    fun hasGroup(code: String): Boolean = Words.normalise(code).let { c -> allGroups().any { it.code == c } }

    fun isLeft(code: String): Boolean = Words.normalise(code).let { c -> allGroups().any { it.code == c && it.left } }

    /**
     * Write a change to the list: the groups and the active code (and whatever [extra] adds) go in
     * ONE editor and ONE commit, so a phone killed at any moment finds either the old state or the
     * new one — never a group that is gone with the radio still pointed at it. commit(), not
     * apply(): the group list is the one thing a crash right after "Join" or "Leave" must not lose.
     * False when nothing changed, or the write did not reach the disk.
     */
    private fun change(extra: SharedPreferences.Editor.() -> Unit = {}, rule: (List<SavedGroup>, String?) -> GroupRules.Result): Boolean =
        synchronized(groupLock) {
            val list = allGroups()
            val active = rawActive()
            val r = rule(list, active)
            if (r.groups === list && r.active == active) return false
            val edit = prefs.edit().putString("groups", GroupRules.encode(r.groups))
            if (r.active == null) edit.remove("activeCode") else edit.putString("activeCode", r.active)
            edit.extra()
            edit.commit()
        }

    /**
     * Add (or re-activate) a group and make it the active one. [nameAt] is non-zero only when this
     * phone CREATES the group — a name from an invite link is a hint that a real rename beats, and
     * it never overwrites a name this phone already knows. A code this phone left is joined again,
     * exactly as [rejoin] would (the screens ask first and go through Core.rejoinGroup; this is the
     * backstop).
     *
     * A code that was DELETED a moment ago may still have its "purge" note. Joining it again leaves
     * the note alone: it stands until the old files are really gone ([finishPurge]). Dropping it
     * here would let a kill before that bring the deleted chat back inside the new group.
     */
    fun addGroup(code: String, name: String, nameAt: Long = 0) {
        change { list, active -> GroupRules.add(list, active, code, name, nameAt, System.currentTimeMillis()) }
    }

    /** Point the radio at a group this phone is in. A left group is refused: only [rejoin] brings one back. */
    fun setActive(code: String) {
        change { list, active -> GroupRules.setActive(list, active, code, System.currentTimeMillis()) }
    }

    fun renameGroup(code: String, name: String, at: Long) {
        change { list, active -> GroupRules.Result(GroupRules.rename(list, code, name, at), active) }
    }

    /**
     * Leave a group this phone is in: the entry stays, marked with when; if the radio was on it, the
     * radio moves to the most recently used group this phone is still in, or to none; and its
     * unread count goes — all in one commit. This is the moment the group counts as left: whatever
     * tidying follows can be redone from here ([SavedGroup.sealed] says whether it still has to be).
     */
    fun markLeft(code: String, now: Long): Boolean {
        val g = allGroups().firstOrNull { it.code == Words.normalise(code) && !it.left } ?: return false
        return change({ remove("unread-${g.fingerprint}") }) { list, active -> GroupRules.leave(list, active, g.code, now) }
    }

    /**
     * Join a left group again and make it the active one (one commit). Anything else changes nothing.
     *
     * False also when the commit did not reach the disk — and then it does not stand in memory
     * either. (Android keeps a failed commit in memory: the app would go on as if the group were
     * joined, say so, and find it left again at its next start.) The list is put back as it was,
     * so "not rejoined" is true of this run and of the next.
     */
    fun rejoin(code: String, now: Long): Boolean = synchronized(groupLock) {
        val groups = prefs.getString("groups", null)
        val active = rawActive()
        if (change { list, a -> GroupRules.rejoin(list, a, code, now) }) return true
        if (prefs.getString("groups", null) != groups || rawActive() != active) {
            prefs.edit().apply {
                if (groups == null) remove("groups") else putString("groups", groups)
                if (active == null) remove("activeCode") else putString("activeCode", active)
            }.apply()
        }
        false
    }

    /**
     * Take a LEFT group off this phone for good (a group this phone is in must be left first). The
     * entry goes and its fingerprint is noted in "purge" in the same commit: the files are deleted
     * after this, and a phone killed half-way finds the note at its next start and finishes.
     */
    fun removeForGood(code: String): Boolean {
        val g = allGroups().firstOrNull { it.code == Words.normalise(code) && it.left } ?: return false
        return change({ putStringSet("purge", purgePending() + g.fingerprint) }) { list, active -> GroupRules.remove(list, active, g.code) }
    }

    /**
     * The tidy-up after leaving is finished. [tidy] is its last, quick step (moving the carried
     * file pieces out of the way): it runs here, under the lock every change to the list takes, and
     * only while the group is still left — so it can never pull the pieces out from under a group
     * that was joined again a moment ago. The group is marked only if [tidy] says it worked;
     * otherwise the next start tries again. apply() is enough: losing this mark only repeats work.
     */
    fun markSealed(code: String, tidy: () -> Boolean = { true }): Boolean = synchronized(groupLock) {
        val list = allGroups()
        val g = list.firstOrNull { it.code == Words.normalise(code) }
        if (g == null || !g.left || !tidy()) return false
        val sealed = GroupRules.seal(list, g.code)
        if (sealed !== list) prefs.edit().putString("groups", GroupRules.encode(sealed)).apply()
        true
    }

    /** Fingerprints of deleted groups whose files may still be on the phone (see [removeForGood]). */
    fun purgePending(): Set<String> = prefs.getStringSet("purge", null)?.toSet() ?: emptySet()

    /**
     * A deleted group's files are gone: forget its read marks, mutes and unread count, and the note
     * that asked for the delete. commit(): if the same code has been joined again meanwhile, the
     * note must be off the disk before the new group saves anything — a note found at the next
     * start means "the files under this fingerprint are still the deleted group's", and they are
     * deleted on that word.
     */
    fun finishPurge(fp: String) {
        synchronized(groupLock) {
            val rest = purgePending() - fp
            val aside = asideNotes() - fp   // nobody is told about a chat they have since deleted
            prefs.edit().apply {
                for (key in GroupRules.prefKeysFor(fp)) remove(key)
                if (rest.isEmpty()) remove("purge") else putStringSet("purge", rest)
                if (aside.isEmpty()) remove("aside") else putStringSet("aside", aside)
            }.commit()
        }
        synchronized(mutes) { mutes.remove(fp) }
    }

    /**
     * The active code must name a group this phone is in. A phone killed between two writes of an
     * older version could leave it pointing at a group that is gone; then the radio goes to the
     * group used last, as it would have. A list that didn't read at all is no reason to change
     * anything.
     */
    private fun repairActive() = synchronized(groupLock) {
        val list = allGroups()
        if (list.isEmpty() && !prefs.getString("groups", null).isNullOrEmpty()) return@synchronized
        val active = rawActive()
        val healed = GroupRules.heal(list, active)
        if (healed != active) prefs.edit().apply { if (healed == null) remove("activeCode") else putString("activeCode", healed) }.commit()
    }

    // ------------------------------------------------------------------ read marks (for unread badges)

    /** chat is "*" for the group chat or a node id for a private chat. */
    fun lastRead(fp: String, chat: String): Long =
        try { JSONObject(prefs.getString("read-$fp", "{}") ?: "{}").optLong(chat, 0) } catch (e: Exception) { 0 }

    fun setLastRead(fp: String, chat: String, ts: Long) {
        try {
            val j = JSONObject(prefs.getString("read-$fp", "{}") ?: "{}")
            if (j.optLong(chat, 0) >= ts) return
            j.put(chat, ts)
            prefs.edit().putString("read-$fp", j.toString()).apply()
        } catch (e: Exception) { }
    }

    /** How many unread messages a group had when the radio left it — its Home row shows this. */
    fun pausedUnread(fp: String): Int = prefs.getInt("unread-$fp", 0)
    fun setPausedUnread(fp: String, n: Int) { prefs.edit().putInt("unread-$fp", n.coerceAtLeast(0)).apply() }

    // ------------------------------------------------------------------ mute

    /** Until when a chat's notifications are silenced (0 = not muted; Long.MAX_VALUE = always). */
    /** Parsed once per group: Home asks for every row on every redraw. */
    private val mutes = HashMap<String, JSONObject>()
    private fun muteMap(fp: String): JSONObject = mutes.getOrPut(fp) {
        try { JSONObject(prefs.getString("mute-$fp", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }
    }

    fun mutedUntil(fp: String, chat: String): Long = synchronized(mutes) { muteMap(fp).optLong(chat, 0) }

    fun isMuted(fp: String, chat: String): Boolean = System.currentTimeMillis() < mutedUntil(fp, chat)

    fun setMuted(fp: String, chat: String, until: Long) = synchronized(mutes) {
        val j = muteMap(fp)
        if (until <= System.currentTimeMillis()) j.remove(chat) else j.put(chat, until)
        prefs.edit().putString("mute-$fp", j.toString()).apply()
    }

    // ------------------------------------------------------------------ router state, one file per group

    private fun stateFile(fp: String): File = File(context.filesDir, "mesh-state-$fp.json")
    /** What an older way of saving left behind when the phone died mid-save: its good copy (.bak), or 2.1's (.tmp). */
    private fun stateBackup(fp: String): File = File(context.filesDir, "mesh-state-$fp.json.bak")
    private fun stateLegacyTmp(fp: String): File = File(context.filesDir, "mesh-state-$fp.json.tmp")

    /** Is anything saved for this group that a new save would write over? */
    fun stateExists(fp: String): Boolean = stateFile(fp).exists() || stateBackup(fp).exists() || stateLegacyTmp(fp).exists()

    /**
     * A group's saved state, to carry on from. Null when there is none — or when what is there
     * can't be used: a file that won't read or won't parse (or is too big for this phone's memory)
     * is moved aside as .corrupt-<time>, never left for the next save to write over. It holds a
     * group's whole chat, and that is worth keeping for a second look. If it can't even be moved,
     * [stateExists] still says so, and nothing may be saved for the group.
     *
     * Moving it aside is for good, so a failure that doesn't prove the file bad — an error from
     * the storage, memory running out half way — is tried once more first ([StateRules.onReadFailure]).
     *
     * AtomicFile writes the new state beside the old one and swaps them only once it is synced, so
     * a power cut mid-save leaves the previous state, never an empty file. Writer thread only.
     */
    fun loadState(fp: String): JSONObject? {
        val f = stateFile(fp)
        var failed = 0
        while (true) {
            try {
                val raw = when {
                    f.exists() || stateBackup(fp).exists() -> String(AtomicFile(f).readFully(), Charsets.UTF_8)
                    stateLegacyTmp(fp).exists() -> stateLegacyTmp(fp).readText()   // 2.1 died between delete and rename
                    else -> return null
                }
                return JSONObject(raw)
            } catch (t: Throwable) {
                if (StateRules.onReadFailure(t, failed++) == StateRules.Failed.ASIDE) { moveStateAside(fp); return null }
                // Whatever got in the way may have passed in a moment.
                try { Thread.sleep(READ_AGAIN_MS) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            }
        }
    }

    /**
     * The same read for a group that is only being looked at (a left group's chat, the tidy-up
     * after leaving): it never renames, moves or deletes anything. Opening an old chat must not be
     * the thing that makes it vanish — a file that doesn't read today is still there tomorrow.
     * Null when there is no file, or it can't be read ([stateExists] tells the two apart).
     * Writer thread only.
     */
    fun peekState(fp: String): JSONObject? {
        // The same order of trust AtomicFile has: a backup, when there is one, is the last good save.
        val f = listOf(stateBackup(fp), stateFile(fp), stateLegacyTmp(fp)).firstOrNull { it.exists() } ?: return null
        return try { JSONObject(f.readText(Charsets.UTF_8)) } catch (t: Throwable) { null }
    }

    /**
     * Put a state that can't be used out of the next save's way, keeping it on the phone as
     * .corrupt-<time> (it goes only when its group is deleted). True when nothing is left to be
     * written over. Writer thread only.
     */
    fun moveStateAside(fp: String): Boolean {
        val stamp = System.currentTimeMillis()
        var clear = true
        var any = false
        for (f in listOf(stateFile(fp), stateBackup(fp), stateLegacyTmp(fp))) {
            if (!f.exists()) continue
            val moved = try { f.renameTo(File(f.parentFile, "${f.name}.corrupt-$stamp")) } catch (e: Exception) { false }
            if (moved) any = true else if (f.exists()) clear = false
        }
        // The group's chat now starts empty, and the person is owed a word about why ([takeAsideNotes]).
        if (any) synchronized(groupLock) { prefs.edit().putStringSet("aside", asideNotes() + fp).apply() }
        return clear
    }

    private fun asideNotes(): Set<String> = prefs.getStringSet("aside", null)?.toSet() ?: emptySet()

    /**
     * The groups whose saved chat was set aside as unusable since this was last asked — so that a
     * chat that suddenly starts empty is never left unexplained. Each is handed out once: whoever
     * takes them tells the person.
     */
    fun takeAsideNotes(): Set<String> = synchronized(groupLock) {
        val notes = asideNotes()
        if (notes.isNotEmpty()) prefs.edit().remove("aside").apply()
        notes
    }

    /** True when the state is safely on disk. Writer thread only. */
    fun saveState(fp: String, j: JSONObject): Boolean {
        val af = AtomicFile(stateFile(fp))
        var out: java.io.FileOutputStream? = null
        return try {
            out = af.startWrite()
            out.write(j.toString().toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
            stateLegacyTmp(fp).delete()
            true
        } catch (t: Throwable) {
            try { out?.let { af.failWrite(it) } } catch (e: Exception) { }
            false
        }
    }

    /**
     * Delete everything saved as a group's state: the file itself, a save cut short (.new), old
     * backups (.bak, .tmp) and every copy set aside as .corrupt-<time>. Writer thread only.
     */
    fun deleteStateFiles(fp: String) {
        val prefix = "mesh-state-$fp.json"
        context.filesDir.listFiles()?.forEach { if (it.isFile && it.name.startsWith(prefix)) it.delete() }
    }

    /** Where a group's older messages are filed (see [History]). Asking never creates it. */
    fun historyDir(fp: String): File = File(File(context.filesDir, "history"), fp)

    // ------------------------------------------------------------------ migration from the single-group 1.x layout

    private fun migrateSingleGroup() {
        val legacyCode = prefs.getString("groupCode", null) ?: return
        if (prefs.getString("groups", null) == null) {
            val now = System.currentTimeMillis()
            val g = SavedGroup(Words.normalise(legacyCode), prefs.getString("groupName", "") ?: "", now, now)
            prefs.edit().putString("groups", GroupRules.encode(listOf(g))).putString("activeCode", g.code).commit()
            val old = File(context.filesDir, "mesh-state.json")
            if (old.exists()) old.renameTo(stateFile(g.fingerprint))
            seedReadMarks(g.fingerprint, now)
        }
        prefs.edit().remove("groupCode").remove("groupName").apply()
    }

    /** 1.x had no unread badges — everything on the phone at upgrade counts as already read. */
    private fun seedReadMarks(fp: String, now: Long) {
        try {
            setLastRead(fp, "*", now)
            val state = loadState(fp) ?: return
            val msgs = state.optJSONArray("messages") ?: return
            val me = nodeId
            for (i in 0 until msgs.length()) {
                val m = msgs.getJSONObject(i)
                val to = m.optString("to", "")
                if (to.isEmpty()) continue
                val partner = if (m.optString("from") == me) to else m.optString("from")
                if (partner.isNotEmpty()) setLastRead(fp, partner, now)
            }
        } catch (e: Exception) { /* badges will just start fresh */ }
    }

    private companion object {
        /** How long a state read that failed without proving the file bad waits before its one more try. */
        const val READ_AGAIN_MS = 300L
    }
}
