package plymeld.geom

import java.nio.file.Files
import java.nio.file.Path

/**
 * Draws polygon sets and directed pieces to an SVG so a disputed boolean case can be
 * looked at instead of reasoned about. Coordinates are grid units scaled to mm.
 */
object DebugSvg {
    class Layer(val label: String, val color: String, val polygons: List<Polygon> = emptyList(), val pieces: LongArray? = null)

    fun write(path: Path, vararg layers: Layer) {
        var minX = Long.MAX_VALUE; var minY = Long.MAX_VALUE; var maxX = Long.MIN_VALUE; var maxY = Long.MIN_VALUE
        for (l in layers) {
            for (p in l.polygons) { val b = p.bounds(); minX = minOf(minX, b[0]); minY = minOf(minY, b[1]); maxX = maxOf(maxX, b[2]); maxY = maxOf(maxY, b[3]) }
            l.pieces?.let { s -> for (i in s.indices step 2) { minX = minOf(minX, s[i]); maxX = maxOf(maxX, s[i]); minY = minOf(minY, s[i + 1]); maxY = maxOf(maxY, s[i + 1]) } }
        }
        if (minX == Long.MAX_VALUE) { minX = 0; minY = 0; maxX = 1000; maxY = 1000 }
        val pad = maxOf(maxX - minX, maxY - minY) / 20 + 1
        val w = (maxX - minX + 2 * pad) / 1000.0; val h = (maxY - minY + 2 * pad) / 1000.0
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="${(minX - pad) / 1000.0} ${-(maxY + pad) / 1000.0} $w $h" width="${w * 20}" height="${h * 20}">""").append('\n')
        sb.append("""<rect x="${(minX - pad) / 1000.0}" y="${-(maxY + pad) / 1000.0}" width="$w" height="$h" fill="#f4f4f4"/>""").append('\n')
        for (l in layers) {
            sb.append("<g stroke=\"${l.color}\" stroke-width=\"${w / 400}\" fill=\"${l.color}\" fill-opacity=\"0.15\">\n")
            for (p in l.polygons) {
                sb.append("<path d=\"")
                for (i in 0 until p.size) sb.append(if (i == 0) "M" else "L").append(p.x(i) / 1000.0).append(' ').append(-p.y(i) / 1000.0)
                sb.append("Z\"/>\n")
            }
            l.pieces?.let { s ->
                for (i in s.indices step 4) {
                    val x0 = s[i] / 1000.0; val y0 = -s[i + 1] / 1000.0; val x1 = s[i + 2] / 1000.0; val y1 = -s[i + 3] / 1000.0
                    sb.append("<line x1=\"$x0\" y1=\"$y0\" x2=\"$x1\" y2=\"$y1\"/>\n")
                    // arrowhead: a short tick at 80% along, offset to the left
                    val mx = x0 + (x1 - x0) * 0.8; val my = y0 + (y1 - y0) * 0.8
                    val dx = x1 - x0; val dy = y1 - y0; val len = Math.hypot(dx, dy).coerceAtLeast(1e-9)
                    val t = w / 150
                    sb.append("<line x1=\"$mx\" y1=\"$my\" x2=\"${mx - dx / len * t + dy / len * t}\" y2=\"${my - dy / len * t - dx / len * t}\"/>\n")
                }
            }
            sb.append("</g>\n<text x=\"${(minX - pad) / 1000.0 + w * 0.01}\" y=\"${-(maxY + pad) / 1000.0 + h * 0.05 * (layers.indexOf(l) + 1)}\" font-size=\"${h / 25}\" fill=\"${l.color}\">${l.label}</text>\n")
        }
        sb.append("</svg>\n")
        path.parent?.let { Files.createDirectories(it) }
        Files.writeString(path, sb.toString())
    }
}
