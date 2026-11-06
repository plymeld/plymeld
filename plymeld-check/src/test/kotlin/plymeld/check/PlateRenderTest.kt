package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.check.plate.BaseLayers
import plymeld.check.plate.PlateRender
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * The plate pictures (`plymeld render-plate`) on small hand-written g-code: each draws what the g-code holds, and refuses
 * g-code without what it draws. (Checked against the workspace scripts they were ported from, on real plates, when ported:
 * byte-identical PNGs.)
 */
class PlateRenderTest : StringSpec({
    val dir = Files.createTempDirectory("plate-render")
    fun write(name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text.trimIndent()) }
    // a first-layer plate in miniature: one block, its prime line, one tile, one marker dot
    val firstLayer = write("first.gcode", """
        M83
        M109 S205
        ; CELL: block 1 prime
        G1 X10 Y5 Z0.2 F3000
        G1 X40 Y5 E1
        ; CELL END
        ; CELL: block 1 tile 1.1
        G1 X10 Y10 F2400
        G1 X20 Y10 E0.5
        G1 X20 Y10.5 E0.03
        G1 X10 Y10.5 E0.5
        ; CELL END
        ; CELL: block 1 mark
        G1 X10 Y8
        G1 X10.5 Y8 E0.02
        ; CELL END
        M104 S0
    """)
    // one base cell over two layers, a wall ring then two diagonal lines
    val base = write("base.gcode", """
        M83
        ;Z:0.2
        ; CELL: base melt
        ;TYPE:Outer wall
        ;WIDTH:0.44
        G1 X0 Y0 Z0.2 F3000
        G1 X20 Y0 E1
        G1 X20 Y10 E0.5
        G1 X0 Y10 E1
        G1 X0 Y0 E0.5
        ; CELL END
        ;Z:0.4
        ; CELL: base melt
        ;TYPE:Solid infill
        G1 X1 Y1 Z0.4
        G1 X9 Y9 E0.5
        G1 X11 Y1
        G1 X19 Y9 E0.5
        ; CELL END
    """)
    fun pixels(p: Path, color: Int): Int { val img = ImageIO.read(p.toFile()); var n = 0
        for (y in 0 until img.height) for (x in 0 until img.width) if (img.getRGB(x, y) and 0xffffff == color) n++
        return n }

    "the first-layer plate's picture is drawn from its lines" {
        val out = dir.resolve("first.png")
        val log = ArrayList<String>()
        PlateRender.render(firstLayer, out, log = { log += it })
        val img = ImageIO.read(out.toFile())
        (img.width to img.height) shouldBe (1900 to 1150)
        pixels(out, 0x3b4cc0) shouldBeGreaterThan 0   // the tile, at the one temperature: the cool end of the ramp (59, 76, 192)
        log.joinToString("\n") shouldContain "first-layer plate, 5 extrusion moves, 1 blocks"
        log.joinToString("\n") shouldContain "block 1: 205 C, waited (M109), temperature label, 0 speed(s), 0 height(s)"
    }
    "a first-layer plate of nine framed blocks is drawn whole: the picture grows to hold every block's lines and the close-up" {
        // nine blocks, each a tile and a frame line over it: the per-block summary (six lines a block) runs past the
        // standard 1150 px, which once left the close-up a negative size
        val text = buildString {
            appendLine("M83")
            for (k in 1..9) {
                val x = 10.0 + 30 * ((k - 1) % 3); val y = 10.0 + 30 * ((k - 1) / 3)
                appendLine("M109 S${190 + 5 * k}")
                appendLine("; CELL: block $k tile 1.1"); appendLine("G1 X$x Y$y Z0.2 F2400"); appendLine("G1 X${x + 10} Y$y E0.5"); appendLine("; CELL END")
                appendLine("; CELL: block $k frame 1"); appendLine("G1 X$x Y${y + 0.5} Z0.4"); appendLine("G1 X${x + 10} Y${y + 0.5} E0.5"); appendLine("; CELL END")
            }
            appendLine("M104 S0")
        }
        val out = dir.resolve("nine.png")
        val log = ArrayList<String>()
        PlateRender.render(write("nine.gcode", text), out, log = { log += it })
        val img = ImageIO.read(out.toFile())
        img.width shouldBe 1900
        img.height shouldBeGreaterThan 1150
        log.joinToString("\n") shouldContain "9 blocks"
        log.joinToString("\n") shouldContain "block 9: 235 C, waited (M109), NO temperature label, 0 speed(s), 0 height(s)"
    }
    "a g-code without plate cells is refused (control)" {
        val bare = write("bare.gcode", "M83\nG1 X0 Y0 F3000\nG1 X10 Y0 E1\n")
        shouldThrow<IllegalArgumentException> { PlateRender.render(bare, dir.resolve("bare.png"), log = {}) }.message shouldContain "no extrusion"
    }
    "a base's layers: a panel per layer, the base cells found" {
        BaseLayers.baseCells(base) shouldBe listOf("base melt")
        val out = dir.resolve("base.png")
        val log = ArrayList<String>()
        BaseLayers.render(base, "base melt", out) { log += it }
        ImageIO.read(out.toFile()).width shouldBe 30 + 2 * (620 + 30)
        log.single() shouldContain "layers 0.2, 0.4"
    }
    "a base the g-code does not have is refused (control)" {
        shouldThrow<IllegalArgumentException> { BaseLayers.render(base, "base fit", dir.resolve("none.png")) {} }.message shouldContain "no 'base fit' cell"
    }
})
