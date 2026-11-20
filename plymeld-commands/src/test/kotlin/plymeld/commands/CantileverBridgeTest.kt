package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Undersides locked to bridge lines (`plan.faces.under.technique = "as_laid"`) over an area hanging off one side, end to
 * end. The model: a bar on the bed (80 x 8 x 10 mm) and three arms 6 mm wide and 2 mm thick, 6 mm over the bed, leaving the
 * bar's side (y = 8) at 45, 90, and 135 degrees, each joined to the bar along one short edge only, nothing under it.
 * Support locked off unless said, so the bridge lines lie over air:
 *  - each arm's bridge lines run along it, out from the bar, each by its own direction (scored by both ends alone, every
 *    arm got the bar edge's direction, 0 degrees, its lines across it resting on nothing at either end); the pre-print check
 *    passes the file (its held-bridge rule finds every line held at the bar);
 *  - control: a slab across two posts, a span, is bridged across the gap between them, post to post, as before;
 *  - a slab hanging under a roof, joined to nothing beside or below it, has nothing to anchor its bridge lines at either
 *    end: the pre-print check says so, with the layer and the place, and refuses nothing (with support unset, and with
 *    support locked off, where the plan warns as well); control: the arms are not said.
 */
class CantileverBridgeTest : StringSpec({
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [plan]
        pose = "as-given"
        placement = "center"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
        faces.under.technique = "as_laid"
    """.trimIndent()
    /** A prism over the convex quad [q] (x0, y0, ... x3, y3, counter-clockwise) from [z0] to [z1], as ASCII STL facets. */
    fun prism(q: DoubleArray, z0: Double, z1: Double): String {
        val v = (0 until 4).map { doubleArrayOf(q[2 * it], q[2 * it + 1], z0) } + (0 until 4).map { doubleArrayOf(q[2 * it], q[2 * it + 1], z1) }
        val faces = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val sb = StringBuilder()
        for (f in faces) { sb.append("facet normal 0 0 0\nouter loop\n"); for (i in f) sb.append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); sb.append("endloop\nendfacet\n") }
        return sb.toString()
    }
    fun box(x0: Double, x1: Double, y0: Double, y1: Double) = doubleArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)
    /** An arm [w] wide from (x, y) along [deg] for [len] mm, as a quad. */
    fun arm(x: Double, y: Double, deg: Double, len: Double, w: Double): DoubleArray {
        val r = Math.toRadians(deg); val ux = Math.cos(r); val uy = Math.sin(r); val nx = -uy * w / 2; val ny = ux * w / 2
        return doubleArrayOf(x - nx, y - ny, x + ux * len - nx, y + uy * len - ny, x + ux * len + nx, y + uy * len + ny, x + nx, y + ny)
    }
    // each arm starts 4 mm inside the bar (one solid where they overlap) and reaches 24 mm past its side
    val arms = listOf(12.0 to 45.0, 40.0 to 90.0, 68.0 to 135.0)
    val armsStl = "solid arms\n" + prism(box(0.0, 80.0, 0.0, 8.0), 0.0, 10.0) +
        arms.joinToString("") { (x, deg) -> prism(arm(x, 4.0, deg, 28.0, 6.0), 6.0, 8.0) } + "endsolid arms\n"
    // two posts 14 mm apart and a slab across them
    val spanStl = "solid span\n" + prism(box(0.0, 6.0, 0.0, 10.0), 0.0, 4.0) + prism(box(20.0, 26.0, 0.0, 10.0), 0.0, 4.0) +
        prism(box(0.0, 26.0, 0.0, 10.0), 4.0, 5.2) + "endsolid span\n"
    // a roof on two posts, and a slab under the roof's middle held by the roof alone: its first layer has nothing beside or under it
    val hangingStl = "solid hanging\n" + prism(box(0.0, 6.0, 0.0, 10.0), 0.0, 6.0) + prism(box(30.0, 36.0, 0.0, 10.0), 0.0, 6.0) +
        prism(box(0.0, 36.0, 0.0, 10.0), 6.0, 7.2) + prism(box(12.0, 24.0, 2.0, 8.0), 4.0, 6.0) + "endsolid hanging\n"
    val d: Path = Files.createTempDirectory("plymeld-cantilever-bridge").also { d ->
        Files.writeString(d.resolve("hw.toml"), hardware)
        Files.writeString(d.resolve("off.toml"), "[plan.support]\nenabled = \"false\"\n")
        Files.writeString(d.resolve("arms.stl"), armsStl)
        Files.writeString(d.resolve("span.stl"), spanStl)
        Files.writeString(d.resolve("hanging.stl"), hangingStl)
    }
    /** Slices [model] through the command (the pre-print check included); its status, its output, and the g-code. */
    fun slice(model: String, supportOff: Boolean = true): Triple<Int, String, String> {
        val out = d.resolve(model.substringBefore('.') + (if (supportOff) "_off" else "") + ".gcode")
        val lines = ArrayList<String>()
        val cfs = listOf("hw.toml") + (if (supportOff) listOf("off.toml") else emptyList())
        val status = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + listOf("-m", d.resolve(model).toString(), "-o", out.toString()), lines::add)
        return Triple(status, lines.joinToString("\n"), if (Files.exists(out)) Files.readString(out) else "")
    }
    /** The bridge's lines (moves longer than 1.5 mm: not the links between them): x and y of the middle, direction (0..180 deg), length. */
    fun bridgeLines(g: String): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        var feature = ""; var x = 0.0; var y = 0.0
        for (line in g.lines()) {
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) { feature = line.substringAfter(':').trim(); continue }
            if (!line.startsWith("G1 ") && !line.startsWith("G0 ")) continue
            fun v(c: Char) = Regex(" $c(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDouble()
            val nx = v('X') ?: x; val ny = v('Y') ?: y
            val e = v('E')
            val len = Math.hypot(nx - x, ny - y)
            if (feature == "Bridge" && e != null && e > 0 && len > 1.5)
                out += doubleArrayOf((x + nx) / 2, (y + ny) / 2, (Math.toDegrees(Math.atan2(ny - y, nx - x)) + 360) % 180, len)
            x = nx; y = ny
        }
        return out
    }
    /** The direction (5 degree steps) holding most of [lines]' length. */
    fun mainDirection(lines: List<DoubleArray>): Long =
        lines.groupBy { Math.round(it[2] / 5) * 5 % 180 }.mapValues { (_, ls) -> ls.sumOf { it[3] } }.maxBy { it.value }.key

    "each arm hanging off the bar is bridged along itself, out from the bar, by its own direction; the check passes" {
        val (status, words, g) = slice("arms.stl")
        status shouldBe 0
        words shouldContain "check: the g-code reads back as the config and the model say"
        val lines = bridgeLines(g)
        lines.shouldNotBeEmpty()
        // the model is centered: its x 0..80 lies at 85..165 on the 250 mm bed; each arm's lines by where they lie along x
        val byArm = lines.groupBy { l -> val mx = l[0] - 85.0; when { mx < 35.5 -> 0; mx < 44.5 -> 1; else -> 2 } }
        byArm.keys shouldBe setOf(0, 1, 2)
        arms.indices.map { mainDirection(byArm.getValue(it)) } shouldBe arms.map { Math.round(it.second) }
    }

    "control: a slab across two posts is bridged post to post, across the gap" {
        val (status, _, g) = slice("span.stl")
        status shouldBe 0
        mainDirection(bridgeLines(g)) shouldBe 0L
    }

    // support unset (decided per overhang), but the pose and the one layer height locked: the decision measures nothing and
    // surveys no overhang, so no support is chosen under the slab and the plan says nothing; the check is what says it
    "a slab held only from above has nothing under either end of its bridge lines: the check says so, where, and refuses nothing" {
        val (status, words, g) = slice("hanging.stl", supportOff = false)
        bridgeLines(g).shouldNotBeEmpty()
        status shouldBe 0
        words shouldContain "warning: check: layer at z"
        words shouldContain "rest on nothing at either end: no model and no support under them"
        words shouldContain "(reported, not refused"
        // with support locked off the plan warns as well
        val (offStatus, offWords, _) = slice("hanging.stl")
        offStatus shouldBe 0
        offWords shouldContain "it will print over air (only support lays it)"
        offWords shouldContain "rest on nothing at either end"
        // control: the arms, each held at the bar, are not said
        slice("arms.stl").second.contains("rest on nothing") shouldBe false
    }
})
