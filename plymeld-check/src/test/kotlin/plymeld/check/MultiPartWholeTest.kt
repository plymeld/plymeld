package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.MultiFilament
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Parts of one object that touch, on synthetic outlines (no mesh): what one part's planning reads
 * about the WHOLE object must not depend on where the other part's walls or edges fall.
 * Found on a multi-part model against Flash Studio.
 */
class MultiPartWholeTest : StringSpec({
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    fun hole(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x0, y1, x1, y1, x1, y0))
    fun len(layers: List<LayerPlanOut>, f: Feature, range: IntRange) = range.sumOf { i -> layers[i].paths.filter { it.feature == f }.sumOf { p ->
        (0 until p.xy.size / 2 - 1).sumOf { k -> Math.hypot((p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(), (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()) } } } / 1000.0

    // A 40 mm square ring (hole 21 mm) around a 20 mm core, 0.5 mm of air between them (a shell
    // part and its core part): straight sides, 40 layers. The core is an island of its own. The object has 4 walls, the core its own 2. The vertical-shell cover test
    // once read reference walls built with the OBJECT's 4 loops, so two wall widths of the core's
    // infill counted as uncovered and turned solid on every layer (a ring ~0.8 mm wide from z 0 to 24
    // in the model it was found on).
    "a part with fewer walls than the object gets no vertical-shell solid on straight sides" {
        val s = SliceSettings.of(testSetup("plan.layer.walls.thickness" to "1.77 mm"))
        val core2 = SliceSettings.of(testSetup("plan.layer.walls.thickness" to "0.87 mm"))
        val n = 40
        val tops = LongArray(n) { 200L * (it + 1) }
        val ring = List(n) { listOf(rect(0, 0, 40_000, 40_000), hole(9_500, 9_500, 30_500, 30_500)) }
        val core = List(n) { listOf(rect(10_000, 10_000, 30_000, 30_000)) }
        val units = listOf("spool 1", "spool 1 / core")
        fun coreSolid(settings: Map<String, SliceSettings>) = MultiFilament.plan(s, listOf(ring, core), listOf("spool 1", "spool 1"), listOf(0, 1), tops, "spool 1", units, settings)
            .filaments.single { it.unit == "spool 1 / core" }.layers.let { len(it, Feature.SOLID, 12..27) }
        val own = coreSolid(mapOf("spool 1 / core" to core2))
        val same = coreSolid(emptyMap())                                   // control: the core with the object's 4 walls
        println("core internal solid, layers 12-27: %.1f mm with its own 2 walls, %.1f mm with the object's 4".format(Locale.ROOT, own, same))
        same shouldBe 0.0
        own shouldBe 0.0
    }

    // A base whose bottom 3 layers have square pockets, filled by a second part's pixels that stop
    // 1 um short of each pocket wall: the seam two separately sliced parts leave where they touch
    // (a QR code inlaid in a base). The first layer above rests on solid material everywhere.
    // The seams once read as "exposed from below" and, grown two nozzle widths as bridge anchors,
    // became 1,393 mm2 of bridge over that QR code.
    "hairline seams between touching parts are not bridged over" {
        val s = SliceSettings.of(testSetup())
        val n = 12
        val tops = LongArray(n) { 200L * (it + 1) }
        val pockets = (0 until 5).flatMap { a -> (0 until 5).map { b -> longArrayOf(4_000L + a * 6_000, 4_000L + b * 6_000) } }
        val base = List(n) { i -> if (i < 3) listOf(rect(0, 0, 32_000, 32_000)) + pockets.map { (x, y) -> hole(x, y, x + 3_000, y + 3_000) } else listOf(rect(0, 0, 32_000, 32_000)) }
        val pixels = List(n) { i -> if (i < 3) pockets.map { (x, y) -> rect(x + 1, y + 1, x + 2_999, y + 2_999) } else emptyList() }
        val r = MultiFilament.plan(s, listOf(base, pixels), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val bridge = r.filaments.sumOf { len(it.layers, Feature.BRIDGE, 3..3) }
        println("bridge on the first layer above the pixels: %.1f mm".format(Locale.ROOT, bridge))
        bridge shouldBe 0.0
    }
})
