package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The plan's full prediction of an object laid part group by part group (here: a part with
 * settings of its own): written as `slice` writes it, by the object's own slicer (Job.write), so the finalists are
 * predicted from their g-code (such objects were refused there, and the pose, technique, and curved-island
 * decisions fell back to quick estimates or were skipped). Control: on a plate of two objects such an object is still
 * refused, and the plan says so.
 */
class MultiPartPredictTest : StringSpec({
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
        [plan.object.stack.part.stack_top.layer.infill]
        density = "40%"
    """.trimIndent()

    /** Triangles (9 floats each) of a closed box, outward. */
    fun boxTris(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): List<DoubleArray> {
        val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return (0 until 12).map { t -> DoubleArray(9) { v[f[3 * t + it / 3]][it % 3] } }
    }
    fun stl(tris: List<DoubleArray>): String = buildString {
        append("solid s\n")
        for (t in tris) { append("facet normal 0 0 0\nouter loop\n"); for (c in 0..2) append("vertex ${t[3 * c]} ${t[3 * c + 1]} ${t[3 * c + 2]}\n"); append("endloop\nendfacet\n") }
        append("endsolid s\n")
    }
    /** A 3MF of one object [name] made of the named parts (each part named "<name>_<part>"). */
    fun threeMf(path: Path, name: String, parts: List<Pair<String, List<DoubleArray>>>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        for ((k, p) in parts.withIndex()) {
            sb.append("<object id=\"${k + 1}\" type=\"model\" name=\"${name}_${p.first}\"><mesh><vertices>")
            for (t in p.second) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
            sb.append("</vertices><triangles>")
            for (i in p.second.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
            sb.append("</triangles></mesh></object>")
        }
        val g = parts.size + 1
        sb.append("<object id=\"$g\" type=\"model\" name=\"$name\"><components>")
        for (k in parts.indices) sb.append("<component objectid=\"${k + 1}\"/>")
        sb.append("</components></object></resources><build><item objectid=\"$g\"/></build></model>")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
    }

    val d: Path = Files.createTempDirectory("plymeld-multipart-predict")
    Files.writeString(d.resolve("m.toml"), machine)
    val stack = d.resolve("stack.3mf")
    threeMf(stack, "stack", listOf("base" to boxTris(0.0, 0.0, 0.0, 20.0, 20.0, 6.0), "top" to boxTris(5.0, 5.0, 6.0, 15.0, 15.0, 12.0)))
    Files.writeString(d.resolve("box.stl"), stl(boxTris(0.0, 0.0, 0.0, 15.0, 15.0, 8.0)))

    /** Every fact and reason of the plan's decisions for [models]. */
    fun planned(models: List<Path>): List<String> {
        val pr = Product(ConfigStack.load(listOf(d.resolve("m.toml").toString()), emptyList(), d), models, null) { }
        return pr.decide().decisions.flatMap { listOf(it.why) + it.facts }
    }

    "an object with a part of its own settings, alone on the plate: its finalists predicted from their g-code; control: beside another object, still refused and said" {
        val alone = planned(listOf(stack))
        alone.any { "finalists through the full pipeline" in it } shouldBe true
        alone.none { "cannot be generated yet" in it } shouldBe true
        val plate = planned(listOf(stack, d.resolve("box.stl")))
        plate.any { "cannot be generated yet" in it && "several filaments or part settings" in it } shouldBe true
    }
})
