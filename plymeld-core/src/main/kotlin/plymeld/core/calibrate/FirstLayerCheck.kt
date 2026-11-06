package plymeld.core.calibrate

/**
 * Reads a first-layer plate's g-code back ([FirstLayerPlate]) and checks it against its specification, as the printer
 * will run it. Its reference is the [FirstLayerSpec] and the layout figures it works out itself from [FirstLayerGeometry]
 * and [PlateMarks] and from the g-code's own lines, never the generator's paths:
 *
 *  - one layer marker only (the frame, the border, the base's upper layers, and the labels print within it, at their own heights),
 *    and nothing but the plate's cells: per column, its prime line, every tile within its melt limit (and none over it:
 *    those are left out), the frame's rings over every tile, and its temperature label's layers; the base's layers and the
 *    border's; each speed's and each row's height label's layers; no other extrusion between the plate's first line and
 *    its last;
 *  - each tile at its height and its speed, the tiles' acceleration and width, its filament per mm the bead model's times
 *    the flow ratio; its flow, read from the g-code, within its column's melt limit; each frame ring at its layer's height
 *    over its tile and each border layer a layer each above the base's top, at the frame's speed (the machine's, held to
 *    the hottest column's melt limit), width, and acceleration;
 *    the prime lines and the base's first layer at the prime height (the first layer's, or the lowest tile's if lower) in
 *    the first layer's width, the base's upper layers and the labels a layer each above it in the ordinary width, all at
 *    the first layer's speed;
 *  - each column's nozzle temperature: the target in force on its every line is its own, a wait (M109) to it before its
 *    first line, the columns coolest first; the base's upper layers, the labels, the frame, and the border at the last
 *    column's; the part fan off;
 *  - the geometry: each tile [FirstLayerGeometry.tileLength] long and as deep as its height's lines make it, exactly that
 *    many lines that spacing apart, its first line starting at the middle of its back edge and its wall closing there;
 *    the tiles in their places, the columns side by side and the rows front to back, every tile touching its neighbors;
 *    the base along the plate's front and left, its front-left corner cut off ([PlateMarks.CHAMFER]), its upper layers
 *    set back [FirstLayerGeometry.baseSetback]; each column's prime line along the base's front under it; each label
 *    where it goes, its lines inside its digits, every digit printed; the plate's first layer ONE piece (its lines'
 *    footprints, sampled every 0.05 mm, connected; nothing above it counted, so nothing can stand in for a gap);
 *  - the order: the base's first layer before any tile; column by column, each column's prime line before its tiles and
 *    its tiles lowest first (the nozzle never works beside a finished line above it); after all the tiles the base's upper
 *    layers and the labels, then the frame and the border, layer by layer, each layer lowest first; the frame's rings
 *    [FirstLayerSpec.frameInsetUm] in from each tile's edges (over its wall, not its inside), ONE piece; the border a ring
 *    round the base's L on its upper layer, flush with its edge, its front-left corner cut as the base's is;
 *  - no travel crosses a finished tile, frame ring, or border at or below its height (the lift).
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object FirstLayerCheck {
    private class Move(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val zUm: Long, val e: Double,
                       val fMmMin: Double, val accel: Double, val tempC: Int?, val waited: Boolean, val fanPct: Double, val layer: Int, val widthMm: Double,
                       val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
    }

    /**
     * A cell label's column (0-based) and part: "prime", "tile" ([r].[c]), "frame" ([r].[c], layer [n]), "base" (layer
     * [n]), "mark" (the temperature label, layer [n]), "speed" (speed [r]'s label, layer [n]), "height" (row [r].[c]'s
     * label, layer [n]), "border" (layer [n]), or null; [r], [c] 0-based, [n] from 1.
     */
    private class Cell(val block: Int, val part: String, val r: Int = -1, val c: Int = -1, val n: Int = 0) {
        /** Printed after all the tiles, at the last column's temperature: the base's upper layers, the labels, the frame, the border. */
        val late: Boolean get() = edge || part == "mark" || part == "speed" || part == "height" || (part == "base" && n > 1)
        /** The raised edge, printed last, layer by layer: the frame over the tiles and the border round the base. */
        val edge: Boolean get() = part == "frame" || part == "border"
    }
    private fun cellOf(label: String): Cell? {
        val m = Regex("block (\\d+) (prime|base (\\d+)|mark (\\d+)|speed (\\d+) (\\d+)|height (\\d+)\\.(\\d+) (\\d+)|tile (\\d+)\\.(\\d+)|frame (\\d+)\\.(\\d+) (\\d+)|border (\\d+))").matchEntire(label) ?: return null
        val k = m.groupValues[1].toInt() - 1
        val g = m.groupValues
        return when {
            g[2].startsWith("base") -> Cell(k, "base", n = g[3].toInt())
            g[2].startsWith("mark") -> Cell(k, "mark", n = g[4].toInt())
            g[2].startsWith("speed") -> Cell(k, "speed", g[5].toInt() - 1, n = g[6].toInt())
            g[2].startsWith("height") -> Cell(k, "height", g[7].toInt() - 1, g[8].toInt() - 1, g[9].toInt())
            g[2].startsWith("tile") -> Cell(k, "tile", g[10].toInt() - 1, g[11].toInt() - 1)
            g[2].startsWith("frame") -> Cell(k, "frame", g[12].toInt() - 1, g[13].toInt() - 1, g[14].toInt())
            g[2].startsWith("border") -> Cell(k, "border", n = g[15].toInt())
            else -> Cell(k, g[2])
        }
    }

    fun check(spec: FirstLayerSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 60) out += s }
        val (moves, layers) = parse(gcode, spec.tool)
        val t = spec.tiles
        val g = FirstLayerGeometry
        if (layers != 1) bad("$layers layers in the g-code, not 1 (a first-layer plate is one layer; its frame, base, and labels print within it, at their own heights)")
        moves.firstOrNull { it.layer != 0 }?.let { bad("'${it.cell}' printed on layer ${it.layer}, not the first (layer 0)") }
        // the cells
        val want = LinkedHashSet<String>()
        for (n in 1..g.baseLayers) want += spec.baseLabel(n)
        for (n in 1..g.frameLayers) want += spec.borderLabel(n)
        for (k in 0 until spec.blocks) {
            want += spec.primeLabel(k)
            for (n in 1..g.labelLayers) want += spec.markLabel(k, n)
            for ((r, c) in spec.rows) if (spec.printed(k, r, c)) {
                want += spec.tileLabel(k, r, c)
                for (n in 1..g.frameLayers) want += spec.frameLabel(k, r, c, n)
            }
        }
        for (n in 1..g.labelLayers) {
            for (r in t.speeds.indices) want += spec.speedLabelOf(r, n)
            for ((r, c) in spec.rows) want += spec.heightLabelOf(r, c, n)
        }
        val byCell = moves.groupBy { it.cell }
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        for (c in byCell.keys) if (c !in want) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have" +
            (cellOf(c)?.takeIf { it.part == "tile" && it.block in 0 until spec.blocks }?.let { cl -> " (over the ${spec.tempsC[cl.block]} C column's melt limit, ${fmt(spec.capsMm3s[cl.block], 1)} mm3/s: left out)" } ?: ""))
        // the order: the columns' first-layer lines column by column; after them the base's upper layers and the labels, then
        // the frame and the border
        val early = moves.filter { cellOf(it.cell)?.let { c -> !c.late } == true }
        val late = moves.filter { cellOf(it.cell)?.late == true }
        val frameMoves = moves.filter { cellOf(it.cell)?.edge == true }
        if (early.map { cellOf(it.cell)!!.block }.zipWithNext().any { (a, b) -> b < a }) bad("the columns do not print in order (the coolest, on the left, first; each column's lines together)")
        early.maxOfOrNull { it.seq }?.let { lastEarly -> late.firstOrNull { it.seq < lastEarly }?.let { bad("'${it.cell}' printed before the last tile: the base's upper layers, the labels, and the frame go on after all the tiles") } }
        late.filter { cellOf(it.cell)?.edge == false }.maxOfOrNull { it.seq }?.let { lastLabel -> frameMoves.firstOrNull { it.seq < lastLabel }?.let { bad("'${it.cell}' printed before the labels: the frame and the border go on last") } }
        moves.firstOrNull { cellOf(it.cell)?.part == "tile" }?.let { firstTile -> moves.firstOrNull { it.cell == spec.baseLabel(1) && it.seq > firstTile.seq }?.let { bad("'${it.cell}' printed after the first tile: the base's first layer goes down first") } }
        for (k in 0 until spec.blocks) {
            val firstTile = moves.firstOrNull { cellOf(it.cell)?.let { c -> c.block == k && c.part == "tile" } == true } ?: continue
            moves.firstOrNull { it.cell == spec.primeLabel(k) && it.seq > firstTile.seq }?.let { bad("'${it.cell}' printed after the ${spec.tempsC[k]} C column's first tile: its prime line goes first") }
            // its tiles lowest first
            val heights = moves.filter { cellOf(it.cell)?.let { c -> c.block == k && c.part == "tile" } == true }.map { it.zUm }
            heights.zipWithNext().firstOrNull { (a, b) -> b < a }?.let { (a, b) -> bad("the ${spec.tempsC[k]} C column prints a tile at z ${b / 1000.0} after one at z ${a / 1000.0}: its tiles go lowest first, so the nozzle never works beside a finished tile above it") }
        }
        // the frame and the border: layer by layer, each layer lowest first
        frameMoves.map { cellOf(it.cell)!!.n to it.zUm }.zipWithNext().firstOrNull { (a, b) -> b.first < a.first || (b.first == a.first && b.second < a.second) }
            ?.let { bad("the frame and the border do not go down layer by layer, each layer lowest first (z ${it.first.second / 1000.0} in layer ${it.first.first}, then z ${it.second.second / 1000.0} in layer ${it.second.first})") }
        // the columns' temperatures, in the order the g-code reaches them, rise
        val printed = (0 until spec.blocks).mapNotNull { k -> early.firstOrNull { cellOf(it.cell)?.let { c -> c.block == k && c.part != "base" } == true }?.tempC }
        if (printed.zipWithNext().any { (a, b) -> b <= a }) bad("the columns' temperatures as printed, ${printed.joinToString()} C, do not rise column by column (coolest first)")
        values(spec, moves, ::bad)
        geometry(spec, moves, ::bad)
        out += travels(gcode)
        return out
    }

    /** Each line's values against the specification: temperature, wait, fan, height, speed, filament per mm, width, acceleration, and (tiles and frame) flow. */
    private fun values(spec: FirstLayerSpec, moves: List<Move>, bad: (String) -> Unit) {
        val t = spec.tiles
        val g = FirstLayerGeometry
        val said = HashSet<String>()
        for (m in moves) {
            val c = cellOf(m.cell) ?: continue
            if (c.block !in 0 until spec.blocks) continue
            if ((c.part == "tile" || c.part == "frame" || c.part == "height") && (c.r !in t.speeds.indices || c.c !in t.heightsUm.indices)) continue
            if (c.part == "speed" && c.r !in t.speeds.indices) continue
            val maxN = when (c.part) { "frame", "border" -> g.frameLayers; "base" -> g.baseLayers; "mark", "speed", "height" -> g.labelLayers; else -> 0 }
            if (maxN > 0 && c.n !in 1..maxN) continue
            fun once(kind: String, s: String) { if (said.add("${m.cell}/$kind")) bad(s) }
            // after all the tiles, at the last column's temperature: the base's upper layers, the labels, the frame
            val wantT = if (c.late) spec.tempsC.last() else spec.tempsC[c.block]
            if (m.tempC != wantT) once("temp", "'${m.cell}': at ${m.tempC} C, not $wantT C")
            else if (!m.waited) once("wait", "'${m.cell}': no wait (M109) for $wantT C before it: it may print while the nozzle is still heating")
            if (m.fanPct != 0.0) once("fan", "'${m.cell}': fan ${fmt(m.fanPct)} %, not off")
            val wantZ: Long; val wantV: Double; val wantW: Long; val accel: Double; val beadH: Long; val flow: Double; val what: String
            val lh = spec.frameLayerUm
            when {
                c.part == "tile" -> { wantZ = t.heightsUm[c.c]; wantV = t.speeds[c.r]; wantW = t.widthUm; accel = t.accel; beadH = wantZ; flow = spec.flowRatio; what = " (its height)" }
                c.part == "frame" -> { wantZ = spec.frameUm(c.c, c.n); wantV = spec.frameSpeed; wantW = spec.frameWidthUm; accel = spec.frame.accel; beadH = lh; flow = spec.frame.flowRatio; what = " (${c.n} frame layer(s) above its tile)" }
                c.part == "border" -> { wantZ = spec.borderUm(c.n); wantV = spec.frameSpeed; wantW = spec.frameWidthUm; accel = spec.frame.accel; beadH = lh; flow = spec.frame.flowRatio; what = " (${c.n} layer(s) above the base's top)" }
                c.part == "base" && c.n > 1 -> { wantZ = spec.baseUm(c.n); wantV = spec.prime.speed; wantW = spec.frameWidthUm; accel = spec.prime.accel; beadH = lh; flow = spec.prime.flowRatio; what = " (the base's layer ${c.n})" }
                c.part == "mark" || c.part == "speed" || c.part == "height" -> { wantZ = spec.labelUm(c.n); wantV = spec.prime.speed; wantW = spec.frameWidthUm; accel = spec.prime.accel; beadH = lh; flow = spec.prime.flowRatio; what = " (label layer ${c.n}, on the base)" }
                else -> { wantZ = spec.primeUm; wantV = spec.prime.speed; wantW = spec.firstWidthUm; accel = spec.prime.accel; beadH = wantZ; flow = spec.prime.flowRatio; what = " (the prime height: the first layer's, or the lowest tile's if lower)" }
            }
            if (m.zUm != wantZ) once("z", "'${m.cell}': printed at z ${m.zUm / 1000.0}, not ${wantZ / 1000.0}$what")
            if (Math.abs(m.fMmMin / 60 - wantV) > 1.0 / 60 + 1e-9) once("v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(wantV, 2)}")
            val expectE = spec.beadMm2(wantW, beadH) / spec.filamentMm2 * flow
            if (Math.abs(m.e / m.len - expectE) > expectE * 0.01 + 2e-5 / m.len) once("e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(expectE, 5)} (a ${wantW / 1000.0} mm line ${beadH / 1000.0} mm high, flow ratio $flow)")
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - wantW / 1000.0) > 5e-4) once("w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(wantW / 1000.0, 3)}")
            if (Math.abs(m.accel - accel) > 0.5) once("a", "'${m.cell}': acceleration ${fmt(m.accel)}, not ${fmt(accel)}")
            // the melt limit, from the g-code itself: filament per mm x the filament's section x the feed
            if (c.part == "tile" || c.edge) {
                val mm3s = m.e / m.len * spec.filamentMm2 * m.fMmMin / 60
                val cap = if (c.edge) spec.capsMm3s.last() else spec.capsMm3s[c.block]
                if (mm3s > cap * 1.001 + 1e-6) once("cap", "'${m.cell}': ${fmt(mm3s, 1)} mm3/s, over the melt limit ${fmt(cap, 1)} mm3/s at $wantT C " +
                    if (c.edge) "(the frame's speed is held to it)" else "(a tile over it is left out, never printed)")
            }
        }
    }

    /**
     * The layout, from the g-code's own lines against figures this check works out itself: each tile
     * [FirstLayerGeometry.tileLength] long and as deep as its height's lines make it (the bead model's spacing, as many lines
     * as fit [FirstLayerGeometry.tileDepth]), exactly those lines that spacing apart, its first line starting at the middle
     * of its back edge and its wall closing there; every tile where the layout puts it from the first one as printed (the
     * columns side by side, the rows front to back in their order, all touching); the base; each column's prime line; the
     * labels; the frame's rings; the plate's first layer one piece, and the frame one piece.
     */
    private fun geometry(spec: FirstLayerSpec, moves: List<Move>, bad: (String) -> Unit) {
        val t = spec.tiles
        val g = FirstLayerGeometry
        val tw = t.widthUm / 1000.0; val fw = spec.firstWidthUm / 1000.0
        val ov = Math.round(t.wallOverlap * t.widthUm) / 1000.0
        val len = g.tileLength / 1000.0
        fun box(ms: List<Move>, w: Double) = doubleArrayOf(ms.minOf { minOf(it.x0, it.x1) } - w / 2, ms.minOf { minOf(it.y0, it.y1) } - w / 2, ms.maxOf { maxOf(it.x0, it.x1) } + w / 2, ms.maxOf { maxOf(it.y0, it.y1) } + w / 2)
        // a tile's figures at height [c]: its bead spacing, its lines, its depth (mm)
        fun spacing(c: Int) = tw - t.heightsUm[c] / 1000.0 * (1 - Math.PI / 4)
        fun lines(c: Int) = maxOf(2, Math.floor((g.tileDepth / 1000.0 - tw - 2 * spacing(c)) / spacing(c) + 1e-9).toInt() + 1)
        fun depth(c: Int) = (lines(c) - 1) * spacing(c) + 2 * spacing(c) + tw
        // where every tile goes, from the first tile as printed (the first column's front row: always printed, or refused)
        // one piece on the bed (first, so the many problems a plate in pieces has cannot crowd it out of the report): every first-layer line (prime lines, the base's first layer, tiles) joined to the rest, nothing
        // above them counted (it must not be what joins them); and the frame one piece over the tiles
        pieces(moves.filter { cellOf(it.cell)?.let { c -> !c.late } == true }).let { p -> if (p > 1) bad("the plate's first layer is $p separate pieces, not one (its lines' footprints sampled every ${fmt(RES, 2)} mm)") }
        pieces(moves.filter { cellOf(it.cell)?.part == "frame" }).let { p -> if (p > 1) bad("the frame is $p separate pieces, not one: its rings do not join (its lines' footprints sampled every ${fmt(RES, 2)} mm)") }
        val first = moves.filter { it.cell == spec.tileLabel(0, 0, 0) }
        if (first.isEmpty()) { bad("no first tile (the first column's front row) to measure the plate from"); return }
        val o = box(first, tw)
        val rowY = DoubleArray(spec.rows.size + 1).also { y -> y[0] = o[1]; for ((j, rc) in spec.rows.withIndex()) y[j + 1] = y[j] + depth(rc.second) }
        val tileBoxes = HashMap<String, DoubleArray>()
        for (k in 0 until spec.blocks) for ((j, rc) in spec.rows.withIndex()) {
            val (r, c) = rc
            val l = spec.tileLabel(k, r, c)
            val mine = moves.filter { it.cell == l }
            if (mine.isEmpty()) continue                                // left out (or reported as a missing cell)
            val sp = spacing(c); val n = lines(c); val want = doubleArrayOf(o[0] + k * len, rowY[j], o[0] + (k + 1) * len, rowY[j + 1])
            val b = box(mine, tw); tileBoxes[l] = b
            if ((0..3).any { Math.abs(b[it] - want[it]) > 0.03 }) bad("'$l' spans (${fmt(b[0], 2)}, ${fmt(b[1], 2)})-(${fmt(b[2], 2)}, ${fmt(b[3], 2)}), not (${fmt(want[0], 2)}, ${fmt(want[1], 2)})-(${fmt(want[2], 2)}, ${fmt(want[3], 2)}): " +
                "${fmt(len, 0)} mm long, ${fmt(depth(c), 2)} mm deep at ${fmt(t.heightsUm[c] / 1000.0, 2)} mm, in its place touching its neighbors")
            // its lines: from bead contact with the wall at each side, plus the line-end overlap into it
            val long = mine.filter { Math.abs(it.y1 - it.y0) < 1e-6 && Math.abs(it.len - (len - tw - 2 * sp + 2 * ov)) < 0.05 }
            val across = long.map { it.y0 }.sorted()
            if (across.size != n) bad("'$l' has ${across.size} lines, not $n")
            else for (i in 1 until across.size) if (Math.abs(across[i] - across[i - 1] - sp) > 0.012) { bad("'$l': lines $i and ${i + 1} are ${fmt(across[i] - across[i - 1], 3)} mm apart, not ${fmt(sp, 3)} (the bead spacing at ${fmt(t.heightsUm[c] / 1000.0, 2)} mm)"); break }
            // its first line starts at the middle of its back edge (half a line in), and its wall closes there
            val sx = (b[0] + b[2]) / 2; val sy = b[3] - tw / 2
            val st = mine.first()
            if (Math.abs(st.x0 - sx) > 0.03 || Math.abs(st.y0 - sy) > 0.03) bad("'$l' starts at x ${fmt(st.x0, 2)}, y ${fmt(st.y0, 2)}, not at the middle of its back edge (${fmt(sx, 2)}, ${fmt(sy, 2)})")
            else if (mine.drop(1).none { Math.abs(it.x1 - sx) < 0.03 && Math.abs(it.y1 - sy) < 0.03 }) bad("'$l': its wall does not close at its start (the middle of its back edge)")
        }
        // the margins the labels stand on (mm): the left one as wide as a gap, the widest speed, a gap, the widest height, a
        // gap; the front one as deep as a gap, the tallest temperature, a gap
        val gap = g.labelGap / 1000.0
        val speedW = t.speeds.indices.maxOf { PlateMarks.labelWidth(spec.speedText(it)) } / 1000.0
        val heightW = t.heightsUm.indices.maxOf { PlateMarks.labelWidth(spec.heightText(it)) } / 1000.0
        val tempH = (0 until spec.blocks).maxOf { PlateMarks.labelHeight(spec.tempText(it)) } / 1000.0
        val x0 = o[0] - (gap + speedW + gap + heightW + gap); val y0 = o[1] - (gap + tempH + gap)
        val right = o[0] + spec.blocks * len; val back = rowY.last()
        // the base: its first layer from the plate's front-left corner to the tiles' right edge and back edge, the corner cut
        // off; its upper layers set back from every edge
        moves.filter { it.cell == spec.baseLabel(1) }.takeIf { it.isNotEmpty() }?.let { base ->
            val b = box(base, fw)
            if (Math.abs(b[0] - x0) > 0.05 || Math.abs(b[1] - y0) > 0.05 || Math.abs(b[2] - right) > 0.05 || Math.abs(b[3] - back) > 0.05)
                bad("the base's first layer spans (${fmt(b[0], 2)}, ${fmt(b[1], 2)})-(${fmt(b[2], 2)}, ${fmt(b[3], 2)}), not (${fmt(x0, 2)}, ${fmt(y0, 2)})-(${fmt(right, 2)}, ${fmt(back, 2)}): the front and left margins, to the tiles")
            val corner = base.flatMap { listOf(it.x0 - x0 + it.y0 - y0, it.x1 - x0 + it.y1 - y0) }.min()
            val wantCorner = PlateMarks.CHAMFER / 1000.0 + fw / 2 * Math.sqrt(2.0)
            if (Math.abs(corner - wantCorner) > 0.05) bad("the base's front-left corner is cut ${fmt(corner - fw / 2 * Math.sqrt(2.0), 2)} mm (by x + y from the corner), not the ${fmt(PlateMarks.CHAMFER / 1000.0, 1)} mm chamfer")
        }
        val setback = g.baseSetback / 1000.0; val fw2 = spec.frameWidthUm / 1000.0
        for (n in 2..g.baseLayers) moves.filter { it.cell == spec.baseLabel(n) }.takeIf { it.isNotEmpty() }?.let { base ->
            val b = box(base, fw2)
            if (Math.abs(b[0] - x0 - setback) > 0.05 || Math.abs(b[1] - y0 - setback) > 0.05 || Math.abs(b[2] - right + setback) > 0.05 || Math.abs(b[3] - back + setback) > 0.05)
                bad("the base's layer $n spans (${fmt(b[0], 2)}, ${fmt(b[1], 2)})-(${fmt(b[2], 2)}, ${fmt(b[3], 2)}), not set back ${fmt(setback, 2)} mm from the first layer's edges")
        }
        // the border: each layer a ring round the base, on its upper layer and flush with its edge (half a line in from it),
        // its front-left corner cut off as the base's is, and its inner corner where the base meets the tiles' front-left
        val bi = setback + fw2 / 2
        for (n in 1..g.frameLayers) moves.filter { it.cell == spec.borderLabel(n) }.takeIf { it.isNotEmpty() }?.let { ring ->
            val b = box(ring, 0.0)
            if (Math.abs(b[0] - x0 - bi) > 0.03 || Math.abs(b[1] - y0 - bi) > 0.03 || Math.abs(b[2] - right + bi) > 0.03 || Math.abs(b[3] - back + bi) > 0.03)
                bad("'${spec.borderLabel(n)}' runs (${fmt(b[0], 2)}, ${fmt(b[1], 2)})-(${fmt(b[2], 2)}, ${fmt(b[3], 2)}), not ${fmt(bi, 2)} mm in from the base's first layer's edges, on its upper layer (${fmt(x0 + bi, 2)}, ${fmt(y0 + bi, 2)})-(${fmt(right - bi, 2)}, ${fmt(back - bi, 2)})")
            val corner = ring.flatMap { listOf(it.x0 - x0 + it.y0 - y0, it.x1 - x0 + it.y1 - y0) }.min()
            if (Math.abs(corner - PlateMarks.CHAMFER / 1000.0 - bi * Math.sqrt(2.0)) > 0.05) bad("'${spec.borderLabel(n)}' does not follow the base's cut front-left corner")
            if (ring.none { Math.abs(it.x1 - (o[0] - bi)) < 0.03 && Math.abs(it.y1 - (o[1] - bi)) < 0.03 }) bad("'${spec.borderLabel(n)}' does not turn at the base's inner corner (${fmt(o[0] - bi, 2)}, ${fmt(o[1] - bi, 2)}): it is not round the base's L")
        }
        // each column's prime line: along x (right to left) under the column, ending at its left edge, overlapping the base's front edge
        for (k in 0 until spec.blocks) {
            val prime = moves.filter { it.cell == spec.primeLabel(k) }
            if (prime.isEmpty()) continue
            val cx = o[0] + k * len
            if (prime.any { Math.abs(it.y1 - it.y0) > 1e-6 }) { bad("the ${spec.tempsC[k]} C column's prime line is not a run along x"); continue }
            if (Math.abs(prime.last().x1 - fw / 2 - cx) > 0.03) bad("the ${spec.tempsC[k]} C column's prime line ends at x ${fmt(prime.last().x1, 2)}, not at its left edge (${fmt(cx + fw / 2, 2)})")
            if (prime.sumOf { it.len } < g.primeLen / 1000.0 - fw - 0.03) bad("the ${spec.tempsC[k]} C column's prime line's run is ${fmt(prime.sumOf { it.len }, 2)} mm, shorter than ${fmt(g.primeLen / 1000.0 - fw, 2)}")
            if (prime.first().y0 + fw / 2 < y0 + g.fuse / 1000.0 - 0.03) bad("the ${spec.tempsC[k]} C column's prime line does not overlap the base's front edge (at y ${fmt(y0, 2)})")
        }
        labels(spec, moves, x0, y0, o, rowY, bad)
        // the frame's rings: each [FirstLayerSpec.frameInsetUm] in from its tile's edges as printed
        val inset = spec.frameInsetUm / 1000.0
        for ((l, fm) in moves.filter { cellOf(it.cell)?.part == "frame" }.groupBy { it.cell }) {
            val c = cellOf(l)!!
            val tb = tileBoxes[spec.tileLabel(c.block, c.r, c.c)] ?: run { bad("'$l' lies over no printed tile"); null } ?: continue
            val fb = box(fm, 0.0)
            val want = doubleArrayOf(tb[0] + inset, tb[1] + inset, tb[2] - inset, tb[3] - inset)
            if ((0..3).any { Math.abs(fb[it] - want[it]) > 0.03 }) bad("'$l' runs (${fmt(fb[0], 2)}, ${fmt(fb[1], 2)})-(${fmt(fb[2], 2)}, ${fmt(fb[3], 2)}), not ${fmt(inset, 2)} mm in from its tile's edges, over its wall (${fmt(want[0], 2)}, ${fmt(want[1], 2)})-(${fmt(want[2], 2)}, ${fmt(want[3], 2)})")
        }
    }

    /**
     * The labels against where they go, worked out here from the plate's lines as printed: the plate's front-left corner
     * ([x0], [y0], mm), the first tile's ([o]), the rows' front edges ([rowY]): each column's temperature
     * ([FirstLayerSpec.tempText]) a gap behind the base's front edge, [FirstLayerGeometry.labelIn] right of the column's left
     * edge; each speed a gap right of the plate's left edge, centered on its group's rows; each row's height right of the
     * widest speed and a gap, centered on its row. The digits are [PlateMarks]'s glyphs for the text; every line of a
     * label (each of its layers) must lie inside them (grown by half a line), and every digit must hold a line, so a label
     * showing another number is caught even at the same width.
     */
    private fun labels(spec: FirstLayerSpec, moves: List<Move>, x0: Double, y0: Double, o: DoubleArray, rowY: DoubleArray, bad: (String) -> Unit) {
        val t = spec.tiles
        val g = FirstLayerGeometry
        val fw2 = spec.frameWidthUm / 1000.0; val gap = g.labelGap / 1000.0
        fun um(v: Double) = Math.round(v * 1000)
        /** Whether ([x], [y]) (mm) is inside [p] (µm). */
        fun inside(p: plymeld.geom.Polygon, x: Double, y: Double): Boolean {
            var c = false; val px = x * 1000; val py = y * 1000
            for (i in 0 until p.size) { val j = (i + 1) % p.size
                val xi = p.x(i).toDouble(); val yi = p.y(i).toDouble(); val xj = p.x(j).toDouble(); val yj = p.y(j).toDouble()
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) c = !c }
            return c
        }
        /** [cell]'s lines against [glyphs] (µm). */
        fun judge(cell: String, text: String, glyphs: List<plymeld.geom.Polygon>) {
            val mine = moves.filter { it.cell == cell }
            if (mine.isEmpty()) return                      // reported with the cells
            val grown = plymeld.geom.Offset.offset(glyphs, Math.round(fw2 * 500))
            // the digits: the glyphs' strokes merged into outlines (a stroke overlapped by others holds no line of its own),
            // each outer outline a digit (or a digit's separate part, such as a decimal point)
            val digits = plymeld.geom.Offset.offset(glyphs, 10).filter { it.signedArea2() > 0 }
            val stray = mine.firstOrNull { m -> grown.none { p -> inside(p, (m.x0 + m.x1) / 2, (m.y0 + m.y1) / 2) } }
            val empty = digits.indexOfFirst { p -> mine.none { m -> inside(p, (m.x0 + m.x1) / 2, (m.y0 + m.y1) / 2) } }
            if (stray != null || empty >= 0) bad("'$cell': not the label '$text' where it goes (" +
                (if (stray != null) "a line at ${fmt(stray.x0, 2)}, ${fmt(stray.y0, 2)}-${fmt(stray.x1, 2)}, ${fmt(stray.y1, 2)} outside its digits" else "a stroke of its digits not printed") + ")")
        }
        val speedW = t.speeds.indices.maxOf { PlateMarks.labelWidth(spec.speedText(it)) } / 1000.0
        val heightX = x0 + gap + speedW + gap
        for (n in 1..g.labelLayers) {
            for (k in 0 until spec.blocks)
                judge(spec.markLabel(k, n), spec.tempText(k), PlateMarks.label(spec.tempText(k), um(o[0] + k * g.tileLength / 1000.0) + g.labelIn, um(y0 + gap)))
            for ((j, rc) in spec.rows.withIndex()) {
                val text = spec.heightText(rc.second); val hh = PlateMarks.labelHeight(text) / 1000.0
                judge(spec.heightLabelOf(rc.first, rc.second, n), text, PlateMarks.label(text, um(heightX), um((rowY[j] + rowY[j + 1]) / 2 - hh / 2)))
            }
            for (r in t.speeds.indices) {
                val text = spec.speedText(r); val sh = PlateMarks.labelHeight(text) / 1000.0
                val ya = rowY[r * t.heightsUm.size]; val yb = rowY[(r + 1) * t.heightsUm.size]
                judge(spec.speedLabelOf(r, n), text, PlateMarks.label(text, um(x0 + gap), um((ya + yb) / 2 - sh / 2)))
            }
        }
    }

    /** The connectivity check's sampling step (mm): footprints closer than this count as touching. */
    private const val RES = 0.05

    /** How many separate pieces [ms]' footprints (each line its declared width wide, round-ended) make, sampled on a [RES] grid. */
    private fun pieces(ms: List<Move>): Int {
        if (ms.isEmpty()) return 0
        val pad = 1.0
        val x0 = ms.minOf { minOf(it.x0, it.x1) } - pad; val y0 = ms.minOf { minOf(it.y0, it.y1) } - pad
        val nx = ((ms.maxOf { maxOf(it.x0, it.x1) } + pad - x0) / RES).toInt() + 1; val ny = ((ms.maxOf { maxOf(it.y0, it.y1) } + pad - y0) / RES).toInt() + 1
        val on = BooleanArray(nx * ny)
        for (m in ms) {
            val hw = (if (m.widthMm.isNaN()) 0.4 else m.widthMm) / 2
            val i0 = maxOf(0, ((minOf(m.x0, m.x1) - hw - x0) / RES).toInt()); val i1 = minOf(nx - 1, ((maxOf(m.x0, m.x1) + hw - x0) / RES).toInt() + 1)
            val j0 = maxOf(0, ((minOf(m.y0, m.y1) - hw - y0) / RES).toInt()); val j1 = minOf(ny - 1, ((maxOf(m.y0, m.y1) + hw - y0) / RES).toInt() + 1)
            val dx = m.x1 - m.x0; val dy = m.y1 - m.y0; val l2 = dx * dx + dy * dy
            for (j in j0..j1) for (i in i0..i1) {
                val px = x0 + i * RES; val py = y0 + j * RES
                val u = if (l2 == 0.0) 0.0 else (((px - m.x0) * dx + (py - m.y0) * dy) / l2).coerceIn(0.0, 1.0)
                val ex = m.x0 + u * dx - px; val ey = m.y0 + u * dy - py
                if (ex * ex + ey * ey <= hw * hw + 1e-9) on[j * nx + i] = true
            }
        }
        val seen = BooleanArray(nx * ny); val stack = IntArray(nx * ny)
        var count = 0
        for (s in on.indices) {
            if (!on[s] || seen[s]) continue
            count++
            var sp = 0; stack[sp++] = s; seen[s] = true
            while (sp > 0) {
                val p = stack[--sp]; val i = p % nx; val j = p / nx
                for (q in intArrayOf(if (i > 0) p - 1 else -1, if (i < nx - 1) p + 1 else -1, if (j > 0) p - nx else -1, if (j < ny - 1) p + nx else -1))
                    if (q >= 0 && on[q] && !seen[q]) { seen[q] = true; stack[sp++] = q }
            }
        }
        return count
    }

    /**
     * The travels, from the g-code text: none may cross a FINISHED tile or frame ring (its last line so far
     * already laid) at or below its height, its whole move counted at its lower end (a travel that rises as it goes is as low as its start). A
     * travel onto a tile not yet printed, or across bare bed, needs no lift (the first-layer plate's lift
     * rule); the travel off a tile, which starts over it, does. A tile's or ring's footprint
     * is its lines' extent, each line its declared width.
     */
    fun travels(gcode: String): List<String> {
        val out = ArrayList<String>()
        class Foot(val label: String, val boxes: ArrayList<DoubleArray>, var z: Double, var lastSeq: Int)
        val feet = LinkedHashMap<String, Foot>()
        class Tr(val seq: Int, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double)
        val travels = ArrayList<Tr>()
        var cell: String? = null; var hw = 0.0
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        for ((seq, raw) in gcode.lineSequence().withIndex()) {
            val l = raw.trim()
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: ").trim(); continue }
            if (l == "; CELL END") { cell = null; continue }
            widthOf(l)?.let { hw = it / 2 }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            if (!x.isNaN() && (nx != x || ny != y)) {
                val cl = cell
                val part = cl?.let { cellOf(it)?.part }
                if (e > 0 && cl != null && (part == "tile" || part == "frame" || part == "border")) {
                    val f = feet.getOrPut(cl) { Foot(cl, ArrayList(), nz, seq) }
                    f.boxes += doubleArrayOf(minOf(x, nx) - hw, minOf(y, ny) - hw, maxOf(x, nx) + hw, maxOf(y, ny) + hw); f.z = nz; f.lastSeq = seq
                } else if (e <= 0) travels += Tr(seq, x, y, z, nx, ny, nz)
            }
            x = nx; y = ny; z = nz
        }
        for (tr in travels) for (f in feet.values) {
            if (f.lastSeq > tr.seq) continue                     // not finished yet (or not begun)
            val low = minOf(tr.z0, tr.z1)
            if (low > f.z + 1e-6) continue
            if (f.boxes.any { crosses(tr.x0, tr.y0, tr.x1, tr.y1, it) } && out.size < 20)
                out += "a travel at z ${fmt(low, 3)} across finished '${f.label}' (its height ${fmt(f.z, 3)}): no lift (from ${fmt(tr.x0, 2)}, ${fmt(tr.y0, 2)} to ${fmt(tr.x1, 2)}, ${fmt(tr.y1, 2)})"
        }
        return out
    }

    /** Whether the segment (ax, ay)-(bx, by) passes through the inside of box [b] (x0, y0, x1, y1): Liang-Barsky clipping, a graze along an edge not counted. */
    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, b: DoubleArray): Boolean {
        val e = 1e-6
        var t0 = 0.0; var t1 = 1.0
        val dx = bx - ax; val dy = by - ay
        for ((p, q) in listOf(-dx to ax - (b[0] + e), dx to (b[2] - e) - ax, -dy to ay - (b[1] + e), dy to (b[3] - e) - ay)) {
            if (p == 0.0) { if (q < 0) return false }
            else { val r = q / p; if (p < 0) { if (r > t1) return false; if (r > t0) t0 = r } else { if (r < t0) return false; if (r < t1) t1 = r } }
        }
        return t1 > t0
    }

    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Extrusion moves inside cells with the state they ran in (the nozzle target for [tool] and whether a wait to that
     * target came after it was set), and the number of layer markers. Extrusion outside every cell between the plate's
     * first line and its last is returned under the cell name "(none)" (the start template's prime line, before the first
     * cell, and the end's, after the last, are the machine's own). Relative extrusion (M83), as plymeld writes.
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
                    num('Z')?.let { zUm = Math.round(it * 1000) }
                    val e = num('E') ?: 0.0
                    val len = if (x.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
                    if (e > 0 && len > 0) {
                        val mv = Move(cell ?: "(none)", x, y, nx, ny, zUm, e, f, accel, target[tool], waited[tool] == true, fan, layer, width, seq)
                        if (cell != null) inCells += mv else outside += cellsClosed to mv
                    }
                    x = nx; y = ny
                }
                "M204" -> num('S')?.let { accel = it }
                "SET_VELOCITY_LIMIT" -> words.firstOrNull { it.startsWith("ACCEL=") }?.let { accel = it.removePrefix("ACCEL=").toDouble() }
                // a new target is not reached until a wait for it; a wait for the target in force reaches it
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
