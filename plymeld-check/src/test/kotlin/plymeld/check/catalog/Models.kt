package plymeld.check.catalog

import plymeld.check.catalog.Shapes.box
import plymeld.check.catalog.Shapes.ellipse
import plymeld.check.catalog.Shapes.join
import plymeld.check.catalog.Shapes.loft
import plymeld.check.catalog.Shapes.prism
import plymeld.check.catalog.Shapes.prismAlongY
import plymeld.check.catalog.Shapes.rect
import plymeld.check.catalog.Shapes.soup
import plymeld.check.catalog.Shapes.union
import plymeld.geom.Polygon
import plymeld.mesh.TriangleSoup

/**
 * The catalog's small models (mm), built in code: each a stable name in [Catalog], each exercising one technique or one known
 * weakness. The narrow-area models stand round ([CX], [CY]).
 */
object Models {
    const val CX = 100.0
    const val CY = 100.0

    // ---- walls by local width (WallsByWidthTest) ----

    /** The lens's depth at height [z]: 0.7 mm at z 0.42, 1.2 mm at z 1.42. */
    fun lensDepth(z: Double) = 0.7 + 0.5 * (z - 0.42)
    /** A lens 2.4 mm long, flat on one side, curved on the other, growing from 0.7 to 1.2 mm deep from z 0.42 to 1.42. */
    val lens by lazy { loft((0..50).map { r -> val z = 0.42 + r * 0.02
        z to (0 until 61).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(CX + 1.2 * t, CY - lensDepth(z) * (1 - t * t)) } }) }
    /** The block the lens lies against (a second part), with the foot both stand on. */
    val lensBlock by lazy { join(box(CX - 3, CY - 2, 0.0, CX + 3, CY + 3, 0.42), box(CX - 3, CY, 0.42, CX + 3, CY + 3, 1.6)) }
    /** The lens on a foot of its own. */
    val lensAlone by lazy { join(box(CX - 1.6, CY - 1.6, 0.0, CX + 1.6, CY + 0.4, 0.42), lens) }
    /** A wedge from nothing to 2 mm wide over 10 mm. */
    val wedge by lazy { prism(listOf(doubleArrayOf(CX - 5, CY), doubleArrayOf(CX + 5, CY - 1), doubleArrayOf(CX + 5, CY + 1)), 0.0, 1.0) }
    /** A strip in ten steps from 0.2 to 2.0 mm wide, each 3 mm long. */
    val strip by lazy { prism(run {
        val ws = (1..10).map { 0.2 * it }; val x0 = CX - 15
        val bottom = ws.indices.flatMap { i -> listOf(doubleArrayOf(x0 + 3 * i, CY - ws[i] / 2), doubleArrayOf(x0 + 3 * (i + 1), CY - ws[i] / 2)) }
        val top = ws.indices.reversed().flatMap { i -> listOf(doubleArrayOf(x0 + 3 * (i + 1), CY + ws[i] / 2), doubleArrayOf(x0 + 3 * i, CY + ws[i] / 2)) }
        bottom + top }, 0.0, 1.0) }
    /** A ring 1 mm wide round a 4 mm hole. */
    val ring by lazy { Shapes.tube(CX, CY, 3.0, 2.0, 0.0, 1.0) }
    /** A blob: an ellipse 2.5 by 1 mm. */
    val blob by lazy { prism((0 until 96).map { k -> val a = 2 * Math.PI * k / 96; doubleArrayOf(CX + 1.25 * Math.cos(a), CY + 0.5 * Math.sin(a)) }, 0.0, 1.0) }
    /** A block 10 by 10 mm, 1 mm tall: an ordinary area. */
    val block by lazy { box(CX - 5, CY - 5, 0.0, CX + 5, CY + 5, 1.0) }
    /** Two blobs 2 x 1.8 mm joined by a neck 0.5 mm wide, 1 mm long. */
    val necked by lazy { prism(union(listOf(ellipse(CX - 2, CY, 1.0, 0.9), rect(CX - 1.2, CY - 0.25, CX + 1.2, CY + 0.25), ellipse(CX + 2, CY, 1.0, 0.9))), 0.0, 1.0) }
    /** A letter E of strokes 0.9 mm wide, 6 mm tall, 3 mm wide: junctions, corners, and ends. */
    val letter by lazy { prism(union(listOf(rect(CX - 1.5, CY - 3, CX - 0.6, CY + 3), rect(CX - 1.5, CY - 3, CX + 1.5, CY - 2.1),
        rect(CX - 1.5, CY - 0.45, CX + 1.2, CY + 0.45), rect(CX - 1.5, CY + 2.1, CX + 1.5, CY + 3))), 0.0, 1.0) }
    /** A lens 2.4 mm long, 1.4 mm deep (its tips at about 34 deg to the flat): tips, not corners. */
    val deepLens by lazy { prism((0 until 61).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(CX + 1.2 * t, CY - 1.4 * (1 - t * t)) }, 0.0, 1.0) }
    /** A blob 2.6 x 1.8 mm narrowing to points at its ends (two parabolas): wider than three lines in its middle. */
    val pointedBlob by lazy { prism((0 until 60).map { k -> val t = -1 + 2.0 * k / 60; doubleArrayOf(CX + 1.3 * t, CY - 0.9 * (1 - t * t)) } +
        (0 until 60).map { k -> val t = 1 - 2.0 * k / 60; doubleArrayOf(CX + 1.3 * t, CY + 0.9 * (1 - t * t)) }, 0.0, 1.0) }
    /**
     * A stroke 1.17 mm wide bent round three quarters of a circle (2 mm inside radius), 0.4 mm tall: about as wide as the
     * rig's two lines become three (computed for 0.44 mm lines on 0.08 mm layers: 1.171 mm): a middle line starts and ends
     * along it.
     */
    val bentStroke by lazy { prism((0..120).map { k -> val a = 1.5 * Math.PI * k / 120; doubleArrayOf(CX + 3.17 * Math.cos(a), CY + 3.17 * Math.sin(a)) } +
        (0..120).map { k -> val a = 1.5 * Math.PI * (120 - k) / 120; doubleArrayOf(CX + 2.0 * Math.cos(a), CY + 2.0 * Math.sin(a)) }, 0.0, 0.4) }

    /**
     * A block 12 by 10.6 mm with a round hole 6 mm across whose edge comes within [webMm] of the block's front: a web between
     * a hole and the side, too narrow for the set walls of both, in an island wide enough for them elsewhere. Its sides and
     * the hole's are sampled on the same rays from the hole's center (the block is star-shaped about it), the block's corners
     * among them, and joined by quads.
     */
    fun webBlock(webMm: Double): TriangleSoup {
        val r = 3.0; val front = CY - r - webMm; val back = CY + 6.0; val left = CX - 6.0; val right = CX + 6.0
        val corners = listOf(doubleArrayOf(right, front), doubleArrayOf(right, back), doubleArrayOf(left, back), doubleArrayOf(left, front))
            .map { Math.atan2(it[1] - CY, it[0] - CX).let { a -> if (a < 0) a + 2 * Math.PI else a } }
        val angles = ((0 until 144).map { 2 * Math.PI * it / 144 } + corners).sorted()
        fun edge(a: Double): DoubleArray {
            val dx = Math.cos(a); val dy = Math.sin(a)
            val t = listOf(if (dx > 1e-12) (right - CX) / dx else Double.MAX_VALUE, if (dx < -1e-12) (left - CX) / dx else Double.MAX_VALUE,
                if (dy > 1e-12) (back - CY) / dy else Double.MAX_VALUE, if (dy < -1e-12) (front - CY) / dy else Double.MAX_VALUE).min()
            return doubleArrayOf(CX + t * dx, CY + t * dy)
        }
        val n = angles.size; val tris = ArrayList<DoubleArray>()
        val outer = angles.map { edge(it) }; val inner = angles.map { doubleArrayOf(CX + r * Math.cos(it), CY + r * Math.sin(it)) }
        fun p(q: DoubleArray, z: Double) = doubleArrayOf(q[0], q[1], z)
        for (k in 0 until n) { val j = (k + 1) % n
            tris += p(outer[k], 0.0) + p(outer[j], 0.0) + p(outer[j], 1.0); tris += p(outer[k], 0.0) + p(outer[j], 1.0) + p(outer[k], 1.0)
            tris += p(inner[k], 0.0) + p(inner[k], 1.0) + p(inner[j], 1.0); tris += p(inner[k], 0.0) + p(inner[j], 1.0) + p(inner[j], 0.0)
            tris += p(inner[k], 1.0) + p(outer[k], 1.0) + p(outer[j], 1.0); tris += p(inner[k], 1.0) + p(outer[j], 1.0) + p(inner[j], 1.0)
            tris += p(inner[k], 0.0) + p(outer[j], 0.0) + p(outer[k], 0.0); tris += p(inner[k], 0.0) + p(inner[j], 0.0) + p(outer[j], 0.0) }
        return soup(tris)
    }

    /**
     * A finger of one part hanging into a second part off a band of the first, joined to the band by a neck too narrow for
     * the finger's loop and about as long as it is wide: the finger 1 mm wide, 3 mm long, its neck 0.5 mm long and, layer by
     * layer, 0.36 to 0.58 mm wide (a slab a layer: the layers' tops are 0.2 mm, then every 0.08 mm). The band and
     * finger (the first part), then the second part round it.
     */
    val finger: List<TriangleSoup> by lazy {
        val fh = 0.5; val nl = 0.5; val slabs = 23
        fun outline(nh: Double) = listOf(doubleArrayOf(CX - nh, CY), doubleArrayOf(CX - nh, CY - nl), doubleArrayOf(CX - fh, CY - nl), doubleArrayOf(CX - fh, CY - 3),
            doubleArrayOf(CX + fh, CY - 3), doubleArrayOf(CX + fh, CY - nl), doubleArrayOf(CX + nh, CY - nl), doubleArrayOf(CX + nh, CY))
        val zs = listOf(0.0) + (0 until slabs).map { 0.2 + 0.08 * it }
        val nhs = (0 until slabs).map { 0.18 + 0.005 * it }
        val band = join(*(0 until slabs).map { k -> prism(listOf(doubleArrayOf(CX - 6, CY)) + outline(nhs[k]) +
            listOf(doubleArrayOf(CX + 6, CY), doubleArrayOf(CX + 6, CY + 4), doubleArrayOf(CX - 6, CY + 4)), zs[k], zs[k + 1]) }.toTypedArray())
        val around = join(*(0 until slabs).map { k -> prism(listOf(doubleArrayOf(CX - 6, CY - 4), doubleArrayOf(CX + 6, CY - 4), doubleArrayOf(CX + 6, CY)) +
            outline(nhs[k]).reversed() + listOf(doubleArrayOf(CX - 6, CY)), zs[k], zs[k + 1]) }.toTypedArray())
        listOf(band, around)
    }

    // ---- a ridge narrowing to a line, then to nothing (ThinRidgeTest) ----

    /**
     * A block swept along a gentle arc (radius 40 mm, 10 mm long) with a roof ridge on it, one face at 62.5 deg from level, the
     * other at 27.5 deg, its top cut off by a [chamfer], its apex rising [rise] along it; [wobble]: the faces moved in and out
     * by this much every 0.37 mm; [dip]: the apex this much higher at its ends than at its middle (a parabola).
     */
    class Ridge(val rise: Double, val wobble: Double = 0.0, val stations: Int = 201, val dip: Double = 0.0, val chamfer: Double = 0.1) {
        val radius = 40.0; val length = 10.0; val ox = 60.0; val oy = 105.0
        val zb = 1.0
        val steep = Math.toRadians(62.5); val shallow = Math.toRadians(27.5)
        val apex0 = 1.0
        /** The block's half width: 3 mm, or wider where the ridge's foot is (its shallow face's foot half a millimetre in). */
        val half = maxOf(3.0, (apex0 + rise + dip) / Math.tan(shallow) + 0.5)
        /** The apex's height over the block: rising [rise] along the ridge, and [dip] higher at its ends than at its middle (a parabola). */
        fun apex(s: Double) = apex0 + rise * s / length + dip * Math.pow((s - length / 2) / (length / 2), 2.0)
        fun shift(s: Double) = wobble * Math.sin(s * 2 * Math.PI / 0.37)
        fun center(s: Double) = doubleArrayOf(ox + radius * Math.sin(s / radius), oy + radius - radius * Math.cos(s / radius))
        fun normal(s: Double) = doubleArrayOf(-Math.sin(s / radius), Math.cos(s / radius))
        /** The ridge's cross-section at [s] and height [z] above the bed: its two faces along the normal, null above it. */
        fun across(s: Double, z: Double): DoubleArray? {
            val up = zb + apex(s) - z
            if (z < zb || up < chamfer) return null
            return doubleArrayOf(-up / Math.tan(shallow) - shift(s), up / Math.tan(steep) + shift(s))
        }
        /** A sweep along the arc of [profile] (n, z), counter-clockwise, its caps triangulated as [cap] (index triples, counter-clockwise). */
        fun sweep(profile: (Double) -> List<DoubleArray>, cap: List<Int>): TriangleSoup {
            val tris = ArrayList<DoubleArray>()
            val rows = (0 until stations).map { k -> val s = length * k / (stations - 1); val c = center(s); val nv = normal(s)
                profile(s).map { p -> doubleArrayOf(c[0] + p[0] * nv[0], c[1] + p[0] * nv[1], p[1]) } }
            val m = rows[0].size
            for (k in 0 until stations - 1) for (i in 0 until m) {
                val j = (i + 1) % m
                val a0 = rows[k][i]; val b0 = rows[k][j]; val a1 = rows[k + 1][i]; val b1 = rows[k + 1][j]
                tris += a0 + b0 + b1; tris += a0 + b1 + a1
            }
            for (t in cap.indices step 3) {
                val e = rows.last(); tris += e[cap[t]] + e[cap[t + 1]] + e[cap[t + 2]]
                val f = rows.first(); tris += f[cap[t]] + f[cap[t + 2]] + f[cap[t + 1]]
            }
            return soup(tris)
        }
        /** The block and its ridge. */
        val part by lazy { sweep({ s -> val a = apex(s); val top = zb + a - chamfer; val w = shift(s)
            listOf(doubleArrayOf(-half, 0.0), doubleArrayOf(half, 0.0), doubleArrayOf(half, zb), doubleArrayOf(a / Math.tan(steep) + w, zb),
                doubleArrayOf(chamfer / Math.tan(steep) + w, top), doubleArrayOf(-chamfer / Math.tan(shallow) - w, top), doubleArrayOf(-a / Math.tan(shallow) - w, zb), doubleArrayOf(-half, zb)) },
            listOf(0, 1, 2, 0, 2, 3, 0, 3, 6, 0, 6, 7, 3, 4, 5, 3, 5, 6)) }
        /** A second part on the block against the ridge's shallow face, up to its chamfer. */
        val against by lazy { sweep({ s -> val a = apex(s); val top = zb + a - chamfer; val w = shift(s)
            listOf(doubleArrayOf(-half, zb), doubleArrayOf(-a / Math.tan(shallow) - w, zb), doubleArrayOf(-chamfer / Math.tan(shallow) - w, top), doubleArrayOf(-half, top)) }, listOf(0, 1, 2, 0, 2, 3)) }
    }
    /** Its apex rising 0.03 mm along it: each layer cuts it about as wide as two walls need along much of its length. */
    val flatRidge = Ridge(0.03)
    /** The flat ridge with its faces moved 10 um in and out every 0.37 mm (as a curved surface's facets move them). */
    val wobblyRidge = Ridge(0.03, 0.01)
    /** Its apex rising 0.16 mm: each layer's change from two walls to one at one place. */
    val steepRidge = Ridge(0.16)
    /** Lowest in its middle (its apex 1.5 mm higher at its ends): two walls at its ends and one line between. */
    val dippedRidge = Ridge(0.0, dip = 1.5)
    /** The steep ridge with its top cut off 5 um down, not 0.1 mm: on each layer its crest narrows to nothing over millimetres. */
    val pointedRidge = Ridge(0.16, chamfer = 0.005)

    // ---- a sliver of one filament on the object's surface beside another (ExposedSliverTest) ----

    /** The exposed wedge's frame: the block's front face at x [WEDGE_X0] on the bed, the wedge from [WEDGE_Z0], both to [WEDGE_TOP]. */
    const val WEDGE_X0 = 40.0; const val WEDGE_Z0 = 1.49; const val WEDGE_TOP = 3.0
    private val cot62 = 1 / Math.tan(Math.toRadians(62.5)); private val cot55 = 1 / Math.tan(Math.toRadians(55.0))
    /** The block's front face at height [z] (leaning back at 62.5 deg from level). */
    fun blockFace(z: Double) = WEDGE_X0 + z * cot62
    /** The wedge's front at height [z] (above [WEDGE_Z0]; leaning out over the block's face at 55 deg from level). */
    fun wedgeFront(z: Double) = blockFace(WEDGE_Z0) - (z - WEDGE_Z0) * cot55
    /** The exposed wedge: A the wedge on the block's face, B the block (prisms along Y from 100 to 110). */
    val exposedWedge: List<TriangleSoup> by lazy { listOf(
        prismAlongY(listOf(blockFace(WEDGE_Z0) to WEDGE_Z0, blockFace(WEDGE_TOP) to WEDGE_TOP, wedgeFront(WEDGE_TOP) to WEDGE_TOP), 100.0, 110.0),
        prismAlongY(listOf(WEDGE_X0 to 0.0, 60.0 to 0.0, 60.0 to WEDGE_TOP, blockFace(WEDGE_TOP) to WEDGE_TOP), 100.0, 110.0)) }
    /**
     * A buried sliver: a block x 50 to 60 (A, the first filament), a block x 40 to 50 beside it (B, the second), and a fin of
     * the first filament [t] mm thick inside B against A (C), inside the object (sliced with job.part_overlap lower_filament).
     */
    fun buriedFin(t: Double): List<TriangleSoup> = listOf(plymeld.check.testBox(50f, 100f, 0f, 60f, 110f, 2f), plymeld.check.testBox(40f, 100f, 0f, 50f, 110f, 2f),
        plymeld.check.testBox((50 - t).toFloat(), 102f, 0.4f, 50f, 108f, 1.6f))

    // ---- a two-color inlay on a leaning face, an opening through to a buried plate ----

    /** The inlay block's face: leaning back at this angle from level (deg). */
    const val INLAY_FACE_DEG = 70.0
    private val sinF = Math.sin(Math.toRadians(INLAY_FACE_DEG)); private val cosF = Math.cos(Math.toRadians(INLAY_FACE_DEG))
    /** The slabs of the inlay block: 0.2 mm, as the layers it is sliced with (a slab a layer: its section at the layer's middle). */
    private val inlayZs = (0..50).map { 0.2 * it }
    /** w >= [w0]: at least [w0] into the block along the face's normal (w = y sin a - z cos a; 0 on the face). */
    private fun wAtLeast(w0: Double) = doubleArrayOf(0.0, -sinF, cosF, -w0)
    private fun wAtMost(w1: Double) = doubleArrayOf(0.0, sinF, -cosF, w1)
    /** v (up the face from its foot: v = y cos a + z sin a) at least [v0] / at most [v1]. */
    private fun vAtLeast(v0: Double) = doubleArrayOf(0.0, -cosF, -sinF, -v0)
    private fun vAtMost(v1: Double) = doubleArrayOf(0.0, cosF, sinF, v1)
    private fun xIn(x0: Double, x1: Double) = listOf(doubleArrayOf(-1.0, 0.0, 0.0, -x0), doubleArrayOf(1.0, 0.0, 0.0, x1))
    /** The block: 16 mm wide, its face leaning back from y 0 on the bed, its back at y 9, 10 mm tall (the slabs). */
    private val inlayBlock = xIn(0.0, 16.0) + listOf(wAtLeast(0.0), doubleArrayOf(0.0, 1.0, 0.0, 9.0))
    /** The inlay: 5 by 4 mm on the face (x 2 to 7, v 3 to 7), 0.8 mm deep along the face's normal, flush with the face. */
    private val inlayInlay = xIn(2.0, 7.0) + listOf(vAtLeast(3.0), vAtMost(7.0), wAtLeast(0.0), wAtMost(0.8))
    /** The buried plate: parallel to the face, 2.4 to 3.2 mm in (x 1.5 to 14.5, v 1.5 to 8.5): inside the block but where the opening reaches it. */
    private val inlayPlate = xIn(1.5, 14.5) + listOf(vAtLeast(1.5), vAtMost(8.5), wAtLeast(2.4), wAtMost(3.2))
    /**
     * The opening: 2.4 by 2.4 mm across, from the face at x 11, v 5 in to the plate's front (w 2.4), its axis turned 25 deg
     * from the face's normal toward +x, so its walls are not square to the face: one side overhangs, the other leans back.
     */
    private val inlayOpening: List<DoubleArray> = run {
        val turn = Math.toRadians(25.0)
        // inward normal n = (0, sin a, -cos a); s1 = -sin(turn) n + cos(turn) x: across the opening, square to its axis and the face's up
        val s1 = doubleArrayOf(Math.cos(turn), -Math.sin(turn) * sinF, Math.sin(turn) * cosF)
        val c = doubleArrayOf(11.0, 5.0 * cosF, 5.0 * sinF)
        val s1c = s1[0] * c[0] + s1[1] * c[1] + s1[2] * c[2]
        listOf(doubleArrayOf(s1[0], s1[1], s1[2], s1c + 1.2), doubleArrayOf(-s1[0], -s1[1], -s1[2], -(s1c - 1.2)),
            vAtLeast(5.0 - 1.2), vAtMost(5.0 + 1.2), wAtMost(2.4))
    }
    private fun section(hs: List<DoubleArray>, z: Double) = Shapes.convexSection(hs, z)?.let { listOf(Shapes.um(it)) } ?: emptyList()
    /**
     * A two-color inlay on a leaning face: A, a block whose face leans back at [INLAY_FACE_DEG], with an inlay of B set flush
     * into the face and an opening cut through to a plate of B buried inside it, the opening's walls turned from the face's
     * normal; B, the inlay and the plate. Synthetic dimensions (mm): see the parts above; built at the origin, the block's
     * middle moved to ([CX], [CY]).
     */
    val inlay: List<TriangleSoup> by lazy {
        val a = Shapes.slabs(inlayZs) { z ->
            plymeld.geom.Booleans.difference(section(inlayBlock, z), section(inlayInlay, z) + section(inlayPlate, z) + section(inlayOpening, z)) }
        val b = Shapes.slabs(inlayZs) { z -> plymeld.geom.Booleans.union(section(inlayInlay, z), section(inlayPlate, z)) }
        listOf(a, b).map { Shapes.moved(it, CX - 8, CY - 4.5) }
    }

    // ---- the inlay block's leaning face with a plate of the second filament just behind it (BuriedPartFaceTest's section) ----

    /**
     * The plate behind the face, across a layer: 1.33 mm of the first filament in front of it (1.25 mm along the face's
     * normal), about three lines of 0.44 mm: room for the two outer walls and a line between them, not for three walls a side.
     */
    const val PLATE_SKIN_MM = 1.33
    /** The plate behind the face: its first and last layers' heights (mm), level. */
    const val PLATE_Z0 = 1.0; const val PLATE_Z1 = 3.0
    /** The plate behind the face: along X (mm, the block's own frame: the block from 0 to 12). */
    const val PLATE_X0 = 2.0; const val PLATE_X1 = 10.0
    /** Slabs as the walls rig's layers (a first layer of 0.2 mm, then 0.08 mm), 3.96 mm tall: a slab a layer, its section at the layer's middle. */
    private val plateZs = listOf(0.0) + (0..47).map { 0.2 + 0.08 * it }
    /** The block of the plate behind the face: 12 mm wide, its face leaning back at [INLAY_FACE_DEG] from y 0 on the bed, its back at y 6. */
    private val plateBlock = xIn(0.0, 12.0) + listOf(wAtLeast(0.0), doubleArrayOf(0.0, 1.0, 0.0, 6.0))
    /** The plate: parallel to the face, [skin] mm across a layer behind it and 0.8 mm thick along the face's normal, shorter than the face both ways. */
    private fun plateBehind(skin: Double) = xIn(PLATE_X0, PLATE_X1) + listOf(wAtLeast(skin * sinF), wAtMost(skin * sinF + 0.8),
        doubleArrayOf(0.0, 0.0, -1.0, -PLATE_Z0), doubleArrayOf(0.0, 0.0, 1.0, PLATE_Z1))
    /**
     * A plate of a second filament buried just behind a leaning face: A, the block of the inlay's face (leaning back at
     * [INLAY_FACE_DEG]) with no inlay or opening; B, a plate parallel to the face with a skin of A in front of it narrower
     * than three walls, ordinary walls beyond its ends and below its bottom edge. Built at the origin, the block's middle
     * moved to ([CX], [CY]).
     */
    val plateBehindFace: List<TriangleSoup> by lazy { plateBehindFace(PLATE_SKIN_MM) }
    /** [plateBehindFace] with [skin] mm of the first filament in front of the plate across a layer. */
    fun plateBehindFace(skin: Double): List<TriangleSoup> {
        val a = Shapes.slabs(plateZs) { z -> plymeld.geom.Booleans.difference(section(plateBlock, z), section(plateBehind(skin), z)) }
        val b = Shapes.slabs(plateZs) { z -> section(plateBehind(skin), z) }
        return listOf(a, b).map { Shapes.moved(it, CX - 6, CY - 3) }
    }

    // ---- seams ----

    /** A smooth cylinder 12 mm across, 8 mm tall: no corner to hide a seam in. */
    val smoothCylinder by lazy { Shapes.cylinder(CX, CY, 6.0, 0.0, 8.0, n = 180) }
    /** A post 8 by 8 mm, 8 mm tall: four corners to hide a seam in. */
    val corneredPost by lazy { box(CX - 4, CY - 4, 0.0, CX + 4, CY + 4, 8.0) }

    // ---- overhangs and bridges ----

    /** The ladder's overhang angles, from vertical (deg). */
    val LADDER_DEG = listOf(30.0, 45.0, 55.0, 65.0)
    /**
     * An overhang ladder: a plate 30 by 10 mm, 1 mm thick, and on it a fin 5 mm wide per angle of [LADDER_DEG], 3 mm tall, its
     * back upright at y 9 and its front leaning out toward -y from y 4 at that angle from vertical (over the plate's front edge
     * at the steeper ones).
     */
    val overhangLadder by lazy {
        Shapes.slabs((0..20).map { 0.2 * it }) { z ->
            val rects = ArrayList<plymeld.geom.Polygon>()
            if (z < 1.0) rects += Shapes.um(rect(CX - 15, CY - 5, CX + 15, CY + 5))
            else for ((i, deg) in LADDER_DEG.withIndex()) {
                val x0 = CX - 14 + 7.25 * i; val front = CY - 1 - (z - 1.0) * Math.tan(Math.toRadians(deg))
                rects += Shapes.um(rect(x0, front, x0 + 5, CY + 4))
            }
            plymeld.geom.Booleans.normalize(rects)
        }
    }
    /** The bridges' spans (mm). */
    val BRIDGE_SPANS = listOf(5.0, 10.0, 20.0)
    /** A bridge per span of [BRIDGE_SPANS]: two piers 3 by 4 mm, 4 mm tall, and a deck 1 mm thick from pier to pier; side by side 8 mm apart. */
    val bridges by lazy {
        Shapes.slabs((0..25).map { 0.2 * it }) { z ->
            val rects = BRIDGE_SPANS.withIndex().flatMap { (j, span) ->
                val y0 = CY - 10 + 8.0 * j; val x0 = CX - span / 2 - 3
                if (z < 4.0) listOf(rect(x0, y0, x0 + 3, y0 + 4), rect(x0 + 3 + span, y0, x0 + 6 + span, y0 + 4))
                else listOf(rect(x0, y0, x0 + 6 + span, y0 + 4))
            }
            plymeld.geom.Booleans.normalize(rects.map { Shapes.um(it) })
        }
    }

    // ---- tops ----

    /** A block 16 by 12 mm whose top slopes from 7 mm at its left to 3 mm at its right (14 deg). */
    val slopedTop by lazy { prismAlongY(listOf(CX - 8 to 0.0, CX + 8 to 0.0, CX + 8 to 3.0, CX - 8 to 7.0), CY - 6, CY + 6) }
    /**
     * A block 18 by 12 mm in three steps: its right third 3.0 mm tall, its middle third 3.2 mm, its left third 5.0 mm. On a
     * few layers fills of several kinds share an edge: a top beside internal solid, an internal bridge beside internal
     * solid, internal solid and bridges beside sparse infill.
     */
    val steppedTop by lazy { join(box(CX - 9, CY - 6, 0.0, CX + 9, CY + 6, 3.0), box(CX - 9, CY - 6, 3.0, CX + 3, CY + 6, 3.2),
        box(CX - 9, CY - 6, 3.2, CX - 3, CY + 6, 5.0)) }
    /**
     * An oval 24 by 14 mm (an ellipse of 180 sides), 1.2 mm tall: a top whose round rings (concentric-inside-out) run along
     * its sides and round its ends, nearly parallel to the walls there.
     */
    val ovalTop by lazy { prism(ellipse(CX, CY, 12.0, 7.0, n = 180), 0.0, 1.2) }
    /** A cylinder 12 mm across, 2 mm tall, under a dome 4 mm high (a cap of a sphere 13 mm across). */
    val domedTop by lazy {
        val n = 96
        fun circle(r: Double) = (0 until n).map { k -> val a = 2 * Math.PI * k / n; doubleArrayOf(CX + r * Math.cos(a), CY + r * Math.sin(a)) }
        val rows = listOf(0.0 to circle(6.0)) + (0..39).map { k -> val z = 2.0 + 0.1 * k; z to circle(Math.sqrt(6.5 * 6.5 - (z + 0.5) * (z + 0.5))) } +
            listOf(5.99 to circle(Math.sqrt(6.5 * 6.5 - 6.49 * 6.49)))
        loft(rows)
    }

    // ---- two filaments, small layers, holes and pins ----

    /** A small two-color piece: two halves 6 by 10 mm, 3 mm tall, side by side (A at the left, B at the right): a tool change on every layer. */
    val twoColorPiece: List<TriangleSoup> by lazy { listOf(box(CX - 6, CY - 5, 0.0, CX, CY + 5, 3.0), box(CX, CY - 5, 0.0, CX + 6, CY + 5, 3.0)) }
    /**
     * A tooth's outline (mm): its apex [d] below the edge y = [CY] at x [cx], its base [b] wide along that edge, its right side
     * bulging out by [bulge] at its middle (four points on a half sine): a triangle about two lines across, with no corner a
     * loop of the set walls fits round.
     */
    fun tooth(cx: Double, b: Double, d: Double, bulge: Double): List<DoubleArray> {
        val nl = Math.hypot(d, b / 2)
        return listOf(doubleArrayOf(cx, CY - d)) + (1..4).map { k -> val t = k / 5.0; val f = bulge * Math.sin(Math.PI * t)
            doubleArrayOf(cx + t * b / 2 + f * d / nl, CY - d + t * d - f * (b / 2) / nl) } + listOf(doubleArrayOf(cx + b / 2, CY), doubleArrayOf(cx - b / 2, CY))
    }
    /** The teeth's outlines: three teeth 1.7 to 2 mm wide, 1 to 1.1 mm deep, 4 mm apart. */
    val TEETH = listOf(tooth(CX - 4, 1.8, 1.0, 0.2), tooth(CX, 1.7, 1.0, 0.2), tooth(CX + 4, 2.0, 1.1, 0.15))
    /**
     * Teeth along a second part's edge: A, three small teeth (the [TEETH]) hanging off the front of B from z 1 to 2.6 mm, over
     * air below; B, a band 14 by 4 mm, 2.6 mm tall, on the bed. Each tooth is an island laid by its beads (too narrow for
     * the set walls) that the walls hand to infill nearly whole (no loop of theirs fits round it).
     */
    val teethUnderBand: List<TriangleSoup> by lazy { listOf(join(*TEETH.map { prism(it, 1.0, 2.6) }.toTypedArray()), box(CX - 7, CY, 0.0, CX + 7, CY + 4, 2.6)) }
    /**
     * Slivers ending inside a second part's area: B, an inlay 8 by 6 mm (its front on the object's front), 1.2 mm tall, in a
     * pocket of A, a block 14 by 8 mm. Along the pocket's back edge, left to right:
     *  - B's tongues reaching 1.2 mm into A, each in a slot of A 5 µm wider a side (parts that meet a few microns apart, as
     *    parts sliced one by one do): straight ones 0.10 and 0.13 mm wide, and wedges 0.13 mm wide at the base narrowing to
     *    nothing over 1.2 mm and over 0.4 mm. Each is narrower than the narrowest place a line is laid in, so it goes over
     *    to A ([TONGUES]);
     *  - A's spikes reaching into B, a wedge [SPIKES] wide at the base and long: too short and blunt to go over to B (their
     *    thin tips are crumbs), so B's area has a narrow notch at each.
     * The widths and lengths are the test's (plymeld's choice: slivers 0.1 to 0.13 mm wide, notches 0.18 to 0.3 mm wide and
     * 0.2 to 0.35 mm deep).
     */
    val sliverTips: List<TriangleSoup> by lazy {
        val y0 = CY + 2; val g = 0.005
        val a = ArrayList<DoubleArray>(); val b = ArrayList<DoubleArray>()
        a += doubleArrayOf(CX - 7, CY - 4); a += doubleArrayOf(CX - 4, CY - 4); a += doubleArrayOf(CX - 4, y0)
        // A's pocket edge, left to right (A above it), and the same features of B's edge, collected to be laid right to left
        val bTop = ArrayList<List<DoubleArray>>()
        for (t in TONGUES) {
            val hw = t.width / 2
            if (t.wedge) {
                a += doubleArrayOf(t.x - hw - g, y0); a += doubleArrayOf(t.x, y0 + t.length + g); a += doubleArrayOf(t.x + hw + g, y0)
                bTop += listOf(doubleArrayOf(t.x + hw, y0), doubleArrayOf(t.x, y0 + t.length), doubleArrayOf(t.x - hw, y0))
            } else {
                a += doubleArrayOf(t.x - hw - g, y0); a += doubleArrayOf(t.x - hw - g, y0 + t.length + g)
                a += doubleArrayOf(t.x + hw + g, y0 + t.length + g); a += doubleArrayOf(t.x + hw + g, y0)
                bTop += listOf(doubleArrayOf(t.x + hw, y0), doubleArrayOf(t.x + hw, y0 + t.length), doubleArrayOf(t.x - hw, y0 + t.length), doubleArrayOf(t.x - hw, y0))
            }
        }
        for (s in SPIKES) { a += doubleArrayOf(s.x - s.width / 2, y0); a += doubleArrayOf(s.x, y0 - s.length); a += doubleArrayOf(s.x + s.width / 2, y0) }
        a += doubleArrayOf(CX + 4, y0); a += doubleArrayOf(CX + 4, CY - 4); a += doubleArrayOf(CX + 7, CY - 4)
        a += doubleArrayOf(CX + 7, CY + 4); a += doubleArrayOf(CX - 7, CY + 4)
        b += doubleArrayOf(CX - 4, CY - 4); b += doubleArrayOf(CX + 4, CY - 4); b += doubleArrayOf(CX + 4, y0)
        for (f in bTop.reversed()) b += f
        b += doubleArrayOf(CX - 4, y0)
        listOf(prism(a, 0.0, 1.2), prism(b, 0.0, 1.2))
    }
    /** A tongue of B into A ([sliverTips]): its middle's x, its width at the base, its length (mm), straight or a wedge. */
    class Tongue(val x: Double, val width: Double, val length: Double, val wedge: Boolean)
    /** A spike of A into B ([sliverTips]): its middle's x, its width at the base, its length (mm). */
    class Spike(val x: Double, val width: Double, val length: Double)
    val TONGUES = listOf(Tongue(CX - 3.2, 0.10, 1.2, false), Tongue(CX - 2.2, 0.13, 1.2, false), Tongue(CX - 1.2, 0.13, 1.2, true), Tongue(CX - 0.2, 0.13, 0.4, true))
    val SPIKES = listOf(Spike(CX + 0.8, 0.18, 0.2), Spike(CX + 1.8, 0.25, 0.3), Spike(CX + 2.8, 0.3, 0.35), Spike(CX + 3.5, 0.18, 0.3))
    /** A pillar 3 mm across, 12 mm tall: a small island, its layers short. */
    val smallPillar by lazy { Shapes.cylinder(CX, CY, 1.5, 0.0, 12.0, n = 64) }
    /** The holes' and pins' diameters (mm). */
    val HOLE_DIAMETERS = listOf(1.0, 2.0, 3.0, 5.0)
    /** A block 36 by 10 mm, 3 mm tall, with a round hole through it per diameter of [HOLE_DIAMETERS], in a row. */
    val holes by lazy { Shapes.blockWithHoles(CX - 18, CY - 5, CX + 18, CY + 5, 0.0, 3.0,
        HOLE_DIAMETERS.withIndex().map { (i, d) -> doubleArrayOf(CX - 13.5 + 9.0 * i, CY, d / 2) }) }
    /** A plate 36 by 8 mm, 1 mm thick, and on it a round pin 4 mm tall per diameter of [HOLE_DIAMETERS], in a row. */
    val pins by lazy { join(box(CX - 18, CY - 4, 0.0, CX + 18, CY + 4, 1.0),
        *HOLE_DIAMETERS.withIndex().map { (i, d) -> Shapes.cylinder(CX - 13.5 + 9.0 * i, CY, d / 2, 1.0, 5.0, n = 64) }.toTypedArray()) }

    // ---- one part's thin edge right in front of another part's tip (SpeckNearPartTest) ----

    /** The base 40 x 10 mm, its front face at y 0 (µm). */
    val speckBase = Polygon(longArrayOf(0, 0, 40_000, 0, 40_000, 10_000, 0, 10_000))
    /** The triangle, its tip 0.1 mm behind the base's front face, its base 5 mm back and 10 mm wide (µm). */
    val speckTriangle = Polygon(longArrayOf(15_000, 5_000, 20_000, 100, 25_000, 5_000))
    /** The triangle as a hole in the base. */
    val speckTriangleHole = Polygon(longArrayOf(15_000, 5_000, 25_000, 5_000, 20_000, 100))
    /** The base and the triangle as two parts 2.4 mm tall at ([CX] - 20, [CY] - 5): the base round the triangle. */
    val speckBeside: List<TriangleSoup> by lazy {
        fun mm(p: Polygon) = (0 until p.size).map { i -> doubleArrayOf(CX - 20 + p.x(i) / 1000.0, CY - 5 + p.y(i) / 1000.0) }
        val tri = (if (speckTriangle.isCcw()) speckTriangle else speckTriangle.reversed()).let { mm(it) }
        val base = (if (speckBase.isCcw()) speckBase else speckBase.reversed()).let { mm(it) }
        val c = doubleArrayOf(tri.sumOf { it[0] } / 3, tri.sumOf { it[1] } / 3)
        listOf(Shapes.frame(base, tri, c[0], c[1], 0.0, 2.4), prism(tri, 0.0, 2.4))
    }
}
