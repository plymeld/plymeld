package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.model.Context
import plymeld.model.Resolver

/**
 * The first-layer window (FirstLayerWindow): its readings per temperature (the lowest good height, the fastest good
 * speed), the rule that takes a first layer from them, and where it stands among the other first-layer values. The
 * readings are the test's own (a 0.4 mm nozzle: at 210 C good from 0.28 mm up to 140 mm/s, at 215 C from 0.24 mm up to
 * 180 mm/s); expected values worked out by hand from them.
 */
class FirstLayerWindowTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "qidi-q2", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "200 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "400 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to true,
        "hardware.machine.m.gcode.start" to "G28", "hardware.machine.m.gcode.end" to "M84",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        // white prints its first layer at 215 C (pla-generic's first layer: the print temperature + 5)
        "hardware.filament.white.like" to "pla-generic", "hardware.filament.white.temperature.print" to "210 C",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "white")
    val w = "hardware.filament.white.first_layer.window.pei"
    val window = listOf("$w.bore" to "0.4 mm",
        "$w.\"210 C\".height.min" to "0.28 mm", "$w.\"210 C\".speed.max" to "140 mm/s",
        "$w.\"215 C\".height.min" to "0.24 mm", "$w.\"215 C\".speed.max" to "180 mm/s")
    val box = ModelObject("box", listOf("box"), listOf(1), "box.stl")
    fun setup(vararg extra: Pair<String, Any>): ConfigSetup.Result {
        val entries = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }
        return ConfigSetup.setup(Config(ConfigStack(entries), listOf(box)))
    }
    fun first(r: ConfigSetup.Result) = (Resolver.resolve(r.setup, "plan.mechanism.adhesion.first_layer.height", Context(0)).value as Double) to (Resolver.resolve(r.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).value as Double)

    "the window decides the first layer below a plan lock and over the filament's, the machine's and plymeld's values" {
        // without a window: plymeld's stand-in height (the bundled plan: the layer height, 0.2 mm) and pla-generic's 80 mm/s
        first(setup()) shouldBe (0.2 to 80.0)
        // with it, at white's first-layer temperature, 215 C: its lowest good height at its fastest good speed, over the
        // filament's and the machine's speeds
        val r = setup(*window.toTypedArray(), "hardware.filament.white.motion.first_layer.speed" to "70 mm/s", "hardware.machine.m.motion.first_layer.speed" to "50 mm/s")
        first(r).first shouldBe (0.24 plusOrMinus 1e-9); first(r).second shouldBe 180.0
        Resolver.resolve(r.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).from shouldContain "first-layer window"
        r.firstLayer.single().let { it.heightMm shouldBe 0.24; it.speedMmS shouldBe 180.0; it.why shouldContain "215 C"; it.why shouldContain "the lowest good height" }
        // a plan value in a config locks each over the window
        first(setup(*window.toTypedArray(), "plan.mechanism.motion.first_layer.speed" to "35 mm/s")) shouldBe (0.24 to 35.0)
        first(setup(*window.toTypedArray(), "plan.mechanism.adhesion.first_layer.height" to "0.32 mm")) shouldBe (0.32 to 180.0)
        first(setup(*window.toTypedArray(), "plan.object.box.mechanism.adhesion.first_layer.height" to "0.32 mm")).first shouldBe 0.32
        // between the two (212 C): good only where good at both: the higher lowest height, the slower fastest speed
        first(setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "212 C")) shouldBe (0.28 to 140.0)
        // control: at 210 C its own readings
        first(setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "210 C")) shouldBe (0.28 to 140.0)
    }

    "a speed set for the filament on this plate: the window gives the height there; a plan lock still wins; past the fastest good speed: a warning, the speed stands" {
        val at100 = setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "100 mm/s", "hardware.filament.white.motion.first_layer.speed" to "70 mm/s")
        first(at100) shouldBe (0.24 to 100.0)
        Resolver.resolve(at100.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).from shouldContain "the speed set for this plate, 100 mm/s"
        first(setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "100 mm/s", "plan.mechanism.motion.first_layer.speed" to "35 mm/s")) shouldBe (0.24 to 35.0)
        // 250 mm/s: past 180, the fastest good speed: the window is not used, the plate's speed stands over the filament's 70
        val fast = setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "250 mm/s", "hardware.filament.white.motion.first_layer.speed" to "70 mm/s")
        first(fast) shouldBe (0.2 to 250.0)
        fast.warnings.any { "250 mm/s" in it && "past its fastest good speed, 180 mm/s" in it } shouldBe true
        // control: on another plate the speed means nothing
        first(setup(*window.toTypedArray(), "hardware.filament.white.bed.cool.first_layer.speed" to "100 mm/s")) shouldBe (0.24 to 180.0)
    }

    "the tool's first-layer range: a lowest good height below it is raised to it; one above it is not used" {
        // the tool lays from 0.26 mm: 0.24 is good, and so is every height above it: 0.26
        val raised = setup(*window.toTypedArray(), "hardware.tool.t1.layer.height.min" to "0.26 mm")
        first(raised).first shouldBe (0.26 plusOrMinus 1e-9)
        raised.firstLayer.single().why shouldContain "raised to the lowest the tool lays"
        // the tool's first layers reach 0.26 mm at most: 210 C's 0.28 is above it: a warning, the window not used
        val low = setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "210 C", "hardware.tool.t1.first_layer.height.max" to "0.26 mm")
        first(low) shouldBe (0.2 to 80.0)
        low.warnings.single() shouldContain "its lowest good height, 0.28 mm, is above what tool 't1' lays as a first layer"
        // control: within first_layer.height.max it is used
        first(setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "210 C", "hardware.tool.t1.first_layer.height.max" to "0.36 mm")) shouldBe (0.28 to 140.0)
        // a first-layer height set above what the tool lays as a first layer is warned about; within first_layer.height.max it is not
        setup("plan.mechanism.adhesion.first_layer.height" to "0.32 mm").warnings.single() shouldContain "outside what tool 't1' lays as a first layer, 0.08 mm to 0.30 mm"
        setup("plan.mechanism.adhesion.first_layer.height" to "0.32 mm", "hardware.tool.t1.first_layer.height.max" to "0.36 mm").warnings shouldBe emptyList()
    }

    "a window not used falls back with a warning: another bore, another plate, a temperature outside it" {
        // each: the same first layer as without the window (plymeld's stand-in height, pla-generic's speed), and a warning
        fun fallsBack(says: String, vararg extra: Pair<String, Any>) {
            val r = setup(*window.toTypedArray(), *extra)
            first(r) shouldBe first(setup(*extra)); first(r).second shouldBe 80.0
            r.firstLayer shouldBe emptyList(); r.warnings.single() shouldContain says
        }
        fallsBack("measured with a 0.40 mm nozzle; tool 't1' has 0.60 mm", "hardware.tool.t1.bore" to "0.6 mm")
        fallsBack("for build plate 'pei', not for 'cool'", "hardware.machine.m.loadout.plate" to "cool")
        fallsBack("outside that", "hardware.filament.white.temperature.first_layer" to "230 C")
        // control: as loaded, used and no warning
        setup(*window.toTypedArray()).warnings shouldBe emptyList()
    }

    "a window missing a reading or its bore, on a plate the config does not have, or with an unknown key is refused, naming it" {
        shouldThrow<ConfigError> { setup("$w.bore" to "0.4 mm", "$w.\"210 C\".height.min" to "0.28 mm") }.message!! shouldContain "speed.max missing"
        shouldThrow<ConfigError> { setup("$w.\"210 C\".height.min" to "0.28 mm", "$w.\"210 C\".speed.max" to "140 mm/s") }.message!! shouldContain "no bore"
        shouldThrow<ConfigError> { setup("hardware.filament.white.first_layer.window.glass.bore" to "0.4 mm", "hardware.filament.white.first_layer.window.glass.\"210 C\".height.min" to "0.28 mm",
            "hardware.filament.white.first_layer.window.glass.\"210 C\".speed.max" to "140 mm/s") }.message!! shouldContain "there is no build plate 'glass'"
        shouldThrow<ConfigError> { setup("$w.bore" to "0.4 mm", "$w.hot.height.min" to "0.28 mm", "$w.hot.speed.max" to "140 mm/s") }.message!! shouldContain "'hot' is not a temperature"
        shouldThrow<ConfigError> { setup(*window.toTypedArray(), "$w.\"210 C\".height.max" to "0.4 mm") }.message!! shouldContain "'height.max' is not a reading"
        // control: the window as recorded is taken
        setup(*window.toTypedArray()).firstLayer.size shouldBe 1
    }
})
