package app.hopline.mesh

import app.hopline.core.Crypto
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Random
import java.util.TreeMap

/** Who runs a group, on its own: signed role ops, the founder, the fold, the timeline posts are judged by, and the set's cap and digest. */
class RolesTest {
    private val TAG = "0123456789abcdef"
    private val OTHER_TAG = "fedcba9876543210"
    private val T0 = 1_700_000_000_000L
    private val MIN = 60_000L
    private val HOUR = 60 * MIN
    private val DAY = 24 * HOUR
    private val YEAR = 365 * DAY
    private val SLACK = Roles.SLACK_MS

    private fun keys(label: String) = FakeNet.keysOf(label)
    private fun id(label: String) = FakeNet.idOf(label)
    private fun roles(label: String) = Roles(TAG, FakeNet.keysOf(label))
    private fun op(label: String, n: Int, ts: Long, type: String, vararg extra: Pair<String, Any>) =
        RoleOp.make(FakeNet.keysOf(label), TAG, n, ts, type, mapOf(*extra))

    private fun found(label: String, ts: Long = T0, sure: Boolean = true) = op(label, 1, ts, RoleOp.FOUND, "sure" to if (sure) 1L else 0L)
    private fun grant(by: String, n: Int, x: String, ts: Long = T0) = op(by, n, ts, RoleOp.GRANT, "x" to id(x))
    private fun revoke(by: String, n: Int, x: String, ts: Long = T0) = op(by, n, ts, RoleOp.REVOKE, "x" to id(x))
    private fun setting(by: String, n: Int, only: Boolean, ts: Long = T0) =
        op(by, n, ts, RoleOp.SET, "k" to RoleOp.SEND, "w" to if (only) RoleOp.ADMINS else RoleOp.ALL)
    private fun onlyAdmins(by: String, n: Int, ts: Long = T0) = setting(by, n, true, ts)
    private fun everyone(by: String, n: Int, ts: Long = T0) = setting(by, n, false, ts)

    /** [op] as another phone hands it over: read off the wire, its signature not checked yet. */
    private fun heard(op: RoleOp): RoleOp = FakeNet.heard(op)
    private fun heard(ops: List<RoleOp>): List<RoleOp> = ops.map { heard(it) }
    /** A forgery: [op]'s signature on changed content. */
    private fun forged(op: RoleOp, ts: Long = op.ts + 1): RoleOp = RoleOp.parse(op.toJson().put("ts", ts))!!
    /** [to] takes everything [from] has, as it would over a link. */
    private fun sync(from: Roles, to: Roles) = to.accept(heard(from.wire()))
    private fun ids(ops: List<RoleOp>) = ops.map { it.id }

    /** What a phone makes of its ops: everything another phone holding the same ops must agree on. */
    private data class Folded(
        val admins: List<String>, val onlyAdmins: Boolean, val sendBy: String?, val effective: List<String>, val told: List<String>,
        val timeline: List<Triple<Long, Set<String>, Boolean>>, val digest: String, val maySend: List<Boolean>,
    )

    private val senders = listOf("F", "A", "B", "C", "D", "E", "M", "N", "Z")
    private val stamps = (-20..140).map { T0 + it * 30_000L } + listOf(0L, T0 + YEAR)

    private fun stateOf(r: Roles) = Folded(
        r.admins, r.onlyAdmins, r.sendBy, ids(r.effective), ids(r.told), r.timeline.map { Triple(it.from, it.admins, it.onlyAdmins) }, r.digest,
        senders.flatMap { x -> stamps.map { r.maySend(id(x), it) } },
    )

    /**
     * 24 ops: F founds the group, sure of it; A to E are made admins along the way, two of them making
     * changes at once more than once, and some stamped before changes they come after in fold order;
     * F's redundant and odd ops, a type this version doesn't know, the founder's jump; M's and N's
     * ops, which count for nothing; two of E's made before E's grant; and M's claim to have started
     * the group, which F's sure found replaces.
     */
    private val fixture: List<RoleOp> by lazy {
        listOf(
            found("F", T0),
            grant("F", 2, "A", T0 + 1 * MIN),
            grant("F", 3, "B", T0 + 2 * MIN),
            grant("A", 4, "C", T0 + 3 * MIN), onlyAdmins("B", 4, T0 + 5 * MIN),
            revoke("C", 5, "B", T0 + 12 * MIN), revoke("F", 5, "C", T0 + 2 * MIN + MIN / 2),   // F's slow clock: before the grant it undoes
            grant("B", 6, "D", T0 + 14 * MIN), everyone("D", 7, T0 + 20 * MIN), everyone("A", 6, T0 + 25 * MIN),
            grant("F", 7, "F", T0 + 4 * MIN), onlyAdmins("F", 8, T0 + 30 * MIN), op("A", 7, T0 + 22 * MIN, "pin", "x" to id("B")),
            op("F", 8, T0 + 31 * MIN, RoleOp.GRANT, "x" to "not-a-node-id"),
            grant("A", 9, "E", T0 + 34 * MIN), revoke("A", 9, "E", T0 + 33 * MIN),
            everyone("F", 2000, T0 + 50 * MIN),
            grant("M", 3, "M", T0 + 6 * MIN), everyone("M", 10, T0 + 40 * MIN), revoke("N", 5, "A", T0 + 15 * MIN),
            revoke("B", 7, "F", T0 + 21 * MIN),
            onlyAdmins("E", 5, T0 + 7 * MIN), grant("E", 10, "M", T0 + 32 * MIN),
            found("M", T0 - 5 * MIN, sure = false),
        )
    }

    /** For every subject the chat tells of, the newest told op says what it is now. */
    private fun assertToldIsState(r: Roles, why: String) {
        val newest = LinkedHashMap<String, RoleOp>()
        for (op in r.told) newest[if (op.type == RoleOp.SET) "send" else op.target!!] = op
        for ((subject, op) in newest)
            if (subject == "send") assertEquals(why, r.onlyAdmins, op.value == RoleOp.ADMINS)
            else assertEquals("$why: $subject", r.isAdmin(subject), op.type == RoleOp.GRANT)
        for (x in r.admins.drop(1)) assertEquals("$why: $x", RoleOp.GRANT, newest[x]?.type)
        if (r.onlyAdmins) assertEquals(why, RoleOp.ADMINS, newest["send"]?.value)
    }

    // ---------------------------------------------------------------- the op on the wire

    @Test fun `an op signed by its author verifies, and one changed in any field does not`() {
        val o = onlyAdmins("A", 7)
        assertTrue(o.verify(TAG))
        assertTrue("as read off the wire", heard(o).verify(TAG))
        val changes = listOf<Pair<String, Any>>("a" to id("B"), "pk" to keys("B").pubB64, "n" to 8L, "ts" to T0 + 1, "t" to RoleOp.GRANT,
            "k" to "sund", "w" to RoleOp.ALL)
        for ((k, v) in changes) assertFalse(k, RoleOp.parse(o.toJson().put(k, v))!!.verify(TAG))
        // someone else's id and key together: the key is theirs, the signature isn't
        assertFalse(RoleOp.parse(o.toJson().put("a", id("B")).put("pk", keys("B").pubB64))!!.verify(TAG))
        // a field added, a field taken away
        assertFalse(RoleOp.parse(o.toJson().put("x", id("B")))!!.verify(TAG))
        assertFalse(RoleOp.parse(o.toJson().apply { remove("w") })!!.verify(TAG))
        // the author's real signature, over something else
        assertFalse(RoleOp.parse(o.toJson().put("s", Crypto.sign(keys("A").priv, "something else".toByteArray())))!!.verify(TAG))
    }

    @Test fun `an op signed for another group's code does not verify here`() {
        val there = RoleOp.make(keys("F"), OTHER_TAG, 2, T0, RoleOp.GRANT, mapOf("x" to id("A")))
        assertTrue(heard(there).verify(OTHER_TAG))
        assertFalse(heard(there).verify(TAG))
        // nor is it kept: it is checked as soon as it could count here, and dropped
        val r = roles("F"); r.found(true, T0)
        r.accept(listOf(heard(there)))
        assertFalse(r.knows(there.id))
        assertFalse(r.isAdmin(id("A")))
    }

    @Test fun `an op whose key is not the one its author's id is made from is refused`() {
        // M signs, with M's own key, an op that says F made it
        val fields = TreeMap<String, Any>(mapOf("a" to id("F"), "pk" to keys("M").pubB64, "n" to 2L, "ts" to T0, "t" to RoleOp.GRANT, "x" to id("M")))
        val j = JSONObject(); for ((k, v) in fields) j.put(k, v)
        j.put("s", Crypto.sign(keys("M").priv, RoleOp.signedBytes(TAG, fields)))
        val claimed = RoleOp.parse(j)!!
        assertTrue("the signature itself is good", Crypto.verify(keys("M").pub, RoleOp.signedBytes(TAG, claimed.fields), claimed.sig))
        assertFalse(claimed.verify(TAG))
        val r = roles("F"); r.found(true, T0)
        r.accept(listOf(claimed))
        assertFalse(r.knows(claimed.id))
        assertEquals(listOf(id("F")), r.admins)
    }

    @Test fun `the same content signed twice is the same op`() {
        val one = grant("F", 2, "A"); val two = grant("F", 2, "A")
        assertEquals(one.id, two.id)
        assertNotEquals("ECDSA signs differently each time", one.sig, two.sig)
        assertTrue(heard(one).verify(TAG) && heard(two).verify(TAG))
        assertNotEquals(one.id, grant("F", 2, "B").id)
        assertNotEquals(one.id, grant("F", 2, "A", T0 + 1).id)
        assertNotEquals(one.id, grant("F", 3, "A").id)
    }

    @Test fun `an op's id doesn't depend on the group's key, but its signature does`() {
        val here = grant("F", 2, "A")
        val there = RoleOp.make(keys("F"), OTHER_TAG, 2, T0, RoleOp.GRANT, mapOf("x" to id("A")))
        assertEquals(here.id, there.id)
        assertEquals(22, here.id.length)
        assertEquals("the signed form with no group at all", Crypto.b64(Crypto.sha256(RoleOp.signedBytes("", here.fields)).copyOf(16)), here.id)
        assertFalse(RoleOp.signedBytes(TAG, here.fields).contentEquals(RoleOp.signedBytes(OTHER_TAG, here.fields)))
        assertFalse(heard(there).verify(TAG))
        assertFalse(heard(here).verify(OTHER_TAG))
    }

    @Test fun `the wire form reads back to the same op, the same id and the same signed bytes`() {
        val ops = listOf(found("F"), found("F", sure = false), grant("F", 2, "A"), revoke("F", 3, "A"), onlyAdmins("F", 4), everyone("F", 5),
            op("F", 6, T0, "pin", "x" to 7L, "why" to "later"))
        for (o in ops) {
            val back = RoleOp.parse(JSONObject(o.toJson().toString()))!!
            assertEquals(o.id, back.id)
            assertEquals(o.sig, back.sig)
            assertEquals(o.fields, back.fields)
            assertTrue(RoleOp.signedBytes(TAG, o.fields).contentEquals(RoleOp.signedBytes(TAG, back.fields)))
            assertTrue(back.verify(TAG))
            assertTrue(o.checked)
            assertFalse("read off the wire, not checked yet", back.checked)
            assertEquals(o.author, back.author); assertEquals(o.pk, back.pk); assertEquals(o.n, back.n); assertEquals(o.ts, back.ts)
            assertEquals(o.type, back.type); assertEquals(o.target, back.target); assertEquals(o.value, back.value); assertEquals(o.sure, back.sure)
            assertTrue(o.toJson().similar(back.toJson()))
        }
        assertEquals(id("A"), heard(ops[2]).target)
        assertTrue(heard(ops[0]).sure)
        assertFalse(heard(ops[1]).sure)
        assertEquals(RoleOp.ADMINS, heard(ops[4]).value)
    }

    @Test fun `a float, a boolean, a nested value, an odd key, an empty string or a thirteenth field is refused unread`() {
        val good = onlyAdmins("A", 3).toJson().toString()   // eight fields
        fun with(k: String, v: Any) = RoleOp.parse(JSONObject(good).put(k, v))
        assertNotNull(RoleOp.parse(JSONObject(good)))
        assertNotNull(with("y", 12L)); assertNotNull(with("y", "Some_text-1"))
        val refused = listOf<Any>(1.5, 2.0, true, false, JSONObject().put("y", 1), JSONArray().put(1), JSONObject.NULL, "", "has space", "ü",
            "x".repeat(101), BigInteger("123456789012345678901234567890"), BigDecimal("2.5"))
        for (v in refused) assertNull("$v", with("y", v))
        // a fraction as it comes off the air, even a whole one, and in a field every op has
        assertNull(RoleOp.parse(JSONObject(good.replaceFirst("{", "{\"y\":2.0,"))))
        assertNull(with("ts", T0.toDouble()))
        assertNull(with("n", 3.0))
        for (k in listOf("Y", "y1", "ninelettr", "_", "x-y", "")) assertNull(k, with(k, "v"))
        assertNotNull("eight letters is a key", with("eightlet", "v"))
        // the fields every op has must be what they say
        assertNull(with("a", "abcdefgh"))
        assertNull(with("pk", keys("A").pubB64 + "A"))
        assertNull(with("t", "Set"))
        assertNull(with("t", 3L))
        assertNull(RoleOp.parse(JSONObject(good).apply { remove("ts") }))
        // the signature
        assertNull(with("s", "short"))
        assertNull(with("s", 12345678L))
        assertNull(with("s", "a+b/c=defghijk"))
        assertNull(RoleOp.parse(JSONObject(good).apply { remove("s") }))
        // up to twelve fields are read, a thirteenth is refused
        val twelve = JSONObject(good).put("aa", 1).put("bb", 2).put("cc", 3).put("dd", 4)
        assertEquals(12, twelve.length())
        assertNotNull(RoleOp.parse(twelve))
        assertNull(RoleOp.parse(twelve.put("ee", 5)))
    }

    @Test fun `a counter outside its range, a found with a counter other than 1, and a negative stamp are refused`() {
        val g = grant("F", 2, "A").toJson().toString()
        val f = found("F").toJson().toString()
        fun with(j: String, k: String, v: Any) = RoleOp.parse(JSONObject(j).put(k, v))
        for (n in listOf(0L, -1L, RoleOp.MAX_N + 1L, Long.MAX_VALUE)) assertNull("n = $n", with(g, "n", n))
        assertNotNull(with(g, "n", 1L)); assertNotNull(with(g, "n", RoleOp.MAX_N.toLong()))
        assertNotNull(RoleOp.parse(JSONObject(f)))
        for (n in listOf(2L, 0L, 3L)) assertNull("found n = $n", with(f, "n", n))
        for (sure in listOf<Any>(2L, -1L, "1")) assertNull("sure = $sure", with(f, "sure", sure))
        assertNull("a found says whether it is sure", RoleOp.parse(JSONObject(f).apply { remove("sure") }))
        assertNotNull(with(f, "sure", 0L))
        for (ts in listOf(-1L, Long.MIN_VALUE, RoleOp.MAX_TS + 1)) assertNull("ts = $ts", with(g, "ts", ts))
        assertNotNull(with(g, "ts", 0L)); assertNotNull(with(g, "ts", RoleOp.MAX_TS))
    }

    @Test fun `a type this version doesn't know is kept and passed on, and changes nothing`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F"), grant("F", 2, "A"))))
        val before = stateOf(r)
        val unknown = op("A", 3, T0 + MIN, "pin", "x" to id("B"))
        assertEquals(Roles.Change.SET, r.accept(listOf(heard(unknown))))
        assertTrue("kept and passed on", unknown.id in ids(r.wire()))
        assertFalse(unknown.id in ids(r.effective))
        assertNotEquals(before.digest, r.digest)
        assertEquals("nothing but the set changed", before.copy(digest = r.digest), stateOf(r))
        assertEquals("but it counts toward the counter", 4, r.nextN())
    }

    @Test fun `a field this version doesn't know on a grant is signed, kept, and the grant still counts`() {
        val g = op("F", 2, T0, RoleOp.GRANT, "x" to id("A"), "why" to "trusted")
        val back = heard(g)
        assertEquals("trusted", back.fields["why"])
        assertTrue(back.verify(TAG))
        assertFalse("signed", RoleOp.parse(g.toJson().put("why", "other"))!!.verify(TAG))
        val r = roles("Z")
        r.accept(listOf(heard(found("F")), back))
        assertTrue(r.isAdmin(id("A")))
        assertEquals(listOf(g.id), ids(r.effective))
        assertEquals("kept, with it", "trusted", r.toJson().getJSONArray("ops").getJSONObject(0).getString("why"))
    }

    @Test fun `a grant whose target is a number and a set whose key or value is a number are kept and change nothing`() {
        val odd = listOf(op("F", 2, T0, RoleOp.GRANT, "x" to 12345L), op("F", 3, T0, RoleOp.SET, "k" to 5L, "w" to RoleOp.ADMINS),
            op("F", 4, T0, RoleOp.SET, "k" to RoleOp.SEND, "w" to 1L), op("F", 5, T0, RoleOp.REVOKE, "x" to 7L))
        val r = roles("Z")
        assertEquals(Roles.Change.FOLD, r.accept(heard(listOf(found("F")) + odd)))   // only the founder changed anything
        assertEquals(ids(odd), ids(r.wire().drop(1)))
        assertTrue(r.effective.isEmpty())
        assertEquals(listOf(id("F")), r.admins)
        assertFalse(r.onlyAdmins)
        assertNull(r.wire()[1].target); assertNull(r.wire()[2].key); assertNull(r.wire()[3].value)
        assertEquals("valid all the same, so counted", 6, r.nextN())
    }

    // ---------------------------------------------------------------- the founder

    @Test fun `with no founder nobody is an admin and everyone can send at any time`() {
        val r = roles("A")
        r.accept(heard(listOf(grant("F", 2, "A"), onlyAdmins("F", 3, T0 + MIN), onlyAdmins("A", 4, T0 + 2 * MIN))))
        assertNull(r.founder)
        assertFalse(r.founderSure)
        assertTrue(r.admins.isEmpty())
        assertFalse(r.isAdmin(r.me))
        assertFalse(r.onlyAdmins)
        assertTrue(r.isEmpty)
        for (x in senders) for (t in stamps + listOf(Long.MIN_VALUE, -1L, Long.MAX_VALUE)) {
            assertTrue("$x at $t", r.maySend(id(x), t))
            assertNull(r.barredSince(id(x), t))
        }
        assertEquals(0L, r.allowedFrom(id("M")))
        assertNull("nobody can make a change", r.grant(id("B"), T0))
        assertNull(r.setOnlyAdmins(true, T0))
    }

    @Test fun `the first sure founder sticks whatever comes after`() {
        val r = roles("Z")
        r.accept(listOf(heard(found("F", T0))))
        r.accept(listOf(heard(found("G", T0 - DAY))))
        r.accept(listOf(heard(found("H", T0 - 2 * DAY, sure = false))))
        r.accept(heard(listOf(found("K", T0 - 3 * DAY), grant("K", 2, "M"))))
        assertNull("nor does a found of mine take its place", r.found(true, T0 - 4 * DAY))
        assertEquals(id("F"), r.founder)
        assertTrue(r.founderSure)
        assertEquals(listOf(id("F")), r.admins)
        // a phone that took G first sticks with G
        val other = roles("Y")
        other.accept(listOf(heard(found("G", T0 - DAY))))
        other.accept(listOf(heard(found("F", T0))))
        assertEquals(id("G"), other.founder)
    }

    @Test fun `of two claims the earlier stamp is the founder, whichever arrives first`() {
        val early = found("G", T0 - MIN, sure = false); val late = found("F", T0, sure = false)
        for (order in listOf(listOf(early, late), listOf(late, early))) {
            val one = roles("Z"); for (f in order) one.accept(listOf(heard(f)))
            val batch = roles("Y"); batch.accept(heard(order))
            for (r in listOf(one, batch)) { assertEquals(id("G"), r.founder); assertFalse(r.founderSure) }
        }
        // the same stamp: the lower id, either way
        val lower = if (id("F") < id("G")) "F" else "G"
        val a = found("F", T0, sure = false); val b = found("G", T0, sure = false)
        for (order in listOf(listOf(a, b), listOf(b, a))) {
            val r = roles("Z"); for (f in order) r.accept(listOf(heard(f)))
            assertEquals(id(lower), r.founder)
        }
    }

    @Test fun `a sure founder replaces a claim, and the claim's admins go with it`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("G", T0 - DAY, sure = false), grant("G", 2, "A"), onlyAdmins("A", 3))))
        assertEquals(id("G"), r.founder)
        assertTrue(r.isAdmin(id("A")))
        assertTrue(r.onlyAdmins)
        val sure = found("F", T0)
        assertEquals(Roles.Change.FOLD, r.accept(listOf(heard(sure))))
        assertEquals(id("F"), r.founder)
        assertTrue(r.founderSure)
        assertEquals(listOf(id("F")), r.admins)
        assertFalse(r.onlyAdmins)
        assertEquals("the claim's ops are no longer in the set", listOf(sure.id), ids(r.wire()))
        // and no later claim takes it back
        r.accept(listOf(heard(found("G", T0 - 2 * DAY, sure = false))))
        assertEquals(id("F"), r.founder)
    }

    @Test fun `the same founder's two sure founds settle on one`() {
        val one = found("F", T0); val two = found("F", T0 + DAY)
        val lower = if (one.id < two.id) one else two
        for (order in listOf(listOf(one, two), listOf(two, one))) {
            val apart = roles("Z"); for (f in order) apart.accept(listOf(heard(f)))
            val batch = roles("Y"); batch.accept(heard(order))
            for (r in listOf(apart, batch)) { assertEquals(lower.id, r.pin!!.id); assertEquals(r.pin!!.id, r.adminBy(id("F"))) }
        }
    }

    @Test fun `founding again after the state is lost is the very same op`() {
        val r = roles("F")
        val first = r.found(true, T0)!!
        r.clear()
        assertNull(r.founder)
        val again = r.found(true, T0)!!
        assertEquals(first.id, again.id)
        assertEquals(first.id, roles("F").found(true, T0)!!.id)
        // a phone that kept the first sees nothing new in it
        val other = roles("Z")
        other.accept(listOf(heard(first)))
        assertEquals(Roles.Change.NONE, other.accept(listOf(heard(again))))
        assertEquals(first.id, other.pin!!.id)
        // a claim is another op
        assertNotEquals(first.id, roles("F").found(false, T0)!!.id)
    }

    // ---------------------------------------------------------------- checking signatures

    @Test fun `ops by someone nobody made an admin are never checked`() {
        val r = roles("F"); r.found(true, T0)
        r.accept((1..80).map { heard(onlyAdmins("M", it, T0 + it)) })
        assertEquals(0, r.signaturesChecked)
        for (i in 1..80) r.accept(listOf(heard(everyone("N", i, T0 + i))))
        assertEquals(0, r.signaturesChecked)
        // an op of the founder's is checked as soon as it comes
        r.accept(listOf(heard(grant("F", 2, "A"))))
        assertEquals(1, r.signaturesChecked)
    }

    @Test fun `found ops that could not take the founder's place are never checked`() {
        val r = roles("Z")
        r.accept(listOf(heard(found("F", T0))))
        assertEquals(1, r.signaturesChecked)
        r.accept((1..80).map { heard(found("M", T0 - it * MIN)) })
        r.accept((1..80).map { heard(found("N", T0 - it * MIN, sure = false)) })
        assertEquals(1, r.signaturesChecked)
        assertEquals(id("F"), r.founder)
    }

    @Test fun `a batch stops being checked at its first forged op, and none of the rest is kept`() {
        val r = roles("F"); r.found(true, T0)
        val real = (2..6).map { n -> setting("F", n, n % 2 == 0, T0 + n * MIN) }
        val fake = forged(real[2])                               // n = 4, between the second and the fourth
        val waiting = heard(grant("A", 7, "B"))                  // someone else's, not rooted
        r.accept((heard(listOf(real[0], real[1], real[3], real[4])) + fake + waiting).shuffled(Random(1)))
        assertEquals("checked up to the forgery and no further", 3, r.signaturesChecked)
        assertEquals(ids(real.take(2)), ids(r.wire().drop(1)))
        for (o in listOf(fake, real[3], real[4], waiting)) assertFalse(r.knows(o.id))
        // the rest, sent again honestly, are kept
        r.accept(heard(real.drop(2)))
        assertEquals(ids(real), ids(r.wire().drop(1)))
    }

    @Test fun `an op that waited unchecked is checked once its author is made an admin, and dropped if forged`() {
        val r = roles("F"); r.found(true, T0)
        val real = onlyAdmins("A", 3, T0 + MIN)
        val fake = forged(everyone("A", 4, T0 + 2 * MIN))
        r.accept(listOf(heard(real), fake))
        assertEquals("waiting, unchecked", 0, r.signaturesChecked)
        assertTrue(r.knows(real.id) && r.knows(fake.id))
        assertEquals(1, r.wire().size)
        val later = everyone("F", 5, T0 + 3 * MIN)
        r.accept(heard(listOf(grant("F", 2, "A"), later)))
        assertEquals("the grant, both of A's, and F's", 4, r.signaturesChecked)
        assertFalse(r.knows(fake.id))
        assertTrue(real.id in ids(r.wire()))
        assertTrue("its failure spoils nothing in the batch that rooted it", later.id in ids(r.wire()))

        // A copy of a real op with someone else's signature has the real one's id: it waits in its place,
        // the real one is skipped as known, the copy is dropped once rooted, and the real one sent again is kept.
        val p = roles("F"); p.found(true, T0)
        val o = onlyAdmins("B", 3, T0)
        val bad = RoleOp.parse(o.toJson().put("s", Crypto.sign(keys("M").priv, RoleOp.signedBytes(TAG, o.fields))))!!
        assertEquals(o.id, bad.id)
        p.accept(listOf(bad))
        p.accept(listOf(heard(o)))
        p.accept(listOf(heard(grant("F", 2, "B"))))
        assertFalse(p.knows(o.id))
        assertFalse(p.onlyAdmins)
        p.accept(listOf(heard(o)))
        assertTrue(p.onlyAdmins)
        // The same when the real one comes in the very batch that roots the copy: the copy waited here, it isn't
        // that batch's, so its failure spoils none of the batch.
        val q = roles("F"); q.found(true, T0)
        q.accept(listOf(bad))
        val alongside = grant("F", 5, "C", T0 + MIN)
        q.accept(heard(listOf(grant("F", 2, "B"), o, alongside)))
        assertFalse(q.knows(o.id))
        assertTrue(alongside.id in ids(q.wire()))
        assertTrue(q.isAdmin(id("C")))
        q.accept(listOf(heard(o)))
        assertTrue(q.onlyAdmins)
    }

    @Test fun `ops past a full set are never checked, however often they come`() {
        val r = roles("Z")
        r.accept(listOf(found("F", T0), grant("F", 2, "A", T0)) + (3..513).map { n -> setting("A", n, n % 2 == 1, T0 + n) })
        assertEquals(Roles.MAX_OPS, r.wire().size - 1)
        val full = ids(r.wire())
        // A's, above everything kept: they could never be kept, so they cost nothing however often they come
        val past = (600 until 680).map { n -> setting("A", n, n % 2 == 0, T0 + n) }
        repeat(3) { assertEquals(Roles.Change.NONE, r.accept(heard(past))) }
        assertEquals(0, r.signaturesChecked)
        assertEquals(full, ids(r.wire()))
        assertTrue(past.none { r.knows(it.id) })
        // one that sorts below the top is checked and kept, and pushes the top out
        val below = grant("A", 3, "B", T0)
        r.accept(listOf(heard(below)))
        assertEquals(1, r.signaturesChecked)
        assertTrue(below.id in ids(r.wire()))
        assertEquals(Roles.MAX_OPS, r.wire().size - 1)
        // and so is the founder's, however high
        val byF = everyone("F", 700, T0 + DAY)
        r.accept(listOf(heard(byF)))
        assertEquals(2, r.signaturesChecked)
        assertTrue(byF.id in ids(r.wire()))
        assertEquals(Roles.MAX_OPS, r.wire().size - 1)
    }

    // ---------------------------------------------------------------- the fold

    @Test fun `the founder is the first admin and can never be dismissed`() {
        val f = roles("F")
        val pin = f.found(true, T0)!!
        assertEquals(listOf(id("F")), f.admins)
        assertTrue(f.foundedBy(id("F")))
        assertEquals(pin.id, f.adminBy(id("F")))
        f.grant(id("A"), T0 + MIN)
        assertEquals(listOf(id("F"), id("A")), f.admins)
        assertNull(f.revoke(id("F"), T0 + 2 * MIN))
        val a = roles("A"); sync(f, a)
        assertNull(a.revoke(id("F"), T0 + 2 * MIN))
        // not even with an op made by hand
        val byHand = revoke("A", 3, "F", T0 + 2 * MIN)
        f.accept(listOf(heard(byHand)))
        assertTrue(byHand.id in ids(f.wire()))
        assertFalse(byHand.id in ids(f.effective))
        assertEquals(listOf(id("F"), id("A")), f.admins)
    }

    @Test fun `an admin can make anyone an admin and dismiss any admin but the founder`() {
        val f = roles("F"); f.found(true, T0); f.grant(id("A"), T0)
        val a = roles("A"); sync(f, a)
        assertNotNull(a.grant(id("B"), T0 + MIN))
        assertNotNull(a.grant(id("C"), T0 + MIN))
        assertNotNull(a.revoke(id("B"), T0 + 2 * MIN))
        assertNull(a.revoke(id("F"), T0 + 2 * MIN))
        assertEquals(listOf(id("F"), id("A"), id("C")), a.admins)
        // C can dismiss the admin who made them one
        val c = roles("C"); sync(a, c)
        assertNotNull(c.revoke(id("A"), T0 + 3 * MIN))
        assertEquals(listOf(id("F"), id("C")), c.admins)
        sync(c, f)
        assertEquals(c.admins, f.admins)
        assertEquals(c.digest, f.digest)
    }

    @Test fun `a member's ops change nothing and never enter the set`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F"), grant("F", 2, "A"))))
        val before = stateOf(r)
        val ops = listOf(grant("M", 3, "M"), onlyAdmins("M", 4), revoke("M", 5, "A"), op("M", 6, T0, "pin"))
        assertEquals(Roles.Change.NONE, r.accept(heard(ops)))
        assertEquals(before, stateOf(r))
        for (o in ops) assertFalse(o.id in ids(r.wire()))
        val m = roles("M"); sync(r, m)
        assertNull(m.grant(id("M"), T0)); assertNull(m.setOnlyAdmins(true, T0)); assertNull(m.revoke(id("A"), T0))
    }

    @Test fun `an op that arrives before the grant that makes its author an admin counts once the grant arrives`() {
        val r = roles("Z")
        r.accept(listOf(heard(found("F"))))
        val early = onlyAdmins("A", 3, T0 + 2 * MIN)
        assertEquals(Roles.Change.NONE, r.accept(listOf(heard(early))))
        assertFalse(r.onlyAdmins)
        assertTrue(r.knows(early.id))
        assertFalse(early.id in ids(r.wire()))
        assertEquals(Roles.Change.FOLD, r.accept(listOf(heard(grant("F", 2, "A", T0 + MIN)))))
        assertTrue(r.onlyAdmins)
        assertEquals(early.id, r.sendBy)
        assertTrue(early.id in ids(r.wire()))
    }

    @Test fun `an op made after its author was dismissed counts for nothing, one made before still counts`() {
        val before = onlyAdmins("A", 3, T0 + 2 * MIN); val after = everyone("A", 5, T0 + 4 * MIN)
        val r = roles("Z")
        r.accept(heard(listOf(found("F"), grant("F", 2, "A", T0 + MIN), before, revoke("F", 4, "A", T0 + 3 * MIN), after)))
        assertFalse(r.isAdmin(id("A")))
        assertTrue(r.onlyAdmins)
        assertEquals(before.id, r.sendBy)
        assertTrue(before.id in ids(r.effective))
        assertFalse(after.id in ids(r.effective))
        assertTrue("kept all the same", after.id in ids(r.wire()))
    }

    @Test fun `making an admin of an admin and dismissing a member change nothing`() {
        val g = grant("F", 2, "A"); val again = grant("F", 3, "A", T0 + MIN); val member = revoke("F", 4, "M", T0 + 2 * MIN)
        val r = roles("Z")
        r.accept(heard(listOf(found("F"), g, again, member)))
        assertEquals(listOf(g.id), ids(r.effective))
        assertEquals(listOf(id("F"), id("A")), r.admins)
        assertEquals("still made an admin by the first", g.id, r.adminBy(id("A")))
        val f = roles("F"); sync(r, f)
        assertNull(f.grant(id("A"), T0 + 3 * MIN))
        assertNull(f.revoke(id("M"), T0 + 3 * MIN))
        assertEquals(4, f.wire().size)
    }

    @Test fun `an admin can step down, the founder cannot`() {
        val f = roles("F"); f.found(true, T0); f.grant(id("A"), T0)
        val a = roles("A"); sync(f, a)
        val down = a.revoke(id("A"), T0 + MIN)!!
        assertFalse(a.isAdmin(id("A")))
        assertEquals(listOf(id("F")), a.admins)
        assertNull(f.revoke(id("F"), T0 + MIN))
        val byHand = revoke("F", 4, "F", T0 + 2 * MIN)
        f.accept(listOf(heard(down), heard(byHand)))
        assertEquals(listOf(id("F")), f.admins)
        assertTrue(down.id in ids(f.effective))
        assertFalse(byHand.id in ids(f.effective))
    }

    @Test fun `two ops with the same author and counter both count, in id order`() {
        val toB = grant("A", 3, "B", T0 + MIN); val toC = grant("A", 3, "C", T0 + MIN)
        val (first, second) = if (toB.id < toC.id) toB to toC else toC to toB
        val g = grant("F", 2, "A")
        for (order in listOf(listOf(first, second), listOf(second, first))) {
            val r = roles("Z")
            r.accept(heard(listOf(found("F"), g) + order))
            assertEquals(listOf(g.id, first.id, second.id), ids(r.effective))
            assertEquals(listOf(id("F"), id("A"), first.target, second.target), r.admins)
        }
    }

    @Test fun `a counter that jumps more than 1024 past what counts counts for nothing, unless it is the founder's`() {
        val base = listOf(found("F"), grant("F", 2, "A"))
        val tooFar = onlyAdmins("A", 2 + Roles.MAX_STEP + 1, T0 + MIN)
        val r = roles("Z")
        r.accept(heard(base + tooFar))
        assertFalse(r.onlyAdmins)
        assertTrue("kept", tooFar.id in ids(r.wire()))
        assertEquals(3, r.nextN())
        val justEnough = roles("Y")
        justEnough.accept(heard(base + onlyAdmins("A", 2 + Roles.MAX_STEP, T0 + MIN)))
        assertTrue(justEnough.onlyAdmins)
        // a valid op below it brings it within reach
        r.accept(listOf(heard(grant("A", 3, "B"))))
        assertTrue(r.onlyAdmins)
        assertEquals(tooFar.id, r.sendBy)
        // the founder may jump as far as it likes
        val f = roles("X")
        f.accept(heard(listOf(found("F"), onlyAdmins("F", 500_000, T0 + MIN))))
        assertTrue(f.onlyAdmins)
        assertEquals(500_001, f.nextN())
    }

    @Test fun `the counter of a new op is one more than the highest that counts, whatever members send`() {
        val f = roles("F"); f.found(true, T0)
        assertEquals(2, f.nextN())
        f.accept(heard(listOf(onlyAdmins("M", 999_999), grant("M", 500, "M"))))
        assertEquals(2, f.nextN())
        assertEquals(2, f.grant(id("A"), T0)!!.n)
        f.accept(listOf(heard(op("A", 9, T0, "pin"))))                      // valid, though this version doesn't know it
        f.accept(listOf(heard(onlyAdmins("A", 9 + 1100, T0))))              // jumps too far: not valid
        assertEquals(10, f.nextN())
        assertEquals(10, f.setOnlyAdmins(true, T0 + MIN)!!.n)
    }

    @Test fun `the admin list is the founder first, then in the order they last became admins`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F"), grant("F", 2, "B"), grant("F", 3, "A"), grant("F", 4, "C"), revoke("F", 5, "B"), grant("F", 6, "B"))))
        assertEquals(listOf(id("F"), id("A"), id("C"), id("B")), r.admins)
    }

    @Test fun `a new op of mine always counts and is always told, and one that would change nothing is refused`() {
        val f = roles("F"); f.found(true, T0)
        // B's grant of A is stamped a year ahead: it sorts below my next op, but after it in time
        val ahead = grant("B", 3, "A", T0 + YEAR)
        f.accept(heard(listOf(grant("F", 2, "B"), ahead)))
        val mine = f.revoke(id("A"), T0 + MIN)!!
        assertTrue(mine.id in ids(f.effective))
        assertTrue(mine.id in ids(f.told))
        assertFalse("the year-ahead grant is overridden", ahead.id in ids(f.told))
        assertFalse(f.isAdmin(id("A")))
        val set = f.setOnlyAdmins(true, T0 + 2 * MIN)!!
        assertTrue(set.id in ids(f.effective) && set.id in ids(f.told))
        assertNull(f.setOnlyAdmins(true, T0 + 3 * MIN))
        assertNull(f.grant(id("B"), T0 + 3 * MIN))
        assertNull(f.revoke(id("A"), T0 + 3 * MIN))
        assertNull(f.grant("not-a-node-id", T0 + 3 * MIN))
        assertEquals("nothing refused was kept", 5, f.wire().size)
    }

    // ---------------------------------------------------------------- the timeline and what the chat tells

    @Test fun `a member's message from before only admins could send is allowed, one from during is not, one from after is`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), onlyAdmins("F", 2, T0 + 10 * MIN), everyone("F", 3, T0 + 30 * MIN))))
        val m = id("M")
        assertTrue(r.maySend(m, T0 - DAY))
        assertTrue(r.maySend(m, T0 + 5 * MIN))
        assertFalse(r.maySend(m, T0 + 20 * MIN))
        assertTrue(r.maySend(m, T0 + 40 * MIN))
    }

    @Test fun `two minutes either side of a change go the sender's way`() {
        val start = T0 + 10 * MIN; val end = T0 + 30 * MIN
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), onlyAdmins("F", 2, start), everyone("F", 3, end))))
        val m = id("M")
        assertTrue(r.maySend(m, start - 1))
        assertTrue(r.maySend(m, start + SLACK - 1))
        assertFalse(r.maySend(m, start + SLACK))
        assertFalse(r.maySend(m, end - SLACK - 1))
        assertTrue(r.maySend(m, end - SLACK))
        assertTrue(r.maySend(m, end))
        // a spell with no end has only its start
        val open = roles("Y")
        open.accept(heard(listOf(found("F", T0), onlyAdmins("F", 2, start))))
        assertTrue(open.maySend(m, start + SLACK - 1))
        assertFalse(open.maySend(m, start + SLACK))
        assertFalse(open.maySend(m, start + 10 * YEAR))
        assertFalse(open.maySend(m, Long.MAX_VALUE))
    }

    @Test fun `a barred spell shorter than four minutes bars nothing`() {
        fun spell(len: Long): Roles {
            val r = roles("Z")
            r.accept(heard(listOf(found("F", T0), onlyAdmins("F", 2, T0 + 10 * MIN), everyone("F", 3, T0 + 10 * MIN + len))))
            return r
        }
        val m = id("M")
        for (len in listOf(MIN, 3 * MIN, 4 * MIN - 1, 4 * MIN)) {
            val r = spell(len)
            assertTrue(r.timeline.any { it.onlyAdmins })
            for (t in (T0 + 9 * MIN)..(T0 + 15 * MIN) step 1_000) assertTrue("$len at $t", r.maySend(m, t))
        }
        // a millisecond longer, and the one instant in its middle is barred
        val r = spell(4 * MIN + 1)
        assertFalse(r.maySend(m, T0 + 12 * MIN))
        assertTrue(r.maySend(m, T0 + 12 * MIN - 1))
        assertTrue(r.maySend(m, T0 + 12 * MIN + 1))
    }

    @Test fun `admins and the founder can always send`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), grant("F", 2, "A", T0 + MIN), onlyAdmins("F", 3, T0 + 10 * MIN), onlyAdmins("A", 4, T0 + 20 * MIN),
            grant("A", 5, "B", T0 + 30 * MIN))))
        assertTrue(r.onlyAdmins)
        for (x in listOf("F", "A")) {
            for (t in stamps) assertTrue("$x at $t", r.maySend(id(x), t))
            assertEquals(0L, r.allowedFrom(id(x)))
        }
        // and an admin from the moment they are made one
        assertFalse(r.maySend(id("B"), T0 + 25 * MIN))
        assertTrue(r.maySend(id("B"), T0 + 30 * MIN))
        assertTrue(r.maySend(id("B"), T0 + YEAR))
        assertFalse(r.maySend(id("M"), T0 + YEAR))
    }

    @Test fun `a change stamped a year ahead holds only from then, and a later change by a right clock applies now`() {
        val ahead = onlyAdmins("A", 3, T0 + YEAR)
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), grant("F", 2, "A", T0), ahead)))
        val n = id("N")
        assertTrue(r.onlyAdmins)
        assertTrue("it holds only from its stamp", r.maySend(n, T0 + HOUR))
        assertEquals(T0 + YEAR, r.barredSince(n, T0 + YEAR + HOUR))
        // F's clock is right: making M an admin applies now, not a year ahead
        r.accept(listOf(heard(grant("F", 4, "M", T0 + HOUR))))
        assertEquals(T0 + HOUR, r.timeline.first { id("M") in it.admins }.from)
        // and so does letting everyone send: the year-ahead setting never takes hold
        r.accept(listOf(heard(everyone("F", 5, T0 + 2 * HOUR))))
        assertFalse(r.onlyAdmins)
        assertTrue(r.maySend(n, T0 + YEAR + HOUR))
        assertTrue(r.timeline.none { it.onlyAdmins })
        assertFalse(ahead.id in ids(r.told))
    }

    @Test fun `a change by a slow clock applies from its own stamp`() {
        // F's clock is an hour slow: its "only admins" reaches back over the hour before it was made
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0 - DAY), onlyAdmins("F", 2, T0 - HOUR))))
        val m = id("M")
        assertEquals(T0 - HOUR, r.timeline.last().from)
        assertEquals(T0 - HOUR, r.barredSince(m, T0 - 30 * MIN))
        assertTrue(r.maySend(m, T0 - HOUR - 3 * MIN))
        assertFalse(r.maySend(m, T0))
    }

    @Test fun `allowedFrom is null while barred, 0 if never barred, and the start of the allowed spell otherwise`() {
        val r = roles("Z")
        val m = id("M"); val b = id("B")
        assertEquals("no founder", 0L, r.allowedFrom(m))
        r.accept(listOf(heard(found("F", T0))))
        assertEquals(0L, r.allowedFrom(m))
        r.accept(listOf(heard(onlyAdmins("F", 2, T0 + 10 * MIN))))
        assertNull(r.allowedFrom(m))
        assertNull(r.allowedFrom(b))
        assertEquals(0L, r.allowedFrom(id("F")))
        r.accept(listOf(heard(grant("F", 3, "B", T0 + 20 * MIN))))
        assertEquals(T0 + 20 * MIN, r.allowedFrom(b))
        r.accept(listOf(heard(everyone("F", 4, T0 + 30 * MIN))))
        assertEquals(T0 + 30 * MIN, r.allowedFrom(m))
        assertEquals(T0 + 20 * MIN, r.allowedFrom(b))
        assertEquals(0L, r.allowedFrom(id("F")))
    }

    @Test fun `a message stamped at allowedFrom plus one is allowed by anyone holding the same ops`() {
        var lifted = 0
        for (seed in 0 until 60) {
            val rnd = Random(seed.toLong())
            val some = fixture.filter { rnd.nextInt(4) != 0 }
            val here = roles("Y").apply { accept(heard(some)) }
            val there = roles("Z").apply { for (op in heard(some).shuffled(rnd)) accept(listOf(op)) }
            for (x in senders) {
                val from = here.allowedFrom(id(x)) ?: continue
                if (from > 0) lifted++
                assertTrue("seed $seed, $x from $from", there.maySend(id(x), from + 1))
            }
        }
        assertTrue("some were barred, then allowed", lifted > 0)
    }

    @Test fun `barredSince names the start of the spell a barred message falls in`() {
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), onlyAdmins("F", 2, T0 + 10 * MIN), grant("F", 3, "A", T0 + 12 * MIN), everyone("F", 4, T0 + 20 * MIN),
            onlyAdmins("F", 5, T0 + 40 * MIN))))
        val m = id("M")
        assertEquals("the spell goes on across A's grant", T0 + 10 * MIN, r.barredSince(m, T0 + 15 * MIN))
        assertEquals(T0 + 10 * MIN, r.barredSince(m, T0 + 12 * MIN + 30_000))
        assertNull(r.barredSince(m, T0 + 30 * MIN))
        assertEquals(T0 + 40 * MIN, r.barredSince(m, T0 + 50 * MIN))
        assertEquals(T0 + 40 * MIN, r.barredSince(m, T0 + YEAR))
        assertNull("A's own spell was two minutes", r.barredSince(id("A"), T0 + 11 * MIN))
        assertNull(r.barredSince(id("A"), T0 + 50 * MIN))
    }

    @Test fun `the timeline's last moment is always the fold's state`() {
        for (seed in 0 until 100) {
            val rnd = Random(seed.toLong())
            val r = roles("Z")
            for (op in heard(fixture.filter { rnd.nextInt(3) != 0 }).shuffled(rnd)) r.accept(listOf(op))
            val last = r.timeline.last()
            assertEquals("seed $seed", r.admins.toSet(), last.admins)
            assertEquals("seed $seed", r.onlyAdmins, last.onlyAdmins)
        }
    }

    @Test fun `the newest told change about each subject is always the group's state`() {
        // B's id sorts below A's, picked at run time: B's stale change folds before A's newer ones
        val (b, a) = listOf("P", "Q").sortedBy { id(it) }
        val stale = everyone(b, 5, T0 + 10 * MIN)                // B's phone hadn't seen A's last two changes
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), grant("F", 2, a, T0), grant("F", 3, b, T0), onlyAdmins(a, 4, T0 + MIN), everyone(a, 5, T0 + 2 * MIN),
            onlyAdmins(a, 6, T0 + 3 * MIN), stale)))
        assertTrue(r.onlyAdmins)
        assertTrue("B's change counted", stale.id in ids(r.effective))
        assertFalse("but A's later one, stamped before it, overrides it", stale.id in ids(r.told))
        assertEquals(RoleOp.ADMINS, r.told.last { it.type == RoleOp.SET }.value)
        assertToldIsState(r, "C4")
        // and so for any part of the fixture
        for (seed in 0 until 100) {
            val rnd = Random(seed.toLong())
            assertToldIsState(roles("Z").apply { accept(heard(fixture.filter { rnd.nextInt(3) != 0 })) }, "seed $seed")
        }
    }

    @Test fun `a change that a later one stamped before it overrides is not told`() {
        val g = grant("F", 2, "A", T0 + 10 * MIN); val rv = revoke("F", 3, "A", T0 + 5 * MIN)
        val r = roles("Z")
        r.accept(heard(listOf(found("F", T0), g, rv)))
        assertEquals(listOf(g.id, rv.id), ids(r.effective))
        assertEquals(listOf(rv.id), ids(r.told))
        assertTrue("A was an admin at no instant", r.timeline.none { id("A") in it.admins })
        // stamped the other way round, both are told
        val g2 = grant("F", 2, "A", T0 + 5 * MIN); val rv2 = revoke("F", 3, "A", T0 + 10 * MIN)
        val r2 = roles("Z")
        r2.accept(heard(listOf(found("F", T0), g2, rv2)))
        assertEquals(listOf(g2.id, rv2.id), ids(r2.told))
    }

    // ---------------------------------------------------------------- the set, the cap and the digest

    @Test fun `every delivery order of the same ops gives the same state`() {
        val want = stateOf(roles("Z").apply { accept(heard(fixture)) })
        assertTrue(want.effective.size > 5)
        for (seed in 0 until 300) {
            val r = roles("Z")
            for (op in heard(fixture).shuffled(Random(seed.toLong()))) r.accept(listOf(op))
            assertEquals("seed $seed", want, stateOf(r))
            assertEquals("seed $seed", id("F"), r.founder)
        }
    }

    @Test fun `all 720 orders of six ops settle the same`() {
        val six = listOf(found("F", T0), grant("F", 2, "A", T0 + MIN), grant("A", 3, "B", T0 + 2 * MIN), onlyAdmins("B", 4, T0 + 3 * MIN),
            revoke("F", 3, "A", T0 + 4 * MIN), everyone("A", 5, T0 + 5 * MIN))
        val want = stateOf(roles("Z").apply { accept(heard(six)) })
        val orders = FakeNet.permutations(six.indices.toList())
        assertEquals(720, orders.size)
        for (order in orders) {
            val r = roles("Z")
            for (i in order) r.accept(listOf(heard(six[i])))
            assertEquals("order $order", want, stateOf(r))
        }
    }

    @Test fun `any split of the same ops into batches settles the same`() {
        val want = stateOf(roles("Z").apply { accept(heard(fixture)) })
        for (seed in 0 until 100) {
            val rnd = Random(seed.toLong())
            val ops = heard(fixture).shuffled(rnd)
            val r = roles("Z")
            var i = 0
            while (i < ops.size) { val k = 1 + rnd.nextInt(8); r.accept(ops.subList(i, minOf(ops.size, i + k))); i += k }
            assertEquals("seed $seed", want, stateOf(r))
        }
    }

    @Test fun `a member's junk never enters the set and never pushes out a real op`() {
        val real = listOf(found("F", T0), grant("F", 2, "A", T0 + MIN), onlyAdmins("A", 3, T0 + 2 * MIN), grant("A", 4, "B", T0 + 3 * MIN),
            everyone("B", 5, T0 + 4 * MIN))
        val junk = listOf("M", "N", "O").flatMap { who -> (0 until 200).map { i -> onlyAdmins(who, 1, T0 + i) } }
        assertEquals(600, junk.size)
        val clean = roles("Y"); clean.accept(heard(real))
        val r = roles("Z")
        r.accept(heard(junk.take(300)))
        r.accept(heard(real))
        r.accept(heard(junk.drop(300)))
        assertEquals(ids(clean.wire()), ids(r.wire()))
        assertEquals(stateOf(clean), stateOf(r))
        assertEquals("no junk was ever checked", clean.signaturesChecked, r.signaturesChecked)
        assertTrue("what waits is bounded", junk.count { r.knows(it.id) } <= Roles.MAX_LIMBO)
        assertFalse(r.full(id("A")))
    }

    @Test fun `over the cap the founder's ops are kept first, then the earliest, the same on every phone`() {
        val base = listOf(found("F", T0), grant("F", 2, "A", T0))
        val flood = (3 until 3 + 520).map { n -> setting("A", n, n % 2 == 1, T0 + n) }
        val late = (0 until 5).map { i -> setting("F", 600 + i, i % 2 == 0, T0 + DAY + i) }   // the founder's, above every one of A's
        val founderOps = 1 + late.size
        val one = roles("Y")
        one.accept(base + flood + late)
        assertEquals(Roles.MAX_OPS, one.wire().size - 1)
        val kept = (listOf(base[1]) + flood.take(Roles.MAX_OPS - founderOps) + late).sortedWith(RoleOp.FOLD_ORDER)
        assertEquals(ids(listOf(base[0]) + kept), ids(one.wire()))
        // another phone: the founder's first, then A's from the newest down
        val two = roles("Z")
        two.accept(base)
        two.accept(late)
        for (op in flood.reversed()) two.accept(listOf(op))
        assertEquals(ids(one.wire()), ids(two.wire()))
        assertEquals(stateOf(one), stateOf(two))
    }

    @Test fun `merging a capped set with another gives what merging everything at once gives`() {
        val base = listOf(found("F", T0), grant("F", 2, "A", T0), grant("F", 3, "B", T0))
        val fromA = (4 until 4 + 560).map { n -> setting("A", n, n % 2 == 0, T0 + n) }
        val fromB = (4 until 4 + 560).map { n -> setting("B", n, n % 3 == 0, T0 + n) }
        val byF = (0 until 3).map { i -> setting("F", 1000 + i, i % 2 == 0, T0 + DAY + i) }
        val p = roles("P"); p.accept(base + fromA + byF.take(2))
        val q = roles("Q"); q.accept(base + fromB + byF.drop(2))
        assertEquals(Roles.MAX_OPS, p.wire().size - 1)
        assertEquals(Roles.MAX_OPS, q.wire().size - 1)
        val all = roles("R"); all.accept(base + fromA + fromB + byF)
        val pWire = heard(p.wire()); val qWire = heard(q.wire())
        p.accept(qWire)
        q.accept(pWire)
        for (r in listOf(p, q)) {
            assertEquals(ids(all.wire()), ids(r.wire()))
            assertEquals(stateOf(all), stateOf(r))
        }
    }

    @Test fun `a rogue admin who fills the set can't lock the founder out, and the founder's dismissal holds`() {
        val f = roles("F")
        val pin = f.found(true, T0)!!
        val toR = f.grant(id("R"), T0)!!
        val flood = (1..511).map { k -> setting("R", 2 + k * Roles.MAX_STEP, k % 2 == 1, T0 + k) }   // each exactly MAX_STEP above the last
        f.accept(heard(flood))
        assertEquals("every one valid", ids(listOf(toR) + flood), ids(f.effective))
        assertEquals(Roles.MAX_OPS, f.wire().size - 1)
        assertTrue(f.full(id("R")))
        assertTrue(f.full(id("A")))
        assertFalse("but not for the founder", f.full(id("F")))
        val out = f.revoke(id("R"), T0 + MIN)
        assertNotNull(out)
        assertTrue(out!!.id in ids(f.effective))
        assertFalse(f.isAdmin(id("R")))
        assertEquals(Roles.MAX_OPS, f.wire().size - 1)
        assertFalse("R's newest made room", flood.last().id in ids(f.wire()))
        val settled = ids(f.wire())
        // a phone that heard the dismissal before the flood ends the same
        val other = roles("Z")
        other.accept(heard(listOf(pin, toR, out)))
        other.accept(heard(flood).shuffled(Random(5)))
        assertFalse(other.isAdmin(id("R")))
        assertTrue(out.id in ids(other.effective))
        assertEquals(settled, ids(other.wire()))
        // R's later ops count for nothing, and the founder can still act
        f.accept(listOf(heard(setting("R", out.n + 1, true, T0 + 2 * MIN))))
        assertFalse(f.isAdmin(id("R")))
        assertNotNull(f.grant(id("A"), T0 + 3 * MIN))
        assertTrue(f.isAdmin(id("A")))
    }

    @Test fun `once the set is full only the founder can make changes`() {
        val f = roles("F"); f.found(true, T0); f.grant(id("A"), T0)
        val a = roles("A"); sync(f, a)
        var on = true; var t = T0
        while (!a.full(a.me)) { assertNotNull(a.setOnlyAdmins(on, ++t)); on = !on }
        assertEquals(Roles.MAX_OPS, a.wire().size - 1)
        assertNull(a.setOnlyAdmins(on, ++t))
        assertNull(a.grant(id("B"), t))
        sync(a, f)
        assertTrue(f.full(id("A")))
        assertFalse(f.full(id("F")))
        assertNotNull(f.grant(id("B"), ++t))
        // B, made an admin since, is refused too
        val b = roles("B"); sync(f, b)
        assertTrue(b.isAdmin(b.me))
        assertNull(b.setOnlyAdmins(!b.onlyAdmins, ++t))
    }

    @Test fun `at the cap a founder's change pushes out the newest change of another admin, the same on every phone`() {
        val f = roles("F")
        val pin = f.found(true, T0)!!
        val toA = f.grant(id("A"), T0)!!
        val byA = (3..513).map { n -> setting("A", n, n % 2 == 1, T0 + n) }       // the newest, n = 513, lets only admins send
        f.accept(byA)
        assertEquals(Roles.MAX_OPS, f.wire().size - 1)
        assertTrue(f.onlyAdmins)
        val change = f.grant(id("B"), T0 + DAY)!!
        assertEquals(Roles.MAX_OPS, f.wire().size - 1)
        assertFalse(byA.last().id in ids(f.wire()))
        assertTrue(byA.dropLast(1).all { it.id in ids(f.wire()) })
        assertFalse("its effect went with it", f.onlyAdmins)
        assertEquals(byA[byA.size - 2].id, f.sendBy)
        val first = roles("Y"); first.accept(heard(listOf(pin, toA, change))); first.accept(heard(byA))
        val once = roles("Z"); once.accept(heard(listOf(pin, toA, change) + byA))
        for (r in listOf(first, once)) {
            assertEquals(ids(f.wire()), ids(r.wire()))
            assertEquals(stateOf(f), stateOf(r))
        }
        // the next change pushes out the next newest
        assertNotNull(f.grant(id("C"), T0 + DAY + MIN))
        assertFalse(byA[byA.size - 2].id in ids(f.wire()))
        assertTrue(f.onlyAdmins)
        assertEquals(Roles.MAX_OPS, f.wire().size - 1)
    }

    @Test fun `at the cap a founder's change is handed back even when the change it pushes out was all that stood in its way`() {
        // A's newest change, n = 513, is the opposite of what the founder now asks; pushing it out brings about what was asked
        fun undo(why: String, byA: List<RoleOp>, change: (Roles) -> RoleOp?, asked: (Roles) -> Boolean) {
            val f = roles("F"); f.found(true, T0); f.grant(id("A"), T0)
            f.accept(byA)
            assertEquals(why, Roles.MAX_OPS, f.wire().size - 1)
            assertFalse(why, asked(f))
            val before = f.digest
            val op = change(f)
            assertNotNull("$why: handed back, to be published and saved", op)
            assertTrue(why, op!!.id in ids(f.wire()))
            assertNotEquals(why, before, f.digest)
            assertFalse("$why: A's newest made room", byA.last().id in ids(f.wire()))
            assertTrue("$why: what was asked holds", asked(f))
            assertFalse("$why: it changed nothing itself, A's newest going did", op.id in ids(f.effective))
        }
        val byA = (3..512).map { n -> setting("A", n, n % 2 == 1, T0 + n) }      // the last, n = 512, lets everyone send
        undo("only admins undone", byA + onlyAdmins("A", 513, T0 + 513), { it.setOnlyAdmins(false, T0 + DAY) }, { !it.onlyAdmins })
        undo("a grant undone", byA + grant("A", 513, "B", T0 + 513), { it.revoke(id("B"), T0 + DAY) }, { !it.isAdmin(id("B")) })
        undo("a dismissal undone", listOf(grant("A", 3, "B", T0 + 3)) + byA.drop(1) + revoke("A", 513, "B", T0 + 513),
            { it.grant(id("B"), T0 + DAY) }, { it.isAdmin(id("B")) })
    }

    @Test fun `a duplicate op changes nothing, whichever copy came first`() {
        val g = grant("F", 2, "A"); val again = grant("F", 2, "A")
        for ((first, second) in listOf(g to again, again to g)) {
            val r = roles("Z")
            r.accept(listOf(heard(found("F"))))
            assertEquals(Roles.Change.FOLD, r.accept(listOf(heard(first))))
            val before = stateOf(r)
            assertEquals(Roles.Change.NONE, r.accept(listOf(heard(second))))
            assertEquals(Roles.Change.NONE, r.accept(listOf(heard(first), heard(second))))
            assertEquals(before, stateOf(r))
            assertEquals(2, r.wire().size)
            assertEquals("the first copy is kept", first.sig, r.wire()[1].sig)
        }
    }

    @Test fun `the digest is the same for the same set and differs for any other, and limbo never moves it`() {
        val one = roles("Y"); one.accept(heard(fixture))
        val two = roles("Z"); for (op in heard(fixture).reversed()) two.accept(listOf(op))
        assertEquals(one.digest, two.digest)
        assertEquals(22, one.digest.length)
        val less = roles("X"); less.accept(heard(fixture.filter { it.id != one.wire().last().id }))
        assertNotEquals(one.digest, less.digest)
        val more = roles("X"); more.accept(heard(fixture + grant("F", 3000, "M", T0 + HOUR)))
        assertNotEquals(one.digest, more.digest)
        fun foundedAt(ts: Long): String { val r = roles("X"); r.accept(listOf(heard(found("F", ts)))); return r.digest }
        assertNotEquals("another founder", foundedAt(T0), foundedAt(T0 + 1))
        // ops still waiting don't move it
        val waiting = heard(listOf(grant("W", 30, "M"), everyone("W", 31)))
        assertEquals(Roles.Change.NONE, one.accept(waiting))
        assertEquals(two.digest, one.digest)
        assertTrue(waiting.all { one.knows(it.id) })
    }

    @Test fun `a phone with no roles still has a 22-character digest, the same on every phone, and keeps ops waiting for a founder`() {
        val a = roles("A"); val b = roles("B")
        assertEquals(22, a.digest.length)
        assertEquals(a.digest, b.digest)
        assertEquals(a.digest, Roles(OTHER_TAG, keys("C")).digest)
        assertTrue(a.isEmpty)
        val waiting = heard(listOf(grant("F", 2, "A"), onlyAdmins("A", 3)))
        assertEquals(Roles.Change.NONE, a.accept(waiting))
        assertEquals(b.digest, a.digest)
        assertTrue(a.isEmpty)
        assertTrue(waiting.all { a.knows(it.id) })
        a.accept(listOf(heard(found("F"))))
        assertFalse(a.isEmpty)
        assertTrue(a.isAdmin(id("A")))
        assertTrue(a.onlyAdmins)
        a.clear()
        assertEquals(b.digest, a.digest)
        assertFalse(a.knows(waiting[0].id))
    }

    @Test fun `the carrier takes the whole set while small, else the chains of the change, the setting and every admin`() {
        val pin = found("F", T0)
        val toA = grant("F", 2, "A"); val toB = grant("A", 3, "B"); val only = onlyAdmins("B", 4); val toC = grant("F", 5, "C")
        val filler = (0 until 40).flatMap { i -> listOf(grant("F", 6 + 2 * i, "G$i"), revoke("F", 7 + 2 * i, "G$i")) }
        val change = grant("C", 86, "D"); val toH = grant("F", 87, "H")
        val r = roles("Z")
        r.accept(heard(listOf(pin, toA, toB, only, toC) + filler + listOf(change, toH)))
        assertEquals(listOf(id("F"), id("A"), id("B"), id("C"), id("D"), id("H")), r.admins)
        fun size(l: List<RoleOp>) = l.sumOf { it.toJson().toString().length + 1 }
        val whole = size(r.wire())
        assertEquals(ids(r.wire()), ids(r.carrierOps(change, whole)))
        val some = r.carrierOps(change, whole - 1)
        assertEquals(ids(listOf(pin, toA, toB, only, toC, change, toH)), ids(some))
        assertEquals("too small even for that", ids(listOf(pin, change)), ids(r.carrierOps(change, size(some) - 1)))
        assertEquals("no founder: the op alone", listOf(change.id), ids(roles("Y").carrierOps(change, 1)))
    }

    @Test fun `the state saved and read back is the same state with the same digest, and nothing is checked again`() {
        val r = roles("Y"); r.accept(heard(fixture))
        val saved = r.toJson().toString()
        assertEquals(setOf("pin", "ops"), JSONObject(saved).keySet())
        val back = roles("Y"); back.restore(JSONObject(saved))
        assertEquals(stateOf(r), stateOf(back))
        assertEquals(ids(r.wire()), ids(back.wire()))
        assertEquals(0, back.signaturesChecked)
        // only what there is, is written
        assertEquals(0, roles("Y").toJson().length())
        assertEquals(setOf("pin"), roles("F").apply { found(true, T0) }.toJson().keySet())
    }

    @Test fun `a saved state with bad entries reads back the rest, and junk reads back as no roles`() {
        val r = roles("Y")
        val ops = listOf(grant("F", 2, "A"), onlyAdmins("A", 3))
        r.accept(heard(listOf(found("F")) + ops))
        val bad = JSONArray().put("not an op").put(ops[0].toJson()).put(5).put(JSONObject().put("a", "b")).put(found("G").toJson())
            .put(ops[1].toJson()).put(grant("F", 4, "B").toJson().put("n", 0)).put(JSONArray()).put(JSONObject.NULL).put(ops[0].toJson())
        val back = roles("Y"); back.restore(JSONObject(r.toJson().toString()).put("ops", bad))
        assertEquals(stateOf(r), stateOf(back))
        assertEquals("an op written twice is read once", ids(r.wire()), ids(back.wire()))
        val none = roles("Y").digest
        val junk = listOf(JSONObject(), JSONObject().put("pin", "x").put("ops", 5), JSONObject().put("pin", JSONArray()).put("ops", JSONObject()),
            JSONObject().put("pin", grant("F", 2, "A").toJson()), JSONObject().put("ops", JSONArray().put(JSONArray()).put("y")))
        for (j in junk) {
            val z = roles("Y"); z.restore(j)
            assertTrue("$j", z.isEmpty); assertNull(z.founder); assertEquals(none, z.digest)
        }
        // with its founder unreadable, the ops wait for one
        val w = roles("Y"); w.restore(JSONObject(r.toJson().toString()).put("pin", "broken"))
        assertTrue(w.isEmpty)
        assertTrue(ops.all { w.knows(it.id) })
        w.accept(listOf(heard(found("F"))))
        assertEquals(stateOf(r), stateOf(w))
    }
}
