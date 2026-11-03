package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import java.nio.file.Files

/**
 * plymeld writes E as amounts (relative extrusion), so the printer must be in M83 before anything is extruded (the
 * sample machine's start template, "G28" alone, left it in the firmware's absolute E, and the time prediction read the file
 * the same way: "filament -1 mm"). A start template without M83 gets one written after it, before the first E; one that sets
 * it gets no second (control). The pre-print check refuses extrusion before M83 (OutputCheckTest's control).
 */
class RelativeExtrusionTest : StringSpec({
    /** An ASCII STL box [x] x [y] x [z] mm, corner at the origin. */
    fun box(x: Double, y: Double, z: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return buildString {
            append("solid box\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid box\n")
        }
    }
    fun slice(start: String): String {
        val d = Files.createTempDirectory("plymeld-relative-e")
        Files.writeString(d.resolve("hw.toml"), """
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
            gcode.start = "$start"
            gcode.end = "M104 S0"
            [hardware.tool.t1]
            machine = "m"
            index = "1"
            bore = "0.4 mm"
            layer.height.min = "= tool.bore / 5"
            layer.height.max = "= tool.bore * 0.75"
            [hardware.machine.m.loadout]
            plate = "pei"
            tool.t1.filament = "pla-generic"
        """.trimIndent())
        Files.writeString(d.resolve("box.stl"), box(20.0, 20.0, 3.0))
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-m", d.resolve("box.stl").toString(), "-o", d.resolve("box.gcode").toString()))
        return Files.readString(d.resolve("box.gcode"))
    }
    fun commands(text: String) = text.lines().map { it.substringBefore(';').trim() }
    fun firstE(cs: List<String>) = cs.indexOfFirst { (it.startsWith("G1 ") || it.startsWith("G0 ")) && Regex(" E-?[0-9.]+").containsMatchIn(it) }

    "a start template without M83 gets it after the start, before the first E; control: one with M83 gets no second" {
        val cs = commands(slice("G28\\nM109 S{first_layer_nozzle_temperature}"))                // sliced: its check passed
        cs.count { it == "M83" } shouldBe 1
        cs.indexOf("M83") shouldBeGreaterThan cs.indexOf("G28")
        cs.indexOf("M83") shouldBeLessThan firstE(cs)
        val own = commands(slice("M83\\nG28\\nM109 S{first_layer_nozzle_temperature}"))
        own.count { it == "M83" } shouldBe 1
        own.indexOf("M83") shouldBeLessThan own.indexOf("G28")                              // the template's own, first
    }
})
