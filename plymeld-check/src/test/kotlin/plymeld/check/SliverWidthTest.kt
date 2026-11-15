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
 * on tool 1 with a strip 0.7 mm wide on tool 2 along its side, written by the Job and read from the g-code.
 *  - the strip between the part and a second part of tool 1's filament (inside the object, its ends meeting the outline end
 *    on): with the width set to 1 mm it goes to the first filament, nothing of the object laid by the second tool. Control:
 *    unset, it prints in its own filament (0.7 mm is wide enough for a wall line);
 *  - the strip along the object's side (on its surface): with the width at 1 mm it stays its own by default
 *    (plan.layer.sliver.at_surface = own), and goes to the first filament with at_surface = neighbor.
 */
class SliverWidthTest : StringSpec({
    /** The object's extruding moves laid by the second tool (T1); [inside]: a part of filament 1 beyond the strip too. */
    fun secondToolMoves(inside: Boolean, vararg extra: Pair<String, Any>): Int {
        val names = if (inside) listOf("A", "B", "C") else listOf("A", "B")
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2", *extra,
            models = listOf(plymeld.config.ModelObject("o", names, names.map { if (it == "B") 2 else 1 }, "o.3mf")))
        val setup = base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"),
            names.map { PartSetup(it, if (it == "B") "spool 2" else "spool 1", Values(Entity.INTENT, it)) })))
        val parts = listOf(testBox(40f, 100f, 0f, 60f, 120f, 3f), testBox(60f, 100f, 0f, 60.7f, 120f, 3f)) +
            (if (inside) listOf(testBox(60.7f, 100f, 0f, 70f, 120f, 3f)) else emptyList())
        val plan = Plan.makeFor(listOf("o" to parts), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
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

    "a 0.7 mm strip of a second filament inside the object goes to the first with the sliver width at 1 mm; control: unset, it prints in its own" {
        val unset = secondToolMoves(true); val handed = secondToolMoves(true, "plan.layer.sliver.width" to "1 mm")
        println("inside: the object's moves by the second tool: unset $unset, sliver width 1 mm $handed")
        (unset > 0) shouldBe true
        handed shouldBe 0
    }

    "along the object's side the strip stays its own at 1 mm by default; with at_surface = neighbor it goes to the first" {
        val own = secondToolMoves(false, "plan.layer.sliver.width" to "1 mm")
        val neighbor = secondToolMoves(false, "plan.layer.sliver.width" to "1 mm", "plan.layer.sliver.at_surface" to "neighbor")
        println("on the side: the object's moves by the second tool at 1 mm: own $own, neighbor $neighbor")
        (own > 0) shouldBe true
        neighbor shouldBe 0
    }
})
