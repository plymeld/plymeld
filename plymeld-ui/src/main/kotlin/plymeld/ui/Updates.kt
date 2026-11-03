package plymeld.ui

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import plymeld.config.UserDirs
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration

/**
 * Updates of the window through Velopack. Velopack has no JVM
 * SDK, so this is plymeld's own client of the two things Velopack defines and documents: the release feed
 * (`releases.{channel}.json` beside the packages, read by every SDK) and the updater binary installed beside the app
 * (`Update.exe` on Windows, `UpdateNix` inside the AppImage), whose `apply --waitPid PID --package FILE` installs a
 * downloaded package once this process has exited and starts the new version. Nothing here depends on how the
 * package was built; `vpk pack` (tools/package-velopack.cmd) makes the installer, the portable zip, and the feed.
 *
 * The rules: the window only CHECKS by itself, and only when the viewer allows it ([Pref]: `ask`, the default, says
 * there is an update and waits for a click; `auto` downloads and installs at the next start; `never` does nothing);
 * the command line never updates itself; a build that is not a Velopack install ([install] null: a Gradle run, a
 * plain unpacked folder) has no updates at all. Deltas are not used yet: the full package is downloaded (the updater
 * can `patch` deltas; a later step).
 */
object Updates {
    /** A Velopack install this executable belongs to: its id, the installed version and channel, the updater. */
    class Install(val id: String, val version: String, val channel: String, val updater: Path, val packages: Path, val portable: Boolean)

    /** One entry of the feed; [sha256] empty when the feed has none (older Velopack feeds carry SHA1 only). */
    class Release(val packageId: String, val version: String, val type: String, val fileName: String, val sha1: String, val size: Long, val sha256: String = "")

    /** An update the feed offers: the release and where its package is. */
    class Available(val release: Release, val url: String)

    /**
     * Velopack's hooks: the installed app is run with `--veloapp-install|updated|obsolete|uninstall VERSION` around an
     * install, an update, and an uninstall, and must exit at once (15 to 30 s, or it is killed); plymeld only puts the
     * command line on the user's PATH or takes it off ([CliPath]). `VELOPACK_FIRSTRUN` and `VELOPACK_RESTART` are
     * environment variables of a normal start, not hooks.
     */
    fun isHook(args: Array<String>): Boolean = args.firstOrNull()?.startsWith("--veloapp-") == true

    /**
     * The install this executable is part of, found the way Velopack's own locator finds it (its `locator.rs`):
     * on Windows the app runs from `{root}\current\` with `Update.exe` in `{root}` and `sq.version` beside the
     * executable (`.portable` in the root marks a portable unpack, which the updater still serves); in an AppImage the
     * executable, `UpdateNix` and `sq.version` share `usr/bin/` and `$APPIMAGE` names the file. Null when the layout is
     * not there: a development run, a bare copy of the files.
     */
    fun install(exe: Path? = currentExe(), env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Install? {
        val here = exe?.toAbsolutePath()?.parent ?: return null
        val manifest = here.resolve("sq.version").takeIf { Files.isRegularFile(it) } ?: return null
        val m = parseManifest(Files.readString(manifest)) ?: return null
        return if (os.lowercase().contains("windows")) {
            val root = here.parent ?: return null
            val updater = root.resolve("Update.exe").takeIf { Files.isRegularFile(it) } ?: return null
            Install(m.id, m.version, m.channel ?: "win", updater, root.resolve("packages"), Files.exists(root.resolve(".portable")))
        } else {
            val updater = here.resolve("UpdateNix").takeIf { Files.isRegularFile(it) } ?: return null
            if (env["APPIMAGE"].isNullOrEmpty()) return null
            Install(m.id, m.version, m.channel ?: "linux", updater, Path.of("/var/tmp/velopack", m.id, "packages"), true)
        }
    }

    class Manifest(val id: String, val version: String, val channel: String?)

    /** `sq.version` is the package's nuspec: `<id>`, `<version>` and Velopack's `<channel>` under `<metadata>`. */
    fun parseManifest(xml: String): Manifest? {
        fun tag(name: String): String? = Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(xml)?.groupValues?.get(1)
        val id = tag("id") ?: return null
        val version = tag("version") ?: return null
        return Manifest(id, version, tag("channel")?.takeIf { it.isNotEmpty() })
    }

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
     * Whether the file at [p] is the package [r] names: its SHA-256 when the feed gives one, else its SHA1; a feed entry
     * with neither is never trusted.
     */
    fun matches(p: Path, r: Release): Boolean = when {
        r.sha256.isNotEmpty() -> sha256(p).equals(r.sha256, ignoreCase = true)
        r.sha1.isNotEmpty() -> sha1(p).equals(r.sha1, ignoreCase = true)
        else -> false
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
    fun newest(feed: List<Release>, installed: String, id: String): Release? =
        feed.filter { it.type.equals("Full", ignoreCase = true) && it.packageId == id && compareVersions(it.version, installed) > 0 }
            .maxWithOrNull { x, y -> compareVersions(x.version, y.version) }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** Reads `{feedUrl}/releases.{channel}.json` and says whether a newer full release is there. Throws on a network or feed error. */
    fun check(feedUrl: String, install: Install): Available? {
        if (!feedAllowed(feedUrl)) error("the update feed must be an https address: $feedUrl")
        val base = feedUrl.trimEnd('/')
        val res = http.send(HttpRequest.newBuilder(URI("$base/releases.${install.channel}.json")).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) error("the update feed answered ${res.statusCode()}")
        val r = newest(parseFeed(res.body()), install.version, install.id) ?: return null
        return Available(r, "$base/${r.fileName}")
    }

    /** Downloads the package into the install's packages directory, verifying its size and checksum ([matches]); [progress] gets (bytes so far, total). */
    fun download(a: Available, install: Install, progress: (Long, Long) -> Unit = { _, _ -> }): Path {
        if (a.release.sha256.isEmpty() && a.release.sha1.isEmpty()) error("the update feed gives no checksum for ${a.release.fileName}")
        Files.createDirectories(install.packages)
        val target = install.packages.resolve(a.release.fileName)
        if (Files.isRegularFile(target) && Files.size(target) == a.release.size && matches(target, a.release)) return target
        val partial = install.packages.resolve(a.release.fileName + ".partial")
        val res = http.send(HttpRequest.newBuilder(URI(a.url)).timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) error("the package download answered ${res.statusCode()}")
        res.body().use { input ->
            Files.newOutputStream(partial).use { out ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, a.release.size) }
            }
        }
        if (a.release.size > 0 && Files.size(partial) != a.release.size) { Files.deleteIfExists(partial); error("the package is ${Files.size(partial)} bytes, the feed says ${a.release.size}") }
        if (!matches(partial, a.release)) { Files.deleteIfExists(partial); error("the package's checksum does not match the feed's") }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Hands the package to the updater and returns the started process: `Update.exe apply --waitPid PID --package FILE`
     * waits for this process to exit, installs, and starts the new version (`--norestart` not given). The caller exits.
     */
    fun apply(install: Install, pkg: Path, restart: Boolean = true): Process {
        val cmd = mutableListOf(install.updater.toString(), "apply", "--waitPid", ProcessHandle.current().pid().toString(), "--package", pkg.toString())
        if (!restart) cmd.add("--norestart")
        return ProcessBuilder(cmd).directory(install.updater.parent.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
    }

    fun sha1(p: Path): String = digest(p, "SHA-1")
    fun sha256(p: Path): String = digest(p, "SHA-256")
    private fun digest(p: Path, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        Files.newInputStream(p).use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun currentExe(): Path? = ProcessHandle.current().info().command().orElse(null)?.let { Path.of(it) }

    /**
     * The viewer's choice, kept beside the theme: `window-updates.txt` in [UserDirs.config] with `check=ask|auto|never`
     * (`ask` when unset) and `feed=URL` (the feed baked into the build when unset: `plymeld-update-feed.txt` on the
     * class path, written by the release packaging; none in a development build, so no check runs).
     */
    object Pref {
        enum class Check { ASK, AUTO, NEVER }
        val file: Path get() = UserDirs.current().config.resolve("window-updates.txt")
        var check: Check = Check.ASK; private set
        var feed: String? = null; private set
        fun load() {
            check = Check.ASK; feed = bakedFeed()
            try {
                if (Files.exists(file)) for (line in Files.readAllLines(file).map { it.trim() }) when {
                    line.startsWith("check=") -> check = Check.entries.firstOrNull { it.name.equals(line.removePrefix("check="), ignoreCase = true) } ?: Check.ASK
                    line.startsWith("feed=") -> feed = line.removePrefix("feed=").trim().takeIf { it.isNotEmpty() }
                }
            } catch (_: Exception) { }
        }
        fun save(check: Check) {
            this.check = check
            try {
                Files.createDirectories(file.parent)
                Files.writeString(file, "# updates of the window through Velopack: check=ask (say so, wait for a click), auto (install at the next start) or never; feed=URL overrides the build's\ncheck=${check.name.lowercase()}\n" + (feed?.let { "feed=$it\n" } ?: ""))
            } catch (_: Exception) { }
        }
        private fun bakedFeed(): String? = Updates::class.java.getResourceAsStream("/plymeld-update-feed.txt")?.bufferedReader()?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }
}
