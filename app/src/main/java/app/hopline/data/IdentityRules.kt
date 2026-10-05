package app.hopline.data

import app.hopline.core.IdentityKeys

/**
 * Who this phone is: the plain rules behind its key pair, kept in identity.json, and the node id
 * made from it. Pure functions — Store reads the file and the prefs and does what these say — so
 * the JVM tests can pin every case down.
 *
 * The file decides, never the prefs: a phone killed between writing the file and noting the new
 * id in the prefs finds the file at its next start and simply notes the id then. Ids this phone
 * had before ([IdentityRules.Ids.formerIds]) are kept, so a group's saved chat can be told which
 * of its messages are this phone's own (see [Upgrade]).
 */
object IdentityRules {
    /** What was found where identity.json lives. */
    sealed class Found {
        object Absent : Found()
        /** The storage answered with an error: the file may well be fine, and must not be written over. */
        object Unreadable : Found()
        class Text(val text: String) : Found()
    }

    /** What to do about it. */
    sealed class Plan {
        /** The file is this phone's identity. [ids]: what the prefs must say first, or null when they say it already. */
        class Use(val keys: IdentityKeys, val ids: Ids?) : Plan()
        /**
         * There is no usable identity: make a new key pair, write the file, and only then note its
         * id ([ids]). [setAside]: a file is there that doesn't read as a key pair; it is moved out
         * of the way first, never written over.
         */
        class Create(val setAside: Boolean) : Plan()
        /** Nothing can be decided now; nothing is written. Try again in a moment, or fail this start. */
        object TryAgain : Plan()
    }

    /** What the prefs say about who this phone is: its node id, and the ids it had before. */
    class Ids(val nodeId: String, val formerIds: Set<String>)

    /** The decision, from what the file holds and what the prefs say ([savedId] = their node id, if any). */
    fun plan(found: Found, savedId: String?, formerIds: Set<String>): Plan = when (found) {
        Found.Absent -> Plan.Create(setAside = false)
        Found.Unreadable -> Plan.TryAgain
        is Found.Text -> IdentityKeys.decode(found.text)?.let { Plan.Use(it, ids(it.nodeId, savedId, formerIds)) }
            ?: Plan.Create(setAside = true)
    }

    /**
     * What the prefs must say once [id] is this phone's: the id, with whatever id they named
     * before added to the former ones — in ONE commit, so no moment ever has the old id gone and
     * not yet noted. Null when they already say it.
     */
    fun ids(id: String, savedId: String?, formerIds: Set<String>): Ids? {
        if (savedId == id && id !in formerIds) return null
        val former = if (savedId.isNullOrEmpty()) formerIds else formerIds + savedId
        return Ids(id, former - id)
    }
}
