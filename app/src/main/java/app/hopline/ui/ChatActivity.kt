package app.hopline.ui

import android.Manifest
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.os.bundleOf
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import app.hopline.R
import app.hopline.databinding.ActivityChatBinding
import app.hopline.databinding.SheetAttachBinding
import app.hopline.databinding.SheetDetailsBinding
import app.hopline.databinding.SheetLocationBinding
import app.hopline.mesh.Errand
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
import app.hopline.service.Locations
import app.hopline.service.Notifications
import app.hopline.service.Permissions
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File
import java.util.Locale

/**
 * One chat: the whole group (no "peer" extra) or one person. Same rules either way — messages
 * are held until phones carry them, and the ticks never lie.
 *
 * Everything a person starts here belongs to the chat it was started in: a draft, a reply, a
 * photo waiting for its caption, a "delete this?" question. Switching chats (a notification,
 * "Reply privately") puts the draft away and closes the rest; rotation and process death bring
 * all of it back.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private var peer: String? = null            // null = the group chat
    private val chatKey: String get() = peer ?: Core.GROUP
    /** The group this screen shows; null while asking whether to switch to another group. The
     *  radio can move to another group behind our back (Home, Settings) — see [followGroup]. */
    private var chatFp: String? = null
    /** Chats opened in place ("Reply privately", a quote from another chat); Back returns to them. */
    private val backStack = ArrayList<String>()

    private var adapter: MessageAdapter? = null
    private var adapterRouter: Router? = null
    /** "Scroll to the newest" stays wanted until a commit actually runs: a newer submit drops an
     *  older one's callback, and the intent must not be dropped with it. */
    private var followPending = false
    private var lastShownId: String? = null
    /** What the list showed at its last applied diff — arrivals are judged against this, since a
     *  newer submit can drop an older one before it ever lands. */
    private var committedIds: Set<String> = emptySet()
    /** Set when the user themself sends something: the next refresh pins the list to the bottom
     *  even if they were up in history — their own message must always be shown. */
    private var pinToBottom = false
    /** Recreated (rotation, process death): the list puts its own scroll position back. */
    private var restoring = false
    /** Recreated, and the views' own state (the composer's words) is not back yet: that only
     *  happens in onRestoreInstanceState, after the first refresh has already run. */
    private var viewStatePending = false
    private var pendingJumpId: String? = null

    /** The unread window, by THIS phone's arrival clock, captured before the chat is marked read. */
    private var unreadSince = UNREAD_CAPTURE
    private var unreadUntil = 0L
    /** Open at the unread divider rather than the bottom. */
    private var unreadJump = false
    /** Back from elsewhere (a photo, another app): look again — only new arrivals replace the divider. */
    private var recheckUnread = false
    /** Arrivals out of sight while the chat is open — carried backlog lands by its send time, often above. */
    private val newAbove = LinkedHashSet<String>()
    private val newBelow = LinkedHashSet<String>()

    private var replyTo: Message? = null
    private var pendingReplyId: String? = null
    /** People picked from @ chips (lowercase name -> id): decides between namesakes. */
    private val chosenMentions = HashMap<String, String>()
    private var mentionKey = ""
    private var lastTooLongToast = 0L
    private var headerShown = ""

    private var cameraFile: File? = null
    /** The chat a picker or the camera was opened for — its result must not land in another one. */
    private var pickTarget: String? = null
    /** Which location action waits on the permission dialog — a plain tag, so it survives the
     *  activity being recreated behind the system dialog. */
    private var pendingLocationAction: String? = null
    /** Save, share and "open with" for photos and files — the same as the photo viewer's. */
    private val media = MediaActions(this)
    /** Bytes of a big file waiting on "send it?" — re-read from its Uri after a rotation. */
    private var pendingPicked: Blobs.PickedFile? = null

    private var menu: MessageMenu? = null
    private val sheets = ArrayList<Dialog>()
    /** The open question (caption, delete, mute…), as data — so a rotation can ask it again. */
    private var prompt: Bundle? = null
    private var promptDialog: Dialog? = null
    private var promptFields: List<EditText> = emptyList()
    private var fixDialog: Dialog? = null
    private var fixStop: (() -> Unit)? = null
    /** Mid-way through moving to another chat: callbacks fired by the teardown (a voice note
     *  stopping, a menu closing) must not redraw the half-left chat. */
    private var switching = false

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pickTarget; pickTarget = null
        if (uri != null && checkTarget(target)) onImagePicked(uri, null)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = pickTarget; pickTarget = null
        if (uri != null && checkTarget(target)) sendPickedFile(uri, target ?: targetKey())
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile; cameraFile = null
        val target = pickTarget; pickTarget = null
        if (f == null) return@registerForActivityResult
        // A backed-out camera can leave an empty or half-written file: that never stays behind.
        if (ok && f.exists() && f.length() > 0 && checkTarget(target)) onImagePicked(uriFor(f), f) else f.delete()
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> launchCamera()
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> { toast(getString(R.string.camera_denied)); openAppSettings() }
            else -> toast(getString(R.string.camera_denied))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingLocationAction
        pendingLocationAction = null
        when {
            grants.values.any { it } -> runLocationAction(action)   // "approximate only" still works, just rougher
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> { toast(getString(R.string.loc_denied)); openAppSettings() }
            else -> toast(getString(R.string.loc_denied))
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> startRecording()
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> { toast(getString(R.string.mic_denied)); openAppSettings() }
            else -> toast(getString(R.string.mic_denied))
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { toLaunch(); return }
        Core.ensureRunning()
        // No router right after a start means something is missing (a revoked permission):
        // the launch screen works out what and asks for it.
        if (Core.router == null) { toLaunch(); return }
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)

        val s = savedInstanceState
        if (s != null) {
            peer = peerOf(s.getString(S_CHAT) ?: Core.GROUP)
            chatFp = s.getString(S_FP)
            s.getStringArrayList(S_BACK)?.let { backStack.addAll(it) }
            cameraFile = s.getString(S_CAMERA)?.let { File(it) }
            pickTarget = s.getString(S_PICK)
            pendingLocationAction = s.getString(S_LOC)
            pendingReplyId = s.getString(S_REPLY)
            unreadSince = s.getLong(S_UNREAD_SINCE, UNREAD_CAPTURE)
            unreadUntil = s.getLong(S_UNREAD_UNTIL)
            unreadJump = s.getBoolean(S_UNREAD_JUMP)
            val names = s.getStringArrayList(S_CHOSEN_NAMES); val ids = s.getStringArrayList(S_CHOSEN_IDS)
            if (names != null && ids != null && names.size == ids.size) for (i in names.indices) chosenMentions[names[i]] = ids[i]
            prompt = s.getBundle(S_PROMPT)
            restoring = true
            viewStatePending = true
            recheckUnread = true   // anything that arrived while we were being re-created still gets a divider
        } else {
            peer = intent.getStringExtra(Notifications.EXTRA_PEER)
            pendingReplyId = intent.getStringExtra(EXTRA_REPLY_TO)
            unreadJump = true
        }
        setUpViews()
        if (s == null) {
            val fp = intent.getStringExtra(Notifications.EXTRA_FP)
            intent.removeExtra(Notifications.EXTRA_FP)   // answered now; a re-created screen must not ask again
            if (fp != null && fp != Core.fingerprint()) offerSwitch(fp, peer, pendingReplyId, fromOpen = true)
            else { chatFp = Core.fingerprint(); loadDraft() }
        }
        sweepCameraLeftovers()
        Core.version.observe(this) { refresh() }
    }

    private fun setUpViews() {
        b.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        // Rows update in place (ticks, pieces arriving, a voice note playing): no cross-fade flicker.
        (b.list.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        b.back.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        b.titleArea.setOnClickListener { openHeader() }
        b.avatar.setOnClickListener { openHeader() }
        b.more.setOnClickListener { showMenu() }
        b.send.setOnClickListener { sendText() }
        b.attach.setOnClickListener { showAttachSheet() }
        b.mic.setOnClickListener { onMicTapped() }
        b.recordCancel.setOnClickListener { finishRecording(send = false) }
        b.recordSend.setOnClickListener { finishRecording(send = true) }
        b.replyClose.setOnClickListener { clearReply() }
        b.liveBanner.setOnClickListener { onLiveBannerTapped() }
        b.errandBanner.setOnClickListener { openPendingSends() }
        b.newAbove.setOnClickListener { jumpToNewAbove() }
        b.jump.setOnClickListener { scrollToBottom(smooth = true) }
        b.input.filters = arrayOf(lengthGuard(Router.MAX_TEXT))
        b.input.doAfterTextChanged { if (it.isNullOrEmpty()) chosenMentions.clear(); updateMentionBar() }
        b.input.onSelectionMoved = { updateMentionBar() }
        attachSwipeToReply()
        b.warn.setOnClickListener {
            try {
                when {
                    !Core.bluetoothOn() -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                    !Core.wifiOn() -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                    else -> openAppSettings()
                }
            } catch (e: ActivityNotFoundException) { openAppSettings() }
        }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateScrollPills() }
        })
    }

    /** singleTop: a notification tap for another chat lands here instead of a fresh instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!::b.isInitialized) return
        val fp = intent.getStringExtra(Notifications.EXTRA_FP)
        intent.removeExtra(Notifications.EXTRA_FP)
        val newPeer = intent.getStringExtra(Notifications.EXTRA_PEER)
        val reply = intent.getStringExtra(EXTRA_REPLY_TO)
        // A notification from a group that isn't on the radio must not open this group's chat
        // as if it were that one.
        if (fp != null && fp != Core.fingerprint()) { offerSwitch(fp, newPeer, reply, fromOpen = chatFp == null); return }
        backStack.clear()
        openChat(newPeer, reply)
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized || isFinishing) return
        if (prompt != null && promptDialog == null) restorePrompt()
        else if (chatFp == null && prompt == null) finishToHome()   // nothing on screen and nothing being asked
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (!::b.isInitialized || !viewStatePending) return
        viewStatePending = false
        // The composer has its words back: a move to another group that refresh() put off can run now.
        val r = Core.router
        if (r != null && chatFp != null && chatFp != r.group.fingerprint) refresh()
    }

    override fun onResume() {
        super.onResume()
        // Closed while starting (followGroup left a private chat): Android 8.x still resumes it, and
        // following again would save the composer it already emptied over the draft it put away.
        if (!::b.isInitialized || isFinishing) return
        if (!Permissions.allGranted(this)) {
            // Nearby permission revoked while we were away (e.g. Android auto-revoke).
            toLaunch(); return
        }
        Core.appVisible = true
        VoicePlayer.onChanged = { refresh() }
        if (VoiceRecorder.recording) showRecordingBar()   // a rotation must not lose a recording
        val r = Core.router
        if (r != null && !followGroup(r)) return
        onChatVisible()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (!::b.isInitialized) return
        Core.appVisible = false
        if (chatFp != null && chatFp == Core.fingerprint()) Core.markRead(chatKey)
        Core.openChat = null
        VoicePlayer.onChanged = null
        VoicePlayer.stop()
        // A recording may outlive this screen (rotation), but this screen's callback must not.
        VoiceRecorder.onMaxReached = null
        saveDraft()
    }

    override fun onStop() {
        super.onStop()
        if (!::b.isInitialized) return
        // GPS must not keep running for a dialog nobody sees; the question comes back with the screen.
        if (fixDialog != null && promptDialog === fixDialog) hidePromptQuietly()
        // Android mutes the mic for backgrounded apps — keeping the bar running would record
        // silence while claiming otherwise. A rotation is fine; a real exit ends the take.
        if (VoiceRecorder.recording && !isChangingConfigurations) {
            finishRecording(send = false)
            toast(getString(R.string.record_discarded))
        }
        if (!isChangingConfigurations) {
            // Whatever arrives while we're away gets its own divider on the way back in.
            recheckUnread = true
            newAbove.clear(); newBelow.clear()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::b.isInitialized) return
        outState.putString(S_CHAT, chatKey)
        outState.putString(S_FP, chatFp)
        outState.putStringArrayList(S_BACK, ArrayList(backStack))
        outState.putString(S_CAMERA, cameraFile?.absolutePath)
        outState.putString(S_PICK, pickTarget)
        outState.putString(S_LOC, pendingLocationAction)
        outState.putString(S_REPLY, replyTo?.id ?: pendingReplyId)
        outState.putLong(S_UNREAD_SINCE, unreadSince)
        outState.putLong(S_UNREAD_UNTIL, unreadUntil)
        outState.putBoolean(S_UNREAD_JUMP, unreadJump)
        val chosen = chosenMentions.entries.toList()
        outState.putStringArrayList(S_CHOSEN_NAMES, ArrayList(chosen.map { it.key }))
        outState.putStringArrayList(S_CHOSEN_IDS, ArrayList(chosen.map { it.value }))
        prompt?.let { p ->
            // What was typed into the question's fields comes back with it.
            if (promptFields.isNotEmpty()) p.putStringArrayList(P_FIELDS, ArrayList(promptFields.map { it.text.toString() }))
            outState.putBundle(S_PROMPT, p)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A rotation recreates us mid-recording and that's fine; actually leaving throws it away.
        if (isFinishing) VoiceRecorder.cancel()
        if (!::b.isInitialized) return
        menu.also { menu = null }?.dismissNow()
        for (d in sheets.toList()) try { d.dismiss() } catch (e: Exception) { }
        sheets.clear()
        val leftPrompt = prompt
        hidePromptQuietly()
        fixStop?.invoke(); fixStop = null
        // A camera shot that never got its caption is litter once the screen is really gone.
        if (isFinishing && leftPrompt?.getString(P_KIND) == P_CAPTION) leftPrompt.getString("temp")?.let { File(it).delete() }
    }

    private fun toLaunch() {
        startActivity(Intent(this, LaunchActivity::class.java))
        finish()
    }

    private fun finishToHome() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    private fun goBack() {
        // "Reply privately" and quote jumps opened chats in place: Back walks back through them.
        val back = backStack.removeLastOrNull()
        if (back != null && chatFp != null && chatFp == Core.fingerprint()) { openChat(peerOf(back), null); return }
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    // ------------------------------------------------------------------ which chat is on screen

    /** Note where "unread" begins for the divider — always before the chat is marked read. */
    private fun captureUnread() {
        val fp = chatFp ?: return
        unreadSince = Core.store.lastRead(fp, chatKey)
        unreadUntil = System.currentTimeMillis()
        recheckUnread = false
    }

    /** Mark the chat seen — after first noting where "unread" begins for the divider. */
    private fun onChatVisible() {
        val fp = chatFp ?: return
        if (unreadSince == UNREAD_CAPTURE) captureUnread()
        else if (recheckUnread) {
            // Back from a photo or another app: the divider stays, unless messages came in
            // meanwhile — then it moves to them (and the list goes there if it was at the bottom).
            recheckUnread = false
            val since = Core.store.lastRead(fp, chatKey)
            val me = Core.router?.me?.id
            if (Core.router?.chatMessages(peer)?.any { it.from != me && !it.isNotice && it.arrivedAt > since } == true) {
                unreadJump = isAtBottom()
                unreadSince = since
                unreadUntil = System.currentTimeMillis()
            }
        }
        Core.openChat = chatKey
        Core.markRead(chatKey)
    }

    /**
     * Show another chat in this screen. [push] keeps the current one for Back. Everything that
     * belonged to the old chat — draft, reply, recording, open questions — stays with it.
     */
    private fun openChat(newPeer: String?, replyId: String?, push: Boolean = false, jumpTo: String? = null) {
        val fp = Core.fingerprint()
        if (newPeer != peer || fp != chatFp) {
            if (push && chatFp != null && chatFp == fp) { backStack.add(chatKey); while (backStack.size > MAX_BACK) backStack.removeAt(0) }
            switching = true
            try {
                leaveChat()
                peer = newPeer
                chatFp = fp
                resetChatState()
                loadDraft()
            } finally { switching = false }
            if (newPeer == null) intent.removeExtra(Notifications.EXTRA_PEER) else intent.putExtra(Notifications.EXTRA_PEER, newPeer)
        }
        if (replyId != null) {
            val m = Core.router?.message(replyId)
            if (m != null) startReply(m) else pendingReplyId = replyId
        }
        pendingJumpId = jumpTo
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onChatVisible()
        refresh()
    }

    /** Everything that belongs to the chat on screen goes with it — never into the next chat. */
    private fun leaveChat() {
        saveDraft()
        if (VoiceRecorder.recording) { finishRecording(send = false); toast(getString(R.string.record_discarded)) }
        VoicePlayer.stop()
        menu.also { menu = null }?.dismissNow()
        for (d in sheets.toList()) d.dismiss()
        sheets.clear()
        abandonPrompt()
        replyTo = null; pendingReplyId = null
        b.replyBar.isVisible = false
        b.input.setText("")
        chosenMentions.clear()
        b.mentionBar.isVisible = false; mentionKey = ""
    }

    private fun resetChatState() {
        adapter = null; adapterRouter = null
        b.list.adapter = null
        lastShownId = null; committedIds = emptySet()
        followPending = false; pinToBottom = false; restoring = false
        unreadSince = UNREAD_CAPTURE; unreadJump = true; recheckUnread = false
        newAbove.clear(); newBelow.clear(); pendingJumpId = null
        updateScrollPills()
    }

    /**
     * The radio moved to another group while this screen was away. A private chat belongs to its
     * group, so it closes; the group chat simply becomes the new group's. False when closing.
     */
    private fun followGroup(r: Router): Boolean {
        val fp = chatFp ?: return true      // still asking whether to switch
        if (fp == r.group.fingerprint) return true
        switching = true
        try {
            leaveChat()
            backStack.clear()
            // Nothing of this chat is on screen any more: no group means onPause can't save the
            // emptied composer over the draft leaveChat just put away.
            if (peer != null) { chatFp = null; finishToHome(); return false }
            chatFp = r.group.fingerprint
            resetChatState()
            loadDraft()
        } finally { switching = false }
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onChatVisible()
        return true
    }

    private fun offerSwitch(fp: String, newPeer: String?, reply: String?, fromOpen: Boolean) {
        if (Core.store.groups().none { it.fingerprint == fp }) {
            // That group was left: nothing to switch to, and its notifications are stale.
            Notifications.clearGroup(this, fp)
            toast(getString(R.string.chat_from_left_group))
            if (fromOpen) finishToHome()
            return
        }
        showPrompt(bundleOf(P_KIND to P_SWITCH, "fp" to fp, "peer" to newPeer, "reply" to reply, "open" to fromOpen))
    }

    private fun doSwitch(code: String, fp: String, newPeer: String?, reply: String?) {
        switching = true
        try {
            leaveChat()
            backStack.clear()
            Core.switchGroup(code)
            // The same goes for a switch that failed: the old chat's draft is already put away.
            if (Core.router == null || Core.fingerprint() != fp) { chatFp = null; finishToHome(); return }
            peer = newPeer
            chatFp = fp
            resetChatState()
            loadDraft()
        } finally { switching = false }
        if (reply != null) pendingReplyId = reply
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onChatVisible()
        refresh()
    }

    // ------------------------------------------------------------------ drafts

    private fun saveDraft() {
        if (!::b.isInitialized) return
        val fp = chatFp ?: return
        ChatDrafts.put(this, fp, chatKey, ChatDrafts.Draft(b.input.text?.toString().orEmpty(), replyTo?.id ?: pendingReplyId, HashMap(chosenMentions)))
    }

    private fun loadDraft() {
        val fp = chatFp ?: return
        val d = ChatDrafts.get(this, fp, chatKey) ?: return
        b.input.setText(d.text)
        b.input.setSelection(b.input.text?.length ?: 0)
        chosenMentions.putAll(d.chosen)
        d.replyId?.let { id -> Core.router?.message(id)?.let { startReply(it, focus = false) } ?: run { pendingReplyId = id } }
    }

    // ------------------------------------------------------------------ sending

    private fun sendText() {
        val r = Core.router ?: return
        val text = b.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        val quote = replyTo?.let { quoteOf(r, it) }
        val to = peer
        if (to == null) r.sendChat(text, quote, Ui.mentionsIn(r, text, chosenMentions)) else r.sendDm(to, text, quote)
        b.input.setText("")
        clearReply()
        sent()
    }

    /** After anything I send: show it, drop the unread divider (I've caught up), keep the draft honest. */
    private fun sent() {
        if (!::b.isInitialized || isDestroyed) return
        pinToBottom = true
        unreadSince = UNREAD_NONE
        saveDraft()
        refresh()
    }

    /** A photo, file or voice note finishes on another thread, maybe after the screen moved to
     *  another chat: only the chat it was sent from ([target]) drops its divider and jumps down. */
    private fun onSent(err: String?, target: String) {
        if (err != null) toast(err) else if (target == targetKey()) sent()
    }

    private fun quoteOf(r: Router, m: Message): Quote = Quote.of(m, Ui.nameOf(r, m.from, m.fromName))

    /** Caps the composer like a LengthFilter, but says so — a long paste is never cut silently. */
    private fun lengthGuard(max: Int) = object : InputFilter.LengthFilter(max) {
        override fun filter(source: CharSequence?, start: Int, end: Int, dest: Spanned?, dstart: Int, dend: Int): CharSequence? {
            val out = super.filter(source, start, end, dest, dstart, dend)
            val now = SystemClock.elapsedRealtime()
            if (out != null && end > start && now - lastTooLongToast > 2_500) {
                lastTooLongToast = now
                toast(resources.getQuantityString(R.plurals.chat_text_too_long, max, max))
            }
            return out
        }
    }

    // ------------------------------------------------------------------ replies

    private fun startReply(m: Message, focus: Boolean = true) {
        if (!m.isPersonal) return
        val r = Core.router ?: return
        replyTo = m
        pendingReplyId = null
        b.replyBar.isVisible = true
        b.replyName.text = if (m.from == r.me.id) getString(R.string.reply_you) else Ui.uniqueName(r, m.from, m.fromName)
        b.replySnippet.text = Quote.of(m).text
        b.replyBar.contentDescription = getString(R.string.chat_replying_to, b.replyName.text, b.replySnippet.text)
        if (focus) {
            b.input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearReply() {
        replyTo = null
        pendingReplyId = null
        b.replyBar.isVisible = false
    }

    private fun replyPrivately(m: Message) = openChat(m.from, m.id, push = true)

    /**
     * Swipe a bubble toward the middle to reply — the WhatsApp muscle memory. The row never
     * actually gets swiped away: past the trigger it ticks, and letting go starts the reply while
     * the row springs back.
     */
    private fun attachSwipeToReply() {
        val density = resources.displayMetrics.density
        val icon = DrawableCompat.wrap(ContextCompat.getDrawable(this, R.drawable.ic_reply)!!.mutate())
        DrawableCompat.setAutoMirrored(icon, true)
        val trigger = 46f * density
        var armed: RecyclerView.ViewHolder? = null
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.END) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val m = adapter?.messageAt(vh.bindingAdapterPosition) ?: return 0   // chips don't swipe
                if (!m.isPersonal || menu?.isShowing == true) return 0
                return makeMovementFlags(0, ItemTouchHelper.END)
            }

            // Never "swiped away": the gesture is only ever a pull that springs back.
            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = Float.MAX_VALUE
            override fun getSwipeEscapeVelocity(defaultValue: Float) = Float.MAX_VALUE
            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) { adapter?.notifyItemChanged(vh.bindingAdapterPosition) }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, actionState: Int, isActive: Boolean) {
                val rtl = rv.layoutDirection == View.LAYOUT_DIRECTION_RTL
                val sign = if (rtl) -1f else 1f
                // Damped, capped drag: the row hints, it doesn't fly away.
                val pull = (dX * sign / 2f).coerceIn(0f, 56f * density)
                super.onChildDraw(c, rv, vh, pull * sign, dY, actionState, isActive)
                if (isActive) {
                    if (pull >= trigger && armed !== vh) { armed = vh; vh.itemView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY) }
                    else if (pull < trigger && armed === vh) armed = null
                } else if (armed === vh) {
                    armed = null
                    adapter?.messageAt(vh.bindingAdapterPosition)?.let { m -> Core.router?.message(m.id)?.let { startReply(it) } }
                }
                if (pull > 10f * density) {
                    val size = (22f * density).toInt()
                    val edge = (12f * density).toInt()
                    val left = if (rtl) rv.width - edge - size else edge
                    val top = vh.itemView.top + (vh.itemView.height - size) / 2
                    DrawableCompat.setLayoutDirection(icon, rv.layoutDirection)
                    icon.setBounds(left, top, left + size, top + size)
                    icon.alpha = (255 * minOf(1f, pull / trigger)).toInt()
                    icon.draw(c)
                }
            }
        })
        helper.attachToRecyclerView(b.list)
    }

    /**
     * Tap a quote block: jump to the original and flash it. An original that lives in another
     * chat on this phone (a private reply to a group message) opens that chat at it.
     */
    private fun jumpToMessage(id: String) {
        val pos = adapter?.positionOf(id) ?: -1
        if (pos < 0) {
            val r = Core.router
            val orig = r?.message(id)
            if (r != null && orig != null) {
                val other = if (orig.isGroup) null else if (orig.from == r.me.id) orig.to else orig.from
                if (other != peer) { openChat(other, null, push = true, jumpTo = id); return }
            }
            toast(getString(R.string.original_gone)); return
        }
        newAbove.remove(id); newBelow.remove(id)
        (b.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, listHeight() / 3)
        b.list.postDelayed({
            if (isDestroyed) return@postDelayed
            // Looked up again: rows may have landed above it during the scroll.
            val at = adapter?.positionOf(id) ?: -1
            val vh = if (at < 0) null else b.list.findViewHolderForAdapterPosition(at) as? MessageAdapter.MsgVH
            if (vh != null && vh.m?.id == id) vh.flash()
            updateScrollPills()
        }, 220)
    }

    // ------------------------------------------------------------------ mentions

    /** The "@token" the cursor sits in right now, if it is an open mention: (index of @, token). */
    private fun openMention(): Pair<Int, String>? {
        if (peer != null) return null
        val text = b.input.text?.toString() ?: return null
        val cursor = b.input.selectionStart
        if (cursor < 0 || cursor != b.input.selectionEnd || cursor > text.length) return null
        val upto = text.substring(0, cursor)
        val at = upto.lastIndexOf('@')
        if (at < 0) return null
        val token = upto.substring(at + 1)
        if ((at > 0 && !upto[at - 1].isWhitespace()) || token.length > 16 || token.contains('\n')) return null
        return at to token
    }

    /** Typing "@" in the group chat offers name chips; tapping one completes the mention. */
    private fun updateMentionBar() {
        val r = Core.router
        val open = if (r == null || chatFp == null) null else openMention()
        val matches = if (r == null || open == null) emptyList()
        else r.people.values.filter { it.name.isNotEmpty() && it.name.startsWith(open.second, ignoreCase = true) }
            .sortedWith(compareBy<Person>({ !r.isInRange(it) }, { it.name.lowercase(Locale.ROOT) })).take(6)
        if (r == null || matches.isEmpty()) { b.mentionBar.isVisible = false; mentionKey = ""; return }
        b.mentionBar.isVisible = true
        val key = matches.joinToString("|") { it.id + ":" + it.name }
        if (key == mentionKey) return
        mentionKey = key
        b.mentionChips.removeAllViews()
        val d = resources.displayMetrics.density
        for (p in matches) {
            // Namesakes are told apart on the chip ("Sam · 3f2a"); the text inserted stays "@Sam".
            val label = "@" + Ui.uniqueName(r, p.id, p.name)
            val chip = TextView(this).apply {
                text = label
                textSize = 14f
                gravity = Gravity.CENTER
                minHeight = (48 * d).toInt()
                setTextColor(getColor(R.color.text))
                // A 48 dp touch target, drawn as a compact pill.
                background = android.graphics.drawable.InsetDrawable(ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_chip), 0, (7 * d).toInt(), 0, (7 * d).toInt())
                setPaddingRelative((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                contentDescription = getString(R.string.chat_mention_desc, label.removePrefix("@"))
                setOnClickListener { completeMention(p) }
            }
            b.mentionChips.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (6 * d).toInt() })
        }
    }

    /** Re-reads the token at tap time: the cursor may have moved since the chips were drawn. */
    private fun completeMention(p: Person) {
        val m = openMention()
        if (m == null || !p.name.startsWith(m.second, ignoreCase = true)) { b.mentionBar.isVisible = false; mentionKey = ""; return }
        val text = b.input.text ?: return
        chosenMentions[p.name.lowercase(Locale.ROOT)] = p.id
        text.replace(m.first, b.input.selectionStart.coerceIn(m.first, text.length), "@${p.name} ")
        b.mentionBar.isVisible = false; mentionKey = ""
    }

    // ------------------------------------------------------------------ photos & files

    private fun onImagePicked(uri: Uri, temp: File?) {
        val r = Core.router ?: run { temp?.delete(); toast(getString(R.string.chat_starting)); return }
        if (!r.canSendFiles()) { temp?.delete(); toast(getString(R.string.files_crowd_off)); return }
        val draft = b.input.text?.toString()?.trim().orEmpty()
        // A caption is offered, never silently taken — and only when the draft fits one; a longer
        // draft stays in the composer, to go as a message of its own.
        val prefill = if (draft.length <= Router.MAX_CAPTION) draft else ""
        showPrompt(bundleOf(P_KIND to P_CAPTION, P_TARGET to targetKey(), "uri" to uri.toString(), "temp" to temp?.absolutePath, "prefill" to prefill))
    }

    private fun captionDialog(p: Bundle): Dialog {
        val uri = Uri.parse(p.getString("uri"))
        val temp = p.getString("temp")?.let { File(it) }
        val prefill = p.getString("prefill").orEmpty()
        val text = p.getStringArrayList(P_FIELDS)?.firstOrNull() ?: prefill
        val asked = Ui.askFields(this, getString(R.string.attach_photo),
            listOf(getString(R.string.caption_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES or InputType.TYPE_TEXT_FLAG_MULTI_LINE)),
            getString(R.string.send), prefill = listOf(text), maxLength = listOf(Router.MAX_CAPTION),
            onCancel = { temp?.delete() }) { v -> sendPhoto(uri, v[0], temp, prefill, p.getString(P_TARGET)) }
        promptFields = asked.fields
        return asked.dialog
    }

    private fun sendPhoto(uri: Uri, caption: String, temp: File?, prefill: String, target: String?) {
        val r = Core.router
        if (r == null || !checkTarget(target)) { temp?.delete(); return }
        val mentions = if (peer == null) Ui.mentionsIn(r, caption, chosenMentions) else emptyList()
        if (prefill.isNotEmpty() && caption == prefill) b.input.setText("")
        val quote = replyTo?.let { quoteOf(r, it) }
        clearReply()
        toast(getString(R.string.sending_file))
        val t = targetKey()
        Core.sendImage(uri, caption, peer, quote, mentions, cleanup = { temp?.delete() }) { err -> onSent(err, t) }
    }

    private fun sendPickedFile(uri: Uri, target: String) {
        val r = Core.router ?: return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        val app = applicationContext
        Thread {
            val result = Blobs.readPicked(app, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed || !checkTarget(target)) return@runOnUiThread
                when (result) {
                    is Blobs.Picked.Ok -> {
                        val picked = result.file
                        if (picked.bytes.size > BIG_FILE) {
                            pendingPicked = picked
                            showPrompt(bundleOf(P_KIND to P_BIGFILE, P_TARGET to target, "uri" to uri.toString(),
                                "label" to "${picked.name} (${Blobs.prettySize(picked.bytes.size.toLong())})"))
                        } else sendFile(picked)
                    }
                    is Blobs.Picked.TooBig -> toast(getString(R.string.file_too_big, Blobs.prettySize(result.size)))
                    is Blobs.Picked.Empty -> toast(getString(R.string.chat_file_empty))
                    is Blobs.Picked.Unreadable -> toast(getString(R.string.chat_file_unreadable))
                }
            }
        }.start()
    }

    private fun sendFile(picked: Blobs.PickedFile) {
        pendingPicked = null
        val r = Core.router ?: return
        val quote = replyTo?.let { quoteOf(r, it) }
        clearReply()
        val t = targetKey()
        Core.sendFileBytes(picked, "", peer, quote = quote) { err -> onSent(err, t) }
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            cameraFile = f
            pickTarget = targetKey()
            takePhoto.launch(uriFor(f))
        } catch (e: Exception) {
            cameraFile?.delete(); cameraFile = null
            toast(getString(R.string.chat_camera_failed))
        }
    }

    private fun uriFor(f: File): Uri = FileProvider.getUriForFile(this, FILES_AUTHORITY, f)

    /** Shots orphaned by a crash or a killed process. Only old ones: a capture may be in flight. */
    private fun sweepCameraLeftovers() {
        if (swept) return
        swept = true
        val keep = setOfNotNull(cameraFile?.absolutePath, prompt?.getString("temp"))
        val dir = File(cacheDir, "camera")
        Thread {
            val cutoff = System.currentTimeMillis() - 3_600_000L
            dir.listFiles()?.forEach { f -> if (f.absolutePath !in keep && f.lastModified() < cutoff) f.delete() }
        }.start()
    }

    private fun openAttachment(m: Message) {
        val r = Core.router ?: return
        val att = m.att ?: return
        val file = Blobs.fileFor(this, r.group.fingerprint, att)
        if (!file.exists()) {
            toast(if (MessageAdapter.neverArrives(m, System.currentTimeMillis())) getString(R.string.chat_file_expired)
                  else getString(R.string.receiving_file, r.fileProgress(att), att.chunks))
            return
        }
        if (att.isInlineImage) {
            startActivity(Intent(this, ViewerActivity::class.java)
                .putExtra("path", file.absolutePath).putExtra("name", att.name).putExtra("mime", att.mime))
            return
        }
        // Under its real name, so the other app shows "report.pdf", not a piece id.
        media.openWith(file, att.name, att.mime)
    }

    private fun fileOnPhone(r: Router, m: Message): Boolean = m.att?.let { Blobs.fileFor(this, r.group.fingerprint, it).exists() } == true

    /** Keep a photo, voice note or file outside Hopline — it survives leaving the group. */
    private fun saveToPhone(m: Message) {
        val r = Core.router ?: return
        val att = m.att ?: return
        media.save(Blobs.fileFor(this, r.group.fingerprint, att), att.name, att.mime)
    }

    private fun shareFile(m: Message) {
        val r = Core.router ?: return
        val att = m.att ?: return
        media.share(Blobs.fileFor(this, r.group.fingerprint, att), att.name, att.mime)
    }

    /**
     * A message of mine that stopped trying gets a fresh start: a new message (new id, new time),
     * because every phone drops the old one once its 48 h are up. The stale one then goes.
     */
    private fun sendAgain(m: Message) {
        val r = Core.router ?: return
        val old = r.message(m.id) ?: return
        if (!Ui.gaveUp(r, old)) return
        val att = old.att
        val loc = old.loc
        when {
            loc != null -> { r.sendLocation(loc, old.to); Core.deleteMessages(listOf(old.id)); sent() }
            att != null -> {
                if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
                if (!Blobs.fileFor(this, r.group.fingerprint, att).exists()) { toast(getString(R.string.chat_resend_gone)); return }
                toast(getString(R.string.sending_file))
                val t = targetKey()
                Core.resendFile(old) { err -> onSent(err, t) }
            }
            else -> {
                val to = old.to
                if (to == null) r.sendChat(old.text, old.quote, old.mentions) else r.sendDm(to, old.text, old.quote)
                Core.deleteMessages(listOf(old.id))
                sent()
            }
        }
    }

    // ------------------------------------------------------------------ voice notes

    private fun onMicTapped() {
        val r = Core.router ?: return
        if (chatFp == null) return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        VoicePlayer.stop()
        if (!VoiceRecorder.start(this)) { toast(getString(R.string.voice_failed)); return }
        b.mic.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        showRecordingBar()
    }

    /** Also called on re-create while a recording runs, so the timer picks up where it truly is.
     *  The bar keeps the screen on while it shows: a sleeping screen would end the take. */
    private fun showRecordingBar() {
        VoiceRecorder.onMaxReached = {
            if (!isFinishing && !isDestroyed && VoiceRecorder.recording) { toast(getString(R.string.voice_max_note)); finishRecording(send = true) }
        }
        b.composerRow.isVisible = false
        b.recordBar.isVisible = true
        b.recordTimer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - VoiceRecorder.startedAt)
        b.recordTimer.start()
        b.recordDot.animate().alpha(0.2f).setDuration(600).withEndAction(object : Runnable {
            override fun run() {
                if (!b.recordBar.isVisible) { b.recordDot.alpha = 1f; return }
                val back = if (b.recordDot.alpha < 0.5f) 1f else 0.2f
                b.recordDot.animate().alpha(back).setDuration(600).withEndAction(this).start()
            }
        }).start()
    }

    private fun finishRecording(send: Boolean) {
        VoiceRecorder.onMaxReached = null
        b.recordTimer.stop()
        b.recordBar.isVisible = false
        b.composerRow.isVisible = true
        b.recordDot.animate().cancel(); b.recordDot.alpha = 1f
        if (!send) { VoiceRecorder.cancel(); return }
        val clip = VoiceRecorder.finish()
        if (clip == null) { toast(getString(R.string.voice_too_short)); return }
        val (file, seconds) = clip
        val bytes = try { file.readBytes() } catch (e: Exception) { null } finally { file.delete() }
        if (bytes == null || bytes.isEmpty()) { toast(getString(R.string.voice_failed)); return }
        val r = Core.router ?: return
        val quote = replyTo?.let { quoteOf(r, it) }
        clearReply()
        val t = targetKey()
        Core.sendFileBytes(Blobs.PickedFile(bytes, file.name, "audio/mp4"), "", peer, seconds, quote) { err -> onSent(err, t) }
    }

    // ------------------------------------------------------------------ attach & location

    private fun showAttachSheet() {
        val r = Core.router ?: return
        if (chatFp == null) return
        val sheet = BottomSheetDialog(this)
        val sb = SheetAttachBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        // Photos and files switch off in a crowd; a location is a hundred bytes and always fits.
        val filesOk = r.canSendFiles()
        for (row in listOf(sb.pickPhoto, sb.pickCamera, sb.pickFile)) row.alpha = if (filesOk) 1f else 0.4f
        sb.pickPhoto.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss(); pickTarget = targetKey(); launchPicker { pickImage.launch("image/*") }
        }
        sb.pickCamera.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss()
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        sb.pickFile.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss(); pickTarget = targetKey(); launchPicker { pickFile.launch("*/*") }
        }
        sb.pickLocation.setOnClickListener { sheet.dismiss(); showLocationSheet() }
        sb.pickInternet.setOnClickListener { sheet.dismiss(); startActivity(Intent(this, InternetActivity::class.java)) }
        trackSheet(sheet)
        sheet.show()
    }

    private fun launchPicker(launch: () -> Unit) {
        try { launch() } catch (e: ActivityNotFoundException) { pickTarget = null; toast(getString(R.string.chat_no_picker)) }
    }

    private fun showLocationSheet() {
        val r = Core.router ?: return
        val sheet = BottomSheetDialog(this)
        val sb = SheetLocationBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        sb.locCurrent.setOnClickListener { sheet.dismiss(); withLocationPermission(PENDING_LOC_FIX) }
        sb.locOther.setOnClickListener { sheet.dismiss(); askForPlace() }
        val to = peer
        val name = to?.let { Ui.nameOf(r, it) }.orEmpty()
        if (Core.liveLocationActive()) {
            sb.locLiveTitle.text = getString(R.string.live_stop)
            sb.locLiveSub.text = getString(R.string.live_left, prettyLeft(Core.liveLocationLeftMs()))
            sb.locLive.setOnClickListener { sheet.dismiss(); Core.stopLiveLocation(); toast(getString(R.string.live_stopped)); refresh() }
        } else if (to != null) {
            // Live location rides the group's beacons: from a private chat it still goes to everyone.
            sb.locLiveTitle.text = getString(R.string.chat_live_share_group)
            sb.locLiveSub.text = getString(R.string.chat_live_share_group_sub, groupName(r), name)
            sb.locLive.setOnClickListener { sheet.dismiss(); showPrompt(bundleOf(P_KIND to P_LIVE_WARN)) }
        } else {
            sb.locLive.setOnClickListener { sheet.dismiss(); withLocationPermission(PENDING_LOC_LIVE) }
        }
        sb.locPrivacy.text = if (to != null) getString(R.string.chat_loc_privacy_dm, name) else getString(R.string.loc_privacy)
        trackSheet(sheet)
        sheet.show()
    }

    private fun withLocationPermission(action: String) {
        if (Locations.granted(this)) runLocationAction(action)
        else { pendingLocationAction = action; locationPermission.launch(Locations.toRequest()) }
    }

    private fun runLocationAction(action: String?) {
        when (action) {
            PENDING_LOC_FIX -> if (requireLocationOn()) showPrompt(bundleOf(P_KIND to P_FIX, P_TARGET to targetKey()))
            PENDING_LOC_LIVE -> if (requireLocationOn()) showPrompt(bundleOf(P_KIND to P_LIVE_FOR))
        }
    }

    /** True when the phone's location switch is on; otherwise offers to open Settings. */
    private fun requireLocationOn(): Boolean {
        if (Locations.serviceOn(this)) return true
        AlertDialog.Builder(this).setMessage(R.string.loc_service_off)
            .setPositiveButton(R.string.turn_on) { _, _ -> try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { } }
            .setNegativeButton(R.string.cancel, null).show().also { trackSheet(it) }
        return false
    }

    private fun prettyLeft(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).toInt()
        return when {
            min >= 60 && min % 60 == 0 -> getString(R.string.chat_left_h, min / 60)
            min >= 60 -> getString(R.string.chat_left_h_min, min / 60, min % 60)
            else -> getString(R.string.chat_left_min, min)
        }
    }

    private fun onLiveBannerTapped() {
        if (Core.liveLocationActive()) showPrompt(bundleOf(P_KIND to P_LIVE_STOP))
        else startActivity(Intent(this, PeopleActivity::class.java))
    }

    private fun askForPlace() = showPrompt(bundleOf(P_KIND to P_PLACE, P_TARGET to targetKey()))

    private fun openLocation(m: Message) {
        val loc = m.loc ?: return
        val r = Core.router
        val label = loc.label.ifEmpty {
            if (m.from == r?.me?.id) getString(R.string.loc_pin_mine)
            else getString(R.string.loc_pin_theirs, r?.let { Ui.nameOf(it, m.from, m.fromName) } ?: m.fromName.ifEmpty { getString(R.string.someone) })
        }
        val coords = String.format(Locale.US, "%.6f,%.6f", loc.lat, loc.lng)
        val geo = Uri.parse("geo:$coords?q=$coords(${Uri.encode(label)})")
        // Google Maps first, then any maps app, then the browser.
        for (intent in listOf(
            Intent(Intent.ACTION_VIEW, geo).setPackage("com.google.android.apps.maps"),
            Intent(Intent.ACTION_VIEW, geo),
            Intent(Intent.ACTION_VIEW, Uri.parse(loc.mapsUrl())),
        )) {
            try { startActivity(intent); return } catch (e: Exception) { }
        }
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.attach_location), loc.pretty()))
        toast(getString(R.string.no_maps_app))
    }

    // ------------------------------------------------------------------ questions that survive rotation

    private fun targetKey(): String = "$chatFp|$chatKey"

    /** True when [target] is the chat on screen; otherwise says so — nothing is sent to the wrong chat. */
    private fun checkTarget(target: String?): Boolean {
        if (target == null || target == targetKey()) return true
        toast(getString(R.string.chat_changed_not_sent))
        return false
    }

    private fun peerOf(chat: String): String? = if (chat == Core.GROUP) null else chat

    private fun showPrompt(p: Bundle) {
        if (isFinishing || isDestroyed) return
        hidePromptQuietly()
        prompt = p
        promptFields = emptyList()
        val d = try { buildPrompt(p) } catch (e: Exception) { null }
        if (d == null) {
            prompt = null
            if (chatFp == null) finishToHome()
            return
        }
        promptDialog = d
        d.setOnDismissListener { promptGone(d) }
    }

    /** The question was answered or backed out of (not merely hidden for a rotation). */
    private fun promptGone(d: Dialog) {
        if (d === fixDialog) { fixStop?.invoke(); fixStop = null; fixDialog = null }
        if (promptDialog !== d) return
        if (prompt?.getString(P_KIND) == P_BIGFILE) pendingPicked = null
        promptDialog = null; prompt = null; promptFields = emptyList()
    }

    /** Close the dialog but keep the question: it is asked again when the screen comes back. */
    private fun hidePromptQuietly() {
        val d = promptDialog ?: return
        promptDialog = null
        try { d.dismiss() } catch (e: Exception) { }
    }

    /** Drop the question for good (the chat changed under it). */
    private fun abandonPrompt() {
        val p = prompt
        prompt = null; promptFields = emptyList(); pendingPicked = null
        hidePromptQuietly()
        if (p?.getString(P_KIND) == P_CAPTION) p.getString("temp")?.let { File(it).delete() }
    }

    private fun restorePrompt() {
        val p = prompt ?: return
        if (p.getString(P_KIND) == P_BIGFILE) {
            // The bytes didn't survive; read the file again and ask again.
            prompt = null
            val uri = p.getString("uri")?.let { Uri.parse(it) } ?: return
            sendPickedFile(uri, p.getString(P_TARGET) ?: targetKey())
        } else showPrompt(p)
    }

    private fun buildPrompt(p: Bundle): Dialog? = when (p.getString(P_KIND)) {
        P_CAPTION -> captionDialog(p)
        P_BIGFILE -> bigFileDialog(p)
        P_PLACE -> placeDialog(p)
        P_DELETE -> deleteDialog(p)
        P_CLEAR -> clearDialog(p)
        P_MUTE -> muteDialog(p)
        P_LIVE_WARN -> liveWarnDialog()
        P_LIVE_FOR -> liveDurationDialog()
        P_LIVE_STOP -> liveStopDialog()
        P_FIX -> fixDialog(p)
        P_SWITCH -> switchDialog(p)
        else -> null
    }

    private fun bigFileDialog(p: Bundle): Dialog? {
        val picked = pendingPicked ?: return null
        return AlertDialog.Builder(this)
            .setMessage(getString(R.string.file_big_warning, p.getString("label")))
            .setPositiveButton(R.string.send) { _, _ -> if (checkTarget(p.getString(P_TARGET))) sendFile(picked) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /** A meeting point: typed coordinates or a pasted maps link, with an optional name. */
    private fun placeDialog(p: Bundle): Dialog {
        val f = p.getStringArrayList(P_FIELDS)
        val asked = Ui.askFields(this, getString(R.string.loc_other),
            listOf(getString(R.string.loc_place_hint) to InputType.TYPE_CLASS_TEXT,
                getString(R.string.loc_place_name_hint) to (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
            getString(R.string.send), prefill = listOf(f?.getOrNull(0).orEmpty(), f?.getOrNull(1).orEmpty()),
            message = getString(R.string.loc_place_help), maxLength = listOf(MAX_PLACE_INPUT, Loc.MAX_LABEL),
            // A place that can't be read keeps the dialog open with the reason — nothing typed is lost.
            validate = { v -> if (Loc.parse(v[0]) == null) getString(R.string.loc_bad_place) else null }) { v ->
            if (!checkTarget(p.getString(P_TARGET))) return@askFields
            val r = Core.router ?: return@askFields
            val loc = Loc.parse(v[0])?.let { Loc.of(it.lat, it.lng, 0, v[1]) } ?: return@askFields
            r.sendLocation(loc, peer)
            sent()
        }
        promptFields = asked.fields
        return asked.dialog
    }

    private fun confirmDelete(m: Message) = showPrompt(bundleOf(P_KIND to P_DELETE, P_TARGET to targetKey(), "id" to m.id))

    private fun deleteDialog(p: Bundle): Dialog? {
        val r = Core.router ?: return null
        val m = r.message(p.getString("id") ?: return null) ?: return null
        // Mine and still ◷: it never left this phone, so deleting it also means it won't be sent.
        val unsent = m.from == r.me.id && m.status == Message.QUEUED
        return AlertDialog.Builder(this).setTitle(R.string.chat_delete_title)
            .setMessage(if (unsent) R.string.chat_delete_unsent else R.string.chat_delete_body)
            .setPositiveButton(R.string.chat_delete) { _, _ -> if (checkTarget(p.getString(P_TARGET))) deleteMessages(listOf(m.id)) }
            .setNegativeButton(R.string.cancel, null).show().also { danger(it) }
    }

    private fun deleteMessages(ids: List<String>) {
        if (replyTo?.id in ids) clearReply()
        Core.deleteMessages(ids)
        refresh()
    }

    private fun clearDialog(p: Bundle): Dialog? {
        val r = Core.router ?: return null
        val unsent = r.chatMessages(peer).count { it.from == r.me.id && it.status == Message.QUEUED }
        val body = getString(R.string.chat_clear_body) +
            if (unsent > 0) "\n\n" + resources.getQuantityString(R.plurals.chat_clear_unsent, unsent, unsent) else ""
        return AlertDialog.Builder(this).setTitle(R.string.chat_clear_title).setMessage(body)
            .setPositiveButton(R.string.chat_clear) { _, _ ->
                if (!checkTarget(p.getString(P_TARGET))) return@setPositiveButton
                Core.clearChat(peer)
                if (replyTo?.let { Core.router?.message(it.id) } == null) clearReply()
                newAbove.clear(); newBelow.clear()
                unreadSince = UNREAD_NONE
                refresh()
            }
            .setNegativeButton(R.string.cancel, null).show().also { danger(it) }
    }

    private fun toggleMute() {
        if (Core.isMuted(chatKey)) { Core.setMuted(chatKey, 0); toast(getString(R.string.chat_unmuted)); refresh() }
        else showPrompt(bundleOf(P_KIND to P_MUTE, P_TARGET to targetKey()))
    }

    private fun muteDialog(p: Bundle): Dialog {
        val labels = arrayOf(getString(R.string.chat_mute_8h), getString(R.string.chat_mute_week), getString(R.string.chat_mute_always))
        return AlertDialog.Builder(this).setTitle(R.string.chat_mute_title)
            .setItems(labels) { _, which ->
                if (!checkTarget(p.getString(P_TARGET))) return@setItems
                val now = System.currentTimeMillis()
                Core.setMuted(chatKey, when (which) { 0 -> now + 8 * 3_600_000L; 1 -> now + 7 * 86_400_000L; else -> Long.MAX_VALUE })
                // A muted group still lets an @mention through — say so, so nobody is surprised.
                toast(getString(if (peer == null) R.string.chat_muted_group else R.string.chat_muted))
                refresh()
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun liveWarnDialog(): Dialog? {
        val r = Core.router ?: return null
        val to = peer ?: return null
        return AlertDialog.Builder(this).setTitle(R.string.chat_live_share_group)
            .setMessage(getString(R.string.chat_live_warn, groupName(r), Ui.nameOf(r, to)))
            .setPositiveButton(R.string.chat_live_share_anyway) { _, _ -> withLocationPermission(PENDING_LOC_LIVE) }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun liveDurationDialog(): Dialog {
        val labels = arrayOf(getString(R.string.live_for_15), getString(R.string.live_for_60), getString(R.string.live_for_480))
        val minutes = intArrayOf(15, 60, 480)
        return AlertDialog.Builder(this).setTitle(if (peer == null) R.string.live_share else R.string.chat_live_share_group)
            .setItems(labels) { _, which -> Core.startLiveLocation(minutes[which]); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun liveStopDialog(): Dialog? {
        if (!Core.liveLocationActive()) return null
        return AlertDialog.Builder(this).setMessage(R.string.live_stop_confirm)
            .setPositiveButton(R.string.stop) { _, _ -> Core.stopLiveLocation(); toast(getString(R.string.live_stopped)); refresh() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    /**
     * One dialog that tells the truth while GPS warms up: Send lights up on the first fix and
     * the accuracy line keeps improving until the user sends or gives up.
     */
    private fun fixDialog(p: Bundle): Dialog? {
        val r = Core.router ?: return null
        if (!Locations.granted(this) || !Locations.serviceOn(this)) return null
        val target = p.getString(P_TARGET)
        var best: Location? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.loc_current)
            .setMessage(R.string.loc_finding)
            .setPositiveButton(R.string.send) { _, _ ->
                val l = best ?: return@setPositiveButton
                if (Core.router !== r || !checkTarget(target)) return@setPositiveButton   // switched groups or chats mid-fix
                Loc.of(l.latitude, l.longitude, l.accuracy.toInt())?.let { r.sendLocation(it, peer); sent() }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        fun offer(l: Location) {
            val b0 = best
            if (b0 == null || l.accuracy < b0.accuracy || l.time - b0.time > 30_000) best = l
            dialog.setMessage(getString(R.string.loc_found, Loc.prettyDistance(best!!.accuracy.toDouble().coerceAtLeast(1.0))))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        }
        fixDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        Locations.lastKnown(this)?.let { if (System.currentTimeMillis() - it.time < 2 * 60_000) offer(it) }
        fixStop?.invoke()
        fixStop = Locations.watch(this) { if (dialog.isShowing) offer(it) }
        return dialog
    }

    private fun switchDialog(p: Bundle): Dialog? {
        val fp = p.getString("fp") ?: return null
        val g = Core.store.groups().firstOrNull { it.fingerprint == fp } ?: return null
        val fromOpen = p.getBoolean("open")
        val name = g.name.ifEmpty { g.code.replace('-', ' ') }
        return AlertDialog.Builder(this)
            .setTitle(getString(R.string.switch_group_title, name))
            .setMessage(getString(R.string.chat_switch_body, name))
            .setPositiveButton(R.string.switch_btn) { _, _ -> doSwitch(g.code, fp, p.getString("peer"), p.getString("reply")) }
            .setNegativeButton(if (fromOpen) R.string.chat_not_now else R.string.cancel) { _, _ -> if (fromOpen) finishToHome() }
            .setOnCancelListener { if (fromOpen) finishToHome() }
            .show()
    }

    private fun danger(d: AlertDialog) {
        d.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(getColor(R.color.danger))
    }

    private fun trackSheet(d: Dialog) {
        sheets.removeAll { !it.isShowing }
        sheets.add(d)
    }

    // ------------------------------------------------------------------ drawing

    private fun refresh() {
        if (!::b.isInitialized || isDestroyed || switching || isFinishing) return
        val r = Core.router
        if (r == null) { b.subtitle.text = getString(R.string.chat_starting); return }
        if (chatFp == null) { renderAwaiting(); return }
        // Back after the process died, and the radio is on another group now: following it this
        // early would save an empty composer over the real draft, then the restored words would
        // land in the new group's composer. onRestoreInstanceState runs this again once they're back.
        if (viewStatePending && chatFp != r.group.fingerprint) return
        if (!followGroup(r)) return
        // The first draw already knows where "unread" starts, so it can open right there.
        if (unreadSince == UNREAD_CAPTURE) captureUnread()
        var a = adapter
        if (a == null || adapterRouter !== r) {
            a = MessageAdapter(this, r, showNames = peer == null, listener = rowListener)
            adapter = a; adapterRouter = r
            b.list.adapter = a
            lastShownId = null; committedIds = emptySet()
        }
        // A reply target from a draft or a re-creation; one no longer on this phone is dropped.
        pendingReplyId?.let { id -> pendingReplyId = null; r.message(id)?.let { startReply(it, focus = false) } }
        val shown = r.chatMessages(peer)
        renderHeader(r)
        renderBanners(r, shown)

        // Only follow the conversation if the user is already at the bottom — never yank them
        // out of history they scrolled up to read. The scroll itself waits for the diff to
        // commit (submitList applies rows a frame or two later).
        val lm = b.list.layoutManager as LinearLayoutManager
        val firstVis = lm.findFirstVisibleItemPosition()
        val lastVis = lm.findLastVisibleItemPosition()
        val atBottom = lastShownId == null || lastVis >= a.itemCount - 2
        val newLastId = shown.lastOrNull()?.id
        // Comparing the newest id (not the count) also catches an arrival that came together with a trim.
        if (!restoring) followPending = followPending || pinToBottom || (newLastId != lastShownId && atBottom)
        pinToBottom = false
        lastShownId = newLastId
        val ids = shown.mapTo(HashSet(shown.size)) { it.id }
        val anchorTop = a.currentList.getOrNull(firstVis)?.key
        val anchorBottom = a.currentList.getOrNull(lastVis)?.key
        val restored = restoring
        restoring = false
        val unread = if (unreadSince >= 0 && unreadSince != UNREAD_NONE) MessageAdapter.Unread(unreadSince, unreadUntil) else null
        val list = a
        a.submit(shown, unread) { committed(list, ids, anchorTop, anchorBottom, restored) }
    }

    /** The diff landed: pills for arrivals out of sight, then the one scroll this refresh earned. */
    private fun committed(a: MessageAdapter, ids: Set<String>, anchorTop: String?, anchorBottom: String?, restored: Boolean) {
        if (isDestroyed || adapter !== a) return
        val r = Core.router ?: return
        val lm = b.list.layoutManager as LinearLayoutManager
        val before = committedIds
        committedIds = ids
        if (before.isNotEmpty() && !restored) {
            val added = ids.filter { it !in before }
            if (added.isNotEmpty()) {
                val index = HashMap<String, Int>(a.currentList.size)
                a.currentList.forEachIndexed { i, row -> index[row.key] = i }
                val top = anchorTop?.let { index[it] } ?: -1
                val bottom = anchorBottom?.let { index[it] } ?: -1
                for (id in added) {
                    val m = r.message(id) ?: continue
                    if (m.from == r.me.id || m.isNotice) continue
                    val pos = index[id] ?: continue
                    if (top >= 0 && pos < top) newAbove.add(id)
                    else if (!followPending && bottom >= 0 && pos > bottom) newBelow.add(id)
                }
            }
        }
        newAbove.retainAll(ids); newBelow.retainAll(ids)
        // Never move the list under an open menu; it catches up when the menu closes.
        if (menu?.isShowing == true) { updateScrollPills(); return }
        val jump = pendingJumpId
        val divider = a.unreadPosition()
        when {
            jump != null -> { pendingJumpId = null; unreadJump = false; followPending = false; jumpToMessage(jump) }
            unreadJump && divider >= 0 -> {
                unreadJump = false; followPending = false
                lm.scrollToPositionWithOffset(divider, listHeight() / 6)
            }
            followPending -> { unreadJump = false; followPending = false; scrollToBottom() }
            else -> unreadJump = false
        }
        updateScrollPills()
    }

    private fun renderAwaiting() {
        b.title.text = getString(R.string.app_name)
        b.subtitle.text = ""
        b.avatar.text = Ui.initial(getString(R.string.app_name))
        b.hint.isVisible = false; b.awayBanner.isVisible = false; b.errandBanner.isVisible = false
    }

    private fun renderHeader(r: Router) {
        val to = peer
        val name: String
        val sub: String
        if (to == null) {
            name = groupName(r)
            sub = Core.statusLine()
            b.avatar.text = Ui.initial(name)
            b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(r.group.fingerprint))
        } else {
            val p = r.people[to]
            // Someone not heard from in a month is forgotten by the mesh; their messages still know their name.
            val fallback = if (p == null) r.messages.lastOrNull { it.from == to }?.fromName.orEmpty() else ""
            name = Ui.uniqueName(r, to, fallback)
            // The header has one line; the People screen shows the same status with room to wrap.
            sub = p?.let { Ui.personStatus(r, it).replace("\n", " · ") }.orEmpty()
            b.avatar.text = Ui.initial(Ui.nameOf(r, to, fallback))
            b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(to))
        }
        b.title.text = name
        b.subtitle.text = sub
        val muted = Core.isMuted(chatKey)
        b.titleArea.contentDescription = listOf(name, sub, if (muted) getString(R.string.chat_muted_desc) else "")
            .filter { it.isNotEmpty() }.joinToString(", ")
        // Only on change: this runs on every redraw (twice a second while a voice note plays).
        val headerKey = "$chatKey|$muted"
        if (headerKey != headerShown) {
            headerShown = headerKey
            b.title.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (muted) R.drawable.ic_chat_muted else 0, 0)
            ViewCompat.replaceAccessibilityAction(b.titleArea, AccessibilityActionCompat.ACTION_CLICK,
                getString(if (to == null) R.string.chat_group_info else R.string.people), null)
        }
    }

    private fun groupName(r: Router): String = r.group.name.ifEmpty { getString(R.string.chat_your_group) }

    private fun renderBanners(r: Router, shown: List<Message>) {
        val warn = when {
            !Core.bluetoothOn() -> getString(R.string.bt_off)
            !Core.wifiOn() -> getString(R.string.wifi_off)
            Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.isVisible = warn.isNotEmpty()

        // Live location: mine first (with the stop affordance), else whoever is sharing.
        val sharers = if (peer == null) r.people.values.filter { r.liveLocOf(it) != null } else emptyList()
        val banner = when {
            Core.liveLocationActive() -> getString(if (peer == null) R.string.live_banner_me else R.string.chat_live_banner_me_group,
                prettyLeft(Core.liveLocationLeftMs()))
            sharers.size == 1 -> getString(R.string.live_banner_one, Ui.nameOf(r, sharers[0].id))
            sharers.size > 1 -> getString(R.string.live_banner_many, sharers.size)
            else -> ""
        }
        b.liveBanner.text = banner
        b.liveBanner.isVisible = banner.isNotEmpty()

        // Friends waiting on this phone's signal: one slim line, never a wall of cards.
        val sends = Core.pendingSends()
        b.errandBanner.isVisible = sends.isNotEmpty()
        if (sends.isNotEmpty()) b.errandBanner.text = sendsLine(r, sends)

        val to = peer
        if (to == null) {
            b.awayBanner.isVisible = false
            val alone = r.authedLinks().isEmpty() && r.peopleInRange() == 0
            b.hint.isVisible = alone && warn.isEmpty() && shown.isEmpty()
            b.hint.text = getString(if (r.people.isEmpty()) R.string.invite_hint else R.string.alone_hint)
        } else {
            b.hint.isVisible = false
            // Out of range is normal on a mesh; say it where it matters — an empty chat, or words still on their way.
            val p = r.people[to]
            val away = p == null || !r.isInRange(p)
            val waiting = shown.any { it.from == r.me.id && it.isPersonal && it.status != Message.DELIVERED && !Ui.gaveUp(r, it) }
            b.awayBanner.isVisible = away && (shown.isEmpty() || waiting)
            if (b.awayBanner.isVisible) b.awayBanner.text = getString(R.string.out_of_range_dm, Ui.nameOf(r, to))
        }
    }

    private fun sendsLine(r: Router, sends: List<Errand>): String {
        val askers = sends.map { it.from }.distinct()
        val first = sends[0]
        val name = Ui.nameOf(r, first.from, first.fromName)
        return when {
            askers.size > 1 -> resources.getQuantityString(R.plurals.chat_sends_many, askers.size, askers.size)
            sends.size > 1 -> resources.getQuantityString(R.plurals.chat_sends_one_many, sends.size, name, sends.size)
            Errand.isEmailTarget(first.args) -> getString(R.string.chat_sends_one_email, name)
            else -> getString(R.string.chat_sends_one_text, name)
        }
    }

    private fun openPendingSends() {
        val sends = Core.pendingSends()
        val i = Intent(this, InternetActivity::class.java)
        Core.fingerprint()?.let { i.putExtra(Notifications.EXTRA_FP, it) }
        if (sends.size == 1) i.putExtra(Notifications.EXTRA_ERRAND, sends[0].id)
        startActivity(i)
    }

    private fun listHeight(): Int = if (b.list.height > 0) b.list.height else resources.displayMetrics.heightPixels / 2

    private fun isAtBottom(): Boolean {
        val n = adapter?.itemCount ?: 0
        return n == 0 || (b.list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition() >= n - 2
    }

    private fun scrollToBottom(smooth: Boolean = false) {
        val n = b.list.adapter?.itemCount ?: 0
        newBelow.clear()
        if (n > 0) {
            val last = (b.list.layoutManager as LinearLayoutManager).findLastVisibleItemPosition()
            if (smooth && last >= 0 && n - last < 25) b.list.smoothScrollToPosition(n - 1)
            else b.list.post { b.list.scrollToPosition(maxOf(0, (b.list.adapter?.itemCount ?: 1) - 1)) }
        }
        updateScrollPills()
    }

    private fun jumpToNewAbove() {
        val a = adapter ?: return
        val id = newAbove.minByOrNull { a.positionOf(it).let { p -> if (p < 0) Int.MAX_VALUE else p } } ?: return
        newAbove.clear()
        jumpToMessage(id)
    }

    /** "N new above" and the jump button (with a count of what arrived below), kept in step with the scroll. */
    private fun updateScrollPills() {
        val a = adapter
        val lm = b.list.layoutManager as? LinearLayoutManager ?: return
        val first = lm.findFirstVisibleItemPosition()
        val last = lm.findLastVisibleItemPosition()
        val total = a?.itemCount ?: 0
        if (a != null && newAbove.isNotEmpty() && first >= 0) {
            val top = newAbove.minOf { a.positionOf(it).let { p -> if (p < 0) Int.MAX_VALUE else p } }
            if (top == Int.MAX_VALUE || first <= top) newAbove.clear()
        }
        if (a != null && newBelow.isNotEmpty() && last >= 0) {
            if (last >= newBelow.maxOf { a.positionOf(it) }) newBelow.clear()
        }
        b.newAbove.isVisible = newAbove.isNotEmpty()
        if (newAbove.isNotEmpty()) b.newAbove.text = resources.getQuantityString(R.plurals.chat_new_above, newAbove.size, newAbove.size)
        val far = total > 0 && last >= 0 && last < total - 3
        if (far || newBelow.isNotEmpty()) b.jump.show() else b.jump.hide()
        b.jumpBadge.isVisible = (far || newBelow.isNotEmpty()) && newBelow.isNotEmpty()
        b.jumpBadge.text = if (newBelow.size > 99) "99+" else newBelow.size.toString()
        b.jump.contentDescription = if (newBelow.isEmpty()) getString(R.string.scroll_to_latest)
            else resources.getQuantityString(R.plurals.chat_jump_new_desc, newBelow.size, newBelow.size)
    }

    // ------------------------------------------------------------------ header & menu

    private fun openHeader() {
        if (chatFp == null || Core.router == null) return
        startActivity(Intent(this, if (peer == null) GroupInfoActivity::class.java else PeopleActivity::class.java))
    }

    private fun showMenu() {
        if (chatFp == null || Core.router == null) return
        val popup = PopupMenu(this, b.more)
        var order = 0
        if (peer == null) popup.menu.add(0, M_INFO, order++, R.string.chat_group_info)
        popup.menu.add(0, M_MUTE, order++, if (Core.isMuted(chatKey)) R.string.chat_unmute else R.string.chat_mute_title)
        popup.menu.add(0, M_CLEAR, order++, R.string.chat_clear)
        popup.menu.add(0, M_PEOPLE, order++, R.string.people)
        popup.menu.add(0, M_INTERNET, order++, R.string.internet_title)
        if (peer == null) popup.menu.add(0, M_CODE, order++, R.string.show_invite)
        popup.menu.add(0, M_SETTINGS, order, R.string.settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_INFO -> startActivity(Intent(this, GroupInfoActivity::class.java))
                M_MUTE -> toggleMute()
                M_CLEAR -> showPrompt(bundleOf(P_KIND to P_CLEAR, P_TARGET to targetKey()))
                M_PEOPLE -> startActivity(Intent(this, PeopleActivity::class.java))
                M_INTERNET -> startActivity(Intent(this, InternetActivity::class.java))
                M_CODE -> startActivity(Intent(this, CodeActivity::class.java))
                M_SETTINGS -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        popup.show()
    }

    // ------------------------------------------------------------------ message gestures & actions

    private val rowListener = object : MessageAdapter.Listener {
        override fun onTap(m: Message) = showDetails(m)
        override fun onLongPress(m: Message, row: View, bubble: View) = showMessageMenu(m, row, bubble)
        override fun onDoubleTap(m: Message, bubble: View) = doubleTapReact(m, bubble)
        override fun onAttachment(m: Message) = openAttachment(m)
        override fun onLocation(m: Message) = openLocation(m)
        override fun onQuote(m: Message) { m.quote?.let { jumpToMessage(it.id) } }
        override fun onReactions(m: Message) = showReactors(m)
    }

    private fun showMessageMenu(m: Message, row: View, bubble: View) {
        val r = Core.router ?: return
        if (menu?.isShowing == true || isFinishing) return
        val live = r.message(m.id) ?: return
        // Bring the whole message into view first, so its lifted copy isn't cut by the header or composer.
        val pad = (8 * resources.displayMetrics.density).toInt()
        val h = b.list.height
        val dy = when {
            row.height >= h -> 0
            row.top < 0 -> row.top - pad
            row.bottom > h -> row.bottom - h + pad
            else -> 0
        }
        if (dy != 0) b.list.scrollBy(0, dy)
        b.list.post {
            val vh = b.list.findContainingViewHolder(row) as? MessageAdapter.MsgVH
            if (isFinishing || isDestroyed || vh?.m?.id != live.id || menu?.isShowing == true) return@post
            vh.stopFlash()   // the lifted copy is a snapshot of the row: never a half-faded one
            menu = MessageMenu(this, row, bubble, live.reactions[r.me.id], live.isPersonal, actionsFor(r, live),
                onReact = { e -> react(live, e) },
                onMoreReactions = { ReactionSheets.showPicker(this) { e -> react(live, e) }?.let { trackSheet(it) } },
                // Posted: a close during teardown must not redraw from inside it.
                onClosed = { b.list.post { if (!isDestroyed) refresh() } }).also { it.show() }
        }
    }

    private fun actionsFor(r: Router, m: Message): List<MessageMenu.Action> {
        val out = ArrayList<MessageMenu.Action>()
        val mine = m.from == r.me.id
        if (m.isPersonal) out.add(MessageMenu.Action(R.drawable.ic_reply, getString(R.string.reply)) { startReply(m) })
        if (m.isPersonal && m.isGroup && !mine) out.add(MessageMenu.Action(R.drawable.ic_chat_private, getString(R.string.chat_reply_privately)) { replyPrivately(m) })
        if (m.text.isNotEmpty()) out.add(MessageMenu.Action(R.drawable.ic_copy, getString(R.string.copy)) { copy(m) })
        if (fileOnPhone(r, m)) {
            out.add(MessageMenu.Action(R.drawable.ic_chat_save, getString(R.string.chat_save_to_phone)) { saveToPhone(m) })
            out.add(MessageMenu.Action(R.drawable.ic_share, getString(R.string.chat_share)) { shareFile(m) })
        }
        if (mine && m.isPersonal) out.add(MessageMenu.Action(R.drawable.ic_info, getString(R.string.message_info)) { showDetails(m) })
        if (Ui.gaveUp(r, m)) out.add(MessageMenu.Action(R.drawable.ic_chat_retry, getString(R.string.chat_send_again)) { sendAgain(m) })
        out.add(MessageMenu.Action(R.drawable.ic_chat_delete, getString(R.string.chat_delete_for_me), danger = true) { confirmDelete(m) })
        return out
    }

    private fun react(m: Message, emoji: String) {
        val r = Core.router ?: return
        val live = r.message(m.id) ?: return
        if (!live.isPersonal) return
        if (emoji.length > Message.MAX_EMOJI) { toast(getString(R.string.chat_emoji_too_long)); return }
        val mine = live.reactions[r.me.id]
        val next = if (emoji == mine) "" else emoji   // the same one again takes it back
        if (next.isEmpty() && mine == null) return
        r.sendReaction(live, next)
        refresh()
    }

    /** Double-tap a bubble: ❤️ (or take my ❤️ back), with a little burst so it's clear what happened. */
    private fun doubleTapReact(m: Message, bubble: View) {
        val r = Core.router ?: return
        val live = r.message(m.id) ?: return
        if (!live.isPersonal || menu?.isShowing == true) return
        val heart = MessageMenu.DOUBLE_TAP_REACTION
        val adding = live.reactions[r.me.id] != heart
        r.sendReaction(live, if (adding) heart else "")
        bubble.performHapticFeedback(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
        if (adding) burst(heart, bubble)
        refresh()
    }

    @android.annotation.SuppressLint("RtlHardcoded")   // absolute on purpose: x/y below are window coordinates
    private fun burst(emoji: String, over: View) {
        if (!over.isAttachedToWindow) return
        val frame = b.listFrame
        val a = IntArray(2).also { over.getLocationInWindow(it) }
        val f = IntArray(2).also { frame.getLocationInWindow(it) }
        val tv = TextView(this).apply {
            text = emoji
            textSize = 46f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        tv.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // Absolute placement (not START): the burst sits over the bubble in either text direction.
        frame.addView(tv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.LEFT))
        tv.x = (a[0] - f[0] + over.width / 2 - tv.measuredWidth / 2).toFloat()
        tv.y = (a[1] - f[1] + over.height / 2 - tv.measuredHeight / 2).toFloat()
        tv.scaleX = 0.3f; tv.scaleY = 0.3f; tv.alpha = 0f
        val d = resources.displayMetrics.density
        tv.animate().scaleX(1.15f).scaleY(1.15f).alpha(1f).setDuration(220).setInterpolator(OvershootInterpolator(2.5f)).withEndAction {
            tv.animate().translationYBy(-28 * d).alpha(0f).setStartDelay(120).setDuration(260).withEndAction { frame.removeView(tv) }.start()
        }.start()
    }

    private fun showReactors(m: Message) {
        val r = Core.router ?: return
        val live = r.message(m.id) ?: return
        ReactionSheets.showReactors(this, r, live) { react(live, "") }?.let { trackSheet(it) }
    }

    private fun copy(m: Message) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_clip_label), m.text))
        // Android 13+ shows its own "copied" confirmation; a toast on top would say it twice.
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
    }

    /** Plain-English answer to "did it get there?", in a sheet that follows the mesh while open. */
    private fun showDetails(m: Message) {
        val r = Core.router ?: return
        if (r.message(m.id) == null || isFinishing) return
        val sheet = BottomSheetDialog(this)
        val sb = SheetDetailsBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        ViewCompat.setAccessibilityHeading(sb.detailsTitle, true)
        sb.detailsTime.text = getString(R.string.chat_details_sent, MessageAdapter.dayLabel(this, m.ts),
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(m.ts)))
        sb.detailsRetry.setOnClickListener { sheet.dismiss(); sendAgain(m) }
        fun render() {
            val live = if (Core.router === r) r.message(m.id) else null
            if (live == null) { sheet.dismiss(); return }
            sb.detailsBody.text = Ui.statusDetail(this, r, live)
            sb.detailsRetry.isVisible = Ui.gaveUp(r, live)
        }
        val obs = Observer<Int> { if (sheet.isShowing) render() }
        Core.version.observe(this, obs)
        sheet.setOnDismissListener { Core.version.removeObserver(obs) }
        render()
        trackSheet(sheet)
        sheet.show()
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
    }

    /** Toasts can outlive the screen (a send finishing after a rotation): always the app context. */
    private fun toast(text: String) = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).show()

    /** "Save as…" with the file's own name and type (the stock contract fixes one type up front). */
    companion object {
        const val FILES_AUTHORITY = "app.hopline.files"
        /** Open the chat with the reply bar already set to this message id. */
        const val EXTRA_REPLY_TO = "replyTo"
        private const val PENDING_LOC_FIX = "fix"
        private const val PENDING_LOC_LIVE = "live"
        private const val BIG_FILE = 300_000
        private const val MAX_PLACE_INPUT = 400
        private const val MAX_BACK = 8
        /** Capture the unread mark on the next resume. */
        private const val UNREAD_CAPTURE = -1L
        /** No divider until the person leaves the chat (they sent something, or cleared it). */
        private const val UNREAD_NONE = Long.MAX_VALUE

        /** One sweep of old camera shots per process. */
        private var swept = false

        private const val M_INFO = 1
        private const val M_MUTE = 2
        private const val M_CLEAR = 3
        private const val M_PEOPLE = 4
        private const val M_INTERNET = 5
        private const val M_CODE = 6
        private const val M_SETTINGS = 7

        private const val S_CHAT = "chat"
        private const val S_FP = "chatFp"
        private const val S_BACK = "backStack"
        private const val S_CAMERA = "cameraFile"
        private const val S_PICK = "pickTarget"
        private const val S_LOC = "pendingLoc"
        private const val S_REPLY = "replyTo"
        private const val S_UNREAD_SINCE = "unreadSince"
        private const val S_UNREAD_UNTIL = "unreadUntil"
        private const val S_UNREAD_JUMP = "unreadJump"
        private const val S_CHOSEN_NAMES = "chosenNames"
        private const val S_CHOSEN_IDS = "chosenIds"
        private const val S_PROMPT = "prompt"

        private const val P_KIND = "k"
        private const val P_TARGET = "target"
        private const val P_FIELDS = "fields"
        private const val P_CAPTION = "caption"
        private const val P_BIGFILE = "bigfile"
        private const val P_PLACE = "place"
        private const val P_DELETE = "delete"
        private const val P_CLEAR = "clear"
        private const val P_MUTE = "mute"
        private const val P_LIVE_WARN = "liveWarn"
        private const val P_LIVE_FOR = "liveFor"
        private const val P_LIVE_STOP = "liveStop"
        private const val P_FIX = "fix"
        private const val P_SWITCH = "switch"
    }
}
