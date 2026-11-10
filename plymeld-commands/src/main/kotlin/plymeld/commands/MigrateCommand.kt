package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.ConfigFiles
import plymeld.config.Migrations

/**
 * `plymeld migrate FILE...`: config and plan files rewritten to the settings' current names ([Migrations]): each renamed
 * setting's line rewritten in place under its new name and value ([TomlEdit.rename], its comments kept), each removed one
 * taken out; the rest of the file as it was. Every file is read back before it is written; each written file is
 * recorded in the config journal, for `plymeld journal --undo`. A file with nothing to change is left alone.
 */
class MigrateCommand : CliktCommand(name = "migrate") {
    private val files by argument("FILE", help = "a config or plan file (TOML) to rewrite").path(mustExist = true, canBeDir = false).multiple(required = true)
    private val dryRun by option("--dry-run", help = "show the changes; write nothing").flag()
    private val journalOpt by option("--journal", metavar = "JOURNAL", help = "record each change in this journal file; default: the config journal in plymeld's state folder (${FilamentValuesCommand.JOURNAL})").path(canBeDir = false)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Rewrite config and plan files to the settings' current names: a setting renamed in a release under its new name, its value converted " +
            "(plymeld reads the old name anyway, with a warning); a removed one taken out. Comments and the rest of each file stay as they were; " +
            "each file written is recorded for `plymeld journal --undo`."

    override fun run() {
        var changed = 0
        for (file in files) {
            if (!file.toString().lowercase().endsWith(".toml")) throw UsageError("$file: only TOML files are rewritten (a JSON config is read with the new names, but not rewritten)")
            val before = java.nio.file.Files.readString(file)
            var after = before
            val shown = ArrayList<String>()
            for (e in ConfigFiles.readToml(before, file.toString())) {
                val r = Migrations.migrate(e)
                if (r.note == null) continue
                val n = r.entry
                after = if (n == null) TomlEdit.unset(after, e.key).first
                    else TomlEdit.rename(after, e.key, n.key, (if (n.keep) "!" else "") + n.value.toString())
                shown += if (n == null) "  ${e.key}: removed" else "  ${e.key} -> ${n.key} = \"${n.value}\""
            }
            if (after == before) continue
            // the rewritten file reads back, and as the old one read with the changes applied
            val readBack = try { ConfigFiles.readToml(after, file.toString()) } catch (x: IllegalArgumentException) { error("the change would leave $file unreadable (${x.message}); nothing written") }
            val expected = ConfigFiles.readToml(before, file.toString()).mapNotNull { Migrations.migrate(it).entry }.associate { it.key to (it.value.toString() to it.keep) }
            check(readBack.associate { it.key to (it.value.toString() to it.keep) } == expected) { "$file: the rewritten file does not read as the old one with the changes: nothing written" }
            echo("$file:"); shown.forEach { echo(it) }
            changed++
            if (dryRun) continue
            java.nio.file.Files.writeString(file, after)
            val journal = journalOpt ?: plymeld.config.UserDirs.current().state.resolve(FilamentValuesCommand.JOURNAL)
            journal.parent?.let { java.nio.file.Files.createDirectories(it) }
            Journal.read(journal).append(Journal.Entry(Journal.now(), "plymeld migrate $file", file.toAbsolutePath().toString(), before, after))
            echo("written; recorded in $journal (undo: plymeld journal '$journal' --undo)")
        }
        when {
            changed == 0 -> echo("nothing to change: every setting in ${if (files.size == 1) "the file" else "these files"} has its current name")
            dryRun -> echo("--dry-run: nothing written")
        }
    }
}
