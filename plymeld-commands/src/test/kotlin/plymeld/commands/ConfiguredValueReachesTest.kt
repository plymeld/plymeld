package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.commands.TechniqueSamples.box
import plymeld.commands.TechniqueSamples.stl
import plymeld.commands.TechniqueSamples.strip
import plymeld.commands.TechniqueSamples.threeMf
import plymeld.config.ConfigStack
import plymeld.core.Job
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * A configured value reaches the code that should use it, through the whole path a user takes: config file -> setup -> plan
 * -> slice (-> pre-print check). Written after a defect (the configured head outline never reached the object
 * order, because the order got the bundled machine description and the order's own test set the head directly with
 * `withHead()`): every test here sets its value ONLY in a config file and reads the effect only from what plymeld produces
 * (the plan's decisions, the g-code, a refusal), with a control that fails if the value is ignored.
 *
 * The tests marked "fixed" each guard a bug that a code review found: each failed on the code that had it, its control
 * passing. A new known bug may be gated, run with an environment variable, and ungated with its fix.
 */
class ConfiguredValueReachesTest : StringSpec({

    /** A four-tool Creator 5 description (its g-code dialect and bundled clearance radius, 92 mm); tools [tools] as given. */
    fun hardware(loadout: String, tools: String) = """
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
        gcode.pause = "M25"
        [hardware.machine.m.loadout]
        plate = "pei"
    """.trimIndent() + "\n" + loadout + "\n" + tools + """

        [hardware.filament.fa]
        like = "pla-generic"
        color = "#FF0000"
        [hardware.filament.fb]
        like = "pla-generic"
        color = "#0000FF"
    """.trimIndent()

    /** A tool of [index] with the basic values; [head] adds the head's shape lines (empty: none). */
    fun tool(key: String, index: Int, head: String) = """
        [hardware.tool.$key]
        machine = "m"
        index = "$index"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
    """.trimIndent() + "\n" + head + "\n"

    /** The head's shape (as the user's Creator 5 config gives it), with the nozzle's height [heightMm]. */
    fun head(heightMm: Double) = "head.nozzle.angle = \"45 deg\"\nhead.nozzle.height = \"$heightMm mm\"\nhead.size.x = \"90 mm\"\nhead.size.y = \"75 mm\"\nhead.nozzle.x = \"70 mm\"\nhead.nozzle.y = \"40 mm\""

    /** Every search locked so only the value under test can change the result. */
    val locks = """
        [method.search]
        log.enabled = "false"
        [plan]
        pose = "as-given"
        placement = "as-given"
        bond_cooling = "off"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
    """.trimIndent()

    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-reaches").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }
    fun product(d: Path, cfs: List<String>, models: List<Path>, values: List<String> = emptyList()) =
        Product(ConfigStack.load(cfs.map { d.resolve(it).toString() }, values, d), models, null) { }
    fun slice(d: Path, cfs: List<String>, models: List<Path>, out: String) {
        SliceCommand().parse(cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + models.flatMap { listOf("-m", it.toString()) } + listOf("-o", d.resolve(out).toString()))
    }

    // A ramp (LiftedBridgeTest's shape, a 3 mm rise): pillars 9 and 6 mm high, 40 mm apart, the tall one on the -X side, a
    // strip from one to the other, placed on the bed's middle. The strip's low end prints while the tall pillar's top, 3 mm
    // higher and 45-53 mm towards -X, is under the head (reaching 70 mm towards -X): the pre-print check refuses that when the
    // nozzle height is under 3 mm.
    fun ramp(d: Path): Path = d.resolve("ramp.3mf").also { p ->
        threeMf(p, "ramp", listOf(
            "supports" to box(97.0, 120.0, 0.0, 105.0, 130.0, 9.0) + box(145.0, 120.0, 0.0, 153.0, 130.0, 6.0),
            "strip" to strip(listOf(97.0 to 9.0, 105.0 to 9.0, 145.0 to 6.0, 153.0 to 6.0)).map { t -> DoubleArray(9) { if (it % 3 == 1) t[it] + 125.0 else t[it] } }))
    }
    fun rampLock(supportsSlot: Int, stripSlot: Int) = "[model.object.ramp.part.ramp_supports]\nslot = \"$supportsSlot\"\n[model.object.ramp.part.ramp_strip]\nslot = \"$stripSlot\"\n" +
        "[plan.object.ramp]\nband.1.technique = \"bridge\"\n"

    "the lifted bridge's head check reads the nozzle height of the tool that prints it, from the config: on tool 3 with 2 mm it is refused; control: the same plate on tool 1 (4 mm) passes" {
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"\ntool.t3.filament = \"fa\"", tool("t1", 1, head(4.0)) + tool("t3", 3, head(2.0))), "locks.toml" to locks)
        val model = ramp(d)
        Files.writeString(d.resolve("on1.toml"), rampLock(1, 1)); Files.writeString(d.resolve("on3.toml"), rampLock(3, 3))
        fun run(lock: String, out: String): Pair<Int, String> {
            val lines = ArrayList<String>()
            val status = Commands.invoke(listOf("slice", "-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("locks.toml").toString(), "-cf", d.resolve(lock).toString(),
                "-m", model.toString(), "-o", d.resolve(out).toString()), lines::add)
            return status to lines.joinToString("\n")
        }
        val (s1, o1) = run("on1.toml", "on1.gcode")
        withClue("control, tool 1 (4 mm):\n$o1") { s1 shouldBe 0 }
        val (s3, o3) = run("on3.toml", "on3.gcode")
        withClue("tool 3 (2 mm):\n$o3") {
            (s3 != 0) shouldBe true
            // the refusal names tool 3's own height, never tool 1's: the value came from the tool that prints
            o3 shouldContain "not under the nozzle height 2.000 mm"
            o3 shouldNotContain "nozzle height 4.000 mm"
        }
    }

    "a lifted bridge whose bridge part prints with a tool that has no head shape is refused by the technique screen (once it screened only the object's first part's tool)" {
        // control first: every part on tool 3 (no head values) is refused, as it should be
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"\ntool.t3.filament = \"fa\"", tool("t1", 1, head(4.0)) + tool("t3", 3, "")), "locks.toml" to locks)
        val model = ramp(d)
        Files.writeString(d.resolve("all3.toml"), rampLock(3, 3)); Files.writeString(d.resolve("mixed.toml"), rampLock(1, 3))
        withClue("control: every part on tool 3") {
            shouldThrow<Exception> { product(d, listOf("hw.toml", "locks.toml", "all3.toml"), listOf(model)).decide() }.message!! shouldContain "no head.nozzle.angle and head.nozzle.height for toolhead 3"
        }
        // the bug: supports on tool 1 (the object's tool: its first part's), strip on tool 3: tool 3's missing cone is never asked for
        withClue("supports on tool 1, the strip on tool 3") {
            shouldThrow<Exception> { product(d, listOf("hw.toml", "locks.toml", "mixed.toml"), listOf(model)).decide() }.message!! shouldContain "no head.nozzle.angle and head.nozzle.height for toolhead 3"
        }
    }

    // two separate boxes as the two parts of one object, part pa on filament fa (tool 1), pb on fb (tool 2)
    fun duo(d: Path): Path = d.resolve("duo.3mf").also { p ->
        threeMf(p, "duo", listOf("pa" to box(100.0, 110.0, 0.0, 120.0, 130.0, 3.0), "pb" to box(135.0, 110.0, 0.0, 155.0, 130.0, 3.0)))
    }
    val duoLock = "[model.object.duo.part.duo_pa]\nslot = \"1\"\n[model.object.duo.part.duo_pb]\nslot = \"2\"\n"
    /** Per T number: filament per mm of the outer walls' moves (E / XY length). */
    fun outerWallEPerMm(g: String): Map<Int, Double> {
        var t = 0; var feature = ""; var x = Double.NaN; var y = Double.NaN
        val e = HashMap<Int, Double>(); val len = HashMap<Int, Double>()
        val w = Regex("([XYE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            if (raw.startsWith(";TYPE:") || raw.startsWith("; FEATURE:")) feature = raw.substringAfter(":").trim()
            val c = raw.substringBefore(';').trim()
            if (Regex("T\\d+").matches(c)) { t = c.substring(1).toInt(); continue }
            if (!c.startsWith("G1 ")) continue
            val v = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = v["X"] ?: x; val ny = v["Y"] ?: y
            val de = v["E"]
            if (de != null && de > 0 && ("X" in v || "Y" in v) && !x.isNaN() && feature.startsWith("Outer wall", ignoreCase = true)) {
                e.merge(t, de, Double::plus); len.merge(t, Math.hypot(nx - x, ny - y), Double::plus)
            }
            x = nx; y = ny
        }
        return e.mapValues { (k, v) -> v / len.getValue(k) }
    }

    "in a multi-filament object each part extrudes with its own filament's flow ratio (once the writer used the first part's filament for every part)" {
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"\ntool.t2.filament = \"fb\"", tool("t1", 1, "") + tool("t2", 2, "")), "locks.toml" to locks, "parts.toml" to duoLock)
        val model = duo(d)
        val cfs = listOf("hw.toml", "locks.toml", "parts.toml")
        slice(d, cfs, listOf(model), "same.gcode")
        val same = outerWallEPerMm(Files.readString(d.resolve("same.gcode")))
        withClue("control: equal flows give equal filament per mm on both tools: $same") { same.keys shouldBe setOf(0, 1); same.getValue(1) shouldBe (same.getValue(0) plusOrMinus 1e-4) }
        Files.writeString(d.resolve("fb_half.toml"), "[hardware.filament.fb]\nflow = \"0.5\"\n")
        slice(d, cfs + "fb_half.toml", listOf(model), "half.gcode")
        val half = outerWallEPerMm(Files.readString(d.resolve("half.gcode")))
        withClue("fb's flow 0.5 halves tool 2's outer walls and leaves tool 1's: $half") {
            half.getValue(0) shouldBe (same.getValue(0) plusOrMinus 1e-4)
            half.getValue(1) shouldBe (same.getValue(1) * 0.5 plusOrMinus 1e-4)
        }
    }

    "one at a time spaces the objects by the brim of every object, not only the first object's" {
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"", tool("t1", 1, "")), "a.stl" to stl(box(0.0, 0.0, 0.0, 20.0, 20.0, 5.0)), "b.stl" to stl(box(0.0, 0.0, 0.0, 20.0, 20.0, 5.0)),
            "order.toml" to locks.replace("placement = \"as-given\"", "placement = \"center\"") + "\nobjects.printing = \"one_at_a_time\"\n")
        val models = listOf(d.resolve("a.stl"), d.resolve("b.stl"))
        fun brimOn(k: String) = "[plan.object.\"$k\".mechanism.adhesion.brim]\nenabled = \"true\"\nwhere = \"outer\"\nwidth = \"8 mm\"\n"
        /** The gap between the two boxes (20 mm wide) as the object order placed them. */
        fun gap(extra: String): Double {
            Files.writeString(d.resolve("brim.toml"), extra)
            val placed = product(d, listOf("hw.toml", "order.toml", "brim.toml"), models).decide().layout.placed
            return Math.abs(placed[1].cx - placed[0].cx) - 20.0
        }
        val none = gap("")
        val onFirst = gap(brimOn("a.stl"))
        val onSecond = gap(brimOn("b.stl"))
        withClue("control: the first object's 8 mm brim widens the gap by its reach (none $none, on a.stl $onFirst)") { (onFirst - none) shouldBe (8.0 plusOrMinus 0.5) }
        withClue("the second object's brim must widen it too (on b.stl: $onSecond)") { (onSecond - none) shouldBe (8.0 plusOrMinus 0.5) }
    }

    "the plate's object order is weighed with the plate's priorities, not the first object's own (job.object.<key>.priorities)" {
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"", tool("t1", 1, "")), "a.stl" to stl(box(0.0, 0.0, 0.0, 20.0, 20.0, 5.0)), "b.stl" to stl(box(0.0, 0.0, 0.0, 20.0, 20.0, 5.0)),
            "plate.toml" to locks.replace("placement = \"as-given\"", "placement = \"center\""))
        val models = listOf(d.resolve("a.stl"), d.resolve("b.stl"))
        fun orderWeights(extra: String): String {
            Files.writeString(d.resolve("w.toml"), extra)
            return product(d, listOf("hw.toml", "plate.toml", "w.toml"), models).decide().decisions.single { it.step == "object order" }.facts.single { it.startsWith("weights: ") }
        }
        withClue("control: the second object's own weights stay its own") { orderWeights("[job.object.\"b.stl\".priorities]\nspeed = \"50\"\n") shouldNotContain "speed=50" }
        orderWeights("[job.object.\"a.stl\".priorities]\nspeed = \"50\"\n") shouldNotContain "speed=50"
        withClue("control: the plate's own priorities do reach the order") { orderWeights("[job.priorities]\nspeed = \"50\"\n") shouldContain "speed=50" }
    }

    "an incremental re-slice after plan.technique.bridge.fill_along changes matches a fresh slice (once the paths stage's key left it out)" {
        val d = dir("hw.toml" to hardware("tool.t1.filament = \"fa\"", tool("t1", 1, head(4.0))), "locks.toml" to locks, "lock.toml" to rampLock(1, 1))
        val model = ramp(d)
        val cfs = listOf("hw.toml", "locks.toml", "lock.toml")
        val on = product(d, cfs, listOf(model), listOf("plan.technique.bridge.fill_along=true"))
        val off = product(d, cfs, listOf(model), listOf("plan.technique.bridge.fill_along=false"))
        val plan = on.decide()
        off.decide()   // the seams are decided per product: the fresh slice's setup carries its own
        fun write(j: Job) = StringWriter().also { j.write(it) }.toString()
        val job = Job(plan.layout, on.result.setup, "test")
        val first = write(job)
        job.update(off.result.setup)
        val incremental = write(job)
        val fresh = write(Job(plan.layout, off.result.setup, "test"))
        withClue("control: fill_along changes the file") { (fresh == first) shouldBe false }
        (incremental == fresh) shouldBe true
    }

    "a printer model of your own, like a shipped one: its added commands reach the writer, the rest comes from the base; control: without them the same start g-code is refused, naming both at once" {
        val machine = hardware("tool.t1.filament = \"fa\"", tool("t1", 1, ""))
            .replace("model = \"flashforge-c5\"", "model = \"my-c5\"")
            .replace("gcode.start = \"M83\\n", "gcode.start = \"M83\\nMY_PURGE\\nMY_WIPE\\n")
        fun model(command: String) = "\n[hardware.model.my-c5]\nlike = \"flashforge-c5\"\nname = \"My Creator 5\"\n$command"
        val d = dir("locks.toml" to locks, "a.stl" to stl(box(0.0, 0.0, 0.0, 10.0, 10.0, 2.0)),
            "with.toml" to machine + model("command.MY_PURGE = \"purpose: a purge my firmware defines\\ntested: printed on my machine\"\n" +
                "command.MY_WIPE = \"tested: printed on my machine\"\n"),
            "without.toml" to machine + model(""))
        fun run(hw: String): Pair<Int, String> {
            val lines = ArrayList<String>()
            val status = Commands.invoke(listOf("slice", "-cf", d.resolve(hw).toString(), "-cf", d.resolve("locks.toml").toString(),
                "-m", d.resolve("a.stl").toString(), "-o", d.resolve("$hw.gcode").toString()), lines::add)
            return status to lines.joinToString("\n")
        }
        val (sWith, oWith) = run("with.toml")
        withClue("with the command:\n$oWith") {
            sWith shouldBe 0
            val gcode = Files.readString(d.resolve("with.toml.gcode"))
            gcode.lines().any { it.trim() == "MY_PURGE" } shouldBe true
            gcode shouldContain ";LAYER_CHANGE"   // the base's dialect (flashforge), taken over through like
        }
        val (sWithout, oWithout) = run("without.toml")
        withClue("control, without it:\n$oWithout") {
            (sWithout != 0) shouldBe true
            oWithout shouldContain "no evidence of accepting 2 commands"
            for (word in listOf("MY_PURGE", "MY_WIPE")) oWithout shouldContain "hardware.model.my-c5.command.$word"
        }
    }
})
