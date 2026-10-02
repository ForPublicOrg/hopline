package app.hopline.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.hopline.R
import app.hopline.core.Crypto
import app.hopline.core.HelperLimits
import app.hopline.core.Names
import app.hopline.data.Store
import app.hopline.mesh.Attachment
import app.hopline.mesh.Errand
import app.hopline.mesh.Group
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.NearbyTransport
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.mesh.RouterListener
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * One per process. Owns the router + radio for the ACTIVE group, keeps them alive via MeshService,
 * and exposes a `version` LiveData that screens observe to redraw. Other groups sleep on disk and
 * wake instantly on switch. Everything here runs on the main thread except file byte-work and
 * the state writer.
 */
object Core {
    private const val TAG = "Hopline/Core"
    lateinit var app: Application
    lateinit var store: Store
    val handler = Handler(Looper.getMainLooper())

    var router: Router? = null; private set
    var transport: NearbyTransport? = null; private set

    /** Bumped whenever anything the UI shows may have changed. */
    val version = MutableLiveData(0)
    /** Which chat is on screen: null, GROUP, INTERNET or a node id. Used to skip notifications for what you're looking at. */
    var openChat: String? = null
    const val GROUP = "*"
    /** The shared-internet screen, as a "chat" for notification purposes. */
    const val INTERNET = "~net"
    var appVisible = false
    var radioProblem: String = ""

    private var changePosted = false
    private var netCallbackRegistered = false
    private val limits = HelperLimits()

    fun init(application: Application) {
        app = application
        store = Store(application)
        Notifications.createChannels(application)
        Cell.onChange = { refreshCaps() }
    }

    fun hasGroup(): Boolean = store.group() != null
    fun fingerprint(): String? = router?.group?.fingerprint ?: store.group()?.fingerprint

    /** Build the router for the active group (idempotent) and start the background service. */
    fun ensureRunning(): Boolean {
        val group = store.group() ?: return false
        if (!Permissions.allGranted(app)) return false
        if (router == null) build(group)
        try { ContextCompat.startForegroundService(app, Intent(app, MeshService::class.java)) }
        catch (e: Exception) { Log.w(TAG, "could not start service now", e); return false }
        return true
    }

    /** Called by the service once it is in the foreground. */
    fun startRadio() {
        val t = transport ?: return
        if (!t.running) { t.start(); Log.i(TAG, "radio started") }
        watchInternet()
        Cell.start(app)
        tick()
    }

    fun stopRadio() { transport?.stop(); flushSave() }

    private fun build(group: Group) {
        val me = store.identity()
        val t = NearbyTransport(app, group, me)
        val r = Router(me, group, t, listener, Blobs.chunkStore(app, group.fingerprint))
        store.loadState(group.fingerprint)?.let { try { r.restore(it) } catch (e: Exception) { Log.w(TAG, "state restore failed", e) } }
        r.shareInternet = store.shareInternet
        t.events = object : NearbyTransport.Events {
            override fun onLinkUp(linkId: String, nodeId: String, name: String, token: String) { if (transport === t) { r.onLinkUp(linkId, nodeId, name, token); changed() } }
            override fun onLinkDown(linkId: String) { if (transport === t) { r.onLinkDown(linkId); changed() } }
            override fun onBytes(linkId: String, bytes: ByteArray) { if (transport === t) { r.onBytes(linkId, bytes); scheduleErrandWake() } }
            override fun onPayloadSent(payloadId: Long) { if (transport === t) r.onPayloadSent(payloadId) }
            override fun onPayloadFailed(payloadId: Long) { if (transport === t) r.onPayloadFailed(payloadId) }
            override fun onStatus(text: String) { if (transport === t) { radioProblem = text; changed() } }
        }
        router = r; transport = t
        // The new group's unread count lives on the router from now on.
        store.setPausedUnread(group.fingerprint, 0)
        r.hasInternet = internetNow()
        r.setCaps(computeCaps())
        r.resumeErrands()
        scheduleErrandWake()
        finishInterruptedFiles(r, group.fingerprint)
    }

    /** After a restart: files whose last pieces arrived while we were dead get assembled now. */
    private fun finishInterruptedFiles(r: Router, fp: String) {
        val pending = r.messages.filter { it.att != null }
        if (pending.isEmpty()) return
        Thread {
            for (m in pending) {
                try {
                    val att = m.att ?: continue
                    if (Blobs.fileFor(app, fp, att).exists() || Blobs.assemble(app, fp, r, m)) {
                        handler.post { if (router === r) r.markFileReady(att.fid) }
                    }
                } catch (e: Throwable) { Log.w(TAG, "could not finish a file", e) }
            }
            handler.post { changed() }
        }.start()
    }

    /** Point the radio at another saved group. Nothing is deleted; the old group sleeps on disk. */
    fun switchGroup(code: String) {
        if (router?.group?.code == code) return
        stopLiveLocation()   // a position shared with one group must not leak into another
        retire()
        store.setActive(code)
        radioProblem = ""
        ensureRunning()
        changed()
    }

    /** Leave the active group for good: its messages, files and read marks are deleted. */
    fun leaveActiveGroup() {
        val leaving = store.activeGroup() ?: return
        stopLiveLocation()
        transport?.stop()
        router?.let { r -> for (e in r.errands.values) stopFetch(e.id) }
        router = null; transport = null
        drainWrites()   // a queued save must not resurrect the deleted state file
        Notifications.clearGroup(app, leaving.fingerprint)
        Blobs.deleteGroup(app, leaving.fingerprint)
        store.removeGroup(leaving.code)
        if (store.group() != null) ensureRunning()
        else app.stopService(Intent(app, MeshService::class.java))
        changed()
    }

    /** Remove a paused group (not the one on the radio) without switching to it first. */
    fun removeSavedGroup(code: String) {
        val g = store.groups().firstOrNull { it.code == code } ?: return
        if (g.code == store.activeCode) return
        drainWrites()
        Notifications.clearGroup(app, g.fingerprint)
        Blobs.deleteGroup(app, g.fingerprint)
        store.removeGroup(g.code)
        changed()
    }

    /** Take the current group off the radio, saving everything and remembering its unread count. */
    private fun retire() {
        val r = router
        if (r != null) {
            store.setPausedUnread(r.group.fingerprint, unreadCounts().values.sum())
            for (e in r.errands.values) stopFetch(e.id)
            saveNow()
            Notifications.clearGroup(app, r.group.fingerprint)
        }
        transport?.stop()
        router = null; transport = null
    }

    // ------------------------------------------------------------------ names

    /** My display name, everywhere: stored, on every envelope from now on, and in my next beacon. */
    fun setMyName(name: String): Boolean {
        val clean = Names.clean(name)
        if (clean.length < 2) return false
        store.name = clean
        router?.rename(clean)
        transport?.renamed()
        changed()
        return true
    }

    /** Rename the group for everyone in it. */
    fun renameGroup(name: String): Boolean {
        val r = router ?: return false
        val m = r.renameGroup(name) ?: return false
        saveSoon()
        return m.text.isNotEmpty()
    }

    // ------------------------------------------------------------------ live location

    /** Until when I share my position (0 = not sharing). It rides presence beacons. */
    var liveLocationUntil = 0L; private set
    private var liveWatchStop: (() -> Unit)? = null

    fun liveLocationActive(): Boolean = System.currentTimeMillis() < liveLocationUntil

    fun liveLocationLeftMs(): Long = (liveLocationUntil - System.currentTimeMillis()).coerceAtLeast(0)

    /**
     * Share my position with the group for a while. One GPS listener runs at a lazy interval;
     * each presence beacon carries the freshest fix, so the update rate scales down with the
     * crowd exactly like presence itself does. The service is promoted to a location service
     * meanwhile, so fixes keep coming with the screen locked.
     */
    fun startLiveLocation(minutes: Int) {
        val r = router ?: return
        liveLocationUntil = System.currentTimeMillis() + minutes * 60_000L
        if (liveWatchStop == null) liveWatchStop = Locations.watch(app, minTimeMs = 10_000L) { pushMyLocation() }
        MeshService.updateLocationType(app, true)
        pushMyLocation()
        r.sendPresence()   // don't make the group wait a beacon interval to learn
        changed()
    }

    fun stopLiveLocation() {
        liveWatchStop?.invoke(); liveWatchStop = null
        val wasSharing = liveLocationUntil != 0L
        liveLocationUntil = 0
        router?.let { r ->
            r.myLoc = null
            if (wasSharing) r.sendPresence()   // an empty beacon clears my pin on every phone
        }
        if (wasSharing) { MeshService.updateLocationType(app, false); changed() }
    }

    /** Move the freshest fix into the router, where presence picks it up. */
    private fun pushMyLocation() {
        val r = router ?: return
        if (!liveLocationActive()) { if (liveWatchStop != null || liveLocationUntil != 0L) stopLiveLocation(); return }
        val l = Locations.lastKnown(app)
        // A phone that stopped getting fixes (indoors, GPS off) must not keep beaconing its
        // last position as "live" — beaconing nothing is honest, a stale ghost is a lie.
        r.myLoc = if (l != null && System.currentTimeMillis() - l.time <= 10 * 60_000)
            Loc.of(l.latitude, l.longitude, l.accuracy.toInt()) else null
    }

    // ------------------------------------------------------------------ sending files

    /**
     * Shrink and send a photo. Byte-work runs off the main thread; `done` is called on the main
     * thread with null on success or a problem description. [target] is captured by the caller
     * when the user chose the photo, so a chat switch in between can't redirect it.
     */
    fun sendImage(uri: Uri, caption: String, to: String?, quote: Quote? = null, mentions: List<String> = emptyList(),
                  cleanup: (() -> Unit)? = null, done: (String?) -> Unit) {
        val r0 = router ?: run { cleanup?.invoke(); return done("Hopline is starting — try again in a moment.") }
        val fp = r0.group.fingerprint
        Thread {
            val prep = try { Blobs.prepareImage(app, uri) } catch (e: Throwable) { null }
            handler.post {
                cleanup?.invoke()
                // The user may have switched groups while we were shrinking the photo — a photo
                // meant for one group must never be flooded into another.
                if (router !== r0) { done("Group changed — photo not sent."); return@post }
                if (prep == null) { done("Couldn't read that photo."); return@post }
                val pieces = Blobs.chunkify(prep.bytes)
                val att = Attachment.make(Crypto.randomId(12), prep.name, prep.mime,
                    prep.bytes.size.toLong(), pieces.size, prep.width, prep.height, prep.thumbB64)
                Blobs.saveOwn(app, fp, att, prep.bytes)
                r0.sendFile(att, pieces, caption, to, quote, mentions)
                saveSoon()
                changed()
                done(null)
            }
        }.start()
    }

    /** Send a picked document (or a recorded voice note) as-is. Same threading contract as sendImage. */
    fun sendFileBytes(picked: Blobs.PickedFile, caption: String, to: String?, durSec: Int = 0, quote: Quote? = null,
                      mentions: List<String> = emptyList(), done: (String?) -> Unit) {
        val r0 = router ?: return done("Hopline is starting — try again in a moment.")
        if (picked.bytes.isEmpty()) return done("That file is empty.")
        val fp = r0.group.fingerprint
        Thread {
            val pieces = Blobs.chunkify(picked.bytes)
            handler.post {
                if (router !== r0) { done("Group changed — file not sent."); return@post }
                val att = Attachment.make(Crypto.randomId(12), picked.name, picked.mime,
                    picked.bytes.size.toLong(), pieces.size, 0, 0, "", durSec)
                Blobs.saveOwn(app, fp, att, picked.bytes)
                r0.sendFile(att, pieces, caption, to, quote, mentions)
                saveSoon()
                changed()
                done(null)
            }
        }.start()
    }

    /**
     * "Send again" for a photo, file or voice note that never got through: the very same bytes,
     * picture size, preview and length, as a new message — a photo isn't shrunk a second time.
     * The old copy goes once the new one is on its way.
     */
    fun resendFile(old: Message, done: (String?) -> Unit) {
        val r0 = router ?: return done("Hopline is starting — try again in a moment.")
        val a = old.att ?: return done("That file isn't on this phone any more.")
        val fp = r0.group.fingerprint
        val src = Blobs.fileFor(app, fp, a)
        Thread {
            val bytes = try { src.readBytes() } catch (e: Exception) { null }
            val pieces = if (bytes != null && bytes.isNotEmpty()) Blobs.chunkify(bytes) else null
            handler.post {
                if (router !== r0) { done("Group changed — not sent."); return@post }
                if (bytes == null || pieces == null) { done("That file isn't on this phone any more."); return@post }
                val att = Attachment.make(Crypto.randomId(12), a.name, a.mime, bytes.size.toLong(), pieces.size, a.width, a.height, a.thumb, a.dur)
                Blobs.saveOwn(app, fp, att, bytes)
                r0.sendFile(att, pieces, old.text, old.to, old.quote, old.mentions)
                deleteMessages(listOf(old.id))
                done(null)
            }
        }.start()
    }

    // ------------------------------------------------------------------ delete for me

    /** Remove messages from this phone for good (and their received files). */
    fun deleteMessages(ids: Collection<String>) {
        val r = router ?: return
        val fp = r.group.fingerprint
        val gone = r.hideMessages(ids)
        val files = gone.mapNotNull { m -> m.att?.let { Blobs.fileFor(app, fp, it) } }
        if (files.isNotEmpty()) Thread { files.forEach { try { it.delete() } catch (e: Exception) { } } }.start()
        saveSoon()
        changed()
    }

    /** Empty one chat on this phone: the group chat (peer = null) or a private chat. */
    fun clearChat(peer: String?) {
        val r = router ?: return
        deleteMessages(r.chatMessages(peer).map { it.id })
        Notifications.clearChat(app, r.group.fingerprint, peer ?: GROUP)
    }

    // ------------------------------------------------------------------ unread & mute

    fun markRead(chat: String) {
        val fp = fingerprint() ?: return
        store.setLastRead(fp, chat, System.currentTimeMillis())
        Notifications.clearChat(app, fp, chat)
    }

    /** Unread counts compare LOCAL arrival times — sender clocks drift, and carried messages
     *  can be hours old by their own clock while still brand new to this phone. */
    fun unreadCount(chat: String): Int = unreadCounts()[chat] ?: 0

    /** All chats' unread counts in one pass over the message list. */
    fun unreadCounts(): Map<String, Int> {
        val r = router ?: return emptyMap()
        val fp = r.group.fingerprint
        val counts = HashMap<String, Int>()
        val since = HashMap<String, Long>()
        for (m in r.messages) {
            if (m.from == r.me.id || m.isNotice) continue
            val chat = if (m.isGroup) GROUP else if (m.to == r.me.id) m.from else continue
            val limit = since.getOrPut(chat) { store.lastRead(fp, chat) }
            if (m.arrivedAt > limit) counts[chat] = (counts[chat] ?: 0) + 1
        }
        return counts
    }

    fun isMuted(chat: String): Boolean = fingerprint()?.let { store.isMuted(it, chat) } ?: false

    fun setMuted(chat: String, until: Long) {
        val fp = fingerprint() ?: return
        store.setMuted(fp, chat, until)
        if (until > System.currentTimeMillis()) Notifications.clearChat(app, fp, chat)
        changed()
    }

    // ------------------------------------------------------------------ shared internet

    /** One switch for every group: "Share my internet when I have signal". */
    fun setShareInternet(on: Boolean) {
        store.shareInternet = on
        router?.shareInternet = on
        refreshCaps()
        saveSoon()
        changed()
    }

    /** What this phone can do for the group right now. Zero when sharing is off or paused. */
    fun computeCaps(): Int {
        if (!store.shareInternet) return 0
        if (helpPausedReason() != null) return 0
        // The data allowance limits data help only: texts home cost no data and carry on.
        var c = 0
        if (internetNow() && budgetLeft() >= MIN_BUDGET_BYTES) {
            c = c or Errand.CAP_READ or Errand.CAP_FIND or Errand.CAP_WX
            if (hasMailApp()) c = c or Errand.CAP_MAIL
        }
        if (Cell.canText(app)) c = c or Errand.CAP_SMS
        return c
    }

    /** A phone with no email app can't send email for anyone, so it doesn't offer to. Caps are
     *  worked out on every tick: the package lookup runs at most once a minute. (The manifest's
     *  SENDTO mailto <queries> entry lets it see mail apps on Android 11+.) */
    @Volatile private var mailAppCheckedAt = 0L
    @Volatile private var mailApp = false
    private fun hasMailApp(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (mailAppCheckedAt == 0L || now - mailAppCheckedAt > 60_000L) {
            mailApp = try { Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).resolveActivity(app.packageManager) != null } catch (e: Exception) { false }
            mailAppCheckedAt = now
        }
        return mailApp
    }

    /** Why this phone isn't helping even though sharing is on, in plain words; null if it is. */
    fun helpPausedReason(): String? {
        if (!store.shareInternet) return null
        if (store.shareWhileRoaming.not() && roamingNow()) return "Paused while roaming"
        if (batteryPercent() in 0..14 && !charging()) return "Paused — battery below 15%"
        if (internetNow() && budgetLeft() < MIN_BUDGET_BYTES && !Cell.canText(app)) return "Paused — today's sharing allowance is used up"
        return null
    }

    /** What I can do for MY OWN requests: my signal, my data — no sharing switch or allowance. */
    private fun ownCaps(): Int {
        var c = 0
        if (internetNow()) c = c or Errand.CAP_READ or Errand.CAP_FIND or Errand.CAP_WX
        return c
    }

    fun refreshCaps() {
        val r = router ?: return
        r.hasInternet = internetNow()
        r.setCaps(computeCaps())
        scheduleErrandWake()
        changed()
    }

    fun budgetLeft(): Long {
        val budget = store.shareBudgetBytes
        if (budget <= 0) return Long.MAX_VALUE
        return (budget - store.shareUsedToday()).coerceAtLeast(0)
    }

    /**
     * Ask the group's internet for something. When this phone has signal it simply runs here; when
     * it doesn't, it travels with the group until a phone that does picks it up.
     */
    /** Why a new request can't be made right now, in plain words; null when it can. */
    fun requestProblem(): String? {
        val r = router ?: return "Hopline is starting — try again in a moment."
        if (r.openRequestCount() >= Router.MAX_OPEN_REQUESTS) return "You have ${Router.MAX_OPEN_REQUESTS} requests waiting already — cancel one or wait for an answer first."
        return null
    }

    fun requestErrand(type: String, args: JSONObject, prefer: String? = null): Errand? {
        val r = router ?: return null
        if (requestProblem() != null) return null
        if (args.toString().length > Router.MAX_ARGS / 2) return null
        val selfCaps = ownCaps()
        val need = Errand.capFor(type, args)
        // My own signal runs my own request even with sharing off — it's my data.
        val e = r.requestErrand(type, args, selfCaps = if (need != 0 && selfCaps and need != 0) selfCaps else 0, prefer = prefer)
        saveSoon()
        scheduleErrandWake()
        changed()
        return e
    }

    /**
     * A request I made while offline, and now my phone has signal: run it here instead of waiting
     * for a friend to spend their data on it. [force] = the person tapped for it.
     */
    fun runOwnNow(eid: String, force: Boolean = true): Boolean {
        val r = router ?: return false
        if (!r.runOwnNow(eid, ownCaps(), force)) return false
        saveSoon()
        scheduleErrandWake()
        changed()
        return true
    }

    /** Take one of my finished requests off the list. */
    fun forgetRequest(eid: String): Boolean {
        val r = router ?: return false
        if (!r.forgetErrand(eid)) return false
        Notifications.cancelErrand(app, r.group.fingerprint, eid)
        saveSoon()
        changed()
        return true
    }

    /** Signal came back: my requests nobody could do yet run on it, the way they would have if I'd had it when asking. */
    private fun runOwnWaiting(r: Router) {
        if (ownCaps() == 0) return
        for (e in r.errands.values.toList()) if (e.from == r.me.id && e.status == Errand.WAITING) runOwnNow(e.id, force = false)
    }

    /** The helper's person tapped "Sent" (or "Couldn't send") for a text/email someone asked for. */
    fun finishSendErrand(eid: String, sent: Boolean) {
        val r = router ?: return
        val e = r.errands[eid] ?: return
        Notifications.cancelErrand(app, r.group.fingerprint, eid)
        limits.finished(isText = true)
        if (sent) {
            val to = e.args.optString("name").ifEmpty { e.args.optString("to") }
            val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
            val text = "Sent from ${r.me.name}'s phone at $time."
            if (r.completeErrand(eid, true, (if (Errand.isEmailTarget(e.args)) "Email sent to " else "Text sent to ") + to, JSONObject().put("t", text))) {
                store.addHelpLog(e.fromName, Errands.titleFor(e), 0, true)
            }
        } else r.declineErrand(eid, "not_sent")
        saveSoon()
        changed()
    }

    /** What the Home screen's "Shared internet" row says, in one line, plus its badges. */
    class InternetSummary(val line: String, val waitingForMe: Int, val unreadAnswers: Int)

    fun internetSummary(): InternetSummary {
        val r = router ?: return InternetSummary("", 0, 0)
        val fp = r.group.fingerprint
        val seen = store.lastRead(fp, INTERNET)
        val unread = r.errands.values.count { it.from == r.me.id && it.answeredAt > seen }
        val waiting = pendingSends().size
        val mine = r.errands.values.count { it.from == r.me.id && it.isOpen }
        val helpers = r.helpers()
        val line = when {
            waiting > 0 -> app.resources.getQuantityString(R.plurals.summary_waiting_for_you, waiting, waiting)
            r.myCaps != 0 -> app.getString(R.string.summary_you_help)
            helpers.isNotEmpty() -> app.getString(R.string.summary_someone_helps, helpers[0].name.ifEmpty { "Someone" })
            mine > 0 -> app.resources.getQuantityString(R.plurals.summary_requests_travel, mine, mine)
            else -> app.getString(R.string.summary_nobody)
        }
        return InternetSummary(line, waiting, unread)
    }

    /** Requests waiting for a tap from the person holding this phone (texts and emails). */
    fun pendingSends(): List<Errand> {
        val r = router ?: return emptyList()
        return r.errands.values.filter { it.type == Errand.SEND && it.from != r.me.id && r.isRunning(it.id) }
    }

    /** Other people's fetches holding one of [limits]' slots — each freed exactly once, however its job ends. */
    private val helping = HashSet<String>()

    private fun release(eid: String, spent: Int = 0) {
        if (!helping.remove(eid)) return
        limits.finished(isText = false)
        if (spent > 0) store.addShareUsage(spent.toLong())
    }

    /** Stop a fetch: its slot comes back, and what it already downloaded still counts against the allowance. */
    private fun stopFetch(eid: String) = release(eid, Errands.cancel(eid))

    /** The reason this phone can't take [e] right now, or null. Checked when the work starts, not
     *  only when a claim was scheduled: the switch, roaming or the battery may have changed since,
     *  and a request can be aimed at this phone by name. */
    private fun cantHelp(e: Errand): String? {
        val need = Errand.capFor(e.type, e.args)
        return when {
            !store.shareInternet -> "off"
            need == 0 -> "unsupported"
            computeCaps() and need != 0 -> null
            helpPausedReason() != null -> "paused"
            e.type == Errand.SEND && !Errand.isEmailTarget(e.args) -> "no_service"
            else -> "no_signal"
        }
    }

    private fun runErrand(r: Router, e: Errand) {
        val fp = r.group.fingerprint
        if (e.type == Errand.SEND) {
            if (e.from == r.me.id) { r.declineErrand(e.id, "own"); return }
            // Its person already opened Messages for it: only they decide now — never re-checked
            // and handed on by a restart (the family could get it twice).
            if (r.sendWasOpened(e.id)) { Notifications.sendRequest(app, fp, e); changed(); return }
            val why = cantHelp(e) ?: limits.check(e.from, isText = true)
            if (why != null) { r.declineErrand(e.id, why); return }
            limits.started(e.from, isText = true)
            Notifications.sendRequest(app, fp, e)
            changed()
            return
        }
        val mine = e.from == r.me.id
        if (!mine) {
            if (e.id !in helping) {
                val why = cantHelp(e) ?: limits.check(e.from, isText = false)
                if (why != null) { r.declineErrand(e.id, why); return }
                limits.started(e.from, isText = false)
                helping.add(e.id)
            }
        }
        val crowd = r.activePeople() >= Router.FILE_GROUP_LIMIT
        val maxWire = if (mine) 600_000 else budgetLeft().coerceAtMost(400_000L).toInt().coerceAtLeast(20_000)
        Errands.run(app, e, partChars = if (crowd) 3_500 else 12_000, maxWire = maxWire) { out ->
            release(e.id)   // billed below, with the answer
            // The fetch can outlive a group switch: an answer for group A must never be signed
            // with group B's key or posted into B — and it must still reach A's asker later.
            val target = if (router === r) r else null
            val cost = when (out) { is Errands.Outcome.Ok -> out.cost; is Errands.Outcome.Fail -> out.cost; is Errands.Outcome.Retry -> out.cost }
            if (!mine && cost > 0) store.addShareUsage(cost.toLong())
            when (out) {
                is Errands.Outcome.Ok -> {
                    val sent = r.completeErrand(e.id, true, out.title, out.body, out.cost)
                    if (sent && !mine) store.addHelpLog(e.fromName, out.title, out.cost, true)
                }
                is Errands.Outcome.Fail -> {
                    val sent = r.completeErrand(e.id, false, out.title, JSONObject().put("t", out.text), out.cost, out.why)
                    if (sent && !mine) store.addHelpLog(e.fromName, out.title, out.cost, false)
                }
                is Errands.Outcome.Retry -> r.declineErrand(e.id, out.why)
            }
            if (target == null) {
                val fp0 = r.group.fingerprint
                val snap = r.snapshot()
                if (store.groups().any { it.fingerprint == fp0 }) writer.execute { store.saveState(fp0, snap) }
            } else { refreshCaps(); saveSoon() }
            changed()
        }
        changed()
    }

    private val errandWake = Runnable { router?.pollErrands(); scheduleErrandWake(); changed() }
    private var errandWakeAt = 0L

    /** Claims and heartbeats run on a seconds scale, between the 30 s ticks. */
    private fun scheduleErrandWake() {
        val r = router ?: return
        val at = r.nextErrandWake()
        if (at == Long.MAX_VALUE) { handler.removeCallbacks(errandWake); errandWakeAt = 0; return }
        if (errandWakeAt in 1..at && errandWakeAt > System.currentTimeMillis()) return
        handler.removeCallbacks(errandWake)
        val delay = (at - System.currentTimeMillis()).coerceIn(200L, 60_000L)
        errandWakeAt = System.currentTimeMillis() + delay
        handler.postAtTime(errandWake, SystemClock.uptimeMillis() + delay)
    }

    // ------------------------------------------------------------------ periodic

    private var lastConnectedAt = 0L
    private var lastPeriodicSave = 0L

    fun tick() {
        val r = router ?: return
        r.battery = batteryPercent()
        r.hasInternet = internetNow()
        r.setCaps(computeCaps())
        if (r.hasInternet) runOwnWaiting(r)
        if (liveLocationUntil != 0L) pushMyLocation()   // stops itself once the time is up
        r.tick()
        scheduleErrandWake()
        // Watchdog: phones visible, nothing linked for 4 minutes → bounce the Bluetooth stack.
        // Measured from the last moment we HAD a link (or the radio started), never across a
        // handshake that is still in progress.
        transport?.let { t ->
            val now = System.currentTimeMillis()
            if (t.connectedCount() > 0) lastConnectedAt = now
            if (t.running && t.connectedCount() == 0 && t.connectingCount() == 0 && t.visibleCount() > 0 &&
                now - maxOf(lastConnectedAt, t.startedAt) > 240_000) {
                lastConnectedAt = now; t.restart()
            }
        }
        if (System.currentTimeMillis() - lastPeriodicSave > 120_000) saveSoon()
    }

    fun changed() {
        if (!changePosted) {
            changePosted = true
            handler.post { changePosted = false; version.value = (version.value ?: 0) + 1 }
        }
        if (router?.takeDirty() == true) saveSoon()
    }

    // ------------------------------------------------------------------ saving

    /** One writer thread, newest snapshot wins: saves never block the UI, never interleave. */
    private val writer = Executors.newSingleThreadExecutor()
    private val pendingWrites = HashMap<String, JSONObject>()
    private var savePosted = false

    private val saveRunnable = Runnable { savePosted = false; saveNow() }

    private fun saveSoon() {
        if (savePosted) return
        savePosted = true
        handler.postDelayed(saveRunnable, 2500)
    }

    /** Snapshot on the main thread (the router is single-threaded), write in the background. */
    fun saveNow() {
        val r = router ?: return
        lastPeriodicSave = System.currentTimeMillis()
        r.takeDirty()
        val fp = r.group.fingerprint
        val snap = r.snapshot()
        synchronized(pendingWrites) {
            val queued = pendingWrites.containsKey(fp)
            pendingWrites[fp] = snap
            if (queued) return
        }
        writer.execute {
            val j = synchronized(pendingWrites) { pendingWrites.remove(fp) } ?: return@execute
            store.saveState(fp, j)
        }
    }

    /** Save right away — the app is going to the background, or may be killed. */
    fun flushSave() { handler.removeCallbacks(saveRunnable); savePosted = false; saveNow() }

    /** Wait until every queued write is on disk (before deleting a group's files). */
    private fun drainWrites() {
        try { writer.submit {}.get(5, java.util.concurrent.TimeUnit.SECONDS) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ router events

    private val listener = object : RouterListener {
        override fun onChanged() { changed() }

        override fun onMessage(m: Message) {
            changed()
            if (m.isNotice) return
            val chat = if (m.to != null) m.from else GROUP
            if (appVisible && openChat == chat) { markRead(chat); return }
            val fp = fingerprint() ?: return
            Notifications.message(app, fp, m)
        }

        override fun onReaction(m: Message, by: String, emoji: String) {
            val r = router ?: return
            val chat = if (m.isGroup) GROUP else (if (m.from == r.me.id) m.to ?: return else m.from)
            if (emoji.isEmpty()) { Notifications.retractReaction(app, r.group.fingerprint, chat, m.id, by); return }
            if (appVisible && openChat == chat) return
            Notifications.reaction(app, r.group.fingerprint, chat, m, by, emoji)
        }

        override fun onFileReady(m: Message) {
            val r = router ?: return
            val fp = r.group.fingerprint
            Thread {
                val ok = try { Blobs.assemble(app, fp, r, m) } catch (e: Throwable) { false }
                handler.post {
                    // A failed assemble (I/O, storage full) must not stay latched: un-mark so the
                    // next chunk arrival or restart retries.
                    if (!ok && router === r) m.att?.let { r.unmarkFileReady(it.fid) }
                    changed()
                }
            }.start()
        }

        override fun onErrandRequest(e: Errand) {
            val r = router ?: return
            runErrand(r, e)
        }

        override fun onErrandAbort(e: Errand) {
            stopFetch(e.id)
            val fp = fingerprint() ?: return
            if (e.type == Errand.SEND) { Notifications.cancelErrand(app, fp, e.id); limits.finished(isText = true) }
            changed()
        }

        override fun onErrandAnswer(e: Errand) {
            changed()
            val fp = fingerprint() ?: return
            if (appVisible && openChat == INTERNET) return
            Notifications.answer(app, fp, e)
        }

        override fun onGroupNamed(name: String, at: Long) {
            store.activeGroup()?.let { store.renameGroup(it.code, name, at) }
            changed()
        }

        override fun onLog(text: String) { Log.d(TAG, text) }
    }

    // ------------------------------------------------------------------ device state

    fun bluetoothOn(): Boolean = try {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        adapter != null && (adapter.isEnabled || adapter.state == BluetoothAdapter.STATE_ON)
    } catch (e: Exception) { Log.w(TAG, "bluetooth check", e); false }
    fun wifiOn(): Boolean = try { (app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (e: Exception) { false }

    fun internetNow(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun roamingNow(): Boolean = try {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> false
            Build.VERSION.SDK_INT >= 28 -> !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)
            // Android 8 never sets NOT_ROAMING: asking it would call every mobile connection "roaming".
            else -> (app.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager)?.isNetworkRoaming == true
        }
    } catch (e: Exception) { false }

    private fun watchInternet() {
        if (netCallbackRegistered) return
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { handler.post { refreshInternet() } }
                    override fun onLost(network: Network) { handler.post { refreshInternet() } }
                    override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { handler.post { refreshInternet() } }
                })
            netCallbackRegistered = true
        } catch (e: Exception) { Log.w(TAG, "net callback", e) }
    }

    private fun refreshInternet() {
        val r = router ?: return
        val now = internetNow()
        val caps = computeCaps()
        if (now != r.hasInternet || caps != r.myCaps) { r.hasInternet = now; r.setCaps(caps); scheduleErrandWake(); changed() }
        if (now) runOwnWaiting(r)
    }

    private fun batteryPercent(): Int = try {
        (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { -1 }

    private fun charging(): Boolean = try {
        if (Build.VERSION.SDK_INT >= 23) (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging
        else {
            val i = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        }
    } catch (e: Exception) { false }

    /** One plain-English line for the top of the screen. Links first: radio advice only when nothing is linked. */
    fun statusLine(): String {
        val r = router ?: return "Starting…"
        val links = r.authedLinks().size
        val inRange = r.peopleInRange()
        if (links == 0 && inRange == 0) {
            if (!bluetoothOn()) return "Turn on Bluetooth to find your group"
            if (radioProblem.isNotEmpty()) return radioProblem
            if (transport?.otherGroupNearby() == true) return "Hopline phones nearby are in another group — check you all typed the same code"
            if (!wifiOn()) return "Looking for your group's phones… (WiFi helps)"
            return "Looking for your group's phones…"
        }
        return when {
            inRange <= 1 -> "1 person in range"
            else -> "$inRange people in range"
        }
    }

    const val MIN_BUDGET_BYTES = 60_000L
    val isTiramisu get() = Build.VERSION.SDK_INT >= 33
}
