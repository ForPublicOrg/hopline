package app.hopline.ui

import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.transition.TransitionManager
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.os.bundleOf
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.Words
import app.hopline.data.SavedGroup
import app.hopline.databinding.ActivityGroupBinding
import app.hopline.service.Core
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Two big cards: start a group (you get a code) or join one (type / scan a code).
 * Also opened from Home's + button to add a group next to the ones you already have, and by a
 * hopline://join link (a friend's QR scanned with the normal camera app).
 *
 * A code this phone already has never joins twice. A group it is in is opened, or offered to be
 * switched to; a group it LEFT is offered to be rejoined — the same question Home asks — whether
 * the code was typed, scanned or came in a link. There is no silent way back into a left group.
 *
 * A link never joins anything without a yes, whatever the phone is in: any app or web page can
 * open one. A code of three words (a group started before 2.4) is joined after one question too.
 */
class GroupActivity : AppCompatActivity() {
    private lateinit var b: ActivityGroupBinding
    private var adding = false
    private var panel = NONE
    /** A join or start is under way: a second tap must not make a second group. */
    private var busy = false
    /** The panel whose start or join could not be saved, saying so until the next try; [NONE] otherwise. */
    private var failed = NONE

    private val scan = registerForActivityResult(ScanContract()) { result ->
        if (!::b.isInitialized) return@registerForActivityResult
        val text = result.contents
        if (text == null) {
            if (result.originalIntent?.hasExtra(com.google.zxing.client.android.Intents.Scan.MISSING_CAMERA_PERMISSION) == true)
                Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        val parsed = parseQr(text)
        // A payment code is offered for paying only when the Pay screen could pay it; any other is just "not an invite".
        if (parsed == null) { notAnInvite(text); return@registerForActivityResult }
        showPanel(JOIN, focus = false)
        b.code.setText(Words.pretty(parsed.first))
        tryJoin(parsed.first, parsed.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Act on a link only when it is new — not after a rotation, and not when Android replays
        // the old link because the app was reopened from Recents.
        val fresh = savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        val link = if (fresh) intent?.data else null
        val deepLink = link?.let { parseQr(it.toString()) }
        val name = Core.store.name
        if (name.isBlank()) {
            // Keep a tapped invite alive through onboarding instead of dropping it.
            deepLink?.let { Core.store.pendingJoin = "${it.first}|${it.second}" }
            startActivity(Intent(this, WelcomeActivity::class.java)); finish(); return
        }
        b = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(b.root)
        Asks.listen(this)   // the rejoin question, for a code of a group this phone left

        adding = intent.getBooleanExtra("add", false)
        b.back.visibility = if (adding) View.VISIBLE else View.GONE
        b.back.setOnClickListener { finish() }
        b.hello.text = if (adding) getString(R.string.add_group) else getString(R.string.hello_name, name)
        b.groupName.filters = arrayOf(InputFilter.LengthFilter(Names.MAX_GROUP))
        b.groupNameLayout.counterMaxLength = Names.MAX_GROUP
        if (savedInstanceState == null) b.groupName.setText(getString(R.string.default_group_name, name))

        b.cardStart.setOnClickListener { showPanel(START, focus = true) }
        b.cardJoin.setOnClickListener { showPanel(JOIN, focus = true) }
        showPanel(savedInstanceState?.getInt(K_PANEL, NONE) ?: NONE, focus = false, animate = false)

        b.startBtn.setOnClickListener { start(name) }
        b.joinBtn.setOnClickListener { tryJoin(b.code.text?.toString().orEmpty(), "") }
        // Four words wrap onto a second line instead of scrolling out of sight; Done still joins
        // (the field stays a one-line field to the keyboard, so Enter never adds a line break).
        b.code.setHorizontallyScrolling(false)
        b.code.maxLines = 3
        b.code.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { tryJoin(b.code.text?.toString().orEmpty(), ""); true } else false
        }
        // A stale "that doesn't look right" must go the moment they fix the code.
        b.code.doAfterTextChanged { b.codeError.visibility = View.GONE; if (failed == JOIN) failed = NONE }
        b.scanBtn.setOnClickListener { launchScan() }

        ScreenDialog.listen(this, K_DID_YOU_MEAN) { r ->
            val linkName = r.getString(K_NAME).orEmpty()
            when (r.getInt(ScreenDialog.WHICH)) {
                // The question about a three-word code was part of this one (see tryJoin): not asked again.
                DialogInterface.BUTTON_POSITIVE -> r.getString(K_SUGGESTED)?.let { s -> b.code.setText(s); tryJoin(s, linkName, olderSeen = true) }
                DialogInterface.BUTTON_NEUTRAL -> r.getString(K_CODE)?.let { join(Words.normalise(it), linkName) }
            }
        }
        ScreenDialog.listen(this, K_UNKNOWN) { r -> r.getString(K_CODE)?.let { join(Words.normalise(it), r.getString(K_NAME).orEmpty()) } }
        ScreenDialog.listen(this, K_OLDER) { r -> r.getString(K_CODE)?.let { join(it, r.getString(K_NAME).orEmpty()) } }
        ScreenDialog.listen(this, K_LINK) { r -> r.getString(K_CODE)?.let { join(it, r.getString(K_NAME).orEmpty()) } }
        ScreenDialog.listen(this, K_SWITCH) { r ->
            val code = r.getString(K_CODE) ?: return@listen
            if (Core.store.groups().none { it.code == code }) return@listen
            busy = true
            Core.switchGroup(code)
            Core.ensureRunning()
            goHome()
        }
        ScreenDialog.listen(this, K_NOT_INVITE) { launchScan() }

        // A start or join under way when the screen was turned (or left and opened again) carries on in
        // Core: this screen shows it working, and moves on when it is done. One that finished while no
        // screen was looking was a screen ago's business — the group is simply there, on Home.
        if (savedInstanceState == null) Core.takeAdding()
        Core.version.observe(this) { showAdding() }

        if (fresh) {
            // Opened from a QR link, or an invite that waited through onboarding?
            val pending = deepLink ?: Core.store.pendingJoin?.split("|", limit = 2)?.let { it[0] to (it.getOrNull(1) ?: "") }
            Core.store.pendingJoin = null
            when {
                pending != null -> offerLink(pending.first, pending.second)
                link != null -> Toast.makeText(this, R.string.bad_link, Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(K_PANEL, panel)
        outState.putInt(K_FAILED, failed)
    }

    /** After the views have their words back (putting the code back counts as typing, and would clear it). */
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        savedInstanceState.getInt(K_FAILED, NONE).takeIf { it != NONE && Core.adding == null }?.let { showFailed(it) }
    }

    private fun showPanel(p: Int, focus: Boolean, animate: Boolean = true) {
        if (animate) TransitionManager.beginDelayedTransition(b.content)
        panel = p
        b.startPanel.visibility = if (p == START) View.VISIBLE else View.GONE
        b.joinPanel.visibility = if (p == JOIN) View.VISIBLE else View.GONE
        if (!focus) return
        val field = if (p == START) b.groupName else b.code
        field.requestFocus()
        field.setSelection(field.length())
        WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
    }

    private fun launchScan() {
        try {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(getString(R.string.scan_prompt))
                .setBeepEnabled(false).setOrientationLocked(false))
        } catch (e: Exception) { Toast.makeText(this, R.string.scan_no_camera, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ start

    private fun start(myName: String) {
        if (busy) return
        // Asked before the group exists, not after: see Asks.radioAllowed. (Only a phone that has
        // nothing but groups it left gets here without the permissions.)
        if (!Asks.radioAllowed(this)) return
        val gname = Names.clean(b.groupName.text?.toString(), Names.MAX_GROUP).ifEmpty { getString(R.string.default_group_name, myName) }
        var code = Words.randomCode()
        while (Core.store.hasGroup(code)) code = Words.randomCode()
        // This phone names the group right now, so the name is stamped: any later rename beats it,
        // and no older name heard from someone else can. The group's key is worked out first (a few
        // seconds); Core saves the group and puts it on the radio once it is there (showAdding).
        if (Core.startGroup(code, gname, System.currentTimeMillis())) showAdding()
    }

    /**
     * A start or join working in Core: the buttons wait meanwhile, and a bar under the one tapped
     * moves (the group's key takes a few seconds to work out). Once it is done, on to the code (a
     * group this phone started) or Home; if it could not be saved, the panel says so — and keeps
     * saying it, through a turn of the phone, until the next try.
     */
    private fun showAdding() {
        if (!::b.isInitialized || isFinishing) return
        val a = Core.adding ?: return
        val buttons = listOf(b.startBtn, b.joinBtn, b.scanBtn)
        if (a.state == Core.Adding.WORKING) {
            busy = true
            failed = NONE
            // A join from a link starts with no panel open: the one it works in shows.
            val p = if (a.made) START else JOIN
            if (panel != p) showPanel(p, focus = false, animate = false)
            b.startError.isVisible = false; b.codeError.isVisible = false
            for (button in buttons) button.isEnabled = false
            (if (a.made) b.startBtn else b.joinBtn).setText(R.string.group_getting_ready)
            (if (a.made) b.startProgress else b.joinProgress).isVisible = true
            return
        }
        Core.takeAdding()
        for (button in buttons) button.isEnabled = true
        b.startBtn.setText(R.string.start)
        b.joinBtn.setText(R.string.join)
        b.startProgress.isVisible = false; b.joinProgress.isVisible = false
        if (a.state == Core.Adding.FAILED) {
            busy = false
            // A link's code (or a scanned one) waits in the field, one tap from another try.
            if (!a.made && b.code.text.isNullOrBlank()) b.code.setText(Words.pretty(a.code))
            showFailed(if (a.made) START else JOIN)
            return
        }
        if (a.made) {
            startActivity(Intent(this, CodeActivity::class.java).putExtra("first", true))
            finish()
        } else goHome()
    }

    /** A start or join that could not be saved: nothing changed, and the panel it was tapped in says so. */
    private fun showFailed(p: Int) {
        failed = p
        showPanel(p, focus = false, animate = false)
        if (p == START) {
            b.startError.isVisible = true
            b.startError.announceForAccessibility(b.startError.text)
        } else showCodeError(R.string.group_not_saved)
    }

    // ------------------------------------------------------------------ join

    /**
     * Join what was typed or scanned — but catch the mistakes that would join an empty group with
     * nobody in it: a code this phone already has, a word that isn't one of Hopline's, a typo, a
     * word left off ([ScreenRules.typed]). Each is one question at most: when a code of three words
     * comes up in one of them, that question says so too, and [olderSeen] keeps it from being asked twice.
     */
    private fun tryJoin(raw: String, linkName: String, olderSeen: Boolean = false) {
        if (busy) return
        val norm = Words.normalise(raw)
        Core.store.allGroups().firstOrNull { it.code == norm }?.let { alreadyHave(it, linkName); return }
        val unknown = Words.unknownWords(raw)
        when (ScreenRules.typed(raw, olderSeen)) {
            ScreenRules.Typed.JOIN -> join(norm, linkName)
            ScreenRules.Typed.OLDER -> ScreenDialog.confirm(this, K_OLDER, getString(R.string.older_code_title),
                getString(R.string.older_code_body), getString(R.string.join), data = bundleOf(K_CODE to norm, K_NAME to linkName))
            ScreenRules.Typed.BAD -> showCodeError(R.string.bad_code)
            ScreenRules.Typed.SUGGEST -> {
                val suggestion = Words.suggest(raw) ?: return
                val why = if (unknown.isEmpty() || !Words.looksValid(raw)) getString(R.string.did_you_mean_body)
                    else resources.getQuantityString(R.plurals.unknown_words, unknown.size, quoted(unknown))
                val older = ScreenRules.olderCode(suggestion) || (Words.looksValid(raw) && ScreenRules.olderCode(raw))
                ScreenDialog.confirm(this, K_DID_YOU_MEAN, getString(R.string.did_you_mean_title, suggestion), withOlder(why, older),
                    getString(R.string.use_suggestion), neutral = if (Words.looksValid(raw)) getString(R.string.join_as_typed) else null,
                    data = bundleOf(K_CODE to raw, K_SUGGESTED to suggestion, K_NAME to linkName))
            }
            ScreenRules.Typed.CHECK -> ScreenDialog.confirm(this, K_UNKNOWN, getString(R.string.check_code_title),
                withOlder(resources.getQuantityString(R.plurals.unknown_words_check, unknown.size, quoted(unknown)), ScreenRules.olderCode(raw)),
                getString(R.string.join_anyway), data = bundleOf(K_CODE to raw, K_NAME to linkName))
        }
    }

    private fun quoted(words: List<String>): String = words.distinct().joinToString(", ") { "“$it”" }

    /** [text], and the word about three-word codes after it when [older]. */
    private fun withOlder(text: String, older: Boolean): String =
        if (older) text + "\n\n" + getString(R.string.older_code_body) else text

    private fun showCodeError(@StringRes res: Int) {
        b.codeError.setText(res)
        b.codeError.visibility = View.VISIBLE
        b.codeError.announceForAccessibility(b.codeError.text)
    }

    /**
     * The code is a group this phone already has: open it, offer to wake it, or — for a group it
     * left — offer to rejoin it. Never join twice, and never back into a left group without a yes.
     */
    private fun alreadyHave(saved: SavedGroup, linkName: String) {
        val g = adoptLinkName(saved, linkName)
        when (ScreenRules.codeEntered(g, Core.store.activeCode)) {
            // Asks answers this one: the group goes back on the radio, and a fresh Home opens.
            ScreenRules.Entered.REJOIN -> Asks.rejoin(this, g)
            ScreenRules.Entered.OPEN -> {
                Toast.makeText(this, getString(R.string.already_in_group, Asks.groupLabel(g)), Toast.LENGTH_SHORT).show()
                busy = true
                goHome()
            }
            ScreenRules.Entered.SWITCH -> ScreenDialog.confirm(this, K_SWITCH, getString(R.string.switch_group_title, Asks.groupLabel(g)),
                getString(R.string.switch_group_body), getString(R.string.switch_btn), data = bundleOf(K_CODE to g.code))
            ScreenRules.Entered.NEW -> { }   // can't be: a saved group was handed in
        }
    }

    /**
     * A link's name fills in a name this phone never learned (joined by typing, nobody in range
     * yet) — and never replaces one it knows. Unstamped, so any real rename still beats it.
     */
    private fun adoptLinkName(g: SavedGroup, linkName: String): SavedGroup {
        val name = Names.clean(linkName, Names.MAX_GROUP)
        if (name.isEmpty() || g.name.isNotEmpty()) return g
        val r = Core.router
        if (r != null && r.group.code == g.code && !r.adoptHintName(name)) return g
        Core.store.renameGroup(g.code, name, 0)
        if (g.left) Core.dropArchive()   // its chat, if it is being read, is read again under the name
        Core.changed()
        return Core.store.allGroups().firstOrNull { it.code == g.code } ?: g
    }

    /**
     * A link can come from anywhere — any app, any web page — so it never joins without a human
     * yes: not even on a phone with no group on the radio, where joining would start the radio
     * and announce this phone to whoever made the link. One question, saying all there is to say.
     */
    private fun offerLink(code: String, linkName: String) {
        val norm = Words.normalise(code)
        Core.store.allGroups().firstOrNull { it.code == norm }?.let { alreadyHave(it, linkName); return }
        val known = Words.isKnownCode(code)
        // "First" is no group on the radio — also on a phone that only has groups it left: there is
        // no current group for the link to take the radio away from, so no switch to warn about.
        val first = Core.store.activeCode == null
        val label = linkName.ifEmpty { Words.pretty(code) }
        val title = if (first) getString(R.string.join_link_title, label) else getString(R.string.switch_group_title, label)
        val body = listOfNotNull(getString(if (first) R.string.join_link_body else R.string.switch_group_body),
            if (known) null else getString(R.string.link_odd_words),
            if (ScreenRules.olderCode(code)) getString(R.string.older_code_body) else null).joinToString("\n\n")
        ScreenDialog.confirm(this, K_LINK, title, body, getString(R.string.join), data = bundleOf(K_CODE to norm, K_NAME to linkName))
    }

    private fun join(norm: String, linkName: String) {
        if (busy) return
        // Asked about a while ago (a "Join anyway?" that stood open): if the code is a left group's
        // by now, joining is rejoining, and that has its own question and its own careful path.
        Core.store.allGroups().firstOrNull { it.code == norm && it.left }?.let { Asks.rejoin(this, it); return }
        // The radio's permissions before the group is saved, not after (Asks.radioAllowed). The
        // code waits in the join field meanwhile — a link's code too — one tap from being joined.
        if (!Asks.radioAllowed(this)) { showPanel(JOIN, focus = false, animate = false); b.code.setText(Words.pretty(norm)); return }
        // A link's name is only a hint: Store never lets it overwrite a name this phone already knows.
        // The key first, then the group is saved and on the radio (showAdding).
        if (Core.joinGroup(norm, linkName)) showAdding()
    }

    private fun goHome() {
        val flags = if (adding) Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            else Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(Intent(this, HomeActivity::class.java).addFlags(flags))
        finish()
    }

    /** Someone scanned a QR that isn't an invite (a web link, a payment code): say so, don't join. */
    private fun notAnInvite(text: String) {
        val oneLine = text.replace(Regex("\\s+"), " ").trim()
        val shown = if (oneLine.length > 80) oneLine.take(79).trimEnd() + "…" else oneLine
        ScreenDialog.confirm(this, K_NOT_INVITE, getString(R.string.not_invite_title), getString(R.string.not_invite_body, shown),
            getString(R.string.scan_again))
    }

    companion object {
        private const val NONE = 0
        private const val START = 1
        private const val JOIN = 2
        private const val K_PANEL = "panel"
        private const val K_FAILED = "failed"
        private const val K_CODE = "code"
        private const val K_NAME = "name"
        private const val K_SUGGESTED = "suggested"
        private const val K_DID_YOU_MEAN = "group.didYouMean"
        private const val K_UNKNOWN = "group.unknown"
        private const val K_OLDER = "group.older"
        private const val K_LINK = "group.link"
        private const val K_SWITCH = "group.switch"
        private const val K_NOT_INVITE = "group.notInvite"

        fun qrText(code: String, name: String): String = "hopline://join?code=${Words.normalise(code)}&name=${Uri.encode(name)}"

        /**
         * A scanned QR or a tapped link → (normalised code, cleaned group name), or null if it is not
         * a Hopline invite. Plain text must be four of Hopline's own words (or an older group's
         * three): "http://bit.ly" would otherwise pass as the code "http bit ly" and point the radio
         * at an empty group.
         */
        fun parseQr(text: String): Pair<String, String>? {
            val t = text.trim()
            if (t.startsWith("hopline://", ignoreCase = true)) {
                return try {
                    val uri = Uri.parse(t)
                    val code = uri.getQueryParameter("code") ?: return null
                    if (Words.looksValid(code)) Words.normalise(code) to Names.clean(uri.getQueryParameter("name"), Names.MAX_GROUP) else null
                } catch (e: Exception) { null }
            }
            return if (Words.isKnownCode(t)) Words.normalise(t) to "" else null
        }
    }
}
