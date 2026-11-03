package plymeld.core.check

import plymeld.mesh.TopSurface
import plymeld.mesh.TriangleSoup
import plymeld.mesh.Warp
import java.util.Locale

/**
 * The pre-print check of a curved band. The planar checks (GcodeCheck,
 * ModelCheck) read every layer as flat; a curved layer is flat only in warped space. So this check makes its OWN
 * warp, from the model's mesh and the band's start (the plan's decision), never from the slicer that made the file,
 * and reads the g-code with it:
 *
 *  - [read] maps every move's Z into warped space (the line count kept, so problems name the file's own lines) and
 *    warps the parts the same way: the planar checks then run on those, a curved layer flat at its warped height;
 *  - its own rules, on the real g-code:
 *    - surface: every line printed in a curved layer lies on that layer's surface (its ends within [ON_SURFACE_MM],
 *      every point along it, sampled every [ALONG_MM], within the slicing resolution on top of that: the straight
 *      chord against the curve);
 *    - thickness: each layer, between the surface under it and its own, within the tool's thinnest and thickest
 *      layer (hardware.tool.layer.height.min .. layer.height.max) at every point printed;
 *    - head clearance (the nozzle's cone): the layer's surface no steeper than the
 *      nozzle angle (hardware.tool.head.nozzle.angle) wherever it is printed; and the head around the nozzle clear of what is printed:
 *      with the head's outline known (hardware.tool.head.size.x/y, nozzle_x/y), the plastic under its rectangle at every point
 *      printed under the nozzle height (hardware.tool.head.nozzle.height) above the nozzle ([HeadGrid]); without it, the layer's
 *      whole rise (its highest line less its lowest) under the nozzle height, whatever the outline. Without the angle
 *      and height a curved band is refused.
 *
 * Z speed is not judged here: the firmware holds every move to its max_z_velocity by its Z share, and the predictor
 * counts it.
 */
object CurvedCheck {
    /** The nozzle tip, for the clearance rules, and the head's outline as its reach from the tip (null: not known). */
    class Tip(val coneDeg: Double, val protrusionMm: Double, val reach: plymeld.core.decide.Arrange.Reach? = null)
    /** The grid the head's rule keeps the highest plastic printed in (mm). plymeld's choice: finer than a line. */
    const val HEAD_CELL_MM = 0.5
    /**
     * The head's rule judges a layer's lines in the order printed, a block of this many lines at a time: each block against
     * the plastic laid before it and its own (before, every
     * line of a layer was judged against the whole layer's plastic, so a foot's wall laid before an arch's beads was refused
     * for the beads' height). Within a block each line counts only the block's lines before it (counting the
     * whole block refused a foot's line for an arch's middle X laid just after it). plymeld's choice, a balance of one
     * sliding maximum per block against the line-by-line pass within it.
     */
    const val HEAD_BLOCK = 16
    /** The tool's thinnest and thickest layer, the slicing resolution (mm), the tip (null: not set, refused). */
    class Limits(val minLayerMm: Double, val maxLayerMm: Double, val resolutionMm: Double, val tip: Tip?)
    /**
     * The g-code and the parts in warped space (for the planar checks), and this check's own problems. With several lifted
     * bridges, also [frames]: no one warped space holds them all (a bridge's lifted layers stand where the support above its
     * ends is printed later), so the model's own check reads each frame on its own.
     */
    class Read(val text: String, val parts: List<TriangleSoup>, val problems: List<String>, val curvedLayers: Int, val frames: List<Frame>? = null)
    /**
     * One frame of several lifted bridges: the g-code's layers read in it ([text], the start template and those layers, in
     * warped space), and the parts there (by their index in the object's parts): what stands on the bed, flat, or one
     * bridge, lifted.
     */
    class Frame(val name: String, val text: String, val partIndex: List<Int>, val parts: List<TriangleSoup>,
                /** Its layers are flat, as printed (what stands on the bed; the whole object when there are no frames): Z anti-aliasing applies there. */
                val flat: Boolean = true,
                /** The part of each cut of [parts] its layers are judged against (ModelCheck.Surface's clip); null: all of it. */
                val clip: ((Double, List<plymeld.geom.Polygon>) -> List<plymeld.geom.Polygon>)? = null)

    /**
     * How far a printed line's end may sit off its layer's surface (mm): the writer gives Z in whole µm, so half a µm
     * of rounding, with room for the warp's own arithmetic. plymeld's choice, on the file's precision.
     */
    const val ON_SURFACE_MM = 0.0015
    /** The lines a waved layer lays above itself on purpose, after the layer (plymeld.core.AfterLayer's labels): not what sets the layer's height. */
    private val LAID_ABOVE = setOf("Wave overhang, second pass", "Wave overhang, X layer", "Wave overhang, span layer")
    /**
     * Where a line's end is read against its surface: on it, and around it within the file's XY rounding (half a µm, read
     * at 0.0006 mm), so an end on a step of the surface may take the height on either side of it.
     */
    private val STEP_PROBE: List<Pair<Double, Double>> = listOf(0.0 to 0.0) +
        listOf(-1, 0, 1).flatMap { i -> listOf(-1, 0, 1).map { j -> i * 0.0006 to j * 0.0006 } }.filter { it != 0.0 to 0.0 }
    /** How far apart a line is sampled against its layer's surface (mm). plymeld's choice, the slicer's own split step. */
    const val ALONG_MM = 0.5
    /** Thickness and slope are judged to this slack (mm, degrees): the file's µm rounding, not an allowance. */
    private const val SLACK_MM = 0.0005
    private const val SLACK_DEG = 1e-6

    private class Line(val idx: Int, val layer: Int, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val feature: String = "")

    /** Where the head stands highest over the nozzle in a layer: the nozzle's point, and how much higher the plastic under the head is. */
    private class Worst(val line: Line, val x: Double, val y: Double, val z: Double, val overMm: Double)

    /**
     * The head's rule with its outline: with the nozzle at a point, the head covers the rectangle
     * [reach] around it, its underside the nozzle height above the tip; the plastic printed there must stay under that.
     * The highest plastic printed so far is kept per cell of [HEAD_CELL_MM] (every line walked in half-cell steps, Z
     * along it), the rectangle widened to whole cells (so it is never smaller than the head), and its maximum taken
     * over each cell's rectangle in two sliding passes. A block of a layer's lines is judged against the plastic before
     * it and, line by line, the block's own lines laid before ([judge]), then added: nothing printed after a point counts.
     */
    private class HeadGrid(lines: List<Line>, r: plymeld.core.decide.Arrange.Reach) {
        private val c = HEAD_CELL_MM
        private val x0 = lines.minOf { minOf(it.x0, it.x1) }; private val y0 = lines.minOf { minOf(it.y0, it.y1) }
        private val nx = ((lines.maxOf { maxOf(it.x0, it.x1) } - x0) / c).toInt() + 1
        private val ny = ((lines.maxOf { maxOf(it.y0, it.y1) } - y0) / c).toInt() + 1
        private val g = DoubleArray(nx * ny) { Double.NEGATIVE_INFINITY }
        // the rectangle in cells about the nozzle's cell, rounded out
        private val left = Math.ceil(r.minusX / c).toInt(); private val right = Math.ceil(r.plusX / c).toInt()
        private val front = Math.ceil(r.minusY / c).toInt(); private val back = Math.ceil(r.plusY / c).toInt()
        private fun cell(x: Double, y: Double) = ((x - x0) / c).toInt().coerceIn(0, nx - 1) + nx * ((y - y0) / c).toInt().coerceIn(0, ny - 1)

        fun add(ls: List<Line>) {
            for (s in ls) {
                val n = maxOf(1, Math.ceil(Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / (c / 2)).toInt())
                for (t in 0..n) {
                    val f = t.toDouble() / n
                    val i = cell(s.x0 + (s.x1 - s.x0) * f, s.y0 + (s.y1 - s.y0) * f)
                    g[i] = maxOf(g[i], s.z0 + (s.z1 - s.z0) * f)
                }
            }
        }

        /**
         * Block [ls] (lines in the order printed) judged, then added: each line's ends against the plastic laid before the
         * block (the grid, its maximum over the head's rectangle in cells) and against the block's lines before it, sampled
         * along them within the same rectangle; its end also against the line itself (counting the whole block
         * refused a foot's line laid before an arch's middle X, laid just after it in the same block).
         */
        fun judge(ls: List<Line>): Worst? {
            val rows = DoubleArray(nx * ny)
            for (j in 0 until ny) slide(g, j * nx, 1, nx, left, right, rows)
            val w = DoubleArray(nx * ny)
            for (i in 0 until nx) slide(rows, i, nx, ny, front, back, w)
            // each block line's points as the grid would hold them (every half cell along it)
            val pts = ls.map { s ->
                val n = maxOf(1, Math.ceil(Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / (c / 2)).toInt())
                DoubleArray(3 * (n + 1)) { k -> val f = (k / 3).toDouble() / n
                    when (k % 3) { 0 -> s.x0 + (s.x1 - s.x0) * f; 1 -> s.y0 + (s.y1 - s.y0) * f; else -> s.z0 + (s.z1 - s.z0) * f } }
            }
            var out: Worst? = null
            for ((i, s) in ls.withIndex()) for (end in 0..1) {
                val x = if (end == 0) s.x0 else s.x1; val y = if (end == 0) s.y0 else s.y1; val z = if (end == 0) s.z0 else s.z1
                var top = w[cell(x, y)]
                for (j in 0..(if (end == 0) i - 1 else i)) {
                    val p = pts[j]
                    for (k in 0 until p.size / 3) {
                        val px = p[3 * k]; val py = p[3 * k + 1]
                        if (px >= x - left * c && px <= x + right * c && py >= y - front * c && py <= y + back * c) top = maxOf(top, p[3 * k + 2])
                    }
                }
                val over = top - z
                if (out == null || over > out.overMm) out = Worst(s, x, y, z, over)
            }
            add(ls)
            return out
        }

        /**
         * dst[k] = the maximum of src over [k - a, k + b] (clipped to 0 until n), for the n values of src at start +
         * k * stride: van Herk's running maxima in blocks of the window's length, two per value whatever the window.
         */
        private fun slide(src: DoubleArray, start: Int, stride: Int, n: Int, a: Int, b: Int, dst: DoubleArray) {
            val len = a + b + 1; val m = n + a + b
            fun w(t: Int) = (t - a).let { if (it in 0 until n) src[start + it * stride] else Double.NEGATIVE_INFINITY }
            val pre = DoubleArray(m); val suf = DoubleArray(m)
            for (t in 0 until m) pre[t] = if (t % len == 0) w(t) else maxOf(pre[t - 1], w(t))
            for (t in m - 1 downTo 0) suf[t] = if (t % len == len - 1 || t == m - 1) w(t) else maxOf(suf[t + 1], w(t))
            for (k in 0 until n) dst[start + k * stride] = maxOf(suf[k], pre[k + len - 1])
        }
    }

    /**
     * [parts]: the placed meshes (bed coordinates); [zBase]: the bed's height in their frame (g-code Z = mesh Z - zBase);
     * [zStartMm]: the band's start above the bed (the band's own start, or the first layer's top if higher).
     * [lineOffset]: when [text] is part of a file (one object's run of a one-at-a-time plate), the lines before it, so the
     * problems name the file's own lines.
     */
    /**
     * Which surface the band's layers follow: the model's top, its underside (the whole object), or a lifted
     * bridge's underside (the parts that do not reach the bed, laid after what holds them up).
     */
    enum class Follows { TOP, UNDERSIDE, LIFT }
    /**
     * One object's curved band for the check: where it starts above the bed, the tool's limits, the surface it follows; for
     * a lifted bridge, the layer grid ([firstMm], then [layerMm]) its supports' printed tops are taken on.
     */
    class Spec(val fromMm: Double, val limits: Limits, val follows: Follows = Follows.TOP, val firstMm: Double = 0.0, val layerMm: Double = 0.0,
               /** A lifted bridge with Z anti-aliasing: how far above its bottom a layer holds a support's top (LiftWarp.of); null = its middle. */
               val holdMm: Double? = null)

    /**
     * A lifted bridge's bridges, from the model: the parts not reaching the bed ([zBase]), grouped where their heights
     * overlap, one group a bridge (by index in [parts]).
     */
    fun bridgeGroups(parts: List<TriangleSoup>, zBase: Double): List<List<Int>> {
        val out = ArrayList<MutableList<Int>>(); var hi = Double.NEGATIVE_INFINITY
        for (i in parts.indices.filter { parts[it].bounds()[2] > zBase + plymeld.core.IncrementalSlicer.LIFT_PART_MM }.sortedBy { parts[it].bounds()[2] }) {
            val b = parts[i].bounds()
            if (out.isEmpty() || b[2] > hi) { out += mutableListOf(i); hi = b[5] } else { out.last() += i; hi = maxOf(hi, b[5]) }
        }
        return out
    }

    fun read(text: String, parts: List<TriangleSoup>, zBase: Double, spec: Spec, lineOffset: Int = 0, headText: String? = null): Read =
        read(text, parts, zBase, spec.fromMm, spec.limits, lineOffset, spec.follows, spec.firstMm, spec.layerMm, spec.holdMm, headText)

    /**
     * [headText]: when [text] is one object's lines of a plate printed together (PlateCheck.together: the other objects'
     * lines blanked, every layer marker kept), the whole file, so the head's rule reads the plastic EVERY object laid,
     * layer by layer (the same layers, by the same markers): a neighbor's layer stands beside this object's lower ones.
     */
    fun read(text: String, parts: List<TriangleSoup>, zBase: Double, zStartMm: Double, lim: Limits, lineOffset: Int = 0, follows: Follows = Follows.TOP,
             firstMm: Double = 0.0, layerMm: Double = 0.0, holdMm: Double? = null, headText: String? = null): Read {
        val soup = merged(parts)
        // a lifted bridge: the parts not reaching the bed are the bridge (the check's own split, from the model)
        val bridge = BooleanArray(parts.size) { follows == Follows.LIFT && parts[it].bounds()[2] > zBase + plymeld.core.IncrementalSlicer.LIFT_PART_MM }
        // several lifted bridges (a tube with an arched strip every 5 mm up): the bridge parts grouped where their
        // heights overlap, one group a bridge, each read in its own lift (the check's own grouping, from the model)
        val groups: List<List<Int>> = if (follows != Follows.LIFT) emptyList() else bridgeGroups(parts, zBase)
        val banded = groups.size > 1
        val bridgeSoup = if (follows == Follows.LIFT) merged(parts.filterIndexed { i, _ -> bridge[i] }) else soup
        val groupSoups = if (banded) groups.map { g -> merged(g.map { parts[it] }) } else listOf(bridgeSoup)
        // the surface the layers follow, and the warp that flattens it: the check's own, from the model
        val top = if (follows == Follows.TOP) TopSurface.of(soup, plymeld.core.IncrementalSlicer.UP_NZ) else TopSurface.underside(bridgeSoup, plymeld.core.IncrementalSlicer.UP_NZ)
        // the surface each frame's slope is read on (each bridge's own underside, for several)
        val slopes = if (banded) groupSoups.map { TopSurface.underside(it, plymeld.core.IncrementalSlicer.UP_NZ) } else listOf(top)
        val zTop = soup.bounds()[5]
        // the warps, one per frame: the band's, or each lifted bridge's
        val warps: List<plymeld.mesh.ZWarp> = when (follows) {
            Follows.TOP -> listOf(Warp(zBase + zStartMm, zTop, top::top))
            Follows.UNDERSIDE -> listOf(plymeld.mesh.UndersideShift(zBase, top::top))
            Follows.LIFT -> {
                require(bridge.any { it } && bridge.any { !it }) { "a lifted bridge needs a part on the bed and a part off it" }
                val supports = merged(parts.filterIndexed { i, _ -> !bridge[i] })
                groupSoups.map { plymeld.mesh.LiftWarp.of(it, supports, zBase, firstMm, layerMm, plymeld.core.IncrementalSlicer.UP_NZ, holdMm) }
            }
        }
        fun zStartOf(f: Int) = if (follows == Follows.LIFT) warps[f].zStart - zBase else zStartMm
        // a lifted bridge's lowest underside where it rests (above the bed): the floor of its tacks (below)
        val lowestUnder = DoubleArray(warps.size) { f ->
            (warps[f] as? plymeld.mesh.LiftWarp)?.let { lw ->
                val bs = groupSoups[f]
                var lo = Double.POSITIVE_INFINITY
                for (t in 0 until bs.count) for (c in 0 until 3) lo = minOf(lo, lw.zStart - lw.lift(bs.x(t, c).toDouble(), bs.y(t, c).toDouble()))
                lo - zBase
            } ?: Double.NaN
        }
        // each layer's frame: the warp it is read in, or -1, as it is (a lifted bridge's supports)
        var frameOf = IntArray(0)
        fun frame(k: Int): Int = when {
            follows != Follows.LIFT -> 0
            k < 0 -> -1
            k < frameOf.size -> frameOf[k]
            else -> frameOf.lastOrNull() ?: -1
        }
        fun wk(k: Int, x: Double, y: Double, z: Double) = frame(k).let { f -> if (f < 0) z else warps[f].warp(x, y, z + zBase) - zBase }
        fun u(k: Int, x: Double, y: Double, zw: Double) = frame(k).let { f -> if (f < 0) zw else warps[f].unwarp(x, y, zw + zBase) - zBase }
        val lines = text.lines()
        val firstLayer = lines.indexOfFirst { isLayer(it) }
        if (firstLayer < 0) return Read(text, parts, listOf("no layer markers: not a layered print"), 0)

        // pass 1: the lines printed, by layer (and, on a plate printed together, every object's, for the head's rule)
        val printed = printedLines(lines)
        val printedAll = headText?.let { printedLines(it.lines()) } ?: printed
        val layers = (printed.maxOfOrNull { it.layer } ?: -1) + 1
        val lastPrinted = printed.lastOrNull()?.idx ?: -1
        frameOf = IntArray(layers) { if (follows == Follows.LIFT) -1 else 0 }
        if (follows == Follows.LIFT && !banded) {
            // a lifted bridge's layers are those from the first with a line end clearly above where the bridge rests (by half
            // a layer: a support's printed top is exactly there, the bridge's first layer a layer above); the supports' are
            // read as they are
            val lw = warps[0] as plymeld.mesh.LiftWarp
            fun above(x: Double, y: Double, z: Double): Boolean { val l = lw.lift(x, y); return l > 0 && z + zBase >= lw.zStart - l + layerMm / 2 }
            val bridgeFrom = printed.firstOrNull { above(it.x0, it.y0, it.z0) || above(it.x1, it.y1, it.z1) }?.layer ?: Int.MAX_VALUE
            for (k in bridgeFrom until layers) frameOf[k] = 0
        }
        if (banded) {
            // several lifted bridges: a layer is bridge f's when every line end it prints is over bridge f, and, lifted by f's
            // warp, the layer stands within f's own lifted band (above where f is laid, by half a layer, up to its top). What
            // stands on f's ends is printed after f, a layer and more above its top in f's lift; what f rests on, at its start.
            val topsW = groupSoups.indices.map { f -> warps[f].warpSoup(groupSoups[f]).bounds()[5] - zBase }
            printed.groupBy { it.layer }.forEach { (k, ls) ->
                for (f in warps.indices) {
                    val lw = warps[f] as plymeld.mesh.LiftWarp
                    if (!ls.all { lw.covers(it.x0, it.y0) && lw.covers(it.x1, it.y1) }) continue
                    val zw = ls.flatMap { listOf(lw.warp(it.x0, it.y0, it.z0 + zBase), lw.warp(it.x1, it.y1, it.z1 + zBase)) }.sorted()
                    val m = zw[zw.size / 2] - zBase
                    if (m > zStartOf(f) + layerMm / 2 && m <= topsW[f] + layerMm / 2) { frameOf[k] = f; break }
                }
            }
        }
        // each layer's warped height: the middle of its lines' ends in warped space, to the µm (on a curved layer all
        // of them sit there; one off it is the surface rule's), not counting the lines laid above it on purpose (a waved
        // layer's second pass, Xs, and layer along the span: the span layer's lines follow the arch in many short
        // pieces, and their ends 0.3 mm up outnumbered the layer's own)
        val lzUm = LongArray(layers) { Long.MIN_VALUE }
        printed.groupBy { it.layer }.forEach { (k, all) ->
            val ls = all.filter { it.feature !in LAID_ABOVE }.ifEmpty { all }
            val zw = ls.flatMap { listOf(wk(k, it.x0, it.y0, it.z0), wk(k, it.x1, it.y1, it.z1)) }.sorted()
            lzUm[k] = Math.round(zw[zw.size / 2] * 1000)
        }
        fun curved(k: Int) = lzUm[k] != Long.MIN_VALUE && frame(k) >= 0 && lzUm[k] > Math.round(zStartOf(frame(k)) * 1000)
        fun lz(k: Int) = lzUm[k] / 1000.0
        /** The warped height of the layer under [k] (the bed under the first). */
        fun printedBelow(k: Int): Double = (k - 1 downTo 0).firstOrNull { lzUm[it] != Long.MIN_VALUE }?.let { lz(it) } ?: 0.0
        /**
         * The warped height a layer is laid on: the layer printed before it; for a lifted bridge's layers at least the bridge's
         * underside (its zStart), which is flat there and in the air above every support where the bridge rises above them (an
         * arch: its top is higher than either pillar, so the first layer over it lies on no layer printed before).
         * With several lifted bridges, the layer before it in its own bridge (the layers between are another frame's).
         */
        fun below(k: Int): Double {
            val f = frame(k)
            if (follows != Follows.LIFT || f < 0) return printedBelow(k)
            if (!banded) return maxOf(printedBelow(k), zStartOf(f))
            return maxOf((k - 1 downTo 0).firstOrNull { lzUm[it] != Long.MIN_VALUE && frame(it) == f }?.let { lz(it) } ?: zStartOf(f), zStartOf(f))
        }

        val problems = ArrayList<String>()
        val tip = lim.tip
        if (tip == null) problems += "curved layers need the nozzle's angle and height (hardware.tool.<key>.head.nozzle.angle and head.nozzle.height), which the config does not give: the head's clearance over a curved layer cannot be judged"
        var curvedCount = 0
        val head = tip?.reach?.takeIf { printed.isNotEmpty() }?.let { HeadGrid(printedAll, it) }
        fun headOver(at: String, w: Worst, t: Tip) {
            val r = t.reach ?: return
            if (w.overMm >= t.protrusionMm) problems += "$at: with the nozzle at (${f(w.x, 2)}, ${f(w.y, 2)}) z ${f(w.z, 3)}, the head (reaching ${f(r.minusX, 0)} / ${f(r.plusX, 0)} / ${f(r.minusY, 0)} / ${f(r.plusY, 0)} mm towards -X / +X / -Y / +Y) is over plastic ${f(w.overMm, 3)} mm higher, not under the nozzle height ${f(t.protrusionMm, 3)} mm: the head could meet it (line ${w.line.idx + 1 + lineOffset})"
        }
        val mineBy = printed.groupBy { it.layer }
        val allBy = printedAll.groupBy { it.layer }
        /**
         * Layer [k]'s own lines [ls] judged by the head's rule in the order printed (HEAD_BLOCK), the other objects' lines on the
         * layer ([others]: a plate printed together, read whole) counted from the layer's start; the layer's plastic is all in
         * the head's grid afterwards.
         */
        fun headOrdered(ls: List<Line>, others: List<Line>): Worst? {
            val hg = head ?: return null
            hg.add(others)
            var out: Worst? = null
            for (b in ls.chunked(HEAD_BLOCK)) hg.judge(b)?.let { w -> val o = out; if (o == null || w.overMm > o.overMm) out = w }
            return out
        }
        for (k in 0 until maxOf(layers, (printedAll.maxOfOrNull { it.layer } ?: -1) + 1)) {
            val all = allBy[k] ?: emptyList()
            val ls = mineBy[k]
            if (ls == null) { head?.add(all); continue }
            // the other objects' lines on this layer (a plate read whole for the head's rule): all of it but this object's own
            val others = if (printedAll === printed) emptyList() else {
                fun key(l: Line) = listOf(l.x0, l.y0, l.z0, l.x1, l.y1, l.z1)
                val mine = ls.mapTo(HashSet()) { key(it) }
                all.filter { key(it) !in mine }
            }
            if (!curved(k)) {
                // several lifted bridges: what stands on a bridge's ends is printed after it, flat and lower than the bridge's top
                if (banded && curvedCount > 0 && tip != null) headOrdered(ls, others)?.let { headOver("layer ${k + 1} (flat, after a lifted bridge)", it, tip) }
                else head?.add(all)
                continue
            }
            curvedCount++
            if (tip == null) head?.add(all)
            val at = "layer ${k + 1} (curved, warped z ${f(lz(k), 3)})"
            val zb = below(k)
            // surface: ends, then all along each line
            var offN = 0; var off: Pair<Line, Double>? = null
            var midN = 0; var mid: Pair<Line, Triple<Double, Double, Double>>? = null
            var tackN = 0; var tackWhy: String? = null
            // thickness, slope, rise
            var thin: Triple<Line, Double, Boolean>? = null; var thick: Triple<Line, Double, Boolean>? = null
            var steep: Pair<Line, Double>? = null
            var zLo = Double.POSITIVE_INFINITY; var zHi = Double.NEGATIVE_INFINITY
            // the layer surface's slope: the top's, scaled by how far up the band the layer is; the underside's, unscaled
            val slopeScale = if (follows != Follows.TOP) 1.0 else (lz(k) - zStartMm) / (zTop - zBase - zStartMm)
            for (s in ls) {
                // a bridge tack (IncrementalSlicer.tacks): below its layer on purpose, so not judged by the surface and thickness
                // rules but by its own: on a lifted bridge, over the bridge, never above its layer's surface, and never lower
                // than one layer above the bridge's lowest underside (no tack reaches below a straight first layer)
                if (s.feature == "Bridge tack") {
                    zLo = minOf(zLo, s.z0, s.z1); zHi = maxOf(zHi, s.z0, s.z1)
                    val lw = warps[frame(k)] as? plymeld.mesh.LiftWarp
                    if (lw == null) { tackN++; if (tackWhy == null) tackWhy = "a bridge tack where there is no lifted bridge (line ${s.idx + 1 + lineOffset})"; continue }
                    val n = maxOf(2, Math.ceil(Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / ALONG_MM).toInt())
                    for (i in 0..n) {
                        val t = i.toDouble() / n
                        val px = s.x0 + (s.x1 - s.x0) * t; val py = s.y0 + (s.y1 - s.y0) * t; val pz = s.z0 + (s.z1 - s.z0) * t
                        val why = when {
                            lw.lift(px, py) <= 0 -> "off the bridge"
                            pz > u(k, px, py, lz(k)) + ON_SURFACE_MM -> "above its layer's surface ${f(u(k, px, py, lz(k)), 3)}"
                            pz < lowestUnder[frame(k)] + layerMm - ON_SURFACE_MM -> "lower than one layer above the bridge's lowest underside (${f(lowestUnder[frame(k)] + layerMm, 3)})"
                            else -> null
                        }
                        if (why != null) { tackN++; if (tackWhy == null) tackWhy = "at (${f(px, 2)}, ${f(py, 2)}) z ${f(pz, 3)}: $why (line ${s.idx + 1 + lineOffset})"; break }
                    }
                    continue
                }
                // a wave bead laid one layer above its own (a second waved layer laid with the first): judged against the layer above
                // (and a waved first layer's line laid again over it, wave.second_pass.zones: judged against the surface as far up
                // as its first end lies, which must be on its layer or at most a layer above; the rest of it must follow that)
                // (its Xs, wave.x_layer, alike; its layer along the span, wave.span_layer, as far as two layers above)
                val second = s.feature == "Wave overhang, second pass" || s.feature == "Wave overhang, X layer" || s.feature == "Wave overhang, span layer"
                val most = if (s.feature == "Wave overhang, span layer") 2 * layerMm else layerMm
                val up = if (s.feature == "Wave overhang, layer above") layerMm
                    else if (second) (s.z0 - u(k, s.x0, s.y0, lz(k))).let { d ->
                        if (d < -ON_SURFACE_MM || d > most + ON_SURFACE_MM) { offN++; if (off == null || Math.abs(d) > off.second) off = s to Math.abs(d) }
                        d.coerceIn(0.0, most)
                    } else 0.0
                for (end in 0..1) {
                    val x = if (end == 0) s.x0 else s.x1; val y = if (end == 0) s.y0 else s.y1; val z = if (end == 0) s.z0 else s.z1
                    // an end on a step of the surface (the model's top meeting a face that rises from it) has the height on
                    // either side: the file's XY is rounded to µm, so the surface is read within that rounding around it
                    // (a wall's end at a strip's edge on its foot, given the foot's height, read on the strip)
                    val d = STEP_PROBE.minOf { (ox, oy) -> Math.abs(z - u(k, x + ox, y + oy, lz(k) + up)) }
                    if (d > ON_SURFACE_MM) { offN++; if (off == null || d > off.second) off = s to d }
                    zLo = minOf(zLo, z); zHi = maxOf(zHi, z)
                    // (a second pass lies on a layer already laid, whose own thickness is judged with it: none of its own)
                    val t = u(k, x, y, lz(k) + up) - u(k, x, y, if (up > 0) lz(k) else zb)
                    if (!second && t < lim.minLayerMm - SLACK_MM && (thin == null || t < thin.second)) thin = Triple(s, t, end == 0)
                    if (!second && t > lim.maxLayerMm + SLACK_MM && (thick == null || t > thick.second)) thick = Triple(s, t, end == 0)
                    // the layer's surface is the top's, scaled by how far up the band it is
                    val topSlope = slopes[frame(k)].slopeDeg(x, y)
                    if (!topSlope.isNaN()) {
                        val slope = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(topSlope)) * slopeScale))
                        if (steep == null || slope > steep.second) steep = s to slope
                    }
                }
                // the straight line between its ends against the surface, sampled all along it (every ALONG_MM), not at its
                // middle alone: a surface symmetric about the middle is on the line there while bending away at its kinks
                // (a bridge's wall ran 0.61 mm into its support's top, its middle exactly on the surface)
                val n = maxOf(2, Math.ceil(Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / ALONG_MM).toInt())
                var dm = 0.0; var dx = 0.0; var dy = 0.0
                for (i in 1 until n) {
                    val t = i.toDouble() / n
                    val px = s.x0 + (s.x1 - s.x0) * t; val py = s.y0 + (s.y1 - s.y0) * t
                    val d = Math.abs(s.z0 + (s.z1 - s.z0) * t - u(k, px, py, lz(k) + up))
                    if (d > dm) { dm = d; dx = px; dy = py }
                }
                if (dm > lim.resolutionMm + ON_SURFACE_MM) { midN++; if (mid == null || dm > mid.second.first) mid = s to Triple(dm, dx, dy) }
            }
            tackWhy?.let { problems += "$at: $tackN bridge tack line(s) out of bounds, the first $it" }
            off?.let { (s, d) -> problems += "$at: $offN line end(s) off the layer's surface, up to ${f(d, 4)} mm (line ${s.idx + 1 + lineOffset}, at ${f(s.x1, 2)}, ${f(s.y1, 2)})" }
            mid?.let { (s, d) -> problems += "$at: $midN line(s) straying off the layer's surface between their ends, up to ${f(d.first, 4)} mm at (${f(d.second, 2)}, ${f(d.third, 2)}), over the slicing resolution ${f(lim.resolutionMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            thin?.let { (s, t, start) -> problems += "$at: ${f(t, 3)} mm thick at (${f(if (start) s.x0 else s.x1, 2)}, ${f(if (start) s.y0 else s.y1, 2)}), under the tool's thinnest layer ${f(lim.minLayerMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            thick?.let { (s, t, start) -> problems += "$at: ${f(t, 3)} mm thick at (${f(if (start) s.x0 else s.x1, 2)}, ${f(if (start) s.y0 else s.y1, 2)}), over the tool's thickest layer ${f(lim.maxLayerMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            if (tip != null) {
                steep?.let { (s, a) -> if (a > tip.coneDeg + SLACK_DEG) problems += "$at: the surface printed is ${f(a, 1)} deg steep near (${f(s.x1, 2)}, ${f(s.y1, 2)}), over the nozzle angle (${f(tip.coneDeg, 1)} deg from horizontal): the nozzle's cone would meet it (line ${s.idx + 1 + lineOffset})" }
                if (head == null) {
                    if (zHi - zLo >= tip.protrusionMm) problems += "$at: rises ${f(zHi - zLo, 3)} mm (${f(zLo, 3)} to ${f(zHi, 3)}), not under the nozzle height ${f(tip.protrusionMm, 3)} mm: the head around the nozzle could meet this layer"
                } else headOrdered(ls, others)?.let { headOver(at, it, tip) }
            }
        }

        // pass 2: every move's Z in warped space (a curved layer's lines at exactly its height when on its surface)
        val out = StringBuilder(text.length + text.length / 8)
        run {
            var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var zwNow = Double.NaN; var layer = -1
            for ((i, raw) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                val l = raw.trim()
                if (isLayer(l)) { layer++; out.append(raw); continue }
                if (layer >= 0 && layer < layers && curved(layer)) {
                    if (l.startsWith(";Z:") || l.startsWith("; Z_HEIGHT:")) { out.append(l.substringBefore(':')).append(": ").append(f(lz(layer), 3)); continue }
                    if (l.startsWith(";HEIGHT:") || l.startsWith("; LAYER_HEIGHT:")) { out.append(l.substringBefore(':')).append(": ").append(f(lz(layer) - below(layer), 3)); continue }
                }
                val m = move(l)
                // the start and end templates' own lines are left as written (their moves are above or before the print;
                // GcodeCheck matches them line for line)
                if (m == null || i < firstLayer || i > lastPrinted) {
                    out.append(raw)
                    if (m != null) { x = m.x ?: x; y = m.y ?: y; z = m.z ?: z; if (!x.isNaN() && !z.isNaN()) zwNow = wk(layer, x, y, z) }
                    continue
                }
                val nx = m.x ?: x; val ny = m.y ?: y; val nz = m.z ?: z
                var zw = if (nx.isNaN() || nz.isNaN()) nz else wk(layer, nx, ny, nz)
                // on the surface within the file's XY rounding (STEP_PROBE): a point on a step has the height on either side
                if (layer >= 0 && layer < layers && curved(layer) && !nx.isNaN() &&
                    STEP_PROBE.any { (ox, oy) -> Math.abs(nz - u(layer, nx + ox, ny + oy, lz(layer))) <= ON_SURFACE_MM }) zw = lz(layer)
                // a travel above the layer's surface where it is (really, not warped) is above the layer: the warp is not monotonic
                // far above its band, and a head lifted clear of a finished group of bridges (plan.bridge_order = hot_to_cold)
                // read 2.4 mm under the layer it then printed
                else if (layer >= 0 && layer < layers && curved(layer) && !nx.isNaN() && !nz.isNaN() && (m.e == null || m.e <= 0.0) &&
                    nz >= u(layer, nx, ny, lz(layer)) - 1e-9) zw = maxOf(zw, lz(layer))
                if (m.z != null || (!zw.isNaN() && (zwNow.isNaN() || Math.abs(zw - zwNow) > 1e-9))) out.append(withZ(raw, zw)) else out.append(raw)
                x = nx; y = ny; z = nz; zwNow = zw
            }
        }
        val groupOf = IntArray(parts.size) { i -> if (banded) groups.indexOfFirst { i in it } else 0 }
        val warpedParts = parts.mapIndexed { i, p -> if (follows == Follows.LIFT && !bridge[i]) p else warps[groupOf[i]].warpSoup(p) }
        // several lifted bridges: each frame's layers on their own, with the start template, and its parts
        val frames = if (!banded) null else {
            val ol = out.toString().lines()
            val marks = ol.indices.filter { isLayer(ol[it].trim()) }
            (-1 until warps.size).map { fr ->
                val sb = StringBuilder()
                for (i in 0 until marks.first()) sb.append(ol[i]).append('\n')
                for ((j, m) in marks.withIndex()) if (frame(j) == fr) for (i in m until (if (j + 1 < marks.size) marks[j + 1] else ol.size)) sb.append(ol[i]).append('\n')
                val idx = if (fr < 0) parts.indices.filter { !bridge[it] } else groups[fr]
                Frame(if (fr < 0) "supports" else "bridge ${fr + 1}", sb.toString(), idx, idx.map { warpedParts[it] }, flat = fr < 0)
            }
        }
        return Read(out.toString(), warpedParts, problems, curvedCount, frames)
    }

    /**
     * Curved islands: a lone object's [text] whose layers marked
     * `; CURVED_ISLAND: <key>` (plymeld.program.LayerStart.ISLAND_MARK) are an island's own, read in that island's warp: the
     * check's own, from the model ([islands]: found on [parts] by the plan's locks, placed with them) and the island's start
     * as printed (the top of the highest flat layer at or under the plan's start, as the file has it). Its rules on each
     * island layer, as the band's: every line end in the island's area; on the layer's surface (ends and all along); its
     * thickness over the island's layer under it (the start under the first) within the tool's thinnest and thickest; the
     * surface no steeper than the nozzle angle (read on the warp itself); and the head clear of every line printed before
     * (HeadGrid, every layer from the first island layer on, flat ones included: a flat layer elsewhere stands beside
     * an island's higher plastic), or the layer's rise under the nozzle height without the head's outline.
     * The planar checks then read [Read.text] (the island layers flat at their warped heights) and [Read.frames]: the flat
     * layers against the parts without the islands' loops above their starts, each island's layers against its warped
     * parts' loops of that island only.
     */
    fun readIslands(text: String, parts: List<TriangleSoup>, zBase: Double, islands: List<plymeld.core.decide.CurvedIsland>, lim: Limits, lineOffset: Int = 0): Read {
        val bottom = merged(parts).bounds()[2]
        val lines = text.lines()
        val firstLayer = lines.indexOfFirst { isLayer(it.trim()) }
        if (firstLayer < 0) return Read(text, parts, listOf("no layer markers: not a layered print"), 0)
        val problems = ArrayList<String>()
        val keyOf = islands.withIndex().associate { (i, isl) -> isl.key.ifEmpty { "island ${i + 1}" } to i }
        // each layer's island (by its mark; -1 flat) and its height line
        val frameL = ArrayList<Int>(); val markZ = ArrayList<Double>()
        val mark = plymeld.program.LayerStart.ISLAND_MARK.trim()
        for (raw in lines) {
            val l = raw.trim()
            if (isLayer(l)) { frameL += -1; markZ += Double.NaN; continue }
            if (frameL.isEmpty()) continue
            val k = frameL.size - 1
            if (l.startsWith(mark)) {
                val key = l.removePrefix(mark).trim()
                frameL[k] = keyOf[key] ?: -1
                if (key !in keyOf) problems += "layer ${k + 1} is marked as curved island '$key', which the plan has none of (${keyOf.keys.joinToString { "'$it'" }})"
            }
            if (l.startsWith(";Z:") || l.startsWith("; Z_HEIGHT:")) markZ[k] = l.substringAfter(':').trim().toDoubleOrNull() ?: Double.NaN
        }
        val layers = frameL.size
        fun frame(k: Int) = if (k in 0 until layers) frameL[k] else -1
        // each island's start as printed: the top of the highest flat layer at or under the plan's start (the file's heights)
        val flatTops = (0 until layers).filter { frameL[it] < 0 && !markZ[it].isNaN() }.map { markZ[it] }
        val startG = DoubleArray(islands.size) { f ->
            val want = bottom - zBase + islands[f].startMm
            flatTops.filter { it <= want + 1e-6 }.maxOrNull() ?: run { problems += "curved island '${islands[f].key}': no flat layer at or under its start (${f(want, 3)})"; want }
        }
        val warps = islands.mapIndexed { f, isl -> plymeld.mesh.ShiftWarp(zBase + startG[f], bottom + isl.topMm, isl.cellMm, isl.window()) { x, y -> isl.dipAt(x, y) } }
        fun wk(k: Int, x: Double, y: Double, z: Double) = frame(k).let { f -> if (f < 0) z else warps[f].warp(x, y, z + zBase) - zBase }
        fun u(k: Int, x: Double, y: Double, zw: Double) = frame(k).let { f -> if (f < 0) zw else warps[f].unwarp(x, y, zw + zBase) - zBase }

        val printed = printedLines(lines)
        val lastPrinted = printed.lastOrNull()?.idx ?: -1
        val byLayer = printed.groupBy { it.layer }
        // each island layer's warped height: the middle of its line ends' warped heights, to the µm
        val lzUm = LongArray(layers) { Long.MIN_VALUE }
        for ((k, ls) in byLayer) if (frame(k) >= 0) {
            val zw = ls.flatMap { listOf(wk(k, it.x0, it.y0, it.z0), wk(k, it.x1, it.y1, it.z1)) }.sorted()
            lzUm[k] = Math.round(zw[zw.size / 2] * 1000)
        }
        fun curved(k: Int) = frame(k) >= 0 && lzUm[k] != Long.MIN_VALUE
        fun lz(k: Int) = lzUm[k] / 1000.0
        /** The warped height an island layer is laid on: its island's layer before it, else the island's start. */
        fun below(k: Int): Double = (k - 1 downTo 0).firstOrNull { frame(it) == frame(k) && curved(it) }?.let { lz(it) } ?: startG[frame(k)]

        val tip = lim.tip
        if (tip == null) problems += "curved islands need the nozzle's angle and height (hardware.tool.<key>.head.nozzle.angle and head.nozzle.height), which the config does not give: the head's clearance over an island's layer cannot be judged"
        val head = tip?.reach?.takeIf { printed.isNotEmpty() }?.let { HeadGrid(printed, it) }
        fun headOver(at: String, w: Worst, t: Tip) {
            val r = t.reach ?: return
            if (w.overMm >= t.protrusionMm) problems += "$at: with the nozzle at (${f(w.x, 2)}, ${f(w.y, 2)}) z ${f(w.z, 3)}, the head (reaching ${f(r.minusX, 0)} / ${f(r.plusX, 0)} / ${f(r.minusY, 0)} / ${f(r.plusY, 0)} mm towards -X / +X / -Y / +Y) is over plastic ${f(w.overMm, 3)} mm higher, not under the nozzle height ${f(t.protrusionMm, 3)} mm: the head could meet it (line ${w.line.idx + 1 + lineOffset})"
        }
        fun headOrdered(ls: List<Line>): Worst? {
            val hg = head ?: return null
            var out: Worst? = null
            for (b in ls.chunked(HEAD_BLOCK)) hg.judge(b)?.let { w -> val o = out; if (o == null || w.overMm > o.overMm) out = w }
            return out
        }
        var curvedCount = 0
        for (k in 0 until layers) {
            val ls = byLayer[k] ?: continue
            if (!curved(k)) {
                // a flat layer printed after an island's layer stands beside its higher plastic: the head's rule reads it too
                if (curvedCount > 0 && tip != null && head != null) headOrdered(ls)?.let { headOver("layer ${k + 1} (flat, after a curved island's layer)", it, tip) }
                else head?.add(ls)
                continue
            }
            curvedCount++
            val fr = frame(k); val isl = islands[fr]
            val at = "layer ${k + 1} (curved island '${isl.key}', warped z ${f(lz(k), 3)})"
            val zb = below(k)
            var outN = 0; var outAt: Line? = null
            var offN = 0; var off: Pair<Line, Double>? = null
            var midN = 0; var mid: Pair<Line, Triple<Double, Double, Double>>? = null
            var thin: Triple<Line, Double, Boolean>? = null; var thick: Triple<Line, Double, Boolean>? = null
            var steep: Pair<Line, Double>? = null
            var zLo = Double.POSITIVE_INFINITY; var zHi = Double.NEGATIVE_INFINITY
            for (s in ls) {
                for (end in 0..1) {
                    val x = if (end == 0) s.x0 else s.x1; val y = if (end == 0) s.y0 else s.y1; val z = if (end == 0) s.z0 else s.z1
                    if (!isl.covers(x, y)) { outN++; if (outAt == null) outAt = s }
                    val d = STEP_PROBE.minOf { (ox, oy) -> Math.abs(z - u(k, x + ox, y + oy, lz(k))) }
                    if (d > ON_SURFACE_MM) { offN++; if (off == null || d > off.second) off = s to d }
                    zLo = minOf(zLo, z); zHi = maxOf(zHi, z)
                    val t = u(k, x, y, lz(k)) - u(k, x, y, zb)
                    if (t < lim.minLayerMm - SLACK_MM && (thin == null || t < thin.second)) thin = Triple(s, t, end == 0)
                    if (t > lim.maxLayerMm + SLACK_MM && (thick == null || t > thick.second)) thick = Triple(s, t, end == 0)
                    // the layer's surface's slope, read on the warp itself (central differences over SLOPE_STEP_MM)
                    val h = SLOPE_STEP_MM
                    val gx = (u(k, x + h, y, lz(k)) - u(k, x - h, y, lz(k))) / (2 * h); val gy = (u(k, x, y + h, lz(k)) - u(k, x, y - h, lz(k))) / (2 * h)
                    val slope = Math.toDegrees(Math.atan(Math.hypot(gx, gy)))
                    if (steep == null || slope > steep.second) steep = s to slope
                }
                val n = maxOf(2, Math.ceil(Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / minOf(ALONG_MM, warps[fr].featureMm / 4)).toInt())
                var dm = 0.0; var dx = 0.0; var dy = 0.0
                for (i in 1 until n) {
                    val t = i.toDouble() / n
                    val px = s.x0 + (s.x1 - s.x0) * t; val py = s.y0 + (s.y1 - s.y0) * t
                    val d = Math.abs(s.z0 + (s.z1 - s.z0) * t - u(k, px, py, lz(k)))
                    if (d > dm) { dm = d; dx = px; dy = py }
                }
                if (dm > lim.resolutionMm + ON_SURFACE_MM) { midN++; if (mid == null || dm > mid.second.first) mid = s to Triple(dm, dx, dy) }
            }
            outAt?.let { s -> problems += "$at: $outN line end(s) outside the island's area (line ${s.idx + 1 + lineOffset}, at ${f(s.x1, 2)}, ${f(s.y1, 2)})" }
            off?.let { (s, d) -> problems += "$at: $offN line end(s) off the layer's surface, up to ${f(d, 4)} mm (line ${s.idx + 1 + lineOffset}, at ${f(s.x1, 2)}, ${f(s.y1, 2)})" }
            mid?.let { (s, d) -> problems += "$at: $midN line(s) straying off the layer's surface between their ends, up to ${f(d.first, 4)} mm at (${f(d.second, 2)}, ${f(d.third, 2)}), over the slicing resolution ${f(lim.resolutionMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            thin?.let { (s, t, start) -> problems += "$at: ${f(t, 3)} mm thick at (${f(if (start) s.x0 else s.x1, 2)}, ${f(if (start) s.y0 else s.y1, 2)}), under the tool's thinnest layer ${f(lim.minLayerMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            thick?.let { (s, t, start) -> problems += "$at: ${f(t, 3)} mm thick at (${f(if (start) s.x0 else s.x1, 2)}, ${f(if (start) s.y0 else s.y1, 2)}), over the tool's thickest layer ${f(lim.maxLayerMm, 3)} (line ${s.idx + 1 + lineOffset})" }
            if (tip != null) {
                steep?.let { (s, a) -> if (a > tip.coneDeg + SLACK_DEG) problems += "$at: the surface printed is ${f(a, 1)} deg steep near (${f(s.x1, 2)}, ${f(s.y1, 2)}), over the nozzle angle (${f(tip.coneDeg, 1)} deg from horizontal): the nozzle's cone would meet it (line ${s.idx + 1 + lineOffset})" }
                if (head == null) {
                    if (zHi - zLo >= tip.protrusionMm) problems += "$at: rises ${f(zHi - zLo, 3)} mm (${f(zLo, 3)} to ${f(zHi, 3)}), not under the nozzle height ${f(tip.protrusionMm, 3)} mm: the head around the nozzle could meet this layer"
                } else headOrdered(ls)?.let { headOver(at, it, tip) }
            } else head?.add(ls)
        }

        problems += islandWalls(parts, zBase, islands, startG, frameL, byLayer, ::curved, ::lz, { k -> (lz(k) + below(k)) / 2 }, ::u, lineOffset)

        // every move's Z in warped space (an island layer's lines at exactly its height when on its surface); flat layers as written
        val out = StringBuilder(text.length + text.length / 8)
        run {
            var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var zwNow = Double.NaN; var layer = -1
            for ((i, raw) in lines.withIndex()) {
                if (i > 0) out.append('\n')
                val l = raw.trim()
                if (isLayer(l)) { layer++; out.append(raw); continue }
                if (layer >= 0 && curved(layer)) {
                    if (l.startsWith(";Z:") || l.startsWith("; Z_HEIGHT:")) { out.append(l.substringBefore(':')).append(": ").append(f(lz(layer), 3)); continue }
                    if (l.startsWith(";HEIGHT:") || l.startsWith("; LAYER_HEIGHT:")) { out.append(l.substringBefore(':')).append(": ").append(f(lz(layer) - below(layer), 3)); continue }
                }
                val m = move(l)
                if (m == null || i < firstLayer || i > lastPrinted) {
                    out.append(raw)
                    if (m != null) { x = m.x ?: x; y = m.y ?: y; z = m.z ?: z; if (!x.isNaN() && !z.isNaN()) zwNow = wk(layer, x, y, z) }
                    continue
                }
                val nx = m.x ?: x; val ny = m.y ?: y; val nz = m.z ?: z
                var zw = if (nx.isNaN() || nz.isNaN()) nz else wk(layer, nx, ny, nz)
                if (layer >= 0 && curved(layer) && !nx.isNaN() &&
                    STEP_PROBE.any { (ox, oy) -> Math.abs(nz - u(layer, nx + ox, ny + oy, lz(layer))) <= ON_SURFACE_MM }) zw = lz(layer)
                // a travel above the layer's surface where it is (really) is above the layer: the warp is not monotonic far above its band
                else if (layer >= 0 && curved(layer) && !nx.isNaN() && !nz.isNaN() && (m.e == null || m.e <= 0.0) &&
                    nz >= u(layer, nx, ny, lz(layer)) - 1e-9) zw = maxOf(zw, lz(layer))
                if (m.z != null || (!zw.isNaN() && (zwNow.isNaN() || Math.abs(zw - zwNow) > 1e-9))) out.append(withZ(raw, zw)) else out.append(raw)
                x = nx; y = ny; z = nz; zwNow = zw
            }
        }
        // the frames: the flat layers against the parts without each island's loops above its start (up to its top, for one holding
        // a taller part); each island's layers
        // against its warped parts' loops of that island only
        val ol = out.toString().lines()
        val marks = ol.indices.filter { isLayer(ol[it].trim()) }
        fun frameText(fr: Int): String {
            val sb = StringBuilder()
            for (i in 0 until marks.first()) sb.append(ol[i]).append('\n')
            for ((j, m) in marks.withIndex()) if (frame(j) == fr) for (i in m until (if (j + 1 < marks.size) marks[j + 1] else ol.size)) sb.append(ol[i]).append('\n')
            return sb.toString()
        }
        // an island holding a taller part ends at its top (CurvedIsland.shared): above it, that part is the flat layers' again
        val meshBottom = parts.minOf { it.bounds()[2] }
        val topMesh = DoubleArray(islands.size) { f -> if (islands[f].shared) meshBottom + islands[f].topMm else Double.POSITIVE_INFINITY }
        val flatClip: (Double, List<plymeld.geom.Polygon>) -> List<plymeld.geom.Polygon> = { zMesh, loops ->
            loops.filter { lp -> islands.indices.none { f -> zMesh > zBase + startG[f] + 1e-9 && zMesh < topMesh[f] && islands[f].holds(lp) } }
        }
        val frames = listOf(Frame("flat layers", frameText(-1), parts.indices.toList(), parts, flat = true, clip = flatClip)) +
            islands.indices.map { fr ->
                val isl = islands[fr]
                Frame("island '${isl.key}'", frameText(fr), parts.indices.toList(), parts.map { warps[fr].warpSoup(it) }, flat = false,
                    clip = { _, loops -> loops.filter { isl.holds(it) } })
            }
        return Read(out.toString(), parts, problems, curvedCount, frames)
    }
    /**
     * The islands' walls against the REAL model (no warped mesh: the model's frames read a warped model, and a warped mesh
     * made wrongly, the same way for the slicer and the check, passed them; a notch 2 mm deep in the Benchy's bow
     * wall): the model cut flat every [WALL_STEP_MM] above each island's start, its loops the island holds (those the flat
     * layers leave to it), and along their outline every [WALL_ALONG_MM] where the model's side is steep (the outline moves
     * under [WALL_STEEP_MM] over the step: a top's contour is a top's, laid as one), a wall of the island's layer whose real
     * thickness there spans that height and whose middle there lies on that cut (a layer is cut at its middle), within half a
     * line and [WALL_SLACK_MM]. More than [WALL_MISS_MM] of outline unwalled
     * on a cut is a problem.
     */
    private fun islandWalls(parts: List<TriangleSoup>, zBase: Double, islands: List<plymeld.core.decide.CurvedIsland>, startG: DoubleArray,
                            frameL: List<Int>, byLayer: Map<Int, List<Line>>, curved: (Int) -> Boolean, lz: (Int) -> Double,
                            mid: (Int) -> Double, u: (Int, Double, Double, Double) -> Double, lineOffset: Int): List<String> {
        val out = ArrayList<String>()
        val soup = merged(parts)
        for ((f, isl) in islands.withIndex()) {
            val layers = frameL.indices.filter { frameL[it] == f && curved(it) }
            if (layers.isEmpty()) continue
            val walls = layers.associateWith { k -> ModelCheck.Grid(byLayer[k].orEmpty().filter { "wall" in it.feature.lowercase() }.map { doubleArrayOf(it.x0, it.y0, it.x1, it.y1) }) }
            val zTopG = soup.bounds()[2] - zBase + isl.topMm
            val zs = generateSequence(startG[f] + WALL_STEP_MM / 2) { it + WALL_STEP_MM }.takeWhile { it < zTopG }.toList()
            if (zs.isEmpty()) continue
            // the object as its parts' union, each part cut on its own (as ModelCheck cuts it): parts overlapping (a post through a
            // column) have no side inside each other (the merged soup's cut read a post's face inside the column as a
            // side with no wall)
            fun cut(at: DoubleArray): List<List<plymeld.geom.Polygon>> {
                val per = parts.map { plymeld.mesh.Slicer.slice(it, at) }
                return at.indices.map { j -> per.flatMap { it[j].loops }.let { if (parts.size > 1) plymeld.geom.Booleans.unionPositive(it) else it } }
            }
            val cuts = cut(DoubleArray(zs.size) { zs[it] + zBase })
            val above = cut(DoubleArray(zs.size) { zs[it] + zBase + WALL_STEP_MM / 2 })
            for ((j, z) in zs.withIndex()) {
                val held = cuts[j].filter { isl.holds(it) }
                if (held.isEmpty()) continue
                val next = ModelCheck.Grid(above[j].flatMap { lp -> (0 until lp.size).map { i -> doubleArrayOf(lp.x(i) / 1000.0, lp.y(i) / 1000.0, lp.x((i + 1) % lp.size) / 1000.0, lp.y((i + 1) % lp.size) / 1000.0) } })
                var missMm = 0.0; var at: DoubleArray? = null; var layerAt = -1
                for (lp in held) for (i in 0 until lp.size) {
                    val ax = lp.x(i) / 1000.0; val ay = lp.y(i) / 1000.0; val bx = lp.x((i + 1) % lp.size) / 1000.0; val by = lp.y((i + 1) % lp.size) / 1000.0
                    val len = Math.hypot(bx - ax, by - ay); val n = maxOf(1, Math.ceil(len / WALL_ALONG_MM).toInt())
                    for (s in 0 until n) {
                        val px = ax + (bx - ax) * (s + 0.5) / n; val py = ay + (by - ay) * (s + 0.5) / n
                        if (next.nearest(px, py, WALL_STEEP_MM * 2) > WALL_STEEP_MM) continue        // a shallow side: a top's contour
                        // the island layer whose real thickness at (px, py) spans z: the first whose surface there is at or above it
                        val k = layers.firstOrNull { u(it, px, py, lz(it)) >= z } ?: continue          // above the island's top there
                        // a layer is cut at its middle (as ModelCheck reads a flat one): judged on the flat cut through that middle
                        // there only, so a feature shorter than a layer, between two middles, is no layer's (a 0.3 mm step of the
                        // Benchy's hull below its deck's top, which the flat layers missed too where their middles fell)
                        if (Math.abs(u(k, px, py, mid(k)) - z) > WALL_STEP_MM / 2 + 1e-9) continue
                        if (walls.getValue(k).nearest(px, py, 2.0) <= LINE_HALF_MM + WALL_SLACK_MM) continue
                        missMm += len / n
                        if (at == null) { at = doubleArrayOf(px, py); layerAt = k }
                    }
                }
                if (missMm > WALL_MISS_MM) out += "curved island '${isl.key}': the model's side at z ${f(z, 2)} (cut flat) has no wall of the island's layers along " +
                    "${f(missMm, 1)} mm of it, the first at (${f(at!![0], 2)}, ${f(at[1], 2)}), in layer ${layerAt + 1} (whose thickness spans that height there): the model's material there is printed by no layer"
            }
        }
        return out
    }
    const val WALL_STEP_MM = 0.1
    const val WALL_ALONG_MM = 0.25
    /** Steep: the outline moves at most this far over half a step (0.05 mm), so the side is steeper than 45 deg. */
    const val WALL_STEEP_MM = 0.05
    /** Half a line (a 0.4 mm nozzle's) and the slack a wall's place has within its layer's thickness. plymeld's choice. */
    const val LINE_HALF_MM = 0.25
    const val WALL_SLACK_MM = 0.15
    /** As ModelCheck's "no wall along" rule takes it: more than this unwalled on a cut. */
    const val WALL_MISS_MM = 0.5

    /** The step the island check reads its layer surface's slope over (mm): finer than the island's grid (0.25 mm). plymeld's choice. */
    const val SLOPE_STEP_MM = 0.05

    /** The lines printed in [lines], by layer (counted from its layer markers). */
    private fun printedLines(lines: List<String>): List<Line> {
        val printed = ArrayList<Line>()
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var layer = -1; var feat = ""
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (isLayer(l)) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feat = l.substringAfter(':').trim(); continue }
            val m = move(l) ?: continue
            val nx = m.x ?: x; val ny = m.y ?: y; val nz = m.z ?: z
            if (layer >= 0 && (m.e ?: 0.0) > 0 && !x.isNaN() && (nx != x || ny != y)) printed += Line(i, layer, x, y, z, nx, ny, nz, feat)
            x = nx; y = ny; z = nz
        }
        return printed
    }

    private class Move(val x: Double?, val y: Double?, val z: Double?, val e: Double?)
    private fun move(l: String): Move? {
        val c = l.substringBefore(';').trim()
        if (!(c.startsWith("G0 ") || c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 "))) return null
        val w = c.split(Regex("\\s+"))
        fun num(ch: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
        return Move(num('X'), num('Y'), num('Z'), num('E'))
    }
    /** [raw] with its Z word set to [z] (added after the command's other words when it has none), its comment kept. */
    private fun withZ(raw: String, z: Double): String {
        val cmd = raw.substringBefore(';').trim(); val comment = raw.substring(raw.substringBefore(';').length)
        val w = cmd.split(Regex("\\s+")).toMutableList()
        val zWord = "Z" + f(z, 4)
        val k = w.indexOfFirst { it.startsWith("Z") }
        if (k >= 0) w[k] = zWord else w.add(zWord)
        return w.joinToString(" ") + (if (comment.isEmpty()) "" else " $comment")
    }
    private fun merged(parts: List<TriangleSoup>): TriangleSoup = if (parts.size == 1) parts[0] else {
        val c = FloatArray(parts.sumOf { it.count } * 9); var at = 0
        for (p in parts) { System.arraycopy(p.coords, 0, c, at, p.count * 9); at += p.count * 9 }
        TriangleSoup(c, parts.sumOf { it.count })
    }
    private fun isLayer(l: String) = l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")
    private fun f(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v)
}
