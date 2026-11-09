package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrowAny
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * The `plymeld` commands the documentation shows run as written. They are read from the documents' fenced blocks, so a
 * document and this test cannot drift apart. They run against a config as `plymeld init` writes it for a QIDI Q2 with
 * PLA loaded (every default taken), which stands for "your config", and from the repository's root. A model the
 * repository holds (in it or under `test-models/`) is taken from there; any other model a document names stands for
 * "your model" and is the 3DBenchy. Outputs (`-o`) and the files an earlier command wrote go to a temporary folder.
 *
 * Not run, by command: those that need a person or a printer (`init`, `printer`, `report`), and those that change the
 * install or the user's settings (`update`, `updates`); each such line is still read, so a document showing one of them
 * with a misspelled command name fails. Control: a documented command misspelled is refused.
 */
class DocumentedCommandsTest : StringSpec({
    val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    val tmp: Path = Files.createTempDirectory("plymeld-documented")

    /** A config folder as `plymeld init` writes it: a QIDI Q2 ("q2") with every default taken, PLA in its tool. */
    val config: Path = Files.createDirectories(tmp.resolve("config")).also { dir ->
        val c = FirstUse.choices()
        val m = c.models.first { it.id == "qidi-q2" }
        val values = FirstUse.printerAsks(m).mapNotNull { a -> a.default?.let { a.path to it } }.toMap()
        val tool = FirstUse.toolAsks(m).mapNotNull { a -> a.default?.let { a.path to it } }.toMap()
        val printer = FirstUse.Printer("q2", m.id, values, listOf(FirstUse.Tool("q2-t1", 1, tool, "pla")), c.plates.first(), emptyMap())
        FirstUse.write(FirstUse.files(FirstUse.Answers(listOf(printer), listOf(FirstUse.Filament("pla", "pla-generic", "#FFFFFF"))), dir), dir)
    }

    /** The documents whose commands are run. */
    val documents = listOf("README.md", "docs/user/tutorials/install.md", "docs/user/tutorials/first-slice.md")

    /** Commands shown but not run (see the class comment). */
    val notRun = setOf("init", "printer", "report", "update", "updates", "--version", "--help")

    /** Words of a shell line: whitespace-separated, single or double quotes kept together (and dropped), a `#` comment ended. */
    fun words(line: String): List<String> {
        val out = ArrayList<String>(); val w = StringBuilder(); var quote: Char? = null; var inWord = false
        for (ch in line) {
            when {
                quote != null -> if (ch == quote) quote = null else w.append(ch)
                ch == '\'' || ch == '"' -> { quote = ch; inWord = true }
                ch == '#' && !inWord -> break
                ch.isWhitespace() -> { if (inWord) { out += w.toString(); w.clear(); inWord = false } }
                else -> { w.append(ch); inWord = true }
            }
        }
        if (inWord) out += w.toString()
        return out
    }

    /** Every `plymeld ...` line in [doc]'s fenced blocks (continuation lines joined), as its words after `plymeld`. */
    fun commands(doc: String): List<List<String>> {
        val out = ArrayList<List<String>>(); var fenced = false; var pending: String? = null
        for (raw in Files.readAllLines(repo.resolve(doc))) {
            if (raw.trimStart().startsWith("```")) { fenced = !fenced; pending = null; continue }
            if (!fenced) continue
            val line = (pending?.let { "$it " } ?: "") + raw.trim()
            if (line.endsWith("\\")) { pending = line.removeSuffix("\\").trim(); continue }
            pending = null
            val w = words(line)
            if (w.isNotEmpty() && w[0] == "plymeld") out += w.drop(1)
        }
        return out
    }

    /** [path] as the command finds it: what an earlier command wrote; in the repository; or, for a model, the 3DBenchy. */
    fun located(path: String, model: Boolean): String {
        val written = tmp.resolve(Path.of(path).fileName)
        if (Files.exists(written)) return written.toString()
        val inRepo = repo.resolve(path)
        if (!path.contains('*') && Files.exists(inRepo)) return inRepo.toString()
        if (path.contains('*')) return repo.resolve(path).toString()
        if (model) {
            val test = repo.resolve("test-models").resolve(Path.of(path).fileName)
            return (if (Files.exists(test)) test else repo.resolve("test-models/3DBenchy.stl")).toString()
        }
        return written.toString()
    }

    /** Runs [words] (after `plymeld`), with the config above unless the line names its own (-cf). */
    fun run(words: List<String>) {
        val args = ArrayList<String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            args += w
            when {
                w in setOf("-m", "--model") && i + 1 < words.size -> { args += located(words[i + 1], model = true); i++ }
                w in setOf("-o", "--output", "--gcode", "--from") && i + 1 < words.size ->
                    { args += if (w == "-o" || w == "--output") tmp.resolve(Path.of(words[i + 1]).fileName).toString() else located(words[i + 1], model = false); i++ }
                w in setOf("-cf", "--config-file") && i + 1 < words.size -> { args += located(words[i + 1], model = false); i++ }
            }
            i++
        }
        val takesConfig = words.firstOrNull() in setOf("slice", "plan", "config")
        if (takesConfig && "-cf" !in words && "--config-file" !in words) { args += "-cf"; args += config.resolve("*.toml").toString() }
        Commands.tree().parse(args)
    }

    for (doc in documents) {
        "the plymeld commands in $doc run as written" {
            val cmds = commands(doc)
            cmds.shouldNotBeEmpty()
            val names = Commands.tree().registeredSubcommands().map { it.commandName }.toSet()
            for (c in cmds) {
                withClue("plymeld ${c.joinToString(" ")}") {
                    // every command shown is one plymeld has (or the --version / --help option)
                    (c.first() in names || c.first() in setOf("--version", "--help")) shouldBe true
                    if (c.first() !in notRun) {
                        run(c)
                        // what the command was asked to write is there
                        c.indexOf("-o").takeIf { it >= 0 }?.let { Files.exists(tmp.resolve(Path.of(c[it + 1]).fileName)) shouldBe true }
                    }
                }
            }
        }
    }

    "control: a documented command misspelled is refused" {
        (shouldThrowAny { run(listOf("slise", "-m", "3DBenchy.stl", "-o", "x.gcode")) } is com.github.ajalt.clikt.core.UsageError) shouldBe true
        // and the name check the documents' lines go through finds it missing
        ("slise" in Commands.tree().registeredSubcommands().map { it.commandName }) shouldBe false
    }
})
