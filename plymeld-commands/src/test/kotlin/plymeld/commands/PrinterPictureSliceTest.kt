package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.model.SliceSettings
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import javax.imageio.ImageIO

/** `plymeld slice` writes the machine's preview pictures (hardware.machine.<key>.gcode.pictures) only when it asks for them. */
class PrinterPictureSliceTest : StringSpec({
    /** An ASCII STL box [x] x [y] x [z] mm, corner at the origin (12 outward-facing triangles). */
    fun box(x: Double, y: Double, z: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return buildString {
            append("solid box\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid box\n")
        }
    }
    fun hardware(description: String) = """
        [hardware.machine.m]
        model = "$description"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M109 S{first_layer_nozzle_temperature}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
    """.trimIndent()
    fun slice(description: String, pictures: String?): String {
        val d = Files.createTempDirectory("plymeld-picture")
        val hw = hardware(description)
        Files.writeString(d.resolve("hw.toml"), if (pictures == null) hw else hw.replace("gcode.end = \"M104 S0\"", "gcode.end = \"M104 S0\"\ngcode.pictures = \"$pictures\""))
        Files.writeString(d.resolve("box.stl"), box(20.0, 20.0, 5.0))
        val out: Path = d.resolve("box.gcode")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-m", d.resolve("box.stl").toString(), "-o", out.toString()))
        return Files.readString(out)
    }
    /** Each picture as the printer would read it: its declared size and the image the base64 decodes to. */
    fun pictures(text: String) = Regex("(?m)^; thumbnail begin (\\d+)x(\\d+) (\\d+)\n((?:; [A-Za-z0-9+/=]+\n)+); thumbnail end$").findAll(text).map { m ->
        val b64 = m.groupValues[4].lines().filter { it.isNotEmpty() }.joinToString("") { it.removePrefix("; ") }
        b64.length shouldBe m.groupValues[3].toInt()
        val img = ImageIO.read(ByteArrayInputStream(Base64.getDecoder().decode(b64)))
        Triple(m.groupValues[1].toInt(), m.groupValues[2].toInt(), img)
    }.toList()

    "a machine that asks for pictures gets one of each size, among the header comments; control: one that asks for none gets none" {
        val text = slice("qidi-q2", "140x110, 32x32")
        val pics = pictures(text)
        pics shouldHaveSize 2
        for ((w, h, img) in pics) { img.width shouldBe w; img.height shouldBe h; (img.getRGB(w / 2, h / 2) ushr 24) shouldBe 255; (img.getRGB(0, 0) ushr 24) shouldBe 0 }
        pics.map { it.first to it.second } shouldBe listOf(140 to 110, 32 to 32)
        // after the writer's header lines, before the first command
        val lines = text.lines()
        lines.indexOf("; THUMBNAIL_BLOCK_START") shouldBe lines.indexOfFirst { it.startsWith("; total layer number:") } + 2
        lines.indexOf("; THUMBNAIL_BLOCK_END") shouldBeLessThan lines.indexOfFirst { it.isNotEmpty() && !it.startsWith(";") }
        // control: the same machine without the setting
        val none = slice("qidi-q2", null)
        none shouldNotContain "thumbnail"
        pictures(none) shouldHaveSize 0
    }

    "on a machine with Flash Studio's header the picture follows the header block, as observed; control: none without the setting" {
        val text = slice("flashforge-c5", "140x110")
        val lines = text.lines()
        lines.first() shouldBe "; HEADER_BLOCK_START"
        pictures(text) shouldHaveSize 1
        lines.indexOf("; THUMBNAIL_BLOCK_START") shouldBeGreaterThan lines.indexOf("; HEADER_BLOCK_END")
        lines.indexOf("; THUMBNAIL_BLOCK_END") shouldBeLessThan lines.indexOfFirst { it.isNotEmpty() && !it.startsWith(";") }
        // control: no picture block (the source block may still name the model's evidence for its picture size)
        val none = slice("flashforge-c5", null)
        none shouldNotContain "; thumbnail begin"
        pictures(none) shouldHaveSize 0
    }

    "picture sizes are read as WIDTHxHEIGHT, comma-separated; control: a malformed one is refused by name" {
        SliceSettings.pictureSizes("140x110, 300X300") shouldBe listOf(140 to 110, 300 to 300)
        SliceSettings.pictureSizes("  ") shouldBe emptyList()
        shouldThrow<IllegalArgumentException> { SliceSettings.pictureSizes("140 by 110") }.message!! shouldContain "hardware.machine.<key>.gcode.pictures"
        shouldThrow<IllegalArgumentException> { SliceSettings.pictureSizes("0x110") }
    }
})
