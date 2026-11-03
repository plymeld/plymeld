package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import java.util.Locale

/** An object with its final placement on the bed. */
class Placed(val name: String, val parts: List<TriangleSoup>, val heightMm: Double, val cx: Double, val cy: Double)

/**
 * Lays posed objects out on the bed: rows along X in the given order, at least [gap] mm between
 * bounding boxes, the whole arrangement centered on the bed. Bounding boxes make the gap
 * conservative (the true distance between objects is at least the box distance).
 */
object Arrange {
    /**
     * [gap] between neighbors in a row, [gapY] between rows. The objects fill rows from the bed's left and front in their
     * order, or from the right ([fromRight]) and the back ([fromBack]): one at a time, the objects printed first go on the
     * side the print head reaches least (see [headClear]).
     */
    fun rows(objects: List<Pair<String, List<TriangleSoup>>>, gap: Double, f: PoseFacts, gapY: Double = gap,
             fromRight: Boolean = false, fromBack: Boolean = false): List<Placed>? {
        val boxes = objects.map { (_, parts) -> PoseDecision.merged(parts).bounds() }
        val bedW = f.bedMaxX - f.bedMinX; val bedD = f.bedMaxY - f.bedMinY
        // shelf layout in a local frame: x along a row, rows stacked in y
        val at = ArrayList<DoubleArray>()   // local min corner of each object
        var x = 0.0; var y = 0.0; var rowD = 0.0; var maxW = 0.0
        for (b in boxes) {
            val w = b[3] - b[0]; val d = b[4] - b[1]
            if (w > bedW || d > bedD) return null
            if (x > 0 && x + w > bedW) { y += rowD + gapY; x = 0.0; rowD = 0.0 }
            at.add(doubleArrayOf(x, y)); x += w + gap; rowD = maxOf(rowD, d); maxW = maxOf(maxW, x - gap)
        }
        val totalD = y + rowD
        if (totalD > bedD) return null
        // filling from the right / back: the local frame mirrored
        for ((i, b) in boxes.withIndex()) {
            if (fromRight) at[i][0] = maxW - at[i][0] - (b[3] - b[0])
            if (fromBack) at[i][1] = totalD - at[i][1] - (b[4] - b[1])
        }
        val ox = f.bedMinX + (bedW - maxW) / 2; val oy = f.bedMinY + (bedD - totalD) / 2
        return objects.mapIndexed { i, (name, parts) ->
            val b = boxes[i]
            val t = doubleArrayOf(ox + at[i][0] - b[0], oy + at[i][1] - b[1], -b[2])
            val moved = parts.map { Pose("arranged", Pose.IDENTITY, t).apply(it) }
            Placed(name, moved, b[5] - b[2], ox + at[i][0] + (b[3] - b[0]) / 2, oy + at[i][1] + (b[4] - b[1]) / 2)
        }
    }

    /** Objects left where they are (placement locked): heights and centers from their own boxes. */
    fun asIs(objects: List<Pair<String, List<TriangleSoup>>>): List<Placed> = objects.map { (name, parts) ->
        val b = PoseDecision.merged(parts).bounds(); Placed(name, parts, b[5] - b[2], (b[0] + b[3]) / 2, (b[1] + b[4]) / 2)
    }

    /** Why a locked placement cannot be printed on this bed (an object's box outside it), or null. */
    fun outsideBed(objects: List<Placed>, f: PoseFacts): String? {
        for (o in objects) {
            val b = PoseDecision.merged(o.parts).bounds()
            if (b[0] < f.bedMinX - 1e-6 || b[1] < f.bedMinY - 1e-6 || b[3] > f.bedMaxX + 1e-6 || b[4] > f.bedMaxY + 1e-6)
                return "'%s' lies at x %.1f..%.1f, y %.1f..%.1f, outside the bed (x %.0f..%.0f, y %.0f..%.0f)".format(Locale.ROOT,
                    o.name, b[0], b[3], b[1], b[4], f.bedMinX, f.bedMaxX, f.bedMinY, f.bedMaxY)
        }
        return null
    }

    /** Smallest gap between the objects' bounding boxes (0 when they overlap). */
    fun minGap(objects: List<Placed>): Double {
        var g = Double.MAX_VALUE
        val bs = objects.map { PoseDecision.merged(it.parts).bounds() }
        for (i in bs.indices) for (j in i + 1 until bs.size) {
            val dx = maxOf(0.0, maxOf(bs[i][0], bs[j][0]) - minOf(bs[i][3], bs[j][3]))
            val dy = maxOf(0.0, maxOf(bs[i][1], bs[j][1]) - minOf(bs[i][4], bs[j][4]))
            g = minOf(g, Math.hypot(dx, dy))
        }
        return g
    }

    /** How far the print head reaches from the nozzle towards the machine's -X, +X, -Y, +Y (mm), seen from below. */
    class Reach(val minusX: Double, val plusX: Double, val minusY: Double, val plusY: Double)

    /**
     * One at a time with the head's outline: while object k prints (its first layer reaching [brimMm] beyond it), the
     * head covers its box grown by the head's reach each way, and must not meet any object printed before it. The first
     * pair, in print order [objects], that breaks this, described; null when none does. Boxes stand in for outlines.
     */
    fun headClear(objects: List<Placed>, reach: Reach, brimMm: Double): String? {
        val bs = objects.map { PoseDecision.merged(it.parts).bounds() }
        for (k in bs.indices) {
            val b = bs[k]
            val x0 = b[0] - brimMm - reach.minusX; val x1 = b[3] + brimMm + reach.plusX
            val y0 = b[1] - brimMm - reach.minusY; val y1 = b[4] + brimMm + reach.plusY
            for (j in 0 until k) {
                val o = bs[j]
                if (o[3] > x0 && o[0] < x1 && o[4] > y0 && o[1] < y1)
                    return "printing '%s', the head (x %.1f..%.1f, y %.1f..%.1f with the brim) would reach '%s', printed before it (x %.1f..%.1f, y %.1f..%.1f)".format(Locale.ROOT,
                        objects[k].name, x0, x1, y0, y1, objects[j].name, o[0], o[3], o[1], o[4])
            }
        }
        return null
    }

    fun describe(p: List<Placed>): String = p.joinToString("; ") { "%s at (%.1f, %.1f), %.1f mm tall".format(Locale.ROOT, it.name, it.cx, it.cy, it.heightMm) }
}
