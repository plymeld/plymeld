package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import plymeld.model.withObjectSetups

/**
 * Multi-color step 5 end to end: the two-color Benchy on a machine of the bundled `flashforge-c5` model with two tools
 * (a filament each, the model's own pre-heat (hardware.model.flashforge-c5.tool.heat.time: no tool sets one), idle 100 C
 * below print: a test value) written as g-code, and the tool-change sequence
 * read back from the TEXT.
 */
class ToolChangeGcodeTest : StringSpec({
    "two-color Benchy on the C5: every change is idle-old, T, wait-new, then a rise before any travel" {
        val base = testSetup(
            "hardware.machine.tm.model" to "flashforge-c5", "hardware.machine.tm.build.x" to "256 mm", "hardware.machine.tm.build.y" to "256 mm",
            "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.gcode.start" to "G90", "hardware.machine.tm.firmware.exclude_object" to false,
            "hardware.tool.t2.machine" to "tm", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.28 mm",
            "hardware.machine.tm.loadout.tool.t2.filament" to "test-pla-2",
            "hardware.filament.test-pla.temperature.idle" to "= filament.temperature.print - 100 C", "hardware.filament.test-pla-2.temperature.idle" to "= filament.temperature.print - 100 C",
            models = listOf(plymeld.config.ModelObject("test", listOf("A", "B"), listOf(1, 2), "benchy-dual.3mf")))
        val o = base.objects[0]
        val setup = base.withObjectSetups(listOf(plymeld.model.ObjectSetup(o.name, o.intent, "spool 1", o.overrides, listOf(
            plymeld.model.PartSetup("A", "spool 1", plymeld.model.Values(plymeld.model.Entity.INTENT, "A")),
            plymeld.model.PartSetup("B", "spool 2", plymeld.model.Values(plymeld.model.Entity.INTENT, "B"))))))
        // place the boat on the bed (the halves sit around the origin): center at (128, 128), bottom at 0
        val raw = benchyDualParts()
        val b = plymeld.core.decide.PoseDecision.merged(raw).bounds()
        val move = plymeld.core.decide.Pose("placed", plymeld.core.decide.Pose.IDENTITY, doubleArrayOf(128 - (b[0] + b[3]) / 2, 128 - (b[1] + b[4]) / 2, -b[2]))
        val out = java.io.StringWriter()
        plymeld.core.IncrementalSlicer(raw.map(move::apply), "test", "benchy-dual").slice(setup, out)
        val lines = out.toString().lines()
        // plymeld's changes come after the first layer starts; the start template has its own "T0"
        val firstLayer = lines.indexOfFirst { it.startsWith(";LAYER_CHANGE") || it.startsWith("; CHANGE_LAYER") }
        val tIdx = lines.indices.filter { it > firstLayer && Regex("T\\d").matches(lines[it].trim()) }
        println("  two-color Benchy, C5: ${tIdx.size} tool changes in ${lines.size} lines")
        (tIdx.size > 100) shouldBe true
        plymeld.core.Predict.toolChanges(out.toString()) shouldBe tIdx.size
        var toolNow = 0
        var prevChange = firstLayer
        for (k in tIdx) {
            val newT = lines[k].trim().substring(1).toInt()
            // preheat (the model's tool.heat.time, the tools setting none): heated since the previous change
            (prevChange + 1 until k).any { lines[it].startsWith("M104 S") && lines[it].contains(" T$newT ; preheat") } shouldBe true
            prevChange = k
            // idle the old tool (print temperature - 100, hardware.filament.temperature.idle_drop), then T, then wait
            val idle = Regex("M104 S(\\d+) T(\\d)").matchEntire(lines[k - 1].trim())!!
            idle.groupValues[2].toInt() shouldBe toolNow
            val wait = Regex("M109 S(\\d+) T(\\d)").matchEntire(lines[k + 1].trim())!!
            wait.groupValues[2].toInt() shouldBe newT
            (wait.groupValues[1].toInt() - idle.groupValues[1].toInt()) shouldBe 100
            // the first motion after the swap is Z only (rise), before any X/Y
            val firstMove = (k + 2 until lines.size).first { lines[it].startsWith("G1 ") && (" X" in lines[it] || " Y" in lines[it] || " Z" in lines[it]) }
            (" Z" in lines[firstMove] && " X" !in lines[firstMove] && " Y" !in lines[firstMove]) shouldBe true
            toolNow = newT
        }
        // the tower's share, counted from the text (extruded filament under "Prime tower")
        val byFeature = plymeld.core.Predict.filamentByFeature(out.toString())
        println("  purge tower %.0f mm of %.0f mm filament".format(java.util.Locale.ROOT, byFeature["Prime tower"] ?: 0.0, byFeature.values.sum()))
    }
})
