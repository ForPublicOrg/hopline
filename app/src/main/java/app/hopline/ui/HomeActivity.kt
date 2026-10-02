package app.hopline.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.data.SavedGroup
import app.hopline.databinding.ActivityHomeBinding
import app.hopline.databinding.ItemChatBinding
import app.hopline.databinding.ItemInternetRowBinding
import app.hopline.databinding.ItemInviteBinding
import app.hopline.databinding.ItemNoteBinding
import app.hopline.databinding.ItemNotifOffBinding
import app.hopline.databinding.ItemSectionBinding
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Notifications
import app.hopline.service.Permissions

/**
 * Home: every conversation on this phone. The active group and its direct chats live at the top;
 * other saved groups sleep below, one tap from waking. This is the front door of the app.
 */
class HomeActivity : AppCompatActivity() {
    private lateinit var b: ActivityHomeBinding
    /** Per-phone screen conveniences (a dismissed hint), kept apart from the app's real data. */
    private val prefs by lazy { getSharedPreferences(UI_PREFS, MODE_PRIVATE) }

    private val adapter = HomeAdapter(object : HomeAdapter.Actions {
        override fun chat(peer: String?) { startActivity(Intent(this@HomeActivity, ChatActivity::class.java).apply { peer?.let { putExtra("peer", it) } }) }
        override fun chatMenu(row: HomeAdapter.Row.Chat, anchor: View) = showChatMenu(row, anchor)
        override fun otherGroup(code: String) { savedGroup(code)?.let { confirmSwitch(it) } }
        override fun otherGroupMenu(code: String, anchor: View) { savedGroup(code)?.let { showOtherGroupMenu(it, anchor) } }
        override fun internet() { startActivity(Intent(this@HomeActivity, InternetActivity::class.java)) }
        override fun invite() { startActivity(Intent(this@HomeActivity, CodeActivity::class.java)) }
        override fun notificationsOn() = turnOnNotifications()
        override fun notificationsDismiss() { prefs.edit().putLong(K_NOTIF_HIDDEN, System.currentTimeMillis()).apply(); refresh() }
    })

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notif_still_off, Toast.LENGTH_LONG).show()
        refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        Asks.listen(this)
        ScreenDialog.listen(this, K_SWITCH) { r ->
            val code = r.getString("code") ?: return@listen
            if (Core.store.groups().none { it.code == code }) return@listen
            Core.switchGroup(code)
            b.list.scrollToPosition(0)
        }

        b.list.layoutManager = LinearLayoutManager(this)
        b.list.adapter = adapter
        b.btnPeople.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        b.btnMe.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        b.fab.setOnClickListener { startActivity(Intent(this, GroupActivity::class.java).putExtra("add", true)) }
        b.warn.setOnClickListener {
            val i = when {
                !Core.bluetoothOn() -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
                !Core.wifiOn() -> Intent(Settings.ACTION_WIFI_SETTINGS)
                else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            }
            try { startActivity(i) } catch (e: Exception) { }
        }
        Core.version.observe(this) { refresh() }
    }

    /** singleTop: "Done" after starting a group lands here — show the new group from the top. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (::b.isInitialized) b.list.scrollToPosition(0)
    }

    override fun onResume() {
        super.onResume()
        if (Core.store.group() == null || !Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.ensureRunning()
        refresh()
    }

    private fun refresh() {
        if (isFinishing || !::b.isInitialized) return
        val name = Core.store.name
        b.meAvatar.text = Ui.initial(name)
        b.meAvatar.background.mutate().setTint(MessageAdapter.avatarColor(Core.store.nodeId))

        val r = Core.router
        // Status pill: coloured dot + the plain-English line.
        val line = Core.statusLine()
        val connected = r != null && r.peopleInRange() > 0
        val s = SpannableString("● $line")
        s.setSpan(ForegroundColorSpan(getColor(if (connected) R.color.online else R.color.offline)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        b.status.text = s
        b.status.contentDescription = line

        // Radio advice. Linked phones work over Bluetooth alone, so WiFi-off is a nudge, not an alarm,
        // and a stale "can't search" problem means nothing while phones are linked.
        val linked = r != null && r.authedLinks().isNotEmpty()
        val warn = when {
            !Core.bluetoothOn() -> getString(R.string.bt_off)
            !Core.wifiOn() -> getString(if (linked) R.string.wifi_off_linked else R.string.wifi_off)
            !linked && Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE

        val rows = ArrayList<HomeAdapter.Row>()
        if (notificationsBlocked() && System.currentTimeMillis() - prefs.getLong(K_NOTIF_HIDDEN, 0) > NOTIF_HINT_PAUSE_MS) {
            rows += HomeAdapter.Row.NotifOff
        }
        if (r != null) addActiveGroup(r, rows, connected)
        addOtherGroups(rows)
        if (r != null && r.messages.isEmpty() && r.people.isEmpty()) rows += HomeAdapter.Row.Invite
        adapter.submit(rows)
    }

    /** One reaction that involves me — on my message, or mine on someone's — for a chat's preview. */
    private class Reacted(val m: Message, val who: String, val emoji: String, val ts: Long)

    private fun addActiveGroup(r: Router, rows: ArrayList<HomeAdapter.Row>, connected: Boolean) {
        val now = System.currentTimeMillis()
        val fp = r.group.fingerprint
        val sum = Core.internetSummary()
        rows += HomeAdapter.Row.Internet(sum.line, sum.waitingForMe, sum.unreadAnswers)

        // One pass over the messages covers every row: each chat's last message, the name each
        // partner last signed with, and the newest reaction that involves me (like other chat apps,
        // "Asha reacted ❤️ to …" leads the preview until something newer is said).
        val unread = Core.unreadCounts()
        val last = HashMap<String, Message>()
        val partnerName = HashMap<String, String>()
        val reacted = HashMap<String, Reacted>()
        for (m in r.messages) {
            val chat = when { m.isGroup -> Core.GROUP; m.from == r.me.id -> m.to ?: continue; else -> m.from }
            last[chat] = m   // r.messages is in chat order
            if (!m.isGroup && m.from != r.me.id) partnerName[chat] = m.fromName
            if (m.reactions.isEmpty() || !m.isPersonal) continue
            for ((who, emoji) in m.reactions) {
                if (who != r.me.id && m.from != r.me.id) continue
                val ts = minOf(m.reactionTsOf(who) ?: continue, now)
                if ((reacted[chat]?.ts ?: Long.MIN_VALUE) < ts) reacted[chat] = Reacted(m, who, emoji, ts)
            }
        }

        // The group chat itself.
        val groupName = r.group.name.ifEmpty { getString(R.string.your_group) }
        val gLast = last[Core.GROUP]
        val gReact = reacted[Core.GROUP]?.takeIf { gLast == null || it.ts > gLast.ts }
        val gTs = minOf(maxOf(gLast?.ts ?: 0, gReact?.ts ?: 0), now)
        // A half-written message shows as "Draft: …" until it's sent, like every chat app.
        fun draftOf(chat: String): String? = ChatDrafts.get(this, fp, chat)?.text?.replace('\n', ' ')?.trim()?.takeIf { it.isNotEmpty() }
        val gDraft = draftOf(Core.GROUP)
        rows += HomeAdapter.Row.Chat(
            key = null, name = groupName, initial = Ui.initial(groupName),
            preview = gDraft ?: gReact?.let { reactionPreview(r, it) } ?: gLast?.let { previewOf(r, it, inGroup = true) } ?: getString(R.string.everyone, groupName),
            draft = gDraft != null,
            ts = gTs, time = Ui.listTime(gTs),
            unread = unread[Core.GROUP] ?: 0, muted = Core.store.isMuted(fp, Core.GROUP),
            avatarColor = MessageAdapter.avatarColor(fp), live = connected,
        )

        // Direct chats, newest first.
        class Dm(val id: String, val last: Message, val react: Reacted?, val ts: Long)
        val threads = last.filterKeys { it != Core.GROUP }.map { (id, m) ->
            val react = reacted[id]?.takeIf { it.ts > m.ts }
            Dm(id, m, react, minOf(maxOf(m.ts, react?.ts ?: 0), now))
        }.sortedByDescending { it.ts }
        if (threads.isNotEmpty()) rows += HomeAdapter.Row.Section(getString(R.string.direct_chats))
        for (t in threads) {
            val fallback = partnerName[t.id].orEmpty()
            val p = r.people[t.id]
            val draft = draftOf(t.id)
            rows += HomeAdapter.Row.Chat(
                key = t.id, name = Ui.uniqueName(r, t.id, fallback), initial = Ui.initial(Ui.nameOf(r, t.id, fallback)),
                preview = draft ?: t.react?.let { reactionPreview(r, it) } ?: previewOf(r, t.last, inGroup = false), draft = draft != null,
                ts = t.ts, time = Ui.listTime(t.ts), unread = unread[t.id] ?: 0, muted = Core.store.isMuted(fp, t.id),
                avatarColor = MessageAdapter.avatarColor(t.id), live = p != null && r.isInRange(p),
            )
        }
    }

    /** Groups the radio isn't serving right now, with what they had unread when they left it. */
    private fun addOtherGroups(rows: ArrayList<HomeAdapter.Row>) {
        val active = Core.store.activeCode
        val others = Core.store.groups().filter { it.code != active }.sortedByDescending { it.lastActive }
        if (others.isEmpty()) return
        rows += HomeAdapter.Row.Section(getString(R.string.other_groups))
        for (g in others) {
            rows += HomeAdapter.Row.OtherGroup(g.code, Asks.groupLabel(g), Core.store.pausedUnread(g.fingerprint), Core.store.isMuted(g.fingerprint, Core.GROUP))
        }
        rows += HomeAdapter.Row.Note(getString(R.string.one_group_note))
    }

    private fun previewOf(r: Router, m: Message, inGroup: Boolean): String {
        val body = Notifications.preview(this, m).replace('\n', ' ')
        return when {
            m.isNotice -> if (m.from == r.me.id) getString(R.string.notice_renamed_by_you, m.text)
                else getString(R.string.notice_renamed_by, Ui.uniqueName(r, m.from, m.fromName), m.text)
            m.kind == Message.SYSTEM -> body
            m.from == r.me.id -> getString(R.string.preview_you, body)
            inGroup -> getString(R.string.preview_from, Ui.uniqueName(r, m.from, m.fromName), body)
            else -> body
        }
    }

    private fun reactionPreview(r: Router, x: Reacted): String {
        val snippet = clip(Notifications.preview(this, x.m).replace('\n', ' '), 40)
        return if (x.who == r.me.id) getString(R.string.reacted_you, x.emoji, snippet)
        else getString(R.string.reacted_to, Ui.uniqueName(r, x.who), x.emoji, snippet)
    }

    /** At most [max] characters, never cutting an emoji in half. */
    private fun clip(s: String, max: Int): String {
        if (s.length <= max) return s
        var end = max - 1
        if (Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).trimEnd() + "…"
    }

    // ------------------------------------------------------------------ long-press menus

    private fun showChatMenu(row: HomeAdapter.Row.Chat, anchor: View) {
        val chat = row.key ?: Core.GROUP
        val muted = Core.isMuted(chat)
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, M_MUTE, 0, if (muted) R.string.menu_unmute else R.string.menu_mute)
            if (row.key == null) {
                menu.add(0, M_CLEAR, 1, R.string.menu_clear_chat)
                menu.add(0, M_INFO, 2, R.string.menu_group_info)
            } else menu.add(0, M_DELETE, 1, R.string.menu_delete_chat)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    M_MUTE -> if (muted) Core.setMuted(chat, 0) else Asks.mute(this@HomeActivity, chat, row.name)
                    M_CLEAR -> Asks.clear(this@HomeActivity, null, row.name)
                    M_INFO -> startActivity(Intent(this@HomeActivity, GroupInfoActivity::class.java))
                    M_DELETE -> Asks.clear(this@HomeActivity, row.key, row.name)
                }
                true
            }
            show()
        }
    }

    private fun showOtherGroupMenu(g: SavedGroup, anchor: View) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, M_SWITCH, 0, R.string.menu_switch_group)
            menu.add(0, M_REMOVE, 1, R.string.menu_remove_group)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    M_SWITCH -> confirmSwitch(g)
                    M_REMOVE -> Asks.removeGroup(this@HomeActivity, g)
                }
                true
            }
            show()
        }
    }

    private fun savedGroup(code: String): SavedGroup? = Core.store.groups().firstOrNull { it.code == code && it.code != Core.store.activeCode }

    private fun confirmSwitch(g: SavedGroup) = ScreenDialog.confirm(this, K_SWITCH,
        getString(R.string.switch_group_title, Asks.groupLabel(g)), getString(R.string.switch_group_body),
        getString(R.string.switch_btn), data = bundleOf("code" to g.code))

    // ------------------------------------------------------------------ notifications off

    /** Off for the whole app, or just for messages (the channel switched off in system settings). */
    private fun notificationsBlocked(): Boolean {
        val nm = NotificationManagerCompat.from(this)
        if (!nm.areNotificationsEnabled()) return true
        return nm.getNotificationChannel(Notifications.CH_MESSAGES)?.importance == NotificationManager.IMPORTANCE_NONE
    }

    private fun turnOnNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            // Android still shows its own dialog while it allows asking; once it doesn't (two
            // "Don't allow"s), asking again would silently fail — so after our first try, Settings.
            val askedHere = prefs.getBoolean(K_NOTIF_ASKED, false)
            if (!askedHere || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                prefs.edit().putBoolean(K_NOTIF_ASKED, true).apply()
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        val nm = NotificationManagerCompat.from(this)
        val i = if (nm.areNotificationsEnabled()) Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, Notifications.CH_MESSAGES)
        else Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        try { startActivity(i) } catch (e: Exception) {
            try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e2: Exception) { }
        }
    }

    // ------------------------------------------------------------------ list adapter

    /**
     * Diffed, so a chat that gets a new message slides to its place and only rows that really
     * changed are redrawn — Home refreshes on every beacon, and a full redraw would flicker.
     */
    class HomeAdapter(private val actions: Actions) : ListAdapter<HomeAdapter.Row, RecyclerView.ViewHolder>(DIFF) {

        interface Actions {
            fun chat(peer: String?)
            fun chatMenu(row: Row.Chat, anchor: View)
            fun otherGroup(code: String)
            fun otherGroupMenu(code: String, anchor: View)
            fun internet()
            fun invite()
            fun notificationsOn()
            fun notificationsDismiss()
        }

        /** Rows are plain values: [id] says which row it is, equality says whether it changed. */
        sealed class Row {
            abstract val id: String
            object NotifOff : Row() { override val id get() = "notif" }
            data class Internet(val line: String, val waitingForMe: Int, val unreadAnswers: Int) : Row() { override val id get() = "net" }
            data class Section(val label: String) : Row() { override val id get() = "section:$label" }
            data class Note(val text: String) : Row() { override val id get() = "note:$text" }
            /** [key] is null for the group chat, else the other person's id. [time] is [ts] as shown, so a new day redraws it. */
            data class Chat(val key: String?, val name: String, val initial: String, val preview: String, val ts: Long, val time: String,
                            val unread: Int, val muted: Boolean, val avatarColor: Int, val live: Boolean, val draft: Boolean = false) : Row() {
                override val id get() = "chat:${key ?: Core.GROUP}"
            }
            data class OtherGroup(val code: String, val name: String, val unread: Int, val muted: Boolean) : Row() {
                override val id get() = "group:$code"
            }
            object Invite : Row() { override val id get() = "invite" }
        }

        fun submit(list: List<Row>) = submitList(list)

        override fun getItemViewType(i: Int) = when (getItem(i)) {
            is Row.Internet -> 0; is Row.Section -> 1; is Row.Note -> 2; is Row.Chat -> 3; is Row.OtherGroup -> 4
            is Row.Invite -> 5; is Row.NotifOff -> 6
        }

        class BindVH(val binding: androidx.viewbinding.ViewBinding) : RecyclerView.ViewHolder(binding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inf = LayoutInflater.from(parent.context)
            return BindVH(when (viewType) {
                0 -> ItemInternetRowBinding.inflate(inf, parent, false)
                1 -> ItemSectionBinding.inflate(inf, parent, false).also { ViewCompat.setAccessibilityHeading(it.root, true) }
                2 -> ItemNoteBinding.inflate(inf, parent, false)
                5 -> ItemInviteBinding.inflate(inf, parent, false)
                6 -> ItemNotifOffBinding.inflate(inf, parent, false)
                else -> ItemChatBinding.inflate(inf, parent, false)
            })
        }

        override fun onBindViewHolder(h: RecyclerView.ViewHolder, i: Int) {
            h as BindVH
            val ctx = h.itemView.context
            when (val row = getItem(i)) {
                is Row.NotifOff -> {
                    val nb = h.binding as ItemNotifOffBinding
                    nb.notifOn.setOnClickListener { actions.notificationsOn() }
                    nb.notifDismiss.setOnClickListener { actions.notificationsDismiss() }
                }
                is Row.Internet -> {
                    val ib = h.binding as ItemInternetRowBinding
                    ib.internetStatus.text = row.line
                    // Someone's request waiting for MY signal outranks answers waiting for me to read.
                    val (n, waiting) = if (row.waitingForMe > 0) row.waitingForMe to true else row.unreadAnswers to false
                    ib.internetBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
                    ib.internetBadge.text = if (n > 99) "99+" else n.toString()
                    ib.internetBadge.setBackgroundResource(if (waiting) R.drawable.bg_unread else R.drawable.bg_badge_info)
                    ib.internetBadge.setTextColor(ctx.getColor(if (waiting) R.color.unread_text else R.color.badge_info_text))
                    // When someone is waiting on my signal the line itself says so — no need to read it twice.
                    val extra = when {
                        row.waitingForMe > 0 -> null
                        row.unreadAnswers > 0 -> ctx.resources.getQuantityString(R.plurals.internet_answers_desc, row.unreadAnswers, row.unreadAnswers)
                        else -> null
                    }
                    ib.root.contentDescription = listOfNotNull(ctx.getString(R.string.internet_title), extra, row.line).joinToString(". ")
                    ib.root.setOnClickListener { actions.internet() }
                }
                is Row.Section -> (h.binding as ItemSectionBinding).label.text = row.label
                is Row.Note -> (h.binding as ItemNoteBinding).note.text = row.text
                is Row.Chat -> {
                    val cb = h.binding as ItemChatBinding
                    cb.name.text = row.name
                    cb.preview.text = if (!row.draft) row.preview else {
                        val label = ctx.getString(R.string.draft_label)
                        SpannableString("$label ${row.preview}").apply {
                            setSpan(ForegroundColorSpan(ctx.getColor(R.color.online)), 0, label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                        }
                    }
                    cb.time.text = row.time
                    cb.time.setTextColor(ctx.getColor(if (row.unread > 0 && !row.muted) R.color.ember else R.color.text_faint))
                    cb.avatar.text = row.initial
                    cb.avatar.background.mutate().setTint(row.avatarColor)
                    cb.avatar.setTextColor(Color.WHITE)
                    cb.dot.visibility = if (row.live) View.VISIBLE else View.GONE
                    cb.muted.visibility = if (row.muted) View.VISIBLE else View.GONE
                    badge(cb.unread, row.unread, row.muted)
                    cb.root.contentDescription = describe(ctx, row.name, row.unread, row.muted, row.live, cb.preview.text.toString(), row.time)
                    cb.root.setOnClickListener { actions.chat(row.key) }
                    cb.root.setOnLongClickListener { v -> actions.chatMenu(row, v); true }
                }
                is Row.Invite -> (h.binding as ItemInviteBinding).inviteBtn.setOnClickListener { actions.invite() }
                is Row.OtherGroup -> {
                    val cb = h.binding as ItemChatBinding
                    val name = row.name
                    cb.name.text = name
                    cb.preview.text = if (row.unread > 0) ctx.resources.getQuantityString(R.plurals.paused_unread, row.unread, row.unread)
                        else ctx.getString(R.string.paused_tap_to_switch)
                    cb.time.text = ""
                    cb.avatar.text = Ui.initial(name)
                    cb.avatar.background.mutate().setTint(ctx.getColor(R.color.surface_variant))
                    cb.avatar.setTextColor(ctx.getColor(R.color.text_muted))
                    cb.dot.visibility = View.GONE
                    cb.muted.visibility = if (row.muted) View.VISIBLE else View.GONE
                    badge(cb.unread, row.unread, row.muted)
                    cb.root.contentDescription = describe(ctx, name, row.unread, row.muted, false, cb.preview.text.toString(), "")
                    cb.root.setOnClickListener { actions.otherGroup(row.code) }
                    cb.root.setOnLongClickListener { v -> actions.otherGroupMenu(row.code, v); true }
                }
            }
        }

        /** Unread count: ember when it should catch the eye, grey when the chat is muted. */
        private fun badge(v: TextView, n: Int, muted: Boolean) {
            v.visibility = if (n > 0) View.VISIBLE else View.GONE
            v.text = if (n > 99) "99+" else n.toString()
            v.setBackgroundResource(if (muted) R.drawable.bg_unread_muted else R.drawable.bg_unread)
            v.setTextColor(v.context.getColor(if (muted) R.color.unread_muted_text else R.color.unread_text))
        }

        /** What TalkBack reads for a chat row: "Trek, 3 unread messages, muted, Asha: see you at camp, 14:20". */
        private fun describe(ctx: android.content.Context, name: String, unread: Int, muted: Boolean, live: Boolean, preview: String, time: String): String =
            listOfNotNull(
                name,
                if (unread > 0) ctx.resources.getQuantityString(R.plurals.unread_desc, unread, unread) else null,
                if (muted) ctx.getString(R.string.muted_desc) else null,
                if (live) ctx.getString(R.string.in_range_desc) else null,
                preview,
                time.ifEmpty { null },
            ).joinToString(", ")

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<Row>() {
                override fun areItemsTheSame(a: Row, b: Row) = a.id == b.id
                @SuppressLint("DiffUtilEquals")   // every Row is a data class or a singleton object
                override fun areContentsTheSame(a: Row, b: Row) = a == b
            }
        }
    }

    companion object {
        const val UI_PREFS = "hopline_screens"
        private const val K_NOTIF_HIDDEN = "notifHintHiddenAt"
        private const val K_NOTIF_ASKED = "notifAskedFromHome"
        private const val K_SWITCH = "home.switch"
        /** A dismissed "notifications are off" hint comes back after a week — off for good is rarely what someone wants. */
        private const val NOTIF_HINT_PAUSE_MS = 7 * 24 * 3_600_000L
        private const val M_MUTE = 1
        private const val M_CLEAR = 2
        private const val M_INFO = 3
        private const val M_DELETE = 4
        private const val M_SWITCH = 5
        private const val M_REMOVE = 6
    }
}
