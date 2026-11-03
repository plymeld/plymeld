package plymeld.commands

/**
 * Line-preserving edits of a TOML config file: one value set at a dotted
 * path, the rest of the file byte for byte as it was: comments, blank lines, the order of tables and keys, the
 * quoting of other values. A value is always written in quotes (every value in quotes), a keep mark being
 * part of the value (`"!0.08 mm"`).
 *
 * How a path lands: a line already holding the key (under its table header, or as a dotted key) has its value
 * replaced and keeps a comment after it; otherwise the key is added at the end of the longest table whose name
 * prefixes the path (after its last key line, before the blank lines and comments that lead to the next table);
 * with no such table, a new table named by the path's parent is appended. Keys are matched as TOML reads them
 * (bare or quoted segments, `.` outside quotes); array tables (`[[...]]`) are not edited.
 */
object TomlEdit {
    /** What a [set] did: the new text, the 1-based line, and the value the line held before (null when added). */
    class Change(val text: String, val line: Int, val before: String?)

    fun set(text: String, path: String, value: String): Change {
        val target = segments(path)
        val lines = text.split("\n").toMutableList()   // a trailing "" when the text ends with a newline, kept as is
        var table: List<String> = emptyList()
        var tableHeaderAt = -1
        var bestTable: List<String>? = null; var bestHeaderAt = -1; var bestLastKeyAt = -1
        var lastKeyAt = -1
        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (t.startsWith("[")) {
                // the previous table closes: remember it when it prefixes the path better than any before
                if (tableHeaderAt >= 0 && prefixes(table, target) && (bestTable?.let { table.size > it.size } != false)) { bestTable = table; bestHeaderAt = tableHeaderAt; bestLastKeyAt = lastKeyAt }
                if (t.startsWith("[[")) { table = listOf("\u0000"); tableHeaderAt = i; lastKeyAt = i; continue }   // an array table: never matched
                table = segments(t.removePrefix("[").removeSuffix("]").trim()); tableHeaderAt = i; lastKeyAt = i
                continue
            }
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = keyEnd(raw)
            if (eq < 0) continue
            lastKeyAt = i
            val key = table + segments(raw.substring(0, eq).trim())
            if (key == target) {
                val (before, rest) = valueAndRest(raw.substring(eq + 1))
                lines[i] = raw.substring(0, eq + 1) + " " + quote(value) + rest
                return Change(lines.joinToString("\n"), i + 1, before)
            }
        }
        if (tableHeaderAt >= 0 && prefixes(table, target) && (bestTable?.let { table.size > it.size } != false)) { bestTable = table; bestHeaderAt = tableHeaderAt; bestLastKeyAt = lastKeyAt }
        val bt = bestTable
        if (bt != null && bestHeaderAt >= 0) {
            val keyText = target.drop(bt.size).joinToString(".") { bare(it) }
            val at = bestLastKeyAt + 1
            lines.add(at, "$keyText = ${quote(value)}")
            return Change(lines.joinToString("\n"), at + 1, null)
        }
        // no table holds the path: a new one named by its parent, appended
        val parent = target.dropLast(1); val leaf = target.last()
        val out = StringBuilder(text)
        if (out.isNotEmpty() && !out.endsWith("\n")) out.append("\n")
        if (out.isNotEmpty() && !out.endsWith("\n\n")) out.append("\n")
        val header = if (parent.isEmpty()) "" else "[${parent.joinToString(".") { bare(it) }}]\n"
        out.append(header).append("${bare(leaf)} = ${quote(value)}\n")
        // the new key is the last line; its number is the count of line ends
        return Change(out.toString(), out.count { it == '\n' }, null)
    }

    /**
     * The lines holding [path], or every key under it when [under] (`plan.object.boat.band` takes `band.1.technique`
     * and the rest), removed; a comment line directly above a removed line goes with it (it described that line). The
     * new text and the keys removed, in file order.
     */
    fun unset(text: String, path: String, under: Boolean = false): Pair<String, List<String>> {
        val target = segments(path)
        val lines = text.split("\n")
        val keep = BooleanArray(lines.size) { true }
        val removed = ArrayList<String>()
        var table: List<String> = emptyList()
        for ((i, raw) in lines.withIndex()) {
            val t = raw.trim()
            if (t.startsWith("[[")) { table = listOf("\u0000"); continue }
            if (t.startsWith("[")) { table = segments(t.removePrefix("[").removeSuffix("]").trim()); continue }
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = keyEnd(raw); if (eq < 0) continue
            val key = table + segments(raw.substring(0, eq).trim())
            val hit = if (under) key.size > target.size && key.subList(0, target.size) == target else key == target
            if (!hit) continue
            keep[i] = false; removed += key.joinToString(".")
            var k = i - 1
            while (k >= 0 && keep[k] && lines[k].trim().startsWith("#") && !lines[k].trim().startsWith("# Written")) { keep[k] = false; k-- }
        }
        return lines.filterIndexed { i, _ -> keep[i] }.joinToString("\n") to removed
    }

    /** The value a key line holds (unquoted), or null when the file has no such key. */
    fun get(text: String, path: String): String? {
        val target = segments(path)
        var table: List<String> = emptyList()
        for (raw in text.split("\n")) {
            val t = raw.trim()
            if (t.startsWith("[[")) { table = listOf("\u0000"); continue }
            if (t.startsWith("[")) { table = segments(t.removePrefix("[").removeSuffix("]").trim()); continue }
            if (t.isEmpty() || t.startsWith("#")) continue
            val eq = keyEnd(raw); if (eq < 0) continue
            if (table + segments(raw.substring(0, eq).trim()) == target) return valueAndRest(raw.substring(eq + 1)).first
        }
        return null
    }

    private fun prefixes(table: List<String>, target: List<String>) = table.isNotEmpty() && table.size < target.size && target.subList(0, table.size) == table

    /** The index of the `=` that ends a key on [line], or -1 (a `=` inside a quoted key segment does not count). */
    private fun keyEnd(line: String): Int {
        var q: Char? = null
        for ((i, c) in line.withIndex()) {
            when {
                q != null -> if (c == q) q = null
                c == '"' || c == '\'' -> q = c
                c == '=' -> return i
                c == '#' -> return -1
            }
        }
        return -1
    }

    /** A dotted key or table name as TOML segments: bare segments as they are, quoted ones without their quotes. */
    fun segments(key: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q: Char? = null
        for (c in key) {
            when {
                q != null -> if (c == q) q = null else cur.append(c)
                c == '"' || c == '\'' -> q = c
                c == '.' -> { out += cur.toString().trim(); cur.clear() }
                else -> cur.append(c)
            }
        }
        out += cur.toString().trim()
        return out
    }

    /** The value on a line after its `=`: the unquoted text and the rest of the line (a comment, kept). */
    private fun valueAndRest(after: String): Pair<String, String> {
        val s = after.trimStart()
        if (s.startsWith("\"")) {
            var i = 1; val v = StringBuilder()
            while (i < s.length && s[i] != '"') { if (s[i] == '\\' && i + 1 < s.length) { i++; v.append(when (s[i]) { 'n' -> '\n'; 't' -> '\t'; else -> s[i] }) } else v.append(s[i]); i++ }
            return v.toString() to s.substring(minOf(i + 1, s.length))
        }
        if (s.startsWith("'")) { val end = s.indexOf('\'', 1).let { if (it < 0) s.length else it }; return s.substring(1, end) to s.substring(minOf(end + 1, s.length)) }
        val hash = s.indexOf(" #").let { if (it < 0) s.length else it }
        return s.substring(0, hash).trim() to (if (hash < s.length) s.substring(hash) else "")
    }

    private fun quote(v: String) = "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    /** A key segment as TOML writes it: bare when it may be, quoted otherwise. */
    private fun bare(s: String) = if (s.matches(Regex("[A-Za-z0-9_-]+"))) s else "\"" + s.replace("\"", "\\\"") + "\""
}
