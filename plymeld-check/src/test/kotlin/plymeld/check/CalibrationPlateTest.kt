package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.core.calibrate.CalibrationCheck
import plymeld.core.calibrate.CalibrationPlate
import plymeld.core.calibrate.CalibrationSpec
import plymeld.core.calibrate.MeltTerraces
import plymeld.core.calibrate.PathValues
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import java.io.StringWriter

/**
 * The calibration plate prints exactly as specified, and its read-back
 * check can fail: the plate as generated passes, and the same g-code with one cell altered (a band's
 * temperature, a missing cell, a melt strip's speed or width) does not.
 */
class CalibrationPlateTest : StringSpec({
    // no prime line from the writer: the specs here are built bare (no bases under the families, as `calibrate` adds), so
    // a prime line along the first object's edge can land between the bridge piers; `calibrate`'s own plates keep it
    val setup = testSetup("plan.mechanism.adhesion.prime.enabled" to false)
    val s = SliceSettings.of(setup)
    val h = 200L; val w = 440L
    val bands = CalibrationSpec.bands(195, 225, 5, 50.0 to 100.0, h, 2_400, 600)
    // the plate's framework alone: the ruler post (each test's spec adds its family)
    val spec = CalibrationSpec(0, "test", bands, h, w, 1.75,
        adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8),
        melt = null, flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000)
    val text = CalibrationPlate(spec).gcode(s, setup.foreignTemplateVars, "test")

    "bands: whole degrees, hot to cold, the fan alternating" {
        bands.map { it.tempC } shouldBe listOf(225, 220, 215, 210, 205, 200, 195)
        bands.map { it.fanPct } shouldBe listOf(50.0, 100.0, 50.0, 100.0, 50.0, 100.0, 50.0)
        CalibrationSpec.bands(200, 215, 3, 50.0 to 100.0, h, 2_400, 600).size shouldBe 6
        shouldThrow<IllegalStateException> { CalibrationSpec.bands(195, 225, 7, 50.0 to 100.0, h, 2_400, 600) }.message!! shouldContain "try 5 C or 6 C"
    }

    "the plate as generated passes its check" {
        CalibrationCheck.check(spec, text).shouldBeEmpty()
        text shouldContain "; CELL: post"
        text shouldContain "M109 S225 T0"
    }

    "control: a band one degree off is caught" {
        // every command setting band 4's temperature (the heat-up, the wait in its settling, the guard at its start)
        CalibrationCheck.check(spec, text.replace("S210 T0", "S209 T0")).shouldNotBeEmpty()
    }

    "control: a missing cell is caught" {
        // every post ring of one layer relabelled: that layer has no post
        val lines = text.lines().toMutableList()
        val starts = lines.indices.filter { lines[it].startsWith("; CHANGE_LAYER") }
        for (k in starts[40] until starts[41]) if (lines[k] == "; CELL: post") lines[k] = "; CELL: mark"
        CalibrationCheck.check(spec, lines.joinToString("\n")).any { it.contains("no 'post' cell") } shouldBe true
    }

    "control: the notches left out are caught" {
        val t = CalibrationPlate(spec, without = setOf("notches")).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(spec, t).any { it.contains("notch") } shouldBe true
    }

    // the frame against the config (the visible-line cap does not apply to a calibration plate): a machine
    // printing at 300 mm/s and 10000 mm/s2, a filament melting 15 mm3/s. Reference figures worked out here from the bead's
    // cross-section, not taken from the generator: 0.2 mm x (0.44 - 0.2 x (1 - pi/4)) mm = 0.0794 mm2, so 15 / 0.0794 = 188.9 mm/s
    "the frame prints at the machine's speed and acceleration, held to the maximum flow; other values are caught" {
        val bead = 0.2 * (0.44 - 0.2 * (1 - Math.PI / 4))
        bead shouldBe (0.07942 plusOrMinus 1e-5)
        fun framed(speed: Double, accel: Double) = CalibrationSpec(0, "test", CalibrationSpec.meltBands(205, 220, 5, 100.0, 3 * h, h), h, w, 1.75,
            adhesion = spec.adhesion, frame = PathValues(speed, accel, 1.0, 0.8), melt = MeltTerraces(300.0, 300.0, 30.0, 400, w, 300, MeltTerraces.linesFor(300.0), 10000.0, s.zHopUm),
            flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstWidthUm = 500, baseLayers = 3, ruler = false)
        fun gcodeOf(sp: CalibrationSpec) = CalibrationPlate(sp).gcode(s, setup.foreignTemplateVars, "test")
        val held = gcodeOf(framed(15 / bead, 10000.0))
        // the maximum flow binds (15 mm3/s allows 188.9 mm/s, under the machine's 300)
        CalibrationCheck.frameMotion(held, 0, 300.0, 10000.0, 15.0, 1.75).shouldBeEmpty()
        // controls: the frame at the old visible-line cap (100 mm/s), at half the machine's acceleration, at the machine's speed
        // past the flow
        CalibrationCheck.frameMotion(gcodeOf(framed(100.0, 10000.0)), 0, 300.0, 10000.0, 15.0, 1.75).let { p -> withClue(p) { p.any { it.contains(Regex("at 100\\.00 mm/s, not 188\\.(8|9)")) } shouldBe true } }
        CalibrationCheck.frameMotion(gcodeOf(framed(15 / bead, 5000.0)), 0, 300.0, 10000.0, 15.0, 1.75).any { it.contains("5000 mm/s2, not the machine's 10000") } shouldBe true
        CalibrationCheck.frameMotion(gcodeOf(framed(300.0, 10000.0)), 0, 300.0, 10000.0, 15.0, 1.75).shouldNotBeEmpty()
        // the machine's speed binds where the flow allows more (30 mm3/s: 377.7 mm/s): 300 passes, 188.9 does not
        CalibrationCheck.frameMotion(gcodeOf(framed(300.0, 10000.0)), 0, 300.0, 10000.0, 30.0, 1.75).shouldBeEmpty()
        CalibrationCheck.frameMotion(held, 0, 300.0, 10000.0, 30.0, 1.75).shouldNotBeEmpty()
        // absence is not a pass: a g-code with no frame cells
        CalibrationCheck.frameMotion(held.lines().filter { !it.startsWith("; CELL: ") }.joinToString("\n"), 0, 300.0, 10000.0, 15.0, 1.75).any { it.contains("no frame moves") } shouldBe true
    }

    // the melt plate, as `plymeld calibrate melt` makes it: the terraced melt test alone, no ruler post, 4
    // bands on a 3-layer base, every band at the same fan ([fan]: the filament's own, or off); band 1's step a 1 mm body (its top
    // layer the floor) and its strips, each later band 2 layers higher (its floor, then its strips); every strip 0.44 mm wide,
    // 0.10 to 0.30 mm thick on a 0.4 mm bore whose layer.height.max is 0.3 mm. FAST: the machine's 300 mm/s, or less to center it on
    // the filament's [maxFlow] ([fast] overrides it: a control)
    val meltBands = CalibrationSpec.meltBands(205, 220, 5, 100.0, 3 * h, h)
    fun melted(fan: Double = 100.0, maxFlow: Double = 30.0, fast: Double? = null) = CalibrationSpec(0, "test", CalibrationSpec.meltBands(205, 220, 5, fan, 3 * h, h), h, w, 1.75,
        adhesion = spec.adhesion, frame = spec.frame,
        melt = (fast ?: MeltTerraces.fastSpeed(300.0, maxFlow, w, 300, 400, 1.0)).let { v -> MeltTerraces(v, 300.0, maxFlow, 400, w, 300, MeltTerraces.linesFor(v), 10000.0, s.zHopUm) },
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstWidthUm = 500, baseLayers = 3, ruler = false)
    // each step's read layer's top (mm), by the plate's own figures, not the specification's bands: the bases (3 x 0.2
    // mm), band 1's 1 mm body (its top layer the floor) and the layer of its strips; each later band 2 layers (0.4 mm) higher.
    // The floor's top is a layer below it; strip k (1 to 6, from the front) stands on it, 0.06 + 0.04 k mm thick
    fun meltTop(b: Int) = 0.6 + 1.0 + 0.2 + 0.4 * (b - 1)
    fun stripH(k: Int) = 0.06 + 0.04 * k
    // the bead model's cross-section, worked out here: (w - h) h + pi h^2 / 4
    fun beadOf(wd: Double, hh: Double) = (wd - hh) * hh + Math.PI * hh * hh / 4
    val meltSpec = melted()
    val meltText = CalibrationPlate(meltSpec).gcode(s, setup.foreignTemplateVars, "test")
    // each melt strip's lines from the g-code text alone: (z, x0, y0, x1, y1, speed, e, declared width, fan %) by cell
    fun meltLines(t: String): Map<String, List<DoubleArray>> {
        val out = LinkedHashMap<String, MutableList<DoubleArray>>(); var cell: String? = null; var x = Double.NaN; var y = Double.NaN; var z = 0.0; var f = 0.0; var wd = Double.NaN; var fan = 0.0
        for (l in t.lines()) {
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").toDouble(); continue }
            if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
            Regex("M106 S(\\d+)").matchEntire(l)?.let { fan = it.groupValues[1].toDouble() / 255 * 100 }
            Regex("G1 .*F(\\d+).*").matchEntire(l)?.let { f = it.groupValues[1].toDouble() / 60 }
            Regex("G1 .*Z([0-9.]+).*").matchEntire(l)?.let { z = it.groupValues[1].toDouble() }
            val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E([0-9.]+).*").matchEntire(l)
            val c = cell
            if (m != null && c != null && !x.isNaN()) out.getOrPut(c) { ArrayList() } += doubleArrayOf(z, x, y, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), f, m.groupValues[3].toDouble(), wd, fan)
            Regex("G[01] .*X([-0-9.]+) Y([-0-9.]+).*").matchEntire(l)?.let { x = it.groupValues[1].toDouble(); y = it.groupValues[2].toDouble() }
        }
        return out
    }

    "terraced melt test: the plate passes; by the g-code alone a step per band, its strips one layer on a solid floor, three surfaces at the speed, half and a quarter, each strip the line width at its own thickness" {
        CalibrationCheck.check(meltSpec, meltText).shouldBeEmpty()
        val cells = meltLines(meltText)
        val area = Math.PI * 1.75 * 1.75 / 4
        // the long lines (60 mm along X) of a strip
        fun lines(c: String) = cells.getValue(c).filter { Math.abs(it[2] - it[4]) < 1e-6 && Math.abs(it[3] - it[1]) > 30 }
        var lastBack = Double.NEGATIVE_INFINITY
        for (b in 1..4) {
            val floorTop = Math.round((meltTop(b) - 0.2) * 1000) / 1000.0
            val surfaces = listOf("fast", "mid", "slow").map { sf -> (1..6).map { k -> lines("melt $b.$sf.$k") } }
            for ((si, strips) in surfaces.withIndex()) for ((k, ls) in strips.withIndex()) withClue("band $b ${listOf("fast", "mid", "slow")[si]} strip ${k + 1}") {
                // one height: the floor's top plus the strip's thickness (0.1 to 0.3 mm, front to back)
                ls.map { it[0] }.distinct() shouldBe listOf(Math.round((floorTop + stripH(k + 1)) * 1000) / 1000.0)
                // the speed: 300, 150, 75 mm/s; the fan 100 %
                ls.map { it[5] }.distinct() shouldBe listOf(300.0 / (1 shl si))
                ls.all { Math.abs(it[8] - 100.0) < 0.5 } shouldBe true
                // ten lines, each 60 mm long
                ls.size shouldBe 10
                ls.all { Math.abs(Math.abs(it[3] - it[1]) - 60.0) < 0.002 } shouldBe true
                // the bead by the filament per mm: a 0.44 mm line at the strip's thickness, declared 0.44 wide
                val a = ls.sumOf { it[6] } / ls.sumOf { Math.abs(it[3] - it[1]) } * area
                a shouldBe (beadOf(0.44, stripH(k + 1)) plusOrMinus 0.0005)
                ls.map { it[7] }.distinct() shouldBe listOf(0.44)
            }
            // left to right FAST, MID, SLOW; front to back the strips, thin to thick
            val xs = surfaces.map { st -> st.flatten().minOf { minOf(it[1], it[3]) } }
            (xs[0] < xs[1] && xs[1] < xs[2]) shouldBe true
            for (st in surfaces) for (k in 1 until 6) (st[k].minOf { it[2] } > st[k - 1].maxOf { it[2] }) shouldBe true
            // terraced: this band's step behind the band before's
            val front = surfaces.flatten().flatten().minOf { it[2] }; val back = surfaces.flatten().flatten().maxOf { it[2] }
            (front > lastBack) shouldBe true
            lastBack = back
            // nothing above the floor within its FAST surface but its strips
            val box = surfaces[0].flatten().let { ls -> doubleArrayOf(ls.minOf { minOf(it[1], it[3]) }, ls.minOf { it[2] }, ls.maxOf { maxOf(it[1], it[3]) }, ls.maxOf { it[2] }) }
            cells.filterKeys { !it.startsWith("melt $b.") }.values.flatten().none { it[0] > floorTop + 1e-6 && (it[1] + it[3]) / 2 > box[0] && (it[1] + it[3]) / 2 < box[2] && (it[2] + it[4]) / 2 > box[1] && (it[2] + it[4]) / 2 < box[3] } shouldBe true
            // the floor, directly under the strips: lines front to back (across the strips') one bead spacing apart, the
            // spacing worked out here: 0.44 - 0.2 (1 - pi/4) = 0.397 mm
            val floor = cells.getValue("melt floor $b.fast")
            floor.map { it[0] }.distinct() shouldBe listOf(floorTop)
            val fLines = floor.filter { Math.abs(it[1] - it[3]) < 1e-6 && Math.abs(it[4] - it[2]) > 10 }.map { it[1] }.sorted()
            (fLines.size > 100) shouldBe true
            fLines.zipWithNext { p, q -> q - p }.all { Math.abs(it - (0.44 - 0.2 * (1 - Math.PI / 4))) < 0.005 } shouldBe true
            floor.none { Math.abs(it[2] - it[4]) < 1e-6 && Math.abs(it[3] - it[1]) > 10 && minOf(it[2], it[4]) > box[1] + 1.0 && maxOf(it[2], it[4]) < box[3] - 1.0 } shouldBe true
            // the floor's lines span the strips: from the first strip line's x to the last's
            (fLines.first() < box[0] + 1.0 && fLines.last() > box[2] - 1.0) shouldBe true
            // under the floor, the body: a grid of lines about 2 mm apart both ways
            val body = cells.getValue("melt body $b.fast").filter { Math.abs(it[0] - (floorTop - 0.2)) < 1e-6 }
            val inner = body.filter { minOf(it[1], it[3]) > box[0] + 1.0 && maxOf(it[1], it[3]) < box[2] - 1.0 || minOf(it[2], it[4]) > box[1] + 1.0 && maxOf(it[2], it[4]) < box[3] - 1.0 }
            for (along in listOf(true, false)) {
                val ls = if (along) inner.filter { Math.abs(it[2] - it[4]) < 1e-6 && Math.abs(it[3] - it[1]) > 30 }.map { it[2] } else inner.filter { Math.abs(it[1] - it[3]) < 1e-6 && Math.abs(it[4] - it[2]) > 10 }.map { it[1] }
                (ls.size > 8) shouldBe true
                ls.sorted().zipWithNext { p, q -> q - p }.all { Math.abs(it - 2.0) < 0.15 } shouldBe true
            }
            // the bodies: from the bases' top (z 0.8) to the layer below the floor, every layer
            cells.getValue("melt body $b.fast").map { Math.round(it[0] * 1000) }.distinct().sorted() shouldBe (4..Math.round(floorTop * 5).toInt() - 1).map { it * 200L }
        }
        // the strips printed last on their layer, each surface's thinnest first
        val order = meltText.lines().filter { it.startsWith("; CELL: ") || it.startsWith("; CHANGE_LAYER") || it.startsWith(";LAYER_CHANGE") }
        for (b in 1..4) {
            val first = order.indexOf("; CELL: melt $b.fast.1")
            val layerEnd = (first until order.size).firstOrNull { !order[it].startsWith("; CELL: melt $b.") } ?: order.size
            order.subList(first, layerEnd) shouldBe listOf("fast", "mid", "slow").flatMap { sf -> (1..6).map { "; CELL: melt $b.$sf.$it" } }
            (layerEnd == order.size || !order[layerEnd].startsWith("; CELL: ")) shouldBe true
        }
        // no ruler post on the melt plate
        meltText shouldNotContain "; CELL: post"
    }

    "melt strips by the g-code alone: every travel past a strip at least the lift above every strip laid" {
        // walking the moves: strips laid so far (center line, top); every XY move after one, sampled, against those within half
        // a line (0.22 mm) of the nozzle; worked out here, not by the plate's check
        val laid = ArrayList<DoubleArray>(); var cell: String? = null; var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        var worstTravel = Double.MAX_VALUE; var travels = 0; var crossings = 0
        fun dist(px: Double, py: Double, s0: DoubleArray): Double { val dx = s0[2] - s0[0]; val dy = s0[3] - s0[1]; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((px - s0[0]) * dx + (py - s0[1]) * dy) / l2).coerceIn(0.0, 1.0); return Math.hypot(px - s0[0] - t * dx, py - s0[1] - t * dy) }
        for (l in meltText.lines()) {
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (!l.startsWith("G1 ")) continue
            fun num(c: Char) = Regex("$c(-?[0-9.]+)").find(l.substringAfter("G1 "))?.groupValues?.get(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            val xy = !x.isNaN() && (nx != x || ny != y)
            if (xy && e <= 0 && laid.isNotEmpty()) {
                travels++
                // leaving the line just laid is not crossing it: from 0.3 mm on
                val d = Math.hypot(nx - x, ny - y); val n = Math.ceil(d / 0.1).toInt()
                for (t in 0..n) { val f = t.toDouble() / n; if (f * d < 0.3) continue
                    val px = x + (nx - x) * f; val py = y + (ny - y) * f; val pz = z + (nz - z) * f
                    for (s0 in laid) if (dist(px, py, s0) < 0.22) { worstTravel = minOf(worstTravel, pz - s0[4]); crossings++ } }
            }
            if (xy && e > 0 && cell?.matches(Regex("melt \\d\\.(fast|mid|slow)\\.\\d")) == true) laid += doubleArrayOf(x, y, nx, ny, nz)
            x = nx; y = ny; z = nz
        }
        // travels do cross strips (from one surface to the next, to the park): the measurement is not empty
        (travels > 0 && crossings > 0) shouldBe true
        // the lift above a strip: at least the tool's (the travels that cross one), never dragging across one
        withClue("worst travel clearance $worstTravel") { (worstTravel >= s.zHopUm / 1000.0 - 0.002) shouldBe true }
    }

    "the melt step's section (render-plate --section) from the g-code: strips at their floor plus their thickness; the FAST-to-MID travel the lift above them, and short of it without the lift (control)" {
        val dir = java.nio.file.Files.createTempDirectory("melt-section")
        fun sectionOf(text: String, name: String): Pair<Double, List<String>> {
            val g = dir.resolve("$name.gcode").also { java.nio.file.Files.writeString(it, text) }
            val log = ArrayList<String>()
            return plymeld.check.plate.PlateSection.render(g, reviewRender("melt_section_$name.png"), 1) { log += it } to log
        }
        val (gap, log) = sectionOf(meltText, "plate")
        // band 1's floor top z 1.6 (0.6 base + 1 mm body); its strips 0.10 to 0.30 mm thick above it, ten lines each
        log.joinToString("\n") shouldContain "floor top z 1.60"
        for ((k, hh) in listOf(0.10, 0.14, 0.18, 0.22, 0.26, 0.30).withIndex()) log.joinToString("\n") shouldContain "strip ${k + 1}: z %.2f, h %.2f mm, 10 lines".format(java.util.Locale.ROOT, 1.6 + hh, hh)
        gap shouldBe (s.zHopUm / 1000.0 plusOrMinus 0.002)
        // without the plate's own lift the writer clears the strips by itself (Clearance): the same gap
        val (noLift, _) = sectionOf(CalibrationPlate(meltSpec, without = setOf("meltnolift")).gcode(s, setup.foreignTemplateVars, "test"), "nolift")
        withClue("the travel's gap without the plate's lift: $noLift") { noLift shouldBe (s.zHopUm / 1000.0 plusOrMinus 0.002) }
        // control: the g-code with those lifts taken out (the travel at the strip it leaves) is short of it
        val (dropped, _) = sectionOf(plymeld.core.calibrate.MeltControls.withoutStripLifts(CalibrationPlate(meltSpec, without = setOf("meltnolift")).gcode(s, setup.foreignTemplateVars, "test")), "droppedlift")
        withClue("the travel's gap with the lifts taken out: $dropped") { (dropped < s.zHopUm / 1000.0 - 0.05) shouldBe true }
    }

    "melt plate by the g-code alone: each band's wait (M109) at its first layer, parked, then its floor and its strips; nothing printed before it is reached" {
        // walking the g-code: every M104/M109 for the tool, and the z of the extrusion before and after it
        val ls = meltText.lines()
        val firstLayer = ls.indexOfFirst { it.startsWith("; CHANGE_LAYER") || it.startsWith(";LAYER_CHANGE") }
        val lastCell = ls.indexOfLast { it == "; CELL END" }
        var z = 0.0; var lastExtrudeZ = Double.NaN
        val cmds = ArrayList<Triple<String, Double, Int>>()        // the command, the z extruded at before it, its line
        for ((i, l) in ls.withIndex()) {
            Regex("G1 .*Z([0-9.]+).*").matchEntire(l)?.let { z = it.groupValues[1].toDouble() }
            if (Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(l)) lastExtrudeZ = z
            if ((l.startsWith("M104 S") || l.startsWith("M109 S")) && i > firstLayer && i < lastCell) cmds += Triple(l, lastExtrudeZ, i)
        }
        fun nextExtrudeZ(from: Int): Double { var zz = Double.NaN
            for (l in ls.drop(from)) { Regex("G1 .*Z([0-9.]+).*").matchEntire(l)?.let { zz = it.groupValues[1].toDouble() }; if (Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(l)) return zz }
            return Double.NaN }
        // the plate's own temperature commands, between its first layer and its last line: only waits
        val plate = cmds
        plate.none { it.first.startsWith("M104") } shouldBe true
        for ((b, t) in listOf(2 to 215, 3 to 210, 4 to 205)) withClue("band $b") {
            val wait = plate.single { it.first.startsWith("M109 S$t ") }
            // after the band before's strips (its thickest, 0.3 mm on its floor, last), before its own first layer: the first
            // layer after it is its floor, one below its strips' layer
            wait.second shouldBe (meltTop(b - 1) - 0.2 + 0.3 plusOrMinus 1e-6)
            nextExtrudeZ(wait.third) shouldBe ((meltTop(b) - 0.2) plusOrMinus 1e-6)
            // parked: the last move before the wait is the travel to the park, in front of the plate (below its every line)
            val park = ls.subList(0, wait.third).last { Regex("G1 X[-0-9.]+ Y[-0-9.]+ F.*").matches(it) }
            val parkY = Regex("Y([-0-9.]+)").find(park)!!.groupValues[1].toDouble()
            (parkY < meltLines(meltText).values.flatten().minOf { minOf(it[2], it[4]) }) shouldBe true
        }
    }

    "terraced melt test controls: MID at FAST's speed, a strip too wide or too high, a second read layer, the floor left out, a second solid layer, a read layer before its wait, a step one layer low, a travel without its lift, strips thickest first, the fan off (and on when off) are caught" {
        fun says(c: String) = CalibrationCheck.check(meltSpec, CalibrationPlate(meltSpec, without = setOf(c)).gcode(s, setup.foreignTemplateVars, "test"))
        // band 2 (215 C) waited for only after its read layer: its floor and strips print before it is reached; the commanded
        // temperature is right all along, so only the wait rule can see it
        says("meltwaitlate").let { p -> p.any { it.contains("printed before its temperature, 215 C, was reached") } shouldBe true; p.none { it.contains("not 215 C") } shouldBe true }
        // band 2's step one layer low: its strips on its floor's layer, its floor a layer lower
        says("meltstep").any { it.contains("band 2's FAST surface's strip 1 at z 1.9, not z 2.1") } shouldBe true
        says("meltstep").any { it.contains("'melt floor 2.fast' on layers at z 1.8, not z 2.0") } shouldBe true
        says("meltmidfast").any { it.contains("MID surface's strip") && it.contains("not 150") } shouldBe true
        // a strip 0.05 mm wider than the plate's 0.44 mm line: its filament per mm and its declared width
        says("meltwidth").let { p -> withClue(p) { p.any { it.contains("FAST surface's strip 3 lays") } shouldBe true; p.any { it.contains("declared 0.490 mm wide, not 0.440") } shouldBe true } }
        // a strip 0.04 mm above its thickness (its filament unchanged): band 1's strip 3, 0.18 mm on the floor at 1.6
        says("meltheight").let { p -> withClue(p) { p.any { it.contains("band 1's FAST surface's strip 3 at z 1.82, not z 1.78") } shouldBe true } }
        says("melt2read").let { p -> withClue(p) { p.any { it.contains("band 1's FAST surface's strip 1 at z 1.7, 1.9, not z 1.7") } shouldBe true } }
        // the floor left out: the grid under the strips
        says("meltnofloor").let { p -> withClue(p) { p.any { it.contains("no floor under band 1's FAST surface") } shouldBe true; p.any { it.contains("no 'melt floor 1.fast' cell") } shouldBe true } }
        // a second solid layer, under the floor: the body is not a grid there
        says("meltsolid").let { p -> withClue(p) { p.any { it.contains(Regex("body's front-to-back lines 0\\.39\\d mm apart, not about 2\\.0")) } shouldBe true } }
        // the nozzle against the strips: a travel from FAST's thickest strip to MID with the writer's own lift only, and FAST's
        // strips thickest first (the travel back across the thicker strip, the thinner ones printed against it)
        // (without the plate's own lifts the writer still clears the strips: Clearance; so the g-code is edited to
        // take the lifts out, and the travel then runs at the strip it leaves)
        CalibrationCheck.check(meltSpec, plymeld.core.calibrate.MeltControls.withoutStripLifts(CalibrationPlate(meltSpec, without = setOf("meltnolift")).gcode(s, setup.foreignTemplateVars, "test"))).let { p -> withClue(p) { p.any { it.contains("a travel at") && it.contains("not at least ${"%.2f".format(java.util.Locale.ROOT, s.zHopUm / 1000.0)} (the travel lift over a strip already laid)") } shouldBe true } }
        says("meltnolift").none { it.contains("the travel lift over a strip already laid") } shouldBe true
        // thickest first: the writer lifts over the thicker strip too, so the same edit takes its lifts out (the check judges the
        // travel; printing a thin strip beside a thicker one is not judged)
        CalibrationCheck.check(meltSpec, plymeld.core.calibrate.MeltControls.withoutStripLifts(CalibrationPlate(meltSpec, without = setOf("meltthickfirst")).gcode(s, setup.foreignTemplateVars, "test")))
            .let { p -> withClue(p) { p.any { it.contains("above 'melt 1.fast.") && it.contains("not at least") } shouldBe true } }
        // the fan: the plate's one M106 (every band at the filament's own) turned off
        CalibrationCheck.check(meltSpec, meltText.replace("M106 S255", "M106 S0")).any { it.contains("fan 0 %, not 100 %") } shouldBe true
        // with the fan off (--melt-fan off): it passes, and the fan turned on in a band is caught
        val off = melted(0.0)
        val offText = CalibrationPlate(off).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(off, offText).shouldBeEmpty()
        offText shouldNotContain "M106 S255"
        val ls = offText.lines().toMutableList(); val layers = ls.indices.filter { ls[it].startsWith("; CHANGE_LAYER") || ls[it].startsWith(";LAYER_CHANGE") }
        ls.add(layers[(meltBands[1].readToUm / h).toInt() - 1] + 1, "M106 S255")
        CalibrationCheck.check(off, ls.joinToString("\n")).any { it.contains("fan 100 %, not 0 %") } shouldBe true
    }

    // a filament melting little (3.5 mm3/s, as a TPU's placeholder): FAST follows it, not the machine, so the rates
    // tested follow the filament's defaults
    "terraced melt test for a slow-melting filament: FAST centered on its flow, the plate passes; FAST at the machine's speed is caught" {
        val slow = melted(maxFlow = 3.5)
        val v = 3.5 / beadOf(0.44, 0.2)
        slow.melt!!.speeds.toList().let { it[0] shouldBe (v plusOrMinus 1e-9); it[1] shouldBe (v / 2 plusOrMinus 1e-9); it[2] shouldBe (v / 4 plusOrMinus 1e-9) }
        val t = CalibrationPlate(slow).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(slow, t).let { withClue(it) { it.shouldBeEmpty() } }
        // the FAST strips' flows, by the g-code: 0.53 to 1.42 x 3.5 mm3/s
        val cells = meltLines(t)
        val area = Math.PI * 1.75 * 1.75 / 4
        for ((k, want) in listOf(1 to 3.5 * beadOf(0.44, 0.1) / beadOf(0.44, 0.2), 6 to 3.5 * beadOf(0.44, 0.3) / beadOf(0.44, 0.2))) {
            val ls = cells.getValue("melt 1.fast.$k").filter { Math.abs(it[3] - it[1]) > 30 }
            (ls.sumOf { it[6] } / ls.sumOf { Math.abs(it[3] - it[1]) } * area * ls[0][5]) shouldBe (want plusOrMinus 0.02)
        }
        // the same plate with FAST at the machine's 300 mm/s (as before): its check works FAST out from the machine
        // and the flow, so the speeds are wrong
        val fast = melted(maxFlow = 3.5, fast = 300.0)
        CalibrationCheck.check(fast, CalibrationPlate(fast).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("FAST surface's strip 1 at 300.00 mm/s, not ${"%.2f".format(java.util.Locale.ROOT, v)}") } shouldBe true
    }

    "the first layer is held to the filament's maximum flow: by the g-code, a first-layer cell past it is caught" {
        // the plate's first layer at 40 mm/s in 0.5 mm lines (0.2 mm): 40 x 0.0914 = 3.66 mm3/s, worked out here
        val first = 0.2 * (0.5 - 0.2 * (1 - Math.PI / 4))
        CalibrationCheck.firstLayerFlow(meltText, 0, 40.0, 4.0, 1.75).shouldBeEmpty()
        // a filament melting 3.5 mm3/s: 40 mm/s is past it
        CalibrationCheck.firstLayerFlow(meltText, 0, 40.0, 3.5, 1.75).any { it.contains("past the filament's maximum flow 3.50") } shouldBe true
        // held: 3.5 / 0.0914 = 38.3 mm/s passes
        val held = CalibrationSpec(0, "test", meltSpec.bands, h, w, 1.75, adhesion = PathValues(3.5 / first, 5000.0, 1.0, 0.8), frame = spec.frame, melt = meltSpec.melt,
            flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstWidthUm = 500, baseLayers = 3, ruler = false)
        CalibrationCheck.firstLayerFlow(CalibrationPlate(held).gcode(s, setup.foreignTemplateVars, "test"), 0, 40.0, 3.5, 1.75).shouldBeEmpty()
        // and past the configured first-layer speed is caught too; absence is not a pass
        CalibrationCheck.firstLayerFlow(meltText, 0, 30.0, 10.0, 1.75).any { it.contains("past the configured first-layer speed") } shouldBe true
        CalibrationCheck.firstLayerFlow(meltText.replace("; CELL: adhesion:", "; CELL: x"), 0, 40.0, 10.0, 1.75).any { it.contains("no first-layer moves") } shouldBe true
    }

    "the melt test's defaults: six thicknesses from layer.height.max down a tenth of the bore, FAST centered on the filament's maximum flow, lines for 2 s and at least ten; the melt test a plate of its own" {
        MeltTerraces.heights(300, 400) shouldBe listOf(100L, 140, 180, 220, 260, 300)
        MeltTerraces.heights(450, 600) shouldBe listOf(150L, 210, 270, 330, 390, 450)
        // the middle thickness 0.2 mm: a 0.44 mm line's bead 0.0794 mm2 (worked out here); 3.5 mm3/s / 0.0794 = 44.07 mm/s; a
        // filament melting 30 mm3/s would need 377.7 mm/s, so the machine's 300 holds it
        MeltTerraces.fastSpeed(300.0, 3.5, 440, 300, 400, 1.0) shouldBe (3.5 / beadOf(0.44, 0.2) plusOrMinus 1e-9)
        MeltTerraces.fastSpeed(300.0, 30.0, 440, 300, 400, 1.0) shouldBe 300.0
        // its strips then bracket it: about 0.5 to 1.4 times it on FAST
        (beadOf(0.44, 0.1) / beadOf(0.44, 0.2)) shouldBe (0.53 plusOrMinus 0.01)
        (beadOf(0.44, 0.3) / beadOf(0.44, 0.2)) shouldBe (1.42 plusOrMinus 0.01)
        // 2 s of 60 mm lines at 300 mm/s: ten (0.2 s each); at 600 mm/s twenty; slower, still ten (at least)
        MeltTerraces.linesFor(300.0) shouldBe 10
        MeltTerraces.linesFor(600.0) shouldBe 20
        MeltTerraces.linesFor(44.0) shouldBe 10
        meltSpec.melt!!.speeds.toList() shouldBe listOf(300.0, 150.0, 75.0)
        // the thickest strip above the next layer is refused (0.3 mm on 0.12 mm layers)
        shouldThrow<IllegalArgumentException> { CalibrationSpec(0, "test", CalibrationSpec.meltBands(205, 220, 5, 100.0, 3 * 120L, 120), 120, w, 1.75,
            adhesion = spec.adhesion, frame = spec.frame, melt = meltSpec.melt, flowRatio = 1.0, retractMm = 0.8, originXUm = 0, originYUm = 0) }.message!! shouldContain "above the next layer"
        // the filament plate's families: not the melt test (nor the motion test, MotionPlate's)
        plymeld.core.calibrate.Part.FILAMENT_TESTS.map { it.key } shouldBe listOf("overhangs", "bridges", "stringing", "line-width", "flow", "fit")
        // a melt test with another family is refused
        shouldThrow<IllegalArgumentException> { melted().let { m -> CalibrationSpec(0, "test", m.bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = m.melt,
            flowRatio = 1.0, retractMm = 0.8, originXUm = 0, originYUm = 0, ledges = plymeld.core.calibrate.Ledges(doubleArrayOf(0.5), 60.0, 5000.0)) } }.message!! shouldContain "a plate of its own"
        // the melt plate's bands: band 1's body 1 mm in whole layers (5 at 0.2 mm, 4 at 0.28 mm), each later band 2 layers
        plymeld.core.calibrate.PlateGeometry.meltBodyLayers(200) shouldBe 5
        plymeld.core.calibrate.PlateGeometry.meltBodyLayers(280) shouldBe 4
        meltBands.map { it.tempC } shouldBe listOf(220, 215, 210, 205)
        meltBands.map { it.readToUm } shouldBe listOf(1_800L, 2_200, 2_600, 3_000)
        meltBands.all { it.toUm == it.readToUm } shouldBe true
    }

    // the same plate with the flow-ratio patches: 0.94, 0.97, 1 (marked), 1.03, 1.06
    val flowed = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        flow = plymeld.core.calibrate.FlowPatches(doubleArrayOf(0.94, 0.97, 1.0, 1.03, 1.06), 2, 80.0, 5000.0, 4))
    val flowText = CalibrationPlate(flowed).gcode(s, setup.foreignTemplateVars, "test")

    "flow patches: each at its own ratio, lines at the bead spacing; the plate passes" {
        CalibrationCheck.check(flowed, flowText).shouldBeEmpty()
        // independently of the check: filament per mm of patch 5's lines over patch 1's is the ratios' quotient
        fun ePerMm(cell: String): Double {
            var x = Double.NaN; var y = Double.NaN; var inCell = false; var e = 0.0; var len = 0.0
            for (l in flowText.lines()) {
                if (l.startsWith("; CELL: ")) { inCell = l == "; CELL: $cell"; continue }
                val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E([0-9.]+).*").matchEntire(l)
                val g = Regex("G1 X([-0-9.]+) Y([-0-9.]+).*").matchEntire(l)
                if (m != null && inCell && !x.isNaN()) { val nx = m.groupValues[1].toDouble(); val ny = m.groupValues[2].toDouble(); len += Math.hypot(nx - x, ny - y); e += m.groupValues[3].toDouble() }
                if (g != null) { x = g.groupValues[1].toDouble(); y = g.groupValues[2].toDouble() }
            }
            return e / len
        }
        (ePerMm("flow 1.5") / ePerMm("flow 1.1")) shouldBe (1.06 / 0.94 plusOrMinus 0.002)
        // five patches on each of the first five layers, each a wall and its lines
        Regex("; CELL: (adhesion:)?flow \\d").findAll(flowText).count() shouldBe 5 * 5 * 2
    }

    "controls: a patch's line made richer, the marker left out, a patch at the wrong spacing are caught" {
        val lines = flowText.lines().toMutableList()
        val at = lines.indices.last { lines[it] == "; CELL: flow 1.2" }
        val k = (at until lines.size).first { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(lines[it]) }
        val e = lines[k].substringAfter(" E").substringBefore(' ')
        lines[k] = lines[k].replace(" E$e", " E" + String.format(java.util.Locale.ROOT, "%.5f", e.toDouble() * 1.03))
        CalibrationCheck.check(flowed, lines.joinToString("\n")).any { it.contains("'flow 1.2'") } shouldBe true
        CalibrationCheck.check(flowed, CalibrationPlate(flowed, without = setOf("mark")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("no marker") } shouldBe true
        // the same plate drawn for 0.42 mm lines but checked as 0.44: its lines are closer than the bead spacing
        val narrow = CalibrationSpec(0, "test", bands, h, 420, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
            flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, flow = flowed.flow)
        CalibrationCheck.check(flowed, CalibrationPlate(narrow).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("flow patch") } shouldBe true
    }

    // the same plate with the overhang ledges: shares 0.35 .. 0.95
    fun ledged(shares: DoubleArray) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        ledges = plymeld.core.calibrate.Ledges(shares, 60.0, 5000.0))
    val ledgeSpec = ledged(doubleArrayOf(0.35, 0.5, 0.65, 0.8, 0.95))

    "overhang ledges: the plate passes; their lean left out, or other shares, are caught" {
        CalibrationCheck.check(ledgeSpec, CalibrationPlate(ledgeSpec).gcode(s, setup.foreignTemplateVars, "test")).shouldBeEmpty()
        CalibrationCheck.check(ledgeSpec, CalibrationPlate(ledgeSpec, without = setOf("lean")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("ledge") } shouldBe true
        // the plate drawn with ledge 3 one step steeper (0.65 -> 0.8), checked as specified
        val other = CalibrationPlate(ledged(doubleArrayOf(0.35, 0.5, 0.8, 0.8, 0.95))).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(ledgeSpec, other).any { it.contains("ledge 3") } shouldBe true
    }

    // the same plate with the bridges: spans 8 .. 32 mm, decks at 1 and 0.85
    fun bridged(spans: LongArray) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        bridges = plymeld.core.calibrate.Bridges(spans, doubleArrayOf(1.0, 0.85), 30.0, 5000.0))
    val bridgeSpec = bridged(longArrayOf(8_000, 14_000, 20_000, 26_000, 32_000))
    val bridgeText = CalibrationPlate(bridgeSpec).gcode(s, setup.foreignTemplateVars, "test")

    "bridges: the plate passes; the decks left out, another span, or a line in a span are caught" {
        CalibrationCheck.check(bridgeSpec, bridgeText).shouldBeEmpty()
        // a deck on every band's top read layer, each at its own flow
        Regex("; CELL: deck 1").findAll(bridgeText).count() shouldBe bands.size
        CalibrationCheck.check(bridgeSpec, CalibrationPlate(bridgeSpec, without = setOf("decks")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("deck") } shouldBe true
        // terraced: the top band's decks where the untiered row had them, or half a deck over the band below's, are caught
        CalibrationCheck.check(bridgeSpec, CalibrationPlate(bridgeSpec, without = setOf("untiered")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("terrace strip") } shouldBe true
        CalibrationCheck.check(bridgeSpec, CalibrationPlate(bridgeSpec, without = setOf("deckoverlap")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("lies over band") } shouldBe true
        // drawn with span 3 at 21 mm, checked as 20
        CalibrationCheck.check(bridgeSpec, CalibrationPlate(bridged(longArrayOf(8_000, 14_000, 21_000, 26_000, 32_000))).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("span 3") } shouldBe true
        // a line of another cell laid in the middle of span 4 on one layer, at a pier's depth: something under the decks
        // (as a pier line it would read as a seventh pier, also caught, but before the spans are probed)
        val lines = bridgeText.lines().toMutableList()
        val at = lines.indices.filter { lines[it] == "; CELL: pier" }.let { it[it.size / 2] }
        val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E.*").matchEntire(lines.subList(at, lines.size).first { it.matches(Regex("G1 X[-0-9.]+ Y[-0-9.]+ E.*")) })!!
        val y = m.groupValues[2].toDouble()
        val spanMid = CalibrationPlate(bridgeSpec).let { p -> (100_000 + (p.pierX(3).second + p.pierX(4).first) / 2) / 1000.0 }     // the origin and the middle of span 4
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        lines.addAll(end + 1, listOf("; CELL: post", "G1 X${spanMid - 1} Y$y F1200", "G1 X${spanMid + 1} Y$y E0.07", "; CELL END"))
        CalibrationCheck.check(bridgeSpec, lines.joinToString("\n")).any { it.contains("in span 4") } shouldBe true
    }

    "bridges: terraced, by the g-code alone: band by band the front decks step back and the back decks step forward, none over another" {
        // each deck occurrence's y range from its own lines (the band: its order in the file); no figure of the plate's
        val ranges = HashMap<String, MutableList<DoubleArray>>()
        var cell: String? = null
        for (l in bridgeText.lines()) {
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: ").takeIf { it.startsWith("deck ") }; cell?.let { ranges.getOrPut(it) { ArrayList() } += doubleArrayOf(Double.MAX_VALUE, -Double.MAX_VALUE) }; continue }
            val c = cell ?: continue
            val m = Regex("G1 X[-0-9.]+ Y([-0-9.]+) E.*").matchEntire(l) ?: continue
            val r = ranges.getValue(c).last(); val y = m.groupValues[1].toDouble()
            r[0] = minOf(r[0], y); r[1] = maxOf(r[1], y)
        }
        val front = ranges.getValue("deck 1"); val back = ranges.getValue("deck 2")
        front.size shouldBe bands.size; back.size shouldBe bands.size
        val step = front[1][0] - front[0][0]
        (step > 4.0) shouldBe true                                   // about a deck's depth (5 mm)
        for (k in 1 until bands.size) {
            (front[k][0] - front[k - 1][0]) shouldBe (step plusOrMinus 0.002)
            (back[k - 1][1] - back[k][1]) shouldBe (step plusOrMinus 0.002)
        }
        // no two decks' line ranges overlap in y (each deck is its own step from above)
        val all = front + back
        for (i in all.indices) for (j in i + 1 until all.size) (minOf(all[i][1], all[j][1]) < maxOf(all[i][0], all[j][0])) shouldBe true
        // the innermost (top band's) decks: the gap between their lines' ends is 5 mm plus a line (half a line each side)
        (back.last()[0] - front.last()[1]) shouldBe ((5.0 + w / 1000.0) plusOrMinus 0.01)
    }

    // the same plate with the stringing ring: hops at 0.4, 0.8, 1.2 mm (four posts)
    fun withPosts(lengths: DoubleArray) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        strings = plymeld.core.calibrate.Strings(lengths, 80.0, 5000.0))
    val postSpec = withPosts(doubleArrayOf(0.4, 0.8, 1.2))

    "stringing ring: each hop retracts its own length, round the ring in order; another length, the lengths reversed, are caught" {
        val t = CalibrationPlate(postSpec).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(postSpec, t).shouldBeEmpty()
        // independently: before post 4 (hop 3) on the first layer above the adhesion layer (its first ring: each ring is a
        // cell of its own, the hop comes before the outermost), a 1.2 mm retraction
        val ls = t.lines()
        val at = ls.indices.filter { ls[it] == "; CELL: post 4" }.first()
        ls.drop(at).first { it.startsWith("G1 E-") } shouldContain "E-1.2"
        // the posts, by the g-code alone: 4 of them, neighbors 19 mm apart center to center (4 mm posts, 15 mm gaps), the
        // posts' centers from their cells' extents on one layer
        val centers = (1..4).map { j ->
            val pts = ArrayList<Pair<Double, Double>>(); var inCell = false; var layerSeen = 0
            for (l in ls) {
                if (l.startsWith("; CHANGE_LAYER") || l.startsWith(";LAYER_CHANGE")) layerSeen++
                if (layerSeen != 6) continue
                if (l.startsWith("; CELL: ")) { inCell = l == "; CELL: post $j"; continue }
                Regex("G1 X([-0-9.]+) Y([-0-9.]+) E.*").matchEntire(l)?.let { if (inCell) pts += it.groupValues[1].toDouble() to it.groupValues[2].toDouble() }
            }
            ((pts.minOf { it.first } + pts.maxOf { it.first }) / 2) to ((pts.minOf { it.second } + pts.maxOf { it.second }) / 2)
        }
        for (j in 0 until 4) { val a = centers[j]; val b = centers[(j + 1) % 4]; Math.hypot(b.first - a.first, b.second - a.second) shouldBe (19.0 plusOrMinus 0.02) }
        // the open gap (post 4 back to post 1) at the front: its middle the lowest y of the four gaps
        val mids = (0 until 4).map { (centers[it].second + centers[(it + 1) % 4].second) / 2 }
        mids.indexOf(mids.min()) shouldBe 3
        // the plate drawn with hop 2 at 1.0 mm, checked as 0.8; drawn with the lengths reversed
        CalibrationCheck.check(postSpec, CalibrationPlate(withPosts(doubleArrayOf(0.4, 1.0, 1.2))).gcode(s, setup.foreignTemplateVars, "test"))
            .any { it.contains("before stringing post 3 (hop 2)") } shouldBe true
        CalibrationCheck.check(postSpec, CalibrationPlate(postSpec, without = setOf("ringreverse")).gcode(s, setup.foreignTemplateVars, "test"))
            .any { it.contains("before stringing post 2 (hop 1)") } shouldBe true
    }

    "the default stringing lengths, tube widths and flow ratios" {
        // the defaults the command uses, against the numbers the user gave (a 0.4 mm nozzle: 0.25 to 0.75 mm in 0.05 steps)
        plymeld.core.calibrate.Tubes.defaultWidths(400) shouldBe listOf(250L, 300, 350, 400, 450, 500, 550, 600, 650, 700, 750)
        plymeld.core.calibrate.Tubes.defaultWidths(600) shouldBe listOf(380L, 450, 530, 600, 680, 750, 830, 900, 980, 1050, 1130)
        plymeld.core.calibrate.Strings.defaultLengths() shouldBe listOf(0.4, 0.6, 0.8, 1.0, 1.2, 1.4, 1.6)
        plymeld.core.calibrate.FlowPatches.defaultRatios(1.0) shouldBe listOf(0.94, 0.96, 0.98, 1.0, 1.02, 1.04, 1.06)
    }

    // the same plate with the line-width tubes: 0.35 .. 0.65 mm
    val tubeSpec = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        tubes = plymeld.core.calibrate.Tubes(longArrayOf(350, 400, 450, 500, 550, 600, 650), 60.0, 5000.0, 10))

    "line-width tubes: each at its own width; all at the plate's width is caught" {
        val t = CalibrationPlate(tubeSpec).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(tubeSpec, t).shouldBeEmpty()
        // independently: filament per mm of tube 7 over tube 1 is their beads' ratio (0.65 and 0.35 mm lines, 0.2 mm layers)
        fun ePerMm(cell: String): Double {
            var x = Double.NaN; var y = Double.NaN; var inCell = false; var e = 0.0; var len = 0.0
            for (l in t.lines()) {
                if (l.startsWith("; CELL: ")) { inCell = l == "; CELL: $cell"; continue }
                val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E([0-9.]+).*").matchEntire(l)
                if (m != null && inCell && !x.isNaN()) { len += Math.hypot(m.groupValues[1].toDouble() - x, m.groupValues[2].toDouble() - y); e += m.groupValues[3].toDouble() }
                Regex("G1 X([-0-9.]+) Y([-0-9.]+).*").matchEntire(l)?.let { x = it.groupValues[1].toDouble(); y = it.groupValues[2].toDouble() }
            }
            return e / len
        }
        fun bead(wd: Double) = 0.2 * (wd - 0.2 * (1 - Math.PI / 4))
        (ePerMm("tube 7") / ePerMm("tube 1")) shouldBe (bead(0.65) / bead(0.35) plusOrMinus 0.01)
        CalibrationCheck.check(tubeSpec, CalibrationPlate(tubeSpec, without = setOf("widths")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("tube") } shouldBe true
    }

    // the same plate with the fit test: holes 5.0 .. 5.4 mm, a 5 mm peg
    fun fitted(holes: LongArray) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        fit = plymeld.core.calibrate.Fit(5_000, holes, 60.0, 5000.0, 12, 30))
    val fitSpec = fitted(longArrayOf(5_000, 5_100, 5_200, 5_300, 5_400))

    "fit test: holes and peg as drawn; the holes at the peg's size, or one hole 0.1 mm off, are caught" {
        CalibrationCheck.check(fitSpec, CalibrationPlate(fitSpec).gcode(s, setup.foreignTemplateVars, "test")).shouldBeEmpty()
        CalibrationCheck.check(fitSpec, CalibrationPlate(fitSpec, without = setOf("clearance")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("hole") } shouldBe true
        CalibrationCheck.check(fitSpec, CalibrationPlate(fitted(longArrayOf(5_000, 5_100, 5_300, 5_300, 5_400))).gcode(s, setup.foreignTemplateVars, "test"))
            .any { it.contains("hole 3 is") } shouldBe true
    }

    // the flow patches and the fit block on pedestals: the patches' solid layers ending at layer 24, the block from layer 15
    fun raised(patchFrom: Int, fitFrom: Int) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame, melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000,
        flow = plymeld.core.calibrate.FlowPatches(doubleArrayOf(0.94, 0.97, 1.0, 1.03, 1.06), 2, 80.0, 5000.0, 4, fromLayers = intArrayOf(patchFrom)),
        fit = plymeld.core.calibrate.Fit(5_000, longArrayOf(5_000, 5_100, 5_200), 60.0, 5000.0, 12, 27, fromLayer = fitFrom))
    val raisedSpec = raised(20, 15)

    "pedestals: the patches and the fit block start where specified; one started a layer early is caught" {
        val t = CalibrationPlate(raisedSpec).gcode(s, setup.foreignTemplateVars, "test")
        CalibrationCheck.check(raisedSpec, t).shouldBeEmpty()
        // independently: the patches' first solid layer is layer 20 (z 4.2 mm), the block's layer 15 (z 3.2 mm)
        // the height the cell's first line is laid at, from the moves' own Z
        fun firstZ(cell: String): Double {
            var z = 0.0; var inCell = false
            for (l in t.lines()) {
                Regex("G1 .*Z([0-9.]+).*").matchEntire(l)?.let { z = it.groupValues[1].toDouble() }
                if (l == "; CELL: $cell") inCell = true
                if (inCell && Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(l)) return z
            }
            return Double.NaN
        }
        firstZ("flow 1.1") shouldBe (4.2 plusOrMinus 1e-9)
        firstZ("fit block") shouldBe (3.2 plusOrMinus 1e-9)
        firstZ("pedestal 1.1") shouldBe (0.4 plusOrMinus 1e-9)
        CalibrationCheck.check(raisedSpec, CalibrationPlate(raised(19, 15)).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("flow") || it.contains("pedestal") } shouldBe true
        CalibrationCheck.check(raisedSpec, CalibrationPlate(raised(20, 14)).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("fit block") || it.contains("pedestal") } shouldBe true
    }
    "the plate's page: levelling first, the conditions, the melt test's strips and flows per band, how to read it, what to enter" {
        val plate = CalibrationPlate(meltSpec)
        val md = plymeld.core.calibrate.PlateInstructions.markdown(meltSpec, plate,
            plymeld.core.calibrate.PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 100.0, 0.8,
                maxFlowKey = "hardware.filament.pla test.melt.flow.max", maxFlowNow = "15 mm3/s", printTempC = 214,
                maxFlowEnterKey = "hardware.filament.\"pla test\".machine.p.melt.flow.max"), "plate.png")
        md shouldContain "Level the bed"
        md shouldContain "| pressure advance | 0.035 s |"
        md shouldContain "![The plate, from above and from the front](plate.png)"
        md shouldContain "**Run the melt test first**"
        md shouldContain "## How to read the melt test"
        md shouldContain "**Gaps on FAST or MID, but not at the same thickness on SLOW** = past the melt limit at that flow"
        md shouldContain "**Gaps at the same thickness on all three surfaces** (and in every band) = that thickness itself"
        // white on white: how to see the floor through a gap
        md shouldContain "**raking light**"
        // a table per band: each strip's thickness, its height and its flow on each surface, worked out here (speed x bead x
        // flow ratio 1), to one decimal, trailing zeros dropped as the page writes them
        fun f1(v: Double) = "%.1f".format(java.util.Locale.ROOT, v).trimEnd('0').trimEnd('.')
        for (b in meltBands) md shouldContain "### Band ${b.index + 1}: ${b.tempC} C, fan 100 %"
        md shouldContain "| 1 | 0.1 | 1.7 | ${f1(300 * beadOf(0.44, 0.1))} |  | ${f1(150 * beadOf(0.44, 0.1))} |  | ${f1(75 * beadOf(0.44, 0.1))} |  |"
        md shouldContain "| 6 | 0.3 | 1.9 | ${f1(300 * beadOf(0.44, 0.3))} |"
        md shouldContain "| 6 | 0.3 | 3.1 | ${f1(300 * beadOf(0.44, 0.3))} |"
        // what to enter: the one setting, and the band nearest the print temperature (214 C: band 2, 215 C)
        md shouldContain "`hardware.filament.pla test.melt.flow.max` (now 15 mm3/s)"
        // a reading is entered for the machine it printed on (the melt rate scoped to the machine)
        md shouldContain "Enter this plate's reading for the machine it printed on, `hardware.filament.\"pla test\".machine.p.melt.flow.max`"
        md shouldContain "(its temperature.print, 214 C: band 2, 215 C)"
        // the melt plate: its title, no post, the heights and where each wait falls
        md shouldContain "# Melt plate: pla test on a printer"
        md shouldContain "There is no ruler post on this plate"
        md shouldNotContain "**The post**"
        md shouldContain "Band 1's step is a body 1 mm tall (5 layers) on the base, its top layer the step's **solid floor** (top at z 1.6 mm), then its strips."
        md shouldContain "| 1 | 0.6-1.8 | 220 C | 100 % | the start, before the first layer (z 0.2), and again at z 0.8 | 1.6 | 1.7, 1.74, 1.78, 1.82, 1.86, 1.9 |"
        md shouldContain "| 2 | 1.8-2.2 | 215 C | 100 % | 2 | 2 | 2.1, 2.14, 2.18, 2.22, 2.26, 2.3 |"
        md shouldContain "| 4 | 2.6-3 | 205 C | 100 % | 2.8 | 2.8 | 2.9, 2.94, 2.98, 3.02, 3.06, 3.1 |"
        // FAST held by the machine (30 mm3/s would center it at 377.7 mm/s): the page says the top of the range is the machine's
        md shouldContain "**the top of the range is the machine's limit, not the filament's**"
        // a plate without the melt test, its maximum flow still the placeholder: the page recommends the melt plate first
        val rmd = plymeld.core.calibrate.PlateInstructions.markdown(flowed, CalibrationPlate(flowed),
            plymeld.core.calibrate.PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 100.0, 0.8, maxFlowPlaceholder = true), "plate.png")
        rmd shouldContain "**Recommended: run the melt test first.**"
        rmd shouldContain "`plymeld calibrate melt ...`"
        rmd shouldContain "# Filament plate: pla test"
        // measured (not the placeholder): no recommendation
        plymeld.core.calibrate.PlateInstructions.markdown(flowed, CalibrationPlate(flowed),
            plymeld.core.calibrate.PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 100.0, 0.8), "plate.png") shouldNotContain "Recommended: run the melt test first"
        // the picture's title names only the parts on the plate
        plymeld.core.calibrate.PlateInstructions.pictureTitle(spec) shouldBe "From above (a layer of band 1)"
        plymeld.core.calibrate.PlateInstructions.pictureTitle(meltSpec) shouldBe "From above (the bases' top layer, notched; every melt step's top)"
        plymeld.core.calibrate.PlateInstructions.pictureTitle(flowed) shouldBe "From above (a layer of band 1; each row of flow patches at its top)"
        // with the flow patches: a row for each, the marked one, and how to read them
        val fmd = plymeld.core.calibrate.PlateInstructions.markdown(flowed, CalibrationPlate(flowed),
            plymeld.core.calibrate.PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 100.0, 0.8), "plate.png")
        fmd shouldContain "| 3 (marked: current) | 1 |"
        fmd shouldContain "| 5 | 1.06 |"
        fmd shouldContain "## How to read the flow patches"
    }

    // every family of the filament plate on one plate, its first layer in [firstW] µm lines; the patches and the block on the
    // bed ([patchFrom], [fitFrom] 0: their first layer theirs) or on pedestals ([lastSpan]: the bridges' last span); the melt
    // test is a plate of its own, the motion test too (MotionPlate)
    fun everything(firstW: Long, patchFrom: Int = 0, fitFrom: Int = 0, lastSpan: Long = 32_000) = CalibrationSpec(0, "test", bands, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame,
        melt = null,
        flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstWidthUm = firstW,
        flow = plymeld.core.calibrate.FlowPatches(doubleArrayOf(0.94, 0.97, 1.0, 1.03, 1.06), 2, 80.0, 5000.0, 4, fromLayers = intArrayOf(patchFrom)),
        ledges = ledgeSpec.ledges, bridges = bridged(longArrayOf(8_000, 14_000, 20_000, 26_000, lastSpan)).bridges, strings = postSpec.strings, tubes = tubeSpec.tubes,
        fit = plymeld.core.calibrate.Fit(5_000, longArrayOf(5_000, 5_100, 5_200), 60.0, 5000.0, 12, 27, fromLayer = fitFrom))
    // the lines of each cell on the first layer, read from the g-code text: declared width, filament per mm,
    // and the cell's footprint (its center lines widened by half their width)
    class FirstCell(val widths: MutableSet<Double> = sortedSetOf(), var e: Double = 0.0, var len: Double = 0.0, val box: DoubleArray = doubleArrayOf(1e9, 1e9, -1e9, -1e9))
    fun firstLayer(t: String): Map<String, FirstCell> {
        val ls = t.lines(); val marks = ls.indices.filter { ls[it].startsWith("; CHANGE_LAYER") || ls[it].startsWith(";LAYER_CHANGE") }
        val out = LinkedHashMap<String, FirstCell>(); var cell: String? = null; var wd = Double.NaN; var x = Double.NaN; var y = Double.NaN
        for (l in ls.subList(marks[0], marks[1])) {
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").toDouble(); continue }
            if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
            val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E([0-9.]+).*").matchEntire(l)
            val c = cell
            if (m != null && c != null && !x.isNaN()) {
                val nx = m.groupValues[1].toDouble(); val ny = m.groupValues[2].toDouble()
                val fc = out.getOrPut(c) { FirstCell() }
                fc.widths += wd; fc.e += m.groupValues[3].toDouble(); fc.len += Math.hypot(nx - x, ny - y)
                fc.box[0] = minOf(fc.box[0], x - wd / 2, nx - wd / 2); fc.box[1] = minOf(fc.box[1], y - wd / 2, ny - wd / 2)
                fc.box[2] = maxOf(fc.box[2], x + wd / 2, nx + wd / 2); fc.box[3] = maxOf(fc.box[3], y + wd / 2, ny + wd / 2)
            }
            Regex("G[01] .*X([-0-9.]+) Y([-0-9.]+).*").matchEntire(l)?.let { x = it.groupValues[1].toDouble(); y = it.groupValues[2].toDouble() }
        }
        return out
    }
    // every extrusion line from the second layer on, in order
    fun aboveFirst(t: String): List<String> { val ls = t.lines(); val second = ls.indices.filter { ls[it].startsWith("; CHANGE_LAYER") || ls[it].startsWith(";LAYER_CHANGE") }[1]
        return ls.drop(second).filter { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(it) } }

    "the first layer in the first-layer width (the tubes in their own); every part's outline and every layer above as before" {
        for ((patchFrom, fitFrom) in listOf(0 to 0, 20 to 15)) {
            val wide = everything(500, patchFrom, fitFrom); val same = everything(440, patchFrom, fitFrom)
            val t = CalibrationPlate(wide).gcode(s, setup.foreignTemplateVars, "test")
            val t0 = CalibrationPlate(same).gcode(s, setup.foreignTemplateVars, "test")
            CalibrationCheck.check(wide, t).shouldBeEmpty()
            CalibrationCheck.check(same, t0).shouldBeEmpty()
            // independently of the check: on the first layer each cell's declared width, and its filament per mm the bead
            // model's at that width (0.2 mm high, 1.75 mm filament, flow ratio 1)
            fun ePerMm(wd: Double) = 0.2 * (wd - 0.2 * (1 - Math.PI / 4)) / (Math.PI * 1.75 * 1.75 / 4)
            val cells = firstLayer(t); val before = firstLayer(t0)
            cells.keys shouldBe before.keys
            cells.keys.filter { it.startsWith("adhesion:tube ") }.size shouldBe 7
            for ((c, fc) in cells) {
                val want = when {
                    c.startsWith("adhesion:tube ") -> listOf(0.35, 0.4, 0.45, 0.5, 0.55, 0.6, 0.65)[c.removePrefix("adhesion:tube ").toInt() - 1]
                    else -> 0.5
                }
                withClue(c) {
                    fc.widths shouldBe setOf(want)
                    (fc.e / fc.len) shouldBe (ePerMm(want) plusOrMinus ePerMm(want) * 0.002)
                    // its outline where it was
                    for (i in 0..3) fc.box[i] shouldBe (before.getValue(c).box[i] plusOrMinus 0.002)
                }
            }
            // every layer above the first exactly as before, line for line
            aboveFirst(t) shouldBe aboveFirst(t0)
            aboveFirst(t).size shouldBe aboveFirst(t0).size
            (aboveFirst(t).size > 10_000) shouldBe true
        }
        // control: the comparison sees a layout that moves (a longer last span widens the bridges and moves what is packed beside them)
        aboveFirst(CalibrationPlate(everything(500, lastSpan = 34_000)).gcode(s, setup.foreignTemplateVars, "test")) shouldNotBe
            aboveFirst(CalibrationPlate(everything(440)).gcode(s, setup.foreignTemplateVars, "test"))
    }

    "controls: an ordinary first-layer line at the plate's own width, a tube's first layer at the first-layer width, a width declared wrong are caught" {
        val wide = everything(500)
        CalibrationCheck.check(wide, CalibrationPlate(wide, without = setOf("firstwidth")).gcode(s, setup.foreignTemplateVars, "test"))
            .any { it.contains("'adhesion:post'") && it.contains("filament per mm") } shouldBe true
        // tube 1 (0.35 mm) laid 0.5 mm wide on the first layer
        CalibrationCheck.check(wide, CalibrationPlate(wide, without = setOf("tubefirst")).gcode(s, setup.foreignTemplateVars, "test"))
            .any { it.contains("'adhesion:tube 1'") } shouldBe true
        // the plate as generated, one first-layer post path's declared width changed from 0.5 to 0.44 (its moves untouched)
        val lines = CalibrationPlate(wide).gcode(s, setup.foreignTemplateVars, "test").lines().toMutableList()
        val at = lines.indexOfFirst { it == "; CELL: adhesion:post" }
        val k = (at until lines.size).first { lines[it].startsWith(";WIDTH:") || lines[it].startsWith("; LINE_WIDTH:") }
        lines[k] = lines[k].replace("0.5", "0.44")
        CalibrationCheck.check(wide, lines.joinToString("\n")).any { it.contains("'adhesion:post': declared 0.440 mm wide, not 0.500") } shouldBe true
    }

    // the filament plate as `plymeld calibrate filament` makes it: every family on a 3-layer base, the
    // bands above the bases, a row of flow patches per band, the eleven tubes, the stringing ring; 4 bands
    val bb = CalibrationSpec.bands(205, 220, 5, 50.0 to 100.0, 3 * h, 2_400, 800)
    fun lastRead(b: Int) = (bb[b].readToUm / h).toInt() - 1
    fun based(packW: Long = Long.MAX_VALUE, packD: Long = Long.MAX_VALUE) = CalibrationSpec(0, "test", bb, h, w, 1.75, adhesion = spec.adhesion, frame = spec.frame,
        melt = null, flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstWidthUm = 500,
        flow = plymeld.core.calibrate.FlowPatches(plymeld.core.calibrate.FlowPatches.defaultRatios(1.0).toDoubleArray(), 3, 80.0, 5000.0, 4, fromLayers = IntArray(bb.size) { lastRead(it) - 4 }),
        ledges = ledgeSpec.ledges, bridges = bridgeSpec.bridges, strings = plymeld.core.calibrate.Strings(plymeld.core.calibrate.Strings.defaultLengths().toDoubleArray(), 80.0, 5000.0),
        tubes = plymeld.core.calibrate.Tubes(plymeld.core.calibrate.Tubes.defaultWidths(400).toLongArray(), 60.0, 5000.0, (bb.last().readToUm / h).toInt()),
        fit = plymeld.core.calibrate.Fit(5_000, longArrayOf(5_000, 5_100, 5_200), 60.0, 5000.0, lastRead(2) - (bb[2].fromUm / h).toInt() + 1, lastRead(2) + 1, fromLayer = (bb[2].fromUm / h).toInt()),
        packWidthUm = packW, packDepthUm = packD, baseLayers = 3)
    val basedSpec = based()
    val basedText = CalibrationPlate(basedSpec).gcode(s, setup.foreignTemplateVars, "test")
    // each cell's lines on each layer (by its order in the file), from the g-code text alone: (x0, y0, x1, y1, width)
    fun cellLines(t: String): Map<Pair<Int, String>, List<DoubleArray>> {
        val out = HashMap<Pair<Int, String>, MutableList<DoubleArray>>(); var layer = -1; var cell: String? = null; var wd = Double.NaN; var x = Double.NaN; var y = Double.NaN
        for (l in t.lines()) {
            if (l.startsWith("; CHANGE_LAYER") || l.startsWith(";LAYER_CHANGE")) { layer++; continue }
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: ").removePrefix("adhesion:"); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").toDouble(); continue }
            if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
            val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+) E([0-9.]+).*").matchEntire(l)
            val c = cell
            if (m != null && c != null && !x.isNaN()) out.getOrPut(layer to c) { ArrayList() } += doubleArrayOf(x, y, m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), wd, m.groupValues[3].toDouble())
            Regex("G[01] .*X([-0-9.]+) Y([-0-9.]+).*").matchEntire(l)?.let { x = it.groupValues[1].toDouble(); y = it.groupValues[2].toDouble() }
        }
        return out
    }
    fun extent(ls: List<DoubleArray>) = doubleArrayOf(ls.minOf { minOf(it[0], it[2]) - it[4] / 2 }, ls.minOf { minOf(it[1], it[3]) - it[4] / 2 }, ls.maxOf { maxOf(it[0], it[2]) + it[4] / 2 }, ls.maxOf { maxOf(it[1], it[3]) + it[4] / 2 })

    "bases: the plate passes; on the bases' layers nothing but the bases and the post; the bands above them" {
        CalibrationCheck.check(basedSpec, basedText).shouldBeEmpty()
        val cells = cellLines(basedText)
        for (layer in 0..2) cells.keys.filter { it.first == layer }.map { it.second }.toSet() shouldBe (setOf("post") + basedSpec.parts.filter { it != plymeld.core.calibrate.Part.POST }.map { "base ${it.key}" })
        cells.keys.filter { it.first == 3 }.none { it.second.startsWith("base ") } shouldBe true
        // band 1's temperature and the fan off on the bases, by the g-code's own commands: no M106 before the fourth layer
        val ls = basedText.lines(); val fourth = ls.indices.filter { ls[it].startsWith("; CHANGE_LAYER") || ls[it].startsWith(";LAYER_CHANGE") }[3]
        ls.subList(0, fourth).none { it.startsWith("M106 S") && !it.startsWith("M106 S0") } shouldBe true
    }

    "bases by the g-code alone: 10 mm round each test, the first layer whole, a 2 mm notch 3 mm deep above it, in front of the test's first item" {
        val cells = cellLines(basedText)
        for (p in basedSpec.parts) {
            if (p == plymeld.core.calibrate.Part.POST) continue
            withClue(p.key) {
                val test = extent(cells.filterKeys { it.first >= 3 && plymeld.core.calibrate.Part.ofCell(it.second) == p }.values.flatten())
                for (layer in 0..2) {
                    val base = cells.getValue(layer to "base ${p.key}")
                    val b = extent(base)
                    for (i in 0..3) b[i] shouldBe ((test[i] + if (i < 2) -10.0 else 10.0) plusOrMinus 0.03)
                    // the front face: the lines along the base's frontmost y, and the gaps between them
                    val fy = base.minOf { minOf(it[1], it[3]) }
                    val front = base.filter { Math.abs(it[1] - fy) < 0.01 && Math.abs(it[3] - fy) < 0.01 }.map { minOf(it[0], it[2]) to maxOf(it[0], it[2]) }.sortedBy { it.first }
                    val gaps = front.zipWithNext().map { (a, c) -> c.first - a.second }.filter { it > 0.05 }
                    if (layer == 0) gaps.shouldBeEmpty() else { gaps.size shouldBe 1; gaps[0] shouldBe ((2.0 + base[0][4]) plusOrMinus 0.06) }
                }
            }
        }
        // the flow base's notch in front of the first column: between the first patch's left and right edges
        val base = cellLines(basedText).getValue(1 to "base flow"); val fy = base.minOf { minOf(it[1], it[3]) }
        val front = base.filter { Math.abs(it[1] - fy) < 0.01 && Math.abs(it[3] - fy) < 0.01 }.map { minOf(it[0], it[2]) to maxOf(it[0], it[2]) }.sortedBy { it.first }
        val gap = front.zipWithNext().first { (a, c) -> c.first - a.second > 0.05 }.let { (a, c) -> (a.second + c.first) / 2 }
        val patch = extent(cellLines(basedText).filterKeys { it.second == "pedestal 1.1" }.values.flatten())
        (gap in patch[0]..patch[2]) shouldBe true
    }

    "controls: a notch filled in, a notch through the first layer, a base 5 mm short, the bands not above the bases, the parts packed with no gap are caught" {
        fun says(c: String) = CalibrationCheck.check(basedSpec, CalibrationPlate(basedSpec, without = setOf(c)).gcode(s, setup.foreignTemplateVars, "test"))
        says("basefill").any { it.contains("notches in its front face, not 1") } shouldBe true
        says("basenotch1").any { it.contains("on its first layer, which is whole") } shouldBe true
        says("baseshort").any { it.contains("base is") } shouldBe true
        says("familygap").any { it.contains("mm apart, not at least 10") } shouldBe true
        val unshifted = CalibrationCheck.check(basedSpec, CalibrationPlate(basedSpec.withBands(CalibrationSpec.bands(205, 220, 5, 50.0 to 100.0, h, 2_400, 800))).gcode(s, setup.foreignTemplateVars, "test"))
        unshifted.shouldNotBeEmpty()
    }

    "bases' fill: the check passes; by the g-code alone, only diagonal lines inside the rings, 45 / 135 / 45 by layer, one bead spacing apart" {
        CalibrationCheck.baseFill(basedSpec, basedText).shouldBeEmpty()
        val cells = cellLines(basedText)
        for (p in basedSpec.parts) {
            if (p == plymeld.core.calibrate.Part.POST) continue
            withClue(p.key) {
                val angles = (0..2).map { layer ->
                    val ls = cells.getValue(layer to "base ${p.key}")
                    // moves neither along X nor along Y: the lines, all at one angle (the rings and the joins run along X or Y)
                    val diag = ls.filter { Math.abs(it[2] - it[0]) > 0.002 && Math.abs(it[3] - it[1]) > 0.002 }
                    diag.shouldNotBeEmpty()
                    val a = diag.map { Math.round(Math.toDegrees(Math.atan2(it[3] - it[1], it[2] - it[0])).let { d -> if (d < 0) d + 180 else d }) }.toSet()
                    a.size shouldBe 1
                    // the lines' offsets across: one bead spacing apart (w - h(1 - pi/4), the width each declares)
                    val r = Math.toRadians(a.single().toDouble())
                    val vs = diag.map { -Math.sin(r) * it[0] + Math.cos(r) * it[1] }.sorted().fold(ArrayList<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 0.01) acc += v; acc }
                    val sp = diag[0][4] - 0.2 * (1 - Math.PI / 4)
                    vs.zipWithNext { u, v -> v - u }.forEach { it shouldBe (sp plusOrMinus 0.005) }
                    a.single()
                }
                angles shouldBe listOf(45L, 135L, 45L)
            }
        }
    }

    "controls: the bases' fill concentric (as before), two layers at one angle, lines out of monotonic order, lines across the notch, a line left out are caught" {
        fun says(c: String) = CalibrationCheck.check(basedSpec, CalibrationPlate(basedSpec, without = setOf(c)).gcode(s, setup.foreignTemplateVars, "test"))
        says("baserings").any { it.contains("outline ring(s), not 2") } shouldBe true
        says("basesameangle").any { it.contains("not alternating") } shouldBe true
        says("basehalves").any { it.contains("is printed after the lines on both sides of it") } shouldBe true
        says("basenotchfill").any { it.contains("innermost ring") } shouldBe true
        // one line of a base's second layer left out (its extrusion removed: a travel in its place)
        val ls = basedText.lines().toMutableList()
        val second = ls.indices.filter { ls[it].startsWith("; CHANGE_LAYER") || ls[it].startsWith(";LAYER_CHANGE") }[1]
        val fillStart = (second until ls.size).filter { ls[it] == "; CELL: base flow" }[2]
        val end = (fillStart until ls.size).first { ls[it] == "; CELL END" }
        val moves = (fillStart until end).filter { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+").matches(ls[it]) }
        fun xy(i: Int) = Regex("G1 X([-0-9.]+) Y([-0-9.]+).*").matchEntire(ls[i])!!.groupValues.let { it[1].toDouble() to it[2].toDouble() }
        val k = (moves.size / 2 until moves.size).first { j -> val (x0, y0) = xy(moves[j - 1]); val (x1, y1) = xy(moves[j]); Math.abs(x1 - x0) > 0.01 && Math.abs(y1 - y0) > 0.01 }
        ls[moves[k]] = ls[moves[k]].substringBefore(" E")
        CalibrationCheck.baseFill(basedSpec, ls.joinToString("\n")).any { it.contains("not one bead spacing") || it.contains("is not covered") } shouldBe true
    }

    "flow patches at every band: a row of 7 per band, each row's top on its band's last read layer; a row left out is caught" {
        val cells = cellLines(basedText)
        val flows = cells.keys.map { it.second }.filter { it.startsWith("flow ") }.toSet()
        flows.size shouldBe 7 * 4
        for (r in 1..4) {
            val top = cells.keys.filter { it.second == "flow $r.1" }.maxOf { it.first }
            ((top + 1) * h) shouldBe bb[r - 1].readToUm
        }
        CalibrationCheck.check(basedSpec, CalibrationPlate(basedSpec, without = setOf("flowrow")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("'pedestal 4.1'") || it.contains("'flow 4.1'") } shouldBe true
    }

    "line-width tubes: eleven, 0.25 to 0.75 mm, each at its own width by its filament per mm; all at the plate's width is caught" {
        val cells = cellLines(basedText)
        fun lines(c: String) = cells.filterKeys { it.second == c && it.first > 3 }.values.flatten()
        val want = (5..15).map { Math.round(it * 0.05 * 100) / 100.0 }
        (1..11).map { lines("tube $it").map { l -> l[4] }.distinct().single() } shouldBe want
        // filament per mm: the bead model's at each width (0.2 mm layers, 1.75 mm filament, flow ratio 1)
        for (k in 1..11) {
            val ls = lines("tube $k"); val e = ls.sumOf { it[5] } / ls.sumOf { Math.hypot(it[2] - it[0], it[3] - it[1]) }
            val wd = want[k - 1]; val bead = 0.2 * (wd - 0.2 * (1 - Math.PI / 4)) / (Math.PI * 1.75 * 1.75 / 4)
            withClue("tube $k") { e shouldBe (bead plusOrMinus bead * 0.005) }
        }
        CalibrationCheck.check(basedSpec, CalibrationPlate(basedSpec, without = setOf("widths")).gcode(s, setup.foreignTemplateVars, "test")).any { it.contains("tube") } shouldBe true
    }

    "packing: the same parts pack the same way, at least 10 mm apart by the g-code; a bed too small for them is not a fit" {
        val a = CalibrationPlate(basedSpec).layout; val b2 = CalibrationPlate(based()).layout
        a.offsets.keys shouldBe b2.offsets.keys
        for (p in a.offsets.keys) a.offsets.getValue(p).toList() shouldBe b2.offsets.getValue(p).toList()
        a.fits shouldBe true
        // every two parts' lines at least 10 mm apart (each part: everything its cells lay, its base included)
        val cells = cellLines(basedText)
        val boxes = basedSpec.parts.associateWith { p -> extent(cells.filterKeys { plymeld.core.calibrate.Part.ofCell(it.second) == p }.values.flatten()) }
        for ((p, x) in boxes) for ((q, y) in boxes) if (p.ordinal < q.ordinal)
            withClue("${p.key} / ${q.key}") { (maxOf(y[0] - x[2], x[0] - y[2], y[1] - x[3], x[1] - y[3]) >= 10.0 - 0.03) shouldBe true }
        // control: a 150 x 150 mm room cannot take them (the plate is refused by the command)
        CalibrationPlate(based(150_000, 150_000)).layout.fits shouldBe false
        // a room as big as the plate packed without limits takes it
        CalibrationPlate(based(a.widthUm, a.depthUm)).layout.fits shouldBe true
    }

    "each family's predicted time and filament add up to the plate's" {
        val machine = s.machineDescription
        val total = plymeld.core.Predict.gcode(basedText, machine, null, plymeld.core.Predict.limits(s))
        val (time, fil) = plymeld.core.Predict.byCell(basedText, machine, null, plymeld.core.Predict.limits(s))
        time!!.values.sum() shouldBe (total.timeS!! plusOrMinus 1e-6 * total.timeS!!)
        fil.values.sum() shouldBe (total.filamentMm plusOrMinus 1e-6 * total.filamentMm)
        // every family has its own share; the bases' and the post's cells are attributed too
        for (p in basedSpec.parts) (time.filterKeys { it.isNotEmpty() && plymeld.core.calibrate.Part.ofCell(it) == p }.values.sum() > 0) shouldBe true
        // control: the attribution sees where the time goes (the plate without its flow patches has none of theirs)
        val noFlow = CalibrationPlate(basedSpec.keeping(basedSpec.parts.toSet() - plymeld.core.calibrate.Part.FLOW)).gcode(s, setup.foreignTemplateVars, "test")
        plymeld.core.Predict.byCell(noFlow, machine, null, plymeld.core.Predict.limits(s)).first!!.keys.none { it.isNotEmpty() && plymeld.core.calibrate.Part.ofCell(it) == plymeld.core.calibrate.Part.FLOW } shouldBe true
    }

    "an exact program refuses a path without its own values" {
        val p = PrintProgram(listOf(LayerStart(0, h, h), Extrude("Outer wall", longArrayOf(0, 0, 10_000, 0), false, h, w, h)), emptyList(), exact = true)
        shouldThrow<IllegalStateException> { KlipperWriter(s, setup.foreignTemplateVars, "test").write(p, StringWriter()) }.message!! shouldContain "without its own exact values"
    }

    // the first layer at a first-layer window's height (the filament plate printed its first layer at the plate's
    // 0.2 mm, graded bad, at a speed graded for 0.28 mm): every layer of the file shifted up by the difference, the first
    // layer that thick, and the check reading the file with the same shift
    "a thicker first layer shifts the plate's layers up and passes its check; the unshifted spec refuses the shifted file (control)" {
        val thick = CalibrationSpec(0, "test", bands, h, w, 1.75,
            adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8),
            melt = null, flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000, firstLayerUm = 280)
        val t = CalibrationPlate(thick).gcode(s, setup.foreignTemplateVars, "test")
        fun zs(g: String) = g.lines().mapNotNull { l -> when { l.startsWith(";Z:") -> l.removePrefix(";Z:"); l.startsWith("; Z_HEIGHT:") -> l.removePrefix("; Z_HEIGHT:"); else -> null }?.trim()?.toDouble() }
        fun hs(g: String) = g.lines().mapNotNull { l -> when { l.startsWith(";HEIGHT:") -> l.removePrefix(";HEIGHT:"); l.startsWith("; LAYER_HEIGHT:") -> l.removePrefix("; LAYER_HEIGHT:"); else -> null }?.trim()?.toDouble() }
        val zs = zs(t)
        zs.first() shouldBe (0.28 plusOrMinus 1e-9)
        zs[1] shouldBe (0.48 plusOrMinus 1e-9)
        zs.last() shouldBe (zs(text).last() + 0.08 plusOrMinus 1e-9)
        hs(t).first() shouldBe (0.28 plusOrMinus 1e-9)
        hs(t)[1] shouldBe (0.2 plusOrMinus 1e-9)
        CalibrationCheck.check(thick, t).shouldBeEmpty()
        CalibrationCheck.check(spec, t).shouldNotBeEmpty()          // control: read as a 0.2 mm first layer, no layer matches
        // every copy of a spec keeps it (the plate is packed and re-banded through copies; seen dropped: the file came out at 0.2 mm)
        thick.withBands(bands).firstLayerUm shouldBe 280L
        thick.withMelt(null).firstLayerUm shouldBe 280L
        thick.keeping(thick.parts.toSet()).firstLayerUm shouldBe 280L
    }
})

