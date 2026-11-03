package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * The files a job writes and opens with: the g-code named after the models open now, not the ones the window was
 * started with (else Send would upload another model under the first's name, or as `job.gcode`); a model opened alone
 * never brings the job before's plan file (its locks would apply to the new model, and its lines be written by it);
 * a config file that no longer exists is left out, not a refusal at every start; the window's own plan files kept in the
 * user's data folder, one per job, not the temp folder.
 */
class JobFilesTest : StringSpec({
    val d = Files.createTempDirectory("jobfiles")
    val hardware = d.resolve("hardware.toml").also { Files.writeString(it, "[hardware.machine.m]\ndescription = \"m\"\n") }
    val otherPlan = d.resolve("benchy.plan.toml").also { Files.writeString(it, "[plan.object.benchy]\npose.label = \"as given\"\n\n[plan.source_fingerprint.model]\nbenchy = \"0\"\n") }

    "the g-code is named after the models open now" {
        JobState.outFor(listOf("/a/bracket.stl")).fileName.toString() shouldBe "bracket.gcode"
        JobState.outFor(listOf("/a/x.y.3mf", "/b/z.stl")).fileName.toString() shouldBe "x.y_and_1_more.gcode"
        // control: no model is "job" (the empty window), never another model's name
        JobState.outFor(emptyList()).fileName.toString() shouldBe "job.gcode"
    }
    "a model opened alone keeps the config but not the job before's plan file" {
        val (files, notes) = JobState.configsFor(listOf(d.resolve("bracket.stl").toString()), listOf(hardware.toString(), otherPlan.toString()))
        files shouldBe listOf(hardware.toString())
        notes.joinToString() shouldContain "plan file left out: benchy.plan.toml"
        // control: the hardware file is a config file, not a plan file
        JobState.isPlanFile(hardware) shouldBe false
        JobState.isPlanFile(otherPlan) shouldBe true
    }
    "an added config file lands by its number, always before the plan file" {
        val f1 = d.resolve("01_filaments.toml").toString(); val f2 = d.resolve("02_c5.toml").toString(); val job = d.resolve("benchy_job.toml").toString()
        val plan = otherPlan.toString()
        JobState.withConfig(listOf(f1, job, plan), f2) shouldBe listOf(f1, f2, job, plan)
        // an unnumbered file goes after the others, still before the plan file (not appended after it)
        val extra = d.resolve("mine.toml").toString()
        JobState.withConfig(listOf(f1, f2, plan), extra) shouldBe listOf(f1, f2, extra, plan)
        // control: a lower number goes first
        JobState.withConfig(listOf(f2, plan), f1) shouldBe listOf(f1, f2, plan)
    }
    "a set value lands by what it is" {
        val mine = d.resolve("benchy_job.toml").also { Files.writeString(it, "[job.priorities]\nquality = \"5\"\n") }
        val plan = otherPlan
        val jobFiles = setOf(hardware, mine, plan).map { it.toAbsolutePath().normalize() }.toSet()
        // a hardware fact: the file defining the machine, not the user's last file
        JobState.target("hardware.machine.m.loadout.plate", "$hardware:2", plan, mine, hardware, jobFiles).file shouldBe hardware
        // a job preference: the job's own file
        JobState.target("job.priorities.speed", null, plan, mine, null, jobFiles).file shouldBe mine
        // a bundled value: overridden in the job's own file, the reason given
        val bundled = JobState.target("plan.layer.walls.thickness", "bundled 00_defaults.toml:41", plan, mine, null, jobFiles)
        bundled.file shouldBe mine
        bundled.why shouldContain "bundled"
        // control: a decision the plan file holds stays in the plan file
        JobState.target("plan.object.benchy.pose.label", "$plan:2", plan, mine, null, jobFiles).file shouldBe plan
    }
    "a config file that no longer exists is left out and said" {
        val gone = d.resolve("gone.toml").toString()
        val (files, notes) = JobState.configsFor(listOf("m.stl"), listOf(gone, hardware.toString()))
        files shouldBe listOf(hardware.toString())
        notes.joinToString() shouldContain "no longer exists"
    }
    "the model's own plan file, where the window kept one, comes back with it" {
        val model = d.resolve("own.stl").toString()
        val own = JobState.planFileFor(listOf(model))
        own.toString() shouldNotContain System.getProperty("java.io.tmpdir").trimEnd('/')
        Files.createDirectories(own.parent)
        Files.writeString(own, "[plan.source_fingerprint.model]\nown = \"0\"\n")
        try {
            JobState.configsFor(listOf(model), listOf(hardware.toString())).first shouldBe listOf(hardware.toString(), own.toString())
            // control: another model of the same name in another folder has a plan file of its own
            (JobState.planFileFor(listOf(d.resolve("sub").resolve("own.stl").toString())) == own) shouldBe false
        } finally { Files.deleteIfExists(own) }
    }
})
