package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Where a value may be set: a value under a scope the code never reads is refused by name, not
 * ignored. A pause is the plate's (an object's pause was accepted and ignored before); the weights are the plate's or
 * an object's, never a part's; a flat-layer value may be set on a part. Declarations written at the object level
 * (plan.object.<key>.pose.turn) are accepted as written.
 */
class ScopesTest : StringSpec({
    fun cfg(vararg kv: Pair<String, Any>, models: List<ModelObject> = emptyList()): Config =
        Config(ConfigStack(kv.map { (k, v) -> ConfigFiles.Entry(k.split('.'), v, "test") }), models)
    val benchy = ModelObject("dual benchy", listOf("hull", "deck"), listOf(1, 2), "benchy.3mf")
    val base = arrayOf<Pair<String, Any>>(
        "hardware.machine.m.build.z" to "250 mm",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.filament.pla.temperature.print" to "210 C",
        "hardware.machine.m.loadout.tool.t1.filament" to "pla")

    "an object's pause is refused by name (the pauses are the plate's); control: the plate's pause is accepted" {
        val e = shouldThrow<ConfigError> { cfg(*base, "plan.object.dual-benchy.pause.lid.at" to "4 mm", models = listOf(benchy)) }
        e.message!! shouldContain "plan.pause.<key>.at cannot be set for an object; it applies to the plate"
        (cfg(*base, "plan.pause.lid.at" to "4 mm", models = listOf(benchy)).entry(listOf("plan", "pause", "lid", "at")) != null) shouldBe true
    }
    "a part's priorities are refused (the weights are the plate's or an object's); control: an object's are accepted" {
        val e = shouldThrow<ConfigError> { cfg(*base, "job.object.dual-benchy.part.deck.priorities.quality" to "10", models = listOf(benchy)) }
        e.message!! shouldContain "job.priorities.quality cannot be set for a part; it applies to the plate or an object"
        cfg(*base, "job.object.dual-benchy.priorities.quality" to "10", models = listOf(benchy))
    }
    "a flat-layer value may be set on an object or a part; the object-level declarations are accepted as written" {
        cfg(*base, "plan.object.dual-benchy.part.deck.technique.flat.core.layers" to "1", models = listOf(benchy))
        cfg(*base, "plan.object.dual-benchy.technique.flat.layer.height" to "0.1 mm", models = listOf(benchy))
        cfg(*base, "plan.object.dual-benchy.pose.turn" to "1 0 0 0 1 0 0 0 1", models = listOf(benchy))
        Schema.scopesOf(listOf("plan", "technique", "flat", "layer", "height")) shouldBe setOf(Schema.Scope.PLATE, Schema.Scope.OBJECT, Schema.Scope.PART)
        Schema.scopesOf(listOf("plan", "mechanism", "purge", "tower", "x")) shouldBe setOf(Schema.Scope.PLATE)
        Schema.scopesOf(listOf("plan", "insert", "ball", "top")) shouldBe setOf(Schema.Scope.PLATE, Schema.Scope.OBJECT)
    }
})
