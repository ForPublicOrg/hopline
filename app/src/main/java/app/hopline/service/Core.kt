package app.hopline.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
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
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.hopline.core.Crypto
import app.hopline.data.Store
import app.hopline.mesh.Attachment
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.Group
import app.hopline.mesh.Loc
import app.hopline.mesh.Message
import app.hopline.mesh.NearbyTransport
import app.hopline.mesh.Quote
import app.hopline.mesh.Router
import app.hopline.mesh.RouterListener

/**
 * One per process. Owns the router + radio for the ACTIVE group, keeps them alive via MeshService,
 * and exposes a `version` LiveData that screens observe to redraw. Other groups sleep on disk and
 * wake instantly on switch. Everything here runs on the main thread except file byte-work.
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
    /** Which chat is on screen: null, GROUP, or a node id. Used to skip notifications for what you're looking at. */
    var openChat: String? = null
    const val GROUP = "*"
    var appVisible = false
    var radioProblem: String = ""

    private var changePosted = false
    private var savePosted = false
    private var netCallbackRegistered = false

    fun init(application: Application) {
        app = application
        store = Store(application)
        Notifications.createChannels(application)
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
        tick()
    }

    fun stopRadio() { transport?.stop() }

    private fun build(group: Group) {
        val me = store.identity()
        val t = NearbyTransport(app, group, me)
        val r = Router(me, group, t, listener, Blobs.chunkStore(app, group.fingerprint))
        store.loadState(group.fingerprint)?.let { try { r.restore(it) } catch (e: Exception) { Log.w(TAG, "state restore failed", e) } }
        t.events = object : NearbyTransport.Events {
            override fun onLinkUp(linkId: String, nodeId: String, name: String) { r.onLinkUp(linkId, nodeId, name); changed() }
            override fun onLinkDown(linkId: String) { r.onLinkDown(linkId); changed() }
            override fun onBytes(linkId: String, bytes: ByteArray) { r.onBytes(linkId, bytes) }
            override fun onPayloadSent(payloadId: Long) { r.onPayloadSent(payloadId) }
            override fun onPayloadFailed(payloadId: Long) { r.onPayloadFailed(payloadId) }
            override fun onStatus(text: String) { radioProblem = text; changed() }
        }
        router = r; transport = t
        finishInterruptedFiles(r, group.fingerprint)
    }

    /** After a restart: files whose last pieces arrived while we were dead get assembled now. */
    private fun finishInterruptedFiles(r: Router, fp: String) {
        val pending = r.messages.filter { it.att != null }
        if (pending.isEmpty()) return
        Thread {
            for (m in pending) {
                val att = m.att ?: continue
                if (Blobs.fileFor(app, fp, att).exists() || Blobs.assemble(app, fp, r, m)) {
                    handler.post { if (router === r) r.markFileReady(att.fid) }
                }
            }
            handler.post { changed() }
        }.start()
    }

    /** Point the radio at another saved group. Nothing is deleted; the old group sleeps on disk. */
    fun switchGroup(code: String) {
        if (router?.group?.code == code) return
        stopLiveLocation()   // a position shared with one group must not leak into another
        saveNow()
        transport?.stop()
        router = null; transport = null
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
        router = null; transport = null
        Blobs.deleteGroup(app, leaving.fingerprint)
        store.removeGroup(leaving.code)
        if (store.group() != null) ensureRunning()
        else app.stopService(Intent(app, MeshService::class.java))
        changed()
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
     * crowd exactly like presence itself does.
     */
    fun startLiveLocation(minutes: Int) {
        val r = router ?: return
        liveLocationUntil = System.currentTimeMillis() + minutes * 60_000L
        if (liveWatchStop == null) liveWatchStop = Locations.watch(app, minTimeMs = 10_000L) { pushMyLocation() }
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
        if (wasSharing) changed()
    }

    /** Move the freshest fix into the router, where presence picks it up. */
    private fun pushMyLocation() {
        val r = router ?: return
        if (!liveLocationActive()) { if (liveWatchStop != null) stopLiveLocation(); return }
        val l = Locations.lastKnown(app)
        // A phone that stopped getting fixes (indoors, GPS off) must not keep beaconing its
        // last position as "live" — beaconing nothing is honest, a stale ghost is a lie.
        r.myLoc = if (l != null && System.currentTimeMillis() - l.time <= 10 * 60_000)
            Loc.of(l.latitude, l.longitude, l.accuracy.toInt()) else null
    }

    // ------------------------------------------------------------------ sending files

    /**
     * Shrink and send a photo. Byte-work runs off the main thread; `done` is called on the main
     * thread with null on success or a problem description.
     */
    fun sendImage(uri: Uri, caption: String, to: String?, quote: Quote? = null, done: (String?) -> Unit) {
        val r0 = router ?: return done("No group")
        val fp = r0.group.fingerprint
        Thread {
            val prep = Blobs.prepareImage(app, uri)
            handler.post {
                // The user may have switched groups while we were shrinking the photo — a photo
                // meant for one group must never be flooded into another.
                if (router !== r0) { done("Group changed — photo not sent."); return@post }
                if (prep == null) { done("Couldn't read that photo."); return@post }
                val pieces = Blobs.chunkify(prep.bytes)
                val att = Attachment.make(Crypto.randomId(12), prep.name, prep.mime,
                    prep.bytes.size.toLong(), pieces.size, prep.width, prep.height, prep.thumbB64)
                Blobs.saveOwn(app, fp, att, prep.bytes)
                r0.sendFile(att, pieces, caption, to, quote)
                changed()
                done(null)
            }
        }.start()
    }

    /** Send a picked document (or a recorded voice note) as-is. Same threading contract as sendImage. */
    fun sendFileBytes(picked: Blobs.PickedFile, caption: String, to: String?, durSec: Int = 0, quote: Quote? = null, done: (String?) -> Unit) {
        val r0 = router ?: return done("No group")
        val fp = r0.group.fingerprint
        Thread {
            val pieces = Blobs.chunkify(picked.bytes)
            handler.post {
                if (router !== r0) { done("Group changed — file not sent."); return@post }
                val att = Attachment.make(Crypto.randomId(12), picked.name, picked.mime,
                    picked.bytes.size.toLong(), pieces.size, 0, 0, "", durSec)
                Blobs.saveOwn(app, fp, att, picked.bytes)
                r0.sendFile(att, pieces, caption, to, quote)
                changed()
                done(null)
            }
        }.start()
    }

    // ------------------------------------------------------------------ unread

    fun markRead(chat: String) {
        val fp = fingerprint() ?: return
        store.setLastRead(fp, chat, System.currentTimeMillis())
        Notifications.clearChat(app, chat)
    }

    /** Unread counts compare LOCAL arrival times — sender clocks drift, and carried messages
     *  can be hours old by their own clock while still brand new to this phone. */
    fun unreadCount(chat: String): Int = unreadCounts()[chat] ?: 0

    /** All chats' unread counts in one pass over the message list. */
    fun unreadCounts(): Map<String, Int> {
        val r = router ?: return emptyMap()
        val fp = fingerprint() ?: return emptyMap()
        val counts = HashMap<String, Int>()
        val since = HashMap<String, Long>()
        for (m in r.messages) {
            if (m.from == r.me.id) continue
            val chat = if (m.isGroup) GROUP else if (m.to == r.me.id) m.from else continue
            val limit = since.getOrPut(chat) { store.lastRead(fp, chat) }
            if (m.arrivedAt > limit) counts[chat] = (counts[chat] ?: 0) + 1
        }
        return counts
    }

    // ------------------------------------------------------------------ periodic

    private var lastTick = 0L
    fun tick() {
        val r = router ?: return
        r.battery = batteryPercent()
        r.hasInternet = internetNow()
        if (liveLocationUntil != 0L) pushMyLocation()   // stops itself once the time is up
        r.tick()
        lastTick = System.currentTimeMillis()
        // Watchdog: phones visible, nothing linked for 4 minutes -> bounce the Bluetooth stack.
        transport?.let { t ->
            if (t.running && t.connectedCount() == 0 && t.visibleCount() > 0 && System.currentTimeMillis() - maxOf(t.lastLinkAt, startedAt) > 240_000) {
                startedAt = System.currentTimeMillis(); t.restart()
            }
        }
        save()
    }
    private var startedAt = System.currentTimeMillis()

    private fun changed() {
        if (changePosted) return
        changePosted = true
        handler.post { changePosted = false; version.value = (version.value ?: 0) + 1 }
        save()
    }

    private fun save() {
        if (savePosted) return
        savePosted = true
        handler.postDelayed({ savePosted = false; saveNow() }, 2000)
    }

    private fun saveNow() {
        val r = router ?: return
        store.saveState(r.group.fingerprint, r.snapshot())
    }

    // ------------------------------------------------------------------ router events

    private val listener = object : RouterListener {
        override fun onChanged() { changed() }

        override fun onMessage(m: Message) {
            changed()
            val chat = if (m.to != null) m.from else GROUP
            if (appVisible && openChat == chat) { markRead(chat); return }
            Notifications.message(app, m)
        }

        override fun onFileReady(m: Message) {
            val r = router ?: return
            val fp = r.group.fingerprint
            Thread {
                val ok = Blobs.assemble(app, fp, r, m)
                handler.post {
                    // A failed assemble (I/O, storage full) must not stay latched: un-mark so the
                    // next chunk arrival or restart retries.
                    if (!ok && router === r) m.att?.let { r.unmarkFileReady(it.fid) }
                    changed()
                }
            }.start()
        }

        override fun onErrandRequest(e: Errand) {
            changed()
            when (e.type) {
                Errand.READ -> Errands.run(app, e) { ok, title, text -> router?.completeErrand(e.id, ok, title, text) }
                Errand.SEND -> Notifications.sendRequest(app, e)   // a human has to tap Send; the card is in the app
            }
        }

        override fun onGroupNamed(name: String) {
            store.activeGroup()?.let { store.renameGroup(it.code, name) }
            changed()
        }

        override fun onLog(text: String) { Log.d(TAG, text) }
    }

    // ------------------------------------------------------------------ device state

    fun bluetoothOn(): Boolean = try {
        val adapter = (app.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        val on = adapter != null && (adapter.isEnabled || adapter.state == BluetoothAdapter.STATE_ON)
        on
    } catch (e: Exception) { Log.w(TAG, "bluetooth check", e); false }
    fun wifiOn(): Boolean = try { (app.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (e: Exception) { false }

    fun internetNow(): Boolean {
        val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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
        if (now != r.hasInternet) { r.hasInternet = now; r.sendPresence(); changed() }
    }

    private fun batteryPercent(): Int = try {
        (app.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    } catch (e: Exception) { -1 }

    /** One plain-English line for the top of the screen. */
    fun statusLine(): String {
        val r = router ?: return ""
        if (!bluetoothOn()) return "Turn on Bluetooth to find your group"
        if (!wifiOn()) return "Turn on WiFi to find your group"
        if (radioProblem.isNotEmpty()) return radioProblem
        val links = r.authedLinks().size
        val inRange = r.peopleInRange()
        return when {
            links == 0 && inRange == 0 -> "Looking for your group's phones…"
            inRange <= 1 -> "1 person in range"
            else -> "$inRange people in range"
        }
    }

    val isTiramisu get() = Build.VERSION.SDK_INT >= 33
}
