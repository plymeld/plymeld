package plymeld.core.decide

import java.util.Locale

/**
 * A planned insert (plan.insert.<key>): a socket the print pauses at so an object (a steel
 * ball, a magnet, a nut) is placed in it, the next layer planned onto the object's top. [minX]..[maxZ] is the socket's
 * box in the model's own coordinates (as a locked support spot's); [topMm] the insert's top once placed, in the same z.
 *
 * What it does: the pause comes before the first layer whose bottom is at or above the top ([PauseLock.Above]); the
 * faces of the model inside the box above the top are counted as covered by the survey (no unsupported area, no
 * support proposed in the socket); the slice lays that layer's bottom over the socket onto the insert, as a bottom
 * surface rather than a bridge, and generates no support in the socket. The pose must be locked and keep the socket's
 * opening upward ([posed] refuses a pose that tilts the model).
 */
class Insert(val key: String, val minX: Double, val maxX: Double, val minY: Double, val maxY: Double, val minZ: Double, val maxZ: Double,
             val topMm: Double, val source: String) {
    init {
        require(minX < maxX && minY < maxY && minZ < maxZ) { "insert '$key' ($source): each min must be below its max (x $minX..$maxX, y $minY..$maxY, z $minZ..$maxZ)" }
        require(topMm >= minZ - 1e-9 && topMm <= maxZ + 1e-9) { "insert '$key' ($source): its top (%.3f mm) must lie within the box's z %.3f..%.3f mm".format(Locale.ROOT, topMm, minZ, maxZ) }
    }

    val words: String get() = "x %s..%s, y %s..%s, z %s..%s mm, top at %s mm, in the model's own coordinates".format(Locale.ROOT,
        Control.fmt(minX), Control.fmt(maxX), Control.fmt(minY), Control.fmt(maxY), Control.fmt(minZ), Control.fmt(maxZ), Control.fmt(topMm))

    /** The insert in a pose: its box as a region from the posed model's corner and bottom, and its top from the posed bottom. */
    class Posed(val key: String, val region: Coordination.Volume.Region, val topMm: Double, val source: String) {
        /** Whether a face centered at (x, y, z) (the region's frame) lies in the socket at or above the insert's top: covered by it. */
        fun covers(x: Double, y: Double, z: Double) = z >= topMm - 1e-6 && region.contains(x, y, z)
        val words: String get() = "${region.words}, top at %s mm from the object's bottom".format(Locale.ROOT, Control.fmt(topMm))
        override fun equals(other: Any?) = other is Posed && key == other.key && region == other.region && Math.abs(topMm - other.topMm) < 1e-9
        override fun hashCode() = key.hashCode() * 31 + region.hashCode()
    }

    /**
     * The insert in [pose] (its rotation; the posed model's bounding box is [bounds]): refused when the pose tilts the
     * model, since the socket must open upward for the object to be placed and the layer over it to land on its top.
     */
    fun posed(pose: Pose, bounds: DoubleArray): Posed {
        val r = pose.r
        require(r[8] > 0.9999) { "insert '$key' ($source): the pose '${pose.label}' tilts the model, so the socket would not open upward; lock a pose that keeps it upright (plan.pose = \"as-given\", or plan.object.<key>.pose.turn turning about z only)" }
        var x0 = Double.POSITIVE_INFINITY; var x1 = Double.NEGATIVE_INFINITY; var y0 = x0; var y1 = x1; var z0 = x0; var z1 = x1
        for (c in 0 until 8) {
            val x = if (c and 1 == 0) minX else maxX; val y = if (c and 2 == 0) minY else maxY; val z = if (c and 4 == 0) minZ else maxZ
            val px = r[0] * x + r[1] * y + r[2] * z + pose.t[0]; val py = r[3] * x + r[4] * y + r[5] * z + pose.t[1]; val pz = r[6] * x + r[7] * y + r[8] * z + pose.t[2]
            x0 = minOf(x0, px); x1 = maxOf(x1, px); y0 = minOf(y0, py); y1 = maxOf(y1, py); z0 = minOf(z0, pz); z1 = maxOf(z1, pz)
        }
        val top = r[8] * topMm + pose.t[2] - bounds[2]
        return Posed(key, Coordination.Volume.Region(z0 - bounds[2], z1 - bounds[2], x0 - bounds[0], x1 - bounds[0], y0 - bounds[1], y1 - bounds[1]), top, source)
    }
}
