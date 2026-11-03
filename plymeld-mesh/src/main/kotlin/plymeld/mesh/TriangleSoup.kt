package plymeld.mesh

/**
 * A triangle soup: `count` triangles, each 9 floats (x0 y0 z0 x1 y1 z1 x2 y2 z2)
 * laid out consecutively in [coords]. No sharing, no indices — exactly what STL
 * stores. Coordinates are in millimetres as read from the file; STL is float32,
 * so storing floats loses nothing.
 *
 * Flat by design, to keep memory small: one array, no per-triangle objects.
 */
class TriangleSoup(val coords: FloatArray, val count: Int) {
    init {
        require(coords.size >= count * 9) { "coords holds ${coords.size} floats, need ${count * 9} for $count triangles" }
    }

    fun x(tri: Int, corner: Int) = coords[tri * 9 + corner * 3]
    fun y(tri: Int, corner: Int) = coords[tri * 9 + corner * 3 + 1]
    fun z(tri: Int, corner: Int) = coords[tri * 9 + corner * 3 + 2]

    /**
     * Axis-aligned bounds as (minX, minY, minZ, maxX, maxY, maxZ); NaN-free by construction of the readers. Computed once
     * (a soup's coordinates never change after it is built; readers fill the array before constructing it) and copied
     * out: the slicer asked for it per layer and spent a tenth of a slice walking 225k triangles again.
     */
    fun bounds(): DoubleArray = (cachedBounds ?: computeBounds().also { cachedBounds = it }).clone()
    @Volatile private var cachedBounds: DoubleArray? = null

    private fun computeBounds(): DoubleArray {
        require(count > 0) { "empty soup has no bounds" }
        var minX = Double.POSITIVE_INFINITY; var minY = minX; var minZ = minX
        var maxX = Double.NEGATIVE_INFINITY; var maxY = maxX; var maxZ = maxX
        val n = count * 9
        var i = 0
        while (i < n) {
            val x = coords[i].toDouble(); val y = coords[i + 1].toDouble(); val z = coords[i + 2].toDouble()
            if (x < minX) minX = x; if (x > maxX) maxX = x
            if (y < minY) minY = y; if (y > maxY) maxY = y
            if (z < minZ) minZ = z; if (z > maxZ) maxZ = z
            i += 3
        }
        return doubleArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    /**
     * Signed volume by the divergence theorem (sum of tetrahedra to the origin),
     * in mm³. Positive for outward-facing, consistently wound closed surfaces.
     * Accumulated in doubles from float inputs.
     */
    fun signedVolume(): Double {
        var v = 0.0
        var i = 0
        val n = count * 9
        while (i < n) {
            val x0 = coords[i].toDouble(); val y0 = coords[i + 1].toDouble(); val z0 = coords[i + 2].toDouble()
            val x1 = coords[i + 3].toDouble(); val y1 = coords[i + 4].toDouble(); val z1 = coords[i + 5].toDouble()
            val x2 = coords[i + 6].toDouble(); val y2 = coords[i + 7].toDouble(); val z2 = coords[i + 8].toDouble()
            v += x0 * (y1 * z2 - z1 * y2) - y0 * (x1 * z2 - z1 * x2) + z0 * (x1 * y2 - y1 * x2)
            i += 9
        }
        return v / 6.0
    }

    /** Total surface area in mm². */
    fun area(): Double {
        var a = 0.0
        var i = 0
        val n = count * 9
        while (i < n) {
            val ux = (coords[i + 3] - coords[i]).toDouble(); val uy = (coords[i + 4] - coords[i + 1]).toDouble(); val uz = (coords[i + 5] - coords[i + 2]).toDouble()
            val vx = (coords[i + 6] - coords[i]).toDouble(); val vy = (coords[i + 7] - coords[i + 1]).toDouble(); val vz = (coords[i + 8] - coords[i + 2]).toDouble()
            val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
            a += Math.sqrt(cx * cx + cy * cy + cz * cz)
            i += 9
        }
        return a / 2.0
    }
}
