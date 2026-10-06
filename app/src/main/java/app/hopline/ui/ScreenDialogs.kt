package app.hopline.ui

import android.app.Dialog
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.Words
import app.hopline.data.GroupRules
import app.hopline.data.SavedGroup
import app.hopline.databinding.DialogScreenPromptBinding
import app.hopline.mesh.RoleChange
import app.hopline.mesh.Router
import app.hopline.service.Core
import app.hopline.service.Notifications
import app.hopline.service.Permissions
import java.util.Locale

/**
 * The prompts the screens raise — a name to type, a yes/no, a pick-one, a list of options — as
 * DialogFragments, so rotating the phone (or Android killing the app behind a dialog) never loses
 * one: the fragment comes back by itself with what was typed, and answers through a fragment
 * result the screen listens for in onCreate. A plain AlertDialog would just vanish, half-typed
 * name and all.
 */
class ScreenDialog : DialogFragment() {
    private var prompt: DialogScreenPromptBinding? = null
    private var radios: RadioGroup? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val a = requireArguments()
        val ctx = requireContext()
        val builder = AlertDialog.Builder(ctx)
        a.getString(TITLE)?.let { builder.setTitle(it) }
        when (a.getString(KIND)) {
            TEXT -> builder.setView(textView(a, savedInstanceState))
            CHOICE -> builder.setView(choiceView(a, savedInstanceState))
            LIST -> builder.setItems(a.getStringArrayList(ITEMS).orEmpty().toTypedArray()) { _, i -> answer(DialogInterface.BUTTON_POSITIVE, item = i) }
            else -> a.getString(MESSAGE)?.let { builder.setMessage(it) }
        }
        // A list answers by its items alone: no OK, no Cancel (a tap outside or Back closes it).
        if (a.getString(KIND) != LIST) {
            // Our own click handlers (below) decide when to close, so a validation error keeps it open.
            builder.setPositiveButton(a.getString(OK), null)
            if (!a.getBoolean(NO_CANCEL)) builder.setNegativeButton(a.getString(CANCEL) ?: getString(R.string.cancel), null)
        }
        a.getString(NEUTRAL)?.let { builder.setNeutralButton(it, null) }
        val dialog = builder.create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.let { ok ->
                ok.setOnClickListener { answer(DialogInterface.BUTTON_POSITIVE) }
                if (a.getBoolean(DANGER)) ok.setTextColor(ctx.getColor(R.color.danger))
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener { answer(DialogInterface.BUTTON_NEUTRAL) }
        }
        if (a.getString(KIND) == TEXT) dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        return dialog
    }

    private fun textView(a: Bundle, saved: Bundle?): View {
        val v = DialogScreenPromptBinding.inflate(LayoutInflater.from(requireContext()))
        prompt = v
        a.getString(MESSAGE)?.let { v.promptMessage.text = it; v.promptMessage.visibility = View.VISIBLE }
        v.promptLayout.hint = a.getString(HINT)
        v.promptInput.inputType = a.getInt(INPUT, InputType.TYPE_CLASS_TEXT)
        val max = a.getInt(MAX, 0)
        if (max > 0) {
            v.promptLayout.counterMaxLength = max
            v.promptInput.filters = arrayOf(InputFilter.LengthFilter(max))
        } else v.promptLayout.isCounterEnabled = false
        val text = saved?.getString(TYPED) ?: a.getString(PREFILL).orEmpty()
        v.promptInput.setText(text)
        v.promptInput.setSelection(v.promptInput.length())
        v.promptInput.doAfterTextChanged { v.promptLayout.error = null }
        v.promptInput.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { answer(DialogInterface.BUTTON_POSITIVE); true } else false
        }
        v.promptInput.requestFocus()
        return v.root
    }

    private fun choiceView(a: Bundle, saved: Bundle?): View {
        val ctx = requireContext()
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
        }
        a.getString(MESSAGE)?.let {
            root.addView(TextView(ctx).apply { text = it; setTextAppearance(R.style.Text_Muted); textSize = 14f; setPadding(0, 0, 0, (8 * d).toInt()) })
        }
        val group = RadioGroup(ctx).apply { isSaveEnabled = false }   // the picked index is saved by hand below
        val picked = saved?.getInt(PICKED, -1)?.takeIf { it >= 0 } ?: a.getInt(PICKED, 0)
        a.getStringArrayList(ITEMS).orEmpty().forEachIndexed { i, label ->
            group.addView(RadioButton(ctx).apply {
                id = View.generateViewId(); tag = i; text = label; textSize = 16f
                minHeight = (48 * d).toInt(); isSaveEnabled = false
            })
            if (i == picked) group.check(group.getChildAt(i).id)
        }
        radios = group
        root.addView(group)
        return root
    }

    private fun pickedIndex(): Int {
        val g = radios ?: return 0
        return (g.findViewById<View>(g.checkedRadioButtonId)?.tag as? Int) ?: 0
    }

    /** [item]: the list item tapped (a LIST answers by that alone). */
    private fun answer(which: Int, item: Int = -1) {
        val a = requireArguments()
        val out = Bundle(a.getBundle(DATA) ?: Bundle())
        out.putInt(WHICH, which)
        when (a.getString(KIND)) {
            TEXT -> {
                val v = prompt ?: return
                val text = v.promptInput.text?.toString()?.trim().orEmpty()
                problem(a.getString(RULE), text)?.let { v.promptLayout.error = it; return }
                out.putString(VALUE, text)
            }
            CHOICE -> out.putInt(INDEX, pickedIndex())
            LIST -> out.putInt(INDEX, item)
        }
        // Something only to read has no answer to deliver (and nobody listening for one).
        if (!a.getBoolean(NO_CANCEL)) parentFragmentManager.setFragmentResult(a.getString(KEY) ?: return, out)
        dismissAllowingStateLoss()
    }

    private fun problem(rule: String?, text: String): String? = when (rule) {
        RULE_PERSON -> if (Asks.validPersonName(text)) null else getString(R.string.name_too_short)
        RULE_GROUP -> if (Names.clean(text, Names.MAX_GROUP).isEmpty()) getString(R.string.group_name_empty) else null
        else -> null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        prompt?.let { outState.putString(TYPED, it.promptInput.text?.toString().orEmpty()) }
        radios?.let { outState.putInt(PICKED, pickedIndex()) }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        prompt = null; radios = null
    }

    companion object {
        /** In every answer: which button (DialogInterface.BUTTON_POSITIVE / BUTTON_NEUTRAL). */
        const val WHICH = "which"
        /** A text prompt's answer, trimmed. */
        const val VALUE = "value"
        /** A choice or list prompt's answer: the picked item's position. */
        const val INDEX = "index"
        const val RULE_PERSON = "person"
        const val RULE_GROUP = "group"

        private const val KEY = "key"; private const val KIND = "kind"; private const val TITLE = "title"
        private const val MESSAGE = "message"; private const val OK = "ok"; private const val CANCEL = "cancel"
        private const val NEUTRAL = "neutral"; private const val DANGER = "danger"; private const val DATA = "data"
        private const val HINT = "hint"; private const val PREFILL = "prefill"; private const val MAX = "max"
        private const val RULE = "rule"; private const val INPUT = "input"; private const val ITEMS = "items"
        private const val PICKED = "picked"; private const val TYPED = "typed"; private const val NO_CANCEL = "noCancel"
        private const val TEXT = "text"; private const val CONFIRM = "confirm"; private const val CHOICE = "choice"
        private const val LIST = "list"

        /** A one-field prompt. [rule] (RULE_PERSON / RULE_GROUP) keeps it open with an inline error until the text is usable. */
        fun text(host: FragmentActivity, key: String, title: String, hint: String, prefill: String, ok: String,
                 message: String? = null, maxLength: Int = 0, rule: String? = null,
                 inputType: Int = InputType.TYPE_CLASS_TEXT, data: Bundle? = null) =
            show(host, key, bundleOf(KIND to TEXT, TITLE to title, HINT to hint, PREFILL to prefill, OK to ok, MESSAGE to message,
                MAX to maxLength, RULE to rule, INPUT to inputType, DATA to data))

        /** A question with a yes (and optionally a third choice). [danger] paints the yes red. */
        fun confirm(host: FragmentActivity, key: String, title: String?, message: String, ok: String,
                    danger: Boolean = false, neutral: String? = null, cancel: String? = null, data: Bundle? = null) =
            show(host, key, bundleOf(KIND to CONFIRM, TITLE to title, MESSAGE to message, OK to ok, DANGER to danger,
                NEUTRAL to neutral, CANCEL to cancel, DATA to data))

        /** Something to read, with just an OK. */
        fun info(host: FragmentActivity, key: String, title: String, message: String, ok: String) =
            show(host, key, bundleOf(KIND to CONFIRM, TITLE to title, MESSAGE to message, OK to ok, NO_CANCEL to true))

        /** Pick one of [items] (radio buttons under an explainer line). */
        fun choice(host: FragmentActivity, key: String, title: String, message: String?, items: List<String>, ok: String,
                   picked: Int = 0, data: Bundle? = null) =
            show(host, key, bundleOf(KIND to CHOICE, TITLE to title, MESSAGE to message, ITEMS to ArrayList(items), OK to ok,
                PICKED to picked, DATA to data))

        /** Pick one of [items] by tapping it — a plain list, WhatsApp's per-person options. [title] may be null. */
        fun list(host: FragmentActivity, key: String, title: String?, items: List<String>, data: Bundle? = null) =
            show(host, key, bundleOf(KIND to LIST, TITLE to title, ITEMS to ArrayList(items), DATA to data))

        private fun show(host: FragmentActivity, key: String, args: Bundle) {
            if (host.isFinishing || host.isDestroyed) return
            val fm = host.supportFragmentManager
            // Never after onSaveInstanceState (it would throw), never two of the same from a double tap.
            if (fm.isStateSaved || fm.findFragmentByTag(key) != null) return
            args.putString(KEY, key)
            ScreenDialog().apply { arguments = args }.show(fm, key)
        }

        /** Answers for [key] arrive here, on the main thread while [host] is started — also after a rotation. */
        fun listen(host: FragmentActivity, key: String, onAnswer: (Bundle) -> Unit) =
            host.supportFragmentManager.setFragmentResultListener(key, host) { _, b -> onAnswer(b) }
    }
}

/**
 * The prompts several screens share — my name, the group's name, mute, clear, leave, rejoin,
 * delete, and an admin's options for a member and for who can send — so each one behaves the same
 * wherever it is opened from. Every answer carries the group it was asked about, and is checked
 * against what that group is NOW: if the radio moved to another group meanwhile, or the group was
 * left, rejoined or deleted while the question stood, the answer is dropped, never misapplied.
 */
object Asks {
    private const val MY_NAME = "ask.myName"
    private const val GROUP_NAME = "ask.groupName"
    private const val LEAVE = "ask.leave"
    private const val REJOIN = "ask.rejoin"
    private const val DELETE = "ask.deleteGroup"
    private const val MUTE = "ask.mute"
    private const val CLEAR = "ask.clear"
    private const val MEMBER = "ask.member"
    private const val DISMISS = "ask.dismissAdmin"
    private const val SEND_POLICY = "ask.sendPolicy"
    private const val FP = "fp"
    private const val CHAT = "chat"
    private const val CODE = "code"
    private const val WHO = "who"
    private const val ACTS = "acts"
    private const val HEIR = "heir"

    private const val HOUR = 3_600_000L

    /** Call once in onCreate (after setContentView): the shared prompts answer through here. */
    fun listen(a: AppCompatActivity) {
        ScreenDialog.listen(a, MY_NAME) { b -> savedMyName(a, b.getString(ScreenDialog.VALUE).orEmpty()) }
        ScreenDialog.listen(a, GROUP_NAME) { b -> savedGroupName(a, b) }
        ScreenDialog.listen(a, LEAVE) { b -> leaveNow(a, b) }
        ScreenDialog.listen(a, REJOIN) { b -> rejoinNow(a, b.getString(CODE)) }
        ScreenDialog.listen(a, DELETE) { b -> deleteNow(a, b.getString(CODE)) }
        ScreenDialog.listen(a, MUTE) { b -> muteNow(b) }
        ScreenDialog.listen(a, CLEAR) { b -> clearNow(a, b) }
        ScreenDialog.listen(a, MEMBER) { b -> memberNow(a, b) }
        ScreenDialog.listen(a, DISMISS) { b -> dismissNow(a, b) }
        ScreenDialog.listen(a, SEND_POLICY) { b -> sendPolicyNow(a, b) }
    }

    /**
     * A name people can tell apart: at least two characters after cleaning, and not just dots or
     * dashes. Letters in any script, digits and emoji all count.
     */
    fun validPersonName(raw: String): Boolean {
        val c = Names.clean(raw, Names.MAX_PERSON)
        if (c.codePointCount(0, c.length) < 2) return false
        return c.codePoints().anyMatch { cp ->
            Character.isLetterOrDigit(cp) || Character.getType(cp) == Character.OTHER_SYMBOL.toInt() || Character.isSupplementaryCodePoint(cp)
        }
    }

    /** A saved group's name as people see it; its code's words when it has no name yet. */
    fun groupLabel(g: SavedGroup): String = g.name.ifEmpty { Words.pretty(g.code) }

    private fun toast(a: AppCompatActivity, text: String, long: Boolean = false) =
        Toast.makeText(a, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

    /** The group's router on the radio — or null, once the person has been told it is still starting. */
    private fun running(a: AppCompatActivity): Router? = Core.router ?: run { toast(a, a.getString(R.string.starting_try_again)); null }

    /** The group an answer was asked about ([FP]), while it is still the one on the radio; null once another is. */
    private fun sameGroup(b: Bundle): String? = b.getString(FP)?.takeIf { it == Core.fingerprint() }

    // ------------------------------------------------------------------ my name

    fun myName(a: AppCompatActivity) = ScreenDialog.text(a, MY_NAME, a.getString(R.string.your_name), a.getString(R.string.name_hint),
        Core.store.name, a.getString(R.string.save), message = a.getString(R.string.name_change_explain),
        maxLength = Names.MAX_PERSON, rule = ScreenDialog.RULE_PERSON,
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PERSON_NAME or InputType.TYPE_TEXT_FLAG_CAP_WORDS)

    private fun savedMyName(a: AppCompatActivity, raw: String) {
        if (!Core.setMyName(raw)) { toast(a, a.getString(R.string.name_too_short)); return }
        // Sharing a name is allowed (two Rahuls happen), but say what everyone will see.
        val r = Core.router ?: return
        val me = r.me.name.lowercase(Locale.ROOT)
        // The same people Ui.uniqueName tells apart: who left lately included.
        if (r.recentPeopleList().any { it.id != r.me.id && it.name.lowercase(Locale.ROOT) == me })
            toast(a, a.getString(R.string.name_shared_warning, r.me.name), long = true)
    }

    // ------------------------------------------------------------------ the group's name

    fun groupName(a: AppCompatActivity) {
        val r = running(a) ?: return
        ScreenDialog.text(a, GROUP_NAME, a.getString(R.string.rename_group), a.getString(R.string.group_name_hint), r.group.name,
            a.getString(R.string.save), message = a.getString(R.string.group_rename_explain), maxLength = Names.MAX_GROUP,
            rule = ScreenDialog.RULE_GROUP, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            data = bundleOf(FP to r.group.fingerprint))
    }

    private fun savedGroupName(a: AppCompatActivity, b: Bundle) {
        val r = running(a) ?: return
        if (b.getString(FP) != r.group.fingerprint) return
        val name = Names.clean(b.getString(ScreenDialog.VALUE), Names.MAX_GROUP)
        if (name.isEmpty() || name == r.group.name) return
        if (!Core.renameGroup(name)) toast(a, a.getString(R.string.group_rename_failed))
    }

    // ------------------------------------------------------------------ leave, rejoin, delete a group

    /**
     * A fresh Home with nothing of the old state underneath — the chat or info screen of a group
     * that was just left, rejoined or deleted must not be there to come back to. With no group
     * saved any more, the launch screen instead: it leads to "Start or join".
     */
    private fun goHome(a: AppCompatActivity) {
        val next = if (Core.store.allGroups().isEmpty()) LaunchActivity::class.java else HomeActivity::class.java
        a.startActivity(Intent(a, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        // An invite link opens the join screen in the task it was tapped in, which the flags don't reach.
        a.finish()
    }

    /**
     * Leave a group this phone is in: the one on the radio, or a paused one (without waking it
     * first). Leaving keeps the chat, and the question says so; it says whether the group will hear
     * of it (only phones linked now can take the goodbye — Core.leaveGroup); and when the radio
     * will move on to another group, it says which. The only admin around is told who will be made
     * admin on the way out (Core.leaveAdmin), or that nobody can be — and the answer names that heir.
     */
    fun leave(a: AppCompatActivity, g: SavedGroup) {
        if (g.left) return
        val store = Core.store
        val next = if (g.code != store.activeCode) null
            else GroupRules.nextActive(store.allGroups(), excluding = g.code)?.let { code -> store.groups().firstOrNull { it.code == code } }
        val heard = Core.router?.takeIf { it.group.code == g.code }?.authedLinks()?.isNotEmpty() == true
        val admin = Core.leaveAdmin(g)
        val adminLine = when {
            admin?.heir != null -> a.getString(R.string.leave_admin_heir, admin.heirName)
            admin != null -> a.getString(if (admin.locked) R.string.leave_admin_nobody_locked else R.string.leave_admin_nobody)
            // A paused group can't hand anything on; said only to the phone that started it.
            g.code != store.activeCode && GroupRules.founds(g, store.nodeId) -> a.getString(R.string.leave_admin_paused)
            else -> null
        }
        val body = listOfNotNull(a.getString(R.string.leave_confirm), adminLine,
            a.getString(if (heard) R.string.leave_confirm_told else R.string.leave_confirm_untold),
            next?.let { a.getString(R.string.leave_confirm_next, groupLabel(it)) })
            .joinToString("\n\n")
        ScreenDialog.confirm(a, LEAVE, a.getString(R.string.leave_title, groupLabel(g)), body, a.getString(R.string.leave),
            danger = true, data = bundleOf(CODE to g.code, HEIR to admin?.heir))
    }

    private fun leaveNow(a: AppCompatActivity, b: Bundle) {
        // Still a group this phone is in? One left or deleted while the question stood needs nothing.
        val g = Core.store.groups().firstOrNull { it.code == b.getString(CODE) } ?: return
        val wasActive = g.code == Core.store.activeCode
        // The group on the radio is still starting (the app was killed behind this question): leaving now would skip its
        // goodbye, and the handover the question may have promised.
        if (wasActive && Core.router == null && Core.buildPending) { toast(a, a.getString(R.string.starting_try_again)); return }
        // Refused: the chat could not be saved first, so nothing was let go. Never "its chat is
        // still on this phone" for a leave that would have cost the latest messages.
        if (!Core.leaveGroup(g.code, b.getString(HEIR))) { toast(a, a.getString(R.string.leave_not_saved), long = true); return }
        if (!Core.store.isLeft(g.code)) return
        // Whatever the question said when it was asked: links come and go while it stands.
        val handed = Core.handedAdminName
        toast(a, when {
            handed != null -> a.getString(R.string.left_toast_handed, groupLabel(g), handed)
            Core.toldLeaving -> a.getString(R.string.left_toast, groupLabel(g))
            else -> a.getString(R.string.left_toast_untold, groupLabel(g))
        }, long = true)
        // Every screen under this one belonged to the group on the radio. A paused group has none: Home just redraws.
        if (wasActive) goHome(a)
    }

    /**
     * Join a group this phone left again. One question, wherever it is asked — Home, the group's
     * info, its chat, or its code typed, scanned or tapped as a link — and never a silent way back
     * in: the radio goes to that group, and the group it is on now (named, if there is one) is paused.
     */
    fun rejoin(a: AppCompatActivity, g: SavedGroup) {
        if (!g.left) return
        val paused = Core.store.activeGroup()
        val body = listOfNotNull(a.getString(R.string.rejoin_body), paused?.let { a.getString(R.string.rejoin_body_switch, groupLabel(it)) })
            .joinToString("\n\n")
        ScreenDialog.confirm(a, REJOIN, a.getString(R.string.rejoin_title, groupLabel(g)), body, a.getString(R.string.rejoin),
            data = bundleOf(CODE to g.code))
    }

    private fun rejoinNow(a: AppCompatActivity, code: String?) {
        // Only a group that is still left: one deleted meanwhile is gone, one already rejoined is in.
        val g = Core.store.leftGroups().firstOrNull { it.code == code } ?: return
        if (!radioAllowed(a, rejoin = g.code)) return
        finishRejoin(a, g)
    }

    private fun finishRejoin(a: AppCompatActivity, g: SavedGroup): Boolean {
        // False: the tidy-up after leaving is still being written, or the rejoin couldn't be saved.
        // Nothing has changed, and in a moment it works.
        if (!Core.rejoinGroup(g.code)) { toast(a, a.getString(R.string.rejoin_busy)); return false }
        toast(a, a.getString(R.string.rejoined_toast, groupLabel(g)))
        goHome(a)
        return true
    }

    /**
     * The permissions screen, opened by [radioAllowed] in the middle of a rejoin the person had
     * already said yes to, finishes that rejoin once the permission is there — they are not asked
     * the same question twice. False (nothing changed) when the group is no longer a left one, the
     * permission still isn't given, or the rejoin can't be saved right now.
     */
    fun rejoinAfterPermissions(a: AppCompatActivity, code: String): Boolean {
        val g = Core.store.leftGroups().firstOrNull { it.code == code } ?: return false
        if (!Permissions.allGranted(a)) return false
        return finishRejoin(a, g)
    }

    /**
     * Asked before a group is put on the radio — rejoined, started or joined — and before anything
     * is saved for it: may the radio run at all? If not, the permissions screen opens on top of
     * [a] and this is false; nothing has changed, and Back leads straight back to [a].
     *
     * It matters on a phone that only has groups it left: that phone gets to Home, and to its old
     * chats, without the permission (Android takes it back from an app left unopened). Joining
     * first and asking afterwards would put every one of those chats behind the permissions
     * screen, with no way back but to grant — and no way to undo the join.
     */
    fun radioAllowed(a: AppCompatActivity, rejoin: String? = null): Boolean {
        if (ScreenRules.beforeRadio(Permissions.allGranted(a)) == ScreenRules.Radio.GO) return true
        toast(a, a.getString(if (rejoin == null) R.string.perm_first else R.string.perm_first_rejoin), long = true)
        // [rejoin]: the left group whose rejoin was already confirmed — that screen finishes it.
        a.startActivity(Intent(a, PermissionsActivity::class.java).putExtra(PermissionsActivity.EXTRA_RETURN, true)
            .apply { if (rejoin != null) putExtra(PermissionsActivity.EXTRA_REJOIN, rejoin) })
        return false
    }

    /**
     * Delete a group this phone left, and everything of it on this phone. Only ever offered for a
     * left group — one this phone is in must be left first — so the tap that destroys a chat is
     * never the tap that takes the phone off a radio.
     */
    fun deleteGroup(a: AppCompatActivity, g: SavedGroup) {
        if (!g.left) return
        ScreenDialog.confirm(a, DELETE, a.getString(R.string.delete_group_title, groupLabel(g)), a.getString(R.string.delete_group_body),
            a.getString(R.string.menu_delete_group), danger = true, data = bundleOf(CODE to g.code))
    }

    private fun deleteNow(a: AppCompatActivity, code: String?) {
        // Only a group that is still left. One rejoined while the question stood is a group this
        // phone is in again: its chat is not deleted on an old answer.
        val g = Core.store.leftGroups().firstOrNull { it.code == code } ?: return
        Core.deleteLeftGroup(g.code)
        if (Core.store.hasGroup(g.code)) return
        toast(a, a.getString(R.string.group_deleted, groupLabel(g)))
        // Home only lists the group, and redraws without it; a screen that was showing it has nothing left to show.
        if (ScreenRules.afterDelete(onHome = a is HomeActivity, anySaved = Core.store.allGroups().isNotEmpty()) != ScreenRules.After.STAY) goHome(a)
    }

    // ------------------------------------------------------------------ mute

    /** [chat] is Core.GROUP or a person's id; [name] is who a private chat is with. */
    fun mute(a: AppCompatActivity, chat: String, name: String) {
        val fp = Core.fingerprint() ?: return
        val body = if (chat == Core.GROUP) a.getString(R.string.mute_group_body) else a.getString(R.string.mute_dm_body, name)
        ScreenDialog.choice(a, MUTE, a.getString(R.string.mute_title), body,
            listOf(a.getString(R.string.mute_8h), a.getString(R.string.mute_week), a.getString(R.string.mute_always)),
            a.getString(R.string.mute_btn), data = bundleOf(FP to fp, CHAT to chat))
    }

    private fun muteNow(b: Bundle) {
        if (b.getString(FP) != Core.fingerprint()) return
        val chat = b.getString(CHAT) ?: return
        val now = System.currentTimeMillis()
        Core.setMuted(chat, when (b.getInt(ScreenDialog.INDEX)) { 0 -> now + 8 * HOUR; 1 -> now + 7 * 24 * HOUR; else -> Long.MAX_VALUE })
    }

    /** "Until 14:20", "Until Mon 09:00", "Always", or null when not muted. */
    fun mutedLine(a: android.content.Context, chat: String): String? {
        val fp = Core.fingerprint() ?: return null
        val until = Core.store.mutedUntil(fp, chat)
        if (until <= System.currentTimeMillis()) return null
        if (until == Long.MAX_VALUE) return a.getString(R.string.mute_always_state)
        val flags = android.text.format.DateUtils.FORMAT_SHOW_TIME or
            (if (until - System.currentTimeMillis() > 20 * HOUR) android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY or
                android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY or android.text.format.DateUtils.FORMAT_SHOW_DATE or
                android.text.format.DateUtils.FORMAT_ABBREV_MONTH else 0)
        return a.getString(R.string.mute_until, android.text.format.DateUtils.formatDateTime(a, until, flags))
    }

    // ------------------------------------------------------------------ clear a chat

    /** Empty the group chat ([peer] null) or a private chat, on this phone only. */
    fun clear(a: AppCompatActivity, peer: String?, name: String) {
        val fp = Core.fingerprint() ?: return
        if (peer == null) ScreenDialog.confirm(a, CLEAR, a.getString(R.string.clear_group_title), a.getString(R.string.clear_group_body, name),
            a.getString(R.string.menu_clear_chat), danger = true, data = bundleOf(FP to fp))
        else ScreenDialog.confirm(a, CLEAR, a.getString(R.string.delete_dm_title, name), a.getString(R.string.delete_dm_body, name),
            a.getString(R.string.menu_delete_chat), danger = true, data = bundleOf(FP to fp, CHAT to peer))
    }

    private fun clearNow(a: AppCompatActivity, b: Bundle) {
        if (b.getString(FP) != Core.fingerprint() || Core.router == null) return
        val peer = b.getString(CHAT)
        Core.clearChat(peer)
        toast(a, a.getString(if (peer == null) R.string.chat_cleared else R.string.chat_deleted))
    }

    // ------------------------------------------------------------------ group admins

    /**
     * An admin's tap on someone in Group info or People: what they can do for that person, in
     * WhatsApp's order (AdminRules.memberActions). Anyone else's tap — or an admin's who isn't one
     * any more — opens the private chat, as it always did.
     */
    fun member(a: AppCompatActivity, who: String) {
        val r = running(a) ?: return
        if (!AdminRules.tapOpensOptions(r.isAdmin(r.me.id), who, r.me.id)) { openChat(a, who); return }
        val p = r.people[who]
        val founder = who == r.founder()
        val acts = AdminRules.memberActions(true, r.isAdmin(who), founder, p?.left == true)
        val name = Ui.uniqueName(r, who, p?.name.orEmpty())
        val labels = acts.map {
            when (it) {
                AdminRules.MemberAction.MESSAGE -> a.getString(if (p?.left == true) R.string.member_open_chat else R.string.member_message, name)
                AdminRules.MemberAction.MAKE_ADMIN -> a.getString(R.string.member_make_admin)
                AdminRules.MemberAction.DISMISS_ADMIN -> a.getString(R.string.member_dismiss_admin)
                AdminRules.MemberAction.VERIFY -> a.getString(R.string.verify_title)
            }
        }
        // The one admin with no "Dismiss" says why. No name: a dialog title stops at two lines, and the first item names them.
        val title = if (founder) a.getString(R.string.member_founder_note) else null
        ScreenDialog.list(a, MEMBER, title, labels, bundleOf(FP to r.group.fingerprint, WHO to who, ACTS to ArrayList(acts.map { it.name })))
    }

    private fun memberNow(a: AppCompatActivity, b: Bundle) {
        val fp = sameGroup(b) ?: return                                        // another group on the radio now: dropped
        val r = running(a) ?: return
        val who = b.getString(WHO) ?: return
        when (b.getStringArrayList(ACTS)?.getOrNull(b.getInt(ScreenDialog.INDEX))?.let { AdminRules.MemberAction.valueOf(it) }) {
            AdminRules.MemberAction.MESSAGE -> openChat(a, who)
            AdminRules.MemberAction.VERIFY -> SecurityCodeDialog.show(a, who, Ui.nameOf(r, who))
            AdminRules.MemberAction.MAKE_ADMIN -> {
                val res = Core.makeAdmin(fp, who)
                told(a, r, res, who)
                if (res == RoleChange.DONE && r.olderPhone(who)) toast(a, a.getString(R.string.admin_older_phone, Ui.uniqueName(r, who)), long = true)
            }
            AdminRules.MemberAction.DISMISS_ADMIN -> confirmDismiss(a, r, who)
            null -> {}
        }
    }

    private fun confirmDismiss(a: AppCompatActivity, r: Router, who: String) {
        val name = Ui.uniqueName(r, who)
        val body = listOfNotNull(a.getString(R.string.dismiss_admin_body, name),
            if (r.onlyAdminsSend()) a.getString(R.string.dismiss_admin_body_locked, name) else null).joinToString("\n\n")
        // Its own words on the button, never the shared "Dismiss" that closes cards. Not red: it can be undone.
        ScreenDialog.confirm(a, DISMISS, a.getString(R.string.dismiss_admin_title, name), body, a.getString(R.string.member_dismiss_admin),
            data = bundleOf(FP to r.group.fingerprint, WHO to who))
    }

    private fun dismissNow(a: AppCompatActivity, b: Bundle) {
        val fp = sameGroup(b) ?: return
        val who = b.getString(WHO) ?: return
        val r = running(a) ?: return
        told(a, r, Core.dismissAdmin(fp, who), who)
    }

    /** "Send messages": Everyone or Only admins, for an admin — the way Mute is picked. */
    fun sendPolicy(a: AppCompatActivity) {
        val r = running(a) ?: return
        if (!r.isAdmin(r.me.id)) { toast(a, a.getString(R.string.admin_not_any_more), long = true); return }
        val name = r.group.name.ifEmpty { a.getString(R.string.your_group) }
        ScreenDialog.choice(a, SEND_POLICY, a.getString(R.string.group_perms_send_title), a.getString(R.string.group_perms_send_body, name),
            listOf(a.getString(R.string.group_perms_send_everyone), a.getString(R.string.group_perms_send_admins)),
            a.getString(R.string.save), picked = if (r.onlyAdminsSend()) 1 else 0, data = bundleOf(FP to r.group.fingerprint))
    }

    private fun sendPolicyNow(a: AppCompatActivity, b: Bundle) {
        val fp = sameGroup(b) ?: return
        val r = running(a) ?: return
        told(a, r, Core.setOnlyAdminsSend(fp, b.getInt(ScreenDialog.INDEX) == 1), null)   // UNCHANGED (already so) says nothing
    }

    /**
     * What an admin change that couldn't be made says — or nothing, when it was done (the tag, the
     * row's place and the chat line show it) or someone already made it.
     */
    private fun told(a: AppCompatActivity, r: Router, why: RoleChange, who: String?) = when (AdminRules.told(why)) {
        AdminRules.Told.SILENT -> {}
        AdminRules.Told.NOT_READY -> toast(a, a.getString(R.string.starting_try_again))
        AdminRules.Told.NOT_ADMIN -> toast(a, a.getString(R.string.admin_not_any_more), long = true)
        AdminRules.Told.CREATOR -> toast(a, a.getString(R.string.admin_creator_stays))
        AdminRules.Told.LEFT -> toast(a, a.getString(R.string.admin_left, Ui.uniqueName(r, who ?: "")), long = true)
        AdminRules.Told.FULL -> toast(a, a.getString(if (r.founder() == r.me.id) R.string.admin_too_many_founder else R.string.admin_too_many), long = true)
    }

    /** [who]'s private chat, as a tap on their row opens it (Group info, People, the options' "Message"). */
    fun openChat(a: AppCompatActivity, who: String) =
        a.startActivity(Intent(a, ChatActivity::class.java).putExtra(Notifications.EXTRA_PEER, who))
}
