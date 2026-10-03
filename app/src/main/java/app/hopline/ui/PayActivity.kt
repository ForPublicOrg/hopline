package app.hopline.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telephony.TelephonyManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import app.hopline.R
import app.hopline.core.Upi
import app.hopline.databinding.ActivityPayBinding
import app.hopline.databinding.ItemPayLineBinding
import app.hopline.databinding.ItemPayStepBinding
import app.hopline.service.Cell
import app.hopline.service.Core
import java.text.NumberFormat

/**
 * Paying by UPI where there is no internet. Every UPI app needs data, but *99# — the banks' own
 * service — runs on plain phone signal and pays any UPI ID; Android just lets no app answer its
 * menu. The *99# box asks for the UPI ID and the amount itself, so this screen asks for nothing:
 * it says plainly what the box will ask, what *99# can't do, and what its errors mean, and opens
 * the Phone app on Send Money > UPI ID for the person to press call. Their PIN goes into their
 * phone company's box, never into Hopline.
 *
 * The phone's own business, not a group's: it needs nothing from a group and never starts the
 * mesh. Nothing on it needs saving: rotation and process death just draw it again.
 */
class PayActivity : AppCompatActivity() {
    private lateinit var b: ActivityPayBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPayBinding.inflate(layoutInflater)
        setContentView(b.root)

        b.toolbar.setNavigationOnClickListener { finish() }
        for (h in listOf(b.heroTitle, b.stepsLabel, b.knowLabel, b.helpLabel)) ViewCompat.setAccessibilityHeading(h, true)

        b.go.setOnClickListener { dial(Upi.CODE_SEND) }
        STEPS.forEachIndexed { i, res ->
            val s = ItemPayStepBinding.inflate(layoutInflater, b.steps, false)
            val n = NumberFormat.getIntegerInstance().format(i + 1)
            s.num.text = n
            s.text.setText(res)
            s.root.contentDescription = getString(R.string.pay_step_desc, n, getString(res))
            b.steps.addView(s.root)
        }
        for (res in KNOW) line(b.knowList, res)
        b.setup.setOnClickListener { dial(Upi.CODE_MENU) }
        for (res in HELP) line(b.helpList, res)
        b.openMenu.setOnClickListener { dial(Upi.CODE_MENU) }

        // With no group the mesh never starts the service-state listener, and "no signal" could
        // never be said. Starting it here starts nothing else; each report redraws the notes.
        Cell.start(applicationContext)
        Cell.heard.observe(this) { renderNotes() }

        // 2.3 kept the people paid (names and amounts) in a file of its own; nothing reads it now.
        if (savedInstanceState == null) deleteSharedPreferences(OLD_RECENTS)
    }

    override fun onResume() {
        super.onResume()
        renderNotes()
        b.root.removeCallbacks(recheckNotes)
        b.root.postDelayed(recheckNotes, NOTES_EVERY_MS)
    }

    override fun onPause() {
        super.onPause()
        b.root.removeCallbacks(recheckNotes)
    }

    /**
     * Signal and SIM are asked again every few seconds while the screen is in front. A change in
     * service is heard the moment it happens, but "can it text?" also needs the SIM ready and
     * airplane mode off, and those can settle a moment later with nothing to say so — leaving
     * "No phone signal" on screen under a full bar.
     */
    private val recheckNotes = object : Runnable {
        override fun run() {
            if (isFinishing || isDestroyed) return
            renderNotes()
            b.root.postDelayed(this, NOTES_EVERY_MS)
        }
    }

    /** What is true right now — internet, signal, a Jio SIM. */
    private fun renderNotes() {
        b.onlineNote.isVisible = Core.internetNow()
        b.signalNote.isVisible = !Cell.canText(this)
        b.jioNote.isVisible = onJio()
    }

    /** By the SIM's operator, which needs no permission to read. Not knowing is not Jio. */
    private fun onJio(): Boolean = try {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        tm != null && Upi.jio(tm.simOperatorName, tm.simOperator)
    } catch (e: Exception) { false }

    /** One bulleted line at the end of [list]. */
    private fun line(list: LinearLayout, res: Int) {
        val l = ItemPayLineBinding.inflate(layoutInflater, list, false)
        l.text.setText(res)
        list.addView(l.root)
    }

    /**
     * The Phone app, opened with [code] typed in for the person to press call. Only ever one of
     * the two fixed *99# codes ([Upi.dialable]). Dialling needs no permission; calling stays the
     * person's own tap.
     */
    private fun dial(code: String) {
        if (!Upi.dialable(code)) return
        val i = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", code, null))   // fromParts writes the # as %23
        try { startActivity(i) } catch (x: ActivityNotFoundException) { noPhoneApp() } catch (x: SecurityException) { noPhoneApp() }
    }

    private fun noPhoneApp() = Toast.makeText(this, R.string.pay_no_phone_app, Toast.LENGTH_LONG).show()

    companion object {
        private const val NOTES_EVERY_MS = 4_000L
        private const val OLD_RECENTS = "hopline_pay"

        private val STEPS = listOf(R.string.pay_step_1, R.string.pay_step_2, R.string.pay_step_3, R.string.pay_step_4,
            R.string.pay_step_5, R.string.pay_step_6)
        private val KNOW = listOf(R.string.pay_know_limit, R.string.pay_know_sims, R.string.pay_know_sim, R.string.pay_know_codes,
            R.string.pay_know_pin)
        private val HELP = listOf(R.string.pay_help_mmi, R.string.pay_help_setup, R.string.pay_help_menu, R.string.pay_help_id,
            R.string.pay_help_declined, R.string.pay_help_unsure)

        /**
         * Should Home offer Pay without internet on this phone? It has to be able to make calls, and
         * not be on a SIM from abroad — *99# is Indian ([Upi.offered]). Not knowing is yes. This asks
         * the telephony service, so Home asks when it comes back and keeps the answer, rather than
         * on every redraw.
         */
        fun offered(ctx: Context): Boolean {
            if (!ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY)) return false
            return try {
                val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
                Upi.offered(tm.simCountryIso, tm.networkCountryIso)
            } catch (e: Exception) { true }
        }
    }
}
