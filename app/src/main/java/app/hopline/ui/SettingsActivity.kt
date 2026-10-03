package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.core.Update
import app.hopline.core.Words
import app.hopline.databinding.ActivitySettingsBinding
import app.hopline.service.Core
import app.hopline.service.Updater

/**
 * Who I am, this group, internet sharing, and how the whole thing works. Opens for a phone that
 * only has groups it left, too — my name, the sharing switch and About belong to the phone, not to
 * a group; the "This group" card is simply not shown while no group is on the radio.
 *
 * About is also where Hopline's own updates live for anyone who wants them later: the version on
 * this phone, what is known about a newer one with the action that fits (the same states as the
 * banner on Home, whether or not that was closed), "Check for updates", and the switch that stops
 * Hopline looking by itself.
 */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding
    /** Which update state TalkBack last heard about here, so it is told of a new one once — and not of the clock moving on. */
    private var updateSaid: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.allGroups().isEmpty()) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        Asks.listen(this)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.meRow.setOnClickListener { Asks.myName(this) }
        b.groupRow.setOnClickListener { startActivity(Intent(this, GroupInfoActivity::class.java)) }
        b.showCode.setOnClickListener { startActivity(Intent(this, CodeActivity::class.java)) }
        b.rename.setOnClickListener { Asks.groupName(this) }
        b.leave.setOnClickListener { Core.store.activeGroup()?.let { Asks.leave(this, it) } }
        // One switch for every group (it is this phone's data plan); refresh() sets it without echo.
        b.share.setOnCheckedChangeListener { _, on -> if (on != Core.store.shareInternet) Core.setShareInternet(on) }
        b.how.setOnClickListener {
            ScreenDialog.info(this, "settings.how", getString(R.string.how_it_works), getString(R.string.how_it_works_body_v2), getString(R.string.ok))
        }
        b.version.text = getString(R.string.update_installed, Updater.installedVersion)
        b.updateCheck.setOnClickListener {
            // With no internet, "Hopline will check when you have signal" is said only when it will ([Update.asked]).
            when (Updater.checkNow()) {
                Update.Asked.CHECKING -> { }
                Update.Asked.LATER -> Toast.makeText(this, R.string.update_no_internet, Toast.LENGTH_LONG).show()
                Update.Asked.NOT_NOW -> Toast.makeText(this, R.string.update_no_internet_manual, Toast.LENGTH_LONG).show()
            }
        }
        // refresh() sets it without echo: setAuto does nothing when told what it already is.
        b.updateAuto.setOnCheckedChangeListener { _, on -> Updater.setAuto(on) }
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() {
        super.onResume()
        if (::b.isInitialized) {
            Updater.screenShown()
            // A look that is due — or was promised here, with no internet at the time — is made on
            // coming back to this screen too, not only on Home: no group on the radio, no ticks.
            Updater.maybeCheck()
        }
        refresh()
    }

    private fun refresh() {
        if (!::b.isInitialized) return
        val name = Core.store.name
        b.meName.text = name
        b.meAvatar.text = Ui.initial(name)
        b.meAvatar.background.mutate().setTint(MessageAdapter.avatarColor(Core.store.nodeId))
        b.meRow.contentDescription = getString(R.string.me_row_desc, name)

        val g = Core.store.activeGroup()
        val inGroup = if (g != null) View.VISIBLE else View.GONE
        b.groupLabel.visibility = inGroup
        b.groupCard.visibility = inGroup
        val groupName = Core.router?.group?.name?.ifEmpty { null } ?: g?.name?.ifEmpty { null } ?: getString(R.string.your_group)
        b.groupName.text = groupName
        b.groupAvatar.text = Ui.initial(groupName)
        g?.let { b.groupAvatar.background.mutate().setTint(MessageAdapter.avatarColor(it.fingerprint)) }
        b.groupCode.text = g?.let { Words.pretty(it.code) } ?: ""
        b.groupRow.contentDescription = getString(R.string.group_row_desc, groupName)

        b.share.isChecked = Core.store.shareInternet
        val mb = Core.store.shareBudgetMb
        b.shareSub.text = if (mb > 0) getString(R.string.share_internet_sub_budget, mb) else getString(R.string.share_internet_sub_unlimited)
        val paused = Core.helpPausedReason()
        b.sharePaused.text = paused.orEmpty()
        b.sharePaused.visibility = if (paused != null) View.VISIBLE else View.GONE

        refreshUpdate()
    }

    /**
     * The update lines of About. Whatever the state, there is at most one thing to tap for it and
     * nothing that has to be tapped: a phone that never updates loses nothing here.
     */
    private fun refreshUpdate() {
        val s = Updater.status
        val shown = UpdateCard.of(this, s)
        // What stands still about the state (for TalkBack, below) and the line as it reads right now.
        val line = UpdateCard.line(enabled = Updater.enabled, checking = s is Updater.Status.Checking, available = s is Updater.Status.Available,
            known = shown != null, checked = Updater.lastOkAt > 0, sure = Updater.sure, auto = Updater.auto)
        val (kind, state) = when (line) {
            UpdateCard.Line.DEBUG -> "debug" to getString(R.string.update_state_debug)
            UpdateCard.Line.CHECKING -> "checking" to getString(R.string.update_state_checking)
            UpdateCard.Line.AVAILABLE -> shown?.spoken.orEmpty() to getString(R.string.update_state_available, s.release?.version.orEmpty())
            UpdateCard.Line.STATE -> shown?.spoken.orEmpty() to shown?.title.orEmpty()
            UpdateCard.Line.CURRENT -> "current" to getString(R.string.update_state_current, UpdateCard.ago(this, Updater.lastOkAt))
            UpdateCard.Line.UNSURE -> "unsure" to getString(R.string.update_state_unsure, UpdateCard.ago(this, Updater.lastOkAt))
            UpdateCard.Line.NEVER -> "never" to getString(R.string.update_state_never)
            UpdateCard.Line.OFF -> "off" to getString(R.string.update_state_off)
        }
        b.updateState.text = state
        b.updateDetail.text = shown?.body.orEmpty()
        b.updateDetail.visibility = if (shown != null) View.VISIBLE else View.GONE
        UpdateCard.bind(shown, b.updateProgress, b.updateActions, b.updatePrimary, b.updateSecondary) { UpdateCard.run(this, it) }

        // A test build can't update itself: nothing to check, nothing to switch.
        val can = if (Updater.enabled) View.VISIBLE else View.GONE
        b.updateCheck.visibility = can
        b.updateAutoBlock.visibility = can
        b.updateCheck.isEnabled = s !is Updater.Status.Checking && s !is Updater.Status.Downloading && s !is Updater.Status.Installing
        b.updateAuto.isChecked = Updater.auto

        b.updateText.contentDescription = listOf(b.version.text, state, shown?.body.orEmpty()).filter { it.isNotEmpty() }.joinToString(". ")
        // Said once when the state becomes another one ("Checking…", then what was found) — not
        // when the screen is first drawn, and not each minute "checked 5 minutes ago" grows older.
        if (updateSaid != null && updateSaid != kind) {
            b.updateText.announceForAccessibility(if (s is Updater.Status.Downloading && shown != null) shown.spoken else listOf(state, shown?.body.orEmpty()).filter { it.isNotEmpty() }.joinToString(". "))
        }
        updateSaid = kind
    }
}
