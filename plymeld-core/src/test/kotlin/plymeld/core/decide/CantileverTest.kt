package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import plymeld.mesh.StlReader
import plymeld.mesh.TriangleSoup

/**
 * The hard check before the weights: an overhang region's lowest layer read against the section below it.
 * A box sticking out of a bigger box's side is a cantilever; a beam on two posts is a bridge; the beam with one post
 * taken away is a cantilever again (the control).
 */
class CantileverTest : StringSpec({
    /** An ASCII STL box [x0]..[x1], [y0]..[y1], [z0]..[z1], outward normals. */
    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double): String {
        val v = listOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val faces = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val sb = StringBuilder("solid box\n")
        for (f in faces) { sb.append("facet normal 0 0 0\nouter loop\n"); for (i in f) sb.append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); sb.append("endloop\nendfacet\n") }
        return sb.append("endsolid box\n").toString()
    }
    val dir = java.nio.file.Files.createTempDirectory("plymeld-cantilever")
    fun soup(vararg stl: String): TriangleSoup = PoseDecision.merged(stl.mapIndexed { i, t ->
        StlReader.read(dir.resolve("part$i.stl").also { java.nio.file.Files.writeString(it, t) }) })
    fun region(z0: Double, z1: Double, x0: Double, x1: Double, y0: Double, y1: Double) = Coordination.Volume.Region(z0, z1, x0, x1, y0, y1)
    val origin = doubleArrayOf(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)

    "a box sticking out of a bigger box's side is a cantilever: it reaches its whole 10 mm over air, anchored on one side" {
        // the big box 0..20 all round; the small one on its +x side, 5 mm up, sticking out to x 30
        val s = soup(box(0.0, 20.0, 0.0, 20.0, 0.0, 20.0), box(20.0, 30.0, 5.0, 15.0, 5.0, 15.0))
        val r = Cantilever.reach(s, origin, region(5.0, 5.0, 20.0, 30.0, 5.0, 15.0), 0.2)!!
        r.reachMm shouldBe (10.0 plusOrMinus 0.3)
        r.bridged shouldBe false
    }
    "a beam on two posts is a bridge: it reaches 15 mm over air at the middle, anchored on both sides" {
        val s = soup(box(0.0, 10.0, 0.0, 10.0, 0.0, 20.0), box(40.0, 50.0, 0.0, 10.0, 0.0, 20.0), box(0.0, 50.0, 0.0, 10.0, 20.0, 25.0))
        val r = Cantilever.reach(s, origin, region(20.0, 20.0, 10.0, 40.0, 0.0, 10.0), 0.2)!!
        r.reachMm shouldBe (15.0 plusOrMinus 0.3)
        r.bridged shouldBe true
    }
    "control: the beam with one post taken away is a cantilever (seen to fail with the anchor search reading one hit as a bridge)" {
        val s = soup(box(0.0, 10.0, 0.0, 10.0, 0.0, 20.0), box(0.0, 50.0, 0.0, 10.0, 20.0, 25.0))
        val r = Cantilever.reach(s, origin, region(20.0, 20.0, 10.0, 50.0, 0.0, 10.0), 0.2)!!
        r.reachMm shouldBe (40.0 plusOrMinus 0.3)
        r.bridged shouldBe false
    }
    "a region whose bottom lies within the first layer stands on the bed: nothing to read" {
        val s = soup(box(0.0, 20.0, 0.0, 20.0, 0.0, 20.0))
        Cantilever.reach(s, origin, region(0.1, 0.1, 0.0, 20.0, 0.0, 20.0), 0.2) shouldBe null
    }
})
