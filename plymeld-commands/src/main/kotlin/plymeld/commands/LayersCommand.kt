package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import plymeld.geom.Polygon
import plymeld.mesh.Layer
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.mesh.StlReader
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * `plymeld layers MODEL.stl` — slice into layers and show them: a per-layer summary
 * on stdout, optional SVG per layer, optional PNG contact sheet. The first thing
 * in plymeld you can look at.
 */
class LayersCommand : CliktCommand(name = "layers") {
    private val model by argument(help = "STL file").path(mustExist = true, canBeDir = false)
    private val layerHeight by option("--layer", metavar = "MM", help = "layer height, mm (default: 0.2)").double().default(0.2)
    private val firstLayer by option("--first", metavar = "MM", help = "first layer height, mm (default: same as --layer)").double()
    private val svgDir by option("--svg", metavar = "DIR", help = "write one SVG per layer into this directory").path()
    private val png by option("--png", metavar = "FILE", help = "write a contact-sheet PNG of up to --sheet layers").path()
    private val sheet by option("--sheet", metavar = "N", help = "layers on the contact sheet (default: 16)").int().default(16)
    private val quiet by option("--quiet", help = "no per-layer summary").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Slice a mesh into layers and report/draw the closed loops of each."

    override fun run() {
        val t0 = System.nanoTime()
        val soup = StlReader.read(model)
        val b = soup.bounds()
        val plan = LayerPlan.fixed(b[2], b[5], firstLayer ?: layerHeight, layerHeight)
        val layers = Slicer.slice(soup, plan.slices)
        val ms = (System.nanoTime() - t0) / 1_000_000
        val open = layers.sumOf { it.openChains.size }
        echo("${model.fileName}: ${soup.count} triangles, z ${fmt(b[2])}..${fmt(b[5])} mm, ${layers.size} layers @ ${layerHeight} mm, $ms ms" +
            (if (open > 0) ", $open OPEN CHAINS" else ""))
        if (!quiet) {
            echo("layer   z(mm)  loops  net area(mm²)  perimeter(mm)")
            for ((i, l) in layers.withIndex()) {
                echo("%5d %7.3f %6d %14.3f %14.2f%s".format(i, l.z, l.loops.size, l.netAreaMm2(), l.perimeterMm(),
                    if (l.openChains.isNotEmpty()) "   open=${l.openChains.size}" else ""))
            }
        }
        svgDir?.let { dir ->
            Files.createDirectories(dir)
            for ((i, l) in layers.withIndex()) Files.writeString(dir.resolve("layer_%04d.svg".format(i)), svg(l, b))
            echo("wrote ${layers.size} SVGs to $dir")
        }
        png?.let { out ->
            out.parent?.let { Files.createDirectories(it) }
            ImageIO.write(contactSheet(layers, b, sheet), "png", out.toFile())
            echo("wrote $out")
        }
    }

    private fun fmt(v: Double) = "%.3f".format(v)
}

private fun svg(l: Layer, b: DoubleArray): String = buildString {
    val w = b[3] - b[0]; val h = b[4] - b[1]
    append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="${b[0]} ${-b[4]} $w $h" width="${w * 4}" height="${h * 4}">""")
    append("\n<!-- z=${l.z} loops=${l.loops.size} open=${l.openChains.size} -->\n")
    append("<g fill-rule=\"evenodd\" fill=\"#c8b48a\" stroke=\"#333\" stroke-width=\"0.15\"><path d=\"")
    for (p in l.loops) appendPath(p)
    append("\"/></g>\n")
    if (l.openChains.isNotEmpty()) {
        append("<g fill=\"none\" stroke=\"#e00\" stroke-width=\"0.3\">")
        for (c in l.openChains) {
            append("<path d=\"")
            for (i in 0 until c.size / 2) append(if (i == 0) "M" else "L").append(c[2 * i] / 1000.0).append(' ').append(-c[2 * i + 1] / 1000.0)
            append("\"/>")
        }
        append("</g>\n")
    }
    append("</svg>\n")
}

private fun StringBuilder.appendPath(p: Polygon) {
    for (i in 0 until p.size) {
        append(if (i == 0) "M" else "L").append(p.x(i) / 1000.0).append(' ').append(-p.y(i) / 1000.0)
    }
    append("Z")
}

/** Up to `n` layers, evenly spread over the stack, tiled left-to-right, top-to-bottom, with z labels. */
private fun contactSheet(layers: List<Layer>, b: DoubleArray, n: Int): BufferedImage {
    val picks = if (layers.size <= n) layers.indices.toList() else (0 until n).map { it * (layers.size - 1) / (n - 1) }
    val cols = Math.ceil(Math.sqrt(picks.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = (picks.size + cols - 1) / cols
    val tile = 300
    val img = BufferedImage(cols * tile, rows * tile, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    g.color = Color(0xEE, 0xEE, 0xEE); g.fillRect(0, 0, img.width, img.height)
    val w = b[3] - b[0]; val h = b[4] - b[1]
    val scale = (tile - 30) / Math.max(w, h)
    for ((k, li) in picks.withIndex()) {
        val l = layers[li]
        val ox = (k % cols) * tile + 15 + ((tile - 30) - w * scale) / 2
        val oy = (k / cols) * tile + 15 + ((tile - 30) - h * scale) / 2
        val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
        for (p in l.loops) {
            for (i in 0 until p.size) {
                val x = ox + (p.x(i) / 1000.0 - b[0]) * scale
                val y = oy + (b[4] - p.y(i) / 1000.0) * scale
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.closePath()
        }
        g.color = Color(0xC8, 0xB4, 0x8A); g.fill(path)
        g.color = Color(0x33, 0x33, 0x33); g.stroke = BasicStroke(1f); g.draw(path)
        g.color = Color.RED
        for (c in l.openChains) for (i in 1 until c.size / 2) {
            g.drawLine((ox + (c[2 * i - 2] / 1000.0 - b[0]) * scale).toInt(), (oy + (b[4] - c[2 * i - 1] / 1000.0) * scale).toInt(),
                (ox + (c[2 * i] / 1000.0 - b[0]) * scale).toInt(), (oy + (b[4] - c[2 * i + 1] / 1000.0) * scale).toInt())
        }
        g.color = Color.BLACK
        g.drawString("layer $li  z=%.2f  loops=%d".format(l.z, l.loops.size), (k % cols) * tile + 6, (k / cols) * tile + 14)
    }
    g.dispose()
    return img
}
