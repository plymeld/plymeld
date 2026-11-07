package plymeld.core.decide

import plymeld.mesh.LayerPlan
import java.util.Locale

/**
 * One object's techniques by height: bands measured
 * from the object's bottom (mm), each built with its technique, in order, covering the object. One
 * band over the whole object is the simplest plan. Layers land on every band boundary (the hand-over).
 */
class TechniquePlan(val bands: List<Banded>,
                   /** The layer height (mm) of the flat bands of one height this plan was chosen with; null = the settings' (plan.technique.flat.layer.height). */
                   val layerHeightMm: Double? = null,
                   /** A curved top band whose start plymeld works out once the object is posed (CurvedDepth): `band.<n>.depth = "auto"`. */
                   val curvedDepthAuto: Boolean = false,
                   /**
                    * Support beside the bands ([SupportPlan]): the spots supported, locked or chosen, each with
                    * its column's print order; null = the switch decides (plan.support.enabled), as before.
                    */
                   val support: SupportPlan? = null,
                   /** Planned inserts in the posed object: sockets the print pauses at, their roofs laid onto the inserts. */
                   val inserts: List<Insert.Posed> = emptyList(),
                   /**
                    * Curved islands: caps whose islands, above their start,
                    * are printed in their own layers with the shifted warp, beside the bands' flat layers.
                    */
                   val islands: List<CurvedIsland> = emptyList(),
                   /** The object's regions in its pose (plan.object.<key>.region.<key>): the values set for the lines in each. */
                   val regions: List<Region.Posed> = emptyList()) {
    /**
     * One band. [follow], for flat layers only: its layers follow the surfaces, each as thick as the surfaces it
     * crosses allow within the tool's thinnest and thickest layer (what adaptive layers were); false = one height, the plan's
     * [layerHeightMm] (what uniform layers were).
     */
    class Banded(val lo: Double, val hi: Double, val technique: PrintTechnique, val follow: Boolean = false) {
        init { require(!follow || technique == PrintTechnique.FLAT_LAYERS) { "only flat layers follow the surfaces (got ${technique.short})" } }
        /** One height: a flat band whose layers are all [TechniquePlan.layerHeightMm] thick. */
        val oneHeight: Boolean get() = technique == PrintTechnique.FLAT_LAYERS && !follow
        val short: String get() = if (follow) FOLLOW_SHORT else technique.short
        fun same(o: Banded) = technique == o.technique && follow == o.follow
    }

    init {
        require(bands.isNotEmpty() && bands.first().lo == 0.0 && bands.zipWithNext().all { (a, b) -> Math.abs(a.hi - b.lo) < 1e-9 }) { "bands must start at the bottom and follow each other" }
    }

    val techniques: List<PrintTechnique> get() = bands.map { it.technique }.distinct()
    /** Flat layers of one height throughout (what uniform layers were). */
    val oneHeight: Boolean get() = bands.all { it.oneHeight }
    /** Flat layers following the surfaces throughout (what adaptive layers were). */
    val followsThroughout: Boolean get() = bands.all { it.follow }
    /** Some band's layers follow the surfaces. */
    val follows: Boolean get() = bands.any { it.follow }
    /** The technique when there is only one and every band uses it alike (null for flat bands of one height and following ones mixed). */
    val single: PrintTechnique? get() = techniques.singleOrNull()?.takeIf { bands.all { b -> b.same(bands[0]) } }
    val label: String get() = (if (single != null) labelOf(bands[0]) else bands.joinToString("; ") { b ->
        (if (b.hi.isInfinite()) "%.2f mm to the top".format(Locale.ROOT, b.lo) else "%.2f-%.2f mm".format(Locale.ROOT, b.lo, b.hi)) + " " + b.short
    }) + heightNote + supportNote
    val short: String get() = (if (single != null) bands[0].short else bands.joinToString("+") { it.short }) + (layerHeightMm?.let { "@" + Control.fmt(it) } ?: "") + (support?.short ?: "")
    private val heightNote: String get() = if (layerHeightMm == null || followsThroughout) "" else ", flat layers ${Control.fmt(layerHeightMm)} mm"
    private val supportNote: String get() = support?.let { s -> if (s.any) "; support under ${s.spots.size} spot(s)" + (if (s.locked.isNotEmpty()) " (${s.locked.size} locked)" else "") else "; no support" } ?: ""
    /** The same bands with the flat bands of one height [h] mm thick (none when every band follows the surfaces: the height would mean nothing). */
    fun at(h: Double?) = TechniquePlan(bands, if (followsThroughout) null else h, curvedDepthAuto, support, inserts, islands, regions)
    /** The same plan with support [s] beside it. */
    fun withSupport(s: SupportPlan?) = TechniquePlan(bands, layerHeightMm, curvedDepthAuto, s, inserts, islands, regions)
    /** The same plan with the planned inserts [ins]. */
    fun withInserts(ins: List<Insert.Posed>) = TechniquePlan(bands, layerHeightMm, curvedDepthAuto, support, ins, islands, regions)
    /** The same plan with the curved islands [isl]. */
    fun withIslands(isl: List<CurvedIsland>) = TechniquePlan(bands, layerHeightMm, curvedDepthAuto, support, inserts, isl, regions)
    /** The same plan with the object's regions [rs], posed. */
    fun withRegions(rs: List<Region.Posed>) = TechniquePlan(bands, layerHeightMm, curvedDepthAuto, support, inserts, islands, rs)

    /**
     * The bands as the coordinator's portions: z bands, each with its technique, flat
     * layers' way as the variant ([FOLLOW_SHORT] where they follow the surfaces), and the layer detail in words (the
     * coordinator does not know layers: the technique says them); then support's spots beside them, the air
     * under each overhang region, in the print order chosen for its column.
     */
    fun portions(): List<Coordination.Portion> = bands.map { b ->
        Coordination.Portion(Coordination.Volume.ZBand(b.lo, b.hi), Coordination.Step.DEPOSIT, b.technique, if (b.follow) FOLLOW_SHORT else null,
            when {
                b.follow -> "each layer as thick as the surfaces it crosses allow"
                b.oneHeight -> "one layer height, " + (layerHeightMm?.let { Control.fmt(it) + " mm" } ?: "the settings'")
                else -> null
            })
    } + support?.spots.orEmpty().map { s ->
        Coordination.Portion(Coordination.Volume.Under(s.region, s.standsOn), Coordination.Step.DEPOSIT, PrintTechnique.SUPPORT, null,
            (if (s.locked) "locked spot '${s.id}': every downward face in it supported" else "the faces too steep to hold themselves up in it supported") +
                ", a tree column %.2f mm tall".format(Locale.ROOT, s.columnMm), s.order)
    }

    /** The bands in absolute height for a model from [zMin] to [zMax], as the layer planner takes them. */
    fun absolute(zMin: Double, zMax: Double): List<LayerPlan.Band> = bands.map { LayerPlan.Band(zMin + it.lo, minOf(zMax, zMin + it.hi), !it.oneHeight) }
        .filter { it.hi > it.lo + 1e-9 }
        .let { bs -> bs.mapIndexed { i, b -> if (i == bs.size - 1) LayerPlan.Band(b.lo, zMax, b.adaptive) else b } }

    override fun equals(other: Any?) = other is TechniquePlan && other.bands.size == bands.size && other.layerHeightMm == layerHeightMm && other.support == support && other.inserts == inserts && other.regions == regions &&
        bands.zip(other.bands).all { (a, b) -> a.same(b) && Math.abs(a.lo - b.lo) < 1e-9 && (a.hi == b.hi || Math.abs(a.hi - b.hi) < 1e-9) }
    override fun hashCode() = bands.fold(layerHeightMm.hashCode() * 31 + support.hashCode() + inserts.hashCode()) { h, b -> 31 * (31 * h + b.technique.hashCode()) + b.follow.hashCode() }

    companion object {
        /** A band following the surfaces, in a plan's short form. */
        const val FOLLOW_SHORT = "flat-follow"

        /** What [b]'s technique is, in words. */
        fun labelOf(b: Banded): String = when {
            b.follow -> "flat layers following the surfaces: each layer as thick as the surfaces it crosses allow"
            b.oneHeight -> "flat layers of one height: one layer height throughout, everything below a height before anything above it"
            else -> b.technique.label
        }

        /** [s] over the whole object ([follow]: flat layers following the surfaces). */
        fun whole(s: PrintTechnique, follow: Boolean = false) = TechniquePlan(listOf(Banded(0.0, Double.POSITIVE_INFINITY, s, follow)))

        /** Flat layers following the surfaces in the given bands (from the object's bottom, mm), flat layers of one height everywhere else. */
        fun mixed(bands: List<Pair<Double, Double>>): TechniquePlan {
            val flat = PrintTechnique.FLAT_LAYERS
            val out = ArrayList<Banded>(); var z = 0.0
            for ((lo, hi) in bands.sortedBy { it.first }) {
                if (lo > z + 1e-9) out.add(Banded(z, lo, flat))
                out.add(Banded(maxOf(lo, z), hi, flat, follow = true)); z = hi
            }
            if (!z.isInfinite()) out.add(Banded(z, Double.POSITIVE_INFINITY, flat))
            // neighboring bands alike become one
            val merged = ArrayList<Banded>()
            for (b in out) if (merged.isNotEmpty() && merged.last().same(b)) merged[merged.size - 1] = Banded(merged.last().lo, b.hi, b.technique, b.follow) else merged.add(b)
            return TechniquePlan(merged)
        }
    }
}

/**
 * What a survey found: a band of the object a way of laying it is suited for, or might be, with the reason and
 * what it expects to change. Flat layers' survey proposes bands whose layers follow the surfaces ([follow]).
 */
class Proposal(val technique: PrintTechnique, val lo: Double, val hi: Double, val suited: Boolean, val reason: String, val layersChange: Double, val follow: Boolean = false) {
    fun describe() = "%s: %.2f-%.2f mm %s — %s".format(Locale.ROOT, if (follow) TechniquePlan.FOLLOW_SHORT else technique.short, lo, hi, if (suited) "suited" else "might be suited", reason)
}

/**
 * Each technique's survey of a posed model: quick,
 * from the mesh and the settings, never from slicing.
 */
object Surveys {
    /**
     * Flat layers' survey, how it finds its portions (what adaptive layers' survey was): the heights
     * where layers following the surfaces would differ from flat layers of one height [layerHeight] (mm), judged one
     * thickest layer at a time from [lim]: runs of windows where they come to fewer whole layers than one height (thicker:
     * the faces allow more) or more (thinner: shallow slopes ask for less), each proposed when it changes the layer count by
     * at least one layer.
     */
    fun flat(lim: LayerPlan.Limits, layerHeight: Double, maxLayer: Double): List<Proposal> {
        val height = lim.zMax - lim.zMin
        if (height <= 0) return emptyList()
        val n = maxOf(1, Math.ceil(height / maxLayer).toInt())
        val w = height / n
        val limit = DoubleArray(n) { k -> minOf(maxLayer, lim.over(lim.zMin + k * w, lim.zMin + (k + 1) * w)) }
        // a window differs only when its layers would come to another whole number of layers in it
        // than one height (a hair over the layer height is not thicker): thicker = fewer, thinner = more
        fun layersIn(h: Double) = Math.ceil(w / h - 1e-9)
        fun cls(h: Double) = Math.signum(layersIn(layerHeight) - layersIn(h)).toInt()
        val out = ArrayList<Proposal>()
        var k = 0
        while (k < n) {
            val c = cls(limit[k]); var e = k
            while (e + 1 < n && cls(limit[e + 1]) == c) e++
            if (c != 0) {
                val len = (e - k + 1) * w
                val followLayers = (k..e).sumOf { w / limit[it] }
                val change = followLayers - len / layerHeight
                if (Math.abs(change) >= 1) {
                    val lo = k * w; val hi = (e + 1) * w
                    out.add(Proposal(PrintTechnique.FLAT_LAYERS, lo, hi, true,
                        if (c > 0) "the faces allow layers up to %.3f mm (layer height %.3f): about %.0f fewer layers".format(Locale.ROOT, (k..e).maxOf { limit[it] }, layerHeight, -change)
                        else "shallow slopes ask for layers down to %.3f mm (layer height %.3f): about %.0f more layers, smaller stair-steps".format(Locale.ROOT, (k..e).minOf { limit[it] }, layerHeight, change),
                        change, follow = true))
                }
            }
            k = e + 1
        }
        return out
    }
}
