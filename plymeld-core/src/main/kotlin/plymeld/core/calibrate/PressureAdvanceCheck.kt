package plymeld.core.calibrate

/**
 * Reads a pressure-advance plate's g-code back ([PressureAdvancePlate]) and checks it against its specification, as the
 * printer will run it. Its reference is the [PressureAdvanceSpec] and the layout it works out itself from
 * [PressureAdvanceGeometry]; pressure advance is read through the machine's own command ([command], its value in [unit]):
 *
 *  - as many layers as the plate has; the base on each of its layers, the labels on the first wall layers, every wall (and
 *    on the find plate its corner) on every wall layer, nothing else;
 *  - every wall at its own pressure advance, set before it and by nothing inside a cell (its corner at the same value), and
 *    none written at all where the plate sets none (a machine that sets its own); the base and the labels at the frame's;
 *  - every wall one straight line along +Y at its place, every layer the same way, its moves unbroken (no retraction, no
 *    travel, no stop between its first and last), at the slow speed over its lead-in, its fast speed over its middle (the
 *    speed changes where the middle starts and ends), the slow speed over its tail; at the machine's acceleration; its
 *    filament per mm the bead's at the flow ratio;
 *  - the base: its first layer over the base's extent (the marks under it filled), and above it nothing laid in a
 *    speed-change hole, the confirm plate's flow-step notches (one, two, or three under each wall, centered on it), or the
 *    chamfer; the front edge whole where a wall's next flow-step notch would go; the gaps between the walls' strips open on
 *    every layer away from the tabs, each break-off tab laid across its gap on the base's layers but the last, and not on that;
 *  - where the plate has a push limit, every move at a wall's value (its wall and its corner) keeps the extruder within it,
 *    from the speed before it (rest at a run's start or a turn), its filament per mm and acceleration as written
 *    ([PressureAdvancePush], smoothed over the spec's [PressureAdvanceSpec.smoothS]);
 *  - the base's first layer at the first-layer temperature, the walls at theirs, waited for; the fan off on the first layer.
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object PressureAdvanceCheck {
    private class Move(val cell: String?, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val zUm: Long, val e: Double,
                       val f: Double, val accel: Double, val pa: Double?, val tempC: Int?, val waited: Boolean, val fanPct: Double, val layer: Int, val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
        val printing get() = e > 0 && len > 0
    }

    fun check(spec: PressureAdvanceSpec, gcode: String, command: Regex, unit: String): List<String> {
        val out = ArrayList<String>()
        val said = HashSet<String>()
        fun bad(key: String, s: String) { if (said.add(key) && out.size < 60) out += s }
        val g = PressureAdvanceGeometry
        val (moves, layers, paInCells) = parse(gcode, spec.tool, command, unit)
        if (layers != spec.layers) bad("layers", "$layers layers in the g-code, not ${spec.layers} (the base's ${g.baseLayers} and the walls' ${spec.wallLayers})")
        for (c in paInCells) bad("pa in $c", "pressure advance set inside '$c': a cell prints at the value set before it")
        val ox = spec.originXUm / 1000.0; val oy = spec.originYUm / 1000.0
        val byCell = moves.filter { it.printing }.groupBy { it.cell }
        // the cells
        val want = LinkedHashSet<String>(); want += spec.baseLabel
        for (w in spec.walls) { want += w.label; want += spec.labelCell(w); if (spec.corners) want += w.cornerLabel }
        for (c in want) if (c !in byCell) bad("no $c", "no '$c' cell")
        for (c in byCell.keys) if (c !in want && !(c != null && Part.isPrimeLine(c))) bad("extra $c", "${byCell.getValue(c).size} extrusion move(s) in '${c ?: "(no cell)"}', which the specification does not have")
        val bead = spec.beadMm2(spec.widthUm, spec.layerUm) / spec.filamentMm2 * spec.flowRatio
        // every wall, layer by layer
        for (w in spec.walls) {
            val mine = moves.filter { it.cell == w.label }
            val x = spec.wallXUm(w) / 1000.0 + ox; val ys = spec.wallStartY(w) / 1000.0 + oy
            val yUp = ys + spec.leadUm / 1000.0; val yDown = ys + (spec.leadUm + spec.fastUm) / 1000.0; val ye = spec.wallEndY(w) / 1000.0 + oy
            val byLayer = mine.groupBy { it.layer }
            val wallLayers = (g.baseLayers until spec.layers).toList()
            for (l in wallLayers) if (byLayer[l]?.any { it.printing } != true) bad("${w.label} layer $l", "'${w.label}' not printed on layer ${l + 1}")
            for ((l, ms) in byLayer) {
                val p = ms.filter { it.printing }
                if (p.isEmpty()) continue
                // unbroken: nothing but its own printing moves between its first and last
                val from = ms.indexOf(p.first()); val to = ms.indexOf(p.last())
                if (ms.subList(from, to + 1).any { !it.printing }) bad("${w.label} broken", "'${w.label}' (layer ${l + 1}): a stop, travel, or retraction inside the wall: one line, unbroken")
                for (m in p) {
                    if (Math.abs(m.x0 - x) > 0.002 || Math.abs(m.x1 - x) > 0.002) bad("${w.label} x", "'${w.label}' (layer ${l + 1}): a line at x ${fmt(m.x0, 3)} to ${fmt(m.x1, 3)}, not along the wall at ${fmt(x, 3)}")
                    if (m.y1 <= m.y0) bad("${w.label} dir", "'${w.label}' (layer ${l + 1}): printed toward -Y; every wall prints toward +Y on every layer")
                    val mid = (m.y0 + m.y1) / 2
                    val speed = if (mid > yUp && mid < yDown) w.fastSpeed else spec.slowSpeed
                    if ((m.y0 < yUp - 0.002 && m.y1 > yUp + 0.002) || (m.y0 < yDown - 0.002 && m.y1 > yDown + 0.002) || (m.y1 < yUp - 0.002 && m.y0 > yUp + 0.002))
                        bad("${w.label} change", "'${w.label}' (layer ${l + 1}): a line across a speed change (${fmt(m.y0, 2)} to ${fmt(m.y1, 2)}): the speed changes where the middle starts and ends")
                    if (Math.abs(m.f / 60 - speed) > 1.0 / 60 + 1e-9) bad("${w.label} v", "'${w.label}': ${fmt(m.f / 60, 2)} mm/s from y ${fmt(m.y0, 2)}, not ${fmt(speed, 2)}")
                    if (Math.abs(m.accel - spec.accel) > 0.5) bad("${w.label} a", "'${w.label}': acceleration ${fmt(m.accel, 0)}, not ${fmt(spec.accel, 0)}")
                    if (Math.abs(m.e / m.len - bead) > bead * 0.01 + 2e-5 / m.len) bad("${w.label} e", "'${w.label}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(bead, 5)}")
                    if (!same(m.pa, w.value)) bad("${w.label} pa", "'${w.label}': at pressure advance ${m.pa?.let { "${spec.valueText(it)} s" } ?: "none written"}, not ${w.value?.let { "${spec.valueText(it)} s" } ?: "none written"}")
                    if (m.tempC != spec.tempC || !m.waited) bad("${w.label} t", "'${w.label}': at ${m.tempC} C${if (m.waited) "" else " (not waited for)"}, not ${spec.tempC} C waited for")
                    if (Math.abs(m.fanPct - spec.fanPct) > 0.5) bad("${w.label} fan", "'${w.label}' (layer ${l + 1}): the part fan at ${fmt(m.fanPct, 0)} %, not ${fmt(spec.fanPct, 0)} %")
                }
                if (Math.abs(p.minOf { minOf(it.y0, it.y1) } - ys) > 0.002 || Math.abs(p.maxOf { maxOf(it.y0, it.y1) } - ye) > 0.002)
                    bad("${w.label} ends", "'${w.label}' (layer ${l + 1}): from y ${fmt(p.minOf { minOf(it.y0, it.y1) }, 2)} to ${fmt(p.maxOf { maxOf(it.y0, it.y1) }, 2)}, not ${fmt(ys, 2)} to ${fmt(ye, 2)}")
            }
            if (spec.corners) for (m in moves.filter { it.cell == w.cornerLabel && it.printing })
                if (!same(m.pa, w.value)) bad("${w.cornerLabel} pa", "'${w.cornerLabel}': at pressure advance ${m.pa?.let { "${spec.valueText(it)} s" } ?: "none written"}, not its wall's ${w.value?.let { "${spec.valueText(it)} s" } ?: "none"}")
        }
        // the base and the labels at the frame's value; the base's first layer at its temperature, the fan off
        for (m in moves.filter { it.printing && (it.cell == spec.baseLabel || it.cell?.startsWith("label ") == true) }) {
            if (!same(m.pa, spec.framePa)) bad("frame pa ${m.cell}", "'${m.cell}': at pressure advance ${m.pa?.let { "${spec.valueText(it)} s" } ?: "none written"}, not the frame's ${spec.framePa?.let { "${spec.valueText(it)} s" } ?: "none"}")
            if (m.layer == 0 && (m.tempC != spec.firstTempC || !m.waited)) bad("base t", "the base's first layer at ${m.tempC} C, not ${spec.firstTempC} C waited for")
            if (m.layer == 0 && m.fanPct > 0.5) bad("base fan", "the part fan on (${fmt(m.fanPct, 0)} %) on the first layer")
        }
        // the base: one piece, its marks
        val base = moves.filter { it.cell == spec.baseLabel && it.printing }
        val first = base.filter { it.layer == 0 }
        if (first.isNotEmpty()) {
            val fw = spec.firstWidthUm / 1000.0
            val b = doubleArrayOf(first.minOf { minOf(it.x0, it.x1) } - fw / 2, first.minOf { minOf(it.y0, it.y1) } - fw / 2, first.maxOf { maxOf(it.x0, it.x1) } + fw / 2, first.maxOf { maxOf(it.y0, it.y1) } + fw / 2)
            if (Math.abs(b[0] - ox) > 0.03 || Math.abs(b[1] - oy) > 0.03 || Math.abs(b[2] - b[0] - spec.baseW / 1000.0) > 0.03 || Math.abs(b[3] - b[1] - spec.baseD / 1000.0) > 0.03)
                bad("base size", "the base's first layer spans (${fmt(b[0], 2)}, ${fmt(b[1], 2)}) to (${fmt(b[2], 2)}, ${fmt(b[3], 2)}), not the base's ${fmt(spec.baseW / 1000.0, 1)} x ${fmt(spec.baseD / 1000.0, 1)} mm at (${fmt(ox, 2)}, ${fmt(oy, 2)})")
        }
        // the marks, worked out here: each hole and notch (shrunk a little: a line's edge may graze it) laid over on the first
        // layer and left open above it; the chamfer's corner open on every layer
        val holeW = PlateMarks.TALLY_W / 1000.0; val holeIn = PlateMarks.TALLY_IN / 1000.0
        val marks = ArrayList<Pair<String, DoubleArray>>()
        for (w in spec.walls) {
            val x1 = (spec.wallXUm(w) - spec.widthUm / 2 - g.holeGap) / 1000.0 + ox; val x0 = x1 - holeIn
            for ((name, at) in listOf("speed-up" to spec.markUp(w), "slow-down" to spec.markDown(w))) {
                val y = (spec.wallStartY(w) + at) / 1000.0 + oy
                marks += "${w.label}'s $name mark" to doubleArrayOf(x0, y - holeW / 2, x1, y + holeW / 2)
            }
        }
        val frontY = oy
        fun notch(x: Double, k: Int): DoubleArray { val d = PlateMarks.depth(k) / 1000.0; return doubleArrayOf(x - holeW / 2, frontY, x + holeW / 2, frontY + d) }
        // the confirm plate's flow steps: under each wall, in the front edge, one notch per step, centered on the wall; where a
        // next notch would go, the edge whole (so a wall's count reads no higher)
        val whole = ArrayList<Pair<String, DoubleArray>>()
        if (spec.kind == PressureAdvanceSpec.Kind.CONFIRM) for (w in spec.walls) {
            val n = w.step + 1
            val first = (spec.wallXUm(w) - PlateMarks.countLength(n) / 2 + PlateMarks.TALLY_W / 2) / 1000.0 + ox
            for (k in 0 until n) marks += "${w.label}'s flow-step notch ${k + 1} of $n in the front edge" to notch(first + k * PlateMarks.TALLY_PITCH / 1000.0, k)
            whole += "${w.label}'s flow step: the front edge where a notch ${n + 1} would go" to notch(first + n * PlateMarks.TALLY_PITCH / 1000.0, n)
        }
        // the strips: each gap between two walls' strips open on every base layer away from the tabs (a spot a tab's width
        // clear of each, in the gap's middle); each tab laid across its gap on the base's layers but the last, nothing there on the last
        val gapSpots = ArrayList<Pair<String, DoubleArray>>()
        val tabs = spec.tabs().map { t -> doubleArrayOf(t[0] / 1000.0 + ox, t[1] / 1000.0 + oy, t[2] / 1000.0 + ox, t[3] / 1000.0 + oy) }
        val sg = g.stripGap / 1000.0; val tw = g.tabW / 1000.0
        for (r in 0 until spec.rows) for (k in 1 until spec.perRow) {
            val x = (g.margin + k * g.pitch) / 1000.0 + ox; val y0 = (r * spec.rowDepth) / 1000.0 + oy
            // an eighth, a half, and seven eighths along the row: clear of the tabs at a quarter and three quarters
            for (q in listOf(1, 4, 7)) { val y = y0 + spec.rowDepth / 1000.0 * q / 8
                gapSpots += "the gap between row ${r + 1}'s strips $k and ${k + 1}" to doubleArrayOf(x - sg / 2, y - tw / 2, x + sg / 2, y + tw / 2) }
        }
        for (r in 1 until spec.rows) for (k in 0 until spec.perRow) {
            val s = spec.strip(r, k); val y = (r * spec.rowDepth) / 1000.0 + oy; val x = s[0] / 1000.0 + ox + 2 * tw
            gapSpots += "the gap between rows ${r} and ${r + 1} at strip ${k + 1}" to doubleArrayOf(x - tw / 2, y - sg / 2, x + tw / 2, y + sg / 2)
        }
        fun crosses(m: Move, box: DoubleArray, shrink: Double): Boolean {
            val n = maxOf(2, Math.ceil(m.len / 0.05).toInt())
            return (0..n).any { s -> val px = m.x0 + (m.x1 - m.x0) * s / n; val py = m.y0 + (m.y1 - m.y0) * s / n
                px > box[0] + shrink && px < box[2] - shrink && py > box[1] + shrink && py < box[3] - shrink }
        }
        val lw = spec.widthUm / 1000.0
        for ((name, box) in marks) {
            if (base.filter { it.layer == 0 }.none { crosses(it, box, 0.0) }) bad("first $name", "$name: the base's first layer leaves it open (the first layer is whole)")
            base.firstOrNull { it.layer > 0 && crosses(it, box, -lw / 2 + 0.1) }?.let { bad("filled $name", "$name: laid over on layer ${it.layer + 1} (open above the first layer)") }
        }
        for ((name, box) in whole) for (l in 1 until g.baseLayers)
            if (base.filter { it.layer == l }.none { crosses(it, box, 0.0) }) bad("cut $name", "$name: open on layer ${l + 1} (a notch there, read as one more step)")
        for ((name, box) in gapSpots) base.firstOrNull { crosses(it, box, -lw / 2 + 0.1) }?.let { bad("bridged $name", "$name: laid across on layer ${it.layer + 1} (the strips break apart along their gaps)") }
        for ((k, t) in tabs.withIndex()) {
            // the tab where it crosses its gap (its lines run across the gap there)
            val cx = (t[0] + t[2]) / 2; val cy = (t[1] + t[3]) / 2
            val across = if (t[2] - t[0] > t[3] - t[1]) doubleArrayOf(cx - sg / 2, cy - tw / 2, cx + sg / 2, cy + tw / 2) else doubleArrayOf(cx - tw / 2, cy - sg / 2, cx + tw / 2, cy + sg / 2)
            for (l in 0 until g.baseLayers) {
                val laid = base.any { it.layer == l && crosses(it, across, 0.0) }
                if (l < g.tabLayers && !laid) bad("tab $k $l", "break-off tab ${k + 1} at (${fmt(cx, 2)}, ${fmt(cy, 2)}) not laid on layer ${l + 1}: the plate does not hold together")
                if (l >= g.tabLayers && laid) bad("tab $k $l", "break-off tab ${k + 1} at (${fmt(cx, 2)}, ${fmt(cy, 2)}) laid on layer ${l + 1}, the base's last (its strips are joined only below it)")
            }
        }
        val ch = PlateMarks.CHAMFER / 1000.0
        val corner = doubleArrayOf(ox, oy, ox + ch / 3, oy + ch / 3)
        base.firstOrNull { crosses(it, corner, -lw / 2) }?.let { bad("chamfer", "the base's front-left corner laid on layer ${it.layer + 1}: no chamfer") }
        // the extruder's push: every move at a wall's value (its wall, its corner), from the speed before it in its run (rest
        // at a run's start), held within the plate's push limit, its own filament per mm and the acceleration it runs at
        spec.pushLimit?.let { limit ->
            val cells = spec.walls.flatMap { listOf(it.label, it.cornerLabel) }.toSet()
            for ((c, ms) in moves.filter { it.cell in cells }.groupBy { it.cell }) {
                var prev = 0.0; var last: Move? = null
                for (m in ms) {
                    if (!m.printing) { prev = 0.0; last = null; continue }
                    // a turn is taken at rest (the plate's corners are square)
                    val l = last
                    if (l != null && Math.abs((l.x1 - l.x0) * (m.y1 - m.y0) - (l.y1 - l.y0) * (m.x1 - m.x0)) > 1e-6 * l.len * m.len) prev = 0.0
                    last = m
                    val v = m.f / 60; val k = m.pa ?: 0.0
                    val peak = PressureAdvancePush.peak(k, prev, v, m.accel, spec.smoothS, m.e / m.len)
                    // a hundredth of a percent over, for the file's rounding of the filament per move
                    if (peak > limit * (1 + 1e-4)) bad("push $c", "'$c' (layer ${m.layer + 1}): the extruder pushed to ${fmt(peak, 1)} mm/s of filament at ${fmt(v, 1)} mm/s with ${spec.valueText(k)} s set (smoothed over ${fmt(spec.smoothS, 3)} s), over the plate's ${fmt(limit, 1)} mm/s")
                    prev = v
                }
            }
        }
        return out
    }

    private fun same(a: Double?, b: Double?) = if (a == null || b == null) a == b else Math.abs(a - b) <= 6e-6

    /**
     * The moves with the state they ran in (the cell, the pressure advance last set, the nozzle target for [tool] and whether
     * a wait to it came after it was set, the fan), the number of layer markers, and the cells a pressure-advance command fell
     * inside. Relative extrusion (M83).
     */
    private fun parse(gcode: String, tool: Int, command: Regex, unit: String): Triple<List<Move>, Int, Set<String>> {
        val moves = ArrayList<Move>(); val paInCells = LinkedHashSet<String>()
        var cell: String? = null
        var x = Double.NaN; var y = Double.NaN; var zUm = 0L
        var f = 0.0; var accel = Double.NaN; var fan = 0.0; var pa: Double? = null
        var target: Int? = null; var waited = false; var layer = -1
        for ((seq, raw) in gcode.lineSequence().withIndex()) {
            val line = raw.trim()
            if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: ").trim(); continue }
            if (line == "; CELL END") { cell = null; continue }
            val code = line.substringBefore(';').trim()
            if (code.isEmpty()) continue
            command.matchEntire(code)?.let { m -> pa = m.groupValues[1].toDouble().let { if (unit == "ms") it / 1000 else it }; cell?.let { paInCells += it } }
            val words = code.split(Regex("\\s+"))
            fun num(letter: Char) = words.drop(1).firstOrNull { it.isNotEmpty() && it[0] == letter }?.substring(1)?.toDoubleOrNull()
            when (words[0]) {
                "G0", "G1" -> {
                    num('F')?.let { f = it }
                    val nx = num('X') ?: x; val ny = num('Y') ?: y
                    num('Z')?.let { zUm = Math.round(it * 1000) }
                    val e = num('E') ?: 0.0
                    // a line that only sets the feed rate moves nothing
                    val moves0 = num('X') != null || num('Y') != null || num('Z') != null || num('E') != null
                    if (!x.isNaN() && layer >= 0 && moves0) moves += Move(cell, x, y, nx, ny, zUm, e, f, accel, pa, target, waited, fan, layer, seq)
                    x = nx; y = ny
                }
                "M204" -> num('S')?.let { accel = it }
                "SET_VELOCITY_LIMIT" -> words.firstOrNull { it.startsWith("ACCEL=") }?.removePrefix("ACCEL=")?.toDoubleOrNull()?.let { accel = it }
                "M104" -> if ((num('T')?.toInt() ?: tool) == tool) num('S')?.toInt()?.let { if (target != it) waited = false; target = it }
                "M109" -> if ((num('T')?.toInt() ?: tool) == tool) num('S')?.toInt()?.let { target = it; waited = true }
                "M106" -> if (num('P') == null) fan = (num('S') ?: 255.0) / 255 * 100
                "M107" -> fan = 0.0
            }
        }
        return Triple(moves, layer + 1, paInCells)
    }

    private fun fmt(v: Double, d: Int) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
}
