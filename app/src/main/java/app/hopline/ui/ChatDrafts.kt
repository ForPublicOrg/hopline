package app.hopline.ui

import android.content.Context
import app.hopline.mesh.Router
import app.hopline.service.Core
import org.json.JSONObject

/**
 * Half-typed messages, one per chat — leave a chat (or jump to another from a notification) and
 * the words, the message being replied to and the @mentions picked from chips are all still
 * there when you come back. Kept on disk, so they survive the app being killed too.
 */
object ChatDrafts {
    class Draft(val text: String, val replyId: String?, val chosen: Map<String, String>)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences("chat_drafts", Context.MODE_PRIVATE)
    private fun key(fp: String, chat: String) = "$fp|$chat"

    fun get(ctx: Context, fp: String, chat: String): Draft? = try {
        prefs(ctx).getString(key(fp, chat), null)?.let { raw ->
            val j = JSONObject(raw)
            val chosen = HashMap<String, String>()
            j.optJSONObject("mn")?.let { mn -> for (k in mn.keys()) mn.optString(k).takeIf { it.isNotEmpty() }?.let { chosen[k] = it } }
            Draft(j.optString("t").take(Router.MAX_TEXT), j.optString("re").ifEmpty { null }, chosen)
        }
    } catch (e: Exception) { null }

    fun put(ctx: Context, fp: String, chat: String, d: Draft?) {
        val p = prefs(ctx)
        val edit = p.edit()
        if (d == null || (d.text.isBlank() && d.replyId == null)) edit.remove(key(fp, chat))
        else edit.putString(key(fp, chat), JSONObject().apply {
            put("t", d.text)
            d.replyId?.let { put("re", it) }
            if (d.chosen.isNotEmpty()) put("mn", JSONObject(d.chosen))
        }.toString())
        // Only groups this phone is in have a composer; a draft for any other is a leftover.
        val live = try { Core.store.groups().map { it.fingerprint }.toSet() } catch (e: Exception) { null }
        if (live != null) for (k in p.all.keys) if (k.substringBefore('|') !in live) edit.remove(k)
        edit.apply()
    }

    /**
     * A group was left or deleted: its half-typed messages go now. A left group's chat is still
     * read, but it has no composer to bring a draft back to — and a draft kept would be waiting
     * there, weeks stale, the day the group is joined again.
     */
    fun dropGroup(ctx: Context, fp: String) {
        val p = prefs(ctx)
        val keys = p.all.keys.filter { it.substringBefore('|') == fp }
        if (keys.isEmpty()) return
        val edit = p.edit()
        for (k in keys) edit.remove(k)
        edit.apply()
    }
}
