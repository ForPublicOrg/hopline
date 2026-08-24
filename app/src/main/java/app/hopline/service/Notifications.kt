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
import app.hopline.ui.HomeActivity

object Notifications {
    const val CH_SERVICE = "service"
    const val CH_MESSAGES = "messages"
    const val ID_SERVICE = 1
    private const val ID_ERRAND = 2
    /** Messages waiting in each chat's single notification (main thread only). */
    private val chatCounts = HashMap<String, Int>()
    /** So a backlog full of @mentions buzzes once, not once per message. */
    private var lastMentionBuzzAt = 0L

    private fun idFor(chat: String): Int = 1000 + (chat.hashCode() and 0xFFFF)

    /** The chat was opened — its notification and count go away. */
    fun clearChat(ctx: Context, chat: String) {
        if (chatCounts.remove(chat) != null) NotificationManagerCompat.from(ctx).cancel(idFor(chat))
    }

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
            .setContentIntent(open(ctx, Intent(ctx, HomeActivity::class.java)))
            .build()

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** What one message looks like in a one-line preview. */
    fun preview(ctx: Context, m: Message): String {
        val att = m.att
        val body = when {
            m.loc != null -> ctx.getString(R.string.location_label) + if (m.loc.label.isNotEmpty()) " — ${m.loc.label}" else ""
            att == null -> m.text
            att.isAudio -> ctx.getString(R.string.voice_label) + if (att.dur > 0) " (${att.dur / 60}:${"%02d".format(att.dur % 60)})" else ""
            att.isImage -> ctx.getString(R.string.photo_label) + if (m.text.isNotEmpty()) " ${m.text}" else ""
            else -> "${ctx.getString(R.string.file_label)} ${att.name}"
        }
        return if (m.kind == Message.SYSTEM) "🌐 $body" else body
    }

    /**
     * One notification per chat, updated in place. A reunion after hours apart delivers a whole
     * backlog in seconds — that must read as "23 new messages", not 23 separate heads-ups.
     */
    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun message(ctx: Context, m: Message) {
        if (!canPost(ctx)) return
        val private = m.to != null
        val chat = if (private) m.from else Core.GROUP
        val count = (chatCounts[chat] ?: 0) + 1
        chatCounts[chat] = count
        val intent = Intent(ctx, ChatActivity::class.java).apply { if (private) putExtra("peer", m.from) }
        val group = Core.store.group()?.name?.ifEmpty { null }
        val title = when {
            private -> "${m.fromName} (private)"
            else -> group ?: "Hopline"
        }
        val mentioned = m.mentions.contains(Core.store.nodeId)
        val body = (if (mentioned) ctx.getString(R.string.mentioned_you) + " · " else "") +
            (if (private || m.kind == Message.SYSTEM) preview(ctx, m) else "${m.fromName}: ${preview(ctx, m)}")
        // A message that spent a while hopping to us is history, not breaking news: no buzz —
        // unless it names me: being called out loud is worth a buzz however old the message is.
        // But a reunion delivering ten old mentions must be one buzz, not a drum roll.
        val now = System.currentTimeMillis()
        val mentionBuzz = mentioned && now - lastMentionBuzzAt > 10_000
        if (mentionBuzz) lastMentionBuzzAt = now
        val backlog = now - m.ts > 2 * 60_000 && !mentionBuzz
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFD9481F.toInt())
            .setContentTitle(if (count > 1) "$title · $count new" else title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setNumber(count)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!mentionBuzz)
            .setSilent(backlog)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, intent))
            .build()
        NotificationManagerCompat.from(ctx).notify(idFor(chat), n)
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
            .setContentIntent(open(ctx, Intent(ctx, ChatActivity::class.java)))
            .build()
        NotificationManagerCompat.from(ctx).notify(ID_ERRAND, n)
    }

    private fun open(ctx: Context, intent: Intent): PendingIntent =
        PendingIntent.getActivity(ctx, intent.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
}
