package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.model.SliceSettings
import java.nio.file.Files

/**
 * The loadout is the machine's, the job
 * names its machine, and files of one numbered group may not set the same path ("config loads should never be
 * order-dependent within a grouping").
 */
class LoadoutTest : StringSpec({
    // two machines, each with a tool and its own loadout: a prints PLA on the bundled cool plate (35 C), b PETG on pei
    // (75 C), so the bed temperature, the bore and the filament each tell the two loadouts apart
    val two = listOf(
        "hardware.machine.a.model" to "qidi-q2", "hardware.machine.a.build.x" to "250 mm", "hardware.machine.a.build.y" to "250 mm",
        "hardware.machine.a.build.z" to "250 mm", "hardware.machine.a.motion.travel.speed" to "300 mm/s", "hardware.machine.a.motion.print.speed" to "300 mm/s",
        "hardware.machine.a.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.a.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.a.firmware.flavor" to "klipper", "hardware.machine.a.firmware.arcs" to false, "hardware.machine.a.firmware.exclude_object" to false,
        "hardware.machine.a.gcode.start" to "G28", "hardware.machine.a.gcode.end" to "M84",
        "hardware.machine.b.like" to "a",
        "hardware.tool.ta.machine" to "a", "hardware.tool.ta.bore" to "0.4 mm", "hardware.tool.ta.layer.height.min" to "0.08 mm", "hardware.tool.ta.layer.height.max" to "0.3 mm",
        "hardware.tool.tb.like" to "ta", "hardware.tool.tb.machine" to "b", "hardware.tool.tb.bore" to "0.6 mm",
        "hardware.machine.a.loadout.plate" to "cool", "hardware.machine.a.loadout.tool.ta.filament" to "pla-generic",
        "hardware.machine.b.loadout.plate" to "pei", "hardware.machine.b.loadout.tool.tb.filament" to "petg-generic")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.stl")
    fun config(vararg extra: Pair<String, Any>, machine: String? = null) =
        Config(ConfigStack(ConfigFiles.bundled() + (two + extra).map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), listOf(box), machine)
    fun printed(c: Config) = SliceSettings.of(ConfigSetup.setup(c).setup).let { Triple(it.bedTempC, it.nozzleDiameterUm, ConfigSetup.setup(c).setup.materials.keys.single()) }

    "--machine picks the machine, and its loadout gives the plate, tool and filament; job.machine names it too, --machine over it" {
        printed(config(machine = "a")) shouldBe Triple(35, 400L, "pla-generic")       // cool's PLA, the 0.4 mm tool
        printed(config(machine = "b")) shouldBe Triple(75, 600L, "petg-generic")      // pei's PETG, the 0.6 mm tool
        printed(config("job.machine" to "b")) shouldBe Triple(75, 600L, "petg-generic")
        printed(config("job.machine" to "b", machine = "a")) shouldBe Triple(35, 400L, "pla-generic")
        // b is `like` a, and does not take a's loadout over: b's own (control: b without one has none)
        val bWithout = Config(ConfigStack(ConfigFiles.bundled() + two.filterNot { it.first.startsWith("hardware.machine.b.loadout") }
            .map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), listOf(box), "b")
        shouldThrow<ConfigError> { ConfigSetup.setup(bWithout) }.message!! shouldContain "hardware.machine.b.loadout.plate: no build plate on machine 'b'"
    }

    "two machines and none named is refused, naming them; one machine needs no name; a name that is no machine is refused" {
        shouldThrow<ConfigError> { ConfigSetup.setup(config()) }.message!! shouldContain "the config defines a, b: name one with --machine, or job.machine"
        shouldThrow<ConfigError> { config(machine = "c") }.message!! shouldContain "--machine: there is no machine 'c'"
        // control: with b gone, a is the only machine and is used without a name
        val one = Config(ConfigStack(ConfigFiles.bundled() + two.filterNot { it.first.contains(".b.") || it.first.startsWith("hardware.tool.tb") }
            .map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), listOf(box))
        one.machine shouldBe "a"; printed(one).third shouldBe "pla-generic"
    }

    "two files of one numbered group setting the same path are refused, naming both; control: different groups, the later wins" {
        val d = Files.createTempDirectory("plymeld-groups")
        Files.writeString(d.resolve("01_a.toml"), "[hardware.filament.x]\nlike = \"pla-generic\"\ncolor = \"#FF0000\"\n")
        Files.writeString(d.resolve("01_b.toml"), "\n[hardware.filament.x]\ncolor = \"#00FF00\"\n")
        Files.writeString(d.resolve("02_c.toml"), "[hardware.filament.x]\ncolor = \"#0000FF\"\n")
        Files.writeString(d.resolve("plain.toml"), "[hardware.filament.x]\ncolor = \"#FFFFFF\"\n")
        val e = shouldThrow<ConfigError> { ConfigFiles.readFiles(listOf("0*_*.toml"), d) }
        e.problems.single() shouldContain "hardware.filament.x.color is set by two files of group 01: ${d.resolve("01_a.toml")}:3 and ${d.resolve("01_b.toml")}:3"
        // named as two separate arguments, in either order: still one group
        shouldThrow<ConfigError> { ConfigFiles.readFiles(listOf("01_b.toml", "01_a.toml"), d) }
        // control: different groups (01, 02) read in order, the later wins; a file with no number is a group of its own
        fun color(vararg args: String) = ConfigStack(ConfigFiles.readFiles(args.toList(), d)).entry("hardware.filament.x.color".split('.'))!!.value
        color("01_a.toml", "02_c.toml") shouldBe "#0000FF"
        color("01_a.toml", "02_c.toml", "plain.toml") shouldBe "#FFFFFF"
        color("01_a.toml", "01_a.toml") shouldBe "#FF0000"                       // the same file twice is not a conflict with itself
        // a --config-value wins over every file
        ConfigStack.load(listOf("01_a.toml", "02_c.toml"), listOf("hardware.filament.x.color=\"#123456\""), d).entry("hardware.filament.x.color".split('.'))!!.value shouldBe "#123456"
    }
})
