package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.nio.file.Files

/** The command line's PATH entry in a Windows install: added once, taken out alone, every other entry kept as written. */
class CliPathTest : StringSpec({
    val cli = "C:\\Users\\a\\AppData\\Local\\plymeld\\current\\cli"
    val user = "%USERPROFILE%\\.dotnet\\tools;C:\\Tools"

    "added at the end, once; the user's entries kept as written, %VAR% unexpanded" {
        CliPath.withEntry(user, cli) shouldBe "$user;$cli"
        CliPath.withEntry("", cli) shouldBe cli
        // already there, in another case or with a trailing backslash: unchanged
        CliPath.withEntry("$user;${cli.uppercase()}\\", cli) shouldBe "$user;${cli.uppercase()}\\"
        // control: a different folder is not taken for it
        CliPath.withEntry("$user;$cli-old", cli) shouldNotBe "$user;$cli-old"
    }

    "taken out alone, wherever it is; nothing else changes" {
        CliPath.withoutEntry("C:\\Tools;$cli;%USERPROFILE%\\.dotnet\\tools", cli) shouldBe "C:\\Tools;%USERPROFILE%\\.dotnet\\tools"
        CliPath.withoutEntry("$cli\\", cli) shouldBe ""
        CliPath.withoutEntry(CliPath.withEntry(user, cli), cli) shouldBe user
        // control: a PATH without it is left as it was
        CliPath.withoutEntry(user, cli) shouldBe user
    }

    "the hook does nothing off Windows, or in an install without the command line" {
        val dir = Files.createTempDirectory("plymeld-clipath")
        val exe = dir.resolve("plymeld-ui.exe")
        // no cli\plymeld.exe beside it: returns without starting PowerShell (which this machine may not have)
        CliPath.hook(arrayOf("--veloapp-install", "1.0.0"), exe, "Windows 11")
        CliPath.hook(arrayOf("--veloapp-install", "1.0.0"), exe, "Linux")
    }
})
