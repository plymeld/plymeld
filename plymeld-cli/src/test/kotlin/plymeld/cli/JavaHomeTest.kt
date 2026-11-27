package plymeld.cli

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * The command line's java.home holds a `lib/` folder however it was installed: the executable's folder where its `lib/`
 * is or can be made, else a folder in plymeld's cache. Controls: an install without `lib/` (writable, read-only, a file
 * in the way) is the case the rule exists for; java.home left as the executable's folder there would fail each.
 */
class JavaHomeTest : StringSpec({
    fun install(): Path = Files.createTempDirectory("plymeld-java-home-").also { Files.writeString(it.resolve("plymeld"), "") }
    fun cache(): Path = Files.createTempDirectory("plymeld-cache-").resolve("java-home")

    "an install with its lib/ is java.home, and nothing is made elsewhere" {
        val exe = install(); Files.createDirectory(exe.resolve("lib"))
        val fallback = cache()
        JavaHome.choose(exe, fallback) shouldBe exe
        Files.exists(fallback) shouldBe false
    }

    "an install without lib/ gets one, and stays java.home" {
        val exe = install()
        val fallback = cache()
        JavaHome.choose(exe, fallback) shouldBe exe
        Files.isDirectory(exe.resolve("lib")) shouldBe true
        Files.exists(fallback) shouldBe false
    }

    "an install without lib/ that cannot be written: java.home is the cache's folder, its lib/ made there" {
        val exe = install()
        val fallback = cache()
        JavaHome.choose(exe, fallback) { it != exe.resolve("lib") && JavaHome.makeFolder(it) } shouldBe fallback
        Files.isDirectory(fallback.resolve("lib")) shouldBe true
        Files.exists(exe.resolve("lib")) shouldBe false
        // the next run finds the cache's lib/ there and makes nothing
        JavaHome.choose(exe, fallback) { false } shouldBe fallback
    }

    "a read-only install folder, as the file system has it" {
        val exe = install()
        val fallback = cache()
        // where the file system has no POSIX permissions (Windows) or they do not hold this user (root), the case above,
        // with the folder refused, stands for it
        val posix = try { Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("r-xr-xr-x")); true } catch (_: UnsupportedOperationException) { false }
        try {
            if (posix && !Files.isWritable(exe)) {
                JavaHome.choose(exe, fallback) shouldBe fallback
                Files.isDirectory(fallback.resolve("lib")) shouldBe true
                Files.exists(exe.resolve("lib")) shouldBe false
            }
        } finally {
            if (posix) Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    "a file named lib in the way: the cache's folder" {
        val exe = install(); Files.writeString(exe.resolve("lib"), "")
        val fallback = cache()
        JavaHome.choose(exe, fallback) shouldBe fallback
        Files.isDirectory(fallback.resolve("lib")) shouldBe true
    }

    "nothing can be made anywhere: the executable's folder, as it is" {
        val exe = install()
        val fallback = cache()
        JavaHome.choose(exe, fallback) { false } shouldBe exe
    }

    "a JVM's own java.home is left alone" {
        val was = System.getProperty("java.home")
        JavaHome.set()
        System.getProperty("java.home") shouldBe was
    }
})
