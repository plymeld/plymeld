package plymeld.mesh

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * Memory-budget test on large models generated here ([LargeMeshes], written to a temporary directory): a
 * 4.9M-triangle binary STL (245 MB) and a 3MF of 1.2M triangles (~80 MB of XML). Reading must keep nothing but the
 * triangles.
 *
 * The triangle counts are known from how the files were written (and the STL's also from its length alone): facts the
 * reader could not have influenced.
 */
class LargeMeshTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-large-mesh")
    afterSpec { dir.toFile().deleteRecursively() }

    "4.9M-triangle STL reads within the retained-memory budget" {
        val path = dir.resolve("large.stl")
        val written = LargeMeshes.writeStl(path, 1566)                       // 2 x 1566² = 4,904,712 triangles
        val expectedTris = ((Files.size(path) - 84) / 50).toInt()
        expectedTris shouldBe written

        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val soup = StlReader.read(path)
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()

        soup.count shouldBe expectedTris
        val soupBytes = soup.coords.size.toLong() * 4
        val retained = after - before
        println("large-stl: $expectedTris tris in $ms ms; soup ${soupBytes / 1_000_000} MB, retained ${retained / 1_000_000} MB")

        // Budget: nothing but the soup itself may remain (allow 16 MB of slack for
        // the runtime). PROVISIONAL threshold — tightened from measurements, not guessed.
        retained shouldBeLessThan soupBytes + 16_000_000
        val b = soup.bounds()
        (b[3] > b[0] && b[4] > b[1] && b[5] > b[2]) shouldBe true
    }

    "1.2M-triangle 3MF streams without holding the document" {
        val path = dir.resolve("large.3mf")
        val written = LargeMeshes.write3mf(path, 780)                        // 2 x 780² = 1,216,800 triangles
        Files.deleteIfExists(dir.resolve("large.stl"))                       // the STL's 245 MB are not needed any more
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val t0 = System.nanoTime()
        val model = ThreeMfReader.read(path)
        val ms = (System.nanoTime() - t0) / 1_000_000
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val tris = model.parts.sumOf { it.soup.count }
        val soupBytes = tris.toLong() * 9 * 4
        val retained = after - before
        println("large-3mf: ${model.parts.size} parts, $tris tris in $ms ms; soups ${soupBytes / 1_000_000} MB, retained ${retained / 1_000_000} MB")
        tris shouldBe written
        // The soups plus 64 MB of slack (per-object vertex/index buffers are dropped after
        // conversion; the XML must never be resident). PROVISIONAL threshold.
        retained shouldBeLessThan soupBytes + 64_000_000
    }
})

/** Large meshes written for [LargeMeshTest]: a gently rolling height field over an n x n grid of 0.1 mm cells, two triangles a cell. */
object LargeMeshes {
    private fun z(i: Int, j: Int): Float = (1.0 + 0.5 * sin(i * 0.05) + 0.5 * cos(j * 0.07)).toFloat()

    /** Writes the height field as a binary STL; returns its triangle count. */
    fun writeStl(path: Path, n: Int): Int {
        val tris = 2 * n * n
        BufferedOutputStream(Files.newOutputStream(path), 1 shl 20).use { out ->
            val head = ByteBuffer.allocate(84).order(ByteOrder.LITTLE_ENDIAN)
            head.position(80); head.putInt(tris)
            out.write(head.array())
            val b = ByteBuffer.allocate(50).order(ByteOrder.LITTLE_ENDIAN)
            fun tri(vararg v: Float) {
                b.clear()
                repeat(3) { b.putFloat(0f) }                                   // normal: readers recompute it
                for (c in v) b.putFloat(c)
                b.putShort(0)
                out.write(b.array())
            }
            for (i in 0 until n) for (j in 0 until n) {
                val x0 = i * 0.1f; val x1 = (i + 1) * 0.1f; val y0 = j * 0.1f; val y1 = (j + 1) * 0.1f
                tri(x0, y0, z(i, j), x1, y0, z(i + 1, j), x1, y1, z(i + 1, j + 1))
                tri(x0, y0, z(i, j), x1, y1, z(i + 1, j + 1), x0, y1, z(i, j + 1))
            }
        }
        return tris
    }

    /** Writes the height field as a one-object 3MF (shared vertices, as 3MF stores them); returns its triangle count. */
    fun write3mf(path: Path, n: Int): Int {
        val tris = 2 * n * n
        ZipOutputStream(BufferedOutputStream(Files.newOutputStream(path), 1 shl 20)).use { zip ->
            fun entry(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
            }
            entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>""")
            entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>""")
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            val w = OutputStreamWriter(zip, Charsets.UTF_8).buffered(1 shl 20)
            w.write("""<?xml version="1.0" encoding="UTF-8"?>
<model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model" name="large"><mesh><vertices>""")
            for (i in 0..n) for (j in 0..n) w.write("<vertex x=\"${i * 0.1f}\" y=\"${j * 0.1f}\" z=\"${z(i, j)}\"/>\n")
            w.write("</vertices><triangles>")
            fun v(i: Int, j: Int) = i * (n + 1) + j
            for (i in 0 until n) for (j in 0 until n) {
                w.write("<triangle v1=\"${v(i, j)}\" v2=\"${v(i + 1, j)}\" v3=\"${v(i + 1, j + 1)}\"/>\n")
                w.write("<triangle v1=\"${v(i, j)}\" v2=\"${v(i + 1, j + 1)}\" v3=\"${v(i, j + 1)}\"/>\n")
            }
            w.write("</triangles></mesh></object></resources><build><item objectid=\"1\"/></build></model>\n")
            w.flush()
            zip.closeEntry()
        }
        return tris
    }
}
