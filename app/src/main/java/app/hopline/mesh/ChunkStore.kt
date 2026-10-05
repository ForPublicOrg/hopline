package app.hopline.mesh

/**
 * Where file pieces live while they are carried for the group. Text envelopes stay in memory and
 * in the router snapshot, but file chunks are big (a photo is ~25 of them), so they get their own
 * store: on disk in the app, a plain map in tests. The router only ever needs the ids for the
 * link-up inventory swap and the envelopes back for gap-filling and reassembly. A piece is kept
 * exactly as it arrived — sealed under its file's key, which only the file's message holds.
 */
interface ChunkStore {
    /**
     * Store one chunk envelope. Returns false if it could not be kept (e.g. storage full). True
     * does not always mean it is held: a piece turned away on purpose is "accepted" so nobody keeps
     * offering it — [has] says what is really here.
     */
    fun put(env: Envelope): Boolean
    fun has(id: String): Boolean
    fun ids(): List<String>
    fun get(id: String): Envelope?
    /** Drop chunks older than `before` (their envelope ts). Assembled files are kept elsewhere. */
    fun expire(before: Long)

    /**
     * What the link-up inventory lists: the pieces held, plus pieces this phone let go of while
     * friends may still carry them (expired here first, evicted for room, turned away while the
     * phone was nearly full). Listing those stops a neighbour re-sending them every sync only for
     * them to be thrown away. [has]/[get]/[ids] stay limited to pieces actually held.
     */
    fun advertised(): List<String> = ids()

    /**
     * Pieces turned away earlier that this phone can take now (space came back, or a stray piece's
     * message arrived). The router forgets it saw them, so the next sync brings them again.
     * Each id is handed out once.
     */
    fun takeReleased(): List<String> = emptyList()

    /**
     * Let go of a piece that turned out bad when its file was put together (it doesn't open, or
     * isn't its file's sender's): no longer held nor listed, and handed back through
     * [takeReleased], so the next sync brings a friend's copy. The disk store takes this from
     * any thread.
     */
    fun forget(id: String) {}
}

class MemoryChunkStore : ChunkStore {
    private val map = LinkedHashMap<String, Envelope>()
    private val released = LinkedHashSet<String>()
    override fun put(env: Envelope): Boolean { map[env.id] = env; return true }
    override fun forget(id: String) { if (map.remove(id) != null) released.add(id) }
    override fun takeReleased(): List<String> = if (released.isEmpty()) emptyList() else released.toList().also { released.clear() }
    override fun has(id: String): Boolean = map.containsKey(id)
    override fun ids(): List<String> = map.keys.toList()
    override fun get(id: String): Envelope? = map[id]
    override fun expire(before: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) if (it.next().value.ts < before) it.remove()
    }
}
