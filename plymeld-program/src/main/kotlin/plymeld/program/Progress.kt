package plymeld.program

import java.util.concurrent.atomic.AtomicInteger

/**
 * What a long run reports and how it is stopped (the window's progress bar and its Cancel). A
 * stage names itself with how many units it has (layers, usually)
 * and counts them as they finish, from every thread; the figures are counts of real work, never a timer's guess.
 * Cancel is a flag the work polls between units: the stage that sees it throws [Cancelled], and the caller's output
 * is left incomplete and said so.
 */
interface Progress {
    /** [done] of [total] units of [stage] are finished ([total] 0: a stage with no count, just its name). */
    fun at(stage: String, done: Int, total: Int)
    /** Set by the user: the work stops at its next check. */
    val canceled: Boolean

    /** Polled between units of work; throws [Cancelled] once the user asked. */
    fun check() { if (canceled) throw Cancelled() }

    class Cancelled : RuntimeException("canceled")

    /** A counter over a stage's units: one `tick()` per unit from any thread, the report every [every] units and at the end. */
    class Counter(private val p: Progress, private val stage: String, private val total: Int, private val every: Int = maxOf(1, total / 50)) {
        private val n = AtomicInteger()
        init { p.at(stage, 0, total) }
        fun tick() {
            val d = n.incrementAndGet()
            if (d % every == 0 || d == total) p.at(stage, d, total)
            p.check()
        }
    }

    companion object {
        /** No one listening, never canceled. */
        val NONE: Progress = object : Progress {
            override fun at(stage: String, done: Int, total: Int) {}
            override val canceled get() = false
        }
    }
}

/** A counter over [total] units of [stage] reporting to this progress. */
@Suppress("FunctionName")
fun Progress.Counter(stage: String, total: Int): Progress.Counter = Progress.Counter(this, stage, total)
