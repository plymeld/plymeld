package plymeld.commands

import java.nio.file.Files
import java.nio.file.Path

/**
 * The command reference as Markdown pages, written from the command model ([CommandModel], read off the same Clikt tree
 * the CLI parses), so the reference says what `--help` says: an index ([INDEX]) with the root's options and a line per
 * command, and a page per top-level command (`slice.md`, `calibrate.md`, ...) holding it and every subcommand under it,
 * each with its help, its usage, its arguments, and its options with their values, defaults, and choices.
 * `plymeld commands --markdown docs/cli/reference` writes them; CliReferenceTest fails when the committed pages differ.
 * Nothing in the pages depends on the machine or the version that wrote them.
 */
object CliReference {
    /** The index page's file name. */
    const val INDEX = "README.md"

    /** Where the repository keeps the reference, from its root. */
    const val DIR = "docs/cli/reference"

    private const val HEADER = "<!-- Written by `plymeld commands --markdown docs/cli/reference` from the commands themselves: do not edit; change a command's help and write it again. -->"

    /** The pages by file name: the index, then one per top-level command, in the tree's order. */
    fun pages(model: CommandModel = CommandModel.read()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        out[INDEX] = index(model.root)
        for (c in model.root.subcommands) out[file(c)] = page(c)
        return out
    }

    /** Whether [text] is a page this writes (it starts with the header line). */
    fun written(text: String): Boolean = text.startsWith(HEADER)

    /**
     * Writes [pages] into [dir], removing the pages an earlier write left there that are no longer pages (a command
     * removed); no other file is touched. The files written.
     */
    fun write(dir: Path, pages: Map<String, String> = pages()): List<Path> {
        Files.createDirectories(dir)
        Files.list(dir).use { s -> s.filter { it.fileName.toString().endsWith(".md") && it.fileName.toString() !in pages && written(Files.readString(it)) }.toList() }.forEach(Files::delete)
        return pages.map { (name, text) -> dir.resolve(name).also { Files.writeString(it, text) } }
    }

    /** The page a top-level command's reference is on. */
    fun file(top: CommandModel.Command): String = "${top.path.first()}.md"

    /** GitHub's anchor for a command's heading (`plymeld calibrate melt values` -> `plymeld-calibrate-melt-values`). */
    fun anchor(c: CommandModel.Command): String = c.line.lowercase().replace(Regex("[^a-z0-9 _-]"), "").replace(' ', '-')

    private fun index(root: CommandModel.Command): String = buildString {
        appendLine(HEADER)
        appendLine()
        appendLine("# Command reference")
        appendLine()
        appendLine("Every command plymeld has, with every option, as its `--help` gives it. The [guides](../README.md) say how the " +
            "commands fit together; `plymeld <command> --help` prints the same for one command.")
        appendLine()
        appendLine("## `plymeld`")
        appendLine()
        appendLine(md(root.help))
        appendLine()
        appendLine("Usage: `${usage(root)}`")
        options(root)
        appendLine()
        appendLine("## Commands")
        appendLine()
        for (c in root.subcommands) {
            appendLine("- [`${c.line}`](${file(c)}): ${md(firstSentence(c.help))}")
            for (s in c.subcommands) appendLine("  - [`${s.line}`](${file(c)}#${anchor(s)})" + s.subcommands.joinToString("") { ", [`${it.name}`](${file(c)}#${anchor(it)})" })
        }
    }

    private fun page(top: CommandModel.Command): String = buildString {
        appendLine(HEADER)
        appendLine()
        for (c in top.all()) {
            val depth = c.path.size
            appendLine("#".repeat(depth) + " `${c.line}`")
            appendLine()
            if (depth == 1) { appendLine("[Command reference](README.md)"); appendLine() }
            appendLine(md(c.help))
            appendLine()
            appendLine("Usage: `${usage(c)}`")
            if (c.subcommands.isNotEmpty()) {
                appendLine()
                appendLine("Subcommands:")
                appendLine()
                for (s in c.subcommands) appendLine("- [`${s.name}`](#${anchor(s)}): ${md(firstSentence(s.help))}")
            }
            if (c.arguments.isNotEmpty()) {
                appendLine()
                appendLine("Arguments:")
                appendLine()
                // as --help lists them: the argument's term (an optional one in brackets, as the usage line has it), its help
                for (a in c.arguments) appendLine("- `${a.term}`: ${md(a.help)}")
            }
            options(c)
            appendLine()
        }
    }.trimEnd() + "\n"

    private fun StringBuilder.options(c: CommandModel.Command) {
        appendLine()
        appendLine("Options:")
        appendLine()
        // as --help lists them: the option's term (its names and value), then its description (its help and tags: [required])
        for (o in c.options) appendLine("- `${o.term}`: ${md(o.description)}")
        appendLine("- `-h, --help`: Show this message and exit")
    }

    /** The command's usage line as --help writes it: its options, then its arguments, or a subcommand when it has some (optional when it also runs alone). */
    private fun usage(c: CommandModel.Command): String {
        val args = c.arguments.map { it.usage }
        val sub = if (c.subcommands.isEmpty()) emptyList() else listOf(if (c.runsAlone) "[<command>]" else "<command>", "[<args>]...")
        return (listOf(c.line, "[<options>]") + args + sub).joinToString(" ")
    }

    /** The first sentence of [help]: up to the first ". " outside parentheses (not an "e.g." or "i.e."), or all of it. */
    internal fun firstSentence(help: String): String {
        var depth = 0
        for ((i, ch) in help.withIndex()) {
            when (ch) {
                '(' -> depth++
                ')' -> depth = (depth - 1).coerceAtLeast(0)
                '.' -> if (depth == 0 && (i + 1 == help.length || help[i + 1] == ' ') && !help.substring(0, i + 1).endsWith("e.g.") && !help.substring(0, i + 1).endsWith("i.e."))
                    return help.substring(0, i + 1).trim()
            }
        }
        return help.trim()
    }

    /**
     * Help text as Markdown: what Markdown would read as markup escaped (so `<key>`, `*.toml`, and `scarf_share` print as
     * written), the help's own `code` spans kept as they are.
     */
    internal fun md(text: String): String {
        fun plain(s: String) = s.replace("\\", "\\\\").replace("<", "&lt;").replace(">", "&gt;").replace(Regex("([*_\\[\\]~#|])"), "\\\\$1")
        // an unpaired backtick opens no span: the text is all plain, the backticks escaped
        if (text.count { it == '`' } % 2 == 1) return plain(text).replace("`", "\\`")
        return text.split('`').mapIndexed { i, part -> if (i % 2 == 1) "`$part`" else plain(part) }.joinToString("")
    }
}
