package plymeld.commands

/**
 * The ledge's test model and machine (CurvedIslandLedgeTest): a block of one filament whose front face leans [FACE_DEG] from
 * level, with a pocket cut square to that face holding an inlay of a second filament, its visible face [RECESS_MM] below the
 * block's face (along the face's normal). In front of (below) the inlay's face the pocket's lower wall shows: a ledge facing up
 * at 90 - [FACE_DEG] deg, with a 45 deg chamfer [CHAMFER_MM] on its rim (17.5 deg from level). The pocket seen on the face is
 * the outer outline of a letter U ([round]: a slot with a half-round bottom, so the ledge's rim rises along it) or a rectangle
 * (its rim level). The values the brief gives: the face's angle, the recess, the chamfer's angle; the rest is Claude's choice
 * (a block 16 mm wide and 12 mm tall, 4 mm deep at its top; the pocket 4 mm wide, its bottom 6.7 mm up the face, 1.5 mm deep;
 * the chamfer 0.15 mm).
 *
 * The frame: x across the face, the face toward -y, z up; the block's front edge on the bed along y = 0. A point on the face is
 * (u, v) (u = x, v up the face from its front edge) and w into the block along the face's normal: [at].
 */
object LedgeInlay {
    const val FACE_DEG = 62.5
    const val RECESS_MM = 0.84
    const val CHAMFER_MM = 0.15
    const val WIDTH_MM = 16.0
    const val HEIGHT_MM = 12.0
    const val TOP_DEPTH_MM = 4.0
    const val POCKET_DEPTH_MM = 1.5
    /** The pocket (its middle at the middle of the face): its half width (the bottom's radius), its lowest point up the face, its height. */
    const val HALF_WIDTH = 2.0
    const val BOTTOM_V = 6.7
    const val POCKET_H = 4.0

    private val sinF = Math.sin(Math.toRadians(FACE_DEG))
    private val cosF = Math.cos(Math.toRadians(FACE_DEG))

    /** The point (u, v) on the face, [w] into the block along its normal (mm). */
    fun at(u: Double, v: Double, w: Double) = doubleArrayOf(u, v * cosF + w * sinF, v * sinF - w * cosF)

    /** The middle of the ledge at the pocket's lowest point ([w] into the block; on the ledge for CHAMFER_MM < w < RECESS_MM). */
    fun ledgePoint(w: Double = (CHAMFER_MM + RECESS_MM) / 2, width: Double = WIDTH_MM) = at(width / 2, BOTTOM_V, w)

    /**
     * The pocket's outline on the face (u, v), counter-clockwise, offset outward by [grow] (its rim's chamfer), each vertex the
     * same one along the pocket however far it is offset (so the outline and its offset pair up vertex by vertex).
     */
    private fun outline(round: Boolean, grow: Double, width: Double, half: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        val r = half; val c = width / 2; val vc = BOTTOM_V + (if (round) r else 0.0); val vt = BOTTOM_V + POCKET_H
        fun add(u: Double, v: Double, nu: Double, nv: Double) { out += doubleArrayOf(u + grow * nu, v + grow * nv) }
        // the top edge, right to left (its corners mitred), then down the left side
        add(c + r, vt, 1.0, 1.0)
        val nTop = 8
        for (k in 1 until nTop) add(c + r - 2 * r * k / nTop, vt, 0.0, 1.0)
        add(c - r, vt, -1.0, 1.0)
        val nSide = ((vt - vc) / 0.25).toInt()
        for (k in 1 until nSide) add(c - r, vt - (vt - vc) * k / nSide, -1.0, 0.0)
        if (round) {
            // the half-round bottom, from the left side round under to the right, every 3.75 deg
            val n = 48
            for (k in 0..n) { val a = Math.PI + Math.PI * k / n; add(c + r * Math.cos(a), vc + r * Math.sin(a), Math.cos(a), Math.sin(a)) }
        } else {
            add(c - r, vc, -1.0, -1.0)
            val nb = 16
            for (k in 1 until nb) add(c - r + 2 * r * k / nb, vc, 0.0, -1.0)
            add(c + r, vc, 1.0, -1.0)
        }
        for (k in 1 until nSide) add(c + r, vc + (vt - vc) * k / nSide, 1.0, 0.0)
        return out
    }

    /**
     * The pocket's edge [w] into the block (0: where the chamfer meets the face, CHAMFER_MM: the ledge's rim, RECESS_MM: its
     * inner edge, the inlay's face) as segments x0, y0, z0, x1, y1, z1 (mm), round the pocket.
     */
    fun edge(round: Boolean, w: Double, width: Double = WIDTH_MM, half: Double = HALF_WIDTH): List<DoubleArray> {
        val ring = outline(round, if (w < CHAMFER_MM) CHAMFER_MM - w else 0.0, width, half).map { at(it[0], it[1], w) }
        return ring.indices.map { k -> val a = ring[k]; val b = ring[(k + 1) % ring.size]; doubleArrayOf(a[0], a[1], a[2], b[0], b[1], b[2]) }
    }

    /** The block ([width] wide, the pocket at its middle, [half] its half width) and the inlay, each its triangles (x, y, z of three corners), outward. */
    fun parts(round: Boolean, width: Double = WIDTH_MM, half: Double = HALF_WIDTH): List<Pair<String, List<DoubleArray>>> {
        val block = ArrayList<DoubleArray>(); val inlay = ArrayList<DoubleArray>()
        /** A triangle whose normal is turned to point along [want]. */
        fun tri(to: MutableList<DoubleArray>, a: DoubleArray, b: DoubleArray, c: DoubleArray, want: DoubleArray) {
            val ux = b[0] - a[0]; val uy = b[1] - a[1]; val uz = b[2] - a[2]; val vx = c[0] - a[0]; val vy = c[1] - a[1]; val vz = c[2] - a[2]
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val (p, q) = if (nx * want[0] + ny * want[1] + nz * want[2] >= 0) b to c else c to b
            to += doubleArrayOf(a[0], a[1], a[2], p[0], p[1], p[2], q[0], q[1], q[2])
        }
        /** A convex face through [pts] (in order round it), fanned from its centroid. */
        fun fan(to: MutableList<DoubleArray>, pts: List<DoubleArray>, want: DoubleArray) {
            val m = doubleArrayOf(pts.sumOf { it[0] } / pts.size, pts.sumOf { it[1] } / pts.size, pts.sumOf { it[2] } / pts.size)
            for (i in pts.indices) tri(to, m, pts[i], pts[(i + 1) % pts.size], want)
        }
        val out = doubleArrayOf(0.0, -sinF, cosF)            // the face's outward normal
        val vTop = HEIGHT_MM / sinF
        val yBack = HEIGHT_MM * cosF / sinF + TOP_DEPTH_MM
        // the face's outline, every 0.5 mm along each edge (shared, point for point, with the faces beside it)
        fun edge(a: DoubleArray, b: DoubleArray): List<DoubleArray> { val n = maxOf(1, Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) / 0.5).toInt())
            return (0 until n).map { k -> doubleArrayOf(a[0] + (b[0] - a[0]) * k / n, a[1] + (b[1] - a[1]) * k / n) } }
        val corners = listOf(doubleArrayOf(0.0, 0.0), doubleArrayOf(width, 0.0), doubleArrayOf(width, vTop), doubleArrayOf(0.0, vTop))
        val rimUv = (0 until 4).flatMap { k -> edge(corners[k], corners[(k + 1) % 4]) }
        val rim = rimUv.map { at(it[0], it[1], 0.0) }
        val n0 = edge(corners[0], corners[1]).size; val n1 = edge(corners[1], corners[2]).size; val n2 = edge(corners[2], corners[3]).size
        val bottomFront = rim.subList(0, n0) + listOf(rim[n0])
        val rightFront = rim.subList(n0, n0 + n1) + listOf(rim[n0 + n1])
        val topFront = rim.subList(n0 + n1, n0 + n1 + n2) + listOf(rim[n0 + n1 + n2])
        val leftFront = rim.subList(n0 + n1 + n2, rim.size) + listOf(rim[0])
        // the bottom (z = 0), the back (y = yBack), the top (z = HEIGHT_MM), the sides
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        fan(block, bottomFront + listOf(p(width, yBack, 0.0), p(0.0, yBack, 0.0)), doubleArrayOf(0.0, 0.0, -1.0))
        fan(block, listOf(p(0.0, yBack, 0.0), p(width, yBack, 0.0), p(width, yBack, HEIGHT_MM), p(0.0, yBack, HEIGHT_MM)), doubleArrayOf(0.0, 1.0, 0.0))
        fan(block, topFront + listOf(p(0.0, yBack, HEIGHT_MM), p(width, yBack, HEIGHT_MM)), doubleArrayOf(0.0, 0.0, 1.0))
        fan(block, rightFront + listOf(p(width, yBack, HEIGHT_MM), p(width, yBack, 0.0)), doubleArrayOf(1.0, 0.0, 0.0))
        fan(block, leftFront + listOf(p(0.0, yBack, 0.0), p(0.0, yBack, HEIGHT_MM)), doubleArrayOf(-1.0, 0.0, 0.0))
        // the face round the pocket: the rim and the chamfer's outer edge zipped by their angle about the pocket's middle
        val hole = outline(round, CHAMFER_MM, width, half)
        val wall = outline(round, 0.0, width, half)
        val cu = width / 2; val cv = BOTTOM_V + POCKET_H / 2
        fun ang(u: Double, v: Double) = Math.atan2(v - cv, u - cu).let { if (it < 0) it + 2 * Math.PI else it }
        run {
            val a = rimUv.indices.sortedBy { ang(rimUv[it][0], rimUv[it][1]) }.map { rimUv[it] }
            val b = hole.indices.sortedBy { ang(hole[it][0], hole[it][1]) }.map { hole[it] }
            var i = 0; var j = 0
            val na = a.size; val nb = b.size
            fun pa(k: Int) = a[k % na].let { at(it[0], it[1], 0.0) }
            fun pb(k: Int) = b[k % nb].let { at(it[0], it[1], 0.0) }
            fun angA(k: Int) = ang(a[k % na][0], a[k % na][1]) + (k / na) * 2 * Math.PI
            fun angB(k: Int) = ang(b[k % nb][0], b[k % nb][1]) + (k / nb) * 2 * Math.PI
            while (i < na || j < nb) {
                if (j >= nb || (i < na && angA(i + 1) < angB(j + 1))) { tri(block, pa(i), pa(i + 1), pb(j), out); i++ }
                else { tri(block, pa(i), pb(j + 1), pb(j), out); j++ }
            }
        }
        // the chamfer, the pocket's walls down to its floor, and its floor
        val m = wall.size
        for (k in 0 until m) {
            val k1 = (k + 1) % m
            val du = wall[k1][0] - wall[k][0]; val dv = wall[k1][1] - wall[k][1]
            // the wall's normal on the face, out of the block's material (into the pocket): inward of the outline
            val inU = -dv; val inV = du
            val inward = doubleArrayOf(inU, inV * cosF, inV * sinF)
            val a0 = at(hole[k][0], hole[k][1], 0.0); val a1 = at(hole[k1][0], hole[k1][1], 0.0)
            val b0 = at(wall[k][0], wall[k][1], CHAMFER_MM); val b1 = at(wall[k1][0], wall[k1][1], CHAMFER_MM)
            val chamferWant = doubleArrayOf(inward[0] + out[0], inward[1] + out[1], inward[2] + out[2])
            tri(block, a0, a1, b1, chamferWant); tri(block, a0, b1, b0, chamferWant)
            val c0 = at(wall[k][0], wall[k][1], POCKET_DEPTH_MM); val c1 = at(wall[k1][0], wall[k1][1], POCKET_DEPTH_MM)
            tri(block, b0, b1, c1, inward); tri(block, b0, c1, c0, inward)
            // the inlay's side, outward (against the block's wall), from its face down to the pocket's floor
            val d0 = at(wall[k][0], wall[k][1], RECESS_MM); val d1 = at(wall[k1][0], wall[k1][1], RECESS_MM)
            val outward = doubleArrayOf(-inward[0], -inward[1], -inward[2])
            tri(inlay, d0, d1, c1, outward); tri(inlay, d0, c1, c0, outward)
        }
        fan(block, wall.map { at(it[0], it[1], POCKET_DEPTH_MM) }, out)
        fan(inlay, wall.map { at(it[0], it[1], RECESS_MM) }, out)
        fan(inlay, wall.map { at(it[0], it[1], POCKET_DEPTH_MM) }, doubleArrayOf(-out[0], -out[1], -out[2]))
        return listOf("block" to block, "inlay" to inlay)
    }

    /**
     * A two-tool machine (the values made up for the test, as the brief gives them: a 0.4 mm bore, layers 0.08 to 0.3 mm, a 45 deg
     * nozzle 4 mm tall; the head's outline [IslandPin]'s), the block on tool 1 and the inlay on tool 2, flat layers 0.08 mm.
     */
    val hardware = """
        [hardware.machine.m]
        model = "flashforge-c5"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        head.nozzle.angle = "45 deg"
        head.nozzle.height = "4 mm"
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
        [hardware.tool.t2]
        like = "t1"
        index = "2"
        [hardware.filament.red]
        like = "pla-generic"
        color = "#FF0000"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        tool.t2.filament = "red"
        [model.object.ledge.part.ledge_block]
        slot = "1"
        [model.object.ledge.part.ledge_inlay]
        slot = "2"
        [plan]
        pose = "as-given"
        view_from = "all_around"
        [plan.technique.flat]
        heights = "one"
        layer.height = "0.08 mm"
    """.trimIndent()

    /** A plan's lock of the island on the ledge (its middle at the pocket's lowest point), with [more] lines. */
    fun lock(vararg more: String, width: Double = WIDTH_MM): String {
        val p = ledgePoint(width = width)
        return "[plan.object.ledge]\n" + (listOf("island.ledge.x = \"%.4f mm\"".format(java.util.Locale.ROOT, p[0]), "island.ledge.y = \"%.4f mm\"".format(java.util.Locale.ROOT, p[1])) + more).joinToString("\n") + "\n"
    }
}
