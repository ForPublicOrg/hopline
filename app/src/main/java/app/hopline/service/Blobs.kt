package app.hopline.service

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.exifinterface.media.ExifInterface
import app.hopline.core.Crypto
import app.hopline.mesh.Attachment
import app.hopline.mesh.ChunkStore
import app.hopline.mesh.Envelope
import app.hopline.mesh.Router
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future

/**
 * Everything about file bytes lives here, out of the router's way:
 *  - a disk-backed ChunkStore (pieces being carried for the group, each sealed under its file's key),
 *  - shrinking a photo until it is small enough to hop, and sealing a file to send,
 *  - gluing arrived pieces back into a real file — every byte checked before it is kept,
 *  - keeping a file on the phone (Pictures / Downloads) and handing it to other apps.
 * Layout: files/blobs/<groupFingerprint>/pieces5/<envelopeId>.json and .../files/<fid>-<name>.
 * (2.3 and older kept plain pieces in .../chunks/; that folder goes for every group.)
 * A group that was left keeps its files/ — they are part of its chat — and loses its pieces;
 * only deleting the group removes the kept files.
 */
object Blobs {
    private const val TAG = "Hopline/Blobs"
    const val MAX_IMAGE_BYTES = 330_000       // ~24 pieces; a photo should hop in seconds, not minutes
    private const val THUMB_DIM = 48

    /** Pieces carried for one group may use this much of the phone, oldest dropped first. */
    const val BUDGET_BYTES = 300L * 1024 * 1024
    /** Below this much free space the phone stops taking pieces, so chats can still be saved. */
    const val RESERVE_BYTES = 200L * 1024 * 1024
    /** Pieces of files nobody here has the message for, before more such pieces are turned away. */
    val ORPHAN_PIECES = 2 * Router.MAX_CHUNKS

    private const val DAY_MS = 24 * 3600_000L

    /** Deletes and sweeps: never on the main thread, never racing each other. */
    private val housekeeping: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "hopline-files").apply { isDaemon = true } }
    /** Every assembly runs here, one at a time, so two can never write the same file. */
    private val assembler: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, ASSEMBLER_THREAD).apply { isDaemon = true } }
    private const val ASSEMBLER_THREAD = "hopline-assemble"
    /** Assemblies queued or running, by group/file: a second request for one joins the first. */
    private val assembling = HashMap<String, Future<BlobRules.Assembly>>()

    /** True while this phone is too full to take more pieces — a screen can say so instead of "Receiving…" forever. */
    @Volatile var storageLow = false
        private set

    fun groupDir(ctx: Context, fp: String): File = File(File(ctx.filesDir, "blobs"), fp)
    private fun piecesDir(ctx: Context, fp: String): File = File(groupDir(ctx, fp), BlobRules.PIECES)
    /** No mkdirs here: this runs on every chat redraw, and must never resurrect a deleted group. */
    private fun filesDir(ctx: Context, fp: String): File = File(groupDir(ctx, fp), "files")

    /** Groups whose files were deleted; late assemble work must not resurrect the directory. */
    private val closed = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * "Delete group": everything this phone holds of the group's files goes — pieces and kept files
     * alike. The folder is renamed aside at once and deleted in the background, so the same code
     * joined again a second later starts in a fresh folder the delete can't reach. Safe to repeat.
     */
    fun deleteGroup(ctx: Context, fp: String) {
        closed.add(fp)
        val dir = groupDir(ctx, fp)
        // If it can't be renamed it is deleted where it stands, as before; [closed] keeps late work out.
        val gone = BlobRules.moveAside(dir, BlobRules.goneGroupName(fp, System.currentTimeMillis())) ?: dir
        housekeeping.execute { gone.deleteRecursively() }
    }

    /**
     * Leaving a group: the pieces it carried for others go (up to 300 MB that serve nobody now);
     * its photos, voice notes and files stay — they are the chat. The folder of pieces is renamed
     * aside first, so a rejoin a moment later can never have its new pieces caught by the delete.
     * The group is NOT closed: its files stay readable, and a late assembly finishing is welcome.
     * True when no pieces are left under the group's name (false: the rename failed; try again later).
     */
    fun dropChunks(ctx: Context, fp: String): Boolean {
        val now = System.currentTimeMillis()
        var none = true
        // Today's pieces, and any plain ones an older version left that nothing has cleared yet.
        for (name in listOf(BlobRules.PIECES, BlobRules.LEGACY_PIECES)) {
            val dir = File(groupDir(ctx, fp), name)
            if (!dir.exists()) continue
            val dead = BlobRules.moveAside(dir, BlobRules.deadChunksName(now, name))
            if (dead == null) { none = false; continue }
            housekeeping.execute { dead.deleteRecursively() }
        }
        return none
    }

    /**
     * The plain pieces 2.3 and older kept for this group go, before its piece store is built —
     * whatever the group's state says, and as often as asked ([BlobRules.legacyAside]). The start-up
     * [sweep] does the same for every group. Files that were still arriving at the upgrade are
     * lost with them: no phone takes those pieces any more.
     */
    private fun dropLegacyPieces(ctx: Context, fp: String) {
        val old = BlobRules.legacyAside(groupDir(ctx, fp), System.currentTimeMillis()) ?: return
        housekeeping.execute { old.deleteRecursively() }
    }

    /** Run a slow file job (saving, copying) off the main thread, in order with the other file chores. */
    fun background(job: () -> Unit) {
        housekeeping.execute { try { job() } catch (t: Throwable) { Log.w(TAG, "file job failed", t) } }
    }

    // ------------------------------------------------------------------ the on-disk chunk store

    /**
     * Thread-safe: the router uses it on the main thread while assembly reads from it. Every piece
     * is one file named by its (strictly checked) id; the file's timestamp is when its 48 h began
     * on this phone ([BlobRules.anchor]). The index keeps size and age in memory, so expiry and the
     * disk budget never have to touch the disk to decide.
     *
     * The router remembers every piece id it has seen and throws away any copy that comes again.
     * So a piece this phone lets go of while friends still carry it stays listed in the inventory
     * ([advertised]) until they are done with it — or they would re-send it every sync, only for it
     * to be thrown away here. A piece turned away only for now (no room, a stray) is handed back to
     * the router once it can be taken ([takeReleased]), so the next sync brings it again.
     */
    class DiskChunkStore(
        private val dir: File,
        private val budgetBytes: Long = BUDGET_BYTES,
        private val reserveBytes: Long = RESERVE_BYTES,
        private val orphanCap: Int = ORPHAN_PIECES,
        private val clock: () -> Long = System::currentTimeMillis,
        private val background: Executor = housekeeping,
        private val log: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) },
        /** Free bytes where the pieces live; 0 when that can't be told. */
        private val freeSpace: () -> Long = { dir.usableSpace },
    ) : ChunkStore {
        /**
         * Does this phone have the message for a file id? Pieces of unknown files are "orphans":
         * fine while a photo's pieces race ahead of its message, refused once they pile up.
         */
        @Volatile var isKnownFile: (String) -> Boolean = { true }
        /** Is this piece one of my own sends? Those are always kept: only this phone may have them yet. */
        @Volatile var isMine: (Envelope) -> Boolean = { false }

        /**
         * Hold pieces for [r]: which files it knows (shown, or held back for the others: Router.holdsFile)
         * and which are mine is what IT knows — a file this phone sent (Router.isMine) — never what a
         * piece claims. A piece signed with my id for a file I never sent is a stranger's, under the
         * reserve and the orphan cap like any other.
         * Asked from put(), which runs on the router's thread.
         */
        fun servedBy(r: Router) {
            isKnownFile = { fid -> r.fileMessage(fid) != null || r.holdsFile(fid) }
            isMine = { env -> BlobRules.chunkFid(env.id)?.let { r.isMine(it) } == true }
        }

        private class Entry(val fid: String, val size: Long, val at: Long, val orphan: Boolean)

        /** Why a piece that isn't kept here is still listed: let go of for good, or turned away for now. */
        private enum class Why { DROPPED, NO_ROOM, STRAY }
        private class Tomb(val why: Why, val until: Long)

        private val index = HashMap<String, Entry>()
        private var total = 0L
        /** Orphan pieces stored, per file id. */
        private val orphans = HashMap<String, Int>()
        private var orphanCount = 0
        /** Pieces not kept here that friends may still carry, oldest first. Never also in [index]. */
        private val tombs = LinkedHashMap<String, Tomb>()
        /** Pieces this phone can take again, until the router collects them in [takeReleased]. */
        private val released = LinkedHashSet<String>()
        private var spaceCheckedAt = 0L
        private var spaceLow = false
        /** Free bytes at the last check (0 = couldn't tell). */
        private var freeAtCheck = 0L
        private var anchorWarned = false

        init {
            dir.mkdirs()
            val now = clock()
            dir.listFiles()?.forEach { f ->
                val name = f.name
                when {
                    // A write cut short (the app was killed mid-piece): never half a piece.
                    name.endsWith(".tmp") -> f.delete()
                    name.endsWith(".json") -> {
                        val id = name.removeSuffix(".json")
                        val fid = BlobRules.chunkFid(id)
                        val attrs = try { Files.readAttributes(f.toPath(), BasicFileAttributes::class.java) } catch (e: Exception) { null }
                        if (fid == null || attrs == null || !attrs.isRegularFile || attrs.size() <= 0) { f.delete(); return@forEach }
                        // A stamp from the future means the clock was set back since: start its 48 h now.
                        val at = attrs.lastModifiedTime().toMillis().let { if (it > now + Router.FUTURE_SLACK_MS) now else it }
                        index[id] = Entry(fid, attrs.size(), at, orphan = false)
                        total += attrs.size()
                    }
                }
            }
        }

        private fun fileOf(id: String): File? {
            if (BlobRules.chunkFid(id) == null) return null
            val f = File(dir, "$id.json")
            return if (BlobRules.isInside(dir, f)) f else null
        }

        override fun put(env: Envelope): Boolean {
            val id = try { env.id } catch (e: Exception) { return false }
            val fid = BlobRules.chunkFid(id) ?: run { log("refused a piece with a bad id", null); return false }
            val target = fileOf(id) ?: return false
            synchronized(this) { if (id in index) return true }   // already carried: keep its original 48 h
            val now = clock()
            // NOTE on "refused": the router forgets a piece whose put() returns false and floods it
            // on again — in a ring of phones that all refuse it, that would circle forever. So a
            // piece turned away on purpose is accepted-and-dropped (true, but not kept), and stays
            // listed so nobody keeps offering it; one turned away only for now is let in again later
            // (see [takeReleased]). Only a failed write returns false.
            val mine = isMine(env)
            // Its time here ran out, or it was squeezed out for room: taking it back would only start that over.
            if (!mine && synchronized(this) { tombs[id]?.why == Why.DROPPED }) return true
            val at = BlobRules.anchor(env.json.optLong("ts", now), now)
            if (!mine && lowOnSpace(now)) { turnAway(id, Why.NO_ROOM, at, now); return true }
            val orphan = !mine && env.to == null && !isKnownFile(fid)
            if (orphan && !roomForOrphan(fid)) {
                log("too many pieces of unknown files; one turned away", null)
                turnAway(id, Why.STRAY, at, now); return true
            }
            val text = env.json.toString().toByteArray(Charsets.UTF_8)
            if (text.size > MAX_PIECE_BYTES) return false
            var tmp: File? = null
            try {
                val t = File.createTempFile("$id.", ".tmp", dir).also { tmp = it }
                t.writeBytes(text)
                if (!t.setLastModified(at) && !anchorWarned) {
                    // The file then keeps "now" — at most a day longer; this session still expires by the index.
                    anchorWarned = true; log("couldn't stamp a piece's time", null)
                }
                val victims = synchronized(this) {
                    if (id in index) return true
                    // One rename: a reader sees the whole piece or none of it, never half.
                    if (!t.renameTo(target)) return false
                    index[id] = Entry(fid, text.size.toLong(), at, orphan)
                    tombs.remove(id); released.remove(id)
                    total += text.size
                    if (orphan) { orphans[fid] = (orphans[fid] ?: 0) + 1; orphanCount++ }
                    if (total > budgetBytes) evictOldestLocked(budgetBytes * 9 / 10, keep = id, now = now) else emptyList()
                }
                if (victims.isNotEmpty()) { log("over the space for pieces: dropped ${victims.size} oldest", null); deleteLater(victims) }
                return true
            } catch (e: Exception) {
                log("piece write failed", e)
                return false
            } finally {
                tmp?.let { if (it.exists()) it.delete() }
            }
        }

        /** Room for one more orphan piece? Pieces whose message has since arrived stop counting. */
        private fun roomForOrphan(fid: String): Boolean {
            val stillUnknown = synchronized(this) {
                if (orphanCount < orphanCap) return true
                orphans.keys.toList()
            }
            val nowKnown = stillUnknown.filter { isKnownFile(it) }
            synchronized(this) {
                for (f in nowKnown) orphans.remove(f)?.let { orphanCount -= it }
                return orphanCount < orphanCap || fid in nowKnown
            }
        }

        @Synchronized private fun lowOnSpace(now: Long): Boolean {
            if (reserveBytes <= 0) return false
            if (now - spaceCheckedAt in 0 until SPACE_CHECK_MS) return spaceLow
            spaceCheckedAt = now
            val free = freeSpace()
            freeAtCheck = free
            // 0 means "couldn't tell" (or a vanished folder, where the write fails honestly anyway).
            // Once full, it stays "full" until there is a real margin again: a phone hovering at the
            // line would otherwise flip every few seconds, taking and refusing the same pieces.
            spaceLow = free in 1 until reserveBytes + (if (spaceLow) RELEASE_MARGIN else 0L)
            storageLow = spaceLow
            return spaceLow
        }

        /** Drop the oldest pieces until [target] bytes remain. Caller holds the lock; files go later. */
        private fun evictOldestLocked(target: Long, keep: String, now: Long): List<String> {
            val victims = ArrayList<String>()
            for ((id, e) in index.entries.sortedBy { it.value.at }) {
                if (total <= target) break
                if (id == keep) continue
                forgetLocked(id, e)
                buryLocked(id, Why.DROPPED, e.at, now)
                victims.add(id)
            }
            return victims
        }

        /** A piece not kept stays listed while friends may carry it. Caller holds the lock. */
        private fun buryLocked(id: String, why: Why, at: Long, now: Long) {
            released.remove(id)
            tombs.remove(id)   // back in at the end: the newest is the last to be forgotten
            tombs[id] = Tomb(why, BlobRules.listUntil(at, now))
            if (tombs.size > MAX_TOMBS) dropOneTombLocked()
        }

        /**
         * Over the cap: forget the oldest piece let go of for good first (late holders of those are
         * rare). Never hand back a piece turned away for room or as a stray just to make space in
         * this list — while the phone is still full it would only be taken, refused and passed on
         * again, round and round. At worst a friend offers the forgotten one once more.
         */
        private fun dropOneTombLocked() {
            val victim = tombs.entries.firstOrNull { it.value.why == Why.DROPPED } ?: tombs.entries.first()
            tombs.remove(victim.key)
        }

        /** Stop listing a piece. One only turned away for now is handed back: a copy may still come in. */
        private fun unlistLocked(id: String, t: Tomb) {
            if (t.why == Why.DROPPED) tombs.remove(id) else refillLocked(id)
        }

        private fun turnAway(id: String, why: Why, at: Long, now: Long) {
            synchronized(this) { if (id !in index) buryLocked(id, why, at, now) }
        }

        /** A piece lost here (damaged, deleted behind our back): let the next sync bring it again. */
        private fun refillLocked(id: String) {
            tombs.remove(id)
            released.add(id)
            if (released.size > MAX_TOMBS) released.remove(released.first())
        }

        /**
         * Pieces turned away only for now that can come in again: all of them once the phone has
         * room, a stray once its file's message is here. Also re-checks the free space on every
         * call, so "storage full" clears when the person frees some even if no piece arrives.
         * Runs on the router's thread ([isKnownFile] asks the router).
         */
        private fun letInAgain(now: Long) {
            if (lowOnSpace(now)) return
            val (waiting, free) = synchronized(this) { tombs.entries.filter { it.value.why != Why.DROPPED }.map { it.key to it.value } to freeAtCheck }
            if (waiting.isEmpty()) return
            val known = HashMap<String, Boolean>()
            fun knownFile(id: String) = BlobRules.chunkFid(id).let { fid -> fid == null || known.getOrPut(fid) { isKnownFile(fid) } }
            // Only as many as there is room for (no point pulling 95 MB into 70 MB of space), and
            // pieces of photos this phone is waiting for before strangers' leftovers.
            // (The margin only decides when a full phone counts as not full again — lowOnSpace;
            // from then on put() takes pieces down to the reserve, so that is the room there is.)
            val room = if (free <= 0L) Int.MAX_VALUE else ((free - reserveBytes) / MAX_PIECE_BYTES).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
            val ready = waiting.filter { (id, t) -> t.why == Why.NO_ROOM || knownFile(id) }
                .sortedBy { (id, _) -> if (knownFile(id)) 0 else 1 }
                .take(room)
            if (ready.isEmpty()) return
            synchronized(this) {
                // Only if nothing changed meanwhile (stored, or turned away again).
                for ((id, t) in ready) if (tombs[id] === t) refillLocked(id)
            }
        }

        private fun forgetLocked(id: String, e: Entry) {
            index.remove(id)
            total -= e.size
            if (e.orphan) {
                val left = (orphans[e.fid] ?: 0) - 1
                if (orphans.containsKey(e.fid)) { orphanCount--; if (left <= 0) orphans.remove(e.fid) else orphans[e.fid] = left }
            }
        }

        /** Files are deleted off the caller's thread — but never one that has since been stored again. */
        private fun deleteLater(ids: List<String>) {
            background.execute {
                for (id in ids) synchronized(this) { if (id !in index) fileOf(id)?.delete() }
            }
        }

        @Synchronized override fun has(id: String): Boolean = id in index
        @Synchronized override fun ids(): List<String> = index.keys.toList()

        /** Pieces held, plus pieces let go of or turned away that friends may still carry. */
        @Synchronized override fun advertised(): List<String> =
            ArrayList<String>(index.size + tombs.size).apply { addAll(index.keys); addAll(tombs.keys) }

        @Synchronized override fun takeReleased(): List<String> =
            if (released.isEmpty()) emptyList() else released.toList().also { released.clear() }

        /** Read outside the lock: assembly reading 150 pieces must not stall the radio on the main thread. */
        override fun get(id: String): Envelope? {
            if (!has(id)) return null
            val f = fileOf(id) ?: return null
            return try {
                Envelope(JSONObject(f.readText())).takeIf { it.id == id } ?: throw IllegalStateException("piece under the wrong name")
            } catch (e: FileNotFoundException) {
                // Expired or dropped a moment ago (then it is no longer indexed) — or deleted behind
                // our back: stop claiming it, and let a friend hand it over again.
                synchronized(this) { if (!f.exists()) index[id]?.let { forgetLocked(id, it); refillLocked(id) } }
                null
            } catch (e: Exception) {
                // A damaged piece is worse than a missing one: forget it, and let a friend refill it.
                log("damaged piece dropped", e)
                forget(id)
                null
            }
        }

        /** A piece that didn't open (see [ChunkStore.forget]): gone from the disk, and handed back so a friend refills it. */
        override fun forget(id: String) {
            val had = synchronized(this) { index[id]?.let { forgetLocked(id, it); refillLocked(id); true } ?: false }
            if (had) deleteLater(listOf(id))
        }

        override fun expire(before: Long) {
            val now = clock()
            val gone = synchronized(this) {
                val old = index.entries.filter { it.value.at < before }.map { it.key to it.value }
                for ((id, e) in old) { forgetLocked(id, e); buryLocked(id, Why.DROPPED, e.at, now) }
                // Every friend is done with these by now.
                for ((id, t) in tombs.entries.filter { it.value.until < now }.map { it.key to it.value }) unlistLocked(id, t)
                old.map { it.first }
            }
            if (gone.isNotEmpty()) deleteLater(gone)
            letInAgain(now)
        }

        /** Bytes of pieces on disk (for tests and diagnostics). */
        @Synchronized fun bytesUsed(): Long = total

        private companion object {
            /** A real piece is ~19 KB of JSON; anything far bigger is not one. */
            const val MAX_PIECE_BYTES = 64 * 1024
            const val SPACE_CHECK_MS = 5_000L
            /**
             * Pieces listed but not kept, at most: a full friend's worth (~16,000 pieces at the 300 MB
             * budget), while the inventory — carried messages (6,000) and the ones let go of (6,000) +
             * pieces held (~16,500) + these — stays inside what a friend reads (Router.MAX_INV_PARTS
             * parts, Router.MAX_INV_IDS ids; pinned in PayloadSizeTest).
             */
            const val MAX_TOMBS = 20_000
            /** Free space needed above the reserve before a full phone takes pieces again. */
            const val RELEASE_MARGIN = 64L * 1024 * 1024
        }
    }

    /**
     * The piece store of the group going on the radio — and of no other: it creates the folder
     * and reopens a deleted group's space; the router built on it is wired in once it exists
     * ([DiskChunkStore.servedBy]). A group that was left must never get one (reading its chat
     * needs no pieces; see Core.archive).
     */
    fun chunkStore(ctx: Context, fp: String): DiskChunkStore {
        closed.remove(fp)   // joining a deleted group's code again reopens its blob space
        dropLegacyPieces(ctx, fp)
        val store = DiskChunkStore(piecesDir(ctx, fp))
        sweep(ctx, fp)
        return store
    }

    /**
     * Once per app start and per group start, in the background: drop camera/share leftovers, what
     * a kill left half-done (half-written files, folders of deleted or left groups on their way
     * out), and pieces of groups that are not on the radio and past their 48 h anyway (no router
     * is around to expire those). [activeFp] is the group whose router looks after its own pieces.
     * Kept files are never touched — see [BlobRules.sweep].
     */
    fun sweep(ctx: Context, activeFp: String?) {
        val app = ctx.applicationContext
        housekeeping.execute {
            try {
                sweepCache(app)
                BlobRules.sweep(File(app.filesDir, "blobs"), System.currentTimeMillis(), activeFp) { it in closed }
            } catch (e: Exception) { Log.w(TAG, "tidy failed", e) }
        }
    }

    /**
     * Delete camera shots and share copies older than [maxAgeMs]. The chat deletes each camera shot
     * after sending it; this catches the ones a cancelled caption or a killed app left behind.
     */
    fun sweepCache(ctx: Context, maxAgeMs: Long = DAY_MS) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        for (sub in listOf("camera", "share")) {
            File(ctx.cacheDir, sub).walkBottomUp().forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) f.delete()
                else if (f.isDirectory && f.name != sub && f.list().isNullOrEmpty()) f.delete()
            }
        }
    }

    // ------------------------------------------------------------------ assembled files

    private fun safeName(name: String): String = BlobRules.safeName(name)

    /**
     * Where a message's file lives on this phone. A file id that isn't one we would ever make maps
     * to a name nothing ever writes, so a crafted message can never point outside the group's folder.
     */
    fun fileFor(ctx: Context, fp: String, att: Attachment): File =
        if (Attachment.validFid(att.fid)) File(filesDir(ctx, fp), "${att.fid}-${safeName(att.name)}")
        else File(filesDir(ctx, fp), "invalid-${safeName(att.name)}")

    /**
     * Glue the pieces of a message's attachment into a real file ([putTogether] says how, and what
     * the outcome means). [from] is the message's sender; [att] is a copy the caller made on the
     * main thread, where the message's own may be marked meanwhile. Blocks until done (callers are
     * background threads); every assembly runs on one worker, and a second request for the same
     * file simply waits for the first.
     */
    fun assemble(ctx: Context, fp: String, chunks: ChunkStore, from: String, att: Attachment): BlobRules.Assembly {
        if (fp in closed || !Attachment.validFid(att.fid)) return BlobRules.Assembly.Waiting()
        val out = fileFor(ctx, fp, att)
        if (Thread.currentThread().name == ASSEMBLER_THREAD) return putTogether(out, chunks, from, att, gone = { fp in closed })
        val key = "$fp/${att.fid}"
        val job = synchronized(assembling) {
            assembling.getOrPut(key) {
                assembler.submit(Callable {
                    try { putTogether(out, chunks, from, att, gone = { fp in closed }) } finally { synchronized(assembling) { assembling.remove(key) } }
                })
            }
        }
        return try { job.get() } catch (e: Exception) { Log.w(TAG, "assemble failed", e); BlobRules.Assembly.Failed }
    }

    /**
     * The work of [assemble], on its thread (and in the tests): [out] is the file's place. Every
     * piece is checked on the way — it must be the file's sender's ([from]) and open under the
     * file's own key — and so is the whole: its size and checksum must be what the message says.
     * The bytes go to a temporary file that is renamed into place only once all of that holds, so
     * no byte that wasn't checked is ever in the final file.
     *
     * A piece that fails is let go of for a friend to hand over again — once ([BlobRules.onBadPieces]).
     * Failing again, or a whole that isn't what its message says, means the file never will open:
     * its pieces stay (friends still pass them on) and the caller marks the message so. A file
     * already in [out]'s place counts only for a message whose sender made that file id; a message
     * from before 2.4 (no key) has no pieces to come and is left alone. [gone]: the group was
     * deleted meanwhile, and nothing may be written for it.
     */
    fun putTogether(out: File, chunks: ChunkStore, from: String, att: Attachment, gone: () -> Boolean = { false },
                    log: (String, Throwable?) -> Unit = { msg, t -> Log.w(TAG, msg, t) }): BlobRules.Assembly {
        if (att.failed) return BlobRules.Assembly.Bad
        val key = att.key
        if (key == null || !Attachment.ownedBy(att.fid, from)) return BlobRules.Assembly.Waiting()
        if (out.exists()) return BlobRules.Assembly.Ready   // finished while this one waited, or my own copy
        for (i in 0 until att.chunks) if (!chunks.has(Envelope.chunkId(att.fid, i))) return BlobRules.Assembly.Waiting()
        if (gone()) return BlobRules.Assembly.Waiting()
        val dir = out.parentFile ?: return BlobRules.Assembly.Failed
        var tmp: File? = null
        try {
            if (!dir.isDirectory && !dir.mkdirs()) return BlobRules.Assembly.Failed
            val t = File.createTempFile(BlobRules.tempPrefix(out.name), ".part", dir).also { tmp = it }
            val bad = ArrayList<Int>()
            val sum = MessageDigest.getInstance("SHA-256")
            var written = 0L
            t.outputStream().buffered().use { os ->
                for (i in 0 until att.chunks) {
                    // Gone a moment ago (expired — or damaged, and handed back by the store): its next arrival tries again.
                    val env = chunks.get(Envelope.chunkId(att.fid, i)) ?: return BlobRules.Assembly.Waiting()
                    val bytes = if (env.origin == from) Crypto.openPiece(key, i, env.sealed) else null
                    if (bytes == null) { bad.add(i); continue }
                    // After a bad piece nothing more is written, but the rest are still checked: every
                    // bad one is fetched again in one go, not one per round.
                    if (bad.isNotEmpty()) continue
                    written += bytes.size
                    if (written > att.size) continue   // more than the file is: said below, never written
                    sum.update(bytes)
                    os.write(bytes)
                }
            }
            if (bad.isNotEmpty()) {
                val verdict = BlobRules.onBadPieces(bad, att.refilled)
                if (verdict is BlobRules.Assembly.Waiting) {
                    log("${bad.size} piece(s) of ${att.fid} didn't open; fetching them again", null)
                    for (i in verdict.refilled) chunks.forget(Envelope.chunkId(att.fid, i))
                } else log("a piece of ${att.fid} didn't open again: it can't be opened", null)
                return verdict
            }
            if (written != att.size || Crypto.hex(sum.digest()) != att.sha) {
                log("file ${att.fid} isn't what its message says: it can't be opened", null)
                return BlobRules.Assembly.Bad
            }
            if (gone()) return BlobRules.Assembly.Waiting()
            // rename() replaces in one step. An existing file is never deleted first: if this rename
            // fails, whatever is already there is the finished file, and that is the honest answer.
            if (t.renameTo(out) || out.exists()) return BlobRules.Assembly.Ready
            log("couldn't move ${att.fid} into place", null)
            return BlobRules.Assembly.Failed
        } catch (e: Exception) {
            log("assemble failed", e)
            return if (out.exists()) BlobRules.Assembly.Ready else BlobRules.Assembly.Failed
        } finally {
            tmp?.let { if (it.exists()) it.delete() }
        }
    }

    /** My own send: the bytes are already here — write the file directly so it shows instantly. Off the main thread. */
    fun saveOwn(ctx: Context, fp: String, att: Attachment, bytes: ByteArray): Boolean {
        if (fp in closed || !Attachment.validFid(att.fid)) return false
        val out = fileFor(ctx, fp, att)
        if (out.exists()) return true
        val dir = out.parentFile ?: return false
        var tmp: File? = null
        return try {
            if (!dir.isDirectory && !dir.mkdirs()) return false
            val t = File.createTempFile(BlobRules.tempPrefix(out.name), ".part", dir).also { tmp = it }
            t.writeBytes(bytes)
            t.renameTo(out)
            out.exists()
        } catch (e: Exception) {
            Log.w(TAG, "saveOwn failed", e); out.exists()
        } finally {
            tmp?.let { if (it.exists()) it.delete() }
        }
    }

    /** A file made ready to send: its own new key, the checksum and size of its bytes, and its pieces sealed under that key. */
    class Sealed(val key: ByteArray, val sha: String, val pieces: List<String>, val size: Long) {
        /**
         * The attachment of a file of mine sent again ("Send again", or right after the upgrade):
         * [old]'s name, kind, picture size, preview and length — a photo isn't shrunk a second
         * time — under the new file id [fid] and this new key.
         */
        fun again(old: Attachment, fid: String): Attachment =
            Attachment.make(fid, old.name, old.mime, size, pieces.size, old.width, old.height, old.thumb, old.dur, key = key, sha = sha)
    }

    /**
     * Seal [bytes] to send, under a key of their own: every send gets a new one, a "Send again"
     * too. The key and the checksum travel only inside the sealed message. Slow: not on the main thread.
     */
    fun seal(bytes: ByteArray): Sealed {
        val key = Crypto.randomBytes(32)
        return Sealed(key, Crypto.sha256Hex(bytes), chunkify(bytes, key), bytes.size.toLong())
    }

    /** Cut bytes into pieces sized for the radio, each sealed under the file's own [key] (see Crypto.sealPiece). Slow: not on the main thread. */
    fun chunkify(bytes: ByteArray, key: ByteArray): List<String> {
        val out = ArrayList<String>((bytes.size + Router.CHUNK_RAW - 1) / Router.CHUNK_RAW)
        var i = 0
        while (i < bytes.size) {
            val end = minOf(bytes.size, i + Router.CHUNK_RAW)
            out.add(Crypto.sealPiece(key, out.size, bytes.copyOfRange(i, end)))
            i = end
        }
        return out
    }

    // ------------------------------------------------------------------ images

    class Prepared(val bytes: ByteArray, val name: String, val mime: String, val width: Int, val height: Int, val thumbB64: String)

    /**
     * Shrink a picked photo until it hops well: longest side ≤1280, JPEG, ≤ MAX_IMAGE_BYTES.
     * Also bakes a tiny thumbnail into the message itself, so receivers see something at once.
     * Never touches the picked file itself (a camera shot belongs to the chat screen, which
     * deletes it). A phone short on memory gets a smaller photo rather than none.
     */
    fun prepareImage(ctx: Context, uri: Uri): Prepared? {
        for (maxDim in intArrayOf(1280, 960, 720)) {
            try {
                return prepareAt(ctx, uri, maxDim)
            } catch (oom: OutOfMemoryError) {
                Log.w(TAG, "out of memory shrinking a photo to $maxDim px; trying smaller")
            } catch (e: Exception) {
                Log.w(TAG, "prepareImage failed", e); return null
            }
        }
        return null
    }

    private fun prepareAt(ctx: Context, uri: Uri, maxDim: Int): Prepared? {
        var bmp = decodeScaled(ctx, uri, maxDim) ?: return null
        try {
            // JPEG has no transparency: a logo or sticker would turn black. Lay it on white first.
            bmp = swap(bmp, flatten(bmp))
            var quality = 78
            var bytes = jpeg(bmp, quality)
            while (bytes.size > MAX_IMAGE_BYTES && quality > 40) { quality -= 12; bytes = jpeg(bmp, quality) }
            if (bytes.size > MAX_IMAGE_BYTES && maxDim > 960) { bmp = swap(bmp, scaleDown(bmp, 960)); bytes = jpeg(bmp, 60) }
            if (bytes.size > MAX_IMAGE_BYTES) { bmp = swap(bmp, scaleDown(bmp, 720)); bytes = jpeg(bmp, 55) }
            val thumb = scaleDown(bmp, THUMB_DIM)
            val thumbB64 = Base64.encodeToString(jpeg(thumb, 45), Base64.NO_WRAP)
            if (thumb !== bmp) thumb.recycle()
            return Prepared(bytes, "photo-${System.currentTimeMillis() / 1000}.jpg", "image/jpeg", bmp.width, bmp.height, thumbB64)
        } finally {
            bmp.recycle()
        }
    }

    /** Use [next] from now on and free [old] — unless they are the same bitmap. */
    private fun swap(old: Bitmap, next: Bitmap): Bitmap { if (next !== old) old.recycle(); return next }

    private fun flatten(b: Bitmap): Bitmap {
        if (!b.hasAlpha() && b.config == Bitmap.Config.ARGB_8888) return b
        // ARGB_8888, not RGB_565: a sky gradient must not band before the JPEG even starts.
        val flat = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
        Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
        return flat
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    private fun scaleDown(b: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= maxDim) return b
        val f = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(b, maxOf(1, (b.width * f).toInt()), maxOf(1, (b.height * f).toInt()), true)
    }

    /** Decode with subsampling (no full-size bitmap in memory) and honour EXIF rotation and mirroring. */
    private fun decodeScaled(ctx: Context, uri: Uri, maxDim: Int): Bitmap? {
        val cr = ctx.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // decodeStream returns null by design with inJustDecodeBounds — only the stream can be the failure here
        val boundsStream = cr.openInputStream(uri) ?: return null
        boundsStream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxDim) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        bmp = swap(bmp, scaleDown(bmp, maxDim))
        // The JPEG made from this carries no EXIF, so the turn goes into the pixels themselves.
        val upright = try { cr.openInputStream(uri)?.use { uprightMatrix(ExifInterface(it)) } } catch (e: Exception) { null }
        if (upright != null) bmp = swap(bmp, Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, upright, true))
        return bmp
    }

    /**
     * The turn (and mirror) a camera wrote into a picture's EXIF instead of turning its pixels —
     * BitmapFactory ignores it, so a portrait shot would lie on its side. Null when it is upright.
     * Mirror first, then turn: that is how every EXIF orientation (incl. transpose) composes.
     */
    fun uprightMatrix(exif: ExifInterface): Matrix? {
        val degrees = exif.rotationDegrees
        val flipped = exif.isFlipped
        if (degrees == 0 && !flipped) return null
        return Matrix().apply { if (flipped) postScale(-1f, 1f); if (degrees != 0) postRotate(degrees.toFloat()) }
    }

    // ------------------------------------------------------------------ arbitrary files

    class PickedFile(val bytes: ByteArray, val name: String, val mime: String)

    /** Why a picked file can or can't be sent — each gets its own honest message. */
    sealed class Picked {
        class Ok(val file: PickedFile) : Picked()
        class TooBig(val name: String, val size: Long) : Picked()
        object Empty : Picked()
        /** Couldn't be read: often a cloud file that isn't downloaded to this phone. */
        object Unreadable : Picked()
    }

    /** Read a picked document, without ever holding more than the mesh's limit in memory. */
    fun readPicked(ctx: Context, uri: Uri): Picked {
        return try {
            val cr = ctx.contentResolver
            var name = "file"
            var declared = -1L
            cr.query(uri, null, null, null, null)?.use { c ->
                val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (i >= 0) name = c.getString(i) ?: name
                    if (s >= 0 && !c.isNull(s)) declared = c.getLong(s)
                }
            }
            // The name rides every copy of the message: keep it readable but bounded.
            name = BlobRules.displayName(name)
            if (declared > Router.MAX_FILE) return Picked.TooBig(name, declared)
            val bytes = cr.openInputStream(uri)?.use { ins ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > Router.MAX_FILE) return Picked.TooBig(name, maxOf(declared, out.size().toLong()))
                }
                out.toByteArray()
            } ?: return Picked.Unreadable
            if (bytes.isEmpty()) return Picked.Empty
            Picked.Ok(PickedFile(bytes, name, cr.getType(uri) ?: "application/octet-stream"))
        } catch (e: Exception) { Log.w(TAG, "readPicked failed", e); Picked.Unreadable }
    }

    // ------------------------------------------------------------------ keeping a file on the phone

    /** Where a save landed: the system's handle for it, and the folder to tell the person about. */
    class Saved(val uri: Uri, val folder: String)

    /**
     * Keep a received photo, voice note or file outside Hopline (Pictures/Hopline or Downloads),
     * so it is the person's own copy: deleting the group (or Hopline) doesn't take it. Returns
     * where it went, or null if it couldn't be saved.
     * (On Android 8–9 the caller offers the system "Save as…" picker instead — see [copyToUri].)
     */
    fun saveToPhone(ctx: Context, file: File, name: String, mime: String): Uri? = save(ctx, file, name, mime)?.uri

    /** [saveToPhone], also saying which folder it went to. Slow: call it off the main thread. */
    fun save(ctx: Context, file: File, name: String, mime: String): Saved? {
        if (Build.VERSION.SDK_INT < 29 || !file.isFile) return null
        val clean = BlobRules.displayName(name, fallback = file.name)
        val type = normalisedMime(mime)
        // A picture goes where the gallery looks; if the gallery won't take this kind of picture
        // (SVG, TIFF on some phones), it still lands in Downloads rather than nowhere.
        if (type.startsWith("image/")) insertCopy(ctx, file, clean, type, image = true)?.let { return it }
        return insertCopy(ctx, file, clean, type, image = false)
    }

    @RequiresApi(29)
    private fun insertCopy(ctx: Context, file: File, name: String, mime: String, image: Boolean): Saved? {
        val cr = ctx.contentResolver
        val folder = (if (image) Environment.DIRECTORY_PICTURES else Environment.DIRECTORY_DOWNLOADS) + "/Hopline"
        val collection = if (image) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                         else MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val unique = BlobRules.uniqueName(name) { candidate -> nameTaken(ctx, collection, folder, candidate) }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, unique)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "$folder/")
            // Hidden from other apps until every byte is there: no half photo in the gallery.
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = try { cr.insert(collection, values) } catch (e: Exception) { Log.w(TAG, "save: insert refused ($mime)", e); null } ?: return null
        return try {
            val copied = cr.openOutputStream(uri, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: -1L
            if (copied != file.length()) throw IllegalStateException("copied $copied of ${file.length()} bytes")
            cr.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            Saved(uri, folder)
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            try { cr.delete(uri, null, null) } catch (ignored: Exception) { }
            null
        }
    }

    /** Is [name] already one of our files in [folder]? (The system adds " (1)" for anyone else's.) */
    @RequiresApi(29)
    private fun nameTaken(ctx: Context, collection: Uri, folder: String, name: String): Boolean = try {
        ctx.contentResolver.query(collection, arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
            arrayOf("$folder/", name), null)?.use { it.count > 0 } ?: false
    } catch (e: Exception) { false }

    private fun normalisedMime(mime: String): String {
        val m = mime.trim().lowercase()
        return when {
            m == "image/jpg" -> "image/jpeg"
            m.isEmpty() || !m.contains('/') -> "application/octet-stream"
            else -> m
        }
    }

    /**
     * Android 8–9: copy a file into the place the person picked in the system "Save as…" screen
     * (ACTION_CREATE_DOCUMENT). Slow: call it off the main thread. False if anything went wrong —
     * the half-written document is then removed rather than left looking like a good copy.
     */
    fun copyToUri(ctx: Context, file: File, target: Uri): Boolean {
        val cr = ctx.contentResolver
        return try {
            // Plain "w": the document was just created empty, and not every provider knows "wt".
            val copied = cr.openOutputStream(target, "w")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: -1L
            if (copied != file.length()) throw IllegalStateException("copied $copied of ${file.length()} bytes")
            true
        } catch (e: Exception) {
            Log.w(TAG, "copy to the chosen place failed", e)
            try { android.provider.DocumentsContract.deleteDocument(cr, target) } catch (ignored: Exception) { }
            false
        }
    }

    /**
     * A copy of a received file under its real name, for handing to another app (share, open
     * with): the app on the other side shows "Trek plan.pdf", not our internal "k3j9…-Trek_plan.pdf".
     * Lives in cache/share and is swept after a day. Slow: call it off the main thread.
     */
    fun shareCopy(ctx: Context, file: File, name: String): File? = try {
        // The mesh service can keep the app alive for days, so old copies are swept here too.
        sweepCache(ctx)
        val dir = File(File(ctx.cacheDir, "share"), Crypto.randomId(10)).apply { mkdirs() }
        val out = File(dir, BlobRules.displayName(name, fallback = file.name))
        if (!BlobRules.isInside(dir, out)) null
        else { file.copyTo(out, overwrite = true); out }
    } catch (e: Exception) { Log.w(TAG, "share copy failed", e); null }

    fun prettySize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }
}
