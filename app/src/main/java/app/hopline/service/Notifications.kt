package app.hopline.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import app.hopline.R
import app.hopline.mesh.Errand
import app.hopline.mesh.Message
import app.hopline.ui.ChatActivity
import app.hopline.ui.HomeActivity
import app.hopline.ui.InternetActivity

/**
 * Every notification Hopline posts. Each is tagged with its group's fingerprint (plus the chat or
 * request it is about), so switching groups can clear the old group's ones, two chats can never
 * replace each other's, and a tap always opens the right group's chat.
 */
object Notifications {
    const val CH_SERVICE = "service"
    const val CH_MESSAGES = "messages"
    /** "A newer Hopline is ready": its own channel, so it can be switched off without touching messages. */
    const val CH_UPDATES = "updates"
    const val ID_SERVICE = 1
    const val EXTRA_FP = "fp"
    const val EXTRA_PEER = "peer"
    const val EXTRA_ERRAND = "errand"

    private const val KEY_REPLY = "reply"
    private const val ACTION_REPLY = "app.hopline.REPLY"
    private const val ACTION_READ = "app.hopline.MARK_READ"
    private const val ACTION_DECLINE = "app.hopline.DECLINE_SEND"

    /** The recent lines of each chat's notification ("fp|chat" -> lines), main thread only. */
    private val lines = HashMap<String, ArrayList<Line>>()
    private val counts = HashMap<String, Int>()
    private class Line(val who: String, val text: String, val ts: Long, val key: String, val mine: Boolean = false)
    /** So a backlog full of @mentions buzzes once, not once per message. */
    private var lastMentionBuzzAt = 0L

    private fun key(fp: String, chat: String) = "$fp|$chat"
    /** The full key is the tag: no hash, so no two chats or requests can collide. */
    private const val ID_CHAT = 2
    private const val ID_ERRAND = 3
    private const val ID_UPDATE = 4
    /** Not a group's fingerprint, so clearing a group's notifications never takes this one. */
    private const val TAG_UPDATE = "update"
    private fun errandTag(fp: String, eid: String) = "$fp|e|$eid"
    private fun errandId(eid: String): Int = 20_000 + (eid.hashCode() and 0xFFFF)   // request codes only
    /** Distinct data per chat/request and action: PendingIntents differing only in extras would be
     *  merged by Android when request codes collide — a reply could then go to the wrong chat. */
    private fun target(vararg parts: String): Uri =
        Uri.parse("hopline://n/" + parts.joinToString("/") { Uri.encode(it) })

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, ctx.getString(R.string.channel_service), NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
        nm.createNotificationChannel(NotificationChannel(CH_MESSAGES, ctx.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_UPDATES, ctx.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT).apply { setShowBadge(false) })
    }

    fun service(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_notif)
            .setContentTitle(ctx.getString(R.string.notif_service))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(ctx, Intent(ctx, HomeActivity::class.java), 1))
            .build()

    fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 || ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** What one message looks like in a one-line preview. */
    fun preview(ctx: Context, m: Message): String {
        val att = m.att
        val body = when {
            m.isRename -> ctx.getString(R.string.notice_renamed_short, m.text)
            // This phone's own notes in a chat carry no text: Home's row says what they mean.
            m.kind == Message.LEFT -> ctx.getString(R.string.chat_notice_left)
            m.kind == Message.REJOINED -> ctx.getString(R.string.chat_notice_rejoined)
            m.loc != null -> ctx.getString(R.string.location_label) + if (m.loc.label.isNotEmpty()) " — ${m.loc.label}" else ""
            att == null -> m.text
            att.isAudio && att.dur > 0 -> ctx.getString(R.string.voice_label) + " (${att.dur / 60}:${"%02d".format(att.dur % 60)})"
            // "📷 Trail news" — the caption says it better than the word "Photo".
            att.isImage -> if (m.text.isNotEmpty()) ctx.getString(R.string.photo_with_caption, m.text) else ctx.getString(R.string.photo_label)
            // The sender's name for it, minus anything that would make it read as something else.
            else -> "${ctx.getString(R.string.file_label)} ${app.hopline.ui.MediaRules.shownName(att.name)}"
        }
        return if (m.kind == Message.SYSTEM) "🌐 $body" else body
    }

    private fun chatIntent(ctx: Context, fp: String, chat: String): Intent =
        Intent(ctx, ChatActivity::class.java).apply {
            putExtra(EXTRA_FP, fp)
            if (chat != Core.GROUP) putExtra(EXTRA_PEER, chat)
        }

    /**
     * One notification per chat, updated in place, showing the last few lines like a messaging
     * app does. A reunion after hours apart delivers a whole backlog in seconds — that must read
     * as "23 new messages", not 23 separate heads-ups. Reply and Mark as read work from the shade.
     */
    fun message(ctx: Context, fp: String, m: Message) {
        if (!canPost(ctx)) return
        val private = m.to != null
        val chat = if (private) m.from else Core.GROUP
        val mentioned = m.mentions.contains(Core.store.nodeId)
        // A muted chat stays quiet — but being called out by name still gets through.
        if (Core.store.isMuted(fp, chat) && !mentioned) return
        val k = key(fp, chat)
        counts[k] = (counts[k] ?: 0) + 1
        val r = Core.router
        val sender = r?.let { app.hopline.ui.Ui.nameOf(it, m.from, m.fromName) } ?: m.fromName.ifEmpty { "Someone" }
        val text = (if (mentioned) ctx.getString(R.string.mentioned_you) + " · " else "") + preview(ctx, m)
        val list = lines.getOrPut(k) { ArrayList() }
        list.add(Line(if (m.kind == Message.SYSTEM) "🌐" else sender, text, m.ts, m.id))
        while (list.size > MAX_LINES) list.removeAt(0)
        // A message that spent a while hopping to us is history, not breaking news: no buzz —
        // unless it names me: being called out loud is worth a buzz however old the message is.
        // But a reunion delivering ten old mentions must be one buzz, not a drum roll.
        val now = System.currentTimeMillis()
        val mentionBuzz = mentioned && now - lastMentionBuzzAt > 10_000
        if (mentionBuzz) lastMentionBuzzAt = now
        val backlog = now - m.ts > 2 * 60_000 && !mentionBuzz
        schedule(ctx, fp, chat, silent = backlog, alertAgain = mentionBuzz)
    }

    /**
     * A reunion delivers dozens of messages within milliseconds, and Android drops updates to one
     * notification beyond about five a second — the last ones (the real count, a mention) would be
     * lost. So updates for a chat are gathered and posted once, a moment later: quiet only if
     * every gathered one was quiet, buzzing if any of them should.
     */
    private class Pending(var silent: Boolean, var alertAgain: Boolean)
    private val pending = HashMap<String, Pending>()

    private fun schedule(ctx: Context, fp: String, chat: String, silent: Boolean, alertAgain: Boolean) {
        val k = key(fp, chat)
        val p = pending[k]
        if (p != null) { p.silent = p.silent && silent; p.alertAgain = p.alertAgain || alertAgain; return }
        pending[k] = Pending(silent, alertAgain)
        val app = ctx.applicationContext
        Core.handler.postDelayed({
            val due = pending.remove(k) ?: return@postDelayed
            post(app, fp, chat, due.silent, due.alertAgain)
        }, COALESCE_MS)
    }

    /** "Asha reacted ❤️ to “see you at camp”" — on my own messages only, like every chat app. */
    fun reaction(ctx: Context, fp: String, chat: String, m: Message, by: String, emoji: String) {
        if (!canPost(ctx) || Core.store.isMuted(fp, chat)) return
        val r = Core.router ?: return
        val who = app.hopline.ui.Ui.nameOf(r, by)
        val snippet = preview(ctx, m).take(60)
        val k = key(fp, chat)
        val list = lines.getOrPut(k) { ArrayList() }
        list.removeAll { it.key == "re|${m.id}|$by" }
        list.add(Line(who, ctx.getString(R.string.reacted_line, emoji, snippet), System.currentTimeMillis(), "re|${m.id}|$by"))
        while (list.size > MAX_LINES) list.removeAt(0)
        schedule(ctx, fp, chat, silent = false, alertAgain = false)
    }

    /** They took the reaction back before I looked: take its line out too. */
    fun retractReaction(ctx: Context, fp: String, chat: String, msgId: String, by: String) {
        val k = key(fp, chat)
        val list = lines[k] ?: return
        if (!list.removeAll { it.key == "re|$msgId|$by" }) return
        if (list.isEmpty()) clearChat(ctx, fp, chat)
        else schedule(ctx, fp, chat, silent = true, alertAgain = false)
    }

    @SuppressLint("MissingPermission")  // guarded by canPost()
    private fun post(ctx: Context, fp: String, chat: String, silent: Boolean, alertAgain: Boolean) {
        val k = key(fp, chat)
        val list = lines[k] ?: return
        val private = chat != Core.GROUP
        val groupName = Core.store.findGroup(fp)?.name?.ifEmpty { null } ?: "Hopline"
        val me = Person.Builder().setName(ctx.getString(R.string.reply_you)).setKey("me").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (!private) style.setConversationTitle(groupName).setGroupConversation(true)
        for (l in list) {
            val p = if (l.mine) null else Person.Builder().setName(l.who).setKey(l.who).build()
            style.addMessage(NotificationCompat.MessagingStyle.Message(l.text, l.ts, p))
        }
        val count = counts[k] ?: 0
        val title = if (private) list.lastOrNull { !it.mine }?.who
            ?: Core.router?.takeIf { it.group.fingerprint == fp }?.let { app.hopline.ui.Ui.nameOf(it, chat) } ?: groupName
            else groupName
        val reqBase = (k.hashCode() and 0x3FFF) * 4
        val replyIntent = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_REPLY).setPackage(ctx.packageName)
            .setData(target(fp, chat, "reply")).putExtra(EXTRA_FP, fp).putExtra(EXTRA_PEER, chat)
        val reply = NotificationCompat.Action.Builder(R.drawable.ic_reply, ctx.getString(R.string.reply),
            PendingIntent.getBroadcast(ctx, reqBase + 1, replyIntent, PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)))
            .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel(ctx.getString(R.string.message_hint)).build())
            .setAllowGeneratedReplies(true)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .build()
        val readIntent = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_READ).setPackage(ctx.packageName)
            .setData(target(fp, chat, "read")).putExtra(EXTRA_FP, fp).putExtra(EXTRA_PEER, chat)
        val read = NotificationCompat.Action.Builder(R.drawable.ic_check, ctx.getString(R.string.mark_read),
            PendingIntent.getBroadcast(ctx, reqBase + 2, readIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
            .setShowsUserInterface(false)
            .build()
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFD9481F.toInt())
            .setStyle(style)
            .setContentTitle(if (count > 1) "$title · ${ctx.getString(R.string.n_new, count)}" else title)
            .setContentText(list.lastOrNull()?.text ?: "")
            .setNumber(count)
            .setAutoCancel(true)
            .setOnlyAlertOnce(!alertAgain)
            .setSilent(silent)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, chatIntent(ctx, fp, chat).setData(target(fp, chat, "open")), reqBase))
            .addAction(reply)
            .addAction(read)
            .build()
        try { NotificationManagerCompat.from(ctx).notify(k, ID_CHAT, n) } catch (e: Exception) { }
    }

    /** I replied from the shade: show my line under theirs, quietly, then let it go on open. */
    private fun appendMine(ctx: Context, fp: String, chat: String, text: String) {
        val k = key(fp, chat)
        val list = lines.getOrPut(k) { ArrayList() }
        list.add(Line("", text, System.currentTimeMillis(), "me", mine = true))
        while (list.size > MAX_LINES) list.removeAt(0)
        counts[k] = 0
        pending.remove(k)
        post(ctx, fp, chat, silent = true, alertAgain = false)
    }

    /** The chat was opened — its notification and count go away (even after a process restart). */
    fun clearChat(ctx: Context, fp: String, chat: String) {
        val k = key(fp, chat)
        lines.remove(k); counts.remove(k); pending.remove(k)
        cancel(ctx, k, ID_CHAT)
        cancel(ctx, fp, 1000 + (chat.hashCode() and 0xFFFF))   // one posted by 2.1, still in the shade after the update
    }

    /** A group went off the radio (switched, left or deleted): its notifications must not linger or merge. */
    fun clearGroup(ctx: Context, fp: String) = clearGroups(ctx, listOf(fp))

    /** [clearGroup] for several groups with one look at what is in the shade (every left group, at each start). */
    fun clearGroups(ctx: Context, fps: Collection<String>) {
        fun ofThese(tag: String) = fps.any { tag == it || tag.startsWith("$it|") }
        for (k in lines.keys.filter { ofThese(it) }) lines.remove(k)
        for (k in counts.keys.filter { ofThese(it) }) counts.remove(k)
        for (k in pending.keys.filter { ofThese(it) }) pending.remove(k)
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            for (sb in nm.activeNotifications) {
                val tag = sb.tag ?: continue
                if (ofThese(tag)) nm.cancel(tag, sb.id)
            }
        } catch (e: Exception) { }
    }

    private fun cancel(ctx: Context, tag: String, id: Int) {
        try { NotificationManagerCompat.from(ctx).cancel(tag, id) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ shared internet

    /** My request was answered (or couldn't be): tell me, and only me. */
    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun answer(ctx: Context, fp: String, e: Errand) {
        if (!canPost(ctx)) return
        val title = when {
            e.status == Errand.DONE -> ctx.getString(R.string.answer_ready, Errands.titleFor(e))
            e.status == Errand.EXPIRED -> ctx.getString(R.string.answer_expired, Errands.titleFor(e))
            e.isOpen && e.why == "quiet" -> ctx.getString(R.string.answer_quiet, e.helperName.ifEmpty { ctx.getString(R.string.someone) })
            else -> ctx.getString(R.string.answer_failed, Errands.titleFor(e))
        }
        val body = (if (e.title.isNotEmpty() && e.status == Errand.DONE) e.title + " · " else "") +
            (if (e.answeredBy.isNotEmpty()) ctx.getString(R.string.via_phone, e.answeredBy) else "")
        val intent = Intent(ctx, InternetActivity::class.java).setData(target(fp, "e", e.id, "open"))
            .putExtra(EXTRA_FP, fp).putExtra(EXTRA_ERRAND, e.id)
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText(body.ifEmpty { ctx.getString(R.string.internet_title) })
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, intent, errandId(e.id)))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(errandTag(fp, e.id), ID_ERRAND, n) } catch (x: Exception) { }
    }

    /**
     * Someone asked THIS phone (it has signal) to send a text or email for them. One notification
     * per request — they never overwrite each other — with "Send" (opens the request, then the
     * messaging app) and "Can't" (hands it to someone else).
     */
    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun sendRequest(ctx: Context, fp: String, e: Errand) {
        if (!canPost(ctx)) return
        val to = e.args.optString("name").ifEmpty { e.args.optString("to") }
        val text = e.args.optString("text")
        val title = if (Errand.isEmailTarget(e.args)) ctx.getString(R.string.errand_mail_title, e.fromName.ifEmpty { "Someone" }, to)
                    else ctx.getString(R.string.errand_send_title_to, e.fromName.ifEmpty { "Someone" }, to)
        val open = Intent(ctx, InternetActivity::class.java).setData(target(fp, "e", e.id, "open"))
            .putExtra(EXTRA_FP, fp).putExtra(EXTRA_ERRAND, e.id)
        val send = Intent(open).setData(target(fp, "e", e.id, "send"))
        val decline = Intent(ctx, ActionReceiver::class.java).setAction(ACTION_DECLINE).setPackage(ctx.packageName)
            .setData(target(fp, "e", e.id, "decline")).putExtra(EXTRA_FP, fp).putExtra(EXTRA_ERRAND, e.id)
        val id = errandId(e.id)
        val n = NotificationCompat.Builder(ctx, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFF2563EB.toInt())
            .setContentTitle(title)
            .setContentText("“$text”")
            .setStyle(NotificationCompat.BigTextStyle().bigText("“$text”\n\n" + ctx.getString(R.string.errand_send_hint)))
            .setOngoing(false)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open(ctx, open, id))
            .addAction(R.drawable.ic_send, ctx.getString(R.string.send), open(ctx, send, id + 1))
            .addAction(R.drawable.ic_close, ctx.getString(R.string.cant_send),
                PendingIntent.getBroadcast(ctx, id + 2, decline, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(errandTag(fp, e.id), ID_ERRAND, n) } catch (x: Exception) { }
    }

    fun cancelErrand(ctx: Context, fp: String, eid: String) {
        cancel(ctx, errandTag(fp, eid), ID_ERRAND)
        cancel(ctx, fp, errandId(eid))   // posted by an earlier version
    }

    // ------------------------------------------------------------------ Hopline's own update

    /**
     * "Hopline 2.3 is ready to install" — the one notification a version ever gets (service/Updater
     * sees to that). It offers and nothing more: a tap opens Home, where the banner has the
     * Install button; swiped away, it does not come back. [quiet]: Hopline is on screen, where
     * the banner already says it — no sound.
     */
    @SuppressLint("MissingPermission")  // guarded by canPost()
    fun updateReady(ctx: Context, version: String, summary: String, quiet: Boolean) {
        if (!canPost(ctx)) return
        val text = summary.ifEmpty { ctx.getString(R.string.update_notif_text) }
        // Back to the Home that is already there (under a chat, say), not a second one on top.
        val home = Intent(ctx, HomeActivity::class.java).setData(target(TAG_UPDATE))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val n = NotificationCompat.Builder(ctx, CH_UPDATES)
            .setSmallIcon(R.drawable.ic_notif)
            .setColor(0xFFD9481F.toInt())
            .setContentTitle(ctx.getString(R.string.update_notif_title, version))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(open(ctx, home, ID_UPDATE))
            .build()
        try { NotificationManagerCompat.from(ctx).notify(TAG_UPDATE, ID_UPDATE, n) } catch (e: Exception) { }
    }

    /** The version was installed, withdrawn, replaced by a newer one — or the person said "not now". */
    fun cancelUpdate(ctx: Context) = cancel(ctx, TAG_UPDATE, ID_UPDATE)

    private fun open(ctx: Context, intent: Intent, req: Int): PendingIntent =
        PendingIntent.getActivity(ctx, req, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private const val MAX_LINES = 7
    private const val COALESCE_MS = 400L

    /** Reply / Mark as read / Can't, straight from the notification shade. */
    class ActionReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val fp = intent.getStringExtra(EXTRA_FP) ?: return
            // A group this phone has left since the notification was posted: nothing can be sent
            // into it, and none of this may land on the group that is on the radio now (the chat
            // keys are the same in every group). Its chat is still there to read, so "read" counts.
            if (Core.store.findGroup(fp)?.left == true) {
                when (intent.action) {
                    ACTION_REPLY -> android.widget.Toast.makeText(ctx, R.string.notif_left_group, android.widget.Toast.LENGTH_LONG).show()
                    ACTION_READ -> intent.getStringExtra(EXTRA_PEER)?.let { Core.store.setLastRead(fp, it, System.currentTimeMillis()) }
                }
                clearGroup(ctx, fp)
                return
            }
            // A notification from a group that is no longer on the radio must not act on another one.
            if (Core.store.activeGroup()?.fingerprint != fp) {
                clearGroup(ctx, fp)
                android.widget.Toast.makeText(ctx, R.string.notif_other_group, android.widget.Toast.LENGTH_LONG).show()
                return
            }
            Core.ensureRunning()
            // The group on the radio — but its router may still be starting (its saved chat being
            // read, its key worked out, after the process was killed): what can wait, waits for it.
            val r = Core.router?.takeIf { it.group.fingerprint == fp }
            when (intent.action) {
                ACTION_REPLY -> {
                    val chat = intent.getStringExtra(EXTRA_PEER) ?: return
                    val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_REPLY)?.toString()?.trim().orEmpty()
                    if (text.isEmpty()) return
                    if (r == null) {
                        // Kept on disk and sent the moment the router is up (Core.sendKeptReplies).
                        if (!Core.store.keepReply(fp, chat, text, System.currentTimeMillis())) {
                            post(ctx, fp, chat, silent = true, alertAgain = false)
                            android.widget.Toast.makeText(ctx, R.string.starting_try_again, android.widget.Toast.LENGTH_LONG).show()
                            return
                        }
                        Core.store.setLastRead(fp, chat, System.currentTimeMillis())
                        appendMine(ctx, fp, chat, text)
                        return
                    }
                    val mine = if (chat == Core.GROUP) r.sendChat(text, mentions = app.hopline.ui.Ui.mentionsIn(r, text)) else r.sendDm(chat, text)
                    if (mine == null) {
                        // A private chat nothing can be sealed for (Router.canWriteTo): nothing went, so nothing
                        // shows as sent; the words wait in that chat (Core.keepAsDraft), the notification is
                        // put back as it was, and the chat's own line says why (the same one its screen shows).
                        Core.keepAsDraft(fp, chat, text)
                        app.hopline.ui.Ui.cantWriteLine(r, chat)?.let { android.widget.Toast.makeText(ctx, it, android.widget.Toast.LENGTH_LONG).show() }
                        post(ctx, fp, chat, silent = true, alertAgain = false)
                        return
                    }
                    // Read now, but keep their lines on screen with my reply under them, like
                    // every messaging app (markRead would wipe the conversation first).
                    Core.store.setLastRead(fp, chat, System.currentTimeMillis())
                    appendMine(ctx, fp, chat, text)
                    Core.saveNow()
                    Core.changed()
                }
                ACTION_READ -> {
                    val chat = intent.getStringExtra(EXTRA_PEER) ?: return
                    Core.markRead(chat)
                    Core.changed()
                }
                ACTION_DECLINE -> {
                    val eid = intent.getStringExtra(EXTRA_ERRAND) ?: return
                    // Handing a request on needs the group's router: the notification stays until it is up.
                    if (r == null) { android.widget.Toast.makeText(ctx, R.string.starting_try_again, android.widget.Toast.LENGTH_LONG).show(); return }
                    Core.finishSendErrand(eid, sent = false)
                }
            }
        }
    }
}
