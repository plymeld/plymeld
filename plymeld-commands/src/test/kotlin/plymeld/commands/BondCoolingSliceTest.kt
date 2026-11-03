package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * Bond-first cooling end to end (a quad helix's helices snapped mid-print): two blocks sliced
 * together, one with bond-first cooling locked on (plan.object."b.stl".bond_cooling = "on"): its lines print at
 * plan.technique.bond_cooling's part fan and nozzle temperature (bundled: half the generic PLA's 100 % fan, its 210 C + 10),
 * the other block at the filament's own (unlocked, and its 100 mm² section is over method.strength.bond.enough_area, so the
 * plan keeps the filament's cooling); the pre-print check passes. Unlocked, thin pillars (3 x 3 mm, under enough_area) are
 * weighed by the job's weights (strength well over quality: bond-first), and the plan says so.
 */
class BondCoolingSliceTest : StringSpec({
    fun box(x0: Double, y0: Double, x1: Double, y1: Double, h: Double): String = buildString {
        append("solid b\n")
        val v = listOf(doubleArrayOf(x0, y0, 0.0), doubleArrayOf(x1, y0, 0.0), doubleArrayOf(x1, y1, 0.0), doubleArrayOf(x0, y1, 0.0),
            doubleArrayOf(x0, y0, h), doubleArrayOf(x1, y0, h), doubleArrayOf(x1, y1, h), doubleArrayOf(x0, y1, h))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
        append("endsolid b\n")
    }
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
        [plan]
        pose = "as-given"
        placement = "center"
        objects.printing = "together"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
        support.enabled = "false"
    """.trimIndent()
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-bond").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }
    fun run(d: Path, command: String, cfs: List<String>, out: Path? = null): Pair<Int, List<String>> {
        val lines = ArrayList<String>()
        val status = Commands.invoke(listOf(command) + cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } +
            listOf("-m", d.resolve("a.stl").toString(), "-m", d.resolve("b.stl").toString()) + (out?.let { listOf("-o", it.toString()) } ?: emptyList()), lines::add)
        return status to lines
    }
    /** Per object block (by name), the part fan values (M106 S, 0-255) and nozzle temperatures (M104 / M109 S) set inside it, above the first layers. */
    fun byObject(g: String): Map<String, Pair<Set<Int>, Set<Int>>> {
        val fans = HashMap<String, MutableSet<Int>>(); val temps = HashMap<String, MutableSet<Int>>()
        var obj: String? = null; var z = 0.0
        for (l in g.lines()) {
            when {
                l.startsWith(";Z:") -> z = l.removePrefix(";Z:").toDouble()
                l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                l.startsWith("; object end") -> obj = null
                obj != null && z > 1.0 && l.startsWith("M106") -> fans.getOrPut(obj) { HashSet() } += Regex("S(\\d+)").find(l)!!.groupValues[1].toInt()
                obj != null && z > 1.0 && (l.startsWith("M104") || l.startsWith("M109")) -> temps.getOrPut(obj) { HashSet() } += Regex("S(\\d+)").find(l)!!.groupValues[1].toInt()
            }
        }
        return (fans.keys + temps.keys).associateWith { (fans[it] ?: emptySet<Int>()) to (temps[it] ?: emptySet<Int>()) }
    }

    "bond-first locked on for one block: its fan and nozzle are bond-first's, the other block's the filament's; the check passes" {
        val d = dir("hw.toml" to hardware, "a.stl" to box(0.0, 0.0, 10.0, 10.0, 8.0), "b.stl" to box(0.0, 0.0, 10.0, 10.0, 8.0),
            "job.toml" to "[plan.object.\"b.stl\"]\nbond_cooling = \"on\"\n")
        val out = d.resolve("plate.gcode")
        val (status, lines) = run(d, "slice", listOf("hw.toml", "job.toml"), out)
        status shouldBe 0
        lines.joinToString("\n") shouldContain "bond-first cooling (b.stl)"
        val g = Files.readString(out)
        val o = byObject(g)
        // b: half the generic PLA's 100 % (S128 of 255), 220 C; a: the filament's 100 % (S255) and no temperature change of its own
        o.getValue("b.stl").first shouldBe setOf(128)
        o.getValue("b.stl").second shouldBe setOf(220)
        o["a.stl"]?.first?.contains(128) shouldBe false
        // control: the plate's fan-on command is the filament's full fan, so a 128 can only come from b's own setting
        g shouldContain "M106 S255"
    }
    "unlocked thin pillars, strength well over quality: the plan weighs bond-first and chooses it, and says why" {
        val d = dir("hw.toml" to hardware, "a.stl" to box(0.0, 0.0, 3.0, 3.0, 8.0), "b.stl" to box(0.0, 0.0, 3.0, 3.0, 8.0),
            "job.toml" to "[job.priorities]\nquality = \"1\"\nstrength = \"10\"\n")
        val (status, lines) = run(d, "plan", listOf("hw.toml", "job.toml"))
        status shouldBe 0
        val text = lines.joinToString("\n")
        text shouldContain "weighed (no plan.bond_cooling lock): bond-first cooling scored best"
        // control: quality over strength keeps the filament's cooling
        val d2 = dir("hw.toml" to hardware, "a.stl" to box(0.0, 0.0, 3.0, 3.0, 8.0), "b.stl" to box(0.0, 0.0, 3.0, 3.0, 8.0),
            "job.toml" to "[job.priorities]\nquality = \"10\"\nstrength = \"1\"\n")
        run(d2, "plan", listOf("hw.toml", "job.toml")).second.joinToString("\n") shouldContain "the filament's cooling scored best"
        // control: the same strength weight on 10 x 10 mm blocks (sections over enough_area) keeps the filament's cooling
        val d3 = dir("hw.toml" to hardware, "a.stl" to box(0.0, 0.0, 10.0, 10.0, 8.0), "b.stl" to box(0.0, 0.0, 10.0, 10.0, 8.0),
            "job.toml" to "[job.priorities]\nquality = \"1\"\nstrength = \"10\"\n")
        run(d3, "plan", listOf("hw.toml", "job.toml")).second.joinToString("\n") shouldContain "the filament's cooling scored best"
    }
})
