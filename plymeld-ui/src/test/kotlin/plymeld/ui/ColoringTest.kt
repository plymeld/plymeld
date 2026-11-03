package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** The preview's color schemes on a small file worked by hand. */
class ColoringTest : StringSpec({
    val p = GcodeToolpaths.parse("""
        M83
        ;LAYER_CHANGE
        ;TYPE:Outer wall
        ;WIDTH:0.4
        T0
        G1 X0 Y0 Z0.2 F1200
        G1 X10 Y0 E0.5
        ;TYPE:Sparse infill
        T1
        G1 X10 Y10 E0.5 F18000
        ;TYPE:Top surface
        G1 X0 Y10 E0.5 F3600
    """.trimIndent())

    "speed: the ramp runs from the slowest line to the fastest, the ends colored as the ramp's ends" {
        val (rgb, lg) = Coloring.colors(p, ColorBy.SPEED)
        lg.lo shouldBe (20.0 plusOrMinus 1e-6); lg.hi shouldBe (300.0 plusOrMinus 1e-6)
        rgb[0] shouldBe Coloring.ramp(0.0)
        rgb[1] shouldBe Coloring.ramp(1.0)
        rgb[2] shouldBe Coloring.ramp((60.0 - 20) / 280)
    }
    "control: the ramp's two ends differ (a ramp that colored everything alike would pass the test above by accident only if lo = hi)" {
        Coloring.ramp(0.0) shouldNotBe Coloring.ramp(1.0)
    }
    "tool: named as people count them, with the file's own T number beside" {
        val (rgb, lg) = Coloring.colors(p, ColorBy.TOOL)
        lg.swatches.map { it.first } shouldBe listOf("Tool 1 (T0)", "Tool 2 (T1)")
        rgb[0] shouldNotBe rgb[1]
        rgb[1] shouldBe rgb[2]
    }
    "a width the file does not give is counted as unknown and drawn gray, not given a value" {
        val q = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\nG1 X0 Y0 Z0.2\nG1 X10 Y0 E0.5\n;WIDTH:0.45\nG1 X20 Y0 E0.5\n")
        val (rgb, lg) = Coloring.colors(q, ColorBy.WIDTH)
        lg.unknown shouldBe 1
        rgb[0] shouldBe Coloring.GRAY
        rgb[1] shouldNotBe Coloring.GRAY
    }
    "feature: the legend lists the features that laid filament, in the file's order" {
        val (_, lg) = Coloring.colors(p, ColorBy.FEATURE)
        lg.swatches.map { it.first } shouldBe listOf("Outer wall", "Sparse infill", "Top surface")
    }
})
