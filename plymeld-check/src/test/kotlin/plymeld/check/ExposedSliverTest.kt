package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.check.ModelCheck
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.gcode.KlipperWriter
import plymeld.mesh.TriangleSoup
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * A sliver of one filament on the object's surface beside another filament (plan.layer.sliver.at_surface): two parts on two
 * tools, written through Job as `slice` writes them, read back from the g-code (each line's width from its plastic), and the
 * models' faces from their own formulas, never the slicer's reading of the mesh (the models are prisms along Y, so every
 * layer's faces are the same along it). Layers 0.08 mm (TEST VALUES, as the other slicing tests').
 *
 *  - the exposed wedge: a white block (part B, filament 2, T1) whose front face leans back at 62.5 deg from level, and above
 *    z 1.49 mm (a layer's middle then falls where the wedge is 0.13 mm wide, under feature.min) a gray wedge (part A, filament 1, T0) on that face, leaning out over it at 55 deg from level, so it starts at
 *    zero width on the white's face and the object's surface runs from the white's face onto the gray's. By default (own):
 *    no white line's bead (anywhere along the front, y 101 to 109) stands out of the white's face by more than [ALLOW]; the
 *    white's front edge never steps out from one layer to the next (its face leans back) by more than [ALLOW], from the
 *    second layer on (the first is drawn in); the gray lays a line inside the wedge (read at y = 105) on every layer
 *    where the wedge, at the layer's middle, is as wide as the walls surely lay a line in (plan.layer.walls.feature.min and
 *    the slicing tolerance a side), and none where it is narrower than feature.min less the tolerance a side (left empty).
 *    Control: with at_surface = neighbor (the earlier behavior) the white's beads stand out over the wedge's place and
 *    step out, and these readings fail;
 *  - a buried sliver: a white block beside a gray one, a gray fin 0.1 mm thick in the white against the gray, inside the
 *    object: it still goes to the white, whose wall then reaches the gray block's face. Control: a fin 0.5 mm thick stays gray
 *    (it is no sliver), and the white's wall stops at the fin;
 *  - the pre-print check (ModelCheck with each part's tool, as `slice` runs it): the default's file reads back with no
 *    problem; the neighbor file, read as the default's (no hand-over allowed on the surface), is refused for the white over
 *    the wedge, and read with the neighbor's own allowance it passes; white lines planted along the wedge on the default's
 *    file are refused: one inside the wedge (both tool rules), and one whose middle lies within the check's tolerance of
 *    the white's face but whose bead covers the wedge to the gray's front (the surface rule alone; the rule reading a
 *    line's middle passes it).
 */
class ExposedSliverTest : StringSpec({
    val cot62 = 1 / Math.tan(Math.toRadians(62.5)); val cot55 = 1 / Math.tan(Math.toRadians(55.0))
    val x0 = 40.0; val z0 = 1.49; val top = 3.0
    /** The white's front face at height [z]. */
    fun white(z: Double) = x0 + z * cot62
    /** The gray wedge's front at height [z] (above [z0]). */
    fun gray(z: Double) = white(z0) - (z - z0) * cot55

    /** A prism along Y from [y0] to [y1] (mm) of the X-Z [profile] (convex, counter-clockwise seen from -Y), every face outward. */
    fun prism(profile: List<Pair<Double, Double>>, y0: Double = 100.0, y1: Double = 110.0): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun at(i: Int, y: Double) = doubleArrayOf(profile[i].first, y, profile[i].second)
        val n = profile.size
        for (i in 1 until n - 1) { tris += at(0, y0) + at(i, y0) + at(i + 1, y0); tris += at(0, y1) + at(i + 1, y1) + at(i, y1) }
        for (i in 0 until n) { val j = (i + 1) % n; tris += at(i, y0) + at(j, y1) + at(j, y0); tris += at(i, y0) + at(i, y1) + at(j, y1) }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }
    val wedgeParts = listOf(
        prism(listOf(white(z0) to z0, white(top) to top, gray(top) to top)),                    // A: the gray wedge
        prism(listOf(x0 to 0.0, 60.0 to 0.0, 60.0 to top, white(top) to top)))                 // B: the white block

    class Written(val g: String, val plan: Plan, val setup: Setup)
    /** One object of [parts] (spools: A, and C when given, filament 1; B filament 2) written through Job, [extra] over the test setup. */
    fun write(parts: List<TriangleSoup>, vararg extra: Pair<String, Any>): Written {
        val names = listOf("A", "B", "C").take(parts.size)
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false, "hardware.machine.tm.firmware.arcs" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm", "plan.support.enabled" to false, "plan.technique.flat.layer.height" to "0.08 mm", *extra,
            models = listOf(plymeld.config.ModelObject("o", names, names.map { if (it == "B") 2 else 1 }, "o.3mf")))
        val setup = base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"),
            names.map { PartSetup(it, if (it == "B") "spool 2" else "spool 1", Values(Entity.INTENT, it)) })))
        val plan = Plan.makeFor(listOf("o" to parts), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE, objects = listOf(Plan.Companion.ObjectLocks(layerHeightMm = 0.08))))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }

    /** A printed segment of the model: its layer (top, thickness), its tool, its ends, its width from its plastic. */
    class Line(val layerZ: Double, val layerH: Double, val tool: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double) {
        val mid get() = layerZ - layerH / 2
    }
    fun lines(w: Written): List<Line> {
        val s = SliceSettings.of(w.setup)
        val af = Math.PI * s.filamentDiameterMm * s.filamentDiameterMm / 4
        val out = ArrayList<Line>()
        var x = Double.NaN; var y = Double.NaN; var lz = Double.NaN; var lh = 0.0; var tool = "T0"; var feature = ""
        for ((i, raw) in w.g.lines().withIndex()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith(";HEIGHT:") -> lh = l.removePrefix(";HEIGHT:").trim().toDouble()
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:").trim()
                Regex("^T\\d+$").matches(l) -> tool = l
            }
            val c = l.substringBefore(';').trim()
            require(!c.startsWith("G2 ") && !c.startsWith("G3 ")) { "line ${i + 1}: an arc (the test writes none)" }
            if (!c.startsWith("G1 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
            val len = Math.hypot(nx - x, ny - y)
            if (!lz.isNaN() && e > 0 && len > 0 && feature in MODEL) {
                // the bead's cross-section from its plastic: h (w - h (1 - pi/4)) = E A / (L flow)
                val area = e * af / (len * s.flowRatio)
                out += Line(lz, lh, tool, x, y, nx, ny, area / lh + lh * (1 - Math.PI / 4))
            }
            x = nx; y = ny
        }
        return out
    }
    /** A line where it crosses y = [cutY]: its layer (top, thickness), its tool, its middle's x, its width. */
    class Cross(val layerZ: Double, val layerH: Double, val tool: String, val x: Double, val w: Double) {
        val back get() = x + w / 2
        val mid get() = layerZ - layerH / 2
    }
    fun crossings(w: Written, cutY: Double = 105.0): List<Cross> = lines(w).filter { (it.y0 - cutY) * (it.y1 - cutY) < 0 }
        .map { Cross(it.layerZ, it.layerH, it.tool, it.x0 + (cutY - it.y0) / (it.y1 - it.y0) * (it.x1 - it.x0), it.w) }

    /** What the exposed-wedge readings found: a list of failures (empty = all hold), and the table they read. */
    class Reading(val failures: List<String>, val table: List<String>)
    fun read(w: Written): Reading {
        val s = SliceSettings.of(w.setup)
        val res = s.resolutionUm / 1000.0; val fmin = s.minFeatureUm / 1000.0
        val fails = ArrayList<String>(); val table = ArrayList<String>()
        val cuts = crossings(w).groupBy { it.layerZ }
        // the white's lines along the object's front (away from the prism's ends, y 100 and 110), anywhere along it
        val whiteLines = lines(w).filter { it.tool == "T1" }.mapNotNull { l ->
            // the part of the line within y 101..109
            val (ya, yb) = minOf(l.y0, l.y1) to maxOf(l.y0, l.y1)
            if (yb < 101.0 || ya > 109.0) return@mapNotNull null
            fun xAt(y: Double) = if (l.y1 == l.y0) minOf(l.x0, l.x1) else l.x0 + (y.coerceIn(ya, yb) - l.y0) / (l.y1 - l.y0) * (l.x1 - l.x0)
            val xs = if (l.y1 == l.y0) listOf(l.x0, l.x1) else listOf(xAt(maxOf(ya, 101.0)), xAt(minOf(yb, 109.0)))
            Line(l.layerZ, l.layerH, l.tool, xs.min(), 0.0, xs.min(), 0.0, l.w)
        }.groupBy { it.layerZ }
        var lastFront = Double.NaN
        for ((z, cs) in cuts.toSortedMap()) {
            val mid = cs[0].mid
            if (mid > top) continue
            // the object's front lines: within 1.5 mm of the white's face
            val grays = cs.filter { it.tool == "T0" && it.x < white(mid) + 1.5 }
            val wedge = if (mid > z0) white(mid) - gray(mid) else 0.0
            // the white's frontmost plastic on the layer: its lines' least x less half their width
            val front = whiteLines[z].orEmpty().filter { minOf(it.x0, it.x1) < white(mid) + 1.5 }.minOfOrNull { minOf(it.x0, it.x1) - it.w / 2 } ?: Double.NaN
            table += String.format(java.util.Locale.ROOT, "z %.2f: white face %.3f, wedge %.3f wide (front %.3f); white lines' front %.3f; gray lines %s", z, white(mid), wedge,
                if (mid > z0) gray(mid) else Double.NaN, front, grays.joinToString { String.format(java.util.Locale.ROOT, "%.3f w %.3f", it.x, it.w) })
            if (!front.isNaN() && front < white(mid) - ALLOW) fails += "z $z: a white bead stands ${white(mid) - front} mm out of the white's face"
            if (!front.isNaN() && !lastFront.isNaN() && lastFront - front > ALLOW) fails += "z $z: the white's front steps out ${lastFront - front} mm"
            // the first layer's outline is drawn in (hardware.plate.first_layer.squish): the steps are read from the second on
            if (!front.isNaN() && z > cuts.keys.min()) lastFront = front
            if (mid > z0 && wedge >= fmin + 2 * res && grays.none { it.x > gray(mid) - ALLOW && it.x < white(mid) + ALLOW })
                fails += "z $z: no gray line in a wedge $wedge mm wide"
            if (mid > z0 && wedge < fmin - 2 * res && grays.isNotEmpty()) fails += "z $z: a gray line in a wedge $wedge mm wide"
        }
        return Reading(fails, table)
    }

    val own by lazy { write(wedgeParts) }
    val neighbor by lazy { write(wedgeParts, "plan.layer.sliver.at_surface" to "neighbor") }

    "the exposed wedge: no white over it, the white's front steps out no more than its face, the gray from its first line; control: neighbor fails" {
        val r = read(own)
        println("default (own):\n  " + r.table.joinToString("\n  "))
        r.failures.shouldBeEmpty()
        // probed: the wedge has layers on both sides of the thin-line threshold, and gray lines
        (crossings(own).count { it.tool == "T0" } > 10) shouldBe true
        val n = read(neighbor)
        println("control (neighbor):\n  " + n.table.joinToString("\n  ") + "\n  failures: " + n.failures)
        n.failures.any { "stands" in it } shouldBe true
        n.failures.any { "steps out" in it } shouldBe true
    }

    /** The pre-print check's model rules, with each part's tool, as `slice` runs them ([surfaceHandOverMm] as slice passes it). */
    fun problems(w: Written, g: String = w.g, surfaceHandOverMm: Double = 0.0): List<String> {
        val s = SliceSettings.of(w.setup)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        // part B (the second) on T1, the others on T0
        return ModelCheck.check(g, w.plan.parts, zBase, partTools = w.plan.parts.indices.map { if (it == 1) 1 else 0 },
            featureMinMm = (s.minFeatureUm + 2 * s.resolutionUm) / 1000.0, surfaceHandOverMm = surfaceHandOverMm)
    }

    "the check: the default's file passes; the neighbor's is refused unless read with its allowance; a planted white line on the wedge is refused" {
        val p = problems(own)
        println("own: $p")
        p.shouldBeEmpty()
        val asOwn = problems(neighbor)
        println("neighbor read as own: $asOwn")
        asOwn.any { "surface laid by the wrong tool" in it } shouldBe true
        val allowance = SliceSettings.of(neighbor.setup).sliverHandOverUm / 1000.0
        problems(neighbor, surfaceHandOverMm = allowance).filter { "wrong tool" in it }.shouldBeEmpty()
        // planted: a white (T1) outer wall 0.44 mm wide along the wedge on a layer where it is wider than [wedge], its middle at
        // [x] of the layer's middle
        fun plant(wedge: Double, x: (Double) -> Double): String {
            val lines = own.g.lines()
            val layerZ = crossings(own).map { it.layerZ }.distinct().first { z -> val mid = z - 0.04; mid > z0 && white(mid) - gray(mid) > wedge }
            val at = lines.indexOfFirst { it.startsWith(";Z:") && Math.abs(it.removePrefix(";Z:").trim().toDouble() - layerZ) < 1e-6 }
            // the layer's first extruding move by T1 (the tool in force), and the feature and width it is laid with
            var tool = ""; var type = ""; var width = ""; var e1 = -1
            for (i in lines.indices) {
                val l = lines[i].trim()
                if (Regex("^T\\d+$").matches(l)) tool = l
                if (l.startsWith(";TYPE:")) type = l
                if (l.startsWith(";WIDTH:")) width = l
                if (i > at && tool == "T1" && l.startsWith("G1 ") && " X" in l && Regex(" E[0-9.]").containsMatchIn(l)) { e1 = i; break }
            }
            fun coord(l: String, c: Char) = l.split(' ').first { it.startsWith(c.toString()) }.substring(1)
            val gx = x(layerZ - 0.04)
            // after it: a travel to the wedge, the line along it (8 mm, 0.44 mm wide at 0.08 mm: 0.112 mm of filament), a
            // travel back, the feature and width restored
            return (lines.subList(0, e1 + 1) + listOf("G1 X%.3f Y101 F6000".format(java.util.Locale.ROOT, gx), ";TYPE:Outer wall", ";WIDTH:0.44",
                "G1 X%.3f Y109 E0.112".format(java.util.Locale.ROOT, gx), "G1 X${coord(lines[e1], 'X')} Y${coord(lines[e1], 'Y')} F6000", type, width) +
                lines.subList(e1 + 1, lines.size)).joinToString("\n")
        }
        // in the wedge (0.6 mm wide), its middle 0.2 mm in from the gray's front: both tool rules refuse it
        val inside = problems(own, plant(0.6) { gray(it) + 0.2 })
        println("planted in the wedge: $inside")
        inside.any { "surface laid by the wrong tool" in it } shouldBe true
        // its middle 0.05 mm out of the white's face, within the check's tolerance of the white, its bead over the whole
        // wedge (0.2 mm wide) to the gray's front: the tool rule that reads a line's middle passes it, the surface rule does not
        val edge = problems(own, plant(0.2) { white(it) - 0.05 })
        println("planted at the white's face: $edge")
        edge.any { "printed by the wrong tool" in it } shouldBe false
        edge.any { "surface laid by the wrong tool" in it } shouldBe true
    }

    "a buried sliver still goes to its neighbor; control: a fin wide enough for a line stays its own" {
        /** A white block, a gray block beside it, and a gray fin [t] mm thick in the white against the gray, inside the object. */
        fun buried(t: Double) = write(listOf(testBox(50f, 100f, 0f, 60f, 110f, 2f), testBox(40f, 100f, 0f, 50f, 110f, 2f),
            testBox((50 - t).toFloat(), 102f, 0.4f, 50f, 108f, 1.6f)), "job.part_overlap" to "lower_filament")
        /** On the fin's layers, how far the white's lines reach toward the gray block (their back edge at the cut), the least. */
        fun reach(w: Written) = crossings(w).filter { it.mid in 0.5..1.5 && it.tool == "T1" }.groupBy { it.layerZ }.values.minOf { cs -> cs.maxOf { it.back } }
        val thin = reach(buried(0.1)); val wide = reach(buried(0.5))
        println("the white's reach on the fin's layers: fin 0.1 mm ${thin}, fin 0.5 mm $wide (the gray block's face at 50)")
        thin shouldBeGreaterThan 50.0 - ALLOW
        wide shouldBeLessThan 49.5 + ALLOW
    }
}) {
    companion object {
        /** How far a reading may differ from the model (mm): rounding of the slice and the file. Claude's choice. */
        const val ALLOW = 0.03
        /** The model's own lines (not the purge tower, brim, or support). */
        val MODEL = setOf("Outer wall", "Inner wall", "Overhang wall", "Sparse infill", "Internal solid infill", "Solid infill",
            "Top surface", "Bottom surface", "Bridge", "Internal Bridge", "Gap infill")
    }
}
