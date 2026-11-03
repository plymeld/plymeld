package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import plymeld.check.plate.BaseLayers
import plymeld.check.plate.PlateIso
import plymeld.check.plate.PlateRender
import plymeld.check.plate.PlateSection
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render-plate PLATE.gcode [--iso] [--section BAND] [--layers | --base CELL...] [--layer-z Z] [-o DIR]`: pictures of
 * a calibration plate (`plymeld calibrate`) read back from its g-code, for reviewing it before it is printed. Each picture is
 * drawn from the g-code text alone (and, for --iso, the plate's instructions page), never from plymeld's own model of the
 * plate, so it shows what the printer will be told. Outputs: `<plate>.render.png` (always), `<plate>.iso.png` (--iso),
 * `<plate>.section<BAND>.png` (--section, the melt plate: one step in section), `<plate>.<base cell>.png`
 * per base (--layers / --base), in DIR (default: beside the g-code).
 *
 * A command of its own rather than `calibrate render`: `calibrate` is a group of plate commands, each with a required -o; a render under one plate
 * would not render the others'.
 */
class RenderPlateCommand : CliktCommand(name = "render-plate") {
    private val gcode by argument("PLATE.gcode", help = "a calibration plate's g-code (plymeld calibrate -o)").path(mustExist = true, canBeDir = false)
    private val outDir by option("-o", "--output", metavar = "DIR", help = "directory for the pictures (default: the g-code's)").path(canBeFile = false)
    private val iso by option("--iso", help = "also the isometric diagram, every region labeled from the plate's instructions page").flag()
    private val page by option("--page", metavar = "PAGE.md", help = "the instructions page for --iso (default: the g-code's name with .md)").path(mustExist = true, canBeDir = false)
    private val layers by option("--layers", help = "also each base's layers (every 'base ...' cell in the g-code), with a zoom round its notch").flag()
    private val bases by option("--base", metavar = "CELL", help = "only this base (e.g. 'base melt'); repeatable; implies --layers").multiple()
    private val layerZ by option("--layer-z", metavar = "MM", help = "the layer for the top view (panel A); default: the one nearest 10 mm").double()
    private val section by option("--section", metavar = "BAND", help = "melt plate: also a side section through this step (from 1, the front): the beads cut front to back through its FAST surface, and the head's travel from its FAST strips to its MID strips against what lies under it").int()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Draw a calibration plate (plymeld calibrate) from its g-code, to review before printing: a top view by flow, the front face " +
            "by temperature, the melt test's strips, and every temperature command (always); an isometric diagram labeled from the " +
            "plate's instructions page (--iso); a melt step in section, its strips on their floor and the head's travel over them (--section); each base's layers (--layers, --base)."

    override fun run() {
        val dir = outDir ?: gcode.toAbsolutePath().parent
        Files.createDirectories(dir)
        val stem = gcode.fileName.toString().removeSuffix(".gcode")
        fun timed(what: String, out: Path, draw: () -> Unit) {
            val t0 = System.nanoTime()
            draw()
            echo("-> $out ($what, ${(System.nanoTime() - t0) / 1_000_000} ms)")
        }
        timed("g-code render", dir.resolve("$stem.render.png")) { PlateRender.render(gcode, dir.resolve("$stem.render.png"), layerZ) { echo(it) } }
        if (iso) {
            val md = page ?: gcode.resolveSibling("$stem.md")
            require(Files.isRegularFile(md)) { "--iso needs the plate's instructions page: no $md (give it with --page)" }
            // a motion plate is drawn by its own page's picture (every face's number, direction, and speed, the bands with their
            // accelerations: MotionInstructions), not here (it crashed on the band ruler, a temperature plate's)
            if (Files.readAllLines(md).firstOrNull()?.startsWith("# Motion plate") == true)
                echo("--iso: no isometric diagram for a motion plate: its instructions page's own picture shows its faces and bands")
            else timed("isometric", dir.resolve("$stem.iso.png")) { PlateIso.render(gcode, md, dir.resolve("$stem.iso.png")) { echo(it) } }
        }
        section?.let { b ->
            val out = dir.resolve("$stem.section$b.png")
            timed("melt step $b in section", out) { PlateSection.render(gcode, out, b) { echo(it) } }
        }
        if (layers || bases.isNotEmpty()) {
            val cells = bases.ifEmpty { BaseLayers.baseCells(gcode) }
            if (cells.isEmpty()) echo("(no base cells, '; CELL: base ...', in ${gcode.fileName}: no layer pictures)")
            for (cell in cells) {
                val out = dir.resolve("$stem.${cell.replace(' ', '_')}.png")
                timed("$cell's layers", out) { BaseLayers.render(gcode, cell, out) { echo(it) } }
            }
        }
    }
}
