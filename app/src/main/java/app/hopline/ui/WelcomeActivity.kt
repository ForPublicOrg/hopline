package app.hopline.ui

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import app.hopline.R
import app.hopline.core.Names
import app.hopline.databinding.ActivityWelcomeBinding
import app.hopline.service.Core

/** First run: what Hopline is, and what friends should call you. */
class WelcomeActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val b = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(b.root)
        // The same cap and cleaning as every other place a name is set (and as other phones apply).
        b.name.filters = arrayOf(InputFilter.LengthFilter(Names.MAX_PERSON))
        if (savedInstanceState == null) b.name.setText(Core.store.name)
        b.name.doAfterTextChanged { b.name.error = null }
        fun go() {
            val raw = b.name.text?.toString().orEmpty()
            if (!Asks.validPersonName(raw) || !Core.setMyName(raw)) {
                b.name.error = getString(R.string.name_too_short)
                b.name.requestFocus()
                return
            }
            startActivity(Intent(this, LaunchActivity::class.java)); finish()
        }
        b.go.setOnClickListener { go() }
        b.name.setOnEditorActionListener { _, id, _ -> if (id == EditorInfo.IME_ACTION_DONE) { go(); true } else false }
    }
}
