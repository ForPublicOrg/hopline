package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
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
        val group = Core.store.group()
        if (group == null) { startActivity(Intent(this, LaunchActivity::class.java)); finish(); return }
        val b = ActivityCodeBinding.inflate(layoutInflater)
        setContentView(b.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pretty = Words.pretty(group.code)
        val name = Core.router?.group?.name?.ifEmpty { null } ?: group.name
        b.groupName.text = name.ifEmpty { getString(R.string.your_group) }
        b.code.text = pretty.split(' ').joinToString("\n")
        b.code.contentDescription = pretty
        // A name can be long (and non-Latin names grow a lot once escaped into a link): if the full
        // invite won't fit in a QR, a code-only one still joins — it just skips the name hint.
        val bmp = qr(GroupActivity.qrText(group.code, name), QR_SIZE) ?: qr(GroupActivity.qrText(group.code, ""), QR_SIZE)
        if (bmp != null) b.qr.setImageBitmap(bmp) else b.qr.visibility = View.GONE
        b.qr.contentDescription = getString(R.string.qr_desc, name.ifEmpty { pretty })

        b.share.setOnClickListener {
            val text = getString(R.string.invite_text, name.ifEmpty { getString(R.string.our_group) }, pretty)
            try {
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            } catch (e: ActivityNotFoundException) { }
        }
        b.copy.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.code_clip_label), pretty))
            // Android 13+ confirms a copy on its own; a toast on top would say it twice.
            if (Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.code_copied, Toast.LENGTH_SHORT).show()
        }

        val first = intent.getBooleanExtra("first", false)
        b.done.setOnClickListener {
            // After starting a group: back to the Home already under us (from +), or a fresh one on
            // first run — never a second Home stacked on the first.
            if (first) startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
    }

    /** The QR as a bitmap, or null when the text can't be encoded (too long for any QR). */
    private fun qr(text: String, size: Int): Bitmap? = try {
        val hints = mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 1)
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width; val h = matrix.height
        val px = IntArray(w * h)
        for (y in 0 until h) { val row = y * w; for (x in 0 until w) px[row + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE }
        Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
    } catch (e: Exception) {
        Log.w("Hopline/Code", "QR not possible for this text", e)
        null
    }

    companion object { private const val QR_SIZE = 600 }
}
