package app.hopline.service

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** Which runtime permissions Nearby Connections needs on this Android version. */
object Permissions {
    fun required(): List<String> {
        val list = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            list += Manifest.permission.BLUETOOTH_SCAN
            list += Manifest.permission.BLUETOOTH_ADVERTISE
            list += Manifest.permission.BLUETOOTH_CONNECT
        }
        if (Build.VERSION.SDK_INT >= 33) {
            list += Manifest.permission.NEARBY_WIFI_DEVICES
            list += Manifest.permission.POST_NOTIFICATIONS
        } else {
            // Android 12/12L silently ignores a request for FINE without COARSE.
            list += Manifest.permission.ACCESS_FINE_LOCATION
            list += Manifest.permission.ACCESS_COARSE_LOCATION
        }
        return list
    }

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    /**
     * The permissions still missing that Nearby truly needs. Notifications are nice-to-have, and
     * COARSE only rides along with FINE (Nearby scanning needs precise location below Android 13).
     */
    fun missing(context: Context): List<String> = required().filter { p ->
        p != Manifest.permission.POST_NOTIFICATIONS && p != Manifest.permission.ACCESS_COARSE_LOCATION &&
            ContextCompat.checkSelfPermission(context, p) != PackageManager.PERMISSION_GRANTED
    }

    /** "Approximate" was chosen where Nearby needs "Precise" (Android 12/12L). */
    fun onlyApproximate(context: Context): Boolean = Build.VERSION.SDK_INT in 31..32 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED

    /** Older Android needs Location *services* switched on for Bluetooth scanning, even with permission granted. */
    fun needsLocationService(): Boolean = Build.VERSION.SDK_INT < 31
}
