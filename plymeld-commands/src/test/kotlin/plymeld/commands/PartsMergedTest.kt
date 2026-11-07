package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Touching parts on one filament whose settings agree are one area: a part's own value equal to the one it would have anyway
 * changes nothing, so the parts are laid together, with one outer wall round both and none where they meet. Two 10 x 10 mm
 * boxes side by side as one object's parts, the second with its own sparse density in the config
 * (`plan.object.pair.part.b.layer.infill.density`): the object's (they agree), one outer wall round the 20 x 10 mm whole;
 * control: another density, each box its own outer wall, the boundary walled twice.
 */
class PartsMergedTest : StringSpec({
    fun machine(bDensity: String) = """
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [plan]
        pose = "as-given"
        placement = "center"
        [plan.layer.infill]
        density = "15%"
        [plan.object.pair.part.b.layer.infill]
        density = "$bDensity"
    """.trimIndent()

    /** Box triangles over [x0, x1] x [0, 10] x [0, 4] mm, as 3MF vertices and triangles. */
    fun boxMesh(x0: Double, x1: Double): String {
        val v = listOf(doubleArrayOf(x0, 0.0, 0.0), doubleArrayOf(x1, 0.0, 0.0), doubleArrayOf(x1, 10.0, 0.0), doubleArrayOf(x0, 10.0, 0.0),
            doubleArrayOf(x0, 0.0, 4.0), doubleArrayOf(x1, 0.0, 4.0), doubleArrayOf(x1, 10.0, 4.0), doubleArrayOf(x0, 10.0, 4.0))
        val f = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return "<mesh><vertices>" + v.joinToString("") { "<vertex x=\"${it[0]}\" y=\"${it[1]}\" z=\"${it[2]}\"/>" } + "</vertices><triangles>" +
            f.joinToString("") { "<triangle v1=\"${it[0]}\" v2=\"${it[1]}\" v3=\"${it[2]}\"/>" } + "</triangles></mesh>"
    }
    val d: Path = Files.createTempDirectory("plymeld-parts-merged")
    /** A project of one object, boxes a (x 0 to 10) and b (x 10 to 20) as its parts. */
    val project: Path = d.resolve("pair.3mf").also { p ->
        ZipOutputStream(Files.newOutputStream(p)).use { z ->
            z.putNextEntry(ZipEntry("3D/3dmodel.model"))
            z.write(("""<?xml version="1.0" encoding="utf-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources>""" +
                """<object id="1" type="model">${boxMesh(0.0, 10.0)}</object><object id="2" type="model">${boxMesh(10.0, 20.0)}</object>""" +
                """<object id="3" type="model"><components><component objectid="1"/><component objectid="2"/></components></object>""" +
                """</resources><build><item objectid="3" transform="1 0 0 0 1 0 0 0 1 100 100 0"/></build></model>""").toByteArray())
            z.closeEntry()
            z.putNextEntry(ZipEntry("Metadata/model_settings.config"))
            z.write(("""<?xml version="1.0" encoding="UTF-8"?><config><object id="3"><metadata key="name" value="pair"/>""" +
                """<part id="1" subtype="normal_part"><metadata key="name" value="a"/><metadata key="extruder" value="1"/></part>""" +
                """<part id="2" subtype="normal_part"><metadata key="name" value="b"/><metadata key="extruder" value="1"/></part>""" +
                """</object></config>""").toByteArray())
            z.closeEntry()
        }
    }

    /** The outer wall's length (mm) on the third layer, part b's density at [bDensity]. */
    fun outerWallMm(bDensity: String): Double {
        val name = "pair-${bDensity.removeSuffix("%")}"
        val cfg = d.resolve("$name.toml").also { Files.writeString(it, machine(bDensity)) }
        val out = d.resolve("$name.gcode")
        Commands.invoke(listOf("slice", "-cf", cfg.toString(), "-m", project.toString(), "-o", out.toString()), { }) shouldBe 0
        var layer = -1; var feature = ""; var x = Double.NaN; var y = Double.NaN; var mm = 0.0
        for (l in Files.readAllLines(out)) {
            when {
                l.startsWith("; CHANGE_LAYER") -> layer++
                l.startsWith("; FEATURE: ") -> feature = l.removePrefix("; FEATURE: ")
                l.startsWith("G1 ") -> {
                    val nx = Regex(" X(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: x
                    val ny = Regex(" Y(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: y
                    if (layer == 2 && feature == "Outer wall" && Regex(" E[0-9.]+").containsMatchIn(l) && !x.isNaN()) mm += Math.hypot(nx - x, ny - y)
                    x = nx; y = ny
                }
                l.startsWith("G2 ") || l.startsWith("G3 ") -> error("arcs are off in this config: $l")
            }
        }
        return mm
    }

    "parts whose settings agree: one outer wall round both; control: settings that differ, each part walled on its own" {
        // each loop inset about half a line (0.4 to 0.5 mm wide) from the outline: one round the 20 x 10 mm whole is
        // 58 to 58.4 mm; two round 10 x 10 mm boxes, 76 to 76.8 mm (the 19 mm of boundary walled twice)
        val agree = outerWallMm("15%")
        agree shouldBe (58.2 plusOrMinus 1.5)
        val differ = outerWallMm("40%")
        differ shouldBe (76.4 plusOrMinus 1.5)
    }
})
