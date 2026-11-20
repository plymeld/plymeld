package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * `plymeld migrate`: a config file with a renamed setting (its old name under its table, a comment above it and after it,
 * a keep mark, and a scoped one as a dotted key) rewritten in place: each under its new name and value, the comments and
 * every other line as they were; recorded in the journal, and `plymeld journal --undo` gives the file back byte for
 * byte. Controls: --dry-run writes nothing; a file with nothing to change is left alone.
 */
class MigrateCommandTest : StringSpec({
    val original = """
        |# my walls
        |[plan.layer.walls]
        |thickness = "1.2 mm"
        |# inner walls first, for overhangs
        |inner_first = "!true"   # kept
        |
        |[plan.object]
        |box.layer.walls.inner_first = "false"
        |""".trimMargin()

    fun run(vararg args: String): Pair<Int, String> {
        val out = StringBuilder()
        val code = Commands.invoke(args.toList(), { out.appendLine(it) })
        return code to out.toString()
    }

    "renamed in place, comments and the rest kept; journaled, and undone byte for byte" {
        val dir = Files.createTempDirectory("plymeld-migrate")
        val f = dir.resolve("walls.toml"); Files.writeString(f, original)
        val journal = dir.resolve("config.journal.json")
        val (code, out) = run("migrate", f.toString(), "--journal", journal.toString())
        println(out)
        code shouldBe 0
        val after = Files.readString(f)
        println(after)
        after shouldContain "# inner walls first, for overhangs\norder = \"!inner_first\"   # kept"
        after shouldContain "box.layer.walls.order = \"outer_first\""
        after shouldContain "# my walls\n[plan.layer.walls]\nthickness = \"1.2 mm\""
        after shouldNotContain "inner_first ="
        // it reads as plymeld reads the old file
        val read = plymeld.config.ConfigFiles.readToml(after, "after").associate { it.key to (it.value to it.keep) }
        read["plan.layer.walls.order"] shouldBe ("inner_first" to true)
        read["plan.object.box.layer.walls.order"] shouldBe ("outer_first" to false)
        run("journal", journal.toString(), "--undo").first shouldBe 0
        Files.readString(f) shouldBe original
    }

    "controls: --dry-run writes nothing; a file with nothing to change is left alone" {
        val dir = Files.createTempDirectory("plymeld-migrate")
        val f = dir.resolve("walls.toml"); Files.writeString(f, original)
        val journal = dir.resolve("config.journal.json")
        val (code, out) = run("migrate", f.toString(), "--dry-run", "--journal", journal.toString())
        code shouldBe 0
        out shouldContain "plan.layer.walls.inner_first -> plan.layer.walls.order = \"inner_first\""
        out shouldContain "--dry-run: nothing written"
        Files.readString(f) shouldBe original
        Files.exists(journal) shouldBe false
        val current = dir.resolve("current.toml"); Files.writeString(current, "[plan.layer.walls]\norder = \"inner_first\"\n")
        val (code2, out2) = run("migrate", current.toString(), "--journal", journal.toString())
        code2 shouldBe 0
        out2 shouldContain "nothing to change"
        Files.readString(current) shouldBe "[plan.layer.walls]\norder = \"inner_first\"\n"
        Files.exists(journal) shouldBe false
    }
})
