package app.hopline.mesh

import app.hopline.core.Crypto
import app.hopline.core.IdentityKeys
import org.json.JSONArray
import org.json.JSONObject
import java.util.Collections
import java.util.SortedMap
import java.util.TreeMap

/**
 * One signed change to who runs a group: its founding, someone made an admin or dismissed, or who
 * may send. It checks itself, whatever brought it: [author] signs it with the key in [pk] (their
 * id is made from that key), bound to the group's air tag, which is never sent. Its [id] is a hash
 * of what it says rather than of the signature (ECDSA signs differently each time), so the same
 * change signed twice is the same op.
 *
 * On the wire it is one flat JSON object of whole numbers and plain ASCII text, so it reads back
 * the same on every platform. A type, field or value this version doesn't know is kept and passed
 * on: it is simply never effective here, and a later version can still use it.
 */
class RoleOp private constructor(val fields: SortedMap<String, Any>, val sig: String, val id: String) {
    // parse and make guarantee these five
    val author: String get() = fields["a"] as String
    val pk: String get() = fields["pk"] as String
    val n: Int get() = (fields["n"] as Long).toInt()
    val ts: Long get() = fields["ts"] as Long
    val type: String get() = fields["t"] as String
    /** The node id a grant or a revoke is about. Null when it is missing or not text. */
    val target: String? get() = fields["x"] as? String
    /** What a set is about ([SEND]), and what it sets it to ([ALL] or [ADMINS]). Null when missing or not text. */
    val key: String? get() = fields["k"] as? String
    val value: String? get() = fields["w"] as? String
    /** A found by a phone that started the group on a version with admins; false for a claim made from old evidence. */
    val sure: Boolean get() = (fields["sure"] as? Long) == 1L

    /** This phone verified it, made it, or read it back from its own saved state. */
    internal var checked = false

    /** True only when [pk] is a key, [author]'s id is made from it, and [sig] is its signature over these exact fields in the group of [airTag]. */
    fun verify(airTag: String): Boolean {
        val raw = Crypto.unb64(pk) ?: return false
        val pub = Crypto.decodePub(pk) ?: return false
        return Crypto.nodeIdOf(raw) == author && Crypto.verify(pub, signedBytes(airTag, fields), sig)
    }

    fun toJson(): JSONObject = JSONObject().apply { for ((k, v) in fields) put(k, v); put("s", sig) }

    companion object {
        const val DOMAIN = "hopline/v5/role"
        const val FOUND = "found"; const val GRANT = "grant"; const val REVOKE = "revoke"; const val SET = "set"
        const val SEND = "send"; const val ALL = "all"; const val ADMINS = "admins"
        const val MAX_N = 1_000_000; const val MAX_TS = 9_999_999_999_999L

        private val KEY = Regex("[a-z]{1,8}")
        private val SIG = Regex("[A-Za-z0-9_-]{8,100}")
        private val TEXT = Regex("[A-Za-z0-9_-]{1,100}")
        private val TYPE = Regex("[a-z]{1,12}")
        private const val PK_LEN = 87

        /** The order every phone folds in: counter, then author, then id. Total, because ids are unique. */
        val FOLD_ORDER: Comparator<RoleOp> = compareBy<RoleOp>({ it.n }, { it.author }, { it.id })
        /** Which found is the better founder: a sure one before any claim, then the earliest stamp. */
        val PIN_ORDER: Comparator<RoleOp> = compareBy<RoleOp>({ !it.sure }, { it.ts }, { it.author }, { it.id })

        /**
         * An op as it came from another phone, or null unless it is well formed: 6 to 12 short
         * lowercase keys, every value a whole number or plain text, and the five fields every op has
         * in range. Nothing that fails this is kept, checked or counted. Its signature is not checked
         * here: that waits until the op could matter ([Roles.accept]).
         */
        fun parse(j: JSONObject): RoleOp? {
            if (j.length() !in 6..12) return null
            val sig = j.opt("s") as? String ?: return null
            if (!SIG.matches(sig)) return null
            val fields = TreeMap<String, Any>()
            for (k in j.keys()) {
                if (!KEY.matches(k)) return null
                if (k == "s") continue
                fields[k] = when (val v = j.opt(k)) {
                    is Int -> v.toLong()
                    is Long -> v
                    is String -> v.takeIf { TEXT.matches(it) } ?: return null
                    else -> return null    // a fraction, a boolean, null, or something nested
                }
            }
            val a = fields["a"] as? String
            val n = fields["n"] as? Long
            val ts = fields["ts"] as? Long
            val t = fields["t"] as? String
            if (a == null || !Crypto.isNodeId(a) || (fields["pk"] as? String)?.length != PK_LEN) return null
            if (n == null || n !in 1L..MAX_N || ts == null || ts !in 0L..MAX_TS || t == null || !TYPE.matches(t)) return null
            val sure = fields["sure"]
            if (t == FOUND && (n != 1L || (sure != 0L && sure != 1L))) return null
            return of(fields, sig)
        }

        /** A new op of [keys]' phone, signed for the group of [airTag]. [extra] holds its own fields, each a Long or a String. */
        fun make(keys: IdentityKeys, airTag: String, n: Int, ts: Long, type: String, extra: Map<String, Any>): RoleOp {
            val fields = TreeMap<String, Any>()
            for ((k, v) in extra) { require(v is Long || v is String) { "a role op's fields are whole numbers or text" }; fields[k] = v }
            fields["a"] = keys.nodeId; fields["pk"] = keys.pubB64; fields["n"] = n.toLong(); fields["ts"] = ts; fields["t"] = type
            return of(fields, Crypto.sign(keys.priv, signedBytes(airTag, fields))).also { it.checked = true }
        }

        /**
         * What an op's signature covers: the domain, the group's [airTag] (never sent, so an op of
         * another group checks against nothing here), then every field but the signature in key
         * order, each value marked as a number or text.
         */
        fun signedBytes(airTag: String, fields: SortedMap<String, Any>): ByteArray {
            val parts = ArrayList<String>(2 + 2 * fields.size)
            parts.add(DOMAIN)
            parts.add(airTag)
            for ((k, v) in fields) { parts.add(k); parts.add(if (v is Long) "i$v" else "s$v") }
            return Crypto.lp(*parts.toTypedArray())
        }

        /**
         * The id is the signed form with an empty air tag, so a left group's kept chat, read without
         * the group's key, still knows its ops by the same ids. The signature still binds each op to
         * its group.
         */
        private fun of(fields: TreeMap<String, Any>, sig: String): RoleOp =
            RoleOp(Collections.unmodifiableSortedMap(fields), sig, Crypto.b64(Crypto.sha256(signedBytes("", fields)).copyOf(16)))
    }
}

/**
 * Who runs one group, and who could post in it when. The group's [RoleOp]s are merged by union and
 * folded in one fixed order ([RoleOp.FOLD_ORDER]), so every phone holding the same ops comes to the
 * same admins and the same send setting, whatever order they arrived in.
 *
 * The founder is the root of trust: the first sure found this phone takes sticks for good, and a
 * sure found replaces a mere claim. An op is kept only once its author is reachable from the founder
 * through earlier grants; until then it waits, unchecked and in memory only. A signature is checked
 * only when its op could matter, so nothing a member sends can make every phone do the work.
 *
 * Each change takes effect at its own stamp: [timeline] says who could send at any instant, and a
 * post is judged by the instant it was stamped ([maySend]). Pure and single-threaded, like the
 * Router that owns it. Every stamp is passed in: it never reads a clock.
 */
class Roles(private val airTag: String, private val keys: IdentityKeys) {
    /** What a merge did. [FOLD]: the admin list, the setting or the effective ops changed. [SET]: only the set did. */
    enum class Change { NONE, SET, FOLD }

    /** From [from] on (until the next moment), [admins] were the group's admins and [onlyAdmins] its setting. */
    class Moment(val from: Long, val admins: Set<String>, val onlyAdmins: Boolean)

    private class Poison { var on = false }

    /** Everything this phone knows, in one value that a merge replaces whole: a merge that fails half way changes nothing. */
    private class State(
        val pin: RoleOp?,                            // the founder's found: the only found op kept
        val stored: List<RoleOp>,                    // rooted ops, fold order: synced, saved, digested, capped
        val limbo: List<RoleOp>,                     // ops not rooted yet, fold order, at most MAX_LIMBO: memory only, may be unchecked
        val adminBy: LinkedHashMap<String, String>,  // admin -> the op that made them one (the pin for the founder), in admin list order
        val onlyAdmins: Boolean,
        val sendBy: String?,                         // the effective set behind onlyAdmins
        val effective: List<RoleOp>,                 // fold order
        val told: List<RoleOp>,                      // by stamp, then fold order
        val supportOf: Map<String, String>,          // valid op -> the op that made its author an admin there
        val maxValidN: Int,
        val timeline: List<Moment>,
        val digest: String,
        val founderOps: Int,                         // stored ops by the founder
        val storedIds: Set<String>, val limboIds: Set<String>,
    )

    val me: String = keys.nodeId

    private var s: State = derive(null, emptyList(), emptyList())

    /** How many signatures this phone has checked: for tests only. */
    internal var signaturesChecked = 0

    val pin: RoleOp? get() = s.pin
    val founder: String? get() = s.pin?.author
    val founderSure: Boolean get() = s.pin?.sure == true
    /** The founder first, then in the order each one last became an admin. */
    val admins: List<String> get() = s.adminBy.keys.toList()
    val onlyAdmins: Boolean get() = s.onlyAdmins
    val sendBy: String? get() = s.sendBy
    /** The ops that changed something, in fold order. */
    val effective: List<RoleOp> get() = s.effective
    /** The effective ops that hold from their own stamp, by stamp: the ones the chat may tell of. The newest about each subject is its state now. */
    val told: List<RoleOp> get() = s.told
    val timeline: List<Moment> get() = s.timeline
    /** Names the set (the founder and every op kept), 22 characters, never empty. Ops still waiting don't move it. */
    val digest: String get() = s.digest
    val isEmpty: Boolean get() = s.pin == null && s.stored.isEmpty()

    /** A set lookup: cheap enough for a sort's comparator. */
    fun isAdmin(id: String): Boolean = id in s.adminBy
    fun foundedBy(id: String): Boolean = s.pin?.author == id
    /** The op that made [id] an admin (the founder's found for the founder), or null if they aren't one. */
    fun adminBy(id: String): String? = s.adminBy[id]
    /** This op is the founder, kept, or waiting here. */
    fun knows(id: String): Boolean = id == s.pin?.id || id in s.storedIds || id in s.limboIds
    /** The whole set as it goes to another phone: the founder first, then fold order, so what makes an author an admin comes before what they did. */
    fun wire(): List<RoleOp> = listOfNotNull(s.pin) + s.stored

    // ---------------------------------------------------------------- merging

    /**
     * Take in [incoming] (from another phone, or made here): the set becomes the union. A found is
     * checked only when it would become the founder; any other op only once it is rooted. In one
     * batch, the first op whose signature fails stops all further checks, and nothing of that batch
     * left unchecked is kept: an honest phone never sends one.
     */
    fun accept(incoming: List<RoleOp>): Change {
        if (incoming.isEmpty()) return Change.NONE
        val bad = Poison()
        var pin = s.pin
        for (f in incoming.filter { it.type == RoleOp.FOUND }.sortedWith(RoleOp.PIN_ORDER)) {
            if (bad.on) break
            if (offerPin(pin, f) !== f) continue    // can't take the founder's place: never checked
            if (!f.checked) { signaturesChecked++; if (f.verify(airTag)) f.checked = true else { bad.on = true; break } }
            pin = f
        }
        val cand = LinkedHashMap<String, RoleOp>()
        for (op in s.stored) cand[op.id] = op
        for (op in s.limbo) cand.putIfAbsent(op.id, op)
        // A known id keeps its first copy. Only the copies this batch brings are fresh: one that waited here isn't the batch's to spoil.
        val fresh = HashSet<String>()
        for (op in incoming) if (op.type != RoleOp.FOUND && cand.putIfAbsent(op.id, op) == null) fresh.add(op.id)
        val before = s
        val next = settle(pin, cand.values, fresh, bad)
        s = next
        return when {
            before.adminBy.keys.toList() != next.adminBy.keys.toList() || before.onlyAdmins != next.onlyAdmins ||
                before.effective.map { it.id } != next.effective.map { it.id } -> Change.FOLD
            before.digest != next.digest -> Change.SET
            else -> Change.NONE
        }
    }

    /** Which of the founder so far ([pin]) and the found [f] is the founder now. */
    private fun offerPin(pin: RoleOp?, f: RoleOp): RoleOp? = when {
        pin == null -> f
        pin.id == f.id -> pin
        pin.sure && f.sure && f.author == pin.author -> if (f.id < pin.id) f else pin   // one founder: settle on one op
        pin.sure -> pin                                                                  // the first sure founder sticks
        f.sure -> f                                                                      // a sure founder replaces a claim
        else -> if (compareValuesBy(f, pin, { it.ts }, { it.author }, { it.id }) < 0) f else pin
    }

    /**
     * Sort [cands] into what is rooted under [pin] and what still waits, checking signatures only
     * as ops become rooted, then trim to the cap: the founder's ops first, then the lowest in fold
     * order. Trimming keeps every kept op rooted: the founder's need nothing, and anyone else's
     * support always sorts below it. An op past the cap is never checked: it would be trimmed
     * whatever else checks out, so the same ops sent again and again cost nothing.
     */
    private fun settle(pin: RoleOp?, cands: Collection<RoleOp>, fresh: Set<String>, bad: Poison): State {
        fun dropped(op: RoleOp) = bad.on && op.id in fresh && !op.checked
        if (pin == null) return derive(null, emptyList(), cands.filterNot(::dropped).sortedWith(RoleOp.FOLD_ORDER).take(MAX_LIMBO))
        val reach = hashSetOf(pin.author)
        val rooted = ArrayList<RoleOp>(); val unrooted = ArrayList<RoleOp>()
        // Room for anyone else's ops is at most this: the founder's checked ops are all kept
        val room = MAX_OPS - minOf(MAX_OPS, cands.count { it.author == pin.author && it.checked })
        var others = 0
        for (op in cands.sortedWith(RoleOp.FOLD_ORDER)) {
            if (op.author !in reach) { unrooted.add(op); continue }
            if (op.author != pin.author && others >= room) continue   // past the cap: never kept, so never checked
            if (!op.checked) {
                if (dropped(op)) continue
                signaturesChecked++
                if (op.verify(airTag)) op.checked = true
                else { if (op.id in fresh) bad.on = true; continue }   // forged: never kept, and an honest batch has none
            }
            rooted.add(op); if (op.author != pin.author) others++
            if (op.type == RoleOp.GRANT) op.target?.takeIf { Crypto.isNodeId(it) }?.let { reach.add(it) }
        }
        val mine = rooted.filter { it.author == pin.author }.take(MAX_OPS)
        val keep = (mine + rooted.filter { it.author != pin.author }.take(MAX_OPS - mine.size)).sortedWith(RoleOp.FOLD_ORDER)
        return derive(pin, keep, unrooted.filterNot(::dropped).take(MAX_LIMBO))
    }

    // ---------------------------------------------------------------- the fold

    /**
     * The state of [stored] under [pin]. An op is valid when its author is an admin at that point
     * and its counter is at most [MAX_STEP] above the highest valid one before it (the founder's
     * never jump); it is effective when it also changed something. Without a founder nobody is an
     * admin, and the ops wait in [limbo].
     */
    private fun derive(pin: RoleOp?, stored: List<RoleOp>, limbo: List<RoleOp>): State {
        if (pin == null) return State(null, emptyList(), limbo, LinkedHashMap(), false, null, emptyList(), emptyList(), emptyMap(), 0,
            listOf(Moment(Long.MIN_VALUE, emptySet(), false)), EMPTY_DIGEST, 0, emptySet(), limbo.mapTo(HashSet()) { it.id })
        val adm = LinkedHashMap<String, String>(); adm[pin.author] = pin.id
        var only = false; var by: String? = null; var maxN = 1
        val eff = ArrayList<RoleOp>(); val sup = HashMap<String, String>()
        for (op in stored) {
            val auth = adm[op.author] ?: continue                                   // not an admin here: kept, no effect
            if (op.author != pin.author && op.n > maxN + MAX_STEP) continue         // jumped too far (the founder never does)
            sup[op.id] = auth; maxN = maxOf(maxN, op.n)
            val x = op.target
            val changed = when (op.type) {
                RoleOp.GRANT -> x != null && Crypto.isNodeId(x) && x !in adm && run { adm[x] = op.id; true }
                RoleOp.REVOKE -> x != null && x != pin.author && x in adm && run { adm.remove(x); true }
                RoleOp.SET -> op.key == RoleOp.SEND && (op.value == RoleOp.ALL || op.value == RoleOp.ADMINS) &&
                    (op.value == RoleOp.ADMINS) != only && run { only = op.value == RoleOp.ADMINS; by = op.id; true }
                else -> false                                                       // a later version's type: valid, never effective
            }
            if (changed) eff.add(op)
        }
        return State(pin, stored, limbo, adm, only, by, eff, toldOf(eff), sup, maxN, timelineOf(pin.author, eff), digestOf(pin, stored),
            stored.count { it.author == pin.author }, stored.mapTo(HashSet()) { it.id }, limbo.mapTo(HashSet()) { it.id })
    }

    // ---------------------------------------------------------------- the timeline, and what the chat tells

    /** Grants and revokes are about their target; every set is about the one setting. */
    private fun subjectOf(op: RoleOp): String = if (op.type == RoleOp.SET) "send" else "x:" + op.target

    /** The state at each instant: for each subject, the effective op last in fold order among those stamped at or before it. */
    private fun timelineOf(founder: String, eff: List<RoleOp>): List<Moment> {
        val rank = HashMap<String, Int>(); eff.forEachIndexed { i, op -> rank[op.id] = i }
        val byTs = eff.sortedWith(compareBy<RoleOp>({ it.ts }, { rank.getValue(it.id) }))
        val last = HashMap<String, Pair<Int, Boolean>>()   // admin -> (fold rank, is admin)
        var setRank = -1; var only = false
        val out = arrayListOf(Moment(Long.MIN_VALUE, setOf(founder), false))
        var i = 0
        while (i < byTs.size) {
            val t = byTs[i].ts
            while (i < byTs.size && byTs[i].ts == t) {
                val op = byTs[i++]; val r = rank.getValue(op.id)
                when (op.type) {
                    RoleOp.GRANT, RoleOp.REVOKE -> op.target?.let { x -> if ((last[x]?.first ?: -1) < r) last[x] = r to (op.type == RoleOp.GRANT) }
                    RoleOp.SET -> if (r > setRank) { setRank = r; only = op.value == RoleOp.ADMINS }
                }
            }
            val admins = LinkedHashSet<String>().apply { add(founder); for ((x, v) in last) if (v.second) add(x) }
            if (admins != out.last().admins || only != out.last().onlyAdmins) out.add(Moment(t, admins, only))
        }
        return out
    }

    /** Effective ops that hold from their own stamp: nothing later in fold order about the same subject is stamped at or before them. */
    private fun toldOf(eff: List<RoleOp>): List<RoleOp> {
        val rank = HashMap<String, Int>(); eff.forEachIndexed { i, op -> rank[op.id] = i }
        val out = ArrayList<RoleOp>()
        for ((_, ops) in eff.groupBy(::subjectOf)) {
            var best = -1
            for (op in ops.sortedWith(compareBy<RoleOp>({ it.ts }, { -rank.getValue(it.id) }))) {
                val r = rank.getValue(op.id); if (r > best) { out.add(op); best = r }
            }
        }
        return out.sortedWith(compareBy<RoleOp>({ it.ts }, { rank.getValue(it.id) }))
    }

    private fun bars(m: Moment, id: String) = m.onlyAdmins && id !in m.admins

    /**
     * The start of the barred spell [ts] falls deep inside for [sender], or null when they could
     * send then. [SLACK_MS] at each edge of a spell goes the sender's way, so a spell shorter than
     * twice that bars nothing.
     */
    fun barredSince(sender: String, ts: Long): Long? {
        if (s.pin == null) return null
        val tl = s.timeline
        val t = ts.coerceIn(-RoleOp.MAX_TS, RoleOp.MAX_TS)
        var lo = 0; var hi = tl.size - 1                  // the last moment from at or before t (the first is from the start of time)
        while (lo < hi) { val mid = (lo + hi + 1) / 2; if (tl[mid].from <= t) lo = mid else hi = mid - 1 }
        if (!bars(tl[lo], sender)) return null
        var a = lo; while (a > 0 && bars(tl[a - 1], sender)) a--
        var b = lo + 1; while (b < tl.size && bars(tl[b], sender)) b++
        val start = tl[a].from                            // never the start of time: the first moment bars nobody
        val end = if (b < tl.size) tl[b].from else Long.MAX_VALUE
        return if (t - SLACK_MS >= start && (end == Long.MAX_VALUE || t + SLACK_MS < end)) start else null
    }

    /** Could [sender] post at [ts]? What every phone holding the same ops answers alike. */
    fun maySend(sender: String, ts: Long): Boolean = barredSince(sender, ts) == null

    /** Null while [id] may not send now; else when the spell they may send in began (0: never barred). */
    fun allowedFrom(id: String): Long? {
        if (s.pin == null) return 0L
        val tl = s.timeline
        if (bars(tl.last(), id)) return null
        var k = tl.size - 1
        while (k > 0 && !bars(tl[k - 1], id)) k--
        return if (k == 0) 0L else tl[k].from
    }

    // ---------------------------------------------------------------- making ops

    /** The set has no room for another op by [author]: stored ops for anyone else, the founder's own for the founder. */
    fun full(author: String): Boolean {
        val p = s.pin ?: return false
        return if (author == p.author) s.founderOps >= MAX_OPS else s.stored.size >= MAX_OPS
    }

    /** The counter of my next op: one more than the highest valid one. Null once there is none left. */
    fun nextN(): Int? = if (s.maxValidN >= RoleOp.MAX_N) null else s.maxValidN + 1

    /** Found the group ([sure]: started here; else a claim). n = 1, so founding again is the same op. The op if it is now the founder, else null. */
    fun found(sure: Boolean, ts: Long): RoleOp? {
        val op = RoleOp.make(keys, airTag, 1, ts.coerceIn(0, RoleOp.MAX_TS), RoleOp.FOUND, mapOf("sure" to if (sure) 1L else 0L))
        accept(listOf(op))
        return op.takeIf { s.pin?.id == it.id }
    }

    /** Make [x] an admin. The op, or null when I'm not an admin, [x] already is one, or there is no room. */
    fun grant(x: String, ts: Long): RoleOp? =
        if (!isAdmin(me) || !Crypto.isNodeId(x) || isAdmin(x)) null else make(RoleOp.GRANT, mapOf("x" to x), ts)

    /** Dismiss [x] as admin. Null when I'm not an admin, [x] isn't one or is the founder, or there is no room. */
    fun revoke(x: String, ts: Long): RoleOp? =
        if (!isAdmin(me) || x == founder || !isAdmin(x)) null else make(RoleOp.REVOKE, mapOf("x" to x), ts)

    /** Let only admins send ([on]) or everyone. Null when I'm not an admin, it is already so, or there is no room. */
    fun setOnlyAdmins(on: Boolean, ts: Long): RoleOp? =
        if (!isAdmin(me) || onlyAdmins == on) null else make(RoleOp.SET, mapOf("k" to RoleOp.SEND, "w" to if (on) RoleOp.ADMINS else RoleOp.ALL), ts)

    private fun make(type: String, extra: Map<String, Any>, ts: Long): RoleOp? {
        if (full(me)) return null
        val n = nextN() ?: return null
        val op = RoleOp.make(keys, airTag, n, ts.coerceIn(0, RoleOp.MAX_TS), type, extra)
        accept(listOf(op))
        // Its counter is above every valid op and what it asks was checked on the state now, so it is always kept, and
        // below the cap always effective. At the cap a founder's op may change nothing itself, when the op it pushed out
        // was all that stood in its way: it went into the set all the same, so it is handed back, to be sent and saved.
        return op.takeIf { it.id in s.storedIds }
    }

    // ---------------------------------------------------------------- digest, carriers, saving

    /**
     * What a carrier of [op] embeds: the whole set while it fits [budget] bytes; else what lets a
     * phone reached only through older phones check this change and who may post (the founder, and
     * the chains of grants behind the op, the setting and every admin); else the founder and the op.
     */
    fun carrierOps(op: RoleOp, budget: Int): List<RoleOp> {
        val p = s.pin ?: return listOf(op)
        fun size(l: List<RoleOp>) = l.sumOf { it.toJson().toString().length + 1 }
        val all = wire()
        if (size(all) <= budget) return all
        val want = LinkedHashSet<String>()
        fun chain(start: String?) { var cur = start; while (cur != null && cur != p.id && want.add(cur)) cur = s.supportOf[cur] }
        chain(op.id); chain(s.sendBy); for (id in s.adminBy.keys) chain(s.adminBy[id])
        val some = listOf(p) + s.stored.filter { it.id in want }
        return if (size(some) <= budget) some else listOf(p, op)
    }

    /** The founder and the kept ops, each key only when there is something in it. What waits is never saved. */
    fun toJson(): JSONObject = JSONObject().apply {
        s.pin?.let { put("pin", it.toJson()) }
        if (s.stored.isNotEmpty()) put("ops", JSONArray(s.stored.map { it.toJson() }))
    }

    /** This phone's own saved state: trusted (not checked again), each entry on its own, and one bad entry costs only itself. */
    fun restore(j: JSONObject) {
        val pin = j.optJSONObject("pin")?.let { RoleOp.parse(it) }?.takeIf { it.type == RoleOp.FOUND }?.also { it.checked = true }
        val ops = ArrayList<RoleOp>()
        j.optJSONArray("ops")?.let { a ->
            for (i in 0 until a.length())
                (a.opt(i) as? JSONObject)?.let { RoleOp.parse(it) }?.takeIf { it.type != RoleOp.FOUND }?.let { it.checked = true; ops.add(it) }
        }
        s = settle(pin, ops.distinctBy { it.id }, emptySet(), Poison())   // an op written twice is read once, as a merge would
    }

    /** No founder, no ops: as a phone that never heard of any. */
    fun clear() { s = derive(null, emptyList(), emptyList()) }

    companion object {
        /** Ops kept at most. Past it the founder's are kept first, then the lowest in fold order. */
        const val MAX_OPS = 512
        /** Ops kept waiting for a founder or a grant at most, in memory only. */
        const val MAX_LIMBO = 64
        /** How far past the highest valid counter anyone but the founder may jump. */
        const val MAX_STEP = 1024
        /** How much of each edge of a barred spell goes the sender's way. */
        const val SLACK_MS = 120_000L

        private fun digestOf(pin: RoleOp?, stored: List<RoleOp>): String =
            Crypto.b64(Crypto.sha256(Crypto.lp("hopline/v5/roles", pin?.id ?: "", *stored.map { it.id }.toTypedArray())).copyOf(16))

        /** What a set with no founder and no ops kept adds up to: the digest of a phone that holds nothing (ops still waiting don't count). */
        val EMPTY_DIGEST: String = digestOf(null, emptyList())
    }
}
