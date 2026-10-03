package app.hopline.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A group's older messages, on disk: everything that has moved out of the router's live window.
 * One directory per group, holding numbered segments — 000001.json, 000002.json, … — each a JSON
 * array of messages exactly as the router saved them. A chat reads them back, newest segment
 * first, when someone scrolls up past the live window.
 *
 * Segments are append-only: a new batch is always a new file, written to a temporary name, synced
 * and then renamed, so a power cut leaves either the whole segment or none of it — never half a
 * file where history used to be. The only thing that rewrites a segment is the person deleting
 * messages, and that goes through the same temp-and-rename.
 *
 * The same message can be in here twice, or in here and in the live window: the app files a batch
 * first and saves the router's state after, so a phone killed in between files that batch again
 * next time. Nothing is lost that way, and readers go by message id ([earlier] does).
 *
 * Plain java.io and JSON, no Android, so the JVM tests can pin it down. Not thread-safe: the app
 * uses it from its one writer thread.
 */
class History(private val dir: File) {

    /**
     * What [earlier] found: [records] oldest first, and [next] — what to pass as `before` to get
     * the page before this one, or 0 when there is nothing older.
     */
    class Page(val records: List<JSONObject>, val next: Int)

    /**
     * File [messages] as the next segment. True when they are safely on disk (or there was nothing
     * to file). False means nothing was written — the caller still has them and must not let go.
     */
    fun append(messages: List<JSONObject>): Boolean {
        if (messages.isEmpty()) return true
        return try {
            if (!dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) return false
            // Whatever a kill left half-written was never part of the history.
            dir.listFiles()?.forEach { if (it.name.startsWith("~") && it.name.endsWith(".tmp")) it.delete() }
            var seq = (segments().lastOrNull() ?: 0) + 1
            while (file(seq).exists()) seq++   // a new batch never replaces a file that is already there
            write(seq, JSONArray(messages))
        } catch (e: Exception) { false }
    }

    /** The segments on disk, oldest first. Looking never creates the directory. */
    fun segments(): List<Int> = (dir.list() ?: emptyArray()).mapNotNull { seqOf(it) }.sorted()

    fun isEmpty(): Boolean = segments().isEmpty()

    /**
     * One segment's messages in the order they were filed. A record that isn't a message object
     * is skipped; a file that can't be read or parsed gives nothing — and is left exactly as it is.
     */
    fun read(seq: Int): List<JSONObject> {
        val a = load(seq) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.takeIf { idOf(it).isNotEmpty() } }
    }

    /**
     * A page of older messages for one chat: walks down from the segment below [before] (from the
     * newest when null), collecting records [want] accepts, until it has at least [atLeast] or runs
     * out of segments. A message filed twice comes back once — the copy filed last, which had the
     * longest to collect its reactions and ticks. Duplicates of a message already shown (an earlier
     * page, the live window) are for the caller to skip by id.
     */
    fun earlier(before: Int?, atLeast: Int, want: (JSONObject) -> Boolean): Page = earlier(before, atLeast, Int.MAX_VALUE, want)

    /**
     * [earlier], reading at most [maxSegments] segments: a chat with a handful of messages scattered
     * through a long history would otherwise read every segment in one go, and nothing else gets
     * the thread meanwhile. A page cut short that way may hold fewer than [atLeast] — even none —
     * and still have a [Page.next] to carry on from.
     */
    fun earlier(before: Int?, atLeast: Int, maxSegments: Int, want: (JSONObject) -> Boolean): Page {
        val segs = segments().filter { before == null || it < before }
        val seen = HashSet<String>()
        val pages = ArrayList<List<JSONObject>>()
        var found = 0
        var next = 0
        for (k in segs.indices.reversed()) {
            val got = read(segs[k]).asReversed().filter { want(it) && seen.add(idOf(it)) }.asReversed()
            pages.add(got); found += got.size
            if (found >= atLeast || pages.size >= maxSegments) { next = if (k > 0) segs[k] else 0; break }
        }
        return Page(pages.asReversed().flatten(), next)
    }

    /** Delete these messages from the history. Returns what was removed, so their files can go too. */
    fun remove(ids: Set<String>): List<JSONObject> = if (ids.isEmpty()) emptyList() else removeWhere { idOf(it) in ids }

    /**
     * Delete every message [gone] picks. Only the segments that actually change are rewritten; one
     * left without messages is deleted. A segment that can't be parsed is left alone, and one
     * whose rewrite fails keeps all its messages — they are then not in the returned list either.
     */
    fun removeWhere(gone: (JSONObject) -> Boolean): List<JSONObject> = removeWhere(0, Int.MAX_VALUE, Int.MAX_VALUE, gone).records

    /**
     * What one go of [removeWhere] took out, and [next] — what to pass as `after` to carry on from
     * there, or 0 when every segment has been looked through.
     */
    class Removed(val records: List<JSONObject>, val next: Int)

    /**
     * [removeWhere], a few segments at a time: only segments numbered above [after] and no higher
     * than [upTo], oldest first, and no more than [maxSegments] of them in one go. A long history
     * sifted in one go keeps the thread for seconds, and everything else that needs it waits; in
     * goes, a save gets its turn in between. [upTo] is the newest segment there was when the
     * delete was asked for: what is filed after that was never meant by it.
     */
    fun removeWhere(after: Int, upTo: Int, maxSegments: Int, gone: (JSONObject) -> Boolean): Removed {
        val removed = ArrayList<JSONObject>()
        var looked = 0
        var last = after
        for (seq in segments()) {
            if (seq <= after || seq > upTo) continue
            if (looked >= maxSegments.coerceAtLeast(1)) return Removed(removed, last)
            looked++; last = seq
            val a = load(seq) ?: continue
            val keep = JSONArray()
            val out = ArrayList<JSONObject>()
            var messagesLeft = 0
            for (i in 0 until a.length()) {
                val item = a.opt(i) ?: continue
                val m = (item as? JSONObject)?.takeIf { idOf(it).isNotEmpty() }
                if (m != null && gone(m)) { out.add(m); continue }
                keep.put(item)
                if (m != null) messagesLeft++
            }
            if (out.isEmpty()) continue
            val done = try { if (messagesLeft == 0) file(seq).delete() else write(seq, keep) } catch (e: Exception) { false }
            if (done) removed.addAll(out)
        }
        return Removed(removed, 0)
    }

    /** Delete the group's whole history: every segment and the directory itself. */
    fun deleteAll() { dir.deleteRecursively() }

    private fun file(seq: Int) = File(dir, String.format(java.util.Locale.US, "%06d.json", seq))

    private fun seqOf(name: String): Int? = if (SEGMENT.matches(name)) name.substringBefore('.').toIntOrNull()?.takeIf { it > 0 } else null

    private fun idOf(m: JSONObject): String = m.optString("id", "")

    private fun load(seq: Int): JSONArray? = try { JSONArray(file(seq).readText(Charsets.UTF_8)) } catch (e: Throwable) { null }

    /** All of it under a temporary name, forced to disk, then renamed into place in one step. */
    private fun write(seq: Int, a: JSONArray): Boolean {
        val tmp = File(dir, String.format(java.util.Locale.US, "~%06d.tmp", seq))
        try {
            FileOutputStream(tmp).use { out ->
                out.write(a.toString().toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            try {
                Files.move(tmp.toPath(), file(seq).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.move(tmp.toPath(), file(seq).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            return true
        } catch (e: Exception) {
            tmp.delete()
            return false
        }
    }

    private companion object {
        val SEGMENT = Regex("^\\d{6,9}\\.json$")
    }
}
