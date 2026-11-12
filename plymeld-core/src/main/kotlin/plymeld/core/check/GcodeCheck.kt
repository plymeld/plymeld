package plymeld.core.check

import java.util.Locale

/**
 * Reads plymeld's own g-code back as the printer would and checks it against what the config says the
 * print must be, whatever made it (calibration plates and slice
 * output alike). It knows the config's numbers, never
 * the program the writer was given. Checks, each with the first offending line:
 *
 *  - machine: every move on the bed and under its height; printing no faster than its print speed,
 *    travel no faster than its travel speed (moves in Z alone excepted: the firmware holds Z to its
 *    own limit); every acceleration within its limit;
 *  - heights: every extrusion on its layer's height (an outer wall's scarf ramp, [Expect.scarfStart], down to its
 *    starting thickness above the layer's bottom); nothing below the layer being printed (the
 *    exceptions: cells the caller names print at their own height: [Expect.ownZ], a calibration plate's
 *    first-layer tiles, on the first layer and only there; [Expect.standing], the melt plate's strips, on
 *    any layer, a move going no lower than the lowest of them on its layer; [Expect.zaa], Z anti-aliasing: a line
 *    on the model's own top where it lies within the layer, all along the line, and nowhere else);
 *  - retraction: nothing printed while retracted; every retraction (wipe moves included) restored in
 *    full and no more; each one a configured length. Where the config spreads a restore over the path's start
 *    ([Expect.returnSpreadMm]) the standing restore may be part of it, the rest going with the lines after it (how much
 *    is not read back: a line's own plastic and the restore's are one E);
 *  - extrusion: no printing move past the tool's largest cross-section ([Expect.extrudeSectionMaxMm2]), as the firmware
 *    refuses it;
 *  - travels: none at or below the height of a visible line (outer wall, top or bottom surface, ironing)
 *    already printed on that layer, across it;
 *  - start: the start template's own lines in order before the first layer, no placeholder left in
 *    any command, the nozzle heated (M109) before the first line is printed, relative extrusion (M83) set
 *    before anything is extruded;
 *  - end: the end template's own lines in order after the last line printed, and the head above
 *    the print before it moves sideways;
 *  - tools: every line printed by a tool the config's loadout gives the print ([Expect.allowedTools]), and every
 *    tool a part is assigned ([Expect.partTools]) printing something (which part each line belongs to is ModelCheck's);
 *  - header: a filament-use header (`; filament used [mm] = ...`, `; <filament id="n" ... used_m="..." />`, the
 *    Creator 5's) giving each tool the filament its T-selected lines actually push (id n = T(n-1)).
 *
 * The start and end templates' moves are not judged by the body's rules (the prime line extrudes
 * without a retraction to restore, and the end may retract without restoring).
 */
object GcodeCheck {
    /** The Z step the writer prints to (mm): three decimals (KlipperWriter's number format). */
    private const val Z_STEP = 0.001

    class Expect(
        val bedMinX: Double, val bedMinY: Double, val bedMaxX: Double, val bedMaxY: Double, val maxZ: Double,
        /** mm/s: printing (hardware.machine.motion.print.speed) and travel (hardware.machine.motion.travel.speed) limits; mm/s²: acceleration. */
        val maxPrintSpeed: Double, val maxTravelSpeed: Double, val maxAccel: Double,
        /** Retraction lengths the config gives (mm): every retraction must be one of them. */
        val retractLengths: Set<Double>,
        val startTemplate: String, val endTemplate: String,
        /**
         * Whether a point at a layer (x, y, layer top, layer height) is on the model's outer surface
         * (ModelCheck.Surface); a crossed wall elsewhere is a hidden one (a part inside another). Null:
         * every outer wall counts as visible (a print whose walls are all on its surface, e.g. the calibration plate).
         */
        val onSurface: ((Double, Double, Double, Double) -> Boolean)? = null,
        /**
         * Cells (`; CELL: <label>` in the g-code) that print at their own height (mm) on the FIRST layer rather than
         * the layer's: a calibration plate's first-layer tiles. On the first layer such a cell's lines must be at
         * exactly that height, and its moves may go down to it; on any other layer, and for every other cell, the
         * layer's own rule holds.
         */
        val ownZ: Map<String, Double> = emptyMap(),
        /**
         * Cells that print at their own height (mm) on whatever layer holds them: a calibration plate's melt strips, each its
         * floor's top plus its own thickness. Their lines must be at exactly that height; a move may go down to the
         * lowest of them printed on its layer so far (the head steps from a thinner strip to the next), no lower.
         */
        val standing: Map<String, Double> = emptyMap(),
        /**
         * The tools (0-based: T0 is the tool of index 1) the config assigns the model's parts, through the machine's loadout:
         * each must print something. Null: not judged (a print with no model, e.g. a calibration plate).
         */
        val partTools: Set<Int>? = null,
        /** Every tool that may print a line (the parts' and the support's); a line by any other is refused. Null: not judged. */
        val allowedTools: Set<Int>? = null,
        /**
         * Scarf seams (plan.technique.scarf_seam.where, Scarf): an outer wall's line may print below its layer's height, down to the layer's
         * bottom plus this share of the layer (a ramp's starting thickness), and no lower. Null: no scarf, every outer wall
         * line at the layer's height.
         */
        val scarfStart: Double? = null,
        /**
         * The print has a lifted bridge, whose layers after the first may hold "Bridge tack" lines: those may lie below the layer
         * (never above it); how far below is the curved check's to judge (CurvedCheck). Without a lifted bridge a tack is refused.
         */
        val tacksAllowed: Boolean = false,
        /**
         * Z anti-aliasing (plan.faces.sloped_top.technique = "z_antialiasing"): a line may print below its flat layer where the MODEL's top lies within
         * the layer, on that top ([ZaaRule], from the model and the config), and nowhere else. Null: no line below its layer.
         */
        val zaa: ZaaRule? = null,
        /**
         * The restore after a travel may be spread over the path's first this many mm (hardware.filament.retract.return.distance,
         * where the tool's extrude.section.max is known): a standing restore may then push back only part of the retraction.
         * Null: every restore in full, standing still.
         */
        val returnSpreadMm: Double? = null,
        /** The largest cross-section (mm²) a printing move may extrude (hardware.tool.extrude.section.max). Null: not judged. */
        val extrudeSectionMaxMm2: Double? = null,
        /** The filament's cross-section (mm²), for [extrudeSectionMaxMm2]. */
        val filamentAreaMm2: Double = Math.PI * 1.75 * 1.75 / 4,
    )

    private class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val halfW: Double, val feature: String, val z: Double)

    fun check(text: String, e: Expect): List<String> {
        val out = ArrayList<String>()
        fun bad(line: Int, s: String) { if (out.size < 40) out += "line $line: $s" }
        val lines = text.lines()
        val firstLayer = lines.indexOfFirst { isLayer(it) }
        if (firstLayer < 0) return listOf("no layer markers: not a layered print")
        // the last line printed: a path's last segment may carry E0, its share of filament rounded to nothing in the file's
        // steps (a scarf's closing pass ending a micron on), and is still part of that line, not a move after it
        val lastExtrusion = lines.indices.last { isExtrusion(lines[it], orZero = true) }

        // start template: its own lines (no placeholders) in order before the first layer
        inOrder(e.startTemplate, lines.subList(0, firstLayer))?.let { bad(1, "start template line '$it' missing or out of order before the first layer") }
        inOrder(e.endTemplate, lines.subList(lastExtrusion + 1, lines.size))?.let { bad(lastExtrusion + 2, "end template line '$it' missing or out of order after the last line printed") }
        for ((i, l) in lines.withIndex()) { if (PLACEHOLDER.containsMatchIn(l.substringBefore(';'))) bad(i + 1, "placeholder left in a command: '${l.trim()}'") }

        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var f = 0.0
        var layerZ = Double.NaN; var maxLayerZ = 0.0; var layerH = 0.2
        var width = 0.4
        var feature = ""
        var tool = 0
        val heated = HashSet<Int>()
        val netE = HashMap<Int, Double>()            // mm of each filament (by number, 0-based) pushed, the whole file (relative E, as plymeld writes)
        val filOf = HashMap<Int, Int>()              // a tool's filament number after a swap by hand (; FILAMENT_SWAP), else its own
        val printedSincePause = HashSet<Int>()       // tools that printed since the last pause: a swap noted for one of them came too late
        val swappedUnheated = HashSet<Int>()         // tools whose filament was swapped and not yet waited for (M109)
        val printedBy = HashMap<Int, Int>()          // lines each tool prints in the body
        val wrongTool = HashSet<Int>()               // tools already reported for printing when the config gives them nothing
        var retracted = 0.0          // mm currently pulled back (body only)
        val printed = ArrayList<Seg>()
        var firstPrinted = false
        var liftedAfterEnd = false
        var layerIndex = -1
        var cell: String? = null
        var lowStanding = Double.NaN    // the lowest standing cell's height met on this layer so far ([Expect.standing])
        var relativeE = false; var absoluteReported = false
        for ((i, raw) in lines.withIndex()) {
            val ln = i + 1
            val l = raw.trim()
            if (isLayer(l)) { printed.clear(); layerIndex++; lowStanding = Double.NaN; continue }
            if (l.startsWith("; CELL: ")) {
                cell = l.removePrefix("; CELL: ").trim()
                e.standing[cell]?.let { lowStanding = if (lowStanding.isNaN()) it else minOf(lowStanding, it) }
                continue
            }
            if (l == "; CELL END") { cell = null; continue }
            if (l.startsWith("; PAUSE:")) { printedSincePause.clear(); continue }
            if (l.startsWith("; FILAMENT_SWAP:")) {
                plymeld.gcode.KlipperWriter.FILAMENT_SWAP_LINE.find(l)?.let { m ->
                    val t = m.groupValues[1].toInt()
                    // the swap is made at the pause: a note after the tool printed since the pause puts the old filament's lines on the new number
                    if (t in printedSincePause) bad(ln, "filament swap noted for T$t (tool ${t + 1}) after T$t printed since the last pause: the swap is made at the pause, so the note belongs right after it")
                    filOf[t] = m.groupValues[3].toInt() - 1
                    heated -= t; swappedUnheated += t
                }
                continue
            }
            // the height this move's printing must be at: the layer's, or (first layer only) its cell's own, or a standing cell's
            val own = (if (layerIndex == 0) cell?.let { e.ownZ[it] } else null) ?: cell?.let { e.standing[it] }
            val printZ = own ?: layerZ
            // a scarf ramp's lowest: the layer's bottom plus the ramp's starting thickness (outer walls only), as the file can
            // round it (half a Z step under the computed value: this is the ONE place the slack lives; every rule below compares
            // against it as it is. one rule added no slack and refused a Benchy for a 0.00025 mm dip)
            val scarfLow = if (e.scarfStart != null && own == null && feature == "Outer wall" && !layerZ.isNaN()) layerZ - layerH * (1 - e.scarfStart) - Z_STEP / 2 else Double.NaN
            layerHeight(l)?.let { layerZ = it; maxLayerZ = maxOf(maxLayerZ, it) }
            thickness(l)?.let { layerH = it }
            widthOf(l)?.let { width = it }
            featureOf(l)?.let { feature = it }
            val c = l.substringBefore(';').trim()
            if (c.isEmpty()) continue
            val w = c.split(Regex("\\s+"))
            fun num(ch: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val inBody = i in firstLayer..lastExtrusion
            when {
                w[0].matches(Regex("T\\d+")) -> tool = w[0].substring(1).toInt()
                w[0] == "M83" -> relativeE = true
                w[0] == "M82" -> relativeE = false
                w[0] == "M109" -> (num('T')?.toInt() ?: tool).let { heated += it; swappedUnheated -= it }
                w[0] == "M204" -> num('S')?.let { if (it > e.maxAccel + 0.5) bad(ln, "acceleration ${fmt(it)} over the machine's ${fmt(e.maxAccel)}") }
                w[0] == "SET_VELOCITY_LIMIT" -> w.firstOrNull { it.startsWith("ACCEL=") }?.removePrefix("ACCEL=")?.toDoubleOrNull()?.let { if (it > e.maxAccel + 0.5) bad(ln, "acceleration ${fmt(it)} over the machine's ${fmt(e.maxAccel)}") }
                w[0] == "G0" || w[0] == "G1" || w[0] == "G2" || w[0] == "G3" -> {
                    num('F')?.let { f = it }
                    val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val ev = num('E') ?: 0.0
                    val xy = !x.isNaN() && (nx != x || ny != y)
                    if (ev != 0.0) (filOf[tool] ?: tool).let { f -> netE[f] = (netE[f] ?: 0.0) + ev }
                    // the file's E values are amounts (plymeld writes relative E): the printer must be told so first (firmware starts
                    // in absolute E, where each would be read as a position)
                    if (ev != 0.0 && !relativeE && !absoluteReported) { bad(ln, "extruding with relative extrusion not set: no M83 before this line (E values are amounts)"); absoluteReported = true }
                    if (i >= firstLayer) {
                        if (nx < e.bedMinX - 1e-6 || nx > e.bedMaxX + 1e-6 || ny < e.bedMinY - 1e-6 || ny > e.bedMaxY + 1e-6) bad(ln, "off the bed: X${fmt(nx, 3)} Y${fmt(ny, 3)}")
                        if (nz > e.maxZ + 1e-6) bad(ln, "above the machine's height: Z${fmt(nz, 3)}")
                    }
                    if (i < firstLayer && ev != 0.0 && !xy) {
                        // before the first layer: a retraction is carried into the print; a push (the prime) settles it, unjudged
                        retracted = if (ev < 0) retracted - ev else maxOf(0.0, retracted - ev)
                    }
                    // a line judged by the Z anti-aliasing rule (below its layer, on the model's top or reported there)
                    var zaaMove = false
                    if (inBody) {
                        if (ev > 0 && xy) {
                            // printing
                            if (!firstPrinted) { firstPrinted = true; if (tool !in heated) bad(ln, "first line printed with tool ${tool + 1} before any wait for its temperature (M109)") }
                            if (tool in swappedUnheated) { bad(ln, "T$tool (tool ${tool + 1}) prints after its filament was swapped without a wait for the new filament's temperature (M109)"); swappedUnheated -= tool }
                            printedSincePause += tool
                            printedBy[tool] = (printedBy[tool] ?: 0) + 1
                            if (e.allowedTools != null && tool !in e.allowedTools && wrongTool.add(tool))
                                bad(ln, "printed by T$tool (tool ${tool + 1}), which the config's loadout gives this print nothing to print; its parts are on ${tools(e.partTools ?: e.allowedTools)}")
                            if (retracted > 1e-6) bad(ln, "printing while ${fmt(retracted, 3)} mm retracted")
                            e.extrudeSectionMaxMm2?.let { maxA ->
                                val mm = Math.hypot(nx - x, ny - y)
                                if (mm > 0 && ev * e.filamentAreaMm2 / mm > maxA + 1e-3) bad(ln, "extrudes ${fmt(ev * e.filamentAreaMm2 / mm, 3)} mm² of cross-section, past the tool's ${fmt(maxA, 3)} mm² (hardware.tool.<key>.extrude.section.max): the firmware refuses the move")
                            }
                            // the ramp's lowest is worked from the layer's height and thickness, so it may fall between the
                            // file's 0.001 mm Z steps (0.245 mm layers: 39.235 - 0.75 x 0.245 = 39.05125, written Z39.051);
                            // a Z rounded to the nearest step is at it
                            val onScarf = !scarfLow.isNaN() && nz >= scarfLow - 1e-6 && nz <= printZ + 1e-6
                            val tack = feature == "Bridge tack"
                            if (tack && !e.tacksAllowed) bad(ln, "a bridge tack line in a print with no lifted bridge")
                            val tackBelow = tack && e.tacksAllowed && !printZ.isNaN() && nz <= printZ + 1e-6
                            // Z anti-aliasing: a line starting or ending below its flat layer (never above it), not a scarf ramp's or
                            // a tack, lies on the model's top within the layer, all along it (ZaaRule), or it is refused
                            val zaaLine = e.zaa != null && own == null && !printZ.isNaN() && !z.isNaN() && !onScarf && !tack &&
                                (nz < printZ - 1e-6 || z < printZ - 1e-6) && nz <= printZ + 1e-6 && z <= printZ + 1e-6
                            val zaaOff = if (zaaLine) e.zaa.off(x, y, z, nx, ny, nz, layerZ - layerH, layerZ) else null
                            val onZaa = zaaLine && zaaOff == null
                            zaaMove = zaaLine
                            // a ramp's line starts where a Z move alone left the nozzle: it too no lower than the ramp's lowest
                            if (!scarfLow.isNaN() && z < scarfLow - 1e-6 && !onZaa) bad(ln, "an outer wall line starting at Z${fmt(z, 3)}, under a scarf ramp's lowest ${fmt(scarfLow, 3)} on the layer at ${fmt(layerZ, 3)}")
                            // a wave bead laid one layer above its own (a second waved layer laid with the first) at that layer's height
                            val onAbove = feature == "Wave overhang, layer above" && !printZ.isNaN() && Math.abs(nz - (printZ + layerH)) <= 1.5 * Z_STEP ||   // warped back, the layer above lands within the file's Z step of it
                                // a waved first layer's line laid again over it (wave.second_pass.zones) or its Xs (wave.x_layer): on its
                                // layer, at most a layer above; its layer along the span (wave.span_layer): at most two layers above
                                (feature == "Wave overhang, second pass" || feature == "Wave overhang, X layer") && !printZ.isNaN() && nz >= printZ - 1.5 * Z_STEP && nz <= printZ + layerH + 1.5 * Z_STEP ||
                                feature == "Wave overhang, span layer" && !printZ.isNaN() && nz >= printZ - 1.5 * Z_STEP && nz <= printZ + 2 * layerH + 1.5 * Z_STEP
                            if (zaaOff != null) bad(ln, "a line below its layer (Z${fmt(z, 3)} to Z${fmt(nz, 3)} on the layer at ${fmt(layerZ, 3)}) off the model's top: Z${fmt(zaaOff[2], 3)} at (${fmt(zaaOff[0], 2)}, ${fmt(zaaOff[1], 2)}), where the model puts it at Z${fmt(zaaOff[3], 3)} (Z anti-aliasing lowers a line only onto the model's top within its layer)")
                            else if (!printZ.isNaN() && Math.abs(nz - printZ) > 1e-6 && !onScarf && !tackBelow && !onZaa && !onAbove) bad(ln, if (own != null) "printing '$cell' at Z${fmt(nz, 3)}, not its own height ${fmt(own, 3)}" else "printing at Z${fmt(nz, 3)} on the layer at ${fmt(layerZ, 3)}")
                            if (f / 60 > e.maxPrintSpeed * 1.005 + 1e-6) bad(ln, "printing at ${fmt(f / 60, 1)} mm/s, over the machine's ${fmt(e.maxPrintSpeed)}")
                            // an arc (G2 / G3) as the nozzle goes, in pieces of at most ARC_PIECE_MM, not its chord: a chord cuts
                            // inside the arc, and a travel across the chord crosses no line printed (refused falsely on
                            // a tube's outer wall)
                            if (feature in VISIBLE) {
                                if (w[0] == "G2" || w[0] == "G3") {
                                    var px = x; var py = y
                                    for ((ax, ay) in arcPoints(x, y, nx, ny, num('I') ?: 0.0, num('J') ?: 0.0, w[0] == "G2")) { printed += Seg(px, py, ax, ay, width / 2, feature, nz); px = ax; py = ay }
                                } else printed += Seg(x, y, nx, ny, width / 2, feature, nz)
                            }
                            // the print's top: its layers', or a line printed above its layer (a melt strip standing on its floor)
                            maxLayerZ = maxOf(maxLayerZ, nz)
                        } else if (ev == 0.0 && xy && num('E') != null && e.zaa != null && !printZ.isNaN() && !z.isNaN() && own == null &&
                            (nz < printZ - 1e-6 || z < printZ - 1e-6) && nz <= printZ + 1e-6 && z <= printZ + 1e-6 &&
                            !(!scarfLow.isNaN() && z >= scarfLow - 1e-6 && nz >= scarfLow - 1e-6)) {
                            // a line's piece whose plastic rounds to nothing (E0, written as part of its path: a travel has no E),
                            // below its flat layer: on the model's top like the rest of the line (Z anti-aliasing). A scarf ramp's
                            // piece is the ramp's (within its band), as its printed lines are
                            zaaMove = true
                            e.zaa.off(x, y, z, nx, ny, nz, layerZ - layerH, layerZ)?.let { o -> bad(ln, "a line below its layer (Z${fmt(z, 3)} to Z${fmt(nz, 3)} on the layer at ${fmt(layerZ, 3)}) off the model's top: Z${fmt(o[2], 3)} at (${fmt(o[0], 2)}, ${fmt(o[1], 2)}), where the model puts it at Z${fmt(o[3], 3)} (Z anti-aliasing lowers a line only onto the model's top within its layer)") }
                        } else if (ev < 0) {
                            retracted += -ev                                  // a retraction (a wipe's moves too)
                        } else if (ev > 0) {
                            // a restore: exactly what was retracted, one configured length
                            // (part of it, the rest spread over the path's start, where the config says so: returnSpreadMm)
                            if (ev > retracted + 1e-4) bad(ln, "restores ${fmt(ev, 3)} mm with ${fmt(retracted, 3)} mm retracted (extra plastic)")
                            else if (Math.abs(ev - retracted) > 1e-4 && e.returnSpreadMm == null) bad(ln, "restores ${fmt(ev, 3)} mm of ${fmt(retracted, 3)} mm retracted")
                            if (e.retractLengths.none { Math.abs(it - retracted) < 1e-3 }) bad(ln, "a retraction of ${fmt(retracted, 3)} mm; the config's are ${e.retractLengths.joinToString { fmt(it, 3) }} mm")
                            retracted = 0.0
                        } else if (xy) {
                            // a travel
                            if (f / 60 > e.maxTravelSpeed * 1.005 + 1e-6) bad(ln, "travel at ${fmt(f / 60, 1)} mm/s, over the machine's ${fmt(e.maxTravelSpeed)}")
                            // across a visible line printed on this layer at or above the travel's height (on an ordinary
                            // layer every line is at the layer's height: a travel at or below it)
                            if (!layerZ.isNaN()) crosses(x, y, nx, ny, nz, printed) { px, py -> e.onSurface?.invoke(px, py, layerZ, layerH) ?: true }?.let { bad(ln, "travel at print height (Z${fmt(nz, 3)}) $it, printed on this layer") }
                        }
                        val floorZ = (if (lowStanding.isNaN()) minOf(layerZ, printZ) else minOf(layerZ, printZ, lowStanding))
                            .let { if (scarfLow.isNaN()) it else minOf(it, scarfLow) }
                        if (!printZ.isNaN() && nz < floorZ - 1e-6 && xy && !(feature == "Bridge tack" && e.tacksAllowed && ev > 0) && !zaaMove) bad(ln, "below the layer being printed: Z${fmt(nz, 3)} on the layer at ${fmt(layerZ, 3)}")
                    } else if (i > lastExtrusion && xy && !liftedAfterEnd) {
                        // the first sideways move after the last line printed: above the print
                        if (nz <= maxLayerZ + 1e-6) bad(ln, "moves sideways at Z${fmt(nz, 3)} after the last line, not above the print (top ${fmt(maxLayerZ, 3)})")
                        liftedAfterEnd = true
                    }
                    x = nx; y = ny; z = nz
                }
            }
        }
        // every tool a part is assigned prints something (absence: a part's tool that never prints is a part printed by another)
        e.partTools?.filter { (printedBy[it] ?: 0) == 0 }?.forEach { t ->
            bad(firstLayer + 1, "T$t (tool ${t + 1}) prints nothing, though the config's loadout assigns it a part; lines printed by ${printedBy.keys.sorted().joinToString { "T$it" }.ifEmpty { "no tool" }}")
        }
        headerProblems(lines, netE).forEach { (ln, s) -> bad(ln, s) }
        return out
    }

    /**
     * A filament-use header (the Creator 5's: `; filament used [mm] = a, b, ...` and `; <filament id="n" ... used_m="x" />`,
     * values rounded to 0.01) against [netE], what each filament's lines push (id n, the n-th value: T(n-1)'s lines, or the
     * lines of a tool after a `; FILAMENT_SWAP` to n). No header: nothing.
     */
    private fun headerProblems(lines: List<String>, netE: Map<Int, Double>): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        fun body(t: Int) = netE[t] ?: 0.0
        val half = 0.005 + 1e-9                     // half the header's last digit
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith("; filament used [mm] =")) {
                val v = l.substringAfter('=').split(',').map { it.trim().toDouble() }
                for ((t, mm) in v.withIndex()) if (Math.abs(mm - body(t)) > half)
                    out += (i + 1) to "the header credits filament ${t + 1} with ${fmt(mm, 2)} mm, but its lines push ${fmt(body(t), 2)} mm"
                netE.filter { (t, mm) -> t >= v.size && Math.abs(mm) > half }.keys.sorted().forEach { t ->
                    out += (i + 1) to "the header lists ${v.size} filament(s), but filament ${t + 1} pushes ${fmt(body(t), 2)} mm" }
            }
            val m = FILAMENT_LINE.find(l) ?: continue
            val t = m.groupValues[1].toInt() - 1; val usedM = m.groupValues[2].toDouble()
            if (Math.abs(usedM - body(t) / 1000) > half)
                out += (i + 1) to "the header's filament ${t + 1} says ${fmt(usedM, 2)} m used, but its lines push ${fmt(body(t) / 1000, 2)} m"
        }
        return out
    }
    private val FILAMENT_LINE = Regex("""^; <filament id="(\d+)"[^>]*\bused_m="([0-9.]+)"""")
    private fun tools(ts: Set<Int>) = ts.sorted().joinToString { "T$it (tool ${it + 1})" }

    /** The template's lines without placeholders, in order, among [region]; the first missing one, or null. */
    private fun inOrder(template: String, region: List<String>): String? {
        var at = 0
        for (t in template.lines().map { it.trim() }.filter { it.isNotEmpty() && '{' !in it && '[' !in it }) {
            val k = (at until region.size).firstOrNull { region[it].trim() == t } ?: return t
            at = k + 1
        }
        return null
    }

    private fun isLayer(l: String) = l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")
    private fun featureOf(l: String) = when { l.startsWith(";TYPE:") -> l.removePrefix(";TYPE:"); l.startsWith("; FEATURE:") -> l.removePrefix("; FEATURE:"); else -> null }?.trim()
    private fun thickness(l: String) = when { l.startsWith(";HEIGHT:") -> l.removePrefix(";HEIGHT:"); l.startsWith("; LAYER_HEIGHT:") -> l.removePrefix("; LAYER_HEIGHT:"); else -> null }?.trim()?.toDoubleOrNull()

    /**
     * Lines a travel must not drag across at print height: the visible ones (it would mark them).
     * Travels inside the part across infill at print height are plymeld's choice (hidden travel).
     */
    private val VISIBLE = setOf("Outer wall", "Overhang wall", "Top surface", "Bottom surface", "Ironing")
    private fun layerHeight(l: String) = when { l.startsWith(";Z:") -> l.removePrefix(";Z:"); l.startsWith("; Z_HEIGHT:") -> l.removePrefix("; Z_HEIGHT:"); else -> null }?.trim()?.toDoubleOrNull()
    private fun widthOf(l: String) = when { l.startsWith(";WIDTH:") -> l.removePrefix(";WIDTH:"); l.startsWith("; LINE_WIDTH:") -> l.removePrefix("; LINE_WIDTH:"); else -> null }?.trim()?.toDoubleOrNull()
    /** A sideways move that extrudes ([orZero]: or carries E0, a segment of a line whose filament rounds to nothing). */
    private fun isExtrusion(l: String, orZero: Boolean = false): Boolean {
        val c = l.substringBefore(';').trim()
        if (!(c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 "))) return false
        val w = c.split(Regex("\\s+"))
        val e = w.firstOrNull { it.startsWith("E") }?.substring(1)?.toDoubleOrNull() ?: return false
        return w.any { it.startsWith("X") || it.startsWith("Y") } && (e > 0 || (orZero && e == 0.0))
    }

    /** Where the travel a->b at height [z] crosses the center of a line printed at [z] or above (away from the travel's own ends) at a point [counts], or null. */
    /**
     * A template placeholder left unrendered: a name in braces (`{nozzle_temp}`, `{if ...}`) or in square brackets
     * (`[first_layer_temperature]`). Not any bracket: Klipper's `EXCLUDE_OBJECT_DEFINE ... POLYGON=[[x,y],...]` is a list of
     * numbers, and every slice with exclude-object on was refused for it.
     */
    private val PLACEHOLDER = Regex("\\{[^}]*[A-Za-z_][^}]*\\}|\\[[A-Za-z_][A-Za-z0-9_.]*\\]")

    /** The longest straight piece an arc is read as (mm). plymeld's choice: its chord then strays under 0.01 mm from a 3 mm radius on. */
    private const val ARC_PIECE_MM = 0.5

    /** The points after the start of an arc from ([x], [y]) to ([nx], [ny]) about center offset ([i], [j]), clockwise for G2. */
    private fun arcPoints(x: Double, y: Double, nx: Double, ny: Double, i: Double, j: Double, cw: Boolean): List<Pair<Double, Double>> {
        val cx = x + i; val cy = y + j
        val r = Math.hypot(x - cx, y - cy)
        val a0 = Math.atan2(y - cy, x - cx); var a1 = Math.atan2(ny - cy, nx - cx)
        if (cw) { while (a1 >= a0) a1 -= 2 * Math.PI } else { while (a1 <= a0) a1 += 2 * Math.PI }
        val n = maxOf(1, Math.ceil(Math.abs(a1 - a0) * r / ARC_PIECE_MM).toInt())
        return (1 until n).map { k -> val a = a0 + (a1 - a0) * k / n; (cx + r * Math.cos(a)) to (cy + r * Math.sin(a)) } + (nx to ny)
    }

    private fun crosses(ax: Double, ay: Double, bx: Double, by: Double, z: Double, segs: List<Seg>, counts: (Double, Double) -> Boolean): String? {
        val len = Math.hypot(bx - ax, by - ay)
        if (len < 2.0 || segs.isEmpty()) return null
        // the travel without its first and last 0.75 mm (leaving and reaching a line is not crossing one)
        val ux = (bx - ax) / len; val uy = (by - ay) / len
        val sx = ax + ux * 0.75; val sy = ay + uy * 0.75; val ex = bx - ux * 0.75; val ey = by - uy * 0.75
        val minX = minOf(sx, ex); val maxX = maxOf(sx, ex); val minY = minOf(sy, ey); val maxY = maxOf(sy, ey)
        for (s in segs) {
            if (z > s.z + 1e-6) continue                     // the travel passes above it
            // a line wholly to one side of the travel's box cannot cross it
            if (maxOf(s.x0, s.x1) < minX || minOf(s.x0, s.x1) > maxX || maxOf(s.y0, s.y1) < minY || minOf(s.y0, s.y1) > maxY) continue
            val p = intersection(sx, sy, ex, ey, s.x0, s.y0, s.x1, s.y1) ?: continue
            if (!counts(p.first, p.second)) continue
            return "at (${fmt(p.first, 2)}, ${fmt(p.second, 2)}) across the ${s.feature} from (${fmt(s.x0, 2)}, ${fmt(s.y0, 2)}) to (${fmt(s.x1, 2)}, ${fmt(s.y1, 2)})"
        }
        return null
    }

    /** Where segment p1-p2 crosses q1-q2: strictly inside p1-p2, anywhere on q1-q2 (a printed line's joints count), or null. */
    private fun intersection(p1x: Double, p1y: Double, p2x: Double, p2y: Double, q1x: Double, q1y: Double, q2x: Double, q2y: Double): Pair<Double, Double>? {
        val rx = p2x - p1x; val ry = p2y - p1y; val sx = q2x - q1x; val sy = q2y - q1y
        val den = rx * sy - ry * sx
        if (Math.abs(den) < 1e-12) return null
        val t = ((q1x - p1x) * sy - (q1y - p1y) * sx) / den
        val u = ((q1x - p1x) * ry - (q1y - p1y) * rx) / den
        return if (t > 0 && t < 1 && u >= -1e-9 && u <= 1 + 1e-9) (p1x + t * rx) to (p1y + t * ry) else null
    }

    private fun dist(px: Double, py: Double, s: Seg): Double {
        val dx = s.x1 - s.x0; val dy = s.y1 - s.y0; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((px - s.x0) * dx + (py - s.y0) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(px - (s.x0 + t * dx), py - (s.y0 + t * dy))
    }

    private fun fmt(v: Double, d: Int = 0) = String.format(Locale.ROOT, "%.${d}f", v)
}
