package plymeld.check.render

/**
 * Moves read from a file, flat: one entry per move (an arc's chords each an entry) in primitive arrays, in print order.
 * [kind]: [EXTRUDE], [TRAVEL], or [WIPE] (a travel that retracts as it goes).
 */
class MoveSet {
    var n = 0; private set
    var x0 = DoubleArray(1024); var y0 = DoubleArray(1024); var z0 = DoubleArray(1024)
    var x1 = DoubleArray(1024); var y1 = DoubleArray(1024); var z1 = DoubleArray(1024)
    /** Filament fed (mm); 0 for a travel. */
    var e = DoubleArray(1024)
    var kind = ByteArray(1024)
    var feature = IntArray(1024); var obj = IntArray(1024); var tool = IntArray(1024); var layer = IntArray(1024)
    /** Declared width and height (NaN: none), part fan 0..1, nozzle temperature (C, NaN: none set), speed (mm/s, NaN: none set). */
    var wDecl = FloatArray(1024); var hDecl = FloatArray(1024); var fan = FloatArray(1024); var temp = FloatArray(1024); var speed = FloatArray(1024)

    private fun grow() {
        val c = x0.size * 2
        x0 = x0.copyOf(c); y0 = y0.copyOf(c); z0 = z0.copyOf(c); x1 = x1.copyOf(c); y1 = y1.copyOf(c); z1 = z1.copyOf(c); e = e.copyOf(c)
        kind = kind.copyOf(c); feature = feature.copyOf(c); obj = obj.copyOf(c); tool = tool.copyOf(c); layer = layer.copyOf(c)
        wDecl = wDecl.copyOf(c); hDecl = hDecl.copyOf(c); fan = fan.copyOf(c); temp = temp.copyOf(c); speed = speed.copyOf(c)
    }

    fun add(k: Byte, ax: Double, ay: Double, az: Double, bx: Double, by: Double, bz: Double, fed: Double, s: ScanState) {
        if (n == x0.size) grow()
        x0[n] = ax; y0[n] = ay; z0[n] = az; x1[n] = bx; y1[n] = by; z1[n] = bz; e[n] = fed; kind[n] = k
        feature[n] = s.feature; obj[n] = s.obj; tool[n] = s.tool; layer[n] = s.layer
        wDecl[n] = s.width.toFloat(); hDecl[n] = s.height.toFloat(); fan[n] = s.fan.toFloat(); temp[n] = s.temp.toFloat()
        speed[n] = (s.f / 60.0).toFloat()
        n++
    }

    fun lengthXY(i: Int): Double = Math.hypot(x1[i] - x0[i], y1[i] - y0[i])

    companion object { const val EXTRUDE: Byte = 0; const val TRAVEL: Byte = 1; const val WIPE: Byte = 2 }
}

/** Points of note read from a file: retractions and tool changes, each where the head stood. */
class MarkSet {
    var n = 0; private set
    var kind = ByteArray(64); var x = DoubleArray(64); var y = DoubleArray(64); var z = DoubleArray(64)
    var layer = IntArray(64); var tool = IntArray(64); var obj = IntArray(64); var feature = IntArray(64)
    fun add(k: Byte, px: Double, py: Double, pz: Double, t: Int, s: ScanState) {
        if (n == kind.size) { val c = n * 2; kind = kind.copyOf(c); x = x.copyOf(c); y = y.copyOf(c); z = z.copyOf(c); layer = layer.copyOf(c); tool = tool.copyOf(c); obj = obj.copyOf(c); feature = feature.copyOf(c) }
        kind[n] = k; x[n] = px; y[n] = py; z[n] = pz; layer[n] = s.layer; tool[n] = t; obj[n] = s.obj; feature[n] = s.feature
        n++
    }
    companion object { const val RETRACT: Byte = 0; const val TOOL_CHANGE: Byte = 1 }
}

/** Reads the moves of some layers of an indexed file, each run of consecutive layers from its own offset. */
object MoveReader {
    /**
     * The moves and marks of [layers] (0-based) of [index]'s file that [keep] passes (start and end points, mm), in print
     * order; extrusion before the first layer is never read.
     */
    fun read(index: GcodeIndex, layers: Collection<Int>, keep: ((Double, Double, Double, Double, Double, Double) -> Boolean)? = null): Pair<MoveSet, MarkSet> {
        val moves = MoveSet(); val marks = MarkSet()
        val sorted = layers.filter { it in index.layers.indices }.distinct().sorted()
        if (sorted.isEmpty()) return moves to marks
        // runs of consecutive layers, each read in one go
        val runs = ArrayList<IntRange>()
        var a = sorted[0]; var b = a
        for (k in sorted.drop(1)) { if (k == b + 1) b = k else { runs += a..b; a = k; b = k } }
        runs += a..b
        for (r in runs) {
            val first = index.layers[r.first]
            val scan = GcodeScan(index.features, index.objects, index.layerMarkers)
            scan.st.set(first.before)
            if (!index.layerMarkers) {
                scan.st.layer = r.first - 1
                scan.startInferred(r.first - 1, if (r.first == 0) Double.NaN else index.layers[r.first - 1].maxZ)
            }
            val sink = object : ScanSink {
                fun inRun(s: ScanState) = s.layer in r
                override fun extrude(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, e: Double, s: ScanState) {
                    if (inRun(s) && (keep == null || keep(x0, y0, z0, x1, y1, z1))) moves.add(MoveSet.EXTRUDE, x0, y0, z0, x1, y1, z1, e, s)
                }
                override fun travel(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, retract: Boolean, s: ScanState) {
                    if (inRun(s) && (keep == null || keep(x0, y0, z0, x1, y1, z1))) moves.add(if (retract) MoveSet.WIPE else MoveSet.TRAVEL, x0, y0, z0, x1, y1, z1, 0.0, s)
                }
                override fun retraction(x: Double, y: Double, z: Double, amount: Double, s: ScanState) {
                    if (inRun(s) && (keep == null || keep(x, y, z, x, y, z))) marks.add(MarkSet.RETRACT, x, y, z, s.tool, s)
                }
                override fun toolChange(x: Double, y: Double, z: Double, from: Int, to: Int, s: ScanState) {
                    if (inRun(s) && !x.isNaN() && (keep == null || keep(x, y, z, x, y, z))) marks.add(MarkSet.TOOL_CHANGE, x, y, z, to, s)
                }
            }
            GcodeFile.open(index.path, first.offset).use { scan.run(Lines(it, first.offset), sink, index.layers[r.last].endOffset) }
        }
        return moves to marks
    }
}
