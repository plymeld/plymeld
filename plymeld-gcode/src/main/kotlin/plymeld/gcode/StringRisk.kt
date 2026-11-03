package plymeld.gcode

import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.util.Locale

/**
 * The stringing and restart measures, read off the print program as the writer will write it:
 * - `string_risk` (mm): the length of travels made WITHOUT a retraction where a string would show: over open air between
 *   the layer's islands (the string hangs there) and across a top surface (the travel leaves or enters one; the string
 *   lies on the finished top). A travel hidden inside an island on another layer is covered by later layers: nothing.
 *   The writer's own rule says which travels retract: longer than the filament's minimum travel, or on a detour, and not
 *   one the planner kept hidden ([Extrude.retractBefore] false).
 * - `restart_starved` (mm): each visible path (an outer wall, a top or a bottom surface) started after a retraction prints
 *   its first `hardware.filament.retract.restart_lag` mm starved before the flow is back; that length, summed.
 * Islands are the layer's outer walls as [TravelRouter] sees them (grown by the detour margin).
 */
object StringRisk {
    class Result(val stringMm: Double, val openMm: Double, val topMm: Double, val unretractedTravels: Int, val visibleRestarts: Int, val restartStarvedMm: Double,
                 /** Every travel that retracts (the writer's rule), and the travel's total length (mm): the retraction decision's time and scale. */
                 val retractions: Int = 0, val travelMm: Double = 0.0) {
        fun words(): String = "strings %.0f mm of unretracted travel where a string would show (%.0f mm over open air, %.0f mm across tops; %d travels); %d visible restarts, %.0f mm printed starved after them; %d retractions"
            .format(Locale.ROOT, stringMm, openMm, topMm, unretractedTravels, visibleRestarts, restartStarvedMm, retractions)
        companion object { val NONE = Result(0.0, 0.0, 0.0, 0, 0, 0.0) }
    }

    fun sum(rs: List<Result>) = Result(rs.sumOf { it.stringMm }, rs.sumOf { it.openMm }, rs.sumOf { it.topMm }, rs.sumOf { it.unretractedTravels }, rs.sumOf { it.visibleRestarts }, rs.sumOf { it.restartStarvedMm },
        rs.sumOf { it.retractions }, rs.sumOf { it.travelMm })

    private val VISIBLE = setOf("Outer wall", "Top surface", "Bottom surface")
    private const val TOP = "Top surface"

    /**
     * [program] after routing (its detours in place); [minTravelMm] the filament's minimum travel for a retraction;
     * [lagMm] the filament's restart lag; [marginUm] the detour margin the islands are grown by. [respectHidden] false:
     * the travels the planner kept hidden retract like any other (plan.mechanism.motion.no_retract_in_island = false), so the
     * same program measures both sides of that decision (RetractionDecision).
     */
    fun measure(program: PrintProgram, minTravelMm: Double, lagMm: Double, marginUm: Long = TravelRouter.MARGIN_UM, respectHidden: Boolean = true): Result {
        val steps = program.steps
        // the layers' steps, for their islands
        val starts = steps.indices.filter { steps[it] is LayerStart }
        fun layerOf(i: Int): List<Step> {
            val k = starts.indexOfLast { it <= i }
            if (k < 0) return emptyList()
            val from = starts[k]; val to = if (k + 1 < starts.size) starts[k + 1] else steps.size
            return steps.subList(from, to)
        }
        var hulls: List<TravelRouter.Hull> = emptyList(); var hullsOf = -1
        var x = Double.NaN; var y = Double.NaN
        var lastFeature = ""
        var open = 0.0; var top = 0.0; var unretracted = 0; var restarts = 0; var retractions = 0; var travel = 0.0
        for ((i, st) in steps.withIndex()) {
            if (st is LayerStart) { hullsOf = -1; continue }
            if (st !is Extrude) continue
            if (st.exact != null) { x = Double.NaN; continue }
            val sx = st.xy[0] / 1000.0; val sy = st.xy[1] / 1000.0
            val d = if (x.isNaN()) Double.POSITIVE_INFINITY else Math.hypot(sx - x, sy - y)
            val hidden = respectHidden && !st.retractBefore
            // a speck's hop (Extrude.hops) is lifted but not retracted: an unretracted travel, no restart
            val retracts = (d > minTravelMm || st.via != null) && !hidden && !st.hops(d)
            if (!x.isNaN()) {
                travel += d
                if (retracts) { retractions++; if (st.feature in VISIBLE) restarts++ }
                else if (d > 0) {
                    if (hullsOf != i) {
                        val k = starts.indexOfLast { it <= i }
                        if (hullsOf != k) { hulls = TravelRouter.islands(layerOf(i), marginUm); hullsOf = k }
                    }
                    unretracted++
                    // the travel's legs (the detour's via points, then the start), each split into inside-island and open parts
                    var ax = x * 1000; var ay = y * 1000
                    val pts = ArrayList<Double>()
                    st.via?.let { v -> for (c in v) pts += c.toDouble() }
                    pts += st.xy[0].toDouble(); pts += st.xy[1].toDouble()
                    var k = 0
                    while (k + 1 < pts.size) {
                        val bx = pts[k]; val by = pts[k + 1]
                        val len = Math.hypot(bx - ax, by - ay) / 1000.0
                        if (len > 0) {
                            val inside = insideShare(ax, ay, bx, by, hulls) * len
                            open += len - inside
                            if (st.feature == TOP || lastFeature == TOP) top += inside
                        }
                        ax = bx; ay = by; k += 2
                    }
                }
            }
            // the head ends where the path ends
            val m = st.xy.size / 2
            val e = if (st.closed) 0 else m - 1
            x = st.xy[2 * e] / 1000.0; y = st.xy[2 * e + 1] / 1000.0
            lastFeature = st.feature
        }
        return Result(open + top, open, top, unretracted, restarts, restarts * lagMm, retractions, travel)
    }

    /** The share of the segment a -> b (µm) that lies inside any of [hulls] (their intervals merged). */
    fun insideShare(ax: Double, ay: Double, bx: Double, by: Double, hulls: List<TravelRouter.Hull>): Double {
        if (hulls.isEmpty()) return 0.0
        val iv = ArrayList<DoubleArray>()
        for (h in hulls) { val c = h.clip(ax, ay, bx, by, 0.0) ?: continue; if (c.tOut > c.tIn) iv += doubleArrayOf(c.tIn.coerceIn(0.0, 1.0), c.tOut.coerceIn(0.0, 1.0)) }
        if (iv.isEmpty()) return 0.0
        iv.sortBy { it[0] }
        var covered = 0.0; var curA = iv[0][0]; var curB = iv[0][1]
        for (j in 1 until iv.size) {
            if (iv[j][0] <= curB) curB = maxOf(curB, iv[j][1]) else { covered += curB - curA; curA = iv[j][0]; curB = iv[j][1] }
        }
        covered += curB - curA
        return covered.coerceIn(0.0, 1.0)
    }
}
