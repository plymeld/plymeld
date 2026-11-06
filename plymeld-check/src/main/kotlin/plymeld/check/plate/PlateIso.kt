package plymeld.check.plate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Polygon
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Isometric diagram of a plymeld calibration plate, every region labeled with what it tests (`plymeld render-plate --iso`),
 * from its g-code and its instructions page (.md). Ported from the workspace script plate_iso.main.kts,
 * drawing the same picture; its description:
 *
 *   - geometry: every extrusion move (E > 0) inside a `; CELL: <name>` block of the g-code, every
 *     layer, drawn as a bead with a per-pixel depth buffer (real occlusion), shaded by a light
 *   - color: one per test region; within it, alternate bands lighter/darker and the layers where the
 *     temperature is changing grayed, so the band structure reads on every feature
 *   - bands: derived layer by layer from the g-code's own M104/M109 (nozzle) and M106 (part fan),
 *     NOT from the .md's band table (the two are compared and the comparison printed); a band starts at the last wait
 *     (M109) for its temperature, the guard the plate writes at every band's first layer (now: before that,
 *     by the fan, which a melt-test plate keeps at one level). (The motion plate, once a plate of this
 *     kind, is MotionPlate's now: its own page draws it, `MotionInstructions`.)
 *   - labels: wording and numbers parsed from the plate's .md page; a phrase the page no longer
 *     contains fails the render instead of drifting
 *   - anchors: each leader ends on a pixel that the depth buffer shows belongs to that region
 * Two kinds of picture, told apart by the page's title: the plates made by band of height ("# Melt plate",
 * "# Filament plate": a region per test, bands by height) and the first-layer plate ("# First-layer
 * plate": one layer, a region per block of tiles, colored cool to warm by temperature, its frame darker, and the prime
 * lines; each block's temperature and marker dots are read from the g-code too and must agree with the page's block
 * table, or the render fails).
 */
object PlateIso {
    fun render(gcodeFile: Path, pageFile: Path, outFile: Path, log: (String) -> Unit = ::println) {
        System.setProperty("java.awt.headless", "true")
        val gcodePath = gcodeFile.toString(); val mdPath = pageFile.toString(); val outPath = outFile.toString()
        val md = File(mdPath).readText()
        val mdLines = md.lines()
        val firstLayerKind = mdLines.first().startsWith("# First-layer plate")

        // ================================================================ g-code
        // [temp]: the nozzle target in force when it printed; [waited]: a wait (M109) for that target came after it was set
        class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val z: Double, val w: Double, val cell: String, val layer: Double, val temp: Int = 0, val waited: Boolean = false,
                  val accel: Double = Double.NaN)
        // [guard]: a wait (M109) came since the layer before's first line (the plate waits at every band's first layer)
        class LayerState(val z: Double, val temp: Int, val reached: Boolean, val fan: Int, val guard: Boolean = false)

        val segs = ArrayList<Seg>()
        val layers = LinkedHashMap<Double, LayerState>()          // keyed by the g-code's ;Z: value
        run {
            var x = Double.NaN; var y = Double.NaN; var z = 0.0; var w = 0.44; var cell = ""
            var tool = 0; var fan = 0; var layerZ = 0.0; var waitedSince = false; var accel = Double.NaN
            val target = HashMap<Int, Int>(); val waited = HashMap<Int, Int>()
            for (raw in File(gcodePath).readLines()) {
                val line = raw.trim()
                if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: ").trim(); continue }
                if (line == "; CELL END") { cell = ""; continue }
                if (line.startsWith(";Z:")) { layerZ = line.removePrefix(";Z:").trim().toDouble(); continue }
                if (line.startsWith(";WIDTH:")) { w = line.removePrefix(";WIDTH:").trim().toDouble(); continue }
                val code = line.substringBefore(';').trim(); if (code.isEmpty()) continue
                val ws = code.split(Regex("\\s+"))
                fun num(c: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == c }?.substring(1)?.toDouble()
                when {
                    ws[0] == "G1" || ws[0] == "G0" -> {
                        num('Z')?.let { z = it }
                        val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0   // M83: E is relative
                        val len = if (x.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
                        // the plate's own cells only (the start template's prime line is not part of the plate)
                        if (e > 0 && len > 1e-9 && cell.isNotEmpty()) {
                            segs += Seg(x, y, nx, ny, z, w, cell, layerZ, target[tool] ?: 0, target[tool] != null && waited[tool] == target[tool], accel)
                            if (layerZ !in layers) {
                                val t = target[tool] ?: 0
                                layers[layerZ] = LayerState(layerZ, t, waited[tool] == t, fan, waitedSince); waitedSince = false
                            }
                        }
                        x = nx; y = ny
                    }
                    ws[0] == "M104" -> { val t = num('T')?.toInt() ?: tool; num('S')?.let { target[t] = it.toInt() } }
                    ws[0] == "M109" -> { val t = num('T')?.toInt() ?: tool; num('S')?.let { target[t] = it.toInt(); waited[t] = it.toInt() }; waitedSince = true }
                    // the part fan is the M106 without a P index (P2/P3/P101 are the machine's other fans)
                    ws[0] == "M106" && num('P') == null -> fan = Math.round((num('S') ?: 255.0) / 255 * 100).toInt()
                    ws[0] == "M107" -> fan = 0
                    ws[0] == "SET_VELOCITY_LIMIT" -> ws.firstOrNull { it.startsWith("ACCEL=") }?.let { accel = it.removePrefix("ACCEL=").toDouble() }
                    ws[0] == "M204" -> num('S')?.let { accel = it }
                    ws[0].matches(Regex("T\\d+")) -> tool = ws[0].substring(1).toInt()
                }
            }
        }
        require(segs.isNotEmpty()) { "no extrusion inside a CELL in $gcodePath" }

        // ---- bands, from the g-code: for each run of layers at one nozzle target, the band runs from its LAST wait (the guard at
        // the band's first layer) to the run's end: the layers before it are the change (not read) and, in band 1's run, the
        // first layer and the bases.
        class Band(val n: Int, val layers: List<LayerState>) {
            val zLo get() = this.layers.first().z; val zHi get() = this.layers.last().z
            val temp get() = this.layers.first().temp; val fan get() = this.layers.first().fan
        }
        val layerList = layers.values.sortedBy { it.z }
        val firstLayerZ = layerList.first().z
        val bands = ArrayList<Band>()
        run {
            var i = 0
            while (i < layerList.size) {
                var j = i; while (j + 1 < layerList.size && layerList[j + 1].temp == layerList[i].temp) j++
                val run = layerList.subList(i, j + 1)
                val k = run.indexOfLast { it.guard }
                val tail = run.subList(maxOf(0, k), run.size)
                if (k >= 0 && tail.first().z != firstLayerZ && tail.last().reached) bands += Band(bands.size + 1, tail.toList())
                i = j + 1
            }
        }
        val bandOf = HashMap<Double, Int>().apply { for (b in bands) for (l in b.layers) put(l.z, b.n) }

        // ================================================================ the .md page
        fun mdHas(s: String): String { require(md.contains(s)) { "the .md page no longer says: \"$s\"" }; return s }
        // a markdown table, found by the start of its header row; rows as cell lists (header and separator dropped)
        fun table(headerStart: String): List<List<String>> {
            val i = mdLines.indexOfFirst { it.startsWith("| $headerStart") }
            require(i >= 0) { "no table starting \"| $headerStart\" in the .md page" }
            return mdLines.drop(i + 2).takeWhile { it.startsWith("|") }.map { r -> r.trim('|').split("|").map { it.trim() } }
        }
        fun num(s: String) = Regex("[-0-9.]+").find(s)!!.value
        // "**what it measures**, qualifier (the region)" in the page's opening paragraph
        val measures = HashMap<String, String>().apply {
            for (m in Regex("""\*\*([^*]+)\*\*([^(*]*)\((the [^)]+)\)""").findAll(md))
                putIfAbsent(m.groupValues[3], (m.groupValues[1] + m.groupValues[2]).trim().trimEnd(','))   // the opening paragraph's, the first
        }
        // ("its flow ratio" leans on the sentence before it; the page calls it "this filament's" elsewhere)
        fun measure(key: String) = (measures[key] ?: error("the .md page has no \"**...** ($key)\" phrase")).replace(Regex("^its "), "this filament's ")

        // (lazy: each plate kind reads only its own page's tables)
        val ledges by lazy { table("ledge (from the left)") }; val spans by lazy { table("span (from the left)") }
        val holes by lazy { table("hole (from the left)") }; val tubes by lazy { table("tube (from the left)") }
        val hops by lazy { table("hop (gap") }
        val tileRows by lazy { table("tile row (from the front)") }; val tileCols by lazy { table("tile column (from the left)") }
        // the page's bands table: the first table under its "## The bands" heading (with a notch column where the plate has the post)
        val mdBands by lazy {
            val h = mdLines.indexOfFirst { it == "## The bands" }; require(h >= 0) { "no '## The bands' section in the .md page" }
            val i = (h until mdLines.size).first { mdLines[it].startsWith("| band |") }
            mdLines.drop(i + 2).takeWhile { it.startsWith("|") }.map { r -> r.trim('|').split("|").map { it.trim() } }
        }
        val mdBlocks by lazy { table("block | nozzle") }
        val conditions by lazy { table("condition |").associate { it[0] to it[1] } }
        val decks by lazy { conditions["bridge decks"] ?: error("no 'bridge decks' condition row") }
        val pegSize by lazy { Regex("""a round column \(([^)]+)\)""").find(md)?.groupValues?.get(1) ?: error("no peg size in the .md page") }
        val ringPosts by lazy { Regex("""is a ring of (\d+ round posts \([^)]+\))""").find(md)?.groupValues?.get(1) ?: error("no stringing ring in the .md page") }
        val holeBand by lazy { Regex("""standing on a pedestal so it prints in (band \d+ \([^)]+\))""").find(md)?.groupValues?.get(1) ?: error("no fit-test band in the .md page") }

        // ================================================================ regions
        class Region(val name: String, val color: Color, val lines: List<String>, val anchorSubs: List<String>)
        fun arrow(a: String, b: String) = "$a -> $b"
        // a filament plate's CELL (without "adhesion:") -> (its family, sub-part); a plate built with some families left
        // out ('none') has no cells for them, and draws and labels only the families it has
        fun filamentKey(c: String): Pair<String, String>? = when {
            // each family's base, by the family's --tests name
            c.startsWith("base ") -> mapOf("melt" to "melt", "overhangs" to "ledges", "bridges" to "bridges", "fit" to "fit",
                "line-width" to "tubes", "stringing" to "locks")[c.removePrefix("base ")]?.let { it to "base" }
            c == "post" -> "post" to "post"
            // the terraced melt test: each step's top (its strips) and the bodies under them
            c.matches(Regex("melt \\d+\\.(fast|mid|slow)\\.\\d+")) -> "melt" to "strips"
            c.matches(Regex("melt body \\d+\\.(fast|mid|slow)")) -> "melt" to "bodies"
            // each step's solid floor, the layer under its strips
            c.matches(Regex("melt floor \\d+\\.(fast|mid|slow)")) -> "melt" to "floors"
            c == "ledges" -> "ledges" to "ledges"
            c == "pier" -> "bridges" to "piers"
            c.matches(Regex("deck \\d+")) -> "bridges" to "decks"
            c == "fit block" -> "fit" to "block"
            c == "pedestal" -> "fit" to "pedestal"        // the fit block's (checked below: it lies under the block)
            c == "peg" -> "fit" to "peg"
            c.matches(Regex("tube \\d+")) -> "tubes" to "tubes"
            c.matches(Regex("lock \\d+")) -> "locks" to "locks"
            else -> null
        }
        val families = listOf("post", "melt", "ledges", "bridges", "fit", "tubes", "locks")
        val present = if (firstLayerKind) emptyList() else
            segs.mapNotNull { filamentKey(it.cell.removePrefix("adhesion:"))?.first }.toSet().let { p -> families.filter { it in p } }
        fun famIndex(key: String) = present.indexOf(key)
        fun filamentRegions() = present.map { key -> when (key) {
            "post" -> Region("Post (the ruler)", Color(105, 108, 120), listOf(
                mdHas("is the ruler").removePrefix("is ") + ": " + mdHas("one notch") + " per band (notches " + mdBands.joinToString(", ") { it[1] } + ", from the bottom)",
                "its " + mdHas("chamfered corner marks the front-left (origin)")) +
                // the bases, when the page has them: every test on its own, a notch in its front edge
                (if (md.contains("- **The bases**")) listOf("every test on a base (pale): " + mdHas("the bands start above the bases"),
                    "one notch in each base's front edge, " + mdHas("in its upper layers only") + ": " + mdHas("The notch stands in front of the test's **first item**").replace("**", "")) else emptyList()),
                listOf("post"))
            "melt" -> Region("Terraced melt test", Color(232, 118, 20), listOf(
                measure("the terraced melt test"),
                (Regex("""a flight of (\d+) \*\*steps\*\*""").find(md)?.groupValues?.get(1) ?: error("no step count in the .md page")) + " steps, " +
                    mdHas("band 1 (the hottest) the lowest step, at the front") + "; each step's " + mdHas("top is one layer of strips on a solid floor") + ", nothing printed on it",
                "on each: " + listOf("FAST", "MID", "SLOW").joinToString(" | ") { s -> "$s " + (Regex("""\*\*$s\*\* at ([0-9.]+ mm/s)""").find(md)?.groupValues?.get(1) ?: error("no $s speed in the .md page")) } +
                    ", left to right",
                // the strips by thickness, all one width
                (Regex("""\*\*(\d+ strips)\*\*, front to back, all ([0-9.]+) mm wide \(the plate's line width\), \*\*the thinnest at the front\*\*: ([0-9., ]+) mm thick""").find(md)
                    ?.let { "${it.groupValues[1]}, all ${it.groupValues[2]} mm wide, thinnest at the front: ${it.groupValues[3].trim()} mm thick" } ?: error("no strips in the .md page")) +
                    "; " + (Regex("""Each strip is (\d+ lines, each [0-9.]+ mm long)""").find(md)?.groupValues?.get(1) ?: error("no strip lines in the .md page")),
                "under each surface its floor (" + mdHas("one solid layer") + ", lines front to back) on a body (two walls, a grid); " +
                    (Regex("""the part fan (at [0-9.]+ % in every band|off in every band|by band)""").find(conditions["melt test"] ?: error("no 'melt test' condition row"))?.value ?: error("no fan in the 'melt test' condition row")),
                // the heights: band 1's body, then each later step two layers higher after its wait
                "band 1's step " + (Regex("""Band 1's step is (a body [0-9.]+ mm tall \(\d+ layers\) on the base)""").find(md)?.groupValues?.get(1) ?: error("no band 1 body in the .md page")) +
                    ", each later step " + mdHas("**two layers higher**").replace("**", "") + ": a wait (M109) for its temperature, its floor, its strips",
                mdHas("**Run the melt test first**").replace("**", "") + ", on a plate of its own"),
                listOf("strips"))
            "ledges" -> Region("Overhang ledges", Color(148, 78, 190), listOf(
                measure("the ledges"),
                "${ledges.size} ledges, share of a line " + arrow(ledges.first()[1], ledges.last()[1]) + " (" +
                    arrow(ledges.first()[3], ledges.last()[3]) + " from horizontal), " + mdHas("lowest share on the left")), listOf("ledges"))
            "bridges" -> Region("Bridges", Color(38, 108, 205), listOf(
                measure("the bridges"),
                "${spans.size} spans " + arrow(spans.first()[1], spans.last()[1]) + " mm between ${spans.size + 1} piers, shortest on the left",
                "decks: " + decks.substringBefore(";") + ", one pair at the top of each band, terraced: band 1's at the very front and back, each higher band's one deck further in (every deck a step of its own from above)"), listOf("piers", "decks"))
            "fit" -> Region("Fit test", Color(150, 100, 45), listOf(
                measure("the holes and the peg"),
                "${holes.size} blind holes " + arrow(holes.first()[1], holes.last()[1]) + " mm (clearance " + arrow(holes.first()[2], holes.last()[2]) +
                    " mm), smallest on the left; peg $pegSize",
                "block on a pedestal, so it prints in $holeBand"), listOf("block", "peg"))
            "tubes" -> Region("Line-width tubes", Color(18, 150, 165), listOf(
                measure("the tubes"),
                "${tubes.size} one-line square tubes, width " + arrow(tubes.first()[1], tubes.last()[1]) + " mm, narrowest on the left, every band"), listOf("tubes"))
            "locks" -> Region("Stringing ring", Color(215, 85, 165), listOf(
                measure("the ring"),
                "$ringPosts; ${hops.size - 1} hops counter-clockwise from post 1, retraction " + arrow(hops.first()[3], hops[hops.size - 2][3]) + " mm",
                "the " + mdHas("open gap") + " (post ${hops.last()[1]} to post 1, at the ring's front) is never crossed: the control"), listOf("locks"))
            else -> error("no region for the family '$key'")
        } }
        // the first-layer plate: a region per block (its row of the page's block table), colored cool to warm in print order,
        // and the prime lines
        fun blockColor(k: Int, n: Int): Color { val f = if (n <= 1) 0.5 else k.toDouble() / (n - 1)
            return Color((50 + 180 * f).toInt(), (95 + 40 * (1 - Math.abs(2 * f - 1))).toInt(), (215 - 175 * f).toInt()) }
        fun firstLayerRegions(): List<Region> {
            val n = mdBlocks.size
            val tiles = "tiles: rows by speed " + arrow(tileRows.first()[1], tileRows.last()[1]) + " mm/s (" + mdHas("the slowest at the front") + "), columns by nozzle height " +
                arrow(num(tileCols.first()[1]), num(tileCols.last()[1])) + " mm (" + mdHas("the lowest on the left") + ")"
            return mdBlocks.withIndex().map { (k, row) ->
                // the page's block table: block | nozzle | marker | melt limit | tiles left out | where
                Region("Block ${row[0]}: ${row[1]}", blockColor(k, n), listOf(
                    "nozzle ${row[1]}, marker: ${row[2]} in front of the tiles; ${row[5]}",
                    "melt limit ${row[3].substringBefore(" (")}; tiles left out (over it): ${if (row[4] == "none") "none" else Regex("tile \\d+\\.\\d+").findAll(row[4]).count().toString()}") +
                    (if (k == 0) listOf(tiles, "block n has n dots: " + mdHas("Count the dots, never trust the position alone"), mdHas("Each block is one piece") + ": columns joined by connectors", mdHas("The frame") + " (dark): a second layer over the tiles' walls only, across the gaps on the connectors; not read") else emptyList()),
                    listOf("tiles", "marker", "frame"))
            } + Region("Prime lines", Color(120, 120, 128), listOf(
                mdHas("The prime line") + ": one per block, along its front and back up its left edge to touch the first tile, laid after the wait for that block's temperature. " + mdHas("It is not read") + "."), listOf("prime"))
        }
        val regions = if (firstLayerKind) firstLayerRegions() else filamentRegions()
        // a CELL name (without its "adhesion:" first-layer prefix) -> (region index, sub-part)
        fun classify(cell: String): Pair<Int, String>? {
            if (firstLayerKind) {
                val m = Regex("block (\\d+) (prime|mark|tile \\d+\\.\\d+|connector \\d+-\\d+ (front|back)|frame \\d+(?:-\\d+ (?:front|back))?)").matchEntire(cell) ?: return null
                val k = m.groupValues[1].toInt() - 1
                return when { m.groupValues[2] == "prime" -> (regions.size - 1) to "prime"; m.groupValues[2] == "mark" -> k to "marker"; m.groupValues[2].startsWith("frame") -> k to "frame"; else -> k to "tiles" }
            }
            val (fam, sub) = filamentKey(cell.removePrefix("adhesion:")) ?: return null
            return famIndex(fam) to sub
        }
        class Group(val region: Int, val sub: String)
        val groups = ArrayList<Group>(); val groupIdx = HashMap<String, Int>()
        val segGroup = IntArray(segs.size)
        val unmapped = sortedSetOf<String>()
        for ((i, s) in segs.withIndex()) {
            val c = classify(s.cell)
            if (c == null) { unmapped += s.cell; segGroup[i] = -1; continue }
            val key = "${c.first}/${c.second}"
            segGroup[i] = groupIdx.getOrPut(key) { groups += Group(c.first, c.second); groups.size - 1 }
        }
        // check: the terraced melt test has each band's strips on ONE layer, and that layer is in the g-code's band
        // of the same number (else the page's "one top per band" is not what the g-code prints)
        if (!firstLayerKind && "melt" in present) run {
            val rx = Regex("melt (\\d+)\\.(fast|mid|slow)\\.\\d+")
            for ((b, ss) in segs.filter { rx.matches(it.cell) }.groupBy { rx.matchEntire(it.cell)!!.groupValues[1].toInt() }.toSortedMap()) {
                val zs = ss.map { it.layer }.distinct()
                require(zs.size == 1) { "melt step $b: strips on ${zs.size} layers (${zs.joinToString()}), not one" }
                require(bandOf[zs[0]] == b) { "melt step $b's top (Z ${zs[0]}) is in g-code band ${bandOf[zs[0]]}, not $b" }
                log("melt step $b: its top one layer, Z ${zs[0]}, in g-code band $b (${ss.first().temp} C); ${ss.map { it.cell }.distinct().size} strips")
            }
        }
        // check: the un-numbered "pedestal" really is under the fit block (else the mapping above is a guess)
        if (!firstLayerKind && "fit" in present) run {
            fun bb(sub: String): DoubleArray { val ss = segs.indices.filter { segGroup[it] >= 0 && groups[segGroup[it]].region == famIndex("fit") && groups[segGroup[it]].sub == sub }.map { segs[it] }
                return doubleArrayOf(ss.minOf { minOf(it.x0, it.x1) }, ss.minOf { minOf(it.y0, it.y1) }, ss.maxOf { maxOf(it.x0, it.x1) }, ss.maxOf { maxOf(it.y0, it.y1) }) }
            val p = bb("pedestal"); val b = bb("block")
            require(p[0] >= b[0] - 1 && p[1] >= b[1] - 1 && p[2] <= b[2] + 1 && p[3] <= b[3] + 1) { "the 'pedestal' cell is not under the fit block: ${p.toList()} vs ${b.toList()}" }
        }

        // ================================================================ projection
        val ZS = 2.0                                         // z drawn 2x so the bands read
        val EL = Math.toRadians(35.264); val AZ = Math.toRadians(225.0)   // isometric, camera at front-left
        val toCam = doubleArrayOf(Math.cos(EL) * Math.cos(AZ), Math.cos(EL) * Math.sin(AZ), Math.sin(EL))
        val fwd = toCam.map { -it }.toDoubleArray()
        val right = run { val r = doubleArrayOf(fwd[1], -fwd[0], 0.0); val l = Math.hypot(r[0], r[1]); doubleArrayOf(r[0] / l, r[1] / l, 0.0) }
        val up = doubleArrayOf(right[1] * fwd[2] - right[2] * fwd[1], right[2] * fwd[0] - right[0] * fwd[2], right[0] * fwd[1] - right[1] * fwd[0])
        fun dot(a: DoubleArray, x: Double, y: Double, z: Double) = a[0] * x + a[1] * y + a[2] * z * ZS
        val light = run { val l = doubleArrayOf(-0.75, -0.25, 1.0); val n = Math.sqrt(l.sumOf { it * it }); l.map { it / n }.toDoubleArray() }

        val minX = segs.minOf { minOf(it.x0, it.x1) }; val maxX = segs.maxOf { maxOf(it.x0, it.x1) }
        val minY = segs.minOf { minOf(it.y0, it.y1) }; val maxY = segs.maxOf { maxOf(it.y0, it.y1) }
        val maxZ = segs.maxOf { it.z }
        // the band ruler stands on the bed in front of the post's corner
        val rulerX = minX - 26; val rulerY = minY + 2                 // left of the front row, clear of its first item (a tube)
        val corners = ArrayList<DoubleArray>()
        for (x in listOf(minX - 6, maxX + 6)) for (y in listOf(minY - 6, maxY + 6)) for (z in listOf(0.0, maxZ)) corners += doubleArrayOf(x, y, z)
        if (!firstLayerKind) corners += doubleArrayOf(rulerX, rulerY, 0.0)       // the first-layer plate has no bands: no ruler
        val uLo = corners.minOf { dot(right, it[0], it[1], it[2]) }; val uHi = corners.maxOf { dot(right, it[0], it[1], it[2]) }
        val vLo = corners.minOf { dot(up, it[0], it[1], it[2]) }; val vHi = corners.maxOf { dot(up, it[0], it[1], it[2]) }

        // the plate (with its 6 mm of bed round it) fits between the label columns, 20 px clear of each: the terraced
        // bridge row made the plate deep enough that letting the bed's corners tuck under the columns put
        // plate pixels under a label (the labels are still checked against the plate itself)
        val colW = 500; val W = 2400; val top = 100; val bottom = 30
        val scale = minOf((W - 2 * colW - 40.0) / (uHi - uLo), 1100.0 / (vHi - vLo))
        val H = maxOf(1150, (top + (vHi - vLo) * scale + bottom).toInt())
        val ox = W / 2.0 - (uLo + uHi) / 2 * scale
        val oy = top + vHi * scale
        fun sx(x: Double, y: Double, z: Double) = ox + dot(right, x, y, z) * scale
        fun sy(x: Double, y: Double, z: Double) = oy - dot(up, x, y, z) * scale

        // ================================================================ colors
        fun mix(a: Color, b: Color, t: Double) = Color((a.red + (b.red - a.red) * t).toInt(), (a.green + (b.green - a.green) * t).toInt(), (a.blue + (b.blue - a.blue) * t).toInt())
        fun darker(c: Color, k: Double) = Color((c.red * k).toInt(), (c.green * k).toInt(), (c.blue * k).toInt())
        val WHITE = Color.WHITE; val GRAY = Color(150, 150, 155)
        fun subColor(g: Group): Color { val c = regions[g.region].color
            return when (g.sub) { "base" -> mix(c, WHITE, 0.72); "pedestals", "pedestal", "bodies" -> mix(c, GRAY, 0.5); "floors" -> mix(c, GRAY, 0.3); "marker" -> darker(c, 0.6); "frame" -> darker(c, 0.35); "decks" -> mix(c, WHITE, 0.6); else -> c } }   // decks pale: each terrace step stands out from the piers; the melt bodies gray under their bright tops
        // the band tint: odd bands base (a little darker), even bands lighter, the change between bands grayed
        fun bandTint(c: Color, layer: Double): Color {
            if (layer == firstLayerZ) return c
            val b = bandOf[layer] ?: return mix(c, Color(170, 170, 172), 0.7)
            return if (b % 2 == 1) darker(c, 0.86) else mix(c, WHITE, 0.32)
        }

        // ================================================================ rasterise (depth buffer, 2x supersampled)
        val SS = 2; val BW = W * SS; val BH = H * SS; val sPx = scale * SS
        val zbuf = FloatArray(BW * BH) { Float.POSITIVE_INFINITY }
        val cbuf = IntArray(BW * BH); val gbuf = IntArray(BW * BH) { -1 }
        val depthLo = corners.minOf { dot(fwd, it[0], it[1], it[2]) }; val depthHi = corners.maxOf { dot(fwd, it[0], it[1], it[2]) }
        val layerPx = 0.2 * ZS * Math.cos(EL) * sPx

        fun capsule(ax: Double, ay: Double, ad: Double, bx: Double, by: Double, bd: Double, r: Double, base: Color, shadeK: Double, gid: Int) {
            val x0 = maxOf(0, Math.floor(minOf(ax, bx) - r).toInt()); val x1 = minOf(BW - 1, Math.ceil(maxOf(ax, bx) + r).toInt())
            val y0 = maxOf(0, Math.floor(minOf(ay, by) - r).toInt()); val y1 = minOf(BH - 1, Math.ceil(maxOf(ay, by) + r).toInt())
            val vx = bx - ax; val vy = by - ay; val l2 = vx * vx + vy * vy
            val rMm = r / sPx
            for (py in y0..y1) for (px in x0..x1) {
                val qx = px + 0.5 - ax; val qy = py + 0.5 - ay
                val t = if (l2 < 1e-12) 0.0 else ((qx * vx + qy * vy) / l2).coerceIn(0.0, 1.0)
                val ex = qx - t * vx; val ey = qy - t * vy
                val d2 = ex * ex + ey * ey; if (d2 > r * r) continue
                val dist = Math.sqrt(d2); val kk = dist / r; val bulge = Math.sqrt(1 - kk * kk)
                val depth = (ad + t * (bd - ad) - bulge * rMm).toFloat()
                val i = py * BW + px
                if (depth >= zbuf[i]) continue
                // the bead is a cylinder: its normal swings from facing the camera (center) to the side (edge)
                var nx = toCam[0] * bulge; var ny = toCam[1] * bulge; var nz = toCam[2] * bulge
                if (dist > 1e-9) { val e3x = right[0] * ex - up[0] * ey; val e3y = right[1] * ex - up[1] * ey; val e3z = right[2] * ex - up[2] * ey
                    val el = Math.sqrt(e3x * e3x + e3y * e3y + e3z * e3z); nx += e3x / el * kk; ny += e3y / el * kk; nz += e3z / el * kk }
                val diffuse = maxOf(0.0, nx * light[0] + ny * light[1] + nz * light[2])
                val fog = 1 - 0.18 * ((depth - depthLo) / (depthHi - depthLo)).coerceIn(0.0, 1.0)
                val k = (0.42 + 0.68 * diffuse) * shadeK * fog
                zbuf[i] = depth
                cbuf[i] = (minOf(255, (base.red * k).toInt()) shl 16) or (minOf(255, (base.green * k).toInt()) shl 8) or minOf(255, (base.blue * k).toInt())
                gbuf[i] = gid
            }
        }
        val segCount = IntArray(regions.size)
        for ((i, s) in segs.withIndex()) {
            val gid = segGroup[i]
            val base = bandTint(if (gid < 0) GRAY else subColor(groups[gid]), s.layer)
            if (gid >= 0) segCount[groups[gid].region]++
            val zc = s.z - 0.1                                               // the bead's center, half a layer down
            val ax = sx(s.x0, s.y0, zc) * SS; val ay = sy(s.x0, s.y0, zc) * SS; val ad = dot(fwd, s.x0, s.y0, zc)
            val bx = sx(s.x1, s.y1, zc) * SS; val by = sy(s.x1, s.y1, zc) * SS; val bd = dot(fwd, s.x1, s.y1, zc)
            // a wall facing the light reads lighter than one turned away (horizontal normal, the side seen)
            var wx = -(s.y1 - s.y0); var wy = s.x1 - s.x0; val wl = Math.hypot(wx, wy); wx /= wl; wy /= wl
            if (wx * toCam[0] + wy * toCam[1] < 0) { wx = -wx; wy = -wy }
            val lh = Math.hypot(light[0], light[1])
            val shadeK = 0.82 + 0.25 * maxOf(0.0, (wx * light[0] + wy * light[1]) / lh)
            val r = maxOf(s.w / 2 * sPx, layerPx * 0.62)
            val n = maxOf(1, Math.ceil(Math.hypot(bx - ax, by - ay) / 10).toInt())    // short pieces keep each box small
            for (k in 0 until n) { val a = k.toDouble() / n; val b = (k + 1.0) / n
                capsule(ax + (bx - ax) * a, ay + (by - ay) * a, ad + (bd - ad) * a, ax + (bx - ax) * b, ay + (by - ay) * b, ad + (bd - ad) * b, r, base, shadeK, gid) }
        }
        // silhouette lines: darken a pixel whose neighbor is background or well behind it
        val drawn = cbuf.copyOf()
        for (py in 1 until BH - 1) for (px in 1 until BW - 1) {
            val i = py * BW + px; if (zbuf[i] == Float.POSITIVE_INFINITY) continue
            val z = zbuf[i]
            if (zbuf[i - 1] - z > 1.2f || zbuf[i + 1] - z > 1.2f || zbuf[i - BW] - z > 1.2f || zbuf[i + BW] - z > 1.2f) {
                val c = cbuf[i]; drawn[i] = (((c shr 16 and 255) * 0.5).toInt() shl 16) or (((c shr 8 and 255) * 0.5).toInt() shl 8) or ((c and 255) * 0.5).toInt()
            }
        }

        // ================================================================ compose
        val BG = Color(246, 246, 248)
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        g.color = BG; g.fillRect(0, 0, W, H)
        // the bed under the plate, with a 10 mm grid
        run {
            val bx0 = if (firstLayerKind) minX - 6 else minOf(minX - 6, rulerX - 4); val bx1 = maxX + 6; val by0 = minY - 6; val by1 = maxY + 6
            val p = Polygon(); for ((x, y) in listOf(bx0 to by0, bx1 to by0, bx1 to by1, bx0 to by1)) p.addPoint(sx(x, y, 0.0).toInt(), sy(x, y, 0.0).toInt())
            g.color = Color(229, 230, 234); g.fillPolygon(p)
            g.color = Color(216, 217, 222); g.stroke = BasicStroke(1f)
            var x = Math.ceil(bx0 / 10) * 10; while (x <= bx1) { g.draw(Line2D.Double(sx(x, by0, 0.0), sy(x, by0, 0.0), sx(x, by1, 0.0), sy(x, by1, 0.0))); x += 10 }
            var y = Math.ceil(by0 / 10) * 10; while (y <= by1) { g.draw(Line2D.Double(sx(bx0, y, 0.0), sy(bx0, y, 0.0), sx(bx1, y, 0.0), sy(bx1, y, 0.0))); y += 10 }
            g.color = Color(195, 196, 202); g.drawPolygon(p)
        }
        // downsample the plate over the background
        val covered = BooleanArray(W * H)
        for (py in 0 until H) for (px in 0 until W) {
            var r = 0; var gg = 0; var b = 0; var n = 0
            val bgc = img.getRGB(px, py)
            for (dy in 0 until SS) for (dx in 0 until SS) {
                val i = (py * SS + dy) * BW + px * SS + dx
                val c = if (zbuf[i] == Float.POSITIVE_INFINITY) bgc else { n++; drawn[i] }
                r += c shr 16 and 255; gg += c shr 8 and 255; b += c and 255
            }
            if (n > 0) { covered[py * W + px] = true; val q = SS * SS; img.setRGB(px, py, ((r / q) shl 16) or ((gg / q) shl 8) or (b / q)) }
        }
        // check: the drawn plate sits inside the image with margin
        val pxs = (0 until W * H).filter { covered[it] }
        val pbX0 = pxs.minOf { it % W }; val pbX1 = pxs.maxOf { it % W }; val pbY0 = pxs.minOf { it / W }; val pbY1 = pxs.maxOf { it / W }
        require(pbX0 >= 20 && pbY0 >= 20 && pbX1 <= W - 21 && pbY1 <= H - 21) { "plate runs off the image: x $pbX0..$pbX1, y $pbY0..$pbY1 of $W x $H" }

        // ================================================================ anchors: a visible pixel of each sub-part
        // the target is the projected centroid of the sub-part's top layer; the anchor is the nearest pixel the
        // depth buffer actually shows as that sub-part, preferring one whose 5x5 neighborhood is all that sub-part
        // a sub-part with no visible pixel gets its projected target instead, a dashed leader, and the name of
        // whatever the depth buffer shows in front of it
        class Anchor(val x: Double, val y: Double, val hiddenBehind: String?)
        // side: 0 = aim at the centroid; +1 = its label is on the left, -1 = on the right: then aim at the
        // sub-part's end on the label's side, so leaders are short and cross less (it stays a pixel OF the sub-part)
        fun anchor(gid: Int, side: Int = 0): Anchor {
            val ss = segs.indices.filter { segGroup[it] == gid }
            val zTop = ss.maxOf { segs[it].z }
            var tx = 0.0; var ty = 0.0; var tl = 0.0
            for (i in ss) { val s = segs[i]; if (s.z < zTop - 1e-6) continue; val l = Math.hypot(s.x1 - s.x0, s.y1 - s.y0)
                tx += (sx(s.x0, s.y0, s.z) + sx(s.x1, s.y1, s.z)) / 2 * l; ty += (sy(s.x0, s.y0, s.z) + sy(s.x1, s.y1, s.z)) / 2 * l; tl += l }
            tx = tx / tl * SS; ty = ty / tl * SS
            if (side != 0) {   // aim at the top layer's end on the label's side, a fifth of the way back toward the centroid
                val ends = ss.filter { segs[it].z >= zTop - 1e-6 }.flatMap { i -> val s = segs[i]
                    listOf(sx(s.x0, s.y0, s.z) to sy(s.x0, s.y0, s.z), sx(s.x1, s.y1, s.z) to sy(s.x1, s.y1, s.z)) }
                val e = if (side > 0) ends.minBy { it.first } else ends.maxBy { it.first }
                tx = (e.first * SS) * 0.8 + tx * 0.2; ty = (e.second * SS) * 0.8 + ty * 0.2
            }
            var best = -1; var bestD = Double.MAX_VALUE; var bestSolid = false
            for (py in 2 until BH - 2) for (px in 2 until BW - 2) {
                val i = py * BW + px; if (gbuf[i] != gid) continue
                var solid = true; loop@ for (dy in -2..2) for (dx in -2..2) if (gbuf[i + dy * BW + dx] != gid) { solid = false; break@loop }
                val d = Math.hypot(px - tx, py - ty)
                if ((solid && !bestSolid) || (solid == bestSolid && d < bestD)) { best = i; bestD = d; bestSolid = solid }
            }
            if (best < 0) {
                val front = gbuf[ty.toInt() * BW + tx.toInt()]
                val by = if (front < 0) "nothing (off the drawn plate)" else regions[groups[front].region].name.lowercase()
                if (side == 0) log("NOTE ${regions[groups[gid].region].name} / ${groups[gid].sub}: no pixel visible; hidden behind the $by")
                return Anchor(tx / SS, ty / SS, by)
            }
            return Anchor(((best % BW) + 0.5) / SS, ((best / BW) + 0.5) / SS, null)
        }
        val visible = IntArray(regions.size).also { v -> for (i in gbuf.indices) if (gbuf[i] >= 0) v[groups[gbuf[i]].region]++ }

        // ================================================================ labels
        val fTitle = Font("SansSerif", Font.BOLD, 17); val fBody = Font("SansSerif", Font.PLAIN, 15)
        val textW = colW - 60
        fun wrap(s: String, f: Font): List<String> {
            val fm = g.getFontMetrics(f); val out = ArrayList<String>(); var cur = ""
            for (word in s.split(" ")) { val t = if (cur.isEmpty()) word else "$cur $word"
                if (fm.stringWidth(t) > textW && cur.isNotEmpty()) { out += cur; cur = word } else cur = t }
            if (cur.isNotEmpty()) out += cur; return out
        }
        class Label(val name: String, val color: Color, var anchors: List<Anchor>, val body: List<String>, val gids: List<Int>) {
            val h = 14 + 22 + body.size * 19 + 6
            var left = true; var y = 0.0
            val ay get() = anchors.map { it.y }.average(); val ax get() = anchors.map { it.x }.average()
        }
        val labels = ArrayList<Label>()
        for ((ri, r) in regions.withIndex()) {
            val subs = r.anchorSubs.map { sub -> groupIdx["$ri/$sub"] ?: error("${r.name}: no cells for its '$sub'") }
            val anchors = subs.map { anchor(it) }
            val hidden = r.anchorSubs.zip(anchors).filter { it.second.hiddenBehind != null }
                .map { (sub, a) -> "($sub hidden behind the ${a.hiddenBehind} in this view: dashed leader)" }
            labels += Label(r.name, r.color, anchors, (r.lines + hidden).flatMap { wrap(it, fBody) }, subs)
        }
        // the band ruler (drawn below) stands on the bed in front of the post; its key is a callout of its own
        fun rulerPt(z: Double) = sx(rulerX, rulerY, z) to sy(rulerX, rulerY, z)
        if (!firstLayerKind && bands.isNotEmpty()) run {
            val (rx, ry) = rulerPt(0.0)                    // the leader meets the ruler's foot, clear of its numbers
            val body = bands.map { b -> "band ${b.n}: Z %.1f-%.1f (%d layers), %d C, fan %d %%".format(b.zLo, b.zHi, b.layers.size, b.temp, b.fan) } +
                (if (layerList.any { it.z > bands.first().zLo && it.z !in bandOf }) listOf("pale on the ruler and the plate: temperature changing between bands, not read") else emptyList()) +
                listOf("from the g-code: nozzle M104/M109, part fan M106; Z = each layer's top")
            labels += Label("Bands (from the g-code)", Color(95, 95, 105), listOf(Anchor(rx - 6, ry + 4, null)), body.flatMap { wrap(it, fBody) }, emptyList())
        }
        // columns: left of the plate's center goes left; balance so neither column overflows
        val midX = (pbX0 + pbX1) / 2.0
        for (l in labels) l.left = l.ax < midX
        val avail = H - top - 30
        while (labels.filter { it.left }.sumOf { it.h + 16 } > avail) labels.filter { it.left }.maxBy { it.ax }.left = false
        while (labels.filter { !it.left }.sumOf { it.h + 16 } > avail) labels.filter { !it.left }.minBy { it.ax }.left = true
        fun boxX(l: Label) = if (l.left) 20 else W - 20 - colW
        // stack each column in anchor order, as near each anchor's height as the column allows
        fun place() {
            for (side in listOf(true, false)) {
                val col = labels.filter { it.left == side }.sortedBy { it.ay }
                for (l in col) l.y = l.ay - l.h / 2.0
                for (i in col.indices) { val lo = if (i == 0) top.toDouble() else col[i - 1].y + col[i - 1].h + 16; if (col[i].y < lo) col[i].y = lo }
                for (i in col.indices.reversed()) { val hi = if (i == col.size - 1) H - 30.0 - col[i].h else col[i + 1].y - 16 - col[i].h; if (col[i].y > hi) col[i].y = hi }
                require(col.isEmpty() || col.first().y >= top - 1) { "labels do not fit in the ${if (side) "left" else "right"} column" }
            }
        }
        fun leaders() = labels.flatMap { l -> val ex = if (l.left) boxX(l) + colW.toDouble() else boxX(l).toDouble()
            l.anchors.map { a -> l.name to Line2D.Double(ex, a.y.coerceIn(l.y + 14, l.y + l.h - 14), a.x, a.y) } }
        fun crossings(): List<Pair<String, String>> { val ls = leaders(); val out = ArrayList<Pair<String, String>>()
            for (i in ls.indices) for (j in i + 1 until ls.size) if (ls[i].first != ls[j].first && ls[i].second.intersectsLine(ls[j].second)) out += ls[i].first to ls[j].first
            return out }
        // each region label may aim at its centroid or at its end on the label's side: try every combination,
        // keep the one with the fewest leader crossings (then the shortest leaders)
        run {
            val choices = labels.map { l -> if (l.gids.isEmpty()) listOf(l.anchors) else listOf(l.anchors, l.gids.map { anchor(it, if (l.left) 1 else -1) }) }
            var best = Long.MAX_VALUE; var bestPick = IntArray(labels.size)
            val n = choices.fold(1) { a, c -> a * c.size }
            for (code in 0 until n) {
                var c = code; val pick = IntArray(labels.size) { i -> val k = c % choices[i].size; c /= choices[i].size; k }
                for ((i, l) in labels.withIndex()) l.anchors = choices[i][pick[i]]
                place()
                val score = crossings().size * 1_000_000L + leaders().sumOf { Math.hypot(it.second.x2 - it.second.x1, it.second.y2 - it.second.y1) }.toLong()
                if (score < best) { best = score; bestPick = pick }
            }
            for ((i, l) in labels.withIndex()) l.anchors = choices[i][bestPick[i]]
            place()
            log("anchors aimed at: " + labels.withIndex().filter { it.value.gids.isNotEmpty() }.joinToString { (i, l) -> "${l.name}=" + (if (bestPick[i] == 0) "centroid" else "near end") })
        }
        // ---- the band ruler, from the g-code's layers: band numbers beside it, the key is its callout
        if (!firstLayerKind && bands.isNotEmpty()) run {
            val (bx, by) = rulerPt(0.0)
            g.stroke = BasicStroke(1.5f); g.color = Color(120, 120, 128); val (tx, ty) = rulerPt(maxZ); g.draw(Line2D.Double(bx, by, tx, ty))
            g.stroke = BasicStroke(10f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            for (l in layerList) {
                val b = bandOf[l.z]
                g.color = when { l.z == firstLayerZ -> Color(90, 90, 98); b == null -> Color(212, 212, 216); b % 2 == 1 -> Color(80, 80, 90); else -> Color(160, 160, 170) }
                val (x0, y0) = rulerPt(l.z - 0.2); val (x1, y1) = rulerPt(l.z); g.draw(Line2D.Double(x0, y0, x1, y1))
            }
            // band numbers on the ruler's left (open bed), haloed so they read over anything
            g.font = Font("SansSerif", Font.BOLD, 16)
            for (b in bands) { val (_, ym) = rulerPt((b.zLo - 0.2 + b.zHi) / 2)
                val glyph = g.font.createGlyphVector(g.fontRenderContext, "${b.n}").getOutline((bx - 22).toFloat(), (ym + 6).toFloat())
                g.stroke = BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.color = WHITE; g.draw(glyph)
                g.color = Color(40, 40, 46); g.fill(glyph) }
        }
        // leaders first (under the boxes), with a white halo so they read over the plate
        for (l in labels) {
            val c = darker(l.color, 0.8)
            val ex = if (l.left) boxX(l) + colW.toDouble() else boxX(l).toDouble()
            for (a in l.anchors) {
                val axp = a.x; val ayp = a.y
                val ey = ayp.coerceIn(l.y + 14, l.y + l.h - 14)
                val dash = if (a.hiddenBehind != null) floatArrayOf(7f, 6f) else null
                g.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.color = Color(255, 255, 255, 220); g.draw(Line2D.Double(ex, ey, axp, ayp))
                g.stroke = BasicStroke(2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, dash, 0f); g.color = c; g.draw(Line2D.Double(ex, ey, axp, ayp))
                g.color = WHITE; g.fill(Ellipse2D.Double(axp - 6, ayp - 6, 12.0, 12.0)); g.color = c
                if (a.hiddenBehind == null) g.fill(Ellipse2D.Double(axp - 4, ayp - 4, 8.0, 8.0))
                else { g.stroke = BasicStroke(2f); g.draw(Ellipse2D.Double(axp - 4, ayp - 4, 8.0, 8.0)) }
            }
        }
        for (l in labels) {
            val x = boxX(l); val y = l.y.toInt()
            g.color = Color(255, 255, 255); g.fillRoundRect(x, y, colW, l.h, 10, 10)
            g.color = mix(l.color, WHITE, 0.4); g.stroke = BasicStroke(1.5f); g.drawRoundRect(x, y, colW, l.h, 10, 10)
            g.color = l.color; g.fillRoundRect(x, y, 10, l.h, 6, 6)
            g.color = darker(l.color, 0.75); g.font = fTitle; g.drawString(l.name, x + 24, y + 28)
            g.color = Color(40, 40, 46); g.font = fBody
            for ((k, s) in l.body.withIndex()) g.drawString(s, x + 24, y + 28 + 22 + k * 19)
        }
        // checks: no label over another, none over the plate
        for (a in labels) for (b in labels) if (a !== b && a.left == b.left)
            require(a.y + a.h <= b.y || b.y + b.h <= a.y) { "labels ${a.name} and ${b.name} overlap" }
        for (l in labels) { var n = 0; for (py in maxOf(0, l.y.toInt()) until minOf(H, (l.y + l.h).toInt())) for (px in boxX(l) until boxX(l) + colW) if (covered[py * W + px]) n++
            require(n == 0) { "label ${l.name} covers $n plate pixels" } }
        // leaders that cross each other (reported, not fatal: some crossings cannot be avoided)
        for ((a, b) in crossings()) log("leader crossing: $a x $b")

        // ================================================================ title
        g.color = Color(25, 25, 30); g.font = Font("SansSerif", Font.BOLD, 26)
        g.drawString(mdLines.first().removePrefix("# ").substringBefore(":") + ", isometric: ${File(gcodePath).name}", 20, 42)
        g.font = Font("SansSerif", Font.PLAIN, 16); g.color = Color(70, 70, 78)
        if (firstLayerKind) {
            g.drawString("Geometry read from the g-code (every extrusion inside a ; CELL: one layer, each tile at its own height, a frame (dark) a layer over the walls where the plate has one, z drawn ${ZS.toInt()}x); labels from ${File(mdPath).name}.", 20, 70)
            g.drawString("Viewed from the front left; a color per block, cool to warm in print order. It measures " + measure("the blocks of tiles") + ".", 20, 92)
        }
        else g.drawString("Geometry read from the g-code (every extrusion inside a ; CELL, all ${layerList.size} layers, z drawn ${ZS.toInt()}x); labels from ${File(mdPath).name}. " +
            "Viewed from the front left; one color per test, alternate bands lighter and darker.", 20, 70)
        g.dispose()
        ImageIO.write(img, "png", File(outPath))

        // ================================================================ report
        log("wrote $outPath ($W x $H), ${segs.size} extrusion segments in CELLs")
        log("plate drawn at x $pbX0..$pbX1, y $pbY0..$pbY1 (margin ${minOf(pbX0, pbY0, W - 1 - pbX1, H - 1 - pbY1)} px)")
        for ((i, r) in regions.withIndex()) log("  %-18s %7d segments  %8d visible px  subs: %s".format(r.name, segCount[i], visible[i],
            groups.withIndex().filter { it.value.region == i }.joinToString { (gi, gr) -> "${gr.sub}=${segGroup.count { it == gi }}" }))
        require(segCount.all { it > 0 }) { "a region has no segments" }
        log("unmapped cells: " + (if (unmapped.isEmpty()) "none" else unmapped.joinToString()))
        require(unmapped.isEmpty() || !firstLayerKind) { "cells the first-layer plate should not have: $unmapped" }
        if (firstLayerKind) {
            // each block's temperature and wait, and its marker dots, from the g-code against the page's block table
            log("blocks from the g-code vs the .md table:")
            require(mdBlocks.size == regions.size - 1) { "the page lists ${mdBlocks.size} blocks" }
            for ((k, row) in mdBlocks.withIndex()) {
                val mine = segs.filter { it.cell.startsWith("block ${k + 1} ") }
                val temps = mine.map { it.temp }.distinct(); val allWaited = mine.all { it.waited }
                var boxes = mine.filter { it.cell.endsWith(" mark") }.map { s -> doubleArrayOf(minOf(s.x0, s.x1) - s.w / 2, minOf(s.y0, s.y1) - s.w / 2, maxOf(s.x0, s.x1) + s.w / 2, maxOf(s.y0, s.y1) + s.w / 2) }
                while (true) {
                    val merged = ArrayList<DoubleArray>()
                    for (b in boxes) { val hit = merged.firstOrNull { d -> b[0] <= d[2] && b[2] >= d[0] && b[1] <= d[3] && b[3] >= d[1] }
                        if (hit != null) { hit[0] = minOf(hit[0], b[0]); hit[1] = minOf(hit[1], b[1]); hit[2] = maxOf(hit[2], b[2]); hit[3] = maxOf(hit[3], b[3]) } else merged += b.copyOf() }
                    if (merged.size == boxes.size) break
                    boxes = merged
                }
                log("  block ${k + 1}: g-code ${temps.joinToString("/")} C, waited for: $allWaited, ${boxes.size} dot(s)   | .md: ${row.joinToString(" | ")}")
                require(temps == listOf(num(row[1]).toInt()) && allWaited) { "block ${k + 1}: the g-code prints at ${temps} C (waited: $allWaited), the page says ${row[1]}" }
                require(boxes.size == num(row[2]).toInt()) { "block ${k + 1}: ${boxes.size} marker dots in the g-code, the page says ${row[2]}" }
            }
            return
        }
        log("bands from the g-code vs the .md table:")
        for ((k, b) in bands.withIndex()) { val m = mdBands.getOrNull(k)
            log("  band ${b.n}: layers Z %.1f-%.1f (slab %.1f-%.1f), %d C, fan %d %%   | .md: %s".format(b.zLo, b.zHi, b.zLo - 0.2, b.zHi, b.temp, b.fan, m?.joinToString(" | ") ?: "MISSING")) }
        require(bands.size == mdBands.size) { "g-code has ${bands.size} bands, the .md page ${mdBands.size}" }
        // the page says the fit block prints in one band: check it against the g-code's bands
        if ("fit" in present) { val ri = famIndex("fit"); val pageBand = Regex("""band (\d+)""").find(holeBand)!!.groupValues[1].toInt()
            val zTop = segs.indices.filter { segGroup[it] == groupIdx["$ri/block"] }.maxOf { segs[it].layer }
            log("  ${regions[ri].name} / block: top layer Z $zTop is in g-code band ${bandOf[zTop] ?: "none (changing)"}; the page says band $pageBand")
            require(bandOf[zTop] == pageBand) { "the fit block's top is not in the band the page says" }
        }
    }
}
