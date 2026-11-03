package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldMatch

class MainTest : StringSpec({
    "version resource is baked in, not the fallback" {
        PlymeldVersion.value shouldNotBe "unknown"
        PlymeldVersion.value shouldMatch Regex("""\d+\.\d+\.\d+.*""")
    }
})
