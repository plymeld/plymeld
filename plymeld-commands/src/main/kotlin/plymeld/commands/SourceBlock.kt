package plymeld.commands

import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import plymeld.config.UsedEntries
import java.nio.file.Files
import java.nio.file.Path

/**
 * What a g-code file was made from, written at its end: plymeld's
 * version, each model file's name, size, and SHA-256, and every config value in force, each with the file and line that set
 * it: what the print reads ([UsedEntries.inPrint]): of the hardware, its machine and that machine's tools, model, plate, and
 * filaments and what they take values from; no value plymeld does not use yet. With the same version, models and values plymeld writes the same g-code (byte-identical output is a project rule), so
 * a reader of a paper can reproduce a print from the file alone. The plan is written too, as `plymeld plan -o` writes it:
 * without a plan file the search runs within a time limit and may decide differently on a busier computer
 *, and slicing with the plan as a config file decides nothing again.
 *
 * The layout, line by line, is docs/gcode-format.md: a format line second ([FORMAT]), comment lines (`; #`) that readers
 * skip, then the parts. Both parts are TOML once their leading "; " is removed, each on its own (the config part as one
 * file, the plan part as another): every value quoted, as in a config file, a kept value with its "!". Left out of the config part: a
 * machine's `connection` group (addresses, SSH, where credentials are found: nothing about the print, and nothing to hand to
 * whoever reads the file), and every source's directory (a file's name and line only).
 */
internal object SourceBlock {
    const val START = "; PLYMELD_SOURCE_BLOCK_START"
    const val END = "; PLYMELD_SOURCE_BLOCK_END"

    /**
     * The block's format (docs/gcode-format.md), its second line. Raised only when a change would make a reader of the
     * older format misread a file; a reader refuses a number it does not know.
     */
    const val FORMAT = "1"
    const val FORMAT_LINE = "; format = \"$FORMAT\""

    /** A comment line: an explanation for people, skipped by readers. */
    const val COMMENT = "; #"

    const val PLAN = "; plan:"
    const val CONFIG = "; config:"

    /** The config part's four groups, in their order: what the print read and the rest, each yours then plymeld's (bundled). */
    enum class Group(val header: String, val used: Boolean, val bundled: Boolean) {
        USED_YOURS("; used, yours:", true, false), USED_BUNDLED("; used, plymeld's:", true, true),
        OTHER_YOURS("; other, yours:", false, false), OTHER_BUNDLED("; other, plymeld's:", false, true),
    }

    fun append(text: String, version: String, stack: ConfigStack, models: List<Path>, plan: String, machine: String?, used: Set<String>): String =
        (if (text.endsWith("\n")) text else text + "\n") + block(version, stack, models, plan, machine, used, sentCommands(text))

    /** The command words [gcode] sends (each line's first word, comments aside), upper case. */
    fun sentCommands(gcode: String): Set<String> = gcode.lineSequence()
        .map { it.substringBefore(';').trim() }.filter { it.isNotEmpty() }
        .mapTo(HashSet()) { it.substringBefore(' ').substringBefore('\t').uppercase() }

    /** The lines of a printer model that tell the writer and the predictor something about every print it makes. */
    private val MODEL_FACTS = setOf("name", "dialect", "motion", "evidence", "toolhead")

    /**
     * [plan]: the plan file (`Product.planFile`) the slice was made with; [machine]: the key of the machine that prints it
     * (null: every entry kept); [used]: the keys of the config lines the print read; [sent]: the command words the g-code
     * sends (a printer model's command counts as used when the g-code sends it).
     */
    fun block(version: String, stack: ConfigStack, models: List<Path>, plan: String, machine: String?, used: Set<String>, sent: Set<String> = emptySet()): String {
        val values = UsedEntries.inPrint(stack.effective.values, machine).sortedBy { it.key }
        fun modelUsed(e: plymeld.config.ConfigFiles.Entry): Boolean {
            if (e.path.size < 4 || e.path[0] != "hardware" || e.path[1] != "model" || plymeld.config.Schema.find(e.path)?.used == false) return false
            return if (e.path[3] == "command") e.path.size == 5 && e.path[4].uppercase() in sent else e.path[3] in MODEL_FACTS
        }
        fun groupOf(e: plymeld.config.ConfigFiles.Entry): Group {
            // a reference (the loadout, `like`, a tool's machine, the machine's model, job.machine) lays out the job itself
            val isUsed = e.key in used || modelUsed(e) ||
                plymeld.config.Schema.find(e.path)?.type is plymeld.config.Schema.Type.Ref
            val bundled = e.source.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)
            return Group.entries.first { it.used == isUsed && it.bundled == bundled }
        }
        val grouped = values.groupBy(::groupOf)
        val config = Group.entries.joinToString("") { g ->
            val es = grouped[g].orEmpty()
            "${g.header} ${es.size} value(s)\n" + es.joinToString("") { "; ${it.key} = ${toml(it.value, it.keep)}  # ${fileName(it.source)}\n" }
        }
        val b = StringBuilder()
        b.append("\n").append(START).append("\n")
        b.append(FORMAT_LINE).append("\n")
        b.append("$COMMENT what this file was made from: plymeld's version, each model file (size, SHA-256), every config value in force\n")
        b.append("$COMMENT (of the hardware, only the machine that printed it and what it uses) with the file and line that set it, in\n")
        b.append("$COMMENT four groups: the values the print read and the rest, each yours and plymeld's own; and the plan.\n")
        b.append("$COMMENT `plymeld slice --from THIS_FILE -m MODEL...` slices it again, deciding nothing again; add -cf / -cv to change it\n")
        b.append("; plymeld = ${toml(version, false)}\n")
        for (m in models) {
            val bytes = Files.readAllBytes(m)
            b.append("; model = ${toml(m.fileName.toString(), false)}  # ${bytes.size} bytes, sha256 ${sha256(bytes)}\n")
        }
        b.append("$CONFIG ${values.size} values (left out: a machine's connection, hardware this print does not use), sha256 ${sha256(config.toByteArray(Charsets.UTF_8))}\n")
        b.append(config)
        b.append("$PLAN the decisions this file was sliced with, as `plymeld plan -o` writes them\n")
        for (line in plan.trimEnd('\n').lines()) b.append(if (line.isEmpty()) ";\n" else "; $line\n")
        b.append(END).append("\n")
        return b.toString()
    }

    /** A model file as the block records it. */
    class RecordedModel(val name: String, val bytes: Long, val sha256: String)

    /**
     * What a g-code's block says it was made from: [entries], the config part's values (plymeld's with a bundled source, so they
     * give way to a filament's or machine's value as they did), then [plan], the plan part's. Each value's source is the one it
     * had, with the g-code's name.
     */
    class Read(val version: String?, val models: List<RecordedModel>, val entries: List<plymeld.config.ConfigFiles.Entry>, val plan: List<plymeld.config.ConfigFiles.Entry>)

    /** The block of g-code [text] (file name [name], for sources); an error when it has none. */
    fun read(text: String, name: String): Read {
        val lines = text.lines().dropWhile { it != START }.drop(1).takeWhile { it != END }
        require(lines.isNotEmpty()) { "$name has no plymeld source block ($START ... $END): only g-code `plymeld slice -o` wrote can be sliced again" }
        val format = lines.first().let { Regex("""^; format = "(.*)"$""").find(it)?.groupValues?.get(1) }
        require(format == FORMAT) {
            if (format == null) "$name: its source block has no format line ($FORMAT_LINE second): written by an older plymeld; slice it again from its models"
            else "$name: its source block is format $format, which this plymeld (format $FORMAT) does not know"
        }
        var version: String? = null
        val models = ArrayList<RecordedModel>()
        val entries = ArrayList<plymeld.config.ConfigFiles.Entry>()
        var group: Group? = null
        var inConfig = false
        val planLines = ArrayList<String>()
        var inPlan = false
        for (l in lines.drop(1)) {
            if (inPlan) { planLines += l.removePrefix(";").removePrefix(" "); continue }
            if (l.startsWith(COMMENT)) continue
            when {
                l.startsWith(PLAN) -> inPlan = true
                l.startsWith(CONFIG) -> inConfig = true
                l.startsWith("; plymeld = ") -> version = plymeld.config.ConfigFiles.readToml(l.removePrefix("; "), name).single().value as String
                l.startsWith("; model = ") -> {
                    val m = Regex("""^; model = "(.*)"  # (\d+) bytes, sha256 ([0-9a-f]+)$""").find(l) ?: error("$name: an unreadable model line in its source block: $l")
                    models += RecordedModel(m.groupValues[1], m.groupValues[2].toLong(), m.groupValues[3])
                }
                inConfig && Group.entries.any { l.startsWith(it.header) } -> group = Group.entries.first { l.startsWith(it.header) }
                inConfig && group != null && l.startsWith("; ") -> {
                    val body = l.removePrefix("; ")
                    val cut = body.lastIndexOf("  # ")
                    val source = if (cut < 0) "?" else body.substring(cut + 4)
                    val e = plymeld.config.ConfigFiles.readToml(if (cut < 0) body else body.substring(0, cut), name).single()
                    // plymeld's own keep their bundled source: precedence reads it (a stand-in gives way to a filament's value)
                    val from = if (group.bundled && !source.startsWith(plymeld.config.ConfigFiles.BUNDLED_SOURCE)) plymeld.config.ConfigFiles.BUNDLED_SOURCE + source else source
                    entries += plymeld.config.ConfigFiles.Entry(e.path, e.value, "$from (in $name)", e.keep)
                }
            }
        }
        val plan = plymeld.config.ConfigFiles.readToml(planLines.joinToString("\n"), "$name, its plan")
        return Read(version, models, entries, plan)
    }

    /** A value as a config file writes it: a quoted string (with "!" when kept) or an array of them. */
    private fun toml(v: Any, keep: Boolean): String = when (v) {
        is List<*> -> v.joinToString(", ", "[", "]") { quoted(it.toString()) }
        else -> quoted((if (keep) "!" else "") + v.toString())
    }

    /** A TOML basic string: backslash, quote, and control characters escaped, so a multi-line value (a start template) stays on one line. */
    private fun quoted(s: String): String {
        val b = StringBuilder("\"")
        for (c in s) when {
            c == '\\' -> b.append("\\\\")
            c == '"' -> b.append("\\\"")
            c == '\n' -> b.append("\\n")
            c == '\r' -> b.append("\\r")
            c == '\t' -> b.append("\\t")
            c < ' ' || c == '\u007f' -> b.append("\\u%04x".format(c.code))
            else -> b.append(c)
        }
        return b.append('"').toString()
    }

    /** A source ("/dir/file.toml:12", "bundled 00_defaults.toml:40") without its directory; a command-line value's as it is. */
    private fun fileName(source: String): String =
        if (source.startsWith("command line")) source.replace("\n", " ") else source.substringAfterLast('/').substringAfterLast('\\')

    private fun sha256(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
