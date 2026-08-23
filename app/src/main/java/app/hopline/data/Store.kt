package app.hopline.data

import android.content.Context
import app.hopline.core.Crypto
import app.hopline.core.Words
import app.hopline.mesh.Group
import app.hopline.mesh.Identity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** One saved group on this phone. The radio serves one at a time; the rest keep their history. */
class SavedGroup(val code: String, var name: String, val joinedAt: Long, var lastActive: Long) {
    val fingerprint: String get() = Crypto.fingerprint(Crypto.groupKey(code))
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code); put("name", name); put("joinedAt", joinedAt); put("lastActive", lastActive)
    }
    companion object {
        fun fromJson(j: JSONObject) = SavedGroup(
            j.getString("code"), j.optString("name", ""), j.optLong("joinedAt", 0), j.optLong("lastActive", 0))
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
        get() = prefs.getString("nodeId", null) ?: Crypto.randomId(8).also { prefs.edit().putString("nodeId", it).apply() }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", v.trim()).apply()

    var permissionsDone: Boolean
        get() = prefs.getBoolean("permissionsDone", false)
        set(v) = prefs.edit().putBoolean("permissionsDone", v).apply()

    /** A hopline://join link tapped before onboarding finished — consumed right after it. */
    var pendingJoin: String?
        get() = prefs.getString("pendingJoin", null)
        set(v) = prefs.edit().apply { if (v == null) remove("pendingJoin") else putString("pendingJoin", v) }.apply()

    fun identity(): Identity = Identity(nodeId, name)

    // ------------------------------------------------------------------ groups

    fun groups(): List<SavedGroup> {
        val raw = prefs.getString("groups", null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { SavedGroup.fromJson(a.getJSONObject(it)) }
        } catch (e: Exception) { emptyList() }
    }

    private fun saveGroups(list: List<SavedGroup>) {
        prefs.edit().putString("groups", JSONArray(list.map { it.toJson() }).toString()).apply()
    }

    var activeCode: String?
        get() = prefs.getString("activeCode", null)
        private set(v) = prefs.edit().apply { if (v == null) remove("activeCode") else putString("activeCode", v) }.apply()

    fun activeGroup(): SavedGroup? = activeCode?.let { c -> groups().firstOrNull { it.code == c } }

    /** The group the radio serves right now, as the mesh sees it. */
    fun group(): Group? = activeGroup()?.let { Group(it.code, it.name) }

    /** Add (or re-activate) a group and make it the active one. */
    fun addGroup(code: String, name: String) {
        val norm = Words.normalise(code)
        val now = System.currentTimeMillis()
        val list = groups().toMutableList()
        val existing = list.firstOrNull { it.code == norm }
        if (existing != null) {
            if (name.isNotEmpty()) existing.name = name
            existing.lastActive = now
        } else list.add(SavedGroup(norm, name, now, now))
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

    fun renameGroup(code: String, name: String) {
        val list = groups()
        list.firstOrNull { it.code == Words.normalise(code) }?.name = name
        saveGroups(list)
    }

    /** Forget one group: its saved chat, read marks and files go with it. */
    fun removeGroup(code: String) {
        val norm = Words.normalise(code)
        val fp = Crypto.fingerprint(Crypto.groupKey(norm))
        val list = groups().filter { it.code != norm }
        saveGroups(list)
        stateFile(fp).delete()
        prefs.edit().remove("read-$fp").apply()
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

    // ------------------------------------------------------------------ router state, one file per group

    private fun stateFile(fp: String): File = File(context.filesDir, "mesh-state-$fp.json")

    fun loadState(fp: String): JSONObject? = try {
        val f = stateFile(fp)
        if (f.exists()) JSONObject(f.readText()) else null
    } catch (e: Exception) { null }

    fun saveState(fp: String, j: JSONObject) {
        try {
            val f = stateFile(fp)
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        } catch (e: Exception) { /* best effort */ }
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
