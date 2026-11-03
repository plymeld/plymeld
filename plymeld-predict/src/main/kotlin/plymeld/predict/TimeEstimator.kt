package plymeld.predict

import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile

/**
 * Print-time estimate for Klipper g-code, applied identically to any slicer's output so the
 * comparison is like-for-like (each slicer's own estimate uses its own model).
 *
 * Model (plymeld's approximation of Klipper's planner, not its code):
 *  - trapezoid per move: accelerate at a, cruise at v <= min(F, max_velocity), decelerate at a;
 *  - a = the last `M204 S` or `SET_VELOCITY_LIMIT ACCEL=` (both dialects), capped at max_accel; pure-Z moves use max_z_velocity / max_z_accel;
 *  - junction speed from square_corner_velocity (junction deviation = scv²(√2−1)/max_accel);
 *  - lookahead: backward pass limits entry speed by what can be braked, forward pass by what
 *    can be reached; peak speed on short moves limited as if accelerating at a/2 (Klipper's
 *    default accel_to_decel, approximated);
 *  - extruder-only moves take |E| / F; `G4 P` dwells are added; macros (heating etc.) are 0.
 * The machine's limits are the caller's (a machine description's motion limits, or the config's); there are no defaults.
 */
class TimeEstimator(
    private val maxVelocity: Double, private val maxAccel: Double,
    private val maxZVelocity: Double, private val maxZAccel: Double,
    private val squareCornerVelocity: Double,
) {
    /** [perCellS]: by calibration cell (`; CELL: <label>` .. `; CELL END`; "" outside every cell): every move and dwell, so the cells and "" add up to [totalS]. */
    class Result(val totalS: Double, val perLayerS: List<Double>, val perFeatureS: Map<String, Double>, val perCellS: Map<String, Double> = emptyMap())

    private class Move(val dx: Double, val dy: Double, val dz: Double, val len: Double, val vMax: Double, val accel: Double, var vEntryMax: Double = 0.0, var vEntry: Double = 0.0, var vExit: Double = 0.0, val layer: Int, val feature: String, val cell: String = "")

    fun estimate(path: Path): Result {
        if (path.toString().endsWith(".3mf")) ZipFile(path.toFile()).use { z ->
            return estimate(z.getInputStream(z.getEntry("Metadata/plate_1.gcode")).bufferedReader(StandardCharsets.UTF_8))
        }
        return Files.newBufferedReader(path).use { estimate(it) }
    }

    fun estimate(r: BufferedReader): Result {
        val jd = squareCornerVelocity * squareCornerVelocity * (Math.sqrt(2.0) - 1) / maxAccel
        val moves = ArrayList<Move>()
        val extraPerLayer = HashMap<Int, Double>()
        var x = 0.0; var y = 0.0; var z = 0.0; var f = 1500.0 / 60; var a = maxAccel
        var layer = -1
        var feature = "None"
        var extruding = false
        val extraPerFeature = HashMap<String, Double>()
        val extraPerCell = HashMap<String, Double>()
        var cell = ""
        var relE = false; var e = 0.0
        fun addTime(s: Double) { extraPerLayer.merge(layer, s, Double::plus); extraPerFeature.merge("retract/dwell", s, Double::plus); extraPerCell.merge(cell, s, Double::plus) }
        fun push(nx: Double, ny: Double, nz: Double) {
            val dx = nx - x; val dy = ny - y; val dz = nz - z
            val len = Math.sqrt(dx * dx + dy * dy + dz * dz)
            if (len < 1e-9) return
            // Klipper's rule for a move with Z in it: its speed and acceleration are held so the Z component
            // stays within max_z_velocity / max_z_accel (a pure Z move: the Z limits themselves; a curved layer's
            // sloped move or a travel that lifts: the Z share of the move scales them)
            var vMax = minOf(f, maxVelocity); var acc = minOf(a, maxAccel)
            if (Math.abs(dz) > 1e-12) {
                val zRatio = len / Math.abs(dz)
                vMax = minOf(vMax, maxZVelocity * zRatio); acc = minOf(acc, maxZAccel * zRatio)
            }
            moves.add(Move(dx / len, dy / len, dz / len, len, vMax, acc, layer = layer, feature = if (extruding) feature else "travel", cell = cell))
            x = nx; y = ny; z = nz
        }
        while (true) {
            val raw = r.readLine() ?: break
            val line = raw.trim()
            if (line.isEmpty()) continue
            // layer and feature markers of both dialects: QIDIStudio's (Q2) and Flash Studio's (Creator 5)
            if (line[0] == ';') {
                if (line.startsWith("; CHANGE_LAYER") || line.startsWith(";LAYER_CHANGE")) layer++
                else if (line.startsWith("; FEATURE:") || line.startsWith(";TYPE:")) feature = line.substringAfter(':').trim()
                else if (line.startsWith("; CELL: ")) cell = line.removePrefix("; CELL: ").trim()
                else if (line == "; CELL END") cell = ""
                continue
            }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val parts = code.split(' ', '\t').filter { it.isNotEmpty() }
            fun num(c: Char): Double? = parts.drop(1).firstOrNull { it[0].uppercaseChar() == c }?.substring(1)?.toDoubleOrNull()
            when (parts[0].uppercase()) {
                "G0", "G1" -> {
                    num('F')?.let { f = it / 60 }
                    val ne = num('E')
                    val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z
                    extruding = ne != null && (if (relE) ne > 0 else ne > e)
                    if (nx == x && ny == y && nz == z) {
                        if (ne != null) { val de = if (relE) ne else ne - e; addTime(Math.abs(de) / f) }
                    } else push(nx, ny, nz)
                    if (ne != null) e = if (relE) e + ne else ne
                }
                "G2", "G3" -> {
                    num('F')?.let { f = it / 60 }
                    val nx = num('X') ?: x; val ny = num('Y') ?: y; val i = num('I') ?: 0.0; val j = num('J') ?: 0.0
                    val ae = num('E'); extruding = ae != null && (if (relE) ae > 0 else ae > e)
                    val cx = x + i; val cy = y + j; val rr = Math.hypot(i, j)
                    val a0 = Math.atan2(y - cy, x - cx); var sweep = Math.atan2(ny - cy, nx - cx) - a0
                    if (parts[0].uppercase() == "G2" && sweep > 0) sweep -= 2 * Math.PI
                    if (parts[0].uppercase() == "G3" && sweep < 0) sweep += 2 * Math.PI
                    val n = Math.max(1, Math.ceil(Math.abs(sweep) * rr / 0.5).toInt())
                    for (k in 1..n) { val ang = a0 + sweep * k / n; push(if (k == n) nx else cx + rr * Math.cos(ang), if (k == n) ny else cy + rr * Math.sin(ang), z) }
                    num('E')?.let { e = if (relE) e + it else it }
                }
                "M204" -> num('S')?.let { a = it }
                "SET_VELOCITY_LIMIT" -> parts.firstOrNull { it.uppercase().startsWith("ACCEL=") }?.substringAfter('=')?.toDoubleOrNull()?.let { a = it }
                "M82" -> relE = false
                "M83" -> relE = true
                "G92" -> num('E')?.let { e = it }
                "G4" -> { num('P')?.let { addTime(it / 1000) }; num('S')?.let { addTime(it) } }
            }
        }
        // junction limits
        for (k in moves.indices) {
            val m = moves[k]
            if (k == 0) { m.vEntryMax = 0.0; continue }
            val p = moves[k - 1]
            val cosTheta = -(p.dx * m.dx + p.dy * m.dy + p.dz * m.dz)
            val vj = if (cosTheta > 0.999999) 0.0 else if (cosTheta < -0.999999) Double.MAX_VALUE else {
                val sinHalf = Math.sqrt((1 - cosTheta) / 2)
                Math.sqrt(minOf(p.accel, m.accel) * jd * sinHalf / (1 - sinHalf))
            }
            m.vEntryMax = minOf(vj, p.vMax, m.vMax)
        }
        // backward pass
        var next = 0.0
        for (k in moves.indices.reversed()) {
            val m = moves[k]
            m.vExit = next
            m.vEntry = minOf(m.vEntryMax, Math.sqrt(next * next + 2 * m.accel * m.len))
            next = m.vEntry
        }
        // forward pass + time
        val perLayer = HashMap<Int, Double>()
        val perFeature = HashMap<String, Double>()
        val perCell = HashMap<String, Double>()
        var prevExit = 0.0
        var total = 0.0
        for (m in moves) {
            val v0 = minOf(m.vEntry, prevExit)
            val v1 = minOf(m.vExit, Math.sqrt(v0 * v0 + 2 * m.accel * m.len))
            // peak speed: limited by vMax and by accelerate-then-brake within the move at a/2 (accel_to_decel)
            val ad = m.accel / 2
            val vPeak = minOf(m.vMax, Math.sqrt((2 * ad * m.len + v0 * v0 + v1 * v1) / 2))
            val dAcc = (vPeak * vPeak - v0 * v0) / (2 * m.accel)
            val dDec = (vPeak * vPeak - v1 * v1) / (2 * m.accel)
            val t = if (dAcc + dDec <= m.len) (vPeak - v0) / m.accel + (vPeak - v1) / m.accel + (m.len - dAcc - dDec) / vPeak
                else 2 * m.len / (v0 + v1).coerceAtLeast(1e-6)   // degenerate: average speed
            total += t
            perLayer.merge(m.layer, t, Double::plus)
            perFeature.merge(m.feature, t, Double::plus)
            perCell.merge(m.cell, t, Double::plus)
            prevExit = v1
        }
        for ((l, s) in extraPerLayer) { total += s; perLayer.merge(l, s, Double::plus) }
        for ((f, s) in extraPerFeature) perFeature.merge(f, s, Double::plus)
        for ((c, s) in extraPerCell) perCell.merge(c, s, Double::plus)
        val n = (perLayer.keys.maxOrNull() ?: -1) + 1
        return Result(total, (0 until n).map { perLayer[it] ?: 0.0 }, perFeature, perCell)
    }
}
