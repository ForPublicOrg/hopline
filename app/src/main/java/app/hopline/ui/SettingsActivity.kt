package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import androidx.appcompat.app.AlertDialog
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
        b = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.toolbar.setNavigationOnClickListener { finish() }

        b.meRow.setOnClickListener {
            Ui.ask(this, getString(R.string.edit_name), listOf(getString(R.string.name_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS)),
                getString(R.string.done), prefill = listOf(Core.store.name)) { v ->
                val name = v[0]
                if (name.length >= 2) {
                    Core.store.name = name
                    Core.router?.let { it.me.name = name; it.sendPresence() }
                    refresh()
                }
            }
        }
        b.showCode.setOnClickListener { startActivity(Intent(this, CodeActivity::class.java)) }
        b.rename.setOnClickListener {
            val g = Core.store.activeGroup() ?: return@setOnClickListener
            Ui.ask(this, getString(R.string.rename_group), listOf(getString(R.string.group_name_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
                getString(R.string.done), prefill = listOf(g.name)) { v ->
                if (v[0].isNotEmpty()) {
                    Core.store.renameGroup(g.code, v[0])
                    Core.router?.let { it.group.name = v[0]; it.sendPresence() }
                    refresh()
                }
            }
        }
        b.leave.setOnClickListener { confirmLeave() }
        b.share.setOnCheckedChangeListener { _, on ->
            Core.router?.let { if (it.shareInternet != on) { it.shareInternet = on; it.sendPresence() } }
        }
        b.how.setOnClickListener {
            AlertDialog.Builder(this).setTitle(R.string.how_it_works)
                .setMessage(R.string.how_it_works_body)
                .setPositiveButton(R.string.ok, null).show()
        }
        val versionName = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        b.version.text = getString(R.string.version, versionName)
        Core.version.observe(this) { refresh() }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val name = Core.store.name
        b.meName.text = getString(R.string.you_suffix, name)
        b.meAvatar.text = name.take(1).uppercase().ifEmpty { "?" }
        b.meAvatar.background.mutate().setTint(MessageAdapter.avatarColor(Core.store.nodeId))
        val g = Core.store.activeGroup()
        b.groupName.text = g?.name?.ifEmpty { "Your group" } ?: "—"
        b.groupCode.text = g?.let { Words.pretty(it.code) } ?: ""
        b.share.isChecked = Core.router?.shareInternet ?: true
    }

    private fun confirmLeave() {
        val g = Core.store.activeGroup() ?: return
        AlertDialog.Builder(this).setTitle(R.string.leave_group)
            .setMessage(getString(R.string.leave_confirm, g.name.ifEmpty { Words.pretty(g.code) }))
            .setPositiveButton(R.string.leave) { _, _ ->
                Core.leaveActiveGroup()
                if (Core.store.group() == null) {
                    startActivity(Intent(this, GroupActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                } else {
                    startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                }
            }
            .setNegativeButton(R.string.cancel, null).show()
    }
}
