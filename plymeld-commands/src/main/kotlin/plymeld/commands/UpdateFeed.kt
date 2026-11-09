package plymeld.commands

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plymeld.config.UserDirs
import java.io.PrintStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.time.Instant

/**
 * Whether a newer plymeld release is out, for the command line and the window alike. Both read the same release feed
 * (Velopack's `releases.{channel}.json`, published with every release) and the same choice, [Pref]: nothing is checked
 * until the user turns it on (`plymeld updates --set notify|install`, or the window's settings). Then the window checks
 * as it opens, and the command line as it runs any command: at most once per [CHECK_EVERY] over the network, in the
 * background (a command never waits for it), and a newer release is said in one line on stderr after the command,
 * again at most once per [CHECK_EVERY], only in a terminal (never into a pipe or a script's output). The command line
 * never updates itself; the window can install an update ([Pref.Mode.INSTALL] at its next start, or on a click).
 */
object UpdateFeed {
    /** One entry of the feed; [sha256] empty when the feed has none (older Velopack feeds carry SHA1 only). */
    class Release(val packageId: String, val version: String, val type: String, val fileName: String, val sha1: String, val size: Long, val sha256: String = "")

    /** The package id every plymeld release is published under (Velopack's `--packId`). */
    const val PACKAGE_ID = "plymeld"

    /**
     * How often a run may ask the feed, and say a newer release again: once a day. plymeld's choice, not measured: a
     * release comes at most a few times a day, and a check per run would ask the server on every command in a script.
     */
    val CHECK_EVERY: Duration = Duration.ofHours(24)

    /** How long the command line's background check may take before it is left for the next run. */
    private val CLI_TIMEOUT: Duration = Duration.ofSeconds(10)

    /** The feed's entries (`releases.{channel}.json`: `{"Assets": [{PackageId, Version, Type, FileName, SHA1, SHA256, Size}]}`). */
    fun parseFeed(json: String): List<Release> {
        val root = Json.parseToJsonElement(json).jsonObject
        val assets = root["Assets"]?.jsonArray ?: return emptyList()
        return assets.map { a ->
            val o = a.jsonObject
            fun s(k: String) = o[k]?.jsonPrimitive?.content ?: ""
            Release(s("PackageId"), s("Version"), s("Type"), s("FileName"), s("SHA1"), o["Size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L, s("SHA256"))
        }
    }

    /** A feed is read over https only; plain http is allowed for this machine alone (a feed served locally for testing). */
    fun feedAllowed(url: String): Boolean {
        val u = try { URI(url.trim()) } catch (_: Exception) { return false }
        return when (u.scheme?.lowercase()) {
            "https" -> true
            "http" -> u.host in setOf("localhost", "127.0.0.1", "::1", "[::1]")
            else -> false
        }
    }

    /**
     * Semantic version order (semver 2: numeric parts, then a pre-release tag before the release itself, its dot
     * parts compared numerically where both are numbers; build metadata after `+` ignored). Positive when [a] is newer.
     */
    fun compareVersions(a: String, b: String): Int {
        fun split(v: String): Pair<List<Int>, List<String>> {
            val core = v.substringBefore('+'); val main = core.substringBefore('-'); val pre = core.substringAfter('-', "")
            return main.split('.').map { it.toIntOrNull() ?: 0 } to (if (pre.isEmpty()) emptyList() else pre.split('.'))
        }
        val (na, pa) = split(a); val (nb, pb) = split(b)
        for (i in 0 until maxOf(na.size, nb.size)) { val c = (na.getOrNull(i) ?: 0).compareTo(nb.getOrNull(i) ?: 0); if (c != 0) return c }
        if (pa.isEmpty() != pb.isEmpty()) return if (pa.isEmpty()) 1 else -1
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: return -1; val y = pb.getOrNull(i) ?: return 1
            val xi = x.toIntOrNull(); val yi = y.toIntOrNull()
            val c = if (xi != null && yi != null) xi.compareTo(yi) else if (xi != null) -1 else if (yi != null) 1 else x.compareTo(y)
            if (c != 0) return c
        }
        return 0
    }

    /** The newest full release in [feed] newer than [installed], or null. */
    fun newest(feed: List<Release>, installed: String, id: String = PACKAGE_ID): Release? =
        feed.filter { it.type.equals("Full", ignoreCase = true) && it.packageId == id && compareVersions(it.version, installed) > 0 }
            .maxWithOrNull { x, y -> compareVersions(x.version, y.version) }

    /** The feed's channel for this system (the release publishes `win` and `linux`), or null where there is none. */
    fun channel(os: String = System.getProperty("os.name")): String? = os.lowercase().let {
        when { it.contains("windows") -> "win"; it.contains("linux") -> "linux"; else -> null }
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** Reads `{feedUrl}/releases.{channel}.json`. Throws on a network or feed error. */
    fun fetch(feedUrl: String, channel: String, timeout: Duration = Duration.ofSeconds(20)): List<Release> {
        if (!feedAllowed(feedUrl)) error("the update feed must be an https address: $feedUrl")
        val res = http.send(HttpRequest.newBuilder(URI("${feedUrl.trimEnd('/')}/releases.$channel.json")).timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) error("the update feed answered ${res.statusCode()}")
        return parseFeed(res.body())
    }

    /** The feed this build was made with (`-Pplymeld.feed`, or `PLYMELD_FEED` when Gradle ran), or null: a development build has none. */
    fun bakedFeed(): String? = UpdateFeed::class.java.getResourceAsStream("/plymeld-update-feed.txt")?.bufferedReader()?.readText()?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * The user's choice, for the command line and the window: `updates.txt` in [UserDirs.config], `check=off|notify|install`
     * (`off` when unset: nothing is checked until the user turns it on) and `feed=URL` (the build's feed, [bakedFeed],
     * when unset). A `window-updates.txt` the window wrote before (its `ask`, `auto` and `never`) is read when there is
     * no `updates.txt`.
     */
    class Pref(val mode: Mode, val feed: String?, val file: Path) {
        enum class Mode(val word: String, val says: String) {
            OFF("off", "nothing is checked"),
            NOTIFY("notify", "a newer release is said: by the command line after a command, in the window's bar (installed on a click)"),
            INSTALL("install", "as notify, and the window installs it at its next start"),
        }

        companion object {
            fun file(dirs: UserDirs = UserDirs.current()): Path = dirs.config.resolve("updates.txt")
            private fun earlier(dirs: UserDirs): Path = dirs.config.resolve("window-updates.txt")

            fun load(dirs: UserDirs = UserDirs.current(), baked: String? = bakedFeed()): Pref {
                val f = file(dirs)
                var mode = Mode.OFF; var feed = baked
                val read = when { Files.isRegularFile(f) -> f; Files.isRegularFile(earlier(dirs)) -> earlier(dirs); else -> null }
                try {
                    if (read != null) for (line in Files.readAllLines(read).map { it.trim() }) when {
                        line.startsWith("check=") -> mode = when (line.removePrefix("check=").trim().lowercase()) {
                            "notify", "ask" -> Mode.NOTIFY; "install", "auto" -> Mode.INSTALL; else -> Mode.OFF
                        }
                        line.startsWith("feed=") -> line.removePrefix("feed=").trim().takeIf { it.isNotEmpty() }?.let { feed = it }
                    }
                } catch (_: Exception) { }
                return Pref(mode, feed, f)
            }

            /** Writes the choice, keeping a `feed=` line the user set. */
            fun save(mode: Mode, dirs: UserDirs = UserDirs.current()) {
                val own = load(dirs, baked = null).feed
                val f = file(dirs)
                Files.createDirectories(f.parent)
                Files.writeString(f, "# whether plymeld checks for a newer release: check=off (never), notify (say so) or install (the window installs it too); feed=URL overrides the build's\n" +
                    "check=${mode.word}\n" + (own?.let { "feed=$it\n" } ?: ""))
            }
        }
    }

    /** What the last check found: when it ran, the newest version then, and when a run last said so. Kept in [UserDirs.state]. */
    class Seen(val checked: Instant?, val newest: String?, val told: Instant?) {
        companion object {
            fun file(dirs: UserDirs = UserDirs.current()): Path = dirs.state.resolve("update-check.txt")
            fun load(dirs: UserDirs = UserDirs.current()): Seen = try {
                val m = Files.readAllLines(file(dirs)).mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
                Seen(m["checked"]?.let(::instant), m["newest"]?.takeIf { it.isNotEmpty() }, m["told"]?.let(::instant))
            } catch (_: Exception) { Seen(null, null, null) }
            private fun instant(s: String): Instant? = try { Instant.parse(s) } catch (_: Exception) { null }
        }
        /** Written whole through a temporary file, so a run that is stopped halfway leaves the last good one. */
        fun save(dirs: UserDirs = UserDirs.current()) {
            val f = file(dirs)
            Files.createDirectories(f.parent)
            val tmp = f.resolveSibling(f.fileName.toString() + ".tmp")
            Files.writeString(tmp, "checked=${checked ?: ""}\nnewest=${newest ?: ""}\ntold=${told ?: ""}\n")
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /**
     * The command line's check around one run: [start] before the command (the network asked in the background when the
     * last check is older than [CHECK_EVERY]), [finish] after it (one line on [out] when a newer release is known and none
     * was said within [CHECK_EVERY]). Nothing at all when the user has not turned checks on, the build has no feed, or
     * the output is not a terminal ([interactive]).
     */
    class ForRun internal constructor(private val installed: String, private val dirs: UserDirs, private val worker: Thread?, private val now: () -> Instant) {
        /** Says a newer release, when one is known and not said lately. Never throws. */
        fun finish(out: PrintStream) {
            try {
                val seen = Seen.load(dirs)
                val newer = seen.newest?.takeIf { compareVersions(it, installed) > 0 } ?: return
                val t = now()
                if (seen.told != null && Duration.between(seen.told, t) < CHECK_EVERY) return
                out.println("plymeld $newer is out (this is $installed): https://github.com/plymeld/plymeld/releases · `plymeld updates --set off` stops this notice")
                Seen(seen.checked, seen.newest, t).save(dirs)
            } catch (_: Exception) { }
        }
        /** For tests: waits for the background check, if one was started. */
        internal fun awaitCheck(ms: Long) { worker?.join(ms) }
    }

    /** The no-op run: nothing checked, nothing said. */
    private val NONE = ForRun("", UserDirs(Path.of(""), Path.of(""), Path.of(""), Path.of("")), null) { Instant.EPOCH }

    fun forRun(installed: String = PlymeldVersion.value, interactive: Boolean = System.console() != null, dirs: UserDirs = UserDirs.current(),
               baked: String? = bakedFeed(), channel: String? = channel(), now: () -> Instant = Instant::now,
               fetcher: (String, String) -> List<Release> = { f, c -> fetch(f, c, CLI_TIMEOUT) }): ForRun {
        return try {
            if (!interactive) return NONE
            val pref = Pref.load(dirs, baked)
            if (pref.mode == Pref.Mode.OFF) return NONE
            val feed = pref.feed ?: return NONE
            val ch = channel ?: return NONE
            val seen = Seen.load(dirs)
            val due = seen.checked == null || Duration.between(seen.checked, now()) >= CHECK_EVERY
            val worker = if (!due) null else Thread {
                try {
                    val n = newest(fetcher(feed, ch), installed)?.version
                    val latest = Seen.load(dirs)
                    Seen(now(), n, latest.told).save(dirs)
                } catch (_: Exception) { }
            }.apply { isDaemon = true; name = "update check" }.also { it.start() }
            ForRun(installed, dirs, worker, now)
        } catch (_: Exception) { NONE }
    }
}
