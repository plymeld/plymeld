package plymeld.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.core.terminal
import com.github.ajalt.mordant.terminal.prompt
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.path
import plymeld.config.Config
import plymeld.printer.Capability
import plymeld.printer.FlashforgeHttp
import plymeld.printer.LoadoutSync
import plymeld.printer.MoonrakerHttp
import plymeld.printer.Printer
import plymeld.printer.PrinterState
import plymeld.printer.Printers
import plymeld.printer.Protocol
import plymeld.printer.Status
import plymeld.printer.ToolMapping
import plymeld.printer.Watch
import plymeld.printer.refuse
import plymeld.printer.text

/**
 * `plymeld printer ...`: talking to the machine's printer through one interface,
 * over the connection its machine names in the config (`hardware.machine.<key>.connection.*`, the
 * credentials from the environment variables or secrets files named there). Each subcommand says what it sends; one the
 * machine's API cannot do is refused in one line. The printer is named by its machine key everywhere, never its model.
 */
class PrinterCommand : CliktCommand(name = "printer") {
    init { subcommands(PrinterStatusCommand(), PrinterWatchCommand(), PrinterCameraCommand(), PrinterFilesCommand(), PrinterSendCommand(), PrinterPauseCommand(), PrinterResumeCommand(), PrinterCancelCommand(), PrinterLoadoutCommand(), PrinterCredentialCommand()) }
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Talk to the machine's printer (--machine, over hardware.machine.<key>.connection in the config; credentials from the environment variables or secrets files named there): " +
            "status (its state), watch (its state as it changes), camera (one picture from its camera), files (what is on it), send (upload a file; --print starts it), pause, resume, cancel, loadout (what it has loaded, against the config), " +
            "credential (set one in its secrets file; sends nothing). " +
            "Each says what it sends; what the machine's API cannot do is refused in one line."
    override fun run() = Unit
}

/** The product options plus the machine's printer opened from the config. */
abstract class PrinterConfigCommand(name: String) : ConfigCommand(name) {
    protected fun config(): Config = try { Config(stack(), emptyList(), machineOpt) } catch (e: plymeld.config.ConfigError) {
        throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
    }
    protected fun machine(c: Config): String = c.machine ?: throw UsageError(c.machineProblem ?: "which machine?")
    protected fun printer(c: Config): Printer = Printers.open(c, machine(c))
    protected fun need(p: Printer, cap: Capability) { if (!p.can(cap)) p.refuse(cap) }
    protected fun echoState(s: PrinterState) = echo(s.text().trimEnd())
}

/** `plymeld printer status`: the typed state, one line per field; `--from-response` reads a saved answer instead of asking. */
class PrinterStatusCommand : PrinterConfigCommand("status") {
    private val fromResponse by option("--from-response", metavar = "FILE", help = "read a saved answer to the state request (as the machine's API gives it) instead of asking the printer; nothing is sent").path(mustExist = true)
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Read the printer's state: status, job, progress, layer, times, nozzle and bed temperatures, speed and flow overrides, what is loaded where the same answer carries it; " +
            "\"not reported\" where its API gives no such field. Sends one state request (flashforge-http: POST /detail; moonraker: GET /printer/objects/query)."
    override fun run() {
        val c = config()
        val saved = fromResponse
        if (saved != null) {
            val key = machine(c)
            val text = java.nio.file.Files.readString(saved)
            echoState(when (Printers.protocol(c, key)) {
                Protocol.FLASHFORGE_HTTP -> FlashforgeHttp.parseState(key, text)
                Protocol.MOONRAKER -> MoonrakerHttp.parseState(key, text)
            })
            return
        }
        val p = printer(c)
        need(p, Capability.STATE)
        echoState(p.state())
    }
}

/** `plymeld printer watch [--every SECONDS]`: the state as it changes, polled, until the print ends or Ctrl+C. */
class PrinterWatchCommand : PrinterConfigCommand("watch") {
    private val every by option("--every", metavar = "SECONDS", help = "how often to ask the printer for its state (default ${Watch.DEFAULT_EVERY.seconds} s, plymeld's choice)").double()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Print the printer's state as it changes: the full state first, then one timestamped line per line that changed, asking every --every seconds (default ${Watch.DEFAULT_EVERY.seconds} s) " +
            "until the print ends (finished, canceled, error, or idle after it was printing) or Ctrl+C. Sends one state request per poll (flashforge-http: POST /detail; moonraker: GET /printer/objects/query) and nothing else."
    override fun run() {
        val p = printer(config())
        need(p, Capability.STATE)
        val interval = every?.let { require(it > 0) { "--every is seconds between polls, more than 0" }; java.time.Duration.ofMillis((it * 1000).toLong()) } ?: Watch.DEFAULT_EVERY
        Watch.run(p, interval, { echo(it) })
    }
}

/** `plymeld printer camera -o FILE [--frame N]`: one JPEG from the printer's camera, written to FILE. */
class PrinterCameraCommand : PrinterConfigCommand("camera") {
    private val output by option("-o", "--output", metavar = "FILE", help = "the JPEG file to write").path().required()
    private val frame by option("--frame", metavar = "N", help = "which frame of a stream to keep, from 1 (default: the adapter's own; flashforge-http keeps the second, its first being stale); a snapshot URL gives its one image").int()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Save one JPEG from the printer's camera to FILE (-o): the machine's connection.camera.url (a snapshot or an MJPEG stream; flashforge-http falls back to the URL its /detail names), " +
            "refused in one line where none is set. Prints the file's path and size. Sends one GET of the camera URL (and, flashforge-http without camera.url, one POST /detail)."
    override fun run() {
        val p = printer(config())
        need(p, Capability.CAMERA)
        frame?.let { require(it >= 1) { "--frame counts from 1" } }
        val f = p.cameraFrame(frame)
        output.toAbsolutePath().parent?.let { java.nio.file.Files.createDirectories(it) }
        java.nio.file.Files.write(output, f.jpeg)
        echo("$output: ${f.jpeg.size} bytes from printer '${p.key}' (${f.source})")
    }
}

/** `plymeld printer files`: the files on the printer, one per line. */
class PrinterFilesCommand : PrinterConfigCommand("files") {
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "List the files on the printer, one per line with the size and time where its API gives them. Sends one list request (flashforge-http: POST /gcodeList; moonraker: GET /server/files/list)."
    override fun run() {
        val p = printer(config())
        need(p, Capability.FILES)
        val fs = p.files()
        if (fs.isEmpty()) { echo("printer '${p.key}': no files"); return }
        for (f in fs) echo("${f.name}  ${f.sizeBytes.text { "$it bytes" }}  ${f.modifiedEpochS.text { java.time.Instant.ofEpochSecond(it.toLong()).toString() }}")
    }
}

/**
 * `plymeld printer send FILE [--name NAME] [--print [--level | --no-level]] [--replace]`: upload FILE, confirm it by the
 * printer's own file list where the API has one, and with `--print` start it, levelling the bed first where asked (the
 * option, else the machine's `connection.start.level`) and the API takes that request. Refused when a file of that name is
 * already on the printer (unless `--replace`), and when the printer is printing, paused, or busy (where its state can be
 * read). Sends only what it says.
 */
class PrinterSendCommand : PrinterConfigCommand("send") {
    private val file by argument("FILE", help = "the g-code file to upload").path(mustExist = true, canBeDir = false)
    private val name by option("--name", metavar = "NAME", help = "the name to give it on the printer (default: the file's own name)")
    private val print by option("--print", help = "also start printing it once the upload is confirmed (the printer runs at once: the bed clear, the right filament loaded, and someone with it)").flag()
    private val level by option("--level", help = "with --print: ask the printer to level the bed before it prints (flashforge-http: levelingBeforePrint); over the machine's connection.start.level; refused, before anything is sent, where the API has no such request (on a Klipper printer levelling is g-code)").flag()
    private val noLevel by option("--no-level", help = "with --print: do not ask the printer to level first, whatever the machine's connection.start.level says").flag()
    private val replace by option("--replace", help = "overwrite a file of the same name already on the printer (refused otherwise)").flag()
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Upload FILE to the printer and confirm it by the printer's own file list (never by the reply code); with --print, start it, telling the printer which slot prints each tool the file uses where its API takes that (flashforge-http: materialMappings, from the machine's loadout), and to level the bed first with --level or the machine's connection.start.level (flashforge-http only). " +
            "What the printer does by itself as a print starts (homing, heating, levelling) is its model's (hardware.model.<key>.notes: a Flashforge Creator 5 homes and levels by itself, levelling when the start asks for it); the rest is the file's start g-code. " +
            "Refused when the printer already has a file of that name (--replace to overwrite), is printing, paused, or busy, or (--print) when a tool the file uses has no filament loaded in the machine's loadout. " +
            "With --print, what the printer reports loaded is compared with the machine's loadout where its API reports one: a mismatch warns, one line per position, and the print goes on. " +
            "Sends the list request, the upload (flashforge-http: POST /uploadGcode; moonraker: POST /server/files/upload), the list again, and with --print one loadout request where the API has one, then the start (POST /printGcode; POST /printer/print/start)."
    override fun run() {
        val c = config()
        val p = printer(c)
        need(p, Capability.UPLOAD)
        if (print) need(p, Capability.START)
        if (level && noLevel) throw UsageError("--level and --no-level: one or the other")
        if ((level || noLevel) && !print) throw UsageError("--level and --no-level ask the printer as it starts a print: give --print with them")
        // the option, else the machine's own default; asked of a printer that cannot, refused before anything is sent
        val key = machine(c)
        val levelFirst = print && when {
            level -> true
            noLevel -> false
            else -> c.entry(listOf("hardware", "machine", key, "connection", "start", "level"))?.let { c.boolean(it, plymeld.config.Site(machine = key)) } == true
        }
        if (levelFirst && !p.can(Capability.LEVEL_ON_START))
            error("printer '${p.key}' cannot level the bed as a print starts: ${p.protocol.id} takes no such request; level in the g-code instead (the machine's gcode.start, or hardware.machine.$key.gcode.level)" +
                (if (!level) " (asked by hardware.machine.$key.connection.start.level; --no-level to print without)" else ""))
        val remote = name ?: file.fileName.toString()
        val bytes = java.nio.file.Files.readAllBytes(file)
        var mapping: ToolMapping? = null
        if (print) {
            // which slot prints each tool the file uses: built from the machine's loadout before anything is sent, so
            // a tool with no filament loaded refuses the whole command; the printer's slot colors are filled in from its
            // reported loadout where the API has one
            val used = ToolMapping.toolsUsed(String(bytes, Charsets.ISO_8859_1))
            mapping = ToolMapping.fromLoadout(c, p.key, used, null)
            if (p.can(Capability.LOADOUT)) {
                val reported = p.loadout()
                mapping = ToolMapping.fromLoadout(c, p.key, used, reported)
                // what the printer reports loaded against the machine's loadout: a mismatch warns, one line per
                // position, and the command goes on (warn, not refuse)
                LoadoutSync.compare(c, reported).warnings().forEach { echo(it) }
            } else echo("printer '${p.key}': its API reports no loadout, so what is loaded is not checked")
        }
        if (p.can(Capability.FILES) && remote in p.files().map { it.name } && !replace)
            error("printer '${p.key}' already has a file named $remote: --replace to overwrite it, or --name to call this one something else")
        if (p.can(Capability.STATE)) {
            val s = p.state()
            if (s.status in setOf(Status.PRINTING, Status.PAUSED, Status.BUSY)) error("printer '${p.key}' is ${s.status.words}${s.job.orNull?.let { " ($it)" } ?: ""}: not uploading to it now")
        }
        val u = p.upload(remote, bytes)
        when (u.confirmed) {
            true -> echo("uploaded $file as $remote (${u.bytes} bytes) to printer '${p.key}': its file list confirms it")
            null -> echo("uploaded $file as $remote (${u.bytes} bytes) to printer '${p.key}': its API has no file list to confirm it by")
            false -> error("printer '${p.key}': the upload of $remote returned no error, yet its file list did not gain the name; not trusting it (check the printer)")
        }
        if (!print) return
        val d = p.start(remote, mapping, levelFirst)
        echo("sent to printer '${p.key}': ${d.sent}")
        d.readBack?.let { echo("status now: ${it.status.words}${it.rawStatus?.let { r -> if (r != it.status.words) " (the printer's word: \"$r\")" else "" } ?: ""}") }
    }
}

/** A job control: what was sent, then the status read back where the adapter can. */
abstract class PrinterJobCommand(name: String, private val cap: Capability, private val sends: String) : PrinterConfigCommand(name) {
    override fun help(context: com.github.ajalt.clikt.core.Context) = "${cap.words.replaceFirstChar { it.uppercase() }}. Sends $sends, then reads the state back where the API allows (the Creator 5's /control acknowledges whatever happens: judge by the status read back)."
    abstract fun act(p: Printer): plymeld.printer.Done
    override fun run() {
        val p = printer(config())
        need(p, cap)
        val d = act(p)
        echo("sent to printer '${p.key}': ${d.sent}")
        d.readBack?.let { echo("status now: ${it.status.words}${it.rawStatus?.let { r -> if (r != it.status.words) " (the printer's word: \"$r\")" else "" } ?: ""}") }
    }
}

class PrinterPauseCommand : PrinterJobCommand("pause", Capability.PAUSE, "the pause (flashforge-http: POST /control jobCtl_cmd pause; moonraker: POST /printer/print/pause)") {
    override fun act(p: Printer) = p.pause()
}

class PrinterResumeCommand : PrinterJobCommand("resume", Capability.RESUME, "the resume (flashforge-http: POST /control jobCtl_cmd continue; moonraker: POST /printer/print/resume, which returns when the RESUME macro has run)") {
    override fun act(p: Printer) = p.resume()
}

class PrinterCancelCommand : PrinterJobCommand("cancel", Capability.CANCEL, "the cancel (flashforge-http: POST /control jobCtl_cmd cancel; moonraker: POST /printer/print/cancel)") {
    override fun act(p: Printer) = p.cancel()
}

/**
 * `plymeld printer credential NAME [--machine M]`: one of the machine's credentials set in the secrets file its config names
 * (`connection.credential.<NAME>.file`), the value typed at a prompt that does not show it, or read as one line from
 * standard input when that is not a terminal; never from the command line, where it would show in the process list.
 * Sends nothing to the printer; `printer status` tries the new value.
 */
class PrinterCredentialCommand : PrinterConfigCommand("credential") {
    private val name by argument("NAME", help = "the credential: flashforge-http serial_number or check_code; moonraker api_key, ssh_password or ssh_key_file")
    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Set one of the printer's credentials in the secrets file the machine's connection names (hardware.machine.<key>.connection.credential.NAME.file), " +
            "the file's other lines kept. The value is typed at a prompt that does not show it, or read as one line from standard input when that is not a terminal; it is never printed. " +
            "Refused when the config names no secrets file for it (one named only by an environment variable is set there, not by plymeld). Sends nothing to the printer."

    override fun run() {
        val c = config()
        val key = machine(c)
        val protocol = Printers.protocol(c, key)
        val names = protocol.required + protocol.optional
        if (name !in names) throw UsageError("${protocol.id} has no credential '$name': it takes ${names.joinToString()}")
        val path = "hardware.machine.$key.connection.credential.$name"
        fun text(source: String) = (c.entry("$path.$source".split('.'))?.value as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val variable = text("env")
        val file = text("file") ?: error("printer '$key': no secrets file is named for '$name'" +
            (variable?.let { " (it comes from environment variable $it: set it there)" } ?: "") + "; set $path.file to one (plymeld edit), then run this again")
        val interactive = terminal.terminalInfo.inputInteractive
        val value = (if (interactive) terminal.prompt("$name for printer '$key'", hideInput = true) else readlnOrNull())?.trim()
        if (value.isNullOrEmpty()) error("no value given for '$name': nothing written")
        val target = plymeld.printer.FileCredential.expandHome(file)
        val replaced = plymeld.printer.FileCredential(target, name).write(value)
        echo("printer '$key': '$name' ${if (replaced) "replaced" else "added"} in $target")
        if (variable != null && !System.getenv(variable).isNullOrEmpty())
            echo("note: environment variable $variable has a value, and it wins over the file while it does")
    }
}

/**
 * `plymeld printer loadout [--api flashforge-http --host H ...] -cf ... [--machine M] [-o overlay.toml]`: what the printer
 * reports loaded in each tool or slot against the machine's loadout in the config, and which config filament matches
 * (LoadoutSync's rule). With -o, the matched loadout as a config file for `-cf`; the user's own files are never rewritten.
 * --from-response reads a saved `/detail` answer instead of asking the printer; --save-response keeps the one it got.
 *
 * The connection comes from the machine's `connection.*` in the config, as for the other subcommands; the options
 * (`--api`, `--host`, `--port`, `--serial-number`, `--check-code`) are the earlier way and still work: when `--api` is
 * given they are used instead of the config's connection.
 */
class PrinterLoadoutCommand : PrinterConfigCommand("loadout") {
    private val api by option("--api", help = "the printer's API, when not the machine's connection in the config: flashforge-http = Flashforge's LAN HTTP API (POST /detail), as the Creator 5 serves it; the only one these options cover (a Moonraker machine's loadout comes over SSH, from the config's connection)")
        .choice("flashforge-http")
    private val host by option("--host", metavar = "HOST", help = "with --api: the printer's address")
    private val port by option("--port", help = "with --api: the API's port (default: the API's own, 8898 for flashforge-http)").int()
    private val serialNumber by option("--serial-number", metavar = "SN",
        help = "with --api: the printer's serial number, which flashforge-http sends with each request")
    private val checkCode by option("--check-code", metavar = "CODE",
        help = "with --api: the printer's LAN check code, sent with each request (on the command line it shows in the process list; the machine's connection in the config reads it from the environment or a secrets file instead)")
    private val timeoutS by option("--timeout", metavar = "SECONDS", help = "with --api: how long to wait for the printer (default 8 s, plymeld's choice)").double().default(8.0)
    private val fromResponse by option("--from-response", metavar = "FILE", help = "read a saved /detail answer instead of asking the printer").path(mustExist = true)
    private val saveResponse by option("--save-response", metavar = "FILE", help = "also write the printer's raw answer here (it identifies the printer: address, MAC, file names; do not commit it as it is)").path()
    private val output by option("-o", "--output", metavar = "FILE", help = "write the matched loadout as a config file (TOML) to pass with -cf after your own; your files are never changed").path()

    override fun help(context: com.github.ajalt.clikt.core.Context) =
        "Read what the printer has loaded in each tool or slot and compare it with the machine's loadout in the config (-cf, -cv, --machine): " +
            "for each, the printer's material and color, the config's filament, and which config filament matches (same kind as the printer's material, same color exactly). " +
            "-o writes the matched loadout as a config file. Sends one state request (flashforge-http: POST /detail; moonraker: reads Klipper's save_variables file over SSH, where connection.ssh.user is set) and nothing that changes the printer; " +
            "the connection is the machine's in the config, or --api with --host and the credentials."

    /** [text] written to --save-response when given; the text itself. */
    private fun save(text: String): String {
        saveResponse?.let { out -> out.parent?.let { java.nio.file.Files.createDirectories(it) }; java.nio.file.Files.writeString(out, text); echo("printer's answer saved to $out") }
        return text
    }

    override fun run() {
        val c = config()
        val key = machine(c)
        // the raw /detail answer (Flashforge: for --save-response and the parser), or the loadout straight from the adapter
        val reported: plymeld.printer.ReportedLoadout = fromResponse?.let { FlashforgeHttp.parseDetail(save(java.nio.file.Files.readString(it))) } ?: run {
            if (api != null) {
                check(api == "flashforge-http")
                fun need(v: String?, what: String) = v ?: throw UsageError("$what is needed to ask the printer (or --from-response FILE)")
                FlashforgeHttp.parseDetail(save(FlashforgeHttp(key, need(host, "--host"), port ?: FlashforgeHttp.DEFAULT_PORT,
                    need(serialNumber, "--serial-number"), need(checkCode, "--check-code"),
                    java.time.Duration.ofMillis((timeoutS * 1000).toLong())).detail()))
            } else {
                // no `need` here: an adapter without LOADOUT refuses in its own words (Moonraker says what to set for a shell)
                val p = printer(c)
                when (p) {
                    is FlashforgeHttp -> FlashforgeHttp.parseDetail(save(p.detail()))
                    else -> {
                        if (saveResponse != null) error("printer '$key': --save-response keeps a /detail answer (flashforge-http); ${p.protocol.id} reads its loadout another way")
                        p.loadout()
                    }
                }
            }
        }
        val report = LoadoutSync.compare(c, reported)
        echo(report.text().trimEnd())
        output?.let { out ->
            out.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(out, report.overlay())
            echo("loadout written to $out: pass it with -cf after your own config files")
        }
    }
}
