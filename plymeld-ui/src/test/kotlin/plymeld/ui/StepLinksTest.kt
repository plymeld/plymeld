package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import plymeld.config.Config
import plymeld.config.ConfigStack
import plymeld.config.ModelObject
import plymeld.config.Schema
import java.nio.file.Files

/**
 * The steps' links that open settings or a choice: what "Turn or move", "Change what is
 * loaded" and "More choices" open in the drawer are paths the schema declares, real ones (no `<key>`, so each can be
 * set); "Another printer" offers the machines the config files define; and a value whose line in force is the plan
 * file's is written into the plan file, where it is locked, not into the user's file the plan file would hide it under.
 */
class StepLinksTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-steplinks")
    val cfg = dir.resolve("mine.toml")
    Files.writeString(cfg, """
        [job]
        machine = "m1"
        [hardware.machine.m1]
        model = "flashforge-c5"
        [hardware.machine.m2]
        model = "qidi-q2"
        [hardware.tool.t1]
        machine = "m1"
        index = "1"
        [hardware.tool.t2]
        machine = "m1"
        index = "2"
        [hardware.tool.t3]
        machine = "m2"
        [hardware.machine.m1.loadout]
        tool.t1.filament = "pla-generic"
    """.trimIndent())
    val stack = ConfigStack.load(listOf(cfg.toString()), emptyList())
    val c = Config(stack, listOf(ModelObject("box", listOf("a"), listOf(1), "test")))
    fun settable(paths: List<String>) = paths.filter { '<' in it || Schema.find(it.split('.')) == null }

    "the machines the config files define, in their order (the tools under them are not machines)" {
        JobState.machinesOf(stack) shouldBe listOf("m1", "m2")
    }

    "Change what is loaded: the plate and every tool of the machine that prints, no other machine's" {
        val g = stepSettings("loadout", c, listOf("box"))!!
        g.paths shouldBe listOf("hardware.machine.m1.loadout.plate", "hardware.machine.m1.loadout.tool.t1.filament", "hardware.machine.m1.loadout.tool.t2.filament")
        g.paths shouldNotContain "hardware.machine.m1.loadout.tool.t3.filament"
        settable(g.paths).shouldBeEmpty()
    }

    "Turn or move: the plate's pose and placement and each object's turn" {
        val g = stepSettings("pose", c, listOf("box"))!!
        g.paths shouldContainAll listOf("plan.pose", "plan.placement", "plan.object.box.pose.turn")
        settable(g.paths).shouldBeEmpty()
    }

    "More choices: every goal's weight" {
        val g = stepSettings("priorities", c, listOf("box"))!!
        g.paths shouldContainAll Schema.GOALS.map { "job.priorities.$it" }
        settable(g.paths).shouldBeEmpty()
    }

    "control: a path the schema does not declare, and a family's path, are caught; an unknown group is none" {
        settable(listOf("plan.object.box.pose.nothing_of_the_kind", "plan.object.<key>.pose.turn")).size shouldBe 2
        stepSettings("nothing", c, emptyList()) shouldBe null
    }

    "a value in force from the plan file is written there; any other into the user's file" {
        val plan = dir.resolve("job.plan.toml"); val mine = dir.resolve("mine.toml")
        JobState.writeTarget("$plan:8", plan, mine) shouldBe plan
        JobState.writeTarget("$mine:3", plan, mine) shouldBe mine
        JobState.writeTarget(null, plan, mine) shouldBe mine
        JobState.writeTarget("$plan:8", null, mine) shouldBe mine
        // control: a file of the same name in another directory is not the plan file
        JobState.writeTarget("${dir.resolve("other").resolve("job.plan.toml")}:8", plan, mine) shouldNotBe plan
        JobState.writeTarget("bundled 00_defaults.toml:4", plan, null) shouldBe null
    }

    "the priorities group lists the measures the config weighs (the bundled config weighs layer_lines)" {
        val inForce = c.values.keys.filter { it.startsWith("job.priorities.") }
        stepSettings("priorities", c, emptyList())!!.paths shouldContainAll inForce
        inForce shouldContain "job.priorities.measure.layer_lines"
    }
})
