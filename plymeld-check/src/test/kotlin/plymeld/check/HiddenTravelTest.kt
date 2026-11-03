package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.core.IncrementalSlicer
import plymeld.mesh.StlReader
import java.io.StringWriter

/**
 * Travels without a retraction must stay hidden: none may cross an outer wall of its layer
 * (ooze there would show). Checked from the g-code alone — its own parse, nothing from the
 * planner: per layer, every outer / overhang wall segment, and every travel that followed no
 * retraction; a crossing is a failure. Arcs (G2/G3) are followed as chords of at most 0.5 mm —
 * a first version read only G1 and invented long "travels" after every arc. "Overhang wall" marks
 * overhanging stretches of ANY wall, so it counts as outer only at the outer wall's line width
 * (`; LINE_WIDTH`, from the file) — crossing an inner wall is hidden and allowed.
 */
object HiddenTravelCheck {
    fun segmentsCross(a: DoubleArray, b: DoubleArray): Boolean = cross(a, b)
    private fun cross(a: DoubleArray, b: DoubleArray): Boolean {
        fun o(p: DoubleArray, q: DoubleArray, r: DoubleArray) = Math.signum((q[0] - p[0]) * (r[1] - p[1]) - (q[1] - p[1]) * (r[0] - p[0]))
        val p1 = doubleArrayOf(a[0], a[1]); val p2 = doubleArrayOf(a[2], a[3]); val q1 = doubleArrayOf(b[0], b[1]); val q2 = doubleArrayOf(b[2], b[3])
        return o(p1, p2, q1) * o(p1, p2, q2) < 0 && o(q1, q2, p1) * o(q1, q2, p2) < 0
    }

    /** Number of unretracted travels (longer than [minTravel]) that cross an outer wall of their layer. */
    fun crossings(gcode: String, minTravel: Double, outerWidth: Double = 0.42): Int = crossingList(gcode, minTravel, outerWidth).size

    /** Each crossing as "layer N: travel (x0, y0) -> (x1, y1) after <feature>, before <feature>". */
    fun crossingList(gcode: String, minTravel: Double, outerWidth: Double = 0.42): List<String> {
        var feature = ""; var x = 0.0; var y = 0.0; var retracted = false; var layer = 0; var lastFeature = ""; var width = 0.0
        val bad = ArrayList<String>()
        val walls = ArrayList<DoubleArray>(); val travels = ArrayList<Pair<DoubleArray, String>>()
        fun endLayer() {
            for ((t, why) in travels) if (walls.any { cross(t, it) }) bad.add("layer $layer: travel (%.2f, %.2f) -> (%.2f, %.2f) %s".format(java.util.Locale.ROOT, t[0], t[1], t[2], t[3], why))
            walls.clear(); travels.clear(); layer++
        }
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("; CHANGE_LAYER") || line.startsWith(";LAYER_CHANGE")) { endLayer(); continue }
            if (line.startsWith("; FEATURE:") || line.startsWith(";TYPE:")) { feature = line.substringAfter(':').trim(); continue }
            if (line.startsWith("; LINE_WIDTH:") || line.startsWith(";WIDTH:")) { width = line.substringAfter(':').trim().toDoubleOrNull() ?: 0.0; continue }
            val arc = line.startsWith("G2 ") || line.startsWith("G3 ")
            if (!line.startsWith("G1 ") && !line.startsWith("G0 ") && !arc) continue
            var nx = x; var ny = y; var e: Double? = null; var ci = 0.0; var cj = 0.0
            for (p in line.substringBefore(';').split(' ').drop(1)) when (p.firstOrNull()) {
                'X' -> nx = p.substring(1).toDouble(); 'Y' -> ny = p.substring(1).toDouble(); 'E' -> e = p.substring(1).toDouble()
                'I' -> ci = p.substring(1).toDouble(); 'J' -> cj = p.substring(1).toDouble()
            }
            // the move as a polyline: an arc becomes chords of at most 0.5 mm
            val pts = ArrayList<DoubleArray>().apply { add(doubleArrayOf(x, y)) }
            if (arc) {
                val cx = x + ci; val cy = y + cj; val r = Math.hypot(ci, cj)
                val a0 = Math.atan2(y - cy, x - cx); var a1 = Math.atan2(ny - cy, nx - cx)
                val cw = line.startsWith("G2 ")
                if (cw && a1 >= a0) a1 -= 2 * Math.PI
                if (!cw && a1 <= a0) a1 += 2 * Math.PI
                val n = maxOf(1, Math.ceil(Math.abs(a1 - a0) * r / 0.5).toInt())
                for (s in 1 until n) { val a = a0 + (a1 - a0) * s / n; pts.add(doubleArrayOf(cx + r * Math.cos(a), cy + r * Math.sin(a))) }
            }
            pts.add(doubleArrayOf(nx, ny))
            val len = Math.hypot(nx - x, ny - y)
            when {
                e != null && e < 0 -> retracted = true
                e != null && e > 0 && (len > 0 || arc) -> { retracted = false; lastFeature = feature
                    if (feature == "Outer wall" || (feature == "Overhang wall" && Math.abs(width - outerWidth) < 1e-3)) for (s in 0 until pts.size - 1) walls.add(doubleArrayOf(pts[s][0], pts[s][1], pts[s + 1][0], pts[s + 1][1])) }
                e != null && e > 0 -> retracted = false
                !arc && len > minTravel && !retracted -> travels.add(doubleArrayOf(x, y, nx, ny) to "after $lastFeature, before $feature")
            }
            x = nx; y = ny
        }
        endLayer()
        return bad
    }
}

class HiddenTravelTest : StringSpec({
    // Not a universal rule: an unretracted travel may cross a wall where nothing
    // intended otherwise (e.g. a short hop under retraction_minimum_travel). What must hold is the
    // PLANNER's promise: every travel it marked hidden (retractBefore = false) stays off the layer's
    // outer walls.
    for (m in listOf("3DBenchy", "testplate")) "$m: every travel the planner marked hidden stays off the outer walls" {
        val layers = IncrementalSlicer(listOf(StlReader.read(testModelsDir().resolve("$m.stl"))), "test", m).planned(testSetup())
        var intended = 0
        val bad = ArrayList<String>()
        for (l in layers) {
            val walls = l.paths.filter { it.feature == plymeld.core.Feature.OUTER_WALL }.flatMap { p ->
                val n = p.xy.size / 2; val segs = if (p.closed) n else n - 1
                (0 until segs).map { k -> val j = (k + 1) % n; doubleArrayOf(p.xy[2 * k] / 1e3, p.xy[2 * k + 1] / 1e3, p.xy[2 * j] / 1e3, p.xy[2 * j + 1] / 1e3) }
            }
            for ((prev, p) in l.paths.zipWithNext()) {
                if (p.retractBefore) continue
                intended++
                val n = prev.xy.size / 2
                val ex = if (prev.closed) prev.xy[0] else prev.xy[2 * (n - 1)]; val ey = if (prev.closed) prev.xy[1] else prev.xy[2 * n - 1]
                val t = doubleArrayOf(ex / 1e3, ey / 1e3, p.xy[0] / 1e3, p.xy[1] / 1e3)
                if (walls.any { HiddenTravelCheck.segmentsCross(t, it) }) bad.add("layer ${l.index}: (%.2f, %.2f) -> (%.2f, %.2f) into ${p.feature.label}".format(java.util.Locale.ROOT, t[0], t[1], t[2], t[3]))
            }
        }
        println("  $m: $intended hidden travels planned; ${bad.size} cross an outer wall")
        bad.take(10).forEach { println("    $it") }
        (intended > 0) shouldBe true          // control: the check sees intended travels at all
        bad shouldBe emptyList()
    }

    "control: an unretracted travel across an outer wall is caught" {
        val g = "; CHANGE_LAYER\n; FEATURE: Outer wall\nG1 X0 Y0\nG1 X10 Y0 E1\nG1 X10 Y10 E1\nG1 X0 Y10 E1\nG1 X0 Y0 E1\n" +
            "; FEATURE: Sparse infill\nG1 X5 Y5 E0.1\nG1 X20 Y5\nG1 X21 Y5 E0.1\n"
        HiddenTravelCheck.crossings(g, 1.0) shouldBe 1
    }

    "control: position is followed through arcs (an arc then a short hop is not a long travel)" {
        val g = "; CHANGE_LAYER\n; FEATURE: Outer wall\nG1 X0 Y0\nG1 X10 Y0 E1\nG1 X10 Y10 E1\nG1 X0 Y10 E1\nG1 X0 Y0 E1\n" +
            "; FEATURE: Sparse infill\nG1 X2 Y5 E0.1\nG3 X8 Y5 I3 J0 E0.3\nG1 X8.5 Y5.5\nG1 X9 Y6 E0.1\n"
        HiddenTravelCheck.crossings(g, 0.1) shouldBe 0
    }

    "control: an overhang stretch of the OUTER wall (outer width) is caught; of an inner wall (inner width) it is not" {
        fun g(w: String) = "; CHANGE_LAYER\n; FEATURE: Overhang wall\n; LINE_WIDTH: $w\nG1 X0 Y0\nG1 X10 Y0 E1\nG1 X10 Y10 E1\nG1 X0 Y10 E1\nG1 X0 Y0 E1\n" +
            "; FEATURE: Sparse infill\n; LINE_WIDTH: 0.45\nG1 X5 Y5 E0.1\nG1 X20 Y5\nG1 X21 Y5 E0.1\n"
        HiddenTravelCheck.crossings(g("0.42"), 1.0, 0.42) shouldBe 1
        HiddenTravelCheck.crossings(g("0.45"), 1.0, 0.42) shouldBe 0
    }

    "control: the same travel after a retraction passes" {
        val g = "; CHANGE_LAYER\n; FEATURE: Outer wall\nG1 X0 Y0\nG1 X10 Y0 E1\nG1 X10 Y10 E1\nG1 X0 Y10 E1\nG1 X0 Y0 E1\n" +
            "; FEATURE: Sparse infill\nG1 X5 Y5 E0.1\nG1 E-0.8\nG1 X20 Y5\nG1 E0.8\nG1 X21 Y5 E0.1\n"
        HiddenTravelCheck.crossings(g, 1.0) shouldBe 0
    }
})
