package plymeld.check.render

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/**
 * The bead routine of the whole-print views ([SolidDrawing.capsule]: spans along each row, tiles passed over, drawn in
 * either order, by bands of rows) marks the same pixels with the same values as the plain routine it replaced, which
 * tested every pixel of the bead's bounding box in print order (kept here as the reference): on random beads, among them
 * ones of no length, smaller than a pixel, off the picture, steep, shallow, and long, alone and many over each other,
 * with beads at exactly the same depth.
 */
class CapsuleSpanTest : StringSpec({
    class Bead(val pa: Double, val pb: Double, val qa: Double, val qb: Double, val da: Double, val db: Double, val rx: Double, val ry: Double, val rMm: Double, val rgb: Int)

    /** The routine as it was: every pixel of the bounding box tested, the nearest kept, the first drawn at the same depth. */
    fun reference(buf: SolidDrawing.Buffer, b: Bead) {
        val x0 = maxOf(0, Math.floor(minOf(b.pa, b.qa) - b.rx).toInt()); val x1 = minOf(buf.w - 1, Math.ceil(maxOf(b.pa, b.qa) + b.rx).toInt())
        val y0 = maxOf(0, Math.floor(minOf(b.pb, b.qb) - b.ry).toInt()); val y1 = minOf(buf.h - 1, Math.ceil(maxOf(b.pb, b.qb) + b.ry).toInt())
        if (x0 > x1 || y0 > y1) return
        val sc = b.rx / b.ry
        val ex = b.qa - b.pa; val ey = (b.qb - b.pb) * sc
        val l2 = ex * ex + ey * ey
        for (y in y0..y1) for (x in x0..x1) {
            val cx = x + 0.5 - b.pa; val cy = (y + 0.5 - b.pb) * sc
            val t = if (l2 == 0.0) 0.0 else ((cx * ex + cy * ey) / l2).coerceIn(0.0, 1.0)
            val ddx = cx - t * ex; val ddy = cy - t * ey
            val d2 = ddx * ddx + ddy * ddy
            if (d2 > b.rx * b.rx) continue
            val bulge = Math.sqrt(1 - d2 / (b.rx * b.rx))
            val dep = (b.da + (b.db - b.da) * t - bulge * b.rMm).toFloat()
            val at = y * buf.w + x
            if (dep < buf.depth[at]) {
                buf.depth[at] = dep; buf.color[at] = b.rgb; buf.key[at] = b.rgb.toShort()
                buf.shade[at] = Math.round(bulge * 255).toInt().toByte()
            }
        }
    }

    val rnd = java.util.Random(20261119)
    fun pick(vararg v: Double) = v[rnd.nextInt(v.size)]
    fun bead(k: Int, w: Int, h: Int): Bead {
        val pa = -20 + rnd.nextDouble() * (w + 40); val pb = -20 + rnd.nextDouble() * (h + 40)
        // the move's length and slope: none, under a pixel, steep, shallow, along an axis, long
        val (dx, dy) = when (rnd.nextInt(7)) {
            0 -> 0.0 to 0.0
            1 -> (rnd.nextDouble() - 0.5) to (rnd.nextDouble() - 0.5)
            2 -> (rnd.nextDouble() - 0.5) * 2 to (rnd.nextDouble() - 0.5) * 120
            3 -> (rnd.nextDouble() - 0.5) * 120 to (rnd.nextDouble() - 0.5) * 2
            4 -> (rnd.nextDouble() - 0.5) * 80 to 0.0
            5 -> 0.0 to (rnd.nextDouble() - 0.5) * 80
            else -> (rnd.nextDouble() - 0.5) * 300 to (rnd.nextDouble() - 0.5) * 300
        }
        val rx = pick(0.5, 0.5, 0.7, 1.3, 2.0, 4.5, 11.0) + rnd.nextDouble() * 0.01
        val ry = if (rnd.nextBoolean()) rx else pick(0.5, 0.9, 2.5, 7.0) + rnd.nextDouble() * 0.01
        // depths from a few values, so beads meet at exactly the same depth
        val da = pick(-2.0, -1.0, 0.0, 1.0); val db = if (rnd.nextBoolean()) da else da + pick(-0.5, 0.25, 3.0)
        return Bead(pa, pb, pa + dx, pb + dy, da, db, rx, ry, pick(0.1, 0.2, 0.45), 0x101010 * (k % 15 + 1))
    }
    fun same(a: SolidDrawing.Buffer, b: SolidDrawing.Buffer): Boolean =
        a.depth.contentEquals(b.depth) && a.color.contentEquals(b.color) && a.key.contentEquals(b.key) && a.shade.contentEquals(b.shade)
    fun drawn(buf: SolidDrawing.Buffer, b: Bead, laterFirst: Boolean, cull: Boolean, rowLo: Int = 0, rowHi: Int = buf.h - 1) =
        SolidDrawing.capsule(buf, b.pa, b.pb, b.qa, b.qb, b.da, b.db, b.rx, b.ry, b.rMm, b.rgb, b.rgb.toShort().toInt(), laterFirst, cull, rowLo, rowHi)

    "one bead: the same pixels and values as the bounding-box routine" {
        var marked = 0
        repeat(4000) { k ->
            val w = 37 + rnd.nextInt(90); val h = 29 + rnd.nextInt(90)
            val b = bead(k, w, h)
            val ref = SolidDrawing.Buffer(w, h).also { reference(it, b) }
            val got = SolidDrawing.Buffer(w, h).also { drawn(it, b, false, false) }
            same(ref, got) shouldBe true
            marked += ref.depth.count { it != Float.MAX_VALUE }
        }
        // a check that can fail: the beads mark pixels, and a bead a little narrower marks others
        (marked > 100_000) shouldBe true
        val b = Bead(10.3, 12.7, 60.1, 40.2, 0.0, 1.0, 4.5, 3.0, 0.2, 0x404040)
        val narrower = Bead(b.pa, b.pb, b.qa, b.qb, b.da, b.db, b.rx * 0.97, b.ry, b.rMm, b.rgb)
        same(SolidDrawing.Buffer(80, 60).also { reference(it, b) }, SolidDrawing.Buffer(80, 60).also { drawn(it, narrower, false, false) }) shouldNotBe true
    }

    "many beads: the same picture in print order, in reverse, with tiles passed over, and by bands" {
        repeat(300) {
            val w = 40 + rnd.nextInt(100); val h = 33 + rnd.nextInt(100)
            val beads = ArrayList<Bead>()
            repeat(30 + rnd.nextInt(40)) { k -> val b = bead(k, w, h); beads += b; if (rnd.nextInt(5) == 0) beads += Bead(b.pa, b.pb, b.qa, b.qb, b.da, b.db, b.rx, b.ry, b.rMm, b.rgb + 0x050505) }
            val ref = SolidDrawing.Buffer(w, h).also { r -> for (b in beads) reference(r, b) }
            for (cull in listOf(false, true)) {
                same(ref, SolidDrawing.Buffer(w, h).also { g -> for (b in beads) drawn(g, b, false, cull) }) shouldBe true
                same(ref, SolidDrawing.Buffer(w, h).also { g -> for (b in beads.asReversed()) drawn(g, b, true, cull) }) shouldBe true
                same(ref, SolidDrawing.Buffer(w, h).also { g ->
                    for (y0 in 0 until h step SolidDrawing.BAND) for (b in beads.asReversed()) drawn(g, b, true, cull, y0, minOf(h, y0 + SolidDrawing.BAND) - 1)
                }) shouldBe true
            }
            // control: in reverse without the rule for beads at the same depth, a different picture where two meet
            if (beads.size > 1) {
                val tied = Bead(20.0, 20.0, 30.0, 20.0, 0.0, 0.0, 3.0, 3.0, 0.2, 0x111111)
                val other = Bead(20.0, 20.0, 30.0, 20.0, 0.0, 0.0, 3.0, 3.0, 0.2, 0x222222)
                val r2 = SolidDrawing.Buffer(w, h).also { reference(it, tied); reference(it, other) }
                same(r2, SolidDrawing.Buffer(w, h).also { drawn(it, other, false, false); drawn(it, tied, false, false) }) shouldBe false
                same(r2, SolidDrawing.Buffer(w, h).also { drawn(it, other, true, false); drawn(it, tied, true, false) }) shouldBe true
            }
        }
    }
})
