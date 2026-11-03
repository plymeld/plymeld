package plymeld.printer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Duration

/**
 * Moonraker's HTTP API on a Klipper machine (the QIDI Q2; most Klipper printers), as a [Printer]
 *: HTTP on port 7125, no credential unless the user set an API key (sent as
 * `X-Api-Key`: Moonraker's documented optional header, from recollection, not observed, not tried).
 * What is built is what other clients were observed doing against a QIDI Q2; nothing was tried against a printer from
 * plymeld's side.
 *
 * - STATE: `GET /printer/objects/query?print_stats&display_status&extruder&heater_bed&gcode_move`.
 * - FILES: `GET /server/files/list?root=gcodes`.
 * - UPLOAD: `POST /server/files/upload`, multipart (`root=gcodes`, `print=false`, `file`); confirmed by the list.
 * - START: `POST /printer/print/start?filename=NAME`.
 * - PAUSE / RESUME / CANCEL: `POST /printer/print/pause` / `resume` / `cancel`. Resume returns only when the RESUME
 *   macro has run (on the Q2: a reheat and a purge, minutes), so it waits [RESUME_WAIT].
 * - RAW_COMMAND: `POST /printer/gcode/script?script=...` (the Q2's `SET_PAUSE_AT_LAYER` is NOT a registered command
 *   there: it returns without effect, as observed).
 * - CAMERA: `connection.camera.url` (a snapshot URL such as `/?action=snapshot` on port 8080, or a stream). The client
 *   observed fetched it on the printer itself over SSH; a direct GET from the LAN is inferred from its config example.
 * - LOADOUT: Moonraker has no standard loaded-filament object; the QIDI Box's slot codes are read from Klipper's
 *   `save_variables` file over SSH ([SshTransport], [QidiBoxVariables]) when the connection names an SSH user,
 *   refused in one line otherwise.
 */
class MoonrakerHttp(
    override val key: String,
    private val host: String,
    private val port: Int,
    private val apiKey: String?,
    private val timeout: Duration,
    private val transport: Transport = JdkHttp(),
    private val cameraUrl: String? = null,
    /** The printer's shell, when the config sets `connection.ssh.user`; null = no LOADOUT. Opened per read, closed after. */
    private val ssh: (() -> SshTransport)? = null,
    /** Klipper's `save_variables` file the Box's slot codes are in (`connection.ssh.variables_file`). */
    private val variablesFile: String = SshSettings.QIDI_VARIABLES_FILE,
) : Printer {
    override val protocol = Protocol.MOONRAKER
    override val capabilities = setOf(Capability.STATE, Capability.FILES, Capability.UPLOAD, Capability.START, Capability.PAUSE,
        Capability.RESUME, Capability.CANCEL, Capability.CAMERA, Capability.RAW_COMMAND) + (if (ssh != null) setOf(Capability.LOADOUT) else emptySet())

    /**
     * The QIDI Box's slots, read from Klipper's `save_variables` file over SSH ([QidiBoxVariables]).
     * Refused in one line when the machine's connection names no SSH user.
     */
    override fun loadout(): ReportedLoadout {
        val open = ssh ?: error("printer '$key' cannot read its loadout: Moonraker reports none, and no SSH is configured to read the printer's save_variables file (set hardware.machine.$key.connection.ssh.user and a credential)")
        val text = open().use { it.read(variablesFile) }
        return QidiBoxVariables.parse(text, "moonraker over SSH: $variablesFile ([Variables] of Klipper's save_variables, the QIDI Box's slot codes)")
    }

    private fun url(path: String) = "http://$host:$port$path"
    private fun headers(vararg more: Pair<String, String>): Map<String, String> = (apiKey?.let { listOf("X-Api-Key" to it) } ?: emptyList()).plus(more).toMap()

    /** [method] [path], the answer's text; a non-2xx answer is a refusal naming the request and Moonraker's own message. */
    private fun call(method: String, path: String, body: ByteArray? = null, contentType: String? = null, wait: Duration = timeout): String {
        val h = if (contentType == null) headers() else headers("Content-Type" to contentType)
        val answer = transport.sendOrRefuse(HttpCall(method, url(path), h, body, wait), key)
        val text = answer.text()
        check(answer.status in 200..299) { "printer '$key' refused $method ${path.substringBefore('?')}: HTTP ${answer.status}${moonrakerMessage(text)?.let { " $it" } ?: ""}" }
        return text
    }

    override fun state(): PrinterState = parseState(key, call("GET", QUERY))

    override fun files(): List<PrinterFile> = parseFiles(key, call("GET", "/server/files/list?root=gcodes"))

    override fun upload(name: String, bytes: ByteArray): Upload {
        val boundary = "----plymeld" + java.util.UUID.randomUUID().toString().replace("-", "")
        val body = Multipart.body(boundary, listOf("root" to "gcodes", "print" to "false"), Triple("file", name, bytes))
        // an upload takes as long as the file: the timeout grows with it (plymeld's choice: the connection's timeout per megabyte, at least the timeout itself)
        val wait = Duration.ofMillis(maxOf(timeout.toMillis(), timeout.toMillis() * (1 + bytes.size / (1 shl 20))))
        call("POST", "/server/files/upload", body, Multipart.contentType(boundary), wait)
        return Upload(name, bytes.size, name in files().map { it.name })
    }

    /** Moonraker's start takes no tool mapping: on a Klipper machine which slot a `T<n>` loads is the printer's own macros' business (the QIDI Box: its `value_t<n>` variables). */
    override fun start(name: String, mapping: ToolMapping?): Done {
        call("POST", "/printer/print/start?filename=" + URLEncoder.encode(name, Charsets.UTF_8))
        val note = if (mapping == null || mapping.entries.isEmpty()) "" else " (the tool mapping is not sent: Moonraker's start takes none; the printer's own macros decide which slot each T<n> loads)"
        return Done("POST /printer/print/start filename=$name$note", state())
    }

    override fun pause(): Done { call("POST", "/printer/print/pause"); return Done("POST /printer/print/pause", state()) }
    override fun resume(): Done { call("POST", "/printer/print/resume", wait = RESUME_WAIT); return Done("POST /printer/print/resume", state()) }
    override fun cancel(): Done { call("POST", "/printer/print/cancel"); return Done("POST /printer/print/cancel", state()) }

    override fun rawCommand(command: String): String =
        call("POST", "/printer/gcode/script?script=" + URLEncoder.encode(command, Charsets.UTF_8))

    /** A snapshot URL's one image, or a stream's first frame unless [frame] says which (nothing stale was observed on this family's camera). */
    override fun cameraFrame(frame: Int?): CameraFrame {
        val url = cameraUrl ?: error("printer '$key' names no camera: set hardware.machine.$key.connection.camera.url (Moonraker's own webcam list is not asked for)")
        return MjpegCamera.frame(transport, url, timeout, skip = (frame ?: 1) - 1, printer = key)
    }

    companion object {
        /** The API's port. */
        const val DEFAULT_PORT = 7125
        /** The state query an observed client makes, without its QIDI-specific `save_variables`. */
        const val QUERY = "/printer/objects/query?print_stats&display_status&extruder&heater_bed&gcode_move"
        /**
         * How long a resume may take: the RESUME macro runs to its end before Moonraker answers, and on the Q2 that is a
         * reheat and a purge. An observed client waits 900 s; the same here (a placeholder).
         */
        val RESUME_WAIT: Duration = Duration.ofSeconds(900)

        /** `print_stats.state` words, as observed clients test for them (all six). */
        private val STATUS = mapOf("standby" to Status.IDLE, "printing" to Status.PRINTING, "paused" to Status.PAUSED,
            "complete" to Status.FINISHED, "canceled" to Status.CANCELLED, "error" to Status.ERROR)

        private fun parse(key: String, what: String, text: String): JsonObject {
            val parsed = try { Json.parseToJsonElement(text) } catch (e: kotlinx.serialization.SerializationException) {
                error("printer '$key': the answer to $what is not JSON: ${e.message}")
            }
            return parsed as? JsonObject ?: error("printer '$key': the answer to $what is not a JSON object")
        }

        /** Moonraker's error message in a failed answer (`{"error": {"code": 400, "message": "..."}}`), if the body is that. */
        private fun moonrakerMessage(text: String): String? {
            val root = try { Json.parseToJsonElement(text) as? JsonObject } catch (e: kotlinx.serialization.SerializationException) { return null }
            return str((root?.get("error") as? JsonObject)?.get("message"))
        }

        /** The typed state in an objects-query answer: `result.status.{print_stats, display_status, extruder, heater_bed, gcode_move}`. */
        fun parseState(key: String, text: String): PrinterState {
            val root = parse(key, "the state query", text)
            val status = (root["result"] as? JsonObject)?.get("status") as? JsonObject ?: error("printer '$key': the state query's answer has no result.status")
            val notes = ArrayList<String>()
            val ps = status["print_stats"] as? JsonObject
            val info = ps?.get("info") as? JsonObject
            val ds = status["display_status"] as? JsonObject
            val ext = status["extruder"] as? JsonObject
            val bed = status["heater_bed"] as? JsonObject
            val gm = status["gcode_move"] as? JsonObject
            if (ps == null) notes += "the answer has no print_stats object"
            val raw = str(ps?.get("state"))
            val st = raw?.let { STATUS[it.lowercase()] } ?: Status.UNKNOWN
            if (raw != null && st == Status.UNKNOWN) notes += "print_stats.state \"$raw\" is not a word plymeld knows for this API"
            fun num(o: JsonObject?, name: String) = (o?.get(name) as? JsonPrimitive)?.doubleOrNull
            fun int(o: JsonObject?, name: String) = (o?.get(name) as? JsonPrimitive)?.intOrNull
            val progress = num(ds, "progress")
            if (st == Status.PRINTING && progress == 0.0) notes += "display_status.progress is 0 while printing: the file carries no M73 progress"
            val nozzles = if (ext == null) emptyList() else listOf(Heater("T1", Reported.of(num(ext, "temperature"), "extruder has no temperature"), Reported.of(num(ext, "target"), "extruder has no target")))
            return PrinterState(key, "moonraker /printer/objects/query", st, raw,
                job = Reported.of(str(ps?.get("filename")), "print_stats.filename is blank"),
                progress = Reported.of(progress, "no display_status.progress"),
                layer = Reported.of(int(info, "current_layer"), "print_stats.info has no current_layer (the file's SET_PRINT_STATS_INFO sets it)"),
                layers = Reported.of(int(info, "total_layer"), "print_stats.info has no total_layer (the file's SET_PRINT_STATS_INFO sets it)"),
                elapsedS = Reported.of(num(ps, "print_duration"), "no print_stats.print_duration"),
                remainingS = Reported.NotReported("Moonraker reports no remaining time (the slicer's estimate is in the file's metadata, not asked for)"),
                activeTool = Reported.NotReported("the query asks for one extruder only (toolhead.extruder is not asked for)"),
                nozzles = nozzles,
                bed = Reported.of(num(bed, "temperature")?.let { Heater("bed", Reported.Value(it), Reported.of(num(bed, "target"), "heater_bed has no target")) }, "no heater_bed.temperature"),
                speedFactor = Reported.of(num(gm, "speed_factor"), "no gcode_move.speed_factor"),
                flowFactor = Reported.of(num(gm, "extrude_factor"), "no gcode_move.extrude_factor"),
                loadout = null, notes = notes)
        }

        /** The files in a `/server/files/list` answer: `result: [{path, size, modified}]`. */
        fun parseFiles(key: String, text: String): List<PrinterFile> {
            val root = parse(key, "/server/files/list", text)
            val list = root["result"] as? JsonArray ?: error("printer '$key': the /server/files/list answer has no result list")
            return list.map { e ->
                val o = e as? JsonObject ?: error("printer '$key': an entry of /server/files/list is not an object")
                PrinterFile(str(o["path"]) ?: error("printer '$key': an entry of /server/files/list has no path"),
                    Reported.of((o["size"] as? JsonPrimitive)?.longOrNull, "no size"), Reported.of((o["modified"] as? JsonPrimitive)?.doubleOrNull, "no modified time"))
            }
        }

        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
    }
}
