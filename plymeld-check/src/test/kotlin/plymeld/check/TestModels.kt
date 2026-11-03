package plymeld.check

import java.nio.file.Files
import java.nio.file.Path

/** The repository's `test-models/` folder, found from the working directory up. */
fun testModelsDir(): Path {
    var p = Path.of("").toAbsolutePath()
    while (true) {
        val c = p.resolve("test-models")
        if (Files.isDirectory(c)) return c
        p = p.parent ?: error("test-models/ not found")
    }
}

/**
 * The two-color Benchy's two parts, from `3DBenchy-dual.3mf` in its order: the hull and its kin (filament 1), then the
 * gunwale, deck and the rest (filament 2), in the one frame both share (test-models/MANIFEST.md).
 */
fun benchyDualParts(): List<plymeld.mesh.TriangleSoup> =
    plymeld.mesh.ThreeMfReader.read(testModelsDir().resolve("3DBenchy-dual.3mf")).parts.map { it.soup }
        .also { require(it.size == 2) { "3DBenchy-dual.3mf: ${it.size} parts, not 2" } }

/** A closed box from (x0, y0, z0) to (x1, y1, z1), mm, as 12 outward triangles. */
fun testBox(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): plymeld.mesh.TriangleSoup {
    val v = arrayOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0),
        floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1))
    val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
    val out = FloatArray(12 * 9)
    for (t in 0 until 12) for (k in 0 until 3) for (d in 0 until 3) out[9 * t + 3 * k + d] = v[f[3 * t + k]][d]
    return plymeld.mesh.TriangleSoup(out, 12)
}
