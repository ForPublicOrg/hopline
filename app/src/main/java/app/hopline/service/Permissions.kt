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
            list += Manifest.permission.ACCESS_FINE_LOCATION
        }
        return list
    }

    fun allGranted(context: Context): Boolean = required().all { p ->
        // notifications are nice-to-have; everything else is needed to link phones
        p == Manifest.permission.POST_NOTIFICATIONS ||
            ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
    }

    /** Older Android needs Location *services* switched on for Bluetooth scanning, even with permission granted. */
    fun needsLocationService(): Boolean = Build.VERSION.SDK_INT < 31
}
