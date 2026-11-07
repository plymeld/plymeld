package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** `--placement` takes `center` or `as-given`, and reads `centre` as `center`. */
class PlacementOptionTest : StringSpec({
    "'centre' places the model as 'center' does; control: any other value is refused, naming it" {
        poseOverride("as-given", "centre")!!.label shouldBe poseOverride("as-given", "center")!!.label
        poseOverride("as-given", "as-given")!!.label shouldBe "as given"
        shouldThrow<UsageError> { poseOverride("as-given", "middle") }.message!! shouldContain "'middle'"
    }
})
