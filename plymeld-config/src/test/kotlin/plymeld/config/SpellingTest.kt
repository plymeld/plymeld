package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** A chosen value spelled the British way is read as the American one the setting takes. */
class SpellingTest : StringSpec({
    fun stack(vararg kv: Pair<String, Any>) = ConfigStack(kv.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") })
    val base = arrayOf<Pair<String, Any>>("hardware.machine.m.build.z" to "250 mm")

    "'centre' is read as 'center' where a setting takes 'center'; control: a value that is neither is still refused" {
        stack("plan.placement" to "centre").entry(listOf("plan", "placement"))!!.value shouldBe "center"
        stack("hardware.machine.m.build.origin" to "centre").entry("hardware.machine.m.build.origin".split('.'))!!.value shouldBe "center"
        Config(stack(*base, "plan.placement" to "centre"), emptyList()).warnings shouldBe emptyList()
        shouldThrow<ConfigError> { Config(stack(*base, "plan.placement" to "middle"), emptyList()) }.message!! shouldContain "middle"
    }
    "a setting's name spelled the British way names the setting; control: a key of the user's own is left as written" {
        val s = stack("hardware.filament.grey.colour" to "#808080")
        s.entry("hardware.filament.grey.color".split('.'))!!.value shouldBe "#808080"
        s.entry("hardware.filament.gray.color".split('.')) shouldBe null
        stack("hardware.filament.grey.colours" to "x").all.single().path.last() shouldBe "colours"
    }
    "only a value the place takes: a British word that would not be an option is left as written" {
        Spelling.american("centre", listOf("center", "as-given")) shouldBe "center"
        Spelling.american("as-given", listOf("center", "as-given")) shouldBe "as-given"
        Spelling.american("colour", listOf("center")) shouldBe null
        stack("plan.pose" to "centre").entry(listOf("plan", "pose"))!!.value shouldBe "centre"
    }
})
