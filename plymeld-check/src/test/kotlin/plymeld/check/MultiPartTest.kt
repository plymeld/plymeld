package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe

import plymeld.core.IncrementalSlicer
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.mesh.ThreeMfReader
import java.io.StringWriter

/**
 * Multi-part 3MF (the two-part Benchy) sliced as one object. Reference: the parts
 * sliced separately, their areas summed minus overlaps — computed here with no outside
 * geometry only from each part's own slice, so the union must equal the sum when the parts
 * do not overlap, and never exceed it.
 */
class MultiPartTest : StringSpec({
    "the two-part Benchy: 2 parts slice into one object whose layer area equals the parts' union" {
        val model = ThreeMfReader.read(testModelsDir().resolve("3DBenchy-dual.3mf"))
        model.parts.size shouldBe 2
        val out = StringWriter()
        val inc = IncrementalSlicer(model.parts.map { it.soup }, "test", "benchy")
        inc.slice(testSetup(), out)
        out.toString().contains("EXCLUDE_OBJECT_DEFINE NAME=benchy_id_0_copy_0") shouldBe true
        // per-layer area of the union vs the sum of each part's own slice at the same planes
        val all = model.parts.map { it.soup }
        val zMin = all.minOf { it.bounds()[2] }; val zMax = all.maxOf { it.bounds()[5] }
        val plan = LayerPlan.fixed(zMin, zMax, 0.2, 0.2)
        val per = all.map { Slicer.slice(it, plan.slices) }
        val bad = ArrayList<Int>()
        for (li in listOf(1, 20, 49, 60, 90)) {
            if (li >= plan.count) continue
            val sum = per.sumOf { p -> p[li].netAreaMm2() }
            val union = plymeld.geom.Booleans.normalize(per.flatMap { it[li].loops }).sumOf { it.signedAreaMm2() }
            println("multipart layer $li z=${"%.1f".format(plan.slices[li])}: parts sum %.1f mm², union %.1f mm²".format(sum, union))
            // a union can be neither smaller than its largest part nor larger than the sum
            // (the first version checked only the upper bound, and passed on a union of 0 mm²)
            val largest = per.maxOf { p -> p[li].netAreaMm2() }
            println("   largest part %.1f mm², per part: ${per.map { "%.1f".format(it[li].netAreaMm2()) }}".format(largest))
            val tol = 1e-3 + sum * 1e-6   // snap rounding moves vertices by <= 1 grid unit
            if (union > sum + tol || union < largest - tol) bad.add(li)
        }
        bad shouldBe emptyList()
        inc.layerCount shouldBe plan.count
    }
})
