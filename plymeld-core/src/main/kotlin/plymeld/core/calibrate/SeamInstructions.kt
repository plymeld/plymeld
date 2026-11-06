package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each seam plate ([SeamPlate]; the ironing plate's is [IroningInstructions]): the question,
 * what to do before printing, the conditions, a labeled picture (the pieces from above, their seams marked, the ticks, the
 * chamfer), the pieces and what each reads, how to grade (a readings sheet, a line per piece), what each kind of result
 * means (decided before printing), and how the readings are written back as the seam measure's shares.
 */
object SeamInstructions {
    /**
     * How visible a seam is, from nothing to glaring: the grade's index is its score (0..3), and a share is one seam's score
     * over its reference's. plymeld's words: the ironing plate's good / marginal / bad grade a surface's
     * quality; this grades how far away a blemish can be seen, a different idea.
     */
    val GRADES = listOf("hidden", "faint", "plain", "glaring")
    val GRADE_MEANING = mapOf(
        "hidden" to "you cannot find the seam, even close up with the light raking across the face",
        "faint" to "you find it only close up, knowing where to look: a hairline, a slight dullness",
        "plain" to "you see it at arm's length: a line, a bead, or a notch the length of the piece",
        "glaring" to "you see it across the room: a ridge, a gap, or a blob that draws the eye")

    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: SeamSpec, plate: SeamPlate, info: PlateInstructions.Info) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, plate, info, png.fileName.toString()))
    }

    fun markdown(spec: SeamSpec, plate: SeamPlate, info: PlateInstructions.Info, pngName: String): String {
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        val g = SeamGeometry
        l("# Seam plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating and any levelling come on top)" } ?: "") + ".")
        l("One slab, ${n(spec.slabW / 1000.0, 0)} x ${n(spec.slabD / 1000.0, 0)} mm, with **${spec.pieces.size} hollow pieces** ${n(g.piece / 1000.0, 0)} mm across and ${n(spec.pieceLayers * spec.layerUm / 1000.0, 1)} mm tall standing in a row on it, each with its outer wall's seam laid one way through every layer. " +
            "**The question: how visible is a seam laid each way** - a scarf seam against a point seam, a seam in a corner against one in the middle of a face, a seam on an underside against one on a wall. " +
            "The ratios are the seam measure's shares (`method.quality.seam.scarf_share`, `.corner_share`, `method.quality.visibility.under`), which decide where plymeld puts every seam; the bundled ones were read from one plate in PLA.")
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
        })
        l("2. **Run any other calibration your printer offers before a print**: nozzle or tool offsets on a multi-tool machine, a z-offset, its own flow or pressure-advance calibration (note whether it ran: a seam's size depends on pressure advance and retraction as much as on where it is), a nozzle clean or wipe.")
        l("3. **Clean the bed**${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C and stays there.")
        l("4. **Load `${info.filament}` on tool ${info.tool + 1}.** Dry filament matters: moisture shows at every seam as a blob.")
        l("5. **During the print**: the two leaning pieces (${spec.pieces.filter { it.shape.leanDeg > 0 }.joinToString(" and ") { "${it.index + 1}" }}) reach forward over the slab as they rise; that is their underside, and it prints at the wall's speed with no overhang slow-down. The whole plate prints at one temperature.")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| nozzle temperature | ${spec.tempC} C throughout (the filament's print temperature), waited for before the first line |")
        l("| layer height | ${n(spec.layerUm / 1000.0, 2)} mm; the slab's first layer ${n(spec.firstLayerUm / 1000.0, 2)} mm; ${spec.layers} layers (the slab ${g.slabLayers}, the pieces ${spec.pieceLayers}), the top at z ${n(spec.topZ / 1000.0, 2)} mm |")
        l("| line width | outer walls ${n(spec.widthUm / 1000.0, 2)} mm, inner walls ${n(spec.innerWidthUm / 1000.0, 2)} mm, the slab's first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off on the first layer, ${n(spec.fanPct, 0)} % from the second (the filament's own, cooling.fan) |")
        l("| the slab | its first layer at ${n(spec.adhesion.speed, 0)} mm/s, ${n(spec.adhesion.accel, 0)} mm/s2 (the configured first-layer speed, held to the filament's maximum flow); its layers above at ${n(spec.frame.speed, 0)} mm/s, ${n(spec.frame.accel, 0)} mm/s2 (the machine's print speed and acceleration, held to the maximum flow) |")
        l("| the outer walls (what you grade) | ${n(spec.outer.speed, 0)} mm/s, ${n(spec.outer.accel, 0)} mm/s2 (a visible wall's speed, held to the maximum flow, and acceleration); no overhang slow-down on the leaning faces |")
        l("| the inner walls | ${n(spec.inner.speed, 0)} mm/s, ${n(spec.inner.accel, 0)} mm/s2 (the machine's print speed, held to the maximum flow); their seams at the back, out of sight |")
        l("| the scarf seams | plymeld's own: a ramp ${n(spec.scarfUm / 1000.0, 1)} mm long (plan.technique.scarf_seam.length) starting ${n(spec.scarfStart * 100, 0)} % of the layer thick (.start), the loop, then the ramp again thinning to nothing |")
        l("| a corner | a turn of ${n(spec.cornerDeg, 0)} degrees or more (plan.technique.corner_seam.corner_angle): the square pieces' corners are 90 |")
        l("| retraction | ${n(spec.outer.retractMm, 2)} mm, lift ${n(spec.liftUm / 1000.0, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: the pieces with their seams marked, the ticks, and the chamfer]($pngName)")
        l()
        l("- **The pieces** stand in a row, ${n(g.gap / 1000.0, 0)} mm apart, **piece 1 at the chamfered front-left corner**, the rest to its right in order; that is also the print order (every layer lays them left to right). Each is two walls with nothing inside; its outer wall's seam is at the same place on every layer, so a seam reads as one vertical line.")
        l("- **The marks.** The slab's **front-left corner is chamfered**: the chamfered edge is the front, which faces you. A **tick** is cut into the front edge and the back edge between each pair of pieces (an indent ${n(g.tickW / 1000.0, 0)} mm wide, ${n(g.tickIn / 1000.0, 1)} mm deep); **every ${g.tickLongEvery}th tick is long** (${n(g.tickLongIn / 1000.0, 0)} mm). To number a piece, count the ticks between it and the chamfer and add one.")
        l("- **The seams face you.** Every outer wall's seam is on the piece's front (a round piece's front-most line, a square piece's front face or its front-left corner, a leaning piece's leaning face); the inner walls' seams are at the back and are not graded.")
        l()
        l("| piece | shape | seam | laid as | reads |")
        l("|---|---|---|---|---|")
        for (p in spec.pieces) l("| ${p.index + 1} | ${shapeWords(p)} | ${atWords(p)} | ${if (p.style == SeamStyle.SCARF) "scarf" else "point"} | ${reads(spec, p)} |")
        l()
        l("## How to grade the seams")
        l()
        l("Set the plate down with the chamfered corner at your front-left and the ticked front edge toward you, in the light the print will be seen in, your eye level with the pieces. For **each piece** find its outer wall's seam on its **front** (the line where the wall's loop starts and ends, running the piece's full height: a hairline, a bead, a notch, a dull streak, or nothing). Look from arm's length first, then close up, then with a light raking across the face from one side. Write one grade per piece:")
        l()
        for (gr in GRADES) l("${GRADES.indexOf(gr)}. **$gr**: ${GRADE_MEANING.getValue(gr)}.")
        l()
        l("The grade is how far away the seam shows, not whether you like it: two seams that look different but are both found only close up are both **faint**. Grade only the outer walls' front seams, not the inner walls, the slab, or the leaning faces' texture (a leaning face is rougher than a wall; grade its seam against that face, as a wall's is against the wall). The leaning pieces' faces reach forward: look at them as you would at a print's underside, from in front.")
        l()
        l("| piece | seam | grade (${GRADES.joinToString(" / ")}) | what you saw |")
        l("|---|---|---|---|")
        for (p in spec.pieces) l("| ${p.index + 1} | ${p.describe()} |  |  |")
        l()
        l("### What the results mean (decided before printing)")
        l()
        l("Each share is one piece's score (hidden 0, faint 1, plain 2, glaring 3) over its reference's, at most 1: the scarf share from pieces 2 against 1 and 4 against 3 (the mean of the two), the corner share from 5 against 3, the underside share from 7 and 8 against 3 (each lean's ratio turned into the share a horizontal underside would have, by the measure's own rule: a face leaning L degrees counts 1 - (1 - share) x sin L).")
        l()
        l("- **The scarf seams read fainter than the point seams** (the expected result): the scarf share is their ratio; the planner then scarfs smooth loops where the time it costs is worth it. **They read the same**: the share is 1, and the planner stops paying for scarfs on this filament. **They read worse** (a bulge or a thin streak along the ramp): the share is still 1 (never above), and the scarf's start thickness or length wants a plate of its own.")
        l("- **The corner seam reads fainter than the face seam**: the corner share is the ratio; corners keep their preference. **The same or worse** (a notch or a blob at the corner): the share is 1, and corners lose their credit.")
        l("- **The leaning faces' seams read fainter the steeper the lean**: the underside share follows. **The same as the wall's**: the share is 1 (an underside counts in full). **Worse** (the seam stands out on the rough face): still 1; the roughness is the overhang's, not the seam's, and belongs to the overhang tests.")
        l("- **Piece 6 (scarf in a corner) against piece 5 (point in a corner)** says whether a scarf helps where a corner already hides the seam; it is reported, not written back.")
        l("- **Every point seam reads hidden**: there is nothing to compare against at this size; the shares stay as they were, and the plate says so. Seams this good need no placement.")
        l("- **Every seam reads glaring, scarfs and corners too**: the blemish is not the placement's: look at retraction, pressure advance, and the flow ratio (the filament plate), then print again.")
        l("- **The turned-away share** (`method.quality.visibility.away`, how much a print's back counts) is a preference, not a print quality: this plate does not read it (`plan.view_from` and the share are yours to set).")
        l()
        l("### What to enter")
        l()
        l("`plymeld calibrate seams readings ${info.gcodeName} -o readings.toml` writes the sheet to grade (one entry per piece, its grade `?`). Replace every `?` with ${GRADES.joinToString(", ")}, then " +
            "`plymeld calibrate seams values readings.toml -o seams.toml` turns the sheet into the shares (`method.quality.seam.scarf_share`, `.corner_share`, `method.quality.visibility.under`), each with the pieces and grades it came from, and leaves out any the readings cannot give. Add the file with `-cf`.")
        return sb.toString()
    }

    private fun shapeWords(p: SeamPiece) = when (p.shape) { SeamShape.ROUND -> "round"; SeamShape.SQUARE -> "square"; else -> "square, front face leaning out ${n(p.shape.leanDeg, 0)} deg" }
    private fun atWords(p: SeamPiece) = when { p.shape == SeamShape.ROUND -> "front-most point"; p.shape.leanDeg > 0 -> "middle of the leaning face"; p.at == SeamAt.CORNER -> "front-left corner"; else -> "middle of the front face" }

    /** What a piece reads, against which reference. */
    fun reads(spec: SeamSpec, p: SeamPiece): String {
        fun ref(shape: SeamShape, at: SeamAt, style: SeamStyle) = spec.pieces.firstOrNull { it.shape == shape && it.at == at && it.style == style }?.let { "piece ${it.index + 1}" } ?: "(no reference on this plate)"
        return when {
            p.shape.leanDeg > 0 -> "the underside share: against ${ref(SeamShape.SQUARE, SeamAt.FACE, SeamStyle.POINT)} (`method.quality.visibility.under`)"
            p.style == SeamStyle.SCARF && p.at == SeamAt.CORNER -> "whether a scarf helps in a corner: against ${ref(SeamShape.SQUARE, SeamAt.CORNER, SeamStyle.POINT)} (reported, not written back)"
            p.style == SeamStyle.SCARF -> "the scarf share: against ${ref(p.shape, SeamAt.FACE, SeamStyle.POINT)} (`method.quality.seam.scarf_share`)"
            p.at == SeamAt.CORNER -> "the corner share: against ${ref(SeamShape.SQUARE, SeamAt.FACE, SeamStyle.POINT)} (`method.quality.seam.corner_share`)"
            p.shape == SeamShape.ROUND -> "the reference of piece ${spec.pieces.firstOrNull { it.shape == SeamShape.ROUND && it.style == SeamStyle.SCARF }?.let { it.index + 1 } ?: "-"} (a point seam on a smooth wall)"
            else -> "the reference of the corner, the leaning faces, and its scarf (a point seam on a flat wall)"
        }
    }

    /** The picture: the slab from above, each piece's outer wall on its lowest and highest layer, its seam marked (a dot for a point seam, a heavy arc along the scarf's ramp), the ticks, and the chamfer. */
    fun picture(file: java.nio.file.Path, spec: SeamSpec, plate: SeamPlate) {
        System.setProperty("java.awt.headless", "true")
        val W = 1800; val H = 560
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val b = plate.bounds()
        val ox = spec.originXUm; val oy = spec.originYUm
        val s = minOf((W - 120).toDouble() / (b[2] - b[0] + 8_000), (H - 300).toDouble() / (b[3] - b[1] + 8_000))
        fun px(x: Long) = 60 + (x - ox - b[0] + 4_000) * s
        fun py(y: Long) = 130 + (H - 300) - (y - oy - b[1] + 4_000) * s
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 22)
        gr.drawString("Seam plate from above: ${spec.pieces.size} pieces on one slab, piece 1 at the chamfer; the front (the ticked edge nearest you) faces the viewer", 40, 40)
        gr.font = Font("SansSerif", Font.PLAIN, 16)
        gr.drawString("Each piece's outer wall on its first layer (gray) and its last (black); its seam a red dot (a point seam) or a red arc along the scarf's ramp (a scarf seam). Dashed: a leaning face's reach.", 40, 68)
        // the slab's outline above the first layer (ticks and chamfer)
        gr.color = Color(40, 40, 45); gr.stroke = BasicStroke(2f)
        val o = plate.outline(SeamGeometry.slabLayers - 1)
        for (i in 0 until o.size) { val j = (i + 1) % o.size; gr.draw(Line2D.Double(px(ox + o.x(i)), py(oy + o.y(i)), px(ox + o.x(j)), py(oy + o.y(j)))) }
        for (p in spec.pieces) {
            val k = p.index
            for ((i, col, width) in listOf(Triple(0, Color(170, 170, 175), 1.5f), Triple(spec.pieceLayers - 1, Color(30, 30, 35), 2f))) {
                val l = spec.loop(k, i, false)
                gr.color = col; gr.stroke = if (i == 0 && p.shape.leanDeg > 0) BasicStroke(width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(4f, 4f), 0f) else BasicStroke(width)
                val m = l.size / 2
                for (v in 0 until m) { val u = (v + 1) % m; gr.draw(Line2D.Double(px(l[2 * v]), py(l[2 * v + 1]), px(l[2 * u]), py(l[2 * u + 1]))) }
            }
            // the seam: on the last layer's loop
            val l = spec.loop(k, spec.pieceLayers - 1, false)
            gr.color = Color(200, 30, 30)
            if (p.style == SeamStyle.SCARF) {
                gr.stroke = BasicStroke(5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                var left = spec.scarfUm.toDouble(); val m = l.size / 2; var v = 0
                while (left > 0 && v < m) {
                    val u = (v + 1) % m
                    val dx = (l[2 * u] - l[2 * v]).toDouble(); val dy = (l[2 * u + 1] - l[2 * v + 1]).toDouble()
                    val len = Math.hypot(dx, dy); val f = minOf(1.0, left / len)
                    gr.draw(Line2D.Double(px(l[2 * v]), py(l[2 * v + 1]), px(l[2 * v] + Math.round(dx * f)), py(l[2 * v + 1] + Math.round(dy * f))))
                    left -= len; v++
                }
            } else {
                val r = 6.0
                gr.fill(Ellipse2D.Double(px(l[0]) - r, py(l[1]) - r, 2 * r, 2 * r))
            }
            // the number and the kind
            val c = spec.cell(k)
            val cx = px(ox + (c[0] + c[2]) / 2); val cy = py(oy + (c[1] + c[3]) / 2)
            gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 18)
            val num = "${k + 1}"
            gr.drawString(num, (cx - gr.fontMetrics.stringWidth(num) / 2).toInt(), (cy - 6).toInt())
            gr.font = Font("SansSerif", Font.PLAIN, 11)
            val words = listOf(shapeWords(p).substringBefore(','), "${if (p.at == SeamAt.CORNER) "corner" else "face"} ${p.style.key}")
            for ((j, t) in words.withIndex()) gr.drawString(t, (cx - gr.fontMetrics.stringWidth(t) / 2).toInt(), (cy + 10 + j * 13).toInt())
        }
        gr.color = Color(70, 70, 75); gr.font = Font("SansSerif", Font.PLAIN, 14)
        gr.drawString("The slab is ${n((b[2] - b[0]) / 1000.0, 0)} x ${n((b[3] - b[1]) / 1000.0, 0)} mm; the chamfer at the front-left corner marks the origin; the front is towards you.", 40, H - 30)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
