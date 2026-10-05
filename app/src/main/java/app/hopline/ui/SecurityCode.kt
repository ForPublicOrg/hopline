package app.hopline.ui

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import app.hopline.R
import app.hopline.core.Crypto
import app.hopline.databinding.DialogSecurityCodeBinding
import app.hopline.service.Core

/**
 * "Verify security code", for one person: both names, the number both phones work out from their
 * two keys (Crypto.safetyNumber — the same on both, whichever phone shows it), what to do with it,
 * and "Mark as verified". Opened from a private chat's menu, or by pressing and holding a person.
 *
 * A DialogFragment like [ScreenDialog], so turning the phone keeps it open. The mark is this
 * phone's own note, kept for every group (Store.isVerified); the person's row, the members of
 * Group info and the private chat's header show it.
 */
class SecurityCodeDialog : DialogFragment() {
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val ctx = requireContext()
        val a = requireArguments()
        val peer = a.getString(PEER).orEmpty()
        val v = DialogSecurityCodeBinding.inflate(LayoutInflater.from(ctx))
        // Worked out now, from the group on the radio: a key is never taken from anywhere else.
        val r = Core.router
        val name = r?.let { Ui.nameOf(it, peer, a.getString(NAME).orEmpty()) } ?: a.getString(NAME).orEmpty()
        val theirs = r?.keyOf(peer)?.takeIf { Crypto.isNodeId(peer) }
        val number = if (r == null || theirs == null) null else Crypto.safetyNumber(r.me.keys.pubB64, theirs)
        v.names.text = getString(R.string.verify_names, Core.store.name, name)
        if (number == null) {
            v.number.isVisible = false
            v.verified.isVisible = false
            v.explain.text = getString(R.string.verify_not_yet, name)
        } else {
            val groups = number.split(' ')
            // Two rows of three, the way people read them out; TalkBack hears it digit by digit.
            v.number.text = groups.chunked(3).joinToString("\n") { it.joinToString("  ") }
            v.number.contentDescription = groups.joinToString(", ") { it.toCharArray().joinToString(" ") }
            v.explain.text = getString(R.string.verify_explain, name)
            v.verified.isChecked = Core.store.isVerified(peer)
            v.verified.setOnCheckedChangeListener { _, on -> Core.store.setVerified(peer, on); Core.changed() }
        }
        return AlertDialog.Builder(ctx).setTitle(R.string.verify_title).setView(v.root)
            .setPositiveButton(R.string.done, null).create()
    }

    companion object {
        private const val PEER = "peer"
        private const val NAME = "name"
        private const val TAG = "securityCode"

        /** For [peer], who goes by [name] where it was opened (their name if the radio doesn't know it). */
        fun show(host: FragmentActivity, peer: String, name: String) {
            if (host.isFinishing || host.isDestroyed) return
            val fm = host.supportFragmentManager
            // Never after onSaveInstanceState (it would throw), never two from a double tap.
            if (fm.isStateSaved || fm.findFragmentByTag(TAG) != null) return
            SecurityCodeDialog().apply { arguments = bundleOf(PEER to peer, NAME to name) }.show(fm, TAG)
        }
    }
}
