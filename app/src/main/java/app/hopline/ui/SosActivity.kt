package app.hopline.ui

import android.os.Bundle
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import app.hopline.databinding.ActivitySosBinding

/** Full red screen when someone in the group holds HELP. Shows over the lock screen. */
class SosActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true); setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val b = ActivitySosBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.text.text = intent.getStringExtra("text") ?: ""
        b.ok.setOnClickListener { finish() }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        findViewById<android.widget.TextView>(app.hopline.R.id.text)?.text = intent.getStringExtra("text") ?: ""
    }
}
