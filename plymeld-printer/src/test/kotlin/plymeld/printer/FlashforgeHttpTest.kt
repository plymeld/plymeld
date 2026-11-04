package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.time.Duration

/**
 * The Flashforge adapter against the recorded Creator 5 `/detail` answer
 * (anonymised) and synthetic answers for the other endpoints: each method sends the request the design documents
 * (path, body, headers) and reads the typed result; every claim has a control. No printer is contacted.
 */
class FlashforgeHttpTest : StringSpec({
    val detail = FlashforgeHttpTest::class.java.getResource("/flashforge/creator5-detail-2026-10-09.json")!!.readText()
    val ok = """{"code": 0, "message": "Success"}"""
    fun names(vararg names: String) = """{"code": 0, "gcodeList": [${names.joinToString { "\"$it\"" }}], "message": "Success"}"""

    /** A printer whose answers come from [answer] by path; the credentials are test strings. */
    fun printer(key: String = "c5", camera: String? = null, answer: (HttpCall) -> HttpAnswer): Pair<FlashforgeHttp, Recorded> {
        val t = Recorded(answer)
        return FlashforgeHttp(key, "printer.test", 8898, "SN-TEST", "CODE-TEST", Duration.ofSeconds(8), t, camera) to t
    }
    fun byPath(vararg pairs: Pair<String, String>) = { c: HttpCall -> Recorded.json(pairs.toMap()[c.path] ?: error("no recorded answer for ${c.path}")) }

    "state from the recorded answer: canceled, the job, progress, layers, four nozzles, the bed, the speed, flow not reported, the loadout" {
        val (p, t) = printer(answer = byPath("/detail" to detail))
        val s = p.state()
        t.paths shouldContainExactly listOf("POST /detail")
        t.bodyText(0) shouldBe """{"serialNumber":"SN-TEST","checkCode":"CODE-TEST"}"""
        s.key shouldBe "c5"
        s.source shouldBe "flashforge-http /detail; Flashforge Creator 5, firmware 1.9.9"
        s.status shouldBe Status.CANCELLED; s.rawStatus shouldBe "cancel"
        s.job shouldBe Reported.Value("example.gcode")
        s.progress.orNull!! shouldBe (0.598067581653595)
        s.layer shouldBe Reported.Value(169); s.layers shouldBe Reported.Value(484)
        s.elapsedS shouldBe Reported.Value(4229.0); s.remainingS shouldBe Reported.Value(3944.0)
        s.nozzles.map { it.name to it.text() } shouldContainExactly listOf("T1" to "17/0 C", "T2" to "19/0 C", "T3" to "21/0 C", "T4" to "21/0 C")
        s.bed.orNull!!.text() shouldBe "19/0 C"
        s.speedFactor shouldBe Reported.Value(1.0)
        s.flowFactor shouldBe Reported.NotReported("the API reports no flow factor")
        s.activeTool shouldBe Reported.NotReported("/detail has no active-tool field")
        s.loadout!!.slots.map { it.position to it.loaded } shouldContainExactly listOf(1 to false, 2 to false, 3 to true, 4 to true)
        s.notes shouldContainExactly emptyList()                                            // nothing hot: no inference
        s.text() shouldContain "status: canceled (the printer's word: \"cancel\")"
        s.text() shouldContain "flow: not reported"
        s.text() shouldContain "loaded in tool 3: PLA #FFFFFF"
    }

    "the active tool is a note from the nozzle targets, never a value; control: a tie says so and infers nothing" {
        fun with(targets: String) = printer(answer = byPath("/detail" to detail.replace("\"nozzleTargetTemps\": [0, 0, 0, 0]", "\"nozzleTargetTemps\": [$targets]"))).first.state()
        val one = with("120, 120, 210, 120")
        one.activeTool shouldBe Reported.NotReported("/detail has no active-tool field")
        one.notes shouldContainExactly listOf("active tool inferred from the nozzle targets: T3 is the only one above the rest (not a field of /detail)")
        with("120, 120, 120, 120").notes shouldContainExactly listOf("the nozzle targets do not single out a tool (4 share the highest target): the active tool is not inferred")
    }

    "a status word plymeld knows maps; control: an unknown word reads UNKNOWN with the word kept and a note" {
        fun status(w: String) = printer(answer = byPath("/detail" to detail.replace("\"status\": \"cancel\"", "\"status\": \"$w\""))).first.state()
        status("printing").status shouldBe Status.PRINTING
        status("ready").status shouldBe Status.IDLE
        status("completed").status shouldBe Status.FINISHED
        val odd = status("defrosting")
        odd.status shouldBe Status.UNKNOWN; odd.rawStatus shouldBe "defrosting"
        odd.notes shouldContainExactly listOf("status \"defrosting\" is not a word plymeld knows for this API")
    }

    "a field the answer lacks reads not reported; control: present, it reads the value" {
        val gone = printer(answer = byPath("/detail" to detail.replace("\"printLayer\": 169,", ""))).first.state()
        gone.layer shouldBe Reported.NotReported("no printLayer field")
        gone.layers shouldBe Reported.Value(484)
        gone.text() shouldContain "layer: not reported of 484"
    }

    "files: POST /gcodeList with the credentials, names only" {
        val (p, t) = printer(answer = byPath("/gcodeList" to names("a.gcode", "b.gcode")))
        p.files().map { it.name } shouldContainExactly listOf("a.gcode", "b.gcode")
        p.files().first().sizeBytes shouldBe Reported.NotReported("/gcodeList gives names only")
        t.paths.first() shouldBe "POST /gcodeList"
        t.bodyText(0) shouldContain "\"serialNumber\":\"SN-TEST\""
    }

    "upload: multipart POST /uploadGcode with the credentials and switches as headers, confirmed by the list after; control: not listed = not confirmed" {
        var listed = names("old.gcode")
        val (p, t) = printer { c -> if (c.path == "/gcodeList") Recorded.json(listed) else { listed = names("old.gcode", "new.gcode"); Recorded.json(ok) } }
        val u = p.upload("new.gcode", "G28\nG1 X1\n".toByteArray())
        u.confirmed shouldBe true; u.bytes shouldBe 10
        t.paths shouldContainExactly listOf("POST /uploadGcode", "POST /gcodeList")
        val up = t.sent[0]
        up.headers["serialNumber"] shouldBe "SN-TEST"; up.headers["checkCode"] shouldBe "CODE-TEST"
        up.headers["fileSize"] shouldBe "10"; up.headers["printNow"] shouldBe "false"; up.headers["levelingBeforePrint"] shouldBe "false"
        up.headers["materialMappings"] shouldBe "W10="
        up.headers["Content-Type"]!! shouldContain "multipart/form-data; boundary=----plymeld"
        val body = String(up.body!!, Charsets.ISO_8859_1)
        body shouldContain "Content-Disposition: form-data; name=\"gcodeFile\"; filename=\"new.gcode\""
        body shouldContain "G28\nG1 X1\n"
        // control: a printer whose list never gains the file -> confirmed false, whatever the reply code said
        val (q, _) = printer { c -> if (c.path == "/gcodeList") Recorded.json(names("old.gcode")) else Recorded.json(ok) }
        q.upload("new.gcode", ByteArray(3)).confirmed shouldBe false
    }

    "start: POST /printGcode with the file name and levelling off, then the state read back; control: code 2 (busy) is refused" {
        val (p, t) = printer(answer = byPath("/printGcode" to ok, "/detail" to detail))
        val d = p.start("new.gcode")
        t.paths shouldContainExactly listOf("POST /printGcode", "POST /detail")
        t.bodyText(0) shouldBe """{"serialNumber":"SN-TEST","checkCode":"CODE-TEST","fileName":"new.gcode","levelingBeforePrint":false}"""
        d.sent shouldBe "POST /printGcode fileName=new.gcode levelingBeforePrint=false (no material mapping: the firmware pairs tools and slots its own way)"
        d.readBack!!.status shouldBe Status.CANCELLED
        val busy = shouldThrow<IllegalStateException> { printer(answer = byPath("/printGcode" to """{"code": 2, "message": "busy"}""")).first.start("x.gcode") }
        busy.message!! shouldContain "printer 'c5' refused /printGcode: code 2, \"busy\""
        busy.message!! shouldNotContain "CODE-TEST"
    }

    "start with levelling asked: levelingBeforePrint true in the request and in what it says it sent; control: the default sends false" {
        val (p, t) = printer(answer = byPath("/printGcode" to ok, "/detail" to detail))
        p.can(Capability.LEVEL_ON_START) shouldBe true
        val d = p.start("new.gcode", level = true)
        t.bodyText(0) shouldBe """{"serialNumber":"SN-TEST","checkCode":"CODE-TEST","fileName":"new.gcode","levelingBeforePrint":true}"""
        d.sent shouldContain "levelingBeforePrint=true"
        val (_, t2) = printer(answer = byPath("/printGcode" to ok, "/detail" to detail)).also { it.first.start("new.gcode") }
        t2.bodyText(0) shouldContain "\"levelingBeforePrint\":false"
    }

    "pause, resume and cancel: POST /control jobCtl_cmd with the action, then the state read back" {
        for ((call, action) in listOf<Pair<(FlashforgeHttp) -> Done, String>>({ p: FlashforgeHttp -> p.pause() } to "pause", { p: FlashforgeHttp -> p.resume() } to "continue", { p: FlashforgeHttp -> p.cancel() } to "cancel")) {
            val (p, t) = printer(answer = byPath("/control" to ok, "/detail" to detail))
            val d = call(p)
            t.paths shouldContainExactly listOf("POST /control", "POST /detail")
            t.bodyText(0) shouldBe """{"serialNumber":"SN-TEST","checkCode":"CODE-TEST","payload":{"cmd":"jobCtl_cmd","args":{"action":"$action"}}}"""
            d.sent shouldContain "action=$action"
            d.readBack!!.rawStatus shouldBe "cancel"
        }
    }

    "a refused request and an unreachable printer each give one line naming the key, never the check code" {
        val refused = shouldThrow<IllegalStateException> { printer(answer = byPath("/detail" to """{"code": -2, "message": "LAN mode off"}""")).first.state() }
        refused.message shouldBe "printer 'c5' refused /detail: code -2, \"LAN mode off\" (a wrong serial number or check code, or LAN mode off, give a non-zero code; 2 = busy)"
        val down = shouldThrow<IllegalStateException> { FlashforgeHttp("c5", "printer.test", 8898, "SN-TEST", "CODE-TEST", Duration.ofSeconds(1), Recorded.unreachable()).files() }
        down.message shouldBe "printer 'c5': could not reach printer.test:8898 (/gcodeList): Connection refused"
        down.message!! shouldNotContain "CODE-TEST"
        // control: the loadout reader's own refusal wording is unchanged
        shouldThrow<IllegalStateException> { FlashforgeHttp.parseDetail("""{"code": -2}""") }.message!! shouldContain "the printer refused /detail: code -2"
    }

    "camera: the MJPEG stream from the configured URL, the first (stale) frame skipped; control: with no URL and no cameraStreamUrl, refused" {
        val frame1 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 'A'.code.toByte(), 0xFF.toByte(), 0xD9.toByte())
        val frame2 = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 'B'.code.toByte(), 'B'.code.toByte(), 0xFF.toByte(), 0xD9.toByte())
        fun part(f: ByteArray) = "--boundarydonotcross\r\nContent-Type: image/jpeg\r\nContent-Length: ${f.size}\r\n\r\n".toByteArray() + f + "\r\n".toByteArray()
        val stream = part(frame1) + part(frame2) + part(frame1)
        val (p, t) = printer(camera = "http://printer.test:8080/?action=stream") { HttpAnswer.of(200, stream, "multipart/x-mixed-replace; boundary=boundarydonotcross") }
        p.cameraFrame().jpeg shouldBe frame2
        t.paths shouldContainExactly listOf("GET /?action=stream")
        // --frame N chooses: the first (stale) frame when asked for, the third as well; control: the default is the second
        p.cameraFrame(1).jpeg shouldBe frame1
        p.cameraFrame(3).jpeg shouldBe frame1
        p.cameraFrame(FlashforgeHttp.DEFAULT_FRAME).jpeg shouldBe frame2
        // the URL from /detail's cameraStreamUrl when the config names none (inferred: documented so)
        val (q, u) = printer { c -> if (c.path == "/detail") Recorded.json(detail) else HttpAnswer.of(200, stream, "multipart/x-mixed-replace; boundary=b") }
        q.cameraFrame().jpeg shouldBe frame2
        u.sent[1].url shouldBe "http://192.0.2.10:8080/?action=stream"
        // control: neither -> refused naming the setting
        val none = printer { Recorded.json(detail.replace("\"cameraStreamUrl\": \"http://192.0.2.10:8080/?action=stream\",", "")) }.first
        shouldThrow<IllegalStateException> { none.cameraFrame() }.message!! shouldContain "set hardware.machine.c5.connection.camera.url"
    }

    "a raw command is not a capability of this API: refused in one line; control: the capability set says so" {
        val (p, _) = printer(answer = byPath())
        p.can(Capability.RAW_COMMAND) shouldBe false
        shouldThrow<IllegalStateException> { p.rawCommand("G28") }.message shouldBe "printer 'c5' cannot send a raw command: flashforge-http has no way plymeld knows"
        p.can(Capability.LOADOUT) shouldBe true
    }
})
