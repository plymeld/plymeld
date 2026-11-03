package plymeld.check

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Draws layers of two g-code files side by side with the SAME code (so differences are the
 * slicers', not the renderer's): extrusion colored by feature at true line width, travel
 * as thin gray lines. One row per requested layer.
 */
object GcodeRender {
    /** Each feature's color (`; FEATURE:` / `;TYPE:` names), shared with [TechniqueRender]. */
    internal val colors = mapOf(
        "Outer wall" to Color(0xE0, 0x8A, 0x1E), "Inner wall" to Color(0xF2, 0xC1, 0x4E),
        "Sparse infill" to Color(0x8E, 0x3B, 0x46), "Internal solid infill" to Color(0xB0, 0x4F, 0xB5),
        "Top surface" to Color(0xE0, 0x3E, 0x3E), "Bottom surface" to Color(0x4F, 0x86, 0xC6),
        "Bridge" to Color(0x3E, 0xA8, 0x9F), "Internal Bridge" to Color(0x2B, 0x7A, 0x73), "Floating vertical shell" to Color(0x9B, 0x6B, 0xD8),
        "Brim" to Color(0x55, 0x55, 0x55),
        "Support" to Color(0x2E, 0x8B, 0x57), "Support interface" to Color(0x7F, 0xD1, 0x9B), "Support transition" to Color(0x9A, 0xC9, 0xAA),
        "Ironing" to Color(0x1F, 0x5F, 0xD0), "Overhang wall" to Color(0x00, 0xB4, 0xD8),
    )

    /** Same as [sideBySide] but each row is the layer whose Z is nearest each requested height, per file. */
    fun sideBySideAtZ(left: GcodeStats, leftName: String, right: GcodeStats, rightName: String, zs: List<Double>, out: Path, px: Int = 520) {
        fun pick(g: GcodeStats, z: Double) = g.layers.minByOrNull { Math.abs(it.z - z) }!!.index
        val l = GcodeStats(zs.map { left.layers[pick(left, it)] }.mapIndexed { i, x -> x }, left.totalExtrudedMm, left.totalExtrudeMoveMm, left.totalTravelMm, left.featureLengthMm, left.headerTotalLayers, left.headerFilamentUsedMm)
        val r = GcodeStats(zs.map { right.layers[pick(right, it)] }, right.totalExtrudedMm, right.totalExtrudeMoveMm, right.totalTravelMm, right.featureLengthMm, right.headerTotalLayers, right.headerFilamentUsedMm)
        sideBySide(l, leftName, r, rightName, zs.indices.toList(), out, px)
    }

    fun sideBySide(left: GcodeStats, leftName: String, right: GcodeStats, rightName: String, layers: List<Int>, out: Path, px: Int = 520) {
        // common view box from both files' extrusion on the chosen layers
        var minX = Double.MAX_VALUE; var minY = Double.MAX_VALUE; var maxX = -Double.MAX_VALUE; var maxY = -Double.MAX_VALUE
        for (g in listOf(left, right)) for (li in layers) {
            val l = g.layers.getOrNull(li) ?: continue
            for (k in 0 until l.segCount) {
                if (l.featureOfSeg[k] == "Brim" || l.featureOfSeg[k] == "Custom" || l.featureOfSeg[k] == "None") continue
                for (c in 0 until 2) { val x = l.segs[k * 5 + 2 * c]; val y = l.segs[k * 5 + 2 * c + 1]; minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y) }
            }
        }
        val pad = 0.05 * maxOf(maxX - minX, maxY - minY)
        minX -= pad; minY -= pad; maxX += pad; maxY += pad
        val scale = px / maxOf(maxX - minX, maxY - minY)
        val header = 26
        val img = BufferedImage(2 * px, layers.size * (px + header) + 24, BufferedImage.TYPE_INT_RGB)
        val g2 = img.createGraphics()
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = Color(0xF3, 0xF3, 0xF3); g2.fillRect(0, 0, img.width, img.height)
        g2.font = Font(Font.SANS_SERIF, Font.BOLD, 14)
        for ((row, li) in layers.withIndex()) {
            for ((col, pair) in listOf(left to leftName, right to rightName).withIndex()) {
                val (g, name) = pair
                val ox = col * px; val oy = row * (px + header) + header
                val l = g.layers.getOrNull(li)
                g2.color = Color.BLACK
                g2.drawString("$name — layer $li z=${l?.z ?: "-"}  travel %.0f mm".format(l?.travelMm ?: 0.0), ox + 8, oy - 8)
                if (l == null) continue
                fun tx(x: Double) = ox + (x - minX) * scale
                fun ty(y: Double) = oy + (maxY - y) * scale
                g2.stroke = BasicStroke(0.6f); g2.color = Color(0x99, 0x99, 0x99)
                for (k in 0 until l.travels.size / 4) g2.draw(Line2D.Double(tx(l.travels[4 * k]), ty(l.travels[4 * k + 1]), tx(l.travels[4 * k + 2]), ty(l.travels[4 * k + 3])))
                g2.stroke = BasicStroke(maxOf(1f, (0.42 * scale).toFloat()), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                for (k in 0 until l.segCount) {
                    g2.color = colors[l.featureOfSeg[k]] ?: Color.DARK_GRAY
                    g2.draw(Line2D.Double(tx(l.segs[k * 5]), ty(l.segs[k * 5 + 1]), tx(l.segs[k * 5 + 2]), ty(l.segs[k * 5 + 3])))
                }
            }
        }
        // legend
        g2.font = Font(Font.SANS_SERIF, Font.PLAIN, 12)
        var lx = 8
        for ((f, c) in colors) { g2.color = c; g2.fillRect(lx, img.height - 16, 12, 12); g2.color = Color.BLACK; g2.drawString(f, lx + 16, img.height - 6); lx += 18 + g2.fontMetrics.stringWidth(f) + 10 }
        g2.dispose()
        out.parent?.let { Files.createDirectories(it) }
        ImageIO.write(img, "png", out.toFile())
    }
}
