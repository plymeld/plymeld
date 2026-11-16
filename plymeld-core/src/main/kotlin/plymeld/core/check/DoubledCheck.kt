package plymeld.core.check

import java.util.Locale

/**
 * The pre-print check's rule for plastic laid twice (ModelCheck): a spot of a layer where two lines lie over each other
 * further than the planner overlaps neighboring lines on purpose, wider than the narrowest place a line is laid in. The
 * hole rule ([HoleCheck]) finds plastic missing inside the walls; nothing found plastic laid where plastic of the same layer
 * already lies (a wall's center lines laid across a layer's infill, 63 mm of lines up to 0.9 mm wide on one layer, passed).
 *
 * Read on a grid of [CELL_MM] cells: each line of the model a bead of its width from its plastic (ModelCheck.Seg.bead) round
 * its center line, narrowed a side by the overlaps the planner makes on purpose (below); a cell is laid twice where the
 * narrowed beads of two runs cover it. A run is what the nozzle lays without stopping: consecutive extruding moves of one
 * tool, each starting where the last ended (a travel, a retraction, a tool change or a layer change ends it; a change of
 * feature or width does not). The doubled cells in spots (cells touching, diagonals too); a spot's width is twice the
 * farthest its cells lie from a cell not laid twice. A spot wider than the floor ([read]) counts: it is measured and
 * reported (the slice's "laid twice" line, [Summary]), and refused only when [REFUSES] says so.
 *
 * The overlaps made on purpose, each from the settings or the plan, never a constant of the check:
 *  - neighboring lines: two beads side by side touch with their rounded flanks overlapping, t (1 - pi/4) on a layer t
 *    thick (the bead model the planner spaces lines by, Walls.spacing; on a curved layer the thickness where the line
 *    lies): every bead narrowed by half that a side;
 *  - the slicing tolerance (method.slicing.tolerance) a side: the outline the lines are planned from is known no closer;
 *  - a line's end reaches past bead contact into the walls by plan.layer.infill.wall_overlap of its width (an interior
 *    line's: infill, a surface, a bridge, gap infill; a thin area's center line ending against a wall loop's turn, Walls:
 *    where two lines become one): an open run (one not ending where it starts) is read without that share of its width at
 *    each end;
 *  - fuzzy skin (plan.technique.fuzzy_skin.thickness, where it is on): a wall line's points move in and out by up to its
 *    thickness, so a wall line is narrowed by it a side;
 *  - one run over itself is not judged: a line's round ends and the inside of its turns, a scarf seam's ramp laid again
 *    at the loop's end (Scarf), a tip's run-in out and back, a loop's end meeting its start, a center line folding back;
 *  - ironing is laid over a top on purpose (a share of a line's plastic, over what is already there): not read;
 *  - a wave bead (laid in its own layer, or carried into the one above) is judged by the over-air rule: not read;
 *  - a sparse infill's families cross each other on a layer (grid, cubic): two sparse infill lines over each other are
 *    not judged. A sparse line over anything else is.
 * What is left counts only over a spot wider than the narrowest place a line is laid in (the hole rule's floor,
 * plan.layer.walls.feature.min and the slicing tolerance a side) and two cells: as much plastic again as a line there. A
 * sliver along a line's side is not, and the planner lays some on purpose: a thin area's center line between walls
 * overlaps them by up to wall_overlap of the narrowest line a side (Walls), and an interior line's runs linking its lines
 * along the region's edge lie wall_overlap of its width past bead contact along the walls (Planner). Narrowing the
 * lines' sides by those too would leave a whole interior line laid over another narrower than the floor.
 * Limits: a run laid twice over itself, and two sparse infill lines laid over each other, pass.
 *
 * Memory, as the hole rule's (each layer is a task of its own, on every core at once): the layer is read in windows of
 * [WIN] x [WIN] cells, one at a time, each with an int and a byte a cell (the run that last laid it, the feature that
 * first did), so a layer's reading is a fixed few hundred KB however large it is; only the doubled cells are kept for the
 * whole layer, in primitive arrays, at most [MAX_CELLS] (past that the layer's reading stops, and says so:
 * [Reading.capped]; its spots and area are then at least what was read, not all of it).
 */
object DoubledCheck {
    /**
     * Whether a spot laid twice is a problem the pre-print check refuses the file on (true), or only measured and reported
     * (false): the slice's "laid twice" line ([Summary.words]), the layer's picture beside the check's others, the test
     * catalog's baseline. It reports: the planner itself still lays plastic twice in places on ordinary models (two fills
     * sharing an edge, an outline's teeth laid as loops of their own over the main loop, wall junctions in narrow webs, a
     * concentric solid ring on an inner wall, among others: DECISIONS 48), and a slicer that refuses a plain model is no
     * use. Flip it to true when the test catalog, the regression cases (tools/regress.sh) and the slow suite's models all
     * read zero spots; DoubledCheckTest holds the setting's effect either way. Not a user setting.
     */
    val REFUSES: Boolean = false

    /** The grid's cell (mm): the hole rule's ([HoleCheck.CELL_MM]). */
    const val CELL_MM = HoleCheck.CELL_MM
    /** A window's side (cells): 6.4 mm. plymeld's choice (memory a layer; a line's capsule touches a few windows). */
    private const val WIN = 256
    /**
     * The most doubled cells kept for one layer (with their lookup, about 40 bytes each, 4 MB): 62.5 mm2 laid twice, far
     * past a spot that counts. plymeld's choice (memory, a layer on every core at once: at a million cells the curved
     * Benchy's check ran out of a 512 MB heap; a layer with more laid twice is read to the first of them, and marked so).
     */
    const val MAX_CELLS = 100_000

    /** A spot laid twice: its width (mm, to about a cell), its widest point, its area (mm2), and the features of the two lines. */
    class Spot(val widthMm: Double, val x: Double, val y: Double, val areaMm2: Double, val first: String, val second: String) {
        /** As the check says it: "a line of B laid over a line of A". */
        fun lines(): String = "a line of ${second.lowercase()} laid over a line of ${first.lowercase()}"
    }

    /** One layer's reading: its spots, widest first; [capped]: the reading stopped at [MAX_CELLS] doubled cells (more lie twice than read). */
    class Reading(val spots: List<Spot>, val capped: Boolean) {
        companion object { val NONE = Reading(emptyList(), false) }
    }

    /** A layer with a spot laid twice, or one whose reading was capped: its height, [where] (a plate's object, else empty) and its [reading]. */
    class Layer(val z: Double, val where: String, val reading: Reading)

    /**
     * What the rule found over a print: each [layers] with a spot laid twice (or a capped reading), in the check's order.
     * The measure the slice reports ([words]) and the test catalog records: how many spots, their area, how many layers
     * hold one, and the widest.
     */
    class Summary(val layers: List<Layer>) {
        val spots: Int get() = layers.sumOf { it.reading.spots.size }
        val areaMm2: Double get() = layers.sumOf { l -> l.reading.spots.sumOf { it.areaMm2 } }
        /** The layers holding a spot. */
        val spotLayers: Int get() = layers.count { it.reading.spots.isNotEmpty() }
        /** The layers whose reading stopped at [MAX_CELLS]: there more lies twice than is counted. */
        val cappedLayers: Int get() = layers.count { it.reading.capped }
        /** The widest spot (the larger area on a tie) and its layer, or null when none. */
        val widest: Pair<Layer, Spot>? get() = layers.flatMap { l -> l.reading.spots.map { l to it } }
            .maxWithOrNull(compareBy<Pair<Layer, Spot>> { it.second.widthMm }.thenBy { it.second.areaMm2 })
        /** This print's layers, then [o]'s (a plate's objects, read one after another). */
        operator fun plus(o: Summary) = Summary(layers + o.layers)
        /** Its layers said as [where]'s (an object of a plate, a frame). */
        fun at(where: String) = Summary(layers.map { Layer(it.z, if (it.where.isEmpty()) where else "$where ${it.where}", it.reading) })

        /** The slice's report line, after "laid twice: ". */
        fun words(): String {
            val cut = if (cappedLayers == 0) "" else String.format(Locale.ROOT,
                "; on %d layer(s) the reading stopped at %,d cells laid twice, so more lies twice there than these count", cappedLayers, MAX_CELLS)
            val w = widest ?: return "no spot of a layer where two lines lie over each other further than neighboring lines overlap by plan$cut"
            val (l, s) = w
            return String.format(Locale.ROOT, "%d spot(s), %.3f mm² in all, on %d layer(s); the widest %.2f mm wide (%.3f mm², %sat z %.3f, %.2f, %.2f): %s",
                spots, areaMm2, spotLayers, s.widthMm, s.areaMm2, if (l.where.isEmpty()) "" else "${l.where} ", l.z, s.x, s.y, s.lines()) + cut
        }

        companion object { val NONE = Summary(emptyList()) }
    }

    /** Features not read: ironing (laid over a top by design), wave beads (the over-air rule's). */
    private val UNREAD = setOf("Ironing", "Wave overhang", "Wave overhang, layer above")
    private const val SPARSE = "Sparse infill"

    /**
     * The spots of the layer whose lines are [segs] (in the file's order, ModelCheck.layers) laid twice wider than
     * [floorMm] and two cells, widest first, and whether the reading stopped at [MAX_CELLS]. [tolMm]: the slicing
     * tolerance; [endOverlap]: plan.layer.infill.wall_overlap (a share of a line's width); [fuzzMm]: the fuzzy skin's
     * thickness where it is on, 0 = off.
     */
    internal fun read(segs: List<ModelCheck.Seg>, floorMm: Double, tolMm: Double, endOverlap: Double, fuzzMm: Double): Reading {
        val c = CELL_MM
        val n = segs.size
        if (n < 2) return Reading.NONE
        // each line's narrowed bead: its ends (an open wall run's trimmed) and its radius; radius <= 0: not read
        val ax = DoubleArray(n); val ay = DoubleArray(n); val bx = DoubleArray(n); val by = DoubleArray(n); val rad = DoubleArray(n)
        val names = ArrayList<String>(); val code = ByteArray(n)
        for ((k, s) in segs.withIndex()) {
            ax[k] = s.x0; ay[k] = s.y0; bx[k] = s.x1; by[k] = s.y1
            if (s.feature in UNREAD) { rad[k] = -1.0; continue }
            val t = if (s.t.isNaN()) 0.0 else s.t
            rad[k] = s.bead / 2 - t * (1 - Math.PI / 4) / 2 - tolMm - (if (s.infill) 0.0 else fuzzMm)
            var i = names.indexOf(s.feature); if (i < 0) { names += s.feature; i = names.size - 1 }
            code[k] = (i + 1).toByte()
        }
        // an open run: its ends trimmed by the share of its width a line's end reaches into the walls
        if (endOverlap > 0) {
            var k = 0
            while (k < n) {
                var e = k; while (e + 1 < n && segs[e + 1].run == segs[k].run) e++
                val s0 = segs[k]; val s1 = segs[e]
                if (Math.hypot(s1.x1 - s0.x0, s1.y1 - s0.y0) > s0.w) {
                    trim(segs, k, e, true, endOverlap, ax, ay, bx, by, rad)
                    trim(segs, k, e, false, endOverlap, ax, ay, bx, by, rad)
                }
                k = e + 1
            }
        }
        // the windows each narrowed bead reaches, in the file's order
        var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
        for (k in 0 until n) if (rad[k] > 0) {
            x0 = minOf(x0, minOf(ax[k], bx[k]) - rad[k]); x1 = maxOf(x1, maxOf(ax[k], bx[k]) + rad[k])
            y0 = minOf(y0, minOf(ay[k], by[k]) - rad[k]); y1 = maxOf(y1, maxOf(ay[k], by[k]) + rad[k])
        }
        if (x0 > x1) return Reading.NONE
        val span = c * WIN
        val wi0 = Math.floor(x0 / span).toInt(); val wj0 = Math.floor(y0 / span).toInt()
        val nwi = Math.floor(x1 / span).toInt() - wi0 + 1; val nwj = Math.floor(y1 / span).toInt() - wj0 + 1
        val lists = arrayOfNulls<IntStack>(nwi * nwj)
        for (k in 0 until n) if (rad[k] > 0) {
            for (wj in Math.floor((minOf(ay[k], by[k]) - rad[k]) / span).toInt()..Math.floor((maxOf(ay[k], by[k]) + rad[k]) / span).toInt())
                for (wi in Math.floor((minOf(ax[k], bx[k]) - rad[k]) / span).toInt()..Math.floor((maxOf(ax[k], bx[k]) + rad[k]) / span).toInt()) {
                    val q = (wj - wj0) * nwi + (wi - wi0)
                    (lists[q] ?: IntStack().also { lists[q] = it }).push(k)
                }
        }
        // each window on its own: a cell's last run (0: none yet) and first feature
        val lastRun = IntArray(WIN * WIN); val first = ByteArray(WIN * WIN)
        val keys = LongStack(); val pairs = IntStack()
        val out = DoubleArray(2)
        val sparse = names.indexOf(SPARSE) + 1
        var capped = false
        windows@ for (q in lists.indices) {
            val list = lists[q] ?: continue
            if (list.size < 2) continue
            val i0 = (q % nwi + wi0) * WIN; val j0 = (q / nwi + wj0) * WIN
            java.util.Arrays.fill(lastRun, 0); java.util.Arrays.fill(first, 0)
            for (m in 0 until list.size) {
                val k = list[m]
                val r = rad[k]; val run = segs[k].run + 1; val f = code[k].toInt()
                val ja = maxOf(j0, Math.floor((minOf(ay[k], by[k]) - r) / c - 0.5).toInt())
                val jb = minOf(j0 + WIN - 1, Math.ceil((maxOf(ay[k], by[k]) + r) / c - 0.5).toInt())
                for (j in ja..jb) {
                    if (!plymeld.core.IslandCheck.capsuleSpan(ax[k], ay[k], bx[k], by[k], r, (j + 0.5) * c, out)) continue
                    val ia = maxOf(i0, Math.ceil(out[0] / c - 0.5).toInt()); val ib = minOf(i0 + WIN - 1, Math.floor(out[1] / c - 0.5).toInt())
                    val row = (j - j0) * WIN - i0
                    for (i in ia..ib) {
                        val p = row + i
                        val lr = lastRun[p]
                        if (lr == run) continue
                        if (lr == 0) { lastRun[p] = run; first[p] = f.toByte(); continue }
                        lastRun[p] = run
                        val g = first[p].toInt()
                        if (g < 0) continue                                   // already doubled
                        if (g == sparse && f == sparse) continue              // a sparse infill's families crossing
                        first[p] = (g or 0x80).toByte()
                        if (keys.size >= MAX_CELLS) { capped = true; break@windows }
                        keys.push((i.toLong() shl 32) or (j.toLong() and 0xffffffffL)); pairs.push((g shl 8) or f)
                    }
                }
            }
        }
        if (keys.size == 0) return Reading.NONE
        // the doubled cells in spots
        val set = LongSet(keys.size)
        for (p in 0 until keys.size) set.add(keys[p])
        val seen = BooleanArray(set.capacity)
        val stack = IntStack(); val cells = IntStack()
        val result = ArrayList<Spot>()
        for (p0 in 0 until keys.size) {
            val s0 = set.slot(keys[p0])
            if (seen[s0]) continue
            seen[s0] = true; stack.push(p0); cells.clear()
            while (stack.size > 0) {
                val p = stack.pop(); cells.push(p)
                val i = (keys[p] shr 32).toInt(); val j = (keys[p] and 0xffffffffL).toInt()
                for (d in 0 until 8) {
                    val nk = ((i + DI[d]).toLong() shl 32) or ((j + DJ[d]).toLong() and 0xffffffffL)
                    val sl = set.slot(nk)
                    if (sl < 0 || seen[sl]) continue
                    seen[sl] = true; stack.push(set.index(sl))
                }
            }
            // its width: the farthest its cells lie from a cell not laid twice
            var far = 0.0; var fp = cells[0]
            val limit = Math.ceil(Math.sqrt(cells.size.toDouble())).toInt() + 1
            for (m in 0 until cells.size) {
                val p = cells[m]
                val i = (keys[p] shr 32).toInt(); val j = (keys[p] and 0xffffffffL).toInt()
                val d = HoleCheck.nearestOut(i, j, limit) { pi, pj -> set.slot((pi.toLong() shl 32) or (pj.toLong() and 0xffffffffL)) >= 0 }
                if (d > far) { far = d; fp = p }
            }
            val width = 2 * far * c
            if (width <= floorMm + 2 * c) continue
            val i = (keys[fp] shr 32).toInt(); val j = (keys[fp] and 0xffffffffL).toInt()
            val pr = pairs[fp]
            result += Spot(width, (i + 0.5) * c, (j + 0.5) * c, cells.size * c * c, names[(pr shr 8) - 1], names[(pr and 0xff) - 1])
        }
        return Reading(result.sortedByDescending { it.widthMm }, capped)
    }

    /**
     * Trims [endOverlap] of the line's width off one end ([atStart]: the first) of run [k]..[e]: the segments within it from
     * that end left out, the next one shortened.
     */
    private fun trim(segs: List<ModelCheck.Seg>, k: Int, e: Int, atStart: Boolean, endOverlap: Double,
                     ax: DoubleArray, ay: DoubleArray, bx: DoubleArray, by: DoubleArray, rad: DoubleArray) {
        var left = endOverlap * segs[if (atStart) k else e].w
        var m = if (atStart) k else e
        while (left > 0 && m in k..e) {
            val len = Math.hypot(bx[m] - ax[m], by[m] - ay[m])
            if (len <= left) { rad[m] = -1.0; left -= len }
            else {
                val u = left / len
                if (atStart) { ax[m] += (bx[m] - ax[m]) * u; ay[m] += (by[m] - ay[m]) * u }
                else { bx[m] += (ax[m] - bx[m]) * u; by[m] += (ay[m] - by[m]) * u }
                left = 0.0
            }
            m += if (atStart) 1 else -1
        }
    }

    private val DI = intArrayOf(1, -1, 0, 0, 1, 1, -1, -1)
    private val DJ = intArrayOf(0, 0, 1, -1, 1, -1, 1, -1)

    private class IntStack {
        var a = IntArray(64); var size = 0
        fun push(v: Int) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        fun pop(): Int = a[--size]
        operator fun get(i: Int) = a[i]
        fun clear() { size = 0 }
    }
    private class LongStack {
        var a = LongArray(64); var size = 0
        fun push(v: Long) { if (size == a.size) a = a.copyOf(size * 2); a[size++] = v }
        operator fun get(i: Int) = a[i]
    }

    /** Cell keys by open addressing: each key's slot, and the index it was added with. */
    private class LongSet(n: Int) {
        val capacity = Integer.highestOneBit(maxOf(4, n) * 2) * 2
        private val keys = LongArray(capacity) { EMPTY }
        private val idx = IntArray(capacity)
        private var count = 0
        private fun h(k: Long): Int { var x = k * -0x61c8864680b583ebL; x = x xor (x ushr 29); return (x.toInt()) and (capacity - 1) }
        fun add(k: Long) {
            var s = h(k)
            while (keys[s] != EMPTY) { if (keys[s] == k) return; s = (s + 1) and (capacity - 1) }
            keys[s] = k; idx[s] = count++
        }
        /** [k]'s slot; -1: not in the set. */
        fun slot(k: Long): Int {
            var s = h(k)
            while (keys[s] != EMPTY) { if (keys[s] == k) return s; s = (s + 1) and (capacity - 1) }
            return -1
        }
        fun index(slot: Int) = idx[slot]
        companion object { const val EMPTY = Long.MIN_VALUE }
    }
}
