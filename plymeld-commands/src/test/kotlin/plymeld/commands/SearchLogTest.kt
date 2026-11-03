package plymeld.commands

import com.github.ajalt.clikt.core.parse
import com.github.ajalt.clikt.testing.test
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * The joint search on the product path: every decision appends one line to the search log where
 * `method.search.log` says; `--explain` shows the stop and the rounds; a flat
 * tops' surface technique the job does not lock is weighed and, when ironing wins, the slice irons and the plan file locks the lock.
 */
class SearchLogTest : StringSpec({
    val q2 = """
        [hardware.machine.m]
        model = "qidi-q2"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M109 S{first_layer_nozzle_temperature}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
    """.trimIndent()
    /** An ASCII STL box [x] x [y] x [z] mm, corner at the origin (12 outward-facing triangles). */
    fun box(x: Double, y: Double, z: Double): String {
        val v = listOf(doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(x, 0.0, 0.0), doubleArrayOf(x, y, 0.0), doubleArrayOf(0.0, y, 0.0),
            doubleArrayOf(0.0, 0.0, z), doubleArrayOf(x, 0.0, z), doubleArrayOf(x, y, z), doubleArrayOf(0.0, y, z))
        val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
            listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
        return buildString {
            append("solid box\n")
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
            append("endsolid box\n")
        }
    }
    val d: Path = Files.createTempDirectory("plymeld-search-log")
    Files.writeString(d.resolve("q2.toml"), q2)
    Files.writeString(d.resolve("box.stl"), box(20.0, 20.0, 10.0))
    Files.writeString(d.resolve("looks.toml"), "[job.priorities]\nquality = \"10\"\nspeed = \"1\"\n")
    Files.writeString(d.resolve("fast.toml"), "[job.priorities]\nquality = \"1\"\nspeed = \"10\"\n")
    Files.writeString(d.resolve("laid.toml"), "[plan.faces]\nflat_top.technique = \"as_laid\"\n")
    fun p(n: String) = d.resolve(n).toString()
    fun plan(vararg cfs: String, more: List<String> = emptyList()): String =
        PlanCommand().test(cfs.flatMap { listOf("-cf", p(it)) } + listOf("-m", p("box.stl")) + more).let { it.stderr + it.stdout }
    fun explained(json: String, vararg cfs: String, more: List<String> = emptyList()) = Json.parseToJsonElement(
        PlanCommand().parse(cfs.flatMap { listOf("-cf", p(it)) } + listOf("-m", p("box.stl"), "--explain-json", p(json)) + more)
            .let { Files.readString(d.resolve(json)) }).jsonObject.getValue("objects").jsonArray.single().jsonObject

    "every decision appends one line to the search log: date, model, stop reason, time, rounds; control: switched off, no line" {
        val log = d.resolve("logs/search.log")
        plan("q2.toml", more = listOf("-cv", "method.search.log.file=$log"))
        plan("q2.toml", more = listOf("-cv", "method.search.log.file=$log"))
        val lines = Files.readAllLines(log)
        lines.size shouldBe 2
        val f = lines[0].split('\t')
        f.size shouldBe 5
        f[0].matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")) shouldBe true
        f[1] shouldBe "box.stl"
        // quality and speed trading at equal weights: the bound cannot prove the winner safe, and two rounds in a row
        // leave it as it was (method.search.settle_rounds)
        f[2] shouldBe "settled"
        f[3].endsWith(" s") shouldBe true
        f[4].endsWith(" rounds") shouldBe true
        // control: with the log switched off, nothing is written
        val off = d.resolve("logs/off.log")
        plan("q2.toml", more = listOf("-cv", "method.search.log.file=$off", "-cv", "method.search.log.enabled=false"))
        Files.exists(off) shouldBe false
    }

    "--explain records the stop (reason, time, rounds, the winner's score per round) and the finalists' full prediction" {
        val e = explained("stop.json", "q2.toml", more = listOf("-cv", "method.search.log.enabled=false"))
        val stop = e.getValue("decision").jsonObject.getValue("stop").jsonObject
        stop.getValue("reason").jsonPrimitive.content shouldBe "settled"
        val rounds = stop.getValue("rounds").jsonPrimitive.content.toInt()
        (rounds > 1) shouldBe true
        stop.getValue("winner_score_by_round").jsonArray.size shouldBe rounds
        val fin = e.getValue("decision").jsonObject.getValue("finalists").jsonObject
        fin.getValue("decided").jsonPrimitive.content shouldBe "true"
        fin.getValue("scored").jsonArray.size shouldBe 3
        fin.getValue("scored").jsonArray[0].jsonObject.getValue("parts").jsonArray.map { it.jsonObject.getValue("measure").jsonPrimitive.content }.contains("time.prediction") shouldBe true
    }

    "the flat tops' surface technique weighed on the product path: quality-heavy weights iron the box's top (the slice irons, the plan file locks the lock), speed-heavy leave it as laid; control: a lock locks it, not weighed" {
        val looks = explained("looks.json", "q2.toml", "looks.toml", more = listOf("-cv", "method.search.log.enabled=false", "-o", p("looks_plan.toml")))
        val ironing = looks.getValue("surface_techniques").jsonArray.map { it.jsonObject }.single { it.getValue("technique").jsonPrimitive.content == "ironing" }
        ironing.getValue("fate").jsonPrimitive.content shouldBe "chosen"
        ironing.getValue("lock_source").jsonPrimitive.content shouldContain "weighed (no lock)"
        looks.getValue("surfaces").jsonArray.map { it.jsonObject }.single { it.getValue("faces").jsonPrimitive.content == "flat_top" }.getValue("surface_technique").jsonPrimitive.content shouldBe "ironing"
        Files.readString(d.resolve("looks_plan.toml")) shouldContain "faces.flat_top.technique = \"ironing\""
        // the slice prints what was decided
        val out = d.resolve("looks.gcode")
        SliceCommand().parse(listOf("-cf", p("q2.toml"), "-cf", p("looks.toml"), "-cv", "method.search.log.enabled=false", "-m", p("box.stl"), "-o", out.toString()))
        Files.readString(out) shouldContain "; FEATURE: Ironing"
        // speed-heavy: as laid wins, the slice does not iron
        val fast = explained("fast.json", "q2.toml", "fast.toml", more = listOf("-cv", "method.search.log.enabled=false", "-o", p("fast_plan.toml")))
        fast.getValue("surfaces").jsonArray.map { it.jsonObject }.single { it.getValue("faces").jsonPrimitive.content == "flat_top" }.getValue("surface_technique").jsonPrimitive.content shouldBe "none"
        Files.readString(d.resolve("fast_plan.toml")) shouldContain "faces.flat_top.technique = \"as_laid\""
        val fastOut = d.resolve("fast.gcode")
        SliceCommand().parse(listOf("-cf", p("q2.toml"), "-cf", p("fast.toml"), "-cv", "method.search.log.enabled=false", "-m", p("box.stl"), "-o", fastOut.toString()))
        Files.readString(fastOut).contains("; FEATURE: Ironing") shouldBe false
        // control: a lock (as laid) locks the surface technique under the same quality-heavy weights: not weighed, not ironed
        val locked = explained("laid.json", "q2.toml", "looks.toml", "laid.toml", more = listOf("-cv", "method.search.log.enabled=false", "-o", p("laid_plan.toml")))
        val laid = locked.getValue("surface_techniques").jsonArray.map { it.jsonObject }.single { it.getValue("technique").jsonPrimitive.content == "ironing" }
        laid.getValue("fate").jsonPrimitive.content shouldBe "rejected"
        laid.getValue("lock_source").jsonPrimitive.content shouldContain "locked off"
        Files.readString(d.resolve("laid_plan.toml")).contains("faces.flat_top.technique") shouldBe false
        // and the plan file written for the weighed case reads back as a lock: the plan is complete, nothing weighed again
        val again = SliceCommand().test(listOf("-cf", p("q2.toml"), "-cf", p("looks.toml"), "-cf", p("looks_plan.toml"), "-cv", "method.search.log.enabled=false",
            "-m", p("box.stl"), "-o", p("again.gcode"))).let { it.stderr + it.stdout }
        again shouldContain "complete in the config"
        again.contains("changed since this plan was made") shouldBe false
        Files.readString(d.resolve("again.gcode")) shouldContain "; FEATURE: Ironing"
    }
})
