package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * How thin an area of one filament goes to the filament beside it (`plan.layer.sliver.width`): an object of a 20 mm part
 * on tool 1 with a strip 0.7 mm wide on tool 2 along its side, written by the Job and read from the g-code. With the
 * width set to 1 mm the strip is the first part's: nothing of the object is laid by the second tool. Control: unset, the
 * strip prints in its own filament (0.7 mm is wide enough for a wall line).
 */
class SliverWidthTest : StringSpec({
    /** The object's extruding moves laid by the second tool (T1). */
    fun secondToolMoves(vararg extra: Pair<String, Any>): Int {
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2", *extra,
            models = listOf(plymeld.config.ModelObject("o", listOf("A", "B"), listOf(1, 2), "o.3mf")))
        val setup = base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"), listOf(
            PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", "spool 2", Values(Entity.INTENT, "B"))))))
        val objs = listOf("o" to listOf(testBox(40f, 100f, 0f, 60f, 120f, 3f), testBox(60f, 100f, 0f, 60.7f, 120f, 3f)))
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
        val g = java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString()
        var tool = ""; var inObject = false; var n = 0
        for (line in g.lineSequence()) {
            val l = line.trim()
            when {
                Regex("^T\\d+$").matches(l) -> tool = l
                l.startsWith("; object start") -> inObject = true
                l.startsWith("; object end") -> inObject = false
                inObject && tool == "T1" && l.startsWith("G1 ") && " X" in l && Regex(" E[0-9.]").containsMatchIn(l) -> n++
            }
        }
        return n
    }

    "a 0.7 mm strip of a second filament goes to the first with the sliver width at 1 mm; control: unset, it prints in its own" {
        val unset = secondToolMoves(); val handed = secondToolMoves("plan.layer.sliver.width" to "1 mm")
        println("the object's moves by the second tool: unset $unset, sliver width 1 mm $handed")
        (unset > 0) shouldBe true
        handed shouldBe 0
    }
})
