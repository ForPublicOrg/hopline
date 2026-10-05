package app.hopline.ui

import app.hopline.data.Upgrade
import app.hopline.mesh.Envelope
import app.hopline.mesh.FakeNet
import app.hopline.mesh.Message
import app.hopline.mesh.Person
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The people of a group on screen, after the update as before: names that tell apart only who could be confused, and nobody lost. */
class NamesTest {
    @Test fun aMemberIsNotTheirOwnNamesakeAfterTheUpdate() {
        val net = FakeNet(); net.line("A", "B")                 // B is heard on 2.4, under its new id
        val a = net.nodes["A"]!!.router
        // B as this phone knew them before the update: kept only so their old messages keep a name
        val old = "k3j9x2pa"
        a.people[old] = Person(old).also { it.name = "B" }
        assertEquals("B", Ui.uniqueName(a, old, "B"))
        assertEquals("B", Ui.uniqueName(a, net.id("B"), "B"))
        // two phones on the air with one name are still told apart — and the old id still isn't anyone's namesake
        net.node("C", "B"); net.connect("A", "C")
        assertEquals("B · ${net.id("B").takeLast(4)}", Ui.uniqueName(a, net.id("B")))
        assertEquals("B · ${net.id("C").takeLast(4)}", Ui.uniqueName(a, net.id("C")))
        assertEquals("B", Ui.uniqueName(a, old))
    }

    @Test fun aGroupWhoseFriendsAreAllFromBeforeTheUpdateIsNotOneNobodyJoined() {
        val net = FakeNet(); val a = net.node("A")
        assertFalse("a group of one", Ui.othersKnown(a.router))
        val old = "k7m2p9qa"; val bea = "b3a4b3a4"           // this phone, and a friend, before 2.4
        val saved = JSONObject()
            .put("messages", JSONArray(listOf(Message("t00000000001", Envelope.CHAT, bea, "Bea", null, "see you at the pass", net.now - 3_600_000L).toJson())))
            .put("people", JSONArray(listOf(Person(bea).also { it.name = "Bea" }.toJson(), Person(old).also { it.name = "A" }.toJson())))
        a.router.restore(JSONObject(Upgrade.state(saved, a.id, setOf(old), net.now, left = false)!!.toString()))
        // People lists nobody else (only 2.4 ids are listed)…
        assertTrue(a.router.people.keys.none { it != a.id && ChatRules.listed(it) })
        // …and yet Bea is in this group: "nobody has joined" would be untrue
        assertTrue(Ui.othersKnown(a.router))
    }
}
