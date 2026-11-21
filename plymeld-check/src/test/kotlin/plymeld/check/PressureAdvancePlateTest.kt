package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PressureAdvanceCheck
import plymeld.core.calibrate.PressureAdvanceGeometry
import plymeld.core.calibrate.PressureAdvanceInstructions
import plymeld.core.calibrate.PressureAdvancePlate
import plymeld.core.calibrate.PressureAdvanceSpec
import plymeld.core.calibrate.PressureAdvanceWall
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.model.SliceSettings

/**
 * The pressure-advance plates (PressureAdvancePlate) as written: the find plate passes its own read-back check and the g-code
 * check, and every control the check must catch is caught; the plate is deterministic; read by the virtual printer's pressure
 * model (PressureModel), its walls' speed-up marks flip from groove to ridge exactly where the set value crosses the true one,
 * in both rows, the slow-down marks the opposite; the confirm plate and the machine's own check pass theirs. The page's
 * picture is drawn for review (build/renders).
 */
class PressureAdvancePlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val command = Regex("SET_PRESSURE_ADVANCE ADVANCE=(-?[0-9]+(?:\\.[0-9]+)?)")
    val slow = 100.0; val fast = 200.0; val accel = 5000.0
    fun spec(walls: List<PressureAdvanceWall>, kind: PressureAdvanceSpec.Kind, framePa: Double?) = PressureAdvanceSpec(0, "spool 1", kind, walls, 200, 200, 450, 500, 1.75, 1.0,
        215, 220, 100.0, PathValues(40.0, 3000.0, 1.0, 0.8), PathValues(150.0, 3000.0, 1.0, 0.8), slow, accel, framePa, 400, 60_000, 30_000)
    val values = (0 until PressureAdvanceGeometry.values).map { it * 0.01 }
    val findWalls = values.indices.map { PressureAdvanceWall(0, it, values[it], fast) } + values.indices.map { PressureAdvanceWall(1, it, values[values.size - 1 - it], fast) }
    val find = spec(findWalls, PressureAdvanceSpec.Kind.FIND, 0.0)
    fun gcodeOf(p: PressureAdvancePlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    fun problems(sp: PressureAdvanceSpec, t: String, plateSets: Boolean = true) =
        PressureAdvanceCheck.check(sp, t, command, "s") + GcodeCheck.check(t, Expectations.forSetup(setup, pressureAdvanceSetByPlate = plateSets))
    val text = gcodeOf(PressureAdvancePlate(find))

    "the find plate passes its read-back check and the g-code check" {
        problems(find, text).shouldBeEmpty()
    }

    "controls: every one the check must catch is caught" {
        for (c in listOf("value", "command", "order", "direction", "speed", "holes", "chamfer", "tally", "corner"))
            withClue(c) { problems(find, gcodeOf(PressureAdvancePlate(find, setOf(c)))).shouldNotBeEmpty() }
        // the g-code check, told nothing of a plate that sets its own values, refuses them (the exemption is what passes it)
        GcodeCheck.check(text, Expectations.forSetup(setup)).filter { "pressure advance" in it }.shouldNotBeEmpty()
    }

    "deterministic: the same specification, the same bytes" {
        gcodeOf(PressureAdvancePlate(find)) shouldBe text
    }

    "read by the pressure model, the speed-up marks flip exactly where the set value crosses the true one, in both rows; the slow-down marks the opposite" {
        val trueK = 0.043
        val runs = PressureModel.runs(text, command, "s", s.filamentDiameterMm) { it.startsWith("wall ") }
        runs.size shouldBe findWalls.size * find.wallLayers
        val m = PressureModel(trueK)
        val read = PressureAdvanceGeometry.readLength / 1000.0
        for (r in runs) {
            val w = findWalls.first { it.label == r.cell }
            val up = find.markUp(w) / 1000.0; val down = find.markDown(w) / 1000.0
            val laid = m.lay(r)
            r.setK shouldBe w.value
            val u = laid.deviation(up, up + read); val d = laid.deviation(down, down + read)
            withClue("${r.cell} at ${w.value} s") {
                (u < 0) shouldBe (w.value!! < trueK)
                (d > 0) shouldBe (w.value!! < trueK)
            }
        }
        // control: the ideal extruder lays every wall as asked, and nothing flips
        val ideal = PressureModel(trueK, lag = false)
        runs.all { r -> val w = findWalls.first { it.label == r.cell }; ideal.lay(r).deviation(find.markUp(w) / 1000.0, find.markUp(w) / 1000.0 + read) == 0.0 } shouldBe true
    }

    "the confirm plate: three values at three flow steps pass; on a machine that sets its own, one value with no command written passes, and a command there is caught" {
        val fasts = listOf(133.3, 166.7, 200.0)
        val confirm = spec(listOf(0.036, 0.04, 0.044).flatMapIndexed { vi, v -> fasts.indices.map { k -> PressureAdvanceWall(0, vi * 3 + k, v, fasts[k], k) } }, PressureAdvanceSpec.Kind.CONFIRM, 0.04)
        val ct = gcodeOf(PressureAdvancePlate(confirm))
        problems(confirm, ct).shouldBeEmpty()
        for (c in listOf("value", "direction", "speed", "holes")) withClue(c) { problems(confirm, gcodeOf(PressureAdvancePlate(confirm, setOf(c)))).shouldNotBeEmpty() }
        val own = testSetup("hardware.machine.tm.firmware.own_pressure_advance" to true)
        val machine = spec(fasts.indices.map { PressureAdvanceWall(0, it, null, fasts[it], it) }, PressureAdvanceSpec.Kind.CONFIRM, null)
        val mt = PressureAdvancePlate(machine).gcode(SliceSettings.of(own), own.foreignTemplateVars, "test")
        mt.lines().none { it.startsWith("SET_PRESSURE_ADVANCE") } shouldBe true
        (PressureAdvanceCheck.check(machine, mt, command, "s") + GcodeCheck.check(mt, Expectations.forSetup(own))).shouldBeEmpty()
        // control: a command slipped in before a wall is caught by both
        val slipped = mt.lines().toMutableList().also { it.add(it.indexOf("; CELL: wall 1.2"), "SET_PRESSURE_ADVANCE ADVANCE=0.05") }.joinToString("\n")
        PressureAdvanceCheck.check(machine, slipped, command, "s").shouldNotBeEmpty()
        GcodeCheck.check(slipped, Expectations.forSetup(own)).filter { "pressure advance" in it }.shouldNotBeEmpty()
    }

    "the page: the question, the conditions, the readings' words, what to enter; its picture drawn for review" {
        val md = PressureAdvanceInstructions.markdown(find, PressureAdvanceInstructions.Info("pla-generic", "qidi-q2", 0, "test", "pa.gcode", 40.0, 60,
            "pei", false, false, emptyList(), 0.1, null, false, 0.8), "pa.png")
        for (w in listOf("groove", "flat", "ridge", "bulge", "sharp", "round", "pressure-advance readings pa.gcode", "pressure-advance values", "speed-up", "slow-down")) md shouldContain w
        PressureAdvanceInstructions.picture(reviewRender("pressure_advance_plate.png"), find, PressureAdvancePlate(find))
    }
})
