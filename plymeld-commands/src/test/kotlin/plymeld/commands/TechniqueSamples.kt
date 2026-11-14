package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import plymeld.check.TechniqueRender
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.cos
import kotlin.math.sin

/**
 * The shared pieces of the print techniques' sample toolpaths (CONTRIBUTING.md, Engineering rules: "Every print technique's
 * documentation includes a sample toolpath"): small synthetic models, a machine to slice them for, and a slice through
 * the command, its pre-print check included. The `*SampleTest` classes slice a technique and its default on one model,
 * assert from the g-code that the technique did what its picture shows, and draw both side by side with
 * [TechniqueRender] into `reviewRender` (docs/renders/ with `-Pplymeld.refreshRenders`).
 */
object TechniqueSamples {
    /**
     * A Creator 5 description (its g-code dialect), as the other slice tests write it. The head's shape (nozzle cone, tip
     * height, outline) is the tests' own, as CurvedCheckTest's: the curved techniques are refused without one. The pause
     * command (for planned inserts) is the tests' own too, as PlannedInsertCliTest's. Everything else is plymeld's bundled
     * defaults, apart from what [PLAN] and each sample lock.
     */
    val HARDWARE = """
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
        gcode.pause = "M25"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        head.nozzle.angle = "45 deg"
        head.nozzle.height = "4 mm"
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
    """.trimIndent()

    /**
     * What every sample locks so that the picture shows the technique and nothing the search chose: the model as given, in
     * the plate's middle, flat layers of one 0.2 mm height (the tests' choice, the layer height of the other slice tests),
     * bond-first cooling off, the search log off, and looked at from all around (the samples' models have no front of their
     * own). A sample overrides any of these in its own file.
     */
    val PLAN = """
        [method.search]
        log.enabled = "false"
        [plan]
        view_from = "all_around"
        pose = "as-given"
        placement = "center"
        bond_cooling = "off"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
    """.trimIndent()

    /**
     * Interior filled solid (the samples' choice, for pictures about the shape of the layers): with the bundled
     * 15 % sparse infill a section through the model cuts each sparse line into a dash, and the layers do not read as lines.
     * Every picture that uses it says so in its panels' labels.
     */
    const val SOLID = "[plan.layer.infill]\ndensity = \"100%\"\n"

    /** A triangle: nine floats, x y z of each corner, counter-clockwise seen from outside. */
    fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray): DoubleArray = a + b + c
    private fun p(x: Double, y: Double, z: Double) = doubleArrayOf(x, y, z)

    /** A box; [inward] turns every face in (a cavity's shell). */
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double, inward: Boolean = false): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        fun quad(a: DoubleArray, b: DoubleArray, c: DoubleArray, d: DoubleArray) {
            if (inward) { out += tri(a, c, b); out += tri(a, d, c) } else { out += tri(a, b, c); out += tri(a, c, d) }
        }
        quad(p(x0, y0, z0), p(x0, y1, z0), p(x1, y1, z0), p(x1, y0, z0))
        quad(p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1))
        quad(p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1))
        quad(p(x1, y1, z0), p(x0, y1, z0), p(x0, y1, z1), p(x1, y1, z1))
        quad(p(x0, y1, z0), p(x0, y0, z0), p(x0, y0, z1), p(x0, y1, z1))
        quad(p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1))
        return out
    }

    /**
     * A prism standing on the bed: the polygon [xy] (x y pairs, counter-clockwise from above, star-shaped from its first
     * corner, so a fan from it covers it) from z [z0] to [z1].
     */
    fun prism(xy: List<Pair<Double, Double>>, z0: Double, z1: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>(); val n = xy.size
        fun v(i: Int, z: Double) = p(xy[i % n].first, xy[i % n].second, z)
        for (i in 1 until n - 1) { out += tri(v(0, z1), v(i, z1), v(i + 1, z1)); out += tri(v(0, z0), v(i + 1, z0), v(i, z0)) }
        for (i in 0 until n) { out += tri(v(i, z0), v(i + 1, z0), v(i + 1, z1)); out += tri(v(i, z0), v(i + 1, z1), v(i, z1)) }
        return out
    }

    /** A round post: [sides] corners on a circle of radius [r] about ([cx], [cy]), from the bed to [h]. */
    fun cylinder(cx: Double, cy: Double, r: Double, h: Double, sides: Int = 96): List<DoubleArray> =
        prism((0 until sides).map { val a = 2 * Math.PI * it / sides; cx + r * cos(a) to cy + r * sin(a) }, 0.0, h)

    /**
     * A block on the bed over [x0]..[x1] x [y0]..[y1] whose top is the height field [top] (x, y), sampled on an [nx] x [ny]
     * grid; the walls straight down to the bed, the bottom flat (as CurvedCheckTest's pillow).
     */
    fun heightField(x0: Double, y0: Double, x1: Double, y1: Double, nx: Int, ny: Int, top: (Double, Double) -> Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        fun x(i: Int) = x0 + (x1 - x0) * i / nx
        fun y(j: Int) = y0 + (y1 - y0) * j / ny
        fun v(i: Int, j: Int, up: Boolean) = p(x(i), y(j), if (up) top(x(i), y(j)) else 0.0)
        for (j in 0 until ny) for (i in 0 until nx) {
            out += tri(v(i, j, true), v(i + 1, j, true), v(i + 1, j + 1, true)); out += tri(v(i, j, true), v(i + 1, j + 1, true), v(i, j + 1, true))
        }
        val ring = ArrayList<Pair<Int, Int>>()
        for (i in 0 until nx) ring += i to 0
        for (j in 0 until ny) ring += nx to j
        for (i in nx downTo 1) ring += i to ny
        for (j in ny downTo 1) ring += 0 to j
        val c = p((x0 + x1) / 2, (y0 + y1) / 2, 0.0)
        for (k in ring.indices) {
            val (pi, pj) = ring[k]; val (qi, qj) = ring[(k + 1) % ring.size]
            out += tri(v(pi, pj, false), v(qi, qj, false), v(qi, qj, true)); out += tri(v(pi, pj, false), v(qi, qj, true), v(pi, pj, true))
            out += tri(c, v(qi, qj, false), v(pi, pj, false))
        }
        return out
    }

    /** A strip along X whose underside passes through [pts] (x, z), [t] thick, [w] wide about y = 0 (as LiftedBridgeTest's). */
    fun strip(pts: List<Pair<Double, Double>>, t: Double = 1.2, w: Double = 5.0): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        fun v(i: Int, up: Boolean, s: Int) = p(pts[i].first, s * w / 2, pts[i].second + if (up) t else 0.0)
        for (i in 0 until pts.size - 1) {
            out += tri(v(i, false, -1), v(i, false, 1), v(i + 1, false, 1)); out += tri(v(i, false, -1), v(i + 1, false, 1), v(i + 1, false, -1))
            out += tri(v(i, true, -1), v(i + 1, true, -1), v(i + 1, true, 1)); out += tri(v(i, true, -1), v(i + 1, true, 1), v(i, true, 1))
            out += tri(v(i, false, -1), v(i + 1, false, -1), v(i + 1, true, -1)); out += tri(v(i, false, -1), v(i + 1, true, -1), v(i, true, -1))
            out += tri(v(i, false, 1), v(i + 1, true, 1), v(i + 1, false, 1)); out += tri(v(i, false, 1), v(i, true, 1), v(i + 1, true, 1))
        }
        val n = pts.size - 1
        out += tri(v(0, false, -1), v(0, true, -1), v(0, true, 1)); out += tri(v(0, false, -1), v(0, true, 1), v(0, false, 1))
        out += tri(v(n, false, -1), v(n, true, 1), v(n, true, -1)); out += tri(v(n, false, -1), v(n, false, 1), v(n, true, 1))
        return out
    }

    /** [tris] as an ASCII STL. */
    fun stl(tris: List<DoubleArray>): String = buildString {
        append("solid sample\n")
        for (t in tris) {
            append("facet normal 0 0 0\nouter loop\n")
            for (c in 0..2) append("vertex ${t[3 * c]} ${t[3 * c + 1]} ${t[3 * c + 2]}\n")
            append("endloop\nendfacet\n")
        }
        append("endsolid sample\n")
    }

    /** A 3MF of one object [name] made of the named parts (as LiftedBridgeTest's: a lifted bridge comes as parts). */
    fun threeMf(path: Path, name: String, parts: List<Pair<String, List<DoubleArray>>>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        for ((k, part) in parts.withIndex()) {
            sb.append("<object id=\"${k + 1}\" type=\"model\" name=\"${name}_${part.first}\"><mesh><vertices>")
            for (t in part.second) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
            sb.append("</vertices><triangles>")
            for (i in part.second.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
            sb.append("</triangles></mesh></object>")
        }
        val g = parts.size + 1
        sb.append("<object id=\"$g\" type=\"model\" name=\"$name\"><components>")
        for (k in parts.indices) sb.append("<component objectid=\"${k + 1}\"/>")
        sb.append("</components></object></resources><build><item objectid=\"$g\"/></build></model>")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
    }

    /** A fresh directory holding the machine ([HARDWARE]) and the samples' locks ([PLAN]). */
    fun dir(): Path = Files.createTempDirectory("plymeld-sample").also { d ->
        Files.writeString(d.resolve("hw.toml"), HARDWARE); Files.writeString(d.resolve("plan.toml"), PLAN)
    }

    /**
     * Slices [models] (files in [d]) with the machine, the samples' locks and then [toml] (a sample's own settings, written
     * to `<out>.toml`), through the command with its pre-print check; the slice must succeed. Returns the g-code, read.
     */
    fun slice(d: Path, models: List<String>, toml: String, out: String): TechniqueRender.Toolpath {
        Files.writeString(d.resolve("$out.toml"), toml)
        val lines = ArrayList<String>()
        val status = Commands.invoke(listOf("slice", "-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("plan.toml").toString(),
            "-cf", d.resolve("$out.toml").toString()) + models.flatMap { listOf("-m", d.resolve(it).toString()) } + listOf("-o", d.resolve(out).toString()), lines::add)
        withClue("slice $out:\n" + lines.joinToString("\n")) { status shouldBe 0 }
        return TechniqueRender.read(Files.readString(d.resolve(out)))
    }

    /** The moves of [feature] in [path] that start a run: the first move, or one not continuing the move before it. */
    fun runStarts(path: TechniqueRender.Toolpath, feature: String): List<TechniqueRender.Move> {
        val out = ArrayList<TechniqueRender.Move>(); var prev: TechniqueRender.Move? = null
        for (m in path.moves) {
            if (m.feature == feature && (prev == null || prev.feature != feature || prev.index != m.index - 1 ||
                    Math.hypot(prev.x1 - m.x0, prev.y1 - m.y0) > 1e-6)) out += m
            prev = m
        }
        return out
    }
}
