package plymeld.ui

import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * A g-code file's extrusions for the 3D view, kept flat so a million segments stay small. Per segment: its two ends
 * (x, y, z, x, y, z as floats; every end its own Z, so curved layers draw as printed), its feature (an index into
 * [features]), its line width and layer height (mm, from the file's `;WIDTH:` / `;HEIGHT:`, NaN where it gives none),
 * its speed (mm/s, from F), its tool (T), and its part-cooling fan (0..255). Layers by [layerStart] (one more entry than
 * layers: the end). Travels apart ([travelXyz], [travelLayerStart]). What the file says about itself in [stated].
 */
class Toolpaths(
    val xyz: FloatArray, val feature: ByteArray, val layerStart: IntArray, val features: List<String>,
    val width: FloatArray = FloatArray(feature.size) { Float.NaN }, val height: FloatArray = FloatArray(feature.size) { Float.NaN },
    val speed: FloatArray = FloatArray(feature.size), val tool: ByteArray = ByteArray(feature.size), val fan: ByteArray = ByteArray(feature.size),
    val travelXyz: FloatArray = FloatArray(0), val travelLayerStart: IntArray = IntArray(layerStart.size),
    /** Filament (mm) pushed for each feature, by index. */
    val filamentByFeature: DoubleArray = DoubleArray(features.size),
    val stated: Stated = Stated(),
) {
    /** What the file's header comments state: the slicer's own estimate, not measured here. */
    class Stated(val printTime: String? = null, val filamentMm: Double? = null, val filamentCm3: Double? = null, val generator: String? = null)

    val segments: Int get() = feature.size
    val layers: Int get() = layerStart.size - 1
    val travels: Int get() = travelXyz.size / 6
    /** The tools used, in order of first use. */
    val tools: List<Int> by lazy { val seen = LinkedHashSet<Int>(); for (t in tool) seen.add(t.toInt()); seen.toList() }

    /**
     * min x, y, z, max x, y, z (mm) of the segments with a feature named (the print itself: a start template's prime line
     * carries none, and fitting the view to it shrank a Benchy to a corner of the bed), or of all of them when none is
     * named; a unit box when there are no segments.
     */
    val bounds: DoubleArray by lazy {
        if (segments == 0) return@lazy doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 1.0)
        val unnamed = features.indexOf("")
        val anyNamed = (0 until segments).any { feature[it].toInt() != unnamed }
        val b = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (s in 0 until segments) {
            if (anyNamed && feature[s].toInt() == unnamed) continue
            for (i in s * 2..s * 2 + 1) for (k in 0..2) { val v = xyz[i * 3 + k].toDouble(); if (v < b[k]) b[k] = v; if (v > b[k + 3]) b[k + 3] = v }
        }
        b
    }
    /**
     * The print's size (mm, x, y, z): the line centers' extent plus one line (the file's most common width, else the
     * display stand-in) in x and y, and its height above the bed (g-code Z is the bed's) in z. An estimate from the lines.
     */
    val size: DoubleArray by lazy {
        val b = bounds
        val ws = width.filter { !it.isNaN() }.sorted()
        val w = if (ws.isEmpty()) Renderer.DEFAULT_WIDTH_MM.toDouble() else ws[ws.size / 2].toDouble()
        doubleArrayOf(b[3] - b[0] + w, b[4] - b[1] + w, b[5])
    }

    /** The highest Z printed in layer [k] (mm). */
    fun layerTop(k: Int): Double { var z = -Double.MAX_VALUE; for (s in layerStart[k] until layerStart[k + 1]) z = maxOf(z, xyz[s * 6 + 2].toDouble(), xyz[s * 6 + 5].toDouble()); return z }
}

/**
 * Reads Klipper-flavor g-code as plymeld, QIDIStudio, OrcaSlicer, and Flash Studio write it: absolute XYZ (G90; G91 is
 * refused), E absolute or relative (M82 / M83, G92 E), arcs G2 / G3 by I J as chords at most [ARC_CHORD_MM], layers from
 * `;LAYER_CHANGE` or `; CHANGE_LAYER`, the feature from `;TYPE:` or `; FEATURE:`, the line width from `;WIDTH:`, the
 * layer height from `;HEIGHT:`, the tool from `T<n>`, the part fan from `M106 S` (no P, or P0) and `M107`. A move is
 * printed when it extrudes and goes somewhere; a move that goes somewhere without extruding is a travel. Before the first
 * layer marker, moves belong to the first layer.
 */
object GcodeToolpaths {
    /** Longest chord an arc is drawn with (mm). plymeld's choice: as the pre-print check reads arcs. */
    const val ARC_CHORD_MM = 0.5

    fun read(path: Path): Toolpaths = Files.newBufferedReader(path).use { parse(it) }

    fun parse(text: String): Toolpaths = parse(text.reader().buffered())

    fun parse(reader: BufferedReader): Toolpaths {
        val p = Parser()
        while (true) p.feed(reader.readLine() ?: break)
        return p.finish()
    }

    /**
     * The reader fed one line at a time, so a file can be shown while it is being written (the window's slice): [feed]
     * each line, [snapshot] what is read so far (the layer in progress included, closed as it stands), [finish] at the end.
     */
    class Parser {
        private val xyz = Floats(1 shl 16); private val feat = Bytes(1 shl 14); private val starts = Ints(256)
        private val wid = Floats(1 shl 14); private val hgt = Floats(1 shl 14); private val spd = Floats(1 shl 14); private val tl = Bytes(1 shl 14); private val fn = Bytes(1 shl 14)
        private val trav = Floats(1 shl 14); private val travStarts = Ints(256)
        private val features = ArrayList<String>(); private val featureIdx = HashMap<String, Int>()
        private val byFeature = ArrayList<Double>()
        private var featNow = featureIndex("", features, featureIdx, byFeature)
        private var x = 0.0; private var y = 0.0; private var z = 0.0; private var e = 0.0; private var relE = false
        private var widthNow = Float.NaN; private var heightNow = Float.NaN; private var feedNow = 0f; private var toolNow = 0; private var fanNow = 0
        private var printTime: String? = null; private var filMm: Double? = null; private var filCm3: Double? = null; private var generator: String? = null
        private var started = false; private var marked = false
        /** Layers begun so far. */
        val layers: Int get() = starts.size

        private fun startLayer() { starts.add(feat.size); travStarts.add(trav.size / 6) }
        private fun seg(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double) {
            if (!started) { startLayer(); started = true }
            xyz.add(x0.toFloat()); xyz.add(y0.toFloat()); xyz.add(z0.toFloat()); xyz.add(x1.toFloat()); xyz.add(y1.toFloat()); xyz.add(z1.toFloat())
            feat.add(featNow.toByte()); wid.add(widthNow); hgt.add(heightNow); spd.add(feedNow / 60f); tl.add(toolNow.toByte()); fn.add(fanNow.toByte())
        }
        private fun sum(list: String) = list.split(',').sumOf { it.trim().toDoubleOrNull() ?: 0.0 }

        fun feed(raw: String) {
            val line = raw.trim()
            if (line.isEmpty()) return
            if (line[0] == ';') {
                when {
                    line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER") -> {
                        // the first marker opens the first layer, which keeps what was printed before it (a start
                        // template's prime line: counting it a layer of its own made the Benchy 162 layers, its file 161);
                        // a layer with nothing printed yet is the same layer
                        if (!started) { startLayer(); started = true } else if (marked && starts.last() != feat.size) startLayer()
                        marked = true
                    }
                    line.startsWith(";TYPE:") || line.startsWith("; FEATURE:") ->
                        featNow = featureIndex(line.substringAfter(':').trim(), features, featureIdx, byFeature)
                    line.startsWith(";WIDTH:") || line.startsWith("; LINE_WIDTH:") -> line.substringAfter(':').trim().toFloatOrNull()?.let { widthNow = it }
                    line.startsWith(";HEIGHT:") || line.startsWith("; LAYER_HEIGHT:") -> line.substringAfter(':').trim().toFloatOrNull()?.let { heightNow = it }
                    line.startsWith("; estimated printing time (normal mode)") -> printTime = line.substringAfter('=').trim()
                    line.startsWith("; filament used [mm]") -> filMm = sum(line.substringAfter('='))
                    line.startsWith("; filament used [cm3]") -> filCm3 = sum(line.substringAfter('='))
                    line.startsWith("; generated by") -> generator = line.removePrefix("; generated by").trim()
                }
                return
            }
            val code = line.substringBefore(';').trim()
            val word = code.substringBefore(' ')
            if (word.length >= 2 && word[0] == 'T' && word.substring(1).all { it.isDigit() }) { toolNow = word.substring(1).toInt(); return }
            when (word) {
                "M82" -> { relE = false; return }
                "M83" -> { relE = true; return }
                "M106" -> { val p = value(code, 'P'); if (p == null || p == 0.0) fanNow = (value(code, 'S') ?: 255.0).toInt().coerceIn(0, 255); return }
                "M107" -> { if ((value(code, 'P') ?: 0.0) == 0.0) fanNow = 0; return }
                "G91" -> error("relative positioning (G91) is not read: line '$raw'")
                "G92" -> { value(code, 'E')?.let { e = it }; return }
                "G0", "G1", "G2", "G3" -> {}
                else -> return
            }
            value(code, 'F')?.let { feedNow = it.toFloat() }
            val nx = value(code, 'X') ?: x; val ny = value(code, 'Y') ?: y; val nz = value(code, 'Z') ?: z
            val ev = value(code, 'E')
            val de = if (ev == null) 0.0 else if (relE) ev else ev - e
            if (ev != null && !relE) e = ev
            val moves = nx != x || ny != y || nz != z
            if (de > 0 && moves) {
                byFeature[featNow] += de
                if (word == "G2" || word == "G3") {
                    val cx = x + (value(code, 'I') ?: 0.0); val cy = y + (value(code, 'J') ?: 0.0)
                    val r = hypot(x - cx, y - cy)
                    val a0 = atan2(y - cy, x - cx); var sweep = atan2(ny - cy, nx - cx) - a0
                    if (word == "G2") { while (sweep >= 0) sweep -= 2 * PI } else { while (sweep <= 0) sweep += 2 * PI }
                    val n = maxOf(1, ceil(Math.abs(sweep) * r / ARC_CHORD_MM).toInt())
                    var px = x; var py = y; var pz = z
                    for (i in 1..n) {
                        val t = i.toDouble() / n
                        val qx = if (i == n) nx else cx + r * cos(a0 + sweep * t); val qy = if (i == n) ny else cy + r * sin(a0 + sweep * t)
                        val qz = z + (nz - z) * t
                        seg(px, py, pz, qx, qy, qz); px = qx; py = qy; pz = qz
                    }
                } else seg(x, y, z, nx, ny, nz)
            } else if (moves && (nx != x || ny != y) && started) {
                trav.add(x.toFloat()); trav.add(y.toFloat()); trav.add(z.toFloat()); trav.add(nx.toFloat()); trav.add(ny.toFloat()); trav.add(nz.toFloat())
            }
            x = nx; y = ny; z = nz
        }

        /** What is read so far, as a file that ended here (the current layer closed as it stands); null before any layer. */
        fun snapshot(): Toolpaths? {
            if (!started) return null
            val st = starts.toArray() + feat.size; val ts = travStarts.toArray() + trav.size / 6
            return Toolpaths(xyz.toArray(), feat.toArray(), st, ArrayList(features), wid.toArray(), hgt.toArray(), spd.toArray(), tl.toArray(), fn.toArray(),
                trav.toArray(), ts, byFeature.toDoubleArray(), Toolpaths.Stated(printTime, filMm, filCm3, generator))
        }

        fun finish(): Toolpaths {
            if (!started) startLayer()
            starts.add(feat.size); travStarts.add(trav.size / 6)
            return Toolpaths(xyz.toArray(), feat.toArray(), starts.toArray(), features, wid.toArray(), hgt.toArray(), spd.toArray(), tl.toArray(), fn.toArray(),
                trav.toArray(), travStarts.toArray(), byFeature.toDoubleArray(), Toolpaths.Stated(printTime, filMm, filCm3, generator))
        }
    }

    private fun featureIndex(name: String, list: MutableList<String>, idx: MutableMap<String, Int>, byFeature: MutableList<Double>): Int =
        idx.getOrPut(name) { require(list.size < 127) { "more than 127 features" }; list.add(name); byFeature.add(0.0); list.size - 1 }

    /** The number after letter [c] in [code] (a G-code word), or null. */
    private fun value(code: String, c: Char): Double? {
        var i = 0
        while (i < code.length) {
            if (code[i] == c && (i == 0 || code[i - 1] == ' ')) {
                var j = i + 1
                while (j < code.length && code[j] != ' ') j++
                return code.substring(i + 1, j).toDoubleOrNull()
            }
            i++
        }
        return null
    }

    private class Floats(cap: Int) { var a = FloatArray(cap); var size = 0
        fun add(v: Float) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
    private class Bytes(cap: Int) { var a = ByteArray(cap); var size = 0
        fun add(v: Byte) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun toArray() = a.copyOf(size) }
    private class Ints(cap: Int) { var a = IntArray(cap); var size = 0
        fun add(v: Int) { if (size == a.size) a = a.copyOf(a.size * 2); a[size++] = v }
        fun last() = a[size - 1]
        fun toArray() = a.copyOf(size) }
}
