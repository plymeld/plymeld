package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.Walls
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.mesh.TriangleSoup
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Values
import plymeld.model.withObjectSetups
import java.util.Locale

/**
 * A part narrowing to a ridge a line or two wide, then to nothing, over a few layers (variable-width walls, Walls.arachne):
 * a block swept along a gentle arc (radius 40 mm, 10 mm long) with a roof ridge on it, one face at 62.5 deg from level, the
 * other at 27.5 deg (0.2 mm narrower each 0.08 mm layer), its top cut off by a 0.1 mm chamfer, its apex rising along it
 * (so each layer cuts it a little further along); 0.4 mm lines, 0.08 mm layers. Alone, and beside a second part on a second
 * tool against its shallow face (the object laid per part). Three ridges: its apex rising 0.03 mm along it, its faces flat or
 * moved 10 um in and out every 0.37 mm (as a curved surface's facets move them): where a layer cuts the ridge about as wide
 * as two walls need, along much of its length; and rising 0.16 mm, where each layer's change from two walls to one is at
 * one place. A fourth ridge is lowest in its middle (its apex 1.5 mm higher at its ends, a parabola): each layer cuts it
 * as two walls at its ends and one line between.
 *
 * Read from the g-code (the first tool's lines, each a bead of its width from its plastic, round its move), at stations
 * every 0.1 mm along the ridge 0.5 mm and more from its cut ends, against the ridge's own width there (from the formula
 * that built it, at the layer's middle):
 *  - too narrow for two walls and at least the narrowest feature a line is laid for (plan.layer.walls.feature.min): the beads
 *    cover at least 3/4 of it (where a single line meets a loop's turn, the line's round end reaches into the turn: a
 *    junction leaves a notch under a quarter of the width); two walls wide: they leave less than the narrowest line uncovered
 *    (fixed-width walls leave a leftover narrower than a line empty, plan.layer.walls.line.width.min);
 *  - narrower than the narrowest feature, away from wider places by a line: nothing laid;
 *  - no two beads overlap by more than the walls' usual overlap (h (1 - pi / 4) per pair in contact), what the narrowest
 *    line may reach into the walls on its two sides (the infill's wall overlap of its width a side, at least its flank
 *    h (1 - pi / 4): a thin leftover between walls gets that line down to that much narrower than it),
 *    the slicing tolerance each side (a loop carried on where the area is that much narrower than it needs) and the
 *    sampling (0.03 mm), except where a line's end reaches into another line (within half a line of an open line's end);
 *  - no fragment: no closed line shorter than 3 mm crosses the ridge (a loop round one dot of a fit region), no open line
 *    shorter than 1 mm but one running to the ridge's cut end (an end within 0.9 mm of it: a line's width and the 0.5 mm
 *    the stations keep off the end).
 * Each reading fails on the walls as they were before this rule (run with the fix taken out): the two-part flat ridge left
 * 0.9 mm of a 0.78 mm ridge with no line (a short line there dropped as a "speck" beside the other part); the wobbly ridge
 * laid a string of closed loops 1.2-1.5 mm long, overlapping each other across the whole width (45 fragments); the steep
 * ridge's change from two walls to one left a notch with nothing laid across the ridge; and the dipped ridge (lowest in
 * its middle: a loop at each end, one line between) had its line pruned to a stub, 10 to 15 stations together with
 * nothing across them. The pre-print check is read on the wobbly ridge with 1.5 mm of a
 * crest layer's lines taken out (refused: the older rule alone passed it), and with the line on a crest 0.26 mm wide taken
 * out (refused: the walls lay a line down to plan.layer.walls.feature.min); its control, a ridge whose crests narrow to
 * nothing over millimetres (its top cut 5 um down), passes, its tips under that floor bare, and is refused judged against
 * a floor near nothing.
 */
class ThinRidgeTest : StringSpec({
    /** The model (mm); [rise]: the apex's rise over the ridge's length; [wobble]: the faces moved in and out by this much. */
    class Ridge(val rise: Double, val wobble: Double = 0.0, val stations: Int = 201, val dip: Double = 0.0, val chamfer: Double = 0.1) {
        val radius = 40.0; val length = 10.0; val ox = 60.0; val oy = 105.0
        val zb = 1.0
        val steep = Math.toRadians(62.5); val shallow = Math.toRadians(27.5)
        val apex0 = 1.0
        /** The block's half width: 3 mm, or wider where the ridge's foot is (its shallow face's foot half a millimetre in). */
        val half = maxOf(3.0, (apex0 + rise + dip) / Math.tan(shallow) + 0.5)
        /** The apex's height over the block: rising [rise] along the ridge, and [dip] higher at its ends than at its middle (a parabola). */
        fun apex(s: Double) = apex0 + rise * s / length + dip * Math.pow((s - length / 2) / (length / 2), 2.0)
        fun shift(s: Double) = wobble * Math.sin(s * 2 * Math.PI / 0.37)
        fun center(s: Double) = doubleArrayOf(ox + radius * Math.sin(s / radius), oy + radius - radius * Math.cos(s / radius))
        fun normal(s: Double) = doubleArrayOf(-Math.sin(s / radius), Math.cos(s / radius))
        /** The ridge's cross-section at [s] and height [z] above the bed: its two faces along the normal, null above it. */
        fun across(s: Double, z: Double): DoubleArray? {
            val up = zb + apex(s) - z
            if (z < zb || up < chamfer) return null
            return doubleArrayOf(-up / Math.tan(shallow) - shift(s), up / Math.tan(steep) + shift(s))
        }
        /** A sweep along the arc of [profile] (n, z), counter-clockwise, its caps triangulated as [cap] (index triples, counter-clockwise). */
        fun sweep(profile: (Double) -> List<DoubleArray>, cap: List<Int>): TriangleSoup {
            val tris = ArrayList<DoubleArray>()
            val rows = (0 until stations).map { k -> val s = length * k / (stations - 1); val c = center(s); val nv = normal(s)
                profile(s).map { p -> doubleArrayOf(c[0] + p[0] * nv[0], c[1] + p[0] * nv[1], p[1]) } }
            val m = rows[0].size
            for (k in 0 until stations - 1) for (i in 0 until m) {
                val j = (i + 1) % m
                val a0 = rows[k][i]; val b0 = rows[k][j]; val a1 = rows[k + 1][i]; val b1 = rows[k + 1][j]
                tris += a0 + b0 + b1; tris += a0 + b1 + a1
            }
            for (t in cap.indices step 3) {
                val e = rows.last(); tris += e[cap[t]] + e[cap[t + 1]] + e[cap[t + 2]]
                val f = rows.first(); tris += f[cap[t]] + f[cap[t + 2]] + f[cap[t + 1]]
            }
            return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
        }
        /** The block and its ridge. */
        val part by lazy { sweep({ s -> val a = apex(s); val top = zb + a - chamfer; val w = shift(s)
            listOf(doubleArrayOf(-half, 0.0), doubleArrayOf(half, 0.0), doubleArrayOf(half, zb), doubleArrayOf(a / Math.tan(steep) + w, zb),
                doubleArrayOf(chamfer / Math.tan(steep) + w, top), doubleArrayOf(-chamfer / Math.tan(shallow) - w, top), doubleArrayOf(-a / Math.tan(shallow) - w, zb), doubleArrayOf(-half, zb)) },
            listOf(0, 1, 2, 0, 2, 3, 0, 3, 6, 0, 6, 7, 3, 4, 5, 3, 5, 6)) }
        /** A second part on the block against the ridge's shallow face, up to its chamfer. */
        val against by lazy { sweep({ s -> val a = apex(s); val top = zb + a - chamfer; val w = shift(s)
            listOf(doubleArrayOf(-half, zb), doubleArrayOf(-a / Math.tan(shallow) - w, zb), doubleArrayOf(-chamfer / Math.tan(shallow) - w, top), doubleArrayOf(-half, top)) }, listOf(0, 1, 2, 0, 2, 3)) }
    }
    val flat = Ridge(0.03); val wobbly = Ridge(0.03, 0.01); val steep = Ridge(0.16); val dipped = Ridge(0.0, dip = 1.5)
    /** The steep ridge with its top cut off 5 um down, not 0.1 mm: on each layer its crest narrows to nothing over millimetres. */
    val pointed = Ridge(0.16, chamfer = 0.005)

    class Written(val g: String, val plan: Plan, val setup: Setup)
    /** [parts] as one object written through Job (two parts: the second on a second tool), 0.08 mm layers, [extra] over the test setup. */
    fun write(parts: List<TriangleSoup>, vararg extra: Pair<String, Any>): Written {
        val two = parts.size == 2
        val common = arrayOf<Pair<String, Any>>("plan.technique.flat.layer.height" to "0.08 mm", "plan.layer.walls.outer.line.width" to "0.4 mm",
            "plan.layer.walls.inner.line.width" to "0.4 mm", "hardware.machine.tm.firmware.arcs" to false, *extra)
        val base = if (!two) testSetup(*common, models = listOf(plymeld.config.ModelObject("o", listOf("A"), listOf(1), "o.3mf")))
            else testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm", *common,
            models = listOf(plymeld.config.ModelObject("o", listOf("A", "B"), listOf(1, 2), "o.3mf")))
        val setup = if (!two) base else base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"), listOf(
            PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", "spool 2", Values(Entity.INTENT, "B"))))))
        val plan = Plan.makeFor(listOf("o" to parts), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE, objects = listOf(Plan.Companion.ObjectLocks(layerHeightMm = 0.08))))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }
    /** The pre-print check as `slice` runs it on a lone object (GcodeCheck, ModelCheck). */
    fun problems(w: Written, g: String = w.g, floorMm: Double = SliceSettings.of(w.setup).let { (it.minFeatureUm + 2 * it.resolutionUm) / 1000.0 }): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val sf = ModelCheck.Surface(w.plan.parts, zBase, g)
        return GcodeCheck.check(g, Expectations.forSetup(w.setup, sf::at)) + ModelCheck.check(g, w.plan.parts, zBase, surface = sf, featureMinMm = floorMm)
    }

    /** [w]'s moves, read with the filament's diameter (the file states none) so each line's width comes from its plastic. */
    fun toolpath(w: Written): TechniqueRender.Toolpath {
        val t = TechniqueRender.read(w.g)
        return TechniqueRender.Toolpath(t.moves, t.layerZs, t.text, t.pausedBefore, SliceSettings.of(w.setup).filamentDiameterMm)
    }
    /** The shift from the model's frame to the g-code's. */
    fun shiftOf(r: Ridge, w: Written): DoubleArray {
        val orig = r.part.bounds(); val placed = w.plan.layout.placed[0].parts[0].bounds()
        return doubleArrayOf(placed[0] - orig[0], placed[1] - orig[1])
    }

    /** What the reading found: each a station's or a line's description. */
    class Reading(val stations: Int, val bare: List<String>, val laidOnSliver: List<String>, val doubled: List<String>, val fragments: List<String>) {
        val faults get() = bare + laidOnSliver + doubled + fragments
        override fun toString() = "$stations stations: ${bare.size} under-laid, ${laidOnSliver.size} laid on a sliver, ${doubled.size} doubled, ${fragments.size} fragments" +
            (if (faults.isEmpty()) "" else "; first: " + listOf(bare, laidOnSliver, doubled, fragments).flatMap { it.take(3) }.joinToString(" | "))
    }

    /** The ridge's crest layers of [w] read as the class comment says. */
    fun read(r: Ridge, w: Written): Reading {
        val tp = toolpath(w); val sh = shiftOf(r, w)
        val st = SliceSettings.of(w.setup)
        val minBead = st.minBeadUm / 1000.0; val minFeature = st.minFeatureUm / 1000.0; val tol = st.resolutionUm / 1000.0
        // paths: consecutive extruding moves joined end to start
        val ms = tp.moves
        val pathOf = IntArray(ms.size); var p = 0
        for (k in ms.indices) { if (k > 0) { val a = ms[k - 1]; val b = ms[k]
            if (Math.hypot(a.x1 - b.x0, a.y1 - b.y0) > 1e-6 || a.feature != b.feature || a.layer != b.layer || a.tool != b.tool) p++ }; pathOf[k] = p }
        val pathLen = DoubleArray(p + 1); for (k in ms.indices) pathLen[pathOf[k]] += ms[k].length
        val pathClosed = BooleanArray(p + 1); val pathStart = IntArray(p + 1); val pathEnd = IntArray(p + 1)
        run { var k = 0; while (k < ms.size) { var j = k; while (j + 1 < ms.size && pathOf[j + 1] == pathOf[k]) j++
            pathClosed[pathOf[k]] = Math.hypot(ms[k].x0 - ms[j].x1, ms[k].y0 - ms[j].y1) < 1e-6
            pathStart[pathOf[k]] = k; pathEnd[pathOf[k]] = j; k = j + 1 } }
        val bare = ArrayList<String>(); val sliver = ArrayList<String>(); val doubled = ArrayList<String>(); val fragments = LinkedHashSet<String>()
        var stations = 0
        for (layer in tp.layerZs.indices) {
            val lm = ms.withIndex().filter { it.value.layer == layer && it.value.obj != null && it.value.tool == 0 }
            if (lm.isEmpty()) continue
            val h = lm.first().value.height
            val zm = tp.layerZs[layer] - h / 2
            if (zm < r.zb + 0.05 || zm > r.zb + r.apex0 + r.rise + r.dip) continue
            // the width two walls need: the outer wall's bead on each side, a spacing apart
            val two = (st.outerWallWidthUm + Walls.spacing(st.outerWallWidthUm, Math.round(h * 1000))) / 1000.0
            // open lines' ends on this layer
            val ends = lm.map { pathOf[it.index] }.distinct().filter { !pathClosed[it] }.flatMap { q ->
                listOf(doubleArrayOf(ms[pathStart[q]].x0, ms[pathStart[q]].y0, tp.beadWidth(ms[pathStart[q]])), doubleArrayOf(ms[pathEnd[q]].x1, ms[pathEnd[q]].y1, tp.beadWidth(ms[pathEnd[q]]))) }
            fun widthAt(s: Double) = r.across(s, zm)?.let { it[1] - it[0] } ?: 0.0
            var s = 0.5
            while (s <= r.length - 0.5 + 1e-9) {
                val c = r.center(s); val nv = r.normal(s)
                val cx = c[0] + sh[0]; val cy = c[1] + sh[1]
                val f = r.across(s, zm)
                val width = widthAt(s)
                val lo = (f?.get(0) ?: 0.0) - 0.6; val hi = (f?.get(1) ?: 0.0) + 0.6
                // the beads near the cross-section: each a capsule of its width round its move
                val near = lm.filter { (_, m) -> val w2 = tp.beadWidth(m) / 2
                    val ax = cx + lo * nv[0]; val ay = cy + lo * nv[1]; val bx = cx + hi * nv[0]; val by = cy + hi * nv[1]
                    maxOf(m.x0, m.x1) >= minOf(ax, bx) - w2 && minOf(m.x0, m.x1) <= maxOf(ax, bx) + w2 && maxOf(m.y0, m.y1) >= minOf(ay, by) - w2 && minOf(m.y0, m.y1) <= maxOf(ay, by) + w2 }
                    .map { (k, m) -> Triple(m, tp.beadWidth(m), k) }
                val step = 0.005
                var covered = 0.0; var over = 0.0; var beads = 0
                val crossing = HashSet<Int>()
                var t = lo
                while (t <= hi) {
                    val px = cx + t * nv[0]; val py = cy + t * nv[1]
                    val here = HashSet<Int>()
                    for ((m, bw, k) in near) {
                        val ex = m.x1 - m.x0; val ey = m.y1 - m.y0; val l2 = ex * ex + ey * ey
                        val u = if (l2 == 0.0) 0.0 else (((px - m.x0) * ex + (py - m.y0) * ey) / l2).coerceIn(0.0, 1.0)
                        if (Math.hypot(px - m.x0 - u * ex, py - m.y0 - u * ey) <= bw / 2) { here += k; crossing += pathOf[k] }
                    }
                    // the beads here, a path's consecutive moves (which overlap at their joints) counted once; a few moves apart
                    // too (a micron-long move between two, its width from its rounded plastic, may miss the point), and a
                    // closed path's first and last moves
                    val n = here.count { k -> (1..3).none { j -> (k - j) in here && pathOf[k - j] == pathOf[k] } &&
                        !(pathClosed[pathOf[k]] && k == pathStart[pathOf[k]] && here.any { q -> pathOf[q] == pathOf[k] && q >= pathEnd[pathOf[k]] - 2 }) }
                    beads = maxOf(beads, n)
                    if (n >= 1 && f != null && t >= f[0] && t <= f[1]) covered += step
                    if (n >= 2) over += step
                    t += step
                }
                stations++
                val at = String.format(Locale.ROOT, "z %.2f s %.1f width %.2f: covered %.2f, overlap %.2f", tp.layerZs[layer], s, width, covered, over)
                // (within a line's width, along the ridge, of where this layer's ridge narrows to nothing: the line's round end)
                val nearTip = (-8..8).any { k -> widthAt(s + 0.05 * k) < minFeature }
                // within half a line (along the ridge) of an open line's end on the ridge: where the end reaches into another line
                val atEnd = ends.any { e -> val ex = e[0] - cx; val ey = e[1] - cy
                    Math.abs(ex * nv[1] - ey * nv[0]) < e[2] / 2 + 0.05 && (ex * nv[0] + ey * nv[1]) in lo..hi }
                if (width >= minFeature && width < 1.2 && !nearTip) {
                    // (where a line's end meets a loop's turn, their two round ends leave notches: half the width there)
                    val need = if (width < two) (if (atEnd) 0.5 else 0.75) * width else width - minBead - 0.1
                    if (covered < need) bare += at
                }
                if (width < minFeature && covered > 0 && listOf(s - 0.4, s + 0.4).all { widthAt(it) < minFeature }) sliver += at
                // (an interior line's side: the infill's overlap with the walls of the narrowest line, or at least its flank)
                val side = maxOf(h * (1 - Math.PI / 4), st.infillWallOverlap * minBead)
                if (width < 1.2 && !atEnd && over > (beads - 1).coerceAtLeast(0) * h * (1 - Math.PI / 4) + 2 * side + 2 * tol + 0.03) doubled += at
                // the walls' lines where the ridge holds walls only
                if (width < 1.2) for (q in crossing) {
                    if (ms[pathStart[q]].feature != "Outer wall" && ms[pathStart[q]].feature != "Inner wall") continue
                    // a line running to an end of the ridge (a point within 0.9 mm along it of a cut end, or of where the ridge on
                    // this layer narrows to less than the narrowest feature) is the ridge's end: a short piece there is its walls
                    fun atEnd(se: Double) = se < 0.4 + 0.5 || se > r.length - 0.4 - 0.5 ||
                        (-18..18).any { k -> widthAt(se + 0.05 * k) < minFeature }
                    fun sOf(x: Double, y: Double) = r.radius * Math.atan2(x - sh[0] - r.ox, r.oy + r.radius - (y - sh[1]))
                    if (pathClosed[q] && pathLen[q] < 2.0 && (pathStart[q]..pathEnd[q]).none { k -> atEnd(sOf(ms[k].x0, ms[k].y0)) })
                        fragments += String.format(Locale.ROOT, "z %.2f: a closed line %.2f mm long at s %.1f", tp.layerZs[layer], pathLen[q], s)
                    // (an inner line between a loop's sides may be short: the leftover there is short)
                    if (!pathClosed[q] && ms[pathStart[q]].feature == "Outer wall" && pathLen[q] < 1.0 && listOf(ms[pathStart[q]].let { doubleArrayOf(it.x0, it.y0) }, ms[pathEnd[q]].let { doubleArrayOf(it.x1, it.y1) }).none { e ->
                            atEnd(r.radius * Math.atan2(e[0] - sh[0] - r.ox, r.oy + r.radius - (e[1] - sh[1]))) })
                        fragments += String.format(Locale.ROOT, "z %.2f: an open line %.2f mm long at s %.1f", tp.layerZs[layer], pathLen[q], s)
                }
                s += 0.1
            }
        }
        return Reading(stations, bare, sliver, doubled, fragments.toList())
    }

    /**
     * The crest layers from above, each a panel: [w]'s first tool's lines at their width from their plastic, the ridge's faces
     * at the layer's middle; into [file], and the same with each line drawn 0.4 of its width colored by print order (lines
     * that overlap at their width read apart) into [file] with "-paths" before its extension.
     */
    fun pictureOf(file: String, title: String, r: Ridge, w: Written, zs: List<Double>, from: Double, to: Double, share: Double, coloring: TechniqueRender.Coloring) {
        val tp = toolpath(w); val sh = shiftOf(r, w)
        val panels = zs.map { z ->
            val k = tp.nearestLayer(z); val zm = tp.layerZs[k] - tp.layer(k).first().height / 2
            val faces = ArrayList<DoubleArray>()
            for (side in 0..1) { var prev: DoubleArray? = null; var s = from
                while (s <= to + 1e-9) { val f = r.across(s, zm); val c = r.center(s); val nv = r.normal(s)
                    val q = f?.let { doubleArrayOf(c[0] + sh[0] + it[side] * nv[0], c[1] + sh[1] + it[side] * nv[1]) }
                    if (q != null && prev != null) faces += doubleArrayOf(prev[0], prev[1], zm, q[0], q[1], zm)
                    prev = q; s += 0.02 } }
            TechniqueRender.Panel(listOf(String.format(Locale.ROOT, "layer at z %.2f mm", tp.layerZs[k]), "black: the ridge's faces at the layer's middle"),
                tp, { it.layer == k && it.obj != null && it.tool == 0 }, coloring, lines = faces, plasticWidth = true)
        }
        val c0 = r.center(from); val c1 = r.center(to)
        TechniqueRender.render(title, TechniqueRender.View.TOP, panels, reviewRender(file),
            window = TechniqueRender.Window(c0[0] + sh[0] - 0.2..c1[0] + sh[0] + 0.2, minOf(c0[1], c1[1]) + sh[1] - 0.8..maxOf(c0[1], c1[1]) + sh[1] + 0.5), lineShare = share)
    }
    fun picture(file: String, title: String, r: Ridge, w: Written, zs: List<Double>, from: Double, to: Double) {
        pictureOf(file, title, r, w, zs, from, to, 1.0, TechniqueRender.Coloring.FEATURE)
        pictureOf(file.replace(".png", "-paths.png"), "$title, by print order", r, w, zs, from, to, 0.4, TechniqueRender.Coloring.ORDER)
    }

    val flatTwo by lazy { write(listOf(flat.part, flat.against)) }
    val wobblyOne by lazy { write(listOf(wobbly.part)) }
    val dippedOne by lazy { write(listOf(dipped.part)) }

    for ((name, ridge, parts) in listOf(Triple("flat, one part", flat, 1), Triple("flat, beside a second part", flat, 2),
        Triple("wobbly, one part", wobbly, 1), Triple("wobbly, beside a second part", wobbly, 2), Triple("steep, one part", steep, 1),
        Triple("dipped, one part", dipped, 1), Triple("dipped, beside a second part", dipped, 2))) {
        "the ridge ($name): each layer's crest laid as wide as it is, one line where two walls do not fit, no overlap, no fragment" {
            val w = when { ridge === flat && parts == 2 -> flatTwo; ridge === wobbly && parts == 1 -> wobblyOne; ridge === dipped && parts == 1 -> dippedOne
                else -> write(if (parts == 1) listOf(ridge.part) else listOf(ridge.part, ridge.against)) }
            val rd = read(ridge, w)
            val pr = problems(w)
            println("$name: $rd; the pre-print check: ${pr.filter { "outline" in it }.take(3)}")
            (rd.stations > 500) shouldBe true
            rd.faults.shouldBeEmpty()
            pr.shouldBeEmpty()
        }
    }

    "review pictures: the crest layers from above (each ridge's first 3 mm)" {
        picture("thin-ridge-flat-two-part.png", "A ridge narrowing to one line, beside a second part (its first tool's lines)", flat, flatTwo, listOf(1.64, 1.72, 1.80), 0.0, 3.0)
        picture("thin-ridge-wobbly.png", "A ridge as wide as two walls need, its faces uneven by 10 um", wobbly, wobblyOne, listOf(1.64, 1.72, 1.80), 0.0, 3.0)
        picture("thin-ridge-dipped.png", "A ridge lowest in its middle", dipped, dippedOne, listOf(1.96, 2.04, 2.12, 2.20), 1.5, 4.5)
    }

    /** [w]'s g-code with the object's lines on the layer nearest [z] within [reach] mm of the ridge's middle at [s] laid as travels (their E taken out); how many. */
    fun without(w: Written, r: Ridge, z: Double, s: Double, reach: Double): Pair<String, Int> {
        val tp = toolpath(w); val sh = shiftOf(r, w)
        val k = tp.nearestLayer(z); val zm = tp.layerZs[k] - tp.layer(k).first().height / 2
        val f = r.across(s, zm)!!; val mid = (f[0] + f[1]) / 2
        val c = r.center(s); val nv = r.normal(s); val cx = c[0] + sh[0] + mid * nv[0]; val cy = c[1] + sh[1] + mid * nv[1]
        val drop = tp.moves.filter { it.layer == k && it.obj != null && Math.hypot((it.x0 + it.x1) / 2 - cx, (it.y0 + it.y1) / 2 - cy) < reach }.map { it.index }.toSet()
        // the file's extruding moves in order, as the reader counts them: the dropped ones lose their E
        var idx = -1; var x = 0.0; var y = 0.0
        val lines = w.g.lines().map { raw ->
            val l = raw.trim()
            if (!l.startsWith("G1 ")) return@map raw
            val ws = l.substringBefore(';').trim().split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
            val extruding = e > 0 && (nx != x || ny != y)
            x = nx; y = ny
            if (!extruding) return@map raw
            idx++
            if (idx in drop) ws.filter { it[0] != 'E' }.joinToString(" ") else raw
        }
        return lines.joinToString("\n") to drop.size
    }

    "the pre-print check refuses a stretch of the crest with no line where it holds one; control: a crest narrower than the floor left bare passes" {
        val w = wobblyOne
        problems(w).shouldBeEmpty()
        // the lines across 1.5 mm of the ridge at s 4.7 on the layer at z 1.72 (about 0.8 mm wide there) taken out
        val (cut, n) = without(w, wobbly, 1.72, 4.7, 0.75)
        val refused = problems(w, cut)
        println("the crest with $n moves taken out round s 4.7, z 1.72: ${refused.filter { "outline" in it }}")
        (n > 2) shouldBe true
        refused.any { "has no line beside it" in it } shouldBe true
        refused.none { "has no wall along" in it } shouldBe true              // the older rule alone missed it
        // on the layer at z 1.96 the crest is 0.24 .. 0.27 mm wide, wider than the floor a line is surely laid down to
        // (plan.layer.walls.feature.min, 0.15 mm, and the slicing tolerance a side, as `slice` judges it): the walls lay
        // the narrowest line there, and its line across 1.5 mm taken out is refused too
        val (thinCut, m) = without(w, wobbly, 1.96, 8.3, 0.75)
        val refusedThin = problems(w, thinCut)
        println("the crest with $m moves taken out round s 8.3, z 1.96 (0.26 mm wide): ${refusedThin.filter { "outline" in it }}")
        (m > 0) shouldBe true
        refusedThin.any { "has no line beside it" in it } shouldBe true
        // control: the steep ridge with its top cut off 5 um down narrows to nothing along each layer over millimetres
        // (its apex rising 0.16 mm along it), its tips under that floor with no line on them, and passes; judged against a
        // floor near nothing (0.02 mm: as if a line could lie anywhere), those bare tips are refused: the floor is what
        // passes them
        val pw = write(listOf(pointed.part))
        problems(pw).shouldBeEmpty()
        val noFloor = problems(pw, floorMm = 0.02)
        println("the pointed ridge judged with a 0.02 mm floor: ${noFloor.filter { "outline" in it }.take(3)}")
        noFloor.any { "has no line beside it" in it } shouldBe true
    }
})
