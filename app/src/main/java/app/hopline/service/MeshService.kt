package app.hopline.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the radio and router alive with the screen off. This is the single biggest thing a
 * native app can do that a web page cannot: keep relaying for everyone else while in a pocket.
 */
class MeshService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private var foreground = false
    private var locationType = false
    private val ticker = object : Runnable {
        override fun run() {
            Core.tick()
            refreshWakeLock()
            refreshNotification()
            Core.handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        foreground = promote(location = false)
        if (!foreground) { stopSelf(); return }
        live = this
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hopline:mesh").also { it.setReferenceCounted(false) }
        } catch (e: Exception) { }
    }

    /** Become (or stay) a foreground service; with [location] while live location is on. */
    private fun promote(location: Boolean): Boolean {
        val notif = Notifications.service(this, "Starting…")
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                if (location && Locations.granted(this)) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(Notifications.ID_SERVICE, notif, type)
            } else startForeground(Notifications.ID_SERVICE, notif)
            locationType = location
            true
        } catch (e: Exception) {
            // e.g. Nearby permissions revoked in Settings after a sticky restart, or a location
            // type requested from the background — the app will re-ask when it is opened.
            Log.w("Hopline/Service", "startForeground refused", e)
            false
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!foreground) return START_NOT_STICKY
        // No group for the radio — none joined, or every one of them left (their chats are only
        // read, and reading needs no service). Also what a sticky restart finds after the last
        // group was left: it must not sit in the shade, and START_NOT_STICKY ends the restarts.
        if (!Core.hasGroup()) return quit()
        if (intent?.hasExtra(EXTRA_LOCATION) == true) {
            val want = intent.getBooleanExtra(EXTRA_LOCATION, false)
            if (want != locationType && !promote(want)) promote(false)
        } else if (intent == null && locationType) {
            // A sticky restart can't hold the location type (Android 14 forbids it from the
            // background), and the live-location timer died with the process anyway.
            promote(false)
        }
        // No radio is possible (no permission, or a build that failed for good): don't sit in the
        // shade saying "Starting…" with a wakelock. A build that is only waiting — the group's
        // saved state still being read, its key still being worked out — is another matter: the
        // service stays, and the radio starts through it the moment the router is there
        // ([routerReady]). Quitting now would leave the radio off until the app is opened, since
        // a service can't be brought back from the background.
        if (!Core.prepare()) return quit()
        run()
        return START_STICKY
    }

    /** The router is there (or on its way): start its radio, and the ticks that keep it going. */
    private fun run() {
        if (Core.router != null) Core.startRadio()
        Core.handler.removeCallbacks(ticker)
        Core.handler.postDelayed(ticker, 3000)
        refreshWakeLock()
    }

    /** Nothing for this service to do: take its notification away with it, and don't come back by itself. */
    private fun quit(): Int {
        try { if (Build.VERSION.SDK_INT >= 24) stopForeground(STOP_FOREGROUND_REMOVE) else @Suppress("DEPRECATION") stopForeground(true) } catch (e: Exception) { }
        stopSelf()
        return START_NOT_STICKY
    }

    /**
     * Keep the CPU awake only while there is something to relay, a position to share, or a request
     * to run — or while the group is still starting: its key being worked out (seconds, the first
     * time after an update that may have come in by itself with the screen off) or its state being
     * read. A phone asleep half way would leave the radio off until something else woke it.
     */
    private fun refreshWakeLock() {
        val wl = wakeLock ?: return
        val r = Core.router
        val busy = Core.buildPending || (r != null && (r.authedLinks().isNotEmpty() || r.peopleInRange() > 0 || Core.liveLocationActive() ||
            r.errands.values.any { r.isRunning(it.id) }))
        try {
            if (busy) wl.acquire(TICK_MS * 3) else if (wl.isHeld) wl.release()
        } catch (e: Exception) { }
    }

    @android.annotation.SuppressLint("MissingPermission")  // notify() is wrapped in try/catch; the service notification itself needs no permission
    private fun refreshNotification() {
        val r = Core.router
        if (r == null) {
            // Its group is still starting (see onStartCommand), or the radio just moved to another one.
            try { NotificationManagerCompat.from(this).notify(Notifications.ID_SERVICE, Notifications.service(this, "Starting…")) } catch (e: Exception) { }
            return
        }
        val links = r.authedLinks().size
        var text = when {
            links == 0 -> "Looking for your group's phones…"
            else -> "Linked to $links ${if (links == 1) "phone" else "phones"} · ${r.peopleInRange()} in range"
        }
        // Sharing your position must never be invisible — it lives in the always-on notification.
        if (Core.liveLocationActive()) text += " · 📡 sharing live location"
        val helping = r.errands.values.count { r.isRunning(it.id) && it.from != r.me.id }
        if (helping > 0) text += " · 🌐 helping $helping"
        try { NotificationManagerCompat.from(this).notify(Notifications.ID_SERVICE, Notifications.service(this, text)) } catch (e: Exception) { }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Some phones kill the whole process right after a swipe-away: save first, and give the
        // save a moment to reach the disk — not long, Android is waiting on this too.
        Core.flushSave()
        Core.drainWrites(TASK_REMOVED_WAIT_MS)
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (live === this) live = null
        Core.handler.removeCallbacks(ticker)
        if (foreground) Core.stopRadio()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val TICK_MS = 30_000L
        private const val EXTRA_LOCATION = "location"
        /** How long a swipe-away waits for the last save, at most. */
        private const val TASK_REMOVED_WAIT_MS = 1_500L

        /** The service while it is up in the foreground (let go in onDestroy). Main thread only. */
        @android.annotation.SuppressLint("StaticFieldLeak")
        private var live: MeshService? = null

        /**
         * The router the service was waiting for has been built (see onStartCommand): its radio
         * starts now. False when no service is up to start it.
         */
        fun routerReady(): Boolean {
            val s = live ?: return false
            s.run()
            return true
        }

        /** The build the service was waiting for failed for good: nothing to keep alive. */
        fun nothingToRun() { live?.quit() }

        /** Live location needs the location service type, granted only while a screen is showing. */
        fun updateLocationType(ctx: Context, on: Boolean) {
            try { ContextCompat.startForegroundService(ctx, Intent(ctx, MeshService::class.java).putExtra(EXTRA_LOCATION, on)) }
            catch (e: Exception) { Log.w("Hopline/Service", "could not switch location type", e) }
        }
    }
}
