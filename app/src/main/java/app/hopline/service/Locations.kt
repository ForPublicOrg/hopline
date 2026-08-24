package app.hopline.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * Where this phone is. GPS works with no signal and no internet — exactly the places Hopline
 * lives — so location sharing is pure hardware, no Play services, no map tiles.
 * Main thread only, like the rest of the app.
 */
object Locations {
    /** Best fix this process has seen, from any source. Powers "1.2 km away" lines cheaply. */
    private var cached: Location? = null
    private var lastScanAt = 0L

    fun fineGranted(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun granted(ctx: Context): Boolean = fineGranted(ctx) ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Android 12+ wants fine and coarse asked together; the user may grant only "approximate". */
    fun toRequest(): Array<String> = arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

    private fun manager(ctx: Context): LocationManager? =
        ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    /** The phone's location switch — permission alone isn't enough. */
    fun serviceOn(ctx: Context): Boolean = try {
        val lm = manager(ctx) ?: return false
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { false }

    private fun note(l: Location) {
        val c = cached
        if (c == null || l.time >= c.time) cached = l
    }

    /**
     * The freshest fix any app already got — never turns the radio on, so it's cheap enough to
     * call while drawing the chat list. Re-scans providers at most every 10 s.
     */
    @SuppressLint("MissingPermission")  // guarded by granted()
    fun lastKnown(ctx: Context): Location? {
        if (!granted(ctx)) return null
        val now = System.currentTimeMillis()
        if (now - lastScanAt > 10_000) {
            lastScanAt = now
            val lm = manager(ctx)
            if (lm != null) for (p in lm.allProviders) {
                try { lm.getLastKnownLocation(p)?.let { note(it) } } catch (e: Exception) { }
            }
        }
        return cached
    }

    /**
     * Live fixes from GPS and network until the returned function is called. Fires on the main
     * thread. Under open sky a cold GPS fix can take a minute — the caller shows that honestly.
     * One-shot senders want fixes fast (default); live sharing passes a lazier interval to spare
     * the battery over an hour.
     */
    @SuppressLint("MissingPermission")  // guarded by granted()
    fun watch(ctx: Context, minTimeMs: Long = 1000L, onFix: (Location) -> Unit): () -> Unit {
        if (!granted(ctx)) return {}
        val lm = manager(ctx) ?: return {}
        // Explicit object, not a lambda: pre-API-30 phones still call the old callbacks.
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { note(location); onFix(location) }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) { }
            override fun onProviderEnabled(provider: String) { }
            override fun onProviderDisabled(provider: String) { }
        }
        var listening = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (lm.isProviderEnabled(p)) { lm.requestLocationUpdates(p, minTimeMs, 0f, listener, Looper.getMainLooper()); listening = true }
            } catch (e: Exception) { /* coarse-only grant can't use GPS; network may not exist */ }
        }
        if (!listening) return {}
        return { try { lm.removeUpdates(listener) } catch (e: Exception) { } }
    }
}
