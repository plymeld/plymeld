package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A multi-color model printed from one filament without editing it: `--slot N` on `plymeld slice` and `plymeld plan`, and the
 * slot map `model.slots` it sets. A project of one object, two boxes as its parts, the left on extruder 1 and the right on
 * extruder 2, on a machine with only slot 1 loaded: as the model says, refused, the refusal naming the map and the option;
 * with --slot 1, or model.slots 2 = 1 in the config, sliced on the one tool, both boxes printed (each one's first-layer walls
 * read back from the g-code); control: --slot 0 is refused.
 */
class SlotOptionTest : StringSpec({
    /** Box triangles over [x0, x1] x [0, 10] x [0, 4] mm, as 3MF vertices and triangles. */
    fun boxMesh(x0: Double, x1: Double): String {
        val v = listOf(doubleArrayOf(x0, 0.0, 0.0), doubleArrayOf(x1, 0.0, 0.0), doubleArrayOf(x1, 10.0, 0.0), doubleArrayOf(x0, 10.0, 0.0),
            doubleArrayOf(x0, 0.0, 4.0), doubleArrayOf(x1, 0.0, 4.0), doubleArrayOf(x1, 10.0, 4.0), doubleArrayOf(x0, 10.0, 4.0))
        val f = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return "<mesh><vertices>" + v.joinToString("") { "<vertex x=\"${it[0]}\" y=\"${it[1]}\" z=\"${it[2]}\"/>" } + "</vertices><triangles>" +
            f.joinToString("") { "<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>" } + "</triangles></mesh>"
    }
    val d = Files.createTempDirectory("plymeld-slot-option")
    val project = d.resolve("two_colors.3mf")
    ZipOutputStream(Files.newOutputStream(project)).use { z ->
        z.putNextEntry(ZipEntry("3D/3dmodel.model"))
        z.write(("""<?xml version="1.0" encoding="utf-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>""" +
            """<object id="1" type="model">${boxMesh(0.0, 10.0)}</object><object id="2" type="model">${boxMesh(20.0, 30.0)}</object>""" +
            """<object id="3" type="model"><components><component objectid="1"/><component objectid="2"/></components></object>""" +
            """</resources><build><item objectid="3" transform="1 0 0 0 1 0 0 0 1 100 100 0"/></build></model>""").toByteArray())
        z.closeEntry()
        z.putNextEntry(ZipEntry("Metadata/model_settings.config"))
        z.write(("""<?xml version="1.0" encoding="UTF-8"?><config><object id="3"><metadata key="name" value="pair"/>""" +
            """<part id="1" subtype="normal_part"><metadata key="name" value="left"/><metadata key="extruder" value="1"/></part>""" +
            """<part id="2" subtype="normal_part"><metadata key="name" value="right"/><metadata key="extruder" value="2"/></part>""" +
            """</object></config>""").toByteArray())
        z.closeEntry()
    }
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
        gcode.start = "M83\nM109 S{first_layer_nozzle_temperature}"
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
    Files.writeString(d.resolve("map.toml"), "[model]\nslots = { 2 = 1 }\n")
    fun slice(cfs: List<String>, vararg extra: String): String {
        val out = d.resolve("out.gcode")
        SliceCommand().parse(cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + listOf("-m", project.toString(), "-o", out.toString()) + extra)
        return Files.readString(out)
    }
    /** The X extent (mm) of the first layer's outer walls, by tool (T number, 0 before any). */
    fun firstLayerXs(g: String): Map<Int, Pair<Double, Double>> {
        val xs = HashMap<Int, MutableList<Double>>(); var layer = -1; var feature = ""; var t = 0
        for (l in g.lines()) {
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(':').trim(); continue }
            val c = l.substringBefore(';').trim()
            if (Regex("T\\d+").matches(c)) { t = c.substring(1).toInt(); continue }
            if (layer == 0 && feature == "Outer wall" && c.startsWith("G1 X") && " E" in c) Regex("X(-?[0-9.]+)").find(c)?.let { xs.getOrPut(t) { ArrayList() } += it.groupValues[1].toDouble() }
        }
        return xs.mapValues { (_, v) -> v.min() to v.max() }
    }
    /** Both boxes printed: walls in each 10 mm box, 10 mm apart (the parts as placed: a 30 mm wide object). */
    fun bothBoxes(g: String) {
        val all = firstLayerXs(g)
        all.keys shouldBe setOf(0)                                   // the one tool
        val (lo, hi) = all.getValue(0)
        ((hi - lo) > 29.0 && (hi - lo) < 30.0) shouldBe true         // from the left box's left wall to the right box's right wall
    }

    "a part on a filament the loadout lacks: refused, the refusal naming model.slots and --slot; --slot 1 or the map prints both parts on the one tool" {
        shouldThrow<Exception> { slice(listOf("hw.toml")) }.message!!.let { it shouldContain "uses filament 2"; it shouldContain "model.slots"; it shouldContain "--slot 1" }
        bothBoxes(slice(listOf("hw.toml"), "--slot", "1"))
        bothBoxes(slice(listOf("hw.toml", "map.toml")))
        // the same option on plan: the plan is decided where it was refused
        PlanCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-m", project.toString(), "--slot", "1"))
    }

    "--slot 0 is refused: a slot's number counts from 1" {
        shouldThrow<Exception> { slice(listOf("hw.toml"), "--slot", "0") }.message!! shouldContain "--slot is a slot's number, from 1"
    }
})
