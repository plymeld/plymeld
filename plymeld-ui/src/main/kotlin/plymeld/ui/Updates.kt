package plymeld.ui

import plymeld.commands.UpdateFeed
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
 * (`releases.{channel}.json` beside the packages, read by every SDK; read through [UpdateFeed], which the command line
 * shares) and the updater binary installed beside the app (`Update.exe` on Windows, `UpdateNix` inside the AppImage),
 * whose `apply --waitPid PID --package FILE` installs a downloaded package once this process has exited and starts the
 * new version. Nothing here depends on how the package was built; `vpk pack` (tools/package-velopack.cmd) makes the
 * installer, the portable zip, and the feed.
 *
 * The rules: nothing is checked until the user turns it on ([UpdateFeed.Pref], the same choice as `plymeld updates
 * --set`: `notify` says there is an update and waits for a click; `install` downloads and installs at the next start;
 * `off`, the default, does nothing); the window checks as it opens; the command line never updates itself; a build that
 * is not a Velopack install ([install] null: a Gradle run, a plain unpacked folder) is never updated. Deltas are not
 * used yet: the full package is downloaded (the updater can `patch` deltas; a later step).
 */
object Updates {
    /** A Velopack install this executable belongs to: its id, the installed version and channel, the updater. */
    class Install(val id: String, val version: String, val channel: String, val updater: Path, val packages: Path, val portable: Boolean)

    /** An update the feed offers: the release and where its package is. */
    class Available(val release: UpdateFeed.Release, val url: String)

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

    /**
     * Whether the file at [p] is the package [r] names: its SHA-256 when the feed gives one, else its SHA1; a feed entry
     * with neither is never trusted.
     */
    fun matches(p: Path, r: UpdateFeed.Release): Boolean = when {
        r.sha256.isNotEmpty() -> sha256(p).equals(r.sha256, ignoreCase = true)
        r.sha1.isNotEmpty() -> sha1(p).equals(r.sha1, ignoreCase = true)
        else -> false
    }

    private val http: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build() }

    /** Reads the feed for the install's channel and says whether a newer full release is there. Throws on a network or feed error. */
    fun check(feedUrl: String, install: Install): Available? {
        val r = UpdateFeed.newest(UpdateFeed.fetch(feedUrl, install.channel), install.version, install.id) ?: return null
        return Available(r, "${feedUrl.trimEnd('/')}/${r.fileName}")
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
}
