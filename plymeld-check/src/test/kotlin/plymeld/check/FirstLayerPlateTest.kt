package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.core.calibrate.CalibrationPlate
import plymeld.core.calibrate.CalibrationSpec
import plymeld.core.calibrate.FirstLayerCheck
import plymeld.core.calibrate.FirstLayerInstructions
import plymeld.core.calibrate.FirstLayerPlate
import plymeld.core.calibrate.FirstLayerSpec
import plymeld.core.calibrate.FirstLayerTiles
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateInstructions
import plymeld.model.SliceSettings

/**
 * The first-layer plate: one layer of first-layer tiles laid out as a table, a column per nozzle temperature (coolest
 * first, a wait and a prime line before each) and a row per speed and height (a group of rows per speed), every tile a
 * strip of the same length; a base along the front and left, its corner cut off, each column's temperature, each row's
 * height, and each speed raised on it; tiles over the melt limit left out; each tile starting at the middle of its back
 * edge; one piece (every tile touching its neighbors and the base) under a three-layer frame over the tiles' walls and as tall a border round the base. It prints exactly as specified,
 * and its read-back check can fail: each control below is caught.
 */
class FirstLayerPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    // speeds 40, 70, 100 mm/s by group, heights 0.16 .. 0.28 mm by row (a tile below the configured 0.2 mm first layer too, on a
    // 0.4 mm bore), 0.5 mm lines, a 0.2 mm lift
    val heights = listOf(0.16, 0.2, 0.24, 0.28)
    val speeds = listOf(40.0, 70.0, 100.0)
    fun tiles(hs: List<Double> = heights) = FirstLayerTiles(speeds.toDoubleArray(), hs.map { Math.round(it * 1000) }.toLongArray(), 500, 5000.0, 200, 0.25)
    // the frame: 100 mm/s, 5000 mm/s2 (held to the melt limit), 0.45 mm lines 0.2 mm high (the test's values)
    val frame = PathValues(100.0, 5000.0, 1.0, 0.8)
    // melt limits 20 mm3/s: no tile over (100 mm/s through the 0.28 mm tile's bead is 12.3)
    fun spec(temps: IntArray = intArrayOf(205, 210, 215), caps: DoubleArray = DoubleArray(temps.size) { 20.0 }, hs: List<Double> = heights) =
        FirstLayerSpec(0, "test", temps, 200, 1.75, 1.0, tiles(hs), caps, PathValues(40.0, 5000.0, 1.0, 0.8), 500, frame, 450, 200, 60_000, 60_000)
    val sp = spec()
    fun gcode(p: FirstLayerPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text = gcode(FirstLayerPlate(sp))
    fun ctl(spec: FirstLayerSpec, vararg w: String) = gcode(FirstLayerPlate(spec, without = w.toSet()))
    // the config's side of the read-back (a bed large enough for the test plate), every cell at its own height on the first layer
    fun ownZ(spec: FirstLayerSpec): Map<String, Double> {
        val out = HashMap<String, Double>()
        for (k in 0 until spec.blocks) {
            for ((r, c) in spec.rows) { out[spec.tileLabel(k, r, c)] = spec.tiles.heightsUm[c] / 1000.0; for (n in 1..3) out[spec.frameLabel(k, r, c, n)] = spec.frameUm(c, n) / 1000.0 }
            out[spec.primeLabel(k)] = spec.primeUm / 1000.0
            for (n in 1..2) out[spec.markLabel(k, n)] = spec.labelUm(n) / 1000.0
        }
        for (n in 1..2) {
            for ((r, c) in spec.rows) out[spec.heightLabelOf(r, c, n)] = spec.labelUm(n) / 1000.0
            for (r in spec.tiles.speeds.indices) out[spec.speedLabelOf(r, n)] = spec.labelUm(n) / 1000.0
            out[spec.baseLabel(n)] = spec.baseUm(n) / 1000.0
        }
        for (n in 1..3) out[spec.borderLabel(n)] = spec.borderUm(n) / 1000.0
        return out
    }
    fun expect(own: Map<String, Double>) = plymeld.core.check.GcodeCheck.Expect(0.0, 0.0, 500.0, 500.0, 300.0, maxPrintSpeed = 1000.0, maxTravelSpeed = 1000.0, maxAccel = 100000.0,
        retractLengths = setOf(0.8, s.retractMm), startTemplate = "", endTemplate = "", ownZ = own)
    // the test machine's exclude-object definition left out (its POLYGON=[[...]] reads as a placeholder to the check)
    fun gcheck(t: String, own: Map<String, Double>) = plymeld.core.check.GcodeCheck.check(t.lines().filterNot { it.startsWith("EXCLUDE_OBJECT_DEFINE") }.joinToString("\n"), expect(own))
    // the height (Z) and feed a cell's first line is laid at, read from the moves themselves
    fun firstZF(t: String, cell: String): Pair<Double, Double> {
        var z = 0.0; var f = 0.0; var inCell = false
        for (l in t.lines()) {
            Regex("G1 .*Z([0-9.]+).*").matchEntire(l)?.let { z = it.groupValues[1].toDouble() }
            Regex("G1 .*F([0-9.]+).*").matchEntire(l)?.let { f = it.groupValues[1].toDouble() }
            if (l == "; CELL: $cell") inCell = true
            if (inCell && Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(l)) return z to f / 60
        }
        return Double.NaN to Double.NaN
    }
    // a cell's extrusion segments (x0, y0, x1, y1), read from the g-code
    fun segs(t: String, cell: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(); var x = Double.NaN; var y = Double.NaN; var inCell = false
        for (l in t.lines()) {
            if (l.startsWith("; CELL: ")) inCell = l == "; CELL: $cell"
            if (l == "; CELL END") inCell = false
            val m = Regex("G1 (?:X([-0-9.]+) )?(?:Y([-0-9.]+) )?.*").matchEntire(l) ?: continue
            val nx = m.groupValues[1].toDoubleOrNull() ?: x; val ny = m.groupValues[2].toDoubleOrNull() ?: y
            if (inCell && l.contains(" E") && !l.contains(" E-") && !x.isNaN() && (nx != x || ny != y)) out += doubleArrayOf(x, y, nx, ny)
            x = nx; y = ny
        }
        return out
    }
    // a cell's lines' extent (x0, y0, x1, y1), from the g-code
    fun ext(t: String, cell: String) = segs(t, cell).let { s -> doubleArrayOf(s.minOf { minOf(it[0], it[2]) }, s.minOf { minOf(it[1], it[3]) }, s.maxOf { maxOf(it[0], it[2]) }, s.maxOf { maxOf(it[1], it[3]) }) }
    // [t] with the line [k] of the first match of [find] before column [block]'s first cell changed by [change] (null: removed)
    fun alterBefore(t: String, block: Int, find: (String) -> Boolean, change: ((String) -> String)?): String {
        val lines = t.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; CELL: block $block ") }
        val k = (0 until at).last { find(lines[it]) }
        if (change == null) lines.removeAt(k) else lines[k] = change(lines[k])
        return lines.joinToString("\n")
    }
    fun caught(spec: FirstLayerSpec, w: String, what: String) = FirstLayerCheck.check(spec, ctl(spec, w)).any { it.contains(what) } shouldBe true

    "temperatures: whole-degree steps, coolest first; one temperature allowed; a step that does not divide refused" {
        CalibrationSpec.temperatures(205, 225, 5) shouldBe listOf(205, 210, 215, 220, 225)
        CalibrationSpec.temperatures(215, 215, 5) shouldBe listOf(215)
        shouldThrow<IllegalStateException> { CalibrationSpec.temperatures(205, 222, 5) }.message!! shouldContain "does not divide"
        shouldThrow<IllegalArgumentException> { FirstLayerSpec(0, "t", intArrayOf(210, 205), 200, 1.75, 1.0, tiles(), doubleArrayOf(20.0, 20.0), PathValues(40.0, 5000.0, 1.0, 0.8), 500, frame, 450, 200) }
    }

    "the plate as generated passes both checks" {
        FirstLayerCheck.check(sp, text).shouldBeEmpty()
        gcheck(text, ownZ(sp)).shouldBeEmpty()
    }

    "independently of the checks: one layer; columns coolest first, each after a wait for its temperature, primed before its tiles; every tile at its height and speed, each column lowest first" {
        val lines = text.lines()
        lines.count { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") } shouldBe 1
        for ((k, temp) in listOf(205, 210, 215).withIndex()) {
            val first = lines.indexOfFirst { it.startsWith("; CELL: block ${k + 1} ") }
            lines[first] shouldBe "; CELL: block ${k + 1} prime"
            // the last nozzle command before the column: a wait for its own temperature
            lines.subList(0, first).last { it.startsWith("M104") || it.startsWith("M109") } shouldBe "M109 S$temp T0"
            for ((r, v) in speeds.withIndex()) for ((c, h) in heights.withIndex()) firstZF(text, "block ${k + 1} tile ${r + 1}.${c + 1}") shouldBe (h to v)
            // its tiles lowest first: the heights its tile cells start at, in the order they come, never fall
            val order = lines.filter { it.startsWith("; CELL: block ${k + 1} tile ") }.map { firstZF(text, it.removePrefix("; CELL: ")).first }
            order.zipWithNext().all { (a, b) -> b >= a - 1e-9 } shouldBe true
        }
        // the prime lines and the base's first layer at the prime height: the lowest tile's 0.16, lower than the first layer's
        // 0.2; the base's second layer a layer (0.2) above, the labels' two layers two and three above
        firstZF(text, "block 2 prime").first shouldBe (0.16 plusOrMinus 1e-9)
        firstZF(text, "block 1 base 1").first shouldBe (0.16 plusOrMinus 1e-9)
        firstZF(text, "block 1 base 2").first shouldBe (0.36 plusOrMinus 1e-9)
        firstZF(text, "block 3 mark 1").first shouldBe (0.56 plusOrMinus 1e-9)
        firstZF(text, "block 1 height 2.3 2").first shouldBe (0.76 plusOrMinus 1e-9)
        // the base's first layer before any tile; its second layer and the labels after all the tiles, before the frame
        (text.indexOf("; CELL: block 1 base 1\n") < text.indexOf("; CELL: block 1 tile ")) shouldBe true
        (text.indexOf("; CELL: block 1 base 2\n") > text.lastIndexOf("; CELL: block 3 tile ")) shouldBe true
        (text.lastIndexOf("; CELL: block 1 speed ") < text.indexOf("; CELL: block 1 frame ")) shouldBe true
    }

    "independently: every tile 40 mm long along X; the columns side by side and the rows front to back, all touching; the labels where they go" {
        // the tiles' extents from their own lines (centers), a line width (0.5 mm) outside: 40 mm long
        for (k in 1..3) for (r in 1..3) for (c in 1..4) ext(text, "block $k tile $r.$c").let { e -> (e[2] - e[0] + 0.5) shouldBe (40.0 plusOrMinus 0.002) }
        // the columns touch: column 2's tile r.c starts where column 1's ends
        for (r in 1..3) for (c in 1..4) (ext(text, "block 2 tile $r.$c")[0] - ext(text, "block 1 tile $r.$c")[2]) shouldBe (0.5 plusOrMinus 0.002)
        // the rows touch, front to back: speed 1's heights, then speed 2's ...: each row's front line a line width behind the
        // back line of the row in front of it
        val order = (1..3).flatMap { r -> (1..4).map { c -> "$r.$c" } }
        for ((a, b) in order.zipWithNext()) (ext(text, "block 1 tile $b")[1] - ext(text, "block 1 tile $a")[3]) shouldBe (0.5 plusOrMinus 0.002)
        // the temperature: on the base in front of the front row (a gap, 1.5 mm, clear of it), right of the column's left edge
        for (k in 1..3) {
            val mark = ext(text, "block $k mark 1"); val t11 = ext(text, "block $k tile 1.1")
            (mark[3] < t11[1] - 1.5 && mark[0] > t11[0]) shouldBe true
        }
        // each row's height label left of the first column, clear of it and level with its row; each speed's further left,
        // clear of the heights, level with its group
        for (r in 1..3) for (c in 1..4) { val hl = ext(text, "block 1 height $r.$c 1"); val tr = ext(text, "block 1 tile $r.$c")
            (hl[2] < tr[0] - 1.5 && hl[1] > tr[1] - 1 && hl[3] < tr[3] + 1) shouldBe true }
        for (r in 1..3) { val sl = ext(text, "block 1 speed $r 1"); val first = ext(text, "block 1 height $r.1 1"); val last = ext(text, "block 1 height $r.4 1")
            (sl[2] < first[0] - 1.5 && sl[1] > first[1] - 1 && sl[3] < last[3] + 1) shouldBe true }
        // the base: from the plate's front-left corner (cut off) to the tiles; its corner point nearest the corner 2 mm along
        // each edge plus half a line, by x + y
        val base = segs(text, "block 1 base 1"); val bx = base.minOf { minOf(it[0], it[2]) } - 0.25; val by = base.minOf { minOf(it[1], it[3]) } - 0.25
        (base.flatMap { listOf(it[0] - bx + it[1] - by, it[2] - bx + it[3] - by) }.min()) shouldBe (2.0 + 0.25 * Math.sqrt(2.0) plusOrMinus 0.02)
        // the temperatures differ, so the labels' lines do: column 1's (205) and column 3's (215), each from its own corner
        fun shape(k: Int) = ext(text, "block $k mark 1").let { e -> segs(text, "block $k mark 1").map { s -> listOf(s[0] - e[0], s[1] - e[1], s[2] - e[0], s[3] - e[1]).map { Math.round(it * 100) } } }
        (shape(1) == shape(3)) shouldBe false
    }

    "controls: the second column at the first's temperature (wait removed, or at 205), or not waited for, is caught" {
        val removed = alterBefore(text, 2, { it == "M109 S210 T0" }, null)
        FirstLayerCheck.check(sp, removed).any { it.contains("'block 2 prime': at 205 C, not 210 C") } shouldBe true
        val wrong = alterBefore(text, 2, { it == "M109 S210 T0" }) { "M109 S205 T0" }
        FirstLayerCheck.check(sp, wrong).any { it.contains("'block 2 tile 1.1': at 205 C, not 210 C") } shouldBe true
        // set without a wait (M104): the target is right, the nozzle may not be there yet
        val noWait = alterBefore(text, 2, { it == "M109 S210 T0" }) { "M104 S210 T0" }
        FirstLayerCheck.check(sp, noWait).any { it.contains("'block 2 prime': no wait (M109) for 210 C") } shouldBe true
    }

    "controls: a tile at the wrong height, a tile at the wrong speed are caught" {
        fun alter(cell: String, prefix: String, change: (String) -> String): String {
            val lines = text.lines().toMutableList()
            val at = lines.indexOfFirst { it == "; CELL: $cell" }
            val end = (at until lines.size).first { lines[it] == "; CELL END" }
            val k = (at until end).first { lines[it].startsWith(prefix) }
            lines[k] = change(lines[k])
            return lines.joinToString("\n")
        }
        val wrongZ = alter("block 3 tile 1.3", "G1 Z") { "G1 Z0.2" }
        FirstLayerCheck.check(sp, wrongZ).any { it.contains("'block 3 tile 1.3': printed at z 0.2, not 0.24 (its height)") } shouldBe true
        gcheck(wrongZ, ownZ(sp)).any { it.contains("printing 'block 3 tile 1.3' at Z0.200, not its own height 0.240") } shouldBe true
        val wrongV = alter("block 2 tile 2.3", "G1 F") { "G1 F5400" }
        FirstLayerCheck.check(sp, wrongV).any { it.contains("'block 2 tile 2.3': 90.00 mm/s, not 70.00") } shouldBe true
        // the g-code check without the tiles named: their heights are lines off the layer's
        gcheck(text, emptyMap()).any { it.contains("on the layer at") } shouldBe true
    }

    "the lift: a travel crossing a FINISHED tile at or below its height is caught" {
        caught(sp, "tilelift", "no lift")
        // a travel at a finished tile's height straight across it (neither end on it), after the first column's tiles
        val lines = text.lines().toMutableList()
        val at = lines.indexOf("; CELL: block 2 prime")
        val t = ext(text, "block 1 tile 1.1"); val y = (t[1] + t[3]) / 2
        lines.addAll(at, listOf("G1 X${t[0] - 2} Y$y F6000", "G1 Z0.16", "G1 X${t[2] + 2} Y$y F6000", "G1 Z1.2"))
        FirstLayerCheck.check(sp, lines.joinToString("\n")).any { it.contains("a travel at z 0.160 across finished 'block 1 tile 1.1'") } shouldBe true
    }

    "a prime line and labels lower than the first layer when the lowest tile is: at its height" {
        val low = spec(hs = listOf(0.12, 0.16, 0.2, 0.24))
        val t = gcode(FirstLayerPlate(low))
        FirstLayerCheck.check(low, t).shouldBeEmpty()
        gcheck(t, ownZ(low)).shouldBeEmpty()
        firstZF(t, "block 1 prime").first shouldBe (0.12 plusOrMinus 1e-9)
    }

    "controls: a prime line missing, a label showing another temperature, a speed or height label missing, a stray line are caught" {
        caught(sp, "prime", "no 'block 3 prime' cell")
        // the last column's label shows 205 (the first's), the same width as its own 215: caught by its digits
        caught(sp, "mark", "'block 3 mark 1': not the label '215' where it goes")
        caught(sp, "speedlabel", "no 'block 1 speed 1 1' cell")
        caught(sp, "heightlabel", "no 'block 1 height 1.1 1' cell")
        caught(sp, "chamfer", "not the 2.0 mm chamfer")
        caught(sp, "base", "no 'block 1 base 2' cell")
        val lines = text.lines().toMutableList()
        val end = (lines.indexOfFirst { it == "; CELL: block 1 prime" } until lines.size).first { lines[it] == "; CELL END" }
        lines.addAll(end + 1, listOf("G1 X50 Y50 F6000", "G1 X55 Y50 E0.2 F1200"))
        FirstLayerCheck.check(sp, lines.joinToString("\n")).any { it.contains("in '(none)'") } shouldBe true
    }

    "one temperature: one column, its wait the plate's own guard" {
        val one = spec(intArrayOf(215))
        val t = gcode(FirstLayerPlate(one))
        FirstLayerCheck.check(one, t).shouldBeEmpty()
        // control: that wait 5 C off
        FirstLayerCheck.check(one, alterBefore(t, 1, { it == "M109 S215 T0" }) { "M109 S210 T0" }).any { it.contains("at 210 C, not 215 C") } shouldBe true
    }

    "tiles over the column's melt limit are left out, never slowed; on the page as empty cells" {
        val capped = spec(caps = doubleArrayOf(8.0, 10.0, 12.0))
        val t = gcode(FirstLayerPlate(capped))
        FirstLayerCheck.check(capped, t).shouldBeEmpty()
        gcheck(t, ownZ(capped)).shouldBeEmpty()
        // independently: a tile's flow is its speed through a 0.5 mm line's bead at its height (bead model); over the column's limit, no cell
        for ((k, cap) in listOf(8.0, 10.0, 12.0).withIndex()) for ((r, v) in speeds.withIndex()) for ((c, h) in heights.withIndex()) {
            val flow = v * h * (0.5 - h * (1 - Math.PI / 4))
            t.contains("; CELL: block ${k + 1} tile ${r + 1}.${c + 1}\n") shouldBe (flow <= cap)
            if (flow <= cap) firstZF(t, "block ${k + 1} tile ${r + 1}.${c + 1}").second shouldBe (v plusOrMinus 1e-9)
        }
        // e.g. the first column (8 mm3/s): 100 mm/s at 0.2 mm is 9.1 mm3/s, left out; at 0.16 mm 7.5, printed; the row keeps its label
        t shouldNotContain "; CELL: block 1 tile 3.2\n"
        t shouldContain "; CELL: block 1 tile 3.1\n"
        t shouldContain "; CELL: block 1 height 3.2 1\n"
        val md = FirstLayerInstructions.markdown(capped, FirstLayerPlate(capped), PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 40.0, 0.8), "plate.png")
        md shouldContain "| 100 |   | over the melt limit (8 mm3/s at 205 C) | over the melt limit (8 mm3/s at 205 C) | over the melt limit (8 mm3/s at 205 C) |  |"
        md shouldContain "100 mm/s at 0.2 mm (9.1 mm3/s)"
        // control: every tile printed, those over the limit too (at their speed): caught by the cells and by the flow read from the g-code
        val all = FirstLayerCheck.check(capped, ctl(capped, "omit"))
        all.any { it.contains("'block 1 tile 3.2': 9.1 mm3/s, over the melt limit 8.0 mm3/s at 205 C") } shouldBe true
        all.any { it.contains("in 'block 1 tile 3.2', which the specification does not have") } shouldBe true
    }

    "each tile starts at the middle of its back edge (independently, from the g-code), and its wall closes there; a corner start is caught" {
        for (cell in listOf("block 1 tile 1.1", "block 2 tile 2.3", "block 3 tile 3.4")) {
            val ss = segs(text, cell)
            val x0 = ss.minOf { minOf(it[0], it[2]) }; val x1 = ss.maxOf { maxOf(it[0], it[2]) }; val y1 = ss.maxOf { maxOf(it[1], it[3]) }
            ss.first()[0] shouldBe ((x0 + x1) / 2 plusOrMinus 0.002); ss.first()[1] shouldBe (y1 plusOrMinus 0.002)
            ss[4][2] shouldBe (ss.first()[0] plusOrMinus 0.002); ss[4][3] shouldBe (ss.first()[1] plusOrMinus 0.002)
            // its lines follow from the back: the first line right after the wall is the backmost one
            ss[5][1] shouldBe (ss.drop(5).maxOf { it[1] } plusOrMinus 0.002)
        }
        caught(sp, "corner", "not at the middle of its back edge")
    }

    "taller heights (0.24 .. 0.36 mm) and every tile printed: each row's place agrees with the check all the way back" {
        // each height's bead spacing is a fraction of a µm off the grid; summed over twelve rows the layout and the check must
        // still agree (the rows 1 mm apart stay caught)
        val hs = listOf(0.24, 0.28, 0.32, 0.36)
        // line widths 0.40 .. 0.60 mm, so each height's spacing falls at a different fraction of a µm
        for (w in 400L..600L step 10) {
            val tiles = FirstLayerTiles(speeds.toDoubleArray(), hs.map { Math.round(it * 1000) }.toLongArray(), w, 5000.0, 200, 0.25)
            val tall = FirstLayerSpec(0, "test", intArrayOf(205, 210, 215), 200, 1.75, 1.0, tiles, DoubleArray(3) { 40.0 }, PathValues(40.0, 5000.0, 1.0, 0.8), 500, frame, 450, 200, 60_000, 60_000)
            withClue("line width $w µm") { FirstLayerCheck.check(tall, gcode(FirstLayerPlate(tall))).shouldBeEmpty() }
        }
        val tall = spec(caps = DoubleArray(3) { 40.0 }, hs = hs)
        FirstLayerCheck.check(tall, ctl(tall, "rowgap")).any { it.contains("in its place touching its neighbors") } shouldBe true
    }

    "one piece: rows apart, or tiles printed highest first, are caught" {
        FirstLayerCheck.check(sp, ctl(sp, "rowgap", "baseapart")).any { it.contains("separate pieces, not one") } shouldBe true
        caught(sp, "rowgap", "in its place touching its neighbors")
        caught(sp, "order", "its tiles go lowest first")
        // the prime line along x, ending at its column's left edge, overlapping the base's front edge (0.15 mm)
        val p = segs(text, "block 1 prime"); val t11 = ext(text, "block 1 tile 1.1"); val base = segs(text, "block 1 base 1")
        p.all { Math.abs(it[1] - it[3]) < 1e-6 } shouldBe true
        (p.last()[2] - 0.25) shouldBe (t11[0] - 0.25 plusOrMinus 0.002)
        (p.first()[1] + 0.25 - (base.minOf { minOf(it[1], it[3]) } - 0.25)) shouldBe (0.15 plusOrMinus 0.002)
    }

    "the frame: three layers of rings over every tile's wall only, from a layer above it, after all the tiles; its controls are caught" {
        // independently, from the g-code: tile 1.1 (0.16) has rings at 0.36, 0.56, 0.76; tile 1.4 (0.28) at 0.48 ...
        firstZF(text, "block 1 frame 1.1 1").first shouldBe (0.36 plusOrMinus 1e-9)
        firstZF(text, "block 1 frame 1.1 3").first shouldBe (0.76 plusOrMinus 1e-9)
        firstZF(text, "block 3 frame 2.4 1").first shouldBe (0.48 plusOrMinus 1e-9)
        // after all the tiles
        (text.indexOf("; CELL: block 1 frame 1.1 1\n") > text.lastIndexOf("; CELL: block 3 tile ")) shouldBe true
        // over the wall only: the ring's center 0.075 mm in from the tile's edge, its 0.45 mm line reaching 0.3 mm in (within the
        // 0.5 mm wall) and 0.15 mm out over the neighbor's
        val t = ext(text, "block 2 tile 2.2"); val f = ext(text, "block 2 frame 2.2 2")
        (f[0] - (t[0] - 0.25)) shouldBe (0.075 plusOrMinus 0.002)
        (f[0] - (t[0] - 0.25) + 0.225 <= 0.5) shouldBe true
        // controls that must fail
        caught(sp, "frame", "no 'block 1 frame 1.1 1' cell")
        caught(sp, "framelayers", "no 'block 1 frame 1.1 2' cell")
        caught(sp, "frameinside", "not 0.08 mm in from its tile's edges")
        caught(sp, "framelow", "'block 1 frame 1.1 1': printed at z 0.16, not 0.36")
        FirstLayerCheck.check(sp, ctl(sp, "framelift")).any { it.contains("across finished 'block") && it.contains("frame") && it.contains("no lift") } shouldBe true
    }

    "the border: three layers of a ring round the base, on its upper layer and flush with its edge, with the frame; its controls are caught" {
        // independently, from the g-code: the base's top is its second layer, 0.36 mm (0.16 + 0.2); the border a layer each above
        firstZF(text, "block 1 border 1").first shouldBe (0.56 plusOrMinus 1e-9)
        firstZF(text, "block 1 border 3").first shouldBe (0.96 plusOrMinus 1e-9)
        // its ring 0.525 mm in from the base's first layer's edges (the 0.3 mm setback and half its 0.45 mm line): its line's
        // outer edge on the base's second layer's
        val b1 = ext(text, "block 1 base 1"); val ring = ext(text, "block 1 border 2")
        (ring[0] - (b1[0] - 0.25)) shouldBe (0.525 plusOrMinus 0.002)
        (ring[1] - (b1[1] - 0.25)) shouldBe (0.525 plusOrMinus 0.002)
        ((b1[3] + 0.25) - ring[3]) shouldBe (0.525 plusOrMinus 0.002)
        // round the L, not across the tiles: it reaches no further right than the base's front strip's right end, and its
        // back-right part stops at the tiles' left edge
        val t11 = ext(text, "block 1 tile 1.1")
        segs(text, "block 1 border 1").any { Math.abs(it[2] - (t11[0] - 0.25 - 0.525)) < 0.002 && Math.abs(it[3] - (t11[1] - 0.25 - 0.525)) < 0.002 } shouldBe true
        // in each frame layer, after the tiles' rings (it is that layer's highest)
        (text.indexOf("; CELL: block 1 border 1\n") > text.lastIndexOf("; CELL: block 3 frame 3.4 1\n")) shouldBe true
        (text.indexOf("; CELL: block 1 border 1\n") < text.indexOf("; CELL: block 1 frame 1.1 2\n")) shouldBe true
        // controls that must fail
        caught(sp, "border", "no 'block 1 border 1' cell")
        caught(sp, "borderedge", "not 0.53 mm in from the base's first layer's edges")
        caught(sp, "chamfer", "'block 1 border 1' does not follow the base's cut front-left corner")
    }

    "the tiles' line ends reach into the wall by the line-end overlap (a quarter line here), so no gap where a line turns on the other side" {
        // independently: tile 1.2's (0.2 mm) long lines reach 0.125 mm past bead contact with its wall at each side
        val ss = segs(text, "block 1 tile 1.2")
        val x0 = ss.minOf { minOf(it[0], it[2]) }; val x1 = ss.maxOf { maxOf(it[0], it[2]) }
        val sp = 0.5 - 0.2 * (1 - Math.PI / 4)
        val long = ss.drop(5).filter { Math.abs(it[1] - it[3]) < 1e-6 }
        long.forEach { minOf(it[0], it[2]) shouldBe (x0 + sp - 0.125 plusOrMinus 0.002); maxOf(it[0], it[2]) shouldBe (x1 - sp + 0.125 plusOrMinus 0.002) }
    }

    "the page: columns by temperature and melt limit, rows by speed and height, a table per column, the outcomes decided before printing" {
        val md = FirstLayerInstructions.markdown(sp, FirstLayerPlate(sp), PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 40.0, 0.8), "plate.png")
        md shouldContain "Level the bed"
        md shouldContain "| 1 | 205 C | 20 mm3/s | none |"
        md shouldContain "| 3 | 215 C | 20 mm3/s | none |"
        md shouldContain "| 5 | 70 | 0.16 |"
        md shouldContain "Column 2: 210 C"
        md shouldContain "| speed (mm/s) | 0.16 mm | 0.2 mm | 0.24 mm | 0.28 mm | notes |"
        md shouldContain "No tile is left out"
        md shouldContain "middle of its back edge"
        md shouldContain "Then peel the plate and hold it up to a light"
        md shouldContain "- **The plate is one piece**"
        md shouldContain "- **The base** runs along the plate's front and left, 2 layers, its front-left corner cut off"
        md shouldContain "| frame and border (not read) | after all the tiles, 3 layers of rings over every tile's wall, from a layer above its tile, and of a ring round the base, flush with its upper layer's edge, from a layer above its top; at the last column's temperature: 0.45 mm lines (the plate's ordinary line width), 0.2 mm high (the layer height), 100 mm/s (the machine's print speed, 100 mm/s), 5000 mm/s2; part fan off |"
        md shouldContain "`plymeld calibrate first-layer values TEMP=HEIGHT,SPEED ... --plate-gcode plate.gcode`"
        // the four tile outcomes, and the three about temperature
        md shouldContain "- **The window of good heights narrows as speed rises**: the fastest speed that still has a window is the first-layer speed."
        md shouldContain "- **The window is the same at every speed**"
        md shouldContain "- **The window sits off-center** (its middle not at 0.2 mm, the configured first layer's height)"
        md shouldContain "- **No tile is good at any speed**"
        md shouldContain "- **The window of good heights moves or widens with temperature**: the first-layer temperature with the widest window at the speed you want is this filament's first-layer temperature on this plate."
        md shouldContain "- **Temperature makes no difference in this range**: choose the first-layer temperature for other reasons (e.g. the print temperature)."
        md shouldContain "- **The first tiles after a temperature change show blobs or strings at their start**: the wait and prime, not the first layer; read those tiles' other edges."
    }

    "the filament plate has no first-layer tiles" {
        val bands = CalibrationSpec.bands(195, 225, 5, 50.0 to 100.0, 200, 2_400, 600)
        val plate = CalibrationSpec(0, "test", bands, 200, 440, 1.75, adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8),
            melt = null, flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000)
        CalibrationPlate(plate).gcode(s, setup.foreignTemplateVars, "test") shouldNotContain "tile "
    }
})
