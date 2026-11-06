package plymeld.check

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
 * The first-layer plate: one layer, a block of first-layer tiles per
 * nozzle temperature, coolest first, a wait and a prime line before each block, each block's temperature, speeds, and
 * heights printed beside it (each label on a line joining it to the block); tiles over the
 * block's melt limit left out; each tile starting at the middle of its back edge; each block one piece (a column's tiles
 * touching, connectors between columns, the prime line touching the first tile, the labels on rails into it). It
 * prints exactly as specified, and its read-back check can fail: each control below is caught.
 */
class FirstLayerPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    // speeds 40 .. 100 mm/s by row, heights 0.2 .. 0.36 mm by column (the default from a 0.4 mm bore), 0.5 mm lines, a 0.2 mm lift
    val heights = listOf(0.2, 0.24, 0.28, 0.32, 0.36)
    fun tiles(hs: List<Double> = heights) = FirstLayerTiles(doubleArrayOf(40.0, 60.0, 80.0, 100.0), hs.map { Math.round(it * 1000) }.toLongArray(), 500, 5000.0, 200, 0.25)
    // the frame: 100 mm/s, 5000 mm/s2 (held to the melt limit), 0.45 mm lines 0.2 mm high (the test's values)
    val frame = PathValues(100.0, 5000.0, 1.0, 0.8)
    // melt limits 20 mm3/s: no tile over (100 mm/s through the 0.36 mm tile's bead is 15.2)
    fun spec(temps: IntArray = intArrayOf(205, 210, 215), perRow: Int = 2, caps: DoubleArray = DoubleArray(temps.size) { 20.0 }, hs: List<Double> = heights) =
        FirstLayerSpec(0, "test", temps, 200, 1.75, 1.0, tiles(hs), caps, PathValues(40.0, 5000.0, 1.0, 0.8), 500, frame, 450, 200, perRow, 60_000, 60_000)
    val sp = spec()
    fun gcode(p: FirstLayerPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text = gcode(FirstLayerPlate(sp))
    fun ctl(spec: FirstLayerSpec, vararg w: String) = gcode(FirstLayerPlate(spec, without = w.toSet()))
    // the config's side of the read-back (a bed large enough for the test plate), every block's tiles and connectors at their own heights
    fun ownZ(spec: FirstLayerSpec) = (0 until spec.blocks).flatMap { k -> (0 until 4).flatMap { r -> spec.tiles.heightsUm.indices.map { c -> spec.tileLabel(k, r, c) to spec.tiles.heightsUm[c] / 1000.0 } } +
        (0 until spec.tiles.heightsUm.size - 1).flatMap { c -> listOf(false, true).flatMap { b -> listOf(spec.connectorLabel(k, c, b) to spec.connectorUm(c) / 1000.0, spec.bridgeLabel(k, c, b) to spec.bridgeUm(c) / 1000.0) } } +
        spec.tiles.heightsUm.indices.map { c -> spec.frameLabel(k, c) to spec.frameUm(c) / 1000.0 } + listOf(spec.primeLabel(k) to spec.primeUm / 1000.0) }.toMap()
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
    // [t] with the line [k] of the first match of [find] before block [block]'s first cell changed by [change] (null: removed)
    fun alterBefore(t: String, block: Int, find: (String) -> Boolean, change: ((String) -> String)?): String {
        val lines = t.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; CELL: block $block ") }
        val k = (0 until at).last { find(lines[it]) }
        if (change == null) lines.removeAt(k) else lines[k] = change(lines[k])
        return lines.joinToString("\n")
    }

    "temperatures: whole-degree steps, coolest first; one temperature allowed; a step that does not divide refused" {
        CalibrationSpec.temperatures(205, 225, 5) shouldBe listOf(205, 210, 215, 220, 225)
        CalibrationSpec.temperatures(215, 215, 5) shouldBe listOf(215)
        shouldThrow<IllegalStateException> { CalibrationSpec.temperatures(205, 222, 5) }.message!! shouldContain "does not divide"
        shouldThrow<IllegalArgumentException> { FirstLayerSpec(0, "t", intArrayOf(210, 205), 200, 1.75, 1.0, tiles(), doubleArrayOf(20.0, 20.0), PathValues(40.0, 5000.0, 1.0, 0.8), 500, frame, 450, 200, 2) }
    }

    "the plate as generated passes both checks" {
        FirstLayerCheck.check(sp, text).shouldBeEmpty()
        gcheck(text, ownZ(sp)).shouldBeEmpty()
    }

    "independently of the checks: one layer; blocks coolest first, each after a wait for its temperature, primed before its tiles; every tile at its height and its row's speed" {
        val lines = text.lines()
        lines.count { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") } shouldBe 1
        for ((k, temp) in listOf(205, 210, 215).withIndex()) {
            val first = lines.indexOfFirst { it.startsWith("; CELL: block ${k + 1} ") }
            lines[first] shouldBe "; CELL: block ${k + 1} prime"
            // the last nozzle command before the block: a wait for its own temperature
            lines.subList(0, first).last { it.startsWith("M104") || it.startsWith("M109") } shouldBe "M109 S$temp T0"
            // nothing of the next block before this one's last line
            if (k > 0) (lines.indexOfLast { it.startsWith("; CELL: block $k ") } < first) shouldBe true
            for (r in 1..4) for (c in 1..5) firstZF(text, "block ${k + 1} tile $r.$c") shouldBe (heights[c - 1] to listOf(40.0, 60.0, 80.0, 100.0)[r - 1])
        }
        // the prime lines and markers at the first layer's height
        firstZF(text, "block 2 prime").first shouldBe (0.2 plusOrMinus 1e-9)
        firstZF(text, "block 3 mark").first shouldBe (0.2 plusOrMinus 1e-9)
    }

    "independently: each block labeled with its temperature in front of its tiles, each row's speed left of it, each column's height behind it; blocks left to right, then a row behind" {
        // a cell's lines' extent (x0, y0, x1, y1), from the g-code
        fun ext(cell: String) = segs(text, cell).let { s -> doubleArrayOf(s.minOf { minOf(it[0], it[2]) }, s.minOf { minOf(it[1], it[3]) }, s.maxOf { maxOf(it[0], it[2]) }, s.maxOf { maxOf(it[1], it[3]) }) }
        for (k in 1..3) {
            val t11 = ext("block $k tile 1.1")
            // the temperature: between the prime line's run and the tiles, starting right of the tiles' left edge
            val mark = ext("block $k mark"); val run = segs(text, "block $k prime").first()[1]
            (mark[1] > run && mark[3] < t11[1] && mark[0] > t11[0]) shouldBe true
            // row r's speed: left of the first column's tile r, level with it (its rail reaching into the tile)
            for (r in 1..4) { val sl = ext("block $k speed $r"); val tr = ext("block $k tile $r.1")
                (sl[0] < tr[0] && sl[1] > tr[1] - 1 && sl[3] < tr[3] + 1) shouldBe true }
            // column c's height: behind its last tile (row 4: none left out here), within the column's width
            for (c in 1..5) { val hl = ext("block $k height $c"); val tc = ext("block $k tile 4.$c")
                (hl[1] > tc[3] - 1 && hl[0] > tc[0] - 0.5 && hl[2] < tc[2] + 0.5) shouldBe true }
        }
        // the temperatures differ, so the labels' lines do: block 1's (205) and block 3's (215), each from its own left
        fun shape(k: Int) = ext("block $k mark").let { e -> segs(text, "block $k mark").map { s -> listOf(s[0] - e[0], s[1] - e[1], s[2] - e[0], s[3] - e[1]).map { Math.round(it * 100) } } }
        (shape(1) == shape(3)) shouldBe false
        // block 2 right of block 1 (same row), block 3 behind block 1 (two to a row): where block k's prime line turns
        fun primeTurn(k: Int): Pair<Double, Double> = segs(text, "block $k prime").first().let { it[2] to it[3] }
        (primeTurn(2).first > primeTurn(1).first) shouldBe true; primeTurn(2).second shouldBe (primeTurn(1).second plusOrMinus 1e-9)
        (primeTurn(3).second > primeTurn(1).second + 40) shouldBe true; primeTurn(3).first shouldBe (primeTurn(1).first plusOrMinus 1e-9)
    }

    "controls: block 2 at block 1's temperature (wait removed, or at 205), or not waited for, is caught" {
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
        val wrongZ = alter("block 3 tile 1.2", "G1 Z") { "G1 Z0.2" }
        FirstLayerCheck.check(sp, wrongZ).any { it.contains("'block 3 tile 1.2': printed at z 0.2, not 0.24 (its height)") } shouldBe true
        gcheck(wrongZ, ownZ(sp)).any { it.contains("printing 'block 3 tile 1.2' at Z0.200, not its own height 0.240") } shouldBe true
        val wrongV = alter("block 2 tile 2.3", "G1 F") { "G1 F5400" }
        FirstLayerCheck.check(sp, wrongV).any { it.contains("'block 2 tile 2.3': 90.00 mm/s, not 60.00") } shouldBe true
        // the g-code check without the tiles named: their heights are lines off the layer's
        gcheck(text, emptyMap()).any { it.contains("on the layer at 0.200") } shouldBe true
    }

    "the lift: a travel crossing a FINISHED tile at or below its height is caught; one onto a tile not yet printed, at the same height, is not a fault" {
        // the generator lifts off each tile and connector; the plate as generated passes (above), although its travel from
        // each prime line onto its first tile starts at that tile's height (0.2 mm: the old endpoint rule refused it)
        val lines = text.lines()
        val primeEnd = (lines.indexOf("; CELL: block 1 prime") until lines.size).first { lines[it] == "; CELL END" }
        val travel = (primeEnd until lines.size).first { Regex("G1 X[-0-9.]+ Y[-0-9.]+( Z[0-9.]+)? F.*").matches(lines[it]) }
        (primeEnd until travel).none { lines[it].startsWith("G1 Z") } shouldBe true      // no separate lift before that travel
        // controls: the lift off the tiles left out; a travel at a finished tile's height straight across it (neither end on it)
        FirstLayerCheck.check(sp, ctl(sp, "tilelift")).any { it.contains("across finished 'block 1 tile 1.1'") && it.contains("no lift") } shouldBe true
        val across = lines.toMutableList().also { ls ->
            // after block 1's tiles: before its frame
            val at = ls.indexOf("; CELL: block 1 frame 1")
            val t11 = segs(text, "block 1 tile 1.1")
            val x0 = t11.minOf { minOf(it[0], it[2]) }; val x1 = t11.maxOf { maxOf(it[0], it[2]) }; val y = (t11.minOf { minOf(it[1], it[3]) } + t11.maxOf { maxOf(it[1], it[3]) }) / 2
            ls.addAll(at, listOf("G1 X${x0 - 2} Y$y F6000", "G1 Z0.2", "G1 X${x1 + 2} Y$y F6000", "G1 Z1.2"))
        }.joinToString("\n")
        FirstLayerCheck.check(sp, across).any { it.contains("a travel at z 0.200 across finished 'block 1 tile 1.1'") } shouldBe true
    }

    "a prime line lower than the first layer when the lowest tile is: it ends touching that tile, at its height" {
        val low = spec(hs = listOf(0.12, 0.16, 0.2, 0.24, 0.28))
        val t = gcode(FirstLayerPlate(low))
        FirstLayerCheck.check(low, t).shouldBeEmpty()
        gcheck(t, ownZ(low)).shouldBeEmpty()
        firstZF(t, "block 1 prime").first shouldBe (0.12 plusOrMinus 1e-9)
    }

    "controls: a prime line missing, a label showing another temperature, a speed or height label missing, a stray line are caught" {
        FirstLayerCheck.check(sp, ctl(sp, "prime")).any { it.contains("no 'block 3 prime' cell") } shouldBe true
        // block 3's label shows 205 (block 1's), the same width as its own 215: caught by its digits
        FirstLayerCheck.check(sp, ctl(sp, "mark")).any { it.contains("'block 3 mark': not the label '215' where it goes") } shouldBe true
        FirstLayerCheck.check(sp, ctl(sp, "speedlabel")).any { it.contains("no 'block 1 speed 1' cell") } shouldBe true
        FirstLayerCheck.check(sp, ctl(sp, "heightlabel")).any { it.contains("no 'block 1 height 1' cell") } shouldBe true
        val lines = text.lines().toMutableList()
        val end = (lines.indexOfFirst { it == "; CELL: block 1 mark" } until lines.size).first { lines[it] == "; CELL END" }
        lines.addAll(end + 1, listOf("G1 X50 Y50 F6000", "G1 X55 Y50 E0.2 F1200"))
        FirstLayerCheck.check(sp, lines.joinToString("\n")).any { it.contains("in '(none)'") } shouldBe true
    }

    "one temperature: one block, its wait the plate's own guard" {
        val one = spec(intArrayOf(215))
        val t = gcode(FirstLayerPlate(one))
        FirstLayerCheck.check(one, t).shouldBeEmpty()
        // control: that wait 5 C off
        FirstLayerCheck.check(one, alterBefore(t, 1, { it == "M109 S215 T0" }) { "M109 S210 T0" }).any { it.contains("at 210 C, not 215 C") } shouldBe true
    }

    "tiles over the block's melt limit are left out, never slowed; on the page as empty cells" {
        val capped = spec(caps = doubleArrayOf(8.0, 10.0, 12.0))
        val t = gcode(FirstLayerPlate(capped))
        FirstLayerCheck.check(capped, t).shouldBeEmpty()
        gcheck(t, ownZ(capped)).shouldBeEmpty()
        // independently: a tile's flow is its speed through a 0.5 mm line's bead at its height (bead model); over the block's limit, no cell
        for ((k, cap) in listOf(8.0, 10.0, 12.0).withIndex()) for ((r, v) in listOf(40.0, 60.0, 80.0, 100.0).withIndex()) for ((c, h) in heights.withIndex()) {
            val flow = v * h * (0.5 - h * (1 - Math.PI / 4))
            t.contains("; CELL: block ${k + 1} tile ${r + 1}.${c + 1}\n") shouldBe (flow <= cap)
            if (flow <= cap) firstZF(t, "block ${k + 1} tile ${r + 1}.${c + 1}").second shouldBe (v plusOrMinus 1e-9)
        }
        // e.g. block 1 (8 mm3/s): 100 mm/s at 0.2 mm is 9.1 mm3/s, left out; 80 mm/s at 0.2 mm is 7.3, printed
        t shouldNotContain "; CELL: block 1 tile 4.1\n"
        t shouldContain "; CELL: block 1 tile 3.1\n"
        val md = FirstLayerInstructions.markdown(capped, FirstLayerPlate(capped), PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 40.0, 0.8), "plate.png")
        md shouldContain "| 100 | over the melt limit (8 mm3/s at 205 C) |"
        md shouldContain "tile 4.1 (100 mm/s, 0.2 mm: 9.1 mm3/s)"
        // control: every tile printed, those over the limit too (at their row's speed): caught by the cells and by the flow read from the g-code
        val all = FirstLayerCheck.check(capped, ctl(capped, "omit"))
        all.any { it.contains("'block 1 tile 4.1': 9.1 mm3/s, over block 1's melt limit 8.0 mm3/s at 205 C") } shouldBe true
        all.any { it.contains("in 'block 1 tile 4.1', which the specification does not have") } shouldBe true
    }

    "each tile starts at the middle of its back edge (independently, from the g-code), and its wall closes there; a corner start is caught" {
        for (cell in listOf("block 1 tile 1.1", "block 2 tile 3.4", "block 3 tile 4.5")) {
            val ss = segs(text, cell)
            val x0 = ss.minOf { minOf(it[0], it[2]) }; val x1 = ss.maxOf { maxOf(it[0], it[2]) }; val y1 = ss.maxOf { maxOf(it[1], it[3]) }
            ss.first()[0] shouldBe ((x0 + x1) / 2 plusOrMinus 0.002); ss.first()[1] shouldBe (y1 plusOrMinus 0.002)
            ss[4][2] shouldBe (ss.first()[0] plusOrMinus 0.002); ss[4][3] shouldBe (ss.first()[1] plusOrMinus 0.002)
            // its lines follow from the back: the first line right after the wall is the backmost one
            ss[5][1] shouldBe (ss.drop(5).maxOf { it[1] } plusOrMinus 0.002)
        }
        FirstLayerCheck.check(sp, ctl(sp, "corner")).any { it.contains("not at the middle of its back edge") } shouldBe true
    }

    "each block is one piece: a column's tiles touch, connectors join the columns at the lower height, the prime touches the first tile" {
        // independently: tile 2.3's front wall line is one line width (0.5 mm) behind tile 1.3's back wall line (touching footprints)
        val a = segs(text, "block 1 tile 1.3"); val b = segs(text, "block 1 tile 2.3")
        (b.minOf { minOf(it[1], it[3]) } - a.maxOf { maxOf(it[1], it[3]) }) shouldBe (0.5 plusOrMinus 0.002)
        // the connectors between columns 1 and 2 at 0.2 mm (the lower), between 4 and 5 at 0.32
        firstZF(text, "block 1 connector 1-2 front").first shouldBe (0.2 plusOrMinus 1e-9)
        firstZF(text, "block 2 connector 4-5 back").first shouldBe (0.32 plusOrMinus 1e-9)
        // each printed before the higher column's first tile
        (text.indexOf("; CELL: block 1 connector 1-2 front") in 0 until text.indexOf("; CELL: block 1 tile 1.2")) shouldBe true
        // the prime line's last point half a line in front of the first tile's front wall's outer edge
        val p = segs(text, "block 1 prime").last(); val t11 = segs(text, "block 1 tile 1.1")
        (t11.minOf { minOf(it[1], it[3]) } - 0.25 - p[3]) shouldBe (0.25 plusOrMinus 0.002)
        // controls that must fail
        fun caught(w: String, what: String) = FirstLayerCheck.check(sp, ctl(sp, w)).any { it.contains(what) } shouldBe true
        caught("colgap", "not touching it")
        caught("colgap", "separate pieces, not one")
        caught("connectors", "separate pieces, not one")
        caught("conninside", "a connector stays outside every tile")
        caught("connhigh", "(the lower of the tiles it joins)")
        caught("connlate", "which is higher")
        // a column's tiles apart: the frame's sides would lie over the gaps between them
        caught("colgap", "runs over air")
    }

    "the frame: one layer over each tile's wall only, joining the columns, after the block's first layer; its controls are caught" {
        // independently, from the g-code: column 3's frame one layer (0.2 mm) above its tiles (0.28), the line across the gap
        // from column 2 to 3 at the higher column's frame height, column 1's at 0.4
        firstZF(text, "block 1 frame 1").first shouldBe (0.4 plusOrMinus 1e-9)
        firstZF(text, "block 2 frame 3").first shouldBe (0.48 plusOrMinus 1e-9)
        firstZF(text, "block 3 frame 2-3 front").first shouldBe (0.48 plusOrMinus 1e-9)
        // after every first-layer line of its block (its marker the last), before the next block's prime
        (text.indexOf("; CELL: block 1 frame 1\n") > text.lastIndexOf("; CELL: block 1 mark\n")) shouldBe true
        (text.indexOf("; CELL: block 1 frame 5\n") < text.indexOf("; CELL: block 2 prime\n")) shouldBe true
        // over the walls only: every frame line's footprint (0.45 mm) stays within 0.5 mm (the wall) of tile 2.1's edges or
        // outside it, read from the tile's own lines in the g-code
        val tile = segs(text, "block 1 tile 2.1")
        val tb = doubleArrayOf(tile.minOf { minOf(it[0], it[2]) } - 0.25, tile.minOf { minOf(it[1], it[3]) } - 0.25, tile.maxOf { maxOf(it[0], it[2]) } + 0.25, tile.maxOf { maxOf(it[1], it[3]) } + 0.25)
        val frame1 = segs(text, "block 1 frame 1")
        for (f in frame1) {
            // the frame lines are along x or y: their footprint's inner edge, measured into the tile from its nearer edge
            if (Math.abs(f[1] - f[3]) < 1e-6 && f[1] > tb[1] - 0.3 && f[1] < tb[3] + 0.3) (minOf(f[1] - tb[1], tb[3] - f[1]) + 0.225 <= 0.5 + 1e-6) shouldBe true
            if (Math.abs(f[0] - f[2]) < 1e-6) (minOf(f[0] - tb[0], tb[2] - f[0]) + 0.225 <= 0.5 + 1e-6) shouldBe true
        }
        // its ring goes round the column's four tiles: 4 sides, then 3 lines over the edges they share
        frame1.count { Math.abs(it[1] - it[3]) < 1e-6 } shouldBe 5
        // controls that must fail
        fun caught(w: String, what: String) = FirstLayerCheck.check(sp, ctl(sp, w)).any { it.contains(what) } shouldBe true
        caught("frame", "no 'block 1 frame 1' cell")
        caught("frameinside", "covers 'block 1 tile 1.1''s inside")
        caught("framebridges", "frame is 5 separate pieces, not one")
        caught("framelow", "'block 1 frame 1': printed at z 0.2, not 0.4")
        FirstLayerCheck.check(sp, ctl(sp, "framelift")).any { it.contains("across finished 'block 1 frame 1'") && it.contains("no lift") } shouldBe true
        // without its frame lines across the gaps, the first layer is still one piece (the connectors), the frame is not
        FirstLayerCheck.check(sp, ctl(sp, "framebridges")).none { it.contains("block 1 is") } shouldBe true
        // the connectors left out: the frame lines across the gaps would bridge bare bed
        caught("connectors", "runs over air")
    }

    "the tiles' line ends reach into the wall by the line-end overlap (a quarter line here), so no gap where a line turns on the other side" {
        // independently: tile 1.1's long lines reach 0.125 mm past bead contact with its wall at each side
        val ss = segs(text, "block 1 tile 1.1")
        val x0 = ss.minOf { minOf(it[0], it[2]) }; val x1 = ss.maxOf { maxOf(it[0], it[2]) }
        val sp = 0.5 - 0.2 * (1 - Math.PI / 4)
        val long = ss.drop(5).filter { Math.abs(it[1] - it[3]) < 1e-6 }
        long.forEach { minOf(it[0], it[2]) shouldBe (x0 + sp - 0.125 plusOrMinus 0.002); maxOf(it[0], it[2]) shouldBe (x1 - sp + 0.125 plusOrMinus 0.002) }
    }

    "the page: blocks by temperature and melt limit, a table per block, the outcomes decided before printing" {
        val md = FirstLayerInstructions.markdown(sp, FirstLayerPlate(sp), PlateInstructions.Info("pla test", "a printer", 0, "test", "plate.gcode", 9.0, "0.035 s", 60, 40.0, 40.0, 0.8), "plate.png")
        md shouldContain "Level the bed"
        md shouldContain "| 1 | 205 C | 20 mm3/s | none |"
        md shouldContain "| 3 | 215 C | 20 mm3/s | none |"
        md shouldContain "Block 2: 210 C"
        md shouldContain "| speed (mm/s) | 0.2 mm | 0.24 mm | 0.28 mm | 0.32 mm | 0.36 mm | notes |"
        md shouldContain "No tile is left out"
        md shouldContain "middle of its back edge"
        md shouldContain "**Each block is one piece**"
        md shouldContain "- **The frame** holds each block together"
        md shouldContain "| frame (not read) | a second layer over each tile's wall and across the gaps between columns, after each block's first layer, at that block's temperature: 0.45 mm lines (the plate's ordinary line width), 0.2 mm high (the layer height) on top of its tiles; 100 mm/s (the machine's print speed, 100 mm/s), 5000 mm/s2; part fan off |"
        // the four tile outcomes, and the three about temperature
        md shouldContain "- **The window of good heights narrows as speed rises**: the fastest speed that still has a window is the first-layer speed."
        md shouldContain "- **The window is the same at every speed**"
        md shouldContain "- **The window sits off-center** (its middle not at 0.2 mm, the configured first layer's height)"
        md shouldContain "- **No tile is good at any speed**"
        md shouldContain "- **The window of good heights moves or widens with temperature**: the first-layer temperature with the widest window at the speed you want is this filament's first-layer temperature on this plate."
        md shouldContain "- **Temperature makes no difference in this range**: choose the first-layer temperature for other reasons (e.g. the print temperature)."
        md shouldContain "- **The first tiles after a temperature change show blobs or strings at their start**: the wait/prime, not the first layer; read those tiles' other edges."
    }

    "the filament plate has no first-layer tiles" {
        val bands = CalibrationSpec.bands(195, 225, 5, 50.0 to 100.0, 200, 2_400, 600)
        val plate = CalibrationSpec(0, "test", bands, 200, 440, 1.75, adhesion = PathValues(40.0, 5000.0, 1.0, 0.8), frame = PathValues(100.0, 5000.0, 1.0, 0.8),
            melt = null, flowRatio = 1.0, retractMm = 0.8, originXUm = 100_000, originYUm = 100_000)
        CalibrationPlate(plate).gcode(s, setup.foreignTemplateVars, "test") shouldNotContain "tile "
    }
})
