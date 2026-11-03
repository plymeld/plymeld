package plymeld.config

import java.util.Locale

/**
 * The first-layer window (`hardware.filament.<key>.first_layer.window.<plate>`): a
 * first-layer plate's results, recorded on the filament as MEASURED DATA per build
 * plate (adhesion depends on the surface, as the filament's `bed.<plate>.first_layer` does): a grade per tile, not a
 * chosen speed. plymeld derives the first layer's height and speed from it where a print is set up ([decide]).
 *
 * One table per nozzle temperature, a TOML multi-line string laid out as the plate lies seen from the front, its axes
 * labeled, so each grade's position in the file is its tile's position on the plate:
 *
 * ```
 * [hardware.filament.white.first_layer.window.c5-plate]
 * from = "firstlayer_white_t3_pei_210_225.gcode ..."
 * bore = "0.4 mm"
 * "210 C" = '''
 * speed \ height   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm
 * 300 mm/s         over      over      over      over      over
 *  60 mm/s         bad       marginal  good      good      good
 * '''
 * ```
 *
 * The first line names the axes (`rows \ columns`: `speed \ height`, or `height \ speed`) and gives the columns' values,
 * each with its unit; every further line starts with its row's value and unit and holds one grade per column. Grades:
 * good, marginal, bad, over (a tile the plate left out, over the melt limit), and `?` (not graded yet: refused). The
 * parser reads the axes from the labels, never from position alone, and refuses unknown grades, ragged rows, missing
 * units and repeated values, each with its line. A literal string (`'''`) is the one to use: in a basic string (`"""`)
 * TOML reads the `\` as an escape.
 */
object FirstLayerWindow {
    enum class Grade(val word: String) { GOOD("good"), MARGINAL("marginal"), BAD("bad"), OVER("over"), UNGRADED("?") }

    private val GRADES = Grade.entries.associateBy { it.word }

    /**
     * One temperature's grades: [speeds] (mm/s) and [heights] (mm) both ascending; [grade] by row (speed) and column
     * (height). [label] says where the grid came from (a table's temperature, or two of them).
     */
    class Grid(val tempC: Double, val speeds: DoubleArray, val heights: DoubleArray, private val cells: Array<Array<Grade>>, val label: String) {
        fun grade(r: Int, c: Int): Grade = cells[r][c]
    }

    /** One recorded window: a filament on a build plate, measured with a nozzle of [boreMm]; its grids by temperature, coolest first. */
    class Window(val filament: String, val plate: String, val boreMm: Double, val from: String?, val grids: List<Grid>, val key: String, val source: String)

    /** What [decide] chose: the first layer's height (mm) and speed (mm/s), and why, in words. */
    class Choice(val heightMm: Double, val speedMmS: Double, val why: String)

    /** The table paths: `hardware.filament.<f>.first_layer.window.<plate>.<name>`. */
    fun isWindowPath(p: List<String>): Boolean = p.size == 7 && p[0] == "hardware" && p[1] == "filament" && p[3] == "first_layer" && p[4] == "window"

    // ---- reading

    /**
     * Every window in [values] (a config's, `like` taken over included), keyed filament then plate; each problem (a
     * malformed table, an ungraded tile, a missing bore, a plate the config does not have) into [problems]. [plates]: the
     * config's build plates.
     */
    fun read(values: Collection<ConfigFiles.Entry>, plates: Set<String>, problems: MutableList<String>): Map<String, Map<String, Window>> {
        val out = LinkedHashMap<String, LinkedHashMap<String, Window>>()
        for ((fp, es) in values.filter { isWindowPath(it.path) }.groupBy { it.path[2] to it.path[5] }) {
            val (f, plate) = fp
            val key = ConfigFiles.pathText(listOf("hardware", "filament", f, "first_layer", "window", plate))
            val n = problems.size
            if (plate !in plates) problems += "$key (${es.first().source}): there is no build plate '$plate' (hardware.plate.<key>: ${plates.sorted().joinToString()})"
            val boreE = es.firstOrNull { it.path[6] == "bore" }
            val bore = if (boreE == null) { problems += "$key (${es.first().source}): no bore: the nozzle bore the window was measured with (bore = \"0.4 mm\"); a window applies only to that bore"; null }
                // a bore that does not parse is reported with every other value's units (Config.check)
                else runCatching { Units.parse(boreE.value, Dimension.LENGTH, "${boreE.key} (${boreE.source})") }.getOrNull()?.takeIf { it.warning == null }?.value
            val from = es.firstOrNull { it.path[6] == "from" }?.value as? String
            val grids = ArrayList<Grid>()
            for (e in es.filter { it.path[6] != "bore" && it.path[6] != "from" }) {
                val what = "${e.key} (${e.source})"
                val t = runCatching { Units.parse(e.path[6], Dimension.TEMPERATURE, what) }.getOrNull()
                if (t == null || t.warning != null) { problems += "$what: '${e.path[6]}' is not a temperature with its unit (a table per nozzle temperature, e.g. \"210 C\"; besides them a window holds `from` and `bore`)"; continue }
                val text = e.value as? String ?: run { problems += "$what: a window's table is text (a multi-line string, '''...''')"; continue }
                grids.find { Math.abs(it.tempC - t.value) < 1e-9 }?.let { problems += "$what: a second table for ${fmt(t.value)} C"; continue }
                val (g, p) = parse(text, t.value, e.key, e.source)
                problems += p; g?.let { grids += it }
            }
            if (grids.isEmpty() && problems.size == n) problems += "$key (${es.first().source}): no table: a window holds one per nozzle temperature (\"210 C\" = '''...''')"
            if (problems.size == n && bore != null) out.getOrPut(f) { LinkedHashMap() }[plate] = Window(f, plate, bore, from, grids.sortedBy { it.tempC }, key, es.first().source)
        }
        return out
    }

    /**
     * One table, [text], for [tempC]: the grid, or null and the problems (each with the file line, where [source] is
     * "file:line" of the key: the table's first line is the next one, as a multi-line string starting on a new line has it).
     * [ungradedAllowed]: `?` is read as [Grade.UNGRADED] rather than refused (reading back a blank window before it is graded).
     */
    fun parse(text: String, tempC: Double, key: String, source: String, ungradedAllowed: Boolean = false): Pair<Grid?, List<String>> {
        val problems = ArrayList<String>()
        val file = source.substringBeforeLast(':', ""); val keyLine = source.substringAfterLast(':', "").toIntOrNull()
        fun at(i: Int) = if (keyLine != null && file.isNotEmpty()) "$file:${keyLine + 1 + i}" else source
        fun bad(i: Int, msg: String) { problems += "$key (${at(i)}): $msg" }
        val lines = text.lines().withIndex().filter { it.value.isNotBlank() }
        if (lines.isEmpty()) { bad(0, "an empty table"); return null to problems }
        val (hi, header) = lines.first()
        val m = CORNER.find(header)
        if (m == null) {
            bad(hi, "the first line names the axes, rows then columns ('speed \\ height': a row per speed, a column per nozzle height), then the columns' values with their units; got '${header.trim()}'")
            return null to problems
        }
        val rowAxis = m.groupValues[1].lowercase(); val colAxis = m.groupValues[2].lowercase()
        if (rowAxis == colAxis) { bad(hi, "the axes are speed and height, one each (got '$rowAxis \\ $colAxis')"); return null to problems }
        val cols = quantities(header.substring(m.range.last + 1), axis(colAxis), hi, ::bad) ?: return null to problems
        if (cols.isEmpty()) { bad(hi, "no columns: the first line gives each column's $colAxis after the axes"); return null to problems }
        cols.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { bad(hi, "the $colAxis ${show(it, colAxis)} is a column twice") }
        val rows = ArrayList<Double>(); val grades = ArrayList<List<Grade>>()
        for ((i, line) in lines.drop(1)) {
            val r = ROW.find(line)
            val label = r?.let { runCatching { Units.parse(it.groupValues[1].trim(), axis(rowAxis), "") }.getOrNull() }
            if (r == null || label == null || label.warning != null) { bad(i, "a row starts with its $rowAxis and unit (e.g. ${if (rowAxis == "speed") "'60 mm/s'" else "'0.2 mm'"}); got '${line.trim()}'"); continue }
            val words = line.substring(r.range.last + 1).trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.size != cols.size) { bad(i, "the row ${show(label.value, rowAxis)} has ${words.size} grade(s) for ${cols.size} column(s) (${cols.joinToString { show(it, colAxis) }})"); continue }
            if (rows.any { Math.abs(it - label.value) < 1e-9 }) { bad(i, "the $rowAxis ${show(label.value, rowAxis)} is a row twice"); continue }
            val g = words.mapIndexed { k, w ->
                GRADES[w.lowercase()] ?: run { bad(i, "row ${show(label.value, rowAxis)}, column ${show(cols[k], colAxis)}: '$w' is not a grade (good, marginal, bad, over; ? until graded)"); Grade.UNGRADED }
            }
            if (!ungradedAllowed) g.withIndex().filter { it.value == Grade.UNGRADED && words[it.index] == "?" }.forEach { (k, _) ->
                bad(i, "row ${show(label.value, rowAxis)}, column ${show(cols[k], colAxis)}: not graded yet ('?'): write good, marginal, or bad as the tile printed")
            }
            rows += label.value; grades += g
        }
        if (rows.isEmpty() && problems.isEmpty()) bad(hi, "no rows: a line per $rowAxis after the first")
        if (problems.isNotEmpty()) return null to problems
        // to rows of speed and columns of height, both ascending
        val speeds = (if (rowAxis == "speed") rows else cols).sorted().toDoubleArray()
        val heights = (if (rowAxis == "speed") cols else rows).sorted().toDoubleArray()
        val cells = Array(speeds.size) { r -> Array(heights.size) { c ->
            if (rowAxis == "speed") grades[rows.indexOf(speeds[r])][cols.indexOf(heights[c])] else grades[rows.indexOf(heights[c])][cols.indexOf(speeds[r])]
        } }
        return Grid(tempC, speeds, heights, cells, "its ${fmt(tempC)} C table") to problems
    }

    private val CORNER = Regex("""^\s*(speed|height)\s*\\\s*(speed|height)(?=\s|$)""", RegexOption.IGNORE_CASE)
    private val ROW = Regex("""^\s*([-+]?(?:\d+\.?\d*|\.\d+)\s*[^\s\d.]\S*)""")
    private val QUANTITY = Regex("""([-+]?(?:\d+\.?\d*|\.\d+))(\s*[^\s\d.]\S*)?""")

    private fun axis(name: String) = if (name == "speed") Dimension.SPEED else Dimension.LENGTH

    /** The columns' values on the header after the axes: each a number and its unit ("0.2 mm" or "0.2mm"). */
    private fun quantities(rest: String, dim: Dimension, line: Int, bad: (Int, String) -> Unit): List<Double>? {
        val out = ArrayList<Double>(); var ok = true; var end = 0
        for (q in QUANTITY.findAll(rest)) {
            if (rest.substring(end, q.range.first).isNotBlank()) { bad(line, "'${rest.substring(end, q.range.first).trim()}' in the first line is not a column's value with its unit"); ok = false }
            end = q.range.last + 1
            val unit = q.groupValues[2].trim()
            if (unit.isEmpty()) { bad(line, "the column '${q.value.trim()}' has no unit (${dim.base})"); ok = false; continue }
            runCatching { Units.parse("${q.groupValues[1]} $unit", dim, "") }.fold({ out += it.value }, { bad(line, "the column '${q.value.trim()}': '$unit' is not a unit of ${dim.name.lowercase()} (${dim.base})"); ok = false })
        }
        if (rest.substring(end).isNotBlank()) { bad(line, "'${rest.substring(end).trim()}' in the first line is not a column's value with its unit"); ok = false }
        return if (ok) out else null
    }

    // ---- using

    /**
     * The grid at [tempC] (the first layer's nozzle temperature): its own table if there is one; between two measured
     * temperatures, a tile is good only where it is good in both neighbors (on the speeds and heights both have); outside
     * the measured range, null (no window there).
     */
    fun gridAt(w: Window, tempC: Double): Grid? {
        w.grids.firstOrNull { Math.abs(it.tempC - tempC) < 1e-6 }?.let { return it }
        val lo = w.grids.lastOrNull { it.tempC < tempC } ?: return null
        val hi = w.grids.firstOrNull { it.tempC > tempC } ?: return null
        val speeds = lo.speeds.filter { s -> hi.speeds.any { Math.abs(it - s) < 1e-9 } }.toDoubleArray()
        val heights = lo.heights.filter { h -> hi.heights.any { Math.abs(it - h) < 1e-9 } }.toDoubleArray()
        fun g(x: Grid, s: Double, h: Double) = x.grade(x.speeds.indexOfFirst { Math.abs(it - s) < 1e-9 }, x.heights.indexOfFirst { Math.abs(it - h) < 1e-9 })
        val cells = Array(speeds.size) { r -> Array(heights.size) { c ->
            val a = g(lo, speeds[r], heights[c]); val b = g(hi, speeds[r], heights[c])
            when { a == Grade.GOOD && b == Grade.GOOD -> Grade.GOOD; a == Grade.BAD || b == Grade.BAD -> Grade.BAD
                a == Grade.MARGINAL || b == Grade.MARGINAL -> Grade.MARGINAL; else -> Grade.OVER }
        } }
        return Grid(tempC, speeds, heights, cells, "between its ${fmt(lo.tempC)} C and ${fmt(hi.tempC)} C tables (a tile good only where good in both)")
    }

    /**
     * The first layer from [g] — the rule is plymeld's choice:
     *  - only heights the tool lays as a first layer count ([minLayerMm]..[maxLayerMm]: its `layer.height.min` and its
     *    `first_layer.height.max`, else `layer.height.max`, when known; [rangeFrom] names them in the reason);
     *  - a height's reach is the fastest speed up to which every tile from the slowest is good;
     *  - the height whose reach is the highest; on a tie, the one nearest the middle of the heights with any reach (the
     *    good height range), then the lower;
     *  - the speed: that reach, [marginRows] row(s) inside it when a faster row was tried there and is not good (the
     *    margin; when the reach is the fastest row tried, there is no edge to keep off).
     * [marginRows] is the hook for the job's priorities (not wired): a high reliability weight could widen it, a
     * high speed weight narrow it to 0. Null when no tile at a height the tool lays is good. The melt cap still applies to
     * the speed where the print is written, as to every move.
     */
    /**
     * The first layer at a speed the user set for this plate ([speedMmS], hardware.filament.<key>.bed.<plate>.first_layer.speed,
     * for this plate only): the height, within the tool's range, graded good on the
     * window's row at that speed (the slowest row at or above it; the fastest row when it is faster than any), the one
     * nearest the middle of the good heights there. Null when no height is good at that speed.
     */
    fun decideAt(g: Grid, speedMmS: Double, minLayerMm: Double?, maxLayerMm: Double?, rangeFrom: String = "its layer.height.min and layer.height.max"): Choice? {
        val r = g.speeds.indices.firstOrNull { g.speeds[it] >= speedMmS - 1e-9 } ?: (g.speeds.size - 1)
        val cols = g.heights.indices.filter { c -> (minLayerMm == null || g.heights[c] >= minLayerMm - 1e-9) && (maxLayerMm == null || g.heights[c] <= maxLayerMm + 1e-9) }
        val good = cols.filter { g.grade(r, it) == Grade.GOOD }
        if (good.isEmpty()) return null
        // the height the window would choose on its own ([decide]) where it is good at this speed (only the speed changes);
        // else the good height nearest the middle of the good ones
        val own = decide(g, minLayerMm, maxLayerMm, rangeFrom = rangeFrom)?.let { ch -> good.firstOrNull { Math.abs(g.heights[it] - ch.heightMm) < 1e-9 } }
        val mid = (g.heights[good.first()] + g.heights[good.last()]) / 2
        val c = own ?: good.minWith(compareBy({ Math.abs(g.heights[it] - mid) }, { g.heights[it] }))
        val h = g.heights[c]
        val row = if (Math.abs(g.speeds[r] - speedMmS) < 1e-9) "its ${mms(speedMmS)} row" else "its ${mms(g.speeds[r])} row (the slowest at or above ${mms(speedMmS)})"
        val range = if (own != null) "the height the window chooses on its own" else if (good.size == 1) "the only good height there" else "the nearest the middle of the good heights ${mm(g.heights[good.first()])} to ${mm(g.heights[good.last()])}"
        val left = g.heights.filter { it !in cols.map { k -> g.heights[k] } }
        val why = "from ${g.label}: the speed set for this plate, ${mms(speedMmS)}; $row grades ${mm(h)} good ($range)" +
            (if (left.isEmpty()) "" else "; ${left.joinToString { mm(it) }} not counted: outside the tool's first-layer range, ${minLayerMm?.let { mm(it) } ?: "any"} to ${maxLayerMm?.let { mm(it) } ?: "any"} ($rangeFrom)")
        return Choice(h, speedMmS, why)
    }

    fun decide(g: Grid, minLayerMm: Double?, maxLayerMm: Double?, marginRows: Int = 1, rangeFrom: String = "its layer.height.min and layer.height.max"): Choice? {
        val cols = g.heights.indices.filter { c -> (minLayerMm == null || g.heights[c] >= minLayerMm - 1e-9) && (maxLayerMm == null || g.heights[c] <= maxLayerMm + 1e-9) }
        val reach = cols.associateWith { c -> var r = -1; while (r + 1 < g.speeds.size && g.grade(r + 1, c) == Grade.GOOD) r++; r }
        val good = cols.filter { reach.getValue(it) >= 0 }
        if (good.isEmpty()) return null
        val best = good.maxOf { reach.getValue(it) }
        val mid = (g.heights[good.first()] + g.heights[good.last()]) / 2
        val c = good.filter { reach.getValue(it) == best }.minWith(compareBy({ Math.abs(g.heights[it] - mid) }, { g.heights[it] }))
        val edge = best + 1 < g.speeds.size
        val r = if (edge) maxOf(0, best - marginRows) else best
        val h = g.heights[c]; val s = g.speeds[r]
        val left = g.heights.filter { it !in cols.map { k -> g.heights[k] } }
        val why = StringBuilder("from ${g.label}: ")
        why.append("good tiles at ${mm(h)} reach ${mms(g.speeds[best])}")
        val tied = good.filter { reach.getValue(it) == best }
        val range = if (good.size == 1) "the only good height" else "the good heights ${mm(g.heights[good.first()])} to ${mm(g.heights[good.last()])}"
        if (tied.size > 1) why.append(" (as at ${tied.filter { it != c }.joinToString { mm(g.heights[it]) }}; the nearest the middle of $range)")
        else why.append(if (good.size == 1) " ($range)" else " (the fastest of $range)")
        val next = if (edge) "the next row, ${mms(g.speeds[best + 1])}, is ${g.grade(best + 1, c).word}" else ""
        when {
            !edge -> why.append(", the fastest row tried: no edge to keep off")
            r < best -> why.append("; ${if (best - r == 1) "one row" else "${best - r} rows"} inside that edge as a margin, ${mms(s)} ($next)")
            else -> why.append("; at that edge, no margin ($next; ${if (marginRows <= 0) "margin 0" else "no slower row"})")
        }
        if (left.isNotEmpty()) why.append("; ${left.joinToString { mm(it) }} not counted: outside the tool's first-layer range, ${minLayerMm?.let { mm(it) } ?: "any"} to ${maxLayerMm?.let { mm(it) } ?: "any"} ($rangeFrom)")
        why.append(". Rule: plymeld's choice; the melt cap still applies")
        return Choice(h, s, why.toString())
    }

    // ---- writing

    /**
     * A table as the config holds it ([cell]: a grade word for row [r] (speed, ascending) and column [c] (height,
     * ascending)): the axes on the first line, the fastest row at the top (the plate's back, as it lies seen from the
     * front), heights left to right, columns aligned.
     */
    fun format(speeds: DoubleArray, heights: DoubleArray, cell: (Int, Int) -> String): String {
        val corner = "speed \\ height"
        val rowLabels = speeds.map { "${num(it)} mm/s" }
        val colLabels = heights.map { "${fixed(it)} mm" }
        val lw = maxOf(corner.length, rowLabels.maxOf { it.length }) + 3
        val cw = maxOf(colLabels.maxOf { it.length }, Grade.entries.maxOf { it.word.length }) + 2
        val sb = StringBuilder()
        sb.append(corner.padEnd(lw)).append(colLabels.joinToString("") { it.padEnd(cw) }.trimEnd()).append('\n')
        for (r in speeds.indices.reversed()) {
            sb.append(rowLabels[r].padStart(rowLabels.maxOf { it.length }).padEnd(lw))
            sb.append(heights.indices.joinToString("") { c -> cell(r, c).padEnd(cw) }.trimEnd()).append('\n')
        }
        return sb.toString()
    }

    fun mm(v: Double) = "${fixed(v)} mm"
    fun mms(v: Double) = "${num(v)} mm/s"
    /** At least two decimals (0.20), more where the value has them (0.125). */
    private fun fixed(v: Double): String { val s = String.format(Locale.ROOT, "%.6f", v).trimEnd('0'); val d = s.substringAfter('.', ""); return if (d.length >= 2) s else String.format(Locale.ROOT, "%.2f", v) }
    private fun num(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.')
    private fun fmt(v: Double) = num(v)
    private fun show(v: Double, axis: String) = if (axis == "speed") mms(v) else mm(v)
}
