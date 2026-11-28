package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.SliceSettings

/**
 * One fallback rule for a machine's and a tool's values (Config's fallbacks): a machine's own value, else its model's, and for a
 * top speed or acceleration still unset, its travel value; a tool's own value, else its machine's model's toolhead's of its
 * index. The expected numbers are the bundled models' as 03_models.toml gives them, or the values written here.
 */
class MachineFallbackTest : StringSpec({
    /** A config of the bundled files and [values] (source "test"), sliced on [machine] when given. */
    fun config(vararg values: Pair<String, Any>, machine: String? = null): Config =
        Config(ConfigStack(ConfigFiles.bundled() + values.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), emptyList(), machine)
    fun Config.at(path: String): ConfigFiles.Entry? = entry(path.split('.'))
    fun Config.qty(path: String, dim: Dimension, site: Site): Double = quantity(at(path)!!, dim, site)

    "a machine's value: its own, else its model's, else (a top speed or acceleration) its travel value; each with where it came from" {
        val c = config("hardware.machine.q.model" to "qidi-q2", "hardware.machine.own.model" to "qidi-q2", "hardware.machine.own.motion.speed.max" to "450 mm/s",
            "hardware.machine.k.model" to "generic-klipper", "hardware.machine.k.motion.travel.speed" to "350 mm/s",
            "hardware.machine.k.motion.travel.acceleration" to "8000 mm/s2", "hardware.machine.bare.model" to "generic-klipper", machine = "q")
        // its model's: the bundled Q2's top speed, sourced at its line in 03_models.toml, through the machine's model
        c.qty("hardware.machine.q.motion.speed.max", Dimension.SPEED, Site(machine = "q")) shouldBe 600.0
        c.at("hardware.machine.q.motion.speed.max")!!.source shouldBe "bundled 03_models.toml:18 via the machine's model qidi-q2"
        c.qty("hardware.machine.q.build.x", Dimension.LENGTH, Site(machine = "q")) shouldBe 270.0
        c.qty("hardware.machine.q.heater.heater_bed.temperature.max", Dimension.TEMPERATURE, Site(machine = "q")) shouldBe 125.0
        // its lines: the model's value and the machine's model line (the g-code's source block reads them as used)
        c.origins(c.at("hardware.machine.q.motion.speed.max")!!) shouldBe listOf("hardware.model.qidi-q2.motion.speed.max", "hardware.machine.q.model")
        // its own wins
        c.qty("hardware.machine.own.motion.speed.max", Dimension.SPEED, Site(machine = "own")) shouldBe 450.0
        c.at("hardware.machine.own.motion.speed.max")!!.source shouldBe "test"
        // no model value: its travel value stands in, said so
        c.qty("hardware.machine.k.motion.speed.max", Dimension.SPEED, Site(machine = "k")) shouldBe 350.0
        c.at("hardware.machine.k.motion.speed.max")!!.source shouldBe "the machine's travel speed: no top speed set, on the machine or by its model"
        c.qty("hardware.machine.k.motion.acceleration.max", Dimension.ACCEL, Site(machine = "k")) shouldBe 8000.0
        // controls: nothing to take from: no value at all (not a stand-in number)
        c.at("hardware.machine.bare.motion.speed.max") shouldBe null
        c.at("hardware.machine.k.build.x") shouldBe null
    }

    "a travel value worked out from the maximum does not stand in for it: a formula reading the maximum names it and says why; control: a number stands in" {
        val cycle = config("hardware.machine.k.model" to "generic-klipper", "hardware.machine.k.motion.travel.acceleration" to "= machine.motion.acceleration.max",
            "hardware.machine.k.motion.print.acceleration" to "= machine.motion.acceleration.max / 2")
        cycle.at("hardware.machine.k.motion.acceleration.max") shouldBe null
        val e = shouldThrow<MissingReference> { cycle.qty("hardware.machine.k.motion.print.acceleration", Dimension.ACCEL, Site(machine = "k")) }.message!!
        e shouldContain "'machine.motion.acceleration.max' names no value (set hardware.machine.k.motion.acceleration.max: the machine's highest acceleration"
        e shouldContain "its travel acceleration cannot stand in for it: hardware.machine.k.motion.travel.acceleration = \"= machine.motion.acceleration.max\" (test) is worked out from it"
        // a loop that does not run through the stand-in is still a loop, told as one
        val loop = config("hardware.machine.k.model" to "generic-klipper", "hardware.machine.k.motion.travel.speed" to "= machine.motion.print.speed",
            "hardware.machine.k.motion.print.speed" to "= machine.motion.travel.speed")
        shouldThrow<FormulaLoop> { loop.qty("hardware.machine.k.motion.print.speed", Dimension.SPEED, Site(machine = "k")) }
        // control: the travel acceleration a number, it stands in
        val number = config("hardware.machine.k.model" to "generic-klipper", "hardware.machine.k.motion.travel.acceleration" to "6000 mm/s2",
            "hardware.machine.k.motion.print.acceleration" to "= machine.motion.acceleration.max / 2")
        number.qty("hardware.machine.k.motion.print.acceleration", Dimension.ACCEL, Site(machine = "k")) shouldBe 3000.0
    }

    "a tool's value: its own, else its machine's model's toolhead's, the one of its index; control: another index, another toolhead" {
        // a model of the user's with two toolheads that differ
        val c = config("hardware.model.two.like" to "generic-klipper", "hardware.model.two.toolhead.0.lid.height" to "100 mm",
            "hardware.model.two.toolhead.1.lid.height" to "200 mm", "hardware.model.two.toolhead.1.heat.time" to "20 s",
            "hardware.machine.m.model" to "two", "hardware.tool.a.machine" to "m", "hardware.tool.a.index" to "1",
            "hardware.tool.b.machine" to "m", "hardware.tool.b.index" to "2", "hardware.tool.own.machine" to "m", "hardware.tool.own.index" to "2",
            "hardware.tool.own.lid.height" to "50 mm", "hardware.tool.none.machine" to "m")
        fun lid(t: String) = c.qty("hardware.tool.$t.lid.height", Dimension.LENGTH, Site(tool = t))
        lid("a") shouldBe 100.0
        lid("b") shouldBe 200.0
        lid("own") shouldBe 50.0
        lid("none") shouldBe 100.0                          // no index: the first tool, toolhead 0
        c.at("hardware.tool.b.lid.height")!!.source shouldBe "test via the machine's model two, toolhead 1"
        c.origins(c.at("hardware.tool.b.lid.height")!!) shouldBe listOf("hardware.model.two.toolhead.1.lid.height", "hardware.tool.b.machine", "hardware.machine.m.model", "hardware.tool.b.index")
        c.qty("hardware.tool.b.heat.time", Dimension.TIME, Site(tool = "b")) shouldBe 20.0
        // control: toolhead 0 sets no heat time, so a tool on it has none
        c.at("hardware.tool.a.heat.time") shouldBe null
    }

    "the visible lines' stand-ins follow each machine's maximums (a quarter of each), never above its printing speed and acceleration; control: two machines' differ, as one number for both would not" {
        val c = config("hardware.machine.c5.model" to "flashforge-c5", "hardware.machine.c5.motion.print.acceleration" to "15000 mm/s2",
            "hardware.machine.small.model" to "generic-klipper", "hardware.machine.small.motion.speed.max" to "240 mm/s",
            "hardware.machine.small.motion.acceleration.max" to "6000 mm/s2", "hardware.machine.small.motion.print.speed" to "200 mm/s",
            "hardware.machine.small.motion.print.acceleration" to "3000 mm/s2",
            "hardware.machine.slow.like" to "small", "hardware.machine.slow.motion.print.speed" to "40 mm/s", "hardware.machine.slow.motion.print.acceleration" to "1000 mm/s2",
            machine = "c5")
        fun visible(m: String) = c.qty("plan.mechanism.motion.visible.speed", Dimension.SPEED, Site(machine = m)) to
            c.qty("plan.mechanism.motion.visible.acceleration", Dimension.ACCEL, Site(machine = m))
        // the Creator 5's model: 600 mm/s and 30000 mm/s2 (03_models.toml); its print speed the model's 300 mm/s
        visible("c5") shouldBe (150.0 to 7500.0)
        visible("small") shouldBe (60.0 to 1500.0)
        visible("c5") shouldNotBe visible("small")
        // a quarter of the maximums above what the machine prints at: held to its printing speed and acceleration
        visible("slow") shouldBe (40.0 to 1000.0)
        c.at("plan.mechanism.motion.visible.speed")!!.source shouldStartWith ConfigFiles.BUNDLED_SOURCE
    }

    "a machine of a model that gives no motion values plans with what it needed before: its travel speed stands in; control: without it, the message names the top speed and the travel speed" {
        val machine = arrayOf<Pair<String, Any>>(
            "hardware.machine.k.model" to "generic-klipper", "hardware.machine.k.build.x" to "220 mm", "hardware.machine.k.build.y" to "220 mm",
            "hardware.machine.k.build.z" to "250 mm", "hardware.machine.k.motion.print.speed" to "200 mm/s",
            "hardware.machine.k.motion.print.acceleration" to "4000 mm/s2", "hardware.machine.k.motion.travel.acceleration" to "= machine.motion.print.acceleration",
            "hardware.machine.k.firmware.flavor" to "klipper", "hardware.machine.k.firmware.arcs" to false, "hardware.machine.k.firmware.exclude_object" to true,
            "hardware.machine.k.gcode.start" to "G28", "hardware.machine.k.gcode.end" to "M84",
            "hardware.plate.pei.bed.PLA.temperature.print" to "65 C", "hardware.plate.pei.first_layer.squish" to "0.1 mm",
            "hardware.tool.t1.machine" to "k", "hardware.tool.t1.bore" to "0.4 mm",
            "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
            "hardware.machine.k.loadout.plate" to "pei", "hardware.machine.k.loadout.tool.t1.filament" to "pla-generic")
        val cube = ModelObject("cube", listOf("body"), listOf(1), "cube.stl")
        fun setup(vararg v: Pair<String, Any>) = ConfigSetup.setup(Config(ConfigStack(ConfigFiles.bundled() + v.map { (k, x) -> ConfigFiles.Entry(k.split('.'), x, "test") }), listOf(cube))).setup
        val s = setup(*machine, "hardware.machine.k.motion.travel.speed" to "300 mm/s")
        SliceSettings.missing(s, Context(0)) shouldBe emptyList()
        val read = SliceSettings.of(s, Context(0))
        read.visibleSpeed shouldBe 75.0                  // its travel speed, 300 mm/s, a quarter
        read.visibleAccel shouldBe 1000.0                // its travel acceleration, its print acceleration's 4000 mm/s2, a quarter
        // control: no travel speed either: refused, naming both values that would give it
        val e = shouldThrow<ConfigError> { setup(*machine) }
        withClue(e.problems.joinToString("\n")) {
            e.problems.any { "set hardware.machine.k.motion.speed.max" in it && "or its travel speed, hardware.machine.k.motion.travel.speed, which stands in for it" in it } shouldBe true
        }
    }

    "a tool that sets none takes its model's toolhead's head shape and preheat: the same numbers the machine description holds (an independent reading of the model)" {
        val r = ConfigSetup.setup(Config(ConfigStack(ConfigFiles.bundled() + listOf(
            "hardware.machine.c5.model" to "flashforge-c5", "hardware.machine.c5.build.x" to "256 mm", "hardware.machine.c5.build.y" to "256 mm",
            "hardware.machine.c5.build.z" to "256 mm", "hardware.machine.c5.motion.print.speed" to "300 mm/s", "hardware.machine.c5.motion.travel.speed" to "600 mm/s",
            "hardware.machine.c5.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.c5.motion.travel.acceleration" to "20000 mm/s2",
            "hardware.machine.c5.firmware.flavor" to "klipper", "hardware.machine.c5.firmware.arcs" to false, "hardware.machine.c5.firmware.exclude_object" to true,
            "hardware.machine.c5.gcode.end" to "M84", "hardware.plate.pei.bed.PLA.temperature.print" to "65 C", "hardware.plate.pei.first_layer.squish" to "0.1 mm",
            "hardware.tool.t2.machine" to "c5", "hardware.tool.t2.index" to "2", "hardware.tool.t2.bore" to "0.4 mm",
            "hardware.tool.t2.layer.height.min" to "0.08 mm", "hardware.tool.t2.layer.height.max" to "0.3 mm",
            "hardware.machine.c5.loadout.plate" to "pei", "hardware.machine.c5.loadout.tool.t2.filament" to "pla-generic",
        ).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(ModelObject("cube", listOf("body"), listOf(2), "cube.stl")))).setup
        val head = Models.bundled.forModel("flashforge-c5").toolhead(1)!!
        val s = SliceSettings.of(r, Context(0))
        s.nozzleAngleDeg shouldBe head["tip_cone_deg"]
        s.nozzleHeightUm shouldBe Math.round(head["head.nozzle.height"] * 1000)
        s.headReachUm!!.toList() shouldBe listOf(head["head_minus_x"], head["head_plus_x"], head["head_minus_y"], head["head_plus_y"]).map { Math.round(it * 1000) }
        (Resolver.resolve(r, "hardware.tool.heat.time", Context(0)).value as Number).toDouble() shouldBe 15.0   // 03_models.toml: toolhead 1's heat.time
        // the machine's start template its model's, as a machine that sets none takes it
        s.startGcode shouldContain "M109 S{first_layer_nozzle_temperature} T{initial_tool}"
        Resolver.resolve(r, "hardware.tool.heat.time", Context(0)).from shouldContain "via the machine's model flashforge-c5, toolhead 1"
        r.used shouldContain "hardware.model.flashforge-c5.toolhead.1.heat.time"
    }
})
