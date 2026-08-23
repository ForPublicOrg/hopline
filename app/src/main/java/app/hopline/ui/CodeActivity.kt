package app.hopline.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.core.Words
import app.hopline.databinding.ActivityCodeBinding
import app.hopline.service.Core
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** The three words, huge, plus a QR of the same thing. Keep the screen on so it can be held up for others. */
class CodeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityCodeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val group = Core.store.group()
        if (group == null) { finish(); return }
        val pretty = Words.pretty(group.code)
        b.groupName.text = group.name.ifEmpty { "Your group" }
        b.code.text = pretty.split(' ').joinToString("\n")
        b.qr.setImageBitmap(qr(GroupActivity.qrText(group.code, group.name), 600))

        b.share.setOnClickListener {
            val text = getString(R.string.invite_text, group.name.ifEmpty { "our group" }, pretty)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
        }
        b.copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("Hopline group code", pretty))
            Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
        }

        val first = intent.getBooleanExtra("first", false)
        b.done.setOnClickListener {
            if (first) startActivity(Intent(this, HomeActivity::class.java))
            finish()
        }
    }

    private fun qr(text: String, size: Int): Bitmap {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) bmp.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        return bmp
    }
}
