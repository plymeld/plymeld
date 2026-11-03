package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.core.calibrate.MotionCheck
import plymeld.core.calibrate.MotionGeometry
import plymeld.core.calibrate.MotionInstructions
import plymeld.core.calibrate.MotionPlate
import plymeld.core.calibrate.MotionSpec
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateInstructions
import plymeld.model.SliceSettings

/**
 * The motion plate: one cross-shaped tower, twelve faces at six speeds (one
 * along X and one along Y each), a band per acceleration, printed exactly as specified; its read-back check can fail: each
 * control below is caught; its page names every face and band.
 */
class MotionPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val speeds = doubleArrayOf(30.0, 60.0, 90.0, 125.0, 155.0, 188.0)
    val accels = doubleArrayOf(1000.0, 2000.0, 4000.0)
    val sp = MotionSpec(0, "test", speeds, accels, 200, 200, 440, 440, 500, 1.75, 1.0, 210, 100.0,
        adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(150.0, 8000.0, 1.0, 0.8), innerCap = 150.0, retractMm = 0.8, liftUm = 200,
        minLayerTimeS = 6.0, travelSpeed = 200.0, originXUm = 60_000, originYUm = 60_000)
    fun gcode(p: MotionPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text = gcode(MotionPlate(sp))
    fun ctl(vararg w: String) = gcode(MotionPlate(sp, without = w.toSet()))
    fun expect() = plymeld.core.check.GcodeCheck.Expect(0.0, 0.0, 500.0, 500.0, 300.0, maxPrintSpeed = 1000.0, maxTravelSpeed = 1000.0, maxAccel = 100000.0,
        retractLengths = setOf(0.8, s.retractMm), startTemplate = "", endTemplate = "")
    fun gcheck(t: String) = plymeld.core.check.GcodeCheck.check(t.lines().filterNot { it.startsWith("EXCLUDE_OBJECT_DEFINE") }.joinToString("\n"), expect())

    "the layout: six speeds on twelve faces, each once along X and once along Y, every corner reached from a face at most one speed step away; three bands of 25 layers on a 3-layer slab" {
        // worked here from the faces' order round the cross, counter-clockwise from the front arm's front face (odd faces along X)
        val fs = MotionGeometry.FACE_SPEED.toList()
        (1..12).filter { it % 2 == 1 }.map { fs[it - 1] }.sorted() shouldBe (0..5).toList()
        (1..12).filter { it % 2 == 0 }.map { fs[it - 1] }.sorted() shouldBe (0..5).toList()
        for (k in 1..12) (Math.abs(fs[k - 1] - fs[(k + 10) % 12]) <= 1) shouldBe true          // face k and the face before it
        sp.bandLayers shouldBe 25; sp.layers shouldBe 3 + 75
        sp.slabSize shouldBe 98_000L                                                               // 3 x 30 + 2 x 4
        // the outer loop on a plain layer: 13 vertices, the seam first, counter-clockwise; on a groove layer set in half a line more
        val l = sp.loop(30, false)
        l.size shouldBe 26
        (plymeld.geom.Polygon(l).signedArea2() > 0) shouldBe true
        // the front arm's front face (face 1): from (m + a + w/2, m + w/2) along X, placed: 60 + 4 + 30 + 0.22 = 94.22, 64.22
        l[2] shouldBe 94_220L; l[3] shouldBe 64_220L
        sp.isGroove(25) shouldBe true; sp.isGroove(27) shouldBe false; sp.isGroove(0) shouldBe false
        sp.loop(25, false)[3] shouldBe 64_220L + 220
        // the seam: face 12 runs from (m + a, m + a) down to (m + a, m) on the cross; 80 % along it at the wall's inset
        val seam = sp.loop(30, false)
        seam[0] shouldBe 94_220L
        seam[1] shouldBe Math.round(60_000 + 4_000 + 30_000 + 220 + (220 - (30_000 + 220)) * 0.8)
        // what a face reaches: sqrt(a x L), L the shortest face (30 - 0.44 mm): at 1000 mm/s2 about 171.9 mm/s
        sp.reach(0) shouldBe (Math.sqrt(1000.0 * 29.56) plusOrMinus 1e-9)
        sp.facesAt(5) shouldBe (7 to 6)
        shouldThrow<IllegalArgumentException> { MotionSpec(0, "t", doubleArrayOf(30.0, 60.0, 90.0, 120.0, 150.0), accels, 200, 200, 440, 440, 500, 1.75, 1.0, 210, 100.0, sp.adhesion, sp.frame, 150.0, 0.8, 200) }
        shouldThrow<IllegalArgumentException> { MotionSpec(0, "t", speeds, doubleArrayOf(2000.0, 1000.0), 200, 200, 440, 440, 500, 1.75, 1.0, 210, 100.0, sp.adhesion, sp.frame, 150.0, 0.8, 200) }
    }

    "the plate as generated passes both checks" {
        MotionCheck.check(sp, text).shouldBeEmpty()
        gcheck(text).shouldBeEmpty()
    }

    "independently of the checks: each band's walls at its acceleration, the outer wall's speeds face by face round the loop, the inner wall held to its cap" {
        val lines = text.lines()
        lines.count { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") } shouldBe 78
        lines.filter { it.startsWith("M109") }.distinct() shouldBe listOf("M109 S210 T0")
        // the acceleration set before each band's cells (SET_VELOCITY_LIMIT or M204), read from the lines between the cell's marker and its first move
        for (b in 0 until 3) {
            val at = lines.indexOfFirst { it == "; CELL: band ${b + 1} outer" }
            val set = (at until at + 12).map { lines[it] }.first { it.startsWith("SET_VELOCITY_LIMIT") || it.startsWith("M204") }
            set shouldContain "${accels[b].toLong()}"
        }
        // band 1's first outer wall: its speed changes in order, face 12, faces 1 to 11 (face 12's last part needs no change)
        val at = lines.indexOfFirst { it == "; CELL: band 1 outer" }; val end = (at until lines.size).first { lines[it] == "; CELL END" }
        val fs = lines.subList(at, end).filter { it.startsWith("G1 F") }.map { it.removePrefix("G1 F").toDouble() / 60 }
        // speed by face, worked by hand from the palindrome 1 2 3 4 5 6 6 5 4 3 2 1 (face 12 first): 30, 30 60 90 125 155 188 [188] 155 125 90 60, then 30
        fs shouldBe listOf(30.0, 60.0, 90.0, 125.0, 155.0, 188.0, 155.0, 125.0, 90.0, 60.0, 30.0)
        // the inner wall: the two fastest faces held to 150 (its cap)
        val ai = lines.indexOfFirst { it == "; CELL: band 1 inner" }; val ei = (ai until lines.size).first { lines[it] == "; CELL END" }
        lines.subList(ai, ei).filter { it.startsWith("G1 F") }.maxOf { it.removePrefix("G1 F").toDouble() / 60 } shouldBe (150.0 plusOrMinus 1e-9)
        // the inner wall before the outer on the first tower layer
        (ai < at) shouldBe true
    }

    "controls: the grooves, the chamfer, the order, the speeds reversed, two faces swapped, one acceleration everywhere, the seam at a corner, the slab's speed are caught" {
        MotionCheck.check(sp, ctl("grooves")).any { it.contains("a groove layer") && it.contains("lies on none of the cross's faces") } shouldBe true
        MotionCheck.check(sp, ctl("chamfer")).any { it.contains("chamfer") } shouldBe true
        MotionCheck.check(sp, ctl("outerfirst")).any { it.contains("outer wall is printed before the inner wall") } shouldBe true
        MotionCheck.check(sp, ctl("speeds")).any { it.contains("face 1 (along X) at 188.00 mm/s, not 30.00") } shouldBe true
        MotionCheck.check(sp, ctl("swap")).any { it.contains("face 1 (along X) at 90.00 mm/s, not 30.00") } shouldBe true
        MotionCheck.check(sp, ctl("accel")).any { it.contains("'band 2 outer': acceleration 1000, not 2000") } shouldBe true
        MotionCheck.check(sp, ctl("seam")).any { it.contains("not at the seam on face 12") } shouldBe true
        MotionCheck.check(sp, ctl("slabspeed")).any { it.contains("'slab 2': 188.00 mm/s, not 150.00") } shouldBe true
        // a wall's plastic doubled (text), a stray line outside the cells (text)
        val ls = text.lines().toMutableList()
        val at = ls.indexOfFirst { it == "; CELL: band 2 outer" }; val end = (at until ls.size).first { ls[it] == "; CELL END" }
        for (k in at until end) Regex("(G1 X[-0-9.]+ Y[-0-9.]+ E)([0-9.]+)(.*)").matchEntire(ls[k])?.let { m -> ls[k] = m.groupValues[1] + "%.5f".format(java.util.Locale.ROOT, m.groupValues[2].toDouble() * 2) + m.groupValues[3] }
        MotionCheck.check(sp, ls.joinToString("\n")).any { it.contains("'band 2 outer'") && it.contains("mm of filament per mm") } shouldBe true
        val stray = text.lines().toMutableList().also { l -> l.addAll(l.indexOfLast { it == "; CELL: band 3 outer" }, listOf("G1 X60 Y55 F6000", "G1 X65 Y55 E0.2 F1200")) }
        MotionCheck.check(sp, stray.joinToString("\n")).any { it.contains("'(none)'") } shouldBe true
    }

    "the arm marks: n notches in the slab's edge in front of arm n's end face, past its first third, clear of the wall; left out, swapped, cut too deep or too early, caught" {
        // from the outline itself, worked here: probe each end edge 1 mm in from the slab's edge (half the 2 mm notch depth) and
        // count the stretches outside the polygon; the edges' lines from the slab's size (98) and the margin (4), by hand
        fun inside(poly: plymeld.geom.Polygon, x: Double, y: Double): Boolean {
            var c = false
            for (i in 0 until poly.size) { val j = (i + 1) % poly.size
                val xi = poly.x(i).toDouble(); val yi = poly.y(i).toDouble(); val xj = poly.x(j).toDouble(); val yj = poly.y(j).toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c }
            return c
        }
        // arm n's end edge: (start, direction) in mm, the end face starting 4 mm along it
        val edges = listOf(doubleArrayOf(30.0, 1.0, 1.0, 0.0), doubleArrayOf(97.0, 30.0, 0.0, 1.0), doubleArrayOf(68.0, 97.0, -1.0, 0.0), doubleArrayOf(1.0, 68.0, 0.0, -1.0))
        fun notches(p: MotionPlate): List<List<Double>> {
            val o = p.outline()
            return edges.map { e ->
                val out = ArrayList<Double>(); var inGap = false
                // sampled between the hundredths, so no sample lies on a notch's side
                for (i in 0 until 3800) { val t = (i + 0.5) / 100.0
                    val gap = !inside(o, (e[0] + e[2] * t) * 1000, (e[1] + e[3] * t) * 1000)
                    if (gap && !inGap) out += Math.round(t - 4.0).toDouble()       // where the notch starts, from the face's start
                    inGap = gap }
                out
            }
        }
        // centered 20 mm along the face, 4 mm apart, 2 mm wide: arm 1 from 19, arm 2 from 17 and 21, ... (the front edge's first
        // stretch outside the slab is the chamfer, before the face starts: dropped)
        val got = notches(MotionPlate(sp)).map { l -> l.filter { it > 0 } }
        got shouldBe listOf(listOf(19.0), listOf(17.0, 21.0), listOf(15.0, 19.0, 23.0), listOf(13.0, 17.0, 21.0, 25.0))
        // clear of the first third (10 mm) and of the face: the deepest point of every notch 2 mm in from the slab's 4 mm margin
        got.flatten().all { it >= 30.0 / 3 } shouldBe true
        (MotionGeometry.margin - MotionGeometry.markDepth >= 2 * sp.widthUm) shouldBe true
        // the check reads them from the g-code; its controls: no marks, the front and back arms' counts swapped
        MotionCheck.check(sp, ctl("marks")).any { it.contains("the front arm's end (face 1): 0 notch(es)") } shouldBe true
        MotionCheck.check(sp, ctl("markswap")).let { p ->
            p.any { it.contains("the front arm's end (face 1): 3 notch(es) in the slab's edge, not 1") } shouldBe true
            p.any { it.contains("the back arm's end (face 7): 1 notch(es) in the slab's edge, not 3") } shouldBe true
            p.none { it.contains("right arm") || it.contains("left arm") } shouldBe true
        }
        MotionCheck.check(sp, ctl("markdeep")).any { it.contains("a mark reaches the wall") } shouldBe true
        MotionCheck.check(sp, ctl("markearly")).any { it.contains("the front arm's end (face 1): a notch from 9.0") && it.contains("within its first third") } shouldBe true
    }

    "the page names every face, band and grade, the outcomes decided before printing, and the picture is written" {
        val d = java.nio.file.Files.createTempDirectory("plymeld-motion-page")
        val info = PlateInstructions.Info("test", "test machine", 0, "test", "motion.gcode", 20.0, "not set", 60, 40.0, 150.0, 0.8)
        MotionInstructions.write(d.resolve("motion.md"), sp, MotionPlate(sp), info, MotionInstructions.Notes("m", "the machine's print speed", "the machine's print acceleration"))
        val md = java.nio.file.Files.readString(d.resolve("motion.md"))
        for (k in 1..12) md shouldContain "| $k | ${MotionGeometry.where(k)} |"
        for (b in 1..3) md shouldContain "| $b (${accels[b - 1].toLong()}) |"
        for (g in plymeld.core.calibrate.MotionInstructions.GRADES) md shouldContain "**$g**"
        md shouldNotContain "**glaring**"                                         // the motion plate grades three words
        md shouldContain "decided before printing"
        md shouldContain "**the control** | faces 7 and 6 of band 3: 188 mm/s at 4000 mm/s2"
        md shouldContain "calibrate motion readings motion.gcode"
        md shouldContain "hardware.machine.m.motion.visible.speed"
        (java.nio.file.Files.size(d.resolve("motion.png")) > 10_000) shouldBe true
    }
})
