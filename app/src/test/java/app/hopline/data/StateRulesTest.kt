package app.hopline.data

import app.hopline.data.StateRules.Failed
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * A group's state file holds its whole chat. These are the moments it could be lost by doing
 * things in the wrong order, or by giving up on the file too soon — and the order that keeps it.
 */
class StateRulesTest {

    // ---------------------------------------------------------------- a state that would not read

    @Test fun textThatIsNotJsonIsProofTheFileIsBadAndItIsSetAsideAtOnce() {
        assertEquals(Failed.ASIDE, StateRules.onReadFailure(JSONException("Unterminated object"), earlier = 0))
    }

    @Test fun aFailureThatSaysNothingAboutTheFileGetsOneMoreTry() {
        assertEquals(Failed.RETRY, StateRules.onReadFailure(IOException("EIO"), earlier = 0))
        assertEquals(Failed.RETRY, StateRules.onReadFailure(OutOfMemoryError(), earlier = 0))
        assertEquals(Failed.RETRY, StateRules.onReadFailure(java.io.FileNotFoundException("Too many open files"), earlier = 0))
    }

    @Test fun theSecondFailureInARowSetsItAsideSoAGroupIsNeverStuckBehindAFileThatWontRead() {
        assertEquals(Failed.ASIDE, StateRules.onReadFailure(IOException("EIO"), earlier = 1))
        assertEquals(Failed.ASIDE, StateRules.onReadFailure(OutOfMemoryError(), earlier = 1))
        assertEquals(Failed.ASIDE, StateRules.onReadFailure(OutOfMemoryError(), earlier = 7))
    }

    @Test fun aStateThatWontRestoreIsSetAsideUnlessItWasOnlyMemoryRunningOut() {
        // An exception while restoring is the content's doing: the same file would throw again.
        assertEquals(Failed.ASIDE, StateRules.onRestoreFailure(IllegalStateException("bad record"), earlier = 0))
        assertEquals(Failed.ASIDE, StateRules.onRestoreFailure(JSONException("no value for id"), earlier = 0))
        // Out of memory half way is not: once more, then aside.
        assertEquals(Failed.RETRY, StateRules.onRestoreFailure(OutOfMemoryError(), earlier = 0))
        assertEquals(Failed.ASIDE, StateRules.onRestoreFailure(OutOfMemoryError(), earlier = 1))
    }

    // ---------------------------------------------------------------- leaving the group on the radio

    @Test fun aLeaveWhoseChatCouldNotBeSavedFirstLetsGoOfNothing() {
        // The disk is full: the save that has to come first fails.
        var letGo = 0
        assertFalse(StateRules.leave(saveWhole = { false }) { letGo++ })
        assertEquals("the router is not detached and the group is not marked left", 0, letGo)
    }

    @Test fun aLeaveGoesAheadOnlyAfterTheWholeChatIsOnDisk() {
        val order = ArrayList<String>()
        assertTrue(StateRules.leave(saveWhole = { order.add("save"); true }) { order.add("let go") })
        assertEquals(listOf("save", "let go"), order)
    }

    // ---------------------------------------------------------------- "Delete for me" in a kept chat

    @Test fun aDeleteThatCouldNotBeSavedDeletesNoFileAndNoHistoryAndPutsTheChatBack() {
        var cleaned = 0; var putBack = 0
        StateRules.deleteKept(saved = false, cleanUp = { cleaned++ }, putBack = { putBack++ })
        assertEquals("the message is still in the saved state: its photo must still be there too", 0, cleaned)
        assertEquals(1, putBack)
    }

    @Test fun onceTheStateWithoutThemIsOnDiskTheirFilesAndHistoryCopiesGo() {
        var cleaned = 0; var putBack = 0
        StateRules.deleteKept(saved = true, cleanUp = { cleaned++ }, putBack = { putBack++ })
        assertEquals(1, cleaned)
        assertEquals(0, putBack)
    }
}
