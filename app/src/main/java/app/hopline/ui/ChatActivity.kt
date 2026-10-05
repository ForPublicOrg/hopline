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
import android.graphics.Color
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.os.SystemClock
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.text.Spanned
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
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
import app.hopline.data.SavedGroup
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
 *
 * The same screen shows the kept chat of a group this phone LEFT, to read ([archive]): no
 * composer, no reactions or replies, nothing that would need the radio — Copy, Save, Share,
 * details and "Delete for me" stay, with the way back in ("Rejoin") where the composer would be.
 *
 * Either way a chat is longer than what the mesh keeps in memory: scrolled to the top, it reads
 * its earlier messages back from the group's history, a page at a time ([earlier]).
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

    /**
     * Set while the screen shows the kept chat of a group this phone left: that group's router
     * with no radio ([Core.archive]). Null means the group on the radio. Which of the two it is
     * comes from the saved group list every time the screen is created, handed a new intent or
     * resumed ([syncMode]) — never from a remembered flag — so a group that was rejoined or
     * deleted from another screen is never shown as it used to be.
     *
     * A chat has the same key in every group ("*" for the group chat, a person's id for a private
     * one). So while this is set, nothing goes through Core's "the group on the radio" helpers —
     * the open chat, read marks, mute, clear, delete, drafts, the status line, the banners: they
     * would silence, mark, mute or empty the chat of the SAME NAME in the group on the radio.
     */
    private var archive: Router? = null
    private val readOnly: Boolean get() = archive != null
    /** The router this screen reads: the left group's kept chat, or the one on the radio. */
    private fun router(): Router? = archive ?: Core.router
    /** The group [router] belongs to. */
    private fun shownFp(): String? = archive?.group?.fingerprint ?: Core.fingerprint()
    /** The left group on screen, as it is saved right now; null in a group this phone is in. */
    private fun leftGroup(): SavedGroup? = if (!readOnly) null else chatFp?.let { Core.store.findGroup(it) }?.takeIf { it.left }
    /**
     * When the group on screen was left; 0 in a group this phone is in. Ticks and delivery details
     * go by it: what was already settled before the leaving is not blamed on the leaving.
     */
    private fun leftAt(): Long = if (!readOnly) 0 else leftGroup()?.leftAt ?: System.currentTimeMillis()
    /**
     * What to say when [Core.archive] gave no chat: that it can't be opened — or, when the phone
     * was only too busy to read it in time, to try again in a moment (and then it opens).
     */
    private fun cantOpen(): Int = if (Core.archiveBusy) R.string.chat_busy else R.string.chat_cant_open

    /** The chat's older messages, read back from the group's history as the person scrolls up. */
    private val earlier = EarlierPages()
    /** A page is being read. One at a time: each starts where the one before it ended. */
    private var earlierBusy = false
    /** Goes up whenever the pages are started over (another chat, a changed history): a page asked
     *  for before that is dropped when it arrives. */
    private var earlierGen = 0
    /** [Core.historyStamp] as of the pages on screen. When it moves, they are read again. */
    private var earlierStamp = 0
    /** The pages being read again, to replace the ones on screen in one go; see [EarlierPages.restart]. */
    private var earlierReload: EarlierPages.Reload? = null
    /** Re-created while scrolled up among earlier messages: the list is filled only once they are
     *  read again, so that it can put its own scroll position back (it goes by row count). */
    private var earlierWait = false
    /** The list's scroll position, carried across a rebuild of the adapter on the same chat. */
    private var keepScroll: Parcelable? = null

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
        val s = savedInstanceState
        // Which group? A re-created screen shows the one it was showing; a new one, the one its
        // intent names (Home's "Groups you left", a left group's info, a stale notification).
        val want = if (s != null) s.getString(S_FP) else intent.getStringExtra(Notifications.EXTRA_FP)
        if (want != null && Core.store.findGroup(want)?.left == true) {
            // A group this phone left is read from its kept chat. That needs no radio, no
            // permissions and no group to be on — this may be a phone that is in no group at all.
            archive = Core.archive(want)
            if (archive == null) { toast(getString(cantOpen())); finishToHome(); return }
        } else {
            if (!Core.store.hasActive()) { toLaunch(); return }
            Core.ensureRunning()
            // No router right after a start means something is missing (a revoked permission):
            // the launch screen works out what and asks for it. A group still starting — its saved
            // chat or its key on the way, seconds after an update or a kill — is shown as it is:
            // "Starting…", and the chat fills in when the router is up ([refresh]).
            if (Core.router == null && !Core.buildPending) { toLaunch(); return }
        }
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        Asks.listen(this)   // "Rejoin" and "Delete group", asked from a left group's chat

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
            // Scrolled up among earlier messages: they are read again before the list is filled.
            val depth = s.getInt(S_EARLIER, -1)
            if (depth >= 0 && chatFp != null) { earlierReload = earlier.reread(depth); earlierWait = true }
        } else {
            peer = intent.getStringExtra(Notifications.EXTRA_PEER)
            pendingReplyId = intent.getStringExtra(EXTRA_REPLY_TO)
            unreadJump = true
        }
        setUpViews()
        if (s == null) {
            val fp = intent.getStringExtra(Notifications.EXTRA_FP)
            intent.removeExtra(Notifications.EXTRA_FP)   // answered now; a re-created screen must not ask again
            if (readOnly) {
                chatFp = fp
                // Opened from a notification that outlived the leaving: nothing can answer it, or its group's others, any more.
                fp?.let { Notifications.clearGroup(this, it) }
            } else if (fp != null && fp != Core.fingerprint()) offerSwitch(fp, peer, pendingReplyId, fromOpen = true)
            else { chatFp = Core.fingerprint(); loadDraft() }
        }
        // Never held back for long: a disk too busy to answer gets the list without them for now.
        if (earlierWait) b.root.postDelayed({ if (earlierWait && !isDestroyed) { earlierWait = false; earlierHere(); refresh() } }, EARLIER_WAIT_MS)
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
        b.leftRejoin.setOnClickListener { leftGroup()?.let { Asks.rejoin(this, it) } }
        b.cantWritePeople.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        b.cantWriteCopy.setOnClickListener { copyOldDraft() }
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
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) { updateScrollPills(); maybeLoadEarlier() }
        })
        applyMode()
    }

    /**
     * The bottom of the screen for the group it shows: a composer for one this phone is in; for one
     * it left, a line saying why there is none, with the way back in. Run again whenever the screen
     * moves between the two — and the keyboard, which a left group's chat has no use for, goes.
     */
    private fun applyMode() {
        val left = readOnly
        b.leftBar.isVisible = left
        if (left) {
            for (v in listOf(b.composerRow, b.recordBar, b.replyBar, b.mentionBar, b.cantWriteBar)) v.isVisible = false
            b.input.clearFocus()
            Ui.hideKeyboard(this, b.input)
        } else if (!b.recordBar.isVisible && !b.cantWriteBar.isVisible) b.composerRow.isVisible = true
        // Coming back to a left group's chat (from a photo, from another app) must not bring a keyboard up either.
        // (Only the "state" half of the window's setting changes; how it resizes stays as the manifest says.)
        val now = window.attributes.softInputMode
        val mode = (now and WindowManager.LayoutParams.SOFT_INPUT_MASK_STATE.inv()) or
            if (left) WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN else WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        if (mode != now) window.setSoftInputMode(mode)
        headerShown = ""   // the header's tap means something else now
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
        // as if it were that one. (From a group this phone left, it opens that group's kept chat.)
        if (fp != null && fp != Core.fingerprint()) { offerSwitch(fp, newPeer, reply, fromOpen = chatFp == null); return }
        // The group on the radio is meant: a left group's chat on screen makes way for it.
        if (readOnly && !leaveArchive()) return
        backStack.clear()
        openChat(newPeer, reply)
    }

    override fun onStart() {
        super.onStart()
        if (!::b.isInitialized || isFinishing) return
        if (prompt != null && promptDialog == null) reaskPrompt()
        else if (chatFp == null && prompt == null) finishToHome()   // nothing on screen and nothing being asked
    }

    /**
     * The question that was open comes back with the screen — but not before the earlier messages
     * a re-created screen is still reading: it may be about one of them ("delete this?").
     */
    private fun reaskPrompt() {
        if (prompt != null && promptDialog == null && !earlierWait && !isFinishing && !isDestroyed) restorePrompt()
    }

    /** The wait for the earlier messages is over. If the screen is showing, its question is asked now; if not, when it next starts. */
    private fun earlierHere() {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) reaskPrompt()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        if (!::b.isInitialized || !viewStatePending) return
        viewStatePending = false
        if (readOnly) return   // a left group's chat has no composer, and never follows the radio
        // The composer has its words back: a move to another group that refresh() put off can run now.
        val r = Core.router
        if (r != null && chatFp != null && chatFp != r.group.fingerprint) refresh()
    }

    override fun onResume() {
        super.onResume()
        // Closed while starting (followGroup left a private chat): Android 8.x still resumes it, and
        // following again would save the composer it already emptied over the draft it put away.
        if (!::b.isInitialized || isFinishing) return
        if (!syncMode()) return
        if (!readOnly && !Permissions.allGranted(this)) {
            // Nearby permission revoked while we were away (e.g. Android auto-revoke). Reading a
            // left group's chat needs none — and its phone is exactly the one Android revokes on.
            toLaunch(); return
        }
        Core.appVisible = true
        VoicePlayer.onChanged = { refresh() }
        if (!readOnly) {
            if (VoiceRecorder.recording) showRecordingBar()   // a rotation must not lose a recording
            val r = Core.router
            if (r != null && !followGroup(r)) return
        }
        onChatVisible()
        refresh()
    }

    override fun onPause() {
        super.onPause()
        if (!::b.isInitialized) return
        Core.appVisible = false
        if (!readOnly) {
            // Both are about the group on the radio: a left group's chat never claimed either.
            if (chatFp != null && chatFp == Core.fingerprint()) Core.markRead(chatKey)
            Core.openChat = null
        }
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
        // How far up the earlier messages went (also when they were just being read again).
        (earlierReload?.let { it.downTo ?: EarlierPages.NEWEST_ONLY } ?: earlier.depth)?.let { outState.putInt(S_EARLIER, it) }
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
        earlierGen++   // a page still being read has no screen to come back to
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
        if (back != null && chatFp != null && chatFp == shownFp()) { openChat(peerOf(back), null); return }
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
        if (readOnly) {
            // A left group's chat is only ever read, and nothing arrives in it. Its read mark is
            // written under ITS group — Core's own "mark read" and "open chat" mean the group on
            // the radio, whose chat of the same key they would mark read and silence.
            if (unreadSince == UNREAD_CAPTURE) captureUnread()
            recheckUnread = false
            Core.store.setLastRead(fp, chatKey, System.currentTimeMillis())
            return
        }
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
        val fp = shownFp()
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
        if (replyId != null && !readOnly) {
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
        keepScroll = null
        resetEarlier()
        updateScrollPills()
    }

    /**
     * The radio moved to another group while this screen was away. A private chat belongs to its
     * group, so it closes; the group chat simply becomes the new group's. False when closing.
     * A left group's chat never follows the radio: it is not on it.
     */
    private fun followGroup(r: Router): Boolean {
        if (readOnly) return true
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

    /**
     * A chat of a group the radio isn't on was asked for (a notification, mostly). A group this
     * phone is in: offer to switch to it. One it left: there is nothing to switch to — its kept
     * chat opens, to read. One it deleted: nothing is left to show.
     */
    private fun offerSwitch(fp: String, newPeer: String?, reply: String?, fromOpen: Boolean) {
        val g = Core.store.findGroup(fp)
        if (g == null || g.left) Notifications.clearGroup(this, fp)   // nothing can answer them any more
        if (g == null) {
            toast(getString(R.string.chat_from_left_group))
            if (fromOpen) finishToHome()
            return
        }
        if (g.left) { openArchive(fp, newPeer, fromOpen); return }
        showPrompt(bundleOf(P_KIND to P_SWITCH, "fp" to fp, "peer" to newPeer, "reply" to reply, "open" to fromOpen))
    }

    private fun doSwitch(code: String, fp: String, newPeer: String?, reply: String?) {
        switching = true
        try {
            leaveChat()
            backStack.clear()
            Core.switchGroup(code)
            // The same goes for a switch that failed: the old chat's draft is already put away. (One
            // still starting is not a failure: the chat shows "Starting…" and fills in when it's up.)
            if ((Core.router == null && !Core.buildPending) || Core.fingerprint() != fp) { chatFp = null; finishToHome(); return }
            archive = null   // asked from a left group's chat: the screen is on the radio's group now
            peer = newPeer
            chatFp = fp
            resetChatState()
            loadDraft()
        } finally { switching = false }
        applyMode()
        if (reply != null) pendingReplyId = reply
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onChatVisible()
        refresh()
    }

    // ------------------------------------------------------------------ a group this phone left

    /**
     * Is the group on screen one this phone is in, or one it left? Asked of the saved group list
     * every time the screen comes to the front, never remembered: either can change behind it — a
     * group left, rejoined or deleted from another screen. False when there is nothing left to
     * show and the screen is closing.
     */
    private fun syncMode(): Boolean {
        val fp = chatFp ?: return true   // still asking whether to switch groups: no chat is on screen
        val g = Core.store.findGroup(fp)
        if (g != null && g.left) {
            // Asked for every time: Core lets a left group's chat go while the app is in the
            // background, and reads it again from disk — as another router.
            val a = Core.archive(fp)
            if (a == null) { toast(getString(cantOpen())); finishToHome(); return false }
            if (archive != null) { adoptArchive(a); return true }
            // It was left while this screen was away. From here on it is only read: nothing of it
            // is saved as a draft, and the screen lets go of its claim on the radio's chat.
            switching = true
            try {
                Core.openChat = null
                archive = a
                leaveChat()
                resetChatState()
            } finally { switching = false }
            applyMode()
            return true
        }
        if (archive == null) return true
        // It was a left group's chat, and the group isn't a left one any more.
        if (g == null) { finishToHome(); return false }   // deleted: nothing to read
        // Rejoined: a group this phone is in again, shown like any other.
        switching = true
        try {
            leaveChat()
            archive = null
            resetChatState()
            loadDraft()
        } finally { switching = false }
        applyMode()
        return true
    }

    /**
     * The left group's chat on screen, as Core holds it now. A new copy of the same chat (read
     * again from disk) has the same rows: the list is rebuilt on it, and put back where it was.
     */
    private fun adoptArchive(a: Router) {
        if (a === archive) return
        keepScroll = if (adapter != null) (b.list.layoutManager as LinearLayoutManager).onSaveInstanceState() else null
        archive = a
    }

    /**
     * Show a chat of a group this phone left, in place: its group chat, or its private chat with
     * [newPeer]. Whatever chat is on screen is put away first — with its draft, if it has a composer.
     */
    private fun openArchive(fp: String, newPeer: String?, fromOpen: Boolean) {
        val a = Core.archive(fp)
        if (a == null) {
            toast(getString(cantOpen()))
            if (fromOpen) finishToHome()
            return
        }
        // Already reading that group: just another of its chats (or the very one on screen).
        if (a === archive && fp == chatFp) { backStack.clear(); openChat(newPeer, null); return }
        switching = true
        try {
            leaveChat()
            backStack.clear()
            if (!readOnly) Core.openChat = null   // this screen no longer shows a chat of the radio's group
            archive = a
            peer = newPeer
            chatFp = fp
            resetChatState()
        } finally { switching = false }
        applyMode()
        if (newPeer == null) intent.removeExtra(Notifications.EXTRA_PEER) else intent.putExtra(Notifications.EXTRA_PEER, newPeer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) onChatVisible()
        refresh()
    }

    /**
     * From a left group's chat to the group on the radio (a notification of its was tapped): the
     * same guards as a fresh open. False when there is no such group to show; the screen then
     * stays as it is, or — when only a permission is missing — hands over to the launch screen.
     */
    private fun leaveArchive(): Boolean {
        if (!Core.store.hasActive()) return false
        Core.ensureRunning()
        if (Core.router == null && !Core.buildPending) { toLaunch(); return false }
        switching = true
        try {
            leaveChat()
            backStack.clear()
            archive = null
            chatFp = null   // openChat takes it from here, as for any chat it opens
            resetChatState()
        } finally { switching = false }
        applyMode()
        return true
    }

    // ------------------------------------------------------------------ drafts

    /**
     * Only a chat with a composer has a draft: a left group's is never saved, and never looked for.
     * Nor is a private chat's from before the update: its composer never comes back, so words
     * waiting in it (typed before the update, or a reply from a notification) are shown in its
     * bar instead, to copy out ([renderBottom]) — and the empty composer must not save over them.
     */
    private fun saveDraft() {
        if (!::b.isInitialized || readOnly || fromBefore()) return
        val fp = chatFp ?: return
        ChatDrafts.put(this, fp, chatKey, ChatDrafts.Draft(b.input.text?.toString().orEmpty(), replyTo?.id ?: pendingReplyId, HashMap(chosenMentions)))
    }

    /** A private chat from before the update (an 8-letter id): nothing can be written in it, ever. */
    private fun fromBefore(): Boolean = peer?.let { !ChatRules.listed(it) } == true

    /** [fromBefore]'s words that were waiting to be sent: copied, and then gone from the chat. */
    private fun copyOldDraft() {
        val fp = chatFp ?: return
        val words = ChatDrafts.get(this, fp, chatKey)?.text?.trim().orEmpty()
        if (words.isNotEmpty()) {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_clip_label), words))
            // Android 13+ shows its own "copied"; the toast says where the words can go.
            toast(getString(R.string.chat_old_draft_copied))
        }
        ChatDrafts.put(this, fp, chatKey, null)
        Core.router?.let { renderBottom(it) }
    }

    private fun loadDraft() {
        if (readOnly || fromBefore()) return
        val fp = chatFp ?: return
        val d = ChatDrafts.get(this, fp, chatKey) ?: return
        b.input.setText(d.text)
        b.input.setSelection(b.input.text?.length ?: 0)
        chosenMentions.putAll(d.chosen)
        d.replyId?.let { id -> Core.router?.message(id)?.let { startReply(it, focus = false) } ?: run { pendingReplyId = id } }
    }

    // ------------------------------------------------------------------ sending

    private fun sendText() {
        if (readOnly) return
        val text = b.input.text?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        // Still starting: the words stay where they are, for a tap in a moment.
        val r = Core.router ?: run { toast(getString(R.string.starting_try_again)); return }
        val quote = replyTo?.let { quoteOf(r, it) }
        val to = peer
        // Nothing goes to a private chat nothing can be sealed for (its composer is hidden, see
        // renderBottom); should a tap get here all the same, the typed words stay.
        val mine = if (to == null) r.sendChat(text, quote, Ui.mentionsIn(r, text, chosenMentions)) else r.sendDm(to, text, quote)
        if (mine == null) return
        b.input.setText("")
        clearReply()
        sent()
    }

    /**
     * After anything I send: on disk at once — a phone killed a moment later must not lose it —
     * then show it, drop the unread divider (I've caught up), keep the draft honest.
     */
    private fun sent() {
        Core.saveNow()
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
        if (!m.isPersonal || readOnly) return
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

    private fun replyPrivately(m: Message) {
        openChat(m.from, m.id, push = true)
        // An earlier message is not one the router can look up by id: the reply starts from the
        // message itself — also over the reply a saved draft of that chat brought back with it.
        if (ScreenRules.replyStillToSet(replyTo?.id, m.id)) startReply(m)
    }

    /** A message on screen, by id: in the router's live window, or among the earlier ones read back. */
    private fun find(r: Router, id: String): Message? = r.message(id) ?: earlier.find(id)

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
                // Nothing to reply into in a left group, or in a private chat that can't be written in.
                if (!m.isPersonal || menu?.isShowing == true || !writable()) return 0
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
                    adapter?.messageAt(vh.bindingAdapterPosition)?.let { m -> Core.router?.let { r -> find(r, m.id) }?.let { startReply(it) } }
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
            val r = router()
            val orig = r?.message(id)
            if (r != null && orig != null) {
                val other = if (orig.isGroup) null else if (orig.from == r.me.id) orig.to else orig.from
                if (other != peer) { openChat(other, null, push = true, jumpTo = id); return }
            }
            // Not in what is loaded. With earlier messages still on disk it may well be among
            // them, further up — then "isn't on this phone" would be a guess, and nothing is said.
            if (chatFp?.let { Core.hasEarlier(it) } != true) toast(getString(R.string.original_gone))
            return
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
        val open = if (r == null || chatFp == null || readOnly) null else openMention()
        // People on 2.4 or later only: an old id's entry is kept for its old messages' names, nobody to call out.
        val matches = if (r == null || open == null) emptyList()
        else r.people.values.filter { ChatRules.listed(it.id) && it.name.isNotEmpty() && it.name.startsWith(open.second, ignoreCase = true) }
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
        if (readOnly) { temp?.delete(); return }
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
        if (r == null || readOnly || !checkTarget(target)) { temp?.delete(); return }
        val mentions = if (peer == null) Ui.mentionsIn(r, caption, chosenMentions) else emptyList()
        if (prefill.isNotEmpty() && caption == prefill) b.input.setText("")
        val quote = replyTo?.let { quoteOf(r, it) }
        clearReply()
        toast(getString(R.string.sending_file))
        val t = targetKey()
        Core.sendImage(uri, caption, peer, quote, mentions, cleanup = { temp?.delete() }) { err -> onSent(err, t) }
    }

    private fun sendPickedFile(uri: Uri, target: String) {
        if (readOnly) return
        val r = Core.router ?: return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        val app = applicationContext
        Thread {
            val result = Blobs.readPicked(app, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed || readOnly || !checkTarget(target)) return@runOnUiThread
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
        if (readOnly) return
        val r = Core.router ?: return
        val quote = replyTo?.let { quoteOf(r, it) }
        clearReply()
        val t = targetKey()
        Core.sendFileBytes(picked, "", peer, quote = quote) { err -> onSent(err, t) }
    }

    private fun launchCamera() {
        if (readOnly) return
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
        val r = router() ?: return
        val att = m.att ?: return
        val file = Blobs.fileFor(this, r.group.fingerprint, att)
        if (!file.exists()) {
            toast(when {
                // Every piece came, but it didn't check out: it never will.
                att.failed -> getString(R.string.chat_file_bad)
                // Its pieces went when the group was left: no more of it will ever arrive.
                readOnly -> getString(R.string.chat_file_left)
                // Still arriving when Hopline was updated: its pieces went with the old format.
                ChatRules.lostInUpdate(att) -> getString(if (m.from == r.me.id) R.string.chat_file_expired else R.string.chat_file_before_update)
                MessageAdapter.neverArrives(m, System.currentTimeMillis()) -> getString(R.string.chat_file_expired)
                else -> getString(R.string.receiving_file, r.fileProgress(att), att.chunks)
            })
            return
        }
        if (att.isInlineImage) {
            // The viewer finds the photo's message by its file — but only one the mesh holds in
            // memory. For an earlier message it is told who sent it, when and with what words.
            startActivity(Intent(this, ViewerActivity::class.java)
                .putExtra("path", file.absolutePath).putExtra("name", att.name).putExtra("mime", att.mime)
                .putExtra(ViewerActivity.EXTRA_FROM, if (m.from == r.me.id) getString(R.string.reply_you) else Ui.nameOf(r, m.from, m.fromName))
                .putExtra(ViewerActivity.EXTRA_AT, m.ts).putExtra(ViewerActivity.EXTRA_CAPTION, m.text))
            return
        }
        // Under its real name, so the other app shows "report.pdf", not a piece id — and as the kind
        // its name says, never what its sender said; an app installer is not opened at all (MediaRules).
        media.openWith(file, att.name, att.mime)
    }

    private fun fileOnPhone(r: Router, m: Message): Boolean = m.att?.let { Blobs.fileFor(this, r.group.fingerprint, it).exists() } == true

    /** Keep a photo, voice note or file outside Hopline — a copy of the person's own, which outlives even deleting the group. */
    private fun saveToPhone(m: Message) {
        val r = router() ?: return
        val att = m.att ?: return
        media.save(Blobs.fileFor(this, r.group.fingerprint, att), att.name)
    }

    private fun shareFile(m: Message) {
        val r = router() ?: return
        val att = m.att ?: return
        media.share(Blobs.fileFor(this, r.group.fingerprint, att), att.name, att.mime)
    }

    /**
     * A message of mine that stopped trying gets a fresh start: a new message (new id, new time),
     * because every phone drops the old one once its 48 h are up. The stale one then goes.
     */
    private fun sendAgain(m: Message) {
        if (readOnly) return   // nothing is sent from a group this phone left
        val r = Core.router ?: return
        val old = find(r, m.id) ?: return
        // A private message goes again only to a phone whose key is known here (Router.canWriteTo):
        // "Send again" isn't offered otherwise (canSendAgain), and the old one stays "not sent".
        if (!canSendAgain(r, old)) return
        val att = old.att
        val loc = old.loc
        when {
            loc != null -> if (r.sendLocation(loc, old.to) != null) { forget(old.id); sent() }
            att != null -> {
                if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
                if (!Blobs.fileFor(this, r.group.fingerprint, att).exists()) { toast(getString(R.string.chat_resend_gone)); return }
                toast(getString(R.string.sending_file))
                val t = targetKey()
                Core.resendFile(old) { err ->
                    // Core deleted the stale copy once the new one was on its way; an earlier one leaves the screen with it.
                    if (err == null && t == targetKey()) earlier.remove(listOf(old.id))
                    onSent(err, t)
                }
            }
            else -> {
                val to = old.to
                val again = if (to == null) r.sendChat(old.text, old.quote, old.mentions) else r.sendDm(to, old.text, old.quote)
                if (again == null) return
                forget(old.id)
                sent()
            }
        }
    }

    /** "Send again" for [m]: one of mine that stopped trying, in a chat of the radio's group that can still be written to. */
    private fun canSendAgain(r: Router, m: Message): Boolean =
        !readOnly && ChatRules.sendAgain(Ui.gaveUp(r, m), m.to, m.to?.let { r.canWriteTo(it) } ?: true)

    /** The stale copy of a message that was sent again goes — from the chat, and from the earlier ones on screen. */
    private fun forget(id: String) {
        earlier.remove(listOf(id))
        Core.deleteMessages(listOf(id))
    }

    // ------------------------------------------------------------------ voice notes

    private fun onMicTapped() {
        val r = Core.router ?: return
        if (chatFp == null || !writable()) return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startRecording()
        else micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startRecording() {
        if (readOnly) return
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
        b.composerRow.isVisible = !readOnly && !b.cantWriteBar.isVisible
        b.recordDot.animate().cancel(); b.recordDot.alpha = 1f
        if (!send || readOnly) { VoiceRecorder.cancel(); return }
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
        if (chatFp == null || !writable()) return
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
        if (!writable()) return
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
        if (readOnly) return
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
        if (readOnly) return
        if (Core.liveLocationActive()) showPrompt(bundleOf(P_KIND to P_LIVE_STOP))
        else startActivity(Intent(this, PeopleActivity::class.java))
    }

    private fun askForPlace() = showPrompt(bundleOf(P_KIND to P_PLACE, P_TARGET to targetKey()))

    private fun openLocation(m: Message) {
        val loc = m.loc ?: return
        val r = router()
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

    private fun buildPrompt(p: Bundle): Dialog? {
        val kind = p.getString(P_KIND)
        // A left group's chat asks two questions only: delete this message, and switch to the
        // group a notification came from. Any other is a leftover of a chat on the radio (a
        // re-creation, the group left under it) and its answer would act on THAT group: dropped.
        if (readOnly && kind != P_DELETE && kind != P_SWITCH) return null
        return when (kind) {
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
            if (r.sendLocation(loc, peer) != null) sent()
        }
        promptFields = asked.fields
        return asked.dialog
    }

    private fun confirmDelete(m: Message) = showPrompt(bundleOf(P_KIND to P_DELETE, P_TARGET to targetKey(), "id" to m.id))

    private fun deleteDialog(p: Bundle): Dialog? {
        val r = router() ?: return null
        val m = find(r, p.getString("id") ?: return null) ?: return null
        // Mine and still ◷: it never left this phone, so deleting it also means it won't be sent.
        // (In a left group nothing is waiting to be sent: there it is only ever "for me".)
        val unsent = !readOnly && m.from == r.me.id && m.status == Message.QUEUED
        return AlertDialog.Builder(this).setTitle(R.string.chat_delete_title)
            .setMessage(if (unsent) R.string.chat_delete_unsent else R.string.chat_delete_body)
            .setPositiveButton(R.string.chat_delete) { _, _ -> if (checkTarget(p.getString(P_TARGET))) deleteMessages(listOf(m.id)) }
            .setNegativeButton(R.string.cancel, null).show().also { danger(it) }
    }

    /**
     * "Delete for me". Earlier messages on screen go at once; Core takes them out of the history.
     * A left group's chat has its own delete: Core's plain one means the group on the radio.
     */
    private fun deleteMessages(ids: List<String>) {
        if (replyTo?.id in ids) clearReply()
        earlier.remove(ids)
        val fp = chatFp
        if (!readOnly) Core.deleteMessages(ids)
        else if (fp != null) {
            Core.deleteArchivedMessages(fp, ids) {
                // The delete could not be saved (Core has said so), and nothing of it was deleted:
                // the chat is read again as the disk has it, with those messages in it.
                if (!isDestroyed && !isFinishing && readOnly && chatFp == fp &&
                    lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && syncMode()) refresh()
            }
            // Core deleted from the copy it holds — the one on screen, unless that was let go meanwhile.
            Core.archive(fp)?.let { adoptArchive(it) }
        }
        refresh()
    }

    private fun clearDialog(p: Bundle): Dialog? {
        val r = Core.router ?: return null
        val unsent = r.chatMessages(peer).count { it.from == r.me.id && it.status == Message.QUEUED }
        val body = getString(R.string.chat_clear_body) +
            if (unsent > 0) "\n\n" + resources.getQuantityString(R.plurals.chat_clear_unsent, unsent, unsent) else ""
        return AlertDialog.Builder(this).setTitle(R.string.chat_clear_title).setMessage(body)
            .setPositiveButton(R.string.chat_clear) { _, _ ->
                if (readOnly || !checkTarget(p.getString(P_TARGET))) return@setPositiveButton
                Core.clearChat(peer)
                resetEarlier()   // its earlier messages went with it
                if (replyTo?.let { Core.router?.message(it.id) } == null) clearReply()
                newAbove.clear(); newBelow.clear()
                unreadSince = UNREAD_NONE
                refresh()
            }
            .setNegativeButton(R.string.cancel, null).show().also { danger(it) }
    }

    private fun toggleMute() {
        if (readOnly) return
        if (Core.isMuted(chatKey)) { Core.setMuted(chatKey, 0); toast(getString(R.string.chat_unmuted)); refresh() }
        else showPrompt(bundleOf(P_KIND to P_MUTE, P_TARGET to targetKey()))
    }

    private fun muteDialog(p: Bundle): Dialog {
        val labels = arrayOf(getString(R.string.chat_mute_8h), getString(R.string.chat_mute_week), getString(R.string.chat_mute_always))
        return AlertDialog.Builder(this).setTitle(R.string.chat_mute_title)
            .setItems(labels) { _, which ->
                if (readOnly || !checkTarget(p.getString(P_TARGET))) return@setItems
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
                Loc.of(l.latitude, l.longitude, l.accuracy.toInt())?.let { if (r.sendLocation(it, peer) != null) sent() }
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
        val r = router()
        if (r == null) {
            // A group that can't start after all (a permission taken back, its key pair unreadable):
            // the launch screen works out what is missing, as for a fresh open.
            if (!readOnly && !Core.buildPending && Core.store.hasActive() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                toLaunch(); return
            }
            renderStarting(); return
        }
        if (chatFp == null) { renderAwaiting(); return }
        // Back after the process died, and the radio is on another group now: following it this
        // early would save an empty composer over the real draft, then the restored words would
        // land in the new group's composer. onRestoreInstanceState runs this again once they're back.
        // (A left group's chat has no composer and follows nothing.)
        if (!readOnly && viewStatePending && chatFp != r.group.fingerprint) return
        if (!followGroup(r)) return
        val fp = chatFp ?: return
        // The first draw already knows where "unread" starts, so it can open right there.
        if (unreadSince == UNREAD_CAPTURE) captureUnread()
        val live = r.chatMessages(peer)
        // Messages leaving the live window for the history stay on screen for someone reading near them.
        earlier.follow(live, r) { nearEarlier(r, live) }
        // The group's history changed under the earlier messages on screen — a batch of this
        // chat's oldest live messages was filed, or something was deleted from it.
        val stamp = Core.historyStamp(fp)
        if (stamp != earlierStamp) { earlierStamp = stamp; restartEarlier(r, live) }
        if (earlierWait) {
            // Re-created while scrolled up among earlier messages. The list puts its own position
            // back, but it goes by row count: it is filled only once those messages are here again.
            if (earlierReload != null && Core.hasEarlier(fp)) {
                if (!earlierBusy) fetchEarlier()
                renderHeader(r)
                renderBottom(r)
                renderBanners(r, live)
                return
            }
            earlierWait = false; earlierReload = null; earlierBusy = false; earlierGen++   // nothing to wait for
            earlierHere()
        }
        var a = adapter
        if (a == null || adapterRouter !== r) {
            a = MessageAdapter(this, r, showNames = peer == null, listener = rowListener, leftAt = leftAt())
            adapter = a; adapterRouter = r
            b.list.adapter = a
            lastShownId = null; committedIds = emptySet()
            // The same chat on a fresh copy of its router (a left group's, read again from disk):
            // the same rows, so the list goes back to where it was instead of to the bottom.
            keepScroll?.let { (b.list.layoutManager as LinearLayoutManager).onRestoreInstanceState(it); restoring = true }
            keepScroll = null
        }
        // A reply target from a draft or a re-creation; one no longer on this phone is dropped.
        pendingReplyId?.let { id -> pendingReplyId = null; if (!readOnly) find(r, id)?.let { startReply(it, focus = false) } }
        // The chat as it is shown: its earlier messages, read back from the history, then the live ones.
        val shown = earlier.shown(live, r)
        renderHeader(r)
        renderBottom(r)
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
        a.submit(shown, unread, topLine(fp)) { committed(list, ids, anchorTop, anchorBottom, restored) }
    }

    /**
     * The line above a chat's first message — how it is sealed (ChatRules.chip) — once its first
     * message is on screen: with earlier ones still on disk, it waits above them, where the chat starts.
     */
    private fun topLine(fp: String): String? {
        val atStart = !Core.hasEarlier(fp) || earlier.exhausted || noEarlier[targetKey()] == Core.historyStamp(fp)
        if (!atStart) return null
        return when (ChatRules.chip(readOnly, peer)) {
            ChatRules.Chip.GROUP -> getString(R.string.chat_secure_group)
            ChatRules.Chip.PRIVATE -> getString(R.string.chat_secure_private)
            ChatRules.Chip.NONE -> null
        }
    }

    /** The diff landed: pills for arrivals out of sight, then the one scroll this refresh earned. */
    private fun committed(a: MessageAdapter, ids: Set<String>, anchorTop: String?, anchorBottom: String?, restored: Boolean) {
        if (isDestroyed || adapter !== a) return
        val r = router() ?: return
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
        // Once the rows are laid out: a chat too short to scroll, or one opened near its top,
        // fetches its earlier messages without waiting for a scroll that can't happen.
        b.list.post { maybeLoadEarlier() }
    }

    // ------------------------------------------------------------------ earlier messages

    /**
     * At (or near) the top of what is loaded — or with a chat too short to scroll at all — and
     * the group has older messages on disk: read the page above. One page at a time; the rows it
     * brings are put in above the ones on screen, which stay where they are.
     */
    private fun maybeLoadEarlier() {
        if (!::b.isInitialized || isDestroyed || isFinishing || switching) return
        if (earlierBusy || earlierReload != null || earlier.exhausted || adapter == null) return
        val fp = chatFp ?: return
        val lm = b.list.layoutManager as? LinearLayoutManager ?: return
        if (lm.findFirstVisibleItemPosition() > EARLIER_NEAR_TOP && b.list.canScrollVertically(-1)) return
        if (!Core.hasEarlier(fp) || noEarlier[targetKey()] == Core.historyStamp(fp)) return
        fetchEarlier()
    }

    /**
     * Ask Core for one page: the next one up, or — while the pages are being read again — the next
     * of those. The answer comes later, from the thread that owns the history; by then the screen
     * may show another chat or have started over, and a page nobody is waiting for is dropped.
     */
    private fun fetchEarlier() {
        val fp = chatFp ?: return
        val gen = earlierGen
        val re = earlierReload
        val key = targetKey()
        val stamp = Core.historyStamp(fp)
        earlierBusy = true
        Core.loadEarlier(fp, chatKey, if (re != null) re.next else earlier.next) { page ->
            if (gen != earlierGen || isDestroyed) return@loadEarlier
            earlierBusy = false
            if (re != null) {
                if (earlier.take(re, page.messages, page.next)) { fetchEarlier(); return@loadEarlier }
                earlier.finish(re)
                earlierReload = null
                if (earlierWait) { earlierWait = false; earlierHere() }
            } else {
                earlier.add(page.messages, page.next)
                // Looked through the whole history and this chat has nothing in it (a short private
                // chat beside a long group chat): not looked through again until the history changes.
                if (earlier.exhausted && earlier.isEmpty && stamp == Core.historyStamp(fp)) noEarlier[key] = stamp
            }
            refresh()
        }
    }

    /**
     * The history changed under the pages on screen. Someone reading up there gets them read
     * again, and swapped in when they are all here — nothing moves under their thumb. Someone far
     * below, among the newest messages, would never see the difference: for them the pages are
     * simply let go (they come back on the way up), so a chat that stays open for days does not
     * re-read, and carry, more and more of the history each time a batch is filed.
     */
    private fun restartEarlier(r: Router, live: List<Message>) {
        val pending = earlierReload
        if (pending == null && (earlier.isEmpty || !nearEarlier(r, live))) {
            if (!earlier.isEmpty || earlier.next != null) { earlierGen++; earlierBusy = false; earlier.unload() }
            return
        }
        val re = pending?.again() ?: earlier.restart() ?: return
        earlierGen++   // a page still on its way was read from the history as it was before
        earlierReload = re
        fetchEarlier()
    }

    /**
     * Is the top of the screen at (or close to) the chat's oldest live messages, or above them,
     * among the earlier ones? Those are the rows a filed batch takes out of the live window.
     */
    private fun nearEarlier(r: Router, live: List<Message>): Boolean {
        val a = adapter ?: return false
        val first = (b.list.layoutManager as? LinearLayoutManager)?.findFirstVisibleItemPosition() ?: return false
        if (first < 0) return false
        // The first message at or under the top edge (a day chip or the unread line may sit above it).
        val top = (first until minOf(first + 4, a.itemCount)).firstNotNullOfOrNull { a.messageAt(it) } ?: return false
        if (r.message(top.id) == null) return true   // an earlier message, or one that has just left the window
        for (i in 0 until minOf(live.size, EARLIER_NEAR_LIVE)) if (live[i].id == top.id) return true
        return false
    }

    /** Another chat, or this one emptied: no earlier messages, and none on their way. */
    private fun resetEarlier() {
        earlierGen++
        earlier.clear()
        earlierReload = null; earlierBusy = false; earlierWait = false
    }

    private fun renderAwaiting() {
        b.title.text = getString(R.string.app_name)
        b.subtitle.text = ""
        b.avatar.text = Ui.initial(getString(R.string.app_name))
        b.hint.isVisible = false; b.awayBanner.isVisible = false; b.errandBanner.isVisible = false
    }

    /**
     * The group on the radio has no router yet: its saved chat or its key is still on the way
     * (Core.buildPending). The header says so — the group chat by its saved name — and the chat
     * fills in by itself the moment the router is up.
     */
    private fun renderStarting() {
        b.subtitle.text = getString(R.string.chat_starting)
        if (peer != null || readOnly) return
        val name = Core.store.activeGroup()?.name?.ifEmpty { null } ?: getString(R.string.chat_your_group)
        b.title.text = name
        b.avatar.text = Ui.initial(name)
    }

    /**
     * Can the chat on screen be written in? Not a left group's, and not a private chat nothing can be
     * sealed for (ChatRules.bottom). While the group is still starting there is nothing to ask yet:
     * the composer stays, and a send waits for it.
     */
    private fun writable(): Boolean {
        if (readOnly) return false
        val to = peer ?: return true
        val r = Core.router ?: return true
        return r.canWriteTo(to)
    }

    /**
     * In a private chat nothing can be sealed for, one line takes the composer's place, saying why —
     * and nothing else is offered to write, attach, record or react with. Looked at on every redraw:
     * the person's key can arrive at any moment, and the composer comes back then, with whatever
     * words were waiting in it (a reply typed into a notification, say).
     */
    private fun renderBottom(r: Router) {
        if (readOnly) { b.cantWriteBar.isVisible = false; return }
        val to = peer
        val bottom = ChatRules.bottom(readOnly = false, peer = to, canWrite = to == null || r.canWriteTo(to))
        val shut = bottom != ChatRules.Bottom.COMPOSER
        if (shut && to != null) {
            b.cantWriteText.text = Ui.cantWriteLine(r, to).orEmpty()
            b.cantWritePeople.isVisible = bottom == ChatRules.Bottom.FROM_BEFORE
        }
        // A chat from before the update keeps no composer to bring words back to: any that were
        // waiting in it are shown here, to copy into the person's new chat.
        val waiting = if (bottom == ChatRules.Bottom.FROM_BEFORE) chatFp?.let { fp ->
            ChatDrafts.get(this, fp, chatKey)?.text?.replace('\n', ' ')?.trim()?.ifEmpty { null }
        } else null
        b.cantWriteDraftRow.isVisible = waiting != null
        if (waiting != null) b.cantWriteDraft.text = getString(R.string.chat_old_draft, waiting)
        if (shut) {
            if (!b.cantWriteBar.isVisible) {
                // Just shut: a recording, or the keyboard, has nowhere to go.
                if (VoiceRecorder.recording) finishRecording(send = false)
                b.input.clearFocus()
                Ui.hideKeyboard(this, b.input)
            }
            // Every time: a draft's reply, brought back with the chat, waits out of sight too.
            for (v in listOf(b.composerRow, b.recordBar, b.replyBar, b.mentionBar)) v.isVisible = false
        } else if (b.cantWriteBar.isVisible) {
            b.composerRow.isVisible = true
            b.replyBar.isVisible = replyTo != null
        }
        b.cantWriteBar.isVisible = shut
    }

    private fun renderHeader(r: Router) {
        val to = peer
        // A left group goes by the name on its Home row (its code's words when it never had one);
        // where the radio's status would be, the header says why this chat is only to be read.
        val left = leftGroup()
        val group = left?.let { Asks.groupLabel(it) } ?: groupName(r)
        val name: String
        val sub: String
        var grey = false
        if (to == null) {
            name = group
            sub = if (readOnly) getString(R.string.left_chat_sub) else Core.statusLine()
            b.avatar.text = Ui.initial(name)
            // Grey like its row on Home: not a group this phone is in.
            grey = readOnly
            b.avatar.background.mutate().setTint(if (grey) getColor(R.color.surface_variant) else MessageAdapter.avatarColor(r.group.fingerprint))
        } else {
            val p = r.people[to]
            // Someone not heard from in a month is forgotten by the mesh; their messages still know their name.
            val fallback = if (p == null) r.messages.lastOrNull { it.from == to }?.fromName.orEmpty() else ""
            name = Ui.uniqueName(r, to, fallback)
            // The header has one line; the People screen shows the same status with room to wrap.
            // ("In range" is not something a phone knows about people in a group it left.)
            sub = if (readOnly) getString(R.string.left_dm_sub, group) else p?.let { Ui.personStatus(r, it).replace("\n", " · ") }.orEmpty()
            b.avatar.text = Ui.initial(Ui.nameOf(r, to, fallback))
            b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(to))
        }
        b.avatar.setTextColor(if (grey) getColor(R.color.text_muted) else Color.WHITE)
        b.title.text = name
        b.subtitle.text = sub
        val muted = !readOnly && Core.isMuted(chatKey)
        // The shield before a private chat's name: its security code was verified on this phone.
        val verified = to != null && Core.store.isVerified(to)
        b.titleArea.contentDescription = listOf(if (verified) getString(R.string.verified_desc, name) else name, sub,
            if (muted) getString(R.string.chat_muted_desc) else "").filter { it.isNotEmpty() }.joinToString(", ")
        // Only on change: this runs on every redraw (twice a second while a voice note plays).
        val headerKey = "$chatKey|$muted|$readOnly|$verified"
        if (headerKey != headerShown) {
            headerShown = headerKey
            b.title.setCompoundDrawablesRelativeWithIntrinsicBounds(if (verified) R.drawable.ic_verified else 0, 0,
                if (muted) R.drawable.ic_chat_muted else 0, 0)
            // In a left group every header leads to that group's info: its People screen is the radio's.
            ViewCompat.replaceAccessibilityAction(b.titleArea, AccessibilityActionCompat.ACTION_CLICK,
                getString(if (to == null || readOnly) R.string.chat_group_info else R.string.people), null)
        }
    }

    private fun groupName(r: Router): String = r.group.name.ifEmpty { getString(R.string.chat_your_group) }

    private fun renderBanners(r: Router, shown: List<Message>) {
        if (readOnly) {
            // The radio's troubles, live locations, requests waiting for this phone, who is out
            // of range: all of it is about the group on the radio, none of it about this one.
            for (v in listOf(b.warn, b.liveBanner, b.errandBanner, b.hint, b.awayBanner)) v.isVisible = false
            return
        }
        val warn = when {
            !Core.bluetoothOn() -> getString(R.string.bt_off)
            !Core.wifiOn() -> getString(R.string.wifi_off)
            Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.isVisible = warn.isNotEmpty()

        // Live location: mine first (with the stop affordance), else whoever is sharing.
        val sharers = if (peer == null) r.people.values.filter { ChatRules.listed(it.id) && r.liveLocOf(it) != null } else emptyList()
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
            // Not in a chat that can't be written in: the line in the composer's place says it all.
            b.awayBanner.isVisible = away && (shown.isEmpty() || waiting) && r.canWriteTo(to)
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
        if (readOnly) return
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
        if (chatFp == null || router() == null) return
        if (readOnly) {
            // The left group's own info — never the radio's Group info or People, which are another group's.
            leftGroup()?.let { startActivity(Intent(this, GroupInfoActivity::class.java).putExtra(GroupInfoActivity.EXTRA_CODE, it.code)) }
            return
        }
        startActivity(Intent(this, if (peer == null) GroupInfoActivity::class.java else PeopleActivity::class.java))
    }

    private fun showMenu() {
        if (chatFp == null || router() == null) return
        val popup = PopupMenu(this, b.more)
        if (readOnly) {
            // Everything else on the usual menu belongs to the group on the radio.
            popup.menu.add(0, M_INFO, 0, R.string.chat_group_info)
            popup.menu.add(0, M_REJOIN, 1, R.string.menu_rejoin_group)
            popup.menu.add(0, M_DELETE_GROUP, 2, R.string.menu_delete_group)
            popup.setOnMenuItemClickListener { item ->
                // The group as it is when tapped, not when the menu opened.
                val g = leftGroup()
                when {
                    g == null -> {}
                    item.itemId == M_INFO -> openHeader()
                    item.itemId == M_REJOIN -> Asks.rejoin(this, g)
                    item.itemId == M_DELETE_GROUP -> Asks.deleteGroup(this, g)
                }
                true
            }
            popup.show()
            return
        }
        var order = 0
        if (peer == null) popup.menu.add(0, M_INFO, order++, R.string.chat_group_info)
        // A private chat with someone on 2.4 or later: the number to compare with them.
        if (peer?.let { ChatRules.listed(it) } == true) popup.menu.add(0, M_VERIFY, order++, R.string.chat_verify)
        popup.menu.add(0, M_MUTE, order++, if (Core.isMuted(chatKey)) R.string.chat_unmute else R.string.chat_mute_title)
        popup.menu.add(0, M_CLEAR, order++, R.string.chat_clear)
        popup.menu.add(0, M_PEOPLE, order++, R.string.people)
        popup.menu.add(0, M_INTERNET, order++, R.string.internet_title)
        if (peer == null) popup.menu.add(0, M_CODE, order++, R.string.show_invite)
        popup.menu.add(0, M_SETTINGS, order, R.string.settings)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                M_INFO -> startActivity(Intent(this, GroupInfoActivity::class.java))
                M_VERIFY -> verifyPeer()
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

    /** "Verify security code" for the person this private chat is with. */
    private fun verifyPeer() {
        val to = peer ?: return
        val r = Core.router ?: return
        SecurityCodeDialog.show(this, to, Ui.nameOf(r, to, r.messages.lastOrNull { it.from == to }?.fromName.orEmpty()))
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
        val r = router() ?: return
        if (menu?.isShowing == true || isFinishing) return
        val live = find(r, m.id) ?: return
        // Reactions travel on the radio and land on the live message: none in a group this phone
        // left, none in a private chat that can't be written in (a reaction there is sealed like a
        // message), and none on a message that has moved into the history (it would not keep them).
        val reactions = live.isPersonal && writable() && r.message(live.id) != null
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
            menu = MessageMenu(this, row, bubble, live.reactions[r.me.id], reactions, actionsFor(r, live),
                onReact = { e -> react(live, e) },
                onMoreReactions = { ReactionSheets.showPicker(this) { e -> react(live, e) }?.let { trackSheet(it) } },
                // Posted: a close during teardown must not redraw from inside it.
                onClosed = { b.list.post { if (!isDestroyed) refresh() } }).also { it.show() }
        }
    }

    private fun actionsFor(r: Router, m: Message): List<MessageMenu.Action> {
        val out = ArrayList<MessageMenu.Action>()
        val mine = m.from == r.me.id
        // A left group's chat keeps what works without the radio: copy, save, share, details, delete.
        // So does a private chat that can't be written in — nothing in it can be answered.
        if (m.isPersonal && writable()) out.add(MessageMenu.Action(R.drawable.ic_reply, getString(R.string.reply)) { startReply(m) })
        // Privately only to someone on 2.4 or later: an old id's private chat could never be written in.
        if (m.isPersonal && m.isGroup && !mine && !readOnly && ChatRules.replyPrivately(m.from))
            out.add(MessageMenu.Action(R.drawable.ic_chat_private, getString(R.string.chat_reply_privately)) { replyPrivately(m) })
        if (m.text.isNotEmpty()) out.add(MessageMenu.Action(R.drawable.ic_copy, getString(R.string.copy)) { copy(m) })
        if (fileOnPhone(r, m)) {
            out.add(MessageMenu.Action(R.drawable.ic_chat_save, getString(R.string.chat_save_to_phone)) { saveToPhone(m) })
            // An app installer is only ever saved (MediaRules).
            val att = m.att
            if (att != null && !MediaRules.saveOnly(att.name, att.mime))
                out.add(MessageMenu.Action(R.drawable.ic_share, getString(R.string.chat_share)) { shareFile(m) })
        }
        if (mine && m.isPersonal) out.add(MessageMenu.Action(R.drawable.ic_info, getString(R.string.message_info)) { showDetails(m) })
        if (canSendAgain(r, m)) out.add(MessageMenu.Action(R.drawable.ic_chat_retry, getString(R.string.chat_send_again)) { sendAgain(m) })
        out.add(MessageMenu.Action(R.drawable.ic_chat_delete, getString(R.string.chat_delete_for_me), danger = true) { confirmDelete(m) })
        return out
    }

    private fun react(m: Message, emoji: String) {
        if (!writable()) return
        val r = Core.router ?: return
        val live = r.message(m.id) ?: return
        if (!live.isPersonal) return
        if (emoji.length > Message.MAX_EMOJI) { toast(getString(R.string.chat_emoji_too_long)); return }
        val mine = live.reactions[r.me.id]
        val next = if (emoji == mine) "" else emoji   // the same one again takes it back
        if (next.isEmpty() && mine == null) return
        if (r.sendReaction(live, next)) Core.saveNow()
        refresh()
    }

    /** Double-tap a bubble: ❤️ (or take my ❤️ back), with a little burst so it's clear what happened. */
    private fun doubleTapReact(m: Message, bubble: View) {
        // Nothing to do in a left group, or a private chat that can't be written in — but the
        // adapter keeps listening for the double tap all the same: the single tap that opens a
        // link or the details is told apart by it.
        if (!writable()) return
        val r = Core.router ?: return
        val live = r.message(m.id) ?: return
        if (!live.isPersonal || menu?.isShowing == true) return
        val heart = MessageMenu.DOUBLE_TAP_REACTION
        val adding = live.reactions[r.me.id] != heart
        if (r.sendReaction(live, if (adding) heart else "")) Core.saveNow()
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
        val r = router() ?: return
        val live = find(r, m.id) ?: return
        // Mine can be taken back only where a reaction can be sent: on a live message of the radio's group.
        val canRemove = writable() && r.message(live.id) != null
        ReactionSheets.showReactors(this, r, live, stillShown = { router() === r && find(r, live.id) != null },
            onRemoveMine = if (canRemove) ({ react(live, "") }) else null)?.let { trackSheet(it) }
    }

    private fun copy(m: Message) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.chat_clip_label), m.text))
        // Android 13+ shows its own "copied" confirmation; a toast on top would say it twice.
        if (Build.VERSION.SDK_INT < 33) toast(getString(R.string.copied))
    }

    /** Plain-English answer to "did it get there?", in a sheet that follows the mesh while open. */
    private fun showDetails(m: Message) {
        val r = router() ?: return
        if (find(r, m.id) == null || isFinishing) return
        val sheet = BottomSheetDialog(this)
        val sb = SheetDetailsBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        ViewCompat.setAccessibilityHeading(sb.detailsTitle, true)
        sb.detailsTime.text = getString(R.string.chat_details_sent, MessageAdapter.dayLabel(this, m.ts),
            java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date(m.ts)))
        sb.detailsRetry.setOnClickListener { sheet.dismiss(); sendAgain(m) }
        fun render() {
            val live = if (router() === r) find(r, m.id) else null
            if (live == null) { sheet.dismiss(); return }
            sb.detailsBody.text = Ui.statusDetail(this, r, live, leftAt = leftAt())
            // Nothing is sent again from a left group, or to someone nothing can be sealed for.
            sb.detailsRetry.isVisible = canSendAgain(r, live)
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
        /** This close to the first loaded row (in rows), the page above is read. */
        private const val EARLIER_NEAR_TOP = 6
        /** This many of the oldest live messages count as "next to the earlier ones": a batch and a half. */
        private const val EARLIER_NEAR_LIVE = Router.SPILL_BATCH * 3 / 2
        /** How long a re-created screen holds its list back for the earlier messages it had. */
        private const val EARLIER_WAIT_MS = 1_500L
        /** Capture the unread mark on the next resume. */
        private const val UNREAD_CAPTURE = -1L
        /** No divider until the person leaves the chat (they sent something, or cleared it). */
        private const val UNREAD_NONE = Long.MAX_VALUE

        /** One sweep of old camera shots per process. */
        private var swept = false

        /**
         * Chats whose history was read to the end without finding a single message of theirs,
         * and the history stamp that was true of: "group|chat" -> stamp. Opening a short private
         * chat must not read a long group history through every time.
         */
        private val noEarlier = HashMap<String, Int>()

        private const val M_INFO = 1
        private const val M_MUTE = 2
        private const val M_CLEAR = 3
        private const val M_PEOPLE = 4
        private const val M_INTERNET = 5
        private const val M_CODE = 6
        private const val M_SETTINGS = 7
        private const val M_REJOIN = 8
        private const val M_DELETE_GROUP = 9
        private const val M_VERIFY = 10

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
        private const val S_EARLIER = "earlier"

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
