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
 * The page that goes with each thermal plate ([ThermalPlate]): the question, what to do before printing, the conditions, a
 * labeled picture (the plate from above, its sections and gauges), each section and how to read it (a position or a go/no-go
 * against a gauge printed with the plate, never a grade of quality), a readings sheet, and what the readings mean (decided
 * before printing). There is no readings file or write-back yet: the readings are recorded by hand on the sheet.
 */
object ThermalInstructions {
    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: ThermalSpec, plate: ThermalPlate, info: PlateInstructions.Info) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, info, png.fileName.toString()))
    }

    fun markdown(spec: ThermalSpec, info: PlateInstructions.Info, pngName: String): String {
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        val g = ThermalGeometry
        val h = spec.layerUm / 1000.0
        fun mm(um: Long) = n(um / 1000.0, 2)
        l("# Thermal plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate, the bands' waits included; heating and any levelling come on top)" } ?: "") + ".")
        l("One slab, ${n(spec.slabW / 1000.0, 0)} x ${n(spec.slabD / 1000.0, 0)} mm, with five sections standing on it, and two gauges printed flat in front of it. " +
            "**The question: how does this filament cool on this machine?** Five readings: how long a bead stays soft, by fan duty (**the strands**); " +
            "the shortest layer time a small part takes (**the pins**); how the bond between layers holds as the layer below cools, by layer time and fan duty (**the fins**); " +
            "how far the part fan reaches round a part, by direction (**the compass**); and how long a fan command takes to act (**the lag wall**). " +
            "Every reading is a position (\"the first marked step where ...\") or a go/no-go against a gauge printed with the plate: nothing is graded by eye. " +
            "plymeld has no setting to write these readings to yet: record them on the sheet below and keep it with the filament.")
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
            info.levels -> "This file runs the printer's bed levelling (hardware.machine.<key>.gcode.level) before the plate, once it has heated."
            info.levelSkipped -> "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling (mesh) yourself first."
            else -> "plymeld cannot level this printer from the g-code (no levelling command is known for it): turn on the printer's own levelling when you start the print, or run it from the printer's menu first."
        })
        l("2. **Clean the bed**${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C and stays there. Keep the printer's door and lid as you print everything else: the readings are the machine's as it is.")
        l("3. **Load `${info.filament}` on tool ${info.tool + 1}.**")
        l("4. **During the print** the head often parks clear of the plate and waits: before each strand row and each layer of the lag wall (the fan settling to its duty), and on every layer of the pins' bands and the fins' bands (each layer made up to its band's time). That is the plate working, not a stall.")
        l("5. **After the print**, let it cool on the bed, then lift the plate off in one piece (the gauges come with it on their thin tabs). Snap the feeler and the ring card off their tabs before you read.")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| nozzle temperature | ${spec.tempC} C above the first layer" + (if (spec.firstTempC != spec.tempC) ", waited for before anything above the slab; the first layer at ${spec.firstTempC} C" else "") + " |")
        l("| layer height | ${n(h, 2)} mm; the first layer ${n(spec.firstLayerUm / 1000.0, 2)} mm; ${spec.layers} layers (the slab ${g.slabLayers}), the pins' tops ${n((spec.topZ - spec.slabTop) / 1000.0, 1)} mm above the slab |")
        l("| line width | ${n(spec.widthUm / 1000.0, 2)} mm, the first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off on the first layer; where no section sets its own, ${n(spec.fanPct, 0)} % (the filament's cooling.fan) |")
        l("| speeds | the first layer ${n(spec.adhesion.speed, 0)} mm/s; the slab, piers, posts and pedestals ${n(spec.frame.speed, 0)} mm/s; the read surfaces (the pins, the fins, the compass's tabs, the lag wall's outer line) ${n(spec.visible.speed, 0)} mm/s at ${n(spec.visible.accel, 0)} mm/s2 (a visible wall's); the strands ${n(spec.bridge.speed, 0)} mm/s at a flow of ${n(spec.bridge.flowRatio, 2)} (the bridge speed and flow) |")
        l("| the fan's settle | ${n(spec.settleS, 1)} s, the head parked, after the fan changes for a strand row or the lag wall |")
        l("| retraction | ${n(spec.frame.retractMm, 2)} mm, lift ${n(spec.liftUm / 1000.0, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The plate from above: the five sections on the slab, the gauges in front]($pngName)")
        l()
        l("Hold the plate with its **chamfered corner at your front-left** (the origin): \"from the front\" and \"from the left\" count from there. A raised label is the value it names (`50%` a fan duty, `60s` a layer time, `20` a span in mm, `2.3` a hole in mm); notches count where a label does not fit.")
        l()
        l("- **The strands** (front): ${spec.strandFans.size} rows, front to back at ${spec.strandFans.joinToString(", ") { spec.dutyText(it) }}, each duty raised at the row's left. In each row a pair of piers ${mm(spec.pierLayers * spec.layerUm)} mm tall per span (${spec.spansUm.joinToString(", ") { mm(it) }} mm, shortest on the left, each span raised in front of the front row), one single line laid across each gap at the piers' top at the bridge speed, then ${g.capLayers} cap layers on the piers that hold the strand's ends. The fan is set to the row's duty and waited for before the row's strands.")
        l("- **The feeler** (in front of the slab, left): a stepped bar ${mm(g.feelerWidth)} mm wide, its steps ${g.FEELER_SAGS.indices.joinToString(", ") { mm(spec.feelerThick(it)) }} mm thick from its thin end, allowing a sag of ${g.FEELER_SAGS.indices.joinToString(", ") { mm(spec.feelerSag(it)) }} mm under a strand; each step's sag is a tally of notches in its edge (${g.FEELER_SAGS.indices.joinToString(", ") { "${g.FEELER_SAGS.size - it}" }}: the thickest step one notch).")
        l("- **The fins** (middle left): ${spec.finFans.size} rows front to back at ${spec.finFans.joinToString(", ") { spec.dutyText(it) }} (each fin's own lines at its row's duty), ${spec.finTimesS.size} fins in each, left to right at ${spec.finTimesS.joinToString(", ") { spec.timeText(it) }} (raised in front of the front row). Each fin is ${mm(g.finWidth)} mm wide: three lines thick below its one **test band** (${mm(spec.finBandRange(0).count() * spec.layerUm)} mm tall, a V notch cut into both edges at its middle), single above it for a lever of ${mm(g.lever)} mm. Fin k's band sits at its own height (${spec.finTimesS.indices.joinToString(", ") { mm(spec.finBandRange(it).first * spec.layerUm) }} mm above the slab), and **while its band prints, every layer of the plate takes that fin's time**. A stop post stands ${mm(g.postBack)} mm behind each fin, as tall as it.")
        l("- **The compass** (middle): a round post ${mm(g.compassPost)} mm across with ${g.tabs} identical tabs round it, each a ledge stepping out ${mm(spec.tabStep)} mm a layer (${n(g.tabLeanDeg, 0)} degrees from vertical) for ${spec.tabLayers} layers, two lines thick, ${mm(g.tabWidth)} mm wide; each tab's tip stands between two reference posts as tall as the tips. The directions are numbered on the slab, **1 toward the front, counted clockwise from above**.")
        l("- **The lag wall** (back left): a wall two lines thick, ${mm(g.lagLength)} mm long and ${mm(spec.lagLayers * spec.layerUm)} mm tall along the slab's back edge, its outer (back) line laid **always left to right** at ${n(spec.visible.speed, 0)} mm/s (raised in front of it). In its lower half the fan is off until a mark ${mm(g.lagSwitch)} mm along, then full; in its upper half full until the mark, then off. **A notch in the slab's back edge** marks the switch and **one every ${mm(g.lagPitch)} mm after it** (every fifth long).")
        l("- **The pins** (back right): ${spec.pinDiametersUm.size} round pins, ${spec.pinDiametersUm.joinToString(", ") { mm(it) }} mm across left to right, each on a pedestal ${mm(g.pedestal)} mm across up to ${mm(spec.pedestalLayers * spec.layerUm)} mm above the slab, their test lengths above in **${spec.pinTimesS.size} bands of ${mm(spec.pinBandLayers * spec.layerUm)} mm**, each band's layers at ${spec.pinTimesS.joinToString(", ") { spec.timeText(it) }} from the bottom (the shortest lowest). Beside them **the band stair**: a block whose steps, front to back, top out at each band's top, each band's layer time raised on the slab beside its step.")
        l("- **The ring card** (in front of the slab, right): a flat card with a hole per pin, each its pin's diameter plus ${mm(g.holeClearance)} mm (${spec.pinDiametersUm.indices.joinToString(", ") { spec.holeText(it) }} mm), each hole's diameter raised beside it.")
        l()
        l("## How to read it")
        l()
        l("### 1. The strands: how long a bead stays soft, by fan duty")
        l()
        l("For each row, slide the feeler's **${mm(spec.feelerThick(1))} mm step** (two notches) under each strand from the front, along the gap, its thin end first. **Reading, per row: the longest span the ${mm(spec.feelerThick(1))} mm step slides under** without lifting the strand. Then refine with the other steps: the longest span the ${mm(spec.feelerThick(2))} mm step (one notch) slides under, and the ${mm(spec.feelerThick(0))} mm step (three notches).")
        l()
        l("### 2. The pins: the real minimum layer time")
        l()
        l("For each pin, lower the ring card's matching hole (the one labeled with the pin's diameter plus ${mm(g.holeClearance)}) over it from the top and let it slide down until it stops. **Reading, per pin: the band it stops in**, read off the band stair beside the pins (the step whose top is level with the card or just above it; its label is that band's layer time). A card that slides down to the pedestal: every band was good. A layer time that bulges a pin is shorter than the filament's real minimum for a part that size.")
        l()
        l("### 3. The fins: the bond against how cold the layer below was")
        l()
        l("For each row, from the left: press the fin's top back with a finger, slowly, until it touches its stop post. **Reading, per row: the first fin (from the left) that snaps before it touches its post** (it breaks at its notched band). None snaps: write \"none\".")
        l()
        l("### 4. The compass: fan reach and shadowing by direction")
        l()
        l("Lay the feeler's **flat back** (the face that was on the bed) across each tab's two reference posts. **Reading: the numbers of the tabs whose tip holds the feeler off its posts** (the tip curled up): those directions the fan does not reach well.")
        l()
        l("### 5. The lag wall: how long a fan command takes to act")
        l()
        l("Look at the wall's back face along the notches in the slab's back edge. The first notch marks where the fan was switched. **Reading, for each half: the number of notches from the switch to where the surface changes** (lower half: from the soft, glossy finish of the fan off to the fan's; upper half: the other way). The lag is that count times ${mm(g.lagPitch)} mm, over ${n(spec.visible.speed, 0)} mm/s (the speed raised in front of the wall): a count of 2 is ${n(2 * g.lagPitch / 1000.0 / spec.visible.speed, 3)} s.")
        l()
        l("## Readings sheet")
        l()
        l("| section | what | reading |")
        l("|---|---|---|")
        for (d in spec.strandFans) l("| strands, row ${spec.dutyText(d)} | the longest span the ${mm(spec.feelerThick(1))} mm step slides under (and the ${mm(spec.feelerThick(2))} and ${mm(spec.feelerThick(0))} mm steps') | |")
        for (k in spec.pinDiametersUm.indices) l("| pin ${mm(spec.pinDiametersUm[k])} mm | the band the ${spec.holeText(k)} mm hole stops in (its layer time) | |")
        for (d in spec.finFans) l("| fins, row ${spec.dutyText(d)} | the first fin from the left that snaps before its post (its layer time), or none | |")
        l("| compass | the tabs whose tip holds the feeler off its posts (1-${g.tabs}) | |")
        l("| lag wall, lower half | notches from the switch to the change (off to full) | |")
        l("| lag wall, upper half | notches from the switch to the change (full to off) | |")
        l()
        l("### What the readings mean (decided before printing)")
        l()
        l("- **The strands**: a longer span under the feeler at a duty means the bead set before it could sag over that span. The span where the ${mm(spec.feelerThick(1))} mm step stops passing, by duty, is how fast the fan sets a bridge line on this machine. All rows the same: the fan does not change the set at these spans. No strand passes even at the shortest span: the beads stay soft longer than any span takes to lay (a lower bridge speed, or the fan, or a cooler nozzle).")
        l("- **The pins**: the band a card stops in is too short a layer time for a part that size; the next band up is the shortest that holds. Small pins stop in longer bands than large ones. Every card reaches its pedestal: even the shortest band holds; nothing to learn below it on this plate.")
        l("- **The fins**: the first fin that snaps marks the layer time past which the layer below is too cold to bond, at that row's duty. A higher duty snapping sooner: the fan costs bond strength. None snaps in any row: the bond holds at every layer time here. The leftmost snaps in every row: even the shortest band is too cold (this plate's shortest is ${spec.timeText(spec.finTimesS.first())}, set by how long the rest of its layer takes).")
        l("- **The compass**: the tabs that curl point away from the fan's duct (its shadow). None curls: the fan reaches all round. All curl: the overhang is past what the fan can hold anywhere.")
        l("- **The lag wall**: the switch on (lower half) and off (upper half) take their own times; a count of 0 means the change shows at the mark (under ${n(g.lagPitch / 1000.0 / spec.visible.speed, 3)} s).")
        l()
        l("### What to enter")
        l()
        l("Nothing yet: plymeld has no setting these readings write to. Keep the filled-in sheet with the filament and the plate.")
        return sb.toString()
    }

    /** The picture: the plate from above, drawn from plymeld's model of it: each section's outline and the gauges, each labeled. */
    fun picture(file: java.nio.file.Path, spec: ThermalSpec, plate: ThermalPlate) {
        System.setProperty("java.awt.headless", "true")
        val W = 1800; val H = 1400
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val b = spec.bounds
        val s = minOf((W - 120).toDouble() / b.w, (H - 200).toDouble() / b.d)
        fun px(x: Long) = 60 + (x - b.x0) * s
        fun py(y: Long) = 120 + (b.y1 - y) * s
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 24)
        gr.drawString("Thermal plate from above: five sections on one slab, the gauges in front; the chamfered corner (front-left) is the origin", 40, 44)
        gr.font = Font("SansSerif", Font.PLAIN, 16)
        gr.drawString("Each part's outline on its first layer above the slab (the labels and tallies on the slab in gray); the slab's back edge carries the lag wall's notches.", 40, 74)
        fun outline(ps: List<plymeld.geom.Polygon>, c: Color, w: Float) {
            gr.color = c; gr.stroke = BasicStroke(w)
            for (p in ps) { val path = Path2D.Double(); for (i in 0 until p.size) { if (i == 0) path.moveTo(px(p.x(i)), py(p.y(i))) else path.lineTo(px(p.x(i)), py(p.y(i))) }; path.closePath(); gr.draw(path) }
        }
        outline(plate.slabOutline(1), Color(40, 40, 45), 2f)
        outline(plate.feelerOutline(1), Color(120, 60, 20), 2f)
        outline(plate.cardOutline(), Color(120, 60, 20), 2f)
        for ((_, glyphs) in plate.slabLabels() + plate.cardLabels()) outline(glyphs, Color(150, 150, 158), 1f)
        // the parts on the first layer above the slab
        val first = plate.basePaths(ThermalGeometry.slabLayers).filter { !it.cell.startsWith("label") }
        val colors = mapOf("pier" to Color(38, 108, 205), "fin" to Color(148, 78, 190), "compass" to Color(18, 150, 165), "pedestal" to Color(215, 85, 35), "stair" to Color(215, 85, 35), "pin" to Color(215, 85, 35))
        for (p in first) {
            val c = colors.entries.firstOrNull { p.cell.startsWith(it.key) }?.value ?: Color(90, 90, 98)
            gr.color = c; gr.stroke = BasicStroke(1.5f)
            val m = p.xy.size / 2
            for (k in 0 until (if (p.closed) m else m - 1)) { val u = (k + 1) % m; gr.draw(Line2D.Double(px(p.xy[2 * k]), py(p.xy[2 * k + 1]), px(p.xy[2 * u]), py(p.xy[2 * u + 1]))) }
        }
        // the fins, the strands and the lag wall as lines
        gr.stroke = BasicStroke(2.5f)
        gr.color = Color(148, 78, 190)
        for (r in spec.finFans.indices) for (k in spec.finTimesS.indices) gr.draw(Line2D.Double(px(spec.finX(k)), py(spec.finY(r)), px(spec.finX(k) + ThermalGeometry.finWidth), py(spec.finY(r))))
        gr.color = Color(38, 108, 205)
        for (r in spec.strandFans.indices) for (k in spec.spansUm.indices) { val a = plate.strand(r, k); gr.draw(Line2D.Double(px(a[0]), py(a[1]), px(a[2]), py(a[3]))) }
        gr.color = Color(200, 30, 30)
        gr.draw(Line2D.Double(px(spec.lagX0), py(spec.lagOuterY), px(spec.lagX0 + ThermalGeometry.lagLength), py(spec.lagOuterY)))
        // the section names
        gr.font = Font("SansSerif", Font.BOLD, 20); gr.color = Color(25, 25, 30)
        fun name(t: String, x: Long, y: Long) { gr.drawString(t, px(x).toInt(), py(y).toInt()) }
        name("1. strands", spec.strands.x1 - 30_000, spec.strands.y1 + 1_000)
        name("3. fins", spec.fins.x0, spec.fins.y1 + 1_000)
        name("4. compass", spec.compass.x0, spec.compass.y1 + 1_000)
        name("5. lag wall", spec.lagX0 + 30_000, spec.slabD + 1_000)
        name("2. pins and band stair", spec.pins.x0, spec.slabD + 1_000)
        name("feeler", spec.feeler.x0, spec.feeler.y0 - 3_500)
        name("ring card", spec.card.x0, spec.card.y0 - 3_500)
        gr.color = Color(70, 70, 75); gr.font = Font("SansSerif", Font.PLAIN, 16)
        gr.drawString("The plate is ${n(b.w / 1000.0, 0)} x ${n(b.d / 1000.0, 0)} mm (the slab ${n(spec.slabW / 1000.0, 0)} x ${n(spec.slabD / 1000.0, 0)} mm); the front is toward you.", 40, H - 30)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
