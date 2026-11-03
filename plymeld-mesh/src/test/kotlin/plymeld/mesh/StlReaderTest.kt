package plymeld.mesh

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.Path

/**
 * The reader is checked against independently computed facts (`test-models/NAME.stl.reference.json`), which plymeld
 * did not produce. Faces must match exactly; bbox, area and volume to float32 tolerance.
 */
class StlReaderTest : StringSpec({
    val models = TestModels.dir()

    for (fixture in listOf("3DBenchy")) {
        "test-models/$fixture.stl matches the independent reference" {
            val reference = TestModels.reference("$fixture.stl")
            val soup = StlReader.read(models.resolve("$fixture.stl"))
            soup.count shouldBe reference.faces
            val b = soup.bounds()
            for (k in 0 until 3) {
                b[k] shouldBe (reference.bboxMin[k] plusOrMinus 1e-4)
                b[k + 3] shouldBe (reference.bboxMax[k] plusOrMinus 1e-4)
            }
            soup.area() shouldBe (reference.area plusOrMinus reference.area * 1e-5)
            soup.signedVolume() shouldBe (reference.volume plusOrMinus Math.abs(reference.volume) * 1e-5)
        }
    }

    "ASCII STL parses to the same soup as its binary twin" {
        val bin = StlReader.read(models.resolve("holeblock.stl"))
        val text = toAscii(bin)
        val asc = StlReader.readAscii(ByteArrayInputStream(text.toByteArray()), "holeblock-ascii")
        asc.count shouldBe bin.count
        asc.coords.contentEquals(bin.coords) shouldBe true
    }

    "binary file that merely starts with 'solid' is still read as binary" {
        val bin = StlReader.read(models.resolve("holeblock.stl"))
        val bytes = Files.readAllBytes(models.resolve("holeblock.stl"))
        "solid".toByteArray().copyInto(bytes, 0)
        val tmp = Files.createTempFile("solid-prefixed", ".stl")
        try {
            Files.write(tmp, bytes)
            StlReader.read(tmp).coords.contentEquals(bin.coords) shouldBe true
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    "control: truncated binary STL is rejected, not silently shortened" {
        val bytes = Files.readAllBytes(models.resolve("holeblock.stl"))
        val cut = bytes.copyOf(bytes.size - 37)
        val tmp = Files.createTempFile("truncated", ".stl")
        try {
            Files.write(tmp, cut)
            shouldThrow<MeshFormatException> { StlReader.read(tmp) }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }

    "control: ASCII facet with two vertices is rejected" {
        val bad = """
            solid t
              facet normal 0 0 1
                outer loop
                  vertex 0 0 0
                  vertex 1 0 0
                endloop
              endfacet
            endsolid t
        """.trimIndent()
        shouldThrow<MeshFormatException> { StlReader.readAscii(ByteArrayInputStream(bad.toByteArray())) }
    }

    "control: NaN coordinate in binary is rejected" {
        val buf = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        buf.position(80); buf.putInt(1)
        repeat(3) { buf.putFloat(0f) }
        buf.putFloat(Float.NaN); repeat(8) { buf.putFloat(1f) }
        buf.putShort(0)
        shouldThrow<MeshFormatException> { StlReader.readBinary(ByteArrayInputStream(buf.array()), 1) }
    }
})

private fun toAscii(s: TriangleSoup): String = buildString {
    append("solid twin\n")
    for (t in 0 until s.count) {
        append("  facet normal 0 0 0\n    outer loop\n")
        for (c in 0 until 3) append("      vertex ${s.x(t, c)} ${s.y(t, c)} ${s.z(t, c)}\n")
        append("    endloop\n  endfacet\n")
    }
    append("endsolid twin\n")
}

/** Locates `test-models/` from the module's working directory and parses the reference JSON (minimal, no library). */
object TestModels {
    fun dir(): Path {
        var p = Path.of("").toAbsolutePath()
        while (true) {
            val c = p.resolve("test-models")
            if (Files.isDirectory(c)) return c
            p = p.parent ?: error("test-models/ not found above ${Path.of("").toAbsolutePath()}")
        }
    }

    class Reference(val faces: Int, val bboxMin: DoubleArray, val bboxMax: DoubleArray, val area: Double, val volume: Double, val watertight: Boolean, val uniqueVertices: Int)

    fun reference(fixture: String): Reference {
        val text = Files.readString(dir().resolve("$fixture.reference.json"))
        fun num(key: String) = Regex("\"$key\"\\s*:\\s*(-?[0-9.eE+-]+)").find(text)!!.groupValues[1].toDouble()
        fun arr(key: String) = Regex("\"$key\"\\s*:\\s*\\[([^\\]]*)]").find(text)!!.groupValues[1]
            .split(",").map { it.trim().toDouble() }.toDoubleArray()
        fun bool(key: String) = Regex("\"$key\"\\s*:\\s*(true|false)").find(text)!!.groupValues[1].toBoolean()
        return Reference(num("faces").toInt(), arr("bbox_min"), arr("bbox_max"), num("area"), num("volume"), bool("watertight"), num("unique_vertices").toInt())
    }
}
