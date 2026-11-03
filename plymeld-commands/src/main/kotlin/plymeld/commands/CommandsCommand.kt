package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path

/**
 * `plymeld commands [--json] [-o FILE]`: the command model ([CommandModel]): every command plymeld has with its options
 * and arguments as `--help` describes them, and every config path with its scopes. It is what the UI builds from, so
 * what it prints is what the UI must show.
 */
class CommandsCommand : CliktCommand(name = "commands") {
    private val json by option("--json", help = "as JSON instead of text").flag()
    private val output by option("-o", "--output", help = "file to write (default: print it)").path()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List every command and option plymeld has, and every config path with the scopes it may be set for: the model the UI is built from, so the two cannot differ."
    override fun run() {
        val m = CommandModel.read()
        val text = if (json) m.json() else m.text()
        output?.let { out -> out.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }; java.nio.file.Files.writeString(out, text); echo("commands written to $out") }
            ?: echo(text, trailingNewline = false)
    }
}
