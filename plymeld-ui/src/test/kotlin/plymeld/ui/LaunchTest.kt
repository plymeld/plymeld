package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * What the window's executable does with its arguments (Launch.kt): a command of the command line, or --version, goes to
 * the command line beside it; --help shows both helps; the window's own options open the window; anything else is
 * refused; Velopack's hooks stay hooks. Nothing here opens a window.
 */
class LaunchTest : StringSpec({
    val linux = System.getProperty("os.name").lowercase().contains("linux")
    val commands = Launch.commandNames()
    fun decide(vararg a: String) = Launch.decide(a.toList())

    "the command line's commands are read off its tree" {
        commands shouldContainAll listOf("slice", "plan", "config", "update", "updates", "render", "printer", "calibrate")
        // control: a word that is no command, and the window's own options, are not taken for commands
        ("slicee" in commands) shouldBe false
        ("--screenshot" in commands) shouldBe false
    }

    "a command, or --version, first: the command line runs it with every argument" {
        decide("--version") shouldBe Launch.Action.CommandLine(listOf("--version"))
        decide("update") shouldBe Launch.Action.CommandLine(listOf("update"))
        decide("slice", "-cf", "a.toml", "-m", "b.stl", "-o", "c.gcode") shouldBe Launch.Action.CommandLine(listOf("slice", "-cf", "a.toml", "-m", "b.stl", "-o", "c.gcode"))
        // a command's own help is the command line's
        decide("slice", "--help") shouldBe Launch.Action.CommandLine(listOf("slice", "--help"))
        for (c in commands) decide(c).shouldBeInstanceOf<Launch.Action.CommandLine>()
    }

    "--help or -h: the help, the command line's and the window's" {
        decide("--help") shouldBe Launch.Action.Help
        decide("-h") shouldBe Launch.Action.Help
        decide("--theme", "dark", "--help") shouldBe Launch.Action.Help
        Launch.usage() shouldContain "Window options:"
        for (o in Launch.options) Launch.usage() shouldContain o.name
    }

    "the window's own options open the window; control: no arguments too" {
        decide() shouldBe Launch.Action.Window
        decide("-cf", "a.toml", "-cf", "b.toml", "-m", "c.stl", "--slice", "--tab", "plan", "--theme", "light") shouldBe Launch.Action.Window
        decide("--icons", "out") shouldBe Launch.Action.Window
        decide("--bench", "--segments", "1000", "--frames", "5") shouldBe Launch.Action.Window
        decide("--gl-info", "--gl-platform", "x11") shouldBe Launch.Action.Window
        // a value is taken as given, whatever it looks like
        decide("--find", "--slice") shouldBe Launch.Action.Window
    }

    "anything else is refused, naming it" {
        (decide("--no-such-option") as Launch.Action.Refused).message shouldContain "no such command or window option: --no-such-option"
        (decide("slicee") as Launch.Action.Refused).message shouldContain "slicee"
        (decide("-cf", "a.toml", "model.stl") as Launch.Action.Refused).message shouldContain "model.stl"
        (decide("--slice", "-x") as Launch.Action.Refused).message shouldContain "-x"
        // a command's option is not the window's: given alone, it is refused rather than ignored
        (decide("-o", "c.gcode") as Launch.Action.Refused).message shouldContain "-o"
        (decide("--size") as Launch.Action.Refused).message shouldContain "--size needs a value"
    }

    "Velopack's hooks are hooks, before anything else is asked" {
        Launch.decide(listOf("--veloapp-install", "0.0.2")) { error("the command tree is not asked for a hook") } shouldBe Launch.Action.Hook
        Launch.decide(listOf("--veloapp-updated", "0.0.2")) { error("the command tree is not asked for a hook") } shouldBe Launch.Action.Hook
        // control: the tree is asked otherwise
        var asked = false
        Launch.decide(listOf("slice")) { asked = true; setOf("slice") } shouldBe Launch.Action.CommandLine(listOf("slice"))
        asked shouldBe true
    }

    "every option the window declares is read in Main.kt, and every one it reads is declared" {
        val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }.first { Files.exists(it.resolve("settings.gradle.kts")) }
        val main = Files.readString(repo.resolve("plymeld-ui/src/main/kotlin/plymeld/ui/Main.kt"))
        val read = Regex("""\b(?:opt|opts|flag)\("(-[^"]+)"\)""").findAll(main).map { it.groupValues[1] }.toSet()
        val declared = Launch.options.map { it.name }.toSet() - setOf("--help", "-h")
        (declared - read).shouldBeEmpty()
        (read - declared).shouldBeEmpty()
        // control: the reading finds the reads, and would miss an option no longer read
        read shouldContain "--screenshot"
        read shouldContain "--ortho"
        val without = Regex("""\b(?:opt|opts|flag)\("(-[^"]+)"\)""").findAll(main.replace("flag(\"--ortho\")", "false")).map { it.groupValues[1] }.toSet()
        (declared - without) shouldBe setOf("--ortho")
        // no option read around the declared list (`"--x" in args`)
        Regex(""""-[-a-z]+" !?in args""").findAll(main).map { it.value }.toList().shouldBeEmpty()
    }

    "the command line beside the window: plymeld in its folder, cli\\plymeld.exe on Windows; control: none, or itself" {
        val dir = Files.createTempDirectory("plymeld-launch")
        try {
            val ui = Files.writeString(dir.resolve("plymeld-ui"), "")
            Launch.commandLine(ui, windows = false) shouldBe null                                    // none beside it
            val cli = Files.writeString(dir.resolve("plymeld"), "")
            if (linux) {
                Launch.commandLine(ui, windows = false) shouldBe null                                // not executable
                cli.toFile().setExecutable(true)
            }
            Launch.commandLine(ui, windows = false) shouldBe cli.toAbsolutePath()
            Launch.commandLine(cli, windows = false) shouldBe null                                   // itself, renamed
            Launch.commandLine(null, windows = false) shouldBe null
            val exe = Files.writeString(dir.resolve("plymeld-ui.exe"), "")
            Launch.commandLine(exe, windows = true) shouldBe null                                    // no cli\ folder
            val winCli = Files.writeString(Files.createDirectories(dir.resolve("cli")).resolve("plymeld.exe"), "")
            winCli.toFile().setExecutable(true)
            Launch.commandLine(exe, windows = true) shouldBe winCli.toAbsolutePath()
        } finally { dir.toFile().deleteRecursively() }
    }

    "the command line runs with the arguments as given, and its exit status is returned".config(enabled = linux) {
        val dir = Files.createTempDirectory("plymeld-launch-run")
        try {
            val got = dir.resolve("args.txt")
            val cli = Files.writeString(dir.resolve("plymeld"), "#!/bin/sh\nprintf '%s\\n' \"\$@\" > \"${got}\"\nexit 3\n")
            cli.toFile().setExecutable(true)
            Launch.run(cli, listOf("slice", "-m", "a model.stl", "--version")) shouldBe 3
            Files.readAllLines(got) shouldBe listOf("slice", "-m", "a model.stl", "--version")
        } finally { dir.toFile().deleteRecursively() }
    }

    // the window's executable on the JVM (a development run: no command line beside the JVM), its main in a process of its own
    val java = ProcessHandle.current().info().command().orElseThrow()
    fun main(vararg args: String): Triple<Int, String, String> {
        val dir = Files.createTempDirectory("plymeld-launch-main")
        try {
            val out = dir.resolve("out.txt"); val err = dir.resolve("err.txt")
            val pb = ProcessBuilder(listOf(java, "-Djava.awt.headless=true", "-cp", System.getProperty("java.class.path"), "plymeld.ui.MainKt") + args)
                .redirectOutput(out.toFile()).redirectError(err.toFile()).redirectInput(ProcessBuilder.Redirect.from(Files.createFile(dir.resolve("in.txt")).toFile()))
            pb.environment()["PLYMELD_HOME"] = dir.resolve("home").toString()
            val p = pb.start()
            // a window would not exit: the arguments here never open one
            if (!p.waitFor(120, TimeUnit.SECONDS)) { p.destroyForcibly(); error("plymeld-ui ${args.joinToString(" ")} did not exit: it opened a window") }
            return Triple(p.exitValue(), Files.readString(out), Files.readString(err))
        } finally { dir.toFile().deleteRecursively() }
    }

    "an unknown argument is refused in one line with the command line's usage status, the window never opened" {
        val (status, out, err) = main("--no-such-option")
        status shouldBe 1
        out shouldBe ""
        err.trim() shouldBe "plymeld: no such command or window option: --no-such-option (--help lists both)"
    }

    "a command with no command line beside the window: said in one line, status 1, the window never opened" {
        for (a in listOf("--version", "update")) {
            val (status, _, err) = main(a)
            status shouldBe 1
            err shouldContain "plymeld: no command line beside"
            err.trim().lines().size shouldBe 1
        }
    }

    "--help with no command line beside the window: the window's own options" {
        val (status, out, err) = main("--help")
        status shouldBe 0
        out shouldContain "Window options:"
        out shouldContain "--screenshot PNG"
        err shouldContain "no command line beside"
    }

    "controls: a hook still exits at once, and a window option still takes the window's path" {
        main("--veloapp-install", "0.0.2").first shouldBe 0
        val dir = Files.createTempDirectory("plymeld-launch-icons")
        try {
            val (status, out, _) = main("--icons", dir.toString())
            status shouldBe 0
            out shouldContain "icon: "
            Files.isRegularFile(dir.resolve("plymeld-256.png")) shouldBe true
        } finally { dir.toFile().deleteRecursively() }
    }
})
