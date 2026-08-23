package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import app.hopline.databinding.ActivityViewerBinding
import java.io.File

/** A received photo, full screen, with share. */
class ViewerActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityViewerBinding.inflate(layoutInflater)
        setContentView(b.root)
        val path = intent.getStringExtra("path") ?: run { finish(); return }
        val file = File(path)
        if (!file.exists()) { finish(); return }
        b.title.text = intent.getStringExtra("name") ?: file.name
        Images.load(file, b.image, targetPx = 1600)
        b.back.setOnClickListener { finish() }
        b.share.setOnClickListener {
            try {
                val uri = FileProvider.getUriForFile(this, ChatActivity.FILES_AUTHORITY, file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND)
                    .setType(intent.getStringExtra("mime") ?: "image/jpeg")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
            } catch (e: Exception) { }
        }
    }
}
