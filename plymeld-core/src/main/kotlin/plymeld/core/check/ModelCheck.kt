package plymeld.core.check

import plymeld.program.Counter
import plymeld.geom.Polygon
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * Judges a print's g-code against the MODEL, not against how it was planned (the Kotlin
 * successor of an earlier deposition check's two main tests): every layer's printed lines are compared
 * with the mesh's own cross-section at the layer's mid height, cut by the mesh slicer (not the planner):
 *
 *  - outside: the model's lines (walls, infill, surfaces) more than half a line plus [tolUm] outside
 *    the cross-section (the part printed oversize, or something printed where nothing is);
 *  - uncovered outline: stretches of the cross-section's outline with no wall within one line width of
 *    them (a wall or a feature missing). Parts of the outline in places narrower than a line (slivers,
 *    a feature's tip) are left out: no slicer could wall them. Gaps in the cross-section narrower than
 *    [tolUm] are not outline (it is closed by half the tolerance first): two pieces that close are one;
 *  - Z anti-aliasing (a [ZaaRule]): a layer's outline also takes in its cut at the tool's thinnest layer above its bottom
 *    (the layer reaches out over a slope to there), and a point of a line below its layer, on the model's top within the
 *    layer, is not outside;
 *  - tool (when the config's part tools are given): the model's lines printed by the tool (the T command in force) the
 *    config's loadout assigns the part whose own cross-section holds them.
 *
 * Support, brim, purge tower, and the start template's lines are not the model's and are not judged.
 */
object ModelCheck {
    private val TOOL = Regex("T\\d+")
    /** Features that are the model itself (both dialects' names). */
    internal val MODEL = setOf("Outer wall", "Inner wall", "Overhang wall", "Sparse infill", "Internal solid infill", "Solid infill",
        "Top surface", "Bottom surface", "Bridge", "Internal Bridge", "Gap infill", "Ironing", "Wave overhang", ABOVE)
    /** What stands along the model's outline: a wall, or a wave bead carrying the edge over air (its last bead runs along the far edge, half a bead in). */
    private val WALLS = setOf("Outer wall", "Overhang wall", "Wave overhang", ABOVE)
    /** A wave bead laid one layer above its own (IncrementalSlicer.WAVE_ABOVE): one of the next layer's lines. */
    private const val ABOVE = "Wave overhang, layer above"

    internal class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double, val wall: Boolean, val tool: Int = 0,
                       val z0: Double = Double.NaN, val z1: Double = Double.NaN)

    /**
     * [parts]: the placed meshes in bed coordinates; [zBaseMm]: the height of the bed in their frame
     * (g-code Z = mesh Z - zBase). [tolUm]: extra allowance beyond half a line (fuzzy skin, rounding); gaps in the
     * cross-section narrower than it are not outline.
     * [imagesDir]: when given, a picture of each layer with a problem (ModelCheckPlot). [surface]: the
     * model cut at this g-code's layers, when already made (shared with GcodeCheck's travel rule).
     * [partTools]: the tool (0-based, T0 = the tool of index 1) the config's loadout assigns each of [parts], in their
     * order; null: the tool is not judged.
     */
    fun check(text: String, parts: List<TriangleSoup>, zBaseMm: Double, tolUm: Long = 100, maxProblems: Int = 30,
              imagesDir: java.nio.file.Path? = null, surface: Surface? = null, partTools: List<Int>? = null, zaa: ZaaRule? = null,
              progress: plymeld.program.Progress = plymeld.program.Progress.NONE): List<String> {
        require(partTools == null || partTools.size == parts.size) { "${partTools!!.size} part tools for ${parts.size} parts" }
        val sf = surface ?: Surface(parts, zBaseMm, text, zaaMinMm = zaa?.minMm)
        val layers = sf.layerLines
        if (layers.isEmpty()) return listOf("no layers in the g-code")
        // this check's own pictures from an earlier run would describe another file: cleared first
        imagesDir?.takeIf { java.nio.file.Files.isDirectory(it) }?.let { d ->
            java.nio.file.Files.list(d).use { s -> s.filter { it.fileName.toString().matches(Regex("z[0-9.]+\\.png")) }.forEach { java.nio.file.Files.delete(it) } }
        }
        val tol = tolUm / 1000.0
        if (imagesDir != null) System.setProperty("java.awt.headless", "true")
        // every layer on its own, in parallel; the problems in layer order
        return plymeld.core.Parallel.map(layers.size, progress.Counter("model check", layers.size)) { k -> layerProblems(sf, k, tol, imagesDir, zaa) + toolProblems(sf, k, tol, partTools) }.flatten().take(maxProblems)
    }

    /**
     * The tool rule: every model line printed by the tool of a part whose cross-section holds it. A point of a line is
     * wrong when no part on the line's tool holds it (within half the line and the tolerance) while a part on another tool
     * does; a point no part holds is the outside rule's. Refused over 0.5 mm wrong on a layer (the outside rule's own
     * allowance; plymeld's choice). With every part on one tool, simply: every model line by that tool, none
     * excused.
     */
    private fun toolProblems(sf: Surface, k: Int, tol: Double, partTools: List<Int>?): List<String> {
        if (partTools == null) return emptyList()
        val layer = sf.layerLines[k]
        val one = partTools.distinct().singleOrNull()
        var wrongMm = 0.0; var at: Seg? = null; var wanted: Set<Int> = emptySet()
        for (s in layer.segs) {
            val len = Math.hypot(s.x1 - s.x0, s.y1 - s.y0)
            if (one != null) { if (s.tool != one) { wrongMm += len; if (at == null) { at = s; wanted = setOf(one) } }; continue }
            val n = maxOf(1, Math.ceil(len / 0.5).toInt())
            for (j in 0..n) {
                val px = s.x0 + (s.x1 - s.x0) * j / n; val py = s.y0 + (s.y1 - s.y0) * j / n
                val reach = s.w / 2 + tol
                val holding = partTools.indices.filter { p -> sf.partGrid(p, k).let { g -> g.inside(px, py) || g.nearest(px, py, reach) <= reach } }
                if (holding.isEmpty() || holding.any { partTools[it] == s.tool }) continue
                wrongMm += len / (n + 1)
                if (at == null) { at = s; wanted = holding.map { partTools[it] }.toSet() }
            }
        }
        if (wrongMm <= (if (one != null) 0.0 else 0.5)) return emptyList()
        val s = at!!
        return listOf("layer at z ${f(layer.z, 3)}: ${f(wrongMm, 1)} mm of the model's lines printed by the wrong tool: T${s.tool} (tool ${s.tool + 1}) " +
            "at (${f(s.x0, 2)}, ${f(s.y0, 2)}), where the part there is assigned ${wanted.sorted().joinToString(" or ") { "T$it (tool ${it + 1})" }}")
    }

    /** One layer's problems (and its picture, when [imagesDir] is given). */
    private fun layerProblems(sf: Surface, k: Int, tol: Double, imagesDir: java.nio.file.Path?, zaa: ZaaRule?): List<String> {
        val out = ArrayList<String>()
        val layer = sf.layerLines[k]
        run {
            // the outline the walls must cover (with Z anti-aliasing, reaching out over slopes); "outside" is judged on the
            // cut at the layer's middle, a point of a line lowered onto the model's top excused (below)
            val loops = sf.wallLoops(k)
            val model = layer.segs
            if (loops.isEmpty()) { if (model.isNotEmpty()) out += "layer at z ${f(layer.z, 3)}: ${model.size} model lines printed where the model has no cross-section"; return out }
            if (model.isEmpty()) {
                // a cross-section with nothing of the model printed on it: a sliver too thin to print, or a missing layer (its
                // cut at the middle: what reaches out over a slope with Z anti-aliasing is a sliver by nature)
                val area = sf.loops(k).sumOf { it.signedAreaMm2() }
                if (area > 1.0) out += "layer at z ${f(layer.z, 3)}: nothing of the model printed, though its cross-section there is ${f(area, 1)} mm2"
                return out
            }
            val edges = sf.grid(k)
            // outside
            var outsideMm = 0.0; var worst = 0.0; var wx = 0.0; var wy = 0.0
            val outPts = ArrayList<DoubleArray>(); val uncPts = ArrayList<DoubleArray>()
            for (s in model) {
                val len = Math.hypot(s.x1 - s.x0, s.y1 - s.y0); val n = maxOf(1, Math.ceil(len / 0.5).toInt())
                for (j in 0..n) {
                    val px = s.x0 + (s.x1 - s.x0) * j / n; val py = s.y0 + (s.y1 - s.y0) * j / n
                    if (edges.inside(px, py)) continue
                    // Z anti-aliasing: a point of a line below its layer, on the model's top within the layer (the rule's own
                    // reading of the model), is where the model is, though outside its cut at the layer's middle
                    if (zaa != null && !s.z0.isNaN() && !s.z1.isNaN()) {
                        val pz = s.z0 + (s.z1 - s.z0) * j / n
                        if (pz < layer.z - 1e-6 && zaa.fits(px, py, pz, layer.z - layer.h, layer.z)) continue
                    }
                    val d = edges.nearest(px, py, 5.0)
                    if (d > s.w / 2 + tol) { outsideMm += len / (n + 1); outPts += doubleArrayOf(px, py); if (d > worst) { worst = d; wx = px; wy = py } }
                }
            }
            val badOutside = outsideMm > 0.5
            if (badOutside) out += "layer at z ${f(layer.z, 3)}: ${f(outsideMm, 1)} mm of the model's lines outside the model, up to ${if (worst >= 5.0) "5+" else f(worst, 2)} mm out (at ${f(wx, 2)}, ${f(wy, 2)})"
            // uncovered outline
            val walls = model.filter { it.wall }
            val w = walls.maxOfOrNull { it.w } ?: model.maxOf { it.w }
            val wallGrid = Grid(walls.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) })
            class Uncovered(val total: Double, val longest: Double, val x: Double, val y: Double, val pts: List<DoubleArray>)
            fun uncoveredOf(outline: List<Polygon>): Uncovered {
                // where a line's center could go: the cross-section shrunk by half a line. An outline point farther than
                // half a line from it is in a part narrower than a line (a sliver, a feature's tip): nothing could wall it
                val centerGrid by lazy {   // only on layers with an outline point no wall is near (the offset is the slow part)
                    val centers = plymeld.geom.Offset.offset(outline, -Math.round(w * 500))
                    Grid(centers.flatMap { p -> (0 until p.size).map { i -> doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, p.x((i + 1) % p.size) / 1000.0, p.y((i + 1) % p.size) / 1000.0) } })
                }
                var uncovered = 0.0; var run = 0.0; var longest = 0.0; var ux = 0.0; var uy = 0.0
                val pts = ArrayList<DoubleArray>()
                for (loop in outline) {
                    if (Math.abs(loop.signedAreaMm2()) < w * w * 2) continue                // too small to hold a line
                    val m = loop.size
                    for (i in 0 until m) {
                        val ax = loop.x(i) / 1000.0; val ay = loop.y(i) / 1000.0
                        val bx = loop.x((i + 1) % m) / 1000.0; val by = loop.y((i + 1) % m) / 1000.0
                        val len = Math.hypot(bx - ax, by - ay); val n = maxOf(1, Math.ceil(len / 0.5).toInt())
                        for (j in 0 until n) {
                            val px = ax + (bx - ax) * (j + 0.5) / n; val py = ay + (by - ay) * (j + 0.5) / n
                            val near = wallGrid.nearest(px, py, w + tol) <= w + tol
                            val wallable = !near && centerGrid.nearest(px, py, w) <= w / 2 + tol
                            if (!near && wallable) { uncovered += len / n; run += len / n; pts += doubleArrayOf(px, py); if (run > longest) { longest = run; ux = px; uy = py } } else run = 0.0
                        }
                    }
                    run = 0.0
                }
                return Uncovered(uncovered, longest, ux, uy, pts)
            }
            // A gap in the cross-section narrower than the tolerance is not outline: two pieces of the model that close
            // are one piece to any nozzle (a V groove cut just above its tip: the Benchy at 0.08 mm layers, z 14.920, is
            // two pieces 3 um apart there, which a slicer rightly prints as one, with no wall along the gap; NarrowGapCheckTest).
            // So the outline judged is the cross-section's, closed by half the tolerance (gaps under [tol] filled; rule:
            // plymeld's choice, on the check's own tolerance, not the planner's setting). The closing only removes
            // outline and moves the rest by less than its radius, so it is made only on a layer with an uncovered point.
            val uncov = uncoveredOf(loops).let { raw ->
                if (raw.pts.isEmpty()) raw else {
                    val r = Math.round(tol * 500)
                    uncoveredOf(plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(loops, r, plymeld.geom.Offset.Join.ROUND), -r, plymeld.geom.Offset.Join.ROUND))
                }
            }
            uncPts += uncov.pts
            val uncovered = uncov.total; val longest = uncov.longest; val ux = uncov.x; val uy = uncov.y
            val badOutline = longest > 2.0
            if (badOutline) out += "layer at z ${f(layer.z, 3)}: the model's outline has no wall along ${f(uncovered, 1)} mm, ${f(longest, 1)} mm in one stretch (ending near ${f(ux, 2)}, ${f(uy, 2)})"
            if (imagesDir != null && (badOutside || badOutline)) {
                val (cx, cy) = if (badOutline) ux to uy else wx to wy
                ModelCheckPlot.write(imagesDir.resolve("z%07.3f.png".format(Locale.ROOT, layer.z)), "layer at z ${f(layer.z, 3)}: ${out.last().substringAfter(": ")}",
                    loops, model.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1, it.w, if (it.wall) 1.0 else 0.0) }, outPts, uncPts, cx, cy)
            }
        }
        return out
    }

    internal class LayerLines(val z: Double, val h: Double, val segs: List<Seg>)

    /**
     * The model cut at a g-code's layers (mid heights, one pass of the mesh slicer for all of them), each
     * layer's outer surface the union of its parts (parts may overlap: parity across them would read an
     * overlap as outside, and their inner boundaries are not surfaces to wall). Shared by ModelCheck and
     * GcodeCheck's travel rule ([at]: is a point on the model's outer surface, within [withinMm] of the
     * outline, so a part's "outer wall" inside another part, a hidden surface, is not taken for a visible one).
     */
    class Surface(parts: List<TriangleSoup>, zBaseMm: Double, text: String, private val withinMm: Double = 0.6,
                  /**
                   * Z anti-aliasing (plan.faces.sloped_top.technique = "z_antialiasing"): the tool's thinnest layer (mm). A layer above the bed then reaches out
                   * to where the model's top is that far above its bottom: its outline, which its walls must cover, is also cut
                   * there ([wallLoops]). Null: the outline is the cut at the layer's middle.
                   */
                  zaaMinMm: Double? = null,
                  /**
                   * The part of each cut this g-code is judged against (curved islands, CurvedCheck.readIslands: the flat layers
                   * without the islands' loops above their starts, an island's layers with its loops only): given the cut's height
                   * in the parts' frame and its loops, the loops kept. Null: all of them.
                   */
                  private val clip: ((Double, List<Polygon>) -> List<Polygon>)? = null) {
        internal val layerLines: List<LayerLines> = layers(text)
        private val cutZ = DoubleArray(layerLines.size) { k -> layerLines[k].z - layerLines[k].h / 2 + zBaseMm }
        private val sections = if (layerLines.isEmpty()) emptyList() else
            parts.map { p -> Slicer.slice(p, cutZ).let { ls -> if (clip == null) ls else ls.mapIndexed { k, l -> plymeld.mesh.Layer(l.z, clip.invoke(cutZ[k], l.loops), l.openChains) } } }
        // each layer's union of the parts, all layers at once in parallel (the union is the slow part)
        private val unions: List<List<Polygon>> by lazy {
            plymeld.core.Parallel.map(layerLines.size) { k -> sections.flatMap { it[k].loops }.let { if (it.size > 1) plymeld.geom.Booleans.unionPositive(it) else it }
                .let { if (clip == null) it else clip.invoke(cutZ[k], it) } }
        }
        // Z anti-aliasing: the layers above the bed whose cut at the thinnest layer above their bottom is below their middle
        private val zaaLayers: IntArray = if (zaaMinMm == null) IntArray(0) else
            layerLines.indices.filter { k -> val l = layerLines[k]; l.z - l.h > 1e-6 && l.z - l.h + zaaMinMm < l.z - l.h / 2 - 1e-9 }.toIntArray()
        private val zaaSections = if (zaaLayers.isEmpty()) emptyList() else
            parts.map { p -> Slicer.slice(p, DoubleArray(zaaLayers.size) { j -> layerLines[zaaLayers[j]].let { it.z - it.h + zaaMinMm!! } + zBaseMm }) }
        private val wallUnions: List<List<Polygon>> by lazy {
            val at = HashMap<Int, Int>(); for ((j, k) in zaaLayers.withIndex()) at[k] = j
            plymeld.core.Parallel.map(layerLines.size) { k ->
                val j = at[k] ?: return@map unions[k]
                val extra = zaaSections.flatMap { it[j].loops }
                if (extra.isEmpty()) unions[k] else plymeld.geom.Booleans.unionPositive(sections.flatMap { it[k].loops } + extra).let { if (clip == null) it else clip.invoke(cutZ[k], it) }
            }
        }
        /** The outline a layer's walls must cover: its cut at the middle, and with Z anti-aliasing where it reaches out over a slope. */
        internal fun wallLoops(k: Int): List<Polygon> = wallUnions[k]
        private val grids = arrayOfNulls<Grid>(layerLines.size)
        private val byZ = layerLines.withIndex().associate { (k, l) -> Math.round(l.z * 1000) to k }
        internal fun loops(k: Int): List<Polygon> = unions[k]
        internal fun grid(k: Int): Grid = grids[k] ?: gridOf(loops(k)).also { grids[k] = it }
        // each part's own cross-section (the tool rule), made when first asked for; layers are judged in parallel, one per thread
        private val partGrids = Array(sections.size) { arrayOfNulls<Grid>(layerLines.size) }
        internal fun partGrid(p: Int, k: Int): Grid = partGrids[p][k] ?: gridOf(sections[p][k].loops).also { partGrids[p][k] = it }
        private fun gridOf(loops: List<Polygon>) = Grid(loops.flatMap { p -> (0 until p.size).map { i -> doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, p.x((i + 1) % p.size) / 1000.0, p.y((i + 1) % p.size) / 1000.0) } })
        /** A layer this g-code does not have counts every point as on the surface (the stricter reading). */
        fun at(x: Double, y: Double, layerZ: Double, h: Double): Boolean {
            val k = byZ[Math.round(layerZ * 1000)] ?: return true
            return grid(k).nearest(x, y, withinMm) <= withinMm
        }
        /** Whether this g-code has a layer at [layerZ]. */
        fun has(layerZ: Double): Boolean = byZ.containsKey(Math.round(layerZ * 1000))
    }

    /**
     * The outer-surface test over several surfaces, each its own g-code and parts (a plate's objects printed one at a time,
     * several lifted bridges' frames): at a layer height some of them print, on the surface of any of those; where none
     * prints, every point is (the stricter reading, as [Surface.at]). One surface of every part over the whole file would
     * cut each layer through all of them, and a layer of one frame through another's parts there.
     */
    class Surfaces(private val list: List<Surface>) {
        fun at(x: Double, y: Double, layerZ: Double, h: Double): Boolean =
            list.filter { it.has(layerZ) }.let { s -> s.isEmpty() || s.any { it.at(x, y, layerZ, h) } }
    }

    internal fun layers(text: String): List<LayerLines> {
        val out = ArrayList<LayerLines>()
        var segs = ArrayList<Seg>(); var z = Double.NaN; var h = 0.2; var started = false
        // the lines laid in this layer's block for the layer above (ABOVE): carried into the next layer's
        var above = ArrayList<Seg>()
        var x = Double.NaN; var y = Double.NaN; var zc = Double.NaN; var w = 0.4; var feature = ""; var tool = 0
        fun flush() { if (started && !z.isNaN()) out += LayerLines(z, h, segs); segs = above; above = ArrayList() }
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { flush(); started = true; continue }
            when {
                l.startsWith(";Z:") -> z = l.removePrefix(";Z:").trim().toDouble()
                l.startsWith("; Z_HEIGHT:") -> z = l.removePrefix("; Z_HEIGHT:").trim().toDouble()
                l.startsWith(";HEIGHT:") -> h = l.removePrefix(";HEIGHT:").trim().toDouble()
                l.startsWith("; LAYER_HEIGHT:") -> h = l.removePrefix("; LAYER_HEIGHT:").trim().toDouble()
                l.startsWith(";WIDTH:") -> w = l.removePrefix(";WIDTH:").trim().toDouble()
                l.startsWith("; LINE_WIDTH:") -> w = l.removePrefix("; LINE_WIDTH:").trim().toDouble()
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:").trim()
                l.startsWith("; FEATURE:") -> feature = l.removePrefix("; FEATURE:").trim()
            }
            val c = l.substringBefore(';').trim()
            if (TOOL.matches(c)) { tool = c.substring(1).toInt(); continue }
            val ws = c.split(Regex("\\s+"))
            val arc = ws[0] == "G2" || ws[0] == "G3"
            if (!(ws[0] == "G1" || ws[0] == "G0" || arc)) continue
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: zc; val e = num('E') ?: 0.0
            if (started && e > 0 && !x.isNaN() && (nx != x || ny != y || arc) && feature in MODEL) {
                if (arc) arcChords(x, y, nx, ny, x + (num('I') ?: 0.0), y + (num('J') ?: 0.0), ws[0] == "G2") { ax, ay, bx, by -> segs += Seg(ax, ay, bx, by, w, feature in WALLS, tool, nz, nz) }
                else (if (feature == ABOVE) above else segs) += Seg(x, y, nx, ny, w, feature in WALLS, tool, zc, nz)
            }
            x = nx; y = ny; zc = nz
        }
        flush()
        return out
    }

    /**
     * An arc move (G2 clockwise, G3 counter-clockwise) from (x0, y0) to (x1, y1) about (cx, cy) as chords no longer than
     * [ARC_CHORD_MM], each passed to [seg]; an arc ending where it starts is a full circle.
     */
    fun arcChords(x0: Double, y0: Double, x1: Double, y1: Double, cx: Double, cy: Double, cw: Boolean, seg: (Double, Double, Double, Double) -> Unit) {
        val r = Math.hypot(x0 - cx, y0 - cy)
        val a0 = Math.atan2(y0 - cy, x0 - cx); val a1 = Math.atan2(y1 - cy, x1 - cx)
        var sweep = if (cw) a0 - a1 else a1 - a0          // the angle traveled, positive
        while (sweep <= 1e-12) sweep += 2 * Math.PI
        val n = maxOf(1, Math.ceil(sweep * r / ARC_CHORD_MM).toInt())
        var px = x0; var py = y0
        for (k in 1..n) {
            val a = a0 + (if (cw) -sweep else sweep) * k / n
            val qx = if (k == n) x1 else cx + r * Math.cos(a); val qy = if (k == n) y1 else cy + r * Math.sin(a)
            seg(px, py, qx, qy); px = qx; py = qy
        }
    }
    /**
     * An arc is judged as chords of at most this length (mm): the outside rule samples lines every 0.5 mm, so chords this
     * short add no coarser step. plymeld's choice.
     */
    private const val ARC_CHORD_MM = 0.5

    /**
     * Segments (x0, y0, x1, y1, mm) bucketed on a 1 mm grid: the nearest one to a point within a radius,
     * and inside / outside of closed loops by crossings of a ray along +x (rows by y).
     */
    internal class Grid(private val segs: List<DoubleArray>) {
        private val cell = 1.0
        private val cells = HashMap<Long, MutableList<Int>>()
        private val rows = HashMap<Int, MutableList<Int>>()
        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        init {
            for ((i, s) in segs.withIndex()) {
                val x0 = Math.floor(minOf(s[0], s[2]) / cell).toInt(); val x1 = Math.floor(maxOf(s[0], s[2]) / cell).toInt()
                val y0 = Math.floor(minOf(s[1], s[3]) / cell).toInt(); val y1 = Math.floor(maxOf(s[1], s[3]) / cell).toInt()
                for (cx in x0..x1) for (cy in y0..y1) cells.getOrPut(key(cx, cy)) { ArrayList() } += i
                for (cy in y0..y1) rows.getOrPut(cy) { ArrayList() } += i
            }
        }
        fun nearest(px: Double, py: Double, r: Double): Double {
            var best = Double.MAX_VALUE
            val rc = Math.ceil(r / cell).toInt(); val cx = Math.floor(px / cell).toInt(); val cy = Math.floor(py / cell).toInt()
            for (gx in cx - rc..cx + rc) for (gy in cy - rc..cy + rc) cells[key(gx, gy)]?.forEach { i -> val s = segs[i]; best = minOf(best, pointSeg(px, py, s[0], s[1], s[2], s[3])) }
            return best
        }
        fun inside(px: Double, py: Double): Boolean {
            var c = false
            rows[Math.floor(py / cell).toInt()]?.forEach { i ->
                val s = segs[i]
                if ((s[1] > py) != (s[3] > py) && px < (s[2] - s[0]) * (py - s[1]) / (s[3] - s[1]) + s[0]) c = !c
            }
            return c
        }
    }

    private fun pointSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun f(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)
}
