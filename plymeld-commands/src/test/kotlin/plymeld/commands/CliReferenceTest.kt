package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.option
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The committed command reference (docs/cli/reference) is what the commands write of themselves
 * (`plymeld commands --markdown docs/cli/reference`), page for page: a command, option, or help text changed in the code
 * and not written again fails here. Controls: the generated pages hold the commands the tree has; a page changed by one
 * word, a page missing, and a page left over are each caught; Markdown in help text is escaped, code spans kept.
 */
class CliReferenceTest : StringSpec({
    val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    val dir = repo.resolve(CliReference.DIR)
    val pages = CliReference.pages()

    /** What differs between the folder's reference pages and [want]: each a file name and why. */
    fun differences(onDisk: Map<String, String>, want: Map<String, String>): List<String> =
        want.keys.filter { it !in onDisk }.map { "$it: missing" } +
            want.filter { (n, t) -> n in onDisk && onDisk[n] != t }.map { "${it.key}: differs from what the commands write" } +
            onDisk.keys.filter { it !in want }.map { "$it: not a page the commands write" }
    fun read(d: Path): Map<String, String> = Files.list(d).use { s -> s.filter { it.fileName.toString().endsWith(".md") }.toList() }
        .associate { it.fileName.toString() to Files.readString(it) }

    "the committed command reference is what the commands write (regenerate with `plymeld commands --markdown docs/cli/reference`)" {
        Files.isDirectory(dir) shouldBe true
        differences(read(dir), pages).shouldBeEmpty()
    }
    "control: the pages hold every top-level command and the subcommands under them" {
        pages.keys shouldContain CliReference.INDEX
        pages.keys shouldContain "slice.md"
        pages.keys shouldContain "calibrate.md"
        pages.getValue("calibrate.md") shouldContain "### `plymeld calibrate melt values`"
        pages.getValue("printer.md") shouldContain "`--print`"
        // a plate runs alone or with its write-back subcommand; a group needs one
        pages.getValue("calibrate.md") shouldContain "Usage: `plymeld calibrate melt [<options>] [<command>] [<args>]...`"
        pages.getValue("printer.md") shouldContain "Usage: `plymeld printer [<options>] <command> [<args>]...`"
        pages.getValue(CliReference.INDEX) shouldContain "[`plymeld printer send`](printer.md#plymeld-printer-send)"
        CommandModel.read().root.subcommands.map { CliReference.file(it) } shouldContainExactly pages.keys.drop(1)
    }
    "every command's usage line, and every option's line, are --help's own" {
        val problems = ArrayList<String>()
        val all = pages.values.joinToString("\n")
        for (c in CommandModel.read().root.all()) {
            val out = StringBuilder(); Commands.invoke(c.path + "--help", { out.append(it).append('\n') })
            val help = out.toString()
            val usage = help.lines().first { it.startsWith("Usage: ") }.removePrefix("Usage: ").trim()
            if ("Usage: `$usage`" !in all) problems += "${c.line}: --help's usage '$usage' is on no page"
            for (o in c.options) if ("- `${o.term}`:" !in all) problems += "${c.line}: no page lists ${o.term} as --help writes it"
        }
        problems.shouldBeEmpty()
    }
    "control: a page changed by one word, a missing page, and a stray page are each caught" {
        val changed = pages.toMutableMap().also { it["slice.md"] = it.getValue("slice.md").replaceFirst("g-code", "gcode") }
        differences(changed, pages) shouldContainExactly listOf("slice.md: differs from what the commands write")
        differences(pages - "init.md", pages) shouldContainExactly listOf("init.md: missing")
        differences(pages + ("gone.md" to "x"), pages) shouldContainExactly listOf("gone.md: not a page the commands write")
    }
    "control: a command added to the tree is a page of its own, its option on it" {
        class Extra : CliktCommand(name = "extra") {
            val level by option("--level", metavar = "N", help = "how much")
            override fun help(context: com.github.ajalt.clikt.core.Context) = "An extra command. More words."
            override fun run() = Unit
        }
        class Root : CliktCommand(name = "plymeld") { override fun run() = Unit }
        val p = CliReference.pages(CommandModel.read(Root().subcommands(Extra())))
        p.keys.toList() shouldContainExactly listOf(CliReference.INDEX, "extra.md")
        p.getValue("extra.md") shouldContain "- `--level=<n>`: how much"
        p.getValue(CliReference.INDEX) shouldContain "- [`plymeld extra`](extra.md): An extra command."
    }
    "help text is escaped for Markdown, its code spans kept" {
        CliReference.md("hardware.machine.<key>.loadout, 'DIR/*.toml', scarf_share, `plymeld journal --undo`") shouldBe
            "hardware.machine.&lt;key&gt;.loadout, 'DIR/\\*.toml', scarf\\_share, `plymeld journal --undo`"
        CliReference.md("an odd ` backtick") shouldBe "an odd \\` backtick"
        CliReference.firstSentence("Slice models (-m) to g-code, e.g. a plate. More.") shouldBe "Slice models (-m) to g-code, e.g. a plate."
    }
    "writing the reference removes a page it wrote earlier that is no longer a command, and no other file" {
        val tmp = Files.createTempDirectory("plymeld-cli-reference-")
        try {
            Files.writeString(tmp.resolve("notes.md"), "my own notes")
            CliReference.write(tmp, mapOf("old.md" to pages.getValue("init.md")))
            CliReference.write(tmp, mapOf(CliReference.INDEX to pages.getValue(CliReference.INDEX)))
            read(tmp).keys shouldBe setOf(CliReference.INDEX, "notes.md")
        } finally {
            Files.walk(tmp).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
})
