package plymeld.check.render

import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.util.Locale

/**
 * What a picture is drawn on, in pixels from its top-left: a PNG image ([Raster]) or an SVG document ([Svg]). The same
 * drawing code writes both, so the two formats show the same picture.
 */
internal abstract class Canvas(val width: Int, val height: Int) {
    abstract fun rect(x: Double, y: Double, w: Double, h: Double, fill: Color)
    abstract fun rectOutline(x: Double, y: Double, w: Double, h: Double, stroke: Color, lw: Double)
    abstract fun line(x0: Double, y0: Double, x1: Double, y1: Double, lw: Double, color: Color, round: Boolean = true)
    abstract fun polygon(xs: DoubleArray, ys: DoubleArray, fill: Color?, stroke: Color? = null, lw: Double = 1.0)
    /** An open line through the points, round at its joins and ends. */
    abstract fun polyline(xs: DoubleArray, ys: DoubleArray, lw: Double, color: Color)
    abstract fun circle(cx: Double, cy: Double, r: Double, fill: Color?, stroke: Color? = null, lw: Double = 1.0)
    abstract fun text(s: String, x: Double, y: Double, size: Int, bold: Boolean = false, color: Color = Color.BLACK)
    /** An ARGB raster [w] by [h] placed at (x, y); its transparent pixels show what is under it. */
    abstract fun image(argb: IntArray, w: Int, h: Int, x: Int, y: Int)
    abstract fun clip(x: Double, y: Double, w: Double, h: Double)
    abstract fun unclip()

    companion object {
        private val probe: java.awt.Graphics2D by lazy { BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics().also {
            it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON) } }
        fun font(size: Int, bold: Boolean) = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, size)
        /** The width of [s] in pixels, as both formats lay it out. */
        @Synchronized fun textWidth(s: String, size: Int, bold: Boolean = false): Int = probe.getFontMetrics(font(size, bold)).stringWidth(s)

        /** [s] broken into lines no wider than [maxW] pixels, at word boundaries. */
        fun wrap(s: String, maxW: Int, size: Int, bold: Boolean = false): List<String> {
            val out = ArrayList<String>()
            var cur = StringBuilder()
            for (word in s.split(' ')) {
                val cand = if (cur.isEmpty()) word else "$cur $word"
                if (textWidth(cand, size, bold) <= maxW || cur.isEmpty()) { cur = StringBuilder(cand) }
                else { out += cur.toString(); cur = StringBuilder(word) }
            }
            if (cur.isNotEmpty()) out += cur.toString()
            return out
        }
    }
}

internal class Raster(width: Int, height: Int) : Canvas(width, height) {
    val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
    private val g = img.createGraphics().also {
        it.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        it.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        it.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
    }
    override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Color) { g.color = fill; g.fill(java.awt.geom.Rectangle2D.Double(x, y, w, h)) }
    override fun rectOutline(x: Double, y: Double, w: Double, h: Double, stroke: Color, lw: Double) { g.color = stroke; g.stroke = BasicStroke(lw.toFloat()); g.draw(java.awt.geom.Rectangle2D.Double(x, y, w, h)) }
    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, lw: Double, color: Color, round: Boolean) {
        g.color = color
        g.stroke = BasicStroke(lw.toFloat(), if (round) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Double(x0, y0, x1, y1))
    }
    override fun polygon(xs: DoubleArray, ys: DoubleArray, fill: Color?, stroke: Color?, lw: Double) {
        val p = Path2D.Double(); p.moveTo(xs[0], ys[0]); for (i in 1 until xs.size) p.lineTo(xs[i], ys[i]); p.closePath()
        if (fill != null) { g.color = fill; g.fill(p) }
        if (stroke != null) { g.color = stroke; g.stroke = BasicStroke(lw.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p) }
    }
    override fun polyline(xs: DoubleArray, ys: DoubleArray, lw: Double, color: Color) {
        val p = Path2D.Double(); p.moveTo(xs[0], ys[0]); for (i in 1 until xs.size) p.lineTo(xs[i], ys[i])
        g.color = color; g.stroke = BasicStroke(lw.toFloat(), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g.draw(p)
    }
    override fun circle(cx: Double, cy: Double, r: Double, fill: Color?, stroke: Color?, lw: Double) {
        val e = Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r)
        if (fill != null) { g.color = fill; g.fill(e) }
        if (stroke != null) { g.color = stroke; g.stroke = BasicStroke(lw.toFloat()); g.draw(e) }
    }
    override fun text(s: String, x: Double, y: Double, size: Int, bold: Boolean, color: Color) { g.color = color; g.font = font(size, bold); g.drawString(s, x.toFloat(), y.toFloat()) }
    override fun image(argb: IntArray, w: Int, h: Int, x: Int, y: Int) {
        // the pixels wrapped, not copied: a whole-print picture's buffer is tens of megabytes
        val db = java.awt.image.DataBufferInt(argb, argb.size)
        val r = java.awt.image.Raster.createPackedRaster(db, w, h, w, intArrayOf(0xFF0000, 0xFF00, 0xFF, 0xFF000000.toInt()), null)
        g.drawImage(BufferedImage(java.awt.image.ColorModel.getRGBdefault(), r, false, null), x, y, null)
    }
    override fun clip(x: Double, y: Double, w: Double, h: Double) { g.clip = java.awt.geom.Rectangle2D.Double(x, y, w, h) }
    override fun unclip() { g.clip = null }
    fun png(): ByteArray { g.dispose(); val b = java.io.ByteArrayOutputStream(); javax.imageio.ImageIO.write(img, "png", b); return b.toByteArray() }
}

internal class Svg(width: Int, height: Int) : Canvas(width, height) {
    private val b = StringBuilder()
    private var clipId = 0
    private var clipOpen = false
    private fun n(v: Double) = String.format(Locale.ROOT, "%.2f", v).trimEnd('0').trimEnd('.').let { if (it == "-0") "0" else it }
    private fun c(col: Color) = String.format(Locale.ROOT, "#%02x%02x%02x", col.red, col.green, col.blue)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    override fun rect(x: Double, y: Double, w: Double, h: Double, fill: Color) { b.append("<rect x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\" fill=\"${c(fill)}\"/>\n") }
    override fun rectOutline(x: Double, y: Double, w: Double, h: Double, stroke: Color, lw: Double) { b.append("<rect x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\" fill=\"none\" stroke=\"${c(stroke)}\" stroke-width=\"${n(lw)}\"/>\n") }
    override fun line(x0: Double, y0: Double, x1: Double, y1: Double, lw: Double, color: Color, round: Boolean) {
        b.append("<line x1=\"${n(x0)}\" y1=\"${n(y0)}\" x2=\"${n(x1)}\" y2=\"${n(y1)}\" stroke=\"${c(color)}\" stroke-width=\"${n(lw)}\"${if (round) " stroke-linecap=\"round\"" else ""}/>\n")
    }
    override fun polygon(xs: DoubleArray, ys: DoubleArray, fill: Color?, stroke: Color?, lw: Double) {
        b.append("<polygon points=\"").append(xs.indices.joinToString(" ") { "${n(xs[it])},${n(ys[it])}" }).append("\" fill=\"${fill?.let(::c) ?: "none"}\"")
        if (stroke != null) b.append(" stroke=\"${c(stroke)}\" stroke-width=\"${n(lw)}\" stroke-linejoin=\"round\"")
        b.append("/>\n")
    }
    override fun polyline(xs: DoubleArray, ys: DoubleArray, lw: Double, color: Color) {
        b.append("<polyline points=\"").append(xs.indices.joinToString(" ") { "${n(xs[it])},${n(ys[it])}" })
            .append("\" fill=\"none\" stroke=\"${c(color)}\" stroke-width=\"${n(lw)}\" stroke-linecap=\"round\" stroke-linejoin=\"round\"/>\n")
    }
    override fun circle(cx: Double, cy: Double, r: Double, fill: Color?, stroke: Color?, lw: Double) {
        b.append("<circle cx=\"${n(cx)}\" cy=\"${n(cy)}\" r=\"${n(r)}\" fill=\"${fill?.let(::c) ?: "none"}\"")
        if (stroke != null) b.append(" stroke=\"${c(stroke)}\" stroke-width=\"${n(lw)}\"")
        b.append("/>\n")
    }
    override fun text(s: String, x: Double, y: Double, size: Int, bold: Boolean, color: Color) {
        b.append("<text x=\"${n(x)}\" y=\"${n(y)}\" font-family=\"sans-serif\" font-size=\"$size\"${if (bold) " font-weight=\"bold\"" else ""} fill=\"${c(color)}\">${esc(s)}</text>\n")
    }
    /** A raster as runs of one color a row, each run a rectangle (one path per color), so the SVG stays vector. */
    override fun image(argb: IntArray, w: Int, h: Int, x: Int, y: Int) {
        val byColor = java.util.TreeMap<Int, StringBuilder>()
        for (r in 0 until h) {
            var i = 0
            while (i < w) {
                val p = argb[r * w + i]
                if ((p ushr 24) == 0) { i++; continue }
                var j = i + 1
                while (j < w && argb[r * w + j] == p) j++
                byColor.getOrPut(p and 0xFFFFFF) { StringBuilder() }.append("M${x + i} ${y + r}h${j - i}v1h-${j - i}z")
                i = j
            }
        }
        for ((rgb, d) in byColor) b.append("<path d=\"$d\" fill=\"${c(Color(rgb))}\"/>\n")
    }
    override fun clip(x: Double, y: Double, w: Double, h: Double) {
        unclip()
        val id = "c${clipId++}"
        b.append("<clipPath id=\"$id\"><rect x=\"${n(x)}\" y=\"${n(y)}\" width=\"${n(w)}\" height=\"${n(h)}\"/></clipPath>\n<g clip-path=\"url(#$id)\">\n")
        clipOpen = true
    }
    override fun unclip() { if (clipOpen) { b.append("</g>\n"); clipOpen = false } }
    /** The body (no XML header), for a sheet to place. */
    fun body(): String { unclip(); return b.toString() }
    fun document(): String = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$width\" height=\"$height\" viewBox=\"0 0 $width $height\">\n" + body() + "</svg>\n"
}
