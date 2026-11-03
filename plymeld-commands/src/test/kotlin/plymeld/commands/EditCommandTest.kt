package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/** `plymeld edit` end to end on a temporary file: what it writes, what it prints, what it refuses. */
class EditCommandTest : StringSpec({
    fun file(text: String) = Files.createTempFile("plymeld-edit", ".toml").also { Files.writeString(it, text) }
    fun run(vararg args: String): Pair<Int, List<String>> { val out = ArrayList<String>(); val s = Commands.invoke(listOf("edit") + args, { out += it }); return s to out }

    "writes the values, prints each line with what it held before, keeps the rest" {
        val f = file("[plan]\nobjects.printing = \"together\"   # all\n\n[plan.object.boat]\nband.1.heights = \"follow\"\n")
        val (status, out) = run(f.toString(), "plan.object.boat.band.1.heights=one", "plan.object.boat.band.1.layer.height=0.12 mm")
        status shouldBe 0
        out shouldContain "$f:5: plan.object.boat.band.1.heights = \"one\"  (was \"follow\")"
        out shouldContain "$f:6: plan.object.boat.band.1.layer.height = \"0.12 mm\"  (added)"
        Files.readString(f) shouldBe "[plan]\nobjects.printing = \"together\"   # all\n\n[plan.object.boat]\nband.1.heights = \"one\"\nband.1.layer.height = \"0.12 mm\"\n"
    }
    "--unset and --unset-under remove lines and say which; nothing to do is a usage error" {
        val f = file("[plan.object.boat]\n# the pose\npose.turn = \"1 0 0 0 1 0 0 0 1\"\nband.1.technique = \"flat\"\nband.1.heights = \"follow\"\nlayer.infill.angle = \"45 deg\"\n")
        val (status, out) = run(f.toString(), "--unset", "plan.object.boat.pose.turn", "--unset-under", "plan.object.boat.band")
        status shouldBe 0
        out shouldContain "$f: plan.object.boat.pose.turn removed"
        out shouldContain "$f: plan.object.boat.band.1.heights removed"
        Files.readString(f) shouldBe "[plan.object.boat]\nlayer.infill.angle = \"45 deg\"\n"
        run(f.toString()).first shouldBe 1
    }
    "--keep writes the value marked" {
        val f = file("[plan.object.boat]\npose.turn = \"1 0 0 0 1 0 0 0 1\"\n")
        run(f.toString(), "--keep", "plan.object.boat.pose.turn=1 0 0 0 1 0 0 0 1").first shouldBe 0
        Files.readString(f) shouldContain "pose.turn = \"!1 0 0 0 1 0 0 0 1\""
    }
    "a path the schema does not declare is refused and nothing is written" {
        val f = file("[plan]\nobjects.printing = \"together\"\n")
        val (status, out) = run(f.toString(), "plan.no.such.thing=1")
        status shouldBe 1
        out.last() shouldContain "not a path the schema declares"
        Files.readString(f) shouldBe "[plan]\nobjects.printing = \"together\"\n"
    }
    "a .json config is refused: edit writes TOML only" {
        val f = Files.createTempFile("plymeld-edit", ".json").also { Files.writeString(it, "{}") }
        val (status, out) = run(f.toString(), "plan.objects.printing=together")
        status shouldBe 1
        out.joinToString("\n") shouldContain "TOML files only"
    }
    "control: a change that would not read back is refused and the file left as it was" {
        // `plan` is already a value, so a [plan] table appended for plan.objects.printing makes the file invalid TOML
        val f = file("plan = \"a value\"\n")
        val (status, out) = run(f.toString(), "plan.objects.printing=together")
        status shouldBe 1
        out.last() shouldContain "would leave"
        Files.readString(f) shouldBe "plan = \"a value\"\n"
    }
})
