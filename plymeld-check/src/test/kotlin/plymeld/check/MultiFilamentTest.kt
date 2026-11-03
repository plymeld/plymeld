package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.FilamentAreas
import plymeld.core.LayerPlanOut
import plymeld.core.MultiFilament
import plymeld.core.Planner
import plymeld.geom.Offset
import plymeld.geom.Polygons
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Multi-color step 2 on the two-color Benchy (halves A = hull etc., B = deck, gunwale etc.), with
 * the Benchy project's settings. Precedence A-first is an arbitrary choice for the test (the rule
 * is the user's decision); it only decides the 0.002 mm tie at the deck top.
 */
class MultiFilamentTest : StringSpec({
    val s = SliceSettings.of(testSetup())
    val parts = benchyDualParts()
    val all = parts.fold(FloatArray(0)) { acc, p -> acc + p.coords }.let { plymeld.mesh.TriangleSoup(it, parts.sumOf { p -> p.count }) }
    val b = all.bounds()
    val plan = LayerPlan.fixed(b[2], b[5], s.firstLayerHeightUm / 1000.0, s.layerHeightUm / 1000.0)
    val tops = LongArray(plan.count) { Math.round((plan.tops[it] - b[2]) * 1000) }
    val partLayers = parts.map { p -> Slicer.slice(p, plan.slices).map { it.loops } }
    val r = MultiFilament.plan(s, partLayers, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")

    fun length(layers: List<LayerPlanOut>, vararg f: Feature) = layers.sumOf { l -> l.paths.filter { it.feature in f }.sumOf { p ->
        val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
        (0 until segs).sumOf { k -> val j = (k + 1) % m; Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) } } } / 1000.0
    fun total(layers: List<LayerPlanOut>) = length(layers, *Feature.entries.toTypedArray())

    "both filaments get paths, and each filament's paths stay inside its own areas" {
        val (fa, fb) = r.filaments
        println("two-color Benchy: filament 1 %.0f mm of paths, filament 2 %.0f mm".format(Locale.ROOT, total(fa.layers), total(fb.layers)))
        total(fa.layers) shouldBeGreaterThan 0.0
        total(fb.layers) shouldBeGreaterThan 0.0
        // every path vertex of a filament (brim and support aside) lies within its areas grown by a line width
        for (f in r.filaments) {
            var outside = 0; var checked = 0
            for ((i, l) in f.layers.withIndex()) {
                if (f.areas[i].isEmpty()) continue
                val grown = Polygons.group(Offset.offset(f.areas[i], s.outerWallWidthUm))
                for (p in l.paths) if (p.feature != Feature.BRIM && p.feature != Feature.SUPPORT && p.feature != Feature.SUPPORT_INTERFACE)
                    for (k in 0 until p.xy.size / 2 step 7) {
                        checked++
                        if (grown.none { (o, hs) -> Polygons.contains(o, p.xy[2 * k], p.xy[2 * k + 1]) && hs.none { Polygons.contains(it, p.xy[2 * k], p.xy[2 * k + 1]) } }) outside++
                    }
            }
            println("  ${f.spool}: $outside of $checked sampled path points outside its areas (+ one wall width)")
            (checked > 0) shouldBe true
            outside shouldBe 0
        }
    }

    // Measured on the SURFACES, not on bridge path length: the deck rests on the hull's sparse
    // infill, so it is bridged (internal bridge over sparse) either way, as in the one-body plan
    // (a first version compared bridge lengths and was wrong for that reason).
    "the deck half's bottom surface is judged against the whole object: none where it rests on the hull" {
        val fb = r.filaments.single { it.spool == "spool 2" }
        val w2 = Planner.walls(s, fb.areas, tops, wholeFirst = r.whole.outlines[0])
        val rWhole = Planner.regions(s, w2, r.whole); val rAlone = Planner.regions(s, w2)
        val whole = fb.areas.indices.map { FilamentAreas.area(rWhole[it].bottomSurface) / 1e6 }
        val alone = fb.areas.indices.map { FilamentAreas.area(rAlone[it].bottomSurface) / 1e6 }
        val deck = alone.indices.maxBy { alone[it] - whole[it] }
        println("  filament 2 bottom surface: %.1f mm² against the whole object, %.1f mm² judged alone; most on layer %d (top z %.2f): %.1f vs %.1f mm²".format(Locale.ROOT,
            whole.sum(), alone.sum(), deck, tops[deck] / 1000.0, whole[deck], alone[deck]))
        // the whole object can only cover MORE: no layer gets more bottom surface from it
        fb.areas.indices.filter { whole[it] > alone[it] + 1e-6 } shouldBe emptyList()
        // the deck's first layer (top z 8.2, on the hull) loses its bottom surface entirely
        (tops[deck] / 1000.0) shouldBe (8.2 plusOrMinus 1e-6)
        whole[deck] shouldBe (0.0 plusOrMinus 1e-6)
        alone[deck] shouldBeGreaterThan 100.0
    }

    // Regression: the bow at z 8.0-8.2 was sparse region with NO sparse paths, because
    // it joins the thin strip round the deck and the whole piece was judged narrow. Every other
    // check here passed with the bow hollow. (12, -15, 8.1) is solid, checked independently.
    "the bow at layer 40 gets sparse infill, in the two-filament and the one-body plan" {
        val px = 12_000.0; val py = -15_000.0
        // SEGMENTS within 3 mm of the point (a grid line crossing the bow has vertices only at its
        // ends; counting vertices read 0 for a filled bow — the first version of this test)
        fun near(ps: List<plymeld.core.ToolPath>) = ps.filter { it.feature == Feature.SPARSE }.sumOf { p ->
            val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
            (0 until segs).count { k ->
                val j = (k + 1) % m
                val ax = p.xy[2 * k].toDouble(); val ay = p.xy[2 * k + 1].toDouble(); val bx = p.xy[2 * j].toDouble(); val by = p.xy[2 * j + 1].toDouble()
                val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
                Math.hypot(px - ax - t * vx, py - ay - t * vy) < 3000
            } }
        val two = near(r.filaments.single { it.spool == "spool 1" }.layers[40].paths)
        val one = near(Planner.paths(s, r.whole.outlines, r.whole.w, Planner.regions(s, r.whole.w), tops)[40].paths)
        println("  bow at layer 40: sparse segments within 3 mm: two filaments $two, one body $one")
        (two > 0) shouldBe true
        (one > 0) shouldBe true
    }

    "report: the same boat as one body vs two filaments" {
        // paths of a few layers, written out for inspection (build/benchy_dual_paths.txt)
        java.io.File("build/benchy_dual_paths.txt").printWriter().use { w ->
            for (i in listOf(30, 40, 100, 160, 183, 225)) for (f in r.filaments) for (p in f.layers[i].paths)
                w.println("$i ${tops[i]} ${f.spool.replace(' ', '_')} ${p.feature.name} ${if (p.closed) 1 else 0} " + p.xy.joinToString(" ") { "%.3f".format(Locale.ROOT, it / 1000.0) })
        }
        val union = r.whole.outlines
        val one = Planner.paths(s, union, r.whole.w, Planner.regions(s, r.whole.w), tops)
        val two = r.filaments.flatMap { it.layers }
        for (f in listOf(Feature.OUTER_WALL, Feature.INNER_WALL, Feature.SPARSE, Feature.SOLID, Feature.TOP, Feature.BOTTOM, Feature.BRIDGE))
            println("  %-16s one body %7.0f mm, two filaments %7.0f mm".format(Locale.ROOT, f.label, length(one, f), length(two, f)))
        println("  total            one body %7.0f mm, two filaments %7.0f mm; overlap resolved %.2f mm²·layers".format(Locale.ROOT,
            total(one), total(two), r.overlapUm2PerLayer.sum() / 1e6))
        // coverage: the filaments' areas together are the whole object, layer by layer, except the
        // hairline seams where the parts meet, which the whole object's outline closes
        // (slice_closing_radius) and no filament prints. So what the whole has beyond the
        // filaments must vanish under an opening by the closing radius, and nothing may be the other
        // way round.
        val worst = union.indices.maxOf { i -> Math.abs(FilamentAreas.area(union[i]) - r.filaments.sumOf { f -> FilamentAreas.area(f.areas[i]) }) } / 1e6
        var wider = 0.0; var outside = 0.0
        for (i in union.indices) {
            val areas = plymeld.geom.Booleans.normalize(r.filaments.flatMap { it.areas[i] })
            val extra = if (areas.isEmpty()) union[i] else plymeld.geom.Booleans.difference(union[i], areas)
            if (extra.isNotEmpty()) {
                val q = s.closingRadiusUm + 1
                wider += FilamentAreas.area(plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(extra, -q), q)) / 1e6
            }
            if (areas.isNotEmpty()) outside += FilamentAreas.area(plymeld.geom.Booleans.difference(areas, union[i])) / 1e6
        }
        println("  worst layer: |whole area - sum of filament areas| = %.4f mm²; beyond hairline %.4f mm², filament area outside the whole %.4f mm²".format(Locale.ROOT, worst, wider, outside))
        wider shouldBe (0.0 plusOrMinus 0.01)
        outside shouldBe (0.0 plusOrMinus 0.01)
    }
})

