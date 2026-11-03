package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.gcode.KlipperWriter
import plymeld.model.SliceSettings
import plymeld.model.Sourced
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.PrintObject
import plymeld.program.PrintProgram
import java.io.StringWriter

/**
 * The conditions a filament's overhang share holds at (step 2): a wall segment
 * hanging past the line below prints with the part fan at hardware.filament.overhang.fan (back to the layer's fan
 * after) and no faster than hardware.filament.overhang.speed. Controls: the same program with the overhang fan equal
 * to the layer's and no overhang speed prints no raised fan and no capped speed.
 */
class OverhangConditionsTest : StringSpec({
    // the part fan at half, so the overhang fan raised above it shows; no prime line (the program below starts above
    // the first layer, where a prime line has no speed of its own)
    val cfg = arrayOf("hardware.filament.test-pla.cooling.fan" to "50%", "hardware.filament.test-pla.overhang.fan" to "50%",
        "plan.mechanism.adhesion.prime.enabled" to false)

    fun gcode(overhangFan: Double, overhangSpeed: Double?): String {
        val setup = testSetup(*cfg)
        for (v in setup.materialTool.values) {
            v["hardware.filament.overhang.fan"] = Sourced(overhangFan, "test")
            if (overhangSpeed != null) v["hardware.filament.overhang.speed"] = Sourced(overhangSpeed, "test")
        }
        val s = SliceSettings.of(setup)
        val on = s.fanOffFirstLayers
        // a 20 mm square outer wall at 0.2 mm, its third and fourth edges hanging 80 % past the line below,
        // then an inner wall with no overhang
        val sq = longArrayOf(100_000, 100_000, 120_000, 100_000, 120_000, 120_000, 100_000, 120_000)
        val inner = longArrayOf(101_000, 101_000, 119_000, 101_000, 119_000, 119_000, 101_000, 119_000)
        val z = 200L * (on + 1)
        val steps = listOf(ObjectStart("o"), LayerStart(on, z, 200, on),
            Extrude("Outer wall", sq, true, z, 420, 200, overhangShare = intArrayOf(0, 0, 800, 800)),
            Extrude("Inner wall", inner, true, z, 420, 200), ObjectEnd("o"))
        val out = StringWriter()
        KlipperWriter(s, setup.foreignTemplateVars, "test").write(PrintProgram(steps, listOf(PrintObject("o", doubleArrayOf(100.0, 100.0, 120.0, 100.0, 120.0, 120.0, 100.0, 120.0), 110.0, 110.0))), out)
        return out.toString()
    }

    "a hanging segment: the fan raised to the overhang fan, the speed capped at the overhang speed, the fan back after" {
        val g = gcode(100.0, 15.0)
        val lines = g.substring(g.indexOf("Outer wall")).lines()     // the paths, after the start template
        val raised = lines.indexOfFirst { it == "M106 S255" }
        (raised >= 0) shouldBe true
        lines.drop(raised).first { it.startsWith("G1 F") } shouldBe "G1 F900"           // 15 mm/s
        // back to the layer's 50 % before the inner wall prints
        val back = lines.drop(raised).indexOfFirst { it == "M106 S128" }
        val inner = lines.drop(raised).indexOfFirst { it.startsWith("G1 X119 Y101 E") }     // the inner wall's first extrusion
        (back in 0 until inner) shouldBe true
    }

    "control: overhang fan equal to the layer's and no overhang speed: no raised fan, no capped speed" {
        val all = gcode(50.0, null)
        all shouldContain "M106 S128"                                   // the layer's fan is on
        val g = all.substring(all.indexOf("Outer wall"))
        g shouldNotContain "M106 S255"
        g shouldNotContain "G1 F900\n"
    }
})
