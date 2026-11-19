package plymeld.check.render

import plymeld.mesh.TriangleSoup

/**
 * `--outline-of`: a model in the g-code's coordinates ([parts], their Z less [zBaseMm]: g-code Z = mesh Z - zBase), and
 * [placedBy], how it was placed there, for the caption. Its outline at a plane is where the plane cuts its triangles.
 */
class ModelOutline(val parts: List<TriangleSoup>, val zBaseMm: Double, val placedBy: String, val name: String) {
    /**
     * The segments where the plane through (px, py, pz) facing (nx, ny, nz), in the g-code's coordinates, cuts the model:
     * each x0, y0, z0, x1, y1, z1 in those coordinates.
     */
    fun section(px: Double, py: Double, pz: Double, nx: Double, ny: Double, nz: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val d = DoubleArray(3); val x = DoubleArray(3); val y = DoubleArray(3); val z = DoubleArray(3)
        for (s in parts) for (t in 0 until s.count) {
            for (c in 0..2) {
                x[c] = s.coords[t * 9 + c * 3].toDouble(); y[c] = s.coords[t * 9 + c * 3 + 1].toDouble(); z[c] = s.coords[t * 9 + c * 3 + 2] - zBaseMm
                d[c] = (x[c] - px) * nx + (y[c] - py) * ny + (z[c] - pz) * nz
            }
            val pts = ArrayList<DoubleArray>(2)
            for (c in 0..2) {
                val e = (c + 1) % 3
                if ((d[c] < 0) != (d[e] < 0)) {
                    val u = d[c] / (d[c] - d[e])
                    pts += doubleArrayOf(x[c] + u * (x[e] - x[c]), y[c] + u * (y[e] - y[c]), z[c] + u * (z[e] - z[c]))
                }
            }
            if (pts.size == 2) out += doubleArrayOf(pts[0][0], pts[0][1], pts[0][2], pts[1][0], pts[1][1], pts[1][2])
        }
        return out
    }
}
