package app.hopline.ui

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.databinding.ActivityInternetBinding
import app.hopline.mesh.Errand
import app.hopline.service.Core
import app.hopline.service.Errands
import org.json.JSONObject

/**
 * Shared internet, explained like it is: when anyone in the group gets signal, everyone can use
 * a little of it. Requests hop to that phone; answers hop back into the group chat.
 */
class InternetActivity : AppCompatActivity() {
    private lateinit var b: ActivityInternetBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityInternetBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.sendout.setOnClickListener {
            Ui.ask(this, getString(R.string.sendout_btn),
                listOf(getString(R.string.phone_or_email_hint) to InputType.TYPE_CLASS_TEXT,
                       getString(R.string.message_out_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
                getString(R.string.ask), message = getString(R.string.sendout_disclosure)) { v ->
                if (v[0].isNotEmpty() && v[1].isNotEmpty()) request(Errand.SEND, JSONObject().put("to", v[0]).put("text", "${v[1]} — ${Core.store.name} (sent via a friend's phone)"))
            }
        }
        b.readlink.setOnClickListener {
            Ui.ask(this, getString(R.string.readlink_btn), listOf(getString(R.string.link_hint) to InputType.TYPE_TEXT_VARIATION_URI), getString(R.string.ask)) { v ->
                if (v[0].isNotEmpty()) request(Errand.READ, JSONObject().put("url", v[0]))
            }
        }
        b.share.setOnCheckedChangeListener { _, on ->
            Core.router?.let { if (it.shareInternet != on) { it.shareInternet = on; it.sendPresence() } }
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun request(type: String, args: JSONObject) {
        val r = Core.router ?: return
        val e = r.requestErrand(type, args)
        if (e.status == Errand.WAITING) Toast.makeText(this, R.string.no_helper, Toast.LENGTH_LONG).show()
        refresh()
    }

    private fun refresh() {
        val r = Core.router ?: return
        val helpers = r.helpers()
        b.helper.text = when {
            helpers.isEmpty() -> getString(R.string.internet_now_none)
            helpers[0].id == r.me.id -> getString(R.string.internet_now_you)
            else -> getString(R.string.internet_now_other, helpers[0].name)
        }
        b.share.isChecked = r.shareInternet

        b.errands.removeAllViews()
        val mine = r.errands.values.filter { it.from == r.me.id }.sortedByDescending { it.ts }
        b.none.visibility = if (mine.isEmpty()) View.VISIBLE else View.GONE
        for (e in mine) {
            val tv = TextView(this).apply {
                setPadding(40, 30, 40, 30); textSize = 15f; setBackgroundResource(R.drawable.bg_card)
                setTextColor(getColor(R.color.text))
                val status = when (e.status) {
                    Errand.DONE -> "✓ Done — see the group chat"
                    Errand.ASKED -> "Asked ${e.helperName.ifEmpty { "a phone with internet" }} · waiting for the answer to hop back"
                    else -> "Waiting for someone with internet"
                }
                text = "${Errands.titleFor(e)}\n$status"
                if (e.status != Errand.DONE) setOnClickListener {
                    r.retryErrand(e.id); Toast.makeText(context, R.string.asked_again, Toast.LENGTH_SHORT).show()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(android.widget.LinearLayout.LayoutParams.MATCH_PARENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = 16 }
            b.errands.addView(tv, lp)
        }
    }
}
