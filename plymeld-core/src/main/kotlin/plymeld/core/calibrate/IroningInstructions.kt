package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each ironing plate ([IroningPlate]; the first-layer plate's is [FirstLayerInstructions]): the
 * question, what to do before printing, the conditions, a labeled picture (the zones, the ticks, the chamfer), the zone
 * plan with its mark legend and which zones read each value, how to grade (a readings sheet, a line per zone), what each
 * kind of result means (decided before printing), and how the readings are written back.
 */
object IroningInstructions {
    /** The grades a zone takes: good, marginal, bad (the score of each in IroningReadings). */
    val GRADES = listOf("good", "marginal", "bad")

    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: IroningSpec, plate: IroningPlate, info: PlateInstructions.Info) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, plate, info, png.fileName.toString()))
    }

    fun markdown(spec: IroningSpec, plate: IroningPlate, info: PlateInstructions.Info, pngName: String): String {
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        val g = IroningGeometry
        val ironed = spec.zones.count { !it.control }
        l("# Ironing plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating and any levelling come on top)" } ?: "") + ".")
        l("One piece, ${n(spec.pieceW / 1000.0, 0)} x ${n(spec.pieceD / 1000.0, 0)} mm (a ${n(g.brim / 1000.0, 0)} mm brim round it): **${spec.slabs} slabs**, ${g.rows} per ironing speed, each one row of zones ${n(spec.slabW / 1000.0, 0)} x ${n(spec.slabD / 1000.0, 0)} x ${n(spec.topZ / 1000.0, 2)} mm, on a base that joins them, and a legend. Their tops are divided into **${spec.zones.size} zones** of ${n(g.zone / 1000.0, 0)} mm: $ironed of them ironed, each with its own combination of the ironing values, and ${spec.speeds.size} left as laid (the controls). " +
            "**The question: which ironing flow, line spacing, speed, angle, and inset give this filament a smooth top with this tool**, and which of them matter.")
        l()
        if (info.warnings.isNotEmpty()) {
            l("**Warnings:**")
            l()
            for (wn in info.warnings) l("- $wn")
            l()
        }
        l("## Before you print")
        l()
        l("1. **Level the bed.** " + when {
            info.levels -> "This file runs the printer's bed levelling (hardware.machine.<key>.gcode.level) before the plate, once it has heated. If your printer also offers levelling when a print starts, you need not use both."
            info.levelSkipped -> "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling (mesh) yourself first."
            else -> "plymeld cannot level this printer from the g-code (no levelling command is known for it): turn on the printer's own levelling when you start the print (e.g. its \"level before print\" option), or run it from the printer's menu first."
        } + " A slab that is not flat irons unevenly: a thicker part gets pressed harder.")
        l("2. **Run any other calibration your printer offers before a print**: nozzle or tool offsets on a multi-tool machine, a z-offset, its own flow or pressure-advance calibration (note whether it ran: it changes the conditions below), a nozzle clean or wipe.")
        l("3. **Clean the bed**${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C and stays there.")
        l("4. **Load `${info.filament}` on tool ${info.tool + 1}.** Dry filament matters: moisture shows as a rough, bubbly top in every zone, ironed or not.")
        l("5. **During the print**: after the slabs' tops are laid, the nozzle goes back over them zone by zone, laying almost no plastic: that is the ironing, and it is expected. The controls are skipped. The whole plate prints at one temperature.")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l(if (spec.firstTempC != spec.tempC) "| nozzle temperature | the top and its ironing at ${spec.tempC} C (the filament's print temperature), waited for (the head parked in front of the plate) before the top; the first layer and the brim at ${spec.firstTempC} C, the filament's first-layer temperature |"
            else "| nozzle temperature | ${spec.tempC} C throughout (the filament's print temperature), waited for before the first line |")
        l("| layer height | ${n(spec.layerUm / 1000.0, 2)} mm; the first layer ${n(spec.firstLayerUm / 1000.0, 2)} mm; ${spec.layers} layers, the top at z ${n(spec.topZ / 1000.0, 2)} mm |")
        l("| line width | ${n(spec.widthUm / 1000.0, 2)} mm; the first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm (the first-layer line width); an ironing line declared ${n(spec.nozzleUm / 1000.0, 2)} mm (the bore) |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} (the ironing lines' plastic is scaled by it too, as in a print) |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off on the first layer, ${n(spec.fanPct, 0)} % from the second (the filament's own, cooling.fan), the ironing passes included |")
        l("| first layer | ${n(spec.adhesion.speed, 0)} mm/s, ${n(spec.adhesion.accel, 0)} mm/s2 (the configured first-layer speed, held to the filament's maximum flow); its lines at 45 degrees |")
        l("| the slab's middle layers (not read) | ${n(spec.frame.speed, 0)} mm/s, ${n(spec.frame.accel, 0)} mm/s2 (the machine's print speed and acceleration, held to the filament's maximum flow); lines at 45 and 135 degrees, alternating |")
        l("| the top fill | ${n(spec.top.speed, 0)} mm/s, ${n(spec.top.accel, 0)} mm/s2 (a visible surface's speed, held to the maximum flow, and acceleration); its lines **along the slab (0 degrees)**, one bead spacing apart, laid as one monotonic surface over each slab's zones and the frames between them, no wall between them |")
        l("| the ironing passes | each zone at its own speed, flow, spacing, angle, and inset (the table below); ${n(spec.ironAccel, 0)} mm/s2 (a visible line's acceleration); an ironing line's plastic is flow x spacing x the layer height (plymeld's ironing rule); the nozzle lifts ${n(spec.liftUm / 1000.0, 2)} mm off the top and off each zone before it travels on |")
        l("| retraction | ${n(spec.retractMm, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: the slabs, the zones, their labels, and the legend]($pngName)")
        l()
        l("- **The slabs.** ${g.rows} per ironing speed, front to back (${spec.speeds.joinToString(", ") { n(it, 1) }} mm/s; slabs 1-${g.rows} the first), each one row of zones, with a ${n(g.groove / 1000.0, 0)} mm groove between neighboring slabs down to the base; the legend behind the last. The piece's **front-left corner is chamfered**: the front slab's, where zone 1 is.")
        l("- **The zones** are ${n(g.zone / 1000.0, 0)} mm squares of the slabs' tops, ${spec.columns} to a slab. A speed's first zone is at the front-left of its front slab, the next behind it on its back slab, then the next column's front, and so on; the first speed's slabs hold zones 1-${spec.perBlock}, the next speed's the next ${spec.perBlock}. That is also the print order. Between neighboring zones of a slab a **${n(g.frame / 1000.0, 0)} mm strip of the top is left as laid**, so every zone is framed by the control's surface.")
        l("- **The labels** are notches cut into the slabs above the base, each starting ${n(g.labelFrom / 1000.0, 0)} mm from the zone's left end; a control has none. On **each zone's front edge**: **square notches for its flow, then V notches for its spacing**. On **its back edge**: **round-bottomed notches for its inset, then slanted notches for its angle** (none for 0 degrees). In **each slab's left end**, **bar notches for its speed**. The **legend** behind the slabs gives each symbol's value: `${spec.legendEntries.joinToString("`, `")}` (`|` a bar, `#` a square, `v` a V, `u` a round-bottomed notch, `\\` a slanted one). " +
            "A count of a symbol is that many times its value: three bars are three times the bar's speed. (Where a setting's values share no step small enough for a few notches each, the legend lists every count's value instead.)")
        l("- **Both the front and the back edge of every zone are on its slab's perimeter** (the end columns' outer ends too): that is where the zone's **inset** is read (away from its label's notches). On its other sides its ironing lines end at its cell's edge, leaving the frame as laid.")
        l("- **The plan.** A **block** of ${spec.perBlock} zones per ironing **speed** (${spec.speeds.joinToString(", ") { n(it, 1) }} mm/s, block 1 first). In each block the first zone is the **control**, not ironed (the top as laid), and the other ${spec.flows.size * spec.spacingsUm.size} are a grid of **flow** (${spec.flows.joinToString(", ") { n(it * 100, 1) }} %) by **spacing** (${spec.spacingsUm.joinToString(", ") { n(it / 1000.0, 3) }} mm); over that grid the **angle** (${spec.anglesDeg.joinToString(", ") { n(it, 1) }} degrees) and the **inset** (${spec.insetsUm.joinToString(", ") { n(it / 1000.0, 3) }} mm) are spread as two Latin squares, so each angle and each inset meets every flow and every spacing" +
            (if (spec.anglesDeg.size == spec.flows.size && spec.flows.size == spec.spacingsUm.size && spec.insetsUm.size == spec.flows.size) " exactly once per block" else "") + ". So **every value of every setting is read in several zones** (the table after the next), never only beside one value of another setting.")
        l()
        l("| zone | slab | column | speed (mm/s) | flow (%) | spacing (mm) | angle (deg) | inset (mm) |")
        l("|---|---|---|---|---|---|---|---|")
        for (z in spec.zones) {
            val k = z.index
            l("| ${k + 1} | ${spec.slab(k) + 1} | ${spec.col(k) + 1} | ${n(spec.speeds[z.block], 1)} | " +
                (if (z.control) "control: not ironed | | | |" else "${n(spec.flows[z.flowIdx] * 100, 1)} | ${n(spec.spacingsUm[z.spacingIdx] / 1000.0, 3)} | ${n(spec.anglesDeg[z.angleIdx], 1)} | ${n(spec.insetsUm[z.insetIdx] / 1000.0, 3)} |"))
        }
        l()
        l("Which zones read each value (a reading is attributed to a value by all of its zones together):")
        l()
        l("| setting | value | zones |")
        l("|---|---|---|")
        for ((name, byValue) in spec.coverage()) for ((v, zs) in byValue) l("| $name | $v | ${zs.joinToString(", ")} |")
        l()
        l("## How to grade the zones")
        l()
        l("Look at the top with a light low from one side, so every ridge and groove casts a shadow, then straight down against a window; run a fingernail across each zone and along it. Compare each zone with **its block's control** (the as-laid top). For **each zone** write one grade and what you saw:")
        l()
        l("1. **good**: smooth, even, and glossy or evenly matte over the whole zone; the top fill's lines no longer show; no ridges, no pits, nothing piled at its edge.")
        l("2. **marginal**: better than the control, but streaky, partly closed, or rough at one end or one edge.")
        l("3. **bad**: no better than the control, or worse: ridges or furrows along the ironing lines, pits, torn or crazed plastic, a lip pushed over the slab's edge, or the nozzle's path showing as a different texture.")
        l("4. For the **edge** of each zone on its slab's perimeter note whether an un-ironed rim is left (the inset too large: the rim is the as-laid top), or plastic has been pushed over the edge (too small).")
        l("5. Grade the controls too (a control that is already smooth means the flow ratio and the top fill are doing the work; one that is rough is what the zones are measured against).")
        l()
        l("| zone | values | grade (good / marginal / bad) | what you saw |")
        l("|---|---|---|---|")
        for (z in spec.zones) l("| ${z.index + 1} | ${spec.describe(z.index)} |  |  |")
        l()
        l("### What the results mean (decided before printing)")
        l()
        l("Read each setting off **all the zones that carry each of its values** (the table above): the share of good zones at that value, against the share at its other values. A setting whose values differ clearly in their share of good zones matters; one whose values read alike does not, in this range.")
        l()
        l("- **Flow: the zones at the low flow show grooves or the fill's lines through, the high flow ridges or a lip at the edge, the middle smooth** (the expected result): the flow in between is this filament's ironing flow.")
        l("- **Every flow reads alike**: the flow is not what limits ironing here; look at the spacing and the speed.")
        l("- **Spacing: the wide spacing leaves parallel streaks, the fine one closes the top**: the finest spacing that is clean at the speed you can afford. **The fine spacing is worse** (over-worked, pitted): the nozzle passes too often; the middle spacing.")
        l("- **Speed: the fast zones are streaky or torn and the slow ones smooth**: the plastic needs the time; the fastest speed whose zones are good. **Every speed reads alike**: take the fastest tested, and the next plate can go faster (`--ironing-speeds`).")
        l("- **Angle: the zones ironed along the top's lines (0 degrees) read differently from those across (90)**: the direction matters; take the angle whose zones read best. **Alike**: it does not matter in this range.")
        l("- **Inset: an un-ironed rim shows at the zone's edge**: the inset is too large (the rim is as wide as the inset is too long); **plastic pushed over the edge**: too small. The inset whose edges are clean and complete.")
        l("- **A whole block is bad, whatever the flow and spacing**: that speed is out of range.")
        l("- **The controls are as good as the best zones**: this top is smooth as laid; ironing adds little at this layer height and flow ratio.")
        l("- **Every zone is bad, the controls too**: not the ironing: look at the flow ratio, the first layer (the slab may not be flat), wet filament, or the nozzle (a worn or dirty tip drags).")
        l("- **The best single zone's values do not agree with the per-setting winners**: the settings interact (a fine spacing may want less flow); report both, and prefer the zone you can see.")
        l()
        l("### What to enter")
        l()
        l("`plymeld calibrate ironing-readings ${info.gcodeName} -o readings.toml` writes the sheet to grade (one entry per zone, its values filled in, its grade `?`). Replace every `?` with good, marginal, or bad, then " +
            "`plymeld calibrate ironing-values readings.toml -o ironing.toml` turns the sheet into the ironing values (`plan.technique.ironing.flow`, `.spacing`, `.speed`, `.angle`, `.inset`): per setting the value with the best share of good zones, with where it came from (this plate, when it printed, when it was graded), and the best single zone beside them. Add the file with `-cf`.")
        return sb.toString()
    }

    /**
     * The picture: the piece from above as the program prints it: the brim, the base, each slab (its notches: the zones'
     * labels and the speed bars) and the legend from the plate's own outlines, the top fill gray, each zone's ironing lines in
     * its slab's color, each zone's cell labeled with its number and values.
     */
    fun picture(file: java.nio.file.Path, spec: IroningSpec, plate: IroningPlate) {
        System.setProperty("java.awt.headless", "true")
        val paths = plate.program().steps.filterIsInstance<plymeld.program.Extrude>()
        val b = plate.bounds()
        // room on the right for the slabs' captions
        val head = 120; val foot = 50; val side = 60; val right = 200
        val W = 1700
        val s = (W - side - right).toDouble() / (b[2] - b[0] + 4_000)
        val H = head + foot + ((b[3] - b[1] + 4_000) * s).toInt()
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val ox = spec.originXUm; val oy = spec.originYUm
        fun px(x: Long) = side + (x - ox - b[0] + 2_000) * s
        fun py(y: Long) = H - foot - (y - oy - b[1] + 2_000) * s
        fun pxl(x: Long) = side + (x - b[0] + 2_000) * s
        fun pyl(y: Long) = H - foot - (y - b[1] + 2_000) * s
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 24)
        gr.drawString("Ironing plate from above: ${spec.slabs} slabs (${IroningGeometry.rows} per speed) on one base, ${spec.zones.size} zones; the front at the bottom", 40, 42)
        gr.font = Font("SansSerif", Font.PLAIN, 16)
        gr.drawString("Each zone: its number, then flow / spacing, angle / inset. Notches: front edge squares = the flow, Vs = the spacing; back edge round = the inset, slanted = the angle;", 40, 72)
        gr.drawString("bars = the slab's speed (left end). Gray: the top as laid (the frames between zones and the controls). Dashed: the base; dotted: the brim. A color per speed.", 40, 96)
        fun slabColor(k: Int): Color { val f = if (spec.speeds.size == 1) 0.5 else k.toDouble() / (spec.speeds.size - 1)
            return Color((60 + 170 * f).toInt(), (110 + 30 * (1 - Math.abs(2 * f - 1))).toInt(), (200 - 160 * f).toInt()) }
        fun drawPoly(p: plymeld.geom.Polygon) { for (i in 0 until p.size) { val j = (i + 1) % p.size; gr.draw(Line2D.Double(pxl(p.x(i)), pyl(p.y(i)), pxl(p.x(j)), pyl(p.y(j)))) } }
        // the brim's outer edge and the base
        gr.color = Color(150, 150, 155)
        gr.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(2f, 4f), 0f)
        val br = IroningGeometry.brim
        drawPoly(plymeld.geom.Polygon(longArrayOf(-br, -br, spec.pieceW + br, -br, spec.pieceW + br, spec.pieceD + br, -br, spec.pieceD + br)))
        gr.color = Color(90, 90, 95)
        gr.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(8f, 5f), 0f)
        drawPoly(plate.baseOutline())
        // the top fill, then the ironing lines
        for (p in paths) {
            if ((p.exact?.label ?: "") != "top") continue
            gr.color = Color(208, 208, 213); gr.stroke = BasicStroke(maxOf(1f, (p.widthUm * s).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val m = p.xy.size / 2
            for (i in 0 until if (p.closed) m else m - 1) { val j = (i + 1) % m; gr.draw(Line2D.Double(px(p.xy[2 * i]), py(p.xy[2 * i + 1]), px(p.xy[2 * j]), py(p.xy[2 * j + 1]))) }
        }
        for (p in paths) {
            val k = Regex("zone (\\d+)").matchEntire(p.exact?.label ?: "")?.groupValues?.get(1)?.toInt()?.minus(1) ?: continue
            gr.color = slabColor(spec.zones[k].block); gr.stroke = BasicStroke(1f)
            val m = p.xy.size / 2
            for (i in 0 until m - 1) gr.draw(Line2D.Double(px(p.xy[2 * i]), py(p.xy[2 * i + 1]), px(p.xy[2 * i + 2]), py(p.xy[2 * i + 3])))
        }
        // the slabs' and the legend's outlines above the base (the notches and the glyphs as printed)
        gr.color = Color(30, 30, 35); gr.stroke = BasicStroke(2f)
        for (o in plate.outlines(spec.layers - 1)) drawPoly(o)
        // each zone's number and values; each slab's speed beside it
        for (z in spec.zones) {
            val zb = spec.zoneBox(z.index)
            val cx = pxl((zb[0] + zb[2]) / 2); val cy = pyl((zb[1] + zb[3]) / 2)
            gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 22)
            val num = "${z.index + 1}"
            gr.drawString(num, (cx - gr.fontMetrics.stringWidth(num) / 2).toInt(), (cy - 14).toInt())
            gr.font = Font("SansSerif", Font.PLAIN, 14)
            val lines = if (z.control) listOf("control", "as laid") else listOf("${n(spec.flows[z.flowIdx] * 100, 0)} % / ${n(spec.spacingsUm[z.spacingIdx] / 1000.0, 2)} mm", "${n(spec.anglesDeg[z.angleIdx], 0)} deg / ${n(spec.insetsUm[z.insetIdx] / 1000.0, 2)} mm")
            for ((i, t) in lines.withIndex()) gr.drawString(t, (cx - gr.fontMetrics.stringWidth(t) / 2).toInt(), (cy + 6 + i * 17).toInt())
        }
        gr.font = Font("SansSerif", Font.BOLD, 16)
        for (k in 0 until spec.slabs) {
            val sb = spec.slabBox(k)
            gr.color = slabColor(spec.blockOf(k))
            gr.drawString("slab ${k + 1}: ${n(spec.speeds[spec.blockOf(k)], 1)} mm/s", (pxl(sb[2]) + 10).toInt(), pyl((sb[1] + sb[3]) / 2).toInt())
        }
        gr.color = Color(70, 70, 75); gr.font = Font("SansSerif", Font.PLAIN, 14)
        gr.drawString("The piece is ${n(spec.pieceW / 1000.0, 1)} x ${n(spec.pieceD / 1000.0, 1)} mm (${n((b[2] - b[0]) / 1000.0, 1)} x ${n((b[3] - b[1]) / 1000.0, 1)} with the brim); the chamfer at the front-left corner marks the origin; the front is towards you.", 40, H - 18)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
