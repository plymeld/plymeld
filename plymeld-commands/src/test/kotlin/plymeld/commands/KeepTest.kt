package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.ConfigStack
import plymeld.core.decide.TechniquePlan
import java.nio.file.Files
import java.nio.file.Path

/**
 * A value kept across re-plans with `!`: `plymeld plan` with a plan file decides every line of
 * it again except the kept ones; `plymeld slice` takes every line as a lock. The stacked cubes of CantileverCheckTest.
 */
class KeepTest : StringSpec({
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
    val d: Path = Files.createTempDirectory("plymeld-keep").also { d ->
        Files.writeString(d.resolve("hw.toml"), hardware)
        Files.writeString(d.resolve("cubes.stl"), "solid cubes\n" + box(0.0, 20.0, 0.0, 20.0, 0.0, 20.0) + box(5.0, 15.0, 5.0, 15.0, 20.0, 30.0) + "endsolid cubes\n")
    }
    val models = listOf(d.resolve("cubes.stl"))
    val said = ArrayList<String>()
    fun product(cfs: List<String>, rePlan: Boolean) = Product(ConfigStack.load(cfs, emptyList(), d), models, null, null, rePlan) { said += it }
    fun heightOf(p: plymeld.core.decide.Plan) = (p.decisions.first { it.value is TechniquePlan }.value as TechniquePlan).layerHeightMm!!

    "the first plan, written; its free layer height is not 0.14 mm (the kept value below)" {
        val pr = product(listOf("hw.toml"), rePlan = true)
        val plan = pr.decide()
        Files.writeString(d.resolve("plan.toml"), pr.planFile(plan))
        val text = Files.readString(d.resolve("plan.toml"))
        text shouldContain "technique.flat.layer.height = \""
        text shouldContain "pose.turn = \"1 0 0 0 1 0 0 0 1\""
        text shouldNotContain "= \"!"
        (Math.abs(heightOf(plan) - 0.14) > 1e-6) shouldBe true
    }
    "re-plan with a kept layer height: the height stays, the rest is decided again, and the plan written back keeps the mark; control: unmarked, the free height returns" {
        val text = Files.readString(d.resolve("plan.toml"))
        // the technique's height kept; the band's (dropped by a re-plan, a lock for slice) set to match, so the file is consistent either way
        val kept = text.replace(Regex("technique\\.flat\\.layer.height = \"[^\"]*\""), "technique.flat.layer.height = \"!0.14 mm\"")
            .replace(Regex("band\\.1\\.layer.height = \"[^\"]*\""), "band.1.layer.height = \"0.14 mm\"")
        Files.writeString(d.resolve("kept.toml"), kept)
        said.clear()
        val pr = product(listOf("hw.toml", "kept.toml"), rePlan = true)
        val plan = pr.decide()
        heightOf(plan) shouldBe (0.14 plusOrMinus 1e-9)
        said.any { it.startsWith("plan file") && "1 value(s) kept (!): plan.object.\"cubes.stl\".technique.flat.layer.height" in it } shouldBe true
        val again = pr.planFile(plan)
        again shouldContain "technique.flat.layer.height = \"!0.14 mm\""
        again.lines().count { it.contains("= \"!") } shouldBe 1
        // control: the same value without the mark is decided again
        Files.writeString(d.resolve("unmarked.toml"), kept.replace("\"!0.14 mm\"", "\"0.14 mm\""))
        (Math.abs(heightOf(product(listOf("hw.toml", "unmarked.toml"), rePlan = true).decide()) - 0.14) > 1e-6) shouldBe true
        // slice (no re-plan): every line of the plan file is a lock, the mark or not
        heightOf(product(listOf("hw.toml", "unmarked.toml"), rePlan = false).decide()) shouldBe (0.14 plusOrMinus 1e-9)
        heightOf(product(listOf("hw.toml", "kept.toml"), rePlan = false).decide()) shouldBe (0.14 plusOrMinus 1e-9)
    }
    "a kept band without the pose kept is refused, naming pose.turn; with the pose kept it is taken" {
        val text = Files.readString(d.resolve("plan.toml"))
        val band = text.replace(Regex("band\\.1\\.layer.height = \"([^\"]*)\""), "band.1.layer.height = \"!$1\"")
        Files.writeString(d.resolve("band.toml"), band)
        shouldThrow<UsageError> { product(listOf("hw.toml", "band.toml"), rePlan = true) }.message!! shouldContain "keep plan.object.\"cubes.stl\".pose.turn too"
        Files.writeString(d.resolve("band_pose.toml"), band.replace("pose.turn = \"", "pose.turn = \"!"))
        said.clear()
        val pr = product(listOf("hw.toml", "band_pose.toml"), rePlan = true)
        val again = pr.planFile(pr.decide())
        // the whole band is kept with its one marked line (a band without its technique is no band), and written back marked whole
        said.any { it.startsWith("plan file") && "band.1.technique" in it && "band.1.layer.height" in it } shouldBe true
        again shouldContain "band.1.technique = \"!flat\""
        again shouldContain "pose.turn = \"!1 0 0 0 1 0 0 0 1\""
    }
})
