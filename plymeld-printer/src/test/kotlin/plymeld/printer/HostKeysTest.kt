package plymeld.printer

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.apache.sshd.common.config.keys.PublicKeyEntry
import java.nio.file.Files
import java.security.KeyPairGenerator

/**
 * The host-key decision without a network: a known_hosts file with a key for
 * one host is KNOWN for it, UNKNOWN for another host or another port, CHANGED for the host with another key; MINA's
 * verifier over it accepts only KNOWN unless accept_new, which records the key (control: without accept_new nothing is
 * written); the refusals name the printer by its key, the host, and the file, never the key. The live connection is
 * untested, as everything else against a printer.
 */
class HostKeysTest : StringSpec({
    val gen = KeyPairGenerator.getInstance("RSA").also { it.initialize(2048) }
    val keyA = gen.generateKeyPair().public
    val keyB = gen.generateKeyPair().public
    val d = Files.createTempDirectory("plymeld-known-hosts")
    val known = d.resolve("known_hosts").also { Files.writeString(it, "# a comment\nprinter-a ${PublicKeyEntry.toString(keyA)}\n[printer-b]:2222 ${PublicKeyEntry.toString(keyB)}\n") }

    "known for the host and port with that key; unknown for another host or port; changed for the host with another key" {
        HostKeys.decide(known, "printer-a", 22, keyA) shouldBe HostKeys.Decision.KNOWN
        HostKeys.decide(known, "printer-b", 2222, keyB) shouldBe HostKeys.Decision.KNOWN
        HostKeys.decide(known, "printer-c", 22, keyA) shouldBe HostKeys.Decision.UNKNOWN
        HostKeys.decide(known, "printer-b", 22, keyB) shouldBe HostKeys.Decision.UNKNOWN
        HostKeys.decide(known, "printer-a", 22, keyB) shouldBe HostKeys.Decision.CHANGED
        HostKeys.decide(d.resolve("none"), "printer-a", 22, keyA) shouldBe HostKeys.Decision.UNKNOWN
    }

    "the verifier accepts a known key, refuses an unknown one unless accept_new (which records it), and refuses a changed one always" {
        HostKeys.Verifier(known, acceptNew = false, "printer-a", 22).verifyServerKey(null, null, keyA) shouldBe true
        val strict = HostKeys.Verifier(known, acceptNew = false, "printer-c", 22)
        strict.verifyServerKey(null, null, keyA) shouldBe false
        strict.decision shouldBe HostKeys.Decision.UNKNOWN
        Files.readString(known) shouldNotContain "printer-c"                               // control: nothing recorded
        val trusting = HostKeys.Verifier(known, acceptNew = true, "printer-c", 2200)
        trusting.verifyServerKey(null, null, keyA) shouldBe true
        Files.readString(known) shouldContain "[printer-c]:2200 ${PublicKeyEntry.toString(keyA)}"
        HostKeys.decide(known, "printer-c", 2200, keyA) shouldBe HostKeys.Decision.KNOWN   // recorded as ssh would read it
        val changed = HostKeys.Verifier(known, acceptNew = true, "printer-a", 22)
        changed.verifyServerKey(null, null, keyB) shouldBe false
        changed.decision shouldBe HostKeys.Decision.CHANGED
    }

    "the refusals name the printer's key, the host, the port and the file; never the key" {
        val unknown = HostKeys.refusal("q2", "printer-c", 22, known, HostKeys.Decision.UNKNOWN)
        unknown shouldBe "printer 'q2': the SSH host key of printer-c:22 is not in $known: connect once with ssh to record it, or set hardware.machine.q2.connection.ssh.accept_new = \"true\" to trust it on first use (a risk: SECURITY.md)"
        val changed = HostKeys.refusal("q2", "printer-a", 22, known, HostKeys.Decision.CHANGED)
        changed shouldContain "is not the one recorded in $known: not connecting"
        changed shouldNotContain PublicKeyEntry.toString(keyA).substringAfter(' ')
    }
})
