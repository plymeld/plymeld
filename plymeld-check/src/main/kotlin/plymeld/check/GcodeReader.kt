package plymeld.check

import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Reads Klipper-flavor g-code (as QIDIStudio/OrcaSlicer write it) back into what the
 * slicer *did*: extrusion segments per layer, tagged by feature, plus totals.
 *
 * This is the reference-side instrument: it is applied to QIDIStudio's output first and
 * checked against the totals that file states about itself, and only then to plymeld's.
 * It knows nothing about plymeld's internals.
 *
 * Tracked state: absolute XYZ (G90 assumed; G91 unsupported -> error), E in absolute or
 * relative mode (M82/M83), G92 E resets, feedrate F. Layers come from `; CHANGE_LAYER`
 * followed by `; Z_HEIGHT:`; the feature from `; FEATURE:`. `; LINE_WIDTH:` is recorded.
 */
class GcodeStats(
    val layers: List<GcodeLayer>,
    val totalExtrudedMm: Double,     // filament length, sum of positive E deltas on moves
    val totalExtrudeMoveMm: Double,  // XY path length of extruding moves
    val totalTravelMm: Double,       // XY path length of non-extruding moves
    val featureLengthMm: Map<String, Double>,
    val headerTotalLayers: Int?,
    val headerFilamentUsedMm: Double?,
)

class GcodeLayer(val index: Int, val z: Double, var height: Double?) {
    /** Extrusion segments: x0 y0 x1 y1 e (filament mm) per segment, flat. */
    val segs = ArrayList<Double>()
    val featureOfSeg = ArrayList<String>()
    /** True where the segment is a chord of a G2/G3 arc rather than a move of its own. */
    val arcSeg = ArrayList<Boolean>()
    /** Lengths (mm) of extrusion runs: continuous extrusion between travels (fragmentation). */
    val runs = ArrayList<Double>()
    val runFeature = ArrayList<String>()
    var openRun = 0.0
    var openRunFeature = ""
    fun closeRun() { if (openRun > 0) { runs.add(openRun); runFeature.add(openRunFeature); openRun = 0.0 } }
    /** Travel moves: x0 y0 x1 y1 per move, flat. */
    val travels = ArrayList<Double>()
    var travelMm = 0.0
    var extrudeMm = 0.0
    var filamentMm = 0.0
    val segCount: Int get() = featureOfSeg.size
}

object GcodeReader {
    /** Max chord length when an arc is turned into segments, mm. */
    const val ARC_CHORD = 0.5

    /** Opens a `.gcode.3mf` (Metadata/plate_1.gcode) or a bare `.gcode`. */
    fun read(path: Path): GcodeStats {
        if (path.toString().endsWith(".3mf")) {
            ZipFile(path.toFile()).use { zip ->
                val entry = zip.getEntry("Metadata/plate_1.gcode") ?: error("no Metadata/plate_1.gcode in $path")
                return BufferedReader(InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8), 1 shl 16).use { parse(it) }
            }
        }
        return Files.newBufferedReader(path, StandardCharsets.UTF_8).use { parse(it) }
    }

    fun parse(reader: BufferedReader): GcodeStats {
        val layers = ArrayList<GcodeLayer>()
        var cur: GcodeLayer? = null
        var pendingLayer = false
        var feature = "None"
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0
        var relativeE = false
        var totalE = 0.0; var totalExtrudeXY = 0.0; var totalTravelXY = 0.0
        val featureLen = HashMap<String, Double>()
        var headerLayers: Int? = null
        var headerFilament: Double? = null
        var lineNo = 0

        while (true) {
            val raw = reader.readLine() ?: break
            lineNo++
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line[0] == ';') {
                when {
                    line.startsWith("; CHANGE_LAYER") || line.startsWith(";CHANGE_LAYER") || line.startsWith(";LAYER_CHANGE") -> pendingLayer = true
                    line.startsWith(";Z:") -> {
                        // Orca / Flash Studio: ;LAYER_CHANGE then ;Z:<z> then ;HEIGHT:<h>
                        val zh = line.substringAfter(':').trim().toDouble()
                        if (pendingLayer) {
                            cur?.closeRun()
                            cur = GcodeLayer(layers.size, zh, null).also { layers.add(it) }
                            pendingLayer = false
                        }
                    }
                    line.startsWith(";HEIGHT:") -> cur?.height = line.substringAfter(':').trim().toDouble()
                    line.startsWith(";TYPE:") -> feature = line.substringAfter(':').trim()
                    line.startsWith("; Z_HEIGHT:") -> {
                        val zh = line.substringAfter(':').trim().toDouble()
                        if (pendingLayer) {
                            cur?.closeRun()
                            cur = GcodeLayer(layers.size, zh, null).also { layers.add(it) }
                            pendingLayer = false
                        }
                    }
                    line.startsWith("; LAYER_HEIGHT:") -> cur?.height = line.substringAfter(':').trim().toDouble()
                    line.startsWith("; FEATURE:") -> feature = line.substringAfter(':').trim()
                    line.startsWith("; total layer number:") -> headerLayers = line.substringAfter(':').trim().toInt()
                    line.startsWith("; filament used [mm]") -> headerFilament = line.substringAfter('=').split(',').sumOf { it.trim().toDouble() }   // per tool on multi-tool machines
                }
                continue
            }
            // strip trailing comment
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val parts = code.split(' ', '\t').filter { it.isNotEmpty() }
            when (val cmd = parts[0].uppercase()) {
                "G0", "G1" -> {
                    var nx = x; var ny = y; var nz = z; var ne: Double? = null
                    for (p in parts.drop(1)) {
                        val v = p.substring(1).toDoubleOrNull() ?: continue
                        when (p[0].uppercaseChar()) {
                            'X' -> nx = v; 'Y' -> ny = v; 'Z' -> nz = v; 'E' -> ne = v
                        }
                    }
                    val de = when {
                        ne == null -> 0.0
                        relativeE -> ne
                        else -> ne - e
                    }
                    if (ne != null) e = if (relativeE) e + ne else ne
                    val dx = nx - x; val dy = ny - y
                    val len = Math.sqrt(dx * dx + dy * dy)
                    // Filament total is NET: a retract (-0.8) and its unretract (+0.8) cancel, as in
                    // the slicer's own "filament used" figure. Segments record only extruding moves.
                    totalE += de
                    cur?.let { it.filamentMm += de }
                    if (de > 0 && len > 0) {
                        totalExtrudeXY += len
                        featureLen[feature] = (featureLen[feature] ?: 0.0) + len
                        cur?.let {
                            it.segs.add(x); it.segs.add(y); it.segs.add(nx); it.segs.add(ny); it.segs.add(de)
                            it.featureOfSeg.add(feature)
                            it.arcSeg.add(false)
                            it.extrudeMm += len
                            it.openRun += len; it.openRunFeature = feature
                        }
                    } else if (len > 0) {
                        totalTravelXY += len
                        cur?.let { it.closeRun(); it.travelMm += len; it.travels.add(x); it.travels.add(y); it.travels.add(nx); it.travels.add(ny) }
                    }
                    x = nx; y = ny; z = nz
                }
                "G92" -> for (p in parts.drop(1)) if (p[0].uppercaseChar() == 'E') e = p.substring(1).toDoubleOrNull() ?: e
                "M82" -> relativeE = false
                "M83" -> relativeE = true
                "G91" -> error("relative XYZ positioning (G91) at line $lineNo is not supported")
                "G90" -> Unit
                "G2", "G3" -> {
                    // Arc in XY (G17), center given as I/J offsets from the start point. Subdivided into
                    // chords no longer than ARC_CHORD so the segment list stays a polyline; E is spread
                    // along the chords in proportion to their length.
                    var nx = x; var ny = y; var nz = z; var ne: Double? = null; var i = 0.0; var j = 0.0
                    for (p in parts.drop(1)) {
                        val v = p.substring(1).toDoubleOrNull() ?: continue
                        when (p[0].uppercaseChar()) {
                            'X' -> nx = v; 'Y' -> ny = v; 'Z' -> nz = v; 'E' -> ne = v; 'I' -> i = v; 'J' -> j = v
                            'R' -> error("R-form arc at line $lineNo is not supported")
                        }
                    }
                    val de = when { ne == null -> 0.0; relativeE -> ne; else -> ne - e }
                    if (ne != null) e = if (relativeE) e + ne else ne
                    val cx = x + i; val cy = y + j
                    val r = Math.hypot(i, j)
                    val a0 = Math.atan2(y - cy, x - cx)
                    val a1 = Math.atan2(ny - cy, nx - cx)
                    var sweep = a1 - a0
                    val cw = cmd == "G2"
                    if (cw && sweep > 0) sweep -= 2 * Math.PI
                    if (!cw && sweep < 0) sweep += 2 * Math.PI
                    if (r == 0.0) error("zero-radius arc at line $lineNo")
                    val arcLen = Math.abs(sweep) * r
                    val n = Math.max(1, Math.ceil(arcLen / ARC_CHORD).toInt())
                    totalE += de
                    cur?.let { it.filamentMm += de }
                    var px = x; var py = y
                    for (k in 1..n) {
                        val a = a0 + sweep * k / n
                        val qx = if (k == n) nx else cx + r * Math.cos(a)
                        val qy = if (k == n) ny else cy + r * Math.sin(a)
                        val len = Math.hypot(qx - px, qy - py)
                        val dek = de / n
                        if (dek > 0 && len > 0) {
                            totalExtrudeXY += len
                            featureLen[feature] = (featureLen[feature] ?: 0.0) + len
                            cur?.let {
                                it.segs.add(px); it.segs.add(py); it.segs.add(qx); it.segs.add(qy); it.segs.add(dek)
                                it.featureOfSeg.add(feature)
                                it.arcSeg.add(true)
                                it.extrudeMm += len
                                it.openRun += len; it.openRunFeature = feature
                            }
                        } else if (len > 0) {
                            totalTravelXY += len
                            cur?.let { it.closeRun(); it.travelMm += len }
                        }
                        px = qx; py = qy
                    }
                    x = nx; y = ny; z = nz
                }
            }
        }
        cur?.closeRun()
        return GcodeStats(layers, totalE, totalExtrudeXY, totalTravelXY, featureLen, headerLayers, headerFilament)
    }
}
