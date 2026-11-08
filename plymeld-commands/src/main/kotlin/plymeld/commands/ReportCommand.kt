package plymeld.commands

import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.path
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `plymeld report`: a problem report about what you are working on, as a zip to attach and a GitHub issue to open. It
 * holds plymeld's version and the system it runs on, the config as plymeld reads it (`plymeld config`), with models (-m)
 * the plan (`plymeld plan`), with --gcode that file's header and source block (the config and plan it was made from), and
 * with --printer the printer's status. Left out unless asked: the models themselves and the whole g-code (--include-model:
 * a model may be private, and its g-code draws it). Never in it: credentials (plymeld's config holds none: they are in the
 * environment or the secrets files, which it does not read), network addresses (written `<address>`), and your home
 * folder's path (written `~`). The issue is opened in the browser, filled in, for you to read, attach the zip to, and
 * submit; nothing is sent by plymeld.
 */
class ReportCommand : ConfigCommand("report") {
    private val models by option("-m", "--model", metavar = "FILE|GLOB", help = "the job's model files: the report holds the plan for them (not the files themselves; --include-model adds them)").multiple()
    private val gcode by option("--gcode", metavar = "FILE", help = "a g-code file plymeld wrote for the job: the report holds its header and its source block (the config and plan it was made from), not its moves").path(mustExist = true, canBeDir = false)
    private val printer by option("--printer", help = "also the machine's printer's status (plymeld printer status): asks the printer").flag()
    private val includeModel by option("--include-model", help = "also the model files and the whole g-code file: a model may be private, and its g-code draws it, so they are left out unless asked").flag()
    private val title by option("--title", metavar = "TEXT", help = "the issue's title (default: \"Problem report\" and plymeld's version)")
    private val description by option("--description", metavar = "TEXT", help = "what happened and what you expected, written into the issue (default: headings to fill in on the issue's page)")
    private val output by option("-o", "--output", metavar = "FILE", help = "the zip to write (default: plymeld-report-<date>-<time>.zip here)").path()
    private val open by option("--open", help = "open the filled-in issue in the browser (otherwise its address is printed)").flag()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Make a problem report about what you are working on: a zip to attach to a GitHub issue, and the issue filled in for you to read and submit (its address printed, or opened with --open). " +
            "The zip holds plymeld's version and the system, the config as plymeld reads it (plymeld config), with -m the plan (plymeld plan), with --gcode that file's header and source block, and with --printer the printer's status. " +
            "The models and the whole g-code only with --include-model. Never credentials (in the environment or the secrets files, not the config), network addresses, or your home folder's path. Nothing is sent: you submit the issue."

    override fun run() {
        val home = System.getProperty("user.home")
        val parts = LinkedHashMap<String, String>()
        val cfg = configFiles.flatMap { listOf("-cf", it) } + configValues.flatMap { listOf("-cv", it) } + (machineOpt?.let { listOf("--machine", it) } ?: emptyList())
        fun run(name: String, args: List<String>) {
            val lines = ArrayList<String>()
            val status = Commands.invoke(args, { lines += it }, { lines += it })
            parts[name] = "$ plymeld ${args.joinToString(" ")}\n" + lines.joinToString("\n") + "\n(exit $status)\n"
        }
        val system = "plymeld ${PlymeldVersion.value}\n" +
            "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})\n" +
            "Java ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})\n"
        parts["system.txt"] = system
        echo("report: the config (plymeld config)")
        run("config.txt", listOf("config") + cfg)
        if (models.isNotEmpty()) {
            echo("report: the plan for ${models.size} model file(s) (plymeld plan)")
            run("plan.txt", listOf("plan") + cfg + models.flatMap { listOf("-m", it) })
        }
        gcode?.let { g -> parts["gcode-header-and-source.txt"] = headerAndSource(Files.readAllLines(g)) }
        if (printer) {
            echo("report: the printer's status (plymeld printer status)")
            run("printer-status.txt", listOf("printer", "status") + cfg)
        }
        val texts = parts.mapValues { (_, v) -> redacted(v, home) }
        val name = output ?: Path.of("plymeld-report-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip")
        name.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        ZipOutputStream(Files.newOutputStream(name)).use { z ->
            for ((n, t) in texts) { z.putNextEntry(ZipEntry(n)); z.write(t.toByteArray(StandardCharsets.UTF_8)); z.closeEntry() }
            if (includeModel) {
                val files = plymeld.config.ConfigFiles.expand(models) + listOfNotNull(gcode)
                for (f in files) { z.putNextEntry(ZipEntry("files/" + f.fileName)); Files.copy(f, z); z.closeEntry() }
            }
        }
        echo("report written to $name: ${texts.keys.joinToString()}" + (if (includeModel) ", and the model and g-code files" else " (no model or g-code files: --include-model adds them)"))
        val url = issueUrl(title ?: "Problem report: plymeld ${PlymeldVersion.value}", issueBody(redacted(system, home), texts.keys.toList(), name.fileName.toString(), description))
        if (open && openInBrowser(url)) echo("the issue opened in your browser: read it, attach ${name.fileName}, and submit it")
        else echo("open this address to file the issue (read it, attach ${name.fileName}, and submit it):\n$url")
    }

    companion object {
        /** Where reports go: the project's issues on GitHub. */
        const val ISSUES = "https://github.com/plymeld/plymeld/issues/new"

        /** A g-code file's header (its first comment lines, without a picture's data) and its source block. */
        fun headerAndSource(lines: List<String>): String {
            val out = StringBuilder()
            var inPicture = false
            for (l in lines) {
                if (l.isNotEmpty() && !l.startsWith(";")) break
                if (l.startsWith("; THUMBNAIL_BLOCK_START")) { inPicture = true; out.append("; (the file's pictures left out)\n"); continue }
                if (l.startsWith("; THUMBNAIL_BLOCK_END")) { inPicture = false; continue }
                if (!inPicture) out.append(l).append('\n')
            }
            val start = lines.indexOfFirst { it == SourceBlock.START }
            val end = lines.indexOfFirst { it == SourceBlock.END }
            out.append('\n').append(if (start >= 0 && end > start) lines.subList(start, end + 1).joinToString("\n") else "(no source block: not a file plymeld wrote, or cut short)").append('\n')
            return out.toString()
        }

        /** [text] with the home folder's path as `~` and network addresses (IPv4, IPv6, MAC) as `<address>`. */
        fun redacted(text: String, home: String?): String {
            var t = text
            if (!home.isNullOrBlank() && home.length > 1) {
                t = t.replace(home, "~")
                t = t.replace(home.replace('\\', '/'), "~")
            }
            t = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b").replace(t, "<address>")
            t = Regex("\\b[0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5}\\b").replace(t, "<address>")
            t = Regex("\\b(?:[0-9A-Fa-f]{1,4}:){7}[0-9A-Fa-f]{1,4}\\b").replace(t, "<address>")
            return t
        }

        /** The issue's text: what to fill in, the system, and what the zip holds. */
        fun issueBody(system: String, files: List<String>, zip: String, description: String?): String = buildString {
            append(description?.let { "## What happened\n\n$it\n\n" } ?: "## What happened\n\n(what you did, and what plymeld did)\n\n## What you expected\n\n(what should have happened)\n\n")
            append("## System\n\n```\n").append(system.trimEnd()).append("\n```\n\n")
            append("## The report\n\nAttached: `$zip` (made by `plymeld report`: ${files.joinToString()}).\n")
        }

        /** The issue page with [title] and [body] filled in. */
        fun issueUrl(title: String, body: String): String {
            fun e(s: String) = URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20")
            return "$ISSUES?title=${e(title)}&body=${e(body)}"
        }

        /** Opens [url] in the desktop's browser; false where there is none (a terminal session, a headless machine). */
        fun openInBrowser(url: String): Boolean = try {
            if (!java.awt.GraphicsEnvironment.isHeadless() && java.awt.Desktop.isDesktopSupported() && java.awt.Desktop.getDesktop().isSupported(java.awt.Desktop.Action.BROWSE)) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url)); true
            } else false
        } catch (_: Exception) { false }
    }
}
