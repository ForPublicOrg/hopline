package app.hopline.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** What a helper with no messaging app copies, to send a friend's text or email by hand. */
class NetTextTest {
    @Test fun `a text copied by hand is the number, then the words`() {
        assertEquals("+919876543210\n\nReached the ridge, all fine",
            NetText.sendByHand("+919876543210", null, "Reached the ridge, all fine"))
    }

    @Test fun `an email copied by hand keeps its subject`() {
        assertEquals("mom@example.com\n\nSubject: Message from Riya\n\nAll fine",
            NetText.sendByHand("mom@example.com", "Subject: Message from Riya", "All fine"))
    }

    @Test fun `nothing blank is copied`() {
        assertEquals("mom@example.com\n\nSubject: Hi", NetText.sendByHand("mom@example.com", "Subject: Hi", "  "))
        assertEquals("+919876543210\n\nOK", NetText.sendByHand("+919876543210", "", "OK"))
    }
}
