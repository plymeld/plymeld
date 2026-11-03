package plymeld.ui

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/** The Velopack client's own parts (Updates.kt): the feed, the version order, the manifest, the install layout, the hooks. */
class UpdatesTest : StringSpec({
    val feed = """{"Assets":[
        {"PackageId":"plymeld","Version":"0.0.1-phase0","Type":"Full","FileName":"plymeld-0.0.1-phase0-full.nupkg","SHA1":"aa","Size":10},
        {"PackageId":"plymeld","Version":"0.0.2","Type":"Delta","FileName":"plymeld-0.0.2-delta.nupkg","SHA1":"bb","Size":2},
        {"PackageId":"plymeld","Version":"0.0.2","Type":"Full","FileName":"plymeld-0.0.2-full.nupkg","SHA1":"cc","Size":12},
        {"PackageId":"other","Version":"9.0.0","Type":"Full","FileName":"other-9.0.0-full.nupkg","SHA1":"dd","Size":1}]}"""
    fun tempDir(): Path = Files.createTempDirectory("plymeld-updates-test")
    fun Path.wipe() { Files.walk(this).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) } }

    "the feed's entries are read, and the newest full release of this package wins" {
        val releases = Updates.parseFeed(feed)
        releases.size shouldBe 4
        releases[1].type shouldBe "Delta"; releases[1].size shouldBe 2L
        Updates.newest(releases, "0.0.1-phase0", "plymeld").shouldNotBeNull().fileName shouldBe "plymeld-0.0.2-full.nupkg"
        Updates.newest(releases, "0.0.2", "plymeld").shouldBeNull()        // up to date
        Updates.newest(releases, "0.0.3", "plymeld").shouldBeNull()        // ahead of the feed
        Updates.newest(releases, "0.0.1", "nothing").shouldBeNull()       // another package's releases are not ours
    }

    "versions order as semver 2" {
        (Updates.compareVersions("0.0.2", "0.0.1-phase0") > 0) shouldBe true
        (Updates.compareVersions("0.0.1", "0.0.1-phase0") > 0) shouldBe true      // the release after its pre-release
        (Updates.compareVersions("0.0.1-phase1", "0.0.1-phase0") > 0) shouldBe true
        (Updates.compareVersions("1.0.0-rc.2", "1.0.0-rc.10") < 0) shouldBe true  // numeric pre-release parts
        (Updates.compareVersions("1.10.0", "1.9.9") > 0) shouldBe true
        Updates.compareVersions("1.0.0+build7", "1.0.0") shouldBe 0             // build metadata ignored
    }

    "date versions (0.YYYYMMDD.N) order by day, then by the day's release" {
        (Updates.compareVersions("0.20261101.0", "0.20261026.0") > 0) shouldBe true
        (Updates.compareVersions("0.20261026.10", "0.20261026.9") > 0) shouldBe true  // numbers, not text
        (Updates.compareVersions("0.20261027.0", "0.20261026.10") > 0) shouldBe true  // a later day beats a later N
        (Updates.compareVersions("0.20261026.0", "0.0.1-phase0") > 0) shouldBe true   // the old pre-release updates
        (Updates.compareVersions("0.20261026.0", "0.20261101.0") < 0) shouldBe true   // control: the order is not always "greater"
    }

    "the manifest's id, version and channel are read" {
        val m = Updates.parseManifest("""<?xml version="1.0"?><package><metadata><id>plymeld</id><version>0.0.1-phase0</version><title>plymeld</title><channel>win</channel></metadata></package>""")
        m.shouldNotBeNull(); m.id shouldBe "plymeld"; m.version shouldBe "0.0.1-phase0"; m.channel shouldBe "win"
        Updates.parseManifest("<package/>").shouldBeNull()
    }

    "a Windows install is found from the executable's place, a bare folder is not" {
        val dir = tempDir()
        try {
            val current = Files.createDirectories(dir.resolve("current"))
            val exe = Files.writeString(current.resolve("plymeld-ui.exe"), "")
            Updates.install(exe, emptyMap(), "Windows 11").shouldBeNull()                    // no manifest, no updater
            Files.writeString(current.resolve("sq.version"), "<package><metadata><id>plymeld</id><version>0.0.1</version></metadata></package>")
            Updates.install(exe, emptyMap(), "Windows 11").shouldBeNull()                    // still no Update.exe
            Files.writeString(dir.resolve("Update.exe"), "")
            val i = Updates.install(exe, emptyMap(), "Windows 11").shouldNotBeNull()
            i.id shouldBe "plymeld"; i.version shouldBe "0.0.1"; i.channel shouldBe "win"; i.updater shouldBe dir.resolve("Update.exe")
            i.packages shouldBe dir.resolve("packages"); i.portable shouldBe false
            Files.writeString(dir.resolve(".portable"), "")
            Updates.install(exe, emptyMap(), "Windows 11").shouldNotBeNull().portable shouldBe true
        } finally { dir.wipe() }
    }

    "a Linux install needs the AppImage layout and the APPIMAGE variable" {
        val dir = tempDir()
        try {
            val bin = Files.createDirectories(dir.resolve("usr/bin"))
            val exe = Files.writeString(bin.resolve("plymeld-ui"), "")
            Files.writeString(bin.resolve("sq.version"), "<package><metadata><id>plymeld</id><version>0.0.1</version><channel>linux</channel></metadata></package>")
            Files.writeString(bin.resolve("UpdateNix"), "")
            Updates.install(exe, emptyMap(), "Linux").shouldBeNull()                          // not run as an AppImage
            val i = Updates.install(exe, mapOf("APPIMAGE" to "/home/x/plymeld.AppImage"), "Linux").shouldNotBeNull()
            i.updater shouldBe bin.resolve("UpdateNix"); i.channel shouldBe "linux"; i.portable shouldBe true
        } finally { dir.wipe() }
    }

    "the hooks are the --veloapp arguments and nothing else" {
        Updates.isHook(arrayOf("--veloapp-install", "0.0.2")) shouldBe true
        Updates.isHook(arrayOf("--veloapp-uninstall", "0.0.2")) shouldBe true
        Updates.isHook(arrayOf("-cf", "x.toml")) shouldBe false
        Updates.isHook(emptyArray()) shouldBe false
    }

    "a downloaded file's SHA1 and SHA-256 are the standard ones" {
        val dir = tempDir()
        try {
            val f = Files.writeString(dir.resolve("a.txt"), "abc")
            Updates.sha1(f) shouldBe "a9993e364706816aba3e25717850c26c9cd0d89d"
            Updates.sha256(f) shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        } finally { dir.wipe() }
    }

    "a package is checked by the feed's SHA-256 where it gives one, else its SHA1, and never without either" {
        val dir = tempDir()
        try {
            val f = Files.writeString(dir.resolve("a.txt"), "abc")
            val sha1 = "a9993e364706816aba3e25717850c26c9cd0d89d"; val sha256 = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
            fun r(s1: String, s256: String) = Updates.Release("plymeld", "0.0.2", "Full", "a.txt", s1, 3, s256)
            Updates.matches(f, r(sha1, sha256)) shouldBe true
            Updates.matches(f, r("", sha256)) shouldBe true
            Updates.matches(f, r(sha1, "")) shouldBe true                       // an older feed: SHA1 only
            Updates.matches(f, r(sha1, "00" + sha256.drop(2))) shouldBe false   // control: the SHA-256 decides when given
            Updates.matches(f, r("", "")) shouldBe false                        // no checksum: never trusted
            val withSha256 = Updates.parseFeed("""{"Assets":[{"PackageId":"plymeld","Version":"0.0.2","Type":"Full","FileName":"a","SHA1":"aa","SHA256":"bb","Size":1}]}""")
            withSha256.single().sha256 shouldBe "bb"
            Updates.parseFeed(feed)[0].sha256 shouldBe ""
        } finally { dir.wipe() }
    }

    "the feed is read over https only, plain http on this machine alone" {
        Updates.feedAllowed("https://updates.example.org/plymeld") shouldBe true
        Updates.feedAllowed("http://localhost:8080/feed") shouldBe true
        Updates.feedAllowed("http://127.0.0.1/feed") shouldBe true
        Updates.feedAllowed("http://updates.example.org/plymeld") shouldBe false
        Updates.feedAllowed("file:///tmp/feed") shouldBe false
        Updates.feedAllowed("not a url") shouldBe false
    }
})
