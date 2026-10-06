package app.hopline.ui

import app.hopline.core.Crypto
import app.hopline.mesh.Attachment
import app.hopline.mesh.FakeNet
import app.hopline.ui.ChatRules.Bottom
import app.hopline.ui.ChatRules.Chip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What a chat shows at its top and in place of its composer — above all: nothing is offered that could only fail. */
class ChatRulesTest {
    /** Someone on 2.4 or later: a node id made from their key. */
    private val asha = "abcdefghijkmnpqr"
    /** The same kind of person, from before the update: an 8-letter id no phone has any more. */
    private val old = "k3j9x2pa"

    @Before fun ids() {
        assertTrue(Crypto.isNodeId(asha))
        assertFalse(Crypto.isNodeId(old))
    }

    // ---------------------------------------------------------------- the line at the top

    @Test fun theGroupChatSaysItIsSealedWithTheGroupsKey() {
        assertEquals(Chip.GROUP, ChatRules.chip(readOnly = false, peer = null))
    }

    @Test fun aPrivateChatWithSomeoneOnTheNewVersionSaysOnlyTheTwoCanReadIt() {
        assertEquals(Chip.PRIVATE, ChatRules.chip(readOnly = false, peer = asha))
    }

    @Test fun aPrivateChatFromBeforeTheUpdateClaimsNothing() {
        // Its messages were never sealed for the two of them.
        assertEquals(Chip.NONE, ChatRules.chip(readOnly = false, peer = old))
    }

    @Test fun aLeftGroupsKeptChatClaimsNothing() {
        assertEquals(Chip.NONE, ChatRules.chip(readOnly = true, peer = null))
        assertEquals(Chip.NONE, ChatRules.chip(readOnly = true, peer = asha))
    }

    // ---------------------------------------------------------------- where the composer is

    @Test fun theGroupChatAlwaysHasItsComposer() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = null, canWrite = false, peerLeft = false))
    }

    @Test fun aPrivateChatThatCanBeSealedForHasItsComposer() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false))
    }

    @Test fun aChatFromBeforeTheUpdateSaysSoInsteadOfAComposer() {
        assertEquals(Bottom.FROM_BEFORE, ChatRules.bottom(readOnly = false, peer = old, canWrite = false, peerLeft = false))
    }

    @Test fun someoneWhoseKeyNeverArrivedCanBeWrittenToOnceTheirPhoneHasBeenInRange() {
        assertEquals(Bottom.NOT_SEEN, ChatRules.bottom(readOnly = false, peer = asha, canWrite = false, peerLeft = false))
        // …and the composer comes back the moment it can.
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false))
    }

    @Test fun someoneWhoLeftTheGroupHasTheirOwnLineInsteadOfAComposer() {
        // Their key is known (an answer to their request could still be sealed), yet nothing written reaches them.
        assertEquals(Bottom.PEER_LEFT, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = true))
        // Never "hasn't been seen for a while": it is not that they are away.
        assertEquals(Bottom.PEER_LEFT, ChatRules.bottom(readOnly = false, peer = asha, canWrite = false, peerLeft = true))
        // Their hello brings the composer back.
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false))
    }

    @Test fun theGroupChatKeepsItsComposerWhoeverLeftAndAChatFromBeforeStaysFromBefore() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = null, canWrite = true, peerLeft = true))
        assertEquals(Bottom.FROM_BEFORE, ChatRules.bottom(readOnly = false, peer = old, canWrite = false, peerLeft = true))
    }

    @Test fun aLeftGroupsChatHasItsOwnLineWhateverTheChat() {
        for (peer in listOf(null, asha, old)) for (canWrite in listOf(false, true)) for (peerLeft in listOf(false, true))
            assertEquals(Bottom.LEFT, ChatRules.bottom(readOnly = true, peer = peer, canWrite = canWrite, peerLeft = peerLeft))
    }

    // ---------------------------------------------------------------- what is offered

    @Test fun sendAgainIsOfferedOnlyWhereItCanSendSomething() {
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = null, canWrite = false))     // the group chat
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = asha, canWrite = true))
        assertFalse(ChatRules.sendAgain(gaveUp = true, to = asha, canWrite = false))   // would only fail
        assertFalse(ChatRules.sendAgain(gaveUp = true, to = old, canWrite = false))
        assertFalse(ChatRules.sendAgain(gaveUp = false, to = null, canWrite = true))   // still trying on its own
    }

    @Test fun replyPrivatelyNeverOpensAChatThatCanNeverBeWrittenIn() {
        assertTrue(ChatRules.replyPrivately(asha, left = false))
        assertFalse(ChatRules.replyPrivately(old, left = false))
        assertFalse("they left the group", ChatRules.replyPrivately(asha, left = true))
    }

    @Test fun listsOfPeopleToPickHoldOnlyTheNewIds() {
        assertTrue(ChatRules.listed(asha))
        assertFalse(ChatRules.listed(old))
        assertFalse(ChatRules.listed(""))
    }

    @Test fun nobodyWhoLeftTheGroupIsOfferedForAMention() {
        assertTrue(ChatRules.mentionable(asha, left = false))
        assertFalse(ChatRules.mentionable(asha, left = true))
        assertFalse(ChatRules.mentionable(old, left = false))
    }

    // ---------------------------------------------------------------- files

    @Test fun aFileStillArrivingAtTheUpdateIsSaidToBeLostAtOnce() {
        // As 2.3 saved a photo's message: no key of its own — its pieces were plain, and went with the update
        val before = Attachment.make("k7m2p9qaabcdefgh", "photo.jpg", "image/jpeg", 40_000, 7, 100, 75, "")
        assertTrue(ChatRules.lostInUpdate(before))
        assertTrue(ChatRules.lostInUpdate(Attachment(JSONObject(before.json.toString()))))
        // Every file since carries its key and hash: it may still come, piece by piece
        val net = FakeNet(); val a = net.node("A")
        val (att, _) = FakeNet.makeFile(a.router, ByteArray(5_000))
        assertFalse(ChatRules.lostInUpdate(att))
        assertFalse(ChatRules.lostInUpdate(Attachment(JSONObject(att.json.toString()))))
    }
}
