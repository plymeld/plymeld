package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `--plate` on `plymeld slice` and `plymeld plan`: a 3MF project with objects on several plates was refused with
 * "choose one with --plate", an option only `investigate slice` had. A project of two boxes, a 10 mm one on plate 1 and a
 * 30 mm one on plate 2: without --plate, refused, naming the option; with --plate 2, only the 30 mm box is sliced (its first
 * layer's extent read back from the g-code); control: --plate 3, which the project does not have, is refused.
 */
class PlateOptionTest : StringSpec({
    /** Box triangles [s] x [s] x 5 mm, corner at the origin, as 3MF vertices and triangles. */
    fun boxMesh(s: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(s, 0.0, 0.0), doubleArrayOf(s, s, 0.0), doubleArrayOf(0.0, s, 0.0),
            doubleArrayOf(0.0, 0.0, 5.0), doubleArrayOf(s, 0.0, 5.0), doubleArrayOf(s, s, 5.0), doubleArrayOf(0.0, s, 5.0))
        val f = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return "<mesh><vertices>" + v.joinToString("") { "<vertex x=\"${it[0]}\" y=\"${it[1]}\" z=\"${it[2]}\"/>" } + "</vertices><triangles>" +
            f.joinToString("") { "<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>" } + "</triangles></mesh>"
    }
    val d = Files.createTempDirectory("plymeld-plate-option")
    val project = d.resolve("two_plates.3mf")
    ZipOutputStream(Files.newOutputStream(project)).use { z ->
        z.putNextEntry(ZipEntry("3D/3dmodel.model"))
        z.write(("""<?xml version="1.0" encoding="utf-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>""" +
            """<object id="1" type="model" name="small">${boxMesh(10.0)}</object><object id="2" type="model" name="big">${boxMesh(30.0)}</object>""" +
            """</resources><build><item objectid="1"/><item objectid="2" transform="1 0 0 0 1 0 0 0 1 300 0 0"/></build></model>""").toByteArray())
        z.closeEntry()
        z.putNextEntry(ZipEntry("Metadata/model_settings.config"))
        z.write(("""<?xml version="1.0" encoding="UTF-8"?><config>""" +
            """<object id="1"><metadata key="name" value="small"/></object><object id="2"><metadata key="name" value="big"/></object>""" +
            """<plate><metadata key="plater_id" value="1"/><model_instance><metadata key="object_id" value="1"/></model_instance></plate>""" +
            """<plate><metadata key="plater_id" value="2"/><model_instance><metadata key="object_id" value="2"/></model_instance></plate>""" +
            """</config>""").toByteArray())
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
    fun slice(vararg extra: String): String {
        val out = d.resolve("out.gcode")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-m", project.toString(), "-o", out.toString()) + extra)
        return Files.readString(out)
    }
    /** The extent in X of the first layer's walls (mm). */
    fun firstLayerWidth(g: String): Double {
        val xs = ArrayList<Double>(); var layer = -1; var feature = ""
        for (l in g.lines()) {
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(':').trim(); continue }
            if (layer == 0 && feature == "Outer wall" && l.startsWith("G1 X") && " E" in l) Regex("X(-?[0-9.]+)").find(l)?.let { xs += it.groupValues[1].toDouble() }
        }
        return xs.max() - xs.min()
    }

    "several plates: refused without --plate, naming it; --plate 2 slices plate 2's object; control: a plate the project lacks is refused" {
        shouldThrow<Exception> { slice() }.message!! shouldContain "choose one with --plate"
        firstLayerWidth(slice("--plate", "2")) shouldBe (29.5 plusOrMinus 0.5)            // the 30 mm box, not the 10 mm one (wall centers a little in)
        shouldThrow<Exception> { slice("--plate", "3") }.message!! shouldContain "has no plate 3"
    }
})
