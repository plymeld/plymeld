package plymeld.gcode

import plymeld.machine.MachineDescription
import java.io.Writer

/**
 * Passes g-code through unchanged and refuses any command the machine description has no
 * evidence of accepting. Every line is checked — plymeld's own moves and markers and the
 * rendered start/end templates alike — so a file plymeld writes contains only commands the machine
 * is known to take. Comments and blank lines pass. The refusal comes at [finish], naming every such
 * command at once (each with the first line that sent it), so one run says everything to add.
 */
class CommandCheck(private val target: Writer, private val machine: MachineDescription) : Writer() {
    private val line = StringBuilder()
    private var lineNo = 0
    /** Each command the machine has no evidence of accepting, with the first output line that sent it. */
    private val refused = LinkedHashMap<String, String>()

    override fun write(cbuf: CharArray, off: Int, len: Int) {
        target.write(cbuf, off, len)
        for (i in off until off + len) {
            val c = cbuf[i]
            if (c == '\n') { check(); line.setLength(0) } else line.append(c)
        }
    }

    private fun check() {
        lineNo++
        val code = line.substring(0, line.indexOf(";").let { if (it < 0) line.length else it }).trim()
        if (code.isEmpty()) return
        val word = code.substringBefore(' ').substringBefore('\t')
        if (!machine.supports(word)) refused.putIfAbsent(word, "output line $lineNo: \"$code\"")
    }

    /** The end of the output: its last line checked, then every command the machine has no evidence of accepting refused. */
    fun finish() {
        if (line.isNotEmpty()) { check(); line.setLength(0) }
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
