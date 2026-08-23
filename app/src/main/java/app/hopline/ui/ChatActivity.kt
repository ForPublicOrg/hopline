package app.hopline.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.hopline.R
import app.hopline.databinding.ActivityChatBinding
import app.hopline.databinding.SheetAttachBinding
import app.hopline.databinding.SheetDetailsBinding
import app.hopline.databinding.ViewErrandCardBinding
import app.hopline.mesh.Errand
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import app.hopline.service.Blobs
import app.hopline.service.Core
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Core.store.group() == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        peer = intent.getStringExtra("peer")
        cameraFile = savedInstanceState?.getString("cameraFile")?.let { File(it) }
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
        peer = intent.getStringExtra("peer")
        adapter = null
        lastCount = -1
        Core.openChat = chatKey
        Core.markRead(chatKey)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        cameraFile?.let { outState.putString("cameraFile", it.absolutePath) }
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
        adapter = null   // router may have been rebuilt (e.g. after switching groups)
        refresh()
    }

    override fun onPause() {
        super.onPause()
        Core.appVisible = false
        Core.markRead(chatKey)
        Core.openChat = null
    }

    // ------------------------------------------------------------------ sending

    private fun sendText() {
        val r = Core.router ?: return
        val text = b.input.text.toString().trim()
        if (text.isEmpty()) return
        peer?.let { r.sendDm(it, text) } ?: r.sendChat(text)
        b.input.setText("")
        refresh()
        scrollToBottom()
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
            Core.sendImage(uri, caption.take(500), peer) { err ->
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
                    Core.sendFileBytes(picked, "", peer) { err -> if (err != null) toast(err) else scrollToBottom() }
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

    private fun showAttachSheet() {
        val r = Core.router ?: return
        if (!r.canSendFiles()) { toast(getString(R.string.files_crowd_off)); return }
        val sheet = BottomSheetDialog(this)
        val sb = SheetAttachBinding.inflate(layoutInflater)
        sheet.setContentView(sb.root)
        sb.pickPhoto.setOnClickListener { sheet.dismiss(); pickImage.launch("image/*") }
        sb.pickCamera.setOnClickListener {
            sheet.dismiss()
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
            else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        sb.pickFile.setOnClickListener { sheet.dismiss(); pickFile.launch("*/*") }
        sheet.show()
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
                onAttachmentTap = { m -> openAttachment(m) })
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
            b.subtitle.text = if (p != null) Ui.personStatus(r, p) else ""
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
        val mine = m.from == r.me.id && m.kind != Message.SYSTEM
        sb.detailsBody.text = if (mine) Ui.statusDetail(this, r, m) else "${m.fromName.ifEmpty { "Someone" }} · ${Ui.ago(m.ts)}"
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

    companion object { const val FILES_AUTHORITY = "app.hopline.files" }
}
