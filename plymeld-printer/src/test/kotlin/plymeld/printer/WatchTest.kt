package plymeld.printer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.time.Duration

/**
 * Watching: a Recorded transport answers a sequence of states; the first is
 * printed in full, then only the lines that changed, timestamped; the loop stops when the print ends (finished;
 * idle after printing) and not before (control: idle at the start does not end it; a print that never ends is capped
 * by the test's poll limit). The sleep between polls is the interval given.
 */
class WatchTest : StringSpec({
    val printing = WatchTest::class.java.getResource("/moonraker/objects-query-printing.json")!!.readText()
    val standby = WatchTest::class.java.getResource("/moonraker/objects-query-standby.json")!!.readText()
    val layer170 = printing.replace("\"current_layer\": 169", "\"current_layer\": 170")
    val complete = layer170.replace("\"state\": \"printing\"", "\"state\": \"complete\"")

    /** A printer whose state answers come from [sequence] in order (the last one repeats). */
    fun printer(sequence: List<String>): Pair<MoonrakerHttp, Recorded> {
        var i = 0
        val t = Recorded { _ -> Recorded.json(sequence[minOf(i++, sequence.size - 1)]) }
        return MoonrakerHttp("q2", "printer.test", 7125, null, Duration.ofSeconds(8), t) to t
    }
    fun watch(sequence: List<String>, maxPolls: Int = 50): Triple<List<String>, List<Duration>, Int> {
        val (p, t) = printer(sequence)
        val out = ArrayList<String>(); val slept = ArrayList<Duration>()
        Watch.run(p, Duration.ofMillis(250), { out += it }, stamp = { "12:00:00" }, sleep = { slept += it }, maxPolls = maxPolls)
        return Triple(out, slept, t.sent.size)
    }

    "the first state in full, then only the changed lines with a timestamp, until the print finishes" {
        val (out, slept, polls) = watch(listOf(printing, printing, layer170, complete))
        polls shouldBe 4
        slept shouldContainExactly listOf(Duration.ofMillis(250), Duration.ofMillis(250), Duration.ofMillis(250))
        out.first() shouldBe "printer: q2 (moonraker /printer/objects/query)"
        val full = out.indexOfFirst { it.startsWith("12:00:00") }
        full shouldBe 12                                                                   // the full state: its 12 lines, none timestamped
        out.drop(full) shouldContainExactly listOf(
            "12:00:00  layer: 170 of 484",
            "12:00:00  status: finished (the printer's word: \"complete\")",
            "12:00:00  the print has ended: finished")
        // control: the second poll, identical to the first, printed nothing
        out.count { it.startsWith("12:00:00") } shouldBe 3
    }

    "idle at the start does not end the watch; idle after printing does" {
        val (out, _, polls) = watch(listOf(standby, standby, printing, standby))
        polls shouldBe 4
        out.last() shouldBe "12:00:00  the print has ended: idle"
        out shouldContain "12:00:00  status: printing"
        // control: a print that never ends runs until the cap
        watch(listOf(printing), maxPolls = 7).third shouldBe 7
        Watch.ended(Status.IDLE, sawPrinting = false) shouldBe false
        Watch.ended(Status.CANCELLED, sawPrinting = false) shouldBe true
    }

    "a print already over ends the watch after one poll" {
        val (out, slept, polls) = watch(listOf(complete, printing))
        polls shouldBe 1; slept.size shouldBe 0
        out.last() shouldBe "12:00:00  the print has ended: finished"
    }
})
