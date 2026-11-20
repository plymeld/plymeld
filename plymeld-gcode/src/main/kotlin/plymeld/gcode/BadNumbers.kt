package plymeld.gcode

/**
 * The numbers in a g-code line as a printer's firmware reads them: what in a command it may reject, or act wrongly on.
 * One rule, applied where plymeld writes a line (CommandCheck: the write fails at the line), where it checks a file before
 * printing (GcodeCheck), and where it sends one to a printer (`plymeld printer send`).
 *
 * In a command (the line before any `;`):
 *  - a parameter that is not a finite number where a number stands: NaN, Infinity, a number too large for a double (1e400),
 *    a malformed one (1.2.3, 1500f, --5), in the letter form (`F1500`: judged when its value starts as a number does) and the
 *    Klipper form (`ACCEL=5000`: judged when its value is made of a number's characters alone, so not `CENTER=105,105`,
 *    `NAME=part_1`, or a macro's text);
 *  - a parameter with no value where a value is needed: any on a move (G0-G3: `G1 X` stops Klipper), and F anywhere, and S
 *    on a temperature, fan, or acceleration command (`G28 X`, home X, is a command of its own and fine);
 *  - a move (G0-G3) at a feed rate of 0 or below: a printer stops the print at F0, a move with no speed;
 *  - a temperature below 0 (M104, M109, M140, M190, M141, M191: S, R), a fan below 0 (M106 S), a dwell below 0 (G4 P, S);
 *  - an acceleration of 0 or below (M204 S, P, T, R; SET_VELOCITY_LIMIT ACCEL, ACCEL_TO_DECEL, and its VELOCITY).
 * Messages (M117, M118) and file names (M23, M28, M30, M32, M928) are text and not judged.
 *
 * In a comment ([inComment]): NaN as a word, or Infinity as a word where a number stands (before a unit, a mark, or the
 * comment's end; not in a name, an object's or a spool's): plymeld writes numbers in its comments (a layer's height, a time
 * estimate), and one that is not a number is the mark of a broken estimate. A firmware ignores comments, so this is for
 * plymeld's own files ([inCommand] alone for a file of any origin).
 */
object BadNumbers {
    private val MOVES = setOf("G0", "G1", "G2", "G3")
    private val TEMPERATURES = setOf("M104", "M109", "M140", "M190", "M141", "M191")
    private val TEXT = setOf("M117", "M118", "M23", "M28", "M30", "M32", "M928")
    /** Klipper parameters that are an acceleration or a speed: 0 or below stops or stalls the motion. */
    private val VELOCITY_LIMITS = setOf("ACCEL", "ACCEL_TO_DECEL", "VELOCITY")
    /** How Kotlin (and Java) write a number that is not one. */
    private val NON_FINITE = setOf("NaN", "+NaN", "-NaN", "Infinity", "+Infinity", "-Infinity")

    /** What is wrong with the numbers of [line]'s command, in words a stranger reads; null when nothing is. */
    fun inCommand(line: String): String? {
        val semi = line.indexOf(';')
        val code = (if (semi < 0) line else line.substring(0, semi)).trim()
        if (code.isEmpty()) return null
        val words = code.split(' ', '\t').filter { it.isNotEmpty() }
        val cmd = words[0].uppercase()
        if (cmd in TEXT) return null
        val classic = isClassic(cmd)
        for (k in 1 until words.size) {
            val w = words[k]
            val eq = w.indexOf('=')
            val key: String; val value: String
            if (eq > 0) { key = w.substring(0, eq).uppercase(); value = w.substring(eq + 1) }
            else if (classic && w[0].isLetter()) { key = w.substring(0, 1).uppercase(); value = w.substring(1) }
            else continue
            // a value that must be a number: a move's (X Y Z E F I J), a feed's, a temperature's, a fan's, an acceleration's
            val needed = classic && eq < 0 && (cmd in MOVES || key == "F" || (key == "S" && (cmd in TEMPERATURES || cmd == "M106" || cmd == "M204")))
            if (value.isEmpty()) {
                if (needed) return "'$w' has no value: ${what(cmd, key)} needs a number, and a printer may refuse the command or stop the print"
                continue
            }
            // judged where a number stands: a letter's value starting as a number does (F1500, X-2), a Klipper value made of a
            // number's characters alone (ACCEL=5000, not CENTER=105,105 or NAME=part_1); NaN and Infinity always
            val numeric = needed || value in NON_FINITE || (if (classic && eq < 0) value[0].isDigit() || value[0] == '-' || value[0] == '+' || value[0] == '.'
                else value.any { it.isDigit() } && value.all { it.isDigit() || it == '-' || it == '+' || it == '.' || it == 'e' || it == 'E' })
            if (!numeric) continue
            val v = number(value) ?: return "'$w' is not a number: a printer cannot read it, and may stop the print or misread the command"
            if (!v.isFinite()) return "'$w' is not a finite number: a printer cannot read it, and may stop the print or misread the command"
            when {
                cmd in MOVES && key == "F" && v <= 0 -> return "a move at feed rate $w, no speed: a printer stops the print at a feed rate of 0 or below"
                cmd in TEMPERATURES && (key == "S" || key == "R") && v < 0 -> return "a temperature of $value °C, below 0: a printer refuses it or heats wrongly"
                cmd == "M106" && key == "S" && v < 0 -> return "a fan speed of $value, below 0: a printer refuses it or runs the fan wrongly"
                cmd == "G4" && (key == "P" || key == "S") && v < 0 -> return "a wait of $value, below 0: a printer refuses it"
                cmd == "M204" && key in setOf("S", "P", "T", "R") && v <= 0 -> return "an acceleration of $value, 0 or below: a printer refuses it, or moves that never reach speed stall the print"
                cmd == "SET_VELOCITY_LIMIT" && key in VELOCITY_LIMITS && v <= 0 -> return "$key=$value, 0 or below: a printer refuses it, or its moves stall the print"
            }
        }
        return null
    }

    /** What is wrong with a number in [line]'s comment (plymeld's own: a broken estimate); null when nothing is. */
    fun inComment(line: String): String? {
        val semi = line.indexOf(';')
        if (semi < 0) return null
        val c = line.substring(semi + 1)
        if (!c.contains("NaN") && !c.contains("Infinity")) return null
        return if (COMMENT.containsMatchIn(c)) "a number in plymeld's comment is not a number (NaN or Infinity): one of its estimates is broken, so the commands near it may be wrong too" else null
    }

    /** [inCommand] on every line of [lines], then (with [comments]) [inComment]: (1-based line, the line and what is wrong) for each. */
    fun scan(lines: Sequence<String>, comments: Boolean, limit: Int = Int.MAX_VALUE): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var inSource = false
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            // the source block at a plymeld file's end: the config it was made from, as text
            if (l == SOURCE_START) { inSource = true; continue }
            if (l == SOURCE_END) { inSource = false; continue }
            if (inSource) continue
            val why = inCommand(l) ?: if (comments) inComment(l) else null
            if (why != null) { out += (i + 1) to "'$l': $why"; if (out.size >= limit) break }
        }
        return out
    }

    /** The source block's first and last lines (SourceBlock in plymeld-commands). */
    const val SOURCE_START = "; PLYMELD_SOURCE_BLOCK_START"
    const val SOURCE_END = "; PLYMELD_SOURCE_BLOCK_END"

    private fun what(cmd: String, key: String) = when {
        key == "F" -> "a feed rate"
        cmd in MOVES -> "a move's $key"
        cmd in TEMPERATURES -> "a temperature"
        cmd == "M106" -> "a fan speed"
        else -> "an acceleration"
    }

    /** A G-, M-, or T-code (`G1`, `M104`, `T0`, `G29.1`): its parameters a letter and a value each. */
    private fun isClassic(cmd: String): Boolean {
        if (cmd.length < 2 || (cmd[0] != 'G' && cmd[0] != 'M' && cmd[0] != 'T')) return false
        var dot = false
        for (k in 1 until cmd.length) { val ch = cmd[k]; if (ch == '.' && !dot && k > 1) dot = true else if (!ch.isDigit()) return false }
        return true
    }

    /**
     * [s] as a number when it is written as one (an optional sign, digits with at most one point, an optional exponent),
     * which may be infinite (1e400); NaN or Infinity when it says so; null when it is not written as a number.
     */
    private fun number(s: String): Double? {
        var k = 0
        if (k < s.length && (s[k] == '-' || s[k] == '+')) k++
        val rest = s.substring(k)
        if (rest == "NaN") return Double.NaN
        if (rest == "Infinity") return Double.POSITIVE_INFINITY
        var digits = 0; var dot = false
        while (k < s.length && (s[k].isDigit() || (s[k] == '.' && !dot))) { if (s[k] == '.') dot = true else digits++; k++ }
        if (digits == 0) return null
        if (k < s.length && (s[k] == 'e' || s[k] == 'E')) {
            k++
            if (k < s.length && (s[k] == '-' || s[k] == '+')) k++
            var exp = 0
            while (k < s.length && s[k].isDigit()) { exp++; k++ }
            if (exp == 0) return null
        }
        return if (k == s.length) s.toDouble() else null
    }

    /** NaN as a word, or Infinity as a word where a number stands: before a unit, a mark, or the comment's end. */
    private val COMMENT = Regex("(?<![A-Za-z0-9_])NaN(?![A-Za-z0-9_])|(?<![A-Za-z0-9_])-?Infinity(?![A-Za-z0-9_])(?=\\s*(?:(?:s|mm|mm/s|%)(?![A-Za-z0-9_/])|[,;)]|$))")
}
