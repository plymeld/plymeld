package plymeld.commands

import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant
import java.util.zip.GZIPInputStream

/**
 * How plymeld is installed, and how an install is updated: shared by `plymeld update` and the window's own install.
 *
 * - A **Velopack install** (the Windows installer and portable zip, the Linux AppImage): Velopack has no JVM SDK, so
 *   this is plymeld's own client of the two things Velopack defines and documents: the release feed (read through
 *   [UpdateFeed]) and the updater binary installed beside the app (`Update.exe` on Windows, `UpdateNix` inside the
 *   AppImage), whose `apply --waitPid PID --package FILE` installs a downloaded package once that process has exited.
 * - The **Linux command line's tar** (`plymeld-linux-x64.tar.gz`, unpacked anywhere): its files are replaced in place
 *   ([Tar]), the download checked against the checksum published beside it and the new executable's own `--version`.
 * - Anything else (a build from source, run on a JVM) is not updated by plymeld.
 */
object Installs {
    /** A Velopack install this executable belongs to: its id, the installed version and channel, the updater, and [root], the folder everything of it is under. */
    class Velopack(val id: String, val version: String, val channel: String, val updater: Path, val packages: Path, val portable: Boolean, val root: Path)

    class Manifest(val id: String, val version: String, val channel: String?)

    /** `sq.version` is the package's nuspec: `<id>`, `<version>` and Velopack's `<channel>` under `<metadata>`. */
    fun parseManifest(xml: String): Manifest? {
        fun tag(name: String): String? = Regex("<$name>\\s*([^<]*?)\\s*</$name>").find(xml)?.groupValues?.get(1)
        val id = tag("id") ?: return null
        val version = tag("version") ?: return null
        return Manifest(id, version, tag("channel")?.takeIf { it.isNotEmpty() })
    }

    fun currentExe(): Path? = ProcessHandle.current().info().command().orElse(null)?.let { Path.of(it) }

    /**
     * The Velopack install this executable is part of, found the way Velopack's own locator finds it (its `locator.rs`):
     * on Windows the app runs from `{root}\current\` with `Update.exe` in `{root}` and `sq.version` in `current\`
     * (the command line runs from `current\cli\`, a folder below it; `.portable` in the root marks a portable unpack,
     * which the updater still serves); in an AppImage the executable, `UpdateNix` and `sq.version` share `usr/bin/` and
     * `$APPIMAGE` names the file. Null when the layout is not there: a development run, a bare copy of the files.
     */
    fun velopack(exe: Path? = currentExe(), env: Map<String, String> = System.getenv(), os: String = System.getProperty("os.name")): Velopack? {
        val dir = exe?.toAbsolutePath()?.parent ?: return null
        val here = listOfNotNull(dir, dir.parent).firstOrNull { Files.isRegularFile(it.resolve("sq.version")) } ?: return null
        val m = parseManifest(Files.readString(here.resolve("sq.version"))) ?: return null
        return if (os.lowercase().contains("windows")) {
            val root = here.parent ?: return null
            val updater = root.resolve("Update.exe").takeIf { Files.isRegularFile(it) } ?: return null
            Velopack(m.id, m.version, m.channel ?: "win", updater, root.resolve("packages"), Files.exists(root.resolve(".portable")), root)
        } else {
            val updater = here.resolve("UpdateNix").takeIf { Files.isRegularFile(it) } ?: return null
            if (env["APPIMAGE"].isNullOrEmpty()) return null
            Velopack(m.id, m.version, m.channel ?: "linux", updater, Path.of("/var/tmp/velopack", m.id, "packages"), true, here)
        }
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

    /** Downloads [url] to [target] through a `.partial` file; [progress] gets (bytes so far, total when known, else 0). */
    fun download(url: String, target: Path, total: Long = 0, progress: (Long, Long) -> Unit = { _, _ -> }) {
        Files.createDirectories(target.parent)
        val partial = target.resolveSibling(target.fileName.toString() + ".partial")
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofMinutes(30)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) { res.body().close(); error("the download of $url answered ${res.statusCode()}") }
        val size = total.takeIf { it > 0 } ?: res.headers().firstValueAsLong("content-length").orElse(0L)
        res.body().use { input ->
            Files.newOutputStream(partial).use { out ->
                val buf = ByteArray(1 shl 16); var done = 0L
                while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n); done += n; progress(done, size) }
            }
        }
        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
    }

    /** A small text file at [url] (a checksum), read whole. */
    fun text(url: String): String {
        val res = http.send(HttpRequest.newBuilder(URI(url)).timeout(Duration.ofSeconds(30)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) error("$url answered ${res.statusCode()}")
        return res.body()
    }

    /** Downloads the Velopack package [r] (at [url]) into the install's packages folder, verifying its size and checksum ([matches]). */
    fun downloadPackage(r: UpdateFeed.Release, url: String, install: Velopack, progress: (Long, Long) -> Unit = { _, _ -> }): Path {
        if (r.sha256.isEmpty() && r.sha1.isEmpty()) error("the update feed gives no checksum for ${r.fileName}")
        val target = install.packages.resolve(r.fileName)
        if (Files.isRegularFile(target) && Files.size(target) == r.size && matches(target, r)) return target
        val got = install.packages.resolve(r.fileName + ".download")
        download(url, got, r.size, progress)
        if (r.size > 0 && Files.size(got) != r.size) { Files.deleteIfExists(got); error("the package is ${Files.size(got)} bytes, the feed says ${r.size}") }
        if (!matches(got, r)) { Files.deleteIfExists(got); error("the package's checksum does not match the feed's") }
        Files.move(got, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    /**
     * Hands the package to the updater and returns the started process: `apply --waitPid PID --package FILE` waits for
     * this process to exit, installs, and (unless [restart] is false: `--norestart`) starts the window. The caller exits.
     */
    fun apply(install: Velopack, pkg: Path, restart: Boolean = true): Process {
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

    /** Another plymeld process running from an install's folder: what it is and since when, for the user to recognize. */
    class Running(val handle: ProcessHandle, val command: String, val started: Instant?) {
        fun describe(): String = "process ${handle.pid()}: $command" + (started?.let { ", started $it" } ?: "")
    }

    /**
     * The processes other than this one whose executable is under [root] (a window, a `printer watch` in a terminal, a
     * second window). On Windows a running program's files cannot be replaced, so an update waits for them or stops them.
     */
    fun running(root: Path, all: () -> List<ProcessHandle> = { ProcessHandle.allProcesses().toList() }, self: Long = ProcessHandle.current().pid()): List<Running> {
        val base = root.toAbsolutePath().normalize()
        return all().mapNotNull { h ->
            if (h.pid() == self) return@mapNotNull null
            val info = h.info()
            val exe = info.command().orElse(null)?.let { try { Path.of(it).toAbsolutePath().normalize() } catch (_: Exception) { null } } ?: return@mapNotNull null
            if (!exe.startsWith(base)) return@mapNotNull null
            val args = info.arguments().orElse(null)?.joinToString(" ")
            Running(h, exe.fileName.toString() + (args?.takeIf { it.isNotEmpty() }?.let { " $it" } ?: ""), info.startInstant().orElse(null))
        }
    }

    /**
     * How long a stopped process is given to end on its own before it is ended forcibly, and then to be gone: plymeld's
     * choice, not measured: a window saves nothing on exit, and a command being stopped is being abandoned anyway.
     */
    val STOP_WAIT: Duration = Duration.ofSeconds(10)

    /** Stops [processes] (asked first, then forced after [STOP_WAIT]); returns those still running after that. */
    fun stop(processes: List<Running>): List<Running> {
        for (p in processes) p.handle.destroy()
        val deadline = System.nanoTime() + STOP_WAIT.toNanos()
        for (p in processes) {
            val left = deadline - System.nanoTime()
            if (left > 0) try { p.handle.onExit().get(left, java.util.concurrent.TimeUnit.NANOSECONDS) } catch (_: Exception) { }
        }
        val stubborn = processes.filter { it.handle.isAlive }
        for (p in stubborn) p.handle.destroyForcibly()
        for (p in stubborn) try { p.handle.onExit().get(STOP_WAIT.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS) } catch (_: Exception) { }
        return processes.filter { it.handle.isAlive }
    }

    /**
     * The Linux command line's tar: `plymeld-linux-<arch>.tar.gz`, a `plymeld/` folder holding the executable and the
     * libraries native-image put beside it, unpacked anywhere. Updated in place: the new tar downloaded beside the
     * install, checked against the published `.sha256`, unpacked into a folder there, its executable's `--version`
     * asked, and then each file moved over the installed one (a rename: the running executable keeps its own copy until
     * it exits, as Linux allows).
     */
    object Tar {
        /** The tar's name for this machine's architecture, or null where none is published. */
        fun name(arch: String = System.getProperty("os.arch")): String? = when (arch.lowercase()) {
            "amd64", "x86_64" -> "plymeld-linux-x64.tar.gz"
            "aarch64", "arm64" -> "plymeld-linux-arm64.tar.gz"
            else -> null
        }

        /** The install folder when this is the tar's executable: Linux, a native executable named `plymeld`, not part of a Velopack install. */
        fun folder(exe: Path? = currentExe(), os: String = System.getProperty("os.name"), native: Boolean = System.getProperty("org.graalvm.nativeimage.imagecode") != null): Path? {
            if (!os.lowercase().contains("linux") || !native) return null
            val e = exe?.toAbsolutePath() ?: return null
            if (e.fileName.toString() != "plymeld") return null
            return e.parent
        }

        /** The checksum in a `.sha256` file (`HASH  NAME`, as `sha256sum` writes it). */
        fun parseSha256(text: String): String = text.trim().split(Regex("\\s+")).firstOrNull()?.takeIf { it.matches(Regex("[0-9a-fA-F]{64}")) }
            ?: error("the published checksum is not a SHA-256: ${text.take(80)}")

        /**
         * Unpacks the gzipped tar at [tar] into [into]: regular files and folders, under its single top folder (stripped),
         * each file's executable bit kept. A path that leaves [into] (`..`, absolute) is refused.
         */
        fun unpack(tar: Path, into: Path): List<Path> {
            Files.createDirectories(into)
            val out = ArrayList<Path>()
            GZIPInputStream(Files.newInputStream(tar)).use { gz ->
                val header = ByteArray(512)
                var longName: String? = null
                while (true) {
                    if (gz.readNBytes(header, 0, 512) < 512 || header.all { it == 0.toByte() }) break
                    fun field(off: Int, len: Int) = String(header, off, len, Charsets.UTF_8).substringBefore('\u0000')
                    val size = field(124, 12).trim().ifEmpty { "0" }.toLong(8)
                    val type = header[156].toInt().toChar()
                    val prefix = field(345, 155)
                    var name = longName ?: (if (prefix.isNotEmpty()) "$prefix/" else "") + field(0, 100)
                    longName = null
                    val mode = field(100, 8).trim().ifEmpty { "0" }.toInt(8)
                    val data = gz.readNBytes(size.toInt())
                    val pad = ((512 - size % 512) % 512).toInt()
                    if (pad > 0) gz.readNBytes(pad)
                    if (type == 'L') { longName = String(data, Charsets.UTF_8).substringBefore('\u0000'); continue }
                    name = name.removePrefix("./").substringAfter('/', "")          // the top folder (plymeld/) stripped
                    if (name.isEmpty()) continue
                    val target = into.resolve(name).normalize()
                    if (!target.startsWith(into.normalize()) || Path.of(name).isAbsolute) error("the tar holds a path outside its folder: $name")
                    when (type) {
                        '5' -> Files.createDirectories(target)
                        '0', '\u0000' -> {
                            Files.createDirectories(target.parent)
                            Files.write(target, data)
                            if (mode and "100".toInt(8) != 0) try {
                                Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(target) + setOf(PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_EXECUTE))
                            } catch (_: UnsupportedOperationException) { }
                            out.add(target)
                        }
                        else -> { }                                                   // links and the rest: none in plymeld's tar
                    }
                }
            }
            return out
        }

        /** Runs the unpacked executable's `--version` and returns its version, or throws. */
        fun versionOf(exe: Path): String {
            val p = ProcessBuilder(exe.toString(), "--version").redirectErrorStream(true).start()
            val text = p.inputStream.use(InputStream::readAllBytes).toString(Charsets.UTF_8).trim()
            if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); error("the new executable did not answer --version") }
            return Regex("plymeld version (\\S+)").find(text)?.groupValues?.get(1) ?: error("the new executable's --version said: ${text.take(200)}")
        }

        /**
         * Updates the tar install in [folder] to [version] from [base] (the feed's address, where every release file of the
         * latest release is served): downloads `{base}/{name}` and `{base}/{name}.sha256` into a folder beside the
         * install, checks the checksum, unpacks, asks the new executable's `--version`, and only then replaces the
         * installed files. Any failure before that changes nothing; [log] gets each step.
         */
        fun update(folder: Path, version: String, base: String, name: String, log: (String) -> Unit) {
            if (!Files.isWritable(folder)) error("$folder is not writable: update it as the user who installed it")
            val root = base.trimEnd('/')
            val work = Files.createTempDirectory(folder, ".plymeld-update-")
            try {
                val sum = parseSha256(text("$root/$name.sha256"))
                val got = work.resolve(name)
                log("downloading $name")
                download("$root/$name", got)
                if (!sha256(got).equals(sum, ignoreCase = true)) error("the download's checksum does not match the published one: nothing was changed")
                val staged = work.resolve("plymeld")
                val files = unpack(got, staged)
                val exe = staged.resolve("plymeld").takeIf { Files.isRegularFile(it) } ?: error("the download holds no plymeld executable: nothing was changed")
                val v = versionOf(exe)
                if (v != version) error("the download is plymeld $v, not $version (a release published meanwhile?): nothing was changed; run `plymeld update` again")
                replace(staged, files, folder)
                log("plymeld $version installed in $folder")
            } finally {
                work.toFile().deleteRecursively()
            }
        }

        /** Moves every file unpacked in [staged] over its counterpart in [folder] (each a rename within one file system). */
        fun replace(staged: Path, files: List<Path>, folder: Path) {
            for (f in files) {
                val rel = staged.relativize(f)
                val target = folder.resolve(rel.toString())
                Files.createDirectories(target.parent)
                Files.move(f, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            }
        }
    }
}
