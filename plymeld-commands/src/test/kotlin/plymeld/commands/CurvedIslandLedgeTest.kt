package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.check.TechniqueRender
import plymeld.check.reviewRender
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.util.Locale

/**
 * A curved island over the parts of an object laid per part, each part by its own filament and tool (IncrementalSlicer:
 * perPartIsland, islandsAmong): the ledge of [LedgeInlay] (a block on tool 1, an inlay on tool 2 recessed in a pocket on the
 * block's 62.5 deg face) with the ledge's island locked. The island's area is every cell above its start joined to the cap
 * (CurvedRegions.islandAt), here all of the object above it: block and inlay both follow the island's layers, each laid by its
 * own tool in its own turn of the layer. Read back from the g-code (no arcs: firmware.arcs = false):
 * - every island line inside the inlay is tool 2's and every one inside the block tool 1's, both present; the pre-print
 *   check clean; controls: the island layers' tools swapped, and the island marks taken off, are refused;
 * - where block and inlay meet in the island's layers their walls touch as in the flat layers (gap or overlap between
 *   neighboring beads' edges, measured from the file);
 * - the tool changes: every layer, flat or the island's, changes tool once per filament it lays after the first, and not at
 *   all into it when its first filament is the one the layer before ended with (MultiFilament.layerOrder); control: two
 *   changes added to an island layer are seen;
 * - with scarf seams, Z anti-aliasing, short walls and the steady share on, the same island reads back clean;
 * - on a plate of several objects such an object is refused, saying so;
 * - review pictures (build/renders/): the cut across the ledge and the island's top layer from above, each beside the flat
 *   layers' print of the same model, for the level rim (a rectangular pocket) and the rising one (the pocket's half-round
 *   bottom).
 */
class CurvedIslandLedgeTest : StringSpec({
    val session = SliceSession()
    fun f(v: Double, d: Int = 3) = "%.${d}f".format(Locale.ROOT, v)

    /** The ledge (with the island locked, [lock]: its extra lines, else none) sliced with its check. */
    fun slice(round: Boolean, lock: List<String>?): SliceSession.Written {
        val d = Files.createTempDirectory("plymeld-ledge")
        Files.writeString(d.resolve("hw.toml"), LedgeInlay.hardware)
        TechniqueSamples.threeMf(d.resolve("ledge.3mf"), "ledge", LedgeInlay.parts(round))
        val cfs = if (lock == null) listOf("hw.toml") else { Files.writeString(d.resolve("isl.toml"), LedgeInlay.lock(*lock.toTypedArray())); listOf("hw.toml", "isl.toml") }
        val pr = Product(ConfigStack.load(cfs, emptyList(), d), listOf(d.resolve("ledge.3mf")), null) { }
        return session.write(pr, d.resolve("ledge.gcode"), { })
    }
    fun problems(w: SliceSession.Written, text: String = w.text) =
        session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, text, w.partTools, w.prediction, w.reused), { }).problems

    /** One extruding move: its ends (mm), feature, tool, width, and bead height from its plastic. */
    class Ln(val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val feature: String, val tool: Int, val w: Double, val h: Double)
    /** One layer of the file: its Z mark, whether it is the island's, its moves, the tool in force at its start, and the tool changes in it. */
    class Lay(val z: Double, val island: Boolean, val lines: List<Ln>, val toolIn: Int, val changes: List<Int>)
    fun layers(text: String): List<Lay> {
        val out = ArrayList<Lay>()
        val path = TechniqueRender.read(text)
        var tool = -1; var z = Double.NaN; var island = false; var started = false; var toolIn = -1; var changes = ArrayList<Int>(); var idx = -1
        val byLayer = path.moves.groupBy { it.layer }
        fun flush() { if (started) out += Lay(z, island, byLayer[idx].orEmpty().map { m -> Ln(m.x0, m.y0, m.z0, m.x1, m.y1, m.z1, m.feature, m.tool, m.width, path.beadHeight(m)) }, toolIn, changes) }
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE")) { flush(); started = true; idx++; island = false; toolIn = tool; changes = ArrayList(); continue }
            if (l.startsWith(";Z:")) z = l.removePrefix(";Z:").trim().toDouble()
            if (l.startsWith(plymeld.program.LayerStart.ISLAND_MARK.trim())) island = true
            if (Regex("T[0-9]+").matches(l)) { val t = l.substring(1).toInt(); if (started && t != tool) changes += t; tool = t }
        }
        flush()
        return out
    }

    // the model's face frame (LedgeInlay.at): u across, v up the face, w into the block
    val sinF = Math.sin(Math.toRadians(LedgeInlay.FACE_DEG)); val cosF = Math.cos(Math.toRadians(LedgeInlay.FACE_DEG))
    val mid = LedgeInlay.WIDTH_MM / 2
    /** How far (mm) point (x, y, z) lies inside the inlay (negative: outside it), from the model's own measures. */
    fun intoInlay(round: Boolean, x: Double, y: Double, z: Double): Double {
        val v = y * cosF + z * sinF; val w = y * sinF - z * cosF
        val r = LedgeInlay.HALF_WIDTH; val vt = LedgeInlay.BOTTOM_V + LedgeInlay.POCKET_H; val vc = LedgeInlay.BOTTOM_V + (if (round) r else 0.0)
        val dw = minOf(w - LedgeInlay.RECESS_MM, LedgeInlay.POCKET_DEPTH_MM - w)
        val duv = if (!round || v >= vc) minOf(x - (mid - r), mid + r - x, vt - v, v - LedgeInlay.BOTTOM_V) else minOf(r - Math.hypot(x - mid, v - vc), vt - v)
        return minOf(dw, duv)
    }

    /** The parts' cut by the plane x = [x] (segments x0, y0, z0, x1, y1, z1): the model's surface in the cut across the ledge. */
    fun cutAt(round: Boolean, x: Double): List<DoubleArray> = LedgeInlay.parts(round).flatMap { it.second }.mapNotNull { t ->
        val pts = ArrayList<DoubleArray>()
        for (e in 0..2) {
            val a = e * 3; val b = ((e + 1) % 3) * 3
            val da = t[a] - x; val db = t[b] - x
            if ((da < 0) != (db < 0)) { val s = da / (da - db); pts += doubleArrayOf(x, t[a + 1] + (t[b + 1] - t[a + 1]) * s, t[a + 2] + (t[b + 2] - t[a + 2]) * s) }
        }
        if (pts.size == 2) doubleArrayOf(pts[0][0], pts[0][1], pts[0][2], pts[1][0], pts[1][1], pts[1][2]) else null
    }

    /** The island's lines: each against the part holding its middle (well inside one, by 0.1 mm), the wrong ones' length per tool. */
    fun wrongTool(round: Boolean, ls: List<Lay>): Map<String, Double> {
        val out = LinkedHashMap<String, Double>()
        for (l in ls.filter { it.island }) for (s in l.lines.filter { it.feature != "Prime tower" }) {
            val d = intoInlay(round, (s.x0 + s.x1) / 2, (s.y0 + s.y1) / 2, (s.z0 + s.z1) / 2)
            val want = if (d > 0.1) 1 else if (d < -0.1) 0 else continue
            if (s.tool != want) out.merge("T${s.tool} where T$want", Math.hypot(s.x1 - s.x0, s.y1 - s.y0), Double::plus)
        }
        return out
    }

    /**
     * Where the inlay's outer wall meets the block in layers [ls]: at points every 0.1 mm along it, the gap between its bead's
     * edge and the nearest block bead's edge (negative: they overlap), where that block bead is within 0.6 mm of it.
     */
    fun meeting(ls: List<Lay>): List<Double> {
        val out = ArrayList<Double>()
        fun seg(px: Double, py: Double, s: Ln): Double {
            val dx = s.x1 - s.x0; val dy = s.y1 - s.y0; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (((px - s.x0) * dx + (py - s.y0) * dy) / l2).coerceIn(0.0, 1.0)
            return Math.hypot(s.x0 + t * dx - px, s.y0 + t * dy - py)
        }
        for (l in ls) {
            val block = l.lines.filter { it.tool == 0 && it.feature != "Prime tower" }
            for (s in l.lines.filter { it.tool == 1 && it.feature == "Outer wall" }) {
                val n = maxOf(1, (Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / 0.1).toInt())
                for (k in 0..n) {
                    val px = s.x0 + (s.x1 - s.x0) * k / n; val py = s.y0 + (s.y1 - s.y0) * k / n
                    val near = block.minByOrNull { seg(px, py, it) } ?: continue
                    val d = seg(px, py, near)
                    if (d < 0.6 + (s.w + near.w) / 2) out += d - (s.w + near.w) / 2
                }
            }
        }
        return out
    }

    /** Each layer's tool changes against the fewest its filaments need (MultiFilament.layerOrder): the layers that change more. */
    fun extraChanges(ls: List<Lay>): List<String> = ls.withIndex().mapNotNull { (i, l) ->
        val tools = l.lines.filter { it.feature != "Prime tower" }.map { it.tool }.distinct()
        val need = if (tools.isEmpty()) 0 else tools.size - (if (l.toolIn in tools) 1 else 0)
        if (l.changes.size > need) "layer ${i + 1} (z ${f(l.z)}${if (l.island) ", island" else ""}): ${l.changes.size} changes, tools ${tools.sorted()} after T${l.toolIn}" else null
    }

    /** The cut across the ledge's middle and the island's top layer from above, the island's print beside the flat one's. */
    fun pictures(name: String, round: Boolean, isl: SliceSession.Written, flat: SliceSession.Written) {
        val pi = TechniqueRender.read(isl.text); val pf = TechniqueRender.read(flat.text)
        val cut = cutAt(round, mid)
        val slab = -mid - 0.15..-mid + 0.15
        val win = TechniqueRender.Window(2.4..4.6, 4.9..6.5)
        TechniqueRender.render("The ledge ($name), cut across its middle (x = ${f(mid, 1)} mm)", TechniqueRender.View.SIDE, listOf(
            TechniqueRender.Panel(listOf("curved island (its layers locked on the ledge)", "T0 the block, T1 the inlay; beads at their plastic's height"), pi, { it.layer >= 0 && it.feature != "Prime tower" },
                TechniqueRender.Coloring.TOOL, slab = slab, plasticHeight = true, lines = cut),
            TechniqueRender.Panel(listOf("flat layers only (0.08 mm)", "the same model and machine"), pf, { it.layer >= 0 && it.feature != "Prime tower" },
                TechniqueRender.Coloring.TOOL, slab = slab, plasticHeight = true, lines = cut)),
            reviewRender("curved-island-ledge-$name-cut.png"), along = Math.PI / 2, window = win)
        // the island's top layer, and the flat layer whose top is nearest it
        val marks = isl.text.lines().let { ls -> var k = -1; ls.mapNotNull { l -> if (l.startsWith(";LAYER_CHANGE")) { k++; null } else if (l.startsWith(plymeld.program.LayerStart.ISLAND_MARK.trim())) k else null } }
        val top = marks.max()
        val topZ = pi.layerZs[top]
        val flatK = pf.nearestLayer(topZ)
        val tw = TechniqueRender.Window(mid - 2.7..mid + 2.7, 2.5..4.5)
        // the pocket's edges: where its chamfer meets the face, the ledge's rim, its inner edge (the inlay's face)
        val edges = listOf(0.0, LedgeInlay.CHAMFER_MM, LedgeInlay.RECESS_MM).flatMap { LedgeInlay.edge(round, it) }
        val edgeWords = "black: the chamfer's edge on the face, the ledge's rim, its inner edge"
        TechniqueRender.render("The ledge ($name), the top layer over it", TechniqueRender.View.TOP, listOf(
            TechniqueRender.Panel(listOf("curved island: its top layer (warped z ${f(topZ)} mm), by feature", edgeWords), pi, { it.layer == top && it.feature != "Prime tower" }, lines = edges),
            TechniqueRender.Panel(listOf("the same layer by tool (T0 block, T1 inlay)", edgeWords), pi, { it.layer == top && it.feature != "Prime tower" }, TechniqueRender.Coloring.TOOL, lines = edges),
            TechniqueRender.Panel(listOf("flat layers: the layer at z ${f(pf.layerZs[flatK])} mm, by feature", edgeWords), pf, { it.layer == flatK && it.feature != "Prime tower" }, lines = edges)),
            reviewRender("curved-island-ledge-$name-top.png"), window = tw, lineShare = 1.0)
    }

    /**
     * The beads of layers [ls] over the point (x, y) of each of [points] (named): in each layer the bead whose path passes
     * nearest it, where that is within half its width; its Z there (the path's, interpolated), its height from its plastic.
     */
    fun probe(label: String, ls: List<Lay>, points: List<Pair<String, DoubleArray>>) {
        for ((what, p) in points) {
            val hits = ls.mapNotNull { l ->
                var best: Triple<Double, Double, Double>? = null
                for (s in l.lines) {
                    if (s.feature == "Prime tower") continue
                    val dx = s.x1 - s.x0; val dy = s.y1 - s.y0; val l2 = dx * dx + dy * dy
                    val t = if (l2 == 0.0) 0.0 else (((p[0] - s.x0) * dx + (p[1] - s.y0) * dy) / l2).coerceIn(0.0, 1.0)
                    val d = Math.hypot(s.x0 + t * dx - p[0], s.y0 + t * dy - p[1])
                    if (d <= s.w / 2 && (best == null || d < best.first)) best = Triple(d, s.z0 + (s.z1 - s.z0) * t, s.h)
                }
                best?.let { (_, z, h) -> z to h }
            }
            println("$label, $what (x ${f(p[0], 2)}, y ${f(p[1], 2)}, the model's surface at z ${f(p[2])}): " + hits.joinToString { (z, h) -> "z ${f(z)} h ${f(h)}" } +
                "; steps " + hits.zipWithNext { a, b -> f(b.first - a.first) }.joinToString())
        }
    }

    /**
     * The outer walls along a face (the plane [w] into the block: 0 the block's face, RECESS_MM the inlay's) at x = [x]: in each
     * of layers [ls], the outer wall crossing x nearest the face at its own height, its Z there and its height from its plastic
     * (a face's stair-step goes with its layers' thickness there).
     */
    fun faceWalls(label: String, ls: List<Lay>, x: Double, w: Double, zs: ClosedFloatingPointRange<Double>) {
        fun yFace(z: Double) = ((z + w * cosF) / sinF).let { v -> v * cosF + w * sinF }
        val hits = ls.mapNotNull { l -> l.lines.filter { it.feature == "Outer wall" && (it.x0 - x) * (it.x1 - x) <= 0 && it.x0 != it.x1 }.mapNotNull { s ->
            val t = (x - s.x0) / (s.x1 - s.x0); val y = s.y0 + (s.y1 - s.y0) * t; val z = s.z0 + (s.z1 - s.z0) * t
            val d = Math.abs(y - yFace(z)) * sinF        // across the face
            if (d < 0.5 && z in zs) Triple(d, z, s.h) else null }.minByOrNull { it.first } }
        println("$label: walls along the face ${if (w == 0.0) "of the block" else "of the inlay"} at x ${f(x, 1)}, z ${f(zs.start, 2)}..${f(zs.endInclusive, 2)}: " +
            hits.joinToString { (_, z, h) -> "z ${f(z)} h ${f(h)}" })
    }

    /** What the island's layers are at the ledge and round it (the ledge's middle; the faces that do not follow it), and its beads' thinnest and thickest. */
    fun alongLedge(ls: List<Lay>, label: String, flat: List<Lay>) {
        val isl = ls.filter { it.island }
        probe(label, isl, listOf(
            "the ledge's inner edge" to LedgeInlay.at(mid, LedgeInlay.BOTTOM_V, LedgeInlay.RECESS_MM - 0.1),
            "the ledge's middle" to LedgeInlay.ledgePoint(),
            "the ledge's rim" to LedgeInlay.at(mid, LedgeInlay.BOTTOM_V, LedgeInlay.CHAMFER_MM + 0.05)))
        val band = isl.minOf { it.z } - 0.2..isl.maxOf { it.z }
        for ((what, set) in listOf("island" to isl, "flat" to flat)) {
            faceWalls("$label, $what", set, mid, 0.0, band)
            faceWalls("$label, $what", set, mid - 6.0, 0.0, band)
            faceWalls("$label, $what", set, mid, LedgeInlay.RECESS_MM, band)
        }
        val beads = isl.flatMap { l -> l.lines.filter { it.feature != "Prime tower" && !it.h.isNaN() } }
        println("$label: island beads from their plastic: thinnest ${f(beads.minOf { it.h })} mm, thickest ${f(beads.maxOf { it.h })} mm, over ${beads.size} moves in ${isl.size} layers")
    }

    "the level-rim ledge's island over block and inlay: each part's lines by its own tool, walls meeting as in the flat layers, no tool change beyond the layers' own; the check clean; controls refused" {
        val w = slice(round = false, lock = emptyList())
        withClue("arcs are off: every move is a G1") { w.text.lines().none { it.startsWith("G2 ") || it.startsWith("G3 ") } shouldBe true }
        val ls = layers(w.text)
        val isl = ls.filter { it.island }
        println("island layers ${isl.size}, z ${f(isl.minOf { it.z })}..${f(isl.maxOf { it.z })}; layers ${ls.size}")
        isl.size shouldBeGreaterThan 3
        // both filaments follow the island's layers, each by its own tool
        val tools = isl.flatMap { l -> l.lines.filter { it.feature != "Prime tower" }.map { it.tool } }.toSet()
        tools shouldBe setOf(0, 1)
        val wrong = wrongTool(false, ls)
        withClue("island lines by the wrong tool (mm): $wrong") { wrong.values.sum() shouldBeLessThan 0.5 }
        val clean = problems(w)
        withClue(clean.joinToString("\n")) { clean.shouldBeEmpty() }
        // controls: the island layers' tools swapped (a line in the inlay laid by T0), and the island's marks taken off
        val swapped = run { var inIsland = false; w.text.lines().joinToString("\n") { l ->
            if (l.startsWith(";LAYER_CHANGE")) inIsland = false
            if (l.startsWith(plymeld.program.LayerStart.ISLAND_MARK.trim())) inIsland = true
            if (inIsland && l == "T0") "T1" else if (inIsland && l == "T1") "T0" else l } }
        withClue("the control swaps some tool") { (swapped != w.text) shouldBe true }
        (wrongTool(false, layers(swapped)).values.sum() > 1.0) shouldBe true
        val ps = problems(w, swapped)
        println("swapped: " + ps.take(3))
        ps.any { "printed by the wrong tool" in it } shouldBe true
        problems(w, w.text.lines().filterNot { it.startsWith(plymeld.program.LayerStart.ISLAND_MARK.trim()) }.joinToString("\n")).isNotEmpty() shouldBe true
        // where the inlay meets the block: its wall's edge against the block's nearest bead's, in the island's layers and the flat ones
        val flat = slice(round = false, lock = null)
        val fls = layers(flat.text)
        val zIsl = isl.minOf { it.z }..isl.maxOf { it.z }
        val mi = meeting(isl); val mf = meeting(fls.filter { it.z > 5.6 && it.z < 7.0 })
        fun stats(xs: List<Double>) = "n ${xs.size}, min ${f(xs.min())}, median ${f(xs.sorted()[xs.size / 2])}, max ${f(xs.max())} mm"
        println("the inlay's outer wall against the block: island layers ${stats(mi)}; flat layers (z 5.6..7.0) ${stats(mf)}")
        mi.size shouldBeGreaterThan 20
        withClue("island median ${f(mi.sorted()[mi.size / 2])} against flat ${f(mf.sorted()[mf.size / 2])}") {
            Math.abs(mi.sorted()[mi.size / 2] - mf.sorted()[mf.size / 2]) shouldBeLessThan 0.05
        }
        // tool changes: each layer's own, no more
        val extra = extraChanges(ls)
        withClue(extra.joinToString("\n")) { extra.shouldBeEmpty() }
        // control: two more changes on the first island layer are seen
        val more = w.text.lines().let { t -> val k = t.indexOfFirst { it.startsWith(plymeld.program.LayerStart.ISLAND_MARK.trim()) }; t.take(k + 1) + listOf("T5", "T6") + t.drop(k + 1) }.joinToString("\n")
        extraChanges(layers(more)).isNotEmpty() shouldBe true
        val nI = ls.sumOf { it.changes.size }; val nF = fls.sumOf { it.changes.size }
        // the flat layers over the island's height: from its start as printed (its first layer's top less its height, 0.114 mm as the file says) to its top
        val flatIn = fls.filter { it.z > zIsl.start - 0.114 + 1e-6 && it.z <= zIsl.endInclusive + 1e-6 }
        println("tool changes: $nI with the island, $nF in flat layers; over the island's height: island layers ${isl.sumOf { it.changes.size }} (${isl.count { l -> l.lines.map { it.tool }.distinct().size > 1 }} of ${isl.size} with both filaments), flat layers there ${flatIn.sumOf { it.changes.size }} (${flatIn.count { l -> l.lines.filter { it.feature != "Prime tower" }.map { it.tool }.distinct().size > 1 }} of ${flatIn.size} with both)")
        extraChanges(fls).shouldBeEmpty()
        alongLedge(ls, "level rim", fls)
        pictures("level-rim", false, w, flat)
    }

    "the rising-rim ledge (the pocket's half-round bottom), its island locked from a start under the ledge: read back as the level rim's, the check clean" {
        val w = slice(round = true, lock = listOf("island.ledge.start = \"5.0 mm\""))
        val ls = layers(w.text)
        val isl = ls.filter { it.island }
        println("island layers ${isl.size}, z ${f(isl.minOf { it.z })}..${f(isl.maxOf { it.z })}")
        isl.size shouldBeGreaterThan 3
        isl.flatMap { l -> l.lines.filter { it.feature != "Prime tower" }.map { it.tool } }.toSet() shouldBe setOf(0, 1)
        val wrong = wrongTool(true, ls)
        withClue("island lines by the wrong tool (mm): $wrong") { wrong.values.sum() shouldBeLessThan 0.5 }
        val clean = problems(w)
        withClue(clean.joinToString("\n")) { clean.shouldBeEmpty() }
        extraChanges(ls).shouldBeEmpty()
        val flat = slice(round = true, lock = null)
        println("tool changes: ${ls.sumOf { it.changes.size }} with the island, ${layers(flat.text).sumOf { it.changes.size }} in flat layers")
        alongLedge(ls, "rising rim", layers(flat.text))
        pictures("rising-rim", true, w, flat)
    }

    "with scarf seams, Z anti-aliasing, short walls and the steady share on, the level-rim ledge's island reads back clean, each part's lines by its own tool" {
        val w = slice(round = false, lock = listOf("", "[plan.technique.scarf_seam]", "enabled = \"true\"", "[plan.faces.sloped_top]", "technique = \"z_antialiasing\"",
            "[plan.mechanism.motion]", "short_walls.length = \"5 mm\"", "short_walls.speed = \"20 mm/s\"", "steady.share = \"0.5\""))
        val ls = layers(w.text)
        val isl = ls.filter { it.island }
        isl.size shouldBeGreaterThan 3
        isl.flatMap { l -> l.lines.filter { it.feature != "Prime tower" }.map { it.tool } }.toSet() shouldBe setOf(0, 1)
        // the techniques are on: a scarf's ramp on some flat layer's outer wall (its Z under the layer's), the short walls slower
        withClue("a scarf ramp somewhere in the flat layers") { ls.filter { !it.island }.any { l -> l.lines.any { it.feature == "Outer wall" && minOf(it.z0, it.z1) < l.z - 1e-3 } } shouldBe true }
        val wrong = wrongTool(false, ls)
        withClue("island lines by the wrong tool (mm): $wrong") { wrong.values.sum() shouldBeLessThan 0.5 }
        val clean = problems(w)
        withClue(clean.joinToString("\n")) { clean.shouldBeEmpty() }
    }

    "an island on an object laid per part, on a plate of several objects: refused, saying so" {
        val d = Files.createTempDirectory("plymeld-ledge-plate")
        Files.writeString(d.resolve("hw.toml"), LedgeInlay.hardware)
        TechniqueSamples.threeMf(d.resolve("ledge.3mf"), "ledge", LedgeInlay.parts(false))
        Files.writeString(d.resolve("box.stl"), TechniqueSamples.stl(TechniqueSamples.box(0.0, 0.0, 0.0, 10.0, 10.0, 5.0)))
        Files.writeString(d.resolve("isl.toml"), LedgeInlay.lock())
        val pr = Product(ConfigStack.load(listOf("hw.toml", "isl.toml"), emptyList(), d), listOf(d.resolve("ledge.3mf"), d.resolve("box.stl")), null) { }
        val e = runCatching { session.write(pr, d.resolve("plate.gcode"), { }) }.exceptionOrNull()
        println("plate: ${e?.message}")
        (e != null) shouldBe true
        e!!.message!! shouldContain "has a curved island and is laid per part: on a plate of several objects that is not written yet"
    }
})
