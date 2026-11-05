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
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate melt values` on a copy of the repo's sample config: the formula it writes, read back through the whole
 * config at temperatures between and past the readings, against a straight line between the readings worked out here;
 * the comment; a second run replacing the first; undo; and the readings it refuses.
 */
class MeltValuesTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    fun copy(): Path {
        val d = Files.createTempDirectory("plymeld-melt-values")
        Files.list(samples).use { s -> s.forEach { Files.copy(it, d.resolve(it.fileName)) } }
        return d
    }
    fun run(dir: Path, vararg args: String) = MeltValuesCommand().parse(listOf("-cf", dir.resolve("0*.toml").toString(), "--journal", dir.resolve("journal.json").toString()) + args)
    val path = listOf("hardware", "filament", "pla-white", "machine", "example-corexy", "melt", "flow", "max")
    // the maximum flow the whole config gives at [tempC]
    fun flowAt(dir: Path, tempC: Int): Double {
        val c = Config(ConfigStack.load(listOf(dir.resolve("0*.toml").toString()), listOf("hardware.filament.pla-white.temperature.print=$tempC C")), emptyList())
        return c.quantity(c.entry(path)!!, Dimension.FLOW, Site(machine = "example-corexy", tool = "t1", filament = "pla-white"))
    }

    "the readings written as the filament's maximum flow on the machine: through each, straight between, flat past the ends" {
        val dir = copy()
        run(dir, "200=24", "210=26", "230=27")
        // a straight line between neighboring readings, worked out here
        fun want(t: Int) = when { t <= 200 -> 24.0; t <= 210 -> 24 + (t - 200) * 0.2; t <= 230 -> 26 + (t - 210) * 0.05; else -> 27.0 }
        for (t in listOf(180, 200, 205, 210, 220, 230, 250)) withClue("$t C") { flowAt(dir, t) shouldBe (want(t) plusOrMinus 1e-9) }
        val text = Files.readString(dir.resolve("01_FILAMENTS.toml"))
        text shouldContain "# Readings, the highest flow without gaps: 200 C 24 mm3/s, 210 C 26 mm3/s, 230 C 27 mm3/s."
        // control: without the command the sample has no such value
        Config(ConfigStack.load(listOf(samples.resolve("0*.toml").toString()), emptyList()), emptyList()).entry(path) shouldBe null
    }

    "a second run replaces the first, comment and all; undo restores the file exactly" {
        val dir = copy(); val f = dir.resolve("01_FILAMENTS.toml"); val original = Files.readString(f)
        run(dir, "200=24", "210=26")
        val once = Files.readString(f)
        run(dir, "200=20", "210=22")
        val twice = Files.readString(f)
        Regex("melt\\.flow\\.max").findAll(twice).count() shouldBe 1
        Regex("# Readings").findAll(twice).count() shouldBe 1
        flowAt(dir, 205) shouldBe (21.0 plusOrMinus 1e-9)
        Journal.read(dir.resolve("journal.json")).undo()
        Files.readString(f) shouldBe once
        Journal.read(dir.resolve("journal.json")).undo()
        Files.readString(f) shouldBe original
    }

    "refused: a reading below the first row, one temperature twice, a filament the config files do not define; --dry-run writes nothing" {
        val dir = copy(); val f = dir.resolve("01_FILAMENTS.toml"); val original = Files.readString(f)
        shouldThrow<UsageError> { run(dir, "200=<8") }.message!! shouldContain "gives no value"
        shouldThrow<UsageError> { run(dir, "200=24", "200=25") }.message!! shouldContain "given twice"
        shouldThrow<UsageError> { run(dir, "--filament", "pla-generic", "200=24") }.message!! shouldContain "not defined in your config files"
        run(dir, "--dry-run", "200=24")
        Files.readString(f) shouldBe original
        // ">=" is at least that value: written as the value
        run(dir, "200=24", "210=>=30")
        flowAt(dir, 215) shouldBe (30.0 plusOrMinus 1e-9)
        Files.readString(f) shouldContain "210 C at least 30 mm3/s"
    }
})
