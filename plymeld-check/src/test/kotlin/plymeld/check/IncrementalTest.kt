package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.IncrementalSlicer
import plymeld.mesh.StlReader
import java.io.StringWriter

/**
 * Incremental re-slicing: after a settings change, only the stages that read the changed setting re-run, and
 * the result is byte-identical to slicing from scratch with the new settings (the guard
 * against a stage reading a setting it did not declare in its key). On the hole block (4 mm tall, a 12 mm hole).
 * The in-island retraction is locked, so its decision (which re-measures every slice) does not run.
 */
class IncrementalTest : StringSpec({
    val soup by lazy { StlReader.read(testModelsDir().resolve("holeblock.stl")) }
    val locked = "plan.mechanism.motion.no_retract_in_island" to true

    fun setup(vararg change: Pair<String, Any>) = testSetup(locked, *change)
    fun fresh(vararg change: Pair<String, Any>): String = StringWriter().also { IncrementalSlicer(listOf(soup), "test").slice(setup(*change), it) }.toString()

    val cases = listOf(
        Triple("plan.mechanism.motion.visible.speed", "120 mm/s", listOf("gcode")),
        // regions too: 100 % density turns sparse into solid there (and the infill's minimum area reads the sparse
        // pieces); the regions stage once ignored the density, so a change to or from 100 % could reuse stale regions
        Triple("plan.layer.infill.density", "35%", listOf("regions", "paths", "gcode")),
        Triple("plan.layer.top.thickness", "1 mm", listOf("regions", "paths", "gcode")),
        Triple("plan.layer.walls.thickness", "1.32 mm", listOf("walls", "regions", "paths", "gcode")),
        Triple("plan.technique.flat.layer.height", "0.16 mm", listOf("slice", "walls", "regions", "paths", "gcode")),
    )

    for ((key, value, expected) in cases) {
        "changing $key re-runs exactly $expected, and matches a from-scratch slice" {
            val inc = IncrementalSlicer(listOf(soup), "test")
            val first = StringWriter()
            inc.slice(setup(), first) shouldBe listOf("slice", "walls", "regions", "paths", "gcode")
            val out = StringWriter()
            val t0 = System.nanoTime()
            val ran = inc.slice(setup(key to value), out)
            val ms = (System.nanoTime() - t0) / 1e6
            println("incremental ($key=$value): ran $ran in ${"%.0f".format(ms)} ms")
            ran shouldBe expected
            (out.toString() == fresh(key to value)) shouldBe true
            (out.toString() == first.toString()) shouldBe false   // control: the change had an effect
        }
    }

    "no change at all re-runs only the g-code writer" {
        val inc = IncrementalSlicer(listOf(soup), "test")
        inc.slice(setup(), StringWriter())
        inc.slice(setup(), StringWriter()) shouldBe listOf("gcode")
    }

    "with the retraction decided, not locked, the decision runs again on every slice (control for the lock above)" {
        val inc = IncrementalSlicer(listOf(soup), "test")
        inc.slice(testSetup(), StringWriter())
        inc.slice(testSetup(), StringWriter()) shouldBe listOf("retraction", "gcode")
    }
})
