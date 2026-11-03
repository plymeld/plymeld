package plymeld.mesh

/**
 * Where the slice planes go. Slicing happens at the *middle* of each layer, the
 * usual convention: layer n occupies (top_{n-1}, top_n] and is sampled at
 * top_n - h/2, so a feature's presence in a layer is decided by its mid-height.
 *
 * `zMin` is the model's lowest point; layers start there (print pose: the part
 * sits on the bed at its own zMin). Heights in mm.
 */
class LayerPlan(val tops: DoubleArray, val slices: DoubleArray) {
    val count: Int get() = tops.size

    /**
     * This plan with its layers above [zFrom] (from the last top at or under it to the plan's top) made [layerHeight] or a
     * little thinner, all of one thickness: a curved band's. There each layer is squeezed
     * where the top is lower (the wave tile's troughs: to 46 %), and a remainder layer left at the top, thin but over the
     * tool's thinnest in warped space, came out under it there (0.12 mm warped, 0.055 mm printed).
     */
    fun evenAbove(zFrom: Double, layerHeight: Double): LayerPlan {
        val keep = tops.indexOfLast { it <= zFrom + LAND_MM }
        if (keep < 0 || keep == tops.size - 1) return this
        val base = tops[keep]; val zMax = tops.last()
        val n = maxOf(1, Math.ceil((zMax - base) / layerHeight - 1e-6).toInt())
        val t = DoubleArray(keep + 1 + n) { i -> if (i <= keep) tops[i] else if (i == keep + n) zMax else base + (zMax - base) * (i - keep) / n }
        return LayerPlan(t, DoubleArray(t.size) { i -> if (i <= keep) slices[i] else t[i] - (t[i] - t[i - 1]) / 2 })
    }

    /** A height band [lo, hi] (absolute mm) printed with flat layers following the surfaces ([adaptive]) or of one height. */
    class Band(val lo: Double, val hi: Double, val adaptive: Boolean)

    /**
     * The thickest layer allowed at each height by the faces there (adaptive layers' rule), in bins
     * of [binMm] from [zMin]; and the heights of the flat faces.
     */
    class Limits(val zMin: Double, val zMax: Double, val bins: DoubleArray, val flats: java.util.TreeSet<Double>, val binMm: Double) {
        /** The thickest layer allowed across [lo, hi) (absolute mm). */
        fun over(lo: Double, hi: Double): Double {
            var h = Double.POSITIVE_INFINITY
            val k0 = maxOf(0, ((lo - zMin) / binMm).toInt())
            for (k in k0 until bins.size) { if (zMin + k * binMm >= hi) break; if (bins[k] < h) h = bins[k] }
            return h
        }
    }

    companion object {
        fun fixed(zMin: Double, zMax: Double, firstLayer: Double, layerHeight: Double): LayerPlan {
            require(firstLayer > 0 && layerHeight > 0) { "layer heights must be positive" }
            val tops = ArrayList<Double>()
            var top = zMin + firstLayer
            // Keep slicing while the layer's midpoint is inside the model.
            while (top - (if (tops.isEmpty()) firstLayer else layerHeight) / 2 < zMax) {
                tops.add(top)
                top += layerHeight
            }
            val t = tops.toDoubleArray()
            val s = DoubleArray(t.size) { i -> t[i] - (if (i == 0) firstLayer else layerHeight) / 2 }
            return LayerPlan(t, s)
        }

        /**
         * Flat layers of one height (technique "flat" with one layer height): layers of [layerHeight] after the first, the last
         * one landing exactly on the model's top (no reason to print past or short of the top). The remainder above
         * the last whole layer changes as few layers as it can, inside what the tool lays ([minLayer]): a remainder the
         * tool can lay is a layer of its own; a thinner one is shared with the layer below, as two equal layers when
         * each is still at least [minLayer], else as one layer (and, when the layer below is the first one, added to
         * it). No layer is thinner than [minLayer]: a model at 0.08 mm layers, the
         * nozzle's thinnest, once ended in two of 0.055.
         */
        fun uniform(zMin: Double, zMax: Double, firstLayer: Double, layerHeight: Double, minLayer: Double): LayerPlan {
            require(firstLayer > 0 && layerHeight > 0 && minLayer > 0) { "layer heights must be positive" }
            val tops = ArrayList<Double>()
            tops.add(minOf(zMax, zMin + firstLayer))
            // whole layers; computed from the first layer each time so no round-off builds up
            var k = 1
            while (zMin + firstLayer + k * layerHeight <= zMax + LAND_MM) { tops.add(minOf(zMax, zMin + firstLayer + k * layerHeight)); k++ }
            val r = zMax - tops.last()
            if (r > LAND_MM) {
                if (r >= minLayer - LAND_MM) tops.add(zMax)
                else if (tops.size >= 2) {
                    val base = tops[tops.size - 2]
                    if ((zMax - base) / 2 >= minLayer - LAND_MM) { tops[tops.size - 1] = base + (zMax - base) / 2; tops.add(zMax) }
                    else tops[tops.size - 1] = zMax
                } else tops[0] = zMax
            } else tops[tops.size - 1] = zMax
            val t = tops.toDoubleArray()
            return LayerPlan(t, DoubleArray(t.size) { i -> t[i] - (t[i] - (if (i == 0) zMin else t[i - 1])) / 2 })
        }

        /**
         * Adaptive layer height: flat layers following the surfaces. Each layer is as
         * thick as the surfaces it crosses allow, between [minLayer] and [maxLayer]:
         *  - a sloped face leaves a stair-step h x |nz| deep (nz: the vertical part of its unit
         *    normal), so a layer crossing it is at most [maxStep] / |nz| thick; vertical faces leave
         *    none and allow [maxLayer];
         *  - a flat face (horizontal) gets a layer top exactly at its height, and so does the model's
         *    top: the layers before it are evened out to land there (a flat face closer than
         *    [minLayer] above the last top cannot be landed on and is passed over);
         *  - [landings] (absolute heights, e.g. pauses) are layer tops too, landed on like flat faces;
         *  - the first layer is [firstLayer], as with fixed layers.
         * Heights in mm; [soup] in its print pose. The one-band case of [composite].
         */
        fun adaptive(soup: TriangleSoup, firstLayer: Double, minLayer: Double, maxLayer: Double, maxStep: Double, landings: Collection<Double> = emptyList()): LayerPlan {
            val b = soup.bounds()
            return composite(soup, firstLayer, listOf(Band(b[2], b[5], true)), 0.0, minLayer, maxLayer, maxStep, landings)
        }

        /** The thickest layer allowed at each height (adaptive layers' rule) and the flat faces: [LayerPlan.Limits]. */
        fun limits(soup: TriangleSoup, minLayer: Double, maxLayer: Double, maxStep: Double): Limits {
            require(minLayer > 0 && maxLayer >= minLayer && maxStep > 0) { "adaptive layers need 0 < min <= max layer height and a positive step" }
            val b = soup.bounds()
            val zMin = b[2]; val zMax = b[5]
            val bins = maxOf(1, Math.ceil((zMax - zMin) / BIN_MM).toInt() + 1)
            val limit = DoubleArray(bins) { maxLayer }
            val flats = java.util.TreeSet<Double>()
            val c = soup.coords
            for (i in 0 until soup.count) {
                val o = 9 * i
                val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
                val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
                if (len == 0.0) continue
                val lo = minOf(c[o + 2], c[o + 5], c[o + 8]).toDouble(); val hi = maxOf(c[o + 2], c[o + 5], c[o + 8]).toDouble()
                if (hi - lo < FLAT_MM) { flats.add(Math.round((lo + hi) / 2 * 1000) / 1000.0); continue }
                val h = (maxStep / (Math.abs(nz) / len)).coerceIn(minLayer, maxLayer)
                if (h >= maxLayer) continue
                // a face starting exactly on a bin edge does not reach into the bin below
                for (k in ((lo - zMin) / BIN_MM + 1e-6).toInt()..minOf(bins - 1, ((hi - zMin) / BIN_MM).toInt())) if (h < limit[k]) limit[k] = h
            }
            return Limits(zMin, zMax, limit, flats, BIN_MM)
        }

        /**
         * Several techniques stacked in one object: [bands]
         * cover the model's height in order; an adaptive band follows [adaptive]'s rule (flat faces
         * inside it are landed on), a uniform band prints layers of [layerHeight], evened out to end
         * on the band's top. Every boundary between bands is a layer top (the hand-over), as are
         * [landings] and the model's top. [layerHeight] is read only by uniform bands.
         *
         * [heightRatio] (> 1; 0 = no limit) is the transition between layer heights: no layer (after the first, which
         * adhesion sets) is more than that much thicker than the one below it, and the layers before a band whose
         * layers start thinner step down by about that much each, so a 0.08 mm band under a 0.28 mm one meets it
         * through 0.12, 0.18, 0.27 rather than a jump. The ramp lands on every must as before.
         */
        fun composite(soup: TriangleSoup, firstLayer: Double, bands: List<Band>, layerHeight: Double, minLayer: Double, maxLayer: Double, maxStep: Double,
                      landings: Collection<Double> = emptyList(), limits: Limits? = null, heightRatio: Double = 0.0): LayerPlan {
            require(firstLayer > 0) { "layer heights must be positive" }
            require(bands.isNotEmpty() && bands.zipWithNext().all { (a, b) -> Math.abs(a.hi - b.lo) < 1e-9 }) { "bands must follow each other without gaps" }
            require(bands.all { it.adaptive } || layerHeight > 0) { "uniform bands need a layer height" }
            require(heightRatio == 0.0 || heightRatio > 1.0) { "the layer height ratio must be above 1 (0: no limit), got $heightRatio" }
            val ramp = heightRatio > 1.0
            val lim = limits ?: limits(soup, minLayer, maxLayer, maxStep)
            val zMin = lim.zMin; val zMax = lim.zMax
            if (zMax - zMin <= firstLayer) return fixed(zMin, zMax, firstLayer, if (layerHeight > 0) layerHeight else minLayer)
            fun r3(z: Double) = Math.round(z * 1000) / 1000.0
            // targets: flat faces inside adaptive bands; musts: band boundaries, landings, the top
            val targets = java.util.TreeSet<Double>()
            for (band in bands) if (band.adaptive) targets.addAll(lim.flats.subSet(band.lo, false, band.hi, false))
            val musts = HashSet<Double>()
            fun must(z0: Double) {
                val z = r3(z0)
                if (z <= zMin || z >= zMax) return
                // flat faces closer to a must than the thinnest layer give way to it
                targets.subSet(z - minLayer, false, z + minLayer, false).clear()
                targets.add(z); musts.add(z)
            }
            for (band in bands.dropLast(1)) must(band.hi)
            for (z in landings) must(z)
            targets.add(zMax); musts.add(zMax)
            fun bandAt(z: Double) = bands.firstOrNull { z < it.hi - LAND_MM } ?: bands.last()
            /** The thickest layer the faces from [z] up to [z] + the tool's thickest allow, within the band's rule, below [target]. */
            fun natural(z: Double, target: Double): Double {
                if (!bandAt(z).adaptive) return layerHeight
                // the thickest layer the faces between z and z + h allow (faces above the target belong to later layers)
                var h = maxLayer
                for (k in maxOf(0, ((z - zMin) / BIN_MM).toInt()) until lim.bins.size) {
                    val start = zMin + k * BIN_MM
                    if (start >= z + h || start >= target - LAND_MM) break
                    if (lim.bins[k] < h) h = lim.bins[k]
                }
                return h
            }
            /** The height the band starting at [zb] begins its layers with (null: no band starts there). */
            fun heightAbove(zb: Double): Double? {
                val above = bands.firstOrNull { Math.abs(it.lo - zb) < 1e-6 && it.lo > zMin } ?: return null
                return if (above.adaptive) lim.bins[((zb - zMin) / BIN_MM + 1e-6).toInt().coerceIn(0, lim.bins.size - 1)].coerceIn(minLayer, maxLayer) else layerHeight
            }
            val tops = ArrayList<Double>()
            var z = zMin + firstLayer
            tops.add(z)
            var prev = Double.NaN // the layer below's height once past the first layer (adhesion sets that one)
            while (z < zMax - LAND_MM) {
                // the next height to land on (a band's top or the model's top at the latest)
                var target = targets.higher(z + LAND_MM)!!
                while (target - z < minLayer && target < zMax && target !in musts) target = targets.higher(target)!!
                var h = natural(z, target)
                val r = target - z
                // the transition up: no layer more than the ratio thicker than the one below
                var capped = false
                if (ramp && !prev.isNaN() && h > prev * heightRatio) { h = prev * heightRatio; capped = true }
                // the transition down: a band starting thinner above the target is met by layers stepping down by
                // the ratio each; the descent starts as soon as the room left would not hold it
                val above = if (ramp && target in musts) heightAbove(target) else null
                if (above != null && h > above * heightRatio + 1e-9) {
                    val seq = descent(r, h, above * heightRatio, minLayer, heightRatio)
                    if (seq != null) {
                        var laid = 0
                        for (s in seq) {
                            // a face inside the descent (a slope under the band's floor) still caps the layer; the loop
                            // takes the rest from there
                            if (s > natural(z, target) + 1e-9) break
                            z += s; tops.add(z); prev = s; laid++
                        }
                        if (laid > 0) continue
                    }
                }
                // land on the target: even the layers out to reach it exactly (a layer the ratio capped is laid as
                // it is: the layers after it grow to the natural height, and evening takes over from there)
                h = if (r <= h) r else if (capped) h else r / Math.ceil(r / h - 1e-9)
                // evened-out layers thinner than the tool allows (e.g. a layer height that is the
                // tool's thinnest): a must is reached in the most layers that are each at least the
                // thinnest (a little over the slope's limit rather than missing it); a flat face is
                // passed over instead, keeping the slope's limit
                if (h < minLayer) h = if (target in musts) r / maxOf(1.0, Math.floor(r / minLayer + 1e-9)) else minOf(r, minLayer)
                z += h
                tops.add(z)
                prev = h
            }
            val t = tops.toDoubleArray()
            val s = DoubleArray(t.size) { i -> t[i] - (t[i] - (if (i == 0) zMin else t[i - 1])) / 2 }
            return LayerPlan(t, s)
        }

        /**
         * The layers that fill [room] up to a band whose layers start thinner: a geometric descent, each at most [ratio]
         * times the one above it, the last at or under [last] (the ratio times that band's first layer), the first at or
         * under [first] (the layer the faces and the ratio allow now). Null when the room still holds a layer of [first]
         * before such a descent (not yet), or when no descent keeps every layer at least [minLayer].
         */
        private fun descent(room: Double, first: Double, last: Double, minLayer: Double, ratio: Double): List<Double>? {
            // the room a full descent from [first] needs: [last], then the ratio more each, up to [first] itself
            var need = first; var x = last
            while (x < first - 1e-9) { need += x; x *= ratio }
            if (room > need + first + 1e-9) return null
            var n = Math.ceil(room / first - 1e-9).toInt().coerceAtLeast(1)
            val nMax = Math.floor(room / minLayer + 1e-9).toInt()
            while (n <= nMax) {
                // equal layers already at or under the last: no descent needed
                if (n * last >= room - 1e-12) return List(n) { room / n }
                // last * (q^n - 1) / (q - 1) = room, for q in (1, ratio]
                fun sum(q: Double) = last * (Math.pow(q, n.toDouble()) - 1) / (q - 1)
                if (sum(ratio) < room - 1e-12) { n++; continue }
                var lo = 1.0 + 1e-9; var hi = ratio
                repeat(80) { val m = (lo + hi) / 2; if (sum(m) < room) lo = m else hi = m }
                val q = hi
                if (last * Math.pow(q, (n - 1).toDouble()) > first + 1e-9) { n++; continue }
                val seq = List(n) { i -> last * Math.pow(q, (n - 1 - i).toDouble()) }
                return if (seq.last() < minLayer - 1e-9) null else seq
            }
            return null
        }

        /** Resolution of the adaptive planner's height bins (mm): a computation grid, not a print parameter. */
        private const val BIN_MM = 0.005
        /** A face whose corners differ in height by less than this is flat (mm): a mesh tolerance. */
        private const val FLAT_MM = 0.0005
        /** Layer tops within this of a target count as on it (mm): a numeric tolerance. */
        private const val LAND_MM = 0.0005
    }
}
