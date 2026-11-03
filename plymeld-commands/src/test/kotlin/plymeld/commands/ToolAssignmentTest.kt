package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import java.nio.file.Files
import java.nio.file.Path

/**
 * A part prints with the tool the machine's loadout gives its filament (the g-code's T = the tool's index - 1), from the
 * config to the g-code, and the pre-print check refuses a g-code that prints a part with another tool or whose header
 * credits the wrong filament. The bug: a Benchy assigned filament 3 (tool index 3, T2) printed with T0,
 * its header crediting filament 1, and the check passed it.
 */
class ToolAssignmentTest : StringSpec({
    /** An ASCII STL box [x] x [y] x [z] mm, corner at the origin. */
    fun box(x: Double, y: Double, z: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return buildString {
            append("solid box\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid box\n")
        }
    }
    // a four-tool changer (a Creator 5 description, for its file header); tools t1..t4 of index 1..4
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
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.tool.t2]
        like = "t1"
        index = "2"
        [hardware.tool.t3]
        like = "t1"
        index = "3"
        [hardware.tool.t4]
        like = "t1"
        index = "4"
        [hardware.filament.red]
        like = "pla-generic"
        color = "#FF0000"
    """.trimIndent()
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-tools").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }
    fun loadout(vararg tools: Pair<String, String>) = "[hardware.machine.m.loadout]\nplate = \"pei\"\n" + tools.joinToString("") { (t, f) -> "tool.$t.filament = \"$f\"\n" }
    fun lock(obj: String, part: String, n: Int) = "[model.object.\"$obj\".part.$part]\nslot = \"$n\"\n"
    /** Slices [models] with the config files [cfs] in [d] (the slice runs the pre-print check and fails on a problem). */
    fun slice(d: Path, cfs: List<String>, models: List<Path>, out: String): String {
        SliceCommand().parse(cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + models.flatMap { listOf("-m", it.toString()) } + listOf("-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }
    /** The tools (T numbers) that print, read from the g-code: the T command in force at each extrusion. */
    fun printingTools(g: String): Set<Int> {
        var t = 0; val out = HashSet<Int>()
        for (l in g.lines()) {
            val c = l.substringBefore(';').trim()
            if (Regex("T\\d+").matches(c)) t = c.substring(1).toInt()
            else if (c.startsWith("G1 ") && Regex(" [XY]").containsMatchIn(c) && Regex(" E[0-9.]+").find(c)?.value?.drop(2)?.toDouble()?.let { it > 0 } == true) out += t
        }
        return out
    }
    /** The pre-print check of [g] as `plymeld slice` runs it, with the config's own reading of the part tools. */
    fun problems(d: Path, cfs: List<String>, models: List<Path>, g: String): List<String> {
        val pr = Product(ConfigStack.load(cfs, emptyList(), d), models, null) { }
        val plan = pr.decide()
        val partTools = pr.partTools(plan)
        val zBase = plan.parts.minOf { it.bounds()[2] }
        if (plan.layout.placed.size > 1) {
            // a plate printed together: each object's lines against that object (PlateCheck), as the slice does
            var at = 0
            val objs = plan.layout.placed.map { pl -> plymeld.core.check.PlateCheck.Obj(pl.name, pl.parts, partTools.subList(at, at + pl.parts.size).also { at += pl.parts.size }) }
            val r = plymeld.core.check.PlateCheck.together(g, objs, zBase)
            return GcodeCheck.check(g, Expectations.forSetup(pr.result.setup, ModelCheck.Surfaces(r.surfaces)::at, partTools.toSet(), partTools.toSet())) + r.problems
        }
        val surface = ModelCheck.Surface(plan.parts, zBase, g)
        return GcodeCheck.check(g, Expectations.forSetup(pr.result.setup, surface::at, partTools.toSet(), partTools.toSet())) +
            ModelCheck.check(g, plan.parts, zBase, surface = surface, partTools = partTools)
    }
    /** [g] with every T number [a] written as [b] (the T commands and the T words of M104 / M109), comments left as they are. */
    fun renumber(g: String, a: Int, b: Int) = g.lines().joinToString("\n") { l ->
        val c = l.substringBefore(';'); c.replace(Regex("(^|\\s)T$a(?![0-9])"), "$1T$b") + l.substring(c.length)
    }

    "a part on filament 3 (the loadout's tool of index 3) prints with T2 and is credited to filament 3; controls: printed on T0, or the header crediting filament 1, are refused" {
        val d = dir("hw.toml" to hardware, "lo.toml" to loadout("t3" to "pla-generic") + lock("box.stl", "part-1", 3), "box.stl" to box(20.0, 20.0, 3.0))
        val cfs = listOf("hw.toml", "lo.toml"); val models = listOf(d.resolve("box.stl"))
        val g = slice(d, cfs, models, "t3.gcode")
        printingTools(g) shouldBe setOf(2)
        g.lines().filter { Regex("^T\\d+$").matches(it) } shouldBe listOf("T2")
        g.lines().single { it.startsWith("M109") } shouldContain " T2"
        // the header (filament id n = T(n-1)): filament 3 used, filament 1 not
        val used = g.lines().first { it.startsWith("; filament used [mm] =") }.substringAfter('=').split(',').map { it.trim().toDouble() }
        (used[2] > 0) shouldBe true
        used.filterIndexed { i, _ -> i != 2 }.all { it == 0.0 } shouldBe true
        problems(d, cfs, models, g).shouldBeEmpty()
        // control: the same print on T0 (the bug's g-code): GcodeCheck and ModelCheck both refuse it
        val onT0 = renumber(g, 2, 0)
        printingTools(onT0) shouldBe setOf(0)
        val p0 = problems(d, cfs, models, onT0)
        p0.any { "printed by T0 (tool 1), which the config's loadout gives this print nothing to print" in it } shouldBe true
        p0.any { "T2 (tool 3) prints nothing" in it } shouldBe true
        p0.any { "printed by the wrong tool: T0 (tool 1)" in it && "assigned T2 (tool 3)" in it } shouldBe true
        // ... and its header, still crediting filament 3, no longer matches what each tool pushes
        p0.any { "the header credits filament 1 with 0.00 mm" in it } shouldBe true
        // control: the right tool, but the header crediting filament 1 with filament 3's use (the bug's header)
        val hdr = g.lines().joinToString("\n") { l ->
            if (l.startsWith("; filament used [mm] =")) "; filament used [mm] = " + listOf(used[2], used[1], used[0], used[3]).joinToString(", ") { "%.2f".format(java.util.Locale.ROOT, it) }
            else l
        }
        val ph = problems(d, cfs, models, hdr)
        ph.any { "the header credits filament 1 with" in it } shouldBe true
        ph.any { "the header credits filament 3 with 0.00 mm" in it } shouldBe true
        ph.size shouldBe 2
        val used3 = g.lines().first { it.startsWith("; <filament id=\"3\"") }
        val swapped = g.lines().joinToString("\n") { l -> when {
            l.startsWith("; <filament id=\"3\"") -> l.replace(Regex("used_m=\"[0-9.]+\""), "used_m=\"0.00\"")
            l.startsWith("; <filament id=\"1\"") -> l.replace(Regex("used_m=\"[0-9.]+\""), Regex("used_m=\"[0-9.]+\"").find(used3)!!.value)
            else -> l } }
        problems(d, cfs, models, swapped).any { "the header's filament 1 says" in it } shouldBe true
    }

    "a part on filament 1 prints with T0; control: printed on T2 is refused" {
        val d = dir("hw.toml" to hardware, "lo.toml" to loadout("t1" to "pla-generic"), "box.stl" to box(20.0, 20.0, 3.0))
        val cfs = listOf("hw.toml", "lo.toml"); val models = listOf(d.resolve("box.stl"))
        val g = slice(d, cfs, models, "t1.gcode")
        printingTools(g) shouldBe setOf(0)
        g.lines().single { it.startsWith("M109") } shouldContain " T0"
        problems(d, cfs, models, g).shouldBeEmpty()
        problems(d, cfs, models, renumber(g, 0, 2)).any { "printed by the wrong tool: T2 (tool 3)" in it && "assigned T0 (tool 1)" in it } shouldBe true
    }

    "a plate of two objects on filament 3 prints with T2 (the plate's path, Job)" {
        // no layer height locked: printed together, each box keeps its own decided layers, merged by
        // height, and the slice's pre-print check reads each object's lines against that object. Each box weighs the goals
        // its own way, so they decide different layers (with equal weights both take the same adaptive layers)
        val d = dir("hw.toml" to hardware, "lo.toml" to loadout("t3" to "pla-generic") + lock("a.stl", "part-1", 3) + lock("b.stl", "part-1", 3) +
            "[job.object.\"a.stl\".priorities]\nquality = \"10\"\n[job.object.\"b.stl\".priorities]\nspeed = \"10\"\n",
            "a.stl" to box(20.0, 20.0, 3.0), "b.stl" to box(15.0, 15.0, 3.0))
        val cfs = listOf("hw.toml", "lo.toml"); val models = listOf(d.resolve("a.stl"), d.resolve("b.stl"))
        val g = slice(d, cfs, models, "plate.gcode")
        printingTools(g) shouldBe setOf(2)
        problems(d, cfs, models, g).shouldBeEmpty()
        val pr = Product(ConfigStack.load(cfs, emptyList(), d), models, null) { }
        val plan = pr.decide()
        plan.layout.order shouldBe plymeld.core.decide.ObjectOrder.TOGETHER
        plan.decisions.filter { it.value is plymeld.core.decide.TechniquePlan }.map { it.value as plymeld.core.decide.TechniquePlan }.distinct().size shouldBe 2
        problems(d, cfs, models, renumber(g, 2, 0)).shouldNotBeEmpty()
        // control: the plate read as ONE model, every object's outline on every layer (what once refused such a plate),
        // is refused: the boxes' layers are at different heights
        ModelCheck.check(g, plan.parts, plan.parts.minOf { it.bounds()[2] }, partTools = pr.partTools(plan)).shouldNotBeEmpty()
    }

    "two parts on filaments 3 and 4 print with T2 and T3, starting with the first one printed; control: the tools swapped is refused part by part" {
        var root = Path.of("").toAbsolutePath()
        while (!Files.isDirectory(root.resolve("test-models"))) root = root.parent ?: error("test-models/ not found")
        val model = root.resolve("test-models/3DBenchy-dual.3mf")
        // the first layer locked at 0.28 mm: with the bundled 0.2 mm the check refuses this model's walls at z 10.1, 10.4 and
        // 24.5 (not a tool question, left open), which would hide what this test is about.
        // The pose locked as given, the model centered: the pose search over this 262k-triangle model takes the 512 MB test JVM to
        // its limit; which tool prints which part does not depend on the pose
        val d = dir("hw.toml" to hardware, "lo.toml" to loadout("t3" to "pla-generic", "t4" to "red") +
            lock("3DBenchy-dual", "3DBenchy-dual-A", 3) + lock("3DBenchy-dual", "3DBenchy-dual-B", 4) +
            "[plan.mechanism.adhesion.first_layer]\nheight = \"0.28 mm\"\n[plan]\npose = \"as-given\"\nplacement = \"center\"\n")
        val cfs = listOf("hw.toml", "lo.toml"); val models = listOf(model)
        val g = slice(d, cfs, models, "dual.gcode")
        printingTools(g) shouldBe setOf(2, 3)
        // the start template selects and heats the tool of the first line printed: no T0, and no change before the first line
        val tLines = g.lines().filter { Regex("^T\\d+$").matches(it) }
        tLines.none { it == "T0" || it == "T1" } shouldBe true
        val lines = g.lines()
        val firstLayer = lines.indexOfFirst { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") }
        val firstPrint = (firstLayer until lines.size).first { lines[it].startsWith("G1 X") && Regex(" E[0-9.]+").containsMatchIn(lines[it]) }
        lines.subList(0, firstPrint).count { Regex("^T\\d+$").matches(it) } shouldBe 1
        problems(d, cfs, models, g).shouldBeEmpty()
        // control: T2 and T3 swapped: both tools are the print's, so only the per-part rule (ModelCheck) can see it
        val swapped = renumber(renumber(renumber(g, 2, 9), 3, 2), 9, 3)
        printingTools(swapped) shouldBe setOf(2, 3)
        val ps = problems(d, cfs, models, swapped)
        ps.any { "printed by the wrong tool" in it } shouldBe true
        ps.none { "which the config's loadout gives this print nothing" in it } shouldBe true
    }
})
