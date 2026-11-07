package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain

/** Resolving a config: keys, `like`, scopes, formulas at their site. Expected values worked by hand. */
class ConfigTest : StringSpec({
    fun cfg(vararg kv: Pair<String, Any>, models: List<ModelObject> = emptyList()): Config =
        Config(ConfigStack(kv.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), models)
    val benchy = ModelObject("dual benchy", listOf("hull", "deck"), listOf(1, 2), "benchy.3mf")
    val base = arrayOf<Pair<String, Any>>(
        "hardware.machine.m.build.z" to "250 mm",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.filament.pla.temperature.print" to "210 C",
        "hardware.filament.pla.temperature.first_layer" to "= filament.temperature.print + 5 C",
        "hardware.machine.m.loadout.tool.t1.filament" to "pla")

    "a key naming two kinds of thing is an error; control: the same key twice for one thing is one entry" {
        shouldThrow<ConfigError> { cfg(*base, "hardware.plate.pla.surface" to "smooth") }.message!! shouldContain "key 'pla' names both a plate and a filament"
        cfg(*base, "hardware.filament.pla.diameter" to "1.75 mm").keysOf("filament") shouldContainExactly listOf("pla")
    }
    "an unknown path, a wrong unit, a reference to a missing key are errors, all reported together" {
        val e = shouldThrow<ConfigError> { cfg(*base, "hardware.filament.pla.temperature.nozzle" to "200 C", "hardware.tool.t1.bore" to "0.4 s", "hardware.machine.m.loadout.plate" to "pei") }
        e.problems.size shouldBe 3
        e.message!! shouldContain "not a setting plymeld knows"
        e.message!! shouldContain "not a unit of length"
        e.message!! shouldContain "there is no plate 'pei'"
    }
    "a bare number is read in the default unit with a warning; control: with a unit, no warning" {
        cfg(*base, "hardware.filament.pla.diameter" to 1.75).warnings.single() shouldContain "no unit"
        cfg(*base, "hardware.filament.pla.diameter" to "1.75 mm").warnings shouldBe emptyList()
    }
    "like: values taken over as written, formulas worked out for the entry that uses them" {
        val c = cfg(*base, "hardware.filament.white.like" to "pla", "hardware.filament.white.temperature.print" to "205 C")
        val e = c.entry("hardware.filament.white.temperature.first_layer".split('.'))!!
        e.source shouldContain "via like pla"
        c.quantity(e, Dimension.TEMPERATURE, Site(filament = "white")) shouldBe (210.0 plusOrMinus 1e-9)
        // control: the base keeps its own
        c.quantity(c.entry("hardware.filament.pla.temperature.first_layer".split('.'))!!, Dimension.TEMPERATURE, Site()) shouldBe (215.0 plusOrMinus 1e-9)
    }
    "like: a loop, or naming another kind, is an error" {
        shouldThrow<ConfigError> { cfg(*base, "hardware.filament.a.like" to "b", "hardware.filament.b.like" to "a") }.message!! shouldContain "loops"
        shouldThrow<ConfigError> { cfg(*base, "hardware.filament.a.like" to "t1") }.message!! shouldContain "not a filament"
    }
    "context names: tool means the tool in play; inside an entry its kind means itself" {
        val c = cfg(*base, "plan.technique.flat.layer.height" to "= clamp(round(tool.bore / 2, 0.04 mm), 0.08 mm, tool.layer.height.max)")
        val e = c.entry("plan.technique.flat.layer.height".split('.'))!!
        c.quantity(e, Dimension.LENGTH, Site(tool = "t1", machine = "m")) shouldBe (0.2 plusOrMinus 1e-9)
        shouldThrow<IllegalArgumentException> { c.quantity(e, Dimension.LENGTH, Site()) }.message!! shouldContain "there is none here"
    }
    "a formula loop is an error naming the paths" {
        val c = cfg(*base, "plan.technique.flat.layer.height" to "= plan.technique.flat.step.max", "plan.technique.flat.step.max" to "= plan.technique.flat.layer.height")
        shouldThrow<IllegalArgumentException> { c.quantity(c.entry("plan.technique.flat.layer.height".split('.'))!!, Dimension.LENGTH, Site()) }.message!! shouldContain
            "loop: plan.technique.flat.layer.height -> plan.technique.flat.step.max -> plan.technique.flat.layer.height"
    }
    "objects keyed from the model (spaces to -) or by `from`; count makes #2...; #1 is the plain key" {
        cfg(*base, models = listOf(benchy)).objects.map { it.key } shouldContainExactly listOf("dual-benchy")
        val c = cfg(*base, "model.object.benchy.from" to "dual benchy", "model.object.benchy.count" to 3L, models = listOf(benchy))
        c.objects.map { it.key } shouldContainExactly listOf("benchy", "benchy#2", "benchy#3")
    }
    "scopes: copy over object over general; a part scope over both" {
        val c = cfg(*base, "model.object.benchy.from" to "dual benchy", "model.object.benchy.count" to 2L,
            "plan.technique.flat.layer.height" to "0.2 mm", "plan.object.benchy.technique.flat.layer.height" to "0.16 mm",
            "plan.object.benchy#2.technique.flat.layer.height" to "0.12 mm", "plan.object.benchy.part.hull.technique.flat.layer.height" to "0.28 mm",
            models = listOf(benchy))
        fun at(i: Int, part: String? = null) = c.quantity(c.scoped("plan", "technique.flat.layer.height".split('.'), Site(obj = c.objects[i], part = part))!!, Dimension.LENGTH, Site())
        at(0) shouldBe (0.16 plusOrMinus 1e-12)
        at(1) shouldBe (0.12 plusOrMinus 1e-12)
        at(1, "hull") shouldBe (0.28 plusOrMinus 1e-12)
        at(0, "deck") shouldBe (0.16 plusOrMinus 1e-12)
    }
    // uniform and adaptive layers became one technique, flat layers: every
    // the naming rules:
    // the surface techniques and seam styles are techniques: their values under plan.technique.<name>,
    // their switches the job's locks. Old keys are not refused by name (there are no earlier users to migrate)
    "the surface techniques' and seam styles' keys moved under plan.technique, their switches to the job's locks; an old key is an unknown key; controls: the new keys are read" {
        for ((k, v) in listOf("job.requests.ironing.speed" to "20 mm/s", "job.requests.ironing.enabled" to true, "job.object.benchy.requests.fuzzy.thickness" to "0.3 mm",
                "plan.layer.walls.scarf.enabled" to false, "plan.layer.walls.seam" to "back", "plan.object.benchy.layer.faces.sloped_top.z_antialiasing" to true,
                "plan.technique.scarf_seam.plain_style" to "back_seam", "plan.seam_start" to "back"))
            shouldThrow<ConfigError> { cfg(*base, k to v, models = listOf(benchy)) }.message!! shouldContain "$k (test): not a setting plymeld knows"
        // controls: the new keys are read, at their scopes
        for ((k, v) in listOf("plan.faces.flat_top.technique" to "ironing", "plan.object.benchy.faces.side.technique" to "fuzzy_skin", "plan.faces.sloped_top.technique" to "as_laid",
                "plan.seam" to "back", "plan.technique.scarf_seam.enabled" to false, "plan.technique.ironing.speed" to "20 mm/s", "plan.object.benchy.technique.fuzzy_skin.thickness" to "0.3 mm",
                "plan.technique.corner_seam.corner_angle" to "40 deg"))
            cfg(*base, k to v, models = listOf(benchy)).entry(k.split('.'))!!.value shouldBe v
        // a lock names a technique that serves it, nothing else
        shouldThrow<ConfigError> { cfg(*base, "plan.faces.flat_top.technique" to "fuzzy_skin") }
    }
    // a flat band states its heights: "one" or "follow"; "derive" refused with the reason
    "a band's heights: one and follow read; derive refused, saying bands lock the plan" {
        val heights = "plan.object.benchy.band.1.heights"
        cfg(*base, heights to "one").entry(heights.split('.'))!!.value shouldBe "one"
        cfg(*base, heights to "follow").entry(heights.split('.'))!!.value shouldBe "follow"
        val e = shouldThrow<ConfigError> { cfg(*base, heights to "derive") }
        e.problems.size shouldBe 1
        e.message!! shouldContain "a band's heights are \"one\" or \"follow\", not \"derive\": an object's bands lock its plan"
        // control: derive is still the object's choice
        cfg(*base, "plan.object.benchy.technique.flat.heights" to "derive").entry("plan.object.benchy.technique.flat.heights".split('.'))!!.value shouldBe "derive"
    }
    "a scope or refinement naming nothing on the plate is reported, not an error" {
        cfg(*base, "plan.object.ghost.technique.flat.layer.height" to "0.2 mm", models = listOf(benchy)).warnings.single() shouldContain "no object on the plate is keyed 'ghost'"
    }
    "fingerprints: one per section, unchanged by plan values; control: a hardware change changes hardware's only" {
        val a = cfg(*base).fingerprints()
        cfg(*base, "plan.pose" to "as-given").fingerprints() shouldBe a
        val b = cfg(*base, "hardware.filament.pla.diameter" to "2.85 mm").fingerprints()
        (b["hardware"] == a["hardware"]) shouldBe false
    }
})
