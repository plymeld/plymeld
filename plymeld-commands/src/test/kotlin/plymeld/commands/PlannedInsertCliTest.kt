package plymeld.commands

import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * A planned insert end to end: a 30 x 30 x 8 mm block with a sealed 10 x 10 x 3 mm pocket (z 2..5) as two
 * shells; `plan.insert.ball` names the pocket with its top at z 5. The slice pauses above z 5, lays the pocket's roof as a
 * bottom surface (no bridge in the file) and generates no support; without the insert the roof is bridged. Without a locked
 * pose the insert is refused by name.
 */
class PlannedInsertCliTest : StringSpec({
    val machine = """
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
        gcode.pause = "M25"
        [hardware.tool.t1]
        machine = "m"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [method.search]
        log.enabled = "false"
        layer.heights = "1"
        [plan.technique.flat]
        layer.height = "0.2 mm"
        heights = "one"
    """.trimIndent()
    /** A box from (x0, y0, z0) to (x1, y1, z1) as faces; [inward] flips every face (a cavity's shell). */
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, inward: Boolean): List<List<DoubleArray>> {
        val faces = ArrayList<List<DoubleArray>>()
        fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)
        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) {
            if (inward) { faces += listOf(a, c, b); faces += listOf(a, d, c) } else { faces += listOf(a, b, c); faces += listOf(a, c, d) }
        }
        quad(p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), p(x1, y0, z0))   // bottom (facing down)
        quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1))   // top (facing up)
        quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1))   // front
        quad(p(x1, y1, z0), p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1))   // back
        quad(p(x0, y1, z0), p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1))   // left
        quad(p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1))   // right
        return faces
    }
    fun stl(): String = buildString {
        append("solid pocket\n")
        for (f in box(-15.0, -15.0, 0.0, 15.0, 15.0, 8.0, false) + box(-5.0, -5.0, 2.0, 5.0, 5.0, 5.0, true)) {
            append("facet normal 0 0 0\nouter loop\n"); for (v in f) append("vertex ${v[0]} ${v[1]} ${v[2]}\n"); append("endloop\nendfacet\n")
        }
        append("endsolid pocket\n")
    }
    val d: Path = Files.createTempDirectory("plymeld-insert")
    Files.writeString(d.resolve("m.toml"), machine)
    Files.writeString(d.resolve("pocket.stl"), stl())
    Files.writeString(d.resolve("pose.toml"), "[plan]\npose = \"as-given\"\nplacement = \"center\"\n")
    Files.writeString(d.resolve("ball.toml"), "[plan.insert.ball]\nmin = \"-5 -5 2 mm\"\nmax = \"5 5 5 mm\"\ntop = \"5 mm\"\n")
    fun p(n: String) = d.resolve(n).toString()
    fun slice(out: String, vararg cfs: String) = SliceCommand().test(cfs.flatMap { listOf("-cf", p(it)) } + listOf("-m", p("pocket.stl"), "-o", p(out)))
    fun gcode(n: String) = Files.readString(d.resolve(n))
    fun features(g: String, name: String) = g.lines().count { it == "; FEATURE: $name" }

    "with the insert: a pause above z 5, the roof a bottom surface onto the insert (no bridge), no support; control: without it the roof is bridged" {
        val r = slice("ball.gcode", "m.toml", "pose.toml", "ball.toml")
        val out = r.stderr + r.stdout
        r.statusCode shouldBe 0
        out shouldContain "planned inserts: 'ball'"
        out shouldContain "pause above z 5.000 mm"
        val g = gcode("ball.gcode")
        features(g, "Bridge") shouldBe 0
        features(g, "Support") shouldBe 0
        // the roof: a bottom surface after the pause (the first layer's bottom is before it)
        val pauseAt = g.lines().indexOfFirst { it.contains("PAUSE") || it.startsWith("M0") || it.startsWith("M25") || it.contains("; pause") }
        (pauseAt > 0) shouldBe true
        g.lines().drop(pauseAt).any { it == "; FEATURE: Bottom surface" } shouldBe true
        val c = slice("plain.gcode", "m.toml", "pose.toml")
        c.statusCode shouldBe 0
        (features(gcode("plain.gcode"), "Bridge") > 0) shouldBe true
    }
    "without a locked pose the insert is refused by name" {
        val r = slice("free.gcode", "m.toml", "ball.toml")
        (r.statusCode != 0) shouldBe true
        (r.stderr + r.stdout) shouldContain "planned insert 'ball'"
        (r.stderr + r.stdout) shouldContain "plan.pose"
        Files.exists(d.resolve("free.gcode")) shouldBe false
    }
})
