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
        val tool = FirstUse.toolAsks().associate { it.path to it.default!! }
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
