package app.hopline.service

import app.hopline.mesh.Attachment
import app.hopline.mesh.Envelope
import app.hopline.mesh.Router
import java.io.File

/**
 * The plain rules behind file storage: which names may touch the disk, how long a carried piece
 * lives, and what a saved file is called. No Android in here, so the JVM tests can pin them down.
 */
object BlobRules {
    /** A piece of a file is carried for 48 h from the sender's stamp… */
    const val KEEP_MS = Router.CARRY_MS
    /** …but never less than a day here, so a sender whose clock runs days behind still gets through. */
    const val MIN_KEEP_MS = 24 * 3600_000L
    /**
     * How much longer than this phone a friend may still carry a piece: one that got it more than a
     * day after it was sent has its 48 h start up to a day after the sender's stamp.
     */
    const val CARRIED_ON_MS = KEEP_MS - MIN_KEEP_MS

    /**
     * The moment a piece's 48 h started, as THIS phone reckons it (it is stored as the file's
     * timestamp; the piece is dropped 48 h after it). The same rule the router uses for carried
     * messages: the sender's stamp, so every phone that got the piece within a day of it being sent
     * drops it at the same moment (a phone that dropped it first would be offered it again by one
     * still holding it, sync after sync); never later than now, so a sender whose clock runs ahead
     * can't make it immortal; never more than a day before now (see [MIN_KEEP_MS]).
     */
    fun anchor(senderTs: Long, now: Long): Long = maxOf(minOf(senderTs, now), now - (KEEP_MS - MIN_KEEP_MS))

    /**
     * Until when a piece this phone no longer keeps is still listed as "have it" (see
     * DiskChunkStore): as long as any friend may still carry it, so none keeps re-sending it here.
     */
    fun listUntil(anchor: Long, now: Long): Long = maxOf(anchor + KEEP_MS, now) + CARRIED_ON_MS

    /**
     * The file id inside a chunk id, or null when the id is not exactly f.<fid>.<index> with a sane
     * fid and an index the mesh can produce. Chunk ids become file names, so nothing else may pass.
     */
    fun chunkFid(id: String): String? {
        if (id.length > 64 || !id.startsWith("f.") || id.contains("..") || id.contains('/') || id.contains('\\')) return null
        val dot = id.lastIndexOf('.')
        if (dot <= 2) return null
        val fid = id.substring(2, dot)
        val index = id.substring(dot + 1).toIntOrNull() ?: return null
        if (!Attachment.validFid(fid) || index !in 0 until Router.MAX_CHUNKS) return null
        // Exactly what the sender builds: no "+1", no "007".
        return if (Envelope.chunkId(fid, index) == id) fid else null
    }

    /** True when [f] resolves to somewhere strictly inside [dir] — symlinks and ".." included. */
    fun isInside(dir: File, f: File): Boolean = try {
        f.canonicalPath.startsWith(dir.canonicalPath + File.separator)
    } catch (e: Exception) { false }

    /**
     * The on-disk spelling of a received file's name. Never change this: files assembled by older
     * versions are found by this exact name.
     */
    fun safeName(name: String): String = name.replace(UNSAFE, "_").take(60).ifEmpty { "file" }

    private val UNSAFE = Regex("[^A-Za-z0-9._-]")

    // ---------------------------------------------------------------- names no kept file can have

    /**
     * What a file is called while it is still being written. A finished file is <fid>-<safe name>,
     * and neither a file id nor a safe name can hold a '~' — so nothing a sender calls their file
     * can ever look like one of these. (Half-written files used to be told apart by ending in
     * ".part", and a received file really named "backup.part" was swept away with them.)
     */
    const val TEMP_PREFIX = "~"

    fun tempPrefix(finalName: String): String = TEMP_PREFIX + finalName.take(40).padEnd(2, '_')

    fun isTemp(name: String): Boolean = name.startsWith(TEMP_PREFIX)

    /** A half-written file older than this was left by a kill, not by work still going on. */
    const val TEMP_KEEP_MS = 3600_000L

    private const val DEAD_CHUNKS = "chunks.dead-"
    private const val GONE_GROUP = ".gone-"

    /** What a left group's folder of carried pieces is renamed to, on its way to being deleted. */
    fun deadChunksName(now: Long): String = DEAD_CHUNKS + now
    fun isDeadChunks(name: String): Boolean = name.startsWith(DEAD_CHUNKS)

    /** What a deleted group's whole folder is renamed to. The dot can't start a fingerprint, so it is never taken for a group. */
    fun goneGroupName(fp: String, now: Long): String = "$GONE_GROUP$fp-$now"
    fun isGoneGroup(name: String): Boolean = name.startsWith(GONE_GROUP)

    /**
     * Take [dir] out of use in one step, by renaming it to [asideName] beside it. Deleting a folder
     * of thousands of pieces takes seconds; if the group is joined again meanwhile, whatever it
     * creates under the old name is a new, empty folder that the slow delete can never reach.
     * Returns the renamed folder, to delete at leisure — or null when there was nothing to move,
     * or the rename failed and [dir] is still where it was.
     */
    fun moveAside(dir: File, asideName: String): File? {
        if (!dir.exists()) return null
        val aside = File(dir.parentFile, asideName)
        return if (dir.renameTo(aside)) aside else null
    }

    /**
     * Housekeeping for every group's folder under [root] (files/blobs): what a kill left behind
     * goes — deleted groups' folders, left groups' piece folders, half-written files — and, for
     * the groups no router is looking after ([activeFp] is the one that has one), pieces past their
     * 48 h. [skip] names folders to leave entirely alone.
     *
     * The one thing this never does is touch a kept file: in a group's files/ only [isTemp] names
     * are ever deleted, whether the group is on the radio, paused, or left years ago.
     */
    fun sweep(root: File, now: Long, activeFp: String?, skip: (String) -> Boolean = { false }) {
        root.listFiles()?.forEach { group ->
            if (isGoneGroup(group.name)) { group.deleteRecursively(); return@forEach }
            if (!group.isDirectory || skip(group.name)) return@forEach
            group.listFiles()?.forEach { if (isDeadChunks(it.name)) it.deleteRecursively() }
            File(group, "files").listFiles()?.forEach { f ->
                if (isTemp(f.name) && now - f.lastModified() > TEMP_KEEP_MS) f.delete()
            }
            if (group.name != activeFp) File(group, "chunks").listFiles()?.forEach { f ->
                if (now - f.lastModified() > KEEP_MS) f.delete()
            }
        }
    }

    /**
     * A name fit to show in another app or a Downloads folder: the sender's own words (any
     * script), minus path separators, characters file systems refuse, and runaway length. The
     * extension survives trimming, so the file still opens in the right app.
     */
    fun displayName(raw: String, fallback: String = "file"): String {
        val cleaned = buildString {
            for (ch in raw) append(if (ch < ' ' || ch == '\u007F' || ch in FORBIDDEN) '_' else ch)
        }.trim().trim('.', ' ')
        if (cleaned.isEmpty()) return fallback
        if (cleaned.length <= MAX_DISPLAY) return cleaned
        val dot = cleaned.lastIndexOf('.')
        val ext = if (dot > 0 && cleaned.length - dot in 2..10) cleaned.substring(dot) else ""
        return cleaned.substring(0, MAX_DISPLAY - ext.length).trimEnd('.', ' ') + ext
    }

    private const val MAX_DISPLAY = 100
    private const val FORBIDDEN = "/\\:*?\"<>|"

    /** "photo.jpg", then "photo (1).jpg", "photo (2).jpg"… — the first one [taken] says is free. */
    fun uniqueName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        for (i in 1..999) {
            val candidate = "$base ($i)$ext"
            if (!taken(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }
}
