package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.UserDirs
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.time.Duration
import java.time.Instant

/**
 * The update check shared by the command line and the window: off until the user turns it on; turned on, a run asks the
 * feed at most once a day in the background and says a newer release once a day, only in a terminal. Controls: the same
 * runs with the check off, out of a terminal, or with nothing newer say nothing and ask nothing.
 */
class UpdateFeedTest : StringSpec({
    fun dirs(): UserDirs { val r = Files.createTempDirectory("plymeld-updates"); return UserDirs(r.resolve("config"), r.resolve("data"), r.resolve("state"), r.resolve("cache")) }
    val feed = "https://updates.example.org/plymeld"
    fun release(v: String) = UpdateFeed.Release("plymeld", v, "Full", "plymeld-$v-full.nupkg", "aa", 1)

    /** One run: what it fetched and what it said. */
    class Run(val fetches: Int, val said: String)
    fun run(d: UserDirs, at: Instant, interactive: Boolean = true, offered: String = "0.20261109.0", installed: String = "0.20261108.0", baked: String? = feed): Run {
        var fetches = 0
        val r = UpdateFeed.forRun(installed, interactive, d, baked, "linux", { at }) { f, c -> fetches++; f shouldBe feed; c shouldBe "linux"; listOf(release(offered)) }
        r.awaitCheck(5_000)
        val out = ByteArrayOutputStream()
        r.finish(PrintStream(out, true))
        return Run(fetches, out.toString())
    }
    val t0 = Instant.parse("2026-11-08T12:00:00Z")

    "off by default: no file, nothing asked or said; control: turned on, the same run asks and says the newer release" {
        val d = dirs()
        UpdateFeed.Pref.load(d, feed).mode shouldBe UpdateFeed.Pref.Mode.OFF
        run(d, t0).let { it.fetches shouldBe 0; it.said shouldBe "" }
        UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.NOTIFY, d)
        run(d, t0).let { it.fetches shouldBe 1; it.said shouldContain "plymeld 0.20261109.0 is out (this is 0.20261108.0)" }
    }

    "turned on: the feed asked and the release said at most once a day; control: a day later both again" {
        val d = dirs(); UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.NOTIFY, d)
        run(d, t0).let { it.fetches shouldBe 1; it.said shouldContain "0.20261109.0" }
        run(d, t0.plus(Duration.ofHours(3))).let { it.fetches shouldBe 0; it.said shouldBe "" }
        run(d, t0.plus(Duration.ofHours(25))).let { it.fetches shouldBe 1; it.said shouldContain "0.20261109.0" }
    }

    "nothing said out of a terminal, without a feed, or when nothing is newer (control: the newer case above says it)" {
        val d = dirs(); UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.INSTALL, d)
        run(d, t0, interactive = false).let { it.fetches shouldBe 0; it.said shouldBe "" }
        run(dirs().also { UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.NOTIFY, it) }, t0, baked = null).let { it.fetches shouldBe 0; it.said shouldBe "" }
        run(d, t0, offered = "0.20261108.0").let { it.fetches shouldBe 1; it.said shouldBe "" }
    }

    "a failed check says nothing and the command goes on; control: the next due run asks again" {
        val d = dirs(); UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.NOTIFY, d)
        val r = UpdateFeed.forRun("0.20261108.0", true, d, feed, "linux", { t0 }) { _, _ -> error("no network") }
        r.awaitCheck(5_000)
        val out = ByteArrayOutputStream(); r.finish(PrintStream(out, true))
        out.toString() shouldBe ""
        run(d, t0).fetches shouldBe 1
    }

    "the choice: updates.txt, else the window's earlier window-updates.txt (ask = notify, auto = install, never = off); its feed= kept on save" {
        val d = dirs(); Files.createDirectories(d.config)
        Files.writeString(d.config.resolve("window-updates.txt"), "check=ask\nfeed=https://mirror.example.org/f\n")
        UpdateFeed.Pref.load(d, feed).let { it.mode shouldBe UpdateFeed.Pref.Mode.NOTIFY; it.feed shouldBe "https://mirror.example.org/f" }
        Files.writeString(d.config.resolve("window-updates.txt"), "check=auto\n")
        UpdateFeed.Pref.load(d, feed).mode shouldBe UpdateFeed.Pref.Mode.INSTALL
        Files.writeString(d.config.resolve("updates.txt"), "check=off\nfeed=https://own.example.org/f\n")
        UpdateFeed.Pref.load(d, feed).mode shouldBe UpdateFeed.Pref.Mode.OFF                  // updates.txt wins
        UpdateFeed.Pref.save(UpdateFeed.Pref.Mode.NOTIFY, d)
        UpdateFeed.Pref.load(d, feed).let { it.mode shouldBe UpdateFeed.Pref.Mode.NOTIFY; it.feed shouldBe "https://own.example.org/f" }
        Files.writeString(d.config.resolve("updates.txt"), "check=sometimes\n")
        UpdateFeed.Pref.load(d, feed).let { it.mode shouldBe UpdateFeed.Pref.Mode.OFF; it.feed shouldBe feed }   // an unknown word is off
    }

    "the channel by system: win and linux; none where no release is published" {
        UpdateFeed.channel("Windows 11") shouldBe "win"
        UpdateFeed.channel("Linux") shouldBe "linux"
        UpdateFeed.channel("Mac OS X") shouldBe null
    }
})
