package app.hopline.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.hopline.R
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.Message
import app.hopline.ui.ChatActivity
import app.hopline.ui.MainActivity
import app.hopline.ui.SosActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_MESSAGES = "messages"
    const val CH_ALERTS = "alerts"
    const val ID_SERVICE = 1
    const val ID_SOS = 2
    private var msgSeq = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_ALERTS, ctx.getString(R.string.channel_alerts), NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500); setBypassDnd(true)
        })
    }

    fun service(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(R.string.notif_service))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, Intent(ctx, MainActivity::class.java)))
            .build()

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun message(ctx: Context, m: Message) {
        if (!canPost(ctx)) return
        val intent = if (m.kind == Envelope.DM) Intent(ctx, ChatActivity::class.java).putExtra("peer", m.from) else Intent(ctx, MainActivity::class.java)
        val title = when (m.kind) {
            Envelope.DM -> "${m.fromName} (private)"
            Message.SYSTEM -> "From the internet"
            else -> m.fromName
        }
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(title)
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, intent))
            .build()
        NotificationManagerCompat.from(ctx).notify(msgSeq++, n)
    }

    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun sos(ctx: Context, m: Message) {
        vibrate(ctx)
        val intent = Intent(ctx, SosActivity::class.java).putExtra("text", m.text).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { ctx.startActivity(intent) } catch (e: Exception) { }
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_ALERTS)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(R.string.sos_title))
            .setContentText(m.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(m.text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setFullScreenIntent(open(ctx, intent), true)
            .setContentIntent(open(ctx, intent))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_SOS, n)
    }

    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun sendRequest(ctx: Context, e: Errand) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(R.string.errand_send_title, e.fromName))
            .setContentText("Your phone has signal — open Hopline to send it.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, Intent(ctx, MainActivity::class.java)))
            .build()
        NotificationManagerCompat.from(ctx).notify(msgSeq++, n)
    }

    fun vibrate(ctx: Context) {
        try {
            val v = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
                    else @Suppress("DEPRECATION") ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 200, 500, 200, 500), -1))
        } catch (e: Exception) { }
    }

    private fun open(ctx: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, intent.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
