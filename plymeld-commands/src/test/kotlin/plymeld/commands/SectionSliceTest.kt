package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.mesh.StlReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * A section of a model sliced on its own (`model.object.<key>.section`): the hole block cut to its lower-left quarter in
 * plan and its lower half in height, sliced by `plymeld slice`, whose own check then reads the g-code back against the
 * model (lines outside it, outlines without walls). Read from the g-code: every line laid within the section's size, and
 * nothing above its height.
 *
 * Closed (faces = "closed"): the model itself is cut, so the section is an object of its own, with walls up its cut faces.
 * Open (the default): the whole block is planned and placed, and its lines inside the box are laid: the section's corner
 * is where the whole block's is, no line runs along its cut faces, and its last layer is not a top surface.
 *
 * An object laid part by part (two parts in a 3MF, one with a setting of its own: the path a multi-filament object takes)
 * is cut the same way: each part's lines inside the box, the layers above its top left out, the check passing.
 *
 * Controls: without the section the same slice lays lines over the whole block's size and to its full height; the closed
 * section is the open one's control (lines along the cut face, a top surface); a box beside the model is refused, naming
 * where the model is; an open section from above the model's bottom is refused.
 */
class SectionSliceTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val model = root.resolve("test-models/holeblock.stl")
    val b = StlReader.read(model).bounds()
    val midX = (b[0] + b[3]) / 2; val midY = (b[1] + b[4]) / 2; val midZ = (b[2] + b[5]) / 2

    /** The slice's exit code, what it said, and its g-code's lines (empty when it wrote none). */
    fun slice(vararg cv: String, of: Path = model): Triple<Int, String, List<String>> {
        val out = Files.createTempDirectory("plymeld-section").resolve("block.gcode")
        val said = StringBuilder()
        val code = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", of.toString(), "-o", out.toString()), { said.appendLine(it) })
        return Triple(code, said.toString(), if (Files.exists(out)) Files.readAllLines(out) else emptyList())
    }
    /** The object's laid lines' extent in x, in y, and their greatest z (mm): moves with plastic between the object's markers. */
    fun extent(g: List<String>): DoubleArray {
        var x = Double.NaN; var y = Double.NaN; var z = 0.0; var inObject = false
        var x0 = Double.MAX_VALUE; var x1 = -Double.MAX_VALUE; var y0 = Double.MAX_VALUE; var y1 = -Double.MAX_VALUE; var zTop = 0.0
        for (l in g) {
            if (l.startsWith("; object start")) inObject = true
            if (l.startsWith("; object end")) inObject = false
            if (!l.startsWith("G1 ")) continue
            val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.isNotEmpty() }.associate { it[0] to it.substring(1).toDouble() }
            val nx = w['X'] ?: x; val ny = w['Y'] ?: y; w['Z']?.let { z = it }
            if (inObject && (w['E'] ?: 0.0) > 0 && (nx != x || ny != y)) {
                x0 = minOf(x0, x, nx); x1 = maxOf(x1, x, nx); y0 = minOf(y0, y, ny); y1 = maxOf(y1, y, ny); zTop = maxOf(zTop, z)
            }
            x = nx; y = ny
        }
        return doubleArrayOf(x1 - x0, y1 - y0, zTop, x0, y0, x1)
    }
    /** The plastic laid along the object's far side in x (mm): moves running in y with both ends within 0.6 mm of [x1], the lines' greatest x. */
    fun alongFarSide(g: List<String>, x1: Double): Double {
        var x = Double.NaN; var y = Double.NaN; var inObject = false; var sum = 0.0
        for (l in g) {
            if (l.startsWith("; object start")) inObject = true
            if (l.startsWith("; object end")) inObject = false
            if (!l.startsWith("G1 ")) continue
            val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.isNotEmpty() }.associate { it[0] to it.substring(1).toDouble() }
            val nx = w['X'] ?: x; val ny = w['Y'] ?: y
            if (inObject && (w['E'] ?: 0.0) > 0 && x > x1 - 0.6 && nx > x1 - 0.6 && Math.abs(ny - y) > 1.0) sum += Math.abs(ny - y)
            x = nx; y = ny
        }
        return sum
    }
    /** How many of the g-code's lines open a top surface. */
    fun tops(g: List<String>): Int = g.count { it.startsWith(";TYPE:") && "top" in it.lowercase() }
    val key = "model.object.\"holeblock.stl\".section"

    "a closed section is sliced and checked as an object of its own: its lines within its size and height; control: without it, the whole block's" {
        val (code, said, g) = slice("$key.min=${b[0] - 1} ${b[1] - 1} ${b[2] - 1} mm", "$key.max=$midX $midY $midZ mm", "$key.faces=closed")
        val (codeAll, _, gAll) = slice()
        println(said.lines().filter { "section" in it || it.startsWith("check") }.joinToString("\n"))
        code shouldBe 0
        said shouldContain "only its section prints"
        said shouldContain "check: the g-code reads back as the config and the model say"
        val e = extent(g); val all = extent(gAll)
        println("section lines span %.2f x %.2f mm to z %.2f; the whole block's %.2f x %.2f to z %.2f (the block %.2f x %.2f x %.2f)".format(java.util.Locale.ROOT,
            e[0], e[1], e[2], all[0], all[1], all[2], b[3] - b[0], b[4] - b[1], b[5] - b[2]))
        codeAll shouldBe 0
        // within the section's size (a line's center lies inside the outline) and no higher than its top
        (e[0] <= midX - b[0] + 1e-3 && e[1] <= midY - b[1] + 1e-3 && e[2] <= midZ - b[2] + 1e-3) shouldBe true
        (e[0] > (midX - b[0]) * 0.8 && e[2] > (midZ - b[2]) * 0.8) shouldBe true                  // and it is the section, not a speck
        // control: the whole block is larger on every axis
        (all[0] > e[0] + 1 && all[1] > e[1] + 1 && all[2] > e[2] + 1) shouldBe true
    }
    "a section left open (the default) lays the whole block's lines inside the box: where the whole block's are, no walls or top on its cut faces" {
        val box = arrayOf("$key.min=${b[0] - 1} ${b[1] - 1} ${b[2] - 1} mm", "$key.max=$midX $midY $midZ mm")
        val (code, said, g) = slice(*box)
        val (codeClosed, _, gClosed) = slice(*box, "$key.faces=closed")
        val (codeAll, _, gAll) = slice()
        println(said.lines().filter { "section" in it || it.startsWith("check") }.joinToString("\n"))
        if (code != 0) println(said)
        code shouldBe 0; codeClosed shouldBe 0; codeAll shouldBe 0
        said shouldContain "its cut faces left open"
        said shouldContain "check: the g-code reads back as the config and the model say"
        val e = extent(g); val closed = extent(gClosed); val all = extent(gAll)
        val along = alongFarSide(g, e[5]); val alongClosed = alongFarSide(gClosed, closed[5])
        println("open: %.2f x %.2f mm to z %.2f from (%.2f, %.2f), %.1f mm along its cut face, %d top surface(s); closed: from (%.2f, %.2f), %.1f mm along it, %d; whole: from (%.2f, %.2f)".format(
            java.util.Locale.ROOT, e[0], e[1], e[2], e[3], e[4], along, tops(g), closed[3], closed[4], alongClosed, tops(gClosed), all[3], all[4]))
        // the section, within its size and height
        (e[0] <= midX - b[0] + 1e-3 && e[1] <= midY - b[1] + 1e-3 && e[2] <= midZ - b[2] + 1e-3) shouldBe true
        (e[0] > (midX - b[0]) * 0.8 && e[2] > (midZ - b[2]) * 0.8) shouldBe true
        // placed as the whole block is: its lines start at the whole block's corner (control: the closed section, centered on its own, does not)
        (Math.abs(e[3] - all[3]) < 0.02 && Math.abs(e[4] - all[4]) < 0.02) shouldBe true         // to 0.02 mm: the whole block's least y may lie on a line outside the box
        (Math.abs(closed[3] - all[3]) > 1) shouldBe true
        // nothing runs along the cut face (control: the closed section's walls do, layer after layer)
        (along < 1.0) shouldBe true
        (alongClosed > 50) shouldBe true
        // its last layer is the block's interior there, not a top surface (control: the closed section has one)
        tops(g) shouldBe 0
        (tops(gClosed) > 0) shouldBe true
    }
    "an object laid part by part, its section left open: each part's lines inside the box, nothing above its top; control: the whole object's" {
        // a 20 mm base 6 mm tall under a 10 mm top to 12 mm, as two parts of one object, the top with an infill of its own
        fun boxTris(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): List<DoubleArray> {
            val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
                doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
            val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
            return (0 until 12).map { t -> DoubleArray(9) { v[f[3 * t + it / 3]][it % 3] } }
        }
        val parts = listOf("base" to boxTris(0.0, 0.0, 0.0, 20.0, 20.0, 6.0), "top" to boxTris(5.0, 5.0, 6.0, 15.0, 15.0, 12.0))
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        for ((k, p) in parts.withIndex()) {
            sb.append("<object id=\"${k + 1}\" type=\"model\" name=\"stack_${p.first}\"><mesh><vertices>")
            for (t in p.second) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
            sb.append("</vertices><triangles>")
            for (i in p.second.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
            sb.append("</triangles></mesh></object>")
        }
        sb.append("<object id=\"3\" type=\"model\" name=\"stack\"><components><component objectid=\"1\"/><component objectid=\"2\"/></components></object></resources><build><item objectid=\"3\"/></build></model>")
        val stack = Files.createTempDirectory("plymeld-section-parts").resolve("stack.3mf")
        java.util.zip.ZipOutputStream(Files.newOutputStream(stack)).use { z -> z.putNextEntry(java.util.zip.ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
        val own = "plan.object.stack.part.stack_top.layer.infill.density=40%"
        val (code, said, g) = slice(own, "model.object.stack.section.min=-1 -1 -1 mm", "model.object.stack.section.max=10 21 9 mm", of = stack)
        val (codeAll, _, gAll) = slice(own, of = stack)
        if (code != 0) println(said)
        code shouldBe 0; codeAll shouldBe 0
        said shouldContain "its cut faces left open"
        said shouldContain "check: the g-code reads back as the config and the model say"
        val e = extent(g); val all = extent(gAll)
        println("parts, open: %.2f x %.2f mm to z %.2f; whole: %.2f x %.2f to z %.2f".format(java.util.Locale.ROOT, e[0], e[1], e[2], all[0], all[1], all[2]))
        // half the base in x, all of it in y, and 3 mm of the top part's 6 above the base
        (e[0] <= 10 + 1e-3 && e[0] > 8 && e[1] > 18 && e[2] <= 9 + 1e-3 && e[2] > 8) shouldBe true
        // control: the whole object is twice as wide and reaches its top
        (all[0] > 18 && all[2] > 11) shouldBe true
    }
    "control: an open section from above the model's bottom is refused" {
        val (code, said, g) = slice("$key.min=${b[0] - 1} ${b[1] - 1} $midZ mm", "$key.max=$midX $midY ${b[5] + 1} mm")
        (code != 0) shouldBe true
        said shouldContain "a section left open starts at the model's bottom"
        g.size shouldBe 0
    }
    "control: a box beside the model is refused, naming where the model is" {
        val (code, said, g) = slice("$key.min=${b[3] + 10} 0 0 mm", "$key.max=${b[3] + 20} 1 1 mm", "$key.faces=closed")
        (code != 0) shouldBe true
        said shouldContain "nothing of 'holeblock.stl' lies in the box"
        said shouldContain "the model spans"
        g.size shouldBe 0
    }
})
