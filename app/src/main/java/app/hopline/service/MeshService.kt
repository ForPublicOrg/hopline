package app.hopline.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationManagerCompat

/**
 * Keeps the radio and router alive with the screen off. This is the single biggest thing a
 * native app can do that a web page cannot: keep relaying for everyone else while in a pocket.
 */
class MeshService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null
    private val ticker = object : Runnable {
        override fun run() {
            Core.tick()
            refreshNotification()
            Core.handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val notif = Notifications.service(this, "Starting…")
        if (Build.VERSION.SDK_INT >= 29) startForeground(Notifications.ID_SERVICE, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(Notifications.ID_SERVICE, notif)
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hopline:mesh").also { it.acquire() }
        } catch (e: Exception) { }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!Core.hasGroup()) { stopSelf(); return START_NOT_STICKY }
        if (Core.router == null) Core.ensureRunning()
        Core.startRadio()
        Core.handler.removeCallbacks(ticker)
        Core.handler.postDelayed(ticker, 3000)
        return START_STICKY
    }

    private fun refreshNotification() {
        val r = Core.router ?: return
        val links = r.authedLinks().size
        val text = when {
            links == 0 -> "Looking for your group's phones…"
            else -> "Linked to $links ${if (links == 1) "phone" else "phones"} · ${r.peopleInRange()} in range"
        }
        try { NotificationManagerCompat.from(this).notify(Notifications.ID_SERVICE, Notifications.service(this, text)) } catch (e: Exception) { }
    }

    override fun onDestroy() {
        Core.handler.removeCallbacks(ticker)
        Core.stopRadio()
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (e: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object { const val TICK_MS = 30_000L }
}
