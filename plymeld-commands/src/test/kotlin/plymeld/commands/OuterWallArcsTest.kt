package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Arcs on outer walls: every outer wall above the first layer carries overhang shares, and the writer fitted
 * arcs only to paths without them, so no outer wall ever got one. Now a wall is fitted in runs of one speed and fan. A
 * 20 mm cylinder of 360 facets, arcs on: its outer walls are mostly arcs, and lay the same filament along the same length as
 * with arcs off (the control: no arc at all).
 */
class OuterWallArcsTest : StringSpec({
    fun cylinderStl(r: Double, h: Double, n: Int): String {
        val sb = StringBuilder("solid cyl\n")
        fun v(x: Double, y: Double, z: Double) = "vertex $x $y $z\n"
        fun tri(a: String, b: String, c: String) { sb.append("facet normal 0 0 0\nouter loop\n").append(a).append(b).append(c).append("endloop\nendfacet\n") }
        for (k in 0 until n) {
            val a0 = 2 * Math.PI * k / n; val a1 = 2 * Math.PI * (k + 1) / n
            val x0 = r * Math.cos(a0); val y0 = r * Math.sin(a0); val x1 = r * Math.cos(a1); val y1 = r * Math.sin(a1)
            tri(v(x0, y0, 0.0), v(x1, y1, 0.0), v(x1, y1, h)); tri(v(x0, y0, 0.0), v(x1, y1, h), v(x0, y0, h))
            tri(v(0.0, 0.0, 0.0), v(x1, y1, 0.0), v(x0, y0, 0.0)); tri(v(0.0, 0.0, h), v(x0, y0, h), v(x1, y1, h))
        }
        return sb.append("endsolid cyl\n").toString()
    }
    fun hardware(arcs: Boolean) = """
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
        firmware.arcs = "$arcs"
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
    """.trimIndent()
    val d: Path = Files.createTempDirectory("plymeld-arcs").also { p ->
        Files.writeString(p.resolve("on.toml"), hardware(true)); Files.writeString(p.resolve("off.toml"), hardware(false))
        Files.writeString(p.resolve("cyl.stl"), cylinderStl(10.0, 3.0, 360))
    }
    fun slice(cf: String): String {
        SliceCommand().parse(listOf("-cf", d.resolve(cf).toString(), "-m", d.resolve("cyl.stl").toString(), "-o", d.resolve("$cf.gcode").toString()))
        return Files.readString(d.resolve("$cf.gcode"))
    }
    /** The outer walls' moves above the first layer: (arcs, lines, filament mm, path mm; an arc's length from its center). */
    fun outer(g: String): DoubleArray {
        var feature = ""; var layer = -1; var x = Double.NaN; var y = Double.NaN
        var arcs = 0.0; var arcLen = 0.0; var e = 0.0; var len = 0.0
        val w = Regex("([XYIJE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(":").trim(); continue }
            val c = l.substringBefore(';').trim()
            if (!(c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 "))) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val de = m["E"] ?: 0.0
            if (layer >= 1 && feature == "Outer wall" && de > 0 && !x.isNaN()) {
                e += de
                if (c.startsWith("G1")) len += Math.hypot(nx - x, ny - y) else {
                    arcs++
                    val cx = x + (m["I"] ?: 0.0); val cy = y + (m["J"] ?: 0.0); val r = Math.hypot(x - cx, y - cy)
                    var sweep = Math.atan2(ny - cy, nx - cx) - Math.atan2(y - cy, x - cx)
                    if (c.startsWith("G2")) sweep = -sweep
                    while (sweep <= 1e-9) sweep += 2 * Math.PI
                    len += r * sweep; arcLen += r * sweep
                }
            }
            x = nx; y = ny
        }
        return doubleArrayOf(arcs, arcLen, e, len)
    }

    // the cylinder has no corner, so its outer walls are scarfed: the two ramps (curved, per-vertex Z) stay straight lines,
    // the rest of each loop is what can be arcs
    "a cylinder's outer walls are fitted with arcs, laying the same filament along the same length as without (control: arcs off, none)" {
        val on = outer(slice("on.toml")); val off = outer(slice("off.toml"))
        on[0].toInt() shouldBeGreaterThan 0
        (on[1] / on[3] > 0.5) shouldBe true                        // most of the length in arcs
        off[0] shouldBe 0.0
        on[2] shouldBe (off[2] plusOrMinus off[2] * 2e-3)
        on[3] shouldBe (off[3] plusOrMinus off[3] * 2e-3)
    }
})
