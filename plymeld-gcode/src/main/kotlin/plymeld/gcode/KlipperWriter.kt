package plymeld.gcode

import plymeld.program.Counter
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.LayerStart
import plymeld.program.LiftTo
import plymeld.program.ObjectEnd
import plymeld.program.ObjectStart
import plymeld.program.OverhangSpeed
import plymeld.program.PrintProgram
import java.io.Writer
import java.util.Locale

/**
 * Klipper-flavor g-code writer.
 *
 * Emits the same comment markers QIDIStudio does (`; CHANGE_LAYER`, `; Z_HEIGHT:`,
 * `; LAYER_HEIGHT:`, `; FEATURE:`, `; LINE_WIDTH:`, `; total layer number:`) so one reader
 * checks both slicers' output. Relative extrusion (M83: the start template's, else written after it). A path's line
 * width and acceleration are written when they change (and at a calibration cell's start, which is read alone), not
 * before every path; no travel goes to where the head already is.
 *
 * Extrusion per mm of path = bead cross-section / filament area × flow ratio, bead
 * cross-section = h·(w − h(1 − π/4)) (the same bead model as the walls' spacing).
 *
 * Travel: retract + lift when longer than retraction_minimum_travel, with a wipe when set.
 * Also here: the minimum-layer-time slow-down (never below its minimum speed), graded overhang
 * speeds, arc fitting, exclude-object markers, pauses, and tool changes. The part fan is one
 * fixed speed from a set layer, raised to the filament's overhang fan while a line hangs past the
 * one below (the conditions its overhang share holds at), not on the first layers whose fan is off.
 * NOT YET: bridge fan boost.
 *
 * An exact program ([PrintProgram.exact], a calibration plate) skips all of that: each path prints at
 * its own speeds, acceleration, flow ratio, and retraction, between `; CELL: <label>` and `; CELL END`, and the fan and
 * temperatures change only where the program says (SetFan, SetTemp). No wipe, no arcs.
 *
 * [foreignVars]: the variable table of the slicer the start/end/layer-change templates were
 * imported from (they are written in its placeholder language); used only to render them.
 * [plate]: the settings of the print as a whole (machine, material, spool, and per-layer policies:
 * fan, minimum layer time, templates). [perObject]: each object's own settings by its program name;
 * inside that object's [ObjectStart]..[ObjectEnd] they decide how its paths print (speeds,
 * accelerations, flow, overhang and bridge handling, arcs). Objects not listed use [plate].
 */
class KlipperWriter(private val plate: SliceSettings, private val foreignVars: Map<String, List<String>>, private val version: String,
                    private val perObject: Map<String, SliceSettings> = emptyMap(),
                    /**
                     * An object's settings for the paths one of its tools prints (object name, 0-based tool), where a part prints
                     * with another filament than the object's own: its flow, melt rate, retraction, overhang handling, wipe, and
                     * z hop are that part's filament's (every part printed with the object's first part's). A path of
                     * a tool not listed uses [perObject].
                     */
                    private val perObjectTool: Map<Pair<String, Int>, SliceSettings> = emptyMap(),
                    /** Heat the next tool this long (s, estimated print time) before a tool change; null = no preheat. */
                    private val preheatS: Double? = null,
                    /**
                     * Pressure advance by tool (0-based, seconds; Setup.pressureAdvanceByTool): set when a tool starts
                     * printing (after the start template, after each tool change). A tool not listed gets none
                     * written: the firmware's own value applies (CONTRIBUTING.md: the firmware's by default).
                     */
                    private val pressureAdvance: Map<Int, Double> = emptyMap(),
                    /** Each layer written is reported here, and a cancel seen between layers stops the file (plymeld.program.Progress). */
                    private val progress: plymeld.program.Progress = plymeld.program.Progress.NONE) {
    companion object {
        /** A travel's length (mm) from ([x0], [y0]) to ([x1], [y1]) through [via] (x, y, x, y ... µm; null = straight). */
        fun travelMm(x0: Double, y0: Double, via: LongArray?, x1: Double, y1: Double): Double {
            var len = 0.0; var ax = x0; var ay = y0
            if (via != null) { var k = 0; while (k + 1 < via.size) { val vx = via[k] / 1000.0; val vy = via[k + 1] / 1000.0; len += Math.hypot(vx - ax, vy - ay); ax = vx; ay = vy; k += 2 } }
            return len + Math.hypot(x1 - ax, y1 - ay)
        }

        /**
         * The comment that opens and closes every object's block of a layer (the name follows), whatever the firmware
         * (Klipper's exclude-object markers are written only when it has them): the pre-print check of objects printed
         * together reads each object's lines by them (PlateCheck.together), and the firmware ignores comments.
         */
        const val OBJECT_START = "; object start "
        /** Between an object's name and a curved island's key in the cooling's stack of a layer's paths (never in a name). */
        private const val ISLAND_STACK = "\u0000island "
        /** A filament swapped by hand on a tool: `; FILAMENT_SWAP: T0 filament 1 -> 5 (...)`; from here that tool's lines push filament 5. */
        const val FILAMENT_SWAP = "; FILAMENT_SWAP:"
        val FILAMENT_SWAP_LINE = Regex("""^; FILAMENT_SWAP: T(\d+) filament (\d+) -> (\d+)""")
        const val OBJECT_END = "; object end "
    }
    /** Settings in force at this point of the program: the plate's, or the current object's. */
    private var s = plate
    /** Which settings each path prints with (filled per program; the minimum-layer-time estimate needs it ahead of time). */
    private val pathSettings = java.util.IdentityHashMap<Extrude, SliceSettings>()
    /** The filament's cross-section (mm2) for [s]: each filament's own diameter (the plate's for every tool). */
    private fun filamentArea(s: SliceSettings) = Math.PI * (s.filamentDiameterMm / 2) * (s.filamentDiameterMm / 2)
    private val filamentArea get() = filamentArea(s)
    private val dialect = plate.dialect
    /** What this machine accepts, with evidence; every emitted line is checked against it. */
    private val machine = plate.machineDescription
    private val minTravelMm = plate.retractMinTravelMm
    /** string_risk and restart_starved of the last program written (StringRisk); null before a write, or for an exact program. */
    var strings: StringRisk.Result? = null; private set
    private val fanFrom = plate.fanOffFirstLayers
    private val fanPct = plate.fanMinPct

    /**
     * Writes a [PrintProgram]. Events decide the structure: [LayerStart] gives the layer markers,
     * the layer-change template, and per-layer policies (fan, minimum layer time — the time between
     * two layer starts); [ObjectStart]/[ObjectEnd] become exclude-object markers. The lift at the
     * start of a layer is written before the next thing that moves after the layer start.
     */
    fun write(given: PrintProgram, target: Writer) {
        // plymeld's own prime line, next to the first object (PrimeLine), in place of a start template's
        val primed = PrimeLine.insert(given, plate)
        // travels around the layer's other islands and inside their own (plan.mechanism.motion.avoid_crossing; TravelRouter), not for an exact program
        val routed = if (s.avoidCrossing && !given.exact) TravelRouter.routed(primed.program, s.detourMarginUm ?: TravelRouter.MARGIN_UM,
            s.detourMaxRatio ?: TravelRouter.MAX_DETOUR_RATIO, s.detourMaxExtraUm ?: TravelRouter.MAX_DETOUR_EXTRA_UM, s.detourInsetUm,
            s.detourSpeckMaxRatio ?: TravelRouter.SPECK_MAX_RATIO, s.detourSpeckMaxExtraUm ?: TravelRouter.SPECK_MAX_EXTRA_UM) else null
        val program = routed?.program ?: primed.program
        // each layer's comb regions (by its LayerStart's step index), the router's, for a travel planned again from where a wipe
        // left the head (below, at the travel)
        val combOf: Map<Int, Lazy<TravelRouter.CombLayer>> = if (routed == null) emptyMap() else
            program.steps.indices.filter { program.steps[it] is LayerStart }.withIndex().associate { (k, i) -> i to routed.combs[k] }
        val detourRatio = s.detourMaxRatio ?: TravelRouter.MAX_DETOUR_RATIO
        val detourExtraUm = s.detourMaxExtraUm ?: TravelRouter.MAX_DETOUR_EXTRA_UM
        val out = CommandCheck(target, machine)
        // printed exactly as specified (calibration): every path carries its own values, and none of the
        // writer's own rules below (layer fan, minimum layer time, melt cap, overhangs, arcs, wipe) apply
        val exact = program.exact
        if (exact) program.steps.filterIsInstance<Extrude>().firstOrNull { it.exact == null || it.widths != null }?.let {
            error("an exact program has a '${it.feature}' path without its own exact values (or with variable widths)")
        }
        // the stringing and restart measures of the program as written (StringRisk); not for an exact program (its own retractions)
        strings = if (exact) null else StringRisk.measure(program, minTravelMm, plate.restartLagMm, plate.detourMarginUm ?: TravelRouter.MARGIN_UM)
        val obj = program.objects.firstOrNull()
        val exclude = s.excludeObject && obj != null
        val layerStarts = program.layerStarts
        val layersWritten = progress.Counter("g-code", layerStarts.size)
        // highest point printed: layer tops when there are layers, and any path's own Z (programs need not be layered)
        val maxZ = maxOf(layerStarts.maxOfOrNull { it.zUm } ?: 0L, program.steps.filterIsInstance<Extrude>().maxOfOrNull { it.zUm } ?: 0L) / 1000.0
        // the tool the start template selects: the one the program prints with before any tool change of its own
        // (a program that changes tools first starts from the tool it changes from)
        val initialTool = program.steps.firstOrNull { it is Extrude || it is plymeld.program.ToolChange }
            ?.let { if (it is Extrude) it.tool else (it as plymeld.program.ToolChange).fromTool } ?: 0
        val vars = templateVars(layerStarts.size, maxZ, initialTool)
        // each layer's extrusions, for the minimum-layer-time factor
        val layerPaths = HashMap<LayerStart, MutableList<Extrude>>()
        s = plate
        pathSettings.clear()
        // each extrusion's object (null: outside any object, e.g. the purge tower), for the cooling gap; a curved island's
        // layer is a stack of its own (the object's name and the island's), whose layer above is the island's next, however
        // many flat layers come between (read as the object's, each small island layer was slowed to the minimum
        // layer time as if its next landed straight on it; the 3DBenchy with a bow island took 2.2 min longer)
        val pathObject = HashMap<Extrude, String?>()
        run {
            // a one-object program has no object markers: its object from the start
            var cur: MutableList<Extrude>? = null; var osName: String? = program.objects.singleOrNull()?.name; var os = osName?.let { perObject[it] } ?: plate
            var island: String? = null
            for (st in program.steps) when (st) {
                is LayerStart -> { cur = ArrayList(); layerPaths[st] = cur; island = st.island }
                is ObjectStart -> { os = perObject[st.name] ?: plate; osName = st.name }
                is ObjectEnd -> { os = plate; osName = null }
                is Extrude -> { cur?.add(st); pathSettings[st] = osName?.let { perObjectTool[it to st.tool] } ?: os; pathObject[st] = osName?.let { if (island != null) it + ISLAND_STACK + island else it } }
                else -> Unit
            }
        }
        // Minimum layer time per layer start (slowOf): if a layer would print faster, its extrusion speeds are scaled
        // down, never below the minimum speed; travel is not slowed. Objects printed together with layers of different
        // heights put one object's layer between two of another's, and that layer's time cools the other:
        // for each object in a layer, the time printed between its paths here and its paths in its next layer (this
        // layer's rest after them, the layers between, the next layer's paths before them) counts, at the unslowed
        // speeds (a lower bound, so the slow-down never falls short); each object's paths in the layer are slowed by its
        // own factor (a pin's 5 mm beside a base's 600 s does not slow the base). An object with no next layer counts as
        // if one followed at once (its top layer slowed as before).
        // Each object's factors: [hidden lines, visible lines] (VISIBLE). plan.mechanism.cooling.slowdown.style = "even" scales every
        // line by one factor, as before; "hidden_first" (a gap found reviewing a slice: the pin heads'
        // outer walls slowed to a different speed every layer while their inner walls kept 100-260 mm/s) takes the time from
        // the hidden lines first, each down to the minimum speed, and slows the visible lines only for what is still missing.
        val slowOf = HashMap<LayerStart, Map<String?, DoubleArray>>()
        // the seconds a layer start still falls short of its objects' minimum layer time with its factors applied (the most
        // of its objects'): with plan.mechanism.cooling.dwell.enabled the head waits that long beside the layer before the next
        val dwellOf = HashMap<LayerStart, Double>()
        if (s.slowDownForCooling && !exact) {
            class Timed(val extrudeS: DoubleArray, val travelS: DoubleArray, val obj: List<String?>, val speed: DoubleArray, val visible: BooleanArray) {
                val total get() = extrudeS.sum() + travelS.sum()
            }
            val timed = layerStarts.map { st ->
                val ps = layerPaths.getValue(st); val fl = st.objectLayer == 0
                val ex = DoubleArray(ps.size); val tr = DoubleArray(ps.size); val sp = DoubleArray(ps.size)
                var px = Double.NaN; var py = 0.0
                for ((i, p) in ps.withIndex()) {
                    val sx = p.xy[0] / 1000.0; val sy = p.xy[1] / 1000.0
                    // the travel as the head goes: through its detour's points where it has one (TravelRouter), else straight
                    if (!px.isNaN()) tr[i] = travelMm(px, py, p.via, sx, sy) / s.travelSpeed
                    var len = 0.0; val m = p.xy.size / 2; val count = if (p.closed) m else m - 1
                    var x = sx; var y = sy
                    for (k in 1..count) { val idx = k % m; val nx = p.xy[2 * idx] / 1000.0; val ny = p.xy[2 * idx + 1] / 1000.0; len += Math.hypot(nx - x, ny - y); x = nx; y = ny }
                    sp[i] = pathSpeed(p, fl); ex[i] = len / sp[i]; px = x; py = y
                }
                Timed(ex, tr, ps.map { pathObject[it] }, sp, BooleanArray(ps.size) { ps[it].feature in VISIBLE })
            }
            // the next layer start holding each object's paths, from each layer start
            val nextOf = HashMap<String?, Int>()
            val next = Array(timed.size) { HashMap<String?, Int>() }
            for (i in timed.indices.reversed()) { for (o in timed[i].obj.toSet()) { nextOf[o]?.let { next[i][o] = it }; nextOf[o] = i } }
            for ((i, t) in timed.withIndex()) {
                val slow = HashMap<String?, DoubleArray>()
                for (o in t.obj.toSet()) {
                    val from = t.obj.indexOf(o)
                    var eoa = 0.0; var gap = 0.0
                    for (k in from until t.obj.size) { eoa += t.extrudeS[k]; gap += t.travelS[k] }
                    val j = next[i][o]
                    if (j != null) {
                        for (k in i + 1 until j) gap += timed[k].total
                        val upTo = timed[j].obj.indexOf(o)
                        for (k in 0 until upTo) gap += timed[j].extrudeS[k] + timed[j].travelS[k]
                    }
                    // each object's own minimum layer time (plan.mechanism.cooling.layer.time.min over its filament's)
                    val target = (o?.let { perObject[it.substringBefore(ISLAND_STACK)] } ?: plate).slowDownLayerTimeS
                    slow[o] = if (s.coolingSlowdown == "even") slowFactor(eoa, gap, target).let { doubleArrayOf(it, it) }
                    // hidden_first: what belongs to no object (the prime tower's purge) gets no factor of its own (with one,
                    // it was slowed for its own minimum as well, and the layer overshot: the C5 two-color Benchy's top, 10.4
                    // s a layer against 8 s); it is hidden, so it is part of each object's hidden lines, the
                    // first to give time, and takes the objects' factor below
                    else if (o == null) doubleArrayOf(1.0, 1.0) else {
                        // the object's own paths in the layer from its first, hidden and visible, and the tower's paths
                        // among them (hidden): their lengths and speeds
                        val mine = (from until t.obj.size).filter { t.obj[it] == o || t.obj[it] == null }
                        hiddenFirst(eoa + gap, target, mine.filter { !t.visible[it] }.map { t.extrudeS[it] * t.speed[it] to t.speed[it] },
                            mine.filter { t.visible[it] }.map { t.extrudeS[it] * t.speed[it] to t.speed[it] })
                    }
                    // what the factors leave short (the minimum speed binding), for the dwell: the object's own paths
                    if (o != null && s.coolingDwell) {
                        val f = slow.getValue(o)
                        val floor = s.slowDownMinSpeed
                        var added = 0.0
                        // (hidden_first: the tower's paths too, slowed by the objects' hidden factor)
                        for (k in from until t.obj.size) if (t.obj[k] == o || (t.obj[k] == null && s.coolingSlowdown != "even")) {
                            val v = t.speed[k]; val len = t.extrudeS[k] * v
                            added += len / Math.max(Math.min(floor, v), v * (if (t.visible[k]) f[1] else f[0])) - t.extrudeS[k]
                        }
                        val short = target - (eoa + gap + added)
                        if (short > (dwellOf[layerStarts[i]] ?: 0.0)) dwellOf[layerStarts[i]] = short
                    }
                }
                // hidden_first: the tower's paths slowed as the objects' hidden lines (the most slowed of them, where several
                // objects share the layer: their minimums are then met, a little over for the others)
                if (s.coolingSlowdown != "even" && null in slow) {
                    val f = slow.filterKeys { it != null }.values.minOfOrNull { it[0] } ?: 1.0
                    slow[null] = doubleArrayOf(f, f)
                }
                slowOf[layerStarts[i]] = slow
            }
        }
        // Preheat (hardware.tool.heat.time): walk back from each tool change over the extrusions since the
        // previous change, adding each one's estimated time (length / speed; travel left out, so the
        // preheat lands EARLIER than needed, never later), and heat the next tool where the sum
        // reaches the preheat time. Never before the previous change: that one may have just set
        // this very tool to its idle temperature.
        val preheatBefore = HashMap<Int, String>()
        if (preheatS != null && preheatS > 0) {
            val stepsList = program.steps
            val firstLayer = BooleanArray(stepsList.size)
            run { var fl = false; for ((k, st) in stepsList.withIndex()) { if (st is LayerStart) fl = st.objectLayer == 0; firstLayer[k] = fl } }
            fun seconds(p: Extrude, fl: Boolean): Double {
                var len = 0.0; val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
                for (k in 0 until segs) { val j = (k + 1) % m; len += Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) }
                return len / 1000.0 / pathSpeed(p, fl)
            }
            var prevChange = -1
            for ((ci, st) in stepsList.withIndex()) {
                if (st !is plymeld.program.ToolChange) continue
                val temp = st.tempC
                if (temp != null) {
                    var acc = 0.0; var at = ci; var j = ci - 1
                    while (j > prevChange && acc < preheatS) { val sj = stepsList[j]; if (sj is Extrude) { acc += seconds(sj, firstLayer[j]); at = j }; j-- }
                    if (at < ci) preheatBefore[at] = (preheatBefore[at] ?: "") + "M104 S$temp T${st.tool} ; preheat, ${"%.0f".format(java.util.Locale.ROOT, acc)} s of printing before the change\n"
                }
                prevChange = ci
            }
        }
        out.write("; generated by plymeld $version\n")
        out.write("; total layer number: ${layerStarts.size}\n")
        if (exclude) for (o in program.objects) {
            val pts = (0 until o.footprint.size / 2).joinToString(",", "[", "]") { "[${n(o.footprint[2 * it])},${n(o.footprint[2 * it + 1])}]" }
            out.write("EXCLUDE_OBJECT_DEFINE NAME=${o.name} CENTER=${n(o.centerX)},${n(o.centerY)} POLYGON=$pts\n")
        }
        val start = Template.render(s.startGcode, vars)
        out.write(start + "\n")
        // plymeld writes relative extrusion: a start template that does not set it (M83) gets it here. Firmware starts in absolute
        // E, so without it every E value would be read as a position (the sample machine's start, "G28" alone; the
        // prediction read the file the same way and said "filament -1 mm")
        if (!RELATIVE_E.containsMatchIn(start)) out.write("M83 ; relative extrusion: plymeld writes E as amounts\n")
        out.write("; prime line: ${primed.note}\n")
        // the nozzle temperature last set on each tool (the start template's own M104 / M109 first): an object's first layer
        // at its first-layer temperature, the layers after at its print temperature, each object at its own (the
        // print temperature was never set after the first layer). A program that changes tools sets its tools' temperatures
        // with its tool changes; one printed exactly as specified sets its own.
        val toolTemp = HashMap<Int, Int>()
        for (m in Regex("(?m)^M10[49] S(\\d+)(?: T(\\d+))?").findAll(start)) toolTemp[m.groupValues[2].toIntOrNull() ?: initialTool] = m.groupValues[1].toInt()
        val ownTemps = !exact && program.steps.none { it is plymeld.program.ToolChange }
        // the tool in hand, and each tool's temperature as last set (its tool changes' too), for the restore after a pause
        var toolInHand = initialTool
        val setTemp = HashMap<Int, Int>(toolTemp)
        // the objects printed with an arched lifted bridge (an object starting in a layer marked as one's): their bridge's own
        // layers at the bridge temperature when set (plan.technique.bridge.temperature: arches printed
        // best cooler; the bridge's layers only, below)
        val bridgeObjects = HashSet<String>()
        run { var bridgeNow = false; for (st in program.steps) when (st) { is LayerStart -> bridgeNow = st.archedBridge; is ObjectStart -> if (bridgeNow) bridgeObjects += st.name; else -> {} } }
        var objectNow: String? = program.objects.singleOrNull()?.name
        // pressure advance for the tool the start template selected (Klipper: the active extruder)
        fun pressureAdvanceFor(tool: Int) { pressureAdvance[tool]?.let { out.write("SET_PRESSURE_ADVANCE ADVANCE=${e(it)}\n") } }
        pressureAdvanceFor(initialTool)
        var x = Double.NaN; var y = Double.NaN; var z = 0.0
        // what every travel must clear: the plastic printed so far, under the tip, its cone, and the head (Clearance)
        // the strictest head of every tool on the plate (the plate's first object's tool alone): the narrowest cone,
        // the lowest nozzle, the furthest reach each way
        val heads = listOf(plate) + perObject.values + perObjectTool.values
        val reaches = heads.mapNotNull { it.headReachUm }
        val clearance = Clearance(plate.bedUm?.let { b -> DoubleArray(4) { b[it] / 1000.0 } }, plate.nozzleDiameterUm / 1000.0, heads.mapNotNull { it.nozzleAngleDeg }.minOrNull(),
            heads.mapNotNull { it.nozzleHeightUm }.minOrNull()?.let { it / 1000.0 }, if (reaches.isEmpty()) null else DoubleArray(4) { i -> reaches.maxOf { it[i] } / 1000.0 })
        var retracted = false
        var lastPath: Extrude? = null
        var feature = ""
        // the line width and acceleration last written: a path that keeps them writes neither again (a scarf seam's ramp, loop
        // and down pass are three paths, each had its own); forgotten at every step that is not a path (a layer change, an
        // object, a tool change, a template: what they write, and where a reader of one block starts, is not known here)
        var widthWritten: String? = null; var accelWritten: Long? = null
        var lz = 0.0
        var first = false      // first-layer speeds apply only after a LayerStart with index 0
        var slowLayer: Map<String?, DoubleArray>? = null   // this layer start's slow-down per object, [hidden, visible] (slowOf)
        var liftPending = false
        var fanOn = false
        var fanNow = 0.0       // the part fan as last commanded, %
        fun fanTo(pct: Double) { if (pct != fanNow) { out.write("M106 S${Math.round(255 * pct / 100)}\n"); fanNow = pct } }
        // retraction length: the next exact path's own when it has one (the travel into it), else the filament's;
        // what was retracted is restored (an exact program only: other programs restore the filament's length)
        var nextRetract: Double? = null
        var retractedMm = 0.0
        // an arched lifted bridge's layer (LayerStart.archedBridge): its own speeds when the object sets them (plan.technique.bridge.print.speed, every
        // extrusion unless the path gives speeds per segment, as the first layer over the hollow and the ramp after it do;
        // plan.technique.bridge.travel.speed, the most any head move without one may take: travels, lifts, drops. Not
        // retractions and their restores: the head
        // stays still in them, and capped at 8 mm/s a retraction pulled the filament back too slowly to break it off clean)
        var bridgeLayer = false
        var printTempLayer = false
        fun moveF(v: Double): Double = if (bridgeLayer) s.bridgeMoveSpeed?.let { Math.min(v, it) } ?: v else v
        /** Whether [lp], the path just laid, is a waved bridge's bead (on an arched bridge's layer): its own retraction and wipe apply. */
        fun leavingBead(lp: Extrude?) = bridgeLayer && lp != null && lp.feature.startsWith("Wave overhang")
        fun retract() {
            // leaving a waved bridge's bead: the object's own retraction and wipe when it sets them (plan.technique.bridge.wave.retract
            // / wave.wipe), wherever the retraction falls (a lift, a travel)
            val lp = lastPath
            val bead = leavingBead(lp)
            val len = nextRetract ?: (if (bead) s.bridgeWaveRetractMm else null) ?: s.retractMm
            if (bead && lp != null && ((s.bridgeWaveWipe ?: s.wipe)) && !exact && s.wipeDistanceMm > 0 && !x.isNaN()) {
                // retrace the bead backwards while retracting (retract_before_wipe of it first, the rest spread over the wipe)
                val pre = len * s.retractBeforeWipe
                if (pre > 0) out.write("G1 E-${n(pre)} F${f(s.retractSpeed)}\n")
                val pts = wipePoints(lp, x, y, s.wipeDistanceMm)
                var wx = x; var wy = y
                val total = pts.windowed(2, 2).sumOf { (a, b) -> Math.hypot(a - wx, b - wy).also { wx = a; wy = b } }
                wx = x; wy = y
                var k = 0
                while (k + 1 < pts.size) {
                    val ax = pts[k]; val ay = pts[k + 1]
                    val seg = Math.hypot(ax - wx, ay - wy)
                    val de = if (total > 0) (len - pre) * seg / total else 0.0
                    out.write("G1 X${n(ax)} Y${n(ay)} E-${e(de)} F${f(s.wipeSpeed)}\n")
                    wx = ax; wy = ay; k += 2
                }
                if (total == 0.0 && len - pre > 0) out.write("G1 E-${n(len - pre)} F${f(s.retractSpeed)}\n")
                x = wx; y = wy
            } else out.write("G1 E-${n(len)} F${f(s.retractSpeed)}\n")
            retracted = true; retractedMm = len
        }
        fun flushLift() {
            if (!liftPending) return
            liftPending = false
            if (!retracted) retract()
            // only ever RISE here: when the head is above this layer (arriving from a taller finished
            // object), the next travel moves sideways at that height first and descends after
            val hop = lz + s.zHopUm / 1000.0
            if (z < hop - 1e-9) { out.write("G1 Z${n(hop)} F${f(moveF(s.travelSpeed))}\n"); z = hop }
        }
        /** Retracted, lifted clear of what is printed on the way, to ([px], [py]), waiting [dwellS] there (a Park step). */
        fun park(px: Double, py: Double, dwellS: Double) {
            flushLift()
            if (!retracted) retract()
            // clear of what is printed on the way there (Clearance; from an unknown place, of everything printed)
            val tip = if (x.isNaN()) maxOf(lz, clearance.highest) else clearance.need(x, y, px, py, lz)
            val hop = tip + s.zHopUm / 1000.0
            if (z.isNaN() || z < hop - 1e-9) { out.write("G1 Z${n(hop)} F${f(s.travelSpeed)}\n"); z = hop }
            out.write("G1 X${n(px)} Y${n(py)} F${f(s.travelSpeed)}\n"); x = px; y = py
            if (dwellS > 0) out.write("G4 P${Math.round(dwellS * 1000)}\n")
        }
        /**
         * The cooling dwell after layer start [ls] (plan.mechanism.cooling.dwell.enabled; a gap found reviewing a
         * slice: a pin's crown printed in 0.1-5 s a layer at the minimum speed, no wait): the nozzle parked where the head's
         * edge just clears the layer's printed area (the head's reach from the nozzle, hardware.tool.head.size / head.nozzle.x/y),
         * on the nearest side that keeps it on the bed, waiting what the layer still falls short; without the head's size or
         * room on the bed, a comment says so and nothing waits (a hot nozzle held over the part would warm it).
         */
        fun coolingDwell(ls: LayerStart) {
            val shortS = dwellOf[ls] ?: return
            if (shortS < 0.05) return
            // lift (plan.mechanism.cooling.dwell.style: kept clear of the model, so ooze is not
            // trailed over it): retracted, straight up
            // where the layer ended, within the machine's height, then the wait; the next layer comes back down on its own
            if (s.coolingDwellStyle == "lift") {
                flushLift()
                if (!retracted) retract()
                val top = s.maxPrintHeightMm
                val up = minOf((if (z.isNaN()) lz else z) + s.coolingDwellLiftUm / 1000.0, top)
                out.write("; cooling dwell: the layer ${"%.1f".format(java.util.Locale.ROOT, shortS)} s short of its minimum time at the minimum speed, the head lifted\n")
                if (z.isNaN() || up > z + 1e-9) { out.write("G1 Z${n(up)} F${f(moveF(s.travelSpeed))}\n"); z = up }
                out.write("G4 P${Math.round(shortS * 1000)}\n")
                return
            }
            val ps = layerPaths[ls] ?: return
            if (ps.isEmpty()) return
            var x0 = Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y1 = -Double.MAX_VALUE
            for (p in ps) for (k in 0 until p.xy.size / 2) { val px = p.xy[2 * k] / 1000.0; val py = p.xy[2 * k + 1] / 1000.0
                x0 = minOf(x0, px); x1 = maxOf(x1, px); y0 = minOf(y0, py); y1 = maxOf(y1, py) }
            val bed = plate.bedUm?.let { b -> DoubleArray(4) { b[it] / 1000.0 } }
            val reach = if (reaches.isEmpty()) null else DoubleArray(4) { i -> reaches.maxOf { it[i] } / 1000.0 }
            if (bed == null || reach == null) { out.write("; cooling dwell: ${"%.1f".format(java.util.Locale.ROOT, shortS)} s short, no wait: the head's size or the bed is not set\n"); return }
            val cx = if (x.isNaN()) (x0 + x1) / 2 else x.coerceIn(bed[0], bed[2]); val cy = if (y.isNaN()) (y0 + y1) / 2 else y.coerceIn(bed[1], bed[3])
            // reach: [-X, +X, -Y, +Y] from the nozzle; the nozzle where that edge of the head meets the printed area's side
            val spots = listOf(x1 + reach[0] to cy, x0 - reach[1] to cy, cx to y1 + reach[2], cx to y0 - reach[3])
                .filter { (px, py) -> px in bed[0]..bed[2] && py in bed[1]..bed[3] }
            val spot = spots.minByOrNull { (px, py) -> Math.hypot(px - cx, py - cy) }
            if (spot == null) { out.write("; cooling dwell: ${"%.1f".format(java.util.Locale.ROOT, shortS)} s short, no wait: no room on the bed beside the layer for the head\n"); return }
            out.write("; cooling dwell: the layer ${"%.1f".format(java.util.Locale.ROOT, shortS)} s short of its minimum time at the minimum speed\n")
            park(spot.first, spot.second, shortS)
        }
        /**
         * Whether the head retracts after step [i], a path ending at ([ex], [ey]) mm: the next step that moves is a travel that
         * retracts, a layer change, a tool change, or a park. False where that is not known (custom g-code, a pause, a lift, a
         * filament swap) and after the last path (the end template's moves follow it): coasting ([SliceSettings.coastMm]) lays a
         * path's end with no filament only before a retraction.
         */
        fun retractsAfter(i: Int, ex: Double, ey: Double): Boolean {
            for (j in i + 1 until program.steps.size) when (val st = program.steps[j]) {
                is ObjectEnd, is ObjectStart, is plymeld.program.SetFan -> {}
                is plymeld.program.SetTemp -> if (st.wait) return true
                is Extrude -> {
                    if (st.exact != null) return false
                    val d = Math.hypot(st.xy[0] / 1000.0 - ex, st.xy[1] / 1000.0 - ey)
                    return (d > minTravelMm || st.via != null) && st.retractBefore && !st.hops(d)
                }
                is LayerStart, is plymeld.program.ToolChange, is plymeld.program.Park -> return true
                else -> return false
            }
            return false
        }
        var lastLayerStart: LayerStart? = null
        var combNow: Lazy<TravelRouter.CombLayer>? = null; var combBelow: Lazy<TravelRouter.CombLayer>? = null; var firstInLayer = false
        for ((stepIndex, step) in program.steps.withIndex()) { preheatBefore[stepIndex]?.let { out.write(it) }
            if (step !is Extrude) { widthWritten = null; accelWritten = null }
            if (step is LayerStart) { lastLayerStart?.let { if (s.coolingDwell) coolingDwell(it) }; lastLayerStart = step; combBelow = combNow; combNow = combOf[stepIndex]; firstInLayer = true }
            when (step) {
            // every object's block is marked by a comment whatever the firmware (objects printed together have their layers
            // merged by height, each at its own heights, so the pre-print check reads each object's lines by these: PlateCheck)
            is ObjectEnd -> { flushLift(); if (exclude) out.write("EXCLUDE_OBJECT_END NAME=${step.name}\n"); out.write(OBJECT_END + step.name + "\n"); s = plate }
            // the feature is forgotten at each object's start so its first extrusion names its own (PlateCheck reads each
            // block alone: a quad tube after an elephant ending on an outer wall had its first ring's wall unnamed)
            is ObjectStart -> { out.write(OBJECT_START + step.name + "\n"); if (exclude) out.write("EXCLUDE_OBJECT_START NAME=${step.name}\n"); s = perObject[step.name] ?: plate; objectNow = step.name; feature = "" }
            is plymeld.program.ToolChange -> {
                // The tool-changer sequence observed in the Creator 5's own slicer output
                // (as observed in Flash Studio's output): the old tool to its idle temperature, the swap,
                // wait for the new tool. Retracted first; afterwards the head's position is unknown,
                // so the next travel rises first (see Extrude).
                flushLift()
                if (!retracted) retract()
                if (step.fromTool != null && step.fromIdleC != null) { out.write("M104 S${step.fromIdleC} T${step.fromTool}\n"); setTemp[step.fromTool!!] = step.fromIdleC!! }
                out.write("T${step.tool}\n")
                toolInHand = step.tool
                step.tempC?.let { out.write("M109 S$it T${step.tool}\n"); setTemp[step.tool] = it }
                pressureAdvanceFor(step.tool)
                x = Double.NaN; y = Double.NaN; z = Double.NaN
            }
            is plymeld.program.FilamentSwap -> {
                // swapped by hand at the pause before it (Pauses.placeSwaps): noted for the file's filament accounting (the
                // header and the pre-print check read the note: FILAMENT_SWAP), the new filament brought to its temperature
                flushLift()
                out.write("$FILAMENT_SWAP T${step.tool} filament ${step.fromId} -> ${step.toId} (${step.fromSpool} -> ${step.toSpool}, swapped by hand at the pause)\n")
                step.tempC?.let { if (!retracted) retract(); out.write("M109 S$it T${step.tool}\n"); toolTemp[step.tool] = it; setTemp[step.tool] = it }
            }
            is plymeld.program.Pause -> {
                flushLift()
                val cmd = s.pauseGcode ?: error("a pause is asked for (${step.reason}) but the machine has no pause command (machine_pause_gcode)")
                out.write("; PAUSE: ${step.reason}\n" + Template.render(cmd, vars) + "\n")
                // the temperatures back as they were, the tool in hand waited for: a printer may turn its nozzles off while
                // paused (a Creator 5 set every nozzle to 0 and kept the bed), and printing would resume with a cold nozzle
                // where no tool change follows to heat it. Where the printer kept them, the wait ends at once.
                for ((t, c) in setTemp.toSortedMap()) if (t != toolInHand) out.write("M104 S$c T$t\n")
                setTemp[toolInHand]?.let { out.write("M109 S$it T$toolInHand\n") }
            }
            is LiftTo -> {
                flushLift()
                val tz = step.zUm / 1000.0
                if (z < tz - 1e-9) {
                    if (!retracted) retract()
                    out.write("G1 Z${n(tz)} F${f(moveF(s.travelSpeed))}\n"); z = tz
                }
            }
            is LayerStart -> {
                flushLift()
                layersWritten.tick()
                lz = step.zUm / 1000.0
                bridgeLayer = step.archedBridge
                printTempLayer = step.printTemp
                out.write(dialect.layerChange(n(lz), n(step.heightUm / 1000.0)))
                step.island?.let { out.write("${LayerStart.ISLAND_MARK}$it\n") }
                val layerVars = { name: String ->
                    when (name) {
                        "layer_z" -> Template.num(lz)
                        "layer_number" -> Template.num(step.index.toLong())
                        // the name other slicers' templates use (investigation)
                        "layer_num" -> Template.num(step.index.toLong())
                        else -> vars(name)
                    }
                }
                s.layerChangeGcode.takeIf { it.isNotBlank() }?.let { out.write(Template.render(it, layerVars) + "\n") }
                // part fan: off for an object's first layers (a new object after one with the fan on
                // turns it off again), on from layer fanFrom of each object
                // (an exact program sets its fan with SetFan only)
                if (!exact && step.objectLayer == 0 && fanOn) { out.write("M106 S0\n"); fanOn = false; fanNow = 0.0 }
                // past the fan-off layers: the plate's fan here, each object's own from its first line (layerFan)
                if (!exact && step.objectLayer == fanFrom) { out.write("M106 S${Math.round(255 * fanPct / 100)}\n"); fanOn = true; fanNow = fanPct }
                first = step.objectLayer == 0
                // Minimum layer time (slowOf, worked out above, per object; 1 = not slowed)
                slowLayer = slowOf[step]
                liftPending = true
            }
            is plymeld.program.SetTemp -> {
                if (step.wait && !retracted) { flushLift(); if (!retracted) retract() }   // a wait holds the nozzle still: retracted, against oozing
                out.write("${if (step.wait) "M109" else "M104"} S${step.tempC} T${step.tool}\n")
            }
            is plymeld.program.SetFan -> fanTo(step.pct)
            is plymeld.program.Custom -> { flushLift(); out.write("; ${step.label}\n" + Template.render(step.gcode, vars) + "\n") }
            // lift clear, retracted, travel there, and wait
            is plymeld.program.Park -> park(step.xUm / 1000.0, step.yUm / 1000.0, step.dwellS)
            is Extrude -> {
                // the settings of this path's tool in its object (a part printing with another filament): perObjectTool
                objectNow?.let { o -> s = perObjectTool[o to step.tool] ?: perObject[o] ?: plate }
                nextRetract = step.exact?.retractMm
                flushLift()
                val p = step
                if (ownTemps) {
                    // this object's temperature for this layer: waited for on a first layer (adhesion), set on the way after
                    // an arched bridge's top layer printed as usual (LayerStart.printTemp): the print temperature, waited for when it
                    // is a rise (a thin top layer would otherwise be laid while the nozzle heats)
                    // the bridge temperature on the arched bridge's own layers only (it was the
                    // whole object's from its second layer, and the helix's bottom ring, printed cooler, broke off): waited for both
                    // ways, so the nozzle is already cooler when the first strand goes over air and hot again for the wall above
                    val bridgeTemp = s.bridgeTempC?.takeIf { objectNow in bridgeObjects }
                    val want = if (first) s.nozzleTempFirstC else if (printTempLayer) s.nozzleTempC else if (bridgeLayer) bridgeTemp ?: s.nozzleTempC else s.nozzleTempC
                    if (toolTemp[p.tool] != want) {
                        val wait = first || (printTempLayer && want > (toolTemp[p.tool] ?: 0)) || bridgeTemp != null
                        if (wait) { if (!retracted) retract(); out.write("M109 S$want T${p.tool}\n") } else out.write("M104 S$want T${p.tool}\n")
                        toolTemp[p.tool] = want; setTemp[p.tool] = want
                    }
                }
                // the path's highest Z (the lifts clear it); a curved path starts at its first vertex's own Z
                val pz = p.zUm / 1000.0
                val startZ = (p.zs?.get(0) ?: p.zUm) / 1000.0
                // a calibration cell is read alone (its block from its CELL line): it states its width and acceleration
                p.exact?.let { out.write("; CELL: ${it.label}\n"); widthWritten = null; accelWritten = null }
                if (p.feature != feature) { out.write(dialect.feature(p.feature)); feature = p.feature; widthWritten = null }
                n(p.widthUm / 1000.0).let { wt -> if (wt != widthWritten) { out.write(dialect.width(wt)); widthWritten = wt } }
                val sx = p.xy[0] / 1000.0; val sy = p.xy[1] / 1000.0
                // travel
                val d = if (x.isNaN()) Double.POSITIVE_INFINITY else Math.hypot(sx - x, sy - y)
                val hiddenTravel = !p.retractBefore
                // where a wipe took the head (the path's end first, then each wipe point), when it did
                var wiped: List<Double>? = null
                // a speck's hop (Extrude.hops): lifted over the wall it crosses below, not retracted
                val speckHop = p.hops(d)
                if ((d > minTravelMm || p.via != null) && !hiddenTravel && !speckHop) {
                    if (!retracted) {
                        val lp = lastPath
                        val len = nextRetract ?: s.retractMm
                        if (s.wipe && !exact && lp != null && s.wipeDistanceMm > 0 && !leavingBead(lp)) {
                            // Wipe: retrace the last path backwards while retracting (retract_before_wipe
                            // of the retraction happens first, the rest spread over the wipe).
                            retractedMm = len
                            val pre = len * s.retractBeforeWipe
                            if (pre > 0) out.write("G1 E-${n(pre)} F${f(s.retractSpeed)}\n")
                            val pts = wipePoints(lp, x, y, s.wipeDistanceMm)
                            var wx = x; var wy = y
                            val total = pts.windowed(2, 2).sumOf { (a, b) -> Math.hypot(a - wx, b - wy).also { wx = a; wy = b } }
                            wx = x; wy = y
                            var k2 = 0
                            while (k2 + 1 < pts.size) {
                                val ax = pts[k2]; val ay = pts[k2 + 1]
                                val seg = Math.hypot(ax - wx, ay - wy)
                                val de = if (total > 0) (len - pre) * seg / total else 0.0
                                out.write("G1 X${n(ax)} Y${n(ay)} E-${e(de)} F${f(s.wipeSpeed)}\n")
                                wx = ax; wy = ay; k2 += 2
                            }
                            if (total == 0.0 && len - pre > 0) out.write("G1 E-${n(len - pre)} F${f(s.retractSpeed)}\n")
                            if (pts.isNotEmpty()) wiped = listOf(x, y) + pts
                            x = wx; y = wy
                        } else retract()
                        retracted = true
                    }
                }
                // the travel's route: TravelRouter planned it from the path's end, and a wipe left the head up to its distance back
                // along the path, so it is planned again from there (on the regression Benchy, 77 of the 123 travels
                // still crossing their own island's outer wall were a wipe's: its end cut back across the wall it had followed)
                val w = wiped; val cl = combNow
                val via = if (w == null || cl == null) p.via else replanAfterWipe(cl, combBelow, firstInLayer, w, sx, sy, p, lastPath, detourRatio, detourExtraUm)
                firstInLayer = false
                // the most it may accelerate: its object's cap (plan.mechanism.motion.acceleration.max), and its first segment's
                // region's (Extrude.accelMax; the rest per segment, below)
                val accelBase = listOfNotNull(accelFor(p.feature), (pathSettings[p] ?: s).accelMax).min()
                Math.round(p.exact?.accel ?: minOf(accelBase, p.accelMax?.get(0)?.takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY)).let { a -> if (a != accelWritten) { out.write(dialect.accel(a)); accelWritten = a } }
                val zHop = s.zHopUm / 1000.0
                val hop = pz + zHop
                // after a tool change the head's place is unknown: rise where it is, clear of everything printed so far
                if (z.isNaN()) { val up = maxOf(hop, clearance.highest + zHop); out.write("G1 Z${n(up)} F${f(moveF(s.travelSpeed))}\n"); z = up }
                // plastic this travel must clear above the layer (a taller finished object, a curved layer's higher part, a
                // support's top beside a bridge): the head rises straight up to clear the tip, its cone, and its body, then
                // crosses, then comes down (Clearance)
                // over a detour (TravelRouter) the head follows the via points: the need is the greatest over its legs
                val tipNeeds = if (x.isNaN()) pz else run {
                    var need = pz; var ax = x; var ay = y
                    via?.let { vs -> var k = 0; while (k + 1 < vs.size) { val vx = vs[k] / 1000.0; val vy = vs[k + 1] / 1000.0; need = maxOf(need, clearance.need(ax, ay, vx, vy, pz)); ax = vx; ay = vy; k += 2 } }
                    maxOf(need, clearance.need(ax, ay, sx, sy, pz))
                }
                if (tipNeeds > pz + 1e-9 && z < tipNeeds + zHop - 1e-9) {
                    if (!retracted) { retract(); retracted = true }
                    out.write("G1 Z${n(tipNeeds + zHop)} F${f(moveF(s.travelSpeed))}\n"); z = tipNeeds + zHop
                }
                // the detour around the layer's other islands first (TravelRouter), the lift on its first move
                via?.let { vs ->
                    var k = 0
                    while (k + 1 < vs.size) {
                        val vx = vs[k] / 1000.0; val vy = vs[k + 1] / 1000.0
                        if ((d > minTravelMm || p.via != null || speckHop) && !hiddenTravel && z < hop - 1e-9) { out.write("G1 X${n(vx)} Y${n(vy)} Z${n(hop)} F${f(moveF(s.travelSpeed))}\n"); z = hop }
                        else out.write("G1 X${n(vx)} Y${n(vy)} F${f(moveF(s.travelSpeed))}\n")
                        x = vx; y = vy; k += 2
                    }
                }
                if ((d > minTravelMm || p.via != null || speckHop) && !hiddenTravel && z < hop - 1e-9) {
                    // lift during the travel (one XY+Z move) rather than a separate slow Z-only move
                    out.write("G1 X${n(sx)} Y${n(sy)} Z${n(hop)} F${f(moveF(s.travelSpeed))}\n"); z = hop
                } else if (x.isNaN() || n(sx) != n(x) || n(sy) != n(y)) out.write("G1 X${n(sx)} Y${n(sy)} F${f(moveF(s.travelSpeed))}\n")   // none to where the head is
                // the drop to the path's start: at the travel's speed, or, on a lifted bridge's layer with its own, at that
                val dropF = if (bridgeLayer && s.bridgeMoveSpeed != null) " F${f(moveF(s.travelSpeed))}" else ""
                if (z != startZ) { out.write("G1 Z${n(startZ)}$dropF\n"); z = startZ }
                // the push back: an exact path's standing still here; any other path's below, once its plastic per mm is known
                // (part of it may be spread over the path's first mm: hardware.filament.retract.return.distance)
                val toReturn = if (retracted) retractedMm else 0.0
                if (retracted && p.exact != null) out.write("G1 E${n(retractedMm)} F${f(s.deretractSpeed)}\n")
                retracted = false
                nextRetract = null
                x = sx; y = sy
                // printed from here on: every later travel clears it
                clearance.add(p.xy, p.closed, p.zUm, p.zs)
                val ex = p.exact
                if (ex != null) {
                    // exactly as given: the bead model times the path's own flow ratio, its own speed per segment; a path with its own
                    // Z and thickness per vertex (a scarf seam's ramp on the seam plate, Scarf.extrudes) lays each segment from the
                    // mean of its ends' thickness and rises with its Z, as the writer's own paths do
                    val wx = p.widthUm / 1000.0
                    fun eOf(hUm: Double) = hUm / 1000.0 * (wx - hUm / 1000.0 * (1 - Math.PI / 4)) / filamentArea * ex.flowRatio
                    val ePerMmX = eOf(p.heightUm.toDouble())
                    val m = p.xy.size / 2
                    var curF = ""
                    for (k in 1..(if (p.closed) m else m - 1)) {
                        val fk = f(ex.speed(k - 1))
                        if (fk != curF) { out.write("G1 F$fk\n"); curF = fk }
                        val idx = k % m
                        val nx = p.xy[2 * idx] / 1000.0; val ny = p.xy[2 * idx + 1] / 1000.0
                        val len = Math.hypot(nx - x, ny - y)
                        val nz = p.zs?.let { it[idx] / 1000.0 } ?: z
                        val eMm = p.heights?.let { hs -> eOf((hs[k - 1] + hs[idx]) / 2.0) } ?: ePerMmX
                        if (len > 0 || nz != z) out.write("G1 X${n(nx)} Y${n(ny)}" + (if (nz != z) " Z${n(nz)}" else "") + " E${e(len * eMm)}\n")
                        x = nx; y = ny; z = nz
                    }
                    out.write("; CELL END\n")
                    lastPath = p
                    continue
                }
                // extrude
                val ePerMm = p.ePerMmOverride ?: (p.heightUm / 1000.0 * (p.widthUm / 1000.0 - p.heightUm / 1000.0 * (1 - Math.PI / 4)) / filamentArea * s.flowRatio *
                    featureFlow(p.feature))
                // a lifted bridge's own print speed: exactly as set, no slow-down for layer time, no floor, no overhang grading;
                //the melt cap stays (it lowers a speed only where the
                // filament cannot melt fast enough)
                val meltCap = s.maxVolumetric / (p.heightUm / 1000.0 * (p.widthUm / 1000.0 - p.heightUm / 1000.0 * (1 - Math.PI / 4)))
                // a path with its own speeds per segment (an arched bridge's layers: its slow speed over air only, ramping
                // up layer by layer) takes them where given and the usual speed elsewhere; without them, every extrusion of the
                // bridge's layers at its print speed, as before
                val segFixed = p.segmentSpeeds
                // wave beads on a waved bridge's first layer keep their own speed (plan.technique.wave_overhang.speed)
                // the fastest it may print: its object's cap (plan.mechanism.motion.speed.max), over every speed below (a region's,
                // per segment: Extrude.speedMax)
                val cap = minOf(meltCap, (pathSettings[p] ?: s).speedMax ?: Double.POSITIVE_INFINITY)
                val fixedSpeed = if (!bridgeLayer || segFixed != null || p.feature == "Wave overhang" || p.feature == "Wave overhang, layer above") null else s.bridgePrintSpeed?.let { v -> Math.min(v, cap) }
                /** Segment [k]'s own speed (mm/s), the melt cap and the caps applied; null = the usual. */
                fun ownSpeed(k: Int): Double? = segFixed?.get(k)?.takeIf { !it.isNaN() }?.let { Math.min(it, cap) }
                val slowF = slowLayer?.get(pathObject[p])?.let { if (p.feature in VISIBLE) it[1] else it[0] } ?: 1.0
                // one cap on every segment (the purge tower's first layer: plan.mechanism.purge.tower.first_layer.speed) caps the
                // path's own speed, so the path is still fitted to arcs; caps that differ along it are laid segment by segment
                val uniformCap = p.speedMax?.let { a -> a.firstOrNull()?.takeIf { c -> !c.isNaN() && a.all { it == c } } }
                val baseSpeed = Math.min(fixedSpeed ?: Math.max(Math.min(s.slowDownMinSpeed, pathSpeed(p, first)), pathSpeed(p, first) * slowF),
                    uniformCap ?: Double.POSITIVE_INFINITY)
                val m = p.xy.size / 2
                val count = if (p.closed) m else m - 1
                // variable width (arachne center lines) or a curved layer's varying thickness: each segment's
                // extrusion from the mean of its ends' widths and thicknesses; the speed cap above uses the
                // widest and thickest (widthUm, heightUm). A path with its own flow (ironing) keeps it: the bead model does
                // not apply to it (an arched bridge's ironed top layer follows the arch, so carries thicknesses, and
                // was laid a whole line's plastic from them, about 36 times its flow)
                val segE = if (p.ePerMmOverride != null || (p.widths == null && p.heights == null)) null else {
                    val fl = s.flowRatio * featureFlow(p.feature)
                    val ws = p.widths; val hs = p.heights
                    DoubleArray(count) { k ->
                        val w = if (ws == null) p.widthUm / 1000.0 else (ws[k] + ws[(k + 1) % m]) / 2000.0
                        val h = if (hs == null) p.heightUm / 1000.0 else (hs[k] + hs[(k + 1) % m]) / 2000.0
                        h * (w - h * (1 - Math.PI / 4)) / filamentArea * fl
                    }
                }
                // the path's length seen from above, for the restore spread over its start and the coasting at its end
                val pathMm = if (s.returnDistanceMm == null && s.coastMm == null) 0.0 else (1..count).sumOf { k ->
                    val a = k - 1; val b = k % m
                    Math.hypot((p.xy[2 * b] - p.xy[2 * a]) / 1000.0, (p.xy[2 * b + 1] - p.xy[2 * a + 1]) / 1000.0)
                }
                // coasting (hardware.filament.retract.coast.distance): the path's last mm with no filament pushed, before a
                // retraction only; a path no longer than it is laid whole
                val coastMm = s.coastMm?.takeIf { it > 0 && pathMm > it && run {
                    val e = if (p.closed) 0 else m - 1
                    retractsAfter(stepIndex, p.xy[2 * e] / 1000.0, p.xy[2 * e + 1] / 1000.0)
                } } ?: 0.0
                // the restore spread over the path's first mm (hardware.filament.retract.return.distance; never into the coasting),
                // as much as the tool's limit on a move's extruded cross-section (hardware.tool.extrude.section.max) leaves over
                // the line's own plastic; the rest standing still first (all of it with either unset)
                val returnOverMm = s.returnDistanceMm?.let { Math.min(it, pathMm - coastMm) } ?: 0.0
                val returnOnPath = s.extrudeSectionMaxMm2?.takeIf { toReturn > 0 && returnOverMm > 0 }?.let { maxA ->
                    val lineE = segE?.max() ?: ePerMm
                    Math.min(toReturn, returnOverMm * Math.max(0.0, maxA / filamentArea - lineE))
                } ?: 0.0
                if (toReturn - returnOnPath > 1e-9) out.write("G1 E${n(toReturn - returnOnPath)} F${f(s.deretractSpeed)}\n")
                // either laid segment by segment, not fitted to arcs
                val ownEnds = returnOnPath > 0 || coastMm > 0
                out.write("G1 F${f(baseSpeed)}\n")
                // the part fan back to the layer's after a raised overhang fan (a path with overhangs sets its own per segment):
                // the object's own (plan.mechanism.cooling.fan over its filament's), not the plate's
                val layerFan = if (fanOn) s.fanMinPct else 0.0
                if (p.overhangShare == null) fanTo(layerFan)
                // a region's caps per segment (MotionRegions): laid segment by segment, not fitted to arcs
                val segCaps = (p.speedMax != null && uniformCap == null) || p.accelMax != null
                if (s.arcFitting && !ownEnds && !segCaps && p.overhangShare == null && segE == null && !p.curved && count >= 3) {
                    // Arc fitting (enable_arc_fitting): tolerance = resolution. The path's own points,
                    // closed back to the start for loops; arcs as G2/G3 with I/J relative to here.
                    val pts = DoubleArray(2 * (count + 1))
                    for (k in 0..count) { val idx = k % m; pts[2 * k] = p.xy[2 * idx] / 1000.0; pts[2 * k + 1] = p.xy[2 * idx + 1] / 1000.0 }
                    for (mv in ArcFit.fit(pts, s.resolutionUm / 1000.0, minLengthMm = s.arcSegmentMm ?: 0.0)) {
                        when (mv) {
                            is ArcFit.Line -> { val len = Math.hypot(mv.x - x, mv.y - y); if (len > 0) out.write("G1 X${n(mv.x)} Y${n(mv.y)} E${e(len * ePerMm)}\n") }
                            is ArcFit.Arc -> out.write("${if (mv.cw) "G2" else "G3"} X${n(mv.x)} Y${n(mv.y)} I${n(mv.cx - x)} J${n(mv.cy - y)} E${e(mv.length * ePerMm)}\n")
                        }
                        x = mv.x; y = mv.y
                    }
                    lastPath = p
                    continue
                }
                // overhangs: the speed graded per segment by its overhang share (OverhangSpeed); the
                // wall keeps its label, as Flash Studio's does. A segment hanging past the line below
                // prints at the conditions the filament's overhang share holds at: no faster than its
                // overhang speed, with the part fan at its overhang fan (back to the layer's after)
                val ps = pathSettings[p] ?: s
                val from = OverhangSpeed.from(p.widthUm, p.heightUm)
                var curF = f(baseSpeed)
                /** Segment [k]'s (from vertex k) speed (as written) and part fan (%), by its overhang share. */
                fun segSpeedFan(k: Int): Pair<String, Double> {
                    val share = p.overhangShare!![k]
                    val over = share / 1000.0 > from
                    val graded = OverhangSpeed.graded(share / 1000.0, baseSpeed, s.bridgeSpeed, from)
                    val fk = (ownSpeed(k) ?: fixedSpeed)?.let { f(it) } ?: f(Math.min(cap, if (over && ps.overhangSpeed != null) Math.min(graded, ps.overhangSpeed!!) else graded))
                    // the overhang fan over air, on the first layer too, where the fan is otherwise off: a first layer
                    // has over-air shares only where layers follow an arch's underside (its first layer over the hollow)
                    return fk to (if (over && (fanOn || first)) Math.max(layerFan, ps.overhangFanPct) else layerFan)
                }
                if (s.arcFitting && !ownEnds && !segCaps && p.overhangShare != null && segE == null && !p.curved && count >= 3) {
                    // Arc fitting on a wall with overhang shares (every outer wall above the first layer has them,
                    // so none was ever fitted): the wall in runs of segments that print at one speed and fan, each run
                    // fitted on its own, the speed and fan changed between runs as segment by segment
                    var a = 0
                    while (a < count) {
                        val sf = segSpeedFan(a)
                        var b = a
                        while (b + 1 < count && segSpeedFan(b + 1) == sf) b++
                        fanTo(sf.second)
                        if (sf.first != curF) { out.write("G1 F${sf.first}\n"); curF = sf.first }
                        val pts = DoubleArray(2 * (b - a + 2))
                        for (k in a..b + 1) { val idx = k % m; pts[2 * (k - a)] = p.xy[2 * idx] / 1000.0; pts[2 * (k - a) + 1] = p.xy[2 * idx + 1] / 1000.0 }
                        for (mv in ArcFit.fit(pts, s.resolutionUm / 1000.0, minLengthMm = s.arcSegmentMm ?: 0.0)) {
                            when (mv) {
                                is ArcFit.Line -> { val len = Math.hypot(mv.x - x, mv.y - y); if (len > 0) out.write("G1 X${n(mv.x)} Y${n(mv.y)} E${e(len * ePerMm)}\n") }
                                is ArcFit.Arc -> out.write("${if (mv.cw) "G2" else "G3"} X${n(mv.x)} Y${n(mv.y)} I${n(mv.cx - x)} J${n(mv.cy - y)} E${e(mv.length * ePerMm)}\n")
                            }
                            x = mv.x; y = mv.y
                        }
                        a = b + 1
                    }
                    lastPath = p
                    continue
                }
                var walked = 0.0
                for (k in 1..count) {
                    val idx = k % m
                    val share = p.overhangShare?.get(k - 1)
                    val own = ownSpeed(k - 1)
                    // the segment's region cap (MotionRegions), over whatever speed it prints at
                    val segCap = p.speedMax?.get(k - 1)?.takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY
                    val speedK: Double? = if (share != null) {
                        val over = share / 1000.0 > from
                        val graded = OverhangSpeed.graded(share / 1000.0, baseSpeed, s.bridgeSpeed, from)
                        // the overhang fan over air, on the first layer too, where the fan is otherwise off: a first layer
                        // has over-air shares only where layers follow an arch's underside (its first layer over the hollow)
                        fanTo(if (over && (fanOn || first)) Math.max(layerFan, ps.overhangFanPct) else layerFan)
                        own ?: fixedSpeed ?: Math.min(cap, if (over && ps.overhangSpeed != null) Math.min(graded, ps.overhangSpeed!!) else graded)
                    } else if (segFixed != null) own ?: baseSpeed
                    else if (p.speedMax != null) baseSpeed else null
                    if (speedK != null) { val fk = f(Math.min(speedK, segCap)); if (fk != curF) { out.write("G1 F$fk\n"); curF = fk } }
                    p.accelMax?.let { am -> Math.round(minOf(accelBase, am[k - 1].takeIf { !it.isNaN() } ?: Double.POSITIVE_INFINITY)).let { a -> if (a != accelWritten) { out.write(dialect.accel(a)); accelWritten = a } } }
                    val nx = p.xy[2 * idx] / 1000.0; val ny = p.xy[2 * idx + 1] / 1000.0
                    val len = Math.hypot(nx - x, ny - y)
                    // One rule: the filament for a move is its whole length, in X, Y, and Z, times its bead's
                    // cross-section. Over air the bead is a strand, its full cross-section: a bridge line, a tack (its rise most of
                    // all), a wall segment wholly over air. On what is printed below, the bead fills a
                    // layer measured vertically, which on a slope θ is h cos θ thick at right angles to its surface: its
                    // cross-section that much thinner (the plastic for the slab, h times the area under the line seen from above).
                    val nz = p.zs?.let { it[idx] / 1000.0 } ?: z
                    val dz = if (z.isNaN()) 0.0 else nz - z
                    val length = Math.hypot(len, dz)
                    val strand = p.feature == "Bridge" || p.feature == "Bridge tack" || (share != null && share >= 1000)
                    // 1, or cos θ on a supported slope; nothing for a move straight up or down (a step of a curved layer's surface,
                    // where the top meets a face rising from it: it lays no line, and plastic there reads as a restore)
                    val section = if (len == 0.0) 0.0 else if (strand || length == 0.0) 1.0 else len / length
                    val eSeg = length * section * (segE?.get(k - 1) ?: ePerMm)
                    if (!ownEnds) {
                        if (len > 0 || nz != z) out.write("G1 X${n(nx)} Y${n(ny)}" + (if (nz != z) " Z${n(nz)}" else "") + " E${e(eSeg)}\n")
                    } else {
                        // the restore's share over this segment (the part of it within the first returnOverMm), and coasting from cut
                        val back = if (returnOverMm > 0) returnOnPath * (Math.min(walked + len, returnOverMm) - Math.min(walked, returnOverMm)) / returnOverMm else 0.0
                        val cut = pathMm - coastMm
                        fun zPart(zz: Double) = if (zz != z) " Z${n(zz)}" else ""
                        when {
                            coastMm == 0.0 || walked + len <= cut -> if (len > 0 || nz != z) out.write("G1 X${n(nx)} Y${n(ny)}${zPart(nz)} E${e(eSeg + back)}\n")
                            walked >= cut -> if (len > 0 || nz != z) out.write("G1 X${n(nx)} Y${n(ny)}${zPart(nz)}\n")
                            else -> {
                                // the segment the coasting starts in: laid to the cut, then on with no filament pushed
                                val t = (cut - walked) / len
                                val cx = x + t * (nx - x); val cy = y + t * (ny - y); val cz = z + t * (nz - z)
                                out.write("G1 X${n(cx)} Y${n(cy)}${zPart(cz)} E${e(eSeg * t + back)}\n")
                                out.write("G1 X${n(nx)} Y${n(ny)}" + (if (nz != cz) " Z${n(nz)}" else "") + "\n")
                            }
                        }
                        walked += len
                    }
                    x = nx; y = ny; z = nz
                }
                lastPath = p
            }
        } }
        flushLift()
        out.write("G1 E-${n(s.retractMm)} F${f(s.retractSpeed)}\n")
        out.write(Template.render(s.endGcode, { name ->
            when (name) {
                "max_layer_z" -> Template.num(maxZ)
                "current_extruder" -> Template.num(0)
                else -> vars(name)
            }
        }) + "\n")
        out.finish()
    }

    /** Feature speed capped by the filament's max volumetric speed for this bead (w, h). */
    private fun pathSpeed(p: Extrude, first: Boolean): Double {
        val ps = pathSettings[p] ?: s
        val area = p.heightUm / 1000.0 * (p.widthUm / 1000.0 - p.heightUm / 1000.0 * (1 - Math.PI / 4))   // mm²
        return minOf(speedFor(ps, p.feature, first), ps.maxVolumetric / area, ps.speedMax ?: Double.POSITIVE_INFINITY)
    }

    /**
     * Factor (<= 1) that stretches [extrudeS] of extrusion (an object's paths in a layer and everything after them in it)
     * so that, with [gapS] of other printing before the object's next layer (travel, other layers), the layer below has
     * the minimum layer time to cool.
     */
    private fun slowFactor(extrudeS: Double, gapS: Double, target: Double = s.slowDownLayerTimeS): Double {
        if (extrudeS + gapS >= target || extrudeS <= 0.0) return 1.0
        return (extrudeS / (target - gapS).coerceAtLeast(extrudeS)).coerceIn(0.0, 1.0)
    }

    /**
     * The factors [hidden, visible] (plan.mechanism.cooling.slowdown.style = "hidden_first") that bring [currentS] (the time until
     * the object's next layer, as now) up to [target]: the [hidden] paths (length mm, speed mm/s each) slowed first, each to
     * no less than the minimum speed (as the writer applies a factor: max(min(floor, v), v f)); the [visible] paths only for
     * what the hidden ones cannot give. A factor of 0 = every such path at its floor.
     */
    private fun hiddenFirst(currentS: Double, target: Double, hidden: List<Pair<Double, Double>>, visible: List<Pair<Double, Double>>): DoubleArray {
        if (currentS >= target) return doubleArrayOf(1.0, 1.0)
        val floor = s.slowDownMinSpeed
        fun time(ps: List<Pair<Double, Double>>, f: Double) = ps.sumOf { (len, v) -> len / Math.max(Math.min(floor, v), v * f) }
        /** The factor adding [extra] seconds to [ps] (bisection: the time falls as the factor rises); 0 when even that is short. */
        fun factorFor(ps: List<Pair<Double, Double>>, extra: Double): Double {
            val now = time(ps, 1.0)
            if (time(ps, 0.0) - now <= extra) return 0.0
            var lo = 0.0; var hi = 1.0
            repeat(40) { val mid = (lo + hi) / 2; if (time(ps, mid) - now > extra) lo = mid else hi = mid }
            return hi
        }
        val need = target - currentS
        val fromHidden = time(hidden, 0.0) - time(hidden, 1.0)
        if (fromHidden >= need) return doubleArrayOf(factorFor(hidden, need), 1.0)
        return doubleArrayOf(0.0, factorFor(visible, need - fromHidden))
    }

    /**
     * Points (mm, flat x y ...) walking `dist` backwards along the path just printed, starting at
     * its end (x, y). Loops walk back around the loop; open paths stop at their start.
     */
    private fun wipePoints(p: Extrude, x: Double, y: Double, dist: Double): List<Double> {
        val m = p.xy.size / 2
        val seq = ArrayList<Double>()
        // vertex order walking backwards from the end
        val order = if (p.closed) (m - 1 downTo 0).toList() + (m - 1 downTo 1).toList() else (m - 2 downTo 0).toList()
        var cx = x; var cy = y; var left = dist
        for (idx in order) {
            val vx = p.xy[2 * idx] / 1000.0; val vy = p.xy[2 * idx + 1] / 1000.0
            val seg = Math.hypot(vx - cx, vy - cy)
            if (seg == 0.0) continue
            if (seg >= left) { val t = left / seg; seq.add(cx + (vx - cx) * t); seq.add(cy + (vy - cy) * t); return seq }
            seq.add(vx); seq.add(vy); left -= seg; cx = vx; cy = vy
        }
        return seq
    }

    /**
     * A travel's route (µm, flat x y ..., null: straight) from where a wipe left the head to [to]'s start (sx, sy, mm); [wiped]
     * is the path's end then each wipe point (mm). Straight when that crosses none of the layer's outer walls; else combed from
     * there (TravelRouter.combFrom; a layer's first travel against the layer below, [first]); else, when the route planned from the path's end ([to]'s via, or straight) crosses no outer
     * wall, back along the wipe (it lay on the path just printed) and on along that route; else that route as planned.
     */
    private fun replanAfterWipe(layer: Lazy<TravelRouter.CombLayer>, below: Lazy<TravelRouter.CombLayer>?, first: Boolean, wiped: List<Double>,
                                sx: Double, sy: Double, to: Extrude, last: Extrude?, ratio: Double, extraUm: Long): LongArray? {
        val m = wiped.size / 2
        val wx = wiped[2 * m - 2] * 1000; val wy = wiped[2 * m - 1] * 1000; val bx = sx * 1000; val by = sy * 1000
        // a layer's first travel runs over the layer below, nothing of this one printed yet (TravelRouter.combFrom)
        val walls = if (first && below != null) below.value else layer.value
        if (!walls.crossesWall(wx, wy, bx, by)) return null
        TravelRouter.combFrom(wx, wy, to, first, layer, below, ratio, extraUm, fromOpenOuter = last != null && last.feature == "Outer wall" && !last.closed)?.let { return it }
        val planned = to.via
        var px = wiped[0] * 1000; var py = wiped[1] * 1000
        var k = 0
        while (planned != null && k + 1 < planned.size) {
            if (walls.crossesWall(px, py, planned[k].toDouble(), planned[k + 1].toDouble())) return planned
            px = planned[k].toDouble(); py = planned[k + 1].toDouble(); k += 2
        }
        if (walls.crossesWall(px, py, bx, by)) return planned
        val back = ArrayList<Long>()
        for (i in m - 2 downTo 0) { back.add(Math.round(wiped[2 * i] * 1000)); back.add(Math.round(wiped[2 * i + 1] * 1000)) }
        planned?.let { for (v in it) back.add(v) }
        return back.toLongArray()
    }

    /** A feature's own extrusion multiplier, on top of the flow ratio. */
    private fun featureFlow(feature: String): Double = when (feature) {
        "Bridge", "Internal Bridge", "Bridge tack" -> s.bridgeFlow
        else -> 1.0
    }

    /**
     * Speeds: the first layer at its own (adhesion), the purge tower at its own (plan.mechanism.purge.tower.speed), its
     * first layer no faster than the first layer's; visible lines
     * at the visible cap; bridges at the bridge speed (sag); everything else, hidden, at the
     * machine's fastest move. [pathSpeed] then caps each by the filament's melt rate.
     */
    private fun speedFor(s: SliceSettings, feature: String, first: Boolean): Double = when {
        // the tower's first layer holds to the bed like the model's (a tower laid at the hidden speed there came loose)
        feature == "Prime tower" -> if (first) Math.min(s.towerSpeed, s.firstLayerSpeed) else s.towerSpeed
        feature == "Ironing" -> s.ironingSpeed
        // a bridge on the first layer (layers that follow an arch's underside: its first layer over the hollow is in the
        // air) prints as a bridge, not at the first layer's adhesion speed
        feature == "Bridge" || feature == "Internal Bridge" || feature == "Bridge tack" -> s.bridgeSpeed
        // a bead laid in the air beside the one before: the technique's own speed, about 2 mm/s in the sources
        feature == "Wave overhang" || feature == "Wave overhang, layer above" -> s.waveSpeed
        // a waved first layer's line laid again over it (plan.technique.bridge.wave.second_pass.zones): its own speed
        feature == "Wave overhang, second pass" -> s.bridgeWaveSecondPassSpeed ?: s.waveSpeed
        // its Xs (wave.x_layer) and its layer along the span (wave.span_layer): theirs
        feature == "Wave overhang, X layer" -> s.bridgeWaveXLayerSpeed ?: s.waveSpeed
        feature == "Wave overhang, span layer" -> s.bridgeWaveSpanLayerSpeed ?: s.waveSpeed
        first || feature == "Brim" -> s.firstLayerSpeed
        feature in VISIBLE -> s.visibleSpeed
        // inner walls held to the visible lines' (plan.mechanism.motion.inner_walls.as_visible): their ripple shows through
        feature == "Inner wall" && s.innerWallsAsVisible -> s.visibleSpeed
        feature in HIDDEN -> s.hiddenSpeed
        else -> error("no speed rule for feature '$feature'")
    }

    private fun accelFor(feature: String): Double = if (feature in VISIBLE || (feature == "Inner wall" && s.innerWallsAsVisible)) s.visibleAccel else s.defaultAccel

    /**
     * Template variables: plymeld's own names (for templates in plymeld config: nozzle_temperature,
     * first_layer_nozzle_temperature, bed_temperature, first_layer_bed_temperature, layers, max_z, max_layer_z,
     * initial_tool, max_filament_flow (mm³/s), filament_diameter (mm), z_hop (mm), max_print_height (mm, the build height)), the names other slicers'
     * templates use (investigation), and every imported setting. [initialTool]: the tool the program
     * prints with before any tool change of its own (0-based, as T<n>).
     */
    private fun templateVars(layerCount: Int, maxZ: Double, initialTool: Int): (String) -> Template.Value? {
        val computed = mapOf(
            // the filament's maximum flow as the config has it: a template passes it on as the filament's (plymeld's own caps
            // keep the melt buffer below it)
            "max_filament_flow" to tnum(s.maxVolumetricMeasured),
            "filament_diameter" to tnum(s.filamentDiameterMm),
            "z_hop" to tnum(s.zHopUm / 1000.0),
            "nozzle_temperature" to Template.num(s.nozzleTempC.toLong()),
            "first_layer_nozzle_temperature" to Template.num(s.nozzleTempFirstC.toLong()),
            "bed_temperature" to Template.num(s.bedTempC.toLong()),
            "first_layer_bed_temperature" to Template.num(s.bedTempFirstC.toLong()),
            "layers" to Template.num(layerCount.toLong()),
            "max_z" to Template.num(maxZ),
            "bed_temperature_initial_layer_single" to Template.num(s.bedTempFirstC.toLong()),
            "initial_no_support_extruder" to Template.num(initialTool.toLong()),
            "initial_tool" to Template.num(initialTool.toLong()),
            "initial_extruder" to Template.num(initialTool.toLong()),
            "total_layer_count" to Template.num(layerCount.toLong()),
            "outer_wall_volumetric_speed" to outerWallVolumetric(),
            "max_print_height" to tnum(s.maxPrintHeightMm),
            "max_layer_z" to Template.num(maxZ),
        )
        return { name -> computed[name] ?: foreignVars[name]?.let { v -> if (v.size == 1) Template.parse(v[0]) else Template.Vec(v.map(Template::parse)) } }
    }

    /**
     * min(outer wall speed × width × height, filament max volumetric speed). Once rendered 12 on a
     * test model, matching QIDIStudio's rendered start block (a comparison test removed). The rule
     * itself is inferred from that one data point — flagged, not established.
     */
    private fun outerWallVolumetric(): Template.Value {
        val v = s.visibleSpeed * s.outerWallWidthUm / 1000.0 * s.layerHeightUm / 1000.0
        val cap = s.maxVolumetric
        return if (cap < v) tnum(cap) else Template.num(v)
    }

    /** A number as a template value: whole numbers render without a decimal point, as when typed. */
    private fun tnum(v: Double): Template.Value = if (v == Math.floor(v) && Math.abs(v) < 1e15) Template.num(v.toLong()) else Template.num(v)
    private fun n(v: Double): String = Num.trimmed(v, 3)
    private fun e(v: Double): String = Num.trimmed(v, 5, minusZero = true)
    private fun f(mmPerS: Double): String = Math.round(mmPerS * 60).toString()
}

/** A start template that sets relative extrusion: M83 as a command (not in a comment), at a line start. */
private val RELATIVE_E = Regex("(?m)^\\s*M83\\b")

/** Lines seen on the finished part: capped by the visible speed and acceleration. */
private val VISIBLE = setOf("Outer wall", "Top surface", "Bottom surface")
/** Lines inside the part or removed after printing: as fast as the machine and the melt rate allow. */
private val HIDDEN = setOf("Inner wall", "Sparse infill", "Internal solid infill", "Support", "Support interface")
