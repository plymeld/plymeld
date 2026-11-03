package plymeld.printer

/**
 * What a printer says it has loaded, in plymeld's terms and nothing more. Every printer
 * family's reader produces one of these; [LoadoutSync] compares it with a machine's loadout in the config.
 *
 * Fields a printer does not report are null, never guessed: a printer that says nothing about color gives a null
 * [ReportedSlot.color], not a default.
 */
data class ReportedLoadout(
    /** Which printer and API this came from, for the report (model, firmware, API); never an address or a credential. */
    val source: String,
    val slots: List<ReportedSlot>,
    /** Things the reader noticed in the response that the report should say (an unreadable color, an odd field). */
    val notes: List<String> = emptyList(),
)

/**
 * One place filament is loaded on the printer, as the printer numbers it ([position], from 1).
 *
 * [feeds] says what the position is in plymeld's config: on a tool changer each position is a TOOL (config
 * `loadout.tool.<tool key>.filament`, the tool whose `index` is [position]); on one nozzle fed by several spools it is a
 * SLOT (config `loadout.slot.<n>.filament`).
 */
data class ReportedSlot(
    val position: Int,
    val feeds: Feeds,
    /** Whether the printer says filament is loaded there; null = it does not say. */
    val loaded: Boolean?,
    /** The material as the printer names it ("PLA"), or null when it gives none. */
    val material: String?,
    /** The color as #RRGGBB (upper case), or null when the printer gives none or one plymeld cannot read. */
    val color: String?,
    /** A filament or spool name, when the printer reports one (the Creator 5 does not). */
    val name: String? = null,
    /** The filament's maker, when the printer reports one (the Creator 5 does not). */
    val vendor: String? = null,
    /** The printer's own fields for this position, as reported, for the report. */
    val fields: Map<String, String> = emptyMap(),
) {
    enum class Feeds { TOOL, SLOT }
}

/**
 * Something that can say what a printer has loaded: the LOADOUT capability on its own (the first one built;
 * the others are [Printer]'s). Implementations only READ: none may change the printer.
 */
fun interface LoadoutReader {
    fun read(): ReportedLoadout
}
