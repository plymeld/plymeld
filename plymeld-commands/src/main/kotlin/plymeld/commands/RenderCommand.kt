package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.convert
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import plymeld.check.render.ColorBy
import plymeld.check.render.FileFacts
import plymeld.check.render.Format
import plymeld.check.render.LineWidth
import plymeld.check.render.Others
import plymeld.check.render.Render
import plymeld.check.render.RenderRequest
import plymeld.check.render.Show
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render FILE.gcode [options]`: pictures of what any g-code file lays down, read from the file alone (no slicing,
 * no printer): its layers from above, framed, chosen, colored, and marked as the options say (plymeld.check.render.Render
 * draws them). Where the file is one plymeld wrote, its source block gives each filament's color and diameter where the
 * file's own lines do not.
 *
 * The options follow fixed rules: one option, one meaning; a layer number and a height never share an option; a view option
 * makes a picture (`--layer...`), an `--only-...` option chooses lines, everything else styles or frames every picture of
 * the run wherever it stands; nothing is silently ignored (an option that applies to no picture asked for, or two that
 * cannot both hold, is refused, naming them); lengths in mm, the first layer 1, tools by the file's own numbers.
 */
class RenderCommand : CliktCommand(name = "render") {
    private val file by argument("FILE.gcode", help = "the g-code file to draw (any slicer's; also a .gcode.3mf)").path(mustExist = true, canBeDir = false)

    // views
    private val layer by option("--layer", metavar = "N", help = "draw layer N from above; the first layer is 1; repeatable, or a list (5,10,20). With no view option at all: the middle layer")
        .convert { s -> s.split(',').map { it.trim().toIntOrNull() ?: fail("a layer number (1 is the first), not '$it'") } }.multiple()
    private val layerAtZ by option("--layer-at-z", metavar = "MM", help = "draw the first layer whose top is at or above this height (mm, a bare number or 5mm), as --pause-at-z picks it; repeatable, or a list")
        .convert { s -> s.split(',').map { length(it) ?: fail("a height in mm, not '$it'") } }.multiple()
    private val layerRange by option("--layer-range", metavar = "A..B", help = "draw layers A to B, one picture each (the first layer is 1); repeatable")
        .convert { s -> Regex("""^\s*(\d+)\s*\.\.\s*(\d+)\s*$""").find(s)?.let { it.groupValues[1].toInt()..it.groupValues[2].toInt() } ?: fail("a range of layers A..B (e.g. 10..20), not '$s'") }.multiple()
    private val layerStep by option("--layer-step", metavar = "K", help = "with --layer-range: every K-th layer of the range, from its first (default 1, every layer)").int()

    private val cutZ by option("--cut-z", metavar = "MM", help = "draw a level plane at this height (mm), seen from above: each bead it crosses as wide as the bead is there. Not a layer: where layers are curved or lines lowered, it meets several; repeatable, or a list")
        .convert { s -> s.split(',').map { length(it) ?: fail("a height in mm, not '$it'") } }.multiple()
    private val cutY by option("--cut-y", metavar = "MM", help = "draw the vertical plane at this Y (mm, bed coordinates), seen from the front: X across, height up; each line crossing it as its bead's cross-section; repeatable, or a list")
        .convert { s -> s.split(',').map { length(it) ?: fail("a Y in mm, not '$it'") } }.multiple()
    private val cutX by option("--cut-x", metavar = "MM", help = "draw the vertical plane at this X (mm, bed coordinates), seen from the right: the bed's front at the picture's left, height up; repeatable, or a list")
        .convert { s -> s.split(',').map { length(it) ?: fail("an X in mm, not '$it'") } }.multiple()
    private val cutLine by option("--cut-line", metavar = "X0,Y0:X1,Y1", help = "draw the vertical plane along this line on the bed (mm), the first point at the picture's left, height up; repeatable")
        .convert { s -> numbers(s, listOf(2, 2)) ?: fail("a line on the bed, X0,Y0:X1,Y1 in mm, not '$s'") }.multiple()
    private val cutPlane by option("--cut-plane", metavar = "PX,PY,PZ:NX,NY,NZ", help = "draw any plane, by a point on it (mm) and the direction it faces, seen from the side that direction points to; a level plane facing up is --cut-z; repeatable")
        .convert { s -> numbers(s, listOf(3, 3)) ?: fail("a point and a direction, PX,PY,PZ:NX,NY,NZ, not '$s'") }.multiple()
    private val cutDepth by option("--cut-depth", metavar = "MM", help = "with a cut: also the lines within this distance (mm) of its plane that do not cross it, drawn lighter (from the side in a vertical cut, so a wall the cut follows shows as a wall)")
        .convert { length(it) ?: fail("a length in mm, not '$it'") }
    private val zRange by option("--z-range", metavar = "Z0..Z1", help = "with a cut seen from the side: only these heights (mm). Not with layer pictures or --cut-z alone")
        .convert { s -> Regex("""^\s*([-0-9.]+)\s*(?:mm)?\s*\.\.\s*([-0-9.]+)\s*(?:mm)?\s*$""").find(s)?.let { (it.groupValues[1].toDoubleOrNull() ?: fail("a range of heights Z0..Z1 in mm, not '$s'"))..(it.groupValues[2].toDoubleOrNull() ?: fail("a range of heights Z0..Z1 in mm, not '$s'")) } ?: fail("a range of heights Z0..Z1 in mm (e.g. 2..4.5), not '$s'") }
    private val heightStretch by option("--height-stretch", metavar = "FACTOR", help = "with a cut seen from the side: how many times taller than true its heights are drawn (1: true proportions). Default: enough for a layer to be at least 3 px tall; the factor is in the caption")
        .convert { it.toDoubleOrNull() ?: fail("a number (1 is true proportions), not '$it'") }

    // choosing lines
    private val onlyObject by option("--only-object", metavar = "NAME", help = "only the lines of this object, named as the file names it (--list-objects); repeatable: any of them").multiple()
    private val onlyPart by option("--only-part", metavar = "NAME", help = "only the lines of this part, named as the file names it; repeatable: any of them. Refused for a file that names no parts").multiple()
    private val onlyTool by option("--only-tool", metavar = "N", help = "only the lines this tool lays, by the file's own tool numbers (T0 is 0); repeatable, or a list: any of them")
        .convert { s -> s.split(',').map { it.trim().removePrefix("T").removePrefix("t").toIntOrNull() ?: fail("a tool number (T0 is 0), not '$it'") } }.multiple()
    private val onlyFeature by option("--only-feature", metavar = "NAME", help = "only the lines of this feature, named as the file writes it (--list-features), e.g. 'Outer wall'; repeatable: any of them. " +
        "Different --only-... options together: a line must pass all of them").multiple()
    private val others by option("--others", metavar = "hide|faint", help = "what becomes of the lines the --only-... options leave out: hidden (default) or drawn in gray under the chosen ones")
        .choice(*Others.entries.map { it.word }.toTypedArray())

    // what is drawn
    private val colorBy by option("--color-by", metavar = "KIND", help = "what a line's color says: feature (default), tool (the filaments' own colors where the file states them), speed (mm/s), " +
        "flow (mm³/s), width (mm), height (layer thickness, mm), fan (part fan, %), temperature (nozzle, C), order (print order), or layer-time (the layer's time at its commanded speeds, s)")
        .choice(*ColorBy.entries.map { it.word }.toTypedArray())
    private val lineWidth by option("--line-width", metavar = "plastic|declared|center", help = "how wide each line is drawn: as wide as the plastic it lays, by the bead model (default; needs the filament's diameter, " +
        "from the file or its source block), as wide as the file declares (;WIDTH:), or as a thin center line")
        .choice(*LineWidth.entries.map { it.word }.toTypedArray())
    private val show by option("--show", metavar = "MARK", help = "with a layer picture: add marks: travels, seams, retractions, starts, or tool-changes; repeatable")
        .choice(*Show.entries.map { it.word }.toTypedArray()).multiple()
    private val layersBelow by option("--layers-below", metavar = "N", help = "with a layer picture: also the N layers under it, in light gray (default 0)").int()

    // framing and size
    private val bedWindow by option("--bed-window", metavar = "X0,Y0,X1,Y1", help = "draw only this rectangle of the bed (mm, in the g-code's coordinates); a cut seen from the side, only where its plane crosses the rectangle. Not with --around")
        .convert { s -> s.split(',').map { length(it) ?: fail("four bed coordinates in mm, X0,Y0,X1,Y1, not '$s'") }.also { if (it.size != 4) fail("four bed coordinates in mm, X0,Y0,X1,Y1, not '$s'") }.toDoubleArray() }
    private val around by option("--around", metavar = "X,Y", help = "draw only the square of side --span centered on this point of the bed (mm, in the g-code's coordinates, as plymeld's check messages give them); needs --span. Not with --bed-window")
        .convert { s -> s.split(',').map { length(it) ?: fail("a point X,Y in mm, not '$s'") }.also { if (it.size != 2) fail("a point X,Y in mm, not '$s'") }.let { it[0] to it[1] } }
    private val span by option("--span", metavar = "MM", help = "the side of the square --around draws (mm); needs --around").convert { length(it) ?: fail("a length in mm, not '$it'") }
    private val sameFrame by option("--same-frame", help = "every layer picture of the run in one frame and at one scale, for flipping through a range").flag()
    private val pxPerMm by option("--px-per-mm", metavar = "N", help = "the scale, pixels per mm. Default: the scale at which the closest lines in view lie at least 3 px apart, " +
        "the picture between 1000 and 4096 px on its longer side. Not with --picture-size").convert { it.toDoubleOrNull() ?: fail("a number of pixels per mm, not '$it'") }
    private val pictureSize by option("--picture-size", metavar = "WxH", help = "fit the drawing into W by H pixels, its proportions kept. Not with --px-per-mm")
        .convert { s -> Regex("""^(\d+)[xX](\d+)$""").find(s.trim())?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() } ?: fail("a size in pixels, WxH (e.g. 1600x1200), not '$s'") }
    private val allowUnresolved by option("--allow-unresolved-lines", help = "draw lines even where the scale cannot set them 3 px apart (by default such a picture shows each feature's area as a flat region with its outline, and the run names the --span that would resolve them)").flag()
    private val noAnnotations by option("--no-annotations", help = "the drawing alone: no caption, scale bar, or color key").flag()

    // outputs
    private val output by option("-o", "--output", metavar = "DIR", help = "the directory the pictures go in (default: beside the g-code, its name without .gcode and with .render: part.render for part.gcode); one file a picture, named from its view: layer-0060.png").path(canBeFile = false)
    private val format by option("--format", metavar = "png|svg", help = "the pictures' format (default png)").choice(*Format.entries.map { it.word }.toTypedArray())
    private val sheet by option("--sheet", metavar = "FILE", help = "also every picture of the run in one image, in a grid, each under its title, in the run's --format (a name without a directory goes in the output directory)").path()
    private val printFiles by option("--print-files", help = "print each written file's path, one a line, and nothing else (notes go to standard error)").flag()
    private val listFeatures by option("--list-features", help = "print the feature names the file writes, one a line, and draw nothing. Not with an option that draws or writes a picture").flag()
    private val listObjects by option("--list-objects", help = "print the object names the file writes, one a line, and draw nothing. Not with an option that draws or writes a picture").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Draw what any g-code file lays down, read from the file alone: its layers from above (--layer, --layer-at-z, --layer-range; the middle layer by default), " +
            "cuts through it (--cut-z from above; --cut-y, --cut-x, --cut-line, --cut-plane from the side, each line crossing the plane as its bead's cross-section), " +
            "the lines chosen by object, tool, or feature (--only-...), colored by feature, tool, speed, flow, width, height, fan, temperature, order, or layer time, " +
            "each line as wide as the plastic it lays, framed round a point (--around, --span) or a window of the bed, at a scale that keeps the closest lines 3 px apart. " +
            "Every picture's caption says what it shows. Lengths in mm, the first layer 1, tools by the file's own numbers."

    override fun run() {
        val req = RenderRequest(file,
            layers = layer.flatten(), layersAtZ = layerAtZ.flatten(), layerRanges = layerRange, layerStep = layerStep,
            cutZ = cutZ.flatten(), cutY = cutY.flatten(), cutX = cutX.flatten(), cutLine = cutLine,
            cutPlane = cutPlane.map { it.copyOfRange(0, 3) to it.copyOfRange(3, 6) }, cutDepth = cutDepth, zRange = zRange, heightStretch = heightStretch,
            onlyObject = onlyObject, onlyPart = onlyPart, onlyTool = onlyTool.flatten(), onlyFeature = onlyFeature,
            others = others?.let { o -> Others.entries.first { it.word == o } },
            colorBy = colorBy?.let { ColorBy.of(it) }, lineWidth = lineWidth?.let { w -> LineWidth.entries.first { it.word == w } },
            show = show.mapTo(LinkedHashSet()) { s -> Show.entries.first { it.word == s } }, layersBelow = layersBelow,
            bedWindow = bedWindow, around = around, span = span, sameFrame = sameFrame, pxPerMm = pxPerMm, pictureSize = pictureSize,
            allowUnresolved = allowUnresolved, noAnnotations = noAnnotations,
            outDir = output, format = format?.let { f -> Format.entries.first { it.word == f } } ?: Format.PNG, sheet = sheet, printFiles = printFiles,
            listFeatures = listFeatures, listObjects = listObjects, facts = facts(file))
        val r = Render.run(req) { echo(it) }
        for (n in r.notes) echo("note: $n", err = printFiles)
    }

    /** Groups of numbers split by ':' then ',' with the counts [sizes] (lengths may end in "mm"), flattened; null when they do not fit. */
    private fun numbers(s: String, sizes: List<Int>): DoubleArray? {
        val groups = s.split(':')
        if (groups.size != sizes.size) return null
        val out = ArrayList<Double>()
        for ((g, n) in groups.zip(sizes)) { val vs = g.split(',').map { length(it) ?: return null }; if (vs.size != n) return null; out += vs }
        return out.toDoubleArray()
    }

    /** A length in mm: a bare number or one ending in "mm". */
    private fun length(s: String): Double? = s.trim().removeSuffix("mm").trim().toDoubleOrNull()

    /** Each tool's filament diameter and color from a plymeld g-code's source block (its tail); none where it has none. */
    private fun facts(path: Path): FileFacts {
        if (path.toString().endsWith(".3mf")) return FileFacts()
        val tail = Files.newByteChannel(path).use { ch ->
            val n = minOf(ch.size(), TAIL_BYTES)
            ch.position(ch.size() - n)
            val buf = java.nio.ByteBuffer.allocate(n.toInt()); while (buf.hasRemaining()) { if (ch.read(buf) <= 0) break }
            String(buf.array(), 0, buf.position(), Charsets.UTF_8)
        }
        val at = tail.lastIndexOf(SourceBlock.START)
        if (at < 0) return FileFacts()
        return try {
            val src = SourceBlock.read(tail.substring(at), path.fileName.toString())
            val stack = plymeld.config.ConfigStack(plymeld.config.ConfigFiles.bundled() + src.entries + src.plan)
            val setup = plymeld.config.ConfigSetup.setup(plymeld.config.Config(stack, emptyList(), null)).setup
            val diameters = HashMap<Int, Double>(); val colors = HashMap<Int, String>()
            for (sp in setup.spools) {
                fun v(id: String) = sp.values[id]?.value ?: setup.materials[sp.material]?.get(id)?.value
                (v("hardware.filament.diameter") as? Number)?.toDouble()?.let { diameters.putIfAbsent(sp.tool, it) }
                (v("hardware.filament.color") as? String)?.let { colors.putIfAbsent(sp.tool, it) }
            }
            FileFacts(diameters, colors, "its source block")
        } catch (e: RuntimeException) {
            echo("note: ${path.fileName}'s source block could not be read (${Commands.said(e)}): its filaments' colors and diameters are taken from its own lines only", err = true)
            FileFacts()
        }
    }

    private companion object {
        /** How much of a file's end is read for its source block (plymeld's choice: a block with a long plan stays well under it). */
        const val TAIL_BYTES = 8L shl 20
    }
}
