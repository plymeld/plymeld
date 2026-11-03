package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.optional
import com.github.ajalt.clikt.parameters.options.option
import plymeld.config.OrcaImport
import plymeld.model.Catalog
import plymeld.model.Entity
import plymeld.model.Resolver

/**
 * `plymeld investigate config [ID] [--settings SRC]` — INVESTIGATION ONLY: another slicer's settings as plymeld reads them, grouped by entity.
 * With --settings: each value as resolved for the first object, and where it came from.
 * With an ID (or a prefix such as `hardware.filament.retract.`): only those settings. An id is the config key that sets it,
 * without the names you give; an id or prefix from before that is refused, naming the new ones.
 */
class InvestigateConfigCommand : CliktCommand(name = "config") {
    private val id by argument(help = "setting id or prefix (e.g. plan.layer.walls.count, hardware.filament.retract.)").optional()
    private val settings by option("--settings", help = InvestigateCommand.SETTINGS_HELP)

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "INVESTIGATION ONLY: list plymeld's settings by entity; with --settings, show each value and where it came from."

    override fun run() {
        val chosen = Catalog.settings.filter { id == null || it.id == id || it.id.startsWith(id!!) }
        if (chosen.isEmpty()) throw UsageError("no setting matches '$id'")
        val setup = settings?.let { OrcaImport.setup(loadSettings(it), java.nio.file.Path.of(it.split(',').last().trim()).fileName.toString()) }
        setup?.importReport?.notes?.forEach { echo("settings: $it") }
        val width = chosen.maxOf { it.id.length } + 2
        for (e in Entity.entries) {
            val group = chosen.filter { it.entity == e }
            if (group.isEmpty()) continue
            echo("[${e.label}]")
            for (s in group) {
                if (setup == null) echo("  ${s.id.padEnd(width)} ${s.kind.unit.padEnd(6)} ${s.meaning}")
                else echo("  " + runCatching { Resolver.resolve(setup, s.id).toString() }.getOrElse { "${s.id} = (no value: ${it.message})" })
            }
        }
        setup?.importReport?.let { echo("\n${it.mapped.size} keys of ${it.source} mapped; ${it.unmapped.size} not mapped onto any plymeld setting") }
    }
}
