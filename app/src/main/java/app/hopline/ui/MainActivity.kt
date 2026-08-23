package app.hopline.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import app.hopline.R
import app.hopline.databinding.ActivityMainBinding
import app.hopline.databinding.ViewErrandCardBinding
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Core

/** The group chat. This is where people live; everything else is one tap away in the menu. */
class MainActivity : AppCompatActivity() {
    private lateinit var b: ActivityMainBinding
    private var adapter: MessageAdapter? = null
    private var lastCount = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivityMainBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()

        b.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.menu_people -> startActivity(Intent(this, PeopleActivity::class.java))
                R.id.menu_outside -> startActivity(Intent(this, OutsideActivity::class.java))
                R.id.menu_code -> startActivity(Intent(this, CodeActivity::class.java))
                R.id.menu_leave -> confirmLeave()
            }
            true
        }
        b.toolbar.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        b.send.setOnClickListener { send() }
        b.warn.setOnClickListener {
            when {
                !Core.bluetoothOn() -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                !Core.wifiOn() -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                else -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        Core.openChat = Core.GROUP
        adapter = null   // router may have been rebuilt (e.g. after leaving/joining)
        refresh()
    }

    override fun onPause() { super.onPause(); Core.openChat = null }

    private fun send() {
        val r = Core.router ?: return
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        r.sendChat(text)
        b.input.setText("")
        refresh()
    }

    private fun refresh() {
        val r = Core.router ?: return
        if (adapter == null) {
            adapter = MessageAdapter(r, showNames = true) { m -> showStatus(r, m) }
            b.list.adapter = adapter
        }
        b.toolbar.title = r.group.name.ifEmpty { "Your group" }
        b.toolbar.subtitle = Core.statusLine()

        val warn = when {
            !Core.bluetoothOn() -> "Bluetooth is off — tap to turn it on"
            !Core.wifiOn() -> "WiFi is off — tap to turn it on (it doesn't need a network)"
            Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE

        val shown = r.messages.filter { it.kind != Envelope.DM }
        adapter!!.submit(shown)
        if (shown.size != lastCount) { lastCount = shown.size; b.list.post { b.list.scrollToPosition(maxOf(0, (b.list.adapter?.itemCount ?: 1) - 1)) } }

        val alone = r.authedLinks().isEmpty() && r.peopleInRange() == 0
        b.hint.visibility = if (alone && warn.isEmpty()) View.VISIBLE else View.GONE
        b.hint.text = if (r.people.isEmpty()) "Nobody in range yet. Ask a friend to open Hopline and join with your code — phones find each other on their own." else getString(R.string.alone_hint)

        renderErrandCards(r)
    }

    /** Plain-English answer to "did it get there?", on tap. */
    private fun showStatus(r: Router, m: Message) {
        if (m.from != r.me.id || m.kind == Message.SYSTEM) return
        val text = Ui.statusDetail(r, m)
        AlertDialog.Builder(this).setMessage(text).setPositiveButton(R.string.ok, null).show()
    }

    /** Cards shown on the phone that has signal when somebody asks it to send a message out. */
    private fun renderErrandCards(r: Router) {
        b.errandCards.removeAllViews()
        val mine = r.errands.values.filter { it.type == Errand.SEND && it.helper == r.me.id && it.status == Errand.ASKED }
        for (e in mine) {
            val card = ViewErrandCardBinding.inflate(layoutInflater, b.errandCards, false)
            val to = e.args.optString("to"); val text = e.args.optString("text")
            card.title.text = getString(R.string.errand_send_title, e.fromName)
            card.body.text = "To: $to\n“$text”"
            card.open.setOnClickListener { openMessenger(to, text) }
            card.sent.setOnClickListener { r.completeErrand(e.id, true, "Message sent out by ${r.me.name}", "To $to: “$text”") }
            card.failed.setOnClickListener { r.completeErrand(e.id, false, "Couldn't send ${e.fromName}'s message", "${r.me.name}'s phone could not send it to $to.") }
            b.errandCards.addView(card.root)
        }
    }

    private fun openMessenger(to: String, text: String) {
        val intent = if (to.contains("@")) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Message from Hopline")
                     else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", text)
        try { startActivity(intent) } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("No messaging app found for $to").setPositiveButton(R.string.ok, null).show()
        }
    }

    private fun confirmLeave() {
        AlertDialog.Builder(this).setTitle(R.string.leave_group).setMessage(R.string.leave_confirm)
            .setPositiveButton(R.string.leave) { _, _ ->
                Core.leaveGroup()
                startActivity(Intent(this, GroupActivity::class.java)); finish()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
