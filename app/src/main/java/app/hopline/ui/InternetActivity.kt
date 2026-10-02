package app.hopline.ui

import android.animation.ValueAnimator
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import app.hopline.R
import app.hopline.core.Names
import app.hopline.core.SafeUrl
import app.hopline.databinding.ActivityInternetBinding
import app.hopline.databinding.ItemHelpLogBinding
import app.hopline.databinding.ItemOutboxBinding
import app.hopline.databinding.ItemRequestBinding
import app.hopline.mesh.Errand
import app.hopline.mesh.Person
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Cell
import app.hopline.service.Core
import app.hopline.service.Errands
import app.hopline.service.Locations
import app.hopline.service.Notifications
import app.hopline.service.Permissions
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import java.util.Calendar

/**
 * Shared internet: when anyone in the group gets signal, everyone can use a little of it. This
 * screen says who can help right now, asks in one or two taps (a search or a link, the weather
 * where you stand, "I'm OK" to family), follows each request live until its answer comes back —
 * to you alone — and lets this phone do its own part, within limits its person sets.
 *
 * Opened with no extras from Home, the chat menu or the attach sheet; from a notification with
 * [Notifications.EXTRA_FP] (whose group it is about) and [Notifications.EXTRA_ERRAND] (which card).
 */
class InternetActivity : AppCompatActivity() {
    private lateinit var b: ActivityInternetBinding
    private var ready = false
    private var resumed = false
    private val handler = Handler(Looper.getMainLooper())

    /** Cards are kept and updated in place by request id: a redraw (several a minute on a busy
     *  mesh) never flickers, never steals focus, and animates only what really changed. */
    private val mineCards = LinkedHashMap<String, ItemRequestBinding>()
    private val outboxCards = LinkedHashMap<String, ItemOutboxBinding>()
    private var shownRouter: Router? = null
    private var logStamp = ""

    /** Answers newer than this get a "New" badge: what arrived since the person last looked. */
    private var seenBefore = Long.MAX_VALUE
    /** Just recreated (rotation): keep the badges the person was looking at a moment ago. */
    private var keepSeen = false
    private var lastMarkedAnswer = 0L

    // ---- survives rotation and process death (onSaveInstanceState)
    /** Which location action waits on the permission dialog. */
    private var pendingLocationAction: String? = null
    /** Looking for a GPS fix for "Weather here" since this time (0 = not looking). */
    private var wxSince = 0L
    /** A weather question waiting for an answer (no fix / no permission / location off). */
    private var wxQuestion = 0
    /** "Did it send?" waits for this request once the person is back from their messaging app. */
    private var promptEid: String? = null
    /** Scroll to and flash this card as soon as it is on screen. */
    private var highlightEid: String? = null
    /** The "this is from another group" banner was answered. */
    private var fpHandled = false
    private var logExpanded = false

    /** The messaging app is on its way up: don't ask "did it send?" before the person even left. */
    private var leftForSend = false
    private var wxStop: (() -> Unit)? = null
    private var promptDialog: AlertDialog? = null
    private var wxDialog: AlertDialog? = null
    private var confirmDialog: AlertDialog? = null
    /** refresh() is moving the switches itself — that is not the person changing a setting. */
    private var setting = false

    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (!ready) return@registerForActivityResult
        val action = pendingLocationAction
        pendingLocationAction = null
        if (action != PENDING_WX) return@registerForActivityResult
        // "Approximate only" is plenty for a forecast.
        if (grants.values.any { it }) weatherHere() else { wxQuestion = Q_DENIED; showWxQuestion() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null || !Permissions.allGranted(this)) {
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        b = ActivityInternetBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()

        if (savedInstanceState != null) {
            pendingLocationAction = savedInstanceState.getString(S_LOC)
            wxSince = savedInstanceState.getLong(S_WX)
            wxQuestion = savedInstanceState.getInt(S_WXQ)
            promptEid = savedInstanceState.getString(S_PROMPT)
            highlightEid = savedInstanceState.getString(S_HIGHLIGHT)
            fpHandled = savedInstanceState.getBoolean(S_FP)
            logExpanded = savedInstanceState.getBoolean(S_LOG)
            seenBefore = savedInstanceState.getLong(S_SEEN, Long.MAX_VALUE)
            keepSeen = true
        } else highlightEid = intent.getStringExtra(Notifications.EXTRA_ERRAND)

        b.toolbar.setNavigationOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        for (h in listOf(b.askLabel, b.mineLabel, b.helpLabel, b.outboxLabel, b.logLabel)) ViewCompat.setAccessibilityHeading(h, true)

        b.askInput.filters = arrayOf(InputFilter.LengthFilter(SafeUrl.MAX_LEN))
        b.askInput.setOnEditorActionListener { _, id, ev ->
            val enter = ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_SEARCH || id == EditorInfo.IME_ACTION_DONE || enter) { ask(); true } else false
        }
        b.askInput.doAfterTextChanged { if (b.askError.isVisible) b.askError.isVisible = false }
        b.askGo.setOnClickListener { ask() }
        b.wxTile.setOnClickListener { if (wxSince > 0) cancelWeatherFix() else weatherHere() }
        b.homeTile.setOnClickListener { openTextHome() }

        b.budget1.text = getString(R.string.net_mb, 1)
        b.budget5.text = getString(R.string.net_mb, 5)
        b.budget20.text = getString(R.string.net_mb, 20)
        b.share.setOnCheckedChangeListener { v, on ->
            if (setting || on == Core.store.shareInternet) return@setOnCheckedChangeListener
            Core.setShareInternet(on)
            NetText.confirmHaptic(v)
        }
        b.roaming.setOnCheckedChangeListener { _, on ->
            if (setting || on == Core.store.shareWhileRoaming) return@setOnCheckedChangeListener
            Core.store.shareWhileRoaming = on
            Core.setShareInternet(Core.store.shareInternet)   // re-works out what this phone can offer
        }
        b.budget.addOnButtonCheckedListener { _, id, checked ->
            if (setting || !checked) return@addOnButtonCheckedListener
            val mb = budgetFor(id)
            if (mb == Core.store.shareBudgetMb) return@addOnButtonCheckedListener
            Core.store.shareBudgetMb = mb
            Core.setShareInternet(Core.store.shareInternet)
        }
        b.logMore.setOnClickListener { logExpanded = !logExpanded; logStamp = ""; refresh() }

        ready = true
        Core.version.observe(this) { onMeshChanged() }
    }

    private var lastRefreshAt = 0L
    private var refreshPosted = false
    private val refreshLater = Runnable { refreshPosted = false; refresh() }

    /** A busy mesh can report changes many times a second; the page redraws at most ~4× a second.
     *  The person's own taps still redraw at once (they call refresh() directly). */
    private fun onMeshChanged() {
        val wait = REFRESH_GAP_MS - (SystemClock.uptimeMillis() - lastRefreshAt)
        if (wait <= 0) refresh()
        else if (!refreshPosted) { refreshPosted = true; handler.postDelayed(refreshLater, wait) }
    }

    /** Opened from a notification while already showing (only if a caller adds SINGLE_TOP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!ready) return
        highlightEid = intent.getStringExtra(Notifications.EXTRA_ERRAND)
        fpHandled = false
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        if (Core.store.group() == null || !Permissions.allGranted(this)) {
            // The group was left, or Nearby was revoked while we were away.
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.ensureRunning()
        resumed = true
        Core.appVisible = true
        Core.openChat = Core.INTERNET
        if (keepSeen) keepSeen = false else Core.fingerprint()?.let { seenBefore = Core.store.lastRead(it, Core.INTERNET) }
        lastMarkedAnswer = 0
        refresh()
        if (wxSince > 0) resumeWeatherFix()
        if (wxQuestion != 0) showWxQuestion()
    }

    override fun onPause() {
        super.onPause()
        if (!ready) return
        resumed = false
        leftForSend = false
        if (Core.openChat == Core.INTERNET) Core.openChat = null
        // GPS stays off while we're not looking; the search resumes on return.
        stopWeatherWatch()
        // These come back in onResume (their state is kept) — only the window goes.
        promptDialog?.dismiss(); promptDialog = null
        wxDialog?.dismiss(); wxDialog = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        pendingLocationAction?.let { outState.putString(S_LOC, it) }
        outState.putLong(S_WX, wxSince)
        outState.putInt(S_WXQ, wxQuestion)
        promptEid?.let { outState.putString(S_PROMPT, it) }
        highlightEid?.let { outState.putString(S_HIGHLIGHT, it) }
        outState.putBoolean(S_FP, fpHandled)
        outState.putBoolean(S_LOG, logExpanded)
        outState.putLong(S_SEEN, seenBefore)
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        wxStop?.invoke(); wxStop = null
        for (d in listOf(promptDialog, wxDialog, confirmDialog)) d?.dismiss()
    }

    private fun goBack() {
        // Opened from a notification with nothing underneath: land on Home, not the launcher.
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------------ drawing

    private fun refresh() {
        if (!ready || !alive()) return
        lastRefreshAt = SystemClock.uptimeMillis()
        val r = Core.router
        if (r !== shownRouter) {
            // Another group (or a rebuilt router): its requests are different ones.
            val switched = shownRouter != null
            shownRouter = r
            mineCards.clear(); outboxCards.clear()
            b.mine.removeAllViews(); b.outbox.removeAllViews()
            logStamp = ""
            if (switched && r != null) { seenBefore = Core.store.lastRead(r.group.fingerprint, Core.INTERNET); lastMarkedAnswer = 0 }
        }
        renderFpBanner()
        renderHelp(r)
        if (r == null) { renderStarting(); return }
        b.askCard.alpha = 1f
        renderStatus(r)
        renderOutbox(r)
        renderAsk()
        renderMine(r)
        if (resumed) { markAnswersSeen(r); showSendPrompt() }
        maybeHighlight(r)
    }

    private fun renderStarting() {
        b.statusTitle.setIfChanged(getString(R.string.net_status_title_starting))
        b.statusDetail.setIfChanged(getString(R.string.net_status_detail_starting))
        b.outboxLabel.isVisible = false
        b.askProblem.isVisible = false
        b.askCard.alpha = 0.55f
        b.mineEmpty.isVisible = false
    }

    /** Who can help right now, by name and by what their phone can do. */
    private fun renderStatus(r: Router) {
        val others = r.helpers().filter { it.id != r.me.id }
        val phrases = others.take(3).map { helperPhrase(r, it) }.toMutableList()
        if (others.size > 3) phrases += getString(R.string.net_helper_more, others.size - 3)
        val mineNet = Core.internetNow()
        val detail = StringBuilder()
        val title = when {
            mineNet -> {
                detail.append(getString(R.string.net_status_detail_you))
                if (phrases.isNotEmpty()) detail.append('\n').append(phrases.joinToString(" · "))
                getString(R.string.net_status_title_you)
            }
            phrases.isNotEmpty() -> { detail.append(phrases.joinToString(" · ")); getString(R.string.net_status_title_helpers) }
            else -> { detail.append(getString(R.string.net_status_detail_none)); getString(R.string.net_status_title_none) }
        }
        if (!mineNet && Cell.canText(this)) detail.append('\n').append(getString(R.string.net_status_you_text))
        b.statusTitle.setIfChanged(title)
        b.statusDetail.setIfChanged(detail.toString())
    }

    private fun helperPhrase(r: Router, p: Person): String {
        val name = Ui.uniqueName(r, p.id, p.name)
        if (p.ev < Errand.EV) return getString(R.string.net_helper_legacy, name)
        val looks = p.cap and (Errand.CAP_READ or Errand.CAP_FIND or Errand.CAP_WX) != 0
        val texts = p.cap and Errand.CAP_SMS != 0
        return getString(when {
            looks && texts -> R.string.net_helper_both
            looks -> R.string.net_helper_look
            texts -> R.string.net_helper_texts
            else -> R.string.net_helper_mail
        }, name)
    }

    private fun renderAsk() {
        val problem = Core.requestProblem()
        b.askProblem.isVisible = problem != null
        if (problem != null) b.askProblem.setIfChanged(problem)
        renderWxTile()
    }

    private fun renderWxTile() {
        val finding = wxSince > 0
        b.wxSub.setIfChanged(getString(if (finding) R.string.net_wx_finding else R.string.net_wx_sub))
        b.wxProgress.isVisible = finding
    }

    // ------------------------------------------------------------------ my requests

    private fun renderMine(r: Router) {
        val mine = r.errands.values.filter { it.from == r.me.id }.sortedByDescending { it.ts }
        val netNow = Core.internetNow()
        val ids = mine.mapTo(HashSet()) { it.id }
        val gone = mineCards.keys.filter { it !in ids }
        for (id in gone) mineCards.remove(id)?.let { b.mine.removeView(it.card) }
        for ((i, e) in mine.withIndex()) {
            val c = mineCards.getOrPut(e.id) {
                ItemRequestBinding.inflate(layoutInflater, b.mine, false).also { it.card.clipToOutline = true }
            }
            if (b.mine.getChildAt(i) !== c.card) {
                (c.card.parent as? android.view.ViewGroup)?.removeView(c.card)
                b.mine.addView(c.card, i)
            }
            bindMine(r, c, e, netNow)
        }
        b.mineEmpty.isVisible = mine.isEmpty()
    }

    private fun bindMine(r: Router, c: ItemRequestBinding, e: Errand, netNow: Boolean) {
        val eid = e.id
        val title = Errands.titleFor(e)
        c.icon.setIfChanged(NetText.icon(e))
        c.title.setIfChanged(title)
        c.time.setIfChanged(Ui.ago(e.ts))
        val status = NetText.status(this, r, e)
        c.status.setIfChanged(styledStatus(status))
        c.status.setTextColor(getColor(when {
            NetText.isQuiet(e) -> R.color.warn_text
            e.status == Errand.DONE || e.status == Errand.FAILED -> R.color.text
            else -> R.color.text_muted
        }))
        c.newBadge.isVisible = !e.isOpen && e.answeredAt > seenBefore

        val preview = NetText.preview(e)
        c.preview.isVisible = preview.isNotEmpty()
        if (preview.isNotEmpty()) c.preview.setIfChanged(previewText(e, preview))

        val offer = legacyOffer(r, e)
        c.offer.isVisible = offer != null
        if (offer != null) {
            c.offerText.setIfChanged(offer.first)
            c.offerBtn.isVisible = offer.second != null
            c.offerBtn.text = offer.second
            c.offerBtn.setOnClickListener { v ->
                val r2 = Core.router ?: return@setOnClickListener
                r2.allowPublicAnswer(eid)
                Core.changed()
                NetText.confirmHaptic(v)
            }
        }

        val quiet = NetText.isQuiet(e)
        val again = e.status == Errand.FAILED || e.status == Errand.EXPIRED || e.status == Errand.CANCELLED
        // Asked while offline, and now this phone has signal itself: nobody else needs to spend data.
        val here = netNow && e.type != Errand.SEND && (e.status == Errand.WAITING || e.status == Errand.ASKED) && e.helper != r.me.id
        c.action.isVisible = quiet || again || here
        when {
            quiet -> { c.action.setText(R.string.net_ask_someone_else); c.action.setOnClickListener { askSomeoneElse(eid) } }
            again -> { c.action.setText(R.string.net_ask_again); c.action.setOnClickListener { askAgain(eid) } }
            here -> { c.action.setText(R.string.net_run_here); c.action.setOnClickListener { runHere(eid) } }
        }
        val readable = e.status == Errand.DONE && e.type != Errand.SEND
        c.read.isVisible = readable
        c.read.setOnClickListener { openReader(eid) }
        c.actions.isVisible = c.action.isVisible || c.read.isVisible

        if (readable) c.card.setOnClickListener { openReader(eid) } else c.card.setOnClickListener(null)
        c.card.isClickable = readable
        c.card.setOnLongClickListener { v ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showCardMenu(c.more, eid); true
        }
        c.more.contentDescription = getString(R.string.net_more_for, title)
        c.more.setOnClickListener { showCardMenu(it, eid) }
    }

    /** "✓ Answered via Riya" — the tick green, the cross red; the words stay readable text colour. */
    private fun styledStatus(s: String): CharSequence {
        val color = when { s.startsWith("✓") -> R.color.online; s.startsWith("✕") -> R.color.danger; else -> return s }
        return SpannableString(s).apply {
            setSpan(ForegroundColorSpan(getColor(color)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(StyleSpan(Typeface.BOLD), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    private fun previewText(e: Errand, body: String): CharSequence {
        // A public (older-version) answer already starts with its title.
        val head = e.title.takeIf { it.isNotEmpty() && e.answerZ.isNotEmpty() } ?: return body
        return SpannableStringBuilder().append(head, StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE).append('\n').append(body)
    }

    /**
     * Only older-version phones have signal: they can still read a page or send a text, but only
     * in public. The asker decides — never by default. Weather and search need 2.2 on that phone.
     */
    private fun legacyOffer(r: Router, e: Errand): Pair<String, String?>? {
        if (!e.isOpen || e.allowPublic || e.status == Errand.CLAIMED) return null
        if (r.capableHelpers(e).isNotEmpty()) return null
        val old = r.legacyHelpers().firstOrNull() ?: return null
        val name = Ui.nameOf(r, old.id, old.name)
        return if (e.type == Errand.READ || e.type == Errand.SEND) getString(R.string.net_legacy_offer, name) to getString(R.string.net_legacy_offer_btn, name)
        else getString(R.string.net_legacy_cant, name) to null
    }

    private fun showCardMenu(anchor: View, eid: String) {
        val r = Core.router ?: return
        val e = r.errands[eid] ?: return
        val menu = PopupMenu(this, anchor)
        if (e.status == Errand.DONE && e.type != Errand.SEND) {
            menu.menu.add(0, M_READ, 0, R.string.net_read)
            menu.menu.add(0, M_COPY, 1, R.string.net_copy_answer)
        }
        if (NetText.isQuiet(e)) menu.menu.add(0, M_ELSE, 2, R.string.net_ask_someone_else)
        if (e.isOpen) menu.menu.add(0, M_CANCEL, 3, R.string.net_cancel_request)
        if (!e.isOpen) menu.menu.add(0, M_AGAIN, 4, R.string.net_ask_again)
        if (!e.isOpen) menu.menu.add(0, M_REMOVE, 5, R.string.net_remove_request)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_READ -> openReader(eid)
                M_COPY -> copyAnswer(eid)
                M_ELSE -> askSomeoneElse(eid)
                M_CANCEL -> cancelRequest(eid)
                M_AGAIN -> askAgain(eid)
                M_REMOVE -> removeRequest(eid)
            }
            true
        }
        menu.show()
    }

    private fun removeRequest(eid: String) {
        if (Core.forgetRequest(eid)) NetText.confirmHaptic(b.root)
    }

    private fun openReader(eid: String) {
        startActivity(Intent(this, ReaderActivity::class.java).putExtra(ReaderActivity.EXTRA_ERRAND, eid))
    }

    private fun copyAnswer(eid: String) {
        val e = Core.router?.errands?.get(eid) ?: return
        val body = NetText.plain(e.answer()?.optString("t") ?: e.result.orEmpty()).trim()
        val text = listOf(e.title, body, e.answer()?.optString("src").orEmpty().takeIf { it.startsWith("http") })
            .filter { !it.isNullOrBlank() }.joinToString("\n\n")
        copy(text)
    }

    private fun copy(text: String, said: Int = R.string.copied) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.internet_title), text))
        // Android 13+ shows its own "copied" confirmation; a second one would be noise.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, said, Toast.LENGTH_SHORT).show()
    }

    private fun askAgain(eid: String) {
        val e = Core.router?.errands?.get(eid) ?: return
        // The weather "again" means "where I am now", so it takes the fresh-position path.
        if (e.type == Errand.WX) { weatherHere(); return }
        request(e.type, JSONObject(e.args.toString()))
    }

    /** Asked while offline, and this phone has signal now: run it here, so no friend spends data on it. */
    private fun runHere(eid: String) {
        if (!Core.internetNow()) { snack(getString(R.string.net_run_here_no_signal)); return }
        if (Core.runOwnNow(eid)) NetText.confirmHaptic(b.root) else snack(getString(R.string.net_run_here_taken))
    }

    /** A text whose helper went quiet: handing it on is the asker's call, because Mom could get it twice. */
    private fun askSomeoneElse(eid: String) {
        val r = Core.router ?: return
        val e = r.errands[eid] ?: return
        val helper = e.helper?.let { Ui.nameOf(r, it, e.helperName) } ?: getString(R.string.net_someone)
        val to = Names.clean(e.args.optString("name"), 40).ifEmpty { e.args.optString("to") }
        if (!alive()) return
        confirmDialog = AlertDialog.Builder(this)
            .setTitle(R.string.net_ask_else_title)
            .setMessage(getString(R.string.net_ask_else_body, helper, to))
            .setPositiveButton(R.string.net_ask_someone_else) { _, _ ->
                if (Core.router !== r) return@setPositiveButton
                r.retryErrand(eid)
                Core.changed()
                NetText.confirmHaptic(b.root)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun cancelRequest(eid: String) {
        val r = Core.router ?: return
        val e = r.errands[eid] ?: return
        if (!e.isOpen) return
        val doIt = {
            if (Core.router === r && r.errands[eid]?.isOpen == true) {
                r.cancelErrand(eid)
                Core.changed()
                snack(getString(R.string.net_cancelled_toast))
            }
        }
        // A person may be typing that text into their phone right now.
        if (e.type == Errand.SEND && e.status == Errand.CLAIMED) {
            if (!alive()) return
            val to = Names.clean(e.args.optString("name"), 40).ifEmpty { e.args.optString("to") }
            val helper = e.helper?.let { Ui.nameOf(r, it, e.helperName) } ?: getString(R.string.net_someone)
            confirmDialog = AlertDialog.Builder(this)
                .setMessage(getString(R.string.net_cancel_send_body, to, helper))
                .setPositiveButton(R.string.net_cancel_request) { _, _ -> doIt() }
                .setNegativeButton(R.string.net_keep, null)
                .show()
        } else doIt()
    }

    /** New answers are "read" once this screen shows them: Home's badge and the notification go. */
    private fun markAnswersSeen(r: Router) {
        val mine = r.errands.values.filter { it.from == r.me.id }
        val newest = mine.maxOfOrNull { it.answeredAt } ?: 0L
        if (lastMarkedAnswer != 0L && newest <= lastMarkedAnswer) return
        lastMarkedAnswer = maxOf(newest, 1L)
        Core.markRead(Core.INTERNET)
        val fp = r.group.fingerprint
        for (e in mine) if (!e.isOpen || e.answeredAt > 0 || NetText.isQuiet(e)) Notifications.cancelErrand(this, fp, e.id)
    }

    // ------------------------------------------------------------------ requests for this phone

    private fun renderOutbox(r: Router) {
        val sends = Core.pendingSends().sortedBy { it.ts }
        b.outboxLabel.isVisible = sends.isNotEmpty()
        val ids = sends.mapTo(HashSet()) { it.id }
        for (id in outboxCards.keys.filter { it !in ids }) outboxCards.remove(id)?.let { b.outbox.removeView(it.card) }
        for ((i, e) in sends.withIndex()) {
            val c = outboxCards.getOrPut(e.id) { ItemOutboxBinding.inflate(layoutInflater, b.outbox, false) }
            if (b.outbox.getChildAt(i) !== c.card) {
                (c.card.parent as? android.view.ViewGroup)?.removeView(c.card)
                b.outbox.addView(c.card, i)
            }
            bindOutbox(r, c, e)
        }
    }

    private fun bindOutbox(r: Router, c: ItemOutboxBinding, e: Errand) {
        val eid = e.id
        val who = Ui.nameOf(r, e.from, e.fromName)
        val email = Errand.isEmailTarget(e.args)
        val to = e.args.optString("to")
        val toName = Names.clean(e.args.optString("name"), 40)
        c.icon.setIfChanged(if (email) "✉️" else "💬")
        c.title.setIfChanged(getString(if (email) R.string.errand_mail_title else R.string.errand_send_title_to, who, toName.ifEmpty { to }))
        c.to.setIfChanged((if (toName.isNotEmpty()) getString(R.string.net_outbox_to, to) + " · " else "") + Ui.ago(e.ts))
        c.message.setIfChanged(e.args.optString("text"))
        val safe = NetText.safeRecipient(e.args)
        if (!r.sendWasOpened(eid)) {
            c.hint.setIfChanged(getString(when {
                safe == null -> R.string.net_outbox_bad_to
                email -> R.string.net_outbox_hint_mail
                else -> R.string.net_outbox_hint
            }))
            c.primary.setText(if (email) R.string.net_outbox_open_mail else R.string.net_outbox_open_sms)
            c.primary.isEnabled = safe != null
            c.primary.setOnClickListener { openSend(eid) }
            c.secondary.setText(R.string.cant_send)
            c.secondary.setOnClickListener { finishSend(eid, false) }
            c.reopen.isVisible = false
        } else {
            // Their messaging app was opened: from here on only the person knows if it went.
            c.hint.setIfChanged(getString(R.string.net_outbox_did_it_send, who))
            c.primary.setText(R.string.net_outbox_yes)
            c.primary.isEnabled = true
            c.primary.setOnClickListener { finishSend(eid, true) }
            c.secondary.setText(R.string.net_outbox_no)
            c.secondary.setOnClickListener { finishSend(eid, false) }
            c.reopen.isVisible = safe != null
            c.reopen.setOnClickListener { openSend(eid) }
        }
    }

    private fun openSend(eid: String) {
        val r = Core.router ?: return
        val e = r.errands[eid] ?: return
        if (!r.isRunning(eid)) { snack(getString(R.string.net_outbox_gone)); return }
        val to = NetText.safeRecipient(e.args) ?: return
        val text = e.args.optString("text")
        // Null for a text; an email always goes with one.
        val subject = if (!Errand.isEmailTarget(e.args)) null
            else e.args.optString("subj").take(120).ifEmpty { getString(R.string.net_th_subject, Ui.nameOf(r, e.from, e.fromName)) }
        val intent = if (subject != null) NetText.mailIntent(to, subject, text) else NetText.smsIntent(to, text)
        try { startActivity(intent) } catch (x: ActivityNotFoundException) { noSendApp(r, eid, to, subject, text); return }
        catch (x: SecurityException) { noSendApp(r, eid, to, subject, text); return }
        wentToSend(r, eid)
    }

    /** The person went off to send it — in their messaging app, or by hand from a copy. Only they
     *  know if it went now: the card asks, and it is never handed to another phone on its own. */
    private fun wentToSend(r: Router, eid: String) {
        r.markSendOpened(eid)
        promptEid = eid
        leftForSend = true
        Core.changed()
        // From now on this text is never handed on automatically — that must survive a kill.
        Core.flushSave()
    }

    /** No app here can send it ([subject] is null for a text): offer everything needed to send it
     *  by hand — webmail, another phone. */
    private fun noSendApp(r: Router, eid: String, to: String, subject: String?, text: String) {
        if (!alive()) return
        confirmDialog = AlertDialog.Builder(this)
            .setMessage(if (subject != null) R.string.net_no_mail_app else R.string.net_no_send_app)
            .setPositiveButton(R.string.copy) { _, _ ->
                copy(NetText.sendByHand(to, subject?.let { getString(R.string.net_copy_subject, it) }, text),
                    if (subject != null) R.string.net_copied_mail else R.string.net_copied_both)
                // Copied to send by hand is as good as opened: without this the card could only
                // say "Can't", and after half an hour a second phone would send it again.
                if (Core.router === r && r.isRunning(eid)) wentToSend(r, eid)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun finishSend(eid: String, sent: Boolean) {
        val r = Core.router ?: return
        val e = r.errands[eid]
        val who = e?.let { Ui.nameOf(r, it.from, it.fromName) } ?: getString(R.string.net_someone)
        if (promptEid == eid) promptEid = null
        Core.finishSendErrand(eid, sent)
        NetText.confirmHaptic(b.root)
        snack(if (sent) getString(R.string.net_thanks_sent, who) else getString(R.string.net_handed_back))
    }

    /** Back from the messaging app: ask once, plainly. The card asks the same until answered. */
    private fun showSendPrompt() {
        val eid = promptEid ?: return
        if (!alive() || !resumed || leftForSend || promptDialog?.isShowing == true) return
        val r = Core.router ?: return
        val e = r.errands[eid]
        if (e == null || e.type != Errand.SEND || !r.isRunning(eid)) { promptEid = null; return }
        val to = Names.clean(e.args.optString("name"), 40).ifEmpty { e.args.optString("to") }
        val who = Ui.nameOf(r, e.from, e.fromName)
        promptDialog = AlertDialog.Builder(this)
            .setTitle(getString(if (Errand.isEmailTarget(e.args)) R.string.net_prompt_title_mail else R.string.net_prompt_title_sms, to))
            .setMessage(getString(R.string.net_prompt_body, who))
            .setPositiveButton(R.string.net_outbox_yes) { _, _ -> finishSend(eid, true) }
            .setNegativeButton(R.string.net_outbox_no) { _, _ -> finishSend(eid, false) }
            .setNeutralButton(R.string.net_outbox_not_yet) { _, _ -> promptEid = null }
            .setOnCancelListener { promptEid = null }
            .show()
    }

    // ------------------------------------------------------------------ asking

    private fun ask() {
        val raw = b.askInput.text.toString().trim()
        if (raw.isEmpty()) {
            b.askInput.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(b.askInput, 0)
            return
        }
        // Anything with a scheme is meant as a link: a private one gets a clear "no", not a search.
        val linky = raw.contains("://") || SafeUrl.looksLikeUrl(raw)
        val e = if (linky) {
            val url = SafeUrl.fromInput(raw) ?: run { askError(getString(R.string.net_bad_link)); return }
            request(Errand.READ, JSONObject().put("url", url))
        } else {
            val q = raw.replace(Regex("\\s+"), " ")
            if (q.length > MAX_QUERY) { askError(getString(R.string.net_query_long, MAX_QUERY)); return }
            request(Errand.FIND, JSONObject().put("q", q).put("lang", NetText.searchLang()))
        }
        if (e != null) {
            b.askInput.setText("")
            Ui.hideKeyboard(this, b.askInput)
            b.askInput.clearFocus()
        }
    }

    private fun askError(text: String) {
        b.askError.text = text
        b.askError.isVisible = true
        NetText.rejectHaptic(b.askInput)
    }

    /** One request out, with the honest "what happens now" for it. Null when it couldn't be made. */
    private fun request(type: String, args: JSONObject): Errand? {
        if (Core.router == null) { snack(getString(R.string.net_starting)); return null }
        Core.requestProblem()?.let { NetText.rejectHaptic(b.root); snack(it); return null }
        val e = Core.requestErrand(type, args) ?: run {
            NetText.rejectHaptic(b.root)
            snack(Core.requestProblem() ?: getString(R.string.net_too_long))
            return null
        }
        onRequested(e)
        return e
    }

    /** A request was just made (here or from the Text home sheet): show it and say what happens next. */
    fun onRequested(e: Errand) {
        if (!ready || !alive()) return
        NetText.confirmHaptic(b.root)
        highlightEid = e.id
        val r = Core.router
        val msg = when {
            r != null && e.helper == r.me.id -> getString(R.string.net_asked_here)
            r != null && e.status == Errand.ASKED ->
                r.capableHelpers(e).firstOrNull()?.let { getString(R.string.net_asked_helper, Ui.nameOf(r, it.id, it.name)) }
                    ?: getString(R.string.net_asked_waiting)
            else -> getString(R.string.net_asked_waiting)
        }
        snack(msg)
        refresh()
    }

    private fun openTextHome() {
        if (Core.router == null) { snack(getString(R.string.net_starting)); return }
        val fm = supportFragmentManager
        if (fm.isStateSaved || fm.findFragmentByTag(TextHomeSheet.TAG) != null) return
        TextHomeSheet().show(fm, TextHomeSheet.TAG)
    }

    // ------------------------------------------------------------------ weather here

    private fun weatherHere() {
        if (Core.router == null) { snack(getString(R.string.net_starting)); return }
        Core.requestProblem()?.let { NetText.rejectHaptic(b.root); snack(it); return }
        if (!Locations.granted(this)) {
            pendingLocationAction = PENDING_WX
            try { locationPermission.launch(Locations.toRequest()) } catch (x: Exception) { pendingLocationAction = null; wxQuestion = Q_DENIED; showWxQuestion() }
            return
        }
        val fix = Locations.lastKnown(this)
        if (fix != null && System.currentTimeMillis() - fix.time <= WX_FRESH_MS) { sendWeather(fix); return }
        if (!Locations.serviceOn(this)) { wxQuestion = Q_LOC_OFF; showWxQuestion(); return }
        startWeatherFix(System.currentTimeMillis())
    }

    /** GPS works with no signal; a cold start can take a while, so the tile says so and we wait ~20 s. */
    private fun startWeatherFix(since: Long) {
        wxSince = since
        renderWxTile()
        wxStop?.invoke()
        wxStop = Locations.watch(applicationContext) { l -> if (wxSince > 0 && alive()) { stopWeatherFix(); sendWeather(l) } }
        handler.removeCallbacks(wxTimeout)
        handler.postDelayed(wxTimeout, (WX_FIX_MS - (System.currentTimeMillis() - since)).coerceAtLeast(500L))
    }

    private fun resumeWeatherFix() {
        val elapsed = System.currentTimeMillis() - wxSince
        // Back after a long time away: the moment has passed, don't pop a question out of nowhere.
        if (elapsed !in 0..WX_ABANDON_MS || !Locations.granted(this)) { wxSince = 0; renderWxTile(); return }
        startWeatherFix(wxSince)
    }

    private val wxTimeout = Runnable {
        if (wxSince > 0) { stopWeatherFix(); wxQuestion = Q_NO_FIX; showWxQuestion() }
    }

    private fun stopWeatherWatch() {
        wxStop?.invoke(); wxStop = null
        handler.removeCallbacks(wxTimeout)
    }

    private fun stopWeatherFix() {
        wxSince = 0
        stopWeatherWatch()
        if (ready) renderWxTile()
    }

    private fun cancelWeatherFix() {
        stopWeatherFix()
        snack(getString(R.string.net_wx_stopped))
    }

    /** No position for the forecast: say why, and offer the helper's own position instead. */
    private fun showWxQuestion() {
        val q = wxQuestion
        if (q == 0 || !alive() || !resumed || wxDialog?.isShowing == true) return
        val builder = AlertDialog.Builder(this)
            .setNegativeButton(R.string.cancel) { _, _ -> wxQuestion = 0 }
            .setOnCancelListener { wxQuestion = 0 }
        when (q) {
            Q_NO_FIX -> builder.setTitle(R.string.net_wx_no_fix_title).setMessage(R.string.net_wx_no_fix_body)
            Q_DENIED -> builder.setMessage(R.string.net_wx_denied)
            else -> builder.setMessage(R.string.net_wx_loc_off).setNeutralButton(R.string.turn_on) { _, _ ->
                wxQuestion = 0
                try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (x: Exception) { }
            }
        }
        builder.setPositiveButton(R.string.net_wx_ask_anyway) { _, _ -> wxQuestion = 0; sendWeather(null) }
        wxDialog = builder.show()
    }

    private fun sendWeather(l: Location?) {
        val args = JSONObject()
        if (l != null) {
            // About 1 km is plenty for a forecast, and the exact spot never leaves the phone.
            val lat = Math.round(l.latitude * 100) * 10_000L
            val lng = Math.round(l.longitude * 100) * 10_000L
            if (lat in -90_000_000L..90_000_000L && lng in -180_000_000L..180_000_000L) args.put("lat", lat).put("lng", lng)
        }
        request(Errand.WX, args)
    }

    // ------------------------------------------------------------------ this phone helps

    private fun renderHelp(r: Router?) {
        val s = Core.store
        setting = true
        b.share.isChecked = s.shareInternet
        b.roaming.isChecked = s.shareWhileRoaming
        val want = idForBudget(s.shareBudgetMb)
        if (want == View.NO_ID) b.budget.clearChecked() else if (b.budget.checkedButtonId != want) b.budget.check(want)
        setting = false
        b.helpSettings.isVisible = s.shareInternet
        b.helpStatus.setIfChanged(when {
            !s.shareInternet -> getString(R.string.net_help_off)
            else -> Core.helpPausedReason() ?: when {
                r == null || r.myCaps == 0 -> getString(R.string.net_help_waiting)
                r.myCaps and (Errand.CAP_READ or Errand.CAP_FIND or Errand.CAP_WX or Errand.CAP_MAIL) == 0 ->
                    getString(if (Core.internetNow() && Core.budgetLeft() < Core.MIN_BUDGET_BYTES) R.string.net_help_texts_only_allowance else R.string.net_help_texts_only)
                s.shareBudgetMb > 0 -> getString(R.string.net_help_using, Blobs.prettySize(s.shareUsedToday()), getString(R.string.net_mb, s.shareBudgetMb))
                else -> getString(R.string.net_help_using_nolimit, Blobs.prettySize(s.shareUsedToday()))
            }
        })
        renderLog()
    }

    private fun renderLog() {
        val log = Core.store.helpLog()
        val used = Core.store.shareUsedToday()
        val stamp = "${log.size}|${log.firstOrNull()?.optLong("ts")}|$logExpanded|$used|${Ui.listTime(System.currentTimeMillis())}"
        if (stamp == logStamp) return
        logStamp = stamp
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val todays = log.count { it.optLong("ts") >= today }
        b.logToday.isVisible = todays > 0
        if (todays > 0) b.logToday.text = resources.getQuantityString(R.plurals.net_log_today, todays, todays, Blobs.prettySize(used))
        b.log.removeAllViews()
        for (j in if (logExpanded) log else log.take(LOG_ROWS)) {
            val row = ItemHelpLogBinding.inflate(layoutInflater, b.log, false)
            val ok = j.optBoolean("ok", true)
            val what = j.optString("what")
            val bytes = j.optLong("bytes")
            val detail = listOfNotNull(
                j.optString("who").takeIf { it.isNotEmpty() }?.let { getString(R.string.net_log_for, it) },
                Ui.listTime(j.optLong("ts")).takeIf { it.isNotEmpty() },
                if (bytes > 0) Blobs.prettySize(bytes) else null,
            ).joinToString(" · ")
            row.mark.text = if (ok) "✓" else "✕"
            row.mark.setTextColor(getColor(if (ok) R.color.online else R.color.danger))
            row.what.text = what
            row.detail.text = detail
            row.root.contentDescription = getString(if (ok) R.string.net_log_ok else R.string.net_log_failed) + ". " + what + ". " + detail
            row.root.isFocusable = true
            b.log.addView(row.root)
        }
        b.logEmpty.isVisible = log.isEmpty()
        b.logMore.isVisible = log.size > LOG_ROWS
        b.logMore.text = if (logExpanded) getString(R.string.net_log_less) else getString(R.string.net_log_more, log.size)
    }

    private fun budgetFor(id: Int): Int = when (id) {
        R.id.budget_1 -> 1
        R.id.budget_5 -> 5
        R.id.budget_20 -> 20
        else -> 0
    }

    private fun idForBudget(mb: Int): Int = when (mb) {
        1 -> R.id.budget_1
        5 -> R.id.budget_5
        20 -> R.id.budget_20
        0 -> R.id.budget_0
        else -> View.NO_ID
    }

    // ------------------------------------------------------------------ another group's notification

    /** A notification from a group that isn't on the radio must never act on this one. */
    private fun fpMismatch(): Boolean {
        val fp = intent.getStringExtra(Notifications.EXTRA_FP) ?: return false
        return !fpHandled && fp != Core.fingerprint()
    }

    private fun renderFpBanner() {
        if (!fpMismatch()) { b.fpBanner.isVisible = false; return }
        val fp = intent.getStringExtra(Notifications.EXTRA_FP)
        val g = Core.store.groups().firstOrNull { it.fingerprint == fp }
        b.fpBanner.isVisible = true
        b.fpText.setIfChanged(when {
            g == null -> getString(R.string.net_other_group_gone)
            g.name.isEmpty() -> getString(R.string.net_other_group_unnamed)
            else -> getString(R.string.net_other_group, g.name)
        })
        b.fpSwitch.isVisible = g != null
        b.fpSwitch.setOnClickListener {
            fpHandled = true
            g?.let { Core.switchGroup(it.code) }
            refresh()
        }
        b.fpDismiss.setOnClickListener { fpHandled = true; highlightEid = null; refresh() }
    }

    // ------------------------------------------------------------------ highlight

    private fun maybeHighlight(r: Router) {
        val eid = highlightEid ?: return
        if (fpMismatch()) return
        val card = mineCards[eid]?.card ?: outboxCards[eid]?.card
        highlightEid = null
        if (card == null) {
            // A helper notification for a text someone else already took (or that was cancelled).
            if (r.errands[eid]?.from != r.me.id) snack(getString(R.string.net_outbox_gone))
            return
        }
        card.doOnLayout { v -> b.scroll.post { if (alive()) { scrollTo(v); flash(v) } } }
    }

    private fun scrollTo(v: View) {
        var y = 0
        var cur: View = v
        while (cur !== b.content) { y += cur.top; cur = cur.parent as? View ?: break }
        b.scroll.smoothScrollTo(0, (y - (16 * resources.displayMetrics.density).toInt()).coerceAtLeast(0))
    }

    /** A soft blue wash that fades — "this one" — like a chat app jumping to a message. */
    private fun flash(v: View) {
        val overlay = GradientDrawable().apply {
            cornerRadius = 20 * resources.displayMetrics.density
            setColor(ColorUtils.setAlphaComponent(getColor(R.color.internet_badge), 0x50))
        }
        val old = v.foreground
        v.foreground = overlay
        ValueAnimator.ofInt(255, 0).apply {
            startDelay = 450
            duration = 1400
            addUpdateListener { overlay.alpha = it.animatedValue as Int }
            doOnEnd { if (v.foreground === overlay) v.foreground = old }
            start()
        }
    }

    private fun snack(text: CharSequence) {
        if (ready && alive()) Snackbar.make(b.root, text, Snackbar.LENGTH_LONG).show()
    }

    /** Redraws happen often; setting the same text again would still re-layout and re-announce. */
    private fun TextView.setIfChanged(s: CharSequence) { if (text.toString() != s.toString()) text = s }

    companion object {
        private const val PENDING_WX = "wx"
        private const val Q_NO_FIX = 1
        private const val Q_DENIED = 2
        private const val Q_LOC_OFF = 3
        /** A position this recent is "here" for a forecast. */
        private const val WX_FRESH_MS = 30 * 60_000L
        private const val WX_FIX_MS = 20_000L
        private const val WX_ABANDON_MS = 2 * 60_000L
        private const val MAX_QUERY = 200
        private const val LOG_ROWS = 5
        private const val REFRESH_GAP_MS = 250L

        private const val M_READ = 1
        private const val M_COPY = 2
        private const val M_ELSE = 3
        private const val M_CANCEL = 4
        private const val M_AGAIN = 5
        private const val M_REMOVE = 6

        private const val S_LOC = "pendingLoc"
        private const val S_WX = "wxSince"
        private const val S_WXQ = "wxQuestion"
        private const val S_PROMPT = "sendPrompt"
        private const val S_HIGHLIGHT = "highlight"
        private const val S_FP = "fpHandled"
        private const val S_LOG = "logExpanded"
        private const val S_SEEN = "seenBefore"
    }
}
