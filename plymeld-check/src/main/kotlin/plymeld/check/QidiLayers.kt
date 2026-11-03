package plymeld.check

import plymeld.mesh.LayerPlan

/**
 * QIDIStudio's layer heights, as measured from its output — for tests that compare plymeld's
 * geometry with QIDIStudio's files layer by layer. Not how plymeld plans layers (that is
 * LayerPlan.uniform / adaptive / composite): plymeld does not copy another slicer's rules
 *.
 */
object QidiLayers {
    /**
     * QIDIStudio's precise_z_height: the print ends exactly at the model's top. Behavior measured from
     * QIDIStudio on test blocks (test-models/MANIFEST.md, "precise_z_height probe"):
     *  - layer count = first layer + the rest rounded to the NEAREST whole layer; a leftover of
     *    exactly half a layer gets its own layer. The tie is judged on the height as stored
     *    (float32 STL: 16.5 is exact -> kept; 2.1 is 2.0999999 -> dropped);
     *  - the difference is spread evenly over the last [ADJUSTED] layers; the first layer is
     *    never changed, and a plan of [ADJUSTED] layers or fewer is left as it is
     *    (a 0.9 mm block prints 0.8 tall, a 1.15 mm block ends in five 0.19 mm layers).
     */
    fun precise(zMin: Double, zMax: Double, firstLayer: Double, layerHeight: Double): LayerPlan {
        require(firstLayer > 0 && layerHeight > 0) { "layer heights must be positive" }
        val rest = (zMax - zMin - firstLayer) / layerHeight
        if (rest < 0) return LayerPlan.fixed(zMin, zMax, firstLayer, layerHeight)
        val whole = Math.floor(rest)
        // 1e-8 layers absorbs double round-off in the division (16.3 / 0.2 = 81.4999...) while
        // staying far below float32 spacing (2.1f is 5e-7 layers under the tie).
        val n = 1 + whole.toInt() + (if (rest - whole >= 0.5 - 1e-8) 1 else 0)
        val t = DoubleArray(n) { i -> zMin + firstLayer + i * layerHeight }
        if (n > ADJUSTED) {
            val k0 = n - ADJUSTED
            val base = t[k0 - 1]
            val hAdj = (zMax - base) / ADJUSTED
            for (j in 0 until ADJUSTED) t[k0 + j] = base + (j + 1) * hAdj
            t[n - 1] = zMax
        }
        val s = DoubleArray(n) { i -> t[i] - (t[i] - (if (i == 0) zMin else t[i - 1])) / 2 }
        return LayerPlan(t, s)
    }

    /** How many top layers precise_z_height re-spaces (5 in every QIDIStudio probe; probed at 0.2 mm layers only). */
    const val ADJUSTED = 5
}
