package app.hopline.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.core.Words
import app.hopline.databinding.ActivitySettingsBinding
import app.hopline.service.Core

/** Who I am, this group, internet sharing, and how the whole thing works. */
class SettingsActivity : AppCompatActivity() {
    private lateinit var b: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()
        Asks.listen(this)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.meRow.setOnClickListener { Asks.myName(this) }
        b.groupRow.setOnClickListener { startActivity(Intent(this, GroupInfoActivity::class.java)) }
        b.showCode.setOnClickListener { startActivity(Intent(this, CodeActivity::class.java)) }
        b.rename.setOnClickListener { Asks.groupName(this) }
        b.leave.setOnClickListener { Asks.leave(this) }
        // One switch for every group (it is this phone's data plan); refresh() sets it without echo.
        b.share.setOnCheckedChangeListener { _, on -> if (on != Core.store.shareInternet) Core.setShareInternet(on) }
        b.how.setOnClickListener {
            ScreenDialog.info(this, "settings.how", getString(R.string.how_it_works), getString(R.string.how_it_works_body_v2), getString(R.string.ok))
        }
        b.version.text = getString(R.string.version, versionName())
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun versionName(): String = try {
        val info = if (Build.VERSION.SDK_INT >= 33) packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            else @Suppress("DEPRECATION") packageManager.getPackageInfo(packageName, 0)
        info.versionName ?: "?"
    } catch (e: Exception) { "?" }

    private fun refresh() {
        if (!::b.isInitialized) return
        val name = Core.store.name
        b.meName.text = name
        b.meAvatar.text = Ui.initial(name)
        b.meAvatar.background.mutate().setTint(MessageAdapter.avatarColor(Core.store.nodeId))
        b.meRow.contentDescription = getString(R.string.me_row_desc, name)

        val g = Core.store.activeGroup()
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
    }
}
