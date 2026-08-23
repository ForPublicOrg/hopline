package app.hopline.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import app.hopline.core.Words
import app.hopline.databinding.ActivityGroupBinding
import app.hopline.service.Core
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

/**
 * Two big cards: start a group (you get a code) or join one (type / scan a code).
 * Also opened from Home's + button to add a group next to the ones you already have.
 */
class GroupActivity : AppCompatActivity() {
    private lateinit var b: ActivityGroupBinding
    private var adding = false

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val text = result.contents ?: return@registerForActivityResult
        val parsed = parseQr(text)
        if (parsed == null) { b.code.setText(text); b.codeError.visibility = View.VISIBLE; return@registerForActivityResult }
        join(parsed.first, parsed.second)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityGroupBinding.inflate(layoutInflater)
        setContentView(b.root)
        val deepLink = intent?.data?.let { parseQr(it.toString()) }
        val name = Core.store.name
        if (name.isBlank()) {
            // Keep a tapped invite alive through onboarding instead of dropping it.
            deepLink?.let { Core.store.pendingJoin = "${it.first}|${it.second}" }
            startActivity(Intent(this, WelcomeActivity::class.java)); finish(); return
        }
        adding = intent.getBooleanExtra("add", false)
        b.back.visibility = if (adding) View.VISIBLE else View.GONE
        b.back.setOnClickListener { finish() }
        b.hello.text = if (adding) getString(app.hopline.R.string.add_group) else "Hi $name! 👋"
        b.groupName.setText("$name's group")

        b.cardStart.setOnClickListener { b.startPanel.visibility = View.VISIBLE; b.joinPanel.visibility = View.GONE; b.groupName.requestFocus() }
        b.cardJoin.setOnClickListener { b.joinPanel.visibility = View.VISIBLE; b.startPanel.visibility = View.GONE; b.code.requestFocus() }

        b.startBtn.setOnClickListener {
            val gname = b.groupName.text.toString().trim().ifEmpty { "$name's group" }
            val code = Words.randomCode()
            activate(code, gname)
            startActivity(Intent(this, CodeActivity::class.java).putExtra("first", true))
            finish()
        }

        fun tryJoin() {
            val code = b.code.text.toString()
            if (!Words.looksValid(code)) { b.codeError.visibility = View.VISIBLE; return }
            join(code, "")
        }
        b.joinBtn.setOnClickListener { tryJoin() }
        b.code.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { tryJoin(); true } else false }
        b.scanBtn.setOnClickListener {
            scan.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setPrompt("Point at your friend's screen")
                .setBeepEnabled(false).setOrientationLocked(false))
        }

        // Opened from a QR link, or an invite that waited through onboarding?
        val pending = deepLink ?: Core.store.pendingJoin?.split("|", limit = 2)?.let { it[0] to (it.getOrNull(1) ?: "") }
        Core.store.pendingJoin = null
        pending?.let { offerDeepLinkJoin(it.first, it.second) }
    }

    /** A link can come from anywhere — joining (and retargeting the radio) needs a human yes. */
    private fun offerDeepLinkJoin(code: String, groupName: String) {
        if (!Words.looksValid(code)) return
        if (Core.store.groups().isEmpty()) { join(code, groupName); return }
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(getString(app.hopline.R.string.switch_group_title, groupName.ifEmpty { Words.pretty(code) }))
            .setMessage(app.hopline.R.string.switch_group_body)
            .setPositiveButton(app.hopline.R.string.join) { _, _ -> join(code, groupName) }
            .setNegativeButton(app.hopline.R.string.cancel, null)
            .show()
    }

    private fun activate(code: String, groupName: String) {
        Core.store.addGroup(code, groupName)
        Core.switchGroup(Words.normalise(code))
        Core.ensureRunning()
    }

    private fun join(code: String, groupName: String) {
        activate(code, groupName)
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        finish()
    }

    companion object {
        fun qrText(code: String, name: String): String = "hopline://join?code=${Words.normalise(code)}&name=${Uri.encode(name)}"

        fun parseQr(text: String): Pair<String, String>? {
            val t = text.trim()
            if (t.startsWith("hopline://")) {
                val uri = Uri.parse(t)
                val code = uri.getQueryParameter("code") ?: return null
                return if (Words.looksValid(code)) code to (uri.getQueryParameter("name") ?: "") else null
            }
            return if (Words.looksValid(t)) t to "" else null
        }
    }
}
