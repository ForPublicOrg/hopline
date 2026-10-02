package app.hopline.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.common.api.CommonStatusCodes
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
 *  - keeps at most MAX_LINKS links, counting dials still in flight
 *  - duty-cycles discovery to save battery once we have a couple of links
 *  - retries failed connections with backoff, notices sessions that died silently,
 *    and restarts the stack if it wedges
 *  - hands the router each connection's authentication token, so link proofs can't be relayed
 *
 * The advertised name carries no display name: anyone scanning nearby would otherwise collect
 * the names of every Hopline user in range. Names travel inside the authenticated link instead.
 */
class NearbyTransport(context: Context, private val group: Group, private val me: Identity) : Transport {

    interface Events {
        fun onLinkUp(linkId: String, nodeId: String, name: String, token: String)
        fun onLinkDown(linkId: String)
        fun onBytes(linkId: String, bytes: ByteArray)
        fun onPayloadSent(payloadId: Long)
        fun onPayloadFailed(payloadId: Long)
        fun onStatus(text: String)
    }

    var events: Events? = null
    private val client = Nearby.getConnectionsClient(context.applicationContext)
    private val handler = Handler(Looper.getMainLooper())

    private class Endpoint(val id: String, val nodeId: String) {
        var state = FOUND
        var attempts = 0
        /** When [attempts] ran out: a phone that stays in range gets a fresh start after a rest. */
        var exhaustedAt = 0L
        var lost = false
        var token = ""
    }

    private val endpoints = HashMap<String, Endpoint>()
    @Volatile var running = false; private set
    /** Bumped on every start/stop: callbacks from an earlier session are ignored. */
    private var generation = 0
    private var discovering = false
    private var advertising = false
    var lastLinkAt = 0L; private set
    var startedAt = 0L; private set
    var problem: String? = null; private set

    fun connectedCount(): Int = endpoints.values.count { it.state == CONNECTED }
    fun connectingCount(): Int = endpoints.values.count { it.state == CONNECTING }
    fun visibleCount(): Int = endpoints.size

    /**
     * Hopline phones in range that advertise ANOTHER group's code. With nobody of our own around,
     * that is usually a mistyped code ("tiger river lamp" vs "tiger rivers lamp") — worth saying so.
     */
    private val otherGroups = HashMap<String, Long>()
    fun otherGroupNearby(): Boolean {
        val now = System.currentTimeMillis()
        otherGroups.values.removeAll { now - it > OTHER_GROUP_MS }
        return otherGroups.isNotEmpty()
    }
    private fun activeCount(): Int = endpoints.values.count { it.state != FOUND }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (running) return
        running = true
        generation++
        startedAt = System.currentTimeMillis()
        clearProblem()
        advertise()
        discoveryLoop()
    }

    fun stop() {
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        try { client.stopAllEndpoints(); client.stopAdvertising(); client.stopDiscovery() } catch (e: Exception) { }
        advertising = false; discovering = false
        for (id in endpoints.filterValues { it.state == CONNECTED }.keys.toList()) events?.onLinkDown(id)
        endpoints.clear()
        otherGroups.clear()
    }

    /** Bluetooth stacks wedge. When nothing has linked for a long while despite phones being visible, bounce it. */
    fun restart() {
        Log.i(TAG, "restarting nearby stack")
        stop()
        val gen = generation
        handler.postDelayed({ if (generation == gen) start() }, 1500)
    }

    /** My display name changed. It isn't advertised (see the class note), so nothing to redo here. */
    fun renamed() {}

    private fun endpointName(): String = "1|${group.fingerprint}|${me.id}|"

    private fun parse(name: String): Endpoint? {
        val parts = name.split("|")
        if (parts.size < 4 || parts[0] != "1") return null
        if (parts[1] != group.fingerprint) return null
        val nodeId = parts[2]; if (nodeId.isEmpty() || nodeId == me.id || nodeId.length > 40) return null
        return Endpoint("", nodeId)
    }

    private fun clearProblem() {
        if (problem != null) { problem = null; events?.onStatus("") }
    }

    private fun report(e: Exception) {
        val text = friendly(e)
        if (problem != text) { problem = text; events?.onStatus(text) }
    }

    /** One pending retry at most: each discovery cycle also re-asserts advertising, and stacking a
     *  fresh 10 s retry loop per cycle while it keeps failing would grow without bound. */
    private val advertiseRetry = Runnable { if (running) advertise() }

    private fun advertise() {
        if (!running || advertising) return
        val gen = generation
        val opts = AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startAdvertising(endpointName(), SERVICE_ID, lifecycle, opts)
            .addOnSuccessListener { if (gen == generation) { advertising = true; clearProblem(); Log.i(TAG, "advertising") } }
            .addOnFailureListener { e ->
                if (gen != generation) return@addOnFailureListener
                advertising = false
                if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_ADVERTISING) { advertising = true; return@addOnFailureListener }
                report(e)
                Log.w(TAG, "advertise failed: $e")
                handler.removeCallbacks(advertiseRetry)
                handler.postDelayed(advertiseRetry, 10_000)
            }
    }

    private fun discoveryLoop() {
        if (!running) return
        // A Play-services restart can end advertising without telling us: re-assert it every
        // cycle (an "already advertising" answer counts as success).
        advertising = false
        advertise()
        startDiscovery()
        handler.postDelayed({
            stopDiscovery()
            val pause = if (connectedCount() >= 2) 45_000L else 8_000L
            handler.postDelayed({ discoveryLoop() }, pause)
        }, 30_000)
    }

    private fun startDiscovery() {
        if (!running || discovering) return
        val gen = generation
        val opts = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
        client.startDiscovery(SERVICE_ID, discovery, opts)
            .addOnSuccessListener { if (gen == generation) { discovering = true; clearProblem() } }
            .addOnFailureListener { e ->
                if (gen != generation) return@addOnFailureListener
                if (statusCode(e) == ConnectionsStatusCodes.STATUS_ALREADY_DISCOVERING) { discovering = true; return@addOnFailureListener }
                report(e)
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
            if (!running) return
            val parsed = parse(info.endpointName)
            if (parsed == null) {
                if (info.endpointName.startsWith("1|")) otherGroups[endpointId] = System.currentTimeMillis()
                return
            }
            val existing = endpoints[endpointId]
            if (existing != null && existing.state != FOUND) { existing.lost = false; return }
            val ep = Endpoint(endpointId, parsed.nodeId).also { it.attempts = existing?.attempts ?: 0; it.exhaustedAt = existing?.exhaustedAt ?: 0L }
            endpoints[endpointId] = ep
            Log.i(TAG, "found ${parsed.nodeId}")
            clearProblem()
            maybeConnect(ep)
        }

        override fun onEndpointLost(endpointId: String) {
            otherGroups.remove(endpointId)
            val ep = endpoints[endpointId] ?: return
            if (ep.state == FOUND) endpoints.remove(endpointId) else ep.lost = true
        }
    }

    private fun alreadyLinkedTo(nodeId: String, except: String? = null): Boolean =
        endpoints.values.any { it.nodeId == nodeId && it.state != FOUND && it.id != except }

    private fun maybeConnect(ep: Endpoint) {
        if (!running || ep.state != FOUND) return
        if (alreadyLinkedTo(ep.nodeId)) return
        if (activeCount() >= MAX_LINKS) return
        if (ep.attempts >= MAX_ATTEMPTS) {
            // Tried hard and failed (a crowded radio, a wedged stack). Not forever — a phone that
            // never leaves range is never "rediscovered" — so after a rest, start over.
            val now = System.currentTimeMillis()
            if (ep.exhaustedAt == 0L) ep.exhaustedAt = now
            if (now - ep.exhaustedAt < RETRY_REST_MS) return
            ep.attempts = 0; ep.exhaustedAt = 0L
        }
        // Lower id dials; the other side waits ~10 s and dials only if nothing happened (one side may not have discovered us).
        val delay = if (me.id < ep.nodeId) 0L else 10_000L
        val gen = generation
        handler.postDelayed({
            if (gen == generation && running && ep.state == FOUND && endpoints[ep.id] === ep &&
                !alreadyLinkedTo(ep.nodeId) && activeCount() < MAX_LINKS) request(ep)
        }, delay)
    }

    private fun request(ep: Endpoint) {
        ep.state = CONNECTING
        val gen = generation
        client.requestConnection(endpointName(), ep.id, lifecycle)
            .addOnFailureListener { e ->
                if (gen != generation || endpoints[ep.id] !== ep || ep.state != CONNECTING) return@addOnFailureListener
                val code = statusCode(e)
                Log.w(TAG, "request to ${ep.nodeId} failed: $e")
                when (code) {
                    ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> {
                        // We lost track of a live session: drop it and start clean rather than
                        // sit on a half-link the router never heard about.
                        try { client.disconnectFromEndpoint(ep.id) } catch (x: Exception) { }
                        ep.state = FOUND
                        handler.postDelayed({ if (gen == generation) maybeConnect(ep) }, 3_000)
                        return@addOnFailureListener
                    }
                    ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN -> { endpoints.remove(ep.id); return@addOnFailureListener }
                }
                ep.state = FOUND; ep.attempts++
                if (ep.lost) { endpoints.remove(ep.id); return@addOnFailureListener }
                val backoff = minOf(60_000L, 5_000L * ep.attempts)
                handler.postDelayed({ if (gen == generation) maybeConnect(ep) }, backoff)
            }
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            if (!running) { try { client.rejectConnection(endpointId) } catch (e: Exception) { }; return }
            val parsed = parse(info.endpointName)
            if (parsed == null) { client.rejectConnection(endpointId); return }
            // One link per phone, and a hard ceiling so a crowd can't pile links onto us
            // (a little slack lets two phones that are both "full" still bridge).
            if (alreadyLinkedTo(parsed.nodeId, except = endpointId) || activeCount() >= MAX_LINKS + 2 && endpoints[endpointId]?.state != CONNECTING) {
                client.rejectConnection(endpointId); return
            }
            val ep = endpoints[endpointId] ?: Endpoint(endpointId, parsed.nodeId).also { endpoints[endpointId] = it }
            ep.state = CONNECTING
            ep.token = try { info.rawAuthenticationToken?.joinToString("") { "%02x".format(it) } ?: "" } catch (e: Exception) { "" }
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ep = endpoints[endpointId] ?: return
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                ep.state = CONNECTED; ep.attempts = 0; ep.lost = false; lastLinkAt = System.currentTimeMillis()
                Log.i(TAG, "linked ${ep.nodeId}")
                events?.onLinkUp(endpointId, ep.nodeId, "", ep.token)
            } else {
                Log.w(TAG, "link to ${ep.nodeId} failed: ${result.status}")
                ep.state = FOUND; ep.attempts++
                if (ep.lost) { endpoints.remove(endpointId); return }
                val gen = generation
                handler.postDelayed({ if (gen == generation) maybeConnect(ep) }, minOf(60_000L, 5_000L * ep.attempts))
            }
        }

        override fun onDisconnected(endpointId: String) {
            val ep = endpoints.remove(endpointId)
            Log.i(TAG, "unlinked ${ep?.nodeId}")
            events?.onLinkDown(endpointId)
            // A slot just freed up: give a phone we couldn't fit earlier its turn.
            for (other in endpoints.values.filter { it.state == FOUND }) maybeConnect(other)
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
        client.sendPayload(linkId, p).addOnFailureListener { e ->
            events?.onPayloadFailed(p.id)
            // The session died without a disconnect callback (e.g. Play services restarted):
            // treat the link as gone so the router stops counting it and we can re-link.
            val code = statusCode(e)
            if ((code == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN || code == ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT) &&
                endpoints[linkId] === ep) {
                endpoints.remove(linkId)
                try { client.disconnectFromEndpoint(linkId) } catch (x: Exception) { }
                events?.onLinkDown(linkId)
            }
        }
        return p.id
    }

    override fun disconnect(linkId: String) {
        endpoints.remove(linkId)
        try { client.disconnectFromEndpoint(linkId) } catch (e: Exception) { }
    }

    // ------------------------------------------------------------------ helpers

    private fun statusCode(e: Exception): Int =
        (e as? com.google.android.gms.common.api.ApiException)?.statusCode ?: -1

    @Suppress("DEPRECATION")   // older Play services still report these codes
    private fun friendly(e: Exception): String = when (statusCode(e)) {
        ConnectionsStatusCodes.STATUS_BLUETOOTH_ERROR -> "Bluetooth isn't working. Try turning it off and on."
        ConnectionsStatusCodes.STATUS_RADIO_ERROR -> "Please turn on Bluetooth and WiFi."
        ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH, ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION, ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
        ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE, ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
        ConnectionsStatusCodes.MISSING_PERMISSION_RECORD_AUDIO -> "Hopline needs the Nearby devices permission. Open Settings → Apps → Hopline → Permissions."
        CommonStatusCodes.API_NOT_CONNECTED, CommonStatusCodes.SERVICE_VERSION_UPDATE_REQUIRED, CommonStatusCodes.SERVICE_DISABLED ->
            "Google Play services needs an update (or is turned off) — Hopline uses it to link phones."
        else -> "Can't search for phones right now. Is Bluetooth on?"
    }

    companion object {
        private const val TAG = "Hopline/Nearby"
        const val SERVICE_ID = "app.hopline.mesh.v1"
        const val MAX_LINKS = 6
        private const val OTHER_GROUP_MS = 120_000L
        private const val MAX_ATTEMPTS = 6
        private const val RETRY_REST_MS = 5 * 60_000L
        private const val FOUND = 0
        private const val CONNECTING = 1
        private const val CONNECTED = 2
    }
}
