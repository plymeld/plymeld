package plymeld.check.catalog

import plymeld.check.WallCoverage
import plymeld.check.reviewRender
import plymeld.check.testSetup
import plymeld.core.Job
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup
import plymeld.model.Entity
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.SliceSettings
import plymeld.model.Values
import plymeld.model.withObjectSetups
import java.util.Locale

/**
 * How the catalog's models are sliced and read: a [Rig] (the machine and the plan values a model is sliced with, over the
 * slicing tests' setup, testSetup), [write] (one object written through Job, as `slice` writes it), [problems] (the
 * pre-print check on a lone object) and [wallReading] (WallCoverage on each layer against the model's own cut).
 */
object Slicing {
    /** The machine and plan values over the test setup; [twoTools]: a second tool for a model's part B; [layerMm]: the layer height locked. */
    class Rig(val name: String, val values: List<Pair<String, Any>>, val twoTools: Boolean, val layerMm: Double)

    /** A Creator 5 with a second tool (t2, the second test filament) beside the test machine's: the two-tool models' machine (TEST VALUES). */
    val C5_TWO_TOOLS: List<Pair<String, Any>> = listOf(
        "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
        "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}",
        "hardware.machine.tm.firmware.exclude_object" to false, "hardware.machine.tm.firmware.arcs" to false,
        "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
        "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
        "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
        "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "150 mm")

    /** Walls by local width: 0.08 mm layers, 0.44 mm lines, three walls, no support, on the two-tool machine. */
    val WALLS = Rig("walls", C5_TWO_TOOLS + listOf("plan.support.enabled" to false,
        "plan.technique.flat.layer.height" to "0.08 mm", "plan.layer.walls.outer.line.width" to "0.44 mm", "plan.layer.walls.inner.line.width" to "0.44 mm",
        "plan.layer.walls.thickness" to "1.32 mm"), twoTools = true, layerMm = 0.08)
    /** Two filaments side by side: 0.08 mm layers, no support, the test plan's walls, on the two-tool machine. */
    val SLIVER = Rig("sliver", C5_TWO_TOOLS + listOf("plan.support.enabled" to false, "plan.technique.flat.layer.height" to "0.08 mm"), twoTools = true, layerMm = 0.08)
    private val RIDGE_LINES: List<Pair<String, Any>> = listOf("plan.technique.flat.layer.height" to "0.08 mm", "plan.layer.walls.outer.line.width" to "0.4 mm",
        "plan.layer.walls.inner.line.width" to "0.4 mm", "hardware.machine.tm.firmware.arcs" to false)
    /** A thin ridge alone: 0.08 mm layers, 0.4 mm lines, on the test machine. */
    val RIDGE = Rig("ridge", RIDGE_LINES, twoTools = false, layerMm = 0.08)
    /** A thin ridge beside a second part: as [RIDGE], on the two-tool machine. */
    val RIDGE_TWO = Rig("ridge beside a part", C5_TWO_TOOLS + RIDGE_LINES, twoTools = true, layerMm = 0.08)
    /** The test setup as it is (0.2 mm layers, two walls), arcs written as lines: one filament on the test machine. */
    val ONE_TOOL = Rig("one tool", listOf("hardware.machine.tm.firmware.arcs" to false), twoTools = false, layerMm = 0.2)
    /** The test setup's plan (0.2 mm layers, two walls) on the two-tool machine: part B on the second tool. */
    val TWO_TOOLS = Rig("two tools", C5_TWO_TOOLS, twoTools = true, layerMm = 0.2)

    /**
     * A file written through Job and what the writing measured: [strings] (StringRisk, as the writer measured it) and
     * [seamScar] (SeamScar of the planned layers; null when not asked for, or not measured: a multi-filament object).
     */
    class Written(val g: String, val plan: Plan, val setup: Setup, val strings: plymeld.gcode.StringRisk.Result? = null,
                  val seamScar: plymeld.core.SeamScar.Result? = null)

    /**
     * One object of [parts] (A, B, C ...: B on the second filament, the others on the first) written through Job with [rig]
     * and [extra] over it; [seams]: measure the seam scar too.
     */
    fun write(parts: List<TriangleSoup>, rig: Rig, vararg extra: Pair<String, Any>, seams: Boolean = false): Written {
        val names = listOf("A", "B", "C").take(parts.size)
        require(rig.twoTools || "B" !in names) { "rig ${rig.name}: one tool, ${parts.size} parts" }
        val base = testSetup(*(rig.values + extra).toTypedArray(),
            models = listOf(plymeld.config.ModelObject("o", names, names.map { if (it == "B") 2 else 1 }, "o.3mf")))
        val setup = if (!rig.twoTools) base else base.withObjectSetups(listOf(ObjectSetup("o", base.objects[0].intent, "spool 1", Values(Entity.INTENT, "object overrides"),
            names.map { PartSetup(it, if (it == "B") "spool 2" else "spool 1", Values(Entity.INTENT, it)) })))
        val plan = Plan.makeFor(listOf("o" to parts), setup, Plan.Companion.Overrides(pose = Pose.asGiven(), order = ObjectOrder.TOGETHER,
            flatHeights = FlatLayers.Heights.ONE, objects = listOf(Plan.Companion.ObjectLocks(layerHeightMm = rig.layerMm))))
        val job = Job(plan.layout, setup, "test")
        val g = java.io.StringWriter().also { job.write(it) }.toString()
        return Written(g, plan, setup, job.strings, if (seams) job.seamScar() else null)
    }

    /**
     * The pre-print check as the technique tests run it on a lone object (GcodeCheck, ModelCheck), with the walls' floor
     * [floorMm], fuzzy skin's [fuzzMm] and the narrowest infill area; not each part's tool, the hand-over widths or the first
     * layer's inset ([sliceCheck] passes every allowance `slice` does).
     */
    fun problems(w: Written, g: String = w.g, floorMm: Double = SliceSettings.of(w.setup).let { (it.minFeatureUm + 2 * it.resolutionUm) / 1000.0 },
                 fuzzMm: Double = 0.0): List<String> {
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val sf = ModelCheck.Surface(w.plan.parts, zBase, g)
        return GcodeCheck.check(g, Expectations.forSetup(w.setup, sf::at)) + ModelCheck.check(g, w.plan.parts, zBase, surface = sf, featureMinMm = floorMm,
            fuzzMm = fuzzMm, infillMinMm = 2 * SliceSettings.of(w.setup).innerWallWidthUm / 1000.0)
    }

    /**
     * The pre-print check as `slice` runs it on a lone flat object, every allowance from the object's settings: GcodeCheck
     * against the setup with each part's tool, and ModelCheck with each part's tool, the hand-over widths, the walls' floor,
     * the first layer's inset, fuzzy skin and the narrowest infill area (SliceSession.check).
     */
    fun sliceCheck(w: Written): List<String> {
        val s = SliceSettings.of(w.setup)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val sf = ModelCheck.Surface(w.plan.parts, zBase, w.g)
        // part B on the second tool (T1), the others on the first ([write])
        val tools = w.plan.parts.indices.map { if (it == 1) 1 else 0 }
        return GcodeCheck.check(w.g, Expectations.forSetup(w.setup, sf::at, tools.toSet(), tools.toSet())) +
            ModelCheck.check(w.g, w.plan.parts, zBase, surface = sf, partTools = tools,
                handOverMm = (s.sliverWidthUm ?: 0L) / 1000.0, featureMinMm = (s.minFeatureUm + 2 * s.resolutionUm) / 1000.0,
                firstLayerInsetMm = s.elephantFootUm / 1000.0, surfaceHandOverMm = if (s.sliverAtSurface == "neighbor") s.sliverHandOverUm / 1000.0 else 0.0,
                fuzzMm = if (s.fuzzySkin == "none") 0.0 else s.fuzzyThicknessUm / 1000.0, infillMinMm = 2 * s.innerWallWidthUm / 1000.0)
    }

    /** One model's wall reading: each read layer's measure (its part 0, tool 0) and the shift from the layer below. */
    class WallReading(val name: String, val layers: List<WallCoverage.Measure>, val shifts: List<Double>) {
        fun sum(f: (WallCoverage.Measure) -> Double) = layers.sumOf(f)
        fun max(f: (WallCoverage.Measure) -> Double) = layers.maxOf(f)
        override fun toString() = String.format(Locale.ROOT,
            "%s: %d layers; uncovered %.4f mm2 (widest %.3f mm; wider than the floor %.4f mm2); outside %.4f mm2 (farthest %.3f mm); paths %d (shortest %.2f mm, shortest move %.3f mm); doubled %.4f mm2; largest shift %.3f mm",
            name, layers.size, sum { it.uncoveredMm2 }, max { it.widestUncoveredMm }, sum { it.uncoveredWideMm2 }, sum { it.outsideMm2 }, max { it.outsideMaxMm },
            layers.sumOf { it.paths }, layers.minOf { it.shortestPathMm }, layers.minOf { it.shortestMoveMm }, sum { it.doubledMm2 }, shifts.maxOrNull() ?: 0.0)
    }

    /** Where a wall reading looks: the layers whose middle lies between [zFrom] and [zTo] (g-code Z), its grid's cell, and a fixed window (null: round each layer's cut). */
    class WallRead(val zFrom: Double, val zTo: Double, val cellMm: Double = 0.005, val window: WallCoverage.Window? = null)

    /** [w] read as [at] says, part 0 by tool 0; [maps]: a picture of each layer's cells, named after [name]. */
    fun wallReading(name: String, w: Written, at: WallRead, maps: Boolean = false): WallReading {
        val st = SliceSettings.of(w.setup)
        val read = WallCoverage.read(w.g, st.filamentDiameterMm, st.flowRatio)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val part = w.plan.parts[0]
        val floor = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0
        val out = ArrayList<WallCoverage.Measure>(); val shifts = ArrayList<Double>()
        var prev: WallCoverage.Layer? = null; var prevWin: WallCoverage.Window? = null
        for (l in read.layers) {
            val mid = l.z - l.h / 2
            if (mid < at.zFrom || mid > at.zTo) continue
            val section = Slicer.slice(part, doubleArrayOf(mid + zBase))[0].loops
            if (section.isEmpty()) continue
            val win = at.window ?: WallCoverage.Window.around(section, 0.6, at.cellMm)
            out += WallCoverage.measure(read, l, section, win, 0, floor, st.resolutionUm / 1000.0, st.infillWallOverlap,
                map = if (!maps) null else reviewRender(String.format(Locale.ROOT, "walls-by-width-map-%s-z%.2f.png", name.replace(' ', '-'), l.z)))
            if (prev != null) {
                val both = WallCoverage.Window(minOf(win.x0, prevWin!!.x0), minOf(win.y0, prevWin.y0), maxOf(win.x1, prevWin.x1), maxOf(win.y1, prevWin.y1))
                shifts += WallCoverage.shift(prev, l, both, 0)
            }
            prev = l; prevWin = win
        }
        return WallReading(name, out, shifts)
    }
}
