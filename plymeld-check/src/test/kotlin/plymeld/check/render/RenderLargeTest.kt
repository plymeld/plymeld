package plymeld.check.render

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Time and memory budget of the 3D view on a large file generated here ([RenderLarge.write]: 2500 layers 0.08 mm thick
 * of a 60 mm square's three walls, a ring of arcs, and fill lines 0.45 mm apart; 15 MB, 1.12 million extruding moves):
 * drawn in a JVM of its own with a 64 MB heap, whatever the file's size: the picture's buffers (2048 px on its longer
 * side, under 11 bytes a pixel) and 64 layers of moves at a time. Control: with a 24 MB heap the child ran out and the
 * test failed. The picture must be the size the scale rule gives (lines too close for the largest picture: flat
 * regions, 2048 px on the longer side), a check that the child drew the view rather than refused it. A slow suite
 * (tools/slow-suites.txt): it writes the file and starts a JVM.
 */
class RenderLargeTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-large")
    afterSpec { dir.toFile().deleteRecursively() }

    "the 3D view of a 15 MB file draws in a 64 MB heap within the time budget" {
        val f = RenderLarge.write(dir.resolve("large.gcode"), 2500)
        val java = ProcessHandle.current().info().command().orElseThrow()
        val p = ProcessBuilder(java, "-Xmx64m", "-cp", System.getProperty("java.class.path"), "plymeld.check.render.RenderLarge", f.toString(), dir.resolve("out").toString())
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor() shouldBe 0
        println("render-large: " + out.lines().filter { it.startsWith("render-large ") }.joinToString("; "))
        val ms = out.lines().single { it.startsWith("render-large ms ") }.removePrefix("render-large ms ").trim().toLong()
        val size = out.lines().single { it.startsWith("render-large size ") }.removePrefix("render-large size ").trim()
        size shouldBe "2048"
        // Budget: measured 1.5 s on an 18-thread development machine (the JVM's start not counted); a CI runner of a few
        // threads may take several times that. PROVISIONAL threshold, tightened from measurements.
        ms shouldBeLessThan 30_000
    }
})

/** The large file [RenderLargeTest] draws, and the child JVM's run ([main]: the file, the output folder). */
object RenderLarge {
    private fun n(v: Double) = String.format(Locale.ROOT, "%.3f", v).trimEnd('0').trimEnd('.')

    fun write(p: Path, layers: Int): Path {
        Files.newBufferedWriter(p).use { w: BufferedWriter ->
            w.write("; filament_diameter: 1.75\nG90\nM83\n")
            // filament for a line [len] mm long, 0.45 mm wide, 0.08 mm thick: its cross-section h (w - h (1 - pi / 4))
            fun e(len: Double) = len * 0.08 * (0.45 - 0.08 * (1 - Math.PI / 4)) / (Math.PI * 1.75 * 1.75 / 4)
            for (k in 1..layers) {
                val z = 0.08 * k
                w.write(";LAYER_CHANGE\n;Z:${n(z)}\n;HEIGHT:0.08\nG1 Z${n(z)} F600\n;TYPE:Outer wall\n;WIDTH:0.45\n")
                for (r in 0 until 3) {
                    val a = 70.0 + 0.45 * r; val b = 130.0 - 0.45 * r
                    w.write("G1 X${n(a)} Y${n(a)} F9000\n")
                    for ((x, y) in listOf(b to a, b to b, a to b, a to a)) w.write("G1 X${n(x)} Y${n(y)} E${n(e(b - a))} F3000\n")
                }
                // a ring of arcs round the middle
                w.write(";TYPE:Inner wall\nG1 X110 Y100 F9000\nG2 X90 Y100 I-10 J0 E${n(e(Math.PI * 10))} F2400\nG2 X110 Y100 I10 J0 E${n(e(Math.PI * 10))} F2400\n")
                w.write(";TYPE:Sparse infill\n")
                for (j in 0 until 120) {
                    val y = 72.0 + j * 0.45
                    val (x0, x1) = if ((j + k) % 2 == 0) 72.0 to 128.0 else 128.0 to 72.0
                    w.write("G1 X${n(x0)} Y${n(y)} F9000\nG1 X${n(x1)} Y${n(y)} E${n(e(56.0))} F6000\n")
                }
            }
        }
        return p
    }

    @JvmStatic fun main(args: Array<String>) {
        val t0 = System.nanoTime()
        val r = Render.run(RenderRequest(Path.of(args[0]), threeD = true, noAnnotations = true, outDir = Path.of(args[1]))) { }
        val ms = (System.nanoTime() - t0) / 1_000_000
        val img = javax.imageio.ImageIO.read(r.files.single().toFile())
        println("render-large ms $ms")
        println("render-large size ${maxOf(img.width, img.height)}")
        println("render-large heap at most ${Runtime.getRuntime().maxMemory() / 1_000_000} MB")
        val index = GcodeIndex.read(Path.of(args[0]))
        println("render-large file ${Files.size(Path.of(args[0])) / 1_000_000} MB, ${index.layers.size} layers, ${index.layers.sumOf { it.extrudeMoves }} extruding moves")
    }
}
