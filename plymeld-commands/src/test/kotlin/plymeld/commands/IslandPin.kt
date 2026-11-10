package plymeld.commands

/**
 * The curved islands' test model and machine (CurvedIslandLockTest, CurvedIslandCheckTest): a pin, a 30 x 30 x 2 mm plate with
 * a column of radius 5 mm up to 12 mm, its center at (15, 15), topped by a rounded cap 3 mm tall (its rim steeper than 45
 * deg); a machine whose tool has a 45 deg, 4 mm nozzle and a 60 x 50 mm head (the values made up for the test), the object
 * as given, flat layers 0.2 mm. With a post (stage 6, an island shared with a taller part, as the 3DBenchy's chimney through
 * its cabin roof): a column of radius 1.2 mm at the pin's center from 8 mm up to the post's top, through the cap.
 */
object IslandPin {
    /** The pin's two parts, the plate and the column with its cap, each its triangles (x, y, z of three corners). */
    fun parts(postTop: Double? = null): List<Pair<String, List<DoubleArray>>> {
        val plate = ArrayList<DoubleArray>(); val column = ArrayList<DoubleArray>()
        fun tri(to: MutableList<DoubleArray>, a: DoubleArray, b: DoubleArray, c: DoubleArray) { to += doubleArrayOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]) }
        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) { tri(plate, a, b, c); tri(plate, a, c, d) }
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        // the plate, its faces outward
        quad(p(0.0, 0.0, 0.0), p(0.0, 30.0, 0.0), p(30.0, 30.0, 0.0), p(30.0, 0.0, 0.0))
        quad(p(0.0, 0.0, 2.0), p(30.0, 0.0, 2.0), p(30.0, 30.0, 2.0), p(0.0, 30.0, 2.0))
        quad(p(0.0, 0.0, 0.0), p(30.0, 0.0, 0.0), p(30.0, 0.0, 2.0), p(0.0, 0.0, 2.0))
        quad(p(30.0, 0.0, 0.0), p(30.0, 30.0, 0.0), p(30.0, 30.0, 2.0), p(30.0, 0.0, 2.0))
        quad(p(30.0, 30.0, 0.0), p(0.0, 30.0, 0.0), p(0.0, 30.0, 2.0), p(30.0, 30.0, 2.0))
        quad(p(0.0, 30.0, 0.0), p(0.0, 0.0, 0.0), p(0.0, 0.0, 2.0), p(0.0, 30.0, 2.0))
        // the column and its cap (a separate closed part standing on the plate, its bottom at 2 mm)
        val a = 5.0; val capH = 3.0; val rr = (a * a + capH * capH) / (2 * capH); val n = 64
        val rings = ArrayList<Pair<Double, Double>>().apply { add(a to 2.0); add(a to 12.0)
            for (k in 1..24) { val rad = a * (1 - k / 24.0); add(rad to (12.0 + Math.sqrt(rr * rr - rad * rad) - (rr - capH))) } }
        fun pt(rad: Double, z: Double, i: Int): DoubleArray { val t = 2 * Math.PI * i / n; return p(15 + rad * Math.cos(t), 15 + rad * Math.sin(t), z) }
        for (s in 0 until rings.size - 1) for (i in 0 until n) {
            val (r0, z0) = rings[s]; val (r1, z1) = rings[s + 1]
            tri(column, pt(r0, z0, i), pt(r0, z0, i + 1), pt(r1, z1, i + 1)); if (r1 > 1e-9) tri(column, pt(r0, z0, i), pt(r1, z1, i + 1), pt(r1, z1, i))
        }
        for (i in 0 until n) tri(column, p(15.0, 15.0, 2.0), pt(a, 2.0, i + 1), pt(a, 2.0, i))
        if (postTop == null) return listOf("plate" to plate, "column" to column)
        // the post: a closed cylinder, its faces outward
        val post = ArrayList<DoubleArray>(); val rp = 1.2; val z0 = 8.0
        fun pp(i: Int, z: Double): DoubleArray { val t = 2 * Math.PI * i / n; return p(15 + rp * Math.cos(t), 15 + rp * Math.sin(t), z) }
        for (i in 0 until n) {
            tri(post, pp(i, z0), pp(i + 1, z0), pp(i + 1, postTop)); tri(post, pp(i, z0), pp(i + 1, postTop), pp(i, postTop))
            tri(post, p(15.0, 15.0, z0), pp(i + 1, z0), pp(i, z0)); tri(post, p(15.0, 15.0, postTop), pp(i, postTop), pp(i + 1, postTop))
        }
        return listOf("plate" to plate, "column" to column, "post" to post)
    }

    /** The pin as one ASCII STL (one part); with [postTop], its post up to there. */
    fun stl(postTop: Double? = null): String = TechniqueSamples.stl(parts(postTop).flatMap { it.second })

    val hardware = """
        [hardware.machine.m]
        model = "qidi-q2"
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
        gcode.start = "M109 S{first_layer_nozzle_temperature}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        head.nozzle.angle = "45 deg"
        head.nozzle.height = "4 mm"
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [plan]
        pose = "as-given"
        view_from = "all_around"
        [plan.technique.flat]
        heights = "one"
        layer.height = "0.2 mm"
    """.trimIndent()

    /** A plan's lock of the island on the pin's cap (its middle), with [more] lines. */
    fun lock(vararg more: String) = "[plan.object.\"pin.stl\"]\n" + (listOf("island.cap.x = \"15 mm\"", "island.cap.y = \"15 mm\"") + more).joinToString("\n") + "\n"
}
