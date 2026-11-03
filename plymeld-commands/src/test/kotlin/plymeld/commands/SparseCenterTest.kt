package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Sparse infill laid out from the object's middle (Planner.patternCenter: a grid centered where it can
 * be, not left asymmetric): a block placed off any round figure, its grid's lines on a middle layer
 * symmetric about the block's middle, family by family (every line has one mirrored through the middle). Control: the same
 * reading about a point 1 mm off the middle finds no such symmetry, so the check can fail.
 */
class SparseCenterTest : StringSpec({
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
        placement = "as-given"
        [plan.technique.flat]
        layer.height = "0.2 mm"
        heights = "one"
        [plan.layer.infill]
        pattern = "grid"
        density = "15%"
    """.trimIndent()

    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): List<DoubleArray> {
        val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return (0 until 12).map { t -> DoubleArray(9) { v[f[3 * t + it / 3]][it % 3] } }
    }
    fun threeMf(path: Path, name: String, tris: List<DoubleArray>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        sb.append("<object id=\"1\" type=\"model\" name=\"$name\"><mesh><vertices>")
        for (t in tris) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
        sb.append("</vertices><triangles>")
        for (i in tris.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
        sb.append("</triangles></mesh></object></resources><build><item objectid=\"1\"/></build></model>")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
    }

    "a grid's lines symmetric about the object's middle; control: not about a point 1 mm off it" {
        val d: Path = Files.createTempDirectory("plymeld-center")
        Files.writeString(d.resolve("hw.toml"), hardware)
        // a 31.7 x 23.3 x 6 mm block, its middle at (117.15, 89.55): on no round figure of any line spacing
        val cx = 101.3 + 31.7 / 2; val cy = 77.9 + 23.3 / 2
        threeMf(d.resolve("blk.3mf"), "blk", box(101.3, 77.9, 0.0, 133.0, 101.2, 6.0))
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-m", d.resolve("blk.3mf").toString(), "-o", d.resolve("blk.gcode").toString()))
        // the sparse lines (at least 3 mm long) on the layer nearest z 3, as (direction in deg mod 180, x0, y0)
        class Seg(val layer: Double, val angle: Double, val x: Double, val y: Double)
        val all = ArrayList<Seg>(); var layer = 0.0; var feature = ""; var x = 0.0; var y = 0.0
        for (l in Files.readAllLines(d.resolve("blk.gcode"))) when {
            l.startsWith(";Z:") -> layer = l.removePrefix(";Z:").trim().toDouble()
            l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
            l.startsWith("G1") -> {
                fun v(c: Char) = Regex(" $c(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
                val nx = v('X') ?: x; val ny = v('Y') ?: y
                if (feature == "Sparse infill" && (v('E') ?: 0.0) > 0 && Math.hypot(nx - x, ny - y) > 3.0)
                    all += Seg(layer, Math.round((Math.toDegrees(Math.atan2(ny - y, nx - x)) + 360) % 180 * 10) / 10.0 % 180, x, y)
                x = nx; y = ny
            }
        }
        val mid = all.map { it.layer }.distinct().minBy { Math.abs(it - 3.0) }
        val segs = all.filter { it.layer == mid }
        val families = segs.groupBy { it.angle }.filter { it.value.size >= 3 }
        withClue("two families: ${segs.groupBy { it.angle }.mapValues { it.value.size }}") { families.size shouldBe 2 }
        /** Each family's lines' positions across them, from (px, py); the worst distance from a line to the nearest mirrored one. */
        fun asymmetry(px: Double, py: Double): Double = families.maxOf { (a, ss) ->
            val r = Math.toRadians(a)
            val pos = ss.map { -Math.sin(r) * (it.x - px) + Math.cos(r) * (it.y - py) }.map { Math.round(it * 1000) / 1000.0 }.distinct()
            pos.maxOf { p -> pos.minOf { q -> Math.abs(p + q) } }
        }
        withClue("about the middle") { (asymmetry(cx, cy) < 0.01) shouldBe true }
        withClue("control: about a point 1 mm off the middle") { (asymmetry(cx + 1.0, cy + 1.0) > 0.3) shouldBe true }
    }
})
