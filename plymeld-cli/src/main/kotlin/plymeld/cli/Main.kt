package plymeld.cli

import plymeld.commands.Commands
import plymeld.commands.UpdateFeed

/**
 * The command line: the process's `main` over [Commands], where every command is declared (plymeld-commands). What
 * this does, the UI does too, from the same tree: there is nothing here a window cannot reach. A Windows console is put
 * in UTF-8 for the run first ([WindowsConsole]), so plymeld's text shows as written. When the user has turned update
 * checks on (`plymeld updates --set notify`), every run asks the release feed in the background, at most once a day,
 * and a newer release is said on stderr after the command ([UpdateFeed.forRun]); `updates` itself says it its own way.
 * A native executable has no java.home, which the JDK's fonts read as they start (every picture with text in it), so it
 * is set first: a folder holding a lib/ folder, where the fonts look for a configuration (on Linux they ask the system's
 * fontconfig only when the folder is there); the executable's own, as the window does, and where its lib/ is missing and
 * cannot be made, one in plymeld's cache ([JavaHome]).
 */
fun main(args: Array<String>) {
    JavaHome.set()
    WindowsConsole.useUtf8()
    val updates = if (args.firstOrNull() == "updates") null else UpdateFeed.forRun()
    val status = Commands.run(args.toList())
    updates?.finish(System.err)
    kotlin.system.exitProcess(status)
}
