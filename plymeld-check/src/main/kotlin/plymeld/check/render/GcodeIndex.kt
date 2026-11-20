package plymeld.check.render

import java.io.BufferedInputStream
import java.io.InputStream
import java.nio.channels.Channels
import java.nio.channels.FileChannel
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.zip.ZipFile

/** Opens a g-code file (or the plate g-code of a `.gcode.3mf`) at a byte offset, to read on from there. */
internal object GcodeFile {
    fun open(path: Path, offset: Long = 0): InputStream {
        if (path.toString().endsWith(".3mf")) {
            val zip = ZipFile(path.toFile())
            val entry = zip.entries().asSequence().filter { Regex("Metadata/plate_\\d+\\.gcode").matches(it.name) }.minByOrNull { it.name }
                ?: run { zip.close(); error("$path holds no plate g-code (Metadata/plate_<n>.gcode)") }
            val s = zip.getInputStream(entry)
            var left = offset
            while (left > 0) { val k = s.skip(left); if (k <= 0) break; left -= k }
            return object : java.io.FilterInputStream(BufferedInputStream(s, 1 shl 16)) { override fun close() { super.close(); zip.close() } }
        }
        val ch = FileChannel.open(path, StandardOpenOption.READ)
        ch.position(offset)
        return Channels.newInputStream(ch)
    }
}

/** One layer of the file: where it starts, how high its top is, and what it lays. */
class LayerInfo(
    /** 0-based; the layer number the user sees is [index] + 1. */
    val index: Int,
    /** The byte offset its reading starts at, and the reader's state there. */
    val offset: Long, val before: ScanState,
) {
    /** Its top: the height the file states for it (`;Z:`), or the first height it extrudes at. */
    var z = Double.NaN
    /** Its thickness: the height the file declares (`;HEIGHT:`), else its top less the top of the layer below. */
    var height = Double.NaN
    /** The highest extruding Z in it (an inferred layer's numbering reads it). */
    var maxZ = Double.NaN
    /** The lowest extruding Z in it (a cut through heights reads the layers that reach them). */
    var minZ = Double.NaN
    var endOffset = Long.MAX_VALUE
    /** The extent of its extruding moves (mm); NaN when it lays nothing. */
    var minX = Double.NaN; var minY = Double.NaN; var maxX = Double.NaN; var maxY = Double.NaN
    var extrudeMoves = 0
    /** The time its moves take at their commanded feed rates (s), acceleration not counted. */
    var nominalS = 0.0
    val number: Int get() = index + 1
}

/**
 * A g-code file's first reading: its layers (each with its byte offset, so a view reads only the layers it draws), the
 * names it writes (features, objects), the tools it uses, and what it states about itself (filament diameters and
 * colors), with what it does not state, for the pictures to say. One pass over the file, streamed.
 */
class GcodeIndex(
    val path: Path,
    val layers: List<LayerInfo>,
    /** True where the file marks its layers; false: each layer read from the heights the moves are laid at. */
    val layerMarkers: Boolean,
    val features: Names, val objects: Names,
    /** The tools that lay plastic, ascending. */
    val tools: List<Int>,
    /** Filament diameter (mm) per tool, as the file's own comments state them (index = tool); empty when it states none. */
    val diameters: List<Double>,
    /** Filament color per tool (#RRGGBB) as the file's comments state them; empty when none. */
    val colors: List<String>,
    /** Whether any extruding move carries a feature name, a declared width, a declared height. */
    val namesFeatures: Boolean, val declaresWidths: Boolean, val declaresHeights: Boolean,
    /** Filament (mm) extruded before the first layer marker: the start code's, belonging to no layer. */
    val beforeFirstLayerMm: Double,
    val lines: Long,
) {
    fun layerNumberAtZ(z: Double): Int? = layers.firstOrNull { !it.z.isNaN() && it.z >= z - 1e-9 }?.number

    /**
     * Sees each extruding move the index reads, after it is counted into its layer: so a view's first pass can be made in
     * the same reading. [marked]: the layer is one the file marks (a move before the first marker is in a layer read
     * from the heights, which a file that marks its layers does not have). [diameters]: the filament diameters the file
     * has stated so far; [layerHeight]: the layer's thickness as known so far (its declared height, else its top less the
     * top of the layer below; NaN when neither is known yet).
     */
    interface Listener {
        fun begin(features: Names, objects: Names) {}
        fun extrude(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, e: Double, s: ScanState, layer: LayerInfo, marked: Boolean,
                    diameters: List<Double>, layerHeight: Double)
    }

    companion object {
        fun read(path: Path, listener: Listener? = null): GcodeIndex {
            val features = Names(); val objects = Names()
            listener?.begin(features, objects)
            val scan = GcodeScan(features, objects, null)
            val marked = ArrayList<LayerInfo>()
            // per inferred layer (kept in case the file marks none): extent, moves, time, highest z
            val inferredStats = ArrayList<LayerInfo>()
            val tools = java.util.TreeSet<Int>()
            var diameters = emptyList<Double>(); var colors = emptyList<String>()
            val ffColors = java.util.TreeMap<Int, String>()
            var namesFeatures = false; var declaresWidths = false; var declaresHeights = false
            var before = 0.0
            fun stat(l: LayerInfo, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, s: ScanState, extrude: Boolean) {
                val len = Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0))
                if (!s.f.isNaN() && s.f > 0) l.nominalS += len / (s.f / 60.0)
                if (!extrude) return
                l.extrudeMoves++
                if (l.minX.isNaN()) { l.minX = minOf(x0, x1); l.maxX = maxOf(x0, x1); l.minY = minOf(y0, y1); l.maxY = maxOf(y0, y1) }
                else { l.minX = minOf(l.minX, x0, x1); l.maxX = maxOf(l.maxX, x0, x1); l.minY = minOf(l.minY, y0, y1); l.maxY = maxOf(l.maxY, y0, y1) }
                l.maxZ = if (l.maxZ.isNaN()) z1 else maxOf(l.maxZ, z1)
                l.minZ = if (l.minZ.isNaN()) minOf(z0, z1) else minOf(l.minZ, z0, z1)
                if (l.z.isNaN()) l.z = z1
                if (l.height.isNaN() && !s.height.isNaN()) l.height = s.height
            }
            val sink = object : ScanSink {
                override val wantsComments get() = true
                override fun layerStart(layer: Int, offset: Long, before: ScanState) {
                    marked.lastOrNull()?.endOffset = offset
                    marked += LayerInfo(layer, offset, before)
                }
                override fun layerZ(layer: Int, z: Double) { marked[layer].z = z }
                private fun inferredLayer(): LayerInfo? {
                    val k = scan.inferredIndex
                    if (k < 0) return null
                    while (inferredStats.size <= k) { val (off, st, z) = scan.inferred[inferredStats.size]; inferredStats += LayerInfo(inferredStats.size, off, st).also { it.z = z } }
                    return inferredStats[k]
                }
                override fun extrude(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, e: Double, s: ScanState) {
                    tools += s.tool
                    if (s.feature >= 0) namesFeatures = true
                    if (!s.width.isNaN()) declaresWidths = true
                    if (!s.height.isNaN()) declaresHeights = true
                    if (!scan.sawMarker) {
                        inferredLayer()?.let { l ->
                            stat(l, x0, y0, z0, x1, y1, z1, s, true)
                            listener?.extrude(x0, y0, z0, x1, y1, z1, e, s, l, false, diameters, heightSoFar(inferredStats, l))
                        }
                        before += e
                    } else if (s.layer >= 0) {
                        val l = marked[s.layer]
                        stat(l, x0, y0, z0, x1, y1, z1, s, true)
                        listener?.extrude(x0, y0, z0, x1, y1, z1, e, s, l, true, diameters, heightSoFar(marked, l))
                    }
                }
                override fun travel(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, retract: Boolean, s: ScanState) {
                    if (!scan.sawMarker) inferredLayer()?.let { stat(it, x0, y0, z0, x1, y1, z1, s, false) }
                    else if (s.layer >= 0) stat(marked[s.layer], x0, y0, z0, x1, y1, z1, s, false)
                }
                override fun comment(text: String, s: ScanState) {
                    // the file's statements about its filaments: a header's (`; filament_diameter: 1.75,1.75`), a settings block's
                    // (`; filament_diameter = 1.75`), Flash Studio's `<filament id="1" ... color="#RRGGBB"/>`, `; filament_colour = #..;#..`
                    val key = text.substringBefore(':').substringBefore('=').trim()
                    val value = if (text.contains('=') && (!text.contains(':') || text.indexOf('=') < text.indexOf(':'))) text.substringAfter('=') else text.substringAfter(':', "")
                    when (key) {
                        "filament_diameter" -> value.split(',', ';').mapNotNull { it.trim().toDoubleOrNull() }.takeIf { it.isNotEmpty() }?.let { diameters = it }
                        "filament_colour", "filament_color", "extruder_colour" -> value.split(';', ',').map { it.trim() }.filter { Regex("#[0-9A-Fa-f]{6}").matches(it) }
                            .takeIf { it.isNotEmpty() && (key != "extruder_colour" || colors.isEmpty()) }?.let { colors = it }
                    }
                    if (text.startsWith("<filament ")) {
                        val id = Regex("id=\"(\\d+)\"").find(text)?.groupValues?.get(1)?.toIntOrNull()
                        val c = Regex("color=\"(#[0-9A-Fa-f]{6})\"").find(text)?.groupValues?.get(1)
                        if (id != null && c != null) ffColors[id - 1] = c
                    }
                }
            }
            GcodeFile.open(path).use { scan.run(Lines(it), sink) }
            if (ffColors.isNotEmpty() && colors.isEmpty()) colors = (0..ffColors.lastKey()).map { ffColors[it] ?: "" }
            val markers = scan.sawMarker
            val layers: List<LayerInfo> = if (markers) marked else {
                for (k in 0 until inferredStats.size - 1) inferredStats[k].endOffset = inferredStats[k + 1].offset
                inferredStats
            }
            // thickness where the file declares none: the top less the top of the layer below (the bed under the first)
            for ((k, l) in layers.withIndex()) if (l.height.isNaN() && !l.z.isNaN()) l.height = thickness(layers, k)
            return GcodeIndex(path, layers, markers, features, objects, tools.toList(), diameters, colors, namesFeatures, declaresWidths, declaresHeights,
                if (markers) before else 0.0, scan.st.lineNo)
        }

        /** Layer [k]'s thickness where the file declares none: its top less the top of the layer below (the bed under the first). */
        private fun thickness(layers: List<LayerInfo>, k: Int): Double = layers[k].z - (if (k == 0) 0.0 else layers[k - 1].z)

        /** Layer [l]'s thickness as known while it is read: declared, else as [thickness] would make it now. */
        private fun heightSoFar(layers: List<LayerInfo>, l: LayerInfo): Double =
            if (!l.height.isNaN()) l.height else if (l.z.isNaN()) Double.NaN else thickness(layers, l.index)
    }
}
