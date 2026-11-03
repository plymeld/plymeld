package plymeld.mesh

/**
 * Curved layers by warping the model: the model's upper surface as a
 * height field, and the warp of Z that makes it flat, so the planar pipeline can slice the warped model
 * and its paths be mapped back (from the bed, or as a top band).
 */

/**
 * The model's top seen from above: at (x, y), the highest point of an upward-facing triangle there, or
 * NaN where no upward-facing triangle covers the point (outside the model's footprint). Exact on the
 * mesh (piecewise linear, no sampling grid): a point on a triangle's edge or corner takes that triangle's
 * height, so a mesh vertex on the top gets its own Z back.
 *
 * Upward-facing: a normal's Z share above [upNz]. The triangles are kept in a bucket grid over the
 * footprint (its cell size only sets the speed of a lookup, never a result).
 */
class TopSurface private constructor(
    private val tris: DoubleArray, private val count: Int,
    private val bx0: Double, private val by0: Double, private val cell: Double, private val nx: Int, private val ny: Int,
    private val buckets: Array<IntArray>,
    /** The underside instead ([underside]): the lowest downward-facing triangle at a point, not the highest upward one. */
    private val lowest: Boolean = false,
) {
    /** The top at (x, y) (the underside's height, for [underside]), or NaN outside the footprint. */
    fun top(x: Double, y: Double): Double {
        val i = ((x - bx0) / cell).toInt(); val j = ((y - by0) / cell).toInt()
        if (i < 0 || j < 0 || i >= nx || j >= ny || x < bx0 || y < by0) return Double.NaN
        var best = Double.NaN
        for (t in buckets[j * nx + i]) {
            val z = zAt(t, x, y)
            if (!z.isNaN() && (best.isNaN() || (if (lowest) z < best else z > best))) best = z
        }
        return best
    }

    /** The slope (degrees from flat) of the top at (x, y), from the highest triangle there; NaN outside. */
    fun slopeDeg(x: Double, y: Double): Double {
        val i = ((x - bx0) / cell).toInt(); val j = ((y - by0) / cell).toInt()
        if (i < 0 || j < 0 || i >= nx || j >= ny) return Double.NaN
        var best = Double.NaN; var slope = Double.NaN
        for (t in buckets[j * nx + i]) {
            val z = zAt(t, x, y)
            if (!z.isNaN() && (best.isNaN() || (if (lowest) z < best else z > best))) { best = z; slope = slopeOf(t) }
        }
        return slope
    }

    /** Z of triangle [t]'s plane at (x, y) when the point lies on the triangle (edges included), else NaN. */
    private fun zAt(t: Int, x: Double, y: Double): Double {
        val o = t * 9
        val x0 = tris[o]; val y0 = tris[o + 1]; val x1 = tris[o + 3]; val y1 = tris[o + 4]; val x2 = tris[o + 6]; val y2 = tris[o + 7]
        val d = (y1 - y2) * (x0 - x2) + (x2 - x1) * (y0 - y2)
        if (d == 0.0) return Double.NaN
        val a = ((y1 - y2) * (x - x2) + (x2 - x1) * (y - y2)) / d
        val b = ((y2 - y0) * (x - x2) + (x0 - x2) * (y - y2)) / d
        val c = 1.0 - a - b
        val eps = EDGE_EPS
        if (a < -eps || b < -eps || c < -eps) return Double.NaN
        return a * tris[o + 2] + b * tris[o + 5] + c * tris[o + 8]
    }

    private fun slopeOf(t: Int): Double {
        val o = t * 9
        val ux = tris[o + 3] - tris[o]; val uy = tris[o + 4] - tris[o + 1]; val uz = tris[o + 5] - tris[o + 2]
        val vx = tris[o + 6] - tris[o]; val vy = tris[o + 7] - tris[o + 1]; val vz = tris[o + 8] - tris[o + 2]
        val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
        val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
        return Math.toDegrees(Math.acos(Math.min(1.0, Math.abs(nz) / len)))
    }

    companion object {
        /** A point this close outside a triangle (in barycentric terms) still counts as on it: a vertex or an edge shared by two. */
        const val EDGE_EPS = 1e-9

        /**
         * The top surface of [soup]: its triangles whose normal points up by more than [upNz] (the Z share
         * of the unit normal; 0 would take vertical walls in, so a caller passes a small positive share).
         */
        fun of(soup: TriangleSoup, upNz: Double): TopSurface = build(soup, upNz, false)

        /**
         * The UNDERSIDE of [soup] seen from below: at (x, y), the lowest of its triangles whose normal points down by more
         * than [downNz]; NaN outside its footprint. Where a part stands on the bed that is the bed; under an arch, the
         * arch's underside (layers that follow the underside).
         */
        fun underside(soup: TriangleSoup, downNz: Double): TopSurface = build(soup, downNz, true)

        private fun build(soup: TriangleSoup, nzShare: Double, lowest: Boolean): TopSurface {
            require(nzShare > 0.0 && nzShare < 1.0) { "the normal's Z share is between 0 and 1: $nzShare" }
            val up = ArrayList<Int>()
            for (t in 0 until soup.count) {
                val ux = (soup.x(t, 1) - soup.x(t, 0)).toDouble(); val uy = (soup.y(t, 1) - soup.y(t, 0)).toDouble(); val uz = (soup.z(t, 1) - soup.z(t, 0)).toDouble()
                val vx = (soup.x(t, 2) - soup.x(t, 0)).toDouble(); val vy = (soup.y(t, 2) - soup.y(t, 0)).toDouble(); val vz = (soup.z(t, 2) - soup.z(t, 0)).toDouble()
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 0 && (if (lowest) -nz / len else nz / len) > nzShare) up.add(t)
            }
            val tris = DoubleArray(up.size * 9)
            for ((k, t) in up.withIndex()) for (c in 0 until 3) {
                tris[k * 9 + c * 3] = soup.x(t, c).toDouble(); tris[k * 9 + c * 3 + 1] = soup.y(t, c).toDouble(); tris[k * 9 + c * 3 + 2] = soup.z(t, c).toDouble()
            }
            val b = if (up.isEmpty()) doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0) else soup.bounds()
            // about a few triangles per bucket: the cell from the footprint and the triangle count (speed only)
            val w = maxOf(b[3] - b[0], 1e-6); val h = maxOf(b[4] - b[1], 1e-6)
            val cell = maxOf(Math.sqrt(w * h / maxOf(1, up.size)) * 2.0, 1e-3)
            val nx = maxOf(1, Math.ceil(w / cell).toInt() + 1); val ny = maxOf(1, Math.ceil(h / cell).toInt() + 1)
            val lists = Array(nx * ny) { ArrayList<Int>() }
            for (k in up.indices) {
                val o = k * 9
                val minX = minOf(tris[o], tris[o + 3], tris[o + 6]); val maxX = maxOf(tris[o], tris[o + 3], tris[o + 6])
                val minY = minOf(tris[o + 1], tris[o + 4], tris[o + 7]); val maxY = maxOf(tris[o + 1], tris[o + 4], tris[o + 7])
                val i0 = ((minX - b[0]) / cell).toInt().coerceIn(0, nx - 1); val i1 = ((maxX - b[0]) / cell).toInt().coerceIn(0, nx - 1)
                val j0 = ((minY - b[1]) / cell).toInt().coerceIn(0, ny - 1); val j1 = ((maxY - b[1]) / cell).toInt().coerceIn(0, ny - 1)
                for (j in j0..j1) for (i in i0..i1) lists[j * nx + i].add(k)
            }
            return TopSurface(tris, up.size, b[0], b[1], cell, nx, ny, Array(nx * ny) { lists[it].toIntArray() }, lowest)
        }
    }
}

/**
 * A map of Z per column that makes a curved band's layers flat: the planar pipeline slices
 * the [warpSoup]ped model, and its paths come back through [unwarp]. Below [zStart], and outside the curve's footprint,
 * nothing moves.
 */
abstract class ZWarp {
    abstract val zStart: Double
    /** Real Z to warped Z. */
    abstract fun warp(x: Double, y: Double, z: Double): Double
    /** Warped Z back to real Z (the inverse of [warp]). */
    abstract fun unwarp(x: Double, y: Double, zw: Double): Double
    /** A warped layer's real thickness per unit of warped thickness at (x, y). */
    abstract fun thicknessFactor(x: Double, y: Double): Double
    /**
     * The finest feature of the warp's surfaces across XY (mm): a line laid along them is read against them every quarter of it
     * at least, so no bend between two samples goes unseen (at half of it, a diagonal line crossing the cells' edges more
     * often than that strayed 0.0146 mm, over the check's 0.0145). Infinite: none finer than the slicer's own sampling.
     */
    open val featureMm: Double get() = Double.POSITIVE_INFINITY

    /**
     * [soup] with every vertex warped (the planar pipeline slices it). Exact for a triangle whose corners
     * share one column top (the top's own triangles, vertical walls); a large triangle crossing the band at a
     * slant is bent only at its corners, which a caller handles by subdividing first.
     */
    fun warpSoup(soup: TriangleSoup): TriangleSoup {
        val src = if (featureMm.isFinite()) subdivided(soup) else soup
        val out = FloatArray(src.count * 9)
        for (t in 0 until src.count) for (c in 0 until 3) {
            val x = src.x(t, c).toDouble(); val y = src.y(t, c).toDouble(); val z = src.z(t, c).toDouble()
            out[t * 9 + c * 3] = x.toFloat(); out[t * 9 + c * 3 + 1] = y.toFloat(); out[t * 9 + c * 3 + 2] = (warp(x, y, z) + modelShift).toFloat()
        }
        return TriangleSoup(out, src.count)
    }

    /**
     * Where the warp moves anything (x0, y0, x1, y1, mm), for [subdivided]; null: anywhere. A warp whose shift varies at [featureMm]
     * gives it, so only the triangles there are split.
     */
    open val region: DoubleArray? get() = null

    /**
     * [soup] with every triangle that reaches over [region] above [zStart] split (its longest edge across XY halved, again)
     * until no edge is longer across XY than [featureMm]: a vertex is moved and the triangle between stays flat, so a triangle
     * longer than the warp's feature took a shift between its corners where the warp's own differs (a curved
     * island's warp, varying cell by cell, on the Benchy's bow; its walls cut a 0.3 mm step of the hull's outline).
     */
    fun subdivided(soup: TriangleSoup): TriangleSoup {
        val r = region
        val lim2 = featureMm * featureMm
        val out = ArrayList<FloatArray>(soup.count)
        val stack = ArrayDeque<FloatArray>()
        for (t in 0 until soup.count) {
            val tri = FloatArray(9) { soup.coords[t * 9 + it] }
            val reaches = (tri[2] > zStart || tri[5] > zStart || tri[8] > zStart) && (r == null ||
                maxOf(tri[0], tri[3], tri[6]) >= r[0] && minOf(tri[0], tri[3], tri[6]) <= r[2] && maxOf(tri[1], tri[4], tri[7]) >= r[1] && minOf(tri[1], tri[4], tri[7]) <= r[3])
            if (!reaches) { out += tri; continue }
            stack.addLast(tri)
            while (stack.isNotEmpty()) {
                val q = stack.removeLast()
                fun d2(a: Int, b: Int): Double { val dx = (q[a * 3] - q[b * 3]).toDouble(); val dy = (q[a * 3 + 1] - q[b * 3 + 1]).toDouble(); return dx * dx + dy * dy }
                val e = doubleArrayOf(d2(0, 1), d2(1, 2), d2(2, 0))
                val k = if (e[0] >= e[1] && e[0] >= e[2]) 0 else if (e[1] >= e[2]) 1 else 2
                if (e[k] <= lim2) { out += q; continue }
                // split edge k (corners k, k+1) at its middle m: (k, m, k+2) and (m, k+1, k+2), the winding kept
                val a = k; val b = (k + 1) % 3; val c = (k + 2) % 3
                val m = FloatArray(3) { (q[a * 3 + it] + q[b * 3 + it]) / 2 }
                fun tri(p: FloatArray, s: FloatArray, u: FloatArray) = floatArrayOf(p[0], p[1], p[2], s[0], s[1], s[2], u[0], u[1], u[2])
                fun corner(i: Int) = floatArrayOf(q[i * 3], q[i * 3 + 1], q[i * 3 + 2])
                stack.addLast(tri(corner(a), m, corner(c)))
                stack.addLast(tri(m, corner(b), corner(c)))
            }
        }
        val coords = FloatArray(out.size * 9)
        for ((i, q) in out.withIndex()) System.arraycopy(q, 0, coords, i * 9, 9)
        return TriangleSoup(coords, out.size)
    }

    /**
     * How far the model is moved where it is printed (mm), on top of the warp: 0, except for a lifted bridge, which is printed
     * resting on its support's printed top, [LiftWarp.shift] from where the model has it. The g-code's points are already
     * there, so [warp] and [unwarp] leave it out; the model warped for slicing (and for the check) takes it.
     */
    open val modelShift: Double get() = 0.0
}

/**
 * The warp of a curved band that follows the model's TOP: above [zStart] and over the footprint of
 * [top], Z is stretched per column so the top lands on the flat [zTop]; at and below [zStart], and
 * outside the footprint, nothing moves. The band's layers, flat in warped space, are each
 * [thicknessFactor] times their warped thickness at (x, y): thicker where the top is higher.
 */
class Warp(override val zStart: Double, val zTop: Double, private val top: (Double, Double) -> Double) : ZWarp() {
    init { require(zTop > zStart) { "the band's top $zTop must be above its start $zStart" } }

    /** The top under (x, y) when it rises above the band's start there; NaN where the column is not warped. */
    private fun columnTop(x: Double, y: Double): Double { val t = top(x, y); return if (t.isNaN() || t <= zStart) Double.NaN else t }

    override fun warp(x: Double, y: Double, z: Double): Double {
        val t = columnTop(x, y)
        if (t.isNaN() || z <= zStart) return z
        return zStart + (z - zStart) * (zTop - zStart) / (t - zStart)
    }

    override fun unwarp(x: Double, y: Double, zw: Double): Double {
        val t = columnTop(x, y)
        if (t.isNaN() || zw <= zStart) return zw
        return zStart + (zw - zStart) * (t - zStart) / (zTop - zStart)
    }

    /** 1 where the column is not warped. */
    override fun thicknessFactor(x: Double, y: Double): Double {
        val t = columnTop(x, y)
        return if (t.isNaN()) 1.0 else (t - zStart) / (zTop - zStart)
    }
}

/**
 * The warp of layers that follow the model's UNDERSIDE (a thin arch over a hollow, "Follow both
 * curves"): each column moved down by the height of the underside above the bed ([zBed]), so the underside lands on the
 * bed in warped space and every layer, flat there, is a constant thickness parallel to the underside in real space. Where
 * the model stands on the bed (its underside at [zBed]) and outside its footprint nothing moves. The first layer over
 * a hollow is then printed in the air along the underside: a bridge.
 */
class UndersideShift(private val zBed: Double, private val under: (Double, Double) -> Double) : ZWarp() {
    override val zStart: Double get() = zBed
    /** How high the underside is above the bed at (x, y): 0 on the bed and outside the footprint. */
    fun lift(x: Double, y: Double): Double { val u = under(x, y); return if (u.isNaN() || u <= zBed) 0.0 else u - zBed }
    override fun warp(x: Double, y: Double, z: Double): Double = z - lift(x, y)
    override fun unwarp(x: Double, y: Double, zw: Double): Double = zw + lift(x, y)
    override fun thicknessFactor(x: Double, y: Double): Double = 1.0
}

/**
 * The warp of a lifted bridge (bridges that change height, printed "in order": what holds them up first,
 * then the bridge): the bridge's parts (those that do not reach the bed) are raised at each column until their underside
 * ([under]) is flat at [zStart], the highest anchor rounded up to a layer top; what holds them up is not moved. Sliced flat,
 * every support is then printed before the bridge's first layer, and the bridge's layers are laid back along its
 * underside: a straight rise between two anchors, or a dip onto a post. [warp] is for the bridge's vertices only (the
 * supports are not warped); [unwarp] lowers whatever is at or above [zStart], which by then is the bridge alone.
 */
class LiftWarp(override val zStart: Double, private val under: (Double, Double) -> Double,
               /** The printed top the bridge rests on (its highest contact with a support, on the layer grid); NaN when not known. */
               val restsOn: Double = Double.NaN,
               /**
                * How far the bridge is printed from where the model has it (mm): its support's printed top less the support's
                * own (a layer holds what is under its middle, so a 5.0 mm support prints to 4.98 on a 0.28 / 0.1 mm grid). [under]
                * is already moved by it. The bridge's model warped for slicing is moved by it too ([modelShift]), so its
                * underside lies at [zStart] and its top on the layer grid; left out, the strip sat [shift] off the grid in its
                * lifted frame and its top came out as a sliver split over two layers (0.22 mm as two of 0.11; the
                * 0.12 mm last layer of the arches on the 0.28 / 0.2 mm grid before it).
                */
               val shift: Double = 0.0) : ZWarp() {
    override val modelShift: Double get() = shift
    /** How far the bridge is raised at (x, y): [zStart] less its underside; 0 off its footprint. */
    fun lift(x: Double, y: Double): Double { val u = under(x, y); return if (u.isNaN() || u >= zStart) 0.0 else zStart - u }
    /** Whether (x, y) is under the bridge (its underside there, seen from below). */
    fun covers(x: Double, y: Double): Boolean = !under(x, y).isNaN()
    override fun warp(x: Double, y: Double, z: Double): Double = z + lift(x, y)
    override fun unwarp(x: Double, y: Double, zw: Double): Double = if (zw >= zStart - 1e-9) zw - lift(x, y) else zw
    override fun thicknessFactor(x: Double, y: Double): Double = 1.0

    companion object {
        /** Where a bridge's underside counts as resting on a support's top (mm): the two meet within this. */
        const val CONTACT_MM = 0.05
        /** A support top this close to a layer's middle counts as at it (mm): the mesh's float coordinates, not an allowance. */
        const val TIE_MM = 0.001

        /**
         * The lift for [bridge] held up by [supports] on a bed at [zBed], layers [firstMm] then [layerMm] thick. The
         * bridge rests on its highest support's PRINTED top (the top of the last layer that support is sliced into: a
         * layer holds what is under its middle), so its first layer sits on the plastic there, neither into it nor above
         * it: the whole underside is moved by that one difference, keeping it continuous (taking each support's own
         * printed top made a step at every support's edge, which a line across it cuts straight through: 0.04 mm off).
         * A lower support whose height falls differently on the layer grid then meets the bridge up to half a
         * layer off. [zStart]: the highest point of the moved underside, rounded up to a layer top.
         * [holdMm]: how far above a layer's bottom a support's top must reach for that layer to hold it, above the first
         * layer: null = the layer's middle (where the slicer cuts it); with Z anti-aliasing the thinnest layer (the layer
         * reaches out to where the top is that far up, and where a bridge rests on it the support is not lowered).
         */
        fun of(bridge: TriangleSoup, supports: TriangleSoup, zBed: Double, firstMm: Double, layerMm: Double, nzShare: Double, holdMm: Double? = null): LiftWarp {
            val under = TopSurface.underside(bridge, nzShare)
            val supTop = TopSurface.of(supports, nzShare)
            // a layer holds what is at or under its middle, as the slicer cuts it (Slicer: a triangle is in a plane when its
            // top is at or above it): a support whose top is a layer's middle prints that layer (6.1 mm pillars on
            // a 0.2 mm grid print to 6.2; taking them as 6.0 laid the strip's first layer at the pillars' own last layer)
            fun printedTop(s: Double): Double {
                val rel = s - zBed
                if (rel < firstMm / 2 - TIE_MM) return zBed
                val k = maxOf(0, Math.floor((rel - firstMm + (if (holdMm == null) layerMm / 2 else layerMm - holdMm) + TIE_MM) / layerMm).toInt())
                return zBed + firstMm + k * layerMm
            }
            // the highest point where the bridge rests on a support, and the bridge's own highest underside
            var contact = Double.NEGATIVE_INFINITY; var highest = Double.NEGATIVE_INFINITY
            for (t in 0 until bridge.count) for (c in 0 until 3) {
                val x = bridge.x(t, c).toDouble(); val y = bridge.y(t, c).toDouble()
                val u = under.top(x, y)
                if (u.isNaN()) continue
                highest = maxOf(highest, u)
                val s = supTop.top(x, y)
                if (!s.isNaN() && s >= u - CONTACT_MM) contact = maxOf(contact, u)
            }
            require(highest.isFinite()) { "the bridge has no underside" }
            require(contact.isFinite()) { "the bridge rests on no support" }
            val shift = printedTop(contact) - contact
            val rests = { x: Double, y: Double -> under.top(x, y) + shift }
            val high = highest + shift
            // (a µm-scale allowance: 11.8 / 0.2 comes out a hair over 59, which must not round up to 60)
            val j = maxOf(0, Math.ceil((high - zBed - firstMm) / layerMm - 1e-6).toInt())
            return LiftWarp(zBed + firstMm + j * layerMm, rests, printedTop(contact), shift)
        }
    }
}

/**
 * The SHIFTED warp of a curved island: above [zStart]
 * each column's layers are lowered by [dip] at (x, y) (0 off the island), phased in linearly over the band up to [zTop]:
 * real Z = warped Z - dip x g, g = (warped Z - zStart) / (zTop - zStart), clamped to 0..1. Where the dip is the band's top less
 * the model's top there, that top lands on the flat [zTop] in warped space, so a flat slice there follows it; elsewhere
 * the layers stay above the top. A layer is (1 - dip / (zTop - zStart)) of its warped thickness at (x, y): thinner, never
 * thicker, by an amount the band's length bounds (the proportional [Warp] squeezes a column whose top barely clears the
 * start to nothing, which a continuous top always has at its edge). Above [zTop] every column stays lowered by its dip.
 */
class ShiftWarp(override val zStart: Double, val zTop: Double,
                /** The grid the dip is read over bends at every cell (mm): [ZWarp.featureMm]. */
                override val featureMm: Double = Double.POSITIVE_INFINITY,
                /** Where the dip is not 0 (x0, y0, x1, y1): [ZWarp.region]. */
                override val region: DoubleArray? = null,
                private val dip: (Double, Double) -> Double) : ZWarp() {
    init { require(zTop > zStart) { "the band's top $zTop must be above its start $zStart" } }
    private val span = zTop - zStart

    /** The dip at (x, y): 0 where none is given (off the island). */
    fun dipAt(x: Double, y: Double): Double { val d = dip(x, y); return if (d.isNaN() || d <= 0.0) 0.0 else d }

    override fun warp(x: Double, y: Double, z: Double): Double {
        val d = dipAt(x, y)
        if (d == 0.0 || z <= zStart) return z
        require(d < span) { "a dip of $d mm needs a band longer than its $span mm" }
        // inside the band: z = zw - d (zw - zStart) / span, so zw = zStart + (z - zStart) / (1 - d / span)
        val zw = zStart + (z - zStart) / (1.0 - d / span)
        return if (zw <= zTop) zw else z + d
    }

    override fun unwarp(x: Double, y: Double, zw: Double): Double {
        val d = dipAt(x, y)
        if (d == 0.0 || zw <= zStart) return zw
        val g = minOf(1.0, (zw - zStart) / span)
        return zw - d * g
    }

    /** Inside the band; 1 off the island. */
    override fun thicknessFactor(x: Double, y: Double): Double = 1.0 - dipAt(x, y) / span
}
