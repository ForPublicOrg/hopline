package app.hopline.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import app.hopline.data.Store
import app.hopline.mesh.Envelope
import app.hopline.mesh.Errand
import app.hopline.mesh.Group
import app.hopline.mesh.Message
import app.hopline.mesh.NearbyTransport
import app.hopline.mesh.Router
import app.hopline.mesh.RouterListener

/**
 * One per process. Owns the router + radio, keeps them alive via MeshService, and exposes a
 * `version` LiveData that screens observe to redraw. Everything here runs on the main thread.
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

    /** Build the router for the current group (idempotent) and start the background service. */
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
        val r = Router(me, group, t, listener)
        store.loadState()?.let { try { r.restore(it) } catch (e: Exception) { Log.w(TAG, "state restore failed", e) } }
        t.events = object : NearbyTransport.Events {
            override fun onLinkUp(linkId: String, nodeId: String, name: String) { r.onLinkUp(linkId, nodeId, name); changed() }
            override fun onLinkDown(linkId: String) { r.onLinkDown(linkId); changed() }
            override fun onBytes(linkId: String, bytes: ByteArray) { r.onBytes(linkId, bytes) }
            override fun onPayloadSent(payloadId: Long) { r.onPayloadSent(payloadId) }
            override fun onPayloadFailed(payloadId: Long) { r.onPayloadFailed(payloadId) }
            override fun onStatus(text: String) { radioProblem = text; changed() }
        }
        router = r; transport = t
    }

    fun leaveGroup() {
        app.stopService(Intent(app, MeshService::class.java))
        transport?.stop()
        router = null; transport = null
        store.clearGroup()
        changed()
    }

    // ------------------------------------------------------------------ periodic

    private var lastTick = 0L
    fun tick() {
        val r = router ?: return
        r.battery = batteryPercent()
        r.hasInternet = internetNow()
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
        handler.postDelayed({ savePosted = false; router?.let { store.saveState(it.snapshot()) } }, 2000)
    }

    // ------------------------------------------------------------------ router events

    private val listener = object : RouterListener {
        override fun onChanged() { changed() }

        override fun onMessage(m: Message) {
            changed()
            val chat = if (m.kind == Envelope.DM) m.from else GROUP
            if (appVisible && openChat == chat) return
            Notifications.message(app, m)
        }

        override fun onSos(m: Message) {
            changed()
            Notifications.sos(app, m)
        }

        override fun onErrandRequest(e: Errand) {
            changed()
            when (e.type) {
                Errand.WEATHER, Errand.READ -> Errands.run(app, e) { ok, title, text -> router?.completeErrand(e.id, ok, title, text) }
                Errand.SEND -> Notifications.sendRequest(app, e)   // a human has to tap Send; the card is in the app
            }
        }

        override fun onGroupNamed(name: String) {
            store.group()?.let { store.setGroup(it.code, name) }
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
