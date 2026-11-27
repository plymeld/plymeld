package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Every calibration plate, and every `readings` and `values` command, on the repo's sample config with a machine, a
 * filament, and a tool key of 40 characters or more: every line each prints within [OutputLines.WIDTH] (a path counted as
 * its file name, a line that is nothing but a command to run excepted), and every comment a `values` writes into the
 * config within the config files' width, each starting `# `. The pressure-advance plate's own refusals, its `values`, and
 * the plates its suggestions make are held to the same rule in PressureAdvanceReadingsTest.
 */
class CalibrateLinesTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()

    /**
     * A copy of the sample config with long keys, the commands [run] in it (each must end with status 0) and what each
     * printed; [check] holds every line they printed and every comment in the config files after them to the rules.
     */
    class Run(samples: Path) {
        val d: Path = OutputLines.longNamed(samples)
        val config = arrayOf("-cf", d.resolve("0*.toml").toString())
        val model = arrayOf("-cv", "hardware.machine.${OutputLines.MACHINE}.model=qidi-q2")
        val journal = arrayOf("--journal", d.resolve("journal.json").toString())
        val all = ArrayList<Pair<String, List<String>>>()
        fun run(vararg args: String): List<String> {
            val (status, lines) = OutputLines.run(args.toList())
            all += args.joinToString(" ") to lines
            withClue("${args.joinToString(" ")}\n${lines.joinToString("\n")}") { status shouldBe 0 }
            return lines
        }
        fun out(name: String) = d.resolve(name).toString()
        fun text(name: String): String = Files.readString(d.resolve(name))
        /** Every line within its limit (the change a values shows, its comments as the file has them under the two spaces it is indented by), every comment in the TOML files the commands read and write (the config files, the sheets, the values files). */
        fun check(count: Int) {
            all.size shouldBe count
            all.none { it.second.isEmpty() } shouldBe true
            // the sample machine neither levels nor has a printer to level as a print starts: no word of levelling (PlateLevellingTest)
            withClue(all.joinToString("\n") { it.second.joinToString("\n") }) { all.flatMap { it.second }.filter { "levelling" in it }.shouldBeEmpty() }
            val over = all.flatMap { (args, lines) -> (OutputLines.overLong(lines) + OutputLines.badComments(lines.map { it.removePrefix("  ") })).map { "$args\n    ${it.length}: $it" } } +
                Files.list(d).use { fs -> fs.toList() }.filter { it.fileName.toString().endsWith(".toml") }.flatMap { f -> OutputLines.badComments(Files.readString(f).lines()).map { "${f.fileName}\n    ${it.length}: $it" } }
            withClue(over.joinToString("\n")) { over.shouldBeEmpty() }
        }
    }

    "the filament plate with long keys (two of its families: all of them take more than a test's heap)" {
        val r = Run(samples)
        r.run("calibrate", "filament", *r.config, *r.model, "--tests", "overhangs,stringing", "-o", r.out("filament.gcode"))
        r.check(1)
    }

    "the pressure-advance plate and its sheet with long keys (its values in PressureAdvanceReadingsTest)" {
        val r = Run(samples)
        r.run("calibrate", "pressure-advance", *r.config, *r.model, "-o", r.out("pa.gcode"))
        r.run("calibrate", "pressure-advance", "readings", r.out("pa.gcode"), "-o", r.out("pa-readings.toml"))
        r.check(2)
    }

    "the melt plate and its values with long keys: each column's reading the filament's expected maximum flow, as the plate says it" {
        val r = Run(samples)
        val melt = r.run("calibrate", "melt", *r.config, *r.model, "-o", r.out("melt.gcode")).joinToString("\n")
        val cols = Regex("""columns: ([0-9, ]+) C""").find(melt)!!.groupValues[1].split(", ").map { it.trim() }
        val expected = Regex("""expected maximum flow: ([0-9.]+)""").find(melt)!!.groupValues[1]
        r.run("calibrate", "melt", "values", *r.config, *r.journal, "--plate-gcode", r.out("melt.gcode"), *cols.map { "$it=$expected" }.toTypedArray())
        r.check(2)
    }

    "the flow plate and its values with long keys" {
        val r = Run(samples)
        val flow = r.run("calibrate", "flow", *r.config, *r.model, "-o", r.out("flow.gcode")).joinToString("\n")
        val cols = Regex("""columns: ([0-9, ]+) C""").find(flow)!!.groupValues[1].split(", ").map { it.trim() }
        r.run("calibrate", "flow", "values", *r.config, *r.journal, "--plate-gcode", r.out("flow.gcode"), *cols.map { "$it=1" }.toTypedArray())
        r.check(2)
    }

    "the first-layer plate and its values with long keys: each column's slowest speed at its lowest height, a tile no plate leaves out" {
        val r = Run(samples)
        r.run("calibrate", "first-layer", *r.config, *r.model, "-o", r.out("first-layer.gcode"))
        val tiles = Regex("""; first_layer_tiles = speeds ([0-9.]+)[^;]*? mm/s .*?heights ([0-9.]+)""").find(r.text("first-layer.gcode"))!!.groupValues
        val blocks = Regex("""; block \d+ = (\d+) C""").findAll(r.text("first-layer.gcode")).map { it.groupValues[1] }.toList()
        r.run("calibrate", "first-layer", "values", *r.config, *r.journal, "--plate-gcode", r.out("first-layer.gcode"), *blocks.map { "$it=${tiles[2]},${tiles[1]}" }.toTypedArray())
        r.check(2)
    }

    for ((kind, grade) in listOf("motion" to "hidden", "ironing" to "good", "seams" to "faint")) {
        "the $kind plate, its sheet, and its values with long keys: every '?' graded $grade" {
            val r = Run(samples)
            r.run("calibrate", kind, *r.config, *r.model, "-o", r.out("$kind.gcode"))
            r.run("calibrate", kind, "readings", r.out("$kind.gcode"), "-o", r.out("$kind-readings.toml"))
            // every "?" graded: a `grade = "?"` line, or a grid's cells
            Files.writeString(r.d.resolve("$kind-readings.toml"), r.text("$kind-readings.toml").lines().joinToString("\n") { l ->
                if (l == "grade = \"?\"") "grade = \"$grade\"" else if (Regex("""^[0-9.]+ mm/s2\s""").containsMatchIn(l)) l.replace("?", grade) else l })
            r.run("calibrate", kind, "values", r.out("$kind-readings.toml"), "-o", r.out("$kind-values.toml"))
            r.check(3)
        }
    }

    "control: a plate's line over the limit, a comment over the width, and one without its space are caught; a path counts as its file name" {
        val patches = "  patches: flow ratios 0.94, 0.96, 0.98, 1, 1.02, 1.04, 1.06, leanest at the front (the filament's own: 1); each 30 mm square, 0.44 mm lines 0.4 mm apart (the bead spacing), " +
            "4 layers of 0.2 mm, at 150 mm/s (a visible surface's)"
        OutputLines.overLong(listOf(patches)) shouldBe listOf(patches)
        val deep = "  -> /" + "folder/".repeat(40) + "flow.gcode"
        OutputLines.overLong(listOf(deep)).shouldBeEmpty()
        val wide = "# " + "x".repeat(TomlEdit.COMMENT_WIDTH - 1)
        OutputLines.badComments(listOf(wide, "#x", "# " + "x".repeat(TomlEdit.COMMENT_WIDTH - 2))) shouldBe listOf(wide, "#x")
    }
})
