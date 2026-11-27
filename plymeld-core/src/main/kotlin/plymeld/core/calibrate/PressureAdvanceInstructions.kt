package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each pressure-advance plate ([PressureAdvancePlate]): the question, what to do before printing,
 * the conditions and the plate's limits, a labeled picture (the walls on their strips from above, their marks, the corners,
 * the chamfer) with a legend drawing the three readings, how to read each wall (snapped out on its strip, held against a
 * light and in a raking light, each drawn; a fingernail as a cross-check), the corners as a reading of their own, what each
 * kind of result means (decided before printing), and how the readings become a value.
 */
object PressureAdvanceInstructions {
    /** A wall's reading at a mark: thin just after it, as the rest of the line, or fat. */
    val MARKS = listOf("groove", "flat", "ridge")
    val MARK_MEANING = mapOf(
        "groove" to "the wall is thinner just after the mark than a few millimeters on: held against a light it shows a brighter band there; in a raking light from the left a dark edge, then a bright one, runs up the face (a shallow channel, the fingernail dips into it)",
        "flat" to "nothing to see: against a light and in a raking light the face runs on past the mark as it was",
        "ridge" to "the wall is fatter just after the mark: held against a light it shows a darker band there; in a raking light from the left a bright edge, then a dark one, runs up the face (a bead, the fingernail catches on it)")
    /** A corner's reading, its own beside the marks: bulging (too low), sharp, or rounded or gapped (too high). */
    val CORNERS = listOf("bulge", "sharp", "round")
    val CORNER_MEANING = mapOf(
        "bulge" to "the corner's outside swells: a blob or a lip where the two lines meet",
        "sharp" to "a clean square corner",
        "round" to "the corner's outside is rounded off, thin, or has a gap where the lines meet")

    class Info(
        val filament: String, val machine: String, val tool: Int, val version: String, val gcodeName: String, val predictedMin: Double?, val bedTempC: Int,
        val plate: String, val levels: Boolean, val levelSkipped: Boolean, val warnings: List<String>,
        /** The largest true value the slow-down mark reads opposite to the speed-up mark for, by the pressure model (s). */
        val settlesUpToS: Double,
        /** The config's pressure advance for the tool (s), or null; whether the machine sets its own. */
        val configuredPa: Double?, val machineOwn: Boolean,
        val retractMm: Double, val date: java.time.LocalDate = java.time.LocalDate.now(),
        /** A first plate's top and why it is there (the tool's ceiling, the highest value the extruder's push allows, or the highest value whose walls fit the bed); null = none of plymeld's choosing. */
        val top: String? = null,
        /** The plate's limits as it states them: its share of the filament's maximum flow, and its push limit or that none is known. */
        val limits: String = "",
        /** Whether the printer can level the bed as a print starts when asked (`plymeld printer send --print --level`): mentioned as an option, never as a step. */
        val levelsOnStart: Boolean = false,
    ) {
        /** What the page says of levelling ([Levelling]). */
        val levelling: Levelling.State get() = Levelling.State(levels, levelSkipped, levelsOnStart)
    }

    /** Writes [mdFile], its picture of the plate beside it (same name, .png), and its picture of the two ways to read a wall (-reading.png). */
    fun write(mdFile: java.nio.file.Path, spec: PressureAdvanceSpec, plate: PressureAdvancePlate, info: Info) {
        val stem = mdFile.fileName.toString().removeSuffix(".md")
        val png = mdFile.resolveSibling("$stem.png"); val reading = mdFile.resolveSibling("$stem-reading.png")
        picture(png, spec, plate)
        readingPicture(reading)
        java.nio.file.Files.writeString(mdFile, markdown(spec, info, png.fileName.toString(), reading.fileName.toString()))
    }

    fun markdown(spec: PressureAdvanceSpec, info: Info, pngName: String, readingPng: String = "reading.png"): String {
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        val g = PressureAdvanceGeometry
        val find = spec.kind == PressureAdvanceSpec.Kind.FIND
        val values = spec.walls.mapNotNull { it.value }.distinct().sorted()
        fun v(x: Double) = "${spec.valueText(x)} s"
        l("# Pressure-advance plate (${if (find) "find the value" else "confirm it holds"}): ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating and any levelling come on top)" } ?: "") + ".")
        l("One base, ${n(spec.baseW / 1000.0, 0)} x ${n(spec.baseD / 1000.0, 0)} mm, with **${spec.walls.size} step walls**: single lines ${n(spec.wallLength / 1000.0, 0)} mm long and ${n(spec.wallLayers * spec.layerUm / 1000.0, 1)} mm tall, each printed slow, then fast, then slow again, at its own pressure advance. " +
            when {
                find -> "**The question: which pressure advance makes the line come out even where its speed changes.** Too little leaves the line thin just after it speeds up (a groove) and fat just after it slows down (a ridge); too much the other way round. The answer is where the grooves turn into ridges, bracketed from both sides; every value is printed twice, the back row in the opposite order, so a trend across the bed cannot pass as the answer."
                info.machineOwn -> "**The question: does the machine's own pressure advance hold** at three flow steps. plymeld writes no pressure advance on this plate: the machine sets its own."
                else -> "**The question: does the value hold at other flow steps.** The value ${v(values[1])} and its neighbors (${v(values[0])}, ${v(values[2])}), each at three flow steps: a value that holds reads flat at all three, its low neighbor grooves and its high neighbor ridges."
            })
        l()
        if (info.warnings.isNotEmpty()) { l("**Warnings:**"); l(); for (wn in info.warnings) l("- $wn"); l() }
        l("## Before you print")
        l()
        val steps = listOf(
            "**Measure the melt rate and the flow ratio first** (`plymeld calibrate melt`, `calibrate flow`): every line is held to a share of the filament's maximum flow, and the readings assume the line is laid at its flow ratio.",
            "**Set the printer's speed and flow to 100 %** (its screen's speed and flow overrides): the walls' speeds and their plastic are the readings' conditions.",
            "**Turn off any pressure-advance calibration the printer runs by itself before a print**" + (if (info.machineOwn) ": not here, where the machine's own value is what the plate checks" else ": the plate sets its own value before each wall, and a value the printer sets afterwards would replace them") + ".",
            "**Clean the bed**${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C.",
            "**Load `${info.filament}` on tool ${info.tool + 1}**, dry.")
        for ((k, st) in steps.withIndex()) l("${k + 1}. $st")
        Levelling.page(info.levelling)?.let { l(); l(it) }
        l()
        l("## What the plate prints with")
        l()
        l("Every reading holds under these conditions only; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l(if (spec.firstTempC != spec.tempC) "| nozzle temperature | the walls at ${spec.tempC} C (the filament's print temperature), waited for with the head parked in front of the plate; the base's first layer at ${spec.firstTempC} C |"
            else "| nozzle temperature | ${spec.tempC} C throughout (the filament's print temperature) |")
        l("| layer height, line width | ${n(spec.layerUm / 1000.0, 2)} mm, ${n(spec.widthUm / 1000.0, 2)} mm; the walls ${spec.wallLayers} layers above a ${g.baseLayers}-layer base |")
        l("| the walls' speeds | a ${n(spec.leadUm / 1000.0, 0)} mm lead-in at ${n(spec.slowSpeed, 0)} mm/s, a ${n(spec.fastUm / 1000.0, 0)} mm middle at ${spec.walls.map { it.fastSpeed }.distinct().joinToString(", ") { n(it, 0) }} mm/s, a ${n(g.tail / 1000.0, 0)} mm tail at ${n(spec.slowSpeed, 0)} mm/s (a visible wall's speed and the machine's print speed, each held to the plate's share of the filament's maximum flow) |")
        l("| the base and the labels | the first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm lines (the first-layer line width) at ${n(spec.adhesion.speed, 0)} mm/s; above it ${n(spec.frame.speed, 0)} mm/s; the labels and arrows at ${n(spec.labels.speed, 0)} mm/s (a visible wall's speed) |")
        if (info.limits.isNotEmpty()) l("| the plate's limits | ${info.limits} |")
        l("| flow step | ${spec.walls.map { spec.flowStep(it) }.distinct().joinToString(", ") { n(it, 1) }} mm3/s (the defect grows with it) |")
        l("| acceleration | ${n(spec.accel, 0)} mm/s2 (the machine's print acceleration) |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | " + when {
            info.machineOwn -> "the machine's own: plymeld writes none |"
            find -> "each wall its own: ${values.joinToString(", ") { spec.valueText(it) }} s; the base and the labels at ${spec.framePa?.let { v(it) + if (info.configuredPa != null) " (the config's)" else " (the plate's lowest)" } ?: "none"} |"
            else -> "each wall its own: ${values.joinToString(", ") { spec.valueText(it) }} s; the base and the labels at ${spec.framePa?.let { v(it) } ?: "none"} |"
        })
        l("| the firmware's pressure-advance smoothing | its own (plymeld sets none); ${n(spec.smoothS, 3)} s read for the extruder's push |")
        l("| the values' resolution | ${n(PressureAdvanceSpec.LABEL_STEP_S * 1000, 1)} ms: every value printed to a tenth of a millisecond, as its label shows it |")
        info.top?.let { l("| the plate's top | $it |") }
        l("| the walls' stretches | a ${n(spec.leadUm / 1000.0, 0)} mm lead-in and a ${n(spec.fastUm / 1000.0, 0)} mm middle: long enough, at these speeds and this acceleration, for the pressure to settle within them up to ${spec.valueText(info.settlesUpToS)} s (plymeld's pressure model); a higher value needs longer walls |")
        l("| bed, part fan, retraction | ${info.bedTempC} C; off on the first layer, ${n(spec.fanPct, 0)} % above; ${n(info.retractMm, 2)} mm, between walls only |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: the walls on their strips, their marks and labels, the corners, and the chamfer; below, the three readings]($pngName)")
        l()
        l("- **The origin**: the base's front-left corner is cut off. The front faces you.")
        l("- **A strip per wall**: the base is cut into a strip under each wall, a gap between neighbors bridged by small break-off tabs, so the plate lifts off the bed in one piece and each wall then snaps out on its own strip, both faces free to look through and along." +
            (if (spec.rows > 1) " Each strip carries its row's number, upright beside the wall's lead-in on its right (1 the front row, 2 the back row): the same value is printed in both rows, and `values` compares the rows, so keep each strip's row as you read it." else ""))
        l("- **Every wall prints from the front to the back** on every layer, the way its **arrow** points; in front of the arrow, its **value in milliseconds**, written along the wall (`40` is 0.04 s).")
        l("- **The marks**: on each wall's left, two small holes in the base: the front one where the head **starts to speed up**, the back one where it **starts to slow down**. The defect is just behind each hole, in the first few millimeters of the wall after it.")
        if (find) {
            l("- **Two rows**: the front row's values rise from the left, the back row's fall from the left: each value is printed twice. Each wall's value is printed on its own strip; count the strips from the chamfered side for a wall's place.")
            l("- **A corner beside each wall** (two short lines meeting square, on the wall's right, at its value and its fast speed): a reading of its own, beside the wall's marks.")
        } else l("- **The flow steps**: under each wall, in its strip's front edge, one notch for the small step, two for the medium, three for the large.")
        l()
        l("## How to read the walls")
        l()
        l("Let the plate cool, lift it off, and snap each wall out on its strip (bend along the gap; the tabs break). Compare the stretch of wall just behind each hole with the same wall a few millimeters further back, two ways:")
        l()
        l("- **Against a light**: hold the strip up with a light behind the wall (a window, a lamp, a phone's torch): a thinner stretch lets more light through and shows **brighter**, a thicker one **darker**. This needs a filament that lets light through a single ${n(spec.widthUm / 1000.0, 2)} mm wall; with a dark or opaque one, read in a raking light.")
        l("- **In a raking light**: in a dim room, hold a light low, almost along the wall's face, from its left: each slope facing the light shows bright and each facing away dark, so a **groove** shows a **dark edge, then a bright one**, and a **ridge** a **bright edge, then a dark one** (from the left; from the right the order turns round).")
        l()
        l("![Reading a wall: held against a light (left) and in a raking light (right), a groove, a flat stretch, and a ridge just after the mark]($readingPng)")
        l()
        l("A fingernail run up the face just behind each hole is a cross-check: it dips into a groove and catches on a ridge. Write one word per mark:")
        l()
        for (m in MARKS) l("- **$m**: ${MARK_MEANING.getValue(m)}.")
        l()
        l("The **speed-up mark** (the front hole) is the reading. The **slow-down mark** (the back hole) must read the opposite (a groove there with a ridge at the speed-up mark, or both flat); where both read the same (two grooves or two ridges), the wall shows something other than pressure advance, and the readings are refused.")
        if (find) {
            l()
            l("Then each wall's **corner**, its outside edge where the two lines meet, a reading of its own (too little pressure advance bulges it, too much rounds it in):")
            l()
            for (c in CORNERS) l("- **$c**: ${CORNER_MEANING.getValue(c)}.")
            l()
            l("`values` reports where the corners flip from bulge to round beside the walls' bracket and whether the two agree; the value is the walls' (their marks are read along a straight line, a corner at one turn the printer takes at its own corner speed), and a disagreement is reported, not averaged in.")
        }
        l()
        l("The lead-in and the middle are ${n(spec.leadUm / 1000.0, 0)} and ${n(spec.fastUm / 1000.0, 0)} mm long so that the pressure settles within them, at these speeds, up to a true pressure advance of ${v(info.settlesUpToS)} (plymeld's pressure model), at least the plate's highest value: up to there the slow-down mark reads opposite to the speed-up mark. Should the true value lie above it, both marks may read alike and the readings are refused; the speed-up mark is the one that flips at the true value.")
        l()
        l("### What the results mean (decided before printing)")
        l()
        if (find) {
            l("| if you see | it means |")
            l("|---|---|")
            l("| grooves at the low values and ridges at the high ones, one clean change between, both rows agreeing | the value is between the highest groove and the lowest ridge (flat walls count inside); `values` gives the middle, the bracket's width its uncertainty |")
            l("| the change spans several flat walls | the flow step is too small for the spacing: the value is honest but wide; a fine plate (`--bracket`) narrows it |")
            l("| both marks the same on a wall | something other than pressure dominates there (the firmware's acceleration limits, a slipping extruder at high values, or a pressure that does not settle within the middle); refused |")
            l("| no defect anywhere, even at 0 | the firmware applies its own value regardless, or the step is too small: check that no value of the printer's own replaces the file's |")
            l("| grooves everywhere | the value lies above the plate's top: raise the tool's `calibrate.pressure_advance.max`, or give higher `--values`, where the top is the ceiling; where it is the bed's, a longer bed axis; where it is the extruder's push limit, this extruder cannot reach it on this plate |")
            l("| the corners flip somewhere else than the walls | reported beside the value, which stays the walls': a corner is taken at the printer's corner speed, so it carries more than pressure advance |")
            l("| stringing, blobs, or clicking on the highest walls | the extruder slipped or squirted at its push: its highest filament speed is lower than the plate took it to be, or not set (see the plate's limits) |")
            l("| ridges everywhere | even the lowest value is too high: 0 on a first plate means the firmware adds its own |")
            l("| the two rows disagree by more than one step (one wall's place on the plate) | position or print order matters (cooling, the bed's warm side); refused |")
            l("| walls wobble or fall | a single line is too frail at this height |")
        } else {
            l("| if you see | it means |")
            l("|---|---|")
            l(if (info.machineOwn) "| flat at all three steps | the machine's own value holds |" else "| the middle value flat at all three steps, its low neighbor grooved and its high neighbor ridged | the value holds: `values` writes it |")
            if (!info.machineOwn) l("| the flat wall moves with the flow step | the true value depends on the flow: `values` reports each step's flat value and writes the medium step's, the range in its note |")
            l(if (info.machineOwn) "| grooves or ridges | the machine's value is low (grooves) or high (ridges) at those steps |" else "| nothing reads flat | the find plate was misread or the conditions changed: no value |")
        }
        l()
        l("### What to enter")
        l()
        l("`plymeld calibrate pressure-advance readings ${info.gcodeName} -o readings.toml` writes the sheet to fill in: an entry per wall with its value, `speed_up`, `slow_down`${if (find) " and `corner`" else ""} each `?`. Replace every `?` with " +
            "${MARKS.joinToString(", ")}${if (find) " (the corner: ${CORNERS.joinToString(", ")})" else ""}, then `plymeld calibrate pressure-advance values readings.toml` " +
            (if (info.machineOwn) "says whether the machine's value holds (nothing is written: the machine sets its own)." else "turns it into the filament's pressure advance (`hardware.filament.<key>.pressure_advance`; the tool's with `--into tool`), written into the file that defines it with the plate, its conditions, and the bracket in the note above it."))
        return sb.toString()
    }

    /**
     * The two ways to read a wall, drawn: a wall's face seen square on with the mark's hole below it, a groove, a flat
     * stretch, and a ridge just after the mark, held against a light behind it (a thinner stretch brighter, a thicker one
     * darker) and in a raking light along its face from the left (each slope facing the light bright, each facing away
     * dark: a groove dark then bright, a ridge bright then dark, from the left). The base under the face carries the mark's
     * hole, so "just after the mark" points somewhere.
     */
    fun readingPicture(file: java.nio.file.Path) {
        System.setProperty("java.awt.headless", "true")
        val W = 1600; val H = 540
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 22)
        gr.drawString("Reading a wall snapped out on its strip: its face square on, printing left to right, the mark's hole below it", 40, 36)
        val panelW = (W - 120) / 2; val subW = (panelW - 40) / 3
        for ((p, how) in listOf("held against a light behind it", "in a raking light along its face, from the left").withIndex()) {
            val px0 = 40 + p * (panelW + 40)
            gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 18)
            gr.drawString(how, px0, 80)
            for ((i, m) in MARKS.withIndex()) {
                val x0 = (px0 + i * (subW + 20)).toDouble(); val y0 = 110.0; val fw = subW.toDouble(); val fh = 300.0
                val xm = x0 + fw * 0.35; val xr = xm + fw * 0.25
                if (p == 0) {
                    // a backlit face: the plastic's light through it, brighter where thinner, darker where thicker
                    gr.color = Color(150, 120, 70); gr.fill(Rectangle2D.Double(x0, y0, fw, fh))
                    if (m != "flat") { gr.color = if (m == "groove") Color(225, 200, 140) else Color(90, 70, 40); gr.fill(Rectangle2D.Double(xm, y0, xr - xm, fh)) }
                } else {
                    // a face in a raking light from the left: each slope facing the light bright, each facing away dark; a groove
                    // dips (its near slope faces away, its far slope toward the light): dark, then bright; a ridge rises: bright, then dark
                    gr.color = Color(120, 120, 128); gr.fill(Rectangle2D.Double(x0, y0, fw, fh))
                    if (m != "flat") {
                        val bright = Color(235, 235, 240); val dark = Color(40, 40, 46)
                        val (first, second) = if (m == "groove") dark to bright else bright to dark
                        gr.color = first; gr.fill(Rectangle2D.Double(xm, y0, 14.0, fh))
                        gr.color = second; gr.fill(Rectangle2D.Double(xr - 14, y0, 14.0, fh))
                    }
                }
                // the base under the face, the mark's hole in it at the start of the stretch read; the direction of travel
                gr.color = Color(205, 208, 214); gr.fill(Rectangle2D.Double(x0, y0 + fh, fw, 28.0))
                gr.color = Color(250, 250, 252); gr.fill(Rectangle2D.Double(xm - 14, y0 + fh + 7, 14.0, 14.0))
                gr.color = Color(70, 70, 80); gr.stroke = BasicStroke(2f)
                gr.draw(Line2D.Double(x0, y0 + fh + 46, x0 + 60, y0 + fh + 46)); gr.draw(Line2D.Double(x0 + 50, y0 + fh + 40, x0 + 60, y0 + fh + 46)); gr.draw(Line2D.Double(x0 + 50, y0 + fh + 52, x0 + 60, y0 + fh + 46))
                gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 17)
                gr.drawString(m, x0.toInt(), (y0 + fh + 80).toInt())
                gr.font = Font("SansSerif", Font.PLAIN, 13)
                gr.drawString(when {
                    m == "flat" -> "the same past the mark"
                    p == 0 -> if (m == "groove") "brighter just after the mark" else "darker just after the mark"
                    else -> if (m == "groove") "dark, then bright, just after it" else "bright, then dark, just after it"
                }, x0.toInt(), (y0 + fh + 100).toInt())
            }
        }
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    /** The picture: the plate from above (the walls on their strips, their marks, corners, labels, the chamfer) and a legend of the three readings. */
    fun picture(file: java.nio.file.Path, spec: PressureAdvanceSpec, plate: PressureAdvancePlate) {
        System.setProperty("java.awt.headless", "true")
        val b = plate.bounds()
        val W = 1600
        val scale = (W - 120).toDouble() / (b[2] - b[0])
        val plateH = Math.ceil((b[3] - b[1]) * scale).toInt()
        val H = plateH + 420
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val top = 90.0
        fun px(x: Long) = 60 + (x - b[0]) * scale
        fun py(y: Long) = top + plateH - (y - b[1]) * scale
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 22)
        gr.drawString("Pressure-advance plate from above (${spec.kind.key}): ${spec.walls.size} step walls; the front, with the chamfered corner, toward you", 40, 36)
        gr.font = Font("SansSerif", Font.PLAIN, 15)
        gr.drawString("Each wall prints toward the back (its arrow). Holes on its left: where it starts to speed up (front) and to slow down (back); read just behind each. Red: the fast middle.", 40, 62)
        // outlines and their holes as one even-odd shape, so a hole (a mark, a glyph's counter) stays open
        fun fillPolys(ps: List<plymeld.geom.Polygon>, c: Color) {
            gr.color = c
            val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
            for (p in ps) { for (i in 0 until p.size) { val x = px(p.x(i)); val y = py(p.y(i)); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }; path.closePath() }
            gr.fill(path)
        }
        // the base above its first layer (its marks cut out), the labels and arrows raised on it
        fillPolys(plate.baseOutline(1), Color(205, 208, 214))
        for (w in spec.walls) fillPolys(plate.labelAndArrow(w), Color(70, 70, 80))
        val lw = Math.max(2.0, spec.widthUm * scale)
        for (w in spec.walls) {
            val x = px(spec.wallXUm(w)); val y0 = spec.wallStartY(w)
            gr.stroke = BasicStroke(lw.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            gr.color = Color(30, 30, 35); gr.draw(Line2D.Double(x, py(y0), x, py(y0 + spec.leadUm)))
            gr.color = Color(200, 40, 40); gr.draw(Line2D.Double(x, py(y0 + spec.leadUm), x, py(y0 + spec.leadUm + spec.fastUm)))
            gr.color = Color(30, 30, 35); gr.draw(Line2D.Double(x, py(y0 + spec.leadUm + spec.fastUm), x, py(spec.wallEndY(w))))
            if (spec.corners) {
                val c = plate.cornerPath(w)
                gr.color = Color(30, 90, 170)
                gr.draw(Line2D.Double(px(c[0] - spec.originXUm), py(c[1] - spec.originYUm), px(c[2] - spec.originXUm), py(c[3] - spec.originYUm)))
                gr.draw(Line2D.Double(px(c[2] - spec.originXUm), py(c[3] - spec.originYUm), px(c[4] - spec.originXUm), py(c[5] - spec.originYUm)))
            }
        }
        // the legend: a wall seen from above at each reading, the mark (a hole) and the direction of travel
        val ly = top + plateH + 60
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 18)
        gr.drawString("Reading a mark (the wall from above, printing left to right; the hole at the mark, read just after it):", 40, ly.toInt())
        val panelW = (W - 160) / 3
        for ((i, m) in MARKS.withIndex()) {
            val x0 = 60.0 + i * (panelW + 20); val yMid = ly + 120
            gr.color = Color(205, 208, 214); gr.fill(Rectangle2D.Double(x0, yMid - 60, panelW.toDouble(), 120.0))
            gr.color = Color(250, 250, 252); gr.fill(Rectangle2D.Double(x0 + panelW * 0.33 - 8, yMid - 46, 16.0, 22.0))
            // the wall: as thick as a line, thinner (groove) or thicker (ridge) for a stretch just after the mark
            val t = 14.0; val change = when (m) { "groove" -> -7.0; "ridge" -> 8.0; else -> 0.0 }
            val path = Path2D.Double()
            val xs = x0 + 10; val xe = x0 + panelW - 10; val xm = x0 + panelW * 0.33; val xr = xm + panelW * 0.22
            path.moveTo(xs, yMid - t / 2); path.lineTo(xm, yMid - t / 2)
            path.curveTo(xm + 10, yMid - t / 2 - change / 2, xm + 20, yMid - (t + change) / 2, xm + 35, yMid - (t + change) / 2)
            path.lineTo(xr - 20, yMid - (t + change) / 2); path.curveTo(xr - 5, yMid - (t + change) / 2, xr, yMid - t / 2, xr + 15, yMid - t / 2)
            path.lineTo(xe, yMid - t / 2); path.lineTo(xe, yMid + t / 2); path.lineTo(xr + 15, yMid + t / 2)
            path.curveTo(xr, yMid + t / 2, xr - 5, yMid + (t + change) / 2, xr - 20, yMid + (t + change) / 2)
            path.lineTo(xm + 35, yMid + (t + change) / 2); path.curveTo(xm + 20, yMid + (t + change) / 2, xm + 10, yMid + t / 2 + change / 2, xm, yMid + t / 2)
            path.lineTo(xs, yMid + t / 2); path.closePath()
            gr.color = Color(30, 30, 35); gr.fill(path)
            gr.stroke = BasicStroke(2f); gr.color = Color(70, 70, 80)
            gr.draw(Line2D.Double(xs, yMid + 40, xs + 60, yMid + 40)); gr.draw(Line2D.Double(xs + 50, yMid + 34, xs + 60, yMid + 40)); gr.draw(Line2D.Double(xs + 50, yMid + 46, xs + 60, yMid + 40))
            gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 18)
            gr.drawString(m, x0.toInt(), (yMid + 85).toInt())
            gr.font = Font("SansSerif", Font.PLAIN, 13)
            gr.drawString(when (m) { "groove" -> "thin just after the mark"; "ridge" -> "fat just after the mark"; else -> "the same past the mark" }, x0.toInt(), (yMid + 105).toInt())
        }
        gr.color = Color(70, 70, 75); gr.font = Font("SansSerif", Font.PLAIN, 14)
        gr.drawString("At the speed-up mark: groove = too little pressure advance, ridge = too much. The slow-down mark reads the opposite.", 40, H - 24)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
