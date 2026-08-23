package app.hopline.data

import android.content.Context
import app.hopline.core.Crypto
import app.hopline.mesh.Group
import app.hopline.mesh.Identity
import org.json.JSONObject
import java.io.File

/** Tiny persistence: who I am, which group I'm in, and the router's memory (messages, people, backlog). */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("hopline", Context.MODE_PRIVATE)
    private val stateFile = File(context.filesDir, "mesh-state.json")

    val nodeId: String
        get() = prefs.getString("nodeId", null) ?: Crypto.randomId(8).also { prefs.edit().putString("nodeId", it).apply() }

    var name: String
        get() = prefs.getString("name", "") ?: ""
        set(v) = prefs.edit().putString("name", v.trim()).apply()

    var permissionsDone: Boolean
        get() = prefs.getBoolean("permissionsDone", false)
        set(v) = prefs.edit().putBoolean("permissionsDone", v).apply()

    fun identity(): Identity = Identity(nodeId, name)

    fun group(): Group? {
        val code = prefs.getString("groupCode", null) ?: return null
        return Group(code, prefs.getString("groupName", "") ?: "")
    }

    fun setGroup(code: String, name: String) {
        prefs.edit().putString("groupCode", app.hopline.core.Words.normalise(code)).putString("groupName", name).apply()
    }

    fun clearGroup() {
        prefs.edit().remove("groupCode").remove("groupName").apply()
        stateFile.delete()
    }

    fun loadState(): JSONObject? = try {
        if (stateFile.exists()) JSONObject(stateFile.readText()) else null
    } catch (e: Exception) { null }

    fun saveState(j: JSONObject) {
        try {
            val tmp = File(stateFile.parentFile, stateFile.name + ".tmp")
            tmp.writeText(j.toString())
            if (!tmp.renameTo(stateFile)) { stateFile.delete(); tmp.renameTo(stateFile) }
        } catch (e: Exception) { /* best effort */ }
    }
}
