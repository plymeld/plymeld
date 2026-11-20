package plymeld.commands

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.zip.GZIPOutputStream

/** A gzipped ustar archive of [entries] (name to bytes, executable when the name ends in `*`), as `tar -czf` writes one. */
private fun tarGz(entries: List<Pair<String, ByteArray>>): ByteArray {
    val raw = ByteArrayOutputStream()
    fun header(name: String, size: Long, type: Char, mode: Int) {
        val h = ByteArray(512)
        fun put(off: Int, s: String) { val b = s.toByteArray(); System.arraycopy(b, 0, h, off, b.size) }
        put(0, name); put(100, "%07o".format(mode)); put(108, "0000000"); put(116, "0000000")
        put(124, "%011o".format(size)); put(136, "00000000000"); h[156] = type.code.toByte(); put(257, "ustar"); put(263, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        put(148, "%06o".format(h.sumOf { it.toInt() and 0xff }) + "\u0000 ")
        raw.write(h)
    }
    header("plymeld/", 0, '5', "755".toInt(8))
    for ((n, data) in entries) {
        val exec = n.endsWith("*"); val name = "plymeld/" + n.removeSuffix("*")
        header(name, data.size.toLong(), '0', if (exec) "755".toInt(8) else "644".toInt(8))
        raw.write(data); raw.write(ByteArray(((512 - data.size % 512) % 512)))
    }
    raw.write(ByteArray(1024))
    val gz = ByteArrayOutputStream(); GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
    return gz.toByteArray()
}

class InstallsTest : StringSpec({
    val linux = System.getProperty("os.name").lowercase().contains("linux")

    "the tar unpacks under its top folder, the executable bit kept; control: a path leaving the folder is refused" {
        val dir = Files.createTempDirectory("plymeld-tar")
        val tar = dir.resolve("p.tar.gz")
        Files.write(tar, tarGz(listOf("plymeld*" to "#!/bin/sh\necho plymeld version 0.20991231.0\n".toByteArray(), "libjava.so" to ByteArray(700) { it.toByte() })))
        val into = dir.resolve("staged")
        val files = Installs.Tar.unpack(tar, into)
        files.map { into.relativize(it).toString() } shouldContainExactlyInAnyOrder listOf("plymeld", "libjava.so")
        Files.readAllBytes(into.resolve("libjava.so")).size shouldBe 700
        if (linux) {
            // the archive's own permissions exactly, whatever the process's umask (002 left them group-writable)
            Files.getPosixFilePermissions(into.resolve("plymeld")) shouldBe java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x")
            Files.getPosixFilePermissions(into.resolve("libjava.so")) shouldBe java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--")
            Installs.Tar.versionOf(into.resolve("plymeld")) shouldBe "0.20991231.0"
        }
        Files.write(tar, tarGz(listOf("../evil" to "x".toByteArray())))
        shouldThrow<IllegalStateException> { Installs.Tar.unpack(tar, dir.resolve("staged2")) }.message!! shouldContain "outside its folder"
        dir.toFile().deleteRecursively()
    }

    "the staged files replace the installed ones in place; control: a file the tar does not hold is left" {
        val dir = Files.createTempDirectory("plymeld-replace")
        val installed = Files.createDirectories(dir.resolve("opt"))
        Files.writeString(installed.resolve("plymeld"), "old"); Files.writeString(installed.resolve("mine.txt"), "kept")
        val staged = Files.createDirectories(dir.resolve("opt/.plymeld-update-1/plymeld"))
        val files = listOf(Files.writeString(staged.resolve("plymeld"), "new"), Files.writeString(staged.resolve("libjava.so"), "lib"))
        Installs.Tar.replace(staged, files, installed)
        Files.readString(installed.resolve("plymeld")) shouldBe "new"
        Files.readString(installed.resolve("libjava.so")) shouldBe "lib"
        Files.readString(installed.resolve("mine.txt")) shouldBe "kept"
        dir.toFile().deleteRecursively()
    }

    "the tar install updated whole from a served release; controls: a wrong checksum or a wrong version changes nothing".config(enabled = linux) {
        val served = HashMap<String, ByteArray>()
        val server = com.sun.net.httpserver.HttpServer.create(java.net.InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { ex ->
            val body = served[ex.requestURI.path.removePrefix("/")]
            if (body == null) { ex.sendResponseHeaders(404, -1) } else { ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
            ex.close()
        }
        server.start()
        val base = "http://127.0.0.1:${server.address.port}/releases"
        val name = "plymeld-linux-x64.tar.gz"
        fun publish(version: String, sum: String? = null) {
            val tar = tarGz(listOf("plymeld*" to "#!/bin/sh\necho plymeld version $version\n".toByteArray(), "libjava.so" to "new lib".toByteArray()))
            served["releases/$name"] = tar
            val f = Files.createTempFile("t", ".gz"); Files.write(f, tar)
            served["releases/$name.sha256"] = "${sum ?: Installs.sha256(f)}  $name\n".toByteArray()
        }
        val dir = Files.createTempDirectory("plymeld-tar-install")
        try {
            val folder = Files.createDirectories(dir.resolve("plymeld"))
            Files.writeString(folder.resolve("plymeld"), "old executable"); Files.writeString(folder.resolve("libjava.so"), "old lib")
            fun unchanged() { Files.readString(folder.resolve("plymeld")) shouldBe "old executable"; Files.readString(folder.resolve("libjava.so")) shouldBe "old lib" }
            val said = ArrayList<String>()

            publish("0.20991231.0", sum = "00".repeat(32))
            shouldThrow<IllegalStateException> { Installs.Tar.update(folder, "0.20991231.0", base, name) { said += it } }.message!! shouldContain "checksum does not match"
            unchanged()
            publish("0.20991230.0")
            shouldThrow<IllegalStateException> { Installs.Tar.update(folder, "0.20991231.0", base, name) { said += it } }.message!! shouldContain "is plymeld 0.20991230.0, not 0.20991231.0"
            unchanged()
            Files.list(folder).use { s -> s.map { it.fileName.toString() }.toList() } shouldContainExactlyInAnyOrder listOf("plymeld", "libjava.so")   // no work folder left

            publish("0.20991231.0")
            Installs.Tar.update(folder, "0.20991231.0", base, name) { said += it }
            Installs.Tar.versionOf(folder.resolve("plymeld")) shouldBe "0.20991231.0"
            Files.readString(folder.resolve("libjava.so")) shouldBe "new lib"
            said.last() shouldContain "installed in $folder"
            // each check said as it passes
            said.any { it.startsWith("checksum matches the published one") } shouldBe true
            said.any { it == "the new executable says it is plymeld 0.20991231.0" } shouldBe true
        } finally { server.stop(0); dir.toFile().deleteRecursively() }
    }

    "the published checksum is read as sha256sum writes it; control: anything else is refused" {
        val h = "ab".repeat(32)
        Installs.Tar.parseSha256("$h  plymeld-linux-x64.tar.gz\n") shouldBe h
        shouldThrow<IllegalStateException> { Installs.Tar.parseSha256("<html>not found</html>") }
    }

    "the tar's name by architecture; the tar install only for a native plymeld on Linux" {
        Installs.Tar.name("amd64") shouldBe "plymeld-linux-x64.tar.gz"
        Installs.Tar.name("aarch64") shouldBe "plymeld-linux-arm64.tar.gz"
        Installs.Tar.name("ppc64le") shouldBe null
        Installs.Tar.folder(Path.of("/opt/plymeld/plymeld"), "Linux", native = true) shouldBe Path.of("/opt/plymeld")
        Installs.Tar.folder(Path.of("/usr/lib/jvm/bin/java"), "Linux", native = true) shouldBe null        // a JVM run
        Installs.Tar.folder(Path.of("/opt/plymeld/plymeld"), "Linux", native = false) shouldBe null
        Installs.Tar.folder(Path.of("C:/plymeld/plymeld"), "Windows 11", native = true) shouldBe null
    }

    "other processes running from an install are found and stopped; control: one running from elsewhere is not found".config(enabled = linux) {
        val dir = Files.createTempDirectory("plymeld-running")
        val install = Files.createDirectories(dir.resolve("install"))
        val sleep = Files.copy(Path.of("/bin/sleep"), install.resolve("sleep"))
        val inside = ProcessBuilder(sleep.toString(), "300").start()
        val outside = ProcessBuilder("/bin/sleep", "300").start()
        try {
            val found = Installs.running(install)
            found.map { it.handle.pid() } shouldBe listOf(inside.pid())
            found.single().describe() shouldContain "sleep 300"
            Installs.running(dir.resolve("other")) shouldBe emptyList()
            Installs.stop(found) shouldBe emptyList()
            inside.isAlive shouldBe false
            outside.isAlive shouldBe true
        } finally { inside.destroyForcibly(); outside.destroyForcibly(); dir.toFile().deleteRecursively() }
    }
})
