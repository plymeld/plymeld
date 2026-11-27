package plymeld.commands

/**
 * A line a command prints, held to [WIDTH] characters: a path counts as its file name alone (its folder is the user's),
 * and a line that is nothing but a command to run, in backquotes, is left whole (it is copied, not read). A longer line
 * is broken where its facts part: between sentences, then at the semicolons between its clauses, then at the commas
 * between a clause's items, then between words; each break outside parentheses where it can be. Its pieces are packed
 * back together up to the width, so a line breaks only where it must, and the lines after its first are indented two
 * spaces past it.
 */
object PrintedLines {
    /** The widest a printed line runs: plymeld's choice, wide enough for a fact with its setting and its source. */
    const val WIDTH = 200

    /** [line]'s length as it is read: each path counted as its file name. */
    fun length(line: String) = line.replace(Regex("""/[^\s'`]*/"""), "").length

    /** [line] as the lines to print, each within [WIDTH]. */
    fun of(line: String): List<String> {
        if (line.lines().size > 1) return line.lines().flatMap { of(it) }
        if (length(line) <= WIDTH || Regex("""^\s*`plymeld [^`]*`$""").matches(line)) return listOf(line)
        val indent = line.takeWhile { it == ' ' }
        val more = "$indent  "
        val parts = pieces(line.substring(indent.length), 0, WIDTH - more.length)
        return listOf(indent + parts[0]) + parts.drop(1).map { more + it }
    }

    // the separators a line breaks at, the strongest first; each kept at the end of the piece before it
    private val BREAKS = listOf(Regex("""(?<=\.) (?=[A-Z])"""), Regex("""(?<=;) """), Regex("""(?<=,) """), Regex(""" """))

    private fun pieces(text: String, level: Int, width: Int): List<String> {
        if (length(text) <= width || level >= BREAKS.size) return listOf(text)
        val tokens = splitOutside(text, BREAKS[level], outsideOnly = level < BREAKS.size - 1)
        // a token too long on its own breaks at the next, weaker separator; then the pieces packed up to the width
        val small = tokens.flatMap { t -> if (length(t) > width) pieces(t, level + 1, width) else listOf(t) }
        val out = ArrayList<String>()
        for (t in small) {
            if (out.isNotEmpty() && length(out.last() + " " + t) <= width) out[out.size - 1] = out.last() + " " + t else out += t
        }
        return out
    }

    /** [text] split at [sep]'s matches, only those outside parentheses where [outsideOnly]; the pieces trimmed. */
    private fun splitOutside(text: String, sep: Regex, outsideOnly: Boolean): List<String> {
        val depth = IntArray(text.length + 1)
        var d = 0
        for ((i, c) in text.withIndex()) { depth[i] = d; if (c == '(') d++ else if (c == ')') d = maxOf(0, d - 1) }
        depth[text.length] = d
        val out = ArrayList<String>(); var from = 0
        for (m in sep.findAll(text)) {
            if (outsideOnly && depth[m.range.first] > 0) continue
            out += text.substring(from, m.range.first).trim(); from = m.range.last + 1
        }
        out += text.substring(from).trim()
        return out.filter { it.isNotEmpty() }.ifEmpty { listOf(text) }
    }
}
