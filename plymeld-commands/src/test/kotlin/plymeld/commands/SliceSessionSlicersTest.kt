package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import java.nio.file.Files

/**
 * `plymeld slice` slices once: its session lets the objects' slicers go as soon as the file is written
 * (SliceSession(keepSlicers = false)), so their stages (every layer's outlines, walls and paths) are not held through the
 * pre-print check beside the check's own; the file and its check are the same. Control: a session kept across runs (the
 * window's) holds them, and its next run of the same plate reuses them.
 */
class SliceSessionSlicersTest : StringSpec({
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
    """.trimIndent()
    val stl = buildString {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(20.0, 0.0, 0.0), doubleArrayOf(20.0, 20.0, 0.0), doubleArrayOf(0.0, 20.0, 0.0),
            doubleArrayOf(0.0, 0.0, 3.0), doubleArrayOf(20.0, 0.0, 3.0), doubleArrayOf(20.0, 20.0, 3.0), doubleArrayOf(0.0, 20.0, 3.0))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        append("solid box\n")
        for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
        append("endsolid box\n")
    }

    "slice's session lets the slicers go once the file is written, and checks the same file; control: a kept session holds them and reuses them" {
        val d = Files.createTempDirectory("plymeld-session")
        Files.writeString(d.resolve("hw.toml"), hardware); Files.writeString(d.resolve("box.stl"), stl)
        fun product() = Product(ConfigStack.load(listOf("hw.toml"), emptyList(), d), listOf(d.resolve("box.stl")), null) { }
        val once = SliceSession(keepSlicers = false)
        val w = once.write(product(), d.resolve("once.gcode"), { })
        once.holdsSlicers() shouldBe false
        once.check(w, { }).problems.shouldBeEmpty()
        // control: the window's session keeps them, and its next run of the same plate reuses them for the same file
        val kept = SliceSession()
        val pr = product()
        val a = kept.write(pr, d.resolve("kept.gcode"), { })
        kept.holdsSlicers() shouldBe true
        a.reused shouldBe false
        val b = kept.write(pr, d.resolve("kept2.gcode"), { })
        b.reused shouldBe true
        // the same file either way, but for its own name in the source block
        fun body(t: String) = t.substringBefore(SourceBlock.START)
        body(a.text) shouldBe body(w.text)
        body(b.text) shouldBe body(w.text)
    }
})
