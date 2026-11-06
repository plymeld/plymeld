package plymeld.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import plymeld.commands.Commands
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window driven through its own controls, as a user drives it (the finder box clicked and typed into, a hit clicked,
 * a form read), against the command line: every command the window offers opens its form from the finder, and the form
 * shows the command's own words, those `--help` prints. Each form's picture goes to build/renders/window-drive/.
 */
@OptIn(ExperimentalTestApi::class)
class WindowDriveTest : StringSpec({
    val shots: Path = Path.of("build", "renders", "window-drive").also { Files.createDirectories(it) }
    fun ComposeUiTest.shot(name: String) =
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("$name.png").toFile())
    fun norm(s: String) = s.replace(Regex("\\s+"), " ").trim()
    /** `plymeld <path> --help` as the command line prints it, whitespace folded. */
    fun cliHelp(path: List<String>): String { val out = StringBuilder(); Commands.invoke(path + "--help", { out.append(it).append('\n') }); return norm(out.toString()) }

    "every command opens its form from the finder, showing what --help says of it" {
        val problems = ArrayList<String>()
        Viewport3D().use { vp ->
            val j = JobState(ViewerState(vp))
            val ui = WindowUi()
            runDesktopComposeUiTest(1600, 1000) {
                setContent { WindowApp(j, ui) }
                waitForIdle()
                for (c in Finder.commands) {
                    // the bar's finder box, clicked; the command's line typed
                    onNodeWithText("find a setting, a command", substring = true).performClick()
                    waitForIdle()
                    if (c == Finder.commands.first()) shot("finder-open")
                    waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                    val box = onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()
                    box.performTextClearance(); box.performTextInput(c.line)
                    waitForIdle()
                    // the hit: a clickable row of the finder's (merged: its kind, its title, its detail) titled with the line
                    val row = hasClickAction() and hasText(c.line, substring = false) and hasText("command", substring = false)
                    if (onAllNodes(row).fetchSemanticsNodes().isEmpty()) { problems += "${c.line}: the finder lists no hit for its own line"; ui.finderOpen = false; waitForIdle(); continue }
                    onAllNodes(row).onFirst().performClick()
                    waitForIdle()
                    if (ui.finderOpen || ui.page != "command:${c.line}") { problems += "${c.line}: its hit did not open its form"; ui.finderOpen = false; waitForIdle(); continue }
                    // the form: its line, its help, every argument and option by name with its help, the line it runs
                    fun shows(what: String, text: String, substring: Boolean = false) {
                        if (onAllNodes(hasText(text, substring = substring), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) problems += "${c.line}: the form does not show $what ('${text.take(80)}')"
                    }
                    shows("the command's help", c.help)
                    val runs = c.subcommands.isEmpty() || c.runsAlone || c.options.isNotEmpty()
                    if (runs) shows("the line it runs", ("$ plymeld " + c.path.joinToString(" ")).trimEnd(), substring = true)
                    val help = cliHelp(c.path)
                    if (norm(c.help) !in help) problems += "${c.line}: --help does not say the form's help"
                    // each argument and option: the form's label is --help's term (an optional argument bracketed, as in the
                    // usage line), its text --help's description
                    val usage = help.substringBefore(" Options:")
                    for (a in c.arguments) {
                        val label = a.usage
                        shows("argument ${a.name} as $label", label)
                        if (a.help.isNotBlank()) shows("argument ${a.name}'s help", a.help, substring = true)
                        if (label !in usage) problems += "${c.line}: --help's usage line does not write $label"
                        if (a.help.isNotBlank() && norm(a.help) !in help) problems += "${c.line}: --help words the argument ${a.name} otherwise than the form"
                    }
                    for (o in c.options) {
                        shows("option ${o.term}", o.term)
                        if (o.description.isNotBlank()) shows("option ${o.term}'s description", o.description, substring = true)
                        if (o.term !in help) problems += "${c.line}: --help does not write ${o.term}"
                        if (o.description.isNotBlank() && norm(o.description) !in help) problems += "${c.line}: --help describes ${o.term} otherwise than the form"
                    }
                    // its subcommands as --help lists them under Commands; Run only where the command runs without one
                    for (s in c.subcommands.filter { it.line !in UiRegistry.notYet }) {
                        shows("subcommand ${s.name}", s.name)
                        if (s.name !in help.substringAfter(" Commands:", "")) problems += "${c.line}: --help does not list the subcommand ${s.name}"
                    }

                    val hasRun = onAllNodes(hasText("Run") and hasClickAction()).fetchSemanticsNodes().isNotEmpty()
                    if (hasRun != runs) problems += "${c.line}: the form ${if (hasRun) "offers" else "does not offer"} Run, but the command ${if (runs) "runs" else "needs a subcommand"}"
                    shot(c.path.joinToString("-").ifEmpty { "plymeld" })
                    // a subcommand's name opens its form
                    c.subcommands.firstOrNull { it.line !in UiRegistry.notYet }?.let { s ->
                        onAllNodes(hasClickAction() and hasText(s.name)).onFirst().performClick(); waitForIdle()
                        if (ui.page != "command:${s.line}") problems += "${c.line}: clicking ${s.name} did not open its form (the drawer shows ${ui.page})"
                    }
                    ui.why = false; ui.page = null
                    waitForIdle()
                }
            }
        }
        withClue(problems.joinToString("\n")) { problems.shouldBeEmpty() }
    }
})
