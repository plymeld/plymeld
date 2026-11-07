package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * Support as a volume technique: tree support under an overhang region too steep to hold itself up, over a volume
 * OUTSIDE the model (the air between the overhang and what is below it). It never competes for the model's plastic: the
 * overhang's entry need ("something under me") is met by support or by whatever lays the overhang over air (today flat
 * layers' bridging, or a locked lifted bridge / layers following the underside), and the weights choose. Its outcomes
 * are its costs only (its plastic, its time); the benefit shows once, in the supported region's outcome (scars as
 * support.interface instead of droop as unsupported_area).
 *
 * - [Overhangs.survey] finds the overhang regions of a posed model under one plan's layers: the faces needing support,
 *   clustered by shared vertices, each cluster an XY box within a height range ([Coordination.Volume.Region]).
 * - [LockedSpot] is a spot the user marks (`plan.support.locked.<key>`, in the model's own coordinates): a requirement,
 *   never weighed away; every downward face inside it is supported, whether or not it needs support.
 * - [order] is the print order of a support column beside the model's layers (searched, not assumed): finished first
 *   where the head clears it, or interleaved layer by layer.
 */
object Support {
    /**
     * Support's controls, each the setting it is locked at (its id, named as the config key) with its unit; the
     * values are read from the settings, never from here. Support's "density, gap, interface, angle" are these: the
     * tips' spacing (how dense the trees stand), the line width, the interface layers, the branches' widening angle.
     */
    val CONTROL_KEYS = listOf("plan.support.tree.tip_spacing" to "mm", "plan.support.line.width" to "mm",
        "plan.support.interface.top_layers" to "layers", "plan.support.tree.widening" to "deg",
        "plan.support.tree.tip_diameter" to "mm", "plan.support.gap.side" to "mm")

    /**
     * The narrowest overhang region a tree can hold up (mm): a tip's diameter with the XY gap on both sides, from
     * [controls] (the settings' values); 0 when either is not set. A region narrower than this is left out of the survey
     * (TreeSupports removes the gap around the object from every branch, so nothing of a tip survives in a sliver), and
     * its faces droop: found on the two-color Benchy, whose plan promised support under two slivers under a
     * millimetre wide that the slice could not build (SupportCheck refused it). Measured on the region's box; the faces'
     * own footprint is held to [columnWidthMm].
     */
    fun minRegionWidthMm(controls: List<Control>): Double {
        fun v(id: String) = controls.firstOrNull { it.id == id }?.let { (it.range as? Control.Range.Steps)?.values?.singleOrNull() }
        // the generator's own fallbacks (Planner): a tip twice the support line wide, the gap one line (bundled: 0.8 + 2 x 0.4)
        val line = v("plan.support.line.width") ?: return 0.0
        val tip = v("plan.support.tree.tip_diameter") ?: 2 * line
        val gap = v("plan.support.gap.side") ?: line
        return tip + 2 * gap
    }

    /**
     * The narrowest column of support the generator lays (mm): [columnWidthSetting] (plan.support.column.width)
     * when set, else its rule for tree support, a tip's diameter ([controls]: tip_diameter, else twice the support line,
     * as Planner falls back); 0 when neither is known. The generator drops every overhang whose downward faces are not this
     * wide anywhere (Supports.dropSpecks), so the survey leaves out a region whose faces, seen from below, are not either
     * ([Overhangs.holdsWidth]): found on the two-color Benchy (one filament on both parts), where a cluster of
     * 0.0 mm² of faces lying diagonally across a 1.67 x 1.62 mm box passed the box's test, was planned as a support spot,
     * and the slice printed no support in it (SupportCheck refused the file).
     */
    fun columnWidthMm(controls: List<Control>, columnWidthSetting: Double?): Double {
        if (columnWidthSetting != null) return columnWidthSetting
        fun v(id: String) = controls.firstOrNull { it.id == id }?.let { (it.range as? Control.Range.Steps)?.values?.singleOrNull() }
        return v("plan.support.tree.tip_diameter") ?: v("plan.support.line.width")?.let { 2 * it } ?: 0.0
    }

    /**
     * Whether a support column may be finished before the model's layers beside it ([Coordination.Order.FIRST]): the head
     * prints the model's lower layers beside the finished column, so the column must not rise above the nozzle tip's
     * protrusion ([nozzleHeightMm], hardware.tool.<key>.head.nozzle.height: how far the tip sits below the rest of the head).
     * Null = allowed; else why not. Unknown nozzle height: not allowed (the safe order), said.
     */
    fun firstBlocked(columnMm: Double, nozzleHeightMm: Double?, nozzleHeightSource: String): String? = when {
        nozzleHeightMm == null -> "the head's clearance is not known ($nozzleHeightSource): interleaved, the safe order"
        columnMm > nozzleHeightMm + 1e-9 -> "the column (%.2f mm) rises above the nozzle tip's %.2f mm protrusion (%s): the head would meet it".format(Locale.ROOT, columnMm, nozzleHeightMm, nozzleHeightSource)
        else -> null
    }

    /**
     * What finishing a column first saves (s): the travel between the model and the column every layer of the column's
     * height, there and back ([layers] layers, [distanceMm] apart, at [travelSpeedMmS]). Null = not known (no travel speed:
     * the order is then not weighed, and said). The quick time estimate leaves travel out, so this is the only place the
     * order's time shows; the finalists' full prediction measures the real g-code.
     */
    fun firstSavingS(layers: Int, distanceMm: Double, travelSpeedMmS: Double?): Double? =
        travelSpeedMmS?.takeIf { it > 0 }?.let { layers * 2 * distanceMm / it }

    /** One support spot's words for the decision's log and the explain. */
    fun words(s: SupportPlan.Spot): String = (if (s.locked) "locked spot '${s.id}'" else s.id) + " " + s.region.words +
        ", standing on ${s.standsOn}, ${s.order.words}"
}

/**
 * A spot the user locks support on ("ensure we can force support on certain spots; that wouldn't be overridable
 * by plymeld"): a box in the model's own coordinates, as the file gives them ([minX]..[maxX], [minY]..[maxY], [minZ]..[maxZ],
 * mm), from `plan.support.locked.<key>` (or `plan.object.<key>.support.locked.<key>`). In each pose the box is turned with the
 * model and taken as its bounding box there ([posed]).
 */
class LockedSpot(val key: String, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minZ: Double, val maxZ: Double, val source: String) {
    init {
        require(minX < maxX && minY < maxY && minZ < maxZ) { "locked support spot '$key' ($source): each min must be below its max (x $minX..$maxX, y $minY..$maxY, z $minZ..$maxZ mm)" }
    }

    val words: String get() = "x %s..%s, y %s..%s, z %s..%s mm in the model's own coordinates".format(Locale.ROOT,
        Control.fmt(minX), Control.fmt(maxX), Control.fmt(minY), Control.fmt(maxY), Control.fmt(minZ), Control.fmt(maxZ))

    /**
     * The box in pose [pose] (its rotation; the posed model's bounding box is [bounds], as TriangleSoup.bounds gives it):
     * the bounding box of the turned corners, relative to the posed model's corner and bottom.
     */
    fun posed(pose: Pose, bounds: DoubleArray): Coordination.Volume.Region {
        val r = pose.r
        var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y0 = x0; var y1 = x1; var z0 = x0; var z1 = x1
        for (c in 0 until 8) {
            val x = if (c and 1 == 0) minX else maxX; val y = if (c and 2 == 0) minY else maxY; val z = if (c and 4 == 0) minZ else maxZ
            val px = r[0] * x + r[1] * y + r[2] * z + pose.t[0]; val py = r[3] * x + r[4] * y + r[5] * z + pose.t[1]; val pz = r[6] * x + r[7] * y + r[8] * z + pose.t[2]
            x0 = minOf(x0, px); x1 = maxOf(x1, px); y0 = minOf(y0, py); y1 = maxOf(y1, py); z0 = minOf(z0, pz); z1 = maxOf(z1, pz)
        }
        return Coordination.Volume.Region(z0 - bounds[2], z1 - bounds[2], x0 - bounds[0], x1 - bounds[0], y0 - bounds[1], y1 - bounds[1])
    }
}

/**
 * Support's plan for one object: the spots it supports, each a region of the model's underside
 * with the air under it, locked or chosen, and the print order of its column. Null on a TechniquePlan = the switch
 * decides (plan.support.enabled true or false), as before.
 */
class SupportPlan(val spots: List<Spot>) {
    /**
     * One spot: [region] (the overhang faces, in the posed model); [locked] = the user's requirement (every downward face
     * in it is supported) rather than a chosen overhang region (the faces needing support in it); [order] of its column
     * beside the model's layers; [standsOn] what the column rests on (words); [columnMm] the column's height.
     */
    class Spot(val id: String, val region: Coordination.Volume.Region, val locked: Boolean, val order: Coordination.Order, val standsOn: String,
               val columnMm: Double, val standsOnMm: Double? = null,
               /** Why its column has this order (the search's words; not part of its identity). */
               val why: String = "") {
        override fun equals(other: Any?) = other is Spot && region == other.region && locked == other.locked && order == other.order
        override fun hashCode() = region.hashCode() * 31 + order.hashCode()
    }

    val any: Boolean get() = spots.isNotEmpty()
    val locked: List<Spot> get() = spots.filter { it.locked }
    val chosen: List<Spot> get() = spots.filter { !it.locked }
    /** For a plan's short form: `+support(n)`, with `!` for each locked spot. */
    val short: String get() = "+support(" + spots.joinToString("") { if (it.locked) "!" else "" } + spots.size + ")"
    val words: String get() = if (spots.isEmpty()) "no support" else "support under " + spots.joinToString("; ") { Support.words(it) }

    override fun equals(other: Any?) = other is SupportPlan && spots == other.spots
    override fun hashCode() = spots.hashCode()
}

/**
 * An overhang region of a posed model under one plan's layers ([Overhangs.survey]): a cluster of the faces needing
 * support, its [region] (box), its downward area [areaMm2] (over air), the column of air under it down to the bed
 * [columnMm3] (the quick estimate's stand-in, as support.volume), and what its column stands on: the model's surface
 * [standsOnMm] above the bottom, or null, the bed.
 */
class OverhangRegion(val index: Int, val region: Coordination.Volume.Region, val areaMm2: Double, val columnMm3: Double, val standsOnMm: Double?,
                     /** The model surface its column stands on (mm², the support interface's bottom; 0 on the bed): [Overhangs.baseArea]. */
                     val baseMm2: Double = 0.0,
                     /** Its droop-equivalent area: [areaMm2] weighted by severity (PoseDecision.severity); what support under it prevents. */
                     val severityMm2: Double = areaMm2) {
    val id: String get() = "overhang #${index + 1}"
    val standsOn: String get() = standsOnMm?.let { "the model's surface %.2f mm up".format(Locale.ROOT, it) } ?: "the bed"
    /** The column's height: from what it stands on to the lowest point of the overhang. */
    val columnMm: Double get() = region.fromMm - (standsOnMm ?: 0.0)
    val words: String get() = "%s: %.1f mm² of downward faces too steep to hold themselves up, %s, standing on %s%s, a column %.2f mm tall".format(Locale.ROOT, id, areaMm2, region.words, standsOn,
        if (baseMm2 > 0) " (%.1f mm² of it under the column)".format(Locale.ROOT, baseMm2) else "", columnMm)
}

/** Support's survey of a posed model: where its overhangs are. */
object Overhangs {
    /**
     * The overhang regions of the posed model [p] (its bounds [b]): the triangles [needs] marks (facing down, over air,
     * needing support under the plan's layers), clustered where they share a vertex (positions within a micron), each
     * cluster's box, area ([area]: per triangle, over air), and column; what each stands on by a ray down from the box's
     * center. [vertexIds] is [vertexIds] of [p], shared across plans. Clusters in order of their lowest point, then x, y.
     */
    fun survey(p: TriangleSoup, b: DoubleArray, vertexIds: IntArray, needs: BooleanArray, area: DoubleArray, zc: DoubleArray, poseLabel: String = "",
               /** Regions narrower than this (mm, the box's narrow side) are left out: too narrow for a tree tip ([minRegionWidthMm]). */
               minWidthMm: Double = 0.0,
               /** Each face's droop severity (PoseDecision.severity), for the regions' droop-equivalent area; null = 1 throughout. */
               sev: DoubleArray? = null,
               /** Regions whose faces, seen from below, are not this wide anywhere (mm, [holdsWidth]) are left out: the generator lays no column narrower ([Support.columnWidthMm]); 0 = not asked. */
               columnWidthMm: Double = 0.0): List<OverhangRegion> {
        val n = p.count
        val parent = IntArray(n) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }; return x }
        val firstAt = HashMap<Int, Int>()
        for (i in 0 until n) {
            if (!needs[i]) continue
            for (k in 0 until 3) {
                val v = vertexIds[3 * i + k]
                val o = firstAt.put(v, i)
                if (o != null) { val a = find(o); val c = find(i); if (a != c) parent[c] = a }
            }
        }
        class Acc { var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y0 = x0; var y1 = x1; var z0 = x0; var z1 = x1; var a = 0.0; var vol = 0.0; var sevA = 0.0 }
        val acc = HashMap<Int, Acc>()
        val c = p.coords
        for (i in 0 until n) {
            if (!needs[i]) continue
            val g = acc.getOrPut(find(i)) { Acc() }
            val o = 9 * i
            for (k in 0 until 3) {
                val x = c[o + 3 * k].toDouble(); val y = c[o + 3 * k + 1].toDouble(); val z = c[o + 3 * k + 2].toDouble()
                g.x0 = minOf(g.x0, x); g.x1 = maxOf(g.x1, x); g.y0 = minOf(g.y0, y); g.y1 = maxOf(g.y1, y); g.z0 = minOf(g.z0, z); g.z1 = maxOf(g.z1, z)
            }
            g.a += area[i]; g.vol += area[i] * (zc[i] - b[2]); g.sevA += area[i] * (sev?.get(i) ?: 1.0)
        }
        val wideBox = acc.filter { (_, g) -> g.a > 0 && minOf(g.x1 - g.x0, g.y1 - g.y0) >= minWidthMm - 1e-9 }
        // the box's narrow side is not the region's width: a sliver lying diagonally across a box wide enough is narrower
        // than any column the generator lays, and gets none (Support.columnWidthMm). So the faces' own footprint, seen
        // from below, must hold a disc of the column's width: shrunk by half of it, something must be left.
        val tris = if (columnWidthMm <= 0 || wideBox.isEmpty()) null else HashMap<Int, ArrayList<Int>>().also { m ->
            for (i in 0 until n) if (needs[i]) find(i).let { r -> if (r in wideBox) m.getOrPut(r) { ArrayList() }.add(i) }
        }
        val sorted = wideBox.filter { (r, _) -> tris == null || holdsWidthCached(p, poseLabel, tris.getValue(r), columnWidthMm) }.values
            .sortedWith(compareBy({ Math.round(it.z0 * 1e4) }, { Math.round(it.x0 * 1e4) }, { Math.round(it.y0 * 1e4) }))
        val regions = sorted.map { g ->
            val region = Coordination.Volume.Region(g.z0 - b[2], g.z1 - b[2], g.x0 - b[0], g.x1 - b[0], g.y0 - b[1], g.y1 - b[1])
            Triple(region, g, standsOn(p, (g.x0 + g.x1) / 2, (g.y0 + g.y1) / 2, g.z0)?.let { it - b[2] })
        }
        val bases = baseAreas(p, b, regions.map { it.first to it.third }, poseLabel)
        return regions.mapIndexed { i, (region, g, so) -> OverhangRegion(i, region, g.a, g.vol, so, bases[i], g.sevA) }
    }

    /** One region's faces for [holdsWidthCached]: the pose, the width, and the faces themselves (compared whole). */
    private class WidthKey(val pose: String, val widthMm: Double, val tris: IntArray) {
        private val hash = pose.hashCode() * 31 + tris.contentHashCode()
        override fun hashCode() = hash
        override fun equals(other: Any?) = other is WidthKey && other.pose == pose && other.widthMm == widthMm && other.tris.contentEquals(tris)
    }
    /** Per posed model (by identity, held weakly: a model no longer used drops its entries), the regions' answers. */
    private val widthCache = java.util.WeakHashMap<TriangleSoup, HashMap<WidthKey, Boolean>>()

    /** [holdsWidth], remembered by model, pose, and faces: the survey runs per candidate plan, and plans with the same layers there find the same regions. */
    fun holdsWidthCached(p: TriangleSoup, poseLabel: String, tris: List<Int>, widthMm: Double): Boolean {
        val key = WidthKey(poseLabel, widthMm, tris.toIntArray())
        synchronized(widthCache) { widthCache[p]?.get(key) }?.let { return it }
        return holdsWidth(p, tris, widthMm).also { synchronized(widthCache) { widthCache.getOrPut(p) { HashMap() }[key] = it } }
    }

    /**
     * Whether the faces [tris] of [p], seen from below (their footprint on the bed's plane, the union of the triangles
     * projected down), hold a disc [widthMm] across: the footprint shrunk by half the width leaves something. A sliver
     * (narrower than the width everywhere, whatever its box) does not.
     */
    fun holdsWidth(p: TriangleSoup, tris: List<Int>, widthMm: Double): Boolean {
        val c = p.coords
        // one grid unit less than half: a footprint exactly the width across holds the disc (as the box test accepts it),
        // and shrinking it by exactly half would leave a line of no area
        val half = plymeld.geom.Units.mm(widthMm / 2) - 1
        // the triangles' directed edges, each triangle wound counter-clockwise: an edge and its reverse (the edge two
        // neighbors share) cancel, so what is left winds around every point as often as the triangles cover it; its
        // rings under the positive rule are the triangles' union, from the outline's edges alone (a union of every
        // triangle made the search five times slower on a 260 000-triangle model)
        val edges = HashMap<Long, HashMap<Long, Int>>()   // from -> (to -> count)
        fun key(x: Long, y: Long) = (x shl 32) or (y and 0xFFFFFFFFL)
        fun add(a: Long, b: Long) {
            val back = edges[b]?.get(a)
            if (back != null) { if (back == 1) edges.getValue(b).remove(a) else edges.getValue(b)[a] = back - 1; return }
            edges.getOrPut(a) { HashMap(4) }.merge(b, 1, Int::plus)
        }
        var sumUm2 = 0.0
        val centroids = ArrayList<DoubleArray>(tris.size)   // x, y (µm), area
        for (i in tris) {
            val o = 9 * i
            val xy = LongArray(6) { k -> plymeld.geom.Units.mm(c[o + 3 * (k / 2) + (k % 2)].toDouble()) }
            val t = plymeld.geom.Polygon(xy)
            val a2 = t.signedArea2()
            if (a2 == 0L) continue
            val a = Math.abs(a2) / 2.0
            // one triangle holding the disc is enough: its inscribed circle's radius is its area over half its perimeter
            if (2 * a / t.perimeter() >= half) return true
            sumUm2 += a
            centroids += doubleArrayOf((xy[0] + xy[2] + xy[4]) / 3.0, (xy[1] + xy[3] + xy[5]) / 3.0, a)
            val ccw = if (a2 > 0) t else t.reversed()
            val k0 = key(ccw.x(0), ccw.y(0)); val k1 = key(ccw.x(1), ccw.y(1)); val k2 = key(ccw.x(2), ccw.y(2))
            add(k0, k1); add(k1, k2); add(k2, k0)
        }
        // a footprint of less area than the disc cannot hold it (the triangles' sum is at least their union's area)
        if (sumUm2 < Math.PI * half * half) return false
        // every point has as many edges leaving as arriving (each triangle's do, and a canceled pair takes one of each),
        // so the edges left chain into closed rings
        val rings = ArrayList<plymeld.geom.Polygon>()
        for (start in edges.keys.toList()) while (edges.getValue(start).isNotEmpty()) {
            val ring = ArrayList<Long>()
            var at = start
            do {
                ring += at
                val outs = edges.getValue(at)
                val to = outs.keys.first()
                if (outs.getValue(to) == 1) outs.remove(to) else outs[to] = outs.getValue(to) - 1
                at = to
            } while (at != start)
            if (ring.size >= 3) rings += plymeld.geom.Polygon(LongArray(2 * ring.size) { j -> if (j % 2 == 0) ring[j / 2] shr 32 else (ring[j / 2] and 0xFFFFFFFFL).toInt().toLong() })
        }
        val union = plymeld.geom.Booleans.unionPositive(rings)
        if (union.isEmpty()) return false
        // a quick yes before the shrink (the shrink of a long, finely cut outline is the slow part): a triangle's centroid,
        // largest triangles first, inside the union and at least half the width from every edge of it holds the disc. Only the
        // QUICK_CENTROIDS largest: where none holds it, the shrink decides anyway, and trying every centroid against every
        // edge was most of a large model's pose search (~35 % of 593 s, on regions that did not hold the disc)
        val order = centroids.indices.sortedByDescending { centroids[it][2] }.take(QUICK_CENTROIDS)
        for (k in order) {
            val x = centroids[k][0]; val y = centroids[k][1]
            val xi = Math.round(x); val yi = Math.round(y)
            if (union.sumOf { r -> if (plymeld.geom.Polygons.contains(r, xi, yi)) (if (r.isCcw()) 1 else -1) else 0 } <= 0) continue
            if (union.all { r -> clearOf(r, x, y, half.toDouble()) }) return true
        }
        return plymeld.geom.Offset.offset(union, -half).isNotEmpty()
    }

    /** How many of a region's triangles' centroids [holdsWidth]'s quick yes tries, largest first: a search bound, not a print value. */
    const val QUICK_CENTROIDS = 64

    /** Whether every edge of [r] is at least [d] from (x, y) (squared distances: Math.hypot's overflow-safe path was a tenth of a pose search). */
    private fun clearOf(r: plymeld.geom.Polygon, x: Double, y: Double, d: Double): Boolean {
        val n = r.size
        val d2 = d * d
        for (i in 0 until n) {
            val ax = r.x(i).toDouble(); val ay = r.y(i).toDouble()
            val bx = r.x((i + 1) % n).toDouble(); val by = r.y((i + 1) % n).toDouble()
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val u = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            val ex = x - ax - u * dx; val ey = y - ay - u * dy
            if (ex * ex + ey * ey < d2) return false
        }
        return true
    }

    /** The grid the surface under a column is sampled on (mm), at most [MAX_BASE_SAMPLES] a side. plymeld's choice. */
    const val BASE_SAMPLE_MM = 0.5
    const val MAX_BASE_SAMPLES = 40
    private val baseCache = object : LinkedHashMap<String, Double>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Double>?) = size > 20_000
    }

    /**
     * The support interface's bottom: the model's cross-section just under the surface a region's column stands
     * on ([standsOnMm] above the model's bottom; null = the bed, 0), within the region's footprint: what the column lands on
     * and marks. Sampled on [BASE_SAMPLE_MM] (at most [MAX_BASE_SAMPLES] a side); cached by model, pose, and region
     * (the survey runs per candidate plan). [baseAreas] cuts every region of a survey in one sweep.
     */
    fun baseArea(p: TriangleSoup, b: DoubleArray, region: Coordination.Volume.Region, standsOnMm: Double?, poseLabel: String): Double =
        baseAreas(p, b, listOf(region to standsOnMm), poseLabel)[0]

    fun baseAreas(p: TriangleSoup, b: DoubleArray, regions: List<Pair<Coordination.Volume.Region, Double?>>, poseLabel: String): DoubleArray {
        val out = DoubleArray(regions.size)
        val keys = regions.map { (r, so) -> so?.let { "${System.identityHashCode(p)}|$poseLabel|${r.words}|${Math.round(it * 1e4)}" } }
        val todo = ArrayList<Int>()
        synchronized(baseCache) { for (i in regions.indices) { val k = keys[i] ?: continue; val c = baseCache[k]; if (c != null) out[i] = c else todo += i } }
        if (todo.isEmpty()) return out
        val zs = todo.map { b[2] + regions[it].second!! - 0.02 }.distinct().sorted()
        val cut = plymeld.mesh.Slicer.slice(p, zs.toDoubleArray())
        val loopsAt = zs.indices.associate { zs[it] to cut[it].loops }
        val areaAt = HashMap<Double, Cantilever.Area>()
        for (i in todo) {
            val (region, so) = regions[i]
            val a = areaAt.getOrPut(b[2] + so!! - 0.02) { Cantilever.Area(loopsAt.getValue(b[2] + so - 0.02)) }
            val x0 = b[0] + region.x0; val x1 = b[0] + region.x1; val y0 = b[1] + region.y0; val y1 = b[1] + region.y1
            val nx = maxOf(1, minOf(MAX_BASE_SAMPLES, Math.ceil((x1 - x0) / BASE_SAMPLE_MM).toInt())); val ny = maxOf(1, minOf(MAX_BASE_SAMPLES, Math.ceil((y1 - y0) / BASE_SAMPLE_MM).toInt()))
            var hits = 0
            for (ii in 0..nx) for (j in 0..ny) if (a.inside(x0 + (x1 - x0) * ii / nx, y0 + (y1 - y0) * j / ny)) hits++
            out[i] = hits.toDouble() / ((nx + 1) * (ny + 1)) * (x1 - x0) * (y1 - y0)
            synchronized(baseCache) { baseCache[keys[i]!!] = out[i] }
        }
        return out
    }

    /**
     * Where a ray straight down from (x, y) below [zTop] first meets the model's upward-facing surface: that height, or
     * null when it reaches the bed. A branch of tree support leans, so this is the column's base to within its lean.
     */
    fun standsOn(p: TriangleSoup, x: Double, y: Double, zTop: Double): Double? {
        val c = p.coords
        var best: Double? = null
        // only the triangles whose footprint holds (x, y) can be hit: the posed model's grid of them ([Footprints])
        val g = footprints(p)
        for (i in g.at(x, y)) {
            val o = 9 * i
            val ax = c[o].toDouble(); val ay = c[o + 1].toDouble(); val az = c[o + 2].toDouble()
            val bx = c[o + 3].toDouble(); val by = c[o + 4].toDouble(); val bz = c[o + 5].toDouble()
            val cx = c[o + 6].toDouble(); val cy = c[o + 7].toDouble(); val cz = c[o + 8].toDouble()
            if (minOf(az, bz, cz) >= zTop - 1e-9) continue
            val nz = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)   // twice the signed XY area: positive = facing up
            if (nz <= 1e-12) continue
            // barycentric test in XY
            val d1 = (bx - ax) * (y - ay) - (by - ay) * (x - ax)
            val d2 = (cx - bx) * (y - by) - (cy - by) * (x - bx)
            val d3 = (ax - cx) * (y - cy) - (ay - cy) * (x - cx)
            if (d1 < -1e-9 || d2 < -1e-9 || d3 < -1e-9) continue
            val w1 = d2 / nz; val w2 = d3 / nz; val w3 = d1 / nz
            val z = w1 * az + w2 * bz + w3 * cz
            if (z < zTop - 1e-6 && (best == null || z > best)) best = z
        }
        return best
    }

    /**
     * The upward-facing triangles of a posed model by where they lie in XY: a grid over the model's footprint, each cell
     * listing every triangle whose box (grown by [FOOTPRINT_SLACK]) touches it. A ray down from a point can only hit those
     * of its cell, so [standsOn] tries a few triangles, not every one of a model for each region (a survey asks for every
     * region; scanning the whole model each time was a twentieth of a pose search). Built once per posed model and kept
     * while there is room ([SoupCache]).
     */
    private class Footprints(p: TriangleSoup) {
        private val x0: Double; private val y0: Double; private val cell: Double; private val nx: Int; private val ny: Int
        private val start: IntArray; private val tris: IntArray
        /** Its size, for [SoupCache]'s bound. */
        val bytes: Long get() = 4L * (start.size + tris.size)
        init {
            val c = p.coords
            var bx0 = Double.POSITIVE_INFINITY; var by0 = bx0; var bx1 = Double.NEGATIVE_INFINITY; var by1 = bx1
            var up = 0
            for (i in 0 until p.count) {
                val o = 9 * i
                for (k in 0 until 3) { val x = c[o + 3 * k].toDouble(); val y = c[o + 3 * k + 1].toDouble(); bx0 = minOf(bx0, x); bx1 = maxOf(bx1, x); by0 = minOf(by0, y); by1 = maxOf(by1, y) }
                if (facesUp(c, o)) up++
            }
            if (up == 0) { bx0 = 0.0; by0 = 0.0; bx1 = 1.0; by1 = 1.0 }
            x0 = bx0 - FOOTPRINT_SLACK; y0 = by0 - FOOTPRINT_SLACK
            val w = bx1 - bx0 + 2 * FOOTPRINT_SLACK; val h = by1 - by0 + 2 * FOOTPRINT_SLACK
            // about as many cells as upward triangles, square cells, at most FOOTPRINT_CELLS a side
            cell = maxOf(Math.sqrt(w * h / maxOf(1, up)), maxOf(w, h) / FOOTPRINT_CELLS, 1e-6)
            nx = maxOf(1, Math.ceil(w / cell).toInt()); ny = maxOf(1, Math.ceil(h / cell).toInt())
            // each upward triangle's cell range (its box grown by the slack), found once: 4 ints per triangle
            val ups = IntArray(up); val range = IntArray(4 * up)
            var u = 0
            for (t in 0 until p.count) {
                val o = 9 * t
                if (!facesUp(c, o)) continue
                var tx0 = Double.POSITIVE_INFINITY; var ty0 = tx0; var tx1 = Double.NEGATIVE_INFINITY; var ty1 = tx1
                for (k in 0 until 3) { val x = c[o + 3 * k].toDouble(); val y = c[o + 3 * k + 1].toDouble(); tx0 = minOf(tx0, x); tx1 = maxOf(tx1, x); ty0 = minOf(ty0, y); ty1 = maxOf(ty1, y) }
                ups[u] = t
                range[4 * u] = ix(tx0 - FOOTPRINT_SLACK); range[4 * u + 1] = ix(tx1 + FOOTPRINT_SLACK)
                range[4 * u + 2] = iy(ty0 - FOOTPRINT_SLACK); range[4 * u + 3] = iy(ty1 + FOOTPRINT_SLACK)
                u++
            }
            val count = IntArray(nx * ny + 1)
            for (q in 0 until up) for (j in range[4 * q + 2]..range[4 * q + 3]) for (i in range[4 * q]..range[4 * q + 1]) count[j * nx + i + 1]++
            for (k in 1 until count.size) count[k] += count[k - 1]
            start = count.copyOf()
            tris = IntArray(count.last())
            val fill = count.copyOf()
            for (q in 0 until up) for (j in range[4 * q + 2]..range[4 * q + 3]) for (i in range[4 * q]..range[4 * q + 1]) tris[fill[j * nx + i]++] = ups[q]
        }
        private fun ix(x: Double) = ((x - x0) / cell).toInt().coerceIn(0, nx - 1)
        private fun iy(y: Double) = ((y - y0) / cell).toInt().coerceIn(0, ny - 1)
        /** The triangles that can lie under (x, y), in index order; none outside the grid. */
        fun at(x: Double, y: Double): IntArray {
            val fx = (x - x0) / cell; val fy = (y - y0) / cell
            if (fx < 0 || fy < 0 || fx >= nx || fy >= ny) return IntArray(0)
            val k = fy.toInt() * nx + fx.toInt()
            return tris.copyOfRange(start[k], start[k + 1])
        }
    }
    /** Whether the triangle at [o] faces up (twice its signed XY area above standsOn's threshold): only those can be hit from above. */
    private fun facesUp(c: FloatArray, o: Int): Boolean {
        val ax = c[o].toDouble(); val ay = c[o + 1].toDouble()
        val nz = (c[o + 3] - ax) * (c[o + 7] - ay) - (c[o + 4] - ay) * (c[o + 6] - ax)
        return nz > 1e-12
    }
    /** A triangle's footprint grown by this much (mm) in the grid: wider than standsOn's barycentric tolerance (1e-9 over an edge's length) for any edge over a micron long, so the grid never leaves out a triangle the scan would hit. */
    private const val FOOTPRINT_SLACK = 1e-2
    /** The footprint grid's most cells a side: a bound on its size for a model of few large triangles, not a print value. */
    private const val FOOTPRINT_CELLS = 512
    private val footprintsOf = plymeld.mesh.SoupCache<Footprints>(plymeld.mesh.SoupCache.heapShare()) { it.bytes }
    private fun footprints(p: TriangleSoup): Footprints = footprintsOf.get(p) { Footprints(p) }

    /** Each triangle's three vertex ids, positions within a micron sharing an id: how the survey joins faces into regions. */
    fun vertexIds(p: TriangleSoup): IntArray {
        val ids = IntArray(p.count * 3)
        val seen = HashMap<Long, Int>(p.count * 2)
        val c = p.coords
        for (i in 0 until p.count * 3) {
            val x = Math.round(c[3 * i] * 1000.0); val y = Math.round(c[3 * i + 1] * 1000.0); val z = Math.round(c[3 * i + 2] * 1000.0)
            val key = (x and 0x1FFFFF) or ((y and 0x1FFFFF) shl 21) or ((z and 0x1FFFFF) shl 42)
            ids[i] = seen.getOrPut(key) { seen.size }
        }
        return ids
    }
}
