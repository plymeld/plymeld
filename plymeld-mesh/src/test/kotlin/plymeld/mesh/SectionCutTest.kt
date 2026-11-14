package plymeld.mesh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * A mesh cut to a box ([SectionCut]) is a closed mesh of the right volume, and slices as one: a cube (cut faces through
 * its sides), a square tube (a cap with a hole), a ball whose equator's vertices lie on the cutting plane (the plane moved
 * off them), and a block with two posts (two outlines on one cap). Closed is checked edge by edge: every directed edge of
 * the result has its reverse exactly once. References: volumes worked by hand; the ball's half from the mesh's own
 * symmetry. A cut outline that crosses itself by a float's step (two points that close together, from a real model) is
 * still closed, and one crossing itself by half a millimetre is refused. Controls: a box round the whole mesh changes
 * nothing; a cube with a face missing, cut across the opening, is
 * refused.
 */
class SectionCutTest : StringSpec({
    val inf = Double.POSITIVE_INFINITY
    /** A box's 12 triangles, outward; [skipTop] leaves its top face out (an open mesh). */
    fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, inward: Boolean = false, skipTop: Boolean = false, caps: Boolean = true): List<Float> {
        val p = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
            floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
        val quads = ArrayList<IntArray>()
        if (caps) quads += intArrayOf(0, 3, 2, 1)                             // bottom
        if (caps && !skipTop) quads += intArrayOf(4, 5, 6, 7)                 // top
        quads += intArrayOf(0, 1, 5, 4); quads += intArrayOf(1, 2, 6, 5); quads += intArrayOf(2, 3, 7, 6); quads += intArrayOf(3, 0, 4, 7)
        val out = ArrayList<Float>()
        fun tri(a: Int, b: Int, c: Int) { for (i in if (inward) intArrayOf(a, c, b) else intArrayOf(a, b, c)) out += p[i].toList() }
        for (q in quads) { tri(q[0], q[1], q[2]); tri(q[0], q[2], q[3]) }
        return out
    }
    fun soup(f: List<Float>) = TriangleSoup(f.toFloatArray(), f.size / 9)
    /** The directed edges with no reverse (or more than one of either): empty for a closed, consistently wound mesh. Triangles of no area are left out. */
    fun unpaired(s: TriangleSoup): Int {
        val count = HashMap<List<Float>, Int>()
        for (t in 0 until s.count) {
            val v = (0 until 3).map { c -> listOf(s.x(t, c) + 0f, s.y(t, c) + 0f, s.z(t, c) + 0f) }
            for (c in 0 until 3) { val a = v[c]; val b = v[(c + 1) % 3]; if (a != b) count.merge(a + b, 1, Int::plus) }
        }
        return count.entries.count { (e, n) -> n != 1 || count[e.subList(3, 6) + e.subList(0, 3)] != 1 }
    }

    "a cube cut to a box: closed, the box's volume, and it slices as a rectangle" {
        val cut = SectionCut.cut(soup(box(0f, 0f, 0f, 10f, 10f, 10f)), doubleArrayOf(2.0, 7.0, -inf, 4.0, 1.0, inf))
        unpaired(cut) shouldBe 0
        cut.signedVolume() shouldBe (5.0 * 4.0 * 9.0 plusOrMinus 1e-3)
        val layer = Slicer.slice(cut, doubleArrayOf(5.0))[0]
        layer.openChains.size shouldBe 0
        layer.netAreaMm2() shouldBe (20.0 plusOrMinus 1e-3)
    }
    "a square tube cut across: the cap has a hole" {
        // a 10 mm tube 20 long with a 4 mm bore: the outer box's sides and the bore's (inward), with ring-shaped ends left open
        // at z = 0 and 20, then cut to z 5..15, so both ends are caps with a hole
        val tube = box(0f, 0f, 0f, 10f, 10f, 20f, caps = false) + box(3f, 3f, 0f, 7f, 7f, 20f, inward = true, caps = false)
        val cut = SectionCut.cut(soup(tube), doubleArrayOf(-inf, inf, -inf, inf, 5.0, 15.0))
        unpaired(cut) shouldBe 0
        cut.signedVolume() shouldBe ((100.0 - 16.0) * 10.0 plusOrMinus 1e-3)
    }
    "a ball cut at its equator, where its vertices lie: half its volume, closed" {
        val n = 24; val f = ArrayList<Float>()
        // the poles one point each, the equator's z exactly 0 (the cutting plane passes through its vertices)
        fun p(i: Int, j: Int): List<Float> { val th = Math.PI * i / n; val ph = 2 * Math.PI * (j % (2 * n)) / (2 * n)
            if (i == 0) return listOf(0f, 0f, 10f); if (i == n) return listOf(0f, 0f, -10f)
            return listOf((10 * Math.sin(th) * Math.cos(ph)).toFloat(), (10 * Math.sin(th) * Math.sin(ph)).toFloat(), if (2 * i == n) 0f else (10 * Math.cos(th)).toFloat()) }
        for (i in 0 until n) for (j in 0 until 2 * n) {
            if (i > 0) { f += p(i, j); f += p(i + 1, j); f += p(i, j + 1) }
            if (i < n - 1) { f += p(i, j + 1); f += p(i + 1, j); f += p(i + 1, j + 1) }
        }
        val ball = soup(f)
        unpaired(ball) shouldBe 0                                    // the test's own mesh is closed
        val half = SectionCut.cut(ball, doubleArrayOf(-inf, inf, -inf, inf, 0.0, inf))
        unpaired(half) shouldBe 0
        half.signedVolume() shouldBe (ball.signedVolume() / 2 plusOrMinus 0.01)
        // and through all six sides at once
        val piece = SectionCut.cut(ball, doubleArrayOf(-6.0, 7.5, -3.0, 8.0, -5.0, 2.5))
        unpaired(piece) shouldBe 0
        (piece.signedVolume() > 0 && piece.signedVolume() < ball.signedVolume()) shouldBe true
    }
    "two posts on a block, cut through the posts: two outlines on one cap" {
        val m = box(0f, 0f, 0f, 20f, 10f, 2f) + box(2f, 2f, 5f, 6f, 6f, 12f) + box(12f, 2f, 5f, 16f, 6f, 12f)   // three closed boxes, apart
        val cut = SectionCut.cut(soup(m), doubleArrayOf(-inf, inf, -inf, inf, -inf, 8.0))
        unpaired(cut) shouldBe 0
        cut.signedVolume() shouldBe (20.0 * 10 * 2 + 2 * (4.0 * 4 * 3) plusOrMinus 1e-3)
    }
    // what was left of a real model's cut outline (46,728 points) when the triangulation stopped: two needles whose tips are
    // two points a float's step apart, their sides swapped there, so the outline crosses itself by 0.000004 mm
    "an outline crossing itself by a float's step at a needle's tip is still closed; control: one truly crossing itself is refused" {
        val needles = doubleArrayOf(54.641090393066406, -20.027002334594727, 55.16203689575195, -17.6484432220459, 71.58467102050781, -17.943384170532227,
            72.80488586425781, -20.63744354248047, 72.80487823486328, -20.63744354248047, 72.68623352050781, -17.90029525756836,
            54.73697280883789, -17.617311477661133, 54.64109420776367, -20.027002334594727)
        fun area2(l: DoubleArray): Double { var s = 0.0; val k = l.size / 2; for (i in 0 until k) { val j = (i + 1) % k; s += l[2 * i] * l[2 * j + 1] - l[2 * j] * l[2 * i + 1] }; return s }
        var tris = 0; var sum = 0.0
        val edges = HashMap<List<Double>, Int>()
        SectionCut.triangulate(needles, emptyList(), 1.0) { ax, ay, bx, by, cx, cy ->
            tris++; sum += (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            for (e in listOf(listOf(ax, ay, bx, by), listOf(bx, by, cx, cy), listOf(cx, cy, ax, ay))) edges.merge(e, 1, Int::plus)
        }
        tris shouldBe 6                                              // n - 2: every point used, none added
        sum shouldBe (area2(needles) plusOrMinus 1e-6)                // the triangles' areas add up to the outline's
        // every edge inside is shared by two triangles, one each way; the outline's own edges are there once
        val k = needles.size / 2
        val outline = (0 until k).map { i -> val j = (i + 1) % k; listOf(needles[2 * i], needles[2 * i + 1], needles[2 * j], needles[2 * j + 1]) }.toSet()
        edges.all { (e, c) -> c == 1 && (e in outline || edges[listOf(e[2], e[3], e[0], e[1])] == 1) } shouldBe true
        outline.all { it in edges } shouldBe true
        // control: the same needles with their tips' two points half a millimetre apart: the sides cross for real
        val crossed = needles.copyOf().also { it[0] -= 0.5; it[6] += 0.5 }
        shouldThrow<IllegalArgumentException> { SectionCut.triangulate(crossed, emptyList(), 1.0) { _, _, _, _, _, _ -> } }.message!! shouldContain "crosses itself"
    }
    "controls: a box round the whole mesh changes nothing; an open mesh cut across its opening is refused; a box beside the mesh leaves nothing" {
        val cube = soup(box(0f, 0f, 0f, 10f, 10f, 10f))
        val same = SectionCut.cut(cube, doubleArrayOf(-1.0, 11.0, -1.0, 11.0, -1.0, 11.0))
        same.count shouldBe cube.count
        same.coords.toList() shouldBe cube.coords.toList()
        shouldThrow<IllegalArgumentException> { SectionCut.cut(soup(box(0f, 0f, 0f, 10f, 10f, 10f, skipTop = true)), doubleArrayOf(5.0, inf, -inf, inf, -inf, inf)) }.message!! shouldContain "not a closed surface"
        SectionCut.cut(cube, doubleArrayOf(20.0, 30.0, -inf, inf, -inf, inf)).count shouldBe 0
    }
})
