package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.calibrate.Levelling
import plymeld.core.calibrate.PlateInstructions
import java.nio.file.Files
import java.nio.file.Path

/**
 * What every calibration plate says before printing, in its output and on its page. Of levelling: never an instruction to
 * level; on a machine whose levelling plymeld runs, that the file runs it (and that `--no-level` leaves it out); made with
 * `--no-level`, that the file does not level; on a machine with neither, nothing (CalibrateLinesTest's runs); each plate in
 * Levelling's words, once. And every plate's page its step to set the printer's speed and flow to 100 %, in the shared
 * words (the melt and flow plates have no page).
 */
class PlateBeforePrintingTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-plates-on-a-bed")   // a name without the word the test counts
    Files.list(samples).use { s -> s.forEach { Files.copy(it, d.resolve(it.fileName)) } }
    // the sample machine with a levelling command of its own (a placeholder command: the plates only carry it)
    val base = listOf("-cf", d.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-cv", "hardware.machine.example-corexy.gcode.level=G29")

    /** A sentence that tells the reader to level the bed, or to do it themselves. */
    fun instructs(text: String) = Regex("""(?i)level the bed\.|\blevel it\b|re-level|levelling yourself|level(ling)? \(mesh\) yourself|turn on the printer's own levelling""").containsMatchIn(text)

    val plates = listOf("melt" to emptyList(), "flow" to emptyList(), "first-layer" to emptyList(), "motion" to emptyList(), "ironing" to emptyList(),
        "seams" to emptyList(), "filament" to listOf("--tests", "overhangs"), "pressure-advance" to emptyList<String>())
    for ((kind, extra) in plates) for (noLevel in listOf(false, true)) {
        "the $kind plate on a machine that levels${if (noLevel) ", made with --no-level" else ""}: Levelling's line once, its page's sentence, no instruction to level; the page's speed-and-flow step" {
            val out = d.resolve("$kind${if (noLevel) "-no-level" else ""}.gcode")
            val (status, lines) = OutputLines.run(listOf("calibrate", kind) + base + extra + (if (noLevel) listOf("--no-level") else emptyList()) + listOf("-o", out.toString()))
            withClue(lines.joinToString("\n")) {
                status shouldBe 0
                val state = Levelling.State(levels = !noLevel, skipped = noLevel, onStart = false)
                val said = Levelling.said(state)!!
                lines.count { it.trim() == said } shouldBe 1
                lines.filter { "levelling" in it && it.trim() != said }.shouldBeEmpty()
                lines.filter(::instructs).shouldBeEmpty()
                // the file levels where it says so
                Files.readString(out).lines().any { it.trim() == "G29" } shouldBe !noLevel
                val page = out.resolveSibling(out.fileName.toString().removeSuffix(".gcode") + ".md")
                // every plate but the melt and flow plates writes a page
                Files.exists(page) shouldBe (kind != "melt" && kind != "flow")
                if (Files.exists(page)) {
                    val md = Files.readString(page)
                    withClue(md) {
                        md.contains(Levelling.page(state)!!) shouldBe true; instructs(md) shouldBe false
                        md.contains(PlateInstructions.SPEED_AND_FLOW) shouldBe true
                        // control: the page without the step is caught
                        md.replace(PlateInstructions.SPEED_AND_FLOW, "").contains(PlateInstructions.SPEED_AND_FLOW) shouldBe false
                    }
                }
            }
        }
    }

    "control: the plates' old levelling words are each an instruction to level; Levelling's own are not" {
        for (old in listOf("bed levelling: this machine has none plymeld can run (hardware.machine.<key>.gcode.level is empty): level it when you start the print",
            "1. **Level the bed.** This file runs the printer's bed levelling before the plate.",
            "This file does **not** level the bed (it was made with `--no-level`): run the printer's bed levelling (mesh) yourself first.",
            "plymeld cannot level this printer from the g-code: turn on the printer's own levelling when you start the print",
            "Stop and re-level if it is not.")) withClue(old) { instructs(old) shouldBe true }
        for (a in listOf(true, false)) for (b in listOf(true, false)) for (c in listOf(true, false)) {
            val s = Levelling.State(a, b, c)
            listOfNotNull(Levelling.said(s), Levelling.page(s)).forEach { withClue(it) { instructs(it) shouldBe false } }
        }
    }
})
