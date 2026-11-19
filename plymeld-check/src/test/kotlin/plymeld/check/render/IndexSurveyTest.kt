package plymeld.check.render

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * The whole-print views' first pass made while the file is indexed ([IndexSurvey]) is the pass the view makes by reading
 * the file again ([SolidDrawing.survey]), number for number: on a file of two objects, two tools, features, speeds, part
 * fan, temperatures and declared widths and heights that change, for every coloring, lines chosen and kept, and several
 * views; and on a file that marks no layers. Where what the index knew while reading did not hold to the end (a filament
 * diameter stated after the moves, a layer's thickness declared after moves that declare none), it gives nothing and the
 * view reads the file again.
 */
class IndexSurveyTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-index-survey")
    fun n(v: Double) = String.format(Locale.ROOT, "%.5f", v).trimEnd('0').trimEnd('.')

    /** A print of [layers] layers: walls by two tools in two objects, fill at changing speeds, fan and temperature. */
    fun gcode(markers: Boolean = true, diameterAtEnd: Boolean = false, lateHeight: Boolean = false, layers: Int = 12): String {
        val b = StringBuilder()
        if (!diameterAtEnd) b.append("; filament_diameter: 1.75,1.75\n")
        b.append("G90\nM83\n")
        var z = 0.0
        for (k in 1..layers) {
            val h = if (k == 1) 0.3 else if (k % 3 == 0) 0.12 else 0.2
            z += h
            // lateHeight: no layer declares its height but the fourth, after its first object's moves
            if (markers) b.append(";LAYER_CHANGE\n;Z:${n(z)}\n" + (if (lateHeight) "" else ";HEIGHT:${n(h)}\n"))
            b.append("G1 Z${n(z)} F600\nM106 S${(k * 20) % 256}\nM104 S${200 + k}\n")
            for ((obj, x0) in listOf("left" to 5.0, "right" to 40.0)) {
                b.append("; object start $obj\n")
                b.append(";TYPE:Outer wall\n;WIDTH:0.45\nT${if (obj == "left") 0 else 1}\nG1 X${n(x0)} Y5 F9000\n")
                for ((x, y) in listOf(x0 + 20 to 5.0, x0 + 20 to 25.0, x0 to 25.0, x0 to 5.0)) b.append("G1 X${n(x)} Y${n(y)} E${n(0.02 * (20 + k % 4))} F${1200 + 300 * (k % 5)}\n")
                if (lateHeight && k == 4 && obj == "right") b.append(";HEIGHT:0.2\n")
                b.append(";TYPE:Sparse infill\n;WIDTH:0.5\nG1 X${n(x0 + 1)} Y6 F9000\n")
                for (j in 0 until 6) b.append("G1 X${n(x0 + 19)} Y${n(6.0 + 3 * j + (k % 2))} E${n(0.6 + 0.05 * j)} F${3000 + 600 * j}\nG1 X${n(x0 + 1)} Y${n(7.5 + 3 * j)} F9000\n")
                b.append("; object end\n")
            }
        }
        if (diameterAtEnd) b.append("; filament_diameter = 1.75,1.75\n")
        return b.toString()
    }
    fun file(name: String, text: String): Path = dir.resolve(name).also { Files.writeString(it, text) }

    /** Both surveys of each whole-print view [req] asks for: from the index, and from reading the file again. */
    fun both(req: RenderRequest): List<Pair<SolidDrawing.Survey?, SolidDrawing.Survey>> {
        val ps = Projection.of(req)
        val is_ = IndexSurvey(req, ps)
        val index = GcodeIndex.read(req.file, is_)
        val ctx = Context(req, index, 1)
        return ps.map { p -> is_.survey(ctx, p) to SolidDrawing(ctx, p, 1).survey() }
    }
    fun fields(s: SolidDrawing.Survey) = listOf(s.a0, s.b0, s.a1, s.b1, s.lo, s.hi, s.count.toDouble(), s.spacing, s.hMin, s.zLo, s.zHi).map { it.toRawBits() }

    "the index's survey is the view's own, number for number" {
        val f = file("print.gcode", gcode())
        val views = listOf("front", "top", "left")
        var compared = 0
        for (c in listOf(null) + ColorBy.entries) for (lw in listOf(null, LineWidth.DECLARED)) {
            val reqs = listOf(
                RenderRequest(f, outside = views, threeD = true, threeDFrom = listOf(-35.0 to 30.0, 150.0 to -25.0), colorBy = c, lineWidth = lw),
                RenderRequest(f, threeD = true, colorBy = c, lineWidth = lw, onlyObject = listOf("right"), others = Others.FAINT),
                RenderRequest(f, threeD = true, colorBy = c, lineWidth = lw, onlyFeature = listOf("Sparse infill"), onlyTool = listOf(1)),
                RenderRequest(f, outside = listOf("front"), colorBy = c, lineWidth = lw, zRange = 0.5..1.4, bedWindow = doubleArrayOf(0.0, 0.0, 30.0, 30.0)),
            )
            for (r in reqs) for ((fromIndex, own) in both(r)) {
                (fromIndex != null) shouldBe true
                fields(fromIndex!!) shouldBe fields(own)
                compared++
            }
        }
        compared shouldBe (ColorBy.entries.size + 1) * 2 * (5 + 1 + 1 + 1)
        // a check that can fail: the views differ from each other, and the counts from a choosing to the next
        val (a, b) = both(RenderRequest(f, outside = listOf("front", "top"))).map { it.second }
        fields(a) shouldNotBe fields(b)
    }

    "a file that marks no layers: the same" {
        val f = file("bare.gcode", gcode(markers = false))
        for (c in listOf(ColorBy.SPEED, ColorBy.HEIGHT, ColorBy.LAYER_TIME, ColorBy.ORDER))
            for ((fromIndex, own) in both(RenderRequest(f, threeD = true, outside = listOf("front"), colorBy = c))) fields(fromIndex!!) shouldBe fields(own)
    }

    "what the index could not know while reading: nothing from it, and the view reads the file again" {
        // the diameter stated after the moves: the widths the index made without it would be wrong
        val late = file("late-diameter.gcode", gcode(diameterAtEnd = true))
        both(RenderRequest(late, threeD = true)).single().first shouldBe null
        // a layer whose thickness is declared after moves that declare none
        val lateH = file("late-height.gcode", gcode(lateHeight = true))
        both(RenderRequest(lateH, threeD = true)).single().first shouldBe null
        // control: the same files with the facts in place give the index's survey
        both(RenderRequest(file("early.gcode", gcode()), threeD = true)).single().first shouldNotBe null
    }
})
