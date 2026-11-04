package plymeld.commands

import com.github.ajalt.mordant.terminal.Terminal
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.nio.file.Files

/**
 * The terminal side of `plymeld init`: a terminal that reports no width is asked at a fallback width (a list drawn at width
 * 0 shows every entry blank); the closing line says how the files are read, by themselves only from plymeld's own config
 * folder. Controls: a terminal with a width is used as it is; the own folder needs no -cf.
 */
class InitFlowTest : StringSpec({
    "a terminal reporting no width is asked at 80 columns; control: one with a width is used as it is" {
        val none = InitFlow.sized(Terminal(width = 0, height = 0))
        none.size.width shouldBe InitFlow.FALLBACK_WIDTH
        none.size.height shouldBe InitFlow.FALLBACK_HEIGHT
        val wide = Terminal(width = 120, height = 40)
        InitFlow.sized(wide) shouldBeSameInstanceAs wide
    }

    "files written to another folder are read with -cf, named; control: plymeld's own folder needs none" {
        val own = Files.createTempDirectory("plymeld-own-config")
        val other = Files.createTempDirectory("plymeld-elsewhere")
        val advice = InitFlow.readAdvice(other, own)
        advice shouldContain "-cf '${other.resolve("*.toml")}'"
        advice shouldNotContain "no -cf needed"
        InitFlow.readAdvice(own, own) shouldBe "no -cf needed"
        // the same folder named another way (a trailing "." step) is still the own one
        InitFlow.readAdvice(own.resolve("."), own) shouldBe "no -cf needed"
    }
})
