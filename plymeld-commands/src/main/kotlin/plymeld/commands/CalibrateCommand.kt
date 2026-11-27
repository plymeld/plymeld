package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.Config
import plymeld.config.ConfigError
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.ModelObject
import plymeld.config.Site
import plymeld.core.calibrate.CalibrationCheck
import plymeld.core.calibrate.CalibrationPlate
import plymeld.core.calibrate.CalibrationSpec
import plymeld.core.calibrate.MeltTerraces
import plymeld.core.calibrate.Part
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateGeometry
import plymeld.model.Resolver
import plymeld.model.SliceSettings
import plymeld.model.ownPressureAdvance
import plymeld.model.pressureAdvanceByTool
import java.util.Locale

/**
 * `plymeld calibrate melt|flow|pressure-advance|first-layer|motion|filament|ironing|seams -cf ... [--filament NAME [--like NAME]] [--machine KEY]
 * [--tool N] [--temps LOW-HIGH] [--step S] -o plate.gcode`: a calibration plate for one filament, which need not be loaded or
 * even in the config, printed exactly as specified. One plate per step of the dependency order ([KINDS]: the melt rate, the
 * flow ratio and pressure advance, the first layer, the motion, then the rest), and the kind is the subcommand, never picked
 * automatically: the melt plate (a column per temperature, a row per flow, run first), the flow plate (a column per
 * temperature, a patch per ratio), the pressure-advance plate (step walls, each at its own value), the first-layer plate (the temperature varied within its one layer), the motion plate (one cross-shaped tower, a
 * speed per face and an acceleration per band, the tower at one temperature), the filament plate (the other tests, the
 * temperature by band), the ironing and the seam plates. Every plate but the melt and flow plates is read back and checked
 * against its specification, with deliberately altered copies as controls that must fail; a plate that fails is not written.
 */
abstract class PlateCommand(name: String, protected val kind: String) : ConfigCommand(name) {
    private val outputOpt by option("-o", "--output", metavar = "FILE", help = "g-code file to write (required for a plate)").path()
    private val output: java.nio.file.Path get() = outputOpt ?: throw IllegalArgumentException("-o is required: the g-code file to write")
    private val filament by option("--filament", metavar = "NAME",
        help = "the filament to calibrate: any the config or plymeld's bundled set defines (e.g. pla-generic), loaded or not; a new one with --like; default: the one the machine's loadout puts on --tool")
    private val like by option("--like", metavar = "NAME", help = "for a --filament the config does not define: start it from this one's values (placeholders until measured)")
    private val toolOpt by option("--tool", metavar = "N", help = "the machine's tool (its index, from 1) to print with (default: 1)").default("1")
    private val plateOpt by option("--plate", metavar = "KEY", help = "the build plate (hardware.plate.<key>): its bed temperature for the filament; plymeld's own are pei and cool; default: the one loaded on the machine (hardware.machine.<key>.loadout.plate), else the only one the config defines itself")
    /** --temps: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val temps: String? get() = null
    /** --step: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val step: String? get() = null
    /** --fans: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val fans: String? get() = null
    /** --tests: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val tests: String? get() = null
    /** --melt-fan: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val meltFan: String? get() = null
    /** --flow-step: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val flowStep: String? get() = null
    private val noLevel by option("--no-level", help = "do not run the machine's bed levelling (hardware.machine.<key>.gcode.level) before the plate").flag()
    /** --overhangs: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val overhangs: String? get() = null
    /** --motion-speeds: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val motionSpeeds: String? get() = null
    /** --motion-accels: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val motionAccels: String? get() = null
    /** --print-temp: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val printTemp: String? get() = null
    /** --peg: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val peg: String? get() = null
    /** --holes: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val holes: String? get() = null
    /** --widths: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val widths: String? get() = null
    /** --retractions: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val retractions: String? get() = null
    /** --spans: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val spans: String? get() = null
    /** --bridge-flows: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val bridgeFlows: String? get() = null
    /** --flow-ratios: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val flowRatios: String? get() = null
    /** --first-speeds: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val firstSpeeds: String? get() = null
    /** --first-heights: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val firstHeights: String? get() = null
    // the ironing plate's spreads: each centered on the filament's
    // current ironing value (plan.technique.ironing.* as resolved for the filament and tool); the spreads and the step counts are
    // placeholders, not measured
    /** --ironing-flows: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val ironingFlows: String? get() = null
    /** --ironing-spacings: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val ironingSpacings: String? get() = null
    /** --ironing-speeds: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val ironingSpeeds: String? get() = null
    /** --ironing-angles: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val ironingAngles: String? get() = null
    /** --ironing-insets: a plate of the kinds that take it declares the option; null for the rest. */
    protected open val ironingInsets: String? get() = null
    /** The pressure-advance plate's options (--values, --bracket, --confirm, --confirm-step, --speeds); null for the rest. */
    protected open val paValues: String? get() = null
    protected open val paBracket: String? get() = null
    protected open val paConfirm: String? get() = null
    protected open val paConfirmStep: String? get() = null
    protected open val paSpeeds: String? get() = null


    override fun run() {
        if (currentContext.invokedSubcommand != null) return
        when (kind) { "melt" -> runMelt(); "flow" -> runFlow(); "first-layer" -> runFirstLayer(); "ironing" -> runIroning(); "seams" -> runSeams(); "motion" -> runMotion(); "pressure-advance" -> runPressureAdvance(); else -> runBanded(kind) }
    }

    /**
     * A plate's candidate columns, one per temperature, coolest first: --temps and --step as given ([given]), else the
     * filament's range (its label's) at 10 C steps; with the filament's own print temperature, [printC] (null: it has none).
     */
    private class Columns(val all: List<Int>, val lowC: Int, val highC: Int, val stepC: Int, val printC: Int?, val given: Boolean)
    private fun columnTemps(toolN: Int): Columns {
        val stepC = whole((step ?: "10").toDoubleOrNull() ?: throw UsageError("--step is a whole number of degrees (got '$step')"), "--step")
        if (stepC < 1) throw UsageError("--step is at least 1 degree (got $stepC)")
        // the filament as the config has it: its range and its own print temperature
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        fun plainC(key: String) = (plainMt?.get(key)?.value as Number?)?.toDouble()
        val printC = plainC("hardware.filament.temperature.print")?.let { whole(it, "the filament's temperature.print") }
        val (lowC, highC) = temps?.let { range(it, "--temps").let { (a, b) -> whole(a, "--temps") to whole(b, "--temps") } } ?: run {
            val lo = plainC("hardware.filament.temperature.min"); val hi = plainC("hardware.filament.temperature.max")
            if (lo == null || hi == null) throw IllegalArgumentException("which temperatures? the filament has no range (temperature.min / temperature.max, the spool's label): give --temps LOW-HIGH")
            whole(lo, "the filament's temperature.min") to whole(hi, "the filament's temperature.max")
        }
        if (highC < lowC) throw UsageError("--temps runs low to high (got $lowC-$highC)")
        return Columns(generateSequence(lowC) { it + stepC }.takeWhile { it <= highC }.toList(), lowC, highC, stepC, printC, temps != null || step != null)
    }

    /**
     * The columns a plate prints: all of [c] where they [fits]; else, unless they were given (then [tooBig] refuses), as many
     * neighbors as fit, centered on the print temperature (the middle of the range without one), and a line saying so.
     */
    private fun fitColumns(c: Columns, fits: (List<Int>) -> Boolean, tooBig: (List<Int>) -> Nothing): Pair<List<Int>, String?> {
        val all = c.all
        if (fits(all)) return all to null
        if (c.given || all.size == 1) tooBig(all)
        val center = all.indices.minBy { Math.abs(all[it] - (c.printC ?: (c.lowC + c.highC) / 2)) }
        val most = (all.size - 1 downTo 1).firstOrNull { k -> val from = (center - k / 2).coerceIn(0, all.size - k); fits(all.subList(from, from + k)) } ?: tooBig(all.subList(center, center + 1))
        val from = (center - most / 2).coerceIn(0, all.size - most)
        return all.subList(from, from + most) to "the range ${c.lowC}-${c.highC} C at ${c.stepC} C steps (${all.size} temperatures) does not fit the bed: " +
            "the $most nearest ${c.printC?.let { "the print temperature, $it C" } ?: "its middle"}; --temps and --step choose others"
    }

    /**
     * The settings at the filament's own temperatures, for a plate of columns: the start template heats to its first-layer
     * temperature, and the base prints at its own; the plate sets each column's temperature itself.
     */
    private fun ownSettings(toolN: Int): Triple<plymeld.config.Config, plymeld.model.SliceSettings, plymeld.model.Setup> {
        val (config, _) = plateConfig(toolN, null)
        val setup = try { ConfigSetup.setup(config).setup } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        return Triple(config, SliceSettings.of(setup, plymeld.model.Context(0)), setup)
    }

    /**
     * The melt plate ([plymeld.core.calibrate.MeltPlate]): a column per temperature, coolest first, a row per flow. The
     * temperatures: --temps and --step as given (refused when they do not fit the bed); else the filament's range (its
     * label's) at 10 C steps, or, where those do not fit, as many neighbors as fit, centered on its print temperature (said
     * so). The rows: from half the filament's expected maximum flow to twice it ([plymeld.core.calibrate.MeltSpec.flows]),
     * each the same bead (the plate's line width, the tool's thickest layer), a row the machine's print speed cannot reach
     * left out (said so). A row's line as long as fits the columns across the bed, up to the old surface's 60 mm.
     */
    private fun runMelt() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val flowStepV = flowStep?.let { it.trim().removeSuffix("mm3/s").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--flow-step is mm3/s above 0, e.g. 0.5 (got '$it')") }
        val fanOff = when (meltFan?.trim()) { null, "on" -> false; "off" -> true; else -> throw UsageError("--melt-fan is 'on' (the filament's own fan, the default) or 'off' (got '$meltFan')") }
        val g = plymeld.core.calibrate.MeltGeometry
        val cols = columnTemps(toolN)
        val (config, s0, setup0) = ownSettings(toolN)
        fun toolMm(key: String) = (Resolver.resolveOrNull(setup0, key, plymeld.model.Context(0))?.value as Number?)?.toDouble()
        // every row the same bead: the plate's line width, the tool's thickest layer (the fattest bead the tool lays, so a row
        // reaches its flow at the lowest speed)
        val readUm = Math.round((toolMm("hardware.tool.layer.height.max") ?: throw IllegalArgumentException("the melt plate's rows are as thick as the tool lays (tool layer.height.max), which the config does not give")) * 1000)
        val h = s0.layerHeightUm; val w = s0.outerWallWidthUm
        // the rows probe the limit itself: around the maximum flow as the config has it, not less the melt buffer
        val expected = s0.maxVolumetricMeasured
        val bead = plymeld.core.calibrate.MeltSpec.bead(w, readUm)
        // the rows: half to twice the expected flow; those the machine's print speed cannot reach at this bead left out
        val allFlows = plymeld.core.calibrate.MeltSpec.flows(expected, flowStepV)
        val reach = s0.hiddenSpeed * bead * s0.flowRatio
        val flows = allFlows.filter { it <= reach + 1e-9 }
        if (flows.size < 2) throw IllegalArgumentException("the machine's print speed (${n(s0.hiddenSpeed)} mm/s, hardware.machine.<key>.motion.print.speed) lays at most ${n(reach)} mm3/s at this plate's line: too few rows to read")
        val cut = if (flows.size < allFlows.size) "the rows above ${n(flows.last())} mm3/s are left out: the machine's print speed (${n(s0.hiddenSpeed)} mm/s) lays at most ${n(reach)} mm3/s at this line, so a reading clean to the last row means at least that" else null
        val level = s0.levelGcode?.takeIf { !noLevel }
        val firstH = s0.firstLayerHeightUm; val fhm = firstH / 1000.0
        // the base's first layer: the first-layer line width at the first-layer speed, held to the filament's flow (as every plate's)
        val baseW = s0.firstLayerWidthUm
        val firstSpeed = Math.min(s0.firstLayerSpeed, s0.maxVolumetric / (fhm * (baseW / 1000.0 - fhm * (1 - Math.PI / 4)) * s0.flowRatio))
        fun spec(tempsC: List<Int>, lineUm: Long, ox: Long = 0, oy: Long = 0, tool: Int = 0, spool: String = "") = plymeld.core.calibrate.MeltSpec(
            tool = tool, spool = spool, tempsC = tempsC.toIntArray(), flows = flows.toDoubleArray(),
            baseFirstC = s0.nozzleTempFirstC, basePrintC = s0.nozzleTempC, fanPct = if (fanOff) 0.0 else s0.fanMinPct,
            layerUm = h, firstLayerUm = firstH, widthUm = w, firstWidthUm = baseW, readUm = readUm, lineUm = lineUm,
            holdS = PlateGeometry.meltHoldS, filamentDiameterMm = s0.filamentDiameterMm, flowRatio = s0.flowRatio, accel = s0.defaultAccel,
            adhesion = PathValues(firstSpeed, s0.defaultAccel, s0.flowRatio, s0.retractMm), frame = PathValues(s0.hiddenSpeed, s0.defaultAccel, s0.flowRatio, s0.retractMm),
            frameCap = s0.maxVolumetric, liftUm = s0.zHopUm, originXUm = ox, originYUm = oy, levelGcode = level)
        // the bed, less the head's parking place in front (counted on both sides of the center)
        fun bed(k: String) = (Resolver.resolve(setup0, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val park = g.parkFront / 1000.0
        // a row's line for [tempsC] columns: as long as fits them across the bed with PlateGeometry.bedSpare to spare at each
        // edge (the width is linear in it), at most lineMax
        fun lineFor(tempsC: List<Int>): Long {
            val b = plymeld.core.calibrate.MeltPlate(spec(tempsC, g.lineMax)).bounds()
            val over = (b[2] - b[0]) - (Math.round(bedW * 1000) - 2 * PlateGeometry.bedSpare)
            return if (over <= 0) g.lineMax else g.lineMax - (over + tempsC.size - 1) / tempsC.size
        }
        fun fits(tempsC: List<Int>): Boolean {
            val line = lineFor(tempsC); if (line < g.lineMin) return false
            val b = plymeld.core.calibrate.MeltPlate(spec(tempsC, line)).bounds()
            return (b[2] - b[0]) / 1000.0 <= bedW && (b[3] - b[1]) / 1000.0 + 2 * park <= bedD
        }
        fun tooBig(tempsC: List<Int>): Nothing {
            val b = plymeld.core.calibrate.MeltPlate(spec(tempsC, maxOf(g.lineMin, lineFor(tempsC)))).bounds()
            val deep = (b[3] - b[1]) / 1000.0 + 2 * park > bedD
            throw IllegalArgumentException("the melt plate does not fit this machine: ${tempsC.size} temperatures and ${flows.size} rows make it ${n((b[2] - b[0]) / 1000.0)} x ${n((b[3] - b[1]) / 1000.0)} mm " +
                "(and ${n(park)} mm in front for the head to park); the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build): " +
                if (deep) "fewer rows (a larger --flow-step)" else "fewer temperatures (--temps, --step)")
        }
        val (chosen, narrowed) = fitColumns(cols, ::fits, ::tooBig)
        val line = lineFor(chosen)
        val s = s0; val setup = setup0
        val spool = setup.spool(setup.objects[0].spool)
        val draft = plymeld.core.calibrate.MeltPlate(spec(chosen, line)).bounds()
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val sp = spec(chosen, line, cx - (draft[0] + draft[2]) / 2, cy - (draft[1] + draft[3]) / 2, spool.tool, spool.id)
        val plate = plymeld.core.calibrate.MeltPlate(sp)
        val pa = setup.pressureAdvanceByTool()
        val text = plate.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val prediction = plymeld.core.Predict.gcode(text, s.machineDescription, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        val b = plate.bounds()
        val apart = n(flows[1] - flows[0])
        echo("melt plate: '${spool.material}' on tool ${toolN} of '${Resolver.resolve(setup, "hardware.machine.model").value}' (bed ${s.bedTempFirstC} C)")
        echo("  base and floor at the filament's own temperatures: ${s.nozzleTempFirstC} C on the first layer (temperature.first_layer), then ${s.nozzleTempC} C (temperature.print); " +
            "the first layer ${n(baseW / 1000.0)} mm lines at ${n(firstSpeed)} mm/s")
        echo("  columns: ${chosen.joinToString(", ")} C, coolest on the left, each waited for before its rows")
        narrowed?.let { echo("  $it") }
        echo("  rows: ${n(flows.first())} to ${n(flows.last())} mm3/s, $apart apart (the filament's expected maximum flow: ${n(expected)}), lowest at the front; " +
            "every row ${n(w / 1000.0)} mm lines ${n(readUm / 1000.0)} mm thick, ${n(line / 1000.0)} mm long, at ${n(sp.speed(0))} to ${n(sp.speed(sp.rows - 1))} mm/s, each holding its flow at least ${n(PlateGeometry.meltHoldS)} s")
        cut?.let { echo("  $it") }
        echo("  ${n((b[2] - b[0]) / 1000.0)} x ${n((b[3] - b[1]) / 1000.0)} mm, ${g.baseLayers + 2} layers; predicted ${prediction.timeS?.let { n(it / 60) + " min" } ?: "time not predicted"}")
        echo("-> $output")
    }

    /**
     * The flow plate ([plymeld.core.calibrate.FlowPlate]): a column per temperature, coolest first (chosen as the melt
     * plate's are), a patch per flow ratio (--flow-ratios, else the filament's ratio x 0.94 to 1.06). The patches at a
     * visible surface's speed and acceleration, held so the richest stays within the filament's maximum flow; only their top
     * layer at the column's temperature.
     */
    private fun runFlow() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val cols = columnTemps(toolN)
        val (config, s, setup) = ownSettings(toolN)
        val ratios = flowRatios?.let { f -> f.split(',').map { it.trim().toDoubleOrNull()?.takeIf { r -> r > 0 } ?: throw UsageError("--flow-ratios is ratios above 0, low to high, e.g. 0.94,0.97,1 (got '$f')") }
            .also { r -> if (r.size < 2 || r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--flow-ratios is at least two ratios, rising, low to high (got '$f')") } }
            ?: plymeld.core.calibrate.FlowSpec.defaultRatios(s.flowRatio)
        val g = plymeld.core.calibrate.FlowGeometry
        val h = s.layerHeightUm; val w = s.outerWallWidthUm
        val bead = plymeld.core.calibrate.MeltSpec.bead(w, h)
        // the patches: a visible surface's speed, held so the richest patch stays within the filament's maximum flow
        val speed = Math.min(s.visibleSpeed, s.maxVolumetric / (bead * ratios.max()))
        val firstH = s.firstLayerHeightUm; val fhm = firstH / 1000.0
        val baseW = s.firstLayerWidthUm
        val firstSpeed = Math.min(s.firstLayerSpeed, s.maxVolumetric / (fhm * (baseW / 1000.0 - fhm * (1 - Math.PI / 4)) * s.flowRatio))
        val frameSpeed = Math.min(s.hiddenSpeed, s.maxVolumetric / (bead * s.flowRatio))
        val level = s.levelGcode?.takeIf { !noLevel }
        val solid = plymeld.core.calibrate.FlowSpec.solidLayersFor(h)
        fun spec(tempsC: List<Int>, ox: Long = 0, oy: Long = 0, tool: Int = 0, spool: String = "") = plymeld.core.calibrate.FlowSpec(
            tool = tool, spool = spool, tempsC = tempsC.toIntArray(), ratios = ratios.toDoubleArray(),
            baseFirstC = s.nozzleTempFirstC, basePrintC = s.nozzleTempC, fanPct = s.fanMinPct,
            layerUm = h, firstLayerUm = firstH, widthUm = w, firstWidthUm = baseW, solidLayers = solid, speed = speed, accel = s.visibleAccel,
            adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm), frame = PathValues(frameSpeed, s.defaultAccel, s.flowRatio, s.retractMm),
            liftUm = s.zHopUm, originXUm = ox, originYUm = oy, levelGcode = level)
        // the bed, less the head's parking place in front (counted on both sides of the center), and PlateGeometry.bedSpare at
        // each side
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val park = g.parkFront / 1000.0
        fun size(tempsC: List<Int>) = plymeld.core.calibrate.FlowPlate(spec(tempsC)).bounds().let { (it[2] - it[0]) / 1000.0 to (it[3] - it[1]) / 1000.0 }
        fun fits(tempsC: List<Int>): Boolean = size(tempsC).let { (pw, pd) -> pw <= bedW - 2 * PlateGeometry.bedSpare / 1000.0 && pd + 2 * park <= bedD }
        fun tooBig(tempsC: List<Int>): Nothing {
            val (pw, pd) = size(tempsC)
            throw IllegalArgumentException("the flow plate does not fit this machine: ${tempsC.size} temperatures and ${ratios.size} ratios make it ${n(pw)} x ${n(pd)} mm " +
                "(and ${n(park)} mm in front for the head to park); the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build): " +
                if (pd + 2 * park > bedD) "fewer ratios (--flow-ratios)" else "fewer temperatures (--temps, --step)")
        }
        val (chosen, narrowed) = fitColumns(cols, ::fits, ::tooBig)
        val spool = setup.spool(setup.objects[0].spool)
        val draft = plymeld.core.calibrate.FlowPlate(spec(chosen)).bounds()
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val sp = spec(chosen, cx - (draft[0] + draft[2]) / 2, cy - (draft[1] + draft[3]) / 2, spool.tool, spool.id)
        val plate = plymeld.core.calibrate.FlowPlate(sp)
        val text = plate.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, setup.pressureAdvanceByTool())
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val prediction = plymeld.core.Predict.gcode(text, s.machineDescription, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        val b = plate.bounds()
        echo("flow plate: '${spool.material}' on tool $toolN of '${Resolver.resolve(setup, "hardware.machine.model").value}' (bed ${s.bedTempFirstC} C)")
        echo("  base at the filament's own temperatures: ${s.nozzleTempFirstC} C on the first layer (temperature.first_layer), then ${s.nozzleTempC} C (temperature.print); " +
            "the first layer ${n(baseW / 1000.0)} mm lines at ${n(firstSpeed)} mm/s")
        echo("  columns: ${chosen.joinToString(", ")} C, coolest on the left; each column's top layer waited for at its temperature, the ${solid} layers under it at ${s.nozzleTempC} C")
        narrowed?.let { echo("  $it") }
        echo("  patches: flow ratios ${ratios.joinToString(", ") { n(it) }}, leanest at the front (the filament's own: ${n(s.flowRatio)}); " +
            "each ${n(plate.patchSize / 1000.0)} mm square, ${n(w / 1000.0)} mm lines ${n(plate.spacing / 1000.0)} mm apart (the bead spacing), ${solid + 1} layers of ${n(h / 1000.0)} mm, " +
            "at ${n(speed)} mm/s${if (speed < s.visibleSpeed - 1e-9) " (a visible surface's ${n(s.visibleSpeed)}, held to the filament's maximum flow at ${n(ratios.max())})" else " (a visible surface's)"}")
        echo("  ${n((b[2] - b[0]) / 1000.0)} x ${n((b[3] - b[1]) / 1000.0)} mm, ${plate.topLayer + 1} layers; predicted ${prediction.timeS?.let { n(it / 60) + " min" } ?: "time not predicted"} (the temperature waits on top)")
        echo("-> $output")
    }

    /**
     * A plate made by band of height: [kind] "melt" (the terraced melt test
     * alone, a short step per temperature) or "filament" (the filament's other families, `--tests`, the temperature by band).
     */
    private fun runBanded(kind: String) {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val stepC = whole((step ?: "5").toDoubleOrNull() ?: throw UsageError("--step is a whole number of degrees (got '$step')"), "--step")
        // the families on the plate: the melt plate one; the filament plate's given (--tests), or all of them
        // (once the melt rate is known, the filament and temperature tests share one plate). --tests is the way to leave one out; the families' own options set their values, and are refused for a
        // family that is not on the plate
        val selected: Set<Part> = when (kind) {
            "melt" -> setOf(Part.MELT)
            else -> tests?.let { v ->
                v.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { key ->
                    when (key) {
                        Part.MELT.key -> throw UsageError("--tests: the melt test is a plate of its own, run first: `plymeld calibrate melt`")
                        "motion" -> throw UsageError("--tests: the motion test is a plate of its own: `plymeld calibrate motion`")
                        else -> Part.FILAMENT_TESTS.firstOrNull { it.key == key } ?: throw UsageError("--tests: no family '$key' (the filament plate's: ${Part.FILAMENT_TESTS.joinToString(",") { it.key }})")
                    }
                }.toSet().also { if (it.isEmpty()) throw UsageError("--tests names no family (the filament plate's: ${Part.FILAMENT_TESTS.joinToString(",") { it.key }})") }
            } ?: Part.FILAMENT_TESTS.toSet()
        }
        // the melt plate's fan: the filament's own in every band (the default), or off as an option
        val meltFanOff = when (meltFan?.trim()) { null, "on" -> false; "off" -> true; else -> throw UsageError("--melt-fan is 'on' (the filament's own fan, the default) or 'off' (got '$meltFan')") }
        if (kind == "filament") {
            val valueOptions = listOf(Part.OVERHANGS to listOf("--overhangs" to overhangs),
                Part.BRIDGES to listOf("--spans" to spans, "--bridge-flows" to bridgeFlows), Part.STRINGING to listOf("--retractions" to retractions),
                Part.LINE_WIDTH to listOf("--widths" to widths), Part.FIT to listOf("--peg" to peg, "--holes" to holes, "--print-temp" to printTemp))
            for ((p, opts) in valueOptions) for ((o, v) in opts) {
                if (v == null) continue
                if (v.trim() == "none") throw UsageError("$o none: to leave the ${p.title} out, list the families you want with --tests, without '${p.key}' (e.g. --tests ${Part.FILAMENT_TESTS.filter { it != p }.joinToString(",") { it.key }})")
                if (p !in selected) throw UsageError("$o: the ${p.title} are not on this plate (--tests ${selected.sortedBy { Part.FILAMENT_TESTS.indexOf(it) }.joinToString(",") { it.key }}): add '${p.key}' to --tests, or leave $o out")
            }
        }
        // the filament as the config has it (before the plate sets its temperatures to the first band's): its range and
        // its own print temperature
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        // the filament's own print temperature: given (--print-temp), or its temperature.print; null = it has none
        val printC: Int? = printTemp?.let { whole(it.trim().removeSuffix("C").trim().toDoubleOrNull() ?: throw UsageError("--print-temp is whole degrees C (got '$it')"), "--print-temp") }
            ?: ((plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { whole(it, "the filament's temperature.print") })
        // the range: given, or the filament's own (the spool's label)
        val (lowC, highC) = temps?.let { range(it, "--temps").let { (a, b) -> whole(a, "--temps") to whole(b, "--temps") } } ?: run {
            val sp = plain.spool(plain.objects[0].spool)
            val lo = (plainMt?.get("hardware.filament.temperature.min")?.value as Number?)?.toDouble(); val hi = (plainMt?.get("hardware.filament.temperature.max")?.value as Number?)?.toDouble()
            if (lo == null || hi == null) throw IllegalArgumentException("which temperatures? '${sp.material}' has no range (temperature.min / temperature.max, the spool's label): give --temps LOW-HIGH")
            whole(lo, "the filament's temperature.min") to whole(hi, "the filament's temperature.max")
        }
        // hot to cold: the first band, and the start template's temperature, is the hottest
        val (config, slotN) = plateConfig(toolN, highC)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val h = s.layerHeightUm; val w = s.outerWallWidthUm
        val bead = h / 1000.0 * (w / 1000.0 - h / 1000.0 * (1 - Math.PI / 4))
        // the filament plate's two fan levels: given, or bracketing the filament's fan, half of it and half again (at most
        // 100 %), so its own value lies between the two the plate reads and plymeld interpolates rather than extrapolates. The
        // melt plate: one level, the filament's own fan or off, in every band
        val fanPair = when (kind) {
            "melt" -> (if (meltFanOff) 0.0 else s.fanMinPct).let { it to it }
            else -> fans?.let { f -> f.split(',').map { it.trim().removeSuffix("%").toDoubleOrNull() ?: throw UsageError("--fans is two percentages, e.g. 50,100 (got '$f')") }
                .also { if (it.size != 2) throw UsageError("--fans is two percentages, e.g. 50,100 (got '$f')") }.let { it[0] to it[1] } } ?: (s.fanMinPct / 2 to Math.min(100.0, s.fanMinPct * 1.5))
        }
        // heights: the bases (3 layers under every family; the bands above them), then by kind:
        //  - filament: 2.4 mm read per band (the design's) and 4 settling layers: two heating toward the next band, a wait, two
        //    at its temperature (CalibrationPlate);
        //  - melt: band 1 a 1 mm body and its read layer, each later band two layers (its wait before the
        //    first, a settling layer, its read layer: CalibrationSpec.meltBands)
        val baseLayers = PlateGeometry.baseLayers
        val readUm = Math.round(2400.0 / h) * h; val settleUm = 4 * h
        fun bandsFrom(startUm: Long): List<plymeld.core.calibrate.Band> = try {
            when (kind) {
                "melt" -> CalibrationSpec.meltBands(lowC, highC, stepC, fanPair.first, startUm, h)
                else -> CalibrationSpec.bands(lowC, highC, stepC, fanPair, startUm, readUm, settleUm)
            }
        } catch (e: IllegalStateException) { throw IllegalArgumentException(e.message ?: "--temps, --step") } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "--temps, --step") }
        val bands = bandsFrom(baseLayers * h)
        val hm = h / 1000.0
        // the terraced melt test (a solid floor, the strips by thickness): every strip the plate's line width, its
        // thicknesses from the tool's layer.height.max down a tenth of the bore at a time (plymeld's choice), refused where the thinnest would be below the tool's layer.height.min. FAST centered on the
        // filament's expected maximum flow at the hottest band (plymeld's choice, so the rates
        // tested follow the filament's defaults): one speed for every band, so a strip's flow is the same at every
        // temperature and the steps differ by temperature alone; the hottest band's flow because the start template and the
        // first band print at it (the config's melt.flow.max at that temperature). MID half, SLOW a quarter;
        // lines a strip by the hold rule, at least ten; the machine's acceleration; the travel lift over the strips
        fun toolMm(key: String) = (Resolver.resolveOrNull(setup, key, plymeld.model.Context(0))?.value as Number?)?.toDouble()
        val meltSpec = if (Part.MELT !in selected) null else {
            val maxUm = Math.round((toolMm("hardware.tool.layer.height.max") ?: throw IllegalArgumentException("the melt test's strips are as thick as the tool lays (tool layer.height.max), which the config does not give")) * 1000)
            val minUm = toolMm("hardware.tool.layer.height.min")?.let { Math.round(it * 1000) }
            val hs = MeltTerraces.heights(maxUm, s.nozzleDiameterUm)
            if (hs.first() <= 0 || (minUm != null && hs.first() < minUm)) throw IllegalArgumentException("the melt test's thinnest strip would be ${n(hs.first() / 1000.0)} mm " +
                "(${MeltTerraces.COUNT} strips from the tool's layer.height.max ${n(maxUm / 1000.0)} mm down ${n(MeltTerraces.STEP_OF_BORE * s.nozzleDiameterUm / 1000.0)} mm at a time), " +
                "below the tool's layer.height.min${minUm?.let { " (" + n(it / 1000.0) + " mm)" } ?: ""}: this nozzle cannot lay the plate")
            if (maxUm > 2 * h) throw IllegalArgumentException("the melt test's thickest strip (${n(maxUm / 1000.0)} mm, the tool's layer.height.max) would stand above the next layer at ${n(h / 1000.0)} mm layers: use layers at least ${n(maxUm / 2000.0)} mm")
            val fast = MeltTerraces.fastSpeed(s.hiddenSpeed, s.maxVolumetric, w, maxUm, s.nozzleDiameterUm, s.flowRatio)
            MeltTerraces(fast, s.hiddenSpeed, s.maxVolumetric, s.nozzleDiameterUm, w, maxUm, MeltTerraces.linesFor(fast), s.defaultAccel, s.zHopUm)
        }
        // the frame (the post, the bases above their first layer, the melt bodies, the pedestals and piers, the marker): read for nothing, so at the machine's print speed (hardware.machine.motion.print.speed) and acceleration
        // (motion.print.acceleration), not the visible-line cap; held to the
        // filament's maximum flow for its lines (a physical limit, not a preference: every move's flow within it)
        val meltCap = s.maxVolumetric / (bead * s.flowRatio)
        val frameSpeed = Math.min(s.hiddenSpeed, meltCap)
        val frameAccel = s.defaultAccel
        val frameHeld = meltCap < s.hiddenSpeed
        // the tests that print at a visible surface's speed and acceleration (the overhang ledges without an hardware.filament.overhang.speed,
        // the stringing ring, the fit test): their condition, read with the result, unchanged; held to the same
        // maximum flow
        val surfaceSpeed = Math.min(s.visibleSpeed, meltCap)
        // the overhang ledges: given, or the filament's share x 0.7 to 1.9 (placeholders, centered on its value; a share of
        // 1 or more is a bridge, not an overhang); at its overhang speed if it has one, else a visible surface's
        val shareList = if (Part.OVERHANGS !in selected) null else when {
            overhangs != null -> overhangs!!.split(',').map { it.trim().removeSuffix("%").toDoubleOrNull()?.let { v -> if (it.trim().endsWith("%")) v / 100 else v }?.takeIf { v -> v > 0 && v < 1 }
                ?: throw UsageError("--overhangs is shares of a line between 0 and 1, low to high, e.g. 0.3,0.5,0.7 (got '$overhangs')") }
                .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--overhangs must rise, low to high (got '$overhangs')") }
            else -> listOf(0.7, 1.0, 1.3, 1.6, 1.9).map { Math.round(s.overhang.share * it * 100) / 100.0 }.filter { it < 1.0 }.distinct()
        }
        val ledges = shareList?.let { plymeld.core.calibrate.Ledges(it.toDoubleArray(), minOf(s.overhangSpeed ?: Double.MAX_VALUE, surfaceSpeed), s.visibleAccel) }
        // the bridges: given spans, or 8 to 32 mm in 6 mm steps (placeholders: no setting holds a span yet); the decks at
        // the bridge flow and 0.85x it; at the bridge speed, held so the richer deck stays within the maximum flow
        val spanList = if (Part.BRIDGES !in selected) null else when {
            spans != null -> spans!!.split(',').map { it.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { v -> v >= 1 } ?: throw UsageError("--spans is spans in mm, at least 1, short to long, e.g. 8,14,20 (got '$spans')") }
                .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--spans must rise, short to long (got '$spans')") }
            else -> listOf(8.0, 14.0, 20.0, 26.0, 32.0)
        }
        val deckFlows = bridgeFlows?.let { f -> f.split(',').map { it.trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--bridge-flows is two flow ratios, e.g. 1,0.85 (got '$f')") }
            .also { if (it.size != 2) throw UsageError("--bridge-flows is two flow ratios, e.g. 1,0.85 (got '$f')") } } ?: listOf(s.bridgeFlow, Math.round(s.bridgeFlow * 0.85 * 1000) / 1000.0)
        val bridges = spanList?.let { sl -> plymeld.core.calibrate.Bridges(sl.map { Math.round(it * 1000) }.toLongArray(), deckFlows.toDoubleArray(),
            Math.min(s.bridgeSpeed, s.maxVolumetric / (bead * deckFlows.max())), s.defaultAccel) }
        // the stringing ring: given lengths, or 0.4 to 1.6 mm in 0.2 steps (the user's), a hop each;
        // at a visible surface's speed; the retraction speed, travel, and lift the filament's and tool's own
        val lengthList = if (Part.STRINGING !in selected) null else when {
            retractions != null -> retractions!!.split(',').map { it.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--retractions is lengths in mm above 0, short to long, e.g. 0.4,0.8,1.2 (got '$retractions')") }
                .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--retractions must rise, short to long (got '$retractions')") }
            else -> plymeld.core.calibrate.Strings.defaultLengths()
        }
        val strings = lengthList?.let { plymeld.core.calibrate.Strings(it.toDoubleArray(), surfaceSpeed, s.visibleAccel) }
        // the line-width tubes (0.25 to 0.75 mm in 0.05 steps on a 0.4 mm nozzle, relative to the nozzle): given widths, or the bore x 0.625 to 1.875 in steps of 0.125, to 0.01 mm; the plate's full height; at a
        // visible surface's speed, held so the widest stays within the filament's maximum flow
        val widthList = if (Part.LINE_WIDTH !in selected) null else when {
            widths != null -> widths!!.split(',').map { it.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--widths is line widths in mm above 0, narrow to wide, e.g. 0.35,0.45,0.55 (got '$widths')") }
                .map { Math.round(it * 1000) }.also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--widths must rise, narrow to wide (got '$widths')") }
            else -> plymeld.core.calibrate.Tubes.defaultWidths(s.nozzleDiameterUm)
        }
        val tubes = widthList?.let { wl -> plymeld.core.calibrate.Tubes(wl.toLongArray(), Math.min(s.visibleSpeed, s.maxVolumetric / (hm * (wl.max() / 1000.0 - hm * (1 - Math.PI / 4)) * s.flowRatio)),
            s.visibleAccel, (bands.last().readToUm / h).toInt()) }
        // the fit test, in the band nearest the filament's own print temperature (not band 1's, the hottest: a fit is read
        // at the temperature parts print at), on a pedestal: a peg (5 mm unless given: a placeholder) and holes from
        // its size up in 0.1 mm steps; the block filling that band's read layers on a pedestal, the peg a column up to the
        // block's top; at a visible surface's speed and acceleration (the frame's until the frame left them)
        val ownC = if (Part.FIT !in selected) null else printC
            ?: throw IllegalArgumentException("the fit test prints in the band nearest the filament's own temperature (temperature.print), which it has none of: give --print-temp")
        val ownBand = ownC?.let { c -> bands.minBy { Math.abs(it.tempC - c) } }
        val fit = if (ownBand == null) null else {
            val ownFirst = (ownBand.fromUm / h).toInt()                    // the band's first read layer
            val ownLast = (ownBand.readToUm / h).toInt() - 1               // and its last
            val pegUm = peg?.let { p -> Math.round((p.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { it >= 2 } ?: throw UsageError("--peg is a diameter in mm, at least 2 (got '$p')")) * 1000) } ?: 5_000L
            val hl = holes?.let { hs -> hs.split(',').map { Math.round((it.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--holes is diameters in mm, small to large, e.g. 5,5.1,5.2 (got '$holes')")) * 1000) }
                .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--holes must rise, small to large (got '$holes')") } } ?: (0..4).map { pegUm + it * 100L }
            plymeld.core.calibrate.Fit(pegUm, hl.toLongArray(), surfaceSpeed, s.visibleAccel, ownLast - ownFirst + 1, ownLast + 1, fromLayer = ownFirst)
        }
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val level = s.levelGcode?.takeIf { !noLevel }
        // the room the parts are packed into: the bed, less the head's parking place in front (counted on both sides of the center)
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val park = PlateGeometry.parkFront / 1000.0
        // the first layer: the configured first-layer speed, held to the filament's maximum flow for its widest line (its own
        // width, or the widest line-width tube's, which lays its own width there too): every move of a plate within the
        // filament's maximum flow (a 3.5 mm3/s TPU's first layer ran 7.3 mm3/s at 80 mm/s before)
        val firstWidest = maxOf(s.firstLayerWidthUm, tubes?.widthsUm?.max() ?: 0L)
        // the first layer's height: a first-layer window's choice, else the setting (the plate's layers above it are h); its
        // speed held to the maximum flow at THAT thickness
        val firstH = s.firstLayerHeightUm; val fhm = firstH / 1000.0
        val firstFrom = plymeld.model.Resolver.resolve(setup, "plan.mechanism.adhesion.first_layer.height", plymeld.model.Context()).from
        val firstSpeed = Math.min(s.firstLayerSpeed, s.maxVolumetric / (fhm * (firstWidest / 1000.0 - fhm * (1 - Math.PI / 4)) * s.flowRatio))
        val undrafted = CalibrationSpec(spool.tool, spool.id, bands, h, w, s.filamentDiameterMm,
            adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm),
            frame = PathValues(frameSpeed, frameAccel, s.flowRatio, s.retractMm),
            melt = null, flowRatio = s.flowRatio, retractMm = s.retractMm, originXUm = 0, originYUm = 0, firstWidthUm = s.firstLayerWidthUm,
            minLayerTimeS = s.slowDownLayerTimeS, travelSpeed = s.travelSpeed, levelGcode = level,
            ledges = ledges, bridges = bridges, strings = strings, tubes = tubes, fit = fit,
            packWidthUm = Math.round(bedW * 1000), packDepthUm = Math.round((bedD - 2 * park) * 1000), baseLayers = baseLayers, firstLayerUm = firstH,
            // the melt plate has no ruler post: its steps mark the bands (plymeld's choice; CalibrationSpec.ruler)
            ruler = kind != "melt")
        val melt = meltSpec
        val draft = undrafted.withMelt(melt)
        val draftPlate = CalibrationPlate(draft)
        val b = draftPlate.bounds()
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        // the plate against the bed (a plate that does not fit is refused with a suggested split): centered, it
        // needs its width across, its depth plus the parking place in front on both sides of the center, and its height.
        // Too big cannot print: refused, with the plates to make instead. Tight: a warning.
        val plateW = (b[2] - b[0]) / 1000.0; val plateD = (b[3] - b[1]) / 1000.0; val plateH = bands.last().readToUm / 1000.0
        val needD = plateD + 2 * park
        val fewerBands = "fewer bands (--temps, --step)"
        if (plateH > s.maxPrintHeightMm) throw IllegalArgumentException("the calibration plate is ${n(plateH)} mm tall; the machine prints ${n(s.maxPrintHeightMm)} mm (hardware.machine.<key>.build): $fewerBands")
        if (plateW > bedW || needD > bedD) throw IllegalArgumentException(if (kind == "filament") splitMessage(draft, plateW, plateD, bedW, bedD)
            else "the $kind plate does not fit this machine: it is ${n(plateW)} x ${n(plateD)} mm (and ${n(needD)} deep with the head's parking place in front); the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build): $fewerBands")
        val fitWarnings = listOfNotNull(if (!draftPlate.layout.spared) "the plate is ${n(plateW)} x ${n(plateD)} mm on a ${n(bedW)} x ${n(bedD)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at an edge; check the bed's clips or wiper are clear of it" else null)
        // every layer at least the filament's minimum layer time (hardware.filament.cooling.layer.time.min), by a pause parked beside the plate
        val spec = draft.keeping(draft.parts.toSet(), originXUm = cx - (b[0] + b[2]) / 2, originYUm = cy - (b[1] + b[3]) / 2)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, bands.map { it.tempC }, "the plate's bands")
        val plate = CalibrationPlate(spec)
        val pa = setup.pressureAdvanceByTool()
        fun gcodeOf(p: CalibrationPlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val text = withConditions(gcodeOf(plate), spec, plate, spool.material, slotN, s, pa[spool.tool])
        // the read-back checks: the plate's specification (cells, temperatures, fans, geometry) and the config (machine,
        // retraction, travels, start and end); each with controls, copies deliberately wrong, that must fail them
        // the plate's own retraction lengths (the stringing ring's) are lengths it is meant to use, beside the config's
        // the melt strips print at their own heights (their floor's top plus their thickness), on their read layer
        // ... in the file's own Z: the design's plus the first layer's extra thickness (CalibrationPlate.shifted; once missed)
        val standing = melt?.let { mt -> spec.bands.indices.flatMap { b -> (0 until 3).flatMap { sf -> (0 until mt.count).map { k ->
            mt.label(b, sf, k) to (spec.meltReadLayer(b) * h + mt.heightsUm[k] + (spec.firstLayerUm - h)) / 1000.0 } } }.toMap() } ?: emptyMap()
        val expect = plymeld.core.check.Expectations.forSetup(setup).let { e ->
            plymeld.core.check.GcodeCheck.Expect(e.bedMinX, e.bedMinY, e.bedMaxX, e.bedMaxY, e.maxZ, e.maxPrintSpeed, e.maxTravelSpeed, e.maxAccel,
                e.retractLengths + (strings?.lengthsMm?.toSet() ?: emptySet()), e.startTemplate, e.endTemplate, e.onSurface, e.ownZ, standing = standing)
        }
        // the frame against the config, not the specification: the machine's print speed and acceleration, the filament's
        // maximum flow; the first layer: its configured speed, held to the filament's maximum flow
        fun frameProblems(t: String) = CalibrationCheck.frameMotion(t, spec.tool, s.hiddenSpeed, s.defaultAccel, s.maxVolumetric, s.filamentDiameterMm)
        fun firstProblems(t: String) = CalibrationCheck.firstLayerFlow(t, spec.tool, s.firstLayerSpeed, s.maxVolumetric, s.filamentDiameterMm)
        fun allProblems(t: String) = CalibrationCheck.check(spec, t) + plymeld.core.check.GcodeCheck.check(t, expect) + frameProblems(t) + firstProblems(t)
        val problems = allProblems(text)
        fun without(vararg c: String) = gcodeOf(CalibrationPlate(spec, without = c.toSet()))
        // the cooling pause can only be missed where a layer is shorter than the minimum on its own: its control
        // runs where one is (where none is, the pause adds nothing and leaving it out cannot be caught)
        val noPause = without("pause")
        val ownTimes = CalibrationCheck.layerTimes(noPause)
        val pauseMatters = spec.minLayerTimeS > 0 && ownTimes.any { it < spec.minLayerTimeS - 0.05 }
        val oneFan = bands.map { it.fanPct }.distinct().size == 1
        val controls = listOfNotNull(
            // the bands' fan: alternating, one band's changed; one level all along (the melt plate), turned off in a
            // band (or, off, on)
            when {
                !oneFan -> "a band's fan changed" to alterFan(text)
                bands[0].fanPct == 0.0 -> "the fan on in a band (it is off${if (kind == "melt") ": --melt-fan off" else ""})" to fanOnMidway(text)
                else -> "the fan off in a band (it is the filament's own, ${n(bands[0].fanPct)} %)" to alterFan(text, toOff = true)
            },
            if (spec.ruler) "the notches left out" to without("notches") else null,
            "a retraction shortened" to alterRetraction(text),
            "a travel across ${if (melt != null) "a melt strip" else "the post"} at print height" to addLowTravel(text, spec, plate),
            removeEndLift(text)?.let { "the end's lift removed" to it },
            // with one part on the plate (the melt plate) there is no gap to leave out
            if (spec.parts.size > 1) "the parts packed with no gap between them" to without("familygap") else null,
            "a base's notch filled in" to without("basefill"),
            "a base's notch cut through its first layer" to without("basenotch1"),
            "a base 5 mm short on one side" to without("baseshort"),
            // the bases' fill
            "the bases filled with concentric rings (as before)" to without("baserings"),
            if (spec.baseLayers >= 2) "every base layer's lines at the same angle (not alternating)" to without("basesameangle") else null,
            "a base's lines out of monotonic order (the back half of the sweep first)" to without("basehalves"),
            "a base's lines across its notch" to without("basenotchfill"),
            "the bands not moved up above the bases" to gcodeOf(CalibrationPlate(spec.withBands(bandsFrom(h)))),
        ) + (if (melt == null) emptyList() else listOf(
            "the melt test's MID surface at the FAST speed" to without("meltmidfast"),
            "a melt strip 0.05 mm wider than the plate's line width" to without("meltwidth"),
            "a melt strip 0.04 mm above its thickness" to without("meltheight"),
            "a second read layer on a melt step" to without("melt2read"),
            "the melt floor left out (the grid under the read layer)" to without("meltnofloor"),
            "a second solid layer, under each melt floor" to without("meltsolid"),
            // the strips stand above the layer grid: the nozzle must never meet one it has laid
            // (the writer lifts over every strip laid: its lifts taken out too, or the control cannot fail)
            "a travel from a surface's thickest strip to the next surface without a lift" to plymeld.core.calibrate.MeltControls.withoutStripLifts(without("meltnolift")),
            "band 1's FAST strips printed thickest first" to plymeld.core.calibrate.MeltControls.withoutStripLifts(without("meltthickfirst")),
            // the melt plate's heights and waits: a read layer printed before its temperature is reached, a
            // step at the wrong height (the melt plate always has two bands or more: its range must rise)
            "band 2's read layer printed before its wait (M104 at its start, the M109 only after its read layer)" to without("meltwaitlate"),
            "band 2's step one layer low" to without("meltstep"),
        )) + (if (spec.firstWidthUm == w) emptyList() else listOf(
            "the first layer's ordinary lines at the plate's own width" to without("firstwidth"),
        )) + (if (!pauseMatters) emptyList() else listOf(
            "the cooling pause left out" to noPause,
        )) + (if (ledges == null) emptyList() else listOf(
            "the ledges' lean left out" to without("lean"),
        )) + (if (bridges == null) emptyList() else listOf(
            "the bridge decks left out" to without("decks"),
        )) + (if (bridges == null || bands.size < 2) emptyList() else listOf(
            // with one band the terraced decks are where the untiered ones were: these controls only differ with two or more
            "the top band's bridge decks where the untiered row had them" to without("untiered"),
            "the top band's bridge decks half a deck over the band below's" to without("deckoverlap"),
        )) + (if (strings == null || strings.lengthsMm.size < 2) emptyList() else listOf(
            "a stringing hop's retraction given another length" to alterRingRetraction(text, strings),
            "the stringing ring's lengths in reverse order" to without("ringreverse"),
        )) + (if (tubes == null || tubes.widthsUm.all { it == w }) emptyList() else listOf(
            "the tubes all at the plate's own width" to without("widths"),
        )) + (if (fit == null || fit.holesUm.all { it == fit.pegUm }) emptyList() else listOf(
            "the fit holes all at the peg's size" to without("clearance"),
        ))
        // the frame's controls, which the frame check itself (against the config, not the specification) must catch: a frame
        // cell at the visible-line cap's speed, and at its acceleration, as before (where the config gives them the
        // frame's own values, half the frame's instead)
        val oldSpeed = if (Math.abs(surfaceSpeed - frameSpeed) > 1.0) surfaceSpeed to "the visible-line cap's ${n(surfaceSpeed)} mm/s (plan.mechanism.motion.visible.speed held to the maximum flow, as before)"
            else frameSpeed / 2 to "half the frame's speed"
        val oldAccel = if (Math.abs(s.visibleAccel - frameAccel) > 1.0) s.visibleAccel to "the visible lines' ${n(s.visibleAccel)} mm/s2 (plan.mechanism.motion.visible.acceleration, as before)"
            else frameAccel / 2 to "half the machine's acceleration"
        val frameControls = listOf("a frame cell at ${oldSpeed.second}" to alterFrame(text, oldSpeed.first),
            "a frame cell at ${oldAccel.second}" to alterFrame(text, frameSpeed, oldAccel.first))
        // the first layer's control, which the first-layer check (against the config) must catch: a first-layer cell 20 % past
        // the filament's maximum flow for its line
        val overFirst = 1.2 * s.maxVolumetric / (fhm * (spec.firstWidthUm / 1000.0 - fhm * (1 - Math.PI / 4)) * s.flowRatio)
        val firstControls = listOf("a first-layer cell at ${n(overFirst)} mm/s, 20 % past the filament's maximum flow" to alterFirst(text, overFirst))
        val controlMisses = (controls + frameControls + firstControls).filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first } +
            frameControls.filter { (_, t) -> frameProblems(t).isEmpty() }.map { "${it.first} (by the frame check)" } +
            firstControls.filter { (_, t) -> firstProblems(t).isEmpty() }.map { "${it.first} (by the first-layer check)" }
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val machineDesc = s.machineDescription
        val prediction = plymeld.core.Predict.gcode(text, machineDesc, changeS, plymeld.core.Predict.limits(s))
        // each family's cost, by the g-code's cells; what no family owns is shared. They must add up to the plate's
        val costs = partCosts(text, spec, machineDesc, changeS, plymeld.core.Predict.limits(s), prediction)
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        echo("$kind plate for filament '${spool.material}' on tool $slotN of '${Resolver.resolve(setup, "hardware.machine.model").value}', build plate '$plateName' (bed ${s.bedTempFirstC} C): ${n(h / 1000.0)} mm layers, ${n(w / 1000.0)} mm lines " +
            "(on the first layer ${n(spec.firstWidthUm / 1000.0)} mm, plan.mechanism.adhesion.first_layer.line.width), flow ratio ${n(s.flowRatio)}")
        echo("families: ${spec.parts.filter { it != Part.POST }.joinToString { it.key }}" + (if (kind != "filament") "" else Part.FILAMENT_TESTS.filter { it !in selected }.takeIf { it.isNotEmpty() }?.let { "; left out (--tests): ${it.joinToString { p -> p.key }}" } ?: ""))
        echo("bases: ${baseLayers} layers under every family (${n(baseLayers * h / 1000.0)} mm, ${n(PlateGeometry.baseMargin / 1000.0)} mm past each test), each layer ${PlateGeometry.frameWalls} outline rings and then solid lines one bead spacing apart in one monotonic sweep, at 45 and 135 degrees on alternate layers; one notch in each base's front edge (above its first layer) in front of the test's first item; the bands start above them")
        echo("layout: ${n(plateW)} x ${n(plateD)} mm, packed ${n(PlateGeometry.familyGap / 1000.0)} mm apart, centered on the ${n(bedW)} x ${n(bedD)} mm bed: " +
            plate.parts.joinToString { "${it.title} ${plymeld.core.calibrate.PlateInstructions.where(plate, it)}" })
        echo(when (kind) {
            "melt" -> "bands (bottom to top, hot to cold): band 1 a ${n(PlateGeometry.meltBodyLayers(h) * h / 1000.0)} mm body (${PlateGeometry.meltBodyLayers(h)} layers, its top layer the step's solid floor) and its step's read layer; each later band 2 layers, " +
                "its wait (M109) for its temperature before the first (the head parked), then its step's solid floor (the other steps' bodies rising beside it) and its read layer; the bases at ${bands[0].tempC} C, fan off:"
            else -> "bands (bottom to top, hot to cold), ${n(readUm / 1000.0)} mm read + ${n(settleUm / 1000.0)} mm settling each (2 layers changing, a wait, 2 at the new temperature); the bases at ${bands[0].tempC} C, fan off:"
        })
        for (bd in bands) echo("  band ${bd.index + 1}: z ${n(bd.fromUm / 1000.0)}-${n(bd.readToUm / 1000.0)} mm  ${bd.tempC} C  fan ${n(bd.fanPct)} %" +
            (melt?.let { mt -> "  wait (M109) before the layer at z ${n((bd.fromUm + h) / 1000.0)} mm; floor's top z ${n(spec.meltReadLayer(bd.index) * h / 1000.0)} mm, " +
                "strips at z ${n((spec.meltReadLayer(bd.index) * h + mt.heightsUm.first()) / 1000.0)}-${n((spec.meltReadLayer(bd.index) * h + mt.heightsUm.last()) / 1000.0)} mm (on the layer to z ${n((spec.meltReadLayer(bd.index) + 1) * h / 1000.0)})" } ?: ""))
        melt?.let { mt ->
            val len = PlateGeometry.meltLineLen / 1000.0
            val fFrom = Resolver.resolveOrNull(setup, "hardware.filament.melt.flow.max", plymeld.model.Context(0))?.from ?: "the config"
            echo("melt test: a step per band (band 1 at the front, each colder band's behind and higher), its top one read layer on a solid floor; on it FAST | MID | SLOW at ${mt.speeds.joinToString(" | ") { n(it) }} mm/s " +
                (if (mt.machineHeld) "(the machine's print speed, hardware.machine.<key>.motion.print.speed, half, and a quarter: the machine holds FAST below the speed that centers it on the filament's ${n(mt.centerFlow)} mm3/s, so the top of the range is the machine's)"
                else "(FAST centered on the filament's expected maximum flow, ${n(mt.centerFlow)} mm3/s at ${bands[0].tempC} C from $fFrom${if (fFrom.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)) ", plymeld's bundled placeholder" else ""}: its middle-thickness line runs at it; MID half, SLOW a quarter)") +
                ", at ${n(mt.accel)} mm/s2; ${mt.count} strips each, ${n(mt.widthUm / 1000.0)} mm wide (the plate's line width), ${mt.heightsUm.joinToString { n(it / 1000.0) }} mm thick (the tool's layer.height.max down a tenth of the bore at a time), thinnest at the front and printed first; " +
                "${mt.lines} lines a strip (FAST holds each flow ${n(mt.lines * len / mt.speeds[0])} s), ${n(len)} mm long (${mt.speeds.joinToString(", ") { n(100 * plymeld.core.calibrate.PlateInstructions.fullSpeedShare(it, mt.accel, len)) }} % of a line at full speed); " +
                "a step ${n(plate.meltStepD / 1000.0)} mm deep, a surface ${n(plate.meltSurfaceW / 1000.0)} mm wide; under each surface its floor (two walls, front-to-back lines a bead spacing apart) on a body (two walls, a grid ${n(PlateGeometry.pedestalPitch / 1000.0)} mm both ways) at the frame's values; " +
                "the head lifted ${n(mt.liftUm / 1000.0)} mm (the travel lift) above the tallest strip before each surface and after the last; " +
                plymeld.core.calibrate.PlateInstructions.meltFanText(spec))
            echo("  flows (mm3/s, at flow ratio ${n(s.flowRatio)}), strip: thickness  FAST / MID / SLOW")
            for (k in 0 until mt.count) echo("    ${k + 1}: ${n(mt.heightsUm[k] / 1000.0)} mm  ${(0 until 3).joinToString(" / ") { n(spec.stripFlow(k, it)) }}")
        }
        echo(if (spec.ruler) "ruler post (front-left, its front-left corner chamfered = the origin): a notch per band, every fourth long"
            else "no ruler post: the steps mark the bands (band 1 at the front and lowest, each next behind and 2 layers higher), and the bands are too short for its notches")
        ledges?.let { lg -> echo("overhang ledges: shares ${lg.shares.joinToString { n(it) }} of a ${n(w / 1000.0)} mm line (steps ${lg.shares.joinToString { n(it * w / 1000.0) }} mm a layer), " +
            "each band's read layers, at ${n(lg.speed)} mm/s (${if (s.overhangSpeed != null) "the filament's hardware.filament.overhang.speed" else "a visible surface's: the filament has no hardware.filament.overhang.speed"})") }
        bridges?.let { br -> echo("bridges: spans ${br.spansUm.joinToString { n(it / 1000.0) }} mm between ${br.spansUm.size + 1} piers, the row ${n(plate.bridgeDepth / 1000.0)} mm deep; on every band's top read layer two decks across them, " +
            "terraced (band k's in deck strip k from the front and from the back, ${n(plate.deckDepth / 1000.0)} mm each: every deck visible from above), " +
            "front at flow ${n(br.flows[0])}, back at ${n(br.flows[1])}, ${n(br.speed)} mm/s (the bridge speed, plan.mechanism.motion.bridge.speed${if (br.speed < s.bridgeSpeed - 1e-9) ", held to the filament's maximum flow" else ""})") }
        fit?.let { f -> echo("fit: holes ${f.holesUm.joinToString { n(it / 1000.0) }} mm in a ${n(f.blockLayers * h / 1000.0)} mm block on a pedestal (z ${n(f.fromLayer * h / 1000.0)}-${n((f.fromLayer + f.blockLayers) * h / 1000.0)} mm, band ${ownBand!!.index + 1}, ${ownBand.tempC} C, nearest the filament's $ownC C; blind holes), a ${n(f.pegUm / 1000.0)} mm peg column to ${n(f.pegLayers * h / 1000.0)} mm beside it (its top ${n(f.blockLayers * h / 1000.0)} mm in that band: the end to use), as drawn (no compensation)") }
        tubes?.let { tb -> echo("line-width tubes: ${tb.widthsUm.joinToString { n(it / 1000.0) }} mm lines, ${n(PlateGeometry.tube / 1000.0)} mm square, to ${n(tb.layers * h / 1000.0)} mm (every band), at ${n(tb.speed)} mm/s") }
        strings?.let { st -> echo("stringing ring: ${st.posts} round posts ${n(PlateGeometry.ringPost / 1000.0)} mm across, ${n(PlateGeometry.ringPostGap / 1000.0)} mm apart, hops counter-clockwise from post 1 with retractions ${st.lengthsMm.joinToString { n(it) }} mm; the open gap (post ${st.posts} to post 1, at its front) never crossed: the control; " +
            "retraction speed ${n(s.retractSpeed)} mm/s, travel ${n(s.travelSpeed)} mm/s, lift ${n(s.zHopUm / 1000.0)} mm (the filament's and tool's own)") }
        echo(when {
            spec.minLayerTimeS <= 0 -> "no cooling pause (hardware.filament.<key>.cooling.layer.time.min is 0)"
            pauseMatters -> "cooling pause: every layer at least ${n(spec.minLayerTimeS)} s (the filament's minimum layer time, hardware.filament.<key>.cooling.layer.time.min), the head parked in front of the plate for the rest; temperature waits parked too"
            else -> "cooling pause: none needed: every layer takes at least ${n(ownTimes.filter { it > 0 }.minOrNull() ?: 0.0)} s on its own, past the filament's minimum layer time (hardware.filament.<key>.cooling.layer.time.min) of ${n(spec.minLayerTimeS)} s (the head still parks between layers; temperature waits parked there)"
        })
        echo(when { level != null -> "bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level to skip it"; noLevel -> "bed levelling: skipped (--no-level)"; else -> "bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print" })
        warnings.forEach { echo("warning: $it") }
        // the filament's maximum flow as the config has it, and whether it is still plymeld's bundled placeholder (its source, not
        // its number): the filament plate recommends the melt plate first (the motion plate too, in runMotion) (the dependency
        // order; a warning, not a refusal)
        // its key: the one that caps it in this tool on this machine (the tool's over the machine's over the filament's own); a
        // reading from this machine is entered for the machine
        val maxFlow = Resolver.resolveOrNull(setup, "hardware.filament.melt.flow.max", plymeld.model.Context(0))
        val meltSlot = ConfigSetup.slots(config, config.machine!!, ArrayList()).single()
        val maxFlowKey = ConfigSetup.meltEntry(config, plymeld.config.Site(machine = config.machine, tool = meltSlot.tool, filament = meltSlot.filament))?.key
            ?: "hardware.filament.${spool.material}.melt.flow.max"
        val maxFlowEnterKey = plymeld.config.ConfigFiles.pathText(listOf("hardware", "filament", spool.material, "machine", config.machine!!, "melt", "flow", "max"))
        val maxFlowPlaceholder = maxFlow != null && maxFlow.from.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)
        val maxFlowNow = maxFlow?.let { "${n((it.value as Number).toDouble())} mm3/s, from ${it.from}${if (maxFlowPlaceholder) ": plymeld's bundled placeholder" else ""}" }
        // the frame's parts on this plate, its values, and which limit holds its speed (the machine's, not the
        // visible-line cap; the maximum flow a physical limit, said when it binds and whether it is still the placeholder)
        val frameParts = listOfNotNull(if (spec.ruler) "the post" else null, "the bases above their first layer", melt?.let { "the melt bodies" },
            fit?.let { "the pedestal" }, bridges?.let { "the piers" })
        val frameWhy = "the machine's print speed (hardware.machine.<key>.motion.print.speed, ${n(s.hiddenSpeed)} mm/s) and acceleration (motion.print.acceleration)" +
            (if (!frameHeld) "; the filament's maximum flow less the melt buffer (${n(s.maxVolumetric)} mm3/s) allows ${n(meltCap)} mm/s on its lines, so the machine's speed binds"
            else "; the speed held to the filament's maximum flow ($maxFlowKey, ${maxFlowNow ?: n(s.maxVolumetricMeasured) + " mm3/s"}) less the melt buffer (${n(s.meltBuffer * 100)} %, plan.mechanism.motion.melt.buffer) for its ${n(w / 1000.0)} mm lines: that is the binding limit" +
                (if (maxFlowPlaceholder) ", and it is the bundled placeholder until ${if (melt != null) "this plate" else "the melt plate"} measures it" else ""))
        echo("frame (${frameParts.joinToString()}) at ${n(frameSpeed)} mm/s, ${n(frameAccel)} mm/s2: $frameWhy; first layer ${n(fhm)} mm thick ($firstFrom; the layers above ${n(hm)} mm, every layer of the plate shifted up by the difference) at ${n(firstSpeed)} mm/s (" +
            (if (firstSpeed < s.firstLayerSpeed - 1e-9) "the configured first-layer speed ${n(s.firstLayerSpeed)} held to the filament's maximum flow for its ${n(firstWidest / 1000.0)} mm lines" else "the configured first-layer speed") +
            "), ${n(s.defaultAccel)} mm/s2; retraction ${n(s.retractMm)} mm")
        if (melt == null && maxFlowPlaceholder) echo("recommendation: this filament's maximum flow ($maxFlowKey, $maxFlowNow) has not been measured. " +
            plymeld.core.calibrate.PlateInstructions.MELT_FIRST.replace("`", ""))
        val printTempC = (plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { Math.round(it).toInt() }
        // a condition every reading depends on: stated, or said to be unknown
        val paFrom = Resolver.resolveOrNull(setup, "hardware.filament.pressure_advance", plymeld.model.Context(0))?.let { "the filament's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.tool.pressure_advance", plymeld.model.Context(0))?.let { "the tool's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.machine.pressure_advance", plymeld.model.Context(0))?.let { "the machine's, ${it.from}" }
        echo(pa[spool.tool]?.let { v -> "pressure advance: $v s ($paFrom)" }
            ?: ownPaEcho(setup) ?: "pressure advance: not set, so the printer's own value applies, unknown to plymeld (the results will not say what it was; set the machine's, the tool's, or the filament's pressure_advance to state it)")
        echo("check: every cell, temperature, fan, and the plate's geometry (the bases and their notches, the packing) as specified, and the g-code as the config says (machine, retraction, travels, start and end), the frame at the machine's print speed (held to the maximum flow) and acceleration, the first layer within its speed and the maximum flow" +
            (if (melt != null) ", every melt strip at its height on a solid floor and every move clear of the strips laid" else "") +
            "; ${spec.bands.size} bands, ${spec.topUm / h} layers. Controls caught: ${(controls + frameControls + firstControls).joinToString { it.first }}")
        echo("predicted cost by family (by the g-code's cells: each family's lines, its base, and the travels into them; shared: layer changes, the parked cooling pause, temperature waits counted as 0, start and end):")
        for (c in costs) echo("  ${(c.part?.title ?: "shared").padEnd(20)} ${c.timeS?.let { n(it / 60) + " min" } ?: "time not predicted"}, ${n(c.filamentMm / 1000)} m" +
            (if (c.part == null) "" else " (its base ${c.baseTimeS?.let { n(it / 60) + " min, " } ?: ""}${n(c.baseFilamentMm / 1000)} m)"))
        // the page that goes with the plate: before printing, the conditions, a picture, how to read it
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        val paText = pa[spool.tool]?.let { v -> "$v s (${paFrom?.substringBefore(',')})" } ?: ownPaPage(setup) ?: "not set: the printer's own, unknown to plymeld (write down what it was if you know)"
        plymeld.core.calibrate.PlateInstructions.write(page, spec, plate, plymeld.core.calibrate.PlateInstructions.Info(
            spool.material, Resolver.resolve(setup, "hardware.machine.model").value.toString(), spool.tool, PlymeldVersion.value, output.fileName.toString(),
            prediction.timeS?.let { it / 60 }, paText, s.bedTempFirstC, firstSpeed, frameSpeed, s.retractMm,
            plate = plateName, levels = level != null, levelSkipped = noLevel && s.levelGcode != null, warnings = warnings, costs = costs,
            maxFlowKey = maxFlowKey, maxFlowNow = maxFlowNow, maxFlowPlaceholder = maxFlowPlaceholder, maxFlowEnterKey = maxFlowEnterKey, printTempC = printTempC,
            frameAccel = frameAccel, frameParts = frameParts, frameWhy = frameWhy))
        echo("-> $output")
        echo("-> $page (and ${page.fileName.toString().removeSuffix(".md")}.png): before printing, the conditions, the picture, how to read it")
        echo("predicted: $prediction")
    }

    /**
     * Each family's predicted time and filament, by the g-code's cells ([plymeld.core.Predict.byCell]): its own lines,
     * its base, and the travels and retractions into them; last, what no family owns (the post among them: it is not a test
     * but every plate's ruler; the layer changes, the cooling pause, the temperature waits, the start and end). They must add
     * up to the plate's own prediction: refused (an error in plymeld) if they do not.
     */
    private fun partCosts(text: String, spec: CalibrationSpec, machine: plymeld.machine.MachineDescription, changeS: Double?, limits: Map<String, Double>,
                          prediction: plymeld.core.Predict.Prediction): List<plymeld.core.calibrate.PlateInstructions.PartCost> {
        val (time, fil) = plymeld.core.Predict.byCell(text, machine, changeS, limits)
        // the prime line (PrimeLine.CELL) is no family's either
        fun partOf(cell: String): Part? = if (cell.isEmpty() || Part.isPrimeLine(cell)) null else Part.ofCell(cell).takeIf { it != Part.POST }
        val t = HashMap<Part?, Double>(); val f = HashMap<Part?, Double>(); val bt = HashMap<Part?, Double>(); val bf = HashMap<Part?, Double>()
        fun isBase(c: String) = c.removePrefix("adhesion:").startsWith("base ")
        time?.forEach { (c, v) -> t.merge(partOf(c), v, Double::plus); if (isBase(c)) bt.merge(partOf(c), v, Double::plus) }
        fil.forEach { (c, v) -> f.merge(partOf(c), v, Double::plus); if (isBase(c)) bf.merge(partOf(c), v, Double::plus) }
        val out = spec.parts.filter { it != Part.POST }.map { p -> plymeld.core.calibrate.PlateInstructions.PartCost(p, time?.let { t[p] ?: 0.0 }, f[p] ?: 0.0, time?.let { bt[p] ?: 0.0 }, bf[p] ?: 0.0) } +
            plymeld.core.calibrate.PlateInstructions.PartCost(null, time?.let { t[null] ?: 0.0 }, f[null] ?: 0.0)
        val sumT = out.mapNotNull { it.timeS }.sum(); val sumF = out.sumOf { it.filamentMm }
        prediction.timeS?.let { tot -> check(time != null && Math.abs(sumT - tot) <= 1e-6 * tot + 1e-3) { "the families' predicted times add up to ${n(sumT)} s, not the plate's ${n(tot)} s" } }
        check(Math.abs(sumF - prediction.filamentMm) <= 1e-6 * prediction.filamentMm + 1e-3) { "the families' filament adds up to ${n(sumF)} mm, not the plate's ${n(prediction.filamentMm)} mm" }
        return out
    }

    /**
     * A plate too big for the bed (refused with a suggested split): its size and the bed's, and plates that
     * fit, each a --tests list: the families largest first, each into the first plate it fits on with the others already
     * there (packed as the plate packs them), else a plate of its own; a family too big for the bed alone is named.
     */
    private fun splitMessage(draft: CalibrationSpec, plateW: Double, plateD: Double, bedW: Double, bedD: Double): String {
        val park = PlateGeometry.parkFront / 1000.0
        fun size(keep: Set<Part>) = CalibrationPlate(draft.keeping(keep)).bounds().let { (it[2] - it[0]) / 1000.0 to (it[3] - it[1]) / 1000.0 }
        fun fits(keep: Set<Part>) = size(keep).let { (pw, pd) -> pw <= bedW && pd + 2 * park <= bedD }
        val families = draft.parts.filter { it != Part.POST }.sortedByDescending { p -> CalibrationPlate.footprints(draft).getValue(p).let { (it[2] - it[0]) * (it[3] - it[1]) } }
        val groups = ArrayList<MutableSet<Part>>(); val alone = ArrayList<Part>()
        for (p in families) {
            val g = groups.firstOrNull { fits(it + p) }
            when { g != null -> g += p; fits(setOf(p)) -> groups += mutableSetOf(p); else -> alone += p }
        }
        fun tests(g: Set<Part>) = g.sortedBy { Part.FILAMENT_TESTS.indexOf(it) }.joinToString(",") { it.key }.ifEmpty { "(none)" }
        return "the calibration plate does not fit this machine: it is ${n(plateW)} x ${n(plateD)} mm (and ${n(plateD + 2 * park)} deep with the head's parking place in front); " +
            "the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build). " +
            (if (groups.isNotEmpty()) "Make ${groups.size} plates instead: " + groups.joinToString("; ") { g -> size(g).let { (pw, pd) -> "--tests ${tests(g)} (${n(pw)} x ${n(pd)} mm)" } } else "No family fits on its own") +
            (if (alone.isEmpty()) "" else ". Too big for this bed even alone (with its base): " + alone.joinToString { p -> size(setOf(p)).let { (pw, pd) -> "the ${p.title} (${n(pw)} x ${n(pd)} mm; fewer items, e.g. its own option)" } })
    }

    /**
     * The config the plate is made with: the user's files and values, with the loadout replaced by one
     * filament on one tool. The filament need not be loaded, nor even in the config: any the config or
     * plymeld's bundled set defines, or a new one started from another (--like).
     */
    private fun plateConfig(toolN: Int, firstBandC: Int?, firstLayerToo: Boolean = true): Pair<Config, Int> {
        fun problems(e: ConfigError) = IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
        val files = ConfigFiles.bundled() + try { ConfigFiles.readFiles(files()) } catch (e: ConfigError) { throw problems(e) }
        val values = configValues.map { ConfigFiles.value(it) }
        val probe = try { Config(ConfigStack(files + values), emptyList(), machineOpt) } catch (e: ConfigError) { throw problems(e) }
        // the machine: --machine, else job.machine, else the only one defined (several and none named: refused)
        val machine = probe.machine ?: throw IllegalArgumentException(probe.machineProblem ?: "which machine?")
        val tools = probe.keysOf("tool").filter { probe.entry(listOf("hardware", "tool", it, "machine"))?.value == machine }
        fun index(tk: String) = probe.entry(listOf("hardware", "tool", tk, "index"))?.let { probe.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1
        val tk = tools.firstOrNull { index(it) == toolN }
            ?: throw IllegalArgumentException("machine '$machine' has no tool $toolN (${if (tools.isEmpty()) "it has no tools: hardware.tool.<key>.machine" else "its tools: " + tools.joinToString { "${index(it)} = $it" }})")
        val known = probe.keysOf("filament")
        val name = filament ?: (probe.loadout("tool", tk, "filament")?.value as String?)?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("which filament? give --filament (one of: ${known.joinToString()}, or a new one with --like)")
        val isNew = name !in known
        if (isNew && like == null) throw IllegalArgumentException("no filament '$name' in the config or plymeld's bundled set: start it from one with --like (one of: ${known.joinToString()})")
        if (!isNew && like != null) throw IllegalArgumentException("filament '$name' is already defined; --like is for a new one")
        if (like != null && like !in known) throw UsageError("--like '$like': no such filament (one of: ${known.joinToString()})")
        val src = "plymeld calibrate"
        fun key(k: String) = if (k.matches(Regex("[A-Za-z0-9_-]+"))) k else "\"" + k.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        // the build plate (required, unless there is only one or the printer names a default):
        // given, else the one on the machine (hardware.machine.<key>.loadout.plate), else the only plate the config
        // defines itself (plymeld's bundled plates aside: with them there is never only one)
        val plates = probe.keysOf("plate")
        val ownPlates = (files + values).filter { it.path.size > 2 && it.path[0] == "hardware" && it.path[1] == "plate" && !it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) }.map { it.path[2] }.distinct()
        val machinePlate = probe.loadout("plate")?.value as String?
        val plateKey = plateOpt ?: machinePlate ?: ownPlates.singleOrNull()
            ?: throw IllegalArgumentException("which build plate? give --plate (${if (plates.isEmpty()) "the config defines none: hardware.plate.<key>" else "one of: " + plates.joinToString()}), or load one on the machine (hardware.machine.$machine.loadout.plate)")
        if (plateKey !in plates) throw IllegalArgumentException("no build plate '$plateKey' in the config (hardware.plate.<key>${if (plates.isEmpty()) "" else "; there are: " + plates.joinToString()})")
        val injected = buildList {
            add(ConfigFiles.value("hardware.machine.${key(machine)}.loadout.plate=${quote(plateKey)}", src))
            add(ConfigFiles.value("hardware.machine.${key(machine)}.loadout.tool.${key(tk)}.filament=${quote(name)}", src))
            if (isNew) add(ConfigFiles.value("hardware.filament.${key(name)}.like=${quote(like!!)}", src))
        }
        // the machines' own loadouts (plate, tools, slots) are left out: the plate loads only this filament
        fun ownLoadout(e: ConfigFiles.Entry) = e.path.size >= 4 && e.path[0] == "hardware" && e.path[1] == "machine" && e.path[3] == "loadout"
        // the start template heats to the filament's temperatures: the plate's first band's (last, so nothing overrides them);
        // with ![firstLayerToo] only its print temperature, its first layer's then worked out from it as the filament says
        val bandTemps = if (firstBandC == null) emptyList() else
            (if (firstLayerToo) listOf("print", "first_layer") else listOf("print")).map { ConfigFiles.value("hardware.filament.${key(name)}.temperature.$it=\"$firstBandC C\"", "$src (the first band)") }
        val stack = ConfigStack(files.filterNot(::ownLoadout) + injected + values.filterNot(::ownLoadout) + bandTemps)
        val model = ModelObject("calibration", listOf("plate"), listOf(index(tk)), src)
        plateName = plateKey
        machineName = machine
        return (try { Config(stack, listOf(model), machine) } catch (e: ConfigError) { throw problems(e) }) to index(tk)
    }

    /** The build plate and the machine (their keys) the last [plateConfig] used. */
    private var plateName = ""
    private var machineName = ""

    /**
     * The plate's conditions in its own g-code, as comments after its first lines (the
     * conditions written down with the plate), so the file says what it was printed with whatever happens
     * to its page.
     */
    private fun withConditions(text: String, spec: CalibrationSpec, plate: CalibrationPlate, filament: String, tool: Int, s: SliceSettings, pa: Double?): String {
        val m = spec.melt
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = ${plymeld.core.calibrate.PlateInstructions.kindOf(spec)}")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; families = ${spec.parts.filter { it != Part.POST }.joinToString(", ") { it.key }} (each on a base, packed ${n(PlateGeometry.familyGap / 1000.0)} mm apart)${if (spec.ruler) ", and the ruler post" else ", no ruler post"}")
            add("; first_layer_height = ${n(spec.firstLayerUm / 1000.0)} mm (the plate's layers shifted up by ${n((spec.firstLayerUm - spec.layerUm) / 1000.0)} mm from its design), layer.height = ${n(spec.layerUm / 1000.0)} mm, line.width = ${n(spec.widthUm / 1000.0)} mm, first_layer_line_width = ${n(spec.firstWidthUm / 1000.0)} mm (the bases${if (spec.ruler) " and the post" else ""}), flow_ratio = ${n(spec.flowRatio)}")
            add("; pressure_advance = ${pa?.let { "$it s" } ?: "the printer's own (not set: unknown)"}, bed = ${s.bedTempFirstC} C, retraction = ${n(spec.retractMm)} mm")
            add("; layer.time.min = ${n(spec.minLayerTimeS)} s (a parked pause makes up the rest)")
            if (spec.baseLayers > 0) add("; bases = ${spec.baseLayers} layers, ${n(PlateGeometry.baseMargin / 1000.0)} mm past each test, a notch ${n(PlateGeometry.baseNotchW / 1000.0)} mm wide and ${n(PlateGeometry.baseNotchIn / 1000.0)} mm in, in each base's front edge above its first layer, in front of the test's first item")
            m?.let { mt -> add("; melt_test = a step per band (band 1 at the front), its top one read layer on a solid floor; surfaces fast, mid, slow at ${mt.speeds.joinToString(", ") { n(it) }} mm/s " +
                "(${if (mt.machineHeld) "fast = the machine's print speed" else "fast centered on ${n(mt.centerFlow)} mm3/s, the filament's maximum flow at the first band"}), ${n(mt.accel)} mm/s2; " +
                "strips ${n(mt.widthUm / 1000.0)} mm wide, ${mt.heightsUm.joinToString(", ") { n(it / 1000.0) }} mm thick (front to back, printed in that order), ${mt.lines} lines of ${n(PlateGeometry.meltLineLen / 1000.0)} mm each; " +
                "floors: two walls, lines front to back a bead spacing apart; bodies: two walls, a grid ${n(PlateGeometry.pedestalPitch / 1000.0)} mm both ways; lift over the strips ${n(mt.liftUm / 1000.0)} mm") }
            spec.fit?.let { f -> add("; fit = holes ${f.holesUm.joinToString(", ") { n(it / 1000.0) }} mm (left to right), peg ${n(f.pegUm / 1000.0)} mm, as drawn, ${n(f.speed)} mm/s, block layers ${f.fromLayer + 1}-${f.fromLayer + f.blockLayers} on a pedestal (band ${spec.readBand((f.fromLayer + 1) * spec.layerUm)!!.index + 1}, ${spec.readBand((f.fromLayer + 1) * spec.layerUm)!!.tempC} C), peg column to layer ${f.pegLayers} (its top end the read part)") }
            spec.tubes?.let { tb -> add("; line_width_tubes = ${tb.widthsUm.joinToString(", ") { n(it / 1000.0) }} mm (left to right), ${n(tb.speed)} mm/s, ${n(tb.accel)} mm/s2, to layer ${tb.layers}, every band") }
            spec.strings?.let { st -> add("; stringing_ring = ${st.posts} posts, hops counter-clockwise from post 1 with retractions ${st.lengthsMm.joinToString(", ") { n(it) }} mm, the open gap (post ${st.posts} to 1) never crossed, ${n(PlateGeometry.ringPostGap / 1000.0)} mm gaps, retraction speed ${n(s.retractSpeed)} mm/s, travel ${n(s.travelSpeed)} mm/s, lift ${n(s.zHopUm / 1000.0)} mm") }
            spec.bridges?.let { br -> add("; bridges = spans ${br.spansUm.joinToString(", ") { n(it / 1000.0) }} mm (left to right), decks at flow ${n(br.flows[0])} (front) and ${n(br.flows[1])} (back), ${n(br.speed)} mm/s, ${n(br.accel)} mm/s2, on every band's top read layer, terraced (band k's decks in deck strip k from the front and from the back)") }
            spec.ledges?.let { lg -> add("; overhang_ledges = shares ${lg.shares.joinToString(", ") { n(it) }} of the line width (left to right), ${n(lg.speed)} mm/s, ${n(lg.accel)} mm/s2, every band") }
            for (b in spec.bands) add("; band ${b.index + 1} = z ${n(b.fromUm / 1000.0)}-${n(b.readToUm / 1000.0)} mm, ${b.tempC} C, fan ${n(b.fanPct)} %" +
                (m?.let { mt -> ", wait (M109) before the layer at z ${n((b.fromUm + spec.layerUm) / 1000.0)} mm, melt floor top at z ${n(spec.meltReadLayer(b.index) * spec.layerUm / 1000.0)} mm, " +
                    "strips at z ${n((spec.meltReadLayer(b.index) * spec.layerUm + mt.heightsUm.first()) / 1000.0)}-${n((spec.meltReadLayer(b.index) * spec.layerUm + mt.heightsUm.last()) / 1000.0)} mm" } ?: ""))
            add("; plate_size = ${plate.bounds().let { "${n((it[2] - it[0]) / 1000.0)} x ${n((it[3] - it[1]) / 1000.0)}" }} mm")
            add("; CALIBRATION_PLATE_END")
        }
        // every line between the markers reads `; name = value` (tools read it: a lost space once broke one)
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /** Column [k]'s first-layer tiles left out, over its melt limit: "tile r.c (v mm/s, h mm: f mm3/s)". */
    private fun omitted(spec: plymeld.core.calibrate.FirstLayerSpec, k: Int): List<String> {
        val t = spec.tiles
        return t.speeds.indices.flatMap { r -> t.heightsUm.indices.filter { c -> !spec.printed(k, r, c) }
            .map { c -> "${t.label(r, c)} (${n(t.speeds[r])} mm/s, ${n(t.heightsUm[c] / 1000.0)} mm: ${String.format(Locale.ROOT, "%.1f", spec.tileFlow(r, c))} mm3/s)" } }
    }

    /**
     * The first-layer plate: only first-layer tiles (and a frame over their walls, a border round the base), one layer, one piece, a column
     * of them per temperature, coolest first, the head parked for each column's wait and a prime line before each column.
     */
    private fun runFirstLayer() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val stepC = whole((step ?: "10").toDoubleOrNull() ?: throw UsageError("--step is a whole number of degrees (got '$step')"), "--step")
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        // the temperatures: given (a range, or one), or from the filament's print temperature up to its maximum (a first
        // layer runs at or above the print temperature: the filament's own first-layer temperature is in that range), else
        // its whole range where it has no print temperature (or one past its maximum)
        val (lowC, highC) = temps?.let { v ->
            if (!v.contains('-')) whole(v.trim().removeSuffix("C").trim().toDoubleOrNull() ?: throw UsageError("--temps is a range LOW-HIGH or one temperature, whole degrees C (got '$v')"), "--temps").let { it to it }
            else range(v, "--temps").let { (a, b) -> whole(a, "--temps") to whole(b, "--temps") }
        } ?: run {
            val sp = plain.spool(plain.objects[0].spool)
            fun temp(k: String) = (plainMt?.get("hardware.filament.temperature.$k")?.value as Number?)?.toDouble()
            val lo = temp("min"); val hi = temp("max"); val print = temp("print")
            if (lo == null || hi == null) throw IllegalArgumentException("which temperatures? '${sp.material}' has no range (temperature.min / temperature.max, the spool's label): give --temps LOW-HIGH, or one temperature")
            val from = if (print != null && print in lo..hi) whole(print, "the filament's temperature.print") else whole(lo, "the filament's temperature.min")
            // up by --step as far as the maximum goes: the last column the highest step at or below it (205 to 230 C at
            // 10 C steps: 205, 215, 225)
            val top = whole(hi, "the filament's temperature.max")
            from to (from + (top - from) / stepC * stepC)
        }
        if (highC < lowC) throw UsageError("--temps: the range must rise, LOW-HIGH (got $lowC-$highC)")
        val tempList = try { CalibrationSpec.temperatures(lowC, highC, stepC) } catch (e: IllegalStateException) { throw IllegalArgumentException(e.message ?: "--step") } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "--step") }
        // the start template heats to the coolest column's temperature (the first printed)
        val (config, slotN) = plateConfig(toolN, lowC)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val h = s.firstLayerHeightUm
        // the tiles' values, around the configured first layer (plymeld's choice: a few values, so a column holds
        // every combination): the first layer's speed (plan.mechanism.motion.first_layer.speed) halved, as it is, and half
        // as fast again; its height (plan.mechanism.adhesion.first_layer.height) and three steps above it, a step
        // a tenth of the nozzle's bore (hardware.tool.bore). In the first layer's line width
        // (plan.mechanism.adhesion.first_layer.line.width); a tile over its column's melt limit left out
        val speedList = firstSpeeds?.let { v -> v.split(',').map { it.trim().toDoubleOrNull()?.takeIf { x -> x > 0 } ?: throw UsageError("--first-speeds is speeds in mm/s above 0, slow to fast, e.g. 40,60,80 (got '$v')") }
            .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--first-speeds must rise, slow to fast (got '$v')") } }
            ?: listOf(0.5, 1.0, 1.5).map { Math.round(s.firstLayerSpeed * it).toDouble() }.distinct()
        val heightList = firstHeights?.let { v -> v.split(',').map { Math.round((it.trim().removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { x -> x > 0 } ?: throw UsageError("--first-heights is nozzle heights in mm above 0, low to high, e.g. 0.16,0.2,0.24 (got '$v')")) * 1000) }
            .also { r -> if (r.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--first-heights must rise, low to high (got '$v')") } }
            ?: (0..3).map { h + it * s.nozzleDiameterUm / 10 }
        if (speedList.any { it > s.hiddenSpeed + 1e-9 }) throw UsageError("--first-speeds: ${speedList.max()} mm/s is past the machine's print speed (hardware.machine.<key>.motion.print.speed ${s.hiddenSpeed})")
        // the tiles' line ends reach into their walls by the slicer's own line-end overlap (plan.layer.infill.wall_overlap)
        val tiles = plymeld.core.calibrate.FirstLayerTiles(speedList.toDoubleArray(), heightList.toLongArray(), s.firstLayerWidthUm, s.defaultAccel, s.zHopUm, s.infillWallOverlap)
        // each column's melt limit: the filament's maximum flow (melt.flow.max) with its print temperature
        // (temperature.print) set to the column's, the config resolved again as `-cv` would set it, less the melt buffer
        // (plan.mechanism.motion.melt.buffer); a constant gives the same limit in every column, a formula of the temperature
        // its value there
        val capPairs = tempList.map { tc ->
            val cs = try { ConfigSetup.setup(plateConfig(toolN, tc).first).setup } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s) at $tc C:\n" + e.problems.joinToString("\n") { "  $it" }) }
            val v = Resolver.resolveOrNull(cs, "hardware.filament.melt.flow.max", plymeld.model.Context(0)) ?: throw IllegalArgumentException("'${spool.material}' has no maximum flow (melt.flow.max): the tiles over it cannot be left out")
            (v.value as Number).toDouble() * (1 - s.meltBuffer) to "${v.from}, with temperature.print = $tc C (the column's), less the melt buffer ${n(s.meltBuffer * 100)} %"
        }
        val prime = PathValues(s.firstLayerSpeed, s.defaultAccel, s.flowRatio, s.retractMm)
        // the frame: not read, so as the filament plate's frame: the machine's print speed
        // (hardware.machine.motion.print.speed) and acceleration (hardware.machine.motion.print.acceleration), the speed held to
        // the hottest column's melt limit; the plate's ordinary line (plan.layer.walls.outer.line.width) one layer high
        // (plan.technique.flat.layer.height) a layer
        val frame = PathValues(s.hiddenSpeed, s.defaultAccel, s.flowRatio, s.retractMm)
        val level = s.levelGcode?.takeIf { !noLevel }
        fun spec(ox: Long, oy: Long) = plymeld.core.calibrate.FirstLayerSpec(spool.tool, spool.id, tempList.toIntArray(), h, s.filamentDiameterMm, s.flowRatio, tiles,
            capPairs.map { it.first }.toDoubleArray(), prime, s.firstLayerWidthUm, frame, s.outerWallWidthUm, s.layerHeightUm, ox, oy, level, capPairs.map { it.second })
        // a column with no tile under its melt limit has nothing to read: refused, not printed empty
        spec(0, 0).let { sp -> (0 until sp.blocks).firstOrNull { k -> !sp.printed(k, 0, 0) }?.let { k ->
            throw IllegalArgumentException("the ${tempList[k]} C column: every tile is over its melt limit, ${n(capPairs[k].first)} mm3/s (${capPairs[k].second}); the slowest, lowest tile needs ${n(sp.tileFlow(0, 0))} mm3/s. Slower speeds (--first-speeds) or lower heights (--first-heights)") } }
        // the bed: the columns side by side, the head's parking place in front (on both sides of the center, as the filament
        // plate counts it). Tiles are never shrunk nor temperatures dropped to make it fit: a plate that does not fit is
        // refused, with its sizes
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val draft = plymeld.core.calibrate.FirstLayerPlate(spec(0, 0))
        if (!draft.labelsFit) throw IllegalArgumentException("the labels do not fit: a column's temperature along its ${n(plymeld.core.calibrate.FirstLayerGeometry.tileLength / 1000.0)} mm front, a height beside each row, a speed beside each group")
        val park = plymeld.core.calibrate.FirstLayerGeometry.parkFront / 1000.0
        val b = draft.bounds()
        val plateW = (b[2] - b[0]) / 1000.0; val plateD = (b[3] - b[1]) / 1000.0
        val needD = plateD + 2 * park
        if (plateW > bedW || needD > bedD)
            throw IllegalArgumentException("the first-layer plate does not fit this machine: ${tempList.size} column(s) of ${n(plymeld.core.calibrate.FirstLayerGeometry.tileLength / 1000.0)} mm and ${speedList.size * heightList.size} rows make it ${n(plateW)} x ${n(plateD)} mm " +
                "(and ${n(needD)} deep with the head's parking place in front); the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build). Fewer temperatures (--temps, --step), fewer speeds or heights (--first-speeds, --first-heights), or two plates")
        val fitWarnings = listOfNotNull(if (bedW - plateW < 2 * PlateGeometry.bedSpare / 1000.0 || bedD - needD < 2 * PlateGeometry.bedSpare / 1000.0) "the plate is ${n(plateW)} x ${n(plateD)} mm on a ${n(bedW)} x ${n(bedD)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at an edge; check the bed's clips or wiper are clear of it" else null)
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val spec = spec(cx - (b[0] + b[2]) / 2, cy - (b[1] + b[3]) / 2)
        val plate = plymeld.core.calibrate.FirstLayerPlate(spec)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, tempList, "the plate's columns")
        val pa = setup.pressureAdvanceByTool()
        fun gcodeOf(p: plymeld.core.calibrate.FirstLayerPlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val text = withFirstLayerConditions(gcodeOf(plate), spec, plate, spool.material, slotN, s, pa[spool.tool])
        // the read-back checks: the plate's specification and the config (every cell named at its own heights on the first layer)
        val expect = plymeld.core.check.Expectations.forSetup(setup).let { e ->
            plymeld.core.check.GcodeCheck.Expect(e.bedMinX, e.bedMinY, e.bedMaxX, e.bedMaxY, e.maxZ, e.maxPrintSpeed, e.maxTravelSpeed, e.maxAccel, e.retractLengths, e.startTemplate, e.endTemplate, e.onSurface,
                ownZ = firstLayerOwnZ(spec))
        }
        fun allProblems(t: String) = plymeld.core.calibrate.FirstLayerCheck.check(spec, t) + plymeld.core.check.GcodeCheck.check(t, expect)
        val problems = allProblems(text)
        val last = spec.blocks - 1
        fun without(vararg w: String) = gcodeOf(plymeld.core.calibrate.FirstLayerPlate(spec, without = w.toSet()))
        val controls = listOfNotNull(
            if (spec.blocks >= 2) "the ${spec.tempsC[1]} C column's wait removed (printed at ${spec.tempsC[0]} C)" to alterBlockWait(text, spec, 1, remove = true) else null,
            if (spec.blocks >= 2) "the ${spec.tempsC[1]} C column's wait at ${spec.tempsC[0]} C" to alterBlockWait(text, spec, 1, toC = spec.tempsC[0]) else "the column's wait 5 C off" to alterBlockWait(text, spec, 0, toC = spec.tempsC[0] - 5),
            "a tile 0.04 mm off its height" to alterTileZ(text, spec.tileLabel(0, 0, 0)),
            "a tile at 1.5x its speed" to alterTileSpeed(text, spec.tileLabel(last, 0, 0)),
            "the last column's prime line left out" to without("prime"),
            if (spec.blocks >= 2) "the last column's temperature label showing the first column's" to without("mark") else null,
            "the base's front-left corner square, no chamfer" to without("chamfer"),
            "the base's upper layer left out" to without("base"),
            "the first speed's label left out" to without("speedlabel"),
            "the first row's height label left out" to without("heightlabel"),
            "the lift off the tiles left out" to without("tilelift"),
            "a travel across a finished tile at its height" to addTravelAcross(text, spec, plate),
            if ((0 until spec.blocks).any { k -> omitted(spec, k).isNotEmpty() }) "the tiles over the melt limit printed" to without("omit") else null,
            if (spec.rows.size > 1) "the rows 1 mm apart, not touching" to without("rowgap") else null,
            if (spec.rows.size > 1) "the rows 1 mm apart and the base 1 mm back from the tiles (the plate in pieces)" to without("rowgap", "baseapart") else null,
            if (tiles.heightsUm.size > 1) "each column's tiles printed highest first" to without("order") else null,
            "each tile started at its front-left corner" to without("corner"),
            "the frame left out" to without("frame"),
            "one frame layer, not ${plymeld.core.calibrate.FirstLayerGeometry.frameLayers}" to without("framelayers"),
            "the frame 1 mm further in, over the tiles' insides" to without("frameinside"),
            "the frame's first layer at its tiles' own height" to without("framelow"),
            "the lift off the frame rings left out" to without("framelift"),
            "the border left out" to without("border"),
            "the border at the base's first-layer edge, off its upper layer" to without("borderedge"),
            "a line laid outside the plate's cells" to addStrayLine(text, spec),
            "a retraction shortened" to alterRetraction(text),
            removeEndLift(text)?.let { "the end's lift removed" to it },
        )
        val controlMisses = controls.filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first }
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val prediction = plymeld.core.Predict.gcode(text, s.machineDescription, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        val g = plymeld.core.calibrate.FirstLayerGeometry
        echo("first-layer plate for filament '${spool.material}' on tool $slotN of '${Resolver.resolve(setup, "hardware.machine.model").value}', build plate '$plateName' (bed ${s.bedTempFirstC} C, constant): one layer of tiles, one piece, a frame over their walls and a border round the base, part fan off, flow ratio ${n(s.flowRatio)}")
        echo("columns (print order, coolest first, left to right): ${spec.tempsC.joinToString(", ") { "$it C" }}, each temperature raised on the base in front of its column")
        echo("rows (front to back, the same in every column): a group per speed, ${tiles.speeds.joinToString { n(it) }} mm/s (the first layer's ${n(s.firstLayerSpeed)} mm/s, plan.mechanism.motion.first_layer.speed, halved and half again), " +
            "a row per height in each, ${tiles.heightsUm.joinToString { n(it / 1000.0) }} mm (from the first layer's ${n(h / 1000.0)} mm, plan.mechanism.adhesion.first_layer.height, up in steps of a tenth of the bore); " +
            "each row's height and each speed raised on the base at the left")
        echo("each tile: ${n(g.tileLength / 1000.0)} mm long (its lines along X), ${tiles.heightsUm.indices.joinToString("/") { n(plate.tileDepth(it) / 1000.0) }} mm deep by height, ${n(tiles.widthUm / 1000.0)} mm lines (plan.mechanism.adhesion.first_layer.line.width), ${n(tiles.accel)} mm/s2, lift ${n(tiles.liftUm / 1000.0)} mm off each tile and frame ring; " +
            "starting at the middle of its back edge, its lines' ends reaching ${n(tiles.wallOverlap)} of a line into its wall (plan.layer.infill.wall_overlap); every tile touching its neighbors, so the plate is one piece; each column's tiles lowest first")
        echo("frame (not read): after all the tiles, ${g.frameLayers} layers of rings over every tile's wall only, each a layer (${n(s.layerHeightUm / 1000.0)} mm, plan.technique.flat.layer.height) above the one before from a layer above its tile, reaching ${n(g.fuse / 1000.0)} mm onto its neighbor so the rings join; " +
            "${n(s.outerWallWidthUm / 1000.0)} mm lines (plan.layer.walls.outer.line.width), ${n(spec.frameSpeed)} mm/s (the machine's print speed, hardware.machine.<key>.motion.print.speed ${n(s.hiddenSpeed)}, held to the hottest column's melt limit), ${n(s.defaultAccel)} mm/s2; part fan off; the raised edge to peel the plate by")
        echo("melt limits (the filament's maximum flow at each column's temperature; a tile over it is left out, not slowed):")
        for (k in 0 until spec.blocks) {
            val left = omitted(spec, k)
            echo("  ${spec.tempsC[k]} C: ${n(spec.capsMm3s[k])} mm3/s (${spec.capFrom[k]}); " + if (left.isEmpty()) "no tile left out" else "left out: " + left.joinToString())
        }
        echo("plate: ${n(plateW)} x ${n(plateD)} mm, centered on the ${n(bedW)} x ${n(bedD)} mm bed")
        val startPrime = startPrimeEnd(text)
        val firstTile = plate.tileBox(0, 0, 0).let { (it[0] + spec.originXUm) / 1000.0 to (it[1] + spec.originYUm) / 1000.0 }
        echo("between columns: retract, rise clear of the tiles, park ${n(park)} mm in front of the plate, wait for the next temperature (M109), then a prime line " +
            "along the base's front under the column (${n(plymeld.core.calibrate.FirstLayerGeometry.primeLen / 1000.0)} mm, overlapping the base's edge; not read). The first column primes too: " +
            (startPrime?.let { (x, y) -> "the start template's own prime ends at X${n(x)} Y${n(y)}, ${n(Math.hypot(firstTile.first - x, firstTile.second - y))} mm from the first tile" } ?: "the start template lays no prime line"))
        echo("the base and its labels: along the plate's front and left, its front-left corner cut off (the origin), ${g.baseLayers} layers (the first at ${n(spec.primeUm / 1000.0)} mm, the first layer's or the lowest tile's if lower, touching the tiles; the next set back ${n(g.baseSetback / 1000.0)} mm), " +
            "the labels raised ${g.labelLayers} layers on it, ${n(g.labelGap / 1000.0)} mm apart; ${n(s.firstLayerSpeed)} mm/s; the base's upper layers and the labels after all the tiles, at the last column's temperature; the border (not read): with the frame, as many layers of a ring round the base's whole outline, flush with its upper layer's edge, from a layer above its top, at the frame's speed and width")
        echo(when { level != null -> "bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level to skip it"; noLevel -> "bed levelling: skipped (--no-level)"; else -> "bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print" })
        warnings.forEach { echo("warning: $it") }
        val paFrom = Resolver.resolveOrNull(setup, "hardware.filament.pressure_advance", plymeld.model.Context(0))?.let { "the filament's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.tool.pressure_advance", plymeld.model.Context(0))?.let { "the tool's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.machine.pressure_advance", plymeld.model.Context(0))?.let { "the machine's, ${it.from}" }
        echo(pa[spool.tool]?.let { v -> "pressure advance: $v s ($paFrom)" }
            ?: ownPaEcho(setup) ?: "pressure advance: not set, so the printer's own value applies, unknown to plymeld (set the machine's, the tool's, or the filament's pressure_advance to state it)")
        echo("check: every tile's size, place, height, speed, width, filament per mm, and flow (within its column's melt limit), its start at the middle of its back edge, every column's temperature and the wait before it, " +
            "the prime lines along the base, the base (its corner cut off, its upper layers set back), each label where it goes (its lines inside its digits, every digit printed), every tile touching its neighbors and the plate one piece, each column's tiles lowest first, " +
            "the frame (${g.frameLayers} layers over the walls only, each a layer above the one under it, after all the tiles, lowest first), the border (as many layers round the base, on its upper layer, its corner cut), no travel across a finished line at its height, nothing else on the plate, and the g-code as the config says. Controls caught: ${controls.joinToString { it.first }}")
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        val paText = pa[spool.tool]?.let { v -> "$v s (${paFrom?.substringBefore(',')})" } ?: ownPaPage(setup) ?: "not set: the printer's own, unknown to plymeld (write down what it was if you know)"
        plymeld.core.calibrate.FirstLayerInstructions.write(page, spec, plate, plymeld.core.calibrate.PlateInstructions.Info(
            spool.material, Resolver.resolve(setup, "hardware.machine.model").value.toString(), spool.tool, PlymeldVersion.value, output.fileName.toString(),
            prediction.timeS?.let { it / 60 }, paText, s.bedTempFirstC, s.firstLayerSpeed, s.firstLayerSpeed, s.retractMm,
            plate = plateName, levels = level != null, levelSkipped = noLevel && s.levelGcode != null, warnings = warnings))
        echo("-> $output")
        echo("-> $page (and ${page.fileName.toString().removeSuffix(".md")}.png): before printing, the conditions, the picture, how to read it")
        echo("predicted: $prediction (the waits for each column's temperature are not in it)")
    }

    /** Every first-layer plate cell's own height (mm), for the g-code check: its tiles, its frame rings, its prime lines and labels. */
    private fun firstLayerOwnZ(spec: plymeld.core.calibrate.FirstLayerSpec): Map<String, Double> {
        val t = spec.tiles
        val g = plymeld.core.calibrate.FirstLayerGeometry
        val out = HashMap<String, Double>()
        for (k in 0 until spec.blocks) {
            for ((r, c) in spec.rows) {
                out[spec.tileLabel(k, r, c)] = t.heightsUm[c] / 1000.0
                for (n in 1..plymeld.core.calibrate.FirstLayerGeometry.frameLayers) out[spec.frameLabel(k, r, c, n)] = spec.frameUm(c, n) / 1000.0
            }
            out[spec.primeLabel(k)] = spec.primeUm / 1000.0
            for (n in 1..g.labelLayers) out[spec.markLabel(k, n)] = spec.labelUm(n) / 1000.0
        }
        for (n in 1..g.labelLayers) {
            for ((r, c) in spec.rows) out[spec.heightLabelOf(r, c, n)] = spec.labelUm(n) / 1000.0
            for (r in t.speeds.indices) out[spec.speedLabelOf(r, n)] = spec.labelUm(n) / 1000.0
        }
        for (n in 1..g.baseLayers) out[spec.baseLabel(n)] = spec.baseUm(n) / 1000.0
        for (n in 1..g.frameLayers) out[spec.borderLabel(n)] = spec.borderUm(n) / 1000.0
        return out
    }

    /** Where the start template's last extrusion ends (mm), before the first layer: its prime line; null = it lays none. */
    private fun startPrimeEnd(text: String): Pair<Double, Double>? {
        var x = Double.NaN; var y = Double.NaN; var end: Pair<Double, Double>? = null
        for (l in text.lines()) {
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) break
            val c = l.substringBefore(';').trim(); if (!c.startsWith("G1 ") && !c.startsWith("G0 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y
            if ((num('E') ?: 0.0) > 0 && !x.isNaN() && (nx != x || ny != y)) end = nx to ny
            x = nx; y = ny
        }
        return end
    }

    /** The first-layer plate's conditions in its own g-code, as `; name = value` comments after its first lines. */
    private fun withFirstLayerConditions(text: String, spec: plymeld.core.calibrate.FirstLayerSpec, plate: plymeld.core.calibrate.FirstLayerPlate, filament: String, tool: Int, s: SliceSettings, pa: Double?): String {
        val t = spec.tiles
        val g = plymeld.core.calibrate.FirstLayerGeometry
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = first-layer (one layer of first-layer tiles, a column per nozzle temperature and a row per speed and height, one piece, a frame over their walls and a border round the base)")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; first_layer_height = ${n(spec.layerUm / 1000.0)} mm (the prime lines and labels at ${n(spec.primeUm / 1000.0)} mm; each tile at its own), first_layer_line_width = ${n(spec.firstWidthUm / 1000.0)} mm, flow_ratio = ${n(spec.flowRatio)}")
            add("; pressure_advance = ${pa?.let { "$it s" } ?: "the printer's own (not set: unknown)"}, bed = ${s.bedTempFirstC} C (constant), part fan = off, retraction = ${n(spec.prime.retractMm)} mm")
            add("; first_layer_tiles = speeds ${t.speeds.joinToString(", ") { n(it) }} mm/s (a group of rows each, front to back), heights ${t.heightsUm.joinToString(", ") { n(it / 1000.0) }} mm (a row each in every group, front to back), " +
                "each tile ${n(g.tileLength / 1000.0)} mm long, ${n(t.widthUm / 1000.0)} mm lines along X, ${n(t.accel)} mm/s2, lift ${n(t.liftUm / 1000.0)} mm, each starting at the middle of its back edge, line ends ${n(t.wallOverlap)} of a line into the wall, " +
                "every tile touching its neighbors (one piece), each column lowest first; a tile over its column's melt limit left out")
            add("; frame = after all the tiles, ${g.frameLayers} layers of rings over every tile's wall only, from a layer above it, and of a ring round the base flush with its upper layer's edge, from a layer above its top (the border), ${n(spec.frameWidthUm / 1000.0)} mm lines ${n(spec.frameLayerUm / 1000.0)} mm high reaching ${n(g.fuse / 1000.0)} mm onto the neighbor, " +
                "${n(spec.frameSpeed)} mm/s (the machine's ${n(spec.frame.speed)} held to the hottest column's melt limit), ${n(spec.frame.accel)} mm/s2, part fan off, lowest first (not read)")
            add("; prime_lines = ${n(g.primeLen / 1000.0)} mm along the base's front under each column, after its wait, at ${n(spec.prime.speed)} mm/s (not read)")
            add("; base = along the plate's front and left, its front-left corner cut off ${n(plymeld.core.calibrate.PlateMarks.CHAMFER / 1000.0)} mm, ${g.baseLayers} layers (the next set back ${n(g.baseSetback / 1000.0)} mm), the labels raised ${g.labelLayers} layers on it: each column's temperature, each speed, each row's height")
            for (k in 0 until spec.blocks) add("; block ${k + 1} = ${spec.tempsC[k]} C, the column ${k + 1} from the left, its temperature printed in front of it, " +
                "melt limit ${n(spec.capsMm3s[k])} mm3/s (${spec.capFrom.getOrElse(k) { "not said" }}), " + omitted(spec, k).let { if (it.isEmpty()) "no tile left out" else "left out: " + it.joinToString("; ") })
            add("; CALIBRATION_PLATE_END")
        }
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /**
     * The ironing plate: one piece testing combinations of every ironing setting, one flat slab whose top is divided
     * into zones, each ironed with its own combination of the ironing values (flow, spacing, speed, angle, inset), a control
     * zone per speed as laid, the top and its ironing at the filament's print temperature and the first layer at its
     * first-layer temperature; printed exactly as specified, checked, with its page.
     */
    private fun runIroning() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        // the top's temperature: the filament's own print temperature (ironing is read at the temperature the filament prints at);
        // the first layer at the filament's first-layer temperature, worked out from it
        val printC = (plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { whole(it, "the filament's temperature.print") }
            ?: throw IllegalArgumentException("the ironing plate prints at the filament's own temperature (temperature.print), which '${plain.spool(plain.objects[0].spool).material}' has none of")
        val (config, slotN) = plateConfig(toolN, printC, firstLayerToo = false)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val h = s.layerHeightUm; val w = s.outerWallWidthUm
        // the spreads: given, or centered on the filament's current ironing values as resolved for it on this tool (the
        // placeholders are plymeld's choice, not measured: the option help names them)
        fun values(opt: String?, name: String, example: String, parse: (String) -> Double?, default: List<Double>, rising: Boolean = true): List<Double> {
            val vs = opt?.let { v -> v.split(',').map { parse(it.trim()) ?: throw IllegalArgumentException("$name is a list, e.g. $example (got '$v')") } } ?: default
            if (vs.isEmpty()) throw IllegalArgumentException("$name names no value")
            if (rising && vs.zipWithNext().any { (a, b) -> b <= a }) throw IllegalArgumentException("$name must rise (got ${vs.joinToString()})")
            if (vs.distinct().size != vs.size) throw IllegalArgumentException("$name has a value twice (got ${vs.joinToString()})")
            return vs
        }
        fun r3(v: Double) = Math.round(v * 1000) / 1000.0
        val flows = values(ironingFlows, "--ironing-flows", "0.05,0.1,0.15 or 5%,10%,15%",
            { t -> t.removeSuffix("%").toDoubleOrNull()?.let { v -> if (t.endsWith("%")) v / 100 else v }?.takeIf { it > 0 && it <= 1 } }, listOf(0.5, 1.0, 1.5).map { r3(s.ironingFlow * it) })
        val spacings = values(ironingSpacings, "--ironing-spacings", "0.1,0.15,0.2", { t -> t.removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { it > 0 } }, listOf(1.0, 1.5, 2.0).map { r3(s.ironingSpacingUm / 1000.0 * it) })
        val speeds = values(ironingSpeeds, "--ironing-speeds", "15,30,45", { t -> t.toDoubleOrNull()?.takeIf { it > 0 } }, listOf(0.5, 1.0, 1.5).map { r3(s.ironingSpeed * it) })
        val angles = values(ironingAngles, "--ironing-angles", "0,45,90", { t -> t.removeSuffix("deg").trim().toDoubleOrNull() }, listOf(0.0, 45.0, 90.0).map { r3((s.ironingAngleDeg + it) % 180.0) }, rising = false)
        val insets = values(ironingInsets, "--ironing-insets", "0.1,0.2,0.4", { t -> t.removeSuffix("mm").trim().toDoubleOrNull()?.takeIf { it >= 0 } }, listOf(0.5, 1.0, 2.0).map { r3(s.ironingInsetUm / 1000.0 * it) })
        if (speeds.any { it > s.hiddenSpeed + 1e-9 }) throw UsageError("--ironing-speeds: ${speeds.max()} mm/s is past the machine's print speed (hardware.machine.<key>.motion.print.speed ${s.hiddenSpeed})")
        // the slab's values: the first layer at its configured speed held to the filament's maximum flow at its own thickness and
        // width; the middle layers at the frame's (the machine's print speed and acceleration, held to the maximum flow); the
        // top fill at a visible surface's speed, held the same, and acceleration: the condition the zones are read under
        val hm = h / 1000.0; val fhm = s.firstLayerHeightUm / 1000.0
        val meltCap = s.maxVolumetric / (hm * (w / 1000.0 - hm * (1 - Math.PI / 4)) * s.flowRatio)
        val firstSpeed = Math.min(s.firstLayerSpeed, s.maxVolumetric / (fhm * (s.firstLayerWidthUm / 1000.0 - fhm * (1 - Math.PI / 4)) * s.flowRatio))
        val level = s.levelGcode?.takeIf { !noLevel }
        fun spec(ox: Long, oy: Long) = try {
            plymeld.core.calibrate.IroningSpec(spool.tool, spool.id, speeds.toDoubleArray(), flows.toDoubleArray(), spacings.map { Math.round(it * 1000) }.toLongArray(), angles.toDoubleArray(), insets.map { Math.round(it * 1000) }.toLongArray(),
                h, s.firstLayerHeightUm, plymeld.core.calibrate.IroningGeometry.layers, w, s.firstLayerWidthUm, s.nozzleDiameterUm, s.filamentDiameterMm, s.flowRatio, printC, s.nozzleTempFirstC, s.fanMinPct,
                adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm), frame = PathValues(Math.min(s.hiddenSpeed, meltCap), s.defaultAccel, s.flowRatio, s.retractMm),
                top = PathValues(Math.min(s.visibleSpeed, meltCap), s.visibleAccel, s.flowRatio, s.retractMm), ironAccel = s.visibleAccel, retractMm = s.retractMm, liftUm = s.zHopUm,
                originXUm = ox, originYUm = oy, levelGcode = level)
        } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "the ironing plate's values") }
        // the bed: the slab centered on it; too big is refused (fewer values), tight gets a warning
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val draft = spec(0, 0)
        // the piece with its brim: what must fit the bed
        val brimMm = plymeld.core.calibrate.IroningGeometry.brim / 1000.0
        val plateW = draft.pieceW / 1000.0 + 2 * brimMm; val plateD = draft.pieceD / 1000.0 + 2 * brimMm
        if (plateW > bedW || plateD > bedD) throw IllegalArgumentException("the ironing plate does not fit this machine: ${draft.zones.size} zones of ${n(plymeld.core.calibrate.IroningGeometry.zone / 1000.0)} mm on ${draft.slabs} slabs make a piece ${n(plateW)} x ${n(plateD)} mm with its brim; " +
            "the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build). Fewer values (--ironing-speeds, --ironing-flows, --ironing-spacings), or two plates")
        val fitWarnings = listOfNotNull(if (bedW - plateW < 2 * PlateGeometry.bedSpare / 1000.0 || bedD - plateD < 2 * PlateGeometry.bedSpare / 1000.0) "the plate is ${n(plateW)} x ${n(plateD)} mm on a ${n(bedW)} x ${n(bedD)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at an edge; check the bed's clips or wiper are clear of it" else null)
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val spec = spec(cx - draft.pieceW / 2, cy - draft.pieceD / 2)
        val plate = plymeld.core.calibrate.IroningPlate(spec)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, listOf(printC), "the plate's temperature")
        val pa = setup.pressureAdvanceByTool()
        fun gcodeOf(p: plymeld.core.calibrate.IroningPlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val text = withIroningConditions(gcodeOf(plate), spec, spool.material, slotN, s, pa[spool.tool])
        // the read-back checks: the plate's specification and the config; each with controls, copies deliberately wrong, that must fail them
        val expect = plymeld.core.check.Expectations.forSetup(setup)
        fun allProblems(t: String) = plymeld.core.calibrate.IroningCheck.check(spec, t) + plymeld.core.check.GcodeCheck.check(t, expect)
        val problems = allProblems(text)
        fun without(vararg c: String) = gcodeOf(plymeld.core.calibrate.IroningPlate(spec, without = c.toSet()))
        val z2 = spec.zoneLabel(1)
        val controls = listOfNotNull(
            "a zone's flow doubled" to alterZoneFlow(text, z2),
            "a zone at 1.5x its speed" to alterTileSpeed(text, z2),
            "zone 2's lines at its angle plus 30 degrees" to without("angle"),
            "zone 2's lines at 1.5x its spacing" to without("spacing"),
            "zone 2's pass 0.3 mm further in from the slab's edge" to without("inset"),
            "the controls ironed too" to without("control"),
            "the labels (the notches) left out" to without("labels"),
            "the legend left out" to without("legend"),
            "the brim left out" to without("brim"),
            "the chamfer left out" to without("chamfer"),
            "the lift off the top and off each zone left out" to plymeld.core.calibrate.IroningControls.withoutZoneLifts(without("lift")),
            "the zones in reverse order" to without("order"),
            "the top fill at the frame's speed" to without("topspeed"),
            "the top fill at 45 degrees, not along the slab" to without("topangle"),
            if (spec.firstTempC != spec.tempC) "the first layer at the top's temperature" to without("firsttemp") else null,
            if (spec.firstTempC != spec.tempC) "no wait for the top's temperature before it" to without("topwait") else null,
            "the part fan ${if (spec.fanPct > 0) "off from the second layer" else "on at the middle layer"}" to (if (spec.fanPct > 0) alterFan(text, toOff = true) else fanOnMidway(text)),
            "a line laid outside the plate's cells" to addStrayIroningLine(text, spec),
            "a retraction shortened" to alterRetraction(text),
            removeEndLift(text)?.let { "the end's lift removed" to it },
        )
        val controlMisses = controls.filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first }
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val machineDesc = s.machineDescription
        val prediction = plymeld.core.Predict.gcode(text, machineDesc, changeS, plymeld.core.Predict.limits(s))
        // the ironing's share of the time, by the zones' cells
        val (cellTime, _) = plymeld.core.Predict.byCell(text, machineDesc, changeS, plymeld.core.Predict.limits(s))
        val ironS = cellTime?.filterKeys { it.startsWith("zone ") }?.values?.sum()
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        echo("ironing plate for filament '${spool.material}' on tool $slotN of '${Resolver.resolve(setup, "hardware.machine.model").value}', build plate '$plateName' (bed ${s.bedTempFirstC} C, constant): " +
            "one piece ${n(spec.pieceW / 1000.0)} x ${n(spec.pieceD / 1000.0)} mm with a ${n(brimMm)} mm brim: ${spec.slabs} slabs ${n(spec.slabW / 1000.0)} x ${n(spec.slabD / 1000.0)} x ${n(spec.topZ / 1000.0)} mm (${plymeld.core.calibrate.IroningGeometry.rows} per speed, a row of zones each) on a base of the first ${plymeld.core.calibrate.IroningGeometry.baseLayers} layers, the legend behind them " +
            "(${spec.layers} layers: the first ${n(spec.firstLayerUm / 1000.0)} mm, then ${n(h / 1000.0)} mm), ${n(w / 1000.0)} mm lines (the first layer ${n(spec.firstWidthUm / 1000.0)} mm), flow ratio ${n(s.flowRatio)}, the top and its ironing at $printC C (the filament's temperature.print)" +
            (if (spec.firstTempC != printC) ", the first layer at ${spec.firstTempC} C (its temperature.first_layer), the top's temperature waited for with the head parked before the top" else "") + ", part fan ${n(spec.fanPct)} % from the second layer")
        echo("zones: ${spec.zones.size} of ${n(plymeld.core.calibrate.IroningGeometry.zone / 1000.0)} mm, ${spec.columns} to a slab with a ${n(plymeld.core.calibrate.IroningGeometry.frame / 1000.0)} mm strip as laid between neighbors; zone 1 at the chamfered front-left corner, zone 2 behind it on the next slab, then the next column (the print order); " +
            "each zone's front edge notched with its flow (squares) and spacing (Vs), its back edge with its inset (round-bottomed) and angle (slanted), each slab's left end with its speed (bars); the legend: ${spec.legend.joinToString(" / ")}")
        echo("the plan: a block of ${spec.perBlock} zones per speed (${speeds.joinToString { n(it) }} mm/s), each its control (not ironed) then flow (${flows.joinToString { n(it * 100) + " %" }}) x spacing (${spacings.joinToString { n(it) }} mm), " +
            "the angles (${angles.joinToString { n(it) }} deg) and insets (${insets.joinToString { n(it) }} mm) over the grid as Latin squares; every value read in ${spec.coverage().values.flatMap { it.values }.minOf { it.size }} zones or more")
        for (z in spec.zones) echo("  zone ${z.index + 1} (slab ${spec.slab(z.index) + 1}, column ${spec.col(z.index) + 1}, ${if (spec.row(z.index) == 0) "front" else "back"}): ${spec.describe(z.index)}")
        echo("values: the first layer ${n(firstSpeed)} mm/s; the middle layers ${n(spec.frame.speed)} mm/s, ${n(spec.frame.accel)} mm/s2 (the machine's, held to the maximum flow); the top fill ${n(spec.top.speed)} mm/s, ${n(spec.top.accel)} mm/s2 (a visible surface's), its lines along the slab; " +
            "each zone at its speed and ${n(spec.ironAccel)} mm/s2, its plastic by the ironing rule (flow x spacing x layer height), lifted ${n(spec.liftUm / 1000.0)} mm off the top and off each zone before traveling on")
        echo("plate: ${n(plateW)} x ${n(plateD)} mm, centered on the ${n(bedW)} x ${n(bedD)} mm bed")
        echo(when { level != null -> "bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level to skip it"; noLevel -> "bed levelling: skipped (--no-level)"; else -> "bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print" })
        warnings.forEach { echo("warning: $it") }
        val paFrom = Resolver.resolveOrNull(setup, "hardware.filament.pressure_advance", plymeld.model.Context(0))?.let { "the filament's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.tool.pressure_advance", plymeld.model.Context(0))?.let { "the tool's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.machine.pressure_advance", plymeld.model.Context(0))?.let { "the machine's, ${it.from}" }
        echo(pa[spool.tool]?.let { v -> "pressure advance: $v s ($paFrom)" }
            ?: ownPaEcho(setup) ?: "pressure advance: not set, so the printer's own value applies, unknown to plymeld (set the machine's, the tool's, or the filament's pressure_advance to state it)")
        echo("check: every slab layer's height, speed, width, and filament per mm; the top fill along the slab, one bead spacing apart; each zone's speed, acceleration, plastic by the ironing rule, angle, spacing, and inset from the slab's edge, inside its cell, in order after the top; nothing over a control; the frames between zones left as laid; the base's size and chamfer, the brim, each slab in its place, the zones' labels, the slabs' speed bars, and the legend; the temperatures (the first layer's and the top's), each waited for, and the fan; no travel across the finished top or a finished zone at its height; and the g-code as the config says. Controls caught: ${controls.joinToString { it.first }}")
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        val paText = pa[spool.tool]?.let { v -> "$v s (${paFrom?.substringBefore(',')})" } ?: ownPaPage(setup) ?: "not set: the printer's own, unknown to plymeld (write down what it was if you know)"
        plymeld.core.calibrate.IroningInstructions.write(page, spec, plate, plymeld.core.calibrate.PlateInstructions.Info(
            spool.material, Resolver.resolve(setup, "hardware.machine.model").value.toString(), spool.tool, PlymeldVersion.value, output.fileName.toString(),
            prediction.timeS?.let { it / 60 }, paText, s.bedTempFirstC, firstSpeed, spec.frame.speed, s.retractMm,
            plate = plateName, levels = level != null, levelSkipped = noLevel && s.levelGcode != null, warnings = warnings))
        echo("-> $output")
        echo("-> $page (and ${page.fileName.toString().removeSuffix(".md")}.png): the question, before printing, the conditions, the picture, the zone plan, how to grade, what to enter")
        echo("predicted: $prediction" + (ironS?.let { "; the ironing passes ${n(it / 60)} min of it" } ?: ""))
        echo("after printing: `plymeld calibrate ironing readings ${output.fileName} -o readings.toml`, grade each zone, then `plymeld calibrate ironing values readings.toml -o ironing.toml`")
    }

    /**
     * The seam plate: [SeamPiece.STANDARD] on one slab, each piece's outer
     * wall seamed one way, printed exactly as specified, checked against its specification with controls that must fail,
     * its conditions in its g-code, its page beside it.
     */
    private fun runSeams() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        // the pieces' temperature: the filament's own print temperature (a seam is read at the temperature the filament prints at);
        // the slab's first layer at the filament's first-layer temperature, worked out from it
        val printC = (plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { whole(it, "the filament's temperature.print") }
            ?: throw IllegalArgumentException("the seam plate prints at the filament's own temperature (temperature.print), which '${plain.spool(plain.objects[0].spool).material}' has none of")
        val (config, slotN) = plateConfig(toolN, printC, firstLayerToo = false)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val h = s.layerHeightUm; val w = s.outerWallWidthUm; val wi = s.innerWallWidthUm
        // the scarf as the config has it (plan.technique.scarf_seam.length, .start) and the slicer's corner (corner_seam.corner_angle)
        val scarfUm = s.scarfLengthUm ?: throw IllegalArgumentException("the seam plate prints plymeld's scarf seam, whose length plan.technique.scarf_seam.length is not set")
        val scarfStart = s.scarfStart ?: throw IllegalArgumentException("the seam plate prints plymeld's scarf seam, whose starting thickness plan.technique.scarf_seam.start is not set")
        val cornerDeg = s.seamCornerDeg ?: throw IllegalArgumentException("plan.technique.corner_seam.corner_angle is not set: the page says what the slicer counts as a corner")
        // the values: the slab's first layer at its configured speed held to the filament's maximum flow; its layers above at the
        // frame's; the outer walls at a visible wall's speed and acceleration (the condition the seams are read under); the inner
        // walls at the machine's print speed; every speed held to the maximum flow
        val hm = h / 1000.0; val fhm = s.firstLayerHeightUm / 1000.0
        fun meltCap(lw: Long, lh: Double) = s.maxVolumetric / (lh * (lw / 1000.0 - lh * (1 - Math.PI / 4)) * s.flowRatio)
        val firstSpeed = Math.min(s.firstLayerSpeed, meltCap(s.firstLayerWidthUm, fhm))
        val level = s.levelGcode?.takeIf { !noLevel }
        fun spec(ox: Long, oy: Long) = try {
            plymeld.core.calibrate.SeamSpec(spool.tool, spool.id, plymeld.core.calibrate.SeamPiece.STANDARD, h, s.firstLayerHeightUm, w, wi, s.firstLayerWidthUm, s.filamentDiameterMm, s.flowRatio, printC, s.nozzleTempFirstC, s.fanMinPct,
                adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm), frame = PathValues(Math.min(s.hiddenSpeed, meltCap(w, hm)), s.defaultAccel, s.flowRatio, s.retractMm),
                outer = PathValues(Math.min(s.visibleSpeed, meltCap(w, hm)), s.visibleAccel, s.flowRatio, s.retractMm), inner = PathValues(Math.min(s.hiddenSpeed, meltCap(wi, hm)), s.defaultAccel, s.flowRatio, s.retractMm),
                scarfUm = scarfUm, scarfStart = scarfStart, cornerDeg = cornerDeg, liftUm = s.zHopUm, originXUm = ox, originYUm = oy, levelGcode = level)
        } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "the seam plate's values") }
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val draft = spec(0, 0)
        val plateW = draft.slabW / 1000.0; val plateD = draft.slabD / 1000.0
        if (plateW > bedW || plateD > bedD) throw IllegalArgumentException("the seam plate does not fit this machine: ${draft.pieces.size} pieces of ${n(plymeld.core.calibrate.SeamGeometry.piece / 1000.0)} mm make a slab ${n(plateW)} x ${n(plateD)} mm; the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build)")
        val fitWarnings = listOfNotNull(if (bedW - plateW < 2 * PlateGeometry.bedSpare / 1000.0 || bedD - plateD < 2 * PlateGeometry.bedSpare / 1000.0) "the plate is ${n(plateW)} x ${n(plateD)} mm on a ${n(bedW)} x ${n(bedD)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at an edge; check the bed's clips or wiper are clear of it" else null)
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val spec = spec(cx - draft.slabW / 2, cy - draft.slabD / 2)
        val plate = plymeld.core.calibrate.SeamPlate(spec)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, listOf(printC), "the plate's temperature")
        val pa = setup.pressureAdvanceByTool()
        fun gcodeOf(p: plymeld.core.calibrate.SeamPlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val text = withSeamConditions(gcodeOf(plate), spec, spool.material, slotN, s, pa[spool.tool])
        // the g-code check accepts a scarf ramp below its layer only where the job scarfs; this plate scarfs by its own specification
        val expect = plymeld.core.check.Expectations.forSetup(setup, scarfStartOverride = scarfStart)
        fun allProblems(t: String) = plymeld.core.calibrate.SeamCheck.check(spec, t) + plymeld.core.check.GcodeCheck.check(t, expect)
        val problems = allProblems(text)
        fun without(vararg c: String) = gcodeOf(plymeld.core.calibrate.SeamPlate(spec, without = c.toSet()))
        val controls = listOfNotNull(
            "piece 2 printed with a point seam, not its scarf" to without("scarf"),
            "piece 1 scarfed, not its point seam" to without("point"),
            "piece 3's seam at the back of the piece" to without("seam"),
            "piece 5's seam in the middle of its face, not its corner" to without("corner"),
            "piece 7 vertical, not leaning" to without("lean"),
            "piece 3's inner wall seamed at the front" to without("inner"),
            "the ticks left out" to without("ticks"),
            "the chamfer left out" to without("chamfer"),
            "the pieces in reverse order" to without("order"),
            "each outer wall before its inner" to without("outerfirst"),
            "the slab's upper layers at the outer wall's speed" to without("slabspeed"),
            if (spec.firstTempC != spec.tempC) "the slab's first layer at the pieces' temperature" to without("firsttemp") else null,
            if (spec.firstTempC != spec.tempC) "no wait for the pieces' temperature before them" to without("piecewait") else null,
            "a piece's outer wall at 1.5x its speed" to alterTileSpeed(text, spec.outerLabel(2)),
            "a piece's outer wall with twice its plastic" to alterZoneFlow(text, spec.outerLabel(2)),
            "the part fan ${if (spec.fanPct > 0) "off from the second layer" else "on at the middle layer"}" to (if (spec.fanPct > 0) alterFan(text, toOff = true) else fanOnMidway(text)),
            "a line laid outside the plate's cells" to addStraySeamLine(text, spec),
            "a retraction shortened" to alterRetraction(text),
            removeEndLift(text)?.let { "the end's lift removed" to it },
        )
        val controlMisses = controls.filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first }
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val machineDesc = s.machineDescription
        val prediction = plymeld.core.Predict.gcode(text, machineDesc, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        echo("seam plate for filament '${spool.material}' on tool $slotN of '${Resolver.resolve(setup, "hardware.machine.model").value}', build plate '$plateName' (bed ${s.bedTempFirstC} C, constant): " +
            "one slab ${n(plateW)} x ${n(plateD)} mm (${plymeld.core.calibrate.SeamGeometry.slabLayers} layers: the first ${n(spec.firstLayerUm / 1000.0)} mm, then ${n(h / 1000.0)} mm) with ${spec.pieces.size} pieces ${n(plymeld.core.calibrate.SeamGeometry.piece / 1000.0)} mm across and ${n(spec.pieceLayers * h / 1000.0)} mm tall (${spec.pieceLayers} layers), " +
            "outer walls ${n(w / 1000.0)} mm, inner ${n(wi / 1000.0)} mm, flow ratio ${n(s.flowRatio)}, the pieces at $printC C (the filament's temperature.print)" +
            (if (spec.firstTempC != printC) ", the slab's first layer at ${spec.firstTempC} C (its temperature.first_layer), waited for with the head parked before the pieces" else "") + ", part fan ${n(spec.fanPct)} % from the second layer")
        for (p in spec.pieces) echo("  piece ${p.index + 1}: ${spec.describe(p.index)}")
        echo("values: the slab's first layer ${n(firstSpeed)} mm/s, its layers above ${n(spec.frame.speed)} mm/s and ${n(spec.frame.accel)} mm/s2; the outer walls ${n(spec.outer.speed)} mm/s and ${n(spec.outer.accel)} mm/s2 (a visible wall's, held to the maximum flow; no overhang slow-down on the leaning faces); the inner walls ${n(spec.inner.speed)} mm/s; " +
            "the scarf ${n(scarfUm / 1000.0)} mm long starting ${n(scarfStart * 100)} % of the layer thick (plan.technique.scarf_seam); a corner ${n(cornerDeg)} deg (plan.technique.corner_seam.corner_angle)")
        echo("plate: ${n(plateW)} x ${n(plateD)} mm, centered on the ${n(bedW)} x ${n(bedD)} mm bed; piece 1 at the chamfered front-left corner, a tick between each pair of pieces, every ${plymeld.core.calibrate.SeamGeometry.tickLongEvery}th long")
        echo(when { level != null -> "bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level to skip it"; noLevel -> "bed levelling: skipped (--no-level)"; else -> "bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print" })
        warnings.forEach { echo("warning: $it") }
        val paFrom = Resolver.resolveOrNull(setup, "hardware.filament.pressure_advance", plymeld.model.Context(0))?.let { "the filament's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.tool.pressure_advance", plymeld.model.Context(0))?.let { "the tool's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.machine.pressure_advance", plymeld.model.Context(0))?.let { "the machine's, ${it.from}" }
        echo(pa[spool.tool]?.let { v -> "pressure advance: $v s ($paFrom)" }
            ?: ownPaEcho(setup) ?: "pressure advance: not set, so the printer's own value applies, unknown to plymeld (set the machine's, the tool's, or the filament's pressure_advance to state it)")
        echo("check: the layers and cells; every slab layer's and wall's height, speed, width, and filament per mm; each piece's seam where specified on every layer, a point seam's loop flat and closed, a scarf's ramp from the starting thickness to the top over the scarf length and laid again; the leaning faces' step-out; the pieces in order, inner wall first; the slab's size, chamfer, and ticks; the temperatures (the slab's first layer's and the pieces'), each waited for, and the fan; no travel across a piece at its layer's height; and the g-code as the config says. Controls caught: ${controls.joinToString { it.first }}")
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        val paText = pa[spool.tool]?.let { v -> "$v s (${paFrom?.substringBefore(',')})" } ?: ownPaPage(setup) ?: "not set: the printer's own, unknown to plymeld (write down what it was if you know)"
        plymeld.core.calibrate.SeamInstructions.write(page, spec, plate, plymeld.core.calibrate.PlateInstructions.Info(
            spool.material, Resolver.resolve(setup, "hardware.machine.model").value.toString(), spool.tool, PlymeldVersion.value, output.fileName.toString(),
            prediction.timeS?.let { it / 60 }, paText, s.bedTempFirstC, firstSpeed, spec.frame.speed, s.retractMm,
            plate = plateName, levels = level != null, levelSkipped = noLevel && s.levelGcode != null, warnings = warnings))
        echo("-> $output")
        echo("-> $page (and ${page.fileName.toString().removeSuffix(".md")}.png): the question, before printing, the conditions, the picture, the pieces, how to grade, what to enter")
        echo("predicted: $prediction")
        echo("after printing: `plymeld calibrate seams readings ${output.fileName} -o readings.toml`, grade each piece, then `plymeld calibrate seams values readings.toml -o seams.toml`")
    }

    /** A pressure advance as an option gives it: seconds, "0.04" or "0.04 s", or milliseconds, "40 ms"; 0 or more. */
    private fun seconds(v: String, opt: String): Double {
        val t = v.trim()
        val ms = t.endsWith("ms")
        val num = t.removeSuffix("ms").removeSuffix("s").trim().toDoubleOrNull()?.takeIf { it >= 0 && it.isFinite() }
            ?: throw UsageError("$opt: '$v' is a pressure advance in seconds (0.04, 0.04 s) or milliseconds (40 ms), 0 or more")
        return if (ms) num / 1000 else num
    }

    /**
     * The pressure-advance plates ([plymeld.core.calibrate.PressureAdvancePlate]): the find plate (two rows of step walls,
     * each value twice, a corner beside each) at --values, the values evenly inside --bracket, or 0 and the tool's lowest value
     * up to its top, each a fixed ratio below the next (the lowest of its ceiling, the highest value the extruder's push allows,
     * and the highest whose walls fit the bed); the confirm plate (--confirm VALUE --confirm-step S: the value and its
     * neighbors at three flow steps; --confirm machine on a machine that sets its own: its value, no command). The walls at
     * the filament's print temperature; slow at a visible wall's speed and fast at the machine's print speed, every line held
     * to the plate's share of the filament's measured maximum flow (--speeds gives both); the machine's print acceleration;
     * every move at a wall's value within the share of the extruder's highest filament speed, where it is known.
     */
    private fun runPressureAdvance() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        val printC = (plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { whole(it, "the filament's temperature.print") }
            ?: throw IllegalArgumentException("the pressure-advance plate prints at the filament's own temperature (temperature.print), which '${plain.spool(plain.objects[0].spool).material}' has none of")
        val (config, slotN) = plateConfig(toolN, printC, firstLayerToo = false)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val model = Resolver.resolve(setup, "hardware.machine.model").value.toString()
        val own = setup.ownPressureAdvance()
        val command = s.machineDescription.pressureAdvance
        val confirmText = paConfirm?.trim()
        val machineCheck = confirmText == "machine"
        if (own && !machineCheck) throw IllegalArgumentException("machine '$machineName' sets its own pressure advance (hardware.machine.$machineName.firmware.own_pressure_advance), so plymeld writes none and cannot print walls at values of its own. " +
            "`--confirm machine` prints the confirm plate at the machine's own value, with no command written, to check it")
        if (!own && machineCheck) throw IllegalArgumentException("--confirm machine checks the value of a machine that sets its own (hardware.machine.<key>.firmware.own_pressure_advance = true), which '$machineName' does not: give the value to confirm, e.g. --confirm 0.04 --confirm-step 0.004")
        if (!own && command == null) throw IllegalArgumentException("plymeld knows no command to set pressure advance on the model '$model' (hardware.model.$model.pressure_advance.command, with .unit): " +
            "the plate sets a value before each wall, so it cannot be printed on this machine until its model says how (Klipper: SET_PRESSURE_ADVANCE ADVANCE={pressure_advance}, in s)")
        val kind = if (confirmText != null) plymeld.core.calibrate.PressureAdvanceSpec.Kind.CONFIRM else plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND
        if (kind == plymeld.core.calibrate.PressureAdvanceSpec.Kind.CONFIRM && (paValues != null || paBracket != null)) throw UsageError("--confirm prints the confirm plate; --values and --bracket are the find plate's")
        if (kind == plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND && paConfirmStep != null) throw UsageError("--confirm-step goes with --confirm")
        if (paValues != null && paBracket != null) throw UsageError("--values or --bracket, not both")
        val h = s.layerHeightUm; val w = s.outerWallWidthUm
        val hm = h / 1000.0
        // the plate's margin below its two measured limits (the filament's maximum flow, the extruder's highest filament speed)
        val shareR = Resolver.resolve(setup, "hardware.tool.calibrate.pressure_advance.share", plymeld.model.Context(0))
        val share = (shareR.value as Number).toDouble()
        if (!(share > 0 && share <= 1)) throw IllegalArgumentException("hardware.tool.<key>.calibrate.pressure_advance.share is a share above 0 and at most 100 % (got ${n(share * 100)} %, ${shareR.from})")
        // every line the plate lays at no more than the share of the filament's measured maximum flow (the general buffer aside)
        fun flowCap(lw: Long, lh: Double) = share * s.maxVolumetricMeasured / (lh * (lw / 1000.0 - lh * (1 - Math.PI / 4)) * s.flowRatio)
        val flowWords = "${n(share * 100)} % of the filament's measured maximum flow, ${n(share * s.maxVolumetricMeasured)} of ${n(s.maxVolumetricMeasured)} mm3/s (hardware.tool.<key>.calibrate.pressure_advance.share)"
        // the speeds: a visible wall's for the slow ones, the machine's print speed for the fast, each held to that flow
        val (slow, fastMax) = paSpeeds?.let { t ->
            val p = t.split(',').map { it.trim().removeSuffix("mm/s").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--speeds is SLOW,FAST in mm/s, above 0 (got '$t')") }
            if (p.size != 2) throw UsageError("--speeds is SLOW,FAST in mm/s (got '$t')")
            p[0] to p[1]
        } ?: (Math.min(s.visibleSpeed, flowCap(w, hm)) to Math.min(s.hiddenSpeed, flowCap(w, hm)))
        if (fastMax <= slow) throw IllegalArgumentException("no speed step to read: the fast speed, ${n(fastMax)} mm/s (the machine's print speed held to the plate's flow share), is not above the slow one, ${n(slow)} mm/s: give both with --speeds SLOW,FAST")
        if (paSpeeds != null && fastMax > flowCap(w, hm) + 1e-9) throw IllegalArgumentException("--speeds: ${n(fastMax)} mm/s is over the plate's ${n(flowCap(w, hm))} mm/s at this line, $flowWords")
        val accel = s.defaultAccel
        // the extruder's push: the firmware's smoothing (the model's, else Klipper's documented default) and the extruder's
        // highest filament speed (the tool's, else its model's toolhead's, else not known), held to the plate's share
        val toolhead = s.machineDescription.toolheads.let { ths -> if (ths.size == 1) ths[0] else ths.firstOrNull { it.index == spool.tool } }
        val smoothS = command?.smoothS ?: KLIPPER_SMOOTH_S
        val smoothWords = if (command?.smoothS != null) "${n(smoothS)} s (hardware.model.$model.pressure_advance.smooth_time)" else "${n(smoothS)} s, Klipper's documented default (hardware.model.$model.pressure_advance.smooth_time unset)"
        val extrudeR = Resolver.resolveOrNull(setup, "hardware.tool.extrude.speed.max", plymeld.model.Context(0))
        val extrudeMax = (extrudeR?.value as Number?)?.toDouble() ?: toolhead?.extrudeSpeedMax
        // a toolhead's value said short: its setting and its evidence's kind; `plymeld config` shows the evidence in full
        fun toolheadSource(key: String, evidence: String?) = "hardware.model.$model.toolhead.${toolhead?.index}.$key, " + (evidence?.let { e -> if (": " in e) "${e.substringBefore(": ")}; `plymeld config` shows its evidence" else e } ?: "no evidence given")
        val extrudeFrom = extrudeR?.from ?: toolhead?.extrudeSpeedMax?.let { toolheadSource("extrude.speed.max", toolhead.extrudeSpeedEvidence) }
        val pushLimit = extrudeMax?.let { share * it }
        val ratio = plymeld.core.calibrate.MeltSpec.bead(w, h) * s.flowRatio / (Math.PI * s.filamentDiameterMm * s.filamentDiameterMm / 4)
        val pushWords = if (pushLimit == null) "no push limit: the extruder's highest filament speed is not known (hardware.tool.<key>.extrude.speed.max)"
            else "every move at a wall's value within ${n(pushLimit)} mm/s of filament, ${n(share * 100)} % of the extruder's ${n(extrudeMax)} mm/s ($extrudeFrom)"
        val limitsWords = "every line at most $flowWords; " + (if (pushLimit == null) pushWords else "push: $pushWords, smoothed over $smoothWords")
        // the values, each to the labels' resolution (a wall's value and its label agree)
        val g = plymeld.core.calibrate.PressureAdvanceGeometry
        val atLabel = plymeld.core.calibrate.PressureAdvanceSpec::atLabel
        // the tool's ceiling: its own, else its model's toolhead's, else plymeld's default
        val ceilingOwn = Resolver.resolve(setup, "hardware.tool.calibrate.pressure_advance.max", plymeld.model.Context(0))
        val fromToolhead = toolhead?.paCeilingS?.takeIf { ceilingOwn.entityName == "catalog" }
        val ceiling = fromToolhead ?: (ceilingOwn.value as Number).toDouble()
        val ceilingR = object { val from = if (fromToolhead != null) toolheadSource("calibrate.pressure_advance.max", toolhead.paCeilingEvidence)
            else "hardware.tool.<key>.calibrate.pressure_advance.max, " + if (ceilingOwn.entityName == "catalog") "plymeld's default" else ceilingOwn.from }
        val evenly = plymeld.core.calibrate.PressureAdvanceSpec::evenly
        // the first plate: 0, then from the tool's lowest value to its top, each a fixed ratio below the next (the ratio set
        // by the two and the plate's count): one plate brackets a short filament path and a long tube
        val floorR = Resolver.resolve(setup, "hardware.tool.calibrate.pressure_advance.min", plymeld.model.Context(0))
        val floor = (floorR.value as Number).toDouble()
        if (!(floor >= plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S && floor < ceiling)) throw IllegalArgumentException("the find plate's values run from hardware.tool.<key>.calibrate.pressure_advance.min (${n(floor)} s, ${floorR.from}) " +
            "up to .max (${n(ceiling)} s, ${ceilingR.from}): the lowest must be at least the labels' ${plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S * 1000} ms and below the highest")
        // the highest value the extruder's push allows: a corner from rest to the fast speed, the largest change a find plate's
        // wall makes, held within the push limit (to the labels' resolution, rounded down); null = no push limit
        val pushTop = pushLimit?.let { lim ->
            val k = plymeld.core.calibrate.PressureAdvancePush.highestK(lim, fastMax, accel, smoothS, ratio)
                ?: throw IllegalArgumentException("the walls' own filament speed at ${n(fastMax)} mm/s, ${n(ratio * fastMax)} mm/s, is already over the push limit ($pushWords): a lower fast speed (--speeds SLOW,FAST)")
            atLabel(Math.floor(k / plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S + 1e-9) * plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S)
        }
        fun geometric(lo: Double, hi: Double): List<Double> {
            val count = g.values - 1
            return listOf(0.0) + (0 until count).map { k -> atLabel(lo * Math.pow(hi / lo, k.toDouble() / (count - 1))) }
        }
        fun distinct(vs: List<Double>, what: String): List<Double> = vs.also {
            if (it.zipWithNext().any { (a, b) -> b <= a }) throw IllegalArgumentException("$what: ${vs.joinToString(", ") { v -> n4(v) }} s at the labels' ${plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S * 1000} ms: two walls would carry one value; a wider range") }
        fun findWalls(vs: List<Double>, fast: Double = fastMax) = vs.indices.map { plymeld.core.calibrate.PressureAdvanceWall(0, it, vs[it], fast) } + vs.indices.map { plymeld.core.calibrate.PressureAdvanceWall(1, it, vs[vs.size - 1 - it], fast) }
        // one step either side, or two: down to the lower neighbor, up to the higher (a bracket's ends on the labels' grid)
        val confirmStep = paConfirmStep?.let { t -> t.split(',').map { seconds(it.trim(), "--confirm-step").takeIf { v -> v > 0 } ?: throw UsageError("--confirm-step is above 0") }
            .also { if (it.size !in 1..2) throw UsageError("--confirm-step is S, or DOWN,UP (got '$t')") }.let { it.first() to it.last() } }
        val autoTop = kind == plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND && paValues == null && paBracket == null
        val given: List<plymeld.core.calibrate.PressureAdvanceWall>? = when (kind) {
            plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND -> (paValues?.let { t -> t.split(',').map { atLabel(seconds(it, "--values")) }.also { v ->
                        if (v.size < 2 || v.zipWithNext().any { (a, b) -> b < a }) throw UsageError("--values is at least two values, rising (got '$t')") }.let { distinct(it, "--values") } }
                    ?: paBracket?.let { t -> range(t.replace(" s", "").replace("s", ""), "--bracket").let { (a, b) -> if (b <= a || a < 0) throw UsageError("--bracket runs low to high, from 0 or more (got '$t')"); distinct(evenly(a, b), "--bracket $t") } })
                ?.let { findWalls(it) }
            plymeld.core.calibrate.PressureAdvanceSpec.Kind.CONFIRM -> {
                // three flow steps: a third, two thirds, and all of the speed step (plymeld's choice)
                val fasts = (1..3).map { slow + (fastMax - slow) * it / 3 }
                if (machineCheck) fasts.indices.map { plymeld.core.calibrate.PressureAdvanceWall(0, it, null, fasts[it], it) }
                else {
                    val c = seconds(confirmText!!, "--confirm")
                    val (down, up) = confirmStep ?: throw UsageError("--confirm-step: the step to the neighbors (the step of the plate that gave the value, as `pressure-advance values` prints it), e.g. --confirm-step 0.004")
                    val vs = distinct(listOf(c - down, c, c + up).map { atLabel(it) }, "--confirm ${n4(c)} --confirm-step ${n4(down)},${n4(up)}")
                    if (vs[0] < 0) throw IllegalArgumentException("--confirm ${n4(c)} less --confirm-step ${n4(down)} is below 0: a smaller step")
                    vs.flatMapIndexed { vi, v -> fasts.indices.map { k -> plymeld.core.calibrate.PressureAdvanceWall(0, vi * 3 + k, v, fasts[k], k) } }
                }
            }
        }
        val firstSpeed = Math.min(s.firstLayerSpeed, flowCap(s.firstLayerWidthUm, s.firstLayerHeightUm / 1000.0))
        // the base above its first layer at a hidden line's speed, the labels and arrows at a visible wall's (read by eye, as
        // an outer wall is seen), each held to the plate's share of the flow
        val frameSpeed = Math.min(s.hiddenSpeed, flowCap(w, hm))
        val labelSpeed = Math.min(s.visibleSpeed, flowCap(w, hm))
        val level = s.levelGcode?.takeIf { !noLevel }
        val configuredPa = setup.pressureAdvanceByTool()[spool.tool]
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val park = g.parkFront / 1000.0
        val bead = plymeld.core.calibrate.MeltSpec.bead(w, h)
        // the lead-in and the middle: long enough for the pressure to settle within them at the plate's highest value (the
        // ceiling where the plate sets none: the machine's own), by the pressure model at the fastest wall's speed, so the
        // slow-down mark reads opposite to the speed-up mark on every wall
        fun highestOf(ws: List<plymeld.core.calibrate.PressureAdvanceWall>) = ws.mapNotNull { it.value }.maxOrNull() ?: ceiling
        fun stretchesOf(ws: List<plymeld.core.calibrate.PressureAdvanceWall>) = highestOf(ws).let { top -> if (top <= 0) Triple(g.leadMin / 1000.0, g.fastMin / 1000.0, g.tail / 1000.0)
            else plymeld.check.PressureModel.stretchesFor(slow, ws.maxOf { it.fastSpeed }, accel, bead, g.readLength / 1000.0, top, g.leadMin / 1000.0, g.fastMin / 1000.0, g.tail / 1000.0, limitMm = Math.max(bedD, bedW)) }
        fun specOf(ws: List<plymeld.core.calibrate.PressureAdvanceWall>, st: Triple<Double, Double, Double>, ox: Long = 0, oy: Long = 0) = try {
            plymeld.core.calibrate.PressureAdvanceSpec(spool.tool, spool.id, kind, ws, h, s.firstLayerHeightUm, w, s.firstLayerWidthUm, s.filamentDiameterMm, s.flowRatio,
                printC, s.nozzleTempFirstC, s.fanMinPct, adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm),
                frame = PathValues(frameSpeed, s.defaultAccel, s.flowRatio, s.retractMm),
                slowSpeed = slow, accel = accel, framePa = if (own) null else configuredPa ?: ws.mapNotNull { it.value }.min(), liftUm = s.zHopUm, originXUm = ox, originYUm = oy, levelGcode = level,
                leadUm = Math.round(st.first * 1000), fastUm = Math.round(st.second * 1000),
                labels = PathValues(labelSpeed, s.defaultAccel, s.flowRatio, s.retractMm), smoothS = smoothS, pushLimit = pushLimit)
        } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "the pressure-advance plate's values") }
        fun fits(sp: plymeld.core.calibrate.PressureAdvanceSpec) = sp.baseW / 1000.0 <= bedW && sp.baseD / 1000.0 + 2 * park <= bedD
        fun sizeWords(sp: plymeld.core.calibrate.PressureAdvanceSpec) = "walls ${n(sp.wallLength / 1000.0)} mm long, a plate ${n(sp.baseW / 1000.0)} x ${n(sp.baseD / 1000.0)} mm"
        // the first plate's top: the lowest of the tool's ceiling, the highest value the extruder's push allows, and the highest
        // value whose walls fit the bed
        var topWhy = ""
        val ceilingWhy = "the tool's ceiling (${ceilingR.from})"
        val cap = if (pushTop != null && pushTop < ceiling) pushTop else ceiling
        val capWhy = if (cap < ceiling) "the highest value the extruder's push allows; the tool's ceiling is ${n4(ceiling)} s" else ceilingWhy
        val walls: List<plymeld.core.calibrate.PressureAdvanceWall> = given ?: run {
            fun tried(top: Double, fast: Double = fastMax): plymeld.core.calibrate.PressureAdvanceSpec? {
                val vs = geometric(floor, top).takeIf { v -> v.zipWithNext().all { (a, b) -> b > a } } ?: return null
                val ws = findWalls(vs, fast)
                return stretchesOf(ws)?.let { specOf(ws, it) }?.takeIf { fits(it) }
            }
            // the lowest top that still spaces the values apart at the labels' resolution
            var lo = floor * Math.pow(1 + 1.5 * plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S / floor, (g.values - 2).toDouble())
            if (lo >= cap) throw IllegalArgumentException("the find plate's values from ${n4(floor)} s (hardware.tool.<key>.calibrate.pressure_advance.min) to ${n4(cap)} s are too close to space ${g.values - 1} " +
                "values a tenth of a millisecond apart: a wider range")
            val atCap = findWalls(geometric(floor, cap)).let { ws -> stretchesOf(ws)?.let { specOf(ws, it) } }
            if (atCap != null && fits(atCap)) { topWhy = capWhy; return@run atCap.walls }
            val need = atCap?.let { sizeWords(it) } ?: "walls longer than the bed at ${n(fastMax)} mm/s"
            if (tried(lo) == null) {
                // the highest whole fast speed below this one, above the slow one, at which the lowest values' walls fit, by the
                // same pressure model and layout, tried one mm/s at a time from the top (how long the walls must be is not
                // monotonic in the fast speed: a step barely above the slow speed settles slowly too); none where even the
                // shortest walls do not fit
                fun fitsAt(f: Double) = f > slow && try { tried(lo, f) != null } catch (e: IllegalArgumentException) { false }
                // the plate at its shortest walls, with the labels it would carry (its size does not depend on the values
                // otherwise): the size the plate has wherever its walls are their shortest
                fun shortestOf(ws: List<plymeld.core.calibrate.PressureAdvanceWall>) = try { specOf(ws, Triple(g.leadMin / 1000.0, g.fastMin / 1000.0, g.tail / 1000.0)) } catch (e: IllegalArgumentException) { null }
                val shortest = shortestOf(findWalls(geometric(floor, cap))) ?: shortestOf(findWalls(geometric(floor, cap), Math.floor(slow) + 1))
                val shortestFits = shortest != null && fits(shortest)
                val bedWords = "the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build)"
                // a bed too small for the plate at its shortest walls: its size against the bed's, whatever the values
                if (shortest != null && !shortestFits) throw IllegalArgumentException("the pressure-advance find plate does not fit: with its shortest walls it is ${n(shortest.baseW / 1000.0)} x ${n(shortest.baseD / 1000.0)} mm, " +
                    "${n(shortest.baseD / 1000.0 + 2 * park)} mm of Y with the head's parking room; $bedWords")
                val slower = if (!shortestFits) null else generateSequence(Math.ceil(fastMax) - 1) { it - 1 }.takeWhile { it > slow }.firstOrNull { fitsAt(it) }
                val lowestSize = try { stretchesOf(findWalls(geometric(floor, lo)))?.let { sizeWords(specOf(findWalls(geometric(floor, lo)), it)) } } catch (e: IllegalArgumentException) { null } ?: "walls longer than the bed"
                throw IllegalArgumentException("the pressure-advance find plate does not fit from the tool's lowest value, ${n4(floor)} s: at ${n(fastMax)} mm/s it needs $lowestSize; $bedWords. " +
                    when {
                        slower != null -> "At ${n(slower)} mm/s, the highest whole fast speed below that fits (plymeld's pressure model): --speeds ${n(slow)},${n(slower)}"
                        else -> "No fast speed from ${n(Math.floor(slow) + 1)} to ${n(Math.ceil(fastMax) - 1)} mm/s fits: a longer bed axis, or a lower lowest value (.min)"
                    })
            }
            var hi = cap
            repeat(24) { val m = Math.sqrt(lo * hi); if (tried(m) != null) lo = m else hi = m }
            topWhy = "the highest value whose walls fit the ${n(bedD)} mm bed; ${n4(cap)} s (${if (cap < ceiling) "the highest the extruder's push allows" else "the tool's ceiling"}) would need $need"
            tried(lo)!!.walls
        }
        val highest = highestOf(walls)
        val highestWhy = when {
            walls.all { it.value == null } -> "the tool's ceiling, ${n(ceiling)} s"
            autoTop -> "the plate's top: $topWhy"
            else -> "the plate's highest value"
        }
        val stretches = stretchesOf(walls)
            ?: throw IllegalArgumentException("the pressure-advance plate's walls cannot be long enough on this bed for the pressure to settle within them at ${n(highest)} s ($highestWhy): " +
                "a true value that high takes longer to settle than a wall ${n(Math.max(bedD, bedW))} mm long allows at ${n(walls.maxOf { it.fastSpeed })} mm/s; give --values with a lower top, or a --bracket, or a lower ceiling (hardware.tool.<key>.calibrate.pressure_advance.max)")
        val draft = specOf(walls, stretches)
        // every wall's moves within the extruder's push limit (a first plate's top is already held to it)
        if (pushLimit != null) draft.walls.filter { draft.peakOf(it) > pushLimit + 1e-9 }.takeIf { it.isNotEmpty() }?.let { over ->
            throw IllegalArgumentException("the walls from ${n4(over.mapNotNull { it.value }.min())} s up would push the extruder to ${n(over.maxOf { draft.peakOf(it) })} mm/s of filament, over the plate's ${n(pushLimit)} mm/s " +
                "(${n(share * 100)} % of hardware.tool.<key>.extrude.speed.max)" +
                (if (kind == plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND) "; values up to ${pushTop?.let { n4(it) } ?: "0"} s keep within it" else ": a lower value or smaller steps"))
        }
        val plateW = draft.baseW / 1000.0; val plateD = draft.baseD / 1000.0
        if (!fits(draft)) throw IllegalArgumentException("the pressure-advance plate does not fit: it is ${n(plateW)} x ${n(plateD)} mm, ${n(plateD + 2 * park)} mm of Y with the head's parking room; the bed is ${n(bedW)} x ${n(bedD)} mm. " +
            (if (draft.leadUm > g.leadMin) "Its ${n(draft.wallLength / 1000.0)} mm walls settle at ${n(highest)} s: a lower top (--values), a --bracket, or a longer bed axis" else "Fewer values (--values)"))
        val fitWarnings = listOfNotNull(if (bedW - plateW < 2 * PlateGeometry.bedSpare / 1000.0) "the plate is ${n(plateW)} mm wide on a ${n(bedW)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at a side; check the bed's clips or wiper are clear of it" else null)
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val spec = specOf(walls, stretches, cx - draft.baseW / 2, cy - draft.baseD / 2)
        val plate = plymeld.core.calibrate.PressureAdvancePlate(spec)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, listOf(printC), "the plate's temperature")
        // where the pressure settles within the stretches at these speeds, by the pressure model: the slow-down mark's reading
        // holds up to it, at least the plate's highest value (to the labels' resolution, rounded down)
        val settles = java.math.BigDecimal(plymeld.check.PressureModel.settlesUpTo(slow, walls.maxOf { it.fastSpeed }, accel, spec.leadUm / 1000.0, spec.fastUm / 1000.0, g.tail / 1000.0, bead, g.readLength / 1000.0,
            Math.max(ceiling, highest))).setScale(4, java.math.RoundingMode.DOWN).toDouble()
        fun gcodeOf(p: plymeld.core.calibrate.PressureAdvancePlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value)
        val text = withPressureAdvanceConditions(gcodeOf(plate), spec, spool.material, slotN, s, settles, configuredPa, madeWith(slotN, spool.material), output.toString(), if (autoTop) "${n4(highest)} s, $topWhy" else null, limitsWords)
        // the g-code check reads pressure advance back: the plate sets its own before each wall, on purpose (none on a machine that sets its own)
        val expect = plymeld.core.check.Expectations.forSetup(setup, pressureAdvanceSetByPlate = !own)
        val pattern = (command ?: plymeld.machine.PressureAdvanceCommand("SET_PRESSURE_ADVANCE ADVANCE={pressure_advance}", "s", emptyList())).let { c ->
            plymeld.core.check.GcodeCheck.PressureAdvanceRule.of(c.template, c.unit, emptyMap())?.pattern to c.unit }
        val paPattern = pattern.first ?: throw IllegalArgumentException("the model's pressure-advance command (hardware.model.$model.pressure_advance.command) is not one plymeld can read back: its value must be the bare {pressure_advance}, on one line")
        fun allProblems(t: String) = plymeld.core.calibrate.PressureAdvanceCheck.check(spec, t, paPattern, pattern.second) + plymeld.core.check.GcodeCheck.check(t, expect)
        val problems = allProblems(text)
        fun without(vararg c: String) = gcodeOf(plymeld.core.calibrate.PressureAdvancePlate(spec, without = c.toSet()))
        val find = kind == plymeld.core.calibrate.PressureAdvanceSpec.Kind.FIND
        val wall1 = spec.walls.first()
        val controls = listOfNotNull(
            if (!machineCheck && spec.walls.size > 3) "wall 1.3 at wall 1.4's value" to without("value") else null,
            if (find) "no command before wall 2.2" to without("command") else null,
            if (find) "the back row in the front row's order" to without("order") else null,
            "wall 1.2 printed back to front on odd layers" to without("direction"),
            "wall 1.1's middle at the slow speed" to without("speed"),
            "the speed-change marks left out" to without("holes"),
            "the chamfer left out" to without("chamfer"),
            "the base whole, no strips" to without("gaps"),
            "the break-off tabs left out" to without("tabs"),
            if (!find) "wall 1.2's flow-step notches one short" to without("steps") else null,
            if (!find) "a flow-step notch more under wall 1.1" to without("notch") else null,
            if (find) "wall 1.2's corner left out" to without("corner") else null,
            "a retraction inside wall 1.1" to retractInside(text, wall1.label),
            "a wall's lead-in at 1.5x its speed" to alterTileSpeed(text, wall1.label),
            "the part fan ${if (spec.fanPct > 0) "off from the second layer" else "on at the middle layer"}" to (if (spec.fanPct > 0) alterFan(text, toOff = true) else fanOnMidway(text)),
            removeEndLift(text)?.let { "the end's lift removed" to it },
        )
        // the push limit's control: the plate read against a limit 1 % under its own highest push must be refused
        val pushControl = pushLimit?.let { _ -> "the walls read against a push limit 1 % under their highest push" to
            plymeld.core.calibrate.PressureAdvanceCheck.check(spec.withPushLimit(spec.walls.maxOf { w -> spec.peakOf(w) } * 0.99), text, paPattern, pattern.second) }
        val controlMisses = controls.filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first } + listOfNotNull(pushControl?.takeIf { it.second.isEmpty() }?.first)
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val prediction = plymeld.core.Predict.gcode(text, s.machineDescription, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        val values = spec.walls.mapNotNull { it.value }.distinct().sorted()
        // where the file does not level, whether the printer levels as a print starts when asked (as `printer send --level` would)
        val levelsOnStart = level == null && !noLevel && try { plymeld.printer.Printers.open(config, machineName).can(plymeld.printer.Capability.LEVEL_ON_START) } catch (e: Exception) { false }
        // one thing a line; each limit's setting cited once
        echo("pressure-advance plate (${kind.key}) for filament '${spool.material}' on tool $slotN of '$model', build plate '$plateName' (bed ${s.bedTempFirstC} C)")
        echo("plate: ${n(plateW)} x ${n(plateD)} mm, ${spec.layers} layers (the base ${g.baseLayers}, the walls ${spec.wallLayers} of ${n(hm)} mm); the walls at $printC C (temperature.print)" +
            (if (spec.firstTempC != printC) ", the base's first layer at ${spec.firstTempC} C" else ""))
        echo("walls: ${n(w / 1000.0)} mm lines; a ${n(spec.leadUm / 1000.0)} mm lead-in at ${n(slow)} mm/s, a ${n(spec.fastUm / 1000.0)} mm middle at ${spec.walls.map { it.fastSpeed }.distinct().joinToString(", ") { n(it) }} mm/s, " +
            "a ${n(g.tail / 1000.0)} mm tail at ${n(slow)} mm/s; ${n(accel)} mm/s2 (the machine's print acceleration)")
        echo("flow step: ${spec.walls.map { spec.flowStep(it) }.distinct().joinToString(", ") { n(it) }} mm3/s")
        echo("flow: every line at most $flowWords")
        echo("speeds: the first layer ${n(firstSpeed)} mm/s, the base above it ${n(frameSpeed)}, the labels ${n(labelSpeed)} (a visible wall's), the walls ${n(slow)} and ${n(fastMax)}")
        echo(if (pushLimit == null) pushWords else "push: $pushWords")
        if (pushLimit != null) echo("push smoothing: $smoothWords; the highest push on the plate ${n(spec.walls.maxOf { w -> spec.peakOf(w) })} mm/s")
        echo(when {
            machineCheck -> "values: the machine's own, no command written; three flow steps (one, two, three notches in the front edge)"
            find -> "values: ${values.joinToString(", ") { spec.valueText(it) }} s, the front row rising from the left, the back row falling; a corner beside each wall"
            else -> "values: ${values.joinToString(", ") { spec.valueText(it) }} s (the value and its neighbors), each at three flow steps (one, two, three notches in the front edge)"
        })
        if (autoTop) {
            echo("spacing: 0, then from the tool's lowest, ${spec.valueText(floor)} s (hardware.tool.<key>.calibrate.pressure_advance.min), each ${n(Math.pow(highest / floor, 1.0 / (g.values - 2)))} times the one before")
            echo("top: ${n4(highest)} s, $topWhy")
        }
        echo("stretches: the pressure settles within the lead-in and the middle up to a true value of ${spec.valueText(settles)} s (plymeld's pressure model), at least the plate's highest value")
        when {
            level != null -> echo("levelling: the file runs the machine's bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level leaves it out")
            noLevel -> echo("levelling: left out of the file (--no-level)")
            levelsOnStart -> echo("levelling: optional, as the print starts: `plymeld printer send --print --level`")
        }
        warnings.forEach { echo("warning: $it") }
        echo("check: every wall at its value, one straight unbroken line, slow, fast, slow; the base's strips, gaps, tabs, and marks" + (if (pushLimit != null) "; the push" else "") + "; temperatures and fan; the g-code as configured")
        // the controls, as many to a line as fit
        val caught = controls.map { it.first } + listOfNotNull(pushControl?.first)
        val controlLines = ArrayList<String>(); var cur = "controls caught (${caught.size}): "
        for ((k, c) in caught.withIndex()) { val add = c + if (k < caught.size - 1) ", " else ""; if (cur.length + add.length > 200) { controlLines += cur.trimEnd(); cur = "  " }; cur += add }
        controlLines += cur
        controlLines.forEach { echo(it) }
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        plymeld.core.calibrate.PressureAdvanceInstructions.write(page, spec, plate, plymeld.core.calibrate.PressureAdvanceInstructions.Info(
            spool.material, model, spool.tool, PlymeldVersion.value, output.fileName.toString(), prediction.timeS?.let { it / 60 }, s.bedTempFirstC,
            plateName, level != null, noLevel && s.levelGcode != null, warnings, settles, configuredPa, own, s.retractMm,
            top = if (autoTop) "${n4(highest)} s: $topWhy" else null, limits = limitsWords,
            levelsOnStart = levelsOnStart))
        echo("-> $output")
        echo("-> $page (and its pictures): the question, the conditions and limits, how to read each wall, what to enter")
        // the prediction, and the motion limits it was made with on a line of their own
        "$prediction".let { pr -> val at = pr.indexOf(" (motion limits: ")
            if (at < 0) echo("predicted: $pr") else { echo("predicted: ${pr.substring(0, at)}"); echo("predicted with the motion limits ${pr.substring(at + " (motion limits: ".length).removeSuffix(")")}") } }
        echo("after printing: `plymeld calibrate pressure-advance readings ${output.fileName} -o readings.toml`, read each wall, then `plymeld calibrate pressure-advance values readings.toml`")
    }

    /** The pressure-advance plate's conditions in its own g-code, as `; name = value` comments after its first lines. */
    private fun withPressureAdvanceConditions(text: String, spec: plymeld.core.calibrate.PressureAdvanceSpec, filament: String, tool: Int, s: SliceSettings, settles: Double, configuredPa: Double?, made: String, out: String, top: String?, limits: String): String {
        val g = plymeld.core.calibrate.PressureAdvanceGeometry
        val values = spec.walls.mapNotNull { it.value }.distinct().sorted()
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = pressure-advance ${spec.kind.key} (step walls: each wall's pressure advance read from the sign of its defect just after the speed-up and the slow-down marks)")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; temperature = " + (if (spec.firstTempC != spec.tempC) "${spec.firstTempC} C on the base's first layer (the filament's temperature.first_layer), ${spec.tempC} C for the walls (its temperature.print), waited for before them"
                else "${spec.tempC} C (the filament's temperature.print) throughout") + "; part fan ${n(spec.fanPct)} % from the second layer (off on the first)")
            add("; walls = ${spec.perRow} a row, ${spec.rows} row(s), ${n(spec.widthUm / 1000.0)} mm lines ${n(spec.layerUm / 1000.0)} mm high, ${spec.wallLayers} layers above a ${g.baseLayers}-layer base; lead-in ${n(spec.leadUm / 1000.0)} mm at ${n(spec.slowSpeed)} mm/s, middle ${n(spec.fastUm / 1000.0)} mm, tail ${n(g.tail / 1000.0)} mm at ${n(spec.slowSpeed)} mm/s; acceleration ${n(spec.accel)} mm/s2; flow_ratio ${n(spec.flowRatio)}; filament ${n(spec.filamentDiameterMm)} mm")
            add("; speeds = slow ${n(spec.slowSpeed)} mm/s, fast ${spec.walls.map { it.fastSpeed }.distinct().joinToString(", ") { n(it) }} mm/s; flow step ${spec.walls.map { spec.flowStep(it) }.distinct().joinToString(", ") { n(it) }} mm3/s")
            add("; values = " + (if (values.isEmpty()) "the machine's own (it sets its own: none written)" else values.joinToString(", ") { spec.valueText(it) } + " s"))
            add("; resolution = ${plymeld.core.calibrate.PressureAdvanceSpec.LABEL_STEP_S * 1000} ms (every value to its label's tenth of a millisecond)")
            top?.let { add("; top = $it") }
            add("; made_with = $made")
            add("; output = $out")
            add("; settles_up_to = ${spec.valueText(settles)} s (by plymeld's pressure model: the largest true value whose pressure settles within the middle, where the slow-down mark reads opposite to the speed-up mark)")
            add("; frame_pressure_advance = ${spec.framePa?.let { "${spec.valueText(it)} s" + if (configuredPa != null) " (the config's)" else " (the plate's lowest)" } ?: "none written"} (the base and the labels)")
            add("; smoothing = the firmware's own (plymeld sets none), ${n(spec.smoothS)} s read for the push; bed = ${s.bedTempFirstC} C; retraction = ${n(spec.frame.retractMm)} mm; lift = ${n(spec.liftUm / 1000.0)} mm")
            add("; limits = $limits")
            add("; first_layer = ${n(spec.firstWidthUm / 1000.0)} mm lines (first_layer_line_width), ${n(spec.firstLayerUm / 1000.0)} mm high, ${n(spec.adhesion.speed)} mm/s; base = ${n(spec.frame.speed)} mm/s above it; labels = ${n(spec.labels.speed)} mm/s")
            for (w in spec.walls) add("; ${w.label} = ${w.value?.let { "${spec.valueText(it)} s" } ?: "the machine's own"}, fast ${n(w.fastSpeed)} mm/s, flow step ${w.step + 1}")
            add("; CALIBRATION_PLATE_END")
        }
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /** A control: a retraction and its restore put between two of [label]'s first cell's printing moves (the wall broken). */
    private fun retractInside(text: String, label: String): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it == "; CELL: $label" }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        val prints = (at until end).filter { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(lines[it]) }
        lines.addAll(prints[1], listOf("G1 E-0.8 F2400", "G1 E0.8 F2400"))
        return lines.joinToString("\n")
    }

    /**
     * The motion plate: one cross-shaped tower, twelve faces at six speeds (one along X and one along Y
     * each), a band per acceleration; the fastest speed the machine's print speed held to the filament's maximum flow and the top
     * band the machine's print acceleration (the control). At the filament's own print temperature (a machine test does not vary it).
     */
    private fun runMotion() {
        val toolN = toolOpt.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("--tool is a tool's index, a whole number from 1 (got '$toolOpt')")
        val plain = ConfigSetup.setup(plateConfig(toolN, null).first).setup
        val plainMt = plain.spool(plain.objects[0].spool).let { sp -> plain.materialTool[sp.material to sp.tool] }
        val printC: Int = printTemp?.let { whole(it.trim().removeSuffix("C").trim().toDoubleOrNull() ?: throw UsageError("--print-temp is whole degrees C (got '$it')"), "--print-temp") }
            ?: (plainMt?.get("hardware.filament.temperature.print")?.value as Number?)?.toDouble()?.let { whole(it, "the filament's temperature.print") }
            ?: throw IllegalArgumentException("the motion plate prints at the filament's own temperature (temperature.print), which '${plain.spool(plain.objects[0].spool).material}' has none of: give --print-temp")
        // the slab's first layer at the filament's first-layer temperature, worked out from the plate's print temperature
        val (config, slotN) = plateConfig(toolN, printC, firstLayerToo = false)
        val result = try { ConfigSetup.setup(config) } catch (e: ConfigError) { throw IllegalArgumentException("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" }) }
        val setup = result.setup
        val s = SliceSettings.of(setup, plymeld.model.Context(0))
        val spool = setup.spool(setup.objects[0].spool)
        val h = s.layerHeightUm; val w = s.outerWallWidthUm; val wi = s.innerWallWidthUm
        val hm = h / 1000.0; val fhm = s.firstLayerHeightUm / 1000.0
        fun meltCap(lw: Long, lh: Double) = s.maxVolumetric / (lh * (lw / 1000.0 - lh * (1 - Math.PI / 4)) * s.flowRatio)
        val outerCap = meltCap(w, hm); val innerCap = meltCap(wi, hm)
        // the filament's maximum flow as the config has it: its key, and whether it is still plymeld's bundled placeholder
        val maxFlow = Resolver.resolveOrNull(setup, "hardware.filament.melt.flow.max", plymeld.model.Context(0))
        val meltSlot = ConfigSetup.slots(config, config.machine!!, ArrayList()).single()
        val maxFlowKey = ConfigSetup.meltEntry(config, plymeld.config.Site(machine = config.machine, tool = meltSlot.tool, filament = meltSlot.filament))?.key
            ?: "hardware.filament.${spool.material}.melt.flow.max"
        val maxFlowPlaceholder = maxFlow != null && maxFlow.from.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)
        // the speeds: given (six, rising), or a sixth of the top to the top, the top the machine's print speed held to the filament's
        // maximum flow for the outer wall (past it the wall would starve, which reads like ringing), whole mm/s; the others rounded
        // down to 5 mm/s (plymeld's choice: the spread of the motion hexagon this plate replaced)
        val topSpeed = Math.floor(Math.min(s.hiddenSpeed, outerCap))
        val flowBinds = outerCap < s.hiddenSpeed
        val speeds = motionSpeeds?.let { m -> m.split(',').map { it.trim().removeSuffix("mm/s").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--motion-speeds is six speeds in mm/s, slow to fast (got '$m')") } }
            ?.also { sp ->
                if (sp.size != plymeld.core.calibrate.MotionGeometry.speeds) throw UsageError("--motion-speeds is ${plymeld.core.calibrate.MotionGeometry.speeds} speeds, one per face along X and one along Y (got ${sp.size})")
                if (sp.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--motion-speeds must rise, slow to fast (got '$motionSpeeds')")
                if (sp.max() > s.hiddenSpeed + 1e-9) throw UsageError("--motion-speeds: ${n(sp.max())} mm/s is past the machine's print speed (hardware.machine.<key>.motion.print.speed, ${n(s.hiddenSpeed)} mm/s)")
                if (sp.max() > outerCap + 1e-9) throw UsageError("--motion-speeds: ${n(sp.max())} mm/s is past what the filament's maximum flow ($maxFlowKey) less the melt buffer (${n(s.maxVolumetric)} mm3/s) allows on a ${n(w / 1000.0)} mm outer wall (${n(outerCap)} mm/s): the wall would starve, which reads like ringing")
            } ?: (1..6).map { k -> if (k == 6) topSpeed else maxOf(5.0, Math.floor(topSpeed * k / 6 / 5) * 5) }
        if (speeds.zipWithNext().any { (a, b) -> b <= a }) throw IllegalArgumentException("the fastest speed is ${n(topSpeed)} mm/s (${if (flowBinds) "the filament's maximum flow" else "the machine's print speed"}): too slow to spread six speeds 5 mm/s apart; give --motion-speeds")
        // the accelerations: given (rising), or MOTION_BANDS of them, the machine's print acceleration and each band below half the
        // one above, to 10 mm/s2 (plymeld's choice: ringing grows with acceleration, so even ratios rather than even steps)
        val accels = motionAccels?.let { m -> m.split(',').map { it.trim().removeSuffix("mm/s2").trim().toDoubleOrNull()?.takeIf { v -> v > 0 } ?: throw UsageError("--motion-accels is accelerations in mm/s2, one per band, low to high (got '$m')") } }
            ?.also { ac ->
                if (ac.zipWithNext().any { (a, b) -> b <= a }) throw UsageError("--motion-accels must rise, bottom band to top (got '$motionAccels')")
                if (ac.max() > s.defaultAccel + 1e-9) throw UsageError("--motion-accels: ${n(ac.max())} mm/s2 is past the machine's print acceleration (hardware.machine.<key>.motion.print.acceleration, ${n(s.defaultAccel)} mm/s2)")
            } ?: (0 until MOTION_BANDS).map { k -> if (k == MOTION_BANDS - 1) s.defaultAccel else Math.round(s.defaultAccel / Math.pow(2.0, (MOTION_BANDS - 1 - k).toDouble()) / 10) * 10.0 }
        val firstSpeed = Math.min(s.firstLayerSpeed, meltCap(s.firstLayerWidthUm, fhm))
        val level = s.levelGcode?.takeIf { !noLevel }
        fun spec(ox: Long, oy: Long) = try {
            plymeld.core.calibrate.MotionSpec(spool.tool, spool.id, speeds.toDoubleArray(), accels.toDoubleArray(), h, s.firstLayerHeightUm, w, wi, s.firstLayerWidthUm,
                s.filamentDiameterMm, s.flowRatio, printC, s.nozzleTempFirstC, s.fanMinPct,
                adhesion = PathValues(firstSpeed, s.defaultAccel, s.flowRatio, s.retractMm), frame = PathValues(Math.min(s.hiddenSpeed, outerCap), s.defaultAccel, s.flowRatio, s.retractMm),
                innerCap = innerCap, retractMm = s.retractMm, liftUm = s.zHopUm, minLayerTimeS = s.slowDownLayerTimeS, travelSpeed = s.travelSpeed,
                originXUm = ox, originYUm = oy, levelGcode = level)
        } catch (e: IllegalArgumentException) { throw IllegalArgumentException(e.message ?: "the motion plate's values") }
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        val bedW = bed("right") - bed("left"); val bedD = bed("back") - bed("front")
        val draft = spec(0, 0)
        val plateSize = draft.slabSize / 1000.0
        val park = PlateGeometry.parkFront / 1000.0
        if (draft.topZ / 1000.0 > s.maxPrintHeightMm) throw IllegalArgumentException("the motion plate is ${n(draft.topZ / 1000.0)} mm tall; the machine prints ${n(s.maxPrintHeightMm)} mm (hardware.machine.<key>.build): fewer accelerations (--motion-accels)")
        if (plateSize > bedW || plateSize + 2 * park > bedD) throw IllegalArgumentException("the motion plate does not fit this machine: its slab is ${n(plateSize)} x ${n(plateSize)} mm (and ${n(plateSize + 2 * park)} deep with the head's parking place in front); the bed is ${n(bedW)} x ${n(bedD)} mm (hardware.machine.<key>.build)")
        val fitWarnings = listOfNotNull(if (bedW - plateSize < 2 * PlateGeometry.bedSpare / 1000.0 || bedD - plateSize - 2 * park < 2 * PlateGeometry.bedSpare / 1000.0) "the plate is ${n(plateSize)} x ${n(plateSize)} mm on a ${n(bedW)} x ${n(bedD)} mm bed: less than ${n(PlateGeometry.bedSpare / 1000.0)} mm to spare at an edge; check the bed's clips or wiper are clear of it" else null)
        val cx = Math.round((bed("left") + bed("right")) * 500); val cy = Math.round((bed("front") + bed("back")) * 500)
        val spec = spec(cx - draft.slabSize / 2, cy - draft.slabSize / 2)
        val plate = plymeld.core.calibrate.MotionPlate(spec)
        val warnings = result.warnings + fitWarnings + plymeld.core.check.TemperatureLimits.warnings(setup, spool.id, listOf(printC), "the plate's temperature")
        val pa = setup.pressureAdvanceByTool()
        fun gcodeOf(p: plymeld.core.calibrate.MotionPlate) = p.gcode(s, setup.foreignTemplateVars, PlymeldVersion.value, pa)
        val machineDesc = Resolver.resolve(setup, "hardware.machine.model").value.toString()
        val topSpeedWhy = if (motionSpeeds != null) "given (--motion-speeds)" else if (flowBinds)
            "the machine's print speed (hardware.machine.<key>.motion.print.speed, ${n(s.hiddenSpeed)} mm/s) held to the filament's maximum flow ($maxFlowKey${if (maxFlowPlaceholder) ", plymeld's bundled placeholder" else ""}) less the melt buffer (${n(s.maxVolumetric)} mm3/s) on the ${n(w / 1000.0)} mm outer wall: the flow binds"
            else "the machine's print speed (hardware.machine.<key>.motion.print.speed): it binds (the filament's maximum flow allows ${n(outerCap)} mm/s on the outer wall)"
        val topAccelWhy = if (motionAccels != null) "given (--motion-accels)" else "the machine's print acceleration (hardware.machine.<key>.motion.print.acceleration)"
        val text = withMotionConditions(gcodeOf(plate), spec, spool.material, slotN, s, pa[spool.tool], machineDesc, topSpeedWhy, topAccelWhy)
        val expect = plymeld.core.check.Expectations.forSetup(setup)
        fun allProblems(t: String) = plymeld.core.calibrate.MotionCheck.check(spec, t) + plymeld.core.check.GcodeCheck.check(t, expect)
        val problems = allProblems(text)
        fun without(vararg c: String) = gcodeOf(plymeld.core.calibrate.MotionPlate(spec, without = c.toSet()))
        val controls = listOfNotNull(
            "the grooves left out" to without("grooves"),
            "the chamfer left out" to without("chamfer"),
            "each layer's outer wall before its inner" to without("outerfirst"),
            "the faces' speeds reversed" to without("speeds"),
            "faces 1 and 3 swapped" to without("swap"),
            "every band at the first band's acceleration" to without("accel"),
            "the loops starting at a corner, not on face 12" to without("seam"),
            "the slab's upper layers at the fastest face's speed" to without("slabspeed"),
            "the arm marks left out" to without("marks"),
            "the front and back arms' marks swapped" to without("markswap"),
            "the arm marks cut through to the faces" to without("markdeep"),
            "the arm marks in the faces' first third" to without("markearly"),
            "face 1's speed label left out" to without("speedlabel"),
            "face 1's speed label showing the fastest speed" to without("labeltext"),
            "the speed labels laid after the walls (their travels across the walls unlifted)" to without("labelsafter"),
            if (spec.firstTempC != spec.tempC) "the slab's first layer at the tower's temperature" to without("firsttemp") else null,
            if (spec.firstTempC != spec.tempC) "no wait for the tower's temperature before its first line" to without("towerwait") else null,
            "a wall at 1.5x its first face's speed" to alterTileSpeed(text, spec.outerLabel(0)),
            "a wall with twice its plastic" to alterZoneFlow(text, spec.outerLabel(1)),
            "the part fan ${if (spec.fanPct > 0) "off from the second layer" else "on at the middle layer"}" to (if (spec.fanPct > 0) alterFan(text, toOff = true) else fanOnMidway(text)),
            "a line laid outside the plate's cells" to addStrayMotionLine(text, spec),
            "a retraction shortened" to alterRetraction(text),
            removeEndLift(text)?.let { "the end's lift removed" to it },
        )
        val controlMisses = controls.filter { (_, t) -> allProblems(t).isEmpty() }.map { it.first }
        if (problems.isNotEmpty() || controlMisses.isNotEmpty()) {
            val msg = StringBuilder("the plate does not print as specified; not written\n")
            problems.forEach { msg.append("  $it\n") }
            controlMisses.forEach { msg.append("  control not caught: $it (the check cannot fail there)\n") }
            error(msg.toString().trimEnd())
        }
        val changeS = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
        val prediction = plymeld.core.Predict.gcode(text, s.machineDescription, changeS, plymeld.core.Predict.limits(s))
        output.parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.writeString(output, withMachineHeader(text, config, setup, s, prediction))
        val g = plymeld.core.calibrate.MotionGeometry
        echo("motion plate for machine '$machineName' ($machineDesc), filament '${spool.material}' on tool $slotN, build plate '$plateName' (bed ${s.bedTempFirstC} C, constant): " +
            "one cross-shaped tower ${n(3 * g.arm / 1000.0)} x ${n(3 * g.arm / 1000.0)} mm on a ${n(plateSize)} mm slab, ${spec.bands} bands of ${n(spec.bandLayers * h / 1000.0)} mm (${spec.layers} layers), " +
            "outer wall ${n(w / 1000.0)} mm, inner ${n(wi / 1000.0)} mm, ${if (spec.firstTempC != printC) "the tower at $printC C (the slab's first layer at ${spec.firstTempC} C)" else "at $printC C"}, part fan ${n(spec.fanPct)} % from the second layer")
        echo("speeds (mm/s, each on one face along X and one along Y): ${speeds.joinToString(", ") { n(it) }}; the fastest $topSpeedWhy")
        echo("accelerations (mm/s2, band 1 at the bottom): ${accels.joinToString(", ") { n(it) }}; the top $topAccelWhy")
        val (cfx, cfy) = spec.facesAt(g.speeds - 1)
        echo("the control: faces $cfx (along X) and $cfy (along Y) in band ${spec.bands}: ${n(speeds.last())} mm/s at ${n(accels.last())} mm/s2")
        for (b in 0 until spec.bands) if (spec.reach(b) < speeds.last()) echo("  band ${b + 1} (${n(accels[b])} mm/s2): a ${n(spec.shortestFaceUm / 1000.0)} mm face reaches at most ${n(spec.reach(b))} mm/s; its faster faces are credited that")
        echo(when { level != null -> "bed levelling before the plate (hardware.machine.<key>.gcode.level); --no-level to skip it"; noLevel -> "bed levelling: skipped (--no-level)"; else -> "bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print" })
        warnings.forEach { echo("warning: $it") }
        if (maxFlowPlaceholder) echo("recommendation: this filament's maximum flow ($maxFlowKey) has not been measured, and the fastest faces are held to it. " + plymeld.core.calibrate.PlateInstructions.MELT_FIRST.replace("`", ""))
        val paFrom = Resolver.resolveOrNull(setup, "hardware.filament.pressure_advance", plymeld.model.Context(0))?.let { "the filament's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.tool.pressure_advance", plymeld.model.Context(0))?.let { "the tool's, ${it.from}" }
            ?: Resolver.resolveOrNull(setup, "hardware.machine.pressure_advance", plymeld.model.Context(0))?.let { "the machine's, ${it.from}" }
        echo(pa[spool.tool]?.let { v -> "pressure advance: $v s ($paFrom)" }
            ?: ownPaEcho(setup) ?: "pressure advance: not set, so the printer's own value applies, unknown to plymeld (set the machine's, the tool's, or the filament's pressure_advance to state it)")
        echo("check: the layers and cells; every slab and wall line's height, speed, acceleration, width, and filament per mm; on every tower layer each wall's lines on the cross's twelve faces at their speeds, counter-clockwise, every face its full length, the grooves set in; the seams on face 12; the inner wall first; the slab's size and chamfer; the arm marks (n notches in the slab's edge at arm n, clear of the faces); each face's speed label in front of it; the slab's first layer at its temperature, the tower's waited for before it, and the fan; no travel across a wall at its layer's height; and the g-code as the config says. Controls caught: ${controls.joinToString { it.first }}")
        val page = output.resolveSibling(output.fileName.toString().substringBeforeLast('.') + ".md")
        val paText = pa[spool.tool]?.let { v -> "$v s (${paFrom?.substringBefore(',')})" } ?: ownPaPage(setup) ?: "not set: the printer's own, unknown to plymeld (write down what it was if you know)"
        plymeld.core.calibrate.MotionInstructions.write(page, spec, plate, plymeld.core.calibrate.PlateInstructions.Info(
            spool.material, machineDesc, spool.tool, PlymeldVersion.value, output.fileName.toString(),
            prediction.timeS?.let { it / 60 }, paText, s.bedTempFirstC, firstSpeed, spec.frame.speed, s.retractMm,
            plate = plateName, levels = level != null, levelSkipped = noLevel && s.levelGcode != null, warnings = warnings),
            plymeld.core.calibrate.MotionInstructions.Notes(machineName, topSpeedWhy, topAccelWhy,
                if (maxFlowPlaceholder) "This filament's maximum flow (`$maxFlowKey`) is still plymeld's bundled placeholder, not a measurement, and the fastest faces are held to it. " + plymeld.core.calibrate.PlateInstructions.MELT_FIRST else null))
        echo("-> $output")
        echo("-> $page (and ${page.fileName.toString().removeSuffix(".md")}.png): the question, before printing, the conditions, the picture, the faces and bands, how to grade, what each result means, what to enter")
        echo("predicted: $prediction")
        echo("after printing: `plymeld calibrate motion readings ${output.fileName} -o readings.toml`, grade each cell, then `plymeld calibrate motion values readings.toml -o motion.toml`")
    }

    /** The motion plate's conditions in its own g-code, as `; name = value` comments after its first lines. */
    private fun withMotionConditions(text: String, spec: plymeld.core.calibrate.MotionSpec, filament: String, tool: Int, s: SliceSettings, pa: Double?, machineDesc: String,
                                     topSpeedWhy: String, topAccelWhy: String): String {
        val g = plymeld.core.calibrate.MotionGeometry
        val (cfx, cfy) = spec.facesAt(g.speeds - 1)
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = motion (one hollow cross-shaped tower: twelve faces at six speeds, one face along X and one along Y each; a band per acceleration; graded for ringing per band, speed, and direction)")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; machine = $machineName ($machineDesc)")
            add("; temperature = ${spec.firstTempC} C on the slab's first layer (the filament's temperature.first_layer), ${spec.tempC} C above (the tower's), waited for before the tower; part fan ${n(spec.fanPct)} % from the second layer (off on the first)")
            add("; slab = ${n(spec.slabSize / 1000.0)} x ${n(spec.slabSize / 1000.0)} mm cross, ${g.slabLayers} layers (the first ${n(spec.firstLayerUm / 1000.0)} mm, then ${n(spec.layerUm / 1000.0)} mm), the first layer ${n(spec.firstWidthUm / 1000.0)} mm lines at ${n(spec.adhesion.speed)} mm/s, the layers above ${n(spec.widthUm / 1000.0)} mm at ${n(spec.frame.speed)} mm/s and ${n(spec.frame.accel)} mm/s2; the front arm's front-left corner chamfered ${n(g.chamfer / 1000.0)} mm; flow_ratio = ${n(spec.flowRatio)}")
            add("; tower = a cross of five ${n(g.arm / 1000.0)} mm squares, ${spec.bands} bands of ${spec.bandLayers} layers of ${n(spec.layerUm / 1000.0)} mm, a groove ${n(spec.grooveUm / 1000.0)} mm deep for ${g.grooveLayers} layers at the start of each band above the first")
            add("; walls = outer ${n(spec.widthUm / 1000.0)} mm, inner ${n(spec.innerWidthUm / 1000.0)} mm (held to ${n(spec.innerCap)} mm/s), both at their face's speed and their band's acceleration, inner first, the seams on face 12 at ${n(g.seamAt * 100)} % of its length")
            add("; face_length = ${n(spec.shortestFaceUm / 1000.0)} mm (the shortest outer face: what a face reaches at a band's acceleration is worked out on it)")
            add("; speeds = ${spec.speeds.joinToString(", ") { n(it) }} mm/s (speed ${g.speeds}: $topSpeedWhy)")
            add("; accelerations = ${spec.accels.joinToString(", ") { n(it) }} mm/s2 (band ${spec.bands}: $topAccelWhy)")
            add("; control = faces $cfx and $cfy of band ${spec.bands}, ${n(spec.speeds.last())} mm/s at ${n(spec.accels.last())} mm/s2")
            for (k in 1..g.faces) add("; face $k = along ${if (g.alongX(k)) "x" else "y"}, speed ${g.FACE_SPEED[k - 1] + 1}, ${n(spec.faceSpeed(k))} mm/s: ${g.where(k)}")
            for (b in 0 until spec.bands) add("; band ${b + 1} = ${n(spec.accels[b])} mm/s2, z ${n(spec.bandFrom(b) / 1000.0)}-${n(spec.bandTo(b) / 1000.0)} mm, a face reaches at most ${n(spec.reach(b))} mm/s")
            add("; pressure_advance = ${pa?.let { "$it s" } ?: "the printer's own (not set: unknown)"}, bed = ${s.bedTempFirstC} C, retraction = ${n(spec.retractMm)} mm, lift = ${n(spec.liftUm / 1000.0)} mm, layer.time.min = ${n(spec.minLayerTimeS)} s (a parked pause makes up the rest)")
            add("; CALIBRATION_PLATE_END")
        }
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /** A control: a short line extruded outside every cell, before the last band's last outer wall, in front of the slab. */
    private fun addStrayMotionLine(text: String, spec: plymeld.core.calibrate.MotionSpec): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfLast { it == "; CELL: ${spec.outerLabel(spec.bands - 1)}" }
        val x = spec.originXUm / 1000.0; val y = spec.originYUm / 1000.0 - 5
        lines.addAll(at, listOf("G1 X${n(x)} Y${n(y)} F6000", "G1 X${n(x + 5)} Y${n(y)} E0.2 F1200"))
        return lines.joinToString("\n")
    }

    /** The seam plate's conditions in its own g-code, as `; name = value` comments after its first lines. */
    private fun withSeamConditions(text: String, spec: plymeld.core.calibrate.SeamSpec, filament: String, tool: Int, s: SliceSettings, pa: Double?): String {
        val g = plymeld.core.calibrate.SeamGeometry
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = seams (hollow pieces on one slab, each with its outer wall's seam laid one way; graded for how far away each seam shows)")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; temperature = " + (if (spec.firstTempC != spec.tempC) "${spec.firstTempC} C on the slab's first layer (the filament's temperature.first_layer), ${spec.tempC} C above (the filament's temperature.print), waited for before the pieces"
                else "${spec.tempC} C (the filament's temperature.print) throughout") + "; part fan ${n(spec.fanPct)} % from the second layer (off on the first)")
            add("; slab = ${n(spec.slabW / 1000.0)} x ${n(spec.slabD / 1000.0)} mm, ${g.slabLayers} layers (the first ${n(spec.firstLayerUm / 1000.0)} mm, then ${n(spec.layerUm / 1000.0)} mm), the first layer ${n(spec.firstWidthUm / 1000.0)} mm lines at ${n(spec.adhesion.speed)} mm/s, the layers above ${n(spec.widthUm / 1000.0)} mm at ${n(spec.frame.speed)} mm/s and ${n(spec.frame.accel)} mm/s2; flow_ratio = ${n(spec.flowRatio)}")
            add("; pieces = ${spec.pieces.size} of ${n(g.piece / 1000.0)} mm, ${n(g.gap / 1000.0)} mm apart, ${n(spec.pieceLayers * spec.layerUm / 1000.0)} mm tall (${spec.pieceLayers} layers of ${n(spec.layerUm / 1000.0)} mm), piece 1 at the chamfered front-left corner; ticks ${n(g.tickW / 1000.0)} mm wide between the pieces in the front and back edges, ${n(g.tickIn / 1000.0)} mm deep, every ${g.tickLongEvery}th ${n(g.tickLongIn / 1000.0)} mm")
            add("; walls = outer ${n(spec.widthUm / 1000.0)} mm at ${n(spec.outer.speed)} mm/s and ${n(spec.outer.accel)} mm/s2 (no overhang slow-down), inner ${n(spec.innerWidthUm / 1000.0)} mm at ${n(spec.inner.speed)} mm/s and ${n(spec.inner.accel)} mm/s2, the inner seams at the back")
            add("; scarf = ${n(spec.scarfUm / 1000.0)} mm long, starting ${n(spec.scarfStart * 100)} % of the layer thick (plan.technique.scarf_seam.length, .start); corner = ${n(spec.cornerDeg)} deg (plan.technique.corner_seam.corner_angle)")
            add("; pressure_advance = ${pa?.let { "$it s" } ?: "the printer's own (not set: unknown)"}, bed = ${s.bedTempFirstC} C, retraction = ${n(spec.outer.retractMm)} mm, lift = ${n(spec.liftUm / 1000.0)} mm")
            for (p in spec.pieces) add("; piece ${p.index + 1} = ${spec.describe(p.index)}")
            add("; CALIBRATION_PLATE_END")
        }
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /** A control: a short line extruded outside every cell, before the last piece's outer wall, in front of the slab. */
    private fun addStraySeamLine(text: String, spec: plymeld.core.calibrate.SeamSpec): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfLast { it == "; CELL: ${spec.outerLabel(spec.pieces.size - 1)}" }
        val x = spec.originXUm / 1000.0; val y = spec.originYUm / 1000.0 - 5
        lines.addAll(at, listOf("G1 X${n(x)} Y${n(y)} F6000", "G1 X${n(x + 5)} Y${n(y)} E0.2 F1200"))
        return lines.joinToString("\n")
    }

    /** The ironing plate's conditions in its own g-code, as `; name = value` comments after its first lines. */
    private fun withIroningConditions(text: String, spec: plymeld.core.calibrate.IroningSpec, filament: String, tool: Int, s: SliceSettings, pa: Double?): String {
        val g = plymeld.core.calibrate.IroningGeometry
        val block = buildList {
            add("; CALIBRATION_PLATE_START")
            add("; plate = ironing (one piece: ${g.rows} slabs per speed on one base, their tops in zones, each ironed with its own values; a control zone per speed as laid)")
            add("; filament = $filament, tool $tool, build plate $plateName")
            add("; temperature = " + (if (spec.firstTempC != spec.tempC) "${spec.firstTempC} C on the first layer and the brim (the filament's temperature.first_layer), ${spec.tempC} C above (the filament's temperature.print), waited for before the top"
                else "${spec.tempC} C (the filament's temperature.print) throughout") + "; part fan ${n(spec.fanPct)} % from the second layer (off on the first)")
            add("; piece = ${n(spec.pieceW / 1000.0)} x ${n(spec.pieceD / 1000.0)} mm, a ${n(g.brim / 1000.0)} mm brim; ${spec.slabs} slabs ${n(spec.slabW / 1000.0)} x ${n(spec.slabD / 1000.0)} mm (${g.rows} per speed, front to back, a row of zones each) on a base of the first ${g.baseLayers} layers, ${n(g.groove / 1000.0)} mm grooves between them, the legend behind them; " +
                "${spec.layers} layers (the first ${n(spec.firstLayerUm / 1000.0)} mm, then ${n(spec.layerUm / 1000.0)} mm; the top at z ${n(spec.topZ / 1000.0)} mm), ${n(spec.widthUm / 1000.0)} mm lines (the first layer ${n(spec.firstWidthUm / 1000.0)} mm), flow_ratio = ${n(spec.flowRatio)}; " +
                "the first layer ${n(spec.adhesion.speed)} mm/s, the middle layers ${n(spec.frame.speed)} mm/s and ${n(spec.frame.accel)} mm/s2, the top fill ${n(spec.top.speed)} mm/s and ${n(spec.top.accel)} mm/s2 along the slab (0 deg)")
            add("; zones = ${spec.zones.size} of ${n(g.zone / 1000.0)} mm, ${spec.columns} to a slab, ${n(g.frame / 1000.0)} mm as laid between neighbors; zone 1 at the chamfered front-left corner, zone 2 behind it on the next slab, then the next column (the print order); below, a zone's row is its block's front or back slab")
            add("; labels = notches above the base: on each zone's front edge its flow (square notches) then its spacing (V notches), on its back edge its inset (round-bottomed notches) then its angle (slanted notches), each slab's left end its speed (bar notches); legend = ${spec.legend.joinToString(" / ")}")
            add("; ironing = speeds ${spec.speeds.joinToString(", ") { n(it) }} mm/s (a block each), flows ${spec.flows.joinToString(", ") { n(it * 100) }} % (rows of the plan), spacings ${spec.spacingsUm.joinToString(", ") { n(it / 1000.0) }} mm (columns), " +
                "angles ${spec.anglesDeg.joinToString(", ") { n(it) }} deg and insets ${spec.insetsUm.joinToString(", ") { n(it / 1000.0) }} mm (Latin squares over the grid); ${n(spec.ironAccel)} mm/s2; lines declared ${n(spec.nozzleUm / 1000.0)} mm; plastic by the ironing rule (flow x spacing x layer height, times the flow ratio); lift ${n(spec.liftUm / 1000.0)} mm")
            add("; pressure_advance = ${pa?.let { "$it s" } ?: "the printer's own (not set: unknown)"}, bed = ${s.bedTempFirstC} C, retraction = ${n(spec.retractMm)} mm")
            for (z in spec.zones) add("; zone ${z.index + 1} = column ${spec.col(z.index) + 1}, ${if (spec.row(z.index) == 0) "front" else "back"}, block ${z.block + 1}: " +
                (if (z.control) "control, not ironed" else "speed ${n(spec.speeds[z.block])} mm/s, flow ${n(spec.flows[z.flowIdx] * 100)} %, spacing ${n(spec.spacingsUm[z.spacingIdx] / 1000.0)} mm, angle ${n(spec.anglesDeg[z.angleIdx])} deg, inset ${n(spec.insetsUm[z.insetIdx] / 1000.0)} mm"))
            add("; CALIBRATION_PLATE_END")
        }
        block.drop(1).dropLast(1).firstOrNull { !it.matches(Regex("; [a-z_0-9. ]+ = \\S.*")) }?.let { error("the conditions block has a malformed line: '$it'") }
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; total layer number:") }.let { if (it < 0) 0 else it + 1 }
        lines.addAll(at, block)
        return lines.joinToString("\n")
    }

    /** A control: every extrusion of the zone cell [label] with twice its plastic. */
    private fun alterZoneFlow(text: String, label: String): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it == "; CELL: $label" }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        for (k in at until end) Regex("(G1 X[-0-9.]+ Y[-0-9.]+ E)([0-9.]+)(.*)").matchEntire(lines[k])?.let { m -> lines[k] = m.groupValues[1] + String.format(Locale.ROOT, "%.5f", m.groupValues[2].toDouble() * 2) + m.groupValues[3] }
        return lines.joinToString("\n")
    }

    /** A control: a short line extruded outside every cell, between the last zone's cell and the one before it, in front of the slab. */
    private fun addStrayIroningLine(text: String, spec: plymeld.core.calibrate.IroningSpec): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfLast { it.startsWith("; CELL: zone ") }
        val x = spec.originXUm / 1000.0; val y = spec.originYUm / 1000.0 - 5
        lines.addAll(at, listOf("G1 X${n(x)} Y${n(y)} F6000", "G1 X${n(x + 5)} Y${n(y)} E0.2 F1200"))
        return lines.joinToString("\n")
    }

    private fun range(v: String, opt: String): Pair<Double, Double> {
        val parts = v.split('-').map { it.trim() }
        if (parts.size != 2) throw UsageError("$opt is a range FROM-TO (got '$v')")
        val a = parts[0].toDoubleOrNull(); val b = parts[1].toDoubleOrNull()
        if (a == null || b == null) throw UsageError("$opt is a range FROM-TO (got '$v')")
        return a to b
    }

    /** Whole degrees only: refused, not rounded. */
    private fun whole(v: Double, opt: String): Int {
        if (v != Math.floor(v)) throw UsageError("$opt takes whole degrees only (got ${n(v)}): temperatures and the step between them are integers")
        return v.toInt()
    }

    /** A control: one line of a middle flow patch, on its top layer, given 4 % more filament (between two neighboring patches' ratios). */
    private fun alterPatchFlow(text: String): String {
        val lines = text.lines().toMutableList()
        val cells = lines.indices.filter { lines[it].startsWith("; CELL: flow ") }
        val at = cells[cells.size - 4]
        val k = (at until lines.size).first { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(lines[it]) }
        val e = lines[k].substringAfter(" E").substringBefore(' ').toDouble()
        lines[k] = lines[k].replace(" E" + lines[k].substringAfter(" E").substringBefore(' '), " E" + String.format(Locale.ROOT, "%.5f", e * 1.04))
        return lines.joinToString("\n")
    }

    /**
     * A control: mid-print, the retraction before the stringing ring's post 2 (hop 1) given hop 2's length, its restore
     * too, so the retraction stays balanced and is one the plate uses: only the stringing check can see it.
     */
    private fun alterRingRetraction(text: String, st: plymeld.core.calibrate.Strings): String {
        val lines = text.lines().toMutableList()
        // post 2's cells whose travel in retracts (its first ring on a layer)
        val cells = lines.indices.filter { i -> lines[i] == "; CELL: post 2" && (i + 1 until minOf(lines.size, i + 6)).any { lines[it].startsWith("G1 E-") } }
        val at = cells[cells.size / 2]
        val other = st.lengthsMm[1]
        val k = (at until lines.size).first { lines[it].startsWith("G1 E-") }
        val r = (k until lines.size).first { Regex("G1 E[0-9.]+ F.*").matches(lines[it]) }
        lines[k] = "G1 E-${String.format(Locale.ROOT, "%.3f", other)} F" + lines[k].substringAfter(" F")
        lines[r] = "G1 E${String.format(Locale.ROOT, "%.3f", other)} F" + lines[r].substringAfter(" F")
        return lines.joinToString("\n")
    }

    /**
     * A control: one frame cell above the first layer (the middle one of the plate's first frame label: a base, the post) with
     * its speed settings at [speed] mm/s and, given, its acceleration settings at [accel] mm/s2 (as before, at the
     * visible-line cap).
     */
    private fun alterFrame(text: String, speed: Double, accel: Double? = null): String {
        val lines = text.lines().toMutableList()
        val label = lines.first { it.startsWith("; CELL: ") && CalibrationCheck.isFrameCell(it.removePrefix("; CELL: ")) }
        val at = lines.indices.filter { lines[it] == label }.let { it[it.size / 2] }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        for (k in at until end) {
            if (lines[k].startsWith("G1 F")) lines[k] = "G1 F" + Math.round(speed * 60)
            if (accel != null && (lines[k].startsWith("SET_VELOCITY_LIMIT ") || lines[k].startsWith("M204 ")))
                lines[k] = lines[k].replace(Regex("(ACCEL=|\\bS)[0-9.]+")) { it.groupValues[1] + Math.round(accel) }
        }
        return lines.joinToString("\n")
    }

    /** A control: the middle first-layer cell of the plate (an "adhesion:" cell: the first layer's lines) with its speed settings at [speed] mm/s. */
    private fun alterFirst(text: String, speed: Double): String {
        val lines = text.lines().toMutableList()
        val at = lines.indices.filter { lines[it].startsWith("; CELL: adhesion:") }.let { it[it.size / 2] }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        for (k in at until end) if (lines[k].startsWith("G1 F")) lines[k] = "G1 F" + Math.round(speed * 60)
        return lines.joinToString("\n")
    }

    /** A control: the tile cell [label] brought down 0.04 mm higher than its height (its descent over it; its lines follow at that height). */
    private fun alterTileZ(text: String, label: String): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it == "; CELL: $label" }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        val k = (at until end).first { lines[it].startsWith("G1 Z") }
        lines[k] = "G1 Z" + String.format(Locale.ROOT, "%.3f", lines[k].removePrefix("G1 Z").substringBefore(' ').toDouble() + 0.04)
        return lines.joinToString("\n")
    }

    /** A control: the tile cell [label] at 1.5x its speed (its first speed setting). */
    private fun alterTileSpeed(text: String, label: String): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it == "; CELL: $label" }
        val end = (at until lines.size).first { lines[it] == "; CELL END" }
        val k = (at until end).first { lines[it].startsWith("G1 F") }
        lines[k] = "G1 F" + Math.round(lines[k].removePrefix("G1 F").toDouble() * 1.5)
        return lines.joinToString("\n")
    }

    /**
     * A control: the last wait (M109) before block [k]'s first line (block 0's: the plate's own guard after the start
     * template) removed, or given [toC] instead of the block's temperature.
     */
    private fun alterBlockWait(text: String, spec: plymeld.core.calibrate.FirstLayerSpec, k: Int, remove: Boolean = false, toC: Int? = null): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; CELL: block ${k + 1} ") }
        val w = (0 until at).last { lines[it].startsWith("M109 S${spec.tempsC[k]}") }
        if (remove) lines.removeAt(w) else lines[w] = "M109 S$toC" + lines[w].substringAfter("M109 S${spec.tempsC[k]}")
        return lines.joinToString("\n")
    }

    /**
     * A control: after the first column's tiles (before the next column's, or the frame), a travel down at its first tile's
     * height straight across that tile, from 2 mm left of it to 2 mm right of it (neither end on it), then back up 1 mm.
     */
    private fun addTravelAcross(text: String, spec: plymeld.core.calibrate.FirstLayerSpec, plate: plymeld.core.calibrate.FirstLayerPlate): String {
        val lines = text.lines().toMutableList()
        val at = lines.indexOfFirst { it.startsWith("; CELL: block 2 ") || it.startsWith("; CELL: block 1 frame ") }.takeIf { it >= 0 } ?: lines.indexOfLast { it == "; CELL END" }
        val b = plate.tileBox(0, 0, 0).let { doubleArrayOf((it[0] + spec.originXUm) / 1000.0, (it[1] + spec.originYUm) / 1000.0, (it[2] + spec.originXUm) / 1000.0, (it[3] + spec.originYUm) / 1000.0) }
        val z = spec.tiles.heightsUm[0] / 1000.0; val y = (b[1] + b[3]) / 2
        lines.addAll(at, listOf("G1 X${n(b[0] - 2)} Y${n(y)} F6000", "G1 Z${n(z)}", "G1 X${n(b[2] + 2)} Y${n(y)} F6000", "G1 Z${n(z + 1)}"))
        return lines.joinToString("\n")
    }

    /** A control: a short line extruded outside every cell, between block 1's last cell and the next thing on the plate, in front of the plate. */
    private fun addStrayLine(text: String, spec: plymeld.core.calibrate.FirstLayerSpec): String {
        val lines = text.lines().toMutableList()
        val end = (lines.indexOfFirst { it == "; CELL: ${spec.primeLabel(0)}" } until lines.size).first { lines[it] == "; CELL END" }
        val x = spec.originXUm / 1000.0; val y = spec.originYUm / 1000.0 - 5
        lines.addAll(end + 1, listOf("G1 X${n(x)} Y${n(y)} F6000", "G1 X${n(x + 5)} Y${n(y)} E0.2 F1200"))
        return lines.joinToString("\n")
    }

    /**
     * A control: the middle part-fan command of the plate's layers changed: to a third of its speed, or ([toOff]) off. (The
     * plate's own commands only: those between its first layer and the end template, where a level is set; with one fan level
     * all along there is one.)
     */
    private fun alterFan(text: String, toOff: Boolean = false): String {
        val lines = text.lines().toMutableList()
        val first = lines.indexOfFirst { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") }
        val last = lines.indexOfLast { it == "; CELL END" }
        val k = (first until last).filter { lines[it].startsWith("M106 S") && lines[it] != "M106 S0" }.let { it[it.size / 2] }
        lines[k] = "M106 S" + if (toOff) 0 else lines[k].removePrefix("M106 S").toLong() / 3
        return lines.joinToString("\n")
    }

    /** A control: the part fan turned on (full) at the start of the middle layer, on a plate whose fan is off throughout. */
    private fun fanOnMidway(text: String): String {
        val lines = text.lines().toMutableList()
        val layers = lines.indices.filter { lines[it].startsWith(";LAYER_CHANGE") || lines[it].startsWith("; CHANGE_LAYER") }
        lines.add(layers[layers.size / 2] + 1, "M106 S255")
        return lines.joinToString("\n")
    }

    /** A control: one retraction in the middle of the print shortened by a third (its restore left as it was). */
    private fun alterRetraction(text: String): String {
        val lines = text.lines().toMutableList()
        val first = lines.indexOfFirst { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") }
        val k = (first until lines.size).filter { Regex("G1 E-[0-9.]+ F\\d+").matches(lines[it]) }.let { it[it.size / 2] }
        val v = lines[k].substringAfter("E-").substringBefore(' ').toDouble()
        lines[k] = "G1 E-${String.format(Locale.ROOT, "%.3f", v * 2 / 3)} F" + lines[k].substringAfter(" F")
        return lines.joinToString("\n")
    }

    /**
     * A control: mid-print, a travel at print height across a visible line: with the melt test, after band 1's FAST strip 1,
     * across that strip's lines (front to back through its middle); without it, after a post layer, across the post's front face.
     */
    private fun addLowTravel(text: String, spec: CalibrationSpec, plate: CalibrationPlate): String {
        val lines = text.lines().toMutableList()
        val mt = spec.melt
        val cell = if (mt != null) "; CELL: ${mt.label(0, 0, 0)}" else "; CELL: post"
        val cells = lines.indices.filter { lines[it] == cell }
        val end = (cells[cells.size / 2] until lines.size).first { lines[it] == "; CELL END" }
        if (mt != null) {
            // from 1 mm in front of the step to past strip 1's last line
            val b = plate.meltBox(0, 0); val x = (spec.originXUm + (b[0] + b[2]) / 2) / 1000.0
            val y0 = (spec.originYUm + b[1]) / 1000.0 - 1; val y1 = (spec.originYUm + b[1] + plate.meltLineY(0, mt.lines - 1)) / 1000.0 + 1
            lines.addAll(end + 1, listOf("G1 X${n(x)} Y${n(y0)} F6000", "G1 X${n(x)} Y${n(y1)} F6000"))
        } else {
            val (x, y) = plate.footprint(Part.POST).let { b -> (spec.originXUm + (b[0] + b[2]) / 2) / 1000.0 to (spec.originYUm + b[1]) / 1000.0 }
            lines.addAll(end + 1, listOf("G1 X${n(x)} Y${n(y + 5)} F6000", "G1 X${n(x)} Y${n(y - 5)} F6000"))
        }
        return lines.joinToString("\n")
    }

    /**
     * A control: the lift above the print after its last line taken out: every rise (a Z-only move) between the last
     * cell and the next move across, so a plate that rises twice there (the first-layer plate: off its last frame line,
     * then above every line on the plate) loses both. Null when the end never moves the head across after the print, or never
     * lifts before it does: there is no lift to take out, and the rule it tests (the head above the print before it moves
     * sideways) cannot be broken (an end template of heaters off alone; the control was "not caught" and every
     * plate refused).
     */
    private fun removeEndLift(text: String): String? {
        val lines = text.lines().toMutableList()
        val last = lines.indexOfLast { it == "; CELL END" }
        val across = (last until lines.size).firstOrNull { l -> lines[l].startsWith("G1 ") && Regex("\\s[XY]-?[0-9.]").containsMatchIn(lines[l].substringBefore(';')) } ?: return null
        val rises = (last until across).filter { lines[it].startsWith("G1 Z") }
        if (rises.isEmpty()) return null
        for (k in rises.reversed()) lines.removeAt(k)
        return lines.joinToString("\n")
    }

    private fun n(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.')
    /** A pressure advance (s) to the labels' resolution, trailing zeros dropped: 0.0425. */
    private fun n4(v: Double) = java.math.BigDecimal(v).setScale(4, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

    /**
     * The options a pressure-advance plate was made with that the next plate of the same filament, tool, and machine needs,
     * as a shell would take them: the config (-cf, -cv as given), the machine, tool, filament, and build plate as resolved,
     * the speeds and levelling as given. The next plate adds its own values and output.
     */
    private fun madeWith(slotN: Int, filament: String): String {
        fun q(a: String) = if (a.matches(Regex("[A-Za-z0-9._/=:,@+%-]+"))) a else "'" + a.replace("'", "'\\''") + "'"
        val args = buildList {
            for (f in configFiles) { add("-cf"); add(f) }
            for (v in configValues) { add("-cv"); add(v) }
            add("--machine"); add(machineName); add("--tool"); add(slotN.toString()); add("--filament"); add(filament)
            like?.let { add("--like"); add(it) }
            add("--plate"); add(plateName)
            paSpeeds?.let { add("--speeds"); add(it) }
            if (noLevel) add("--no-level")
        }
        return args.joinToString(" ") { q(it) }
    }

    /** Where the machine sets its own pressure advance (firmware.own_pressure_advance): what a plate says of it as it is made; null otherwise. */
    protected fun ownPaEcho(setup: plymeld.model.Setup): String? = if (setup.ownPressureAdvance())
        "pressure advance: the machine sets its own (hardware.machine.<key>.firmware.own_pressure_advance), unknown to plymeld, which writes none" else null
    /** The same for the plate's page; null where the machine does not set its own. */
    protected fun ownPaPage(setup: plymeld.model.Setup): String? = if (setup.ownPressureAdvance())
        "the machine's own (it sets its own: firmware.own_pressure_advance), unknown to plymeld (write down what it was if you know)" else null

    /** The kinds, one line each, in the order to run them. */
    fun kindsText() = "The kinds, in the order to run them (each plate's results lean on the ones before it):\n" +
        KINDS.joinToString("\n") { (k, what) -> "  ${k.padEnd(12)} $what" }

    companion object {
        /**
         * The time Klipper smooths pressure advance over unless its config says otherwise (s): its documented default
         * pressure_advance_smooth_time (Klipper, Config Reference, [extruder]: 0.040), read where a machine model gives none.
         */
        const val KLIPPER_SMOOTH_S = 0.04

        /**
         * The plates by kind, in the order they are run: each relies on the results of those before it (melt rate, then
         * flow ratio, then the first layer, the machine's motion, the filament's other tests, the surfaces), each with what
         * it is for.
         */
        val KINDS = listOf(
            "melt" to "the melt rate: a column per temperature, a row per flow, each row's flow printed beside it. Run it first: an accurate melt rate drives so much of the rest, and the other plates are not reliable until it is measured",
            "flow" to "the flow ratio: a column per temperature, a patch per ratio, each patch solid at its own ratio and its top laid at its column's temperature; after the melt plate",
            "pressure-advance" to "the pressure advance: straight walls printed slow, fast, slow, each at its own value, read for a groove or a ridge just after each speed change; the value is where the grooves turn into ridges, each value twice; then a confirm plate at three flow steps (--confirm); beside the flow plate, after the melt plate",
            "first-layer" to "the first layer: nozzle height x first-layer speed by temperature, one layer of tiles in one piece, a column per temperature and a row per speed and height (a frame over their walls and a border round the base to peel it by); after the flow plate",
            "motion" to "the machine: the speed and acceleration a visible wall holds without ringing, per direction: one cross-shaped tower, twelve faces at six speeds (one along X and one along Y each), a band per acceleration, the top band's fastest faces at the machine's limits; graded per cell, the readings become the machine's visible speed and acceleration; after the first-layer plate (the tower, shaken at the machine's limits, needs a first layer that holds)",
            "filament" to "the filament's other tests by temperature band, once its melt rate and flow ratio are measured: overhangs, bridges, stringing, line widths, fit (--tests picks a subset)",
            "ironing" to "the ironing values: one flat slab whose top is divided into zones, each ironed with its own combination of flow, line spacing, speed, angle, and inset, a control zone per speed left as laid; graded zone by zone, the readings become the ironing values",
            "seams" to "the seam measure's shares: eight hollow pieces on one slab, each with its outer wall's seam laid one way (a point seam or plymeld's scarf, on a round wall, mid-face, in a corner, on a face leaning 30 and 45 degrees); graded piece by piece for how far away the seam shows, the ratios become method.quality.seam.scarf_share, .corner_share and method.quality.visibility.under",
        )

        /** The motion plate's band count unless --motion-accels gives the accelerations: plymeld's choice, as many as there are speeds (a 6 x 6 grid of speed by acceleration in each direction). */
        const val MOTION_BANDS = 6
    }
}


/** `plymeld calibrate melt`: the melt plate, run first: a table printed in plastic, a column per temperature and a row per flow. */
class MeltPlateCommand : PlateCommand("melt", "melt") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The melt plate, run first: the filament's maximum flow at each temperature, as a table printed in plastic: a column per " +
        "temperature (--temps, --step), a row per flow (--flow-step), each row the same line at its own speed, its flow printed beside it and each " +
        "column's temperature in front of it. Read each column for the first row with gaps; `melt values` writes the readings into the filament's config. " +
        "An accurate melt rate drives so much of the rest."
    init { subcommands(MeltValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val temps: String? by option("--temps", metavar = "LOW-HIGH", help = "the temperature range, whole degrees C, e.g. 190-230: a column each, from LOW up by --step; default: the filament's (temperature.min to temperature.max, the spool's label)")
    override val step: String? by option("--step", metavar = "C", help = "whole degrees between columns; default: 10")
    override val meltFan: String? by option("--melt-fan", metavar = "on|off", help = "the part fan in every column at the filament's own (cooling.fan; 'on', the default) or off ('off')")
    override val flowStep: String? by option("--flow-step", metavar = "MM3S", help = "mm3/s between rows (each row that much more flow than the one in front of it); the rows run from half the filament's expected maximum flow to twice it; default: the finest of 0.25, 0.5, 1, 2, 5 that keeps them to about 24")
}

/** `plymeld calibrate flow`: the flow plate, after the melt plate: a column per temperature, a patch per flow ratio. */
class FlowPlateCommand : PlateCommand("flow", "flow") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The flow plate, after the melt plate: the filament's flow ratio at each temperature: a column per " +
        "temperature (--temps, --step), a square patch per flow ratio (--flow-ratios), each patch solid at its own ratio with its lines exactly the bead " +
        "spacing apart, its top laid at its column's temperature; each patch's ratio printed beside its row and each column's temperature in front of it. " +
        "Read each column for its best patch (closed, no gaps, no ridges); `flow values` writes the readings into the filament's config."
    init { subcommands(FlowValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val temps: String? by option("--temps", metavar = "LOW-HIGH", help = "the temperature range, whole degrees C, e.g. 190-230: a column each, from LOW up by --step; default: the filament's (temperature.min to temperature.max, the spool's label)")
    override val step: String? by option("--step", metavar = "C", help = "whole degrees between columns; default: 10")
    override val flowRatios: String? by option("--flow-ratios", metavar = "R,R,...", help = "the patches' flow ratios, low to high (e.g. 0.94,0.97,1,1.03), a patch each in every column; default: the filament's flow ratio x 0.94 to 1.06 in 0.02 steps")
}

/** `plymeld calibrate pressure-advance`, with `readings` and `values` (PressureAdvanceReadings.kt). */
class PressureAdvancePlateCommand : PlateCommand("pressure-advance", "pressure-advance") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The pressure-advance plate, after the melt plate (every line it lays at no more than a share of the measured melt rate, hardware.tool.<key>.calibrate.pressure_advance.share) and beside the flow plate: straight single-line walls, each on its own strip of the base (joined by break-off tabs, so each snaps out to be read against a light and in a raking light), each printed the same way on every layer, slow, then fast, then slow, at its own pressure advance, set just before it. " +
        "Too little leaves the line thin just after the speed-up (a groove up the wall) and fat just after the slow-down (a ridge); too much the other way round. The find plate prints two rows, the front one's values rising, the back one's falling (each value twice), a corner beside each wall read on its own, the values 0 and, each a fixed ratio below the next, from the plate's top (the lowest of the tool's ceiling, hardware.tool.<key>.calibrate.pressure_advance.max or its model's toolhead's; the highest value the extruder's push allows, where hardware.tool.<key>.extrude.speed.max is known; and the highest value whose walls fit the bed) " +
        "down to the tool's lowest (.min), or --values, or evenly inside --bracket for a fine plate; the confirm plate (--confirm VALUE --confirm-step S) prints the value and its neighbors at three flow steps; on a machine that sets its own (firmware.own_pressure_advance), --confirm machine checks its value with no command written. " +
        "Each wall's value is printed in front of it in milliseconds, a hole marks each speed change. `pressure-advance readings` writes the sheet to fill in; `pressure-advance values` turns it into the filament's pressure advance (hardware.filament.<key>.pressure_advance), or the tool's with --into tool."
    init { subcommands(PressureAdvanceReadingsCommand(), PressureAdvanceValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val paValues: String? by option("--values", metavar = "S,S,...", help = "the find plate's values (s, or 40ms), rising, each printed twice; default: 0 and ${plymeld.core.calibrate.PressureAdvanceGeometry.values - 1} values from the tool's lowest (hardware.tool.<key>.calibrate.pressure_advance.min) to the plate's top, each a fixed ratio below the next: the lowest of the tool's ceiling (.max), the highest value the extruder's push allows, and the highest value whose walls fit the bed")
    override val paBracket: String? by option("--bracket", metavar = "LOW-HIGH", help = "a fine find plate: ${plymeld.core.calibrate.PressureAdvanceGeometry.values} values evenly from LOW to HIGH (s), the bracket a first plate gave (`pressure-advance values` prints it)")
    override val paConfirm: String? by option("--confirm", metavar = "S|machine", help = "the confirm plate: the value (s) and its neighbors, each at three flow steps; 'machine' on a machine that sets its own (hardware.machine.<key>.firmware.own_pressure_advance): its own value, no command written")
    override val paConfirmStep: String? by option("--confirm-step", metavar = "S|DOWN,UP", help = "with --confirm VALUE: the step (s) from the value to each neighbor, or two, down to the lower neighbor and up to the higher, so the neighbors are a bracket's ends exactly (`pressure-advance values` prints them)")
    override val paSpeeds: String? by option("--speeds", metavar = "SLOW,FAST", help = "the walls' slow and fast speeds (mm/s); default: a visible wall's speed (plan.mechanism.motion.visible.speed) and the machine's print speed (hardware.machine.<key>.motion.print.speed), each held to the plate's share of the filament's measured maximum flow (hardware.tool.<key>.calibrate.pressure_advance.share)")
}

/** `plymeld calibrate motion`, with `readings` and `values` (MotionReadings.kt). */
class MotionPlateCommand : PlateCommand("motion", "motion") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The motion plate, once per printer: one hollow cross-shaped tower whose twelve faces print at six speeds (--motion-speeds; one face along X and one along Y for each) and whose bands print at one acceleration each (--motion-accels), the fastest face of the top band at the machine's limits (the control), each face's speed printed on the slab in front of it; the tower at one temperature (the filament's temperature.print, or --print-temp), the slab's first layer at the filament's first-layer temperature. Graded per band, speed, and direction for ringing; `motion readings` writes the sheet to grade, `motion values` turns the graded sheet into the machine's visible speed and acceleration (hardware.machine.<key>.motion.visible.speed, .visible.acceleration)."
    init { subcommands(MotionReadingsCommand(), MotionValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val motionSpeeds: String? by option("--motion-speeds", metavar = "S,S,S,S,S,S", help = "motion plate: the six speeds (mm/s), slow to fast, each on one face along X and one along Y; default: the machine's print speed held to the filament's maximum flow on the outer wall (the control), and a sixth of it, two sixths ... rounded down to 5 mm/s (plymeld's choice)")
    override val motionAccels: String? by option("--motion-accels", metavar = "A,A,...", help = "motion plate: one acceleration (mm/s2) per band, bottom to top, rising; default: $MOTION_BANDS bands, the machine's print acceleration at the top (the control) and each band below half the one above (plymeld's choice)")
    override val printTemp: String? by option("--print-temp", metavar = "C", help = "motion plate: the tower's temperature (the slab's first layer follows the filament's first-layer temperature, worked out from it); filament plate: the fit test stands on a pedestal up to the band nearest this temperature; default: the filament's temperature.print")
}

/** `plymeld calibrate filament`. */
class FilamentPlateCommand : PlateCommand("filament", "filament") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The filament plate, once its melt rate and flow ratio are measured: the filament's other families of tests (--tests: overhangs, bridges, stringing, line widths, fit) with the temperature varied by band of height, each family on a base of its own, packed to fit the bed, with each family's predicted time and filament."
    override val temps: String? by option("--temps", metavar = "LOW-HIGH", help = "the temperature range, whole degrees C, e.g. 195-225: a band each, hottest at the bottom; default: the filament's (temperature.min to temperature.max, the spool's label)")
    override val step: String? by option("--step", metavar = "C", help = "whole degrees between bands; default: 5")
    override val fans: String? by option("--fans", metavar = "LOW,HIGH", help = "filament plate: the two part-fan levels (%) the bands alternate between; default: half the filament's fan, and 1.5x it (at most 100)")
    override val tests: String? by option("--tests", metavar = "T,T,...", help = "filament plate: the families of tests on it, any of ${Part.FILAMENT_TESTS.joinToString(",") { it.key }} (default: all of them); " +
        "this is the way to leave a family out. The melt test and the motion test are plates of their own (`calibrate melt`, `calibrate motion`). " +
        "The ruler post is on every filament plate (the bands' ruler); every family stands on a base of its own; the families are packed to fit the bed, and a plate that does not fit is refused with a split to make instead")
    override val overhangs: String? by option("--overhangs", metavar = "S,S,...", help = "filament plate: the overhang ledges' shares of a line, low to high (e.g. 0.3,0.5,0.7); default: the filament's overhang.share (hardware.filament.<key>.overhang.share) x 0.7, 1, 1.3, 1.6, 1.9 (below 1)")
    override val printTemp: String? by option("--print-temp", metavar = "C", help = "motion plate: the tower's temperature (the slab's first layer follows the filament's first-layer temperature, worked out from it); filament plate: the fit test stands on a pedestal up to the band nearest this temperature; default: the filament's temperature.print")
    override val peg: String? by option("--peg", metavar = "MM", help = "filament plate: the fit test's peg diameter (mm); default: 5")
    override val holes: String? by option("--holes", metavar = "MM,MM,...", help = "filament plate: the fit test's hole diameters (mm), small to large; default: the peg and 0.1 to 0.4 mm larger")
    override val widths: String? by option("--widths", metavar = "MM,MM,...", help = "filament plate: the line-width tubes' widths (mm), narrow to wide; default: the nozzle bore x 0.625 to 1.875 in steps of 0.125 (0.25 to 0.75 mm on a 0.4 mm nozzle)")
    override val retractions: String? by option("--retractions", metavar = "MM,MM,...", help = "filament plate: the stringing ring's hops' retraction lengths (mm), short to long, a hop each; default: 0.4 to 1.6 in 0.2 steps")
    override val spans: String? by option("--spans", metavar = "MM,MM,...", help = "filament plate: the bridges' spans (mm), short to long; default: 8,14,20,26,32")
    override val bridgeFlows: String? by option("--bridge-flows", metavar = "FRONT,BACK", help = "filament plate: the two bridge decks' flow ratios; default: the bridge flow (plan.layer.bridge.flow) and 0.85x it")
}

/** `plymeld calibrate first-layer`, with `values` (FirstLayerValuesCommand: a read plate into the filament's first-layer window). */
class FirstLayerPlateCommand : PlateCommand("first-layer", "first-layer") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The first-layer plate: one layer of first-layer tiles laid out as a table, one piece: a column per temperature (--temps, --step; coolest first, heating and priming between columns), and in each column a group of rows per speed (--first-speeds), a row per nozzle height in each (--first-heights), every tile a strip of the same size; each column's temperature, each speed, and each row's height raised on a base along the plate's front and left. A tile whose flow would pass the filament's maximum flow at its column's temperature is left out; every tile touches its neighbors, and a frame of three layers over their walls, with as tall a border round the base (neither read), is the raised edge to peel the plate by. `first-layer values` writes the read plate into the filament's first-layer window: per column, the lowest good nozzle height and the fastest good speed."
    init { subcommands(FirstLayerValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val temps: String? by option("--temps", metavar = "LOW-HIGH", help = "the temperature range, whole degrees C, e.g. 200-230, or a single temperature, e.g. 215: a column each, coolest first; default: the filament's print temperature up to its maximum by --step, as far as the maximum goes (temperature.print to temperature.max: a first layer runs at or above the print temperature), or its whole range (temperature.min to temperature.max) where it has no print temperature")
    override val step: String? by option("--step", metavar = "C", help = "whole degrees between columns; default: 10")
    override val firstSpeeds: String? by option("--first-speeds", metavar = "S,S,...", help = "first-layer plate: the tiles' speeds (mm/s), a group of rows each, slow to fast; default: the first layer's speed (plan.mechanism.motion.first_layer.speed) halved, as it is, and half as fast again")
    override val firstHeights: String? by option("--first-heights", metavar = "MM,MM,...", help = "first-layer plate: the tiles' nozzle heights (mm), a row each in every speed's group, low to high; default: the first layer's height (plan.mechanism.adhesion.first_layer.height) and three steps above it, a step a tenth of the nozzle's bore (hardware.tool.<key>.bore: 0.2 to 0.32 mm for a 0.2 mm first layer on a 0.4 mm nozzle)")
}

/** `plymeld calibrate ironing`, with `readings` and `values` (IroningReadings.kt). */
class IroningPlateCommand : PlateCommand("ironing", "ironing") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The ironing plate: zones of flat tops, each ironed with its own combination of the ironing flow, line spacing, speed, angle, and inset (--ironing-flows, --ironing-spacings, --ironing-speeds, --ironing-angles, --ironing-insets; every value in several zones, a control zone per speed left as laid): two slabs per speed (a row of zones each) on one base with a brim, a strip as laid between neighboring zones, each zone notched with its flow and spacing (front edge) and its inset and angle (back edge), each slab with its speed, and a legend of the marks; the top and its ironing at the filament's print temperature (temperature.print), the first layer at its first-layer temperature. `ironing readings` writes its sheet to grade; `ironing values` turns the graded sheet into the ironing values."
    init { subcommands(IroningReadingsCommand(), IroningValuesCommand()) }
    override val invokeWithoutSubcommand = true
    override val ironingFlows: String? by option("--ironing-flows", metavar = "F,F,...", help = "ironing plate: the ironing flows (shares of a full line, e.g. 0.05,0.1,0.15 or 5%,10%,15%), low to high, a row of the plan each; default: the current ironing flow (plan.technique.ironing.flow) x 0.5, 1, 1.5 (a placeholder spread, not measured)")
    override val ironingSpacings: String? by option("--ironing-spacings", metavar = "MM,MM,...", help = "ironing plate: the ironing line spacings (mm), fine to wide, a column of the plan each; default: the current spacing (plan.technique.ironing.spacing) x 1, 1.5, 2 (a placeholder spread, not measured)")
    override val ironingSpeeds: String? by option("--ironing-speeds", metavar = "S,S,...", help = "ironing plate: the ironing speeds (mm/s), slow to fast, a block of zones each; default: the current speed (plan.technique.ironing.speed) x 0.5, 1, 1.5 (a placeholder spread, not measured)")
    override val ironingAngles: String? by option("--ironing-angles", metavar = "DEG,DEG,...", help = "ironing plate: the ironing directions (degrees; 0 = along the slab and its top fill's lines, 90 = across them), spread over the plan as a Latin square; default: the current angle (plan.technique.ironing.angle), +45, and +90 (plymeld's choice, not measured)")
    override val ironingInsets: String? by option("--ironing-insets", metavar = "MM,MM,...", help = "ironing plate: how far inside the slab's edge each zone's ironing stays (mm, on top of half the bore), small to large, spread over the plan as a Latin square; default: the current inset (plan.technique.ironing.inset) x 0.5, 1, 2 (a placeholder spread, not measured)")
}

/** `plymeld calibrate seams`, with `readings` and `values` (SeamReadings.kt). */
class SeamsPlateCommand : PlateCommand("seams", "seams") {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "The seam plate: eight hollow pieces on one slab, each with its outer wall's seam laid one way on every layer (a point seam or plymeld's scarf seam; on a round wall, in the middle of a flat face, in a corner, on a face leaning 30 and 45 degrees), the pieces marked by ticks in the slab's edges and a chamfered corner; the pieces at the filament's print temperature (temperature.print), the slab's first layer at its first-layer temperature. Graded piece by piece for how far away the seam shows; `seams readings` writes the sheet to grade, `seams values` turns the graded sheet into the seam measure's shares (method.quality.seam.scarf_share, .corner_share, method.quality.visibility.under), which decide where plymeld puts every seam."
    init { subcommands(SeamReadingsCommand(), SeamValuesCommand()) }
    override val invokeWithoutSubcommand = true
}

/**
 * `plymeld calibrate <kind> ...`: one subcommand per plate kind, each with its own options, readings, and write-back, so a
 * kind offers only the options it takes.
 */
class CalibrateCommand : com.github.ajalt.clikt.core.CliktCommand(name = "calibrate") {
    init { subcommands(MeltPlateCommand(), FlowPlateCommand(), PressureAdvancePlateCommand(), FirstLayerPlateCommand(), MotionPlateCommand(), FilamentPlateCommand(), IroningPlateCommand(), SeamsPlateCommand()) }
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Make a calibration plate for one filament (--filament, --tool), printed exactly as specified: " +
            "one subcommand per plate, in the order to run them (each plate's results lean on the ones before it): " +
            PlateCommand.KINDS.joinToString("; ") { (k, what) -> "'$k' ($what)" } + ". " +
            "Every plate but the melt and flow plates also checks its g-code against its specification and writes an instructions page. " +
            "Readings and write-backs sit under their plate: `melt values`, `flow values`, `pressure-advance readings`, `pressure-advance values`, `first-layer values`, `motion readings`, `motion values`, `ironing readings`, `ironing values`, `seams readings`, `seams values`."
    override fun run() = Unit
}
