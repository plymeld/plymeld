package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import java.nio.file.Files

/**
 * The `plymeld printer` subcommands over the interface, run in-process as
 * the UI runs them and without any network: `status --from-response` prints the typed state of a saved answer through
 * the machine's protocol; a machine whose adapter lacks the capability, a machine with no connection, a credential whose
 * variable is unset, each refuse in one line naming the machine by its KEY (never its model) with exit status 1; every
 * subcommand's help says what it sends. Controls: the same commands on the machine that can.
 */
class PrinterCommandsTest : StringSpec({
    val d = Files.createTempDirectory("plymeld-printer-cli")
    fun machine(key: String, model: String, connection: String) = """
        [hardware.machine.$key]
        model = "$model"
        build.x = "200 mm"
        build.y = "200 mm"
        build.z = "200 mm"
        firmware.flavor = "klipper"
        $connection

    """.trimIndent()
    Files.writeString(d.resolve("hw.toml"),
        machine("shop-a", "flashforge-c5", "connection.api = \"flashforge-http\"\nconnection.host = \"a.test\"\n" +
            "connection.credential.serial_number.env = \"PLYMELD_TEST_UNSET_SN\"\nconnection.credential.check_code.env = \"PLYMELD_TEST_UNSET_CODE\"") +
        machine("shop-b", "qidi-q2", "connection.api = \"moonraker\"\nconnection.host = \"b.test\"") +
        machine("bare", "flashforge-c5", ""))
    val cfg = d.resolve("hw.toml").toString()
    val standby = d.resolve("standby.json").also {
        Files.writeString(it, """{"result": {"status": {"print_stats": {"filename": "", "print_duration": 0.0, "state": "standby", "info": {"total_layer": null, "current_layer": null}},
            "display_status": {"progress": 0.0}, "extruder": {"temperature": 24.0, "target": 0.0}, "heater_bed": {"temperature": 23.5, "target": 0.0}, "gcode_move": {"speed_factor": 1.0, "extrude_factor": 1.0}}}}""")
    }
    val detail = d.resolve("detail.json").also {
        Files.writeString(it, """{"code": 0, "message": "Success", "detail": {"model": "Creator 5", "firmwareVersion": "9.9.9", "status": "printing", "printFileName": "part.gcode",
            "printProgress": 0.25, "printLayer": 10, "targetPrintLayer": 40, "printDuration": 65, "estimatedTime": 195, "nozzleCnt": 2, "nozzleTemps": [210, 30], "nozzleTargetTemps": [210, 0],
            "platTemp": 60, "platTargetTemp": 60, "printSpeedAdjust": 100, "matlStationInfo": {"slotCnt": 2, "slotInfos": [{"slotId": 1, "hasFilament": true, "materialName": "PLA", "materialColor": "#FFFFFF"}, {"slotId": 2, "hasFilament": false, "materialName": "", "materialColor": ""}]}}}""")
    }

    /** Runs the command in-process; the exit status with the lines printed and the error lines. */
    fun run(vararg args: String): Triple<Int, List<String>, List<String>> {
        val out = ArrayList<String>(); val err = ArrayList<String>()
        val status = Commands.invoke(args.toList(), { out += it }, { err += it })
        return Triple(status, out, err)
    }

    "status --from-response prints the typed state through the machine's protocol, naming the printer by its key; nothing is sent" {
        val (status, out, err) = run("printer", "status", "-cf", cfg, "--machine", "shop-b", "--from-response", standby.toString())
        err.shouldBeEmpty(); status shouldBe 0
        val text = out.joinToString("\n")
        text shouldContain "printer: shop-b (moonraker /printer/objects/query)"
        text shouldContain "status: idle (the printer's word: \"standby\")"
        text shouldContain "layer: not reported of not reported"
        text shouldContain "remaining: not reported"
        text shouldNotContain "Creator 5"                                                   // the model is not the printer's name
        // control: the Flashforge one, with the same command, reads its own answer: the model is a detail after the key
        val (s2, out2, _) = run("printer", "status", "-cf", cfg, "--machine", "shop-a", "--from-response", detail.toString())
        s2 shouldBe 0
        val t2 = out2.joinToString("\n")
        t2 shouldContain "printer: shop-a (flashforge-http /detail; Flashforge Creator 5, firmware 9.9.9)"
        t2 shouldContain "status: printing"
        t2 shouldContain "layer: 10 of 40"
        t2 shouldContain "nozzles: T1 210/210 C, T2 30/0 C"
        t2 shouldContain "flow: not reported"
        t2 shouldContain "loaded in tool 1: PLA #FFFFFF"
        t2 shouldContain "note: active tool inferred from the nozzle targets: T1 is the only one above the rest"
    }

    "a credential whose variable is unset is refused in one line naming the key and the variable, exit 1; nothing of the model or the value in it" {
        val (status, out, err) = run("printer", "status", "-cf", cfg, "--machine", "shop-a")
        status shouldBe 1; out.shouldBeEmpty()
        err shouldContain "plymeld: printer 'shop-a': credential 'serial_number' comes from environment variable PLYMELD_TEST_UNSET_SN, and none has it"
    }

    "a capability the machine's adapter lacks is refused in one line, exit 1 (loadout on a Moonraker machine)" {
        val (status, _, err) = run("printer", "loadout", "-cf", cfg, "--machine", "shop-b")
        status shouldBe 1
        err shouldContain "plymeld: printer 'shop-b' cannot read its loadout: Moonraker reports none, and no SSH is configured to read the printer's save_variables file (set hardware.machine.shop-b.connection.ssh.user and a credential)"
    }

    "a machine with no connection is refused naming the paths to set; control: the connected one gets past that to its credentials" {
        val (status, _, err) = run("printer", "pause", "-cf", cfg, "--machine", "bare")
        status shouldBe 1
        err shouldContain "plymeld: machine 'bare' has no connection: set hardware.machine.bare.connection.api (one of flashforge-http, moonraker) and hardware.machine.bare.connection.host"
        run("printer", "pause", "-cf", cfg, "--machine", "shop-a").third.single() shouldContain "credential 'serial_number'"
    }

    "send --print refuses, before anything is sent, a file using a tool with no filament loaded; control: a file using only loaded tools gets past that to the connection" {
        val part = d.resolve("two_tools.gcode").also { Files.writeString(it, "G28\nT0\nG1 X1 E1\nT1\nG1 X2 E1\n") }
        val loadout = d.resolve("loadout.toml").also { Files.writeString(it, """
            [hardware.tool.b1]
            machine = "shop-b"
            index = "1"
            bore = "0.4 mm"
            [hardware.filament.white]
            like = "pla-generic"
            color = "#FFFFFF"
            [hardware.machine.shop-b.loadout]
            tool.b1.filament = "white"
        """.trimIndent()) }
        val (status, out, err) = run("printer", "send", part.toString(), "--print", "-cf", cfg, "-cf", loadout.toString(), "--machine", "shop-b")
        status shouldBe 1; out.shouldBeEmpty()
        err shouldContain "plymeld: printer 'shop-b': the file uses T1 (tool 2), but machine 'shop-b' loads no filament in tool 2 (hardware.machine.shop-b.loadout.tool.<key>.filament): not starting it"
        val one = d.resolve("one_tool.gcode").also { Files.writeString(it, "G28\nT0\nG1 X1 E1\n") }
        val (s2, out2, err2) = run("printer", "send", one.toString(), "--print", "-cf", cfg, "-cf", loadout.toString(), "--machine", "shop-b")
        s2 shouldBe 1
        out2 shouldContain "printer 'shop-b': its API reports no loadout, so what is loaded is not checked"   // said once where there is no LOADOUT
        err2.single() shouldContain "could not reach b.test:7125"                          // past the loadout check, at the network
    }

    "camera refuses in one line where the machine names no camera URL, before any connection; the output file is not written" {
        val out = d.resolve("frame.jpg")
        val (status, _, err) = run("printer", "camera", "-o", out.toString(), "-cf", cfg, "--machine", "shop-b")
        status shouldBe 1
        err shouldContain "plymeld: printer 'shop-b' names no camera: set hardware.machine.shop-b.connection.camera.url (Moonraker's own webcam list is not asked for)"
        Files.exists(out) shouldBe false
        // --frame 0 is a usage refusal before anything else
        run("printer", "camera", "-o", out.toString(), "--frame", "0", "-cf", cfg, "--machine", "shop-b").third.single() shouldContain "--frame counts from 1"
    }

    "send refuses a file that does not exist before touching any connection; several machines and none named is a usage error" {
        val (status, _, err) = run("printer", "send", d.resolve("nothing.gcode").toString(), "-cf", cfg, "--machine", "shop-b")
        status shouldBe 1
        err.joinToString("\n") shouldContain "nothing.gcode"
        val (s2, _, err2) = run("printer", "files", "-cf", cfg)
        s2 shouldBe 1
        err2.joinToString("\n") shouldContain "--machine"
    }

    "every printer subcommand's help says what it sends, and the printer command is in the model with its nine subcommands" {
        val printer = CommandModel.read().root.subcommands.single { it.name == "printer" }
        printer.subcommands.map { it.name } shouldBe listOf("status", "watch", "camera", "files", "send", "pause", "resume", "cancel", "loadout")
        for (c in printer.subcommands) c.help shouldContain "Sends "
        printer.subcommands.single { it.name == "send" }.options.flatMap { it.names } shouldContain "--print"
    }
})
