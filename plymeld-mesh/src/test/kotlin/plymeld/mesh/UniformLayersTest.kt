package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * Uniform layers end exactly on the model's top, changing as few layers as they can inside the
 * tool's thinnest layer. First layer 0.2, layers 0.2, thinnest 0.08;
 * expected tops worked by hand.
 */
class UniformLayersTest : StringSpec({
    fun plan(top: Double) = LayerPlan.uniform(0.0, top, 0.2, 0.2, 0.08)
    fun heights(p: LayerPlan) = p.tops.indices.map { i -> p.tops[i] - (if (i == 0) 0.0 else p.tops[i - 1]) }

    "a top on a whole layer: every layer 0.2, the last on the top" {
        val p = plan(10.0)
        p.count shouldBe 50
        p.tops.last() shouldBe 10.0
        heights(p).forEach { it shouldBe (0.2 plusOrMinus 1e-9) }
    }
    "a remainder the tool can lay (0.1 >= 0.08) is a layer of its own; control: the rest stay 0.2" {
        val p = plan(10.1)
        p.count shouldBe 51
        p.tops.last() shouldBe 10.1
        heights(p).last() shouldBe (0.1 plusOrMinus 1e-9)
        heights(p).dropLast(1).forEach { it shouldBe (0.2 plusOrMinus 1e-9) }
    }
    "a remainder too thin to lay (0.05 < 0.08) is shared with the layer below: two layers of 0.125" {
        val p = plan(10.05)
        p.count shouldBe 51
        p.tops.last() shouldBe 10.05
        heights(p).takeLast(2).forEach { it shouldBe (0.125 plusOrMinus 1e-9) }
        heights(p).dropLast(2).forEach { it shouldBe (0.2 plusOrMinus 1e-9) }
    }
    "thinner than the first layer: one layer, the model's height; a thin remainder over the first layer joins it" {
        plan(0.15).tops.toList() shouldBe listOf(0.15)
        plan(0.25).tops.toList() shouldBe listOf(0.25)
    }
    "slices are mid-layer" {
        val p = plan(10.05)
        for (i in 0 until p.count) p.slices[i] shouldBe ((p.tops[i] + (if (i == 0) 0.0 else p.tops[i - 1])) / 2 plusOrMinus 1e-9)
    }
    "a curved band's layers one thickness above its start (the wave tile: 0.28 first layer, top 4.0); control: uniform leaves a 0.12 remainder" {
        val u = LayerPlan.uniform(0.0, 4.0, 0.28, 0.2, 0.08)
        heights(u).last() shouldBe (0.12 plusOrMinus 1e-9)                    // 0.28 + 18 x 0.2 = 3.88, then 0.12
        val p = u.evenAbove(0.28, 0.2)
        p.tops.first() shouldBe 0.28
        p.tops.last() shouldBe 4.0
        p.count shouldBe 20                                                    // the first, then 19 of 3.72 / 19
        heights(p).drop(1).forEach { it shouldBe (3.72 / 19 plusOrMinus 1e-9) }
        for (i in 0 until p.count) p.slices[i] shouldBe ((p.tops[i] + (if (i == 0) 0.0 else p.tops[i - 1])) / 2 plusOrMinus 1e-9)
        // a band starting between grid tops keeps the grid up to the last top under its start
        val q = u.evenAbove(1.0, 0.2)
        q.tops.take(4).toList().zip(listOf(0.28, 0.48, 0.68, 0.88)).forEach { (a, b) -> a shouldBe (b plusOrMinus 1e-9) }
        heights(q).drop(4).forEach { it shouldBe ((4.0 - 0.88) / 16 plusOrMinus 1e-9) }
    }
    "layers at the tool's thinnest: a thin remainder joins the last layer as one, never two below the minimum" {
        // 0.08 mm layers, thinnest 0.08, top at 10.03: whole layers to 10.0 (first 0.08), remainder 0.03;
        // two of (0.08 + 0.03) / 2 = 0.055 would be thinner than the nozzle lays, so one of 0.11
        val p = LayerPlan.uniform(0.0, 10.03, 0.08, 0.08, 0.08)
        p.tops.last() shouldBe 10.03
        val hs = p.tops.indices.map { i -> p.tops[i] - (if (i == 0) 0.0 else p.tops[i - 1]) }
        hs.last() shouldBe (0.11 plusOrMinus 1e-9)
        hs.forEach { (it >= 0.08 - 1e-9) shouldBe true }
    }
    "snapped to a Z step: every top on the nearest step, a layer the snap leaves without height joined to the next; control: unsnapped, off the steps" {
        val p = LayerPlan(doubleArrayOf(0.21, 0.42, 0.43, 0.63), doubleArrayOf(0.105, 0.315, 0.425, 0.53))
        val s = p.snapped(0.0, 0.04)
        s.tops.toList().map { Math.round(it * 1000) } shouldBe listOf(200L, 440L, 640L)       // 0.43 snaps to 0.44 too: joined
        s.slices.toList().map { Math.round(it * 1000) } shouldBe listOf(100L, 320L, 540L)
        p.tops.any { Math.abs(it / 0.04 - Math.round(it / 0.04)) > 1e-6 } shouldBe true
    }
})
