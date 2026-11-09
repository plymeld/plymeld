package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/**
 * The finder's docs index lists every page under `docs/user/` with its title (its first heading), each opened online
 * under the repository's address; and only pages that exist. Control: a page not in the repository is not listed.
 */
class DocsIndexTest : StringSpec({
    val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }

    "every user docs page is in the finder's index with its title; control: none that does not exist" {
        val user = repo.resolve("docs/user")
        val pages = Files.walk(user).use { s -> s.toList() }.filter { it.isRegularFile() && it.extension == "md" }
            .associate { p -> "docs/" + repo.resolve("docs").relativize(p).toString().replace('\\', '/') to Files.readAllLines(p).first { it.startsWith("# ") }.removePrefix("# ").trim() }
        val listed = Finder.Docs.pages.associate { it.path to it.title }
        (pages.isNotEmpty()) shouldBe true
        listed shouldBe pages
        Finder.Docs.url("docs/user/tutorials/install.md") shouldStartWith "https://github.com/plymeld/plymeld/"
        ("docs/user/no-such-page.md" in listed) shouldBe false
    }
})
