package app.hopline.ui

import app.hopline.core.Crypto
import app.hopline.mesh.Attachment
import app.hopline.mesh.FakeNet
import app.hopline.ui.ChatRules.Bottom
import app.hopline.ui.ChatRules.Chip
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun theGroupChatHasItsComposerForAnyoneWhoMayPost() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = null, canWrite = false, peerLeft = false, canPost = true))
    }

    @Test fun aMemberWhoMayNotPostGetsTheAdminsOnlyLineInsteadOfAComposer() {
        // Whatever else is said about the chat: the group chat asks no key, and nobody "left" it.
        for (canWrite in listOf(false, true)) for (peerLeft in listOf(false, true))
            assertEquals(Bottom.ADMINS_ONLY, ChatRules.bottom(readOnly = false, peer = null, canWrite = canWrite, peerLeft = peerLeft, canPost = false))
    }

    @Test fun theComposerComesBackTheMomentTheyMayPostAgain() {
        assertEquals(Bottom.ADMINS_ONLY, ChatRules.bottom(readOnly = false, peer = null, canWrite = true, peerLeft = false, canPost = false))
        // The lift, or being made an admin: the very next look.
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = null, canWrite = true, peerLeft = false, canPost = true))
    }

    @Test fun privateChatsNeverAskWhoMayPostInTheGroup() {
        // A private chat stays open to someone who may not post in the group: that's the point of it.
        for (canPost in listOf(false, true)) {
            assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false, canPost = canPost))
            assertEquals(Bottom.NOT_SEEN, ChatRules.bottom(readOnly = false, peer = asha, canWrite = false, peerLeft = false, canPost = canPost))
            assertEquals(Bottom.PEER_LEFT, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = true, canPost = canPost))
            assertEquals(Bottom.FROM_BEFORE, ChatRules.bottom(readOnly = false, peer = old, canWrite = false, peerLeft = false, canPost = canPost))
        }
    }

    @Test fun aPrivateChatThatCanBeSealedForHasItsComposer() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false, canPost = true))
    }

    @Test fun aChatFromBeforeTheUpdateSaysSoInsteadOfAComposer() {
        assertEquals(Bottom.FROM_BEFORE, ChatRules.bottom(readOnly = false, peer = old, canWrite = false, peerLeft = false, canPost = true))
    }

    @Test fun someoneWhoseKeyNeverArrivedCanBeWrittenToOnceTheirPhoneHasBeenInRange() {
        assertEquals(Bottom.NOT_SEEN, ChatRules.bottom(readOnly = false, peer = asha, canWrite = false, peerLeft = false, canPost = true))
        // …and the composer comes back the moment it can.
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false, canPost = true))
    }

    @Test fun someoneWhoLeftTheGroupHasTheirOwnLineInsteadOfAComposer() {
        // Their key is known (an answer to their request could still be sealed), yet nothing written reaches them.
        assertEquals(Bottom.PEER_LEFT, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = true, canPost = true))
        // Never "hasn't been seen for a while": it is not that they are away.
        assertEquals(Bottom.PEER_LEFT, ChatRules.bottom(readOnly = false, peer = asha, canWrite = false, peerLeft = true, canPost = true))
        // Their hello brings the composer back.
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = asha, canWrite = true, peerLeft = false, canPost = true))
    }

    @Test fun theGroupChatKeepsItsComposerWhoeverLeftAndAChatFromBeforeStaysFromBefore() {
        assertEquals(Bottom.COMPOSER, ChatRules.bottom(readOnly = false, peer = null, canWrite = true, peerLeft = true, canPost = true))
        assertEquals(Bottom.FROM_BEFORE, ChatRules.bottom(readOnly = false, peer = old, canWrite = false, peerLeft = true, canPost = true))
    }

    @Test fun aLeftGroupsChatHasItsOwnLineWhateverTheChat() {
        // Who may post in a group this phone left doesn't matter: nothing can be sent from it.
        for (peer in listOf(null, asha, old)) for (canWrite in listOf(false, true)) for (peerLeft in listOf(false, true))
            for (canPost in listOf(false, true))
                assertEquals(Bottom.LEFT, ChatRules.bottom(readOnly = true, peer = peer, canWrite = canWrite, peerLeft = peerLeft, canPost = canPost))
    }

    // ---------------------------------------------------------------- what is offered

    @Test fun sendAgainIsOfferedOnlyWhereItCanSendSomething() {
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = null, canWrite = false, canPost = true))     // the group chat
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = asha, canWrite = true, canPost = true))
        assertFalse(ChatRules.sendAgain(gaveUp = true, to = asha, canWrite = false, canPost = true))   // would only fail
        assertFalse(ChatRules.sendAgain(gaveUp = true, to = old, canWrite = false, canPost = true))
        assertFalse(ChatRules.sendAgain(gaveUp = false, to = null, canWrite = true, canPost = true))   // still trying on its own
    }

    @Test fun aGroupMessageIsNeverOfferedSendAgainWhileOnlyAdminsMaySend() {
        for (canWrite in listOf(false, true)) assertFalse(ChatRules.sendAgain(gaveUp = true, to = null, canWrite = canWrite, canPost = false))
        // A private one still is: the group's setting is nothing to do with it.
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = asha, canWrite = true, canPost = false))
        // And the group one is again the moment they may post.
        assertTrue(ChatRules.sendAgain(gaveUp = true, to = null, canWrite = false, canPost = true))
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

    // ---------------------------------------------------------------- the composer's words

    @Test fun aReplyKeptWhileTheChatWasOnScreenJoinsWhatIsTyped() {
        // The group was starting with its chat open. A reply from the notification waited for it, then couldn't go
        // (only admins may send), so Core.keepAsDraft put it after the saved draft — the one the screen had read.
        assertEquals("on my way\ncan't make it", ChatRules.withKeptWords(typed = "on my way", seen = "", stored = "can't make it"))
        assertEquals("see you at 5, or 6\ncan't make it",
            ChatRules.withKeptWords(typed = "see you at 5, or 6", seen = "see you at 5", stored = "see you at 5\ncan't make it"))
        // Nothing typed meanwhile: the composer holds what the draft holds now — two kept replies, both.
        assertEquals("see you at 5\nlate\nvery late",
            ChatRules.withKeptWords(typed = "see you at 5", seen = "see you at 5", stored = "see you at 5\nlate\nvery late"))
        assertEquals("can't make it", ChatRules.withKeptWords(typed = "", seen = "", stored = "can't make it"))
        // A draft of spaces (kept for its reply) is no words: the reply stands alone, as keepAsDraft writes it.
        assertEquals("can't make it", ChatRules.withKeptWords(typed = "  ", seen = "  ", stored = "can't make it"))
    }

    @Test fun wordsTypedWhileTheGroupStartedAreNeverTradedForTheSavedDraft() {
        // Nothing was kept: what is typed stays, whatever older words the saved draft still holds.
        assertNull(ChatRules.withKeptWords(typed = "on my way", seen = "", stored = ""))
        assertNull(ChatRules.withKeptWords(typed = "see you at 5, or 6", seen = "see you at 5", stored = "see you at 5"))
        // The draft went (the group was left), or another screen of the chat saved its own words: no reply to add.
        assertNull(ChatRules.withKeptWords(typed = "on my way", seen = "see you at 5", stored = ""))
        assertNull(ChatRules.withKeptWords(typed = "on my way", seen = "see you at 5", stored = "see you"))
        assertNull(ChatRules.withKeptWords(typed = "", seen = "  ", stored = ""))
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
