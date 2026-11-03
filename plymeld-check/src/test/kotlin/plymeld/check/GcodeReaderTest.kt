package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.BufferedReader
import java.io.StringReader

/** The g-code reader on hand-written files: extrusion modes, a refused mode, and a header the file does not match. */
class GcodeReaderTest : StringSpec({
    "relative and absolute E modes and G92 are tracked" {
        val g = """
            M83
            ; CHANGE_LAYER
            ; Z_HEIGHT: 0.2
            G1 X0 Y0
            G1 X10 Y0 E1.5
            G1 X10 Y10 E2.5
            M82
            G92 E0
            G1 X0 Y10 E3
            G1 X0 Y0 E5
            G1 X5 Y5
        """.trimIndent()
        val s = GcodeReader.parse(BufferedReader(StringReader(g)))
        s.layers.size shouldBe 1
        s.totalExtrudedMm shouldBe (1.5 + 2.5 + 3.0 + 2.0 plusOrMinus 1e-9)
        s.totalExtrudeMoveMm shouldBe (40.0 plusOrMinus 1e-9)
        s.totalTravelMm shouldBe (Math.sqrt(50.0) plusOrMinus 1e-9)
        s.layers[0].segCount shouldBe 4
    }

    "control: relative XYZ (G91) is refused rather than mis-tracked" {
        shouldThrow<IllegalStateException> { GcodeReader.parse(BufferedReader(StringReader("G91\nG1 X1\n"))) }
    }

    "control: a file claiming more layers than it has is caught" {
        val g = "; total layer number: 3\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X1 Y1 E1\n"
        val s = GcodeReader.parse(BufferedReader(StringReader(g)))
        (s.layers.size == s.headerTotalLayers) shouldBe false
    }
})
