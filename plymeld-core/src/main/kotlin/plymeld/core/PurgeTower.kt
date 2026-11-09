package plymeld.core

import plymeld.geom.Offset
import plymeld.geom.Polygon

/**
 * Multi-color step 4 — the purge tower (plymeld's own design; the settings supply on/off,
 * position, width, purge volume per change — whatever file they were imported from, which is not
 * the same as the user having chosen them).
 *
 * A rectangular prism at ([xUm], [yUm]), [widthUm] along X, its depth along Y sized for the layer
 * with the most changes. Inside its outline wall, each change on a layer gets its own band of purge
 * lines (back and forth along X, [lineUm] wide, one bead spacing apart) long enough to hold
 * [purgeMm3]; the band printed with the NEW filament. Layer 0 is solid (adhesion). The tower stops at
 * the last layer with a change.
 *
 * Two layouts:
 * - **banded** (the original): every layer up to the top prints the wall and every band; bands no
 *   change uses get sparse lines at [sparseSpacingUm] so a purge above never lands on air.
 * - **compact** (default): each band is a column with its own top. A column is printed only when a
 *   change purges into it, or when leaving it one more layer would open a gap taller than
 *   [maxGapUm] (the nozzle's max layer height); then it gets sparse filler. Anything printed over a
 *   gap is extruded at the gap's height. The wall is printed only on a layer the tower is visited;
 *   on a change the NEW filament lays it after its purge lines (its first plastic goes inside the tower, not on its
 *   edge), and it counts toward the purge volume. A layer without a change and without a filler due is not visited at all.
 *
 * Each side at least [minSideShare] of the tower's height (a tall, thin tower sways): a tower the purge alone would
 * leave narrower is widened, its bands deepened, and a band's lines beyond its purge laid at the sparse spacing. A brim
 * [brimUm] wide around it on the first layer, laid before the tower, for its hold on the bed, its inner loop reaching
 * [brimOverlapUm] into the tower's outline so the two fuse.
 */
class PurgeTower(
    val xUm: Long, val yUm: Long, val widthUm: Long, val purgeMm3: Double,
    val lineUm: Long, val sparseSpacingUm: Long, private val changesPerLayer: IntArray, layerTopsUm: LongArray,
    val compact: Boolean = false, val maxGapUm: Long = Long.MAX_VALUE,
    /** Each side at least this share of the tower's height (0 = no minimum). */
    val minSideShare: Double = 0.0,
    /** The brim around the tower's first layer, its width (0 = none). */
    val brimUm: Long = 0,
    /** The brim's line width on the first layer. */
    val brimLineUm: Long = lineUm,
    /** How far the brim's inner loop reaches into the tower's outline, so the two fuse (0 = they only touch). */
    val brimOverlapUm: Long = 0,
) {
    private val hs = Planner.heights(layerTopsUm)
    /** Layers the tower is printed on: 0 .. the last layer with a change (empty when there is none). */
    val top: Int = changesPerLayer.indexOfLast { it > 0 }
    /** The inside of the outline wall, along X. */
    private val innerW = widthUm - 2 * lineUm
    private fun spacing(hUm: Long) = Walls.spacing(lineUm, hUm)
    /** Cross-section (mm²) of a bead [lineUm] wide and [hUm] high: the same bead model as every path. */
    private fun bead(hUm: Long): Double { val h = hUm / 1000.0; val w = lineUm / 1000.0; return h * (w - h * (1 - Math.PI / 4)) }
    /** Lines of the band's width holding [mm3] at height [hUm]. */
    private fun linesFor(mm3: Double, hUm: Long): Int = Math.ceil(mm3 / (innerW / 1000.0 * bead(hUm))).toInt()
    /** Lines one purge needs on layer [i] at that layer's own height (banded layout). */
    fun purgeLines(i: Int): Int = linesFor(purgeMm3, hs[i])
    private val bands: Int = Math.max(1, changesPerLayer.maxOrNull() ?: 0)

    /** Per layer 0..top: the height each band is printed at (0 = not printed), and the wall's. */
    private val bandH = Array(top + 1) { LongArray(bands) }
    private val wallH = LongArray(top + 1)
    init {
        val colTop = LongArray(bands); var wallTop = 0L
        for (i in 0..top) {
            val z = layerTopsUm[i]
            var visit = false
            for (k in 0 until bands) {
                val print = !compact || i == 0 || k < changesPerLayer[i] ||
                    (i < top && layerTopsUm[i + 1] - colTop[k] > maxGapUm)
                if (print) {
                    bandH[i][k] = z - colTop[k]
                    require(bandH[i][k] <= maxGapUm) { "purge tower: layer $i leaves a %.2f mm gap over column $k, above the %.2f mm limit".format(java.util.Locale.ROOT, bandH[i][k] / 1e3, maxGapUm / 1e3) }
                    colTop[k] = z; visit = true
                }
            }
            if (visit) { wallH[i] = z - wallTop; wallTop = z }
        }
    }

    /** Band depth for the purge alone: the most any purge needs (thinner prints need more lines). */
    private val purgeBandUm: Long = when {
        top < 0 -> 0
        !compact -> (0..top).maxOf { purgeLines(it) * spacing(hs[it]) }
        else -> (0..top).maxOf { i -> (0 until changesPerLayer[i]).maxOfOrNull { k -> linesFor(purgeMm3, bandH[i][k]) * spacing(bandH[i][k]) } ?: 0L }
            .coerceAtLeast(spacing(hs[0]))
    }
    /** The least side ([minSideShare] of the tower's height). */
    val minSideUm: Long = if (top < 0) 0 else Math.round(minSideShare * layerTopsUm[top])
    /** Band depth: the purge's, deepened so the tower is at least [minSideUm] deep. */
    private val bandUm: Long = if (top < 0) 0 else maxOf(purgeBandUm, Math.ceil((minSideUm - 2 * lineUm).toDouble() / bands).toLong())
    /** Bands deeper than their purge: each band's lines beyond its purge laid at the sparse spacing, so its top stays level. */
    private val padded = bandUm > purgeBandUm
    val depthUm: Long = bands * bandUm + 2 * lineUm

    companion object {
        /**
         * The tower as near square as its purge lines allow (the width from
         * the purge volume per change and the layer heights, which set the area each layer needs): the
         * smallest width whose depth is no greater. A square holds that area inside the shortest outline
         * wall, and is the least slender across its narrowest side, where a tall tower would sway.
         */
        fun square(xUm: Long, yUm: Long, purgeMm3: Double, lineUm: Long, sparseSpacingUm: Long, changesPerLayer: IntArray, layerTopsUm: LongArray,
                   compact: Boolean = false, maxGapUm: Long = Long.MAX_VALUE, minSideShare: Double = 0.0, brimUm: Long = 0, brimLineUm: Long = lineUm, brimOverlapUm: Long = 0): PurgeTower {
            fun at(w: Long) = PurgeTower(xUm, yUm, w, purgeMm3, lineUm, sparseSpacingUm, changesPerLayer, layerTopsUm, compact, maxGapUm, minSideShare, brimUm, brimLineUm, brimOverlapUm)
            var lo = 3 * lineUm; var hi = lo
            while (at(hi).depthUm > hi) hi *= 2                 // the depth only falls as the width grows
            while (hi - lo > 1) { val mid = (lo + hi) / 2; if (at(mid).depthUm > mid) lo = mid else hi = mid }
            return at(hi)
        }
    }
    /** The tower's outline. */
    private val outline: Polygon = Polygon(longArrayOf(xUm, yUm, xUm + widthUm, yUm, xUm + widthUm, yUm + depthUm, xUm, yUm + depthUm))
    /** The brim's loops, outermost last: a first-layer line each, one spacing apart, out to [brimUm]. */
    private val brimLoops: List<Polygon> = if (brimUm <= 0 || top < 0) emptyList() else {
        val sp = Walls.spacing(brimLineUm, hs[0])
        val out = ArrayList<Polygon>()
        // the inner loop reaches [brimOverlapUm] into the tower's outline: a loop that only touched the tower's wall, laid by
        // another tool before it, held the bed while the tower peeled off it
        var d = brimLineUm / 2 - brimOverlapUm
        while (d <= brimUm - brimLineUm / 2) { out += Offset.offset(listOf(outline), d, Offset.Join.ROUND).filter { it.isCcw() }; d += sp }
        out
    }
    /** The tower's footprint, its brim included (for collision and bed checks). */
    val footprint: Polygon = if (brimUm <= 0) outline else
        Offset.offset(listOf(outline), brimUm, Offset.Join.ROUND).filter { it.isCcw() }.maxBy { it.signedArea() }

    /** [base]: printed by the tool in hand at the layer's first visit (before its first change) or at its end; [purges]: one group per change, in change order, printed by the NEW tool. */
    class Layer(val base: List<ToolPath>, val purges: List<List<ToolPath>>)

    /**
     * Layer [i] with [changes] changes. [needs]: what each change still has to purge here (mm³) after what went into the
     * object's own infill (plan.mechanism.purge.into); null = the whole purge. A band whose purge takes fewer lines than
     * the band holds gets the rest at the sparse spacing, so the band's top stays level for the next layer.
     */
    fun layer(i: Int, changes: Int, needs: List<Double>? = null): Layer {
        require(i in 0..top) { "layer $i is above the tower (top $top)" }
        require(changes <= bands) { "$changes changes on layer $i, tower has $bands bands" }
        require(needs == null || needs.size == changes) { "${needs?.size} purge needs for $changes changes on layer $i" }
        val half = lineUm / 2
        val wall = if (wallH[i] > 0) ToolPath(Feature.TOWER, longArrayOf(xUm + half, yUm + half, xUm + widthUm - half, yUm + half,
            xUm + widthUm - half, yUm + depthUm - half, xUm + half, yUm + depthUm - half), true, lineUm, wallH[i]) else null
        val x0 = xUm + lineUm; val x1 = xUm + widthUm - lineUm
        /** Lines across [yFrom, yTo] [step] apart, [count] at most; from [yFrom] up (or from [yTo] down with [down]), the first from the left (or the right with [fromRight]). */
        fun zigzag(yFrom: Long, yTo: Long, step: Long, h: Long, count: Int? = null, down: Boolean = false, fromRight: Boolean = false): ToolPath? {
            val pts = ArrayList<Long>()
            var y = if (down) yTo - step / 2 else yFrom + step / 2; var k = 0; var leftToRight = !fromRight
            while ((if (down) y > yFrom else y < yTo) && (count == null || k < count)) {
                if (leftToRight) { pts += x0; pts += y; pts += x1; pts += y } else { pts += x1; pts += y; pts += x0; pts += y }
                leftToRight = !leftToRight; y += if (down) -step else step; k++
            }
            return if (pts.size >= 4) ToolPath(Feature.TOWER, pts.toLongArray(), false, lineUm, h) else null
        }
        val wallMm3 = if (wall == null) 0.0 else 2.0 * ((widthUm - lineUm) + (depthUm - lineUm)) / 1000.0 * bead(wallH[i])
        val purges = (0 until changes).map { k ->
            val h = bandH[i][k]
            val y0 = yUm + lineUm + k * bandUm
            val group = ArrayList<ToolPath>()
            var need = needs?.get(k) ?: purgeMm3
            // the wall this change lays (compact: the first change on a layer the tower is visited) counts toward its purge,
            // but is laid LAST: a nozzle just back from the dock lays its first, uneven plastic inside the tower, not on its
            // edge (every change starting the wall at one corner piled a blob there each time, and that corner peeled off)
            val ownWall = compact && k == 0 && wall != null
            if (ownWall) need = Math.max(0.0, need - wallMm3)
            val n = if (compact || needs != null) linesFor(need, h) else purgeLines(i)
            // where the change starts: one of the band's four corners, turning layer by layer and change by change, so the
            // first plastic of a nozzle back from the dock does not land on one spot every time (all 188 changes of a
            // two-color nameplate started at one point, by the corner that peeled)
            val turn = (i + k) % 4
            val down = turn >= 2; val fromRight = turn % 2 == 1
            if (n > 0) zigzag(y0, y0 + bandUm, spacing(h), h, n, down, fromRight)?.let { group += it }
            // the band's rest, where the purge took fewer lines than the band holds: on the first layer solid (the tower's
            // hold on the bed, and the floor the next layers' purges land on, which may take more lines than a thicker
            // first layer's), above it at the sparse spacing; on the side of the band the purge did not reach
            val restStep = if (i == 0) spacing(h) else sparseSpacingUm
            if (i == 0 || needs != null || padded) {
                if (!down) { val yEnd = y0 + n * spacing(h); if (yEnd + restStep < y0 + bandUm) zigzag(yEnd, y0 + bandUm, restStep, h)?.let { group += it } }
                else { val yEnd = y0 + bandUm - n * spacing(h); if (y0 + restStep < yEnd) zigzag(y0, yEnd, restStep, h, down = true)?.let { group += it } }
            }
            if (ownWall) group += wall
            group
        }
        val idle = (changes until bands).filter { bandH[i][it] > 0 }.mapNotNull { k ->
            val h = bandH[i][k]; val y0 = yUm + lineUm + k * bandUm
            zigzag(y0, y0 + bandUm, if (i == 0) spacing(h) else sparseSpacingUm, h)
        }
        val wallInBase = wall != null && !(compact && changes > 0)
        // the brim, on the first layer before anything of the tower, innermost first
        val brim = if (i == 0) brimLoops.map { ToolPath(Feature.BRIM, it.xy, true, brimLineUm, hs[0]) } else emptyList()
        return Layer(brim + (if (wallInBase) listOfNotNull(wall) else emptyList()) + idle, purges)
    }
}
