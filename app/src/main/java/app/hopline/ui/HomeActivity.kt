package app.hopline.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.data.SavedGroup
import app.hopline.databinding.ActivityHomeBinding
import app.hopline.databinding.ItemChatBinding
import app.hopline.databinding.ItemInternetRowBinding
import app.hopline.databinding.ItemInviteBinding
import app.hopline.databinding.ItemNoteBinding
import app.hopline.databinding.ItemSectionBinding
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Notifications

/**
 * Home: every conversation on this phone. The active group and its private chats live at the top;
 * other saved groups sleep below, one tap from waking. This is the front door of the app.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var b: ActivityHomeBinding
    private val adapter = HomeAdapter(
        onChat = { peer -> startActivity(Intent(this, ChatActivity::class.java).apply { peer?.let { putExtra("peer", it) } }) },
        onOtherGroup = { g -> confirmSwitch(g) },
        onInternet = { startActivity(Intent(this, InternetActivity::class.java)) },
        onInvite = { startActivity(Intent(this, CodeActivity::class.java)) },
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnPeople.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        b.btnSettings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.fab.setOnClickListener { startActivity(Intent(this, GroupActivity::class.java).putExtra("add", true)) }
        b.warn.setOnClickListener {
            when {
                !Core.bluetoothOn() -> startActivity(Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS))
                !Core.wifiOn() -> startActivity(Intent(android.provider.Settings.ACTION_WIFI_SETTINGS))
                else -> startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.parse("package:$packageName")))
            }
        }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        Core.appVisible = true
        if (Core.store.group() == null || !app.hopline.service.Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.ensureRunning()
        refresh()
    }

    override fun onPause() { super.onPause(); Core.appVisible = false }

    private fun refresh() {
        val r = Core.router ?: return

        // Status pill: coloured dot + the plain-English line.
        val line = Core.statusLine()
        val connected = r.peopleInRange() > 0
        val s = SpannableString("● $line")
        s.setSpan(ForegroundColorSpan(getColor(if (connected) R.color.online else R.color.offline)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.status.text = s

        val warn = when {
            !Core.bluetoothOn() -> getString(R.string.bt_off)
            !Core.wifiOn() -> getString(R.string.wifi_off)
            Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE

        val rows = ArrayList<HomeAdapter.Row>()
        rows.add(HomeAdapter.Row.Internet(internetStatus(r)))

        // One pass over the message list covers every badge on this screen.
        val unread = Core.unreadCounts()

        // The group chat itself.
        val active = Core.store.activeGroup()
        val groupLast = r.messages.lastOrNull { it.isGroup }
        rows.add(HomeAdapter.Row.Chat(
            key = null,
            name = r.group.name.ifEmpty { "Your group" },
            preview = groupLast?.let { previewOf(r, it, withName = true) } ?: getString(R.string.everyone, r.group.name.ifEmpty { "your group" }),
            ts = groupLast?.ts ?: 0,
            unread = unread[Core.GROUP] ?: 0,
            avatarColor = MessageAdapter.avatarColor(r.group.fingerprint),
            live = connected,
        ))

        // Private chats, newest first.
        val partners = LinkedHashMap<String, Message>()
        for (m in r.messages) {
            if (m.isGroup) continue
            val other = if (m.from == r.me.id) m.to ?: continue else m.from
            val prev = partners[other]
            if (prev == null || m.ts > prev.ts) partners[other] = m
        }
        val threads = partners.entries.sortedByDescending { it.value.ts }
        if (threads.isNotEmpty()) rows.add(HomeAdapter.Row.Section(getString(R.string.direct_messages)))
        for ((id, last) in threads) {
            val p = r.people[id]
            rows.add(HomeAdapter.Row.Chat(
                key = id,
                name = p?.name?.ifEmpty { "Someone" } ?: "Someone",
                preview = previewOf(r, last, withName = false),
                ts = last.ts,
                unread = unread[id] ?: 0,
                avatarColor = MessageAdapter.avatarColor(id),
                live = p != null && r.isInRange(p),
            ))
        }

        // Groups the radio isn't serving right now.
        val others = Core.store.groups().filter { it.code != active?.code }.sortedByDescending { it.lastActive }
        if (others.isNotEmpty()) {
            rows.add(HomeAdapter.Row.Section(getString(R.string.other_groups)))
            for (g in others) rows.add(HomeAdapter.Row.OtherGroup(g))
            rows.add(HomeAdapter.Row.Note(getString(R.string.one_group_note)))
        }

        // A brand-new group gets the invite card right in the list — chats stay reachable above it.
        if (r.messages.isEmpty() && r.people.isEmpty()) rows.add(HomeAdapter.Row.Invite)

        adapter.submit(rows)
    }

    private fun previewOf(r: Router, m: Message, withName: Boolean): String {
        val body = Notifications.preview(this, m)
        return when {
            m.kind == Message.SYSTEM -> body
            m.from == r.me.id -> "You: $body"
            withName -> "${m.fromName.ifEmpty { "Someone" }}: $body"
            else -> body
        }
    }

    private fun internetStatus(r: Router): String {
        val helpers = r.helpers()
        return when {
            helpers.isEmpty() -> getString(R.string.internet_now_none)
            helpers[0].id == r.me.id -> getString(R.string.internet_now_you)
            else -> getString(R.string.internet_now_other, helpers[0].name)
        }
    }

    private fun confirmSwitch(g: SavedGroup) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.switch_group_title, g.name.ifEmpty { g.code }))
            .setMessage(R.string.switch_group_body)
            .setPositiveButton(R.string.switch_btn) { _, _ -> Core.switchGroup(g.code) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ list adapter

    class HomeAdapter(
        private val onChat: (String?) -> Unit,
        private val onOtherGroup: (SavedGroup) -> Unit,
        private val onInternet: () -> Unit,
        private val onInvite: () -> Unit,
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        sealed class Row {
            class Internet(val status: String) : Row()
            class Section(val label: String) : Row()
            class Note(val text: String) : Row()
            class Chat(val key: String?, val name: String, val preview: String, val ts: Long,
                       val unread: Int, val avatarColor: Int, val live: Boolean) : Row()
            class OtherGroup(val g: SavedGroup) : Row()
            object Invite : Row()
        }

        private var rows: List<Row> = emptyList()
        fun submit(list: List<Row>) { rows = list; notifyDataSetChanged() }

        override fun getItemCount() = rows.size
        override fun getItemViewType(i: Int) = when (rows[i]) {
            is Row.Internet -> 0; is Row.Section -> 1; is Row.Note -> 2; is Row.Chat -> 3; is Row.OtherGroup -> 4
            is Row.Invite -> 5
        }

        class BindVH(val binding: androidx.viewbinding.ViewBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return BindVH(when (viewType) {
                0 -> ItemInternetRowBinding.inflate(inf, parent, false)
                1 -> ItemSectionBinding.inflate(inf, parent, false)
                2 -> ItemNoteBinding.inflate(inf, parent, false)
                5 -> ItemInviteBinding.inflate(inf, parent, false)
                else -> ItemChatBinding.inflate(inf, parent, false)
            })
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            h as BindVH
            when (val row = rows[i]) {
                is Row.Internet -> {
                    val ib = h.binding as ItemInternetRowBinding
                    ib.internetStatus.text = row.status
                    ib.root.setOnClickListener { onInternet() }
                }
                is Row.Section -> (h.binding as ItemSectionBinding).label.text = row.label
                is Row.Note -> (h.binding as ItemNoteBinding).note.text = row.text
                is Row.Chat -> {
                    val cb = h.binding as ItemChatBinding
                    cb.name.text = row.name
                    cb.preview.text = row.preview
                    cb.time.text = Ui.listTime(row.ts)
                    cb.avatar.text = row.name.take(1).uppercase().ifEmpty { "?" }
                    cb.avatar.background.mutate().setTint(row.avatarColor)
                    cb.avatar.setTextColor(Color.WHITE)
                    cb.avatar.alpha = 1f
                    cb.dot.visibility = if (row.live) View.VISIBLE else View.GONE
                    cb.unread.visibility = if (row.unread > 0) View.VISIBLE else View.GONE
                    cb.unread.text = if (row.unread > 99) "99+" else row.unread.toString()
                    cb.root.setOnClickListener { onChat(row.key) }
                }
                is Row.Invite -> (h.binding as ItemInviteBinding).inviteBtn.setOnClickListener { onInvite() }
                is Row.OtherGroup -> {
                    val cb = h.binding as ItemChatBinding
                    val ctx = cb.root.context
                    val name = row.g.name.ifEmpty { row.g.code.replace('-', ' ') }
                    cb.name.text = name
                    cb.preview.text = ctx.getString(R.string.paused_tap_to_switch)
                    cb.time.text = ""
                    cb.avatar.text = name.take(1).uppercase()
                    cb.avatar.background.mutate().setTint(ctx.getColor(R.color.surface_variant))
                    cb.avatar.setTextColor(ctx.getColor(R.color.text_muted))
                    cb.avatar.alpha = 1f
                    cb.dot.visibility = View.GONE
                    cb.unread.visibility = View.GONE
                    cb.root.setOnClickListener { onOtherGroup(row.g) }
                }
            }
        }
    }
}
