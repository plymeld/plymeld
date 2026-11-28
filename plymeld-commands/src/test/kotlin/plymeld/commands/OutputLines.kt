package plymeld.commands

import java.nio.file.Files
import java.nio.file.Path

/**
 * The rules a command's printed lines and the comments it writes into the config are held to, for the tests that hold
 * every command to them; and the repo's sample config with keys long enough that a name's length cannot hide a line over
 * the limit.
 */
object OutputLines {
    /** The widest a printed line runs, a path counted as its file name alone (its folder is the user's). */
    const val WIDTH = 200

    /**
     * The lines of [lines] over [WIDTH] characters, a path counted as its file name alone, but a line that is nothing but a
     * command to run (in backquotes): it is copied whole rather than read.
     */
    fun overLong(lines: List<String>) = lines.filter { it.replace(Regex("""/[^\s'`]*/"""), "").length > WIDTH && !Regex("""^\s*`plymeld [^`]*`$""").matches(it) }

    /** The comment lines of [lines] over the config files' own width ([TomlEdit.COMMENT_WIDTH]) or not starting `# `. */
    fun badComments(lines: List<String>) = lines.filter { it.trimStart().startsWith("#") && !it.startsWith("#:schema") && (it.length > TomlEdit.COMMENT_WIDTH || !it.trimStart().startsWith("# ")) }

    /** A machine, a filament, and a tool key of 40 characters or more. */
    const val MACHINE = "a-workshop-corexy-printer-with-a-much-longer-name"
    const val FILAMENT = "a-matte-silk-pla-in-a-deep-ocean-blue-color"
    const val TOOL = "a-left-hand-nozzle-with-a-hardened-steel-tip"

    /** A copy of the repo's sample config with [MACHINE], [FILAMENT], and [TOOL] in place of its own keys. */
    fun longNamed(samples: Path): Path {
        val d = Files.createTempDirectory("plymeld-long-names")
        Files.list(samples).use { s -> s.forEach { Files.copy(it, d.resolve(it.fileName)) } }
        Files.list(d).use { fs -> fs.toList() }.forEach { p -> Files.writeString(p, Files.readString(p).replace("example-corexy", MACHINE).replace("pla-white", FILAMENT)
            .replace("tool.t1]", "tool.$TOOL]").replace("tool.t1.", "tool.$TOOL.")) }
        check(Files.readString(d.resolve("01_TOOLS.toml")).contains("[hardware.tool.$TOOL]")) { "the sample's tool t1 was not renamed" }
        return d
    }

    /** Runs the command line [args] through the command line's own parser: its status and what it printed, line by line. */
    fun run(args: List<String>): Pair<Int, List<String>> {
        val lines = ArrayList<String>()
        val status = Commands.invoke(args, { lines += it }, { lines += it })
        return status to lines.flatMap { it.lines() }
    }
}
