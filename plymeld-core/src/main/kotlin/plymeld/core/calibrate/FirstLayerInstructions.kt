package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each first-layer plate ([FirstLayerPlate]; the filament plate's is [PlateInstructions]): what to
 * do before printing, the conditions, a labeled picture (the blocks by temperature, as their printed labels say), how to read it
 * block by block and speed by speed, a table to fill in, and what each kind of result means, written before the print.
 */
object FirstLayerInstructions {
    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: FirstLayerSpec, plate: FirstLayerPlate, info: PlateInstructions.Info) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, plate, info, png.fileName.toString()))
    }

    fun markdown(spec: FirstLayerSpec, plate: FirstLayerPlate, info: PlateInstructions.Info, pngName: String): String {
        val ti = spec.tiles
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        l("# First-layer plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating, the wait for each block's temperature, and any levelling come on top)" } ?: "") + ".")
        l("One layer of tiles (and a frame over their walls, not read): a block of first-layer tiles for each nozzle temperature, ${spec.tempsC.joinToString(", ")} C, printed coolest first. " +
            "This plate measures **the nozzle height, speed, and first-layer temperature a first layer goes down well at** (the blocks of tiles).")
        l()
        if (info.warnings.isNotEmpty()) {
            l("**Warnings:**")
            l()
            for (wn in info.warnings) l("- $wn")
            l()
        }
        l("## Before you print")
        l()
        l("The whole plate is a first layer, so the bed decides it:")
        l()
        l("1. **Level the bed.** " + when {
            info.levels -> "This file runs the printer's bed levelling (hardware.machine.<key>.gcode.level) before the plate, once it has heated. If your printer also offers levelling when a print starts, you need not use both."
            info.levelSkipped -> "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling (mesh) yourself first."
            else -> "plymeld cannot level this printer from the g-code (no levelling command is known for it): turn on the printer's own levelling when you start the print (e.g. its \"level before print\" option), or run it from the printer's menu first."
        } + " Every tile is read against its neighbors: a bed that is not level reads as a height that is wrong.")
        l("2. **Run any other calibration your printer offers before a print**: nozzle or tool offsets on a multi-tool machine, a z-offset, its own flow or pressure-advance calibration (note whether it ran: it changes the conditions below), a nozzle clean or wipe.")
        l("3. **Clean the bed** (no fingerprints: they show as tiles that did not stick)${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C and stays there.")
        l("4. **Load `${info.filament}` on tool ${info.tool + 1}.** Dry filament matters: moisture shows as rough, bubbly tiles at every height.")
        l("5. **During the print**: some tiles are meant to come out badly (too low or too high): do not stop for them. Between blocks the head moves off to the front of the plate and waits while the nozzle heats to the next block's temperature; that is expected. Each block then starts with its prime line, and ends with its frame (a second layer over the tiles' walls).")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| nozzle temperature | by block, below: ${spec.tempsC.first()} to ${spec.tempsC.last()} C, coolest first, a wait (M109) for each before it prints |")
        l("| tile line width | ${n(ti.widthUm / 1000.0, 2)} mm (the first-layer line width) |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off |")
        l("| first-layer tiles | speeds by row, nozzle heights by column (below), each tile exactly at its row's speed; ${n(ti.accel, 0)} mm/s2 (the first layer's acceleration); the nozzle lifts ${n(ti.liftUm / 1000.0, 2)} mm off each tile, connector, and frame line before moving on; each tile's lines reach ${n(ti.wallOverlap * 100, 0)} % of a line into its wall at their ends (the line-end overlap) |")
        val fv = (0 until spec.blocks).map { spec.frameSpeed(it) }
        l("| frame (not read) | a second layer over each tile's wall and across the gaps between columns, after each block's first layer, at that block's temperature: ${n(spec.frameWidthUm / 1000.0, 2)} mm lines (the plate's ordinary line width), " +
            "${n(spec.frameLayerUm / 1000.0, 2)} mm high (the layer height) on top of its tiles; ${if (fv.distinct().size == 1) "${n(fv[0], 0)} mm/s" else "by block ${fv.joinToString(", ") { n(it, 0) }} mm/s"} " +
            "(the machine's print speed, ${n(spec.frame.speed, 0)} mm/s${if (fv.any { it < spec.frame.speed - 1e-9 }) ", held to the block's melt limit" else ""}), ${n(spec.frame.accel, 0)} mm/s2; part fan off |")
        l("| melt limit | by block, below: the filament's maximum flow at the block's temperature; a tile that would need more is left out, never slowed |")
        l("| prime lines, connectors, labels | prime lines ${n(spec.primeUm / 1000.0, 2)} mm high, connectors at the lower of the two columns' heights, labels ${n(spec.layerUm / 1000.0, 2)} mm (the first layer's height); ${n(spec.prime.speed, 0)} mm/s, ${n(spec.firstWidthUm / 1000.0, 2)} mm lines; not read |")
        l("| retraction | ${n(info.retractMm, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: the blocks by temperature]($pngName)")
        l()
        l("- **The blocks** are the ${spec.blocks} groups of tiles, one per nozzle temperature, laid out **left to right" + (if (plate.blockRows > 1) ", then a further row behind" else "") + ", in the order they print: the coolest first**. " +
            "**Each block's temperature is printed in front of it**, on its prime line; **each row's speed** (mm/s) left of it and **each column's nozzle height** (mm) behind it, turned to read from the front. " +
            "Each label stands on a line that joins it to the block, so it peels off with it; read the labels, never trust the position alone.")
        l("- **The prime line** runs along the front of each block, in front of its temperature, then back up its left edge to touch the first tile: laid after the wait for that block's temperature, so the first tile does not start with what oozed during the wait. It is not read.")
        l("- Inside each block, the **first-layer tiles**: up to ${ti.speeds.size * ti.heightsUm.size} flat square tiles, one layer each, each at its own nozzle height and speed. " +
            "**Rows are speeds, the slowest at the front; columns are nozzle heights, the lowest on the left**: the front-left tile of each block is the lowest and slowest, the back-right one the highest and fastest. " +
            "Each tile is a wall round lines laid side by side at the spacing a line of its height should fill exactly, ${n(ti.widthUm / 1000.0, 2)} mm wide, so how its top closes up tells whether that height was right. " +
            "Each tile starts, and its wall closes, at the **middle of its back edge** (any start or stop blob is there); its lines follow from the back.")
        l("- **Each block is one piece**, so it peels off at once: a column's tiles touch edge to edge, the columns are joined by short **connectors** (two lines across the gap at the front and back corners, at the lower column's height), the prime line touches the first tile, and each label stands on a line joined to the block. Peel each block as a whole; the connectors are not read.")
        l("- **The frame** holds each block together as it peels: after the block's tiles, a second layer of lines laid **only over the tiles' walls** (round each column and along every edge two of its tiles share, one line over both walls) and across the gaps between columns on top of the connectors, " +
            "stepping up to the higher column. It bonds layer on layer to the walls below, so each column is stiff and the columns are one lattice. **Each tile's inside, the part you read, is left uncovered**: read the tiles inside the frame; the frame is not read.")
        l("- **Tiles over the melt limit are left out**: a tile whose flow (its speed x its line's cross-section x the flow ratio) would pass the filament's maximum flow at its block's temperature is not printed; its place stays empty, so nothing on the plate claims to test what it cannot.")
        l()
        l("| block | nozzle | melt limit | tiles left out | where |")
        l("|---|---|---|---|---|")
        for (k in 0 until spec.blocks) {
            val left = ti.speeds.indices.flatMap { r -> ti.heightsUm.indices.filter { c -> !spec.printed(k, r, c) }.map { c -> "${ti.label(r, c)} (${n(ti.speeds[r], 0)} mm/s, ${n(ti.heightsUm[c] / 1000.0, 2)} mm: ${n(spec.tileFlow(r, c), 1)} mm3/s)" } }
            l("| ${k + 1} | ${spec.tempsC[k]} C | ${n(spec.capsMm3s[k], 1)} mm3/s${spec.capFrom.getOrNull(k)?.let { " ($it)" } ?: ""} | ${if (left.isEmpty()) "none" else left.joinToString(", ")} | row ${k / spec.perRow + 1} of blocks (from the front), ${k % spec.perRow + 1} from the left |")
        }
        l()
        l("| tile row (from the front) | first-layer speed (mm/s) |")
        l("|---|---|")
        for ((k, v) in ti.speeds.withIndex()) l("| ${k + 1} | ${n(v, 0)} |")
        l()
        l("| tile column (from the left) | nozzle height (mm) |")
        l("|---|---|")
        for ((k, hz) in ti.heightsUm.withIndex()) l("| ${k + 1} | ${n(hz / 1000.0, 2)}${if (hz == spec.layerUm) " (the configured first layer's)" else ""} |")
        l()
        if ((0 until spec.blocks).all { k -> ti.speeds.indices.all { r -> ti.heightsUm.indices.all { c -> spec.printed(k, r, c) } } })
            l("No tile is left out: every one stays within its block's melt limit.")
        else l("Every tile prints at its row's speed; the ones that would pass their block's melt limit are left out (the table above, and the empty cells in the tables below).")
        l()
        val mid = n(spec.layerUm / 1000.0, 2)
        l("## How to read the first-layer tiles")
        l()
        l("Look at the tiles with a low light from the side, so every ridge and gap casts a shadow, and run a fingernail across them. Find each block by its printed temperature. For each tile:")
        l()
        l("1. A **good** tile is closed, flat, and square-cornered, its lines joined into one smooth sheet, nothing lifted at its edges or corners.")
        l("2. **Too low**: ridges between the lines, plastic ploughed up or pushed to the edges, or thin, see-through spots where the nozzle scraped the bed.")
        l("3. **Too high**: gaps between the lines, lines that stay round beads rather than flattening, or a tile that peels off easily (it did not stick).")
        l("4. **For each block (temperature) and each speed (row), tick the heights whose tiles are good.** The good heights in a row are that speed's window at that temperature.")
        l()
        for (k in 0 until spec.blocks) {
            l("Block ${k + 1}: ${spec.tempsC[k]} C")
            l()
            l("| speed (mm/s) | " + ti.heightsUm.joinToString(" | ") { "${n(it / 1000.0, 2)} mm" } + " | notes |")
            l("|---|" + ti.heightsUm.joinToString("") { "---|" } + "---|")
            for ((r, v) in ti.speeds.withIndex()) l("| ${n(v, 0)} | " + ti.heightsUm.indices.joinToString(" | ") { c ->
                if (spec.printed(k, r, c)) " " else "over the melt limit (${n(spec.capsMm3s[k], 1)} mm3/s at ${spec.tempsC[k]} C)" } + " |  |")
            l()
        }
        l("### What the first-layer results mean (decided before printing)")
        l()
        l("- **The window of good heights narrows as speed rises**: the fastest speed that still has a window is the first-layer speed.")
        l("- **The window is the same at every speed**: speed does not matter in this range; ${n(ti.speeds.last(), 0)} mm/s will do, and the next plate can go faster (`--first-speeds`).")
        l("- **The window sits off-center** (its middle not at $mid mm, the configured first layer's height): the printer's levelling or z-offset is off by that much; plymeld could take it as a first-layer height correction for this machine, plate, and filament (not yet).")
        l("- **No tile is good at any speed**: not the height or the speed. Look at the bed's cleanliness, the bed temperature, or a bed that is not flat across the plate (compare the tiles by position: one side or corner worse than the rest).")
        l("- **The window of good heights moves or widens with temperature**: the first-layer temperature with the widest window at the speed you want is this filament's first-layer temperature on this plate.")
        l("- **Temperature makes no difference in this range**: choose the first-layer temperature for other reasons (e.g. the print temperature).")
        l("- **The first tiles after a temperature change show blobs or strings at their start**: the wait/prime, not the first layer; read those tiles' other edges.")
        l()
        l("plymeld cannot read these results back in yet (`plymeld calibrate read` is planned). Keep the filled tables and this page together.")
        return sb.toString()
    }

    /** The picture: the plate from above as the program prints it, its printed labels among its lines, each block named with its temperature and melt limit, the prime lines. */
    fun picture(file: java.nio.file.Path, spec: FirstLayerSpec, plate: FirstLayerPlate) {
        System.setProperty("java.awt.headless", "true")
        val paths = plate.program().steps.filterIsInstance<plymeld.program.Extrude>()
        val W = 1800; val H = 1300
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 252); g.fillRect(0, 0, W, H)
        val b = plate.bounds()
        val ox = spec.originXUm; val oy = spec.originYUm
        val s = minOf((W - 160).toDouble() / (b[2] - b[0] + 24_000), (H - 260).toDouble() / (b[3] - b[1] + 24_000))
        fun px(x: Long) = 80 + (x - ox - b[0] + 12_000) * s
        fun py(y: Long) = 120 + (H - 260) - (y - oy - b[1] + 12_000) * s
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString("First-layer plate from above: a block of tiles per nozzle temperature, coolest first, each with its values printed beside it", 40, 40)
        g.font = Font("SansSerif", Font.PLAIN, 16)
        g.drawString("In each block: rows by speed (the slowest at the front), columns by nozzle height (the lowest on the left); black dot = where a tile starts; dashed: left out, over the melt limit.", 40, 68)
        g.drawString("Gray: prime line and connectors; dark outline: the frame, a second layer over the tiles' walls and across the gaps (none of these is read: read each tile inside its frame).", 40, 90)
        // a color per block, cool to warm
        fun blockColor(k: Int): Color { val f = if (spec.blocks == 1) 0.5 else k.toDouble() / (spec.blocks - 1)
            return Color((60 + 170 * f).toInt(), (110 + 30 * (1 - Math.abs(2 * f - 1))).toInt(), (200 - 160 * f).toInt()) }
        for (p in paths) {
            val label = p.exact?.label ?: ""
            val k = Regex("block (\\d+) ").find(label)?.groupValues?.get(1)?.toInt()?.minus(1) ?: 0
            g.color = when { label.endsWith("prime") -> Color(150, 150, 155); label.contains(" connector ") -> Color(90, 90, 95); label.contains(" frame ") -> Color(35, 35, 40); label.endsWith("mark") || label.contains(" speed ") || label.contains(" height ") -> blockColor(k).darker(); else -> blockColor(k) }
            g.stroke = BasicStroke(maxOf(2f, (p.widthUm * s).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val m = p.xy.size / 2
            for (i in 0 until if (p.closed) m else m - 1) { val j = (i + 1) % m; g.draw(Line2D.Double(px(p.xy[2 * i]), py(p.xy[2 * i + 1]), px(p.xy[2 * j]), py(p.xy[2 * j + 1]))) }
        }
        // each tile's start (its wall's first point: the middle of its back edge), a black dot
        g.color = Color.BLACK
        for (p in paths) if (p.closed && p.exact?.label?.contains(" tile ") == true) g.fill(java.awt.geom.Ellipse2D.Double(px(p.xy[0]) - 4, py(p.xy[1]) - 4, 8.0, 8.0))
        val t = spec.tiles
        // the tiles left out (over their block's melt limit): an empty dashed cell, labeled
        for (k in 0 until spec.blocks) for (r in t.speeds.indices) for (c in t.heightsUm.indices) if (!spec.printed(k, r, c)) {
            val tb = plate.tileBox(k, r, c)
            g.color = Color(170, 60, 60); g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
            g.draw(java.awt.geom.Rectangle2D.Double(px(ox + tb[0]), py(oy + tb[3]), (tb[2] - tb[0]) * s, (tb[3] - tb[1]) * s))
            g.font = Font("SansSerif", Font.PLAIN, 10)
            val cx = px(ox + (tb[0] + tb[2]) / 2); val cy = py(oy + (tb[1] + tb[3]) / 2)
            for ((i, txt) in listOf("over melt", "limit").withIndex()) g.drawString(txt, (cx - g.fontMetrics.stringWidth(txt) / 2).toInt(), (cy - 2 + i * 12).toInt())
        }
        for (k in 0 until spec.blocks) {
            val bx = plate.blockXY(k).first
            // the block's name above it, in the gap behind it
            g.color = blockColor(k).darker(); g.font = Font("SansSerif", Font.BOLD, 18)
            val top = plate.blockXY(k).second + plate.blockD
            // one line, in the gap behind the block (a row of blocks behind starts 6 mm further back)
            g.font = Font("SansSerif", Font.BOLD, 15)
            g.drawString("block ${k + 1}: ${spec.tempsC[k]} C, limit ${n(spec.capsMm3s[k], 1)} mm3/s", px(ox + bx).toInt(), (py(oy + top) - 8).toInt())
            if (k == 0) {
                val pp = plate.primePath(0)
                g.color = Color(110, 110, 115); g.font = Font("SansSerif", Font.ITALIC, 13)
                g.drawString("prime line (not read)", (px(ox + pp[0]) + 8).toInt(), (py(oy + pp[1]) + 5).toInt())
            }
        }
        g.color = Color(70, 70, 75); g.font = Font("SansSerif", Font.PLAIN, 14)
        g.drawString("The plate is ${n((b[2] - b[0]) / 1000.0, 0)} x ${n((b[3] - b[1]) / 1000.0, 0)} mm; each block's temperature, row speeds (mm/s), and column heights (mm) are printed on it, as drawn.", 40, H - 30)
        g.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
