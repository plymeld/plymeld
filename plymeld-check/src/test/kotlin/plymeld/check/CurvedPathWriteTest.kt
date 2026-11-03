package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.predict.TimeEstimator
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

/**
 * A curved layer's path in the g-code: each vertex at its own Z,
 * each segment's filament from the mean of its ends' thicknesses (the bead model), no arcs. Worked by
 * hand: a 3-vertex line along X, 10 mm a segment, at Z 1.00 / 1.10 / 1.25 with thicknesses 0.20 / 0.22 /
 * 0.25 mm and a 0.44 mm line; segment 1's bead 0.21 x (0.44 - 0.21 (1 - pi/4)) = 0.08766 mm2, segment 2's
 * 0.235 x (0.44 - 0.235 (1 - pi/4)) = 0.09154 mm2; filament per mm = area / filament area (times the flow).
 */
class CurvedPathWriteTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    val xy = longArrayOf(100_000, 100_000, 110_000, 100_000, 120_000, 100_000)
    val curved = Extrude("Top surface", xy, false, 1250, 440, 250, zs = longArrayOf(1000, 1100, 1250), heights = longArrayOf(200, 220, 250))
    val flat = Extrude("Top surface", xy, false, 1000, 440, 200)
    fun gcode(p: Extrude) = StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(listOf(LayerStart(0, 1000, 200), p), emptyList()), it) }.toString()
    fun moves(g: String) = g.lines().filter { it.startsWith("G1 X") && it.contains(" E") }
    val filamentArea = Math.PI * 1.75 * 1.75 / 4
    fun bead(h: Double) = h * (0.44 - h * (1 - Math.PI / 4))

    "each vertex at its own Z, the path entered at its first vertex's Z" {
        val g = gcode(curved)
        val ms = moves(g)
        ms.size shouldBe 2
        ms[0] shouldContain "Z1.1 "
        ms[1] shouldContain "Z1.25 "
        g.lines().any { it == "G1 Z1" } shouldBe true
    }
    "each segment's filament from the mean of its ends' thicknesses (worked by hand); control: the flat twin's are all alike" {
        fun eOf(line: String) = line.substringAfter(" E").trim().toDouble()
        val flow = s.flowRatio
        val ms = moves(gcode(curved))
        eOf(ms[0]) shouldBe (10 * bead(0.21) / filamentArea * flow plusOrMinus 2e-5)
        eOf(ms[1]) shouldBe (10 * bead(0.235) / filamentArea * flow plusOrMinus 2e-5)
        val fs = moves(gcode(flat))
        fs.size shouldBe 2
        eOf(fs[0]) shouldBe (eOf(fs[1]) plusOrMinus 1e-9)
        fs.forEach { it shouldNotContain " Z" }
    }
    // Over air a bead is a strand: its filament goes by its 3D length. A tack: 1.5 mm level, then rising 1.62 mm over
    // 1 mm (3D 1.904 mm), 0.2 mm thick, at the bridge flow. Control: the same path on what is printed (a top surface) keeps
    // the XY length (a sloped layer of a vertical thickness holds that thickness times its XY length).
    "a strand over air takes filament for its 3D length (a tack's rise); control: the same path on a layer, its XY length" {
        fun eOf(line: String) = line.substringAfter(" E").trim().toDouble()
        val path = longArrayOf(100_000, 100_000, 101_500, 100_000, 102_500, 100_000)
        fun tack(feature: String) = Extrude(feature, path, false, 2620, 440, 200, zs = longArrayOf(1000, 1000, 2620), heights = longArrayOf(200, 200, 200))
        val perMm = bead(0.2) / filamentArea * s.flowRatio
        val t = moves(gcode(tack("Bridge tack")))
        eOf(t[0]) shouldBe (1.5 * perMm * s.bridgeFlow plusOrMinus 2e-5)
        eOf(t[1]) shouldBe (Math.hypot(1.0, 1.62) * perMm * s.bridgeFlow plusOrMinus 2e-5)
        val c = moves(gcode(tack("Top surface")))
        eOf(c[1]) shouldBe (1.0 * perMm plusOrMinus 2e-5)
    }
    "no arcs in a curved path, even with arc fitting on" {
        val round = Extrude("Outer wall", longArrayOf(100_000, 100_000, 110_000, 102_000, 118_000, 108_000, 122_000, 118_000, 122_000, 130_000),
            false, 1300, 440, 200, zs = longArrayOf(1000, 1050, 1120, 1200, 1300), heights = longArrayOf(200, 200, 200, 200, 200))
        val g = StringWriter().also { KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(listOf(LayerStart(0, 1000, 200), round), emptyList()), it) }.toString()
        g.lines().none { it.startsWith("G2 ") || it.startsWith("G3 ") } shouldBe true
    }
    "the predictor holds a sloped move to the Z limit, as Klipper does: 10 mm along X rising 1 mm at 300 mm/s with max_z_velocity 20 mm/s runs at 20 x sqrt(101) = 201 mm/s; control: flat, 300" {
        fun time(g: String) = TimeEstimator(600.0, 1e9, 20.0, 1e9, 1e9).estimate(BufferedReader(StringReader(g))).totalS
        val sloped = "G90\nG1 X0 Y0 Z0 F18000\nG1 X10 Y0 Z1 F18000\n"
        val level = "G90\nG1 X0 Y0 Z0 F18000\nG1 X10 Y0 Z0 F18000\n"
        (time(sloped) - time("G90\nG1 X0 Y0 Z0 F18000\n")) shouldBe (Math.sqrt(101.0) / (20 * Math.sqrt(101.0)) plusOrMinus 1e-6)
        (time(level) - time("G90\nG1 X0 Y0 Z0 F18000\n")) shouldBe (10.0 / 300 plusOrMinus 1e-6)
    }
})
