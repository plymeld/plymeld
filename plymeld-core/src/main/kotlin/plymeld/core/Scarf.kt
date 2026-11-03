package plymeld.core

import plymeld.program.Extrude

/**
 * A scarf seam (plan.technique.scarf_seam.where): an outer wall loop printed as
 * three runs, one after the other with no travel between them:
 *
 *  1. the ramp: from the seam along the first L of the loop (L = plan.technique.scarf_seam.length, at most half the loop), the bead
 *     growing from plan.technique.scarf_seam.start of the layer's thickness to all of it, its top rising with it from just above the
 *     layer below to the layer's top;
 *  2. the rest of the loop at the layer's top, back to the seam;
 *  3. the ramp again at the layer's top, the bead thinning from what the ramp left open to nothing.
 *
 * Over the ramp the two wedges add up to one layer, so the loop has no seam blob and no gap: its start and end are
 * smeared along L instead of meeting at a point. A loop too short for a ramp of two line widths, or one of varying
 * width or flow, is printed as it is.
 */
object Scarf {
    /**
     * The ramp's longest step (µm): the writer lays each segment's plastic from the mean of its ends' thickness, which
     * overshoots the true mean by (1 - pi/4) (dt)^2 / 4 for a step dt thick; at 1 mm steps on a 10 mm ramp that is
     * under 0.1 % of the bead. plymeld's choice.
     */
    const val RAMP_STEP_UM = 1000.0

    /** A scarf's ramp on a loop: its length (µm), the loop edge [j] holding its end Q = ([qx], [qy]), the loop's edge lengths. */
    private class Ramp(val ramp: Double, val j: Int, val qx: Long, val qy: Long, val len: DoubleArray)

    /** [p]'s ramp, or null when it is printed as it is (no scarf asked, open, of varying width or flow, too short). */
    private fun ramp(p: ToolPath): Ramp? {
        if (p.scarfUm <= 0 || !p.closed || p.widths != null || p.ePerMmOverride != null) return null
        val m = p.xy.size / 2
        if (m < 3) return null
        fun x(i: Int) = p.xy[2 * (i % m)]
        fun y(i: Int) = p.xy[2 * (i % m) + 1]
        val len = DoubleArray(m) { Math.hypot((x(it + 1) - x(it)).toDouble(), (y(it + 1) - y(it)).toDouble()) }
        val ramp = minOf(p.scarfUm.toDouble(), len.sum() / 2)
        if (ramp < 2 * p.widthUm) return null
        // the edge j holding the ramp's end Q, and the distance along the loop to each vertex before it
        var j = 0; var acc = 0.0
        while (acc + len[j] < ramp) { acc += len[j]; j++ }
        val f = if (len[j] == 0.0) 0.0 else (ramp - acc) / len[j]
        return Ramp(ramp, j, Math.round(x(j) + (x(j + 1) - x(j)) * f), Math.round(y(j) + (y(j + 1) - y(j)) * f), len)
    }

    /**
     * Where the nozzle is when [p] is done (x, y in µm): a scarfed loop ends at its ramp's end Q, having re-printed the
     * ramp; any other loop at its seam, an open path at its last point.
     */
    fun endOf(p: ToolPath): LongArray {
        ramp(p)?.let { return longArrayOf(it.qx, it.qy) }
        return if (p.closed) longArrayOf(p.xy[0], p.xy[1]) else longArrayOf(p.xy[p.xy.size - 2], p.xy[p.xy.size - 1])
    }

    /** [p] (at the layer top [zTopUm]) as its extrusions: the three scarf runs when [ToolPath.scarfUm] asks for one, else one. */
    fun extrudes(p: ToolPath, zTopUm: Long, tool: Int): List<Extrude> {
        val whole = Extrude(p.feature.label, p.xy, p.closed, zTopUm, p.widthUm, p.heightUm, tool, p.overhangShare, p.ePerMmOverride, p.retractBefore, p.widths, hopOnly = p.hopOnly)
        val r = ramp(p) ?: return listOf(whole)
        val m = p.xy.size / 2
        fun x(i: Int) = p.xy[2 * (i % m)]
        fun y(i: Int) = p.xy[2 * (i % m) + 1]
        val len = r.len; val ramp = r.ramp; val j = r.j; val qx = r.qx; val qy = r.qy
        // the ramp's vertices: the seam (vertex 0) .. vertex j, then Q, each edge split into steps of at most RAMP_STEP_UM
        // (the writer lays a segment's plastic from the mean of its ends' thickness, exact only for short steps);
        // s = the distance along from the seam, edge = the loop edge each step lies on
        val pts = ArrayList<Long>(); val sl = ArrayList<Double>(); val edgeOf = ArrayList<Int>()
        var d = 0.0
        for (k in 0..j) {
            val ex = if (k == j) qx else x(k + 1); val ey = if (k == j) qy else y(k + 1)
            val el = if (k == j) ramp - d else len[k]
            val n = maxOf(1, Math.ceil(el / RAMP_STEP_UM).toInt())
            for (i in 0 until n) {
                pts += Math.round(x(k) + (ex - x(k)) * i.toDouble() / n); pts += Math.round(y(k) + (ey - y(k)) * i.toDouble() / n)
                sl += d + el * i / n; edgeOf += k
            }
            d += el
        }
        pts += qx; pts += qy; sl += ramp; edgeOf += j
        val rn = sl.size
        val rampXy = pts.toLongArray(); val s = sl.toDoubleArray()
        val h = p.heightUm; val t0 = p.scarfStart.coerceIn(0.0, 1.0) * h
        /** The ramp's bead thickness (µm) at distance [sk] from the seam. */
        fun t(sk: Double) = t0 + (h - t0) * sk / ramp
        val upZs = LongArray(rn) { k -> zTopUm - h + Math.round(t(s[k])) }
        upZs[rn - 1] = zTopUm
        // The extrusion follows the bead's thickness through `heights` (the writer's bead model, area t (w - t (1 - pi/4))),
        // which is not proportional to t: two beads of t and h - t carry more than one of h (up to (1 - pi/4) h^2 / 2 more
        // where they are equal, about 9 % at 0.3 mm layers: a bulge at the seam, read back from the Benchy's g-code).
        // So each pass's `heights` is the thickness whose bead holds its share of one layer's bead, t / h of
        // it: the two passes then carry exactly one layer's plastic all along the ramp.
        val w = p.widthUm.toDouble(); val kk = 1 - Math.PI / 4
        val full = h * (w - kk * h)
        fun flowThickness(share: Double): Long { val a = full * share.coerceIn(0.0, 1.0); return Math.round((w - Math.sqrt(w * w - 4 * kk * a)) / (2 * kk)) }
        val upHs = LongArray(rn) { k -> flowThickness(t(s[k]) / h) }.also { it[rn - 1] = h }
        val downHs = LongArray(rn) { k -> flowThickness(1 - t(s[k]) / h) }.also { it[rn - 1] = 0 }
        val downZs = LongArray(rn) { zTopUm }
        // the rest of the loop: Q, vertex j+1 .. the last, back to the seam
        val restN = m - j + 1
        val restXy = LongArray(2 * restN)
        restXy[0] = qx; restXy[1] = qy
        for (k in 1 until restN) { val v = (j + k) % m; restXy[2 * k] = x(v); restXy[2 * k + 1] = y(v) }
        // overhang shares follow the edges: the ramp's j edges and its piece of edge j; the rest from Q on edge j
        val sh = p.overhangShare
        val rampShares = sh?.let { a -> IntArray(rn) { k -> a[edgeOf[k]] } }
        val restShares = sh?.let { a -> IntArray(restN) { k -> a[(j + k) % m] } }
        val up = Extrude(p.feature.label, rampXy, false, zTopUm, p.widthUm, h, tool, rampShares, null, p.retractBefore, null, zs = upZs, heights = upHs, hopOnly = p.hopOnly)
        val rest = Extrude(p.feature.label, restXy, false, zTopUm, p.widthUm, h, tool, restShares, null, false, null)
        val down = Extrude(p.feature.label, rampXy, false, zTopUm, p.widthUm, downHs.max(), tool, rampShares, null, false, null, zs = downZs, heights = downHs)
        return listOf(up, rest, down)
    }
}
