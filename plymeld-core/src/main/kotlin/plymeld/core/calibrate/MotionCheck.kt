package plymeld.core.calibrate

/**
 * Reads a motion plate's g-code back ([MotionPlate]) and checks it against its specification, as the printer will run it
 *. Its reference is the [MotionSpec] and the cross's outline it
 * works out itself from [MotionGeometry] (the corners, the arm, the margin, which speed each face takes), never the generator's
 * loops:
 *
 *  - as many layers as the plate has, and nothing but its cells: the slab on each of its layers, then each band's inner and
 *    outer wall on each of its layers; no other extrusion between the plate's first line and its last;
 *  - every slab layer at its own height, speed, acceleration, width, and filament per mm (the first layer's on the first, the
 *    frame's above); every tower line at its band's acceleration, its wall's width, the bead's filament per mm, flat on its layer;
 *  - on every tower layer each wall's lines lie on the cross's twelve faces, each found from the line itself (its direction,
 *    the side of the cross it runs along, set in from the outline by half the outer line, the inner wall a wall spacing more, a
 *    groove layer half the outer line more), each at its face's speed ([MotionGeometry.FACE_SPEED]; the inner wall's held to its
 *    flow cap), running counter-clockwise, every face laid once and its full length;
 *  - each wall's loop starts at the seam on face 12, [MotionGeometry.seamAt] of the way along it; the inner wall before the
 *    outer on every layer;
 *  - the slab's size from its outer ring, and the chamfer at the front arm's front-left corner;
 *  - on every slab layer the arm marks, found as gaps in the layer's lines along each arm's end face: n notches at arm n (1 the
 *    front, counter-clockwise), each a mark's width, past the face's first third, none reaching within a line of the face;
 *  - the one temperature waited for before every line, the part fan off on the first layer and the filament's above;
 *  - on the tower's layers no travel longer than [SHORT] at or below the layer's height crosses the cross's outline.
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object MotionCheck {
    private class Move(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val z0Um: Long, val z1Um: Long, val e: Double,
                       val fMmMin: Double, val accel: Double, val tempC: Int?, val waited: Boolean, val fanPct: Double, val layer: Int, val widthMm: Double, val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
    }

    /** A travel shorter than this (mm) is the step from the inner wall's seam to the outer's: not judged. */
    private const val SHORT = 1.0

    fun check(spec: MotionSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        val said = HashSet<String>()
        fun bad(s: String) { if (out.size < 60) out += s }
        fun once(key: String, s: String) { if (said.add(key)) bad(s) }
        val g = MotionGeometry
        val (moves, layers) = parse(gcode, spec.tool)
        if (layers != spec.layers) bad("$layers layers in the g-code, not ${spec.layers} (the slab's ${g.slabLayers} and ${spec.bands} bands of ${spec.bandLayers})")
        // the cells
        val want = LinkedHashSet<String>()
        for (l in 0 until g.slabLayers) want += spec.slabLabel(l)
        for (b in 0 until spec.bands) { want += spec.innerLabel(b); want += spec.outerLabel(b) }
        val byCell = moves.groupBy { it.cell }
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        for (c in byCell.keys) if (c !in want && !Part.isPrimeLine(c)) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have")
        // every line at its values
        val fa = spec.filamentMm2
        for (m in moves) {
            val slabLayer = (0 until g.slabLayers).firstOrNull { spec.slabLabel(it) == m.cell }
            val band = spec.bandOfLabel(m.cell)
            if (slabLayer == null && band == null) continue
            if (m.tempC != spec.tempC) once("${m.cell}/temp", "'${m.cell}': at ${m.tempC} C, not ${spec.tempC} C")
            else if (!m.waited) once("${m.cell}/wait", "'${m.cell}': no wait (M109) for ${spec.tempC} C before it")
            val wantFan = if (slabLayer == 0) 0.0 else spec.fanPct
            if (Math.abs(m.fanPct - wantFan) > 0.5) once("${m.cell}/fan", "'${m.cell}': fan ${fmt(m.fanPct)} %, not ${fmt(wantFan)} %")
            val lw = when { slabLayer != null -> if (slabLayer == 0) spec.firstWidthUm else spec.widthUm; band!!.second -> spec.innerWidthUm; else -> spec.widthUm }
            val hz = if (slabLayer != null) spec.heightOf(slabLayer) else spec.layerUm
            val wantA = if (slabLayer != null) (if (slabLayer == 0) spec.adhesion else spec.frame).accel else spec.accels[band!!.first]
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - lw / 1000.0) > 5e-4) once("${m.cell}/w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(lw / 1000.0, 3)}")
            if (Math.abs(m.accel - wantA) > 0.5) once("${m.cell}/a", "'${m.cell}': acceleration ${fmt(m.accel)}, not ${fmt(wantA)}")
            val wantE = spec.beadMm2(lw, hz) / fa * spec.flowRatio
            if (Math.abs(m.e / m.len - wantE) > wantE * 0.01 + 2e-5 / m.len) once("${m.cell}/e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(wantE, 5)}")
            if (slabLayer != null) {
                val pv = if (slabLayer == 0) spec.adhesion else spec.frame
                if (Math.abs(m.fMmMin / 60 - pv.speed) > 1.0 / 60 + 1e-9) once("${m.cell}/v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(pv.speed, 2)}")
                if (m.z1Um != spec.zTop(slabLayer) || m.z0Um != m.z1Um) once("${m.cell}/z", "'${m.cell}': printed at z ${m.z1Um / 1000.0}, not ${spec.zTop(slabLayer) / 1000.0}")
            } else {
                val i = m.layer - g.slabLayers
                if (i < 0 || i >= spec.towerLayers || spec.bandOf(i) != band!!.first) { once("${m.cell}/layer", "'${m.cell}' printed on layer ${m.layer + 1}, which is not band ${band!!.first + 1}'s"); continue }
                val top = spec.slabTop + (i + 1) * spec.layerUm
                if (m.z1Um != top || m.z0Um != m.z1Um) once("${m.cell}/z", "'${m.cell}': a line at z ${m.z1Um / 1000.0} on the layer whose top is ${top / 1000.0}")
            }
        }
        val origin = slab(spec, moves, ::bad) ?: return out
        marks(spec, moves, origin, ::bad)
        tower(spec, moves, origin, ::bad, ::once)
        out += travels(gcode, spec, origin)
        return out
    }

    /** The slab from the g-code's own lines: its size, where it starts, its chamfer. Returns its front-left corner (mm), or null. */
    private fun slab(spec: MotionSpec, moves: List<Move>, bad: (String) -> Unit): DoubleArray? {
        val g = MotionGeometry
        val size = spec.slabSize / 1000.0
        val first = moves.filter { it.cell == spec.slabLabel(0) }
        if (first.isEmpty()) { bad("no first layer to measure the slab from"); return null }
        val fw = spec.firstWidthUm / 1000.0
        val ox = first.minOf { minOf(it.x0, it.x1) } - fw / 2; val oy = first.minOf { minOf(it.y0, it.y1) } - fw / 2
        val a = g.arm / 1000.0
        for (l in 0 until g.slabLayers) {
            val mine = moves.filter { it.cell == spec.slabLabel(l) }
            if (mine.isEmpty()) continue
            val lw = (if (l == 0) spec.firstWidthUm else spec.widthUm) / 1000.0
            val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - lw / 2, mine.minOf { minOf(it.y0, it.y1) } - lw / 2, mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2, mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2)
            if (Math.abs(b[2] - b[0] - size) > 0.03 || Math.abs(b[3] - b[1] - size) > 0.03) bad("layer $l: the slab is ${fmt(b[2] - b[0], 2)} x ${fmt(b[3] - b[1], 2)} mm, not ${fmt(size, 1)} x ${fmt(size, 1)} (three arms of ${fmt(a, 0)} mm and ${fmt(g.margin / 1000.0, 0)} mm on each side)")
            if (Math.abs(b[0] - ox) > 0.03 || Math.abs(b[1] - oy) > 0.03) bad("layer $l: the slab starts at (${fmt(b[0], 2)}, ${fmt(b[1], 2)}), not where its first layer does (${fmt(ox, 2)}, ${fmt(oy, 2)})")
            // the chamfer: the front arm's front-left corner (ox + a, oy), its nearest ring point by x + y
            val ring = mine.filter { it.y0 < oy + a && it.x0 > ox + a - 2 * lw && it.y1 < oy + a && it.x1 > ox + a - 2 * lw }
            val corner = ring.flatMap { listOf(it.x0 - ox - a + it.y0 - oy, it.x1 - ox - a + it.y1 - oy) }.minOrNull()
            val wantCorner = g.chamfer / 1000.0 + lw / 2 * Math.sqrt(2.0)
            if (corner == null || Math.abs(corner - wantCorner) > 0.05) bad("layer $l: the front arm's front-left corner is cut ${corner?.let { fmt(it - lw / 2 * Math.sqrt(2.0), 2) } ?: "?"} mm (by x + y from the corner), not the ${fmt(g.chamfer / 1000.0, 1)} mm chamfer")
        }
        return doubleArrayOf(ox, oy)
    }

    /**
     * The arm marks on every slab layer, from the layer's own lines. For each arm n, a probe runs the length of its end face
     * (face [MotionGeometry.endFace], worked out from the cross's corners and the margin, never the generator's outline) half the
     * layer's line in from the slab's edge: a stretch of it that no line covers (within half a line of it) and that is wider than a
     * line is a notch, since lines laid side by side leave no such gap. Arm n must show n, each [MotionGeometry.markWidth] wide and
     * wholly past the first third of the face (where ringing is read). A second probe a line width out from the face must be
     * covered all along: no mark reaches the wall.
     */
    private fun marks(spec: MotionSpec, moves: List<Move>, o: DoubleArray, bad: (String) -> Unit) {
        val g = MotionGeometry
        val a = g.arm / 1000.0; val m = g.margin / 1000.0; val want = g.markWidth / 1000.0
        val step = 0.01
        for (l in 0 until g.slabLayers) {
            val mine = moves.filter { it.cell == spec.slabLabel(l) }
            if (mine.isEmpty()) continue
            val lw = (if (l == 0) spec.firstWidthUm else spec.widthUm) / 1000.0
            for (n in 1..g.arms) {
                val k = g.endFace(n)
                val f = faceEnds(k, 0.0, o)
                val ux = (f[2] - f[0]) / a; val uy = (f[3] - f[1]) / a
                // outward: to the right of a counter-clockwise face
                val nx = uy; val ny = -ux
                /** The uncovered stretches wider than a line along the face, [out] mm out from it: (from, to) in mm from its start. */
                fun gaps(out: Double): List<Pair<Double, Double>> {
                    val x0 = f[0] + nx * out; val y0 = f[1] + ny * out; val x1 = f[2] + nx * out; val y1 = f[3] + ny * out
                    val near = mine.filter { mv -> maxOf(mv.x0, mv.x1) > minOf(x0, x1) - lw && minOf(mv.x0, mv.x1) < maxOf(x0, x1) + lw && maxOf(mv.y0, mv.y1) > minOf(y0, y1) - lw && minOf(mv.y0, mv.y1) < maxOf(y0, y1) + lw }
                    val runs = ArrayList<Pair<Double, Double>>()
                    var from = Double.NaN
                    val samples = Math.round(a / step).toInt()
                    for (i in 0..samples) {
                        val t = i * step
                        val px = x0 + ux * t; val py = y0 + uy * t
                        val covered = near.any { segDist(px, py, it) <= lw / 2 + step / 2 }
                        if (!covered && from.isNaN()) from = t
                        if ((covered || i == samples) && !from.isNaN()) { runs += from to t; from = Double.NaN }
                    }
                    return runs.filter { (s, e) -> e - s > lw }
                }
                val where = "slab layer ${l + 1}: the ${g.armName(n)} arm's end (face $k)"
                val found = gaps(m - lw / 2)
                if (found.size != n) bad("$where: ${found.size} notch(es) in the slab's edge, not $n (${found.joinToString { (s, e) -> "${fmt(s, 2)}-${fmt(e, 2)}" }} mm along the face)")
                for ((s, e) in found) {
                    if (Math.abs(e - s - want) > 0.05) bad("$where: a notch ${fmt(e - s, 2)} mm wide (${fmt(s, 2)}-${fmt(e, 2)} mm along the face), not ${fmt(want, 2)}")
                    if (s < a / 3) bad("$where: a notch from ${fmt(s, 2)} mm along the face, within its first third (${fmt(a / 3, 2)} mm), where ringing is read")
                }
                gaps(lw).firstOrNull()?.let { (s, e) -> bad("$where: no slab ${fmt(s, 2)}-${fmt(e, 2)} mm along the face a line width (${fmt(lw, 2)} mm) out from it: a mark reaches the wall") }
            }
        }
    }

    /** The distance (mm) from ([px], [py]) to [mv]'s line. */
    private fun segDist(px: Double, py: Double, mv: Move): Double {
        val dx = mv.x1 - mv.x0; val dy = mv.y1 - mv.y0
        val ll = dx * dx + dy * dy
        val t = if (ll == 0.0) 0.0 else (((px - mv.x0) * dx + (py - mv.y0) * dy) / ll).coerceIn(0.0, 1.0)
        return Math.hypot(px - mv.x0 - t * dx, py - mv.y0 - t * dy)
    }

    /** Face [k] (1-based) at inset [d] (mm) from the outline whose bounding box starts at [o] (mm): its start and end points. */
    private fun faceEnds(k: Int, d: Double, o: DoubleArray): DoubleArray {
        val g = MotionGeometry
        val a = g.arm / 1000.0; val m = g.margin / 1000.0
        fun c(j: Int) = g.corner(j).let { (x, y) -> doubleArrayOf(o[0] + m + x * a, o[1] + m + y * a) }
        fun shifted(j: Int): DoubleArray {
            val p = c(j); val pa = c(j + g.faces - 1); val pb = c(j + 1)
            val inX = Math.signum(p[0] - pa[0]); val inY = Math.signum(p[1] - pa[1]); val outX = Math.signum(pb[0] - p[0]); val outY = Math.signum(pb[1] - p[1])
            return doubleArrayOf(p[0] + d * (-inY - outY), p[1] + d * (inX + outX))
        }
        val s = shifted(k - 1); val e = shifted(k % g.faces)
        return doubleArrayOf(s[0], s[1], e[0], e[1])
    }

    /** The tower, layer by layer: each wall's lines on the faces, at their speeds, the seams, the order. */
    private fun tower(spec: MotionSpec, moves: List<Move>, o: DoubleArray, bad: (String) -> Unit, once: (String, String) -> Unit) {
        val g = MotionGeometry
        val w = spec.widthUm / 1000.0; val wi = spec.innerWidthUm / 1000.0
        val spacing = (w + wi) / 2 - spec.layerUm / 1000.0 * (1 - Math.PI / 4)
        for (i in 0 until spec.towerLayers) {
            val b = spec.bandOf(i)
            val groove = b > 0 && i % spec.bandLayers < g.grooveLayers
            val layer = g.slabLayers + i
            val walls = listOf(false, true).associateWith { inner -> moves.filter { it.cell == (if (inner) spec.innerLabel(b) else spec.outerLabel(b)) && it.layer == layer } }
            for ((inner, ms) in walls) {
                val name = "layer ${layer + 1} (band ${b + 1}${if (groove) ", a groove layer" else ""}), the ${if (inner) "inner" else "outer"} wall"
                if (ms.isEmpty()) { once("$i/$inner/none", "$name: not printed"); continue }
                val d = w / 2 + (if (inner) spacing else 0.0) + (if (groove) w / 2 else 0.0)
                val laid = DoubleArray(g.faces)
                for (m in ms) {
                    val face = (1..g.faces).firstOrNull { k ->
                        val f = faceEnds(k, d, o)
                        val alongX = g.alongX(k)
                        val dir = if (alongX) Math.signum(f[2] - f[0]) else Math.signum(f[3] - f[1])
                        val onLine = if (alongX) Math.abs(m.y0 - f[1]) < 0.03 && Math.abs(m.y1 - f[1]) < 0.03 else Math.abs(m.x0 - f[0]) < 0.03 && Math.abs(m.x1 - f[0]) < 0.03
                        val moving = if (alongX) Math.signum(m.x1 - m.x0) else Math.signum(m.y1 - m.y0)
                        val lo = if (alongX) minOf(f[0], f[2]) else minOf(f[1], f[3]); val hi = if (alongX) maxOf(f[0], f[2]) else maxOf(f[1], f[3])
                        val mid = if (alongX) (m.x0 + m.x1) / 2 else (m.y0 + m.y1) / 2
                        onLine && moving == dir && mid > lo - 0.03 && mid < hi + 0.03
                    }
                    if (face == null) { once("$i/$inner/off", "$name: a line from (${fmt(m.x0, 2)}, ${fmt(m.y0, 2)}) to (${fmt(m.x1, 2)}, ${fmt(m.y1, 2)}) lies on none of the cross's faces at ${fmt(d, 3)} mm in from its outline${if (groove) " (a groove layer: set in half the outer line more)" else ""}, counter-clockwise"); continue }
                    laid[face - 1] += m.len
                    val wantV = if (inner) spec.innerSpeed(face) else spec.faceSpeed(face)
                    if (Math.abs(m.fMmMin / 60 - wantV) > 1.0 / 60 + 1e-9) once("$i/$inner/v$face", "$name: face $face (${if (g.alongX(face)) "along X" else "along Y"}) at ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(wantV, 2)} (speed ${g.FACE_SPEED[face - 1] + 1})")
                }
                for (k in 1..g.faces) {
                    val f = faceEnds(k, d, o)
                    val len = Math.hypot(f[2] - f[0], f[3] - f[1])
                    if (Math.abs(laid[k - 1] - len) > 0.05) once("$i/$inner/len$k", "$name: face $k laid ${fmt(laid[k - 1], 2)} mm, not its ${fmt(len, 2)} mm")
                }
                // the seam: face 12, seamAt of the way along it
                val f12 = faceEnds(12, d, o)
                val sx = f12[0] + (f12[2] - f12[0]) * g.seamAt; val sy = f12[1] + (f12[3] - f12[1]) * g.seamAt
                val first = ms.first(); val last = ms.last()
                if (Math.hypot(first.x0 - sx, first.y0 - sy) > 0.03) once("$i/$inner/seam", "$name: starts at (${fmt(first.x0, 2)}, ${fmt(first.y0, 2)}), not at the seam on face 12 (${fmt(sx, 2)}, ${fmt(sy, 2)})")
                if (Math.hypot(last.x1 - sx, last.y1 - sy) > 0.03) once("$i/$inner/close", "$name: ends at (${fmt(last.x1, 2)}, ${fmt(last.y1, 2)}), not back at its seam")
            }
            val inn = walls.getValue(true); val outr = walls.getValue(false)
            if (inn.isNotEmpty() && outr.isNotEmpty() && outr.first().seq < inn.last().seq) once("order", "layer ${layer + 1}: the outer wall is printed before the inner wall")
        }
    }

    /**
     * The travels on the tower's layers, from the g-code text: one at or below its layer's top, longer than [SHORT], that crosses
     * the cross's outline would drag the nozzle over a wall.
     */
    private fun travels(gcode: String, spec: MotionSpec, o: DoubleArray): List<String> {
        val out = ArrayList<String>()
        val g = MotionGeometry
        val edges = (1..g.faces).map { faceEnds(it, 0.0, o) }
        fun crosses(ax: Double, ay: Double, bx: Double, by: Double): Int? = edges.indexOfFirst { e ->
            fun side(px: Double, py: Double, qx: Double, qy: Double, rx: Double, ry: Double) = Math.signum((qx - px) * (ry - py) - (qy - py) * (rx - px))
            side(ax, ay, bx, by, e[0], e[1]) * side(ax, ay, bx, by, e[2], e[3]) < 0 && side(e[0], e[1], e[2], e[3], ax, ay) * side(e[0], e[1], e[2], e[3], bx, by) < 0
        }.takeIf { it >= 0 }?.let { it + 1 }
        var layer = -1
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        for (raw in gcode.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            val i = layer - g.slabLayers
            if (!x.isNaN() && (nx != x || ny != y) && e <= 0 && i >= 0 && i < spec.towerLayers && out.size < 20 && Math.hypot(nx - x, ny - y) > SHORT) {
                val top = (spec.slabTop + (i + 1) * spec.layerUm) / 1000.0
                if (minOf(z, nz) <= top + 1e-6) crosses(x, y, nx, ny)?.let { f ->
                    out += "a travel at z ${fmt(minOf(z, nz), 3)}, the layer's height (${fmt(top, 3)}), crosses face $f (from ${fmt(x, 2)}, ${fmt(y, 2)} to ${fmt(nx, 2)}, ${fmt(ny, 2)}): no lift"
                }
            }
            x = nx; y = ny; z = nz
        }
        return out
    }

    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Extrusion moves inside cells with the state they ran in (the nozzle target for [tool] and whether a wait to that target
     * came after it was set; the Z before and after the move), and the number of layer markers. Extrusion outside every cell
     * between the plate's first line and its last is returned under the cell name "(none)". Relative extrusion (M83).
     */
    private fun parse(gcode: String, tool: Int): Pair<List<Move>, Int> {
        val inCells = ArrayList<Move>(); val outside = ArrayList<Pair<Int, Move>>()
        var cellsClosed = 0; var cell: String? = null
        var x = Double.NaN; var y = Double.NaN; var zUm = 0L
        var f = 0.0; var accel = Double.NaN; var fan = 0.0
        val target = HashMap<Int, Int>(); val waited = HashMap<Int, Boolean>()
        var curTool = 0; var layer = -1; var width = Double.NaN
        for ((seq, raw) in gcode.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: ").trim(); width = Double.NaN; continue }
            if (line == "; CELL END") { cell = null; cellsClosed++; continue }
            widthOf(line)?.let { width = it }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            val words = code.split(Regex("\\s+"))
            fun num(letter: Char) = words.drop(1).firstOrNull { it.isNotEmpty() && it[0] == letter }?.substring(1)?.toDouble()
            when (words[0]) {
                "G0", "G1" -> {
                    num('F')?.let { f = it }
                    val nx = num('X') ?: x; val ny = num('Y') ?: y
                    val z0 = zUm
                    num('Z')?.let { zUm = Math.round(it * 1000) }
                    val e = num('E') ?: 0.0
                    val len = if (x.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
                    if (e > 0 && len > 0) {
                        val mv = Move(cell ?: "(none)", x, y, nx, ny, z0, zUm, e, f, accel, target[tool], waited[tool] == true, fan, layer, width, seq)
                        if (cell != null) inCells += mv else outside += cellsClosed to mv
                    }
                    x = nx; y = ny
                }
                "M204" -> num('S')?.let { accel = it }
                "SET_VELOCITY_LIMIT" -> words.firstOrNull { it.startsWith("ACCEL=") }?.let { accel = it.removePrefix("ACCEL=").toDouble() }
                "M104" -> { val tt = num('T')?.toInt() ?: curTool; num('S')?.toInt()?.let { if (target[tt] != it) waited[tt] = false; target[tt] = it } }
                "M109" -> { val tt = num('T')?.toInt() ?: curTool; num('S')?.toInt()?.let { target[tt] = it; waited[tt] = true } }
                "M106" -> if (num('P') == null) fan = (num('S') ?: 255.0) / 255 * 100
                "M107" -> fan = 0.0
                else -> if (words[0].length >= 2 && words[0][0] == 'T' && words[0].substring(1).all { it.isDigit() }) curTool = words[0].substring(1).toInt()
            }
        }
        return (inCells + outside.filter { (closed, _) -> closed in 1 until cellsClosed }.map { it.second }) to (layer + 1)
    }

    private fun fmt(v: Double, d: Int = 0) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
}
