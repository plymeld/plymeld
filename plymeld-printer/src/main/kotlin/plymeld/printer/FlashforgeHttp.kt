package plymeld.printer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.Duration

/**
 * Flashforge's LAN HTTP API, as the Creator 5 serves it (firmware 1.9.x), as a [Printer]: JSON POSTs to port 8898, each
 * carrying the printer's serial number and check code (in the body; in headers for the upload). What is built is what
 * other clients were observed doing against a Creator 5, plus the community endpoint map of firmware 1.9.2; nothing was
 * tried against a printer from plymeld's side.
 *
 * - STATE and LOADOUT: `POST /detail` (the loadout reader is the first filament-sync reader, unchanged).
 * - FILES: `POST /gcodeList` (names only on this firmware).
 * - UPLOAD: `POST /uploadGcode`, multipart field `gcodeFile`, the credentials and switches as headers; confirmed by
 *   listing before and after, never by the reply code.
 * - START: `POST /printGcode` with `levelingBeforePrint` (the firmware levels the bed first when true) and the material
 *   mapping when one is given. LEVEL_ON_START: that switch.
 * - PAUSE / RESUME / CANCEL: `POST /control` with `jobCtl_cmd` pause / continue / cancel. `/control` acknowledges
 *   UNKNOWN commands with "Success" (the map, and observed), so the state is read back and reported.
 * - CAMERA: the MJPEG stream (`connection.camera.url`, else `/detail`'s `cameraStreamUrl`: inferred, that field was
 *   seen but no tool uses it), the first frame discarded (it was observed stale). One client at a time.
 * - RAW_COMMAND: refused; `/control` takes only its own commands.
 *
 * The raw `/detail` answer identifies the printer (address, MAC, file names): never commit one as it is.
 */
class FlashforgeHttp(
    override val key: String,
    private val host: String,
    private val port: Int,
    private val serialNumber: String,
    private val checkCode: String,
    private val timeout: Duration,
    private val transport: Transport = JdkHttp(),
    private val cameraUrl: String? = null,
) : Printer, LoadoutReader {
    override val protocol = Protocol.FLASHFORGE_HTTP
    override val capabilities = setOf(Capability.STATE, Capability.LOADOUT, Capability.FILES, Capability.UPLOAD, Capability.START,
        Capability.LEVEL_ON_START, Capability.PAUSE, Capability.RESUME, Capability.CANCEL, Capability.CAMERA)

    private fun url(path: String) = "http://$host:$port$path"
    private fun credentials() = buildJsonObject { put("serialNumber", serialNumber); put("checkCode", checkCode) }

    /** A JSON POST to [path] with the credentials and [extra] fields; the answer's text (HTTP 200 or a refusal). */
    private fun post(path: String, extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String {
        val body = buildJsonObject { put("serialNumber", serialNumber); put("checkCode", checkCode); extra() }
        return postRaw(path, body.toString())
    }

    private fun postRaw(path: String, body: String): String {
        val call = HttpCall("POST", url(path), mapOf("Content-Type" to "application/json"), body.toByteArray(Charsets.UTF_8), timeout)
        val answer = transport.sendOrRefuse(call, key)
        check(answer.status == 200) { "printer '$key' at $host:$port answered $path with HTTP ${answer.status}" }
        return answer.text()
    }

    /** The printer's raw `/detail` answer. */
    fun detail(): String = postRaw("/detail", credentials().toString())

    override fun read(): ReportedLoadout = parseDetail(detail())
    override fun loadout(): ReportedLoadout = read()
    override fun state(): PrinterState = parseState(key, detail())

    override fun files(): List<PrinterFile> = parseFiles(key, postRaw("/gcodeList", credentials().toString()))

    override fun upload(name: String, bytes: ByteArray): Upload {
        val boundary = "----plymeld" + java.util.UUID.randomUUID().toString().replace("-", "")
        val headers = linkedMapOf(
            "Content-Type" to Multipart.contentType(boundary), "serialNumber" to serialNumber, "checkCode" to checkCode,
            "fileSize" to bytes.size.toString(), "printNow" to "false", "levelingBeforePrint" to "false", "flowCalibration" to "false",
            "firstLayerInspection" to "false", "timeLapseVideo" to "false", "useMatlStation" to "false", "gcodeToolCnt" to "0",
            "materialMappings" to "W10=")   // base64 of "[]": no mapping, as an observed client sends
        val body = Multipart.body(boundary, emptyList(), Triple("gcodeFile", name, bytes))
        // an upload takes as long as the file: the timeout grows with it (plymeld's choice: the connection's timeout per megabyte, at least the timeout itself)
        val wait = Duration.ofMillis(maxOf(timeout.toMillis(), timeout.toMillis() * (1 + bytes.size / (1 shl 20))))
        val answer = transport.sendOrRefuse(HttpCall("POST", url("/uploadGcode"), headers, body, wait), key)
        check(answer.status == 200) { "printer '$key' at $host:$port answered /uploadGcode with HTTP ${answer.status}" }
        codeOf(key, "/uploadGcode", answer.text())
        return Upload(name, bytes.size, name in files().map { it.name })
    }

    /**
     * `materialMappings` as the community map describes it (`MaterialMapping`: `toolId` 0-based, `slotId` 1-based,
     * `materialName`, `toolMaterialColor` and `slotMaterialColor` as #RRGGBB): sent on `/printGcode` when a mapping is
     * given (the map says to omit it for a single-tool print; an observed client sends none at all). Read, not tried.
     */
    override fun start(name: String, mapping: ToolMapping?, level: Boolean): Done {
        val entries = mapping?.entries.orEmpty()
        codeOf(key, "/printGcode", post("/printGcode") {
            put("fileName", name); put("levelingBeforePrint", level)
            if (entries.isNotEmpty()) putJsonArray("materialMappings") {
                for (e in entries) addJsonObject {
                    put("toolId", e.toolIndex - 1); put("slotId", e.slot); put("materialName", e.material)
                    put("toolMaterialColor", e.toolColor); put("slotMaterialColor", e.slotColor)
                }
            }
        })
        val sent = "POST /printGcode fileName=$name levelingBeforePrint=$level" +
            (if (entries.isEmpty()) " (no material mapping: the firmware pairs tools and slots its own way)" else " materialMappings: ${mapping!!.text()}")
        return Done(sent, state())
    }

    override fun pause(): Done = job("pause")
    override fun resume(): Done = job("continue")
    override fun cancel(): Done = job("cancel")

    private fun job(action: String): Done {
        val body = buildJsonObject {
            put("serialNumber", serialNumber); put("checkCode", checkCode)
            putJsonObject("payload") { put("cmd", "jobCtl_cmd"); putJsonObject("args") { put("action", action) } }
        }
        codeOf(key, "/control", postRaw("/control", body.toString()))
        return Done("POST /control jobCtl_cmd action=$action (acknowledged whatever happens: judge by the state read back)", state())
    }

    /** The stream's second frame unless [frame] says which: its first was observed stale. */
    override fun cameraFrame(frame: Int?): CameraFrame {
        val url = cameraUrl ?: str(detailObject(key, detail())["cameraStreamUrl"])
            ?: error("printer '$key' names no camera: set hardware.machine.$key.connection.camera.url (its /detail answer has no cameraStreamUrl)")
        return MjpegCamera.frame(transport, url, timeout, skip = (frame ?: DEFAULT_FRAME) - 1, printer = key)
    }

    companion object {
        /** The API's port (the Creator 5's; the community spec and Flash Studio's own traffic both use it). */
        const val DEFAULT_PORT = 8898
        /** Which frame of the camera's stream to return by default: the second, the first being a stale buffered one (observed). */
        const val DEFAULT_FRAME = 2

        private val COLOR = Regex("^#?([0-9A-Fa-f]{6})$")

        /**
         * The printer's status words as plymeld reads them: the recorded answer's `cancel` and the community map's
         * examples. NOT verified which words the firmware emits beyond `cancel`; an unknown word reads UNKNOWN with the
         * word kept.
         */
        private val STATUS = mapOf("ready" to Status.IDLE, "idle" to Status.IDLE, "printing" to Status.PRINTING, "pause" to Status.PAUSED,
            "paused" to Status.PAUSED, "completed" to Status.FINISHED, "complete" to Status.FINISHED, "cancel" to Status.CANCELLED,
            "canceled" to Status.CANCELLED, "canceled" to Status.CANCELLED, "error" to Status.ERROR, "busy" to Status.BUSY)

        /** The JSON of an answer, refused when it is not JSON, not an object, or its `code` is not 0 (naming [path]). */
        private fun codeOf(key: String, path: String, text: String): JsonObject {
            val parsed = try { Json.parseToJsonElement(text) } catch (e: kotlinx.serialization.SerializationException) {
                error("printer '$key': the answer to $path is not JSON: ${e.message}")
            }
            val root = parsed as? JsonObject ?: error("printer '$key': the answer to $path is not a JSON object")
            val code = (root["code"] as? JsonPrimitive)?.intOrNull
            check(code == 0) { "printer '$key' refused $path: code ${root["code"]}, \"${str(root["message"]) ?: ""}\" (a wrong serial number or check code, or LAN mode off, give a non-zero code; 2 = busy)" }
            return root
        }

        private fun detailObject(key: String, text: String): JsonObject =
            codeOf(key, "/detail", text)["detail"] as? JsonObject ?: error("printer '$key': the /detail answer has no `detail` object")

        /**
         * The loadout in a `/detail` answer (the first reader, unchanged). Refused (an error naming why): an answer that
         * is not JSON, one whose `code` is not 0 (a wrong serial number or check code, or LAN mode off), and one that does
         * not say how many nozzles the printer has when it has a material station (which tool each slot feeds is then not
         * known, and is not guessed).
         */
        fun parseDetail(text: String): ReportedLoadout {
            val parsed = try { Json.parseToJsonElement(text) } catch (e: kotlinx.serialization.SerializationException) {
                error("the printer's /detail answer is not JSON: ${e.message}")
            }
            val root = parsed as? JsonObject ?: error("the printer's /detail answer is not a JSON object")
            val code = (root["code"] as? JsonPrimitive)?.intOrNull
            check(code == 0) { "the printer refused /detail: code ${root["code"]}, \"${str(root["message"]) ?: ""}\" (a wrong serial number or check code, or LAN mode off, give a non-zero code)" }
            val d = root["detail"] as? JsonObject ?: error("the printer's /detail answer has no `detail` object")
            val model = str(d["model"]) ?: str(d["name"]) ?: "a Flashforge printer"
            val source = "Flashforge $model${str(d["firmwareVersion"])?.let { " (firmware $it)" } ?: ""}, LAN HTTP API /detail"
            val notes = ArrayList<String>()
            val station = d["matlStationInfo"] as? JsonObject
            val infos = station?.get("slotInfos") as? JsonArray
            if (station == null || infos == null) {
                notes += "the printer reports no material station (matlStationInfo.slotInfos): nothing to read about what is loaded"
                return ReportedLoadout(source, emptyList(), notes)
            }
            // which tool a slot feeds: one slot per nozzle (a tool changer, the Creator 5: slot N feeds tool N) or several
            // slots into one nozzle (a single-nozzle printer with a station). plymeld's choice, from the fields'
            // meaning in the community spec; the 1:1 case agrees with a Creator 5's own answer (see the note).
            val nozzles = (d["nozzleCnt"] as? JsonPrimitive)?.intOrNull
            val slotCount = (station["slotCnt"] as? JsonPrimitive)?.intOrNull ?: infos.size
            val feeds = when {
                nozzles == 1 -> ReportedSlot.Feeds.SLOT
                nozzles != null && nozzles == slotCount -> ReportedSlot.Feeds.TOOL
                else -> error("the printer reports $slotCount material slot(s) and ${nozzles ?: "no count of"} nozzle(s): which tool each slot feeds is not known (plymeld reads one slot per nozzle, or several slots into one nozzle)")
            }
            val slots = infos.mapIndexed { i, el ->
                val s = el as? JsonObject ?: error("matlStationInfo.slotInfos[$i] is not an object")
                val id = (s["slotId"] as? JsonPrimitive)?.intOrNull ?: error("matlStationInfo.slotInfos[$i] has no slotId")
                val loaded = (s["hasFilament"] as? JsonPrimitive)?.booleanOrNull
                val material = str(s["materialName"])
                val rawColor = str(s["materialColor"])
                val color = rawColor?.let { c -> COLOR.matchEntire(c)?.let { "#" + it.groupValues[1].uppercase() } }
                if (rawColor != null && color == null) notes += "slot $id: color \"$rawColor\" is not #RRGGBB; left out"
                if (loaded == false && (material != null || color != null)) notes += "slot $id: reports no filament, yet names ${listOfNotNull(material, color).joinToString(" ")}"
                if (loaded == null) notes += "slot $id: does not say whether filament is loaded (hasFilament)"
                ReportedSlot(id, feeds, loaded, material, color, fields = s.mapValues { (_, v) -> show(v) })
            }
            slots.groupBy { it.position }.filterValues { it.size > 1 }.keys.forEach { notes += "slot $it is reported more than once" }
            return ReportedLoadout(source, slots.sortedBy { it.position }, notes)
        }

        /**
         * The typed state in a `/detail` answer. The fields and their meaning come from observed answers and the
         * community map; a field absent or blank reads not reported. The active tool is NOT a field of `/detail`
         * (checked), so it is not reported; an inference from the nozzle targets (the one target strictly
         * above the rest) is given as a note.
         */
        fun parseState(key: String, text: String): PrinterState {
            val d = detailObject(key, text)
            val notes = ArrayList<String>()
            val model = str(d["model"]) ?: str(d["name"])
            val source = "flashforge-http /detail" + (model?.let { "; Flashforge $it" } ?: "") + (str(d["firmwareVersion"])?.let { ", firmware $it" } ?: "")
            val raw = str(d["status"])
            val status = raw?.let { STATUS[it.lowercase()] } ?: Status.UNKNOWN
            if (raw != null && status == Status.UNKNOWN) notes += "status \"$raw\" is not a word plymeld knows for this API"
            fun num(name: String) = (d[name] as? JsonPrimitive)?.doubleOrNull
            fun int(name: String) = (d[name] as? JsonPrimitive)?.intOrNull
            fun nums(name: String) = (d[name] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            val temps = nums("nozzleTemps"); val targets = nums("nozzleTargetTemps")
            val nozzles = if (temps == null) emptyList() else temps.indices.map { i ->
                Heater("T${i + 1}", Reported.Value(temps[i]), Reported.of(targets?.getOrNull(i), "nozzleTargetTemps has no entry ${i + 1}"))
            }
            if (temps == null) notes += "the answer has no nozzleTemps (older two-nozzle firmware reports leftTemp / rightTemp instead; not read)"
            // the inference: the one target strictly above the rest is the printing tool;
            // a tie (preheat puts all four at one target) is ambiguous and says nothing
            if (targets != null && targets.any { it > 0 }) {
                val top = targets.max(); val leaders = targets.indices.filter { targets[it] == top }
                notes += if (leaders.size == 1) "active tool inferred from the nozzle targets: T${leaders[0] + 1} is the only one above the rest (not a field of /detail)"
                    else "the nozzle targets do not single out a tool (${leaders.size} share the highest target): the active tool is not inferred"
            }
            var loadout: ReportedLoadout? = null
            try { loadout = parseDetail(text) } catch (e: IllegalStateException) { notes += "loadout not read: ${e.message}" }
            return PrinterState(key, source, status, raw,
                job = Reported.of(str(d["printFileName"]), "printFileName is blank"),
                progress = Reported.of(num("printProgress"), "no printProgress field"),
                layer = Reported.of(int("printLayer"), "no printLayer field"), layers = Reported.of(int("targetPrintLayer"), "no targetPrintLayer field"),
                elapsedS = Reported.of(num("printDuration"), "no printDuration field"),
                remainingS = Reported.of(num("estimatedTime"), "no estimatedTime field"),
                activeTool = Reported.NotReported("/detail has no active-tool field"),
                nozzles = nozzles,
                bed = Reported.of(num("platTemp")?.let { Heater("bed", Reported.Value(it), Reported.of(num("platTargetTemp"), "no platTargetTemp field")) }, "no platTemp field"),
                speedFactor = Reported.of(num("printSpeedAdjust")?.let { it / 100 }, "no printSpeedAdjust field"),
                flowFactor = Reported.NotReported("the API reports no flow factor"),
                loadout = loadout, notes = notes)
        }

        /** The names in a `/gcodeList` answer (`gcodeList: [name, ...]` on firmware 1.9.7+, as observed). */
        fun parseFiles(key: String, text: String): List<PrinterFile> {
            val root = codeOf(key, "/gcodeList", text)
            val list = root["gcodeList"] as? JsonArray ?: error("printer '$key': the /gcodeList answer has no gcodeList list")
            return list.mapNotNull { str(it) }.map { PrinterFile(it, Reported.NotReported("/gcodeList gives names only"), Reported.NotReported("/gcodeList gives names only")) }
        }

        /** A JSON string's text, trimmed; null when absent, not a string, or blank. */
        private fun str(e: JsonElement?): String? = (e as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        private fun show(e: JsonElement): String = when (e) {
            is JsonNull -> "null"
            is JsonPrimitive -> e.content
            else -> e.toString()
        }
    }
}
