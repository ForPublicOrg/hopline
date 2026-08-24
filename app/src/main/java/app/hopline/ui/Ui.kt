package app.hopline.ui

import android.content.Context
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
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

    fun ago(ts: Long): String {
        if (ts <= 0) return "never"
        val s = (System.currentTimeMillis() - ts) / 1000
        return when {
            s < 60 -> "just now"
            s < 3600 -> "${s / 60} min ago"
            s < 86400 -> "${s / 3600} h ago"
            else -> "${s / 86400} days ago"
        }
    }

    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("d MMM", Locale.getDefault())

    /** Chat-list style timestamp: 14:23 today, Yesterday, Mon, then 12 Aug. */
    fun listTime(ts: Long): String {
        if (ts <= 0) return ""
        val now = Calendar.getInstance()
        val then = Calendar.getInstance().apply { timeInMillis = ts }
        fun sameDay(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (now.clone() as Calendar).also { it.add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            sameDay(then, now) -> timeFmt.format(Date(ts))
            sameDay(then, yesterday) -> "Yesterday"
            now.timeInMillis - ts < 6 * 86400_000L -> dayFmt.format(Date(ts))
            else -> dateFmt.format(Date(ts))
        }
    }

    fun personStatus(r: Router, p: Person): String {
        val inRange = r.isInRange(p)
        val base = if (inRange) {
            val away = if (p.hops == 1) "1 phone away" else "${p.hops} phones away"
            if (p.direct) "In range · right next to you" else "In range · $away"
        } else "Out of range · last heard ${ago(p.lastSeen)}"
        val bat = if (p.battery in 0..20 && inRange) " · battery ${p.battery}%" else ""
        // While they share live location, say where that actually is from HERE.
        val live = r.liveLocOf(p)?.let { loc ->
            val fix = Locations.lastKnown(Core.app)
            val where = if (fix != null) {
                Loc.prettyDistance(Loc.distanceMeters(fix.latitude, fix.longitude, loc.lat, loc.lng)) +
                    " away · " + Loc.compass(Loc.bearingDeg(fix.latitude, fix.longitude, loc.lat, loc.lng)) + " of you"
            } else loc.pretty()
            "\n📡 Live location · $where"
        } ?: ""
        return base + bat + live
    }

    /** The full truthful story of one of my messages, for the delivery-details sheet. */
    fun statusDetail(ctx: Context, r: Router, m: Message): String {
        if (m.status == Message.QUEUED) return ctx.getString(app.hopline.R.string.status_queued)
        if (m.to != null) {
            val name = r.people[m.to]?.name?.ifEmpty { "them" } ?: "them"
            return if (m.status == Message.DELIVERED) "Delivered — $name's phone has it. ✓✓"
            else "On its way — handed to nearby phones, waiting for $name's phone to confirm."
        }
        val total = r.people.size
        if (total == 0) return "Sent."
        if (total >= Router.RECEIPT_GROUP_LIMIT) {
            return "Sent to the group. In a group this big, phones don't send individual confirmations — that would flood the radios."
        }
        val got = m.reached.mapNotNull { r.people[it]?.name?.ifEmpty { null } }.sorted()
        val waiting = r.people.values.filter { it.id !in m.reached }.mapNotNull { it.name.ifEmpty { null } }.sorted()
        return buildString {
            append("Reached ${m.reached.size} of $total phones.")
            if (got.isNotEmpty()) append("\n\nGot it: ").append(got.joinToString(", "))
            if (waiting.isNotEmpty()) append("\nStill waiting: ").append(waiting.joinToString(", "))
        }
    }

    fun hideKeyboard(ctx: Context, v: android.view.View) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** One or two big text fields in a dialog, with an optional explainer line above them. */
    fun ask(ctx: Context, title: String, fields: List<Pair<String, Int>>, okText: String, prefill: List<String> = emptyList(), message: String? = null, onOk: (List<String>) -> Unit) {
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        message?.let {
            layout.addView(android.widget.TextView(ctx).apply { text = it; textSize = 14f },
                LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        val edits = fields.mapIndexed { i, (hint, type) ->
            EditText(ctx).apply {
                this.hint = hint; inputType = type; textSize = 18f
                if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) { minLines = 3; gravity = android.view.Gravity.TOP }
                prefill.getOrNull(i)?.let { setText(it) }
                layout.addView(this, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = 16 })
            }
        }
        AlertDialog.Builder(ctx).setTitle(title).setView(layout)
            .setPositiveButton(okText) { _, _ -> onOk(edits.map { it.text.toString().trim() }) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
        edits.firstOrNull()?.requestFocus()
    }
}
