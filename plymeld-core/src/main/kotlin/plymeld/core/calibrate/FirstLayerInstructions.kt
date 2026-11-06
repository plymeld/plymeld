package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each first-layer plate ([FirstLayerPlate]; the filament plate's is [PlateInstructions]): what to
 * do before printing, the conditions, a picture of the plate as printed (its labels among its tiles), how to read it
 * column by column and row by row, a table to fill in, and what each kind of result means, written before the print.
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
        val g = FirstLayerGeometry
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        l("# First-layer plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating, the wait for each column's temperature, and any levelling come on top)" } ?: "") + ".")
        l("One layer of first-layer tiles in one piece (and a frame over their walls, not read): a column for each nozzle temperature, ${spec.tempsC.joinToString(", ")} C, printed coolest first, and in each column a row for every speed and nozzle height. " +
            "This plate measures **the nozzle height, speed, and first-layer temperature a first layer goes down well at**.")
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
        l("5. **During the print**: some tiles are meant to come out badly (too low or too high): do not stop for them. Between columns the head moves off to the front of the plate and waits while the nozzle heats to the next column's temperature; that is expected. " +
            "Each column starts with its prime line; after the last column come the base's upper layers, the raised labels, and the frame over every tile's wall.")
        l("6. **After the print**, let the bed cool, then peel the plate as one piece by the frame's raised edge.")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| nozzle temperature | by column, below: ${spec.tempsC.first()} to ${spec.tempsC.last()} C, coolest first (the left), a wait (M109) for each before it prints |")
        l("| tile line width | ${n(ti.widthUm / 1000.0, 2)} mm (the first-layer line width) |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off |")
        l("| first-layer tiles | every tile ${n(g.tileLength / 1000.0, 0)} mm long, its lines along it, exactly at its row's speed and height; ${n(ti.accel, 0)} mm/s2; the nozzle lifts ${n(ti.liftUm / 1000.0, 2)} mm off each tile and frame ring before moving on; each tile's lines reach ${n(ti.wallOverlap * 100, 0)} % of a line into its wall at their ends (the line-end overlap) |")
        l("| frame (not read) | after all the tiles, ${g.frameLayers} layers of rings over every tile's wall, from a layer above its tile, at the last column's temperature: ${n(spec.frameWidthUm / 1000.0, 2)} mm lines (the plate's ordinary line width), " +
            "${n(spec.frameLayerUm / 1000.0, 2)} mm high (the layer height), ${n(spec.frameSpeed, 0)} mm/s (the machine's print speed, ${n(spec.frame.speed, 0)} mm/s${if (spec.frameSpeed < spec.frame.speed - 1e-9) ", held to the melt limit" else ""}), ${n(spec.frame.accel, 0)} mm/s2; part fan off |")
        l("| melt limit | by column, below: the filament's maximum flow at the column's temperature; a tile that would need more is left out, never slowed |")
        l("| prime lines, base, labels | the prime lines and the base's first layer ${n(spec.primeUm / 1000.0, 2)} mm high (the first layer's, or the lowest tile's if lower) in ${n(spec.firstWidthUm / 1000.0, 2)} mm lines; the base's upper layers and the labels ${n(spec.frameLayerUm / 1000.0, 2)} mm each, ${n(spec.frameWidthUm / 1000.0, 2)} mm lines; ${n(spec.prime.speed, 0)} mm/s; not read |")
        l("| retraction | ${n(info.retractMm, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: a column per temperature, a row per speed and height, the labels as printed]($pngName)")
        l()
        l("- **The columns** are the nozzle temperatures, **left to right in the order they print: the coolest first**. **Each column's temperature is printed in front of it**, raised on the base.")
        l("- **The rows** are the tests, the same in every column: **a group of rows for each speed, the slowest at the front, and in each group a row for each nozzle height, the lowest at the front**. " +
            "**Each row's height** (mm) is printed at the left of the plate, and **each group's speed** (mm/s) left of its rows. So a row read across the plate is one speed and height at every temperature.")
        l("- **Every tile** is a strip ${n(g.tileLength / 1000.0, 0)} mm long, one layer, at its own height and speed: a wall round lines laid side by side along it at the spacing a line of its height should fill exactly, ${n(ti.widthUm / 1000.0, 2)} mm wide, so how its top closes up tells whether that height was right. " +
            "Each tile starts, and its wall closes, at the **middle of its back edge** (any start or stop blob is there); its lines follow from the back.")
        l("- **The base** runs along the plate's front and left, ${g.baseLayers} layers, its front-left corner cut off: the corner every plate is read from. **The labels** stand ${g.labelLayers} layers on it.")
        l("- **The plate is one piece**: every tile touches its neighbors and the base. **The frame**, ${g.frameLayers} layers over every tile's wall only, makes it stiff and gives it a raised edge to peel by, with the base. **Each tile's inside, the part you read, is left uncovered**: read the tiles inside the frame; the frame is not read.")
        l("- **The prime line** runs along the base's front under each column: laid after the wait for that column's temperature, so the column's first tile does not start with what oozed during the wait. It is not read.")
        l("- **Tiles over the melt limit are left out**: a tile whose flow (its speed x its line's cross-section x the flow ratio) would pass the filament's maximum flow at its column's temperature is not printed; its place stays empty, so nothing on the plate claims to test what it cannot.")
        l()
        l("| column (from the left) | nozzle | melt limit | tiles left out |")
        l("|---|---|---|---|")
        for (k in 0 until spec.blocks) {
            val left = spec.rows.filter { (r, c) -> !spec.printed(k, r, c) }.map { (r, c) -> "${n(ti.speeds[r], 0)} mm/s at ${n(ti.heightsUm[c] / 1000.0, 2)} mm (${n(spec.tileFlow(r, c), 1)} mm3/s)" }
            l("| ${k + 1} | ${spec.tempsC[k]} C | ${n(spec.capsMm3s[k], 1)} mm3/s${spec.capFrom.getOrNull(k)?.let { " ($it)" } ?: ""} | ${if (left.isEmpty()) "none" else left.joinToString(", ")} |")
        }
        l()
        l("| row (from the front) | speed (mm/s) | nozzle height (mm) |")
        l("|---|---|---|")
        for ((j, rc) in spec.rows.withIndex()) l("| ${j + 1} | ${n(ti.speeds[rc.first], 0)} | ${n(ti.heightsUm[rc.second] / 1000.0, 2)}${if (ti.heightsUm[rc.second] == spec.layerUm) " (the configured first layer's)" else ""} |")
        l()
        if ((0 until spec.blocks).all { k -> spec.rows.all { (r, c) -> spec.printed(k, r, c) } })
            l("No tile is left out: every one stays within its column's melt limit.")
        else l("Every tile prints at its row's speed; the ones that would pass their column's melt limit are left out (the table above, and the empty cells in the tables below).")
        l()
        val mid = n(spec.layerUm / 1000.0, 2)
        l("## How to read the first-layer tiles")
        l()
        l("Look at the tiles with a low light from the side, so every ridge and gap casts a shadow, and run a fingernail across them. Find each column by its printed temperature and each row by its printed height and speed. For each tile:")
        l()
        l("1. A **good** tile is closed, flat, and square-cornered, its lines joined into one smooth sheet, nothing lifted at its edges or corners.")
        l("2. **Too low**: ridges between the lines, plastic ploughed up or pushed to the edges, or thin, see-through spots where the nozzle scraped the bed.")
        l("3. **Too high**: gaps between the lines, lines that stay round beads rather than flattening, or a tile that lifts at its edges or comes away from its neighbors.")
        l("4. **For each column (temperature) and each speed, tick the heights whose tiles are good.** The good heights at a speed are that speed's window at that temperature.")
        l()
        for (k in 0 until spec.blocks) {
            l("Column ${k + 1}: ${spec.tempsC[k]} C")
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
        l("- **The first tiles after a temperature change show blobs or strings at their start**: the wait and prime, not the first layer; read those tiles' other edges.")
        l()
        l("To record the results, `plymeld calibrate first-layer window ${info.gcodeName} --bore <mm>` writes the window to grade, a table per temperature like the ones above; grade each tile there and add it to your config.")
        return sb.toString()
    }

    /** The picture: the plate from above as the program prints it, its labels and frame among its tiles, each column named with its temperature and melt limit. */
    fun picture(file: java.nio.file.Path, spec: FirstLayerSpec, plate: FirstLayerPlate) {
        System.setProperty("java.awt.headless", "true")
        val paths = plate.program().steps.filterIsInstance<plymeld.program.Extrude>()
        val W = 1800; val H = 1300
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val b = plate.bounds()
        val ox = spec.originXUm; val oy = spec.originYUm
        val s = minOf((W - 160).toDouble() / (b[2] - b[0] + 24_000), (H - 260).toDouble() / (b[3] - b[1] + 24_000))
        fun px(x: Long) = 80 + (x - ox - b[0] + 12_000) * s
        fun py(y: Long) = 120 + (H - 260) - (y - oy - b[1] + 12_000) * s
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 22)
        gr.drawString("First-layer plate from above: a column per nozzle temperature, coolest first; a row per speed and height", 40, 40)
        gr.font = Font("SansSerif", Font.PLAIN, 16)
        gr.drawString("Rows: a group per speed (the slowest at the front), a row per nozzle height in each (the lowest at the front); black dot = where a tile starts; dashed: left out, over the melt limit.", 40, 68)
        gr.drawString("Gray: prime lines and the base; dark: the frame over the tiles' walls (none of these is read: read each tile inside its frame). The labels are raised on the base as drawn.", 40, 90)
        // a color per column, cool to warm
        fun colColor(k: Int): Color { val f = if (spec.blocks == 1) 0.5 else k.toDouble() / (spec.blocks - 1)
            return Color((60 + 170 * f).toInt(), (110 + 30 * (1 - Math.abs(2 * f - 1))).toInt(), (200 - 160 * f).toInt()) }
        // the first layer, then the frame over it; the labels' glyphs as filled shapes (even-odd, so their holes stay open)
        for (p in paths.sortedBy { p -> p.exact?.label.orEmpty().let { if (it.contains(" frame ")) 2 else if (it.contains(" mark ") || it.contains(" speed ") || it.contains(" height ")) 1 else 0 } }) {
            val label = p.exact?.label ?: ""
            val k = Regex("block (\\d+) ").find(label)?.groupValues?.get(1)?.toInt()?.minus(1) ?: 0
            val isLabel = label.contains(" mark ") || label.contains(" speed ") || label.contains(" height ")
            gr.color = when { label.endsWith("prime") -> Color(150, 150, 155); label.contains(" base ") -> Color(200, 200, 205); label.contains(" frame ") -> Color(35, 35, 40); isLabel -> Color(60, 60, 70); else -> colColor(k) }
            gr.stroke = BasicStroke(maxOf(1.5f, (p.widthUm * s).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val m = p.xy.size / 2
            for (i in 0 until if (p.closed) m else m - 1) { val j = (i + 1) % m; gr.draw(Line2D.Double(px(p.xy[2 * i]), py(p.xy[2 * i + 1]), px(p.xy[2 * j]), py(p.xy[2 * j + 1]))) }
        }
        // each tile's start (its wall's first point: the middle of its back edge), a black dot
        gr.color = Color.BLACK
        for (p in paths) if (p.closed && p.exact?.label?.contains(" tile ") == true) gr.fill(java.awt.geom.Ellipse2D.Double(px(p.xy[0]) - 3, py(p.xy[1]) - 3, 6.0, 6.0))
        // the tiles left out (over their column's melt limit): an empty dashed cell, labeled
        for (k in 0 until spec.blocks) for ((r, c) in spec.rows) if (!spec.printed(k, r, c)) {
            val tb = plate.tileBox(k, r, c)
            gr.color = Color(170, 60, 60); gr.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
            val rect = Path2D.Double().also { q -> q.moveTo(px(ox + tb[0]), py(oy + tb[1])); q.lineTo(px(ox + tb[2]), py(oy + tb[1])); q.lineTo(px(ox + tb[2]), py(oy + tb[3])); q.lineTo(px(ox + tb[0]), py(oy + tb[3])); q.closePath() }
            gr.draw(rect)
            gr.font = Font("SansSerif", Font.PLAIN, 11)
            val txt = "over the melt limit"
            gr.drawString(txt, (px(ox + (tb[0] + tb[2]) / 2) - gr.fontMetrics.stringWidth(txt) / 2).toInt(), (py(oy + (tb[1] + tb[3]) / 2) + 4).toInt())
        }
        // each column's name above it
        val top = b[3]
        for (k in 0 until spec.blocks) {
            gr.color = colColor(k).darker(); gr.font = Font("SansSerif", Font.BOLD, 15)
            gr.drawString("column ${k + 1}: ${spec.tempsC[k]} C, limit ${n(spec.capsMm3s[k], 1)} mm3/s", px(ox + plate.colX(k)).toInt(), (py(oy + top) - 10).toInt())
        }
        gr.color = Color(70, 70, 75); gr.font = Font("SansSerif", Font.PLAIN, 14)
        gr.drawString("The plate is ${n((b[2] - b[0]) / 1000.0, 0)} x ${n((b[3] - b[1]) / 1000.0, 0)} mm, one piece; each column's temperature, each row's height (mm), and each group's speed (mm/s) are printed on it.", 40, H - 30)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
