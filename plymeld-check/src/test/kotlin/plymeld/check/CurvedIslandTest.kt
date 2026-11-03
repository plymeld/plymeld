package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.IncrementalSlicer
import plymeld.core.decide.CurvedIsland
import plymeld.core.decide.CurvedRegions
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.TechniquePlan
import plymeld.mesh.TriangleSoup
import plymeld.program.Extrude

/**
 * Curved islands, stage 2: a pin, a 30 x 30 x 2 mm plate with a
 * column of radius 5 mm on it up to 12 mm, topped by a rounded cap 3 mm tall (its rim steeper than the nozzle's 45 deg). Its
 * island (the shifted survey, a 45 deg / 4 mm nozzle) is printed in its own layers: laid back through its warp, some lines
 * are curved, none lies above the cap, the island's top layer lies on the cap within one of its layers, and no flat layer
 * prints inside the island's area above its start. Control: the same pin with no island has no curved line.
 */
class CurvedIslandTest : StringSpec({
    val capH = 3.0; val a = 5.0; val rr = (a * a + capH * capH) / (2 * capH)        // the cap's sphere: radius 5.67
    fun capTop(x: Double, y: Double): Double { val d2 = (x - 15) * (x - 15) + (y - 15) * (y - 15); return if (d2 >= a * a) Double.NaN else 12.0 + Math.sqrt(rr * rr - d2) - (rr - capH) }

    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): FloatArray {
        val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return FloatArray(f.size * 3) { k -> v[f[k / 3]][k % 3].toFloat() }
    }
    /** The column and its cap: rings of 64 segments, the side from 2 to 12 mm, the cap in 24 rings to its top. */
    fun pin(): FloatArray {
        val n = 64; val out = ArrayList<Float>()
        fun tri(p: DoubleArray, q: DoubleArray, r: DoubleArray) { for (v in listOf(p, q, r)) for (c in v) out += c.toFloat() }
        val rings = ArrayList<Pair<Double, Double>>()                 // (radius, z) from the bottom up
        rings += a to 2.0; rings += a to 12.0
        for (k in 1..24) { val rad = a * (1 - k / 24.0); rings += rad to (12.0 + Math.sqrt(rr * rr - rad * rad) - (rr - capH)) }
        fun pt(rad: Double, z: Double, i: Int): DoubleArray { val t = 2 * Math.PI * i / n; return doubleArrayOf(15 + rad * Math.cos(t), 15 + rad * Math.sin(t), z) }
        for (s in 0 until rings.size - 1) for (i in 0 until n) {
            val (r0, z0) = rings[s]; val (r1, z1) = rings[s + 1]
            val a0 = pt(r0, z0, i); val b0 = pt(r0, z0, i + 1); val a1 = pt(r1, z1, i); val b1 = pt(r1, z1, i + 1)
            tri(a0, b0, b1); if (r1 > 1e-9) tri(a0, b1, a1)
        }
        val c = doubleArrayOf(15.0, 15.0, 2.0)
        for (i in 0 until n) tri(c, pt(a, 2.0, i + 1), pt(a, 2.0, i))  // the column's bottom, facing down
        return out.toFloatArray()
    }
    val parts = listOf(box(0.0, 0.0, 0.0, 30.0, 30.0, 2.0), pin()).map { TriangleSoup(it, it.size / 9) }
    val all = parts.let { ps -> val c = FloatArray(ps.sumOf { it.count } * 9); var at = 0; for (s in ps) { System.arraycopy(s.coords, 0, c, at, s.count * 9); at += s.count * 9 }; TriangleSoup(c, ps.sumOf { it.count }) }
    val p = CurvedRegions.Params(angleDeg = 45.0, nozzleHeightMm = 4.0, layerMm = 0.2, minLayerMm = 0.08)

    /** Every printed point of every planned layer, laid back: (x, y, z) in mm, and whether its path is curved. */
    fun points(slicer: IncrementalSlicer, plan: List<plymeld.core.LayerPlanOut>): List<Pair<DoubleArray, Boolean>> {
        val out = ArrayList<Pair<DoubleArray, Boolean>>()
        val last = doubleArrayOf(Double.NaN, Double.NaN)
        for (l in plan) for (st in slicer.layerSteps(l, 0, last).second) if (st is Extrude) {
            val zs = st.zs
            for (i in 0 until st.xy.size / 2) out += doubleArrayOf(st.xy[2 * i] / 1000.0, st.xy[2 * i + 1] / 1000.0, (zs?.get(i) ?: st.zUm) / 1000.0) to (zs != null)
        }
        return out
    }

    "the pin's cap printed in its own curved layers: on the cap, never above it, nothing flat inside the island above its start; without the island, no curved line (the control)" {
        val r = CurvedRegions.survey(all, p)
        val found = CurvedRegions.shifted(r, p, soupLayerMm = 0.2, maxLayerMm = 0.3).mapNotNull { it.second as? CurvedRegions.Island }
        println("islands: " + found.joinToString("; "))
        val isl = CurvedIsland.of(r, found.maxByOrNull { it.followedMm2 }!!, p)
        val slicer = IncrementalSlicer(parts, "test", "pin", technique = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS).withIslands(listOf(isl)))
        val planned = slicer.planned(testSetup())
        val pts = points(slicer, planned)
        val curved = pts.filter { it.second }.map { it.first }
        println("points ${pts.size}, curved ${curved.size}, island from %.2f to %.2f mm in %.3f mm layers".format(java.util.Locale.ROOT, isl.startMm, isl.topMm, isl.layerMm))
        (curved.isNotEmpty()) shouldBe true
        // never above the cap where it slopes within the survey's angle (41.0 deg for a 45 deg nozzle, CurvedRegions.INTERP_ERR: r up to
        // rr sin 41 deg = 3.72 mm: the layers follow it); beyond, where it steepens, the layers flatten out and print it as flat layers
        // do: a line at its layer's top up to half a layer above the model's slope there
        val capR = rr * Math.sin(Math.toRadians(p.surveyDeg))
        fun over(q: DoubleArray) = q[2] - capTop(q[0], q[1])
        fun worst(qs: List<DoubleArray>) = qs.maxByOrNull { over(it) }?.let { q ->
            "(%.2f, %.2f) z %.3f, the cap %.3f, r %.2f".format(java.util.Locale.ROOT, q[0], q[1], q[2], capTop(q[0], q[1]), Math.hypot(q[0] - 15, q[1] - 15)) }
        val onCap = curved.filter { q -> Math.hypot(q[0] - 15, q[1] - 15) <= capR && over(q) > 0.01 }
        withClue("${onCap.size} point(s) above the cap within its 45 deg, the worst ${worst(onCap)}") { onCap.size shouldBe 0 }
        val onRim = curved.filter { q -> !capTop(q[0], q[1]).isNaN() && over(q) > isl.layerMm / 2 }
        withClue("${onRim.size} point(s) over half a layer above the cap's steeper rim, the worst ${worst(onRim)}") { onRim.size shouldBe 0 }
        // the island's top layer lies on the cap: its highest points within one of its layers under the cap's top there
        val topZ = curved.maxOf { it[2] }
        val nearTop = curved.filter { it[2] > topZ - 0.05 }
        nearTop.all { q -> capTop(q[0], q[1]).let { it.isNaN() || q[2] >= it - isl.layerMm - 0.01 } } shouldBe true
        // nothing flat inside the island's area above its start (away from its edge: 1 mm inside the column)
        pts.none { (q, c) -> !c && q[2] > isl.startMm + 0.3 && Math.hypot(q[0] - 15, q[1] - 15) < a - 1.0 } shouldBe true
        // the control: the same pin without the island
        val flat = IncrementalSlicer(parts, "test", "pin")
        points(flat, flat.planned(testSetup())).none { it.second } shouldBe true
    }

    "an island is kept from the object's corner: the pin moved on the bed gets its curved layers over its cap where it now stands" {
        val r = CurvedRegions.survey(all, p)
        val isl = CurvedIsland.of(r, CurvedRegions.shifted(r, p, 0.2, 0.3).mapNotNull { it.second as? CurvedRegions.Island }.maxByOrNull { it.followedMm2 }!!, p)
        val dx = 50.0; val dy = 40.0
        val moved = parts.map { s -> TriangleSoup(FloatArray(s.coords.size) { i -> s.coords[i] + when (i % 3) { 0 -> dx.toFloat(); 1 -> dy.toFloat(); else -> 0f } }, s.count) }
        val slicer = IncrementalSlicer(moved, "test", "pin", technique = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS).withIslands(listOf(isl)))
        val curved = points(slicer, slicer.planned(testSetup())).filter { it.second }.map { it.first }
        println("moved by ($dx, $dy): ${curved.size} curved points")
        (curved.isNotEmpty()) shouldBe true
        // all on the column where it now stands, never above its cap
        curved.all { q -> Math.hypot(q[0] - 15 - dx, q[1] - 15 - dy) < a + 0.5 } shouldBe true
        val capR = rr * Math.sin(Math.toRadians(p.surveyDeg))
        curved.none { q -> Math.hypot(q[0] - 15 - dx, q[1] - 15 - dy) <= capR && q[2] > capTop(q[0] - dx, q[1] - dy) + 0.01 } shouldBe true
    }
})
