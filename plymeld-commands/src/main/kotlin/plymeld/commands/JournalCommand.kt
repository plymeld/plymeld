package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path

/**
 * `plymeld journal JOURNAL [--undo | --redo]`: the journal of edits ([Journal]) listed, or its last applied edit taken
 * back, or its last undone edit done again. The window's undo and redo are this command.
 */
class JournalCommand : CliktCommand(name = "journal") {
    private val journal by argument("JOURNAL", help = "the journal file (<plan>.journal.json, written by `plymeld edit --journal`)").path(canBeDir = false)
    private val undo by option("--undo", help = "take the last applied edit back: its file written as it was before (refused when the file is not as the edit left it)").flag()
    private val redo by option("--redo", help = "do the last undone edit again: its file written as the edit left it").flag()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List a journal of edits (each as the command that made it, applied or undone), or --undo the last applied one, or --redo the last undone one. " +
            "An undo or redo writes the whole file as the journal holds it, and is refused when the file changed since."

    override fun run() {
        if (undo && redo) throw UsageError("--undo or --redo, not both")
        val j = Journal.read(journal)
        when {
            undo -> { val e = j.undo(); echo("undone: ${e.command}"); echo("${e.file} written as it was before") }
            redo -> { val e = j.redo(); echo("redone: ${e.command}"); echo("${e.file} written as the edit left it") }
            else -> {
                if (j.entries.isEmpty()) { echo("$journal: no edits"); return }
                for ((i, e) in j.entries.withIndex()) echo("${i + 1}. ${if (i < j.done) "applied" else "undone "}  ${e.at}  ${e.command}")
                echo("${j.done} of ${j.entries.size} applied" + (if (j.canUndo) "; --undo takes back #${j.done}" else "") + (if (j.canRedo) "; --redo does #${j.done + 1} again" else ""))
            }
        }
    }
}
