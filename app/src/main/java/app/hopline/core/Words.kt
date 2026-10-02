package app.hopline.core

import java.security.SecureRandom

/**
 * The group code is three everyday words: "tiger river lamp". Easy to say across a campsite, easy to
 * type with gloves on, and no look-alike letters or sound-alike words (no "key"/"quay", "red"/"read").
 */
object Words {
    val LIST: List<String> = """
        apple banana cherry grape lemon mango melon orange peach plum kiwi olive pumpkin potato tomato onion garlic pepper
        tiger lion zebra panda koala camel sheep goat rabbit monkey otter walrus turtle frog snake lizard eagle parrot owl
        robin duck goose swan penguin dolphin shark crab shrimp squid spider beetle ladybug dragon unicorn puppy kitten pony
        donkey hippo rhino giraffe gorilla badger beaver bison falcon heron pelican salmon trout octopus lobster wasp cricket
        river lake ocean island mountain valley forest jungle meadow garden canyon volcano glacier waterfall pond cave cliff
        hill field swamp moon star cloud storm thunder rainbow snow wind fog frost comet planet rocket galaxy sunrise sunset
        sky breeze green yellow purple pink silver gold brown black white violet indigo crimson scarlet lamp chair table
        window door pillow blanket candle mirror clock basket bucket bottle kettle spoon fork plate cup bowl carpet sofa shelf
        hat sock glove scarf jacket button zipper pocket ribbon crown helmet boot sandal belt car bus train truck boat ship
        bike wagon canoe kayak scooter tractor balloon jet sled drum guitar piano violin trumpet flute whistle banjo harp
        pizza cookie muffin pancake waffle noodle honey butter sugar salt cheese toast soup salad taco burger pretzel popcorn
        jelly candy donut king queen wizard pirate robot ninja giant puppet clown cowboy sailor farmer baker doctor tent rope
        torch compass map trail camp cabin bridge tower castle tunnel ladder fence barn igloo wall kite puzzle marble crayon
        pencil paper book letter stamp coin lock magnet yoyo happy brave quick quiet fuzzy shiny sleepy jolly lucky silly
        tiny mighty gentle sunny windy rainy frosty dusty sparkly jump dance sing swim climb ride hop skip clap spin run
        walk fly pebble sand mud shell feather leaf acorn cactus bamboo maple willow tulip daisy lotus clover fern moss oak
        pine palm cotton wool velvet copper iron crystal ruby amber jade hammer nest hive north south east west
    """.trim().split(Regex("\\s+"))

    private val rng = SecureRandom()

    fun randomCode(n: Int = 3): String = (1..n).joinToString(" ") { LIST[rng.nextInt(LIST.size)] }

    /** "Tiger, River LAMP" -> "tiger-river-lamp". Anything non-alphabetic is a separator. */
    fun normalise(code: String): String =
        code.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }.joinToString("-")

    fun pretty(code: String): String = normalise(code).replace('-', ' ')

    fun looksValid(code: String): Boolean = normalise(code).split('-').let { it.size == 3 && it.all { w -> w.length >= 2 } }

    // ------------------------------------------------------------------ checking what someone typed

    private val SET: Set<String> = LIST.toHashSet()

    /**
     * Spellings people reach for that aren't on the list. Applied only to what someone TYPES or
     * hears read out — a code's words are its identity, so the list itself never changes.
     */
    private val ALIAS = mapOf(
        "doughnut" to "donut", "doughnuts" to "donut", "plumb" to "plum", "guerrilla" to "gorilla", "guerilla" to "gorilla",
        "ladybird" to "ladybug", "yoyos" to "yoyo",
    )

    private fun tokens(code: String): List<String> = code.lowercase().split(Regex("[^a-z]+")).filter { it.isNotEmpty() }

    /** Words in [code] that are not Hopline code words ("doughnut", "plumb"). Empty when every word is known. */
    fun unknownWords(code: String): List<String> = tokens(code).filter { it !in SET }

    /** Three words, every one of them from the list: the only kind of code Hopline ever makes. */
    fun isKnownCode(code: String): Boolean = looksValid(code) && unknownWords(code).isEmpty()

    /**
     * The code they probably meant, or null when there is nothing better to offer. Fixes the
     * mistakes people really make with a code read out across a campsite: spelling variants
     * ("doughnut"), words run together ("tigerriver") or split ("yo yo"), and small typos
     * ("tigre", "rivr"). A typo is only fixed when exactly one list word is that close, so a
     * suggestion is never a coin toss.
     */
    fun suggest(code: String): String? {
        val start = tokens(code).map { ALIAS[it] ?: it }
        if (start.isEmpty()) return null
        // "yo yo" → "yoyo", "lady bug" → "ladybug": join two pieces when together they are a word.
        val merged = ArrayList<String>()
        var i = 0
        while (i < start.size) {
            val a = start[i]; val b = start.getOrNull(i + 1)
            val joined = b?.let { (a + it).let { j -> ALIAS[j] ?: j } }
            if (joined != null && joined in SET && (a !in SET || b !in SET)) { merged += joined; i += 2 } else { merged += a; i++ }
        }
        // "tigerriver" → "tiger river": split a run-together word when there is exactly one way to.
        val words = merged.flatMap { w -> if (w in SET) listOf(w) else split(w) ?: listOf(w) }
        if (words.size != 3) return null
        val fixed = words.map { w -> if (w in SET) w else nearest(w) ?: return null }
        val out = fixed.joinToString(" ")
        return if (out == tokens(code).joinToString(" ")) null else out
    }

    /** The only way to cut [w] into two or three list words, or null (none, or more than one). */
    private fun split(w: String): List<String>? {
        val found = ArrayList<List<String>>()
        for (a in 2..w.length - 2) {
            val head = w.substring(0, a)
            if (head !in SET) continue
            val tail = w.substring(a)
            if (tail in SET) found += listOf(head, tail)
            for (b in 2..tail.length - 2) {
                val mid = tail.substring(0, b); val end = tail.substring(b)
                if (mid in SET && end in SET) found += listOf(head, mid, end)
            }
            if (found.size > 1) return null
        }
        return found.singleOrNull()
    }

    /** The one list word within a typo or two of [w] (one for short words), or null if none or a tie. */
    private fun nearest(w: String): String? {
        val max = if (w.length <= 4) 1 else 2
        var best: String? = null
        var bestD = max + 1
        var tie = false
        for (cand in LIST) {
            if (kotlin.math.abs(cand.length - w.length) > max) continue
            val d = distance(w, cand, max)
            if (d < bestD) { best = cand; bestD = d; tie = false }
            else if (d == bestD && cand != best) tie = true
        }
        return if (best != null && !tie) best else null
    }

    /** Edit distance where swapping two neighbouring letters ("tigre") counts as one slip. */
    private fun distance(a: String, b: String, cap: Int): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return cap + 1   // already too far: stop early
        }
        return d[a.length][b.length]
    }
}
