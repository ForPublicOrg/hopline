package app.hopline.mesh

import app.hopline.core.Crypto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the radio layer links to: only proven members count, squatters block nobody, and older phones are told apart. */
class NearbyRulesTest {
    private val tag = "0123456789abcdef"
    private val legacy = Crypto.legacyFingerprint(FakeNet.CODE)
    private val me = FakeNet.idOf("A")
    private val them = FakeNet.idOf("B")

    private fun parse(name: String) = NearbyRules.parse(name, tag, legacy, me)

    @Test fun aNameIsReadForWhatItSays() {
        val name = NearbyRules.name(tag, them)
        assertEquals("2|$tag|$them|", name)
        val m = parse(name)
        assertTrue(m is NearbyRules.Seen.Member); assertEquals(them, (m as NearbyRules.Seen.Member).nodeId)
        // anything after the last bar is for later versions to fill in
        assertEquals(them, (parse("2|$tag|$them|more|stuff") as NearbyRules.Seen.Member).nodeId)
        // my own name, played back to me, is nobody
        assertSame(NearbyRules.Seen.Nothing, parse(NearbyRules.name(tag, me)))
        // an id that isn't a 2.4 node id is nobody either
        for (bad in listOf("abcdefgh", "", "ABCDEFGHJKMNPQRS", "abcdefghijkmnpq1", them + "x")) assertSame(bad, NearbyRules.Seen.Nothing, parse("2|$tag|$bad|"))
        // another 2.4 group — or this one with a mistyped code
        assertSame(NearbyRules.Seen.OtherGroup, parse("2|fedcba9876543210|$them|"))
        // a phone of THIS group still on an older Hopline: never linked, but counted
        assertSame(NearbyRules.Seen.Older, parse("1|$legacy|abcdefgh|"))
        // an older phone of some other group is no concern of ours
        assertSame(NearbyRules.Seen.Nothing, parse("1|deadbeef|abcdefgh|"))
        // junk
        for (junk in listOf("", "2|", "2|$tag", "2|$tag|$them", "3|$tag|$them|", "hello", "1|$legacy")) assertSame(junk, NearbyRules.Seen.Nothing, parse(junk))
    }

    @Test fun theNameCarriesNothingButTheTagAndTheId() {
        val name = NearbyRules.name(tag, me)
        assertFalse(name.contains(legacy))
        assertEquals(listOf("2", tag, me, ""), name.split("|"))
    }

    @Test fun onlyAMemberWithATokenIsLetIn() {
        val member = NearbyRules.Seen.Member(them)
        assertTrue(NearbyRules.accept(member, "ab12", provenToSame = false, provenLinks = 0, ourDial = false))
        // no token: a proof could be relayed onto this connection
        assertFalse(NearbyRules.accept(member, "", provenToSame = false, provenLinks = 0, ourDial = false))
        assertFalse(NearbyRules.accept(member, "", provenToSame = false, provenLinks = 0, ourDial = true))
        // anyone but a member of this group on 2.4
        for (s in listOf(NearbyRules.Seen.Older, NearbyRules.Seen.OtherGroup, NearbyRules.Seen.Nothing))
            assertFalse(NearbyRules.accept(s, "ab12", provenToSame = false, provenLinks = 0, ourDial = false))
        // one proven link per phone
        assertFalse(NearbyRules.accept(member, "ab12", provenToSame = true, provenLinks = 1, ourDial = false))
        assertFalse(NearbyRules.accept(member, "ab12", provenToSame = true, provenLinks = 1, ourDial = true))
    }

    @Test fun theCeilingCountsProvenLinksOnly() {
        val member = NearbyRules.Seen.Member(them)
        val ceiling = NearbyRules.MAX_LINKS + NearbyRules.LINK_SLACK
        assertTrue(NearbyRules.accept(member, "t", provenToSame = false, provenLinks = ceiling - 1, ourDial = false))
        assertFalse(NearbyRules.accept(member, "t", provenToSame = false, provenLinks = ceiling, ourDial = false))
        // a dial this phone made itself was already within its own limit
        assertTrue(NearbyRules.accept(member, "t", provenToSame = false, provenLinks = ceiling, ourDial = true))
    }

    @Test fun aSquatterNeverKeepsTheRealPhoneOut() {
        // someone copied B's name and connected first: that connection is unproven, so B itself is
        // still let in alongside it — whichever proves itself wins, and a stranger can't
        val member = NearbyRules.Seen.Member(them)
        assertTrue(NearbyRules.accept(member, "real", provenToSame = false, provenLinks = 0, ourDial = false))
    }

    @Test fun theOldestUnprovenConnectionMakesRoomNeverTheNewcomer() {
        assertNull(NearbyRules.evict(emptyMap()))
        val three = mapOf("e1" to 300L, "e2" to 100L, "e3" to 200L)
        assertNull(NearbyRules.evict(three))
        val four = three + ("e4" to 400L)
        assertEquals(NearbyRules.MAX_UNPROVEN, four.size)
        assertEquals("e2", NearbyRules.evict(four))
        // however many there are, the oldest goes
        assertEquals("e0", NearbyRules.evict(four + ("e0" to 50L) + ("e5" to 500L)))
        assertTrue(NearbyRules.UNPROVEN_MS in 5_000L..20_000L)
    }
}
