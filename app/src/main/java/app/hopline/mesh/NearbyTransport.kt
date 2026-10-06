package app.hopline.mesh

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import app.hopline.core.Crypto
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
 *  - only talks to phones advertising the same group air tag, and counts the phones of this
 *    group still on an older Hopline (which it never links to) so the person can be told
 *  - avoids the "both sides connect at once" race (lower node id initiates; the other waits)
 *  - keeps at most MAX_LINKS proven links; a connection counts only once the router says its
 *    phone proved itself ([authed]) — until then it is "unproven", may not block anyone, and is
 *    let go after a few seconds, or sooner when newer ones need the room ([NearbyRules])
 *  - duty-cycles discovery to save battery once we have a couple of links
 *  - retries failed connections with backoff, notices sessions that died silently,
 *    and restarts the stack if it wedges
 *  - hands the router each connection's authentication token, so link proofs can't be relayed;
 *    a connection without one is refused
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
        /** Its phone proved itself on this connection (the router said so): from now on it is a link. */
        var authed = false
        /** When this connection started (dialled, or knocked on our door): the oldest unproven one goes first. */
        var since = 0L
        /** Lets the connection go if it is still unproven when it fires. */
        var deadline: Runnable? = null
    }

    private val endpoints = HashMap<String, Endpoint>()
    /**
     * Payloads handed to the radio that haven't gone (or failed) yet, and the link each went on:
     * what [finish] waits for. A link that goes takes its own with it; bounded all the same, in
     * case the radio never says what became of one.
     */
    private val inFlight = LinkedHashMap<Long, String>()
    @Volatile var running = false; private set
    /** Bumped on every start/stop: callbacks from an earlier session are ignored. */
    private var generation = 0
    private var discovering = false
    private var advertising = false
    var lastLinkAt = 0L; private set
    var startedAt = 0L; private set
    var problem: String? = null; private set
    /** What a phone of this group still on a Hopline from before 2.4 advertises (see [olderPhonesNearby]). */
    private val legacyFp = Crypto.legacyFingerprint(group.code)

    /** Proven links: what the rest of the app means by "linked". */
    fun connectedCount(): Int = endpoints.values.count { it.state == CONNECTED && it.authed }
    /** Connections still being made or proved. */
    fun connectingCount(): Int = endpoints.values.count { it.state != FOUND && !it.authed }
    fun visibleCount(): Int = endpoints.size

    /**
     * Hopline phones in range that advertise ANOTHER group's code. With nobody of our own around,
     * that is usually a mistyped code ("tiger river lamp hat" vs "tiger rivers lamp hat") — worth saying so.
     */
    private val otherGroups = HashMap<String, Long>()
    fun otherGroupNearby(): Boolean {
        val now = System.currentTimeMillis()
        otherGroups.values.removeAll { now - it > OTHER_GROUP_MS }
        return otherGroups.isNotEmpty()
    }

    /**
     * Phones in range that are in THIS group but still run a Hopline from before 2.4. They can't
     * link with this one (nothing they send could be read, nor the other way round), so they are
     * never connected to — only counted, so the person can be told one of them needs to update.
     */
    private val olderPhones = HashMap<String, Long>()
    fun olderPhonesNearby(): Int {
        val now = System.currentTimeMillis()
        olderPhones.values.removeAll { now - it > OTHER_GROUP_MS }
        return olderPhones.size
    }

    private fun provenCount(): Int = endpoints.values.count { it.state != FOUND && it.authed }
    private fun unproven(except: String? = null): Map<String, Long> =
        endpoints.values.filter { it.state != FOUND && !it.authed && it.id != except }.associate { it.id to it.since }

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
        if (finishing) hangUpAll()
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        // The radio's client is the app's: while an earlier group's radio is still getting its
        // goodbye out ([finish]), only this one's own links go.
        try {
            if (lingering == 0) client.stopAllEndpoints() else for (id in endpoints.keys) if (id !in held) client.disconnectFromEndpoint(id)
            client.stopAdvertising(); client.stopDiscovery()
        } catch (e: Exception) { }
        advertising = false; discovering = false
        for (id in endpoints.filterValues { it.state == CONNECTED }.keys.toList()) events?.onLinkDown(id)
        endpoints.clear()
        otherGroups.clear()
        olderPhones.clear()
        inFlight.clear()
    }

    /**
     * Leaving the group: everything handed to the radio that hasn't gone yet is called off — so
     * what is said on the way out, sent next, doesn't wait behind a photo's pieces or a backlog,
     * and get cut off by [finish]'s limit (Nearby sends a link's payloads in turn). Just what a
     * leave always did to them: the group carries its backlog anyway, and a message of mine still
     * on its way reads "Not sent" in the kept chat — truly, as nothing would ever say it went.
     */
    fun callOff() {
        for (pid in inFlight.keys.toList()) {
            inFlight.remove(pid)
            try { client.cancelPayload(pid) } catch (e: Exception) { }
        }
    }

    /**
     * Stop, but let what was just handed to the radio get out first — leaving the group, whose
     * goodbye ([Router.sayGoodbye]) is the last thing sent. Nothing new is looked for or let in from
     * now on and nothing reaches the router any more ([events] go); the links that are up stay up
     * until every payload sent on them has gone or failed, [ms] at most, and are then let go one by
     * one, by their own ids. Never [stop]'s stopAllEndpoints: the radio's client is the app's, and
     * the group the radio moves on to may already be using it by then.
     */
    fun finish(ms: Long) {
        if (!running) { stop(); return }
        running = false
        generation++
        handler.removeCallbacksAndMessages(null)
        events = null
        try { client.stopAdvertising(); client.stopDiscovery() } catch (e: Exception) { }
        advertising = false; discovering = false
        otherGroups.clear()
        olderPhones.clear()
        // Connections still being made carry nothing of ours: they go now.
        for (ep in endpoints.values.filter { it.state != CONNECTED }) hangUp(ep.id)
        if (inFlight.isEmpty() || endpoints.isEmpty()) { hangUpAll(); return }
        finishing = true
        lingering++
        holding.addAll(endpoints.keys); held.addAll(holding)
        val gen = generation
        handler.postDelayed({ if (gen == generation) hangUpAll() }, ms)
    }

    /** Set by [finish]: the last payloads are on their way, and the links go once they have. */
    private var finishing = false
    /** The links [finish] keeps up meanwhile (listed in [held] too, for the next group's radio to leave alone). */
    private val holding = HashSet<String>()

    /** A payload is done (gone or failed): if it was the last one [finish] was waiting for, the links go. */
    private fun sent(payloadId: Long) {
        if (inFlight.remove(payloadId) != null) doneWaiting()
    }

    /** [link] is gone: nothing more will be heard of what was sent on it. */
    private fun forget(link: String) {
        if (inFlight.values.removeAll { it == link }) doneWaiting()
    }

    private fun doneWaiting() { if (finishing && inFlight.isEmpty()) hangUpAll() }

    private fun hangUp(id: String) {
        endpoints.remove(id)?.deadline?.let { handler.removeCallbacks(it) }
        try { client.disconnectFromEndpoint(id) } catch (e: Exception) { }
        inFlight.values.removeAll { it == id }
    }

    private fun hangUpAll() {
        if (finishing) lingering--
        finishing = false
        handler.removeCallbacksAndMessages(null)
        for (id in endpoints.keys.toList()) hangUp(id)
        held.removeAll(holding); holding.clear()
        inFlight.clear()
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

    private fun endpointName(): String = NearbyRules.name(group.airTag, me.id)

    private fun parse(name: String): NearbyRules.Seen = NearbyRules.parse(name, group.airTag, legacyFp, me.id)

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
            val seen = parse(info.endpointName)
            if (seen !is NearbyRules.Seen.Member) {
                when (seen) {
                    NearbyRules.Seen.OtherGroup -> otherGroups[endpointId] = System.currentTimeMillis()
                    NearbyRules.Seen.Older -> olderPhones[endpointId] = System.currentTimeMillis()
                    else -> {}
                }
                return
            }
            val existing = endpoints[endpointId]
            if (existing != null && existing.state != FOUND) { existing.lost = false; return }
            val ep = Endpoint(endpointId, seen.nodeId).also { it.attempts = existing?.attempts ?: 0; it.exhaustedAt = existing?.exhaustedAt ?: 0L }
            endpoints[endpointId] = ep
            Log.i(TAG, "found ${seen.nodeId}")
            clearProblem()
            maybeConnect(ep)
        }

        override fun onEndpointLost(endpointId: String) {
            otherGroups.remove(endpointId)
            olderPhones.remove(endpointId)
            val ep = endpoints[endpointId] ?: return
            if (ep.state == FOUND) endpoints.remove(endpointId) else ep.lost = true
        }
    }

    /** A PROVEN link to [nodeId]: a name copied off the air must not keep the real phone out. */
    private fun alreadyLinkedTo(nodeId: String, except: String? = null): Boolean =
        endpoints.values.any { it.nodeId == nodeId && it.state != FOUND && it.authed && it.id != except }

    /**
     * A new unproven connection is starting: when the unproven ones fill their slots, the oldest
     * makes room ([NearbyRules.evict]), and this one gets its own few seconds to prove itself.
     */
    private fun startUnproven(ep: Endpoint) {
        NearbyRules.evict(unproven(except = ep.id))?.let { id -> endpoints[id]?.let { letGo(it, "made room for a newer connection") } }
        ep.since = System.currentTimeMillis()
        armDeadline(ep)
    }

    /** (Re)start [ep]'s few seconds to prove itself; if it hasn't by then, it goes. */
    private fun armDeadline(ep: Endpoint) {
        ep.deadline?.let { handler.removeCallbacks(it) }
        val gen = generation
        val r = Runnable { if (gen == generation && endpoints[ep.id] === ep && ep.state != FOUND && !ep.authed) letGo(ep, "never proved itself") }
        ep.deadline = r
        handler.postDelayed(r, NearbyRules.UNPROVEN_MS)
    }

    /**
     * Drop a connection this side decided against. The router hears of it if it ever heard of the
     * link, and the slot it held goes to a phone waiting for one.
     */
    private fun letGo(ep: Endpoint, why: String) {
        if (endpoints[ep.id] !== ep) return
        Log.i(TAG, "let go of ${ep.nodeId}: $why")
        endpoints.remove(ep.id)
        ep.deadline?.let { handler.removeCallbacks(it) }
        try { client.disconnectFromEndpoint(ep.id) } catch (e: Exception) { }
        forget(ep.id)
        if (ep.state == CONNECTED) events?.onLinkDown(ep.id)
        connectWaiting()
    }

    /** A slot just freed up: give a phone we couldn't fit earlier its turn. */
    private fun connectWaiting() {
        for (other in endpoints.values.filter { it.state == FOUND }) maybeConnect(other)
    }

    private fun maybeConnect(ep: Endpoint) {
        if (!running || ep.state != FOUND) return
        if (alreadyLinkedTo(ep.nodeId)) return
        if (provenCount() >= NearbyRules.MAX_LINKS) return
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
                !alreadyLinkedTo(ep.nodeId) && provenCount() < NearbyRules.MAX_LINKS) request(ep)
        }, delay)
    }

    private fun request(ep: Endpoint) {
        ep.state = CONNECTING
        startUnproven(ep)
        val gen = generation
        client.requestConnection(endpointName(), ep.id, lifecycle)
            .addOnFailureListener { e ->
                if (gen != generation || endpoints[ep.id] !== ep || ep.state != CONNECTING) return@addOnFailureListener
                val code = statusCode(e)
                Log.w(TAG, "request to ${ep.nodeId} failed: $e")
                when (code) {
                    ConnectionsStatusCodes.STATUS_ALREADY_CONNECTED_TO_ENDPOINT -> {
                        // We lost track of a live session: drop it and start clean rather than
                        // sit on a half-link the router never heard about — unless it is the link
                        // an earlier radio of this phone is getting a goodbye out on: that one goes
                        // by itself in a moment.
                        if (ep.id !in held) try { client.disconnectFromEndpoint(ep.id) } catch (x: Exception) { }
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
            val seen = parse(info.endpointName)
            // Every proof is bound to this token: a connection without one could have a proof relayed onto it.
            val token = try { info.rawAuthenticationToken?.joinToString("") { "%02x".format(it) } ?: "" } catch (e: Exception) { "" }
            val existing = endpoints[endpointId]
            val ourDial = existing?.state == CONNECTING
            // One proven link per phone, and a hard ceiling on proven links so a crowd can't pile
            // them onto us. An unproven connection blocks nobody (see NearbyRules).
            val nodeId = (seen as? NearbyRules.Seen.Member)?.nodeId
            if (nodeId == null || !NearbyRules.accept(seen, token, alreadyLinkedTo(nodeId, except = endpointId), provenCount(), ourDial)) {
                try { client.rejectConnection(endpointId) } catch (e: Exception) { }
                if (existing != null && existing.state == CONNECTING) endpoints.remove(endpointId)
                return
            }
            val ep = existing ?: Endpoint(endpointId, nodeId).also { endpoints[endpointId] = it }
            if (!ourDial) { ep.state = CONNECTING; startUnproven(ep) }
            ep.token = token
            client.acceptConnection(endpointId, payloads)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            val ep = endpoints[endpointId] ?: run {
                // Asked for before this radio stopped, made after: nobody here wants it any more.
                if (!running && result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) try { client.disconnectFromEndpoint(endpointId) } catch (e: Exception) { }
                return
            }
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                ep.state = CONNECTED; ep.attempts = 0; ep.lost = false; lastLinkAt = System.currentTimeMillis()
                Log.i(TAG, "linked ${ep.nodeId}")
                armDeadline(ep)   // its few seconds to prove itself start now, with the handshake
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
            ep?.deadline?.let { handler.removeCallbacks(it) }
            Log.i(TAG, "unlinked ${ep?.nodeId}")
            forget(endpointId)
            events?.onLinkDown(endpointId)
            connectWaiting()
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
                PayloadTransferUpdate.Status.SUCCESS -> { sent(update.payloadId); events?.onPayloadSent(update.payloadId) }
                PayloadTransferUpdate.Status.FAILURE, PayloadTransferUpdate.Status.CANCELED -> { sent(update.payloadId); events?.onPayloadFailed(update.payloadId) }
                else -> {}
            }
        }
    }

    // ------------------------------------------------------------------ Transport

    override fun send(linkId: String, bytes: ByteArray): Long {
        val ep = endpoints[linkId]
        if (ep == null || ep.state != CONNECTED) return -1
        val p = Payload.fromBytes(bytes)
        inFlight[p.id] = linkId
        while (inFlight.size > MAX_IN_FLIGHT) inFlight.remove(inFlight.keys.first())
        client.sendPayload(linkId, p).addOnFailureListener { e ->
            sent(p.id)
            events?.onPayloadFailed(p.id)
            // The session died without a disconnect callback (e.g. Play services restarted):
            // treat the link as gone so the router stops counting it and we can re-link.
            val code = statusCode(e)
            if ((code == ConnectionsStatusCodes.STATUS_ENDPOINT_UNKNOWN || code == ConnectionsStatusCodes.STATUS_NOT_CONNECTED_TO_ENDPOINT) &&
                endpoints[linkId] === ep) {
                endpoints.remove(linkId)
                try { client.disconnectFromEndpoint(linkId) } catch (x: Exception) { }
                forget(linkId)
                events?.onLinkDown(linkId)
            }
        }
        return p.id
    }

    /** The router dropped the link (a bad proof, a newer link to the same phone, silence): its slot is free again. */
    override fun disconnect(linkId: String) {
        endpoints.remove(linkId)?.deadline?.let { handler.removeCallbacks(it) }
        try { client.disconnectFromEndpoint(linkId) } catch (e: Exception) { }
        forget(linkId)
        // Nearby says nothing about a disconnect asked for here: without this, a phone waiting for
        // a slot would wait for the next discovery round.
        connectWaiting()
    }

    /** The link's phone proved itself: from now on it counts as linked, and its timer stops. */
    override fun authed(linkId: String) {
        val ep = endpoints[linkId] ?: return
        ep.authed = true
        ep.deadline?.let { handler.removeCallbacks(it) }
        ep.deadline = null
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
        private const val OTHER_GROUP_MS = 120_000L
        /** Payloads remembered as on their way, at most (see [inFlight]): far more than a few links ever have. */
        private const val MAX_IN_FLIGHT = 2_000
        /** Radios still getting a goodbye out ([finish]), and the links they hold. Main thread only. */
        private var lingering = 0
        private val held = HashSet<String>()
        private const val MAX_ATTEMPTS = 6
        private const val RETRY_REST_MS = 5 * 60_000L
        private const val FOUND = 0
        private const val CONNECTING = 1
        private const val CONNECTED = 2
    }
}
