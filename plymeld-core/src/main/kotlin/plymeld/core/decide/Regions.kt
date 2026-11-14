package plymeld.core.decide

import java.util.Locale

/**
 * A region of an object (`plan.object.<key>.region.<key>`), selected by geometry, never by index: a box in the model's own
 * coordinates ([box]: x0, x1, y0, y1, z0, z1 mm), a height range as printed ([fromMm], [toMm], from the object's bottom), the
 * lines it holds by the faces they lay ([facing]), or several of these together; a line is in it where every one given holds.
 * The values set under it apply to the lines in it: the motion caps ([speedMax], [accelMax]; Schema.REGION_VALUES); and its
 * support rule ([support]: [ALWAYS], every downward face in it supported, or [NEVER], none; null = the object's own rule).
 */
class Region(val key: String, val box: DoubleArray?, val fromMm: Double?, val toMm: Double?, val facing: Set<Coordination.Facing>?,
             val speedMax: Double?, val accelMax: Double?, val source: String, val support: String? = null,
             /**
              * The object's section left open (`model.object.<key>.section`, faces = "open"): not a region of values but the box
              * the object's lines are laid in, posed and carried to the slice as a region is (SectionClip).
              */
             val section: Boolean = false) {
    companion object {
        const val ALWAYS = "always"
        const val NEVER = "never"
    }

    init {
        require(box != null || fromMm != null || toMm != null || facing != null) {
            "region '$key' ($source) selects nothing: give it a box (min and max), a height range (from, to), or the faces (facing)" }
        require(box == null || (box[0] < box[1] && box[2] < box[3] && box[4] < box[5])) {
            "region '$key' ($source): each of the box's min must be below its max (x %s..%s, y %s..%s, z %s..%s)".format(Locale.ROOT,
                *box!!.map { Control.fmt(it) }.toTypedArray()) }
        require(fromMm == null || toMm == null || fromMm < toMm) { "region '$key' ($source): from (${Control.fmt(fromMm!!)} mm) must be below to (${Control.fmt(toMm!!)} mm)" }
        require(section || speedMax != null || accelMax != null || support != null) {
            "region '$key' ($source) sets nothing for the lines in it: give it a value (${plymeld.config.Schema.REGION_VALUES.joinToString { "region.$key.${it.removePrefix("plan.")}" }}) or a support rule (region.$key.support)" }
        require(support == null || support == ALWAYS || support == NEVER) { "region '$key' ($source): support is \"always\" or \"never\", not \"$support\"" }
        require(support == null || facing == null) {
            "region '$key' ($source): a support rule holds the region's downward faces, so facing is not read with it; give the support rule a region of its own" }
        require(speedMax == null || speedMax > 0) { "region '$key' ($source): speed.max must be above 0" }
        require(accelMax == null || accelMax > 0) { "region '$key' ($source): acceleration.max must be above 0" }
    }

    /** What selects it, in words. */
    val words: String get() = listOfNotNull(
        box?.let { b -> "x %s..%s, y %s..%s, z %s..%s mm in the model's own coordinates".format(Locale.ROOT, *b.map { Control.fmt(it) }.toTypedArray()) },
        if (fromMm == null && toMm == null) null else "printed from %s to %s".format(Locale.ROOT, fromMm?.let { Control.fmt(it) + " mm" } ?: "the bottom", toMm?.let { Control.fmt(it) + " mm" } ?: "the top"),
        facing?.let { f -> "the lines of its " + f.joinToString { it.words } }).joinToString("; ")

    /**
     * The region in a pose (its rotation; the posed model's bounding box is [bounds], as TriangleSoup.bounds gives it): its box
     * turned with the model, as the bounding box of the turned corners, from the posed model's corner and bottom, cut to the
     * height range; with no box, everything at those heights. Refused when the region lies outside the posed object.
     */
    fun posed(pose: Pose, bounds: DoubleArray): Posed {
        val inf = Double.POSITIVE_INFINITY
        var x0 = -inf; var x1 = inf; var y0 = -inf; var y1 = inf; var z0 = -inf; var z1 = inf
        if (box != null) {
            val r = pose.r
            x0 = inf; x1 = -inf; y0 = inf; y1 = -inf; z0 = inf; z1 = -inf
            for (c in 0 until 8) {
                val x = if (c and 1 == 0) box[0] else box[1]; val y = if (c and 2 == 0) box[2] else box[3]; val z = if (c and 4 == 0) box[4] else box[5]
                val px = r[0] * x + r[1] * y + r[2] * z + pose.t[0]; val py = r[3] * x + r[4] * y + r[5] * z + pose.t[1]; val pz = r[6] * x + r[7] * y + r[8] * z + pose.t[2]
                x0 = minOf(x0, px - bounds[0]); x1 = maxOf(x1, px - bounds[0]); y0 = minOf(y0, py - bounds[1]); y1 = maxOf(y1, py - bounds[1]); z0 = minOf(z0, pz - bounds[2]); z1 = maxOf(z1, pz - bounds[2])
            }
        }
        fromMm?.let { z0 = maxOf(z0, it) }
        toMm?.let { z1 = minOf(z1, it) }
        val w = bounds[3] - bounds[0]; val d = bounds[4] - bounds[1]; val h = bounds[5] - bounds[2]
        require(x0 < w && x1 > 0 && y0 < d && y1 > 0 && z0 <= h && z1 >= 0 && z0 <= z1) {
            "region '$key' ($source, $words) lies outside the object in pose '${pose.label}' (%s x %s x %s mm): it holds no line".format(Locale.ROOT,
                Control.fmt(w), Control.fmt(d), Control.fmt(h)) }
        // within the object (nothing beyond it is the object's): an unbounded side is the object's own
        require(!section || z0 <= 1e-6) { "$source: a section left open starts at the model's bottom (its box's lowest side at or below it; here %s mm above): what is under it would not be printed. A slice from higher up is a section with faces = \"closed\"".format(Locale.ROOT, Control.fmt(z0)) }
        return Posed(key, Coordination.Volume.Region(maxOf(z0, 0.0), minOf(z1, h), maxOf(x0, 0.0), minOf(x1, w), maxOf(y0, 0.0), minOf(y1, d)),
            facing, speedMax, accelMax, source, support, section)
    }

    /** The region in its object's pose: [region] from the posed object's corner and bottom, within the object's bounds. */
    class Posed(val key: String, val region: Coordination.Volume.Region, val facing: Set<Coordination.Facing>?, val speedMax: Double?, val accelMax: Double?, val source: String,
                val support: String? = null, val section: Boolean = false) {
        override fun equals(other: Any?) = other is Posed && key == other.key && region == other.region && facing == other.facing && speedMax == other.speedMax && accelMax == other.accelMax &&
            support == other.support && section == other.section
        override fun hashCode() = key.hashCode() * 31 + region.hashCode()
        override fun toString() = "region '$key'"
    }
}
