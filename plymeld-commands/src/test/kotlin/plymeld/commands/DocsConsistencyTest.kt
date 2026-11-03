package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.Schema
import plymeld.model.Catalog
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * The published documentation against the code and against itself, read from the documents, so a document cannot
 * drift from what plymeld is without a test failing:
 *  - every config path written in backticks (`hardware.…`, `model.…`, `job.…`, `plan.…`, `method.…`) is one the schema
 *    declares, a setting the catalog has, or a section or scope of one;
 *  - every relative link resolves to a published file, and every `#anchor` to a heading of that file (GitHub's rule
 *    for heading anchors);
 *  - every `plymeld …` command written in backticks or a code block names commands that exist, and options that
 *    command has.
 * Each check has a control: a document with one thing wrong is caught. The documents: every Markdown file the
 * repository publishes (docs/ and the root's and the modules' own), not the local, untracked ones ([unpublished]),
 * which a checkout may or may not have.
 */
class DocsConsistencyTest : StringSpec({
    val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    val unpublished = setOf("TODO.md", "CONSULTED.md", "CLAUDE.md")
    fun rel(p: Path) = repo.relativize(p).toString()
    fun published(p: Path) = rel(p) !in unpublished
    val docs: List<Path> by lazy {
        val top = Files.list(repo).use { s -> s.toList() }.filter { it.isRegularFile() && it.extension == "md" }
        val nested = listOf("docs", "tools", "test-models", "plymeld-geom").map { repo.resolve(it) }.filter { Files.isDirectory(it) }
            .flatMap { d -> Files.walk(d).use { s -> s.toList() }.filter { it.isRegularFile() && it.extension == "md" && "/build/" !in it.toString() } }
        (top + nested).filter { published(it) }.sorted()
    }

    // ---- config paths
    val roots = setOf("hardware", "model", "job", "plan", "method")
    val declared: List<List<String>> by lazy { Schema.decls.map { it.path.split('.') } }
    val ids: Set<String> by lazy { Catalog.settings.map { it.id }.toSet() }
    /**
     * Whether [written] names a declared path or a setting, or a section of one (`plan.layer`, `plan.object.<key>`): a
     * `<…>` segment on either side stands for any name, and a `*` in a segment for any text (a trailing `.*`).
     */
    fun known(written: String): Boolean {
        val want = written.split('.').let { if (it.last() == "*") it.dropLast(1) else it }
        if (want.none { it.startsWith("<") || '*' in it } && (Schema.find(want) != null || written in ids)) return true
        if (Regex("(job|plan)\\.object(\\.[^.]+(\\.part(\\.[^.]+)?)?)?").matches(want.joinToString("."))) return true   // a scope itself
        fun seg(w: String, d: String) = w == d || w.startsWith("<") || d.startsWith("<") ||
            ('*' in w && Regex(w.split('*').joinToString(".*") { Regex.escape(it) }).matches(d))
        fun under(d: List<String>) = d.size >= want.size && want.indices.all { seg(want[it], d[it]) }
        // an object's or a part's value is the general path under plan.object.<key> / job.object.<key> (.part.<key>)
        val unscoped = if (want.size > 3 && want[1] == "object" && (want[0] == "plan" || want[0] == "job"))
            listOf(want[0]) + (if (want.size > 5 && want[3] == "part") want.drop(5) else want.drop(3)) else null
        return declared.any { under(it) } || ids.any { id -> under(id.split('.')) } || (unscoped != null && known(unscoped.joinToString(".")))
    }
    val pathToken = Regex("`(${roots.joinToString("|")})\\.([A-Za-z0-9_<>*.-]+)")
    /** The config paths written in backticks in [text]: (line, path); file names (`plan.toml`) are not paths. */
    fun paths(text: String): List<Pair<Int, String>> = text.lines().flatMapIndexed { i, line ->
        pathToken.findAll(line).map { m -> "${m.groupValues[1]}.${m.groupValues[2]}".trimEnd('.') }
            .map { p -> if (p.count { it == '>' } > p.count { it == '<' }) p.dropLast(1) else p }
            .filterNot { Regex("\\.(toml|json|gcode)$").containsMatchIn(it) }.map { i + 1 to it }.toList()
    }
    fun unknownPaths(name: String, text: String) = paths(text).filter { (_, p) -> !known(p) }.map { (n, p) -> "$name:$n: $p" }

    "every config path the documentation writes is one plymeld has" {
        docs.shouldNotBeEmpty()
        val found = docs.flatMap { paths(it.readText()) }
        found.size shouldBeGreaterThan 20                          // the documents do name paths: the check read them
        docs.flatMap { unknownPaths(rel(it), it.readText()) }.joinToString("\n") shouldBe ""
    }
    "control: a path no one declares is caught, and real ones in the same shapes are not" {
        unknownPaths("t", "`plan.layer.walls.no_such_thing` and `hardware.tool.<key>.made.up`").size shouldBe 2
        unknownPaths("t", "`plan.seam = \"back_seam\"`, `hardware.filament.<key>.melt.flow.max`, `plan.technique.ironing.*`, " +
            "`plan.object.<key>.layer.walls.thickness`, `job.object.<key>`, `plan.layer`, `plan.toml`").shouldBeEmpty()
    }

    // ---- links
    /** GitHub's anchor for a heading: lower case, punctuation but hyphens and underscores dropped, spaces as hyphens. */
    fun slug(heading: String) = heading.trim().lowercase().replace(Regex("[`*\\[\\]()]"), "")
        .replace(Regex("[^\\p{L}\\p{N} _-]"), "").replace(' ', '-')
    fun anchors(text: String): Set<String> {
        val seen = HashMap<String, Int>(); val out = HashSet<String>()
        var fence = false
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) { fence = !fence; continue }
            if (fence) continue
            val m = Regex("^#{1,6} (.+)$").find(line) ?: continue
            val s = slug(m.groupValues[1]); val n = seen.merge(s, 1, Int::plus)!!
            out += if (n == 1) s else "$s-${n - 1}"
        }
        return out
    }
    val link = Regex("\\]\\(([^)\\s]+)\\)")
    fun brokenLinks(file: Path, text: String): List<String> = text.lines().flatMapIndexed { i, line ->
        link.findAll(line).mapNotNull { m ->
            val target = m.groupValues[1]
            if (Regex("^[a-z]+:").containsMatchIn(target)) return@mapNotNull null          // http(s), mailto
            val (pathPart, anchor) = target.split('#', limit = 2).let { it[0] to it.getOrNull(1) }
            val dest = if (pathPart.isEmpty()) file else file.parent.resolve(pathPart).normalize()
            val why = when {
                !Files.exists(dest) -> "no such file"
                !published(dest) -> "not published"
                anchor != null && dest.extension == "md" && anchor !in anchors(dest.readText()) -> "no heading '#$anchor' there"
                else -> null
            }
            why?.let { "${rel(file)}:${i + 1}: $target: $it" }
        }.toList()
    }

    "every relative link in the documentation resolves, anchors included" {
        val links = docs.sumOf { d -> link.findAll(d.readText()).count() }
        links shouldBeGreaterThan 40
        docs.flatMap { brokenLinks(it, it.readText()) }.joinToString("\n") shouldBe ""
    }
    "control: a link to a missing file and one to a missing heading are caught; good ones are not" {
        val readme = repo.resolve("README.md")
        brokenLinks(readme, "[a](docs/no-such-page.md) [b](CONTRIBUTING.md#no-such-heading)").size shouldBe 2
        brokenLinks(readme, "[a](TODO.md)").size shouldBe 1          // local only: not published here, missing in a clean checkout
        brokenLinks(readme, "[a](CONTRIBUTING.md) [b](LICENSE) [c](https://example.com/x)").shouldBeEmpty()
    }

    // ---- commands
    val model by lazy { CommandModel.read() }
    /**
     * What is wrong with the command line [words] (after `plymeld`), each as the command line up to the wrong word
     * (`plymeld frobnicate`, `plymeld slice --no-such-option`): a command that does not exist, or an option its command
     * lacks. A `<kind>` or `...` word stands for any subcommand and `melt|motion` for those named; a group named without
     * a subcommand takes its subcommands' options. In a code block ([fenced]) a line whose first word is no command and
     * that has no option is the program's output (help text), not a command.
     */
    fun commandProblems(words: List<String>, fenced: Boolean): List<String> {
        var at = listOf(model.root); var i = 0
        fun line(w: String) = "${at.first().line} $w"
        while (i < words.size) {
            val w = words[i]
            val any = w.startsWith("<") || w == "..." || w == "…"
            val alternatives = w.split('|')
            val next = at.flatMap { c -> c.subcommands.filter { any || it.name in alternatives } }
            if (next.isEmpty()) break
            val missing = if (any) emptyList() else alternatives.filter { a -> at.none { c -> c.subcommands.any { it.name == a } } }
            if (missing.isNotEmpty()) return missing.map { line(it) }
            at = next; i++
        }
        val options = words.drop(i).map { it.trim('[', ']', '(', ')', ',') }
            .filter { it.startsWith("-") && it.length > 1 && it != "--" && !it[1].isDigit() }.map { it.substringBefore('=') }
        val rest = words.getOrNull(i)
        if (rest != null && !rest.startsWith("-") && !rest.startsWith("[") && at.all { it.subcommands.isNotEmpty() && it.arguments.isEmpty() })
            return if (fenced && options.isEmpty()) emptyList() else listOf(line(rest))
        val names = at.flatMap { if (it === model.root) listOf(it) else it.all() }.flatMap { c -> c.options.flatMap { it.names } }.toSet() + setOf("-h", "--help")
        return options.filter { it !in names }.map { line(it) }
    }
    /** The `plymeld …` command lines in [text]: in backticks, or a code block's line starting with it; (line, words, fenced). */
    fun commandLines(text: String): List<Triple<Int, List<String>, Boolean>> {
        val out = ArrayList<Triple<Int, List<String>, Boolean>>(); var fence = false
        for ((i, line) in text.lines().withIndex()) {
            if (line.trimStart().startsWith("```")) { fence = !fence; continue }
            val candidates = if (fence) listOf(line.trim()).filter { it.startsWith("plymeld ") }.map { it.substringBefore(" #") }
                else Regex("`(plymeld [^`]+)`").findAll(line).map { it.groupValues[1] }.toList()
            for (c in candidates) out += Triple(i + 1, c.split(Regex("\\s+")).drop(1).filter { it.isNotEmpty() }, fence)
        }
        return out
    }
    fun badCommands(name: String, text: String) = commandLines(text).flatMap { (n, w, f) -> commandProblems(w, f).map { "$name:$n: $it" } }

    "every plymeld command the documentation writes exists, with options its command has" {
        docs.sumOf { commandLines(it.readText()).size } shouldBeGreaterThan 20
        docs.flatMap { badCommands(rel(it), it.readText()) }.joinToString("\n") shouldBe ""
    }
    "control: an unknown command and an option a command lacks are caught; real ones are not" {
        badCommands("t", "`plymeld frobnicate` and `plymeld slice --no-such-option` and `plymeld calibrate melt|nope -o x`").size shouldBe 3
        badCommands("t", "```\nplymeld frobnicate -m x\n```").size shouldBe 1
        badCommands("t", "`plymeld slice -m a.stl -o a.gcode --explain` and `plymeld printer loadout --machine m` and " +
            "`plymeld calibrate <kind> -cf a.toml [--like NAME] -o p.gcode` and `plymeld calibrate melt|motion -o p.gcode` and " +
            "`plymeld calibrate ...`").shouldBeEmpty()
        badCommands("t", "```\nplymeld — a slicer. Commands: slice (models to g-code)\n```").shouldBeEmpty()
    }
})
