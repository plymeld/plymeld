package plymeld.core

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.util.Base64
import javax.imageio.ImageIO

/**
 * The printer-screen preview picture (PrinterPicture). Its PNG is read back with the JDK's own decoder (ImageIO), a reader
 * independent of plymeld's encoder; the block is read back by [readBlocks] below, written from the layout observed in Flash
 * Studio 1.7.18's output, not from PrinterPicture.block.
 */
class PrinterPictureTest : StringSpec({
    /** A box [x] x [y] x [z] mm with its low corner at ([ox], [oy], [oz]): 12 triangles. */
    fun box(x: Double, y: Double, z: Double, ox: Double = 0.0, oy: Double = 0.0, oz: Double = 0.0): TriangleSoup {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val c = FloatArray(faces.size * 9); var i = 0
        for (f in faces) for (k in f) { c[i++] = (v[k][0] + ox).toFloat(); c[i++] = (v[k][1] + oy).toFloat(); c[i++] = (v[k][2] + oz).toFloat() }
        return TriangleSoup(c, faces.size)
    }
    /** A sphere of radius [r] mm on the bed, [stacks] x [slices] quads as 2 triangles each. */
    fun sphere(r: Double, stacks: Int, slices: Int): TriangleSoup {
        val c = FloatArray(stacks * slices * 2 * 9); var i = 0
        fun p(a: Int, b: Int) { val th = Math.PI * a / stacks; val ph = 2 * Math.PI * b / slices
            c[i++] = (r * Math.sin(th) * Math.cos(ph)).toFloat(); c[i++] = (r * Math.sin(th) * Math.sin(ph)).toFloat(); c[i++] = (r + r * Math.cos(th)).toFloat() }
        for (a in 0 until stacks) for (b in 0 until slices) { p(a, b); p(a + 1, b); p(a + 1, b + 1); p(a, b); p(a + 1, b + 1); p(a, b + 1) }
        return TriangleSoup(c, stacks * slices * 2)
    }
    fun decode(png: ByteArray): BufferedImage = ImageIO.read(ByteArrayInputStream(png)) ?: error("ImageIO could not read the PNG")
    fun alpha(img: BufferedImage, x: Int, y: Int) = img.getRGB(x, y) ushr 24
    /** Whether [img] shows something in its middle and nothing at its four corners. */
    fun shows(img: BufferedImage): Boolean {
        val w = img.width; val h = img.height
        val corners = listOf(0 to 0, w - 1 to 0, 0 to h - 1, w - 1 to h - 1).all { (x, y) -> alpha(img, x, y) == 0 }
        return corners && alpha(img, w / 2, h / 2) == 255
    }

    /** One `; thumbnail begin` ... `; thumbnail end` found in a g-code text, as the printer would read it. */
    class Found(val width: Int, val height: Int, val declared: Int, val base64: String, val lines: List<String>, val framed: Boolean)
    fun readBlocks(text: String): List<Found> {
        val ls = text.lines(); val out = ArrayList<Found>(); var i = 0
        while (i < ls.size) {
            val m = Regex("; thumbnail begin (\\d+)x(\\d+) (\\d+)").matchEntire(ls[i])
            if (m == null) { i++; continue }
            val begin = i
            val body = ArrayList<String>(); i++
            while (ls[i] != "; thumbnail end") body += ls[i++]
            // observed: "; THUMBNAIL_BLOCK_START", "", ";" before the begin line; "; THUMBNAIL_BLOCK_END" after the end
            // (for a second picture in the block: the previous one's end line, "", ";")
            val framed = ls[begin - 1] == ";" && ls[begin - 2] == "" &&
                (ls[begin - 3] == "; THUMBNAIL_BLOCK_START" || ls[begin - 3] == "; thumbnail end") &&
                (ls[i + 1] == "; THUMBNAIL_BLOCK_END" || ls[i + 1] == "")
            out += Found(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), body.joinToString("") { it.removePrefix("; ") }, body, framed)
            i++
        }
        return out
    }
    /** What is wrong with a block as read: its framing, its line layout, its declared length. */
    fun problems(f: Found): List<String> = buildList {
        if (!f.framed) add("not framed as observed")
        if (f.lines.any { !it.startsWith("; ") }) add("a line without '; '")
        if (f.lines.dropLast(1).any { it.length != 2 + 78 } || f.lines.last().length !in 3..80) add("lines not 78 base64 characters")
        if (f.declared != f.base64.length) add("declared ${f.declared} characters, ${f.base64.length} follow")
    }

    val cube = listOf(PrinterPicture.Part(box(20.0, 20.0, 20.0), PrinterPicture.NEUTRAL_RGB))

    "the block's framing and declared length match the base64 that follows; control: a line taken out is caught" {
        val pics = listOf(PrinterPicture.Picture(140, 110, PrinterPicture.png(PrinterPicture.render(cube, 140, 110))),
            PrinterPicture.Picture(48, 48, PrinterPicture.png(PrinterPicture.render(cube, 48, 48))))
        val text = PrinterPicture.insert("; generated by plymeld test\n; total layer number: 3\nG28\n", PrinterPicture.block(pics))
        val found = readBlocks(text)
        found shouldHaveSize 2
        found.map { "${it.width}x${it.height}" } shouldBe listOf("140x110", "48x48")
        for (f in found) problems(f).shouldBeEmpty()
        // after the header line, before the first command
        text.indexOf("; THUMBNAIL_BLOCK_START") shouldBeGreaterThan text.indexOf("; total layer number: 3")
        text.indexOf("\nG28\n") shouldBeGreaterThan text.indexOf("; THUMBNAIL_BLOCK_END")
        // control: one base64 line removed -> the declared length no longer matches
        val cut = text.lines().toMutableList().also { l -> l.removeAt(l.indexOfFirst { it.startsWith("; thumbnail begin 140x110") } + 2) }.joinToString("\n")
        problems(readBlocks(cut).first()).shouldNotBeEmpty()
        // control: the framing lines removed -> caught
        problems(readBlocks(text.replace("; THUMBNAIL_BLOCK_START\n", "")).first()).shouldNotBeEmpty()
    }

    "the base64 decodes to a PNG of the asked size, showing the part in the middle and nothing at the corners; control: an empty plate shows nothing" {
        for ((w, h) in listOf(140 to 110, 300 to 300, 48 to 48, 16 to 120)) {
            val text = PrinterPicture.block(listOf(PrinterPicture.Picture(w, h, PrinterPicture.png(PrinterPicture.render(cube, w, h)))))
            val f = readBlocks("; total layer number: 1\n$text").single()
            val img = decode(Base64.getDecoder().decode(f.base64))
            img.width shouldBe w; img.height shouldBe h
            shows(img) shouldBe true
            // scaled to fill: the part spans at least 80% of the picture along one axis (the one that limits the scale)
            var x0 = w; var x1 = -1; var y0 = h; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (alpha(img, x, y) > 0) { x0 = minOf(x0, x); x1 = maxOf(x1, x); y0 = minOf(y0, y); y1 = maxOf(y1, y) }
            maxOf((x1 - x0 + 1) * 10 / w, (y1 - y0 + 1) * 10 / h) shouldBeGreaterThan 7
        }
        // control: nothing to draw -> the check sees an empty picture
        shows(decode(PrinterPicture.png(PrinterPicture.render(emptyList(), 140, 110)))) shouldBe false
    }

    "each part is drawn in its color and the nearer part hides the farther one; control: the parts swapped, the other wins" {
        val red = 0xFF0000; val blue = 0x0000FF
        // the view looks along +X +Y (from the front-left): a cube 20 mm further along that line is behind the first
        val d = 20.0 / Math.sqrt(2.0)
        fun count(nearRgb: Int, farRgb: Int): Pair<Int, Int> {
            // the far one is listed last: drawn in list order without a depth test, it would cover the near one
            val img = decode(PrinterPicture.png(PrinterPicture.render(listOf(
                PrinterPicture.Part(box(10.0, 10.0, 10.0), nearRgb), PrinterPicture.Part(box(10.0, 10.0, 10.0, d, d), farRgb)), 140, 110)))
            var r = 0; var b = 0
            for (y in 0 until 110) for (x in 0 until 140) {
                val v = img.getRGB(x, y); if (v ushr 24 != 255) continue
                val rr = (v shr 16) and 255; val bb = v and 255
                if (rr > 40 && bb == 0) r++ else if (bb > 40 && rr == 0) b++
            }
            return r to b
        }
        val (r1, b1) = count(red, blue)
        r1 shouldBeGreaterThan b1          // red in front: all of it shows, blue only in part
        b1 shouldBeGreaterThan 0           // ...but blue is there
        val (r2, b2) = count(blue, red)
        b2 shouldBeGreaterThan r2          // control: blue in front wins instead
    }

    "same parts, same picture, byte for byte" {
        val a = PrinterPicture.png(PrinterPicture.render(cube, 140, 110))
        val b = PrinterPicture.png(PrinterPicture.render(cube, 140, 110))
        a.contentEquals(b) shouldBe true
        PrinterPicture.png(PrinterPicture.render(listOf(PrinterPicture.Part(box(20.0, 10.0, 20.0), PrinterPicture.NEUTRAL_RGB)), 140, 110)).contentEquals(a) shouldBe false
    }

    "a filament color #RRGGBB is read; anything else is not" {
        PrinterPicture.rgbOf("#FF8000") shouldBe 0xFF8000
        PrinterPicture.rgbOf("ff8000") shouldBe 0xFF8000
        PrinterPicture.rgbOf("red") shouldBe null
        PrinterPicture.rgbOf(null) shouldBe null
        PrinterPicture.rgbOf("#FF80") shouldBe null
    }

    "a ~100k-triangle model renders and encodes at 140x110 quickly (timings printed; the bound is 5x the 100 ms aim, for a busy machine)" {
        val s = sphere(20.0, 224, 224)
        s.count shouldBe 100_352
        val parts = listOf(PrinterPicture.Part(s, PrinterPicture.NEUTRAL_RGB))
        val times = LongArray(6)
        var png = ByteArray(0)
        for (k in times.indices) { val t0 = System.nanoTime(); png = PrinterPicture.png(PrinterPicture.render(parts, 140, 110)); times[k] = (System.nanoTime() - t0) / 1_000_000 }
        println("PrinterPicture 140x110, ${s.count} triangles: first ${times[0]} ms, then ${times.drop(1).joinToString(", ")} ms; PNG ${png.size} bytes")
        shows(decode(png)) shouldBe true
        times.drop(1).min() shouldBeLessThan 500L
    }
})
