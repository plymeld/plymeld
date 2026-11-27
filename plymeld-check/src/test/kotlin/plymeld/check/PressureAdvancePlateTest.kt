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
 * picture is drawn; the docs' pictures are the command's own plate's (plymeld-commands, PressureAdvanceSampleTest).
 */
class PressureAdvancePlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val command = Regex("SET_PRESSURE_ADVANCE ADVANCE=(-?[0-9]+(?:\\.[0-9]+)?)")
    val slow = 100.0; val fast = 200.0; val accel = 5000.0
    fun spec(walls: List<PressureAdvanceWall>, kind: PressureAdvanceSpec.Kind, framePa: Double?) = PressureAdvanceSpec(0, "spool 1", kind, walls, 200, 200, 450, 500, 1.75, 1.0,
        215, 220, 100.0, PathValues(40.0, 3000.0, 1.0, 0.8), PathValues(150.0, 3000.0, 1.0, 0.8), slow, accel, framePa, 400, 60_000, 30_000)
    val values = (0 until PressureAdvanceGeometry.values).map { PressureAdvanceSpec.atLabel(it * 0.01) }
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

    "the walls grow with the plate's highest value until the pressure settles within them: on the plate's own g-code, every wall's slow-down mark reads opposite to its speed-up mark for true values up to that highest value" {
        val area = plymeld.core.calibrate.MeltSpec.bead(450, 200)
        val read = PressureAdvanceGeometry.readLength / 1000.0
        var lastLength = 0.0
        for (top in listOf(0.1, 0.5, 1.0)) {
            val st = PressureModel.stretchesFor(slow, fast, accel, area, read, top, PressureAdvanceGeometry.leadMin / 1000.0, PressureAdvanceGeometry.fastMin / 1000.0, PressureAdvanceGeometry.tail / 1000.0, 1000.0)!!
            (st.first + st.second >= lastLength) shouldBe true
            lastLength = st.first + st.second
            // the plate: one row of values from 0 to the highest, its walls as long as the model says
            val vs = (0..10).map { PressureAdvanceSpec.atLabel(it * top / 10) }
            val sp = PressureAdvanceSpec(0, "spool 1", PressureAdvanceSpec.Kind.FIND, vs.indices.map { PressureAdvanceWall(0, it, vs[it], fast) }, 200, 200, 450, 500, 1.75, 1.0,
                215, 220, 100.0, PathValues(40.0, 3000.0, 1.0, 0.8), PathValues(150.0, 3000.0, 1.0, 0.8), slow, accel, 0.0, 400, 60_000, 30_000,
                leadUm = Math.round(st.first * 1000), fastUm = Math.round(st.second * 1000))
            for (trueK in listOf(top / 4 + 0.001, top / 2 + 0.003, top * 0.95)) for (v in vs) {
                // the wall alone, by its stretches
                val (u, d) = PressureModel.stepWall(trueK, v, slow, fast, accel, st.first, st.second, st.third, area, read)
                withClue("highest $top s, true $trueK s, a wall at $v s (${st.first} / ${st.second} / ${st.third} mm)") { (u < 0) shouldBe (v < trueK); (u * d < 0) shouldBe true }
            }
            // control: the shortest walls do not settle at a highest value of 0.5 s or more, and the same reading fails there
            if (top >= 0.5) {
                val short = PressureModel.stepWall(top * 0.95, top * 0.95 / 2, slow, fast, accel, PressureAdvanceGeometry.leadMin / 1000.0, PressureAdvanceGeometry.fastMin / 1000.0, PressureAdvanceGeometry.tail / 1000.0, area, read)
                (short.first * short.second < 0) shouldBe false
            }
            // on the plate's own g-code, where the plate fits the test machine's bed (270 mm deep, from y 30)
            if (sp.baseD > 230_000) continue
            val g = gcodeOf(PressureAdvancePlate(sp))
            problems(sp, g).shouldBeEmpty()
            val runs = PressureModel.runs(g, command, "s", s.filamentDiameterMm) { it.startsWith("wall ") }.take(vs.size)
            for (trueK in listOf(top / 4 + 0.001, top / 2 + 0.003, top * 0.95)) {
                val m = PressureModel(trueK)
                for (r in runs) {
                    val w = sp.walls.first { it.label == r.cell }
                    val laid = m.lay(r)
                    val u = laid.deviation(sp.markUp(w) / 1000.0, sp.markUp(w) / 1000.0 + read); val d = laid.deviation(sp.markDown(w) / 1000.0, sp.markDown(w) / 1000.0 + read)
                    withClue("highest $top s, true $trueK s, ${r.cell} at ${w.value} s") {
                        (u < 0) shouldBe (w.value!! < trueK)
                        (u * d < 0) shouldBe true
                    }
                }
            }
        }
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

    "the page: the question, the conditions, the readings' words, what to enter; its picture drawn" {
        val md = PressureAdvanceInstructions.markdown(find, PressureAdvanceInstructions.Info("pla-generic", "qidi-q2", 0, "test", "pa.gcode", 40.0, 60,
            "pei", false, false, emptyList(), 0.1, null, false, 0.8), "pa.png")
        for (w in listOf("groove", "flat", "ridge", "bulge", "sharp", "round", "pressure-advance readings pa.gcode", "pressure-advance values", "speed-up", "slow-down")) md shouldContain w
        // the docs' copy is the command's own (plymeld-commands, PressureAdvanceSampleTest); this one is only drawn
        val png = java.nio.file.Files.createTempFile("pa-page", ".png")
        PressureAdvanceInstructions.picture(png, find, PressureAdvancePlate(find))
        (javax.imageio.ImageIO.read(png.toFile()).width > 0) shouldBe true
    }

    "the stroke font's 1 has a base and a flat flag, so it is not taken for the arrow beside it" {
        val one = plymeld.core.calibrate.StrokeFont.polygons("1", 0, 0, 6_000, 800)
        val width = one.maxOf { p -> p.bounds()[2] } - one.minOf { p -> p.bounds()[0] }
        // the stem alone spans 0.8 mm at this size; with its base, as wide as the base (0.5 to 3.5 units, and the strokes)
        (width >= 3_500) shouldBe true
        // no slanted stroke: every edge of its outline runs across or along it, so no stroke meets the stem at an angle as an
        // arrowhead's barb does (a slanted flag at the stem's top read as one)
        for (p in one) for (i in 0 until p.xy.size / 2) {
            val j = (i + 1) % (p.xy.size / 2)
            withClue("edge ${p.xy[2 * i]},${p.xy[2 * i + 1]} to ${p.xy[2 * j]},${p.xy[2 * j + 1]}") { (p.xy[2 * i] == p.xy[2 * j] || p.xy[2 * i + 1] == p.xy[2 * j + 1]) shouldBe true }
        }
    }

    "the decimal point is a short bar two beads tall, not one bead; the arrow is one outline, laid without fragments at its tip" {
        val dot = plymeld.core.calibrate.StrokeFont.polygons(".", 0, 0, 6_000, 800)
        val b = dot.single().bounds()
        // a dot of one stroke was 0.8 x 0.8 mm here; the bar is a stroke wide and a sixth of the height more tall
        (b[3] - b[1] >= 1_800) shouldBe true
        val arrow = PressureAdvancePlate(find).arrow(10_000, 10_000)
        val (rings, _) = plymeld.core.calibrate.PlateFill.fill(listOf(arrow), 450, 200, 0.0)
        (rings.size <= plymeld.core.calibrate.PlateGeometry.frameWalls) shouldBe true
        // no fragments: every edge of every ring at least half the line's width (a shorter move is lost in the bead); a shaft
        // and a head of crossing strokes leave edges of a few hundredths of a millimeter where their square ends meet at the tip
        for (r in rings) for (i in 0 until r.size / 2) {
            val j = (i + 1) % (r.size / 2)
            withClue("edge ${r[2 * i]},${r[2 * i + 1]} to ${r[2 * j]},${r[2 * j + 1]}") { (Math.hypot((r[2 * j] - r[2 * i]).toDouble(), (r[2 * j + 1] - r[2 * i + 1]).toDouble()) >= 450 / 2.0) shouldBe true }
        }
    }
})
