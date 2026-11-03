package plymeld.core

import plymeld.program.Progress

/**
 * Per-layer work that does not depend on other layers' RESULTS runs on all cores, for speed. Results
 * come back in index order, so output stays byte-identical to a sequential run; the work
 * functions must be pure (the geometry kernel is — its only shared state is thread-safe counters).
 */
object Parallel {
    fun <T> map(n: Int, f: (Int) -> T): List<T> =
        java.util.stream.IntStream.range(0, n).parallel().mapToObj { f(it) }.toList()

    /** As [map], each finished unit counted on [counter] (the progress report, and the cancel check between units). */
    fun <T> map(n: Int, counter: Progress.Counter?, f: (Int) -> T): List<T> =
        if (counter == null) map(n, f) else java.util.stream.IntStream.range(0, n).parallel().mapToObj { f(it).also { counter.tick() } }.toList()
}
