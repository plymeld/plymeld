package plymeld.core.calibrate

/**
 * Reads a first-layer plate's g-code back ([FirstLayerPlate]) and checks it against its specification, as the printer
 * will run it. Its reference is the [FirstLayerSpec] and the
 * layout figures it works out itself from [PlateGeometry] and [FirstLayerGeometry] and from the g-code's own lines, never
 * the generator's paths:
 *
 *  - one layer marker only (the frame prints within it, at its own heights), and nothing but the plate's cells: per
 *    block, its prime line, every tile within the block's melt limit (and none over it: those are left out), the
 *    connectors between its columns, its marker, and its frame (over each column, and across each gap the connectors
 *    cross); no other extrusion between the plate's first line and its last;
 *  - each tile at its column's height and its row's speed, the first layer's acceleration, its filament per mm the bead
 *    model's at its width and height times the flow ratio, the width declared, its lines reaching into its wall by the
 *    line-end overlap; its flow, read from the g-code, within the block's melt limit;
 *  - the frame at its column's frame height (a line across a gap: the higher column's), the frame's speed (the machine's,
 *    held to the block's melt limit), width, acceleration, and filament per mm (a frame line's bead one frame layer high),
 *    its flow within the melt limit;
 *  - each block's nozzle temperature: the target in force on every line of the block is the block's, and a wait (M109) to
 *    it came before the block's first line; the blocks in print order, coolest first; the part fan off;
 *  - the prime lines, connectors, and marker dots at the first layer's speed, width, and filament per mm; the dots at the
 *    first layer's height, the prime line at it or the lowest tile's if lower;
 *  - the geometry: each tile square with as many lines as its height's bead spacing makes, exactly that spacing apart,
 *    its first line starting at the middle of its back edge and its wall closing there; a block's columns one tile pitch
 *    apart and each column's tiles touching edge to edge; block n's marker n dots in front of its tiles; its prime line
 *    along the block's front to its left edge, then back along it to touch the first tile; each connector touching a tile
 *    on either side, inside none, at the lower one's height and printed before the higher one; each block's first layer
 *    ONE piece (its first-layer lines' footprints, sampled every 0.05 mm, connected; the frame not counted, so it cannot
 *    stand in for a missing connector); blocks left to right, then the next row behind, none overlapping;
 *  - the frame: no frame line's footprint inside a tile's wall (the part read), each frame line one frame
 *    layer above the highest tile under it as printed, every point of it over a tile or a connector (none over air),
 *    printed after its block's first layer, lowest first, and ONE piece at its own level (it joins the columns);
 *  - no travel crosses a finished tile, connector, or frame line at or below its height (the lift).
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
     * A cell label's block (0-based) and part: "prime", "mark", "tile" with its row and column, "connector" with its first
     * column, "frame" with its column, "bridge" (a frame line across a gap) with its first column (all 0-based), or null.
     */
    private class Cell(val block: Int, val part: String, val r: Int = -1, val c: Int = -1, val back: Boolean = false)
    private fun cellOf(label: String): Cell? {
        val m = Regex("block (\\d+) (prime|mark|tile (\\d+)\\.(\\d+)|connector (\\d+)-(\\d+) (front|back)|frame (\\d+)(?:-(\\d+) (front|back))?)").matchEntire(label) ?: return null
        val k = m.groupValues[1].toInt() - 1
        val g = m.groupValues
        return when {
            g[2].startsWith("tile") -> Cell(k, "tile", g[3].toInt() - 1, g[4].toInt() - 1)
            g[2].startsWith("connector") -> Cell(k, "connector", c = g[5].toInt() - 1, back = g[7] == "back")
            g[2].startsWith("frame") && g[9].isNotEmpty() -> Cell(k, "bridge", c = g[8].toInt() - 1, back = g[10] == "back")
            g[2].startsWith("frame") -> Cell(k, "frame", c = g[8].toInt() - 1)
            else -> Cell(k, g[2])
        }
    }
    private fun isFrame(part: String?) = part == "frame" || part == "bridge"

    fun check(spec: FirstLayerSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 60) out += s }
        val (moves, layers) = parse(gcode, spec.tool)
        val t = spec.tiles
        if (layers != 1) bad("$layers layers in the g-code, not 1 (a first-layer plate is one layer; its frame prints within it, at its own heights)")
        moves.firstOrNull { it.layer != 0 }?.let { bad("'${it.cell}' printed on layer ${it.layer}, not the first (layer 0)") }
        // the cells: every block's prime line, tiles within its melt limit, connectors between columns that print tiles, and marker
        val want = LinkedHashSet<String>()
        for (k in 0 until spec.blocks) {
            want += spec.primeLabel(k)
            for (r in t.speeds.indices) for (c in t.heightsUm.indices) if (spec.printed(k, r, c)) want += spec.tileLabel(k, r, c)
            for (c in 0 until t.heightsUm.size - 1) if (minOf(spec.rowsIn(k, c), spec.rowsIn(k, c + 1)) > 0) for (back in listOf(false, true)) want += spec.connectorLabel(k, c, back)
            want += spec.markLabel(k)
            // the frame: over every column that prints a tile, and across every gap the connectors cross
            for (c in t.heightsUm.indices) if (spec.rowsIn(k, c) > 0) want += spec.frameLabel(k, c)
            for (c in 0 until t.heightsUm.size - 1) if (minOf(spec.rowsIn(k, c), spec.rowsIn(k, c + 1)) > 0) for (back in listOf(false, true)) want += spec.bridgeLabel(k, c, back)
        }
        val byCell = moves.groupBy { it.cell }
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        for (c in byCell.keys) if (c !in want) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have" +
            (cellOf(c)?.takeIf { it.part == "tile" && it.block in 0 until spec.blocks }?.let { cl -> " (over block ${cl.block + 1}'s melt limit, ${fmt(spec.capsMm3s[cl.block], 1)} mm3/s at ${spec.tempsC[cl.block]} C: left out)" } ?: ""))
        // print order: block by block (each block's lines together, block 1 first), each block's prime line before its tiles
        val order = moves.mapNotNull { m -> cellOf(m.cell)?.block }
        if (order.zipWithNext().any { (a, b) -> b < a }) bad("the blocks do not print in order (block 1, the coolest, first; each block's lines together)")
        for (k in 0 until spec.blocks) {
            val first = moves.indexOfFirst { cellOf(it.cell)?.let { c -> c.block == k && c.part == "tile" } == true }
            val prime = moves.indexOfFirst { it.cell == spec.primeLabel(k) }
            if (first >= 0 && prime >= 0 && prime > first) bad("block ${k + 1}'s prime line comes after its first tile")
        }
        // the blocks print coolest first: the temperatures in the order the g-code reaches them
        val printed = (0 until spec.blocks).mapNotNull { k -> moves.firstOrNull { cellOf(it.cell)?.block == k }?.tempC }
        if (printed.zipWithNext().any { (a, b) -> b <= a }) bad("the blocks' temperatures as printed, ${printed.joinToString()} C, do not rise block by block (coolest first)")
        // each line's values against the specification
        val tileFirst = HashSet<String>()
        for (m in moves) {
            val c = cellOf(m.cell) ?: continue
            if (c.block !in 0 until spec.blocks) continue
            if (c.part == "tile" && (c.r !in t.speeds.indices || c.c !in t.heightsUm.indices)) continue
            if ((c.part == "connector" || c.part == "bridge") && c.c !in 0 until t.heightsUm.size - 1) continue
            if (c.part == "frame" && c.c !in t.heightsUm.indices) continue
            val wantT = spec.tempsC[c.block]
            val key = "${m.cell}/" // the first problem of each kind per cell (its lines are alike)
            fun once(kind: String, s: String) { if (tileFirst.add(key + kind)) bad(s) }
            if (m.tempC != wantT) once("temp", "'${m.cell}': at ${m.tempC} C, not ${wantT} C (block ${c.block + 1}'s)")
            else if (!m.waited) once("wait", "'${m.cell}': no wait (M109) for ${wantT} C before it: it may print while the nozzle is still heating")
            if (m.fanPct != 0.0) once("fan", "'${m.cell}': fan ${fmt(m.fanPct)} %, not off")
            val (wantZ, wantV, wantW, accel) = when (c.part) {
                "tile" -> listOf(t.heightsUm[c.c].toDouble(), t.speeds[c.r], t.widthUm.toDouble(), t.accel)
                "prime" -> listOf(spec.primeUm.toDouble(), spec.prime.speed, spec.firstWidthUm.toDouble(), spec.prime.accel)
                // a connector's height is checked against the tiles it touches (geometry); its filament at its own height
                "connector" -> listOf(m.zUm.toDouble(), spec.prime.speed, spec.firstWidthUm.toDouble(), spec.prime.accel)
                // the frame: one layer above its column's tiles (a line across a gap: the higher column's); also checked
                // against the tiles as printed under it (geometry); the machine's print speed held to the block's melt limit
                "frame" -> listOf(spec.frameUm(c.c).toDouble(), spec.frameSpeed(c.block), spec.frameWidthUm.toDouble(), spec.frame.accel)
                "bridge" -> listOf(spec.bridgeUm(c.c).toDouble(), spec.frameSpeed(c.block), spec.frameWidthUm.toDouble(), spec.frame.accel)
                else -> listOf(spec.layerUm.toDouble(), spec.prime.speed, spec.firstWidthUm.toDouble(), spec.prime.accel)
            }
            val frame = isFrame(c.part)
            // the bead's height: a first-layer line's is its height over the bed; a frame line's the frame's layer height
            val beadH = if (frame) spec.frameLayerUm else wantZ.toLong()
            val flow = when { c.part == "tile" -> spec.flowRatio; frame -> spec.frame.flowRatio; else -> spec.prime.flowRatio }
            val what = when (c.part) { "tile" -> " (its height)"; "prime" -> " (the first layer's, or the lowest tile's if lower)"; "frame" -> " (one layer above its tiles)"; "bridge" -> " (one layer above the higher column's tiles)"; else -> " (the first layer's)" }
            if (m.zUm != wantZ.toLong()) once("z", "'${m.cell}': printed at z ${m.zUm / 1000.0}, not ${wantZ / 1000.0}$what")
            if (Math.abs(m.fMmMin / 60 - wantV) > 1.0 / 60 + 1e-9) once("v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(wantV, 2)}")
            val expectE = spec.beadMm2(wantW.toLong(), beadH) / spec.filamentMm2 * flow
            if (Math.abs(m.e / m.len - expectE) > expectE * 0.01 + 2e-5 / m.len) once("e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(expectE, 5)} (a ${wantW / 1000.0} mm line ${beadH / 1000.0} mm high, flow ratio $flow)")
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - wantW / 1000.0) > 5e-4) once("w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(wantW / 1000.0, 3)}")
            if (Math.abs(m.accel - accel) > 0.5) once("a", "'${m.cell}': acceleration ${fmt(m.accel)}, not ${fmt(accel)}")
            // the melt limit, from the g-code itself: filament per mm x the filament's section x the feed
            if (c.part == "tile" || frame) {
                val mm3s = m.e / m.len * spec.filamentMm2 * m.fMmMin / 60
                val cap = spec.capsMm3s[c.block]
                if (mm3s > cap * 1.001 + 1e-6) once("cap", "'${m.cell}': ${fmt(mm3s, 1)} mm3/s, over block ${c.block + 1}'s melt limit ${fmt(cap, 1)} mm3/s at $wantT C " +
                    if (frame) "(the frame's speed is held to it)" else "(a tile over it is left out, never printed)")
            }
        }
        geometry(spec, moves, ::bad)
        out += travels(gcode)
        return out
    }

    /**
     * The layout, from the g-code's own lines against figures this check works out itself: each tile square with its line
     * count and spacing for its height, its first line starting at the middle of its back edge and its wall closing there;
     * each block's columns a tile pitch ([PlateGeometry.tileAbout] + [PlateGeometry.tileGap]) apart and each column's tiles
     * touching (each one's front edge on the back edge of the one in front); its marker dots ([FirstLayerGeometry.dot]
     * square, as many as its number) in front of its tiles; its prime line along its front to its left edge, then back to
     * touch the first tile; each connector touching a tile on either side, overlapping none, at the lower one's height and
     * printed before the higher one; each block one piece; the blocks in print order left to right, then a row further
     * back, none overlapping.
     */
    private fun geometry(spec: FirstLayerSpec, moves: List<Move>, bad: (String) -> Unit) {
        val t = spec.tiles
        val tw = t.widthUm / 1000.0; val fw = spec.firstWidthUm / 1000.0
        val ov = Math.round(t.wallOverlap * t.widthUm) / 1000.0
        val pitch = (PlateGeometry.tileAbout + PlateGeometry.tileGap) / 1000.0
        fun box(ms: List<Move>, w: Double) = doubleArrayOf(ms.minOf { minOf(it.x0, it.x1) } - w / 2, ms.minOf { minOf(it.y0, it.y1) } - w / 2, ms.maxOf { maxOf(it.x0, it.x1) } + w / 2, ms.maxOf { maxOf(it.y0, it.y1) } + w / 2)
        val blockBoxes = ArrayList<DoubleArray>(); val gridFronts = ArrayList<Double>()
        for (k in 0 until spec.blocks) {
            var x0 = Double.NaN; var front = Double.NaN
            val all = ArrayList<DoubleArray>()
            // the block's tiles as printed: box, height, first move's index
            class Tile(val label: String, val c: Int, val b: DoubleArray, val zUm: Long, val firstSeq: Int)
            val tiles = ArrayList<Tile>()
            for (c in t.heightsUm.indices) {
                var behind: DoubleArray? = null              // the tile in front of this one in the column, as printed
                for (r in t.speeds.indices) {
                    val l = spec.tileLabel(k, r, c)
                    val mine = moves.filter { it.cell == l }
                    if (mine.isEmpty()) { behind = null; continue }  // left out (or reported as a missing cell)
                    val h = t.heightsUm[c] / 1000.0
                    val sp = tw - h * (1 - Math.PI / 4)
                    val lines = maxOf(2, Math.floor((PlateGeometry.tileAbout / 1000.0 - tw - 2 * sp) / sp + 1e-9).toInt() + 1)
                    val size = (lines - 1) * sp + 2 * sp + tw
                    val b = box(mine, tw); all += b
                    tiles += Tile(l, c, b, mine.first().zUm, mine.first().seq)
                    if (Math.abs(b[2] - b[0] - size) > 0.03 || Math.abs(b[3] - b[1] - size) > 0.03) bad("'$l' is ${fmt(b[2] - b[0], 2)} x ${fmt(b[3] - b[1], 2)} mm, not ${fmt(size, 2)} square ($lines lines at ${fmt(sp, 3)} mm)")
                    // its lines: from bead contact with the wall at each side, plus the line-end overlap into it
                    val long = mine.filter { Math.abs(it.y1 - it.y0) < 1e-6 && Math.abs(it.len - (size - tw - 2 * sp + 2 * ov)) < 0.05 }
                    val across = long.map { it.y0 }.sorted()
                    if (across.size != lines) bad("'$l' has ${across.size} lines, not $lines")
                    else for (i in 1 until across.size) if (Math.abs(across[i] - across[i - 1] - sp) > 0.012) { bad("'$l': lines $i and ${i + 1} are ${fmt(across[i] - across[i - 1], 3)} mm apart, not ${fmt(sp, 3)} (the bead spacing at ${fmt(h, 2)} mm)"); break }
                    // its first line starts at the middle of its back edge (half a line in), and its wall closes there
                    val sx = (b[0] + b[2]) / 2; val sy = b[3] - tw / 2
                    val st = mine.first()
                    if (Math.abs(st.x0 - sx) > 0.03 || Math.abs(st.y0 - sy) > 0.03) bad("'$l' starts at x ${fmt(st.x0, 2)}, y ${fmt(st.y0, 2)}, not at the middle of its back edge (${fmt(sx, 2)}, ${fmt(sy, 2)})")
                    else if (mine.drop(1).none { Math.abs(it.x1 - sx) < 0.03 && Math.abs(it.y1 - sy) < 0.03 }) bad("'$l': its wall does not close at its start (the middle of its back edge)")
                    if (r == 0 && c == 0) { x0 = b[0]; front = b[1] }
                    else if (!x0.isNaN()) {
                        if (Math.abs(b[0] - (x0 + c * pitch)) > 0.03) bad("'$l' starts at x ${fmt(b[0], 2)}, not ${fmt(x0 + c * pitch, 2)} (column ${c + 1} of block ${k + 1})")
                        if (r == 0 && Math.abs(b[1] - front) > 0.03) bad("'$l''s front is at y ${fmt(b[1], 2)}, not ${fmt(front, 2)} (the block's front row)")
                    }
                    // a column's tiles touch: this one's front edge on the back edge of the one in front of it
                    behind?.let { p -> if (Math.abs(b[1] - p[3]) > 0.03) bad("'$l''s front edge is ${fmt(b[1] - p[3], 2)} mm from the back of the tile in front of it, not touching it (column ${c + 1} of block ${k + 1})") }
                    behind = b
                }
            }
            if (x0.isNaN() || all.isEmpty()) { bad("block ${k + 1}: no first tile (row 1, column 1) to measure it from"); continue }
            val grid = doubleArrayOf(all.minOf { it[0] }, all.minOf { it[1] }, all.maxOf { it[2] }, all.maxOf { it[3] })
            // the marker: as many separate dots as the block's number, each a dot square, in front of the tiles, within the block
            val mark = moves.filter { it.cell == spec.markLabel(k) }
            // separate dots: the lines' footprints merged wherever they touch, until no two touch
            var dots = mark.map { box(listOf(it), fw) }
            while (true) {
                val merged = ArrayList<DoubleArray>()
                for (sb in dots) {
                    val hit = merged.firstOrNull { d -> sb[0] <= d[2] + 1e-6 && sb[2] >= d[0] - 1e-6 && sb[1] <= d[3] + 1e-6 && sb[3] >= d[1] - 1e-6 }
                    if (hit != null) { hit[0] = minOf(hit[0], sb[0]); hit[1] = minOf(hit[1], sb[1]); hit[2] = maxOf(hit[2], sb[2]); hit[3] = maxOf(hit[3], sb[3]) } else merged += sb.copyOf()
                }
                if (merged.size == dots.size) break
                dots = merged
            }
            val dot = FirstLayerGeometry.dot / 1000.0
            if (dots.size != k + 1) bad("block ${k + 1}'s marker has ${dots.size} dot(s), not ${k + 1}")
            for (d in dots) {
                if (Math.abs(d[2] - d[0] - dot) > 0.03 || Math.abs(d[3] - d[1] - dot) > 0.03) { bad("block ${k + 1}: a marker dot ${fmt(d[2] - d[0], 2)} x ${fmt(d[3] - d[1], 2)} mm, not ${fmt(dot, 1)} square"); break }
                if (d[3] > grid[1] - FirstLayerGeometry.dotsFront / 1000.0 + 0.03 || d[0] < grid[0] - 0.03 || d[2] > grid[2] + 0.03) { bad("block ${k + 1}: a marker dot is not in front of its tiles, within the block (at x ${fmt(d[0], 2)}, y ${fmt(d[1], 2)})"); break }
            }
            // the prime line: along x (right to left) in front of the dots, ending at the block's left edge, then along y ending
            // touching the first tile's front edge
            val prime = moves.filter { it.cell == spec.primeLabel(k) }
            if (prime.isNotEmpty()) {
                val pb = box(prime, fw)
                val run = prime.filter { Math.abs(it.y1 - it.y0) < 1e-6 }; val leg = prime.filter { Math.abs(it.x1 - it.x0) < 1e-6 }
                if (run.isEmpty() || leg.isEmpty() || run.size + leg.size != prime.size || prime.indexOf(run.last()) > prime.indexOf(leg.first()))
                    bad("block ${k + 1}'s prime line is not a run along x then a run along y")
                else {
                    val last = prime.last()
                    if (Math.abs(run.last().x1 - fw / 2 - grid[0]) > 0.03) bad("block ${k + 1}'s prime line turns at x ${fmt(run.last().x1, 2)}, not at the block's left edge (${fmt(grid[0] + fw / 2, 2)})")
                    if (Math.abs(last.y1 + fw / 2 - front) > 0.03 || Math.abs(last.x1 - fw / 2 - grid[0]) > 0.03) bad("block ${k + 1}'s prime line ends at x ${fmt(last.x1, 2)}, y ${fmt(last.y1, 2)}, not touching its first tile's front edge (y ${fmt(front, 2)}) at its left corner")
                    if (run.sumOf { it.len } < FirstLayerGeometry.primeLen / 1000.0 - fw - 0.03) bad("block ${k + 1}'s prime line's run is ${fmt(run.sumOf { it.len }, 2)} mm, shorter than ${fmt(FirstLayerGeometry.primeLen / 1000.0 - fw, 2)}")
                    val runBack = run.maxOf { it.y0 } + fw / 2
                    if (dots.any { it[1] < runBack - 0.03 }) bad("block ${k + 1}'s prime line is not in front of its marker")
                }
                blockBoxes += doubleArrayOf(minOf(grid[0], pb[0]), pb[1], maxOf(grid[2], pb[2]), grid[3])
            } else blockBoxes += grid
            gridFronts += grid[1]
            // the connectors: each touches a tile on its left and one on its right, overlaps none, is at the lower of their
            // heights and printed before every higher one it touches
            for ((label, cm) in moves.filter { cellOf(it.cell)?.let { c -> c.block == k && c.part == "connector" } == true }.groupBy { it.cell }) {
                val foot = cm.map { box(listOf(it), fw) }
                val cb = box(cm, fw)
                for (tl in tiles) for (f in foot) {
                    val ox = minOf(f[2], tl.b[2]) - maxOf(f[0], tl.b[0]); val oy = minOf(f[3], tl.b[3]) - maxOf(f[1], tl.b[1])
                    if (ox > 0.03 && oy > 0.03) { bad("'$label' crosses into '${tl.label}' (${fmt(ox, 2)} x ${fmt(oy, 2)} mm inside it): a connector stays outside every tile"); break }
                }
                fun touching(side: Int) = tiles.filter { tl -> (if (side < 0) Math.abs(tl.b[2] - cb[0]) else Math.abs(tl.b[0] - cb[2])) <= 0.03 && tl.b[1] < cb[3] - 0.03 && tl.b[3] > cb[1] + 0.03 }
                val left = touching(-1); val right = touching(1)
                if (left.isEmpty() || right.isEmpty()) { bad("'$label' does not touch a tile on ${if (left.isEmpty()) "its left" else "its right"} (x ${fmt(cb[0], 2)} to ${fmt(cb[2], 2)})"); continue }
                val touched = left + right
                val low = touched.minOf { it.zUm }
                if (cm.any { it.zUm != low }) bad("'$label' printed at z ${cm.first().zUm / 1000.0}, not ${low / 1000.0} (the lower of the tiles it joins)")
                for (tl in touched) if (tl.zUm > cm.first().zUm && tl.firstSeq < cm.first().seq) bad("'$label' printed after '${tl.label}', which is higher: the nozzle would work beside it below its top")
            }
            // the frame: over the tiles' walls only (no frame line's footprint reaches into a tile's inside, its box less its
            // wall on every side), one layer above the highest tile it lies over as printed, after the block's first layer,
            // lowest first, and one piece at its own level (it joins the columns)
            val frameMoves = moves.filter { cellOf(it.cell)?.let { c -> c.block == k && isFrame(c.part) } == true }
            if (frameMoves.isNotEmpty()) {
                val fhw = spec.frameWidthUm / 1000.0 / 2
                for ((label, fm) in frameMoves.groupBy { it.cell }) {
                    run inside@{
                        for (m in fm) for (tl in tiles) {
                            val inner = doubleArrayOf(tl.b[0] + tw, tl.b[1] + tw, tl.b[2] - tw, tl.b[3] - tw)
                            val d = segRectDist(m.x0, m.y0, m.x1, m.y1, inner)
                            if (d < fhw - 0.01) { bad("'$label' covers '${tl.label}''s inside (the part read): its line comes ${fmt(fhw - d, 2)} mm past the wall, at x ${fmt(m.x0, 2)}, y ${fmt(m.y0, 2)} to x ${fmt(m.x1, 2)}, y ${fmt(m.y1, 2)}"); return@inside }
                        }
                    }
                    val under = tiles.filter { tl -> fm.any { m -> val f = box(listOf(m), 2 * fhw); minOf(f[2], tl.b[2]) - maxOf(f[0], tl.b[0]) > 0.03 && minOf(f[3], tl.b[3]) - maxOf(f[1], tl.b[1]) > 0.03 } }
                    if (under.isEmpty()) { bad("'$label' lies over no tile"); continue }
                    val wantZ = under.maxOf { it.zUm } + spec.frameLayerUm
                    fm.firstOrNull { it.zUm != wantZ }?.let { bad("'$label' printed at z ${it.zUm / 1000.0}, not ${wantZ / 1000.0}: one layer (${spec.frameLayerUm / 1000.0} mm) above the highest tile under it as printed, '${under.maxBy { u -> u.zUm }.label}' at ${under.maxOf { u -> u.zUm } / 1000.0}") }
                }
                // nothing of the frame over air: every point of its lines' centers (sampled every RES) over a tile as printed,
                // or over a connector's line
                val conn = moves.filter { cellOf(it.cell)?.let { c -> c.block == k && c.part == "connector" } == true }
                fun segDist(px: Double, py: Double, m: Move): Double {
                    val dx = m.x1 - m.x0; val dy = m.y1 - m.y0; val l2 = dx * dx + dy * dy
                    val u = if (l2 == 0.0) 0.0 else (((px - m.x0) * dx + (py - m.y0) * dy) / l2).coerceIn(0.0, 1.0)
                    return Math.hypot(m.x0 + u * dx - px, m.y0 + u * dy - py)
                }
                run air@{
                    for (m in frameMoves) {
                        val n = maxOf(1, Math.ceil(m.len / RES).toInt())
                        for (i in 0..n) {
                            val px = m.x0 + (m.x1 - m.x0) * i / n; val py = m.y0 + (m.y1 - m.y0) * i / n
                            val onTile = tiles.any { px >= it.b[0] - 1e-6 && px <= it.b[2] + 1e-6 && py >= it.b[1] - 1e-6 && py <= it.b[3] + 1e-6 }
                            if (!onTile && conn.none { c -> segDist(px, py, c) <= (if (c.widthMm.isNaN()) fw else c.widthMm) / 2 + 1e-6 }) {
                                bad("'${m.cell}' runs over air at x ${fmt(px, 2)}, y ${fmt(py, 2)}: over no tile and no connector"); return@air
                            }
                        }
                    }
                }
                val lastFirst = moves.filter { cellOf(it.cell)?.let { c -> c.block == k && !isFrame(c.part) } == true }.maxOfOrNull { it.seq } ?: -1
                frameMoves.firstOrNull { it.seq < lastFirst }?.let { bad("'${it.cell}' printed before block ${k + 1}'s first layer is finished") }
                frameMoves.zipWithNext().firstOrNull { (a, b) -> b.zUm < a.zUm }?.let { (a, b) -> bad("'${b.cell}' at z ${b.zUm / 1000.0} printed after '${a.cell}' at z ${a.zUm / 1000.0}: the frame goes down lowest first") }
                pieces(frameMoves).let { p -> if (p > 1) bad("block ${k + 1}'s frame is $p separate pieces, not one: it does not join the columns (its lines' footprints sampled every ${fmt(RES, 2)} mm)") }
            }
            // one piece on the bed: every first-layer line of the block (prime, tiles, connectors, marker) joined to the rest,
            // the frame left out (it lies on them, a layer up: it must not be what joins them, or a missing connector would
            // pass with a frame line over bare bed)
            val mine = moves.filter { cellOf(it.cell)?.let { c -> c.block == k && !isFrame(c.part) } == true }
            pieces(mine).let { p -> if (p > 1) bad("block ${k + 1} is $p separate pieces, not one (its first-layer lines' footprints sampled every ${fmt(RES, 2)} mm)") }
        }
        // blocks in print order: left to right in a row, then the next row behind (starting at the first block's left edge)
        for (k in 1 until blockBoxes.size) {
            val a = blockBoxes[k - 1]; val b = blockBoxes[k]
            val sameRow = Math.abs(gridFronts[k] - gridFronts[k - 1]) < 0.03
            val ok = if (sameRow) b[0] > a[2] else b[1] > blockBoxes.subList(0, k).maxOf { it[3] } && Math.abs(b[0] - blockBoxes[0][0]) < 0.03
            if (!ok) bad("block ${k + 1} is not right of block $k in its row, nor at the start of a row behind it")
        }
        for (i in blockBoxes.indices) for (j in i + 1 until blockBoxes.size) {
            val a = blockBoxes[i]; val b = blockBoxes[j]
            if (a[0] < b[2] && b[0] < a[2] && a[1] < b[3] && b[1] < a[3]) bad("blocks ${i + 1} and ${j + 1} overlap")
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
     * The travels, from the g-code text: none may cross a FINISHED tile, connector, or frame cell (its last line so far
     * already laid) at or below its height, its whole move counted at its lower end (a travel that rises as it goes is as low as its start). A
     * travel onto a tile not yet printed, or across bare bed, needs no lift (the first-layer plate's lift
     * rule); the travel off a tile, which starts over it, does. A tile's or connector's footprint
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
                if (e > 0 && cl != null && (part == "tile" || part == "connector" || isFrame(part))) {
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

    /** The shortest distance from the segment (ax, ay)-(bx, by) to box [r] (x0, y0, x1, y1), 0 where it touches or enters it; an empty box (x1 < x0 or y1 < y0) is infinitely far. */
    private fun segRectDist(ax: Double, ay: Double, bx: Double, by: Double, r: DoubleArray): Double {
        if (r[2] < r[0] || r[3] < r[1]) return Double.POSITIVE_INFINITY
        fun inside(x: Double, y: Double) = x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3]
        if (inside(ax, ay) || inside(bx, by) || crosses(ax, ay, bx, by, doubleArrayOf(r[0] - 2e-6, r[1] - 2e-6, r[2] + 2e-6, r[3] + 2e-6))) return 0.0
        fun pointBox(x: Double, y: Double) = Math.hypot(maxOf(r[0] - x, 0.0, x - r[2]), maxOf(r[1] - y, 0.0, y - r[3]))
        fun pointSeg(px: Double, py: Double): Double {
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val u = if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
            return Math.hypot(ax + u * dx - px, ay + u * dy - py)
        }
        return minOf(minOf(pointBox(ax, ay), pointBox(bx, by)), minOf(minOf(pointSeg(r[0], r[1]), pointSeg(r[2], r[1])), minOf(pointSeg(r[2], r[3]), pointSeg(r[0], r[3]))))
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
