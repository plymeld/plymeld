package plymeld.core.calibrate

/**
 * Reads a calibration plate's g-code back and checks every cell against the plate's specification
 *: the cells each layer should have are
 * there, and each extrusion move's speed, acceleration, flow ratio, line width (as declared, and by its filament per
 * mm), temperature and part fan are the specification's. Its reference is the [CalibrationSpec], not the program the writer was given; it
 * parses the text the printer will run. Returns the problems (empty = the plate prints as specified).
 */
object CalibrationCheck {
    /**
     * An extrusion move; [widthMm]: the line width the g-code declares for its path (`; LINE_WIDTH:` / `;WIDTH:`), NaN if none;
     * [tempC]: the nozzle temperature last set (M104 or M109); [reachedC]: the temperature last waited for (M109), null once a
     * later M104 set another (so the nozzle may not be there yet).
     */
    private class Move(val cell: String, val zUm: Long, val lenMm: Double, val e: Double, val fMmMin: Double, val accel: Double, val tempC: Int?, val fanPct: Double, val layer: Int, val widthMm: Double,
                       val reachedC: Int? = null, val layerZUm: Long = zUm)

    /**
     * The melt plate's read layer (0-based) for band [b], by the plate's own figures, not the specification's bands:
     * the bases, band 1's body [PlateGeometry.meltFirstBody] in whole layers ([PlateGeometry.meltBodyLayers]) and its read
     * layer; each later band two layers higher (a settling layer after its wait, then its read layer).
     */
    private fun meltReadLayerByRule(spec: CalibrationSpec, b: Int): Int = spec.baseLayers + PlateGeometry.meltBodyLayers(spec.layerUm) + 2 * b

    /**
     * The width (µm) a cell's lines are on the layer whose top is [zUm], by the specification: each tube's own on every layer
     * (their widths are the test); the melt strips the plate's own (now they vary by thickness, not width); every
     * other line the first layer's width on the first layer, the plate's own above it.
     */
    private fun widthUm(spec: CalibrationSpec, cell: String, zUm: Long): Long {
        val c = cell.removePrefix("adhesion:")
        return when {
            meltStrip(c) != null -> spec.widthUm
            c.startsWith("tube ") -> spec.tubes!!.widthsUm[c.removePrefix("tube ").toInt() - 1]
            zUm == spec.layerUm -> spec.firstWidthUm
            else -> spec.widthUm
        }
    }

    /**
     * Melt strip [k]'s thickness (µm), worked out here from the tool's layer.height.max and the bore (the decision), not
     * from the generator's list: six strips, the thickest at the tool's layer.height.max, each next one in front a tenth of the bore
     * thinner.
     */
    private fun stripHeightUm(spec: CalibrationSpec, k: Int): Long = spec.melt!!.let { mt -> mt.maxHeightUm - Math.round((5 - k) * mt.boreUm / 10.0) }

    /** A melt strip's cell label ("melt 2.mid.3") as its band, surface, and strip (0-based), or null (not a strip). */
    private fun meltStrip(cell: String): IntArray? {
        val m = Regex("melt (\\d+)\\.(fast|mid|slow)\\.(\\d+)").matchEntire(cell.removePrefix("adhesion:")) ?: return null
        return intArrayOf(m.groupValues[1].toInt() - 1, MeltTerraces.SURFACES.indexOf(m.groupValues[2]), m.groupValues[3].toInt() - 1)
    }

    /** The plate's ordinary line width (mm) on the layer whose top is [zUm]: the first layer's on the first layer. */
    private fun lineW(spec: CalibrationSpec, zUm: Long) = (if (zUm == spec.layerUm) spec.firstWidthUm else spec.widthUm) / 1000.0

    fun check(spec: CalibrationSpec, given: String): List<String> {
        // the file's layers are the design's shifted up by the first layer's extra thickness (CalibrationPlate.shifted): read
        // back with every Z lowered by it, so each checker below finds the design's layers (the first layer's own thickness
        // stays in its filament per mm, expected at CalibrationSpec.firstLayerUm)
        val gcode = unshift(given, spec.firstLayerUm - spec.layerUm)
        val moves = parse(gcode, spec.tool)
        val problems = ArrayList<String>()
        fun bad(s: String) { if (problems.size < 50) problems += s }
        // by layer: every move at its own height but the melt strips, which stand on their floor at their own thickness
        // and belong to the layer they print on (their heights are [meltGeometry]'s)
        val byLayer = moves.groupBy { if (meltStrip(it.cell) != null) it.layerZUm else it.zUm }
        val layers = (spec.topUm / spec.layerUm).toInt()
        for ((z, ms) in byLayer) if (z % spec.layerUm != 0L || z <= 0 || z > spec.topUm) bad("${ms.size} extrusion move(s) at z ${z / 1000.0}, which is no layer of the plate (cells ${ms.map { it.cell }.distinct().joinToString()})")
        // the bands start above the bases (their first read layer on top of them)
        if (spec.bands.first().fromUm < spec.baseLayers * spec.layerUm) bad("band 1 starts at z ${spec.bands.first().fromUm / 1000.0}, inside the ${spec.baseLayers}-layer bases")
        for (layer in 0 until layers) {
            val z = (layer + 1) * spec.layerUm
            val here = byLayer[z].orEmpty()
            val want = buildList {
                if (spec.ruler) add(if (layer == 0) "adhesion:post" else "post")
                // the bases' layers: the post and every family's base, nothing of the tests
                if (layer < spec.baseLayers) { for (p in spec.parts) if (p != Part.POST) add((if (layer == 0) "adhesion:" else "") + "base ${p.key}"); return@buildList }
                // the melt test: each step's body below its floor, the floor directly under its read layer, its strips on
                // that, nothing above
                spec.melt?.let { mt -> for (b in spec.bands.indices) for (s in 0 until 3) {
                    val read = spec.meltReadLayer(b)
                    if (layer < read - 1) add((if (layer == 0) "adhesion:" else "") + mt.bodyLabel(b, s))
                    else if (layer == read - 1) add((if (layer == 0) "adhesion:" else "") + mt.floorLabel(b, s))
                    else if (layer == read) for (k in 0 until mt.count) add(mt.label(b, s, k))
                } }
                val f = spec.flow
                if (f != null) {
                    val pre = if (layer == 0) "adhesion:" else ""
                    // a row per band: its patches solid up to its top, on their pedestals below
                    for (r in f.fromLayers.indices) {
                        if (layer >= f.fromLayers[r] && layer <= f.topLayer(r)) for (k in f.ratios.indices) add(pre + f.label(r, k))
                        else if (layer < f.fromLayers[r]) for (k in f.ratios.indices) add(pre + f.pedestalLabel(r, k))
                    }
                    if (layer - spec.baseLayers <= f.layers && f.marked in f.ratios.indices) add("${pre}mark")
                }
                if (spec.ledges != null) add(if (layer == 0) "adhesion:ledges" else "ledges")
                spec.strings?.let { st -> for (j in 1..st.posts) add((if (layer == 0) "adhesion:" else "") + "post $j") }
                spec.tubes?.let { tb -> if (layer < tb.layers) for (k in tb.widthsUm.indices) add((if (layer == 0) "adhesion:" else "") + "tube ${k + 1}") }
                spec.fit?.let { f ->
                    if (layer >= f.fromLayer && layer < f.fromLayer + f.blockLayers) add((if (layer == 0) "adhesion:" else "") + "fit block")
                    else if (layer < f.fromLayer) add((if (layer == 0) "adhesion:" else "") + "pedestal")
                    if (layer < f.pegLayers) add((if (layer == 0) "adhesion:" else "") + "peg")
                }
                if (spec.bridges != null) {
                    add(if (layer == 0) "adhesion:pier" else "pier")
                    if (layer > 0 && spec.bands.any { it.readToUm == z }) { add("deck 1"); add("deck 2") }
                }
            }
            val have = here.map { it.cell }.distinct()
            for (c in want) if (c !in have) bad("layer $layer (z ${z / 1000.0}): no '$c' cell")
            // the writer's prime line on the first layer is no cell of the plate's (PrimeLine; its own rules place it)
            for (c in have) if (c !in want && !(layer == 0 && Part.isPrimeLine(c))) bad("layer $layer (z ${z / 1000.0}): a '$c' cell the specification does not have")
            val band = spec.readBand(z)
            for (mv in here) {
                if (mv.cell !in want) continue      // reported above
                // temperature and fan: band 0's temperature and the fan off on the adhesion layer; the band's own in its read part
                if (layer == 0 || layer < spec.baseLayers) {
                    // the adhesion layer and the bases: band 1's temperature, the fan off
                    if (mv.tempC != spec.bands[0].tempC) bad("layer $layer: '${mv.cell}' at ${mv.tempC} C, not ${spec.bands[0].tempC} C"); if (mv.fanPct != 0.0) bad("layer $layer: fan ${fmt(mv.fanPct)} %, not off")
                } else if (band != null) {
                    if (mv.tempC != band.tempC) bad("band ${band.index} (z ${z / 1000.0}): '${mv.cell}' at ${mv.tempC} C, not ${band.tempC} C")
                    if (Math.abs(mv.fanPct - band.fanPct) > 0.5) bad("band ${band.index} (z ${z / 1000.0}): fan ${fmt(mv.fanPct)} %, not ${fmt(band.fanPct)} %")
                }
                // printed only once its temperature has been reached: a wait (M109) for it came after it was set (
                // on the melt plate a band's settling and read layers come after its wait). Settling layers on the filament
                // plate print while the temperature changes: not read, not checked
                val wantC = if (layer == 0 || layer < spec.baseLayers) spec.bands[0].tempC else band?.tempC
                if (wantC != null && mv.tempC == wantC && mv.reachedC != wantC)
                    bad("z ${z / 1000.0} '${mv.cell}': printed before its temperature, $wantC C, was reached (no wait, M109, for it since it was set)")
                // flow ratio: filament per mm against the bead model
                val pv = values(spec, mv.cell)
                // its width: each melt strip's and each tube's own on every layer, the rest the first layer's on the first
                // layer; filament per mm the bead model's at that width and the layer's height
                val wantW = widthUm(spec, mv.cell, z)
                // its thickness: a melt strip's own, the first layer's on the first layer (CalibrationSpec.firstLayerUm), every other line the layer's
                val wantH = meltStrip(mv.cell)?.let { stripHeightUm(spec, it[2]) } ?: (if (z == spec.layerUm) spec.firstLayerUm else spec.layerUm)
                val expectE = MeltTerraces.bead(wantW, wantH) / spec.filamentMm2 * pv.flowRatio
                if (Math.abs(mv.e / mv.lenMm - expectE) > expectE * 0.01 + 2e-5 / mv.lenMm) bad("z ${z / 1000.0} '${mv.cell}': ${fmt(mv.e / mv.lenMm, 5)} mm of filament per mm, not ${fmt(expectE, 5)} (a ${wantW / 1000.0} mm line ${wantH / 1000.0} mm thick, flow ratio ${pv.flowRatio})")
                if (Math.abs(mv.widthMm - wantW / 1000.0) > 5e-4 || mv.widthMm.isNaN()) bad("z ${z / 1000.0} '${mv.cell}': declared ${fmt(mv.widthMm, 3)} mm wide, not ${fmt(wantW / 1000.0, 3)}")
                if (Math.abs(mv.accel - pv.accel) > 0.5) bad("z ${z / 1000.0} '${mv.cell}': acceleration ${fmt(mv.accel)}, not ${fmt(pv.accel)}")
                if (!pv.speed.isNaN() && Math.abs(mv.fMmMin / 60 - pv.speed) > 1.0 / 60 + 1e-9) bad("z ${z / 1000.0} '${mv.cell}': ${fmt(mv.fMmMin / 60, 2)} mm/s, not ${fmt(pv.speed, 2)}")
            }
        }
        // every layer at least the minimum layer time: its moves at their feed rates (a lower bound) and its pause
        if (spec.minLayerTimeS > 0) {
            val times = layerTimes(gcode)
            val short = times.withIndex().filter { it.value < spec.minLayerTimeS - 0.05 }
            if (short.isNotEmpty()) bad("${short.size} layer(s) shorter than the minimum layer time ${fmt(spec.minLayerTimeS, 1)} s, e.g. layer ${short.first().index} at ${fmt(short.first().value, 1)} s")
        }
        return problems + geometry(spec, gcode) + baseFill(spec, gcode) + ringRetractions(spec, gcode)
    }

    /** Each layer's time (s) by the g-code: every move at its feed rate (accelerations left out: a lower bound) plus its dwells. */
    fun layerTimes(gcode: String): List<Double> {
        val out = ArrayList<Double>()
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var f = 0.0
        for (raw in gcode.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { out += 0.0; continue }
            val c = l.substringBefore(';').trim(); if (c.isEmpty()) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            when (ws[0]) {
                "G0", "G1" -> {
                    num('F')?.let { f = it }
                    val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z
                    if (out.isNotEmpty() && !x.isNaN() && f > 0) {
                        val d = Math.sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y) + (if (z.isNaN()) 0.0 else (nz - z) * (nz - z)))
                        out[out.size - 1] += d / (f / 60)
                    }
                    x = nx; y = ny; z = nz
                }
                "G4" -> if (out.isNotEmpty()) out[out.size - 1] += (num('P') ?: 0.0) / 1000 + (num('S') ?: 0.0)
            }
        }
        return out
    }

    /**
     * The plate's geometry as the g-code lays it, against [PlateGeometry] and the specification:
     *  - the post's notches (where the plate has a post): a gap in its front face exactly on each band's notch layers, as
     *    wide as the notch plus a line (the outer ring steps round it), short or long by band; no gap elsewhere;
     *  - the parts (the post and each family of tests, packed): every part's lines on every layer, widened by half
     *    their declared width, at least [PlateGeometry.familyGap] from every other part's;
     *  - the terraced melt test ([meltGeometry]);
     *  - the bases, the flow patches, the ledges, the bridges, the stringing ring, the tubes, the fit test
     *    (each its own function, below).
     */
    fun geometry(spec: CalibrationSpec, gcode: String): List<String> {
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 30) out += s }
        // [wd]: the line width the g-code declares for the segment's path
        class S(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val flow: Double, val speed: Double, val wd: Double) { val len get() = Math.hypot(x1 - x0, y1 - y0) }
        val byZ = LinkedHashMap<Long, MutableList<S>>()
        run {
            var cell: String? = null; var x = Double.NaN; var y = Double.NaN; var z = 0.0; var f = 0.0; var wd = Double.NaN
            for (raw in gcode.lineSequence()) {
                val l = raw.trim()
                if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); wd = Double.NaN; continue }
                if (l == "; CELL END") { cell = null; continue }
                if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
                if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").trim().toDouble(); continue }
                val c = l.substringBefore(';').trim(); if (!c.startsWith("G1")) continue
                val ws = c.split(Regex("\\s+"))
                fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
                num('F')?.let { f = it }; num('Z')?.let { z = it }
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                val cl = cell
                if (cl != null && e > 0 && !x.isNaN() && (nx != x || ny != y)) {
                    val len = Math.hypot(nx - x, ny - y)
                    byZ.getOrPut(Math.round(z * 1000)) { ArrayList() } += S(cl, x, y, nx, ny, f / 60 * e / len * spec.filamentMm2, f / 60, wd)
                }
                x = nx; y = ny
            }
        }
        // the parts packed: every part's lines, on every layer, widened by half their width, at least the families' gap
        // from every other part's
        // the extent of every part (bases included), of every cell (by its label, the adhesion layer's with the rest), and of
        // every part's test (its lines above its base), each line widened by half its declared width
        val boxes = LinkedHashMap<Part, DoubleArray>(); val cellBoxes = HashMap<String, DoubleArray>(); val testBoxes = HashMap<Part, DoubleArray>()
        fun grow(b: DoubleArray, sg: S) { val hw = if (sg.wd.isNaN()) 0.0 else sg.wd / 2
            b[0] = minOf(b[0], sg.x0 - hw, sg.x1 - hw); b[1] = minOf(b[1], sg.y0 - hw, sg.y1 - hw); b[2] = maxOf(b[2], sg.x0 + hw, sg.x1 + hw); b[3] = maxOf(b[3], sg.y0 + hw, sg.y1 + hw) }
        fun empty() = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
        for (segs in byZ.values) for (sg in segs) {
            // the prime line (laid by the writer) is no part of the plate: its own clearance rule places it
            if (Part.isPrimeLine(sg.cell)) continue
            val p = Part.ofCell(sg.cell); val c = sg.cell.removePrefix("adhesion:")
            grow(boxes.getOrPut(p) { empty() }, sg); grow(cellBoxes.getOrPut(c) { empty() }, sg)
            if (!c.startsWith("base ")) grow(testBoxes.getOrPut(p) { empty() }, sg)
        }
        run {
            val gap = PlateGeometry.familyGap / 1000.0
            val ps = boxes.keys.toList()
            for (i in ps.indices) for (j in i + 1 until ps.size) {
                val a = boxes.getValue(ps[i]); val b = boxes.getValue(ps[j])
                val apart = maxOf(b[0] - a[2], a[0] - b[2], b[1] - a[3], a[1] - b[3])
                if (apart < gap - 0.03) bad("the ${ps[i].title} (${box(a)}) and the ${ps[j].title} (${box(b)}) are ${fmt(apart, 2)} mm apart, not at least ${fmt(gap, 1)} (the gap between the plate's parts)")
            }
        }
        // the bridge row as the first layer lays it (front, back: every later layer's row must match it), and every deck
        // as laid, by band (which deck, its y range), for the check across layers below
        val bridgeRow = doubleArrayOf(Double.NaN, Double.NaN)
        val decksLaid = java.util.TreeMap<Int, MutableList<Pair<String, DoubleArray>>>()
        for ((zUm, segs) in byZ) {
            val zs = "z ${zUm / 1000.0}"
            // the post's front face: its outer ring's frontmost line (the notch steps back from it), its pieces and the gaps between them
            val lw = lineW(spec, zUm)
            val frontY = segs.filter { it.cell.endsWith("post") }.minOfOrNull { minOf(it.y0, it.y1) } ?: Double.NaN
            val front = segs.filter { it.cell.endsWith("post") && Math.abs(it.y0 - frontY) < 0.03 && Math.abs(it.y1 - frontY) < 0.03 }
                .map { minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) }.sortedBy { it.first }
            val gaps = ArrayList<Double>()
            var reach = front.firstOrNull()?.second ?: Double.NaN
            for ((a, b) in front.drop(1)) { if (a > reach + 0.05) gaps += a - reach; reach = maxOf(reach, b) }
            val notch = PlateGeometry.notchAt(spec, zUm)
            if (!spec.ruler) { if (front.isNotEmpty()) bad("$zs: a ruler post on a plate that has none") }
            else if (front.isEmpty()) bad("$zs: the post has no front face")
            else if (notch == null && gaps.isNotEmpty()) bad("$zs: a gap of ${fmt(gaps.first(), 2)} mm in the post's front face where no band's notch is")
            else if (notch != null) {
                val want = PlateGeometry.notchWidth(notch) / 1000.0 + lw
                if (gaps.size != 1) bad("$zs: band ${notch.index + 1}'s notch: ${gaps.size} gaps in the post's front face, not 1")
                else if (Math.abs(gaps[0] - want) > 0.06) bad("$zs: band ${notch.index + 1}'s notch is ${fmt(gaps[0], 2)} mm across the front face, not ${fmt(want, 2)} (${if (want > 3) "long" else "short"})")
            }
            // the bases' layers hold no test (the bases are checked on their own, below)
            if (zUm <= spec.baseLayers * spec.layerUm) continue
            val ss = segs.map { Seg(it.cell, it.x0, it.y0, it.x1, it.y1, it.speed) }
            flowGeometry(spec, zUm, ss, ::bad)
            ledgeGeometry(spec, zUm, ss, ::bad)
            bridgeGeometry(spec, zUm, ss, ::bad, bridgeRow, decksLaid)
            ringGeometry(spec, zUm, ss, ::bad)
            tubeGeometry(spec, zUm, ss, ::bad)
            fitGeometry(spec, zUm, ss, ::bad)
        }
        // terraced decks: no deck of a lower band lies under a higher band's deck (every deck runs the whole row, so
        // overlapping in y is lying directly beneath it, inside its strip)
        for ((k, upper) in decksLaid) for ((j, lower) in decksLaid.headMap(k)) for ((un, u) in upper) for ((ln, lo) in lower) {
            if (minOf(u[1], lo[1]) - maxOf(u[0], lo[0]) > 0.01)
                bad("band ${k + 1}'s $un deck (y ${fmt(u[0], 2)}..${fmt(u[1], 2)}) lies over band ${j + 1}'s $ln deck (y ${fmt(lo[0], 2)}..${fmt(lo[1], 2)}): a deck beneath it within its strip")
        }
        meltGeometry(spec, byZ.mapValues { (z, v) -> v.map { Seg(it.cell, it.x0, it.y0, it.x1, it.y1, it.speed, z, it.flow, it.wd) } }, ::bad)
        meltClearance(spec, gcode, ::bad)
        if (spec.baseLayers > 0) baseGeometry(spec, byZ.mapValues { (_, v) -> v.map { Seg(it.cell, it.x0, it.y0, it.x1, it.y1) } }, testBoxes, cellBoxes, ::bad)
        return out
    }

    /**
     * The terraced melt test (its read layer by thickness on a solid floor), from the
     * g-code's own lines, against figures this check works out itself, not the generator's
     * lists: the strips' thicknesses from the tool's layer.height.max and the bore ([stripHeightUm]), their width the plate's line
     * width, the FAST speed from the machine's print speed and the filament's expected maximum flow ([MeltTerraces.machineSpeed],
     * [MeltTerraces.centerFlow]: the lower of the machine's speed and the speed that runs a line of the middle thickness at that
     * flow), MID half of it, SLOW a quarter:
     *  - the steps' heights, from the bases, the layer height, and the user's 1 mm, not from the
     *    specification's bands ([meltReadLayerByRule]): band 1's read layer after its body (1 mm in whole layers, its top
     *    layer the floor) on the bases, each later band's two layers higher;
     *  - each strip at exactly its floor's top plus its own thickness and at no other height (one read layer); its filament
     *    per mm the bead model's for the plate's line width at that thickness (and declared that wide); its speed its
     *    surface's;
     *  - three surfaces left to right, level; each surface's strips front to back, thinnest at the front, as many lines as
     *    hold the FAST strip's flow [PlateGeometry.meltHoldS] (at least [MeltTerraces.MIN_LINES]), each
     *    [PlateGeometry.meltLineLen] long along X, one bead spacing for their thickness apart, the mean of the two between
     *    strips, every strip's lines starting and ending alike; terraced: each band's step behind the band before's;
     *  - nothing laid above a step's floor within its surfaces but its own strips;
     *  - the floor, on the layer directly under the read layer: its lines inside its two walls front to back (perpendicular
     *    to the strips'), one bead spacing apart (within 2 %), and under every strip line along its whole length (a floor
     *    line's or wall's center line within three quarters of a spacing of every point: a line left out fails it);
     *  - the bodies, on every layer between the bases and the floor: a grid of front-to-back and left-right lines about
     *    [PlateGeometry.pedestalPitch] apart (so never solid);
     *  - every move clear of the strips laid ([meltClearance], from the g-code's moves, travels included).
     */
    private fun meltGeometry(spec: CalibrationSpec, byZ: Map<Long, List<Seg>>, bad: (String) -> Unit) {
        val mt = spec.melt ?: return
        val n = 6
        val w = spec.widthUm / 1000.0
        val wantH = LongArray(n) { stripHeightUm(spec, it) }
        val fast = Math.min(mt.machineSpeed, mt.centerFlow / (MeltTerraces.bead(spec.widthUm, (wantH.first() + wantH.last()) / 2) * spec.flowRatio))
        val wantV = doubleArrayOf(fast, fast / 2, fast / 4)
        val len = PlateGeometry.meltLineLen / 1000.0
        val wantLines = maxOf(MeltTerraces.MIN_LINES, Math.ceil(PlateGeometry.meltHoldS * fast / len - 1e-9).toInt())
        fun spOf(hUm: Long) = w - hUm / 1000.0 * (1 - Math.PI / 4)
        val names = listOf("FAST", "MID", "SLOW")
        // each surface as its strips lay it (x0, y0, x1, y1), and its strips' lines (for the floor's coverage)
        val surfaces = LinkedHashMap<Pair<Int, Int>, DoubleArray>(); val stripLines = HashMap<Pair<Int, Int>, List<Seg>>()
        var prevBack = Double.NaN
        val bodyLayers = PlateGeometry.meltBodyLayers(spec.layerUm)
        for (b in spec.bands.indices) {
            val read = meltReadLayerByRule(spec, b)
            val floorTop = read * spec.layerUm
            val zs = "z ${floorTop / 1000.0}+"
            var stepFront = Double.MAX_VALUE; var stepBack = -Double.MAX_VALUE
            var heightOff = 0
            for (s in 0 until 3) {
                val name = "band ${b + 1}'s ${names[s]} surface"
                val box = doubleArrayOf(Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE)
                val all = ArrayList<Seg>()
                var lastY = Double.NaN; var lastSp = Double.NaN; var sx0 = Double.NaN; var sx1 = Double.NaN
                for (k in 0 until n) {
                    val cell = "melt ${b + 1}.${MeltTerraces.SURFACES[s]}.${k + 1}"
                    // its height: its floor's top plus its thickness, and no other
                    val want = floorTop + wantH[k]
                    val at = byZ.filter { (_, segs) -> segs.any { it.cell == cell } }.keys.sorted()
                    // (the first strip off its height on a step only: a step at the wrong height has every strip off)
                    if (at != listOf(want) && heightOff++ == 0) bad("$name's strip ${k + 1} at z ${at.joinToString { (it / 1000.0).toString() }}, not z ${want / 1000.0} (its floor's top ${floorTop / 1000.0} plus its " +
                        "${wantH[k] / 1000.0} mm thickness; the ${spec.baseLayers}-layer bases, band 1's ${bodyLayers}-layer body with its floor on top, each later band 2 layers higher)")
                    // its lines where it is (at its height, or the first it is at: its height is reported above)
                    val lines = byZ[if (want in at) want else at.firstOrNull() ?: want].orEmpty().filter { it.cell == cell && Math.abs(it.y1 - it.y0) < 1e-6 && it.len > len / 2 }
                    if (lines.isEmpty()) { bad("$name has no strip ${k + 1}"); continue }
                    all += lines
                    if (lines.size != wantLines) bad("$name's strip ${k + 1} has ${lines.size} lines, not $wantLines (${fmt(PlateGeometry.meltHoldS, 1)} s at ${fmt(fast, 1)} mm/s, ${fmt(len, 0)} mm a line, at least ${MeltTerraces.MIN_LINES})")
                    lines.firstOrNull { Math.abs(it.len - len) > 0.01 }?.let { bad("$name's strip ${k + 1} has a line ${fmt(it.len, 3)} mm long, not ${fmt(len, 0)}") }
                    // its bead: from its filament per mm (flow / speed: the bead's area times the flow ratio), against the bead
                    // model's for the plate's line width at the strip's thickness
                    val area = lines.map { it.flow / it.speed / spec.flowRatio }.average(); val wantA = MeltTerraces.bead(spec.widthUm, wantH[k])
                    if (Math.abs(area - wantA) > wantA * 0.01) bad("$name's strip ${k + 1} lays ${fmt(area, 4)} mm2 a mm by its filament, not ${fmt(wantA, 4)} (a ${fmt(w, 2)} mm line ${wantH[k] / 1000.0} mm thick)")
                    lines.firstOrNull { Math.abs(it.wd - w) > 5e-4 || it.wd.isNaN() }?.let { bad("$name's strip ${k + 1} is declared ${fmt(it.wd, 3)} mm wide, not ${fmt(w, 3)} (the plate's line width)") }
                    lines.firstOrNull { Math.abs(it.speed - wantV[s]) > 0.02 }?.let { bad("$name's strip ${k + 1} at ${fmt(it.speed, 2)} mm/s, not ${fmt(wantV[s], 2)} (FAST ${fmt(fast, 2)}${if (s == 0) "" else if (s == 1) " / 2" else " / 4"})") }
                    // its lines one bead spacing apart for its thickness; from the last strip's, the mean of the two spacings
                    val ys = lines.map { it.y0 }.sorted(); val sp = spOf(wantH[k])
                    for (i in 1 until ys.size) if (Math.abs(ys[i] - ys[i - 1] - sp) > 0.012) { bad("$name's strip ${k + 1}: lines $i and ${i + 1} are ${fmt(ys[i] - ys[i - 1], 3)} mm apart, not ${fmt(sp, 3)} (the bead spacing)"); break }
                    if (!lastY.isNaN()) {
                        val wantGap = (lastSp + sp) / 2
                        if (Math.abs(ys.first() - lastY - wantGap) > 0.012) bad("$name: strips $k and ${k + 1} are ${fmt(ys.first() - lastY, 3)} mm apart, not ${fmt(wantGap, 3)} (the mean of their spacings; strip ${k + 1} behind strip $k)")
                    }
                    lastY = ys.last(); lastSp = sp
                    val a = lines.minOf { minOf(it.x0, it.x1) }; val c = lines.maxOf { maxOf(it.x0, it.x1) }
                    if (sx0.isNaN()) { sx0 = a; sx1 = c } else if (Math.abs(a - sx0) > 0.03 || Math.abs(c - sx1) > 0.03) bad("$name's strip ${k + 1} runs x ${fmt(a, 2)}..${fmt(c, 2)}, not ${fmt(sx0, 2)}..${fmt(sx1, 2)} as its first strip")
                    box[0] = minOf(box[0], a - w / 2); box[2] = maxOf(box[2], c + w / 2)
                    box[1] = minOf(box[1], ys.first() - w / 2); box[3] = maxOf(box[3], ys.last() + w / 2)
                }
                if (box[0] > box[2]) continue
                surfaces[b to s] = box; stripLines[b to s] = all
                stepFront = minOf(stepFront, box[1]); stepBack = maxOf(stepBack, box[3])
            }
            // left to right FAST, MID, SLOW, level with each other
            for (s in 1 until 3) {
                val a = surfaces[b to s - 1] ?: continue; val c = surfaces[b to s] ?: continue
                if (c[0] <= a[2]) bad("$zs: band ${b + 1}'s ${names[s]} surface (x ${fmt(c[0], 2)}..) is not right of its ${names[s - 1]} surface (..x ${fmt(a[2], 2)})")
                if (Math.abs(c[1] - a[1]) > 0.03) bad("$zs: band ${b + 1}'s ${names[s]} surface starts at y ${fmt(c[1], 2)}, not level with its ${names[s - 1]} surface's ${fmt(a[1], 2)}")
            }
            // terraced: each band's step behind the band before's
            if (!prevBack.isNaN() && stepFront < prevBack - 0.03) bad("$zs: band ${b + 1}'s melt step starts at y ${fmt(stepFront, 2)}, in front of band $b's back (y ${fmt(prevBack, 2)}): not behind it")
            if (stepFront <= stepBack) prevBack = stepBack
            // its bodies from the bases' top to below the floor, its floor directly under the read layer, no higher
            for (s in 0 until 3) {
                for ((cell, want) in listOf("melt body ${b + 1}.${MeltTerraces.SURFACES[s]}" to ((spec.baseLayers + 1) until read).map { it * spec.layerUm },
                        "melt floor ${b + 1}.${MeltTerraces.SURFACES[s]}" to listOf(floorTop))) {
                    val zsOf = byZ.filter { (_, segs) -> segs.any { it.cell.removePrefix("adhesion:") == cell } }.keys.sorted()
                    if (zsOf != want) bad("'$cell' on layers at z ${zsOf.joinToString { (it / 1000.0).toString() }}, not ${if (want.isEmpty()) "none" else "z " + want.joinToString { (it / 1000.0).toString() }}" +
                        " (the body from the bases' top up to its floor, the floor the layer directly under the read layer)")
                }
            }
        }
        // one read layer: above a step's floor, within its surfaces, nothing but its own strips
        for ((key, box) in surfaces) {
            val floorTop = meltReadLayerByRule(spec, key.first) * spec.layerUm
            val own = "melt ${key.first + 1}."
            fun inside(x: Double, y: Double) = x > box[0] + 0.05 && x < box[2] - 0.05 && y > box[1] + 0.05 && y < box[3] - 0.05
            for ((z, segs) in byZ) {
                if (z <= floorTop) continue
                val over = segs.firstOrNull { !it.cell.startsWith(own) && (inside(it.x0, it.y0) || inside(it.x1, it.y1) || inside((it.x0 + it.x1) / 2, (it.y0 + it.y1) / 2)) } ?: continue
                bad("z ${z / 1000.0}: '${over.cell}' laid on band ${key.first + 1}'s ${names[key.second]} surface, above its floor (z ${floorTop / 1000.0}): one read layer only"); break
            }
        }
        val pitch = PlateGeometry.pedestalPitch / 1000.0
        for ((key, _) in surfaces) {
            val (b, s) = key
            val read = meltReadLayerByRule(spec, b)
            val name = "band ${b + 1}'s ${names[s]}"
            // the floor: perpendicular lines a bead spacing apart, under every strip line
            run {
                val z = read * spec.layerUm
                val mine = byZ[z].orEmpty().filter { it.cell == "melt floor ${b + 1}.${MeltTerraces.SURFACES[s]}" }
                if (mine.isEmpty()) { bad("z ${z / 1000.0}: no floor under $name surface (the grid instead?)"); return@run }
                val lw = lineW(spec, z); val sp = lw - spec.layerUm / 1000.0 * (1 - Math.PI / 4)
                val bx0 = mine.minOf { minOf(it.x0, it.x1) } - lw / 2; val bx1 = mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2
                val by0 = mine.minOf { minOf(it.y0, it.y1) } - lw / 2; val by1 = mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2
                val wall = lw / 2 + sp + 0.05
                val inner = mine.filter { minOf(it.x0, it.x1) > bx0 + wall && maxOf(it.x0, it.x1) < bx1 - wall && minOf(it.y0, it.y1) > by0 + wall && maxOf(it.y0, it.y1) < by1 - wall }
                inner.firstOrNull { it.len > 2.0 && Math.abs(it.x1 - it.x0) > 1e-6 }?.let { bad("z ${z / 1000.0}: $name floor has a ${fmt(it.len, 1)} mm line from (${fmt(it.x0, 2)}, ${fmt(it.y0, 2)}) not front to back (perpendicular to the strips' lines)") }
                val xs = inner.filter { it.len > 2.0 }.map { it.x0 }.sorted()
                if (xs.size < 2) { bad("z ${z / 1000.0}: $name floor has ${xs.size} front-to-back lines"); return@run }
                xs.zipWithNext { a, c -> c - a }.firstOrNull { Math.abs(it - sp) > sp * 0.02 }?.let { bad("z ${z / 1000.0}: $name floor's lines ${fmt(it, 3)} mm apart, not one bead spacing (${fmt(sp, 3)} mm): not solid") }
                // every strip line over it: along its whole length within three quarters of a spacing of a floor line's or wall's
                // center line (where the lines are a spacing apart no point is further than half of one, or about 0.7 of one in
                // the corners between line ends and the walls; a line left out leaves points a whole spacing from the nearest)
                val reach = sp * 0.75
                for (ln in stripLines[key].orEmpty()) {
                    val y = ln.y0
                    val cover = ArrayList<Pair<Double, Double>>()
                    // where each floor line (along X or Y) comes within [reach] of the strip line's height: an x range
                    for (f in mine) {
                        if (Math.abs(f.x1 - f.x0) < 1e-6) {
                            val dy = maxOf(0.0, minOf(f.y0, f.y1) - y, y - maxOf(f.y0, f.y1))
                            if (dy <= reach) Math.sqrt(reach * reach - dy * dy).let { hw -> cover += (f.x0 - hw) to (f.x0 + hw) }
                        } else if (Math.abs(f.y1 - f.y0) < 1e-6) {
                            val dy = Math.abs(f.y0 - y)
                            if (dy <= reach) Math.sqrt(reach * reach - dy * dy).let { hw -> cover += (minOf(f.x0, f.x1) - hw) to (maxOf(f.x0, f.x1) + hw) }
                        }
                    }
                    cover.sortBy { it.first }
                    var at = minOf(ln.x0, ln.x1); val end = maxOf(ln.x0, ln.x1)
                    for ((a, c) in cover) { if (a > at + 1e-6) break; at = maxOf(at, c) }
                    if (at < end - 1e-6) { bad("$name's strip line at y ${fmt(y, 3)} is over no floor from x ${fmt(at, 3)} (the floor at z ${z / 1000.0} does not cover it; ${cover.size} floor lines or walls reach it, the first x ${cover.firstOrNull()?.let { "${fmt(it.first, 3)}..${fmt(it.second, 3)}" } ?: "none"})"); break }
                }
            }
            // the bodies: on every layer above the bases (and the first) up to below the floor, the grid
            for ((z, segs) in byZ) {
                // the layers (not the strips' own heights between them)
                if (z % spec.layerUm != 0L || z <= spec.baseLayers * spec.layerUm || z <= spec.layerUm || z >= read * spec.layerUm) continue
                val zs = "z ${z / 1000.0}"
                val mine = segs.filter { it.cell == "melt body ${b + 1}.${MeltTerraces.SURFACES[s]}" }
                if (mine.isEmpty()) { bad("$zs: no $name body"); break }
                val lw = lineW(spec, z); val sp = lw - spec.layerUm / 1000.0 * (1 - Math.PI / 4)
                val bx0 = mine.minOf { minOf(it.x0, it.x1) } - lw / 2; val bx1 = mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2
                val by0 = mine.minOf { minOf(it.y0, it.y1) } - lw / 2; val by1 = mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2
                // the grid: lines further in than the body's two walls (their centers half a line and a spacing more in)
                val wall = lw / 2 + sp + 0.05
                val ys = mine.filter { Math.abs(it.x1 - it.x0) < 1e-6 && it.x0 > bx0 + wall && it.x0 < bx1 - wall && it.len > (by1 - by0) / 2 }.map { it.x0 }.sorted()
                val xs = mine.filter { Math.abs(it.y1 - it.y0) < 1e-6 && it.y0 > by0 + wall && it.y0 < by1 - wall && it.len > (bx1 - bx0) / 2 }.map { it.y0 }.sorted()
                for ((what, at) in listOf("front-to-back" to ys, "left-right" to xs)) {
                    if (at.isEmpty()) { bad("$zs: $name body has no $what lines"); break }
                    val off = at.zipWithNext { a, c -> c - a }.firstOrNull { Math.abs(it - pitch) > 0.25 }
                    if (off != null) { bad("$zs: $name body's $what lines ${fmt(off, 3)} mm apart, not about ${fmt(pitch, 1)} (a grid, never solid)"); break }
                }
            }
        }
    }

    /**
     * The nozzle against the melt strips: they stand up to the tool's layer.height.max above their floor, above the
     * layer grid, and are the surface read. From the g-code's own moves (travels and Z moves included), every point of every
     * move after a strip is laid (sampled every 0.1 mm), against every strip bead laid before it whose center line is within
     * half its width of the nozzle's: the nozzle's height above the bead's top (its clearance) must be at least the tool's
     * lift ([MeltTerraces.liftUm]), except over the strip a printing move belongs to, and over the line a travel is leaving
     * (the line the nozzle has just laid), where it must not be below 0. So a travel dragged across a strip, or a strip printed
     * after a thicker one beside it, fails here.
     */
    private fun meltClearance(spec: CalibrationSpec, gcode: String, bad: (String) -> Unit) {
        val mt = spec.melt ?: return
        val lift = mt.liftUm / 1000.0
        class Bead(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val hw: Double, val top: Double, val cell: String)
        val beads = ArrayList<Bead>()
        val grid = HashMap<Long, MutableList<Int>>()
        fun key(ix: Int, iy: Int) = ix.toLong() * 1_000_003L + iy
        fun lay(bd: Bead) {
            val i = beads.size; beads += bd
            for (ix in Math.floor(minOf(bd.x0, bd.x1) - bd.hw).toInt()..Math.floor(maxOf(bd.x0, bd.x1) + bd.hw).toInt())
                for (iy in Math.floor(minOf(bd.y0, bd.y1) - bd.hw).toInt()..Math.floor(maxOf(bd.y0, bd.y1) + bd.hw).toInt()) grid.getOrPut(key(ix, iy)) { ArrayList() } += i
        }
        val lines = gcode.lines()
        val lastCell = lines.indexOfLast { it.trim() == "; CELL END" }
        var cell: String? = null; var wd = Double.NaN
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        var leaving = -1          // the bead the nozzle has just laid (a travel may leave it at 0 clearance)
        for ((li, raw) in lines.withIndex()) {
            if (li > lastCell) break
            val l = raw.trim()
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
            if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").trim().toDouble(); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            val xy = !x.isNaN() && (nx != x || ny != y)
            val printing = e > 0 && xy
            if (beads.isNotEmpty() && !x.isNaN() && !z.isNaN() && (xy || nz != z)) {
                val d = Math.hypot(nx - x, ny - y)
                val steps = maxOf(1, Math.ceil(d / 0.1).toInt())
                for (t in 0..steps) {
                    val px = x + (nx - x) * t / steps; val py = y + (ny - y) * t / steps; val pz = z + (nz - z) * t / steps
                    val near = grid[key(Math.floor(px).toInt(), Math.floor(py).toInt())] ?: continue
                    for (i in near) {
                        val bd = beads[i]
                        if (segDist(px, py, bd.x0, bd.y0, bd.x1, bd.y1) >= bd.hw - 1e-6) continue
                        val need = if ((printing && bd.cell == cell) || (!printing && i == leaving)) 0.0 else lift
                        val clear = pz - bd.top
                        if (clear < need - 0.0015) {
                            bad("line ${li + 1}: ${if (printing) "printing '$cell'" else if (xy) "a travel" else "a Z move"} at (${fmt(px, 2)}, ${fmt(py, 2)}) z ${fmt(pz, 3)}, ${fmt(clear, 3)} mm above " +
                                "'${bd.cell}' (its top z ${fmt(bd.top, 3)}), not at least ${fmt(need, 2)} (${if (need > 0) "the travel lift over a strip already laid" else "never below the strip"})")
                            return
                        }
                    }
                }
            }
            if (printing) {
                val cl = cell
                if (cl != null && meltStrip(cl) != null) { lay(Bead(x, y, nx, ny, wd / 2, nz, cl)); leaving = beads.size - 1 } else leaving = -1
            } else if (xy) leaving = -1     // the travel has left it
            x = nx; y = ny; z = nz
        }
    }

    /**
     * The bases, from the g-code's own lines: on each base layer every family's base a rectangle
     * [PlateGeometry.baseMargin] wider than its test (the test's lines above the base, read from the g-code) on every side;
     * its front face whole on the first layer, and on every layer above it broken by exactly one notch, as wide as
     * [PlateGeometry.baseNotchW] plus a line (the outer ring steps round it) and [PlateGeometry.baseNotchIn] deep, standing in
     * front of the test's first item as the g-code lays it (the first ledge, span, tube, hole, column of patches, ring post,
     * the melt test's FAST surface).
     */
    private fun baseGeometry(spec: CalibrationSpec, byZ: Map<Long, List<Seg>>, tests: Map<Part, DoubleArray>, cells: Map<String, DoubleArray>, bad: (String) -> Unit) {
        val m = PlateGeometry.baseMargin / 1000.0
        // where each test's first item stands across (x range), from its own lines
        fun cell(c: String) = cells[c]
        fun item(p: Part): Pair<Double, Double>? = when (p) {
            Part.MELT -> cell("melt 1.fast.1")?.let { it[0] to it[2] }
            Part.OVERHANGS -> cell("ledges")?.let { b -> val a = b[0] + PlateGeometry.ledgeEnd / 1000.0; a to a + PlateGeometry.ledgeLen / 1000.0 }
            Part.BRIDGES -> cell("pier")?.let { b -> val a = b[0] + PlateGeometry.pierW / 1000.0; a to a + spec.bridges!!.spansUm[0] / 1000.0 }
            Part.FIT -> cell("fit block")?.let { b -> val a = b[0] + PlateGeometry.fitWall / 1000.0; a to a + spec.fit!!.holesUm.max() / 1000.0 }
            Part.LINE_WIDTH -> cell("tube 1")?.let { it[0] to it[2] }
            Part.STRINGING -> cell("post 1")?.let { it[0] to it[2] }
            Part.FLOW -> (0 until spec.flow!!.fromLayers.size).flatMap { r -> listOfNotNull(cell(spec.flow.label(r, 0)), cell(spec.flow.pedestalLabel(r, 0))) }
                .takeIf { it.isNotEmpty() }?.let { bs -> bs.minOf { it[0] } to bs.maxOf { it[2] } }
            Part.POST -> null
        }
        for (layer in 0 until spec.baseLayers) {
            val z = (layer + 1) * spec.layerUm
            val zs = "z ${z / 1000.0}"
            val lw = lineW(spec, z)
            val segs = byZ[z].orEmpty()
            for (p in spec.parts) {
                if (p == Part.POST) continue
                val mine = segs.filter { it.cell.removePrefix("adhesion:") == "base ${p.key}" }
                if (mine.isEmpty()) { bad("$zs: the ${p.title} has no base"); continue }
                val t = tests[p] ?: run { bad("the ${p.title} has a base but no test above it"); continue }
                val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - lw / 2, mine.minOf { minOf(it.y0, it.y1) } - lw / 2, mine.maxOf { maxOf(it.x0, it.x1) } + lw / 2, mine.maxOf { maxOf(it.y0, it.y1) } + lw / 2)
                val want = doubleArrayOf(t[0] - m, t[1] - m, t[2] + m, t[3] + m)
                if ((0..3).any { Math.abs(b[it] - want[it]) > 0.03 }) bad("$zs: the ${p.title}'s base is ${box(b)}, not ${box(want)} (its test and ${fmt(m, 0)} mm round it)")
                // its front face: the outer ring's frontmost line, its pieces and the gaps between them
                val frontY = mine.minOf { minOf(it.y0, it.y1) }
                val front = mine.filter { Math.abs(it.y0 - frontY) < 0.03 && Math.abs(it.y1 - frontY) < 0.03 }.map { minOf(it.x0, it.x1) to maxOf(it.x0, it.x1) }.sortedBy { it.first }
                val gaps = ArrayList<Pair<Double, Double>>()
                var reach = front.firstOrNull()?.second ?: Double.NaN
                for ((a, c) in front.drop(1)) { if (a > reach + 0.05) gaps += reach to a; reach = maxOf(reach, c) }
                if (layer == 0) { if (gaps.isNotEmpty()) bad("$zs: the ${p.title}'s base has ${gaps.size} gap(s) in its front face on its first layer, which is whole (a notch is cut only above it)"); continue }
                if (gaps.size != 1) { bad("$zs: the ${p.title}'s base has ${gaps.size} notches in its front face, not 1"); continue }
                val (g0, g1) = gaps[0]
                val wantW = PlateGeometry.baseNotchW / 1000.0 + lw
                if (Math.abs(g1 - g0 - wantW) > 0.06) bad("$zs: the ${p.title}'s base notch is ${fmt(g1 - g0, 2)} mm across its front face, not ${fmt(wantW, 2)}")
                // its depth: the frontmost base line across the notch's middle (where each line crosses it: the fill's lines are
                // diagonal)
                val mid = (g0 + g1) / 2
                val inner = mine.filter { minOf(it.x0, it.x1) <= mid && maxOf(it.x0, it.x1) >= mid }
                    .minOfOrNull { if (Math.abs(it.x1 - it.x0) < 1e-9) minOf(it.y0, it.y1) else it.y0 + (mid - it.x0) * (it.y1 - it.y0) / (it.x1 - it.x0) }
                if (inner == null || Math.abs(inner - frontY - PlateGeometry.baseNotchIn / 1000.0) > 0.03) bad("$zs: the ${p.title}'s base notch is ${inner?.let { fmt(it - frontY, 2) } ?: "?"} mm deep, not ${fmt(PlateGeometry.baseNotchIn / 1000.0, 1)}")
                val at = item(p)
                if (at == null || mid < at.first - 0.03 || mid > at.second + 0.03) bad("$zs: the ${p.title}'s base notch is at x ${fmt(mid, 2)}, not in front of its first item (x ${at?.let { "${fmt(it.first, 2)}..${fmt(it.second, 2)}" } ?: "not found"})")
            }
        }
    }

    /** How far apart (mm) [baseFill] samples a base's inside for coverage, in X and in Y. */
    private const val COVER_STEP = 0.25

    /**
     * The bases' fill, from the g-code's own paths
     * (a path: one `; CELL:` block), against figures this check works out itself: the bead spacing from the width the path
     * declares and the layer height (w - h(1 - pi/4)), the base's inside from its own innermost ring as printed. On every base
     * layer, every family's base:
     *  - first [PlateGeometry.frameWalls] closed outline rings (walls), each one bead spacing inside the one before; after them
     *    only lines (open paths, not walls);
     *  - every line at +-45 degrees, one angle on the whole layer and the other on the base's next layer (alternating); the
     *    joins between lines, along the inside's edge, the only other moves (along X or Y, as the base's edges run);
     *  - the lines one bead spacing apart, every one there between the first and the last (none missing);
     *  - in monotonic order: each run of joined lines steps one line at a time, every run on the layer the same way across the
     *    base, the first line at that end of the sweep, and no line printed when lines on both sides of it (beside it along its
     *    length) are printed already;
     *  - every line inside the innermost ring and nowhere nearer to it than a bead spacing (so none across the notch), every
     *    end and turn of the lines a bead spacing from it (up to sqrt 2 of one, at the inside's corners where the ring turns
     *    into the notch);
     *  - the inside covered: every point more than two bead spacings inside the innermost ring (sampled every [COVER_STEP] mm)
     *    within half a spacing of a line.
     * With the bases' front faces ([baseGeometry]) this is the notch: the rings round it above the first layer and nothing in
     * it, the first layer's rings whole and its inside covered.
     */
    fun baseFill(spec: CalibrationSpec, gcode: String): List<String> {
        if (spec.baseLayers <= 0) return emptyList()
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 30) out += s }
        class P(val type: String, val wd: Double, val xy: ArrayList<Double>) {
            val n get() = xy.size / 2
            fun closed() = n >= 4 && Math.abs(xy[0] - xy[xy.size - 2]) < 0.002 && Math.abs(xy[1] - xy[xy.size - 1]) < 0.002
        }
        // the bases' paths: z (µm) -> base cell -> its paths in print order
        val paths = HashMap<Long, LinkedHashMap<String, MutableList<P>>>()
        run {
            var cell: String? = null; var type = ""; var wd = Double.NaN; var x = Double.NaN; var y = Double.NaN; var z = 0.0
            var cur: P? = null; var curZ = 0L
            for (raw in gcode.lineSequence()) {
                val l = raw.trim()
                if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: ").removePrefix("adhesion:"); cur = null; continue }
                if (l == "; CELL END") {
                    val c = cur; val cl = cell
                    if (c != null && cl != null && c.n >= 2) paths.getOrPut(curZ) { LinkedHashMap() }.getOrPut(cl) { ArrayList() } += c
                    cell = null; cur = null; continue
                }
                if (l.startsWith(";TYPE:")) { type = l.removePrefix(";TYPE:").trim(); continue }
                if (l.startsWith("; FEATURE:")) { type = l.removePrefix("; FEATURE:").trim(); continue }
                if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").trim().toDouble(); continue }
                if (l.startsWith("; LINE_WIDTH:")) { wd = l.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
                val c = l.substringBefore(';').trim(); if (!c.startsWith("G1")) continue
                val ws = c.split(Regex("\\s+"))
                fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
                num('Z')?.let { z = it }
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                val cl = cell
                if (cl != null && cl.startsWith("base ") && !x.isNaN() && (nx != x || ny != y)) {
                    if (e > 0) {
                        val p = cur ?: P(type, wd, ArrayList()).also { cur = it; curZ = Math.round(z * 1000); it.xy += x; it.xy += y }
                        p.xy += nx; p.xy += ny
                    } else cur?.let { c ->
                        // a travel inside the cell ends the path there (the next line printed is a path of its own)
                        if (c.n >= 2) paths.getOrPut(curZ) { LinkedHashMap() }.getOrPut(cl) { ArrayList() } += c
                        cur = null
                    }
                }
                x = nx; y = ny
            }
        }
        val h = spec.layerUm / 1000.0
        val lastAngle = HashMap<String, Double>()
        for (layer in 0 until spec.baseLayers) {
            val zUm = (layer + 1) * spec.layerUm
            val zs = "z ${zUm / 1000.0}"
            for (part in spec.parts) {
                if (part == Part.POST) continue
                val name = "base ${part.key}"
                val ps = paths[zUm]?.get(name) ?: continue          // a base that is not there: baseGeometry reports it
                val title = "the ${part.title}'s base"
                val sp = ps.first().wd - h * (1 - Math.PI / 4)
                fun isWall(t: String) = t.endsWith("wall", ignoreCase = true)
                // the outline rings first, each a bead spacing inside the one before (their extents); then lines only
                val nRings = ps.indexOfFirst { !(isWall(it.type) && it.closed()) }.let { if (it < 0) ps.size else it }
                if (nRings != PlateGeometry.frameWalls) bad("$zs: $title starts with $nRings outline ring(s), not ${PlateGeometry.frameWalls}")
                val fill = ps.drop(nRings)
                if (fill.any { isWall(it.type) || it.closed() }) bad("$zs: $title has a ring after its lines (its outline rings first, then only lines)")
                if (nRings == 0) continue
                fun ext(q: P) = doubleArrayOf((0 until q.n).minOf { q.xy[2 * it] }, (0 until q.n).minOf { q.xy[2 * it + 1] }, (0 until q.n).maxOf { q.xy[2 * it] }, (0 until q.n).maxOf { q.xy[2 * it + 1] })
                for (i in 1 until nRings) {
                    val a = ext(ps[i - 1]); val b = ext(ps[i])
                    if ((0..3).any { k -> Math.abs((b[k] - a[k]) * (if (k < 2) 1 else -1) - sp) > 0.01 }) bad("$zs: $title's ring ${i + 1} is not one bead spacing (${fmt(sp, 3)} mm) inside ring $i (${box(a)}; ${box(b)})")
                }
                val lines = fill.filter { !it.closed() && !isWall(it.type) }
                if (lines.isEmpty()) { bad("$zs: $title has no lines inside its rings"); continue }
                // the lines: every diagonal move; moves along X or Y are the joins along the inside's edge; nothing else
                class L(val v: Double, val lo: Double, val hi: Double, val path: Int, val angle: Double)
                val ls = ArrayList<L>(); val angles = java.util.TreeSet<Double>()
                var stray = 0
                for ((pi, q) in lines.withIndex()) for (j in 0 until q.n - 1) {
                    val x0 = q.xy[2 * j]; val y0 = q.xy[2 * j + 1]; val x1 = q.xy[2 * j + 2]; val y1 = q.xy[2 * j + 3]
                    val dx = x1 - x0; val dy = y1 - y0
                    if (Math.abs(dx) <= 0.002 || Math.abs(dy) <= 0.002) continue
                    val a = when { Math.abs(dx - dy) / Math.sqrt(2.0) < 0.005 -> 45.0; Math.abs(dx + dy) / Math.sqrt(2.0) < 0.005 -> 135.0; else -> Double.NaN }
                    if (a.isNaN()) { if (stray++ == 0) bad("$zs: $title has a line at ${fmt(Math.toDegrees(Math.atan2(dy, dx)), 1)} degrees (from x ${fmt(x0, 2)}, y ${fmt(y0, 2)}), not +-45"); continue }
                    angles += a
                    val r = Math.toRadians(a); val ux = Math.cos(r); val uy = Math.sin(r)
                    val u0 = ux * x0 + uy * y0; val u1 = ux * x1 + uy * y1
                    ls += L(-uy * x0 + ux * y0, minOf(u0, u1), maxOf(u0, u1), pi, a)
                }
                if (angles.isEmpty()) { bad("$zs: $title has no diagonal lines"); continue }
                if (angles.size > 1) bad("$zs: $title has lines at ${angles.joinToString(" and ") { fmt(it, 0) }} degrees on one layer, not one angle")
                val angle = angles.first()
                lastAngle[name]?.let { if (it == angle) bad("$zs: $title's lines at ${fmt(angle, 0)} degrees, as on the layer below: not alternating") }
                lastAngle[name] = angle
                val mine = ls.filter { it.angle == angle }
                // one bead spacing apart, none missing: the lines' distinct offsets across, each the next's neighbor
                val across = ArrayList<Double>()
                for (v in mine.map { it.v }.sorted()) if (across.isEmpty() || v - across.last() > 0.01) across += v
                for (i in 1 until across.size) {
                    val g = across[i] - across[i - 1]
                    if (Math.abs(g - sp) > 0.005) { bad("$zs: $title's lines ${i} and ${i + 1} (of ${across.size}) are ${fmt(g, 3)} mm apart, not one bead spacing (${fmt(sp, 3)} mm)"); break }
                }
                fun kOf(v: Double): Int {
                    val at = across.binarySearch(v); if (at >= 0) return at
                    val ins = -at - 1
                    return when { ins == 0 -> 0; ins == across.size -> ins - 1; v - across[ins - 1] < across[ins] - v -> ins - 1; else -> ins }
                }
                val ks = mine.map { kOf(it.v) }
                // monotonic: each run steps one line at a time, all runs the same way, from the end of the sweep
                var dir = 0
                for (i in 1 until mine.size) if (mine[i].path == mine[i - 1].path) {
                    val d = ks[i] - ks[i - 1]
                    if (Math.abs(d) != 1) { bad("$zs: $title's run of lines steps from line ${ks[i - 1] + 1} to line ${ks[i] + 1} (of ${across.size}), not to the next"); break }
                    if (dir == 0) dir = d else if (d != dir) { bad("$zs: $title's runs of lines go both ways across it (not monotonic)"); break }
                }
                if (dir != 0 && ks.first() != (if (dir > 0) 0 else across.size - 1)) bad("$zs: $title's first line is line ${ks.first() + 1} of ${across.size}, not at the end of the sweep (not monotonic)")
                val printed = HashMap<Int, MutableList<L>>()
                for ((i, ln) in mine.withIndex()) {
                    fun beside(k: Int) = printed[k]?.maxOfOrNull { minOf(it.hi, ln.hi) - maxOf(it.lo, ln.lo) } ?: 0.0
                    val below = beside(ks[i] - 1); val above = beside(ks[i] + 1)
                    if (below > 0.05 && above > 0.05) { bad("$zs: $title's line ${ks[i] + 1} (of ${across.size}, ${fmt(ln.hi - ln.lo, 2)} mm long, from u ${fmt(ln.lo, 2)}) is printed after the lines on both sides of it (beside it for ${fmt(below, 2)} and ${fmt(above, 2)} mm: not monotonic)"); break }
                    printed.getOrPut(ks[i]) { ArrayList() } += ln
                }
                // inside the innermost ring as printed (closed: its last point is its first)
                val ring = ps[nRings - 1]
                val rx = DoubleArray(ring.n - 1) { ring.xy[2 * it] }; val ry = DoubleArray(ring.n - 1) { ring.xy[2 * it + 1] }
                fun inside(x: Double, y: Double): Boolean {
                    var c = false; var j = rx.size - 1
                    for (i in rx.indices) { if ((ry[i] > y) != (ry[j] > y) && x < (rx[j] - rx[i]) * (y - ry[i]) / (ry[j] - ry[i]) + rx[i]) c = !c; j = i }
                    return c
                }
                fun dist(x: Double, y: Double): Double {
                    var d = Double.MAX_VALUE; var j = rx.size - 1
                    for (i in rx.indices) { d = minOf(d, segDist(x, y, rx[j], ry[j], rx[i], ry[i])); j = i }
                    return d
                }
                run {
                    for (q in lines) {
                        for (j in 0 until q.n) {
                            val x = q.xy[2 * j]; val y = q.xy[2 * j + 1]; val d = dist(x, y); val inn = inside(x, y)
                            if (!inn || d < sp - 0.01 || d > sp * Math.sqrt(2.0) + 0.01) {
                                bad("$zs: $title has a line's end or turn at x ${fmt(x, 2)}, y ${fmt(y, 2)}, ${fmt(d, 3)} mm ${if (inn) "inside" else "outside"} its innermost ring, not one bead spacing (${fmt(sp, 3)} mm; up to sqrt 2 of one at its inside corners) inside it")
                                return@run
                            }
                        }
                        for (j in 0 until q.n - 1) {
                            val x0 = q.xy[2 * j]; val y0 = q.xy[2 * j + 1]; val x1 = q.xy[2 * j + 2]; val y1 = q.xy[2 * j + 3]
                            val steps = Math.ceil(Math.hypot(x1 - x0, y1 - y0) / 0.5).toInt()
                            for (t in 1 until steps) {
                                val x = x0 + (x1 - x0) * t / steps; val y = y0 + (y1 - y0) * t / steps
                                if (!inside(x, y) || dist(x, y) < sp - 0.01) {
                                    bad("$zs: $title has a line through x ${fmt(x, 2)}, y ${fmt(y, 2)}, ${if (inside(x, y)) "${fmt(dist(x, y), 3)} mm from" else "outside"} its innermost ring (lines stay a bead spacing, ${fmt(sp, 3)} mm, inside it; nothing in the notch)")
                                    return@run
                                }
                            }
                        }
                    }
                }
                // the inside covered: sampled points more than two spacings inside the ring, each within half a spacing of a line
                val byK = HashMap<Int, MutableList<L>>()
                for ((i, ln) in mine.withIndex()) byK.getOrPut(ks[i]) { ArrayList() } += ln
                val r = Math.toRadians(angle); val ux = Math.cos(r); val uy = Math.sin(r)
                var tested = 0; var missed = 0; var firstMiss: String? = null
                var y = ry.min() + COVER_STEP / 2
                while (y < ry.max()) {
                    var x = rx.min() + COVER_STEP / 2
                    while (x < rx.max()) {
                        if (inside(x, y) && dist(x, y) > 2 * sp) {
                            tested++
                            val v = -uy * x + ux * y; val u = ux * x + uy * y; val k = kOf(v)
                            val near = Math.abs(v - across[k]) <= sp / 2 + 0.01 && byK[k]?.any { u >= it.lo - 0.01 && u <= it.hi + 0.01 } == true
                            if (!near) { missed++; if (firstMiss == null) firstMiss = "x ${fmt(x, 2)}, y ${fmt(y, 2)}" }
                        }
                        x += COVER_STEP
                    }
                    y += COVER_STEP
                }
                if (tested == 0) bad("$zs: $title has no inside more than two bead spacings within its rings to cover")
                else if (missed > 0) bad("$zs: $title's inside is not covered: $missed of $tested points (every ${fmt(COVER_STEP, 2)} mm, more than two bead spacings inside its innermost ring) over half a spacing from any line, the first at $firstMiss")
            }
        }
        return out
    }

    /** The distance (mm) from ([px], [py]) to the segment ([ax], [ay])-([bx], [by]). */
    private fun segDist(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
        val t = if (l2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - ax - t * vx, py - ay - t * vy)
    }

    /** A line of the g-code: [flow] its volumetric flow (mm³/s), [wd] the width its path declares (NaN where not read). */
    private class Seg(val cell: String, val x0: Double, val y0: Double, val x1: Double, val y1: Double, val speed: Double = Double.NaN, val zUm: Long = 0,
                      val flow: Double = Double.NaN, val wd: Double = Double.NaN) { val len get() = Math.hypot(x1 - x0, y1 - y0) }

    /**
     * The flow-ratio patches on the layer at [zUm], from the g-code's own lines, against figures this check
     * works out itself (not the generator's): every patch square, as many lines as make it about
     * [PlateGeometry.patchAbout] across at the bead model's spacing, its lines exactly that spacing apart (the
     * spacing is what makes the flow ratio the only thing that varies); a row per band, each
     * row's patches low to high from the left [PlateGeometry.patchGap] apart, the rows in band order
     * from the front, a patch and that gap apart and in line; the marker in front of the front row's patch at the current
     * ratio.
     */
    private fun flowGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit) {
        val f = spec.flow ?: return
        val layer = (zUm / spec.layerUm - 1).toInt()
        val zs = "z ${zUm / 1000.0}"
        // a patch's size: whole lines of the plate's own width at the bead spacing, about [PlateGeometry.patchAbout]
        // across; on the first layer its lines are the first layer's width, as many as fit in that size at their spacing
        val h = spec.layerUm / 1000.0
        val w0 = spec.widthUm / 1000.0; val sp0 = w0 - h * (1 - Math.PI / 4)
        val lines0 = maxOf(2, Math.floor((PlateGeometry.patchAbout / 1000.0 - w0 - 2 * sp0) / sp0 + 1e-9).toInt() + 1)
        val size = (lines0 - 1) * sp0 + 2 * sp0 + w0
        val w = lineW(spec, zUm)
        val sp = w - h * (1 - Math.PI / 4)
        val lines = maxOf(1, Math.floor((size - w - 2 * sp) / sp + 1e-6).toInt() + 1)
        val gap = PlateGeometry.patchGap / 1000.0
        // each row on this layer (solid up to its top, its pedestals below): its patches' boxes
        val rows = java.util.TreeMap<Int, List<DoubleArray>>()
        for (r in f.fromLayers.indices) {
            val solid = layer >= f.fromLayers[r] && layer <= f.topLayer(r)
            if (!solid && layer >= f.fromLayers[r]) continue
            val boxes = ArrayList<DoubleArray>()
            for (k in f.ratios.indices) {
                val name = if (solid) f.label(r, k) else f.pedestalLabel(r, k)
                val pn = "${r + 1}.${k + 1}"
                val mine = segs.filter { it.cell == name || it.cell == "adhesion:$name" }
                if (mine.isEmpty()) { bad("$zs: flow patch $pn (band ${r + 1}'s row) has no ${if (solid) "lines" else "pedestal"}"); return }
                // its wall's center line half a line in from its edge: the box of everything it lays, widened by half a line
                val b = doubleArrayOf(mine.minOf { minOf(it.x0, it.x1) } - w / 2, mine.minOf { minOf(it.y0, it.y1) } - w / 2, mine.maxOf { maxOf(it.x0, it.x1) } + w / 2, mine.maxOf { maxOf(it.y0, it.y1) } + w / 2)
                boxes += b
                if (Math.abs(b[2] - b[0] - size) > 0.03 || Math.abs(b[3] - b[1] - size) > 0.03) bad("$zs: flow patch $pn${if (solid) "" else "'s pedestal"} is ${fmt(b[2] - b[0], 2)} x ${fmt(b[3] - b[1], 2)} mm, not ${fmt(size, 2)} square ($lines lines at ${fmt(sp, 3)} mm)")
                if (!solid) continue
                // its lines: the long moves, one bead spacing apart across them
                val long = mine.filter { it.len > size - w - 2 * sp - 0.05 && it.len < size - w - 2 * sp + 0.05 && (Math.abs(it.x1 - it.x0) < 1e-6 || Math.abs(it.y1 - it.y0) < 1e-6) }
                val alongX = long.count { Math.abs(it.y1 - it.y0) < 1e-6 } > long.size / 2
                val across = long.filter { if (alongX) Math.abs(it.y1 - it.y0) < 1e-6 else Math.abs(it.x1 - it.x0) < 1e-6 }.map { if (alongX) it.y0 else it.x0 }.sorted()
                if (across.size != lines) bad("$zs: flow patch $pn has ${across.size} lines, not $lines")
                else for (i in 1 until across.size) if (Math.abs(across[i] - across[i - 1] - sp) > 0.012) { bad("$zs: flow patch $pn's lines ${i} and ${i + 1} are ${fmt(across[i] - across[i - 1], 3)} mm apart, not ${fmt(sp, 3)} (the bead spacing)"); break }
            }
            // one row, low to high from the left, the gap between them
            for (k in 1 until boxes.size) {
                if (Math.abs(boxes[k][0] - boxes[k - 1][2] - gap) > 0.03 || Math.abs(boxes[k][1] - boxes[0][1]) > 0.03) bad("$zs: flow patch ${r + 1}.${k + 1} is not in row ${r + 1}, ${fmt(gap, 1)} mm after patch ${r + 1}.$k")
            }
            rows[r] = boxes
        }
        // the rows in band order from the front, each a patch and the gap behind the one before, in line with it
        val first = rows.firstEntry() ?: return
        for ((r, boxes) in rows) if (r != first.key) {
            val behind = (r - first.key) * (size + gap)
            if (Math.abs(boxes[0][0] - first.value[0][0]) > 0.03 || Math.abs(boxes[0][1] - first.value[0][1] - behind) > 0.03)
                bad("$zs: flow row ${r + 1} (band ${r + 1}'s) is not ${fmt(behind, 2)} mm behind row ${first.key + 1}, in line with it (its first patch at x ${fmt(boxes[0][0], 2)}, y ${fmt(boxes[0][1], 2)})")
        }
        // the marker: on the bed (as tall as a patch on the bed would be), in front of the front row's patch at the current ratio, centered on it
        val mark = segs.filter { it.cell.endsWith("mark") }
        if (layer > f.layers) return
        if (f.marked in f.ratios.indices) {
            val front = rows[0] ?: run { bad("$zs: no front row of flow patches (band 1's) to mark"); return }
            if (mark.isEmpty()) bad("$zs: no marker in front of flow patch 1.${f.marked + 1} (the current ratio ${f.ratios[f.marked]})")
            else {
                val cx = (mark.minOf { minOf(it.x0, it.x1) } + mark.maxOf { maxOf(it.x0, it.x1) }) / 2
                val pb = front[f.marked]
                if (Math.abs(cx - (pb[0] + pb[2]) / 2) > 0.05 || mark.maxOf { maxOf(it.y0, it.y1) } > pb[1]) bad("$zs: the marker is not in front of flow patch 1.${f.marked + 1}, centered (its center x ${fmt(cx, 2)}, the patch's ${fmt((pb[0] + pb[2]) / 2, 2)})")
            }
        } else if (mark.isNotEmpty()) bad("$zs: a marker, but no patch is at the current ratio")
    }

    /**
     * The overhang ledges on the layer at [zUm], from the g-code's outermost lines: how far each ledge's front
     * stands out of the spine's own front (measured between the ledges, on the same layer) against what this
     * check works out itself: in a band's read part, [i] layers in, [i] times its share of the outer wall's
     * width (so each layer hangs exactly one share past the one below); elsewhere flush.
     */
    private fun ledgeGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit) {
        val lg = spec.ledges ?: return
        val zs = "z ${zUm / 1000.0}"
        val mine = segs.filter { it.cell.endsWith("ledges") && Math.abs(it.y1 - it.y0) < 1e-6 }
        if (mine.isEmpty()) { bad("$zs: no overhang spine"); return }
        val w = lineW(spec, zUm)
        val x0 = mine.minOf { minOf(it.x0, it.x1) } - w / 2
        val len = PlateGeometry.ledgeLen / 1000.0; val gap = PlateGeometry.ledgeGap / 1000.0; val end = PlateGeometry.ledgeEnd / 1000.0
        val cells = lg.shares.indices.map { k -> (x0 + end + k * (len + gap)).let { it to it + len } }
        // the frontmost line crossing the middle of [a, b] (a flush front is one long line)
        fun frontAt(a: Double, b: Double) = ((a + b) / 2).let { m -> mine.filter { s -> minOf(s.x0, s.x1) <= m && maxOf(s.x0, s.x1) >= m }.minOfOrNull { it.y0 } }
        // the spine's own front: in the gaps between the ledges
        val spine = (0 until cells.size - 1).mapNotNull { k -> frontAt(cells[k].second + 0.3, cells[k + 1].first - 0.3) }.minOrNull()
            ?: run { bad("$zs: the overhang spine has no front between its ledges"); return }
        val band = spec.readBand(zUm)
        val i = band?.let { (zUm - it.fromUm) / spec.layerUm } ?: 0
        for ((k, c) in cells.withIndex()) {
            val want = Math.round(i * lg.shares[k] * spec.widthUm) / 1000.0
            val front = frontAt(c.first + 0.3, c.second - 0.3) ?: run { bad("$zs: ledge ${k + 1} has no front"); continue }
            val got = spine - front
            if (Math.abs(got - want) > 0.012) bad("$zs: ledge ${k + 1} (share ${lg.shares[k]}) stands ${fmt(got, 3)} mm out of the spine, not ${fmt(want, 3)} (${i} layer(s) into ${band?.let { "band ${it.index + 1}" } ?: "no read band"})")
        }
    }

    /**
     * The bridges on the layer at [zUm], from the g-code's own lines: the piers (clustered by x) each
     * [PlateGeometry.pierW] wide, the first at the post's left edge, the gaps between them the specification's
     * spans in order; the row (piers and decks together) as deep as this check works out for the terraces (a
     * deck depth per band at the front and at the back, [PlateGeometry.deckGap] between the innermost two), the
     * same row on every layer as on the first ([row]: front, back; NaN until the first layer sets it). On a deck
     * layer (band k's top read layer) each deck's lines running from the first pier's left edge to the last one's
     * right edge (so nothing holds them between), as many as this check works out for a deck about
     * [PlateGeometry.deckAbout] deep, exactly the bead spacing apart; the front deck k deck depths behind the row's
     * front, the back deck k deck depths in front of its back; every pier in pieces leaving exactly those two
     * strips (elsewhere one piece the row's depth). Each deck as laid goes into [laid] by band, for the check that
     * no lower band's deck lies under a higher one's.
     */
    private fun bridgeGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit,
                               row: DoubleArray, laid: MutableMap<Int, MutableList<Pair<String, DoubleArray>>>) {
        val br = spec.bridges ?: return
        val zs = "z ${zUm / 1000.0}"
        val w = lineW(spec, zUm); val h = spec.layerUm / 1000.0
        val sp = w - h * (1 - Math.PI / 4)
        // a deck's depth, by this check: decks are laid in the plate's own lines (never on the first layer)
        val w0 = spec.widthUm / 1000.0; val sp0 = w0 - h * (1 - Math.PI / 4)
        val lines = maxOf(2, Math.floor((PlateGeometry.deckAbout / 1000.0 - w0) / sp0 + 1e-9).toInt() + 1)
        val deckD = (lines - 1) * sp0 + w0
        val piers = segs.filter { it.cell.endsWith("pier") }
        if (piers.isEmpty()) { bad("$zs: no piers"); return }
        // clusters along x: a new pier where the next segment starts more than a millimetre past the last one's reach
        val clusters = ArrayList<DoubleArray>()
        for (s in piers.sortedBy { minOf(it.x0, it.x1) }) {
            val a = minOf(s.x0, s.x1); val b = maxOf(s.x0, s.x1)
            val last = clusters.lastOrNull()
            if (last != null && a <= last[1] + 1.0) last[1] = maxOf(last[1], b) else clusters += doubleArrayOf(a, b)
        }
        val edges = clusters.map { doubleArrayOf(it[0] - w / 2, it[1] + w / 2) }
        if (edges.size != br.spansUm.size + 1) { bad("$zs: ${edges.size} piers, not ${br.spansUm.size + 1}"); return }
        val pw = PlateGeometry.pierW / 1000.0
        for ((k, e) in edges.withIndex()) if (Math.abs(e[1] - e[0] - pw) > 0.03) bad("$zs: pier ${k + 1} is ${fmt(e[1] - e[0], 2)} mm wide, not ${fmt(pw, 2)}")
        for (k in br.spansUm.indices) {
            val gap = edges[k + 1][0] - edges[k][1]
            if (Math.abs(gap - br.spansUm[k] / 1000.0) > 0.03) bad("$zs: span ${k + 1} (between piers ${k + 1} and ${k + 2}) is ${fmt(gap, 2)} mm, not ${fmt(br.spansUm[k] / 1000.0, 2)}")
        }
        // the row: everything the piers and decks lay, front to back; as deep as the terraces make it, where the first layer put it
        val rowSegs = segs.filter { it.cell.endsWith("pier") || it.cell.startsWith("deck ") }
        val py0 = rowSegs.minOf { minOf(it.y0, it.y1) } - w / 2; val py1 = rowSegs.maxOf { maxOf(it.y0, it.y1) } + w / 2
        val wantDepth = 2 * spec.bands.size * deckD + PlateGeometry.deckGap / 1000.0
        if (Math.abs(py1 - py0 - wantDepth) > 0.03) bad("$zs: the bridge row is ${fmt(py1 - py0, 2)} mm deep, not ${fmt(wantDepth, 2)} (${spec.bands.size} bands' deck strips of ${fmt(deckD, 3)} mm at the front and the back, ${fmt(PlateGeometry.deckGap / 1000.0, 1)} mm between)")
        if (row[0].isNaN()) { row[0] = py0; row[1] = py1 }
        else if (Math.abs(py0 - row[0]) > 0.03 || Math.abs(py1 - row[1]) > 0.03) bad("$zs: the bridge row spans y ${fmt(py0, 2)}..${fmt(py1, 2)}, not ${fmt(row[0], 2)}..${fmt(row[1], 2)} as on the first layer")
        // nothing between the piers but the decks themselves: anything else there would hold a deck up
        for (k in br.spansUm.indices) {
            val a = edges[k][1]; val b = edges[k + 1][0]
            val under = segs.filter { !it.cell.startsWith("deck ") && (it.x0 + it.x1) / 2 > a && (it.x0 + it.x1) / 2 < b && (it.y0 + it.y1) / 2 > py0 && (it.y0 + it.y1) / 2 < py1 }
            if (under.isNotEmpty()) bad("$zs: ${under.size} line(s) of '${under.first().cell}' in span ${k + 1}, under where the decks bridge")
        }
        // the deck strips this layer must leave (band k's top read layer: strip k from the front and from the back)
        val band = spec.bands.firstOrNull { it.readToUm == zUm }?.index
        val strips = band?.let { k -> listOf(py0 + k * deckD to py0 + (k + 1) * deckD, py1 - (k + 1) * deckD to py1 - k * deckD) }
        // every pier in pieces: the row less this layer's deck strips (one piece the row's depth elsewhere), by its own lines
        val wantPieces = if (strips == null) listOf(py0 to py1)
            else listOf(py0 to strips[0].first, strips[0].second to strips[1].first, strips[1].second to py1).filter { it.second - it.first > 0.01 }
        for ((k, e) in edges.withIndex()) {
            val mine = piers.filter { minOf(it.x0, it.x1) >= e[0] - 0.01 && maxOf(it.x0, it.x1) <= e[1] + 0.01 }.sortedBy { minOf(it.y0, it.y1) }
            // pieces along y: a new one where a line starts more than a millimetre past the last one's reach
            val pieces = ArrayList<DoubleArray>()
            for (s in mine) {
                val a = minOf(s.y0, s.y1); val b = maxOf(s.y0, s.y1)
                val last = pieces.lastOrNull()
                if (last != null && a <= last[1] + 1.0) last[1] = maxOf(last[1], b) else pieces += doubleArrayOf(a, b)
            }
            val got = pieces.map { (it[0] - w / 2) to (it[1] + w / 2) }
            val ok = got.size == wantPieces.size && got.zip(wantPieces).all { (g, wnt) -> Math.abs(g.first - wnt.first) <= 0.03 && Math.abs(g.second - wnt.second) <= 0.03 }
            if (!ok) { bad("$zs: pier ${k + 1} lies at y ${got.joinToString { "${fmt(it.first, 2)}..${fmt(it.second, 2)}" }}, not ${wantPieces.joinToString { "${fmt(it.first, 2)}..${fmt(it.second, 2)}" }}${band?.let { " (band ${it + 1}'s deck strips left free)" } ?: " (the whole row)"}"); break }
        }
        if (band == null || strips == null) return
        val runFrom = edges.first()[0] + w / 2; val runTo = edges.last()[1] - w / 2
        for (d in 1..2) {
            val name = if (d == 1) "front" else "back"
            val deck = segs.filter { it.cell == "deck $d" && Math.abs(it.y1 - it.y0) < 1e-6 && Math.abs(it.len - (runTo - runFrom)) < 0.05 }
            val ys = deck.map { it.y0 }.sorted()
            if (ys.size != lines) { bad("$zs: deck $d has ${ys.size} lines across the whole row, not $lines"); continue }
            for (i in 1 until ys.size) if (Math.abs(ys[i] - ys[i - 1] - sp) > 0.012) { bad("$zs: deck $d's lines ${i} and ${i + 1} are ${fmt(ys[i] - ys[i - 1], 3)} mm apart, not ${fmt(sp, 3)}"); break }
            if (deck.any { Math.abs(minOf(it.x0, it.x1) - runFrom) > 0.03 || Math.abs(maxOf(it.x0, it.x1) - runTo) > 0.03 }) bad("$zs: deck $d's lines do not run from the first pier to the last")
            val got = doubleArrayOf(ys.first() - w / 2, ys.last() + w / 2)
            val want = strips[d - 1]
            if (Math.abs(got[0] - want.first) > 0.03 || Math.abs(got[1] - want.second) > 0.03)
                bad("$zs: band ${band + 1}'s $name deck (deck $d) lies at y ${fmt(got[0], 2)}..${fmt(got[1], 2)}, not its terrace strip ${fmt(want.first, 2)}..${fmt(want.second, 2)} (strip ${band + 1} from the $name)")
            laid.getOrPut(band) { ArrayList() } += name to got
        }
    }

    /**
     * The stringing ring on the layer at [zUm], from the g-code's own lines: one more post than the hops, each round and
     * [PlateGeometry.ringPost] across (its outermost line plus half a line), every two neighbors (the last and post 1 too) a post
     * and [PlateGeometry.ringPostGap] apart center to center, so every gap is the same; the posts numbered counter-clockwise round
     * the ring's middle; the open gap (from the last post back to post 1) the frontmost gap.
     */
    private fun ringGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit) {
        val st = spec.strings ?: return
        if (zUm <= spec.baseLayers * spec.layerUm) return
        val zs = "z ${zUm / 1000.0}"
        val w = lineW(spec, zUm)
        val ringPost = PlateGeometry.ringPost / 1000.0; val pitch = (PlateGeometry.ringPost + PlateGeometry.ringPostGap) / 1000.0
        val centers = ArrayList<DoubleArray>()
        for (j in 0 until st.posts) {
            val ss = segs.filter { it.cell.removePrefix("adhesion:") == "post ${j + 1}" }
            if (ss.isEmpty()) { bad("$zs: the stringing ring has no post ${j + 1}"); return }
            val cx = (ss.minOf { minOf(it.x0, it.x1) } + ss.maxOf { maxOf(it.x0, it.x1) }) / 2; val cy = (ss.minOf { minOf(it.y0, it.y1) } + ss.maxOf { maxOf(it.y0, it.y1) }) / 2
            val d = 2 * (ss.maxOf { s -> maxOf(Math.hypot(s.x0 - cx, s.y0 - cy), Math.hypot(s.x1 - cx, s.y1 - cy)) } + w / 2)
            if (Math.abs(d - ringPost) > 0.03) bad("$zs: stringing post ${j + 1} is ${fmt(d, 3)} mm across, not ${fmt(ringPost, 3)}")
            centers += doubleArrayOf(cx, cy)
        }
        val n = centers.size
        val mx = centers.sumOf { it[0] } / n; val my = centers.sumOf { it[1] } / n
        for (j in 0 until n) {
            val a = centers[j]; val b = centers[(j + 1) % n]
            val gap = Math.hypot(b[0] - a[0], b[1] - a[1]) - ringPost
            val name = if (j == n - 1) "the open gap (post $n to post 1)" else "hop ${j + 1} (post ${j + 1} to post ${j + 2})"
            if (Math.abs(gap - PlateGeometry.ringPostGap / 1000.0) > 0.03) bad("$zs: the stringing ring's $name is ${fmt(gap, 2)} mm, not ${fmt(PlateGeometry.ringPostGap / 1000.0, 2)} (centers ${fmt(pitch, 2)} apart)")
            if ((a[0] - mx) * (b[1] - my) - (a[1] - my) * (b[0] - mx) <= 0) bad("$zs: stringing post ${(j + 1) % n + 1} is not the next counter-clockwise after post ${j + 1}")
        }
        // the open gap at the front: its middle the frontmost of the gaps' middles
        val mids = (0 until n).map { j -> (centers[j][1] + centers[(j + 1) % n][1]) / 2 }
        if (mids.indexOf(mids.min()) != n - 1) bad("$zs: the stringing ring's open gap (post $n to post 1) is not at its front")
    }

    /**
     * The line-width tubes on the layer at [zUm], from the g-code's own lines: each tube's line a square whose
     * center line is [PlateGeometry.tube] less its own width (so its outside is [PlateGeometry.tube] whatever the
     * width: the line's width, read from where it is laid), in a row from the first tube [PlateGeometry.tubeGap] apart,
     * their fronts in line.
     */
    private fun tubeGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit) {
        val tb = spec.tubes ?: return
        if (zUm > tb.layers * spec.layerUm) return
        val zs = "z ${zUm / 1000.0}"
        val size = PlateGeometry.tube / 1000.0; val gap = PlateGeometry.tubeGap / 1000.0
        var x0 = Double.NaN; var y0 = Double.NaN
        for (k in tb.widthsUm.indices) {
            val wk = tb.widthsUm[k] / 1000.0
            val mine = segs.filter { it.cell.removePrefix("adhesion:") == "tube ${k + 1}" }
            if (mine.isEmpty()) { bad("$zs: no tube ${k + 1}"); continue }
            val cx0 = mine.minOf { minOf(it.x0, it.x1) }; val cx1 = mine.maxOf { maxOf(it.x0, it.x1) }
            val cy0 = mine.minOf { minOf(it.y0, it.y1) }; val cy1 = mine.maxOf { maxOf(it.y0, it.y1) }
            if (Math.abs(cx1 - cx0 - (size - wk)) > 0.02 || Math.abs(cy1 - cy0 - (size - wk)) > 0.02)
                bad("$zs: tube ${k + 1}'s line runs ${fmt(cx1 - cx0, 3)} x ${fmt(cy1 - cy0, 3)} mm, not ${fmt(size - wk, 3)} square (${fmt(size, 1)} outside, a ${fmt(wk, 3)} mm line)")
            // the row: from the first tube's left edge, a tube and the gap each, the fronts in line
            if (x0.isNaN()) { x0 = cx0 - wk / 2 - k * (size + gap); y0 = cy0 - wk / 2 }
            if (Math.abs(cx0 - wk / 2 - (x0 + k * (size + gap))) > 0.03 || Math.abs(cy0 - wk / 2 - y0) > 0.03)
                bad("$zs: tube ${k + 1} starts at x ${fmt(cx0 - wk / 2, 2)}, y ${fmt(cy0 - wk / 2, 2)}, not ${fmt(x0 + k * (size + gap), 2)}, ${fmt(y0, 2)} (in the row, ${fmt(gap, 1)} mm apart)")
        }
    }

    /**
     * The fit block and peg on the layer at [zUm], from the g-code's own lines: each hole's size, as the line
     * closest to where its center should be (worked out from the block's own outline and [PlateGeometry]'s
     * spacing) less half a line (the wall line runs outside the hole's edge), the specification's hole; the peg's size, as its outermost line plus half a line,
     * the specification's peg. As drawn: a hole or peg off by a fit's worth (0.1 mm) is caught.
     */
    private fun fitGeometry(spec: CalibrationSpec, zUm: Long, segs: List<Seg>, bad: (String) -> Unit) {
        val f = spec.fit ?: return
        val zs = "z ${zUm / 1000.0}"
        val w = lineW(spec, zUm)
        val block = segs.filter { it.cell.removePrefix("adhesion:") == "fit block" }
        if (zUm > f.fromLayer * spec.layerUm && zUm <= (f.fromLayer + f.blockLayers) * spec.layerUm) {
            if (block.isEmpty()) { bad("$zs: no fit block"); return }
            val bx0 = block.minOf { minOf(it.x0, it.x1) } - w / 2; val by0 = block.minOf { minOf(it.y0, it.y1) } - w / 2; val by1 = block.maxOf { maxOf(it.y0, it.y1) } + w / 2
            val maxHole = f.holesUm.max() / 1000.0; val wall = PlateGeometry.fitWall / 1000.0
            for (k in f.holesUm.indices) {
                val cx = bx0 + wall + k * (maxHole + wall) + maxHole / 2; val cy = (by0 + by1) / 2
                // the hole's wall line runs half a line outside its edge
                val near = block.minOfOrNull { s -> Math.hypot((s.x0 + s.x1) / 2 - cx, (s.y0 + s.y1) / 2 - cy) } ?: continue
                val d = 2 * (near - w / 2)
                if (Math.abs(d - f.holesUm[k] / 1000.0) > 0.03) bad("$zs: hole ${k + 1} is ${fmt(d, 3)} mm across, not ${fmt(f.holesUm[k] / 1000.0, 3)}")
            }
        }
        val peg = segs.filter { it.cell.removePrefix("adhesion:") == "peg" }
        if (zUm <= f.pegLayers * spec.layerUm) {
            if (peg.isEmpty()) { bad("$zs: no peg"); return }
            val cx = (peg.minOf { minOf(it.x0, it.x1) } + peg.maxOf { maxOf(it.x0, it.x1) }) / 2; val cy = (peg.minOf { minOf(it.y0, it.y1) } + peg.maxOf { maxOf(it.y0, it.y1) }) / 2
            val far = peg.maxOf { s -> maxOf(Math.hypot(s.x0 - cx, s.y0 - cy), Math.hypot(s.x1 - cx, s.y1 - cy)) }
            val d = 2 * (far + w / 2)
            if (Math.abs(d - f.pegUm / 1000.0) > 0.03) bad("$zs: the peg is ${fmt(d, 3)} mm across, not ${fmt(f.pegUm / 1000.0, 3)}")
        }
    }

    /**
     * The stringing ring's hops, layer by layer (above the adhesion layer): the posts visited in order, 1 to the last, each
     * once (so the open gap, from the last back to post 1, is never crossed between them); and before each post after the
     * first exactly one retraction before its first line, of its hop's length (hop k's before post k + 1). Read from the
     * g-code text: a post's cell begins before its travel and retraction.
     */
    fun ringRetractions(spec: CalibrationSpec, gcode: String): List<String> {
        val st = spec.strings ?: return emptyList()
        val out = ArrayList<String>()
        fun bad(s: String) { if (out.size < 20) out += s }
        val lines = gcode.lines()
        var layer = -1
        val order = ArrayList<Int>()
        fun endLayer() { if (order.isNotEmpty() && order != (1..st.posts).toList()) bad("layer $layer: the stringing ring's posts in the order ${order.joinToString()}, not 1 to ${st.posts}"); order.clear() }
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { endLayer(); layer++; continue }
            val m = Regex("; CELL: post (\\d+)").matchEntire(l.trim()) ?: continue
            val post = m.groupValues[1].toInt()
            if (post in order) { if (order.last() != post) bad("layer $layer: back to stringing post $post after post ${order.last()}"); continue }
            order += post
            if (post == 1) continue
            val want = st.lengthsMm[post - 2]
            val pulls = ArrayList<Double>()
            for (j in i + 1 until lines.size) {
                val c = lines[j].substringBefore(';').trim()
                if (Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(c)) break
                Regex("G1 E-([0-9.]+).*").matchEntire(c)?.let { pulls += it.groupValues[1].toDouble() }
            }
            if (pulls.size != 1 || Math.abs(pulls[0] - want) > 1e-3)
                bad("layer $layer: before stringing post $post (hop ${post - 1}), retraction(s) ${pulls.joinToString { fmt(it, 3) }} mm, not one of ${fmt(want, 3)} mm")
        }
        endLayer()
        return out
    }

    /**
     * Whether a cell is the plate's frame (read for nothing) above the first layer, by its label alone: a base, a melt body or
     * floor, a pier, a pedestal, the ruler post, the flow marker. On the first layer they are "adhesion:" cells.
     */
    fun isFrameCell(label: String): Boolean = label.startsWith("base ") || label.startsWith("melt body ") || label.startsWith("melt floor ") || label == "pier" ||
        label == "pedestal" || label.startsWith("pedestal ") || label == "post" || label == "mark"

    /**
     * The frame's motion by the g-code alone (the visible-line cap does not apply to a calibration plate): every frame
     * move above the first layer ([isFrameCell]) at the machine's print speed
     * [printVelocity] (mm/s) held to the filament's maximum flow [maxFlow] (mm³/s), and at the machine's acceleration [accel]
     * (mm/s²). The flow cap is worked out per cell from the filament the g-code feeds per mm of its lines ([filamentDiameterMm]),
     * not from the plate's bead model or its specification's frame values, so a frame written at another speed fails here
     * whatever the specification says. Also fails when the g-code has no frame moves at all (absence is not a pass).
     */
    fun frameMotion(gcode: String, tool: Int, printVelocity: Double, accel: Double, maxFlow: Double, filamentDiameterMm: Double): List<String> {
        val area = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
        val moves = parse(gcode, tool).filter { isFrameCell(it.cell) }
        if (moves.isEmpty()) return listOf("the frame: no frame moves above the first layer (no base, body, pier, pedestal, post, or marker cell)")
        val problems = ArrayList<String>()
        for ((cell, mvs) in moves.groupBy { it.cell }) {
            // the line's cross-section as fed (mm²: filament per mm x the filament's area), over all the cell's lines
            val fed = mvs.sumOf { it.e } / mvs.sumOf { it.lenMm } * area
            val want = Math.min(printVelocity, maxFlow / fed)
            val tol = Math.max(1.0 / 60 + 1e-9, want * 0.002)
            mvs.firstOrNull { Math.abs(it.fMmMin / 60 - want) > tol }?.let {
                problems += "the frame: z ${it.zUm / 1000.0} '$cell' at ${fmt(it.fMmMin / 60, 2)} mm/s, not ${fmt(want, 2)} (the machine's print speed ${fmt(printVelocity, 2)}" +
                    (if (want < printVelocity) ", held to the filament's maximum flow ${fmt(maxFlow, 2)} mm3/s for a ${fmt(fed, 4)} mm2 line" else "") + ")"
            }
            mvs.firstOrNull { Math.abs(it.accel - accel) > 0.5 }?.let { problems += "the frame: z ${it.zUm / 1000.0} '$cell' at ${fmt(it.accel)} mm/s2, not the machine's ${fmt(accel)}" }
        }
        return problems
    }

    /**
     * The first layer's motion by the g-code alone (every move of a calibration plate within the filament's maximum
     * flow, the first layer included; a 3.5 mm³/s TPU's first layer had run 7.3 at the configured 80 mm/s): every first-layer
     * move ("adhesion:" cells) no faster than the configured first-layer speed [firstLayerSpeed] (mm/s), and its flow (its
     * speed times the cross-section the g-code feeds, from [filamentDiameterMm]) within [maxFlow] (mm³/s). Fails when the
     * g-code has no first-layer moves at all.
     */
    fun firstLayerFlow(gcode: String, tool: Int, firstLayerSpeed: Double, maxFlow: Double, filamentDiameterMm: Double): List<String> {
        val area = Math.PI * filamentDiameterMm * filamentDiameterMm / 4
        val moves = parse(gcode, tool).filter { it.cell.startsWith("adhesion:") }
        if (moves.isEmpty()) return listOf("the first layer: no first-layer moves (no 'adhesion:' cell)")
        val problems = ArrayList<String>()
        for ((cell, mvs) in moves.groupBy { it.cell }) {
            mvs.firstOrNull { it.fMmMin / 60 > firstLayerSpeed + 1.0 / 60 + 1e-9 }?.let {
                problems += "the first layer: '$cell' at ${fmt(it.fMmMin / 60, 2)} mm/s, past the configured first-layer speed ${fmt(firstLayerSpeed, 2)}"
            }
            mvs.firstOrNull { it.fMmMin / 60 * it.e / it.lenMm * area > maxFlow * 1.002 + 1e-6 }?.let {
                problems += "the first layer: '$cell' at ${fmt(it.fMmMin / 60 * it.e / it.lenMm * area, 2)} mm3/s (${fmt(it.fMmMin / 60, 2)} mm/s), past the filament's maximum flow ${fmt(maxFlow, 2)} mm3/s"
            }
        }
        return problems
    }

    /** The values a cell prints with, by the specification. */
    private fun values(spec: CalibrationSpec, cell: String): PathValues = when {
        cell in setOf("adhesion:post", "adhesion:mark", "adhesion:ledges", "adhesion:pier") || cell.startsWith("adhesion:flow ") -> spec.adhesion
        cell.startsWith("adhesion:melt body ") || cell.startsWith("adhesion:melt floor ") -> spec.adhesion
        cell.startsWith("melt body ") || cell.startsWith("melt floor ") -> spec.frame
        meltStrip(cell) != null -> spec.melt!!.let { mt -> PathValues(mt.speeds[meltStrip(cell)!![1]], mt.accel, spec.flowRatio, spec.retractMm) }
        cell == "ledges" -> spec.ledges!!.let { PathValues(it.speed, it.accel, spec.flowRatio, spec.retractMm) }
        cell == "pier" -> spec.frame
        cell == "pedestal" || cell.startsWith("pedestal ") -> spec.frame
        cell == "adhesion:pedestal" || cell.startsWith("adhesion:pedestal ") -> spec.adhesion
        cell.startsWith("adhesion:post ") || cell.startsWith("adhesion:tube ") || cell == "adhesion:fit block" || cell == "adhesion:peg" -> spec.adhesion
        cell == "fit block" || cell == "peg" -> spec.fit!!.let { PathValues(it.speed, it.accel, spec.flowRatio, spec.retractMm) }
        cell.startsWith("tube ") -> spec.tubes!!.let { PathValues(it.speed, it.accel, spec.flowRatio, spec.retractMm) }
        cell.startsWith("post ") -> spec.strings!!.let { PathValues(it.speed, it.accel, spec.flowRatio, it.retractBefore(cell.removePrefix("post ").toInt() - 1, spec.retractMm)) }
        cell.startsWith("adhesion:base ") -> spec.adhesion
        cell.startsWith("base ") -> spec.frame
        cell.startsWith("deck ") -> spec.bridges!!.let { PathValues(it.speed, it.accel, it.flows[cell.removePrefix("deck ").toInt() - 1], spec.retractMm) }
        cell == "post" || cell == "mark" -> spec.frame
        cell.startsWith("flow ") -> spec.flow!!.let { f -> PathValues(f.speed, f.accel, f.ratios[cell.substringAfter('.').toInt() - 1], spec.retractMm) }
        else -> error("no cell '$cell' in the specification")
    }

    /**
     * Extrusion moves inside cells (`; CELL: <label>` .. `; CELL END`) with the state they ran in.
     * Extrusion outside a cell is the machine's own start and end templates (a prime line), except
     * between the first cell and the last: that is a path the specification does not have, and it is
     * returned under the cell name "(none)". Relative extrusion (M83), as plymeld writes.
     */
    /** [gcode] with every Z (the moves' and the layer comments') lowered by [shiftUm] µm; unchanged when it is 0. */
    fun unshift(gcode: String, shiftUm: Long): String {
        if (shiftUm == 0L) return gcode
        fun z(v: String) = String.format(java.util.Locale.ROOT, "%.3f", v.toDouble() - shiftUm / 1000.0).trimEnd('0').trimEnd('.')
        val move = Regex("""^(G[01]\b[^;]*?\sZ)(-?\d*\.?\d+)""")
        val layer = Regex("""^(;Z:|; Z_HEIGHT:)\s*(-?\d*\.?\d+)""")
        return gcode.lineSequence().joinToString("\n") { l ->
            move.find(l)?.let { m -> l.replaceRange(m.range, m.groupValues[1] + z(m.groupValues[2])) }
                ?: layer.find(l)?.let { m -> l.replaceRange(m.range, m.groupValues[1] + z(m.groupValues[2])) } ?: l
        }
    }

    private fun parse(gcode: String, tool: Int): List<Move> {
        val inCells = ArrayList<Move>()
        val outside = ArrayList<Pair<Int, Move>>()     // (cells seen before it, move)
        var cellsClosed = 0
        var cell: String? = null
        var x = Double.NaN; var y = Double.NaN; var zUm = 0L
        var f = 0.0; var accel = Double.NaN; var fan = 0.0
        val temps = HashMap<Int, Int>()
        // by tool: the temperature last waited for (M109), dropped when an M104 sets another
        val reached = HashMap<Int, Int>()
        var curTool = 0
        var layer = -1
        var layerZUm = 0L
        var width = Double.NaN
        for (raw in gcode.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith(";LAYER_CHANGE") || line.startsWith("; CHANGE_LAYER")) { layer++; continue }
            // the layer's own height (its top), as the layer change states it: a move may print at its own height on it
            layerZOf(line)?.let { layerZUm = it; continue }
            if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: ").trim(); width = Double.NaN; continue }
            if (line == "; CELL END") { cell = null; cellsClosed++; continue }
            // the width the g-code declares for the path that follows (a cell's own: none carried over from the last cell)
            when {
                line.startsWith("; LINE_WIDTH:") -> { width = line.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
                line.startsWith(";WIDTH:") -> { width = line.removePrefix(";WIDTH:").trim().toDouble(); continue }
            }
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
                        val mv = Move(cell ?: "(none)", zUm, len, e, f, accel, temps[tool], fan, layer, width, reached[tool], layerZUm)
                        if (cell != null) inCells += mv else outside += cellsClosed to mv
                    }
                    x = nx; y = ny
                }
                "M204" -> num('S')?.let { accel = it }
                "SET_VELOCITY_LIMIT" -> words.firstOrNull { it.startsWith("ACCEL=") }?.let { accel = it.removePrefix("ACCEL=").toDouble() }
                "M104", "M109" -> { val t = num('T')?.toInt() ?: curTool; num('S')?.let { c ->
                    temps[t] = c.toInt()
                    if (words[0] == "M109") reached[t] = c.toInt() else if (reached[t] != c.toInt()) reached.remove(t)
                } }
                "M106" -> fan = (num('S') ?: 255.0) / 255 * 100
                "M107" -> fan = 0.0
                else -> if (words[0].length >= 2 && words[0][0] == 'T' && words[0].substring(1).all { it.isDigit() }) curTool = words[0].substring(1).toInt()
            }
        }
        // outside every cell but after the first one closed and before the last one closed: inside the plate
        return inCells + outside.filter { (closed, _) -> closed in 1 until cellsClosed }.map { it.second }
    }

    /** A layer change's stated height (µm), from `;Z:` or `; Z_HEIGHT:`, or null (not such a line). */
    private fun layerZOf(line: String): Long? = when {
        line.startsWith(";Z:") -> line.removePrefix(";Z:")
        line.startsWith("; Z_HEIGHT:") -> line.removePrefix("; Z_HEIGHT:")
        else -> null
    }?.trim()?.toDoubleOrNull()?.let { Math.round(it * 1000) }

    private fun box(b: DoubleArray) = "x ${fmt(b[0], 1)}..${fmt(b[2], 1)}, y ${fmt(b[1], 1)}..${fmt(b[3], 1)}"

    private fun fmt(v: Double, d: Int = 0) = String.format(java.util.Locale.ROOT, "%.${d}f", v)
}
