package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.path

/**
 * `plymeld investigate predict FILE --machine MODEL` — INVESTIGATION ONLY: plymeld's own
 * time and filament prediction for any slicer's g-code, so two slicers' files are timed by one
 * model (a slicer's own estimate uses its own model). The machine's motion limits come from
 * plymeld's machine description; nothing is read from another slicer's settings.
 */
class InvestigatePredictCommand : CliktCommand(name = "predict") {
    private val file by argument(help = ".gcode or .gcode.3mf").path(mustExist = true, canBeDir = false)
    private val machine by option("--machine", help = "the machine description to time it with, by its model's key (e.g. qidi-q2, flashforge-c5)").required()
    private val toolChange by option("--tool-change-time", help = "seconds the machine spends on one filament change beyond the g-code's moves (default: not counted)").double()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "INVESTIGATION ONLY: plymeld's time and filament prediction for any g-code file, whatever wrote it, with a machine description's motion limits."

    override fun run() {
        val text = if (file.toString().endsWith(".3mf")) java.util.zip.ZipFile(file.toFile()).use { z ->
            val e = z.entries().asSequence().filter { Regex("Metadata/plate_\\d+\\.gcode").matches(it.name) }.minByOrNull { it.name }
                ?: throw com.github.ajalt.clikt.core.UsageError("$file has no plate g-code")
            z.getInputStream(e).readBytes().decodeToString()
        } else java.nio.file.Files.readString(file)
        echo("predicted: " + plymeld.core.Predict.gcode(text, plymeld.config.Models.bundled.forModel(machine), toolChange))
        val byFeature = plymeld.core.Predict.filamentByFeature(text)
        echo("filament by feature (mm): " + byFeature.entries.sortedByDescending { it.value }.joinToString { "%s %.0f".format(java.util.Locale.ROOT, it.key, it.value) })
    }
}
