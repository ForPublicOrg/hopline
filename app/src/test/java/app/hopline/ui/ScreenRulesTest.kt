package app.hopline.ui

import app.hopline.data.SavedGroup
import app.hopline.ui.ScreenRules.After
import app.hopline.ui.ScreenRules.Entered
import app.hopline.ui.ScreenRules.Screen
import app.hopline.ui.ScreenRules.Typed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which screen a phone lands on — above all: a phone that only has groups it left can still get to their chats. */
class ScreenRulesTest {
    private val now = 1_700_000_000_000L
    private val trek = "tiger-river-lamp"
    private val family = "apple-moon-drum"

    private fun g(code: String, leftAt: Long = 0) = SavedGroup(code, "", now, now).also { it.leftAt = leftAt }

    // ---------------------------------------------------------------- the launch screen

    @Test fun aPhoneWithNoNameIsAskedForOneBeforeAnythingElse() {
        for (inGroup in listOf(false, true)) for (ready in listOf(false, true))
            assertEquals(Screen.WELCOME, ScreenRules.launch(named = false, inGroup = inGroup, anySaved = inGroup, radioReady = ready))
    }

    @Test fun aPhoneInAGroupGoesHomeOnceTheRadioIsAllowed() {
        assertEquals(Screen.HOME, ScreenRules.launch(named = true, inGroup = true, anySaved = true, radioReady = true))
        assertEquals(Screen.PERMISSIONS, ScreenRules.launch(named = true, inGroup = true, anySaved = true, radioReady = false))
    }

    @Test fun aPhoneWithNoGroupAtAllIsSentToStartOrJoinOne() {
        assertEquals(Screen.JOIN, ScreenRules.launch(named = true, inGroup = false, anySaved = false, radioReady = true))
        assertEquals(Screen.PERMISSIONS, ScreenRules.launch(named = true, inGroup = false, anySaved = false, radioReady = false))
    }

    @Test fun aPhoneThatOnlyHasGroupsItLeftGoesHomeToReadThem() {
        assertEquals(Screen.HOME, ScreenRules.launch(named = true, inGroup = false, anySaved = true, radioReady = true))
        // …also when Android has taken the permissions back: reading an old chat needs no radio.
        assertEquals(Screen.HOME, ScreenRules.launch(named = true, inGroup = false, anySaved = true, radioReady = false))
    }

    // ---------------------------------------------------------------- Home's own guard

    @Test fun homeKeepsAPhoneThatOnlyHasGroupsItLeft() {
        assertFalse(ScreenRules.homeNeedsLaunch(inGroup = false, anySaved = true, granted = true))
        assertFalse(ScreenRules.homeNeedsLaunch(inGroup = false, anySaved = true, granted = false))
    }

    @Test fun homeHandsOverWhenThereIsNothingToShowOrNoPermissionToRunTheGroup() {
        assertTrue(ScreenRules.homeNeedsLaunch(inGroup = false, anySaved = false, granted = true))
        assertTrue(ScreenRules.homeNeedsLaunch(inGroup = true, anySaved = true, granted = false))
        assertFalse(ScreenRules.homeNeedsLaunch(inGroup = true, anySaved = true, granted = true))
    }

    @Test fun homeAndTheLaunchScreenNeverSendAPhoneRoundInCircles() {
        // Every state a named phone can be in (a group on the radio is always a saved one).
        for (inGroup in listOf(false, true)) for (anySaved in listOf(false, true)) for (granted in listOf(false, true)) for (done in listOf(false, true)) {
            if (inGroup && !anySaved) continue
            val state = "inGroup=$inGroup anySaved=$anySaved granted=$granted done=$done"
            val next = ScreenRules.launch(named = true, inGroup = inGroup, anySaved = anySaved, radioReady = granted && done)
            val homeLeaves = ScreenRules.homeNeedsLaunch(inGroup, anySaved, granted)
            // Home sent it to the launch screen: the launch screen must not send it straight back…
            if (homeLeaves) assertNotEquals(state, Screen.HOME, next)
            // …and where the launch screen says Home, Home must let it stay.
            if (next == Screen.HOME) assertFalse(state, homeLeaves)
        }
    }

    // ---------------------------------------------------------------- before a group goes on the radio

    @Test fun thePermissionsAreAskedForBeforeAGroupIsPutOnTheRadioNeverAfter() {
        assertEquals(ScreenRules.Radio.GO, ScreenRules.beforeRadio(granted = true))
        assertEquals(ScreenRules.Radio.ASK_PERMISSIONS, ScreenRules.beforeRadio(granted = false))
    }

    @Test fun rejoiningWithoutThePermissionsWouldPutEveryKeptChatBehindThePermissionsScreen() {
        // Why they are asked for first. A phone that only has groups it left is on Home without them…
        assertFalse(ScreenRules.homeNeedsLaunch(inGroup = false, anySaved = true, granted = false))
        // …but the moment one is rejoined (or another started or joined) regardless, Home hands
        // over to the launch screen, and that leads to the permissions screen — every time.
        assertTrue(ScreenRules.homeNeedsLaunch(inGroup = true, anySaved = true, granted = false))
        assertEquals(Screen.PERMISSIONS, ScreenRules.launch(named = true, inGroup = true, anySaved = true, radioReady = false))
    }

    // ---------------------------------------------------------------- "Reply privately"

    @Test fun replyPrivatelyAlwaysEndsUpPointingAtTheMessageThatWasPicked() {
        // the chat opened with no reply set (an earlier message the router can't look up)
        assertTrue(ScreenRules.replyStillToSet(current = null, picked = "m7"))
        // the chat's saved draft brought its own, older reply target back
        assertTrue(ScreenRules.replyStillToSet(current = "m2", picked = "m7"))
        // opening the chat already pointed it at the picked message
        assertFalse(ScreenRules.replyStillToSet(current = "m7", picked = "m7"))
    }

    // ---------------------------------------------------------------- a code typed, scanned or tapped

    @Test fun aCodeThisPhoneNeverHadIsNew() {
        assertEquals(Entered.NEW, ScreenRules.codeEntered(null, null))
        assertEquals(Entered.NEW, ScreenRules.codeEntered(null, trek))
    }

    @Test fun theCodeOfTheGroupOnTheRadioOpensIt() {
        assertEquals(Entered.OPEN, ScreenRules.codeEntered(g(trek), trek))
    }

    @Test fun theCodeOfAPausedGroupOffersToSwitch() {
        assertEquals(Entered.SWITCH, ScreenRules.codeEntered(g(family), trek))
    }

    @Test fun theCodeOfAGroupThisPhoneLeftAsksToRejoinAndNeverJoinsOrSwitchesSilently() {
        assertEquals(Entered.REJOIN, ScreenRules.codeEntered(g(family, leftAt = now), trek))
        // with no group on the radio at all (every group left) it is still a rejoin, not a first join
        assertEquals(Entered.REJOIN, ScreenRules.codeEntered(g(family, leftAt = now), null))
        // and even if the active code somehow named it, left wins: nothing opens a left group as live
        assertEquals(Entered.REJOIN, ScreenRules.codeEntered(g(family, leftAt = now), family))
    }

    // ---------------------------------------------------------------- a new code, typed or scanned

    @Test fun fourOfHoplinesWordsJoinAtOnce() {
        assertEquals(Typed.JOIN, ScreenRules.typed("tiger river lamp hat"))
        assertEquals(Typed.JOIN, ScreenRules.typed("Tiger, River LAMP-hat"))
    }

    @Test fun threeOfHoplinesWordsAskOnceBeforeJoining() {
        // A group started before 2.4 — or a new code with a word left off, which would find nobody.
        assertEquals(Typed.OLDER, ScreenRules.typed("tiger river lamp"))
        // Asked already, in the question that led here ("Did you mean…?"): joined, not asked again.
        assertEquals(Typed.JOIN, ScreenRules.typed("tiger river lamp", olderSeen = true))
    }

    @Test fun aTypoIsOfferedItsFixAndAFixedThreeWordCodeIsNotAskedAboutTwice() {
        assertEquals(Typed.SUGGEST, ScreenRules.typed("tigre river lamp hat"))
        assertEquals(Typed.SUGGEST, ScreenRules.typed("tigre river lamp"))
        // What the "Use that" answer joins: the three-word question was part of "Did you mean…?".
        assertEquals(Typed.JOIN, ScreenRules.typed("tiger river lamp", olderSeen = true))
    }

    @Test fun wordsNotOnTheListAreCheckedAndNonsenseIsRefused() {
        assertEquals(Typed.CHECK, ScreenRules.typed("xqzv wpkj tiger lamp"))
        assertEquals(Typed.BAD, ScreenRules.typed("hello"))
        assertEquals(Typed.BAD, ScreenRules.typed(""))
        assertEquals(Typed.BAD, ScreenRules.typed("tiger river lamp hat moon"))
    }

    @Test fun onlyAThreeWordCodeIsAnOlderOne() {
        assertTrue(ScreenRules.olderCode("tiger river lamp"))
        assertTrue(ScreenRules.olderCode(trek))
        assertFalse(ScreenRules.olderCode("tiger river lamp hat"))
        assertFalse(ScreenRules.olderCode("tiger river"))
    }

    // ---------------------------------------------------------------- after "Delete group"

    @Test fun deletingFromHomeStaysOnHomeWhileAnyGroupIsSaved() {
        assertEquals(After.STAY, ScreenRules.afterDelete(onHome = true, anySaved = true))
    }

    @Test fun deletingFromAScreenThatShowedTheGroupGoesToAFreshHome() {
        assertEquals(After.HOME, ScreenRules.afterDelete(onHome = false, anySaved = true))
    }

    @Test fun deletingTheLastSavedGroupGoesToTheLaunchScreenFromAnywhere() {
        assertEquals(After.LAUNCH, ScreenRules.afterDelete(onHome = true, anySaved = false))
        assertEquals(After.LAUNCH, ScreenRules.afterDelete(onHome = false, anySaved = false))
    }
}
