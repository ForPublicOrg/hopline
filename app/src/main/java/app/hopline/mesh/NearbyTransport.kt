package app.hopline.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy

/**
 * Radio layer: Google Nearby Connections in P2P_CLUSTER mode. Every phone both advertises and
 * discovers, so a group forms a web of Bluetooth/WiFi links with no hotspot, no router, no setup.
 *
 * What this class does beyond the API:
 *  - only talks to phones advertising the same group fingerprint
 *  - avoids the "both sides connect at once" race (lower node id initiates; the other waits)
 *  - duty-cycles discovery to save battery once we have a couple of links
 *  - retries failed connections with backoff, and restarts the stack if it wedges
 */
class NearbyTransport(context: Context, private val group: Group, private val me: Identity) : Transport {

    interface Events {
        fun onLinkUp(linkId: String, nodeId: String, name: String)
        fun onLinkDown(linkId: String)
        fun onBytes(linkId: String, bytes: ByteArray)
        fun onPayloadSent(payloadId: Long)
        fun onPayloadFailed(payloadId: Long)
        fun onStatus(text: String)
    }

    var events: Events? = null
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    private class Endpoint(val id: String, val nodeId: String, val name: String) {
        var state = FOUND
        var attempts = 0
        var foundAt = System.currentTimeMillis()
    }

    private val endpoints = HashMap<String, Endpoint>()
    @Volatile var running = false; private set
    private var discovering = false
    private var advertising = false
    var lastLinkAt = 0L; private set
    var problem: String? = null; private set

    fun connectedCount(): Int = endpoints.values.count { it.state == CONNECTED }
    fun visibleCount(): Int = endpoints.size

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        problem = null
        advertise()
        discoveryLoop()
    }

    fun stop() {
        running = false
        handler.removeCallbacksAndMessages(null)
        try { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery() } catch (e: Exception) { }
        advertising = false; discovering = false
        for (id in endpoints.keys.toList()) events?.onLinkDown(id)
        endpoints.clear()
    }

    /** Bluetooth stacks wedge. When nothing has linked for a long while despite phones being visible, bounce it. */
    fun restart() {
        Log.i(TAG, "restarting nearby stack")
        stop(); handler.postDelayed({ start() }, 1500)
    }

    private fun endpointName(): String = "1|${group.fingerprint}|${me.id}|${me.name.take(20).replace("|", " ")}"

    private fun parse(name: String): Endpoint? {
        val parts = name.split("|")
        if (parts.size < 4 || parts[0] != "1") return null
        if (parts[1] != group.fingerprint) return null
        val nodeId = parts[2]; if (nodeId.isEmpty() || nodeId == me.id) return null
        return Endpoint("", nodeId, parts.drop(3).joinToString("|"))
    }

    private fun advertise() {
        if (!running || advertising) return
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(endpointName(), SERVICE_ID, lifecycle, opts)
            .addOnSuccessListener { advertising = true; problem = null; Log.i(TAG, "advertising") }
            .addOnFailureListener { e ->
                advertising = false
                if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) { advertising = true; return@addOnFailureListener }
                problem = friendly(e); events?.onStatus(problem ?: "")
                Log.w(TAG, "advertise failed: $e")
                handler.postDelayed({ advertise() }, 10_000)
            }
    }

    private fun discoveryLoop() {
        if (!running) return
        startDiscovery()
        handler.postDelayed({
            stopDiscovery()
            val pause = if (connectedCount() >= 2) 45_000L else 8_000L
            handler.postDelayed({ discoveryLoop() }, pause)
        }, 30_000)
    }

    private fun startDiscovery() {
        if (!running || discovering) return
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, discovery, opts)
            .addOnSuccessListener { discovering = true; problem = null }
            .addOnFailureListener { e ->
                if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) { discovering = true; return@addOnFailureListener }
                problem = friendly(e); events?.onStatus(problem ?: "")
                Log.w(TAG, "discovery failed: $e")
            }
    }

    private fun stopDiscovery() {
        if (!discovering) return
        discovering = false
        try { client.stopDiscovery() } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ finding & linking

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val parsed = parse(info.endpointName) ?: return
            val existing = endpoints[endpointId]
            if (existing != null && existing.state != FOUND) return
            val ep = Endpoint(endpointId, parsed.nodeId, parsed.name)
            endpoints[endpointId] = ep
            Log.i(TAG, "found ${parsed.name}")
            events?.onStatus("")
            maybeConnect(ep)
        }

        override fun onEndpointLost(endpointId: String) {
            val ep = endpoints[endpointId] ?: return
            if (ep.state == FOUND) endpoints.remove(endpointId)
        }
    }

    private fun alreadyLinkedTo(nodeId: String): Boolean =
        endpoints.values.any { it.nodeId == nodeId && it.state != FOUND }

    private fun maybeConnect(ep: Endpoint) {
        if (!running || ep.state != FOUND) return
        if (alreadyLinkedTo(ep.nodeId)) return
        if (connectedCount() >= MAX_LINKS) return
        // Lower id dials; the other side waits ~10 s and dials only if nothing happened (one side may not have discovered us).
        val delay = if (me.id < ep.nodeId) 0L else 10_000L
        handler.postDelayed({ if (ep.state == FOUND && endpoints[ep.id] === ep && !alreadyLinkedTo(ep.nodeId)) request(ep) }, delay)
    }

    private fun request(ep: Endpoint) {
        ep.state = CONNECTING
        client.requestConnection(endpointName(), ep.id, lifecycle)
            .addOnFailureListener { e ->
                val code = statusCode(e)
                Log.w(TAG, "request to ${ep.name} failed: $e")
                if (code == ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT) { ep.state = CONNECTED; return@addOnFailureListener }
                ep.state = FOUND; ep.attempts++
                val backoff = minOf(60_000L, 5_000L * ep.attempts)
                handler.postDelayed({ maybeConnect(ep) }, backoff)
            }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val parsed = parse(info.endpointName)
            if (parsed == null) { client.rejectConnection(endpointId); return }
            val ep = endpoints[endpointId] ?: Endpoint(endpointId, parsed.nodeId, parsed.name).also { endpoints[endpointId] = it }
            ep.state = CONNECTING
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ep = endpoints[endpointId] ?: return
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                ep.state = CONNECTED; ep.attempts = 0; lastLinkAt = System.currentTimeMillis()
                Log.i(TAG, "linked ${ep.name}")
                events?.onLinkUp(endpointId, ep.nodeId, ep.name)
            } else {
                Log.w(TAG, "link to ${ep.name} failed: ${result.status}")
                ep.state = FOUND; ep.attempts++
                handler.postDelayed({ maybeConnect(ep) }, minOf(60_000L, 5_000L * ep.attempts))
            }
        }

        override fun onDisconnected(endpointId: String) {
            val ep = endpoints.remove(endpointId)
            Log.i(TAG, "unlinked ${ep?.name}")
            events?.onLinkDown(endpointId)
        }
    }

    private val payloads = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            events?.onBytes(endpointId, bytes)
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            when (update.status) {
                PayloadTransferUpdate.Status.SUCCESS -> events?.onPayloadSent(update.payloadId)
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> events?.onPayloadFailed(update.payloadId)
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ Transport

    override fun send(linkId: String, bytes: ByteArray): Long {
        val ep = endpoints[linkId]
        if (ep == null || ep.state != CONNECTED) return -1
        val p = Payload.fromBytes(bytes)
        client.sendPayload(linkId, p).addOnFailureListener { events?.onPayloadFailed(p.id) }
        return p.id
    }

    override fun disconnect(linkId: String) {
        endpoints.remove(linkId)
        try { client.disconnectFromEndpoint(linkId) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ helpers

    private fun statusCode(e: Exception): Int =
        (e as? com.google.android.gms.common.api.ApiException)?.statusCode ?: -1

    private fun friendly(e: Exception): String = when (statusCode(e)) {
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth isn't working. Try turning it off and on."
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Please turn on Bluetooth and WiFi."
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION, ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE, ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_RECORD_AUDIO -> "Hopline needs the Nearby devices permission. Open Settings → Apps → Hopline → Permissions."
        else -> "Can't search for phones right now. Is Bluetooth on?"
    }

    companion object {
        private const val TAG = "Hopline/Nearby"
        const val SERVICE_ID = "app.hopline.mesh.v1"
        const val MAX_LINKS = 6
        private const val FOUND = 0
        private const val CONNECTING = 1
        private const val CONNECTED = 2
    }
}
