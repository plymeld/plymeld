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
import plymeld.core.calibrate.PressureAdvancePush
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
        for (c in listOf("value", "command", "order", "direction", "speed", "holes", "chamfer", "gaps", "tabs", "corner"))
            withClue(c) { problems(find, gcodeOf(PressureAdvancePlate(find, setOf(c)))).shouldNotBeEmpty() }
        // the strips' controls caught by their own rules
        problems(find, gcodeOf(PressureAdvancePlate(find, setOf("gaps")))).any { "laid across" in it } shouldBe true
        problems(find, gcodeOf(PressureAdvancePlate(find, setOf("tabs")))).any { "break-off tab" in it } shouldBe true
        // the g-code check, told nothing of a plate that sets its own values, refuses them (the exemption is what passes it)
        GcodeCheck.check(text, Expectations.forSetup(setup)).filter { "pressure advance" in it }.shouldNotBeEmpty()
    }

    "the push limit: a plate read against a limit at its own highest push passes; 1 % under it is refused, naming the move" {
        val top = find.walls.maxOf { find.peakOf(it) }
        (top > 0) shouldBe true
        PressureAdvanceCheck.check(find.withPushLimit(top + 1e-6), text, command, "s").shouldBeEmpty()
        PressureAdvanceCheck.check(find.withPushLimit(top * 0.99), text, command, "s").any { "the extruder pushed" in it } shouldBe true
    }

    "the model against the printed plate: with a Creator 5's values (294.65 mm/s fast, 100 slow, 15000 mm/s2, 0.44 x 0.2 mm lines, 1.75 mm filament, Klipper's 0.04 s smoothing), the 111.6 ms wall's corner from rest and the 166.8 ms wall's step bound the extruder's limit, which was quiet at the first and squirted at the second" {
        val r = (0.2 * (0.44 - 0.2 * (1 - Math.PI / 4))) / (Math.PI * 1.75 * 1.75 / 4)
        val quiet = PressureAdvancePush.peak(0.1116, 0.0, 294.65, 15000.0, 0.04, r)
        val squirt = PressureAdvancePush.peak(0.1668, 100.0, 294.65, 15000.0, 0.04, r)
        withClue("corner at 111.6 ms $quiet, step at 166.8 ms $squirt") { (quiet > 50.0 && quiet < 51.0 && squirt > 54.0 && squirt < 55.0) shouldBe true }
        // with a limit between them, every move at 111.6 ms and below is inside, at 166.8 ms and above outside
        val limit = (quiet + squirt) / 2
        for (k in listOf(0.0, 0.01, 0.0223, 0.0499, 0.0746, 0.1116)) for (v0 in listOf(0.0, 100.0)) (PressureAdvancePush.peak(k, v0, 294.65, 15000.0, 0.04, r) <= limit) shouldBe true
        for (k in listOf(0.1668, 0.2494, 0.3727)) (PressureAdvancePush.peak(k, 100.0, 294.65, 15000.0, 0.04, r) > limit) shouldBe true
        // controls: the push spread evenly over the window (half the documented weighting's peak) orders the corner at 111.6 ms
        // over the step at 166.8 ms, against the print; unsmoothed, the 111.6 ms corner is far over the bound
        val evenCorner = r * (294.65 + 0.1116 * 294.65 / 0.04); val evenStep = r * (294.65 + 0.1668 * 194.65 / 0.04)
        (evenCorner > evenStep) shouldBe true
        (PressureAdvancePush.peak(0.1116, 0.0, 294.65, 15000.0, 0.0, r) > squirt) shouldBe true
    }

    "the base is solid: on every base layer, in every wall's strip, neighboring fill lines no further apart than a line's spacing; a line left out is caught" {
        val all = TechniqueRender.read(text).moves.filter { (it.feature == "Bottom surface" || it.feature == "Internal solid infill") && it.length > 1.0 }
        fun gaps(moves: List<TechniqueRender.Move>): List<String> {
            val out = ArrayList<String>()
            val ox = find.originXUm / 1000.0; val oy = find.originYUm / 1000.0
            for (layer in 0 until PressureAdvanceGeometry.baseLayers) {
                val fill = moves.filter { it.layer == layer }
                val spacing = (if (layer == 0) find.firstWidthUm - find.firstLayerUm * (1 - Math.PI / 4) else find.widthUm - find.layerUm * (1 - Math.PI / 4)) / 1000.0
                for (r in 0 until find.rows) for (k in 0 until find.perRow) {
                    val b = find.strip(r, k)
                    val mine = fill.filter { val mx = (it.x0 + it.x1) / 2 - ox; val my = (it.y0 + it.y1) / 2 - oy; mx > b[0] / 1000.0 && mx < b[2] / 1000.0 && my > b[1] / 1000.0 && my < b[3] / 1000.0 }
                    for ((ang, ms) in mine.groupBy { Math.round(Math.toDegrees(Math.atan2(it.y1 - it.y0, it.x1 - it.x0)) + 360) % 180 }) {
                        val a = Math.toRadians(ang.toDouble()); val nx = -Math.sin(a); val ny = Math.cos(a)
                        val offs = ms.map { Math.round((it.x0 * nx + it.y0 * ny) * 1000) / 1000.0 }.distinct().sorted()
                        offs.zipWithNext().firstOrNull { (p, q) -> q - p > spacing + 0.01 }?.let { (p, q) -> out += "layer ${layer + 1}, strip ${r + 1}.${k + 1}: lines ${q - p} mm apart, the spacing $spacing mm" }
                    }
                }
            }
            return out
        }
        gaps(all).shouldBeEmpty()
        // control: the second layer's longest fill line left out
        val longest = all.filter { it.layer == 1 }.maxBy { it.length }
        gaps(all.filter { it !== longest }).shouldNotBeEmpty()
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
        for (c in listOf("value", "direction", "speed", "holes", "gaps", "tabs", "steps", "notch")) withClue(c) { problems(confirm, gcodeOf(PressureAdvancePlate(confirm, setOf(c)))).shouldNotBeEmpty() }
        // the flow-step notches read back: one missing and the rest moved, or one too many, each named
        problems(confirm, gcodeOf(PressureAdvancePlate(confirm, setOf("steps")))).any { "wall 1.2's flow-step notch" in it } shouldBe true
        problems(confirm, gcodeOf(PressureAdvancePlate(confirm, setOf("notch")))).any { "wall 1.1's flow step" in it && "notch 2 would go" in it } shouldBe true
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
        for (w in listOf("groove", "flat", "ridge", "bulge", "sharp", "round", "pressure-advance readings pa.gcode", "pressure-advance values", "speed-up", "slow-down",
            "snap each wall out", "Against a light", "In a raking light", "reading.png", "a reading of its own")) md shouldContain w
        // control: a word the page does not say is not found
        md.contains("tally") shouldBe false
        // the reading picture drawn
        val reading = java.nio.file.Files.createTempFile("pa-reading", ".png")
        PressureAdvanceInstructions.readingPicture(reading)
        (javax.imageio.ImageIO.read(reading.toFile()).width > 0) shouldBe true
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
