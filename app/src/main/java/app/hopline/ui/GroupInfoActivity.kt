package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.hopline.R
import app.hopline.core.Words
import app.hopline.data.SavedGroup
import app.hopline.databinding.ActivityGroupInfoBinding
import app.hopline.databinding.ItemPersonBinding
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Notifications
import app.hopline.service.Permissions
import java.util.Locale

/**
 * Group info: the group's name (rename for everyone), its code, mute, its members, clear and leave.
 * Opened by tapping the group chat's header, WhatsApp-style. It belongs to the group that was on
 * the radio when it opened — if that changes, it closes instead of showing another group.
 *
 * Opened with [EXTRA_CODE] it is about a group this phone LEFT instead, and shows what is still
 * true of one: its name, when it was left, its code (which still gets back in), the private chats
 * kept with its chat, and the two things left to do with it — rejoin, or delete. Nothing that
 * needs the radio is offered: no rename, no QR or invite, no mute, and no member list (who is "in
 * range" of a group this phone no longer hears would be a guess). If the group is rejoined or
 * deleted from somewhere else, the screen closes.
 */
class GroupInfoActivity : AppCompatActivity() {
    private lateinit var b: ActivityGroupInfoBinding
    private var fp: String? = null
    /** The code of the left group this screen is about; null when it is about the group on the radio. */
    private var leftCode: String? = null
    private val rows = ArrayList<ItemPersonBinding>()

    /** One private chat kept with a left group's chat: who it is with, and when it last said something. */
    private class PrivateChat(val id: String, val name: String, val time: String)
    private var privateChats: List<PrivateChat> = emptyList()
    private val privateRows = ArrayList<ItemPersonBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        leftCode = intent.getStringExtra(EXTRA_CODE)?.let { Words.normalise(it) }
        val g = shownGroup()
        if (g == null) {
            // No group on the radio: the launch screen knows where to go. A left group that isn't
            // one any more (rejoined, deleted) just closes — whatever opened this is still there.
            if (leftCode == null) startActivity(Intent(this, LaunchActivity::class.java))
            finish(); return
        }
        fp = g.fingerprint
        b = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        Asks.listen(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.code.setOnClickListener { copyCode() }
        if (leftCode == null) setUpMember() else setUpLeft()
        Core.version.observe(this) { refresh() }
    }

    /**
     * The group this screen is about, as it is saved right now. Null once it is no longer what the
     * screen was opened for: not on the radio any more, or — for a left group — rejoined or deleted.
     */
    private fun shownGroup(): SavedGroup? {
        val code = leftCode ?: return Core.store.activeGroup()
        return Core.store.leftGroups().firstOrNull { it.code == code }
    }

    private fun setUpMember() {
        Core.ensureRunning()
        b.editName.setOnClickListener { Asks.groupName(this) }
        b.groupName.setOnClickListener { Asks.groupName(this) }
        b.showQr.setOnClickListener { startActivity(Intent(this, CodeActivity::class.java)) }
        b.shareInvite.setOnClickListener { shareInvite() }
        b.muteRow.setOnClickListener {
            if (Core.isMuted(Core.GROUP)) Core.setMuted(Core.GROUP, 0) else Asks.mute(this, Core.GROUP, currentName())
        }
        b.clearChat.setOnClickListener { Asks.clear(this, null, currentName()) }
        b.leave.setOnClickListener { shownGroup()?.let { Asks.leave(this, it) } }
        b.viewAll.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        // TalkBack hears the mute row as the switch it looks like: "Mute notifications, switch, off".
        ViewCompat.setAccessibilityDelegate(b.muteRow, object : AccessibilityDelegateCompat() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = android.widget.Switch::class.java.name
                info.isCheckable = true
                info.isChecked = Core.isMuted(Core.GROUP)
            }
        })
    }

    /**
     * A left group: everything that talks to the radio goes, and Rejoin / Delete take the place of
     * Clear / Leave. The radio is not woken for it — reading needs none.
     */
    private fun setUpLeft() {
        // The pencil keeps its place, unseen, so the name stays centred against the spacer opposite.
        b.editName.visibility = View.INVISIBLE
        for (v in listOf(b.namedLine, b.codeActions, b.muteRow, b.membersHeader, b.membersCard, b.clearChat, b.leave)) v.visibility = View.GONE
        b.rejoin.visibility = View.VISIBLE
        b.deleteGroup.visibility = View.VISIBLE
        b.rejoin.setOnClickListener { shownGroup()?.let { Asks.rejoin(this, it) } }
        b.deleteGroup.setOnClickListener { shownGroup()?.let { Asks.deleteGroup(this, it) } }
    }

    override fun onResume() {
        super.onResume()
        if (!::b.isInitialized || isFinishing) return
        if (leftCode == null) {
            if (!Core.store.hasActive() || !Permissions.allGranted(this)) {
                startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
            }
            Core.ensureRunning()
        } else {
            // Worked out each time the screen comes to the front, not on every redraw: it reads the
            // group's saved chat, and only changes by what a chat screen above this one deletes.
            privateChats = fp?.let { readPrivateChats(it) } ?: emptyList()
        }
        refresh()
    }

    private fun currentName(): String {
        val g = Core.store.activeGroup()
        return Core.router?.group?.name?.ifEmpty { null } ?: g?.name?.ifEmpty { null } ?: getString(R.string.your_group)
    }

    private fun refresh() {
        if (isFinishing) return
        val g = shownGroup()
        if (g == null || g.fingerprint != fp) { finish(); return }
        if (leftCode != null) { refreshLeft(g); return }
        val r = Core.router
        val name = currentName()
        b.groupName.text = name
        b.groupName.contentDescription = getString(R.string.group_name_desc, name)
        b.avatar.text = Ui.initial(name)
        b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(g.fingerprint))
        showCode(g)

        val muted = Asks.mutedLine(this, Core.GROUP)
        b.muteSwitch.isChecked = muted != null
        b.muteState.text = muted ?: getString(R.string.mute_off)

        if (r == null) {
            b.subtitle.text = getString(R.string.people_starting)
            b.namedLine.visibility = View.GONE
            b.membersHeader.text = getString(R.string.people)
            ensureRows(1); bindMe(rows[0])
            b.membersNote.visibility = View.GONE
            b.viewAll.visibility = View.GONE
            return
        }
        // "The group" is everyone heard from in the last two days, plus me — not everyone ever.
        val others = r.activePeopleList().filter { it.id != r.me.id }
        val total = others.size + 1
        b.subtitle.text = resources.getQuantityString(R.plurals.group_people, total, total)
        val line = namedLine(r, g)
        b.namedLine.text = line
        b.namedLine.visibility = if (line == null) View.GONE else View.VISIBLE

        val inRange = others.count { r.isInRange(it) }
        b.membersHeader.text = getString(R.string.members_header, resources.getQuantityString(R.plurals.members_count, total, total), inRange)
        val sorted = others.sortedWith(compareByDescending<Person> { r.isInRange(it) }
            .thenBy { Ui.nameOf(r, it.id, it.name).lowercase(Locale.getDefault()) })
        val shown = sorted.take(MAX_ROWS - 1)
        ensureRows(shown.size + 1)
        bindMe(rows[0])
        shown.forEachIndexed { i, p -> PeopleActivity.bindPerson(rows[i + 1], r, p, onVerify = { verify(r, p) }) { openChat(p) } }
        // A group that has been quiet for two days (every rejoin starts this way) isn't a group
        // nobody joined: its people are in the chat, just not heard from lately.
        b.membersNote.text = getString(if (Ui.othersKnown(r)) R.string.nobody_lately else R.string.only_you)
        b.membersNote.visibility = if (others.isEmpty()) View.VISIBLE else View.GONE
        b.viewAll.text = getString(R.string.view_all, total)
        b.viewAll.visibility = if (sorted.size > shown.size) View.VISIBLE else View.GONE
    }

    /** A left group, from its saved entry alone: grey like its row on Home, with when it was left. */
    private fun refreshLeft(g: SavedGroup) {
        val name = Asks.groupLabel(g)
        b.groupName.text = name
        b.avatar.text = Ui.initial(name)
        b.avatar.background.mutate().setTint(getColor(R.color.surface_variant))
        b.avatar.setTextColor(getColor(R.color.text_muted))
        b.subtitle.text = getString(R.string.left_info_subtitle, whenText(g.leftAt))
        showCode(g)
        bindPrivateChats(g.fingerprint)
    }

    /**
     * The group's code — and, for one started before 2.4, one quiet line under it: its code is
     * the older three-word kind, and a new group gets a stronger one. Nothing to do, nothing forced.
     */
    private fun showCode(g: SavedGroup) {
        val words = Words.pretty(g.code)
        b.code.text = words
        b.code.contentDescription = getString(R.string.code_desc, words)
        b.codeNote.visibility = if (ScreenRules.olderCode(g.code)) View.VISIBLE else View.GONE
    }

    /**
     * The private chats kept with a left group's chat, newest first, from the group's saved state
     * ([Core.archive]: read from disk the first time, then held by Core). Empty when there are
     * none — or when the chat can't be read just now, and then the card simply isn't shown.
     * Only what the rows need is kept: Core lets the chat itself go when the app is in the
     * background, and holding on to it here would keep it in memory.
     */
    private fun readPrivateChats(fp: String): List<PrivateChat> {
        val a = Core.archive(fp) ?: return emptyList()
        val me = a.me.id
        val last = LinkedHashMap<String, Message>()
        val partnerName = HashMap<String, String>()
        for (m in a.messages) {
            if (m.isGroup) continue
            val chat = m.chatKey(me)
            last[chat] = m   // a.messages is in chat order
            if (m.from != me) partnerName[chat] = m.fromName
        }
        val now = System.currentTimeMillis()
        return last.entries.sortedByDescending { minOf(it.value.ts, now) }.map { (id, m) ->
            PrivateChat(id, Ui.nameOf(a, id, partnerName[id].orEmpty()), Ui.listTime(minOf(m.ts, now)))
        }
    }

    private fun bindPrivateChats(fp: String) {
        val chats = privateChats
        val show = if (chats.isEmpty()) View.GONE else View.VISIBLE
        b.privateHeader.visibility = show
        b.privateChats.visibility = show
        while (privateRows.size < chats.size) privateRows += ItemPersonBinding.inflate(layoutInflater, b.privateChats, true)
        while (privateRows.size > chats.size) b.privateChats.removeView(privateRows.removeAt(privateRows.size - 1).root)
        chats.forEachIndexed { i, c ->
            val row = privateRows[i]
            row.name.text = c.name
            row.avatar.text = Ui.initial(c.name)
            row.avatar.background.mutate().setTint(MessageAdapter.avatarColor(c.id))
            row.status.text = c.time
            row.dot.visibility = View.GONE
            row.badge.visibility = View.GONE
            // The chat screen is told which group by its fingerprint, and shows it read-only.
            row.root.setOnClickListener {
                startActivity(Intent(this, ChatActivity::class.java).putExtra(Notifications.EXTRA_FP, fp).putExtra(Notifications.EXTRA_PEER, c.id))
            }
            ViewCompat.replaceAccessibilityAction(row.root, AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
                getString(R.string.action_read_chat), null)
        }
    }

    /** Who named the group and when, if this phone knows. */
    private fun namedLine(r: Router, g: SavedGroup): String? {
        val notice = r.messages.lastOrNull { it.isRename }
        val at = r.group.nameAt
        return when {
            notice != null && notice.text == r.group.name ->
                if (notice.from == r.me.id) getString(R.string.renamed_by_you, whenText(notice.ts))
                else getString(R.string.renamed_by, Ui.uniqueName(r, notice.from, notice.fromName), whenText(notice.ts))
            // Starting a group stamps its name the moment it is saved; a joiner learns an older stamp.
            at > 0 && g.joinedAt - at in 0L..2_000L ->getString(R.string.started_by_you, whenText(at))
            at > 0 -> getString(R.string.named_at, whenText(minOf(at, System.currentTimeMillis())))
            else -> null
        }
    }

    private fun whenText(ts: Long): String = DateUtils.formatDateTime(this, ts,
        DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH)

    private fun ensureRows(n: Int) {
        while (rows.size < n) rows += ItemPersonBinding.inflate(layoutInflater, b.members, true)
        while (rows.size > n) b.members.removeView(rows.removeAt(rows.size - 1).root)
    }

    private fun bindMe(row: ItemPersonBinding) = PeopleActivity.bindMe(row) { Asks.myName(this) }

    private fun openChat(p: Person) = startActivity(Intent(this, ChatActivity::class.java).putExtra("peer", p.id))

    private fun verify(r: Router, p: Person) = SecurityCodeDialog.show(this, p.id, Ui.nameOf(r, p.id, p.name))

    private fun shareInvite() {
        val g = Core.store.activeGroup() ?: return
        val name = Core.router?.group?.name?.ifEmpty { null } ?: g.name.ifEmpty { getString(R.string.our_group) }
        val text = getString(R.string.invite_text, name, Words.pretty(g.code))
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        } catch (e: ActivityNotFoundException) { }
    }

    /** The code of the group on screen — a left group's still works, to get back in or to pass on. */
    private fun copyCode() {
        val g = shownGroup() ?: return
        Ui.copyCode(this, Words.pretty(g.code))
    }

    companion object {
        /**
         * The code of a group this phone LEFT, to show that group (read from the saved list, with
         * no radio). Without it the screen is about the group on the radio.
         */
        const val EXTRA_CODE = "code"
        /** Rows shown here, me included; a bigger group gets "View all" (the People screen). */
        private const val MAX_ROWS = 10
    }
}
