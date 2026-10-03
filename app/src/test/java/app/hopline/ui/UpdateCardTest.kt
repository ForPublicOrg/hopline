package app.hopline.ui

import app.hopline.service.Updater.Why
import app.hopline.ui.UpdateCard.Action
import app.hopline.ui.UpdateCard.Line
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** How Hopline's own update is put to the person: which buttons a failure gets, and what Settings says. */
class UpdateCardTest {

    // ---------------------------------------------------------------- the buttons under "Couldn't update"

    @Test fun whereTryingAgainCanHelpThatComesFirstAndThePageIsTheWayRound() {
        for (why in listOf(Why.NETWORK, Why.SPACE, Why.INSTALL)) assertEquals("$why", Action.RETRY to Action.PAGE, UpdateCard.buttons(why, retry = true))
    }

    @Test fun aFileThatCanNeverBeUsedLeavesOnlyTheDownloadPage() {
        for (why in listOf(Why.FETCH, Why.MISMATCH, Why.UNUSABLE, Why.REFUSED, Why.INSTALL, Why.NETWORK, Why.SPACE))
            assertEquals("$why", Action.PAGE to null, UpdateCard.buttons(why, retry = false))
    }

    @Test fun aFileSignedWithAnotherKeyGetsNoButtonThatLeadsStraightBackToIt() {
        // The page is handing out the very file Hopline refused. It can't be installed over this
        // Hopline by hand either — the only way through would be uninstalling it, chats and all.
        assertEquals(null to null, UpdateCard.buttons(Why.SIGNER, retry = false))
    }

    @Test fun everyReasonIsDecidedOnPurpose() {
        // A reason added later must be given its buttons here, not fall into one of the cases by accident.
        assertEquals(setOf(Why.NETWORK, Why.SPACE, Why.FETCH, Why.MISMATCH, Why.SIGNER, Why.UNUSABLE, Why.REFUSED, Why.INSTALL), Why.values().toSet())
        for (why in Why.values()) if (why != Why.SIGNER) assertEquals("$why", Action.PAGE, UpdateCard.buttons(why, retry = false).first)
    }

    // ---------------------------------------------------------------- the line in Settings → About

    private fun line(enabled: Boolean = true, checking: Boolean = false, available: Boolean = false, known: Boolean = false,
                     checked: Boolean = false, sure: Boolean = true, auto: Boolean = true) =
        UpdateCard.line(enabled, checking, available, known, checked, sure, auto)

    @Test fun upToDateIsSaidOnlyWhenTheLatestReleaseWasReadAndIsNotNewer() {
        assertEquals(Line.CURRENT, line(checked = true, sure = true))
        // GitHub answered, but with a release Hopline could not read — its APK under another name,
        // a tag that is not a version. It may be newer: "Up to date" is more than Hopline knows.
        assertEquals(Line.UNSURE, line(checked = true, sure = false))
        for (auto in listOf(true, false)) assertNotEquals(Line.CURRENT, line(checked = true, sure = false, auto = auto))
    }

    @Test fun aPhoneThatNeverCheckedSaysWhetherItWillByItself() {
        assertEquals(Line.NEVER, line(checked = false, auto = true))
        assertEquals(Line.OFF, line(checked = false, auto = false))
        assertEquals("nothing was ever answered, so there is nothing to be unsure about", Line.NEVER, line(checked = false, sure = false))
    }

    @Test fun whatIsGoingOnOrOnOfferComesBeforeWhenItWasLastChecked() {
        assertEquals(Line.DEBUG, line(enabled = false, checking = true, available = true, known = true, checked = true))
        assertEquals(Line.CHECKING, line(checking = true, known = true, checked = true))
        assertEquals(Line.AVAILABLE, line(available = true, known = true, checked = true, sure = false))
        assertEquals(Line.STATE, line(known = true, checked = true, sure = false))
    }
}
