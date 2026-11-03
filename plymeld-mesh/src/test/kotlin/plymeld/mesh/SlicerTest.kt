package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The slicer on a unit cube worked by hand (loop count, area, perimeter, the on-plane convention), and a control: a
 * mesh with one triangle taken out leaves an open chain.
 */
class SlicerTest : StringSpec({
    val models = TestModels.dir()

    "unit cube: one CCW loop of area 1 at mid-height, nothing above it" {
        val layers = Slicer.slice(cube(), doubleArrayOf(0.5, 1.5))
        layers[0].loops.size shouldBe 1
        layers[0].openChains.size shouldBe 0
        layers[0].loops[0].isCcw() shouldBe true
        layers[0].netAreaMm2() shouldBe (1.0 plusOrMinus 1e-9)
        layers[0].perimeterMm() shouldBe (4.0 plusOrMinus 1e-9)
        layers[1].loops.size shouldBe 0
    }

    "planes exactly through cube vertices: the bottom face gives nothing, the top face gives the loop" {
        // On-plane counts as ABOVE. At z=0 every vertex is >= z, so nothing crosses. At z=1 the
        // top vertices are above and the bottom ones below, so the side faces cross: the solid
        // occupies (0, 1] in this convention, and a plane through the top still sees it.
        val layers = Slicer.slice(cube(), doubleArrayOf(0.0, 1.0))
        layers[0].loops.size shouldBe 0
        layers[1].loops.size shouldBe 1
        layers[1].netAreaMm2() shouldBe (1.0 plusOrMinus 1e-9)
    }

    "control: removing one triangle from a mesh produces an open chain at a plane it spanned" {
        val soup = StlReader.read(models.resolve("holeblock.stl"))
        // find a triangle crossing z=2 and drop it
        var drop = -1
        for (t in 0 until soup.count) {
            val zs = listOf(soup.z(t, 0), soup.z(t, 1), soup.z(t, 2))
            if (zs.min() < 2f && zs.max() > 2f) { drop = t; break }
        }
        (drop >= 0) shouldBe true
        val kept = FloatArray((soup.count - 1) * 9)
        var w = 0
        for (t in 0 until soup.count) if (t != drop) { System.arraycopy(soup.coords, t * 9, kept, w, 9); w += 9 }
        val holed = TriangleSoup(kept, soup.count - 1)
        val layer = Slicer.slice(holed, doubleArrayOf(2.0))[0]
        (layer.openChains.size > 0) shouldBe true
    }
})

/** Axis-aligned unit cube [0,1]³ as 12 outward-facing triangles. */
private fun cube(): TriangleSoup {
    val p = arrayOf(
        floatArrayOf(0f, 0f, 0f), floatArrayOf(1f, 0f, 0f), floatArrayOf(1f, 1f, 0f), floatArrayOf(0f, 1f, 0f),
        floatArrayOf(0f, 0f, 1f), floatArrayOf(1f, 0f, 1f), floatArrayOf(1f, 1f, 1f), floatArrayOf(0f, 1f, 1f),
    )
    // faces as CCW-from-outside triangles
    val f = arrayOf(
        intArrayOf(0, 2, 1), intArrayOf(0, 3, 2),   // bottom (-z)
        intArrayOf(4, 5, 6), intArrayOf(4, 6, 7),   // top (+z)
        intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),   // -y
        intArrayOf(1, 2, 6), intArrayOf(1, 6, 5),   // +x
        intArrayOf(2, 3, 7), intArrayOf(2, 7, 6),   // +y
        intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),   // -x
    )
    val coords = FloatArray(12 * 9)
    var w = 0
    for (tri in f) for (v in tri) for (k in 0 until 3) coords[w++] = p[v][k]
    return TriangleSoup(coords, 12)
}
