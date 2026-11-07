package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.SliceSettings

/** A config as the pipeline takes it (ConfigSetup). Expected values worked by hand from the values below and the bundled files. */
class ConfigSetupTest : StringSpec({
    val machine = listOf(
        "hardware.machine.m.model" to "qidi-q2", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "200 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "400 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to true,
        "hardware.machine.m.gcode.start" to "G28", "hardware.machine.m.gcode.end" to "M84",
        "hardware.plate.pei.bed.PLA.temperature.print" to "65 C", "hardware.plate.pei.first_layer.squish" to "0.1 mm",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        "hardware.tool.t2.machine" to "m", "hardware.tool.t2.index" to 2L, "hardware.tool.t2.bore" to "0.6 mm",
        "hardware.tool.t2.layer.height.min" to "= tool.bore / 5", "hardware.tool.t2.layer.height.max" to "= tool.bore * 0.75",
        "hardware.filament.white.like" to "pla-generic", "hardware.filament.white.temperature.print" to "205 C",
        "hardware.machine.m.loadout.plate" to "pei",
        "hardware.machine.m.loadout.tool.t1.filament" to "white", "hardware.machine.m.loadout.tool.t2.filament" to "petg-generic")
    val benchy = ModelObject("benchy", listOf("hull", "deck"), listOf(1, 2), "benchy.3mf")
    fun setup(vararg extra: Pair<String, Any>, models: List<ModelObject> = listOf(benchy)): ConfigSetup.Result {
        val entries = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        return ConfigSetup.setup(Config(ConfigStack(entries), models))
    }

    "every bundled filament resolves in full: each setting a slice reads, its label range around its print temperature" {
        val bundled = ConfigFiles.bundled().filter { it.path.size >= 3 && it.path[0] == "hardware" && it.path[1] == "filament" }.map { it.path[2] }.distinct()
        bundled.size shouldBe 9                                 // PLA, PETG, TPU, ABS, ASA, PC, PA, HIPS, PVA
        for (f in bundled) {
            val r = setup("hardware.machine.m.loadout.tool.t1.filament" to f)
            val s = SliceSettings.of(r.setup)                    // throws when a setting has no value
            val lo = (Resolver.resolve(r.setup, "hardware.filament.temperature.min").value as Number).toInt()
            val hi = (Resolver.resolve(r.setup, "hardware.filament.temperature.max").value as Number).toInt()
            ((s.nozzleTempC in lo..hi) && (s.nozzleTempFirstC in lo..hi)) shouldBe true
            (s.maxVolumetric > 0 && s.retractMm > 0 && s.slowDownLayerTimeS > 0) shouldBe true
        }
    }

    "bed temperatures are the plate's for the filament's kind; a filament's own for the plate wins; none is an error naming what to set" {
        fun bed(plate: String, filament: String, vararg extra: Pair<String, Any>): Triple<Int, Int, List<String>> {
            val r = setup("hardware.machine.m.loadout.plate" to plate, "hardware.machine.m.loadout.tool.t1.filament" to filament, "hardware.machine.m.loadout.tool.t2.filament" to filament, *extra)
            val s = SliceSettings.of(r.setup)
            return Triple(s.bedTempC, s.bedTempFirstC, r.warnings)
        }
        bed("pei", "abs-generic") shouldBe Triple(100, 100, emptyList())             // pei's ABS; the first layer as the rest
        bed("cool", "pla-generic") shouldBe Triple(35, 35, emptyList())              // cool's PLA
        bed("pei", "pla-generic", "hardware.plate.pei.bed.PLA.temperature.first_layer" to "70 C") shouldBe Triple(65, 70, emptyList())   // this config's PLA on pei is 65 C
        // a filament's own for this plate wins, its first layer following it; control: its own for another plate does not apply
        bed("pei", "pla-generic", "hardware.filament.pla-generic.bed.pei.temperature.print" to "70 C", "hardware.plate.pei.bed.PLA.temperature.first_layer" to "65 C") shouldBe Triple(70, 70, emptyList())
        bed("pei", "pla-generic", "hardware.filament.pla-generic.bed.cool.temperature.print" to "40 C") shouldBe Triple(65, 65, emptyList())
        // none: an error naming both places; the plate says it does not suit ABS
        shouldThrow<ConfigError> { bed("cool", "abs-generic") }.message!!.let {
            it shouldContain "hardware.plate.cool.bed.ABS.temperature.print"; it shouldContain "hardware.filament.abs-generic.bed.cool.temperature.print"; it shouldContain "does not suit ABS"
        }
        // given one anyway, it prints, with the plate's warning; control: a plate of the user's own like cool that says it suits ABS is not warned
        bed("cool", "abs-generic", "hardware.filament.abs-generic.bed.cool.temperature.print" to "90 C").third.single() shouldContain "build plate 'cool' does not suit ABS"
        bed("mine", "abs-generic", "hardware.plate.mine.like" to "cool", "hardware.plate.mine.suits.ABS" to true, "hardware.plate.mine.bed.ABS.temperature.print" to "90 C").third shouldBe emptyList()
        // a filament's bed temperature for a plate that does not exist is an error, not ignored
        shouldThrow<ConfigError> { bed("pei", "pla-generic", "hardware.filament.pla-generic.bed.pie.temperature.print" to "70 C") }.message!! shouldContain "no build plate 'pie'"
    }

    "every setting a slice reads comes from the config and the bundled files" {
        val r = setup()
        val s = SliceSettings.of(r.setup)
        s.layerHeightUm shouldBe 200L                       // clamp(round(0.4/2, 0.04), 0.08, 0.3)
        s.wallLoops shouldBe 3                              // 1 + round((1.2 - 0.44) / 0.44)
        s.nozzleTempC shouldBe 205                          // white: like pla-generic, its own print temperature
        s.nozzleTempFirstC shouldBe 210                     // pla-generic's formula worked out for white
        s.bedTempC shouldBe 65                              // the config's PLA on pei, over the bundled 60 C
        s.fanMinPct shouldBe (100.0 plusOrMinus 1e-9)       // 100% as the pipeline's percent
        s.elephantFootUm shouldBe 100L
        s.wallGenerator shouldBe "arachne"                  // variable-width walls, the bundled default
        s.brimType shouldBe "no_brim"
        s.fuzzySkin shouldBe "none"
        s.printerModel shouldBe "qidi-q2"
        s.travelAccel shouldBe (10000.0 plusOrMinus 1e-9)
    }
    "a part on another tool gets its own nozzle's values" {
        val r = setup()
        // the deck is on tool 2 (0.6 mm): round(0.3, 0.04) = 0.32 mm (7.5 steps round up), inside 0.12..0.45
        (Resolver.resolve(r.setup, "plan.technique.flat.layer.height", Context(0, 1)).value as Double) shouldBe (0.32 plusOrMinus 1e-9)
        (Resolver.resolve(r.setup, "plan.technique.flat.layer.height", Context(0, 0)).value as Double) shouldBe (0.2 plusOrMinus 1e-9)
        (Resolver.resolve(r.setup, "hardware.filament.temperature.print", Context(0, 1)).value as Double) shouldBe (240.0 plusOrMinus 1e-9)
    }
    "the bed: build area from 0,0 at the front left, or centered" {
        val a = SliceSettings.of(setup().setup)
        (Resolver.resolve(setup().setup, "hardware.machine.build.right").value as Double) shouldBe (250.0 plusOrMinus 1e-9)
        a.maxPrintHeightMm shouldBe (250.0 plusOrMinus 1e-9)
        (Resolver.resolve(setup("hardware.machine.m.build.origin" to "center").setup, "hardware.machine.build.front").value as Double) shouldBe (-100.0 plusOrMinus 1e-9)
    }
    "job: priorities become the goal weights, a wall surface technique locked as fuzzy skin turns it on; per object" {
        val r = setup("job.priorities.quality" to 10L, "job.priorities.speed" to 3L, "plan.object.benchy.faces.side.technique" to "fuzzy_skin")
        Resolver.resolve(r.setup, "job.priorities").value shouldBe "quality=10,speed=3,layer_lines=4"      // the bundled measure weight rides along
        Resolver.resolve(r.setup, "plan.technique.fuzzy_skin.where").value shouldBe "external"
        // the technique's own where, under plan.technique
        Resolver.resolve(setup("plan.faces.side.technique" to "fuzzy_skin", "plan.technique.fuzzy_skin.where" to "all").setup, "plan.technique.fuzzy_skin.where").value shouldBe "all"
        // control: as laid, or never locked
        Resolver.resolve(setup("plan.faces.side.technique" to "as_laid").setup, "plan.technique.fuzzy_skin.where").value shouldBe "none"
        Resolver.resolve(setup().setup, "plan.technique.fuzzy_skin.where").value shouldBe "none"
    }
    // a filament's own technique value (technique values as config per filament, carried
    // by like, tweaked per part): over the bundled default and the job's general value, under a user plan value and an object's
    "a filament's technique value: over the bundled default, under a plan value a config sets and an object's own; a tool's over the filament's; carried by like" {
        fun flow(vararg kv: Pair<String, Any>) = Resolver.resolve(setup(*kv).setup, "plan.technique.ironing.flow", Context(0))
        val bundled = flow()
        bundled.from shouldContain "bundled"
        // the filament's own (white likes pla-generic: set on pla-generic it is taken over through like)
        flow("hardware.filament.white.technique.ironing.flow" to "0.12").value shouldBe 0.12
        flow("hardware.filament.pla-generic.technique.ironing.flow" to "0.13").value shouldBe 0.13
        // the tool's over the filament's own; the machine's between
        flow("hardware.filament.white.technique.ironing.flow" to "0.12", "hardware.filament.white.tool.t1.technique.ironing.flow" to "0.14").value shouldBe 0.14
        flow("hardware.filament.white.technique.ironing.flow" to "0.12", "hardware.filament.white.machine.m.technique.ironing.flow" to "0.15").value shouldBe 0.15
        // a plan value a config of yours sets wins (the first-layer speed's rule), and so does the object's own
        flow("hardware.filament.white.technique.ironing.flow" to "0.12", "plan.technique.ironing.flow" to "0.2").value shouldBe 0.2
        flow("hardware.filament.white.technique.ironing.flow" to "0.12", "plan.object.benchy.technique.ironing.flow" to "0.3").value shouldBe 0.3
        // control: a setting outside plan.technique on the filament is not a scope (refused by the schema, so not read here)
        flow("hardware.filament.white.technique.ironing.spacing" to "0.3 mm").value shouldBe bundled.value
    }
    // a switch is a boolean beside its choice; the pipeline's setting still holds the choice, "none" when off
    "switches: enabled and the choice beside it give the pipeline's choice, off its none; controls: each switch off, and on" {
        fun v(id: String, vararg kv: Pair<String, Any>) = Resolver.resolveOrNull(setup(*kv).setup, id, Context(0))?.value
        // the seams unset are decided per object: nothing set up front; plan.seam is read as it is, and scarf seams'
        // switch gives their where ("none" off)
        v("plan.technique.scarf_seam.where") shouldBe null
        v("plan.seam") shouldBe null
        v("plan.technique.scarf_seam.where", "plan.technique.scarf_seam.enabled" to true) shouldBe "smooth"
        v("plan.technique.scarf_seam.where", "plan.technique.scarf_seam.enabled" to false) shouldBe "none"
        v("plan.seam", "plan.seam" to "back") shouldBe "back"
        v("plan.technique.scarf_seam.where", "plan.seam" to "back") shouldBe null                                   // the placement alone: the scarf still decided
        v("plan.technique.scarf_seam.where", "plan.technique.scarf_seam.enabled" to true, "plan.object.benchy.technique.scarf_seam.where" to "all") shouldBe "all"   // per object
        v("plan.technique.scarf_seam.where", "plan.object.benchy.technique.scarf_seam.enabled" to false) shouldBe "none"   // a lock per object
        // the surface techniques locked: each locks its technique; as laid, none
        v("plan.faces.sloped_top.z_antialiasing", "plan.faces.sloped_top.technique" to "z_antialiasing") shouldBe true
        v("plan.faces.sloped_top.z_antialiasing", "plan.faces.sloped_top.technique" to "as_laid") shouldBe false
        v("plan.mechanism.adhesion.brim.where") shouldBe "no_brim"                                                 // bundled: off
        v("plan.mechanism.adhesion.brim.where", "plan.mechanism.adhesion.brim.enabled" to true) shouldBe "outer_only"
        v("plan.mechanism.adhesion.prime.line") shouldBe null                                                     // unset: by the print (PrimeLine)
        v("plan.mechanism.adhesion.prime.line", "plan.mechanism.adhesion.prime.enabled" to false) shouldBe "none"
        v("plan.mechanism.adhesion.prime.line", "plan.mechanism.adhesion.prime.line" to "tab") shouldBe "tab"
        v("plan.mechanism.adhesion.prime.line", "plan.mechanism.adhesion.prime.enabled" to true) shouldBe "tab"       // on, no brim: the tab
        v("plan.mechanism.adhesion.prime.line", "plan.mechanism.adhesion.prime.enabled" to true, "plan.mechanism.adhesion.brim.enabled" to true) shouldBe "tab"
        Resolver.resolve(setup("plan.faces.flat_top.technique" to "ironing").setup, "plan.technique.ironing.type", Context(0)).value shouldBe "top"
        Resolver.resolve(setup("plan.faces.flat_top.technique" to "as_laid").setup, "plan.technique.ironing.type", Context(0)).value shouldBe "none"
        Resolver.resolve(setup().setup, "plan.technique.ironing.type", Context(0)).value shouldBe "none"
    }
    // every value plymeld supplies is an overridable setting: the infill's reach into the walls was a constant
    "the infill's reach into the walls is a setting: bundled 25 % with where it came from, overridable per object; control: the bundled value without one" {
        val bundled = Resolver.resolve(setup().setup, "plan.layer.infill.wall_overlap", Context(0))
        (bundled.value as Double) shouldBe (0.25 plusOrMinus 1e-12)
        bundled.from shouldContain "00_defaults.toml"
        val set = Resolver.resolve(setup("plan.object.benchy.layer.infill.wall_overlap" to "40%").setup, "plan.layer.infill.wall_overlap", Context(0))
        (set.value as Double) shouldBe (0.4 plusOrMinus 1e-12)
        set.from shouldBe "test"
        SliceSettings.of(setup("plan.layer.infill.wall_overlap" to "10%").setup).infillWallOverlap shouldBe (0.1 plusOrMinus 1e-12)
    }
    "priorities: accuracy is a goal, and the weights come in the goals' order whatever the config's; none = equal weights on all six" {
        val r = setup("job.priorities.reliability" to 2L, "job.priorities.accuracy" to 3L, "job.priorities.quality" to 10L)
        Resolver.resolve(r.setup, "job.priorities").value shouldBe "quality=10,accuracy=3,reliability=2,layer_lines=4"
        Resolver.resolve(setup().setup, "job.priorities").value shouldBe "quality=1,accuracy=1,speed=1,material=1,strength=1,reliability=1,layer_lines=4"
    }
    "a filament's density reaches the pipeline in g/cm3, in any unit; unset = none (material is then compared by volume)" {
        (Resolver.resolve(setup().setup, "hardware.filament.density").value as Double) shouldBe (1.24 plusOrMinus 1e-12)          // white: like pla-generic's
        (Resolver.resolve(setup("hardware.filament.white.density" to "1250 kg/m3").setup, "hardware.filament.density").value as Double) shouldBe (1.25 plusOrMinus 1e-12)
        // control: the same config with no density anywhere (the bundled ones left out) has none
        val entries = ConfigFiles.bundled().filter { it.path.last() != "density" } + machine.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        Resolver.resolveOrNull(ConfigSetup.setup(Config(ConfigStack(entries), listOf(benchy))).setup, "hardware.filament.density") shouldBe null
    }
    "a setting with no value is named by the config key that sets it, with its id; controls: set, it resolves; a setup that knows no keys names only the id" {
        val entries = ConfigFiles.bundled() + machine.filter { it.first != "hardware.machine.m.model" }.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }
        val bare = ConfigSetup.setup(Config(ConfigStack(entries), listOf(benchy))).setup
        val e = shouldThrow<IllegalStateException> { Resolver.resolve(bare, "hardware.machine.model") }.message!!
        e shouldContain "hardware.machine.m.model has no value for object 'benchy'"   // the machine in play named for <key>
        e shouldContain "setting hardware.machine.model"
        Resolver.resolve(setup().setup, "hardware.machine.model").value shouldBe "qidi-q2"
        val noKeys = plymeld.model.Setup(bare.machine, bare.tools, bare.materials, bare.spools, bare.materialTool, bare.intents, bare.objects)
        val e2 = shouldThrow<IllegalStateException> { Resolver.resolve(noKeys, "hardware.machine.model") }.message!!
        e2 shouldContain "machine setting 'hardware.machine.model'"
        (e2.contains("<key>")) shouldBe false
    }
    "every value a formula needs and nothing sets is named at once, with the key to set; control: the same filament like pla-generic builds" {
        val bare = arrayOf<Pair<String, Any>>("hardware.machine.m.loadout.tool.t1.filament" to "bare", "hardware.filament.bare.kind" to "PLA")
        val e = shouldThrow<ConfigError> { setup(*bare) }
        val named = e.problems.filter { "names no value" in it }
        withClue(e.problems.joinToString("\n")) {
            (named.size > 1) shouldBe true
            for (k in listOf("cooling.fan", "cooling.layer.time.min", "temperature.print"))
                named.any { it.endsWith("(set hardware.filament.bare.$k)") } shouldBe true
        }
        setup(*bare, "hardware.filament.bare.like" to "pla-generic")   // control
    }
    "every setting the slice needs and nothing sets is named at once, by the key to set; control: the same filament like pla-generic reads" {
        val few = arrayOf<Pair<String, Any>>("hardware.machine.m.loadout.tool.t1.filament" to "few", "hardware.filament.few.kind" to "PLA",
            "hardware.filament.few.temperature.print" to "210 C", "hardware.filament.few.cooling.fan" to "100%", "hardware.filament.few.cooling.layer.time.min" to "6 s")
        val s = setup(*few).setup
        val missing = SliceSettings.missing(s, Context(0, 0))
        withClue(missing.joinToString("\n")) {
            (missing.size > 1) shouldBe true
            for (k in listOf("diameter", "flow", "retract.length", "melt.flow.max"))
                missing.any { it.startsWith("hardware.filament.few.$k has no value") } shouldBe true
        }
        s.used.isEmpty() shouldBe true   // the check reads every setting without counting it as used
        SliceSettings.missing(setup(*few, "hardware.filament.few.like" to "pla-generic").setup, Context(0, 0)).isEmpty() shouldBe true   // control
    }
    "a setting counts as used when the print asks for it, not when the settings are made; readAll reads every one" {
        val s = setup().setup
        val settings = SliceSettings.of(s, Context(0))
        s.used.isEmpty() shouldBe true
        settings.ironingSpeed   // asked for: its line counts
        s.used.any { "ironing.speed" in it } shouldBe true
        s.used.any { "fuzzy_skin" in it } shouldBe false   // control: not asked for, not counted
        // readAll leaves no field unread (a field added later and left out of it would escape the missing-value check)
        val all = SliceSettings.of(s, Context(0)).also { it.readAll() }
        val fields = SliceSettings::class.java.declaredFields.filter { SliceSettings.Setting::class.java.isAssignableFrom(it.type) }
        (fields.size > 200) shouldBe true
        val unread = fields.filter { f -> f.isAccessible = true; !(f.get(all) as SliceSettings.Setting<*>).isRead }.map { it.name }
        unread shouldBe emptyList()
    }
    "a switch can be a formula, worked out where it is read: per part from its own filament; a brim from the machine; controls: the opposite formula" {
        // benchy's hull prints in white (PLA), its deck in petg-generic
        val s = setup("plan.mechanism.motion.avoid_crossing" to "= filament.kind == 'PETG'").setup
        Resolver.resolve(s, "plan.mechanism.motion.avoid_crossing", Context(0, 0)).value shouldBe false
        Resolver.resolve(s, "plan.mechanism.motion.avoid_crossing", Context(0, 1)).value shouldBe true
        fun brim(f: String) = Resolver.resolve(setup("plan.mechanism.adhesion.brim.enabled" to f, "plan.mechanism.adhesion.brim.where" to "outer").setup,
            "plan.mechanism.adhesion.brim.where", Context(0)).value
        brim("= machine.firmware.exclude_object") shouldBe "outer_only"
        brim("= not machine.firmware.exclude_object") shouldBe "no_brim"
    }
    "a part's filament number that is not loaded is an error naming it" {
        shouldThrow<ConfigError> { setup(models = listOf(ModelObject("x", listOf("p"), listOf(3), "x.stl"))) }.message!! shouldContain "uses filament 3"
    }
    "the slot map (model.slots) prints the model's filaments from the loaded slots: by number, \"*\" for every one, an object's own first, a part's own slot over both" {
        // a four-color model on a machine with slots 1 and 2 loaded
        val four = ModelObject("x", listOf("a", "b", "c", "d"), listOf(1, 2, 3, 4), "x.3mf")
        fun spools(vararg extra: Pair<String, Any>) = setup(*extra, models = listOf(four)).setup.objects.single().parts.map { it.spool }
        // control: as the model says, refused, and the refusal names the map and the option
        shouldThrow<ConfigError> { spools() }.message!!.let { it shouldContain "uses filament 3"; it shouldContain "model.slots"; it shouldContain "--slot 1" }
        // every number to slot 1
        spools("model.slots.*" to 1L) shouldBe List(4) { "spool 1" }
        // by number; a number not in the map prints from the model's own (2 is loaded)
        spools("model.slots.3" to 1L, "model.slots.4" to 2L) shouldBe listOf("spool 1", "spool 2", "spool 1", "spool 2")
        // a number's own entry over "*"
        spools("model.slots.*" to 1L, "model.slots.4" to 2L) shouldBe listOf("spool 1", "spool 1", "spool 1", "spool 2")
        // the object's own map over the model's, number by number and "*" alike
        spools("model.slots.*" to 1L, "model.object.x.slots.*" to 2L) shouldBe List(4) { "spool 2" }
        spools("model.slots.3" to 1L, "model.slots.4" to 1L, "model.object.x.slots.4" to 2L) shouldBe listOf("spool 1", "spool 2", "spool 1", "spool 2")
        // a part's own slot over every map
        spools("model.slots.*" to 1L, "model.object.x.part.d.slot" to 2L) shouldBe listOf("spool 1", "spool 1", "spool 1", "spool 2")
        // a map to a slot that is not loaded is refused like the model's own number
        shouldThrow<ConfigError> { spools("model.slots.*" to 5L) }.message!! shouldContain "uses filament 5"
        // the pre-print check's tools read the same map
        val c = Config(ConfigStack(ConfigFiles.bundled() + (machine + listOf("model.slots.3" to 1L, "model.slots.4" to 2L)).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(four))
        LoadoutTools.byObject(c).getValue("x") shouldBe listOf(0, 1, 0, 1)
    }
    "pauses as asked" {
        val p = setup("plan.pause.ballast.at" to "12.4 mm").pauses.single()
        p.kind shouldBe "at"; (p.value as Double) shouldBe (12.4 plusOrMinus 1e-9)
    }
    "variable-width walls' thresholds from each tool's narrowest line; a tool without one: its bore, as a stand-in" {
        val r = setup("hardware.tool.t1.line.width.min" to "= tool.bore * 0.9", "hardware.tool.t2.line.width.min" to "0.5 mm")
        fun v(id: String, part: Int) = (Resolver.resolve(r.setup, id, Context(0, part)).value as Double)
        v("plan.layer.walls.line.width.min", 0) shouldBe (0.36 plusOrMinus 1e-9)           // the base on t1: 0.4 x 0.9
        v("plan.layer.walls.feature.min", 0) shouldBe (0.18 plusOrMinus 1e-9)         // half the narrowest line
        v("plan.layer.walls.transition_length", 0) shouldBe (0.36 plusOrMinus 1e-9)
        v("plan.layer.walls.length_factor.min", 0) shouldBe (2.0 plusOrMinus 1e-9)
        v("plan.layer.walls.line.width.min", 1) shouldBe (0.5 plusOrMinus 1e-9)            // the deck on t2
        // no narrowest line on the tool: three quarters of its bore (t1, 0.4 mm: 0.3), said to be a stand-in
        val sd = Resolver.resolve(setup().setup, "plan.layer.walls.line.width.min", Context(0, 0))
        (sd.value as Double) shouldBe (0.3 plusOrMinus 1e-9)
        sd.from shouldContain "stand-in"
    }
    "the first-layer speed: the filament's own, else the machine's, else plymeld's stand-in; a plan value in a config locks it over all" {
        // the Benchy's hull prints white (like pla-generic) on t1, its deck petg-generic on t2
        fun speeds(vararg extra: Pair<String, Any>): Pair<Double, Double> {
            val r = setup(*extra)
            fun v(part: Int) = Resolver.resolve(r.setup, "plan.mechanism.motion.first_layer.speed", Context(0, part)).value as Double
            return v(0) to v(1)
        }
        // white (like pla-generic) takes the bundled pla-generic's 80 mm/s; petg-generic sets none: the bundled stand-in
        speeds() shouldBe (80.0 to 40.0)
        speeds("hardware.machine.m.motion.first_layer.speed" to "50 mm/s") shouldBe (80.0 to 50.0) // the machine's default where the filament sets none
        // a filament's own wins over the machine's; one like an archetype takes the archetype's over; the other keeps the machine's
        speeds("hardware.machine.m.motion.first_layer.speed" to "50 mm/s", "hardware.filament.pla-generic.motion.first_layer.speed" to "30 mm/s") shouldBe (30.0 to 50.0)
        // two filaments, two speeds; white's own over its archetype's; with no machine value the other has the stand-in
        val two = arrayOf<Pair<String, Any>>("hardware.filament.pla-generic.motion.first_layer.speed" to "30 mm/s", "hardware.filament.white.motion.first_layer.speed" to "80 mm/s")
        speeds(*two) shouldBe (80.0 to 40.0)
        // a plan value in a config locks it over the filament's and the machine's, and per object
        speeds(*two, "hardware.machine.m.motion.first_layer.speed" to "50 mm/s", "plan.mechanism.motion.first_layer.speed" to "25 mm/s") shouldBe (25.0 to 25.0)
        speeds(*two, "plan.object.benchy.mechanism.motion.first_layer.speed" to "35 mm/s") shouldBe (35.0 to 35.0)
        // control: the old resolution (the plan's one value, read at each part's site, as the schema's path once fed it) cannot tell
        // the two filaments apart, so the two-filament check above fails for it
        val c = Config(ConfigStack(ConfigFiles.bundled() + (machine + two).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(benchy))
        val decl = Schema.find("plan.mechanism.motion.first_layer.speed".split('.'))!!
        fun old(tool: String, filament: String): Double {
            val site = Site(machine = "m", plate = "pei", tool = tool, filament = filament, obj = c.objects.single())
            return c.quantity(ConfigSetup.entryAt(c, decl, site)!!, Dimension.SPEED, site)
        }
        (old("t1", "white") to old("t2", "petg-generic")) shouldBe (40.0 to 40.0)
    }
    "the melt rate: the filament's own, over it its machine's, over that its tool's; the nearest filament in the like chain first" {
        // two machines: n is like m (the loadout aside), its tool n1 like t1; white (205 C, like pla-generic) on tool 1 of each
        val two = machine + listOf("hardware.machine.n.like" to "m", "hardware.machine.n.loadout.plate" to "pei", "hardware.machine.n.loadout.tool.n1.filament" to "white",
            "hardware.tool.n1.like" to "t1", "hardware.tool.n1.machine" to "n")
        val cube = ModelObject("cube", listOf("body"), listOf(1), "cube.stl")
        fun config(on: String, vararg extra: Pair<String, Any>) =
            Config(ConfigStack(ConfigFiles.bundled() + (two + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(cube), on)
        fun value(on: String, id: String, vararg extra: Pair<String, Any>) = Resolver.resolve(ConfigSetup.setup(config(on, *extra)).setup, id, Context(0)).value as Double
        fun cap(on: String, vararg extra: Pair<String, Any>) = value(on, "hardware.filament.melt.flow.max", *extra)
        // a curve measured on n (a white PLA+ curve, as a test value): 22 mm3/s at 210 C, 0.4 per degree, so 20 at white's 205 C
        val curve = "hardware.filament.white.machine.n.melt.flow.max" to "= min(22 mm3/s + (filament.temperature.print - 210 C) * 0.4 mm3/s / 1 C, 26 mm3/s)"
        cap("n", curve) shouldBe (20.0 plusOrMinus 1e-9)
        cap("m", curve) shouldBe (15.0 plusOrMinus 1e-9)                    // on m its own: pla-generic's bundled 15 mm3/s
        // a tool's wins over the machine's and the filament's own; one for another tool does not apply
        val t1 = "hardware.filament.white.tool.t1.melt.flow.max" to "18 mm3/s"
        cap("m", curve, t1) shouldBe (18.0 plusOrMinus 1e-9)
        cap("n", curve, t1) shouldBe (20.0 plusOrMinus 1e-9)
        cap("n", curve, "hardware.filament.white.tool.n1.melt.flow.max" to "19 mm3/s") shouldBe (19.0 plusOrMinus 1e-9)
        cap("m", "hardware.filament.white.machine.m.melt.flow.max" to "16 mm3/s", t1) shouldBe (18.0 plusOrMinus 1e-9)
        // like: a base's value for the machine is taken over where the filament sets none; the filament's own wins over it
        val base = "hardware.filament.pla-generic.machine.n.melt.flow.max" to "30 mm3/s"
        cap("n", base) shouldBe (30.0 plusOrMinus 1e-9)
        cap("m", base) shouldBe (15.0 plusOrMinus 1e-9)
        cap("n", base, "hardware.filament.white.melt.flow.max" to "12 mm3/s") shouldBe (12.0 plusOrMinus 1e-9)
        // a formula's filament.melt.flow.max is the one for the machine and tool in play: 20 / (0.5 x 0.2) on n, 15 / 0.1 on m
        val visible = "plan.mechanism.motion.visible.speed" to "= filament.melt.flow.max / (0.5 mm * 0.2 mm)"
        value("n", "plan.mechanism.motion.visible.speed", curve, visible) shouldBe (200.0 plusOrMinus 1e-9)
        value("m", "plan.mechanism.motion.visible.speed", curve, visible) shouldBe (150.0 plusOrMinus 1e-9)
        // a machine or tool that does not exist is an error, not ignored
        shouldThrow<ConfigError> { config("n", "hardware.filament.white.machine.nn.melt.flow.max" to "20 mm3/s") }.message!! shouldContain "there is no machine 'nn'"
        shouldThrow<ConfigError> { config("n", "hardware.filament.white.tool.n9.melt.flow.max" to "20 mm3/s") }.message!! shouldContain "there is no tool 'n9'"
        // control: the filament-only lookup (hardware.filament.<key>.melt.flow.max, as before) gives 15 on n too, so the first check fails for it
        val c = config("n", curve)
        val site = Site(machine = "n", plate = "pei", tool = "n1", filament = "white")
        c.quantity(c.entry("hardware.filament.white.melt.flow.max".split('.'))!!, Dimension.FLOW, site) shouldBe (15.0 plusOrMinus 1e-9)
    }
    "the visible lines' motion: the machine's measured value (its motion plate) over plymeld's stand-in; a job value of yours wins over it; another machine's does not apply" {
        // two machines, n like m; the values on n only
        val two = machine + listOf("hardware.machine.n.like" to "m", "hardware.machine.n.loadout.plate" to "pei", "hardware.machine.n.loadout.tool.n1.filament" to "white",
            "hardware.tool.n1.like" to "t1", "hardware.tool.n1.machine" to "n")
        val cube = ModelObject("cube", listOf("body"), listOf(1), "cube.stl")
        fun vis(on: String, vararg extra: Pair<String, Any>): Pair<Double, Double> {
            val c = Config(ConfigStack(ConfigFiles.bundled() + (two + extra).map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), listOf(cube), on)
            val s = SliceSettings.of(ConfigSetup.setup(c).setup)
            return s.visibleSpeed to s.visibleAccel
        }
        val measured = arrayOf<Pair<String, Any>>("hardware.machine.n.motion.visible.speed" to "135 mm/s", "hardware.machine.n.motion.visible.acceleration" to "3750 mm/s2")
        // plymeld's stand-ins (00_defaults.toml): 100 mm/s, the machine's print acceleration (10000) halved
        vis("m") shouldBe (100.0 to 5000.0)
        vis("n", *measured) shouldBe (135.0 to 3750.0)
        // control: machine m sets none, so n's values do not reach it
        vis("m", *measured) shouldBe (100.0 to 5000.0)
        // a job value of yours wins over the machine's (the acceleration, not set in the job, stays the machine's)
        vis("n", *measured, "plan.mechanism.motion.visible.speed" to "90 mm/s") shouldBe (90.0 to 3750.0)
    }
    "a declared value nothing reads yet earns a warning; control: a used one does not" {
        setup("hardware.machine.m.tool_change.kind" to "swap_tool").notes.any { "not used yet" in it } shouldBe true
        // what formulas read is used: the first-use setup's accelerations are formulas of it
        setup("hardware.machine.m.motion.acceleration.max" to "20000 mm/s2").notes.any { "not used yet" in it } shouldBe false
        setup("hardware.machine.m.motion.travel.speed" to "500 mm/s").notes.any { "not used yet" in it } shouldBe false
    }
})
