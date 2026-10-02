package app.hopline.ui

import android.Manifest
import android.app.Dialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.databinding.ActivityPermissionsBinding
import app.hopline.service.Core
import app.hopline.service.Permissions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

@android.annotation.SuppressLint("MissingPermission")  // the Bluetooth enable prompt is only offered after permissions are granted
class PermissionsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPermissionsBinding
    /** The system dialog has answered at least once — only then can "denied for good" be true. */
    private var asked = false
    /** Play services' own fix-it dialog pops up once by itself; after that the button offers it. */
    private var playPrompted = false
    private var playDialog: Dialog? = null
    private var playOk = false

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { asked = true; refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)
        asked = savedInstanceState?.getBoolean(K_ASKED) ?: false
        playPrompted = savedInstanceState?.getBoolean(K_PLAY) ?: false
        b.locNote.visibility = if (Build.VERSION.SDK_INT <= 32) View.VISIBLE else View.GONE

        b.allow.setOnClickListener { request.launch(Permissions.required().toTypedArray()) }
        b.settings.setOnClickListener { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        b.btBtn.setOnClickListener {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        b.wifiBtn.setOnClickListener { open(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        b.locBtn.setOnClickListener { open(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        b.playBtn.setOnClickListener { checkPlayServices(offer = true); refresh() }
        b.next.setOnClickListener {
            Core.store.permissionsDone = true
            startActivity(Intent(this, LaunchActivity::class.java)); finish()
        }
    }

    // Re-checked every time the screen comes back: from the Play Store, from Settings, from a dialog.
    override fun onResume() {
        super.onResume()
        checkPlayServices(offer = !playPrompted)
        refresh()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(K_ASKED, asked)
        outState.putBoolean(K_PLAY, playPrompted)
    }

    override fun onDestroy() {
        playDialog?.dismiss(); playDialog = null
        super.onDestroy()
    }

    private fun open(i: Intent) { try { startActivity(i) } catch (e: Exception) { } }

    /**
     * Phones that rarely see the internet often carry an old (or switched-off) Google Play services.
     * That is fixable — offer Google's own update / turn-on dialog — rather than "won't work here".
     */
    private fun checkPlayServices(offer: Boolean) {
        val api = GoogleApiAvailability.getInstance()
        val code = api.isGooglePlayServicesAvailable(this)
        playOk = code == ConnectionResult.SUCCESS
        if (playOk) {
            playDialog?.dismiss(); playDialog = null
            b.playPanel.visibility = View.GONE
            return
        }
        b.playPanel.visibility = View.VISIBLE
        b.playBtn.visibility = View.VISIBLE
        when {
            code == ConnectionResult.SERVICE_UPDATING -> {
                b.playText.setText(R.string.play_updating)
                b.playBtn.setText(R.string.try_again)
            }
            (code == ConnectionResult.SERVICE_VERSION_UPDATE_REQUIRED || code == ConnectionResult.SERVICE_DISABLED) && api.isUserResolvableError(code) -> {
                val disabled = code == ConnectionResult.SERVICE_DISABLED
                b.playText.setText(if (disabled) R.string.play_disabled else R.string.play_update)
                b.playBtn.setText(if (disabled) R.string.play_enable_btn else R.string.play_update_btn)
                if (offer && playDialog?.isShowing != true && !isFinishing) {
                    playPrompted = true
                    playDialog = try { api.getErrorDialog(this, code, REQ_PLAY)?.also { it.show() } } catch (e: Exception) { null }
                }
            }
            else -> {
                // Missing or not genuine: nothing on this phone can fix it.
                b.playText.setText(R.string.no_play_services)
                b.playBtn.visibility = View.GONE
            }
        }
    }

    private fun refresh() {
        if (!playOk) {
            // Nothing else on this screen can work until Play services does.
            for (v in listOf(b.allow, b.denied, b.settings, b.checks, b.next)) v.visibility = View.GONE
            return
        }
        val missing = Permissions.missing(this)
        val granted = missing.isEmpty()
        b.allow.visibility = if (granted) View.GONE else View.VISIBLE
        // Only what is still missing counts: granted permissions (and notifications, which are
        // optional) also report "no rationale", and must not make it look denied for good.
        val deniedForGood = !granted && asked && missing.any { !shouldShowRequestPermissionRationale(it) }
        val nearby = missing.any { it.startsWith("android.permission.BLUETOOTH") || (Build.VERSION.SDK_INT >= 33 && it == Manifest.permission.NEARBY_WIFI_DEVICES) }
        val location = Manifest.permission.ACCESS_FINE_LOCATION in missing
        val problem = when {
            granted -> null
            // Android 12/12L: "Approximate" was picked, but finding phones needs "Precise".
            Permissions.onlyApproximate(this) && !nearby -> getString(R.string.perm_precise)
            !deniedForGood -> null
            nearby && location -> getString(R.string.perm_denied_both)
            nearby -> getString(R.string.perm_denied_nearby)
            else -> getString(R.string.perm_denied_location)
        }
        b.denied.text = problem.orEmpty()
        b.denied.visibility = if (problem != null) View.VISIBLE else View.GONE
        b.settings.visibility = if (problem != null) View.VISIBLE else View.GONE
        b.checks.visibility = if (granted) View.VISIBLE else View.GONE
        b.next.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) return

        val bt = Core.bluetoothOn(); val wifi = Core.wifiOn()
        val needLoc = Permissions.needsLocationService()
        val loc = !needLoc || locationOn()
        b.btText.setText(if (bt) R.string.check_bt_on else R.string.check_bt_off)
        b.btBtn.visibility = if (bt) View.INVISIBLE else View.VISIBLE
        b.wifiText.setText(if (wifi) R.string.check_wifi_on else R.string.check_wifi_off)
        b.wifiBtn.visibility = if (wifi) View.INVISIBLE else View.VISIBLE
        b.locRow.visibility = if (needLoc) View.VISIBLE else View.GONE
        b.locText.setText(if (loc) R.string.check_loc_on else R.string.check_loc_off)
        b.locBtn.visibility = if (loc) View.INVISIBLE else View.VISIBLE
        b.next.isEnabled = bt && wifi && loc
    }

    private fun locationOn(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }

    companion object {
        private const val K_ASKED = "asked"
        private const val K_PLAY = "playPrompted"
        private const val REQ_PLAY = 9001
    }
}
