package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.slice
import plymeld.commands.TechniqueSamples.strip
import plymeld.commands.TechniqueSamples.threeMf
import plymeld.check.TechniqueRender
import plymeld.check.TechniqueRender.Coloring
import plymeld.check.TechniqueRender.Panel
import plymeld.check.TechniqueRender.View
import plymeld.check.TechniqueRender.Window
import plymeld.check.reviewRender
import kotlin.math.abs

/**
 * Sample toolpaths of the waved first layer's options (`plan.technique.bridge.first_layer = "wave_overhang"` and its
 * `wave.*` values), drawn into docs/renders/technique_wave_bridge_*.png.
 * The model: a flat strip 10 mm wide and 1.2 mm thick spanning 20 mm between two pillars 8 x 10 x 6 mm, its first layer
 * locked to waves (`band.1.technique = "bridge"`), from above, each line drawn thin and colored by print order. Each test
 * first asserts from the g-code what its picture shows, against the option's default on the same model:
 *  - `wave.line.direction`: the edge each bead across the span starts from (as grown with `mirrored`, the default: each
 *    side's beads from one edge, the two sides from opposite edges; `matched`: every bead from one edge; `zigzag`: the edge
 *    changing from bead to bead along each side);
 *  - `wave.start`: what is laid first (`edge_strips`: the first quarter of the wave plastic further out towards the edges
 *    than the default's; `triangles` and `opposite_triangles`: the last quarter, the region left between the triangles,
 *    on one edge for both supports, or on opposite edges);
 *  - `wave.front = "chevron"`: fronts as chevrons (`along_fronts`: the lines diagonal to the span; `across`: the lines
 *    across it as the default's, laid in another order; `wave.chevron.leading = "middle"`: the first quarter of the plastic
 *    nearer the strip's middle line than with the edges leading);
 *  - `wave.layers = 2`: the first two layers both waved (`wave.second_layer = "x"`: the second only Xs, diagonal and far
 *    shorter than a full second layer);
 *  - `wave.after_supported`: the waves laid after the strip's part over the pillars on that layer (the default: before).
 * The model's sizes are the test's own (plymeld's choice).
 */
class WaveBridgeSampleTest : StringSpec({
    val d = TechniqueSamples.dir()
    threeMf(d.resolve("span.3mf"), "span", listOf(
        "supports" to box(-18.0, -5.0, 0.0, -10.0, 5.0, 6.0) + box(10.0, -5.0, 0.0, 18.0, 5.0, 6.0),
        "strip" to strip(listOf(-18.0 to 6.0, 18.0 to 6.0))))
    fun lock(extra: String = "") = "[plan.object.span]\nband.1.technique = \"bridge\"\n[plan.technique.bridge]\nfirst_layer = \"wave_overhang\"\n$extra"
    fun cx(p: TechniqueRender.Toolpath): Double { val ms = p.moves.filter { it.layer >= 1 }; return (ms.minOf { minOf(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2 }
    fun cy(p: TechniqueRender.Toolpath): Double { val ms = p.moves.filter { it.layer >= 1 }; return (ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2 }
    val wave: (TechniqueRender.Move) -> Boolean = { it.feature == "Wave overhang" }
    /** The strip's first layer: the first with a wave move. */
    fun first(p: TechniqueRender.Toolpath): Int = p.moves.first(wave).layer
    /** The wave moves of layer [k], in print order. */
    fun waves(p: TechniqueRender.Toolpath, k: Int = first(p)) = p.layer(k).filter(wave)
    /** The beads of layer [k]: runs of contiguous wave moves. */
    fun beads(p: TechniqueRender.Toolpath, k: Int = first(p)): List<List<TechniqueRender.Move>> {
        val out = ArrayList<MutableList<TechniqueRender.Move>>(); var prev: TechniqueRender.Move? = null
        for (m in p.layer(k)) {
            if (!wave(m)) { prev = null; continue }
            if (prev == null || prev.index != m.index - 1 || Math.hypot(prev.x1 - m.x0, prev.y1 - m.y0) > 1e-6) out += arrayListOf(m) else out.last() += m
            prev = m
        }
        return out
    }
    /** The moves of [ms] laid within [from, to) of their plastic, in print order (shares of the total, 0..1). */
    fun share(ms: List<TechniqueRender.Move>, from: Double, to: Double): List<TechniqueRender.Move> {
        val total = ms.sumOf { it.e }; var acc = 0.0
        return ms.filter { m -> val mid = (acc + m.e / 2) / total; acc += m.e; mid >= from && mid < to }
    }
    /** The mean distance from the strip's middle line (y) of [ms], weighted by length. */
    fun meanOff(ms: List<TechniqueRender.Move>, c: Double) = ms.sumOf { abs((it.y0 + it.y1) / 2 - c) * it.length } / ms.sumOf { it.length }
    /** The share of [ms]' length running across the span (within 30 degrees of the Y axis; the span runs along X). */
    fun across(ms: List<TechniqueRender.Move>) = ms.filter { abs(it.y1 - it.y0) > 1.73 * abs(it.x1 - it.x0) }.sumOf { it.length } / ms.sumOf { it.length }
    /** The share of [ms]' length running diagonal to the span (more than 30 degrees off both axes): an X's lines. */
    fun diagonal(ms: List<TechniqueRender.Move>) = ms.filter { val dx = abs(it.x1 - it.x0); val dy = abs(it.y1 - it.y0); dx > 0.58 * dy && dy > 0.58 * dx }.sumOf { it.length } / ms.sumOf { it.length }
    fun window(p: TechniqueRender.Toolpath) = Window(cx(p) - 11.0..cx(p) + 11.0, cy(p) - 5.6..cy(p) + 5.6)
    fun panel(label: List<String>, p: TechniqueRender.Toolpath, k: Int = first(p), marks: List<TechniqueRender.Mark> = emptyList()) =
        Panel(label, p, { it.layer == k }, Coloring.ORDER, marks = marks, highlight = wave)
    /** A ring at the start of each bead across the span: the edge it starts from. */
    fun startMarks(p: TechniqueRender.Toolpath) = beads(p).filter { b -> abs(b.last().y1 - b.first().y0) > 4.0 }.map { TechniqueRender.Mark(it.first().x0, it.first().y0, it.first().z0) }

    "which edge the beads across the span start from: mirrored, matched, zigzag" {
        val mirrored = slice(d, listOf("span.3mf"), lock(), "wave_dir_mirrored.gcode")
        val matched = slice(d, listOf("span.3mf"), lock("wave.line.direction = \"matched\"\n"), "wave_dir_matched.gcode")
        val zigzag = slice(d, listOf("span.3mf"), lock("wave.line.direction = \"zigzag\"\n"), "wave_dir_zigzag.gcode")
        /** Per bead running across the span: (which side of the gap, which edge it starts from). */
        fun starts(p: TechniqueRender.Toolpath): List<Pair<Boolean, Boolean>> { val c = cx(p); val m = cy(p)
            return beads(p).filter { b -> abs(b.last().y1 - b.first().y0) > 4.0 }.map { b -> ((b.first().x0 < c) to (b.first().y0 < m)) } }
        val sm = starts(mirrored); val sa = starts(matched); val sz = starts(zigzag)
        withClue("beads across the span: ${sm.size}, ${sa.size}, ${sz.size}") { sm.size shouldBeGreaterThan 10; sa.size shouldBeGreaterThan 10; sz.size shouldBeGreaterThan 10 }
        withClue("mirrored: each side from one edge, the two sides from opposite edges") {
            sm.filter { it.first }.map { it.second }.distinct().size shouldBe 1
            sm.filter { !it.first }.map { it.second }.distinct().size shouldBe 1
            (sm.first { it.first }.second != sm.first { !it.first }.second) shouldBe true
        }
        withClue("matched: every bead from one edge") { sa.map { it.second }.distinct().size shouldBe 1 }
        withClue("zigzag: the edge changes from bead to bead along each side") {
            for (side in listOf(true, false)) { val e = sz.filter { it.first == side }.map { it.second }; e.zipWithNext().count { (a, b) -> a != b } shouldBeGreaterThan e.size / 2 }
        }
        TechniqueRender.render("Waved first layer, the beads' starting edge (ringed): a flat strip over a 20 mm gap, by print order", View.TOP, listOf(
            panel(listOf("mirrored (default)", "wave.line.direction unset", "each side's beads from one edge, the sides from opposite edges"), mirrored, marks = startMarks(mirrored)),
            panel(listOf("matched", "wave.line.direction = \"matched\"", "every bead from the same edge"), matched, marks = startMarks(matched)),
            panel(listOf("zigzag", "wave.line.direction = \"zigzag\"", "the starting edge changes from bead to bead"), zigzag, marks = startMarks(zigzag)),
        ), reviewRender("technique_wave_bridge_direction.png"), window = window(mirrored), lineShare = 0.4)
    }

    "what a waved first layer lays first: the whole span, edge strips, triangles, opposite triangles" {
        val whole = slice(d, listOf("span.3mf"), lock(), "wave_start_whole.gcode")
        val strips = slice(d, listOf("span.3mf"), lock("wave.start = \"edge_strips\"\nwave.strip.width = \"2 mm\"\n"), "wave_start_strips.gcode")
        val tri = slice(d, listOf("span.3mf"), lock("wave.start = \"triangles\"\n"), "wave_start_tri.gcode")
        val opp = slice(d, listOf("span.3mf"), lock("wave.start = \"opposite_triangles\"\n"), "wave_start_opp.gcode")
        val c = cy(whole)
        withClue("edge strips first: the first quarter of the plastic further out than the whole span's") {
            meanOff(share(waves(strips), 0.0, 0.25), c) shouldBeGreaterThan meanOff(share(waves(whole), 0.0, 0.25), c) + 1.0
        }
        /** The mean signed y offset from the middle line of each half of the gap's last quarter (what the triangles left). */
        fun lastBySide(p: TechniqueRender.Toolpath): Pair<Double, Double> { val x = cx(p); val last = share(waves(p), 0.75, 1.0)
            fun mean(ms: List<TechniqueRender.Move>) = ms.sumOf { ((it.y0 + it.y1) / 2 - c) * it.length } / ms.sumOf { it.length }
            return mean(last.filter { (it.x0 + it.x1) / 2 < x }) to mean(last.filter { (it.x0 + it.x1) / 2 >= x }) }
        val (tl, tr) = lastBySide(tri); val (ol, or) = lastBySide(opp)
        withClue("triangles: what is left lies on one edge for both supports ($tl, $tr)") { (tl * tr > 0) shouldBe true }
        withClue("opposite triangles: on opposite edges ($ol, $or)") { (ol * or < 0) shouldBe true }
        TechniqueRender.render("Waved first layer, what is laid first: a flat strip over a 20 mm gap, by print order", View.TOP, listOf(
            panel(listOf("the whole span (default)", "wave.start unset", "fronts from both supports to the middle"), whole),
            panel(listOf("edge strips", "wave.start = \"edge_strips\", wave.strip.width = \"2 mm\"", "a strip along each edge first, then the rest"), strips),
            panel(listOf("triangles", "wave.start = \"triangles\"", "a triangle from each support to one edge's middle first"), tri),
            panel(listOf("opposite triangles", "wave.start = \"opposite_triangles\"", "the two triangles' points on opposite edges"), opp),
        ), reviewRender("technique_wave_bridge_start.png"), window = window(whole), lineShare = 0.4)
    }

    "chevron fronts: straight, chevron with the edges leading, the middle leading, lines across" {
        val straight = slice(d, listOf("span.3mf"), lock(), "wave_front_straight.gcode")
        val edges = slice(d, listOf("span.3mf"), lock("wave.front = \"chevron\"\n"), "wave_front_edges.gcode")
        val middle = slice(d, listOf("span.3mf"), lock("wave.front = \"chevron\"\nwave.chevron.leading = \"middle\"\n"), "wave_front_middle.gcode")
        val crossLines = slice(d, listOf("span.3mf"), lock("wave.front = \"chevron\"\nwave.chevron.lines = \"across\"\n"), "wave_front_across.gcode")
        val c = cy(straight)
        /** The wave lines' length by direction (degrees from the span's axis X, 0..180, in 15 degree bins), for the messages. */
        fun dirs(ms: List<TechniqueRender.Move>) = ms.groupBy { (((Math.toDegrees(Math.atan2(it.y1 - it.y0, it.x1 - it.x0)) + 180) % 180) / 15).toInt() * 15 }
            .toSortedMap().map { (a, g) -> "$a: %.0f".format(java.util.Locale.ROOT, g.sumOf { it.length }) }
        // straight fronts lay their lines across the span; a chevron's lines along its fronts run slanted along the span
        withClue("lines across the span: chevron ${across(waves(edges))}, straight ${across(waves(straight))}; edges ${dirs(waves(edges))}, straight ${dirs(waves(straight))}") {
            across(waves(edges)) shouldBeLessThan 0.2; across(waves(straight)) shouldBeGreaterThan 0.8
        }
        withClue("chevron, lines across: ${across(waves(crossLines))}; ${dirs(waves(crossLines))}") { across(waves(crossLines)) shouldBeGreaterThan 0.8 }
        withClue("the middle leading: the first quarter of the plastic nearer the middle line than with the edges leading") {
            meanOff(share(waves(middle), 0.0, 0.25), c) shouldBeLessThan meanOff(share(waves(edges), 0.0, 0.25), c)
        }
        TechniqueRender.render("Waved first layer, chevron fronts: a flat strip over a 20 mm gap, by print order", View.TOP, listOf(
            panel(listOf("straight fronts (default)", "wave.front unset", "each front straight across the span"), straight),
            panel(listOf("chevron, the edges leading", "wave.front = \"chevron\"", "lines along slanted fronts, the diamond between last"), edges),
            panel(listOf("chevron, the middle leading", "wave.front = \"chevron\", wave.chevron.leading = \"middle\"", "the middle reaches the center first, the edges last"), middle),
            panel(listOf("chevron, lines across", "wave.front = \"chevron\", wave.chevron.lines = \"across\"", "lines across the span, laid in the chevron's order"), crossLines),
        ), reviewRender("technique_wave_bridge_chevron.png"), window = window(straight), lineShare = 0.4)
    }

    // the second layer of Xs (wave.second_layer = "x"): the Xs, and the strip's edges over the gap laid one layer up as well,
    // so the strip's outline keeps its wall on that layer and the pre-print check passes (the slice helper requires it)
    "two waved layers, the second only Xs: its edges laid too, so the check passes" {
        val x = slice(d, listOf("span.3mf"), lock("wave.sides = \"alternate\"\nwave.layers = \"2\"\nwave.second_layer = \"x\"\nwave.x_length = \"4 mm\"\n"), "wave_layers_x.gcode")
        val above: (TechniqueRender.Move) -> Boolean = { it.feature == "Wave overhang, layer above" }
        val c = cx(x); val mid = cy(x)
        fun overGap(m: TechniqueRender.Move) = abs((m.x0 + m.x1) / 2 - c) < 9.5
        fun alongSpan(m: TechniqueRender.Move) = abs(m.x1 - m.x0) > 1.73 * abs(m.y1 - m.y0)
        // an edge piece: a move along the span near one of the strip's two edges (y = the middle +- 2.5 mm), over the gap
        fun edgeLength(ms: List<TechniqueRender.Move>, side: Int) =
            ms.filter { overGap(it) && alongSpan(it) && side * ((it.y0 + it.y1) / 2 - mid) > 1.8 }.sumOf { it.length }
        val up = x.moves.filter(above); val first = x.moves.filter(wave)
        withClue("the layer above is Xs: ${"%.2f".format(java.util.Locale.ROOT, diagonal(up.filter { overGap(it) && !alongSpan(it) }))} of its other length diagonal") {
            diagonal(up.filter { overGap(it) && !alongSpan(it) }) shouldBeGreaterThan 0.9 }
        for (side in listOf(-1, 1)) {
            val a = edgeLength(up, side); val b = edgeLength(first, side)
            // the first layer's own edge over the gap is the reference: the layer above lays the same pieces again
            withClue("edge %+d over the gap: %.1f mm one layer up against %.1f mm on the first layer".format(java.util.Locale.ROOT, side, a, b)) {
                (b > 15.0) shouldBe true
                abs(a - b) shouldBeLessThan 0.5 }
        }
    }

    "two waved layers: one, and two" {
        val sides = "wave.sides = \"alternate\"\n"
        val one = slice(d, listOf("span.3mf"), lock(sides), "wave_layers_one.gcode")
        val two = slice(d, listOf("span.3mf"), lock(sides + "wave.layers = \"2\"\n"), "wave_layers_two.gcode")
        // the second waved layer is laid with the first, front by front: its beads ("Wave overhang, layer above") one layer up
        val above: (TechniqueRender.Move) -> Boolean = { it.feature == "Wave overhang, layer above" }
        val up = two.moves.filter(above); val firstZ = two.moves.filter(wave).map { it.z0 }.average()
        withClue("beads of a second waved layer: ${up.size} with two layers, ${one.moves.count(above)} with one") { up.size shouldBeGreaterThan 10; one.moves.count(above) shouldBe 0 }
        withClue("the second layer's beads one layer above the first's: %.3f against %.3f".format(java.util.Locale.ROOT, up.map { it.z0 }.average(), firstZ)) {
            (up.map { it.z0 }.average() - firstZ) shouldBeGreaterThan 0.1 }
        // laid together: the fronts alternate, a first-layer front, then the same front one layer up
        val runs = two.moves.filter { wave(it) || above(it) }.map { above(it) }.zipWithNext().count { (a, b) -> a != b }
        withClue("the two layers alternate $runs times") { runs shouldBeGreaterThan 10 }
        // from the side, cut along the strip's middle: the beads across the span seen end on, one row per waved layer
        val both: (TechniqueRender.Move) -> Boolean = { wave(it) || above(it) }
        val zs = two.moves.filter(both).map { it.z0 }
        val side = Window(cx(one) - 11.0..cx(one) + 11.0, zs.min() - 0.4..zs.max() + 0.4)
        fun slab(p: TechniqueRender.Toolpath) = cy(p) - 0.25..cy(p) + 0.25
        TechniqueRender.render("Waved layers over a 20 mm gap, cut along the strip's middle, by print order", View.SIDE, listOf(
            Panel(listOf("one waved layer (default)", "wave.layers unset, wave.sides = \"alternate\"", "one row of beads over the gap"), one,
                { both(it) }, Coloring.ORDER, slab = slab(one)),
            Panel(listOf("two waved layers", "wave.layers = \"2\"", "two rows, laid together: each front, then the same front one layer up"), two,
                { both(it) }, Coloring.ORDER, slab = slab(two)),
        ), reviewRender("technique_wave_bridge_layers.png"), zScale = 8.0, window = side)
    }

    "the waves before or after the strip over the pillars" {
        val before = slice(d, listOf("span.3mf"), lock(), "wave_after_off.gcode")
        val after = slice(d, listOf("span.3mf"), lock("wave.after_supported = \"true\"\n"), "wave_after_on.gcode")
        /** (the first wave move, the last move of the strip over the pillars) on the strip's first layer, by place in print order. */
        fun order(p: TechniqueRender.Toolpath): Pair<Int, Int> { val k = first(p); val c = cx(p)
            val ms = p.layer(k)
            return ms.first(wave).index to ms.filter { !wave(it) && abs((it.x0 + it.x1) / 2 - c) > 10.5 }.maxOf { it.index } }
        val (bw, bs) = order(before); val (aw, as_) = order(after)
        withClue("default: the waves start at move $bw, before the strip over the pillars ends at $bs") { (bw < bs) shouldBe true }
        withClue("after_supported: the waves start at move $aw, after it ends at $as_") { (aw > as_) shouldBe true }
        val kb = first(before); val ka = first(after)
        TechniqueRender.render("Waved first layer, before or after the strip over the pillars: a flat strip over a 20 mm gap, by print order", View.TOP, listOf(
            Panel(listOf("before (default)", "wave.after_supported unset", "the waves first, resting on the pillars' edge"), before, { it.layer == kb }, Coloring.ORDER),
            Panel(listOf("after", "wave.after_supported = \"true\"", "the strip over the pillars first, the waves attached to its edge"), after, { it.layer == ka }, Coloring.ORDER),
        ), reviewRender("technique_wave_bridge_after_supported.png"), window = Window(cx(before) - 19.0..cx(before) + 19.0, cy(before) - 5.6..cy(before) + 5.6), lineShare = 0.4)
    }
})
