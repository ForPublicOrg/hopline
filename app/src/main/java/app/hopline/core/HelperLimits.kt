package app.hopline.core

/**
 * What keeps "Share my internet" safe to leave on: anyone with the group code can ask, so the
 * phone with signal paces each asker and never runs more than a couple of fetches at once. The
 * daily data budget itself lives in the app's store (it must survive restarts); this is the
 * in-memory pacing. Pure and clock-injected, so it is unit-tested.
 */
class HelperLimits(private val clock: () -> Long = System::currentTimeMillis) {
    private val asks = HashMap<String, ArrayDeque<Long>>()     // requester -> recent fetch times
    private val texts = HashMap<String, ArrayDeque<Long>>()    // requester -> recent text requests
    private var runningNow = 0

    /** Null when the helper may start it now; otherwise a short reason code. */
    fun check(requester: String, isText: Boolean): String? {
        val now = clock()
        if (!isText && runningNow >= MAX_CONCURRENT) return "busy"
        val window = if (isText) texts.getOrPut(requester) { ArrayDeque() } else asks.getOrPut(requester) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() > DAY_MS) window.removeFirst()
        val (shortMs, shortMax, dayMax) = if (isText) Triple(HOUR_MS, TEXTS_PER_HOUR, TEXTS_PER_DAY) else Triple(TEN_MIN_MS, FETCHES_PER_10_MIN, FETCHES_PER_DAY)
        if (window.size >= dayMax) return "limit"
        if (window.count { now - it < shortMs } >= shortMax) return "limit"
        return null
    }

    fun started(requester: String, isText: Boolean) {
        val map = if (isText) texts else asks
        map.getOrPut(requester) { ArrayDeque() }.addLast(clock())
        if (!isText) runningNow++
    }

    fun finished(isText: Boolean) { if (!isText) runningNow = maxOf(0, runningNow - 1) }

    companion object {
        const val MAX_CONCURRENT = 2
        const val FETCHES_PER_10_MIN = 6
        const val FETCHES_PER_DAY = 40
        const val TEXTS_PER_HOUR = 3
        const val TEXTS_PER_DAY = 10
        private const val TEN_MIN_MS = 10 * 60_000L
        private const val HOUR_MS = 3600_000L
        private const val DAY_MS = 24 * 3600_000L
    }
}
