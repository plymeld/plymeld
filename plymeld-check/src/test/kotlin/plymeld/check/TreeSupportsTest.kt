package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.Supports
import plymeld.core.TreeSupports
import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon

/** Axis-aligned square, mm -> µm, counter-clockwise. */
private fun sq(x0: Double, y0: Double, x1: Double, y1: Double): Polygon {
    fun u(v: Double) = Math.round(v * 1000)
    return Polygon(longArrayOf(u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1)))
}

private fun areaMm2(set: List<Polygon>) = set.sumOf { it.signedAreaMm2() }

class TreeSupportsTest : StringSpec({
    val h = 200L                                        // 0.2 mm layers
    val n = 100                                         // 20 mm tall
    val heights = LongArray(n) { h }
    // 45° at 0.2 mm layers: each layer may step out one layer height
    fun params(plateOnly: Boolean) = Supports.Params(overhangStepUm = h, xyGapUm = 300, topGapLayers = 1, interfaceTopLayers = 2,
        interfaceBottomLayers = 0, onPlateOnly = plateOnly, lineWidthUm = 420)
    // values as imported from a Flash Studio project: 45°, tip 0.8, branch 5, 5°, 5 mm
    val tree = TreeSupports.Params(branchAngleDeg = 45.0, tipDiameterUm = 800, branchDiameterUm = 5000, diameterAngleDeg = 5.0, branchDistanceUm = 5000)

    // a 4 x 4 pillar carrying a 30 x 30 slab from layer 80 up
    val pillar = sq(-2.0, -2.0, 2.0, 2.0)
    val slab = sq(-15.0, -15.0, 15.0, 15.0)
    val tShape = List(n) { j -> if (j < 80) listOf(pillar) else listOf(slab) }

    "T shape: branches stand on the plate, never cut into the pillar, and a roof sits under the slab" {
        val s = TreeSupports.generate(tShape, heights, params(plateOnly = true), tree)
        val all = { j: Int -> s[j].walls + s[j].base + s[j].interfaceTop + s[j].interfaceBottom }
        // something reaches the plate (the only thing a branch can stand on here)
        areaMm2(Booleans.unionPositive(s[0].walls)) shouldBeGreaterThan 1.0
        // never inside the pillar grown by the XY gap, on any layer
        for (j in 0 until 80) areaMm2(Booleans.intersection(Booleans.unionPositive(all(j)), Offset.offset(listOf(pillar), 300))) shouldBe 0.0
        // gap layer 79 is empty; the roof covers the overhang on 77..78
        all(79).isEmpty() shouldBe true
        areaMm2(s[78].interfaceTop) shouldBeGreaterThan 800.0            // slab 900 minus the pillar and its gap
        areaMm2(s[77].interfaceTop) shouldBeGreaterThan 800.0
        s[76].interfaceTop.isEmpty() shouldBe true
    }

    "plymeld's lean: a branch shifts the overhang step per layer; one layer height at 0.2 mm is the 45 deg lean; control: no step differs" {
        fun plate(t: TreeSupports.Params) = TreeSupports.generate(tShape, heights, params(plateOnly = true), t).map { areaMm2(Booleans.unionPositive(it.walls + it.base)) }
        val byStep = TreeSupports.Params(branchAngleDeg = null, tipDiameterUm = 800, branchDiameterUm = 5000, diameterAngleDeg = 5.0, branchDistanceUm = 5000, leanStepUm = h)
        plate(byStep) shouldBe plate(tree)
        val still = TreeSupports.Params(branchAngleDeg = null, tipDiameterUm = 800, branchDiameterUm = 5000, diameterAngleDeg = 5.0, branchDistanceUm = 5000, leanStepUm = 0)
        (plate(still) != plate(tree)) shouldBe true
    }

    // the same slab over a 20 x 20 block up to layer 40: branches above the block cannot reach the plate
    val onBlock = List(n) { j -> if (j < 40) listOf(sq(-10.0, -10.0, 10.0, 10.0)) else if (j < 80) listOf(pillar) else listOf(slab) }

    "branches that cannot dodge the object rest on it; plate only removes them" {
        val rest = TreeSupports.generate(onBlock, heights, params(plateOnly = false), tree)
        val only = TreeSupports.generate(onBlock, heights, params(plateOnly = true), tree)
        // just above the block top, branches stand over the block (inside its outline) only when allowed
        val overBlock = listOf(Offset.offset(listOf(sq(-10.0, -10.0, 10.0, 10.0)), -1000).first())
        areaMm2(Booleans.intersection(Booleans.unionPositive(rest[40].walls), overBlock)) shouldBeGreaterThan 1.0
        areaMm2(Booleans.intersection(Booleans.unionPositive(only[40].walls), overBlock)) shouldBe 0.0
        // control: plate-only still supports the slab's outer ring, from the plate
        areaMm2(Booleans.unionPositive(only[0].walls)) shouldBeGreaterThan 1.0
    }

    "control: a straight column needs no support" {
        val column = List(n) { listOf(pillar) }
        TreeSupports.generate(column, heights, params(plateOnly = false), tree).all { it.walls.isEmpty() && it.base.isEmpty() && it.interfaceTop.isEmpty() } shouldBe true
    }
})
