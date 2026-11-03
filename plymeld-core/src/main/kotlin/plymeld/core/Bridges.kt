package plymeld.core

import plymeld.geom.Hatch
import plymeld.geom.Polygon
import plymeld.geom.Polygons

/**
 * Bridge direction (bridge_angle = 0 means "auto"): plymeld's own search. For each candidate
 * angle (every 5°), hatch the bridge region and score it by the SHARE of line length that is
 * anchored at BOTH ends — an end is anchored when the point one probe distance beyond it, along
 * the line, lies on material of the layer below. Unanchored length counts against the angle.
 * Ties go to the angle whose LONGEST line is shortest: the longest unsupported span is the one
 * that sags. Not the anchored LENGTH: it differs between angles by hatching noise alone, so a
 * region anchored all round would never tie and the pick would be arbitrary (hole block:
 * -75°, lines 1.38 mm apart along the edges, too far to link: 22 bridge retractions vs
 * QIDIStudio's 1, which bridged at 45°).
 */
object Bridges {
    /** One family of sparse lines below: direction (deg), spacing, and grid offset (µm), as Hatch lays them. */
    class Family(val angleDeg: Double, val spacingUm: Long, val offsetUm: Double = 0.0)

    /**
     * Direction for an INTERNAL bridge over sparse infill: the angle whose
     * LONGEST UNSUPPORTED SPAN is shortest. A strand is held where it crosses a sparse line below
     * (the families' lines lie on Hatch's global grid, so the crossings are exact) and at an end that
     * lands on material ([below], probed one [probeUm] beyond, as [bestAngle]); between supports it
     * sags. Ties: the shorter mean span. Crossings alone ran a test model's 2.4 mm strip along its length
     * (spans of a grid's cells) where across it every strand spans 2.4 mm wall to wall.
     */
    fun internalAngle(bridge: List<Polygon>, below: List<Polygon>, families: List<Family>, spacingUm: Long, probeUm: Long): Double? {
        if (families.isEmpty()) return null
        val groups = Polygons.group(below)
        fun supported(x: Double, y: Double): Boolean {
            val px = Math.round(x); val py = Math.round(y)
            return groups.any { (o, hs) -> Polygons.contains(o, px, py) && hs.none { Polygons.contains(it, px, py) } }
        }
        var bestA: Double? = null; var bestWorst = Double.POSITIVE_INFINITY; var bestMean = Double.POSITIVE_INFINITY
        var a = 0.0
        while (a < 180.0) {
            val segs = Hatch.lines(bridge, a, spacingUm)
            var worst = 0.0; var sum = 0.0; var count = 0
            for (i in segs.indices step 4) {
                val x0 = segs[i].toDouble(); val y0 = segs[i + 1].toDouble(); val x1 = segs[i + 2].toDouble(); val y1 = segs[i + 3].toDouble()
                val len = Math.hypot(x1 - x0, y1 - y0)
                if (len == 0.0) continue
                val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
                val sup = ArrayList<Double>()
                if (supported(x0 - ux * probeUm, y0 - uy * probeUm)) sup.add(0.0)
                if (supported(x1 + ux * probeUm, y1 + uy * probeUm)) sup.add(len)
                for (f in families) {
                    val r = Math.toRadians(f.angleDeg); val nx = -Math.sin(r); val ny = Math.cos(r)
                    val du = nx * ux + ny * uy                      // how fast the line's grid coordinate changes along the strand
                    if (Math.abs(du) < 1e-9) continue
                    val v0 = nx * x0 + ny * y0; val v1 = v0 + du * len
                    val lo = Math.ceil((Math.min(v0, v1) - f.offsetUm) / f.spacingUm).toLong(); val hi = Math.floor((Math.max(v0, v1) - f.offsetUm) / f.spacingUm).toLong()
                    for (k in lo..hi) sup.add((k * f.spacingUm + f.offsetUm - v0) / du)
                }
                sup.sort()
                // the longest stretch between supports; a free end counts from the end
                var prev = 0.0; var longest = 0.0
                for (t in sup) { longest = maxOf(longest, t - prev); prev = t }
                longest = maxOf(longest, len - prev)
                worst = maxOf(worst, longest); sum += longest; count++
            }
            val mean = if (count > 0) sum / count else Double.POSITIVE_INFINITY
            if (count > 0 && (worst < bestWorst - 1.0 || (Math.abs(worst - bestWorst) <= 1.0 && mean < bestMean))) { bestA = a; bestWorst = worst; bestMean = mean }
            a += 5.0
        }
        return bestA
    }

    fun bestAngle(bridge: List<Polygon>, below: List<Polygon>, spacingUm: Long, probeUm: Long): Double = best(bridge, below, spacingUm, probeUm).first

    /**
     * Whether [bridge] is a SPAN: at its best angle every line lands on [below] at both ends (bridging
     * stays for a span between two printed supports; an underside hanging off one side is never bridged, it gets wave
     * overhangs, support, or nothing).
     */
    fun spanned(bridge: List<Polygon>, below: List<Polygon>, spacingUm: Long, probeUm: Long): Boolean = best(bridge, below, spacingUm, probeUm).second >= 1.0 - 1e-9

    /** The best angle and its score (the share of line length anchored at both ends, unanchored counting against it). */
    private fun best(bridge: List<Polygon>, below: List<Polygon>, spacingUm: Long, probeUm: Long): Pair<Double, Double> {
        var bestA = 0.0; var bestScore = Double.NEGATIVE_INFINITY; var bestMaxLen = Double.POSITIVE_INFINITY
        val groups = Polygons.group(below)
        fun supported(x: Double, y: Double): Boolean {
            val px = Math.round(x); val py = Math.round(y)
            return groups.any { (o, hs) -> Polygons.contains(o, px, py) && hs.none { Polygons.contains(it, px, py) } }
        }
        var a = 0.0
        while (a < 180.0) {
            val segs = Hatch.lines(bridge, a, spacingUm)
            var anchored = 0.0; var total = 0.0; var maxLen = 0.0
            for (i in segs.indices step 4) {
                val x0 = segs[i].toDouble(); val y0 = segs[i + 1].toDouble(); val x1 = segs[i + 2].toDouble(); val y1 = segs[i + 3].toDouble()
                val len = Math.hypot(x1 - x0, y1 - y0)
                if (len == 0.0) continue
                val ux = (x1 - x0) / len; val uy = (y1 - y0) / len
                val a0 = supported(x0 - ux * probeUm, y0 - uy * probeUm)
                val a1 = supported(x1 + ux * probeUm, y1 + uy * probeUm)
                anchored += if (a0 && a1) len else -len
                total += len; maxLen = maxOf(maxLen, len)
            }
            if (total == 0.0) { a += 5.0; continue }
            val score = anchored / total
            if (score > bestScore + 1e-9 || (Math.abs(score - bestScore) <= 1e-9 && maxLen < bestMaxLen)) {
                bestScore = score; bestA = a; bestMaxLen = maxLen
            }
            a += 5.0
        }
        return bestA to bestScore
    }
}
