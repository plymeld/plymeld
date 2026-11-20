package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.check.catalog.Catalog
import plymeld.check.catalog.Models
import plymeld.check.catalog.Slicing
import plymeld.core.FilamentAreas
import plymeld.geom.Booleans
import plymeld.geom.Polygon
import plymeld.mesh.Slicer
import plymeld.model.SliceSettings

/**
 * Slivers ending inside a second part's area (the catalog's Models.sliverTips): one part's lines laid over each other where
 * they converge at the tip of a thin sliver of the other's.
 *  - A tongue of B too thin for a line goes over to A (FilamentAreas.handOverSlivers) with the gap between it and A's slot
 *    (5 µm a side): nothing of a slot is left to no area. Control: the tongue handed over alone leaves the gap, a crack in A's
 *    area along each tongue that A's walls loop round, laying plastic twice there.
 *  - The model sliced: the pre-print check passes, and no spot laid twice lies along a tongue. Spots are left at the spikes
 *    (B's loops crossing a spike's tip: open, DECISIONS 50); the catalog's baseline records them.
 */
class SliverTipTest : StringSpec({
    val s = SliceSettings.of(testSetup(*Slicing.WALLS.values.toTypedArray()))
    val zMid = 0.6
    val cuts = Models.sliverTips.map { Slicer.slice(it, doubleArrayOf(zMid))[0].loops }
    fun split(bridge: Boolean) = FilamentAreas.split(cuts, listOf("spool 1", "spool 2"), listOf(0, 1), s.sliverHandOverUm, bridgeGap = bridge)
    fun mm2(p: List<Polygon>) = p.sumOf { it.signedAreaMm2() }
    fun box(x0: Double, y0: Double, x1: Double, y1: Double) = listOf(Polygon(longArrayOf(um(x0), um(y0), um(x1), um(y0), um(x1), um(y1), um(x0), um(y1))))
    /** Per tongue, the area (mm²) of its slot's box that no area holds. */
    fun unheld(sp: FilamentAreas.Split) = Models.TONGUES.map { t ->
        val b = box(t.x - t.width / 2 - 0.02, Models.CY + 2.0, t.x + t.width / 2 + 0.02, Models.CY + 2.0 + t.length + 0.01)
        mm2(b) - sp.bySpool.values.sumOf { mm2(Booleans.intersection(it, b)) }
    }

    "a tongue handed over takes the gap along; control: handed over alone it leaves a crack in the neighbor's area" {
        // each tongue is narrower than the hand-over width: it goes over (none of B is left in its slot)
        val bridged = split(true)
        for (t in Models.TONGUES) mm2(Booleans.intersection(bridged.bySpool.getValue("spool 2"),
            box(t.x - 0.1, Models.CY + 2.05, t.x + 0.1, Models.CY + 2.0 + t.length))) shouldBe 0.0
        val left = unheld(bridged); val control = unheld(split(false))
        println("unheld per slot (mm2): bridged $left; control $control")
        left.forEach { (it < 1e-4) shouldBe true }
        // control: the gaps, 5 µm a side along each tongue
        control.forEach { (it > 0.002) shouldBe true }
    }

    "the model sliced: the check passes, and nothing is laid twice along a tongue" {
        val r = Slicing.sliceCheck(Catalog.write(Catalog.byName("sliver tips inside a second part")))
        r.problems shouldBe emptyList()
        println("laid twice: ${r.laidTwice.words()}")
        // the spots left are at the spikes (open: B's inner line meeting its outer loop's dip under a spike), none at a tongue
        val atTongue = r.laidTwice.layers.flatMap { l -> l.reading.spots.map { l.z to it } }.filter { (_, s) -> Models.TONGUES.any { t ->
            Math.abs(s.x - t.x) < 0.3 && s.y > Models.CY + 1.8 && s.y < Models.CY + 2.1 + t.length } }
        atTongue.forEach { (z, s) -> println("at a tongue: z $z ${s.x} ${s.y} ${s.widthMm} ${s.areaMm2} ${s.lines()}") }
        atTongue shouldBe emptyList()
    }
})

private fun um(mm: Double) = Math.round(mm * 1000)
