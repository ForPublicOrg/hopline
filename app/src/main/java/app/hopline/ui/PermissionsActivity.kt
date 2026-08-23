package app.hopline.ui

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import app.hopline.R
import app.hopline.databinding.ActivityPermissionsBinding
import app.hopline.service.Core
import app.hopline.service.Permissions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability

class PermissionsActivity : AppCompatActivity() {
    private lateinit var b: ActivityPermissionsBinding
    private var asked = false

    private val request = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        b = ActivityPermissionsBinding.inflate(layoutInflater)
        setContentView(b.root)

        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) {
            AlertDialog.Builder(this).setMessage(R.string.no_play_services).setPositiveButton(R.string.ok) { _, _ -> finish() }.setCancelable(false).show()
            return
        }

        b.allow.setOnClickListener { asked = true; request.launch(Permissions.required().toTypedArray()) }
        b.settings.setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        b.btBtn.setOnClickListener {
            try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (e: Exception) { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        }
        b.wifiBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        b.locBtn.setOnClickListener { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
        b.next.setOnClickListener {
            Core.store.permissionsDone = true
            startActivity(Intent(this, LaunchActivity::class.java)); finish()
        }
    }

    override fun onResume() { super.onResume(); refresh() }

    private fun refresh() {
        val granted = Permissions.allGranted(this)
        b.allow.visibility = if (granted) View.GONE else View.VISIBLE
        val deniedForGood = !granted && asked && Permissions.required().any { !shouldShowRequestPermissionRationale(it) }
        b.denied.visibility = if (deniedForGood) View.VISIBLE else View.GONE
        b.settings.visibility = if (deniedForGood) View.VISIBLE else View.GONE
        b.checks.visibility = if (granted) View.VISIBLE else View.GONE
        if (!granted) return

        val bt = Core.bluetoothOn(); val wifi = Core.wifiOn()
        val needLoc = Permissions.needsLocationService()
        val loc = !needLoc || locationOn()
        b.btText.text = if (bt) "✓  Bluetooth is on" else "Bluetooth is off"
        b.btBtn.visibility = if (bt) View.INVISIBLE else View.VISIBLE
        b.wifiText.text = if (wifi) "✓  WiFi is on" else "WiFi is off (it doesn't need to be connected to anything)"
        b.wifiBtn.visibility = if (wifi) View.INVISIBLE else View.VISIBLE
        b.locRow.visibility = if (needLoc) View.VISIBLE else View.GONE
        b.locText.text = if (loc) "✓  Location is on" else "Location is off (older Android needs it to see Bluetooth)"
        b.locBtn.visibility = if (loc) View.INVISIBLE else View.VISIBLE
        b.next.isEnabled = bt && wifi && loc
    }

    private fun locationOn(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }
}
