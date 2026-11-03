package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.calibrate.IroningCheck
import plymeld.core.calibrate.IroningControls
import plymeld.core.calibrate.IroningGeometry
import plymeld.core.calibrate.IroningInstructions
import plymeld.core.calibrate.IroningPlate
import plymeld.core.calibrate.IroningSpec
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateInstructions
import plymeld.core.calibrate.StrokeFont
import plymeld.model.SliceSettings

/**
 * The ironing plate: one piece, two slabs per speed on a base that joins them, each slab a
 * row of zones framed by strips left as laid, each zone ironed with its own combination of flow, spacing, speed, angle and
 * inset, a control zone per speed as laid; every value of every setting read in several zones; the zones labeled by
 * notches (flow and spacing on the front edge, inset and angle on the back), the slabs by bar notches (speed), a legend of
 * the marks, a brim. It prints exactly as specified,
 * its page names every zone, and its read-back check can fail: each control below is caught.
 */
class IroningPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    // three values of each setting (the defaults' shape): speeds 15/30/45, flows 5/10/15 %, spacings 0.1/0.15/0.2, angles 0/45/90, insets 0.1/0.2/0.4
    fun spec(speeds: List<Double> = listOf(15.0, 30.0, 45.0), flows: List<Double> = listOf(0.05, 0.1, 0.15), spacings: List<Long> = listOf(100, 150, 200),
             angles: List<Double> = listOf(0.0, 45.0, 90.0), insets: List<Long> = listOf(100, 200, 400)) =
        IroningSpec(0, "test", speeds.toDoubleArray(), flows.toDoubleArray(), spacings.toLongArray(), angles.toDoubleArray(), insets.toLongArray(),
            200, 200, IroningGeometry.layers, 440, 500, 400, 1.75, 1.0, 210, 100.0,
            adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8), top = PathValues(60.0, 3000.0, 1.0, 0.8),
            ironAccel = 3000.0, retractMm = 0.8, liftUm = 200, originXUm = 60_000, originYUm = 60_000)
    val sp = spec()
    fun gcode(p: IroningPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text = gcode(IroningPlate(sp))
    fun ctl(vararg w: String) = gcode(IroningPlate(sp, without = w.toSet()))
    fun expect() = plymeld.core.check.GcodeCheck.Expect(0.0, 0.0, 500.0, 500.0, 300.0, maxPrintSpeed = 1000.0, maxTravelSpeed = 1000.0, maxAccel = 100000.0,
        retractLengths = setOf(0.8, s.retractMm), startTemplate = "", endTemplate = "")
    // the test machine's exclude-object definition left out (its POLYGON=[[...]] reads as a placeholder to the check)
    fun gcheck(t: String) = plymeld.core.check.GcodeCheck.check(t.lines().filterNot { it.startsWith("EXCLUDE_OBJECT_DEFINE") }.joinToString("\n"), expect())
    fun problems(t: String) = IroningCheck.check(sp, t) + gcheck(t)
    // a cell's first extrusion over 5 mm long (a short piece's E, rounded to the g-code's digits, is too coarse): its feed
    // (mm/s) and its filament per mm, read from the moves themselves
    fun firstLine(t: String, cell: String): Pair<Double, Double> {
        var f = 0.0; var x = Double.NaN; var y = Double.NaN; var inCell = false
        for (l in t.lines()) {
            if (l == "; CELL: $cell") inCell = true
            if (l == "; CELL END") inCell = false
            Regex("G1 F([0-9.]+)").matchEntire(l)?.let { f = it.groupValues[1].toDouble() }
            val m = Regex("G1 X([-0-9.]+) Y([-0-9.]+)(?: Z[0-9.]+)?(?: E([0-9.]+))?(?: F[0-9.]+)?").matchEntire(l)
            if (m != null) {
                val nx = m.groupValues[1].toDouble(); val ny = m.groupValues[2].toDouble()
                val e = m.groupValues[3].toDoubleOrNull()
                if (inCell && e != null && !x.isNaN() && Math.hypot(nx - x, ny - y) > 5.0) return f / 60 to e / Math.hypot(nx - x, ny - y)
                x = nx; y = ny
            }
        }
        return Double.NaN to Double.NaN
    }

    "the plan: a block per speed, its control first; every value of every setting in several zones; angle and inset Latin squares over the grid; zones numbered once" {
        sp.zones.size shouldBe 30
        sp.zones.count { it.control } shouldBe 3
        sp.zones.filter { it.control }.map { it.index } shouldBe listOf(0, 10, 20)
        sp.zones.map { it.index } shouldBe (0 until 30).toList()
        for ((name, byValue) in sp.coverage()) for ((v, zs) in byValue) { zs.size shouldBe 9; withClue("$name $v") { zs.distinct().size shouldBe 9 } }
        // in each block every angle meets every flow once and every spacing once (a Latin square), the inset likewise
        for (b in 0 until 3) for (ai in 0 until 3) {
            val zs = sp.zones.filter { it.block == b && !it.control && it.angleIdx == ai }
            zs.map { it.flowIdx }.sorted() shouldBe listOf(0, 1, 2); zs.map { it.spacingIdx }.sorted() shouldBe listOf(0, 1, 2)
            val iz = sp.zones.filter { it.block == b && !it.control && it.insetIdx == ai }
            iz.map { it.flowIdx }.sorted() shouldBe listOf(0, 1, 2); iz.map { it.spacingIdx }.sorted() shouldBe listOf(0, 1, 2)
        }
        // two slabs per speed: each a row of 5 zones of 20 mm, 3 mm frames between them (112 x 20 mm), 3 mm grooves between slabs
        sp.slabs shouldBe 6; sp.columns shouldBe 5; sp.slabW shouldBe 112_000L; sp.slabD shouldBe 20_000L
        sp.slabY(1) shouldBe 23_000L; sp.slabY(2) shouldBe 46_000L
        sp.blockOf(1) shouldBe 0; sp.blockOf(2) shouldBe 1
        // zone 2: slab 2 (the first speed's back slab), column 1; zone 11: slab 3's first (front-left); zone 10: slab 2, column 5
        sp.slab(1) shouldBe 1; sp.slab(10) shouldBe 2; sp.slab(9) shouldBe 1
        sp.zoneBox(1).toList() shouldBe listOf(0L, 23_000L, 20_000L, 43_000L)
        sp.zoneBox(10).toList() shouldBe listOf(0L, 46_000L, 20_000L, 66_000L)
        sp.zoneBox(9).toList() shouldBe listOf(92_000L, 23_000L, 112_000L, 43_000L)
        // the marks: each value a count of its setting's common step; an angle of 0 no notches
        sp.speedMarks.counts.toList() shouldBe listOf(1, 2, 3); sp.flowMarks.counts.toList() shouldBe listOf(1, 2, 3)
        sp.spacingMarks.counts.toList() shouldBe listOf(2, 3, 4); sp.insetMarks.counts.toList() shouldBe listOf(1, 2, 4)
        sp.angleMarks.counts.toList() shouldBe listOf(0, 1, 2); sp.angleMarks.unit shouldBe 45.0
        // values with no step few enough notches can count (5 mm/s steps to 45): marked by their place in the plan instead
        spec(speeds = listOf(15.0, 35.0, 45.0)).speedMarks.let { it.unit shouldBe null; it.counts.toList() shouldBe listOf(1, 2, 3) }
        // the legend lists each symbol's one value, as many to a line as fit the slabs' width
        sp.legendEntries shouldBe listOf("|=15 mm/s", "#=5%", "v=0.05 mm", "u=0.1 mm", "\\=45°")
        sp.legend shouldBe listOf("|=15 mm/s  #=5%  v=0.05 mm", "u=0.1 mm  \\=45°")
        // fewer values still read each in several zones (two flows, two spacings, two angles: 4 zones each)
        val small = spec(flows = listOf(0.05, 0.1), spacings = listOf(100, 200), angles = listOf(0.0, 90.0))
        small.coverage().values.flatMap { it.values }.all { it.size >= 4 } shouldBe true
        // a spacing a quarter of a zone or more leaves too few lines (5 mm in a 20 mm zone): refused; just under it is not
        shouldThrow<IllegalArgumentException> { spec(spacings = listOf(100, 5000)) }
        spec(spacings = listOf(100, 4900)).zones.size shouldBe 3 * (1 + 3 * 2)
    }

    "the plate as generated passes both checks; its footprint is the piece and a 3 mm brim round it" {
        IroningCheck.check(sp, text).shouldBeEmpty()
        gcheck(text).shouldBeEmpty()
        val b = IroningPlate(sp).bounds()
        b.toList() shouldBe listOf(-3_000L, -3_000L, sp.pieceW + 3_000L, sp.pieceD + 3_000L)
        // the piece holds the six slabs and the legend behind them (135 mm of slabs and grooves, then the legend)
        (sp.pieceD > 6 * 20_000L + 5 * 3_000L + sp.legendD) shouldBe true
        (sp.pieceW >= 112_000L) shouldBe true
    }

    "the marks: on a zone's front edge squares (flow) then Vs (spacing), on its back edge round-bottomed (inset) then slanted (angle) notches; a slab's speed is bars; a control is unlabelled" {
        val plate = IroningPlate(sp)
        // slab 1: the control (none) and zones 3, 5, 7, 9: flow + spacing + inset + angle notches 1+3+4+1, 2+2+2+1, 2+4+4+0,
        // 3+3+2+0 (the zone list on the plate's page), and the first speed's one bar
        plate.notches(0).size shouldBe 9 + 7 + 10 + 8 + 1
        // slab 2: zones 2, 4, 6, 8, 10: 1+2+1+0, 1+4+2+2, 2+3+1+2, 3+2+4+2, 3+4+1+1, and the same speed's bar
        plate.notches(1).size shouldBe 4 + 9 + 8 + 11 + 9 + 1
        IroningPlate(sp, without = setOf("labels")).notches(0).size shouldBe 0
        // a label longer than a zone's edge is refused (four insets' notches and four angles' at 45 degree steps)
        shouldThrow<IllegalArgumentException> { spec(angles = listOf(45.0, 90.0, 135.0, 180.0), insets = listOf(100, 200, 300, 400)) }
    }

    "the legend's glyphs stand apart: no two neighbors touch at the plate's lettering" {
        // '=' is two bars spanning its whole width: two of them four polygons unless their ends touch (the font's closest neighbors)
        StrokeFont.polygons("==", 0, 0, IroningGeometry.legendH, IroningGeometry.legendStroke).size shouldBe 4
        StrokeFont.polygons("|=5", 0, 0, IroningGeometry.legendH, IroningGeometry.legendStroke).size shouldBe 4
        // the control: strokes wider than the gap between glyphs (two grid units) merge the lot into one
        StrokeFont.polygons("==", 0, 0, IroningGeometry.legendH, 2 * IroningGeometry.legendH / 6 + 50).size shouldBe 1
    }

    "independently of the checks: four layers at one temperature, the fan on from the second; the zones after the top, each at its speed and the ironing rule's plastic; the controls not ironed" {
        val lines = text.lines()
        lines.count { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") } shouldBe 4
        lines.filter { it.startsWith("M109") }.distinct() shouldBe listOf("M109 S210 T0")
        val top = lines.indexOf("; CELL: top"); val zone1 = lines.indexOf("; CELL: zone 2")
        (top in 0 until zone1) shouldBe true
        lines.none { it == "; CELL: zone 1" || it == "; CELL: zone 11" || it == "; CELL: zone 21" } shouldBe true
        // zone 2: block 1 (15 mm/s), flow 5 %, spacing 0.1: 0.05 x 0.1 x 0.2 / (pi 1.75^2 / 4) = 0.000416 mm per mm
        val (v2, e2) = firstLine(text, "zone 2")
        v2 shouldBe (15.0 plusOrMinus 0.02); e2 shouldBe (0.05 * 0.1 * 0.2 / (Math.PI * 1.75 * 1.75 / 4) plusOrMinus 2e-6)
        // zone 16: block 2 (30 mm/s), flow 10 %, spacing 0.15 (grid row 1, column 1)
        val (v16, e16) = firstLine(text, "zone 16")
        v16 shouldBe (30.0 plusOrMinus 0.02); e16 shouldBe (0.1 * 0.15 * 0.2 / (Math.PI * 1.75 * 1.75 / 4) plusOrMinus 2e-6)
        // the fan: off until the second layer, then the filament's
        val secondLayer = lines.indices.filter { lines[it].startsWith(";LAYER_CHANGE") || lines[it].startsWith("; CHANGE_LAYER") }[1]
        lines.subList(top, zone1).none { it.startsWith("M106 S0") } shouldBe true
        lines.subList(secondLayer, secondLayer + 12).any { it == "M106 S255" } shouldBe true
    }

    "controls: a zone's flow doubled, its speed changed, its angle, spacing or inset wrong are caught" {
        fun alter(cell: String, change: (String) -> String): String {
            val ls = text.lines().toMutableList()
            val at = ls.indexOfFirst { it == "; CELL: $cell" }; val end = (at until ls.size).first { ls[it] == "; CELL END" }
            for (k in at until end) ls[k] = change(ls[k])
            return ls.joinToString("\n")
        }
        val richer = alter("zone 5") { l -> Regex("(G1 X[-0-9.]+ Y[-0-9.]+ E)([0-9.]+)(.*)").matchEntire(l)?.let { it.groupValues[1] + "%.5f".format(java.util.Locale.ROOT, it.groupValues[2].toDouble() * 2) + it.groupValues[3] } ?: l }
        IroningCheck.check(sp, richer).any { it.contains("'zone 5'") && it.contains("mm of filament per mm, not") } shouldBe true
        val faster = alter("zone 5") { l -> if (l.startsWith("G1 F")) "G1 F" + Math.round(l.removePrefix("G1 F").toDouble() * 1.5) else l }
        IroningCheck.check(sp, faster).any { it.contains("'zone 5': 22.50 mm/s, not 15.00") } shouldBe true
        IroningCheck.check(sp, ctl("angle")).any { it.contains("'zone 2'") && it.contains("not at the zone's angle") } shouldBe true
        IroningCheck.check(sp, ctl("spacing")).any { it.contains("'zone 2'") && it.contains("not its spacing") } shouldBe true
        // zone 2's inset is read on its slab's front and back edges (and, in column 1, its left edge)
        IroningCheck.check(sp, ctl("inset")).any { it.contains("'zone 2'") && it.contains("from its slab's back edge") } shouldBe true
    }

    "controls: the controls ironed, the labels, legend, brim or chamfer left out, the zones out of order, the top at the wrong speed or angle are caught" {
        IroningCheck.check(sp, ctl("control")).any { it.contains("'zone 1'") && it.contains("does not have") } shouldBe true
        val unlabelled = IroningCheck.check(sp, ctl("labels"))
        unlabelled.any { it.contains("0 bar notch(es) in slab 1's left end") } shouldBe true
        unlabelled.any { it.contains("zone 2's label in the slab's front edge reads ''") } shouldBe true
        unlabelled.any { it.contains("zone 2's label in the slab's back edge reads ''") } shouldBe true
        // the labels read against another plan's insets: the round-bottomed notches counted, and told from the slanted ones
        val wrongInsets = IroningCheck.check(spec(insets = listOf(100, 200, 300)), text)
        wrongInsets.any { it.contains("in the slab's back edge reads 'uuuu\\\\'") } shouldBe true
        IroningCheck.check(sp, ctl("legend")).any { it.contains("no legend") } shouldBe true
        IroningCheck.check(sp, ctl("brim")).any { it.contains("no 'adhesion:brim' cell") } shouldBe true
        IroningCheck.check(sp, ctl("chamfer")).any { it.contains("chamfer") } shouldBe true
        IroningCheck.check(sp, ctl("order")).any { it.contains("printed before") } shouldBe true
        IroningCheck.check(sp, ctl("topspeed")).any { it.contains("'top': 100.00 mm/s, not 60.00") } shouldBe true
        IroningCheck.check(sp, ctl("topangle")).any { it.contains("not along the slab") } shouldBe true
    }

    "the lift: the plate as generated rises off the top and off each zone; without the lifts (the writer's taken out too) a travel across a finished zone is caught" {
        problems(IroningControls.withoutZoneLifts(ctl("lift"))).any { it.contains("leaves zone") && it.contains("no lift") } shouldBe true
        // the writer's own hop alone keeps the g-code check content, so the plate's check (the lower end of a rising travel) is what catches it
        IroningCheck.check(sp, ctl("lift")).isEmpty() shouldBe false
    }

    "the page names every zone with its values, the coverage of every value, the grades, and the picture is written" {
        val d = java.nio.file.Files.createTempDirectory("plymeld-ironing-page")
        val info = PlateInstructions.Info("test", "test machine", 0, "test", "ironing.gcode", 20.0, "not set", 60, 40.0, 100.0, 0.8)
        IroningInstructions.write(d.resolve("ironing.md"), sp, IroningPlate(sp), info)
        val md = java.nio.file.Files.readString(d.resolve("ironing.md"))
        for (z in sp.zones) md shouldContain "| ${z.index + 1} | ${sp.slab(z.index) + 1} | ${sp.col(z.index) + 1} | ${sp.speeds[z.block].toInt()} |"
        md shouldContain "| 2 | 2 | 1 | 15 | 5 | 0.1 | 0 | 0.1 |"
        md shouldContain "| flow | 10 % | "
        md shouldContain "control: not ironed"
        for (g in IroningInstructions.GRADES) md shouldContain "**$g**"
        md shouldContain "decided before printing"
        (java.nio.file.Files.size(d.resolve("ironing.png")) > 10_000) shouldBe true
    }
})
