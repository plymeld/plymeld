package plymeld.check

import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.model.Setup

/**
 * The machine, tool and filaments the slicing tests print with, on plymeld's bundled files: a machine of the bundled
 * `qidi-q2` model, one 0.4 mm tool, and a filament per slot `like` the bundled PLA. The plan values are TEST VALUES, stated
 * so the tests' geometry (layer heights, line widths, walls, shells, infill, the purge tower's place and size) is fixed by the
 * test and not by plymeld's defaults as they change: they are not plymeld's defaults and not for printing.
 */
val TEST_MACHINE: List<Pair<String, Any>> = listOf(
    "hardware.machine.tm.model" to "qidi-q2", "hardware.machine.tm.build.x" to "270 mm", "hardware.machine.tm.build.y" to "270 mm",
    "hardware.machine.tm.build.z" to "256 mm", "hardware.machine.tm.build.origin" to "front-left",
    "hardware.machine.tm.motion.print.speed" to "600 mm/s", "hardware.machine.tm.motion.travel.speed" to "500 mm/s",
    "hardware.machine.tm.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.tm.motion.travel.acceleration" to "10000 mm/s2",
    "hardware.machine.tm.firmware.flavor" to "klipper", "hardware.machine.tm.firmware.arcs" to true,
    "hardware.machine.tm.firmware.exclude_object" to true,
    "hardware.machine.tm.gcode.start" to "G28", "hardware.machine.tm.gcode.end" to "M104 S0", "hardware.machine.tm.gcode.pause" to "PAUSE",
    "hardware.tool.t1.machine" to "tm", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
    "hardware.tool.t1.layer.height.min" to "0.08 mm", "hardware.tool.t1.layer.height.max" to "0.28 mm",
    "hardware.filament.test-pla.like" to "pla-generic", "hardware.filament.test-pla.color" to "#FFFFFF",
    "hardware.filament.test-pla-2.like" to "pla-generic", "hardware.filament.test-pla-2.color" to "#202020",
    "hardware.machine.tm.loadout.plate" to "pei",
)

/** The test values for what the tests' geometry depends on (see [TEST_MACHINE]). */
val TEST_PLAN: List<Pair<String, Any>> = listOf(
    "plan.technique.flat.layer.height" to "0.2 mm", "plan.mechanism.adhesion.first_layer.height" to "0.2 mm",
    "plan.mechanism.adhesion.first_layer.line.width" to "0.5 mm",
    // two walls: the count is 1 + round((thickness - outer width) / inner width); three are 1.32 mm, four 1.77 mm
    "plan.layer.walls.thickness" to "0.87 mm", "plan.layer.walls.outer.line.width" to "0.42 mm", "plan.layer.walls.inner.line.width" to "0.45 mm",
    "plan.layer.walls.inner_first" to true, "plan.seam" to "back_seam",
    "plan.layer.top.thickness" to "0.6 mm", "plan.layer.bottom.thickness" to "0.6 mm",
    "plan.layer.top.pattern" to "monotonic", "plan.layer.bottom.pattern" to "monotonic", "plan.layer.top.line.width" to "0.3 mm",
    "plan.layer.infill.density" to "20%", "plan.layer.infill.pattern" to "grid", "plan.layer.infill.line.width" to "0.45 mm",
    "plan.layer.infill.angle" to "45 deg", "plan.layer.solid.line.width" to "0.42 mm",
    "plan.mechanism.motion.visible.speed" to "200 mm/s", "plan.mechanism.motion.visible.acceleration" to "3000 mm/s2",
    "plan.mechanism.motion.first_layer.speed" to "50 mm/s", "plan.mechanism.motion.bridge.speed" to "50 mm/s",
    "plan.support.enabled" to false, "plan.faces.under.technique" to "as_laid",
    "plan.mechanism.purge.tower.enabled" to true, "plan.mechanism.purge.tower.x" to "165 mm", "plan.mechanism.purge.tower.y" to "232 mm",
    "plan.mechanism.purge.tower.brim.enabled" to false, "plan.mechanism.purge.tower.side_share.min" to "0",
    "plan.mechanism.adhesion.brim.where" to "outer", "plan.mechanism.adhesion.brim.width" to "5 mm",
    "plan.mechanism.adhesion.brim.gap" to "0.1 mm",
)

/** One object, "test", of one part on slot 1: the object the single-model tests slice. */
val TEST_OBJECT = ModelObject("test", listOf("test"), listOf(1), "test.stl")

/**
 * A setup from [TEST_MACHINE] and [TEST_PLAN] on the bundled files, [extra] over them (by config path), for [models];
 * [slots] filaments on the one tool (the first `test-pla`, the rest `test-pla-2`).
 */
fun testSetup(vararg extra: Pair<String, Any>, models: List<ModelObject> = listOf(TEST_OBJECT), slots: Int = 1): Setup {
    val loadout = if (slots <= 1) listOf("hardware.machine.tm.loadout.tool.t1.filament" to "test-pla")
        else (1..slots).map { "hardware.machine.tm.loadout.slot.$it.filament" to (if (it == 1) "test-pla" else "test-pla-2") }
    // a value in [extra] replaces the test value of the same path (the last of a path wins)
    val values = LinkedHashMap<String, Any>().apply { for ((k, v) in TEST_MACHINE + loadout + TEST_PLAN + extra) { remove(k); put(k, v) } }
    val entries = ConfigFiles.bundled() + values.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
    return ConfigSetup.setup(Config(ConfigStack(entries), models)).setup
}
