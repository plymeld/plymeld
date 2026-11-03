package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.gcode.StringRisk
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import plymeld.program.Step

/** The stringing and restart measures (StringRisk) on hand-made programs. µm; a 0.4 mm line, 0.2 mm layers. */
class StringRiskTest : StringSpec({
    fun square(x0: Double, y0: Double, side: Double, feature: String = "Outer wall", retractBefore: Boolean = true): Extrude {
        fun u(v: Double) = Math.round(v * 1000)
        return Extrude(feature, longArrayOf(u(x0), u(y0), u(x0 + side), u(y0), u(x0 + side), u(y0 + side), u(x0), u(y0 + side)), true, 200, 400, 200, retractBefore = retractBefore)
    }
    fun line(x0: Double, y0: Double, x1: Double, y1: Double, feature: String, retractBefore: Boolean = true): Extrude {
        fun u(v: Double) = Math.round(v * 1000)
        return Extrude(feature, longArrayOf(u(x0), u(y0), u(x1), u(y1)), false, 200, 400, 200, retractBefore = retractBefore)
    }
    fun program(vararg steps: Step) = PrintProgram(listOf(LayerStart(0, 200, 200)) + steps.toList(), emptyList())

    "a travel between two islands over open air without a retraction is a string; with one it is a visible restart" {
        // two 10 mm squares 20 mm apart; the second's travel is marked hidden (no retraction) although it crosses open air
        val unretracted = program(square(0.0, 0.0, 10.0), square(30.0, 0.0, 10.0, retractBefore = false))
        val r = StringRisk.measure(unretracted, 1.0, 5.0, 1000)
        r.unretractedTravels shouldBe 1
        // the 20 mm gap less what lies inside the two grown hulls (1 mm margin + half a line each side): about 17 mm
        r.openMm shouldBe (17.2 plusOrMinus 0.6)
        r.topMm shouldBe (0.0 plusOrMinus 1e-9)
        r.visibleRestarts shouldBe 0
        val retracted = program(square(0.0, 0.0, 10.0), square(30.0, 0.0, 10.0))
        val q = StringRisk.measure(retracted, 1.0, 5.0, 1000)
        q.unretractedTravels shouldBe 0
        q.stringMm shouldBe (0.0 plusOrMinus 1e-9)
        q.visibleRestarts shouldBe 1
        q.restartStarvedMm shouldBe (5.0 plusOrMinus 1e-9)
    }

    "a travel hidden inside an island counts nothing, unless it crosses a top surface" {
        // infill lines inside the square, each travel hidden
        val hidden = program(square(0.0, 0.0, 20.0), line(2.0, 2.0, 18.0, 2.0, "Sparse infill", retractBefore = false), line(18.0, 6.0, 2.0, 6.0, "Sparse infill", retractBefore = false))
        StringRisk.measure(hidden, 1.0, 5.0, 1000).stringMm shouldBe (0.0 plusOrMinus 1e-9)
        // the same travels on a top surface: the string lies on the finished top
        val top = program(square(0.0, 0.0, 20.0), line(2.0, 2.0, 18.0, 2.0, "Top surface", retractBefore = false), line(18.0, 6.0, 2.0, 6.0, "Top surface", retractBefore = false))
        val t = StringRisk.measure(top, 1.0, 5.0, 1000)
        t.topMm shouldBe (Math.hypot(2.0 - 0.0, 2.0 - 0.0) + 4.0 plusOrMinus 1e-6)   // from the square's seam to the first line, then the 4 mm hop between lines
        t.stringMm shouldBe (t.topMm plusOrMinus 1e-9)
    }

    "a travel shorter than the filament's minimum travel does not retract: inside the island it is nothing, over air it is a string" {
        val short = program(line(0.0, 0.0, 10.0, 0.0, "Inner wall"), line(10.0, 0.5, 0.0, 0.5, "Inner wall"))
        val r = StringRisk.measure(short, 1.0, 5.0, 1000)
        r.unretractedTravels shouldBe 1
        r.openMm shouldBe (0.5 plusOrMinus 1e-9)   // no island on this layer (open chains): all of it over air
    }
})
