package app.hopline.service

import app.hopline.data.History
import app.hopline.mesh.Attachment
import app.hopline.mesh.Message
import app.hopline.mesh.Router
import org.json.JSONObject

/**
 * The plain rules between a router's live window and its group's history on disk: how a batch of
 * old messages changes hands without ever being in neither place, which chat a filed message
 * belongs to, and how a chat reads its older messages back. No Android in here, so the JVM tests
 * can pin them down; Core supplies the threads.
 */
object HistoryRules {
    /** About how many of a chat's older messages one scroll to the top brings back. */
    const val PAGE = 80
    /**
     * Segments read in one go. A private chat with a few messages scattered through a long history
     * may need hundreds of segments looked through for one page; in steps this size, a save that
     * is waiting for the same thread gets its turn in between.
     */
    const val STEP = 16

    /**
     * Which chat a filed message belongs to on the phone whose id is [me] — [Message.chatKey], read
     * straight off the record, so a whole segment can be sifted without building its messages.
     */
    fun chatOf(record: JSONObject, me: String): String {
        val to = record.optString("to", "")
        val from = record.optString("from", "")
        return if (to.isEmpty()) Message.GROUP_CHAT else if (from == me) to else from
    }

    /**
     * Step one of filing: the messages that have left [r]'s live window, to be written to the
     * history — while the router goes on holding them. They stay part of every snapshot until
     * [settle] says the history has them, so a state saved (or a phone killed) in between loses
     * nothing: at worst a message is in both places, and readers go by id.
     */
    fun offer(r: Router): List<Message> {
        val batch = r.takeOverflow()
        r.returnOverflow(batch)
        return batch
    }

    /**
     * Step two, once [filed] is safely on disk: the router lets go of exactly those — and refuses
     * their ids from now on, so a friend still carrying one can't make it show up again as new.
     * Whatever moved out of the window after [offer] waits for the next round.
     */
    fun settle(r: Router, filed: List<Message>) {
        val ids = filed.mapTo(HashSet()) { it.id }
        r.returnOverflow(r.takeOverflow().filter { it.id !in ids })
    }

    /**
     * "Delete for me" for messages that are between the two steps (or stuck there, on a phone too
     * full to file them): they are neither in the live window, where the router's own delete looks,
     * nor reliably in the history yet. Takes the ones [gone] picks out of the waiting batch for
     * good and returns them, so their files can go too.
     */
    fun dropWaiting(r: Router, gone: (Message) -> Boolean): List<Message> {
        val waiting = r.takeOverflow()
        if (waiting.isEmpty()) return emptyList()
        val (out, keep) = waiting.partition(gone)
        r.returnOverflow(keep)
        return out
    }

    /**
     * One page of a chat's older messages, read a few segments at a time: call [step] until it
     * says the page is ready. A message filed twice (see [History]) is read once, the copy filed
     * last. Not thread-safe: one thread at a time, like the history it reads.
     *
     * [before] is the `next` of the page above this one — null to start at the newest segment.
     */
    class Walk(private val history: History, private val chat: String, private val me: String, before: Int?) {
        private var from: Int? = before
        private val found = ArrayList<Message>()
        private val ids = HashSet<String>()

        /** What to pass as `before` for the page below this one; 0 = there is nothing older. */
        var next = 0
            private set

        /** Read the next few segments down. True when the page is ready: full, or the history ran out. */
        fun step(): Boolean {
            val page = history.earlier(from, (PAGE - found.size).coerceAtLeast(1), STEP) { chatOf(it, me) == chat && it.optString("id") !in ids }
            for (record in page.records) {
                val m = try { Message.fromJson(record) } catch (e: Exception) { continue }
                if (m.att != null && !Attachment.validFid(m.att.fid)) continue
                if (ids.add(m.id)) found.add(m)
            }
            next = page.next
            from = page.next
            return next == 0 || found.size >= PAGE
        }

        /**
         * The page in chat order. Order across segments is not a given: a message can be filed late
         * (it was still being sent, or its file was still arriving) after newer ones went in.
         */
        fun messages(): List<Message> = found.sortedBy { it.sortKey }
    }

    /**
     * Deleting from a group's history — "Delete for me", "Clear chat" — a few segments at a time:
     * call [step] until [done]. Every segment is read, and each one that changes is written out
     * again, so a long history sifted in one go would keep the writer thread for seconds while
     * saves, and a main thread waiting to open a chat, queue up behind it. Not thread-safe: one
     * thread at a time, like the history it works on.
     *
     * The first step notes the newest segment there is, and nothing filed after it is ever looked
     * at: [gone] can name a whole chat, and a message that reaches the history after the chat was
     * cleared is not one the person cleared.
     */
    class Forget(private val history: History, private val gone: (JSONObject) -> Boolean) {
        private var after = 0
        private var upTo = -1

        /** True once every segment that was there has been looked through. */
        var done = false
            private set

        /** Sift the next few segments. Returns what was taken out of them, so those messages' files can go too. */
        fun step(): List<JSONObject> {
            if (done) return emptyList()
            if (upTo < 0) upTo = history.segments().lastOrNull() ?: 0
            val went = history.removeWhere(after, upTo, STEP, gone)
            after = went.next
            done = went.next == 0
            return went.records
        }
    }
}
