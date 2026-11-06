package plymeld.core.calibrate

/**
 * Reads an ironing plate's g-code back ([IroningPlate]) and checks it against its specification, as the printer will run it
 *. Its reference is the [IroningSpec] and the layout figures it
 * works out itself from [IroningGeometry] and from the g-code's own lines, never the generator's paths:
 *
 *  - as many layers as the slab has, and nothing but the plate's cells: the slab on every layer (the top fill on the
 *    last), and after the top one ironing cell per zone that is not a control; no cell for a control, no other extrusion
 *    between the plate's first line and its last;
 *  - every slab layer at its own height, speed, acceleration, width, and filament per mm (the first layer's values and
 *    width on the first, the frame's between, a visible surface's on the top);
 *  - the top fill along the slab (0 degrees): its long lines level, one bead spacing apart with none missing;
 *  - each zone's pass at the top layer's height, its block's speed, the ironing acceleration, declared the bore wide, its
 *    filament per mm the slicer's ironing rule for its flow and spacing (worked out here, not read from the generator); its
 *    long lines at its angle, its spacing apart; every line inside its cell, and its nearest line to the slab's edge half the
 *    bore plus its inset in from it (and no more than a spacing further: the lines sit on the shared grid);
 *  - the plan's zones in print order after the top, nothing ironed over a control's cell or the empty cell;
 *  - the base's size from its outer ring on the base layers, whole (no notch), its front-left corner chamfered; the brim on
 *    the first layer only, touching the base and reaching its width out; above the base each slab in its place (a speed
 *    per slab, front to back), the front slab chamfered, its left end cut with one bar notch per its speed's place in the
 *    plan, each zone's stretch of its perimeter edge cut with its label (square notches for its flow's place, then V
 *    notches for its spacing's; none for a control); the legend raised behind the slabs;
 *  - each zone's lines ending at its cell's edge where it faces a neighbor (the un-ironed frame between them);
 *  - the first layer and the brim at the first-layer temperature and the top and the zones at the print temperature, each
 *    waited for before its first line (the layers between heating), the part fan off on the first layer and the
 *    filament's above it;
 *  - once the top is finished, no travel at or below its height leaves the zone cell it starts in (the lift off the top and
 *    off each zone; the ironing's own travels between a zone's lines stay in its cell), the travel counted at its lower end.
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object IroningCheck {
    private class Move(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val zUm: Long, val e: Double,
                       val fMmMin: Double, val accel: Double, val tempC: Int?, val waited: Boolean, val fanPct: Double, val layer: Int, val widthMm: Double, val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
    }

    /** A cell label's zone (0-based), or null. */
    private fun zoneOf(label: String): Int? = Regex("zone (\\d+)").matchEntire(label)?.groupValues?.get(1)?.toInt()?.minus(1)

    fun check(spec: IroningSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 60) out += s }
        val (moves, layers) = parse(gcode, spec.tool)
        if (layers != spec.layers) bad("$layers layers in the g-code, not ${spec.layers} (the slab's)")
        // the cells
        val want = LinkedHashSet<String>()
        want += spec.brimLabel
        for (l in 0 until spec.layers) want += spec.slabLabel(l)
        for (z in spec.zones) if (!z.control) want += spec.zoneLabel(z.index)
        val byCell = moves.groupBy { it.cell }
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        // the writer's own prime line before the first layer is no cell of the plate's (Part.isPrimeLine)
        for (c in byCell.keys) if (c !in want && !Part.isPrimeLine(c)) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have" +
            (zoneOf(c)?.takeIf { it in spec.zones.indices && spec.zones[it].control }?.let { " (zone ${it + 1} is a control: not ironed)" } ?: ""))
        // every line at its values
        val once = HashSet<String>()
        fun first(cell: String, kind: String, s: String) { if (once.add("$cell/$kind")) bad(s) }
        val fa = spec.filamentMm2
        for (m in moves) {
            val k = zoneOf(m.cell)
            // the brim is laid as the first layer is (its values, its width)
            val slabLayer = if (m.cell == spec.brimLabel) 0 else (0 until spec.layers).firstOrNull { spec.slabLabel(it) == m.cell }
            if (k == null && slabLayer == null) continue
            if (k != null && (k !in spec.zones.indices || spec.zones[k].control)) continue
            // the first layer and the brim at the first-layer temperature, waited for; the layers between heating to the top's;
            // the top and the zones at it, waited for
            val wantC = if (slabLayer == 0) spec.firstTempC else spec.tempC
            if (m.tempC != wantC) first(m.cell, "temp", "'${m.cell}': at ${m.tempC} C, not $wantC C")
            else if (!m.waited && (slabLayer == null || slabLayer == 0 || slabLayer == spec.layers - 1)) first(m.cell, "wait", "'${m.cell}': no wait (M109) for $wantC C before it")
            val wantFan = if (slabLayer == 0) 0.0 else spec.fanPct
            if (Math.abs(m.fanPct - wantFan) > 0.5) first(m.cell, "fan", "'${m.cell}': fan ${fmt(m.fanPct)} %, not ${fmt(wantFan)} %")
            val (wantZ, wantV, wantW, accel, wantE) = if (slabLayer != null) {
                val pv = if (slabLayer == 0) spec.adhesion else if (slabLayer == spec.layers - 1) spec.top else spec.frame
                val lw = if (slabLayer == 0) spec.firstWidthUm else spec.widthUm
                listOf(spec.zTop(slabLayer).toDouble(), pv.speed, lw.toDouble(), pv.accel, spec.beadMm2(lw, spec.heightOf(slabLayer)) / fa * pv.flowRatio)
            } else listOf(spec.topZ.toDouble(), spec.speeds[spec.zones[k!!].block], spec.nozzleUm.toDouble(), spec.ironAccel, spec.ironEPerMm(k))
            if (m.zUm != wantZ.toLong()) first(m.cell, "z", "'${m.cell}': printed at z ${m.zUm / 1000.0}, not ${wantZ / 1000.0}")
            if (Math.abs(m.fMmMin / 60 - wantV) > 1.0 / 60 + 1e-9) first(m.cell, "v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(wantV, 2)}")
            if (Math.abs(m.e / m.len - wantE) > wantE * 0.01 + 2e-5 / m.len) first(m.cell, "e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(wantE, 5)}" +
                (if (k != null) " (the ironing rule: flow ${fmt(spec.flows[spec.zones[k].flowIdx] * 100, 1)} % x spacing ${fmt(spec.spacingsUm[spec.zones[k].spacingIdx] / 1000.0, 3)} mm x the layer height, times the flow ratio)" else ""))
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - wantW / 1000.0) > 5e-4) first(m.cell, "w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(wantW / 1000.0, 3)}")
            if (Math.abs(m.accel - accel) > 0.5) first(m.cell, "a", "'${m.cell}': acceleration ${fmt(m.accel)}, not ${fmt(accel)}")
        }
        geometry(spec, moves, ::bad)
        out += travels(gcode, spec)
        return out
    }

    /** The piece, its slabs, marks, and zones from the g-code's own lines, against figures worked out from [IroningGeometry] and the specification. */
    private fun geometry(spec: IroningSpec, moves: List<Move>, bad: (String) -> Unit) {
        val g = IroningGeometry
        val zone = g.zone / 1000.0
        val pieceW = spec.pieceW / 1000.0; val pieceD = spec.pieceD / 1000.0
        val slabW = spec.slabW / 1000.0; val slabD = spec.slabD / 1000.0
        // the piece's origin on the bed: the base's outer ring on the first layer, half a line in
        val first = moves.filter { it.cell == spec.slabLabel(0) }
        if (first.isEmpty()) { bad("no first layer to measure the piece from"); return }
        val fw = spec.firstWidthUm / 1000.0
        val ox = first.minOf { minOf(it.x0, it.x1) } - fw / 2; val oy = first.minOf { minOf(it.y0, it.y1) } - fw / 2
        fun chamfered(mine: List<Move>, lw: Double, x0: Double, y0: Double): Double =
            mine.filter { Math.abs(minOf(it.x0, it.x1) - x0 - lw / 2) < 0.03 || Math.abs(minOf(it.y0, it.y1) - y0 - lw / 2) < 0.03 }
                .minOf { minOf(it.x0 - x0 + it.y0 - y0, it.x1 - x0 + it.y1 - y0) }
        val wantCorner = { lw: Double -> g.chamfer / 1000.0 + lw / 2 * Math.sqrt(2.0) }
        // the base: the whole piece, its front-left corner chamfered, no notch in it
        for (l in 0 until g.baseLayers) {
            val mine = moves.filter { it.cell == spec.slabLabel(l) }
            if (mine.isEmpty()) continue
            val lw = (if (l == 0) spec.firstWidthUm else spec.widthUm) / 1000.0
            val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - lw / 2, mine.minOf { minOf(it.y0, it.y1) } - lw / 2, mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2, mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2)
            if (Math.abs(b[2] - b[0] - pieceW) > 0.03 || Math.abs(b[3] - b[1] - pieceD) > 0.03) bad("layer $l: the base is ${fmt(b[2] - b[0], 2)} x ${fmt(b[3] - b[1], 2)} mm, not ${fmt(pieceW, 2)} x ${fmt(pieceD, 2)} (the slabs, the grooves, and the legend)")
            if (Math.abs(b[0] - ox) > 0.03 || Math.abs(b[1] - oy) > 0.03) bad("layer $l: the base starts at (${fmt(b[0], 2)}, ${fmt(b[1], 2)}), not where its first layer does (${fmt(ox, 2)}, ${fmt(oy, 2)})")
            val corner = chamfered(mine, lw, b[0], b[1])
            if (Math.abs(corner - wantCorner(lw)) > 0.05) bad("layer $l: the base's front-left corner is cut ${fmt(corner - lw / 2 * Math.sqrt(2.0), 2)} mm (by x + y from the corner), not the ${fmt(g.chamfer / 1000.0, 1)} mm chamfer")
            val gaps = faceGaps(mine, b[1] + lw / 2, true, b[0], b[2])
            if (gaps.isNotEmpty()) bad("layer $l: ${gaps.size} gap(s) in the base's front face (the base is whole; the notches are cut above it)")
        }
        // the brim: on the first layer only, round the base, reaching [IroningGeometry.brim] out (within half a line spacing)
        run {
            val brim = moves.filter { it.cell == spec.brimLabel }
            if (brim.isEmpty()) return@run
            if (brim.any { it.layer != 0 }) bad("the brim has lines above the first layer")
            val sp = fw - spec.firstLayerUm / 1000.0 * (1 - Math.PI / 4)
            val reach = listOf(ox - (brim.minOf { minOf(it.x0, it.x1) } - fw / 2), oy - (brim.minOf { minOf(it.y0, it.y1) } - fw / 2),
                brim.maxOf { maxOf(it.x0, it.x1) } + fw / 2 - (ox + pieceW), brim.maxOf { maxOf(it.y0, it.y1) } + fw / 2 - (oy + pieceD))
            reach.firstOrNull { Math.abs(it - g.brim / 1000.0) > sp / 2 + 0.05 }?.let { bad("the brim reaches ${fmt(it, 2)} mm out from the base, not ${fmt(g.brim / 1000.0, 1)} mm") }
            // touching the base: its innermost ring half a line out from the base's edge
            val inner = brim.filter { Math.abs(it.y0 - it.y1) < 1e-6 && it.y0 < oy }.maxOfOrNull { it.y0 }
            if (inner == null || Math.abs(oy - inner - fw / 2) > 0.03) bad("the brim's innermost ring is ${inner?.let { fmt(oy - it, 2) } ?: "?"} mm from the base's front edge, not half a line (${fmt(fw / 2, 2)}): not touching it")
        }
        // the slabs above the base: each in its place, the front one chamfered; each zone's label in its perimeter edge, each slab's speed bars in its left end
        for (l in g.baseLayers until spec.layers) {
            val onLayer = moves.filter { it.cell == spec.slabLabel(l) }
            if (onLayer.isEmpty()) continue
            val lw = spec.widthUm / 1000.0
            for (s in 0 until spec.slabs) {
                val want = spec.slabBox(s).let { doubleArrayOf(ox + it[0] / 1000.0, oy + it[1] / 1000.0, ox + it[2] / 1000.0, oy + it[3] / 1000.0) }
                val mine = onLayer.filter { insideBox(it.x0, it.y0, want, 0.3) && insideBox(it.x1, it.y1, want, 0.3) }
                val speed = spec.blockOf(s)
                if (mine.isEmpty()) { bad("layer $l: nothing of slab ${s + 1} (speed ${fmt(spec.speeds[speed], 1)} mm/s)"); continue }
                val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - lw / 2, mine.minOf { minOf(it.y0, it.y1) } - lw / 2, mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2, mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2)
                if ((0..3).any { Math.abs(b[it] - want[it]) > 0.03 }) bad("layer $l: slab ${s + 1} spans (${fmt(b[0] - ox, 2)}, ${fmt(b[1] - oy, 2)})-(${fmt(b[2] - ox, 2)}, ${fmt(b[3] - oy, 2)}) on the piece, not (${fmt(want[0] - ox, 2)}, ${fmt(want[1] - oy, 2)})-(${fmt(want[2] - ox, 2)}, ${fmt(want[3] - oy, 2)}) (${fmt(slabW, 1)} x ${fmt(slabD, 1)} mm)")
                if (s == 0) { val corner = chamfered(mine, lw, want[0], want[1]); if (Math.abs(corner - wantCorner(lw)) > 0.05) bad("layer $l: the front slab's front-left corner is cut ${fmt(corner - lw / 2 * Math.sqrt(2.0), 2)} mm, not the ${fmt(g.chamfer / 1000.0, 1)} mm chamfer") }
                // the speed bars: the gaps in the slab's left face
                val bars = faceGaps(mine, want[0] + lw / 2, false, want[1], want[3])
                val wantBars = spec.speedMarks.counts[speed]
                if (bars.size != wantBars) bad("layer $l: ${bars.size} bar notch(es) in slab ${s + 1}'s left end, not $wantBars (its speed, ${fmt(spec.speeds[speed], 1)} mm/s, ${marksWhy(spec.speedMarks, speed, "mm/s")})")
                // the zones' labels, in each zone's stretch of the slab's faces: on the front squares (the flow) then Vs (the
                // spacing), on the back round-bottomed notches (the inset) then slanted ones (the angle)
                for (z in spec.zones) {
                    val k = z.index
                    if (spec.slab(k) != s) continue
                    val zb = spec.zoneBox(k).let { doubleArrayOf(ox + it[0] / 1000.0, oy + it[1] / 1000.0, ox + it[2] / 1000.0, oy + it[3] / 1000.0) }
                    for (front in listOf(true, false)) {
                        val faceY = if (front) want[1] + lw / 2 else want[3] - lw / 2
                        val gaps = faceGaps(mine, faceY, true, zb[0], zb[2])
                        val kinds = gaps.map { (a, c) ->
                            // at the notch's mouth the ring turns off the face: diagonally into a V or a slanted notch, straight in
                            // to a square or a round-bottomed one
                            val diagonal = mine.any { m -> val on0 = Math.abs(m.y0 - faceY) < 0.03; val on1 = Math.abs(m.y1 - faceY) < 0.03
                                val atX = if (on0) m.x0 else m.x1
                                on0 != on1 && atX > a - 0.03 && atX < c + 0.03 && Math.abs(m.x1 - m.x0) > 0.05 }
                            if (front) (if (diagonal) 'v' else '#') else (if (diagonal) '\\' else 'u')
                        }.joinToString("")
                        val wantKinds = if (z.control) "" else if (front) "#".repeat(spec.flowMarks.counts[z.flowIdx]) + "v".repeat(spec.spacingMarks.counts[z.spacingIdx])
                            else "u".repeat(spec.insetMarks.counts[z.insetIdx]) + "\\".repeat(spec.angleMarks.counts[z.angleIdx])
                        if (kinds != wantKinds) bad("layer $l: zone ${k + 1}'s label in the slab's ${if (front) "front" else "back"} edge reads '${kinds}' (" +
                            (if (front) "# a square notch, v a V" else "u a round-bottomed notch, \\ a slanted one") + "), not '$wantKinds'" +
                            if (z.control) " (a control has no label)"
                            else if (front) " (flow ${fmt(spec.flows[z.flowIdx] * 100, 1)} %, ${marksWhy(spec.flowMarks, z.flowIdx, "%")}; spacing ${fmt(spec.spacingsUm[z.spacingIdx] / 1000.0, 3)} mm, ${marksWhy(spec.spacingMarks, z.spacingIdx, "mm", 1000.0)})"
                            else " (inset ${fmt(spec.insetsUm[z.insetIdx] / 1000.0, 3)} mm, ${marksWhy(spec.insetMarks, z.insetIdx, "mm", 1000.0)}; angle ${fmt(spec.anglesDeg[z.angleIdx], 1)} degrees, ${marksWhy(spec.angleMarks, z.angleIdx, "degrees")})")
                    }
                }
            }
            // the legend: raised on the base behind the last slab, on every layer above the base
            val lb = doubleArrayOf(ox, oy + spec.legendY / 1000.0, ox + pieceW, oy + (spec.legendY + spec.legendD) / 1000.0)
            if (onLayer.none { insideBox(it.x0, it.y0, lb, 0.3) }) bad("layer $l: no legend behind the slabs")
        }
        // the top fill: per slab, its long lines (longer than half a zone: the rings' pieces round the notches and the joins
        // between lines are shorter) along the slab (0 degrees), one bead spacing apart, none missing
        val top = moves.filter { it.cell == spec.slabLabel(spec.layers - 1) }
        if (top.isNotEmpty()) for (s in 0 until spec.slabs) {
            val lw = spec.widthUm / 1000.0; val sp = lw - spec.layerUm / 1000.0 * (1 - Math.PI / 4)
            val sb = spec.slabBox(s).let { doubleArrayOf(ox + it[0] / 1000.0, oy + it[1] / 1000.0, ox + it[2] / 1000.0, oy + it[3] / 1000.0) }
            // inside the rings across the slab; along it, clear of the labels' notches too (the lines between them are broken)
            val band = lw / 2 + sp * (PlateGeometry.frameWalls - 1) + 0.05
            val bandY = band + g.notchIn / 1000.0
            fun inner(px: Double, py: Double) = px > sb[0] - 0.01 && px < sb[2] + 0.01 && py > sb[1] + bandY && py < sb[3] - bandY
            val long = top.filter { it.len > zone / 2 && inner(it.x0, it.y0) && inner(it.x1, it.y1) }
            long.firstOrNull { Math.abs(it.y1 - it.y0) > 1e-6 }?.let { bad("slab ${s + 1}'s top fill has a ${fmt(it.len, 1)} mm line from (${fmt(it.x0, 2)}, ${fmt(it.y0, 2)}) to (${fmt(it.x1, 2)}, ${fmt(it.y1, 2)}) not along the slab (its lines run at 0 degrees)") }
            val ys = long.filter { Math.abs(it.y1 - it.y0) < 1e-6 }.map { it.y0 }.distinct().sorted()
            if (ys.size < 3) bad("slab ${s + 1}'s top fill has ${ys.size} lines along the slab inside its rings")
            else ys.zipWithNext { a, c -> c - a }.firstOrNull { Math.abs(it - sp) > sp * 0.02 }?.let { bad("slab ${s + 1}'s top fill lines are ${fmt(it, 3)} mm apart somewhere, not one bead spacing (${fmt(sp, 3)} mm): not solid, or a line missing") }
        }
        // the zones: each pass inside its cell, at its angle and spacing, its inset from its slab's edges; in order; nothing over a control
        val topLast = top.maxOfOrNull { it.seq } ?: -1
        var lastSeq = topLast
        val nz = spec.nozzleUm / 1000.0
        for (z in spec.zones) {
            val k = z.index
            val mine = moves.filter { it.cell == spec.zoneLabel(k) }
            val b = spec.zoneBox(k).let { doubleArrayOf(ox + it[0] / 1000.0, oy + it[1] / 1000.0, ox + it[2] / 1000.0, oy + it[3] / 1000.0) }
            if (z.control) {
                moves.firstOrNull { zoneOf(it.cell) != null && it.zUm == spec.topZ && insideBox((it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2, b) }
                    ?.let { bad("'${it.cell}' is laid over zone ${k + 1}'s cell, the control (not ironed)") }
                continue
            }
            if (mine.isEmpty()) continue
            if (mine.first().seq < lastSeq) bad("zone ${k + 1} is printed before ${if (lastSeq == topLast) "the top fill is finished" else "the zone before it"} (the zones print in order after the top)")
            lastSeq = maxOf(lastSeq, mine.last().seq)
            val sp = spec.spacingsUm[z.spacingIdx] / 1000.0
            val a = spec.anglesDeg[z.angleIdx]
            mine.firstOrNull { !insideBox(it.x0, it.y0, b, 0.01) || !insideBox(it.x1, it.y1, b, 0.01) }?.let { bad("'${it.cell}' runs outside its cell (x ${fmt(b[0], 1)}..${fmt(b[2], 1)}, y ${fmt(b[1], 1)}..${fmt(b[3], 1)}): from (${fmt(it.x0, 2)}, ${fmt(it.y0, 2)}) to (${fmt(it.x1, 2)}, ${fmt(it.y1, 2)})") }
            val long = mine.filter { it.len > 3.5 * sp }
            if (long.size < 3) { bad("'zone ${k + 1}' has ${long.size} lines longer than ${fmt(3.5 * sp, 2)} mm"); continue }
            val ux = Math.cos(Math.toRadians(a)); val uy = Math.sin(Math.toRadians(a))
            long.firstOrNull { Math.abs(((it.x1 - it.x0) * uy - (it.y1 - it.y0) * ux) / it.len) > Math.sin(Math.toRadians(0.5)) }
                ?.let { bad("'zone ${k + 1}': a line from (${fmt(it.x0, 2)}, ${fmt(it.y0, 2)}) to (${fmt(it.x1, 2)}, ${fmt(it.y1, 2)}) is not at the zone's angle, ${fmt(a, 1)} degrees") }
            val offs = long.map { -uy * (it.x0 + it.x1) / 2 + ux * (it.y0 + it.y1) / 2 }.sorted()
            val distinct = ArrayList<Double>()
            for (o in offs) if (distinct.isEmpty() || o - distinct.last() > 0.01) distinct += o
            distinct.zipWithNext { p, q -> q - p }.firstOrNull { Math.abs(it - sp) > 0.012 }?.let { bad("'zone ${k + 1}': lines ${fmt(it, 3)} mm apart, not its spacing ${fmt(sp, 3)}") }
            // its inset: the nearest point of its lines to its slab's edges its cell lies on (the front and back always, a
            // slab being one row of zones), half the bore plus the inset in (and within a spacing further)
            val sb = spec.slabBox(spec.slab(k)).let { doubleArrayOf(ox + it[0] / 1000.0, oy + it[1] / 1000.0, ox + it[2] / 1000.0, oy + it[3] / 1000.0) }
            val d = nz / 2 + spec.insetsUm[z.insetIdx] / 1000.0
            val edges = buildList { add("front" to mine.minOf { minOf(it.y0, it.y1) } - sb[1]); add("back" to sb[3] - mine.maxOf { maxOf(it.y0, it.y1) })
                if (spec.col(k) == 0) add("left" to mine.minOf { minOf(it.x0, it.x1) } - sb[0]); if (spec.col(k) == spec.columns - 1) add("right" to sb[2] - mine.maxOf { maxOf(it.x0, it.x1) }) }
            for ((name, dist) in edges) if (dist < d - 0.01 || dist > d + sp + 0.01)
                bad("'zone ${k + 1}': its lines come ${fmt(dist, 3)} mm from its slab's $name edge, not ${fmt(d, 3)} (half the bore and its inset ${fmt(spec.insetsUm[z.insetIdx] / 1000.0, 3)} mm) to ${fmt(d + sp, 3)} (a spacing further)")
            // the frame: on a side facing a neighbor, its lines end half the bore short of the cell's edge, no further in than a spacing more
            val sides = buildList { if (spec.col(k) > 0) add("left" to mine.minOf { minOf(it.x0, it.x1) } - b[0]); if (spec.col(k) < spec.columns - 1) add("right" to b[2] - mine.maxOf { maxOf(it.x0, it.x1) }) }
            for ((name, dist) in sides) if (dist < nz / 2 - 0.01 || dist > nz / 2 + sp + 0.01)
                bad("'zone ${k + 1}': its lines come ${fmt(dist, 3)} mm from its cell's $name edge (the frame), not ${fmt(nz / 2, 3)} to ${fmt(nz / 2 + sp, 3)}")
        }
        // the empty cells at the end of a block's back slabs (zones per block not a multiple of the slabs per block): nothing ironed there
        for (s in 0 until spec.slabs) for (c in 0 until spec.columns) {
            if (spec.zoneAt(s, c) != null) continue
            val step = (g.zone + g.frame) / 1000.0
            val b = doubleArrayOf(ox + c * step, oy + spec.slabY(s) / 1000.0, ox + c * step + zone, oy + spec.slabY(s) / 1000.0 + zone)
            moves.firstOrNull { zoneOf(it.cell) != null && insideBox((it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2, b) }?.let { bad("'${it.cell}' is laid over the empty cell at the end of slab ${s + 1}") }
        }
    }

    /**
     * The gaps in a face of the outer ring: the ring's segments lying on the line at [at] (a y when [horizontal], else an x)
     * between [from] and [to] along it, and the gaps between them wider than 0.05 mm (a notch's mouth, the ring going round it).
     */
    private fun faceGaps(mine: List<Move>, at: Double, horizontal: Boolean, from: Double, to: Double): List<Pair<Double, Double>> {
        val face = mine.filter { if (horizontal) Math.abs(it.y0 - at) < 0.03 && Math.abs(it.y1 - at) < 0.03 else Math.abs(it.x0 - at) < 0.03 && Math.abs(it.x1 - at) < 0.03 }
            .map { if (horizontal) minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) else minOf(it.y0, it.y1) to maxOf(it.y0, it.y1) }
            .filter { it.second > from - 0.01 && it.first < to + 0.01 }.sortedBy { it.first }
        val gaps = ArrayList<Pair<Double, Double>>()
        if (face.isEmpty()) return gaps
        var reach = face.first().second
        for ((a, c) in face.drop(1)) { if (a > reach + 0.05 && a > from && reach < to) gaps += reach to a; reach = maxOf(reach, c) }
        return gaps
    }

    private fun ordinal(n: Int) = when (n) { 1 -> "first"; 2 -> "second"; 3 -> "third"; else -> "${n}th" }

    /** Why value [i] carries its count of marks: that many of the legend's unit, or its place in the plan. */
    private fun marksWhy(m: IroningSpec.Marks, i: Int, unit: String, per: Double = 1.0) =
        if (m.unit != null) "${m.counts[i]} x the legend's ${fmt(m.unit / per, 3)} $unit" else "the plan's ${ordinal(i + 1)}"

    private fun insideBox(x: Double, y: Double, b: DoubleArray, tol: Double = 1e-6) = x >= b[0] - tol && x <= b[2] + tol && y >= b[1] - tol && y <= b[3] + tol

    /**
     * The travels, from the g-code text, once the top fill is finished (its last line laid): the ironing works ON the top at
     * its height, so a travel inside one zone's cell (between a zone's lines) may stay at that height, but **no travel at or
     * below the top's height may leave the cell it starts in** (onto the next zone, a control's cell, the empty cell, or off
     * the slab: it would drag across a finished zone or the as-laid top), the whole move counted at its lower end (a travel
     * that rises as it goes is as low as its start). The cells are the specification's, placed on the bed; a point within
     * [GRAZE] of a cell's boundary counts as in either cell (a zone's lines stop half a spacing short of the boundary).
     */
    fun travels(gcode: String, spec: IroningSpec): List<String> {
        val out = ArrayList<String>()
        val cells = spec.zones.map { z -> spec.zoneBox(z.index).let { b -> doubleArrayOf((b[0] + spec.originXUm) / 1000.0, (b[1] + spec.originYUm) / 1000.0, (b[2] + spec.originXUm) / 1000.0, (b[3] + spec.originYUm) / 1000.0) } }
        fun cellsAt(px: Double, py: Double): Set<Int> = cells.indices.filter { i -> val b = cells[i]; px >= b[0] - GRAZE && px <= b[2] + GRAZE && py >= b[1] - GRAZE && py <= b[3] + GRAZE }.toSet()
        var topDone = false; var topZ = Double.NaN
        var cell: String? = null
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        for (raw in gcode.lineSequence()) {
            val l = raw.trim()
            // the top is finished when the first zone's pass begins (every path of the top is a cell block of its own)
            if (l.startsWith("; CELL: ")) { val c = l.removePrefix("; CELL: ").trim(); cell = c; if (zoneOf(c) != null && !topZ.isNaN()) topDone = true; continue }
            if (l == "; CELL END") { cell = null; continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            if (!x.isNaN() && (nx != x || ny != y)) {
                if (e > 0 && cell == spec.slabLabel(spec.layers - 1)) topZ = nz
                else if (e <= 0 && topDone && minOf(z, nz) <= topZ + 1e-6 && out.size < 20) {
                    // every point of the travel in the cell it starts in (sampled every GRAZE)
                    val start = cellsAt(x, y)
                    val n = maxOf(1, Math.ceil(Math.hypot(nx - x, ny - y) / GRAZE).toInt())
                    val off = (1..n).firstOrNull { i -> val px = x + (nx - x) * i / n; val py = y + (ny - y) * i / n; cellsAt(px, py).intersect(start).isEmpty() }
                    if (off != null) out += "a travel at z ${fmt(minOf(z, nz), 3)}, the top's height (${fmt(topZ, 3)}), leaves ${if (start.isEmpty()) "the slab's edge" else "zone ${start.min() + 1}'s cell"} across the finished top: no lift (from ${fmt(x, 2)}, ${fmt(y, 2)} to ${fmt(nx, 2)}, ${fmt(ny, 2)})"
                }
            }
            x = nx; y = ny; z = nz
        }
        return out
    }

    /** How close to a cell's boundary a point counts as in either cell, and the travel sampling step (mm). */
    private const val GRAZE = 0.25

    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Extrusion moves inside cells with the state they ran in (the nozzle target for [tool] and whether a wait to that
     * target came after it was set), and the number of layer markers. Extrusion outside every cell between the plate's
     * first line and its last is returned under the cell name "(none)". Relative extrusion (M83), as plymeld writes.
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
