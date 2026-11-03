package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.Planner
import plymeld.core.Supports
import plymeld.core.decide.Insert
import plymeld.core.decide.Pose
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * A planned insert: a 30 x 30 x 8 mm block with a 10 x 10 mm socket from z 2 to z 5 (a sealed pocket), the
 * insert's top at z 5. With the insert's cover the layer over the socket (bottom at 5.0) lays its bottom there as a bottom
 * surface onto the insert, no bridge, and no support is generated in the socket; control: without the cover the roof is
 * bridged and tree support stands in the socket. Also: an insert posed with a tilt is refused, a turn about z is not.
 */
class PlannedInsertTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "0.05 mm", "hardware.tool.t1.layer.height.max" to "0.3 mm",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.technique.flat.layer.height" to "0.2 mm", "plan.technique.flat.core.layers" to "1", "plan.support.enabled" to "true")
    val n = 40
    val tops = LongArray(n) { 200L * (it + 1) }
    val outer = Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 30_000, 0, 30_000))
    // the socket: a hole on the layers whose tops are 2.2 .. 5.0 (layers 10..24); the roof is layer 25 (bottom 5.0)
    val hole = Polygon(longArrayOf(10_000, 10_000, 10_000, 20_000, 20_000, 20_000, 20_000, 10_000))
    val outline = List(n) { i -> if (i in 10..24) listOf(outer, hole) else listOf(outer) }
    val s: SliceSettings = run {
        val all = ConfigFiles.bundled() + seamLockedForTests + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(ModelObject("a", listOf("a"), listOf(1), "a.3mf")))).setup)
    }
    val cover = Supports.Box(10_000, 20_000, 10_000, 20_000, 5_000, 5_000)
    fun plan(covers: List<Supports.Box>): List<LayerPlanOut> {
        val w = Planner.walls(s, outline, tops)
        return Planner.paths(s, outline, w, Planner.regions(s, w), tops, covers = covers)
    }
    /** Every vertex inside the socket's footprint (a support column stands wholly in it). */
    fun inSocket(p: plymeld.core.ToolPath) = (0 until p.xy.size / 2).all { k -> p.xy[2 * k] in 9_500..20_500 && p.xy[2 * k + 1] in 9_500..20_500 }
    /** Some segment's midpoint inside the socket's footprint (a bridge reaches two widths past it to anchor, so its ends lie outside). */
    fun touchesSocket(p: plymeld.core.ToolPath) = (0 until p.xy.size / 2 - 1).any { k ->
        (p.xy[2 * k] + p.xy[2 * k + 2]) / 2 in 10_000..20_000 && (p.xy[2 * k + 1] + p.xy[2 * k + 3]) / 2 in 10_000..20_000 }
    fun socketPaths(l: LayerPlanOut, f: Feature) = l.paths.filter { it.feature == f && touchesSocket(it) }

    "with the cover: the roof over the socket is a bottom surface onto the insert, not a bridge; control: without it, a bridge" {
        val with = plan(listOf(cover)); val without = plan(emptyList())
        (socketPaths(with[25], Feature.BOTTOM).isNotEmpty()) shouldBe true
        socketPaths(with[25], Feature.BRIDGE).size shouldBe 0
        (socketPaths(without[25], Feature.BRIDGE).isNotEmpty()) shouldBe true
        socketPaths(without[25], Feature.BOTTOM).size shouldBe 0
        // the layers beside the roof are unchanged by the cover (support aside)
        fun model(l: LayerPlanOut) = l.paths.map { it.feature }.filter { it != Feature.SUPPORT && it != Feature.SUPPORT_INTERFACE }.sorted()
        for (i in listOf(10, 24, 26)) model(with[i]) shouldBe model(without[i])
    }
    "with the cover: no support in the socket; control: without it, support stands in the socket" {
        val with = plan(listOf(cover)); val without = plan(emptyList())
        val supportIn = { ls: List<LayerPlanOut> -> (10..24).sumOf { i -> ls[i].paths.count { (it.feature == Feature.SUPPORT || it.feature == Feature.SUPPORT_INTERFACE) && inSocket(it) } } }
        supportIn(with) shouldBe 0
        (supportIn(without) > 0) shouldBe true
    }
    "an insert posed: its box and top from the posed object's corner and bottom; a tilt is refused, a turn about z is not" {
        val ins = Insert("ball", 10.0, 20.0, 10.0, 20.0, 2.0, 5.0, 5.0, "test")
        val bounds = doubleArrayOf(-15.0, -15.0, -1.0, 15.0, 15.0, 7.0)   // the model's own frame: the bottom at z -1
        val same = ins.posed(Pose("as given", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0)), bounds)
        same.topMm shouldBe (6.0 plusOrMinus 1e-9)
        same.region.x0 shouldBe (25.0 plusOrMinus 1e-9); same.region.fromMm shouldBe (3.0 plusOrMinus 1e-9)
        same.covers(30.0, 30.0, 6.0) shouldBe true    // the roof's underside, at the top
        same.covers(30.0, 30.0, 5.0) shouldBe false   // below the top: the socket's air
        same.covers(40.0, 30.0, 6.0) shouldBe false   // outside the box
        // a quarter turn about z keeps the socket upright
        ins.posed(Pose("yaw", doubleArrayOf(0.0, -1.0, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 1.0), doubleArrayOf(0.0, 0.0, 0.0)), bounds).topMm shouldBe (6.0 plusOrMinus 1e-9)
        // a tilt about x would turn the socket sideways
        shouldThrow<IllegalArgumentException> { ins.posed(Pose("tilt", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0), doubleArrayOf(0.0, 0.0, 0.0)), bounds) }
        shouldThrow<IllegalArgumentException> { Insert("bad", 10.0, 20.0, 10.0, 20.0, 2.0, 5.0, 6.0, "test") }
    }
})
