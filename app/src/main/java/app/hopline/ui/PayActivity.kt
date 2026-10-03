package app.hopline.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.DialogInterface
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.InputFilter
import android.transition.TransitionManager
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import app.hopline.R
import app.hopline.core.Upi
import app.hopline.databinding.ActivityPayBinding
import app.hopline.databinding.ItemPayLineBinding
import app.hopline.databinding.ItemPayRecentBinding
import app.hopline.databinding.ItemPayStepBinding
import app.hopline.service.Cell
import app.hopline.service.Core
import com.google.zxing.client.android.Intents
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.text.DecimalFormatSymbols
import java.text.NumberFormat

/**
 * Paying a shop where there is no internet. Every UPI app needs data, but *99# — the banks' own
 * service — runs on plain phone signal and pays any UPI ID; Android just lets no app answer its
 * menu. So this screen gets the payment ready and hands over: it reads the shop's QR code (or a
 * typed UPI ID, or someone paid before), checks the amount against what *99# will send, says
 * plainly what the *99# box is going to ask, then copies the UPI ID and opens the Phone app on one
 * fixed code. The person presses call, pastes, sees the name their bank has, and types the amount
 * and their PIN — into their phone company's box, never into Hopline. Back here, only they know
 * how it went, so the screen asks, and helps when it didn't go.
 *
 * Three stages on one page: [START] (whom to pay), [CONFIRM] (whom, how much, what happens next)
 * and [AFTER] (did it go through?). The phone's own business, not a group's: it needs nothing from a
 * group and never starts the mesh. Opened from Home (any phone with a group saved, a left one
 * included), or from the Join screen with [EXTRA_SCANNED] — a payment code scanned there by mistake
 * for an invite; both come after the name and the permissions. A payment in hand
 * survives rotation and process death: the camera and the Phone app both send this screen to the
 * background, where Android may kill it.
 */
class PayActivity : AppCompatActivity() {
    private lateinit var b: ActivityPayBinding
    private var ready = false
    /** This feature's own small file: the people paid before ([Upi.recents]), and nothing else. */
    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    // ---- survives rotation and process death (onSaveInstanceState)
    private var stage = START
    /** Whom the payment in hand is for; null on [START]. */
    private var payee: Upi.Payee? = null
    /** The Phone app was opened on *99# for this payment: coming back means asking how it went. */
    private var dialled = false
    /** "No, or not sure" was tapped: what may have gone wrong stays open. */
    private var helpOpen = false
    /** The "type a UPI ID" field is open (what is typed in it the field keeps itself). */
    private var typing = false
    /** Opened straight onto a code scanned on the Join screen: back from the payee goes back there. */
    private var straight = false

    /** The screen is setting the amount itself — that is not the person typing. */
    private var filling = false
    /** The step that names the amount, kept to follow the amount field as it changes. */
    private var step3: ItemPayStepBinding? = null
    /** The help line that spells out the UPI ID, to type by hand. */
    private var helpId: TextView? = null

    private val scanner = registerForActivityResult(ScanContract()) { result ->
        if (!ready) return@registerForActivityResult
        val text = result.contents
        if (text == null) {
            // A plain cancel needs no word; a camera that wasn't allowed gets the other way in.
            if (result.originalIntent?.hasExtra(Intents.Scan.MISSING_CAMERA_PERMISSION) == true) cameraRefused()
            return@registerForActivityResult
        }
        scanned(text)
    }

    /**
     * The camera is asked for here, before the scanner opens: left to the scanner, a refusal shows
     * its own "the camera encountered a problem — restart the device" box, which is untrue and
     * leads nowhere.
     */
    private val cameraAsk = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!ready) return@registerForActivityResult
        if (granted) openScanner() else cameraRefused()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPayBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { back() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
        for (h in listOf(b.heroTitle, b.recentLabel, b.knowLabel, b.amountLabel, b.stepsLabel, b.afterTitle, b.helpTitle)) {
            ViewCompat.setAccessibilityHeading(h, true)
        }

        // Whom to pay
        b.scan.setOnClickListener { launchScan() }
        b.type.setOnClickListener { openTyping() }
        b.idInput.filters = arrayOf(InputFilter.LengthFilter(Upi.MAX_ID))
        b.idInput.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { typed(); true } else false }
        // A stale "that doesn't look right" must go the moment they fix it.
        b.idInput.doAfterTextChanged { if (b.idError.isVisible) b.idError.isVisible = false }
        b.idNext.setOnClickListener { typed() }
        for (res in KNOW) line(b.knowList, getString(res))
        b.setup.setOnClickListener { dial(Upi.CODE_MENU) }

        // Whom, how much, what happens next
        b.amount.filters = arrayOf(amountFilter)
        b.amount.doAfterTextChanged {
            if (!filling) b.amountError.isVisible = false
            renderStep3()
        }
        // Done on the keypad checks the amount and, if it can be sent, brings the button into view.
        b.amount.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { amountDone(); true } else false }
        STEPS.forEachIndexed { i, res ->
            val s = ItemPayStepBinding.inflate(layoutInflater, b.steps, false)
            s.num.text = NumberFormat.getIntegerInstance().format(i + 1)
            setStep(s, i + 1, getString(res))
            b.steps.addView(s.root)
            if (i + 1 == STEP_AMOUNT) step3 = s
        }
        b.go.setOnClickListener { handOver() }

        // Did it go through?
        b.afterYes.setOnClickListener { paid(it) }
        b.afterNo.setOnClickListener { openHelp() }
        for (res in HELP) {
            val t = line(b.helpList, getString(res))
            if (res == R.string.pay_help_id) helpId = t.apply { setTextIsSelectable(true) }
        }
        b.again.setOnClickListener { again() }
        b.openMenu.setOnClickListener { dial(Upi.CODE_MENU) }
        b.done.setOnClickListener { startOver() }

        ScreenDialog.listen(this, K_BAD) { launchScan() }
        ScreenDialog.listen(this, K_CAMERA) { a -> if (a.getInt(ScreenDialog.WHICH) == DialogInterface.BUTTON_NEUTRAL) openTyping() else appSettings() }
        // TalkBack says where the page went when one stage gives way to another (the payee's title is set as it opens).
        ViewCompat.setAccessibilityPaneTitle(b.startPanel, getString(R.string.pay_hero_title))
        ViewCompat.setAccessibilityPaneTitle(b.afterPanel, getString(R.string.pay_after_title))
        // With no group the mesh never starts the service-state listener, and "no signal" could
        // never be said. Starting it here starts nothing else; its first report redraws the notes.
        Cell.start(applicationContext)
        Cell.heard.observe(this) { if (ready && stage != AFTER) renderNotes() }

        if (savedInstanceState != null) restore(savedInstanceState)
        // A code scanned on the Join screen and handed over: read exactly as if just scanned here.
        // Only on the first creation — after a rotation the payment in hand is already restored.
        else intent.getStringExtra(EXTRA_SCANNED)?.let { straight = scanned(it) }
        render()
        ready = true
    }

    override fun onResume() {
        super.onResume()
        if (!ready) return
        // Back from the Phone app with the payment handed over: from here only the person knows how it went.
        if (dialled && stage == CONFIRM) go(AFTER)
        renderNotes()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(S_STAGE, stage)
        payee?.let { p ->
            outState.putString(S_ID, p.id)
            outState.putString(S_NAME, p.name)
            outState.putLong(S_PAISE, p.paise)
            outState.putLong(S_FLOOR, p.floor)
            outState.putBoolean(S_FIXED, p.fixed)
            outState.putBoolean(S_ONE_BILL, p.oneBill)
        }
        outState.putString(S_AMOUNT, b.amount.text?.toString().orEmpty())
        if (b.amountError.isVisible) outState.putCharSequence(S_AMOUNT_ERROR, b.amountError.text)
        outState.putBoolean(S_DIALLED, dialled)
        outState.putBoolean(S_HELP, helpOpen)
        outState.putBoolean(S_TYPING, typing)
        outState.putBoolean(S_STRAIGHT, straight)
    }

    private fun restore(s: Bundle) {
        stage = s.getInt(S_STAGE, START)
        // Checked again on the way back in, as everywhere: only a real UPI ID is ever copied.
        payee = s.getString(S_ID)?.let { Upi.id(it) }?.let {
            Upi.Payee(it, s.getString(S_NAME).orEmpty(), s.getLong(S_PAISE), s.getLong(S_FLOOR),
                fixed = s.getBoolean(S_FIXED), oneBill = s.getBoolean(S_ONE_BILL))
        }
        if (payee == null) stage = START
        setAmount(s.getString(S_AMOUNT).orEmpty())
        // What was wrong with the amount is still wrong after turning the phone.
        s.getCharSequence(S_AMOUNT_ERROR)?.let { b.amountError.text = it; b.amountError.isVisible = stage == CONFIRM }
        dialled = s.getBoolean(S_DIALLED)
        helpOpen = s.getBoolean(S_HELP)
        typing = s.getBoolean(S_TYPING)
        straight = s.getBoolean(S_STRAIGHT)
    }

    /**
     * Back walks the stages back: from "did it go through?" to the payee (nothing assumed about the
     * payment), from the payee to the start — or, opened straight onto a code from the Join screen,
     * back there — and from the start out of the screen.
     */
    private fun back() {
        when (stage) {
            AFTER -> { dialled = false; helpOpen = false; go(CONFIRM) }
            CONFIRM -> if (straight) finish() else { payee = null; dialled = false; go(START) }
            else -> finish()
        }
    }

    private fun alive() = !isFinishing && !isDestroyed

    // ------------------------------------------------------------------ drawing

    /** To another stage: one panel out, another in, from its top. */
    private fun go(to: Int) {
        if (ready) TransitionManager.beginDelayedTransition(b.content)
        stage = to
        if (to == START) straight = false
        // The payee's stage brings the keypad up for the amount itself; elsewhere nothing is typed.
        if (to != CONFIRM) currentFocus?.let { Ui.hideKeyboard(this, it) }
        render()
        b.scroll.scrollTo(0, 0)
    }

    private fun render() {
        // Set before the panel shows, so TalkBack says whom the page is now about as it appears.
        payee?.let { ViewCompat.setAccessibilityPaneTitle(b.confirmPanel, getString(R.string.pay_paying_desc, it.id)) }
        b.startPanel.isVisible = stage == START
        b.confirmPanel.isVisible = stage == CONFIRM
        b.afterPanel.isVisible = stage == AFTER
        when (stage) {
            START -> renderStart()
            CONFIRM -> renderConfirm()
            else -> renderAfter()
        }
    }

    private fun renderStart() {
        b.type.isVisible = !typing
        b.typePanel.isVisible = typing
        renderNotes()
        renderRecents()
    }

    /**
     * What is true right now — internet, signal, a Jio SIM — re-checked whenever the screen comes
     * back. No signal and Jio are said again on the payee's stage, just before the Phone app opens:
     * a code scanned on the Join screen comes straight there, past the start.
     */
    private fun renderNotes() {
        val noSignal = !Cell.canText(this)
        val jio = onJio()
        b.onlineNote.isVisible = Core.internetNow()
        b.signalNote.isVisible = noSignal
        b.jioNote.isVisible = jio
        b.confirmSignalNote.isVisible = noSignal
        b.confirmJioNote.isVisible = jio
    }

    /** By the SIM's operator, which needs no permission to read. Not knowing is not Jio. */
    private fun onJio(): Boolean = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm != null && Upi.jio(tm.simOperatorName, tm.simOperator)
    } catch (e: Exception) { false }

    /** Six rows at most ([Upi.MAX_RECENT]): a plain list, rebuilt whenever it is shown. */
    private fun renderRecents() {
        val list = recents()
        b.recents.removeAllViews()
        b.recentLabel.isVisible = list.isNotEmpty()
        b.recents.isVisible = list.isNotEmpty()
        for (r in list) {
            val row = ItemPayRecentBinding.inflate(layoutInflater, b.recents, false)
            val title = r.name.ifEmpty { r.id }
            row.avatar.text = Ui.initial(title)
            row.title.text = title
            row.upiId.text = r.id
            row.upiId.isVisible = r.name.isNotEmpty()
            row.line.text = getString(R.string.pay_recent_line, Upi.shown(r.paise), Ui.ago(r.at))
            // Paying someone again starts from nothing: last time's amount is not this time's.
            row.root.setOnClickListener { confirm(Upi.Payee(r.id, r.name, 0, 0, fixed = false, oneBill = false)) }
            row.root.setOnLongClickListener { v ->
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                recentMenu(v, r.id); true
            }
            ViewCompat.replaceAccessibilityAction(row.root, AccessibilityActionCompat.ACTION_CLICK, getString(R.string.pay_recent_action_pay), null)
            ViewCompat.replaceAccessibilityAction(row.root, AccessibilityActionCompat.ACTION_LONG_CLICK, getString(R.string.pay_recent_action_remove), null)
            b.recents.addView(row.root)
        }
    }

    private fun recentMenu(anchor: View, id: String) {
        PopupMenu(this, anchor, Gravity.END).apply {
            menu.add(0, M_REMOVE, 0, R.string.pay_recent_remove)
            setOnMenuItemClickListener {
                saveRecents(Upi.forget(recents(), id))
                if (alive()) {
                    TransitionManager.beginDelayedTransition(b.content)
                    renderRecents()
                }
                true
            }
            show()
        }
    }

    /**
     * The payee as the code gave it, led by the UPI ID — the one thing that is really paid, and what
     * goes on the clipboard. The name is only the code's claim — a sticker can be pasted over a
     * shop's, and say anything — so it comes second, marked as the code's, and is never shown as
     * checked: the bank's own name in the *99# box is the one to believe.
     */
    private fun renderConfirm() {
        val p = payee ?: return
        b.payeeAvatar.text = Ui.initial(p.name.ifEmpty { p.id })
        b.payeeId.text = p.id
        b.payeeName.text = getString(R.string.pay_name_claim, p.name)
        b.payeeName.isVisible = p.name.isNotEmpty()
        b.payeeNote.setText(if (p.name.isNotEmpty()) R.string.pay_name_note else R.string.pay_no_name_note)
        b.oneBill.isVisible = p.oneBill
        b.amount.isEnabled = !p.fixed
        b.amountFixed.isVisible = p.fixed
        renderNotes()
        renderStep3()
    }

    /** Step 3 names the amount to type in the *99# box — once the field holds one that can be sent. */
    private fun renderStep3() {
        val s = step3 ?: return
        val p = payee
        val paise = Upi.paise(amountText())
        val text = if (p != null && paise != null && Upi.amountFlaw(paise, p) == null) getString(R.string.pay_step_3, Upi.rupees(paise))
            else getString(R.string.pay_step_3_blank)
        setStep(s, STEP_AMOUNT, text)
    }

    private fun setStep(s: ItemPayStepBinding, n: Int, text: String) {
        if (s.text.text.toString() == text) return
        s.text.text = text
        s.root.contentDescription = getString(R.string.pay_step_desc, NumberFormat.getIntegerInstance().format(n), text)
    }

    private fun renderAfter() {
        val p = payee ?: return
        val paise = Upi.paise(amountText()) ?: 0L
        // The UPI ID that was copied, never the code's claimed name: that is whom the money went to, if it went.
        b.afterLine.text = getString(R.string.pay_after_line, Upi.shown(paise), p.id)
        b.helpCard.isVisible = helpOpen
        helpId?.text = getString(R.string.pay_help_id, p.id)
    }

    /** One bulleted line at the end of [list]; its text, for a line that changes later. */
    private fun line(list: LinearLayout, text: String): TextView {
        val l = ItemPayLineBinding.inflate(layoutInflater, list, false)
        l.text.text = text
        list.addView(l.root)
        return l.text
    }

    // ------------------------------------------------------------------ whom to pay

    private fun launchScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openScanner()
        else cameraAsk.launch(Manifest.permission.CAMERA)
    }

    private fun openScanner() {
        try {
            scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt(getString(R.string.pay_scan_prompt))
                .setBeepEnabled(false).setOrientationLocked(false))
        } catch (e: Exception) { Toast.makeText(this, R.string.pay_scan_no_camera, Toast.LENGTH_LONG).show() }
    }

    /**
     * No camera: the other way in is right there — typing the UPI ID, which most shops print under
     * their code. Once Android won't ask again, only Settings can give the camera back, so that is
     * offered too, rather than a Scan button that silently does nothing.
     */
    private fun cameraRefused() {
        if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
            Toast.makeText(this, R.string.pay_scan_no_camera, Toast.LENGTH_LONG).show()
            openTyping()
        } else ScreenDialog.confirm(this, K_CAMERA, getString(R.string.pay_camera_title), getString(R.string.pay_camera_body),
            getString(R.string.open_settings), neutral = getString(R.string.pay_type))
    }

    private fun appSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))) }
        catch (e: ActivityNotFoundException) { openTyping() }
    }

    /**
     * A code from the camera, or handed over by the Join screen: on to whom it pays, or a plain
     * "can't pay this" with the reason and another go at scanning. True when there is a payee.
     */
    private fun scanned(text: String): Boolean {
        val read = Upi.read(text)
        val p = read.payee ?: run { cantPay(getString(flawText(read.flaw))); return false }
        // An amount the code won't let be changed (or the least it takes) that *99# can't send:
        // say so now, not at the last step with nothing the person can do about it.
        val least = if (p.fixed) p.paise else p.floor
        if (least > Upi.MAX_PAISE) { cantPay(getString(R.string.pay_bad_too_big, Upi.shown(least))); return false }
        if (p.fixed && p.paise < Upi.MIN_PAISE) { cantPay(getString(R.string.pay_bad_too_small, Upi.shown(p.paise))); return false }
        confirm(p)
        return true
    }

    @StringRes
    private fun flawText(f: Upi.Flaw?): Int = when (f) {
        Upi.Flaw.NOT_PAY -> R.string.pay_bad_not_pay
        Upi.Flaw.NO_ID -> R.string.pay_bad_no_id
        Upi.Flaw.FOREIGN -> R.string.pay_bad_foreign
        Upi.Flaw.SPECIAL -> R.string.pay_bad_special
        Upi.Flaw.BROKEN -> R.string.pay_bad_broken
        Upi.Flaw.NOT_UPI, null -> R.string.pay_bad_not_upi
    }

    private fun cantPay(message: String) =
        ScreenDialog.confirm(this, K_BAD, getString(R.string.pay_bad_title), message, getString(R.string.pay_scan_again))

    private fun openTyping() {
        if (ready) TransitionManager.beginDelayedTransition(b.content)
        typing = true
        b.type.isVisible = false
        b.typePanel.isVisible = true
        showKeyboard(b.idInput)
    }

    private fun typed() {
        val raw = b.idInput.text?.toString().orEmpty()
        val id = Upi.id(raw)
        if (id == null) {
            b.idError.setText(if (Upi.isPhone(raw)) R.string.pay_id_phone else R.string.pay_id_bad)
            b.idError.isVisible = true
            NetText.rejectHaptic(b.idInput)
            reveal(b.idNext, b.typePanel)
            return
        }
        confirm(Upi.Payee(id, "", 0, 0, fixed = false, oneBill = false))
    }

    /** Whom to pay is known: on to the amount and what happens next. Nothing is dialled yet. */
    private fun confirm(p: Upi.Payee) {
        payee = p
        dialled = false
        helpOpen = false
        setAmount(if (p.paise > 0) Upi.rupees(p.paise) else "")
        b.amountError.isVisible = false
        go(CONFIRM)
        // Like any payment app: the amount is the next thing to give, so the keypad comes up for it.
        if (ready && !p.fixed && p.paise == 0L) b.amount.post { if (stage == CONFIRM && alive()) showKeyboard(b.amount) }
    }

    // ------------------------------------------------------------------ handing over to *99#

    private fun setAmount(text: String) {
        filling = true
        b.amount.setText(text)
        b.amount.setSelection(b.amount.length())
        filling = false
    }

    /**
     * "Copy UPI ID and open *99#": the amount checked against what *99# sends, the UPI ID on the
     * clipboard for pasting into the carrier's box, and the Phone app opened on Send Money > UPI ID.
     * The amount itself goes nowhere — the person types it there, as step 3 says.
     */
    private fun handOver() {
        val p = payee ?: return
        val paise = Upi.paise(amountText())
        Upi.amountFlaw(paise, p)?.let { amountError(it, p); return }
        // "250." goes out as 250: the field, step 3 and "did it go through?" all say the same.
        if (paise != null && !p.fixed) setAmount(Upi.rupees(paise))
        copyId(p.id)
        dialled = true
        if (!dial(Upi.CODE_SEND)) dialled = false
    }

    /**
     * The field read as anyone means it: typing passes through "250." and ".5" on the way to an
     * amount, so a dot left dangling is nothing and one in front has a 0 before it.
     */
    private fun amountText(): String =
        b.amount.text?.toString().orEmpty().trim().removeSuffix(".").let { if (it.startsWith(".")) "0$it" else it }

    /** Done on the keypad: a wrong amount is said under the field; a right one brings the button into view. */
    private fun amountDone() {
        val p = payee ?: return
        Upi.amountFlaw(Upi.paise(amountText()), p)?.let { amountError(it, p); return }
        Ui.hideKeyboard(this, b.amount)
        b.go.post { if (alive()) b.go.requestRectangleOnScreen(Rect(0, 0, b.go.width, b.go.height), false) }
    }

    private fun amountError(f: Upi.AmountFlaw, p: Upi.Payee) {
        b.amountError.text = when (f) {
            Upi.AmountFlaw.EMPTY -> getString(R.string.pay_amount_empty)
            Upi.AmountFlaw.TOO_SMALL -> getString(R.string.pay_amount_small)
            Upi.AmountFlaw.TOO_BIG -> getString(R.string.pay_amount_big)
            Upi.AmountFlaw.UNDER_FLOOR -> getString(R.string.pay_amount_floor, Upi.shown(p.floor))
        }
        b.amountError.isVisible = true
        NetText.rejectHaptic(b.amount)
        if (b.amount.isEnabled) showKeyboard(b.amount)
        reveal(b.amountError, b.amountError.parent as View)
    }

    /**
     * Once the next layout has placed it, scroll [v] into view — above the keyboard, which would
     * otherwise hide a message just put under the field being typed in. [parent] is any view whose
     * layout the change that showed [v] invalidated, so this waits for the new positions.
     */
    private fun reveal(v: View, parent: View) =
        parent.doOnLayout { if (alive() && v.isShown) v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), false) }

    private fun copyId(id: String) {
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(getString(R.string.pay_clip_label), id))
        // Android 13+ shows its own "copied" confirmation; a second one would be noise.
        if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.pay_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * The Phone app, opened with [code] typed in for the person to press call. Only ever one of
     * the two fixed *99# codes ([Upi.dialable]): nothing read from a QR code or typed on this screen
     * reaches a dial string — a code that slipped one in could forward this phone's calls. Dialling
     * needs no permission; calling stays the person's own tap. False when there is no Phone app.
     */
    private fun dial(code: String): Boolean {
        if (!Upi.dialable(code)) return false
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", code, null))   // fromParts writes the # as %23
        try { startActivity(i) } catch (x: ActivityNotFoundException) { noPhoneApp(); return false }
        catch (x: SecurityException) { noPhoneApp(); return false }
        return true
    }

    private fun noPhoneApp() = Toast.makeText(this, R.string.pay_no_phone_app, Toast.LENGTH_LONG).show()

    // ------------------------------------------------------------------ did it go through?

    /** The person says it went: they are kept in Recent (with this amount, for the record) and the screen starts over. */
    private fun paid(v: View) {
        val p = payee ?: return
        Upi.paise(amountText())?.let { paise ->
            saveRecents(Upi.remember(recents(), Upi.Recent(p.id, p.name, paise, System.currentTimeMillis())))
        }
        NetText.confirmHaptic(v)
        Toast.makeText(this, R.string.pay_saved, Toast.LENGTH_SHORT).show()
        startOver()
    }

    private fun openHelp() {
        if (!helpOpen) {
            TransitionManager.beginDelayedTransition(b.content)
            helpOpen = true
            b.helpCard.isVisible = true
        }
        b.helpCard.doOnLayout { b.scroll.smoothScrollTo(0, b.afterPanel.top + it.top) }
    }

    /**
     * "Try again", asked for by the person — never by the screen on its own: the UPI ID copied once
     * more (something else may have taken the clipboard since) and *99# opened on Send Money again.
     */
    private fun again() {
        val p = payee ?: return
        copyId(p.id)
        dial(Upi.CODE_SEND)
    }

    /** The payment is over, one way or the other: back to the start with nothing of it left behind. */
    private fun startOver() {
        payee = null
        dialled = false
        helpOpen = false
        typing = false
        setAmount("")
        b.amountError.isVisible = false
        b.idInput.setText("")
        b.idError.isVisible = false
        go(START)
    }

    // ------------------------------------------------------------------ people paid before

    private fun recents(): List<Upi.Recent> = Upi.recents(prefs.getString(K_RECENTS, null))

    private fun saveRecents(list: List<Upi.Recent>) = prefs.edit().putString(K_RECENTS, Upi.json(list)).apply()

    /** A phone whose language writes 2,50 for two and a half: there, and only there, a comma is the dot. */
    private val commaIsDecimal by lazy { DecimalFormatSymbols.getInstance().decimalSeparator == ',' }

    /**
     * The amount field takes only what [Upi.amountEdit] allows — so a comma typed in "2,500" is the
     * thousands mark it is in India, never a decimal point. A refused edit leaves the field exactly
     * as it was, a selection included: a stray key simply doesn't type, as in any payment app, and a
     * refused paste says why, since nothing visibly happened. Deleting is always allowed.
     */
    private val amountFilter = InputFilter { source, start, end, dest, dstart, dend ->
        if (start == end) return@InputFilter null
        val typed = source.subSequence(start, end).toString()
        val after = Upi.amountEdit(dest.toString(), dstart, dend, typed, commaIsDecimal)
        if (after == null) {
            if (end - start > 1 && !filling) b.amount.post { if (alive()) pasteRefused() }
            return@InputFilter dest.subSequence(dstart, dend)
        }
        val put = after.substring(dstart, after.length - (dest.length - dend))
        if (put == typed) null else put   // as typed: keep the keyboard's own spans
    }

    private fun pasteRefused() {
        b.amountError.setText(R.string.pay_amount_paste)
        b.amountError.isVisible = true
        NetText.rejectHaptic(b.amount)
        reveal(b.amountError, b.amountError.parent as View)
    }

    private fun showKeyboard(field: View) {
        field.requestFocus()
        WindowCompat.getInsetsController(window, field).show(WindowInsetsCompat.Type.ime())
    }

    companion object {
        /** Raw text of a QR code scanned on the Join screen, read here exactly as if it had been scanned here. */
        const val EXTRA_SCANNED = "scanned"

        private const val PREFS = "hopline_pay"
        private const val K_RECENTS = "recents"
        private const val K_BAD = "pay.bad"
        private const val K_CAMERA = "pay.camera"

        private const val START = 0
        private const val CONFIRM = 1
        private const val AFTER = 2
        private const val M_REMOVE = 1
        private const val STEP_AMOUNT = 3

        private const val S_STAGE = "stage"
        private const val S_ID = "id"
        private const val S_NAME = "name"
        private const val S_PAISE = "paise"
        private const val S_FLOOR = "floor"
        private const val S_FIXED = "fixed"
        private const val S_ONE_BILL = "oneBill"
        private const val S_AMOUNT = "amount"
        private const val S_AMOUNT_ERROR = "amountError"
        private const val S_DIALLED = "dialled"
        private const val S_HELP = "help"
        private const val S_TYPING = "typing"
        private const val S_STRAIGHT = "straight"

        private val STEPS = listOf(R.string.pay_step_1, R.string.pay_step_2, R.string.pay_step_3_blank, R.string.pay_step_4, R.string.pay_step_5)
        private val KNOW = listOf(R.string.pay_know_limit, R.string.pay_know_sims, R.string.pay_know_sim, R.string.pay_know_codes, R.string.pay_know_pin)
        private val HELP = listOf(R.string.pay_help_mmi, R.string.pay_help_setup, R.string.pay_help_menu, R.string.pay_help_id,
            R.string.pay_help_declined, R.string.pay_help_unsure)

        /**
         * Should this phone offer Pay without internet at all — on Home, and when the Join screen
         * scans a payment code? It has to be able to make calls, and not be on a SIM from abroad —
         * *99# is Indian ([Upi.offered]). Not knowing is yes. This asks the telephony service, so
         * Home asks when it comes back and keeps the answer, rather than on every redraw.
         */
        fun offered(ctx: Context): Boolean {
            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
            return try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                Upi.offered(tm.simCountryIso, tm.networkCountryIso)
            } catch (e: Exception) { true }
        }
    }
}
