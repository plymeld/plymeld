package plymeld.core.check

import plymeld.geom.Polygon
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage

/**
 * A picture of one layer ModelCheck found a problem on, around the problem (24 x 24 mm): the model's
 * cross-section outline (black), its walls (red) and other lines (gray) at their widths, the outline with
 * no wall near it (blue), lines outside the model (magenta). Java 2D only, headless.
 */
object ModelCheckPlot {
    private const val SPAN_MM = 24.0
    private const val SIZE = 960

    /** [lines]: x0, y0, x1, y1, width, wall (1/0). [cx], [cy]: the center of the view (mm). */
    fun write(file: java.nio.file.Path, title: String, loops: List<Polygon>, lines: List<DoubleArray>,
              outside: List<DoubleArray>, uncovered: List<DoubleArray>, cx: Double, cy: Double) {
        System.setProperty("java.awt.headless", "true")
        java.nio.file.Files.createDirectories(file.parent)
        val img = BufferedImage(SIZE, SIZE + 60, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(250, 250, 252); g.fillRect(0, 0, img.width, img.height)
        val s = SIZE / SPAN_MM
        fun px(x: Double) = (x - (cx - SPAN_MM / 2)) * s
        fun py(y: Double) = 60 + (cy + SPAN_MM / 2 - y) * s
        // the cross-section, filled light
        val area = Path2D.Double(Path2D.WIND_EVEN_ODD)
        for (p in loops) { for (i in 0 until p.size) { val x = px(p.x(i) / 1000.0); val y = py(p.y(i) / 1000.0); if (i == 0) area.moveTo(x, y) else area.lineTo(x, y) }; area.closePath() }
        g.color = Color(228, 232, 240); g.fill(area)
        // printed lines at their widths
        for (l in lines.sortedBy { it[5] }) {
            g.color = if (l[5] > 0.5) Color(210, 60, 50, 200) else Color(140, 140, 150, 160)
            g.stroke = BasicStroke((l[4] * s).toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(Line2D.Double(px(l[0]), py(l[1]), px(l[2]), py(l[3])))
        }
        g.color = Color.BLACK; g.stroke = BasicStroke(1.5f); g.draw(area)
        g.color = Color(30, 90, 230); for (p in uncovered) g.fillOval((px(p[0]) - 5).toInt(), (py(p[1]) - 5).toInt(), 10, 10)
        g.color = Color(220, 0, 200); for (p in outside) g.fillOval((px(p[0]) - 4).toInt(), (py(p[1]) - 4).toInt(), 8, 8)
        // scale bar and title
        g.color = Color.BLACK; g.stroke = BasicStroke(3f)
        g.drawLine(20, SIZE + 40, (20 + 5 * s).toInt(), SIZE + 40)
        g.font = Font("SansSerif", Font.PLAIN, 15); g.drawString("5 mm", 20, SIZE + 32)
        g.font = Font("SansSerif", Font.BOLD, 15); g.drawString(title.take(140), 10, 22)
        g.font = Font("SansSerif", Font.PLAIN, 13)
        g.drawString("black: the model's cross-section (mesh slicer)   red: walls   gray: other lines   blue: outline with no wall within a line   magenta: lines outside the model", 10, 44)
        g.dispose()
        javax.imageio.ImageIO.write(img, "png", file.toFile())
    }
}
