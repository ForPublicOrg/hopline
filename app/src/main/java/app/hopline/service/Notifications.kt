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
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.hopline.R
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.Message
import app.hopline.ui.ChatActivity
import app.hopline.ui.MainActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_MESSAGES = "messages"
    const val ID_SERVICE = 1
    private var msgSeq = 100

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
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
        val group = Core.store.group()?.name?.ifEmpty { null }
        val title = when (m.kind) {
            Envelope.DM -> "${m.fromName} (private)"
            else -> group ?: "Hopline"
        }
        val body = when (m.kind) {
            Envelope.DM -> m.text
            Message.SYSTEM -> "🌐 ${m.text}"
            else -> "${m.fromName}: ${m.text}"
        }
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF008069.toInt())
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, intent))
            .build()
        NotificationManagerCompat.from(ctx).notify(msgSeq++, n)
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

    private fun open(ctx: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, intent.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
