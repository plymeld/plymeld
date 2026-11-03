package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.LayerSupport
import plymeld.core.Supports
import plymeld.core.TreeSupports
import plymeld.geom.Booleans
import plymeld.geom.Polygon

/**
 * plan.support.gap.bottom (Supports.clearAboveModel; a gap found reviewing a slice: support was
 * laid straight onto the model's top): a slab carried by a pillar on a 20 x 20 block, support standing on the block's top
 * (layer 39's top, 8.0 mm). With a 0.6 mm gap (three 0.2 mm layers) no support lies over the block on layers 40-42, the
 * next two layers there are bottom interface, then plain support; beside the block (on the bed) nothing changes.
 * Controls: without the gap, support lies on the block from layer 40.
 */
class SupportBottomGapTest : StringSpec({
    fun sq(x0: Double, y0: Double, x1: Double, y1: Double): Polygon {
        fun u(v: Double) = Math.round(v * 1000)
        return Polygon(longArrayOf(u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1)))
    }
    fun area(set: List<Polygon>) = if (set.isEmpty()) 0.0 else Booleans.unionPositive(set).sumOf { it.signedAreaMm2() }
    val h = 200L
    val n = 100
    val tops = LongArray(n) { (it + 1) * h }
    val block = sq(-10.0, -10.0, 10.0, 10.0)
    val model = List(n) { j -> if (j < 40) listOf(block) else if (j < 80) listOf(sq(-2.0, -2.0, 2.0, 2.0)) else listOf(sq(-15.0, -15.0, 15.0, 15.0)) }
    val p = Supports.Params(overhangStepUm = h, xyGapUm = 300, topGapLayers = 1, interfaceTopLayers = 2, interfaceBottomLayers = 2,
        onPlateOnly = false, lineWidthUm = 420)
    fun all(ls: LayerSupport) = ls.base + ls.interfaceTop + ls.interfaceBottom + ls.walls
    fun over(set: List<Polygon>) = if (set.isEmpty()) 0.0 else area(Booleans.intersection(Booleans.unionPositive(set), listOf(block)))
    fun beside(set: List<Polygon>) = if (set.isEmpty()) 0.0 else area(Booleans.difference(Booleans.unionPositive(set), listOf(block)))

    // the bundled defaults through the planner: at 0.08 mm layers the gap on top of support is one layer
    // but at least gap.top.min (0.2 mm: 3 layers), and support on the model leaves out the layer on its top
    "defaults at 0.08 mm layers: 3 layers under the slab, 1 above the block; controls: gap.top.value 0.08 mm, gap.bottom 0" {
        val machine = listOf(
            "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
            "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
            "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
            "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
            "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
            "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
            "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
            "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
            "plan.support.enabled" to true, "plan.technique.flat.layer.height" to "0.08 mm")
        val o = plymeld.config.ModelObject("o", listOf("o"), listOf(1), "o.3mf")
        val h8 = 80L
        val tops8 = LongArray(n) { (it + 1) * h8 }
        fun plan(vararg extra: Pair<String, Any>): List<plymeld.core.LayerPlanOut> {
            val all = plymeld.config.ConfigFiles.bundled() + seamLockedForTests + (machine + extra).map { (k, v) -> plymeld.config.ConfigFiles.Entry(k.split('.'), v, "test") }
            val setup = plymeld.config.ConfigSetup.setup(plymeld.config.Config(plymeld.config.ConfigStack(all), listOf(o))).setup
            return plymeld.core.Planner.plan(plymeld.model.SliceSettings.of(setup, plymeld.model.Context(0)), model, tops8)
        }
        val slabOnly = Booleans.difference(listOf(sq(-15.0, -15.0, 15.0, 15.0)), listOf(block))
        // "over the block": 0.5 mm inside its edge (support beside it is clipped at the edge, its line ends on it)
        val inBlock = listOf(sq(-9.5, -9.5, 9.5, 9.5))
        fun supportIn(l: plymeld.core.LayerPlanOut, region: List<Polygon>): Int = l.paths.filter { it.feature == plymeld.core.Feature.SUPPORT || it.feature == plymeld.core.Feature.SUPPORT_INTERFACE }
            .count { p -> (0 until p.xy.size / 2).any { k -> region.any { r -> plymeld.geom.Polygons.contains(r, p.xy[2 * k], p.xy[2 * k + 1]) } } }
        val def = plan()
        println("support paths beside the block under the slab, layers 74-79: " + (74..79).map { supportIn(def[it], slabOnly) } +
            "; over the block, layers 40-41: " + (40..41).map { supportIn(def[it], inBlock) })
        (77..79).all { supportIn(def[it], slabOnly) == 0 } shouldBe true
        (supportIn(def[76], slabOnly) > 0) shouldBe true
        supportIn(def[40], inBlock) shouldBe 0
        (supportIn(def[41], inBlock) > 0) shouldBe true
        // controls
        val oneTop = plan("plan.support.gap.top.value" to "0.08 mm")
        (supportIn(oneTop[78], slabOnly) > 0) shouldBe true
        supportIn(oneTop[79], slabOnly) shouldBe 0
        (supportIn(plan("plan.support.gap.bottom" to "0 mm")[40], inBlock) > 0) shouldBe true
    }

    "tree support: the same clearance over the block; control: branches rest on it without the gap" {
        val tree = TreeSupports.Params(branchAngleDeg = 45.0, tipDiameterUm = 800, branchDiameterUm = 5000, diameterAngleDeg = 5.0, branchDistanceUm = 5000)
        val gen = TreeSupports.generate(model, LongArray(n) { h }, p, tree)
        val gap = Supports.clearAboveModel(gen, model, tops, 600, interfaceBottom = 2)
        val restsOn = (40..60).sumOf { over(all(gen[it])) }
        println("tree over the block, layers 40-60: without %.0f mm2, with %.0f mm2 (layers 40-42 with: %.0f)".format(restsOn, (40..60).sumOf { over(all(gap[it])) }, (40..42).sumOf { over(all(gap[it])) }))
        (restsOn > 1.0) shouldBe true                                 // control: some branch rests on the block
        for (j in 40..42) over(all(gap[j])) shouldBe 0.0
    }
})
