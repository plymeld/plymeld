package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.decide.Control
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.Pose
import plymeld.core.decide.PoseDecision
import plymeld.core.decide.PoseFacts
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Weights
import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang

/**
 * The bridging limit (item 7): a beam on two posts 30 mm apart is a bridge. With the filament's
 * `bridge.span.max` at 40 mm it bridges, and material-heavy weights support nothing; at 20 mm its middle needs support,
 * which is required whatever the weights, and the explain says so.
 */
class BridgeSpanTest : StringSpec({
    val soup = beamOnPosts()
    val range = Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Continuous(0.1, 0.3), "test")
    fun facts(spanMm: Double?) = PoseFacts(0.0, 0.0, 270.0, 270.0, 256.0, Overhang.fromAngle(30.0, 0.2, 0.4), "test", Weights.parse("quality=1,material=50", "test"), null,
        layerHeightMm = 0.2, flowMaxMm3s = 18.0, minLayerTimeS = 8.0, firstLayerMm = 0.2, minLayerMm = 0.1, maxLayerMm = 0.3,
        layerHeightCandidates = FlatLayers.coarse(range, 0.2, 3), layerHeightControl = range, layerHeightBudget = 3, baselineLayerMm = 0.2, baselineWhat = "test",
        nozzleHeightMm = 20.0, nozzleHeightSource = "test", travelSpeedMmS = 300.0, bridgeMaxSpanMm = spanMm)
    fun decide(spanMm: Double?) = PoseDecision.decideWithTechnique(listOf(soup), facts(spanMm), listOf(PrintTechnique.FLAT_LAYERS to null), Pose.asGiven(), heights = FlatLayers.Heights.ONE)

    "within the filament's span (40 mm) the beam bridges: material-heavy weights support nothing" {
        decide(40.0).technique.value.support?.chosen.orEmpty().size shouldBe 0
    }
    "past the filament's span (20 mm) the beam's middle needs support: required whatever the weights, and the explain says so" {
        val d = decide(20.0)
        d.technique.value.support!!.chosen.size shouldBe 1
        d.explain.text() shouldContain "a bridge spanning about 30 mm, past the filament's bridging limit of 20 mm"
    }
    "control: with no span known, any bridge passes" {
        decide(null).technique.value.support?.chosen.orEmpty().size shouldBe 0
    }
})

/** Two posts 10 x 10 x 20 mm at x 0..10 and 40..50, a beam 50 x 10 x 5 mm across their tops: its underside between them is a 30 mm bridge. */
internal fun beamOnPosts(): TriangleSoup {
    val t = ArrayList<Float>()
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { for (v in listOf(a, b, c)) for (x in v) t.add(x.toFloat()) }
    fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double) {
        val v = listOf(p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1))
        for (f in listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))) tri(v[f[0]], v[f[1]], v[f[2]])
    }
    box(0.0, 10.0, 0.0, 10.0, 0.0, 20.0); box(40.0, 50.0, 0.0, 10.0, 0.0, 20.0); box(0.0, 50.0, 0.0, 10.0, 20.0, 25.0)
    return TriangleSoup(t.toFloatArray(), t.size / 9)
}
