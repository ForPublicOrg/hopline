package app.hopline.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.databinding.ActivityOutsideBinding
import app.hopline.mesh.Errand
import app.hopline.service.Core
import app.hopline.service.Errands
import org.json.JSONObject

/** "The Outside World": borrow a sliver of whoever's internet to get weather, read a page, or send a message home. */
class OutsideActivity : AppCompatActivity() {
    private lateinit var b: ActivityOutsideBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityOutsideBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.weather.setOnClickListener {
            Ui.ask(this, getString(R.string.weather_btn), listOf(getString(R.string.place_hint) to InputType.TYPE_CLASS_TEXT), getString(R.string.ask)) { v ->
                if (v[0].isNotEmpty()) request(Errand.WEATHER, JSONObject().put("place", v[0]))
            }
        }
        b.sendout.setOnClickListener {
            Ui.ask(this, getString(R.string.sendout_btn),
                listOf(getString(R.string.phone_or_email_hint) to InputType.TYPE_CLASS_TEXT,
                       getString(R.string.message_out_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
                getString(R.string.ask)) { v ->
                if (v[0].isNotEmpty() && v[1].isNotEmpty()) request(Errand.SEND, JSONObject().put("to", v[0]).put("text", "${v[1]} — ${Core.store.name} (sent via a friend's phone)"))
            }
        }
        b.readlink.setOnClickListener {
            Ui.ask(this, getString(R.string.readlink_btn), listOf(getString(R.string.link_hint) to InputType.TYPE_TEXT_VARIATION_URI), getString(R.string.ask)) { v ->
                if (v[0].isNotEmpty()) request(Errand.READ, JSONObject().put("url", v[0]))
            }
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun request(type: String, args: JSONObject) {
        val r = Core.router ?: return
        val e = r.requestErrand(type, args)
        if (e.status == Errand.WAITING) android.widget.Toast.makeText(this, R.string.no_helper, android.widget.Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun refresh() {
        val r = Core.router ?: return
        val helpers = r.helpers()
        b.helper.text = when {
            helpers.isEmpty() -> "Nobody in the group has internet right now. You can still ask — it will happen as soon as someone does."
            helpers[0].id == r.me.id -> "Your phone has internet, so you can do these yourself — and the whole group can use it."
            else -> "${helpers[0].name}'s phone has internet right now. Requests go there."
        }
        b.errands.removeAllViews()
        val mine = r.errands.values.filter { it.from == r.me.id }.sortedByDescending { it.ts }
        b.none.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
        for (e in mine) {
            val tv = TextView(this).apply {
                setPadding(32, 24, 32, 24); textSize = 16f; setBackgroundResource(R.drawable.bg_card)
                val status = when (e.status) {
                    Errand.DONE -> "✓ Done — see the group chat"
                    Errand.ASKED -> "Asked ${e.helperName.ifEmpty { "a phone with internet" }} · waiting for the answer to hop back"
                    else -> "Waiting for someone with internet"
                }
                text = "${Errands.titleFor(e)}\n$status"
                if (e.status != Errand.DONE) setOnClickListener { r.retryErrand(e.id); android.widget.Toast.makeText(context, "Asked again", android.widget.Toast.LENGTH_SHORT).show() }
            }
            val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
            b.errands.addView(tv, lp)
        }
    }
}
