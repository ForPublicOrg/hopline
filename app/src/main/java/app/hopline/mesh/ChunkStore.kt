package app.hopline.mesh

/**
 * Where file pieces live while they are carried for the group. Text envelopes stay in memory and
 * in the router snapshot, but file chunks are big (a photo is ~25 of them), so they get their own
 * store: on disk in the app, a plain map in tests. The router only ever needs the ids for the
 * link-up inventory swap and the envelopes back for gap-filling and reassembly.
 */
interface ChunkStore {
    /** Store one chunk envelope. Returns false if it could not be kept (e.g. storage full). */
    fun put(env: Envelope): Boolean
    fun has(id: String): Boolean
    fun ids(): List<String>
    fun get(id: String): Envelope?
    /** Drop chunks older than `before` (their envelope ts). Assembled files are kept elsewhere. */
    fun expire(before: Long)
}

class MemoryChunkStore : ChunkStore {
    private val map = LinkedHashMap<String, Envelope>()
    override fun put(env: Envelope): Boolean { map[env.id] = env; return true }
    override fun has(id: String): Boolean = map.containsKey(id)
    override fun ids(): List<String> = map.keys.toList()
    override fun get(id: String): Envelope? = map[id]
    override fun expire(before: Long) {
        val it = map.entries.iterator()
        while (it.hasNext()) if (it.next().value.ts < before) it.remove()
    }
}
