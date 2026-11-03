package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each motion plate ([MotionPlate]): the question, what to do before printing, the conditions, a
 * labeled picture (the cross from above with every face's number, direction, and speed; the bands from the front with their
 * accelerations and grooves), how to grade (two grids, faces along X and faces along Y, a grade per band and speed), what each
 * kind of result means (decided before printing), and how the readings become the machine's visible speed and acceleration.
 */
object MotionInstructions {
    /**
     * What the page needs beyond [PlateInstructions.Info]: the machine's key (where the values go), why the fastest speed and the
     * top acceleration are what they are (which limit binds), and the line about the filament's maximum flow when it is still a
     * placeholder (null when it is measured).
     */
    class Notes(val machineKey: String, val topSpeedWhy: String, val topAccelWhy: String, val maxFlowPlaceholder: String? = null)

    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: MotionSpec, plate: MotionPlate, info: PlateInstructions.Info, notes: Notes) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, info, notes, png.fileName.toString()))
    }

    fun markdown(spec: MotionSpec, info: PlateInstructions.Info, notes: Notes, pngName: String): String {
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        val g = MotionGeometry
        val grades = GRADES
        val top = spec.bands - 1
        val (cx, cy) = spec.facesAt(g.speeds - 1)
        l("# Motion plate: ${info.machine}, with ${info.filament} on tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating and any levelling come on top)" } ?: "") + ".")
        l("**One piece**: a hollow tower shaped like a plus sign, ${n(3 * g.arm / 1000.0, 0)} x ${n(3 * g.arm / 1000.0, 0)} mm and ${n((spec.topZ - spec.slabTop) / 1000.0, 1)} mm tall on a ${n(spec.slabSize / 1000.0, 0)} mm slab. " +
            "Its **twelve faces** each print at one of **six speeds** (one face along X and one along Y for each), and it rises in **${spec.bands} bands**, one **acceleration** each. " +
            "**The question: which speed and acceleration lay an outer wall without ringing** (ripples running on from a corner), in each direction. " +
            "The answer becomes this machine's visible speed and acceleration (`hardware.machine.${notes.machineKey}.motion.visible.speed`, `.visible.acceleration`): what plymeld prints outer walls and top and bottom surfaces at.")
        l()
        notes.maxFlowPlaceholder?.let { l("**Recommended: run the melt plate first.** $it"); l() }
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
            info.levelSkipped -> "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling yourself first."
            else -> "plymeld cannot level this printer from the g-code (no levelling command is known for it): turn on the printer's own levelling when you start the print, or run it from the printer's menu first."
        })
        l("2. **Leave the printer's own motion settings as you print with them**: its vibration compensation (input shaping) on or off, its acceleration and corner-speed limits. The plate measures the machine as it is set; change one and the readings no longer hold. Note whether it ran any vibration or resonance calibration of its own before this print.")
        l("3. **Check the machine is sound**: belts tensioned, nothing loose on the head or the bed, the printer standing firm. A loose part rings at every speed and reads as the machine's limit.")
        l("4. **Clean the bed**${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C and stays there.")
        l("5. **Load `${info.filament}` on tool ${info.tool + 1}.** A dark, glossy filament shows ripples best; any works.")
        l("6. **During the print**: the tower speeds up and slows down at every corner, harder band by band; the top band prints at the machine's limits and may be loud. Where a layer would be quicker than the filament's minimum layer time the head parks in front of the plate and waits.")
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| machine | ${info.machine} (`${notes.machineKey}`) |")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| nozzle temperature | ${spec.tempC} C throughout, waited for before the first line |")
        l("| layer height | ${n(spec.layerUm / 1000.0, 2)} mm; the slab's first layer ${n(spec.firstLayerUm / 1000.0, 2)} mm; ${spec.layers} layers (the slab ${g.slabLayers}, the tower ${spec.towerLayers}), the top at z ${n(spec.topZ / 1000.0, 2)} mm |")
        l("| line width | outer wall ${n(spec.widthUm / 1000.0, 2)} mm, inner wall ${n(spec.innerWidthUm / 1000.0, 2)} mm, the slab's first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C, constant${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | off on the first layer, ${n(spec.fanPct, 0)} % from the second |")
        l("| the slab | its first layer at ${n(spec.adhesion.speed, 0)} mm/s, ${n(spec.adhesion.accel, 0)} mm/s2; its layers above at ${n(spec.frame.speed, 0)} mm/s, ${n(spec.frame.accel, 0)} mm/s2 (the machine's print speed and acceleration, held to the filament's maximum flow) |")
        l("| the speeds | ${spec.speeds.joinToString(", ") { n(it, 0) }} mm/s. The fastest: ${notes.topSpeedWhy}. The others: a sixth of it, two sixths ... rounded down to 5 mm/s (unless `--motion-speeds` gave them) |")
        l("| the accelerations | ${spec.accels.joinToString(", ") { n(it, 0) }} mm/s2, band 1 at the bottom. The top: ${notes.topAccelWhy}. The others: half of the band above (unless `--motion-accels` gave them) |")
        l("| **the control** | faces $cx and $cy of band ${top + 1}: ${n(spec.speeds.last(), 0)} mm/s at ${n(spec.accels.last(), 0)} mm/s2, the machine flat out |")
        l("| both walls of the tower | at their face's speed (the inner wall held to ${n(spec.innerCap, 0)} mm/s, the maximum flow at its width) and their band's acceleration, the inner wall first; both seams on face 12, ${n(g.seamAt * 100, 0)} % of the way along it |")
        l("| retraction | ${n(spec.retractMm, 2)} mm, lift ${n(spec.liftUm / 1000.0, 2)} mm |")
        l()
        l("## The plate")
        l()
        l("![The cross from above with every face's number, direction, and speed; the bands from the front with their accelerations]($pngName)")
        l()
        l("- **Which way round**: **notches in the slab's edge number the arms** (each notch ${n(g.markWidth / 1000.0, 1)} x ${n(g.markDepth / 1000.0, 1)} mm, cut through the slab in front of an arm's end): **1 the front arm, 2 the right, 3 the back, 4 the left** (counter-clockwise seen from above, like the faces). Set the plate down with the single notch toward you. " +
            "The arm with n notches ends in face 3n - 2 (${(1..g.arms).joinToString(", ") { "${g.endFace(it)}" }}). The slab's corner at the front arm's front-left is chamfered too.")
        l("- **The faces** are numbered counter-clockwise seen from above, **face 1 the front arm's front face** (the edge behind the single notch, beside the chamfer). The odd faces run **along X** (left to right as you look at the front), the even faces **along Y** (front to back). Each face starts at a sharp corner: the jolt there is what sets off ringing, so the ripples (if any) start at the face's beginning, in the direction it printed, and fade along it. The picture's arrows show which end that is.")
        l("- **The bands**: band 1 at the bottom, just above the slab. A **groove** (the walls set in ${n(spec.grooveUm / 1000.0, 2)} mm for ${g.grooveLayers} layers) marks the start of every band above the first: count the grooves from the slab and add one. Each band is ${n(spec.bandLayers * spec.layerUm / 1000.0, 1)} mm tall.")
        l("- **No diagonal faces.** A wall running at 45 degrees wobbles in X and Y at once, each by its own resonance, so it rings no worse than the worse of the two directions read here, as long as the machine's two belts (on a CoreXY machine, each motor drives a diagonal) are alike. A machine whose diagonal walls ring worse than both has unequal belts: a fault to fix, not a speed to set.")
        l("- **The seam** (where each layer's loop starts and ends) is on face 12, ${n(g.seamAt * g.arm / 1000.0, 0)} mm from its start, near the chamfered corner: a vertical line there is the seam, not ringing.")
        l()
        l("| face | where | runs along | shows the head's wobble in | speed | mm/s |")
        l("|---|---|---|---|---|---|")
        for (k in 1..g.faces) l("| $k | ${g.where(k)} | ${if (g.alongX(k)) "X" else "Y"} | ${if (g.alongX(k)) "Y" else "X"} | ${g.FACE_SPEED[k - 1] + 1} | ${n(spec.faceSpeed(k), 0)} |")
        l()
        l("| band | grooves below it | height (mm) | acceleration (mm/s2) | fastest a face gets (mm/s) |")
        l("|---|---|---|---|---|")
        for (b in spec.bands - 1 downTo 0) l("| ${b + 1} | $b | ${n(spec.bandFrom(b) / 1000.0, 1)}-${n(spec.bandTo(b) / 1000.0, 1)} | ${n(spec.accels[b], 0)} | ${n(spec.reach(b), 0)}${if (spec.reach(b) < spec.speeds.last()) " (the faster speeds are not reached)" else ""} |")
        l()
        l("A face of ${n(spec.shortestFaceUm / 1000.0, 1)} mm, speeding up from its corner and slowing for the next, gets at most to sqrt(acceleration x length) (the last column; the printer's own corner speed lifts it a little, a firmware rule such as a minimum cruise share lowers it). A face whose speed is above that never reaches it in that band: grade it all the same; plymeld credits it only with the speed it reached.")
        l()
        l("## How to grade")
        l()
        l("Hold the plate in good light with a lamp **low and to one side**, so it rakes along the face you are looking at. On each face look at the **first third after its corner** (where it starts, the arrow's tail in the picture): ringing is a row of evenly spaced ripples there, fading along the face, the same on every layer of the band, so it reads as fine vertical stripes. Do not grade the seam (face 12, near the chamfer), the grooves, or a layer line that runs round the whole tower. Grade each **band and speed** twice: on its face **along X** and on its face **along Y** (the table above says which faces). Use the seam plate's words (its first three: ringing that draws the eye from across the room is not seen on these faces), by how far away the ripples show:")
        l()
        for (gr in grades) l("${grades.indexOf(gr)}. **$gr**: ${RIPPLE_MEANING.getValue(gr)}.")
        l()
        l("Two grids to fill, one per direction, a row per band (the top band at the top, as the tower stands) and a column per speed. The control's cells are **band ${top + 1}, speed ${g.speeds}**.")
        l()
        for ((title, alongX) in listOf("Faces along X (they show the head's wobble in Y)" to true, "Faces along Y (they show the head's wobble in X)" to false)) {
            l("**$title**")
            l()
            l("| band (mm/s2) \\ speed | " + (0 until g.speeds).joinToString(" | ") { j -> val (fx, fy) = spec.facesAt(j); "${n(spec.speeds[j], 0)} (face ${if (alongX) fx else fy})" } + " |")
            l("|---|" + "---|".repeat(g.speeds))
            for (b in spec.bands - 1 downTo 0) l("| ${b + 1} (${n(spec.accels[b], 0)}) |" + " |".repeat(g.speeds))
            l()
        }
        l("If you can, also measure the **ripple spacing** on a face that rings plainly: the distance across ten ripples, over ten. Spacing times frequency is speed, so a face at v mm/s whose ripples are D mm apart rings at v / D Hz; the same frequency on every face along one direction is that axis's resonance (what a firmware's vibration compensation is tuned to). Write it in the notes.")
        l()
        l("### What the results mean (decided before printing)")
        l()
        l("A cell is **clean** when its grade is ${grades.take(grades.indexOf(DEFAULT_CLEAN) + 1).joinToString(" or ")} (`plymeld calibrate motion values` takes `--clean` to change that). For each band, plymeld takes the fastest speed whose cell and every slower cell in that band are clean in **both** directions (the speed credited is what the face reached); of those pairs it writes the one that lays a face of the plate's length (${n(spec.shortestFaceUm / 1000.0, 1)} mm) quickest.")
        l()
        l("| what you see | what it means | what plymeld writes |")
        l("|---|---|---|")
        l("| Ripples grow band by band and speed by speed; the lower bands' slower faces are clean, the control rings | **The expected result.** The clean cells mark what this machine lays without ringing | The quickest clean pair: `visible.speed` and `visible.acceleration` for this machine |")
        l("| The control (band ${top + 1}, speed ${g.speeds}) is clean in both directions | Either the machine lays clean walls flat out (its vibration compensation works), or the light does not show ripples. Check with a lamp raking along the faces before believing it | The machine's limits: ${n(spec.speeds.last(), 0)} mm/s, ${n(spec.accels.last(), 0)} mm/s2 |")
        l("| One direction rings and the other is clean | That axis binds: its resonance is lower or less damped (on a CoreXY machine, check that axis's belt; a firmware's vibration compensation may be tuned for one axis only) | The pair clean in both, so the worse axis decides; the report says which axis bound each band |")
        l("| Band 1's slowest faces ring (the negative control) | Not the visible walls' motion: something loose, the bed or frame moving, the tower itself flexing, or ripples that are not ringing (below) | Nothing (no clean pair): fix the machine, then print again |")
        l("| Ripples evenly spaced in mm whatever the face's speed (e.g. 2 mm apart on every face) | Not ringing (ringing's spacing grows with speed): a belt's teeth, a pulley, or the extruder's gear pattern | Grade them as what you see; note them: the values cannot fix them |")
        l("| The fast faces look rough, dull, or gappy rather than rippled, worst at the fastest | The filament's melt rate, not motion (the faster faces push it hardest): `plymeld calibrate melt` | Grade only the ripples; the speeds are held to the maximum flow as configured |")
        l("| A higher band cleaner than a lower one at the same speed | A resonance at one acceleration, or a grading slip: look again | Each band is judged on its own; the report names the band |")
        l("| A face whose speed it never reached grades like a slower one | Expected: it ran at the speed it reached | Credited only with the speed it reached |")
        l("| Every cell rings, even band 1 | The range is too high for this machine, or the machine needs attention | Nothing: print again with lower `--motion-accels` and `--motion-speeds` |")
        l("| Every cell is clean | The range is too low to find the limit (only if the control was not the machine's limit: `--motion-accels` / `--motion-speeds` given) | The quickest pair on the plate: a lower bound; print again higher |")
        l()
        l("### What to enter")
        l()
        l("`plymeld calibrate motion readings ${info.gcodeName} -o readings.toml` writes the sheet to grade: the two grids, every cell `?`. Replace every `?` with ${grades.joinToString(", ")}, then " +
            "`plymeld calibrate motion values readings.toml -o motion.toml` writes `hardware.machine.${notes.machineKey}.motion.visible.speed` and `.visible.acceleration`, with where they came from (the plate, the dates, every band's clean speed in each direction, the control's grades). " +
            "Add the file with `-cf`. A visible speed or acceleration set in the job (`plan.mechanism.motion.visible.speed`, in a config file of yours) still wins over the machine's.")
        return sb.toString()
    }

    /** What each grade means for ripples. The words are the seam plate's (one idea, how far away a blemish shows, one set of words). */
    val RIPPLE_MEANING = mapOf(
        "hidden" to "no ripples, even close up with the lamp raking along the face",
        "faint" to "ripples you find only close up, knowing where to look, in the raking light",
        "plain" to "ripples you see at arm's length")

    /**
     * The motion plate's grades: the seam plate's words without glaring (ringing is
     * not seen across a room).
     */
    val GRADES: List<String> = SeamInstructions.GRADES.filter { it != "glaring" }

    /** The worst grade that counts as clean unless `--clean` says otherwise: hidden, since a visible wall at maximum quality
     *  should show no ripples (faint ripples are not clean). */
    const val DEFAULT_CLEAN = "hidden"

    /** The picture: the cross from above (each face's number, speed, and direction, X faces blue, Y faces orange), and the bands from the front. */
    fun picture(file: java.nio.file.Path, spec: MotionSpec, plate: MotionPlate) {
        System.setProperty("java.awt.headless", "true")
        val g = MotionGeometry
        val W = 1800; val H = 1030
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val gr = img.createGraphics()
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.color = Color(250, 250, 252); gr.fillRect(0, 0, W, H)
        val blue = Color(30, 90, 200); val orange = Color(220, 110, 20)
        gr.color = Color.BLACK; gr.font = Font("SansSerif", Font.BOLD, 24)
        gr.drawString("Motion plate: one cross-shaped tower. Left: from above (front at the bottom). Right: from the front, the bands.", 30, 40)
        // ---- the top view
        val ox = spec.originXUm; val oy = spec.originYUm
        val size = spec.slabSize
        val s = 780.0 / (size + 24_000)
        fun px(x: Long) = 40 + (x - ox + 12_000) * s
        fun py(y: Long) = 70 + 780 - (y - oy + 12_000) * s
        val o = plate.outline()
        val slab = Path2D.Double().also { p -> for (i in 0 until o.size) { if (i == 0) p.moveTo(px(ox + o.x(i)), py(oy + o.y(i))) else p.lineTo(px(ox + o.x(i)), py(oy + o.y(i))) }; p.closePath() }
        gr.color = Color(228, 228, 232); gr.fill(slab)
        gr.color = Color(120, 120, 125); gr.stroke = BasicStroke(2f); gr.draw(slab)
        // the arm marks: each arm's number beside its notches, outside the slab
        gr.color = Color(60, 60, 65); gr.font = Font("SansSerif", Font.BOLD, 16)
        run {
            val a = g.arm; val m = g.margin; val sz = spec.slabSize; val at = m + g.markAt; val off = 6_000L
            val spots = listOf(a + at to -off, sz + off to a + at, 2 * a + 2 * m - at to sz + off, -off to 2 * a + 2 * m - at)
            for ((i, xy) in spots.withIndex()) {
                val t = "arm ${i + 1}"
                gr.drawString(t, (px(ox + xy.first) - gr.fontMetrics.stringWidth(t) / 2).toInt(), (py(oy + xy.second) + 6).toInt())
            }
        }
        val v = spec.inset(spec.widthUm / 2)
        for (k in 1..g.faces) {
            val a = k - 1; val b = k % g.faces
            val x0 = px(v[2 * a]); val y0 = py(v[2 * a + 1]); val x1 = px(v[2 * b]); val y1 = py(v[2 * b + 1])
            gr.color = if (g.alongX(k)) blue else orange
            gr.stroke = BasicStroke(5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            gr.draw(Line2D.Double(x0, y0, x1, y1))
            // the arrow: from the face's start (where ripples begin), 40 % of the way along, drawn just inside the face
            val dx = x1 - x0; val dy = y1 - y0; val len = Math.hypot(dx, dy); val ux = dx / len; val uy = dy / len
            val nx = uy; val ny = -ux     // inward on screen (counter-clockwise on the bed is clockwise on screen, y flipped)
            val off = 16.0
            val ax0 = x0 + ux * 12 + nx * off; val ay0 = y0 + uy * 12 + ny * off
            val ax1 = x0 + ux * len * 0.4 + nx * off; val ay1 = y0 + uy * len * 0.4 + ny * off
            gr.stroke = BasicStroke(2.5f); gr.draw(Line2D.Double(ax0, ay0, ax1, ay1))
            val head = Path2D.Double(); head.moveTo(ax1, ay1); head.lineTo(ax1 - ux * 12 + nx * 6, ay1 - uy * 12 + ny * 6); head.lineTo(ax1 - ux * 12 - nx * 6, ay1 - uy * 12 - ny * 6); head.closePath(); gr.fill(head)
            // the label inside the face: number, speed
            val lx = x0 + ux * len * 0.68 + nx * 44; val ly = y0 + uy * len * 0.68 + ny * 44
            gr.font = Font("SansSerif", Font.BOLD, 20)
            val t1 = "$k"; gr.drawString(t1, (lx - gr.fontMetrics.stringWidth(t1) / 2).toInt(), (ly - 2).toInt())
            gr.font = Font("SansSerif", Font.PLAIN, 15)
            val t2 = "${n(spec.faceSpeed(k), 0)} mm/s"; gr.drawString(t2, (lx - gr.fontMetrics.stringWidth(t2) / 2).toInt(), (ly + 16).toInt())
        }
        // the seam
        val seam = spec.loop(spec.bandLayers, false)
        gr.color = Color(200, 30, 30); val r = 7.0
        gr.fill(Ellipse2D.Double(px(seam[0]) - r, py(seam[1]) - r, 2 * r, 2 * r))
        gr.font = Font("SansSerif", Font.PLAIN, 14)
        gr.drawString("seam", (px(seam[0]) - 48).toInt(), (py(seam[1]) + 5).toInt())
        // the chamfer note and the legend
        gr.color = Color(60, 60, 65); gr.font = Font("SansSerif", Font.PLAIN, 15); gr.stroke = BasicStroke(1.2f)
        val chx = px(ox + g.arm + g.chamfer / 4); val chy = py(oy + g.chamfer / 4)
        gr.draw(Line2D.Double(chx, chy, chx - 30, chy + 40))
        val cht = "chamfered corner"
        gr.drawString(cht, (chx - 34 - gr.fontMetrics.stringWidth(cht)).toInt(), (chy + 56).toInt())
        gr.color = blue; gr.fillRect(50, 905, 30, 6); gr.color = Color.BLACK; gr.drawString("faces along X: read for the head's wobble in Y", 90, 913)
        gr.color = orange; gr.fillRect(50, 935, 30, 6); gr.color = Color.BLACK; gr.drawString("faces along Y: read for the head's wobble in X", 90, 943)
        gr.drawString("arrows: the print direction, from the corner where ripples start", 50, 973)
        gr.drawString("notches in the slab's edge number the arms: 1 front, 2 right, 3 back, 4 left (counter-clockwise from above)", 50, 1003)
        // ---- the front view: the bands
        val fx0 = 980.0; val fx1 = 1500.0; val fy1 = 900.0; val fy0 = 110.0
        val zs = (spec.topZ).toDouble()
        fun fz(z: Long) = fy1 - (fy1 - fy0) * z / zs
        gr.color = Color(200, 200, 205); gr.fill(java.awt.geom.Rectangle2D.Double(fx0 - 30, fz(spec.slabTop), fx1 - fx0 + 60, fz(0) - fz(spec.slabTop)))
        for (b in 0 until spec.bands) {
            val shade = 235 - (180 * b / maxOf(1, spec.bands - 1))
            gr.color = Color(shade, shade, 255)
            gr.fill(java.awt.geom.Rectangle2D.Double(fx0, fz(spec.bandTo(b)), fx1 - fx0, fz(spec.bandFrom(b)) - fz(spec.bandTo(b))))
            gr.color = Color.BLACK; gr.stroke = BasicStroke(1.5f)
            gr.draw(java.awt.geom.Rectangle2D.Double(fx0, fz(spec.bandTo(b)), fx1 - fx0, fz(spec.bandFrom(b)) - fz(spec.bandTo(b))))
            if (b > 0) {
                // the groove: drawn as a notch in each side
                val gz0 = fz(spec.bandFrom(b) + g.grooveLayers * spec.layerUm); val gz1 = fz(spec.bandFrom(b))
                gr.color = Color(40, 40, 45)
                gr.fill(java.awt.geom.Rectangle2D.Double(fx0 - 1, gz0, 8.0, gz1 - gz0)); gr.fill(java.awt.geom.Rectangle2D.Double(fx1 - 7, gz0, 8.0, gz1 - gz0))
            }
            val mid = (fz(spec.bandFrom(b)) + fz(spec.bandTo(b))) / 2
            gr.font = Font("SansSerif", Font.BOLD, 18); gr.color = Color.BLACK
            gr.drawString("band ${b + 1}: ${n(spec.accels[b], 0)} mm/s2" + (if (b == spec.bands - 1) "  (the control's)" else ""), (fx0 + 20).toInt(), (mid + 6).toInt())
            gr.font = Font("SansSerif", Font.PLAIN, 14)
            gr.drawString("z ${n(spec.bandFrom(b) / 1000.0, 1)}-${n(spec.bandTo(b) / 1000.0, 1)} mm", (fx1 + 12).toInt(), (mid + 5).toInt())
        }
        gr.font = Font("SansSerif", Font.PLAIN, 15); gr.color = Color(60, 60, 65)
        gr.drawString("slab", (fx0 - 20).toInt(), (fz(0) - 3).toInt())
        gr.drawString("dark notches: the grooves that start each band above the first", fx0.toInt() - 30, 940)
        gr.drawString("the control: faces ${spec.facesAt(g.speeds - 1).first} and ${spec.facesAt(g.speeds - 1).second} (${n(spec.speeds.last(), 0)} mm/s) in band ${spec.bands}", fx0.toInt() - 30, 965)
        gr.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
