package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.program.Exact
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import plymeld.program.SetPressureAdvance
import plymeld.program.SetTemp
import plymeld.program.Step
import java.io.StringWriter

/**
 * The virtual printer's pressure model (PressureModel) on g-code the writer wrote: a row of straight walls, each a slow
 * lead-in, a fast middle, and a slow tail, each at its own pressure advance set just before it. Read as a person reads a
 * pressure-advance plate: just after the speed-up mark (where the head starts to speed up) and just after the slow-down mark
 * (where it starts to slow down), thin (groove), as asked (flat), or fat (ridge). The flip from groove to ridge at the
 * speed-up mark must fall exactly where the set value crosses the true one, for several true values and flow steps, the
 * slow-down mark reading the opposite; with the lag switched off every wall is flat, and the flip test must fail.
 */
class PressureModelTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    // the wall (mm): a 10 mm lead-in, a 30 mm middle, a 10 mm tail, 0.45 mm wide and 0.2 mm high; the walls 10 mm apart
    val lead = 10.0; val fast = 30.0; val tail = 10.0
    val paCommand = Regex("SET_PRESSURE_ADVANCE ADVANCE=(-?[0-9.]+)")

    fun wallsGcode(values: List<Double>, slow: Double, quick: Double, accel: Double): String {
        val steps = ArrayList<Step>()
        steps += SetTemp(0, 210, wait = true)
        steps += LayerStart(0, 200, 200)
        for ((k, v) in values.withIndex()) {
            val x = 60_000L + k * 10_000L
            steps += SetPressureAdvance(0, v)
            val xy = longArrayOf(x, 50_000, x, 50_000 + (lead * 1000).toLong(), x, 50_000 + ((lead + fast) * 1000).toLong(), x, 50_000 + ((lead + fast + tail) * 1000).toLong())
            steps += Extrude("Outer wall", xy, false, 200, 450, 200, tool = 0, exact = Exact("wall ${k + 1}", doubleArrayOf(slow, quick, slow), accel, 1.0, 0.8))
        }
        val program = PrintProgram(steps, emptyList(), exact = true, selfPrimed = true)
        return StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test", pressureAdvance = emptyMap()).write(program, it) }.toString()
    }

    /** Each wall's set value and its readings at the speed-up and slow-down marks, by the model with true value [trueK]. */
    class Wall(val setK: Double, val up: Double, val down: Double)
    fun read(g: String, trueK: Double, slow: Double, quick: Double, accel: Double, lag: Boolean = true): List<Wall> {
        val runs = PressureModel.runs(g, paCommand, "s", s.filamentDiameterMm) { it.startsWith("wall ") }
        val m = PressureModel(trueK, lag)
        // the marks: the speed-up where the middle starts (the head speeds up after it), the slow-down where the head starts to
        // slow down before the tail; each read over the 5 mm after it
        val upAt = lead; val downAt = lead + fast - (quick * quick - slow * slow) / (2 * accel)
        return runs.map { r -> val l = m.lay(r); Wall(r.setK, l.deviation(upAt, upAt + 5), l.deviation(downAt, downAt + 5)) }
    }
    fun word(dev: Double) = when { dev < -1e-9 -> "groove"; dev > 1e-9 -> "ridge"; else -> "flat" }

    /** The flip test: grooves below the true value, ridges above it, flat at it, and the slow-down mark the opposite. */
    fun expectFlip(walls: List<Wall>, trueK: Double) {
        for (w in walls) {
            val want = when { w.setK < trueK - 1e-12 -> "groove"; w.setK > trueK + 1e-12 -> "ridge"; else -> "flat" }
            word(w.up) shouldBe want
            word(w.down) shouldBe when (want) { "groove" -> "ridge"; "ridge" -> "groove"; else -> "flat" }
        }
        // a flip: a groove and a ridge on the row, the highest groove's value below the lowest ridge's, the true value between
        val grooves = walls.filter { word(it.up) == "groove" }; val ridges = walls.filter { word(it.up) == "ridge" }
        (grooves.isNotEmpty() && ridges.isNotEmpty()) shouldBe true
        grooves.maxOf { it.setK } shouldBeLessThan ridges.minOf { it.setK }
        (trueK in grooves.maxOf { it.setK }..ridges.minOf { it.setK }) shouldBe true
    }

    "the speed-up mark flips exactly where the set value crosses the true one, the slow-down mark the opposite: several true values and flow steps" {
        for (trueK in listOf(0.02, 0.043, 0.07)) for ((slow, quick) in listOf(100.0 to 150.0, 100.0 to 200.0, 60.0 to 250.0)) {
            // the row from 0 to twice the true value, and the true value itself (flat there)
            val values = (0..10).map { it * trueK / 5 }.map { Math.round(it * 1e5) / 1e5 } + trueK
            val g = wallsGcode(values, slow, quick, 5000.0)
            val walls = read(g, trueK, slow, quick, 5000.0)
            walls.size shouldBe values.size
            walls.map { it.setK } shouldBe values
            expectFlip(walls, trueK)
        }
    }

    "the defect grows with the flow step and with how far the set value is off: dV = dK x dQ" {
        val trueK = 0.04
        fun up(quick: Double, setK: Double) = read(wallsGcode(listOf(setK), 100.0, quick, 5000.0), trueK, 100.0, quick, 5000.0).single().up
        (Math.abs(up(250.0, 0.02)) > Math.abs(up(150.0, 0.02))) shouldBe true
        (Math.abs(up(200.0, 0.0)) > Math.abs(up(200.0, 0.02))) shouldBe true
    }

    "control: the lag switched off, every wall is flat, and the flip test fails" {
        val trueK = 0.043
        val values = (0..10).map { it * 0.01 }
        val walls = read(wallsGcode(values, 100.0, 200.0, 5000.0), trueK, 100.0, 200.0, 5000.0, lag = false)
        walls.all { word(it.up) == "flat" && word(it.down) == "flat" } shouldBe true
        shouldThrow<AssertionError> { expectFlip(walls, trueK) }
        // and on: the same row flips (the control's own control)
        expectFlip(read(wallsGcode(values, 100.0, 200.0, 5000.0), trueK, 100.0, 200.0, 5000.0), trueK)
    }

    "the slow-down mark reads opposite only while the pressure settles within the fast stretch: a true value of 0.5 s over 30 mm at 200 mm/s does not" {
        val trueK = 0.5
        val walls = read(wallsGcode(listOf(0.25, 0.75), 100.0, 200.0, 5000.0), trueK, 100.0, 200.0, 5000.0)
        // the speed-up mark still flips at the true value; the slow-down mark reads the same as it
        walls.map { word(it.up) } shouldBe listOf("groove", "ridge")
        walls.map { word(it.down) } shouldBe listOf("groove", "ridge")
    }
})
