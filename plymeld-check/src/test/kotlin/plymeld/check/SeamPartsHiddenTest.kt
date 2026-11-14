package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.Feature
import plymeld.core.MultiFilament
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * Least-visible seams on an object of parts in two filaments: an inlay part set into a notch in the back of a base part.
 * The inlay's front edge, facing the viewer, is pressed against the base and covered by it: it hides a seam. The inlay
 * alone sees that edge as an open wall facing the viewer, the most visible place on it. Each part's seams were placed
 * seeing that part alone, so on a two-color nameplate the inlaid lettering's seams went to its edges on the visible face
 * (587 of 1,715) rather than its edges against the base (205 left once every part was counted). Control: the inlay with no
 * base puts no seam on that edge. With scarf seams on, a base's seam in a pocket the inlay fills: kept out of a pocket
 * shorter than the ramp where covered seams are scarfed too (plan.technique.scarf_seam.covered = scarf), hidden in it as a
 * point seam by default (each the other's control).
 */
class SeamPartsHiddenTest : StringSpec({
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    // the base 40 x 20 mm, its front face at y 0 (the viewer's side), a notch x 15..25 and 3 mm deep in its back face (y 20)
    val base = Polygon(longArrayOf(0, 0, 40_000, 0, 40_000, 20_000, 25_000, 20_000, 25_000, 17_000, 15_000, 17_000, 15_000, 20_000, 0, 20_000))
    val inlay = rect(15_000, 17_000, 25_000, 20_000)
    val n = 30
    val tops = LongArray(n) { 200L * (it + 1) }
    val s = SliceSettings.of(testSetup("plan.seam" to "least_visible"))

    /** Per layer from the third up, where the inlay's outer wall starts (x, y in mm). */
    fun inlaySeams(withBase: Boolean): List<Pair<Double, Double>> {
        val parts = if (withBase) listOf(List(n) { listOf(base) }, List(n) { listOf(inlay) }) else listOf(List(n) { emptyList() }, List(n) { listOf(inlay) })
        val r = MultiFilament.plan(s, parts, listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val layers = r.filaments.single { it.spool == "spool 2" }.layers
        return (2 until n).map { i ->
            val wall = layers[i].paths.single { it.feature == Feature.OUTER_WALL }
            wall.xy[0] / 1000.0 to wall.xy[1] / 1000.0
        }
    }
    /** On the inlay's front edge (y 17, against the base when there is one). */
    fun onCoveredEdge(p: Pair<Double, Double>) = p.second < 17.6

    "the inlay's seams go to its edge the base covers; control: with no base, none there" {
        val inlaid = inlaySeams(withBase = true)
        val alone = inlaySeams(withBase = false)
        println("inlay seams on its front edge: with the base ${inlaid.count(::onCoveredEdge)} of ${inlaid.size}, alone ${alone.count(::onCoveredEdge)}; first ${inlaid.first()} / ${alone.first()}")
        inlaid.count(::onCoveredEdge) shouldBe inlaid.size
        alone.count(::onCoveredEdge) shouldBe 0
    }

    // a scarf seam is seen along its ramp: a base with a 4 x 2 mm pocket in its front face, an inlay of the other filament in
    // it (a letter in a nameplate's face). A point seam may hide in the pocket, against the inlay; a 10 mm scarf's ramp from
    // there runs out of the 8 mm of pocket walls onto the face, so where a seam the inlay covers is scarfed too
    // (plan.technique.scarf_seam.covered = scarf) it stays out of the pocket. By default (point) a seam the inlay covers is
    // laid as a point seam, and costed as one: it hides in the pocket, with no ramp
    val pocketBase = Polygon(longArrayOf(0, 0, 18_000, 0, 18_000, 2_000, 22_000, 2_000, 22_000, 0, 40_000, 0, 40_000, 20_000, 0, 20_000))
    val letter = rect(18_000, 0, 22_000, 2_000)
    /** Per layer from the third up, the base's outer wall: where it starts (x, y in mm), and its scarf's length (µm; 0 = a point seam). */
    fun baseSeams(scarf: Boolean, vararg extra: Pair<String, Any>): List<Triple<Double, Double, Long>> {
        val st = SliceSettings.of(testSetup("plan.seam" to "least_visible", "plan.technique.scarf_seam.enabled" to scarf,
            *(if (scarf) arrayOf<Pair<String, Any>>("plan.technique.scarf_seam.where" to "all", "plan.technique.scarf_seam.length" to "10 mm") else emptyArray()), *extra))
        val r = MultiFilament.plan(st, listOf(List(n) { listOf(pocketBase) }, List(n) { listOf(letter) }), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1")
        val layers = r.filaments.single { it.spool == "spool 1" }.layers
        return (2 until n).map { i -> val w = layers[i].paths.first { it.feature == Feature.OUTER_WALL }; Triple(w.xy[0] / 1000.0, w.xy[1] / 1000.0, w.scarfUm) }
    }
    /** In the pocket: its walls and floor, x 17.5..22.5 and less than 2.5 mm in from the front face. */
    fun inPocket(p: Triple<Double, Double, Long>) = p.first in 17.5..22.5 && p.second < 2.5

    "a scarf seam's ramp is seen: with covered seams scarfed too, no base seam in a pocket shorter than the ramp; control: point seams may hide there" {
        val scarfed = baseSeams(scarf = true, "plan.technique.scarf_seam.covered" to "scarf")
        val points = baseSeams(scarf = false)
        println("base seams in the pocket: scarf ${scarfed.count(::inPocket)} of ${scarfed.size} (first ${scarfed.first()}), point ${points.count(::inPocket)} (first ${points.first()})")
        scarfed.count(::inPocket) shouldBe 0
        (points.count(::inPocket) > 0) shouldBe true
    }

    "a seam the inlay covers is costed as the point seam it is laid as: by default the base's seams hide in the pocket, with no ramp; control: covered = scarf, none there (above)" {
        val covered = baseSeams(scarf = true)
        println("base seams in the pocket, scarf seams on, covered = point: ${covered.count(::inPocket)} of ${covered.size} (first ${covered.first()}); scarfed there ${covered.filter(::inPocket).count { it.third > 0 }}")
        (covered.count(::inPocket) > covered.size / 2) shouldBe true
        covered.filter(::inPocket).filter { it.third > 0 }.shouldBeEmpty()
    }
})
