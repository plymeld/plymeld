package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

/**
 * The model by vertical columns (Z anti-aliasing): where the solid is along the line through (x, y), over the union of the
 * parts, against boxes and a wedge worked by hand. A point exactly on an edge two triangles share is counted once (the
 * top's diagonal), a part's face inside another is no top, parts that touch or nearly touch are one run within the join.
 */
class SolidColumnsTest : StringSpec({
    // corners 0..7 (bottom 0-3 counter-clockwise from (x0, y0), top 4-7 above them), faces counter-clockwise from outside
    val faces = arrayOf(
        intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
        intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7),
    )
    /** A box from (x0, y0) to (x1, y1), its bottom at [z0], its top at [zl] along x = x0 rising straight to [zr] along x = x1. */
    fun block(x0: Double, y0: Double, x1: Double, y1: Double, z0: Double, zl: Double, zr: Double = zl): TriangleSoup {
        val p = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, zl), doubleArrayOf(x1, y0, zr), doubleArrayOf(x1, y1, zr), doubleArrayOf(x0, y1, zl))
        val c = FloatArray(12 * 9); var w = 0
        for (f in faces) for (v in f) for (k in 0 until 3) c[w++] = p[v][k].toFloat()
        return TriangleSoup(c, 12)
    }
    val a = block(0.0, 0.0, 10.0, 10.0, 0.0, 5.0)

    "a box: one run from its bottom to its top inside, none outside" {
        SolidColumns(listOf(a), 0.0).runs(3.0, 7.0).toList() shouldBe listOf(0.0, 5.0)
        SolidColumns(listOf(a), 0.0).topAbove(3.0, 7.0, 1.0) shouldBe 5.0
        // absence: above the box, and beside it
        SolidColumns(listOf(a), 0.0).topAbove(3.0, 7.0, 5.5).isNaN() shouldBe true
        SolidColumns(listOf(a), 0.0).runs(10.001, 5.0).size shouldBe 0
    }
    "a point on the edge the top's (and the bottom's) two triangles share is counted once" {
        // (5, 5) lies on the diagonal corner 0 - corner 2 of both: counted twice, the line would enter twice and leave twice
        SolidColumns(listOf(a), 0.0).runs(5.0, 5.0).toList() shouldBe listOf(0.0, 5.0)
        // and on the box's own outline (a vertical side's foot), never half a run
        (SolidColumns(listOf(a), 0.0).runs(10.0, 10.0).size % 2) shouldBe 0
        (SolidColumns(listOf(a), 0.0).runs(0.0, 5.0).size % 2) shouldBe 0
    }
    "two parts overlapping: the union's top, not the face of one inside the other" {
        val b = block(5.0, 0.0, 15.0, 10.0, 3.0, 8.0)
        SolidColumns(listOf(a, b), 0.0).topAbove(7.0, 5.0, 1.0) shouldBe 8.0
        // control: the box alone tops out at its own face
        SolidColumns(listOf(a), 0.0).topAbove(7.0, 5.0, 1.0) shouldBe 5.0
    }
    "parts touching are one run; a gap is joined only within the join distance" {
        SolidColumns(listOf(a, block(0.0, 0.0, 10.0, 10.0, 5.0, 9.0)), 0.0).runs(3.0, 3.0).toList() shouldBe listOf(0.0, 9.0)
        val gap = block(0.0, 0.0, 10.0, 10.0, 5.03, 9.0)
        SolidColumns(listOf(a, gap), 0.05).topAbove(3.0, 3.0, 1.0) shouldBe (9.0 plusOrMinus 1e-6)
        // control: a join under the gap keeps two runs
        SolidColumns(listOf(a, gap), 0.01).topAbove(3.0, 3.0, 1.0) shouldBe 5.0
    }
    "a wedge: its sloped top where it is, by hand" {
        val t = Math.tan(Math.toRadians(15.0))
        val w = block(0.0, 0.0, 20.0, 10.0, 0.0, 1.0, 1.0 + 20 * t)
        val c = SolidColumns(listOf(w), 0.0)
        for (x in listOf(0.5, 3.7, 10.0, 19.2)) c.topAbove(x, 4.3, 0.5) shouldBe ((1.0 + x * t) plusOrMinus 1e-5)
    }
})
