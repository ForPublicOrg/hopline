package app.hopline.ui

import android.content.Context
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import app.hopline.R
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Locations
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Plain-English words for the things the mesh knows. No hops, nodes, relays or envelopes on screen. */
object Ui {

    private val res get() = Core.app.resources

    fun ago(ts: Long): String {
        if (ts <= 0) return res.getString(R.string.chat_ago_never)
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 60 -> res.getString(R.string.chat_ago_now)
            s < 3600 -> (s / 60).toInt().let { res.getQuantityString(R.plurals.chat_ago_min, it, it) }
            s < 86400 -> (s / 3600).toInt().let { res.getQuantityString(R.plurals.chat_ago_hours, it, it) }
            else -> (s / 86400).toInt().let { res.getQuantityString(R.plurals.chat_ago_days, it, it) }
        }
    }

    // Made per call, not held: the language can change while the app runs.
    private val timeFmt get() = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt get() = SimpleDateFormat("EEE", Locale.getDefault())
    private val dateFmt get() = SimpleDateFormat("d MMM", Locale.getDefault())
    private val yearFmt get() = SimpleDateFormat("d MMM yyyy", Locale.getDefault())

    /**
     * Chat-list style timestamp: 14:23 today, Yesterday, Mon, then 12 Aug — and 12 Aug 2025 once
     * that was in another year. A group that was left stays on Home until it is deleted, and this
     * is all its row says about when: two summers ago must not read like this summer.
     */
    fun listTime(ts: Long, nowMs: Long = System.currentTimeMillis()): String {
        if (ts <= 0) return ""
        val now = Calendar.getInstance().apply { timeInMillis = nowMs }
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (now.clone() as Calendar).also { it.add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, now) -> timeFmt.format(Date(ts))
            sameDay(then, yesterday) -> res.getString(R.string.chat_yesterday)
            nowMs - ts < 6 * 86400_000L -> dayFmt.format(Date(ts))
            then.get(Calendar.YEAR) != now.get(Calendar.YEAR) -> yearFmt.format(Date(ts))
            else -> dateFmt.format(Date(ts))
        }
    }

    /**
     * Someone's name as it is NOW. Messages carry the name their sender had when sending, but
     * people rename themselves — every screen shows the current one, like a contact name does.
     * [fallback] (usually the name baked into a message) covers people we never heard presence from.
     */
    fun nameOf(r: Router, id: String, fallback: String = ""): String = when (id) {
        r.me.id -> r.me.name.ifEmpty { res.getString(R.string.reply_you) }
        else -> r.people[id]?.name?.ifEmpty { null } ?: fallback.ifEmpty { res.getString(R.string.someone) }
    }

    /**
     * Which people a text calls out with @Name. The @ must start a word (not an email) and the
     * name must end at a word boundary — "@Samantha" is not a mention of Sam. Longer names claim
     * their characters first, so "@Ravi Kumar" never also mentions plain "Ravi". When two people
     * share a name, [chosen] (filled when someone tapped a suggestion chip) says which one.
     */
    fun mentionsIn(r: Router, text: String, chosen: Map<String, String> = emptyMap()): List<String> {
        if (!text.contains('@')) return emptyList()
        val lower = text.lowercase(Locale.ROOT)
        val sameLength = lower.length == text.length
        val claimed = ArrayList<IntRange>()
        val out = LinkedHashSet<String>()
        val byName = r.people.values.filter { it.name.isNotEmpty() }.groupBy { it.name.lowercase(Locale.ROOT) }
        for ((nameLower, persons) in byName.entries.sortedByDescending { it.key.length }) {
            val needle = "@" + (if (sameLength) nameLower else persons[0].name)
            val hay = if (sameLength) lower else text
            var i = hay.indexOf(needle)
            while (i >= 0) {
                val end = i + needle.length
                val startsWord = i == 0 || text[i - 1].isWhitespace()
                val endsWord = end >= text.length || !text[end].isLetterOrDigit()
                if (startsWord && endsWord && claimed.none { i in it || (end - 1) in it }) {
                    claimed.add(i until end)
                    val pick = chosen[nameLower]?.takeIf { id -> persons.any { it.id == id } }
                    if (pick != null) out.add(pick) else persons.forEach { out.add(it.id) }
                }
                i = hay.indexOf(needle, end)
            }
            if (out.size >= Message.MAX_MENTIONS) break
        }
        return out.take(Message.MAX_MENTIONS)
    }

    /**
     * A name that tells two people apart: when someone else in the group goes by the same name,
     * add a short, stable tag from their phone's id ("Sam · 3f2a") so nobody can hide behind a
     * namesake.
     */
    fun uniqueName(r: Router, id: String, fallback: String = ""): String {
        val name = nameOf(r, id, fallback)
        val lower = name.lowercase(Locale.ROOT)
        val clash = (r.activePeopleList().filter { it.id != id }.any { it.name.lowercase(Locale.ROOT) == lower }) ||
            (id != r.me.id && r.me.name.lowercase(Locale.ROOT) == lower)
        return if (clash) "$name · ${id.takeLast(4)}" else name
    }

    /** First letter for an avatar circle — a whole grapheme, so an emoji or accented name stays intact. */
    fun initial(name: String): String {
        val t = name.trim()
        if (t.isEmpty()) return "?"
        val it = java.text.BreakIterator.getCharacterInstance()
        it.setText(t)
        val end = it.next().takeIf { e -> e != java.text.BreakIterator.DONE } ?: t.length
        return t.substring(0, end).uppercase(Locale.getDefault())
    }

    fun personStatus(r: Router, p: Person): String {
        val inRange = r.isInRange(p)
        val base = when {
            !inRange -> res.getString(R.string.chat_status_out_of_range, ago(p.lastSeen))
            p.direct -> res.getString(R.string.chat_status_next_to_you)
            else -> res.getQuantityString(R.plurals.chat_status_phones_away, p.hops, p.hops)
        }
        val withBattery = if (p.battery in 0..20 && inRange) res.getString(R.string.chat_status_battery, base, p.battery) else base
        // While they share live location, say where that actually is from HERE.
        val live = r.liveLocOf(p)?.let { loc ->
            val fix = Locations.lastKnown(Core.app)
            val where = if (fix != null) {
                res.getString(R.string.loc_away, Loc.prettyDistance(Loc.distanceMeters(fix.latitude, fix.longitude, loc.lat, loc.lng)),
                    Loc.compass(Loc.bearingDeg(fix.latitude, fix.longitude, loc.lat, loc.lng)))
            } else loc.pretty()
            "\n" + res.getString(R.string.chat_status_live, where)
        } ?: ""
        return withBattery + live
    }

    /**
     * True once a message of mine has stopped trying: nothing carries it after its 48 h, so a ◷
     * that never left, or a private message whose receiver never confirmed, will not get there on
     * its own. (A private message gets an hour's grace — a late confirmation may still be on its way.)
     * A ◷ this phone no longer holds an envelope for has stopped trying whatever its age: it was
     * still waiting when I left the group, and leaving let it go. It reads "Not sent" at once — in
     * the kept chat and after a rejoin — and only "Send again" sends it.
     */
    fun gaveUp(r: Router, m: Message, now: Long = System.currentTimeMillis()): Boolean {
        if (m.from != r.me.id || !m.isPersonal) return false
        val age = now - m.ts
        return when {
            m.status == Message.QUEUED -> age > Router.CARRY_MS || !r.carries(m.id)
            m.to != null && m.status != Message.DELIVERED -> age > Router.CARRY_MS + 3_600_000L
            else -> false
        }
    }

    /**
     * A ◷ of mine that stopped trying because I left the group, not because its 48 h ran out: it
     * is younger than that, yet this phone no longer holds its envelope — and leaving is what lets
     * one go that early. "No phone came in range within 48 hours" would be untrue of it. (Past
     * 48 h the two can't be told apart, and that sentence is then true of both.)
     */
    fun unsentByLeaving(r: Router, m: Message, now: Long = System.currentTimeMillis()): Boolean =
        m.from == r.me.id && m.isPersonal && m.status == Message.QUEUED && !r.carries(m.id) && now - m.ts <= Router.CARRY_MS

    /**
     * Was a message of mine past its time when this phone left the group at [leftAt]? Nothing
     * carries a message beyond its 48 h (a private one gets the same hour's grace as in [gaveUp]),
     * so by then everything that could be learned about it had been: who got it, or that the
     * person it was for never confirmed. Leaving changed nothing about such a message, and its
     * tick and details must not say it did.
     */
    fun settledBeforeLeaving(m: Message, leftAt: Long): Boolean =
        leftAt - m.ts > Router.CARRY_MS + (if (m.to != null) 3_600_000L else 0L)

    /**
     * The full truthful story of one of my messages, for the delivery-details sheet.
     *
     * [leftAt] above 0: the message is in the kept chat of a group this phone left then. Nothing
     * is on its way any more and nothing can be sent again from there, so it says what was known
     * at the leave and promises nothing: no "still waiting", no "Send again". And it blames the
     * leaving only for what the leaving cut short — a message whose 48 h were over long before
     * ([settledBeforeLeaving]) reads as it did then.
     */
    fun statusDetail(ctx: Context, r: Router, m: Message, leftAt: Long = 0): String {
        val now = System.currentTimeMillis()
        if (leftAt > 0) {
            if (m.status == Message.QUEUED) return ctx.getString(R.string.chat_status_left_unsent)
            if (m.to != null && m.status == Message.DELIVERED) return ctx.getString(R.string.chat_status_delivered, nameOf(r, m.to))
            val settled = settledBeforeLeaving(m, leftAt)
            if (settled && m.to != null) return ctx.getString(R.string.chat_status_left_dm_not_delivered, nameOf(r, m.to))
            val got = m.reached.map { nameOf(r, it, "") }.sorted()
            return ctx.getString(if (settled) R.string.chat_status_sent else R.string.chat_status_left_sent) +
                if (got.isEmpty()) "" else "\n\n" + ctx.getString(R.string.chat_status_got, got.joinToString(", "))
        }
        if (gaveUp(r, m, now)) {
            return when {
                m.status != Message.QUEUED -> ctx.getString(R.string.chat_status_dm_not_delivered, nameOf(r, m.to ?: ""))
                // Back in the group after leaving it: the wait wasn't for a phone in range.
                unsentByLeaving(r, m, now) -> ctx.getString(R.string.chat_status_left_unsent)
                else -> ctx.getString(R.string.chat_status_not_sent)
            }
        }
        if (m.status == Message.QUEUED) return ctx.getString(R.string.status_queued)
        if (m.to != null) {
            val name = nameOf(r, m.to)
            return if (m.status == Message.DELIVERED) ctx.getString(R.string.chat_status_delivered, name)
            else ctx.getString(R.string.chat_status_on_way, name)
        }
        // The group as it is now (heard from within 48 h) — plus anyone who confirmed, so the
        // count can never read "3 of 2".
        val active = r.activePeopleList()
        val totalIds = active.mapTo(LinkedHashSet()) { it.id }.apply { addAll(m.reached) }
        val total = totalIds.size
        if (total == 0) return ctx.getString(R.string.chat_status_sent)
        if (active.size >= Router.RECEIPT_GROUP_LIMIT) return ctx.getString(R.string.chat_status_big_group)
        val got = m.reached.map { nameOf(r, it, "") }.sorted()
        val waiting = active.filter { it.id !in m.reached }.mapNotNull { it.name.ifEmpty { null } }.sorted()
        return buildString {
            append(ctx.resources.getQuantityString(R.plurals.chat_status_reached, total, m.reached.size, total))
            if (got.isNotEmpty()) append("\n\n").append(ctx.getString(R.string.chat_status_got, got.joinToString(", ")))
            if (waiting.isNotEmpty()) {
                // Past its 48 h nothing carries it any more: say it didn't get there, not "waiting".
                val missed = now - m.ts > Router.CARRY_MS
                append("\n").append(ctx.getString(if (missed) R.string.chat_status_missed else R.string.chat_status_waiting, waiting.joinToString(", ")))
            }
        }
    }

    fun hideKeyboard(ctx: Context, v: android.view.View) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** A dialog from [askFields], with its text fields (a screen that survives rotation reads them back). */
    class Asked(val dialog: AlertDialog, val fields: List<EditText>)

    /**
     * One or two big text fields in a dialog, with an optional explainer line above them.
     * [maxLength] caps each field as you type. [validate] may return an error to show under the
     * first field — then the dialog stays open, so a too-short name is never silently dropped.
     * [onCancel] runs when the person backs out (Cancel, Back, or a tap outside) — not when the
     * screen itself goes away.
     */
    fun ask(ctx: Context, title: String, fields: List<Pair<String, Int>>, okText: String, prefill: List<String> = emptyList(),
            message: String? = null, maxLength: List<Int> = emptyList(), validate: ((List<String>) -> String?)? = null,
            onCancel: (() -> Unit)? = null, onOk: (List<String>) -> Unit): AlertDialog =
        askFields(ctx, title, fields, okText, prefill, message, maxLength, validate, onCancel, onOk).dialog

    fun askFields(ctx: Context, title: String, fields: List<Pair<String, Int>>, okText: String, prefill: List<String> = emptyList(),
                  message: String? = null, maxLength: List<Int> = emptyList(), validate: ((List<String>) -> String?)? = null,
                  onCancel: (() -> Unit)? = null, onOk: (List<String>) -> Unit): Asked {
        val density = ctx.resources.displayMetrics.density
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding((24 * density).toInt(), (12 * density).toInt(), (24 * density).toInt(), 0) }
        message?.let {
            layout.addView(android.widget.TextView(ctx).apply { text = it; textSize = 14f },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val edits = fields.mapIndexed { i, (hint, type) ->
            EditText(ctx).apply {
                this.hint = hint; inputType = type; textSize = 18f
                if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) { minLines = 3; gravity = android.view.Gravity.TOP }
                maxLength.getOrNull(i)?.let { filters = arrayOf(android.text.InputFilter.LengthFilter(it)) }
                prefill.getOrNull(i)?.let { setText(it); setSelection(text.length) }
                layout.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (8 * density).toInt() })
            }
        }
        val dialog = AlertDialog.Builder(ctx).setTitle(title).setView(layout)
            .setPositiveButton(okText, null)
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val values = edits.map { it.text.toString().trim() }
                val problem = validate?.invoke(values)
                if (problem != null) { edits.firstOrNull()?.error = problem; return@setOnClickListener }
                dialog.dismiss()
                onOk(values)
            }
        }
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        edits.firstOrNull()?.requestFocus()
        return Asked(dialog, edits)
    }
}
