package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import java.time.Duration

/**
 * The QIDI Box's slots over SSH: a synthetic
 * `saved_variables.cfg` shaped as observed on a Q2, parsed through a fake [SshTransport] into
 * slots with their codes (no material or color: the codes are QIDI's); the Moonraker adapter has LOADOUT only when a
 * shell is configured (control: without one, refused in one line); the factory names the shell from the config and
 * refuses a user without a credential; the real transport is refused until the library is wired in.
 */
class QidiBoxTest : StringSpec({
    val variables = QidiBoxTest::class.java.getResource("/moonraker/saved_variables.cfg")!!.readText()

    /** A shell whose files are [files]; records what was read. */
    class FakeSsh(private val files: Map<String, String>) : SshTransport {
        val reads = ArrayList<String>(); var closed = false
        override fun run(command: String): String = error("not run in this test: $command")
        override fun read(path: String): String { reads += path; return files[path] ?: error("no such file on the printer: $path") }
        override fun close() { closed = true }
    }

    "one Box (the observed shape): four slots, three loaded, their codes in the fields and the name, no material or color, the notes" {
        val l = QidiBoxVariables.parse(variables, "test")
        l.slots.map { Triple(it.position, it.loaded, it.feeds) } shouldContainExactly listOf(
            Triple(1, true, ReportedSlot.Feeds.SLOT), Triple(2, true, ReportedSlot.Feeds.SLOT), Triple(3, true, ReportedSlot.Feeds.SLOT), Triple(4, false, ReportedSlot.Feeds.SLOT))
        l.slots.all { it.material == null && it.color == null } shouldBe true
        l.slots[0].name shouldBe "Box 1 slot A (the printer's slot0); codes: vendor 1, color 1 (white, as observed), filament 41"
        l.slots[2].name shouldBe "Box 1 slot C (the printer's slot2); codes: vendor 3, color 11, filament 7"
        l.slots[1].fields shouldBe mapOf("slot1" to "1", "vendor_slot1" to "1", "color_slot1" to "2", "filament_slot1" to "41", "runout_slot1" to "0")
        l.notes shouldContainExactly listOf(
            "1 Box(es), 4 slots, numbered 1 to 4 across them (box_count = 1)",
            "the Box records each slot's vendor, color, and filament as QIDI's own small integer codes (color 1 = white and 2 = black were observed; the rest are unknown): no material or color is read from them, so matching cannot apply",
            "loaded into the nozzle now: slot 3, Box 1 slot C (last_load_slot = 'slot2')",
            "which slot each T<n> loads (value_t<n>): T0 -> slot0, T1 -> slot1, T2 -> slot2, T3 -> slot3")
        // controls: nothing loaded reads as such; an odd present value is noted, not guessed; no section = no slots
        QidiBoxVariables.parse(variables.replace("last_load_slot = 'slot2'", "last_load_slot = 'slot-1'"), "t").notes[2] shouldBe "nothing loaded into the nozzle now (last_load_slot = 'slot-1')"
        QidiBoxVariables.parse(variables.replace("slot3 = 0", "slot3 = maybe"), "t").let { it.slots[3].loaded shouldBe null; it.notes.first() shouldContain "slot3 = maybe is neither" }
        QidiBoxVariables.parse("[Other]\nslot0 = 1\n", "t").slots.size shouldBe 0
    }

    "no Box (synthetic, inferred from the macros' box_count guard): no slots, a note saying the config's loadout stands; the comparison lets it stand and warns nothing" {
        val l = QidiBoxVariables.parse(QidiBoxTest::class.java.getResource("/moonraker/saved_variables_no_box.cfg")!!.readText(), "test")
        l.slots.size shouldBe 0
        l.notes shouldContainExactly listOf("the printer reports no Box slots (box_count = 0): one spool fed straight into the tool; what is loaded is what the config's loadout says")
        val base = listOf("hardware.machine.q.model" to "qidi-q2", "hardware.machine.q.build.x" to "200 mm", "hardware.machine.q.build.y" to "200 mm", "hardware.machine.q.build.z" to "200 mm",
            "hardware.machine.q.firmware.flavor" to "klipper", "hardware.tool.t1.machine" to "q", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
            "hardware.machine.q.loadout.tool.t1.filament" to "pla-generic")
        val c = Config(ConfigStack(ConfigFiles.bundled() + base.map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
        val r = LoadoutSync.compare(c, l)
        r.rows.single().verdict shouldBe LoadoutSync.Verdict.NOT_REPORTED
        r.warnings().size shouldBe 0
        r.text() shouldContain "slot 1 (t1): printer no such position; config pla-generic (PLA no color) -> the printer reports no slots; the config's loadout stands"
        r.overlay() shouldContain "# slot 1: the printer reports no slots; the config's loadout stands (pla-generic)"
        // control: a printer that DOES report slots, but not this one, is a different verdict and a warning
        val other = LoadoutSync.compare(c, ReportedLoadout("test", listOf(ReportedSlot(2, ReportedSlot.Feeds.SLOT, true, "PLA", "#FFFFFF"))))
        other.rows.single { it.position == 1 }.verdict shouldBe LoadoutSync.Verdict.NOT_ON_PRINTER
        other.warnings().size shouldBe 2
    }

    "two Boxes (synthetic, from QIDI's box2.cfg template numbering): eight slots in one flat numbering, 5 to 8 in Box 2; the counts read, not assumed" {
        val l = QidiBoxVariables.parse(QidiBoxTest::class.java.getResource("/moonraker/saved_variables_two_boxes.cfg")!!.readText(), "test")
        l.slots.map { it.position to it.loaded } shouldContainExactly listOf(1 to true, 2 to false, 3 to true, 4 to true, 5 to true, 6 to true, 7 to false, 8 to false)
        l.slots[4].name shouldBe "Box 2 slot A (the printer's slot4); codes: vendor 2, color 1 (white, as observed), filament 41"
        l.slots[7].name shouldContain "Box 2 slot D (the printer's slot7)"
        l.notes[0] shouldBe "2 Box(es), 8 slots, numbered 1 to 8 across them (box_count = 2)"
        l.notes[2] shouldBe "loaded into the nozzle now: slot 6, Box 2 slot B (last_load_slot = 'slot5')"
        l.notes[3] shouldContain "T7 -> slot7"
        // controls: a box_count that disagrees with the entries is noted, and the entries win; a file without box_count counts from the entries;
        // a third Box's slots (slot8-11) land in slots 9-12 without any fixed count
        val text = QidiBoxTest::class.java.getResource("/moonraker/saved_variables_two_boxes.cfg")!!.readText()
        QidiBoxVariables.parse(text.replace("box_count = 2", "box_count = 1"), "t").notes[0] shouldBe "box_count = 1, but the slot entries span 2 Box(es) (slots 1 to 8): the slots read are the ones listed"
        QidiBoxVariables.parse(text.replace("box_count = 2\n", ""), "t").notes[0] shouldBe "2 Box(es) read from the slot entries (slots 1 to 8; the file has no box_count)"
        val three = QidiBoxVariables.parse(text.replace("box_count = 2", "box_count = 3") + "slot8 = 1\nslot9 = 0\nslot10 = 0\nslot11 = 0\n", "t")
        three.slots.size shouldBe 12
        three.slots.last().let { it.position shouldBe 12; it.name shouldContain "Box 3 slot D (the printer's slot11)" }
        three.notes[0] shouldBe "3 Box(es), 12 slots, numbered 1 to 12 across them (box_count = 3)"
        QidiBoxVariables.label(0) shouldBe "Box 1 slot A"; QidiBoxVariables.label(15) shouldBe "Box 4 slot D"
    }

    "the Moonraker adapter reads the loadout through the shell and closes it; control: no shell, LOADOUT refused in one line" {
        val ssh = FakeSsh(mapOf(SshSettings.QIDI_VARIABLES_FILE to variables))
        val p = MoonrakerHttp("q2", "printer.test", 7125, null, Duration.ofSeconds(8), Recorded { error("no HTTP in this test") }, ssh = { ssh })
        p.can(Capability.LOADOUT) shouldBe true
        val l = p.loadout()
        l.source shouldBe "moonraker over SSH: /home/mks/printer_data/config/saved_variables.cfg ([Variables] of Klipper's save_variables, the QIDI Box's slot codes)"
        l.slots.size shouldBe 4
        ssh.reads shouldContainExactly listOf(SshSettings.QIDI_VARIABLES_FILE); ssh.closed shouldBe true
        val none = MoonrakerHttp("q2", "printer.test", 7125, null, Duration.ofSeconds(8), Recorded { error("no HTTP in this test") })
        none.can(Capability.LOADOUT) shouldBe false
        shouldThrow<IllegalStateException> { none.loadout() }.message shouldBe
            "printer 'q2' cannot read its loadout: Moonraker reports none, and no SSH is configured to read the printer's save_variables file (set hardware.machine.q2.connection.ssh.user and a credential)"
    }

    "the comparison on Box codes says cannot compare for a loaded slot, naming what the printer gives no material or color for" {
        val base = listOf("hardware.machine.q.model" to "qidi-q2", "hardware.machine.q.build.x" to "200 mm", "hardware.machine.q.build.y" to "200 mm", "hardware.machine.q.build.z" to "200 mm",
            "hardware.machine.q.firmware.flavor" to "klipper", "hardware.tool.t1.machine" to "q", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
            "hardware.machine.q.loadout.slot.1.filament" to "pla-generic")
        val c = Config(ConfigStack(ConfigFiles.bundled() + base.map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
        val r = LoadoutSync.compare(c, QidiBoxVariables.parse(variables, "test"))
        r.rows.single { it.position == 1 }.verdict shouldBe LoadoutSync.Verdict.CANNOT_COMPARE
        r.rows.single { it.position == 1 }.why shouldBe "the printer gives no material or color"
        r.rows.single { it.position == 4 }.verdict shouldBe LoadoutSync.Verdict.BOTH_EMPTY
    }

    "the factory: a shell from connection.ssh.* and the credentials, the host and port defaulted; a user without a credential refused; the printer opened reads through the shell given" {
        fun config(vararg extra: Pair<String, Any>) = Config(ConfigStack(ConfigFiles.bundled() + (listOf(
            "hardware.machine.q.model" to "qidi-q2", "hardware.machine.q.build.x" to "200 mm", "hardware.machine.q.build.y" to "200 mm", "hardware.machine.q.build.z" to "200 mm",
            "hardware.machine.q.firmware.flavor" to "klipper", "hardware.machine.q.connection.api" to "moonraker", "hardware.machine.q.connection.host" to "q.test") + extra)
            .map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
        val env = mapOf("Q_PW" to "PW-TEST")::get
        val cn = Printers.connection(config("hardware.machine.q.connection.ssh.user" to "mks", "hardware.machine.q.connection.credential.ssh_password.env" to "Q_PW"), "q", env)
        val s = cn.ssh!!
        s.host shouldBe "q.test"; s.port shouldBe 22; s.user shouldBe "mks"; s.password shouldBe "PW-TEST"; s.keyFile shouldBe null
        s.variablesFile shouldBe SshSettings.QIDI_VARIABLES_FILE; s.acceptNew shouldBe false
        val own = Printers.connection(config("hardware.machine.q.connection.ssh.user" to "mks", "hardware.machine.q.connection.ssh.host" to "shell.test", "hardware.machine.q.connection.ssh.port" to "2222",
            "hardware.machine.q.connection.ssh.variables_file" to "/tmp/v.cfg", "hardware.machine.q.connection.ssh.accept_new" to "true", "hardware.machine.q.connection.credential.ssh_password.env" to "Q_PW"), "q", env).ssh!!
        own.host shouldBe "shell.test"; own.port shouldBe 2222; own.variablesFile shouldBe "/tmp/v.cfg"; own.acceptNew shouldBe true
        Printers.connection(config(), "q", env).ssh shouldBe null
        shouldThrow<IllegalStateException> { Printers.connection(config("hardware.machine.q.connection.ssh.user" to "mks"), "q", env) }.message shouldBe
            "printer 'q': hardware.machine.q.connection.ssh.user is set, but no credential unlocks the shell: name hardware.machine.q.connection.credential.ssh_password or hardware.machine.q.connection.credential.ssh_key_file (.env or .file)"
        // the printer opened from that config reads through the shell it is given (the real one, MinaSsh, is HostKeysTest's business offline)
        val p = Printers.open(cn, Recorded { error("no HTTP") }) { FakeSsh(mapOf(SshSettings.QIDI_VARIABLES_FILE to variables)) }
        p.loadout().slots.size shouldBe 4
    }
})
