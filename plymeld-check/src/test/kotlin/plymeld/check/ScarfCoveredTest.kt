package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.Job
import plymeld.core.MultiFilament
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.check.PlateCheck
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.gcode.KlipperWriter
import plymeld.geom.Polygon
import plymeld.mesh.TriangleSoup
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Sourced
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * A scarf seam where another part of the object covers the seam (plan.technique.scarf_seam.covered): an inlay, 6 x 4 mm on
 * tool 2, set into a notch in the front of a base on tool 1 (the base's left, right, and back blocks around it, the inlay's
 * front flush with the base's front and open), 3 mm tall, scarf seams on (where = smooth, the bundled 10 mm ramp) and every
 * loop starting at its back-most point (plan.seam = back, ties to the left). The inlay's seam lands on its back-left corner,
 * against the base: hidden. A scarf from there ramps down its left side and across its whole front, the faces that show,
 * and lays that stretch a second time closing the loop. The base's seam lands on its own back-left corner, open air.
 *
 * Read from the g-code, independently of the scarf's code: a ramp is a wall line below its layer's height, and a scarf's
 * closing pass is wall laid over a stretch the same layer's wall already laid on it (each move's middle on an earlier move).
 *  - by default (point) the inlay's outer wall has neither on any layer: one pass at the layer's height, about its
 *    centerline's length; control: covered = scarf, both on every layer;
 *  - the base's outer wall, whose seam nothing covers, keeps its scarf by default (both, on every layer);
 *  - a plate of two copies, one with covered = scarf as its own setting: each laid its own way;
 *  - the pre-print check reads every file back clean;
 *  - what covers a seam is the other parts as laid: on a round base with an inlay in a round hole, no point of its open outer
 *    wall, every point of its hole's; control: the object's closed outline less the base, which leaves hairlines along the
 *    round edge, covers some of the open wall;
 *  - the stage keys: on a kept slicer, changing the setting alone re-plans and writes what a slice from scratch writes.
 * 0.2 mm layers, a 0.42 mm outer wall (TEST VALUES, as the other slicing tests').
 */
class ScarfCoveredTest : StringSpec({
    /**
     * The base at [ox] (mm): 26 x 10 mm, 3 mm tall, a 6 x 4 mm notch in the middle of its front face (y 100). One closed body:
     * its sides round the outline (counter-clockwise seen from above), its caps as three rectangles.
     */
    fun notchedBase(ox: Float): TriangleSoup {
        val o = listOf(0f to 100f, 10f to 100f, 10f to 104f, 16f to 104f, 16f to 100f, 26f to 100f, 26f to 110f, 0f to 110f).map { (x, y) -> ox + x to y }
        val tris = ArrayList<FloatArray>()
        for (k in o.indices) {
            val (ax, ay) = o[k]; val (bx, by) = o[(k + 1) % o.size]
            tris += floatArrayOf(ax, ay, 0f, bx, by, 0f, bx, by, 3f); tris += floatArrayOf(ax, ay, 0f, bx, by, 3f, ax, ay, 3f)
        }
        for ((x0, y0, x1, y1) in listOf(listOf(0f, 100f, 10f, 110f), listOf(10f, 104f, 16f, 110f), listOf(16f, 100f, 26f, 110f)).map { r -> listOf(ox + r[0], r[1], ox + r[2], r[3]) }) {
            tris += floatArrayOf(x0, y0, 3f, x1, y0, 3f, x1, y1, 3f); tris += floatArrayOf(x0, y0, 3f, x1, y1, 3f, x0, y1, 3f)
            tris += floatArrayOf(x0, y0, 0f, x1, y1, 0f, x1, y0, 0f); tris += floatArrayOf(x0, y0, 0f, x0, y1, 0f, x1, y1, 0f)
        }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9] }, tris.size)
    }
    /** The object at [ox] (mm): the base (tool 1), and the inlay (tool 2) filling its notch, its front flush with the base's. */
    fun inlaid(ox: Float): List<TriangleSoup> = listOf(notchedBase(ox), testBox(ox + 10f, 100f, 0f, ox + 16f, 104f, 3f))
    /** The inlay's outer wall's centerline: its outline 6 x 4 mm, half a 0.42 mm line in on every side. */
    val centerline = 2 * ((6 - 0.42) + (4 - 0.42))

    class Written(val g: String, val plan: Plan, val setup: Setup)
    /** [objects] written through Job; the objects in [scarfed] with covered = scarf as their own setting; [extra] over the test setup. */
    fun write(objects: List<List<TriangleSoup>>, scarfed: Set<Int> = emptySet(), vararg extra: Pair<String, Any>): Written {
        val names = objects.indices.map { "o$it" }
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            // the start heats the first tool, as the pre-print check requires before its first line
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false, "hardware.machine.tm.firmware.arcs" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm",
            "plan.technique.scarf_seam.enabled" to true, *extra,
            models = names.map { plymeld.config.ModelObject(it, listOf("B", "I"), listOf(1, 2), "$it.3mf") })
        val setup = base.withObjectSetups(names.mapIndexed { k, name ->
            val own = Values(Entity.INTENT, "object overrides").also { if (k in scarfed) it["plan.technique.scarf_seam.covered"] = Sourced("scarf", "test") }
            ObjectSetup(name, base.objects[k].intent, "spool 1", own, listOf(
                PartSetup("B", "spool 1", Values(Entity.INTENT, "B")), PartSetup("I", "spool 2", Values(Entity.INTENT, "I"))))
        })
        val plan = Plan.makeFor(names.zip(objects), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }

    /** An outer-wall printing move: its layer's height, its ends, its object's block, its tool. */
    class Move(val layerZ: Double, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val obj: String, val tool: String) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
        val low get() = minOf(z0, z1) < layerZ - 1e-6
    }
    fun outerMoves(g: String): List<Move> {
        val out = ArrayList<Move>()
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var lz = Double.NaN; var obj = ""; var tool = "T0"; var feature = ""
        for ((i, raw) in g.lines().withIndex()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith("; Z_HEIGHT:") -> lz = l.removePrefix("; Z_HEIGHT:").trim().toDouble()
                l.startsWith(";TYPE:") || l.startsWith("; FEATURE:") -> feature = l.substringAfter(':').trim()
                raw.startsWith(KlipperWriter.OBJECT_START) -> obj = raw.removePrefix(KlipperWriter.OBJECT_START).trim()
                raw.startsWith(KlipperWriter.OBJECT_END) -> obj = ""
                Regex("^T\\d+$").matches(l) -> tool = l
            }
            val c = l.substringBefore(';').trim()
            require(!c.startsWith("G2 ") && !c.startsWith("G3 ")) { "line ${i + 1}: an arc (the test writes none)" }
            if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            if (feature == "Outer wall" && !lz.isNaN() && e > 0 && !x.isNaN() && (nx != x || ny != y)) out += Move(lz, x, y, z, nx, ny, nz, obj, tool)
            x = nx; y = ny; z = nz
        }
        return out
    }
    /** Whether ([px], [py]) lies on the segment of [m] (within 0.02 mm). */
    fun on(m: Move, px: Double, py: Double): Boolean {
        val dx = m.x1 - m.x0; val dy = m.y1 - m.y0; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - m.x0) * dx + (py - m.y0) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(m.x0 + t * dx - px, m.y0 + t * dy - py) < 0.02
    }
    /** One layer's outer wall of one loop: its moves below the layer, its length laid over its own earlier lines, its length. */
    class Loop(val layerZ: Double, val low: Int, val twiceMm: Double, val lenMm: Double)
    /** Per layer from the third to the last but one, [tool]'s outer wall in object [obj] ("" = any). */
    fun loops(g: String, tool: String, obj: String = ""): List<Loop> = outerMoves(g).filter { it.tool == tool && obj in it.obj }
        .groupBy { it.layerZ }.filterKeys { it > 0.5 && it < 2.9 }.map { (z, ms) ->
            val twice = ms.withIndex().filter { (k, m) -> ms.subList(0, k).any { on(it, (m.x0 + m.x1) / 2, (m.y0 + m.y1) / 2) } }.sumOf { it.value.len }
            Loop(z, ms.count { it.low }, twice, ms.sumOf { it.len })
        }.sortedBy { it.layerZ }
    fun words(ls: List<Loop>) = ls.take(2).joinToString { "z %.1f: %d below the layer, %.2f mm laid twice of %.2f".format(java.util.Locale.ROOT, it.layerZ, it.low, it.twiceMm, it.lenMm) }
    /** A loop laid plain: nothing below its layer, nothing laid twice, its centerline's length. */
    fun plain(l: Loop) = l.low == 0 && l.twiceMm < 0.1 && Math.abs(l.lenMm - centerline) < 0.5
    /** A loop scarfed: a ramp below its layer, and a stretch over 3 mm laid twice (the inlay's ramp: half its loop, 9.2 mm). */
    fun scarfed(l: Loop) = l.low > 0 && l.twiceMm > 3.0

    /** The pre-print check as `slice` runs it (as ZAntiAliasFilamentsTest's): a lone object read whole, a plate per object. */
    fun problems(w: Written): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val zaa = Expectations.forZaa(w.setup, w.plan.layout.placed.mapIndexed { j, pl -> w.plan.layout.objectIndex[j] to pl.parts }, zBase, false)
        if (w.plan.layout.placed.size == 1) {
            val sf = ModelCheck.Surface(w.plan.parts, zBase, w.g, zaaMinMm = zaa?.minMm)
            return GcodeCheck.check(w.g, Expectations.forSetup(w.setup, sf::at, zaa = zaa)) + ModelCheck.check(w.g, w.plan.parts, zBase, surface = sf, zaa = zaa)
        }
        val objs = w.plan.layout.placed.map { PlateCheck.Obj(it.name, it.parts, null) }
        val plate = PlateCheck.together(w.g, objs, zBase, null, zaa)
        return plate.problems + GcodeCheck.check(plate.text, Expectations.forSetup(w.setup, ModelCheck.Surfaces(plate.surfaces)::at, zaa = zaa))
    }

    val point by lazy { write(listOf(inlaid(40f))) }
    val scarf by lazy { write(listOf(inlaid(40f)), emptySet(), "plan.technique.scarf_seam.covered" to "scarf") }

    "the inlay's covered seam is a point seam: no ramp, one pass; control: covered = scarf, a ramp and its closing pass" {
        val p = loops(point.g, "T1"); val s = loops(scarf.g, "T1")
        println("inlay, point: ${words(p)}; scarf: ${words(s)}")
        p.size shouldBe s.size
        (p.size >= 10) shouldBe true
        p.filterNot(::plain).shouldBeEmpty()
        s.filterNot(::scarfed).shouldBeEmpty()
    }

    "the base's seam, which nothing covers, keeps its scarf by default" {
        val b = loops(point.g, "T0")
        println("base, point: ${words(b)}")
        (b.size >= 10) shouldBe true
        b.filterNot(::scarfed).shouldBeEmpty()
    }

    "a plate of two copies, one with covered = scarf as its own setting: each laid its own way" {
        val plate = write(listOf(inlaid(40f), inlaid(110f)), setOf(1))
        val own = loops(plate.g, "T1", "o0"); val other = loops(plate.g, "T1", "o1")
        println("plate: o0 (point) ${words(own)}; o1 (scarf) ${words(other)}")
        (own.size >= 10 && other.size >= 10) shouldBe true
        own.filterNot(::plain).shouldBeEmpty()
        other.filterNot(::scarfed).shouldBeEmpty()
        // both bases keep their scarfs
        loops(plate.g, "T0", "o0").filterNot(::scarfed).shouldBeEmpty()
        loops(plate.g, "T0", "o1").filterNot(::scarfed).shouldBeEmpty()
        problems(plate).shouldBeEmpty()
    }

    "the pre-print check reads both lone objects back clean" {
        problems(point).shouldBeEmpty()
        problems(scarf).shouldBeEmpty()
    }

    // what covers a seam is the other parts as laid (MultiFilament.otherParts), not the object's outline less this part: that
    // outline is closed, and along a round edge the closing leaves hairline slivers outside the part, which read as another part
    "what covers a seam: a round base's open edge nowhere, its hole round the inlay everywhere, its seams scarfed and not; control: the closed outline less the base covers some of the open edge" {
        fun circle(cx: Long, cy: Long, r: Double, k: Int, phase: Double, ccw: Boolean = true) = Polygon(LongArray(2 * k) { j ->
            val a = phase + 2 * Math.PI * (j / 2) / k * (if (ccw) 1 else -1)
            if (j % 2 == 0) cx + Math.round(r * Math.cos(a)) else cy + Math.round(r * Math.sin(a)) })
        val n = 4
        val tops = LongArray(n) { 200L * (it + 1) }
        val s = SliceSettings.of(testSetup("plan.technique.scarf_seam.enabled" to true))
        val spools = listOf("spool 1", "spool 2")
        var hairline = 0
        for (t in 0 until 4) {
            val phase = 2 * Math.PI / 97 * t / 4
            // a round base (a 97-gon, 20 mm across) with a round hole off its center, an inlay of the other filament in it
            val base = listOf(circle(20_000, 20_000, 10_000.0, 97, phase), circle(23_000, 18_000, 3_000.0, 61, phase, ccw = false))
            val inlay = listOf(circle(23_000, 18_000, 3_000.0, 61, phase))
            val r = MultiFilament.plan(s, listOf(List(n) { base }, List(n) { inlay }), spools, listOf(0, 1), tops, "spool 1")
            val split = plymeld.core.FilamentAreas.split(listOf(base, inlay), spools, listOf(0, 1), s.outerWallWidthUm)
            val walls = r.filaments.single { it.spool == "spool 1" }.layers[2].paths.filter { it.feature == Feature.OUTER_WALL && it.closed }
            val open = walls.single { Polygon(it.xy).isCcw() }; val hole = walls.single { !Polygon(it.xy).isCcw() }
            fun covered(p: plymeld.core.ToolPath, near: (Long, Long) -> Boolean) = (0 until p.xy.size / 2).count { near(p.xy[2 * it], p.xy[2 * it + 1]) }
            val near = plymeld.core.Planner.near(MultiFilament.otherParts(split, "spool 1"), s.outerWallWidthUm)!!
            val closedLess = plymeld.core.Planner.near(plymeld.geom.Booleans.difference(r.whole.outlines[2], split.bySpool.getValue("spool 1")), s.outerWallWidthUm)!!
            println("round base, turn $t: open edge covered at ${covered(open, near)} of ${open.xy.size / 2} points (the closed outline less the base: ${covered(open, closedLess)}), " +
                "the hole at ${covered(hole, near)} of ${hole.xy.size / 2}; scarfs ${open.scarfUm} / ${hole.scarfUm}")
            covered(open, near) shouldBe 0
            covered(hole, near) shouldBe hole.xy.size / 2
            (open.scarfUm > 0 && hole.scarfUm == 0L) shouldBe true
            hairline += covered(open, closedLess)
        }
        (hairline > 0) shouldBe true
    }

    "the stage keys: on a kept slicer, changing covered alone re-plans and matches a slice from scratch" {
        val inc = plymeld.core.IncrementalSlicer(point.plan.layout.placed[0].parts, "test", point.plan.layout.placed[0].name, point.plan.layout.objectIndex[0], point.plan.layout.techniques[0])
        fun slice(w: Written) = java.io.StringWriter().let { out -> inc.slice(w.setup, out); out.toString() }
        slice(point) shouldBe point.g
        slice(scarf) shouldBe scarf.g
        slice(point) shouldBe point.g
        (point.g != scarf.g) shouldBe true                                  // control: the setting changes the print
    }
})
