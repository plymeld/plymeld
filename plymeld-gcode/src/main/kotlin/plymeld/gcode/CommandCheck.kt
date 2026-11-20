package plymeld.gcode

import plymeld.machine.MachineDescription
import java.io.Writer

/**
 * Passes g-code through line by line and refuses what a printer must not be sent. Every line is checked, plymeld's own
 * moves and markers and the rendered start, end, and other templates alike:
 *  - its numbers (BadNumbers): a line with a number a printer's firmware may reject or act wrongly on (NaN, Infinity, a
 *    feed rate of 0 or below, a temperature below 0 ...) fails the write at once, before the line is passed on, naming the
 *    output line, what is wrong, and the feature, object, and layer being written (read from the markers passed before it);
 *  - its command: one the machine description has no evidence of accepting is refused at [finish], naming every such
 *    command at once (each with the first line that sent it), so one run says everything to add.
 * Comments and blank lines pass (their numbers checked as plymeld's own: BadNumbers.inComment).
 */
class CommandCheck(private val target: Writer, private val machine: MachineDescription) : Writer() {
    private val line = StringBuilder()
    private var lineNo = 0
    /** Each command the machine has no evidence of accepting, with the first output line that sent it. */
    private val refused = LinkedHashMap<String, String>()
    /** What is being written, from the markers passed so far: the feature, the object, the layer's height. */
    private var feature: String? = null
    private var obj: String? = null
    private var layerZ: String? = null

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        for (i in off until off + len) {
            val c = cbuf[i]
            if (c == '\n') { line.append('\n'); val t = line.toString(); check(t.substring(0, t.length - 1)); target.write(t); line.setLength(0) } else line.append(c)
        }
    }

    private fun check(text: String) {
        lineNo++
        BadNumbers.inCommand(text)?.let { bad(text, it) }
        BadNumbers.inComment(text)?.let { bad(text, it) }
        track(text)
        val code = text.substring(0, text.indexOf(";").let { if (it < 0) text.length else it }).trim()
        if (code.isEmpty()) return
        val word = code.substringBefore(' ').substringBefore('\t')
        if (!machine.supports(word)) refused.putIfAbsent(word, "output line $lineNo: \"$code\"")
    }

    /** The feature, object, and layer markers, both dialects' (Dialect). */
    private fun track(t: String) {
        when {
            t.startsWith(";TYPE:") -> feature = t.removePrefix(";TYPE:").trim()
            t.startsWith("; FEATURE:") -> feature = t.removePrefix("; FEATURE:").trim()
            t.startsWith(";Z:") -> layerZ = t.removePrefix(";Z:").trim()
            t.startsWith("; Z_HEIGHT:") -> layerZ = t.removePrefix("; Z_HEIGHT:").trim()
            t.startsWith(KlipperWriter.OBJECT_START) -> obj = t.removePrefix(KlipperWriter.OBJECT_START)
            t.startsWith(KlipperWriter.OBJECT_END) -> obj = null
        }
    }

    private fun bad(text: String, why: String): Nothing {
        target.flush()
        val where = listOfNotNull(feature?.let { "the $it" }, obj?.let { "of object '$it'" }, layerZ?.let { "on the layer at Z$it" })
            .joinToString(" ").let { if (it.isEmpty()) "before the first layer (the start template or plymeld's own start)" else "for $it" }
        error("output line $lineNo, '${text.trim()}': $why. Written $where. This is plymeld's fault, or a value of the config's " +
            "(a setting or a template); the g-code stops before this line, unfinished: do not print it")
    }

    /** The end of the output: its last line checked, then every command the machine has no evidence of accepting refused. */
    fun finish() {
        if (line.isNotEmpty()) { val t = line.toString(); check(t); target.write(t); line.setLength(0) }
        target.flush()
        if (refused.isEmpty()) return
        val how = "\"<kind>: <detail>\" in a config of yours"
        if (refused.size == 1) {
            val (word, where) = refused.entries.single()
            error("machine '${machine.name}' has no evidence of accepting '$word' ($where). " +
                "If it does, list it in its model with the evidence: hardware.model.${machine.id}.command.$word = $how; else stop emitting it.")
        }
        error("machine '${machine.name}' has no evidence of accepting ${refused.size} commands:\n" +
            refused.entries.joinToString("\n") { (word, where) -> "  '$word' ($where): hardware.model.${machine.id}.command.$word" } +
            "\nFor each it does accept, list it in its model with the evidence: the key above = $how; else stop emitting it.")
    }

    override fun flush() = target.flush()
    override fun close() { finish(); target.close() }
}
