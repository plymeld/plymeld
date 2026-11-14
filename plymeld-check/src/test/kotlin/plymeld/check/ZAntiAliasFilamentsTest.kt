package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.check.PlateCheck
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
import plymeld.model.Sourced
import plymeld.model.SliceSettings
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * Z anti-aliasing (plan.faces.sloped_top.z_antialiasing, ZAntiAlias) on objects laid per part: two parts on two tools (a
 * machine of the bundled two-and-more-tool model), written through Job as `slice` writes them, alone and on a plate. The
 * models are ramps whose top rises along X at 10 deg (z = 1 + x tan 10 from the object's low end), and the reference for
 * where a lowered line belongs is that formula, never the slicer's reading of the mesh. 0.2 mm layers, both tools' thinnest
 * layer 0.08 mm (TEST VALUES, as the other slicing tests'), the slicing resolution the config's.
 *
 *  - a ramp split in two (20 mm on tool 1, then 10 mm on tool 2): lines below their layer on BOTH tools, every one on the
 *    ramp's top within the resolution and the file's rounding, never under the layer's bottom plus 0.08 mm; control: with it
 *    off, none below its layer;
 *  - each part's layer reaches out over its slope: the first part's low outer wall stands (0.1 - 0.08) / tan 10 mm further
 *    out than with it off (the slice at the thinnest layer over the layer's bottom, not at its middle); control: off;
 *  - a part covering another's slope (a prism on tool 2 standing on the middle third of a wedge on tool 1, up to 8 mm): no
 *    line over the covered face is lowered (the object's top there is the prism's); control: the prism set beside the wedge
 *    instead, the same face is lowered;
 *  - a plate of two split ramps with it on for one only (and on 0.15 mm layers, the other on 0.2 mm): lowered lines in that
 *    object's blocks only, each on its own layer's height and thickness, none outside the blocks (the purge tower), and no
 *    block left with the head below its layer; control: on for both, the other's are lowered too;
 *  - the pre-print check (GcodeCheck, ModelCheck, PlateCheck, with the rule the slice command makes) reads each file back
 *    clean; controls: a lowered line moved 0.03 mm below the top is refused, a line lowered onto the covered face is refused,
 *    and the plate with it on for both read with the rule of the plate with it on for one has the other's lowered lines refused;
 *  - the stage keys: on a kept slicer, turning it on, or changing the second tool's thinnest layer alone, re-slices the parts
 *    and writes what a slice from scratch writes;
 *  - the thinnest layer: with both tools' at the layer height, or the second tool's alone, no line on a layer of that
 *    height is lowered and none is lowered under its layer's bottom plus that height (no tool's line is thinner than its own
 *    thinnest layer, and an object's lowered lines are as thin whichever tool lays them: ZAntiAlias.minMm); control: at
 *    0.08 mm, lowered (the first case).
 */
class ZAntiAliasFilamentsTest : StringSpec({
    val tan = Math.tan(Math.toRadians(10.0))
    val h0 = 1.0
    /** A prism along Y from [y0] to [y1] (mm) of the X-Z [profile] (convex, counter-clockwise seen from -Y), every face outward. */
    fun prism(profile: List<Pair<Double, Double>>, y0: Double, y1: Double): TriangleSoup {
        val tris = ArrayList<DoubleArray>()
        fun at(i: Int, y: Double) = doubleArrayOf(profile[i].first, y, profile[i].second)
        val n = profile.size
        for (i in 1 until n - 1) { tris += at(0, y0) + at(i, y0) + at(i + 1, y0); tris += at(0, y1) + at(i + 1, y1) + at(i, y1) }
        for (i in 0 until n) { val j = (i + 1) % n; tris += at(i, y0) + at(j, y1) + at(j, y0); tris += at(i, y0) + at(i, y1) + at(j, y1) }
        return TriangleSoup(FloatArray(tris.size * 9) { tris[it / 9][it % 9].toFloat() }, tris.size)
    }
    /** The ramp's top over [x] mm from its low end. */
    fun top(x: Double) = h0 + x * tan
    /** A wedge from [x0] to [x1] (its top [top] from the low end at [ox]). */
    fun wedge(ox: Double, x0: Double, x1: Double) = prism(listOf(ox + x0 to 0.0, ox + x1 to 0.0, ox + x1 to top(x1), ox + x0 to top(x0)), 100.0, 120.0)
    /** A ramp 30 mm long at [ox], split at 20 mm into two parts. */
    fun splitRamp(ox: Double) = listOf(wedge(ox, 0.0, 20.0), wedge(ox, 20.0, 30.0))
    /** A 30 mm wedge at [ox] and a prism standing on its top from 10 to 20 mm, up to 8 mm. */
    fun covered(ox: Double) = listOf(wedge(ox, 0.0, 30.0),
        prism(listOf(ox + 10.0 to top(10.0), ox + 20.0 to top(20.0), ox + 20.0 to 8.0, ox + 10.0 to 8.0), 100.0, 120.0))
    /** The control: the same wedge, and a box beside it (the second part, so the object is still laid per part). */
    fun uncovered(ox: Double) = listOf(wedge(ox, 0.0, 30.0), testBox((ox + 34).toFloat(), 100f, 0f, (ox + 38).toFloat(), 120f, 3f))

    class Written(val g: String, val plan: Plan, val setup: Setup)
    /**
     * [objects] (each two parts: the first on tool 1, the second on tool 2) written through Job; Z anti-aliasing on for the
     * objects in [on]; [layerMm], an object's own flat layer height, by index; [extra] over the test setup.
     */
    fun write(objects: List<List<TriangleSoup>>, on: Set<Int>, layerMm: Map<Int, Double> = emptyMap(), vararg extra: Pair<String, Any>): Written {
        val names = objects.indices.map { "o$it" }
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            // the start heats the first tool, as the pre-print check requires before its first line
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.machine.tm.firmware.arcs" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            // the purge tower clear of the objects and on the bed whatever its height
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm", *extra,
            models = names.map { plymeld.config.ModelObject(it, listOf("A", "B"), listOf(1, 2), "$it.3mf") })
        val setup = base.withObjectSetups(names.mapIndexed { k, name ->
            val own = Values(Entity.INTENT, "object overrides").also {
                if (k in on) it["plan.faces.sloped_top.z_antialiasing"] = Sourced(true, "test")
                layerMm[k]?.let { h -> it["plan.technique.flat.layer.height"] = Sourced(h, "test") }
            }
            ObjectSetup(name, base.objects[k].intent, "spool 1", own, listOf(
                PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", "spool 2", Values(Entity.INTENT, "B"))))
        })
        val locks = names.indices.map { Plan.Companion.ObjectLocks(layerHeightMm = layerMm[it]) }
        val plan = Plan.makeFor(names.zip(objects), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE, objects = locks))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }
    /** A slicer of [w]'s lone object, as Job makes it. */
    fun slicer(w: Written) = plymeld.core.IncrementalSlicer(w.plan.layout.placed[0].parts, "test", w.plan.layout.placed[0].name, w.plan.layout.objectIndex[0], w.plan.layout.techniques[0])

    /** A printing move: its layer's height and thickness, its ends, its object's block (by the writer's markers), its tool. */
    class Move(val layerZ: Double, val layerH: Double, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double,
               val obj: String, val tool: String, val line: Int) {
        val lowered get() = minOf(z0, z1) < layerZ - 1e-6
    }
    fun moves(g: String): List<Move> {
        val out = ArrayList<Move>()
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var lz = Double.NaN; var lh = 0.0; var obj = ""; var tool = "T0"
        for ((i, raw) in g.lines().withIndex()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith("; Z_HEIGHT:") -> lz = l.removePrefix("; Z_HEIGHT:").trim().toDouble()
                l.startsWith(";HEIGHT:") -> lh = l.removePrefix(";HEIGHT:").trim().toDouble()
                l.startsWith("; LAYER_HEIGHT:") -> lh = l.removePrefix("; LAYER_HEIGHT:").trim().toDouble()
                raw.startsWith(KlipperWriter.OBJECT_START) -> obj = raw.removePrefix(KlipperWriter.OBJECT_START).trim()
                raw.startsWith(KlipperWriter.OBJECT_END) -> obj = ""
                Regex("^T\\d+$").matches(l) -> tool = l
            }
            val c = l.substringBefore(';').trim()
            require(!c.startsWith("G2 ") && !c.startsWith("G3 ")) { "line ${i + 1}: an arc (the test writes none)" }
            if (!c.startsWith("G1 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            if (!lz.isNaN() && e > 0 && !x.isNaN() && (nx != x || ny != y)) out += Move(lz, lh, x, y, z, nx, ny, nz, obj, tool, i + 1)
            x = nx; y = ny; z = nz
        }
        return out
    }
    /** The object blocks of [g] the head leaves below its layer's top (Z at the block's end marker under the layer's height). */
    fun lowAtBlockEnd(g: String): Int {
        var z = Double.NaN; var lz = Double.NaN; var n = 0
        for (raw in g.lines()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith("; Z_HEIGHT:") -> lz = l.removePrefix("; Z_HEIGHT:").trim().toDouble()
                raw.startsWith(KlipperWriter.OBJECT_END) -> if (z < lz - 1e-6) n++
            }
            val c = l.substringBefore(';').trim()
            if (c.startsWith("G1 ") || c.startsWith("G0 ")) c.split(Regex("\\s+")).firstOrNull { it.startsWith("Z") }?.let { z = it.substring(1).toDouble() }
        }
        return n
    }
    /** Object [k]'s moves (its block's name holds its name). */
    fun List<Move>.of(k: Int) = filter { it.obj.isNotEmpty() && "o$k" in it.obj }

    val min = 0.08
    val round = 0.0015
    /** Every lowered move of object [k] of [w] on its ramp's top ([top], from the object's low end), never under its layer's bottom plus [min]. */
    fun onTop(w: Written, k: Int) {
        val res = SliceSettings.of(w.setup).resolutionUm / 1000.0
        val parts = w.plan.layout.placed[k].parts
        val xmin = parts.minOf { it.bounds()[0] }; val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val ms = moves(w.g).of(k)
        for (m in ms.filter { it.lowered }) for (t in listOf(0.0, 0.5, 1.0)) {
            val x = m.x0 + (m.x1 - m.x0) * t; val z = m.z0 + (m.z1 - m.z0) * t
            val want = minOf(m.layerZ, maxOf(top(x - xmin) - zBase, m.layerZ - m.layerH + min))
            Math.abs(z - want) shouldBeLessThan res + round
        }
        for (m in ms) minOf(m.z0, m.z1) shouldBeGreaterThan m.layerZ - m.layerH + min - round
    }

    /**
     * The pre-print check as `slice` runs it (SliceSession.check): the rule from the model and the config (Expectations.forZaa,
     * of [ruleOf]'s setup: the objects that have it on there), a lone object read whole, a plate per object (PlateCheck).
     */
    fun problems(w: Written, g: String = w.g, ruleOf: Written = w): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val zaa = Expectations.forZaa(ruleOf.setup, w.plan.layout.placed.mapIndexed { j, pl -> w.plan.layout.objectIndex[j] to pl.parts }, zBase, false)
        if (w.plan.layout.placed.size == 1) {
            val sf = ModelCheck.Surface(w.plan.parts, zBase, g, zaaMinMm = zaa?.minMm)
            return GcodeCheck.check(g, Expectations.forSetup(w.setup, sf::at, zaa = zaa)) + ModelCheck.check(g, w.plan.parts, zBase, surface = sf, zaa = zaa)
        }
        val objs = w.plan.layout.placed.map { PlateCheck.Obj(it.name, it.parts, null) }
        val plate = PlateCheck.together(g, objs, zBase, null, zaa)
        return plate.problems + GcodeCheck.check(plate.text, Expectations.forSetup(w.setup, ModelCheck.Surfaces(plate.surfaces)::at, zaa = zaa))
    }
    /** [g] with line [n] (1-based) replaced by [f] of it. */
    fun edit(g: String, n: Int, f: (String) -> String) = g.lines().toMutableList().also { it[n - 1] = f(it[n - 1]) }.joinToString("\n")
    fun setZ(line: String, z: Double) = line.replace(Regex(" Z[0-9.]+"), "").replace(" E", " Z${"%.3f".format(java.util.Locale.ROOT, z)} E")

    val split by lazy { write(listOf(splitRamp(40.0)), setOf(0)) }

    "a ramp split in two parts on two tools: lines below their layer on both tools, all on the ramp's top; control: off, none" {
        val low = moves(split.g).filter { it.lowered }
        val byTool = low.groupBy { it.tool }.mapValues { (_, v) -> v.size }
        println("split ramp: lowered moves by tool $byTool")
        byTool.keys shouldBe setOf("T0", "T1")
        (byTool.getValue("T0") > 20) shouldBe true
        (byTool.getValue("T1") > 20) shouldBe true
        onTop(split, 0)
        // control: with it off, every line at its layer's height
        moves(write(listOf(splitRamp(40.0)), emptySet()).g).filter { it.lowered }.shouldBeEmpty()
    }

    "each part's layer reaches out over its slope: its outer wall where the top is the thinnest layer over its bottom (control: off)" {
        val off = write(listOf(splitRamp(40.0)), emptySet())
        val xmin = split.plan.parts.minOf { it.bounds()[0] }
        /** The low edge's outer wall on the layer topping at [layerZ] (the first part's, on the first tool): its least X. */
        fun lowWallX(g: String, layerZ: Double) = moves(g).of(0).filter { Math.abs(it.layerZ - layerZ) < 1e-6 && it.tool == "T0" && Math.abs(it.x1 - it.x0) < 0.01 && Math.abs(it.y1 - it.y0) > 2.0 }.minOf { it.x0 }
        // three layers on the first part's slope (from 1 mm to 4.5 mm high)
        for (layerZ in listOf(2.0, 2.8, 3.6)) {
            val onX = lowWallX(split.g, layerZ); val offX = lowWallX(off.g, layerZ)
            val bottom = layerZ - 0.2
            // the slice at the thinnest layer over the layer's bottom, against the one at its middle: (0.1 - 0.08) / tan 10 apart
            println("layer $layerZ: low wall at x ${onX - xmin} (on), ${offX - xmin} (off); the top is the thinnest layer over the bottom at ${(bottom + min - h0) / tan}")
            (offX - onX) shouldBeGreaterThan (0.1 - min) / tan - 0.02
            (offX - onX) shouldBeLessThan (0.1 - min) / tan + 0.02
        }
    }

    "a face another part covers is not a top: no line over it lowered; control: uncovered, it is" {
        val cov = write(listOf(covered(40.0)), setOf(0)); val unc = write(listOf(uncovered(40.0)), setOf(0))
        fun over(w: Written) = moves(w.g).filter { it.lowered && minOf(it.x0, it.x1) > 51.0 && maxOf(it.x0, it.x1) < 59.0 }
        println("covered face: ${over(cov).size} lowered moves over it; uncovered: ${over(unc).size}; covered model elsewhere: ${moves(cov.g).count { it.lowered }}")
        over(cov).shouldBeEmpty()
        (over(unc).size > 10) shouldBe true                               // control: the same face, uncovered, is lowered
        (moves(cov.g).count { it.lowered } > 10) shouldBe true            // the wedge's slope beside the prism still is
        problems(cov).shouldBeEmpty()
        // control: a line over the covered face, lowered onto the wedge's own top there, is refused (the object's top is the prism's)
        val xmin = cov.plan.parts.minOf { it.bounds()[0] }
        val m = moves(cov.g).first { !it.lowered && it.x0 == it.x1 && it.x0 > 51.0 && it.x0 < 59.0 && Math.abs(it.y1 - it.y0) > 3.0 &&
            top(it.x0 - xmin) in (it.layerZ - it.layerH + min + 0.01)..(it.layerZ - 0.03) }
        problems(cov, edit(cov.g, m.line) { setZ(it, top(m.x0 - xmin)) }).any { "off the model's top" in it } shouldBe true
    }

    "the pre-print check reads the split ramp back clean; control: a lowered line moved 0.03 mm below the top is refused" {
        problems(split).shouldBeEmpty()
        val m = moves(split.g).first { it.lowered && it.z1 < it.layerZ - 0.05 && Math.hypot(it.x1 - it.x0, it.y1 - it.y0) > 0.2 }
        problems(split, edit(split.g, m.line) { setZ(it, m.z1 - 0.03) }).any { "off the model's top" in it } shouldBe true
    }

    "a plate with it on for one object (0.15 mm layers, the other 0.2 mm): lowered lines in its blocks only; control: on for both" {
        val one = write(listOf(splitRamp(40.0), splitRamp(110.0)), setOf(1), mapOf(1 to 0.15))
        val both = write(listOf(splitRamp(40.0), splitRamp(110.0)), setOf(0, 1), mapOf(1 to 0.15))
        val ms = moves(one.g)
        println("plate, on for o1: lowered o0 ${ms.of(0).count { it.lowered }}, o1 ${ms.of(1).count { it.lowered }} (by tool ${ms.of(1).filter { it.lowered }.groupBy { it.tool }.mapValues { it.value.size }}); " +
            "o1's layer heights ${ms.of(1).map { it.layerH }.toSet()}; on for both: o0 ${moves(both.g).of(0).count { it.lowered }}")
        ms.of(0).count { it.lowered } shouldBe 0
        ms.of(1).filter { it.lowered }.map { it.tool }.toSet() shouldBe setOf("T0", "T1")
        // each on its own layers: o1's lowered lines on its 0.15 mm layers, o0's lines on none
        ms.of(1).filter { it.lowered }.map { it.layerH }.toSet() shouldBe setOf(0.15)
        (0.15 in ms.of(0).map { it.layerH }) shouldBe false
        onTop(one, 1)
        (moves(both.g).of(0).count { it.lowered } > 20) shouldBe true      // control: o0 is lowered when it has it on
        onTop(both, 0)
        // nothing outside the objects' blocks is lowered (the purge tower), and the head leaves no block below its layer
        ms.filter { it.obj.isEmpty() && it.lowered }.shouldBeEmpty()
        lowAtBlockEnd(one.g) shouldBe 0
        lowAtBlockEnd(both.g) shouldBe 0
        lowAtBlockEnd(split.g) shouldBe 0
        // the check: each file clean with its own rule; the file with it on for both, read with the rule for o1 alone, has o0's refused
        problems(one).shouldBeEmpty()
        problems(both).shouldBeEmpty()
        problems(both, ruleOf = one).any { "off the model's top" in it || "printing at Z" in it } shouldBe true
    }

    "the stage keys: turning it on, or changing the second tool's thinnest layer alone, re-slices the parts and matches a slice from scratch" {
        val off = write(listOf(splitRamp(40.0)), emptySet())
        val t2 = write(listOf(splitRamp(40.0)), setOf(0), emptyMap(), "hardware.tool.t2.layer.height.min" to "0.2 mm")
        val inc = slicer(off)
        fun slice(sl: plymeld.core.IncrementalSlicer, w: Written) = java.io.StringWriter().let { out -> sl.slice(w.setup, out) to out.toString() }
        val (_, first) = slice(inc, off)
        first shouldBe off.g
        for (w in listOf(split, t2, off)) {
            val (ran, again) = slice(inc, w)
            println("re-sliced: ran $ran")
            ("slice parts" in ran && "filaments" in ran) shouldBe true
            again shouldBe slice(slicer(w), w).second                       // as a slice from scratch
            again shouldBe w.g                                               // and as Job writes it
        }
        // control: the three prints differ (the setting, and the second tool's thinnest layer, change the print)
        (split.g != off.g && split.g != t2.g) shouldBe true
    }

    "the thinnest layer: at the layer height (both tools, or one), nothing lowered; control: at 0.08 mm, lowered (the first case)" {
        // the ramp's top (6.29 mm) leaves a remainder at the top: with the layers' own thinnest layer (the object's tool's) at
        // 0.2 mm the last layer is 0.29 mm thick, whose lines may still be lowered, to 0.2 mm over its bottom; at 0.08 mm a
        // last layer 0.09 mm thick, not lowered. On every other layer, none
        fun check(w: Written) {
            val ms = moves(w.g)
            ms.filter { it.lowered && it.layerH <= 0.2 + 1e-6 }.shouldBeEmpty()
            for (m in ms.filter { it.lowered }) minOf(m.z0, m.z1) shouldBeGreaterThan m.layerZ - m.layerH + 0.2 - round
            println("thinnest layer 0.2 mm: ${ms.count { it.lowered }} lowered moves, on layers ${ms.filter { it.lowered }.map { it.layerH }.toSet()} mm thick")
        }
        check(write(listOf(splitRamp(40.0)), setOf(0), emptyMap(), "hardware.tool.t1.layer.height.min" to "0.2 mm", "hardware.tool.t2.layer.height.min" to "0.2 mm"))
        check(write(listOf(splitRamp(40.0)), setOf(0), emptyMap(), "hardware.tool.t2.layer.height.min" to "0.2 mm"))
        // control: at 0.08 mm, lines lowered on the 0.2 mm layers, below 0.2 mm over their bottom
        val ms = moves(split.g)
        (ms.count { it.lowered && it.layerH <= 0.2 + 1e-6 } > 40) shouldBe true
        (ms.count { minOf(it.z0, it.z1) < it.layerZ - it.layerH + 0.2 - 0.01 } > 10) shouldBe true
    }
})
