package plymeld.ui

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.math.hypot

/** The 3D view's g-code reader, on small files worked by hand. */
class GcodeToolpathsTest : StringSpec({
    "extrusions become segments, each end its own Z; travels and retractions do not; layers and features from the comments" {
        val p = GcodeToolpaths.parse("""
            M83
            G1 X0 Y0 Z0.2 F6000
            ;LAYER_CHANGE
            ;TYPE:Outer wall
            G1 X10 Y0 E0.5
            G1 X10 Y10 E0.5
            G1 E-0.8
            G1 X0 Y0
            G1 E0.8
            ;LAYER_CHANGE
            ;TYPE:Top surface
            G1 X5 Y0 Z0.45 E0.3 ; a curved line: Z changes along it
        """.trimIndent())
        p.segments shouldBe 3
        p.layers shouldBe 2
        p.layerStart.toList() shouldBe listOf(0, 2, 3)
        p.features[p.feature[0].toInt()] shouldBe "Outer wall"
        p.features[p.feature[2].toInt()] shouldBe "Top surface"
        p.xyz[2 * 6 + 2] shouldBe 0.2f; p.xyz[2 * 6 + 5] shouldBe 0.45f
        p.layerTop(1) shouldBe (0.45 plusOrMinus 1e-6)
    }
    "absolute E: a move extrudes only where E rises (a retraction's return is not printed)" {
        val p = GcodeToolpaths.parse("""
            M82
            ;LAYER_CHANGE
            G1 X10 Y0 Z0.2 E1.0
            G1 E0.2
            G1 X20 Y0
            G1 E1.0
            G1 X30 Y0 E1.5
            G92 E0
            G1 X40 Y0 E0.4
        """.trimIndent())
        p.segments shouldBe 3   // 0 -> 10, 20 -> 30, 30 -> 40 (after the reset)
    }
    "an arc is drawn as chords at most 0.5 mm, its ends on the circle" {
        // a counter-clockwise half turn of radius 5 about (5, 0): 15.7 mm, at least 32 chords
        val p = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\nG1 X0 Y0 Z0.2\nG3 X10 Y0 I5 J0 E1\n")
        p.segments shouldBe 32
        for (s in 0 until p.segments) for (end in 0..1) {
            val x = p.xyz[s * 6 + end * 3].toDouble(); val y = p.xyz[s * 6 + end * 3 + 1].toDouble()
            hypot(x - 5, y) shouldBe (5.0 plusOrMinus 1e-4)
        }
        // counter-clockwise from (0, 0) to (10, 0) about (5, 0) passes below: y negative half way
        p.xyz[16 * 6 + 1].toDouble() shouldBe (-5.0 plusOrMinus 0.05)
    }
    "control: the same arc clockwise passes above" {
        val p = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\nG1 X0 Y0 Z0.2\nG2 X10 Y0 I5 J0 E1\n")
        p.xyz[16 * 6 + 1].toDouble() shouldBe (5.0 plusOrMinus 0.05)
    }
    "fitting reads the print, not the prime line: segments with no feature named leave the bounds" {
        val p = GcodeToolpaths.parse("M83\nG1 X0 Y0 Z0.3\nG1 X200 Y0 E5\n;LAYER_CHANGE\n;TYPE:Outer wall\nG1 X100 Y100 Z0.2\nG1 X110 Y100 E0.4\n")
        p.bounds.toList() shouldBe listOf(100.0, 100.0, 0.2, 110.0, 100.0, 0.2).map { it.toFloat().toDouble() }
    }
    "each line's width, layer height, speed, tool and part fan come from the file; an auxiliary fan is not the part fan" {
        val p = GcodeToolpaths.parse("""
            M83
            ;LAYER_CHANGE
            ;TYPE:Outer wall
            ;WIDTH:0.45
            ;HEIGHT:0.2
            T1
            M106 S128
            M106 P2 S255
            G1 X0 Y0 Z0.2 F6000
            G1 X10 Y0 E0.5
            M107
            ;WIDTH:0.62
            G1 X10 Y10 E0.5 F1800
        """.trimIndent())
        p.segments shouldBe 2
        p.width[0] shouldBe 0.45f; p.width[1] shouldBe 0.62f
        p.height[0] shouldBe 0.2f
        p.speed[0] shouldBe 100f; p.speed[1] shouldBe 30f            // F is mm/min
        p.tool[0].toInt() shouldBe 1
        (p.fan[0].toInt() and 0xFF) shouldBe 128; (p.fan[1].toInt() and 0xFF) shouldBe 0
    }
    "control: a file with no ;WIDTH: leaves the width unknown (NaN), never a made-up value" {
        val p = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\nG1 X0 Y0 Z0.2\nG1 X10 Y0 E0.5\n")
        p.width[0].isNaN() shouldBe true
        p.height[0].isNaN() shouldBe true
    }
    "moves that go somewhere without extruding are travels, by layer; a retraction alone is not" {
        val p = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\nG1 X0 Y0 Z0.2\nG1 X10 Y0 E0.5\nG1 E-0.8\nG1 X50 Y50\nG1 E0.8\n;LAYER_CHANGE\nG1 Z0.4\nG1 X60 Y50 E0.5\nG1 X0 Y0\n")
        // by hand: (10, 0) -> (50, 50) on layer 1, (60, 50) -> (0, 0) on layer 2; the moves up in Z alone are not travels
        p.travels shouldBe 2
        p.travelLayerStart.toList() shouldBe listOf(0, 1, 2)
    }
    "a prime line printed before the first layer marker belongs to the first layer: as many layers as markers" {
        val p = GcodeToolpaths.parse("M83\nG1 X0 Y0 Z0.3\nG1 X100 Y0 E5\n;LAYER_CHANGE\n;TYPE:Outer wall\nG1 X10 Y10 Z0.2\nG1 X20 Y10 E0.4\n;LAYER_CHANGE\nG1 X20 Y20 Z0.4 E0.4\n")
        p.layers shouldBe 2
        p.layerStart.toList() shouldBe listOf(0, 2, 3)
    }
    "what the file states about itself is read, filament summed over its tools; filament by feature from E" {
        val p = GcodeToolpaths.parse("""
            ; generated by plymeld 0.0.1
            ; filament used [mm] = 0.00, 0.00, 4157.68, 0.00
            ; filament used [cm3] = 0.00, 0.00, 10.00, 0.00
            ; estimated printing time (normal mode) = 22m 41s
            M83
            ;LAYER_CHANGE
            ;TYPE:Outer wall
            G1 X0 Y0 Z0.2
            G1 X10 Y0 E0.3
            ;TYPE:Sparse infill
            G1 X10 Y10 E0.9
        """.trimIndent())
        p.stated.printTime shouldBe "22m 41s"
        p.stated.filamentMm shouldBe (4157.68 plusOrMinus 1e-9)
        p.stated.filamentCm3 shouldBe (10.0 plusOrMinus 1e-9)
        p.stated.generator shouldBe "plymeld 0.0.1"
        p.filamentByFeature[p.features.indexOf("Outer wall")] shouldBe (0.3 plusOrMinus 1e-9)
        p.filamentByFeature[p.features.indexOf("Sparse infill")] shouldBe (0.9 plusOrMinus 1e-9)
    }
    "size: the line centers' extent plus one line width across, the top's height above the bed" {
        val p = GcodeToolpaths.parse(";LAYER_CHANGE\nM83\n;TYPE:Outer wall\n;WIDTH:0.4\nG1 X0 Y0 Z0.2\nG1 X59.6 Y0 E1\nG1 X59.6 Y30.6 E1\n;LAYER_CHANGE\nG1 Z48 \nG1 X0 Y30.6 E1\n")
        p.size[0] shouldBe (60.0 plusOrMinus 1e-4)
        p.size[1] shouldBe (31.0 plusOrMinus 1e-4)
        p.size[2] shouldBe (48.0 plusOrMinus 1e-4)
    }
    "relative positioning is refused, not misread" {
        shouldThrow<IllegalStateException> { GcodeToolpaths.parse("G91\nG1 X10 E1\n") }
    }
})
