package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path

/**
 * `plymeld commands [--json] [-o FILE] [--markdown DIR]`: the command model ([CommandModel]): every command plymeld has
 * with its options and arguments as `--help` describes them, and every config path with its scopes. It is what the UI
 * builds from, so what it prints is what the UI must show. `--markdown` writes the same commands as the command
 * reference's pages ([CliReference]).
 */
class CommandsCommand : CliktCommand(name = "commands") {
    private val json by option("--json", help = "as JSON instead of text").flag()
    private val output by option("-o", "--output", metavar = "FILE", help = "file to write (default: print it)").path()
    private val markdown by option("--markdown", metavar = "DIR", help = "write the command reference instead: an index (${CliReference.INDEX}) and a Markdown page per command, " +
        "into this folder (a page an earlier --markdown wrote that is no longer a command is removed; no other file is touched)").path(canBeFile = false)
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List every command and option plymeld has, and every config path with the scopes it may be set for: the model the UI is built from, so the two cannot differ; or write the command reference (--markdown)."
    override fun run() {
        val m = CommandModel.read()
        markdown?.let { dir ->
            if (json || output != null) throw UsageError("--markdown writes the reference's pages into its folder: not with --json or -o")
            val written = CliReference.write(dir, CliReference.pages(m))
            echo("command reference: ${written.size} pages written to $dir")
            return
        }
        val text = if (json) m.json() else m.text()
        output?.let { out -> out.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }; java.nio.file.Files.writeString(out, text); echo("commands written to $out") }
            ?: echo(text, trailingNewline = false)
    }
}
