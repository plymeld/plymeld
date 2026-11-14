package plymeld.check

import plymeld.config.OrcaConfig
import java.io.BufferedReader
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Pre-print safety check ("virtual printer"): reads ANY slicer's Klipper g-code and checks it
 * against the machine's own limits from the imported settings. It knows nothing about how the
 * file was made. Run before sending anything to a printer.
 *
 * Checks (each a finding with the first offending line):
 *  - every command's numbers as the firmware reads them (BadNumbers): no value that is not a finite number where one
 *    stands, no move at a feed rate of 0 or below, no temperature, fan, or wait below 0, no acceleration of 0 or below
 *  - extruding moves inside printable_area and outside bed_exclude_area (after the first layer marker)
 *  - Z within [0, printable_height] after the first layer marker (start g-code may wipe below 0)
 *  - nozzle temperatures 0 or within [nozzle_temperature_range_low, _high]; bed within [0, 120]
 *  - no extrusion before a nozzle heat command or a PRINT_START macro
 *  - feed rate within machine_max_speed_x/y (travel/print) — Klipper would clamp, but a larger
 *    value means the file assumes a different machine
 *  - extrusion per mm below 3x the widest plausible bead (catches unit errors)
 *  - print head never meets printed material: when the settings give the head's clearance
 *    (extruder_clearance_max_radius / extruder_clearance_radius, extruder_clearance_height_to_rod),
 *    no move comes within that radius of anything printed taller than the nozzle, and nothing
 *    printed is taller than the nozzle by more than the rod clearance. Printed material is tracked
 *    as one bounding box per layer height (conservative). Without those figures: layer Z strictly
 *    increasing (the only order that is safe without knowing the head).
 */
object PrePrintCheck {
    class Finding(val rule: String, val line: Int, val detail: String) {
        override fun toString() = "FAIL [$rule] line $line: $detail"
    }

    fun check(path: Path, cfg: OrcaConfig): List<Finding> =
        if (path.toString().endsWith(".3mf")) ZipFile(path.toFile()).use { z -> check(z.getInputStream(z.getEntry("Metadata/plate_1.gcode")).bufferedReader(), cfg) }
        else Files.newBufferedReader(path).use { check(it, cfg) }

    private fun polygon(v: List<String>?): List<Pair<Double, Double>> =
        v?.map { p -> p.split('x').let { it[0].toDouble() to it[1].toDouble() } } ?: emptyList()

    private fun inside(poly: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var c = false
        for (i in poly.indices) {
            val (xi, yi) = poly[i]; val (xj, yj) = poly[(i + 1) % poly.size]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
        }
        return c
    }

    fun check(r: BufferedReader, cfg: OrcaConfig): List<Finding> {
        val out = ArrayList<Finding>()
        fun fail(rule: String, line: Int, d: String) { if (out.none { it.rule == rule }) out.add(Finding(rule, line, d)) }
        val bed = polygon(cfg.list("printable_area"))
        val exclude = polygon(cfg.list("bed_exclude_area")).takeIf { it.size >= 3 }
        val maxZ = cfg.require("printable_height").toDouble()
        val tLo = cfg.require("nozzle_temperature_range_low").toDouble()
        val tHi = cfg.require("nozzle_temperature_range_high").toDouble()
        val vMax = minOf(cfg.require("machine_max_speed_x").toDouble(), cfg.require("machine_max_speed_y").toDouble())
        val fd = cfg.require("filament_diameter").toDouble()
        val nozzle = cfg.require("nozzle_diameter").toDouble()
        // widest plausible bead: 2 x nozzle wide, 1 x nozzle high
        val maxEPerMm = 3 * (2 * nozzle * nozzle) / (Math.PI * fd * fd / 4)
        var x = 0.0; var y = 0.0; var z = 0.0; var e = 0.0; var relE = false
        var heated = false; var inLayers = false; var lastLayerZ = Double.NEGATIVE_INFINITY
        var lineNo = 0
        val headR = (cfg.peek("extruder_clearance_max_radius") ?: cfg.peek("extruder_clearance_radius"))?.firstOrNull()?.toDoubleOrNull()
        val rodH = cfg.peek("extruder_clearance_height_to_rod")?.firstOrNull()?.toDoubleOrNull()
        val headKnown = headR != null && rodH != null
        // printed material: one XY bounding box per extrusion height (minX, minY, maxX, maxY)
        val printed = java.util.TreeMap<Double, DoubleArray>()
        var maxPrintedZ = Double.NEGATIVE_INFINITY
        fun segBoxDist(ax: Double, ay: Double, bx: Double, by: Double, b: DoubleArray): Double {
            fun ptBox(px: Double, py: Double) = Math.hypot(maxOf(b[0] - px, 0.0, px - b[2]), maxOf(b[1] - py, 0.0, py - b[3]))
            fun ptSeg(px: Double, py: Double): Double {
                val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
                val u = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
                return Math.hypot(px - ax - u * dx, py - ay - u * dy)
            }
            // a segment crossing the box is at distance 0: sample it finely enough for the box size
            val steps = maxOf(1, (Math.hypot(bx - ax, by - ay) / 0.5).toInt())
            var best = minOf(ptBox(ax, ay), ptBox(bx, by))
            for (s in 1 until steps) { val u = s.toDouble() / steps; best = minOf(best, ptBox(ax + (bx - ax) * u, ay + (by - ay) * u)) }
            return minOf(best, ptSeg(b[0], b[1]), ptSeg(b[2], b[1]), ptSeg(b[0], b[3]), ptSeg(b[2], b[3]))
        }
        while (true) {
            val raw = r.readLine() ?: break
            lineNo++
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(";")) {
                val lz = when {
                    line.startsWith("; Z_HEIGHT:") -> line.substringAfter(':').trim().toDoubleOrNull()
                    line.startsWith(";Z:") -> line.substringAfter(':').trim().toDoubleOrNull()
                    else -> null
                }
                if (lz != null) {
                    inLayers = true
                    if (!headKnown && lz <= lastLayerZ) fail("layer-z-increasing", lineNo, "layer z $lz after $lastLayerZ (the head's clearance is not in the settings, so no lower layer can be checked as safe)")
                    lastLayerZ = lz
                }
                continue
            }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            // a number the firmware may refuse or act wrongly on (BadNumbers: the rule plymeld's writer, its pre-print check,
            // and `plymeld printer send` apply)
            plymeld.gcode.BadNumbers.inCommand(code)?.let { fail("numbers", lineNo, "'$code': $it") }
            val parts = code.split(' ', '\t').filter { it.isNotEmpty() }
            val cmd = parts[0].uppercase()
            fun num(c: Char): Double? = parts.drop(1).firstOrNull { it[0].uppercaseChar() == c }?.substring(1)?.toDoubleOrNull()
            when (cmd) {
                "PRINT_START" -> {
                    heated = true
                    parts.drop(1).firstOrNull { it.startsWith("HOTEND=") }?.substringAfter('=')?.toDoubleOrNull()?.let { t ->
                        if (t != 0.0 && (t < tLo || t > tHi)) fail("nozzle-temp", lineNo, "PRINT_START HOTEND=$t outside [$tLo, $tHi]")
                    }
                }
                "M104", "M109" -> num('S')?.let { t ->
                    if (t > 0) heated = true
                    // standby temperatures (tool changers) are allowed below the range; above is not
                    if (t > tHi) fail("nozzle-temp", lineNo, "$cmd S$t above $tHi")
                    if (cmd == "M109" && t > 0 && t < tLo) fail("nozzle-temp", lineNo, "M109 waits for $t, below $tLo")
                }
                "M140", "M190" -> num('S')?.let { t -> if (t < 0 || t > 120) fail("bed-temp", lineNo, "$cmd S$t outside [0, 120]") }
                "M82" -> relE = false
                "M83" -> relE = true
                "G92" -> num('E')?.let { e = it }
                "G0", "G1", "G2", "G3" -> {
                    val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z
                    val ne = num('E')
                    val de = if (ne == null) 0.0 else if (relE) ne else ne - e
                    if (ne != null) e = if (relE) e + ne else ne
                    num('F')?.let { f -> if (f / 60 > vMax + 1e-9) fail("feed-rate", lineNo, "F$f = ${f / 60} mm/s above machine max $vMax") }
                    val len = Math.hypot(nx - x, ny - y)
                    if (de > 0 && !heated) fail("cold-extrusion", lineNo, "extrusion before any nozzle heating")
                    if (inLayers) {
                        if (nz < -1e-9 || nz > maxZ) fail("z-range", lineNo, "Z$nz outside [0, $maxZ]")
                        val low = minOf(z, nz)
                        if (headKnown && low < maxPrintedZ - 1e-3) {
                            if (maxPrintedZ > low + rodH + 1e-3) fail("gantry-clearance", lineNo, "printed material reaches z $maxPrintedZ, more than the rod clearance $rodH above the nozzle at z $low")
                            for ((pz, b) in printed.tailMap(low + 1e-3, false)) {
                                val d = segBoxDist(x, y, nx, ny, b)
                                if (d < headR) { fail("head-clearance", lineNo, "move at z $low passes %.1f mm from material printed at z $pz (head clearance radius $headR)".format(java.util.Locale.ROOT, d)); break }
                            }
                        }
                        if (de > 0 && len > 0) {
                            if (bed.size >= 3 && (!inside(bed, nx, ny) || !inside(bed, x, y))) fail("bed-area", lineNo, "extrusion to ($nx, $ny) outside printable_area")
                            if (exclude != null && (inside(exclude, nx, ny) || inside(exclude, x, y))) fail("excluded-area", lineNo, "extrusion to ($nx, $ny) inside bed_exclude_area")
                            if (cmd == "G1" && de / len > maxEPerMm) fail("extrusion-per-mm", lineNo, "E/mm ${de / len} above $maxEPerMm")
                            val b = printed.getOrPut(nz) { doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE) }
                            b[0] = minOf(b[0], x, nx); b[1] = minOf(b[1], y, ny); b[2] = maxOf(b[2], x, nx); b[3] = maxOf(b[3], y, ny)
                            maxPrintedZ = maxOf(maxPrintedZ, nz)
                        }
                    }
                    x = nx; y = ny; z = nz
                }
            }
        }
        if (!inLayers) fail("layers", lineNo, "no layer markers found")
        return out
    }
}
