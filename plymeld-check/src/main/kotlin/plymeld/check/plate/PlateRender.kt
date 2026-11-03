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
 * Renders a plymeld calibration plate's g-code (`plymeld render-plate`): panels read from the g-code text alone, nothing from
 * plymeld's own model of the plate. Ported from the workspace script render_plate.main.kts, drawing the same
 * picture; its description of the panels:
 *
 * The plates made by band of height (the melt and filament plates; cells other than the first-layer
 * plate's "block k ..."; the ironing, seam, and motion plates are drawn the same way, panel A their top view):
 *   A: top view of one layer, each extrusion colored by its volumetric flow (mm3/s); with the terraced melt test (cells
 *      "melt B.fast|mid|slow.K"), every step's top (its strips, each on its own layer) drawn over it
 *   B: the plate's front faces (lines within 0.4 mm of the front of the post, where the plate has one, and of each melt
 *      step), colored by the nozzle temperature the g-code had set, the fan level drawn beside each layer, and each wait
 *      (M109) after the first layer as a dashed line at the bottom of the layer it comes before
 *   C: the melt test: each strip's flow (mm3/s, speed x filament per mm) against its thickness (now: its Z over
 *      its step's floor, cross-checked against its filament per mm through the bead model at its declared width), a line per
 *      surface, a marker per band, all read from the g-code's strips; with each surface's speed and each strip's line count and
 *      length as read (earlier against its line width; earlier the melt loop's flow round its circle)
 *   D: what the bands were set to, and every temperature command after the first layer began: M104 or M109, its temperature,
 *      and the Z of the extrusion before it and after it
 * The first-layer plate (no melt loop; cells "block k tile r.c", "block k prime", "block k mark";;
 * "block k connector c-c' front|back"):
 *   A: top view of its one layer, each line colored by the nozzle temperature in force when it printed (prime lines and
 *      connectors gray), each tile labeled with the Z and speed of its own moves and a black dot where its first line
 *      starts, each block with that temperature, whether a wait (M109) for it came before the block, and its marker dots
 *      as counted from the lines; the tiles the conditions block says were left out (over the melt limit) drawn as empty
 *      dashed cells where their column's printed tiles put them, labeled with the block's limit as the conditions say it
 *   D: per block, the same from the g-code, in print order, with its limit (conditions block) and its highest tile flow
 *      (speed x filament per mm, from the moves); its frame's Z, width, speeds, and highest flow
 *   E: (a plate with a frame, cells "block k frame c" and "block k frame c-c' front|back") a close-up of
 *      the first block's front gap between columns 1 and 2, every line at its declared width, the frame drawn over the
 *      tiles; in A too every line is drawn at its declared width, the frame last
 * Everything is read from the g-code text, nothing from plymeld's own model of the plate.
 */
object PlateRender {
    /** [layerZ]: the layer for panel A (the melt steps' strips are drawn over it, but those of a step whose floor it is); default 10 mm. */
    fun render(gcodeFile: Path, outFile: Path, layerZ: Double? = null, log: (String) -> Unit = ::println) {
        System.setProperty("java.awt.headless", "true")
        val gcodePath = gcodeFile.toString(); val outPath = outFile.toString()
        val pickZ = layerZ ?: 10.0
        val filArea = Math.PI * 1.75 * 1.75 / 4

        // [w]: the width the g-code declares for the move's path; [e]: its filament (mm), [len]: its length (mm)
        class Mv(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val z: Double, val flow: Double, val temp: Int, val fan: Double, val cell: String, val v: Double = 0.0,
                 val w: Double = 0.44, val e: Double = 0.0) { val len get() = Math.hypot(x1 - x0, y1 - y0) }

        val moves = ArrayList<Mv>()
        // every M104/M109 after the plate's first extrusion: (command, temperature, Z extruded at before it, Z extruded at after it)
        class TempCmd(val cmd: String, val temp: Int, val zBefore: Double, var zAfter: Double = Double.NaN)
        val tempCmds = ArrayList<TempCmd>()
        run {
            var x = Double.NaN; var y = Double.NaN; var z = 0.0; var f = 0.0; var fan = 0.0; var tool = 0; var cell = ""; var wd = 0.44
            val temps = HashMap<Int, Int>()
            for (raw in File(gcodePath).readLines()) {
                val line = raw.trim()
                if ((line.startsWith("M104 ") || line.startsWith("M109 ")) && moves.isNotEmpty())
                    tempCmds += TempCmd(line.substring(0, 4), Regex("S(\\d+)").find(line)!!.groupValues[1].toInt(), moves.last().z)
                if (line.startsWith("; CELL: ")) { cell = line.removePrefix("; CELL: "); continue }
                if (line == "; CELL END") { cell = ""; continue }
                if (line.startsWith(";WIDTH:")) { wd = line.removePrefix(";WIDTH:").trim().toDouble(); continue }
                if (line.startsWith("; LINE_WIDTH:")) { wd = line.removePrefix("; LINE_WIDTH:").trim().toDouble(); continue }
                val code = line.substringBefore(';').trim(); if (code.isEmpty()) continue
                val w = code.split(Regex("\\s+"))
                fun num(c: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == c }?.substring(1)?.toDouble()
                when {
                    w[0] == "G1" || w[0] == "G0" -> {
                        num('F')?.let { f = it }; num('Z')?.let { z = it }
                        val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
                        val len = if (x.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
                        // the plate's own cells only (the start template's prime line is not part of the plate)
                        if (e > 0 && len > 0 && cell.isNotEmpty()) {
                            moves += Mv(x, y, nx, ny, z, f / 60 * e / len * filArea, temps[tool] ?: 0, fan, cell, f / 60, wd, e)
                            tempCmds.filter { it.zAfter.isNaN() }.forEach { it.zAfter = z }
                        }
                        x = nx; y = ny
                    }
                    w[0] == "M104" || w[0] == "M109" -> { val t = num('T')?.toInt() ?: tool; num('S')?.let { temps[t] = it.toInt() } }
                    w[0] == "M106" -> fan = (num('S') ?: 255.0) / 255 * 100
                    w[0] == "M107" -> fan = 0.0
                    w[0].matches(Regex("T\\d+")) -> tool = w[0].substring(1).toInt()
                }
            }
        }
        require(moves.isNotEmpty()) { "no extrusion in $gcodePath" }

        // a viridis-like ramp and a cool-to-warm one
        fun ramp(t: Double, stops: List<Color>): Color {
            val u = t.coerceIn(0.0, 1.0) * (stops.size - 1); val i = Math.min(Math.floor(u).toInt(), stops.size - 2); val f = u - i
            val a = stops[i]; val b = stops[i + 1]
            return Color((a.red + (b.red - a.red) * f).toInt(), (a.green + (b.green - a.green) * f).toInt(), (a.blue + (b.blue - a.blue) * f).toInt())
        }
        val viridis = listOf(Color(68, 1, 84), Color(59, 82, 139), Color(33, 145, 140), Color(94, 201, 98), Color(253, 231, 37))
        val coolwarm = listOf(Color(59, 76, 192), Color(141, 176, 254), Color(221, 221, 221), Color(244, 154, 123), Color(180, 4, 38))

        val W = 1900; val H = 1150
        val img = BufferedImage(W, H, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 252); g.fillRect(0, 0, W, H)
        g.color = Color.BLACK; g.font = Font("SansSerif", Font.BOLD, 22)
        g.drawString("Calibration plate as written to g-code: ${File(gcodePath).name}", 20, 34)
        g.font = Font("SansSerif", Font.PLAIN, 15)

        val minX = moves.minOf { minOf(it.x0, it.x1) }; val maxX = moves.maxOf { maxOf(it.x0, it.x1) }
        val minY = moves.minOf { minOf(it.y0, it.y1) }; val maxY = moves.maxOf { maxOf(it.y0, it.y1) }
        val maxZ = moves.maxOf { it.z }

        // ---- the first-layer plate: its own picture, then stop
        if (moves.all { Regex("block \\d+ (prime|mark|tile \\d+\\.\\d+|connector \\d+-\\d+ (front|back)|frame \\d+(-\\d+ (front|back))?)").matches(it.cell) }) {
            // the conditions block's per-block lines: "; block k = T C, ..., melt limit N mm3/s (...), left out: tile r.c (...); ..."
            val limitOf = HashMap<Int, String>(); val leftOut = HashMap<Int, List<Pair<Int, Int>>>()
            for (l in File(gcodePath).readLines()) {
                val m = Regex("; block (\\d+) = .*melt limit ([0-9.]+) mm3/s.*").matchEntire(l.trim()) ?: continue
                val k = m.groupValues[1].toInt(); limitOf[k] = m.groupValues[2]
                leftOut[k] = Regex("tile (\\d+)\\.(\\d+) \\(").findAll(l.substringAfter("left out:", "")).map { it.groupValues[1].toInt() to it.groupValues[2].toInt() }.toList()
            }
            // per block, from the g-code: whether the last nozzle command before its first line was a wait (M109) for the
            // temperature its lines printed at
            val waitedBefore = HashMap<Int, Boolean>()
            run {
                var lastCmd = ""; var lastT = -1
                for (raw in File(gcodePath).readLines()) {
                    val l = raw.trim()
                    if (l.startsWith("M104") || l.startsWith("M109")) { lastCmd = l.substring(0, 4); lastT = Regex("S(\\d+)").find(l)!!.groupValues[1].toInt() }
                    val m = Regex("; CELL: block (\\d+) ").find(l) ?: continue
                    val k = m.groupValues[1].toInt()
                    if (k !in waitedBefore) waitedBefore[k] = lastCmd == "M109" && lastT == moves.first { it.cell.startsWith("block $k ") }.temp
                }
            }
            fun blockOf(c: String) = Regex("block (\\d+) ").find(c)!!.groupValues[1].toInt()
            fun isFrame(c: String) = c.contains(" frame ")
            val blocks = moves.map { blockOf(it.cell) }.distinct().sorted()
            // marker dots: each block's mark lines, their footprints (0.5 mm wide) merged where they touch
            fun dots(k: Int): Int {
                var boxes = moves.filter { it.cell == "block $k mark" }.map { doubleArrayOf(minOf(it.x0, it.x1) - 0.25, minOf(it.y0, it.y1) - 0.25, maxOf(it.x0, it.x1) + 0.25, maxOf(it.y0, it.y1) + 0.25) }
                while (true) {
                    val merged = ArrayList<DoubleArray>()
                    for (b in boxes) { val hit = merged.firstOrNull { d -> b[0] <= d[2] && b[2] >= d[0] && b[1] <= d[3] && b[3] >= d[1] }
                        if (hit != null) { hit[0] = minOf(hit[0], b[0]); hit[1] = minOf(hit[1], b[1]); hit[2] = maxOf(hit[2], b[2]); hit[3] = maxOf(hit[3], b[3]) } else merged += b.copyOf() }
                    if (merged.size == boxes.size) return merged.size
                    boxes = merged
                }
            }
            val tLo = moves.minOf { it.temp }; val tHi = moves.maxOf { it.temp }
            // A on the left at full height, D on the right (now: a tall plate's labels need the room)
            val ax = 40; val ay = 110; val aw = 1000; val ah = 1000
            // room above the lines for a tile left out at the back and the back row's labels
            val s = minOf(aw / (maxX - minX + 30), ah / (maxY - minY + 14 + (if (leftOut.values.any { it.isNotEmpty() }) 12 else 0)))
            fun px(x: Double) = ax + 90 + (x - minX) * s
            fun py(y: Double) = ay + ah - (y - minY + 4) * s
            g.color = Color.BLACK
            g.drawString("A  top view: each line colored by the nozzle temperature set when it printed (cool blue to warm red, ${tLo}-${tHi} C); prime lines and connectors gray; the frame (a layer up) near-black at its declared width, drawn last", ax, ay - 30)
            g.drawString("each tile labeled with its moves' Z (mm) over speed (mm/s); black dot: a tile's first line's start; dashed: left out (the conditions block), over the block's melt limit; each block with its temperature, wait, and marker dots", ax, ay - 10)
            // every line at its declared width; the first layer first, the frame over it
            for (m in moves.sortedBy { if (isFrame(it.cell)) 1 else 0 }) {
                g.stroke = BasicStroke((m.w * s).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g.color = when { isFrame(m.cell) -> Color(25, 25, 30); m.cell.endsWith("prime") -> Color(150, 150, 155); m.cell.contains(" connector ") -> Color(70, 70, 75); else -> ramp((m.temp - tLo).toDouble() / maxOf(1, tHi - tLo), coolwarm) }
                g.draw(Line2D.Double(px(m.x0), py(m.y0), px(m.x1), py(m.y1)))
            }
            // each tile's first line's start
            g.color = Color.BLACK
            for ((_, ms) in moves.filter { it.cell.contains(" tile ") }.groupBy { it.cell }) g.fill(java.awt.geom.Ellipse2D.Double(px(ms.first().x0) - 3.5, py(ms.first().y0) - 3.5, 7.0, 7.0))
            // the tiles left out: an empty dashed cell where its column's printed tiles put it (the column's x; rows a tile apart, touching)
            for ((k, cells) in leftOut) for ((r, c) in cells) {
                val col = moves.filter { it.cell.startsWith("block $k tile ") && it.cell.endsWith(".$c") }.groupBy { it.cell }
                if (col.isEmpty()) continue
                fun box(ms: List<Mv>) = doubleArrayOf(ms.minOf { minOf(it.x0, it.x1) } - 0.25, ms.minOf { minOf(it.y0, it.y1) } - 0.25, ms.maxOf { maxOf(it.x0, it.x1) } + 0.25, ms.maxOf { maxOf(it.y0, it.y1) } + 0.25)
                val last = col.maxBy { (cl, _) -> cl.substringAfter("tile ").substringBefore('.').toInt() }
                val lastR = last.key.substringAfter("tile ").substringBefore('.').toInt(); val lb = box(last.value); val size = lb[3] - lb[1]
                val y0 = lb[1] + (r - lastR) * size
                g.color = Color(170, 60, 60); g.stroke = BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 5f), 0f)
                g.draw(java.awt.geom.Rectangle2D.Double(px(lb[0]), py(y0 + size), size * s, size * s))
                g.font = Font("SansSerif", Font.PLAIN, 9)
                val cx = px((lb[0] + lb[2]) / 2); val cy = py(y0 + size / 2)
                val lines = listOf("over the", "melt limit", "(${limitOf[k]} mm3/s", "at ${moves.first { it.cell.startsWith("block $k ") }.temp} C)")
                for ((i, txt) in lines.withIndex()) g.drawString(txt, (cx - g.fontMetrics.stringWidth(txt) / 2).toInt(), (cy - 12 + i * 10).toInt())
            }
            g.stroke = BasicStroke((0.5 * s).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.font = Font("SansSerif", Font.BOLD, 11)
            for ((cell, ms) in moves.filter { it.cell.contains(" tile ") }.groupBy { it.cell }) {
                val zs = ms.map { it.z }.distinct(); val vs = ms.map { it.v }.distinct()
                val cx = px((ms.minOf { minOf(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2)
                val cy = py((ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2)
                val l1 = zs.joinToString("/") { "%.2f".format(it) }; val l2 = vs.joinToString("/") { "%.0f".format(it) }
                g.color = Color(20, 20, 20)
                g.drawString(l1, (cx - g.fontMetrics.stringWidth(l1) / 2).toInt(), cy.toInt() - 1)
                g.drawString(l2, (cx - g.fontMetrics.stringWidth(l2) / 2).toInt(), cy.toInt() + 11)
            }
            g.font = Font("SansSerif", Font.BOLD, 16)
            val summary = ArrayList<String>()
            for (k in blocks) {
                val ms = moves.filter { blockOf(it.cell) == k }
                val temps = ms.map { it.temp }.distinct()
                val label = "block $k: ${temps.joinToString("/")} C, ${if (waitedBefore[k] == true) "waited (M109)" else "NO WAIT"}, ${dots(k)} dot(s)"
                val tileMs = ms.filter { it.cell.contains(" tile ") }
                // the top of the block's area: its printed lines or the empty cells above them
                val rows = (tileMs.map { it.cell.substringAfter("tile ").substringBefore('.').toInt() } + (leftOut[k]?.map { it.first } ?: emptyList())).max()
                val size = tileMs.groupBy { it.cell }.values.maxOf { c -> c.maxOf { maxOf(it.y0, it.y1) } - c.minOf { minOf(it.y0, it.y1) } + 0.5 }
                val top = maxOf(ms.maxOf { maxOf(it.y0, it.y1) }, tileMs.minOf { minOf(it.y0, it.y1) } - 0.25 + rows * size)
                val left = ms.minOf { minOf(it.x0, it.x1) }
                g.font = Font("SansSerif", Font.BOLD, 14); g.color = Color(30, 30, 40); g.drawString(label, px(left).toInt(), py(top).toInt() - 18)
                g.font = Font("SansSerif", Font.PLAIN, 12); g.drawString("limit ${limitOf[k] ?: "?"} mm3/s; highest tile %.1f mm3/s".format(tileMs.maxOf { it.flow }), px(left).toInt(), py(top).toInt() - 4)
                summary += label
                summary += "   tile Z ${tileMs.map { it.z }.distinct().sorted().joinToString { "%.2f".format(it) }} mm, speeds ${tileMs.map { it.v }.distinct().sorted().joinToString { "%.0f".format(it) }} mm/s"
                summary += "   ${tileMs.map { it.cell }.distinct().size} tiles, ${leftOut[k]?.size ?: 0} left out; melt limit ${limitOf[k] ?: "?"} mm3/s (conditions block), highest tile flow %.1f mm3/s (g-code)".format(tileMs.maxOf { it.flow })
                summary += "   connectors at Z ${ms.filter { it.cell.contains(" connector ") }.map { it.z }.distinct().sorted().joinToString { "%.2f".format(it) }} mm"
                val fr = ms.filter { isFrame(it.cell) }
                summary += if (fr.isEmpty()) "   frame: none" else "   frame Z ${fr.map { it.z }.distinct().sorted().joinToString { "%.2f".format(it) }} mm, ${fr.map { it.w }.distinct().joinToString { "%.2f".format(it) }} mm wide"
                if (fr.isNotEmpty()) summary += "      ${fr.map { it.v }.distinct().sorted().joinToString { "%.0f".format(it) }} mm/s, highest flow %.1f mm3/s, ${fr.map { it.cell }.distinct().size} cells".format(fr.maxOf { it.flow })
            }
            g.font = Font("SansSerif", Font.PLAIN, 15); g.color = Color.BLACK
            val dx = ax + aw + 60; var dy = ay + 10
            g.drawString("D  blocks in print order, from the g-code (fan ${moves.map { it.fan }.distinct().joinToString { "%.0f".format(it) }} %, layers: one):", dx, dy)
            for (line in summary) { dy += 21; g.drawString(line, dx + 10, dy) }
            // E (with a frame): a close-up of the first block's front gap between columns 1 and 2, every line at its declared
            // width, so the frame's place over the walls (and the tiles' insides left bare) can be read
            moves.filter { it.cell == "block ${blocks.first()} tile 1.1" }.takeIf { it.isNotEmpty() && moves.any { m -> isFrame(m.cell) } }?.let { t11 ->
                val zx0 = t11.maxOf { maxOf(it.x0, it.x1) } - 5.5; val zy0 = t11.minOf { minOf(it.y0, it.y1) } - 1.0
                val zw = 12.0; val zh = 7.0
                val zs = minOf((W - dx - 30) / zw, (H - dy - 70) / zh)
                val zTop = dy + 50
                fun zx(x: Double) = dx + (x - zx0) * zs
                fun zy(y: Double) = zTop + zh * zs - (y - zy0) * zs
                g.color = Color.BLACK; g.font = Font("SansSerif", Font.PLAIN, 15)
                g.drawString("E  close-up, %.0f px/mm: block ${blocks.first()}, front gap between columns 1 and 2".format(zs), dx, zTop - 30)
                g.drawString("lines at their declared widths, white = each line's center; frame near-black on top", dx, zTop - 12)
                val clip = g.clip; g.clipRect(dx, zTop, (zw * zs).toInt(), (zh * zs).toInt())
                g.color = Color(235, 235, 238); g.fillRect(dx, zTop, (zw * zs).toInt(), (zh * zs).toInt())
                for (m in moves.sortedBy { if (isFrame(it.cell)) 1 else 0 }) {
                    if (maxOf(m.x0, m.x1) < zx0 - 1 || minOf(m.x0, m.x1) > zx0 + zw + 1 || maxOf(m.y0, m.y1) < zy0 - 1 || minOf(m.y0, m.y1) > zy0 + zh + 1) continue
                    g.stroke = BasicStroke((m.w * zs).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g.color = when { isFrame(m.cell) -> Color(25, 25, 30, 215); m.cell.contains(" connector ") -> Color(110, 110, 115); else -> ramp((m.temp - tLo).toDouble() / maxOf(1, tHi - tLo), coolwarm) }
                    g.draw(Line2D.Double(zx(m.x0), zy(m.y0), zx(m.x1), zy(m.y1)))
                }
                // every line's center, thin and white, so each line (a tile's wall from its fill) can be told apart
                g.color = Color(255, 255, 255, 170); g.stroke = BasicStroke(1.2f)
                for (m in moves) if (!(maxOf(m.x0, m.x1) < zx0 - 1 || minOf(m.x0, m.x1) > zx0 + zw + 1 || maxOf(m.y0, m.y1) < zy0 - 1 || minOf(m.y0, m.y1) > zy0 + zh + 1))
                    g.draw(Line2D.Double(zx(m.x0), zy(m.y0), zx(m.x1), zy(m.y1)))
                g.clip = clip
                g.color = Color(60, 60, 65); g.stroke = BasicStroke(1f); g.drawRect(dx, zTop, (zw * zs).toInt(), (zh * zs).toInt())
                g.font = Font("SansSerif", Font.PLAIN, 12)
                g.drawString("1 mm", dx + 6, zTop + (zh * zs).toInt() - 14); g.stroke = BasicStroke(3f); g.drawLine(dx + 6, zTop + (zh * zs).toInt() - 8, dx + 6 + zs.toInt(), zTop + (zh * zs).toInt() - 8)
            }
            g.dispose()
            ImageIO.write(img, "png", File(outPath))
            log("wrote $outPath: first-layer plate, ${moves.size} extrusion moves, ${blocks.size} blocks")
            for (line in summary) log("  $line")
            return
        }
        // (the filament plate from here on: no first-layer tiles on it; any left over are drawn apart)
        val tileMoves = moves.filter { it.cell.startsWith("tile ") }

        // the terraced melt test's strips: "melt B.SURFACE.K"; each at its own Z (its floor's
        // top plus its thickness), so they are not layers: kept out of the per-layer panels
        val stripRx = Regex("melt (\\d+)\\.(fast|mid|slow)\\.(\\d+)")
        val strips = moves.filter { stripRx.matches(it.cell) }
        val surfaceNames = listOf("fast", "mid", "slow")
        val layered = moves.filterNot { it.cell.startsWith("tile ") || stripRx.matches(it.cell) }
        // each melt step's floor (cells "melt floor B.SURFACE"): its Z by band, read from its lines
        val floorZ = moves.filter { it.cell.startsWith("melt floor ") }.groupBy { Regex("melt floor (\\d+)\\.").find(it.cell)!!.groupValues[1].toInt() }
            .mapValues { (_, ms) -> ms.map { it.z }.distinct().single() }

        // ---- A: top view of one layer, by flow (and every melt step's top over it)
        val layerZ = layered.map { it.z }.distinct().minBy { Math.abs(it - pickZ) }
        // the chosen layer first, every step's strips over it, but the strips of a step whose floor is the chosen layer (so a
        // step's floor shows: the layer under its strips)
        val floorBand = floorZ.entries.firstOrNull { Math.abs(it.value - layerZ) < 1e-6 }?.key
        val layer = layered.filter { Math.abs(it.z - layerZ) < 1e-6 } + strips.filter { stripRx.matchEntire(it.cell)!!.groupValues[1].toInt() != floorBand }
        val flowLo = layer.minOf { it.flow }; val flowHi = layer.maxOf { it.flow }
        run {
            val ax = 40; val ay = 92; val aw = 1060; val ah = 400
            val s = minOf(aw / (maxX - minX + 4), ah / (maxY - minY + 4))
            fun px(x: Double) = ax + (x - minX + 2) * s
            fun py(y: Double) = ay + ah - (y - minY + 2) * s
            g.color = Color.BLACK; g.drawString("A  top view, layer at z ${"%.2f".format(layerZ)} mm" + (if (strips.isNotEmpty()) " under every melt step's strips (each at its own z)" + (floorBand?.let { " but band $it's, whose floor it is" } ?: "") else "") + ":", ax, ay - 28)
            g.drawString("each line colored by its volumetric flow, at its declared width", ax + 22, ay - 10)
            for (m in layer) {
                g.stroke = BasicStroke((m.w * s).toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)   // each line its declared width
                g.color = ramp((m.flow - flowLo) / (flowHi - flowLo), viridis)
                g.draw(Line2D.Double(px(m.x0), py(m.y0), px(m.x1), py(m.y1)))
            }
            // each melt step's band, from its strips' cell names, at its right
            g.font = Font("SansSerif", Font.PLAIN, 13); g.color = Color.BLACK
            for ((b, ms) in strips.groupBy { stripRx.matchEntire(it.cell)!!.groupValues[1].toInt() }) {
                val yMid = (ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2; val x1 = ms.maxOf { maxOf(it.x0, it.x1) }
                g.drawString("band $b: strips at z %.2f-%.2f, %d C, fan %.0f %%".format(ms.minOf { it.z }, ms.maxOf { it.z }, ms.first().temp, ms.first().fan), px(x1).toInt() + 8, py(yMid).toInt() + 5)
            }
            g.font = Font("SansSerif", Font.PLAIN, 15)
            // the first-layer tiles (layer 0, each at its own Z): amber, each labeled with the Z and speed of its own moves
            if (tileMoves.isNotEmpty()) {
                g.color = Color(200, 150, 40)
                for (m in tileMoves) g.draw(Line2D.Double(px(m.x0), py(m.y0), px(m.x1), py(m.y1)))
                g.font = Font("SansSerif", Font.BOLD, 11)
                for ((cell, ms) in tileMoves.groupBy { it.cell }) {
                    val zs = ms.map { it.z }.distinct(); val vs = ms.map { it.v }.distinct()
                    val cx = px((ms.minOf { minOf(it.x0, it.x1) } + ms.maxOf { maxOf(it.x0, it.x1) }) / 2)
                    val cy = py((ms.minOf { minOf(it.y0, it.y1) } + ms.maxOf { maxOf(it.y0, it.y1) }) / 2)
                    val l1 = zs.joinToString("/") { "%.2f".format(it) }; val l2 = vs.joinToString("/") { "%.0f".format(it) }
                    g.color = Color(30, 30, 30)
                    g.drawString(l1, (cx - g.fontMetrics.stringWidth(l1) / 2).toInt(), cy.toInt() - 1)
                    g.drawString(l2, (cx - g.fontMetrics.stringWidth(l2) / 2).toInt(), cy.toInt() + 11)
                }
                g.font = Font("SansSerif", Font.PLAIN, 15); g.color = Color(150, 105, 20)
                val tx = px(tileMoves.maxOf { maxOf(it.x0, it.x1) }) + 14; val ty = py((tileMoves.minOf { minOf(it.y0, it.y1) } + tileMoves.maxOf { maxOf(it.y0, it.y1) }) / 2)
                g.drawString("first-layer tiles (layer 0, ${tileMoves.map { it.cell }.distinct().size} cells):", tx.toInt(), ty.toInt() - 10)
                g.drawString("each labeled nozzle Z (mm) over speed (mm/s), read from its moves", tx.toInt(), ty.toInt() + 10)
                g.font = Font("SansSerif", Font.PLAIN, 15)
            }
            // color bar
            g.stroke = BasicStroke(1f)
            for (i in 0 until 300) { g.color = ramp(i / 299.0, viridis); g.fillRect(ax + 700 + i, ay + ah + 30, 1, 18) }
            g.color = Color.BLACK
            g.drawString("%.1f".format(flowLo), ax + 680, ay + ah + 66); g.drawString("%.1f mm3/s".format(flowHi), ax + 985, ay + ah + 66)
            g.drawString("flow (speed x filament per mm, read from the g-code)", ax + 700, ay + ah + 86)
        }

        // ---- B: the front face, by temperature, fan beside it
        run {
            val bx = 40; val by = 640; val bw = 1060; val bh = 440
            // the front of each feature: the post's front face, and each melt step's front face (its body's and its strips' lines
            // within 0.4 mm of that step's own front: a staircase, each step at its band's temperature)
            // (the melt plate has no post: NaN, so no line counts as the post's front)
            val postFront = moves.filter { it.cell.endsWith("post") }.minOfOrNull { minOf(it.y0, it.y1) } ?: Double.NaN
            fun stepOf(c: String) = Regex("melt (body |floor )?(\\d+)\\.").find(c.removePrefix("adhesion:"))?.groupValues?.get(2)?.toInt()
            val stepFront = moves.filter { stepOf(it.cell) != null }.groupBy { stepOf(it.cell)!! }.mapValues { (_, ms) -> ms.minOf { minOf(it.y0, it.y1) } }
            val faces = moves.filter { m -> (m.cell.endsWith("post") && Math.abs(m.y0 - m.y1) < 1e-6 && m.y0 < postFront + 0.4) ||
                (stepOf(m.cell)?.let { b -> Math.abs(m.y0 - m.y1) < 1e-6 && m.y0 < stepFront.getValue(b) + 0.4 } ?: false) }
            val sx = bw / (maxX - minX + 4); val sz = bh / (maxZ + 1)
            fun px(x: Double) = bx + (x - minX + 2) * sx
            fun pz(z: Double) = by + bh - z * sz
            g.color = Color.BLACK
            g.drawString("B  front face (x across, z up; z stretched ${"%.1f".format(sz / sx)}x): lines within 0.4 mm of each feature's front, colored by the nozzle's set temperature; fan % at right", bx, by - 10)
            val tLo = faces.minOfOrNull { it.temp } ?: 0; val tHi = faces.maxOfOrNull { it.temp } ?: 0   // a plate with no post or melt step (ironing, seams): the panel stays empty
            g.stroke = BasicStroke(maxOf(1f, (0.2 * sz * 0.8).toFloat()), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            for (m in faces) { g.color = ramp((m.temp - tLo).toDouble() / maxOf(1, tHi - tLo), coolwarm); g.draw(Line2D.Double(px(m.x0), pz(m.z - 0.1), px(m.x1), pz(m.z - 0.1))) }
            // fan per layer
            val fx = bx + bw + 10
            g.stroke = BasicStroke(1f)
            for ((z, ms) in layered.groupBy { it.z }) { val fan = ms.first().fan; g.color = Color(40, 90, 200); g.fillRect(fx, pz(z).toInt(), (fan * 0.6).toInt(), maxOf(1, (0.2 * sz).toInt() - 1)) }
            g.color = Color.BLACK; g.drawString("fan 0-100 %", fx, by + bh + 20)
            // temperature labels per run of layers
            var last = -1; for (z in layered.map { it.z }.distinct().sorted()) { val t = layered.first { it.z == z }.temp; if (t != last) { g.drawString("$t C", fx + 70, pz(z).toInt() + 4); last = t } }
            g.drawString("z 0", bx - 30, pz(0.0).toInt()); g.drawString("z %.1f".format(maxZ), bx - 30, pz(maxZ).toInt())
            // each wait (M109) after the first layer: a dashed line at the bottom of the layer it comes before, labeled at the left
            val lh = layered.map { it.z }.distinct().sorted().zipWithNext { a, b -> b - a }.minOrNull() ?: 0.2
            g.stroke = BasicStroke(1.2f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, floatArrayOf(6f, 4f), 0f); g.font = Font("SansSerif", Font.PLAIN, 12)
            for (w in tempCmds.filter { it.cmd == "M109" && !it.zAfter.isNaN() }) {
                g.color = Color(30, 30, 30); g.draw(Line2D.Double(px(minX), pz(w.zAfter - lh), px(maxX), pz(w.zAfter - lh)))
                g.drawString("M109 ${w.temp}", bx + 4, pz(w.zAfter - lh).toInt() - 2)
            }
            g.font = Font("SansSerif", Font.PLAIN, 15)
        }

        // ---- C: the melt test's strips: flow against line width, a line per surface, a marker per band (all from the g-code)
        val stripSummary = ArrayList<String>()
        if (strips.isNotEmpty()) run {
            val cx = 1200; val cy = 70; val cw = 650; val ch = 420
            // per strip cell, its long lines (along X, over half the strip's longest): its thickness from its Z over its step's floor
            // (the "melt floor B.*" lines' Z:), and again from its filament per mm through the bead model at its
            // declared width (the two must agree); flow = speed x filament per mm x filament area
            class Strip(val band: Int, val surface: Int, val k: Int, val h: Double, val hE: Double, val w: Double, val flow: Double, val v: Double, val lines: Int, val len: Double, val z: Double, val temp: Int, val fan: Double)
            val all = strips.groupBy { it.cell }.map { (c, ms) ->
                val m = stripRx.matchEntire(c)!!
                val band = m.groupValues[1].toInt()
                val longest = ms.maxOf { it.len }; val ls = ms.filter { Math.abs(it.y1 - it.y0) < 1e-6 && it.len > longest / 2 }
                val area = ls.sumOf { it.e } / ls.sumOf { it.len } * filArea          // mm3 per mm: the bead (flow ratio included)
                // (1 - pi/4) h^2 - w h + area = 0, the thinner root
                val w = ls.first().w; val q = 1 - Math.PI / 4
                val hE = (w - Math.sqrt(w * w - 4 * q * area)) / (2 * q)
                Strip(band, surfaceNames.indexOf(m.groupValues[2]), m.groupValues[3].toInt(), ls.first().z - (floorZ[band] ?: Double.NaN), hE, w,
                    ls.map { it.flow }.average(), ls.map { it.v }.average(), ls.size, ls.map { it.len }.average(), ls.first().z, ls.first().temp, ls.first().fan)
            }
            val fHi = all.maxOf { it.flow } * 1.1; val wLo = all.minOf { it.h } - 0.02; val wHi = all.maxOf { it.h } + 0.02
            fun px(wd: Double) = cx + (wd - wLo) / (wHi - wLo) * cw
            fun py(f: Double) = cy + ch - f / fHi * ch
            g.color = Color.BLACK; g.drawString("C  melt strips from the g-code: flow (mm3/s) by strip thickness", cx - 40, cy - 28)
            g.drawString("(its Z over its floor), per surface and band", cx - 18, cy - 10)
            val fStep = listOf(0.5, 1.0, 2.0, 5.0, 10.0).first { fHi / it <= 12 }
            g.color = Color(220, 220, 225); g.stroke = BasicStroke(1f)
            run { var f = 0.0; while (f <= fHi) { g.drawLine(cx, py(f).toInt(), cx + cw, py(f).toInt()); f += fStep } }
            g.color = Color.BLACK; g.drawRect(cx, cy, cw, ch)
            run { var f = 0.0; while (f <= fHi) { g.drawString(if (fStep < 1) "%.1f".format(f) else "%.0f".format(f), cx - 32, py(f).toInt() + 5); f += fStep * 2 } }
            for (hh in all.map { Math.round(it.h * 100) / 100.0 }.distinct().sorted()) g.drawString("%.2f".format(hh), px(hh).toInt() - 14, cy + ch + 18)
            g.drawString("strip thickness (mm): its Z minus its step's floor Z; every strip %s mm wide".format(all.map { "%.2f".format(it.w) }.distinct().joinToString("/")), cx + 60, cy + ch + 38)
            val surfColor = listOf(Color(205, 70, 40), Color(190, 150, 30), Color(50, 100, 190))
            val bands = all.map { it.band }.distinct().sorted()
            for (sf in 0 until 3) {
                val mine = all.filter { it.surface == sf }
                if (mine.isEmpty()) continue
                g.color = surfColor[sf]; g.stroke = BasicStroke(1.5f)
                // the line: band 1's strips in order
                val b1 = mine.filter { it.band == bands.first() }.sortedBy { it.k }
                for ((a, b) in b1.zipWithNext()) g.draw(Line2D.Double(px(a.h), py(a.flow), px(b.h), py(b.flow)))
                // every band's strips, each band's marker a little to the right of the last (so all show)
                for (st in mine) {
                    val bi = bands.indexOf(st.band); val dx = (bi - (bands.size - 1) / 2.0) * 5
                    val x = px(st.h) + dx; val y = py(st.flow)
                    when (bi % 4) { 0 -> g.fill(java.awt.geom.Ellipse2D.Double(x - 4, y - 4, 8.0, 8.0)); 1 -> g.fill(java.awt.Rectangle((x - 4).toInt(), (y - 4).toInt(), 8, 8))
                        2 -> g.draw(java.awt.geom.Ellipse2D.Double(x - 4, y - 4, 8.0, 8.0)); else -> g.draw(java.awt.Rectangle((x - 4).toInt(), (y - 4).toInt(), 8, 8)) }
                }
                val vs = mine.map { it.v }.distinct()
                g.drawString("${surfaceNames[sf].uppercase()}: ${vs.joinToString("/") { "%.1f".format(it) }} mm/s", cx + 12, cy + 22 + sf * 20)
            }
            g.color = Color.BLACK
            // the markers' key, four bands to a line
            val keys = bands.withIndex().map { (i, b) -> "band $b ${listOf("dot", "square", "ring", "box")[i % 4]}" }.chunked(4)
            for ((r, row) in keys.withIndex()) g.drawString((if (r == 0) "markers: " else "             ") + row.joinToString("  "), cx + 12, cy + 22 + (3 + r) * 20)
            // the thickness two ways (Z over the floor, and the filament per mm): the largest disagreement
            val worst = all.maxOf { Math.abs(it.h - it.hE) }
            g.drawString("thickness by Z vs by filament per mm: at most %.4f mm apart".format(worst), cx + 12, cy + 22 + (3 + keys.size) * 20)
            for (sf in 0 until 3) for (b in bands) {
                val mine = all.filter { it.surface == sf && it.band == b }.sortedBy { it.k }
                if (mine.isEmpty()) continue
                stripSummary += "band $b ${surfaceNames[sf]} (floor z %.2f, %d C, fan %.0f %%): ".format(floorZ[b] ?: Double.NaN, mine.first().temp, mine.first().fan) +
                    mine.joinToString("  ") { "z %.2f h %.3f (by E %.3f) %.2f".format(it.z, it.h, it.hE, it.flow) } + " mm3/s; %.2f mm/s; lines %s x %s mm".format(mine.first().v,
                        mine.map { it.lines }.distinct().joinToString("/"), mine.map { "%.2f".format(it.len) }.distinct().joinToString("/"))
            }
            stripSummary += "thickness by Z vs by filament per mm: at most %.4f mm apart".format(worst)
        }

        // ---- D: what the bands were set to, from the g-code
        run {
            val dx = 1290; var dy = 640           // right of B's temperature labels (x 1180 to about 1230)
            g.color = Color.BLACK; g.drawString("D  per layer, as the g-code sets them (runs of identical layers merged):", dx - 40, dy - 10)
            // the melt strips count in the layer they print on, the one above their step's floor (else a layer of strips alone,
            // the last step's, would be missing here)
            val step = layered.map { it.z }.distinct().sorted().zipWithNext { a, b -> b - a }.minOrNull() ?: 0.2
            val stripLayers = strips.map { m -> Math.round((floorZ.getValue(stripRx.matchEntire(m.cell)!!.groupValues[1].toInt()) + step) * 1000) / 1000.0 to m }
            val rows = (layered.map { it.z to it } + stripLayers).groupBy({ it.first }, { it.second }).toSortedMap().map { (z, ms) -> Triple(z, ms.first().temp, ms.first().fan) }
            var i = 0
            while (i < rows.size) {
                var j = i; while (j + 1 < rows.size && rows[j + 1].second == rows[i].second && Math.abs(rows[j + 1].third - rows[i].third) < 0.5) j++
                g.drawString("z %.2f-%.2f mm   %d C   fan %.0f %%   (%d layers)".format(rows[i].first - 0.2, rows[j].first, rows[i].second, rows[i].third, j - i + 1), dx, dy + 18)
                dy += 21; i = j + 1
            }
            // every temperature command after the plate's first extrusion, with the Z extruded at before and after it
            dy += 8
            g.drawString("temperature commands after the first extrusion (M109 waits, M104 does not):", dx - 40, dy + 18); dy += 21
            for (c in tempCmds) {
                val line = "${c.cmd} S${c.temp}: after z %.2f, before z %s".format(c.zBefore, if (c.zAfter.isNaN()) "- (the end)" else "%.2f".format(c.zAfter))
                g.drawString(line, dx, dy + 18); dy += 19
                log("  $line")
            }
            if (tileMoves.isNotEmpty()) {
                dy += 12
                g.drawString("first-layer tiles (not layers; within the first): Z ${tileMoves.map { it.z }.distinct().sorted().joinToString { "%.2f".format(it) }} mm,", dx, dy + 18)
                g.drawString("  %d C, fan %.0f %%, speeds %s mm/s".format(tileMoves.first().temp, tileMoves.first().fan, tileMoves.map { it.v }.distinct().sorted().joinToString { "%.0f".format(it) }), dx, dy + 39)
            }
        }
        g.dispose()
        ImageIO.write(img, "png", File(outPath))
        log("wrote $outPath: ${moves.size} extrusion moves, layer for A at z $layerZ")
        for (l in stripSummary) log("  $l")
    }
}
