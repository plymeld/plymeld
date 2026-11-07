package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * [SoupCache]: a soup's value worked out once while it fits, the values' total held to the bound (the least recently used
 * dropped first), a value larger than the bound not kept, and keys by identity. The pose search on a model of millions of
 * triangles ran out of heap when every live soup's value was held; this is that bound. Control: a second soup with the
 * same coordinates is another key.
 */
class SoupCacheTest : StringSpec({
    fun soup() = TriangleSoup(floatArrayOf(0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f), 1)

    "values are worked out once while they fit; past the bound the least recently used go first; too large is never kept" {
        var computed = 0
        val cache = SoupCache<LongArray>(100) { 8L * it.size }
        val a = soup(); val b = soup(); val c = soup()
        fun get(s: TriangleSoup, n: Int) = cache.get(s) { computed++; LongArray(n) }
        get(a, 5); get(a, 5)
        computed shouldBe 1                     // held: 40 bytes
        get(b, 5)
        computed shouldBe 2                     // 80 bytes: both held
        get(a, 5)
        computed shouldBe 2                     // a used last
        get(c, 5)                               // 120 bytes: b, the least recently used, dropped
        computed shouldBe 3
        get(a, 5); get(c, 5)
        computed shouldBe 3
        get(b, 5)
        computed shouldBe 4                     // b was dropped: worked out again
        // larger than the bound: returned, never kept
        val big = soup()
        get(big, 20); get(big, 20)
        computed shouldBe 6
        // control: by identity, not by coordinates
        val twin = soup()
        get(twin, 5)
        computed shouldBe 7
    }
})
