package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldEndWith
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The command model read off the Clikt tree: what `--help` says, in a form a program (the UI, the parity test) reads. */
class CommandModelTest : StringSpec({
    val model = CommandModel.read()
    val all = model.root.all()
    fun cmd(line: String) = all.first { it.line == line }

    "every command of the tree is in the model, with the subcommands under their parents" {
        all.map { it.line } shouldContain "plymeld"
        all.map { it.line } shouldContain "plymeld printer loadout"
        all.map { it.line } shouldContain "plymeld calibrate first-layer values"
        all.map { it.line } shouldContain "plymeld investigate predict"
        all.map { it.line } shouldContain "plymeld commands"
        cmd("plymeld").subcommands.map { it.name } shouldContainExactly Commands.tree().registeredSubcommandNames()
    }
    "an option is as its help describes it: names, metavar, repeatable, required, a flag's no value, choices" {
        val cf = cmd("plymeld slice").options.first { "-cf" in it.names }
        cf.names shouldContainExactly listOf("-cf", "--config-file")
        cf.metavar shouldBe "FILE|GLOB"
        cf.repeated shouldBe true
        cf.flag shouldBe false
        val m = cmd("plymeld slice").options.first { "-m" in it.names }
        m.required shouldBe true
        val explain = cmd("plymeld plan").options.first { "--explain" in it.names }
        explain.flag shouldBe true
        explain.metavar shouldBe null
        cmd("plymeld printer loadout").options.first { "--api" in it.names }.choices shouldContainExactly listOf("flashforge-http")
    }
    "a flag carries no empty default tag; a value's default is kept" {
        cmd("plymeld plan").options.first { "--explain" in it.names }.tags.keys.shouldBeEmpty()
        cmd("plymeld investigate slice").options.first { "--copies" in it.names }.tags["default"] shouldBe "1"
    }
    "the help option is not an option of the model (it is Clikt's, on every command alike)" {
        all.flatMap { it.options }.flatMap { it.names }.filter { it == "-h" || it == "--help" }.shouldBeEmpty()
    }
    "an argument is in the model with its help and whether it is required" {
        val prefix = cmd("plymeld config").arguments.single()
        prefix.required shouldBe false
        prefix.help shouldContain "paths starting with this"
    }
    "the config paths are the schema's, each with its scopes" {
        model.paths.map { it.path } shouldContain "plan.technique.flat.layer.height"
        model.paths.first { it.path == "plan.pause.<key>.at" }.scopes shouldContainExactly listOf("plate")
        model.paths.first { it.path == "plan.technique.flat.layer.height" }.scopes shouldContainExactly listOf("plate", "object", "part")
    }
    "the text lists each command's line and the config paths; the JSON holds the same" {
        val text = model.text()
        text shouldStartWith "plymeld\n"
        text shouldContain "\nplymeld printer loadout\n"
        text shouldContain "-cf, --config-file=FILE|GLOB (repeatable)"
        text shouldContain "config paths ("
        val j = Json.parseToJsonElement(model.json()).jsonObject
        val root = j["commands"]!!.jsonObject
        root["path"]!!.jsonArray.size shouldBe 0
        fun lines(c: JsonObject): List<String> = listOf((listOf("plymeld") + c["path"]!!.jsonArray.map { it.jsonPrimitive.content }).joinToString(" ")) +
            c["subcommands"]!!.jsonArray.flatMap { lines(it.jsonObject) }
        lines(root) shouldContainExactly all.map { it.line }
        (j["config_paths"] as JsonArray).size shouldBe model.paths.size
    }
    "a small tree of its own reads the same way (the walk is of the tree given, not of plymeld's)" {
        class Leaf : CliktCommand(name = "leaf") {
            val kind by option("--kind", help = "which kind").choice("a", "b").required()
            val many by option("--many", help = "a value; repeatable").multiple()
            val on by option("--on", help = "a switch").flag()
            override fun help(context: com.github.ajalt.clikt.core.Context) = "a leaf"
            override fun run() = Unit
        }
        class Root : CliktCommand(name = "root") { override fun run() = Unit }
        val m = CommandModel.read(Root().subcommands(Leaf()))
        val leaf = m.root.subcommands.single()
        leaf.path shouldBe listOf("leaf")
        leaf.help shouldBe "a leaf"
        leaf.options.map { it.names.last() } shouldContainExactly listOf("--kind", "--many", "--on")
        leaf.options[0].choices shouldContainExactly listOf("a", "b")
        leaf.options[0].required shouldBe true
        leaf.options[1].repeated shouldBe true
        leaf.options[2].flag shouldBe true
    }
    "control: every option whose help says repeatable is read as repeated, and no other" {
        for (o in all.flatMap { it.options }) o.repeated shouldBe ("repeatable" in o.help)
    }
    "every option with a default says it in its help (--help shows no default of its own), and its term and description are --help's" {
        val missing = all.flatMap { c -> c.options.filter { "default" in it.tags && !it.help.contains("default", ignoreCase = true) }.map { "${c.line} ${it.term}" } }
        withClue(missing.joinToString("\n")) { missing.shouldBeEmpty() }
        // as --help writes them: the names joined, the value attached; a required option tagged
        val slice = all.single { it.line == "plymeld slice" }
        slice.options.single { "-cf" in it.names }.term shouldBe "-cf, --config-file=FILE|GLOB"
        slice.options.single { "--machine" in it.names }.term shouldBe "--machine=<key>"
        slice.options.single { "-o" in it.names }.description shouldEndWith "(required)"
        all.single { it.line == "plymeld config" }.arguments.single().term shouldBe "<prefix>"
        // control: an option not required carries no tag
        slice.options.single { "--machine" in it.names }.description shouldNotContain "(required)"
    }
})
