package app.hopline.ui

import android.content.Context
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import androidx.appcompat.app.AlertDialog
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Plain-English words for the things the mesh knows. No hops, nodes, relays or envelopes on screen. */
object Ui {
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dayFmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())

    fun time(ts: Long): String {
        val age = System.currentTimeMillis() - ts
        return if (age < 20 * 3600_000L) timeFmt.format(Date(ts)) else dayFmt.format(Date(ts))
    }

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

    fun personStatus(r: Router, p: Person): String {
        val inRange = r.isInRange(p)
        val base = if (inRange) {
            if (p.direct) "In range · right next to you" else "In range · ${p.hops} phones away"
        } else "Out of range · last heard ${ago(p.lastSeen)}"
        val bat = if (p.battery in 0..20 && inRange) " · battery ${p.battery}%" else ""
        return base + bat
    }

    /** Status line under my own message. */
    fun myStatus(r: Router, m: Message): String {
        if (m.kind == Envelope.DM) return when (m.status) {
            Message.DELIVERED -> "Delivered ✓✓"
            Message.SENT -> "On its way ✓"
            else -> "Waiting for a phone in range…"
        }
        val total = r.people.size
        return when {
            m.reached.isNotEmpty() -> "Reached ${m.reached.size} of $total ✓"
            m.status == Message.SENT -> "Sent ✓"
            else -> "Waiting for a phone in range…"
        }
    }

    fun hideKeyboard(ctx: Context, v: android.view.View) {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    /** One or two big text fields in a dialog. */
    fun ask(ctx: Context, title: String, fields: List<Pair<String, Int>>, okText: String, onOk: (List<String>) -> Unit) {
        val layout = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 24, 48, 0) }
        val edits = fields.map { (hint, type) ->
            EditText(ctx).apply {
                this.hint = hint; inputType = type; textSize = 18f
                if (type and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0) { minLines = 3; gravity = android.view.Gravity.TOP }
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
