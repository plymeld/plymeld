package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * The built executables' smoke run (tools/native-smoke.sh, which CI and the release run on the native command line) has
 * a case for every command of the command model, or a line in its not-yet ledger (`not_yet "<command>" "<reason>"`) with
 * a reason; and names no command the model does not have. So a command added to the CLI fails this test until the smoke
 * run exercises it on the built executable, as UiParityTest holds the window to the same tree.
 */
class NativeSmokeCoverageTest : StringSpec({
    val repo: Path = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
    val script = Files.readString(repo.resolve("tools/native-smoke.sh"))
    val lines = CommandModel.read().root.all().map { it.line }
    // a case: a call of one of the script's case functions, its first argument the command line it runs, in quotes
    // (a loop's "plymeld calibrate $plate" stands for each value the loop names)
    val case = Regex("""(?m)^\s*(?:if\s+)?(?:\S+=\S+\s+)?(ok|refused|pictures|not_yet)\s+"(plymeld[^"]*)"""")
    fun covered(text: String): Set<String> {
        val out = HashSet<String>()
        for (m in case.findAll(text)) {
            val cmd = m.groupValues[2]
            val v = Regex("""\$(\w+)""").find(cmd)
            if (v == null) { out += cmd; continue }
            // the values of `for NAME in ...; do` that this case's line sits in
            val loop = Regex("""for ${v.groupValues[1]} in ([^;]+); do""").findAll(text.substring(0, m.range.first)).lastOrNull()
                ?: error("case \"$cmd\": no loop gives \$${v.groupValues[1]}")
            for (value in loop.groupValues[1].trim().split(Regex("\\s+"))) out += cmd.replace(v.value, value.substringBefore(':'))
        }
        return out
    }
    val cases = covered(script)
    val ledger = Regex("""(?m)^\s*not_yet\s+"(plymeld[^"]*)"\s+"([^"]*)"""").findAll(script).map { it.groupValues[1] to it.groupValues[2] }.toList()

    "every command of the model has a smoke case or a not-yet line" {
        lines.filter { it !in cases }.shouldBeEmpty()
    }
    "every not-yet line gives its reason" {
        ledger.filter { it.second.isBlank() }.shouldBeEmpty()
    }
    "the smoke run names no command the model does not have (the window's own command line apart)" {
        cases.filter { it !in lines && it != "plymeld-ui" }.shouldBeEmpty()
    }
    "control: the reading finds the cases, loops included, and would miss a command with none" {
        cases shouldContain "plymeld render"
        cases shouldContain "plymeld calibrate seams values"
        cases shouldContain "plymeld printer pause"
        cases shouldContain "plymeld-ui"
        val without = covered(script.lines().filterNot { it.contains("\"plymeld migrate\"") }.joinToString("\n"))
        lines.filter { it !in without } shouldBe listOf("plymeld migrate")
    }
})
