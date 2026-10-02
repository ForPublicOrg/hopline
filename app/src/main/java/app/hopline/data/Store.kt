package app.hopline.data

import android.content.Context
import androidx.core.util.AtomicFile
import app.hopline.core.Crypto
import app.hopline.core.Names
import app.hopline.core.Words
import app.hopline.mesh.Group
import app.hopline.mesh.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved group on this phone. The radio serves one at a time; the rest keep their history. */
class SavedGroup(val code: String, var name: String, val joinedAt: Long, var lastActive: Long, var nameAt: Long = 0) {
    val fingerprint: String get() = Crypto.fingerprint(Crypto.groupKey(code))
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code); put("name", name); put("joinedAt", joinedAt); put("lastActive", lastActive); put("nameAt", nameAt)
    }
    companion object {
        fun fromJson(j: JSONObject) = SavedGroup(
            j.getString("code"), Names.clean(j.optString("name", ""), Names.MAX_GROUP), j.optLong("joinedAt", 0),
            j.optLong("lastActive", 0), j.optLong("nameAt", 0))
    }
}

/**
 * Tiny persistence: who I am, the groups I'm in, which one the radio serves, what I've read,
 * and (per group) the router's memory — messages, people, backlog.
 */
class Store(private val context: Context) {
    private val prefs = context.getSharedPreferences("hopline", Context.MODE_PRIVATE)

    init { migrateSingleGroup() }

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
                val legacy = activeGroup()?.let { loadState(it.fingerprint)?.optBoolean("shareInternet", true) } ?: true
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

    fun groups(): List<SavedGroup> {
        val raw = prefs.getString("groups", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).mapNotNull { i -> try { SavedGroup.fromJson(a.getJSONObject(i)) } catch (e: Exception) { null } }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveGroups(list: List<SavedGroup>) {
        // commit(): the group list is the one thing a crash right after "Join" must not lose.
        prefs.edit().putString("groups", JSONArray(list.map { it.toJson() }).toString()).commit()
    }

    var activeCode: String?
        get() = prefs.getString("activeCode", null)
        private set(v) { prefs.edit().apply { if (v == null) remove("activeCode") else putString("activeCode", v) }.commit() }

    fun activeGroup(): SavedGroup? = activeCode?.let { c -> groups().firstOrNull { it.code == c } }

    /** The group the radio serves right now, as the mesh sees it. */
    fun group(): Group? = activeGroup()?.let { Group(it.code, it.name, it.nameAt) }

    fun hasGroup(code: String): Boolean = groups().any { it.code == Words.normalise(code) }

    /**
     * Add (or re-activate) a group and make it the active one. [nameAt] is non-zero only when this
     * phone CREATES the group — a name from an invite link is a hint that a real rename beats, and
     * it never overwrites a name this phone already knows.
     */
    fun addGroup(code: String, name: String, nameAt: Long = 0) {
        val norm = Words.normalise(code)
        val clean = Names.clean(name, Names.MAX_GROUP)
        val now = System.currentTimeMillis()
        val list = groups().toMutableList()
        val existing = list.firstOrNull { it.code == norm }
        if (existing != null) {
            if (existing.name.isEmpty() && clean.isNotEmpty()) existing.name = clean
            existing.lastActive = now
        } else list.add(SavedGroup(norm, clean, now, now, if (clean.isEmpty()) 0 else nameAt))
        saveGroups(list)
        activeCode = norm
    }

    fun setActive(code: String) {
        val norm = Words.normalise(code)
        val list = groups()
        if (list.none { it.code == norm }) return
        list.firstOrNull { it.code == norm }?.lastActive = System.currentTimeMillis()
        saveGroups(list)
        activeCode = norm
    }

    fun renameGroup(code: String, name: String, at: Long) {
        val list = groups()
        val g = list.firstOrNull { it.code == Words.normalise(code) } ?: return
        g.name = Names.clean(name, Names.MAX_GROUP)
        g.nameAt = at
        saveGroups(list)
    }

    /** Forget one group: its saved chat, read marks, mutes and files go with it. */
    fun removeGroup(code: String) {
        val norm = Words.normalise(code)
        val fp = Crypto.fingerprint(Crypto.groupKey(norm))
        val list = groups().filter { it.code != norm }
        saveGroups(list)
        AtomicFile(stateFile(fp)).delete()
        prefs.edit().remove("read-$fp").remove("mute-$fp").remove("unread-$fp").apply()
        synchronized(mutes) { mutes.remove(fp) }
        if (activeCode == norm) activeCode = list.maxByOrNull { it.lastActive }?.code
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

    /**
     * AtomicFile keeps a backup until a write is fully synced, so a power cut mid-save leaves the
     * previous state, never an empty file. A file that won't parse is moved aside, not silently
     * overwritten by the next save — 48 h of a group's history is worth keeping for a second look.
     */
    fun loadState(fp: String): JSONObject? {
        val f = stateFile(fp)
        val af = AtomicFile(f)
        val legacyTmp = File(f.parentFile, f.name + ".tmp")
        val raw = try {
            if (f.exists() || File(f.path + ".bak").exists()) String(af.readFully(), Charsets.UTF_8)
            else if (legacyTmp.exists()) legacyTmp.readText()   // 2.1 died between delete and rename
            else return null
        } catch (e: Exception) { return null }
        return try { JSONObject(raw) } catch (e: Exception) {
            try { f.renameTo(File(f.parentFile, f.name + ".corrupt-" + System.currentTimeMillis())) } catch (ex: Exception) { }
            null
        }
    }

    fun saveState(fp: String, j: JSONObject) {
        val af = AtomicFile(stateFile(fp))
        var out: java.io.FileOutputStream? = null
        try {
            out = af.startWrite()
            out.write(j.toString().toByteArray(Charsets.UTF_8))
            af.finishWrite(out)
            File(context.filesDir, "mesh-state-$fp.json.tmp").delete()
        } catch (e: Exception) {
            out?.let { af.failWrite(it) }
        }
    }

    // ------------------------------------------------------------------ migration from the single-group 1.x layout

    private fun migrateSingleGroup() {
        val legacyCode = prefs.getString("groupCode", null) ?: return
        if (prefs.getString("groups", null) == null) {
            val now = System.currentTimeMillis()
            val g = SavedGroup(Words.normalise(legacyCode), prefs.getString("groupName", "") ?: "", now, now)
            saveGroups(listOf(g))
            prefs.edit().putString("activeCode", g.code).apply()
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
}
