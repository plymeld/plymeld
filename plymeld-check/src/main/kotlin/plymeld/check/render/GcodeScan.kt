package plymeld.check.render

import java.io.InputStream

/**
 * One line at a time from a byte stream, with each line's byte offset in the file, so a later pass can start at a layer
 * without reading what comes before it. The file is never held whole: a g-code of hundreds of megabytes is read through
 * one buffer.
 */
internal class Lines(private val input: InputStream, startOffset: Long = 0) {
    private val buf = ByteArray(1 shl 16)
    private var len = 0
    private var pos = 0
    /** The current line's bytes ([lineLen] of them, without its line end). */
    var line = ByteArray(512); private set
    var lineLen = 0; private set
    /** The byte offset of the current line's first byte. */
    var lineOffset = 0L; private set
    private var offset = startOffset

    /** Reads the next line; false at the end of the stream. */
    fun next(): Boolean {
        lineOffset = offset
        lineLen = 0
        var any = false
        while (true) {
            if (pos == len) {
                len = input.read(buf, 0, buf.size)
                pos = 0
                if (len <= 0) { len = 0; return any }
            }
            val b = buf[pos++]
            offset++
            any = true
            if (b == '\n'.code.toByte()) break
            if (lineLen == line.size) line = line.copyOf(line.size * 2)
            line[lineLen++] = b
        }
        if (lineLen > 0 && line[lineLen - 1] == '\r'.code.toByte()) lineLen--
        return true
    }

    fun text(from: Int = 0, to: Int = lineLen): String = String(line, from, to - from, Charsets.UTF_8)
}

/** Names the file writes (features, objects), numbered in the order they first appear. */
class Names {
    private val ids = HashMap<String, Int>()
    val list = ArrayList<String>()
    fun id(name: String): Int = ids.getOrPut(name) { list += name; list.size - 1 }
    fun find(name: String): Int? = ids[name]
    operator fun get(i: Int): String = list[i]
    val size: Int get() = list.size
}

/**
 * What the reader knows at a point of the file: the head's position (NaN until the file sets it), the extruder's mode and
 * position, the feed rate, and what the comments and commands have set (feature, object, tool, part fan, temperatures,
 * declared line width and height, the layer).
 */
class ScanState {
    var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
    var e = 0.0
    var relE = false
    var relXyz = false
    /** Feed rate, mm/min; NaN until the file sets one. */
    var f = Double.NaN
    var feature = -1
    var obj = -1
    var tool = 0
    /** Part fan, 0..1. */
    var fan = 0.0
    /** Each tool's nozzle temperature last set (C), NaN before any. */
    var temps = DoubleArray(MAX_TOOLS) { Double.NaN }
    /** The line width and height the file declares for the path being laid (`;WIDTH:` / `;HEIGHT:`), NaN where it declares none. */
    var width = Double.NaN
    var height = Double.NaN
    /** The layer being read, 0-based (-1 before the first). */
    var layer = -1
    var lineNo = 0L

    val temp: Double get() = if (tool in temps.indices) temps[tool] else Double.NaN

    fun copy(): ScanState = ScanState().also { it.set(this) }
    fun set(o: ScanState) {
        x = o.x; y = o.y; z = o.z; e = o.e; relE = o.relE; relXyz = o.relXyz; f = o.f; feature = o.feature; obj = o.obj
        tool = o.tool; fan = o.fan; temps = o.temps.copyOf(); width = o.width; height = o.height; layer = o.layer; lineNo = o.lineNo
    }

    companion object { const val MAX_TOOLS = 64 }
}

/**
 * What a pass does with what the reader finds. Positions in mm, as the file states them; an arc arrives as chords.
 */
interface ScanSink {
    /** A layer begins (marker mode: at its marker line; otherwise at the first extruding move above the layer before). */
    fun layerStart(layer: Int, offset: Long, before: ScanState) {}
    /** The current layer's height as the file states it (`;Z:`, `; Z_HEIGHT:`), or the first height it extrudes at. */
    fun layerZ(layer: Int, z: Double) {}
    /** A move laying [e] mm of filament (> 0). */
    fun extrude(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, e: Double, s: ScanState) {}
    /** A move laying nothing; [retract]: it pulls filament back as it moves (a wipe). */
    fun travel(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, retract: Boolean, s: ScanState) {}
    /** Filament pulled back ([amount] mm, > 0) where the head stands. */
    fun retraction(x: Double, y: Double, z: Double, amount: Double, s: ScanState) {}
    /** A tool change from [from] to [to] where the head stands. */
    fun toolChange(x: Double, y: Double, z: Double, from: Int, to: Int, s: ScanState) {}
    /** A comment line (without its ';'), for passes that read the file's own notes; called only when [wantsComments]. */
    fun comment(text: String, s: ScanState) {}
    val wantsComments: Boolean get() = false
}

/**
 * Reads g-code as a printer would: G0 / G1 moves, G2 / G3 arcs (centre by I / J, or radius R; chorded finely enough to
 * stay round when magnified), absolute or relative positioning (G90 / G91) and extrusion (M82 / M83), G92, firmware
 * retraction (G10), tool changes (T<n>), part fan (M106 / M107) and nozzle temperatures (M104 / M109). From the
 * comments: layers (`;LAYER_CHANGE`, `; CHANGE_LAYER`, `;LAYER:` with `;Z:` / `; Z_HEIGHT:` and `;HEIGHT:` /
 * `; LAYER_HEIGHT:`), features (`;TYPE:`, `; FEATURE:`), declared widths (`;WIDTH:`, `; LINE_WIDTH:`) and objects
 * (`; object start` / `; object end`, `EXCLUDE_OBJECT_START NAME=` / `_END`, `; printing object` / `; stop printing
 * object`, `;MESH:`).
 *
 * [layerMarkers]: true where the file marks its layers (then extrusion before the first marker belongs to no layer); false
 * where it does not, and a layer starts at each extruding move above every move of the layer before; null while it is not
 * yet known (the first pass, which tracks both and keeps the one the file turns out to use).
 */
class GcodeScan(val features: Names, val objects: Names, var layerMarkers: Boolean?) {
    val st = ScanState()
    /** The highest extruding Z of the current inferred layer (marker-less files). */
    private var inferredTop = Double.NaN
    private var inferredLayer = -1
    /** Whether a layer marker has been seen in this pass. */
    var sawMarker = false; private set
    private var pendingZ = false

    /** Each inferred layer's start, kept while [layerMarkers] is null: (offset, state before, z). */
    val inferred = ArrayList<Triple<Long, ScanState, Double>>()

    internal fun run(lines: Lines, sink: ScanSink, stopOffset: Long = Long.MAX_VALUE) {
        while (lines.next()) {
            if (lines.lineOffset >= stopOffset) return
            st.lineNo++
            line(lines, sink)
        }
    }

    private fun isSpace(b: Byte) = b == ' '.code.toByte() || b == '\t'.code.toByte()

    private fun line(l: Lines, sink: ScanSink) {
        val b = l.line; val n = l.lineLen
        var i = 0
        while (i < n && isSpace(b[i])) i++
        if (i == n) return
        if (b[i] == ';'.code.toByte()) { comment(l.text(i + 1, n), l.lineOffset, sink); return }
        // the code: up to a ';'
        var end = i
        while (end < n && b[end] != ';'.code.toByte()) end++
        while (end > i && isSpace(b[end - 1])) end--
        if (end == i) return
        // the command word
        var w = i
        while (w < end && !isSpace(b[w])) w++
        val c0 = upper(b[i])
        if ((c0 == 'G'.code || c0 == 'M'.code || c0 == 'T'.code) && w - i >= 2 && allDigits(b, i + 1, w)) {
            val num = intOf(b, i + 1, w)
            when (c0) {
                'G'.code -> gcode(num, b, w, end, l.lineOffset, sink)
                'M'.code -> mcode(num, b, w, end)
                'T'.code -> { val from = st.tool; st.tool = num; if (from != num) sink.toolChange(st.x, st.y, st.z, from, num, st) }
            }
        } else {
            // a named command (Klipper's EXCLUDE_OBJECT_START NAME=...)
            val word = String(b, i, w - i, Charsets.US_ASCII).uppercase()
            if (word == "EXCLUDE_OBJECT_START") {
                val rest = String(b, w, end - w, Charsets.UTF_8)
                Regex("NAME=(\\S+)", RegexOption.IGNORE_CASE).find(rest)?.let { st.obj = objects.id(it.groupValues[1]) }
            } else if (word == "EXCLUDE_OBJECT_END") st.obj = -1
        }
    }

    private fun upper(b: Byte): Int { val c = b.toInt(); return if (c in 'a'.code..'z'.code) c - 32 else c }
    private fun allDigits(b: ByteArray, from: Int, to: Int): Boolean { for (k in from until to) if (b[k] < '0'.code.toByte() || b[k] > '9'.code.toByte()) return false; return true }
    private fun intOf(b: ByteArray, from: Int, to: Int): Int { var v = 0; for (k in from until to) v = v * 10 + (b[k] - '0'.code.toByte()); return v }

    // the parameters of the command being read, by letter (NaN: not given)
    private val p = DoubleArray(26)
    private fun params(b: ByteArray, from: Int, to: Int) {
        java.util.Arrays.fill(p, Double.NaN)
        var k = from
        while (k < to) {
            while (k < to && isSpace(b[k])) k++
            if (k >= to) break
            val letter = upper(b[k])
            var e = k + 1
            while (e < to && !isSpace(b[e])) e++
            if (letter in 'A'.code..'Z'.code) p[letter - 'A'.code] = numberOf(b, k + 1, e)
            k = e
        }
    }
    private fun has(c: Char) = !p[c - 'A'].isNaN()
    private fun v(c: Char) = p[c - 'A']

    private fun gcode(g: Int, b: ByteArray, from: Int, to: Int, offset: Long, sink: ScanSink) {
        when (g) {
            0, 1, 2, 3 -> {
                params(b, from, to)
                if (has('F')) st.f = v('F')
                val x0 = st.x; val y0 = st.y; val z0 = st.z
                fun target(c: Char, cur: Double) = if (!has(c)) cur else if (st.relXyz) cur + v(c) else v(c)
                val x1 = target('X', x0); val y1 = target('Y', y0); val z1 = target('Z', z0)
                val de = if (!has('E')) 0.0 else if (st.relE) v('E') else v('E') - st.e
                if (has('E')) st.e = if (st.relE) st.e + v('E') else v('E')
                val moved = !(x1 == x0 || (x1.isNaN() && x0.isNaN())) || !(y1 == y0 || (y1.isNaN() && y0.isNaN())) || !(z1 == z0 || (z1.isNaN() && z0.isNaN()))
                val known = !x0.isNaN() && !y0.isNaN() && !z0.isNaN() && !x1.isNaN() && !y1.isNaN() && !z1.isNaN()
                if (de > 0 && known && (g >= 2 || moved)) inferLayer(z1, offset, x0, y0, z0, de, sink)
                if (g >= 2 && known) arc(g == 2, x0, y0, z0, x1, y1, z1, de, sink)
                else if (moved && known) {
                    if (de > 0) sink.extrude(x0, y0, z0, x1, y1, z1, de, st)
                    else sink.travel(x0, y0, z0, x1, y1, z1, de < 0, st)
                    if (de < 0) sink.retraction(x1, y1, z1, -de, st)
                } else if (!moved && de < 0 && known) sink.retraction(x0, y0, z0, -de, st)
                st.x = x1; st.y = y1; st.z = z1
            }
            10 -> if (!st.x.isNaN()) sink.retraction(st.x, st.y, st.z, 0.0, st)
            20 -> error("the file is in inches (G20 at line ${st.lineNo}): only millimetres are read")
            28 -> { params(b, from, to)
                // homing: the axes named (all when none is) are somewhere the file does not say until it moves them
                val any = has('X') || has('Y') || has('Z')
                if (!any || has('X')) st.x = Double.NaN; if (!any || has('Y')) st.y = Double.NaN; if (!any || has('Z')) st.z = Double.NaN }
            90 -> st.relXyz = false
            91 -> st.relXyz = true
            92 -> { params(b, from, to)
                if (has('E')) st.e = v('E'); if (has('X')) st.x = v('X'); if (has('Y')) st.y = v('Y'); if (has('Z')) st.z = v('Z')
                if (!has('E') && !has('X') && !has('Y') && !has('Z')) st.e = 0.0 }
        }
    }

    /** A marker-less file's layer starts at an extruding move above every extruding move of the layer before. */
    private fun inferLayer(z1: Double, offset: Long, x0: Double, y0: Double, z0: Double, de: Double, sink: ScanSink) {
        if (layerMarkers == true || sawMarker) return
        if (inferredTop.isNaN() || z1 > inferredTop + 1e-4) {
            // the state before this move: the position and extruder before it
            val before = st.copy().also { it.x = x0; it.y = y0; it.z = z0; it.e = if (st.relE) st.e - de else st.e - de; it.lineNo = st.lineNo - 1 }
            inferredLayer++
            inferredTop = z1
            if (layerMarkers == null) inferred += Triple(offset, before, z1)
            else { st.layer = inferredLayer; before.layer = inferredLayer - 1; sink.layerStart(inferredLayer, offset, before); sink.layerZ(inferredLayer, z1) }
        } else inferredTop = maxOf(inferredTop, z1)
    }

    /** Sets where a pass that starts mid-file stands in an inferred layer's numbering. */
    fun startInferred(layer: Int, top: Double) { inferredLayer = layer; inferredTop = top }
    /** The inferred layer being read (marker-less reading), -1 before the first. */
    val inferredIndex: Int get() = inferredLayer

    private fun arc(cw: Boolean, x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, de: Double, sink: ScanSink) {
        var cx: Double; var cy: Double
        if (has('R') && !has('I') && !has('J')) {
            // the centre from the radius: on the chord's perpendicular bisector, the side the direction and the sign say
            val r = v('R'); val dx = x1 - x0; val dy = y1 - y0; val d = Math.hypot(dx, dy)
            require(d > 0) { "an arc by radius from a point to itself at line ${st.lineNo}: its centre is not defined" }
            val hh = Math.sqrt(maxOf(0.0, r * r - d * d / 4))
            val mx = (x0 + x1) / 2; val my = (y0 + y1) / 2
            // left of the chord for a counter-clockwise arc of less than half a turn
            val sgn = (if (cw) -1.0 else 1.0) * (if (r < 0) -1.0 else 1.0)
            cx = mx - sgn * hh * dy / d; cy = my + sgn * hh * dx / d
        } else { cx = x0 + (if (has('I')) v('I') else 0.0); cy = y0 + (if (has('J')) v('J') else 0.0) }
        val r = Math.hypot(x0 - cx, y0 - cy)
        if (r < 1e-9) { if (de > 0) sink.extrude(x0, y0, z0, x1, y1, z1, de, st) else if (x1 != x0 || y1 != y0 || z1 != z0) sink.travel(x0, y0, z0, x1, y1, z1, de < 0, st); return }
        // chords whose sagitta stays within ARC_SAGITTA_MM (plymeld's choice: a curve stays round at 2000 px per mm)
        val chord = (2 * Math.sqrt(maxOf(0.0, 2 * r * ARC_SAGITTA_MM - ARC_SAGITTA_MM * ARC_SAGITTA_MM))).coerceIn(MIN_CHORD_MM, MAX_CHORD_MM)
        val pts = ArrayList<Double>()
        plymeld.core.check.ModelCheck.arcChords(x0, y0, x1, y1, cx, cy, cw, chord) { _, _, bx, by -> pts += bx; pts += by }
        var total = 0.0
        run { var px = x0; var py = y0; for (k in 0 until pts.size / 2) { total += Math.hypot(pts[2 * k] - px, pts[2 * k + 1] - py); px = pts[2 * k]; py = pts[2 * k + 1] } }
        var px = x0; var py = y0; var pz = z0; var done = 0.0
        for (k in 0 until pts.size / 2) {
            val qx = pts[2 * k]; val qy = pts[2 * k + 1]
            val len = Math.hypot(qx - px, qy - py)
            done += len
            val qz = if (k == pts.size / 2 - 1) z1 else z0 + (z1 - z0) * (if (total > 0) done / total else 1.0)
            val share = if (total > 0) len / total else 1.0
            if (de > 0) sink.extrude(px, py, pz, qx, qy, qz, de * share, st) else sink.travel(px, py, pz, qx, qy, qz, de < 0, st)
            px = qx; py = qy; pz = qz
        }
        if (de < 0) sink.retraction(x1, y1, z1, -de, st)
    }

    private fun mcode(m: Int, b: ByteArray, from: Int, to: Int) {
        when (m) {
            82 -> st.relE = false
            83 -> st.relE = true
            106 -> { params(b, from, to); st.fan = ((if (has('S')) v('S') else 255.0) / 255.0).coerceIn(0.0, 1.0) }
            107 -> st.fan = 0.0
            104, 109 -> { params(b, from, to)
                if (has('S')) { val t = if (has('T')) v('T').toInt() else st.tool; if (t in st.temps.indices) st.temps[t] = v('S') } }
        }
    }

    private fun comment(c: String, offset: Long, sink: ScanSink) {
        val t = c.trim()
        if (sink.wantsComments) sink.comment(t, st)
        when {
            t.startsWith("LAYER_CHANGE") || t.startsWith("CHANGE_LAYER") || (t.startsWith("LAYER:") && t.substring(6).trim().toIntOrNull() != null) -> {
                if (layerMarkers == false) return
                sawMarker = true
                val before = st.copy()
                st.layer++
                pendingZ = true
                sink.layerStart(st.layer, offset, before)
            }
            t.startsWith("Z:") || t.startsWith("Z_HEIGHT:") -> {
                val z = t.substringAfter(':').trim().toDoubleOrNull() ?: return
                if (pendingZ && st.layer >= 0 && layerMarkers != false) { sink.layerZ(st.layer, z); pendingZ = false }
            }
            t.startsWith("HEIGHT:") || t.startsWith("LAYER_HEIGHT:") -> st.height = t.substringAfter(':').trim().toDoubleOrNull() ?: Double.NaN
            t.startsWith("TYPE:") || t.startsWith("FEATURE:") -> st.feature = features.id(t.substringAfter(':').trim())
            t.startsWith("WIDTH:") || t.startsWith("LINE_WIDTH:") -> st.width = t.substringAfter(':').trim().toDoubleOrNull() ?: Double.NaN
            t.startsWith("object start ") -> st.obj = objects.id(t.removePrefix("object start ").trim())
            t.startsWith("object end") -> st.obj = -1
            t.startsWith("printing object ") -> st.obj = objects.id(t.removePrefix("printing object ").trim())
            t.startsWith("stop printing object") -> st.obj = -1
            t.startsWith("MESH:") -> st.obj = t.removePrefix("MESH:").trim().let { if (it == "NONMESH") -1 else objects.id(it) }
        }
    }

    /** Whether a layer still waits for its height from a `;Z:` line (marker mode); the first extrusion gives it instead. */
    val waitingForZ: Boolean get() = pendingZ

    companion object {
        /** The most an arc's chord may stand off the curve, mm (plymeld's choice). */
        const val ARC_SAGITTA_MM = 0.0005
        const val MIN_CHORD_MM = 0.01
        const val MAX_CHORD_MM = 0.5

        /** A number from ASCII bytes (sign, digits, a point, an exponent); NaN when it is none. */
        fun numberOf(b: ByteArray, from: Int, to: Int): Double {
            var k = from
            if (k >= to) return Double.NaN
            var neg = false
            if (b[k] == '-'.code.toByte()) { neg = true; k++ } else if (b[k] == '+'.code.toByte()) k++
            var mant = 0L; var digits = 0; var scale = 0; var any = false
            while (k < to && b[k] >= '0'.code.toByte() && b[k] <= '9'.code.toByte()) {
                if (digits < 18) { mant = mant * 10 + (b[k] - '0'.code.toByte()); if (mant != 0L) digits++ } else scale++
                k++; any = true
            }
            if (k < to && b[k] == '.'.code.toByte()) {
                k++
                while (k < to && b[k] >= '0'.code.toByte() && b[k] <= '9'.code.toByte()) {
                    if (digits < 18) { mant = mant * 10 + (b[k] - '0'.code.toByte()); if (mant != 0L) digits++; scale-- }
                    k++; any = true
                }
            }
            if (!any) return Double.NaN
            if (k < to && (b[k] == 'e'.code.toByte() || b[k] == 'E'.code.toByte())) {
                // rare: the slow, exact way
                return String(b, from, to - from, Charsets.US_ASCII).toDoubleOrNull() ?: Double.NaN
            }
            if (k != to) return Double.NaN
            val v = if (scale == 0) mant.toDouble() else if (scale < 0 && scale >= -22 && mant < (1L shl 53)) mant.toDouble() / POW10[-scale]
                else String(b, from, to - from, Charsets.US_ASCII).toDoubleOrNull() ?: return Double.NaN
            return if (neg) -v else v
        }
        private val POW10 = DoubleArray(23) { Math.pow(10.0, it.toDouble()) }
    }
}
