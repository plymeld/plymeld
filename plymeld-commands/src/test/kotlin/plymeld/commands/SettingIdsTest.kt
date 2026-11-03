package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.testing.test
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.model.Entity
import plymeld.model.Setup
import plymeld.model.Values

/**
 * Where a setting id can be typed: `plymeld investigate config <id or prefix>` and `--set id=value` on the investigate
 * commands. An id plymeld does not have is an ordinary unknown id (old ids are not refused by name: there are no
 * earlier users to migrate).
 */
class SettingIdsTest : StringSpec({
    "investigate config: an id or a prefix lists its settings; control: an unknown id is reported as unknown" {
        val now = InvestigateConfigCommand().test(listOf("plan.layer.walls.count"))
        now.statusCode shouldBe 0
        now.stdout shouldContain "plan.layer.walls.count"
        val under = InvestigateConfigCommand().test(listOf("hardware.filament.retract."))
        under.statusCode shouldBe 0
        under.stdout shouldContain "hardware.filament.retract.return.speed"
        under.stdout shouldNotContain "plan.layer"
        val old = InvestigateConfigCommand().test(listOf("walls.count"))
        old.statusCode shouldBe 1
        old.stderr shouldContain "no setting matches 'walls.count'"
    }

    "--set: a machine setting is refused as one no object overrides, an unknown id as unknown; control: an intent id is set" {
        fun setup() = Setup(Values(Entity.MACHINE, "m"), emptyList(), emptyMap(), emptyList(), emptyMap(), mapOf("i" to Values(Entity.INTENT, "i")), emptyList())
        shouldThrow<UsageError> { applySetOverrides(setup(), listOf("hardware.machine.build.z=100")) }.message!! shouldContain "a machine setting"
        shouldThrow<UsageError> { applySetOverrides(setup(), listOf("walls.count=3")) }.message!! shouldContain "no setting 'walls.count'"
        applySetOverrides(setup(), listOf("plan.layer.walls.count=3")).intents.getValue("i")["plan.layer.walls.count"]!!.value shouldBe 3.0
    }
})
