package app.hopline.ui

import app.hopline.core.Crypto
import app.hopline.mesh.Envelope
import app.hopline.mesh.Message
import app.hopline.mesh.RoleChange
import app.hopline.mesh.RoleOp
import app.hopline.ui.AdminRules.Line
import app.hopline.ui.AdminRules.MemberAction
import app.hopline.ui.AdminRules.RoleArea
import app.hopline.ui.AdminRules.Told
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** What the screens offer and say about admins — above all: a member is never offered what only an admin can do. */
class AdminRulesTest {
    /** This phone, and two others in the group: node ids made from their keys. */
    private val me = "mmmmmmmmmmmmmmmm"
    private val asha = "abcdefghijkmnpqr"
    private val ravi = "rstuvwxyzabcdefg"
    /** Someone from before the update: an 8-letter id no phone has any more. */
    private val old = "k3j9x2pa"
    private val bools = listOf(false, true)

    @Before fun ids() {
        for (id in listOf(me, asha, ravi)) assertTrue(id, Crypto.isNodeId(id))
        assertFalse(Crypto.isNodeId(old))
    }

    /** Every combination [AdminRules.memberActions] can be asked, with what it answers. */
    private fun everyTarget(viewerAdmin: Boolean, check: (admin: Boolean, founder: Boolean, left: Boolean, acts: List<MemberAction>) -> Unit) {
        for (admin in bools) for (founder in bools) for (left in bools)
            check(admin, founder, left, AdminRules.memberActions(viewerAdmin, targetAdmin = admin, targetFounder = founder, targetLeft = left))
    }

    // ---------------------------------------------------------------- an admin's options for someone

    @Test fun aMembersTapStillOpensThePrivateChat() {
        for (target in listOf(asha, ravi, me, old)) assertFalse(target, AdminRules.tapOpensOptions(viewerAdmin = false, target = target, me = me))
    }

    @Test fun anAdminsTapOpensTheOptionsForEveryoneElseInTheList() {
        for (target in listOf(asha, ravi)) assertTrue(target, AdminRules.tapOpensOptions(viewerAdmin = true, target = target, me = me))
    }

    @Test fun myOwnRowNeverOpensTheOptions() {
        // My row is where I change my name.
        for (viewerAdmin in bools) assertFalse(AdminRules.tapOpensOptions(viewerAdmin, target = me, me = me))
    }

    @Test fun someoneFromBeforeTheUpdateIsNeverOfferedOptions() {
        // That id is on no phone's radio any more: nothing could be made of it.
        for (viewerAdmin in bools) assertFalse(AdminRules.tapOpensOptions(viewerAdmin, target = old, me = me))
    }

    @Test fun anAdminCanMakeAnyoneStillInTheGroupAnAdmin() {
        val acts = AdminRules.memberActions(viewerAdmin = true, targetAdmin = false, targetFounder = false, targetLeft = false)
        assertTrue(MemberAction.MAKE_ADMIN in acts)
        assertFalse("not an admin, nothing to dismiss", MemberAction.DISMISS_ADMIN in acts)
    }

    @Test fun anAdminCanDismissAnyAdminButTheOneWhoStartedTheGroup() {
        for (left in bools) {
            val other = AdminRules.memberActions(viewerAdmin = true, targetAdmin = true, targetFounder = false, targetLeft = left)
            assertTrue("left $left", MemberAction.DISMISS_ADMIN in other)
            assertFalse("left $left: already one", MemberAction.MAKE_ADMIN in other)
            val founder = AdminRules.memberActions(viewerAdmin = true, targetAdmin = true, targetFounder = true, targetLeft = left)
            assertEquals("left $left", listOf(MemberAction.MESSAGE, MemberAction.VERIFY), founder)
        }
    }

    @Test fun nobodyIsMadeAdminAfterLeavingButALeftAdminCanBeDismissed() {
        assertEquals(listOf(MemberAction.MESSAGE, MemberAction.VERIFY),
            AdminRules.memberActions(viewerAdmin = true, targetAdmin = false, targetFounder = false, targetLeft = true))
        // A left admin keeps the tag — still one, and again on rejoin — so it can be taken away.
        assertTrue(MemberAction.DISMISS_ADMIN in AdminRules.memberActions(viewerAdmin = true, targetAdmin = true, targetFounder = false, targetLeft = true))
    }

    @Test fun aMembersListHasNoAdminActionWhateverTheTarget() {
        everyTarget(viewerAdmin = false) { admin, founder, left, acts ->
            assertEquals("admin $admin, founder $founder, left $left", listOf(MemberAction.MESSAGE, MemberAction.VERIFY), acts)
        }
    }

    @Test fun theOptionsAlwaysStartWithMessageAndEndWithVerify() {
        for (viewerAdmin in bools) everyTarget(viewerAdmin) { admin, founder, left, acts ->
            val case = "viewer admin $viewerAdmin, admin $admin, founder $founder, left $left"
            assertEquals(case, MemberAction.MESSAGE, acts.first())
            assertEquals(case, MemberAction.VERIFY, acts.last())
            // At most the one admin action that applies between them.
            assertTrue(case, acts.size <= 3)
            assertEquals(case, acts.size, acts.toSet().size)
        }
    }

    // ---------------------------------------------------------------- Group info

    @Test fun anAdminSeesGroupPermissionsWhateverTheSetting() {
        for (only in bools) for (around in bools)
            assertEquals("only $only, around $around", RoleArea.PERMISSIONS, AdminRules.roleArea(ready = true, meAdmin = true, onlyAdmins = only, adminAround = around))
    }

    @Test fun aMemberSeesTheOnlyAdminsNoteOnlyWhileOnlyAdminsCanSend() {
        for (around in bools)
            assertEquals("around $around", RoleArea.NONE, AdminRules.roleArea(ready = true, meAdmin = false, onlyAdmins = false, adminAround = around))
        assertEquals(RoleArea.ONLY_ADMINS, AdminRules.roleArea(ready = true, meAdmin = false, onlyAdmins = true, adminAround = true))
    }

    @Test fun aMemberIsToldWhenNoAdminHasBeenAround() {
        assertEquals(RoleArea.ONLY_ADMINS_NOBODY, AdminRules.roleArea(ready = true, meAdmin = false, onlyAdmins = true, adminAround = false))
        // An admin is never told so about themselves: they can change it.
        assertEquals(RoleArea.PERMISSIONS, AdminRules.roleArea(ready = true, meAdmin = true, onlyAdmins = true, adminAround = false))
    }

    @Test fun nothingAboutAdminsShowsWhileTheGroupIsStarting() {
        for (meAdmin in bools) for (only in bools) for (around in bools)
            assertEquals(RoleArea.NONE, AdminRules.roleArea(ready = false, meAdmin = meAdmin, onlyAdmins = only, adminAround = around))
        for (founder in bools) for (others in bools) assertFalse(AdminRules.noAdminsNote(ready = false, founderKnown = founder, othersKnown = others))
    }

    @Test fun aGroupWithNoAdminSaysSoOnlyOnceSomeoneElseIsKnown() {
        assertTrue(AdminRules.noAdminsNote(ready = true, founderKnown = false, othersKnown = true))
        // Alone in a new group: the founder is still on its way (a joiner hears it within seconds of linking).
        assertFalse(AdminRules.noAdminsNote(ready = true, founderKnown = false, othersKnown = false))
        for (others in bools) assertFalse(AdminRules.noAdminsNote(ready = true, founderKnown = true, othersKnown = others))
    }

    // ---------------------------------------------------------------- chat lines

    /** What a role line of [kind] by [actor] about [subject] carries as its text (a target's id, or the setting). */
    private fun textOf(kind: String, subject: String, setting: String): String = when (kind) {
        Message.ROLE_ADMIN, Message.ROLE_DISMISSED -> subject
        Message.ROLE_SEND -> setting
        else -> ""
    }

    @Test fun everyRoleLineKindHasItsOwnWording() {
        val people = listOf(me, asha, ravi)
        val worded = HashMap<String, MutableSet<Line>>()
        for (kind in Message.ROLE_KINDS) for (actor in people) for (subject in people) for (setting in listOf(RoleOp.ADMINS, RoleOp.ALL)) {
            val line = AdminRules.line(kind, actor, textOf(kind, subject, setting), me)
            val othersOwn = (kind == Message.ROLE_STARTED || kind == Message.ROLE_UNSEEN) && actor != me
            if (othersOwn) assertNull("$kind by $actor: only this phone's own", line)
            else assertNotNull("$kind by $actor about $subject ($setting)", line)
            line?.let { worded.getOrPut(kind) { HashSet() }.add(it) }
        }
        assertEquals(Message.ROLE_KINDS, worded.keys)
        // No two kinds share a wording: a dismissal never reads as a grant, a setting never as either.
        for (a in Message.ROLE_KINDS) for (b in Message.ROLE_KINDS) if (a != b) assertTrue("$a and $b", worded[a]!!.intersect(worded[b]!!).isEmpty())
        // Every wording is reached.
        assertEquals(Line.values().toSet(), worded.values.flatten().toSet())
    }

    @Test fun myOwnChangesAreWordedWithYou() {
        assertEquals(Line.YOU_STARTED, AdminRules.line(Message.ROLE_STARTED, me, "", me))
        for (target in listOf(asha, ravi)) {
            assertEquals(Line.YOU_MADE, AdminRules.line(Message.ROLE_ADMIN, me, target, me))
            assertEquals(Line.YOU_DISMISSED, AdminRules.line(Message.ROLE_DISMISSED, me, target, me))
        }
        assertEquals(Line.YOU_ONLY_ADMINS, AdminRules.line(Message.ROLE_SEND, me, RoleOp.ADMINS, me))
        assertEquals(Line.YOU_EVERYONE, AdminRules.line(Message.ROLE_SEND, me, RoleOp.ALL, me))
        assertEquals(Line.UNSEEN, AdminRules.line(Message.ROLE_UNSEEN, me, "", me))
        // The same changes on someone else's phone name me.
        assertEquals(Line.MADE, AdminRules.line(Message.ROLE_ADMIN, me, asha, ravi))
        assertEquals(Line.ONLY_ADMINS, AdminRules.line(Message.ROLE_SEND, me, RoleOp.ADMINS, ravi))
        assertEquals(Line.EVERYONE, AdminRules.line(Message.ROLE_SEND, me, RoleOp.ALL, ravi))
    }

    @Test fun aChangeAboutMeIsWordedWithYou() {
        assertEquals(Line.MADE_YOU, AdminRules.line(Message.ROLE_ADMIN, asha, me, me))
        assertEquals(Line.DISMISSED_YOU, AdminRules.line(Message.ROLE_DISMISSED, asha, me, me))
        // About someone else, by someone else: both named.
        assertEquals(Line.MADE, AdminRules.line(Message.ROLE_ADMIN, asha, ravi, me))
        assertEquals(Line.DISMISSED, AdminRules.line(Message.ROLE_DISMISSED, asha, ravi, me))
    }

    @Test fun anAdminWhoDismissedThemselvesSteppedDown() {
        assertEquals(Line.STEPPED_DOWN, AdminRules.line(Message.ROLE_DISMISSED, asha, asha, me))
        assertEquals(Line.YOU_STEPPED_DOWN, AdminRules.line(Message.ROLE_DISMISSED, me, me, me))
        // Never "Asha dismissed Asha", nor "You dismissed you".
        assertNotEquals(Line.DISMISSED, AdminRules.line(Message.ROLE_DISMISSED, asha, asha, me))
        assertNotEquals(Line.YOU_DISMISSED, AdminRules.line(Message.ROLE_DISMISSED, me, me, me))
    }

    @Test fun onlyMyOwnPhoneSaysYouStartedThisGroup() {
        assertEquals(Line.YOU_STARTED, AdminRules.line(Message.ROLE_STARTED, me, "", me))
        assertNull(AdminRules.line(Message.ROLE_STARTED, asha, "", me))
    }

    @Test fun aSettingLineWithWordsThisVersionDoesntKnowIsNotShown() {
        for (actor in listOf(me, asha)) for (setting in listOf("mods", "", "ADMINS"))
            assertNull("$setting by $actor", AdminRules.line(Message.ROLE_SEND, actor, setting, me))
    }

    @Test fun aRenameOrAGoodbyeIsNeverWordedAsARoleChange() {
        for (kind in listOf(Message.NOTICE, Message.MEMBER_LEFT, Message.MEMBER_JOINED, Message.LEFT, Message.REJOINED, Message.SYSTEM, Envelope.CHAT))
            for (actor in listOf(me, asha)) assertNull("$kind by $actor", AdminRules.line(kind, actor, asha, me))
    }

    // ---------------------------------------------------------------- what a refused change says

    @Test fun everyRefusalIsToldOrSilentOnPurpose() {
        // A reason added later must be decided here, not fall into one of the cases by accident.
        val decided = mapOf(
            RoleChange.DONE to Told.SILENT, RoleChange.UNCHANGED to Told.SILENT, RoleChange.ELSEWHERE to Told.SILENT,
            RoleChange.STARTING to Told.NOT_READY, RoleChange.NOT_ADMIN to Told.NOT_ADMIN, RoleChange.CREATOR to Told.CREATOR,
            RoleChange.NOT_MEMBER to Told.LEFT, RoleChange.FULL to Told.FULL,
        )
        assertEquals(RoleChange.values().toSet(), decided.keys)
        for (why in RoleChange.values()) assertEquals("$why", decided[why], AdminRules.told(why))
        // Each refusal says its own thing.
        val refusals = RoleChange.values().map { AdminRules.told(it) }.filter { it != Told.SILENT }
        assertEquals(refusals.size, refusals.toSet().size)
    }

    @Test fun aChangeSomeoneElseAlreadyMadeSaysNothing() {
        // Two admins made the same change at once: the second finds it already so.
        assertEquals(Told.SILENT, AdminRules.told(RoleChange.UNCHANGED))
        // The question was about a group that isn't on the radio any more.
        assertEquals(Told.SILENT, AdminRules.told(RoleChange.ELSEWHERE))
    }

    @Test fun someoneWhoLeftIsSaidToHaveLeft() {
        // "Make group admin" for someone who left meanwhile must not do nothing silently.
        assertEquals(Told.LEFT, AdminRules.told(RoleChange.NOT_MEMBER))
    }
}
