package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.OrcaConfig
import plymeld.check.PrePrintCheck

/** `plymeld investigate check FILE.gcode --settings SRC` — pre-print safety check of any slicer's g-code, with the limits read from that slicer's settings. INVESTIGATION ONLY. */
class InvestigateCheckCommand : CliktCommand(name = "check") {
    private val file by argument(help = ".gcode or .gcode.3mf").path(mustExist = true, canBeDir = false)
    private val settings by option("--settings", help = InvestigateCommand.SETTINGS_HELP).required()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "INVESTIGATION ONLY (limits from the settings in a project or g-code file): check g-code against the machine's limits before printing: numbers the firmware may refuse or act wrongly on (not a finite number, a feed rate of 0 or below, a temperature, fan, or wait below 0, an acceleration of 0 or below), bed area, excluded area, Z, temperatures, extrusion before heating, feed rates, extrusion amounts, and whether the head would hit printed material."

    override fun run() {
        val findings = PrePrintCheck.check(file, loadSettings(settings))
        if (findings.isEmpty()) echo("${file.fileName}: PASS (all pre-print checks)")
        else { findings.forEach { echo(it.toString(), err = true) }; throw ProgramResult(1) }
    }
}

/** Settings from a project .3mf, a Flash Studio .gcode (its CONFIG_BLOCK), or layered preset JSONs. */
fun loadSettings(settings: String): OrcaConfig = when {
    settings.endsWith(".3mf") -> OrcaConfig.fromProject3mf(java.nio.file.Path.of(settings))
    settings.endsWith(".gcode") -> OrcaConfig.fromGcode(java.nio.file.Path.of(settings))
    else -> OrcaConfig.layered(*settings.split(',').map { java.nio.file.Path.of(it.trim()) }.toTypedArray())
}
