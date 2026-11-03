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
})
