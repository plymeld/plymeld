package plymeld.printer

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import plymeld.config.Config
import plymeld.config.ConfigFiles
import plymeld.config.ConfigStack
import plymeld.printer.LoadoutSync.Verdict
import java.nio.file.Files

/**
 * Filament sync: a recorded Creator 5 `/detail` answer (anonymised) read into a
 * loadout, and compared with a config's. Each check has a control that must come out the other way.
 */
class LoadoutSyncTest : StringSpec({
    val recorded = LoadoutSyncTest::class.java.getResource("/flashforge/creator5-detail-2026-10-09.json")!!.readText()

    // a four-tool machine (indices 1-4) and five filaments, three of them PLA with colors
    val base = listOf(
        "hardware.machine.m.model" to "flashforge-c5", "hardware.machine.m.build.x" to "256 mm", "hardware.machine.m.build.y" to "256 mm",
        "hardware.machine.m.build.z" to "256 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "300 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "5000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to false,
        "hardware.machine.m.gcode.start" to "G28", "hardware.machine.m.gcode.end" to "M84", "hardware.machine.m.loadout.plate" to "pei",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "0.08 mm", "hardware.tool.t1.layer.height.max" to "0.3 mm",
        "hardware.tool.t2.like" to "t1", "hardware.tool.t2.index" to 2L,
        "hardware.tool.t3.like" to "t1", "hardware.tool.t3.index" to 3L,
        "hardware.tool.t4.like" to "t1", "hardware.tool.t4.index" to 4L,
        "hardware.filament.white.like" to "pla-generic", "hardware.filament.white.color" to "#FFFFFF",
        "hardware.filament.black.like" to "pla-generic", "hardware.filament.black.color" to "#1b1b1b",
        "hardware.filament.gray.like" to "pla-generic", "hardware.filament.gray.color" to "#8C8C89",
        "hardware.filament.petg-white.like" to "petg-generic", "hardware.filament.petg-white.color" to "#FFFFFF")
    fun entries(vararg extra: Pair<String, Any>, drop: (String) -> Boolean = { false }) =
        ConfigFiles.bundled() + (base.filterNot { drop(it.first) } + extra).map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }
    fun config(vararg extra: Pair<String, Any>, drop: (String) -> Boolean = { false }) = Config(ConfigStack(entries(*extra, drop = drop)), emptyList())
    fun detail(edit: (String) -> String = { it }) = FlashforgeHttp.parseDetail(edit(recorded))
    fun row(r: LoadoutSync.Report, p: Int) = r.rows.single { it.position == p }

    "the recorded Creator 5 answer reads as four tools: 1 and 2 empty, 3 PLA #FFFFFF, 4 PLA #1B1B1B" {
        val l = detail()
        l.source shouldBe "Flashforge Creator 5 (firmware 1.9.9), LAN HTTP API /detail"
        l.slots.map { listOf(it.position, it.feeds, it.loaded, it.material, it.color) } shouldContainExactly listOf(
            listOf(1, ReportedSlot.Feeds.TOOL, false, null, null), listOf(2, ReportedSlot.Feeds.TOOL, false, null, null),
            listOf(3, ReportedSlot.Feeds.TOOL, true, "PLA", "#FFFFFF"), listOf(4, ReportedSlot.Feeds.TOOL, true, "PLA", "#1B1B1B"))
        l.notes.shouldBeEmpty()
        // control: one nozzle with the same station reads as slots feeding it (an AD5X-like printer), not tools
        detail { it.replace("\"nozzleCnt\": 4", "\"nozzleCnt\": 1") }.slots.map { it.feeds }.distinct() shouldBe listOf(ReportedSlot.Feeds.SLOT)
        // control: nozzles and slots that do not pair up are refused, not guessed
        shouldThrow<IllegalStateException> { detail { it.replace("\"nozzleCnt\": 4", "\"nozzleCnt\": 2") } }.message!! shouldContain "which tool each slot feeds is not known"
    }

    "a refused request (non-zero code) is refused, an unreadable color is noted, a slot naming filament while empty is noted" {
        shouldThrow<IllegalStateException> { detail { it.replace("\"code\": 0", "\"code\": -2") } }.message!! shouldContain "the printer refused /detail: code -2"
        val odd = detail { it.replace("\"#1B1B1B\"", "\"black\"").replaceFirst("\"materialName\": \"\"", "\"materialName\": \"PETG\"") }
        odd.slots.single { it.position == 4 }.color shouldBe null
        odd.notes shouldContainExactly listOf("slot 1: reports no filament, yet names PETG", "slot 4: color \"black\" is not #RRGGBB; left out")
        detail().notes.shouldBeEmpty()                                                     // control: the recorded one has none
    }

    "a tool loaded with the matching filament agrees; control: the config's color one step off differs, naming the nearest" {
        val agrees = LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t3.filament" to "white"), detail())
        row(agrees, 3).verdict shouldBe Verdict.AGREES
        row(agrees, 3).tool shouldBe "t3"
        val off = LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t3.filament" to "white", "hardware.filament.white.color" to "#FFFFFE"), detail())
        row(off, 3).verdict shouldBe Verdict.DIFFERS
        row(off, 3).why shouldContain "color #FFFFFE vs #FFFFFF; no filament in the config is PLA #FFFFFF (nearest PLA: white #FFFFFE, RGB distance 1)"
    }

    "the material counts as well as the color: PETG white in the config against PLA white on the printer differs, and the PLA white is offered" {
        val r = LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t3.filament" to "petg-white"), detail())
        row(r, 3).verdict shouldBe Verdict.DIFFERS
        row(r, 3).why shouldContain "material PETG vs PLA; matches white"
        row(r, 3).chosen shouldBe "white"
    }

    "an empty tool is reported empty; control: the same tool loaded agrees" {
        val r = LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t1.filament" to "gray"), detail())
        row(r, 1).verdict shouldBe Verdict.EMPTY_ON_PRINTER
        row(r, 2).verdict shouldBe Verdict.BOTH_EMPTY
        val loaded = detail { it.replaceFirst("\"hasFilament\": false,\n          \"materialColor\": \"\",\n          \"materialName\": \"\"",
            "\"hasFilament\": true,\n          \"materialColor\": \"#8c8c89\",\n          \"materialName\": \"pla\"") }
        row(LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t1.filament" to "gray"), loaded), 1).verdict shouldBe Verdict.AGREES
    }

    "a loaded tool the config leaves empty is reported with its match; control: with that filament undefined there is no match" {
        val r = LoadoutSync.compare(config(), detail())
        row(r, 4).verdict shouldBe Verdict.NOT_IN_CONFIG
        row(r, 4).matches shouldContainExactly listOf("black")
        val none = LoadoutSync.compare(config { it.startsWith("hardware.filament.black.") }, detail())
        row(none, 4).matches.shouldBeEmpty()
        row(none, 4).why shouldContain "no filament in the config is PLA #1B1B1B (nearest PLA: gray #8C8C89"
    }

    "two filaments matching are not chosen between; control: the config's own choice among them agrees" {
        val twins = config("hardware.filament.white2.like" to "pla-generic", "hardware.filament.white2.color" to "#FFFFFF")
        val r = LoadoutSync.compare(twins, detail())
        row(r, 3).matches shouldContainExactly listOf("white", "white2")
        row(r, 3).chosen shouldBe null
        r.overlay() shouldContain "# tool 3: the printer has PLA #FFFFFF; several filaments match (white, white2): not chosen"
        val own = LoadoutSync.compare(config("hardware.filament.white2.like" to "pla-generic", "hardware.filament.white2.color" to "#FFFFFF",
            "hardware.machine.m.loadout.tool.t3.filament" to "white2"), detail())
        row(own, 3).verdict shouldBe Verdict.AGREES
        row(own, 3).chosen shouldBe "white2"
    }

    "the overlay reads back as config: the printer's loadout on the machine, read by the config's own loader; an empty tool is \"\", the unmatched are comments" {
        val own = arrayOf("hardware.machine.m.loadout.tool.t1.filament" to "gray", "hardware.machine.m.loadout.tool.t3.filament" to "petg-white")
        val r = LoadoutSync.compare(config(*own), detail())
        val d = Files.createTempDirectory("plymeld-overlay")
        Files.writeString(d.resolve("overlay.toml"), r.overlay())
        val overlay = ConfigFiles.readFiles(listOf("overlay.toml"), d)
        val back = ConfigStack(overlay)
        fun at(t: String) = back.entry("hardware.machine.m.loadout.tool.$t.filament".split('.'))?.value
        at("t3") shouldBe "white"
        at("t4") shouldBe "black"
        at("t1") shouldBe ""                                                               // empty on the printer: unloaded
        at("t2") shouldBe ""
        r.overlay() shouldContain "tool.t1.filament = \"\"  # tool 1: empty on the printer (the config loads gray there)"
        // passed after the user's own files, it brings the config in line with the printer: every position agrees or is empty on both
        val synced = LoadoutSync.compare(Config(ConfigStack(entries(*own) + overlay), emptyList()), detail())
        synced.rows.map { it.verdict }.distinct().toSet() shouldBe setOf(Verdict.BOTH_EMPTY, Verdict.AGREES)
        synced.warnings().shouldBeEmpty()
        row(synced, 1).config shouldBe null
        // control: the user's files alone still load gray in tool 1, which the printer reports empty
        row(LoadoutSync.compare(config(*own), detail()), 1).verdict shouldBe Verdict.EMPTY_ON_PRINTER
        // control: without a black filament, tool 4 gets no line, only a comment saying what to define
        val noBlack = LoadoutSync.compare(config { it.startsWith("hardware.filament.black.") }, detail()).overlay()
        noBlack shouldNotContain "tool.t4.filament"
        noBlack shouldContain "# tool 4: the printer has PLA #1B1B1B; no filament in the config matches (define one: kind = \"PLA\", color = \"#1B1B1B\")"
    }

    "the warnings before a start: one line per position that does not agree, none for agreeing or empty-on-both; control: a matching loadout warns nothing" {
        val r = LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t3.filament" to "petg-white"), detail())
        r.warnings() shouldContainExactly listOf(
            "warning: tool 3 (t3): printer PLA #FFFFFF; config petg-white (PETG #FFFFFF) -> differs: material PETG vs PLA; matches white",
            "warning: tool 4 (t4): printer PLA #1B1B1B; config nothing -> loaded on the printer, nothing in the config: matches black")
        LoadoutSync.compare(config("hardware.machine.m.loadout.tool.t3.filament" to "white", "hardware.machine.m.loadout.tool.t4.filament" to "black"), detail()).warnings().shouldBeEmpty()
    }

    "the recorded answer is anonymised: no private address, MAC, location or file name; control: the same patterns find them in a live-shaped answer" {
        val privateIp = Regex("""\b(10|192\.168|172\.(1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}(\.\d{1,3})?\b""")
        val mac = Regex("""\b([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\b""")
        fun leaks(t: String) = listOfNotNull(privateIp.find(t)?.value, mac.find(t)?.value?.takeIf { it != "00:00:00:00:00:00" },
            Regex("\"location\": \"([^\"]+)\"").find(t)?.value, Regex("\"printFileName\": \"(?!example\\.gcode)[^\"]+\"").find(t)?.value,
            Regex("\"serialNumber\"|\"checkCode\"").find(t)?.value)
        leaks(recorded).shouldBeEmpty()
        val live = recorded.replace("192.0.2.10", "192.168.7.7").replace("00:00:00:00:00:00", "AB:CD:EF:01:23:45").replace("\"location\": \"\"", "\"location\": \"Group B\"")
        leaks(live).size shouldBe 3                                                        // the address, the MAC, the location
    }
})
