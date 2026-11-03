package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.WaveFronts
import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon
import java.util.Locale

/**
 * EXPLORATION of wave overhangs' geometry: fronts grown from an
 * anchor across an overhang, each one bead spacing out from the last, on three shapes: a straight ledge, an L-shaped
 * ledge (fronts round the corner) and a ledge with a hole (fronts round the hole and meet behind it), plus a neck too
 * narrow to span. The numbers printed are what the technique's design starts from: fronts per mm, coverage, where
 * growth stops. The assertions pin the geometry's invariants: every front within the overhang and within one spacing
 * of the last, the ledge filled in depth / spacing fronts, a cut neck not crossed.
 */
class WaveOverhangExplorationTest : StringSpec({
    val spacing = 350L; val bead = 440L
    val p = WaveFronts.Params(spacingUm = spacing, overlapUm = 100, minNeckUm = 700)
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    fun area(ps: List<Polygon>) = ps.sumOf { it.signedAreaMm2() }
    fun report(name: String, overhang: List<Polygon>, fronts: List<WaveFronts.Front>) {
        val depthMm = fronts.size * spacing / 1000.0
        println("%s: overhang %.1f mm2, %d fronts (%.2f mm of growth), coverage %.0f %%, last front added %.3f mm2".format(Locale.ROOT,
            name, area(overhang), fronts.size, depthMm, 100 * WaveFronts.coverage(fronts, overhang), fronts.lastOrNull()?.addedMm2 ?: 0.0))
    }
    fun invariants(overhang: List<Polygon>, fronts: List<WaveFronts.Front>) {
        for ((k, f) in fronts.withIndex()) {
            // inside the overhang
            val outside = area(Booleans.difference(f.loops, overhang))
            if (outside >= 1e-6) println("front $k lies outside the overhang by %.6f mm2 (front area %.3f)".format(Locale.ROOT, outside, area(f.loops)))
            (outside < 1e-6) shouldBe true
            // within one spacing (and the bead) of the front before: laterally supported
            if (k > 0) (area(Booleans.difference(f.loops, Offset.offset(fronts[k - 1].loops, spacing + 1, Offset.Join.ROUND))) < 1e-6) shouldBe true
            // growing: each front holds the one before
            if (k > 0) (area(Booleans.difference(fronts[k - 1].loops, f.loops)) < 1e-6) shouldBe true
        }
    }

    "a straight ledge 8 mm deep: filled in about depth / spacing fronts, every front inside and laterally supported" {
        val anchor = listOf(rect(0, 0, 20_000, 10_000))
        val overhang = listOf(rect(0, 10_000, 20_000, 18_000))
        val fronts = WaveFronts.grow(overhang, anchor, p)
        report("ledge", overhang, fronts)
        invariants(overhang, fronts)
        val expected = Math.ceil((8_000 - p.overlapUm).toDouble() / spacing).toInt()
        (fronts.size in expected - 1..expected + 1) shouldBe true
        (WaveFronts.coverage(fronts, overhang) > 0.999) shouldBe true
        // the beads: one loop per front, each inside the overhang
        val paths = WaveFronts.paths(fronts, overhang, bead)
        paths.all { ps -> ps.isNotEmpty() && area(Booleans.difference(ps, overhang)) < 1e-6 } shouldBe true
    }
    "an L-shaped ledge: the fronts round the corner (the overhang beside the anchor's end is reached), no arc dimple needed" {
        val anchor = listOf(rect(0, 0, 10_000, 10_000))
        // the overhang wraps the anchor's top and right side: an L 6 mm wide
        val overhang = Booleans.union(listOf(rect(0, 10_000, 16_000, 16_000)), listOf(rect(10_000, 0, 16_000, 10_000)))
        val fronts = WaveFronts.grow(overhang, anchor, p)
        report("L", overhang, fronts)
        invariants(overhang, fronts)
        (WaveFronts.coverage(fronts, overhang) > 0.99) shouldBe true
        // the outer corner of the L (far from the anchor's corner along the diagonal) is reached last: more fronts than the width alone
        (fronts.size * spacing > 6_000) shouldBe true
    }
    "a ledge with a hole: the fronts pass round the hole and meet behind it; the hole stays open" {
        val anchor = listOf(rect(0, 0, 20_000, 10_000))
        val hole = rect(8_000, 12_000, 12_000, 15_000)
        val overhang = Booleans.difference(listOf(rect(0, 10_000, 20_000, 20_000)), listOf(hole))
        val fronts = WaveFronts.grow(overhang, anchor, p)
        report("hole", overhang, fronts)
        invariants(overhang, fronts)
        (WaveFronts.coverage(fronts, overhang) > 0.99) shouldBe true
        // nothing laid in the hole
        fronts.all { f -> area(Booleans.intersection(f.loops, listOf(hole))) < 1e-6 } shouldBe true
    }
    "a neck narrower than the limit is not crossed: the region beyond it stays empty; control: a wide neck is crossed" {
        fun shape(neckUm: Long): List<Polygon> {
            val y = 10_000L
            return Booleans.union(Booleans.union(listOf(rect(0, y, 20_000, y + 4_000)), listOf(rect(10_000 - neckUm / 2, y + 4_000, 10_000 + neckUm / 2, y + 6_000))),
                listOf(rect(0, y + 6_000, 20_000, y + 10_000)))
        }
        val anchor = listOf(rect(0, 0, 20_000, 10_000))
        val narrow = shape(500); val wide = shape(3_000)
        val beyond = listOf(rect(0, 16_000, 20_000, 20_000))
        val fn = WaveFronts.grow(narrow, anchor, p); val fw = WaveFronts.grow(wide, anchor, p)
        report("narrow neck", narrow, fn); report("wide neck", wide, fw)
        invariants(narrow, fn); invariants(wide, fw)
        (area(Booleans.intersection(fn.last().loops, beyond)) < 1e-6) shouldBe true
        (area(Booleans.intersection(fw.last().loops, beyond)) > 70.0) shouldBe true
    }
    // a fill the anchor surrounds (a waved bridge's gap between two edge strips and two feet): the fronts close in,
    // their advancing edge the hole in the covered region; given the overhang, its beads are those rings, all inside it.
    // Control: without the overhang every hole is taken for one in the part, and no bead is laid (#4 of plate 8 came out empty)
    "a fill surrounded by its anchor: its fronts close in as rings, laid when the overhang is given; control: none without it" {
        val hole = listOf(rect(5_000, 5_000, 12_000, 25_000))
        val anchor = Booleans.difference(listOf(rect(0, 0, 17_000, 30_000)), hole)
        val fronts = WaveFronts.grow(hole, anchor, WaveFronts.Params(spacingUm = spacing, overlapUm = 100, minNeckUm = 700, beadUm = bead))
        (fronts.size > 5) shouldBe true
        val rings = WaveFronts.beads(fronts, anchor, bead, overhang = hole).flatten()
        (rings.size >= fronts.size - 1) shouldBe true
        for (b in rings) for (k in 0 until b.xy.size / 2) {
            val x = b.xy[2 * k]; val y = b.xy[2 * k + 1]
            (x in 5_000..12_000 && y in 5_000..25_000) shouldBe true
        }
        WaveFronts.beads(fronts, anchor, bead).flatten().size shouldBe 0
    }
    // the first regions of a waved bridge's span (plan.technique.bridge.wave.start): a 40 x 12 mm gap between supports at x 0
    // and x 60, the gap from x 10 to 50, y 0 to 12: edge strips 2.5 mm wide along both long edges (2 x 40 x 2.5 = 200 mm2);
    // triangles from each foot to the middle (x 30) of the y 0 edge, each shape reaching 0.5 mm past the gap (worked by hand:
    // the slant from (9.5, 12.5) to (30, -0.5) leaves 12 x 0.288 + 1/2 x 12 x 18.92 = 117.0 mm2 of the gap a side, 233.9 both);
    // opposite triangles the same area, the second's point at the y 12 edge
    "the first regions: edge strips 2 x 2.5 mm, triangles meeting at one edge's middle, opposite triangles at both" {
        val gap = listOf(rect(10_000, 0, 50_000, 12_000))
        val a = listOf(rect(0, 0, 10_000, 12_000)); val b = listOf(rect(50_000, 0, 60_000, 12_000))
        area(plymeld.core.WaveStart.region(gap, a, b, "edge_strips", 2_500)) shouldBe (200.0 plusOrMinus 0.5)
        fun covers(ps: List<Polygon>, x: Long, y: Long) = area(Booleans.intersection(ps, listOf(rect(x - 200, y - 200, x + 200, y + 200)))) > 0.01
        val tri = plymeld.core.WaveStart.region(gap, a, b, "triangles", null)
        area(tri) shouldBe (233.9 plusOrMinus 0.5)
        // both narrow towards the y 0 edge: near the middle covered low, open high
        covers(tri, 25_000, 300) shouldBe true; covers(tri, 35_000, 300) shouldBe true
        covers(tri, 25_000, 11_700) shouldBe false; covers(tri, 35_000, 11_700) shouldBe false
        val opp = plymeld.core.WaveStart.region(gap, a, b, "opposite_triangles", null)
        area(opp) shouldBe (233.9 plusOrMinus 0.5)
        // the second narrows towards the y 12 edge instead
        covers(opp, 25_000, 300) shouldBe true; covers(opp, 35_000, 11_700) shouldBe true; covers(opp, 35_000, 300) shouldBe false
        // control: the whole span is not a shape here
        io.kotest.assertions.throwables.shouldThrow<IllegalStateException> { plymeld.core.WaveStart.region(gap, a, b, "whole", null) }
        // a speck of air beyond the second support's far end (under a strip's end, at the notches) leaves the shapes as
        // they are: the span runs between the supports' inner edges. Control: the speck lies past the second support's inner edge (x 50)
        val speck = gap + listOf(rect(59_000, 5_000, 59_100, 5_010))
        area(plymeld.core.WaveStart.region(speck, a, b, "triangles", null)) shouldBe (233.9 plusOrMinus 0.5)
        covers(plymeld.core.WaveStart.region(speck, a, b, "triangles", null), 35_000, 300) shouldBe true
        (speck.flatMap { p -> (0 until p.xy.size / 2).map { p.xy[2 * it] } }.max() > 55_000) shouldBe true
    }
    // chevron fronts (plan.technique.bridge.wave.front = "chevron"), lead 12 mm on the same 40 x 12 mm
    // gap: the first fronts are triangles in the four corners (four loops, none reaching the center line y 6); then each side's
    // front is a V, its tip on the center line 12 mm nearer its support than its edges; the sides close at the edges first,
    // leaving a diamond in the middle (a hole), laid last as rings; every bead on the gap or the supports. Control: grown fronts
    // (Huygens) from the same supports are straight across, their first front one piece per support, the whole width
    "chevron fronts, lead 12 mm: corner triangles first, then Vs 12 mm deep, a diamond in the middle last; control: grown straight" {
        val gap = listOf(rect(10_000, 0, 50_000, 12_000))
        val a = listOf(rect(0, 0, 10_000, 12_000)); val b = listOf(rect(50_000, 0, 60_000, 12_000))
        val wp = WaveFronts.Params(spacingUm = spacing, overlapUm = 100, minNeckUm = 700, beadUm = bead)
        val fronts = plymeld.core.WaveStart.chevronFronts(gap, a, b, 12_000, wp)
        fun ys(p: Polygon) = (0 until p.xy.size / 2).map { p.xy[2 * it + 1] }
        fun xs(p: Polygon) = (0 until p.xy.size / 2).map { p.xy[2 * it] }
        // the first front: four corner pieces, none crossing the center line
        fronts.first().loops.size shouldBe 4
        fronts.first().loops.all { p -> ys(p).all { it <= 6_000 } || ys(p).all { it >= 6_000 } } shouldBe true
        // a later front from support a: its outline's tip on the center line 12 mm short of where it meets the edges
        val v = fronts.first { f -> f.loops.size == 2 }.loops.first { p -> xs(p).min() == 10_000L }
        val tip = (0 until v.xy.size / 2).filter { v.xy[2 * it + 1] == 6_000L }.minOf { v.xy[2 * it] }
        val edge = (0 until v.xy.size / 2).filter { v.xy[2 * it + 1] == 0L }.maxOf { v.xy[2 * it] }
        (edge - tip).toDouble() shouldBe (12_000.0 plusOrMinus 2.0)
        // the end: a hole (the diamond) inside the covered area, then the gap full
        fronts.any { f -> f.loops.any { it.signedAreaMm2() < 0 } } shouldBe true
        WaveFronts.coverage(fronts, gap) shouldBe (1.0 plusOrMinus 0.01)
        val beads = WaveFronts.beads(fronts, a + b, bead, overhang = gap).flatten()
        beads.any { it.closed } shouldBe true
        for (bd in beads) for (k in 0 until bd.xy.size / 2) withClue("bead point (${bd.xy[2 * k]}, ${bd.xy[2 * k + 1]})") { (bd.xy[2 * k] in 9_600..50_400 && bd.xy[2 * k + 1] in -100..12_100) shouldBe true }   // within a bead of the gap: an early V's tip lies on its support
        // the lead unset is half the span: the
        // same fronts as 20 mm on this 40 mm gap; control: not the 12 mm ones
        fun same(x: List<WaveFronts.Front>, y: List<WaveFronts.Front>) = x.size == y.size && x.indices.all { x[it].loops.toString() == y[it].loops.toString() }
        same(plymeld.core.WaveStart.chevronFronts(gap, a, b, null, wp), plymeld.core.WaveStart.chevronFronts(gap, a, b, 20_000, wp)) shouldBe true
        same(plymeld.core.WaveStart.chevronFronts(gap, a, b, null, wp), fronts) shouldBe false
        // control: grown from the supports, the first front is one straight piece at each support, across the whole width
        val grown = WaveFronts.grow(gap, a + b, wp)
        grown.first().loops.size shouldBe 2
        grown.first().loops.all { p -> ys(p).min() == 0L && ys(p).max() == 12_000L } shouldBe true
    }
    // the middle leading (plan.technique.bridge.wave.chevron.leading = "middle", middle-out), lead
    // unset (half the span) on the same 40 x 12 mm gap: the first front is a small triangle out of each support's middle,
    // touching neither long edge; the center point (30, 6) is reached before the middle of an edge (30, 0.3). Control: the
    // edges leading reach the middle of the edge first and the center point last
    "middle-out chevron fronts: a triangle out of each support's middle first, the center before the edges' middles; control: edges leading" {
        val gap = listOf(rect(10_000, 0, 50_000, 12_000))
        val a = listOf(rect(0, 0, 10_000, 12_000)); val b = listOf(rect(50_000, 0, 60_000, 12_000))
        val wp = WaveFronts.Params(spacingUm = spacing, overlapUm = 100, minNeckUm = 700, beadUm = bead)
        fun ys(p: Polygon) = (0 until p.xy.size / 2).map { p.xy[2 * it + 1] }
        fun covers(ps: List<Polygon>, x: Long, y: Long) = area(Booleans.intersection(ps, listOf(rect(x - 150, y - 150, x + 150, y + 150)))) > 0.08
        fun reached(fs: List<WaveFronts.Front>, x: Long, y: Long) = fs.indexOfFirst { covers(it.loops, x, y) }
        val mid = plymeld.core.WaveStart.chevronFronts(gap, a, b, null, wp, middle = true)
        mid.first().loops.size shouldBe 2
        mid.first().loops.all { p -> ys(p).min() > 300 && ys(p).max() < 11_700 } shouldBe true
        val center = reached(mid, 30_000, 6_000); val edgeMid = reached(mid, 30_000, 300)
        withClue("center reached at front $center, the edge's middle at $edgeMid") { (center in 0 until edgeMid) shouldBe true }
        WaveFronts.coverage(mid, gap) shouldBe (1.0 plusOrMinus 0.01)
        // control: the edges leading
        val edges = plymeld.core.WaveStart.chevronFronts(gap, a, b, null, wp)
        val c2 = reached(edges, 30_000, 6_000); val e2 = reached(edges, 30_000, 300)
        withClue("edges leading: center at front $c2, the edge's middle at $e2") { (e2 in 0 until c2) shouldBe true }
    }
    // a chevron's lines across (plan.technique.bridge.wave.chevron.lines = "across": lines parallel to the
    // foot's edge, not rows laid piece by piece), middle leading, on the same gap: every line runs across at one x (parallel
    // to the supports' edges, x 10 and 50), rows 0.35 mm apart; first the middle pieces (each centered on y 6), then the edge
    // pieces (each from the triangle out to an edge, ending half a bead inside it), none of them a middle piece after the
    // first edge piece; the rows at the center two edge pieces meeting at y 6. Control: the edges leading lay edge pieces first
    "middle-out chevron across: rows parallel to the supports, middle pieces first, then edge pieces out to the edges; control: edges leading" {
        val gap = listOf(rect(10_000, 0, 50_000, 12_000))
        val a = listOf(rect(0, 0, 10_000, 12_000)); val b = listOf(rect(50_000, 0, 60_000, 12_000))
        val wp = WaveFronts.Params(spacingUm = spacing, overlapUm = 100, minNeckUm = 700, beadUm = bead)
        val rows = plymeld.core.WaveStart.chevronRows(gap, a, b, null, wp, middle = true)
        val all = rows.flatten()
        all.all { r -> r.xy.size == 4 && Math.abs(r.xy[0] - r.xy[2]) <= 1 } shouldBe true
        // the rows from support a: 0.35 mm apart, the first half a bead less the overlap into the gap
        val xa = all.map { it.xy[0] }.filter { it < 30_000 }.distinct().sorted()
        (xa.first() in (10_000 + bead / 2 - 100 - 2)..(10_000 + bead / 2 - 100 + 2)) shouldBe true
        xa.zipWithNext { p, q -> q - p }.all { it in (spacing - 2)..(spacing + 2) } shouldBe true
        fun centered(r: plymeld.core.WaveStart.Row) = Math.abs((r.xy[1] + r.xy[3]) / 2 - 6_000) <= 2
        val firstEdge = rows.indexOfFirst { f -> f.any { !centered(it) } }
        (firstEdge > 0) shouldBe true
        rows.take(firstEdge).flatten().all(::centered) shouldBe true
        rows.drop(firstEdge).flatten().none { centered(it) && it.xy[1] != 6_000L && it.xy[3] != 6_000L } shouldBe true
        // each edge piece from the middle out: it ends at an edge, half a bead inside it, and keeps that direction
        for (r in rows.drop(firstEdge).flatten()) withClue("edge piece ${r.xy.toList()}") {
            (r.xy[3] == bead / 2 || r.xy[3] == 12_000 - bead / 2) shouldBe true
            r.ownDirection shouldBe true
        }
        // the center rows: two edge pieces meeting at y 6
        rows.flatten().any { r -> r.xy[0] in 29_000..30_000 && r.xy[1] == 6_000L } shouldBe true
        // control: the edges leading lay the two edge pieces first, each from its edge in
        val outIn = plymeld.core.WaveStart.chevronRows(gap, a, b, null, wp, middle = false)
        outIn.first().none(::centered) shouldBe true
        outIn.first().all { r -> r.xy[1] == bead / 2 || r.xy[1] == 12_000 - bead / 2 } shouldBe true
    }
})
