package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.mesh.Slicer
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
 * Walls laid by an area's local width: small models whose areas are one to a few lines wide, sliced through Job as `slice`
 * writes them and read back from the g-code by [WallCoverage] against each model's own cut of its mesh. 0.08 mm layers,
 * 0.44 mm lines, three walls (TEST VALUES, as the other slicing tests'); the first layer is not read (it is drawn in).
 *
 * The models (mm):
 *  - the lens beside a second part: a gray lens 2.4 mm long, flat on one side against a white block (a second part on a
 *    second tool), curved on the other, growing from 0.7 to 1.2 mm deep over a dozen layers (z 0.42 to 1.42, on the block's
 *    foot); and the same lens alone on a foot of its own;
 *  - a wedge from nothing to 2 mm wide over 10 mm;
 *  - a strip in ten steps from 0.2 to 2.0 mm wide, each 3 mm long;
 *  - a ring 1 mm wide round a 4 mm hole;
 *  - a blob: an ellipse 2.5 by 1 mm;
 *  - a block 10 by 10 mm (an ordinary area: its walls and infill as they always were).
 */
class WallsByWidthTest : StringSpec({
    /** A solid of [rows] (each a height and its counter-clockwise section, the same number of points in each), capped by fans from each end's middle (star-shaped ends). */
    fun loft(rows: List<Pair<Double, List<DoubleArray>>>): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun p(r: Int, i: Int) = rows[r].second[i].let { doubleArrayOf(it[0], it[1], rows[r].first) }
        val m = rows[0].second.size
        for (r in 0 until rows.size - 1) for (i in 0 until m) {
            val j = (i + 1) % m
            tris += p(r, i) + p(r, j) + p(r + 1, j); tris += p(r, i) + p(r + 1, j) + p(r + 1, i)
        }
        for ((r, up) in listOf(0 to false, rows.size - 1 to true)) {
            val sec = rows[r].second; val z = rows[r].first
            val c = doubleArrayOf(sec.sumOf { it[0] } / m, sec.sumOf { it[1] } / m, z)
            for (i in 0 until m) { val j = (i + 1) % m; tris += if (up) c + p(r, i) + p(r, j) else c + p(r, j) + p(r, i) }
        }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }
    /** Ear clipping of a simple counter-clockwise polygon: index triples. */
    fun ears(poly: List<DoubleArray>): List<IntArray> {
        val idx = poly.indices.toMutableList(); val out = ArrayList<IntArray>()
        fun cross(a: DoubleArray, b: DoubleArray, c: DoubleArray) = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0])
        var guard = 0
        while (idx.size > 3 && guard++ < 100000) {
            var cut = false
            for (k in idx.indices) {
                val a = idx[(k + idx.size - 1) % idx.size]; val b = idx[k]; val c = idx[(k + 1) % idx.size]
                if (cross(poly[a], poly[b], poly[c]) <= 1e-12) continue
                if (idx.any { it != a && it != b && it != c && cross(poly[a], poly[b], poly[it]) >= 0 && cross(poly[b], poly[c], poly[it]) >= 0 && cross(poly[c], poly[a], poly[it]) >= 0 }) continue
                out += intArrayOf(a, b, c); idx.removeAt(k); cut = true; break
            }
            require(cut) { "not a simple counter-clockwise polygon" }
        }
        out += intArrayOf(idx[0], idx[1], idx[2])
        return out
    }
    /** An upright prism of the counter-clockwise polygon [poly] from [z0] to [z1]. */
    fun prism(poly: List<DoubleArray>, z0: Double, z1: Double): TriangleSoup {
        val tris = ArrayList<DoubleArray>(); val n = poly.size
        fun at(i: Int, z: Double) = doubleArrayOf(poly[i][0], poly[i][1], z)
        for (i in 0 until n) { val j = (i + 1) % n; tris += at(i, z0) + at(j, z0) + at(j, z1); tris += at(i, z0) + at(j, z1) + at(i, z1) }
        for (t in ears(poly)) { tris += at(t[0], z1) + at(t[1], z1) + at(t[2], z1); tris += at(t[0], z0) + at(t[2], z0) + at(t[1], z0) }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }
    fun join(vararg s: TriangleSoup) = TriangleSoup(s.flatMap { it.coords.asList() }.toFloatArray(), s.sumOf { it.count })
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) = testBox(x0.toFloat(), y0.toFloat(), z0.toFloat(), x1.toFloat(), y1.toFloat(), z1.toFloat())

    val cx = 100.0; val cy = 100.0
    /** The lens's depth at height [z]: 0.7 mm at z 0.42, 1.2 mm at z 1.42. */
    fun depth(z: Double) = 0.7 + 0.5 * (z - 0.42)
    val lens = loft((0..50).map { r -> val z = 0.42 + r * 0.02
        z to (0 until 61).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(cx + 1.2 * t, cy - depth(z) * (1 - t * t)) } })
    val lensBlock = join(box(cx - 3, cy - 2, 0.0, cx + 3, cy + 3, 0.42), box(cx - 3, cy, 0.42, cx + 3, cy + 3, 1.6))
    val lensAlone = join(box(cx - 1.6, cy - 1.6, 0.0, cx + 1.6, cy + 0.4, 0.42), lens)
    val wedge = prism(listOf(doubleArrayOf(cx - 5, cy), doubleArrayOf(cx + 5, cy - 1), doubleArrayOf(cx + 5, cy + 1)), 0.0, 1.0)
    val strip = prism(run {
        val ws = (1..10).map { 0.2 * it }; val x0 = cx - 15
        val bottom = ws.indices.flatMap { i -> listOf(doubleArrayOf(x0 + 3 * i, cy - ws[i] / 2), doubleArrayOf(x0 + 3 * (i + 1), cy - ws[i] / 2)) }
        val top = ws.indices.reversed().flatMap { i -> listOf(doubleArrayOf(x0 + 3 * (i + 1), cy + ws[i] / 2), doubleArrayOf(x0 + 3 * i, cy + ws[i] / 2)) }
        bottom + top }, 0.0, 1.0)
    val ring = run {
        val n = 120; val tris = ArrayList<DoubleArray>()
        fun pt(r: Double, k: Int, z: Double) = doubleArrayOf(cx + r * Math.cos(2 * Math.PI * k / n), cy + r * Math.sin(2 * Math.PI * k / n), z)
        for (k in 0 until n) { val j = (k + 1) % n
            tris += pt(3.0, k, 0.0) + pt(3.0, j, 0.0) + pt(3.0, j, 1.0); tris += pt(3.0, k, 0.0) + pt(3.0, j, 1.0) + pt(3.0, k, 1.0)
            tris += pt(2.0, k, 0.0) + pt(2.0, k, 1.0) + pt(2.0, j, 1.0); tris += pt(2.0, k, 0.0) + pt(2.0, j, 1.0) + pt(2.0, j, 0.0)
            tris += pt(2.0, k, 1.0) + pt(3.0, k, 1.0) + pt(3.0, j, 1.0); tris += pt(2.0, k, 1.0) + pt(3.0, j, 1.0) + pt(2.0, j, 1.0)
            tris += pt(2.0, k, 0.0) + pt(3.0, j, 0.0) + pt(3.0, k, 0.0); tris += pt(2.0, k, 0.0) + pt(2.0, j, 0.0) + pt(3.0, j, 0.0) }
        TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }
    val blob = prism((0 until 96).map { k -> val a = 2 * Math.PI * k / 96; doubleArrayOf(cx + 1.25 * Math.cos(a), cy + 0.5 * Math.sin(a)) }, 0.0, 1.0)
    val block = box(cx - 5, cy - 5, 0.0, cx + 5, cy + 5, 1.0)
    /** An upright prism of the union of [shapes] (mm, counter-clockwise), which must be one polygon with no hole. */
    fun union(shapes: List<List<DoubleArray>>): List<DoubleArray> {
        val ps = shapes.map { s -> plymeld.geom.Polygon(LongArray(2 * s.size) { k -> Math.round(s[k / 2][k % 2] * 1000) }) }
        val u = plymeld.geom.Booleans.unionPositive(ps).single()
        return (0 until u.size).map { doubleArrayOf(u.x(it) / 1000.0, u.y(it) / 1000.0) }
    }
    fun ellipse(x: Double, y: Double, a: Double, b: Double, n: Int = 72) = (0 until n).map { k -> val t = 2 * Math.PI * k / n; doubleArrayOf(x + a * Math.cos(t), y + b * Math.sin(t)) }
    fun rect(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(doubleArrayOf(x0, y0), doubleArrayOf(x1, y0), doubleArrayOf(x1, y1), doubleArrayOf(x0, y1))
    /** Two blobs 2 x 1.8 mm joined by a neck 0.5 mm wide, 1 mm long. */
    val necked = prism(union(listOf(ellipse(cx - 2, cy, 1.0, 0.9), rect(cx - 1.2, cy - 0.25, cx + 1.2, cy + 0.25), ellipse(cx + 2, cy, 1.0, 0.9))), 0.0, 1.0)
    /** A letter E of strokes 0.9 mm wide, 6 mm tall, 3 mm wide: junctions, corners, and ends. */
    val letter = prism(union(listOf(rect(cx - 1.5, cy - 3, cx - 0.6, cy + 3), rect(cx - 1.5, cy - 3, cx + 1.5, cy - 2.1),
        rect(cx - 1.5, cy - 0.45, cx + 1.2, cy + 0.45), rect(cx - 1.5, cy + 2.1, cx + 1.5, cy + 3))), 0.0, 1.0)
    /** A lens 2.4 mm long, 1.4 mm deep (its tips at about 34 deg to the flat): tips, not corners. */
    val deepLens = prism((0 until 61).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(cx + 1.2 * t, cy - 1.4 * (1 - t * t)) }, 0.0, 1.0)
    /** A blob 2.6 x 1.8 mm narrowing to points at its ends (two parabolas): wider than three lines in its middle. */
    val pointedBlob = prism((0 until 60).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(cx + 1.3 * t, cy - 0.9 * (1 - t * t)) } +
        (0 until 60).map { k -> val t = 1 - 2.0 * k / 60; doubleArrayOf(cx + 1.3 * t, cy + 0.9 * (1 - t * t)) }, 0.0, 1.0)

    /**
     * A block 12 by 10.6 mm with a round hole 6 mm across whose edge comes within [webMm] of the block's front: a web between
     * a hole and the side, too narrow for the set walls of both, in an island wide enough for them elsewhere. Its sides and
     * the hole's are sampled on the same rays from the hole's center (the block is star-shaped about it), the block's corners
     * among them, and joined by quads.
     */
    fun webBlock(webMm: Double): TriangleSoup {
        val r = 3.0; val front = cy - r - webMm; val back = cy + 6.0; val left = cx - 6.0; val right = cx + 6.0
        val corners = listOf(doubleArrayOf(right, front), doubleArrayOf(right, back), doubleArrayOf(left, back), doubleArrayOf(left, front))
            .map { Math.atan2(it[1] - cy, it[0] - cx).let { a -> if (a < 0) a + 2 * Math.PI else a } }
        val angles = ((0 until 144).map { 2 * Math.PI * it / 144 } + corners).sorted()
        fun box(a: Double): DoubleArray {
            val dx = Math.cos(a); val dy = Math.sin(a)
            val t = listOf(if (dx > 1e-12) (right - cx) / dx else Double.MAX_VALUE, if (dx < -1e-12) (left - cx) / dx else Double.MAX_VALUE,
                if (dy > 1e-12) (back - cy) / dy else Double.MAX_VALUE, if (dy < -1e-12) (front - cy) / dy else Double.MAX_VALUE).min()
            return doubleArrayOf(cx + t * dx, cy + t * dy)
        }
        val n = angles.size; val tris = ArrayList<DoubleArray>()
        val outer = angles.map { box(it) }; val inner = angles.map { doubleArrayOf(cx + r * Math.cos(it), cy + r * Math.sin(it)) }
        fun p(q: DoubleArray, z: Double) = doubleArrayOf(q[0], q[1], z)
        for (k in 0 until n) { val j = (k + 1) % n
            tris += p(outer[k], 0.0) + p(outer[j], 0.0) + p(outer[j], 1.0); tris += p(outer[k], 0.0) + p(outer[j], 1.0) + p(outer[k], 1.0)
            tris += p(inner[k], 0.0) + p(inner[k], 1.0) + p(inner[j], 1.0); tris += p(inner[k], 0.0) + p(inner[j], 1.0) + p(inner[j], 0.0)
            tris += p(inner[k], 1.0) + p(outer[k], 1.0) + p(outer[j], 1.0); tris += p(inner[k], 1.0) + p(outer[j], 1.0) + p(inner[j], 1.0)
            tris += p(inner[k], 0.0) + p(outer[j], 0.0) + p(outer[k], 0.0); tris += p(inner[k], 0.0) + p(inner[j], 0.0) + p(outer[j], 0.0) }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }

    class Written(val g: String, val plan: Plan, val setup: Setup)
    /** One object of [parts] (the first on tool 1, a second on tool 2) written through Job. */
    fun write(parts: List<TriangleSoup>, vararg extra: Pair<String, Any>): Written {
        val names = listOf("A", "B").take(parts.size)
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false, "hardware.machine.tm.firmware.arcs" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm", "plan.support.enabled" to false,
            "plan.technique.flat.layer.height" to "0.08 mm", "plan.layer.walls.outer.line.width" to "0.44 mm", "plan.layer.walls.inner.line.width" to "0.44 mm",
            "plan.layer.walls.thickness" to "1.32 mm", *extra,
            models = listOf(plymeld.config.ModelObject("o", names, names.map { if (it == "B") 2 else 1 }, "o.3mf")))
        val setup = base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"),
            names.map { PartSetup(it, if (it == "B") "spool 2" else "spool 1", Values(Entity.INTENT, it)) })))
        val plan = Plan.makeFor(listOf("o" to parts), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE, objects = listOf(Plan.Companion.ObjectLocks(layerHeightMm = 0.08))))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }

    /** One model's reading: each layer's measure (its part 0, tool 0) and the shift from the layer below. */
    class Reading(val name: String, val layers: List<WallCoverage.Measure>, val shifts: List<Double>) {
        fun sum(f: (WallCoverage.Measure) -> Double) = layers.sumOf(f)
        fun max(f: (WallCoverage.Measure) -> Double) = layers.maxOf(f)
        override fun toString() = String.format(Locale.ROOT,
            "%s: %d layers; uncovered %.4f mm2 (widest %.3f mm; wider than the floor %.4f mm2); outside %.4f mm2 (farthest %.3f mm); paths %d (shortest %.2f mm, shortest move %.3f mm); doubled %.4f mm2; largest shift %.3f mm",
            name, layers.size, sum { it.uncoveredMm2 }, max { it.widestUncoveredMm }, sum { it.uncoveredWideMm2 }, sum { it.outsideMm2 }, max { it.outsideMaxMm },
            layers.sumOf { it.paths }, layers.minOf { it.shortestPathMm }, layers.minOf { it.shortestMoveMm }, sum { it.doubledMm2 }, shifts.maxOrNull() ?: 0.0)
    }

    /** [w] read on its layers whose middle lies between [zFrom] and [zTo] (g-code Z), part 0 by tool 0. */
    fun reading(name: String, w: Written, zFrom: Double, zTo: Double, cellMm: Double = 0.005, window: WallCoverage.Window? = null): Reading {
        val st = SliceSettings.of(w.setup)
        val read = WallCoverage.read(w.g, st.filamentDiameterMm, st.flowRatio)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val part = w.plan.parts[0]
        val floor = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0
        val out = ArrayList<WallCoverage.Measure>(); val shifts = ArrayList<Double>()
        var prev: WallCoverage.Layer? = null; var prevWin: WallCoverage.Window? = null
        for (l in read.layers) {
            val mid = l.z - l.h / 2
            if (mid < zFrom || mid > zTo) continue
            val section = Slicer.slice(part, doubleArrayOf(mid + zBase))[0].loops
            if (section.isEmpty()) continue
            val win = window ?: WallCoverage.Window.around(section, 0.6, cellMm)
            out += WallCoverage.measure(read, l, section, win, 0, floor, st.resolutionUm / 1000.0, st.infillWallOverlap,
                map = reviewRender(String.format(Locale.ROOT, "walls-by-width-map-%s-z%.2f.png", name.replace(' ', '-'), l.z)))
            if (prev != null) {
                val both = WallCoverage.Window(minOf(win.x0, prevWin!!.x0), minOf(win.y0, prevWin.y0), maxOf(win.x1, prevWin.x1), maxOf(win.y1, prevWin.y1))
                shifts += WallCoverage.shift(prev, l, both, 0)
            }
            prev = l; prevWin = win
        }
        return Reading(name, out, shifts)
    }

    /** The webs between a hole and the block's front (webBlock), by width: room for the two outer lines and a little, for them and a line between, for them and an inner loop each and a line between. */
    val webs = listOf("web 1.0 mm", "web 1.6 mm", "web 2.2 mm")
    val models = HashMap<String, Pair<Written, Reading>>()
    fun model(name: String): Pair<Written, Reading> = models.getOrPut(name) {
        val w = when (name) {
            "lens beside a second part" -> write(listOf(lens, lensBlock))
            "lens alone" -> write(listOf(lensAlone))
            "wedge" -> write(listOf(wedge))
            "strip" -> write(listOf(strip))
            "ring" -> write(listOf(ring))
            "blob" -> write(listOf(blob))
            "two blobs and a neck" -> write(listOf(necked))
            "letter E" -> write(listOf(letter))
            "deep lens" -> write(listOf(deepLens))
            "pointed blob" -> write(listOf(pointedBlob))
            in webs -> write(listOf(webBlock(name.removePrefix("web ").removeSuffix(" mm").toDouble())))
            else -> write(listOf(block))
        }
        val r = when (name) {
            "lens beside a second part", "lens alone" -> reading(name, w, 0.42, 1.42)
            "strip" -> reading(name, w, 0.25, 1.0, cellMm = 0.01)
            // the web alone: the front's middle, where the hole comes within the web's width of it (the infill's gaps
            // beyond are no concern here)
            in webs -> reading(name, w, 0.25, 1.0, window = WallCoverage.Window(cx - 1.2, cy - 3.0 - name.removePrefix("web ").removeSuffix(" mm").toDouble() - 0.1, cx + 1.2, cy - 2.7))
            else -> reading(name, w, 0.25, 1.0)
        }
        w to r
    }
    val names = listOf("lens beside a second part", "lens alone", "wedge", "strip", "ring", "blob", "two blobs and a neck", "letter E",
        "deep lens", "pointed blob", "block")

    /** The pre-print check as `slice` runs it on a lone object (GcodeCheck, ModelCheck). */
    fun problems(w: Written, fuzzMm: Double = 0.0): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val sf = ModelCheck.Surface(w.plan.parts, zBase, w.g)
        val st = SliceSettings.of(w.setup)
        return GcodeCheck.check(w.g, Expectations.forSetup(w.setup, sf::at)) +
            ModelCheck.check(w.g, w.plan.parts, zBase, surface = sf, featureMinMm = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0, fuzzMm = fuzzMm,
                infillMinMm = 2 * st.innerWallWidthUm / 1000.0)
    }

    /*
     * Fuzzy skin moves the outer wall's points in and out by up to its thickness, opening gaps between it and the next loop;
     * the pre-print check reads each wall that much wider for the hole rule (as `slice` passes it), and these pass. Control:
     * without the allowance the same files are refused for holes (the gaps are there: 0.25 mm wide on the block and the lens).
     */
    "fuzzy skin: the block and the lens pass the pre-print check" {
        for ((n, parts) in listOf("block" to listOf(block), "lens alone" to listOf(lensAlone))) {
            val w = write(parts, "plan.faces.side.technique" to "fuzzy_skin")
            val st = SliceSettings.of(w.setup)
            withClue(n) { (st.fuzzySkin != "none") shouldBe true; w.g.contains("Outer wall") shouldBe true }
            val fuzz = st.fuzzyThicknessUm / 1000.0
            val with = problems(w, fuzz)
            val without = problems(w).filter { it.contains("a hole inside the walls") }
            println("fuzzy skin ($fuzz mm), $n: ${with.size} problem(s) ${with.take(3)}; without the allowance ${without.size} hole(s) ${without.take(2)}")
            withClue(n) { with.shouldBeEmpty(); without.isNotEmpty() shouldBe true }
        }
    }

    "the pre-print check passes every model (the strip's first layer aside: see below)" {
        for (n in names) {
            val pr = problems(model(n).first)
            println("$n: the pre-print check: ${pr.size} problem(s) ${pr.take(5)}")
            // the strip's first layer: drawn in by the squish compensation, its 0.2 mm step is narrower than the thinnest
            // line laid, while the check reads the outline drawn in less (an open item of the check, as before this rule)
            pr.filter { !(n == "strip" && it.startsWith("layer at z 0.200:")) }.shouldBeEmpty()
        }
    }

    /*
     * The hole rule's control on a real planner's output: the lens alone as the walls before walls by local width laid it
     * (a loop and a line down its middle; the file that version wrote for this model, kept as a test fixture), whose loop's
     * turn and line's end left a hole 0.25 to 0.29 mm wide on its top layers (z 1.32 and 1.42) that the outline rules
     * passed. Its first layer is refused as well, rightly: the foot laid as one loop round an empty middle 2 by 0.8 mm. The
     * same check on this version's output passes (the test above).
     */
    "control: the old walls' hole inside the lens is refused by the pre-print check" {
        val w = model("lens alone").first
        val old = WallsByWidthTest::class.java.getResource("lens-alone-old-walls.gcode")!!.readText()
        val st = SliceSettings.of(w.setup)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val pr = ModelCheck.check(old, w.plan.parts, zBase, featureMinMm = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0, infillMinMm = 2 * st.innerWallWidthUm / 1000.0)
        println("the old walls of the lens alone: ${pr.size} problem(s) $pr")
        val holes = pr.filter { it.contains("a hole inside the walls") }
        (holes.isNotEmpty()) shouldBe true
        // the outline rules alone passed it: every refusal is a hole
        (pr.size == holes.size) shouldBe true
    }

    /*
     * The limits (mm, mm²), each reading on every model's read layers:
     *  - nothing uncovered wider than the floor (feature.min and the slicing tolerance a side), and no uncovered spot wider
     *    than 0.105 mm: what the round ends of beads and corners sharper than a bead can reach leave (the floor is 0.18);
     *  - no plastic farther outside than 0.09: the narrowest line laid in the narrowest place a line is laid in stands out
     *    (0.3 - 0.15) / 2 = 0.075, and the slicing tolerance;
     *  - no path shorter than 0.5 mm (no stub, hook or tiny loop), and at most two paths a layer on the lens;
     *  - plastic laid twice (beyond the usual overlap) at most 0.2 mm² a layer: the round start of a bead coming in where
     *    the count changes, beside the bead that carries on;
     *  - the growing lens: consecutive layers' lines no farther apart than 0.2 mm, but at one switch.
     */
    for (n in names.filter { it != "block" }) "$n: covered edge to edge, nothing far outside, no fragment, little laid twice" {
        val r = model(n).second
        for (m in r.layers) {
            withClue("$n, $m") {
                (m.uncoveredWideMm2 == 0.0) shouldBe true
                (m.widestUncoveredMm <= 0.105) shouldBe true
                (m.outsideMaxMm <= 0.09) shouldBe true
                (m.shortestPathMm >= 0.5) shouldBe true
                (m.doubledMm2 <= 0.2) shouldBe true
                if (n.startsWith("lens")) (m.paths <= 2) shouldBe true
            }
        }
    }

    /*
     * A narrow part of an island wide enough for its walls elsewhere: the web between a hole and the block's front. The set
     * loops that fit there run on through it, and what they leave between them is laid by its width, with no hole the
     * pre-print check refuses and nothing uncovered wider than the floor; at most 0.2 mm² laid twice a layer (as above).
     * The 1.0 and 2.2 mm webs also keep every uncovered spot within 0.105 mm, as the narrow islands do. The 1.6 mm web does
     * not: where its wide middle line turns down into the corner, between that line, the inner loop's turn and the hole's
     * loop, a pocket up to 0.14 mm wide stays bare. It is narrower than the floor, so the fork that would reach into it is
     * trimmed (a line laid there would lie on its neighbors), and no rule yet widens three lines into a pocket the way two
     * loops share a slit; that web is held to the floor alone.
     */
    "a web between a hole and the side is laid edge to edge" {
        val checked = webs.map { n -> problems(model(n).first).also { pr -> println("$n: the pre-print check: ${pr.size} problem(s) ${pr.take(3)}"); println(model(n).second) } }
        for ((i, n) in webs.withIndex()) {
            val (_, r) = model(n)
            val pr = checked[i]
            withClue(n) { pr.shouldBeEmpty() }
            for (m in r.layers) withClue("$n, $m") {
                (m.uncoveredWideMm2 == 0.0) shouldBe true
                if (n != "web 1.6 mm") (m.widestUncoveredMm <= 0.105) shouldBe true
                (m.doubledMm2 <= 0.2) shouldBe true
            }
        }
    }

    "the growing lens changes from one line to two once, both sides of the change covered" {
        for (n in listOf("lens beside a second part", "lens alone")) {
            val r = model(n).second
            val jumps = r.shifts.withIndex().filter { it.value > 0.2 }
            println("$n: shifts ${r.shifts.joinToString { String.format(Locale.ROOT, "%.3f", it) }}")
            withClue(n) { (jumps.size <= 1) shouldBe true }
            for (j in jumps) for (m in listOf(r.layers[j.index], r.layers[j.index + 1])) withClue("$n, $m") { (m.uncoveredWideMm2 == 0.0) shouldBe true }
        }
    }

    "the harness's readings (development report)" {
        for (n in names) {
            val (w, r) = model(n)
            println(r)
            if (n.startsWith("lens")) for ((k, m) in r.layers.withIndex()) println("  $m; shift from below ${if (k == 0) "-" else String.format(Locale.ROOT, "%.3f", r.shifts[k - 1])}")
            java.nio.file.Files.writeString(reviewRender("walls-by-width-${n.replace(' ', '-')}.gcode"), w.g)
        }
    }

    /** A picture of [w]'s part-0 lines (tool 0) on the layers nearest [zs] from above, at their width from their plastic, the model's cut over them. */
    fun picture(file: String, title: String, w: Written, zs: List<Double>) {
        val st = SliceSettings.of(w.setup)
        val t = TechniqueRender.read(w.g)
        val tp = TechniqueRender.Toolpath(t.moves, t.layerZs, t.text, t.pausedBefore, st.filamentDiameterMm)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        val panels = zs.map { z ->
            val k = tp.nearestLayer(z); val h = tp.layer(k).first().height; val mid = tp.layerZs[k] - h / 2
            val cut = w.plan.parts.flatMap { p -> Slicer.slice(p, doubleArrayOf(mid + zBase))[0].loops }
            val own = Slicer.slice(w.plan.parts[0], doubleArrayOf(mid + zBase))[0].loops
            for (p in own) { val b = p.bounds(); bx0 = minOf(bx0, b[0] / 1000.0); by0 = minOf(by0, b[1] / 1000.0); bx1 = maxOf(bx1, b[2] / 1000.0); by1 = maxOf(by1, b[3] / 1000.0) }
            val lines = cut.flatMap { p -> (0 until p.size).map { i -> val j = (i + 1) % p.size
                doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, mid, p.x(j) / 1000.0, p.y(j) / 1000.0, mid) } }
            TechniqueRender.Panel(listOf(String.format(Locale.ROOT, "layer at z %.2f mm", tp.layerZs[k]), "black: the model's cut at the layer's middle"),
                tp, { it.layer == k && it.obj != null && it.tool == 0 }, TechniqueRender.Coloring.ORDER, lines = lines, plasticWidth = true)
        }
        TechniqueRender.render(title, TechniqueRender.View.TOP, panels, reviewRender(file),
            window = TechniqueRender.Window(bx0 - 0.3..bx1 + 0.3, by0 - 0.3..by1 + 0.3), lineShare = 1.0)
    }

    "review pictures: each model's lines from above at their width" {
        picture("walls-by-width-lens.png", "The lens beside a second part: four layers round where it grows past two lines", model("lens beside a second part").first, listOf(0.52, 0.60, 0.84, 1.40))
        picture("walls-by-width-wedge.png", "A wedge from nothing to 2 mm", model("wedge").first, listOf(0.6))
        picture("walls-by-width-strip.png", "A strip in steps from 0.2 to 2.0 mm", model("strip").first, listOf(0.6))
        picture("walls-by-width-ring.png", "A ring 1 mm wide", model("ring").first, listOf(0.6))
        picture("walls-by-width-blob.png", "A blob 2.5 by 1 mm", model("blob").first, listOf(0.6))
        picture("walls-by-width-two-blobs-and-a-neck.png", "Two blobs joined by a neck", model("two blobs and a neck").first, listOf(0.6))
        picture("walls-by-width-letter-E.png", "A letter E of strokes 0.9 mm wide", model("letter E").first, listOf(0.6))
        picture("walls-by-width-deep-lens.png", "A lens 2.4 mm long, 1.4 mm deep", model("deep lens").first, listOf(0.36, 0.6))
        picture("walls-by-width-pointed-blob.png", "A blob narrowing to points", model("pointed blob").first, listOf(0.6))
        for (n in webs) picture("walls-by-width-${n.replace(' ', '-')}.png", "A $n between a hole and the side", model(n).first, listOf(0.6))
    }

    /** [g] with every extrusion that starts and ends inside the box (mm) laid as a travel instead (its E taken out). */
    fun blanked(g: String, x0: Double, y0: Double, x1: Double, y1: Double): String {
        var x = Double.NaN; var y = Double.NaN
        fun inBox(px: Double, py: Double) = px in x0..x1 && py in y0..y1
        return g.lines().joinToString("\n") { line ->
            val ws = line.substringBefore(';').trim().split(Regex("\\s+"))
            if (ws.isEmpty() || ws[0] !in setOf("G0", "G1")) return@joinToString line
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E')
            val out = if (e != null && e > 0 && inBox(x, y) && inBox(nx, ny)) ws.filter { it.isEmpty() || it[0] != 'E' }.joinToString(" ") else line
            x = nx; y = ny
            out
        }
    }

    /*
     * A finger of one part hanging into a second part off a band of the first, joined to the band by a neck too narrow for
     * the finger's loop and about as long as it is wide: the neck is a pocket between the band's loop, the finger's loop and
     * the other part, inside the object. Its line is a short outer line beside the other part, often shorter than its width
     * (the rule that leaves such a dot out on the object's surface), but not on the surface: no dot shows there, and left
     * out it left the neck bare, a hole the pre-print check refused on 6 of these 23 layers (on a real plate, a hole 0.45 mm
     * wide between letters). The pre-print check passes it. Control: the same file with the lines in the neck taken out
     * is refused for a hole there.
     */
    "a finger of one part inside another, its neck a short pocket: laid, no hole; control: the neck's lines taken out are refused" {
        // the finger: 1 mm wide, 3 mm long, its neck 0.5 mm long and, layer by layer, 0.36 to 0.58 mm wide (a slab a layer:
        // the layers' tops are 0.2 mm, then every 0.08 mm)
        val fh = 0.5; val nl = 0.5; val slabs = 23
        fun finger(nh: Double) = listOf(doubleArrayOf(cx - nh, cy), doubleArrayOf(cx - nh, cy - nl), doubleArrayOf(cx - fh, cy - nl), doubleArrayOf(cx - fh, cy - 3),
            doubleArrayOf(cx + fh, cy - 3), doubleArrayOf(cx + fh, cy - nl), doubleArrayOf(cx + nh, cy - nl), doubleArrayOf(cx + nh, cy))
        val zs = listOf(0.0) + (0 until slabs).map { 0.2 + 0.08 * it }
        val nhs = (0 until slabs).map { 0.18 + 0.005 * it }
        val gray = join(*(0 until slabs).map { k -> prism(listOf(doubleArrayOf(cx - 6, cy)) + finger(nhs[k]) +
            listOf(doubleArrayOf(cx + 6, cy), doubleArrayOf(cx + 6, cy + 4), doubleArrayOf(cx - 6, cy + 4)), zs[k], zs[k + 1]) }.toTypedArray())
        val white = join(*(0 until slabs).map { k -> prism(listOf(doubleArrayOf(cx - 6, cy - 4), doubleArrayOf(cx + 6, cy - 4), doubleArrayOf(cx + 6, cy)) +
            finger(nhs[k]).reversed() + listOf(doubleArrayOf(cx - 6, cy)), zs[k], zs[k + 1]) }.toTypedArray())
        val w = write(listOf(gray, white))
        java.nio.file.Files.writeString(reviewRender("walls-by-width-finger.gcode"), w.g)
        val pr = problems(w)
        println("a finger inside a second part: the pre-print check: ${pr.size} problem(s) ${pr.take(5)}")
        pr.shouldBeEmpty()
        // control: the finger's tip (where it is under 0.6 mm wide) with its lines taken out
        val shift = w.plan.layout.placed[0].parts[0].bounds().let { b -> doubleArrayOf(b[0] - (cx - 6), b[1] - (cy - 3)) }
        val bare = blanked(w.g, cx - 0.35 + shift[0], cy - 0.6 + shift[1], cx + 0.35 + shift[0], cy - 0.05 + shift[1])
        val prBare = problems(Written(bare, w.plan, w.setup)).filter { it.contains("a hole inside the walls") }
        println("control, the tip's lines taken out: ${prBare.size} hole(s) ${prBare.take(3)}")
        prBare.isNotEmpty() shouldBe true
    }
})
