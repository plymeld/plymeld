package plymeld.check.plate

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * A side section through one step of the terraced melt test (`plymeld render-plate --section`), read from
 * the g-code text alone, nothing from plymeld's own model of the plate:
 *   1: the step cut front to back (Y across, Z up, Z stretched) at the middle of its FAST surface: every extrusion the plane
 *      cuts, as its bead's cross-section (its declared width; its height the layer's, a melt strip's its Z over its step's
 *      floor), colored by kind (base, body, floor, strips by thickness, the step behind); each strip labeled with its
 *      thickness and Z as the g-code prints it
 *   2: the head's path from the step's FAST surface's last strip to its MID surface's first (the travel between the
 *      surfaces), against distance along it: the nozzle's Z, and under it the tallest top of everything laid before it (any
 *      bead within half its width of the point); the smallest gap over the travel's horizontal moves is printed
 */
object PlateSection {
    private class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val z: Double, val w: Double, val cell: String, val order: Int)
    private class Pt(val x: Double, val y: Double, val z: Double, val order: Int, val extruding: Boolean)

    /** [band]: the step (from 1, the front). Returns the smallest clearance (mm) over the travel's horizontal moves. */
    fun render(gcodeFile: Path, outFile: Path, band: Int = 1, log: (String) -> Unit = ::println): Double {
        System.setProperty("java.awt.headless", "true")
        val segs = ArrayList<Seg>(); val path = ArrayList<Pt>()
        run {
            var x = Double.NaN; var y = Double.NaN; var z = 0.0; var cell = ""; var wd = 0.44; var n = 0
            for (raw in File(gcodeFile.toString()).readLines()) {
                val line = raw.trim()
                if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: ").removePrefix("adhesion:"); continue }
                if (line == "; CELL END") { cell = ""; continue }
                if (line.startsWith(";WIDTH:")) { wd = line.removePrefix(";WIDTH:").trim().toDouble(); continue }
                val code = line.substringBefore(';').trim(); if (code.isEmpty()) continue
                val w = code.split(Regex("\\s+"))
                if (w[0] != "G1" && w[0] != "G0") continue
                fun num(c: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == c }?.substring(1)?.toDouble()
                val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
                if (nx.isNaN() || ny.isNaN()) { x = nx; y = ny; z = nz; continue }
                val moved = x.isNaN() || Math.hypot(nx - x, ny - y) > 1e-9 || Math.abs(nz - z) > 1e-9
                val printing = e > 0 && !x.isNaN() && Math.hypot(nx - x, ny - y) > 1e-9 && cell.isNotEmpty()
                if (printing) segs += Seg(x, y, nx, ny, nz, wd, cell, n)
                if (moved) path += Pt(nx, ny, nz, n, printing)
                x = nx; y = ny; z = nz; n++
            }
        }
        val stripRx = Regex("melt (\\d+)\\.(fast|mid|slow)\\.(\\d+)")
        fun stripOf(c: String) = stripRx.matchEntire(c)?.groupValues
        val fast = segs.filter { stripOf(it.cell)?.let { g -> g[1].toInt() == band && g[2] == "fast" } == true }
        require(fast.isNotEmpty()) { "no 'melt $band.fast.*' strips in ${gcodeFile.fileName}: not a melt plate, or no band $band" }
        val floorTop = segs.filter { it.cell == "melt floor $band.fast" }.map { it.z }.distinct().singleOrNull()
            ?: error("band $band's FAST floor ('melt floor $band.fast') is not on one layer")
        // the layer height: the smallest step between the Zs of everything but the strips (which stand at their own)
        val layerZs = segs.filter { stripOf(it.cell) == null }.map { it.z }.distinct().sorted()
        val lh = layerZs.zipWithNext { a, b -> b - a }.filter { it > 1e-6 }.minOrNull() ?: 0.2
        fun floorOf(b: Int) = segs.filter { it.cell == "melt floor $b.fast" }.map { it.z }.distinct().singleOrNull()
        fun heightOf(s: Seg): Double {
            val g = stripOf(s.cell) ?: return if (s.z <= layerZs.first() + 1e-6) s.z else lh
            return s.z - (floorOf(g[1].toInt()) ?: Double.NaN)
        }

        val W = 1900; val H = 1100
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 252); g.fillRect(0, 0, W, H)
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString("Melt step $band in section, as written to g-code: ${gcodeFile.fileName}", 20, 34)

        // ---- 1: the cut front to back at the middle of the FAST surface
        // midway between the two front-to-back body lines nearest the surface's middle, so the cut shows the grid as it is
        // (its left-right lines in section, gaps between them) and not one front-to-back line lying in the plane
        val mid = (fast.minOf { minOf(it.x0, it.x1) } + fast.maxOf { maxOf(it.x0, it.x1) }) / 2
        val bodyXs = segs.filter { it.cell == "melt body $band.fast" && Math.abs(it.x1 - it.x0) < 1e-9 }.map { it.x0 }.distinct().sorted()
        val xCut = bodyXs.zipWithNext { a, b -> (a + b) / 2 }.minByOrNull { Math.abs(it - mid) } ?: mid
        val yLo = fast.minOf { minOf(it.y0, it.y1) } - 4; val yHi = fast.maxOf { maxOf(it.y0, it.y1) } + 6
        val zHi = fast.maxOf { it.z } + 0.6
        val ax = 70; val ay = 110; val aw = 1780; val ah = 470
        val sy = aw / (yHi - yLo); val sz = ah / zHi
        fun px(y: Double) = ax + (y - yLo) * sy
        fun pz(z: Double) = ay + ah - z * sz
        g.font = Font("SansSerif", Font.PLAIN, 15)
        g.drawString("1  cut front to back at x %.2f (the middle of its FAST surface): every bead the plane cuts, its declared width by its height; Y across (front left), Z up, Z drawn %.1fx"
            .format(xCut, sz / sy), ax - 50, ay - 40)
        g.drawString("height: the layer's (%.2f mm), a strip's its Z over its step's floor; strips colored thin (pale) to thick (dark); floor green, body gray, base tan, the step behind blue".format(lh), ax - 50, ay - 20)
        val heights = fast.map { Math.round(heightOf(it) * 1000) / 1000.0 }.distinct().sorted()
        fun colorOf(s: Seg): Color {
            val c = s.cell; val sg = stripOf(c)
            return when {
                sg != null && sg[1].toInt() == band -> { val f = heights.indexOf(Math.round(heightOf(s) * 1000) / 1000.0).toDouble() / maxOf(1, heights.size - 1)
                    Color((250 - 150 * f).toInt(), (190 - 140 * f).toInt(), (120 - 100 * f).toInt()) }
                c.startsWith("melt floor $band.") -> Color(60, 160, 90)
                c.startsWith("melt body $band.") -> Color(150, 150, 158)
                c.startsWith("base ") -> Color(214, 190, 150)
                else -> Color(110, 140, 210)
            }
        }
        var cut = 0
        g.stroke = BasicStroke(0.6f)
        for (s in segs) {
            val dx = s.x1 - s.x0
            val h = heightOf(s); if (h.isNaN()) continue
            val rect = when {
                // crossing the plane: its cross-section, centered where it crosses
                Math.abs(dx) > 1e-9 && (xCut - s.x0) * (xCut - s.x1) < 0 -> { val t = (xCut - s.x0) / dx; val yc = s.y0 + t * (s.y1 - s.y0)
                    if (yc < yLo || yc > yHi) null else Rectangle2D.Double(px(yc - s.w / 2), pz(s.z), s.w * sy, h * sz) }
                // along the plane (front to back) within half its width: lying in it
                Math.abs(dx) <= 1e-9 && Math.abs(s.x0 - xCut) < s.w / 2 -> { val a = maxOf(yLo, minOf(s.y0, s.y1)); val b = minOf(yHi, maxOf(s.y0, s.y1))
                    if (b <= a) null else Rectangle2D.Double(px(a - s.w / 2), pz(s.z), (b - a + s.w) * sy, h * sz) }
                else -> null
            } ?: continue
            g.color = colorOf(s); g.fill(rect); g.color = Color(60, 60, 65); g.draw(rect); cut++
        }
        // the floor's top, and each strip's thickness and Z (from its own lines)
        g.color = Color(30, 120, 60); g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f)
        g.draw(Line2D.Double(px(yLo), pz(floorTop), px(yHi), pz(floorTop)))
        g.font = Font("SansSerif", Font.PLAIN, 13); g.drawString("floor top z %.2f".format(floorTop), px(yLo).toInt() + 6, pz(floorTop).toInt() + 16)
        g.color = Color.BLACK
        val rows = ArrayList<String>()
        for ((k, ss) in fast.groupBy { stripOf(it.cell)!![3].toInt() }.toSortedMap()) {
            val ys = ss.filter { Math.abs(it.y1 - it.y0) < 1e-9 }.map { it.y0 }
            val z = ss.map { it.z }.distinct().single(); val h = z - floorTop
            val ym = (ys.min() + ys.max()) / 2
            g.drawString("strip $k", (px(ym) - 22).toInt(), (pz(z) - 34).toInt())
            g.drawString("h %.2f, z %.2f".format(h, z), (px(ym) - 40).toInt(), (pz(z) - 18).toInt())
            rows += "strip $k: z %.2f, h %.2f mm, %d lines, y %.2f-%.2f".format(z, h, ys.size, ys.min(), ys.max())
        }
        g.drawString("front", ax, ay + ah + 20); g.drawString("back (the step behind)", ax + aw - 160, ay + ah + 20)
        g.drawString("z 0", ax - 40, pz(0.0).toInt() + 5); g.drawString("z %.1f".format(zHi), ax - 52, pz(zHi).toInt() + 5)
        g.color = Color(120, 120, 120); g.drawRect(ax, ay, aw, ah)

        // ---- 2: the travel from FAST's last strip to MID's first
        val lastFast = fast.maxOf { it.order }
        val firstMid = segs.filter { stripOf(it.cell)?.let { gg -> gg[1].toInt() == band && gg[2] == "mid" } == true }.minOfOrNull { it.order }
            ?: error("no 'melt $band.mid.*' strips in ${gcodeFile.fileName}")
        val start = path.indexOfLast { it.order == lastFast }
        // from the end of FAST's last line to the start of MID's first (that line itself not included)
        val trip = path.filter { it.order in lastFast until firstMid }.let { p -> if (p.first().order == lastFast) p else listOf(path[start]) + p }
        val before = segs.filter { it.order <= lastFast }
        fun topUnder(x: Double, y: Double): Double {
            var best = 0.0
            for (s in before) {
                val vx = s.x1 - s.x0; val vy = s.y1 - s.y0; val l2 = vx * vx + vy * vy
                val t = if (l2 == 0.0) 0.0 else (((x - s.x0) * vx + (y - s.y0) * vy) / l2).coerceIn(0.0, 1.0)
                if (Math.hypot(x - (s.x0 + t * vx), y - (s.y0 + t * vy)) <= s.w / 2 && s.z > best) best = s.z
            }
            return best
        }
        // samples every 0.1 mm along the path's horizontal moves: (distance, nozzle z, tallest top under it)
        class Sample(val d: Double, val z: Double, val top: Double, val horizontal: Boolean)
        val samples = ArrayList<Sample>(); var dist = 0.0
        for ((a, b) in trip.zipWithNext()) {
            val l = Math.hypot(b.x - a.x, b.y - a.y)
            if (l < 1e-9) { samples += Sample(dist, a.z, topUnder(a.x, a.y), false); samples += Sample(dist, b.z, topUnder(b.x, b.y), false); continue }
            val n = maxOf(1, Math.ceil(l / 0.1).toInt())
            for (i in 0..n) { val t = i.toDouble() / n; samples += Sample(dist + t * l, a.z + t * (b.z - a.z), topUnder(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y)), b.extruding.not()) }
            dist += l
        }
        val travel = samples.filter { it.horizontal }
        val minGap = travel.minOf { it.z - it.top }
        val bx = 70; val by = 700; val bw = 1780; val bh = 300
        val dMax = maxOf(1.0, dist); val zMax = samples.maxOf { maxOf(it.z, it.top) } + 0.3; val zMin = maxOf(0.0, samples.minOf { minOf(it.z, it.top) } - 0.3)
        fun qx(d: Double) = bx + d / dMax * bw
        fun qz(z: Double) = by + bh - (z - zMin) / (zMax - zMin) * bh
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 15)
        g.drawString("2  the head from FAST's last strip to MID's first (band $band), by distance along its path: the nozzle's Z (red), the tallest top laid under it (black, any bead within half its width)", bx - 50, by - 40)
        g.drawString("smallest gap over the travel's horizontal moves: %.2f mm (nozzle Z minus the tallest top under it); the path %.1f mm long".format(minGap, dist), bx - 50, by - 20)
        g.color = Color(120, 120, 120); g.stroke = BasicStroke(1f); g.drawRect(bx, by, bw, bh)
        g.color = Color.BLACK; g.stroke = BasicStroke(2f)
        for ((a, b) in samples.zipWithNext()) g.draw(Line2D.Double(qx(a.d), qz(a.top), qx(b.d), qz(b.top)))
        g.color = Color(200, 40, 40); g.stroke = BasicStroke(2.5f)
        for ((a, b) in samples.zipWithNext()) g.draw(Line2D.Double(qx(a.d), qz(a.z), qx(b.d), qz(b.z)))
        g.font = Font("SansSerif", Font.PLAIN, 13); g.color = Color.BLACK
        var zl = Math.ceil(zMin * 10) / 10; while (zl <= zMax) { g.drawString("%.1f".format(zl), bx - 34, qz(zl).toInt() + 5); zl += 0.2 }
        g.drawString("0 mm", bx, by + bh + 18); g.drawString("%.1f mm".format(dist), bx + bw - 50, by + bh + 18)
        g.drawString("left: the end of FAST's strip ${fast.groupBy { stripOf(it.cell)!![3] }.keys.maxOf { it.toInt() }}; right: the start of MID's strip 1", bx + 600, by + bh + 18)
        g.dispose()
        ImageIO.write(img, "png", File(outFile.toString()))
        log("section of melt step $band at x %.2f: %d beads cut; floor top z %.2f; layer %.2f mm".format(xCut, cut, floorTop, lh))
        rows.forEach { log("  $it") }
        log("travel FAST -> MID: %.1f mm, smallest gap over its horizontal moves %.2f mm".format(dist, minGap))
        return minGap
    }
}
