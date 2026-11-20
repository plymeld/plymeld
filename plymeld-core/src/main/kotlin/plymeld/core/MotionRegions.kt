package plymeld.core

import plymeld.core.decide.Coordination
import plymeld.program.Extrude
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.Step

/**
 * A region's motion caps on the paths in it (plan.object.<key>.region.<key>.mechanism.motion.speed.max / .acceleration.max):
 * a path crossing a region's side gets a vertex where it crosses, and each segment inside carries the caps of the regions it
 * is in ([Extrude.speedMax], [Extrude.accelMax], the lowest where regions overlap). The path stays one path, a loop stays a
 * loop: no walls, seams, or travels are added, and nothing changes where the path runs or the plastic it lays. Paths printed
 * exactly as given (a calibration cell) are left alone.
 */
object MotionRegions {
    /**
     * A region in the frame the object's paths are in: x and y placed (µm), z from the object's bottom (µm), unbounded sides
     * infinite; [features], the lines it holds by their feature label (null = every line); its caps.
     */
    class Box(val key: String, val x0: Double, val x1: Double, val y0: Double, val y1: Double, val z0: Double, val z1: Double,
              val features: Set<String>?, val speedMax: Double?, val accelMax: Double?) {
        fun holds(feature: String) = features == null || feature in features
        fun contains(x: Double, y: Double, z: Double) = x >= x0 - 1e-6 && x <= x1 + 1e-6 && y >= y0 - 1e-6 && y <= y1 + 1e-6 && z >= z0 - 1e-6 && z <= z1 + 1e-6
    }

    /** The feature labels of the lines laying the faces of [facing] (Schema's region.facing). */
    fun features(facing: Set<Coordination.Facing>): Set<String> = facing.flatMap { f ->
        when (f) {
            Coordination.Facing.WALL -> listOf(Feature.OUTER_WALL.label, Feature.INNER_WALL.label)
            Coordination.Facing.FLAT_TOP, Coordination.Facing.SLOPED_TOP -> listOf(Feature.TOP.label, Feature.IRONING.label)
            Coordination.Facing.UNDERSIDE -> listOf(Feature.BOTTOM.label, Feature.BRIDGE.label, Feature.WAVE.label,
                "Wave overhang, layer above", "Wave overhang, second pass", "Wave overhang, X layer", "Wave overhang, span layer")
        }
    }.toSet()

    /** [steps] with every path the [boxes] reach capped where it is in them. */
    fun apply(steps: List<Step>, boxes: List<Box>): List<Step> {
        if (boxes.isEmpty()) return steps
        return steps.map { st -> if (st is Extrude && st.exact == null) capped(st, boxes) else st }
    }

    /**
     * [steps] of objects laid per part (MultiFilament: one object, or a plate of several) with every path in an object's block
     * (between its [ObjectStart] and [ObjectEnd]) capped by that object's boxes in [boxesOf] (by its program name); paths
     * outside every block (the purge tower's) as they are.
     */
    fun applyByObject(steps: List<Step>, boxesOf: Map<String, List<Box>>): List<Step> {
        if (boxesOf.values.all { it.isEmpty() }) return steps
        var boxes: List<Box> = emptyList()
        return steps.map { st ->
            when (st) {
                is ObjectStart -> { boxes = boxesOf[st.name].orEmpty(); st }
                is ObjectEnd -> { boxes = emptyList(); st }
                is Extrude -> if (boxes.isNotEmpty() && st.exact == null) capped(st, boxes) else st
                else -> st
            }
        }
    }

    /** [e] with a vertex where it crosses a side of [boxes], and each segment's caps; [e] itself when no segment is in one. */
    fun capped(e: Extrude, boxes: List<Box>): Extrude {
        val mine = boxes.filter { it.holds(e.feature) && (e.zs?.let { zs -> zs.min() <= it.z1 && zs.max() >= it.z0 } ?: (e.zUm >= it.z0 - 1e-6 && e.zUm <= it.z1 + 1e-6)) }
        if (mine.isEmpty()) return e
        val m = e.xy.size / 2
        val segs = if (e.closed) m else m - 1
        if (segs < 1) return e
        fun vz(i: Int) = (e.zs?.get(i) ?: e.zUm).toDouble()
        fun vw(i: Int) = (e.widths?.get(i) ?: e.widthUm).toDouble()
        fun vh(i: Int) = (e.heights?.get(i) ?: e.heightUm).toDouble()
        // the vertices, with a new one wherever a segment crosses a box's side; each new segment's caps and the segment it lies on
        val px = ArrayList<Double>(); val py = ArrayList<Double>(); val pz = ArrayList<Double>(); val pw = ArrayList<Double>(); val ph = ArrayList<Double>()
        val from = ArrayList<Int>()
        for (k in 0 until segs) {
            val a = k; val b = (k + 1) % m
            val ax = e.xy[2 * a].toDouble(); val ay = e.xy[2 * a + 1].toDouble(); val bx = e.xy[2 * b].toDouble(); val by = e.xy[2 * b + 1].toDouble()
            val ts = sortedSetOf(0.0)
            for (r in mine) for ((lo, hi, p0, d) in listOf(Side(r.x0, r.x1, ax, bx - ax), Side(r.y0, r.y1, ay, by - ay))) {
                if (d == 0.0) continue
                for (edge in listOf(lo, hi)) if (edge.isFinite()) { val t = (edge - p0) / d; if (t > 1e-9 && t < 1 - 1e-9) ts += t }
            }
            for (t in ts) {
                fun lerp(u: Double, v: Double) = u + t * (v - u)
                px += lerp(ax, bx); py += lerp(ay, by); pz += lerp(vz(a), vz(b)); pw += lerp(vw(a), vw(b)); ph += lerp(vh(a), vh(b)); from += k
            }
        }
        if (!e.closed) { px += e.xy[2 * (m - 1)].toDouble(); py += e.xy[2 * (m - 1) + 1].toDouble(); pz += vz(m - 1); pw += vw(m - 1); ph += vh(m - 1) }
        val n = px.size
        val nSegs = if (e.closed) n else n - 1
        // each segment's caps: the regions holding its middle
        val speed = DoubleArray(nSegs) { Double.NaN }; val accel = DoubleArray(nSegs) { Double.NaN }
        for (i in 0 until nSegs) {
            val j = (i + 1) % n
            val inside = mine.filter { it.contains((px[i] + px[j]) / 2, (py[i] + py[j]) / 2, (pz[i] + pz[j]) / 2) }
            inside.mapNotNull { it.speedMax }.minOrNull()?.let { speed[i] = it }
            inside.mapNotNull { it.accelMax }.minOrNull()?.let { accel[i] = it }
        }
        if (speed.all { it.isNaN() } && accel.all { it.isNaN() }) return e
        val xy = LongArray(2 * n) { q -> Math.round(if (q % 2 == 0) px[q / 2] else py[q / 2]) }
        val zs = if (e.zs == null) null else LongArray(n) { Math.round(pz[it]) }
        val ws = if (e.widths == null) null else LongArray(n) { Math.round(pw[it]) }
        val hs = if (e.heights == null) null else LongArray(n) { Math.round(ph[it]) }
        // per-segment values from the segment each new one lies on (overhang shares: one per segment from vertex k, the last
        // repeated for an open path's end vertex)
        val shares = e.overhangShare?.let { sh -> IntArray(n) { q -> sh[minOf(from[minOf(q, from.size - 1)], sh.size - 1)] } }
        val speeds = e.segmentSpeeds?.let { sp -> DoubleArray(nSegs) { q -> sp[from[q]] } }
        return Extrude(e.feature, xy, e.closed, zs?.max() ?: e.zUm, e.widthUm, hs?.max() ?: e.heightUm, e.tool, shares, e.ePerMmOverride,
            e.retractBefore, ws, null, zs, hs, e.via, speeds, e.hopOnly, if (speed.all { it.isNaN() }) null else speed, if (accel.all { it.isNaN() }) null else accel, e.ofLoop, e.scarf)
    }

    private data class Side(val lo: Double, val hi: Double, val p0: Double, val d: Double)
}
