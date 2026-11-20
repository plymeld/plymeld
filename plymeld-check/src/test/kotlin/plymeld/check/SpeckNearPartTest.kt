package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.MultiFilament
import plymeld.core.ToolPath
import plymeld.model.SliceSettings

/**
 * One part's thin edge right in front of another part: a base whose front face passes a triangle's tip 0.1 mm in front of
 * it, the base between them narrower than any line is laid in (plan.layer.walls.feature.min), widening either side of the
 * tip. On an object of two filaments a dot of one filament there would be stopped and restarted on the visible face every
 * layer (stacking into a column of blobs on that face); and a line of the base laid through the gap at the narrowest line
 * (0.3 mm in a gap 0.1 mm wide) reaches 0.13 mm into the triangle's place. Read here on the base's lines: no dot at the tip
 * (an outer-wall speck, a line kept only for walling the outline, shorter than its own width), and no bead of the base
 * reaching into the triangle's place further than the narrowest line stands out of the narrowest place a line is laid in
 * (half of the narrowest line less that place, a side) and the slicing tolerance.
 * Controls, each a line the readings must catch: a line of the base laid through the gap at the narrowest line reaches
 * too far into the triangle; a dot at the tip is counted. The base's line stops either side of the gap, where the base is
 * narrower than plan.layer.walls.feature.min, alone and beside the triangle alike, its pieces about 0.6 mm long and 0.3 to
 * 0.43 mm wide, lines rather than dots, so no dot forms at the tip either way.
 */
class SpeckNearPartTest : StringSpec({
    // the base 40 x 10 mm, its front face at y 0; the triangle's tip 0.1 mm behind the face, its base 5 mm back and 10 mm
    // wide (the catalog's speck beside a part, as outlines)
    val base = plymeld.check.catalog.Models.speckBase
    val tri = plymeld.check.catalog.Models.speckTriangle
    val triHole = plymeld.check.catalog.Models.speckTriangleHole
    val n = 12
    val tops = LongArray(n) { 200L * (it + 1) }
    val s = SliceSettings.of(testSetup())

    /** The dots at the tip: outer-wall specks shorter than their own width (what makes the blobs). */
    fun specksNearTip(layers: List<List<ToolPath>>): Int = (2 until n).sumOf { i ->
        layers[i].count { p -> p.feature == Feature.OUTER_WALL && p.speck && plymeld.core.Planner.polylineLength(p.xy) < p.widthUm &&
            (0 until p.xy.size / 2).all { k -> p.xy[2 * k] in 18_000..22_000 && p.xy[2 * k + 1] < 1_500 } }
    }
    /** Where the bead reaching furthest into the triangle lies, from the last [intoTriangle]. */
    var at = ""
    /**
     * How far (µm) a bead of [paths] reaches into the triangle near its tip (x 18 to 22 mm, y under 1.5 mm): half its width
     * less its center's distance outside it (more when inside). (The tip's neighborhood only: at the triangle's base
     * corners the base's outer loop cuts across the corner, a matter of its own.)
     */
    fun intoTriangle(paths: List<ToolPath>): Double {
        val ex = doubleArrayOf(15_000.0, 20_000.0, 25_000.0); val ey = doubleArrayOf(5_000.0, 100.0, 5_000.0)
        fun dist(px: Double, py: Double): Double = (0 until 3).minOf { e ->
            val ax = ex[e]; val ay = ey[e]; val bx = ex[(e + 1) % 3]; val by = ey[(e + 1) % 3]
            val dx = bx - ax; val dy = by - ay; val t = (((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
            Math.hypot(px - ax - t * dx, py - ay - t * dy) }
        fun inside(px: Double, py: Double) = plymeld.geom.Polygons.contains(tri.let { if (it.isCcw()) it else it.reversed() }, Math.round(px), Math.round(py))
        var worst = Double.NEGATIVE_INFINITY
        for (p in paths) {
            val m = p.xy.size / 2
            for (k in 0 until m) {
                // the move from vertex k to k + 1, sampled every 10 um
                val k2 = if (k + 1 < m) k + 1 else k
                val ax = p.xy[2 * k].toDouble(); val ay = p.xy[2 * k + 1].toDouble(); val bx = p.xy[2 * k2].toDouble(); val by = p.xy[2 * k2 + 1].toDouble()
                val steps = maxOf(1, (Math.hypot(bx - ax, by - ay) / 10).toInt())
                for (j in 0..steps) {
                    val px = ax + (bx - ax) * j / steps; val py = ay + (by - ay) * j / steps
                    if (px !in 18_000.0..22_000.0 || py > 1_500.0) continue           // the tip's neighborhood only
                    val half = (p.widths?.get(k) ?: p.widthUm) / 2.0
                    val d = dist(px, py)
                    val v = if (inside(px, py)) half + d else half - d
                    if (v > worst) { worst = v; at = "${p.feature} at ${Math.round(px)},${Math.round(py)} width ${(half * 2).toLong()}" }
                }
            }
        }
        return worst
    }
    // the most a bead of the base may reach into the triangle: the narrowest line laid in the narrowest place a line is laid in stands
    // out of it by half their difference a side; and the slicing tolerance
    val allowed = (s.minBeadUm - s.minFeatureUm) / 2.0 + s.resolutionUm

    "one part's edge right in front of another part's tip: no speck, and no bead in the other part's place; controls: a line through the gap, a speck, are caught" {
        val two = MultiFilament.plan(s, listOf(List(n) { listOf(base, triHole) }, List(n) { listOf(tri) }), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val baseLines = two.filaments.single { it.spool == "spool 1" }.layers.map { it.paths }
        val specks = specksNearTip(baseLines)
        var where = ""
        val into = (2 until n).maxOf { i -> intoTriangle(baseLines[i].filter { p -> p.feature == Feature.OUTER_WALL || p.feature == Feature.INNER_WALL }).also { v -> if (where.isEmpty() || v > 0) where = "layer $i: $at" } }
        println("the base's specks at the triangle's tip: $specks; the base's beads reach ${Math.round(into)} um into the triangle's place (allowed ${Math.round(allowed)}; $where)")
        specks shouldBe 0
        (into <= allowed) shouldBe true
        // control: a line of the base through the gap at the narrowest line
        val through = ToolPath(Feature.OUTER_WALL, longArrayOf(19_500, 82, 20_500, 82), false, s.minBeadUm, 200)
        val intoThrough = intoTriangle(listOf(through))
        println("a line through the gap reaches ${Math.round(intoThrough)} um into the triangle's place")
        (intoThrough > allowed) shouldBe true
        // control: a dot at the tip is counted
        val speck = ToolPath(Feature.OUTER_WALL, longArrayOf(19_900, 150, 20_100, 150), false, s.minBeadUm, 200, speck = true)
        specksNearTip(baseLines.mapIndexed { i, l -> if (i == 5) l + speck else l }) shouldBe 1
    }
})
