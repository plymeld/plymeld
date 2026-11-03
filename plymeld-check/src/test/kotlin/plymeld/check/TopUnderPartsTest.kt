package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.Regions
import plymeld.geom.Booleans
import plymeld.geom.Polygon

private fun square(x0: Double, y0: Double, x1: Double, y1: Double): Polygon {
    fun u(v: Double) = Math.round(v * 1000)
    return Polygon(longArrayOf(u(x0), u(y0), u(x1), u(y0), u(x1), u(y1), u(x0), u(y1)))
}
private fun mm2(ps: List<Polygon>) = ps.sumOf { it.signedAreaMm2() }

/**
 * A top surface runs under what stands on it (the same pattern as with nothing on
 * top): a 30 x 30 slab 10 layers tall with a 6 x 6 post standing on its
 * middle, walls 1 mm, top shell 3 layers. Control: without the option the post's footprint is a hole
 * in the top surface and sparse infill runs up under it.
 */
class TopUnderPartsTest : StringSpec({
    val outlines = List(20) { i -> if (i < 10) listOf(square(0.0, 0.0, 30.0, 30.0)) else listOf(square(12.0, 12.0, 18.0, 18.0)) }
    val inners = List(20) { i -> if (i < 10) listOf(square(1.0, 1.0, 29.0, 29.0)) else listOf(square(13.0, 13.0, 17.0, 17.0)) }
    val middle = listOf(square(12.0, 12.0, 18.0, 18.0))

    // the slab and the post as two parts: where each carries on to the next layer
    val slabPart = List(20) { i -> if (i < 10) listOf(square(0.0, 0.0, 30.0, 30.0)) else emptyList() }
    val postPart = List(20) { i -> if (i >= 10) listOf(square(12.0, 12.0, 18.0, 18.0)) else emptyList() }
    fun cover(parts: List<List<List<Polygon>>>) = List(20) { i ->
        if (i + 1 >= 20) emptyList() else Booleans.normalize(parts.flatMap { p -> if (p[i].isEmpty() || p[i + 1].isEmpty()) emptyList() else Booleans.intersection(p[i], p[i + 1]) })
    }

    "on, the post another part: the slab's top is whole (784 mm2), and solid under the post's footprint for the 3 shell layers" {
        val r = Regions.classify(outlines, inners, 3, 3, topUnderParts = true, selfCover = cover(listOf(slabPart, postPart)))
        mm2(r[9].topSurface) shouldBe (784.0 plusOrMinus 1e-6)
        for (j in 6..8) mm2(Booleans.intersection(r[j].sparse, middle)) shouldBe (0.0 plusOrMinus 1e-6)
        mm2(Booleans.intersection(r[5].sparse, middle)) shouldBe (36.0 plusOrMinus 1e-6)     // below the shell: sparse again
        mm2(r[19].topSurface) shouldBe (16.0 plusOrMinus 1e-6)                              // the post's own top as before
    }

    "control: off, the footprint is a hole in the top surface and sparse runs up under the post" {
        val r = Regions.classify(outlines, inners, 3, 3)
        mm2(r[9].topSurface) shouldBe (748.0 plusOrMinus 1e-6)
        (mm2(Booleans.intersection(r[8].sparse, middle)) > 1.0) shouldBe true
    }

    "a hairline top (another part covering all but a 0.05 mm ring) has no inside to fill, with or without the line-width test (it is not mostly exposed either)" {
        val big = List(20) { i -> if (i < 10) listOf(square(0.0, 0.0, 30.0, 30.0)) else listOf(square(1.05, 1.05, 28.95, 28.95)) }
        val bigInner = List(20) { i -> if (i < 10) listOf(square(1.0, 1.0, 29.0, 29.0)) else listOf(square(2.05, 2.05, 27.95, 27.95)) }
        val over = List(20) { i -> if (i >= 10) listOf(square(1.05, 1.05, 28.95, 28.95)) else emptyList() }
        val sc = cover(listOf(slabPart, over))
        val r = Regions.classify(big, bigInner, 3, 3, topUnderParts = true, selfCover = sc, topLineUm = 440)
        (mm2(r[9].topSurface) < 10.0) shouldBe true                                         // the hairline only
        (mm2(Regions.classify(big, bigInner, 3, 3, topUnderParts = true, selfCover = sc)[9].topSurface) < 10.0) shouldBe true
    }

    "a floor with another part standing on all but a 1 mm rim (exposed 108 mm2 vs 676 mm2 covered) is not mostly exposed: it stays" {
        val core = List(20) { i -> if (i >= 10) listOf(square(2.0, 2.0, 28.0, 28.0)) else emptyList() }
        val outs = List(20) { i -> if (i < 10) listOf(square(0.0, 0.0, 30.0, 30.0)) else listOf(square(2.0, 2.0, 28.0, 28.0)) }
        val ins = List(20) { i -> if (i < 10) listOf(square(1.0, 1.0, 29.0, 29.0)) else listOf(square(3.0, 3.0, 27.0, 27.0)) }
        val r = Regions.classify(outs, ins, 3, 3, topUnderParts = true, selfCover = cover(listOf(slabPart, core)), topLineUm = 440)
        mm2(r[9].topSurface) shouldBe (108.0 plusOrMinus 1e-6)                             // the rim only: 28 x 28 - 26 x 26
    }

    "control: on, but the post is the slab's own part rising further: nothing else stands there, the hole stays" {
        val one = List(20) { i -> outlines[i] }
        mm2(Regions.classify(outlines, inners, 3, 3, topUnderParts = true, selfCover = cover(listOf(one)))[9].topSurface) shouldBe (748.0 plusOrMinus 1e-6)
        mm2(Regions.classify(outlines, inners, 3, 3, topUnderParts = true, selfCover = null)[9].topSurface) shouldBe (748.0 plusOrMinus 1e-6)
    }
})
