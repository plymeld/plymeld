package plymeld.core.calibrate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * The page that goes with each calibration plate (Markdown and pictures, encouraging levelling
 * and any other calibration before printing): what to do before printing, the conditions the plate
 * prints with, a labeled picture, how to read each band, a table to fill in, and what each kind of
 * result means, written down before the print.
 */
object PlateInstructions {
    /** What the page says about the print that the specification does not hold. */
    class Info(
        val filament: String, val machine: String, val tool: Int, val version: String, val gcodeName: String,
        val predictedMin: Double?, val pressureAdvance: String, val bedTempC: Int, val firstLayerSpeed: Double,
        val frameSpeed: Double, val retractMm: Double, val date: java.time.LocalDate = java.time.LocalDate.now(),
        /** The build plate, whether the file levels the bed (hardware.machine.gcode.level), whether that was turned off, and the temperature warnings. */
        val plate: String = "", val levels: Boolean = false, val levelSkipped: Boolean = false, val warnings: List<String> = emptyList(),
        /** Each family's predicted cost, and last what the families share ([PartCost.part] null); empty = not given. */
        val costs: List<PartCost> = emptyList(),
        /**
         * The filament's maximum flow as the config has it: its key (e.g. `hardware.filament.pla.melt.flow.max`), its value
         * and where the value comes from; and whether that is still plymeld's bundled placeholder (never measured). Null:
         * not given.
         */
        val maxFlowKey: String? = null, val maxFlowNow: String? = null, val maxFlowPlaceholder: Boolean = false,
        /**
         * Where a melt reading from this plate goes: the filament's value for the machine it printed on (e.g.
         * `hardware.filament.pla.machine.q2.melt.flow.max`; the melt rate depends on the hot end). Null: not given.
         */
        val maxFlowEnterKey: String? = null,
        /** The filament's own print temperature (temperature.print), for which melt reading to enter; null = none. */
        val printTempC: Int? = null,
        /**
         * The frame's acceleration ([frameSpeed] its speed), the parts of this plate that are frame (e.g. "the post", "the
         * bases above their first layer") and why it prints at those values, which limit binds (now the machine's
         * print speed and acceleration, held to the filament's maximum flow). NaN / empty / null: not given.
         */
        val frameAccel: Double = Double.NaN, val frameParts: List<String> = emptyList(), val frameWhy: String? = null,
    )

    /**
     * What a part of the plate costs by plymeld's prediction, attributed by
     * the g-code's cells: its own lines and the travels into them. [part] null: what no part owns (the layer changes, the
     * cooling pause, the temperature waits, the start and end g-code). [timeS] null: the time is not predicted.
     */
    class PartCost(val part: Part?, val timeS: Double?, val filamentMm: Double, val baseTimeS: Double? = null, val baseFilamentMm: Double = 0.0)

    /** The item a family's base notch stands in front of ([CalibrationPlate.notchX]), in the page's words. */
    fun firstItem(p: Part): String = when (p) {
        Part.MELT -> "the FAST surface (the machine's print speed)"; Part.OVERHANGS -> "ledge 1 (the lowest share)"; Part.BRIDGES -> "span 1 (the shortest)"
        Part.FIT -> "hole 1 (the smallest)"; Part.LINE_WIDTH -> "tube 1 (the narrowest line)"
        Part.STRINGING -> "post 1 (hop 1 starts there)"; Part.POST -> "none"
    }

    /**
     * Why the melt test comes first, alone (later it can join other tests as a litmus test): an accurate melt
     * rate drives so much of the rest that the other tests are not reliable without it. For the page and the command's help and output.
     */
    const val MELT_WHY = "an accurate melt rate drives so much of the rest, and the other tests are not reliable until it is measured (they print within " +
        "the filament's maximum flow, and a wrong one shows up in them as gaps, weak overhangs, or sagging bridges that are not theirs)"
    /** The plates in the dependency order: temperature (coarse), then the melt rate, then the rest. */
    const val MELT_FIRST = "Run the melt plate first (`calibrate melt`), then the motion plate (`calibrate motion`) and the filament plate (`calibrate filament`): " + MELT_WHY + "."

    /** Which plate [spec] is: "melt" (the melt test alone) or "filament" (the motion plate is [MotionPlate]'s). */
    fun kindOf(spec: CalibrationSpec): String = if (spec.melt != null) "melt" else "filament"

    /** Where part [p] stands on the plate, seen from above with its front towards you, by thirds of the plate: "front left", "the middle", "back right". */
    fun where(plate: CalibrationPlate, p: Part): String {
        val b = plate.bounds(); val f = plate.footprint(p)
        val fx = ((f[0] + f[2]) / 2.0 - b[0]) / maxOf(1L, b[2] - b[0]); val fy = ((f[1] + f[3]) / 2.0 - b[1]) / maxOf(1L, b[3] - b[1])
        val row = when { fy < 1 / 3.0 -> "front"; fy > 2 / 3.0 -> "back"; else -> "middle" }
        val col = when { fx < 1 / 3.0 -> "left"; fx > 2 / 3.0 -> "right"; else -> "" }
        return if (row == "middle" && col.isEmpty()) "the middle" else if (col.isEmpty()) "$row center" else "$row $col"
    }

    /**
     * The share of a [lenMm] line a head at [accel] spends at full [speed] (its ends at standstill: a 180 degree turn at
     * each): 1 - v² / (a L), none if it never reaches the speed.
     */
    fun fullSpeedShare(speed: Double, accel: Double, lenMm: Double): Double = maxOf(0.0, 1 - speed * speed / (accel * lenMm))

    /** The melt test's fan, in the page's words: the same in every band (the filament's own, or off), or by band. */
    fun meltFanText(spec: CalibrationSpec): String {
        val fans = spec.bands.map { it.fanPct }.distinct()
        return when {
            fans.size > 1 -> "the part fan by band (the bands table)"
            fans[0] == 0.0 -> "the part fan off in every band (`--melt-fan off`)"
            else -> "the part fan at ${n(fans[0], 0)} % in every band, the filament's own (cooling.fan; `--melt-fan off` prints it with the fan off)"
        }
    }

    /**
     * The melt test's part of the page (the strips by thickness on a solid floor): how to read
     * it, per band a table of its strips' thicknesses and flows on each surface to fill in, what the
     * results mean (decided before printing), and which setting to enter from the reading.
     */
    private fun meltReading(l: (String) -> Unit, spec: CalibrationSpec, info: Info) {
        val m = spec.melt!!
        val len = PlateGeometry.meltLineLen / 1000.0
        val sec = m.speeds.map { len / it }
        val last = m.count - 1
        val lo = spec.stripFlow(0, 2); val hi = spec.stripFlow(last, 0)
        l("## How to read the melt test")
        l("")
        l("Every step's top is its own band (front to back and low to high: band 1 to band ${spec.bands.size}). Each strip is **one layer of lines laid on a solid floor**, so a gap shows **the floor through it: the same filament, the same color**. " +
            "Light it so the floor shows: **raking light** low from one side (a gap casts a shadow, a closed strip stays even), or **from behind** where the filament lets light through (a gap glows: the floor is one layer, the strip one more). A magnifier helps. On each step, for **each strip** on **each surface**:")
        l("")
        l("1. **Read the middle of the strip's lines.** Their ends are where the head brakes and turns (and they lie on the floor's walls), so they get more time than the rest.")
        l("2. A strip is **clean** if its lines have joined into one closed surface. It has **gaps** if its lines are thin, broken, or apart, so you see the floor between them.")
        l("3. **Gaps on FAST or MID, but not at the same thickness on SLOW** = past the melt limit at that flow: the filament did not melt fast enough. SLOW is the control: the same thicknesses at a quarter of FAST's flow.")
        l("4. **Gaps at the same thickness on all three surfaces** (and in every band) = that thickness itself (too thin, or too thick for the nozzle to lay as one line), not the melt.")
        l("5. **Gaps that start partway across a strip**, not at its front line: the hot end ran on its stored heat until it ran out. Read where they start in time: each line takes about ${n(sec[0], 2)} s on FAST, ${n(sec[1], 2)} s on MID, and ${n(sec[2], 2)} s on SLOW, from the strip's front line (a strip of ${m.lines} lines: ${n(m.lines * sec[0], 1)}, ${n(m.lines * sec[1], 1)}, and ${n(m.lines * sec[2], 1)} s). What the filament holds is the flow after the gaps start, not the clean start.")
        l("6. **Cross-check**: strips with about the same flow on different surfaces (a thick MID strip and a thin FAST strip, say) should read alike. The tables give each strip's flow.")
        l("7. For each band, write down the **highest flow that came out clean** and the **lowest flow with melt gaps** (step 3): the melt limit at that temperature lies between them. If every strip is clean, write **\"at least ${n(hi, 1)}\"**; if even the lowest flow (${n(lo, 1)} mm3/s, SLOW's thinnest) has melt gaps, **\"below ${n(lo, 1)}\"**.")
        l("")
        l("Every strip is ${n(m.widthUm / 1000.0, 2)} mm wide (the plate's line width); what changes from strip to strip is its **thickness**, ${m.heightsUm.joinToString { n(it / 1000.0, 2) }} mm from the front (the tool's thickest layer, `layer.height.max`, " +
            "down a tenth of the bore at a time), so its flow. Flows are the line's speed times its bead (the bead model at that width and thickness) times the flow ratio ${n(spec.flowRatio, 3)}; every band has the same strips, so the same flows. " +
            (if (m.machineHeld) "FAST is the machine's print speed (${n(m.machineSpeed, 0)} mm/s, hardware.machine.<key>.motion.print.speed): the speed that would center FAST on the filament's expected maximum flow (${n(m.centerFlow, 1)} mm3/s) is faster than the machine runs, so **the top of the range is the machine's limit, not the filament's**."
            else "FAST is set so its middle thickness runs at the filament's expected maximum flow, ${n(m.centerFlow, 1)} mm3/s at ${spec.bands[0].tempC} C (${info.maxFlowKey?.let { "`$it`" } ?: "its melt.flow.max"}, " +
                (if (info.maxFlowPlaceholder) "plymeld's bundled placeholder, not a measurement" else "the config's value") + "): its strips bracket it, about half to 1.4 times it, MID's and SLOW's lie below it. One FAST speed for every band, so each strip's flow is the same at every temperature (plymeld's choice)."))
        l("")
        for (b in spec.bands) {
            val floorTop = spec.meltReadLayer(b.index) * spec.layerUm / 1000.0
            l("### Band ${b.index + 1}: ${b.tempC} C, fan ${n(b.fanPct, 0)} % (step ${b.index + 1} from the front, its floor's top at z ${n(floorTop, 2)} mm)")
            l("")
            l("| strip (from the front) | thickness (mm) | z (mm) | FAST flow (mm3/s) | gaps? | MID flow (mm3/s) | gaps? | SLOW flow (mm3/s) | gaps? |")
            l("|---|---|---|---|---|---|---|---|---|")
            for (k in 0 until m.count) l("| ${k + 1} | ${n(m.heightsUm[k] / 1000.0, 2)} | ${n(floorTop + m.heightsUm[k] / 1000.0, 2)} | ${n(spec.stripFlow(k, 0), 1)} |  | ${n(spec.stripFlow(k, 1), 1)} |  | ${n(spec.stripFlow(k, 2), 1)} |  |")
            l("")
            l("Highest clean flow: ______ mm3/s. Lowest flow with melt gaps: ______ mm3/s. Notes:")
            l("")
        }
        l("### What the melt results mean (decided before printing)")
        l("")
        l("- **The limit rises with temperature, step by step** (the expected result): it is this filament's maximum flow at each temperature.")
        l("- **Every strip is clean on every surface of a band**: the filament melts at least ${n(hi, 1)} mm3/s there. A bound, not a value: the next plate for this filament should ask for more.")
        l("- **Melt gaps from FAST's first strip on, in every band**: even its lowest flow (${n(spec.stripFlow(0, 0), 1)} mm3/s) is past the limit: read the limit on MID and SLOW, whose flows are lower.")
        l("- **Gaps at the same thickness on all three surfaces, in every band**: that thickness itself (the thinnest may be too thin to close up, the thickest too thick for the nozzle's flat to spread), not the melt; the melt reading comes from the thicknesses that are clean on SLOW.")
        l("- **Gaps that start partway across FAST's strips**: the hot end holds a higher flow for a moment than it keeps up; the limit it keeps up is lower. Note how many seconds in (step 5): it matters for long, fast lines.")
        l("- **Strips at about the same flow read differently on two surfaces**: the speed itself matters (pressure advance, the extruder's grip, the acceleration), not only the melt. Note it; the slower surface's reading is the one to trust for the melt.")
        l("- **The extruder clicked or ground** at some strips: the extruder's grip gave out at that flow, which may be below the melt limit; note at which band and strip.")
        l("- **Bad everywhere, even SLOW's narrowest strip**: not a melt limit. Look at the flow ratio, a partial clog, wet filament, the first layer, or pressure advance first.")
        l("")
        l("### What to enter")
        l("")
        val near = info.printTempC?.let { c -> spec.bands.minBy { Math.abs(it.tempC - c) } }
        l("The filament's maximum flow is one setting, ${info.maxFlowKey?.let { "`$it`" } ?: "its `melt.flow.max`"}${info.maxFlowNow?.let { " (now $it)" } ?: ""}: plymeld holds every move's flow below it. " +
            (info.maxFlowEnterKey?.let { "Enter this plate's reading for the machine it printed on, `$it`: the melt rate depends on the hot end as much as on the plastic, so it applies on this machine only, " +
                "and the filament's own `melt.flow.max` stays the default elsewhere (a tool that melts differently takes its own, `tool.<tool key>.melt.flow.max` in place of `machine.<machine key>.melt.flow.max`, over the machine's). " } ?: "") +
            "It is one value at a time, so enter **the limit read at the band nearest the temperature you print this filament at**" +
            (if (near != null) " (its temperature.print, ${info.printTempC} C: band ${near.index + 1}, ${near.tempC} C)" else "") +
            ": the highest clean flow, or a value between it and the lowest flow with melt gaps (how much margin to leave is your call). If you print at another temperature, use that band's reading, " +
            "or write a formula of the print temperature (`filament.temperature.print`) through the bands' readings, such as a straight line held at the hottest band's reading; keep every band's reading.")
        l("")
    }

    /** Writes [mdFile] and its picture beside it (same name, .png). */
    fun write(mdFile: java.nio.file.Path, spec: CalibrationSpec, plate: CalibrationPlate, info: Info) {
        val png = mdFile.resolveSibling(mdFile.fileName.toString().removeSuffix(".md") + ".png")
        picture(png, spec, plate)
        java.nio.file.Files.writeString(mdFile, markdown(spec, plate, info, png.fileName.toString()))
    }

    fun markdown(spec: CalibrationSpec, plate: CalibrationPlate, info: Info, pngName: String): String {
        val m = spec.melt
        val kind = kindOf(spec)
        val sb = StringBuilder()
        fun l(s: String = "") { sb.append(s).append('\n') }
        l("# ${kind.replaceFirstChar { it.uppercase() }} plate: ${info.filament} on ${info.machine}, tool ${info.tool + 1}")
        l()
        l("Made by plymeld ${info.version} on ${info.date} for `${info.gcodeName}`" + (info.predictedMin?.let { ", about ${n(it, 0)} min to print (plymeld's estimate; heating, the waits for each band's temperature, and any levelling come on top)" } ?: "") + ".")
        val lg = spec.ledges
        val br = spec.bridges
        val st = spec.strings
        val tb = spec.tubes
        val ft = spec.fit
        // the band the fit block stands in (on its pedestal)
        val ftBand = ft?.let { spec.readBand((it.fromLayer + 1) * spec.layerUm) }
        fun bandText(b: Band?) = b?.let { "band ${it.index + 1} (${it.tempC} C, fan ${n(it.fanPct, 0)} %)" } ?: "no read band"
        l(when (kind) {
            "melt" -> "Temperatures ${spec.bands.first().tempC} down to ${spec.bands.last().tempC} C, a step per temperature (${spec.bands.size} bands), the hottest the lowest, at the front. "
            else -> "Temperatures ${spec.bands.first().tempC} down to ${spec.bands.last().tempC} C in ${spec.bands.size} bands, hottest at the bottom. "
        } + "This plate measures, so far, " +
            listOfNotNull(if (m != null) "**how fast this filament melts at each temperature** (the terraced melt test)" else null,
                if (lg != null) "**how far an overhang holds at each temperature and fan** (the ledges)" else null,
                if (br != null) "**how far a bridge spans, and at which bridge flow**, at each temperature and fan (the bridges)" else null,
                if (st != null) "**how much retraction stops stringing** at each temperature (the ring)" else null,
                if (tb != null) "**the narrowest and widest line this nozzle lays well** (the tubes)" else null,
                if (ft != null) "**the clearance a fit needs** (the holes and the peg)" else null).let { if (it.size > 1) it.dropLast(1).joinToString(", ") + " and " + it.last() else it.joinToString() } + ".")
        l()
        if (m != null) {
            l("**Run the melt test first**, on its own plate (this one), before the motion plate and the filament plate: " + MELT_WHY + ".")
            l()
        } else if (info.maxFlowPlaceholder) {
            l("**Recommended: run the melt test first.** This filament's maximum flow (${info.maxFlowKey?.let { "`$it`" } ?: "melt.flow.max"}${info.maxFlowNow?.let { ", $it" } ?: ""}) is still plymeld's placeholder, not a measurement. " +
                MELT_FIRST + " Make the melt plate with `plymeld calibrate melt ...`.")
            l()
        }
        if (info.warnings.isNotEmpty()) {
            l("**Warnings:**")
            l()
            for (wn in info.warnings) l("- $wn")
            l()
        }
        l("## Before you print")
        l()
        l("The readings are only as good as the print's first layers and the printer's own state, so:")
        l()
        l("1. **Level the bed.** " + when {
            info.levels -> "This file runs the printer's bed levelling (hardware.machine.<key>.gcode.level) before the plate, once it has heated. If your printer also offers levelling when a print starts, you need not use both."
            info.levelSkipped -> "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling (mesh) yourself first."
            else -> "plymeld cannot level this printer from the g-code (no levelling command is known for it): turn on the printer's own levelling when you start the print (e.g. its \"level before print\" option), or run it from the printer's menu first."
        })
        l("2. **Run any other calibration your printer offers before a print**: nozzle or tool offsets on a multi-tool machine, its own flow or pressure-advance calibration (note whether it ran: it changes the conditions below), a nozzle clean or wipe.")
        l("3. **Clean the bed** (no fingerprints)${if (info.plate.isNotEmpty()) ", and use the build plate the file was made for (`${info.plate}`)" else ""}; the bed heats to ${info.bedTempC} C.")
        l("4. **Load `${info.filament}` on tool ${info.tool + 1}.** Dry filament matters: moisture looks like a melt limit (bubbles, rough walls).")
        l("5. **Watch the first layer**: every part's first layer should be flat and joined. Stop and re-level if it is not.")
        l("6. **During the print**: after each layer the head moves off to the front of the plate and pauses, so every layer cools alike; that is expected." +
            (if (m != null) " Before each step after the first, the head also waits there until the nozzle has reached that step's temperature; then it prints one layer (not read) and the step's top." else "") +
            (if (m != null) " On each melt step's top layer the FAST surface's thicker strips ask for up to ${n(spec.stripFlow(m.count - 1, 0), 1)} mm3/s, likely past what the filament melts, more so in the colder bands (the upper steps): the extruder may click there. That is the test working. If it keeps grinding, or the filament stops feeding, stop the print: a ground filament starves every band above." else ""))
        l()
        l("## What the plate prints with")
        l()
        l("Every reading is measured under these conditions; keep them with the results.")
        l()
        l("| condition | value |")
        l("|---|---|")
        l("| filament, tool | ${info.filament}, tool ${info.tool + 1} |")
        l("| layer height | ${n(spec.layerUm / 1000.0, 2)} mm |")
        l("| line width | ${n(spec.widthUm / 1000.0, 2)} mm (${listOfNotNull(if (tb != null) "the tubes" else null).joinToString(" and ").ifEmpty { "nothing" }} their own, below); " +
            "first layer ${n(spec.firstWidthUm / 1000.0, 2)} mm (the first-layer line width)${if (tb != null) ", except the tubes" else ""} |")
        l("| flow ratio | ${n(spec.flowRatio, 3)} |")
        l("| pressure advance | ${info.pressureAdvance} |")
        l("| bed | ${info.bedTempC} C${if (info.plate.isNotEmpty()) " (build plate `${info.plate}`)" else ""} |")
        l("| part fan | by band, below (first layer off) |")
        l("| retraction | ${n(info.retractMm, 2)} mm |")
        if (spec.minLayerTimeS > 0) l("| each layer | at least ${n(spec.minLayerTimeS, 1)} s (a pause, parked, makes up the rest) |")
        m?.let {
            val len = PlateGeometry.meltLineLen / 1000.0
            l("| melt test | surfaces at ${it.speeds.joinToString(", ") { v -> n(v, 1) }} mm/s (FAST = " +
                (if (it.machineHeld) "the machine's print speed: the top of the range is the machine's" else "centered on the filament's expected maximum flow, ${n(it.centerFlow, 1)} mm3/s" + (if (info.maxFlowPlaceholder) " (plymeld's placeholder)" else "")) +
                ", MID half, SLOW a quarter), acceleration ${n(it.accel, 0)} mm/s2; " +
                "lines ${n(len, 0)} mm long (${it.speeds.joinToString(", ") { v -> n(100 * fullSpeedShare(v, it.accel, len), 1) }} % of each at full speed), ${it.lines} to a strip; " +
                "strips ${n(it.widthUm / 1000.0, 2)} mm wide, ${it.heightsUm.joinToString(", ") { hh -> n(hh / 1000.0, 2) }} mm thick, on a solid floor; the head ${n(it.liftUm / 1000.0, 2)} mm above every strip laid (the travel lift) when it travels; ${meltFanText(spec)} |")
        }
        ft?.let { l("| fit holes and peg | as drawn, no compensation; ${n(it.speed, 0)} mm/s, in ${bandText(ftBand)}: the block on a pedestal, the peg's top end |") }
        tb?.let { l("| line-width tubes | ${n(it.speed, 0)} mm/s, ${n(it.accel, 0)} mm/s2, the full height: every band |") }
        st?.let { l("| stringing ring | retraction by hop (below); its speed, the travel speed, and the lift are the filament's and tool's own (see the g-code's CALIBRATION_PLATE block) |") }
        br?.let { l("| bridge decks | front flow ${n(it.flows[0], 3)}, back flow ${n(it.flows[1], 3)}; ${n(it.speed, 0)} mm/s, ${n(it.accel, 0)} mm/s2, the fan by band |") }
        lg?.let { l("| overhang ledges | ${n(it.speed, 0)} mm/s, ${n(it.accel, 0)} mm/s2, the fan by band |") }
        l("| the frame${if (info.frameParts.isNotEmpty()) " (" + info.frameParts.joinToString(", ") + ")" else ""}: read for nothing | ${n(info.frameSpeed, 0)} mm/s" +
            (if (info.frameAccel.isNaN()) "" else ", ${n(info.frameAccel, 0)} mm/s2") + (info.frameWhy?.let { ": $it" } ?: "") + " |")
        l("| first layer | ${n(spec.firstLayerUm / 1000.0, 2)} mm thick (plan.mechanism.adhesion.first_layer.height: a first-layer window's choice, else the setting; the layers above ${n(spec.layerUm / 1000.0, 2)} mm), ${n(info.firstLayerSpeed, 0)} mm/s (the configured first-layer speed, held to the filament's maximum flow) |")
        l()
        if (info.costs.isNotEmpty()) {
            l("## What each test costs")
            l()
            l("plymeld's estimate, attributed by the g-code's cells: each test's own lines and the travels into them. What no test owns is shared: the layer changes, the cooling pause (parked in front), the temperature waits (counted as nothing: heating comes on top) and the start and end g-code.")
            l()
            l("| test | time (min) | of it, its base | filament (m) | of it, its base |")
            l("|---|---|---|---|---|")
            for (c in info.costs) l("| ${c.part?.title ?: "shared"} | ${c.timeS?.let { n(it / 60, 1) } ?: "not predicted"} | ${if (c.part == null) "" else c.baseTimeS?.let { n(it / 60, 1) } ?: ""} | ${n(c.filamentMm / 1000, 2)} | ${if (c.part == null) "" else n(c.baseFilamentMm / 1000, 2)} |")
            val tt = info.costs.mapNotNull { it.timeS }.takeIf { it.size == info.costs.size }?.sum()
            val bt = info.costs.mapNotNull { it.baseTimeS }.takeIf { it.isNotEmpty() }?.sum()
            l("| **the plate** | ${tt?.let { n(it / 60, 1) } ?: "not predicted"} | ${bt?.let { n(it / 60, 1) } ?: ""} | ${n(info.costs.sumOf { it.filamentMm } / 1000, 2)} | ${n(info.costs.sumOf { it.baseFilamentMm } / 1000, 2)} |")
            l()
        }
        l("## The plate")
        l()
        l("![The plate, from above and from the front]($pngName)")
        l()
        l("The tests are laid out to fit the bed (packed, ${n(PlateGeometry.familyGap / 1000.0, 0)} mm apart), so where each stands depends on which are on the plate: the picture shows this plate's.")
        l()
        if (spec.baseLayers > 0) {
            l("- **The bases**: every test stands on a base of its own, ${spec.baseLayers} layers (${n(spec.baseLayers * spec.layerUm / 1000.0, 1)} mm) thick and ${n(PlateGeometry.baseMargin / 1000.0, 0)} mm wider than the test on every side; the bands start above the bases${if (spec.ruler) " (the post's notches count from there)" else ""}. " +
                "Each layer of a base is ${PlateGeometry.frameWalls} **outline rings** round its edge (and round its notch), then **solid diagonal lines** printed in one sweep from one side of the base to the other (monotonic), at 45 degrees on one layer and the other diagonal on the next, so the lines of neighboring layers cross. " +
                "One **notch** is cut into each base's **front edge** (${n(PlateGeometry.baseNotchW / 1000.0, 0)} mm wide, ${n(PlateGeometry.baseNotchIn / 1000.0, 0)} mm in), in its upper layers only: its first layer is whole, so the notch is a step in the edge, not a hole. " +
                "The notch stands in front of the test's **first item**: " + spec.parts.filter { it != Part.POST }.joinToString("; ") { p -> "the ${p.title}: ${firstItem(p)}" } +
                ". So the notched edge is the test's front, and its items count from the notch.")
        }
        if (spec.ruler) l("- **The post** (front left) is the ruler. Its chamfered corner marks the front-left (origin). Each band has **one notch** in the post's front face, a little above the band's start; every fourth notch is **long**. Count notches from the bottom to know which band you are looking at.")
        if (m != null) {
            val len = PlateGeometry.meltLineLen / 1000.0
            val h = spec.layerUm / 1000.0
            val body1 = (spec.meltReadLayer(0) - spec.baseLayers) * h
            l("- **The terraced melt test** (${where(plate, Part.MELT)}) is a flight of ${spec.bands.size} **steps**, one per band: **band 1 (the hottest) the lowest step, at the front**, each colder band's step directly behind and higher. " +
                "Each step's **top is one layer of strips on a solid floor**, and nothing is printed on it afterwards: that top is what you read. There is no ruler post on this plate: the steps themselves tell the bands (count them from the front, or from the lowest).")
            l("- **The heights.** Band 1's step is a body ${n(body1, 2)} mm tall (${spec.meltReadLayer(0) - spec.baseLayers} layers) on the base, its top layer the step's **solid floor** (top at z ${n(spec.meltReadLayer(0) * h, 2)} mm), then its strips. " +
                "Each later band's step is **two layers higher**: before its first layer the head parks in front of the plate and **waits until the nozzle has reached the band's temperature** (M109); then that layer is printed at it: the step's solid floor, while the steps behind it rise as bodies; then the step's strips. " +
                "So every top is printed at its own temperature, already reached. Each strip is printed at **its floor's top plus its own thickness** (the strips' heights per band are in the tables below), so the thicker strips stand up to ${n((m.heightsUm.last() - spec.layerUm) / 1000.0, 2)} mm above the layer the strips are printed on: " +
                "they are printed **last on their layer and thinnest first**, and the head **lifts ${n(m.liftUm / 1000.0, 2)} mm (the travel lift) above the tallest strip** before it travels to the next surface and when it leaves the step, so it never drags across one.")
            l("- Each step's top has **three surfaces** side by side, left to right: **FAST** at ${n(m.speeds[0], 1)} mm/s (" +
                (if (m.machineHeld) "the machine's print speed" else "centered on the filament's expected maximum flow, below the machine's ${n(m.machineSpeed, 0)} mm/s") + "), **MID** at ${n(m.speeds[1], 1)} mm/s and **SLOW** at ${n(m.speeds[2], 1)} mm/s. All three are laid alike: " +
                "**${m.count} strips**, front to back, all ${n(m.widthUm / 1000.0, 2)} mm wide (the plate's line width), **the thinnest at the front**: ${m.heightsUm.joinToString(", ") { n(it / 1000.0, 2) }} mm thick. Each strip is ${m.lines} lines, each ${n(len, 0)} mm long, left and right, laid front to back, so **across a strip is time at that flow**: " +
                "a line takes about ${m.speeds.joinToString(", ") { n(len / it, 2) }} s on FAST, MID, and SLOW, a strip ${m.speeds.joinToString(", ") { n(m.lines * len / it, 1) }} s. The speed never changes on a surface; the flow changes with the strip's thickness, strip by strip.")
            l("- **Under each surface** is its **floor**, one solid layer (two walls, then lines front to back one bead spacing apart, across the strips' lines), on a body: two walls and a **grid** of lines about ${n(PlateGeometry.pedestalPitch / 1000.0, 0)} mm apart both ways. " +
                "So a strip is pressed onto something solid and spreads as it would on a print, and one that does not melt fast enough shows **gaps down to the floor**. The strips' line ends lie on the floor's walls.")
            l("- **Why thickness, not width**: on the first printed plate the strips varied in width on a sparse grid, and gaps showed on every surface, worst on the widest strips: a line wider than the nozzle spreads only when pressed onto something solid, and over the grid's gaps every line was a small bridge. Every strip is now the plate's width on a solid floor, and its thickness sets its flow.")
            l()
        }
        if (lg != null) {
            l()
            l("- **The ledges** stand on the front of the long block (${where(plate, Part.OVERHANGS)}), **lowest share on the left**. In every band each ledge leans out layer by layer, every layer hanging its share of a line (${n(spec.widthUm / 1000.0, 2)} mm) past the one below, so its underside is an overhang as steep as that share; between bands it is cut back to the block, so each band has a ledge of its own (count the bands on the post's notches: the ledges' bands are at the same heights).")
            l()
            l("| ledge (from the left) | share of a line | step out per layer (mm) | angle from horizontal |")
            l("|---|---|---|---|")
            for ((k, sh) in lg.shares.withIndex()) {
                val step = sh * spec.widthUm / 1000.0
                l("| ${k + 1} | ${n(sh, 2)} | ${n(step, 3)} | ${n(Math.toDegrees(Math.atan2(spec.layerUm / 1000.0, step)), 0)} deg |")
            }
        }
        if (br != null) {
            l()
            val dd = n(plate.deckDepth / 1000.0, 1)
            l("- **The bridges** are the row of ${br.spansUm.size + 1} piers (${where(plate, Part.BRIDGES)}, ${n(plate.bridgeDepth / 1000.0, 0)} mm deep), **the shortest span on the left**. On the top layer of every band, two decks of lines are laid across all the piers at once, with nothing under them between the piers down to the bed: the **front deck** at bridge flow ${n(br.flows[0], 3)}, the **back deck** at ${n(br.flows[1], 3)}. So every band has its own pair of decks, at its own temperature and fan.")
            l("- The decks are **terraced** like a stepped pyramid seen from the side: band 1's front deck is the strip at the very front of the row and its back deck the strip at the very back; each higher band's decks are one strip (about $dd mm) further in, from the front and from the back. From above, every deck is a step of its own and none covers another; the top band's two decks are the innermost, ${n(PlateGeometry.deckGap / 1000.0, 0)} mm apart in the middle.")
            l()
            l("| deck strip (from the front) | band | deck | flow |")
            l("|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${b.index + 1} (${b.tempC} C, fan ${n(b.fanPct, 0)} %) | front | ${n(br.flows[0], 3)} |")
            for (b in spec.bands.reversed()) l("| ${2 * spec.bands.size - b.index} | ${b.index + 1} (${b.tempC} C, fan ${n(b.fanPct, 0)} %) | back | ${n(br.flows[1], 3)} |")
            l()
            l("| span (from the left) | length (mm) |")
            l("|---|---|")
            for ((k, sp) in br.spansUm.withIndex()) l("| ${k + 1} | ${n(sp / 1000.0, 1)} |")
        }
        if (ft != null) {
            l()
            l("- **The fit test** (${where(plate, Part.FIT)}) is the block with a row of ${ft.holesUm.size} round holes, **the smallest on the left**, standing on a pedestal so it prints in ${bandText(ftBand)} (its holes are blind, ${n(ft.blockLayers * spec.layerUm / 1000.0, 1)} mm deep), and beside it the **peg**: a round column (${n(ft.pegUm / 1000.0, 2)} mm across, ${n(ft.pegLayers * spec.layerUm / 1000.0, 1)} mm tall) whose **top end** (its last ${n(ft.blockLayers * spec.layerUm / 1000.0, 1)} mm) printed in the same band. Holes and peg are printed exactly as drawn, with no compensation.")
            l()
            l("| hole (from the left) | drawn across (mm) | clearance on the peg (mm) |")
            l("|---|---|---|")
            for ((k, hd) in ft.holesUm.withIndex()) l("| ${k + 1} | ${n(hd / 1000.0, 2)} | ${n((hd - ft.pegUm) / 1000.0, 2)} |")
        }
        if (tb != null) {
            l()
            l("- **The tubes** are the row of ${tb.widthsUm.size} square tubes (${where(plate, Part.LINE_WIDTH)}), the plate's full height (every band), each one line thick, **the narrowest line on the left**. Each line is laid at its own width (the filament it gets follows the width).")
            l()
            l("| tube (from the left) | line width (mm) |")
            l("|---|---|")
            for ((k, wd) in tb.widthsUm.withIndex()) l("| ${k + 1} | ${n(wd / 1000.0, 2)} |")
        }
        if (st != null) {
            l()
            l("- **The stringing ring** (${where(plate, Part.STRINGING)}) is a ring of ${st.lengthsMm.size + 1} round posts (${n(PlateGeometry.ringPost / 1000.0, 0)} mm across, ${n(PlateGeometry.ringPostGap / 1000.0, 0)} mm apart), the plate's full height. On every layer the nozzle prints post 1, then hops **counter-clockwise** (seen from above) to each next post, retracting by that hop's length before it crosses the gap: any hairs across a gap are its hop's. Post 1 is the first **counter-clockwise from the open gap**, the one gap the nozzle never crosses (at the ring's front): it is the control, so hairs there come from something other than a hop (oozing, or the travels to and from the ring).")
            l()
            l("| hop (gap, counter-clockwise from the open gap) | from post | to post | retraction (mm) |")
            l("|---|---|---|---|")
            for ((k, len) in st.lengthsMm.withIndex()) l("| ${k + 1} | ${k + 1} | ${k + 2} | ${n(len, 2)} |")
            l("| open gap | ${st.lengthsMm.size + 1} | 1 | none: never crossed |")
        }
        l()
        l("## The bands")
        l()
        if (m != null) {
            // the melt plate: no post; each band's wait (M109), its floor, and its strips' heights
            val h = spec.layerUm / 1000.0
            // heights as printed: the design's plus the first layer's extra thickness (every layer of the plate sits that much higher)
            val up = (spec.firstLayerUm - spec.layerUm) / 1000.0
            l("| band | height (mm) | nozzle | part fan | its wait (M109) for the temperature: before the layer at z (mm) | its floor's top at z (mm) | its strips at z (mm), front to back |")
            l("|---|---|---|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${n(b.fromUm / 1000.0 + up, 2)}-${n(b.readToUm / 1000.0 + up, 2)} | ${b.tempC} C | ${n(b.fanPct, 0)} % | " +
                "${if (b.index == 0) "the start, before the first layer (z ${n(h + up, 2)}), and again at z ${n(b.fromUm / 1000.0 + h + up, 2)}" else n(b.fromUm / 1000.0 + h + up, 2)} | ${n(spec.meltReadLayer(b.index) * h + up, 2)} | " +
                "${m.heightsUm.joinToString(", ") { n(spec.meltReadLayer(b.index) * h + it / 1000.0 + up, 2) }} |")
            if (up > 0) l("Heights are as printed: the plate's layers sit ${n(up, 2)} mm above its design, the first layer being that much thicker (${n(spec.firstLayerUm / 1000.0, 2)} mm).")
            l()
            l("Each band's wait comes at its first layer, before anything of that layer is printed; band 1's bases and first layers print at its temperature, waited for at the start. Each band after the first is two layers: its step's floor, printed after the wait, and its strips, " +
                "each at the floor's top plus its thickness (the layer they print on reaches z ${n(h, 2)} above the floor; the thicker strips stand above it, below the next layer's top).")
        } else {
            l("| band | notch | height (mm) | nozzle | part fan |")
            l("|---|---|---|---|---|")
            val up = (spec.firstLayerUm - spec.layerUm) / 1000.0
            for (b in spec.bands) l("| ${b.index + 1} | ${if ((b.index + 1) % 4 == 0) "long" else "short"} | ${n(b.fromUm / 1000.0 + up, 1)}-${n(b.readToUm / 1000.0 + up, 1)} | ${b.tempC} C | ${n(b.fanPct, 0)} % |")
            if (up > 0) l("Heights are as printed: the plate's layers sit ${n(up, 2)} mm above its design, the first layer being that much thicker (${n(spec.firstLayerUm / 1000.0, 2)} mm).")
            l()
            l("Between bands, a few layers change to the next temperature (and wait for it); they are not read.")
        }
        l()
        if (m != null) meltReading(::l, spec, info)
        if (lg != null) {
            val top = n(lg.shares.last(), 2)
            l("## How to read the ledges")
            l()
            l("Look at the ledges' undersides from below and in front, with a light behind you. For **each band** (find it by its notch on the post):")
            l()
            l("1. Go along that band's ledges from the left (the shallowest).")
            l("2. Find the first ledge whose underside **is not clean**: lines drooping or hanging in loops, a curled-up edge at its front, or a rough, stringy surface.")
            l("3. Write down the share of the **ledge before it**: the steepest overhang that held at that temperature and fan.")
            l("4. If every ledge in the band is clean, write **\"at least $top\"**; if even the first is not, write **\"below ${n(lg.shares.first(), 2)}\"**.")
            l()
            l("| band | nozzle | fan | last clean ledge (share) | notes |")
            l("|---|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${b.tempC} C | ${n(b.fanPct, 0)} % |  |  |")
            l()
            l("### What the ledge results mean (decided before printing)")
            l()
            l("- **More fan holds a steeper share** (bands at the higher fan read higher than their neighbors): expected for most filaments; plymeld takes the share by fan.")
            l("- **Colder bands hold a steeper share**: expected (the plastic sets sooner).")
            l("- **The fan makes no difference**: this filament's overhangs do not depend on the fan in this range; the lower fan may then do.")
            l("- **Every ledge holds in every band** (\"at least $top\"): a bound; the next plate can test steeper shares (`--overhangs`), up to just under 1 (a share of 1 is a bridge).")
            l("- **Ledges curl up at the front rather than droop**: the edge is cooling and shrinking; a hotter band or less fan may hold better, and the speed (${n(lg.speed, 0)} mm/s, a condition of every reading) matters.")
            l()
        }
        if (br != null) {
            val longest = n(br.spansUm.last() / 1000.0, 1)
            l("## How to read the bridges")
            l()
            l("Each band's decks are their own step: from above, band 1's are the outermost strips (the very front and the very back of the row), band ${spec.bands.size}'s the innermost; and at its height they are at the top of the band (count the post's notches). Look at each step from above, then along it from the front and from below, with a light behind the plate (from below, a deck is the first one you see in its strip, nothing lies under it). For **each band**, and for **each deck** (the front one at flow ${n(br.flows[0], 3)}, the back one at ${n(br.flows[1], 3)}):")
            l()
            l("1. Go along the spans from the left (the shortest).")
            l("2. Find the first span where the deck **is not clean**: it sags (its middle hangs below its ends), its lines have parted (gaps you can see through), or lines hang down in loops.")
            l("3. Write down the length of the **span before it**: the longest that bridged cleanly. If every span is clean, write **\"at least $longest\"**; if even the first is not, write **\"below ${n(br.spansUm.first() / 1000.0, 1)}\"**.")
            l()
            l("| band | nozzle | fan | front deck (flow ${n(br.flows[0], 3)}): longest clean span (mm) | back deck (flow ${n(br.flows[1], 3)}): longest clean span (mm) | notes |")
            l("|---|---|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${b.tempC} C | ${n(b.fanPct, 0)} % |  |  |  |")
            l()
            l("### What the bridge results mean (decided before printing)")
            l()
            l("- **More fan bridges further** (the higher-fan bands read longer): expected; plymeld takes the longest clean span by fan.")
            l("- **One deck bridges further than the other in most bands**: that deck's flow is the better bridge flow for this filament (the leaner deck's strands are thinner but tauter).")
            l("- **The two decks read the same**: the bridge flow makes no difference in this range; either will do.")
            l("- **Every span is clean** (\"at least $longest\"): a bound; the next plate can test longer spans (`--spans`).")
            l("- **The strands are clean but the deck has holes**: the strands are too thin or too far apart to join; the richer deck should show fewer holes.")
            l()
        }
        if (ft != null) {
            l("## How to read the fit test")
            l()
            l("Snap the peg (the column) off the plate. Its **top end** is the part that printed in ${bandText(ftBand)}, like the block: use that end. Then, from the top of the block:")
            l()
            l("1. Try the peg's top end in each hole, from the left (the tightest). The holes are blind, ${n(ft.blockLayers * spec.layerUm / 1000.0, 1)} mm deep.")
            l("2. Find the **first hole it goes into all the way down by hand**, without forcing it.")
            l("3. Note whether it is **snug** (no play) or **loose** (it rattles), and whether it **turns** freely.")
            l("4. If even the largest hole is too tight, write **\"more than ${n((ft.holesUm.last() - ft.pegUm) / 1000.0, 2)}\"**; if the smallest takes it loosely, **\"${n((ft.holesUm.first() - ft.pegUm) / 1000.0, 2)} or less\"**.")
            l()
            l("| first hole the peg goes into: its clearance (mm) | snug, loose, turns? | notes |")
            l("|---|---|---|")
            l("|  |  |  |")
            l()
            l("### What the fit results mean (decided before printing)")
            l()
            l("- **The peg goes in at some clearance**: that is the clearance a sliding fit needs with this filament and nozzle, holes and pegs both printed upright; plymeld is meant to use it for hole and peg compensation (not yet).")
            l("- **It goes in at no clearance (the first hole)**: holes print true or large; a tighter fit than these holes would need a smaller hole than drawn.")
            l("- **It goes into none**: holes print small, or the peg large: more clearance than tested; the next plate can test larger holes (`--holes`). Check the peg's diameter with calipers if you can, and write it in the notes: it tells the peg's share from the hole's.")
            l("- This is measured on vertical holes and a vertical peg, in ${bandText(ftBand)}: a hole printed lying down, or at another temperature, fits differently.")
            l()
        }
        if (tb != null) {
            val lo = n(tb.widthsUm.first() / 1000.0, 2); val hi = n(tb.widthsUm.last() / 1000.0, 2)
            l("## How to read the tubes")
            l()
            l("Look at each tube's wall from the side against the light, and squeeze it gently. For **each band** (the post's notches tell the bands):")
            l()
            l("1. A **good** tube's wall is whole and even: no gaps or see-through spots, no layers that missed each other, no blobs, its surface the same all round.")
            l("2. Narrow lines fail first by gaps and by layers not sticking (a wall that splits when squeezed); wide lines by an uneven, rippled, or blobby wall, or plastic smeared out to the sides.")
            l("3. Write down the **narrowest** and the **widest** good tube at that band's height. If the narrowest is still good, write **\"$lo or less\"**; if the widest is, **\"$hi or more\"**.")
            l()
            l("| band | nozzle | narrowest good line (mm) | widest good line (mm) | notes |")
            l("|---|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${b.tempC} C |  |  |  |")
            l()
            l("### What the tube results mean (decided before printing)")
            l()
            l("- **A range around the nozzle's size** (the expected result): plymeld's thinnest and widest lines for this tool (`line.width.min` / `line.width.max`), by temperature; variable-width walls stay inside it.")
            l("- **The narrow end gets worse in the colder bands**: expected (colder plastic bonds less across a thin line).")
            l("- **The widest good line is narrower than the nozzle**: unusual; look at the flow ratio (the flow plate) and a partial clog first.")
            l("- **Every tube is good**: a bound at both ends; the next plate can test narrower and wider lines (`--widths`).")
            l()
        }
        if (st != null) {
            l("## How to read the stringing ring")
            l()
            l("Look across each gap of the ring against a dark background, with a light from the side: strings show as fine hairs or webs across the gap. First look at the **open gap** (the control: never crossed); then, for **each band** (the same heights as the post's notches):")
            l()
            l("1. Go round the hops counter-clockwise from the open gap (hop 1, the shortest retraction, first).")
            l("2. Find the first hop with **no strings** across its gap at that band's height (a few loose wisps that brush off are not strings).")
            l("3. Write down that hop's retraction. If every hop strings, write **\"more than ${n(st.lengthsMm.last(), 2)}\"**; if even the first is clean, write **\"${n(st.lengthsMm.first(), 2)} or less\"**. Note whether the open gap has hairs at that height.")
            l()
            l("| band | nozzle | shortest retraction with no strings (mm) | hairs in the open gap? | notes |")
            l("|---|---|---|---|---|")
            for (b in spec.bands) l("| ${b.index + 1} | ${b.tempC} C |  |  |  |")
            l()
            l("### What the stringing results mean (decided before printing)")
            l()
            l("- **The open gap has hairs too**: they are not the hops' (the nozzle never crosses it): oozing, or the travels to and from the ring. Read a hop as stringing only where it has clearly more than the open gap.")
            l("- **Hotter bands need more retraction**: expected (the plastic runs thinner); plymeld takes the retraction by temperature, and it weighs against the melt rate in choosing a temperature.")
            l("- **Every hop strings in the hottest bands only**: those temperatures string whatever the retraction; plymeld avoids them where stringing matters.")
            l("- **No hop ever strings**: the shortest length is enough at every temperature tested (\"${n(st.lengthsMm.first(), 2)} or less\"); a shorter one may do as well.")
            l("- **Every hop strings in every band**: not the retraction alone. Wet filament strings whatever is done (dry it first); the travel speed and the lift (conditions in the g-code) matter too.")
            l()
        }
        l("plymeld cannot read these results back in yet (`plymeld calibrate read` is planned). Keep the filled tables and this page together.")
        return sb.toString()
    }

    /** The picture's title line: which layer each part on the plate is drawn at, naming only the parts the plate has. */
    fun pictureTitle(spec: CalibrationSpec): String {
        val shown = listOfNotNull(if (spec.parts.any { it != Part.MELT }) "a layer of band 1" else null, if (spec.baseLayers > 0) "the bases' top layer, notched" else null, if (spec.melt != null) "every melt step's top" else null, if (spec.bridges != null) "every band's bridge decks" else null,
            if (spec.fit != null) "the fit block at its first layer" else null)
        return "From above (${shown.joinToString("; ")})"
    }

    /**
     * The picture: the plate from above (as the program prints it: each part at the layer that shows it, the melt test's
     * steps at their tops, labeled) and the post from the front (one notch per band, labeled with its temperature and fan);
     * on the melt plate (no post) the steps from the side instead, each band's body, its wait, and its top to scale.
     */
    fun picture(file: java.nio.file.Path, spec: CalibrationSpec, plate: CalibrationPlate) {
        System.setProperty("java.awt.headless", "true")
        val program = plate.program()
        val readZ = spec.bands.first().let { it.fromUm + spec.layerUm }
        // the bases from their last layer (its notches cut); the ledges from band 1's top read layer, where they stand out
        // furthest; every band's bridge decks (each band's in its own strips, as seen from above); the fit block from its
        // first (on its pedestal); everything else from band 1's first read layer
        val ledgeZ = spec.bands.first().readToUm
        val baseZ = spec.baseLayers * spec.layerUm
        val fitZ = spec.fit?.let { (it.fromLayer + 1) * spec.layerUm }
        val paths = program.steps.filterIsInstance<plymeld.program.Extrude>().filter {
            val lb = it.exact?.label?.removePrefix("adhesion:") ?: ""
            when {
                lb.startsWith("base ") -> it.zUm == baseZ
                // the melt test: every step's top (its strips, on their read layer), not the floors and bodies under them
                lb.startsWith("melt body ") || lb.startsWith("melt floor ") -> false
                lb.startsWith("melt ") -> true
                lb.startsWith("deck ") -> plate.deckBandAt(it.zUm) != null
                lb.endsWith("ledges") -> it.zUm == ledgeZ
                lb == "fit block" || lb == "pedestal" || lb == "peg" -> it.zUm == fitZ
                else -> it.zUm == readZ
            }
        }
        val W = 1800; val H = 1400
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 252); g.fillRect(0, 0, W, H)
        // top view
        val b = plate.bounds()
        val ox = spec.originXUm; val oy = spec.originYUm
        val s = minOf((W - 160).toDouble() / (b[2] - b[0] + 12_000), 860.0 / (b[3] - b[1] + 12_000))
        fun px(x: Long) = 80 + (x - ox - b[0] + 6_000) * s
        fun py(y: Long) = 96 + 860 - (y - oy - b[1] + 6_000) * s
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 20)
        // the title, wrapped to the picture's width
        run {
            var line = ""; var y = 34
            for (word in pictureTitle(spec).split(' ')) {
                val next = if (line.isEmpty()) word else "$line $word"
                if (g.fontMetrics.stringWidth(next) > W - 80 && line.isNotEmpty()) { g.drawString(line, 40, y); y += 24; line = word } else line = next
            }
            g.drawString(line, 40, y)
        }
        for (p in paths) {
            val label = p.exact?.label ?: ""
            g.color = when { label.startsWith("base ") -> Color(222, 222, 228)
                // the melt strips: lighter to darker as the strip thickens, a hue per surface (FAST, MID, SLOW)
                label.startsWith("melt ") -> label.split('.').let { parts -> val k = parts[2].toInt() - 1; val f = 0.35 + 0.65 * k / maxOf(1, spec.melt!!.count - 1)
                    when (parts[1]) { "fast" -> Color((235 - 60 * f).toInt(), (150 - 110 * f).toInt(), (110 - 90 * f).toInt()); "mid" -> Color((230 - 80 * f).toInt(), (200 - 90 * f).toInt(), (110 - 90 * f).toInt()); else -> Color((150 - 110 * f).toInt(), (190 - 110 * f).toInt(), (235 - 90 * f).toInt()) } }
                label.startsWith("flow ") -> Color(70, 140, 95); label == "ledges" -> Color(190, 120, 50)
                // the decks: alternate bands' strips lighter and darker, so the steps read apart
                label == "deck 1" -> if ((plate.deckBandAt(p.zUm) ?: 0) % 2 == 0) Color(200, 80, 80) else Color(150, 50, 50)
                label == "deck 2" -> if ((plate.deckBandAt(p.zUm) ?: 0) % 2 == 0) Color(170, 80, 170) else Color(115, 45, 115); label.startsWith("post ") -> Color(40, 140, 160); label.startsWith("tube ") -> Color(120, 90, 30); label == "fit block" -> Color(110, 110, 160); label == "peg" -> Color(150, 50, 50); label.startsWith("hex ") -> Color(80, 120, 60); else -> Color(150, 150, 155) }
            g.stroke = BasicStroke(maxOf(2f, (p.widthUm * s).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            val m = p.xy.size / 2
            for (k in 0 until if (p.closed) m else m - 1) { val j = (k + 1) % m; g.draw(Line2D.Double(px(p.xy[2 * k]), py(p.xy[2 * k + 1]), px(p.xy[2 * j]), py(p.xy[2 * j + 1]))) }
        }
        // the melt test: each surface's name and speed above the back step; each step's band at its left; band 1's strips'
        // widths and FAST flows beside its FAST surface (the front step: its strips the same in every band)
        spec.melt?.let { mt ->
            val last = spec.bands.size - 1
            g.font = Font("SansSerif", Font.BOLD, 15)
            for (sf in 0 until 3) {
                val bx = plate.meltBox(last, sf)
                val label = "${listOf("FAST", "MID", "SLOW")[sf]}: ${n(mt.speeds[sf], 1)} mm/s"
                g.color = Color(20, 20, 20)
                g.drawString(label, (px(ox + (bx[0] + bx[2]) / 2) - g.fontMetrics.stringWidth(label) / 2).toInt(), (py(oy + bx[3]) - 8).toInt())
            }
            g.font = Font("SansSerif", Font.PLAIN, 13)
            // each step's band right of its SLOW surface (open room: left of the FAST surface the picture's edge may cut it)
            for (bd in spec.bands) {
                val bx = plate.meltBox(bd.index, 2)
                val label = "band ${bd.index + 1}: ${bd.tempC} C, fan ${n(bd.fanPct, 0)} %, floor's top at z ${n(spec.meltReadLayer(bd.index) * spec.layerUm / 1000.0, 2)} mm"
                g.color = Color(20, 20, 20)
                g.drawString(label, (px(ox + plate.footprint(Part.MELT)[2]) + 10).toInt(), (py(oy + (bx[1] + bx[3]) / 2) + 5).toInt())
            }
            // band 1's strips: each one's thickness and its flow on each surface, inside its strip on the FAST surface (white on the strip)
            g.font = Font("SansSerif", Font.BOLD, maxOf(9, minOf(13, (plate.meltLineY(1, 0) - plate.meltLineY(0, 0)).let { (it * s * 0.7).toInt() })))
            val fb = plate.meltBox(0, 0)
            for (k in 0 until mt.count) {
                val yMid = fb[1] + (plate.meltLineY(k, 0) + plate.meltLineY(k, mt.lines - 1)) / 2
                val label = "${n(mt.heightsUm[k] / 1000.0, 2)} mm thick: ${(0 until 3).joinToString(" / ") { sf -> n(spec.stripFlow(k, sf), 1) }} mm3/s"
                g.color = Color.WHITE
                g.drawString(label, (px(ox + fb[0]) + 6).toInt(), (py(oy + yMid) + g.font.size / 3).toInt())
            }
        }
        // the ledges: each one's number and share behind the block (they lean out of its front)
        spec.ledges?.let { lgs ->
            g.font = Font("SansSerif", Font.PLAIN, 12)
            for (k in lgs.shares.indices) {
                val (a, b2) = plate.ledgeCell(k)
                val label = n(lgs.shares[k], 2)
                val tw = g.fontMetrics.stringWidth(label)
                g.color = Color(20, 20, 20)
                g.drawString(label, (px(ox + (a + b2) / 2) - tw / 2).toInt(), (py(oy + plate.ledgeBack) - 12).toInt())
            }
            g.color = Color(190, 120, 50); g.font = Font("SansSerif", Font.BOLD, 15)
            // under the ledges (where they stand out furthest), from the second one: behind them the block (and its labels)
            // leaves no room
            val front = plate.ledgeBack - PlateGeometry.ledgeBase - lgs.shares.maxOf { PlateGeometry.ledgeDepth(spec, it, ledgeZ) }
            val tx = px(ox + plate.ledgeCell(minOf(1, lgs.shares.size - 1)).first).toInt(); val ty = (py(oy + front) + 22).toInt()
            g.drawString("ledges (1 to ${lgs.shares.size} from the left): shares of a line, drawn at band 1's top", tx, ty)
        }
        // the bridges: each span's number and length above the row; which deck is which
        spec.bridges?.let { brs ->
            g.font = Font("SansSerif", Font.PLAIN, 12); g.color = Color(20, 20, 20)
            val yTop = plate.bridgeY0 + plate.bridgeDepth
            for (k in brs.spansUm.indices) {
                val a = plate.pierX(k).second; val b2 = plate.pierX(k + 1).first
                val label = "${n(brs.spansUm[k] / 1000.0, 1)} mm"
                g.drawString(label, (px(ox + (a + b2) / 2) - g.fontMetrics.stringWidth(label) / 2).toInt(), (py(oy + yTop) - 10).toInt())
            }
            // each terrace strip's band and deck inside it (a font that fits the strip)
            g.font = Font("SansSerif", Font.BOLD, maxOf(9, minOf(15, (plate.deckDepth * s * 0.8).toInt()))); g.color = Color.WHITE
            val xl = (px(ox + plate.pierX(0).first) + 10).toInt()
            for (bd in spec.bands) for (d in 0 until 2) {
                val (y0, y1) = plate.deckY(bd.index, d)
                val label = "band ${bd.index + 1} ${if (d == 0) "front" else "back"}: flow ${n(brs.flows[d], 3)}"
                g.drawString(label, xl, (py(oy + (y0 + y1) / 2) + g.font.size / 3).toInt())
            }
        }
        // the fit test: each hole's clearance above the block, the peg's size beside it
        spec.fit?.let { fts ->
            g.font = Font("SansSerif", Font.PLAIN, 11); g.color = Color(20, 20, 20)
            val fb = plate.fitBox()
            for (k in fts.holesUm.indices) {
                val (cx, _) = plate.holeCenter(k)
                val label = "+${n((fts.holesUm[k] - fts.pegUm) / 1000.0, 1)}"
                g.drawString(label, (px(ox + cx) - g.fontMetrics.stringWidth(label) / 2).toInt(), (py(oy + fb[3]) - 8).toInt())
            }
            val (pcx, pcy) = plate.pegCenter()
            g.font = Font("SansSerif", Font.BOLD, 14); g.color = Color(150, 50, 50)
            g.drawString("peg ${n(fts.pegUm / 1000.0, 1)} mm", (px(ox + pcx + fts.pegUm / 2) + 8).toInt(), (py(oy + pcy) + 5).toInt())
            g.drawString("fit: hole clearance (mm)", (px(ox + fb[0])).toInt(), (py(oy + fb[1]) + 20).toInt())
        }
        // the tubes: each one's number and width below it
        spec.tubes?.let { tbs ->
            g.font = Font("SansSerif", Font.PLAIN, 11); g.color = Color(20, 20, 20)
            for (k in tbs.widthsUm.indices) {
                val bx = plate.tubeBox(k)
                val label = n(tbs.widthsUm[k] / 1000.0, 2)
                g.drawString(label, (px(ox + (bx[0] + bx[2]) / 2) - g.fontMetrics.stringWidth(label) / 2).toInt(), (py(oy + bx[1]) + 18).toInt())
            }
            g.font = Font("SansSerif", Font.BOLD, 13); g.color = Color(120, 90, 30)
            val first = plate.tubeBox(0)
            g.drawString("tubes (1 to ${tbs.widthsUm.size} from the left): line width (mm)", px(ox + first[0]).toInt(), (py(oy + first[3]) - 8).toInt())
        }
        // the stringing ring: each post's number inside the ring, each hop's length in its gap (outside), the open gap marked
        spec.strings?.let { sts ->
            val n0 = sts.posts
            val (rcx, rcy) = (0 until n0).map { plate.ringPost(it) }.let { ps -> ps.sumOf { it.first } / n0 to ps.sumOf { it.second } / n0 }
            g.font = Font("SansSerif", Font.PLAIN, 14); g.color = Color(20, 20, 20)
            for (j in 0 until n0) {
                val (x, y) = plate.ringPost(j); val a = Math.atan2(y - rcy, x - rcx); val r = Math.hypot(x - rcx, y - rcy) - 6_000
                val label = "${j + 1}"
                g.drawString(label, (px(ox + Math.round(rcx + r * Math.cos(a))) - g.fontMetrics.stringWidth(label) / 2).toInt(), (py(oy + Math.round(rcy + r * Math.sin(a))) + 5).toInt())
                val (x2, y2) = plate.ringPost((j + 1) % n0); val ga = Math.atan2((y + y2) / 2 - rcy, (x + x2) / 2 - rcx); val gr = Math.hypot((x + x2) / 2 - rcx, (y + y2) / 2 - rcy) + 5_000
                val gl = if (j == n0 - 1) "open" else n(sts.lengthsMm[j], 1)
                g.color = if (j == n0 - 1) Color(40, 140, 160) else Color(20, 20, 20)
                g.drawString(gl, (px(ox + Math.round(rcx + gr * Math.cos(ga))) - g.fontMetrics.stringWidth(gl) / 2).toInt(), (py(oy + Math.round(rcy + gr * Math.sin(ga))) + 5).toInt())
                g.color = Color(20, 20, 20)
            }
            g.font = Font("SansSerif", Font.BOLD, 13); g.color = Color(40, 140, 160)
            val rb = plate.footprint(Part.STRINGING)
            g.drawString("stringing ring: posts 1-$n0, hops' retraction (mm)", px(ox + rb[0]).toInt(), (py(oy + rb[3]) - 4).toInt())
        }
        // the direction the flow rises, and the post
        g.font = Font("SansSerif", Font.BOLD, 16); g.color = Color(60, 90, 160)
        if (spec.melt != null) g.drawString("melt test: a step per band (band 1 at the front); on each, FAST | MID | SLOW, strips thin (front) to thick (back) on a solid floor; front step: thickness: FAST / MID / SLOW flow", 40, 82)
        g.color = Color(90, 90, 95)
        if (spec.ruler) plate.footprint(Part.POST).let { pb -> g.drawString("post (ruler)", px(ox + pb[0]).toInt() - 10, py(oy + pb[3]).toInt() - 12) }
        // each base's notch: an arrow up into it from below, "item 1"
        if (spec.baseLayers > 0) {
            g.font = Font("SansSerif", Font.BOLD, 13); g.color = Color(200, 60, 40)
            for (p in spec.parts) if (p != Part.POST) {
                val nx = px(ox + plate.notchX(p)); val ny = py(oy + plate.footprint(p)[1])
                g.stroke = BasicStroke(2f); g.draw(Line2D.Double(nx, ny + 16, nx, ny + 2))
                g.drawString("notch: 1", (nx + 5).toInt(), (ny + 16).toInt())
            }
        }
        // the melt plate (no post): the steps from the side, front at the left, heights to scale (stretched)
        if (!spec.ruler && spec.melt != null) {
            stepsFromSide(g, spec, plate)
            g.dispose()
            javax.imageio.ImageIO.write(img, "png", file.toFile())
            return
        }
        // front view of the post: its notches by band
        val fx = 120; val fy = 1000; val fh = 360
        val top = spec.topUm.toDouble()
        val ps = fh / top
        val postW = 220
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString("The post from the front: one notch per band", 40, fy - 30)
        g.color = Color(170, 170, 175); g.fillRect(fx, fy, postW, fh)
        g.font = Font("SansSerif", Font.PLAIN, 15)
        for (band in spec.bands) {
            val nTop = band.fromUm + PlateGeometry.notchFrom + PlateGeometry.notchTall; val nBot = band.fromUm + PlateGeometry.notchFrom
            val nw = PlateGeometry.notchWidth(band).toDouble() / PlateGeometry.post * postW
            val y0 = fy + fh - nTop * ps; val y1 = fy + fh - nBot * ps
            g.color = Color(250, 250, 252)
            g.fillRect((fx + postW / 2 - nw / 2).toInt(), y0.toInt(), nw.toInt(), maxOf(2, (y1 - y0).toInt()))
            // the band itself, and its label
            val b0 = fy + fh - band.readToUm * ps; val b1 = fy + fh - band.fromUm * ps
            g.color = Color(60, 90, 160); g.stroke = BasicStroke(2f)
            g.drawLine(fx + postW + 20, b0.toInt(), fx + postW + 20, b1.toInt())
            g.color = Color.BLACK
            g.drawString("band ${band.index + 1}: ${band.tempC} C, fan ${n(band.fanPct, 0)} %${if ((band.index + 1) % 4 == 0) "  (long notch)" else ""}", fx + postW + 32, ((b0 + b1) / 2 + 5).toInt())
        }
        g.color = Color(90, 90, 95)
        g.drawString("bottom", fx, fy + fh + 22)
        g.font = Font("SansSerif", Font.PLAIN, 14)
        g.drawString("Heights and notch widths to scale (heights stretched); " + (if (spec.bands.any { it.toUm > it.readToUm }) "the gaps between band marks are the settling layers (not read)." else "no layers between the bands."), fx + postW + 32, fy + fh + 22)
        g.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }

    /**
     * The melt plate's steps from the side (front at the left), drawn into the picture's lower part: the base, each band's
     * body, its solid floor, and its strips (each at the floor's top plus its thickness) at their heights, each band's wait
     * (M109) as a dashed line at its first layer, labeled.
     */
    private fun stepsFromSide(g: java.awt.Graphics2D, spec: CalibrationSpec, plate: CalibrationPlate) {
        val h = spec.layerUm
        val fp = plate.footprint(Part.MELT)
        val x0 = 120; val fy = 1000; val fh = 300; val fw = 1100
        val top = spec.topUm.toDouble()
        val sz = fh / top; val sy = fw.toDouble() / (fp[3] - fp[1])
        fun px(y: Long) = x0 + (y - fp[1]) * sy
        fun pz(z: Long) = fy + fh - z * sz
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString("The steps from the side (front at the left; heights stretched ${n(sz / sy, 0)}x): a body, a floor, and strips per band", 40, fy - 40)
        // the base
        g.color = Color(205, 205, 212)
        g.fill(java.awt.geom.Rectangle2D.Double(px(fp[1]), pz(spec.baseLayers * h), px(fp[3]) - px(fp[1]), pz(0) - pz(spec.baseLayers * h)))
        g.font = Font("SansSerif", Font.PLAIN, 14)
        for (bd in spec.bands) {
            val b = plate.meltBox(bd.index, 0); val read = spec.meltReadLayer(bd.index)
            // the body, its floor (the layer under the read layer), then the strips, each at the floor's top plus its thickness
            g.color = Color(240, 200, 160)
            g.fill(java.awt.geom.Rectangle2D.Double(px(b[1]), pz((read - 1) * h), px(b[3]) - px(b[1]), pz(spec.baseLayers * h) - pz((read - 1) * h)))
            g.color = Color(150, 150, 160)
            g.fill(java.awt.geom.Rectangle2D.Double(px(b[1]), pz(read * h), px(b[3]) - px(b[1]), pz((read - 1) * h) - pz(read * h)))
            val mt = spec.melt!!
            g.color = Color(205, 90, 30)
            for (k in 0 until mt.count) {
                val y0 = b[1] + plate.meltLineY(k, 0) - mt.widthUm / 2; val y1 = b[1] + plate.meltLineY(k, mt.lines - 1) + mt.widthUm / 2
                g.fill(java.awt.geom.Rectangle2D.Double(px(y0), pz(read * h + mt.heightsUm[k]), px(y1) - px(y0), pz(read * h) - pz(read * h + mt.heightsUm[k])))
            }
            // the wait: at the band's first layer, across the steps still rising (this band's and those behind)
            g.color = Color(60, 90, 160); g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
            g.draw(Line2D.Double(px(b[1]), pz(bd.fromUm), px(fp[3]), pz(bd.fromUm)))
            g.color = Color(20, 20, 20)
            val label = "${bd.index + 1}: ${bd.tempC} C"
            g.drawString(label, (px((b[1] + b[3]) / 2) - g.fontMetrics.stringWidth(label) / 2).toInt(), (pz(read * h + mt.heightsUm.last()) - 8).toInt())
        }
        // the key, from the specification's bands: each band's wait, its floor, and its strips
        val kx = x0 + fw + 40; var ky = fy - 10
        val mt = spec.melt!!
        g.font = Font("SansSerif", Font.PLAIN, 15); g.color = Color.BLACK
        g.drawString("band: nozzle, wait (M109) before z, floor's top, strips at z", kx, ky)
        for (bd in spec.bands) {
            ky += 22
            val ft = spec.meltReadLayer(bd.index) * h
            g.drawString("${bd.index + 1}: ${bd.tempC} C, wait ${n((bd.fromUm + h) / 1000.0, 2)}, floor ${n(ft / 1000.0, 2)}, strips ${n((ft + mt.heightsUm.first()) / 1000.0, 2)}-${n((ft + mt.heightsUm.last()) / 1000.0, 2)}", kx, ky)
        }
        ky += 30
        g.color = Color(60, 90, 160); g.drawString("dashed: where each band's wait comes (its first layer)", kx, ky)
        g.color = Color(205, 90, 30); g.drawString("dark: the strips, thin (front) to thick (back), the one layer read", kx, ky + 22)
        g.color = Color(150, 150, 160); g.drawString("gray: each step's solid floor", kx, ky + 44)
        g.color = Color(90, 90, 95); g.drawString("base ${spec.baseLayers} layers; band 1's body ${spec.meltReadLayer(0) - spec.baseLayers} layers with its floor", kx, ky + 66)
    }

    private fun n(v: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", v).let { if (d > 0) it.trimEnd('0').trimEnd('.') else it }
}
