package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateInstructions
import plymeld.core.calibrate.SeamAt
import plymeld.core.calibrate.SeamCheck
import plymeld.core.calibrate.SeamGeometry
import plymeld.core.calibrate.SeamInstructions
import plymeld.core.calibrate.SeamPiece
import plymeld.core.calibrate.SeamPlate
import plymeld.core.calibrate.SeamShape
import plymeld.core.calibrate.SeamSpec
import plymeld.core.calibrate.SeamStyle
import plymeld.model.SliceSettings

/**
 * The seam plate: eight hollow pieces on one slab, each with its outer
 * wall's seam laid one way (a point seam or plymeld's own scarf; on a round wall, mid-face, in a corner, on a leaning face),
 * printed exactly as specified; its read-back check can fail: each control below is caught; its page names every piece.
 */
class SeamPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val sp = SeamSpec(0, "test", SeamPiece.STANDARD, 200, 200, 440, 440, 500, 1.75, 1.0, 210, 100.0,
        adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8), outer = PathValues(60.0, 3000.0, 1.0, 0.8), inner = PathValues(100.0, 5000.0, 1.0, 0.8),
        scarfUm = 10_000, scarfStart = 0.25, cornerDeg = 30.0, liftUm = 200, originXUm = 60_000, originYUm = 60_000)
    fun gcode(p: SeamPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text = gcode(SeamPlate(sp))
    fun ctl(vararg w: String) = gcode(SeamPlate(sp, without = w.toSet()))
    fun expect() = plymeld.core.check.GcodeCheck.Expect(0.0, 0.0, 500.0, 500.0, 300.0, maxPrintSpeed = 1000.0, maxTravelSpeed = 1000.0, maxAccel = 100000.0,
        retractLengths = setOf(0.8, s.retractMm), startTemplate = "", endTemplate = "", scarfStart = 0.25)
    fun gcheck(t: String) = plymeld.core.check.GcodeCheck.check(t.lines().filterNot { it.startsWith("EXCLUDE_OBJECT_DEFINE") }.joinToString("\n"), expect())
    fun problems(t: String) = SeamCheck.check(sp, t) + gcheck(t)
    /** The extrusion lines of the first cell block with [label] after the slab, as (x, y, z or NaN, e). */
    fun firstBlock(t: String, label: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(); var inCell = false
        for (l in t.lines()) {
            if (l == "; CELL: $label") { if (out.isNotEmpty()) break; inCell = true; continue }
            if (l == "; CELL END") { if (out.isNotEmpty()) break; inCell = false; continue }
            if (!inCell) continue
            Regex("G1 X([-0-9.]+) Y([-0-9.]+)(?: Z([0-9.]+))? E([0-9.]+).*").matchEntire(l)?.let { m -> out += doubleArrayOf(m.groupValues[1].toDouble(), m.groupValues[2].toDouble(), m.groupValues[3].toDoubleOrNull() ?: Double.NaN, m.groupValues[4].toDouble()) }
        }
        return out
    }

    "the layout: eight pieces in a row on a 160 x 30 mm slab, 54 layers; the seams where specified; a leaning face steps out by its lean" {
        sp.pieces.size shouldBe 8
        sp.pieceLayers shouldBe 50; sp.layers shouldBe 54
        sp.slabW shouldBe 160_000L; sp.slabD shouldBe 30_000L   // 3 + 8 x 14 + 7 x 6 + 3; 3 + 14 + 3 + the 45 degree lean's 10 mm reach
        sp.reachUm shouldBe 10_000L
        // piece 1 (round, front): the seam at the front-most point of its wall; piece 5 (corner): the front-left corner, half a line in
        val c0 = sp.cell(0)
        sp.seamPoint(0, 0).toList() shouldBe listOf(60_000 + (c0[0] + c0[2]) / 2, 60_000 + (c0[1] + c0[3]) / 2 - (7_000 - 220))
        val c4 = sp.cell(4)
        sp.seamPoint(4, 0).toList() shouldBe listOf(60_000 + c4[0] + 220, 60_000 + c4[1] + 220)
        // piece 8 leans 45 degrees: its front steps out 0.2 mm a layer (read at the layer's middle)
        (sp.frontY(7, 0) - sp.frontY(7, 10)) shouldBe 2_000L
        sp.frontY(2, 10) shouldBe sp.cell(2)[1]
        // the loops are counter-clockwise and start at the seam
        val l = sp.loop(2, 0, false)
        l[0] shouldBe sp.seamPoint(2, 0)[0]; l[1] shouldBe sp.seamPoint(2, 0)[1]
        (plymeld.geom.Polygon(l).signedArea2() > 0) shouldBe true
        shouldThrow<IllegalArgumentException> { SeamSpec(0, "t", SeamPiece.STANDARD, 200, 200, 440, 440, 500, 1.75, 1.0, 210, 100.0, sp.adhesion, sp.frame, sp.outer, sp.inner, scarfUm = 500, scarfStart = 0.25, cornerDeg = 30.0, liftUm = 200) }
        SeamPiece.parse(3, "square corner scarf")!!.let { it.shape shouldBe SeamShape.SQUARE; it.at shouldBe SeamAt.CORNER; it.style shouldBe SeamStyle.SCARF }
        SeamPiece.parse(0, "oval face point") shouldBe null
    }

    "the plate as generated passes both checks" {
        SeamCheck.check(sp, text).shouldBeEmpty()
        gcheck(text).shouldBeEmpty()
    }

    "independently of the checks: a point seam's loop is flat and closes at the seam; a scarf's first line starts at the layer's bottom plus a quarter, rises to the top over 10 mm, and the ramp is laid again" {
        val lines = text.lines()
        lines.count { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") } shouldBe 54
        lines.filter { it.startsWith("M109") }.distinct() shouldBe listOf("M109 S210 T0")
        // piece 1, its first layer above the slab (top z = 0.8 + 0.2 = 1.0): 72 flat segments ending at the seam
        val p1 = firstBlock(text, "piece 1")
        p1.size shouldBe 72
        p1.all { it[2].isNaN() } shouldBe true
        val seam1 = sp.seamPoint(0, 0)
        p1.last()[0] shouldBe (seam1[0] / 1000.0 plusOrMinus 0.001); p1.last()[1] shouldBe (seam1[1] / 1000.0 plusOrMinus 0.001)
        // piece 2: the ramp's lines carry Z from 0.85 (the layer's bottom 0.8 plus a quarter of 0.2) up to 1.0
        val p2 = firstBlock(text, "piece 2")
        p2.first()[2] shouldBe (0.855 plusOrMinus 0.011)
        p2.last()[2] shouldBe 1.0
        (p2.sumOf { it[3] } > 0) shouldBe true
        // its ramp is 10 mm long: the block's path length
        var len = 0.0; var px = sp.seamPoint(1, 0)[0] / 1000.0; var py = sp.seamPoint(1, 0)[1] / 1000.0
        for (m in p2) { len += Math.hypot(m[0] - px, m[1] - py); px = m[0]; py = m[1] }
        len shouldBe (10.0 plusOrMinus 0.05)
        // three cell blocks per layer for a scarfed piece (ramp, loop, ramp), one for a point seam's
        val perLayer2 = lines.count { it == "; CELL: piece 2" } / 50
        perLayer2 shouldBe 3
        lines.count { it == "; CELL: piece 1" } / 50 shouldBe 1
        // the inner wall before the outer, piece 1 before piece 2 on the first piece layer
        val i1 = lines.indexOf("; CELL: piece 1 inner"); val o1 = lines.indexOf("; CELL: piece 1"); val i2 = lines.indexOf("; CELL: piece 2 inner")
        (i1 < o1 && o1 < i2) shouldBe true
    }

    "controls: a scarf left out, a point seam scarfed, a seam moved, a corner seam moved mid-face, a lean taken out, the inner seam at the front are caught" {
        SeamCheck.check(sp, ctl("scarf")).any { it.contains("piece 2") && it.contains("no scarf ramp") } shouldBe true
        SeamCheck.check(sp, ctl("point")).any { it.contains("piece 1") && it.contains("scarf ramp") } shouldBe true
        SeamCheck.check(sp, ctl("seam")).any { it.contains("piece 3") && it.contains("not at its seam") } shouldBe true
        SeamCheck.check(sp, ctl("corner")).any { it.contains("piece 5") && it.contains("not at its seam") } shouldBe true
        SeamCheck.check(sp, ctl("lean")).any { it.contains("piece 7") && it.contains("front face is at y") } shouldBe true
        SeamCheck.check(sp, ctl("inner")).any { it.contains("piece 3") && it.contains("inner wall starts at") } shouldBe true
    }

    "controls: the ticks or the chamfer left out, the pieces out of order, the outer wall before the inner, the slab at the wrong speed, a wall's speed or plastic changed are caught" {
        SeamCheck.check(sp, ctl("ticks")).any { it.contains("0 tick(s)") } shouldBe true
        SeamCheck.check(sp, ctl("chamfer")).any { it.contains("chamfer") } shouldBe true
        SeamCheck.check(sp, ctl("order")).any { it.contains("printed before") } shouldBe true
        SeamCheck.check(sp, ctl("outerfirst")).any { it.contains("outer wall is printed before its inner wall") } shouldBe true
        SeamCheck.check(sp, ctl("slabspeed")).any { it.contains("'slab 2': 60.00 mm/s, not 100.00") } shouldBe true
        fun alter(cell: String, change: (String) -> String): String {
            val ls = text.lines().toMutableList()
            val at = ls.indexOfFirst { it == "; CELL: $cell" }; val end = (at until ls.size).first { ls[it] == "; CELL END" }
            for (k in at until end) ls[k] = change(ls[k])
            return ls.joinToString("\n")
        }
        val richer = alter("piece 3") { l -> Regex("(G1 X[-0-9.]+ Y[-0-9.]+ E)([0-9.]+)(.*)").matchEntire(l)?.let { it.groupValues[1] + "%.5f".format(java.util.Locale.ROOT, it.groupValues[2].toDouble() * 2) + it.groupValues[3] } ?: l }
        SeamCheck.check(sp, richer).any { it.contains("piece 3") && it.contains("mm of filament per mm") } shouldBe true
        val faster = alter("piece 3") { l -> if (l.startsWith("G1 F")) "G1 F" + Math.round(l.removePrefix("G1 F").toDouble() * 1.5) else l }
        SeamCheck.check(sp, faster).any { it.contains("'piece 3': 90.00 mm/s, not 60.00") } shouldBe true
    }

    "the page names every piece, what it reads, the grades, and the picture is written" {
        val d = java.nio.file.Files.createTempDirectory("plymeld-seam-page")
        val info = PlateInstructions.Info("test", "test machine", 0, "test", "seams.gcode", 16.0, "not set", 60, 40.0, 100.0, 0.8)
        SeamInstructions.write(d.resolve("seams.md"), sp, SeamPlate(sp), info)
        val md = java.nio.file.Files.readString(d.resolve("seams.md"))
        for (p in sp.pieces) md shouldContain "| ${p.index + 1} | ${p.describe()} |"
        md shouldContain "| 5 | square | front-left corner | point | the corner share: against piece 3"
        md shouldContain "| 8 | square, front face leaning out 45 deg | middle of the leaning face | point | the underside share: against piece 3"
        for (g in SeamInstructions.GRADES) md shouldContain "**$g**"
        md shouldContain "decided before printing"
        md shouldContain "calibrate seams readings seams.gcode"
        (java.nio.file.Files.size(d.resolve("seams.png")) > 10_000) shouldBe true
        SeamGeometry.height shouldBe 10_000L
    }
})
