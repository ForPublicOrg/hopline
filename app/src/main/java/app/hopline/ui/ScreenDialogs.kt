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
import app.hopline.data.SavedGroup
import app.hopline.databinding.DialogScreenPromptBinding
import app.hopline.service.Core
import java.util.Locale

/**
 * The prompts the screens raise — a name to type, a yes/no, a pick-one — as DialogFragments, so
 * rotating the phone (or Android killing the app behind a dialog) never loses one: the fragment
 * comes back by itself with what was typed, and answers through a fragment result the screen
 * listens for in onCreate. A plain AlertDialog would just vanish, half-typed name and all.
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
            else -> a.getString(MESSAGE)?.let { builder.setMessage(it) }
        }
        // Our own click handlers (below) decide when to close, so a validation error keeps it open.
        builder.setPositiveButton(a.getString(OK), null)
        if (!a.getBoolean(NO_CANCEL)) builder.setNegativeButton(a.getString(CANCEL) ?: getString(R.string.cancel), null)
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

    private fun answer(which: Int) {
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
        /** A choice prompt's answer: the picked item's position. */
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
 * The prompts several screens share — my name, the group's name, mute, clear, leave — so each
 * one behaves the same wherever it is opened from. Every answer carries the group it was asked
 * about: if the radio moved to another group meanwhile, the answer is dropped, never misapplied.
 */
object Asks {
    private const val MY_NAME = "ask.myName"
    private const val GROUP_NAME = "ask.groupName"
    private const val LEAVE = "ask.leave"
    private const val MUTE = "ask.mute"
    private const val CLEAR = "ask.clear"
    private const val REMOVE = "ask.removeGroup"
    private const val FP = "fp"
    private const val CHAT = "chat"
    private const val CODE = "code"
    private const val NAME = "name"

    private const val HOUR = 3_600_000L

    /** Call once in onCreate (after setContentView): the shared prompts answer through here. */
    fun listen(a: AppCompatActivity) {
        ScreenDialog.listen(a, MY_NAME) { b -> savedMyName(a, b.getString(ScreenDialog.VALUE).orEmpty()) }
        ScreenDialog.listen(a, GROUP_NAME) { b -> savedGroupName(a, b) }
        ScreenDialog.listen(a, LEAVE) { b -> leaveNow(a, b.getString(CODE)) }
        ScreenDialog.listen(a, MUTE) { b -> muteNow(b) }
        ScreenDialog.listen(a, CLEAR) { b -> clearNow(a, b) }
        ScreenDialog.listen(a, REMOVE) { b -> removeNow(a, b) }
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

    /** A saved group's name as people see it; its three words when it has no name yet. */
    fun groupLabel(g: SavedGroup): String = g.name.ifEmpty { Words.pretty(g.code) }

    private fun toast(a: AppCompatActivity, text: String, long: Boolean = false) =
        Toast.makeText(a, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

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
        if (r.activePeopleList().any { it.id != r.me.id && it.name.lowercase(Locale.ROOT) == me })
            toast(a, a.getString(R.string.name_shared_warning, r.me.name), long = true)
    }

    // ------------------------------------------------------------------ the group's name

    fun groupName(a: AppCompatActivity) {
        val r = Core.router ?: run { toast(a, a.getString(R.string.starting_try_again)); return }
        ScreenDialog.text(a, GROUP_NAME, a.getString(R.string.rename_group), a.getString(R.string.group_name_hint), r.group.name,
            a.getString(R.string.save), message = a.getString(R.string.group_rename_explain), maxLength = Names.MAX_GROUP,
            rule = ScreenDialog.RULE_GROUP, inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
            data = bundleOf(FP to r.group.fingerprint))
    }

    private fun savedGroupName(a: AppCompatActivity, b: Bundle) {
        val r = Core.router ?: run { toast(a, a.getString(R.string.starting_try_again)); return }
        if (b.getString(FP) != r.group.fingerprint) return
        val name = Names.clean(b.getString(ScreenDialog.VALUE), Names.MAX_GROUP)
        if (name.isEmpty() || name == r.group.name) return
        if (!Core.renameGroup(name)) toast(a, a.getString(R.string.group_rename_failed))
    }

    // ------------------------------------------------------------------ leave / remove

    fun leave(a: AppCompatActivity) {
        val g = Core.store.activeGroup() ?: return
        ScreenDialog.confirm(a, LEAVE, a.getString(R.string.leave_group), a.getString(R.string.leave_confirm, groupLabel(g)),
            a.getString(R.string.leave), danger = true, data = bundleOf(CODE to g.code))
    }

    private fun leaveNow(a: AppCompatActivity, code: String?) {
        if (code == null || Core.store.activeCode != code) return
        Core.leaveActiveGroup()
        val next = if (Core.store.group() == null) GroupActivity::class.java else HomeActivity::class.java
        a.startActivity(Intent(a, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    /** A paused group: same words as leaving (it is leaving), without waking it first. */
    fun removeGroup(a: AppCompatActivity, g: SavedGroup) = ScreenDialog.confirm(a, REMOVE, a.getString(R.string.leave_group),
        a.getString(R.string.leave_confirm, groupLabel(g)), a.getString(R.string.leave), danger = true,
        data = bundleOf(CODE to g.code, NAME to groupLabel(g)))

    private fun removeNow(a: AppCompatActivity, b: Bundle) {
        val code = b.getString(CODE) ?: return
        if (code == Core.store.activeCode || Core.store.groups().none { it.code == code }) return
        Core.removeSavedGroup(code)
        toast(a, a.getString(R.string.group_removed, b.getString(NAME).orEmpty()))
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
}
