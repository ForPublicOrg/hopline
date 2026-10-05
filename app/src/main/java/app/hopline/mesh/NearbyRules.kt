package app.hopline.mesh

import app.hopline.core.Crypto

/**
 * The plain rules behind NearbyTransport's choices: what an advertised name says, which
 * connection may come in, and which unproven one makes room for a newcomer. Pure functions, no
 * Android, so the JVM tests can pin them down; NearbyTransport keeps the endpoints and the timers.
 *
 * Anyone in range can copy a name off the air, so nothing here trusts one: a connection counts as
 * a link to a phone only once that phone has proved itself on it (Transport.authed). Until then
 * it is "unproven" — it may be a member still shaking hands, or a stranger squatting a member's
 * id to keep the real one out. Unproven connections therefore never block a phone, never fill
 * the link slots, are few, and don't last.
 */
object NearbyRules {
    /** What one advertised name says. */
    sealed class Seen {
        /** A phone of this group on 2.4 or later: link to it. */
        class Member(val nodeId: String) : Seen()
        /** A phone of this group that still runs an older Hopline: never linked, but worth telling the person. */
        object Older : Seen()
        /** A 2.4 phone of another group — or of this one, with a mistyped code. */
        object OtherGroup : Seen()
        /** Anything else, this phone included. */
        object Nothing : Seen()
    }

    /** What this phone advertises: the version, the group's air tag (the only thing derived from the code), its node id. */
    fun name(airTag: String, me: String): String = "2|$airTag|$me|"

    /**
     * Read an advertised [name]. [legacyFp] is the active group's fingerprint from before 2.4
     * (Crypto.legacyFingerprint of its code): what a phone of this group still on an older Hopline
     * advertises. Another group's older phones are none of this phone's business.
     */
    fun parse(name: String, airTag: String, legacyFp: String, me: String): Seen {
        val parts = name.split("|")
        if (parts.size < 4) return Seen.Nothing
        return when (parts[0]) {
            "2" -> when {
                parts[1] != airTag -> Seen.OtherGroup
                Crypto.isNodeId(parts[2]) && parts[2] != me -> Seen.Member(parts[2])
                else -> Seen.Nothing
            }
            "1" -> if (parts[1] == legacyFp) Seen.Older else Seen.Nothing
            else -> Seen.Nothing
        }
    }

    /**
     * May a connection to [seen] go ahead? Only to a member of this group, only with a Nearby
     * authentication token (every proof is bound to it — without one a proof could be relayed),
     * never to a phone this one already has a proven link to, and never past the link ceiling —
     * counted in proven links only ([ourDial]: this phone asked for it, within its own limits).
     * A second, unproven connection claiming the same phone is let in: the one that proves itself
     * wins (Router drops the other), so a squatter can't keep the real phone out.
     */
    fun accept(seen: Seen, token: String, provenToSame: Boolean, provenLinks: Int, ourDial: Boolean): Boolean {
        if (seen !is Seen.Member || token.isEmpty() || provenToSame) return false
        return ourDial || provenLinks < MAX_LINKS + LINK_SLACK
    }

    /**
     * A new unproven connection is about to start, and [unproven] are those already going (id to
     * when each started). When they fill [MAX_UNPROVEN], the oldest makes room — never the
     * newcomer: every real link starts unproven, and a few squatters must not lock everyone out.
     * Null when there is room.
     */
    fun evict(unproven: Map<String, Long>): String? =
        if (unproven.size < MAX_UNPROVEN) null else unproven.minByOrNull { it.value }?.key

    /** Proven links this phone keeps, at most (dials it makes itself stop here). */
    const val MAX_LINKS = 6
    /** A little slack for links others make, so two phones that are both "full" can still bridge. */
    const val LINK_SLACK = 2
    /** Connections not yet proven, at most, at any one time. */
    const val MAX_UNPROVEN = 4
    /** How long a connection may stay unproven (counted again from the moment it is up): a real phone proves itself in a moment. */
    const val UNPROVEN_MS = 12_000L
}
