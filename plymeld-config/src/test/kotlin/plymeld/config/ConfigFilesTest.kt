package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/** Config files, units, layering. */
class ConfigFilesTest : StringSpec({
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-config").also { d ->
        for ((name, text) in files) { val p = d.resolve(name); Files.createDirectories(p.parent); Files.writeString(p, text) }
    }

    // An editor finds a JSON file's schema by its top-level "$schema" (plymeld refused it as "not a setting plymeld
    // knows"). Ignored at the top, allowed by plymeld.schema.json; controls: one deeper down, or not text, is still refused.
    "a JSON config's top-level \$schema is ignored and allowed by the schema; control: one deeper down or not text is refused" {
        val d = dir("a.json" to """{ "${'$'}schema": "./plymeld.schema.json", "plan": { "technique": { "flat": { "layer": { "height": "0.2 mm" } } } } }""",
            "deep.json" to """{ "plan": { "${'$'}schema": "x" } }""",
            "num.json" to """{ "${'$'}schema": 3 }""")
        ConfigFiles.read(d.resolve("a.json")).map { it.key } shouldBe listOf("plan.technique.flat.layer.height")
        ConfigFiles.read(d.resolve("deep.json")).map { it.key } shouldBe listOf("plan.\"${'$'}schema\"")      // read, then refused as unknown by Config
        shouldThrow<IllegalArgumentException> { ConfigFiles.read(d.resolve("num.json")) }.message!! shouldContain "must be text"
        SchemaJson.document()["properties"].toString() shouldContain "\"${'$'}schema\""
    }
    "units: with or without a space, any unit of the dimension, converted to the base unit" {
        Units.parse("0.2 mm", Dimension.LENGTH, "x").value shouldBe (0.2 plusOrMinus 1e-12)
        Units.parse("0.2mm", Dimension.LENGTH, "x").value shouldBe (0.2 plusOrMinus 1e-12)
        Units.parse("2 cm", Dimension.LENGTH, "x").value shouldBe (20.0 plusOrMinus 1e-12)
        Units.parse("4 h", Dimension.TIME, "x").value shouldBe (14400.0 plusOrMinus 1e-9)
        Units.parse("212F", Dimension.TEMPERATURE, "x").value shouldBe (100.0 plusOrMinus 1e-9)
        Units.parse("15 mm3/s", Dimension.FLOW, "x").value shouldBe (15.0 plusOrMinus 1e-12)
    }
    "with no -cf, the config folder's .toml files in natural order, the secrets file and other files left out; control: none there, none read" {
        val home = dir("config/10_b.toml" to "", "config/2_c.toml" to "", "config/a.toml" to "", "config/secrets.toml" to "x = \"y\"\n", "config/notes.txt" to "")
        fun dirs(h: Path) = UserDirs(h.resolve("config"), h.resolve("data"), h.resolve("state"), h.resolve("cache"))
        ConfigFiles.defaultFiles(dirs(home)).map { it.fileName.toString() } shouldBe listOf("2_c.toml", "10_b.toml", "a.toml")
        ConfigFiles.defaultFiles(dirs(home.resolve("nowhere"))) shouldBe emptyList()
    }
    // a TOML (or JSON) boolean, number or array of numbers reads as the text its quoted form holds, so both read alike;
    // a value starting with ! is kept, the mark read off; -cv takes the text as the quoted value would hold it
    "a bare boolean, number or array of numbers reads as its quoted form; control: an array mixing the two is refused" {
        val bare = "[plan.mechanism.motion]\navoid_crossing = false\n[plan.support.interface]\ntop_layers = 2\n[plan.object.x]\npose.turn = [1, 0, 0, 0, 1, 0, 0, 0, 1]\n" +
            "[plan.mechanism.adhesion.brim]\nwidth = 0.0001\n"
        val quoted = "[plan.mechanism.motion]\navoid_crossing = \"false\"\n[plan.support.interface]\ntop_layers = \"2\"\n[plan.object.x]\npose.turn = \"1 0 0 0 1 0 0 0 1\"\n" +
            "[plan.mechanism.adhesion.brim]\nwidth = \"0.0001\"\n"
        val d = dir("bare.toml" to bare, "quoted.toml" to quoted, "mixed.toml" to "[plan.object.x]\npose.turn = [1, \"0\", 0]\n",
            "j.json" to """{ "plan": { "support": { "interface": { "top_layers": 2 } }, "object": { "x": { "pose": { "turn": [1, 0, 0, 0, 1, 0, 0, 0, 1] } } } } }""")
        fun read(f: String) = ConfigFiles.read(d.resolve(f)).associate { it.key to it.value }
        read("bare.toml") shouldBe read("quoted.toml")
        read("bare.toml")["plan.mechanism.adhesion.brim.width"] shouldBe "0.0001"   // never 1.0E-4
        read("j.json")["plan.support.interface.top_layers"] shouldBe "2"
        read("j.json")["plan.object.x.pose.turn"] shouldBe "1 0 0 0 1 0 0 0 1"
        // read through Config the bare boolean is the setting's boolean, as the quoted one is
        Config(ConfigStack(ConfigFiles.bundled() + ConfigFiles.read(d.resolve("bare.toml"))), emptyList())
            .entry("plan.mechanism.motion.avoid_crossing".split('.'))!!.value shouldBe false
        shouldThrow<IllegalArgumentException> { ConfigFiles.read(d.resolve("mixed.toml")) }.message!! shouldContain "not a mix"
    }
    "a value starting with ! is kept, the mark read off; in a file, in JSON and on the command line; control: without it, not kept" {
        val d = dir("k.toml" to "[plan.technique.flat]\nlayer.height = \"!0.2 mm\"\n", "k.json" to """{ "plan": { "technique": { "flat": { "layer": { "height": "!0.2 mm" } } } } }""")
        for (f in listOf("k.toml", "k.json")) ConfigFiles.read(d.resolve(f)).single().let { it.value shouldBe "0.2 mm"; it.keep shouldBe true }
        ConfigFiles.value("plan.technique.flat.layer.height=!0.2 mm").let { it.value shouldBe "0.2 mm"; it.keep shouldBe true }
        ConfigFiles.value("plan.technique.flat.layer.height=0.2 mm").let { it.value shouldBe "0.2 mm"; it.keep shouldBe false }
        ConfigFiles.value("plan.mechanism.motion.avoid_crossing=true").value shouldBe "true"      // the text, not a TOML boolean
        ConfigFiles.value("job.priorities.speed=\"5\"").value shouldBe "5"                        // quotes given are taken off
    }
    "a switch and a Numbers value are read from their quotes into their types" {
        val d = dir("t.toml" to "[plan.mechanism.motion]\navoid_crossing = \"false\"\n[plan.object.x]\npose.turn = \"1 0 0 0 1 0 0 0 1\"\n")
        val c = Config(ConfigStack(ConfigFiles.bundled() + ConfigFiles.read(d.resolve("t.toml"))), emptyList())
        c.entry("plan.mechanism.motion.avoid_crossing".split('.'))!!.value shouldBe false
        c.entry(listOf("plan", "object", "x", "pose", "turn"))!!.value shouldBe listOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
    }
    "units: a bare number is the default unit and earns a warning; control: with a unit, none" {
        val bare = Units.parse(0.2, Dimension.LENGTH, "layer")
        bare.value shouldBe (0.2 plusOrMinus 1e-12)
        bare.warning!! shouldContain "no unit"
        Units.parse("0.2 mm", Dimension.LENGTH, "layer").warning shouldBe null
    }
    "units: a multiplier is 0.15 or 15% (15 per 100); counts and weights are plain numbers, without warnings" {
        Units.parse(0.15, Dimension.RATIO, "x").value shouldBe (0.15 plusOrMinus 1e-12)
        Units.parse("15%", Dimension.RATIO, "x").value shouldBe (0.15 plusOrMinus 1e-12)
        Units.parse("15 %", Dimension.RATIO, "x").warning shouldBe null
        Units.parse(3L, Dimension.COUNT, "x").warning shouldBe null
        Units.parse(10L, Dimension.WEIGHT, "x").warning shouldBe null
    }
    "units: a wrong unit, a fractional count, text that is not a number are errors" {
        shouldThrow<IllegalArgumentException> { Units.parse("0.2 s", Dimension.LENGTH, "layer") }.message!! shouldContain "not a unit of length"
        shouldThrow<IllegalArgumentException> { Units.parse(2.5, Dimension.COUNT, "count") }
        shouldThrow<IllegalArgumentException> { Units.parse("thin", Dimension.LENGTH, "layer") }
    }
    "TOML and JSON read into the same paths; TOML keeps the line" {
        val d = dir("a.toml" to "[job.priorities]\nquality = \"10\"\n\n[hardware.filament.\"pla-white\"]\nnozzle.temp = \"210 C\"\n",
            "b.json" to """{"job": {"priorities": {"quality": "10"}}, "hardware": {"filament": {"pla-white": {"nozzle": {"temp": "210 C"}}}}}""")
        val t = ConfigFiles.read(d.resolve("a.toml")).associate { it.key to it }
        val j = ConfigFiles.read(d.resolve("b.json")).associate { it.key to it }
        t.keys shouldBe j.keys
        t.getValue("job.priorities.quality").value shouldBe "10"
        j.getValue("job.priorities.quality").value shouldBe "10"
        t.getValue("hardware.filament.pla-white.nozzle.temp").source shouldContain "a.toml:5"
    }
    "layering: for the same path the later source wins; --config-value wins over files" {
        val d = dir("1.toml" to "[hardware.filament.pla]\nnozzle.temp = \"205 C\"\n", "2.toml" to "[hardware.filament.pla]\nnozzle.temp = \"215 C\"\n")
        val s = ConfigStack.load(listOf("1.toml", "2.toml"), emptyList(), d)
        s.entry(listOf("hardware", "filament", "pla", "nozzle", "temp"))!!.value shouldBe "215 C"
        val s2 = ConfigStack.load(listOf("1.toml", "2.toml"), listOf("hardware.filament.pla.nozzle.temp=220C"), d)
        s2.entry(listOf("hardware", "filament", "pla", "nozzle", "temp"))!!.value shouldBe "220C"
        // control: reversed file order, the other value wins
        ConfigStack.load(listOf("2.toml", "1.toml"), emptyList(), d).entry(listOf("hardware", "filament", "pla", "nozzle", "temp"))!!.value shouldBe "205 C"
    }
    "a value for an object or part wins over the general one, whatever the order" {
        val d = dir("obj.toml" to "[plan.object.benchy.part.hull.technique.flat]\nlayer.height = \"0.24 mm\"\n",
            "general.toml" to "[plan.technique.flat]\nlayer.height = \"0.08 mm\"\n")
        val c = Config(ConfigStack.load(listOf("obj.toml", "general.toml"), emptyList(), d, bundled = false),   // the general file comes later
            listOf(ModelObject("benchy", listOf("hull", "deck"), listOf(1, 1), "benchy.3mf")))
        val rest = listOf("technique", "flat", "layer", "height")
        c.scoped("plan", rest, Site(obj = c.objects[0], part = "hull"))!!.value shouldBe "0.24 mm"
        c.scoped("plan", rest, Site(obj = c.objects[0], part = "deck"))!!.value shouldBe "0.08 mm"     // control: another part gets the general value
        c.scoped("plan", rest, Site())!!.value shouldBe "0.08 mm"
    }
    "--config-value: the text as a quoted value would hold it, quoted path segments" {
        ConfigFiles.value("job.priorities.speed=5").value shouldBe "5"
        ConfigFiles.value("plan.technique.flat.layer.height=0.2 mm").value shouldBe "0.2 mm"
        ConfigFiles.value("model.object.\"pp oval\".count=2").path shouldBe listOf("model", "object", "pp oval", "count")
        shouldThrow<IllegalArgumentException> { ConfigFiles.value("no equals sign") }
    }
    "globs expand in natural order (2 before 10), whatever the file system lists; files and globs mix in argument order" {
        val d = dir("setup/10_JOB.toml" to "", "setup/2_PLATE.toml" to "", "setup/01_HARDWARE.toml" to "", "setup/01_FILAMENT.toml" to "", "extra.json" to "{}")
        val names = ConfigFiles.expand(listOf("extra.json", "setup/*.toml"), d).map { it.fileName.toString() }
        names shouldBe listOf("extra.json", "01_FILAMENT.toml", "01_HARDWARE.toml", "2_PLATE.toml", "10_JOB.toml")
        shouldThrow<IllegalArgumentException> { ConfigFiles.expand(listOf("setup/*.yaml"), d) }.message!! shouldContain "no file matches"
        shouldThrow<IllegalArgumentException> { ConfigFiles.expand(listOf("missing.toml"), d) }
    }
})
