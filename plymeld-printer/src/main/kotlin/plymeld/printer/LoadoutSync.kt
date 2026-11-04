package plymeld.printer

import plymeld.config.Config
import plymeld.config.ConfigSetup
import plymeld.config.Dimension
import plymeld.config.Site
import kotlin.math.sqrt

/**
 * A printer's reported loadout against a machine's loadout in the config: per tool (or
 * slot), what the printer has, what the config says, and which of the config's filaments matches what the printer has.
 * It never changes the config or the printer; [Report.overlay] is a small config file the user may pass with `-cf`.
 *
 * Matching (plymeld's choice, deliberately strict): a config filament MATCHES a reported slot when its `kind` equals
 * the printer's material name, ignoring case and surrounding spaces, AND its `color` equals the printer's color
 * exactly as #RRGGBB (case ignored). Nothing is fuzzy: "PLA+" is not "PLA", #FFFFFE is not #FFFFFF. A filament that sets
 * no kind or no color never matches (the bundled generic filaments set no color). When no filament matches, the
 * nearest one of the same kind by color is named for information (straight-line distance in 0-255 RGB, also plymeld's
 * choice), never used.
 */
object LoadoutSync {
    enum class Verdict(val text: String) {
        AGREES("agrees"),
        DIFFERS("differs"),
        NOT_IN_CONFIG("loaded on the printer, nothing in the config"),
        EMPTY_ON_PRINTER("the config loads a filament, the printer reports none"),
        BOTH_EMPTY("empty on both"),
        CANNOT_COMPARE("cannot compare"),
        NOT_ON_PRINTER("the config loads a filament where the printer reports no slot"),
        /** The printer reports no slots at all (a Q2 with no Box, a Flashforge printer with no material station): nothing to compare, the config's loadout stands. */
        NOT_REPORTED("the printer reports no slots; the config's loadout stands"),
    }

    /** A config filament as matching sees it: its key, `kind` and `color` (#RRGGBB upper case), either possibly unset. */
    data class Filament(val key: String, val kind: String?, val color: String?) {
        fun show() = "$key (${kind ?: "no kind"} ${color ?: "no color"})"
    }

    data class Row(
        val position: Int,
        val feeds: ReportedSlot.Feeds,
        /** The config's tool there (a tool changer: the tool whose index is [position]; a slot: the tool it feeds), if any. */
        val tool: String?,
        val config: Filament?,
        val reported: ReportedSlot?,
        val verdict: Verdict,
        /** The config filaments matching what the printer reports (empty when none, or nothing to match). */
        val matches: List<String>,
        /** Why, in words: what differs, what is missing. */
        val why: String,
    ) {
        /** The one filament the overlay names here: the config's own when it matches, else the only match; null = none. */
        val chosen: String? get() = when {
            reported?.loaded == false -> null
            config != null && config.key in matches -> config.key
            else -> matches.singleOrNull()
        }
    }

    class Report(val machine: String, val source: String, val rows: List<Row>, val notes: List<String>) {
        /** The report as printed by `plymeld printer loadout`. */
        fun text(): String = buildString {
            appendLine("printer: $source")
            appendLine("machine: $machine (hardware.machine.$machine.loadout)")
            appendLine("matching: a config filament matches when its kind is the printer's material name (case ignored) and its color is the printer's color exactly (#RRGGBB)")
            for (r in rows) appendLine(line(r))
            notes.forEach { appendLine("note: $it") }
            val counts = rows.groupingBy { it.verdict }.eachCount()
            append("summary: ").appendLine(Verdict.entries.filter { it in counts }.joinToString(", ") { "${counts.getValue(it)} ${it.text}" })
        }

        /** One row as the report prints it: the position, what the printer has, what the config loads, the verdict, and why. */
        fun line(r: Row): String {
            val where = if (r.feeds == ReportedSlot.Feeds.TOOL) "tool ${r.position}" else "slot ${r.position}"
            return "$where${r.tool?.let { " ($it)" } ?: ""}: printer ${reportedText(r.reported)}; config ${r.config?.show() ?: "nothing"} -> ${r.verdict.text}${if (r.why.isEmpty()) "" else ": ${r.why}"}"
        }

        /**
         * The rows that do not agree, as warning lines (a mismatch before a start warns, never
         * refuses): every verdict but "agrees" and "empty on both", one line each, the report's row text.
         */
        fun warnings(): List<String> = rows.filter { it.verdict != Verdict.AGREES && it.verdict != Verdict.BOTH_EMPTY && it.verdict != Verdict.NOT_REPORTED }.map { "warning: ${line(it)}" }

        /**
         * The matched loadout as a config file for `-cf` (after the user's own files, so it wins): one line per tool or slot
         * with exactly one filament to name, and an empty value ("") for one the printer reports empty, which unloads it over
         * the user's files; the rest as comments saying why there is no line.
         */
        fun overlay(): String = buildString {
            appendLine("# What the printer reports loaded, as config lines (plymeld printer loadout, read-only).")
            appendLine("# From: $source. Pass it with -cf after your own config files. Check it before printing with it.")
            appendLine("[hardware.machine.$machine.loadout]")
            for (r in rows) {
                val chosen = r.chosen
                val path = if (r.feeds == ReportedSlot.Feeds.TOOL) r.tool?.let { "tool.$it.filament" } else "slot.${r.position}.filament"
                val where = if (r.feeds == ReportedSlot.Feeds.TOOL) "tool ${r.position}" else "slot ${r.position}"
                when {
                    chosen != null && path != null -> appendLine("$path = \"$chosen\"")
                    chosen != null -> appendLine("# $where: the printer has ${reportedText(r.reported)} (matches $chosen), but machine '$machine' has no tool with index ${r.position}")
                    r.reported?.loaded == false && path != null -> appendLine("$path = \"\"  # $where: empty on the printer${r.config?.let { c -> " (the config loads ${c.key} there)" } ?: ""}")
                    r.reported?.loaded == false -> appendLine("# $where: empty on the printer, and machine '$machine' has no tool with index ${r.position}")
                    r.verdict == Verdict.NOT_REPORTED -> appendLine("# $where: the printer reports no slots; the config's loadout stands${r.config?.let { c -> " (${c.key})" } ?: ""}")
                    r.reported == null -> appendLine("# $where: the printer reports no such ${if (r.feeds == ReportedSlot.Feeds.TOOL) "tool" else "slot"}")
                    r.matches.size > 1 -> appendLine("# $where: the printer has ${reportedText(r.reported)}; several filaments match (${r.matches.joinToString()}): not chosen")
                    else -> appendLine("# $where: the printer has ${reportedText(r.reported)}; no filament in the config matches${if (r.reported.material != null && r.reported.color != null) " (define one: kind = \"${r.reported.material}\", color = \"${r.reported.color}\")" else ""}")
                }
            }
        }

        private fun reportedText(s: ReportedSlot?): String = when {
            s == null -> "no such position"
            s.loaded == false -> "empty"
            else -> listOf(s.material ?: "no material", s.color ?: "no color").joinToString(" ") +
                (if (s.loaded == null) " (loaded? not reported)" else "") + listOfNotNull(s.name, s.vendor).joinToString("") { ", $it" }
        }
    }

    /** Compare [reported] with the loadout of [c]'s machine (refused when the config names no machine, or its loadout is wrong). */
    fun compare(c: Config, reported: ReportedLoadout): Report {
        val machine = c.machine ?: error(c.machineProblem ?: "which machine?")
        val problems = ArrayList<String>()
        val slots = ConfigSetup.slots(c, machine, problems)
        if (problems.isNotEmpty()) error("the config's loadout of '$machine' has problems:\n" + problems.joinToString("\n") { "  $it" })
        val toolIndex = c.keysOf("tool").filter { (c.entry(listOf("hardware", "tool", it, "machine"))?.value as? String) == machine }
            .associateWith { tk -> c.entry(listOf("hardware", "tool", tk, "index"))?.let { c.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1 }
        val filaments = c.keysOf("filament").map { f ->
            fun text(rest: String) = (c.filamentEntry(f, listOf(rest), machine, null)?.value as? String)?.trim()?.takeIf { it.isNotEmpty() }
            Filament(f, text("kind"), text("color")?.let(::normalColor))
        }
        val byKey = filaments.associateBy { it.key }
        val notes = ArrayList(reported.notes)
        // a printer reporting nothing: the config's own positions, as tools on a machine with several, else as slots
        val feeds = reported.slots.map { it.feeds }.distinct().let { fs ->
            check(fs.size <= 1) { "the printer reports both tools and slots; plymeld reads one or the other" }
            fs.singleOrNull() ?: if (toolIndex.size > 1) ReportedSlot.Feeds.TOOL else ReportedSlot.Feeds.SLOT
        }
        val positions = (reported.slots.map { it.position } + slots.map { it.number }).distinct().sorted()
        val rows = positions.map { p ->
            val rep = reported.slots.firstOrNull { it.position == p }
            val cs = slots.firstOrNull { it.number == p }
            val tool = when (feeds) {
                ReportedSlot.Feeds.TOOL -> toolIndex.filterValues { it == p }.keys.let { ks ->
                    if (ks.size > 1) notes += "machine '$machine' has ${ks.size} tools with index $p (${ks.joinToString()}): none chosen"
                    ks.singleOrNull() }
                ReportedSlot.Feeds.SLOT -> cs?.tool ?: toolIndex.keys.singleOrNull()
            }
            row(p, feeds, tool, cs?.filament?.let { byKey[it] ?: Filament(it, null, null) }, rep, filaments, reported.slots.isEmpty())
        }
        return Report(machine, reported.source, rows, notes)
    }

    private fun row(p: Int, feeds: ReportedSlot.Feeds, tool: String?, cf: Filament?, rep: ReportedSlot?, all: List<Filament>, noSlots: Boolean): Row {
        fun r(v: Verdict, matches: List<String> = emptyList(), why: String = "") = Row(p, feeds, tool, cf, rep, v, matches, why)
        // a printer reporting no slots at all (no Box, no material station) is not a printer missing this one: nothing to compare
        if (rep == null) return r(if (noSlots) Verdict.NOT_REPORTED else Verdict.NOT_ON_PRINTER)
        if (rep.loaded == false) return r(if (cf == null) Verdict.BOTH_EMPTY else Verdict.EMPTY_ON_PRINTER)
        val material = rep.material; val color = rep.color
        if (material == null || color == null) {
            val missing = listOfNotNull(if (material == null) "material" else null, if (color == null) "color" else null).joinToString(" or ")
            return r(if (cf == null && rep.loaded == true) Verdict.NOT_IN_CONFIG else Verdict.CANNOT_COMPARE, why = "the printer gives no $missing")
        }
        val matches = all.filter { it.kind != null && it.color != null && it.kind.equals(material, ignoreCase = true) && it.color == color }.map { it.key }
        val found = when (matches.size) {
            0 -> "no filament in the config is $material $color" + (nearest(material, color, all)?.let { (f, d) -> " (nearest $material: ${f.key} ${f.color}, RGB distance ${"%.0f".format(java.util.Locale.ROOT, d)})" } ?: "")
            1 -> "matches ${matches.single()}"
            else -> "several filaments match: ${matches.joinToString()}"
        }
        if (cf == null) return r(Verdict.NOT_IN_CONFIG, matches, found)
        if (cf.key in matches) return r(Verdict.AGREES, matches, if (matches.size > 1) "also matching: ${(matches - cf.key).joinToString()}" else "")
        if (cf.kind == null || cf.color == null)
            return r(Verdict.CANNOT_COMPARE, matches, "'${cf.key}' sets no ${listOfNotNull(if (cf.kind == null) "kind" else null, if (cf.color == null) "color" else null).joinToString(" or ")}; $found")
        val differs = listOfNotNull(
            if (!cf.kind.equals(material, ignoreCase = true)) "material ${cf.kind} vs $material" else null,
            if (cf.color != color) "color ${cf.color} vs $color" else null).joinToString(", ")
        return r(Verdict.DIFFERS, matches, "$differs; $found")
    }

    /** The same-kind filament with a color nearest [color], and the distance; null when there is none. */
    private fun nearest(material: String, color: String, all: List<Filament>): Pair<Filament, Double>? =
        all.filter { it.kind != null && it.color != null && it.kind.equals(material, ignoreCase = true) }
            .map { it to distance(it.color!!, color) }.minByOrNull { it.second }

    private fun rgb(c: String) = intArrayOf(c.substring(1, 3).toInt(16), c.substring(3, 5).toInt(16), c.substring(5, 7).toInt(16))

    private fun distance(a: String, b: String): Double {
        val x = rgb(a); val y = rgb(b)
        return sqrt((0..2).sumOf { ((x[it] - y[it]) * (x[it] - y[it])).toDouble() })
    }

    /** A config color as #RRGGBB upper case; null when it is not one (it then never matches). */
    private fun normalColor(c: String): String? = Regex("^#?([0-9A-Fa-f]{6})$").matchEntire(c)?.let { "#" + it.groupValues[1].uppercase() }
}
