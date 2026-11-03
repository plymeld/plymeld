package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

import plymeld.core.Job
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose

import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.Resolver
import plymeld.model.Sourced
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * A plate whose objects have settings of their own (a project's per-object keys): each object's
 * paths print with its own settings, whatever order the objects print in.
 */
class PlateSettingsTest : StringSpec({
    /** Two generated objects: a short block (20 x 20 x 10 mm) and a taller one (30 x 20 x 20 mm), placed with their box starting at (x, y). */
    val shapes = mapOf("block.stl" to Triple(20f, 20f, 10f), "tall.stl" to Triple(30f, 20f, 20f))
    fun stl(n: String, x: Double = 100.0, y: Double = 100.0): plymeld.mesh.TriangleSoup {
        val (sx, sy, sz) = shapes.getValue(n)
        return testBox(x.toFloat(), y.toFloat(), 0f, x.toFloat() + sx, y.toFloat() + sy, sz)
    }

    /** Feed rates (mm/s) of outer-wall moves, by exclude-object name. */
    fun outerWallSpeeds(gcode: String): Map<String, Set<Long>> {
        val out = HashMap<String, MutableSet<Long>>()
        var obj = ""; var feature = ""
        for (line in gcode.lineSequence()) {
            when {
                line.startsWith("EXCLUDE_OBJECT_START NAME=") -> obj = line.substringAfter('=')
                line.startsWith("EXCLUDE_OBJECT_END") -> obj = ""
                line.startsWith("; FEATURE:") -> feature = line.substringAfter(':').trim()
                line.startsWith("G1 F") && feature == "Outer wall" && obj.isNotEmpty() ->
                    out.getOrPut(obj) { HashSet() } += line.substringAfter("G1 F").trim().toLong() / 60
            }
        }
        return out
    }

    fun plate(order: ObjectOrder): Pair<String, List<String>> {
        val base = testSetup()
        val a = "block.stl" to listOf(stl("block.stl", 40.0, 100.0)); val b = "tall.stl" to listOf(stl("tall.stl", 180.0, 100.0))
        fun h(o: Pair<String, List<plymeld.mesh.TriangleSoup>>) = o.second.single().bounds().let { it[5] - it[2] }
        // setup object 0 is the TALLER one, so one-at-a-time (shortest first) must reorder
        val objs = listOf(a, b).sortedByDescending(::h)
        val fast = Values(Entity.INTENT, "object overrides").also { it["plan.mechanism.motion.visible.speed"] = Sourced(123.0, "test") }
        val setup = base.withObjectSetups(listOf(
            ObjectSetup(objs[0].first, base.objects[0].intent, "spool 1", fast),
            ObjectSetup(objs[1].first, base.objects[0].intent, "spool 1", Values(Entity.INTENT, "none")),
        ))
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = order))
        val w = java.io.StringWriter()
        Job(plan.layout, setup, "test").write(w)
        val project0 = (Resolver.resolve(base, "plan.mechanism.motion.visible.speed").value as Number).toLong()
        return w.toString() to listOf(objs[0].first, objs[1].first, project0.toString())
    }

    for (order in listOf(ObjectOrder.TOGETHER, ObjectOrder.ONE_AT_A_TIME)) {
        "${order.name.lowercase()}: the object with its own outer-wall speed prints its walls at it; the other at the project's" {
            val (gcode, info) = plate(order)
            val speeds = outerWallSpeeds(gcode)
            val tall = speeds.entries.single { it.key.startsWith(info[0].replace(Regex("\\s+"), "_")) }
            val short = speeds.entries.single { it.key.startsWith(info[1].replace(Regex("\\s+"), "_")) }
            // the overridden speed 123 is the fastest outer-wall rate on its object (slower ones: first
            // layer, minimum layer time), and appears nowhere on the other object — whose own fastest
            // rate is its settings' (project 200, capped by the volumetric limit; slower alone, cooling)
            tall.value.max() shouldBe 123L
            (123L in short.value) shouldBe false
            println("${order.name}: outer-wall rates, overridden object ${tall.value.sorted()}, other ${short.value.sorted()} (project speed ${info[2]})")
            // the tall object's g-code name carries its SETUP index (0), even when it prints second
            tall.key shouldContain "_id_0_"
        }
    }

    "a plate re-sliced with changed settings: same bytes as a fresh job, fewer stages run" {
        val base = testSetup()
        fun setupWith(density: Double?) = base.withObjectSetups(listOf("a", "b").map { n ->
            ObjectSetup(n, base.objects[0].intent, "spool 1", Values(Entity.INTENT, n).also { v -> density?.let { v["plan.layer.infill.density"] = Sourced(it, "test") } })
        })
        val objs = listOf("a" to listOf(stl("tall.stl", 40.0, 100.0)), "b" to listOf(stl("block.stl", 180.0, 100.0)))
        val s0 = setupWith(null); val s1 = setupWith(0.35)
        val plan = Plan.makeFor(objs, s0, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
        val job = Job(plan.layout, s0, "test")
        val first = java.io.StringWriter().also { job.write(it) }.toString()
        job.stageMs.all { "slice" in it } shouldBe true
        job.update(s1)
        val again = java.io.StringWriter().also { job.write(it) }.toString()
        val fresh = java.io.StringWriter().also { Job(Plan.makeFor(objs, s1, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER)).layout, s1, "test").write(it) }.toString()
        (again == fresh) shouldBe true
        job.stageMs.none { "slice" in it } shouldBe true            // the mesh was not sliced again
        (again == first) shouldBe false                              // control: the density change reached the g-code
    }

    "control: objects on different spools are refused (changes between objects on a plate are not written yet)" {
        val base = testSetup(slots = 2)
        (base.spools.size >= 2) shouldBe true      // a filament slot each on the one tool
        val setup = base.withObjectSetups(listOf(
            ObjectSetup("a", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "a")),
            ObjectSetup("b", base.objects[0].intent, "spool 2", Values(Entity.INTENT, "b")),
        ))
        val objs = listOf("a" to listOf(stl("tall.stl", 40.0, 100.0)), "b" to listOf(stl("block.stl", 180.0, 100.0)))
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
        shouldThrow<IllegalArgumentException> { Job(plan.layout, setup, "test") }.message!! shouldContain "spool 1, spool 2"
    }
})
