package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import java.nio.file.Files
import java.nio.file.Path

/**
 * Credential sources: a secrets file's `name = "value"`
 * line is read (controls: a missing line is null, a malformed line and a missing file are refused naming the file);
 * when both the environment and the file are named, the environment wins (control: with the variable unset, the
 * file's value); `~/` expands to the home given; the factory reads a credential from a file alone, and refuses naming
 * every source tried when none has it, never the value.
 */
class CredentialsTest : StringSpec({
    val d: Path = Files.createTempDirectory("plymeld-secrets")
    val secrets = d.resolve("secrets.toml").also { Files.writeString(it, "# the printers' credentials\nserial_number = \"SN-FILE\"\ncheck_code = 'CODE-FILE'   # single quotes work too\n\napi_key = \"\"\n") }

    "a secrets file's line is read; a missing line is null; an empty value counts as none" {
        FileCredential(secrets, "serial_number").value() shouldBe "SN-FILE"
        FileCredential(secrets, "check_code").value() shouldBe "CODE-FILE"
        FileCredential(secrets, "nothing").value() shouldBe null
        FileCredential(secrets, "api_key").value() shouldBe null
        FileCredential(secrets, "check_code").words shouldBe "file $secrets (its 'check_code' line)"
    }

    "a malformed line and a missing file are refused naming the file, never a value" {
        val bad = d.resolve("bad.toml").also { Files.writeString(it, "serial_number = \"SN-FILE\"\ncheck_code = CODE-BARE\n") }
        val e = shouldThrow<IllegalStateException> { FileCredential(bad, "check_code").value() }
        e.message shouldBe "credential 'check_code': line 2 of the secrets file $bad is not `name = \"value\"`"
        e.message!! shouldNotContain "SN-FILE"
        shouldThrow<IllegalStateException> { FileCredential(d.resolve("none.toml"), "check_code").value() }.message!! shouldContain "does not exist"
    }

    "the environment wins over the file when both have a value; control: with the variable unset, the file's value" {
        val env = EnvironmentCredential("X", mapOf("X" to "SN-ENV")::get)
        Credentials.resolve(listOf(env, FileCredential(secrets, "serial_number"))) shouldBe "SN-ENV"
        Credentials.resolve(listOf(EnvironmentCredential("X", emptyMap<String, String>()::get), FileCredential(secrets, "serial_number"))) shouldBe "SN-FILE"
        Credentials.resolve(listOf(EnvironmentCredential("X", mapOf("X" to "")::get), FileCredential(secrets, "nothing"))) shouldBe null
    }

    "write replaces a credential's line or adds one, every other line kept; a new file is created owner-only; what a line cannot hold is refused, nothing written" {
        val f = d.resolve("write.toml").also { Files.writeString(it, "# mine\nserial_number = \"SN-1\"   # from the screen\n\ncheck_code = 'old'\n") }
        FileCredential(f, "check_code").write("new\"one") shouldBe true                    // a double quote: written in single quotes
        FileCredential(f, "check_code").value() shouldBe "new\"one"
        FileCredential(f, "api_key").write("K") shouldBe false
        Files.readString(f) shouldBe "# mine\nserial_number = \"SN-1\"   # from the screen\n\ncheck_code = 'new\"one'\napi_key = \"K\"\n"
        // refused, the file as it was: empty, a line break, both quotes, and a file with a malformed line
        val kept = Files.readString(f)
        shouldThrow<IllegalArgumentException> { FileCredential(f, "check_code").write("") }.message shouldBe "credential 'check_code': the value is empty"
        shouldThrow<IllegalArgumentException> { FileCredential(f, "check_code").write("a\nb") }
        shouldThrow<IllegalArgumentException> { FileCredential(f, "check_code").write("a\"b'c") }
        Files.readString(f) shouldBe kept
        val bad = d.resolve("bad-write.toml").also { Files.writeString(it, "check_code = BARE\n") }
        shouldThrow<IllegalStateException> { FileCredential(bad, "check_code").write("x") }.message!! shouldContain "nothing written"
        Files.readString(bad) shouldBe "check_code = BARE\n"
        // a file not there yet: created, with its folder, readable by its owner only (where the file system has POSIX permissions)
        val fresh = d.resolve("new-folder").resolve("printer.toml")
        FileCredential(fresh, "check_code").write("C") shouldBe false
        Files.readString(fresh) shouldBe "check_code = \"C\"\n"
        if (fresh.fileSystem.supportedFileAttributeViews().contains("posix"))
            java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(fresh)) shouldBe "rw-------"
    }

    "~ and ~/ expand to the home directory; other paths are left alone" {
        FileCredential.expandHome("~/.plymeld/secrets.toml", "/home/someone") shouldBe Path.of("/home/someone/.plymeld/secrets.toml")
        FileCredential.expandHome("~", "/home/someone") shouldBe Path.of("/home/someone")
        FileCredential.expandHome("/etc/x", "/home/someone") shouldBe Path.of("/etc/x")
        FileCredential.expandHome("~user/x", "/home/someone") shouldBe Path.of("~user/x")
    }

    "the factory reads a credential from a file alone, and names every source tried when none has it" {
        fun config(vararg extra: Pair<String, Any>) = Config(ConfigStack(ConfigFiles.bundled() + (listOf(
            "hardware.machine.c.model" to "qidi-q2", "hardware.machine.c.build.x" to "200 mm", "hardware.machine.c.build.y" to "200 mm", "hardware.machine.c.build.z" to "200 mm",
            "hardware.machine.c.firmware.flavor" to "klipper", "hardware.machine.c.connection.api" to "flashforge-http", "hardware.machine.c.connection.host" to "c.test") + extra)
            .map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
        val fromFile = Printers.connection(config("hardware.machine.c.connection.credential.serial_number.file" to secrets.toString(),
            "hardware.machine.c.connection.credential.check_code.file" to secrets.toString()), "c", emptyMap<String, String>()::get)
        fromFile.credentials shouldBe mapOf("serial_number" to "SN-FILE", "check_code" to "CODE-FILE")
        // both named: the environment's value wins for the one it has, the file's for the other
        val both = Printers.connection(config("hardware.machine.c.connection.credential.serial_number.env" to "C_SN", "hardware.machine.c.connection.credential.serial_number.file" to secrets.toString(),
            "hardware.machine.c.connection.credential.check_code.env" to "C_CODE_UNSET", "hardware.machine.c.connection.credential.check_code.file" to secrets.toString()), "c", mapOf("C_SN" to "SN-ENV")::get)
        both.credentials shouldBe mapOf("serial_number" to "SN-ENV", "check_code" to "CODE-FILE")
        // none has it (the variable unset, a file without the line): every source tried is named
        val partial = d.resolve("partial.toml").also { Files.writeString(it, "check_code = \"CODE-FILE\"\n") }
        val none = shouldThrow<IllegalStateException> { Printers.connection(config("hardware.machine.c.connection.credential.serial_number.env" to "C_SN_UNSET",
            "hardware.machine.c.connection.credential.serial_number.file" to partial.toString(), "hardware.machine.c.connection.credential.check_code.file" to partial.toString()), "c", emptyMap<String, String>()::get) }
        none.message shouldBe "printer 'c': credential 'serial_number' comes from environment variable C_SN_UNSET, then file $partial (its 'serial_number' line), and none has it"
    }
})
