package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Sparse infill patterns on a synthetic 30 x 30 mm block, 50 layers of 0.2 mm. `cubic` once printed
 * as rectilinear without a word; an unknown pattern must stop the slice.
 */
class SparsePatternTest : StringSpec({
    val n = 50
    val tops = LongArray(n) { 200L * (it + 1) }
    val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 30_000, 0, 30_000))) }
    fun plan(pattern: String) = Planner.plan(SliceSettings.of(testSetup("plan.layer.infill.pattern" to pattern, "plan.layer.infill.density" to "20%")), outline, tops)
    /** Straight sparse segments of layer i longer than 5 mm: (angle 0..180, offset along the normal in um). */
    fun segments(l: LayerPlanOut): List<Pair<Double, Double>> = l.paths.filter { it.feature == Feature.SPARSE }.flatMap { p ->
        (0 until p.xy.size / 2 - 1).mapNotNull { k ->
            val dx = (p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(); val dy = (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()
            val mx = p.xy[2 * k] + dx / 2; val my = p.xy[2 * k + 1] + dy / 2
            // not the linking runs along the block's edge
            if (Math.hypot(dx, dy) < 5_000 || minOf(mx, my, 30_000 - mx, 30_000 - my) < 2_000) null else {
                val a = Math.toRadians(Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dy, dx))), 180L).toDouble())
                Math.toDegrees(a) to (-Math.sin(a) * p.xy[2 * k] + Math.cos(a) * p.xy[2 * k + 1])
            }
        }
    }
    fun sparseMm(layers: List<LayerPlanOut>) = layers.subList(10, 40).sumOf { l -> l.paths.filter { it.feature == Feature.SPARSE }.sumOf { p ->
        (0 until p.xy.size / 2 - 1).sumOf { k -> Math.hypot((p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(), (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()) } } } / 1000.0

    "cubic: three line directions 60 deg apart, each moving z/sqrt(2) per unit of height, at the density of rectilinear" {
        val cubic = plan("cubic")
        val rect = plan("rectilinear")
        val mid = segments(cubic[25])
        val angles = mid.map { it.first }.toSortedSet()
        println("cubic layer 25 line angles: $angles")
        angles.size shouldBe 3
        val a = angles.toList()
        (Math.abs(a[1] - a[0] - 60) < 1 && Math.abs(a[2] - a[1] - 60) < 1) shouldBe true
        // one family's lines, 5 layers apart: shifted 5 x 0.2 / sqrt(2) = 0.707 mm along the normal
        val fam = a[0]
        fun offsets(i: Int) = segments(cubic[i]).filter { Math.abs(it.first - fam) < 1 }.map { it.second }.toSortedSet().toList()
        val o25 = offsets(25); val o30 = offsets(30)
        val spacing = o25.zipWithNext { x, y -> y - x }.minOrNull()!!
        val raw = ((o30.first() - o25.first()) % spacing + spacing) % spacing
        val shift = if (raw > spacing / 2) raw - spacing else raw
        println("family %.0f deg: spacing %.0f um, shift over 5 layers %.0f um (expected -707 or +707 modulo spacing)".format(Locale.ROOT, fam, spacing, shift))
        (Math.abs(Math.abs(shift) - 1000 / Math.sqrt(2.0)) < 20) shouldBe true
        // control for the shift: rectilinear lines do not move with height (same angle two layers apart)
        val r25 = segments(rect[25]).map { it.second }.toSortedSet(); val r27 = segments(rect[27]).map { it.second }.toSortedSet()
        (r25.first() - r27.first()).let { Math.abs(it) < 2 } shouldBe true
        val c = sparseMm(cubic); val r = sparseMm(rect)
        println("sparse over layers 10-39: cubic %.0f mm, rectilinear %.0f mm".format(Locale.ROOT, c, r))
        (Math.abs(c / r - 1) < 0.10) shouldBe true
    }

    // minimum_sparse_infill_area (Flash Studio fills the interiors of raised letters solid):
    // a 4 mm pillar's interior (about 5 mm2 inside its walls) is solid; the 30 mm block stays sparse.
    "a sparse piece smaller than minimum_sparse_infill_area is internal solid; control: 0 = off" {
        val two = List(n) { listOf(Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 30_000, 0, 30_000)), Polygon(longArrayOf(40_000, 0, 44_000, 0, 44_000, 4_000, 40_000, 4_000))) }
        fun byIsland(area: String): Pair<Double, Double> {
            val layers = Planner.plan(SliceSettings.of(testSetup().also { s -> for (v in s.intents.values) v["plan.layer.infill.area.min"] = plymeld.model.Sourced(area.toDouble(), "test") }), two, tops)
            fun len(f: Feature, pillar: Boolean) = layers.subList(10, 40).sumOf { l -> l.paths.filter { p -> p.feature == f && (p.xy[0] > 35_000) == pillar }.sumOf { p ->
                (0 until p.xy.size / 2 - 1).sumOf { k -> Math.hypot((p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(), (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()) } } } / 1000.0
            println("min area $area: pillar sparse %.0f mm, solid %.0f mm; block sparse %.0f mm".format(Locale.ROOT, len(Feature.SPARSE, true), len(Feature.SOLID, true), len(Feature.SPARSE, false)))
            return len(Feature.SPARSE, true) to len(Feature.SOLID, true)
        }
        val on = byIsland("15"); val off = byIsland("0")
        on.first shouldBe 0.0
        (on.second > 0) shouldBe true
        (off.first > 0) shouldBe true                         // control: the pillar is sparse without the rule
    }

    "gyroid: curves inside the block, at the density of rectilinear, changing with height; no line crosses another" {
        val gy = plan("gyroid")
        val rect = plan("rectilinear")
        fun sparse(l: LayerPlanOut) = l.paths.filter { it.feature == Feature.SPARSE }
        // curved: one layer's sparse segments point in many directions (rectilinear: one)
        fun directions(l: LayerPlanOut) = sparse(l).flatMap { p -> (0 until p.xy.size / 2 - 1).mapNotNull { k ->
            val dx = (p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(); val dy = (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()
            if (Math.hypot(dx, dy) < 100) null else Math.floorMod(Math.round(Math.toDegrees(Math.atan2(dy, dx)) / 10), 18L) } }.toSet()
        println("gyroid layer 25: ${directions(gy[25]).size} directions (10 deg bins); rectilinear: ${directions(rect[25]).size}")
        // (rectilinear also has its short linking runs along the walls: up to a few bins besides its own)
        (directions(gy[25]).size >= 8) shouldBe true
        (directions(rect[25]).size <= 4) shouldBe true                 // control
        // inside the block, the walls' width aside
        for (l in gy.subList(10, 40)) for (p in sparse(l)) for (k in 0 until p.xy.size / 2) {
            (p.xy[2 * k] in 0..30_000 && p.xy[2 * k + 1] in 0..30_000) shouldBe true
        }
        // density: on a 100 mm block, where the edges (rectilinear's linking runs along the walls, gyroid's pieces too short
        // to lay) weigh little; the 30 mm block's figures printed beside it
        val big = List(n) { listOf(Polygon(longArrayOf(0, 0, 100_000, 0, 100_000, 100_000, 0, 100_000))) }
        fun planBig(pattern: String) = Planner.plan(SliceSettings.of(testSetup("plan.layer.infill.pattern" to pattern, "plan.layer.infill.density" to "20%")), big, tops)
        val g = sparseMm(planBig("gyroid")); val r = sparseMm(planBig("rectilinear"))
        println("sparse over layers 10-39, 100 mm block: gyroid %.0f mm, rectilinear %.0f mm; 30 mm block: %.0f, %.0f (period %d um)".format(Locale.ROOT,
            g, r, sparseMm(gy), sparseMm(rect), plymeld.core.Gyroid.periodUm(plymeld.core.Walls.spacing(440, 200).toDouble(), 0.2)))
        (Math.abs(g / r - 1) < 0.05) shouldBe true
        // the pattern moves with height: layer 25's points differ from layer 30's (control: rectilinear's 25 and 27 match)
        fun pts(l: LayerPlanOut) = sparse(l).flatMap { p -> (0 until p.xy.size / 2).map { p.xy[2 * it] / 200 to p.xy[2 * it + 1] / 200 } }.toSet()
        val same = (pts(gy[25]) intersect pts(gy[30])).size.toDouble() / pts(gy[25]).size
        println("gyroid layers 25 and 30 share %.0f %% of their points (0.2 mm cells)".format(Locale.ROOT, 100 * same))
        (same < 0.5) shouldBe true
        // no two sparse segments of one layer cross (grid's do, the control)
        fun crossings(l: LayerPlanOut): Int {
            val s = sparse(l).flatMap { p -> (0 until p.xy.size / 2 - 1).map { k -> longArrayOf(p.xy[2 * k], p.xy[2 * k + 1], p.xy[2 * k + 2], p.xy[2 * k + 3]) } }
            fun cr(a: LongArray, b: LongArray): Boolean {
                fun o(ax: Long, ay: Long, bx: Long, by: Long, cx: Long, cy: Long) = Math.signum((bx - ax).toDouble() * (cy - ay) - (by - ay).toDouble() * (cx - ax))
                return o(a[0], a[1], a[2], a[3], b[0], b[1]) * o(a[0], a[1], a[2], a[3], b[2], b[3]) < 0 &&
                    o(b[0], b[1], b[2], b[3], a[0], a[1]) * o(b[0], b[1], b[2], b[3], a[2], a[3]) < 0
            }
            var c = 0
            for (x in s.indices) for (y in x + 1 until s.size) if (cr(s[x], s[y])) c++
            return c
        }
        val gc = crossings(gy[25]); val grc = crossings(plan("grid")[25])
        println("crossings on layer 25: gyroid $gc, grid $grc")
        gc shouldBe 0
        (grc > 0) shouldBe true                                       // control
    }

    "an unknown sparse pattern stops the slice instead of printing something else" {
        // the config refuses it; and the planner refuses one that reaches it some other way (control: a known one plans)
        shouldThrow<plymeld.config.ConfigError> { plan("honeycomb") }
        fun planned(pattern: String) = Planner.plan(SliceSettings.of(testSetup().also { s ->
            for (v in s.intents.values) v["plan.layer.infill.pattern"] = plymeld.model.Sourced(pattern, "test") }), outline, tops)
        shouldThrow<IllegalStateException> { planned("honeycomb") }
        planned("grid").size shouldBe n
    }
    "gyroid: the laid curves are the trace simplified to the chord tolerance (plan.layer.infill.chord_tolerance): far fewer vertices, the same length, no point further off than the tolerance" {
        val region = listOf(Polygon(longArrayOf(0, 0, 40_000, 0, 40_000, 40_000, 0, 40_000)))
        val period = plymeld.core.Gyroid.periodUm(plymeld.core.Walls.spacing(440, 200).toDouble(), 0.15)
        val fine = plymeld.core.Gyroid.curves(region, period, 5_100.0, null, 880, 0)
        val coarse = plymeld.core.Gyroid.curves(region, period, 5_100.0, null, 880, 50)
        fun vertices(c: List<LongArray>) = c.sumOf { it.size / 2 }
        fun length(c: List<LongArray>) = c.sumOf { pl -> (0 until pl.size / 2 - 1).sumOf { k -> Math.hypot((pl[2 * k + 2] - pl[2 * k]).toDouble(), (pl[2 * k + 3] - pl[2 * k + 1]).toDouble()) } }
        println("gyroid 40 mm square, period %d um: %d vertices as traced, %d at a 0.05 mm chord; %.1f and %.1f mm".format(Locale.ROOT, period, vertices(fine), vertices(coarse), length(fine) / 1000, length(coarse) / 1000))
        (vertices(coarse) * 3 < vertices(fine)) shouldBe true
        (Math.abs(length(coarse) - length(fine)) < length(fine) * 0.01) shouldBe true
        // the simplifier on the laid curves themselves (clipping and the short-piece filter act on each set on its own): every
        // point of a curve lies within the tolerance of its simplified curve (the distance to the nearest simplified segment)
        var worst = 0.0; var kept = 0; var all = 0
        for (pl in fine) {
            val simple = plymeld.core.Gyroid.simplify(pl.map { it.toDouble() }, 50.0)
            kept += simple.size / 2; all += pl.size / 2
            fun dist(px: Double, py: Double): Double = (0 until simple.size / 2 - 1).minOf { k ->
                val ax = simple[2 * k]; val ay = simple[2 * k + 1]; val vx = simple[2 * k + 2] - ax; val vy = simple[2 * k + 3] - ay; val l2 = vx * vx + vy * vy
                val t = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
                Math.hypot(px - ax - t * vx, py - ay - t * vy) }
            for (i in 0 until pl.size / 2) worst = maxOf(worst, dist(pl[2 * i].toDouble(), pl[2 * i + 1].toDouble()))
        }
        println("simplified: %d of %d vertices kept, the worst deviation %.1f um".format(Locale.ROOT, kept, all, worst))
        (worst <= 50.0 + 1e-6) shouldBe true
        (kept * 3 < all) shouldBe true
        // the control: simplify alone, a straight run of points with one kink keeps the kink and its ends only
        val kinked = listOf(0.0, 0.0, 1000.0, 0.0, 2000.0, 0.0, 3000.0, 500.0, 4000.0, 1000.0)
        plymeld.core.Gyroid.simplify(kinked, 10.0) shouldBe listOf(0.0, 0.0, 2000.0, 0.0, 4000.0, 1000.0)
        plymeld.core.Gyroid.simplify(kinked, 600.0) shouldBe listOf(0.0, 0.0, 4000.0, 1000.0)
    }
})
