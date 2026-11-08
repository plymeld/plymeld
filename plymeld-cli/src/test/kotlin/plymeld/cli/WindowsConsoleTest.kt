package plymeld.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * The code page `chcp` reports, in the words of each Windows language (the number is last, a period after it in some):
 * read from its answer. Control: an answer with no number reads as none, and nothing is switched.
 */
class WindowsConsoleTest : StringSpec({
    "the code page from chcp's answer, whatever its language; control: no number, none" {
        WindowsConsole.codePage("Active code page: 437\r\n") shouldBe 437
        WindowsConsole.codePage("Aktive Codepage: 1252.") shouldBe 1252
        WindowsConsole.codePage("Página de códigos activa: 850") shouldBe 850
        WindowsConsole.codePage("Active code page: 65001") shouldBe 65001
        WindowsConsole.codePage("'chcp' is not recognized as an internal or external command") shouldBe null
    }
})
