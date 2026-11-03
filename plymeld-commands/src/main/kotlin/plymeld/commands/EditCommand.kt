package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple as multipleOption
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.config.Schema

/**
 * `plymeld edit FILE PATH=VALUE... [--keep]`: one or more values written into a config file (a plan file or your
 * own), each on its own line, the rest of the file kept as it was ([TomlEdit]). The window's every edit is this
 * command (the journal is a list of these). A path the schema does not declare
 * is refused; a file that would not read back is refused and left untouched.
 */
class EditCommand : CliktCommand(name = "edit") {
    private val file by argument("FILE", help = "the config file to change (.toml; a plan file from `plymeld plan -o`, or your own)").path(mustExist = true, canBeDir = false)
    private val assignments by argument("PATH=VALUE", help = "a config path and the value to write there, e.g. plan.object.boat.technique.flat.layer.height=0.12mm; repeatable").multiple()
    private val keep by option("--keep", help = "write each value kept (\"!value\"): a plan file's line that survives `plymeld plan` re-planning the rest").flag()
    private val unset by option("--unset", metavar = "PATH", help = "remove the line holding PATH (a comment directly above it goes with it); repeatable. In a plan file: that decision is decided again").multipleOption()
    private val unsetUnder by option("--unset-under", metavar = "PATH", help = "remove every line whose key is under PATH (plan.object.boat.band takes every band line); repeatable").multipleOption()
    private val journal by option("--journal", metavar = "JOURNAL", help = "record the edit in this journal file (<plan>.journal.json: the file's text before and after, and this command line), for `plymeld journal --undo`").path(canBeDir = false)
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Write values into a config file, each on its own line, the rest of the file kept as it was: comments, order, the other values; --unset and --unset-under remove lines instead. " +
            "A path the schema does not declare is refused; a file that would not read back is refused and left untouched. Prints each line changed, with the value it held before."

    override fun run() {
        if (!file.fileName.toString().endsWith(".toml")) throw UsageError("$file: edit writes TOML files only (a .json config is read, not written)")
        if (assignments.isEmpty() && unset.isEmpty() && unsetUnder.isEmpty()) throw UsageError("nothing to do: give PATH=VALUE, --unset PATH, or --unset-under PATH")
        var text = java.nio.file.Files.readString(file)
        val original = text
        val changes = ArrayList<String>()
        for ((p, under) in unset.map { it to false } + unsetUnder.map { it to true }) {
            val (t, removed) = TomlEdit.unset(text, p.trim(), under)
            text = t
            if (removed.isEmpty()) changes += "$file: nothing at $p${if (under) " or under it" else ""}" else removed.forEach { changes += "$file: $it removed" }
        }
        for (a in assignments) {
            val eq = a.indexOf('=')
            if (eq <= 0) throw UsageError("'$a': a PATH=VALUE")
            val path = a.substring(0, eq).trim(); val raw = a.substring(eq + 1).trim()
            if (Schema.find(TomlEdit.segments(path)) == null) error("$path: not a path the schema declares")
            val value = if (keep && !raw.startsWith("!")) "!$raw" else raw
            val c = TomlEdit.set(text, path, value)
            text = c.text
            changes += "$file:${c.line}: $path = \"$value\"" + (c.before?.let { "  (was \"$it\")" } ?: "  (added)")
        }
        // the file must read back as a config file; otherwise nothing is written
        try { ConfigFiles.readToml(text, file.toString()) } catch (e: IllegalArgumentException) { error("the edit would leave $file unreadable (${e.message}); nothing written") }
        if (text != original) {
            java.nio.file.Files.writeString(file, text)
            journal?.let { jp ->
                val line = (listOf("plymeld edit", file.toString()) + (if (keep) listOf("--keep") else emptyList()) + unset.flatMap { listOf("--unset", it) } + unsetUnder.flatMap { listOf("--unset-under", it) } +
                    assignments.map { if (' ' in it) "'$it'" else it }).joinToString(" ")
                Journal.read(jp).append(Journal.Entry(Journal.now(), line, file.toAbsolutePath().toString(), original, text))
                changes += "recorded in $jp"
            }
        }
        changes.forEach { echo(it) }
    }
}
