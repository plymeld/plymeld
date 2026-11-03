package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.Schema

/**
 * The interface itself: a capability an adapter lacks is refused with
 * the one-line message naming the printer's key and the protocol (control: the capability it has runs); a figure not
 * reported reads "not reported" (control: a reported one reads its value); the schema's protocol list is the adapters'.
 */
class PrinterInterfaceTest : StringSpec({
    /** An adapter with one capability, for the default refusals. */
    class OneTrick(override val key: String) : Printer {
        override val protocol = Protocol.MOONRAKER
        override val capabilities = setOf(Capability.FILES)
        override fun files() = listOf(PrinterFile("only.gcode", Reported.NotReported("no size"), Reported.NotReported("no time")))
    }

    "a capability the adapter lacks is refused in one line naming the key and the protocol; control: the one it has runs" {
        val p = OneTrick("left-printer")
        p.can(Capability.STATE) shouldBe false
        shouldThrow<IllegalStateException> { p.state() }.message shouldBe "printer 'left-printer' cannot read its state: moonraker has no way plymeld knows"
        shouldThrow<IllegalStateException> { p.pause() }.message shouldBe "printer 'left-printer' cannot pause the print: moonraker has no way plymeld knows"
        shouldThrow<IllegalStateException> { p.rawCommand("G28") }.message shouldBe "printer 'left-printer' cannot send a raw command: moonraker has no way plymeld knows"
        p.can(Capability.FILES) shouldBe true
        p.files().map { it.name } shouldContainExactly listOf("only.gcode")
    }

    "the refusal names the printer by its key, never a model: two printers of one kind refuse as two" {
        val a = shouldThrow<IllegalStateException> { OneTrick("shop-a").cancel() }.message!!
        val b = shouldThrow<IllegalStateException> { OneTrick("shop-b").cancel() }.message!!
        a shouldContain "'shop-a'"; b shouldContain "'shop-b'"
        a shouldNotContain "shop-b"
    }

    "a figure not reported reads 'not reported' with why; control: a reported one reads its value" {
        val none: Reported<Double> = Reported.of(null, "no such field")
        none.text() shouldBe "not reported"
        (none as Reported.NotReported).why shouldBe "no such field"
        none.orNull shouldBe null
        val some: Reported<Double> = Reported.of(0.5, "no such field")
        some.text { "%.0f%%".format(java.util.Locale.ROOT, it * 100) } shouldBe "50%"
        some.orNull shouldBe 0.5
    }

    "the schema's connection.api choices are exactly the protocols the adapters speak" {
        val decl = Schema.decls.single { it.path == "hardware.machine.<key>.connection.api" }
        (decl.type as Schema.Type.Choice).options shouldContainExactly Protocol.ids
        // control: every protocol has a default port and words a help text can use
        Protocol.entries.forEach { it.defaultPort shouldBe it.defaultPort.coerceIn(1, 65535); it.words.isNotEmpty() shouldBe true }
    }

    "the typed state prints every field, 'not reported' where the printer gave none" {
        val s = PrinterState("k", "test", Status.PRINTING, "printing", Reported.Value("a.gcode"), Reported.Value(0.25), Reported.Value(3), Reported.NotReported("x"),
            Reported.Value(65.0), Reported.NotReported("x"), Reported.NotReported("x"), listOf(Heater("T1", Reported.Value(210.4), Reported.Value(210.0))),
            Reported.NotReported("x"), Reported.Value(1.0), Reported.NotReported("x"), null, listOf("a note"))
        s.text() shouldBe """
            printer: k (test)
            status: printing
            job: a.gcode
            progress: 25.0%
            layer: 3 of not reported
            elapsed: 1:05
            remaining: not reported
            active tool: not reported
            nozzles: T1 210/210 C
            bed: not reported
            speed: 100%
            flow: not reported
            note: a note

        """.trimIndent()
    }
})
