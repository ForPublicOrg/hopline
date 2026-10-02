package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.format.DateUtils
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.hopline.R
import app.hopline.core.Words
import app.hopline.data.SavedGroup
import app.hopline.databinding.ActivityGroupInfoBinding
import app.hopline.databinding.ItemPersonBinding
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Permissions
import java.util.Locale

/**
 * Group info: the group's name (rename for everyone), its code, mute, its members, clear and leave.
 * Opened by tapping the group chat's header, WhatsApp-style. It belongs to the group that was on
 * the radio when it opened — if that changes, it closes instead of showing another group.
 */
class GroupInfoActivity : AppCompatActivity() {
    private lateinit var b: ActivityGroupInfoBinding
    private var fp: String? = null
    private val rows = ArrayList<ItemPersonBinding>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val g = Core.store.activeGroup()
        if (g == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        fp = g.fingerprint
        b = ActivityGroupInfoBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        Asks.listen(this)

        b.toolbar.setNavigationOnClickListener { finish() }
        b.editName.setOnClickListener { Asks.groupName(this) }
        b.groupName.setOnClickListener { Asks.groupName(this) }
        b.showQr.setOnClickListener { startActivity(Intent(this, CodeActivity::class.java)) }
        b.shareInvite.setOnClickListener { shareInvite() }
        b.code.setOnClickListener { copyCode() }
        b.muteRow.setOnClickListener {
            if (Core.isMuted(Core.GROUP)) Core.setMuted(Core.GROUP, 0) else Asks.mute(this, Core.GROUP, currentName())
        }
        b.clearChat.setOnClickListener { Asks.clear(this, null, currentName()) }
        b.leave.setOnClickListener { Asks.leave(this) }
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
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (Core.store.group() == null || !Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.ensureRunning()
        refresh()
    }

    private fun currentName(): String {
        val g = Core.store.activeGroup()
        return Core.router?.group?.name?.ifEmpty { null } ?: g?.name?.ifEmpty { null } ?: getString(R.string.your_group)
    }

    private fun refresh() {
        if (isFinishing) return
        val g = Core.store.activeGroup()
        if (g == null || g.fingerprint != fp) { finish(); return }
        val r = Core.router
        val name = currentName()
        b.groupName.text = name
        b.groupName.contentDescription = getString(R.string.group_name_desc, name)
        b.avatar.text = Ui.initial(name)
        b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(g.fingerprint))
        val words = Words.pretty(g.code)
        b.code.text = words
        b.code.contentDescription = getString(R.string.code_desc, words)

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
        shown.forEachIndexed { i, p -> PeopleActivity.bindPerson(rows[i + 1], r, p) { openChat(p) } }
        b.membersNote.text = getString(R.string.only_you)
        b.membersNote.visibility = if (others.isEmpty()) View.VISIBLE else View.GONE
        b.viewAll.text = getString(R.string.view_all, total)
        b.viewAll.visibility = if (sorted.size > shown.size) View.VISIBLE else View.GONE
    }

    /** Who named the group and when, if this phone knows. */
    private fun namedLine(r: Router, g: SavedGroup): String? {
        val notice = r.messages.lastOrNull { it.isNotice }
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

    private fun shareInvite() {
        val g = Core.store.activeGroup() ?: return
        val name = Core.router?.group?.name?.ifEmpty { null } ?: g.name.ifEmpty { getString(R.string.our_group) }
        val text = getString(R.string.invite_text, name, Words.pretty(g.code))
        try {
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        } catch (e: ActivityNotFoundException) { }
    }

    private fun copyCode() {
        val g = Core.store.activeGroup() ?: return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.code_clip_label), Words.pretty(g.code)))
        // Android 13+ confirms a copy on its own; a toast on top would say it twice.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
    }

    companion object {
        /** Rows shown here, me included; a bigger group gets "View all" (the People screen). */
        private const val MAX_ROWS = 10
    }
}
