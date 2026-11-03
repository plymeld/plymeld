package plymeld.geom

/** A growable array of longs with no boxing (an `ArrayList<Long>` boxed every coordinate the arrangement wrote). */
class LongList(capacity: Int = 16) {
    private var a = LongArray(maxOf(capacity, 4))
    var size = 0; private set

    fun add(v: Long) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    fun add(x: Long, y: Long) { add(x); add(y) }
    operator fun get(i: Int): Long = a[i]
    fun toLongArray(): LongArray = a.copyOf(size)
}

/** A growable array of ints with no boxing. */
class IntList(capacity: Int = 16) {
    private var a = IntArray(maxOf(capacity, 4))
    var size = 0; private set

    fun add(v: Int) {
        if (size == a.size) a = a.copyOf(a.size * 2)
        a[size++] = v
    }
    operator fun get(i: Int): Int = a[i]
    fun toIntArray(): IntArray = a.copyOf(size)
}
