package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import app.hopline.databinding.ActivityWelcomeBinding
import app.hopline.service.Core

class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        b.name.setText(Core.store.name)
        fun go() {
            val name = b.name.text.toString().trim()
            if (name.length < 2) { b.name.error = "Type your name"; return }
            Core.store.name = name
            Core.router?.me?.name = name
            startActivity(Intent(this, LaunchActivity::class.java)); finish()
        }
        b.go.setOnClickListener { go() }
        b.name.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
    }
}
