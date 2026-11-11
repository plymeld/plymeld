package plymeld.core

/**
 * An object's planned lines cut to a section left open (`model.object.<key>.section`, faces = "open"): the whole object is
 * planned, and only what lies inside the box is laid, so the section prints as that part of the whole print would: its
 * walls, seams and infill where the whole model has them, nothing added on the cut faces (the infill shows there).
 *
 * A path wholly inside is kept as it is (its seam and scarf with it). A path the box cuts is laid as its pieces inside, in
 * the order the whole path runs, each an open line: its width and overhang shares carried along, the point where it is cut
 * placed on the box's side; a cut loop's scarf is dropped (its ramp and closing pass belong to the whole loop), its seam
 * stays where it was when that is inside. The travel into the first piece is the path's own; into any later piece it
 * retracts (the pieces lie apart, across what was cut away), as does the travel into the path after a cut or left-out one:
 * planned from where that path ended, it now starts elsewhere (an unretracted step from the outer wall's end to the next
 * wall became a travel across the wall).
 */
object SectionClip {
    /** Pieces shorter than this (µm) are not laid: a corner of the box clipping a line's tip. */
    private const val MIN_PIECE_UM = 50.0

    /** [layers] with every path cut to x [x0]..[x1], y [y0]..[y1] (µm), and the layers topping above [topUm] left out. */
    fun layers(layers: List<LayerPlanOut>, x0: Long, x1: Long, y0: Long, y1: Long, topUm: Long, same: (LayerPlanOut, LayerPlanOut) -> LayerPlanOut = { c, _ -> c }): List<LayerPlanOut> =
        layers.filter { it.topZUm <= topUm + 1 }.map { l -> same(LayerPlanOut(l.index, l.topZUm, l.heightUm, paths(l.paths, x0, x1, y0, y1)), l) }

    fun paths(ps: List<ToolPath>, x0: Long, x1: Long, y0: Long, y1: Long): List<ToolPath> {
        val out = ArrayList<ToolPath>()
        var moved = false          // the path before was cut or left out: the head is not where the next path was planned from
        for (p in ps) {
            val pieces = path(p, x0, x1, y0, y1)
            for ((k, q) in pieces.withIndex()) out += if (k == 0 && moved) q.retracted() else q
            moved = pieces.size != 1 || pieces[0] !== p
        }
        return out
    }

    /** [p] within the box: itself when all of it is inside, else its pieces inside (none when it lies outside). */
    fun path(p: ToolPath, x0: Long, x1: Long, y0: Long, y1: Long): List<ToolPath> {
        val m = p.xy.size / 2
        if (m < 2) return emptyList()
        var allIn = true
        for (i in 0 until m) { val x = p.xy[2 * i]; val y = p.xy[2 * i + 1]; if (x < x0 || x > x1 || y < y0 || y > y1) { allIn = false; break } }
        if (allIn) return listOf(p)
        val edges = if (p.closed) m else m - 1
        val out = ArrayList<ToolPath>()
        var pts = ArrayList<Long>(); var ws = ArrayList<Long>(); var shares = ArrayList<Int>(); var fromStart = false
        fun flush() {
            if (pts.size >= 4) {
                var len = 0.0
                for (q in 2 until pts.size step 2) len += Math.hypot((pts[q] - pts[q - 2]).toDouble(), (pts[q + 1] - pts[q - 1]).toDouble())
                if (len >= MIN_PIECE_UM) {
                    val n = pts.size / 2
                    // one share per vertex for an open path: each edge's, the last repeated
                    val sh = p.overhangShare?.let { IntArray(n) { shares[minOf(it, shares.size - 1)] } }
                    out += ToolPath(p.feature, pts.toLongArray(), false, p.widthUm, p.heightUm, sh, p.ePerMmOverride, if (fromStart && out.isEmpty()) p.retractBefore else true,
                        if (p.widths == null) null else ws.toLongArray(), 0, 0.0, p.raiseUm, p.waveFill, p.climbFrom, p.afterLayer, false, p.sequence, false, false, p.wall, p.scarfStepUm)
                }
            }
            pts = ArrayList(); ws = ArrayList(); shares = ArrayList(); fromStart = false
        }
        for (k in 0 until edges) {
            val j = (k + 1) % m
            val ax = p.xy[2 * k].toDouble(); val ay = p.xy[2 * k + 1].toDouble(); val bx = p.xy[2 * j].toDouble(); val by = p.xy[2 * j + 1].toDouble()
            // the part of the edge inside the box: t0..t1 along it (Liang-Barsky)
            var t0 = 0.0; var t1 = 1.0; var hit = true
            fun side(d: Double, lo: Double, hi: Double, a: Double) {
                if (d == 0.0) { if (a < lo || a > hi) hit = false; return }
                var ta = (lo - a) / d; var tb = (hi - a) / d
                if (ta > tb) { val s = ta; ta = tb; tb = s }
                t0 = maxOf(t0, ta); t1 = minOf(t1, tb)
            }
            side(bx - ax, x0.toDouble(), x1.toDouble(), ax); side(by - ay, y0.toDouble(), y1.toDouble(), ay)
            if (!hit || t0 >= t1) { flush(); continue }
            fun at(t: Double): LongArray = if (t == 0.0) longArrayOf(p.xy[2 * k], p.xy[2 * k + 1]) else if (t == 1.0) longArrayOf(p.xy[2 * j], p.xy[2 * j + 1])
                else longArrayOf(Math.round(ax + t * (bx - ax)), Math.round(ay + t * (by - ay)))
            fun widthAt(t: Double): Long = p.widths?.let { w -> Math.round(w[k] + t * (w[j] - w[k])) } ?: p.widthUm
            if (t0 > 0.0 || pts.isEmpty()) {
                flush()
                val a = at(t0); pts.add(a[0]); pts.add(a[1]); ws.add(widthAt(t0)); fromStart = k == 0 && t0 == 0.0
            }
            val b = at(t1); pts.add(b[0]); pts.add(b[1]); ws.add(widthAt(t1))
            shares.add(p.overhangShare?.let { it[minOf(k, it.size - 1)] } ?: 0)
            if (t1 < 1.0) flush()
        }
        flush()
        return out
    }
}
