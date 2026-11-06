package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.Config
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate flow values` on a copy of the repo's sample config: the flow ratio it writes, read back through the
 * whole config at temperatures between and past the readings, against a straight line between them worked out here; one
 * plain value when the columns agree; undo; and the readings it refuses.
 */
class FlowValuesTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    fun copy(): Path {
        val d = Files.createTempDirectory("plymeld-flow-values")
        Files.list(samples).use { s -> s.forEach { Files.copy(it, d.resolve(it.fileName)) } }
        return d
    }
    fun run(dir: Path, vararg args: String) = FlowValuesCommand().parse(listOf("-cf", dir.resolve("0*.toml").toString(), "--journal", dir.resolve("journal.json").toString()) + args)
    val path = listOf("hardware", "filament", "pla-white", "flow")
    // the flow ratio the whole config gives at [tempC]
    fun ratioAt(dir: Path, tempC: Int): Double {
        val c = Config(ConfigStack.load(listOf(dir.resolve("0*.toml").toString()), listOf("hardware.filament.pla-white.temperature.print=$tempC C")), emptyList())
        return c.quantity(c.entry(path)!!, Dimension.RATIO, Site(machine = "example-corexy", tool = "t1", filament = "pla-white"))
    }

    "the best patch per column written as the filament's flow ratio: through each, straight between, flat past the ends" {
        val dir = copy()
        // control: the sample sets no flow ratio of its own (the bundled one applies, through `like`)
        (Config(ConfigStack.load(listOf(samples.resolve("0*.toml").toString()), emptyList()), emptyList()).entry(path)?.source?.contains("01_FILAMENTS") ?: false) shouldBe false
        run(dir, "200=0.96", "210=1", "230=98%")
        fun want(t: Int) = when { t <= 200 -> 0.96; t <= 210 -> 0.96 + (t - 200) * 0.004; t <= 230 -> 1.0 - (t - 210) * 0.001; else -> 0.98 }
        for (t in listOf(190, 200, 205, 210, 220, 230, 240)) withClue("$t C") { ratioAt(dir, t) shouldBe (want(t) plusOrMinus 1e-9) }
        Files.readString(dir.resolve("01_FILAMENTS.toml")) shouldContain "# Readings, the best patch per column: 200 C 0.96, 210 C 1, 230 C 0.98."
    }

    "columns that agree: one plain value; undo restores the file exactly" {
        val dir = copy(); val f = dir.resolve("01_FILAMENTS.toml"); val original = Files.readString(f)
        run(dir, "200=1.02", "210=1.02")
        Files.readString(f) shouldContain "flow = \"1.02\""
        ratioAt(dir, 250) shouldBe (1.02 plusOrMinus 1e-9)
        Journal.read(dir.resolve("journal.json")).undo()
        Files.readString(f) shouldBe original
    }

    "refused: a column with no good patch, one temperature twice, a filament the config files do not define" {
        val dir = copy(); val f = dir.resolve("01_FILAMENTS.toml"); val original = Files.readString(f)
        shouldThrow<UsageError> { run(dir, "210=1.06+") }.message!! shouldContain "above the plate's range"
        shouldThrow<UsageError> { run(dir, "210=0.94-") }.message!! shouldContain "below the plate's range"
        shouldThrow<UsageError> { run(dir, "210=1", "210=0.98") }.message!! shouldContain "given twice"
        shouldThrow<UsageError> { run(dir, "--filament", "pla-generic", "210=1") }.message!! shouldContain "not defined in your config files"
        Files.readString(f) shouldBe original
    }
})
