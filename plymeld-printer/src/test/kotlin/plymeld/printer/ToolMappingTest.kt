package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import java.time.Duration

/**
 * The tool mapping a start carries: the tools a file uses
 * are read from its `T<n>` lines (controls: a `T` in a comment or another command does not count); the mapping comes
 * from the machine's loadout with the printer's slot color (control: the config's color when the printer reports
 * none); a used tool with no filament loaded, or a filament without a kind or a color, is refused by name; the
 * Flashforge adapter sends `materialMappings` exactly as the community map shapes it (control: none without a
 * mapping), and the Moonraker adapter sends none and says so.
 */
class ToolMappingTest : StringSpec({
    fun config(vararg extra: Pair<String, Any>, drop: (String) -> Boolean = { false }): Config {
        val base = listOf("hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "256 mm", "hardware.machine.m.build.y" to "256 mm",
            "hardware.machine.m.build.z" to "256 mm", "hardware.machine.m.firmware.flavor" to "klipper",
            "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
            "hardware.tool.t2.like" to "t1", "hardware.tool.t2.index" to 2L, "hardware.tool.t3.like" to "t1", "hardware.tool.t3.index" to 3L,
            "hardware.filament.white.like" to "pla-generic", "hardware.filament.white.color" to "#ffffff",
            "hardware.filament.black.like" to "pla-generic", "hardware.filament.black.color" to "#1B1B1B",
            "hardware.filament.nocolour.like" to "pla-generic",
            "hardware.machine.m.loadout.tool.t1.filament" to "white", "hardware.machine.m.loadout.tool.t3.filament" to "black")
        return Config(ConfigStack(ConfigFiles.bundled() + (base.filterNot { drop(it.first) } + extra).map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }), emptyList())
    }
    val reported = ReportedLoadout("test", listOf(ReportedSlot(1, ReportedSlot.Feeds.TOOL, true, "PLA", "#FEFEFE"), ReportedSlot(3, ReportedSlot.Feeds.TOOL, true, "PLA", "#1B1B1B")))
    val detail = ToolMappingTest::class.java.getResource("/flashforge/creator5-detail-2026-10-09.json")!!.readText()

    "the tools a file uses are its T<n> lines; control: a T in a comment, in another command, or as a word's tail does not count" {
        ToolMapping.toolsUsed("G28\nT0\nG1 X1 ; T7 in a comment\nT2 ; change\nSET_PRINT_STATS_INFO TOTAL_LAYER=3\nTOOL_CHANGE T=5\nM104 T1 S200\n  T2\n") shouldContainExactly setOf(0, 2)
        ToolMapping.toolsUsed("G28\nG1 X1\n") shouldContainExactly emptySet()
    }

    "the mapping from the loadout: each used tool to its slot with the filament's kind and color, the printer's color in the slot" {
        val m = ToolMapping.fromLoadout(config(), "m", setOf(0, 2), reported)
        m.entries shouldContainExactly listOf(
            ToolMapping.Entry(1, 1, "PLA", "#FFFFFF", "#FEFEFE"), ToolMapping.Entry(3, 3, "PLA", "#1B1B1B", "#1B1B1B"))
        m.text() shouldBe "T0 (tool 1) -> slot 1 PLA #FEFEFE; T2 (tool 3) -> slot 3 PLA #1B1B1B"
        // control: with nothing reported, the config's color stands in for the slot's
        ToolMapping.fromLoadout(config(), "m", setOf(0), null).entries.single().slotColor shouldBe "#FFFFFF"
    }

    "a used tool with no filament loaded is refused by name; control: the same file over the loaded tools maps" {
        shouldThrow<IllegalStateException> { ToolMapping.fromLoadout(config(), "m", setOf(0, 1), reported) }.message shouldBe
            "printer 'm': the file uses T1 (tool 2), but machine 'm' loads no filament in tool 2 (hardware.machine.m.loadout.tool.<key>.filament): not starting it"
        ToolMapping.fromLoadout(config(), "m", setOf(0, 2), reported).entries.size shouldBe 2
        // a filament without a color (the bundled generic ones) is refused too: the API wants one and none is guessed
        shouldThrow<IllegalStateException> { ToolMapping.fromLoadout(config("hardware.machine.m.loadout.tool.t2.filament" to "nocolour"), "m", setOf(1), null) }.message!! shouldContain
            "tool 2 prints 'nocolour', which sets no color as #RRGGBB"
    }

    "the Flashforge adapter sends materialMappings as the community map shapes it; control: no mapping, no key" {
        val ok = """{"code": 0, "message": "Success"}"""
        val t = Recorded { c -> Recorded.json(if (c.path == "/detail") detail else ok) }
        val p = FlashforgeHttp("c5", "printer.test", 8898, "SN-TEST", "CODE-TEST", Duration.ofSeconds(8), t)
        val d = p.start("part.gcode", ToolMapping.fromLoadout(config(), "m", setOf(0, 2), reported))
        t.bodyText(0) shouldBe """{"serialNumber":"SN-TEST","checkCode":"CODE-TEST","fileName":"part.gcode","levelingBeforePrint":false,""" +
            """"materialMappings":[{"toolId":0,"slotId":1,"materialName":"PLA","toolMaterialColor":"#FFFFFF","slotMaterialColor":"#FEFEFE"},""" +
            """{"toolId":2,"slotId":3,"materialName":"PLA","toolMaterialColor":"#1B1B1B","slotMaterialColor":"#1B1B1B"}]}"""
        d.sent shouldContain "materialMappings: T0 (tool 1) -> slot 1 PLA #FEFEFE"
        val u = Recorded { c -> Recorded.json(if (c.path == "/detail") detail else ok) }
        FlashforgeHttp("c5", "printer.test", 8898, "SN-TEST", "CODE-TEST", Duration.ofSeconds(8), u).start("part.gcode").sent shouldContain "no material mapping"
        u.bodyText(0) shouldNotContain "materialMappings"
    }

    "the Moonraker adapter sends no mapping and says so" {
        val printing = ToolMappingTest::class.java.getResource("/moonraker/objects-query-printing.json")!!.readText()
        val t = Recorded { c -> Recorded.json(if (c.path.startsWith("/printer/objects/query")) printing else """{"result": "ok"}""") }
        val d = MoonrakerHttp("q2", "printer.test", 7125, null, Duration.ofSeconds(8), t).start("part.gcode", ToolMapping.fromLoadout(config(), "m", setOf(0), null))
        d.sent shouldContain "the tool mapping is not sent"
        t.paths.first() shouldBe "POST /printer/print/start?filename=part.gcode"
    }
})
