package plymeld.mesh

/**
 * Something worked out once per soup and asked for again and again (a posed model sliced at a few heights per candidate,
 * its overhangs surveyed region by region), by the soup's identity, its total bounded in bytes: the least recently used
 * dropped first. A soup's coordinates are not changed once it is made, so a value stays true while it is held. Holding
 * every live soup's value was more than the heap on a model of millions of triangles, with the pose search's candidates
 * alive at once; a dropped value is worked out again when asked, so the bound changes the time, never the result.
 *
 * Keys are held weakly: a soup no one else holds is not kept alive here, and its value goes with the next drop.
 */
class SoupCache<V : Any>(
    /** The most bytes the values may take together; a value larger than this is not kept at all. */
    private val maxBytes: Long,
    /** A value's size in bytes, for the bound. */
    private val bytesOf: (V) -> Long,
) {
    private class Key(soup: TriangleSoup) : java.lang.ref.WeakReference<TriangleSoup>(soup) {
        private val hash = System.identityHashCode(soup)
        override fun hashCode() = hash
        override fun equals(other: Any?): Boolean = other is Key && (this === other || (get() != null && get() === other.get()))
    }
    private class Held<V>(val value: V, val bytes: Long)
    private val map = LinkedHashMap<Key, Held<V>>(16, 0.75f, true)
    private var bytes = 0L

    /** [soup]'s value, worked out by [compute] when not held (outside the lock: two threads may both work it out). */
    fun get(soup: TriangleSoup, compute: () -> V): V {
        val key = Key(soup)
        synchronized(map) { map[key]?.let { return it.value } }
        val v = compute()
        val b = bytesOf(v)
        if (b > maxBytes) return v
        synchronized(map) {
            map.put(key, Held(v, b))?.let { bytes -= it.bytes }
            bytes += b
            val it = map.entries.iterator()
            while (bytes > maxBytes && it.hasNext()) { val e = it.next(); if (e.key === key) continue; bytes -= e.value.bytes; it.remove() }
            // values of soups no one holds any more go first, whatever their age
            map.entries.removeIf { e -> (e.key.get() == null).also { gone -> if (gone) bytes -= e.value.bytes } }
        }
        return v
    }

    companion object {
        /** The bound plymeld gives such a cache: an eighth of the heap, so a few of them stay well inside it. */
        fun heapShare(): Long = Runtime.getRuntime().maxMemory() / 8
    }
}
