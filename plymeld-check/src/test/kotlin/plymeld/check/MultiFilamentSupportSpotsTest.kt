package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.MultiFilament
import plymeld.core.Supports
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * The plan's support spots are printed for a MULTI-COLOR object too (seen to fail on a multi-color model: the plan held
 * seven spots, three of them locked, and the sliced file held no support in any; the per-filament planning never received
 * them, so with the switch unset nothing was generated). Two parts on two spools: a 20 mm block, and from 4 mm up a
 * shelf of the second color hanging 8 mm out over air. The support switch is unset (the bundled files leave it so): the
 * spots alone say where support goes.
 */
class MultiFilamentSupportSpotsTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.3mf")
    val all = ConfigFiles.bundled() + seamLockedForTests + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
    val s = SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(box))).setup)
    fun rect(x0: Long, y0: Long, x1: Long, y1: Long) = Polygon(longArrayOf(x0, y0, x1, y0, x1, y1, x0, y1))
    val n = 40
    val tops = LongArray(n) { 200L * (it + 1) }
    val block = List(n) { listOf(rect(100_000, 100_000, 120_000, 120_000)) }
    val shelf = List(n) { i -> if (i < 20) emptyList() else listOf(rect(120_000, 105_000, 128_000, 115_000)) }
    // the shelf's underside: a flat face at 4 mm (a spot of no height), the box around it in the placed frame
    val spot = Supports.Box(119_000, 129_000, 104_000, 116_000, 4_000, 4_000)
    fun supportLayers(regions: Supports.Regions?) = MultiFilament.plan(s, listOf(block, shelf), listOf("spool 1", "spool 2"), listOf(0, 1), tops, "spool 1", supportSpots = regions)
        .support.withIndex().filter { it.value.isNotEmpty() }.map { it.index }

    "the switch unset: a chosen spot under the second color's shelf is printed as support, under the shelf only" {
        s.enableSupport shouldBe null
        val layers = supportLayers(Supports.Regions(listOf(spot), emptyList()))
        layers.size shouldBeGreaterThan 5                                  // a column of layers under the 4 mm face
        layers.all { it < 20 } shouldBe true                               // nothing at or above the shelf
        supportLayers(null) shouldBe emptyList()                           // control: no spots, no support
        supportLayers(Supports.Regions(emptyList(), emptyList())) shouldBe emptyList()   // control: the plan chose none
    }
    "a locked spot is printed the same way" {
        supportLayers(Supports.Regions(emptyList(), listOf(spot))).size shouldBeGreaterThan 5
    }
})
