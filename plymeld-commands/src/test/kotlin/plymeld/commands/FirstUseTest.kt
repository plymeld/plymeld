package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.file.Files

/**
 * The first-use setup's files make a setup that slices: each bundled model with the setup's defaults, as `plymeld init`
 * writes them; controls: a setup missing a value the setup asks for does not.
 */
class FirstUseTest : StringSpec({
    val c = FirstUse.choices()
    fun model(id: String) = c.models.first { it.id == id }
    /**
     * The setup's own answers for model [id]: every default taken ([omitBuild]: the build size left out), and [typed] for
     * the questions a model gives no default for.
     */
    fun printer(id: String, key: String, omitBuild: Boolean = false, host: String? = null, port: Int? = null, secrets: Map<String, String> = emptyMap(),
                typed: Map<String, String> = emptyMap()): FirstUse.Printer {
        val m = model(id)
        val values = LinkedHashMap<String, String>()
        for (a in FirstUse.printerAsks(m)) if (!(omitBuild && a.path in setOf("build.x", "build.y", "build.z"))) a.default?.let { values[a.path] = it }
        values.putAll(typed)
        host?.let { values["connection.host"] = it; values["connection.api"] = FirstUse.api(m)!! }
        port?.let { values["connection.port"] = "$it" }
        val tool = FirstUse.toolAsks(m).mapNotNull { a -> a.default?.let { a.path to it } }.toMap()
        val tools = (1..m.toolheads).map { FirstUse.Tool("$key-t$it", it, tool, if (it == 1) "pla" else null) }
        return FirstUse.Printer(key, id, values, tools, c.plates.first(), secrets)
    }

    "each bundled model, set up with the setup's defaults alone, slices a test cube; control: no build size, it does not" {
        c.models.map { it.id }.toSet() shouldBe setOf("generic-klipper", "qidi-q2", "flashforge-c5")
        // the defaults come from the models: their build area, and the Q2's firmware start and end
        FirstUse.printerAsks(model("qidi-q2")).first { it.path == "build.x" }.default shouldBe "270 mm"
        FirstUse.printerAsks(model("flashforge-c5")).first { it.path == "build.z" }.default shouldBe "256 mm"
        FirstUse.printerAsks(model("qidi-q2")).first { it.path == "gcode.start" }.default!! shouldContain "PRINT_START BED="
        FirstUse.printerAsks(model("flashforge-c5")).first { it.path == "gcode.start" }.default!! shouldNotContain "G28"   // the C5 model lists no G28
        // the generic model has no build area or motion limits of its own: the user types them
        FirstUse.printerAsks(model("generic-klipper")).first { it.path == "build.x" }.default shouldBe null
        FirstUse.printerAsks(model("generic-klipper")).first { it.path == "motion.print.speed" }.default shouldBe null
        val dir = Files.createTempDirectory("plymeld-firstuse-")
        val answers = FirstUse.Answers(listOf(printer("qidi-q2", "q2", host = "192.168.0.20"),
            printer("flashforge-c5", "c5", host = "192.168.0.21", secrets = mapOf("serial_number" to "SN1", "check_code" to "CC1")),
            printer("generic-klipper", "k", typed = mapOf("build.x" to "220 mm", "build.y" to "220 mm", "build.z" to "250 mm",
                "motion.acceleration.max" to "5000 mm/s2", "motion.print.speed" to "200 mm/s"))),
            listOf(FirstUse.Filament("pla", "pla-generic", "#FFFFFF")))
        val files = FirstUse.files(answers, dir)
        files.keys shouldBe setOf("10_printers.toml", "secrets/c5.toml", "20_filaments.toml", "30_loadout.toml", "40_job.toml")
        files.getValue("10_printers.toml") shouldContain "connection.credential.serial_number.file = "
        files.getValue("10_printers.toml") shouldNotContain "SN1"          // a credential's value only in its secrets file
        files.getValue("secrets/c5.toml") shouldContain "serial_number = \"SN1\""
        FirstUse.write(files, dir)
        val said = ArrayList<String>()
        withClue({ said.joinToString("\n") }) { FirstUse.check(dir, listOf("q2", "c5", "k"), { said += it }) shouldBe true }
        said.joinToString("\n") shouldContain "q2: a 20 mm test cube sliced"
        said.joinToString("\n") shouldContain "c5: a 20 mm test cube sliced"
        said.joinToString("\n") shouldContain "k: a 20 mm test cube sliced"

        // control: the build size left out, the cube is refused
        val bare = Files.createTempDirectory("plymeld-firstuse-bare-")
        FirstUse.write(FirstUse.files(FirstUse.Answers(listOf(printer("qidi-q2", "q2", omitBuild = true)), listOf(FirstUse.Filament("pla", "pla-generic", null))), bare), bare)
        val bareSaid = ArrayList<String>()
        FirstUse.check(bare, listOf("q2"), { bareSaid += it }) shouldBe false
        bareSaid.joinToString("\n") shouldContain "build.x"
    }

    "the Creator 5's defaults come from its model: Flashforge's start and end, print and travel speeds, pause, pictures, nozzle limit; controls: models without them" {
        fun ask(id: String, path: String) = FirstUse.printerAsks(model(id)).first { it.path == path }
        val start = ask("flashforge-c5", "gcode.start").default!!
        start shouldContain "M106 P101 S0"                                         // the maker's fans off
        start shouldContain "G90\\nM83\\nG1 Z5 F2400\\nT{initial_tool}\\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        start shouldNotContain "G1 X256 Y0"                                       // the profile's prime line: plymeld lays its own
        val end = ask("flashforge-c5", "gcode.end").default!!
        end.substringBefore("\\n") shouldBe FirstUse.END_LIFT                     // the same lift as the generic end's
        end shouldContain "G1 X150 Y150 E-1.2 F12000"
        end shouldContain "M104 S0 T3"                                            // every tool's heater, the fourth's too
        ask("flashforge-c5", "gcode.pause").default shouldBe "M25"
        ask("flashforge-c5", "gcode.pictures").default shouldBe "140x110"
        // the maker's print speed below the axes' top speed, which travel takes
        ask("flashforge-c5", "motion.print.speed").default shouldBe "300 mm/s"
        ask("flashforge-c5", "motion.travel.speed").default shouldBe "600 mm/s"
        FirstUse.toolAsks(model("flashforge-c5")).first { it.path == "temperature.max" }.default shouldBe "320 C"
        // controls: the Q2's model names no print speed of its own (its firmware limit, travel the same) and no nozzle limit;
        // the generic model names no pause, pictures or travel speed
        ask("qidi-q2", "motion.print.speed").default shouldBe "600 mm/s"
        ask("qidi-q2", "motion.travel.speed").default shouldBe "600 mm/s"
        FirstUse.toolAsks(model("qidi-q2")).first { it.path == "temperature.max" }.default shouldBe null
        ask("generic-klipper", "gcode.pause").default shouldBe null
        ask("generic-klipper", "gcode.pictures").default shouldBe null
        ask("generic-klipper", "motion.travel.speed").default shouldBe "= machine.motion.print.speed"
    }

    "the generic start selects the print's first tool where the model selects tools by T; the generic end lifts clear and switches every tool's heater off" {
        fun m(toolheads: Int, vararg accepts: String) = FirstUse.Model("x", "X", toolheads, "klipper", emptyMap(), false, false, accepts.toSet())
        val tools = m(2, "M140", "G90", "T0", "M109", "M104", "M106", "G1")
        FirstUse.start(tools) shouldBe "M140 S{first_layer_bed_temperature}\\nG90\\nT{initial_tool}\\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        FirstUse.end(tools) shouldBe "${FirstUse.END_LIFT}\\nM106 S0\\nM104 S0 T0\\nM104 S0 T1\\nM140 S0"
        // control: a model with no T0 heats the one nozzle it has, by no tool
        val one = m(1, "M140", "M109", "M104")
        FirstUse.start(one) shouldBe "M140 S{first_layer_bed_temperature}\\nM109 S{first_layer_nozzle_temperature}"
        FirstUse.end(one) shouldBe "M104 S0\\nM140 S0"
    }

    "the Creator 5 set up with its defaults writes g-code that selects its first tool, heats that one, lifts at the end and carries its picture" {
        var root = java.nio.file.Path.of("").toAbsolutePath()
        while (!Files.isDirectory(root.resolve("test-models"))) root = root.parent ?: error("test-models/ not found")
        val dir = Files.createTempDirectory("plymeld-firstuse-c5-")
        FirstUse.write(FirstUse.files(FirstUse.Answers(listOf(printer("flashforge-c5", "c5")), listOf(FirstUse.Filament("pla", "pla-generic", null))), dir), dir)
        val out = dir.resolve("hole.gcode")
        val configs = Files.list(dir).use { s -> s.filter { it.toString().endsWith(".toml") }.map { it.toString() }.sorted().toList() }
        val said = ArrayList<String>()
        withClue({ said.joinToString("\n") }) {
            Commands.invoke(listOf("slice") + configs.flatMap { listOf("-cf", it) } + listOf("--machine", "c5", "-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()),
                { said += it }, { said += it }) shouldBe 0
        }
        val g = Files.readAllLines(out)
        g.indexOf("T0") shouldBe g.indexOfFirst { it.startsWith("M109 S") && it.endsWith(" T0") } - 1   // the tool selected, then its nozzle waited for
        g.any { it.startsWith("; thumbnail begin 140x110") } shouldBe true
        // the 4 mm block's last layer at 4 mm: the lift goes 10 mm above it (control: no unrendered template is left)
        ("G1 Z14" in g) shouldBe true
        g.none { "{" in it && !it.startsWith(";") } shouldBe true
    }

    "a printer given an address is reached with the files as written; control: nothing listening there, it is not" {
        // a stand-in Moonraker on this machine, answering the state query as a printer would
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/printer/objects/query") { ex ->
            val body = """{"result":{"status":{"print_stats":{"state":"standby","filename":""},"extruder":{"temperature":24.0,"target":0.0},
                "heater_bed":{"temperature":23.0,"target":0.0}}}}""".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        val port = server.address.port
        val answers = FirstUse.Answers(listOf(printer("qidi-q2", "q2", host = "127.0.0.1", port = port)),
            listOf(FirstUse.Filament("pla", "pla-generic", null)))
        FirstUse.connected(answers) shouldBe listOf("q2")
        val dir = Files.createTempDirectory("plymeld-firstuse-reach-")
        FirstUse.write(FirstUse.files(answers, dir), dir)
        val said = ArrayList<String>()
        try {
            withClue({ said.joinToString("\n") }) { FirstUse.reach(dir, "q2", { said += it }) shouldBe true }
        } finally { server.stop(0) }
        // control: the same files, the stand-in gone
        val gone = ArrayList<String>()
        FirstUse.reach(dir, "q2", { gone += it }) shouldBe false
        gone.joinToString("\n") shouldContain "127.0.0.1"
        // a printer given no address is not one to reach
        FirstUse.connected(FirstUse.Answers(listOf(printer("qidi-q2", "q2")), emptyList())) shouldBe emptyList()
    }

    "keys: lower-case letters, digits, and '-', and none the bundled config or another answer uses" {
        FirstUse.keyProblem("my-q2", c.taken) shouldBe null
        FirstUse.keyProblem("My Q2", c.taken)!! shouldContain "lower-case"
        FirstUse.keyProblem("pla-generic", c.taken)!! shouldContain "already a key"
    }
})
