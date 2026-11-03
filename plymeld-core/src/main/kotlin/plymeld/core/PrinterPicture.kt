package plymeld.core

import plymeld.mesh.TriangleSoup
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * The preview picture a printer's screen shows for a g-code file (hardware.machine.<key>.gcode.pictures): the plate's
 * parts in their printed pose, drawn by a small software renderer (orthographic view, depth buffer, flat Lambert shading
 * in each part's filament color, transparent background, the parts scaled to fill the picture), encoded as a PNG and
 * written into the file as a base64 comment block.
 *
 * The block's layout is the one observed in Flash Studio 1.7.18's output for the Creator 5 (its g-code):
 * `; THUMBNAIL_BLOCK_START`, a blank line, `;`, `; thumbnail begin <w>x<h> <length>` (length: the base64 text's
 * characters), the base64 in lines of 78 characters each after `; `, `; thumbnail end`, `; THUMBNAIL_BLOCK_END`.
 * Several pictures in one block, one `thumbnail begin` ... `end` each, is plymeld's choice (only one was observed).
 *
 * Plain JDK only: the PNG is written here (zlib from java.util.zip), no image library.
 */
object PrinterPicture {
    /** One mesh to draw, in its printed pose (mm), and its color as 0xRRGGBB. */
    class Part(val soup: TriangleSoup, val rgb: Int)

    /** A finished picture: its size in pixels and its PNG bytes. */
    class Picture(val width: Int, val height: Int, val png: ByteArray)

    /** An image as rows of 0xAARRGGBB pixels, top row first. */
    class Image(val width: Int, val height: Int, val argb: IntArray)

    // ---- plymeld's choice, none of them a printer's figure: how the picture looks
    /** The view: from the front-left, this far round from straight-on the front (degrees)... */
    const val AZIMUTH_DEG = 45.0
    /** ...and this far above the bed (degrees). */
    const val ELEVATION_DEG = 30.0
    /** A part whose filament color is not known: a light gray. */
    const val NEUTRAL_RGB = 0xC8C8C8
    /** Empty border round the parts: this share of the picture's smaller side. */
    const val MARGIN_SHARE = 0.05
    /** Samples per pixel along each axis (2 = 4 per pixel), for smooth outlines. */
    const val SUPERSAMPLE = 2
    /** Light on a face turned away from the light (ambient) and the share the light adds at most (Lambert). */
    const val AMBIENT = 0.35
    const val DIFFUSE = 0.65

    /** The base64 text's line length in the block, as observed in Flash Studio 1.7.18's output. */
    const val LINE = 78

    /**
     * [parts] drawn into a [width] x [height] image. Parts with no triangles are left out; none at all gives a fully
     * transparent image.
     */
    fun render(parts: List<Part>, width: Int, height: Int): Image {
        require(width > 0 && height > 0) { "a picture needs a positive size, not ${width}x$height" }
        val ss = SUPERSAMPLE
        val bw = width * ss; val bh = height * ss
        val az = Math.toRadians(AZIMUTH_DEG); val el = Math.toRadians(ELEVATION_DEG)
        // forward (from the eye into the scene; the eye at -X, -Y, above), right = forward x Z, up = right x forward
        val fx = Math.cos(el) * Math.sin(az); val fy = Math.cos(el) * Math.cos(az); val fz = -Math.sin(el)
        val rl = Math.hypot(fy, fx); val rx = fy / rl; val ry = -fx / rl
        val ux = ry * fz; val uy = -rx * fz; val uz = rx * fy - ry * fx
        // the light: from behind the viewer's left shoulder, above (plymeld's choice)
        var lx = -fx - 0.4 * rx + 0.6 * ux; var ly = -fy - 0.4 * ry + 0.6 * uy; var lz = -fz + 0.6 * uz
        run { val l = Math.sqrt(lx * lx + ly * ly + lz * lz); lx /= l; ly /= l; lz /= l }

        // the parts' extent on screen
        var sx0 = Double.POSITIVE_INFINITY; var sx1 = Double.NEGATIVE_INFINITY
        var sy0 = Double.POSITIVE_INFINITY; var sy1 = Double.NEGATIVE_INFINITY
        for (p in parts) {
            val c = p.soup.coords; val n = p.soup.count * 9
            var i = 0
            while (i < n) {
                val x = c[i].toDouble(); val y = c[i + 1].toDouble(); val z = c[i + 2].toDouble()
                val sx = x * rx + y * ry; val sy = x * ux + y * uy + z * uz
                if (sx < sx0) sx0 = sx; if (sx > sx1) sx1 = sx
                if (sy < sy0) sy0 = sy; if (sy > sy1) sy1 = sy
                i += 3
            }
        }
        val out = IntArray(width * height)
        if (sx0 > sx1) return Image(width, height, out)
        val margin = MARGIN_SHARE * minOf(width, height) * ss
        val spanX = maxOf(sx1 - sx0, 1e-9); val spanY = maxOf(sy1 - sy0, 1e-9)
        val scale = minOf((bw - 2 * margin) / spanX, (bh - 2 * margin) / spanY)
        val offX = (bw - spanX * scale) / 2; val offY = (bh - spanY * scale) / 2

        val depth = FloatArray(bw * bh) { Float.POSITIVE_INFINITY }
        val color = IntArray(bw * bh)
        val px = DoubleArray(3); val py = DoubleArray(3); val pd = DoubleArray(3)
        for (p in parts) {
            val c = p.soup.coords; val n = p.soup.count
            val cr = (p.rgb shr 16) and 255; val cg = (p.rgb shr 8) and 255; val cb = p.rgb and 255
            for (t in 0 until n) {
                val b = t * 9
                for (k in 0 until 3) {
                    val x = c[b + 3 * k].toDouble(); val y = c[b + 3 * k + 1].toDouble(); val z = c[b + 3 * k + 2].toDouble()
                    px[k] = (x * rx + y * ry - sx0) * scale + offX
                    py[k] = bh - ((x * ux + y * uy + z * uz - sy0) * scale + offY)
                    pd[k] = x * fx + y * fy + z * fz
                }
                val area = (px[1] - px[0]) * (py[2] - py[0]) - (px[2] - px[0]) * (py[1] - py[0])
                if (Math.abs(area) < 1e-12) continue
                val xMin = maxOf(0, Math.ceil(minOf(px[0], px[1], px[2]) - 0.5).toInt())
                val xMax = minOf(bw - 1, Math.floor(maxOf(px[0], px[1], px[2]) - 0.5).toInt())
                val yMin = maxOf(0, Math.ceil(minOf(py[0], py[1], py[2]) - 0.5).toInt())
                val yMax = minOf(bh - 1, Math.floor(maxOf(py[0], py[1], py[2]) - 0.5).toInt())
                if (xMin > xMax || yMin > yMax) continue
                // flat shading: the face's normal, turned toward the viewer (a soup's winding is not trusted)
                val ax = c[b + 3] - c[b]; val ay = c[b + 4] - c[b + 1]; val azz = c[b + 5] - c[b + 2]
                val bx = c[b + 6] - c[b]; val by = c[b + 7] - c[b + 1]; val bz = c[b + 8] - c[b + 2]
                var nx = (ay * bz - azz * by).toDouble(); var ny = (azz * bx - ax * bz).toDouble(); var nz = (ax * by - ay * bx).toDouble()
                val nl = Math.sqrt(nx * nx + ny * ny + nz * nz)
                if (nl == 0.0) continue
                nx /= nl; ny /= nl; nz /= nl
                if (nx * fx + ny * fy + nz * fz > 0) { nx = -nx; ny = -ny; nz = -nz }
                val shade = AMBIENT + DIFFUSE * maxOf(0.0, nx * lx + ny * ly + nz * lz)
                val rgb = (minOf(255, (cr * shade).toInt()) shl 16) or (minOf(255, (cg * shade).toInt()) shl 8) or minOf(255, (cb * shade).toInt())
                val inv = 1.0 / area
                for (yy in yMin..yMax) {
                    val sy = yy + 0.5
                    for (xx in xMin..xMax) {
                        val sx = xx + 0.5
                        val w0 = ((px[1] - sx) * (py[2] - sy) - (px[2] - sx) * (py[1] - sy)) * inv
                        val w1 = ((px[2] - sx) * (py[0] - sy) - (px[0] - sx) * (py[2] - sy)) * inv
                        val w2 = 1.0 - w0 - w1
                        if (w0 < 0 || w1 < 0 || w2 < 0) continue
                        val d = (w0 * pd[0] + w1 * pd[1] + w2 * pd[2]).toFloat()
                        val i = yy * bw + xx
                        if (d < depth[i]) { depth[i] = d; color[i] = rgb }
                    }
                }
            }
        }
        // down to the asked size: each pixel the mean of its covered samples, its opacity the share covered
        val samples = ss * ss
        for (y in 0 until height) for (x in 0 until width) {
            var r = 0; var g = 0; var bl = 0; var covered = 0
            for (j in 0 until ss) for (k in 0 until ss) {
                val i = (y * ss + j) * bw + x * ss + k
                if (depth[i] == Float.POSITIVE_INFINITY) continue
                val v = color[i]; r += (v shr 16) and 255; g += (v shr 8) and 255; bl += v and 255; covered++
            }
            if (covered == 0) continue
            val a = (covered * 255 + samples / 2) / samples
            out[y * width + x] = (a shl 24) or ((r / covered) shl 16) or ((g / covered) shl 8) or (bl / covered)
        }
        return Image(width, height, out)
    }

    /** [image] as a PNG (8-bit RGBA, no interlace, rows unfiltered, zlib at its best compression). */
    fun png(image: Image): ByteArray {
        val w = image.width; val h = image.height
        val raw = ByteArray(h * (1 + 4 * w))
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0   // filter: none
            for (x in 0 until w) {
                val v = image.argb[y * w + x]
                raw[o++] = (v shr 16).toByte(); raw[o++] = (v shr 8).toByte(); raw[o++] = v.toByte(); raw[o++] = (v ushr 24).toByte()
            }
        }
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        val z = ByteArrayOutputStream()
        try {
            deflater.setInput(raw); deflater.finish()
            val buf = ByteArray(8192)
            while (!deflater.finished()) { val k = deflater.deflate(buf); z.write(buf, 0, k) }
        } finally { deflater.end() }
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
        val ihdr = ByteArrayOutputStream().apply { int(w); int(h); write(8); write(6); write(0); write(0); write(0) }.toByteArray()
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.int(v: Int) { write(v ushr 24); write((v ushr 16) and 255); write((v ushr 8) and 255); write(v and 255) }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        out.int(data.size)
        val t = type.toByteArray(Charsets.US_ASCII)
        out.write(t); out.write(data)
        val crc = CRC32(); crc.update(t); crc.update(data)
        out.int(crc.value.toInt())
    }

    /** The comment block holding [pictures], in the layout above; it starts and ends with a blank line. */
    fun block(pictures: List<Picture>): String {
        require(pictures.isNotEmpty()) { "a picture block needs a picture" }
        val sb = StringBuilder("\n; THUMBNAIL_BLOCK_START\n")
        for (p in pictures) {
            val b64 = Base64.getEncoder().encodeToString(p.png)
            sb.append("\n;\n; thumbnail begin ${p.width}x${p.height} ${b64.length}\n")
            var i = 0
            while (i < b64.length) { sb.append("; ").append(b64, i, minOf(b64.length, i + LINE)).append('\n'); i += LINE }
            sb.append("; thumbnail end\n")
        }
        sb.append("; THUMBNAIL_BLOCK_END\n\n")
        return sb.toString()
    }

    /**
     * [text] (plymeld's g-code) with [block] after its header line `; total layer number:` (the writer's second line), so
     * it stays among the header comments whatever header a machine adds round it (FlashStudioHeader).
     */
    fun insert(text: String, block: String): String {
        val at = Regex("(?m)^; total layer number:[^\n]*\n").find(text)
            ?: throw IllegalArgumentException("no '; total layer number:' header line to put the preview picture after")
        return text.substring(0, at.range.last + 1) + block + text.substring(at.range.last + 1)
    }

    /** "#RRGGBB" (a filament's color) as 0xRRGGBB, or null when it is not that form. */
    fun rgbOf(hex: String?): Int? {
        val h = hex?.trim()?.removePrefix("#") ?: return null
        if (h.length != 6) return null
        return h.toIntOrNull(16)
    }
}
