package app.hopline.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.location.Location
import app.hopline.R
import app.hopline.databinding.ActivityChatBinding
import app.hopline.databinding.SheetAttachBinding
import app.hopline.databinding.SheetDetailsBinding
import app.hopline.databinding.SheetLocationBinding
import app.hopline.databinding.ViewErrandCardBinding
import app.hopline.mesh.Errand
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
import app.hopline.service.Locations
import com.google.android.material.bottomsheet.BottomSheetDialog
import java.io.File

/**
 * One chat: the whole group (no "peer" extra) or one person. Same rules either way — messages
 * are held until phones carry them, and the ticks never lie.
 */
class ChatActivity : AppCompatActivity() {
    private lateinit var b: ActivityChatBinding
    private var peer: String? = null            // null = the group chat
    private val chatKey: String get() = peer ?: Core.GROUP
    private var adapter: MessageAdapter? = null
    private var lastCount = -1
    private var cameraFile: File? = null
    private var replyTo: Message? = null
    /** Which location action waits on the permission dialog — a plain tag, so it survives the
     *  activity being recreated behind the system dialog. */
    private var pendingLocationAction: String? = null
    /** The "finding your position" dialog, so leaving the screen also stops its GPS listener. */
    private var fixDialog: AlertDialog? = null

    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendImage(uri)
    }
    private val pickFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) sendPickedFile(uri)
    }
    private val takePhoto = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null && f.exists()) sendImage(FileProvider.getUriForFile(this, FILES_AUTHORITY, f))
    }
    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> launchCamera()
            !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                toast(getString(R.string.camera_denied))
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
            else -> toast(getString(R.string.camera_denied))
        }
    }
    private val locationPermission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        val action = pendingLocationAction
        pendingLocationAction = null
        when {
            grants.values.any { it } -> runLocationAction(action)   // "approximate only" still works, just rougher
            !shouldShowRequestPermissionRationale(Manifest.permission.ACCESS_FINE_LOCATION) -> {
                toast(getString(R.string.loc_denied))
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
            else -> toast(getString(R.string.loc_denied))
        }
    }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        when {
            granted -> startRecording()
            !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                toast(getString(R.string.mic_denied))
                try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (e: Exception) { }
            }
            else -> toast(getString(R.string.mic_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        peer = intent.getStringExtra("peer")
        cameraFile = savedInstanceState?.getString("cameraFile")?.let { File(it) }
        pendingLocationAction = savedInstanceState?.getString("pendingLoc")
        b = ActivityChatBinding.inflate(layoutInflater)
        setContentView(b.root)
        Core.ensureRunning()

        b.list.layoutManager = LinearLayoutManager(this).apply { stackFromEnd = true }
        b.back.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { goBack() }
        })
        b.titleArea.setOnClickListener { startActivity(Intent(this, PeopleActivity::class.java)) }
        b.more.setOnClickListener { showMenu() }
        b.send.setOnClickListener { sendText() }
        b.attach.setOnClickListener { showAttachSheet() }
        b.mic.setOnClickListener { onMicTapped() }
        b.recordCancel.setOnClickListener { finishRecording(send = false) }
        b.recordSend.setOnClickListener { finishRecording(send = true) }
        b.replyClose.setOnClickListener { clearReply() }
        b.liveBanner.setOnClickListener { onLiveBannerTapped() }
        b.input.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) { updateMentionBar() }
            override fun beforeTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, c: Int, d: Int) {}
        })
        attachSwipeToReply()
        // A rotation must not lose an in-progress reply.
        savedInstanceState?.getString("replyTo")?.let { id ->
            Core.router?.message(id)?.let { startReply(it, focus = false) }
        }
        b.jump.setOnClickListener { scrollToBottom() }
        b.warn.setOnClickListener {
            when {
                !Core.bluetoothOn() -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                !Core.wifiOn() -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
                else -> startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            }
        }
        b.list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
                val lm = rv.layoutManager as LinearLayoutManager
                val last = lm.findLastVisibleItemPosition()
                val total = rv.adapter?.itemCount ?: 0
                b.jump.visibility = if (total > 0 && last < total - 3) View.VISIBLE else View.GONE
            }
        })
        Core.version.observe(this) { refresh() }
    }

    private fun goBack() {
        if (isTaskRoot) startActivity(Intent(this, HomeActivity::class.java))
        finish()
    }

    /** singleTop: a notification tap for another chat lands here instead of a fresh instance. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val newPeer = intent.getStringExtra("peer")
        if (newPeer != peer) {
            // A recording or a reply belongs to the chat it was started in — never carry it over.
            if (VoiceRecorder.recording) { finishRecording(send = false); toast(getString(R.string.record_discarded)) }
            clearReply()
            VoicePlayer.stop()
        }
        peer = newPeer
        adapter = null
        lastCount = -1
        Core.openChat = chatKey
        Core.markRead(chatKey)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraFile?.let { outState.putString("cameraFile", it.absolutePath) }
        replyTo?.let { outState.putString("replyTo", it.id) }
        pendingLocationAction?.let { outState.putString("pendingLoc", it) }
    }

    override fun onResume() {
        super.onResume()
        if (!app.hopline.service.Permissions.allGranted(this)) {
            // Nearby permission revoked while we were away (e.g. Android auto-revoke).
            startActivity(Intent(this, LaunchActivity::class.java)); finish(); return
        }
        Core.appVisible = true
        Core.openChat = chatKey
        Core.markRead(chatKey)
        VoicePlayer.onChanged = { refresh() }
        if (VoiceRecorder.recording) showRecordingBar()   // a rotation must not lose a recording
        adapter = null   // router may have been rebuilt (e.g. after switching groups)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Core.appVisible = false
        Core.markRead(chatKey)
        Core.openChat = null
        VoicePlayer.onChanged = null
        VoicePlayer.stop()
        // A recording may outlive this screen (rotation), but this screen's callback must not.
        VoiceRecorder.onMaxReached = null
        // Rotation would leak the fix dialog's GPS listener; dismissing runs its stop().
        fixDialog?.dismiss(); fixDialog = null
    }

    override fun onStop() {
        super.onStop()
        // Android mutes the mic for backgrounded apps — keeping the bar running would record
        // silence while claiming otherwise. A rotation is fine; a real exit ends the take.
        if (VoiceRecorder.recording && !isChangingConfigurations) {
            finishRecording(send = false)
            toast(getString(R.string.record_discarded))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // A rotation recreates us mid-recording and that's fine; actually leaving throws it away.
        if (isFinishing) VoiceRecorder.cancel()
    }

    // ------------------------------------------------------------------ sending

    private fun sendText() {
        val r = Core.router ?: return
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        val quote = replyTo?.let { Quote.of(it) }
        peer?.let { r.sendDm(it, text, quote) } ?: r.sendChat(text, quote, extractMentions(r, text))
        b.input.setText("")
        clearReply()
        refresh()
        scrollToBottom()
    }

    // ------------------------------------------------------------------ replies

    private fun startReply(m: Message, focus: Boolean = true) {
        if (m.kind == Message.SYSTEM) return
        val r = Core.router ?: return
        replyTo = m
        b.replyBar.visibility = View.VISIBLE
        b.replyName.text = if (m.from == r.me.id) getString(R.string.reply_you) else m.fromName.ifEmpty { "Someone" }
        b.replySnippet.text = Quote.of(m).text
        if (focus) {
            b.input.requestFocus()
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .showSoftInput(b.input, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun clearReply() {
        replyTo = null
        b.replyBar.visibility = View.GONE
    }

    /** Swipe a bubble to the right to reply — the WhatsApp muscle memory. */
    private fun attachSwipeToReply() {
        val density = resources.displayMetrics.density
        val icon = ContextCompat.getDrawable(this, R.drawable.ic_reply)!!.mutate()
        val helper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.RIGHT) {
            override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

            override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int {
                val m = adapter?.messageAt(vh.bindingAdapterPosition) ?: return 0   // day chips don't swipe
                if (m.kind == Message.SYSTEM) return 0
                return makeMovementFlags(0, ItemTouchHelper.RIGHT)
            }

            override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder) = 0.2f

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {
                val pos = vh.bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) { adapter?.notifyDataSetChanged(); return }
                val m = adapter?.messageAt(pos)
                b.list.adapter?.notifyItemChanged(pos)   // spring back — a reply swipe never dismisses
                if (m != null) {
                    vh.itemView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    startReply(m)
                }
            }

            override fun onChildDraw(c: Canvas, rv: RecyclerView, vh: RecyclerView.ViewHolder,
                                     dX: Float, dY: Float, actionState: Int, isActive: Boolean) {
                // Damped, capped drag: the row hints, it doesn't fly away.
                val capped = minOf(dX / 2f, 56f * density).coerceAtLeast(0f)
                super.onChildDraw(c, rv, vh, capped, dY, actionState, isActive)
                if (capped > 10f * density) {
                    val size = (22f * density).toInt()
                    val left = (12f * density).toInt()
                    val top = vh.itemView.top + (vh.itemView.height - size) / 2
                    icon.setBounds(left, top, left + size, top + size)
                    icon.alpha = (255 * minOf(1f, capped / (48f * density))).toInt()
                    icon.draw(c)
                }
            }
        })
        helper.attachToRecyclerView(b.list)
    }

    /** Tap a quote block: jump to the original and flash it. */
    private fun jumpToMessage(id: String) {
        val pos = adapter?.positionOf(id) ?: -1
        if (pos < 0) { toast(getString(R.string.original_gone)); return }
        (b.list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(pos, b.list.height / 3)
        b.list.postDelayed({
            val vh = b.list.findViewHolderForAdapterPosition(pos) ?: return@postDelayed
            vh.itemView.animate().alpha(0.35f).setDuration(160).withEndAction {
                vh.itemView.animate().alpha(1f).setDuration(420).start()
            }.start()
        }, 220)
    }

    // ------------------------------------------------------------------ mentions

    /**
     * Which people the text calls out with @Name. The @ must start a word (not an email) and the
     * name must end at a word boundary — "@Samantha" is not a mention of Sam.
     */
    private fun extractMentions(r: Router, text: String): List<String> {
        if (!text.contains('@')) return emptyList()
        val out = ArrayList<String>()
        for (p in r.people.values) {
            if (p.name.isEmpty()) continue
            var i = text.indexOf("@${p.name}", ignoreCase = true)
            while (i >= 0) {
                val end = i + 1 + p.name.length
                val startsWord = i == 0 || text[i - 1].isWhitespace()
                val endsWord = end >= text.length || !text[end].isLetterOrDigit()
                if (startsWord && endsWord) { out.add(p.id); break }
                i = text.indexOf("@${p.name}", i + 1, ignoreCase = true)
            }
            if (out.size >= Message.MAX_MENTIONS) break
        }
        return out
    }

    /** Typing "@" in the group chat offers name chips; tapping one completes the mention. */
    private fun updateMentionBar() {
        val r = Core.router
        if (r == null || peer != null) { b.mentionBar.visibility = View.GONE; return }
        val text = b.input.text.toString()
        val cursor = b.input.selectionStart.coerceIn(0, text.length)
        val upto = text.substring(0, cursor)
        val at = upto.lastIndexOf('@')
        val token = if (at >= 0) upto.substring(at + 1) else null
        val open = token != null && (at == 0 || upto[at - 1].isWhitespace()) &&
            token.length <= 16 && !token.contains('\n')
        if (!open) { b.mentionBar.visibility = View.GONE; return }
        val matches = r.people.values
            .filter { it.name.isNotEmpty() && it.name.startsWith(token!!, ignoreCase = true) }
            .sortedBy { it.name.lowercase() }.take(6)
        if (matches.isEmpty()) { b.mentionBar.visibility = View.GONE; return }
        b.mentionBar.visibility = View.VISIBLE
        b.mentionChips.removeAllViews()
        for (p in matches) {
            val chip = TextView(this).apply {
                this.text = "@${p.name}"
                textSize = 14f
                setTextColor(getColor(R.color.text))
                background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_chip)
                setPadding((12 * resources.displayMetrics.density).toInt(), (7 * resources.displayMetrics.density).toInt(),
                    (12 * resources.displayMetrics.density).toInt(), (7 * resources.displayMetrics.density).toInt())
                setOnClickListener {
                    val fresh = b.input.text.toString()
                    if (at <= fresh.length) {
                        val end = b.input.selectionStart.coerceIn(at, fresh.length)
                        b.input.text.replace(at, end, "@${p.name} ")
                    }
                    b.mentionBar.visibility = View.GONE
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = (6 * resources.displayMetrics.density).toInt()
            b.mentionChips.addView(chip, lp)
        }
    }

    /** A caption is offered, never silently taken: the dialog is prefilled from the composer. */
    private fun sendImage(uri: Uri) {
        val r = Core.router ?: return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        val prefill = b.input.text.toString().trim()
        Ui.ask(this, getString(R.string.attach_photo),
            listOf(getString(R.string.caption_hint) to (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
            getString(R.string.send), prefill = listOf(prefill)) { v ->
            val caption = v[0]
            if (caption == prefill) b.input.setText("")
            toast(getString(R.string.sending_file))
            val quote = replyTo?.let { Quote.of(it) }
            clearReply()
            Core.sendImage(uri, caption.take(500), peer, quote) { err ->
                if (err != null) toast(err) else scrollToBottom()
            }
        }
    }

    private fun sendPickedFile(uri: Uri) {
        val r = Core.router ?: return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        Thread {
            val picked = Blobs.readPicked(this, uri)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (picked == null) { toast(getString(R.string.file_too_big, "over 2 MB")); return@runOnUiThread }
                val send = {
                    val quote = replyTo?.let { Quote.of(it) }
                    clearReply()
                    Core.sendFileBytes(picked, "", peer, quote = quote) { err -> if (err != null) toast(err) else scrollToBottom() }
                }
                if (picked.bytes.size > 300_000) {
                    AlertDialog.Builder(this)
                        .setMessage(getString(R.string.file_big_warning, "${picked.name} (${Blobs.prettySize(picked.bytes.size.toLong())})"))
                        .setPositiveButton(R.string.send) { _, _ -> send() }
                        .setNegativeButton(R.string.cancel, null).show()
                } else send()
            }
        }.start()
    }

    // ------------------------------------------------------------------ voice notes

    private fun onMicTapped() {
        val r = Core.router ?: return
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

    /** Also called on re-create while a recording runs, so the timer picks up where it truly is. */
    private fun showRecordingBar() {
        VoiceRecorder.onMaxReached = {
            if (!isFinishing && !isDestroyed && VoiceRecorder.recording) { toast(getString(R.string.voice_max_note)); finishRecording(send = true) }
        }
        b.composerRow.visibility = View.GONE
        b.recordBar.visibility = View.VISIBLE
        b.recordTimer.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - VoiceRecorder.startedAt)
        b.recordTimer.start()
        b.recordDot.animate().alpha(0.2f).setDuration(600).withEndAction(object : Runnable {
            override fun run() {
                if (b.recordBar.visibility != View.VISIBLE) { b.recordDot.alpha = 1f; return }
                val back = if (b.recordDot.alpha < 0.5f) 1f else 0.2f
                b.recordDot.animate().alpha(back).setDuration(600).withEndAction(this).start()
            }
        }).start()
    }

    private fun finishRecording(send: Boolean) {
        VoiceRecorder.onMaxReached = null
        b.recordTimer.stop()
        b.recordBar.visibility = View.GONE
        b.composerRow.visibility = View.VISIBLE
        b.recordDot.animate().cancel(); b.recordDot.alpha = 1f
        if (!send) { VoiceRecorder.cancel(); return }
        val clip = VoiceRecorder.finish()
        if (clip == null) { toast(getString(R.string.voice_too_short)); return }
        val (file, seconds) = clip
        val bytes = try { file.readBytes() } catch (e: Exception) { null } finally { file.delete() }
        if (bytes == null || bytes.isEmpty()) { toast(getString(R.string.voice_failed)); return }
        val quote = replyTo?.let { Quote.of(it) }
        clearReply()
        Core.sendFileBytes(Blobs.PickedFile(bytes, file.name, "audio/mp4"), "", peer, seconds, quote) { err ->
            if (err != null) toast(err) else scrollToBottom()
        }
    }

    private fun showAttachSheet() {
        val r = Core.router ?: return
        val sheet = BottomSheetDialog(this)
        val sb = SheetAttachBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        // Photos and files switch off in a crowd; a location is a hundred bytes and always fits.
        val filesOk = r.canSendFiles()
        for (row in listOf(sb.pickPhoto, sb.pickCamera, sb.pickFile)) row.alpha = if (filesOk) 1f else 0.4f
        sb.pickPhoto.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss(); pickImage.launch("image/*")
        }
        sb.pickCamera.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss()
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        sb.pickFile.setOnClickListener {
            if (!filesOk) { toast(getString(R.string.files_crowd_off)); return@setOnClickListener }
            sheet.dismiss(); pickFile.launch("*/*")
        }
        sb.pickLocation.setOnClickListener { sheet.dismiss(); showLocationSheet() }
        sheet.show()
    }

    // ------------------------------------------------------------------ location

    private fun showLocationSheet() {
        val sheet = BottomSheetDialog(this)
        val sb = SheetLocationBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        sb.locCurrent.setOnClickListener {
            sheet.dismiss()
            withLocationPermission(PENDING_LOC_FIX)
        }
        sb.locOther.setOnClickListener { sheet.dismiss(); askForPlace() }
        if (Core.liveLocationActive()) {
            sb.locLiveTitle.text = getString(R.string.live_stop)
            sb.locLiveSub.text = getString(R.string.live_left, prettyLeft(Core.liveLocationLeftMs()))
            sb.locLive.setOnClickListener { sheet.dismiss(); Core.stopLiveLocation(); toast(getString(R.string.live_stopped)) }
        } else {
            sb.locLive.setOnClickListener { sheet.dismiss(); withLocationPermission(PENDING_LOC_LIVE) }
        }
        sheet.show()
    }

    private fun withLocationPermission(action: String) {
        if (Locations.granted(this)) runLocationAction(action)
        else { pendingLocationAction = action; locationPermission.launch(Locations.toRequest()) }
    }

    private fun runLocationAction(action: String?) {
        when (action) {
            PENDING_LOC_FIX -> sendCurrentLocation()
            PENDING_LOC_LIVE -> askLiveDuration()
        }
    }

    /** True when the phone's location switch is on; otherwise offers to open Settings. */
    private fun requireLocationOn(): Boolean {
        if (Locations.serviceOn(this)) return true
        AlertDialog.Builder(this).setMessage(R.string.loc_service_off)
            .setPositiveButton(R.string.turn_on) { _, _ -> try { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) } catch (e: Exception) { } }
            .setNegativeButton(R.string.cancel, null).show()
        return false
    }

    private fun askLiveDuration() {
        if (!requireLocationOn()) return
        val labels = arrayOf(getString(R.string.live_for_15), getString(R.string.live_for_60), getString(R.string.live_for_480))
        val minutes = intArrayOf(15, 60, 480)
        AlertDialog.Builder(this).setTitle(R.string.live_share)
            .setItems(labels) { _, which -> Core.startLiveLocation(minutes[which]); refresh() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun prettyLeft(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).toInt()
        return when {
            min >= 60 && min % 60 == 0 -> "${min / 60} h"
            min >= 60 -> "${min / 60} h ${min % 60} min"
            else -> "$min min"
        }
    }

    private fun onLiveBannerTapped() {
        if (Core.liveLocationActive()) {
            AlertDialog.Builder(this).setMessage(R.string.live_stop_confirm)
                .setPositiveButton(R.string.stop) { _, _ -> Core.stopLiveLocation(); toast(getString(R.string.live_stopped)) }
                .setNegativeButton(R.string.cancel, null).show()
        } else startActivity(Intent(this, PeopleActivity::class.java))
    }

    /**
     * One dialog that tells the truth while GPS warms up: Send lights up on the first fix and
     * the accuracy line keeps improving until the user sends or gives up.
     */
    private fun sendCurrentLocation() {
        val r = Core.router ?: return
        if (!requireLocationOn()) return
        var best: Location? = null
        var stop: (() -> Unit)? = null
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.loc_current)
            .setMessage(R.string.loc_finding)
            .setPositiveButton(R.string.send) { _, _ ->
                val l = best ?: return@setPositiveButton
                if (Core.router !== r) return@setPositiveButton   // switched groups mid-fix
                Loc.of(l.latitude, l.longitude, l.accuracy.toInt())?.let { r.sendLocation(it, peer); scrollToBottom() }
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        fun offer(l: Location) {
            val b0 = best
            if (b0 == null || l.accuracy < b0.accuracy || l.time - b0.time > 30_000) best = l
            dialog.setMessage(getString(R.string.loc_found, Loc.prettyDistance(best!!.accuracy.toDouble().coerceAtLeast(1.0))))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = true
        }
        dialog.setOnDismissListener { stop?.invoke(); if (fixDialog === dialog) fixDialog = null }
        fixDialog = dialog
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = false
        Locations.lastKnown(this)?.let { if (System.currentTimeMillis() - it.time < 2 * 60_000) offer(it) }
        stop = Locations.watch(this) { offer(it) }
    }

    /** A meeting point: typed coordinates or a pasted maps link, with an optional name. */
    private fun askForPlace() {
        Ui.ask(this, getString(R.string.loc_other),
            listOf(getString(R.string.loc_place_hint) to android.text.InputType.TYPE_CLASS_TEXT,
                getString(R.string.loc_place_name_hint) to (android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES)),
            getString(R.string.send), message = getString(R.string.loc_place_help)) { v ->
            val loc = Loc.parse(v[0])?.let { Loc.of(it.lat, it.lng, 0, v[1]) }
            if (loc == null) { toast(getString(R.string.loc_bad_place)); return@ask }
            Core.router?.sendLocation(loc, peer)
            scrollToBottom()
        }
    }

    private fun openLocation(m: Message) {
        val loc = m.loc ?: return
        val label = loc.label.ifEmpty {
            if (m.from == Core.router?.me?.id) getString(R.string.loc_pin_mine)
            else getString(R.string.loc_pin_theirs, m.fromName.ifEmpty { "Someone" })
        }
        val coords = String.format(java.util.Locale.US, "%.6f,%.6f", loc.lat, loc.lng)
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
        cm.setPrimaryClip(ClipData.newPlainText("location", loc.pretty()))
        toast(getString(R.string.no_maps_app))
    }

    private fun launchCamera() {
        try {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            val f = File(dir, "shot-${System.currentTimeMillis()}.jpg")
            cameraFile = f
            takePhoto.launch(FileProvider.getUriForFile(this, FILES_AUTHORITY, f))
        } catch (e: Exception) { toast("Couldn't open the camera.") }
    }

    // ------------------------------------------------------------------ drawing

    private fun refresh() {
        val r = Core.router ?: return
        if (adapter == null) {
            adapter = MessageAdapter(r, showNames = peer == null,
                onMessageTap = { m -> if (m.from == r.me.id && m.kind != Message.SYSTEM) showDetails(r, m) },
                onMessageLong = { m -> showLongPress(r, m) },
                onAttachmentTap = { m -> openAttachment(m) },
                onLocationTap = { m -> openLocation(m) },
                onQuoteTap = { m -> m.quote?.let { jumpToMessage(it.id) } },
                onReactionsTap = { m -> showReactions(r, m) })
            b.list.adapter = adapter
        }
        if (peer == null) {
            b.title.text = r.group.name.ifEmpty { "Your group" }
            b.subtitle.text = Core.statusLine()
            b.avatar.text = (r.group.name.ifEmpty { "G" }).take(1).uppercase()
            b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(r.group.fingerprint))
        } else {
            val p = r.people[peer]
            b.title.text = p?.name?.ifEmpty { "Someone" } ?: "Someone"
            // The header has one line; the People screen shows the same status with room to wrap.
            b.subtitle.text = if (p != null) Ui.personStatus(r, p).replace("\n", " · ") else ""
            b.avatar.text = (p?.name ?: "?").take(1).uppercase().ifEmpty { "?" }
            b.avatar.background.mutate().setTint(MessageAdapter.avatarColor(peer!!))
        }

        val warn = when {
            !Core.bluetoothOn() -> getString(R.string.bt_off)
            !Core.wifiOn() -> getString(R.string.wifi_off)
            Core.radioProblem.isNotEmpty() -> Core.radioProblem
            else -> ""
        }
        b.warn.text = warn; b.warn.visibility = if (warn.isEmpty()) View.GONE else View.VISIBLE

        // Live location banner: mine first (with the stop affordance), else whoever is sharing.
        val sharers = if (peer == null) r.people.values.filter { r.liveLocOf(it) != null } else emptyList()
        val banner = when {
            Core.liveLocationActive() -> getString(R.string.live_banner_me, prettyLeft(Core.liveLocationLeftMs()))
            sharers.size == 1 -> getString(R.string.live_banner_one, sharers[0].name.ifEmpty { "Someone" })
            sharers.size > 1 -> getString(R.string.live_banner_many, sharers.size)
            else -> ""
        }
        b.liveBanner.text = banner
        b.liveBanner.visibility = if (banner.isEmpty()) View.GONE else View.VISIBLE

        val shown = if (peer == null) r.messages.filter { it.isGroup }
        else r.messages.filter { !it.isGroup && ((it.from == peer && it.to == r.me.id) || (it.from == r.me.id && it.to == peer)) }
        // Only follow the conversation if the user is already at the bottom — never yank them
        // out of history they scrolled up to read.
        val lm = b.list.layoutManager as LinearLayoutManager
        val atBottom = lastCount < 0 || lm.findLastVisibleItemPosition() >= (b.list.adapter?.itemCount ?: 0) - 2
        adapter!!.submit(shown)
        if (shown.size != lastCount) {
            lastCount = shown.size
            if (atBottom) scrollToBottom()
        }

        if (peer == null) {
            val alone = r.authedLinks().isEmpty() && r.peopleInRange() == 0
            b.hint.visibility = if (alone && warn.isEmpty() && shown.isEmpty()) View.VISIBLE else View.GONE
            b.hint.text = if (r.people.isEmpty()) getString(R.string.invite_hint) else getString(R.string.alone_hint)
            renderErrandCards(r)
        } else {
            val p = r.people[peer]
            val away = p != null && !r.isInRange(p)
            b.hint.visibility = if (away) View.VISIBLE else View.GONE
            b.hint.text = getString(R.string.out_of_range_dm, p?.name?.ifEmpty { "They" } ?: "They")
        }
    }

    private fun scrollToBottom() {
        b.list.post { b.list.scrollToPosition(maxOf(0, (b.list.adapter?.itemCount ?: 1) - 1)) }
    }

    private fun showMenu() {
        val menu = PopupMenu(this, b.more)
        menu.menu.add(0, 1, 0, R.string.people)
        menu.menu.add(0, 2, 1, R.string.internet_title)
        menu.menu.add(0, 3, 2, R.string.show_invite)
        menu.menu.add(0, 4, 3, R.string.settings)
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> startActivity(Intent(this, PeopleActivity::class.java))
                2 -> startActivity(Intent(this, InternetActivity::class.java))
                3 -> startActivity(Intent(this, CodeActivity::class.java))
                4 -> startActivity(Intent(this, SettingsActivity::class.java))
            }
            true
        }
        menu.show()
    }

    // ------------------------------------------------------------------ message actions

    /** Plain-English answer to "did it get there?", in a sheet. */
    private fun showDetails(r: Router, m: Message) {
        val sheet = BottomSheetDialog(this)
        val sb = SheetDetailsBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        sb.detailsBody.text = Ui.statusDetail(this, r, m)
        sb.detailsCopy.visibility = View.GONE
        sheet.show()
    }

    private fun showLongPress(r: Router, m: Message) {
        val sheet = BottomSheetDialog(this)
        val sb = SheetDetailsBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        val system = m.kind == Message.SYSTEM
        val mine = m.from == r.me.id && !system
        if (!system) {
            // Quick reactions: one tap applies (tapping your current one takes it back).
            sb.reactScroll.visibility = View.VISIBLE
            val density = resources.displayMetrics.density
            val myCurrent = m.reactions[r.me.id]
            for (emoji in QUICK_REACTIONS) {
                val v = TextView(this).apply {
                    text = emoji
                    textSize = 27f
                    gravity = Gravity.CENTER
                    setPadding((9 * density).toInt(), (5 * density).toInt(), (9 * density).toInt(), (5 * density).toInt())
                    if (emoji == myCurrent) {
                        background = ContextCompat.getDrawable(this@ChatActivity, R.drawable.bg_chip)
                        background?.mutate()?.setTint(getColor(R.color.ember_soft))
                    }
                    setOnClickListener {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        r.sendReaction(m, if (emoji == myCurrent) "" else emoji)
                        sheet.dismiss()
                        refresh()
                    }
                }
                sb.reactRow.addView(v)
            }
            sb.detailsReply.visibility = View.VISIBLE
            sb.detailsReply.setOnClickListener { sheet.dismiss(); startReply(m) }
        }
        // "Delivery details" only belongs on my own message; on someone else's, the sheet is
        // about them, so the header carries their name and the body the time.
        if (mine) {
            sb.detailsTitle.text = getString(R.string.message_details)
            sb.detailsBody.text = Ui.statusDetail(this, r, m)
        } else {
            sb.detailsTitle.text = m.fromName.ifEmpty { getString(R.string.someone) }
            sb.detailsBody.text = Ui.ago(m.ts)
        }
        if (m.text.isNotEmpty()) {
            sb.detailsCopy.visibility = View.VISIBLE
            sb.detailsCopy.setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("message", m.text))
                toast(getString(R.string.copied))
                sheet.dismiss()
            }
        }
        sheet.show()
    }

    /** The pill under a bubble: who reacted with what, by name. */
    private fun showReactions(r: Router, m: Message) {
        if (m.reactions.isEmpty()) return
        val lines = m.reactions.entries.joinToString("\n") { (who, emoji) ->
            val name = when {
                who == r.me.id -> getString(R.string.you_suffix, r.me.name.ifEmpty { "You" })
                else -> r.people[who]?.name?.ifEmpty { null } ?: "Someone"
            }
            "$emoji   $name"
        }
        AlertDialog.Builder(this).setTitle(R.string.reactions)
            .setMessage(lines + "\n\n" + getString(R.string.reaction_hint))
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun openAttachment(m: Message) {
        val att = m.att ?: return
        val fp = Core.fingerprint() ?: return
        val file = Blobs.fileFor(this, fp, att)
        if (!file.exists()) return
        if (att.isImage) {
            startActivity(Intent(this, ViewerActivity::class.java)
                .putExtra("path", file.absolutePath).putExtra("name", att.name).putExtra("mime", att.mime))
        } else {
            try {
                val uri = FileProvider.getUriForFile(this, FILES_AUTHORITY, file)
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, att.mime)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (e: Exception) { toast("No app on this phone can open ${att.name}.") }
        }
    }

    // ------------------------------------------------------------------ errand cards

    /** Cards shown on the phone that has signal when somebody asks it to send a message out. */
    private fun renderErrandCards(r: Router) {
        b.errandCards.removeAllViews()
        val mine = r.errands.values.filter { it.type == Errand.SEND && it.helper == r.me.id && it.status == Errand.ASKED }
        for (e in mine) {
            val card = ViewErrandCardBinding.inflate(layoutInflater, b.errandCards, false)
            val to = e.args.optString("to"); val text = e.args.optString("text")
            card.title.text = getString(R.string.errand_send_title, e.fromName)
            card.body.text = "To: $to\n“$text”"
            card.open.setOnClickListener { openMessenger(to, text) }
            card.sent.setOnClickListener { r.completeErrand(e.id, true, "Message sent out by ${r.me.name}", "To $to: “$text”") }
            card.failed.setOnClickListener { r.completeErrand(e.id, false, "Couldn't send ${e.fromName}'s message", "${r.me.name}'s phone could not send it to $to.") }
            b.errandCards.addView(card.root)
        }
    }

    private fun openMessenger(to: String, text: String) {
        val intent = if (to.contains("@")) Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$to")).putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, "Message from Hopline")
                     else Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$to")).putExtra("sms_body", text)
        try { startActivity(intent) } catch (e: Exception) {
            AlertDialog.Builder(this).setMessage("No messaging app found for $to").setPositiveButton(R.string.ok, null).show()
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        const val FILES_AUTHORITY = "app.hopline.files"
        /** WhatsApp's classic six — familiar thumbs land without thinking. */
        val QUICK_REACTIONS = listOf("👍", "❤️", "😂", "😮", "😢", "🙏")
        private const val PENDING_LOC_FIX = "fix"
        private const val PENDING_LOC_LIVE = "live"
    }
}
