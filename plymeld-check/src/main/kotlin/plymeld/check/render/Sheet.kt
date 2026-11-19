package plymeld.check.render

import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage

/**
 * `--sheet`: every picture of a run in one image, in a grid as near square as the count allows, in the run's order (left
 * to right, then down), each under its title. Each picture keeps its own caption, scale bar, and key. A sheet larger than
 * [MAX_SIDE] pixels on a side is drawn smaller, by one factor for all, and says so in its title.
 */
internal object Sheet {
    /** The largest sheet, pixels on a side (plymeld's choice). */
    const val MAX_SIDE = 16000
    private const val GAP = 16
    private const val TITLE = 22

    fun compose(pictures: List<Pair<String, ByteArray>>, format: Format, fileName: String): ByteArray {
        require(pictures.isNotEmpty()) { "--sheet: the run draws no picture" }
        val sizes = pictures.map { (_, b) -> if (format == Format.PNG) javax.imageio.ImageIO.createImageInputStream(b.inputStream()).use { s ->
            val r = javax.imageio.ImageIO.getImageReaders(s).next(); r.input = s; (r.getWidth(0) to r.getHeight(0)).also { r.dispose() } }
            else svgSize(String(b, Charsets.UTF_8)) }
        val cols = Math.ceil(Math.sqrt(pictures.size.toDouble())).toInt()
        val rows = (pictures.size + cols - 1) / cols
        val colW = IntArray(cols); val rowH = IntArray(rows)
        for ((k, wh) in sizes.withIndex()) { colW[k % cols] = maxOf(colW[k % cols], wh.first); rowH[k / cols] = maxOf(rowH[k / cols], wh.second + TITLE) }
        val head = 30
        val fullW = colW.sum() + (cols + 1) * GAP; val fullH = head + rowH.sum() + (rows + 1) * GAP
        val k = minOf(1.0, MAX_SIDE.toDouble() / maxOf(fullW, fullH))
        val w = Math.ceil(fullW * k).toInt(); val h = Math.ceil(fullH * k).toInt()
        val title = "$fileName: ${pictures.size} picture(s)" + (if (k < 1.0) ", drawn at ${Render.f(k * 100, 0)} % of their size" else "")
        fun cell(i: Int): Pair<Double, Double> {
            val c = i % cols; val r = i / cols
            return (GAP + (0 until c).sumOf { colW[it] + GAP }).toDouble() to (head + GAP + (0 until r).sumOf { rowH[it] + GAP }).toDouble()
        }
        if (format == Format.PNG) {
            val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
            val g = img.createGraphics()
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.color = Color.WHITE; g.fillRect(0, 0, w, h)
            g.scale(k, k)
            g.color = Color.BLACK; g.font = Canvas.font(16, true); g.drawString(title, GAP, 22)
            for ((i, p) in pictures.withIndex()) {
                val (x, y) = cell(i)
                g.color = Color.BLACK; g.font = Canvas.font(14, true); g.drawString(p.first, x.toFloat(), (y + 15).toFloat())
                val im = javax.imageio.ImageIO.read(p.second.inputStream())
                g.drawImage(im, x.toInt(), (y + TITLE).toInt(), null)
            }
            g.dispose()
            return java.io.ByteArrayOutputStream().also { javax.imageio.ImageIO.write(img, "png", it) }.toByteArray()
        }
        val b = StringBuilder()
        b.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"$w\" height=\"$h\" viewBox=\"0 0 $fullW $fullH\">\n")
        b.append("<rect x=\"0\" y=\"0\" width=\"$fullW\" height=\"$fullH\" fill=\"#ffffff\"/>\n")
        b.append("<text x=\"$GAP\" y=\"22\" font-family=\"sans-serif\" font-size=\"16\" font-weight=\"bold\">${esc(title)}</text>\n")
        for ((i, p) in pictures.withIndex()) {
            val (x, y) = cell(i)
            b.append("<text x=\"${x.toInt()}\" y=\"${(y + 15).toInt()}\" font-family=\"sans-serif\" font-size=\"14\" font-weight=\"bold\">${esc(p.first)}</text>\n")
            val inner = String(p.second, Charsets.UTF_8).substringAfter("?>\n").replaceFirst("<svg ", "<svg x=\"${x.toInt()}\" y=\"${(y + TITLE).toInt()}\" ")
            b.append(inner)
        }
        b.append("</svg>\n")
        return b.toString().toByteArray(Charsets.UTF_8)
    }

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    private fun svgSize(s: String): Pair<Int, Int> {
        val m = Regex("<svg [^>]*width=\"(\\d+)\" height=\"(\\d+)\"").find(s) ?: error("not a picture this run wrote")
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }
}
