package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.multiple
import com.github.ajalt.clikt.parameters.types.int
import plymeld.model.withObjects
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.OrcaImport
import plymeld.core.decide.Plan

/** `plymeld investigate plan MODEL --settings SRC` — INVESTIGATION ONLY: the decisions with another slicer's settings. */
class InvestigatePlanCommand : CliktCommand(name = "plan") {
    private val models by argument(help = "STL or 3MF, one or more").path(mustExist = true, canBeDir = false).multiple(required = true)
    private val copies by option("--copies", help = "each model this many times").int().default(1)
    private val settings by option("--settings", help = InvestigateCommand.SETTINGS_HELP).required()
    private val pose by option("--pose", help = "derive (default) or as-given").default("derive")
    private val technique by option("--technique", help = "derive (default: chosen with the pose), one (flat layers of one height), or follow (flat layers following the surfaces)").default("derive")
    private val placement by option("--placement", help = "center or as-given (default: as-given with --pose as-given, else center)")
    private val plate by option("--plate", help = "which plate of a 3MF project (needed when several have objects)").int()
    private val sets by option("--set", help = "override an intent setting: id=value, repeatable (e.g. --set job.priorities=quality=10,speed=3)").multiple()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "INVESTIGATION ONLY (the settings in a project or g-code file): show each decision for printing the model: the value, the facts behind it, the alternatives, and any stand-in rules."

    override fun run() {
        val cfg = loadSettings(settings)
        val inputs = models.flatMap { m -> List(copies) { loadModel(m, plate) }.flatten() }
        inputs.mapNotNull { it.note }.distinct().forEach { echo(it) }
        val reported = HashSet<String>()
        val setup = withInputs(applySetOverrides(OrcaImport.setup(cfg, java.nio.file.Path.of(settings.split(',').last().trim()).fileName.toString()), sets), cfg, inputs) { if (reported.add(it)) echo(it) }
        setup.importReport?.notes?.forEach { echo("settings: $it") }
        echo(Plan.makeFor(inputs.map { it.objectName to it.parts }, setup, overrides(pose, placement, technique)).describe().trimEnd())
    }
}
