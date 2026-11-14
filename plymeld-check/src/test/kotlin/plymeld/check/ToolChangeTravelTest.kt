package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Job
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.check.PlateCheck
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.gcode.KlipperWriter
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Values
import plymeld.model.withObjectSetups

/**
 * Where a tool change is made: read from the g-code as a printer runs it (X, Y, Z and E per move, arcs by their ends), for a
 * plate of two objects of two filaments each (a 20 mm part on tool 1 beside a 10 mm part on tool 2, on a machine of the bundled
 * two-and-more-tool model), written through Job. For every change after the start:
 *  - the head is not where the outgoing filament's last line ended when the T line is written, but over the start of the
 *    incoming filament's first line (the next printing move's start), lifted above the layer by the filament's hop;
 *  - the outgoing tool retracted (and, where the writer wipes, wiped) before the travel there, not after it;
 *  - nothing extruded between the outgoing filament's last line and the T line.
 * With the purge tower, the incoming line is its purge on the tower; with the purge into infill, the infill on the part first
 * where it has some; with no tower, its first line on the part. Each file passes the pre-print check (GcodeCheck and PlateCheck).
 * Controls: the same file with each change made where the last line ended (the travel removed: retract, then the change, as a
 * change standing on the part) is refused on every change; one with a line printed before the T, and one with the retraction
 * made after the travel, are refused too.
 */
class ToolChangeTravelTest : StringSpec({
    class Written(val g: String, val plan: Plan, val setup: Setup)
    /** Two objects 60 mm apart, each two parts on two tools, [extra] over the test setup. */
    fun write(vararg extra: Pair<String, Any>): Written {
        val names = listOf("o0", "o1")
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            // the start heats the first tool, as the pre-print check requires before its first line
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
            "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm", *extra,
            models = names.map { plymeld.config.ModelObject(it, listOf("A", "B"), listOf(1, 2), "$it.3mf") })
        val setup = base.withObjectSetups(names.mapIndexed { k, name ->
            ObjectSetup(name, base.objects[k].intent, "spool 1", Values(Entity.INTENT, "object overrides"), listOf(
                PartSetup("A", "spool 1", Values(Entity.INTENT, "A")), PartSetup("B", "spool 2", Values(Entity.INTENT, "B"))))
        })
        val objs = names.mapIndexed { k, name -> val x = 40f + 60f * k
            name to listOf(testBox(x, 100f, 0f, x + 20f, 120f, 3f), testBox(x + 20f, 100f, 0f, x + 30f, 120f, 3f)) }
        val plan = Plan.makeFor(objs, setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER))
        return Written(java.io.StringWriter().also { Job(plan.layout, setup, "test").write(it) }.toString(), plan, setup)
    }
    /** The pre-print check as `slice` runs it on a plate printed together (PlateCheck, then GcodeCheck on what it returns). */
    fun prePrint(w: Written, g: String = w.g): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val plate = PlateCheck.together(g, w.plan.layout.placed.map { PlateCheck.Obj(it.name, it.parts, null) }, zBase, null, null)
        return plate.problems + GcodeCheck.check(plate.text, Expectations.forSetup(w.setup, ModelCheck.Surfaces(plate.surfaces)::at))
    }

    /** A tool change as the file runs it: its T line (0-based), and the lines (0-based) of the outgoing filament's last printing move and of the incoming's first. */
    class Change(val tLine: Int, val lastPrinted: Int, val nextPrinted: Int, val x: Double, val y: Double, val z: Double, val layerZ: Double,
                 val lastX: Double, val lastY: Double, val nextX: Double, val nextY: Double, val nextFeature: String, val nextInObject: Boolean,
                 /** The moves between the last printing move and the T line: (moves in X/Y, E). */
                 val between: List<Pair<Boolean, Double>>) {
        val wiped get() = between.any { it.first && it.second < 0 }
    }
    /** Every tool change after the first printed line in [g], read move by move. */
    fun changes(g: String): List<Change> {
        val lines = g.lines()
        // each line's head position after it, and whether it prints (moves in X/Y pushing filament)
        val xs = DoubleArray(lines.size); val ys = DoubleArray(lines.size); val zs = DoubleArray(lines.size); val lzs = DoubleArray(lines.size)
        val prints = BooleanArray(lines.size); val moves = BooleanArray(lines.size); val es = DoubleArray(lines.size); val feats = arrayOfNulls<String>(lines.size)
        val inObj = BooleanArray(lines.size)
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var lz = Double.NaN; var feature = ""; var obj = false
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith("; Z_HEIGHT:") -> lz = l.removePrefix("; Z_HEIGHT:").trim().toDouble()
                l.startsWith(";TYPE:") || l.startsWith("; FEATURE:") -> feature = l.substringAfter(':').trim()
                raw.startsWith(KlipperWriter.OBJECT_START) -> obj = true
                raw.startsWith(KlipperWriter.OBJECT_END) -> obj = false
            }
            val c = l.substringBefore(';').trim()
            val ws = c.split(Regex("\\s+"))
            if (ws[0] == "G0" || ws[0] == "G1" || ws[0] == "G2" || ws[0] == "G3") {
                // an arc ends at its X and Y like a line: only the ends matter here
                fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDouble()
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
                val xy = nx != x || ny != y
                moves[i] = xy; es[i] = e; prints[i] = xy && e > 0 && !x.isNaN()
                x = nx; y = ny; z = nz
            }
            xs[i] = x; ys[i] = y; zs[i] = z; lzs[i] = lz; feats[i] = feature; inObj[i] = obj
        }
        val out = ArrayList<Change>()
        val firstPrint = prints.indexOfFirst { it }
        for (t in lines.indices) {
            if (t < firstPrint || !Regex("^T\\d+$").matches(lines[t].trim())) continue
            val last = (t downTo 0).first { prints[it] }
            val next = (t until lines.size).first { prints[it] }
            val between = (last + 1 until t).filter { es[it] != 0.0 || moves[it] }.map { moves[it] to es[it] }
            out += Change(t, last, next, xs[t], ys[t], zs[t], lzs[t], xs[last], ys[last], xs[next - 1], ys[next - 1], feats[next]!!, inObj[next], between)
        }
        return out
    }
    /** What is wrong with each change in [g] (tool changes after the first printed line; [hopMm], the lift over the layer), by its T line (1-based). */
    fun refusals(g: String, hopMm: Double): List<String> {
        val out = ArrayList<String>()
        for (c in changes(g)) {
            val at = "line ${c.tLine + 1}"
            if (Math.hypot(c.x - c.nextX, c.y - c.nextY) > 0.0015)
                out += "$at: the change made at (${c.x}, ${c.y}), not over the incoming line's start (${c.nextX}, ${c.nextY})"
            if (Math.hypot(c.x - c.lastX, c.y - c.lastY) < 0.0015)
                out += "$at: the change made where the outgoing filament's last line ended (${c.lastX}, ${c.lastY})"
            if (!(c.z >= c.layerZ + hopMm - 0.0015)) out += "$at: the change made at Z${c.z}, not lifted ${hopMm} mm over the layer at ${c.layerZ}"
            if (c.between.any { it.second > 0 }) out += "$at: filament pushed between the last line and the change"
            // retracted before the travel: every retraction (a wipe's moves too) ahead of the first plain travel
            val firstTravel = c.between.indexOfFirst { it.first && it.second == 0.0 }
            val lastRetract = c.between.indexOfLast { it.second < 0 }
            if (lastRetract < 0) out += "$at: no retraction before the change"
            else if (firstTravel in 0 until lastRetract) out += "$at: retracted after the travel to the change, not before it"
        }
        return out
    }
    /**
     * [g] with every change after the first printed line made as before, standing where the outgoing filament's last line
     * ended: the lines between that line and the T replaced by a standing retraction of [retractMm], the idle temperature kept.
     */
    fun inPlace(g: String, retractMm: Double): String {
        val lines = g.lines().toMutableList()
        for (c in changes(g).reversed()) {
            val idle = lines.subList(c.lastPrinted + 1, c.tLine).filter { it.startsWith("M104 ") && " T" in it && "preheat" !in it }
            for (k in c.tLine - 1 downTo c.lastPrinted + 1) lines.removeAt(k)
            lines.addAll(c.lastPrinted + 1, listOf("G1 E-$retractMm F2400") + idle)
        }
        return lines.joinToString("\n")
    }

    val hop = SliceSettings.of(write().setup).zHopUm / 1000.0

    "with the purge tower: every change made over the tower's purge start, lifted, after the outgoing tool retracted and wiped; control: made in place, refused" {
        val w = write()
        val cs = changes(w.g)
        println("tower: ${cs.size} changes, ${cs.count { it.wiped }} with a wipe, incoming first lines ${cs.groupingBy { it.nextFeature }.eachCount()}; " +
            "travel to the change %.1f mm at most".format(java.util.Locale.ROOT, cs.maxOf { Math.hypot(it.x - it.lastX, it.y - it.lastY) }))
        (cs.size >= 10) shouldBe true
        cs[cs.size / 2].let { c -> println("one change, from the outgoing filament's last line to the incoming's first:\n" + w.g.lines().subList(c.lastPrinted, c.nextPrinted + 1).joinToString("\n") { "    $it" }) }
        cs.all { it.nextFeature == "Prime tower" && !it.nextInObject } shouldBe true
        // the outgoing tool wipes as before any travel (here most of its last lines are on the parts, a few on the tower's base)
        (cs.count { it.wiped } >= cs.size / 2) shouldBe true
        refusals(w.g, hop) shouldBe emptyList()
        prePrint(w) shouldBe emptyList()
        // controls: each change made standing where the last line ended (the old place) is refused, every one of them
        val old = inPlace(w.g, SliceSettings.of(w.setup).retractMm)
        val r = refusals(old, hop)
        r.count { "not over the incoming line's start" in it } shouldBe cs.size
        r.count { "where the outgoing filament's last line ended" in it } shouldBe cs.size
        r.count { "not lifted" in it } shouldBe cs.size
        // a line printed after the travel, before the T: refused
        val c0 = cs[cs.size / 2]
        val printed = w.g.lines().toMutableList().also { it.add(c0.tLine, "G1 X${c0.x + 1} Y${c0.y} E0.05") }.joinToString("\n")
        refusals(printed, hop).any { it.startsWith("line ${c0.tLine + 2}:") && "not over the incoming line's start" in it } shouldBe true
        // filament pushed back before the T (a restore standing over the tower): refused
        val pushed = w.g.lines().toMutableList().also { it.add(c0.tLine, "G1 E0.3 F2100") }.joinToString("\n")
        refusals(pushed, hop).any { it.startsWith("line ${c0.tLine + 2}:") && "filament pushed" in it } shouldBe true
        // the retraction made after the travel: refused
        val lines = w.g.lines().toMutableList()
        val firstTravel = (c0.lastPrinted + 1 until c0.tLine).first { val l = lines[it]; l.startsWith("G1 ") && (" X" in l || " Y" in l) && " E" !in l }
        val retraction = (c0.lastPrinted + 1 until firstTravel).filter { " E-" in lines[it] }
        (retraction.isNotEmpty()) shouldBe true
        val moved = retraction.map { lines[it].replace(Regex(" X[-0-9.]+| Y[-0-9.]+"), "") }
        for (k in retraction.reversed()) lines.removeAt(k)
        lines.addAll(firstTravel - retraction.size + 1, moved)
        refusals(lines.joinToString("\n"), hop).any { "retracted after the travel" in it } shouldBe true
    }

    "with the purge into infill: a change before infill on the part is made over that infill's start, the same way; control: in place, refused" {
        val w = write("plan.mechanism.purge.into" to "infill")
        val cs = changes(w.g)
        val onPart = cs.filter { it.nextInObject }
        println("purge into infill: ${cs.size} changes, ${onPart.size} with the incoming first line on a part (${onPart.groupingBy { it.nextFeature }.eachCount()}), ${cs.count { it.wiped }} with a wipe")
        (onPart.size >= 5) shouldBe true
        onPart.all { it.nextFeature == "Sparse infill" } shouldBe true
        refusals(w.g, hop) shouldBe emptyList()
        prePrint(w) shouldBe emptyList()
        refusals(inPlace(w.g, SliceSettings.of(w.setup).retractMm), hop).count { "not over the incoming line's start" in it } shouldBe cs.size
    }

    "with no purge tower: every change made over the incoming filament's first line on the part; control: in place, refused" {
        // a print through Job refuses changes with the tower off (purging elsewhere is not built), so the program is made as
        // Job makes it, without the tower: part A (tool 0) a 10 mm square, part B (tool 1) a 30 mm square beside it
        val s = SliceSettings.of(testSetup())
        val n = 12
        val tops = LongArray(n) { 200L * (it + 1) }
        val sq = { x: Long, wd: Long -> listOf(plymeld.geom.Polygon(longArrayOf(x, 100_000, x + wd, 100_000, x + wd, 100_000 + wd, x, 100_000 + wd))) }
        val r = plymeld.core.MultiFilament.plan(s, listOf(List(n) { sq(100_000, 10_000) }, List(n) { sq(115_000, 30_000) }), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val present = tops.indices.map { i -> r.filaments.filter { it.layers[i].paths.isNotEmpty() }.map { it.spool }.toSet() }
        val order = plymeld.core.MultiFilament.layerOrder(present, listOf("spool 1", "spool 2"), "spool 1")
        val steps = plymeld.core.MultiFilament.steps(r, order, { sp -> if (sp == "spool 1") 0 else 1 }, "obj", tops, 0, null,
            tempOf = { _, _ -> 210 }, idleOf = { _, _ -> 170 })
        val g = java.io.StringWriter().also { KlipperWriter(s, emptyMap(), "test").write(plymeld.program.PrintProgram(steps, emptyList()), it) }.toString()
        val cs = changes(g)
        println("no tower: ${cs.size} changes, incoming first lines ${cs.groupingBy { it.nextFeature }.eachCount()}, ${cs.count { it.wiped }} with a wipe")
        (cs.size >= n - 1) shouldBe true
        cs.none { it.nextFeature == "Prime tower" } shouldBe true
        (cs.count { it.wiped } >= cs.size / 2) shouldBe true
        refusals(g, s.zHopUm / 1000.0) shouldBe emptyList()
        refusals(inPlace(g, s.retractMm), s.zHopUm / 1000.0).count { "not over the incoming line's start" in it } shouldBe cs.size
    }
})
