package app.hopline.data

import app.hopline.core.Crypto
import app.hopline.core.IdentityKeys
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Who this phone is: identity.json decides, the prefs follow, and nothing that might be good is ever written over. */
class IdentityRulesTest {
    private val keys = IdentityKeys.generate()
    private val id = keys.nodeId
    private val oldId = "k7m2p9qa"   // a 2.3 id: eight random letters

    private fun use(p: IdentityRules.Plan): IdentityRules.Plan.Use = p as IdentityRules.Plan.Use

    @Test fun aFileThatReadsIsThisPhoneAndThePrefsFollowIt() {
        // the usual start: file and prefs agree, nothing to write
        val same = use(IdentityRules.plan(IdentityRules.Found.Text(keys.encode()), id, setOf(oldId)))
        assertEquals(id, same.keys.nodeId)
        assertNull(same.ids)
        // killed between writing the file and noting its id: the 2.3 id is still in the prefs — it is moved to the former ids now
        val late = use(IdentityRules.plan(IdentityRules.Found.Text(keys.encode()), oldId, emptySet()))
        assertEquals(id, late.keys.nodeId)
        assertEquals(id, late.ids!!.nodeId)
        assertEquals(setOf(oldId), late.ids!!.formerIds)
        // prefs wiped (or never written): the id is noted, nothing invented
        val bare = use(IdentityRules.plan(IdentityRules.Found.Text(keys.encode()), null, emptySet()))
        assertEquals(id, bare.ids!!.nodeId); assertTrue(bare.ids!!.formerIds.isEmpty())
    }

    @Test fun noFileMeansANewIdentityAndTheOldIdIsRemembered() {
        val p = IdentityRules.plan(IdentityRules.Found.Absent, oldId, emptySet())
        assertTrue(p is IdentityRules.Plan.Create); assertFalse((p as IdentityRules.Plan.Create).setAside)
        // what the one commit after writing the file says
        val made = IdentityKeys.generate()
        val ids = IdentityRules.ids(made.nodeId, oldId, emptySet())!!
        assertEquals(made.nodeId, ids.nodeId)
        assertEquals(setOf(oldId), ids.formerIds)
        assertTrue(Crypto.isNodeId(ids.nodeId))
        // a fresh install has nothing to remember
        assertTrue(IdentityRules.ids(made.nodeId, null, emptySet())!!.formerIds.isEmpty())
        assertTrue(IdentityRules.ids(made.nodeId, "", emptySet())!!.formerIds.isEmpty())
    }

    @Test fun aFileThatDoesNotReadAsAKeyPairIsSetAsideNeverWrittenOver() {
        val bad = listOf("", "not json", "{}", JSONObject().put("priv", "x").put("pub", "y").toString(),
            // two halves that don't belong together
            JSONObject(keys.encode()).put("pub", IdentityKeys.generate().pubB64).toString())
        for (text in bad) {
            val p = IdentityRules.plan(IdentityRules.Found.Text(text), id, emptySet())
            assertTrue(text, p is IdentityRules.Plan.Create && p.setAside)
        }
        // the identity made in its place: the id the prefs named is now a former one
        val made = IdentityKeys.generate()
        val ids = IdentityRules.ids(made.nodeId, id, setOf(oldId))!!
        assertEquals(setOf(oldId, id), ids.formerIds)
    }

    @Test fun aFileTheStorageWontReadIsTriedAgainNeverReplaced() {
        assertSame(IdentityRules.Plan.TryAgain, IdentityRules.plan(IdentityRules.Found.Unreadable, id, emptySet()))
        assertSame(IdentityRules.Plan.TryAgain, IdentityRules.plan(IdentityRules.Found.Unreadable, null, emptySet()))
    }

    @Test fun formerIdsGrowAndNeverHoldTheCurrentOne() {
        val second = IdentityKeys.generate().nodeId
        val ids = IdentityRules.ids(second, id, setOf(oldId))!!
        assertEquals(setOf(oldId, id), ids.formerIds)
        // an id that somehow sits among the former ones too is taken out of them
        val odd = IdentityRules.ids(id, id, setOf(oldId, id))!!
        assertEquals(setOf(oldId), odd.formerIds)
        assertEquals(id, odd.nodeId)
    }
}
