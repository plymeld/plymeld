package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.core.IncrementalSlicer
import plymeld.mesh.StlReader
import java.io.StringWriter
import java.nio.file.Files

/**
 * Arc fitting must not change what is printed: the same file sliced with and without arcs,
 * read back by the independent reader (which expands arcs into chords), extrudes the same
 * filament and traces the same path length to within the fitting tolerance.
 */
class ArcFittingTest : StringSpec({
    "Benchy: arcs on vs off — same filament and path, fewer lines" {
        val soup = StlReader.read(testModelsDir().resolve("3DBenchy.stl"))
        fun slice(arcs: Boolean): String = StringWriter().also {
            IncrementalSlicer(listOf(soup), "test", "benchy").slice(testSetup("hardware.machine.tm.firmware.arcs" to arcs), it)
        }.toString()
        val on = slice(true); val off = slice(false)
        fun read(g: String): GcodeStats { val t = Files.createTempFile("arc", ".gcode"); try { Files.writeString(t, g); return GcodeReader.read(t) } finally { Files.deleteIfExists(t) } }
        val a = read(on); val b = read(off)
        val arcs = Regex("^G[23] ", RegexOption.MULTILINE).findAll(on).count()
        println("arc fitting (Benchy): ${arcs} arcs; lines ${off.lines().size} -> ${on.lines().size}; " +
            "filament %.2f vs %.2f mm; path %.1f vs %.1f mm".format(a.totalExtrudedMm, b.totalExtrudedMm, a.totalExtrudeMoveMm, b.totalExtrudeMoveMm))
        (arcs > 0) shouldBe true                                   // absence probe
        Regex("^G[23] ", RegexOption.MULTILINE).findAll(off).count() shouldBe 0   // control: off writes none
        a.totalExtrudedMm shouldBe (b.totalExtrudedMm plusOrMinus b.totalExtrudedMm * 1e-3)
        a.totalExtrudeMoveMm shouldBe (b.totalExtrudeMoveMm plusOrMinus b.totalExtrudeMoveMm * 1e-3)
        (on.lines().size < off.lines().size) shouldBe true
    }

    // The firmware's own arc piece (hardware.machine.firmware.arc_segment): an arc shorter than one would run as a single chord
    // across it, so none is written; the stretch keeps its own lines and the plastic is the same. Control: unset, some are.
    "Benchy: no arc shorter than the firmware's arc piece; control: unset, some are" {
        val soup = StlReader.read(testModelsDir().resolve("3DBenchy.stl"))
        fun slice(vararg extra: Pair<String, Any>): String = StringWriter().also {
            IncrementalSlicer(listOf(soup), "test", "benchy").slice(testSetup("hardware.machine.tm.firmware.arcs" to true, *extra), it)
        }.toString()
        /** Every arc's length (mm), from the head's place, its end, and its center (I, J). */
        fun arcLengths(g: String): List<Double> {
            val out = ArrayList<Double>(); var x = Double.NaN; var y = Double.NaN
            for (l in g.lines()) {
                if (!(l.startsWith("G1 ") || l.startsWith("G2 ") || l.startsWith("G3 ") || l.startsWith("G0 "))) continue
                val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.length > 1 }.associate { it[0] to it.substring(1).toDouble() }
                val nx = w['X'] ?: x; val ny = w['Y'] ?: y
                if ((l.startsWith("G2 ") || l.startsWith("G3 ")) && !x.isNaN()) {
                    val cx = x + (w['I'] ?: 0.0); val cy = y + (w['J'] ?: 0.0); val r = Math.hypot(x - cx, y - cy)
                    var sweep = Math.atan2(ny - cy, nx - cx) - Math.atan2(y - cy, x - cx)
                    if (l.startsWith("G3 ")) { if (sweep <= 0) sweep += 2 * Math.PI } else { if (sweep >= 0) sweep -= 2 * Math.PI }
                    out += r * Math.abs(sweep)
                }
                x = nx; y = ny
            }
            return out
        }
        fun read(g: String): GcodeStats { val t = Files.createTempFile("arc", ".gcode"); try { Files.writeString(t, g); return GcodeReader.read(t) } finally { Files.deleteIfExists(t) } }
        val plain = slice(); val held = slice("hardware.machine.tm.firmware.arc_segment" to "1 mm")
        val a = arcLengths(plain); val b = arcLengths(held)
        println("arcs: %d unset (%d under 1 mm), %d at a 1 mm piece".format(a.size, a.count { it < 1.0 }, b.size))
        (a.count { it < 1.0 - 1e-3 } > 0) shouldBe true          // control: unset, short arcs are written
        (b.isNotEmpty()) shouldBe true
        b.all { it >= 1.0 - 1e-3 } shouldBe true
        read(held).totalExtrudedMm shouldBe (read(plain).totalExtrudedMm plusOrMinus read(plain).totalExtrudedMm * 1e-3)
    }
})
