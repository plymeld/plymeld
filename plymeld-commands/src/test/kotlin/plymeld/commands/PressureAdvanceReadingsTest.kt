package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import plymeld.config.Config
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate pressure-advance readings` and `values` on a plate made from a copy of the repo's sample config: the
 * sheet read from the plate's own g-code; a find plate read with its flip at a value worked out here gives that value,
 * written as the filament's (or the tool's) pressure advance and read back through the whole config; every refusal the
 * design names, each against the accepted sheet as its control; the confirm plate's three outcomes; a machine that sets
 * its own; a refusal printed as one line. The tests that make many plates are PressureAdvanceCommandTest's (a slow suite).
 */
class PressureAdvanceReadingsTest : StringSpec({
    val samples = PressureAdvanceSheets.samples
    fun copy() = PressureAdvanceSheets.copy()
    fun plate(dir: Path, vararg args: String) = PressureAdvanceSheets.plate(dir, *args)
    fun sheet(g: Path) = PressureAdvanceSheets.sheet(g)
    fun fill(blank: String, read: (row: String, value: Double?, step: Int) -> Triple<String, String, String?>) = PressureAdvanceSheets.fill(blank, read)
    fun truth(k: Double) = PressureAdvanceSheets.truth(k)
    fun values(text: String) = PressureAdvanceSheets.values(text)

    val dir = copy()
    val find = plate(dir, "--values", "0,0.01,0.02,0.03,0.04,0.05,0.06,0.07,0.08,0.09,0.1")

    "the sheet: an entry per wall, both rows, the values the plate's, every reading '?'" {
        val b = sheet(find)
        Regex("""\[wall\.(front|back)\.\d+]""").findAll(b).count() shouldBe 22
        b shouldContain "kind = \"find\""
        b shouldContain "value = \"0.1 s\""
        b.lines().count { it == "speed_up = \"?\"" } shouldBe 22
        // a sheet with a '?' left is refused
        shouldThrow<IllegalArgumentException> { values(b) }.message!! shouldContain "not read yet"
    }

    "a flip in both rows: the middle of the bracket between the highest groove and the lowest ridge" {
        values(fill(sheet(find), truth(0.05))).value shouldBe (0.05 plusOrMinus 1e-9)
        // between two walls: the middle of the two
        values(fill(sheet(find), truth(0.043))).value shouldBe (0.045 plusOrMinus 1e-9)
    }

    "refused, each against the accepted sheet: no flip, rows more than a step apart, out of order, both marks alike; the corners reported beside the walls" {
        val good = truth(0.05)
        values(fill(sheet(find), good)).value shouldBe (0.05 plusOrMinus 1e-9)                    // the control: accepted
        shouldThrow<IllegalArgumentException> { values(fill(sheet(find), truth(0.5))) }.message!! shouldContain "never reads a ridge"
        shouldThrow<IllegalArgumentException> { values(fill(sheet(find)) { _, _, _ -> Triple("ridge", "groove", "round") }) }.message!! shouldContain "never reads a groove"
        shouldThrow<IllegalArgumentException> { values(fill(sheet(find)) { r, v, s -> if (r == "back") truth(0.08)(r, v, s) else good(r, v, s) }) }.message!! shouldContain "more than one step"
        // one step apart is accepted, the brackets joined
        values(fill(sheet(find)) { r, v, s -> if (r == "back") truth(0.06)(r, v, s) else good(r, v, s) }).value shouldBe (0.055 plusOrMinus 1e-9)
        shouldThrow<IllegalArgumentException> { values(fill(sheet(find)) { r, v, s -> if (r == "front" && Math.abs(v!! - 0.08) < 1e-9) Triple("groove", "ridge", "bulge") else good(r, v, s) }) }.message!! shouldContain "out of order"
        // the corners read on their own: agreeing with the walls (the control), or out of order and flipping elsewhere, each
        // reported, the value still the walls'
        values(fill(sheet(find), good)).report.joinToString("\n") shouldContain "corners: bulges up to 0.04 s, round from 0.06 s: they agree"
        val odd = values(fill(sheet(find)) { r, v, s -> good(r, v, s).let { if (Math.abs(v!! - 0.02) < 1e-9) it.copy(third = "round") else it } })
        odd.value shouldBe (0.05 plusOrMinus 1e-9)
        odd.report.joinToString("\n") shouldContain "corners: out of order"
        val late = values(fill(sheet(find)) { r, v, s -> good(r, v, s).copy(third = if (v!! < 0.08) "bulge" else "round") })
        late.value shouldBe (0.05 plusOrMinus 1e-9)
        late.report.joinToString("\n") shouldContain "they disagree with the walls' 0.04 to 0.06 s"
        shouldThrow<IllegalArgumentException> { values(fill(sheet(find)) { r, v, s -> good(r, v, s).let { if (Math.abs(v!! - 0.02) < 1e-9) it.copy(second = "groove") else it } }) }.message!! shouldContain "at both marks"
    }

    "written as the filament's pressure advance, with the plate, its conditions, and the bracket; the tool's with --into tool; read back through the whole config" {
        val d = copy()
        val g = plate(d, "--values", "0,0.02,0.04,0.06,0.08")
        val readings = d.resolve("readings.toml")
        Files.writeString(readings, fill(sheet(g), truth(0.05)))
        fun at(path: List<String>): Double { val c = Config(ConfigStack.load(listOf(d.resolve("0*.toml").toString()), emptyList()), emptyList())
            return c.quantity(c.entry(path)!!, Dimension.TIME, Site(machine = "example-corexy", tool = "t1", filament = "pla-white")) }
        val fil = listOf("hardware", "filament", "pla-white", "pressure_advance")
        PressureAdvanceValuesCommand().parse(listOf("-cf", d.resolve("0*.toml").toString(), "--journal", d.resolve("journal.json").toString(), readings.toString()))
        at(fil) shouldBe (0.05 plusOrMinus 1e-9)
        val text = Files.readString(d.resolve("01_FILAMENTS.toml"))
        text shouldContain "pressure_advance = \"0.05 s\""
        text shouldContain "grooves up to 0.04 s and ridges from 0.06 s"
        text shouldContain "Conditions: slow"
        PressureAdvanceValuesCommand().parse(listOf("-cf", d.resolve("0*.toml").toString(), "--journal", d.resolve("journal.json").toString(), "--into", "tool", readings.toString()))
        at(listOf("hardware", "tool", "t1", "pressure_advance")) shouldBe (0.05 plusOrMinus 1e-9)
    }

    "the confirm plate: holds, depends on the flow, or no value" {
        val c = plate(dir, "--confirm", "0.04", "--confirm-step", "0.01")
        val b = sheet(c)
        b shouldContain "kind = \"confirm\""
        values(fill(b, truth(0.04))).value shouldBe (0.04 plusOrMinus 1e-9)
        // the flat wall moves with the flow step: 0.03 at the small step, 0.04 at the medium, 0.05 at the large
        val moving = values(fill(b) { r, v, s -> truth(listOf(0.03, 0.04, 0.05)[s - 1])(r, v, s) })
        moving.value shouldBe (0.04 plusOrMinus 1e-9)
        moving.report.joinToString("\n") shouldContain "depends on the flow"
        shouldThrow<IllegalArgumentException> { values(fill(b, truth(0.035))) }.message!! shouldContain "nothing reads flat"
    }

    "a machine that sets its own: the find plate refused, the confirm plate at its own value with no command, nothing written" {
        val own = arrayOf("-cv", "hardware.machine.example-corexy.firmware.own_pressure_advance=true")
        shouldThrow<IllegalArgumentException> { plate(dir, *own) }.message!! shouldContain "--confirm machine"
        val m = plate(dir, *own, "--confirm", "machine")
        Files.readString(m).lines().none { it.startsWith("SET_PRESSURE_ADVANCE") } shouldBe true
        val r = values(fill(sheet(m)) { _, _, _ -> Triple("flat", "flat", null) })
        r.value shouldBe null
        r.report.joinToString("\n") shouldContain "it holds at every flow step"
        // --confirm machine on a machine that does not set its own is refused
        shouldThrow<IllegalArgumentException> { plate(dir, "--confirm", "machine") }.message!! shouldContain "does not"
    }

    "a refusal is one line, no usage text: this plate's and another plate's" {
        fun run(vararg args: String): Pair<Int, List<String>> {
            val lines = ArrayList<String>()
            val status = Commands.invoke(listOf("calibrate") + args.toList() + listOf("-cf", dir.resolve("0*.toml").toString(), "-o", dir.resolve("refused.gcode").toString()), { lines += it }, { lines += it })
            return status to lines.flatMap { it.lines() }.filter { it.isNotBlank() }
        }
        for (args in listOf(arrayOf("pressure-advance", "--confirm", "machine"), arrayOf("flow", "--temps", "100-400", "--step", "1"))) {
            val (status, lines) = run(*args)
            status shouldBe 1
            lines.size shouldBe 1
            lines.single() shouldStartWith "plymeld: "
        }
        // control: a mistake in the command line's own form still shows its usage
        val (_, usage) = run("pressure-advance", "--tool", "x")
        usage.any { it.startsWith("Usage:") } shouldBe true
    }

    "the config folder is said once, however often a plate reads its config" {
        val said = ArrayList<String>()
        val c = object : ConfigCommand("t") { override fun run() { files(); files() } }
        c.configureContext { echoMessage = { _, m, _, _ -> said += m?.toString() ?: "" } }
        c.parse(emptyList())
        said.count { it.startsWith("config: no -cf") } shouldBe 1
    }

})
