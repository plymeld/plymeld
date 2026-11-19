package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.SyntheticGcode.Companion.png
import plymeld.commands.SyntheticGcode.Companion.render
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld render`'s model outline and the pre-print check's marks, on the generated hole block (test-models, not a user's
 * model) sliced with the sample config: the model's outline lies where the block's sides are; a wall line laid a second
 * time is marked laid twice and a stretch of the outer wall taken out is marked bare, each by the check's own rules;
 * controls: the file as sliced marks neither, and no outline is drawn without --outline-of. And the refusals.
 */
class RenderMarksTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")
    val dir = Files.createTempDirectory("plymeld-render-marks")
    val sliced = dir.resolve("block.gcode")
    Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
        listOf("plan.pose=as-given", "plan.placement=center", "plan.technique.flat.heights=one", "plan.technique.flat.layer.height=0.3 mm").flatMap { listOf("-cv", it) } +
        listOf("-m", model.toString(), "-o", sliced.toString()), { }) shouldBe 0
    val lines = Files.readAllLines(sliced)

    /** Layer 5's first outer-wall extruding move at least 5 mm long: its line index and its start (x, y). */
    fun wallMove(): Triple<Int, Double, Double> {
        var layer = 0; var feature = ""; var x = Double.NaN; var y = Double.NaN
        for ((i, l) in lines.withIndex()) {
            if (l.startsWith(";LAYER_CHANGE")) layer++
            if (l.startsWith(";TYPE:")) feature = l.removePrefix(";TYPE:")
            if (!l.startsWith("G1 ")) continue
            val w = l.substringBefore(';').split(' ')
            val nx = w.firstOrNull { it.startsWith("X") }?.drop(1)?.toDouble() ?: x
            val ny = w.firstOrNull { it.startsWith("Y") }?.drop(1)?.toDouble() ?: y
            val e = w.firstOrNull { it.startsWith("E") }?.drop(1)?.toDouble() ?: 0.0
            if (layer == 5 && feature == "Outer wall" && e > 0 && Math.hypot(nx - x, ny - y) >= 5.0) return Triple(i, x, y)
            x = nx; y = ny
        }
        error("no outer-wall move 5 mm long on layer 5")
    }
    val (at, sx, sy) = wallMove()
    // the same wall move laid again by a run of its own: a travel back to its start, then the move
    val twice = dir.resolve("twice.gcode").also { Files.write(it, lines.subList(0, at + 1) + listOf("G1 X$sx Y$sy F9000", lines[at]) + lines.subList(at + 1, lines.size)) }
    // the wall move made a travel: a stretch of the outer wall left out
    val bare = dir.resolve("bare.gcode").also { Files.write(it, lines.subList(0, at) + listOf(lines[at].substringBefore(" E") + " F9000") + lines.subList(at + 1, lines.size)) }

    fun picture(f: Path, out: String, vararg a: String): BufferedImage {
        val o = dir.resolve(out)
        // without the caption and key, whose swatches carry the marks' colors
        val (code, _, err) = render(f.toString(), "--layer", "5", *a, "--no-annotations", "-o", o.toString())
        code shouldBe 0; err shouldBe emptyList()
        return png(o.resolve("layer-0005.png"))
    }
    fun count(img: BufferedImage, rgb: Int): Int { var n = 0; for (y in 0 until img.height) for (x in 0 until img.width) if ((img.getRGB(x, y) and 0xFFFFFF) == rgb) n++; return n }
    val magenta = 0xD010C0; val red = 0xE01010

    "the model's outline lies along the block's sides; control: none without --outline-of" {
        // the block is 30 mm square: its sides 15 mm either side of its outer wall's middle
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var layer = 0; var feature = ""
        for (l in lines) { if (l.startsWith(";LAYER_CHANGE")) layer++; if (l.startsWith(";TYPE:")) feature = l.removePrefix(";TYPE:")
            if (layer == 5 && feature == "Outer wall" && l.startsWith("G1 ") && " E" in l) l.split(' ').firstOrNull { it.startsWith("X") }?.drop(1)?.toDouble()?.let { x0 = minOf(x0, it); x1 = maxOf(x1, it) } }
        val left = (x0 + x1) / 2 - 15
        val win = arrayOf("--bed-window", "${left - 5},${(x0 + x1) / 2 - 20},${left + 35},${(x0 + x1) / 2 + 20}", "--px-per-mm", "10")
        fun darkInColumn(img: BufferedImage): Int { val c = 50; var n = 0; for (y in 0 until img.height) { val p = img.getRGB(c, y); if ((0..2).all { ((p shr (8 * it)) and 0xFF) < 0x50 }) n++ }; return n }
        darkInColumn(picture(sliced, "outline", *win, "--outline-of", model.toString())) shouldBeGreaterThan 250
        darkInColumn(picture(sliced, "no-outline", *win)) shouldBe 0
    }

    "a wall line laid a second time is marked laid twice; control: the file as sliced marks none" {
        count(picture(twice, "twice", "--outline-of", model.toString(), "--mark-laid-twice"), magenta) shouldBeGreaterThan 0
        count(picture(sliced, "twice-control", "--outline-of", model.toString(), "--mark-laid-twice"), magenta) shouldBe 0
    }

    "a stretch of outer wall left out is marked bare; control: the file as sliced marks none" {
        count(picture(bare, "bare", "--outline-of", model.toString(), "--mark-bare"), red) shouldBeGreaterThan 0
        count(picture(sliced, "bare-control", "--outline-of", model.toString(), "--mark-bare"), red) shouldBe 0
    }

    "the marks and the outline are refused where they cannot apply, naming the options" {
        val o = dir.resolve("refused").toString()
        fun said(f: Path, vararg a: String): String { val (code, _, err) = render(f.toString(), *a, "-o", o); code shouldBe 1; return err.joinToString("\n") }
        said(sliced, "--mark-bare") shouldContain "--outline-of"
        said(sliced, "--mark-laid-twice") shouldContain "--outline-of"
        said(sliced, "--cut-y", "125", "--outline-of", model.toString(), "--mark-bare") shouldContain "--mark-bare"
        said(sliced, "--3d", "--outline-of", model.toString()) shouldContain "--outline-of"
        val foreign = dir.resolve("foreign.gcode").also { Files.write(it, lines.takeWhile { l -> l != "; PLYMELD_SOURCE_BLOCK_START" }) }
        said(foreign, "--outline-of", model.toString(), "--mark-laid-twice") shouldContain "source block"
        // control: a file without a source block still takes --outline-of (the model as its file places it)
        render(foreign.toString(), "--layer", "5", "--outline-of", model.toString(), "-o", o).first shouldBe 0
    }
})
