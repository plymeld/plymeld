package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.machine.Dialect
import plymeld.machine.Toolhead

class ModelsTest : StringSpec({
    val models = Models.bundled

    "the three shipped models load, each command with evidence" {
        models.all.map { it.id }.toSet() shouldBe setOf("generic-klipper", "qidi-q2", "flashforge-c5")
        for (m in models.all) for (c in m.commands) (c.evidence.isNotEmpty()) shouldBe true
    }

    "generic Klipper: Klipper's documented commands only, nothing a maker adds" {
        val k = models.forModel("generic-klipper")
        k.dialect shouldBe "klipper"
        k.commands.flatMap { it.evidence }.map { it.kind }.toSet() shouldBe setOf("documented")
        k.command("G2")!!.evidence.single().detail shouldContain "[gcode_arcs]"
        k.supports("EXCLUDE_OBJECT_START") shouldBe true
        // control: a maker's macro and a tool change are not Klipper's own
        k.supports("PRINT_START") shouldBe false
        k.supports("T1") shouldBe false
        k.toolheads.size shouldBe 0
    }

    "Q2: arcs come from the firmware config, Box macros only from QIDIStudio's output" {
        val q2 = models.forModel("qidi-q2")
        q2.command("G2")!!.evidence.first().kind shouldBe "firmware_config"
        q2.command("G2")!!.evidence.first().detail shouldContain "[gcode_arcs]"
        q2.command("unload_filament")!!.evidence.map { it.kind }.toSet() shouldBe setOf("observed")   // case-insensitive lookup
        q2.fan("auxiliary_part_cooling")!!.command shouldBe "M106 P2 S{s255}"
        q2.heaters.first { it.name == "chamber" }.maxTempC shouldBe 67.0
    }

    "Creator 5: observed commands only; no exclude-object evidence" {
        val c5 = models.forModel("flashforge-c5")
        c5.supports("T3") shouldBe true
        c5.supports("EXCLUDE_OBJECT_START") shouldBe false
        c5.commands.flatMap { it.evidence }.map { it.kind }.toSet() shouldBe setOf("observed")
    }

    "toolhead geometry lives with each toolhead; tiers known only where the maker's profile gives them" {
        val q2 = models.forModel("qidi-q2").toolheads
        q2.size shouldBe 1
        q2[0]["head.radius"] shouldBe 75.0
        q2[0].has(Toolhead.Tier.GANTRY) shouldBe true
        q2[0].has(Toolhead.Tier.TIP_CONE) shouldBe false
        q2[0].evidence.single().kind shouldBe "manufacturer_profile"
        val c5 = models.forModel("flashforge-c5")
        c5.toolheads.map { it.index } shouldBe listOf(0, 1, 2, 3)
        c5.toolhead(3)!!["gantry.height"] shouldBe 60.0
    }

    "control: an unknown model is refused, not guessed" {
        shouldThrow<IllegalStateException> { models.forModel("Some Other Printer") }.message shouldContain "no printer model"
    }

    "a model is found by its key or by the name the maker's profile gives it, the same description either way" {
        models.forModel("flashforge-c5") shouldBe models.forModel("Flashforge Creator 5")
        models.forModel("qidi-q2") shouldBe models.forModel("Q2")
        models.all.map { it.id }.toSet().size shouldBe models.all.size
    }

    /** The models of the bundled config plus [kv] (path to value, as a config file of yours would set them). */
    fun withMine(vararg kv: Pair<String, Any>) =
        Models.library(Config(ConfigStack(ConfigFiles.bundled() + kv.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), emptyList()))

    "a bundled model's value set in your config wins, with your config as its evidence; control: the bundled value" {
        models.forModel("qidi-q2").motion.getValue("max_accel").value shouldBe 20000.0
        val mine = withMine("hardware.model.qidi-q2.motion.acceleration.max" to "15000 mm/s2").forModel("qidi-q2").motion.getValue("max_accel")
        mine.value shouldBe 15000.0
        mine.evidence.kind shouldBe "config"                      // not the bundled value's firmware_config
        mine.evidence.detail shouldContain "test"
        // control: set together with its own evidence, that evidence counts
        withMine("hardware.model.qidi-q2.motion.acceleration.max" to "15000 mm/s2", "hardware.model.qidi-q2.evidence.motion.acceleration.max" to "measured: my ringing plate")
            .forModel("qidi-q2").motion.getValue("max_accel").evidence.kind shouldBe "measured"
    }

    "a model of your own takes over its base's facts through like, and adds its own" {
        val mine = withMine("hardware.model.my-q2.like" to "qidi-q2", "hardware.model.my-q2.name" to "My Q2",
            "hardware.model.my-q2.command.MY_PURGE" to "tested: printed on my machine")
        val m = mine.forModel("my-q2")
        m.name shouldBe "My Q2"
        m.supports("MY_PURGE") shouldBe true
        m.command("MY_PURGE")!!.evidence.single().kind shouldBe "tested"
        m.supports("G2") shouldBe true                                   // the base's, through like
        m.dialect shouldBe "qidi"
        m.toolheads shouldBe models.forModel("qidi-q2").toolheads
        mine.forModel("qidi-q2").supports("MY_PURGE") shouldBe false     // control: the base is not changed
    }

    "a toolhead's underside is its size and the nozzle's place in it, kept as the head's reach each way; both or neither" {
        fun head(vararg kv: Pair<String, String>) = withMine(*kv.map { (k, v) -> "hardware.model.qidi-q2.toolhead.0.$k" to v }.toTypedArray())
            .forModel("qidi-q2").toolheads.single()
        val t = head("head.size.x" to "60 mm", "head.nozzle.x" to "20 mm", "head.size.y" to "50 mm", "head.nozzle.y" to "30 mm")
        t["head_minus_x"] shouldBe 20.0
        t["head_plus_x"] shouldBe 40.0
        t["head_minus_y"] shouldBe 30.0
        t["head_plus_y"] shouldBe 20.0
        t.has(Toolhead.Tier.UNDERSIDE) shouldBe true
        t.evidence.map { it.kind } shouldBe listOf("manufacturer_profile", "config", "config", "config", "config")   // the bundled clearance, your four values
        models.forModel("qidi-q2").toolheads.single().has(Toolhead.Tier.UNDERSIDE) shouldBe false   // control: none bundled
        shouldThrow<IllegalStateException> { head("head.size.x" to "60 mm") }.message shouldContain "go together"
        shouldThrow<IllegalStateException> { head("head.size.x" to "60 mm", "head.nozzle.x" to "70 mm") }.message shouldContain "outside the head"
    }

    "an imported profile's printer_model picks the shipped model by its profile_name; control: a name no model goes by is kept as it is" {
        OrcaImport.modelFor("Q2") shouldBe "qidi-q2"
        OrcaImport.modelFor("Flashforge Creator 5") shouldBe "flashforge-c5"
        OrcaImport.modelFor("qidi-q2") shouldBe "qidi-q2"
        OrcaImport.modelFor("Some Other Printer") shouldBe "Some Other Printer"
    }

    "the dialect: the model's default unless the machine overrides it; an unknown one is refused" {
        val c5 = models.forModel("flashforge-c5")
        val q2 = models.forModel("qidi-q2")
        Dialect.forMachine(c5, null) shouldBe Dialect.FLASHFORGE
        Dialect.forMachine(q2, null) shouldBe Dialect.QIDI
        Dialect.forMachine(q2, "") shouldBe Dialect.QIDI                // empty = unset
        Dialect.forMachine(q2, "flashforge") shouldBe Dialect.FLASHFORGE  // the override wins over the model's
        Dialect.forMachine(c5, "qidi") shouldBe Dialect.QIDI
        shouldThrow<IllegalStateException> { Dialect.forMachine(q2, "prusa") }.message shouldContain "no g-code dialect 'prusa'"
    }
})
