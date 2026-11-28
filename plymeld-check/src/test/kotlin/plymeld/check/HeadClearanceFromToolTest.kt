package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.Models
import plymeld.core.decide.Plan
import plymeld.machine.Toolhead

/**
 * One object at a time reads the head's clearances (the gantry's rod and the lid above the nozzle, the head as a circle)
 * from the tool's config (Plan.headFromConfig): a tool that sets none takes its machine model's toolhead's (the bundled QIDI
 * Q2's: 47 mm, 152 mm, 75 mm, as the machine description holds them); a tool's own wins. Control: a model whose toolhead
 * gives no lid height (a model of the user's like the generic one), the tool none: the gantry tier is not known.
 */
class HeadClearanceFromToolTest : StringSpec({
    fun head(vararg extra: Pair<String, Any>): Toolhead? {
        val setup = testSetup(*extra)
        val m = Plan.headFromConfig(Models.bundled.forModel((extra.toMap()["hardware.machine.tm.model"] as String?) ?: "qidi-q2"), 0, setup, 0)
        return m.toolhead(0)
    }

    "a tool that sets no clearances takes its toolhead's; its own wins; control: none known, no gantry tier" {
        val q2 = Models.bundled.forModel("qidi-q2").toolhead(0)!!
        val model = head()!!
        model["gantry.height"] shouldBe q2["gantry.height"]
        model["lid.height"] shouldBe q2["lid.height"]
        model["head.radius"] shouldBe q2["head.radius"]
        val own = head("hardware.tool.t1.gantry.height" to "30 mm", "hardware.tool.t1.lid.height" to "100 mm", "hardware.tool.t1.head.radius" to "40 mm")!!
        listOf(own["gantry.height"], own["lid.height"], own["head.radius"]) shouldBe listOf(30.0, 100.0, 40.0)
        // control: the generic model's description has no toolhead, and the tool gives only the gantry's height
        val none = head("hardware.machine.tm.model" to "generic-klipper", "hardware.tool.t1.gantry.height" to "30 mm")
        (none?.has(Toolhead.Tier.GANTRY) ?: false) shouldBe false
    }
})
