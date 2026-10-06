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
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.hopline.R
import app.hopline.core.Crypto
import app.hopline.core.GroupKeys
import app.hopline.core.HelperLimits
import app.hopline.core.Names
import app.hopline.core.Words
import app.hopline.data.History
import app.hopline.data.SavedGroup
import app.hopline.data.StateRules
import app.hopline.data.Store
import app.hopline.mesh.Archive
import app.hopline.mesh.Attachment
import app.hopline.mesh.Errand
import app.hopline.mesh.Group
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.NearbyTransport
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.mesh.RouterListener
import app.hopline.mesh.Transport
import app.hopline.ui.ChatDrafts
import app.hopline.ui.Ui
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * One per process. Owns the router + radio for the ACTIVE group, keeps them alive via MeshService,
 * and exposes a `version` LiveData that screens observe to redraw. The other groups this phone is
 * in sleep on disk and wake instantly on switch; the groups it left stay on disk too, to be read
 * ([archive]) until the person deletes them.
 *
 * Threads:
 *  - Everything here runs on the main thread, and so does every router.
 *  - A group's state file and its history are read and written on ONE writer thread, in the order
 *    asked ([queueWrite], [loadStateOrdered]). Reading a state file while a save of it is under
 *    way throws that save away, so nothing but the writer may touch one: the main thread may wait
 *    for the writer for a moment, never go round it.
 *  - Slow file work — putting a file together, deleting a folder of pieces — runs on Blobs'
 *    housekeeping thread, never on the writer, because the main thread may be waiting for that.
 *
 * Leaving, rejoining and deleting a group are each ONE committed change to the saved group list,
 * followed by tidying that can simply be done again: a phone killed half-way finds what is left
 * to do at its next start ([reconcile]). And a group's state file never has two routers.
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
    const val GROUP = Message.GROUP_CHAT
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
        reconcile()
        backfillKeys()
        Updater.init(application)
    }

    /**
     * Is there a group for the radio? Groups this phone left don't count: they are only read. A
     * group whose key is still being worked out does: it is starting, not missing.
     */
    fun hasGroup(): Boolean = store.hasActive()
    fun fingerprint(): String? = router?.group?.fingerprint ?: store.activeGroup()?.fingerprint

    /**
     * The group on the radio has no router yet only because its build is waiting: for the writer
     * to hand over its saved state, or for its key to be worked out. It will start by itself; the
     * service stays up meanwhile (see [prepare]).
     */
    val buildPending: Boolean
        get() = buildWaiting || store.activeGroup()?.let { it.masterKey() == null && it.code in keyWaits } == true

    /**
     * Build the router for the active group (idempotent) and start the background service. With
     * no active group — every group left, or none joined yet — nothing is built and nothing starts.
     * True too while the build is waiting ([buildPending]): the service comes up and the radio
     * starts through it when the router is there. False only when nothing can start: no group, no
     * permission, or a build that failed for a reason waiting won't cure.
     */
    fun ensureRunning(): Boolean {
        if (!prepare()) return false
        try { ContextCompat.startForegroundService(app, Intent(app, MeshService::class.java)) }
        catch (e: Exception) { Log.w(TAG, "could not start service now", e); return false }
        return true
    }

    /**
     * [ensureRunning] without starting the service — what the service itself asks. True when the
     * active group's router is there, or its build is waiting ([buildPending]).
     */
    fun prepare(): Boolean {
        if (!store.hasActive() || !Permissions.allGranted(app)) return false
        if (router == null) build()
        return router != null || buildPending
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

    /**
     * Wake the active group: its router, restored from its saved state, and its radio (not started
     * here). False — and nothing is built — when the saved state can't be had right now: a router
     * that started without it would save an empty chat over the real one a few seconds later.
     * False too while the group's key is still being worked out: the build then runs by itself
     * the moment it is there ([keyLanded]).
     */
    private fun build(): Boolean {
        if (buildWaiting) return false   // already queued behind a busy writer: no second wait
        val saved = store.activeGroup() ?: return false
        // The key first: worked out once per group, on the keys thread — seconds, never here.
        val group = store.groupOf(saved) ?: run { needKey(saved.code); return false }
        val me = store.identity() ?: run { Log.w(TAG, "this phone's key pair can't be read right now; not starting"); return false }
        val fp = group.fingerprint
        // The state first, and in its turn on the writer: a save of this very group may still be
        // waiting there (a quick switch away and back), and it must land before this reads.
        val read = loadStateOrdered(fp)
        if (!read.usable) {
            Log.w(TAG, "the group's saved state can't be read yet; not starting it")
            if (read.busy) buildWhenWriterFree()
            return false
        }
        val t = NearbyTransport(app, group, me)
        val chunks = Blobs.chunkStore(app, fp)
        var restored = Router(me, group, t, listener, chunks)
        if (read.state != null) {
            var failed = 0
            while (true) try { restored.restore(read.state); break } catch (e: Throwable) {
                // That router now holds half a chat, and its first save would write the half over the
                // whole: it is dropped. Memory running out half way is not the file's fault and gets
                // one more try; otherwise the file is set aside for a second look — for good, so
                // the person is told ([Store.takeAsideNotes]) — and the group starts clean.
                Log.w(TAG, "state restore failed", e)
                restored = Router(me, group, t, listener, chunks)
                if (StateRules.onRestoreFailure(e, failed++) == StateRules.Failed.RETRY) continue
                if (!moveStateAsideOrdered(fp)) return false
                break
            }
        }
        val r = restored
        // Live counters above any this phone sent in any group, this one's earlier life included.
        r.liveQAbove(store.liveQMark())
        // Mine and known by what this router knows — from the first piece on, a reissued file's included.
        chunks.servedBy(r)
        // Right after an upgrade: what this phone had not got off it yet in the old format goes out
        // again in the new one — and the state that says so is on disk before the radio starts, or
        // the group doesn't start (a kill in between would send the same messages twice, under two ids).
        val files = r.reissueQueued()
        val copies = if (files.isEmpty()) null else reissueFiles(r, fp, files)
        if (read.upgraded || copies != null) {
            val saved = savedBeforeStart(fp, r)
            copies?.let { settleCopies(fp, it) }
            if (!saved) return false
        }
        // Back in a group this phone had left: "You rejoined", once, under the "You left" in its chat.
        Archive.rejoined(r)
        // The group hears that this phone is in it, if it is owed a hello: a group joined by code
        // just now and starting for the first time (no state yet — a started group is saved with
        // its name's time, a joined one without; and a member's state set aside as unreadable starts
        // empty too, but long after joining), or one this phone said goodbye to. After the counters
        // are raised (above), so the hello outranks that goodbye everywhere.
        r.helloIfDue(firstJoin = read.state == null && saved.nameAt == 0L && System.currentTimeMillis() - saved.joinedAt in 0..FIRST_JOIN_MS,
            joinedAt = saved.joinedAt)
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
        turn(fp)
        if (archiveFp == fp) dropArchive()   // the file is this router's now: no reading copy beside it
        // The new group's unread count lives on the router from now on.
        store.setPausedUnread(fp, 0)
        r.hasInternet = internetNow()
        r.setCaps(computeCaps())
        r.resumeErrands()
        scheduleErrandWake()
        finishInterruptedFiles(r, fp)
        sendKeptReplies(r)
        changed()
        return true
    }

    /**
     * Save a router that is not on the radio yet, and wait — a few seconds at most — to hear that
     * its state is on disk. A writer too busy to say in time is waited for the usual way
     * ([buildWhenWriterFree]): the save is queued, and the build after it finds it.
     */
    private fun savedBeforeStart(fp: String, r: Router): Boolean {
        r.takeDirty()
        queueWrite(fp, r.snapshot())
        val saved = try { writer.submit(Callable { fp !in unsaved }).get(WRITER_WAIT_S, TimeUnit.SECONDS) }
            catch (e: TimeoutException) { buildWhenWriterFree(); return false }
            catch (e: Exception) { false }
        if (!saved) Log.w(TAG, "the upgraded state could not be saved; not starting the group")
        return saved
    }

    /**
     * Right after the upgrade ([Router.reissueQueued]): photos and files of mine that never got off
     * this phone in the old format go out again the way "Send again" sends them — read from the
     * copy this phone kept, under a new file id and key. One whose copy is gone, or that can't go
     * out (no room), stays "not sent", for the person to send again or delete. The reading and
     * sealing run off the main thread, and the build waits for them — once, seconds at most —
     * because what they change belongs in the state that is saved before the radio starts.
     *
     * Each file sent again has two copies here for a moment: the old one, which the state on disk
     * still names, and the new one. Which goes is decided once that save is known ([settleCopies]).
     */
    private fun reissueFiles(r: Router, fp: String, files: List<Message>): Copies {
        class Redo(val old: Message, val was: Attachment, val att: Attachment, val kept: Boolean, val pieces: List<String>)
        val asked = files.mapNotNull { m -> m.att?.let { Triple(m, it, r.newFid()) } }
        val work = FutureTask(Callable {
            asked.mapNotNull { (m, a, fid) ->
                val bytes = (try { Blobs.fileFor(app, fp, a).readBytes() } catch (e: Exception) { null })?.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val s = Blobs.seal(bytes)
                val att = s.again(a, fid)
                Redo(m, a, att, Blobs.saveOwn(app, fp, att, bytes), s.pieces)
            }
        })
        Thread(work, "hopline-reissue").start()
        val ready = try { work.get(WRITER_WAIT_S, TimeUnit.SECONDS) } catch (e: Exception) {
            Log.w(TAG, "files to send again weren't ready in time; they stay not sent")
            // The copies it still writes belong to no message: they go once it is done.
            Blobs.background { (try { work.get() } catch (t: Exception) { null })?.forEach { Blobs.fileFor(app, fp, it.att).delete() } }
            return Copies(emptyList(), emptyList())
        }
        val old = ArrayList<File>(); val new = ArrayList<File>()
        for (x in ready) {
            // The old message goes from the chat; its copy stays until the save says it may go.
            val sent = BlobRules.resend(r, x.old, x.att, x.kept, x.pieces) { id -> r.hideMessages(listOf(id)) }
            if (sent is BlobRules.Sent.Ok) { old.add(Blobs.fileFor(app, fp, x.was)); new.add(Blobs.fileFor(app, fp, x.att)) }
            else dropOwnCopy(fp, x.att)
        }
        return Copies(old, new)
    }

    /** The copies of the files [reissueFiles] sent again: [old], which the state on disk names until the save, and [new], which it names after. */
    private class Copies(val old: List<File>, val new: List<File>)

    /**
     * Once the state saved before the start is on disk — known on the writer, right behind that
     * save — the old copies go. If it never got there, the new ones go instead: the next start
     * reads the old state again and sends those files again, from the old copies. Either way no
     * photo is ever left without its copy, and no copy is left without its message.
     */
    private fun settleCopies(fp: String, copies: Copies) {
        if (copies.old.isEmpty() && copies.new.isEmpty()) return
        writer.execute {
            val gone = if (fp !in unsaved) copies.old else copies.new
            Blobs.background { gone.forEach { it.delete() } }
        }
    }

    /**
     * Replies typed into a notification while the group was still starting (Store.keepReply): they
     * go now that its router is up, and are forgotten once the state that holds them is on disk.
     * One for a private chat nothing can be written to (Router.canMessage) is not lost either: it
     * waits in that chat ([keepAsDraft]).
     */
    private fun sendKeptReplies(r: Router) {
        val fp = r.group.fingerprint
        val kept = store.keptReplies(fp)
        if (kept.isEmpty()) return
        for (k in kept) {
            val mine = if (k.chat == GROUP) r.sendChat(k.text, mentions = Ui.mentionsIn(r, k.text)) else r.sendDm(k.chat, k.text)
            if (mine == null) keepAsDraft(fp, k.chat, k.text)
        }
        saveNow()
        writer.execute { if (fp !in unsaved) handler.post { store.dropKeptReplies(fp, kept) } }
    }

    /**
     * Words that could not be sent wait in [chat]'s composer, after whatever was half-typed there
     * already. A chat from before the update never gets its composer back: its screen shows them
     * in the bar that says so, with a Copy button, until they are copied out.
     */
    fun keepAsDraft(fp: String, chat: String, text: String) {
        val draft = ChatDrafts.get(app, fp, chat)
        val words = listOfNotNull(draft?.text?.ifBlank { null }, text).joinToString("\n")
        ChatDrafts.put(app, fp, chat, ChatDrafts.Draft(words, draft?.replyId, draft?.chosen ?: emptyMap()))
    }

    private var buildWaiting = false

    /**
     * The writer was too busy to hand over the group's state in time. Start the group the moment
     * the writer has caught up — by queueing behind it, not by waiting on it again and again.
     */
    private fun buildWhenWriterFree() {
        if (buildWaiting) return
        buildWaiting = true
        writer.execute {
            handler.post {
                buildWaiting = false
                if (router == null && store.hasActive() && Permissions.allGranted(app)) build()
                deferredBuildDone()
            }
        }
    }

    /**
     * A build that had to wait has run. Started from the background, a service can't be brought
     * up again (Android refuses), so the radio starts through the one that stayed up waiting for
     * it; with none up, it is asked for the usual way. A build that failed for good lets the
     * waiting service go.
     */
    private fun deferredBuildDone() {
        if (router != null) { if (!MeshService.routerReady()) ensureRunning() }
        else if (!buildPending) MeshService.nothingToRun()
        changed()
    }

    // ------------------------------------------------------------------ group keys

    /**
     * One thread that stretches group codes into keys (Crypto.stretch, seconds each). Never the
     * main thread, and never the writer: both are waited on, and a key behind a slow save — or a
     * save behind a key — would fail the build, a leave or a rejoin.
     */
    private val keyThread = Executors.newSingleThreadExecutor()
    /** Codes whose key is being worked out, and who is waiting for it. Main thread only. */
    private val keyWaits = HashMap<String, ArrayList<(ByteArray?) -> Unit>>()

    /** Work out [code]'s master key on the keys thread; [done] gets it on the main thread (null if it couldn't be made). */
    private fun deriveKey(code: String, done: (ByteArray?) -> Unit) {
        keyWaits[code]?.let { it.add(done); return }
        keyWaits[code] = arrayListOf(done)
        keyThread.execute {
            val mk = try { Crypto.stretch(code) } catch (t: Throwable) { Log.w(TAG, "could not work out a group's key", t); null }
            handler.post { keyWaits.remove(code)?.forEach { it(mk) } }
        }
    }

    /** A saved group without its key: worked out and kept; the group starts the moment it's there, if it's on the radio. */
    private fun needKey(code: String) {
        if (code !in keyWaits) deriveKey(code) { keyLanded(code, it) }
    }

    private fun keyLanded(code: String, mk: ByteArray?) {
        if (mk != null) store.setKey(code, mk)
        if (store.activeCode != code || router != null || buildWaiting) return
        if (mk != null && Permissions.allGranted(app)) build()
        deferredBuildDone()
    }

    /**
     * After the upgrade to 2.4, at every start until done: every saved group without its key gets
     * it worked out once — the group on the radio first, then the paused ones, then the ones left
     * (a rejoin needs it). Groups saved since have theirs from the start.
     */
    private fun backfillKeys() {
        val active = store.activeCode
        val missing = store.allGroups().filter { it.masterKey() == null }
            .sortedBy { when { it.code == active -> 0; !it.left -> 1; else -> 2 } }
        for (g in missing) needKey(g.code)
    }

    /**
     * A group being started or joined (GroupActivity): its key is worked out first, which takes
     * seconds, then it is saved and put on the radio. Owned here, not by the screen, so it carries
     * on through a turned phone or a screen left behind. [made]: this phone started the group.
     */
    class Adding(val code: String, val name: String, val nameAt: Long, val made: Boolean) {
        /** [WORKING], [DONE] (saved and on the radio) or [FAILED] (nothing was saved: the list didn't reach the disk). */
        var state = WORKING; internal set

        companion object {
            const val WORKING = 0
            const val DONE = 1
            const val FAILED = 2
        }
    }

    /** The group being started or joined, or the outcome of the last one until a screen takes it ([takeAdding]). */
    var adding: Adding? = null; private set

    /** Start a new group with [code] (see [Adding]). False when another start or join is still working. */
    fun startGroup(code: String, name: String, nameAt: Long): Boolean = addGroup(Adding(Words.normalise(code), name, nameAt, made = true))

    /** Join the group [code] — a link's [name] is only a hint (see Store.addGroup). False when another is still working. */
    fun joinGroup(code: String, name: String): Boolean = addGroup(Adding(Words.normalise(code), name, 0, made = false))

    private fun addGroup(a: Adding): Boolean {
        if (adding?.state == Adding.WORKING) return false
        adding = a
        deriveKey(a.code) { mk ->
            if (adding !== a) return@deriveKey
            if (mk == null || !store.addGroup(a.code, a.name, mk, a.nameAt)) {
                Log.w(TAG, "a new group could not be saved")
                a.state = Adding.FAILED
            } else {
                switchGroup(a.code)
                a.state = Adding.DONE
            }
            changed()
        }
        changed()
        return true
    }

    /** The finished start or join, handed to the one screen that shows its outcome; null while it works (or there is none). */
    fun takeAdding(): Adding? = adding?.takeIf { it.state != Adding.WORKING }?.also { adding = null }

    /**
     * Goes up whenever a group's state file changes hands: a router is built for it, the group is
     * left, or deleted. A router from before that moment may then never write its state again —
     * it would put an old chat over a newer one, or a deleted one back on the phone.
     */
    private val epochs = HashMap<String, Int>()
    private fun turn(fp: String) {
        epochs[fp] = (epochs[fp] ?: 0) + 1
        if (slowRead?.fp == fp) slowRead = null
    }

    /**
     * After a restart: files whose last pieces arrived while we were dead get assembled now, and
     * each outcome counts as if the file had just come in ([assembled]) — a file already marked as
     * one that can't be opened is only noted, never tried again.
     */
    private fun finishInterruptedFiles(r: Router, fp: String) {
        val pending = r.messages.mapNotNull { m -> m.att?.let { m to copyOf(it) } }
        if (pending.isEmpty()) return
        Thread {
            for ((m, att) in pending) {
                try {
                    val a = Blobs.assemble(app, fp, r.chunks, m.from, att)
                    handler.post { assembled(r, m, a) }
                } catch (e: Throwable) { Log.w(TAG, "could not finish a file", e) }
            }
            handler.post { changed() }
        }.start()
    }

    /** The assembler's own copy of an attachment: the message's may be marked on the main thread meanwhile. */
    private fun copyOf(att: Attachment) = Attachment(JSONObject(att.json.toString()))

    /** Put [m]'s file together off the main thread, and act on how it went ([assembled]). */
    private fun assembleFile(r: Router, m: Message) {
        val att = m.att?.let { copyOf(it) } ?: return
        val fp = r.group.fingerprint
        Thread {
            val a = try { Blobs.assemble(app, fp, r.chunks, m.from, att) }
                catch (e: Throwable) { Log.w(TAG, "could not put a file together", e); BlobRules.Assembly.Failed }
            handler.post { assembled(r, m, a) }
        }.start()
    }

    /**
     * How putting [m]'s file together went, on the main thread ([r]: the router it was for; a
     * router that is off the radio by now has nothing left to decide):
     *  - ready: it is on the phone;
     *  - waiting: a piece is still to come — perhaps one that didn't open and is being fetched
     *    again, which the message remembers, so that is done once only — and its arrival tries again;
     *  - bad: it can never be opened; the message says so from now on, and it is never tried again;
     *  - failed: every piece is here but the file couldn't be written; the tick tries again later.
     * The two marks are saved at once: they are what keeps "fetched again once" and "never tried
     * again" true after the app is killed.
     */
    private fun assembled(r: Router, m: Message, a: BlobRules.Assembly) {
        val att = m.att ?: return
        if (router !== r) return
        val fid = att.fid
        if (a !is BlobRules.Assembly.Failed) fileRetries.remove(fid)
        when (a) {
            BlobRules.Assembly.Ready -> r.markFileReady(fid)
            is BlobRules.Assembly.Waiting -> {
                r.unmarkFileReady(fid)
                if (a.refilled.isNotEmpty()) { att.markRefilled(a.refilled); saveNow() }
            }
            BlobRules.Assembly.Bad -> {
                r.markFileReady(fid)
                if (!att.failed) { att.markFailed(); saveNow() }
            }
            BlobRules.Assembly.Failed -> {
                // No piece is left to arrive and try again: the tick does, less and less often.
                r.markFileReady(fid)
                val tries = (fileRetries[fid]?.takeIf { it.r === r }?.tries ?: 0) + 1
                fileRetries[fid] = FileRetry(r, tries, System.currentTimeMillis() + BlobRules.retryAfter(tries))
            }
        }
        changed()
    }

    /** A file whose pieces are all here but that couldn't be written: when it is tried again, and how often it was (bug service#5). */
    private class FileRetry(val r: Router, val tries: Int, val at: Long)
    private val fileRetries = HashMap<String, FileRetry>()

    /** From the tick: files [assembled] put off whose time has come. Only those — never every file in the chat. */
    private fun retryFiles(r: Router) {
        if (fileRetries.isEmpty()) return
        val now = System.currentTimeMillis()
        for ((fid, t) in fileRetries.entries.toList()) {
            if (t.r !== r) { fileRetries.remove(fid); continue }
            if (now < t.at) continue
            val m = r.fileMessage(fid)
            val att = m?.att
            // Deleted meanwhile, or a piece has gone since (expired): its arrival tries again, not the tick.
            if (att == null || !r.fileComplete(att)) { fileRetries.remove(fid); r.unmarkFileReady(fid); continue }
            fileRetries[fid] = FileRetry(r, t.tries, Long.MAX_VALUE)   // under way: not again until it says how it went
            assembleFile(r, m)
        }
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

    // ------------------------------------------------------------------ leaving, rejoining, deleting

    /**
     * Leave a group this phone is in — the one on the radio or a paused one. The phone stops
     * serving it; its chat stays here, to be read, until the person deletes the group.
     *
     * What stays and what goes is [Archive.strip]'s rule: every message stays as it is, and what
     * was only there to serve the group goes — other people's messages in transit, their requests,
     * and (once the files that are complete have been put together) the pieces carried for others.
     *
     * The order matters. The group list is committed FIRST ([Store.markLeft]); only then is the
     * stripped state written. A phone killed in between has a left group with its full state, and
     * the next start strips it ([reconcile]). The other way round, a kill would leave a group this
     * phone is still in with its unsent messages robbed of their envelopes.
     *
     * The group on the radio is told: a goodbye ([Router.sayGoodbye]) goes to the phones linked
     * now, the radio staying up a few seconds more for it ([NearbyTransport.finish]), and the group
     * carries it on to everyone else — they see "Asha left", and stop counting this phone. With
     * nobody linked, or for a paused group, nobody can be told: to them this phone has walked away.
     *
     * False — and nothing at all has changed — when the group on the radio could not be saved
     * first ([StateRules.leave]): its router holds the only whole copy of the chat, and is not let
     * go until that copy is on disk. The phone stays in the group; the screen says why.
     */
    fun leaveGroup(code: String): Boolean {
        val g = store.allGroups().firstOrNull { it.code == Words.normalise(code) && !it.left } ?: return false
        val r = router?.takeIf { it.group.fingerprint == g.fingerprint }
        // The goodbye is noted before the chat is saved whole, so the saved chat knows it was said.
        r?.aboutToLeave()
        // A paused group's chat is on disk already, as its router saved it when the radio moved off.
        val left = StateRules.leave(saveWhole = { r == null || savedNow(r) }) { letGo(g, r) }
        // Refused: no goodbye after all — and the chat on disk must stop saying there was one, now
        // rather than at the next save (a kill in between would have the next start say hello).
        if (!left && r != null) { r.stayed(); if (router === r) flushSave() }
        return left
    }

    /**
     * Save the router on the radio now, and wait — a few seconds at most — to hear whether its
     * state reached the disk. False when it did not (no space, most likely), or the writer was too
     * busy to say in time: the router then saves again shortly, as after any change.
     */
    private fun savedNow(r: Router): Boolean {
        if (router !== r) return false
        val fp = r.group.fingerprint
        saveNow()
        // Behind the write just queued, on the same thread: by then its outcome is known.
        val saved = try { writer.submit(Callable { fp !in unsaved }).get(WRITER_WAIT_S, TimeUnit.SECONDS) } catch (e: Exception) { false }
        if (!saved) { Log.w(TAG, "the group's state could not be saved"); saveSoon() }
        return saved
    }

    /** Whether the last group left was told so (Router.sayGoodbye): a paused one never is, nor one with nobody linked. */
    var toldLeaving = false; private set

    /** The leaving itself, once the chat is safely on disk ([r] is null for a paused group). */
    private fun letGo(g: SavedGroup, r: Router?) {
        val fp = g.fingerprint
        val now = System.currentTimeMillis()
        val wasActive = r != null || store.activeCode == g.code
        if (r != null) {
            // What is still on its way is called off, so what is said on the way out (below) isn't
            // stuck behind a photo's pieces or a backlog, as a leave always cut those short.
            transport?.callOff()
            stopLiveLocation()   // while the radio is still up, so the beacon that clears my pin goes out
            for (e in r.errands.values) stopFetch(e.id)
            // Requests this phone was on for others go back to the group at once ...
            val declined = r.declineForLeaving()
            // ... and the group hears that this phone is leaving — from the phones linked now, and
            // through them everyone else — while the radio is still up. Last of all that it sends:
            // anything numbered after the goodbye would put it back in the group. Its state
            // remembers it said so, and a rejoin answers it with a hello. (A paused group's radio
            // is off: there is nobody to tell.)
            toldLeaving = r.sayGoodbye(declined)
            handler.removeCallbacks(saveRunnable); savePosted = false
        } else toldLeaving = false
        val full = r?.snapshot()
        // Files whose every piece is here but which were never put together: now is their last
        // chance, before the pieces go.
        val complete = if (r == null) emptyList() else r.messages.mapNotNull { m -> m.att?.takeIf { r.fileComplete(it) }?.let { m.from to copyOf(it) } }
        if (r != null) {
            // Detached before the radio stops: the link-downs it ends with must not reach a router
            // that has had its last word, nor queue another save of it. The radio itself stays up a
            // moment more, until what was just sent on it — the goodbye — has gone.
            val t = transport
            router = null; transport = null; radioProblem = ""
            t?.finish(FAREWELL_MS)
        }
        dropArchive()
        turn(fp)
        val committed = store.markLeft(g.code, now)
        // If the list didn't reach the disk (no space, most likely), a kill now would find this phone
        // still in the group: then its state stays whole, and the start that finds it left tidies up.
        if (r != null && full != null) queueWrite(fp, if (committed) Archive.strip(full, r.me.id, r.me.name, now, now) else full)
        Notifications.clearGroup(app, fp)
        ChatDrafts.dropGroup(app, fp)
        if (committed) {
            if (r == null) sealPaused(g.code, fp, now)
            else seal(g.code, fp) { for ((from, att) in complete) try { Blobs.assemble(app, fp, r.chunks, from, att) } catch (e: Throwable) { Log.w(TAG, "could not finish a file", e) } }
        }
        // The radio moves to the group this phone is still in that it used last. With none — or none
        // that can start right now — the service has nothing to keep alive.
        if (wasActive && !ensureRunning()) app.stopService(Intent(app, MeshService::class.java))
        changed()
    }

    /**
     * The end of leaving the group that was on the radio, queued behind its stripped state on the
     * writer: put together the files that are complete ([first], with the detached router — nothing
     * changes it any more), then let the carried pieces go and note that the tidy-up is done. The
     * slow part runs on Blobs' housekeeping thread. If the stripped state failed to save, the group
     * stays unsealed, and the next start does all of it again from the file — which holds the
     * whole chat: [leaveGroup] made sure of that before it let the router go.
     */
    private fun seal(code: String, fp: String, first: () -> Unit) {
        writer.execute {
            val stripped = fp !in unsaved
            Blobs.background {
                first()
                if (stripped) dropPieces(code, fp)
            }
        }
    }

    /**
     * The same end for a group with no router: one that was paused when it was left, or one whose
     * leaving a kill cut short. Its state is stripped where it lies — on the writer, behind any
     * save still queued for it. A file that can't be read is left exactly as it is.
     */
    private fun sealPaused(code: String, fp: String, leftAt: Long) {
        // Without this phone's id nothing can tell its messages from the rest: the next start tries again.
        val me = store.identity() ?: return
        val meName = store.name
        writer.execute {
            val done = try {
                val state = store.peekState(fp)
                // Saved by an older version: made fit for this one first, so "mine" means mine (see Store.upgrade).
                state == null || store.saveState(fp, Archive.strip(store.upgrade(fp, state, left = true) ?: state,
                    me.id, meName, leftAt, System.currentTimeMillis()))
            } catch (t: Throwable) { Log.w(TAG, "could not tidy a left group's state", t); false }
            if (done) Blobs.background { dropPieces(code, fp) }
        }
    }

    /**
     * The pieces go and the group is sealed — but only while it is still left ([Store.markSealed]
     * checks under its lock): a group joined again a moment after leaving keeps its pieces.
     */
    private fun dropPieces(code: String, fp: String) { store.markSealed(code) { Blobs.dropChunks(app, fp) } }

    /**
     * Join a group this phone had left: its chat is still here, and the radio goes back to it (the
     * group that was on the radio, if any, is paused exactly as a switch pauses it). False when the
     * code is not a left group's — or the writer is still busy with the tidy-up after leaving: the
     * stripped state must be on disk before a router is built on it, and the screen says to try
     * again in a moment. False, too, when the group now on the radio could not be saved before
     * being paused, or the saved group list could not be written: then the phone is not back in
     * the group, nothing says it is, and the radio stays where it was.
     *
     * The screens ask for the radio's permissions before they call this (Asks.radioAllowed). Should
     * those be taken away in between, this is still true — the group is joined — and Home's guard
     * sends the person to grant them; the group starts then.
     */
    fun rejoinGroup(code: String): Boolean {
        val g = store.allGroups().firstOrNull { it.code == Words.normalise(code) && it.left } ?: return false
        dropArchive()
        // Everything queued for the writer has to be on disk first. And the group on the radio now,
        // if there is one, is about to be paused: its router is let go, so its chat is saved whole
        // before that — a save that fails here still has a router to try again from.
        val live = router
        if (if (live != null) !savedNow(live) else !drainWrites()) return false
        // The list first, and only then the radio: a rejoin that can't be saved must not have
        // paused the group the radio is on, nor leave this run believing what the next won't.
        if (!store.rejoin(g.code, System.currentTimeMillis())) return false
        stopLiveLocation()   // a position shared with one group must not leak into another
        retire()
        radioProblem = ""
        ensureRunning()
        changed()
        return true
    }

    /**
     * "Delete group": the one thing that takes a group's chat off this phone — every message, photo
     * and file. Only for a group that was already left (one this phone is in must be left first).
     * Copies the person saved to Pictures or Downloads are theirs, and stay.
     */
    fun deleteLeftGroup(code: String) {
        val g = store.allGroups().firstOrNull { it.code == Words.normalise(code) && it.left } ?: return
        dropArchive()
        Notifications.clearGroup(app, g.fingerprint)
        store.removeForGood(g.code)
        purge(g.fingerprint, revived = false)
        changed()
    }

    /**
     * Delete what a deleted group left on the phone. It is asked for by the note [Store.removeForGood]
     * committed with the delete, so after a kill it is simply done again from the top, as often as
     * it takes. The state files and the history go on the writer thread — behind any save still
     * queued for the group, so nothing can write them back, and without the main thread waiting.
     *
     * [revived]: the same code has been joined again since the delete, and a kill came before the
     * old files were gone. What the writer deletes under this fingerprint is then still the deleted
     * group's — the new group's router reads its state only after this, and saves nothing before —
     * but the folder of photos and pieces may already be the new group's, and is left alone.
     */
    private fun purge(fp: String, revived: Boolean) {
        turn(fp)
        earlierKnown.remove(fp)
        if (!revived) {
            Blobs.deleteGroup(app, fp)
            ChatDrafts.dropGroup(app, fp)
        }
        writer.execute {
            synchronized(pendingWrites) { pendingWrites.remove(fp) }
            unsaved.remove(fp)
            try {
                store.deleteStateFiles(fp)
                History(store.historyDir(fp)).deleteAll()
            } catch (t: Throwable) { Log.w(TAG, "could not delete a group's files", t) }
            // Only when they are really gone: otherwise the note stays, and the next start tries again.
            if (!store.stateExists(fp) && !store.historyDir(fp).exists()) store.finishPurge(fp)
        }
    }

    /**
     * At every start, before anything else can queue a write: finish what a kill cut short. Each
     * job is asked for by something that was committed — a "purge" note, a left group not yet
     * sealed — never by what merely looks orphaned. If the group list ever failed to read, "matches
     * no saved group" would be every chat on the phone.
     */
    private fun reconcile() {
        for (fp in store.purgePending()) purge(fp, revived = store.findGroup(fp) != null)
        val left = store.leftGroups()
        for (g in left) if (!g.sealed) sealPaused(g.code, g.fingerprint, g.leftAt)
        // A notification posted just before a kill mid-leave would sit there offering a reply box.
        if (left.isNotEmpty()) Notifications.clearGroups(app, left.map { it.fingerprint })
        Blobs.sweep(app, store.activeGroup()?.fingerprint)
    }

    // ------------------------------------------------------------------ reading a group that was left

    private val noRadio = object : Transport {
        override fun send(linkId: String, bytes: ByteArray): Long = -1L
        override fun disconnect(linkId: String) {}
    }

    /**
     * All a left group's router can ever cause is a redraw. It never gets Core's own listener: that
     * one posts notifications, runs requests, and renames the group on the radio.
     */
    private val archiveListener = object : RouterListener {
        override fun onChanged() { changed() }
        override fun onMessage(m: Message) {}
        override fun onErrandRequest(e: Errand) {}
    }

    private var archiveFp: String? = null
    private var archiveRouter: Router? = null

    /**
     * The chat of a group this phone left, to read: a router with no radio, restored from the
     * group's saved state. Null unless [fp] is a LEFT group — never for the group on the radio or
     * a paused one, whose state file belongs to its real router. Null too when the state can't be
     * read just now: then nothing is built, so nothing can ever be saved over the file. (A left
     * group that never had a state gets an empty chat.)
     *
     * The router is for looking only. It is never ticked — so nothing in it expires, is trimmed or
     * is filed away — never polled, and keeps file pieces in memory only (the real piece store
     * would create folders and treat the group as live). Nothing is ever saved from it except by
     * [deleteArchivedMessages]. One is kept at a time: ask again on every resume, because leaving,
     * rejoining, deleting, or a long while in the background lets it go.
     */
    fun archive(fp: String): Router? {
        archiveBusy = false
        val g = store.findGroup(fp)?.takeIf { it.left } ?: return null
        if (router?.group?.fingerprint == fp) return null
        if (archiveFp == fp) archiveRouter?.let { return it }
        val me = store.identity() ?: return null
        val read = loadStateOrdered(fp, peek = true)
        archiveBusy = read.busy
        if (!read.usable) return null
        // Reading a left group's chat needs no key: it never seals, opens or checks anything. The
        // saved one is used when there is one; otherwise a stand-in nothing is ever sealed with.
        val group = store.groupOf(g) ?: Group(g.code, g.name, g.nameAt, keys = GroupKeys(ByteArray(32)), fingerprint = g.fingerprint)
        val r = Router(me, group, noRadio, archiveListener)
        if (read.state != null) try { r.restore(read.state) } catch (e: Throwable) {
            Log.w(TAG, "a left group's chat could not be opened", e)
            return null
        }
        archiveFp = fp; archiveRouter = r
        return r
    }

    /**
     * The last [archive] came back empty only because the chat wasn't read in time — the writer was
     * busy, or the file is big and the phone slow. The read carries on, so asking again in a
     * moment works; a screen says that, not that the chat can't be opened.
     */
    var archiveBusy = false; private set

    /** Let go of the left group's chat held for reading (it is read again from disk when next asked for). */
    fun dropArchive() {
        archiveFp = null; archiveRouter = null
        if (slowRead?.peek == true) slowRead = null
    }

    /**
     * "Delete for me" inside a left group's chat — the only thing that ever writes to a left
     * group's state. The messages go from the chat and from its history, and their files with them.
     *
     * In that order ([StateRules.deleteKept]): the state without them is saved first, and their
     * files and history copies go only once it is on disk. If it can't be saved, nothing of theirs
     * is deleted, the person is told, and [failed] runs (main thread) so the screen can show the
     * chat again as the disk has it — otherwise they would come back later without their photos.
     */
    fun deleteArchivedMessages(fp: String, ids: Collection<String>, failed: () -> Unit = {}) {
        val a = archive(fp) ?: return
        val g = store.findGroup(fp) ?: return
        val want = ids.toHashSet()
        val gone = a.hideMessages(want)
        // A left group's state can hold a message its history holds too (the group was left, or the
        // phone killed, between filing a batch and saving), so the history is always looked through.
        val cleanUp = { deleteFiles(fp, gone); if (hasEarlier(fp)) forgetEarlier(fp) { it.optString("id") in want } }
        changed()
        // Only earlier messages, which the state doesn't hold: there is nothing to save first.
        if (gone.isEmpty()) { cleanUp(); return }
        queueWrite(fp, Archive.strip(a.snapshot(), a.me.id, a.me.name, g.leftAt, System.currentTimeMillis()))
        // Behind that write, on the same thread: by then its outcome is known.
        writer.execute {
            val saved = fp !in unsaved
            handler.post {
                StateRules.deleteKept(saved, cleanUp) {
                    Log.w(TAG, "a delete in a left group's chat could not be saved; nothing of it was deleted")
                    if (archiveRouter === a) dropArchive()   // it shows them deleted; the disk doesn't
                    if (appVisible) Toast.makeText(app, R.string.delete_not_saved, Toast.LENGTH_LONG).show()
                    failed()
                    changed()
                }
            }
        }
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
        if (to != null && !r0.canMessage(to)) { cleanup?.invoke(); return done(cantWriteTo(r0, to)) }
        val fp = r0.group.fingerprint
        val fid = r0.newFid()
        Thread {
            val prep = try { Blobs.prepareImage(app, uri) } catch (e: Throwable) { null }
            // Sealed here, off the main thread, under a key of its own — and the copy this phone
            // keeps is written here too: up to 2 MB is no job for the main thread.
            val sealed = prep?.let { Blobs.seal(it.bytes) }
            val att = if (prep == null || sealed == null) null else Attachment.make(fid, prep.name, prep.mime,
                prep.bytes.size.toLong(), sealed.pieces.size, prep.width, prep.height, prep.thumbB64, key = sealed.key, sha = sealed.sha)
            val kept = att != null && prep != null && Blobs.saveOwn(app, fp, att, prep.bytes)
            handler.post {
                cleanup?.invoke()
                // The user may have switched groups while we were shrinking the photo — a photo
                // meant for one group must never be flooded into another.
                if (router !== r0) { att?.let { dropOwnCopy(fp, it) }; done("Group changed — photo not sent."); return@post }
                if (att == null || sealed == null) { done("Couldn't read that photo."); return@post }
                done(sent(r0, fp, att, BlobRules.send(r0, att, kept, sealed.pieces, caption, to, quote, mentions)))
            }
        }.start()
    }

    /** Send a picked document (or a recorded voice note) as-is. Same threading contract as sendImage. */
    fun sendFileBytes(picked: Blobs.PickedFile, caption: String, to: String?, durSec: Int = 0, quote: Quote? = null,
                      mentions: List<String> = emptyList(), done: (String?) -> Unit) {
        val r0 = router ?: return done("Hopline is starting — try again in a moment.")
        if (picked.bytes.isEmpty()) return done("That file is empty.")
        if (to != null && !r0.canMessage(to)) return done(cantWriteTo(r0, to))
        val fp = r0.group.fingerprint
        val fid = r0.newFid()
        Thread {
            val sealed = Blobs.seal(picked.bytes)
            val att = Attachment.make(fid, picked.name, picked.mime,
                picked.bytes.size.toLong(), sealed.pieces.size, 0, 0, "", durSec, key = sealed.key, sha = sealed.sha)
            val kept = Blobs.saveOwn(app, fp, att, picked.bytes)
            handler.post {
                if (router !== r0) { dropOwnCopy(fp, att); done("Group changed — file not sent."); return@post }
                done(sent(r0, fp, att, BlobRules.send(r0, att, kept, sealed.pieces, caption, to, quote, mentions)))
            }
        }.start()
    }

    /**
     * "Send again" for a photo, file or voice note that never got through: the very same bytes,
     * picture size, preview and length, as a new message — a photo isn't shrunk a second time.
     * The old message goes once the new one is on its way; if this one can't go either, the old
     * one stays as it was ([BlobRules.resend]).
     */
    fun resendFile(old: Message, done: (String?) -> Unit) {
        val r0 = router ?: return done("Hopline is starting — try again in a moment.")
        val a = old.att ?: return done("That file isn't on this phone any more.")
        old.to?.let { if (!r0.canMessage(it)) return done(cantWriteTo(r0, it)) }
        val fp = r0.group.fingerprint
        val src = Blobs.fileFor(app, fp, a)
        val fid = r0.newFid()
        Thread {
            val bytes = (try { src.readBytes() } catch (e: Exception) { null })?.takeIf { it.isNotEmpty() }
            // A new file id and a new key: a resend is a new file.
            val sealed = bytes?.let { Blobs.seal(it) }
            val att = sealed?.again(a, fid)
            val kept = att != null && bytes != null && Blobs.saveOwn(app, fp, att, bytes)
            handler.post {
                if (router !== r0) { att?.let { dropOwnCopy(fp, it) }; done("Group changed — not sent."); return@post }
                if (att == null || sealed == null) { done("That file isn't on this phone any more."); return@post }
                done(sent(r0, fp, att, BlobRules.resend(r0, old, att, kept, sealed.pieces) { deleteMessages(listOf(it)) }))
            }
        }.start()
    }

    /**
     * The end of every file send, on the main thread: null when it is on its way, otherwise what
     * to tell the person. A file that didn't go keeps no copy here — nothing in the chat points at it.
     */
    private fun sent(r: Router, fp: String, att: Attachment, s: BlobRules.Sent): String? = when (s) {
        is BlobRules.Sent.Ok -> {
            saveNow()   // what I send is on disk at once, not seconds later
            changed()
            null
        }
        BlobRules.Sent.NoRoom -> { dropOwnCopy(fp, att); NO_ROOM }
        is BlobRules.Sent.CantWrite -> { dropOwnCopy(fp, att); cantWriteTo(r, s.to) }
    }

    private const val NO_ROOM = "Not enough free space on this phone — not sent."

    /** Why nothing can be sent to [to] (Router.canMessage), in the words the chat uses for it. */
    private fun cantWriteTo(r: Router, to: String): String = when {
        !Crypto.isNodeId(to) -> "This chat is from before the update — not sent. To keep talking, open them from People."
        r.people[to]?.left == true -> "${Ui.nameOf(r, to)} left the group — not sent. You can send again if they rejoin."
        else -> "${Ui.nameOf(r, to)} hasn't been seen for a while — not sent. You can send once their phone has been in range."
    }

    /** The copy of a file of mine that never went out: in the background, with the other file chores. */
    private fun dropOwnCopy(fp: String, att: Attachment) {
        val f = Blobs.fileFor(app, fp, att)
        Blobs.background { f.delete() }
    }

    // ------------------------------------------------------------------ delete for me

    /**
     * Remove messages from this phone for good (and their received files): from the live chat, and
     * — for the earlier ones a chat shows when scrolled up — from the group's history.
     */
    fun deleteMessages(ids: Collection<String>) {
        val r = router ?: return
        val fp = r.group.fingerprint
        val want = ids.toHashSet()
        val live = r.hideMessages(want)
        deleteFiles(fp, live + HistoryRules.dropWaiting(r) { it.id in want })
        // The history is looked through for every one of them, not only for those the live window
        // didn't have: a message can be in both (the phone was killed, or a save failed, between
        // filing a batch and saving the state without it). The router remembers a deleted id for
        // two weeks only; after that the copy left in the history would be back in the chat.
        if (hasEarlier(fp) || filing === r) forgetEarlier(fp) { it.optString("id") in want }
        saveSoon()
        changed()
    }

    /** Empty one chat on this phone: the group chat (peer = null) or a private chat — its earlier messages too. */
    fun clearChat(peer: String?) {
        val r = router ?: return
        val fp = r.group.fingerprint
        val chat = peer ?: GROUP
        val me = r.me.id
        val gone = r.hideMessages(r.chatMessages(peer).map { it.id }) + HistoryRules.dropWaiting(r) { it.chatKey(me) == chat }
        deleteFiles(fp, gone)
        if (hasEarlier(fp) || filing === r) forgetEarlier(fp) { HistoryRules.chatOf(it, me) == chat }
        Notifications.clearChat(app, fp, chat)
        saveSoon()
        changed()
    }

    /** A deleted message's photo, voice note or file goes with it — in the background, with the other file chores. */
    private fun deleteFiles(fp: String, gone: List<Message>) {
        val files = gone.mapNotNull { m -> m.att?.let { Blobs.fileFor(app, fp, it) } }
        if (files.isNotEmpty()) Blobs.background { files.forEach { it.delete() } }
    }

    // ------------------------------------------------------------------ earlier messages (the history)

    /**
     * One page of a chat's older messages, read back from the group's history: [messages] in chat
     * order, and [next] — what to pass as `before` for the page before this one; 0 = nothing older.
     * A page can repeat a message an earlier page (or the live chat) already showed — the phone was
     * killed between filing it and saving — so whoever shows pages goes by message id.
     */
    class EarlierPage(val messages: List<Message>, val next: Int)

    /** Does a group have anything in its history? Asked on every redraw, so remembered per group. */
    private val earlierKnown = HashMap<String, Boolean>()
    private val historyStamps = HashMap<String, Int>()

    /**
     * Are there older messages on disk than the group's chat shows from memory? True for a group on
     * the radio or one that was left alike. (Only looks at the names in a folder — cheap, and safe
     * beside the writer.)
     */
    fun hasEarlier(fp: String): Boolean = earlierKnown.getOrPut(fp) {
        store.findGroup(fp) != null && try { !History(store.historyDir(fp)).isEmpty() } catch (e: Exception) { false }
    }

    /**
     * Goes up every time a group's history changes under a chat that may be showing it: older
     * messages moved out of the live window into a new segment, or some were deleted. The pages a
     * screen loaded before no longer line up with what is on disk; it starts its paging again.
     */
    fun historyStamp(fp: String): Int = historyStamps[fp] ?: 0

    private fun historyChanged(fp: String) {
        earlierKnown.remove(fp)
        historyStamps[fp] = historyStamp(fp) + 1
        changed()
    }

    /**
     * Read the page of [chat]'s older messages ([GROUP] or a person's id) that comes before the
     * segment [before] — null to start from the newest. [cb] is called later, on the main thread,
     * always; check there that the screen still shows the chat it asked for.
     *
     * The reading happens on the writer thread, in order with the saves and the filing of new
     * segments, a few segments at a time ([HistoryRules.Walk]) so a save never waits long behind it.
     */
    fun loadEarlier(fp: String, chat: String, before: Int?, cb: (EarlierPage) -> Unit) {
        if (store.findGroup(fp) == null) { handler.post { cb(EarlierPage(emptyList(), 0)) }; return }
        val walk = HistoryRules.Walk(History(store.historyDir(fp)), chat, store.nodeId, before)
        fun step() {
            writer.execute {
                // A delete from this history is still working its way through: a page read now could
                // show what is being deleted. It waits its turn behind the delete's next step.
                if ((forgetting[fp] ?: 0) > 0) { step(); return@execute }
                val ready = try { walk.step() } catch (t: Throwable) { Log.w(TAG, "could not read earlier messages", t); true }
                if (!ready) { step(); return@execute }
                val page = EarlierPage(walk.messages(), walk.next)
                handler.post { cb(page) }
            }
        }
        step()
    }

    /** Deletes still working through a group's history ([forgetEarlier]), per group. Pages of it wait for them. */
    private val forgetting = ConcurrentHashMap<String, Int>()

    /**
     * Delete from a group's history what [gone] picks, and the files those messages had. On the
     * writer thread, in order with everything else there — but a few segments at a time
     * ([HistoryRules.Forget]): a long history sifted in one go would hold the writer for seconds,
     * with every save, and a main thread waiting to open a chat, queued behind it.
     */
    private fun forgetEarlier(fp: String, gone: (JSONObject) -> Boolean) {
        val forget = HistoryRules.Forget(History(store.historyDir(fp)), gone)
        forgetting.merge(fp, 1) { a, b -> a + b }
        var any = false   // writer thread only
        fun step() {
            writer.execute {
                val removed = try { forget.step() } catch (t: Throwable) { Log.w(TAG, "could not delete from the history", t); null }
                if (!removed.isNullOrEmpty()) {
                    any = true
                    val files = removed.mapNotNull { m -> m.optJSONObject("att")?.let { Blobs.fileFor(app, fp, Attachment(it)) } }
                    if (files.isNotEmpty()) Blobs.background { files.forEach { it.delete() } }
                }
                if (removed != null && !forget.done) { step(); return@execute }
                forgetting.computeIfPresent(fp) { _, n -> if (n > 1) n - 1 else null }
                if (any) handler.post { historyChanged(fp) }
            }
        }
        step()
    }

    /** The router whose oldest messages are being written to the history right now. */
    private var filing: Router? = null

    /**
     * The live window is over its size: its oldest messages move to the group's history on disk,
     * where the chat finds them again when scrolled up. In two steps ([HistoryRules.offer], then
     * [HistoryRules.settle] once the segment is written), so they are never in neither place:
     *  - killed before the segment is on disk: every saved state still holds them;
     *  - killed after it, before a state without them is saved: they are in both, and readers go by id;
     *  - the segment could not be written (no space): nothing changes, and the next tick tries again.
     * The state without them is saved only from here, after the segment — never by a save that
     * happened to be waiting on the writer already.
     */
    private fun fileOverflow(r: Router) {
        if (filing === r) return
        val batch = HistoryRules.offer(r)
        if (batch.isEmpty()) return
        val fp = r.group.fingerprint
        val records = batch.map { it.toJson() }
        filing = r
        writer.execute {
            val ok = try { History(store.historyDir(fp)).append(records) } catch (t: Throwable) { false }
            handler.post {
                if (filing === r) filing = null
                if (!ok) { Log.w(TAG, "older messages could not be filed; they stay with the group's state for now"); return@post }
                // A router that went off the radio meanwhile saved them with its state: in both places, read once.
                if (router === r) { HistoryRules.settle(r, batch); saveNow() }
                historyChanged(fp)
            }
        }
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
        if (batteryLow()) return "Paused — battery below 15%"
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
        val epoch = epochs[fp]
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
                // That router is off the radio. Its answer is saved only where it still belongs: a
                // group this phone is IN and has merely switched away from, whose state nobody has
                // touched since — not one that was left (its state is stripped, and stays so), not
                // one that is back on the radio under a new router, and not one deleted and joined
                // again. An old router's snapshot over any of those would undo what came after.
                if (epochs[fp] == epoch && router?.group?.fingerprint != fp && store.groups().any { it.fingerprint == fp }) queueWrite(fp, r.snapshot())
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
        // The service's tick is Hopline's "in the background": the moment to see whether a look for
        // a newer version is due. It decides in a few comparisons and does its work on its own thread.
        Updater.maybeCheck()
        val r = router ?: return
        r.battery = batteryPercent()
        r.hasInternet = internetNow()
        r.setCaps(computeCaps())
        if (r.hasInternet) runOwnWaiting(r)
        if (liveLocationUntil != 0L) pushMyLocation()   // stops itself once the time is up
        r.tick()
        retryFiles(r)
        fileOverflow(r)
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

    /**
     * One writer thread for every group's state file and history: saves never block the UI, never
     * interleave, and a read is always behind the saves queued before it.
     */
    private val writer = Executors.newSingleThreadExecutor()
    /** The newest state of each group still waiting for the writer. */
    private val pendingWrites = HashMap<String, JSONObject>()
    /** Groups whose last save did not reach the disk. Writer thread only. */
    private val unsaved = HashSet<String>()
    private var savePosted = false

    private val saveRunnable = Runnable { savePosted = false; saveNow() }

    private fun saveSoon() {
        if (savePosted) return
        savePosted = true
        handler.postDelayed(saveRunnable, 2500)
    }

    /**
     * Snapshot on the main thread (the router is single-threaded), write in the background. Only
     * ever the router on the radio: once a group is off it — paused, or left — nothing here saves it.
     */
    fun saveNow() {
        val r = router ?: return
        lastPeriodicSave = System.currentTimeMillis()
        r.takeDirty()
        queueWrite(r.group.fingerprint, r.snapshot())
    }

    /**
     * Hand a group's state to the writer. One write per group waits at a time, and a newer state
     * replaces an older one still waiting — newest wins, in the order the groups were queued.
     */
    private fun queueWrite(fp: String, state: JSONObject) {
        if (slowRead?.fp == fp) slowRead = null
        synchronized(pendingWrites) {
            val queued = pendingWrites.containsKey(fp)
            pendingWrites[fp] = state
            if (queued) return
        }
        writer.execute {
            val j = synchronized(pendingWrites) { pendingWrites.remove(fp) } ?: return@execute
            // The phone's own mark first, whatever the file does: it outlives the group's state.
            store.raiseLiveQMark(j.optLong("lastQ", 0))
            if (store.saveState(fp, j)) unsaved.remove(fp) else unsaved.add(fp)
        }
    }

    /** Save right away — the app is going to the background, or may be killed. */
    fun flushSave() { handler.removeCallbacks(saveRunnable); savePosted = false; saveNow() }

    /** Wait, [ms] at most, until everything queued for the writer is on disk. False if it isn't yet. */
    fun drainWrites(ms: Long = WRITER_WAIT_S * 1000): Boolean =
        try { writer.submit {}.get(ms, TimeUnit.MILLISECONDS); true } catch (e: Exception) { false }

    /**
     * What reading a group's state gave. [usable] false means there is a state on disk that could
     * not be had — nothing may be built in its place, or the next save would write over it.
     * [busy]: only because the writer didn't get to it in time. [upgraded]: [state] was saved by
     * an older version and has just been made fit for this one (Store.upgrade) — not on disk yet.
     */
    private class StateRead(val state: JSONObject?, val usable: Boolean, val busy: Boolean = false, val upgraded: Boolean = false)

    /**
     * Read a group's saved state in its turn on the writer thread: behind every save already
     * queued, so what comes back is the newest state, and no save is caught half-written. (Reading
     * a state file from the main thread while its save is under way throws that save away — a quick
     * switch to another group and back used to lose the last seconds of a chat that way.) The
     * caller waits, a few seconds at most.
     *
     * [peek] is for a group that is only being looked at — one this phone left: nothing is moved
     * aside, whatever the file's condition. Without it, a state that can't be used is set aside as
     * .corrupt-<time>, and the group starts clean.
     *
     * A state saved by an older version is made fit for this one right here, on the writer, before
     * any router sees it (Store.upgrade). If that can't be done just now, the state is not usable
     * this time — and stays on disk exactly as it was. A left group's upgraded state is saved at
     * once (nothing else would ever save it); the group going on the radio saves its own, after
     * what it has to send again is in it.
     */
    private fun loadStateOrdered(fp: String, peek: Boolean = false): StateRead {
        slowRead?.let { slow ->
            // The read an earlier call stopped waiting for has finished, and nothing has written
            // this state since: that is the answer. (Reading again could only time out again.)
            if (slow.fp == fp && slow.peek == peek && slow.read.isDone) {
                slowRead = null
                return try { slow.read.get() } catch (e: Exception) { StateRead(null, usable = false) }
            }
            if (slow.fp != fp || slow.peek != peek) slowRead = null
        }
        val read = writer.submit(Callable {
            val state = (if (peek) store.peekState(fp) else store.loadState(fp))
                ?: return@Callable StateRead(null, usable = !store.stateExists(fp))
            val up = try { store.upgrade(fp, state, left = peek) } catch (e: IOException) {
                Log.w(TAG, "a group's saved state can't be made fit for this version yet")
                return@Callable StateRead(null, usable = false)
            }
            if (up != null && peek) store.saveState(fp, up)
            StateRead(up ?: state, usable = true, upgraded = up != null && !peek)
        })
        return try { read.get(WRITER_WAIT_S, TimeUnit.SECONDS) }
        catch (e: TimeoutException) {
            // The read keeps its place in the queue. The group going on the radio is built on it
            // when it is done; a left group's chat opens on it when the person tries again — a
            // big state on a slow phone would otherwise time out every single time.
            slowRead = SlowRead(fp, peek, read)
            StateRead(null, usable = false, busy = true)
        }
        catch (e: Exception) { Log.w(TAG, "state read failed", e); StateRead(null, usable = false) }
    }

    /**
     * A read the main thread stopped waiting for (the writer was busy, or the state is big and the
     * phone slow): which group's, whether it was only a look ([peek]), and its result once the
     * writer gets there. Dropped the moment anything else touches that group's state ([turn],
     * [queueWrite]).
     */
    private class SlowRead(val fp: String, val peek: Boolean, val read: Future<StateRead>)
    private var slowRead: SlowRead? = null

    /** Set a state that won't restore aside, in its turn on the writer. False if it is still in the way. */
    private fun moveStateAsideOrdered(fp: String): Boolean =
        try { writer.submit(Callable { store.moveStateAside(fp) }).get(WRITER_WAIT_S, TimeUnit.SECONDS) } catch (e: Exception) { false }

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
            assembleFile(r, m)
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
            // The group whose router said so — which is not "whichever group is active" in the
            // moment a leave or a switch moves that on.
            router?.let { store.renameGroup(it.group.code, name, at) }
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

    /**
     * Have Android say when internet comes or goes ([refreshInternet]). Asked for when the radio
     * starts — and by the updater on a phone with no group on the radio, when it has promised to
     * look for a newer version "when you have signal": with no service ticking, nothing else
     * would notice. True once the phone is being told; registering twice does nothing.
     */
    fun watchInternet(): Boolean {
        if (netCallbackRegistered) return true
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
        return netCallbackRegistered
    }

    private fun refreshInternet() {
        val now = internetNow()
        // Internet appeared, or changed — mobile data to WiFi, say: Hopline's own update may have been waiting for that.
        if (now) Updater.maybeCheck()
        val r = router ?: return
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

    /** Nearly flat and not on a charger: no time to spend the battery on anything but the mesh. */
    fun batteryLow(): Boolean = batteryPercent() in 0..14 && !charging()

    /**
     * Phones in range that are in this group but still run a Hopline from before 2.4: they can't
     * link to this one until they update (NearbyTransport never connects to them).
     */
    fun olderPhonesNearby(): Int = transport?.olderPhonesNearby() ?: 0

    /** One plain-English line for the top of the screen. Links first: radio advice only when nothing is linked. */
    fun statusLine(): String {
        val r = router ?: return "Starting…"
        val links = r.authedLinks().size
        val inRange = r.peopleInRange()
        if (links == 0 && inRange == 0) {
            if (!bluetoothOn()) return "Turn on Bluetooth to find your group"
            if (radioProblem.isNotEmpty()) return radioProblem
            if (olderPhonesNearby() > 0) return "A phone in this group has an older Hopline and needs to update to chat"
            if (transport?.otherGroupNearby() == true) return "Hopline phones nearby are in another group — check you all typed the same code"
            if (!wifiOn()) return "Looking for your group's phones… (WiFi helps)"
            return "Looking for your group's phones…"
        }
        return when {
            // Linked, yet nobody in range: the one linked phone just left the group (its radio goes in a moment).
            inRange == 0 -> "Looking for your group's phones…"
            inRange == 1 -> "1 person in range"
            else -> "$inRange people in range"
        }
    }

    const val MIN_BUDGET_BYTES = 60_000L
    /** How long the main thread will wait for the writer to reach it, at most. */
    private const val WRITER_WAIT_S = 5L
    /** How long a group's radio stays up after leaving it, at most, for the goodbye to get out. */
    private const val FAREWELL_MS = 5_000L
    /** A group with no saved chat this long after it was joined is no new join, and says no hello. */
    private const val FIRST_JOIN_MS = 24 * 3_600_000L
    val isTiramisu get() = Build.VERSION.SDK_INT >= 33
}
