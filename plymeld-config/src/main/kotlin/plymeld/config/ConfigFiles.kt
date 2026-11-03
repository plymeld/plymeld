package plymeld.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import org.tomlj.Toml
import org.tomlj.TomlArray
import org.tomlj.TomlTable
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * Config files: TOML or JSON, by extension, read into one flat
 * list of path = value, each with where it was set. Nothing here knows what the paths mean; the
 * schema does.
 */
object ConfigFiles {
    /**
     * One value as written: [path] as segments, [value] (from a file: a String, or a list of Strings; the pipeline's own entries
     * may carry a Boolean, a Number, or a List), [source] "file:line" or the command line; [keep]: written as `"!value"`, a value
     * kept across re-plans (`plymeld plan` decides every other line of a plan file again), the mark stripped.
     */
    class Entry(val path: List<String>, val value: Any, val source: String, val keep: Boolean = false) {
        val key: String get() = pathText(path)
    }

    /** A value written as "!x" is kept: the mark stripped, [Entry.keep] set. Only a whole quoted value can carry it. */
    private fun marked(v: Any): Pair<Any, Boolean> = if (v is String && v.startsWith("!")) v.substring(1) to true else v to false

    /** A path as written in TOML: segments joined by dots, quoted when they are not bare keys. */
    fun pathText(path: List<String>): String = path.joinToString(".") { if (BARE.matches(it)) it else "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
    private val BARE = Regex("[A-Za-z0-9_-]+")

    fun read(file: Path): List<Entry> = when (file.extension.lowercase()) {
        "toml" -> readToml(file)
        "json" -> readJson(file)
        else -> throw IllegalArgumentException("$file: a config file is .toml or .json")
    }

    private fun readToml(file: Path): List<Entry> {
        val r = Toml.parse(file)
        if (r.hasErrors()) throw IllegalArgumentException(r.errors().joinToString("; ") { "$file:${it.position()?.line() ?: "?"}: ${it.message}" })
        return tomlEntries(r, emptyList(), file.toString())
    }

    /** TOML text, sourced as [name] (a bundled file). */
    fun readToml(text: String, name: String): List<Entry> {
        val r = Toml.parse(text)
        if (r.hasErrors()) throw IllegalArgumentException(r.errors().joinToString("; ") { "$name:${it.position()?.line() ?: "?"}: ${it.message}" })
        return tomlEntries(r, emptyList(), name)
    }

    /** plymeld's bundled config files (resources/config/), read before any other: defaults and placeholder filaments. */
    val BUNDLED = listOf("00_defaults.toml", "01_filaments.toml", "02_plates.toml", "03_models.toml")
    const val BUNDLED_SOURCE = "bundled "

    fun bundled(): List<Entry> = BUNDLED.flatMap { f ->
        val text = ConfigFiles::class.java.getResourceAsStream("/config/$f")?.use { it.readBytes().decodeToString() } ?: error("bundled config $f missing from the build")
        readToml(text, BUNDLED_SOURCE + f)
    }

    private fun tomlEntries(t: TomlTable, prefix: List<String>, file: String): List<Entry> = t.keySet().sorted().flatMap { k ->
        val v = t.get(listOf(k))
        val at = "$file:${t.inputPositionOf(listOf(k))?.line() ?: "?"}"
        when (v) {
            is TomlTable -> tomlEntries(v, prefix + k, file)
            else -> { val (value, keep) = marked(tomlValue(v, "$at: ${pathText(prefix + k)}")); listOf(Entry(prefix + k, value, at, keep)) }
        }
    }

    /**
     * A value as its text: a quoted value as it is ("0.2 mm", "= tool.bore * 1.25", "!2"), a TOML boolean or number as the
     * text its quoted form holds (`true` reads as "true", `2` as "2"), so every rule (units, a bare number's unit, choices)
     * reads both alike. An array of quoted values is a list; an array of numbers is the numbers in one text separated by
     * spaces, as a matrix is written quoted ("1 0 0 0 1 0 0 0 1").
     */
    private fun tomlValue(v: Any?, what: String): Any = when (v) {
        is String -> v
        is Boolean -> v.toString()
        is Long, is Double -> numberText(v as Number)
        is TomlArray -> {
            val xs = (0 until v.size()).map { v.get(it) }
            when {
                xs.all { it is String } -> xs.map { it as String }
                xs.all { it is Long || it is Double } -> xs.joinToString(" ") { numberText(it as Number) }
                else -> throw IllegalArgumentException("$what: an array holds quoted values or numbers, not a mix")
            }
        }
        is TomlTable -> throw IllegalArgumentException("$what: tables inside arrays are not used by plymeld")
        else -> throw IllegalArgumentException("$what: ${v?.let { it::class.simpleName } ?: "nothing"} is not a value plymeld reads (dates and times are not used)")
    }

    /** A number as its quoted form would write it: 2, 0.4, 0.0001 (never 1.0E-4). */
    private fun numberText(n: Number): String = when (n) {
        is Long, is Int -> n.toString()
        is java.math.BigDecimal -> n.stripTrailingZeros().toPlainString()
        else -> java.math.BigDecimal.valueOf(n.toDouble()).stripTrailingZeros().toPlainString()
    }

    private fun readJson(file: Path): List<Entry> {
        val root = runCatching { Json.parseToJsonElement(Files.readString(file)) }.getOrElse { throw IllegalArgumentException("$file: not valid JSON: ${it.message}") }
        require(root is JsonObject) { "$file: the top level must be an object" }
        // a top-level "$schema" names the schema an editor checks the file against (plymeld.schema.json allows it): not a value
        // (it was refused as "not a setting plymeld knows")
        root["\$schema"]?.let { require(it is JsonPrimitive && it.isString) { "$file: \$schema must be text (the schema an editor checks the file against)" } }
        // JSON parsing keeps no line numbers: the file is the source
        return jsonEntries(JsonObject(root - "\$schema"), emptyList(), file.toString())
    }

    private fun jsonEntries(o: JsonObject, prefix: List<String>, file: String): List<Entry> = o.keys.sorted().flatMap { k ->
        when (val v = o.getValue(k)) {
            is JsonObject -> jsonEntries(v, prefix + k, file)
            else -> { val (value, keep) = marked(jsonValue(v, "$file: ${pathText(prefix + k)}")); listOf(Entry(prefix + k, value, file, keep)) }
        }
    }

    /** As [tomlValue]: a boolean or number as its text, an array of numbers as one text. */
    private fun jsonValue(v: JsonElement, what: String): Any = when (v) {
        is JsonArray -> if (v.isNotEmpty() && v.all { it is JsonPrimitive && !it.isString && it.content != "true" && it.content != "false" })
            v.joinToString(" ") { numberText((it as JsonPrimitive).content.toBigDecimal()) } else v.map { jsonValue(it, what) }
        is JsonNull -> throw IllegalArgumentException("$what: null is not a value (leave the key out)")
        is JsonPrimitive -> if (v.isString || v.content == "true" || v.content == "false") v.content else numberText(v.content.toBigDecimal())
        is JsonObject -> throw IllegalArgumentException("$what: objects inside arrays are not used by plymeld")
    }

    /**
     * One `--config-value PATH=VALUE`. PATH is a TOML dotted key (quote segments that need it); VALUE is the text itself, as a
     * file's quoted value would hold it (so `0.2 mm`, `2`, `true` and `!0.2 mm` need no quotes; quotes given are taken off).
     */
    fun value(arg: String, source: String = "command line --config-value"): Entry {
        val eq = arg.indexOf('=')
        require(eq > 0) { "--config-value wants PATH=VALUE, got '$arg'" }
        val path = arg.substring(0, eq).trim(); var text = arg.substring(eq + 1).trim()
        if (text.length >= 2 && text.startsWith("\"") && text.endsWith("\"")) text = text.substring(1, text.length - 1)
        val r = Toml.parse("$path = \"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
        if (r.hasErrors()) throw IllegalArgumentException("--config-value '$arg': ${r.errors().first().message}")
        return tomlEntries(r, emptyList(), source).single().let { Entry(it.path, it.value, "$source '$arg'", it.keep) }
    }

    /**
     * The entries of the config files named by [args] (files or globs, [expand]), in read order, after checking the
     * groups: a file's GROUP is the
     * number its name starts with before an underscore (`01_c5.toml` and `01_q2.toml` are group 1; `1_x.toml` too). Two
     * files of one group setting the same path is an error naming both (wherever in the read order they come, and however
     * they were named: one glob, several, explicit files); a file with no number is a group of its own. Between groups,
     * and for a file without a number, the later in the read order wins as before. The same file read twice is not a
     * conflict with itself.
     */
    fun readFiles(args: List<String>, cwd: Path = Path.of("").toAbsolutePath()): List<Entry> {
        val files = expand(args, cwd)
        val byFile = files.distinct().associateWith { read(it) }
        val problems = ArrayList<String>()
        for ((g, fs) in files.distinct().groupBy { groupOf(it) }) {
            if (g == null || fs.size < 2) continue
            val seen = HashMap<String, Entry>()
            for (f in fs) for (e in byFile.getValue(f)) {
                val had = seen.putIfAbsent(e.key, e) ?: continue
                problems += "${e.key} is set by two files of group ${g.toString().padStart(2, '0')}: ${had.source} and ${e.source}. Within a group the load order must not " +
                    "matter: set it in one file only, or move one to a later-numbered group to override it"
            }
        }
        if (problems.isNotEmpty()) throw ConfigError(problems)
        // the same file named twice (or by a glob and by name) is read once (a plan file passed twice doubled its kept lines)
        return files.distinct().flatMap { byFile.getValue(it) }
    }

    /** A credentials file kept in the config folder (`connection.credential.<name>.file` may name it): not config, never loaded as config. */
    const val SECRETS_FILE = "secrets.toml"

    /**
     * The config files a command reads when given none (`-cf`): every `.toml` file in plymeld's config folder ([UserDirs]), in
     * natural order, except [SECRETS_FILE]; none when the folder does not exist or holds none.
     */
    fun defaultFiles(dirs: UserDirs = UserDirs.current()): List<Path> {
        val dir = dirs.config
        if (!Files.isDirectory(dir)) return emptyList()
        val tomls = Files.list(dir).use { s -> s.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".toml") && it.fileName.toString() != SECRETS_FILE }.toList() }
        return tomls.sortedWith(NATURAL)
    }

    /** A config file's group: the number its name starts with, before an underscore (`01_c5.toml` -> 1); null = none (a group of its own). */
    fun groupOf(file: Path): Int? = Regex("""^(\d+)_""").find(file.fileName.toString())?.groupValues?.get(1)?.toIntOrNull()

    /**
     * The files named by [args], each a file or a glob, in order; a glob's matches in natural sort
     * order of their full path (digit runs as numbers), so the order never depends on the file
     * system. `~/` is the home directory. A glob that matches nothing, or a missing file, is an error.
     */
    fun expand(args: List<String>, cwd: Path = Path.of("").toAbsolutePath()): List<Path> = args.flatMap { a0 ->
        val a = if (a0 == "~" || a0.startsWith("~/")) System.getProperty("user.home") + a0.substring(1) else a0
        if (a.none { it in "*?[{" }) {
            val p = cwd.resolve(a).normalize()
            require(p.isRegularFile()) { "no such file: $a0" }
            listOf(p)
        } else {
            val abs = cwd.resolve(a).normalize().toString()
            // walk from the deepest directory before the first glob character
            val firstGlob = abs.indexOfFirst { it in "*?[{" }
            val base = Path.of(abs.substring(0, abs.lastIndexOf(java.io.File.separatorChar, firstGlob).coerceAtLeast(1)))
            val matcher = FileSystems.getDefault().getPathMatcher("glob:$abs")
            val found = if (!Files.isDirectory(base)) emptyList() else Files.walk(base).use { s ->
                s.filter { it.isRegularFile() && matcher.matches(it) }.toList()
            }
            require(found.isNotEmpty()) { "no file matches '$a0'" }
            found.sortedWith(NATURAL)
        }
    }

    /** Natural order of paths: digit runs compared as numbers, the rest by code point; then the full text. */
    val NATURAL: Comparator<Path> = Comparator { x, y ->
        val a = x.toString(); val b = y.toString()
        val ra = RUNS.findAll(a).map { it.value }.toList(); val rb = RUNS.findAll(b).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val p = ra[i]; val q = rb[i]
            val c = if (p[0].isDigit() && q[0].isDigit()) {
                val pp = p.trimStart('0'); val qq = q.trimStart('0')
                if (pp.length != qq.length) pp.length.compareTo(qq.length) else pp.compareTo(qq)
            } else p.compareTo(q)
            if (c != 0) return@Comparator c
        }
        if (ra.size != rb.size) ra.size.compareTo(rb.size) else a.compareTo(b)
    }
    private val RUNS = Regex("""\d+|\D+""")
}
