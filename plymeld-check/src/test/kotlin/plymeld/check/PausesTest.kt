package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.PauseLock
import plymeld.core.Pauses
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.Pause
import plymeld.program.PrintProgram
import plymeld.program.Step

/** Pauses: before/after an object or part, at a height, at a layer number. */
class PausesTest : StringSpec({
    // one object after another: A on layers 1..5 (0.2 first, then 0.1), B on 6..8 (its own layers from z 0.2)
    fun layer(i: Int, topUm: Long, h: Long, obj: String) = listOf(LayerStart(i, topUm, h),
        ObjectStart(obj), Extrude("Outer wall", longArrayOf(0, 0, 1000, 0), false, topUm, 400, h), ObjectEnd(obj))
    val tops = listOf(200L, 300L, 400L, 500L, 600L)
    val steps: List<Step> = tops.mapIndexed { i, t -> layer(i, t, if (i == 0) 200 else 100, "A") }.flatten() +
        listOf(200L, 300L, 400L).mapIndexed { j, t -> layer(5 + j, t, if (j == 0) 200 else 100, "B") }.flatten()
    val program = PrintProgram(steps, emptyList())

    /** The layer number (1-based) each pause follows, and its reason. */
    fun pausesAt(p: PrintProgram): List<Pair<Int, String>> {
        var layer = 0; val out = ArrayList<Pair<Int, String>>()
        for (st in p.steps) { if (st is LayerStart) layer = st.index + 1; if (st is Pause) out.add(layer to st.reason) }
        return out
    }

    "control: no locks leave the program as it was" {
        Pauses.insert(program, emptyList()).program.steps shouldBe program.steps
    }

    "at a layer number, counted from 1" {
        pausesAt(Pauses.insert(program, listOf(PauseLock.AtLayer(3))).program) shouldBe listOf(3 to "at layer 3")
    }

    "at a height: the first layer whose top reaches it (between layers rounds up)" {
        pausesAt(Pauses.insert(program, listOf(PauseLock.AtZ(0.4))).program).map { it.first } shouldBe listOf(3)
        pausesAt(Pauses.insert(program, listOf(PauseLock.AtZ(0.35))).program).map { it.first } shouldBe listOf(3)
    }

    "before / after an object, by the program's object blocks" {
        pausesAt(Pauses.insert(program, listOf(PauseLock.Before("B"))).program).map { it.first } shouldBe listOf(6)
        pausesAt(Pauses.insert(program, listOf(PauseLock.After("A"))).program).map { it.first } shouldBe listOf(6)
        // the pause comes right after the layer start, before anything on the layer prints
        val q = Pauses.insert(program, listOf(PauseLock.Before("B"))).program.steps
        val k = q.indexOfFirst { it is Pause }
        (q[k - 1] is LayerStart) shouldBe true
        (q[k + 1] is ObjectStart) shouldBe true
    }

    "before / after a part, by its Z range" {
        // a part from z 0.25 to 0.45 lies in layers 2 (0.2..0.3), 3 and 4 (0.4..0.5): before = 2, after = 5
        val ranges = mapOf("magnet" to doubleArrayOf(0.25, 0.45))
        pausesAt(Pauses.insert(program, listOf(PauseLock.Before("magnet")), ranges).program).map { it.first } shouldBe listOf(2)
        pausesAt(Pauses.insert(program, listOf(PauseLock.After("magnet")), ranges).program).map { it.first } shouldBe listOf(5)
    }

    "two locks on one layer make one pause; impossible ones are refused" {
        pausesAt(Pauses.insert(program, listOf(PauseLock.AtLayer(6), PauseLock.Before("B"))).program) shouldBe listOf(6 to "at layer 6; before 'B'")
        shouldThrow<IllegalArgumentException> { Pauses.insert(program, listOf(PauseLock.After("B"))) }.message shouldContain "last layer"
        shouldThrow<IllegalStateException> { Pauses.insert(program, listOf(PauseLock.Before("C"))) }.message shouldContain "no object or part"
        shouldThrow<IllegalArgumentException> { Pauses.insert(program, listOf(PauseLock.AtLayer(9))) }.message shouldContain "1..8"
    }
})
