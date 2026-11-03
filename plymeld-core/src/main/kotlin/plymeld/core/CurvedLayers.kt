package plymeld.core

import plymeld.mesh.ZWarp

/**
 * A curved band's paths back from warped space: the planar pipeline
 * planned them on the warped model, flat at the warped layer's top; each vertex goes back to the real
 * height of that layer surface at its (x, y), with the layer's real thickness there, and each segment is
 * split until the curve between its ends stays within [tolMm] of the straight move.
 */
object CurvedLayers {
    /** One path back from warped space: vertices (µm), and each vertex's Z and layer thickness (µm). */
    class Unwarped(val xy: LongArray, val zs: LongArray, val heights: LongArray, val widths: LongArray?, val overhangShare: IntArray?)

    /** The real Z of the warped layer surface [zTopW] at (x, y) (mm). */
    fun surfaceZ(warp: ZWarp, x: Double, y: Double, zTopW: Double) = warp.unwarp(x, y, zTopW)

    /**
     * Path [xy] (µm, absolute bed coordinates), planned on the warped layer from [zBottomW] to [zTopW] (mm,
     * absolute), back in real space. [widths] (per vertex) and [overhangShare] (per edge) follow their
     * vertices and edges onto the split path.
     */
    fun unwarp(xy: LongArray, closed: Boolean, widths: LongArray?, overhangShare: IntArray?,
               warp: ZWarp, zBottomW: Double, zTopW: Double, tolMm: Double): Unwarped =
        follow(xy, closed, widths, overhangShare, { x, y -> surfaceZ(warp, x, y, zTopW) }, { x, y -> warp.unwarp(x, y, zBottomW) }, tolMm, atSplit = true,
            // a surface bending at every cell of its grid (a curved island's, 0.25 mm) read at a quarter cell: at SAMPLE_MM the bends
            // between two samples went unseen and lines strayed up to 0.034 mm off the surface (the Benchy's bow)
            sampleMm = minOf(SAMPLE_MM, warp.featureMm / 4))

    /**
     * Path [xy] (µm, absolute bed coordinates) laid along the surface [topAt] (mm, absolute, at (x, y) in mm), each vertex's
     * thickness down to [bottomAt]; each segment split until the surface between its ends stays within [tolMm] of the
     * straight move. A curved layer's paths ([unwarp]) and lines lowered onto a sloped top (ZAntiAlias).
     *
     * [atSplit]: each vertex takes its Z and thickness where the split put it, not where its µm-rounded position is. A
     * surface with a step (a top meeting a face that rises from it: ZAntiAlias) is split just before the step, and that
     * point rounds onto the step: read again there, it took the height after the step, and the step went back into the
     * straight move before it (a wall 0.6 mm long rising 0.08 mm along a ring's flat top). With it the step is a
     * move straight up at one place. Curved layers too: their surface follows the model's top, which steps where a face
     * rises from it (a 12 mm strip on a 15 mm foot; the walls ramped 0.13 mm over the foot before the strip's edge).
     */
    fun follow(xy: LongArray, closed: Boolean, widths: LongArray?, overhangShare: IntArray?,
               topAt: (Double, Double) -> Double, bottomAt: (Double, Double) -> Double, tolMm: Double, atSplit: Boolean = false,
               /** How far apart a segment is read against the surface (mm). */
               sampleMm: Double = SAMPLE_MM): Unwarped {
        require(tolMm > 0) { "the tolerance must be positive" }
        val m = xy.size / 2
        val ox = ArrayList<Long>(); val oy = ArrayList<Long>(); val ow = ArrayList<Long>(); val oe = ArrayList<Int>()
        // the split's own (unrounded) positions, for [atSplit]
        val sx = ArrayList<Double>(); val sy = ArrayList<Double>()
        fun zAt(x: Double, y: Double) = topAt(x, y)
        fun add(x: Double, y: Double, w: Double) { ox.add(Math.round(x * 1000)); oy.add(Math.round(y * 1000)); ow.add(Math.round(w)); if (atSplit) { sx.add(x); sy.add(y) } }
        // split segment a->b (mm) where the surface Z strays furthest from the straight line, while that is more than tolMm:
        // sampled along the whole segment (every SAMPLE_MM, at least 4 pieces), not at its middle alone, since a surface
        // symmetric about the middle (flat, a ramp, flat: a bridge between two supports) is exactly on the straight line
        // there while bending away from it at both kinks (such a wall ran 0.61 mm into its support's top)
        fun split(ax: Double, ay: Double, aw: Double, bx: Double, by: Double, bw: Double, edge: Int, depth: Int) {
            val za = zAt(ax, ay); val zb = zAt(bx, by)
            val n = maxOf(4, Math.ceil(Math.hypot(bx - ax, by - ay) / sampleMm).toInt())
            fun dev(t: Double) = Math.abs(zAt(ax + (bx - ax) * t, ay + (by - ay) * t) - (za + (zb - za) * t))
            var worst = 0.0; var at = 0.5
            for (i in 1 until n) { val t = i.toDouble() / n; val d = dev(t); if (d > worst) { worst = d; at = t } }
            // the furthest point lies between the samples either side of the worst one: found there by ternary search, so
            // a kink (a support's edge) is split on itself, not up to half a sample beside it, which cuts its corner
            if (worst > tolMm) {
                var lo = maxOf(0.0, at - 1.0 / n); var hi = minOf(1.0, at + 1.0 / n)
                repeat(40) { val m1 = lo + (hi - lo) / 3; val m2 = hi - (hi - lo) / 3; if (dev(m1) < dev(m2)) lo = m1 else hi = m2 }
                val t = (lo + hi) / 2
                if (dev(t) >= worst) at = t
            }
            if (depth < MAX_DEPTH && worst > tolMm) {
                val mx = ax + (bx - ax) * at; val my = ay + (by - ay) * at; val mw = aw + (bw - aw) * at
                split(ax, ay, aw, mx, my, mw, edge, depth + 1)
                split(mx, my, mw, bx, by, bw, edge, depth + 1)
            } else { add(bx, by, bw); oe.add(edge) }
        }
        fun vx(i: Int) = xy[2 * i] / 1000.0
        fun vy(i: Int) = xy[2 * i + 1] / 1000.0
        fun vw(i: Int) = widths?.get(i)?.toDouble() ?: 0.0
        add(vx(0), vy(0), vw(0))
        val edges = if (closed) m else m - 1
        for (k in 0 until edges) {
            val a = k; val b = (k + 1) % m
            split(vx(a), vy(a), vw(a), vx(b), vy(b), vw(b), k, 0)
        }
        // a closed path ends back on its first vertex: drop that copy (the writer closes the loop itself)
        if (closed && ox.size > 1) { ox.removeAt(ox.size - 1); oy.removeAt(oy.size - 1); ow.removeAt(ow.size - 1); if (atSplit) { sx.removeAt(sx.size - 1); sy.removeAt(sy.size - 1) } }
        val n = ox.size
        val outXy = LongArray(2 * n) { if (it % 2 == 0) ox[it / 2] else oy[it / 2] }
        val zs = LongArray(n); val hs = LongArray(n)
        for (i in 0 until n) {
            val x = if (atSplit) sx[i] else ox[i] / 1000.0; val y = if (atSplit) sy[i] else oy[i] / 1000.0
            val top = topAt(x, y); val bottom = bottomAt(x, y)
            zs[i] = Math.round(top * 1000); hs[i] = Math.round((top - bottom) * 1000)
        }
        // per-edge shares: a closed loop's last edge closes it; an open path has one edge fewer than vertices
        val shares = overhangShare?.let { s -> IntArray(if (closed) n else n - 1) { s[oe[it]] } }
        return Unwarped(outXy, zs, hs, widths?.let { LongArray(n) { ow[it] } }, shares)
    }

    /**
     * The highest real Z of the warped layer surface [zTopW] along the straight travel from (ax, ay) to
     * (bx, by) (mm), sampled every [stepMm] and at both ends: a travel inside a curved layer lifts above it.
     */
    fun highestAlong(warp: ZWarp, ax: Double, ay: Double, bx: Double, by: Double, zTopW: Double, stepMm: Double): Double {
        val len = Math.hypot(bx - ax, by - ay)
        val n = maxOf(1, Math.ceil(len / stepMm).toInt())
        var best = Double.NEGATIVE_INFINITY
        for (i in 0..n) {
            val t = i.toDouble() / n
            best = maxOf(best, surfaceZ(warp, ax + (bx - ax) * t, ay + (by - ay) * t, zTopW))
        }
        return best
    }

    /** A segment is split at most this many times over: a guard, never reached on a smooth top. */
    const val MAX_DEPTH = 12

    /** How far apart a segment's surface is sampled for its split (mm). plymeld's choice, the travels' step. */
    const val SAMPLE_MM = 0.5
}
