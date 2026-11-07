package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Control
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Weights
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/**
 * The pose search's screen: a pose whose best possible score cannot reach the best candidates is left without its
 * estimates (and its overhangs unsurveyed). On a trophy (a wide base, a tall column on it) under quality-heavy weights,
 * standing upright needs no support, while upside down or on its side much of it needs support: those are ruled out.
 *
 * The check against the search without the screen, which measures every pose: the same choice; and every pose the screen
 * ruled out scores, in every candidate measured there, no better than the bound the screen gave it (the bound is a lower
 * bound, or it could rule out a pose that wins). Absence is probed: the screen rules out at least one pose, and the
 * search without it measured candidates in at least one of those.
 */
class PoseScreenTest : StringSpec({
    val soup = trophy()
    val range = Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Continuous(0.1, 0.3), "test: the tool's thinnest and thickest layer")
    fun facts(screen: Boolean) =
        PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", Weights.parse("quality=100", "test"), null,
            layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.1, maxLayerMm = 0.3,
            layerHeightCandidates = FlatLayers.coarse(range, 0.2, 3), layerHeightControl = range, layerHeightBudget = 3, baselineLayerMm = 0.2, baselineWhat = "test",
            screenPoses = screen)
    val flat = listOf(PrintTechnique.FLAT_LAYERS to null)
    val screened = PoseDecision.decideWithTechnique(listOf(soup), facts(true), flat, heights = FlatLayers.Heights.ONE).pose
    val unscreened = PoseDecision.decideWithTechnique(listOf(soup), facts(false), flat, heights = FlatLayers.Heights.ONE).pose
    val bound = Regex("its best possible score, (-?[0-9.]+)")
    val score = Regex("(?:^|; )score (-?[0-9.]+)")

    "poses that cannot reach the best candidates are ruled out, said in the facts and each one's reason; the choice is the same as without the screen" {
        val ruled = screened.alternatives.filter { it.rejected?.startsWith("ruled out before its strength and time estimates") == true }
        ruled.size shouldBeGreaterThan 0
        screened.facts.single { it.startsWith("search:") } shouldContain "ruled out by their best possible score"
        screened.why shouldBe unscreened.why
        // control: without the screen nothing is ruled out
        unscreened.alternatives.none { it.rejected?.startsWith("ruled out") == true } shouldBe true
    }

    "every pose ruled out scores, in each candidate the search without the screen measured there, no better than its bound" {
        val ruled = screened.alternatives.filter { it.rejected?.startsWith("ruled out before") == true }
            .associate { it.label.substringBefore(" · ") to bound.find(it.rejected!!)!!.groupValues[1].toDouble() }
        var compared = 0
        for ((pose, b) in ruled) {
            val scores = unscreened.alternatives.filter { it.label.substringBefore(" · ") == pose }.mapNotNull { score.find(it.score)?.groupValues?.get(1)?.toDouble() }
            for (s in scores) { compared++; withClue("'$pose': a candidate scores $s, below the bound $b the screen ruled it out by") { (s >= b - 1e-3) shouldBe true } }
        }
        // the comparison is not empty: the search without the screen scored candidates in the poses ruled out
        compared shouldBeGreaterThan 0
    }
})

/**
 * A trophy: a base 40 x 40 mm and 4 mm thick, a column 8 x 8 mm on its middle up to 40 mm. Upright nothing overhangs;
 * upside down the base's underside ring (1536 mm²) faces down 36 mm up; on a side the column hangs 16 mm over the bed.
 * Outward winding.
 */
internal fun trophy(): TriangleSoup {
    val t = ArrayList<Float>()
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { for (v in listOf(a, b, c)) for (x in v) t.add(x.toFloat()) }
    fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { tri(a, b, c); tri(a, c, d) }
    fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    fun walls(r: Double, z0: Double, z1: Double) {
        quad(p(-r, -r, z0), p(r, -r, z0), p(r, -r, z1), p(-r, -r, z1))      // -y
        quad(p(r, r, z0), p(-r, r, z0), p(-r, r, z1), p(r, r, z1))          // +y
        quad(p(-r, r, z0), p(-r, -r, z0), p(-r, -r, z1), p(-r, r, z1))      // -x
        quad(p(r, -r, z0), p(r, r, z0), p(r, r, z1), p(r, -r, z1))          // +x
    }
    // the base: its bottom (facing down), its walls, its top round the column (facing up)
    quad(p(-20.0, -20.0, 0.0), p(-20.0, 20.0, 0.0), p(20.0, 20.0, 0.0), p(20.0, -20.0, 0.0))
    walls(20.0, 0.0, 4.0)
    quad(p(-20.0, -20.0, 4.0), p(20.0, -20.0, 4.0), p(20.0, -4.0, 4.0), p(-20.0, -4.0, 4.0))
    quad(p(-20.0, 4.0, 4.0), p(20.0, 4.0, 4.0), p(20.0, 20.0, 4.0), p(-20.0, 20.0, 4.0))
    quad(p(-20.0, -4.0, 4.0), p(-4.0, -4.0, 4.0), p(-4.0, 4.0, 4.0), p(-20.0, 4.0, 4.0))
    quad(p(4.0, -4.0, 4.0), p(20.0, -4.0, 4.0), p(20.0, 4.0, 4.0), p(4.0, 4.0, 4.0))
    // the column: its walls and its top
    walls(4.0, 4.0, 40.0)
    quad(p(-4.0, -4.0, 40.0), p(4.0, -4.0, 40.0), p(4.0, 4.0, 40.0), p(-4.0, 4.0, 40.0))
    return TriangleSoup(t.toFloatArray(), t.size / 9)
}
