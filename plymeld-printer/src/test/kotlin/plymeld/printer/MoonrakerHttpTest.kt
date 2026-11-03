package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

/**
 * The Moonraker adapter against synthetic answers shaped as the workspace's
 * scripts read them: each method sends the request the design documents and reads the typed result; every claim has
 * a control. No printer is contacted.
 */
class MoonrakerHttpTest : StringSpec({
    fun res(name: String) = MoonrakerHttpTest::class.java.getResource("/moonraker/$name")!!.readText()
    val printing = res("objects-query-printing.json"); val standby = res("objects-query-standby.json"); val files = res("files-list.json")

    fun printer(key: String = "q2", apiKey: String? = null, camera: String? = null, answer: (HttpCall) -> HttpAnswer): Pair<MoonrakerHttp, Recorded> {
        val t = Recorded(answer)
        return MoonrakerHttp(key, "printer.test", 7125, apiKey, Duration.ofSeconds(8), t, camera) to t
    }
    fun byPath(vararg pairs: Pair<String, String>) = { c: HttpCall -> Recorded.json(pairs.toMap()[c.path] ?: error("no recorded answer for ${c.path}")) }
    val query = "GET " + MoonrakerHttp.QUERY

    "state while printing: the query the status line makes; status, job, progress, layers, elapsed, one nozzle, the bed, both factors" {
        val (p, t) = printer(answer = byPath(MoonrakerHttp.QUERY to printing))
        val s = p.state()
        t.paths shouldContainExactly listOf(query)
        t.sent[0].headers.keys shouldContainExactly emptyList()                              // no key set: no header
        s.key shouldBe "q2"; s.source shouldBe "moonraker /printer/objects/query"
        s.status shouldBe Status.PRINTING; s.rawStatus shouldBe "printing"
        s.job shouldBe Reported.Value("example.gcode")
        s.progress shouldBe Reported.Value(0.598)
        s.layer shouldBe Reported.Value(169); s.layers shouldBe Reported.Value(484)
        s.elapsedS shouldBe Reported.Value(4229.0)
        s.remainingS.text() shouldBe "not reported"
        s.nozzles.map { it.name to it.text() } shouldContainExactly listOf("T1" to "210/210 C")
        s.bed.orNull!!.text() shouldBe "60/60 C"
        s.speedFactor shouldBe Reported.Value(1.0); s.flowFactor shouldBe Reported.Value(1.05)
        s.loadout shouldBe null
        s.notes shouldContainExactly emptyList()
    }

    "state in standby: idle, the job blank and the layers null read not reported; control: the printing answer reports them" {
        val s = printer(answer = byPath(MoonrakerHttp.QUERY to standby)).first.state()
        s.status shouldBe Status.IDLE; s.rawStatus shouldBe "standby"
        s.job shouldBe Reported.NotReported("print_stats.filename is blank")
        s.layer.text() shouldBe "not reported"; s.layers.text() shouldBe "not reported"
        s.text() shouldContain "layer: not reported of not reported"
        printer(answer = byPath(MoonrakerHttp.QUERY to printing)).first.state().layer.text() shouldBe "169"
    }

    "an unknown state word reads UNKNOWN with the word kept; a zero progress while printing earns a note" {
        val odd = printer(answer = byPath(MoonrakerHttp.QUERY to printing.replace("\"state\": \"printing\"", "\"state\": \"dreaming\""))).first.state()
        odd.status shouldBe Status.UNKNOWN; odd.rawStatus shouldBe "dreaming"
        odd.notes shouldContainExactly listOf("print_stats.state \"dreaming\" is not a word plymeld knows for this API")
        val noM73 = printer(answer = byPath(MoonrakerHttp.QUERY to printing.replace("\"progress\": 0.598", "\"progress\": 0.0"))).first.state()
        noM73.notes shouldContainExactly listOf("display_status.progress is 0 while printing: the file carries no M73 progress")
    }

    "files: GET /server/files/list?root=gcodes with path, size and modified time" {
        val (p, t) = printer(answer = byPath("/server/files/list?root=gcodes" to files))
        val fs = p.files()
        t.paths shouldContainExactly listOf("GET /server/files/list?root=gcodes")
        fs.map { it.name } shouldContainExactly listOf("example.gcode", "coupons/plate_a.gcode")
        fs[0].sizeBytes shouldBe Reported.Value(1234567L); fs[0].modifiedEpochS shouldBe Reported.Value(1760000000.5)
    }

    "upload: multipart POST /server/files/upload with root=gcodes, print=false and the file; confirmed by the list; control: not listed = not confirmed" {
        val (p, t) = printer { c -> if (c.path.startsWith("/server/files/list")) Recorded.json(files) else Recorded.json("""{"item": {"path": "example.gcode", "root": "gcodes"}, "action": "create_file"}""") }
        val u = p.upload("example.gcode", "G28\n".toByteArray())
        u.confirmed shouldBe true
        t.paths shouldContainExactly listOf("POST /server/files/upload", "GET /server/files/list?root=gcodes")
        val body = String(t.sent[0].body!!, Charsets.ISO_8859_1)
        body shouldContain "name=\"root\"\r\n\r\ngcodes\r\n"
        body shouldContain "name=\"print\"\r\n\r\nfalse\r\n"
        body shouldContain "name=\"file\"; filename=\"example.gcode\""
        t.sent[0].headers["Content-Type"]!! shouldContain "multipart/form-data; boundary="
        printer { c -> if (c.path.startsWith("/server/files/list")) Recorded.json(files) else Recorded.json("{}") }.first.upload("other.gcode", ByteArray(1)).confirmed shouldBe false
    }

    "start: POST /printer/print/start with the file name URL-encoded, then the state read back" {
        val (p, t) = printer(answer = byPath("/printer/print/start?filename=my+part+%231.gcode" to """{"result": "ok"}""", MoonrakerHttp.QUERY to printing))
        val d = p.start("my part #1.gcode")
        t.paths shouldContainExactly listOf("POST /printer/print/start?filename=my+part+%231.gcode", query)
        d.sent shouldBe "POST /printer/print/start filename=my part #1.gcode"
        d.readBack!!.status shouldBe Status.PRINTING
    }

    "pause, resume and cancel: POST /printer/print/<action>, then the state read back; resume waits through the RESUME macro" {
        for ((call, action) in listOf<Pair<(MoonrakerHttp) -> Done, String>>({ p: MoonrakerHttp -> p.pause() } to "pause", { p: MoonrakerHttp -> p.resume() } to "resume", { p: MoonrakerHttp -> p.cancel() } to "cancel")) {
            val (p, t) = printer(answer = byPath("/printer/print/$action" to """{"result": "ok"}""", MoonrakerHttp.QUERY to printing))
            val d = call(p)
            t.paths shouldContainExactly listOf("POST /printer/print/$action", query)
            t.sent[0].body shouldBe null
            t.sent[0].timeout shouldBe (if (action == "resume") MoonrakerHttp.RESUME_WAIT else Duration.ofSeconds(8))
            d.sent shouldBe "POST /printer/print/$action"
            d.readBack!!.status shouldBe Status.PRINTING
        }
    }

    "a raw command: POST /printer/gcode/script with the script URL-encoded, the answer returned as text" {
        val (p, t) = printer(answer = byPath("/printer/gcode/script?script=SET_PRESSURE_ADVANCE+ADVANCE%3D0.04" to """{"result": "ok"}"""))
        p.rawCommand("SET_PRESSURE_ADVANCE ADVANCE=0.04") shouldBe """{"result": "ok"}"""
        t.paths shouldContainExactly listOf("POST /printer/gcode/script?script=SET_PRESSURE_ADVANCE+ADVANCE%3D0.04")
    }

    "Moonraker's own error is named in the refusal; an unreachable printer gives one line; neither carries the API key" {
        val e = shouldThrow<IllegalStateException> { printer(apiKey = "KEY-TEST") { HttpAnswer.of(400, """{"error": {"code": 400, "message": "Printer is not idle"}}""") }.first.pause() }
        e.message shouldBe "printer 'q2' refused POST /printer/print/pause: HTTP 400 Printer is not idle"
        e.message!! shouldNotContain "KEY-TEST"
        val down = shouldThrow<IllegalStateException> { MoonrakerHttp("q2", "printer.test", 7125, "KEY-TEST", Duration.ofSeconds(1), Recorded.unreachable()).state() }
        down.message shouldBe "printer 'q2': could not reach printer.test:7125 (/printer/objects/query?print_stats&display_status&extruder&heater_bed&gcode_move): Connection refused"
        down.message!! shouldNotContain "KEY-TEST"
    }

    "an API key, when set, rides as X-Api-Key on every request; control: unset, no such header" {
        val (p, t) = printer(apiKey = "KEY-TEST", answer = byPath(MoonrakerHttp.QUERY to standby))
        p.state()
        t.sent[0].headers shouldBe mapOf("X-Api-Key" to "KEY-TEST")
        val (q, u) = printer(answer = byPath(MoonrakerHttp.QUERY to standby))
        q.state()
        u.sent[0].headers shouldBe emptyMap()
    }

    "camera: a snapshot URL answering one JPEG is returned as is; control: no URL in the config is refused naming the setting" {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 'Q'.code.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val (p, t) = printer(camera = "http://printer.test:8080/?action=snapshot") { HttpAnswer.of(200, jpeg, "image/jpeg") }
        p.cameraFrame().jpeg shouldBe jpeg
        p.cameraFrame(3).jpeg shouldBe jpeg                                                 // a snapshot has one image whatever frame is asked
        t.paths shouldContainExactly listOf("GET /?action=snapshot", "GET /?action=snapshot")
        // a stream: the first frame by default on this family, the second with --frame 2
        val f1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), '1'.code.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val f2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), '2'.code.toByte(), 0xFF.toByte(), 0xD9.toByte())
        fun part(f: ByteArray) = "--b\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + f + "\r\n".toByteArray()
        val (s, _) = printer(camera = "http://printer.test:8080/?action=stream") { HttpAnswer.of(200, part(f1) + part(f2), "multipart/x-mixed-replace; boundary=b") }
        s.cameraFrame().jpeg shouldBe f1
        s.cameraFrame(2).jpeg shouldBe f2
        shouldThrow<IllegalStateException> { printer(answer = byPath()).first.cameraFrame() }.message!! shouldContain "set hardware.machine.q2.connection.camera.url"
    }

    "the loadout needs a shell: without one it is not a capability and is refused in one line (the Q2's Box codes are read over SSH; QidiBoxTest)" {
        val (p, _) = printer(answer = byPath())
        p.can(Capability.LOADOUT) shouldBe false
        shouldThrow<IllegalStateException> { p.loadout() }.message!! shouldContain "printer 'q2' cannot read its loadout: Moonraker reports none, and no SSH is configured"
        p.can(Capability.RAW_COMMAND) shouldBe true
    }
})
