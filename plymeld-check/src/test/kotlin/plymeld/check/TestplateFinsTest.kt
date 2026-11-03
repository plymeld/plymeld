package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.core.Walls
import plymeld.mesh.LayerPlan
import plymeld.mesh.Slicer
import plymeld.mesh.StlReader
import plymeld.model.Context
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * The testplate's thin fins (0.5/0.8/1.2/1.6 x 12 mm), sliced from the
 * test model's mesh with its settings, through Walls.arachne on the WHOLE layer. With Arachne on,
 * the 0.5 fin printed nothing and the 0.8 fin half a loop (testplate z 6.4): the outer
 * band counted every area without a loop as covered, as soon as the layer had a loop anywhere, so
 * a fin on its own printed fine. Every fin must get beads along its whole length.
 */
class TestplateFinsTest : StringSpec({
    "testplate fins: each printed along its whole length by variable-width walls" {
        val setup = testSetup()
        val s = SliceSettings.of(setup, Context(0))
        val soup = StlReader.read(testModelsDir().resolve("testplate.stl"))
        val b = soup.bounds()
        val plan = LayerPlan.fixed(b[2], b[5], s.firstLayerHeightUm / 1000.0, s.layerHeightUm / 1000.0)
        val li = plan.tops.indices.first { Math.abs(plan.tops[it] - b[2] - 6.4) < 1e-6 }
        // as the slicer's clean-up stage: simplify, close, simplify
        val loops = Slicer.slice(soup, doubleArrayOf(plan.slices[li])).single().loops.map { it.douglasPeucker(s.resolutionUm) }
        val cleaned = if (s.closingRadiusUm > 0) plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(loops, s.closingRadiusUm, plymeld.geom.Offset.Join.ROUND), -s.closingRadiusUm, plymeld.geom.Offset.Join.ROUND)
            .map { it.douglasPeucker(s.resolutionUm) } else loops
        println("settings: loops %d, wo %d, wi %d, h %d, min bead %d, min feature %d, min length factor %.2f, transition %d".format(Locale.ROOT,
            s.wallLoops, s.outerWallWidthUm, s.innerWallWidthUm, s.layerHeightUm, s.minBeadUm, s.minFeatureUm, s.minLengthFactor, s.transitionLengthUm))
        fun box(xy: LongArray) = longArrayOf((0 until xy.size / 2).minOf { xy[2 * it] }, (0 until xy.size / 2).minOf { xy[2 * it + 1] },
            (0 until xy.size / 2).maxOf { xy[2 * it] }, (0 until xy.size / 2).maxOf { xy[2 * it + 1] })
        // the fins: loops about 12 mm long in y and under 2 mm wide
        val fins = cleaned.map { box(it.xy) }.filter { f -> f[3] - f[1] in 11_000..13_000 && f[2] - f[0] < 2_000 }.sortedBy { it[2] - it[0] }
        fins.size shouldBe 4
        // control: the rest of the layer must have loops, or the bug this guards cannot show
        val whole = Walls.arachne(cleaned, s.wallLoops, s.outerWallWidthUm, s.innerWallWidthUm, s.layerHeightUm, s.minBeadUm, s.minFeatureUm, s.minLengthFactor, s.transitionLengthUm)
        val loopsElsewhere = whole.walls.flatten().count { p -> val q = box(p.xy); fins.none { f -> q[0] >= f[0] - 100 && q[2] <= f[2] + 100 } }
        println("layer z 6.4: %d loop(s) away from the fins".format(Locale.ROOT, loopsElsewhere))
        (loopsElsewhere > 0) shouldBe true
        for (f in fins) {
            // the y spans of every loop and center line on this fin, merged
            val on = { q: LongArray -> q[0] >= f[0] - 100 && q[2] <= f[2] + 100 && q[1] >= f[1] - 100 && q[3] <= f[3] + 100 }
            val spans = (whole.walls.flatten().map { box(it.xy) } + whole.center.map { box(it.xy) }).filter(on).map { longArrayOf(it[1], it[3]) }.sortedBy { it[0] }
            var covered = 0L; var lo = Long.MIN_VALUE; var hi = Long.MIN_VALUE
            for (sp in spans) { if (sp[0] > hi) { if (hi > lo) covered += hi - lo; lo = sp[0]; hi = sp[1] } else hi = maxOf(hi, sp[1]) }
            if (hi > lo) covered += hi - lo
            println("fin %d um wide: %d bead path(s), %d of %d um covered".format(Locale.ROOT, f[2] - f[0], spans.size, covered, f[3] - f[1]))
            (covered > (f[3] - f[1]) - 1_500) shouldBe true
        }
    }
})
