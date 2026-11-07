package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Flat layers held to the machine's Z step (hardware.machine.motion.z.step): the hole block at 0.21 mm layers on a machine
 * whose Z moves in 0.04 mm steps (test values) prints every layer 0.20 mm (0.21 held to the nearest whole steps, not
 * alternating 0.20 and 0.24), every top on a step, the last layers landing on the top in whole steps. Control: without the
 * step, 0.21 mm layers, their tops off the steps.
 */
class ZStepTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    fun tops(vararg cv: String): List<Double> {
        val out = Files.createTempDirectory("plymeld-zstep").resolve("block.gcode")
        val lines = ArrayList<String>()
        val status = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center", "plan.technique.flat.heights=one", "plan.technique.flat.layer.height=0.21 mm") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), lines::add)
        status shouldBe 0
        return Files.readAllLines(out).filter { it.startsWith(";Z:") }.map { it.removePrefix(";Z:").trim().toDouble() }
    }
    fun onSteps(zs: List<Double>, step: Double) = zs.all { z -> Math.abs(z / step - Math.round(z / step)) < 1e-6 }

    "every flat layer's top on a whole Z step; control: without the step, off it" {
        val held = tops("hardware.machine.example-corexy.motion.z.step=0.04 mm")
        (held.size > 15) shouldBe true
        onSteps(held, 0.04) shouldBe true
        // one thickness throughout, 0.21 held to the nearest whole steps (0.20), not alternating 0.20 and 0.24 to average
        // 0.21; only the last layers, landing on the block's top, may differ (each still whole steps, none under one)
        val heights = (listOf(held[0]) + held.zipWithNext { a, b -> b - a }).map { Math.round(it * 1000) }
        heights.dropLast(2).toSet() shouldBe setOf(200L)
        heights.all { it >= 40 } shouldBe true
        // control: without the step, the layers are 0.21 mm and their tops off the steps
        val free = tops()
        onSteps(free, 0.04) shouldBe false
        Math.round((free[2] - free[1]) * 1000) shouldBe 210L
    }
})
