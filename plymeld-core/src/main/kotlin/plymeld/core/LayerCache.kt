package plymeld.core

import plymeld.geom.Polygon

/**
 * Per-layer results kept across slices (incremental re-slicing at the layer): a change in one band of layers recomputes
 * only those layers; the rest come back from here. A stage's per-layer work is a pure function of what it reads: the
 * layer's own polygons, its neighbors' where the rule looks at them, and the settings. A [Key] holds exactly those: the
 * settings' recorded values and the scalars as text, and every polygon array as it is. A hit is an exact match (the
 * arrays compared whole, never only hashed), so a stale result is impossible; an input left out of a key only costs the
 * hit. Bounded by bytes (`plymeld.layerCache.mb`, default 768), the least recently used results dropped first.
 * Process-wide: a window's every slice shares it; the CLI's one run gains nothing from it.
 */
object LayerCache {
    class Key(val text: String, val arrays: Array<LongArray>) {
        private val h: Int = run { var x = text.hashCode(); for (a in arrays) x = x * 31 + a.contentHashCode(); x }
        override fun hashCode() = h
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Key || other.h != h || other.text != text || other.arrays.size != arrays.size) return false
            for (i in arrays.indices) if (!arrays[i].contentEquals(other.arrays[i])) return false
            return true
        }
        val bytes: Long get() = text.length * 2L + arrays.sumOf { it.size * 8L }
    }

    /** A key built in order: scalars as text, polygon lists and raw arrays as they are. */
    class Builder(tag: String) {
        private val sb = StringBuilder(256).append(tag)
        private val arrays = ArrayList<LongArray>()
        fun text(v: Any?): Builder { sb.append('|').append(v); return this }
        fun polys(ps: List<Polygon>): Builder { sb.append("|p").append(ps.size); for (p in ps) arrays.add(p.xy); return this }
        fun longs(a: LongArray): Builder { sb.append("|a"); arrays.add(a); return this }
        fun flags(b: BooleanArray): Builder = longs(LongArray(b.size) { if (b[it]) 1L else 0L })
        fun build() = Key(sb.toString(), arrays.toTypedArray())
    }

    /**
     * [read]: the settings its computation read, with their values. Settings are read when first asked for, so a key's
     * settings text holds only what was read before it was built; a hit also needs every setting the result read to hold
     * the same value now (reading them again, so they count as read by that slice).
     */
    private class Entry(val value: Any, val bytes: Long, val read: Map<String, String>)
    private val map = LinkedHashMap<Key, Entry>(1024, 0.75f, true)
    private var bytes = 0L
    /** The bound: `plymeld.layerCache.mb`, else 768 MB or a quarter of the heap, whichever is less (a 512 MB test JVM ran out of heap at 768). */
    @Volatile var maxBytes: Long = System.getProperty("plymeld.layerCache.mb")?.toLongOrNull()?.let { it shl 20 } ?: minOf(768L shl 20, Runtime.getRuntime().maxMemory() / 4)
    @Volatile var hits = 0L; private set
    @Volatile var misses = 0L; private set

    /**
     * The result for [key], computed by [compute] when not held ([bytesOf]: the result's size, for the bound). [s]: the slice's
     * settings: a held result is used only if every setting its computation read has the same value in them (a computation
     * that reads settings needs [s]; without it the key must hold everything the computation reads).
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> get(key: Key, bytesOf: (T) -> Long, s: plymeld.model.SliceSettings? = null, compute: () -> T): T {
        synchronized(map) { map[key] }?.let { e ->
            if (s == null || e.read.all { (id, v) -> s.textOf(id) == v }) { synchronized(map) { hits++ }; return e.value as T }
        }
        val (v, reads) = plymeld.model.SliceSettings.reading(compute)
        val read = if (s == null) emptyMap() else reads.associateWith { s.textOf(it) }
        val b = key.bytes + bytesOf(v)
        synchronized(map) {
            misses++
            val old = map.put(key, Entry(v, b, read))
            bytes += b - (old?.bytes ?: 0L)
            // a bound of 0 keeps nothing (the cache off); otherwise the least recently used results go first
            while (bytes > maxBytes && map.isNotEmpty()) { val it = map.entries.iterator(); val e = it.next(); bytes -= e.value.bytes; it.remove() }
        }
        return v
    }

    fun clear() { synchronized(map) { map.clear(); bytes = 0 } }
    fun stats(): String = synchronized(map) { "layer cache: $hits hits, $misses misses, ${map.size} results, ${bytes shr 20} MB of ${maxBytes shr 20}" }

    fun bytesOfPolys(ps: List<Polygon>): Long = ps.sumOf { it.xy.size * 8L }
    fun bytesOfPaths(ps: List<ToolPath>): Long = ps.sumOf { it.xy.size * 8L + (it.widths?.size ?: 0) * 8L + (it.overhangShare?.size ?: 0) * 4L }
}
