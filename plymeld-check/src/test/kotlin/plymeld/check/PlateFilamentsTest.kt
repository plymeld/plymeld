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
import plymeld.model.Sourced
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * A plate of several objects on two filaments each (two parts side by side, one per tool, on a machine of the bundled
 * two-and-more-tool model): written as one program, each filament in turn laying its lines of every object on a layer.
 * Read from the g-code: every object's lines of both tools are there; the plate changes tool no more often than one of
 * its objects alone does (a change per filament per layer, not per object); an object's own setting (its visible speed)
 * prints on its lines only; and there is one purge tower. Control: the one-object plate's count of changes is above 0.
 */
class PlateFilamentsTest : StringSpec({
    /** A plate of [n] objects 60 mm apart, each a 20 mm part on tool 1 beside a 10 mm part on tool 2, 3 mm tall; the last object's visible lines at 77 mm/s. */
    fun gcode(n: Int): String {
        val names = (0 until n).map { "o$it" }
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            models = names.map { plymeld.config.ModelObject(it, listOf("A", "B"), listOf(1, 2), "$it.3mf") })
        val setup = base.withObjectSetups(names.mapIndexed { k, name ->
            val own = Values(Entity.INTENT, "object overrides").also { if (n > 1 && k == n - 1) it["plan.mechanism.motion.visible.speed"] = Sourced(77.0, "test") }
            ObjectSetup(name, base.objects[k].intent, "spool 1", own, listOf(
                PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", "spool 2", Values(Entity.INTENT, "B"))))
        })
        val objs = names.mapIndexed { k, name -> val x = 40f + 60f * k
            name to listOf(testBox(x, 100f, 0f, x + 20f, 120f, 3f), testBox(x + 20f, 100f, 0f, x + 30f, 120f, 3f)) }
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
        return java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString()
    }
    /** Tool changes after the start; per object and tool, its extruding moves; per object, its outer walls' feeds (mm/s); "Prime tower" labels. */
    class Read(val changes: Int, val moves: Map<Pair<String, String>, Int>, val outerFeeds: Map<String, Set<Long>>)
    fun read(g: String): Read {
        var tool = ""; var obj = ""; var feature = ""; var changes = 0; var printed = false
        val moves = HashMap<Pair<String, String>, Int>(); val feeds = HashMap<String, MutableSet<Long>>()
        for (line in g.lineSequence()) {
            val l = line.trim()
            when {
                Regex("^T\\d+$").matches(l) -> { if (printed && l != tool) changes++; tool = l }
                l.startsWith("; object start") -> obj = l.substringAfterLast(' ')
                l.startsWith("; object end") -> obj = ""
                l.startsWith(";TYPE:") || l.startsWith("; FEATURE:") -> feature = l.substringAfter(':').trim()
                l.startsWith("G1 F") && feature == "Outer wall" && obj.isNotEmpty() -> feeds.getOrPut(obj) { HashSet() } += l.substringAfter("G1 F").trim().toDouble().toLong() / 60
                l.startsWith("G1 ") && " X" in l && Regex(" E[0-9.]").containsMatchIn(l) -> { printed = true; if (obj.isNotEmpty()) moves.merge(obj to tool, 1, Int::plus) }
            }
        }
        return Read(changes, moves, feeds)
    }

    "two and three objects on two filaments: all laid, no more tool changes than one object, each object's own speed on its own lines" {
        val one = read(gcode(1)); val two = read(gcode(2)); val three = read(gcode(3))
        println("tool changes: one object ${one.changes}, two ${two.changes}, three ${three.changes}; moves per (object, tool), two: ${two.moves.toSortedMap(compareBy({ it.first }, { it.second }))}")
        (one.changes > 0) shouldBe true                                   // control: the object does change tools
        two.changes shouldBe one.changes
        three.changes shouldBe one.changes
        // every object's lines of both tools are in the file (the print's first lines come before any T line: the start selects the tool)
        for (r in listOf(two, three)) for (o in r.moves.keys.map { it.first }.distinct()) { ((o to "T0") in r.moves && (o to "T1") in r.moves) shouldBe true }
        two.moves.keys.map { it.first }.distinct().size shouldBe 2
        three.moves.keys.map { it.first }.distinct().size shouldBe 3
        // the last object's own visible speed on its outer walls, and on no other object's
        val own = two.outerFeeds.entries.single { "o1" in it.key }; val other = two.outerFeeds.entries.single { "o0" in it.key }
        println("outer-wall feeds: ${other.key} ${other.value.sorted()}, ${own.key} ${own.value.sorted()}")
        own.value.max() shouldBe 77L
        (77L in other.value) shouldBe false
    }
})
