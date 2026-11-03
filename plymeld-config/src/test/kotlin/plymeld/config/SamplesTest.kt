package plymeld.config

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** The sample configs (test-models/config) pass plymeld's own checks, and the committed JSON Schema is the generated one. */
class SamplesTest : StringSpec({
    val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }

    "every sample config reads without a problem, with the sample model's object and parts" {
        val stack = ConfigStack.load(listOf("test-models/config/*.toml"), emptyList(), repo)
        val model = ModelObject("3DBenchy-dual", listOf("3DBenchy-dual-A", "3DBenchy-dual-B"), listOf(1, 2), "3DBenchy-dual.3mf")
        val c = Config(stack, listOf(model))
        // the samples set values nothing reads yet on purpose (to show them); nothing else may warn
        c.warnings.filter { "not used yet" !in it }.shouldBeEmpty()
    }
    "plymeld.schema.json is what `plymeld schema` generates (regenerate it with `plymeld schema -o plymeld.schema.json`)" {
        Files.readString(repo.resolve("plymeld.schema.json")) shouldBe SchemaJson.text()
    }
})
