package plymeld.cli

import plymeld.commands.Commands

/**
 * The command line: the process's `main` over [Commands], where every command is declared (plymeld-commands). What
 * this does, the UI does too, from the same tree: there is nothing here a window cannot reach. A Windows console is put
 * in UTF-8 for the run first ([WindowsConsole]), so plymeld's text shows as written.
 */
fun main(args: Array<String>) {
    WindowsConsole.useUtf8()
    kotlin.system.exitProcess(Commands.run(args.toList()))
}
