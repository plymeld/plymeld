package plymeld.check.render

import java.awt.Color
import java.util.Locale

/** The print's outside seen from one side ([side]: front, back, left, right, or top): its outermost lines. */
internal class OutsideView(val side: String) : View {
    override val fileName = "outside-$side"
    override val title = "outside from the $side"
}

/** The print in 3D, seen from [turn] round to the right of the front and [tilt] up (degrees). */
internal class ThreeDView(val turn: Double, val tilt: Double, named: Boolean) : View {
    override val fileName = if (named) "3d-${n(turn)},${n(tilt)}" else "3d"
    override val title = "3D from turn ${n(turn)}, tilt ${n(tilt)}"
    companion object {
        fun n(v: Double) = if (Math.abs(v - Math.round(v)) < 1e-9) Math.round(v).toString() else String.format(Locale.ROOT, "%.1f", v)
        /** The view `--3d` alone gives: from the front-left above (plymeld's choice of the angles). */
        const val TURN = -35.0
        const val TILT = 30.0
    }
}

/**
 * An orthographic view of the bed: picture across [a], up [b] (stretched [k] where it is height), and depth (smaller is
 * nearer the viewer). [sideView]: up is height, so --height-stretch applies.
 */
internal class Projection(val rx: Double, val ry: Double, val rz: Double, val ux: Double, val uy: Double, val uz: Double,
                          val dx: Double, val dy: Double, val dz: Double, val sideView: Boolean, val words: String) {
    fun a(x: Double, y: Double, z: Double) = x * rx + y * ry + z * rz
    fun b(x: Double, y: Double, z: Double) = x * ux + y * uy + z * uz
    /** Distance away from the viewer (along the view). */
    fun depth(x: Double, y: Double, z: Double) = -(x * dx + y * dy + z * dz)
    /** The same view as [o]: every axis the same. */
    fun same(o: Projection) = rx == o.rx && ry == o.ry && rz == o.rz && ux == o.ux && uy == o.uy && uz == o.uz && dx == o.dx && dy == o.dy && dz == o.dz && sideView == o.sideView

    companion object {
        /** The views of the whole print [req] asks for (`--outside`, `--3d`), as [Context.views] makes them. */
        fun of(req: RenderRequest): List<Projection> =
            req.outside.distinct().filter { it in SIDES }.map { outside(it) } +
                (if (!req.threeD) emptyList() else if (req.threeDFrom.isEmpty()) listOf(turned(ThreeDView.TURN, ThreeDView.TILT)) else req.threeDFrom.map { (t, u) -> turned(t, u) })
        private val SIDES = setOf("front", "back", "left", "right", "top")
        /** The viewer looking from [d] (toward the viewer), up as near Z as the view allows. */
        private fun from(dx: Double, dy: Double, dz: Double, side: Boolean, words: String): Projection {
            // right = up x toward with up = Z (level views), or the turn's own right (the top view and tilted ones)
            val lh = Math.hypot(dx, dy)
            val rx: Double; val ry: Double
            if (lh < 1e-9) { rx = 1.0; ry = 0.0 } else { rx = -dy / lh; ry = dx / lh }
            // up = toward x right
            val ux = dy * 0.0 - dz * ry; val uy = dz * rx - dx * 0.0; val uz = dx * ry - dy * rx
            return Projection(rx, ry, 0.0, ux, uy, uz, dx, dy, dz, side, words)
        }
        fun outside(side: String): Projection = when (side) {
            "front" -> from(0.0, -1.0, 0.0, true, "from the front: X across, height up")
            "back" -> from(0.0, 1.0, 0.0, true, "from the back: X across (its high end at the left), height up")
            "left" -> from(-1.0, 0.0, 0.0, true, "from the left: Y across (the bed's front at the right), height up")
            "right" -> from(1.0, 0.0, 0.0, true, "from the right: Y across (the bed's front at the left), height up")
            "top" -> Projection(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0, false, "from above: X across, Y up")
            else -> error("--outside $side: one of front, back, left, right, or top")
        }
        fun turned(turnDeg: Double, tiltDeg: Double): Projection {
            val t = Math.toRadians(turnDeg); val p = Math.toRadians(tiltDeg)
            return from(Math.sin(t) * Math.cos(p), -Math.cos(t) * Math.cos(p), Math.sin(p), false,
                "turn ${ThreeDView.n(turnDeg)} degrees, tilt ${ThreeDView.n(tiltDeg)} degrees (0, 0 is level from the front; turn goes round to the right, tilt rises): orthographic")
        }
    }
}

/**
 * Draws a view of the whole print (or the part --z-range, --bed-window, or --around keep) into a depth buffer: every
 * chosen extruding move as a round bead (an ellipse [w] wide and [h] tall seen side on, swept along the move), the
 * nearest bead at each pixel kept, shaded as a tube so neighboring lines read apart. The file is read a run of layers at
 * a time, never whole. Where the lines cannot be resolved, flat colors with each region's outline.
 *
 * On [threads] threads: the pieces of a run of layers are read at once (each from its own offset, through the index),
 * and the run's beads are drawn by bands of rows at once, each band drawing every bead that reaches it in the same
 * order. A pixel belongs to one band, which draws into it in the order one thread would, so the picture is the same
 * bytes whatever the number of threads.
 */
internal class SolidDrawing(private val ctx: Context, private val p: Projection, private val threads: Int) {
    private val req = ctx.req

    class Survey(var a0: Double, var b0: Double, var a1: Double, var b1: Double, var lo: Double, var hi: Double, var count: Int,
                 val spacing: Double, val hMin: Double, val zLo: Double, val zHi: Double)

    private fun layersToRead(): List<Int> = ctx.index.layers.filter { drawsFrom(req, it) }.map { it.index }
    private val window: DoubleArray? = windowOf(req)
    private fun kept(m: MoveSet, i: Int): Boolean = kept(req, window, m.x0[i], m.y0[i], m.z0[i], m.x1[i], m.y1[i], m.z1[i])
    /**
     * Reads the layers drawn a piece at a time, [BATCH] pieces at once on the threads, [work] making each piece's result
     * from its moves on a thread of its own, then hands the results to [use] one by one in print order, or ([reverse]) in
     * exactly the reverse of it. The pieces are the same whatever the number of threads.
     */
    private fun <R> pieces(reverse: Boolean, work: (MoveSet) -> R, use: (R) -> Unit) = batches(reverse, work) { rs -> for (r in rs) use(r) }

    /** As [pieces], each batch's results handed to [use] at once, in print order (or its reverse). */
    private fun <R> batches(reverse: Boolean, work: (MoveSet) -> R, use: (List<R>) -> Unit) {
        val ls = layersToRead()
        val all = (ls.indices step PIECE).map { ls.subList(it, minOf(ls.size, it + PIECE)) }
        val order = if (reverse) all.asReversed() else all
        for (b in order.indices step BATCH) {
            val batch = order.subList(b, minOf(order.size, b + BATCH))
            val results = ArrayList<R?>(batch.size).also { r -> repeat(batch.size) { r += null } }
            parallel(batch.size) { j -> results[j] = work(MoveReader.read(ctx.index, batch[j]).first) }
            use(results.map { it!! })
        }
    }

    /** [body] for 0 until [n], on the threads (at most [threads] at once); on this thread alone where there is one. */
    private fun parallel(n: Int, body: (Int) -> Unit) {
        if (threads <= 1 || n <= 1) { for (j in 0 until n) body(j); return }
        try { pool.submit { java.util.stream.IntStream.range(0, n).parallel().forEach(body) }.get() }
        catch (e: java.util.concurrent.ExecutionException) { throw e.cause ?: e }
    }
    private val pool by lazy { java.util.concurrent.ForkJoinPool(threads) }
    /** Ends the threads this drawing started. */
    fun close() { if (threads > 1) pool.shutdown() }

    /** One piece's share of the survey: its extent, values, and count, and its lengths by spacing and by height in print order. */
    private class Part {
        var a0 = Double.MAX_VALUE; var b0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
        var lo = Double.NaN; var hi = Double.NaN; var n = 0
        var zLo = Double.MAX_VALUE; var zHi = -Double.MAX_VALUE
        var spBin = IntArray(64); var spLen = DoubleArray(64); var spN = 0
        var hsBin = IntArray(64); var hsLen = DoubleArray(64); var hsN = 0
        fun sp(bin: Int, len: Double) { if (spN == spBin.size) { spBin = spBin.copyOf(spN * 2); spLen = spLen.copyOf(spN * 2) }; spBin[spN] = bin; spLen[spN++] = len }
        fun hs(bin: Int, len: Double) { if (hsN == hsBin.size) { hsBin = hsBin.copyOf(hsN * 2); hsLen = hsLen.copyOf(hsN * 2) }; hsBin[hsN] = bin; hsLen[hsN++] = len }
    }

    private fun surveyPiece(m: MoveSet): Part {
        val r = Part()
        for (i in 0 until m.n) {
            if (m.kind[i] != MoveSet.EXTRUDE || !kept(m, i)) continue
            val ch = ctx.chosen(m, i)
            if (!ch && req.others != Others.FAINT) continue
            val w = ctx.beadWidth(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.4
            val h = ctx.heightOf(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.2
            for (end in 0..1) {
                val x = if (end == 0) m.x0[i] else m.x1[i]; val y = if (end == 0) m.y0[i] else m.y1[i]; val z = if (end == 0) m.z0[i] else m.z1[i]
                val a = p.a(x, y, z); val b = p.b(x, y, z)
                r.a0 = minOf(r.a0, a - w); r.a1 = maxOf(r.a1, a + w); r.b0 = minOf(r.b0, b - w - h); r.b1 = maxOf(r.b1, b + w)
                r.zLo = minOf(r.zLo, z - h); r.zHi = maxOf(r.zHi, z)
            }
            if (!ch) continue
            if (ctx.colorBy != ColorBy.FEATURE && ctx.colorBy != ColorBy.TOOL && ctx.colorBy != ColorBy.ORDER) {
                val v = ctx.value(m, i, 0); if (!v.isNaN()) { if (r.lo.isNaN() || v < r.lo) r.lo = v; if (r.hi.isNaN() || v > r.hi) r.hi = v }
            }
            r.n++
            val len = m.lengthXY(i)
            val decl = m.wDecl[i].toDouble()
            if (decl.isNaN() || w >= Context.TAPER_SHARE * decl) r.sp(Math.round(plymeld.program.Bead.spacing(w, h) * 1000).toInt(), len)
            r.hs(Math.round(h * 1000).toInt(), len)
        }
        return r
    }

    /** The first pass: the extent drawn, the gradient's range, and the closest lines (spacing and thinnest layer). */
    fun survey(): Survey {
        var a0 = Double.MAX_VALUE; var b0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
        var lo = Double.NaN; var hi = Double.NaN; var n = 0
        var zLo = Double.MAX_VALUE; var zHi = -Double.MAX_VALUE
        // spacing and height, length-weighted, in 1-micron bins; each piece's lengths added in print order, so every sum
        // is made in the same order whatever the number of threads
        val sp = Hist(); val hs = Hist()
        pieces(false, ::surveyPiece) { r ->
            a0 = minOf(a0, r.a0); a1 = maxOf(a1, r.a1); b0 = minOf(b0, r.b0); b1 = maxOf(b1, r.b1)
            zLo = minOf(zLo, r.zLo); zHi = maxOf(zHi, r.zHi)
            if (!r.lo.isNaN() && (lo.isNaN() || r.lo < lo)) lo = r.lo
            if (!r.hi.isNaN() && (hi.isNaN() || r.hi > hi)) hi = r.hi
            n += r.n
            for (j in 0 until r.spN) sp.add(r.spBin[j], r.spLen[j])
            for (j in 0 until r.hsN) hs.add(r.hsBin[j], r.hsLen[j])
        }
        return finish(a0, b0, a1, b1, lo, hi, n, sp, hs, zLo, zHi)
    }

    /**
     * What a pixel shows: its color and the key entry it stands for (feature or tool; -2 none). Over the pixels, a coarse
     * map of tiles [TILE] pixels square, each holding the farthest depth drawn in it, so a bead behind all of a tile is
     * passed over without reading the tile's pixels.
     */
    class Buffer(val w: Int, val h: Int) {
        val depth = FloatArray(w * h) { Float.MAX_VALUE }
        val color = IntArray(w * h)
        /** The key entry (feature or tool id, -1 other lines, -2 nothing), as a short: a picture's buffers stay under 11 bytes a pixel. */
        val key = ShortArray(w * h) { -2 }
        /** How near the bead's middle the pixel is, 0..255. */
        val shade = ByteArray(w * h)
        /** Tiles across and down. */
        val tw = (w + TILE - 1) / TILE; val th = (h + TILE - 1) / TILE
        /** Each tile's farthest depth drawn, as last worked out: never nearer than the true one (depths only come nearer). */
        val tileFar = FloatArray(tw * th) { Float.MAX_VALUE }
        /** Each tile's pixels nothing is drawn in yet: while any is left, the tile hides nothing. */
        val tileEmpty = IntArray(tw * th).also { e ->
            for (ty in 0 until th) for (tx in 0 until tw) e[ty * tw + tx] = (minOf(w, (tx + 1) * TILE) - tx * TILE) * (minOf(h, (ty + 1) * TILE) - ty * TILE)
        }
        /** Tiles drawn in since their farthest depth was worked out. */
        val tileDirty = BooleanArray(tw * th)

        /** Tile [t]'s farthest depth, worked out again where it was drawn in since. */
        fun far(t: Int): Float {
            if (tileEmpty[t] > 0) return Float.MAX_VALUE
            if (tileDirty[t]) {
                val tx = t % tw; val ty = t / tw
                var f = -Float.MAX_VALUE
                for (y in ty * TILE until minOf(h, (ty + 1) * TILE)) { val row = y * w; for (x in tx * TILE until minOf(w, (tx + 1) * TILE)) f = maxOf(f, depth[row + x]) }
                tileFar[t] = f; tileDirty[t] = false
            }
            return tileFar[t]
        }
    }

    /**
     * The second pass: every kept move drawn into a buffer [bw] x [bh] pixels; (a, b) maps to the pixel ((a - a0) s,
     * (b1 - b k) s) with b stretched [k] times where it is height.
     *
     * Nearest first: seen from above, the moves are drawn in exactly the reverse of print order (the top layers first),
     * from below in print order, so the beads in front fill the tiles early and those behind them are passed over. Seen
     * level from a side, print order is not nearest first, and no tile is passed over (working out a tile's farthest
     * depth again after each bead drawn in it cost more than it saved: the outside of a boat from the front took three
     * times as long). The picture does not depend on the order: each pixel shows the nearest bead, and of beads at
     * exactly the same depth the one printed first, whichever order they are drawn in.
     */
    fun draw(sv: Survey, a0: Double, b1: Double, s: Double, k: Double, bw: Int, bh: Int): Buffer {
        val buf = Buffer(bw, bh)
        val reverse = p.dz > 0
        val cull = p.dz != 0.0
        val total = maxOf(1, sv.count - 1)
        // the chosen moves printed before the piece drawn next (drawing in reverse: counted down from them all)
        var before = if (reverse) sv.count else 0
        val bands = (bh + BAND - 1) / BAND
        batches(reverse, { m -> beads(m, sv, a0, b1, s, k, reverse) }) { rs ->
            for (r in rs) {
                if (reverse) before -= r.chosen
                if (ctx.colorBy == ColorBy.ORDER) for (j in 0 until r.n) if (r.ord[j] >= 0) r.rgb[j] = Palette.ramp((before + r.ord[j]).toDouble() / total).rgb
                if (!reverse) before += r.chosen
            }
            // each band draws the batch's beads that reach it, piece after piece in the order drawn
            parallel(bands) { band ->
                val y0 = band * BAND; val y1 = minOf(bh, y0 + BAND) - 1
                for (r in rs) for (j in 0 until r.n) if (r.yHi[j] >= y0 && r.yLo[j] <= y1)
                    capsule(buf, r.pa[j], r.pb[j], r.qa[j], r.qb[j], r.da[j], r.db[j], r.rx[j], r.ry[j], r.rMm[j], r.rgb[j], r.key[j], reverse, cull, y0, y1)
            }
        }
        return buf
    }
    private fun kOf(k: Double) = if (p.sideView) k else 1.0

    /**
     * One piece's beads as drawn, in the order they are drawn: each move's middle line in pixels and depth, its brush,
     * color and key entry, its rows, and (colored by order) its place among the piece's chosen moves in print order.
     */
    private class Beads(cap: Int) {
        var n = 0
        val pa = DoubleArray(cap); val pb = DoubleArray(cap); val qa = DoubleArray(cap); val qb = DoubleArray(cap)
        val da = DoubleArray(cap); val db = DoubleArray(cap); val rx = DoubleArray(cap); val ry = DoubleArray(cap); val rMm = DoubleArray(cap)
        val rgb = IntArray(cap); val key = IntArray(cap); val ord = IntArray(cap); val yLo = IntArray(cap); val yHi = IntArray(cap)
        var chosen = 0
    }

    private val toolRgb: Map<Int, Int> = ctx.index.tools.associateWith { ctx.toolColor(it).rgb }

    private fun beads(m: MoveSet, sv: Survey, a0: Double, b1: Double, s: Double, k: Double, reverse: Boolean): Beads {
        var count = 0
        for (i in 0 until m.n) if (m.kind[i] == MoveSet.EXTRUDE && kept(m, i)) count++
        val r = Beads(count)
        // each chosen move's place among the piece's chosen moves, in print order
        var placed = 0
        for (step in 0 until m.n) {
            val i = if (reverse) m.n - 1 - step else step
            if (m.kind[i] != MoveSet.EXTRUDE || !kept(m, i)) continue
            val ch = ctx.chosen(m, i)
            if (!ch && req.others != Others.FAINT) continue
            val j = r.n
            if (!ch) { r.rgb[j] = Palette.CONTEXT.rgb; r.key[j] = -1; r.ord[j] = -1 } else {
                r.rgb[j] = when (ctx.colorBy) {
                    ColorBy.FEATURE -> (if (ctx.index.namesFeatures) ctx.featureColor(m.feature[i]) else Palette.UNNAMED).rgb
                    ColorBy.TOOL -> toolRgb[m.tool[i]] ?: ctx.toolColor(m.tool[i]).rgb
                    ColorBy.ORDER -> 0
                    else -> { val v = ctx.value(m, i, 0); (if (v.isNaN()) Palette.UNNAMED else Palette.ramp(if (sv.hi > sv.lo) (v - sv.lo) / (sv.hi - sv.lo) else 0.5)).rgb }
                }
                r.key[j] = when (ctx.colorBy) { ColorBy.FEATURE -> m.feature[i]; ColorBy.TOOL -> m.tool[i]; else -> 0 }
                r.ord[j] = placed++
            }
            val w = ctx.beadWidth(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.4
            val h = ctx.heightOf(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.2
            // the bead's center line, half its height under the nozzle
            val ax = m.x0[i]; val ay = m.y0[i]; val az = m.z0[i] - h / 2; val bx = m.x1[i]; val by = m.y1[i]; val bz = m.z1[i] - h / 2
            r.pa[j] = (p.a(ax, ay, az) - a0) * s; r.pb[j] = (b1 - p.b(ax, ay, az) * kOf(k)) * s
            r.qa[j] = (p.a(bx, by, bz) - a0) * s; r.qb[j] = (b1 - p.b(bx, by, bz) * kOf(k)) * s
            r.da[j] = p.depth(ax, ay, az); r.db[j] = p.depth(bx, by, bz)
            // the brush: half its width across, half its height up (side on), stretched as the heights are
            r.rx[j] = maxOf(0.5, w / 2 * s)
            r.ry[j] = maxOf(0.5, (if (p.sideView) h / 2 * k else h / 2 * Math.abs(p.uz) + w / 2 * Math.sqrt(maxOf(0.0, 1 - p.uz * p.uz))) * s)
            r.rMm[j] = w / 2
            r.yLo[j] = Math.floor(minOf(r.pb[j], r.qb[j]) - r.ry[j]).toInt(); r.yHi[j] = Math.ceil(maxOf(r.pb[j], r.qb[j]) + r.ry[j]).toInt()
            r.n++
        }
        r.chosen = placed
        // drawn in reverse, the places were counted from the piece's last chosen move: turned to print order
        if (reverse) for (j in 0 until r.n) if (r.ord[j] >= 0) r.ord[j] = placed - 1 - r.ord[j]
        return r
    }

    companion object {
        /** Whether a whole-print view reads layer [l]: one that lays plastic, at the heights --z-range keeps. */
        fun drawsFrom(req: RenderRequest, l: LayerInfo): Boolean {
            val zr = req.zRange
            return !l.minZ.isNaN() && (zr == null || (l.maxZ >= zr.start && l.minZ - maxOf(l.height, 0.0) <= zr.endInclusive))
        }
        /** The part of the bed --bed-window or --around keeps (x0, y0, x1, y1), or null: all of it. */
        fun windowOf(req: RenderRequest): DoubleArray? = req.bedWindow ?: req.around?.let { (x, y) -> val s = req.span!! / 2; doubleArrayOf(x - s, y - s, x + s, y + s) }
        /** Whether a move from (x0, y0, z0) to (x1, y1, z1) is kept: at the heights --z-range keeps, its middle in the [window]. */
        fun kept(req: RenderRequest, window: DoubleArray?, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): Boolean {
            val zr = req.zRange
            if (zr != null && (maxOf(z0, z1) < zr.start || minOf(z0, z1) > zr.endInclusive)) return false
            val w = window ?: return true
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            return mx >= w[0] && mx <= w[2] && my >= w[1] && my <= w[3]
        }

        /** The survey from its sums: the closest lines are those past the narrowest [Context.THIN_SHARE] of the length. */
        fun finish(a0: Double, b0: Double, a1: Double, b1: Double, lo: Double, hi: Double, n: Int, sp: Hist, hs: Hist, zLo: Double, zHi: Double): Survey {
            fun low(t: java.util.TreeMap<Int, Double>): Double {
                val total = t.values.sum(); if (total <= 0) return Double.NaN
                var acc = 0.0
                for ((k, v) in t) { acc += v; if (acc > Context.THIN_SHARE * total) return k / 1000.0 }
                return t.lastKey() / 1000.0
            }
            if (a0 > a1) return Survey(0.0, 0.0, 10.0, 10.0, lo, hi, n, low(sp.sorted()), low(hs.sorted()), 0.0, 1.0)
            return Survey(a0, b0, a1, b1, lo, hi, n, low(sp.sorted()), low(hs.sorted()), zLo, zHi)
        }

        /** Neighboring pixels further apart in depth than this (mm) are one surface in front of another: an outline (plymeld's choice). */
        const val DEPTH_STEP_MM = 1.0f
        /** The side of a tile of the depth map, pixels (plymeld's choice: a few hundred pixels read to work out its farthest depth). */
        const val TILE = 16
        /** Layers read in one piece, and pieces read at once (plymeld's choice: 64 layers in memory at a time, a few megabytes of moves). */
        const val PIECE = 8
        const val BATCH = 8
        /** Rows a band draws, a whole number of tiles so each tile is one band's (plymeld's choice: a 2048 px picture is 64 bands to share out). */
        const val BAND = 2 * TILE

        /**
         * One bead: the ellipse [rx] x [ry] pixels swept from (pa, pb) to (qa, qb), its middle line at depths [da] to [db]
         * (mm), bulging [rMm] toward the viewer at its middle. A pixel takes it where it is nearer than what the pixel
         * shows; [laterFirst]: the moves are drawn in the reverse of print order, so at exactly the same depth it is the
         * one printed first and takes the pixel too. [cull]: tiles whose farthest depth is nearer than the bead can come
         * there are passed over. Only rows [rowLo] to [rowHi] are drawn (a band's), each a whole number of tiles.
         */
        internal fun capsule(buf: Buffer, pa: Double, pb: Double, qa: Double, qb: Double, da: Double, db: Double, rx: Double, ry: Double, rMm: Double, rgb: Int, keyId: Int,
                             laterFirst: Boolean, cull: Boolean, rowLo: Int = 0, rowHi: Int = buf.h - 1) {
            val x0 = maxOf(0, Math.floor(minOf(pa, qa) - rx).toInt()); val x1 = minOf(buf.w - 1, Math.ceil(maxOf(pa, qa) + rx).toInt())
            val y0 = maxOf(rowLo, Math.floor(minOf(pb, qb) - ry).toInt()); val y1 = minOf(rowHi, Math.ceil(maxOf(pb, qb) + ry).toInt())
            if (x0 > x1 || y0 > y1) return
            // in the brush's own frame the ellipse is a circle of radius rx: y scaled by rx / ry
            val sc = rx / ry
            val ex = qa - pa; val ey = (qb - pb) * sc
            val l2 = ex * ex + ey * ey
            val len = Math.sqrt(l2)
            // each row's span: the columns the bead reaches on it (the stadium's chord at the row's middle, worked out
            // exactly), one more each side so no pixel the test below takes is left outside; only those are tested
            val spanLo = IntArray(TILE); val spanHi = IntArray(TILE)
            val band = DoubleArray(2)
            for (ty in y0 / TILE..y1 / TILE) {
                val ya = maxOf(y0, ty * TILE); val yb = minOf(y1, ty * TILE + TILE - 1)
                var rowsLo = Int.MAX_VALUE; var rowsHi = Int.MIN_VALUE
                for (y in ya..yb) {
                    val k = y - ya
                    spanLo[k] = 1; spanHi[k] = 0
                    val cy = (y + 0.5 - pb) * sc
                    var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
                    // the round ends
                    val e0 = rx * rx - cy * cy
                    if (e0 >= 0) { val r = Math.sqrt(e0); lo = minOf(lo, -r); hi = maxOf(hi, r) }
                    val e1 = rx * rx - (cy - ey) * (cy - ey)
                    if (e1 >= 0) { val r = Math.sqrt(e1); lo = minOf(lo, ex - r); hi = maxOf(hi, ex + r) }
                    // the straight sides: within rx of the middle line, and beside the move, not past its ends
                    if (l2 > 0) {
                        band[0] = -Double.MAX_VALUE; band[1] = Double.MAX_VALUE
                        atMost(band, -ey, rx * len - ex * cy); atMost(band, ey, rx * len + ex * cy)
                        atMost(band, -ex, cy * ey); atMost(band, ex, l2 - cy * ey)
                        if (band[0] <= band[1]) { lo = minOf(lo, band[0]); hi = maxOf(hi, band[1]) }
                    }
                    if (!(lo <= hi)) continue
                    spanLo[k] = maxOf(x0, Math.floor((lo + pa - 0.5).coerceIn(x0 - 2.0, x1 + 2.0)).toInt() - 1)
                    spanHi[k] = minOf(x1, Math.ceil((hi + pa - 0.5).coerceIn(x0 - 2.0, x1 + 2.0)).toInt() + 1)
                    if (spanLo[k] <= spanHi[k]) { rowsLo = minOf(rowsLo, spanLo[k]); rowsHi = maxOf(rowsHi, spanHi[k]) }
                }
                if (rowsLo > rowsHi) continue
                for (tx in rowsLo / TILE..rowsHi / TILE) {
                    val t = ty * buf.tw + tx
                    val xa = maxOf(x0, tx * TILE); val xb = minOf(x1, tx * TILE + TILE - 1)
                    if (cull && buf.tileEmpty[t] == 0) {
                        val near = nearest(xa, xb, ya, yb, pa, pb, sc, ex, ey, l2, da, db, rMm)
                        // the farthest depth as last worked out first: it is never nearer than the true one
                        if (near > buf.tileFar[t] || near > buf.far(t)) continue
                    }
                    var wrote = false
                    for (y in ya..yb) for (x in maxOf(xa, spanLo[y - ya])..minOf(xb, spanHi[y - ya])) {
                        val cx = x + 0.5 - pa; val cy = (y + 0.5 - pb) * sc
                        val tt = if (l2 == 0.0) 0.0 else ((cx * ex + cy * ey) / l2).coerceIn(0.0, 1.0)
                        val ddx = cx - tt * ex; val ddy = cy - tt * ey
                        val d2 = ddx * ddx + ddy * ddy
                        if (d2 > rx * rx) continue
                        val bulge = Math.sqrt(1 - d2 / (rx * rx))
                        val dep = (da + (db - da) * tt - bulge * rMm).toFloat()
                        val at = y * buf.w + x
                        val cur = buf.depth[at]
                        if (dep < cur || (laterFirst && dep == cur)) {
                            if (cur == Float.MAX_VALUE) buf.tileEmpty[t]--
                            buf.depth[at] = dep; buf.color[at] = rgb; buf.key[at] = keyId.toShort()
                            buf.shade[at] = Math.round(bulge * 255).toInt().toByte()
                            wrote = true
                        }
                    }
                    if (wrote) buf.tileDirty[t] = true
                }
            }
        }

        /** Narrows [band] (lowest and highest cx) to where a * cx <= b; an empty band where none is. */
        private fun atMost(band: DoubleArray, a: Double, b: Double) {
            if (a > 0) band[1] = minOf(band[1], b / a) else if (a < 0) band[0] = maxOf(band[0], b / a) else if (!(b >= 0)) { band[0] = 1.0; band[1] = 0.0 }
        }

        /**
         * A depth no pixel of the bead within columns [xa]..[xb] and rows [ya]..[yb] is nearer than, as the pixels work
         * theirs out: the middle line's nearest depth over the stretch of it those pixels lie beside (its place along the
         * line is linear in the pixel's position, so the rectangle's corners bound it), less the bulge, less a margin far
         * wider than the rounding of the pixels' own sums.
         */
        private fun nearest(xa: Int, xb: Int, ya: Int, yb: Int, pa: Double, pb: Double, sc: Double, ex: Double, ey: Double, l2: Double,
                            da: Double, db: Double, rMm: Double): Float {
            var lo = 0.0; var hi = 0.0
            if (l2 != 0.0) {
                // the four corners' places along the line: across and up each add their own term
                val ca = (xa + 0.5 - pa) * ex; val cb = (xb + 0.5 - pa) * ex
                val ra = (ya + 0.5 - pb) * sc * ey; val rb = (yb + 0.5 - pb) * sc * ey
                lo = (minOf(ca, cb) + minOf(ra, rb)) / l2; hi = (maxOf(ca, cb) + maxOf(ra, rb)) / l2
                lo = lo.coerceIn(0.0, 1.0); hi = hi.coerceIn(0.0, 1.0)
            }
            val d = minOf(da + (db - da) * lo, da + (db - da) * hi) - rMm
            return (d - 1e-9 * (1 + Math.abs(da) + Math.abs(db) + rMm)).toFloat()
        }
    }

    /**
     * The buffer as ARGB, made in its own color array (the buffer is spent): shaded as tubes ([shaded]), else flat with
     * each region's edge darker (edges found first, on the colors and depths as drawn).
     */
    fun image(buf: Buffer, shaded: Boolean): IntArray {
        val n = buf.w * buf.h
        val c = buf.color
        val edges = if (shaded) null else java.util.BitSet(n).also { e ->
            for (y in 0 until buf.h) for (x in 0 until buf.w) {
                val at = y * buf.w + x
                if (buf.depth[at] == Float.MAX_VALUE) continue
                // a region's edge: another color (or nothing) beside it, or a step in depth (one surface in front of another)
                fun differs(o: Int) = buf.depth[o] == Float.MAX_VALUE || c[o] != c[at] || Math.abs(buf.depth[o] - buf.depth[at]) > DEPTH_STEP_MM
                if (x == 0 || y == 0 || x == buf.w - 1 || y == buf.h - 1 || differs(at - 1) || differs(at + 1) || differs(at - buf.w) || differs(at + buf.w)) e.set(at)
            }
        }
        for (at in 0 until n) {
            if (buf.depth[at] == Float.MAX_VALUE) { c[at] = 0; continue }
            val v = c[at]
            val f = if (edges != null) (if (edges.get(at)) 0.55 else 1.0) else 0.55 + 0.5 * ((buf.shade[at].toInt() and 0xFF) / 255.0)
            c[at] = (0xFF shl 24) or (scale(v shr 16, f) shl 16) or (scale(v shr 8, f) shl 8) or scale(v, f)
        }
        return c
    }
    private fun scale(c: Int, f: Double) = ((c and 0xFF) * f).toInt().coerceIn(0, 255)

    fun key(buf: Buffer, sv: Survey): List<KeyItem> {
        val k = ArrayList<KeyItem>()
        val seen = java.util.TreeSet<Int>()
        for (v in buf.key) if (v.toInt() != -2) seen += v.toInt()
        when (ctx.colorBy) {
            ColorBy.FEATURE -> if (ctx.index.namesFeatures) for (f in seen) if (f >= 0) k += KeyItem.Swatch(ctx.featureColor(f), ctx.index.features[f])
            ColorBy.TOOL -> for (t in seen) if (t >= 0) k += KeyItem.Swatch(ctx.toolColor(t), "T$t")
            ColorBy.ORDER -> k += KeyItem.Gradient("printed first", "printed last")
            else -> if (!sv.lo.isNaN()) { val u = ctx.unit(); k += KeyItem.Gradient("${Render.f(sv.lo, 2)} $u", "${Render.f(sv.hi, 2)} $u") }
        }
        if (-1 in seen) k += KeyItem.Swatch(Palette.CONTEXT, "other lines")
        return k
    }
}

/**
 * Lengths by a whole-number bin (a micron), each bin's sum made in the order the lengths come; the bins from 0 to [DENSE]
 * in an array, any other in a map.
 */
internal class Hist {
    private val sum = DoubleArray(DENSE); private val has = BooleanArray(DENSE)
    private val other = java.util.TreeMap<Int, Double>()
    fun add(bin: Int, len: Double) {
        if (bin in 0 until DENSE) { sum[bin] += len; has[bin] = true } else other.merge(bin, len, Double::plus)
    }
    /** The bins in order, as a map ordered by bin. */
    fun sorted(): java.util.TreeMap<Int, Double> = java.util.TreeMap(other).also { t -> for (b in 0 until DENSE) if (has[b]) t[b] = sum[b] }
    companion object { const val DENSE = 4096 }
}

/**
 * The whole-print views' first pass ([SolidDrawing.survey]) made while the file is indexed, so the file is read once
 * before it is drawn rather than twice: each extruding move's extent as each view sees it, the gradient's range, and
 * the lengths by spacing and by thickness, added in print order as the pass made them.
 *
 * The index reads the file before it knows all of what the file states, so the sums are made on what is known at each
 * move and kept only where it held to the end; otherwise [survey] gives null and the view makes its own pass: a filament
 * diameter the file states after moves of its tool (a settings block at its end), a layer's thickness that changed while
 * it was read (a height declared after moves that declare none), or a layer the moves were counted from that the view
 * does not read.
 */
internal class IndexSurvey(private val req: RenderRequest, private val projections: List<Projection>) : GcodeIndex.Listener {
    private lateinit var features: Names
    private lateinit var objects: Names
    private val colorBy = req.colorBy ?: ColorBy.FEATURE
    private val window = SolidDrawing.windowOf(req)
    private val objectNames = req.onlyObject.toSet(); private val featureNames = req.onlyFeature.toSet(); private val toolIds = req.onlyTool.toSet()

    override fun begin(features: Names, objects: Names) { this.features = features; this.objects = objects }

    /** One reading's sums: the moves of the layers the file marks, or of those read from the moves' heights. */
    private inner class Sums {
        val ext = Array(projections.size) { doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE) }
        var zLo = Double.MAX_VALUE; var zHi = -Double.MAX_VALUE; var n = 0
        // the gradient's range; for width, from the plastic and as declared (which one is drawn is known at the end)
        var lo = Double.NaN; var hi = Double.NaN; var loDecl = Double.NaN; var hiDecl = Double.NaN
        val sp = Hist(); val hs = Hist()
        // per layer: moves counted; the thickness a move declaring none took; a chosen move (for the layer's time)
        val counted = java.util.BitSet(); val chosen = java.util.BitSet(); val heightTaken = HashMap<Int, Double>()
        // per tool, the filament diameter its moves took (NaN: none known)
        val diameter = DoubleArray(ScanState.MAX_TOOLS); val took = BooleanArray(ScanState.MAX_TOOLS)
        var held = true
    }
    private val inferred = Sums(); private val marked = Sums()
    // the diameter each tool takes from the statements read so far, worked out again when they change
    private var stated: List<Double>? = null
    private val toolDiameter = DoubleArray(ScanState.MAX_TOOLS)
    private fun diameterNow(diameters: List<Double>, tool: Int): Double {
        if (diameters !== stated) { stated = diameters; for (t in toolDiameter.indices) toolDiameter[t] = Context.diameterOf(diameters, req.facts, t) ?: Double.NaN }
        return toolDiameter[tool]
    }

    private fun chosen(s: ScanState): Boolean =
        (objectNames.isEmpty() || (s.obj >= 0 && objects[s.obj] in objectNames)) && (featureNames.isEmpty() || (s.feature >= 0 && features[s.feature] in featureNames)) &&
            (toolIds.isEmpty() || s.tool in toolIds)

    private fun range(v: Double, lo: Double, hi: Double, set: (Double, Double) -> Unit) {
        if (v.isNaN()) return
        set(if (lo.isNaN() || v < lo) v else lo, if (hi.isNaN() || v > hi) v else hi)
    }

    override fun extrude(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, e: Double, s: ScanState, layer: LayerInfo, marked: Boolean,
                         diameters: List<Double>, layerHeight: Double) {
        if (!SolidDrawing.kept(req, window, x0, y0, z0, x1, y1, z1)) return
        val ch = chosen(s)
        if (!ch && req.others != Others.FAINT) return
        val a = if (marked) this.marked else inferred
        // a tool numbered beyond those kept here: the view reads the file again
        if (s.tool !in 0 until ScanState.MAX_TOOLS) { a.held = false; return }
        val l = layer.index
        a.counted.set(l)
        // as a move read later holds them: declared width and height and the rest as single precision
        val hDecl = s.height.toFloat().toDouble(); val wDecl = s.width.toFloat().toDouble()
        if (hDecl.isNaN() || hDecl <= 0) {
            val was = a.heightTaken.putIfAbsent(l, layerHeight)
            if (was != null && !(was == layerHeight || (was.isNaN() && layerHeight.isNaN()))) a.held = false
        }
        val dn = diameterNow(diameters, s.tool)
        val d: Double? = if (dn.isNaN()) null else dn
        if (a.took[s.tool]) { if (a.diameter[s.tool].toRawBits() != dn.toRawBits()) a.held = false } else { a.took[s.tool] = true; a.diameter[s.tool] = dn }
        val len = Math.hypot(x1 - x0, y1 - y0)
        val hRaw = Context.heightOf(hDecl, layerHeight)
        val bead = Context.beadWidthOf(e, len, d, hRaw, wDecl)
        val w = bead.takeIf { !it.isNaN() && it > 0 } ?: 0.4
        val h = hRaw.takeIf { !it.isNaN() && it > 0 } ?: 0.2
        for ((k, p) in projections.withIndex()) {
            val x = a.ext[k]
            for (end in 0..1) {
                val px = if (end == 0) x0 else x1; val py = if (end == 0) y0 else y1; val pz = if (end == 0) z0 else z1
                val pa = p.a(px, py, pz); val pb = p.b(px, py, pz)
                x[0] = minOf(x[0], pa - w); x[2] = maxOf(x[2], pa + w); x[1] = minOf(x[1], pb - w - h); x[3] = maxOf(x[3], pb + w)
            }
        }
        for (end in 0..1) { val pz = if (end == 0) z0 else z1; a.zLo = minOf(a.zLo, pz - h); a.zHi = maxOf(a.zHi, pz) }
        if (!ch) return
        a.chosen.set(l)
        val speed = (s.f / 60.0).toFloat().toDouble()
        when (colorBy) {
            ColorBy.SPEED -> range(speed, a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }
            ColorBy.FLOW -> range(if (len <= 1e-9) Double.NaN else if (d != null) e * Math.PI * d * d / 4 / len * speed else e / len * speed, a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }
            ColorBy.WIDTH -> { range(bead, a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }; range(wDecl, a.loDecl, a.hiDecl) { p, q -> a.loDecl = p; a.hiDecl = q } }
            ColorBy.HEIGHT -> range(hRaw, a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }
            ColorBy.FAN -> range(s.fan.toFloat() * 100.0, a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }
            ColorBy.TEMPERATURE -> range(s.temp.toFloat().toDouble(), a.lo, a.hi) { p, q -> a.lo = p; a.hi = q }
            ColorBy.FEATURE, ColorBy.TOOL, ColorBy.ORDER, ColorBy.LAYER_TIME -> {}
        }
        a.n++
        if (wDecl.isNaN() || w >= Context.TAPER_SHARE * wDecl) a.sp.add(Math.round(plymeld.program.Bead.spacing(w, h) * 1000).toInt(), len)
        a.hs.add(Math.round(h * 1000).toInt(), len)
    }

    /** The survey of view [p] for [ctx] (its file's index, its options), or null where what the sums were made on did not hold. */
    fun survey(ctx: Context, p: Projection): SolidDrawing.Survey? {
        val k = projections.indexOfFirst { it.same(p) }.takeIf { it >= 0 } ?: return null
        val index = ctx.index
        val a = if (index.layerMarkers) marked else inferred
        if (!a.held) return null
        var l = a.counted.nextSetBit(0)
        while (l >= 0) { if (l >= index.layers.size || !SolidDrawing.drawsFrom(req, index.layers[l])) return null; l = a.counted.nextSetBit(l + 1) }
        for ((layer, h) in a.heightTaken) { val f = index.layers[layer].height; if (!(f == h || (f.isNaN() && h.isNaN()))) return null }
        for (tool in a.took.indices) if (a.took[tool] && (ctx.diameter(tool) ?: Double.NaN).toRawBits() != a.diameter[tool].toRawBits()) return null
        var lo = a.lo; var hi = a.hi
        if (colorBy == ColorBy.WIDTH && ctx.lineWidth == LineWidth.DECLARED) { lo = a.loDecl; hi = a.hiDecl }
        if (colorBy == ColorBy.LAYER_TIME) {
            var c = a.chosen.nextSetBit(0)
            while (c >= 0) { range(index.layers.getOrNull(c)?.nominalS ?: Double.NaN, lo, hi) { p0, q0 -> lo = p0; hi = q0 }; c = a.chosen.nextSetBit(c + 1) }
        }
        val x = a.ext[k]
        return SolidDrawing.finish(x[0], x[1], x[2], x[3], lo, hi, a.n, a.sp, a.hs, a.zLo, a.zHi)
    }
}
