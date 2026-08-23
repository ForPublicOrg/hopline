package app.hopline.ui

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import app.hopline.R
import app.hopline.databinding.ActivityChatBinding
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.service.Core

/** A private chat with one person. Same rules as the group: held until their phone has it, then ✓✓. */
class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private lateinit var peer: String
    private var adapter: MessageAdapter? = null
    private var lastCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        peer = intent.getStringExtra("peer") ?: run { finish(); return }
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }
        b.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.send.setOnClickListener {
            val r = Core.router ?: return@setOnClickListener
            val text = b.input.text.toString().trim(); if (text.isEmpty()) return@setOnClickListener
            r.sendDm(peer, text); b.input.setText(""); refresh()
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); Core.openChat = peer; adapter = null; refresh() }
    override fun onPause() { super.onPause(); Core.openChat = null }

    private fun refresh() {
        val r = Core.router ?: return
        if (adapter == null) {
            adapter = MessageAdapter(r, showNames = false) { m ->
                if (m.from == r.me.id) AlertDialog.Builder(this).setMessage(Ui.statusDetail(r, m)).setPositiveButton(R.string.ok, null).show()
            }
            b.list.adapter = adapter
        }
        val p = r.people[peer]
        b.toolbar.title = p?.name?.ifEmpty { "Someone" } ?: "Someone"
        b.toolbar.subtitle = if (p != null) Ui.personStatus(r, p) else ""
        val shown = r.messages.filter { it.kind == Envelope.DM && ((it.from == peer && it.to == r.me.id) || (it.from == r.me.id && it.to == peer)) }
        adapter!!.submit(shown)
        if (shown.size != lastCount) { lastCount = shown.size; b.list.post { b.list.scrollToPosition(maxOf(0, (b.list.adapter?.itemCount ?: 1) - 1)) } }
        val away = p != null && !r.isInRange(p)
        b.hint.visibility = if (away) View.VISIBLE else View.GONE
        b.hint.text = "${p?.name ?: "They"} is out of range. Your message will be held and delivered when any phone reaches them."
    }
}
