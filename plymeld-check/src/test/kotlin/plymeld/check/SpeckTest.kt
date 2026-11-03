package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.Feature
import plymeld.core.Planner
import plymeld.core.Scarf
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * An outer-wall speck (an Arachne line kept only because it lines the outline, shorter than two of its widths) prints
 * without a retraction of its own where it is reached from its own outer wall in its island, even where the plan retracts
 * every other travel (plan.mechanism.motion.no_retract_in_island off; a restart for a 0.2 mm dot
 * left a blob on a show face): the travel to it is clear (no lift either), or, crossing its wall as every route to a speck
 * past its wall's loop does, lifted over it but not retracted (ToolPath.hopOnly). Squares 8 mm
 * across, each with a tab 0.2 mm wide and 0.6 mm long on its top edge, 10 layers of 0.2 mm, walled by Arachne (the default):
 * each tab is lined by one center line 0.41 mm long, a speck. (Earlier, thin wedges, whose tips gave specks of 0 µm,
 * points, now dropped as shorter than the outline tolerance: with them every speck's travel was clear, and none of a real
 * speck's ever is.) Controls: the other walls reached the same way still retract with the switch off; travels from another
 * piece retract.
 */
class SpeckTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.mechanism.adhesion.prime.enabled" to false, "hardware.filament.pla-generic.retract.travel.min" to "0 mm")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    fun settings(extra: List<Pair<String, Any>>) =
        SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(ConfigFiles.bundled() + seamLockedForTests + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(box))).setup)
    val n = 10
    val tops = LongArray(n) { 200L * (it + 1) }
    // squares 8 mm across, 12 mm apart, each with a tab narrower than a line on its top edge (its speck); and a 10 mm square
    // beside them, whose wall loops are reached from one another inside it (the controls' clear travels)
    val pieces = (0 until 6).map { k -> val x0 = k * 12_000L; val m = x0 + 4_000L
        Polygon(longArrayOf(x0, 0, x0 + 8_000, 0, x0 + 8_000, 8_000, m + 100, 8_000, m + 100, 8_600, m - 100, 8_600, m - 100, 8_000, x0, 8_000)) } +
        Polygon(longArrayOf(80_000, 0, 90_000, 0, 90_000, 10_000, 80_000, 10_000))
    val layers = Planner.plan(settings(listOf("plan.mechanism.motion.no_retract_in_island" to false)), List(n) { pieces }, tops)
    /** The island a point is in or nearest to (its index in [pieces]: the edge distance, 0 inside). */
    fun islandOf(x: Long, y: Long): Int = pieces.indices.minBy { i ->
        val p = pieces[i]
        if (plymeld.geom.Polygons.contains(p, x, y)) 0.0 else (0 until p.size).minOf { e ->
            val ax = p.x(e).toDouble(); val ay = p.y(e).toDouble(); val bx = p.x((e + 1) % p.size).toDouble(); val by = p.y((e + 1) % p.size).toDouble()
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((x - ax) * dx + (y - ay) * dy) / l2).coerceIn(0.0, 1.0)
            Math.hypot(x - ax - t * dx, y - ay - t * dy)
        }
    }

    /** Each outer or inner wall after the first path of a layer, by where the head comes from (the end of the path before it, in
     *  its own island or another), whether it retracts, and why. */
    fun tally(specks: Boolean): Map<String, Int> {
        val t = HashMap<String, Int>()
        for (l in layers.drop(1)) for (k in 1 until l.paths.size) {
            val b = l.paths[k]
            if (b.speck != specks || (b.feature != Feature.OUTER_WALL && b.feature != Feature.INNER_WALL)) continue
            val a = l.paths[k - 1]
            val end = if (a.closed) Scarf.endOf(a) else longArrayOf(a.xy[a.xy.size - 2], a.xy[a.xy.size - 1])
            val isl = Planner.Islands(pieces, l.paths)
            val why = when {
                !b.retractBefore -> ""
                !isl.clear(end[0], end[1], b.xy[0], b.xy[1]) { false } -> " (leaves the island)"
                !isl.clear(end[0], end[1], b.xy[0], b.xy[1]) { it.outer } -> " (crosses or grazes an outer wall)"
                !isl.clear(end[0], end[1], b.xy[0], b.xy[1]) { it.k < k } -> " (crosses or grazes an earlier wall)"
                else -> " (clear)"
            }
            val how = if (!b.retractBefore) " none" else if (b.hopOnly) " hop" else " retract"
            t.merge((if (islandOf(end[0], end[1]) == islandOf(b.xy[0], b.xy[1])) "same" else "other") + how + (if (b.hopOnly) "" else why), 1, Int::plus)
        }
        return t
    }

    "with no_retract_in_island off, specks reached from their own piece print without a retraction (lifted over their wall); other walls reached clear still retract (the control)" {
        // real specks, longer than the outline tolerance
        val specks = layers.drop(1).flatMap { it.paths }.filter { it.speck }
        (specks.size > 0) shouldBe true
        specks.all { Planner.polylineLength(it.xy) > 300.0 } shouldBe true
        val s = tally(specks = true); val w = tally(specks = false)
        println("specks: $s\nother walls: $w")
        ((s["same hop"] ?: 0) + (s["same none"] ?: 0) > 0) shouldBe true
        s.keys.none { it.startsWith("same retract") } shouldBe true
        s.keys.filter { it.startsWith("other") }.all { it.startsWith("other retract") } shouldBe true
        // the control: the switch is off, so a wall that is not a speck retracts even where its travel is clear
        ((w["same retract (clear)"] ?: 0) > 0) shouldBe true
        w.keys.none { it.startsWith("same none") } shouldBe true
    }

    "each speck is laid right after the outer wall it lines, or another speck of that wall: never reached from another piece" {
        var specks = 0; val bad = ArrayList<String>()
        for (l in layers.drop(1)) for (k in l.paths.indices) {
            val b = l.paths[k]
            if (!b.speck) continue
            specks++
            val a = l.paths.getOrNull(k - 1)
            if (a == null || a.feature != Feature.OUTER_WALL || islandOf(a.xy[0], a.xy[1]) != islandOf(b.xy[0], b.xy[1])) bad += "layer ${l.index}: speck at (${b.xy[0]}, ${b.xy[1]}) after ${a?.feature} at (${a?.xy?.get(0)}, ${a?.xy?.get(1)})"
        }
        println("specks $specks, not after their own wall: ${bad.size}" + bad.take(5).joinToString("") { "\n  $it" })
        (specks > 0) shouldBe true
        bad shouldBe emptyList()
    }

    "with no_retract_in_island unset (decided per object), a cleared or hopped-to speck is not marked as the in-island rule's, so the decision's 'off' side leaves it unretracted; the other walls it clears are marked (the control)" {
        val cleared = Planner.plan(settings(emptyList()), List(n) { pieces }, tops).drop(1).flatMap { it.paths }
            .filter { (it.feature == Feature.OUTER_WALL || it.feature == Feature.INNER_WALL) && !it.retractBefore }
        val (specks, walls) = cleared.partition { it.speck }
        val hops = Planner.plan(settings(emptyList()), List(n) { pieces }, tops).drop(1).flatMap { it.paths }.filter { it.speck && it.hopOnly }
        println("cleared without a retraction: ${specks.size} specks (${specks.count { it.islandHidden }} marked), ${hops.size} specks hopped to (${hops.count { it.islandHidden }} marked), ${walls.size} other walls (${walls.count { it.islandHidden }} marked)")
        (specks.size + hops.size > 0) shouldBe true
        (specks + hops).none { it.islandHidden } shouldBe true
        walls.any { it.islandHidden } shouldBe true
    }
})
