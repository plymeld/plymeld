package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.core.Feature
import plymeld.core.LayerPlanOut
import plymeld.core.Planner
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * The hidden core in thicker layers (layer heights per region in their simplest case; plan.technique.flat.core.layers): a 30 x 30 x 10 mm block
 * in 0.1 mm layers on a tool whose thickest layer is 0.3 mm. With core.layers = 3, the sparse infill of each group of
 * three layers is laid once, on the group's last layer, 0.3 mm thick; the two layers before it lay no sparse infill
 * (the block's sparse region is the same on every layer, so the group shares all of it), and the plastic laid as sparse
 * infill over the block is the same as without the core (to a tenth). Controls: core.layers = 1 lays sparse infill on
 * every layer at the layer height; the bundled value is as many layers as fit the thickest layer (3 here).
 */
class CoreLayersTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "250 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "M83", "hardware.machine.m.gcode.end" to "M104 S0",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "0.05 mm", "hardware.tool.t1.layer.height.max" to "0.3 mm",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "pla-generic",
        "plan.technique.flat.layer.height" to "0.1 mm", "plan.layer.infill.pattern" to "rectilinear", "plan.layer.infill.density" to "20%")
    val n = 100
    val tops = LongArray(n) { 100L * (it + 1) }
    val outline = List(n) { listOf(Polygon(longArrayOf(0, 0, 30_000, 0, 30_000, 30_000, 0, 30_000))) }
    fun settings(extra: List<Pair<String, Any>>): SliceSettings {
        val all = ConfigFiles.bundled() + seamLockedForTests + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        return SliceSettings.of(ConfigSetup.setup(Config(ConfigStack(all), listOf(ModelObject("a", listOf("a"), listOf(1), "a.3mf")))).setup)
    }
    fun plan(extra: List<Pair<String, Any>>) = Planner.plan(settings(extra), outline, tops)
    fun sparse(l: LayerPlanOut) = l.paths.filter { it.feature == Feature.SPARSE }
    fun lengthMm(ps: List<plymeld.core.ToolPath>) = ps.sumOf { p -> (0 until p.xy.size / 2 - 1).sumOf { k -> Math.hypot((p.xy[2 * k + 2] - p.xy[2 * k]).toDouble(), (p.xy[2 * k + 3] - p.xy[2 * k + 1]).toDouble()) } } / 1000.0
    /** Plastic laid as sparse infill (mm3: each bead's cross-section, as the writer extrudes it, times its length). */
    fun plastic(layers: List<LayerPlanOut>) = layers.sumOf { l -> sparse(l).sumOf { p -> val h = p.heightUm / 1000.0; val w = p.widthUm / 1000.0; lengthMm(listOf(p)) * h * (w - h * (1 - Math.PI / 4)) } }

    "core.layers = 3: a group's two first layers lay no sparse infill, the third lays it 0.3 mm thick; the plastic is the same as without the core" {
        val core = plan(listOf("plan.technique.flat.core.layers" to "3"))
        val plain = plan(listOf("plan.technique.flat.core.layers" to "1"))
        val thick = core.indices.filter { i -> sparse(core[i]).any { it.heightUm == 300L } }
        (thick.size > 20) shouldBe true
        for (i in thick) {
            sparse(core[i]).all { it.heightUm == 300L } shouldBe true
            lengthMm(sparse(core[i - 1])) shouldBe 0.0
            lengthMm(sparse(core[i - 2])) shouldBe 0.0
        }
        // the core's groups are the layers 1-3, 4-6, ...: every third layer from the first sparse one
        thick.zipWithNext().all { (a, b) -> b - a == 3 } shouldBe true
        val mid = 20 until 80
        val p = plastic(mid.map { core[it] }); val q = plastic(mid.map { plain[it] })
        (Math.abs(p - q) / q < 0.1) shouldBe true
        // control: without the core every layer lays sparse infill at the layer height
        for (i in mid) { (lengthMm(sparse(plain[i])) > 0) shouldBe true; sparse(plain[i]).all { it.heightUm == 100L } shouldBe true }
    }
    "the bundled value: as many layers as fit the tool's thickest layer (3 at 0.1 mm under 0.3 mm)" {
        settings(emptyList()).coreLayers shouldBe 3
        settings(listOf("hardware.tool.t1.layer.height.max" to "0.15 mm")).coreLayers shouldBe 1
    }
})
