package plymeld.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files

/** The journal of edits: recorded by `edit --journal`, listed, undone and redone by `journal`, refused when a file moved on. */
class JournalTest : StringSpec({
    fun run(vararg args: String): Pair<Int, List<String>> { val out = ArrayList<String>(); val s = Commands.invoke(args.toList(), { out += it }); return s to out }
    val dir = Files.createTempDirectory("plymeld-journal")
    val plan = dir.resolve("plan.toml").also { Files.writeString(it, "[plan.object.boat]\nband.1.heights = \"follow\"\n") }
    val journal = Journal.beside(plan)

    "an edit with --journal is recorded with the file's text before and after; the journal lists it applied" {
        run("edit", "--journal", journal.toString(), plan.toString(), "plan.object.boat.band.1.heights=one").first shouldBe 0
        val j = Journal.read(journal)
        j.entries.size shouldBe 1; j.done shouldBe 1
        j.entries[0].before shouldBe "[plan.object.boat]\nband.1.heights = \"follow\"\n"
        j.entries[0].after shouldBe "[plan.object.boat]\nband.1.heights = \"one\"\n"
        j.entries[0].command shouldContain "plymeld edit"
        val (_, out) = run("journal", journal.toString())
        out[0] shouldContain "1. applied"
        out.last() shouldContain "1 of 1 applied; --undo takes back #1"
    }
    "undo writes the file back and the entry stays, undone; redo writes it forward again" {
        run("journal", journal.toString(), "--undo").first shouldBe 0
        Files.readString(plan) shouldBe "[plan.object.boat]\nband.1.heights = \"follow\"\n"
        Journal.read(journal).done shouldBe 0
        run("journal", journal.toString()).second[0] shouldContain "1. undone"
        run("journal", journal.toString(), "--redo").first shouldBe 0
        Files.readString(plan) shouldBe "[plan.object.boat]\nband.1.heights = \"one\"\n"
        Journal.read(journal).done shouldBe 1
    }
    "a new edit after an undo drops what was undone" {
        run("journal", journal.toString(), "--undo").first shouldBe 0
        run("edit", "--journal", journal.toString(), plan.toString(), "plan.object.boat.band.1.layer.height=0.12 mm").first shouldBe 0
        val j = Journal.read(journal)
        j.entries.size shouldBe 1; j.done shouldBe 1
        j.entries[0].command shouldContain "layer.height"
        j.canRedo shouldBe false
    }
    "control: undo is refused when the file is not as the edit left it, and nothing is written" {
        Files.writeString(plan, "[plan.object.boat]\nsomething = \"else\"\n")
        val (status, out) = run("journal", journal.toString(), "--undo")
        status shouldBe 1
        out.last() shouldContain "not as the edit left it"
        Files.readString(plan) shouldBe "[plan.object.boat]\nsomething = \"else\"\n"
        Journal.read(journal).done shouldBe 1
    }
    "control: nothing to undo or redo is a one-line refusal; an edit that changes nothing records nothing" {
        val j2 = dir.resolve("other.journal.json")
        run("journal", j2.toString(), "--undo").second.last() shouldContain "nothing to undo"
        run("journal", j2.toString(), "--redo").second.last() shouldContain "nothing to redo"
        val same = dir.resolve("same.toml").also { Files.writeString(it, "[plan]\nobjects.printing = \"together\"\n") }
        run("edit", "--journal", j2.toString(), same.toString(), "plan.objects.printing=together").second shouldContain "$same:2: plan.objects.printing = \"together\"  (was \"together\")"
        Files.exists(j2) shouldBe false
        run("journal", j2.toString()).second shouldContain "$j2: no edits"
    }
    "control: --undo and --redo together is a usage error" {
        run("journal", journal.toString(), "--undo", "--redo").first shouldBe 1
        shouldThrow<IllegalStateException> { Journal(dir.resolve("none.json")).undo() }
    }
})
