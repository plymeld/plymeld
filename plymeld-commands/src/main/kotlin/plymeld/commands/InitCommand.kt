package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import com.github.ajalt.mordant.input.interactiveSelectList
import com.github.ajalt.mordant.terminal.Terminal
import com.github.ajalt.mordant.terminal.YesNoPrompt
import com.github.ajalt.mordant.terminal.prompt
import java.nio.file.Files
import java.nio.file.Path

/** `plymeld init`: the first-use setup in the terminal ([InitFlow]); the window has its own face on [FirstUse]. */
class InitCommand : CliktCommand(name = "init") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Set up plymeld: your printers (each a model plymeld ships), their tools and build plates, your filaments, and what is loaded " +
            "where, written to plymeld's config folder; it ends by slicing a test cube with them. Arrow keys choose, Enter confirms, " +
            "Ctrl+C stops without writing anything. A command run in a terminal with no -cf and an empty config folder starts it too."

    private val folder by option("--folder", metavar = "DIR",
        help = "write the setup here instead of plymeld's config folder (a folder holding no .toml file)").path()

    override fun run() {
        InitFlow(terminal, ::echo).run(folder ?: plymeld.config.UserDirs.current().config)
    }
}

/**
 * The questions of [FirstUse], asked in a terminal: arrow-key lists for every choice, a line for every value, each with its
 * default and why. Ctrl+C at any question stops it and nothing is written (a list stops cleanly; a line prompt ends the
 * program, before anything was written). Esc does not: the terminal library's lists and prompts take no Esc. Ends with the
 * files written and a test cube sliced.
 */
class InitFlow(terminal: Terminal, private val say: (String) -> Unit) {
    private class Stopped : RuntimeException()

    /**
     * The terminal asked on: [terminal] itself, or, where it reports no width (a pseudo-terminal whose size was never set),
     * the same terminal at [FALLBACK_WIDTH] columns, as a list drawn at width 0 shows every entry blank.
     */
    private val t: Terminal = sized(terminal)

    companion object {
        /** 80 x 24: the classic terminal size (Claude's choice), for a terminal that reports none. */
        const val FALLBACK_WIDTH = 80
        const val FALLBACK_HEIGHT = 24

        /** [terminal], or the same terminal at [FALLBACK_WIDTH] columns where it reports no width. */
        internal fun sized(terminal: Terminal): Terminal = if (terminal.size.width > 0) terminal else Terminal(width = FALLBACK_WIDTH,
            height = terminal.size.height.takeIf { it > 0 } ?: FALLBACK_HEIGHT, terminalInterface = terminal.terminalInterface)

        /**
         * How the files in [dir] are read: by themselves when [dir] is plymeld's own config folder ([own]), else only when
         * named with -cf.
         */
        internal fun readAdvice(dir: Path, own: Path = plymeld.config.UserDirs.current().config): String =
            if (dir.toAbsolutePath().normalize() == own.toAbsolutePath().normalize()) "no -cf needed"
            else "passing the files with -cf '${dir.resolve("*.toml")}' (plymeld reads them by itself only from its own config folder)"
    }

    /** Whether a terminal can ask: input and output both interactive. */
    val canAsk: Boolean get() = t.terminalInfo.inputInteractive && t.terminalInfo.outputInteractive

    /** Sets up [dir]; true when written and checked, false when stopped (nothing written). */
    fun run(dir: Path): Boolean {
        if (!canAsk) throw UsageError("plymeld init asks its questions in a terminal: run it in one")
        FirstUse.folderProblem(dir)?.let { throw UsageError("$it, or give plymeld init another folder with --folder") }
        val c = FirstUse.choices()
        val taken = c.taken.toMutableSet()
        return try {
            say("plymeld init: your printers, then your filaments, then what is loaded where. Ctrl+C stops; nothing is written until the end.")
            val printers = ArrayList<FirstUse.Printer>()
            while (true) {
                val done = "done: no more printers"
                val other = "my printer is not listed"
                val pick = select(if (printers.isEmpty()) "Which printer do you have?" else "Another printer?",
                    c.models.map { "${it.name} (${it.id})" } + (if (printers.isEmpty()) listOf(other) else listOf(done, other)))
                if (pick == done) break
                if (pick == other) {
                    say("plymeld ships ${c.models.joinToString { it.name }}. Another printer is described in its own config first " +
                        ", then set up with plymeld edit.")
                    if (printers.isEmpty()) throw Stopped() else break
                }
                val m = c.models.first { "${it.name} (${it.id})" == pick }
                val key = key("A name for this printer", m.id.substringAfterLast('-'), taken)
                val values = LinkedHashMap<String, String>()
                for (a in FirstUse.printerAsks(m)) answer(a)?.let { values[a.path] = it }
                val secrets = LinkedHashMap<String, String>()
                val conn = FirstUse.connectionAsks(m)
                val host = answer(conn.first())
                if (host != null) {
                    values["connection.host"] = host
                    FirstUse.api(m)?.let { values["connection.api"] = it }
                    for (a in conn.drop(1)) answer(a)?.let { secrets[a.path] = it }
                }
                val ts = ArrayList<FirstUse.Tool>()
                for (i in 1..m.toolheads) {
                    val tk = key("A name for tool $i of $key", "$key-t$i", taken)
                    val same = i > 1 && yes("The same nozzle as tool 1?")
                    val tv = if (same) ts.first().values else LinkedHashMap<String, String>().also { v -> for (a in FirstUse.toolAsks(m)) answer(a)?.let { v[a.path] = it } }
                    ts += FirstUse.Tool(tk, i, tv, null)
                }
                val plate = select("Which build plate is on $key?", c.plates)
                printers += FirstUse.Printer(key, m.id, values, ts, plate, secrets)
            }
            val filaments = ArrayList<FirstUse.Filament>()
            while (true) {
                val done = "done: no more filaments"
                val pick = select(if (filaments.isEmpty()) "Which filament do you print with? (it starts from plymeld's own for its kind)" else "Another filament?",
                    c.filaments.map { "${it.kind} (${it.key})" } + (if (filaments.isEmpty()) emptyList() else listOf(done)))
                if (pick == done) break
                val base = c.filaments.first { "${it.kind} (${it.key})" == pick }
                val key = key("A name for this filament", base.kind.lowercase(), taken)
                val color = text("Its color (#RRGGBB, for the pictures; empty: none)", null, optional = true)
                filaments += FirstUse.Filament(key, base.key, color)
            }
            // what is loaded: the first tool of each printer gets a filament (a print needs one), the others may stay empty;
            // only filaments the printer's plate has a bed temperature for (another kind cannot print on it)
            val kindOf = filaments.associate { f -> f.key to c.filaments.first { it.key == f.like }.kind }
            val loaded = printers.map { p ->
                var plate = p.plate
                while (filaments.none { c.suits(plate, kindOf.getValue(it.key)) }) {
                    say("None of your filaments prints on $plate (it has no bed temperature for ${filaments.joinToString { kindOf.getValue(it.key) }}).")
                    plate = select("Which build plate is on ${p.key}?", c.plates.filter { pl -> filaments.any { c.suits(pl, kindOf.getValue(it.key)) } })
                }
                val fits = filaments.filter { c.suits(plate, kindOf.getValue(it.key)) }.map { it.key }
                val ts = p.tools.mapIndexed { i, tool ->
                    val none = "nothing loaded"
                    val pick = select("Which filament is in ${tool.key}? (those that print on $plate)", fits + (if (i == 0) emptyList() else listOf(none)))
                    FirstUse.Tool(tool.key, tool.index, tool.values, pick.takeUnless { it == none })
                }
                FirstUse.Printer(p.key, p.model, p.values, ts, plate, p.secrets)
            }
            val answers = FirstUse.Answers(loaded, filaments)
            val files = FirstUse.files(answers, dir)
            // checked before anything is written: a test cube sliced on each printer
            val ok = FirstUse.trial(files, loaded.map { it.key }, say)
            if (!ok && !yes("The test cube did not slice with these answers (above). Write the files anyway, to fix with plymeld edit?", default = false)) throw Stopped()
            say("These files go in $dir: ${files.keys.joinToString()}")
            if (!yes("Write them?")) throw Stopped()
            for (p in FirstUse.write(files, dir)) say("  wrote $p")
            // printing needs the printer reached: its address and credentials asked, now that they are written
            val unreached = ArrayList<String>()
            for (p in FirstUse.connected(answers)) {
                if (!yes("Ask $p for its state now (it must be switched on)?")) { unreached += p; continue }
                if (FirstUse.reach(dir, p, say)) say("$p answered: plymeld printer send can reach it")
                else {
                    unreached += p
                    say("$p did not answer (above): check it is on, and its address (${dir.resolve("10_printers.toml")})" +
                        (if (loaded.first { it.key == p }.secrets.isNotEmpty()) " and credentials (${dir.resolve("secrets/$p.toml")})" else "") +
                        "; plymeld printer status --machine $p asks again")
                }
            }
            // plymeld reads its own config folder by itself; files written anywhere else are read only when named
            val cf = readAdvice(dir)
            if (ok) say((if (unreached.isEmpty()) "Set up: plan, slice, and print with plymeld plan / slice / printer send, $cf."
                else "Set up: plan and slice with plymeld plan / slice, $cf; printing waits on reaching ${unreached.joinToString()}.") +
                (if (loaded.size > 1) " The first printer prints unless --machine names another (job.machine, 40_job.toml)." else ""))
            ok
        } catch (e: Stopped) {
            say("plymeld init stopped: nothing written.")
            false
        }
    }

    private fun select(title: String, entries: List<String>): String =
        t.interactiveSelectList(entries, title = title) ?: throw Stopped()

    private fun yes(question: String, default: Boolean = true): Boolean = YesNoPrompt(question, t, default = default).ask() ?: throw Stopped()

    /** A line of text; null = left empty where [optional]. The end of input stops. */
    private fun text(question: String, default: String?, optional: Boolean = false, hide: Boolean = false): String? {
        while (true) {
            val v = t.prompt(question, default = default, hideInput = hide) ?: throw Stopped()
            if (v.isNotBlank()) return v.trim()
            if (optional) return null
            say("  an answer is needed here")
        }
    }

    private fun answer(a: FirstUse.Ask): String? {
        say("  ${a.why}")
        return if (a.choices.isNotEmpty()) select(a.question.replaceFirstChar { it.uppercase() } + "?", a.choices.sortedByDescending { it == a.default })
        else text(a.question.replaceFirstChar { it.uppercase() }, a.default, a.optional, a.secret)
    }

    private fun key(question: String, default: String, taken: MutableSet<String>): String {
        var d = default
        var n = 2
        while (FirstUse.keyProblem(d, taken) != null) d = "$default-${n++}"
        while (true) {
            val k = text(question, d)!!
            val problem = FirstUse.keyProblem(k, taken)
            if (problem == null) { taken += k; return k }
            say("  $problem")
        }
    }
}
