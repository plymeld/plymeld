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

    companion object {
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
 */
internal class SolidDrawing(private val ctx: Context, private val p: Projection) {
    private val req = ctx.req
    /** Layers read at a time (plymeld's choice: a few megabytes of moves). */
    private val chunk = 64

    class Survey(var a0: Double, var b0: Double, var a1: Double, var b1: Double, var lo: Double, var hi: Double, var count: Int,
                 val spacing: Double, val hMin: Double, val zLo: Double, val zHi: Double)

    private fun layersToRead(): List<Int> {
        val zr = req.zRange
        return ctx.index.layers.filter { l -> !l.minZ.isNaN() && (zr == null || (l.maxZ >= zr.start && l.minZ - maxOf(l.height, 0.0) <= zr.endInclusive)) }.map { it.index }
    }
    private val window: DoubleArray? = req.bedWindow ?: req.around?.let { (x, y) -> val s = req.span!! / 2; doubleArrayOf(x - s, y - s, x + s, y + s) }
    private fun kept(m: MoveSet, i: Int): Boolean {
        val zr = req.zRange
        if (zr != null && (maxOf(m.z0[i], m.z1[i]) < zr.start || minOf(m.z0[i], m.z1[i]) > zr.endInclusive)) return false
        val w = window ?: return true
        val mx = (m.x0[i] + m.x1[i]) / 2; val my = (m.y0[i] + m.y1[i]) / 2
        return mx >= w[0] && mx <= w[2] && my >= w[1] && my <= w[3]
    }
    /** Every kept extruding move in print order, or ([reverse]) in exactly the reverse of print order. */
    private fun each(reverse: Boolean = false, f: (MoveSet, Int) -> Unit) {
        val ls = layersToRead()
        val starts = (ls.indices step chunk).toList()
        for (k in if (reverse) starts.asReversed() else starts) {
            val (m, _) = MoveReader.read(ctx.index, ls.subList(k, minOf(ls.size, k + chunk)))
            if (reverse) { for (i in m.n - 1 downTo 0) if (m.kind[i] == MoveSet.EXTRUDE && kept(m, i)) f(m, i) }
            else for (i in 0 until m.n) if (m.kind[i] == MoveSet.EXTRUDE && kept(m, i)) f(m, i)
        }
    }

    /** The first pass: the extent drawn, the gradient's range, and the closest lines (spacing and thinnest layer). */
    fun survey(): Survey {
        var a0 = Double.MAX_VALUE; var b0 = Double.MAX_VALUE; var a1 = -Double.MAX_VALUE; var b1 = -Double.MAX_VALUE
        var lo = Double.NaN; var hi = Double.NaN; var n = 0
        var zLo = Double.MAX_VALUE; var zHi = -Double.MAX_VALUE
        // spacing and height, length-weighted, in 1-micron bins
        val sp = java.util.TreeMap<Int, Double>(); val hs = java.util.TreeMap<Int, Double>()
        each { m, i ->
            val ch = ctx.chosen(m, i)
            if (!ch && req.others != Others.FAINT) return@each
            val w = ctx.beadWidth(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.4
            val h = ctx.heightOf(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.2
            for ((x, y, z) in listOf(Triple(m.x0[i], m.y0[i], m.z0[i]), Triple(m.x1[i], m.y1[i], m.z1[i]))) {
                val a = p.a(x, y, z); val b = p.b(x, y, z)
                a0 = minOf(a0, a - w); a1 = maxOf(a1, a + w); b0 = minOf(b0, b - w - h); b1 = maxOf(b1, b + w)
                zLo = minOf(zLo, z - h); zHi = maxOf(zHi, z)
            }
            if (!ch) return@each
            if (ctx.colorBy != ColorBy.FEATURE && ctx.colorBy != ColorBy.TOOL && ctx.colorBy != ColorBy.ORDER) {
                val v = ctx.value(m, i, 0); if (!v.isNaN()) { if (lo.isNaN() || v < lo) lo = v; if (hi.isNaN() || v > hi) hi = v }
            }
            n++
            val len = m.lengthXY(i)
            val decl = m.wDecl[i].toDouble()
            if (decl.isNaN() || w >= Context.TAPER_SHARE * decl) sp.merge(Math.round(plymeld.program.Bead.spacing(w, h) * 1000).toInt(), len, Double::plus)
            hs.merge(Math.round(h * 1000).toInt(), len, Double::plus)
        }
        fun low(t: java.util.TreeMap<Int, Double>): Double {
            val total = t.values.sum(); if (total <= 0) return Double.NaN
            var acc = 0.0
            for ((k, v) in t) { acc += v; if (acc > Context.THIN_SHARE * total) return k / 1000.0 }
            return t.lastKey() / 1000.0
        }
        if (a0 > a1) { a0 = 0.0; a1 = 10.0; b0 = 0.0; b1 = 10.0; zLo = 0.0; zHi = 1.0 }
        return Survey(a0, b0, a1, b1, lo, hi, n, low(sp), low(hs), zLo, zHi)
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
        // the move's place among the chosen moves in print order (counted down when drawing in reverse)
        var order = if (reverse) sv.count - 1 else 0
        val total = maxOf(1, sv.count - 1)
        each(reverse) { m, i ->
            val ch = ctx.chosen(m, i)
            if (!ch && req.others != Others.FAINT) return@each
            val col: Color; val keyId: Int
            if (!ch) { col = Palette.CONTEXT; keyId = -1 } else {
                col = when (ctx.colorBy) {
                    ColorBy.FEATURE -> if (ctx.index.namesFeatures) ctx.featureColor(m.feature[i]) else Palette.UNNAMED
                    ColorBy.TOOL -> ctx.toolColor(m.tool[i])
                    ColorBy.ORDER -> Palette.ramp(order.toDouble() / total)
                    else -> { val v = ctx.value(m, i, 0); if (v.isNaN()) Palette.UNNAMED else Palette.ramp(if (sv.hi > sv.lo) (v - sv.lo) / (sv.hi - sv.lo) else 0.5) }
                }
                keyId = when (ctx.colorBy) { ColorBy.FEATURE -> m.feature[i]; ColorBy.TOOL -> m.tool[i]; else -> 0 }
                if (reverse) order-- else order++
            }
            val w = ctx.beadWidth(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.4
            val h = ctx.heightOf(m, i).takeIf { !it.isNaN() && it > 0 } ?: 0.2
            // the bead's center line, half its height under the nozzle
            val ax = m.x0[i]; val ay = m.y0[i]; val az = m.z0[i] - h / 2; val bx = m.x1[i]; val by = m.y1[i]; val bz = m.z1[i] - h / 2
            val pa = (p.a(ax, ay, az) - a0) * s; val pb = (b1 - p.b(ax, ay, az) * kOf(k)) * s
            val qa = (p.a(bx, by, bz) - a0) * s; val qb = (b1 - p.b(bx, by, bz) * kOf(k)) * s
            val da = p.depth(ax, ay, az); val db = p.depth(bx, by, bz)
            // the brush: half its width across, half its height up (side on), stretched as the heights are
            val rxp = maxOf(0.5, w / 2 * s)
            val ryp = maxOf(0.5, (if (p.sideView) h / 2 * k else h / 2 * Math.abs(p.uz) + w / 2 * Math.sqrt(maxOf(0.0, 1 - p.uz * p.uz))) * s)
            capsule(buf, pa, pb, qa, qb, da, db, rxp, ryp, w / 2, col.rgb, keyId, reverse, cull)
        }
        return buf
    }
    private fun kOf(k: Double) = if (p.sideView) k else 1.0

    companion object {
        /** Neighboring pixels further apart in depth than this (mm) are one surface in front of another: an outline (plymeld's choice). */
        const val DEPTH_STEP_MM = 1.0f
        /** The side of a tile of the depth map, pixels (plymeld's choice: a few hundred pixels read to work out its farthest depth). */
        const val TILE = 16

        /**
         * One bead: the ellipse [rx] x [ry] pixels swept from (pa, pb) to (qa, qb), its middle line at depths [da] to [db]
         * (mm), bulging [rMm] toward the viewer at its middle. A pixel takes it where it is nearer than what the pixel
         * shows; [laterFirst]: the moves are drawn in the reverse of print order, so at exactly the same depth it is the
         * one printed first and takes the pixel too. [cull]: tiles whose farthest depth is nearer than the bead can come
         * there are passed over.
         */
        internal fun capsule(buf: Buffer, pa: Double, pb: Double, qa: Double, qb: Double, da: Double, db: Double, rx: Double, ry: Double, rMm: Double, rgb: Int, keyId: Int,
                             laterFirst: Boolean, cull: Boolean) {
            val x0 = maxOf(0, Math.floor(minOf(pa, qa) - rx).toInt()); val x1 = minOf(buf.w - 1, Math.ceil(maxOf(pa, qa) + rx).toInt())
            val y0 = maxOf(0, Math.floor(minOf(pb, qb) - ry).toInt()); val y1 = minOf(buf.h - 1, Math.ceil(maxOf(pb, qb) + ry).toInt())
            if (x0 > x1 || y0 > y1) return
            // in the brush's own frame the ellipse is a circle of radius rx: y scaled by rx / ry
            val sc = rx / ry
            val ex = qa - pa; val ey = (qb - pb) * sc
            val l2 = ex * ex + ey * ey
            for (ty in y0 / TILE..y1 / TILE) for (tx in x0 / TILE..x1 / TILE) {
                val t = ty * buf.tw + tx
                val ya = maxOf(y0, ty * TILE); val yb = minOf(y1, ty * TILE + TILE - 1)
                val xa = maxOf(x0, tx * TILE); val xb = minOf(x1, tx * TILE + TILE - 1)
                if (cull && buf.tileEmpty[t] == 0) {
                    val near = nearest(xa, xb, ya, yb, pa, pb, sc, ex, ey, l2, da, db, rMm)
                    // the farthest depth as last worked out first: it is never nearer than the true one
                    if (near > buf.tileFar[t] || near > buf.far(t)) continue
                }
                var wrote = false
                for (y in ya..yb) for (x in xa..xb) {
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
                lo = Double.MAX_VALUE; hi = -Double.MAX_VALUE
                for (cx in doubleArrayOf(xa + 0.5 - pa, xb + 0.5 - pa)) for (cy in doubleArrayOf((ya + 0.5 - pb) * sc, (yb + 0.5 - pb) * sc)) {
                    val t = (cx * ex + cy * ey) / l2
                    lo = minOf(lo, t); hi = maxOf(hi, t)
                }
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
