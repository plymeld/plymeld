package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.FlashStudioHeader
import plymeld.core.MultiFilament
import plymeld.core.PauseLock
import plymeld.core.Pauses
import plymeld.geom.Polygon
import plymeld.model.SliceSettings
import plymeld.program.Extrude
import plymeld.program.FilamentSwap
import plymeld.program.Pause
import plymeld.program.PrintProgram
import plymeld.program.ToolChange

/**
 * Two spools on ONE tool: a filament swapped by hand at a pause (a top part on a fifth filament, swapped in for the
 * base's on the same toolhead). A base on spool 1 and, from 4 mm up, a top
 * on spool 5, both on tool 1: the program marks the swap where the tool first prints spool 5, a pause before that layer
 * is accepted, no pause (or one too early, with spool 1 printed after it) is refused; the file's filament accounting
 * moves the tool's lines to filament 5 from the swap's note on.
 */
class FilamentSwapTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0", "hardware.machine.m.gcode.pause" to "M25",   // the Creator 5's own pause (evidenced)
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    val all = ConfigFiles.bundled() + seamLockedForTests + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
    val s = SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup)
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    val n = 40
    val tops = LongArray(n) { 200L * (it + 1) }
    val base = List(n) { i -> if (i < 20) listOf(rect(100_000, 100_000, 120_000, 120_000)) else emptyList() }
    val top = List(n) { i -> if (i >= 20) listOf(rect(100_000, 100_000, 120_000, 120_000)) else emptyList() }
    val r = MultiFilament.plan(s, listOf(base, top), listOf("spool 1", "spool 5"), listOf(0, 1), tops, "spool 1")
    val toolOf = { _: String -> 0 }                          // both spools on tool 1
    val order = MultiFilament.layerOrder(MultiFilament.present(r, null, null), listOf("spool 1", "spool 5"), "spool 1")
    val steps = MultiFilament.steps(r, order, toolOf, "box", tops, 0, tempOf = { sp, _ -> if (sp == "spool 5") 205 else 210 })
    val program = PrintProgram(steps, emptyList())

    "the swap is marked once, where the tool first prints spool 5, with the new filament's temperature; no tool change" {
        val swaps = steps.filterIsInstance<FilamentSwap>()
        swaps.size shouldBe 1
        swaps.single().let { it.tool shouldBe 0; it.fromSpool shouldBe "spool 1"; it.toSpool shouldBe "spool 5"; it.fromId shouldBe 1; it.toId shouldBe 5; it.tempC shouldBe 205 }
        steps.none { it is ToolChange } shouldBe true
        // before the swap only spool 1's lines, after it only spool 5's (the parts split at 4 mm)
        val at = steps.indexOf(swaps.single())
        steps.subList(0, at).filterIsInstance<Extrude>().isNotEmpty() shouldBe true
        steps.subList(at, steps.size).filterIsInstance<Extrude>().isNotEmpty() shouldBe true
    }
    "a pause before the swap's layer is accepted and lands there; no pause, or one with spool 1 printed after it, is refused" {
        val (withPause, landed) = Pauses.insert(program, listOf(PauseLock.AtZ(4.2)))   // before the first layer whose top is 4.2: the top's first
        landed.single() shouldContain "before layer 21"
        val i = withPause.steps.indexOfFirst { it is Pause }; val j = withPause.steps.indexOfFirst { it is FilamentSwap }
        j shouldBe i + 1                                        // the mark moved to right after the pause: the swap happens there
        withPause.steps.count { it is FilamentSwap } shouldBe 1
        shouldThrow<IllegalArgumentException> { Pauses.insert(program, emptyList()) }.message!! shouldContain "no pause between spool 1's last line and spool 5's first"
        shouldThrow<IllegalArgumentException> { Pauses.insert(program, listOf(PauseLock.AtZ(2.0))) }.message!! shouldContain "no pause"
        // one layer too early: before the base's last layer (top 4.0), so spool 1 prints after the pause
        shouldThrow<IllegalArgumentException> { Pauses.insert(program, listOf(PauseLock.AtZ(4.0))) }.message!! shouldContain "no pause"
        // a purge tower's base laid with the tool after the pause (outside any object block) is the NEW filament's, not a refusal
        val withTower = ArrayList(program.steps)
        val p0 = withTower.indexOfFirst { it is plymeld.program.LayerStart && it.index == 20 } + 1
        withTower.add(p0, Pause("test"))
        withTower.add(p0 + 1, Extrude("Prime tower", longArrayOf(0, 0, 1000, 0), false, 4_200, 400, 200, 0))
        Pauses.placeSwaps(withTower).first.let { placed -> placed.indexOfFirst { it is FilamentSwap } shouldBe placed.indexOfFirst { it is Pause } + 1 }
        // control: two spools on two tools need no pause
        val two = MultiFilament.steps(r, order, { sp -> if (sp == "spool 5") 1 else 0 }, "box", tops, 0)
        two.none { it is FilamentSwap } shouldBe true
        Pauses.insert(PrintProgram(two, emptyList()), emptyList())
    }
    "after the swap the new filament purges: the tower's band follows the swap's note, or the top's own sparse infill first with purge.into = infill; control: no purge without a tower" {
        val purges = MultiFilament.purgesPerLayer(order, toolOf, 0, mapOf(0 to "spool 1"))
        purges.sum() shouldBe 1                                   // the one swap, on the top's first layer
        purges[20] shouldBe 1
        MultiFilament.changesPerLayer(order, toolOf, 0).sum() shouldBe 0   // not a tool change
        val tower = plymeld.core.PurgeTower.square(60_000, 0, 30.0, 440, 2_000, purges, tops, compact = false, maxGapUm = 300)
        fun afterSwap(steps: List<plymeld.program.Step>): List<Pair<String, Boolean>> {
            val at = steps.indexOfFirst { it is FilamentSwap }
            var inObject = false
            return steps.subList(at, steps.size).mapNotNull { st ->
                when (st) { is plymeld.program.ObjectStart -> { inObject = true; null }; is plymeld.program.ObjectEnd -> { inObject = false; null }; is Extrude -> st.feature to inObject; else -> null }
            }.take(60)
        }
        val towerOnly = afterSwap(MultiFilament.steps(r, order, toolOf, "box", tops, 0, tower, loadedOn = mapOf(0 to "spool 1")))
        towerOnly.first() shouldBe ("Prime tower" to false)
        (towerOnly.indexOfFirst { it.first != "Prime tower" } > 0) shouldBe true       // then the top's own lines
        val intoInfill = afterSwap(MultiFilament.steps(r, order, toolOf, "box", tops, 0, tower, loadedOn = mapOf(0 to "spool 1"), purgeInto = "infill"))
        intoInfill.first() shouldBe ("Sparse infill" to true)
        (intoInfill.any { it.first == "Prime tower" }) shouldBe true
        // the pause still lands the mark right after it, the purge following the mark
        val placed = Pauses.insert(PrintProgram(MultiFilament.steps(r, order, toolOf, "box", tops, 0, tower, loadedOn = mapOf(0 to "spool 1")), emptyList()), listOf(PauseLock.AtZ(4.2))).program.steps
        placed.indexOfFirst { it is FilamentSwap } shouldBe placed.indexOfFirst { it is Pause } + 1
        afterSwap(placed).first() shouldBe ("Prime tower" to false)
        // control: without a tower nothing purges after the swap; the new filament's first line is its infill, hidden, as
        // after any change
        afterSwap(steps).first() shouldBe ("Sparse infill" to true)
        afterSwap(steps).none { it.first == "Prime tower" } shouldBe true
    }
    "written: the swap's note comes right after the pause, the new filament's temperature is waited for, and the header counts filament 5 from there" {
        val placed = Pauses.insert(program, listOf(PauseLock.AtZ(4.2))).program
        val g = java.io.StringWriter().also { plymeld.gcode.KlipperWriter(s, emptyMap(), "test").write(placed, it) }.toString()
        val lines = g.lines()
        val pause = lines.indexOfFirst { it.startsWith("; PAUSE:") }; val note = lines.indexOfFirst { it.startsWith("; FILAMENT_SWAP: T0 filament 1 -> 5") }
        (pause in 0 until note) shouldBe true
        lines.subList(pause, note).none { it.startsWith("G1") && " E" in it && !it.startsWith("G1 E-") } shouldBe true     // nothing printed between
        lines.subList(note, note + 6).any { it.startsWith("M109 S205 T0") } shouldBe true                                // the new filament's temperature
        val used = FlashStudioHeader.filamentByTool(g, 5)
        (used[0] > 0 && used[4] > 0) shouldBe true
        (used[1] == 0.0 && used[2] == 0.0 && used[3] == 0.0) shouldBe true
        // control: the note dropped, every line is filament 1's
        FlashStudioHeader.filamentByTool(g.replace("; FILAMENT_SWAP:", "; (no swap)"), 5)[4] shouldBe 0.0
    }
    "the header's filament accounting follows the swap note: the tool's lines count as filament 5 after it" {
        val text = "M83\nT0\nG1 X1 Y0 E1.5\nG1 X2 Y0 E2.5\n; FILAMENT_SWAP: T0 filament 1 -> 5 (spool 1 -> spool 5, swapped by hand at the pause)\nG1 X3 Y0 E4\nT1\nG1 X4 E0.5\n"
        FlashStudioHeader.filamentByTool(text, 5).toList() shouldBe listOf(4.0, 0.5, 0.0, 0.0, 4.0)
        FlashStudioHeader.filamentByTool(text.replace(Regex("; FILAMENT_SWAP[^\n]*\n"), ""), 5).toList() shouldBe listOf(8.0, 0.5, 0.0, 0.0, 0.0)   // control: no note, all on filament 1
    }
})
