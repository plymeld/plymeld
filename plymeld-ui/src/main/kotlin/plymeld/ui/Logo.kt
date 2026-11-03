package plymeld.ui

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.painter.Painter
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.RRect
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The plymeld mark: six lines with round ends on a 100-unit square, a leaning P read through their ends, laid like the
 * layers of a print: the bowl in the top four lines, the stem in the last two. This file is the one source of it: the window draws it (the bar's mark,
 * the window icon), and `plymeld-ui --icons DIR` writes it out for everything outside the window: the SVG (the
 * README, the docs), PNGs at the sizes desktop entries and installers want, and a Windows .ico.
 *
 * Two forms. The MARK alone, in the text's ink, where it sits on a surface of the window. The ICON for a taskbar, a
 * dock, or a file manager: the mark in a light ink on a rounded tile of the dark ink, so it reads on a light and a
 * dark background alike.
 */
object Logo {
    /** A line's center height and its two ends on the 100-unit square. */
    data class Bar(val y: Float, val x0: Float, val x1: Float)
    val bars = listOf(Bar(14f, 34f, 76f), Bar(28f, 30f, 86f), Bar(42f, 26f, 82f), Bar(56f, 22f, 66f), Bar(70f, 18f, 34f), Bar(84f, 14f, 30f))
    const val STROKE = 9f
    /** The icon's inks: the tile and the mark on it. */
    val TILE = Color(0xFF1C2127); val ON_TILE = Color(0xFFF3F4F6)
    private const val TILE_RADIUS = 22f   // of the 100-unit tile
    private const val TILE_INSET = 12f    // the mark drawn in the tile's middle 76 units

    /** The mark in [color] filling this scope's square (the smaller dimension), the lines scaled with it. */
    fun DrawScope.drawMark(color: Color, inset: Float = 0f) {
        val k = size.minDimension / 100f; val s = 1f - 2 * inset / 100f
        for (b in bars) drawLine(color, Offset((inset + b.x0 * s) * k, (inset + b.y * s) * k), Offset((inset + b.x1 * s) * k, (inset + b.y * s) * k), strokeWidth = STROKE * s * k, cap = StrokeCap.Round)
    }

    /** The icon: the mark on its tile. */
    fun DrawScope.drawIcon() {
        val k = size.minDimension / 100f
        drawRoundRect(TILE, Offset.Zero, Size(100f * k, 100f * k), CornerRadius(TILE_RADIUS * k))
        drawMark(ON_TILE, TILE_INSET)
    }

    /** The icon as a painter, for `Window(icon = ...)`: Compose rasterizes it at the sizes the platform asks for. */
    class IconPainter : Painter() {
        override val intrinsicSize = Size(256f, 256f)
        override fun DrawScope.onDraw() = drawIcon()
    }

    /** The mark as SVG, in `currentColor` so a page's text color carries it. */
    fun svg(): String = buildString {
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100\" height=\"100\">")
        for (b in bars) append("<path d=\"M ${b.x0.n()} ${b.y.n()} H ${b.x1.n()}\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"${STROKE.n()}\" stroke-linecap=\"round\"/>")
        append("</svg>\n")
    }

    /** The icon as SVG (the tile and the mark), for pages that want it as a file. */
    fun iconSvg(): String = buildString {
        val s = 1f - 2 * TILE_INSET / 100f
        append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 100 100\" width=\"100\" height=\"100\">")
        append("<rect width=\"100\" height=\"100\" rx=\"${TILE_RADIUS.n()}\" fill=\"${TILE.hex()}\"/>")
        for (b in bars) append("<path d=\"M ${(TILE_INSET + b.x0 * s).n()} ${(TILE_INSET + b.y * s).n()} H ${(TILE_INSET + b.x1 * s).n()}\" fill=\"none\" stroke=\"${ON_TILE.hex()}\" stroke-width=\"${(STROKE * s).n()}\" stroke-linecap=\"round\"/>")
        append("</svg>\n")
    }

    /** The icon rastered at [size] px, straight (unpremultiplied) BGRA, rows top to bottom. */
    private fun raster(size: Int): ByteArray {
        val bmp = Bitmap().also { it.allocPixels(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.PREMUL)) }
        val c = Canvas(bmp); val k = size / 100f
        c.drawRRect(RRect.makeXYWH(0f, 0f, 100f * k, 100f * k, TILE_RADIUS * k), Paint().also { it.color = TILE.argb(); it.isAntiAlias = true })
        val s = 1f - 2 * TILE_INSET / 100f
        val p = Paint().also { it.color = ON_TILE.argb(); it.isAntiAlias = true; it.mode = PaintMode.STROKE; it.strokeWidth = STROKE * s * k; it.strokeCap = PaintStrokeCap.ROUND }
        for (b in bars) c.drawLine((TILE_INSET + b.x0 * s) * k, (TILE_INSET + b.y * s) * k, (TILE_INSET + b.x1 * s) * k, (TILE_INSET + b.y * s) * k, p)
        return bmp.readPixels(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), size * 4, 0, 0) ?: error("logo: no pixels at $size px")
    }

    /** The icon as a PNG at [size] px. */
    fun png(size: Int): ByteArray {
        val px = raster(size)
        val img = Image.makeRaster(ImageInfo(size, size, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL), px, size * 4)
        return img.encodeToData(EncodedImageFormat.PNG)?.bytes ?: error("logo: PNG encoding failed at $size px")
    }

    /**
     * The icon as a Windows .ico holding [sizes], every one a 32-bit DIB with an empty AND mask: what every reader
     * handles (System.Drawing answered 128 px for the 256 px entry when that one was a PNG; a 256 px DIB is 256 KB,
     * which is fine).
     */
    fun ico(sizes: List<Int> = ICO_SIZES): ByteArray {
        val entries = sizes.map { n -> n to dib(n) }
        val out = ByteArrayOutputStream()
        fun le(v: Int, bytes: Int) { for (i in 0 until bytes) out.write((v shr (8 * i)) and 0xFF) }
        le(0, 2); le(1, 2); le(entries.size, 2)
        var offset = 6 + 16 * entries.size
        for ((n, bytes) in entries) {
            out.write(if (n >= 256) 0 else n); out.write(if (n >= 256) 0 else n); out.write(0); out.write(0)
            le(1, 2); le(32, 2); le(bytes.size, 4); le(offset, 4)
            offset += bytes.size
        }
        for ((_, bytes) in entries) out.write(bytes)
        return out.toByteArray()
    }
    val ICO_SIZES = listOf(16, 24, 32, 48, 64, 128, 256)
    val PNG_SIZES = listOf(16, 24, 32, 48, 64, 128, 256, 512)

    /** A 32-bit BGRA DIB of the icon at [n] px: the header, the rows bottom-up, then the 1-bit AND mask (all clear). */
    private fun dib(n: Int): ByteArray {
        val px = raster(n)
        val maskRow = ((n + 31) / 32) * 4
        val b = ByteBuffer.allocate(40 + n * n * 4 + maskRow * n).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(40); b.putInt(n); b.putInt(2 * n); b.putShort(1); b.putShort(32); b.putInt(0); b.putInt(n * n * 4 + maskRow * n)
        b.putInt(0); b.putInt(0); b.putInt(0); b.putInt(0)
        for (row in n - 1 downTo 0) b.put(px, row * n * 4, n * 4)
        return b.array()   // the mask bytes stay zero: every pixel drawn, the alpha channel says how much
    }

    /** Writes the SVGs, the PNGs, and the .ico into [dir] (`plymeld-ui --icons DIR`; `./gradlew :plymeld-ui:icons`). */
    fun writeIcons(dir: Path): List<Path> {
        Files.createDirectories(dir)
        val written = mutableListOf<Path>()
        fun put(name: String, bytes: ByteArray) { val p: Path = dir.resolve(name); Files.write(p, bytes); written.add(p) }
        put("plymeld-mark.svg", svg().toByteArray()); put("plymeld.svg", iconSvg().toByteArray())
        for (n in PNG_SIZES) put("plymeld-$n.png", png(n))
        put("plymeld.ico", ico())
        return written
    }

    private fun Float.n(): String = if (this == toInt().toFloat()) toInt().toString() else String.format(java.util.Locale.ROOT, "%.2f", this).trimEnd('0').trimEnd('.')
    private fun Color.argb(): Int = ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
    private fun Color.hex(): String = String.format(java.util.Locale.ROOT, "#%06X", argb() and 0xFFFFFF)
}
