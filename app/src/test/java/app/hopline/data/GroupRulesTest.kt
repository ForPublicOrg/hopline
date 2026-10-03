package app.hopline.data

import app.hopline.core.Words
import app.hopline.mesh.Group
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved-group list: who is a member, who was left, and where the radio goes — with left groups never lost. */
class GroupRulesTest {
    private val day = 86_400_000L
    private val now = 1_700_000_000_000L

    private val trek = "tiger-river-lamp"
    private val family = "apple-moon-drum"
    private val fest = "kite-olive-fern"
    private val old = "cave-owl-salt"

    private fun g(code: String, name: String = "", lastActive: Long = now - day, leftAt: Long = 0, sealed: Boolean = false,
                  joinedAt: Long = now - 30 * day, nameAt: Long = 0) =
        SavedGroup(code, name, joinedAt, lastActive, nameAt).also { it.leftAt = leftAt; it.sealed = sealed }

    private fun List<SavedGroup>.of(code: String) = single { it.code == code }
    private fun List<SavedGroup>.codes() = map { it.code }

    /** What Store does with a result: write it out and read it back. A left group must survive that too. */
    private fun saved(list: List<SavedGroup>) = GroupRules.parse(GroupRules.encode(list))

    // ---------------------------------------------------------------- reading and writing

    @Test fun aGroupSavedBy22ReadsBackAsAMember() {
        val raw = """[{"code":"tiger-river-lamp","name":"Trek","joinedAt":11,"lastActive":22,"nameAt":33}]"""
        val g = GroupRules.parse(raw).single()
        assertEquals(trek, g.code); assertEquals("Trek", g.name)
        assertEquals(11L, g.joinedAt); assertEquals(22L, g.lastActive); assertEquals(33L, g.nameAt)
        assertEquals(0L, g.leftAt); assertFalse(g.left); assertFalse(g.sealed)
        assertEquals(listOf(g.code), GroupRules.members(listOf(g)).codes())
        // …and a member is written back exactly as 2.2 wrote it: no new keys for an old build to trip on.
        val back = JSONArray(GroupRules.encode(listOf(g))).getJSONObject(0)
        assertEquals(setOf("code", "name", "joinedAt", "lastActive", "nameAt"), back.keySet())
        assertTrue(back.similar(JSONArray(raw).getJSONObject(0)))
    }

    @Test fun theLeftMarkAndTheSealSurviveARoundTrip() {
        val list = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now - 5, sealed = true), g(fest, leftAt = now - 9))
        val back = saved(list)
        assertEquals(listOf(trek, family, fest), back.codes())
        assertFalse(back.of(trek).left)
        assertEquals(now - 5, back.of(family).leftAt); assertTrue(back.of(family).left); assertTrue(back.of(family).sealed)
        assertEquals(now - 9, back.of(fest).leftAt); assertFalse(back.of(fest).sealed)
        assertEquals("Family", back.of(family).name)
    }

    @Test fun oneBadEntryDoesNotLoseTheRest() {
        val a = JSONArray()
            .put(g(trek, "Trek").toJson())
            .put(JSONObject().put("name", "no code at all"))
            .put(17)
            .put(g(family, "Family", leftAt = now).toJson())
        val back = GroupRules.parse(a.toString())
        assertEquals(listOf(trek, family), back.codes())
        assertTrue(back.of(family).left)
        assertTrue(GroupRules.parse(null).isEmpty())
        assertTrue(GroupRules.parse("").isEmpty())
        assertTrue(GroupRules.parse("not json").isEmpty())
    }

    @Test fun theFingerprintIsTheGroupsOwn() {
        val saved = g(trek)
        assertEquals(Group(trek, "").fingerprint, saved.fingerprint)
        assertSame(saved.fingerprint, saved.fingerprint)          // worked out once
        assertEquals(saved.fingerprint, saved.copy().fingerprint)
    }

    // ---------------------------------------------------------------- leaving

    @Test fun leavingKeepsTheGroupInTheListMarkedAsLeft() {
        val list = listOf(g(trek, "Trek", nameAt = 77), g(family, "Family"))
        val r = GroupRules.leave(list, trek, family, now)
        assertEquals(listOf(trek, family), r.groups.codes())
        val left = r.groups.of(family)
        assertTrue(left.left); assertEquals(now, left.leftAt); assertFalse(left.sealed)
        assertEquals("Family", left.name); assertEquals(list.of(family).joinedAt, left.joinedAt)
        assertEquals(list.of(family).lastActive, left.lastActive)
        assertEquals(trek, r.active)                                // a paused group: the radio stays where it is
        assertEquals(listOf(family), GroupRules.left(r.groups).codes())
        assertEquals(listOf(trek), GroupRules.members(r.groups).codes())
        // the list it was given is not touched — the entry that changed is a copy
        assertFalse(list.of(family).left)
        assertTrue(saved(r.groups).of(family).left)
    }

    @Test fun leavingTheActiveGroupMovesTheRadioToTheMostRecentMemberNeverALeftOne() {
        val list = listOf(
            g(trek, lastActive = now),
            g(family, lastActive = now - 3 * day),
            g(fest, lastActive = now - 2 * day),
            g(old, lastActive = now - 1000, leftAt = now - 500),    // used more recently than any member — but left
        )
        val r = GroupRules.leave(list, trek, trek, now)
        assertEquals(fest, r.active)
        assertEquals(4, r.groups.size)
        assertEquals(listOf(trek, old), GroupRules.left(r.groups).codes())   // newest-left first
    }

    @Test fun leavingTheOnlyGroupLeavesNoActiveGroupButTheListIsNotEmpty() {
        val r = GroupRules.leave(listOf(g(trek, "Trek")), trek, trek, now)
        assertNull(r.active)
        assertEquals(listOf(trek), r.groups.codes())
        assertTrue(r.groups.single().left)
        assertTrue(GroupRules.members(r.groups).isEmpty())
        // and with only left groups around, the radio still has nowhere to go
        val two = GroupRules.leave(listOf(g(trek), g(family, leftAt = now - day)), trek, trek, now)
        assertNull(two.active)
        assertEquals(2, two.groups.size)
    }

    @Test fun leavingAgainKeepsTheFirstDateAndTheTidyUpAlreadyDone() {
        val list = listOf(g(trek), g(family, leftAt = now - day, sealed = true))
        val r = GroupRules.leave(list, trek, family, now)
        assertEquals(now - day, r.groups.of(family).leftAt)
        assertTrue(r.groups.of(family).sealed)
        assertEquals(trek, r.active)
        // a code this phone doesn't have changes nothing
        val none = GroupRules.leave(list, trek, "no such group", now)
        assertEquals(list.codes(), none.groups.codes()); assertEquals(trek, none.active)
    }

    @Test fun onlyALeftGroupIsEverSealed() {
        val list = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now - day), g(fest, leftAt = now - 2 * day, sealed = true))
        val r = GroupRules.seal(list, "Apple MOON drum")
        assertTrue(r.of(family).sealed); assertEquals(now - day, r.of(family).leftAt); assertEquals("Family", r.of(family).name)
        assertFalse(list.of(family).sealed)                         // the list it was given is not touched
        assertTrue(saved(r).of(family).sealed)
        assertEquals(3, r.size)
        // a member (it may have been joined again while the tidy-up ran), a group already sealed and
        // a code this phone doesn't have: nothing changes, and nothing needs saving
        assertSame(list, GroupRules.seal(list, trek))
        assertSame(list, GroupRules.seal(list, fest))
        assertSame(list, GroupRules.seal(list, "wizard pirate robot"))
        // joining again starts from a clean slate: the next leave is tidied up afresh
        assertFalse(GroupRules.rejoin(r, trek, family, now).groups.of(family).sealed)
    }

    // ---------------------------------------------------------------- a left group is never on the radio

    @Test fun aLeftGroupCanNeverBeMadeActive() {
        val list = listOf(g(trek, lastActive = now - day), g(family, leftAt = now - day, lastActive = now - 2 * day))
        val r = GroupRules.setActive(list, trek, family, now)
        assertEquals(trek, r.active)
        assertEquals(now - 2 * day, r.groups.of(family).lastActive)
        assertTrue(r.groups.of(family).left)
        // nor a code this phone has never had
        assertEquals(trek, GroupRules.setActive(list, trek, "wizard pirate robot", now).active)
        // while a member switches as before
        val two = listOf(g(trek), g(fest), g(family, leftAt = now - day))
        val s = GroupRules.setActive(two, trek, "Kite, Olive FERN", now)
        assertEquals(fest, s.active); assertEquals(now, s.groups.of(fest).lastActive)
        assertEquals(3, s.groups.size)
    }

    @Test fun healFixesADanglingActiveCode() {
        val list = listOf(g(trek, lastActive = now - 2 * day), g(fest, lastActive = now - day), g(family, leftAt = now, lastActive = now))
        assertEquals(trek, GroupRules.heal(list, trek))                       // fine as it is
        assertEquals(fest, GroupRules.heal(list, family))                     // points at a group that was left
        assertEquals(fest, GroupRules.heal(list, "gone-gone-gone"))           // points at nothing
        assertEquals(fest, GroupRules.heal(list, null))
        assertNull(GroupRules.heal(listOf(g(family, leftAt = now)), family))  // only left groups: no radio
        assertNull(GroupRules.heal(emptyList(), trek))
    }

    @Test fun nextActiveSkipsLeftGroupsAndTheOneBeingTakenAway() {
        val list = listOf(g(trek, lastActive = now), g(fest, lastActive = now - day), g(family, leftAt = now, lastActive = now))
        assertEquals(trek, GroupRules.nextActive(list))
        assertEquals(fest, GroupRules.nextActive(list, excluding = trek))
        assertNull(GroupRules.nextActive(listOf(g(family, leftAt = now))))
    }

    // ---------------------------------------------------------------- nothing a member does drops a left group

    @Test fun renameSwitchAddLeaveAndRemoveOnAMemberNeverDropLeftGroups() {
        val start = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now - 2 * day, sealed = true), g(fest, "Fest"),
            g(old, "Old", leftAt = now - 9 * day))
        fun check(what: String, groups: List<SavedGroup>) {
            for (list in listOf(groups, saved(groups))) {
                assertEquals(what, listOf(family, old), GroupRules.left(list).codes())
                assertEquals(what, now - 2 * day, list.of(family).leftAt); assertTrue(what, list.of(family).sealed)
                assertEquals(what, "Family", list.of(family).name)
                assertEquals(what, now - 9 * day, list.of(old).leftAt); assertFalse(what, list.of(old).sealed)
            }
        }
        check("rename", GroupRules.rename(start, trek, "Kedarkantha", now))
        check("switch", GroupRules.setActive(start, trek, fest, now).groups)
        check("add a new group", GroupRules.add(start, trek, "wizard pirate robot", "New", now, now).groups)
        check("add a group already here", GroupRules.add(start, trek, fest, "", 0, now).groups)
        check("leave a member", GroupRules.leave(start, trek, trek, now).groups.filter { it.code != trek })
        check("remove a member", GroupRules.remove(start, trek, fest).groups)
        assertEquals(4, GroupRules.rename(start, trek, "Kedarkantha", now).size)
        assertEquals(5, GroupRules.add(start, trek, "wizard pirate robot", "New", now, now).groups.size)
    }

    @Test fun renameChangesOnlyTheNamedGroup() {
        val list = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now))
        val r = GroupRules.rename(list, "Tiger River Lamp", "  Kedarkantha\n2026 ", 555)
        assertEquals("Kedarkantha 2026", r.of(trek).name); assertEquals(555L, r.of(trek).nameAt)
        assertEquals("Family", r.of(family).name)
        assertEquals("Trek", list.of(trek).name)                    // the list it was given is not touched
        assertSame(list, GroupRules.rename(list, "no such group", "x", 1))
    }

    // ---------------------------------------------------------------- joining again

    @Test fun rejoinClearsTheMarkAndKeepsNameAndJoinedAt() {
        val list = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now - day, sealed = true, joinedAt = 4242, nameAt = 99))
        val r = GroupRules.rejoin(list, trek, family, now)
        val back = r.groups.of(family)
        assertFalse(back.left); assertEquals(0L, back.leftAt); assertFalse(back.sealed)
        assertEquals("Family", back.name); assertEquals(4242L, back.joinedAt); assertEquals(99L, back.nameAt)
        assertEquals(now, back.lastActive)
        assertEquals(family, r.active)
        assertEquals(2, r.groups.size)
        assertFalse(saved(r.groups).of(family).left)
    }

    @Test fun rejoinOnlyWorksOnALeftGroup() {
        val list = listOf(g(trek, lastActive = now - day), g(fest, lastActive = now - 2 * day))
        val member = GroupRules.rejoin(list, trek, fest, now)
        assertEquals(trek, member.active); assertEquals(now - 2 * day, member.groups.of(fest).lastActive)
        val unknown = GroupRules.rejoin(list, trek, "wizard pirate robot", now)
        assertEquals(trek, unknown.active); assertEquals(2, unknown.groups.size)
    }

    @Test fun addingALeftGroupsCodeAgainUnLeavesItAndKeepsWhatItWas() {
        val list = listOf(g(trek, "Trek"), g(family, "Family", leftAt = now - day, sealed = true, joinedAt = 4242, nameAt = 99))
        val r = GroupRules.add(list, trek, "Apple Moon Drum", "A name from a link", 12345, now)
        assertEquals(2, r.groups.size)                              // not added a second time
        val back = r.groups.of(family)
        assertFalse(back.left); assertFalse(back.sealed)
        assertEquals("Family", back.name); assertEquals(4242L, back.joinedAt); assertEquals(99L, back.nameAt)
        assertEquals(now, back.lastActive)
        assertEquals(family, r.active)
    }

    @Test fun addingANewGroupAppendsItAndMakesItActive() {
        val list = listOf(g(trek, "Trek"), g(family, leftAt = now - day))
        val made = GroupRules.add(list, trek, "Kite olive fern", "Fest", 777, now)
        assertEquals(listOf(trek, family, fest), made.groups.codes())
        val g = made.groups.of(fest)
        assertEquals("Fest", g.name); assertEquals(777L, g.nameAt); assertEquals(now, g.joinedAt); assertEquals(now, g.lastActive)
        assertFalse(g.left)
        assertEquals(fest, made.active)
        // joined by typed code: no name yet, so no name time either
        assertEquals(0L, GroupRules.add(list, trek, fest, "", 777, now).groups.of(fest).nameAt)
        // nothing that isn't a code is ever saved
        val junk = GroupRules.add(list, trek, " 123 ", "x", 0, now)
        assertEquals(2, junk.groups.size); assertEquals(trek, junk.active)
    }

    @Test fun addingAMemberAgainOnlyFillsAnEmptyName() {
        val list = listOf(g(trek, "Trek", nameAt = 5), g(fest, ""))
        val named = GroupRules.add(list, trek, trek, "A link's name", 999, now)
        assertEquals("Trek", named.groups.of(trek).name); assertEquals(5L, named.groups.of(trek).nameAt)
        assertEquals(now, named.groups.of(trek).lastActive)
        val filled = GroupRules.add(list, trek, fest, "Fest", 999, now)
        assertEquals("Fest", filled.groups.of(fest).name)
        assertEquals(fest, filled.active)
        assertEquals(2, filled.groups.size)
    }

    // ---------------------------------------------------------------- deleting

    @Test fun removeTakesTheGroupOutAndNeverActivatesALeftGroup() {
        val list = listOf(g(trek, lastActive = now), g(family, leftAt = now - day, lastActive = now), g(fest, lastActive = now - 5 * day))
        val gone = GroupRules.remove(list, trek, family)
        assertEquals(listOf(trek, fest), gone.groups.codes()); assertEquals(trek, gone.active)
        // taking away the active one moves the radio to a member — never to the left group
        val moved = GroupRules.remove(list, trek, trek)
        assertEquals(listOf(family, fest), moved.groups.codes()); assertEquals(fest, moved.active)
        val last = GroupRules.remove(listOf(g(trek), g(family, leftAt = now)), trek, trek)
        assertNull(last.active); assertEquals(listOf(family), last.groups.codes())
        // deleting the last left group empties the list
        assertTrue(GroupRules.remove(last.groups, null, family).groups.isEmpty())
    }

    @Test fun prefKeysForNamesAllThree() {
        assertEquals(listOf("read-ab12cd34", "mute-ab12cd34", "unread-ab12cd34"), GroupRules.prefKeysFor("ab12cd34"))
    }

    @Test fun theCodeIsNormalisedHoweverItWasTyped() {
        val list = listOf(g(trek), g(family, leftAt = now - day))
        assertTrue(GroupRules.leave(list, trek, "  Tiger, RIVER   lamp ", now).groups.of(trek).left)
        assertEquals(family, GroupRules.rejoin(list, trek, "APPLE moon drum", now).active)
        assertEquals(listOf(trek), GroupRules.remove(list, trek, "apple  moon  drum").groups.codes())
        assertEquals(Words.normalise("wizard pirate robot"), GroupRules.add(list, trek, "Wizard Pirate Robot", "", 0, now).active)
    }

    @Test fun leftGroupsAreListedNewestLeftFirst() {
        val list = listOf(g(trek, leftAt = now - 3 * day), g(family), g(fest, leftAt = now - day), g(old, leftAt = now - 2 * day))
        assertEquals(listOf(fest, old, trek), GroupRules.left(list).codes())
        assertEquals(listOf(family), GroupRules.members(list).codes())
    }
}
