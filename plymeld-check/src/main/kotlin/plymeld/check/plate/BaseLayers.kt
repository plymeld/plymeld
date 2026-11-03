package plymeld.check.plate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Close-up of one calibration-plate base's layers, read from the g-code only (`plymeld render-plate --layers`).
 * Top row: each base layer whole; bottom row: a zoom round its notch. Every extrusion move of the base's cell drawn at the
 * width its path declares (;WIDTH:), colored by its place in the layer's print order (first = dark blue ... last = yellow);
 * travels inside the zoom drawn thin and dashed. Titles: what the g-code shows (rings = closed wall paths, the lines' angle
 * measured from the diagonal moves, line count = distinct offsets across the lines).
 * Ported from the workspace script base_layers.main.kts, drawing the same picture.
 */
object BaseLayers {
    /** The base cells ("base <family>") of a plate's g-code, in the order they first appear. */
    fun baseCells(gcodePath: Path): List<String> = File(gcodePath.toString()).readLines().asSequence()
        .map { it.trim() }.filter { it.startsWith("; CELL: ") }
        .map { it.removePrefix("; CELL: ").removePrefix("adhesion:").trim() }.filter { it.startsWith("base ") }.distinct().toList()

    fun render(gcodePath: Path, baseCell: String, outPath: Path, log: (String) -> Unit = ::println) {
        System.setProperty("java.awt.headless", "true")

        class Mv(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val w: Double, val print: Boolean, val type: String, val path: Int)
        // z (mm, 3 decimals) -> moves of the base cell in order (prints and the travels between them)
        val layers = java.util.TreeMap<Double, ArrayList<Mv>>()
        run {
            var cell: String? = null; var type = ""; var wd = 0.44; var x = Double.NaN; var y = Double.NaN; var z = 0.0; var path = 0
            for (raw in File(gcodePath.toString()).readLines()) {
                val l = raw.trim()
                if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: ").removePrefix("adhesion:"); path++; continue }
                if (l == "; CELL END") { cell = null; continue }
                if (l.startsWith(";TYPE:")) { type = l.removePrefix(";TYPE:").trim(); continue }
                if (l.startsWith(";WIDTH:")) { wd = l.removePrefix(";WIDTH:").trim().toDouble(); continue }
                // the layer: its ;Z: comment (travels lift above it)
                if (l.startsWith(";Z:")) { z = l.removePrefix(";Z:").trim().toDouble(); continue }
                val c = l.substringBefore(';').trim(); if (!c.startsWith("G1")) continue
                val ws = c.split(Regex("\\s+"))
                fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                if (cell == baseCell && !x.isNaN() && (nx != x || ny != y))
                    layers.getOrPut(Math.round(z * 1000) / 1000.0) { ArrayList() } += Mv(x, y, nx, ny, wd, e > 0, type, path)
                x = nx; y = ny
            }
        }
        // the base's layers: the ones holding its cell (the bases are the plate's lowest layers)
        val zs = layers.keys.toList()
        require(zs.isNotEmpty()) { "no '$baseCell' cell in $gcodePath" }

        fun ramp(t: Double): Color {
            // dark blue -> teal -> green -> yellow (a viridis-like ramp, by hand)
            val stops = listOf(doubleArrayOf(0.27, 0.0, 0.33), doubleArrayOf(0.23, 0.32, 0.55), doubleArrayOf(0.13, 0.57, 0.55), doubleArrayOf(0.37, 0.79, 0.38), doubleArrayOf(0.99, 0.91, 0.14))
            val s = t.coerceIn(0.0, 1.0) * (stops.size - 1); val i = minOf(s.toInt(), stops.size - 2); val f = s - i
            val a = stops[i]; val b = stops[i + 1]
            return Color((a[0] + (b[0] - a[0]) * f).toFloat(), (a[1] + (b[1] - a[1]) * f).toFloat(), (a[2] + (b[2] - a[2]) * f).toFloat())
        }

        // extents of the base (all layers)
        val all = layers.values.flatten().filter { it.print }
        val bx0 = all.minOf { minOf(it.x0, it.x1) } - 1; val bx1 = all.maxOf { maxOf(it.x0, it.x1) } + 1
        val by0 = all.minOf { minOf(it.y0, it.y1) } - 1; val by1 = all.maxOf { maxOf(it.y0, it.y1) } + 1
        val panelW = 620; val scaleTop = panelW / (bx1 - bx0); val topH = ((by1 - by0) * scaleTop).toInt()
        val zoomW = 620; val zoomHalfX = 7.0; val zoomScale = zoomW / (2 * zoomHalfX); val zoomDepth = 10.0; val zoomH = (zoomDepth * zoomScale).toInt()
        val pad = 30; val titleH = 64
        val W = pad + zs.size * (panelW + pad); val H = titleH + topH + pad + zoomH + 90
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color(246, 246, 243); g.fillRect(0, 0, W, H)
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 17)
        g.drawString("$baseCell: its ${zs.size} layers from the g-code (${gcodePath.fileName}); color = print order within the layer (dark blue first, yellow last), lines at their declared widths", pad, 22)

        for ((pi, z) in zs.withIndex()) {
            val mv = layers.getValue(z)
            val prints = mv.filter { it.print }
            // what the g-code shows: rings (closed wall paths), lines' angle (diagonal moves), distinct offsets
            val byPath = prints.groupBy { it.path }
            val rings = byPath.values.count { ps -> ps.first().type.endsWith("wall", true) && Math.abs(ps.first().x0 - ps.last().x1) < 0.002 && Math.abs(ps.first().y0 - ps.last().y1) < 0.002 }
            val diag = prints.filter { Math.abs(it.x1 - it.x0) > 0.002 && Math.abs(it.y1 - it.y0) > 0.002 }
            val longest = diag.maxByOrNull { Math.hypot(it.x1 - it.x0, it.y1 - it.y0) }
            val angle = longest?.let { var a = Math.toDegrees(Math.atan2(it.y1 - it.y0, it.x1 - it.x0)); if (a < 0) a += 180.0; a } ?: Double.NaN
            val offsets = longest?.let { _ -> val r = Math.toRadians(angle); diag.map { -Math.sin(r) * it.x0 + Math.cos(r) * it.y0 }.sorted().fold(ArrayList<Double>()) { acc, v -> if (acc.isEmpty() || v - acc.last() > 0.01) acc += v; acc } } ?: arrayListOf()
            val gaps = offsets.zipWithNext { a, b -> b - a }
            val px0 = pad + pi * (panelW + pad)
            g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 15)
            g.drawString("layer ${pi + 1} (z $z): $rings ring(s), then ${offsets.size} lines at ${String.format("%.1f", angle)} deg", px0, 44)
            g.font = Font("SansSerif", Font.PLAIN, 13)
            g.drawString("width ${prints.map { it.w }.distinct().joinToString()} mm; line gaps ${if (gaps.isEmpty()) "-" else String.format("%.3f-%.3f", gaps.min(), gaps.max())} mm; ${byPath.size - rings} runs", px0, 60)
            // top: whole base
            fun tx(x: Double) = px0 + (x - bx0) * scaleTop
            fun ty(y: Double) = titleH + topH - (y - by0) * scaleTop
            g.color = Color.WHITE; g.fillRect(px0, titleH, panelW, topH)
            for ((i, m) in prints.withIndex()) {
                g.color = ramp(i.toDouble() / maxOf(1, prints.size - 1))
                g.stroke = BasicStroke(maxOf(1f, (m.w * scaleTop).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(tx(m.x0), ty(m.y0), tx(m.x1), ty(m.y1)))
            }
            // the notch: the outer ring's front-face gap (the first path's frontmost moves)
            val outer = byPath.values.first()
            val frontY = outer.minOf { minOf(it.y0, it.y1) }
            val back = outer.filter { Math.abs(it.y0 - it.y1) < 0.002 && it.y0 > frontY + 0.5 && it.y0 < frontY + 6 }
            val notchX = back.firstOrNull()?.let { (it.x0 + it.x1) / 2 } ?: run {
                // the first layer has no notch: use the next layer's
                val nxt = layers.getValue(zs[minOf(pi + 1, zs.size - 1)]).filter { it.print }.groupBy { it.path }.values.first()
                nxt.filter { Math.abs(it.y0 - it.y1) < 0.002 && it.y0 > frontY + 0.5 && it.y0 < frontY + 6 }.firstOrNull()?.let { (it.x0 + it.x1) / 2 } ?: (bx0 + bx1) / 2
            }
            val zx0 = notchX - zoomHalfX; val zy0 = frontY - 1.0
            g.color = Color.RED; g.stroke = BasicStroke(2f)
            g.drawRect(tx(zx0).toInt(), ty(zy0 + zoomDepth).toInt(), (2 * zoomHalfX * scaleTop).toInt(), (zoomDepth * scaleTop).toInt())
            // bottom: zoom round the notch
            val zTop = titleH + topH + pad
            fun zx(x: Double) = px0 + (x - zx0) * zoomScale
            fun zy(y: Double) = zTop + zoomH - (y - zy0) * zoomScale
            g.color = Color.WHITE; g.fillRect(px0, zTop, zoomW, zoomH)
            val clip = g.clip; g.clipRect(px0, zTop, zoomW, zoomH)
            // in the zoom the color is the order among the moves that reach into it (so the order round the notch reads)
            fun inZoom(m: Mv) = maxOf(m.x0, m.x1) >= zx0 - 1 && minOf(m.x0, m.x1) <= zx0 + 2 * zoomHalfX + 1 && maxOf(m.y0, m.y1) >= zy0 - 1 && minOf(m.y0, m.y1) <= zy0 + zoomDepth + 1
            val local = prints.filter { inZoom(it) }
            for ((i, m) in local.withIndex()) {
                g.color = ramp(i.toDouble() / maxOf(1, local.size - 1))
                g.stroke = BasicStroke((m.w * zoomScale).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.draw(Line2D.Double(zx(m.x0), zy(m.y0), zx(m.x1), zy(m.y1)))
            }
            // each run (path) that reaches into the zoom, numbered in print order on the layer, at its first move in the zoom
            val runOrder = byPath.keys.toList()
            g.font = Font("SansSerif", Font.BOLD, 15)
            for ((pathId, ms) in local.groupBy { it.path }) {
                val m = ms.first(); val n = runOrder.indexOf(pathId) + 1
                val lx = zx((m.x0 + m.x1) / 2).toInt().coerceIn(px0 + 4, px0 + zoomW - 40); val ly = zy((m.y0 + m.y1) / 2).toInt().coerceIn(zTop + 16, zTop + zoomH - 4)
                g.color = Color.WHITE; g.fillRect(lx - 2, ly - 14, 34, 18); g.color = Color.BLACK; g.drawString("#$n", lx, ly)
            }
            // center lines and travels, thin, so neighboring beads and the joins read
            g.stroke = BasicStroke(1f); g.color = Color(0, 0, 0, 90)
            for (m in prints) g.draw(Line2D.Double(zx(m.x0), zy(m.y0), zx(m.x1), zy(m.y1)))
            g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f); g.color = Color(200, 0, 0)
            for (m in mv) if (!m.print) g.draw(Line2D.Double(zx(m.x0), zy(m.y0), zx(m.x1), zy(m.y1)))
            g.clip = clip
            g.color = Color.RED; g.stroke = BasicStroke(2f); g.drawRect(px0, zTop, zoomW, zoomH)
            g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 13)
            g.drawString("zoom: x ${String.format("%.1f", zx0)}..${String.format("%.1f", zx0 + 2 * zoomHalfX)}, y ${String.format("%.1f", zy0)}..${String.format("%.1f", zy0 + zoomDepth)} (the notch: the outer ring's front-face gap)", px0, zTop + zoomH + 18)
            g.drawString("zoom color: order among the moves in it; #n: the layer's n-th path (rings 1-$rings); red dashed: travels", px0, zTop + zoomH + 34)
        }
        // color bar
        val cbY = H - 30; val cbX = pad
        for (i in 0 until 400) { g.color = ramp(i / 399.0); g.fillRect(cbX + i, cbY, 1, 14) }
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 13)
        g.drawString("first", cbX, cbY - 3); g.drawString("last (print order within the layer)", cbX + 330, cbY - 3)
        ImageIO.write(img, "png", outPath.toFile())
        log("wrote $outPath (${W}x$H), layers ${zs.joinToString()}")
    }
}
