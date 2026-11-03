package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import java.nio.file.Files

/** The g-code's source block (SourceBlock): version, models and config in force, read back as TOML. */
class SourceBlockTest : StringSpec({
    val dir = Files.createTempDirectory("source-block")
    val model = dir.resolve("part.stl").also { Files.writeString(it, "solid part\nendsolid part\n") }
    val file = dir.resolve("sub").also { Files.createDirectories(it) }.resolve("job.toml")
    Files.writeString(file, """
        [hardware.machine.m]
        model = "flashforge-c5"
        start_gcode = "G28\nM104 S\"200\"\t; heat \\ wait"
        connection.host = "192.0.2.7"
        connection.credential.check_code.env = "M_CHECK"

        [plan.technique.flat]
        layer.height = "!0.2 mm"

        [plan.object."ledge 4.stl"]
        faces.under.technique = "wave_overhang"

        [job]
        colors = ["red", "blue"]
    """.trimIndent())
    val stack = ConfigStack(ConfigFiles.read(file) + ConfigFiles.value("plan.technique.flat.heights=one"))
    val plan = "# Written by `plymeld plan`\n\n[plan]\nobjects.printing = \"together\"\n\n[plan.object.part]\npose.turn = \"1 0 0 0 1 0 0 0 1\"\n"
    val block = SourceBlock.block("9.9.9-test", stack, listOf(model), plan, "m", emptySet())

    fun configLines() = block.lines().dropWhile { !it.startsWith("; config:") }.drop(1).takeWhile { !it.startsWith(SourceBlock.PLAN) }
        .filter { l -> SourceBlock.Group.entries.none { l.startsWith(it.header) } }
    fun planLines() = block.lines().dropWhile { !it.startsWith(SourceBlock.PLAN) }.drop(1).takeWhile { it != SourceBlock.END }

    "the version and each model, its size and SHA-256 computed apart from the block" {
        block shouldContain "; plymeld = \"9.9.9-test\""
        val bytes = Files.readAllBytes(model)
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        block shouldContain "; model = \"part.stl\"  # ${bytes.size} bytes, sha256 $sha"
    }

    "the config lines read back as TOML to the values in force, a machine's connection left out" {
        val back = ConfigFiles.readToml(configLines().joinToString("\n") { it.removePrefix("; ") }, "block")
            .associate { it.key to (it.value to it.keep) }
        val expected = stack.effective.values.filterNot { it.path.getOrNull(3) == "connection" }.associate { it.key to (it.value to it.keep) }
        back shouldBe expected
        // control: the stack did hold the connection values, so their absence is the filter's doing
        stack.effective.keys.count { ".connection." in it } shouldBe 2
        block shouldNotContain "192.0.2.7"
        block shouldNotContain "M_CHECK"
    }

    "a multi-line value stays on one line; sources name the file, not its directory" {
        configLines().size shouldBe stack.effective.size - 2
        block shouldContain "  # job.toml:"
        block shouldNotContain dir.toString()
        block shouldContain "# command line --config-value 'plan.technique.flat.heights=one'"
    }

    "the plan part gives back the plan file, line for line" {
        planLines().joinToString("\n") { it.removePrefix(";").removePrefix(" ") } + "\n" shouldBe plan
    }

    // docs/gcode-format.md, read independently of SourceBlock's constants: each line in its documented order
    "the block follows docs/gcode-format.md line by line, in its order" {
        val ls = block.trimStart('\n').trimEnd('\n').lines()
        var i = 0
        fun next() = ls[i++]
        next() shouldBe "; PLYMELD_SOURCE_BLOCK_START"
        next() shouldBe "; format = \"1\""
        while (ls[i].startsWith("; #")) i++
        next() shouldBe "; plymeld = \"9.9.9-test\""
        Regex("""^; model = "[^"/\\]+"  # \d+ bytes, sha256 [0-9a-f]{64}$""").matches(next()) shouldBe true
        val config = Regex("""^; config: (\d+) values \(.*\), sha256 ([0-9a-f]{64})$""").find(next())!!
        val groupStart = i
        var total = 0
        for (heading in listOf("used, yours", "used, plymeld's", "other, yours", "other, plymeld's")) {
            val n = Regex("""^; ${Regex.escape(heading)}: (\d+) value\(s\)$""").find(next())!!.groupValues[1].toInt()
            val keys = (1..n).map { Regex("""^; (.+?) = ("[^\n]*"|\[[^\n]*\])  # .+$""").find(next())!!.groupValues[1] }
            keys shouldBe keys.sorted()
            total += n
        }
        total shouldBe config.groupValues[1].toInt()
        val groups = ls.subList(groupStart, i).joinToString("") { "$it\n" }
        java.security.MessageDigest.getInstance("SHA-256").digest(groups.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) } shouldBe config.groupValues[2]
        next().startsWith("; plan: ") shouldBe true
        while (i < ls.size - 1) Regex("""^;( .*)?$""").matches(next()) shouldBe true
        next() shouldBe "; PLYMELD_SOURCE_BLOCK_END"
    }

    "read: format 1 is read, comments skipped; controls: another format number and a block without one are refused" {
        val out = SourceBlock.append("G28", "9.9.9-test", stack, listOf(model), plan, "m", emptySet())
        // a comment that looks like a value is skipped, not read as the version
        val commented = out.replace("; plymeld = ", "; # plymeld = \"0.0.0-comment\"\n; plymeld = ")
        SourceBlock.read(commented, "a.gcode").version shouldBe "9.9.9-test"
        SourceBlock.read(out, "a.gcode").models.single().name shouldBe "part.stl"
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { SourceBlock.read(out.replace("; format = \"1\"", "; format = \"2\""), "b.gcode") }
            .message!! shouldContain "format 2, which this plymeld (format 1) does not know"
        io.kotest.assertions.throwables.shouldThrow<IllegalArgumentException> { SourceBlock.read(out.replace("; format = \"1\"\n", ""), "c.gcode") }
            .message!! shouldContain "has no format line"
    }

    "appended after the g-code, between its markers" {
        val out = SourceBlock.append("G28\nM84", "9.9.9-test", stack, listOf(model), plan, "m", emptySet())
        out.startsWith("G28\nM84\n\n${SourceBlock.START}\n") shouldBe true
        out.endsWith("${SourceBlock.END}\n") shouldBe true
    }
})
