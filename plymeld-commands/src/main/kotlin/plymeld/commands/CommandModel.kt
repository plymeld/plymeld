package plymeld.commands

import com.github.ajalt.clikt.core.BaseCliktCommand
import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.output.HelpFormatter
import plymeld.config.Schema

/**
 * The command model: every command, its options and arguments, read off the Clikt tree ([Commands.tree]) with the same
 * calls Clikt's own help uses, so the model and `--help` cannot differ; and every config path from the schema with its
 * scopes. It is what the UI builds its controls from and what the parity test walks: a
 * command or option that is in the tree is in the model, and the test demands a place in the UI for it.
 */
class CommandModel(val root: Command, val paths: List<ConfigPath>) {
    /**
     * A command: its [path] from the root (`plymeld printer loadout` is `["printer", "loadout"]`), as `--help` describes it;
     * [runsAlone]: a command with subcommands that also runs without one (`plymeld calibrate melt` makes the plate,
     * `plymeld calibrate melt values` writes its readings back).
     */
    class Command(val path: List<String>, val help: String, val options: List<Option>, val arguments: List<Argument>, val subcommands: List<Command>,
                  val runsAlone: Boolean = false) {
        val name: String get() = path.lastOrNull() ?: "plymeld"
        val line: String get() = (listOf("plymeld") + path).joinToString(" ")
        /** This command and every command under it, parents first. */
        fun all(): List<Command> = listOf(this) + subcommands.flatMap { it.all() }
    }

    /**
     * An option as its help describes it: its [names] (`-cf`, `--config-file`), the [metavar] of its value (`FILE|GLOB`,
     * `PATH=VALUE`, `INT`, ...; null for a flag), its help, how many values it takes per use ([nvalues]: 0..0 a flag),
     * whether it may be [repeated], its [tags] (`required`, `default`, `env`), and its fixed [choices] when it has them.
     */
    class Option(val names: List<String>, val metavar: String?, val help: String, val nvalues: IntRange, val repeated: Boolean, val tags: Map<String, String>, val choices: List<String>) {
        val flag: Boolean get() = nvalues.last == 0
        val required: Boolean get() = "required" in tags
    }

    class Argument(val name: String, val help: String, val required: Boolean, val repeatable: Boolean)

    /** A config path from the schema: its declared form, meaning, type, and the scopes it may be set for. */
    class ConfigPath(val path: String, val meaning: String, val type: String, val scopes: List<String>)

    /** The model as text: each command with its options and arguments, then the config paths; what `plymeld commands` prints. */
    fun text(): String = buildString {
        for (c in root.all()) {
            appendLine(c.line)
            appendLine("  ${c.help}")
            for (a in c.arguments) appendLine("  <${a.name}>${if (a.repeatable) "..." else ""}${if (a.required) "" else " (optional)"}: ${a.help}")
            for (o in c.options) {
                val tags = o.tags.entries.filter { it.key != "required" }.joinToString("") { (k, v) -> " [$k${if (v.isEmpty()) "" else " $v"}]" }
                appendLine("  ${o.names.joinToString(", ")}${o.metavar?.let { "=$it" } ?: ""}${if (o.repeated) " (repeatable)" else ""}${if (o.required) " (required)" else ""}${if (o.choices.isEmpty()) "" else " {${o.choices.joinToString("|")}}"}$tags: ${o.help}")
            }
        }
        appendLine()
        appendLine("config paths (${paths.size}; scopes: where a value may be set)")
        for (p in paths) appendLine("  ${p.path}  ${p.type}  [${p.scopes.joinToString(", ")}]: ${p.meaning}")
    }

    /** The model as JSON (hand-written, like the schema's), for a program reading it. */
    fun json(): String {
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""
        fun list(xs: List<String>) = xs.joinToString(", ", "[", "]") { q(it) }
        fun cmd(c: Command, ind: String): String = buildString {
            append("{\n$ind  \"path\": ${list(c.path)},\n$ind  \"help\": ${q(c.help)},\n")
            append("$ind  \"arguments\": [${c.arguments.joinToString(", ") { "{\"name\": ${q(it.name)}, \"help\": ${q(it.help)}, \"required\": ${it.required}, \"repeatable\": ${it.repeatable}}" }}],\n")
            append("$ind  \"options\": [")
            append(c.options.joinToString(",") { o ->
                "\n$ind    {\"names\": ${list(o.names)}, \"metavar\": ${o.metavar?.let(::q) ?: "null"}, \"help\": ${q(o.help)}, \"flag\": ${o.flag}, \"repeated\": ${o.repeated}, \"required\": ${o.required}, " +
                    "\"tags\": {${o.tags.entries.joinToString(", ") { (k, v) -> "${q(k)}: ${q(v)}" }}}, \"choices\": ${list(o.choices)}}" })
            append(if (c.options.isEmpty()) "],\n" else "\n$ind  ],\n")
            append("$ind  \"subcommands\": [")
            append(c.subcommands.joinToString(",") { "\n$ind    " + cmd(it, "$ind    ") })
            append(if (c.subcommands.isEmpty()) "]\n$ind}" else "\n$ind  ]\n$ind}")
        }
        return buildString {
            append("{\n  \"about\": ${q("every command and option plymeld has, and every config path, as the UI reads them")},\n")
            append("  \"version\": ${q(PlymeldVersion.value)},\n  \"commands\": ${cmd(root, "  ")},\n")
            append("  \"config_paths\": [")
            append(paths.joinToString(",") { "\n    {\"path\": ${q(it.path)}, \"meaning\": ${q(it.meaning)}, \"type\": ${q(it.type)}, \"scopes\": ${list(it.scopes)}}" })
            append("\n  ]\n}\n")
        }
    }

    companion object {
        /** The model of [Commands.tree] and the schema. */
        fun read(): CommandModel = read(Commands.tree())

        /** The model of [root]'s tree (any Clikt tree: tests build small ones) and the schema. */
        fun read(root: BaseCliktCommand<*>): CommandModel = CommandModel(command(root, null, emptyList()),
            Schema.decls.map { ConfigPath(it.path, it.meaning, it.type.label, it.scopes.sorted().map { s -> s.name.lowercase() }) })

        private fun command(c: BaseCliktCommand<*>, parent: Context?, path: List<String>): Command {
            // the same context and parameter helps Clikt's help formatter reads, so a help text, metavar, or tag shown by
            // --help is the one here
            val ctx = c.resetContext(parent)
            val params = c.allHelpParams()
            val options = params.filterIsInstance<HelpFormatter.ParameterHelp.Option>().filter { ctx.helpOptionNames.none { h -> h in it.names } }.map { o ->
                val opt = c.registeredOptions().firstOrNull { it.names == o.names }
                val choices = (opt?.completionCandidates as? com.github.ajalt.clikt.completion.CompletionCandidates.Fixed)?.candidates?.toList() ?: emptyList()
                // Clikt's help does not say whether an option may be repeated (`multiple()`); plymeld's help texts do, with the
                // word "repeatable" (CommandModelTest holds every repeatable option to it)
                // a flag carries an empty "default" tag in Clikt's help parameters (its false), which says nothing: dropped
                Option(o.names.sortedBy { it.length }, o.metavar, o.help, o.nvalues, repeated = "repeatable" in o.help,
                    tags = o.tags.filterValues { it.isNotEmpty() } + (if ("required" in o.tags) mapOf("required" to "") else emptyMap()), choices = choices)
            }
            val arguments = params.filterIsInstance<HelpFormatter.ParameterHelp.Argument>().map { Argument(it.name, it.help, it.required, it.repeatable) }
            val subs = c.registeredSubcommands().map { command(it, ctx, path + it.commandName) }
            return Command(path, c.help(ctx).trim(), options, arguments, subs, runsAlone = subs.isNotEmpty() && c.invokeWithoutSubcommand)
        }
    }
}
