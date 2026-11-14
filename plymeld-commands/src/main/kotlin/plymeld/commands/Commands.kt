package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.options.versionOption

/** Version baked in by Gradle at build time (see plymeld-commands/build.gradle.kts). */
object PlymeldVersion {
    val value: String by lazy {
        PlymeldVersion::class.java.getResourceAsStream("/plymeld-version.txt")
            ?.bufferedReader()?.readText()?.trim()
            ?: "unknown"
    }
}

class Plymeld : CliktCommand(name = "plymeld") {
    init {
        versionOption(PlymeldVersion.value, help = "show the version and exit")
        // --help tags a required option [required]; every command under the root inherits it. The command model renders each
        // option's term and description with the same settings (CommandModel.Terms), so the window and the reference show
        // what --help shows
        configureContext { helpFormatter = { HelpFormat(it) } }
    }

    /**
     * Clikt's help, with a required option tagged, and a subcommand in the usage line bracketed where the command also runs
     * without one (`plymeld calibrate melt [<options>] [<command>] [<args>]...`: the plate itself, or its `values`), as
     * the command model and the reference write it.
     */
    private class HelpFormat(ctx: com.github.ajalt.clikt.core.Context) : com.github.ajalt.clikt.output.MordantHelpFormatter(ctx, showRequiredTag = HELP_SHOWS_REQUIRED) {
        override fun renderUsageParametersString(parameters: List<com.github.ajalt.clikt.output.HelpFormatter.ParameterHelp>): String {
            val s = super.renderUsageParametersString(parameters)
            val c = context.command
            return if (c.invokeWithoutSubcommand && c.registeredSubcommands().isNotEmpty() && "[<command>]" !in s) s.replace("<command>", "[<command>]") else s
        }
    }

    companion object {
        /** Whether --help tags required options: the formatter's setting, shared with [CommandModel]'s rendering. */
        const val HELP_SHOWS_REQUIRED = true
    }

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "plymeld — a 3D print slicer. Commands: slice (models to g-code), plan (how plymeld would print the models, and why), and config (what the config adds up to), " +
            "each with config files (-cf), config values (-cv), and models (-m); schema (the config's JSON Schema, or the settings reference); calibrate (a calibration plate for one filament); render-plate (pictures of a plate from its g-code); layers (inspect a model's slices); " +
            "printer (talk to the machine's printer over its connection in the config: status, watch, camera, files, send, pause, resume, cancel, loadout, which compares what it has loaded with the config's, and credential, which sets one in its secrets file; each says what it sends); " +
            "investigate (slice, plan, check, config with the settings in a project or g-code file, to compare plymeld's result with that file — never how a print is set up — and predict, the time and filament of any g-code file); " +
            "commands (every command and option plymeld has, and every config path, as the UI reads them; or the command reference as Markdown pages); " +
            "edit (write values into a config file, the rest of it kept as it was); journal (the edits made with --journal: list, undo, redo); " +
            "init (set up your printers and filaments, asked in the terminal; it starts by itself when a command finds no config); " +
            "report (a problem report about what you are working on: a zip, and a GitHub issue filled in for you to submit); " +
            "updates (whether plymeld checks for a newer release as it runs: off until you turn it on); update (install the newest release over this one)."

    override fun run() = Unit
}

/**
 * Every command plymeld has, declared once: [tree] is the whole of it, and both faces read it: the CLI
 * parses a command line against it ([run]); the UI builds its controls from the [CommandModel] read off it and
 * runs the same commands. A command that is not in the tree does not exist.
 */
object Commands {
    /** The command tree, fresh (Clikt commands hold their parsed values, so each parse gets its own). */
    fun tree(): Plymeld = Plymeld().subcommands(SliceCommand(), PlanCommand(), ShowConfigCommand(), SchemaCommand(), LayersCommand(), CalibrateCommand(),
        RenderPlateCommand(), PrinterCommand(), InvestigateCommand(), CommandsCommand(), EditCommand(), JournalCommand(), InitCommand(), ReportCommand(), UpdatesCommand(), UpdateCommand(), MigrateCommand())

    /**
     * Runs a command line as the CLI does and returns the exit status. Refusals (plymeld's own `require` / `error`
     * checks, whose message names what is missing or not supported) print as one line and give 1. Any other exception
     * is a bug and keeps its stack trace; PLYMELD_TRACE=1 shows the trace for refusals too.
     */
    fun run(args: List<String>): Int {
        try {
            tree().main(args)
        } catch (e: RuntimeException) {
            if (!refusal(e)) throw e
            System.err.println("plymeld: ${said(e)}")
            return 1
        }
        return 0
    }

    /**
     * Runs a command line in-process with everything it prints handed to [out] (its error lines and usage errors to
     * [err]) and returns the exit status: the UI's way of running the same command the CLI would, its
     * console showing the lines. Refusals are reported as [run] does; a usage error prints as the CLI's would, and so do
     * `--help` and `--version` (to [out], as the CLI prints them to its standard output).
     */
    fun invoke(args: List<String>, out: (String) -> Unit, err: (String) -> Unit = out): Int {
        val t = tree()
        t.configureContext { echoMessage = { _, message, _, isErr -> (if (isErr) err else out)(message?.toString() ?: "") } }
        try {
            t.parse(args)
        } catch (e: CliktError) {
            t.getFormattedHelp(e)?.let(if (e.printError) err else out)
            return e.statusCode
        } catch (e: RuntimeException) {
            if (!refusal(e)) throw e
            err("plymeld: ${said(e)}")
            return 1
        }
        return 0
    }

    /** A refusal: plymeld's own `require` / `error` with a message (not a bug), unless PLYMELD_TRACE=1 asks for the trace anyway. */
    private fun refusal(e: RuntimeException) =
        (e is IllegalArgumentException || e is IllegalStateException) && e.message != null && System.getenv("PLYMELD_TRACE") != "1"

    /**
     * A refusal's own words. A refusal raised in parallel work reaches here as the fork-join pool's copy, whose message
     * is the original's `toString()` ("java.lang.IllegalStateException: ..."): the original's message is shown instead.
     */
    internal fun said(e: Throwable): String {
        var t = e
        while (t.cause != null && t.message == t.cause.toString()) t = t.cause!!
        return t.message ?: t.toString()
    }
}
