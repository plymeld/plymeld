package plymeld.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.Commands
import java.nio.file.Files
import java.nio.file.Path

/**
 * The window run as a user runs it, against the command line run the same way: a command's form filled field by field and
 * Run, and the job's steps (opened, then Slice & check), each compared with the CLI given the same words: the lines it
 * prints and the files it writes. The pictures go to build/renders/window-runs/.
 */
@OptIn(ExperimentalTestApi::class)
class WindowRunsTest : StringSpec({
    val shots: Path = Path.of("build", "renders", "window-runs").also { Files.createDirectories(it) }
    val repo: Path = Path.of("..").toAbsolutePath().normalize()
    // a glob, as a user types it on the platform (on Windows a * is no path character: the glob is text)
    val config = repo.resolve("test-models").resolve("config").toString() + java.io.File.separator + "0*.toml"
    fun ComposeUiTest.shot(name: String) =
        javax.imageio.ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", shots.resolve("$name.png").toFile())
    /** A button labeled [label] (its role a button, as a screen reader finds it), not other text with the same words. */
    fun button(label: String) = hasText(label) and androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Button)
    /** The CLI, in-process, as `plymeld WORDS` prints: its status and lines. */
    fun cli(words: List<String>): Pair<Int, List<String>> { val out = ArrayList<String>(); val st = Commands.invoke(words, { out += it.split('\n') }); return st to out }

    /** The finder clicked, [line] typed, its hit clicked: the command's form open in the drawer. */
    fun ComposeUiTest.openForm(ui: WindowUi, line: String) {
        if (!ui.finderOpen) onNodeWithText("find a setting, a command", substring = true).performClick()
        waitUntil(timeoutMillis = 10_000) { onAllNodes(hasSetTextAction(), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        val box = onAllNodes(hasSetTextAction(), useUnmergedTree = true).onFirst()
        box.performTextClearance(); box.performTextInput(line)
        waitForIdle()
        onAllNodes(hasClickAction() and hasText(line) and hasText("command")).onFirst().performClick()
        waitForIdle()
        ui.page shouldBe "command:$line"
    }
    /** The form's field named [term] (its label, as --help writes the option or argument) given [value], typed in. */
    fun ComposeUiTest.fill(term: String, value: String) {
        val f = onAllNodes(hasContentDescription(term) and hasSetTextAction(), useUnmergedTree = true).onFirst()
        f.performTextClearance(); f.performTextInput(value)
        waitForIdle()
    }
    /** Run clicked; waits for the command to finish (Run shows again) and returns what the console printed after its own line. */
    fun ComposeUiTest.run(j: JobState): List<String> {
        val from = j.console.size
        onAllNodes(button("Run")).onFirst().performScrollTo().performClick()
        try { waitUntil(timeoutMillis = 300_000) { j.console.size > from && onAllNodes(hasText("running…")).fetchSemanticsNodes().isEmpty() } }
        catch (e: Throwable) { error("Run did not finish; the console holds:\n" + j.console.takeLast(40).joinToString("\n") + "\nthreads:\n" + Thread.getAllStackTraces().filterKeys { it.name.startsWith("plymeld") }.entries.joinToString("\n") { (t, s) -> t.name + "\n  " + s.take(25).joinToString("\n  ") }) }
        waitForIdle()
        return j.console.drop(from)
    }

    "a form filled and Run does what the command line does: the first-layer plate, its lines and its files" {
        val dir = Files.createTempDirectory("plymeld-window-runs")
        val ui = dir.resolve("ui.gcode"); val cl = dir.resolve("cli.gcode")
        Viewport3D().use { vp ->
            val j = JobState(ViewerState(vp))
            val w = WindowUi()
            runDesktopComposeUiTest(1600, 1000) {
                setContent { WindowApp(j, w) }
                waitForIdle()
                openForm(w, "plymeld calibrate first-layer")
                fill("-cf, --config-file=FILE|GLOB", config)
                fill("-cv, --config-value=<path=value>", "hardware.machine.example-corexy.model=qidi-q2")
                fill("--temps=<low-high>", "210-230")
                fill("-o, --output=<file>", ui.toString())
                shot("first-layer-form-filled")
                val lines = run(j)
                shot("first-layer-ran")
                // the line it ran is the form's, and the CLI given the same words prints the same (the file's name aside)
                val words = listOf("calibrate", "first-layer", "-cf", config, "-cv", "hardware.machine.example-corexy.model=qidi-q2", "-o", cl.toString(), "--temps", "210-230")
                lines.first() shouldBe "$ plymeld calibrate first-layer --config-file $config --config-value hardware.machine.example-corexy.model=qidi-q2 --output $ui --temps 210-230"
                val (st, cliLines) = cli(words)
                st shouldBe 0
                withClue("the console against the CLI") { lines.drop(1).map { it.replace(ui.toString(), "OUT").replace("ui.", "OUT.") } shouldBe cliLines.filter { it.isNotEmpty() || true }.map { it.replace(cl.toString(), "OUT").replace("cli.", "OUT.") }.dropLastWhile { it.isEmpty() } }
                // the files: the g-code, the page, and its picture
                Files.readString(ui).replace("ui.", "OUT.") shouldBe Files.readString(cl).replace("cli.", "OUT.")
                Files.readString(dir.resolve("ui.md")).replace("ui.", "OUT.") shouldBe Files.readString(dir.resolve("cli.md")).replace("cli.", "OUT.")
                Files.readAllBytes(dir.resolve("ui.png")).contentEquals(Files.readAllBytes(dir.resolve("cli.png"))) shouldBe true
            }
        }
    }

    "the job's steps: opened with a config and a model, Slice & check writes the g-code plymeld slice writes" {
        val dir = Files.createTempDirectory("plymeld-window-slice")
        val model = repo.resolve("test-models/holeblock.stl").toString()
        val cl = dir.resolve("cli.gcode")
        Viewport3D().use { vp ->
            val j = JobState(ViewerState(vp))
            val w = WindowUi()
            runDesktopComposeUiTest(1600, 1000) {
                // the window as `plymeld-ui -cf CONFIG -m MODEL` opens it (Main: the job opened, then its steps), Slice & check as Main wires it
                setContent { WindowApp(j, w, onSlice = { j.slice(j.sliceOut()) }) }
                val opened = java.util.concurrent.CountDownLatch(1)
                j.open(listOf(config), listOf(model), null) { opened.countDown() }
                opened.await()
                waitForIdle()
                shot("steps-planned")
                onAllNodes(button("Slice & check")).onFirst().performScrollTo().performClick()
                try { waitUntil(timeoutMillis = 600_000) { j.slice.status == JobState.Status.DONE || j.slice.status == JobState.Status.FAILED } }
                catch (e: Throwable) { error("the slice did not finish (${j.slice.lines}); the console holds:\n" + j.console.takeLast(40).joinToString("\n") + "\nthreads:\n" + Thread.getAllStackTraces().filterKeys { it.name.startsWith("plymeld") }.entries.joinToString("\n") { (t, s) -> t.name + "\n  " + s.take(25).joinToString("\n  ") }) }
                waitForIdle()
                shot("steps-sliced")
                j.slice.status shouldBe JobState.Status.DONE
                val out = j.gcode!!
                val (st, lines) = cli(listOf("slice", "-cf", config, "-m", model, "-o", cl.toString()))
                withClue(lines.joinToString("\n")) { st shouldBe 0 }
                // the same g-code, the file's own name aside
                Files.readString(out).replace(out.fileName.toString(), "OUT") shouldBe Files.readString(cl).replace(cl.fileName.toString(), "OUT")
                // and the console said the line that does it
                j.console.any { it.startsWith("$ plymeld slice -cf ") && it.contains(" -m $model") } shouldBe true
                j.slice.lines.first() shouldContain "checked"
            }
        }
    }
})
