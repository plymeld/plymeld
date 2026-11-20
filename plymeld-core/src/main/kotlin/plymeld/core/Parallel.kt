package plymeld.core

import plymeld.program.Progress

/**
 * Per-layer work that does not depend on other layers' RESULTS runs on all cores, for speed. Results
 * come back in index order, so output stays byte-identical to a sequential run; the work
 * functions must be pure (the geometry kernel is — its only shared state is thread-safe counters).
 *
 * Each index is a task of its own (the range split in halves down to one index), so the few slow units of a run (a model's
 * first layers, its walls by width) run side by side rather than one after another on one core: a parallel stream's
 * chunks of a few layers each put a run's five slowest layers on one core, which then took twice as long as the rest of
 * the run.
 *
 * A unit must not start parallel work while it holds a lock other units may wait on (a synchronized lazy whose initializer
 * calls [map], first read from inside a unit): a worker joining that work helps with other units, one of them waits on
 * the lock, and the pool stops. Make such a value before the parallel work reads it, on the calling thread, or compute it
 * under no lock (LazyThreadSafetyMode.PUBLICATION); ModelCheck.Surface.ready does both (ParallelLockTest).
 */
object Parallel {
    fun <T> map(n: Int, f: (Int) -> T): List<T> = map(n, null, f)

    /** As [map], each finished unit counted on [counter] (the progress report, and the cancel check between units). */
    fun <T> map(n: Int, counter: Progress.Counter?, f: (Int) -> T): List<T> {
        if (n <= 0) return emptyList()
        val out = arrayOfNulls<Any?>(n)
        val task = Range(0, n) { i -> out[i] = f(i); counter?.tick() }
        if (java.util.concurrent.ForkJoinTask.inForkJoinPool()) task.invoke() else java.util.concurrent.ForkJoinPool.commonPool().invoke(task)
        @Suppress("UNCHECKED_CAST")
        return out.asList() as List<T>
    }

    /** Indices [lo] until [hi], split in halves down to one index each. */
    private class Range(val lo: Int, val hi: Int, val unit: (Int) -> Unit) : java.util.concurrent.RecursiveAction() {
        override fun compute() {
            if (hi - lo == 1) { unit(lo); return }
            val mid = (lo + hi) ushr 1
            invokeAll(Range(lo, mid, unit), Range(mid, hi, unit))
        }
    }
}
