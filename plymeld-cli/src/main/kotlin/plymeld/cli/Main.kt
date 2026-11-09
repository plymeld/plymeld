package plymeld.cli

import plymeld.commands.Commands
import plymeld.commands.UpdateFeed

/**
 * The command line: the process's `main` over [Commands], where every command is declared (plymeld-commands). What
 * this does, the UI does too, from the same tree: there is nothing here a window cannot reach. A Windows console is put
 * in UTF-8 for the run first ([WindowsConsole]), so plymeld's text shows as written. When the user has turned update
 * checks on (`plymeld updates --set notify`), every run asks the release feed in the background, at most once a day,
 * and a newer release is said on stderr after the command ([UpdateFeed.forRun]); `updates` itself says it its own way.
 */
fun main(args: Array<String>) {
    WindowsConsole.useUtf8()
    val updates = if (args.firstOrNull() == "updates") null else UpdateFeed.forRun()
    val status = Commands.run(args.toList())
    updates?.finish(System.err)
    kotlin.system.exitProcess(status)
}
