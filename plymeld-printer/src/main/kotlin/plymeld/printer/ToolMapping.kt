package plymeld.printer

import plymeld.config.Config
import plymeld.config.ConfigSetup
import java.util.TreeSet

/**
 * Which slot of the printer prints each tool a g-code file uses, with what:
 * what a start request carries where the API takes one (the Creator 5's `materialMappings`;
 * Bambu's `ams_mapping` is the same idea). An adapter whose API has no such thing ignores it and says so in [Done.sent].
 *
 * [Entry.toolIndex] is the config's tool index (1 = `T0`); [Entry.slot] the printer's slot, 1-based; [Entry.material]
 * the filament's `kind` as the printer names materials; [Entry.toolColor] the filament's `color` in the config
 * (what the file was sliced with); [Entry.slotColor] the color the printer reports in that slot, or the config's
 * when the printer reports none (inferred: the API wants both, and the printer's is the better one where it exists).
 */
data class ToolMapping(val entries: List<Entry>) {
    data class Entry(val toolIndex: Int, val slot: Int, val material: String, val toolColor: String, val slotColor: String)

    fun text(): String = entries.joinToString("; ") { "T${it.toolIndex - 1} (tool ${it.toolIndex}) -> slot ${it.slot} ${it.material} ${it.slotColor}" }

    companion object {
        /**
         * The tools a g-code file uses: the `n` of every line whose command is `T<n>` (a line's text before any `;`,
         * trimmed, first word): the tool changes plymeld writes (`T<index - 1>`) and the ones other slicers write. A
         * `T` inside a comment or another command (`SET_PRINT_STATS_INFO`, `TOOL_CHANGE`) does not count. 0-based, as
         * the file writes them.
         */
        fun toolsUsed(gcode: String): Set<Int> {
            val out = TreeSet<Int>()
            for (raw in gcode.lineSequence()) {
                val line = raw.substringBefore(';').trim()
                if (line.length < 2 || line[0] != 'T' || !line[1].isDigit()) continue
                val word = line.substringBefore(' ')
                word.substring(1).toIntOrNull()?.let { out += it }
            }
            return out
        }

        /**
         * The mapping for the tools [used] (0-based, as [toolsUsed] gives them) from [machine]'s loadout in [c]: each
         * tool's filament gives the material (`kind`) and the tool color (`color`); the slot is the tool's index
         * (slot N feeds tool N: one Creator 5's reading, one printer; for a slotted loadout, the slot that
         * feeds the tool). Refused in one line, naming the printer by its key: a used tool with no filament loaded, a
         * filament without a kind or a color (the API wants both; nothing is guessed). [reported] fills each entry's
         * slot color with what the printer reports there; absent, the config's color stands in (inferred).
         */
        fun fromLoadout(c: Config, machine: String, used: Set<Int>, reported: ReportedLoadout?): ToolMapping {
            val problems = ArrayList<String>()
            val slots = ConfigSetup.slots(c, machine, problems)
            if (problems.isNotEmpty()) error("the config's loadout of '$machine' has problems:\n" + problems.joinToString("\n") { "  $it" })
            val entries = used.sorted().map { t ->
                val index = t + 1
                val loaded = slots.filter { it.toolIndex == index }
                val slot = loaded.singleOrNull() ?: if (loaded.isEmpty())
                    error("printer '$machine': the file uses T$t (tool $index), but machine '$machine' loads no filament in tool $index (hardware.machine.$machine.loadout.tool.<key>.filament): not starting it")
                    else error("printer '$machine': the file uses T$t (tool $index), and machine '$machine' loads ${loaded.size} filaments into it (slots ${loaded.joinToString { it.number.toString() }}): which one prints is not decided here")
                fun text(rest: String) = (c.filamentEntry(slot.filament, listOf(rest), machine, slot.tool)?.value as? String)?.trim()?.takeIf { it.isNotEmpty() }
                val kind = text("kind") ?: error("printer '$machine': tool $index prints '${slot.filament}', which sets no kind (hardware.filament.${slot.filament}.kind, the material name the printer wants): not starting it")
                val color = text("color")?.let { normal(it) } ?: error("printer '$machine': tool $index prints '${slot.filament}', which sets no color as #RRGGBB (hardware.filament.${slot.filament}.color): not starting it")
                val printerColor = reported?.slots?.firstOrNull { it.position == slot.number }?.color
                Entry(index, slot.number, kind, color, printerColor ?: color)
            }
            return ToolMapping(entries)
        }

        private fun normal(c: String): String? = Regex("^#?([0-9A-Fa-f]{6})$").matchEntire(c)?.let { "#" + it.groupValues[1].uppercase() }
    }
}
