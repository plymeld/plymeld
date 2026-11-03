package plymeld.core.decide

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.mesh.TriangleSoup

/**
 * Support's survey leaves out an overhang region the generator lays no support in: one whose faces, seen from below, are
 * narrower everywhere than the narrowest column it lays (Support.columnWidthMm; the generator's speck filter,
 * Supports.dropSpecks, drops such faces). Found on the two-color Benchy: a cluster of 0.0 mm² of faces lying
 * across a 1.67 x 1.62 mm box passed the box's test (Support.minRegionWidthMm, 1.60 mm), was planned as a support spot,
 * and the slice printed no support in it (SupportCheck refused the file).
 */
class OverhangSurveyTest : StringSpec({
    val box = 1.6      // a tip with the XY gap on both sides (the bundled 0.8 + 2 x 0.4): the box's test
    val column = 0.8   // a tip (twice the bundled 0.4 mm support line): the footprint's test
    /** [faces] (each three x, y, z corners, facing down) over a floor triangle away from them, every face needing support. */
    fun survey(faces: List<FloatArray>, columnWidth: Double, boxWidth: Double = box): List<OverhangRegion> {
        val floor = floatArrayOf(10f, 0f, 0f, 11f, 0f, 0f, 10f, 1f, 0f)   // facing up, not under the faces: they stand on the bed
        val coords = (faces + listOf(floor)).fold(FloatArray(0)) { a, f -> a + f }
        val p = TriangleSoup(coords, faces.size + 1)
        val needs = BooleanArray(p.count) { it < faces.size }
        val area = DoubleArray(p.count) { i ->
            val o = 9 * i; val c = p.coords
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            Math.sqrt(nx * nx + ny * ny + nz * nz) / 2
        }
        val zc = DoubleArray(p.count) { i -> (p.coords[9 * i + 2] + p.coords[9 * i + 5] + p.coords[9 * i + 8]) / 3.0 }
        return Overhangs.survey(p, p.bounds(), Overhangs.vertexIds(p), needs, area, zc, minWidthMm = boxWidth, columnWidthMm = columnWidth)
    }
    // a sliver: 0.05 mm² lying diagonally across a 2 x 2.05 mm box (wider than the box's test both ways), wound clockwise
    // seen from above: facing down
    val sliver = listOf(floatArrayOf(0f, 0f, 5f, 2f, 2.05f, 5f, 2f, 2f, 5f))
    // a 2 x 2 mm flat underside, two triangles facing down
    val square = listOf(floatArrayOf(0f, 0f, 5f, 0f, 2f, 5f, 2f, 2f, 5f), floatArrayOf(0f, 0f, 5f, 2f, 2f, 5f, 2f, 0f, 5f))
    // an s x s mm underside as a fan of 24 thin triangles round its middle, none of which is a column wide on its own: the
    // footprint is their union (at 1.8 mm no triangle's centroid is half a column inside it, at 6 mm each is)
    fun fan(s: Float): List<FloatArray> {
        val ring = (0 until 24).map { k -> val side = k / 6; val f = (k % 6) / 6f
            when (side) { 0 -> floatArrayOf(s * f, 0f); 1 -> floatArrayOf(s, s * f); 2 -> floatArrayOf(s - s * f, s); else -> floatArrayOf(0f, s - s * f) } }
        // clockwise seen from above (facing down): the middle, then the next corner, then this one
        return (0 until 24).map { k -> val a = ring[k]; val b = ring[(k + 1) % 24]; floatArrayOf(s / 2, s / 2, 5f, b[0], b[1], 5f, a[0], a[1], 5f) }
    }
    // a strip [w] mm wide and 6 mm long, diagonal (its box over 4 mm both ways), as eight triangles
    fun strip(w: Float): List<FloatArray> {
        val d = w / Math.sqrt(2.0).toFloat() / 2
        return (0 until 4).flatMap { k -> val x0 = 1.06f * k; val x1 = 1.06f * (k + 1)
            listOf(floatArrayOf(x0 + d, x0 - d, 5f, x1 - d, x1 + d, 5f, x1 + d, x1 - d, 5f), floatArrayOf(x0 + d, x0 - d, 5f, x0 - d, x0 + d, 5f, x1 - d, x1 + d, 5f)) }
    }

    "a sliver whose box is wide enough but which is no column wide is not a region; control: it is one when no width is asked" {
        survey(sliver, column).size shouldBe 0
        survey(sliver, 0.0).size shouldBe 1
    }
    "an underside a column wide is a region; control: the box's own test still leaves out a narrower box" {
        survey(square, column).size shouldBe 1
        survey(square, column, boxWidth = 2.1).size shouldBe 0
    }
    "an underside made of thin triangles is a region where their union is a column wide" {
        survey(fan(1.8f), column).size shouldBe 1
        survey(fan(6f), column).size shouldBe 1
    }
    "a strip of more area than a column's disc but narrower than it is not a region; control: it is one with no width asked" {
        survey(strip(0.5f), column).size shouldBe 0
        survey(strip(0.5f), 0.0).size shouldBe 1
    }
    "a strip wider than a column, though narrower than a tip with its gaps, is a region (a cantilever's edge face needs its support)" {
        survey(strip(1.2f), column).size shouldBe 1
    }
})
