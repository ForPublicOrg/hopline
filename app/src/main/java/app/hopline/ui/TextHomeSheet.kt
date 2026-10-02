package app.hopline.ui

import android.app.Activity
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.location.Location
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.SmsText
import app.hopline.databinding.ItemNetChipBinding
import app.hopline.databinding.SheetTextHomeBinding
import app.hopline.mesh.Errand
import app.hopline.service.Cell
import app.hopline.service.Core
import app.hopline.service.Locations
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Text home": an "I'm OK" SMS (or an email) to someone who hasn't heard from you, carried by the
 * group to whichever phone gets even one bar of signal — or, when this phone has service itself,
 * simply opened in its own Messages app. A fragment, so a half-written text survives rotation and
 * a trip to the contact picker even if Android kills the app meanwhile.
 */
class TextHomeSheet : BottomSheetDialogFragment() {
    private var _b: SheetTextHomeBinding? = null
    private val b get() = _b!!

    private var addLoc = false
    private var watchFix: Location? = null
    private var stopWatch: (() -> Unit)? = null
    /** Chips and the fields follow each other; this stops them chasing each other in a loop. */
    private var syncing = false

    private val pickContact = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data
        if (res.resultCode == Activity.RESULT_OK && uri != null) readContact(uri)
    }

    private val locPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (_b == null) return@registerForActivityResult
        if (grants.values.any { it }) startFix()
        else { addLoc = false; b.addLoc.isChecked = false; toast(getString(R.string.loc_denied)) }
        updateLoc(); updateCounter()
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog =
        super.onCreateDialog(savedInstanceState).also {
            // The keyboard must push the sheet up, never cover the message box. Deprecated on 30+
            // in favour of insets, but still exactly what a dialog window needs on 8–14.
            @Suppress("DEPRECATION")
            it.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _b = SheetTextHomeBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        addLoc = savedInstanceState?.getBoolean(S_LOC) ?: false
        if (savedInstanceState == null) b.msg.setText(getString(R.string.net_th_default, Core.store.name))
        b.addLoc.isChecked = addLoc
        buildChips()
        // Two taps: the person you texted last is already chosen.
        if (savedInstanceState == null) Core.store.homeContacts().firstOrNull()?.let { (name, to) -> fill(name, to) }

        b.to.doAfterTextChanged {
            b.toLayout.error = null
            if (!syncing) syncChips()
            updateCounter(); updateButtons()
        }
        b.msg.doAfterTextChanged { b.msgLayout.error = null; updateCounter() }
        b.addLoc.setOnCheckedChangeListener { _, on -> if (on != addLoc) onLocToggled(on) }
        b.sendGroup.setOnClickListener { sendViaGroup() }
        b.sendMine.setOnClickListener { sendFromMine() }
        updateLoc(); updateCounter(); updateButtons()
    }

    override fun onViewStateRestored(savedInstanceState: Bundle?) {
        super.onViewStateRestored(savedInstanceState)
        // Typed text came back on its own; put the chosen chip back in step with it.
        if (_b != null) { syncChips(); updateCounter(); updateButtons(); updateLoc() }
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BottomSheetDialog)?.behavior?.apply { skipCollapsed = true; state = BottomSheetBehavior.STATE_EXPANDED }
        if (addLoc && freshFix() == null) startFix()
        updateLoc(); updateButtons()
    }

    override fun onStop() {
        super.onStop()
        stopFix()   // no GPS while the sheet isn't on screen
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(S_LOC, addLoc)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopFix()
        _b = null
    }

    // ------------------------------------------------------------------ who

    private fun buildChips() {
        b.chips.removeAllViews()
        for ((name, to) in Core.store.homeContacts()) {
            val label = name.ifEmpty { to }
            val chip = ItemNetChipBinding.inflate(layoutInflater, b.chips, false).root
            chip.text = label
            chip.tag = to
            chip.isCheckable = true
            // Which one is chosen follows the "to" field (restored on its own), never a stale chip state.
            chip.isSaveEnabled = false
            chip.contentDescription = getString(R.string.net_th_contact_desc, label)
            chip.setOnCheckedChangeListener { _, on -> if (on && !syncing) fill(name, to) }
            chip.setOnLongClickListener { confirmRemove(label, to); true }
            b.chips.addView(chip)
        }
        val pick = ItemNetChipBinding.inflate(layoutInflater, b.chips, false).root
        pick.text = getString(R.string.net_th_pick)
        pick.isCheckable = false
        pick.isChipIconVisible = true
        pick.setChipIconResource(R.drawable.ic_people)
        pick.setOnClickListener {
            try { pickContact.launch(Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)) }
            catch (x: ActivityNotFoundException) { toast(getString(R.string.net_th_no_contacts_app)) }
        }
        b.chips.addView(pick)
    }

    private fun fill(name: String, to: String) {
        syncing = true
        b.to.setText(to)
        b.to.setSelection(b.to.length())
        b.name.setText(if (name == to) "" else name)
        syncing = false
        syncChips()
        updateCounter(); updateButtons()
    }

    /** The chip for whoever is in the "to" field is the chosen one; typing someone new clears it. */
    private fun syncChips() {
        val to = normalised(b.to.text.toString())
        syncing = true
        for (i in 0 until b.chips.childCount) {
            val c = b.chips.getChildAt(i) as? Chip ?: continue
            if (c.isCheckable) c.isChecked = c.tag == to
        }
        syncing = false
    }

    private fun normalised(raw: String): String {
        val t = raw.trim()
        return if (t.contains('@')) t else SmsText.normaliseNumber(t) ?: t
    }

    private fun confirmRemove(label: String, to: String) {
        val ctx = context ?: return
        AlertDialog.Builder(ctx)
            .setMessage(getString(R.string.net_th_remove_contact, label))
            .setPositiveButton(R.string.net_th_remove) { _, _ ->
                Core.store.removeHomeContact(to)
                if (_b != null) { buildChips(); syncChips() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** The system picker hands us just the one number it showed, with a temporary grant — no contacts permission. */
    private fun readContact(uri: Uri) {
        if (_b == null) return
        var number = ""
        var name = ""
        try {
            requireContext().contentResolver.query(uri,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER, ContactsContract.Contacts.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) { number = c.getString(0).orEmpty().trim(); name = c.getString(1).orEmpty() }
            }
        } catch (x: Exception) { /* a picker that returned something we may not read */ }
        if (number.isEmpty()) { toast(getString(R.string.net_th_contact_no_number)); return }
        fill(Names.clean(name, 40), SmsText.normaliseNumber(number) ?: number)
    }

    // ------------------------------------------------------------------ where

    private fun freshFix(): Location? {
        val ctx = context ?: return null
        val l = watchFix ?: Locations.lastKnown(ctx)
        return l?.takeIf { System.currentTimeMillis() - it.time <= FIX_FRESH_MS }
    }

    private fun onLocToggled(on: Boolean) {
        addLoc = on
        val ctx = context ?: return
        if (on) {
            if (!Locations.granted(ctx)) {
                try { locPermission.launch(Locations.toRequest()) } catch (x: Exception) { addLoc = false; b.addLoc.isChecked = false }
            } else if (freshFix() == null) startFix()
        } else stopFix()
        updateLoc(); updateCounter()
    }

    private fun startFix() {
        val ctx = context ?: return
        if (stopWatch != null || !Locations.granted(ctx) || !Locations.serviceOn(ctx)) return
        stopWatch = Locations.watch(ctx.applicationContext) { l ->
            watchFix = l
            stopFix()
            if (_b != null) { updateLoc(); updateCounter() }
        }
    }

    private fun stopFix() { stopWatch?.invoke(); stopWatch = null }

    private fun updateLoc() {
        val ctx = context ?: return
        val fix = freshFix()
        b.locSub.text = when {
            fix != null -> getString(R.string.net_th_loc_ready, Ui.ago(fix.time))
            !addLoc -> getString(R.string.net_th_loc_off_hint)
            stopWatch != null && Locations.serviceOn(ctx) -> getString(R.string.net_th_loc_finding)
            else -> getString(R.string.net_th_loc_none)
        }
    }

    // ------------------------------------------------------------------ what

    private fun isEmail() = b.to.text.toString().contains('@')

    /** The text exactly as it will be sent: the message, an optional map link, and when it was written. */
    private fun composeText(): String {
        val sb = StringBuilder(b.msg.text.toString().trim())
        if (addLoc) freshFix()?.let {
            sb.append('\n').append(getString(R.string.net_th_near, String.format(Locale.US, "https://maps.google.com/?q=%.4f,%.4f", it.latitude, it.longitude)))
        }
        val now = Date()
        val stamp = android.text.format.DateFormat.getTimeFormat(requireContext()).format(now) + ", " +
            SimpleDateFormat("d MMM", Locale.getDefault()).format(now)
        sb.append('\n').append(getString(R.string.net_th_written, stamp))
        // Curly quotes and dashes would switch the SMS to the costly alphabet and halve what fits.
        return if (isEmail()) sb.toString() else SmsText.toGsm(sb.toString())
    }

    /** "1 SMS · 96/160" — the count the carrier bills, so the helper's phone sends no surprises. */
    private fun updateCounter() {
        val ctx = context ?: return
        val text = composeText()
        if (isEmail()) {
            b.counter.text = getString(R.string.net_th_counter_mail, text.length, MAX_MAIL)
            b.counter.setTextColor(ctx.getColor(if (text.length > MAX_MAIL) R.color.danger else R.color.text_muted))
            return
        }
        val segs = SmsText.segments(text)
        if (segs > SmsText.MAX_SEGMENTS) {
            b.counter.text = getString(R.string.net_th_counter_too_long, segs, SmsText.MAX_SEGMENTS)
            b.counter.setTextColor(ctx.getColor(R.color.danger))
            return
        }
        b.counter.text = getString(R.string.net_th_counter_sms, segs, SmsText.units(text), SmsText.capacity(text))
        b.counter.setTextColor(ctx.getColor(R.color.text_muted))
    }

    /** "Send from my phone" only when this phone really could: mobile service for a text, data for an email. */
    private fun updateButtons() {
        val ctx = context ?: return
        b.sendMine.isVisible = if (isEmail()) Core.internetNow() else Cell.canText(ctx)
    }

    private class Target(val to: String, val name: String, val email: Boolean, val text: String)

    private fun validated(): Target? {
        val raw = b.to.text.toString().trim()
        val email = raw.contains('@')
        val to = when {
            raw.isEmpty() -> null
            email -> raw.takeIf { SmsText.looksLikeEmail(it) }
            else -> SmsText.normaliseNumber(raw)
        }
        if (to == null) {
            b.toLayout.error = getString(when { raw.isEmpty() -> R.string.net_th_err_to; email -> R.string.net_th_err_email; else -> R.string.net_th_err_number })
            NetText.rejectHaptic(b.to)
            b.to.requestFocus()
            return null
        }
        if (b.msg.text.toString().isBlank()) {
            b.msgLayout.error = getString(R.string.net_th_err_msg)
            NetText.rejectHaptic(b.msg)
            b.msg.requestFocus()
            return null
        }
        val text = composeText()
        val tooLong = if (email) text.length > MAX_MAIL else SmsText.segments(text) > SmsText.MAX_SEGMENTS
        if (tooLong) {
            b.msgLayout.error = if (email) getString(R.string.net_th_err_long_mail, MAX_MAIL) else getString(R.string.net_th_err_long, SmsText.MAX_SEGMENTS)
            NetText.rejectHaptic(b.msg)
            return null
        }
        return Target(to, Names.clean(b.name.text.toString(), 40), email, text)
    }

    private fun subject(): String = getString(R.string.net_th_subject, Core.store.name.ifEmpty { getString(R.string.app_name) })

    private fun sendViaGroup() {
        val t = validated() ?: return
        if (Core.router == null) { toast(getString(R.string.net_starting)); return }
        Core.requestProblem()?.let { NetText.rejectHaptic(b.sendGroup); toast(it); return }
        val args = JSONObject().put("to", t.to).put("text", t.text)
        if (t.name.isNotEmpty()) args.put("name", t.name)
        if (t.email) args.put("subj", subject())
        val e = Core.requestErrand(Errand.SEND, args) ?: run {
            NetText.rejectHaptic(b.sendGroup)
            toast(Core.requestProblem() ?: getString(R.string.net_too_long))
            return
        }
        Core.store.saveHomeContact(t.name.ifEmpty { t.to }, t.to)
        val host = activity as? InternetActivity
        dismiss()
        host?.onRequested(e)
    }

    private fun sendFromMine() {
        val t = validated() ?: return
        val intent = if (t.email) NetText.mailIntent(t.to, subject(), t.text) else NetText.smsIntent(t.to, t.text)
        try { startActivity(intent) } catch (x: ActivityNotFoundException) { toast(getString(R.string.net_th_no_sms_app)); return }
        Core.store.saveHomeContact(t.name.ifEmpty { t.to }, t.to)
        dismiss()
    }

    private fun toast(text: String) {
        context?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val TAG = "textHome"
        private const val S_LOC = "addLoc"
        private const val FIX_FRESH_MS = 15 * 60_000L
        private const val MAX_MAIL = 1000
    }
}
