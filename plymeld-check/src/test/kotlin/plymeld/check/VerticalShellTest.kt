package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Vertical shells on a face leaning INWARD (the part narrows upward): Flash Studio
 * (ensure_vertical_shell_thickness = ensure_all, top_shell_thickness 1 mm, 0.08 mm layers) lays a
 * solid band behind the face exactly shell thickness x tan(lean) wide (fin ladder 20-65 deg). plymeld
 * uses its own ellipsoid rule alone; top_shell_thickness does not set the band.
 */
class VerticalShellTest : StringSpec({
    val cfg = arrayOf<Pair<String, Any>>("plan.technique.flat.layer.height" to "0.08 mm", "plan.mechanism.adhesion.first_layer.height" to "0.08 mm",
        "plan.layer.top.thickness" to "1 mm")

    "top_shell_thickness raises the layer count" {
        SliceSettings.of(testSetup(*cfg)).topLayers shouldBe 13                        // ceil(1.0 / 0.08)
        SliceSettings.of(testSetup(*cfg, "plan.layer.top.thickness" to "0.48 mm")).topLayers shouldBe 6
    }

    // a 30 mm square narrowing 0.08 mm per 0.08 mm layer on every side (45 deg), 60 layers. The
    // ellipsoid rule (default): every point within the wall thickness W across and the top
    // shell T = 13 x 0.08 mm up is solid, so behind the walls a band sqrt(W^2 + (T tan 45)^2) - W.
    // Control: Flash Studio (ensure_all) lays the whole T tan 45 = 1.04 mm band - plastic beyond every
    // stated thickness; the ellipsoid band is clearly less.
    "an inward 45 deg face: the ellipsoid band, sqrt(W^2 + T^2) - W, less than the full T" {
        val n = 60
        val tops = LongArray(n) { 80L * (it + 1) }
        val outlines = List(n) { i -> val d = 80L * i; listOf(Polygon(longArrayOf(d, d, 30_000 - d, d, 30_000 - d, 30_000 - d, d, 30_000 - d))) }
        fun band(): Pair<Double, Double> {
            val s = SliceSettings.of(testSetup(*cfg))
            val w = Planner.walls(s, outlines, tops)
            val r = Planner.regions(s, w)[30]
            val inner = r.inner.sumOf { it.perimeterMm() }         // the band runs round the inside of the walls
            val wallDepth = (outlines[30].sumOf { it.signedAreaMm2() } - r.inner.sumOf { it.signedAreaMm2() }) / outlines[30].sumOf { it.perimeterMm() }
            return r.internalSolid.sumOf { it.signedAreaMm2() } / inner to wallDepth
        }
        val (ell, wd) = band()
        val t = 13 * 0.08
        val expect = Math.sqrt(wd * wd + t * t) - wd
        println("solid band behind the face at layer 30: ellipsoid %.2f mm (W %.2f, T %.2f: expected %.2f; the full T band %.2f)".format(Locale.ROOT, ell, wd, t, expect, t))
        (Math.abs(ell - expect) < 0.1) shouldBe true
        (t > ell + 0.3) shouldBe true
    }
})
