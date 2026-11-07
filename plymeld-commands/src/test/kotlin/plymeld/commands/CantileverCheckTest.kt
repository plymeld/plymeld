package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/**
 * The hard check before the weights, end to end: two stacked cubes, the small one on top, with equal
 * weights. Before the check the search laid them on a side face, the small cube's underside 5 mm over
 * nothing, because the face in the air cost 0.007 and the thicker layer bond gained 1.39. Now every candidate leaving
 * that face unheld is rejected as a cantilever: the chosen candidate holds up everything that needs it (upright, or on a
 * side with support under the small cube), and every pose but upright has rejected candidates. The geometry's own
 * controls are CantileverTest.
 */
class CantileverCheckTest : StringSpec({
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
        head.nozzle.x = "20 mm"
        head.nozzle.y = "15 mm"
        head.size.x = "60 mm"
        head.size.y = "50 mm"
        [plan.technique.flat]
        heights = "one"
    """.trimIndent()
    fun box(x0: Double, x1: Double, y0: Double, y1: Double, z0: Double, z1: Double): String {
        val v = listOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val faces = listOf(intArrayOf(0, 2, 1), intArrayOf(0, 3, 2), intArrayOf(4, 5, 6), intArrayOf(4, 6, 7), intArrayOf(0, 1, 5), intArrayOf(0, 5, 4),
            intArrayOf(1, 2, 6), intArrayOf(1, 6, 5), intArrayOf(2, 3, 7), intArrayOf(2, 7, 6), intArrayOf(3, 0, 4), intArrayOf(3, 4, 7))
        val sb = StringBuilder()
        for (f in faces) { sb.append("facet normal 0 0 0\nouter loop\n"); for (i in f) sb.append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); sb.append("endloop\nendfacet\n") }
        return sb.toString()
    }
    val d: Path = Files.createTempDirectory("plymeld-cantilever").also { d ->
        Files.writeString(d.resolve("hw.toml"), hardware)
        // the 20 mm cube with the 10 mm cube centered on top: one solid (the shared face left out: two shells meeting there)
        Files.writeString(d.resolve("cubes.stl"), "solid cubes\n" + box(0.0, 20.0, 0.0, 20.0, 0.0, 20.0) + box(5.0, 15.0, 5.0, 15.0, 20.0, 30.0) + "endsolid cubes\n")
    }
    val models = listOf(d.resolve("cubes.stl"))

    "with equal weights the model stays upright (as given): support priced from one pad outweighs the side poses' layer bond, and the as-given pose has standing; every candidate leaving the small cube over air is rejected as a cantilever" {
        // every pose searched in full (the first look would leave the side poses out before their cantilevers are read)
        val plan = Product(ConfigStack.load(listOf("hw.toml"), listOf("method.search.pose.shortlist_margin=1000"), d), models, null) { }.decide()
        val pose = plan.decisions.first { it.step.startsWith("pose") }
        pose.why shouldContain "pose 'as given"
        pose.why shouldContain "0.0 mm² unsupported"
        pose.why shouldContain "(quick estimate)"
        pose.facts.any { it.startsWith("weights in effect") && "layer_lines 0.5" in it } shouldBe true
        pose.facts.any { it.startsWith("the quick time estimate holds every layer") } shouldBe true
        val m = Regex("([0-9.]+) mm² unsupported \\(([0-9.]+) mm² of it gets support").find(pose.why) ?: error("no support figures in: ${pose.why}")
        m.groupValues[1] shouldBe m.groupValues[2]
        val rejectedPoses = pose.alternatives.filter { it.rejected?.contains("a cantilever no technique holds up") == true }.map { it.label.substringBefore(" ·") }.distinct()
        rejectedPoses.size shouldBe 5                        // upside down and the four sides; never as given
        rejectedPoses.any { it.startsWith("as given") } shouldBe false
        pose.alternatives.first { it.rejected?.contains("cantilever") == true }.rejected!! shouldContain "reaches"
    }
    "the plan's support is printed: on its side the small cube's underside is a cantilever, its support required, and the sliced file holds support lines in the spot (seen to fail: a spot of no height matched no layer, and the slice held no support)" {
        // the pose locked on a side face (the free search keeps the model upright, support having a price)
        Files.writeString(d.resolve("side.toml"), "[plan.object.\"cubes.stl\"]\npose.label = \"-Y down\"\npose.turn = \"1 0 0 0 0 -1 0 1 0\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("side.toml").toString(), "-m", models.single().toString(), "-o", d.resolve("cubes.gcode").toString()))
        val g = Files.readString(d.resolve("cubes.gcode"))
        (g.lines().count { it.startsWith(";TYPE:Support") || it.startsWith("; FEATURE: Support") } > 0) shouldBe true
    }
    // missing support only warns: support locked off over a cantilever is said, never refused
    "support locked off: on its side the small cube's underside is warned about as a cantilever and the plan stands; controls: upright, and support decided, warn nothing" {
        Files.writeString(d.resolve("side.toml"), "[plan.object.\"cubes.stl\"]\npose.label = \"-Y down\"\npose.turn = \"1 0 0 0 0 -1 0 1 0\"\n")
        Files.writeString(d.resolve("off.toml"), "[plan.support]\nenabled = \"false\"\n")
        Files.writeString(d.resolve("upright.toml"), "[plan]\npose = \"as-given\"\n")
        fun plan(vararg cfs: String) = Product(ConfigStack.load(listOf("hw.toml") + cfs, emptyList(), d), models, null) { }.decide()
        val side = plan("side.toml", "off.toml")
        side.warnings.size shouldBe 1
        side.warnings.single() shouldContain "'cubes.stl': support is off (plan.support.enabled = false)"
        side.warnings.single() shouldContain "a cantilever, its lowest layer reaching"
        // wave overhangs by default lay it from its supported side; control: as_laid, over air
        side.warnings.single() shouldContain "it will be laid as wave overhangs grown from its supported side"
        Files.writeString(d.resolve("laid.toml"), "[plan.faces]\nunder.technique = \"as_laid\"\n")
        plan("side.toml", "off.toml", "laid.toml").warnings.single() shouldContain "it will print over air (only support lays it)"
        side.describe() shouldContain "warning: 'cubes.stl': support is off"
        // the plan stands: the pose is the locked one, nothing refused
        side.decisions.first { it.step.startsWith("pose") }.why shouldContain "-Y down"
        plan("upright.toml", "off.toml").warnings shouldBe emptyList()
        plan("side.toml").warnings shouldBe emptyList()
        // the slice says it too
        val out = SliceCommand().test(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("side.toml").toString(), "-cf", d.resolve("off.toml").toString(),
            "-m", models.single().toString(), "-o", d.resolve("off.gcode").toString()))
        out.output shouldContain "warning: 'cubes.stl': support is off"
    }
})
