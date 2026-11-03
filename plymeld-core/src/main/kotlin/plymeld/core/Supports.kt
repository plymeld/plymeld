package plymeld.core

import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.geom.Polygon

/**
 * What support stands under, shared by tree support ([TreeSupports]):
 *
 *  1. Overhang of layer i: its outline minus the layer below grown by the filament's step: how far a
 *     line may hang past the one below (plymeld.model.Overhang: a share of the outer wall's width), whatever the
 *     layer's height (a thinner layer holds a flatter face). Every overhang it finds is supported
 *     that anything could stand under: a strip is kept only where the overhang stacked over a column's
 *     width of height around it is at least that wide ([dropSpecks]; plan.support.column.width,
 *     unset = a tree tip, two support lines). A sloped face is a stack of thin per-layer strips, so it is
 *     judged as its stack, never strip by strip: the "skip small overhangs" opening judged each layer's
 *     strip, which on a slope is one layer's advance wide, and dropped nearly every sloped overhang
 *     (removed); a per-line filter dropped every tree tip's interface and the
 *     columns under small faces (reverted). What this drops: specks (an engraved letter's
 *     ceiling a fraction of a line wide, 21 of them in one print's show face) and faces narrower than a
 *     column, which get no support at all. A locked spot (plan.support.locked) is never filtered.
 *  2. Support standing on the model kept clear of it ([clearAboveModel]).
 */
/** [walls]: outlines printed as support loops before the lines (the tree's branches). */
class LayerSupport(val base: List<Polygon>, val interfaceTop: List<Polygon>, val interfaceBottom: List<Polygon>, val walls: List<Polygon> = emptyList())

object Supports {
    /**
     * A box support is confined to or locked in (SupportPlan): x and y in the placed frame, z from
     * the object's bottom (as the layer tops are), all µm.
     */
    class Box(val x0: Long, val x1: Long, val y0: Long, val y1: Long, val z0: Long, val z1: Long) {
        fun polygon() = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
        /**
         * Whether a layer from [bottom] to [top] (µm, from the object's bottom) prints a face of the box's height range:
         * the layer holding a face is the first whose material lies above it, up to one layer above the range (a flat
         * underside is a range of no height, z0 = z1, and the layer over it starts at or above it: without the layer's
         * own height as tolerance such a spot matched no layer, and the plan's support was silently not generated).
         */
        fun spans(bottom: Long, top: Long) = bottom - (top - bottom) < z1 && z0 < top
    }

    /**
     * Where support is generated: [chosen] boxes (null = everywhere, the switch locked on): an overhang outside every box
     * gets no support; [locked] boxes: every downward face in them gets support, whether or not it needs it (the
     * user's locked spots).
     */
    class Regions(val chosen: List<Box>?, val locked: List<Box>) {
        val any: Boolean get() = chosen == null || chosen.isNotEmpty() || locked.isNotEmpty()
    }

    class Params(
        /** How far a layer may step out beyond the one below and hold (the filament's plymeld.model.Overhang.stepMm), µm. */
        val overhangStepUm: Long, val xyGapUm: Long, val topGapLayers: Int, val interfaceTopLayers: Int,
        val interfaceBottomLayers: Int, val onPlateOnly: Boolean, val lineWidthUm: Long,
        /** Where support is generated; null = everywhere, nothing locked (as before). */
        val regions: Regions? = null,
        /** Planned inserts, by the layer laid onto each: what that layer stands on there besides the layer below (no overhang over it). */
        val covered: Map<Int, List<Polygon>> = emptyMap(),
        /** The narrowest overhang supported, µm ([dropSpecks]; also the height it is stacked over); 0 = every overhang, as before. Needs the layer tops. */
        val minWidthUm: Long = 0,
    )

    /**
     * Step 1: the overhang of each layer (empty for layer 0). With [Params.regions]: the overhang within the
     * chosen boxes only, plus every downward face (the layer beyond the one below, no step allowed) within a locked box;
     * [layerTopsUm] places each layer in the boxes' height ranges (needed then).
     */
    fun overhangs(outlines: List<List<Polygon>>, p: Params, layerTopsUm: LongArray? = null): List<List<Polygon>> {
        val n = outlines.size
        val rg = p.regions
        require(rg == null || layerTopsUm != null) { "support confined to regions needs the layer tops" }
        require(p.minWidthUm <= 0 || layerTopsUm != null) { "the support speck filter needs the layer tops" }
        // what the layer stands on: the layer below, and a planned insert's cover where one is laid onto here
        fun below(i: Int) = p.covered[i]?.let { Booleans.union(outlines[i - 1], it) } ?: outlines[i - 1]
        val raw = ArrayList<List<Polygon>>(n)
        raw.add(emptyList())
        // the downward faces (the layer beyond the one below, no step allowed), the speck filter's stack
        val faces = if (p.minWidthUm > 0) ArrayList<List<Polygon>>(n).also { it.add(emptyList()) } else null
        for (i in 1 until n) {
            if (outlines[i].isEmpty()) { raw.add(emptyList()); faces?.add(emptyList()); continue }
            val under = below(i)
            val over = Booleans.difference(outlines[i], Offset.offset(under, p.overhangStepUm))
            faces?.add(if (over.isEmpty()) emptyList() else Booleans.difference(outlines[i], under))
            val chosenHere = rg?.chosen?.filter { it.spans(layerTopsUm!![i - 1], layerTopsUm[i]) }?.map { it.polygon() }
            raw.add(if (chosenHere == null) over else if (chosenHere.isEmpty() || over.isEmpty()) emptyList() else Booleans.intersection(over, chosenHere))
        }
        val kept = if (faces != null && n > 1) dropSpecks(raw, faces, layerTopsUm!!, p.minWidthUm) else raw
        if (rg == null || rg.locked.isEmpty()) return kept
        // a locked spot: every downward face in it, filtered by nothing
        return List(n) { i ->
            if (i == 0 || outlines[i].isEmpty()) kept[i] else {
                val lockedHere = rg.locked.filter { it.spans(layerTopsUm!![i - 1], layerTopsUm[i]) }.map { it.polygon() }
                if (lockedHere.isEmpty()) kept[i] else {
                    val down = Booleans.intersection(Booleans.difference(outlines[i], below(i)), lockedHere)
                    if (down.isEmpty()) kept[i] else if (kept[i].isEmpty()) down else Booleans.union(kept[i], down)
                }
            }
        }
    }

    /**
     * The overhang pieces nothing could stand under, dropped (plan.support.column.width; the "support dabs" of a
     * 30-part model's review): layer k's overhang is kept only where the downward faces ([faces]: each
     * layer beyond the one below, no step allowed; given for the layers whose overhang is not empty) of every layer
     * within [widthUm] of height above and below it, taken together, are at least [widthUm] across (their opening by
     * half that width). A sloped face's overhang is a comb of per-layer strips, each one layer's advance less the step
     * wide, with the step zones between them; its downward faces stacked over a column's width of height are the
     * face's own footprint, so a face flatter than the overhang limit is kept whole (judge the column, not the
     * strip). What never stacks up is dropped: a speck (an engraved letter's ceiling a
     * fraction of a line wide, one layer), and a face narrower than [widthUm] everywhere, under which no column that
     * wide can be laid. The layers run in parallel; each result is a subset of its input.
     */
    fun dropSpecks(overhang: List<List<Polygon>>, faces: List<List<Polygon>>, layerTopsUm: LongArray, widthUm: Long): List<List<Polygon>> {
        val n = overhang.size
        val out = arrayOfNulls<List<Polygon>>(n)
        val half = widthUm / 2
        java.util.stream.IntStream.range(0, n).parallel().forEach { k ->
            val o = overhang[k]
            if (o.isEmpty()) { out[k] = o; return@forEach }
            var lo = k; while (lo > 0 && layerTopsUm[k] - layerTopsUm[lo - 1] <= widthUm) lo--
            var hi = k; while (hi + 1 < n && layerTopsUm[hi + 1] - layerTopsUm[k] <= widthUm) hi++
            var stack: List<Polygon> = faces[k]
            for (j in lo..hi) if (j != k && faces[j].isNotEmpty()) stack = Booleans.union(stack, faces[j])
            val opened = Offset.offset(Offset.offset(stack, -half), half)
            out[k] = if (opened.isEmpty()) emptyList() else Booleans.intersection(o, opened)
        }
        return out.map { it!! }
    }

    /**
     * Support standing on the model kept [gapUm] above it (plan.support.gap.bottom; without it, support was
     * laid straight onto the model's top, an ironed show surface included): layer j
     * loses its support over every model layer whose top lies less than the gap below j's bottom. The [interfaceBottom]
     * layers of support just above the gap become bottom interface, as on the bed. Applied to what was
     * generated ([gen], one per layer as [outlines] and [layerTopsUm]).
     */
    fun clearAboveModel(gen: List<LayerSupport>, outlines: List<List<Polygon>>, layerTopsUm: LongArray, gapUm: Long, interfaceBottom: Int): List<LayerSupport> {
        val n = gen.size
        /** The model under layer j within [depth] (µm) below its bottom: the union of those layers' outlines. */
        fun modelBelow(j: Int, fromUm: Long, toUm: Long): List<Polygon> {
            if (j == 0) return emptyList()
            val bottom = layerTopsUm[j - 1]
            var acc: List<Polygon> = emptyList()
            var k = j - 1
            while (k >= 0 && bottom - layerTopsUm[k] < toUm) {
                if (bottom - layerTopsUm[k] >= fromUm && outlines[k].isNotEmpty()) acc = if (acc.isEmpty()) outlines[k] else Booleans.union(acc, outlines[k])
                k--
            }
            return acc
        }
        return List(n) { j ->
            val ls = gen[j]
            if (ls.base.isEmpty() && ls.interfaceTop.isEmpty() && ls.interfaceBottom.isEmpty() && ls.walls.isEmpty()) return@List ls
            val gapZone = modelBelow(j, 0, gapUm)
            fun cut(p: List<Polygon>) = if (p.isEmpty() || gapZone.isEmpty()) p else Booleans.difference(p, gapZone)
            val base = cut(ls.base); val top = cut(ls.interfaceTop); var bottom = cut(ls.interfaceBottom); val walls = cut(ls.walls)
            // bottom interface: support over the model just under the gap (the next interfaceBottom layers' worth of height)
            var rest = base
            if (interfaceBottom > 0 && rest.isNotEmpty() && j > 0) {
                val h = layerTopsUm[j] - layerTopsUm[j - 1]
                val onModel = modelBelow(j, gapUm, gapUm + interfaceBottom * h)
                if (onModel.isNotEmpty()) {
                    val itf = Booleans.intersection(rest, onModel)
                    if (itf.isNotEmpty()) { bottom = if (bottom.isEmpty()) itf else Booleans.union(bottom, itf); rest = Booleans.difference(rest, onModel) }
                }
            }
            LayerSupport(rest, top, bottom, walls)
        }
    }
}
