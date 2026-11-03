package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.mesh.Warp

/**
 * Paths back from a curved band's warped space, on an analytic top:
 * a dome z = 8 + (1 - (r/20)^2) over a 40 mm disc (9 at the center, 8 at r = 20), band from 0.28 to 9. A
 * warped layer's top zTopW maps at (x, y) to 0.28 + (zTopW - 0.28) (top - 0.28) / 8.72, its thickness to the
 * warped thickness times (top - 0.28) / 8.72 (worked by hand below).
 */
class CurvedLayersTest : StringSpec({
    fun top(x: Double, y: Double): Double { val r2 = (x * x + y * y) / 400.0; return if (r2 > 1.0) Double.NaN else 8.0 + (1.0 - r2) }
    val warp = Warp(0.28, 9.0, ::top)

    "a straight path across the dome is split where it bends, each vertex at the layer surface's real Z" {
        // the warped band's last layer (8.8 .. 9.0): at the center real Z 9.0, at x = +-18 Z 8 + (1 - 0.81) = 8.19
        val u = CurvedLayers.unwarp(longArrayOf(-18_000, 0, 18_000, 0), false, null, null, warp, 8.8, 9.0, 0.01)
        (u.xy.size / 2) shouldBeGreaterThan 8
        val n = u.xy.size / 2
        for (i in 0 until n) {
            val x = u.xy[2 * i] / 1000.0
            u.zs[i] / 1000.0 shouldBe (top(x, 0.0) plusOrMinus 0.0015)   // this layer's top IS the model's top (rounded to µm)
        }
        u.zs.first() / 1000.0 shouldBe (8.19 plusOrMinus 0.002)
        // thickness at the ends: 0.2 x (8.19 - 0.28) / 8.72 = 0.1814 mm; the middle vertex (x = 0): 0.2
        u.heights.first() / 1000.0 shouldBe (0.1814 plusOrMinus 0.002)
        u.heights[n / 2] / 1000.0 shouldBe (0.2 plusOrMinus 0.002)
    }
    "the split keeps the curve within the tolerance: every segment's middle within 0.01 mm of the surface" {
        val u = CurvedLayers.unwarp(longArrayOf(-18_000, -5_000, 17_000, 6_000), false, null, null, warp, 8.8, 9.0, 0.01)
        val n = u.xy.size / 2
        for (i in 0 until n - 1) {
            val mx = (u.xy[2 * i] + u.xy[2 * i + 2]) / 2000.0; val my = (u.xy[2 * i + 1] + u.xy[2 * i + 3]) / 2000.0
            val chord = (u.zs[i] + u.zs[i + 1]) / 2000.0
            Math.abs(top(mx, my) - chord) shouldBe (0.0 plusOrMinus 0.0115)
        }
    }
    "control: with a tolerance coarser than the dome's whole bend nothing is split" {
        val u = CurvedLayers.unwarp(longArrayOf(-18_000, 0, 18_000, 0), false, null, null, warp, 8.8, 9.0, 5.0)
        (u.xy.size / 2) shouldBe 2
    }
    "a closed loop keeps its vertex count consistent: no doubled closing vertex; overhang shares follow their edges" {
        val sq = longArrayOf(-10_000, -10_000, 10_000, -10_000, 10_000, 10_000, -10_000, 10_000)
        val u = CurvedLayers.unwarp(sq, true, null, intArrayOf(100, 200, 300, 400), warp, 8.8, 9.0, 0.01)
        val n = u.xy.size / 2
        (u.xy[0] == u.xy[2 * (n - 1)] && u.xy[1] == u.xy[2 * (n - 1) + 1]) shouldBe false
        val shares = u.overhangShare!!
        shares.size shouldBe n
        shares.first() shouldBe 100
        shares.last() shouldBe 400
    }
    "below the band nothing changes: a layer under its start keeps its flat Z and thickness" {
        val u = CurvedLayers.unwarp(longArrayOf(-18_000, 0, 18_000, 0), false, null, null, warp, 0.08, 0.28, 0.01)
        u.zs.forEach { it shouldBe 280L }
        u.heights.forEach { it shouldBe 200L }
    }
    // A lifted bridge's underside (a ramp, centered on x = 0): flat at 6.28 to x = -20, rising straight to
    // 12.28 at x = 20, flat after; 10 mm wide. Lifted flat to 12.28, its first layer (12.28 .. 12.48 warped) comes back
    // 0.2 mm above the underside: z = under + 0.2 at every x.
    fun under(x: Double, y: Double) = if (Math.abs(y) > 5) Double.NaN else if (x < -20) 6.28 else if (x > 20) 12.28 else 6.28 + 6 * (x + 20) / 40
    val lift = plymeld.mesh.LiftWarp(12.28, ::under)
    /** The furthest any printed segment of [xy] / [zs] strays from under + 0.2, sampled every 0.05 mm along it (mm). */
    fun worstAlong(xy: LongArray, zs: LongArray): Double {
        var worst = 0.0
        for (i in 0 until xy.size / 2 - 1) {
            val ax = xy[2 * i] / 1000.0; val bx = xy[2 * i + 2] / 1000.0; val az = zs[i] / 1000.0; val bz = zs[i + 1] / 1000.0
            val n = maxOf(1, Math.ceil(Math.abs(bx - ax) / 0.05).toInt())
            for (k in 0..n) { val t = k.toDouble() / n; worst = maxOf(worst, Math.abs(az + (bz - az) * t - (under(ax + (bx - ax) * t, 0.0) + 0.2))) }
        }
        return worst
    }
    "a line across a bridge whose rise is symmetric about its middle is split at both kinks and follows it all along" {
        val u = CurvedLayers.unwarp(longArrayOf(-28_000, 0, 28_000, 0), false, null, null, lift, 12.28, 12.48, 0.01)
        // the middle (x = 0) is exactly on the straight line from end to end: judging by it alone left this unsplit
        val xs = (0 until u.xy.size / 2).map { u.xy[2 * it] / 1000.0 }
        (xs.minOf { Math.abs(it + 20) } <= 0.25) shouldBe true
        (xs.minOf { Math.abs(it - 20) } <= 0.25) shouldBe true
        worstAlong(u.xy, u.zs) shouldBe (0.0 plusOrMinus 0.0115)        // the tolerance, plus µm rounding
        // control: the same measure on the unsplit line (end to end, as printed) finds it: at x = 20 the chord
        // is at 6.48 + 6 x 48/56 = 11.623, the surface at 12.48, 0.857 under it
        worstAlong(longArrayOf(-28_000, 0, 28_000, 0), longArrayOf(u.zs.first(), u.zs.last())) shouldBe (0.857 plusOrMinus 0.002)
    }
    // A top with a step (a 12 mm strip on a 15 mm foot, the strip's edge rising 0.2 mm from the foot's top): 9.2 on
    // the strip (x under 0.0007 mm), 9.0 on the foot. The step sits just under a µm, where a split before it rounds onto it.
    fun stepped(x: Double, y: Double) = if (Math.abs(y) > 5 || Math.abs(x) > 10) Double.NaN else if (x < 0.0007) 9.2 else 9.0
    val stepWarp = Warp(0.28, 9.2, ::stepped)
    /** The furthest any printed segment (one with an XY length) strays from the stepped top, sampled every 0.01 mm (mm). */
    fun strayFromStep(xy: LongArray, zs: LongArray): Double {
        var worst = 0.0
        for (i in 0 until xy.size / 2 - 1) {
            val ax = xy[2 * i] / 1000.0; val bx = xy[2 * i + 2] / 1000.0; val az = zs[i] / 1000.0; val bz = zs[i + 1] / 1000.0
            if (Math.abs(bx - ax) < 0.0015) continue                       // a move straight up the step
            val n = maxOf(1, Math.ceil(Math.abs(bx - ax) / 0.01).toInt())
            for (k in 1 until n) { val t = k.toDouble() / n; worst = maxOf(worst, Math.abs(az + (bz - az) * t - stepped(ax + (bx - ax) * t, 0.0))) }
        }
        return worst
    }
    "a top with a step: the line runs flat to the step and goes straight up it, never ramping over the foot before it" {
        // the band's last layer is the model's top: a wall from the foot (x = 2) across the strip's edge (x = 0.0007) to x = -2
        val u = CurvedLayers.unwarp(longArrayOf(2_000, 0, -2_000, 0), false, null, null, stepWarp, 9.0, 9.2, 0.01)
        strayFromStep(u.xy, u.zs) shouldBe (0.0 plusOrMinus 0.0115)
        // control: the same split with each vertex read where its µm-rounded position lands (as curved layers did before):
        // the split point before the step rounds onto it, takes the strip's 9.2, and the move from x = 2 ramps up to it
        val old = CurvedLayers.follow(longArrayOf(2_000, 0, -2_000, 0), false, null, null,
            { x, y -> CurvedLayers.surfaceZ(stepWarp, x, y, 9.2) }, { x, y -> stepWarp.unwarp(x, y, 9.0) }, 0.01, atSplit = false)
        strayFromStep(old.xy, old.zs) shouldBe (0.1 plusOrMinus 0.1)
        (strayFromStep(old.xy, old.zs) > 0.05) shouldBe true
    }
    "a travel's lift clears the highest point of the layer surface under it: across the dome's center, 9.0; along the edge, 8.19" {
        CurvedLayers.highestAlong(warp, -18.0, 0.0, 18.0, 0.0, 9.0, 0.5) shouldBe (9.0 plusOrMinus 1e-6)
        CurvedLayers.highestAlong(warp, -18.0, -2.0, -18.0, 2.0, 9.0, 0.5) shouldBe (top(-18.0, 0.0) plusOrMinus 1e-6)
    }
})
