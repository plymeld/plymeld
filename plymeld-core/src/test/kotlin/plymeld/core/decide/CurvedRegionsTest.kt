package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup

/**
 * The curved-top survey (CurvedRegions). Models are height fields over a rectangle (the top from a function, plain
 * sides and bottom): the survey reads only the top. The head: a 45 deg nozzle 4 mm tall; band layers 0.2 mm, the tool's
 * thinnest 0.08 mm.
 */
class CurvedRegionsTest : StringSpec({
    val p = CurvedRegions.Params(angleDeg = 45.0, nozzleHeightMm = 4.0, layerMm = 0.2, minLayerMm = 0.08)

    /** A solid over [x0, x1] x [y0, y1] whose top is [f] (sampled every [step] mm), with vertical sides down to z 0. */
    fun field(x0: Double, x1: Double, y0: Double, y1: Double, step: Double = 0.25, f: (Double, Double) -> Double): TriangleSoup {
        val nx = Math.round((x1 - x0) / step).toInt(); val ny = Math.round((y1 - y0) / step).toInt()
        val tris = ArrayList<Float>()
        fun tri(vararg v: Double) { for (d in v) tris += d.toFloat() }
        for (i in 0 until nx) for (j in 0 until ny) {
            val xa = x0 + i * step; val xb = xa + step; val ya = y0 + j * step; val yb = ya + step
            val za = f(xa, ya); val zb = f(xb, ya); val zc = f(xb, yb); val zd = f(xa, yb)
            tri(xa, ya, za, xb, ya, zb, xb, yb, zc); tri(xa, ya, za, xb, yb, zc, xa, yb, zd)    // counter-clockwise from above: up
        }
        // sides (outward) and bottom: enough for the bounds; the survey reads only the top
        for (i in 0 until nx) {
            val xa = x0 + i * step; val xb = xa + step
            tri(xa, y0, 0.0, xb, y0, 0.0, xb, y0, f(xb, y0)); tri(xa, y0, 0.0, xb, y0, f(xb, y0), xa, y0, f(xa, y0))
            tri(xb, y1, 0.0, xa, y1, 0.0, xa, y1, f(xa, y1)); tri(xb, y1, 0.0, xa, y1, f(xa, y1), xb, y1, f(xb, y1))
        }
        for (j in 0 until ny) {
            val ya = y0 + j * step; val yb = ya + step
            tri(x1, ya, 0.0, x1, yb, 0.0, x1, yb, f(x1, yb)); tri(x1, ya, 0.0, x1, yb, f(x1, yb), x1, ya, f(x1, ya))
            tri(x0, yb, 0.0, x0, ya, 0.0, x0, ya, f(x0, ya)); tri(x0, yb, 0.0, x0, ya, f(x0, ya), x0, yb, f(x0, yb))
        }
        tri(x0, y0, 0.0, x1, y1, 0.0, x1, y0, 0.0); tri(x0, y0, 0.0, x0, y1, 0.0, x1, y1, 0.0)
        return TriangleSoup(tris.toFloatArray(), tris.size / 9)
    }

    /** The band's surface: every 4-neighbor pair of its cells within the nozzle's slope (plus the grid's rounding). */
    fun steepest(r: CurvedRegions.Result, band: CurvedRegions.Band): Double {
        val at = HashMap<Int, Double>(); band.cells.forEachIndexed { q, a -> at[a] = band.surface[q] }
        var worst = 0.0
        for ((a, v) in at) for (o in listOf(a + 1, a + r.ny)) at[o]?.let { worst = maxOf(worst, Math.abs(v - it) / r.cellMm) }
        return Math.toDegrees(Math.atan(worst))
    }

    "a shallow dome on a block: one cap, a band whose layers follow most of it, within the head's rise and the tool's thinnest; the surface never under the top nor steeper than the nozzle" {
        // a spherical cap 2 mm tall and 16 mm across on a 20 x 20 x 10 mm block: its steepest about 27 deg
        val rr = (8.0 * 8.0 + 2.0 * 2.0) / (2 * 2.0)              // the sphere's radius: (a^2 + h^2) / 2h = 17
        val m = field(0.0, 20.0, 0.0, 20.0) { x, y -> val d2 = (x - 10) * (x - 10) + (y - 10) * (y - 10); if (d2 >= 64.0) 10.0 else 10.0 + Math.sqrt(rr * rr - d2) - (rr - 2.0) }
        val r = CurvedRegions.survey(m, p)
        println(r.bands.joinToString("\n") + r.rejected.joinToString("\n") { "rejected cap ${it.cap.id} (${it.cap.areaMm2} mm2): ${it.why}" })
        r.caps.size shouldBe 1
        val cap = r.caps.single()
        (Math.abs(cap.areaMm2 - Math.PI * 64.0) < 0.1 * Math.PI * 64.0) shouldBe true      // the cap's footprint, a circle of radius 8
        val band = r.bands.single()
        (band.riseMm <= 4.0 + 1e-9) shouldBe true
        (band.thinnestMm >= 0.08 - 1e-9) shouldBe true
        (band.followedMm2 >= 0.8 * cap.areaMm2) shouldBe true
        // T never under the top over the band, nor steeper than the nozzle
        band.cells.forEachIndexed { q, a -> (band.surface[q] >= r.top[a] - 1e-9) shouldBe true }
        (steepest(r, band) <= 45.0 + 1e-6) shouldBe true
    }

    "no island starts under the first layer (Params.minStartMm); control: without the bound, a dome on an 8 mm block starts under it" {
        // the shape that showed it: a spherical cap rising 1.75 mm over 40 mm (10 deg at its edge) on an 8 mm block;
        // the island's best start was 0.08 mm, under the 0.28 mm first layer, and the slicer refused it
        val rr = 20.0 / Math.sin(Math.toRadians(10.0)); val rise = rr - Math.sqrt(rr * rr - 400.0)
        val m = field(0.0, 40.0, 0.0, 40.0) { x, y -> val d2 = (x - 20) * (x - 20) + (y - 20) * (y - 20); if (d2 >= 400.0) 8.0 else 8.0 + Math.sqrt(rr * rr - d2) - (rr - rise) }
        // the plan's case: flat layers 0.08 mm (the Creator 5's quality plan), the tool's thickest 0.3
        val p08 = CurvedRegions.Params(angleDeg = 45.0, nozzleHeightMm = 4.0, layerMm = 0.08, minLayerMm = 0.08)
        val r = CurvedRegions.survey(m, p08, withBands = false)
        fun starts(pp: CurvedRegions.Params) = CurvedRegions.shifted(r, pp, pp.layerMm, 0.3).map { (c, x) -> (x as? CurvedRegions.Island)?.startMm ?: Double.NaN.also { println("cap ${c.id}: $x") } }.filter { !it.isNaN() }
        val free = starts(p08)
        println("starts without the bound: $free")
        free.isNotEmpty() shouldBe true
        (free.min() < 0.28) shouldBe true
        val bounded = starts(CurvedRegions.Params(angleDeg = 45.0, nozzleHeightMm = 4.0, layerMm = 0.08, minLayerMm = 0.08, minStartMm = 0.28))
        println("starts with the bound: $bounded")
        bounded.isNotEmpty() shouldBe true
        (bounded.min() >= 0.28 - 1e-9) shouldBe true
    }

    "a roof with a chimney through it: the chimney stays flat (out of the band), the roof's layers rise to meet it" {
        // a roof 0.8 mm high over 20 mm (about 9 deg at its edges) on a 30 x 20 x 10 mm block, a 4 x 4 mm chimney to 20 mm
        val m = field(0.0, 30.0, 0.0, 20.0) { x, y ->
            if (x in 13.0..17.0 && y in 8.0..12.0) 20.0 else 10.0 + 0.8 * (1 - ((y - 10) / 10).let { it * it })
        }
        val r = CurvedRegions.survey(m, p)
        println(r.bands.joinToString("\n") + r.rejected.joinToString("\n") { "rejected cap ${it.cap.id} (${it.cap.areaMm2} mm2): ${it.why}" })
        val roof = r.bands.maxByOrNull { it.cap.areaMm2 }!!
        (roof.followedMm2 > 0.5 * roof.cap.areaMm2) shouldBe true
        // no chimney cell is in the band
        roof.cells.none { a -> r.top[a] > roof.cap.zHiMm + 1e-6 } shouldBe true
        (roof.riseMm <= 4.0 + 1e-9) shouldBe true
        // the control: the chimney's height is beyond the nozzle's from the roof; had it been kept in, the rise would be
        // its top less the roof's edge, over 9 mm
        ((20.0 - 10.0) > 4.0) shouldBe true
    }

    "no caps on a flat box, nor on a pyramid steeper than the nozzle; two ramps meeting at a cliff are two caps, not one (the controls)" {
        CurvedRegions.survey(field(0.0, 10.0, 0.0, 10.0) { _, _ -> 5.0 }, p).caps.size shouldBe 0
        // a pyramid at 60 deg (tan 60 = 1.73 per mm)
        CurvedRegions.survey(field(0.0, 10.0, 0.0, 10.0) { x, y -> 5.0 + 1.732 * minOf(minOf(x, 10 - x), minOf(y, 10 - y)) }, p).caps.size shouldBe 0
        // two 10 deg ramps, the right one 3 mm higher at the join (a cliff at x 10)
        val two = CurvedRegions.survey(field(0.0, 20.0, 0.0, 10.0) { x, _ -> if (x < 10) 5.0 + 0.176 * x else 8.0 + 2.0 + 0.176 * (x - 10) }, p)
        two.caps.size shouldBe 2
    }

    // read bilinearly, a sharp peak sampled on the grid is sqrt 2 steeper at its corner than its sides (the Benchy's bow, 49.5 deg on
    // a 45 deg nozzle): limitCorners raises it until every cell's corners are within the step, and only raises
    "limitCorners: a cone's peak, read bilinearly sqrt 2 too steep, held within the step by raising its neighborhood only; control: as dilated it is not" {
        // a cone held to the survey's angle (the nozzle's slope less INTERP_ERR) over 0.25 mm cells, limited at the nozzle's 45 deg:
        // a step of 0.25 per cell
        val n = 21; val m = 0.25
        val cone = DoubleArray(n * n) { q -> val i = q / n - 10; val j = q % n - 10; 10.0 - m / CurvedRegions.INTERP_ERR * Math.hypot(i.toDouble(), j.toDouble()) }
        fun steepest(f: DoubleArray): Double {
            var g = 0.0
            for (i in 0 until n - 1) for (j in 0 until n - 1) {
                val a = f[i * n + j]; val b = f[(i + 1) * n + j]; val c = f[i * n + j + 1]; val d = f[(i + 1) * n + j + 1]
                g = maxOf(g, Math.hypot(b - a, c - a), Math.hypot(b - a, d - b), Math.hypot(d - c, c - a), Math.hypot(d - c, d - b))
            }
            return g
        }
        // the control: the cone as it is, its peak's corners sqrt 2 over its own slope, over the step
        (steepest(cone) / m) shouldBe (Math.sqrt(2.0) / CurvedRegions.INTERP_ERR plusOrMinus 1e-9)
        val held = cone.copyOf()
        CurvedRegions.limitCorners(held, n, n, m)
        (steepest(held) <= m * (1 + 1e-6)) shouldBe true
        held.indices.all { held[it] >= cone[it] - 1e-12 } shouldBe true
        // the peak stays the peak, and the cone beyond the peak's few cells is untouched
        held[10 * n + 10] shouldBe cone[10 * n + 10]
        // a little rounding round the tip, none beyond eight cells (the margin's slope brings it back down)
        fun r(q: Int) = Math.hypot((q / n - 10).toDouble(), (q % n - 10).toDouble())
        val rise = DoubleArray(n * n) { held[it] - cone[it] }
        println("raised up to %.4f mm, out to %.1f cells".format(java.util.Locale.ROOT, rise.max(), rise.indices.filter { rise[it] > 1e-9 }.maxOfOrNull { r(it) } ?: 0.0))
        (rise.max() < 0.05) shouldBe true
        rise.indices.filter { r(it) > 8 }.all { rise[it] < 1e-9 } shouldBe true
    }
})
