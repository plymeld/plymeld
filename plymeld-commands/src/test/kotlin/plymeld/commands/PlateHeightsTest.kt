package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import plymeld.core.decide.Plan
import plymeld.core.decide.TechniquePlan
import java.nio.file.Files
import java.nio.file.Path

/**
 * Layer heights on a plate of several objects:
 * - each object keeps its own decided layer height in either order (the one height shared by objects printed together
 *   is gone): printed together, their layers are merged by height and the
 *   pre-print check reads each object's lines against that object.
 * - each candidate object order is predicted at the objects' decided layer heights (and infill
 *   directions), not at the settings' stand-ins.
 *
 * A Creator 5 with its head described (so both orders are usable and predicted), flat layers of one height only, and two wedges.
 */
class PlateHeightsTest : StringSpec({
    val hardware = """
        [hardware.machine.m]
        model = "flashforge-c5"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        gcode.end = "M104 S0"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
        [plan.technique.flat]
        heights = "one"
    """.trimIndent()
    /** A wedge 10 mm across (Y), its top rising from 1 mm along X at [deg] over [len] mm: its X-Z profile extruded along Y. */
    fun wedge(deg: Double, len: Double): String {
        val h1 = 1.0 + len * Math.tan(Math.toRadians(deg))
        val prof = listOf(0.0 to 0.0, len to 0.0, len to h1, 0.0 to 1.0)
        val sb = StringBuilder("solid wedge\n")
        fun tri(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>, c: Triple<Double, Double, Double>) {
            sb.append("facet normal 0 0 0\nouter loop\n")
            for (v in listOf(a, b, c)) sb.append("vertex ${v.first} ${v.second} ${v.third}\n")
            sb.append("endloop\nendfacet\n")
        }
        fun at(i: Int, y: Double) = Triple(prof[i].first, y, prof[i].second)
        val n = prof.size
        for (i in 1 until n - 1) { tri(at(0, 0.0), at(i, 0.0), at(i + 1, 0.0)); tri(at(0, 10.0), at(i + 1, 10.0), at(i, 10.0)) }
        for (i in 0 until n) { val j = (i + 1) % n; tri(at(i, 0.0), at(j, 10.0), at(j, 0.0)); tri(at(i, 0.0), at(i, 10.0), at(j, 10.0)) }
        return sb.append("endsolid wedge\n").toString()
    }
    val d: Path = Files.createTempDirectory("plymeld-plate-layers").also { d ->
        Files.writeString(d.resolve("hw.toml"), hardware)
        Files.writeString(d.resolve("shallow.stl"), wedge(10.0, 30.0)); Files.writeString(d.resolve("steep.stl"), wedge(60.0, 8.0))
        Files.writeString(d.resolve("together.toml"), "[plan]\nobjects.printing = \"together\"\n")
        Files.writeString(d.resolve("apart.toml"), "[plan]\nobjects.printing = \"one_at_a_time\"\n")
        // the seams and the in-island retraction locked: they are decided after the plan, which predicts each order with
        // provisional ones (back seams, the planner's hidden travels), so a decided value unlike those moves the written g-code's
        // time off the order's prediction (with speck travels routed along the wall, 20.3 against 20.4 min; the
        // decided retraction, every travel retracting on the steep object, was the same before); this test is about the
        // decided layer heights
        Files.writeString(d.resolve("quality.toml"), "[job.priorities]\nquality = \"10\"\nspeed = \"1\"\n[plan]\nseam = \"back_seam\"\nmechanism.motion.no_retract_in_island = \"true\"\n")
        // the coarse sample only (the range's ends, the baseline, two midpoints): no refinement, so two shapes whose optimum
        // lies on the same flat stretch land on the same point rather than on neighboring microns of it
        Files.writeString(d.resolve("coarse.toml"), "[method.search]\nlayer.heights = \"5\"\n")
        // each on its own: the shallow wedge for looks, the steep one for speed, so they decide different heights
        Files.writeString(d.resolve("each.toml"), "[job.object.\"shallow.stl\".priorities]\nquality = \"10\"\n[job.object.\"steep.stl\".priorities]\nspeed = \"10\"\n")
    }
    val models = listOf(d.resolve("shallow.stl"), d.resolve("steep.stl"))
    fun product(vararg cfs: String) = Product(ConfigStack.load(listOf("hw.toml") + cfs, emptyList(), d), models, null) { }
    fun heights(p: Plan) = p.decisions.filter { it.value is TechniquePlan }.map { (it.value as TechniquePlan).layerHeightMm }

    "in either order each object keeps its own decided layer height; printed together, the file holds both heights and the check reads each object against itself" {
        val apart = product("each.toml", "apart.toml").decide()
        apart.layout.order shouldBe plymeld.core.decide.ObjectOrder.ONE_AT_A_TIME
        val own = heights(apart)
        own.all { it != null } shouldBe true
        own.distinct().size shouldBe 2
        val pr = product("each.toml", "together.toml")
        val together = pr.decide()
        together.layout.order shouldBe plymeld.core.decide.ObjectOrder.TOGETHER
        heights(together) shouldBe own
        val text = java.io.StringWriter().also { plymeld.core.Job(together.layout, pr.result.setup, "test").write(it) }.toString()
        // the file's layers are at both heights (the first layer's own height aside)
        val fileHeights = text.lines().filter { it.startsWith(";HEIGHT:") }.map { it.removePrefix(";HEIGHT:").trim().toDouble() }.toSet()
        for (h in own) fileHeights.any { Math.abs(it - h!!) < 1e-6 } shouldBe true
        val zBase = together.parts.minOf { it.bounds()[2] }
        val tools = pr.partTools(together); var at = 0
        val objs = together.layout.placed.map { pl -> plymeld.core.check.PlateCheck.Obj(pl.name, pl.parts, tools.subList(at, at + pl.parts.size).also { at += pl.parts.size }) }
        plymeld.core.check.PlateCheck.together(text, objs, zBase).problems shouldBe emptyList()
        // control: the plate read as one model, every object's outline on every layer, is refused (what made such a plate
        // once need one shared height)
        plymeld.core.check.ModelCheck.check(text, together.parts, zBase).isNotEmpty() shouldBe true
    }

    "an object's own priorities steer its own decision: the wedge for looks takes finer layers than the one for speed; control: without them, the same" {
        val each = heights(product("each.toml", "apart.toml").decide())
        (each[0]!! < each[1]!!) shouldBe true
        // control (and seen to fail with every object given the general weights in the code): on the coarse sample, where
        // the refinement's path (which neighboring micron of a flat optimum each shape ends on) cannot tell them apart
        heights(product("apart.toml", "coarse.toml").decide()).distinct().size shouldBe 1
    }

    "each candidate order is predicted at the decided layer heights: the chosen order's prediction is the written g-code's" {
        // quality weighed up, so the decided heights are well away from the settings' 0.2 mm stand-in
        val free = product("quality.toml").decide()
        // each order, locked, written as the slice writes it (Product.decide puts the decided heights into the settings) and
        // predicted on its own: the free decision's candidate for that order must carry the same prediction
        for (cf in listOf("together.toml", "apart.toml")) {
            val pr = product("quality.toml", cf)
            val plan = pr.decide()
            heights(plan).all { it != null && Math.abs(it - 0.2) > 0.01 } shouldBe true
            val setup = pr.result.setup
            val out = java.io.StringWriter()
            plymeld.core.Job(plan.layout, setup, "test").write(out)
            val s = plymeld.model.SliceSettings.of(setup)
            val change = (plymeld.model.Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble()
            val p = plymeld.core.Predict.gcode(out.toString(), s.machineDescription, change, plymeld.core.Predict.limits(s))
            free.decisions.last().alternatives.single { it.label == plan.layout.order.label }.score shouldContain "predicted $p"
        }
    }
})
