package plymeld.core.calibrate

/**
 * Reads a seam plate's g-code back ([SeamPlate]) and checks it against its specification, as the printer will run it
 *. Its reference is the [SeamSpec] and the layout figures it
 * works out itself from [SeamGeometry] and the g-code's own lines, never the generator's paths:
 *
 *  - as many layers as the plate has, and nothing but its cells: the slab on each of its layers, then every piece's inner
 *    and outer wall on every layer above; no other extrusion between the plate's first line and its last;
 *  - every slab layer at its own height, speed, acceleration, width, and filament per mm (the first layer's values and width
 *    on the first, the frame's above); every inner wall at the inner wall's values and width, every outer wall at a
 *    visible wall's, declared its width, its flat lines the bead's filament per mm;
 *  - each piece's outer wall starts every layer at its specified seam (the middle of its front face, its front-left corner, a
 *    round piece's front-most point), its inner wall at the back; a point seam's loop lies flat on its layer and ends where
 *    it began; a scarf seam's loop begins with a ramp (its first line starting at the layer's bottom plus the scarf's starting
 *    thickness, rising to the layer's top over the scarf length), runs flat back to the seam, then lays the ramp again, the
 *    two ramps' plastic together one layer's;
 *  - a leaning piece's front face steps out by its lean each layer (its loop's front read against the specification's), a
 *    vertical piece's stays;
 *  - the pieces in order on every layer, each inner wall before its outer, every layer after the one below;
 *  - the slab's size from its outer ring, the chamfer at its front-left corner, above the first layer a tick in the front and
 *    the back edge between each pair of pieces (short or long by the ruler's rule), none on the first layer;
 *  - the slab's first layer at the first-layer temperature and the pieces at theirs, each waited for before its first
 *    line (the slab's layers between heating), the part fan off on the first layer and the filament's above;
 *  - above the slab, no travel at or below its layer's height crosses a piece it neither starts nor ends in.
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object SeamCheck {
    private class Move(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val z0Um: Long, val z1Um: Long, val e: Double,
                       val fMmMin: Double, val accel: Double, val tempC: Int?, val waited: Boolean, val fanPct: Double, val layer: Int, val widthMm: Double, val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
    }

    fun check(spec: SeamSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 60) out += s }
        val (moves, layers) = parse(gcode, spec.tool)
        if (layers != spec.layers) bad("$layers layers in the g-code, not ${spec.layers} (the slab's ${SeamGeometry.slabLayers} and the pieces' ${spec.pieceLayers})")
        // the cells
        val want = LinkedHashSet<String>()
        for (l in 0 until SeamGeometry.slabLayers) want += spec.slabLabel(l)
        for (k in spec.pieces.indices) { want += spec.innerLabel(k); want += spec.outerLabel(k) }
        val byCell = moves.groupBy { it.cell }
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        for (c in byCell.keys) if (c !in want && !Part.isPrimeLine(c)) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have")
        // every line at its values
        val once = HashSet<String>()
        fun first(cell: String, kind: String, s: String) { if (once.add("$cell/$kind")) bad(s) }
        val fa = spec.filamentMm2
        val h = spec.layerUm
        for (m in moves) {
            val slabLayer = (0 until SeamGeometry.slabLayers).firstOrNull { spec.slabLabel(it) == m.cell }
            val piece = spec.pieceOf(m.cell)
            if (slabLayer == null && piece == null) continue
            // the slab's first layer at the first-layer temperature, waited for; its layers above heating to the pieces'; the
            // pieces at it, waited for
            val wantC = if (slabLayer == 0) spec.firstTempC else spec.tempC
            if (m.tempC != wantC) first(m.cell, "temp", "'${m.cell}': at ${m.tempC} C, not $wantC C")
            else if (!m.waited && (slabLayer == null || slabLayer == 0)) first(m.cell, "wait", "'${m.cell}': no wait (M109) for $wantC C before it")
            val wantFan = if (slabLayer == 0) 0.0 else spec.fanPct
            if (Math.abs(m.fanPct - wantFan) > 0.5) first(m.cell, "fan", "'${m.cell}': fan ${fmt(m.fanPct)} %, not ${fmt(wantFan)} %")
            val (pv, lw, hz) = when {
                slabLayer != null -> Triple(if (slabLayer == 0) spec.adhesion else spec.frame, if (slabLayer == 0) spec.firstWidthUm else spec.widthUm, spec.heightOf(slabLayer))
                piece!!.second -> Triple(spec.inner, spec.innerWidthUm, h)
                else -> Triple(spec.outer, spec.widthUm, h)
            }
            if (Math.abs(m.fMmMin / 60 - pv.speed) > 1.0 / 60 + 1e-9) first(m.cell, "v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(pv.speed, 2)}")
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - lw / 1000.0) > 5e-4) first(m.cell, "w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(lw / 1000.0, 3)}")
            if (Math.abs(m.accel - pv.accel) > 0.5) first(m.cell, "a", "'${m.cell}': acceleration ${fmt(m.accel)}, not ${fmt(pv.accel)}")
            val flat = m.z0Um == m.z1Um
            val wantE = spec.beadMm2(lw, hz) / fa * pv.flowRatio
            if (slabLayer != null) {
                if (m.z1Um != spec.zTop(slabLayer) || !flat) first(m.cell, "z", "'${m.cell}': printed at z ${m.z1Um / 1000.0}, not ${spec.zTop(slabLayer) / 1000.0}")
                if (Math.abs(m.e / m.len - wantE) > wantE * 0.01 + 2e-5 / m.len) first(m.cell, "e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(wantE, 5)}")
            } else {
                // a piece's lines lie on a piece layer's top; a scarf's ramp (the outer wall of a scarfed piece) may rise within its layer,
                // judged per layer below; the inner walls and a point seam's loop are flat at the full bead
                val i = m.layer - SeamGeometry.slabLayers
                val top = spec.slabTop + (i + 1) * h
                if (i < 0 || i >= spec.pieceLayers) { first(m.cell, "layer", "'${m.cell}' printed on layer ${m.layer + 1}, which is the slab's or past the plate"); continue }
                // a scarfed piece's outer wall rises within its layer (its ramp, judged in geometry); every other line ends at the top
                val scarfed = !piece!!.second && spec.pieces[piece.first].style == SeamStyle.SCARF
                if (m.z1Um != top && !(scarfed && m.z1Um < top)) first(m.cell, "z", "'${m.cell}': a line ending at z ${m.z1Um / 1000.0} on the layer whose top is ${top / 1000.0}" + if (m.z1Um < top) " (a scarf ramp's line where a point seam is specified)" else "")
            }
        }
        geometry(spec, moves, ::bad)
        out += travels(gcode, spec)
        return out
    }

    /** The slab and the pieces from the g-code's own lines, against figures worked out from [SeamGeometry] and the specification. */
    private fun geometry(spec: SeamSpec, moves: List<Move>, bad: (String) -> Unit) {
        val g = SeamGeometry
        val slabW = spec.slabW / 1000.0; val slabD = spec.slabD / 1000.0
        val first = moves.filter { it.cell == spec.slabLabel(0) }
        if (first.isEmpty()) { bad("no first layer to measure the slab from"); return }
        val fw = spec.firstWidthUm / 1000.0
        val ox = first.minOf { minOf(it.x0, it.x1) } - fw / 2; val oy = first.minOf { minOf(it.y0, it.y1) } - fw / 2
        val n = spec.pieces.size
        for (l in 0 until g.slabLayers) {
            val mine = moves.filter { it.cell == spec.slabLabel(l) }
            if (mine.isEmpty()) continue
            val lw = (if (l == 0) spec.firstWidthUm else spec.widthUm) / 1000.0
            val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - lw / 2, mine.minOf { minOf(it.y0, it.y1) } - lw / 2, mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2, mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2)
            if (Math.abs(b[2] - b[0] - slabW) > 0.03 || Math.abs(b[3] - b[1] - slabD) > 0.03) bad("layer $l: the slab is ${fmt(b[2] - b[0], 2)} x ${fmt(b[3] - b[1], 2)} mm, not ${fmt(slabW, 1)} x ${fmt(slabD, 1)} ($n pieces of ${fmt(g.piece / 1000.0, 0)} mm, ${fmt(g.gap / 1000.0, 0)} mm apart, ${fmt(g.margin / 1000.0, 0)} mm of slab around them and ${fmt(spec.reachUm / 1000.0, 1)} mm for the leaning faces)")
            if (Math.abs(b[0] - ox) > 0.03 || Math.abs(b[1] - oy) > 0.03) bad("layer $l: the slab starts at (${fmt(b[0], 2)}, ${fmt(b[1], 2)}), not where its first layer does (${fmt(ox, 2)}, ${fmt(oy, 2)})")
            val ring = mine.filter { Math.abs(minOf(it.x0, it.x1) - b[0] - lw / 2) < 0.03 || Math.abs(minOf(it.y0, it.y1) - b[1] - lw / 2) < 0.03 || Math.abs(maxOf(it.x0, it.x1) - b[2] + lw / 2) < 0.03 || Math.abs(maxOf(it.y0, it.y1) - b[3] + lw / 2) < 0.03 }
            val corner = ring.minOf { minOf(it.x0 - ox + it.y0 - oy, it.x1 - ox + it.y1 - oy) }
            val wantCorner = g.chamfer / 1000.0 + lw / 2 * Math.sqrt(2.0)
            if (Math.abs(corner - wantCorner) > 0.05) bad("layer $l: the slab's front-left corner is cut ${fmt((corner - lw / 2 * Math.sqrt(2.0)), 2)} mm (by x + y from the corner), not the ${fmt(g.chamfer / 1000.0, 1)} mm chamfer")
            for ((name, edgeY, into) in listOf(Triple("front", b[1] + lw / 2, 1.0), Triple("back", b[3] - lw / 2, -1.0))) {
                val face = mine.filter { Math.abs(it.y0 - edgeY) < 0.03 && Math.abs(it.y1 - edgeY) < 0.03 }.map { minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) }.sortedBy { it.first }
                if (face.isEmpty()) { bad("layer $l: the slab has no $name face"); continue }
                val gaps = ArrayList<Pair<Double, Double>>()
                var reach = face.first().second
                for ((a, c) in face.drop(1)) { if (a > reach + 0.05) gaps += reach to a; reach = maxOf(reach, c) }
                if (l == 0) { if (gaps.isNotEmpty()) bad("layer 0: ${gaps.size} gap(s) in the slab's $name face on its first layer, which is whole (the ticks are cut above it)"); continue }
                if (gaps.size != n - 1) { bad("layer $l: ${gaps.size} tick(s) in the slab's $name face, not ${n - 1} (one between each pair of pieces)"); continue }
                for ((c, gp) in gaps.withIndex()) {
                    val mid = (gp.first + gp.second) / 2 - ox; val wantX = spec.tickX(c) / 1000.0
                    val wantW = g.tickW / 1000.0 + lw
                    if (Math.abs(mid - wantX) > 0.03) bad("layer $l: tick ${c + 1} in the $name face is at x ${fmt(mid, 2)} from the slab's left edge, not between pieces ${c + 1} and ${c + 2} (${fmt(wantX, 1)})")
                    if (Math.abs(gp.second - gp.first - wantW) > 0.06) bad("layer $l: tick ${c + 1} in the $name face is ${fmt(gp.second - gp.first, 2)} mm across, not ${fmt(wantW, 2)}")
                    val wantIn = spec.tickIn(c) / 1000.0
                    val gapMid = (gp.first + gp.second) / 2
                    val back = mine.filter { Math.abs(it.y1 - it.y0) < 1e-6 && minOf(it.x0, it.x1) <= gapMid + 1e-6 && maxOf(it.x0, it.x1) >= gapMid - 1e-6 && (it.y0 - edgeY) * into > 0.05 }
                        .minOfOrNull { (it.y0 - edgeY) * into }
                    if (back == null || Math.abs(back - wantIn) > 0.03) bad("layer $l: tick ${c + 1} in the $name face is ${back?.let { fmt(it, 2) } ?: "?"} mm deep, not ${fmt(wantIn, 1)} (${if ((c + 1) % g.tickLongEvery == 0) "long: every ${g.tickLongEvery}th" else "short"})")
                }
            }
        }
        // the pieces, layer by layer
        val h = spec.layerUm
        val w = spec.widthUm / 1000.0
        val fullE = spec.beadMm2(spec.widthUm, h) / spec.filamentMm2 * spec.outer.flowRatio
        val ramp = spec.scarfUm / 1000.0
        var lastSeq = moves.filter { spec.slabLabel(g.slabLayers - 1) == it.cell }.maxOfOrNull { it.seq } ?: -1
        for (i in 0 until spec.pieceLayers) {
            val top = spec.slabTop + (i + 1) * h
            for (k in spec.pieces.indices) {
                val p = spec.pieces[k]
                val inner = moves.filter { it.cell == spec.innerLabel(k) && it.layer == g.slabLayers + i }
                val outer = moves.filter { it.cell == spec.outerLabel(k) && it.layer == g.slabLayers + i }
                val name = "piece ${k + 1} (${p.key})"
                if (inner.isEmpty()) { if (i == 0) bad("$name: no inner wall on layer ${g.slabLayers + i + 1}"); continue }
                if (outer.isEmpty()) { if (i == 0) bad("$name: no outer wall on layer ${g.slabLayers + i + 1}"); continue }
                // the order: the inner wall before the outer, this piece after the one before it, this layer after the last
                if (inner.first().seq < lastSeq) { if (i < 2) bad("$name: its inner wall on layer ${g.slabLayers + i + 1} is printed before ${if (k == 0) "the layer below is finished" else "piece $k"} (the pieces print in order, layer by layer)") }
                if (outer.first().seq < inner.last().seq) { if (i < 2) bad("$name: its outer wall is printed before its inner wall") }
                lastSeq = maxOf(lastSeq, outer.last().seq, inner.last().seq)
                // the inner seam at the back
                val innerSeam = spec.loop(k, i, true)
                val im = inner.first()
                if (Math.hypot(im.x0 - innerSeam[0] / 1000.0, im.y0 - innerSeam[1] / 1000.0) > 0.03) first(bad, "$name/innerseam", "$name: its inner wall starts at (${fmt(im.x0, 2)}, ${fmt(im.y0, 2)}), not at the back of the piece (${fmt(innerSeam[0] / 1000.0, 2)}, ${fmt(innerSeam[1] / 1000.0, 2)})")
                // the seam where specified
                val seam = spec.seamPoint(k, i).let { doubleArrayOf(it[0] / 1000.0, it[1] / 1000.0) }
                val om = outer.first()
                if (Math.hypot(om.x0 - seam[0], om.y0 - seam[1]) > 0.03) first(bad, "$name/seam", "$name: its outer wall starts at (${fmt(om.x0, 2)}, ${fmt(om.y0, 2)}) on layer ${g.slabLayers + i + 1}, not at its seam (${fmt(seam[0], 2)}, ${fmt(seam[1], 2)}: ${p.describe()})")
                // the front face where specified (a leaning piece steps out each layer)
                val frontY = spec.frontY(k, i) / 1000.0 + oy + w / 2
                val minY = outer.minOf { minOf(it.y0, it.y1) }
                if (Math.abs(minY - frontY) > 0.03) first(bad, "$name/front", "$name: its front face is at y ${fmt(minY, 2)} on layer ${g.slabLayers + i + 1}, not ${fmt(frontY, 2)}" +
                    (if (p.shape.leanDeg > 0) " (a face leaning ${fmt(p.shape.leanDeg, 0)} degrees steps out ${fmt(Math.tan(Math.toRadians(p.shape.leanDeg)) * h / 1000.0, 3)} mm a layer)" else " (a vertical face)"))
                // the seam's kind
                val rising = outer.filter { it.z0Um != it.z1Um || it.z1Um != top }
                val startZ = top - Math.round(h * (1 - spec.scarfStart))
                if (p.style == SeamStyle.POINT) {
                    if (rising.isNotEmpty()) { first(bad, "$name/flat", "$name: a line rising from z ${rising.first().z0Um / 1000.0} to ${rising.first().z1Um / 1000.0} on the layer whose top is ${top / 1000.0}: a scarf ramp where a point seam is specified"); continue }
                    val last = outer.last()
                    if (Math.hypot(last.x1 - seam[0], last.y1 - seam[1]) > 0.03) first(bad, "$name/close", "$name: its outer wall ends at (${fmt(last.x1, 2)}, ${fmt(last.y1, 2)}), not back at its seam (a point seam's loop closes where it began)")
                    outer.firstOrNull { Math.abs(it.e / it.len - fullE) > fullE * 0.01 + 2e-5 / it.len }?.let { first(bad, "$name/e", "$name: ${fmt(it.e / it.len, 5)} mm of filament per mm, not the bead's ${fmt(fullE, 5)}") }
                } else {
                    // the ramp: from the layer's bottom plus the starting thickness up to the top over the scarf length; then the
                    // loop flat back to the seam; then the ramp again at the top, the two together one layer's plastic
                    if (rising.isEmpty()) { first(bad, "$name/ramp", "$name: no scarf ramp: its outer wall lies flat at z ${top / 1000.0} (a scarf seam's ramp starts at ${startZ / 1000.0} and rises to the top over ${fmt(ramp, 1)} mm)"); continue }
                    if (om.z0Um != startZ) first(bad, "$name/rampstart", "$name: its ramp starts at z ${om.z0Um / 1000.0}, not ${startZ / 1000.0} (the layer's bottom plus the scarf's starting thickness, ${fmt(spec.scarfStart, 2)} of the layer)")
                    val upEnd = outer.indexOfFirst { it.z1Um == top }
                    val up = outer.subList(0, upEnd + 1)
                    val upLen = up.sumOf { it.len }
                    if (Math.abs(upLen - ramp) > 0.5) first(bad, "$name/ramplen", "$name: its ramp is ${fmt(upLen, 2)} mm long, not the scarf length ${fmt(ramp, 1)}")
                    val q = up.last()
                    val back = (upEnd + 1 until outer.size).firstOrNull { Math.hypot(outer[it].x1 - seam[0], outer[it].y1 - seam[1]) < 0.03 }
                    if (back == null) { first(bad, "$name/return", "$name: after its ramp the loop never returns to the seam (${fmt(seam[0], 2)}, ${fmt(seam[1], 2)})"); continue }
                    val rest = outer.subList(upEnd + 1, back + 1); val down = outer.subList(back + 1, outer.size)
                    rest.firstOrNull { Math.abs(it.e / it.len - fullE) > fullE * 0.01 + 2e-5 / it.len }?.let { first(bad, "$name/reste", "$name: ${fmt(it.e / it.len, 5)} mm of filament per mm between its ramps, not the bead's ${fmt(fullE, 5)}") }
                    val downLen = down.sumOf { it.len }
                    if (Math.abs(downLen - upLen) > 0.1) first(bad, "$name/downlen", "$name: the ramp laid again is ${fmt(downLen, 2)} mm, not the ramp's ${fmt(upLen, 2)}")
                    else {
                        val last = down.last()
                        if (Math.hypot(last.x1 - q.x1, last.y1 - q.y1) > 0.03) first(bad, "$name/downend", "$name: the second ramp ends at (${fmt(last.x1, 2)}, ${fmt(last.y1, 2)}), not where the first did (${fmt(q.x1, 2)}, ${fmt(q.y1, 2)})")
                        if (down.any { it.z0Um != top || it.z1Um != top }) first(bad, "$name/downz", "$name: the second ramp is not laid at the layer's top ${top / 1000.0}")
                        val both = up.sumOf { it.e } + down.sumOf { it.e }
                        if (Math.abs(both - upLen * fullE) > upLen * fullE * 0.03) first(bad, "$name/rampe", "$name: the two ramps lay ${fmt(both, 4)} mm of filament over ${fmt(upLen, 2)} mm, not one layer's ${fmt(upLen * fullE, 4)}")
                    }
                }
            }
        }
    }

    private val said = HashSet<String>()
    private fun first(bad: (String) -> Unit, key: String, s: String) { if (said.add(key)) bad(s) }

    /**
     * The travels, from the g-code text, above the slab: a travel at or below its layer's top that crosses a piece's cell other
     * than the one it starts or ends in would drag across that piece's seam or wall; the whole move counted at its lower end. The
     * cells are each piece's footprint on that layer, placed on the bed.
     */
    fun travels(gcode: String, spec: SeamSpec): List<String> {
        val out = ArrayList<String>()
        val g = SeamGeometry
        fun cellsAt(px: Double, py: Double, i: Int): Set<Int> = spec.pieces.indices.filter { k ->
            val c = spec.cell(k); val x0 = (c[0] + spec.originXUm) / 1000.0; val x1 = (c[2] + spec.originXUm) / 1000.0
            val y0 = (spec.frontY(k, i) + spec.originYUm) / 1000.0; val y1 = (c[3] + spec.originYUm) / 1000.0
            px >= x0 - GRAZE && px <= x1 + GRAZE && py >= y0 - GRAZE && py <= y1 + GRAZE }.toSet()
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
            if (!x.isNaN() && (nx != x || ny != y) && e <= 0 && i >= 0 && i < spec.pieceLayers && out.size < 20) {
                val top = (spec.slabTop + (i + 1) * spec.layerUm) / 1000.0
                if (minOf(z, nz) <= top + 1e-6) {
                    val ends = cellsAt(x, y, i) + cellsAt(nx, ny, i)
                    val n = maxOf(1, Math.ceil(Math.hypot(nx - x, ny - y) / GRAZE).toInt())
                    val hit = (1 until n).firstNotNullOfOrNull { s -> val px = x + (nx - x) * s / n; val py = y + (ny - y) * s / n; (cellsAt(px, py, i) - ends).firstOrNull() }
                    if (hit != null) out += "a travel at z ${fmt(minOf(z, nz), 3)}, the layer's height (${fmt(top, 3)}), crosses piece ${hit + 1} (from ${fmt(x, 2)}, ${fmt(y, 2)} to ${fmt(nx, 2)}, ${fmt(ny, 2)}): no lift"
                }
            }
            x = nx; y = ny; z = nz
        }
        return out
    }

    /** How close to a cell's boundary a point counts as in it, and the travel sampling step (mm). */
    private const val GRAZE = 0.25

    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Extrusion moves inside cells with the state they ran in (the nozzle target for [tool] and whether a wait to that target
     * came after it was set; the Z before and after the move), and the number of layer markers. Extrusion outside every cell
     * between the plate's first line and its last is returned under the cell name "(none)". Relative extrusion (M83).
     */
    private fun parse(gcode: String, tool: Int): Pair<List<Move>, Int> {
        said.clear()
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
