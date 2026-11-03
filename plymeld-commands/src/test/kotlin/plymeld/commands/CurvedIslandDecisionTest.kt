package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/**
 * The curved islands decision (IslandDecision): the pin of
 * [IslandPin] planned with no island locked. Its cap's island removes most of its stair-step (the cap is most of its sloped
 * surface) and, its own layers thicker than the flat 0.2 mm, saves time: the default weights take it, and the plan file
 * written back locks the same island. Controls: a job weighing the island's border heavily keeps flat layers (the one cost it
 * has here); job.faces.sloped_top.look = concentric_steps takes no island, lines_follow_curve takes it whatever the weights.
 */
class CurvedIslandDecisionTest : StringSpec({
    val d: Path = Files.createTempDirectory("plymeld-island-decision").also { d ->
        Files.writeString(d.resolve("hw.toml"), IslandPin.hardware); Files.writeString(d.resolve("pin.stl"), IslandPin.stl())
        Files.writeString(d.resolve("border.toml"), "[job.priorities]\nquality = \"1\"\nspeed = \"1\"\nmeasure.curved_border = \"1000\"\n")
        Files.writeString(d.resolve("steps.toml"), "[job]\nfaces.sloped_top.look = \"concentric_steps\"\n")
        Files.writeString(d.resolve("curve.toml"), "[job]\nfaces.sloped_top.look = \"lines_follow_curve\"\n")
    }
    fun product(vararg cfs: String) = Product(ConfigStack.load(cfs.toList(), emptyList(), d), listOf(d.resolve("pin.stl")), null) { }
    fun decided(vararg cfs: String): Pair<List<plymeld.core.decide.CurvedIsland>, String> {
        val plan = product(*cfs).decide()
        val why = plan.decisions.first { it.step.startsWith("curved islands") }
        val text = "${why.step}: ${why.why}\n  " + why.alternatives.joinToString("\n  ") { "${it.label}: ${it.score}" }
        println(text)
        return plan.layout.techniques.single().islands to text
    }

    "the default weights take the cap's island; the plan file locks the same island" {
        val pr = product("hw.toml")
        val plan = pr.decide()
        val isl = plan.layout.techniques.single().islands
        val why = plan.decisions.first { it.step.startsWith("curved islands") }
        println("${why.step}: ${why.why}")
        isl.size shouldBe 1
        why.why shouldContain "weighed"
        val file = pr.planFile(plan)
        file shouldContain "island.${isl.single().key}.x"
        Files.writeString(d.resolve("plan.toml"), file)
        val back = product("hw.toml", "plan.toml").decide().layout.techniques.single().islands.single()
        back.startMm shouldBe (isl.single().startMm plusOrMinus 1e-6)     // the plan file's 6 decimals
        back.cells.toList() shouldBe isl.single().cells.toList()
    }
    "control: a job weighing the island's border heavily keeps flat layers" {
        decided("hw.toml", "border.toml").first.size shouldBe 0
    }
    "job.faces.sloped_top.look: concentric_steps takes no island; lines_follow_curve takes it even against a heavy border weight" {
        decided("hw.toml", "steps.toml").first.size shouldBe 0
        decided("hw.toml", "border.toml", "curve.toml").first.size shouldBe 1
    }
    // a plan file "prints as planned and decides nothing again" (its header): with the technique locked, its islands are the
    // ones it locks or none (a large model's plan-file slice re-ran the island search, ~380 s single-threaded)
    "with a plan file locking the technique and no island, the islands are not searched again; without it they are (the control)" {
        val pr = product("hw.toml", "border.toml")
        val plan = pr.decide()
        plan.decisions.any { it.step.startsWith("curved islands") } shouldBe true          // the plan's own search (the control)
        plan.layout.techniques.single().islands.size shouldBe 0
        Files.writeString(d.resolve("flat_plan.toml"), pr.planFile(plan))
        // the plan file read back WITHOUT the heavy border weight, which on its own would take the island (the first test):
        // nothing decided again, so still none
        val back = product("hw.toml", "flat_plan.toml").decide()
        back.decisions.any { it.step.startsWith("curved islands") } shouldBe false
        back.layout.techniques.single().islands.size shouldBe 0
    }
})
