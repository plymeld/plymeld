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

    "every calibration plate, readings, and values with long keys: every line within the limit, every comment written within the config files' width" {
        val d = OutputLines.longNamed(samples)
        val config = listOf("-cf", d.resolve("0*.toml").toString())
        val model = listOf("-cv", "hardware.machine.${OutputLines.MACHINE}.model=qidi-q2")
        val journal = listOf("--journal", d.resolve("journal.json").toString())
        val all = ArrayList<Pair<String, List<String>>>()
        /** Runs `plymeld` [args], which must end with status 0; what it printed, kept in [all]. */
        fun run(vararg args: String): List<String> {
            val (status, lines) = OutputLines.run(args.toList())
            all += args.joinToString(" ") to lines
            withClue("${args.joinToString(" ")}\n${lines.joinToString("\n")}") { status shouldBe 0 }
            return lines
        }
        fun out(name: String) = d.resolve(name).toString()
        fun gcode(name: String) = Files.readString(d.resolve(name))

        // the filament plate (two of its families: all of them take more than a test's heap), and the pressure-advance
        // plate and its sheet, before any values changes the filament
        run("calibrate", "filament", *config.toTypedArray(), *model.toTypedArray(), "--tests", "overhangs,stringing", "-o", out("filament.gcode"))
        run("calibrate", "pressure-advance", *config.toTypedArray(), *model.toTypedArray(), "-o", out("pa.gcode"))
        run("calibrate", "pressure-advance", "readings", out("pa.gcode"), "-o", out("pa-readings.toml"))
        // the melt plate and its values: each column's reading the filament's expected maximum flow, as the plate says it
        val melt = run("calibrate", "melt", *config.toTypedArray(), *model.toTypedArray(), "-o", out("melt.gcode"))
        val meltCols = Regex("""columns: ([0-9, ]+) C""").find(melt.joinToString("\n"))!!.groupValues[1].split(", ").map { it.trim() }
        val expected = Regex("""expected maximum flow: ([0-9.]+)""").find(melt.joinToString("\n"))!!.groupValues[1]
        run("calibrate", "melt", "values", *config.toTypedArray(), *journal.toTypedArray(), "--plate-gcode", out("melt.gcode"), *meltCols.map { "$it=$expected" }.toTypedArray())
        // the flow plate and its values
        val flow = run("calibrate", "flow", *config.toTypedArray(), *model.toTypedArray(), "-o", out("flow.gcode"))
        val flowCols = Regex("""columns: ([0-9, ]+) C""").find(flow.joinToString("\n"))!!.groupValues[1].split(", ").map { it.trim() }
        run("calibrate", "flow", "values", *config.toTypedArray(), *journal.toTypedArray(), "--plate-gcode", out("flow.gcode"), *flowCols.map { "$it=1" }.toTypedArray())
        // the first-layer plate and its values: each column's slowest speed at its lowest height, a tile no plate leaves out
        run("calibrate", "first-layer", *config.toTypedArray(), *model.toTypedArray(), "-o", out("first-layer.gcode"))
        val tiles = Regex("""; first_layer_tiles = speeds ([0-9.]+)[^;]*? mm/s .*?heights ([0-9.]+)""").find(gcode("first-layer.gcode"))!!.groupValues
        val blocks = Regex("""; block \d+ = (\d+) C""").findAll(gcode("first-layer.gcode")).map { it.groupValues[1] }.toList()
        run("calibrate", "first-layer", "values", *config.toTypedArray(), *journal.toTypedArray(), "--plate-gcode", out("first-layer.gcode"), *blocks.map { "$it=${tiles[2]},${tiles[1]}" }.toTypedArray())
        // the motion, ironing, and seam plates, their sheets, and their values
        for ((kind, grade) in listOf("motion" to "hidden", "ironing" to "good", "seams" to "faint")) {
            run("calibrate", kind, *config.toTypedArray(), *model.toTypedArray(), "-o", out("$kind.gcode"))
            run("calibrate", kind, "readings", out("$kind.gcode"), "-o", out("$kind-readings.toml"))
            // every "?" graded: a `grade = "?"` line, or a grid's cells
            Files.writeString(d.resolve("$kind-readings.toml"), gcode("$kind-readings.toml").lines().joinToString("\n") { l ->
                if (l == "grade = \"?\"") "grade = \"$grade\"" else if (Regex("""^[0-9.]+ mm/s2\s""").containsMatchIn(l)) l.replace("?", grade) else l })
            run("calibrate", kind, "values", out("$kind-readings.toml"), "-o", out("$kind-values.toml"))
        }
        // every command ran (each must end with status 0) and printed something
        all.size shouldBe 18
        all.none { it.second.isEmpty() } shouldBe true
        // every line within its limit (the change a values shows, its comments as the file has them under the two spaces
        // it is indented by), and every comment in the config files
        val over = all.flatMap { (args, lines) -> (OutputLines.overLong(lines) + OutputLines.badComments(lines.map { it.removePrefix("  ") })).map { "$args\n    ${it.length}: $it" } } +
            Files.list(d).use { fs -> fs.toList() }.filter { it.fileName.toString().matches(Regex("0.*\\.toml")) }.flatMap { f -> OutputLines.badComments(Files.readString(f).lines()).map { "${f.fileName}\n    ${it.length}: $it" } }
        withClue(over.joinToString("\n")) { over.shouldBeEmpty() }
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
