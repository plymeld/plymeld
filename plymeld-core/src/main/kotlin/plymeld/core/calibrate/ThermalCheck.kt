package plymeld.core.calibrate

/**
 * Reads a thermal plate's g-code back ([ThermalPlate]) and checks it against its specification, as the printer will run it.
 * Its reference is the [ThermalSpec] (its inputs and the layout it works out) and the g-code's own lines, never the generator's
 * paths; the layer times are the caller's prediction of the file as written (plymeld's own, [plymeld.core.Predict]):
 *
 *  - as many layers as the plate has, and only its cells; the first layer at the first-layer temperature, waited for, and
 *    everything above the slab at the plate's, waited for before it; the fan off on the first layer;
 *  - every line at its cell's speed, width and filament per mm (a strand's at the bridge flow);
 *  - **one base**: the slab's outline from its first layer (its front-left corner chamfered); every part standing on it starts
 *    on the first layer above the slab, inside the slab; the two gauges lie off it, on the bed, each joined to it by its two
 *    break-off tabs, which reach from the gauge across to the slab;
 *  - **the strands**: each one line at the bridge speed across its gap, from its left pier to its right one, on the layer above
 *    the piers' top, the piers' height and the gap's span as specified (both read from the piers' own lines), its row's fan
 *    duty in force and waited for (the settle) before the row's first strand, and the caps on its piers above it;
 *  - **the pins' bands**: each band's layers at its layer time, within [TIME_TOL] of it, each with a wait with the head parked off
 *    the plate; the band stair's steps topping out at each band's top;
 *  - **the fins**: each fin's single, notched band at its height (the layers where its line is single and shorter than the fin,
 *    the shortest at the band's middle), three lines below it, a single lever above to its top, its band's layers at its layer
 *    time, and its row's duty in force while each of its lines is laid; each stop post as tall as its fin;
 *  - **the compass**: eight tabs, each in its direction (the tab's lines' middle from the post's center), stepping out by the
 *    lean on every layer, its tip at the post's top; a reference post between each pair of neighbors, as tall as the tips;
 *  - **the lag wall**: on every layer its outer line laid left to right, the fan's duty switching where it passes the mark (in
 *    the lower half off to full, in the upper half full to off), the starting duty waited for; the notches in the slab's back
 *    edge from the mark on, [ThermalGeometry.lagPitch] apart, every fifth long.
 *
 * Returns the problems (empty = the plate prints as specified).
 */
object ThermalCheck {
    /** How far (s) a banded layer's time may be from its band's. */
    const val TIME_TOL = 0.1

    private class Move(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val zUm: Long, val z0Um: Long, val e: Double,
                       val fMmMin: Double, val tempC: Int?, val waited: Boolean, val fanPct: Double, val settledS: Double, val layer: Int, val widthMm: Double, val seq: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
    }
    private class Dwell(val layer: Int, val x: Double, val y: Double, val s: Double)

    fun check(spec: ThermalSpec, gcode: String, layerS: List<Double>): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 80) out += s }
        val seen = HashSet<String>()
        fun once(key: String, s: String) { if (seen.add(key)) bad(s) }
        val (moves, dwells, layers) = parse(gcode, spec.tool)
        val g = ThermalGeometry
        if (layers != spec.layers) bad("$layers layers in the g-code, not ${spec.layers} (the slab's ${g.slabLayers} and ${spec.pinTopLayers} above it)")
        val byCell = moves.groupBy { it.cell }
        val want = wantedCells(spec)
        for (c in want) if (c !in byCell) bad("no '$c' cell")
        for (c in byCell.keys) if (c !in want && !Part.isPrimeLine(c)) bad("${byCell.getValue(c).size} extrusion move(s) in '$c', which the specification does not have")
        val h = spec.layerUm
        // ---- temperatures, fan, speed, width, plastic
        for (m in moves) {
            if (Part.isPrimeLine(m.cell) || m.cell !in want) continue
            val first = m.layer == 0
            val wantC = if (first) spec.firstTempC else spec.tempC
            if (m.tempC != wantC) once("${m.cell}/temp", "'${m.cell}': at ${m.tempC} C, not $wantC C")
            else if (!m.waited && (first || m.layer >= g.slabLayers)) once("${m.cell}/wait", "'${m.cell}': no wait (M109) for $wantC C before it")
            if (first && m.fanPct > 0.5) once("${m.cell}/fan0", "'${m.cell}': fan ${fmt(m.fanPct)} % on the first layer, not off")
            val pv = valuesOf(spec, m.cell)
            if (Math.abs(m.fMmMin / 60 - pv.speed) > 1.0 / 60 + 1e-9) once("${m.cell}/v", "'${m.cell}': ${fmt(m.fMmMin / 60, 2)} mm/s, not ${fmt(pv.speed, 2)}")
            val lw = if (m.cell.startsWith("adhesion:")) spec.firstWidthUm else spec.widthUm
            if (m.widthMm.isNaN() || Math.abs(m.widthMm - lw / 1000.0) > 5e-4) once("${m.cell}/w", "'${m.cell}': declared ${fmt(m.widthMm, 3)} mm wide, not ${fmt(lw / 1000.0, 3)}")
            val wantE = spec.beadMm2(lw, spec.heightOf(m.layer)) / spec.filamentMm2 * pv.flowRatio
            if (Math.abs(m.e / m.len - wantE) > wantE * 0.01 + 2e-5 / m.len) once("${m.cell}/e", "'${m.cell}': ${fmt(m.e / m.len, 5)} mm of filament per mm, not ${fmt(wantE, 5)}")
            if (m.zUm != spec.zTop(m.layer)) once("${m.cell}/z", "'${m.cell}': a line at z ${m.zUm / 1000.0} on layer ${m.layer + 1}, whose top is ${spec.zTop(m.layer) / 1000.0}")
        }
        val base = base(spec, byCell, ::bad) ?: return out
        strands(spec, byCell, h, ::bad)
        bands(spec, byCell, dwells, layerS, ::bad)
        fins(spec, byCell, layerS, ::bad)
        compass(spec, byCell, ::bad)
        lag(spec, byCell, moves, base, ::bad)
        return out
    }

    /** The cells the plate has. */
    fun wantedCells(spec: ThermalSpec): Set<String> {
        val g = ThermalGeometry
        val w = LinkedHashSet<String>()
        w += "adhesion:base thermal"; w += "base thermal"; w += "adhesion:feeler"; w += "feeler"; w += "adhesion:card"; w += "card"
        for (n in listOf("feeler", "card")) for (j in 1..2) { w += "adhesion:break-off $n $j"; w += "break-off $n $j" }
        for (k in spec.pinDiametersUm.indices) { w += "card label ${k + 1}"; w += "pedestal ${k + 1}"; w += "pin ${k + 1}"; w += "pin ${k + 1} core" }
        for (r in spec.strandFans.indices) { w += "label strand row ${r + 1}"; for (k in spec.spansUm.indices) { w += "pier ${r + 1}.${k + 1} left"; w += "pier ${r + 1}.${k + 1} right"; w += "strand ${r + 1}.${k + 1}" } }
        for (k in spec.spansUm.indices) w += "label span ${k + 1}"
        for (k in spec.finTimesS.indices) w += "label fin ${k + 1}"
        for (r in spec.finFans.indices) { w += "label fin row ${r + 1}"; for (k in spec.finTimesS.indices) { w += "fin ${r + 1}.${k + 1}"; w += "fin post ${r + 1}.${k + 1}" } }
        w += "compass post"
        for (k in 0 until g.tabs) { w += "compass tab ${k + 1}"; w += "compass reference ${k + 1}"; w += "label direction ${k + 1}" }
        for (b in spec.pinTimesS.indices) w += "label band ${b + 1}"
        w += "stair"; w += "lag inner"; w += "lag outer"; w += "label lag speed"
        return w
    }

    /** A cell's values: a strand's the bridge's, the read surfaces' the visible wall's, the first layer's its own, the rest the frame's. */
    private fun valuesOf(spec: ThermalSpec, cell: String): PathValues = when {
        cell.startsWith("adhesion:") -> spec.adhesion
        cell.startsWith("strand ") -> spec.bridge
        cell.startsWith("fin ") && !cell.startsWith("fin post") -> spec.visible
        cell.matches(Regex("pin \\d+")) || cell == "lag outer" || cell.startsWith("compass tab") -> spec.visible
        else -> spec.frame
    }

    /** The slab from its first layer's lines (bed mm: x0, y0, x1, y1), with its chamfer checked; null when it has none. */
    private class Base(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
        /** Whether ([x], [y]) is on the slab (its chamfer cut off), within [tol]. */
        fun on(x: Double, y: Double, tol: Double, ch: Double): Boolean = x >= x0 - tol && x <= x1 + tol && y >= y0 - tol && y <= y1 + tol && (x - x0) + (y - y0) >= ch - tol
    }

    private fun base(spec: ThermalSpec, byCell: Map<String, List<Move>>, bad: (String) -> Unit): Base? {
        val g = ThermalGeometry
        val first = byCell["adhesion:base thermal"] ?: run { bad("no first layer of the slab to measure it from"); return null }
        val fw = spec.firstWidthUm / 1000.0
        val b = Base(first.minOf { minOf(it.x0, it.x1) } - fw / 2, first.minOf { minOf(it.y0, it.y1) } - fw / 2, first.maxOf { maxOf(it.x0, it.x1) } + fw / 2, first.maxOf { maxOf(it.y0, it.y1) } + fw / 2)
        if (Math.abs(b.x1 - b.x0 - spec.slabW / 1000.0) > 0.03 || Math.abs(b.y1 - b.y0 - spec.slabD / 1000.0) > 0.03)
            bad("the slab is ${fmt(b.x1 - b.x0, 2)} x ${fmt(b.y1 - b.y0, 2)} mm, not ${fmt(spec.slabW / 1000.0, 2)} x ${fmt(spec.slabD / 1000.0, 2)}")
        val corner = first.minOf { minOf(it.x0 - b.x0 + it.y0 - b.y0, it.x1 - b.x0 + it.y1 - b.y0) }
        val wantCorner = PlateMarks.CHAMFER / 1000.0 + fw / 2 * Math.sqrt(2.0)
        if (Math.abs(corner - wantCorner) > 0.05) bad("the slab's front-left corner is cut ${fmt(corner - fw / 2 * Math.sqrt(2.0), 2)} mm (by x + y from the corner), not the ${fmt(PlateMarks.CHAMFER / 1000.0, 1)} mm chamfer")
        for (l in 1 until g.slabLayers) if ((byCell["base thermal"] ?: emptyList()).none { it.layer == l }) bad("the slab has no layer ${l + 1}")
        // every part standing on the slab: its first layer the first above the slab, all of it on the slab
        val ch = PlateMarks.CHAMFER / 1000.0
        val gauges = setOf("feeler", "card")
        for ((cell, ms) in byCell) {
            val c = cell.removePrefix("adhesion:")
            // (what stands on another part: the strands on their piers, the tabs on the compass post, the pins on their pedestals)
            if (c == "base thermal" || c in gauges || c.startsWith("break-off") || c.startsWith("card label") || Part.isPrimeLine(c) || c.startsWith("strand ") ||
                c.startsWith("compass tab") || c.matches(Regex("pin \\d+( core)?"))) continue
            val low = ms.minOf { it.layer }
            if (low != g.slabLayers) bad("'$cell' starts on layer ${low + 1}, not on the first layer above the slab (${g.slabLayers + 1}): it does not stand on the slab")
            val off = ms.firstOrNull { m -> !b.on(m.x0, m.y0, spec.widthUm / 2000.0 + 1e-3, ch) || !b.on(m.x1, m.y1, spec.widthUm / 2000.0 + 1e-3, ch) }
            if (off != null) bad("'$cell' is not on the slab: a line from (${fmt(off.x0, 2)}, ${fmt(off.y0, 2)}) to (${fmt(off.x1, 2)}, ${fmt(off.y1, 2)}) lies off it (${fmt(b.x0, 2)}..${fmt(b.x1, 2)} x ${fmt(b.y0, 2)}..${fmt(b.y1, 2)})")
        }
        // the gauges off the slab, on the bed, each joined to it by its two break-off tabs
        for (gauge in gauges) {
            val ms = (byCell[gauge] ?: emptyList()) + (byCell["adhesion:$gauge"] ?: emptyList())
            if (ms.isEmpty()) continue
            if (ms.minOf { it.layer } != 0) bad("the $gauge does not start on the bed")
            val gy1 = ms.maxOf { maxOf(it.y0, it.y1) } + spec.widthUm / 2000.0
            if (gy1 > b.y0 + 1e-6) bad("the $gauge reaches onto the slab (to y ${fmt(gy1, 2)}; the slab starts at ${fmt(b.y0, 2)})")
            val gx0 = ms.minOf { minOf(it.x0, it.x1) }; val gx1 = ms.maxOf { maxOf(it.x0, it.x1) }
            var joints = 0
            for (j in 1..2) {
                val t = (byCell["adhesion:break-off $gauge $j"] ?: emptyList()) + (byCell["break-off $gauge $j"] ?: emptyList())
                if (t.isEmpty()) continue
                val ty0 = t.minOf { minOf(it.y0, it.y1) }; val ty1 = t.maxOf { maxOf(it.y0, it.y1) }
                val tx = t.map { (it.x0 + it.x1) / 2 }.average()
                if (ty0 <= gy1 - spec.widthUm / 2000.0 && ty1 >= b.y0 + spec.widthUm / 2000.0 && tx in gx0..gx1 && t.minOf { it.layer } == 0) joints++
            }
            if (joints != 2) bad("the $gauge is joined to the slab by $joints break-off tab(s), not 2 (each reaching from the $gauge across to the slab, from the bed up)")
        }
        return b
    }

    private fun strands(spec: ThermalSpec, byCell: Map<String, List<Move>>, h: Long, bad: (String) -> Unit) {
        val w = spec.widthUm / 1000.0
        for ((r, duty) in spec.strandFans.withIndex()) {
            var firstOfRow = true
            for (k in spec.spansUm.indices) {
                val name = "strand ${r + 1}.${k + 1}"
                val s = byCell[name] ?: continue
                val left = byCell["pier ${r + 1}.${k + 1} left"] ?: continue; val right = byCell["pier ${r + 1}.${k + 1} right"] ?: continue
                val z = s.first().zUm
                if (s.any { it.zUm != z }) bad("$name: not on one layer")
                if (s.any { Math.abs(it.y1 - it.y0) > 1e-6 }) bad("$name: not one straight line along its row")
                // the piers' own top: their last layer under the strand
                val leftTop = left.filter { it.zUm < z }.maxOfOrNull { it.zUm }; val rightTop = right.filter { it.zUm < z }.maxOfOrNull { it.zUm }
                val wantZ = spec.slabTop + (spec.pierLayers + 1) * h
                if (leftTop == null || rightTop == null || leftTop != rightTop || z != leftTop + h || z != wantZ)
                    bad("$name: laid at z ${z / 1000.0}, not on the layer above its piers' top (z ${wantZ / 1000.0}: the piers ${spec.pierLayers * h / 1000.0} mm above the slab; their lines below it end at ${leftTop?.let { it / 1000.0 }}, ${rightTop?.let { it / 1000.0 }})")
                val pierUm = (leftTop ?: 0L) - spec.slabTop
                if (Math.abs(pierUm - spec.pierLayers * h) > h / 2) bad("$name: its piers stand ${pierUm / 1000.0} mm above the slab, not ${spec.pierLayers * h / 1000.0}")
                // the span between the piers' inner faces, from their lines below the strand
                val lx1 = left.filter { it.zUm < z }.maxOf { maxOf(it.x0, it.x1) } + w / 2
                val rx0 = right.filter { it.zUm < z }.minOf { minOf(it.x0, it.x1) } - w / 2
                val lx0 = left.filter { it.zUm < z }.minOf { minOf(it.x0, it.x1) } - w / 2
                val rx1 = right.filter { it.zUm < z }.maxOf { maxOf(it.x0, it.x1) } + w / 2
                if (Math.abs(rx0 - lx1 - spec.spansUm[k] / 1000.0) > 0.05) bad("$name: its piers are ${fmt(rx0 - lx1, 2)} mm apart, not the span ${fmt(spec.spansUm[k] / 1000.0, 1)}")
                val sx0 = s.minOf { minOf(it.x0, it.x1) }; val sx1 = s.maxOf { maxOf(it.x0, it.x1) }
                if (sx0 > lx1 - w || sx0 < lx0 || sx1 < rx0 + w || sx1 > rx1) bad("$name: from x ${fmt(sx0, 2)} to ${fmt(sx1, 2)}, not from its left pier (${fmt(lx0, 2)}..${fmt(lx1, 2)}) across the gap onto its right pier (${fmt(rx0, 2)}..${fmt(rx1, 2)})")
                // its row's duty, waited for before the row
                if (s.any { Math.abs(it.fanPct - duty) > 0.5 }) bad("$name: fan ${fmt(s.first().fanPct)} %, not its row's ${fmt(duty)} %")
                else if (firstOfRow && spec.settleS > 0 && s.first().settledS < spec.settleS - 1e-6) bad("$name: the fan's ${fmt(duty)} % was waited for ${fmt(s.first().settledS, 1)} s before the row, not the settle's ${fmt(spec.settleS, 1)} s")
                firstOfRow = false
                // the caps over its ends
                for ((pn, ps) in listOf("left" to left, "right" to right)) for (c in 1..ThermalGeometry.capLayers)
                    if (ps.none { it.zUm == z + c * h }) bad("$name: no cap layer ${c} on its $pn pier (z ${(z + c * h) / 1000.0})")
            }
        }
    }

    private fun bands(spec: ThermalSpec, byCell: Map<String, List<Move>>, dwells: List<Dwell>, layerS: List<Double>, bad: (String) -> Unit) {
        // each pin on its pedestal: its first layer the one above the pedestal's top, all of it within the pedestal
        for (k in spec.pinDiametersUm.indices) {
            val ped = byCell["pedestal ${k + 1}"] ?: continue
            val pin = (byCell["pin ${k + 1}"] ?: emptyList()) + (byCell["pin ${k + 1} core"] ?: emptyList())
            if (pin.isEmpty()) continue
            val pedTop = ped.maxOf { it.layer }
            val px0 = ped.minOf { minOf(it.x0, it.x1) }; val px1 = ped.maxOf { maxOf(it.x0, it.x1) }; val py0 = ped.minOf { minOf(it.y0, it.y1) }; val py1 = ped.maxOf { maxOf(it.y0, it.y1) }
            if (pin.minOf { it.layer } != pedTop + 1 || pin.any { minOf(it.x0, it.x1) < px0 || maxOf(it.x0, it.x1) > px1 || minOf(it.y0, it.y1) < py0 || maxOf(it.y0, it.y1) > py1 })
                bad("pin ${k + 1} does not stand on its pedestal (the pedestal's top on layer ${pedTop + 1}; the pin from layer ${pin.minOf { it.layer } + 1})")
            if (Math.abs(spec.above(pedTop) - spec.pedestalLayers * spec.layerUm) > spec.layerUm / 2) bad("pedestal ${k + 1} is ${spec.above(pedTop) / 1000.0} mm tall above the slab, not ${spec.pedestalLayers * spec.layerUm / 1000.0}")
        }
        for ((bi, t) in spec.pinTimesS.withIndex()) for (i in spec.pinBandRange(bi)) {
            val l = spec.plateLayer(i)
            if (l >= spec.layers - 1) continue
            if (spec.pinDiametersUm.indices.any { k -> byCell["pin ${k + 1}"]?.none { it.layer == l } != false }) bad("pin band ${bi + 1}: layer ${l + 1} does not print every pin")
            val got = layerS.getOrNull(l)
            if (got == null || Math.abs(got - t) > TIME_TOL) bad("pin band ${bi + 1}: layer ${l + 1} takes ${got?.let { fmt(it, 2) } ?: "?"} s, not its ${fmt(t, 1)} s (within ${fmt(TIME_TOL, 1)} s)")
            parked(spec, dwells, l, "pin band ${bi + 1}", bad)
        }
        // the stair: its steps' tops at the bands' tops
        val st = byCell["stair"] ?: return
        for (bi in spec.pinTimesS.indices) {
            val box = spec.stairBox(bi)
            val y = (box.y0 + box.y1) / 2 / 1000.0 + spec.dy / 1000.0
            val top = st.filter { minOf(it.y0, it.y1) <= y && maxOf(it.y0, it.y1) >= y }.maxOfOrNull { it.zUm }
            val wantTop = spec.slabTop + spec.pinBandTop(bi)
            if (top != wantTop) bad("the band stair's step ${bi + 1} tops out at z ${top?.let { it / 1000.0 }}, not at band ${bi + 1}'s top ${wantTop / 1000.0}")
        }
    }

    /** A banded layer [l] waits, parked off the plate. */
    private fun parked(spec: ThermalSpec, dwells: List<Dwell>, l: Int, what: String, bad: (String) -> Unit) {
        val ds = dwells.filter { it.layer == l && it.s > 0 }
        if (ds.isEmpty()) { bad("$what: layer ${l + 1} has no wait"); return }
        val pb = spec.bounds
        val x0 = (pb.x0 + spec.dx) / 1000.0; val y0 = (pb.y0 + spec.dy) / 1000.0; val x1 = (pb.x1 + spec.dx) / 1000.0; val y1 = (pb.y1 + spec.dy) / 1000.0
        val over = ds.firstOrNull { it.x >= x0 && it.x <= x1 && it.y >= y0 && it.y <= y1 }
        if (over != null) bad("$what: layer ${l + 1} waits with the head at (${fmt(over.x, 1)}, ${fmt(over.y, 1)}), over the plate")
    }

    private fun fins(spec: ThermalSpec, byCell: Map<String, List<Move>>, layerS: List<Double>, bad: (String) -> Unit) {
        val w = spec.widthUm / 1000.0
        val full = ThermalGeometry.finWidth / 1000.0 - w
        for ((r, duty) in spec.finFans.withIndex()) for ((k, t) in spec.finTimesS.withIndex()) {
            val name = "fin ${r + 1}.${k + 1}"
            val ms = byCell[name] ?: continue
            if (ms.any { Math.abs(it.fanPct - duty) > 0.5 }) bad("$name: a line at fan ${fmt(ms.first { Math.abs(it.fanPct - duty) > 0.5 }.fanPct)} %, not its row's ${fmt(duty)} %")
            val byLayer = ms.groupBy { it.layer }.toSortedMap()
            // per layer: how many lines across (distinct y), and the longest line's length
            val kind = byLayer.mapValues { (_, ls) -> ls.map { Math.round(it.y0 * 1000) }.distinct().size to ls.filter { Math.abs(it.y1 - it.y0) < 1e-6 }.maxOf { it.len } }
            val single = kind.filter { it.value.first == 1 }.keys
            val notched = kind.filter { it.value.first == 1 && it.value.second < full - 0.05 }.keys
            val band = spec.finBandRange(k).map { spec.plateLayer(it) }
            if (notched.isEmpty()) { bad("$name: no notched band (no layer whose single line is shorter than the fin)"); continue }
            if (notched.min() < band.first() || notched.max() > band.last() || single.min() != band.first()) bad("$name: its band is on layers ${single.min() + 1}..${notched.max() + 1} (notched ${notched.min() + 1}..${notched.max() + 1}), not ${band.first() + 1}..${band.last() + 1} (${fmt((spec.above(band.first()) - spec.layerUm) / 1000.0, 2)} mm above the slab)")
            if (kind.filter { it.key < single.min() }.any { it.value.first != 3 }) bad("$name: not three lines thick below its band")
            val top = byLayer.lastKey()
            if (top != spec.plateLayer(spec.finTopLayer(k))) bad("$name: its top is on layer ${top + 1}, not ${spec.plateLayer(spec.finTopLayer(k)) + 1} (a lever of ${fmt(ThermalGeometry.lever / 1000.0, 1)} mm above its band)")
            if ((single.min()..top).any { it !in single }) bad("$name: not single above its band")
            val post = byCell["fin post ${r + 1}.${k + 1}"]
            if (post == null || post.maxOf { it.layer } != top) bad("$name: its stop post's top is not at the fin's top")
            if (r == 0) for (l in band) {
                val got = layerS.getOrNull(l)
                if (got == null || Math.abs(got - t) > TIME_TOL) bad("fin ${k + 1}'s band: layer ${l + 1} takes ${got?.let { fmt(it, 2) } ?: "?"} s, not its ${fmt(t, 1)} s (within ${fmt(TIME_TOL, 1)} s)")
            }
        }
    }

    private fun compass(spec: ThermalSpec, byCell: Map<String, List<Move>>, bad: (String) -> Unit) {
        val g = ThermalGeometry
        val post = byCell["compass post"] ?: return
        val cx = (post.minOf { minOf(it.x0, it.x1) } + post.maxOf { maxOf(it.x0, it.x1) }) / 2
        val cy = (post.minOf { minOf(it.y0, it.y1) } + post.maxOf { maxOf(it.y0, it.y1) }) / 2
        val postTop = post.maxOf { it.zUm }
        val tabs = (1..g.tabs).count { byCell["compass tab $it"] != null }
        if (tabs != g.tabs) bad("the compass has $tabs tab(s), not ${g.tabs}")
        val step = Math.tan(Math.toRadians(g.tabLeanDeg)) * spec.layerUm / 1000.0
        for (k in 0 until g.tabs) {
            val ms = byCell["compass tab ${k + 1}"] ?: continue
            val byLayer = ms.groupBy { it.layer }.toSortedMap()
            val mids = byLayer.mapValues { (_, ls) -> (ls.sumOf { (it.x0 + it.x1) / 2 * it.len } / ls.sumOf { it.len }) to (ls.sumOf { (it.y0 + it.y1) / 2 * it.len } / ls.sumOf { it.len }) }
            val (mx, my) = mids.getValue(byLayer.lastKey())
            val a = Math.toDegrees(Math.atan2(my - cy, mx - cx)); val want = Math.toDegrees(spec.tabAngle(k))
            val d = ((a - want) % 360 + 540) % 360 - 180
            if (Math.abs(d) > 5) bad("compass tab ${k + 1}: points at ${fmt(a)} degrees, not ${fmt(((want % 360) + 360) % 360)} (direction ${k + 1}: 1 toward the front, clockwise from above)")
            val rs = mids.values.map { (x, y) -> Math.hypot(x - cx, y - cy) }
            val steps = rs.zipWithNext { p, q -> q - p }
            if (steps.any { Math.abs(it - step) > 0.03 }) bad("compass tab ${k + 1}: steps out ${fmt(steps.min(), 3)}..${fmt(steps.max(), 3)} mm a layer, not ${fmt(step, 3)} (${fmt(g.tabLeanDeg)} degrees from vertical)")
            if (byLayer.lastKey() != post.maxOf { it.layer }) bad("compass tab ${k + 1}: its tip is not at the post's top")
            // its root against the post's outer wall: its first layer's inner line a line's spacing out from it
            val postR = (post.maxOf { maxOf(it.x0, it.x1) } - post.minOf { minOf(it.x0, it.x1) }) / 2
            val root = byLayer.getValue(byLayer.firstKey()).minOf { Math.hypot((it.x0 + it.x1) / 2 - cx, (it.y0 + it.y1) / 2 - cy) }
            if (root > postR + spec.spacingUm / 1000.0 + 0.05) bad("compass tab ${k + 1}: its first layer is ${fmt(root - postR, 2)} mm out from the post's outer wall, not against it")
        }
        for (k in 0 until g.tabs) {
            val ms = byCell["compass reference ${k + 1}"] ?: continue
            if (ms.maxOf { it.zUm } != postTop) bad("compass reference ${k + 1}: its top at z ${ms.maxOf { it.zUm } / 1000.0}, not the tips' ${postTop / 1000.0}")
            val rx = ms.map { (it.x0 + it.x1) / 2 }.average(); val ry = ms.map { (it.y0 + it.y1) / 2 }.average()
            val a = Math.toDegrees(Math.atan2(ry - cy, rx - cx)); val want = Math.toDegrees(spec.tabAngle(k) - Math.PI / g.tabs)
            if (Math.abs(((a - want) % 360 + 540) % 360 - 180) > 5) bad("compass reference ${k + 1}: not between tabs ${k + 1} and ${(k + 1) % g.tabs + 1}")
        }
    }

    private fun lag(spec: ThermalSpec, byCell: Map<String, List<Move>>, moves: List<Move>, b: Base, bad: (String) -> Unit) {
        val g = ThermalGeometry
        val outer = byCell["lag outer"] ?: return
        val mark = b.x0 + (spec.lagX0 + g.lagSwitch) / 1000.0
        for ((l, ms) in outer.groupBy { it.layer }.toSortedMap()) {
            val i = l - g.slabLayers
            val lower = i < spec.lagHalf
            val (from, to) = if (lower) 0.0 to g.FULL else g.FULL to 0.0
            val what = "the lag wall, layer ${l + 1} (${if (lower) "lower" else "upper"} half)"
            if (ms.any { it.x1 < it.x0 - 1e-6 }) { bad("$what: its outer line is laid right to left, not left to right"); continue }
            val before = ms.filter { Math.abs(it.fanPct - from) < 0.5 }; val after = ms.filter { Math.abs(it.fanPct - to) < 0.5 }
            if (before.size + after.size != ms.size || before.isEmpty() || after.isEmpty()) { bad("$what: the fan is not ${fmt(from)} % then ${fmt(to)} %"); continue }
            val sw = before.maxOf { it.x1 }
            if (after.minOf { it.x0 } < sw - 1e-6 || Math.abs(sw - mark) > 0.05) bad("$what: the fan switches at x ${fmt(sw, 2)}, not at the mark (${fmt(mark, 2)}, ${fmt(g.lagSwitch / 1000.0, 0)} mm along)")
            if (spec.settleS > 0 && before.first().settledS < spec.settleS - 1e-6) bad("$what: its starting duty, ${fmt(from)} %, was waited for ${fmt(before.first().settledS, 1)} s, not the settle's ${fmt(spec.settleS, 1)} s")
        }
        // the notches in the slab's back edge: from the slab's lines above its first layer, the gaps in its back face
        val w = spec.widthUm / 1000.0
        val slab = moves.filter { it.cell == "base thermal" && it.layer == 1 }
        val back = b.y1 - w / 2
        val face = slab.filter { Math.abs(it.y0 - back) < 0.03 && Math.abs(it.y1 - back) < 0.03 }.map { minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) }.sortedBy { it.first }
        val gaps = ArrayList<Pair<Double, Double>>()
        if (face.isNotEmpty()) { var reach = face.first().second; for ((a, c) in face.drop(1)) { if (a > reach + 0.05) gaps += reach to a; reach = maxOf(reach, c) } }
        if (gaps.size != spec.lagNotches) bad("${gaps.size} notch(es) in the slab's back edge, not ${spec.lagNotches} (from the lag wall's mark, every ${fmt(g.lagPitch / 1000.0, 0)} mm)")
        else for ((k, gp) in gaps.withIndex()) {
            val mid = (gp.first + gp.second) / 2
            val wantX = b.x0 + spec.lagNotchX(k) / 1000.0
            if (Math.abs(mid - wantX) > 0.05) bad("lag notch ${k + 1} is at x ${fmt(mid, 2)}, not ${fmt(wantX, 2)} (${fmt((spec.lagNotchX(k) - spec.lagX0) / 1000.0, 0)} mm along the wall)")
            val deep = slab.filter { Math.abs(it.y1 - it.y0) < 1e-6 && minOf(it.x0, it.x1) <= mid + 1e-6 && maxOf(it.x0, it.x1) >= mid - 1e-6 && it.y0 < back - 0.05 }.minOfOrNull { back - it.y0 }
            val wantIn = PlateMarks.depth(k) / 1000.0
            if (deep == null || Math.abs(deep - wantIn) > 0.05) bad("lag notch ${k + 1} is ${deep?.let { fmt(it, 2) } ?: "?"} mm deep, not ${fmt(wantIn, 1)} (${if ((k + 1) % PlateMarks.TALLY_LONG_EVERY == 0) "long" else "short"})")
        }
    }

    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Extrusion moves inside cells with the state they ran in (the nozzle target and whether a wait to it came after it was set,
     * the fan and how long the head has waited since it last changed), the waits (G4) with where the head was, and the number of
     * layers. Extrusion outside every cell between the plate's first line and its last is returned under "(none)". Relative
     * extrusion (M83).
     */
    private fun parse(gcode: String, tool: Int): Triple<List<Move>, List<Dwell>, Int> {
        val inCells = ArrayList<Move>(); val outside = ArrayList<Pair<Int, Move>>(); val dwells = ArrayList<Dwell>()
        var cellsClosed = 0; var cell: String? = null
        var x = Double.NaN; var y = Double.NaN; var zUm = 0L
        var f = 0.0; var fan = 0.0; var settled = 0.0
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
                        val mv = Move(cell ?: "(none)", x, y, nx, ny, zUm, z0, e, f, target[tool], waited[tool] == true, fan, settled, layer, width, seq)
                        if (cell != null) inCells += mv else outside += cellsClosed to mv
                    }
                    x = nx; y = ny
                }
                "G4" -> { val s = (num('P')?.div(1000.0) ?: 0.0) + (num('S') ?: 0.0); settled += s; dwells += Dwell(layer, x, y, s) }
                "M104" -> { val tt = num('T')?.toInt() ?: curTool; num('S')?.toInt()?.let { if (target[tt] != it) waited[tt] = false; target[tt] = it } }
                "M109" -> { val tt = num('T')?.toInt() ?: curTool; num('S')?.toInt()?.let { target[tt] = it; waited[tt] = true } }
                "M106" -> if (num('P') == null) { val v = (num('S') ?: 255.0) / 255 * 100; if (Math.abs(v - fan) > 1e-9) settled = 0.0; fan = v }
                "M107" -> { if (fan != 0.0) settled = 0.0; fan = 0.0 }
                else -> if (words[0].length >= 2 && words[0][0] == 'T' && words[0].substring(1).all { it.isDigit() }) curTool = words[0].substring(1).toInt()
            }
        }
        return Triple(inCells + outside.filter { (closed, _) -> closed in 1 until cellsClosed }.map { it.second }, dwells, layer + 1)
    }

    private fun fmt(v: Double, d: Int = 0) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
}
