package plymeld.core.check

import plymeld.program.Counter
import plymeld.geom.Polygon
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup
import java.util.Locale

/**
 * Judges a print's g-code against the MODEL, not against how it was planned: every layer's printed lines are compared
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
 *  - laid twice: a spot where two runs' lines lie over each other further than the planner overlaps lines on purpose
 *    (neighboring beads' flanks, the slicing tolerance, a line end's reach into the walls, fuzzy skin), wider than that
 *    narrowest place for a line ([DoubledCheck]); ironing, wave beads, one run over itself and two sparse infill lines
 *    crossing are not judged. Measured on every layer and reported ([checkAll]'s Result); a problem only when
 *    [DoubledCheck.REFUSES] (its own words say why it is off and when it turns on);
 *  - Z anti-aliasing (a [ZaaRule]): a layer's outline also takes in its cut at the thinnest lowered line (ZaaRule.minMm) above its bottom
 *    (the layer reaches out over a slope to there), and a point of a line below its layer, on the model's top within the
 *    layer, is not outside;
 *  - over air (a layer with wave beads): each piece of the layer's cross-section where the model has none under it (its
 *    cut at the middle of the layer below) that has a wave bead in it, laid all over: no point of it, where a line's center could go, more than a line's width
 *    from every line of the layer (a gap wider than a line between beads). Refused over [AIR_MM2] unlaid; wave beads are
 *    laid a spacing apart, and what no front reaches is bridged, so the planner leaves none;
 *  - held bridge: a bridge line over air with nothing under either end, neither the model below nor support
 *    ([bridgeProblems]): it is laid in the air with nothing to start or end on. Reported ([Result.said]), not refused,
 *    while the planner still lays such lines where the plan accepts them (support locked off or kept off a region, a
 *    sliver the wave overhangs leave to bridges, locked support spots that leave an overhang out);
 *  - tool (when the config's part tools are given): the model's lines printed by the tool (the T command in force) the
 *    config's loadout assigns the part whose own cross-section holds them; and on the object's outer surface, no line's
 *    bead covering a stretch of the outline that belongs to another tool's part alone (a filament showing outside its own
 *    part).
 *
 * Support, brim, purge tower, and the start template's lines are not the model's and are not judged; support is read for
 * what it holds up (the held-bridge rule).
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
     * A layer whose thickness varies along it (a curved layer read flat in warped space, CurvedCheck): its real thickness
     * (mm) at a point, given the layer's height as the text states it; NaN where the text's own layer height holds (a flat
     * layer). Its lines' plastic per mm goes as the thickness where they are, not as their width alone ([layers]).
     */
    fun interface Thickness { fun at(layerZ: Double, x: Double, y: Double): Double }

    /**
     * A printed segment: [w], its path's width as the file states it (`;WIDTH`, a variable-width line's widest); [bead], this
     * segment's own width from its plastic ([layers]: its share of the path's widest extrusion per mm, on the layer's
     * thickness where it lies); [t], that thickness (NaN: not read).
     */
    internal class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double, val wall: Boolean, val tool: Int = 0,
                       val z0: Double = Double.NaN, val z1: Double = Double.NaN, val wave: Boolean = false,
                       /** A line of an infill or a surface (not a wall: [INFILL]). */
                       val infill: Boolean = false,
                       /** Its feature, as the file names it. */
                       val feature: String = "",
                       /** Its run: what the nozzle lays without stopping ([layers]); one number a run, increasing through the file. */
                       val run: Int = 0) {
        var bead: Double = w
        var t: Double = Double.NaN
    }
    /** The features that fill an area (infill, surfaces, bridges, ironing), as against the walls. */
    private val INFILL = setOf("Sparse infill", "Internal solid infill", "Solid infill", "Top surface", "Bottom surface", "Bridge", "Internal Bridge",
        "Gap infill", "Ironing")
    /** A wave bead (laid in its own layer or carried into the one above): a layer with one lays the area over air ([airProblems]). */
    private val WAVES = setOf("Wave overhang", ABOVE)
    /** Support and its interface, as the file names them: not the model's, read for what they hold up ([bridgeProblems]). */
    private val SUPPORT = setOf("Support", "Support interface")

    /**
     * [parts]: the placed meshes in bed coordinates; [zBaseMm]: the height of the bed in their frame
     * (g-code Z = mesh Z - zBase). [tolUm]: extra allowance beyond half a line (fuzzy skin, rounding); gaps in the
     * cross-section narrower than it are not outline.
     * [imagesDir]: when given, a picture of each layer with a problem or a spot laid twice (ModelCheckPlot). [surface]: the
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
     * [slicingTolMm]: the slicing tolerance (method.slicing.tolerance) and [endOverlap]: how far a line's end reaches into
     * the walls past bead contact, a share of its width (plan.layer.infill.wall_overlap): overlaps the laid-twice rule
     * allows ([DoubledCheck]); 0: none.
     * The problems only; [checkAll] gives the laid-twice rule's measure too.
     */
    fun check(text: String, parts: List<TriangleSoup>, zBaseMm: Double, tolUm: Long = 100, maxProblems: Int = 30,
              imagesDir: java.nio.file.Path? = null, surface: Surface? = null, partTools: List<Int>? = null, zaa: ZaaRule? = null,
              progress: plymeld.program.Progress = plymeld.program.Progress.NONE, handOverMm: Double = 0.0, featureMinMm: Double = 0.0,
              firstLayerInsetMm: Double = 0.0, surfaceHandOverMm: Double = 0.0, fuzzMm: Double = 0.0, infillMinMm: Double = Double.MAX_VALUE,
              slicingTolMm: Double = 0.0, endOverlap: Double = 0.0): List<String> =
        checkAll(text, parts, zBaseMm, tolUm, maxProblems, imagesDir, surface, partTools, zaa, progress, handOverMm, featureMinMm,
            firstLayerInsetMm, surfaceHandOverMm, fuzzMm, infillMinMm, slicingTolMm, endOverlap).problems

    /**
     * The check's outcome: its [problems] (the file is refused on any), and the laid-twice rule's measure over every layer
     * ([DoubledCheck.Summary]: reported, and among the problems only when [DoubledCheck.REFUSES]), and what it [said]
     * without refusing: the held-bridge rule's findings ([bridgeProblems]), reported until the planner lays no such line.
     */
    class Result(val problems: List<String>, val laidTwice: DoubledCheck.Summary, val said: List<String> = emptyList())

    /** As [check], with the laid-twice rule's measure and the held-bridge rule's findings ([Result]). */
    fun checkAll(text: String, parts: List<TriangleSoup>, zBaseMm: Double, tolUm: Long = 100, maxProblems: Int = 30,
                 imagesDir: java.nio.file.Path? = null, surface: Surface? = null, partTools: List<Int>? = null, zaa: ZaaRule? = null,
                 progress: plymeld.program.Progress = plymeld.program.Progress.NONE, handOverMm: Double = 0.0, featureMinMm: Double = 0.0,
                 firstLayerInsetMm: Double = 0.0, surfaceHandOverMm: Double = 0.0, fuzzMm: Double = 0.0, infillMinMm: Double = Double.MAX_VALUE,
                 slicingTolMm: Double = 0.0, endOverlap: Double = 0.0): Result {
        require(partTools == null || partTools.size == parts.size) { "${partTools!!.size} part tools for ${parts.size} parts" }
        val sf = surface ?: Surface(parts, zBaseMm, text, zaaMinMm = zaa?.minMm)
        val layers = sf.layerLines
        if (layers.isEmpty()) return Result(listOf("no layers in the g-code"), DoubledCheck.Summary.NONE)
        // this check's own pictures from an earlier run would describe another file: cleared first
        imagesDir?.takeIf { java.nio.file.Files.isDirectory(it) }?.let { d ->
            java.nio.file.Files.list(d).use { s -> s.filter { it.fileName.toString().matches(Regex("z[0-9.]+\\.png")) }.forEach { java.nio.file.Files.delete(it) } }
        }
        val tol = tolUm / 1000.0
        if (imagesDir != null) System.setProperty("java.awt.headless", "true")
        // every layer on its own, in parallel; the problems in layer order (the model's cuts made first, here: Parallel)
        sf.ready()
        // each layer's problems and its laid-twice reading, returned by its own task (nothing shared between the tasks)
        val per = plymeld.core.Parallel.map(layers.size, progress.Counter("model check", layers.size)) { k ->
            val lo = layerProblems(sf, k, tol, imagesDir, zaa, featureMinMm, if (k == 0) firstLayerInsetMm else 0.0, fuzzMm, infillMinMm, slicingTolMm, endOverlap)
            LayerOut(lo.problems + airProblems(sf, k, tol, imagesDir) + toolProblems(sf, k, tol + handOverMm, partTools) + surfaceToolProblems(sf, k, tol + surfaceHandOverMm, partTools),
                lo.twice, lo.picture, said = bridgeProblems(sf, k, tol, imagesDir)).also { sf.release(k) } }
        // the layers' pictures, one at a time once every layer is read: drawn by the layers' own tasks, a picture each
        // (an image of about 4 MB, its line list and its PNG encoding) on every core at once takes a 512 MB test JVM to its
        // limit where a slice holds a spot laid twice on dozens of layers
        if (imagesDir != null) for (k in per.indices) { val p = per[k].picture ?: continue
            val file = imagesDir.resolve("z%07.3f.png".format(Locale.ROOT, layers[k].z))
            val lines = layers[k].segs.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1, it.w, if (it.wall) 1.0 else 0.0) }
            if (p.uncoveredIs == null) ModelCheckPlot.write(file, p.title, sf.wallLoops(k), lines, p.outPts, p.uncPts, p.cx, p.cy)
            else ModelCheckPlot.write(file, p.title, sf.wallLoops(k), lines, p.outPts, p.uncPts, p.cx, p.cy, uncoveredIs = p.uncoveredIs) }
        return Result(per.flatMap { it.problems }.take(maxProblems),
            DoubledCheck.Summary(per.indices.mapNotNull { k -> per[k].twice.takeIf { it.spots.isNotEmpty() || it.capped }?.let { DoubledCheck.Layer(layers[k].z, "", it) } }),
            per.flatMap { it.said }.take(maxProblems))
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
        fun gridOf(ps: List<Polygon>) = Grid.ofLoops(ps)
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
     * The held-bridge rule: a bridge line over air (the model's cut below the layer, [Surface.below], not under its middle
     * nor within half its width and the tolerance of it: a line beside a step rests on the step's edge) that nothing holds
     * at either end. An end is held where printed material lies under it, or under the point one line width past it (the
     * probe the planner's bridge direction reads, Bridges): the model's cut below the layer, within the same reach, or a support
     * line within reach (the two beads' half widths and the tolerance) in a layer below, the highest such, with no surface
     * of the model between it and this layer ([Surface.modelTopUnder]). A bridge line is a straight run of a bridge's
     * moves (a move continuing another in line, within the tolerance, extends it) longer than three line widths: the planner
     * links two lines no further apart than three spacings, so a link along an edge is never one. The first layer stands on
     * the bed; a move in a warped frame (its height changing along it, or read in warped space, [Surface.thickness]) is not
     * judged: the cut below a warped layer is not what lies under it.
     */
    private fun bridgeProblems(sf: Surface, k: Int, tol: Double, imagesDir: java.nio.file.Path?): List<String> {
        val layer = sf.layerLines[k]
        if (layer.z - layer.h < 1e-6) return emptyList()
        // a move of a layer read in warped space (a curved layer, a lifted bridge: its thickness known along it) is not judged
        val moves = layer.segs.filter { it.feature == "Bridge" && (it.z0.isNaN() || it.z1.isNaN() || Math.abs(it.z0 - it.z1) < 1e-6) &&
            sf.thickness?.at(layer.z, (it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2)?.isNaN() != false }
        if (moves.isEmpty()) return emptyList()
        // the straight runs: a move continuing the one before (same run, starting where it ends) in line with it
        val lines = ArrayList<DoubleArray>()   // x0, y0, x1, y1, width
        var cur: DoubleArray? = null; var curRun = -1
        for (s in moves) {
            val c = cur
            if (c != null && s.run == curRun && Math.hypot(s.x0 - c[2], s.y0 - c[3]) <= 1e-6) {
                val len = Math.hypot(s.x1 - c[0], s.y1 - c[1])
                // the joint's distance from the line through the run's start and the move's end
                val off = if (len == 0.0) 0.0 else Math.abs((s.x1 - c[0]) * (c[3] - c[1]) - (s.y1 - c[1]) * (c[2] - c[0])) / len
                val forward = (c[2] - c[0]) * (s.x1 - s.x0) + (c[3] - c[1]) * (s.y1 - s.y0) > 0
                if (off <= tol && forward) { c[2] = s.x1; c[3] = s.y1; c[4] = maxOf(c[4], s.w); continue }
            }
            cur = doubleArrayOf(s.x0, s.y0, s.x1, s.y1, s.w).also { lines += it }; curRun = s.run
        }
        val below = Grid.ofLoops(sf.below(k))
        val bottom = layer.z - layer.h
        /** Whether the model below lies under a bead [w] wide centered at (x, y): under its center, or within its half width and the tolerance. */
        fun onBelow(x: Double, y: Double, w: Double): Boolean = below.inside(x, y) || below.nearest(x, y, w / 2 + tol) <= w / 2 + tol
        /** Whether printed material lies under (x, y) for a line [w] wide: the model below, or support with no model between. */
        fun held(x: Double, y: Double, w: Double): Boolean {
            if (onBelow(x, y, w)) return true
            for (j in k - 1 downTo 0) {
                val g = sf.supportGrid(j) ?: continue
                val reach = sf.layerLines[j].support.maxOf { it.w } / 2 + w / 2 + tol
                if (g.nearest(x, y, reach) > reach) continue
                val top = sf.modelTopUnder(x, y, bottom) ?: return true
                return sf.layerLines[j].z > top + 1e-6
            }
            return false
        }
        val loose = lines.filter { l ->
            val len = Math.hypot(l[2] - l[0], l[3] - l[1])
            val w = l[4]
            if (len <= 3 * w) return@filter false
            if (onBelow((l[0] + l[2]) / 2, (l[1] + l[3]) / 2, w)) return@filter false
            val ux = (l[2] - l[0]) / len; val uy = (l[3] - l[1]) / len
            !(held(l[0], l[1], w) || held(l[0] - ux * w, l[1] - uy * w, w) || held(l[2], l[3], w) || held(l[2] + ux * w, l[3] + uy * w, w))
        }
        if (loose.isEmpty()) return emptyList()
        val total = loose.sumOf { Math.hypot(it[2] - it[0], it[3] - it[1]) }
        val longest = loose.maxBy { Math.hypot(it[2] - it[0], it[3] - it[1]) }
        val problem = "layer at z ${f(layer.z, 3)}: ${loose.size} bridge line(s) over air, ${f(total, 1)} mm in all (about ${f(loose.sumOf { Math.hypot(it[2] - it[0], it[3] - it[1]) * it[4] }, 1)} mm2), " +
            "rest on nothing at either end: no model and no support under them (the longest from ${f(longest[0], 2)}, ${f(longest[1], 2)} to ${f(longest[2], 2)}, ${f(longest[3], 2)}); " +
            "a bridge line over air needs printed material under at least one end"
        val file = imagesDir?.resolve("z%07.3f.png".format(Locale.ROOT, layer.z))
        if (file != null && !java.nio.file.Files.exists(file)) {
            val pts = loose.flatMap { l -> val n = maxOf(1, Math.ceil(Math.hypot(l[2] - l[0], l[3] - l[1]) / 0.25).toInt()); (0..n).map { i -> doubleArrayOf(l[0] + (l[2] - l[0]) * i / n, l[1] + (l[3] - l[1]) * i / n) } }
            ModelCheckPlot.write(file, problem, sf.loops(k) + sf.below(k), layer.segs.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1, it.w, if (it.wall) 1.0 else 0.0) }, emptyList(), pts,
                (longest[0] + longest[2]) / 2, (longest[1] + longest[3]) / 2, "bridge line held at neither end")
        }
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

    /** One layer's problems and its laid-twice reading ([DoubledCheck]); the bare-outline and hole rules' readings, where read. */
    private class LayerOut(val problems: List<String>, val twice: DoubledCheck.Reading, val picture: Picture? = null,
                           val bare: Bare? = null, val hole: HoleCheck.Hole? = null, val floor: Double = Double.NaN,
                           /** What the layer's rules found and say without refusing ([Result.said]). */
                           val said: List<String> = emptyList())

    /**
     * What the check measures on one layer, for `plymeld render`'s marks: [twice], the laid-twice rule's spots; the hole
     * rule's widest hole inside the walls ([holeWidthMm] NaN: none) and the bare-outline rule's bare points ([bare], each
     * x, y) and its longest bare stretch ([bareLongestMm]); NaN and empty where the model was not given (the laid-twice rule
     * reads the lines alone).
     */
    class LayerMarks(val z: Double, val twice: DoubledCheck.Reading, val holeWidthMm: Double, val holeX: Double, val holeY: Double, val holeAreaMm2: Double,
                     val bare: List<DoubleArray>, val bareLongestMm: Double,
                     /** The narrowest place a line is laid in, as the rules read it; a hole wider than it and two cells is refused. */
                     val floorMm: Double = Double.NaN) {
        /** The widest a hole may be before the check refuses the file for it. */
        val holeLimitMm: Double get() = floorMm + 2 * HoleCheck.CELL_MM
    }

    /**
     * The check's measures on the layers of [text] whose stated heights are among [zs], each read as [checkAll] reads it
     * (the same rules on the same lines, with the same settings): with [parts] (the model placed as the file was sliced),
     * the hole and bare-outline rules too; without, the laid-twice rule alone (its floor [featureMinMm], which must then be
     * given). The other arguments are [checkAll]'s.
     */
    fun marks(text: String, parts: List<TriangleSoup>?, zBaseMm: Double, zs: Collection<Double>, tolUm: Long = 100, zaa: ZaaRule? = null,
              featureMinMm: Double = 0.0, firstLayerInsetMm: Double = 0.0, fuzzMm: Double = 0.0, infillMinMm: Double = Double.MAX_VALUE,
              slicingTolMm: Double = 0.0, endOverlap: Double = 0.0): List<LayerMarks> {
        fun wanted(z: Double) = zs.any { Math.abs(it - z) < 1e-6 }
        if (parts == null) {
            require(featureMinMm > 0) { "the laid-twice rule's floor (plan.layer.walls.feature.min) is not known" }
            return layers(text).filter { wanted(it.z) }.map { l ->
                LayerMarks(l.z, DoubledCheck.read(l.segs, featureMinMm, slicingTolMm, endOverlap, fuzzMm), Double.NaN, Double.NaN, Double.NaN, Double.NaN, emptyList(), 0.0, featureMinMm) }
        }
        val sf = Surface(parts, zBaseMm, text, zaaMinMm = zaa?.minMm)
        val out = ArrayList<LayerMarks>()
        sf.ready()
        for ((k, l) in sf.layerLines.withIndex()) {
            if (!wanted(l.z)) continue
            val lo = layerProblems(sf, k, tolUm / 1000.0, null, zaa, featureMinMm, if (k == 0) firstLayerInsetMm else 0.0, fuzzMm, infillMinMm, slicingTolMm, endOverlap)
            val h = lo.hole
            out += LayerMarks(l.z, lo.twice, h?.widthMm ?: Double.NaN, h?.x ?: Double.NaN, h?.y ?: Double.NaN, h?.areaMm2 ?: Double.NaN, lo.bare?.pts ?: emptyList(), lo.bare?.longest ?: 0.0, lo.floor)
            sf.release(k)
        }
        return out
    }
    /** A layer's picture, drawn after the layers are read ([checkAll]): its title, the points marked, where it is centered. */
    private class Picture(val title: String, val outPts: List<DoubleArray>, val uncPts: List<DoubleArray>, val cx: Double, val cy: Double,
                          val uncoveredIs: String?)

    /** One layer's problems and laid-twice reading (and its picture, when [imagesDir] is given). */
    private fun layerProblems(sf: Surface, k: Int, tol: Double, imagesDir: java.nio.file.Path?, zaa: ZaaRule?, featureMinMm: Double, insetMm: Double,
                              fuzzMm: Double, infillMinMm: Double, slicingTolMm: Double, endOverlap: Double): LayerOut {
        val out = ArrayList<String>()
        val layer = sf.layerLines[k]
        var reading = DoubledCheck.Reading.NONE
        var bareRead: Bare? = null; var holeRead: HoleCheck.Hole? = null; var floorRead = Double.NaN
        run {
            // the outline the walls must cover (with Z anti-aliasing, reaching out over slopes); "outside" is judged on the
            // cut at the layer's middle, a point of a line lowered onto the model's top excused (below)
            val loops = sf.wallLoops(k)
            val model = layer.segs
            if (loops.isEmpty()) { if (model.isNotEmpty()) out += "layer at z ${f(layer.z, 3)}: ${model.size} model lines printed where the model has no cross-section"; return LayerOut(out, reading) }
            if (model.isEmpty()) {
                // a cross-section with nothing of the model printed on it: a sliver too thin to print, or a missing layer (its
                // cut at the middle: what reaches out over a slope with Z anti-aliasing is a sliver by nature)
                val area = sf.loops(k).sumOf { it.signedAreaMm2() }
                if (area > 1.0) out += "layer at z ${f(layer.z, 3)}: nothing of the model printed, though its cross-section there is ${f(area, 1)} mm2"
                return LayerOut(out, reading)
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
                    Grid.ofLoops(centers)
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
            // plastic laid twice (DoubledCheck): two lines over each other further than neighboring lines overlap by plan, over
            // a spot wider than the narrowest place a line is laid in, read to a grid cell either side. Measured on every
            // layer (the slice's "laid twice" line), a problem only when DoubledCheck.REFUSES; drawn in the layer's picture
            // either way
            reading = DoubledCheck.read(model, floor, slicingTolMm, endOverlap, fuzzMm)
            bareRead = bare; holeRead = hole; floorRead = floor
            val twice = reading.spots
            val twiceWords = twice.firstOrNull()?.let { s ->
                "plastic laid twice over a spot ${f(s.widthMm, 2)} mm wide (${f(s.areaMm2, 3)} mm2, at ${f(s.x, 2)}, ${f(s.y, 2)}): " +
                    "${s.lines()} of the same layer, further than neighboring lines overlap" +
                    (if (twice.size > 1) " (${twice.size} such spots on the layer, ${f(twice.sumOf { it.areaMm2 }, 3)} mm2 in all)" else "") +
                    (if (reading.capped) String.format(Locale.ROOT, " (the reading stopped at %,d cells laid twice: more lies twice on the layer than this counts)", DoubledCheck.MAX_CELLS) else "") }
            if (twiceWords != null) uncPts += doubleArrayOf(twice[0].x, twice[0].y)
            if (twiceWords != null && DoubledCheck.REFUSES) out += "layer at z ${f(layer.z, 3)}: $twiceWords"
            if (imagesDir != null && (badOutside || badOutline || badBare || badHole || twiceWords != null)) {
                val (cx, cy) = if (badOutline) ux to uy else if (badBare) bare.x to bare.y else if (badHole) hole.x to hole.y else if (twiceWords != null) twice[0].x to twice[0].y else wx to wy
                // a layer with nothing refused, drawn for its spots laid twice: titled and marked as that
                val picture = if (out.isEmpty()) Picture("layer at z ${f(layer.z, 3)}: $twiceWords", outPts, uncPts, cx, cy, "the widest spot laid twice")
                    else Picture("layer at z ${f(layer.z, 3)}: ${out.last().substringAfter(": ")}", outPts, uncPts, cx, cy, null)
                return LayerOut(out, reading, picture, bareRead, holeRead, floorRead)
            }
        }
        return LayerOut(out, reading, bare = bareRead, hole = holeRead, floor = floorRead)
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
            Grid.ofLoops(c)
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

    /** A layer's model lines [segs], and its [support] lines (support and its interface: what holds a bridge line's end, [bridgeProblems]). */
    internal class LayerLines(val z: Double, val h: Double, val segs: List<Seg>, val support: List<Seg> = emptyList())

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
                  val cut: DoubleArray? = null,
                  /** A curved layer's real thickness along it (CurvedCheck's Read and Frame), for its lines' beads ([layers]); null: every layer flat. */
                  internal val thickness: Thickness? = null) {
        /** Whether an outline point lies on the open section's cut (within [tolMm] of a side of the box). */
        fun onCut(x: Double, y: Double, tolMm: Double): Boolean = cut != null &&
            (Math.abs(x - cut[0]) <= tolMm || Math.abs(x - cut[1]) <= tolMm || Math.abs(y - cut[2]) <= tolMm || Math.abs(y - cut[3]) <= tolMm)
        private val cutRect: List<Polygon>? = cut?.let { c -> listOf(Polygon(longArrayOf(Math.round(c[0] * 1000), Math.round(c[2] * 1000), Math.round(c[1] * 1000), Math.round(c[2] * 1000),
            Math.round(c[1] * 1000), Math.round(c[3] * 1000), Math.round(c[0] * 1000), Math.round(c[3] * 1000)))) }
        /** [loops], the model cut at [z] mm, inside the open section's box (as they are with none; none above its top). */
        private fun inCut(z: Double, loops: List<Polygon>): List<Polygon> = if (cutRect == null || loops.isEmpty()) loops
            else if (z > cut!![4] + 1e-6) emptyList() else plymeld.geom.Booleans.intersection(loops, cutRect)
        internal val layerLines: List<LayerLines> = layers(text, thickness)
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
        /**
         * Layer [k]'s grids and its cut below let go, once its rules have read it ([checkAll]): made again if asked for
         * (the travel rule after a plate's model check), so a check holds only the layers it is reading, not every one.
         */
        internal fun release(k: Int) { grids[k] = null; for (g in partGrids) g[k] = null; belows[k] = null }
        /** How many of the layers' grids and cuts below are held now (the outline's, each part's, the cut below; [release]). */
        internal fun held(): Int = grids.count { it != null } + partGrids.sumOf { g -> g.count { it != null } } + belows.count { it != null }
        private fun gridOf(loops: List<Polygon>) = Grid.ofLoops(loops)
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
        // each layer's support lines as a grid, made when first asked for and kept (support lines are few): a bridge line's
        // end looks down through the layers below it for them ([bridgeProblems])
        private val supportGrids = arrayOfNulls<Grid>(layerLines.size)
        /** Layer [k]'s support lines as a grid; null when it has none. */
        internal fun supportGrid(k: Int): Grid? {
            val s = layerLines[k].support
            if (s.isEmpty()) return null
            return supportGrids[k] ?: Grid(s.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) }).also { supportGrids[k] = it }
        }
        /**
         * The highest of the model's upward-facing surfaces straight under (x, y) below the g-code height [zMm] (in the
         * g-code's frame); null when nothing of the model is under it there (down to the bed).
         */
        internal fun modelTopUnder(x: Double, y: Double, zMm: Double): Double? =
            parts.mapNotNull { plymeld.core.decide.Overhangs.standsOn(it, x, y, zMm + zBaseMm) }.maxOrNull()?.let { it - zBaseMm }
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

    /**
     * The model's lines, layer by layer. [thickness]: where a layer's thickness varies along it (a curved layer), its real
     * thickness at each segment's middle; null: each layer as thick as its stated height everywhere.
     */
    internal fun layers(text: String, thickness: Thickness? = null): List<LayerLines> {
        val out = ArrayList<LayerLines>()
        var segs = ArrayList<Seg>(); var z = Double.NaN; var h = 0.2; var started = false
        var sup = ArrayList<Seg>()
        // the lines laid in this layer's block for the layer above (ABOVE): carried into the next layer's
        var above = ArrayList<Seg>()
        var x = Double.NaN; var y = Double.NaN; var zc = Double.NaN; var w = 0.4; var feature = ""; var tool = 0
        // the run: what the nozzle lays without stopping (Seg.run); a travel, a retraction, a tool or layer change, or a line
        // that is not the model's, ends it
        var run = 0; var broken = true
        // the segments of the path being read, each with its plastic per mm: a path's widest (its stated width) sets the
        // scale for the rest (Seg.bead): the bead's cross-section, t (w - t (1 - pi/4)) on a layer t thick, goes as the
        // plastic per mm
        val path = ArrayList<Pair<Seg, Double>>()
        fun endPath() {
            if (path.isEmpty()) return
            // the widest from segments long enough that the file's rounding of E does not set it
            val long = path.filter { (s, _) -> Math.hypot(s.x1 - s.x0, s.y1 - s.y0) >= 0.05 }.takeIf { it.isNotEmpty() } ?: path
            val ts = if (thickness == null || z.isNaN()) null else DoubleArray(path.size) { i -> val s = path[i].first
                thickness.at(z, (s.x0 + s.x1) / 2, (s.y0 + s.y1) / 2).let { t -> if (t.isNaN() || t <= 0) h else t } }
            if (ts == null) {
                val top = long.maxOf { it.second }
                val flank = h * (1 - Math.PI / 4)
                for ((s, rate) in path) { s.t = h; if (top > 0) s.bead = flank + (s.w - flank) * minOf(1.0, rate / top) }
            } else {
                // a layer whose thickness varies along the path (a curved layer): a segment's cross-section, c times its plastic
                // per mm, is t (bead - t (1 - pi/4)) on its own thickness t; c makes the path's widest bead its stated width
                // (on one thickness throughout, the reading above). A thinner stretch lays less plastic per mm at the same
                // width: read on the layer's stated height, a curved island's walls 0.44 mm wide read 0.32 mm where the layer
                // is two thirds as thick as where it is thickest
                val longOnly = long !== path
                var c = Double.MAX_VALUE
                for ((i, p) in path.withIndex()) { val (s, rate) = p
                    if (rate > 0 && (!longOnly || Math.hypot(s.x1 - s.x0, s.y1 - s.y0) >= 0.05)) c = minOf(c, (s.w - ts[i] * (1 - Math.PI / 4)) * ts[i] / rate) }
                if (c < Double.MAX_VALUE) for ((i, p) in path.withIndex()) { val (s, rate) = p; val t = ts[i]
                    s.bead = minOf(s.w, c * rate / t + t * (1 - Math.PI / 4)) }
                for ((i, p) in path.withIndex()) p.first.t = ts[i]
            }
            path.clear()
        }
        fun flush() { endPath(); broken = true; if (started && !z.isNaN()) out += LayerLines(z, h, segs, sup); segs = above; above = ArrayList(); sup = ArrayList() }
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
            if (TOOL.matches(c)) { endPath(); broken = true; tool = c.substring(1).toInt(); continue }
            val ws = c.split(Regex("\\s+"))
            val arc = ws[0] == "G2" || ws[0] == "G3"
            if (!(ws[0] == "G1" || ws[0] == "G0" || arc)) continue
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: zc; val e = num('E') ?: 0.0
            if (started && e > 0 && !x.isNaN() && (nx != x || ny != y || arc) && feature in MODEL) {
                if (broken) { run++; broken = false }
                if (arc) {
                    val chords = ArrayList<Seg>()
                    arcChords(x, y, nx, ny, x + (num('I') ?: 0.0), y + (num('J') ?: 0.0), ws[0] == "G2") { ax, ay, bx, by -> chords += Seg(ax, ay, bx, by, w, feature in WALLS, tool, nz, nz, feature in WAVES, feature in INFILL, feature, run) }
                    val len = chords.sumOf { Math.hypot(it.x1 - it.x0, it.y1 - it.y0) }
                    segs += chords
                    if (len > 0) for (s in chords) path += s to e / len
                } else {
                    val s = Seg(x, y, nx, ny, w, feature in WALLS, tool, zc, nz, feature in WAVES, feature in INFILL, feature, run)
                    (if (feature == ABOVE) above else segs) += s
                    path += s to e / Math.hypot(nx - x, ny - y)
                }
            } else {
                // a support line: kept apart from the model's, for what it holds up
                if (started && e > 0 && !x.isNaN() && (nx != x || ny != y || arc) && feature in SUPPORT) {
                    if (arc) arcChords(x, y, nx, ny, x + (num('I') ?: 0.0), y + (num('J') ?: 0.0), ws[0] == "G2") { ax, ay, bx, by -> sup += Seg(ax, ay, bx, by, w, false, tool, nz, nz, feature = feature) }
                    else sup += Seg(x, y, nx, ny, w, false, tool, zc, nz, feature = feature)
                }
                if (nx != x || ny != y || e < 0) { endPath(); broken = true }     // a travel or a retraction ends it (a feed rate alone does not)
            }
            x = nx; y = ny; zc = nz
        }
        flush()
        return out
    }

    /**
     * An arc move (G2 clockwise, G3 counter-clockwise) from (x0, y0) to (x1, y1) about (cx, cy) as chords no longer than
     * [chordMm] ([ARC_CHORD_MM] unless a reader needs them finer, as `plymeld render` does to draw a curve round when
     * magnified) and lying no further than [ARC_SAG_MM] inside the arc, each passed to [seg]; an arc ending where it starts
     * is a full circle.
     */
    fun arcChords(x0: Double, y0: Double, x1: Double, y1: Double, cx: Double, cy: Double, cw: Boolean, chordMm: Double = ARC_CHORD_MM,
                  seg: (Double, Double, Double, Double) -> Unit) {
        val r = Math.hypot(x0 - cx, y0 - cy)
        val a0 = Math.atan2(y0 - cy, x0 - cx); val a1 = Math.atan2(y1 - cy, x1 - cx)
        var sweep = if (cw) a0 - a1 else a1 - a0          // the angle traveled, positive
        while (sweep <= 1e-12) sweep += 2 * Math.PI
        // a chord of an arc of angle t lies r (1 - cos(t / 2)) inside it at its middle
        val bySag = if (r <= ARC_SAG_MM) 1 else Math.ceil(sweep / (2 * Math.acos(1 - ARC_SAG_MM / r))).toInt()
        val n = maxOf(1, Math.ceil(sweep * r / chordMm).toInt(), bySag)
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
     * The furthest a chord of an arc lies inside it (mm): a fifth of the hole rule's cell (HoleCheck.CELL_MM). Chords only
     * as long as [ARC_CHORD_MM] lay a small loop's beads inside its arcs (on a loop 0.69 mm round, 0.046 mm), and the
     * ground they close in read that much narrower each side: a pin's middle as wide as the walls hand to infill read
     * narrower, a hole. plymeld's choice.
     */
    const val ARC_SAG_MM = 0.005

    /**
     * Segments (x0, y0, x1, y1, mm; [s] holds four numbers a segment) bucketed on a 1 mm grid: the nearest one to a point
     * within a radius, and inside / outside of closed loops by crossings of a ray along +x (rows by y).
     * Flat: the cells and rows are sorted primitive arrays, each one's segment indices a run of one array. The check keeps
     * a grid of every layer's outline and of each part's while it runs; held as maps of boxed indices and an array a
     * segment, they took about 70 MB of a 512 MB test heap on a 262k-triangle model.
     */
    internal class Grid(private val s: DoubleArray) {
        constructor(segs: List<DoubleArray>) : this(DoubleArray(4 * segs.size).also { a -> for ((i, q) in segs.withIndex()) System.arraycopy(q, 0, a, 4 * i, 4) })
        private val cell = 1.0
        // the cells' and rows' extent, in cells; a cell's id is its place in the extent, row by row
        private var lx = 0; private var ly = 0; private var hy = -1; private var w = 1
        // the cells holding a segment by id, ascending; cell k's segments are cellSegs[cellAt[k] until cellAt[k + 1]]
        private val cellIds: IntArray; private val cellAt: IntArray; private val cellSegs: IntArray
        // the same by row (y cell less [ly])
        private val rowIds: IntArray; private val rowAt: IntArray; private val rowSegs: IntArray
        init {
            val n = s.size / 4
            var hx = Int.MIN_VALUE; lx = Int.MAX_VALUE; ly = Int.MAX_VALUE; hy = Int.MIN_VALUE
            var ce = 0L; var re = 0L
            for (i in 0 until n) {
                val x0 = cellOf(minOf(s[4 * i], s[4 * i + 2])); val x1 = cellOf(maxOf(s[4 * i], s[4 * i + 2]))
                val y0 = cellOf(minOf(s[4 * i + 1], s[4 * i + 3])); val y1 = cellOf(maxOf(s[4 * i + 1], s[4 * i + 3]))
                lx = minOf(lx, x0); hx = maxOf(hx, x1); ly = minOf(ly, y0); hy = maxOf(hy, y1)
                ce += (x1 - x0 + 1).toLong() * (y1 - y0 + 1); re += y1 - y0 + 1
            }
            if (n == 0) { lx = 0; ly = 0; hy = -1; w = 1 } else w = hx - lx + 1
            require(n == 0 || (hy - ly + 1).toLong() * w < Int.MAX_VALUE && ce < Int.MAX_VALUE) { "a grid of ${w} x ${hy - ly + 1} cells and $ce entries is too large" }
            // each (cell, segment) and (row, segment) as one long, the id high and the segment low, sorted: grouped by id
            val ck = LongArray(ce.toInt()); val rk = LongArray(re.toInt())
            var c = 0; var r = 0
            for (i in 0 until n) {
                val x0 = cellOf(minOf(s[4 * i], s[4 * i + 2])); val x1 = cellOf(maxOf(s[4 * i], s[4 * i + 2]))
                val y0 = cellOf(minOf(s[4 * i + 1], s[4 * i + 3])); val y1 = cellOf(maxOf(s[4 * i + 1], s[4 * i + 3]))
                for (cx in x0..x1) for (cy in y0..y1) ck[c++] = (((cy - ly).toLong() * w + (cx - lx)) shl 32) or i.toLong()
                for (cy in y0..y1) rk[r++] = ((cy - ly).toLong() shl 32) or i.toLong()
            }
            ck.sort(); rk.sort()
            fun groups(k: LongArray): Triple<IntArray, IntArray, IntArray> {
                var g = 0
                for (j in k.indices) if (j == 0 || (k[j] ushr 32) != (k[j - 1] ushr 32)) g++
                val ids = IntArray(g); val at = IntArray(g + 1); val segs = IntArray(k.size)
                g = 0
                for (j in k.indices) {
                    if (j == 0 || (k[j] ushr 32) != (k[j - 1] ushr 32)) { ids[g] = (k[j] ushr 32).toInt(); at[g] = j; g++ }
                    segs[j] = (k[j] and 0xffffffffL).toInt()
                }
                at[g] = k.size
                return Triple(ids, at, segs)
            }
            val (ci, ca, cs) = groups(ck); cellIds = ci; cellAt = ca; cellSegs = cs
            val (ri, ra, rs) = groups(rk); rowIds = ri; rowAt = ra; rowSegs = rs
        }
        private fun cellOf(v: Double) = Math.floor(v / cell).toInt()
        /** The first index of [ids] whose value is at least [v]. */
        private fun lowerBound(ids: IntArray, v: Int): Int { var a = 0; var b = ids.size; while (a < b) { val m = (a + b) ushr 1; if (ids[m] < v) a = m + 1 else b = m }; return a }
        fun nearest(px: Double, py: Double, r: Double): Double {
            var best = Double.MAX_VALUE
            val rc = Math.ceil(r / cell).toInt(); val cx = cellOf(px); val cy = cellOf(py)
            val gx0 = maxOf(cx - rc, lx); val gx1 = minOf(cx + rc, lx + w - 1)
            if (gx0 > gx1) return best
            for (gy in maxOf(cy - rc, ly)..minOf(cy + rc, hy)) {
                val base = (gy - ly) * w
                var k = lowerBound(cellIds, base + gx0 - lx)
                val last = base + gx1 - lx
                while (k < cellIds.size && cellIds[k] <= last) {
                    for (j in cellAt[k] until cellAt[k + 1]) { val q = 4 * cellSegs[j]; best = minOf(best, pointSeg(px, py, s[q], s[q + 1], s[q + 2], s[q + 3])) }
                    k++
                }
            }
            return best
        }
        fun inside(px: Double, py: Double): Boolean {
            var c = false
            val row = cellOf(py)
            if (row < ly || row > hy) return false
            val k = lowerBound(rowIds, row - ly)
            if (k >= rowIds.size || rowIds[k] != row - ly) return false
            for (j in rowAt[k] until rowAt[k + 1]) {
                val q = 4 * rowSegs[j]
                if ((s[q + 1] > py) != (s[q + 3] > py) && px < (s[q + 2] - s[q]) * (py - s[q + 1]) / (s[q + 3] - s[q + 1]) + s[q]) c = !c
            }
            return c
        }

        companion object {
            /** The edges of [loops] (µm), each loop closed, as a grid in mm. */
            fun ofLoops(loops: List<Polygon>): Grid {
                val a = DoubleArray(4 * loops.sumOf { it.size }); var at = 0
                for (p in loops) for (i in 0 until p.size) {
                    a[at++] = p.x(i) / 1000.0; a[at++] = p.y(i) / 1000.0; a[at++] = p.x((i + 1) % p.size) / 1000.0; a[at++] = p.y((i + 1) % p.size) / 1000.0
                }
                return Grid(a)
            }
        }
    }

    private fun pointSeg(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    private fun f(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)
}
