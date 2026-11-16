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
 *  - bare outline: stretches of the outline longer than a line where no line of the model (of any kind) lies where the
 *    outer bead belongs (half a line in, within the tolerance), where a line could lie (a disc as wide as the walls'
 *    narrowest place for a line, plan.layer.walls.feature.min, fits touching the outline there): a hole in a band a
 *    line or two wide, whose walls on either side the uncovered rule counts as near. Within a line of a convex corner, and
 *    on a layer laid with wave beads, not judged;
 *  - hole: a spot of the cross-section no line covers, closed in by walls alone (not the outline's, not an infill's gap),
 *    wider than that narrowest place for a line and narrower than the walls hand to infill ([HoleCheck]): a hole inside the
 *    walls. On a layer laid with wave beads, not judged;
 *  - Z anti-aliasing (a [ZaaRule]): a layer's outline also takes in its cut at the thinnest lowered line (ZaaRule.minMm) above its bottom
 *    (the layer reaches out over a slope to there), and a point of a line below its layer, on the model's top within the
 *    layer, is not outside;
 *  - over air (a layer with wave beads): each piece of the layer's cross-section where the model has none under it (its
 *    cut at the middle of the layer below) that has a wave bead in it, laid all over: no point of it, where a line's center could go, more than a line's width
 *    from every line of the layer (a gap wider than a line between beads). Refused over [AIR_MM2] unlaid; wave beads are
 *    laid a spacing apart, and what no front reaches is bridged, so the planner leaves none;
 *  - tool (when the config's part tools are given): the model's lines printed by the tool (the T command in force) the
 *    config's loadout assigns the part whose own cross-section holds them; and on the object's outer surface, no line's
 *    bead covering a stretch of the outline that belongs to another tool's part alone (a filament showing outside its own
 *    part).
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

    /**
     * A printed segment: [w], its path's width as the file states it (`;WIDTH`, a variable-width line's widest); [bead], this
     * segment's own width from its plastic ([layers]: its share of the path's widest extrusion per mm).
     */
    internal class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double, val wall: Boolean, val tool: Int = 0,
                       val z0: Double = Double.NaN, val z1: Double = Double.NaN, val wave: Boolean = false,
                       /** A line of an infill or a surface (not a wall: [INFILL]). */
                       val infill: Boolean = false) {
        var bead: Double = w
    }
    /** The features that fill an area (infill, surfaces, bridges, ironing), as against the walls. */
    private val INFILL = setOf("Sparse infill", "Internal solid infill", "Solid infill", "Top surface", "Bottom surface", "Bridge", "Internal Bridge",
        "Gap infill", "Ironing")
    /** A wave bead (laid in its own layer or carried into the one above): a layer with one lays the area over air ([airProblems]). */
    private val WAVES = setOf("Wave overhang", ABOVE)

    /**
     * [parts]: the placed meshes in bed coordinates; [zBaseMm]: the height of the bed in their frame
     * (g-code Z = mesh Z - zBase). [tolUm]: extra allowance beyond half a line (fuzzy skin, rounding); gaps in the
     * cross-section narrower than it are not outline.
     * [imagesDir]: when given, a picture of each layer with a problem (ModelCheckPlot). [surface]: the
     * model cut at this g-code's layers, when already made (shared with GcodeCheck's travel rule).
     * [partTools]: the tool (0-based, T0 = the tool of index 1) the config's loadout assigns each of [parts], in their
     * order; null: the tool is not judged. [handOverMm]: how thin an area of one filament the config hands to the
     * neighboring filament (plan.layer.sliver.width, when set over the walls' own thinnest): a line of one tool may lie that
     * much further from its own tool's part. [surfaceHandOverMm]: the same allowance on the object's outer surface, where
     * the config hands a sliver lying along it to the neighbor too (plan.layer.sliver.at_surface = neighbor: the width it
     * hands over, SliceSettings.sliverHandOverUm); 0 = none there, the tolerance alone. [featureMinMm]: the narrowest place the walls surely lay a line in
     * (plan.layer.walls.feature.min and the slicing tolerance a side, as `slice` passes it); 0 = unknown, half the layer's
     * walls' usual width stands in. The bare-outline rule judges only outline a line of that width could lie against.
     * [firstLayerInsetMm]: how far the first layer's outline is drawn in (hardware.plate.first_layer.squish): there the
     * bare-outline rule judges the outline drawn in by it, which is where the walls lie (a star's thin tips, drawn in,
     * recede by a millimetre and more).
     * [fuzzMm]: the fuzzy skin's thickness where it is on (plan.technique.fuzzy_skin.thickness), 0 = off: the hole rule reads
     * each wall line as covering that much further either side (its points move in and out by up to it).
     * [infillMinMm]: the narrowest area the walls hand to infill (two inner wall lines, plan.layer.walls.inner.line.width):
     * the hole rule leaves an uncovered piece that wide to the infill region (no line of a sparse infill need cross it).
     */
    fun check(text: String, parts: List<TriangleSoup>, zBaseMm: Double, tolUm: Long = 100, maxProblems: Int = 30,
              imagesDir: java.nio.file.Path? = null, surface: Surface? = null, partTools: List<Int>? = null, zaa: ZaaRule? = null,
              progress: plymeld.program.Progress = plymeld.program.Progress.NONE, handOverMm: Double = 0.0, featureMinMm: Double = 0.0,
              firstLayerInsetMm: Double = 0.0, surfaceHandOverMm: Double = 0.0, fuzzMm: Double = 0.0, infillMinMm: Double = Double.MAX_VALUE): List<String> {
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
        // every layer on its own, in parallel; the problems in layer order (the model's cuts made first, here: Parallel)
        sf.ready()
        return plymeld.core.Parallel.map(layers.size, progress.Counter("model check", layers.size)) { k ->
            layerProblems(sf, k, tol, imagesDir, zaa, featureMinMm, if (k == 0) firstLayerInsetMm else 0.0, fuzzMm, infillMinMm) + airProblems(sf, k, tol, imagesDir) +
                toolProblems(sf, k, tol + handOverMm, partTools) + surfaceToolProblems(sf, k, tol + surfaceHandOverMm, partTools) }.flatten().take(maxProblems)
    }

    /** The unlaid area over air a waved layer may leave (mm2): a few sampling cells, so a corner's rounding is not one. */
    private const val AIR_MM2 = 0.25

    /**
     * The over-air rule: on a layer with wave beads, the area over air with no line within a line's width ([AIR_MM2]), in
     * the pieces of it the beads were laid for (a wave bead's middle in them).
     * Over air: the layer's cut at its middle less the model's cut at the middle of the layer below (one layer height
     * down); the first layer stands on the bed. Points are sampled a quarter line apart where a line's center could go
     * (half a line inside the cross-section); a point is unlaid when no line of the layer, of any feature, has its center
     * within a line's width (the beads' widest) and the tolerance of it.
     */
    private fun airProblems(sf: Surface, k: Int, tol: Double, imagesDir: java.nio.file.Path?): List<String> {
        val layer = sf.layerLines[k]
        val waves = layer.segs.filter { it.wave }
        if (waves.isEmpty() || layer.z - layer.h < 1e-6) return emptyList()
        // the pieces of the area over air the beads were laid for: those with a wave bead in them (a narrow hole closing
        // within the layer, walled as still open, is over air by the cut at the middle but no wave's)
        fun gridOf(ps: List<Polygon>) = Grid(ps.flatMap { p -> (0 until p.size).map { i -> doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, p.x((i + 1) % p.size) / 1000.0, p.y((i + 1) % p.size) / 1000.0) } })
        val air = plymeld.geom.Polygons.group(plymeld.geom.Booleans.difference(sf.loops(k), sf.below(k))).map { (o, hs) -> listOf(o) + hs }
            .filter { piece -> val g = gridOf(piece); waves.any { g.inside((it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2) } }.flatten()
        if (air.isEmpty()) return emptyList()
        val w = waves.maxOf { it.w }
        val airGrid = gridOf(air)
        val edges = sf.grid(k)
        val lines = Grid(layer.segs.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) })
        val step = w / 4
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (p in air) for (i in 0 until p.size) { val x = p.x(i) / 1000.0; val y = p.y(i) / 1000.0; x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
        var unlaid = 0.0; var far = 0.0; var fx = 0.0; var fy = 0.0
        val pts = ArrayList<DoubleArray>()
        var y = y0 + step / 2
        while (y < y1) {
            var x = x0 + step / 2
            while (x < x1) {
                if (airGrid.inside(x, y) && edges.nearest(x, y, w) >= w / 2) {
                    if (lines.nearest(x, y, w + tol) > w + tol) {
                        unlaid += step * step; pts += doubleArrayOf(x, y)
                        val d = lines.nearest(x, y, 5.0)
                        if (d > far) { far = d; fx = x; fy = y }
                    }
                }
                x += step
            }
            y += step
        }
        if (unlaid <= AIR_MM2) return emptyList()
        val problem = "layer at z ${f(layer.z, 3)}: ${f(unlaid, 1)} mm2 of the area over air laid by no line (a gap wider than a line, " +
            "up to ${if (far >= 5.0) "5+" else f(far - w / 2, 2)} mm from the nearest line's edge, at ${f(fx, 2)}, ${f(fy, 2)})"
        // the layer's picture, unless the layer's other rules drew one: the area over air as the outline, the unlaid points marked
        val file = imagesDir?.resolve("z%07.3f.png".format(Locale.ROOT, layer.z))
        if (file != null && !java.nio.file.Files.exists(file))
            ModelCheckPlot.write(file, problem, sf.loops(k) + air, layer.segs.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1, it.w, if (it.wall) 1.0 else 0.0) }, emptyList(), pts, fx, fy, "over air, no line within a line")
        return listOf(problem)
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

    /**
     * The tool rule on the object's outer surface: no line's bead (half its width round its middle, the width from its
     * plastic, Seg.bead: a variable-width line's file width is its widest) covers a stretch of the
     * layer's outline (its cut at the middle, the union of the parts) that belongs to the parts of other tools alone (no part
     * on the line's tool holds it within [allowMm]). There the line's filament shows on the surface outside its own part: a
     * sliver of one part lying along the surface laid by its neighbor. The tool rule above reads only where a line's middle
     * lies, so a bead reaching past its own part over a thin piece of another's passed it. A bead covers an outline point
     * within half its width and [SURFACE_EDGE_MM] of its middle: an outer wall laid round the object's outline has its
     * edge right on it. The outline is sampled every [SURFACE_STEP_MM]; a stretch of it covered by one tool's beads (gaps of
     * up to [SURFACE_GAP_MM] bridged) longer than [SURFACE_RUN_MM] is refused: shorter ones are where a feature narrower
     * than a line meets another part (a tip a wall passes over, a line's round end in the narrowest place a line is laid
     * in), which the walls cannot follow. The section's cut (an open section's sides) is not surface.
     */
    private fun surfaceToolProblems(sf: Surface, k: Int, allowMm: Double, partTools: List<Int>?): List<String> {
        if (partTools == null) return emptyList()
        val tools = partTools.distinct().sorted()
        if (tools.size < 2) return emptyList()
        val layer = sf.layerLines[k]
        if (layer.segs.isEmpty()) return emptyList()
        val all = (1 shl tools.size) - 1
        // the outline's samples, loop by loop in order; those some tool's parts do not hold bucketed by cell, each with the
        // tools that do hold it
        class Sample(val x: Double, val y: Double, val held: Int) { var wrong = 0; var by: Seg? = null }
        val loops = ArrayList<List<Sample?>>()
        val cells = HashMap<Long, MutableList<Sample>>()
        fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xffffffffL)
        for (p in sf.loops(k)) {
            val seq = ArrayList<Sample?>()
            for (i in 0 until p.size) {
                val ax = p.x(i) / 1000.0; val ay = p.y(i) / 1000.0
                val bx = p.x((i + 1) % p.size) / 1000.0; val by = p.y((i + 1) % p.size) / 1000.0
                val n = maxOf(1, Math.ceil(Math.hypot(bx - ax, by - ay) / SURFACE_STEP_MM).toInt())
                for (j in 0 until n) {
                    val x = ax + (bx - ax) * j / n; val y = ay + (by - ay) * j / n
                    if (sf.onCut(x, y, allowMm)) { seq += null; continue }
                    var held = 0
                    for (q in partTools.indices) {
                        val g = sf.partGrid(q, k)
                        if (g.inside(x, y) || g.nearest(x, y, allowMm) <= allowMm) held = held or (1 shl tools.indexOf(partTools[q]))
                    }
                    if (held == all) { seq += null; continue }
                    val sm = Sample(x, y, held)
                    seq += sm
                    cells.getOrPut(key(Math.floor(x / SURFACE_CELL_MM).toInt(), Math.floor(y / SURFACE_CELL_MM).toInt())) { ArrayList() } += sm
                }
            }
            loops += seq
        }
        if (cells.isEmpty()) return emptyList()
        for (s in layer.segs) {
            val bit = tools.indexOf(s.tool).takeIf { it >= 0 }?.let { 1 shl it } ?: continue   // a tool of no part: the tool rule's
            val r = s.bead / 2 + SURFACE_EDGE_MM
            val cx0 = Math.floor((minOf(s.x0, s.x1) - r) / SURFACE_CELL_MM).toInt(); val cx1 = Math.floor((maxOf(s.x0, s.x1) + r) / SURFACE_CELL_MM).toInt()
            val cy0 = Math.floor((minOf(s.y0, s.y1) - r) / SURFACE_CELL_MM).toInt(); val cy1 = Math.floor((maxOf(s.y0, s.y1) + r) / SURFACE_CELL_MM).toInt()
            for (cx in cx0..cx1) for (cy in cy0..cy1) cells[key(cx, cy)]?.forEach { p ->
                if (p.held and bit == 0 && p.wrong and bit == 0 && pointSeg(p.x, p.y, s.x0, s.y0, s.x1, s.y1) <= r) { p.wrong = p.wrong or bit; if (p.by == null) p.by = s }
            }
        }
        // the longest stretch one tool covers, round each loop (a loop all covered: its whole length)
        val gap = Math.round(SURFACE_GAP_MM / SURFACE_STEP_MM).toInt()
        var longest = 0; var at: Sample? = null
        for (seq in loops) for (b in tools.indices) {
            val bit = 1 shl b
            val n = seq.size
            if (seq.none { it != null && it.wrong and bit != 0 }) continue
            if (seq.all { it != null && it.wrong and bit != 0 }) { if (n > longest) { longest = n; at = seq[0] }; continue }
            // start after an uncovered sample, so a stretch over the loop's start is read whole
            val start = seq.indices.first { seq[it]?.let { s -> s.wrong and bit != 0 } != true }
            var run = 0; var miss = 0; var first: Sample? = null
            for (t in 1..n) {
                val sm = seq[(start + t) % n]
                if (sm != null && sm.wrong and bit != 0) { if (run == 0) first = sm; run += 1 + miss; miss = 0; if (run > longest) { longest = run; at = first } }
                else if (run > 0 && ++miss > gap) { run = 0; miss = 0 }
            }
        }
        val mm = longest * SURFACE_STEP_MM
        if (mm <= SURFACE_RUN_MM) return emptyList()
        val p = at!!; val s = p.by!!
        val owners = tools.filterIndexed { i, _ -> p.held and (1 shl i) != 0 }
        return listOf("layer at z ${f(layer.z, 3)}: ${f(mm, 1)} mm of the object's surface laid by the wrong tool: T${s.tool} (tool ${s.tool + 1})'s bead " +
            "covers it from (${f(p.x, 2)}, ${f(p.y, 2)}), outside that tool's parts, where the surface is " +
            (if (owners.isEmpty()) "no part's" else "the part of ${owners.joinToString(" or ") { "T$it (tool ${it + 1})" }}"))
    }
    /** How far apart the surface tool rule samples the outline (mm): well under the thinnest line. plymeld's choice. */
    private const val SURFACE_STEP_MM = 0.05
    /** The surface tool rule's buckets (mm). */
    private const val SURFACE_CELL_MM = 0.5
    /** How far past half its width a bead still lays the outline (mm): the file's and the slice's rounding. plymeld's choice. */
    private const val SURFACE_EDGE_MM = 0.02
    /** Uncovered samples a covered stretch of the outline runs on over (mm). plymeld's choice. */
    private const val SURFACE_GAP_MM = 0.1
    /**
     * The longest stretch of another tool's surface one tool's beads may cover (mm): longer than where a feature narrower than
     * a line meets another part (a tip 0.15 mm into a face, a wall passing over it, covers about 0.4 mm of it). plymeld's choice.
     */
    private const val SURFACE_RUN_MM = 1.0

    /** One layer's problems (and its picture, when [imagesDir] is given). */
    private fun layerProblems(sf: Surface, k: Int, tol: Double, imagesDir: java.nio.file.Path?, zaa: ZaaRule?, featureMinMm: Double, insetMm: Double,
                              fuzzMm: Double, infillMinMm: Double): List<String> {
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
                            // a section left open: its cut sides are not walled, by intent
                            val near = wallGrid.nearest(px, py, w + tol) <= w + tol || sf.onCut(px, py, tol)
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
            // Bare outline: the rule above counts a wall within a whole line of the outline, so a hole in a thin band, a line
            // or two wide, whose walls on either side reach in (a ridge 0.78 mm wide with no line along 0.9 mm of it, a
            // loop's turn on one side and the band's end on the other) passed. Here each outline point is probed where its
            // outer bead's middle belongs (half the walls' usual width in from the outline), where the model holds a line
            // there (a disc as wide as the narrowest place the walls lay a line in, [featureMinMm], fits touching the point):
            // bare when no line of the model, of any kind, has its bead within the tolerance of that point. An acute
            // corner's tip and a place narrower than that floor hold no line: no line is laid there, by design. Refused over
            // a stretch longer than the walls' usual width: a seam, a line's round end, a coasted end (its bead reaches the
            // tolerance further), the squish compensation's inset all stay under it. A layer laid with wave beads is left
            // out: the over-air rule judges it (a lifted bridge's second layer laid as a crosshatch of Xs leaves its outline
            // open by design).
            // (on the cross-section closed by half the tolerance, as above, when the raw one has a bare point)
            // The line it probes for is the walls' usual one (their width over most of their length): the widest, a thin area's
            // line declared at its widest (up to two lines), left every band narrower than that unjudged and its holes passed.
            val wb = walls.sortedBy { it.w }.let { ws -> val total = ws.sumOf { Math.hypot(it.x1 - it.x0, it.y1 - it.y0) }; var acc = 0.0
                ws.firstOrNull { acc += Math.hypot(it.x1 - it.x0, it.y1 - it.y0); acc >= total / 2 }?.w ?: w }
            val floor = if (featureMinMm > 0) featureMinMm else wb / 2
            val bare = if (model.any { it.wave }) Bare(0.0, 0.0, 0.0, emptyList()) else bareOf(loops, model, edges, wb, floor, insetMm, tol, sf).let { raw ->
                if (raw.pts.isEmpty()) raw else {
                    val r = Math.round(tol * 500)
                    bareOf(plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(loops, r, plymeld.geom.Offset.Join.ROUND), -r, plymeld.geom.Offset.Join.ROUND), model, edges, wb, floor, insetMm, tol, sf)
                }
            }
            uncPts += bare.pts
            val badBare = !badOutline && bare.longest > wb
            if (badBare) out += "layer at z ${f(layer.z, 3)}: the model's outline has no line beside it along ${f(bare.longest, 2)} mm in one stretch, where the model holds a line (ending near ${f(bare.x, 2)}, ${f(bare.y, 2)})"
            // a hole inside the walls (HoleCheck) wider than the narrowest place a line is laid in, read to a grid cell either
            // side; a layer laid with wave beads is left to the over-air rule, as above
            val hole = if (model.any { it.wave }) null else HoleCheck.widest(loops, model, edges, fuzzMm, infillMinMm)
            val badHole = hole != null && hole.widthMm > floor + 2 * HoleCheck.CELL_MM
            if (badHole) { uncPts += doubleArrayOf(hole.x, hole.y)
                out += "layer at z ${f(layer.z, 3)}: a hole inside the walls ${f(hole.widthMm, 2)} mm wide (${f(hole.areaMm2, 3)} mm2, at ${f(hole.x, 2)}, ${f(hole.y, 2)}), where the model holds a line: no line laid there" }
            if (imagesDir != null && (badOutside || badOutline || badBare || badHole)) {
                val (cx, cy) = if (badOutline) ux to uy else if (badBare) bare.x to bare.y else if (badHole) hole.x to hole.y else wx to wy
                ModelCheckPlot.write(imagesDir.resolve("z%07.3f.png".format(Locale.ROOT, layer.z)), "layer at z ${f(layer.z, 3)}: ${out.last().substringAfter(": ")}",
                    loops, model.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1, it.w, if (it.wall) 1.0 else 0.0) }, outPts, uncPts, cx, cy)
            }
        }
        return out
    }

    /**
     * A convex corner of the outline, for the bare-outline rule: turning toward the model by more than this (deg) within a
     * line either way (a corner rounded over a few vertices is one). A box's corner (90) and a hexagon's (60) are; a curved
     * face's facets (a few degrees each, over a line) are not. plymeld's choice.
     */
    private const val CORNER_DEG = 45.0

    /** The bare-outline rule's reading of one layer ([bareOf]): its longest bare stretch (mm), where it ends, the bare points. */
    private class Bare(val longest: Double, val x: Double, val y: Double, val pts: List<DoubleArray>)

    /**
     * Each outline point of [loops] (the cross-section, outer loops counter-clockwise: the model on their left), every
     * quarter of [w], probed half of [w] in: bare when the model there holds a line (a disc as wide as the narrowest place
     * a line is laid in, [floor], fits in the cross-section touching the outline point within [tol]: an acute corner's
     * tip, a place narrower than [floor], holds none; on the first layer the disc fits the outline drawn in by [inset]),
     * and no line of [model] has its center within half of [w] and [tol] of the probe. Stretches run on round a loop's start.
     */
    private fun bareOf(loops: List<Polygon>, model: List<Seg>, edges: Grid, w: Double, floor: Double, inset: Double, tol: Double, sf: Surface): Bare {
        val lines = Grid(model.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) })
        // where a disc of [floor] can have its center: the cross-section (drawn in by [inset]) shrunk by half of it
        val centers by lazy {
            val c = plymeld.geom.Offset.offset(loops, -Math.round((floor / 2 + inset) * 1000))
            Grid(c.flatMap { p -> (0 until p.size).map { i -> doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, p.x((i + 1) % p.size) / 1000.0, p.y((i + 1) % p.size) / 1000.0) } })
        }
        val step = w / 4; val reach = w / 2 + tol
        var longest = 0.0; var lx = 0.0; var ly = 0.0
        val pts = ArrayList<DoubleArray>()
        val judged = ArrayList<DoubleArray>()
        for (loop in loops) {
            if (Math.abs(loop.signedAreaMm2()) < w * w * 2) continue                // too small to hold a line
            val m = loop.size
            // the side the model is on (+1: left of the loop's way round), read off its longest edge a hair to the left
            val side = run {
                var best = 0; var bl = -1.0
                for (i in 0 until m) { val l = Math.hypot((loop.x((i + 1) % m) - loop.x(i)).toDouble(), (loop.y((i + 1) % m) - loop.y(i)).toDouble()); if (l > bl) { bl = l; best = i } }
                val ax = loop.x(best) / 1000.0; val ay = loop.y(best) / 1000.0; val bx = loop.x((best + 1) % m) / 1000.0; val by = loop.y((best + 1) % m) / 1000.0
                val l = Math.hypot(bx - ax, by - ay); val eps = minOf(0.01, l / 4)
                if (edges.inside((ax + bx) / 2 - (by - ay) / l * eps, (ay + by) / 2 + (bx - ax) / l * eps)) 1.0 else -1.0
            }
            // the loop's convex corners (turning toward the model by more than [CORNER_DEG] within a line), by their place along
            // it: within a line of one, the outline is not judged (a wall rounds or cuts a corner, fuzzy skin's resampling across it)
            val at = DoubleArray(m + 1)
            for (i in 0 until m) at[i + 1] = at[i] + Math.hypot((loop.x((i + 1) % m) - loop.x(i)) / 1000.0, (loop.y((i + 1) % m) - loop.y(i)) / 1000.0)
            val perimeter = at[m]
            // each vertex's turn toward the model (radians; a corner rounded by a few vertices turns over all of them), summed
            // along the loop: the turn within a line either way of a point, by two binary searches
            val turn = DoubleArray(m) { i ->
                val p = (i + m - 1) % m; val q = (i + 1) % m
                val ux = (loop.x(i) - loop.x(p)).toDouble(); val uy = (loop.y(i) - loop.y(p)).toDouble()
                val vx = (loop.x(q) - loop.x(i)).toDouble(); val vy = (loop.y(q) - loop.y(i)).toDouble()
                maxOf(0.0, side * Math.atan2(ux * vy - uy * vx, ux * vx + uy * vy))
            }
            val sum = DoubleArray(m + 1); for (i in 0 until m) sum[i + 1] = sum[i] + turn[i]
            /** The convex turn of the vertices at places [a, b] along the loop (0 <= a <= b <= perimeter). */
            fun turned(a: Double, b: Double): Double {
                fun first(u: Double): Int { var lo = 0; var hi = m; while (lo < hi) { val mid = (lo + hi) / 2; if (at[mid] < u) lo = mid + 1 else hi = mid }; return lo }
                return sum[first(b + 1e-12).coerceAtMost(m)] - sum[first(a).coerceAtMost(m)]
            }
            fun nearCorner(u: Double): Boolean {
                if (2 * w >= perimeter) return true
                val a = u - w; val b = u + w
                val t = if (a < 0) turned(0.0, b) + turned(a + perimeter, perimeter) else if (b > perimeter) turned(a, perimeter) + turned(0.0, b - perimeter) else turned(a, b)
                return t > Math.toRadians(CORNER_DEG)
            }
            // each sample: 1 bare, 0 laid, -1 not judged (beside a corner: neither ends a bare stretch nor adds to it)
            val lens = ArrayList<Double>(); val state = ArrayList<Int>(); val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
            for (i in 0 until m) {
                val ax = loop.x(i) / 1000.0; val ay = loop.y(i) / 1000.0
                val bx = loop.x((i + 1) % m) / 1000.0; val by = loop.y((i + 1) % m) / 1000.0
                val len = Math.hypot(bx - ax, by - ay)
                if (len == 0.0) continue
                val nxl = -(by - ay) / len * side; val nyl = (bx - ax) / len * side
                val n = maxOf(1, Math.ceil(len / step).toInt())
                for (j in 0 until n) {
                    val px = ax + (bx - ax) * (j + 0.5) / n; val py = ay + (by - ay) * (j + 0.5) / n
                    val qx = px + nxl * w / 2; val qy = py + nyl * w / 2
                    val e = !sf.onCut(px, py, tol) && edges.inside(px + nxl * (floor / 2 + inset), py + nyl * (floor / 2 + inset)) && lines.nearest(qx, qy, reach) > reach &&
                        centers.nearest(px, py, floor + inset) <= floor / 2 + inset + tol
                    lens += len / n; xs += px; ys += py
                    state += if (!e) 0 else if (nearCorner(at[i] + len * (j + 0.5) / n)) -1 else 1
                    if (e) pts += doubleArrayOf(px, py)
                    if (state.last() == 1) judged += doubleArrayOf(px, py)
                }
            }
            val k = lens.size
            if (k == 0) continue
            // the longest run of bare points round the loop (from a laid point; none laid: the whole loop's bare points)
            val start = state.indexOfFirst { it == 0 }
            if (start < 0) { val total = (0 until k).sumOf { if (state[it] == 1) lens[it] else 0.0 }; if (total > longest) { longest = total; lx = xs[0]; ly = ys[0] }; continue }
            var run = 0.0
            for (t in 1..k) {
                val i = (start + t) % k
                when (state[i]) {
                    1 -> { run += lens[i]; if (run > longest) { longest = run; lx = xs[i]; ly = ys[i] } }
                    0 -> run = 0.0
                }
            }
        }
        // One hole is bare on both faces of a band: its bare points on either face, a band's width apart, are one stretch
        // (points within two lines of each other joined, unless a line lies between them), as long as the farthest two of
        // them are apart. Counted face by face, a hole in a ridge 0.75 mm wide passed: a stub of line across it broke each
        // face's run in two. Joined across a line between them, the two corners of an arch's mouth either side of the line
        // filling it read as one bare stretch 0.86 mm long.
        if (judged.size > 1) {
            val link = 2 * w
            val parent = IntArray(judged.size) { it }
            fun root(i: Int): Int { var r = i; while (parent[r] != r) r = parent[r]; var j = i; while (parent[j] != r) { val n = parent[j]; parent[j] = r; j = n }; return r }
            val cells = HashMap<Long, MutableList<Int>>()
            fun key(cx: Long, cy: Long) = (cx shl 32) xor (cy and 0xffffffffL)
            for ((i, p) in judged.withIndex()) cells.getOrPut(key(Math.floor(p[0] / link).toLong(), Math.floor(p[1] / link).toLong())) { ArrayList() } += i
            for ((i, p) in judged.withIndex()) {
                val cx = Math.floor(p[0] / link).toLong(); val cy = Math.floor(p[1] / link).toLong()
                for (gx in cx - 1..cx + 1) for (gy in cy - 1..cy + 1) for (j in cells[key(gx, gy)] ?: continue)
                    if (j > i && Math.hypot(judged[j][0] - p[0], judged[j][1] - p[1]) <= link &&
                        // not across a line lying between them: two small bare places either side of a line are two
                        lines.nearest((judged[j][0] + p[0]) / 2, (judged[j][1] + p[1]) / 2, reach) > reach) parent[root(j)] = root(i)
            }
            for (group in judged.indices.groupBy { root(it) }.values) {
                if (group.size < 2) continue
                // the farthest two: exactly for a small group, else across its box
                val d = if (group.size <= 400) group.maxOf { a -> group.maxOf { b -> Math.hypot(judged[a][0] - judged[b][0], judged[a][1] - judged[b][1]) } }
                    else Math.hypot(group.maxOf { judged[it][0] } - group.minOf { judged[it][0] }, group.maxOf { judged[it][1] } - group.minOf { judged[it][1] })
                if (d > longest) { longest = d; lx = judged[group[0]][0]; ly = judged[group[0]][1] }
            }
        }
        return Bare(longest, lx, ly, pts)
    }

    internal class LayerLines(val z: Double, val h: Double, val segs: List<Seg>)

    /**
     * The model cut at a g-code's layers (mid heights, one pass of the mesh slicer for all of them), each
     * layer's outer surface the union of its parts (parts may overlap: parity across them would read an
     * overlap as outside, and their inner boundaries are not surfaces to wall). Shared by ModelCheck and
     * GcodeCheck's travel rule ([at]: is a point on the model's outer surface, within [withinMm] of the
     * outline, so a part's "outer wall" inside another part, a hidden surface, is not taken for a visible one).
     */
    class Surface(private val parts: List<TriangleSoup>, private val zBaseMm: Double, text: String, private val withinMm: Double = 0.6,
                  /**
                   * Z anti-aliasing (plan.faces.sloped_top.technique = "z_antialiasing"): the thinnest lowered line (ZaaRule.minMm). A layer above the bed then reaches out
                   * to where the model's top is that far above its bottom: its outline, which its walls must cover, is also cut
                   * there ([wallLoops]). Null: the outline is the cut at the layer's middle.
                   */
                  zaaMinMm: Double? = null,
                  /**
                   * The part of each cut this g-code is judged against (curved islands, CurvedCheck.readIslands: the flat layers
                   * without the islands' loops above their starts, an island's layers with its loops only): given the cut's height
                   * in the parts' frame and its loops, the loops kept. Null: all of them.
                   */
                  private val clip: ((Double, List<Polygon>) -> List<Polygon>)? = null,
                  /**
                   * A section left open (model.object.<key>.section, faces = "open"): the box the object's lines are laid in (x0,
                   * x1, y0, y1 and its top's z, mm, in the g-code's frame). Each cut is judged inside it only (nothing above
                   * its top), and its outline along the box's sides is the cut, where no wall is asked for ([onCut]).
                   */
                  val cut: DoubleArray? = null) {
        /** Whether an outline point lies on the open section's cut (within [tolMm] of a side of the box). */
        fun onCut(x: Double, y: Double, tolMm: Double): Boolean = cut != null &&
            (Math.abs(x - cut[0]) <= tolMm || Math.abs(x - cut[1]) <= tolMm || Math.abs(y - cut[2]) <= tolMm || Math.abs(y - cut[3]) <= tolMm)
        private val cutRect: List<Polygon>? = cut?.let { c -> listOf(Polygon(longArrayOf(Math.round(c[0] * 1000), Math.round(c[2] * 1000), Math.round(c[1] * 1000), Math.round(c[2] * 1000),
            Math.round(c[1] * 1000), Math.round(c[3] * 1000), Math.round(c[0] * 1000), Math.round(c[3] * 1000)))) }
        /** [loops], the model cut at [z] mm, inside the open section's box (as they are with none; none above its top). */
        private fun inCut(z: Double, loops: List<Polygon>): List<Polygon> = if (cutRect == null || loops.isEmpty()) loops
            else if (z > cut!![4] + 1e-6) emptyList() else plymeld.geom.Booleans.intersection(loops, cutRect)
        internal val layerLines: List<LayerLines> = layers(text)
        private val cutZ = DoubleArray(layerLines.size) { k -> layerLines[k].z - layerLines[k].h / 2 + zBaseMm }
        private val sections = if (layerLines.isEmpty()) emptyList() else
            parts.map { p -> Slicer.slice(p, cutZ).let { ls -> if (clip == null && cutRect == null) ls else ls.mapIndexed { k, l ->
                plymeld.mesh.Layer(l.z, inCut(cutZ[k], if (clip == null) l.loops else clip.invoke(cutZ[k], l.loops)), l.openChains) } } }
        // each layer's union of the parts, all layers at once in parallel (the union is the slow part). Made by [ready] on the
        // calling thread before the per-layer work reads it, and never under a lock (PUBLICATION): a lazy's lock taken by a
        // pool task whose initializer then forked the layers and joined them left the pool's tasks waiting on each other
        private val unions: List<List<Polygon>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            plymeld.core.Parallel.map(layerLines.size) { k -> sections.flatMap { it[k].loops }.let { if (it.size > 1) plymeld.geom.Booleans.unionPositive(it) else it }
                .let { if (clip == null) it else clip.invoke(cutZ[k], it) } }
        }
        // Z anti-aliasing: the layers above the bed whose cut at the thinnest layer above their bottom is below their middle
        private val zaaLayers: IntArray = if (zaaMinMm == null) IntArray(0) else
            layerLines.indices.filter { k -> val l = layerLines[k]; l.z - l.h > 1e-6 && l.z - l.h + zaaMinMm < l.z - l.h / 2 - 1e-9 }.toIntArray()
        private val zaaSections = if (zaaLayers.isEmpty()) emptyList() else
            parts.map { p -> Slicer.slice(p, DoubleArray(zaaLayers.size) { j -> layerLines[zaaLayers[j]].let { it.z - it.h + zaaMinMm!! } + zBaseMm }) }
        // (as [unions]: made by [ready], never under a lock; [unions] made first, on this thread, not by each of its tasks)
        private val wallUnions: List<List<Polygon>> by lazy(LazyThreadSafetyMode.PUBLICATION) {
            val at = HashMap<Int, Int>(); for ((j, k) in zaaLayers.withIndex()) at[k] = j
            val u = unions
            plymeld.core.Parallel.map(layerLines.size) { k ->
                val j = at[k] ?: return@map u[k]
                val extra = zaaSections.flatMap { it[j].loops }
                if (extra.isEmpty()) u[k] else plymeld.geom.Booleans.unionPositive(sections.flatMap { it[k].loops } + extra).let { if (clip == null) it else clip.invoke(cutZ[k], it) }
            }
        }
        /** The layers' unions and wall outlines made now, on the calling thread: before per-layer parallel work reads them. */
        internal fun ready() { wallUnions }
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
        private val belows = arrayOfNulls<List<Polygon>>(layerLines.size)
        /** The model's cross-section under layer [k]: its cut one layer height below the layer's middle (the over-air rule), made when first asked for. */
        internal fun below(k: Int): List<Polygon> = belows[k] ?: run {
            val z = cutZ[k] - layerLines[k].h
            val loops = parts.flatMap { p -> Slicer.slice(p, doubleArrayOf(z))[0].loops }.let { if (it.size > 1) plymeld.geom.Booleans.unionPositive(it) else it }
            (if (clip == null) loops else clip.invoke(z, loops)).also { belows[k] = it }
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
        // the segments of the path being read, each with its plastic per mm: a path's widest (its stated width) sets the
        // scale for the rest (Seg.bead): the bead's cross-section, h (w - h (1 - pi/4)), goes as the plastic per mm
        val path = ArrayList<Pair<Seg, Double>>()
        fun endPath() {
            if (path.isEmpty()) return
            // the widest from segments long enough that the file's rounding of E does not set it
            val top = (path.filter { (s, _) -> Math.hypot(s.x1 - s.x0, s.y1 - s.y0) >= 0.05 }.takeIf { it.isNotEmpty() } ?: path).maxOf { it.second }
            val flank = h * (1 - Math.PI / 4)
            if (top > 0) for ((s, rate) in path) s.bead = flank + (s.w - flank) * minOf(1.0, rate / top)
            path.clear()
        }
        fun flush() { endPath(); if (started && !z.isNaN()) out += LayerLines(z, h, segs); segs = above; above = ArrayList() }
        for (raw in text.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { flush(); started = true; continue }
            if (l.startsWith(";WIDTH:") || l.startsWith("; LINE_WIDTH:") || l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) endPath()
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
            if (TOOL.matches(c)) { endPath(); tool = c.substring(1).toInt(); continue }
            val ws = c.split(Regex("\\s+"))
            val arc = ws[0] == "G2" || ws[0] == "G3"
            if (!(ws[0] == "G1" || ws[0] == "G0" || arc)) continue
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: zc; val e = num('E') ?: 0.0
            if (started && e > 0 && !x.isNaN() && (nx != x || ny != y || arc) && feature in MODEL) {
                if (arc) {
                    val chords = ArrayList<Seg>()
                    arcChords(x, y, nx, ny, x + (num('I') ?: 0.0), y + (num('J') ?: 0.0), ws[0] == "G2") { ax, ay, bx, by -> chords += Seg(ax, ay, bx, by, w, feature in WALLS, tool, nz, nz, feature in WAVES, feature in INFILL) }
                    val len = chords.sumOf { Math.hypot(it.x1 - it.x0, it.y1 - it.y0) }
                    segs += chords
                    if (len > 0) for (s in chords) path += s to e / len
                } else {
                    val s = Seg(x, y, nx, ny, w, feature in WALLS, tool, zc, nz, feature in WAVES, feature in INFILL)
                    (if (feature == ABOVE) above else segs) += s
                    path += s to e / Math.hypot(nx - x, ny - y)
                }
            } else if (nx != x || ny != y || e < 0) endPath()     // a travel or a retraction ends it (a feed rate alone does not)
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
