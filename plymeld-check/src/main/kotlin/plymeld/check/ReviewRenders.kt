package plymeld.check

import java.nio.file.Files
import java.nio.file.Path

/**
 * Where a test writes a review picture (a print technique's sample toolpath):
 * `build/renders/` of the test's module, so an ordinary build never rewrites the committed copies in `docs/renders/`. To
 * refresh those on purpose, run the tests with `-Pplymeld.refreshRenders`,
 * e.g. `./gradlew :plymeld-commands:test --tests 'plymeld.commands.SurfaceTechniqueSampleTest' -Pplymeld.refreshRenders`,
 * then review and commit them. In the main source set so the tests of every module that depends on
 * plymeld-check (plymeld-commands: the technique samples) write through it too.
 */
fun reviewRender(name: String): Path {
    val dir = if (System.getProperty("plymeld.refreshRenders") == "true") repoRoot().resolve("docs/renders")
        else Path.of("build/renders").toAbsolutePath()
    Files.createDirectories(dir)
    return dir.resolve(name)
}

/** The repository's root: the nearest directory up from the working directory holding `settings.gradle.kts`. */
private fun repoRoot(): Path {
    var p = Path.of("").toAbsolutePath()
    while (true) {
        if (Files.isRegularFile(p.resolve("settings.gradle.kts"))) return p
        p = p.parent ?: error("settings.gradle.kts not found above ${Path.of("").toAbsolutePath()}")
    }
}
