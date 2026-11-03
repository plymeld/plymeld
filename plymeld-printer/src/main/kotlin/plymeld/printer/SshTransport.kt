package plymeld.printer

/**
 * A shell on the printer: what an adapter needs from
 * SSH, and nothing more. [run] runs one command and gives its output; [read] gives a file's text. The real transport
 * is [MinaSsh] (Apache MINA SSHD); a test substitutes a fake. Nothing here decides what is
 * run: the adapter does, and says so in its documentation.
 */
interface SshTransport : AutoCloseable {
    /** Runs [command] on the printer and returns what it printed; a command that fails is a refusal naming it. */
    fun run(command: String): String
    /** The text of the file at [path] on the printer; a file that cannot be read is a refusal naming it. */
    fun read(path: String): String
    override fun close() {}
}

/**
 * How a printer's shell is reached, from `hardware.machine.<key>.connection.ssh.*` and the `ssh_password` /
 * `ssh_key_file` credentials: [host] defaults to the connection's host, [port] to 22, [variablesFile] to the
 * QIDI layout as observed on one printer (`/home/mks/printer_data/config/saved_variables.cfg`, the one
 * path seen); [acceptNew] trusts an unknown host key on first use (a risk: SECURITY.md), off unless set.
 */
class SshSettings(
    val host: String,
    val port: Int,
    val user: String,
    val password: String?,
    val keyFile: String?,
    val variablesFile: String,
    val acceptNew: Boolean,
) {
    companion object {
        const val DEFAULT_PORT = 22
        /** Klipper's `save_variables` file on a QIDI Q2, as observed (one printer; a setting overrides it). */
        const val QIDI_VARIABLES_FILE = "/home/mks/printer_data/config/saved_variables.cfg"
    }
}

/**
 * What the QIDI Boxes have in their slots, read from Klipper's `save_variables` file as observed on a Q2
 *. A Q2 runs with no Box (one spool fed straight into the tool) or with one to about four Boxes
 * (approximate): **the count is read, never assumed.**
 *
 * READ from that file and from QIDI's own `box<n>.cfg` templates, which describe
 * them: the `[Variables]` section holds `box_count`; per slot `i`, `slot<i>` (present), `vendor_slot<i>`,
 * `color_slot<i>`, `filament_slot<i>` (small integer codes into QIDI's own tables, not names or RGB), `runout_<i>`;
 * `last_load_slot` (`slot<i>`, or `slot-1` when nothing is loaded); `value_t<n>` (which slot each `T<n>` loads). The
 * slot index is ONE FLAT NUMBERING across the Boxes, with no per-Box prefix in the file: Box 1 = `slot0`..`slot3`,
 * Box 2 = `slot4`..`slot7`, Box 3 = `slot8`..`slot11`, Box 4 = `slot12`..`slot15` (the templates; the printer's screen
 * labels them Box 1 A-D, Box 2 A-D ...). Only ONE Box has ever been observed live; a second Box's entries are
 * inferred from those templates. OBSERVED (box-diagnosis): color code 1 displayed as white and 2 as
 * black; every other code's meaning is unknown. INFERRED here: `slot<i>` reads as loaded when it is `1` or `true`.
 *
 * plymeld's slot numbers (`hardware.machine.<key>.loadout.slot.<n>`) are the same flat numbering, 1-based: the
 * printer's `slot<i>` is plymeld's slot `i + 1`, whichever Box holds it. Why: it is the printer's own index, the one
 * its `T<n>` macros and `value_t<n>` variables use, so a config written for one Box keeps its numbers when a second
 * is added; the Box-and-letter form is only the screen's label and goes in each slot's name.
 *
 * A slot's material and color are NOT reported (codes are not names): the codes go into the slot's fields and a
 * note, and `printer loadout` can only say "cannot compare" (a table from codes to filaments is not built). With no
 * Box there are no slots and the comparison lets the config's loadout stand.
 */
object QidiBoxVariables {
    /** Slots per Box, from QIDI's templates (`slot0-3` per Box); only used to label a slot with its Box and letter. */
    private const val SLOTS_PER_BOX = 4
    private val SLOT = Regex("^slot(\\d+)$")

    /** The printer's screen label of flat slot [i]: "Box 1 slot A" (Box i / 4 + 1, letter i % 4). */
    fun label(i: Int): String = "Box ${i / SLOTS_PER_BOX + 1} slot ${'A' + i % SLOTS_PER_BOX}"

    fun parse(text: String, source: String): ReportedLoadout {
        val v = variables(text)
        val notes = ArrayList<String>()
        val slots = ArrayList<ReportedSlot>()
        val indices = v.keys.mapNotNull { k -> SLOT.matchEntire(k)?.groupValues?.get(1)?.toIntOrNull() }.sorted()
        for (i in indices) {
            val present = v.getValue("slot$i")
            val loaded = when (present.lowercase()) { "1", "true" -> true; "0", "false" -> false; else -> null }
            if (loaded == null) notes += "slot ${i + 1}: slot$i = $present is neither 0/1 nor true/false; whether filament is loaded is not read"
            val codes = linkedMapOf("vendor" to v["vendor_slot$i"], "color" to v["color_slot$i"], "filament" to v["filament_slot$i"], "runout" to v["runout_$i"])
            val colorWord = when (v["color_slot$i"]) { "1" -> "white"; "2" -> "black"; else -> null }
            val name = "${label(i)} (the printer's slot$i); codes: vendor ${codes["vendor"] ?: "-"}, color ${codes["color"] ?: "-"}${colorWord?.let { " ($it, as observed)" } ?: ""}, filament ${codes["filament"] ?: "-"}"
            slots += ReportedSlot(i + 1, ReportedSlot.Feeds.SLOT, loaded, material = null, color = null, name = name,
                fields = (mapOf("slot$i" to present) + codes.filterValues { it != null }.mapKeys { "${it.key}_slot$i" }.mapValues { it.value!! }))
        }
        val boxCount = v["box_count"]?.toIntOrNull()
        val boxesSeen = if (indices.isEmpty()) 0 else indices.max() / SLOTS_PER_BOX + 1
        when {
            indices.isEmpty() -> notes += "the printer reports no Box slots" + (if (boxCount != null) " (box_count = $boxCount)" else " (no slot<i> variables in the file)") +
                ": one spool fed straight into the tool; what is loaded is what the config's loadout says"
            boxCount == null -> notes += "$boxesSeen Box(es) read from the slot entries (slots ${indices.first() + 1} to ${indices.last() + 1}; the file has no box_count)"
            boxCount != boxesSeen -> notes += "box_count = $boxCount, but the slot entries span $boxesSeen Box(es) (slots ${indices.first() + 1} to ${indices.last() + 1}): the slots read are the ones listed"
            else -> notes += "$boxCount Box(es), ${indices.size} slots, numbered ${indices.first() + 1} to ${indices.last() + 1} across them (box_count = $boxCount)"
        }
        if (slots.isNotEmpty()) notes += "the Box records each slot's vendor, color, and filament as QIDI's own small integer codes (color 1 = white and 2 = black were observed; the rest are unknown): no material or color is read from them, so matching cannot apply"
        v["last_load_slot"]?.let { raw ->
            val n = raw.trim('\'', '"').removePrefix("slot").toIntOrNull()
            notes += if (n != null && n >= 0) "loaded into the nozzle now: slot ${n + 1}, ${label(n)} (last_load_slot = $raw)" else "nothing loaded into the nozzle now (last_load_slot = $raw)"
        }
        val mapping = v.keys.mapNotNull { k -> Regex("^value_t(\\d+)$").matchEntire(k)?.groupValues?.get(1)?.toIntOrNull() }.sorted()
            .map { n -> "T$n -> ${v.getValue("value_t$n").trim('\'', '"')}" }
        if (mapping.isNotEmpty()) notes += "which slot each T<n> loads (value_t<n>): ${mapping.joinToString(", ")}"
        return ReportedLoadout(source, slots, notes)
    }

    /** The `[Variables]` section's `key = value` pairs (Klipper's save_variables writes a config-file shape; values are kept as written, quotes and all). */
    fun variables(text: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        var inSection = false
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue
            if (line.startsWith("[")) { inSection = line.equals("[Variables]", ignoreCase = true); continue }
            if (!inSection) continue
            val eq = line.indexOfFirst { it == '=' || it == ':' }
            if (eq <= 0) continue
            out[line.substring(0, eq).trim()] = line.substring(eq + 1).trim()
        }
        return out
    }
}
