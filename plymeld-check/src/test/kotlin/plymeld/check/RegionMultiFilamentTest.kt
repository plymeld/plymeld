package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.core.decide.Region
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * A region's speed cap (plan.object.<key>.region.<key>.mechanism.motion.speed.max) on an object laid per part (two parts
 * side by side, one per tool, on a machine of the bundled two-and-more-tool model), alone and on a plate of two such objects:
 * read from the g-code, every line in the capped object's blocks above the first layer prints at most 20 mm/s, and nothing
 * else changes speed: on the plate the other object's lines print as on the plate without the region, and the purge tower's
 * lines (outside every object's block) keep a speed above the cap; and an object on one filament on such a plate (laid by
 * the plate's per-part program too) gets its region's cap as well. Controls: without the region the object's lines there
 * print faster than the cap.
 */
class RegionMultiFilamentTest : StringSpec({
    val capMmS = 20.0
    /** The region: the whole object from its bottom, its lines at most [capMmS]. */
    fun slow() = Region("slow", null, 0.0, null, null, capMmS, null, "test")

    /**
     * A plate of [n] objects 60 mm apart, each a 20 mm part on tool 1 beside a 10 mm part on tool 2, 3 mm tall; [regionOn],
     * the objects (by index) given the region; [oneFilament], the objects with both parts on tool 1 (laid as one).
     */
    fun gcode(n: Int, regionOn: Set<Int>, oneFilament: Set<Int> = emptySet()): String {
        val names = (0 until n).map { "o$it" }
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            models = names.map { plymeld.config.ModelObject(it, listOf("A", "B"), listOf(1, 2), "$it.3mf") })
        val setup = base.withObjectSetups(names.mapIndexed { k, name ->
            ObjectSetup(name, base.objects[k].intent, "spool 1", Values(Entity.INTENT, "object overrides"), listOf(
                PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", if (k in oneFilament) "spool 1" else "spool 2", Values(Entity.INTENT, "B"))))
        })
        val objs = names.mapIndexed { k, name -> val x = 40f + 60f * k
            name to listOf(testBox(x, 100f, 0f, x + 20f, 120f, 3f), testBox(x + 20f, 100f, 0f, x + 30f, 120f, 3f)) }
        val locks = names.indices.map { k -> Plan.Companion.ObjectLocks(regions = if (k in regionOn) listOf(slow()) else emptyList()) }
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER, objects = locks))
        return java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString()
    }

    /** The feeds (mm/s) of the extruding moves above the first layer: per object by its block's name, and those outside every block under "". */
    fun feeds(g: String): Map<String, List<Double>> {
        val out = HashMap<String, MutableList<Double>>()
        var obj = ""; var layers = 0; var f = 0.0; var x = Double.NaN; var y = Double.NaN
        for (line in g.lineSequence()) {
            val l = line.trim()
            when {
                l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER") -> layers++
                l.startsWith("; object start") -> obj = l.substringAfterLast(' ')
                l.startsWith("; object end") -> obj = ""
                l.startsWith("G1 ") -> {
                    val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.length > 1 }.associate { it[0] to it.substring(1).toDouble() }
                    w['F']?.let { f = it / 60.0 }
                    val nx = w['X'] ?: x; val ny = w['Y'] ?: y
                    val e = w['E']
                    if (layers > 1 && e != null && e > 0 && !x.isNaN() && (nx != x || ny != y)) out.getOrPut(obj) { ArrayList() } += f
                    x = nx; y = ny
                }
            }
        }
        return out
    }
    /** The one block name holding [name] (the writer may decorate it). */
    fun Map<String, List<Double>>.of(name: String) = entries.single { it.key.isNotEmpty() && name in it.key }.value

    "an object laid per part: its lines at most the region's speed, the tower's not; control: without the region, faster" {
        val plain = feeds(gcode(1, emptySet())); val capped = feeds(gcode(1, setOf(0)))
        println("one object, above its first layer: without the region ${plain.of("o0").max()} mm/s at most, with it ${capped.of("o0").max()} mm/s; the tower ${capped[""]?.max()} mm/s")
        (plain.of("o0").max() > capMmS) shouldBe true                     // control: the cap has lines to slow
        (capped.of("o0").size > 50) shouldBe true
        capped.of("o0").all { it <= capMmS + 1e-6 } shouldBe true
        (capped.getValue("").max() > capMmS) shouldBe true                // the tower, outside the object's blocks, at its own speed
    }

    "a plate of two objects laid per part, the region on one: its lines capped, the other's as without it; control: without the region, faster" {
        val plain = feeds(gcode(2, emptySet())); val capped = feeds(gcode(2, setOf(1)))
        println("plate, above the first layer: o1 without the region ${plain.of("o1").max()} mm/s at most, with it ${capped.of("o1").max()} mm/s; o0 ${capped.of("o0").max()} mm/s; the tower ${capped[""]?.max()} mm/s")
        (plain.of("o1").max() > capMmS) shouldBe true                     // control: the cap has lines to slow
        (capped.of("o1").size > 50) shouldBe true
        capped.of("o1").all { it <= capMmS + 1e-6 } shouldBe true
        // the object without the region: its lines' speeds as on the plate without it (and above the cap)
        capped.of("o0") shouldBe plain.of("o0")
        (capped.of("o0").max() > capMmS) shouldBe true
        (capped.getValue("").max() > capMmS) shouldBe true
    }

    "a one-filament object on a plate with an object laid per part, the region on it: its lines capped; control: without the region, faster" {
        val plain = feeds(gcode(2, emptySet(), oneFilament = setOf(1))); val capped = feeds(gcode(2, setOf(1), oneFilament = setOf(1)))
        println("plate with one object on one filament, above the first layer: o1 without the region ${plain.of("o1").max()} mm/s at most, with it ${capped.of("o1").max()} mm/s; o0 ${capped.of("o0").max()} mm/s")
        (plain.of("o1").max() > capMmS) shouldBe true                     // control: the cap has lines to slow
        (capped.of("o1").size > 50) shouldBe true
        capped.of("o1").all { it <= capMmS + 1e-6 } shouldBe true
        capped.of("o0") shouldBe plain.of("o0")
    }
})
