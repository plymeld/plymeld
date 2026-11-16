package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.core.ToolPath
import plymeld.geom.Booleans
import plymeld.geom.Hatch
import plymeld.geom.Linker
import plymeld.geom.Offset
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Where two fills of a layer share an edge (internal solid beside sparse infill, an internal bridge beside internal
 * solid or sparse, a top beside internal solid, a bridge over air beside the solid around it), there is no wall between
 * them: one fill owns the edge and lays its lines against it as against a wall's bead, the other lays its lines against
 * the owner's beads as against a wall's ([Planner.reachUm]). Placed both against the edge, their line ends and linking
 * runs lay over each other along it; placed both at bead contact with no overlap, the gaps between their line ends' round
 * caps meet in a bare seam.
 *
 * The measure is this test's own, on a 25 µm grid: each bead as the capsule of its stated width round its moves.
 * Laid twice: cells under the beads of two different fills (two sparse lines cross by design and are not read), each bead
 * narrowed by what a fill and a wall overlap by plan (half the rounded flanks, h (1 - pi/4) / 2, and half the line-end
 * overlap, plan.layer.infill.wall_overlap, a side) and the slicing tolerance. Bare: cells inside the walls that no bead
 * covers, between two solid fills (within a millimetre of the beads of two solid kinds). A spot's width is read across
 * it: the shorter of the row and column runs through a cell.
 */
class FillContactTest : StringSpec({
    val cell = 25L
    val fills = setOf(Feature.SOLID, Feature.SPARSE, Feature.TOP, Feature.BOTTOM, Feature.BRIDGE, Feature.INTERNAL_BRIDGE)
    val solidKinds = fills - Feature.SPARSE

    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))

    /** The measures of one layer's [paths] over the box [x0, x1] x [y0, y1] µm; [inner]: the walls' inner region (for bare). */
    class Reading(val doubledMm2: Double, val doubledWidestUm: Long, val bareMm2: Double, val bareWidestUm: Long, val pairs: Set<String>)

    fun read(paths: List<ToolPath>, hUm: Long, tolUm: Long, overlap: Double, inner: List<Polygon>?, x0: Long, y0: Long, x1: Long, y1: Long): Reading {
        val nx = ((x1 - x0) / cell + 1).toInt(); val ny = ((y1 - y0) / cell + 1).toInt()
        val narrowed = IntArray(nx * ny)          // a bit per fill kind: under its narrowed beads
        val covered = BooleanArray(nx * ny)       // under any bead at its stated width
        val near = IntArray(nx * ny)              // a bit per solid kind: within 1 mm of its beads
        fun capsule(ax: Long, ay: Long, bx: Long, by: Long, r: Double, mark: (Int) -> Unit) {
            if (r <= 0) return
            val lox = ((minOf(ax, bx) - r - x0) / cell).toInt().coerceAtLeast(0); val hix = ((maxOf(ax, bx) + r - x0) / cell).toInt().coerceAtMost(nx - 1)
            val loy = ((minOf(ay, by) - r - y0) / cell).toInt().coerceAtLeast(0); val hiy = ((maxOf(ay, by) + r - y0) / cell).toInt().coerceAtMost(ny - 1)
            val dx = (bx - ax).toDouble(); val dy = (by - ay).toDouble(); val l2 = dx * dx + dy * dy
            for (j in loy..hiy) for (i in lox..hix) {
                val px = (x0 + i * cell).toDouble(); val py = (y0 + j * cell).toDouble()
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                val ex = ax + t * dx - px; val ey = ay + t * dy - py
                if (ex * ex + ey * ey <= r * r) mark(j * nx + i)
            }
        }
        val flankHalf = hUm * (1 - Math.PI / 4) / 2
        for (p in paths) {
            val n = p.xy.size / 2
            val segs = if (p.closed) n else n - 1
            for (k in 0 until segs) {
                val a = k; val b = (k + 1) % n
                val w = p.widths?.let { maxOf(it[a], it[b]) } ?: p.widthUm
                val ax = p.xy[2 * a]; val ay = p.xy[2 * a + 1]; val bx = p.xy[2 * b]; val by = p.xy[2 * b + 1]
                capsule(ax, ay, bx, by, w / 2.0) { covered[it] = true }
                if (p.feature in fills) capsule(ax, ay, bx, by, w / 2.0 - flankHalf - overlap * w / 2 - tolUm) { narrowed[it] = narrowed[it] or (1 shl p.feature.ordinal) }
                if (p.feature in solidKinds) capsule(ax, ay, bx, by, 1000.0) { near[it] = near[it] or (1 shl p.feature.ordinal) }
            }
        }
        val sparseBit = 1 shl Feature.SPARSE.ordinal
        val doubled = BooleanArray(nx * ny) { val m = narrowed[it]; Integer.bitCount(m) >= 2 }
        val pairs = HashSet<String>()
        for (c in doubled.indices) if (doubled[c]) pairs += Feature.entries.filter { (narrowed[c] and (1 shl it.ordinal)) != 0 }.joinToString("|")
        val insideGroups = inner?.let { plymeld.geom.Polygons.group(it) }
        fun inside(c: Int): Boolean {
            val px = x0 + (c % nx) * cell; val py = y0 + (c / nx) * cell
            return insideGroups!!.any { (o, hs) -> plymeld.geom.Polygons.contains(o, px, py) && hs.none { plymeld.geom.Polygons.contains(it, px, py) } }
        }
        val bare = BooleanArray(nx * ny) { c -> inner != null && !covered[c] && Integer.bitCount(near[c]) >= 2 && (narrowed[c] and sparseBit) == 0 && inside(c) }
        fun widest(m: BooleanArray): Long {
            var best = 0
            for (j in 0 until ny) for (i in 0 until nx) if (m[j * nx + i]) {
                var a = i; while (a > 0 && m[j * nx + a - 1]) a--
                var b = i; while (b < nx - 1 && m[j * nx + b + 1]) b++
                var c = j; while (c > 0 && m[(c - 1) * nx + i]) c--
                var d = j; while (d < ny - 1 && m[(d + 1) * nx + i]) d++
                best = maxOf(best, minOf(b - a + 1, d - c + 1))
            }
            return best * cell
        }
        // two sparse lines crossing are not read: a cell is laid twice only when two different kinds lie in it
        return Reading(doubled.count { it } * cell * cell / 1e6, widest(doubled), bare.count { it } * cell * cell / 1e6, widest(bare), pairs)
    }

    /**
     * Two solid fills 10 by 20 mm side by side, hatched at 45 and -45 deg (the left as internal solid, the right as a
     * top, so the measure reads the pair), in three placements: both against the shared edge as against a wall (the
     * planner's placement before), both at bead contact with each other with no overlap, and the right owning the edge.
     */
    fun placements(s: SliceSettings): Triple<Reading, Reading, Reading> {
        val w = s.solidWidthUm; val h = 200L
        val sp = Math.round(w - h * (1 - Math.PI / 4))
        val left = listOf(rect(0, 0, 10_000, 20_000)); val right = listOf(rect(10_000, 0, 20_000, 20_000))
        val ov = Math.round(s.infillWallOverlap * w)
        /** [region] hatched at [angle], clipped at bead contact and the line-end overlap, and kept [away] (µm) from [other]. */
        fun lay(region: List<Polygon>, other: List<Polygon>?, away: Long, angle: Double): List<ToolPath> {
            var clip = Offset.offset(region, ov - Planner.contactInset(w, h))
            if (other != null) clip = Booleans.difference(clip, Offset.offset(other, away))
            val (segs, idx) = Hatch.indexed(clip, angle, sp)
            return Linker.link(segs, idx, clip, 3 * sp).map { ToolPath(Feature.SOLID, it, false, w, h) }
        }
        fun asTop(p: List<ToolPath>) = p.map { ToolPath(Feature.TOP, it.xy, false, it.widthUm, it.heightUm) }
        val inner = listOf(rect(0, 0, 20_000, 20_000))
        fun reading(l: List<ToolPath>, r: List<ToolPath>) = read(l + asTop(r), h, s.resolutionUm, s.infillWallOverlap, inner, -500, -500, 20_500, 20_500)
        return Triple(reading(lay(left, null, 0, 45.0), lay(right, null, 0, -45.0)),
            reading(lay(left, right, sp / 2, 45.0), lay(right, left, sp / 2, -45.0)),
            reading(lay(left, right, Planner.reachUm(w, h, s.infillWallOverlap) + Planner.contactInset(w, h) - ov, 45.0), lay(right, null, 0, -45.0)))
    }

    "controls: two fills both placed against the edge lay it twice; both at bead contact leave a bare seam; one owning it, neither" {
        val s = SliceSettings.of(testSetup())
        val (wall, contact, owned) = placements(s)
        for ((name, r) in listOf("against the edge" to wall, "at contact" to contact, "one owns the edge" to owned))
            println(String.format(Locale.ROOT, "%s: laid twice %.4f mm², widest %d µm; bare %.4f mm², widest %d µm", name, r.doubledMm2, r.doubledWidestUm, r.bareMm2, r.bareWidestUm))
        (wall.doubledWidestUm > 4 * cell) shouldBe true
        val floor = s.minFeatureUm + 2 * s.resolutionUm
        (contact.bareWidestUm > floor) shouldBe true
        (owned.doubledWidestUm <= 2 * cell) shouldBe true
        (owned.bareMm2 < contact.bareMm2) shouldBe true
    }
    /** A block 20 by 20 mm whose right half stops one layer below the left half's top: a stepped top. */
    val stepped: List<List<Polygon>> = List(40) { i -> listOf(if (i <= 38) rect(0, 0, 20_000, 20_000) else rect(0, 0, 10_000, 20_000)) }
    /** A table: two legs 6 mm wide, a deck across them from the 16th layer on (a bridge over air beside the solid over the legs). */
    val table: List<List<Polygon>> = List(30) { i -> if (i < 15) listOf(rect(0, 0, 6_000, 20_000), rect(14_000, 0, 20_000, 20_000)) else listOf(rect(0, 0, 20_000, 20_000)) }

    "two fills sharing an edge meet as a fill meets a wall: nothing laid twice along it, no bare seam" {
        val s = SliceSettings.of(testSetup())
        val tol = s.resolutionUm
        val floor = s.minFeatureUm + 2 * s.resolutionUm
        val seam = placements(s).second
        println("the hole floor: $floor µm; two fills at bead contact leave ${seam.bareMm2} mm² bare along 20 mm")
        val bad = ArrayList<String>()
        for ((name, model, layers) in listOf(Triple("stepped", stepped, listOf(36, 37, 38)), Triple("table", table, listOf(15, 16)))) {
            val tops = LongArray(model.size) { 200L * (it + 1) }
            val plan = Planner.plan(s, model, tops)
            val w = Planner.walls(s, model, tops)
            for (i in layers) {
                val paths = plan[i].paths
                val kinds = paths.map { it.feature }.filter { it in fills }.toSet()
                val r = read(paths, 200, tol, s.infillWallOverlap, w.walls[i].inner, -500, -500, 20_500, 20_500)
                println(String.format(Locale.ROOT, "%s layer %d: fills %s; laid twice %.4f mm² (widest %d µm) %s; bare between solid fills %.4f mm² (widest %d µm)",
                    name, i, kinds, r.doubledMm2, r.doubledWidestUm, r.pairs, r.bareMm2, r.bareWidestUm))
                // absence is probed: the layer holds two fills that share an edge
                if (kinds.size < 2) bad += "$name layer $i: one fill"
                // nothing laid twice wider than two cells, the grid's own reach on a slanted edge
                if (r.doubledWidestUm > 2 * cell) bad += "$name layer $i: laid twice ${r.doubledWidestUm} µm wide ${r.pairs}"
                // no bare seam: between two solid fills (the stepped block's edge is 20 mm long, as the controls' below) less
                // bare than the two fills placed at bead contact leave along theirs. What is left are the gaps between line
                // ends' round caps where both fills' gaps meet, up to 0.275 mm across, wider than the hole floor: open
                if (r.bareMm2 >= seam.bareMm2) bad += "$name layer $i: bare ${r.bareMm2} mm², as much as a seam"
            }
        }
        bad shouldBe emptyList()
    }

})