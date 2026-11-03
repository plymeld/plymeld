package plymeld.cli

import plymeld.commands.Commands

/**
 * The command line: the process's `main` over [Commands], where every command is declared (plymeld-commands). What
 * this does, the UI does too, from the same tree: there is nothing here a window cannot reach.
 */
fun main(args: Array<String>) {
    kotlin.system.exitProcess(Commands.run(args.toList()))
}
