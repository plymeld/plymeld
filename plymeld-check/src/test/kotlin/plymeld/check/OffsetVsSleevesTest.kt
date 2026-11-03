package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.geom.Booleans
import plymeld.geom.Offset
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import java.util.Locale

/**
 * The fast offset against the independent sleeve method on real layers (the two-color Benchy's
 * halves) at wall-like distances. While fixing the offset: a mixed ring of trimmed
 * corners and loops, trimmed edges pushed past their own extent, and a zero-length edge each
 * produced 0.9-2.0 mm² of stray geometry on single layers, and put filament paths outside their
 * areas (MultiFilamentTest). Most layers now differ by 0.01-0.05 mm² (the 1 µm jitter tolerance);
 * the worst, 0.75 mm², is part A layer 40, where the stern hole's outline comes out of the slicer
 * as a tangle of fragments (the former offset differed there by 0.35 mm²).
 */
class OffsetVsSleevesTest : StringSpec({
    "fast offset agrees with the sleeve method on Benchy layers" {
        val parts = benchyDualParts()
        val all = parts.fold(FloatArray(0)) { acc, p -> acc + p.coords }.let { plymeld.mesh.TriangleSoup(it, parts.sumOf { p -> p.count }) }
        val b = all.bounds()
        val plan = LayerPlan.fixed(b[2], b[5], 0.2, 0.2)
        val layers = parts.map { p -> Slicer.slice(p, plan.slices).map { it.loops } }
        var worst = 0.0; var where = ""
        for (part in layers.indices) for (i in listOf(40, 76, 128, 136, 141, 143, 144, 145, 148, 150, 152, 153, 161)) {
            if (i >= layers[part].size) continue
            val region = Booleans.normalize(layers[part][i])
            if (region.isEmpty()) continue
            for (delta in listOf(-210L, -630L, 210L)) {
                val diff = Booleans.xor(Offset.offset(region, delta), Offset.offsetBySleeves(region, delta)).sumOf { Math.abs(it.signedAreaMm2()) }
                if (diff > worst) { worst = diff; where = "part $part layer $i delta $delta" }
            }
        }
        println("offset vs sleeves: worst %.3f mm² (%s)".format(Locale.ROOT, worst, where))
        (worst < 1.0) shouldBe true
    }
})
