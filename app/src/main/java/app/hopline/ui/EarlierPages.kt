package app.hopline.ui

import app.hopline.mesh.Message
import app.hopline.mesh.Router

/**
 * The part of an open chat that is not in the router's live window: its older messages, read back
 * from the group's history a page at a time as the person scrolls up, and shown above the live ones.
 *
 * Three things keep the chat from ever showing a message twice, or dropping one it was showing:
 *  - Messages go by id. The history can hold one twice, or hold one the live window still has (a
 *    phone killed between filing a batch and saving its state); [shown] leaves those out.
 *  - A message that moves out of the live window while someone is reading near it stays on
 *    screen — [follow] notices it go — until the pages are read again and bring it back from
 *    the history.
 *  - When the history changes under the pages already read (a batch was filed on top of them, or
 *    something was deleted from it), they are read again from the newest segment down as far as
 *    they went ([restart]) and swapped in one go ([finish]): the same messages in the same rows,
 *    so the list under the person's thumb does not move.
 *
 * No Android in here, so the JVM tests can pin it down. The chat screen does the reading (through
 * Core, on the writer thread) and hands the pages in. Main thread only, like the router it reads.
 */
class EarlierPages {
    private val list = ArrayList<Message>()            // chat order
    private val byId = HashMap<String, Message>()
    /** Deleted from this screen: never shown again, whatever a page read a moment before still holds. */
    private val gone = HashSet<String>()
    /** The live window at the last [follow], to notice what has left it since. */
    private var window: List<Message> = emptyList()

    /** What to ask the history for to get the page above these: null = nothing asked yet, 0 = there is nothing older. */
    var next: Int? = null
        private set

    val exhausted: Boolean get() = next == 0
    val isEmpty: Boolean get() = list.isEmpty()

    /**
     * How far back the earlier messages on screen go, for a screen that is re-created and must
     * read them again ([reread]); null when there are none to read again.
     */
    val depth: Int? get() = if (list.isEmpty()) null else next ?: NEWEST_ONLY

    /** One of the earlier messages on screen, by id. */
    fun find(id: String): Message? = byId[id]

    /** The page above the ones already here has been read; [next] is where the one above it starts. */
    fun add(page: List<Message>, next: Int) {
        var any = false
        for (m in page) if (m.id !in gone && m.id !in byId) { byId[m.id] = m; list.add(m); any = true }
        // A page is in chat order, and nearly always older than everything before it — but a
        // message filed late can belong between two that an earlier page brought.
        if (any) list.sortBy { it.sortKey }
        this.next = next
    }

    /** "Delete for me" on earlier messages: off the screen now, and not let back by a page read before the delete. */
    fun remove(ids: Collection<String>) {
        gone.addAll(ids)
        var any = false
        for (id in ids) if (byId.remove(id) != null) any = true
        if (any) list.removeAll { it.id !in byId }
    }

    /**
     * Let the pages go, to be read again when the chat is next scrolled to its top. What was
     * deleted from this screen stays deleted, and the live window goes on being followed.
     */
    fun unload() {
        list.clear(); byId.clear()
        next = null
    }

    /** Another chat (or this one, emptied): nothing read, nothing remembered. */
    fun clear() {
        list.clear(); byId.clear(); gone.clear()
        window = emptyList()
        next = null
    }

    /**
     * Keep up with the live window: call once per redraw, before [shown], with the router's
     * messages of this chat as they are now. A message that was in the window last time and that
     * [r] no longer has — and did not delete — is on its way into the history (the window was
     * over its size). It is kept on screen until the pages are read again and find it there.
     *
     * If [keep] says so, that is. The screen says no when the person is nowhere near those
     * messages (they are the chat's oldest, far above someone reading the newest): then they just
     * go off the top of the list, to be read back like any earlier message — and a chat left open
     * does not grow by every batch that is filed under it. [keep] is asked only when something left.
     */
    fun follow(live: List<Message>, r: Router, keep: () -> Boolean = { true }) {
        var held = false
        var kept: Boolean? = null
        for (m in window) {
            if (r.message(m.id) != null || r.isHidden(m.id) || m.id in gone || m.id in byId) continue
            if (kept == null) kept = keep()
            if (kept == false) break
            byId[m.id] = m; list.add(m); held = true
        }
        if (held) list.sortBy { it.sortKey }
        window = live
    }

    /**
     * The chat as it is shown: the earlier messages, then [live] — the router's window on this
     * chat — merged in chat order, each message once. With nothing earlier it is [live] itself,
     * untouched.
     */
    fun shown(live: List<Message>, r: Router): List<Message> {
        if (list.isEmpty()) return live
        val early = list.filter { r.message(it.id) == null && !r.isHidden(it.id) }
        if (early.isEmpty()) return live
        if (live.isEmpty()) return early
        // Mostly "earlier, then live" — but a message of mine still trying to go out, or a file
        // still arriving, stays in the window while newer ones are filed. Chat order is by sortKey.
        val out = ArrayList<Message>(early.size + live.size)
        var i = 0; var j = 0
        while (i < early.size && j < live.size) out.add(if (early[i].sortKey <= live[j].sortKey) early[i++] else live[j++])
        while (i < early.size) out.add(early[i++])
        while (j < live.size) out.add(live[j++])
        return out
    }

    // ---------------------------------------------------------------- reading the pages again

    /**
     * The pages being read again from the newest segment down, to take the place of the ones on
     * screen. They stay on screen, untouched, until [finish].
     */
    class Reload internal constructor(
        /** The cursor the pages on screen had reached: read down to there again. Null when no
         *  page had been read yet, and the only earlier messages are ones that just left the window. */
        val downTo: Int?,
        /** With no cursor to go by: read until a message at least this old has come back. */
        private val oldest: Long,
    ) {
        internal val list = ArrayList<Message>()
        internal val byId = HashMap<String, Message>()

        /** What to ask the history for next; null = start at the newest segment. */
        var next: Int? = null
            internal set

        /** The same reading, from the top once more: the history changed again while it was under way. */
        fun again(): Reload = Reload(downTo, oldest)

        internal fun deepEnough(next: Int): Boolean =
            if (downTo != null) next <= downTo else (list.minOfOrNull { it.sortKey } ?: Long.MAX_VALUE) <= oldest
    }

    /**
     * The history changed under the pages on screen: start reading them again. Null when there is
     * nothing to line up — no page was read and nothing is being kept — and the chat simply starts
     * its paging whenever the person next scrolls to the top.
     */
    fun restart(): Reload? =
        if (list.isEmpty() && next == null) null else Reload(next, list.firstOrNull()?.sortKey ?: Long.MAX_VALUE)

    /** A re-created screen (rotation, process death) reads again the pages its list had, down to [depth]. */
    fun reread(depth: Int): Reload = Reload(depth, Long.MAX_VALUE)

    /**
     * One page of the reading-again. True: read on, from [Reload.next]. False: it has gone as far
     * back as the pages on screen did (or the history ran out) — [finish] it.
     */
    fun take(re: Reload, page: List<Message>, next: Int): Boolean {
        for (m in page) if (m.id !in gone && m.id !in re.byId) { re.byId[m.id] = m; re.list.add(m) }
        re.next = next
        return next != 0 && !re.deepEnough(next)
    }

    /**
     * Swap what was read again in for the pages on screen. A message that was here and is no
     * longer in the history (deleted from another screen) goes; one filed since is there.
     */
    fun finish(re: Reload) {
        list.clear(); byId.clear()
        for (m in re.list) if (m.id !in gone) { byId[m.id] = m; list.add(m) }
        list.sortBy { it.sortKey }
        next = re.next ?: next
    }

    companion object {
        /** A [depth] for "no page was read, only the newest segment matters": every cursor is at or below it. */
        const val NEWEST_ONLY = Int.MAX_VALUE
    }
}
