package plymeld.ui

import java.nio.file.Files
import java.nio.file.Path

/**
 * What the window's executable does with its arguments, decided before anything of the window starts ([decide]):
 *
 * - Velopack's hooks ([Updates.isHook]) first, as the updater waits for them.
 * - A command of the command line (the first argument one of [Commands.tree]'s commands), `--version`: the command
 *   line's executable beside this one ([commandLine]) runs with the same arguments, standard input, output and error,
 *   and its exit status is this one's. So every install that ships the window alone as its entry point (the AppImage,
 *   the Windows launcher) runs every command, and a command that depends on the install it runs from (`update`) runs
 *   from the install's own command line. Where no command line is beside this executable (a development run), that is
 *   said in one line and the exit status is 1.
 * - `--help` or `-h` anywhere: the command line's help (through it, as above, where it is beside this one), then the
 *   window's own options ([usage]).
 * - The window's own options ([options]): the window (or its bench, its icons, its OpenGL report).
 * - Anything else, an option or a word the window does not know, or an option without its value: refused in one line,
 *   exit status 1, as the command line refuses a usage error.
 */
object Launch {
    /** One option of the window's own: its [name], the [value] it takes (its metavar; null for a flag), what it does. */
    class Option(val name: String, val value: String?, val help: String)

    private fun <E : Enum<E>> choices(entries: List<E>) = entries.joinToString("|") { it.name.lowercase() }

    /** The window's own options, every one [main] reads (WindowOptionsTest holds Main.kt to this list). */
    val options: List<Option> = listOf(
        Option("-cf", "FILE", "a config file of the job (repeatable), as `plymeld plan` takes it; with a model alone, the last job's config files"),
        Option("-m", "FILE", "a model of the job (repeatable)"),
        Option("--machine", "KEY", "the machine of the job, where the config has more than one"),
        Option("--slice", null, "slice the job and check it as the window opens (or before --screenshot)"),
        Option("--tab", choices(StageTab.entries), "the stage's tab shown first"),
        Option("--why", null, "the drawer of the plan's decisions open"),
        Option("--console", null, "the tray's console open"),
        Option("--tray", choices(TrayTab.entries), "the tray open at this pane"),
        Option("--theme", "dark|light", "the colors (otherwise the theme last chosen in the window)"),
        Option("--find", "WORDS", "the finder open with WORDS typed"),
        Option("--setting", "PATH", "the drawer open at this setting"),
        Option("--link", "WORDS", "a step's link followed once the job is in"),
        Option("--pick-machine", "KEY", "step 2's choice of machine picked once the job is in"),
        Option("--lock-height", "MM", "the first object's flat layers locked at this height once the job is in"),
        Option("--ask-printer", null, "step 2 asks the printer once the job is in"),
        Option("--screenshot", "PNG", "draw the window off-screen to this picture once the job is in, instead of opening it"),
        Option("--size", "WxH", "the picture's size (--screenshot, --bench; default 1600x1000)"),
        Option("--view", choices(Camera.View.entries), "the 3D view's direction"),
        Option("--ortho", null, "the 3D view orthographic"),
        Option("--single", null, "one layer at a time"),
        Option("--layers", "LO:HI", "the layers shown (1-based)"),
        Option("--color", "tool|" + choices(ColorBy.entries), "what the toolpaths' colors show"),
        Option("--travels", null, "travels shown"),
        Option("--moves", "N", "the moves of the top layer shown up to N"),
        Option("--gl-platform", "x11|wayland", "the OpenGL context's platform on Linux"),
        Option("--gl-info", null, "print the OpenGL context's description and exit"),
        Option("--icons", "DIR", "write the logo (SVG, PNGs, .ico) to DIR and exit"),
        Option("--bench", null, "time the 3D view on a g-code file's toolpaths (--gcode) or synthetic rings (--segments)"),
        Option("--gcode", "FILE", "--bench: the g-code file"),
        Option("--segments", "N", "--bench: the synthetic rings' line count (default 1000000)"),
        Option("--frames", "N", "--bench: frames drawn (default 120)"),
        Option("--png", "FILE", "--bench: the last frame written to FILE"),
        Option("--bench-edit", null, "time the edit cycle through the window's own path (open, slice, lock a layer height, slice again) on -cf and -m"),
        Option("--height", "MM", "--bench-edit: the layer height locked (default 0.16)"),
        Option("-h", null, "this help (and the command line's), then exit"),
        Option("--help", null, "this help (and the command line's), then exit"),
    )

    private val byName = options.associateBy { it.name }

    /** The declared option [name] (a name [main] reads that is not declared is a bug, refused here at once). */
    fun option(name: String): Option = byName[name] ?: error("the window reads an option it does not declare: $name")

    /** The arguments, other than a command's, that go to the command line. */
    val FORWARDED = setOf("--version")
    private val HELP = setOf("--help", "-h")

    sealed interface Action {
        /** Velopack's install, update, or uninstall hook. */
        data object Hook : Action
        /** The command line's executable, run with these arguments. */
        data class CommandLine(val args: List<String>) : Action
        /** The help: the command line's, then the window's own options. */
        data object Help : Action
        /** The window's own options, every one known. */
        data object Window : Action
        /** A usage error, said in one line. */
        data class Refused(val message: String) : Action
    }

    /** The command line's top commands, read off its command tree (never a list kept here). */
    fun commandNames(): Set<String> = plymeld.commands.Commands.tree().registeredSubcommands().map { it.commandName }.toSet()

    /** What [args] ask of the window's executable, the command line's commands being [commands] (asked only past a hook). */
    fun decide(args: List<String>, commands: () -> Set<String> = ::commandNames): Action {
        if (Updates.isHook(args.toTypedArray())) return Action.Hook
        val first = args.firstOrNull()
        if (first != null && (first in FORWARDED || first in commands())) return Action.CommandLine(args)
        if (args.any { it in HELP }) return Action.Help
        var i = 0
        while (i < args.size) {
            val a = args[i]
            val o = byName[a] ?: return Action.Refused("no such command or window option: $a (--help lists both)")
            if (o.value != null) {
                if (i + 1 >= args.size) return Action.Refused("the window's option $a needs a value: ${o.value}")
                i++
            }
            i++
        }
        return Action.Window
    }

    /**
     * The command line's executable that ships beside the window's [exe]: `plymeld` in the same folder (the Linux
     * package, the AppImage's `usr/bin`), `cli\plymeld.exe` below it on Windows (the installer and the portable zip). Null
     * when it is not there (a development run, where [exe] is the JVM's), or when it is [exe] itself.
     */
    fun commandLine(exe: Path?, windows: Boolean = System.getProperty("os.name").lowercase().contains("windows")): Path? {
        val self = exe?.toAbsolutePath() ?: return null
        val dir = self.parent ?: return null
        val cli = if (windows) dir.resolve("cli").resolve("plymeld.exe") else dir.resolve("plymeld")
        if (!Files.isRegularFile(cli) || !Files.isExecutable(cli)) return null
        if (Files.isSameFile(cli, self)) return null
        return cli
    }

    /** Runs [cli] with [args], with this process's standard input, output and error, and returns its exit status. */
    fun run(cli: Path, args: List<String>): Int = ProcessBuilder(listOf(cli.toString()) + args).inheritIO().start().waitFor()

    /** Said where no command line is beside the window's executable. */
    fun missing(exe: Path?): String =
        "plymeld: no command line beside ${exe ?: "this program"} (an install has it there; in a build from source, run the command line's own build)"

    /** The window's own part of the help: how it is run, and its options. */
    fun usage(): String = buildString {
        appendLine("The window's executable runs the window, and every command above: given a command (or --version) first, it runs the command line")
        appendLine("installed beside it with the same arguments. With no arguments the window opens empty, waiting for a model.")
        appendLine()
        appendLine("Window options:")
        for (o in options) {
            val term = o.name + (o.value?.let { " $it" } ?: "")
            appendLine("  ${term.padEnd(28)} ${o.help}")
        }
    }
}
