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

/** Two big cards. Start a group (you get a code) or join one (type / scan a code). Nothing else to configure. */
class GroupActivity : AppCompatActivity() {
    private lateinit var b: ActivityGroupBinding

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
        val name = Core.store.name
        if (name.isBlank()) { startActivity(Intent(this, WelcomeActivity::class.java)); finish(); return }
        b.hello.text = "Hi $name! 👋"
        b.groupName.setText("$name's group")

        b.cardStart.setOnClickListener { b.startPanel.visibility = View.VISIBLE; b.joinPanel.visibility = View.GONE; b.groupName.requestFocus() }
        b.cardJoin.setOnClickListener { b.joinPanel.visibility = View.VISIBLE; b.startPanel.visibility = View.GONE; b.code.requestFocus() }

        b.startBtn.setOnClickListener {
            val gname = b.groupName.text.toString().trim().ifEmpty { "$name's group" }
            val code = Words.randomCode()
            Core.store.setGroup(code, gname)
            Core.ensureRunning()
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

        // Opened from a QR link?
        intent?.data?.let { uri -> parseQr(uri.toString())?.let { join(it.first, it.second) } }
    }

    private fun join(code: String, groupName: String) {
        Core.store.setGroup(code, groupName)
        Core.ensureRunning()
        startActivity(Intent(this, MainActivity::class.java))
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
