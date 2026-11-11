package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import plymeld.core.check.CurvedCheck
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import java.nio.file.Files
import java.nio.file.Path

/**
 * The pre-print check of curved layers, from the config to the g-code: a pillow
 * (40 x 40 mm, its top z = 8 + cos(pi x / 40) cos(pi y / 40): 1 mm of rise, its steepest slope atan(pi / 40) = 4.49 deg at
 * the middle of each side, by hand) sliced curved from the bed at 0.2 mm reads back clean in warped space. Controls
 * that must fail: the same file read as flat layers; a nozzle angle under the slope; a nozzle height under the rise;
 * a thinnest layer above the thinnest the band prints (0.2 x (8 - zs) / (9 - zs), about 0.177 mm at the edge); one line
 * lifted off its surface; no nozzle angle and height at all. Also the head's outline from the config: its XY size and
 * the nozzle's position in it as the reach from the nozzle each way; a nozzle outside the head refused.
 */
class CurvedCheckTest : StringSpec({
    fun pillowStl(): String {
        val n = 40
        fun v(i: Int, j: Int, top: Boolean): String {
            val x = -20.0 + i; val y = -20.0 + j
            val z = if (top) 8 + Math.cos(Math.PI * x / 40) * Math.cos(Math.PI * y / 40) else 0.0
            return "vertex $x $y $z\n"
        }
        val sb = StringBuilder("solid pillow\n")
        fun tri(a: String, b: String, c: String) { sb.append("facet normal 0 0 0\nouter loop\n").append(a).append(b).append(c).append("endloop\nendfacet\n") }
        for (j in 0 until n) for (i in 0 until n) {
            tri(v(i, j, true), v(i + 1, j, true), v(i + 1, j + 1, true))
            tri(v(i, j, true), v(i + 1, j + 1, true), v(i, j + 1, true))
        }
        // the boundary counter-clockwise from above: walls outward, the bottom a fan from its center (as CurvedBandTest's)
        val ring = ArrayList<Pair<Int, Int>>()
        for (i in 0 until n) ring.add(i to 0)
        for (j in 0 until n) ring.add(n to j)
        for (i in n downTo 1) ring.add(i to n)
        for (j in n downTo 1) ring.add(0 to j)
        val center = "vertex 0.0 0.0 0.0\n"
        for (k in ring.indices) {
            val (pi, pj) = ring[k]; val (qi, qj) = ring[(k + 1) % ring.size]
            tri(v(pi, pj, false), v(qi, qj, false), v(qi, qj, true))
            tri(v(pi, pj, false), v(qi, qj, true), v(pi, pj, true))
            tri(center, v(qi, qj, false), v(pi, pj, false))
        }
        return sb.append("endsolid pillow\n").toString()
    }
    // a Creator 5 description (its g-code dialect, as ToolAssignmentTest's); the head and layer values are made up for the test
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
        head.nozzle.angle = "45 deg"
        head.nozzle.height = "4 mm"
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
    """.trimIndent()
    val lock = """
        [plan.object."pillow.stl"]
        band.1.technique = "curved"
        band.1.from = "0 mm"
        [plan.technique.flat]
        layer.height = "0.2 mm"
    """.trimIndent()
    val d: Path = Files.createTempDirectory("plymeld-curved").also { d ->
        Files.writeString(d.resolve("hw.toml"), hardware); Files.writeString(d.resolve("lock.toml"), lock); Files.writeString(d.resolve("pillow.stl"), pillowStl())
    }
    val cfs = listOf("hw.toml", "lock.toml")
    val model = d.resolve("pillow.stl")
    fun slice(out: String, vararg cv: String): String {
        SliceCommand().parse(cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + cv.flatMap { listOf("-cv", it) } + listOf("-m", model.toString(), "-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }
    /** The slice's refusal, as `plymeld slice` gives it. */
    fun refusal(out: String, vararg cv: String): String = shouldThrow<IllegalStateException> { slice(out, *cv) }.message ?: ""
    val g by lazy { slice("pillow.gcode") }
    val product by lazy { Product(ConfigStack.load(cfs, emptyList(), d), listOf(model), null) { } }
    val plan by lazy { product.decide() }
    val zBase by lazy { plan.parts.minOf { it.bounds()[2] } }

    // plan.technique.curved.top_order = "uphill" (plate 8's solid arch was rough on the half its top
    // was swept down): the pillow's top lines laid from each side up to the highest line, so within every run of the top
    // layer each line lies no lower than the one before. Control: one way (unset), one run crosses the dome and comes down it
    /**
     * The top layer's "Top surface" runs, each as the steps between its consecutive lines (a line: moves along the run's main
     * direction): how much higher each line lies than the one before it, at the same place along the lines (the mean over
     * where both reach). Not the lines' mean heights: on the dome a line through the middle runs corner to corner, so its mean
     * is lower than a shorter neighbor's though every point of it lies higher (that measure called a climb a fall).
     */
    fun topRuns(g: String): List<List<Double>> {
        val runs = ArrayList<ArrayList<DoubleArray>>()   // per run: its moves (x0, y0, z0, x1, y1, z1)
        var feature = ""; var x = 0.0; var y = 0.0; var z = 0.0; var cur: ArrayList<DoubleArray>? = null; var layer = 0; var topLayer = -1
        val byLayer = HashMap<Int, ArrayList<ArrayList<DoubleArray>>>()
        for (l in g.lines()) {
            when {
                l.startsWith(";LAYER_CHANGE") -> { layer++; cur = null }
                l.startsWith(";TYPE:") -> { feature = l.removePrefix(";TYPE:"); cur = null }
                l.startsWith("G1") -> {
                    val nx = Regex(" X(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: x
                    val ny = Regex(" Y(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: y
                    val nz = Regex(" Z(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: z
                    val ext = Regex(" E([0-9.]+)").containsMatchIn(l) && (" X" in l || " Y" in l)
                    if (ext && feature == "Top surface") {
                        if (cur == null) { cur = ArrayList(); byLayer.getOrPut(layer) { ArrayList() } += cur; topLayer = layer }
                        cur += doubleArrayOf(x, y, z, nx, ny, nz)
                    } else if (" X" in l || " Y" in l) cur = null
                    x = nx; y = ny; z = nz
                }
            }
        }
        runs += byLayer[topLayer] ?: emptyList()
        return runs.map { moves ->
            // the run's main direction: its longest move's; a line = consecutive moves within 20 deg of it
            val m0 = moves.maxBy { Math.hypot(it[3] - it[0], it[4] - it[1]) }
            val ul = Math.hypot(m0[3] - m0[0], m0[4] - m0[1]); val ux = (m0[3] - m0[0]) / ul; val uy = (m0[4] - m0[1]) / ul
            val lines = ArrayList<ArrayList<DoubleArray>>()   // per line: its points as (s along the lines, z)
            var on = false
            for (m in moves) {
                val L = Math.hypot(m[3] - m[0], m[4] - m[1]); if (L < 1e-6) continue
                val along = Math.abs((m[3] - m[0]) * ux + (m[4] - m[1]) * uy) / L > Math.cos(Math.toRadians(20.0))
                if (!along) { on = false; continue }
                if (!on) { lines += arrayListOf(doubleArrayOf(m[0] * ux + m[1] * uy, m[2])); on = true }
                lines.last() += doubleArrayOf(m[3] * ux + m[4] * uy, m[5])
            }
            fun zAt(line: List<DoubleArray>, s: Double): Double? {
                val p = line.sortedBy { it[0] }
                if (s < p.first()[0] || s > p.last()[0]) return null
                for (k in 1 until p.size) if (s <= p[k][0]) { val t = if (p[k][0] == p[k - 1][0]) 0.0 else (s - p[k - 1][0]) / (p[k][0] - p[k - 1][0]); return p[k - 1][1] + t * (p[k][1] - p[k - 1][1]) }
                return p.last()[1]
            }
            lines.zipWithNext().mapNotNull { (a, b) -> b.mapNotNull { pt -> zAt(a, pt[0])?.let { pt[1] - it } }.takeIf { it.isNotEmpty() }?.average() }
        }
    }
    // how far a run's lines come down in all, mm: each line's step below the one before (at the same place along them), summed;
    // the dome's lines step only 0.001-0.01 mm apart, so a count of steps over a threshold missed a whole descent
    fun descent(steps: List<Double>) = steps.sumOf { maxOf(0.0, -it) }
    "uphill top order: every run of the pillow's top climbs, line by line; control: one way, a run comes down the dome" {
        val up = topRuns(slice("pillow_uphill.gcode", "plan.technique.curved.top_order=uphill"))
        withClue("runs ${up.map { it.size }}") { up.sumOf { it.size } shouldBeGreaterThan 50 }
        for ((i, st) in up.withIndex()) withClue("uphill run $i, steps ${st.map { "%.4f".format(it) }}") { descent(st) shouldBeLessThan 0.01 }
        val oneWay = topRuns(g)
        withClue("one way: some run comes down; runs ${oneWay.map { it.size }}, descents ${oneWay.map { "%.3f".format(descent(it)) }}") {
            oneWay.maxOf { descent(it) } shouldBeGreaterThan 0.3 }
    }
    "the pillow sliced curved from the bed reads back clean: in warped space, by the planar checks and the curved rules" {
        val r = curvedRead(g, plan, product.result.setup, zBase)!!
        r.curvedLayers shouldBeGreaterThan 40
        r.problems.shouldBeEmpty()
        // within one layer the head goes up and down with the pillow: Z on the printing moves, several values
        val layer30 = g.split(Regex(";LAYER_CHANGE|; CHANGE_LAYER"))[30]
        layer30.lines().filter { it.startsWith("G1 ") && " E" in it }.mapNotNull { Regex(" Z([0-9.]+)").find(it)?.groupValues?.get(1) }.distinct().size shouldBeGreaterThan 20
    }
    "control: the same file read as flat layers (no re-warp) fails the planar checks" {
        val surface = ModelCheck.Surface(plan.parts, zBase, g)
        val flat = GcodeCheck.check(g, Expectations.forSetup(product.result.setup, surface::at)) + ModelCheck.check(g, plan.parts, zBase, surface = surface)
        flat.any { "printing at Z" in it } shouldBe true
    }
    "control: a nozzle angle of 3 deg, under the pillow's 4.5 deg slope, is refused" {
        refusal("cone.gcode", "hardware.tool.t1.head.nozzle.angle=3 deg") shouldContain "over the nozzle angle (3.0 deg from horizontal)"
    }
    "control: a nozzle height of 0.5 mm, under the pillow's 1 mm rise, is refused" {
        refusal("protrusion.gcode", "hardware.tool.t1.head.nozzle.height=0.5 mm") shouldContain "not under the nozzle height 0.500 mm"
    }
    // The head's rule with its outline: only the plastic under the head's rectangle counts. A 4 x 4 mm head, the
    // nozzle in its middle (reaching 2 mm each way, 2.5 with the check's 0.5 mm cells), over the pillow's steepest 4.49 deg:
    // 0.157 .. 0.196 mm higher by hand (tan 4.49 deg = 0.0785), so a 0.5 mm nozzle height, refused for the 60 x 50 head
    // above, passes.
    val smallHead = arrayOf("hardware.tool.t1.head.size.x=4 mm", "hardware.tool.t1.head.size.y=4 mm", "hardware.tool.t1.head.nozzle.x=2 mm", "hardware.tool.t1.head.nozzle.y=2 mm")
    "a 4 x 4 mm head over the pillow: a 0.5 mm nozzle height passes (the layer's whole 1 mm rise is never under the head)" {
        slice("small.gcode", *smallHead, "hardware.tool.t1.head.nozzle.height=0.5 mm").shouldContain(";LAYER_CHANGE")   // sliced, its check passed
    }
    "control: the 4 x 4 mm head with a 0.1 mm nozzle height is refused, the plastic under it 0.157 .. 0.196 mm higher" {
        val e = refusal("small01.gcode", *smallHead, "hardware.tool.t1.head.nozzle.height=0.1 mm")
        e shouldContain "not under the nozzle height 0.100 mm"
        val over = Regex("is over plastic ([0-9.]+) mm higher").findAll(e).map { it.groupValues[1].toDouble() }.max()
        over shouldBe (0.177 plusOrMinus 0.021)
    }
    "the head's outline from the config: 60 x 50 mm, the nozzle 20 mm from its -X edge and 15 from its -Y edge, reaches 20 / 40 / 15 / 35 mm" {
        val head = plymeld.core.decide.Plan.headFromConfig(plymeld.config.Models.bundled.forModel("flashforge-c5"), 0, product.result.setup, 0).toolhead(0)!!
        head.has(plymeld.machine.Toolhead.Tier.UNDERSIDE) shouldBe true
        listOf("head_minus_x", "head_plus_x", "head_minus_y", "head_plus_y").map { head[it] } shouldBe listOf(20.0, 40.0, 15.0, 35.0)
        head["tip_cone_deg"] shouldBe 45.0
        head["head.nozzle.height"] shouldBe 4.0
    }
    "control: a nozzle outside the head (head.nozzle.x 70 mm in a 60 mm head) is refused" {
        val p = Product(ConfigStack.load(cfs, listOf("hardware.tool.t1.head.nozzle.x=70 mm"), d), listOf(model), null) { }
        shouldThrow<IllegalArgumentException> { plymeld.core.decide.Plan.headFromConfig(plymeld.config.Models.bundled.forModel("flashforge-c5"), 0, p.result.setup, 0) }
            .message!! shouldContain "is outside the head"
    }
    "control: a thinnest layer of 0.19 mm, above the 0.177 mm the band prints at the pillow's edge, is refused" {
        refusal("thin.gcode", "hardware.tool.t1.layer.height.min=0.19 mm") shouldContain "under the tool's thinnest layer 0.190"
    }
    "control: one printed line lifted 0.05 mm off its layer's surface is found" {
        val lines = g.lines().toMutableList()
        val layer30 = lines.indices.filter { lines[it].startsWith(";LAYER_CHANGE") || lines[it].startsWith("; CHANGE_LAYER") }[29]
        val k = (layer30 until lines.size).first { Regex("^G1 X\\S+ Y\\S+ Z\\S+ E").containsMatchIn(lines[it]) }
        val z = Regex(" Z([0-9.]+)").find(lines[k])!!.groupValues[1].toDouble()
        lines[k] = lines[k].replace(Regex(" Z[0-9.]+"), " Z" + "%.3f".format(java.util.Locale.ROOT, z + 0.05))
        val r = curvedRead(lines.joinToString("\n"), plan, product.result.setup, zBase)!!
        // the lifted end ends that line and starts the next: two line ends, the first named; the distance the check reads for a
        // point lifted 0.05 mm is not always 0.0500 (0.0495 on this line, on the pillow's slope), so it is read as a number
        println("tampered line ${k + 1}: ${lines[k]}; problems: ${r.problems.take(4)}")
        val found = r.problems.firstNotNullOfOrNull { p -> Regex("2 line end\\(s\\) off the layer's surface, up to ([0-9.]+) mm \\(line ${k + 1},").find(p)?.groupValues?.get(1)?.toDouble() }
        (found != null && Math.abs(found - 0.05) <= 0.002) shouldBe true
    }
    "control: with no nozzle angle and height the band is refused" {
        val s = plymeld.model.SliceSettings.of(product.result.setup, plymeld.model.Context(0))
        val r = CurvedCheck.read(g, plan.parts, zBase, s.firstLayerHeightUm / 1000.0, CurvedCheck.Limits(0.08, 0.3, s.resolutionUm / 1000.0, null))
        r.problems.any { "need the nozzle's angle and height" in it } shouldBe true
    }

    // A plate printed one object at a time (PlateCheck): the pillow curved and a second one flat. The check reads each
    // object's run of layers against that object alone, the curved one in its own warp. Controls that must fail: the plate
    // read as a whole (each layer lacks the other object's walls), one object's run cut out (absent), and both runs over
    // one object (the second printed where the first is).
    val plateLock = """
        [plan]
        objects.printing = "one_at_a_time"
        [plan.object."pillow.stl"]
        band.1.technique = "curved"
        band.1.from = "0 mm"
        [plan.object."flat.stl"]
        band.1.technique = "flat"
        band.1.heights = "one"
        band.1.layer.height = "0.2 mm"
        band.1.from = "0 mm"
        [plan.technique.flat]
        layer.height = "0.2 mm"
    """.trimIndent()
    val pd: Path = Files.createTempDirectory("plymeld-plate").also { p ->
        Files.writeString(p.resolve("hw.toml"), hardware); Files.writeString(p.resolve("plate.toml"), plateLock)
        Files.writeString(p.resolve("pillow.stl"), pillowStl()); Files.writeString(p.resolve("flat.stl"), pillowStl())
    }
    val plateModels = listOf(pd.resolve("pillow.stl"), pd.resolve("flat.stl"))
    val plateCfs = listOf("hw.toml", "plate.toml")
    val plateG by lazy {
        SliceCommand().parse(plateCfs.flatMap { listOf("-cf", pd.resolve(it).toString()) } + plateModels.flatMap { listOf("-m", it.toString()) } + listOf("-o", pd.resolve("plate.gcode").toString()))
        Files.readString(pd.resolve("plate.gcode"))
    }
    val plateProduct by lazy { Product(ConfigStack.load(plateCfs, emptyList(), pd), plateModels, null) { } }
    val platePlan by lazy { plateProduct.decide() }
    fun plateCheck(text: String): plymeld.core.check.PlateCheck.Result {
        val tools = plateProduct.partTools(platePlan); var at = 0
        val objs = platePlan.layout.placed.withIndex().map { (j, pl) ->
            plymeld.core.check.PlateCheck.Obj(pl.name, pl.parts, tools.subList(at, at + pl.parts.size).also { at += pl.parts.size }, curvedSpec(platePlan, plateProduct.result.setup, j))
        }
        return plymeld.core.check.PlateCheck.oneAtATime(text, objs, platePlan.parts.minOf { it.bounds()[2] })
    }
    /** The file's runs: (first, last) line indices, by the check's own split. */
    fun runsOf(text: String) = plymeld.core.check.PlateCheck.runs(text.lines())

    "a plate of a curved and a flat pillow, one at a time, reads back clean: each object's run against itself" {
        platePlan.layout.order shouldBe plymeld.core.decide.ObjectOrder.ONE_AT_A_TIME
        runsOf(plateG).size shouldBe 2
        val r = plateCheck(plateG)
        r.problems.shouldBeEmpty()
        r.curvedLayers shouldBeGreaterThan 40
    }
    "control: the same plate read as a whole, every layer against both objects, is refused (the other object's walls missing)" {
        val zb = platePlan.parts.minOf { it.bounds()[2] }
        val flatParts = platePlan.parts
        ModelCheck.check(plateG, flatParts, zb).any { "has no wall along" in it } shouldBe true
    }
    "control: one object's run cut out of the file is an object not printed" {
        val lines = plateG.lines(); val second = runsOf(plateG)[1]
        val cut = (lines.subList(0, second.first) + lines.subList(second.last + 1, lines.size)).joinToString("\n")
        plateCheck(cut).problems.any { "nothing of it printed" in it } shouldBe true
    }
    "control: the second object's run printed over the first one is refused (the first printed twice, the second not at all)" {
        val lines = plateG.lines(); val runs = runsOf(plateG)
        val dx = platePlan.layout.placed[0].cx - platePlan.layout.placed[1].cx; val dy = platePlan.layout.placed[0].cy - platePlan.layout.placed[1].cy
        val moved = lines.mapIndexed { i, l ->
            if (i !in runs[1] || !(l.startsWith("G1 ") || l.startsWith("G0 "))) l
            else l.replace(Regex(" X(-?[0-9.]+)")) { m -> " X%.3f".format(java.util.Locale.ROOT, m.groupValues[1].toDouble() + dx) }
                .replace(Regex(" Y(-?[0-9.]+)")) { m -> " Y%.3f".format(java.util.Locale.ROOT, m.groupValues[1].toDouble() + dy) }
        }.joinToString("\n")
        val p = plateCheck(moved).problems
        p.any { "printed in 2 runs" in it } shouldBe true
        p.any { "nothing of it printed" in it } shouldBe true
    }

    // The same plate printed TOGETHER: the curved pillow's layers merged by height with the flat one's,
    // each object read against itself by the file's object markers, the curved one in its own warp, the head's rule over
    // both objects' plastic. The slice itself runs the check (it fails on a problem). Control: the file read as one model,
    // every layer flat against both objects, is refused.
    val td: Path = Files.createTempDirectory("plymeld-plate-together").also { p ->
        Files.writeString(p.resolve("hw.toml"), hardware); Files.writeString(p.resolve("plate.toml"), plateLock.replace("one_at_a_time", "together"))
        Files.writeString(p.resolve("pillow.stl"), pillowStl()); Files.writeString(p.resolve("flat.stl"), pillowStl())
    }
    val togetherModels = listOf(td.resolve("pillow.stl"), td.resolve("flat.stl"))
    "a plate of a curved and a flat pillow printed together reads back clean: each object's lines against itself, the curved one in its warp" {
        SliceCommand().parse(plateCfs.flatMap { listOf("-cf", td.resolve(it).toString()) } + togetherModels.flatMap { listOf("-m", it.toString()) } + listOf("-o", td.resolve("plate.gcode").toString()))
        val g = Files.readString(td.resolve("plate.gcode"))
        val product = Product(ConfigStack.load(plateCfs, emptyList(), td), togetherModels, null) { }
        val plan = product.decide()
        plan.layout.order shouldBe plymeld.core.decide.ObjectOrder.TOGETHER
        // the objects' layers are merged: the file goes up once, where the one-at-a-time plate has two runs
        runsOf(g).size shouldBe 1
        val tools = product.partTools(plan); var at = 0
        val objs = plan.layout.placed.withIndex().map { (j, pl) ->
            plymeld.core.check.PlateCheck.Obj(pl.name, pl.parts, tools.subList(at, at + pl.parts.size).also { at += pl.parts.size }, curvedSpec(plan, product.result.setup, j))
        }
        val zb = plan.parts.minOf { it.bounds()[2] }
        val r = plymeld.core.check.PlateCheck.together(g, objs, zb)
        r.problems.shouldBeEmpty()
        r.curvedLayers shouldBeGreaterThan 40
        ModelCheck.check(g, plan.parts, zb).any { "has no wall along" in it } shouldBe true
    }

    // Layers that follow the underside: a ring arch, the ring between two circles 3 mm apart about one center,
    // the bed cutting it so the inner circle is a 4 deg arc over 50 mm (0.873 mm high at the middle, by hand: 25 tan 2 deg),
    // 5 mm wide, hollow under it. Sliced so: every layer rises 0.873 mm; the first over the hollow is bridged (bridge speed,
    // the fan on); the check passes. Controls: the file read as top-following layers, a nozzle height under the rise, a
    // line lifted off its layer.
    /** The arch spanning X, or [turned] a quarter turn to span Y (x, y swapped, the winding kept by swapping two corners). */
    fun archStl(turned: Boolean = false): String {
        val r = 25.0 / Math.sin(Math.toRadians(4.0)); val dd = r * Math.cos(Math.toRadians(4.0)); val ro = r + 3.0
        val half = Math.sqrt(ro * ro - dd * dd); val n = 300
        fun inner(x: Double) = if (Math.abs(x) >= 25.0) 0.0 else Math.sqrt(r * r - x * x) - dd
        fun outer(x: Double) = maxOf(Math.sqrt(ro * ro - x * x) - dd, 0.0)
        val sb = StringBuilder("solid arch\n")
        fun v(x: Double, y: Double, z: Double) = if (turned) "vertex $y $x $z\n" else "vertex $x $y $z\n"
        fun tri(a: String, b: String, c: String) { sb.append("facet normal 0 0 0\nouter loop\n").append(a)
            .append(if (turned) c else b).append(if (turned) b else c).append("endloop\nendfacet\n") }
        for (i in 0 until n) {
            val a = -half + 2 * half * i / n; val b = -half + 2 * half * (i + 1) / n
            val ta = outer(a); val tb = outer(b); val ba = inner(a); val bb = inner(b)
            tri(v(a, -2.5, ta), v(b, -2.5, tb), v(b, 2.5, tb)); tri(v(a, -2.5, ta), v(b, 2.5, tb), v(a, 2.5, ta))
            tri(v(a, -2.5, ba), v(b, 2.5, bb), v(b, -2.5, bb)); tri(v(a, -2.5, ba), v(a, 2.5, ba), v(b, 2.5, bb))
            tri(v(a, -2.5, ba), v(b, -2.5, bb), v(b, -2.5, tb)); tri(v(a, -2.5, ba), v(b, -2.5, tb), v(a, -2.5, ta))
            tri(v(a, 2.5, ba), v(b, 2.5, tb), v(b, 2.5, bb)); tri(v(a, 2.5, ba), v(a, 2.5, ta), v(b, 2.5, tb))
        }
        return sb.append("endsolid arch\n").toString()
    }
    val archLock = """
        [plan.object."arch.stl"]
        pose.label = "as given (-Z down)"
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "underside"
        band.1.from = "0 mm"
        [plan.technique.flat]
        layer.height = "0.2 mm"
    """.trimIndent()
    val ad: Path = Files.createTempDirectory("plymeld-arch").also { p ->
        Files.writeString(p.resolve("hw.toml"), hardware); Files.writeString(p.resolve("arch.toml"), archLock); Files.writeString(p.resolve("arch.stl"), archStl())
    }
    val archCfs = listOf("hw.toml", "arch.toml")
    fun archSlice(out: String, vararg cv: String): String {
        SliceCommand().parse(archCfs.flatMap { listOf("-cf", ad.resolve(it).toString()) } + cv.flatMap { listOf("-cv", it) } + listOf("-m", ad.resolve("arch.stl").toString(), "-o", ad.resolve(out).toString()))
        return Files.readString(ad.resolve(out))
    }
    val archG by lazy { archSlice("arch.gcode") }
    val archProduct by lazy { Product(ConfigStack.load(archCfs, emptyList(), ad), listOf(ad.resolve("arch.stl")), null) { } }
    val archPlan by lazy { archProduct.decide() }
    val archZBase by lazy { archPlan.parts.minOf { it.bounds()[2] } }
    /** Each layer's printed Z range (from the g-code). */
    fun layerRises(g: String): List<Double> {
        val out = ArrayList<Double>(); var lo = 9e9; var hi = -9e9; var started = false
        for (l in g.lines()) {
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { if (started && hi > lo) out += hi - lo; lo = 9e9; hi = -9e9; started = true; continue }
            if (started && l.startsWith("G1 ") && " E" in l && !l.contains(" E-")) Regex(" Z([0-9.]+)").find(l)?.let { val z = it.groupValues[1].toDouble(); lo = minOf(lo, z); hi = maxOf(hi, z) }
        }
        if (hi > lo) out += hi - lo
        return out
    }

    "an arch sliced with layers following its underside reads back clean; every layer rises 0.873 mm" {
        val r = curvedRead(archG, archPlan, archProduct.result.setup, archZBase)!!
        r.problems.shouldBeEmpty()
        r.curvedLayers shouldBeGreaterThan 10
        val rises = layerRises(archG)
        rises.size shouldBeGreaterThan 10
        rises.all { Math.abs(it - 0.873) < 0.005 } shouldBe true
    }
    "the first layer over the hollow is a bridge: lines named Bridge over the hollow, the fan on for them" {
        val first = archG.substringAfter("; CHANGE_LAYER").substringBefore("; CHANGE_LAYER")
        first.contains(";TYPE:Bridge") || first.contains("; FEATURE: Bridge") shouldBe true
        first.lines().any { it.startsWith("M106 S255") } shouldBe true
    }
    "control: the same file read as layers following the TOP is refused (its lines are off those layers)" {
        val spec = curvedSpec(archPlan, archProduct.result.setup, 0)!!
        val top = CurvedCheck.read(archG, archPlan.parts, archZBase, CurvedCheck.Spec(0.28, spec.limits, CurvedCheck.Follows.TOP))
        top.problems.any { "off the layer's surface" in it } shouldBe true
    }
    "control: a nozzle height of 0.8 mm, under the 0.873 mm each layer rises, is refused" {
        val e = shouldThrow<IllegalStateException> { archSlice("low.gcode", "hardware.tool.t1.head.nozzle.height=0.8 mm") }
        e.message!! shouldContain "not under the nozzle height 0.800 mm"
    }

    // The first layer over the hollow (item 6): bridged in the direction its lines land on the bed at both ends
    // (along the span, onto the feet; across the 5 mm width they would end in the air), at the nozzle's width; and the squish
    // compensation (the bundled PEI's 0.1 mm) only where it lies on the bed. The arch turned a quarter turn is the control
    // for the direction: the same rule must then lay the lines along Y.
    class Seg(val feature: String, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double)
    /** The first layer's extruding moves. */
    fun firstLayer(g: String): List<Seg> {
        val out = ArrayList<Seg>(); var feature = ""; var layer = -1
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        val w = Regex("([XYZE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; if (layer > 0) break; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(":").trim(); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z
            if (layer == 0 && (m["E"] ?: 0.0) > 0 && !x.isNaN()) out += Seg(feature, x, y, z, nx, ny, nz)
            x = nx; y = ny; z = nz
        }
        return out
    }
    /** The first layer's height where it lies on the bed (its lowest printed Z). */
    fun bedZ(segs: List<Seg>) = segs.minOf { minOf(it.z0, it.z1) }
    /** The bridge lines over the hollow (both ends 0.05 mm above the first layer on the bed): their length and its X share. */
    fun overHollow(g: String): Pair<Double, Double> {
        val first = firstLayer(g); val bed = bedZ(first)
        val b = first.filter { it.feature == "Bridge" && minOf(it.z0, it.z1) > bed + 0.05 }
        val len = b.sumOf { Math.hypot(it.x1 - it.x0, it.y1 - it.y0) }
        return len to b.sumOf { Math.abs(it.x1 - it.x0) } / len
    }
    val turned: Path = Files.createTempDirectory("plymeld-arch-turned").also { p ->
        Files.writeString(p.resolve("hw.toml"), hardware); Files.writeString(p.resolve("arch.toml"), archLock); Files.writeString(p.resolve("arch.stl"), archStl(true))
    }
    val turnedG by lazy {
        SliceCommand().parse(archCfs.flatMap { listOf("-cf", turned.resolve(it).toString()) } + listOf("-m", turned.resolve("arch.stl").toString(), "-o", turned.resolve("t.gcode").toString()))
        Files.readString(turned.resolve("t.gcode"))
    }

    "the bridge over the hollow runs along the span, from foot to foot" {
        val (len, xShare) = overHollow(archG)
        len shouldBeGreaterThan 100.0                 // there is a bridge over the hollow (about 50 mm x 5 mm of it)
        xShare shouldBeGreaterThan 0.95
    }
    "control: the arch turned a quarter turn is bridged along Y" {
        val (len, xShare) = overHollow(turnedG)
        len shouldBeGreaterThan 100.0
        xShare shouldBeLessThan 0.05
    }
    /**
     * How far the first layer's outer wall reaches from the arch's middle line across its width: over the hollow (both ends
     * 0.05 mm above the bed), on the feet (both ends on the bed). The middle line from the first layer's extent.
     */
    fun wallReach(g: String): Pair<Double, Double> {
        val all = firstLayer(g); val bed = bedZ(all)
        val walls = all.filter { it.feature == "Outer wall" }
        val cy = (all.minOf { minOf(it.y0, it.y1) } + all.maxOf { maxOf(it.y0, it.y1) }) / 2
        fun reach(segs: List<Seg>) = segs.maxOf { maxOf(Math.abs(it.y0 - cy), Math.abs(it.y1 - cy)) }
        return reach(walls.filter { minOf(it.z0, it.z1) > bed + 0.05 }) to reach(walls.filter { maxOf(it.z0, it.z1) < bed + 0.005 })
    }
    "the squish compensation shrinks the first layer's edge on the feet only: over the hollow it reaches 0.1 mm further" {
        val (hollow, foot) = wallReach(archG)
        (hollow - foot) shouldBe (0.1 plusOrMinus 0.03)
    }
    "control: with no squish compensation the wall reaches as far over the hollow as on the feet" {
        val (hollow, foot) = wallReach(archSlice("nosquish.gcode", "hardware.plate.pei.first_layer.squish=0 mm"))
        (hollow - foot) shouldBe (0.0 plusOrMinus 0.02)
    }
})
