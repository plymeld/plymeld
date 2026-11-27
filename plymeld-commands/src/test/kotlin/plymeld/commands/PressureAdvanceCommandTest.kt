package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.withClue
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import plymeld.config.Config
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Site
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate pressure-advance` itself, its plates made from a copy of the repo's sample config and read back: the
 * first plate's top (the bed, the extruder's push, the toolhead's ceiling) and its values; the first layer's width; every
 * plate a result suggests made as printed; what the command prints, one thing a line within the limit, with long keys
 * too; its refusals. A slow suite (tools/slow-suites.txt): it makes many plates.
 */
class PressureAdvanceCommandTest : StringSpec({
    val samples = PressureAdvanceSheets.samples
    fun copy() = PressureAdvanceSheets.copy()
    fun plate(dir: Path, vararg args: String) = PressureAdvanceSheets.plate(dir, *args)
    fun sheet(g: Path) = PressureAdvanceSheets.sheet(g)
    fun fill(blank: String, read: (row: String, value: Double?, step: Int) -> Triple<String, String, String?>) = PressureAdvanceSheets.fill(blank, read)
    fun truth(k: Double) = PressureAdvanceSheets.truth(k)
    fun values(text: String) = PressureAdvanceSheets.values(text)

    val dir = copy()

    /** The command lines a result suggests (each `plymeld ...` in backquotes in its report), as printed. */
    fun suggested(r: PressureAdvanceRecord.Result): List<String> = Regex("`(plymeld [^`]+)`").findAll(r.report.joinToString("\n")).map { it.groupValues[1] }.toList()
    /** A command line split into words as a POSIX shell splits it (single quotes, backslashes outside them). */
    fun words(line: String): List<String> {
        val out = ArrayList<String>(); val cur = StringBuilder(); var inWord = false; var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                c == '\'' -> { val end = line.indexOf('\'', i + 1); cur.append(line, i + 1, end); i = end; inWord = true }
                c == '\\' && i + 1 < line.length -> { cur.append(line[i + 1]); i++; inWord = true }
                c.isWhitespace() -> { if (inWord) { out += cur.toString(); cur.clear(); inWord = false } }
                else -> { cur.append(c); inWord = true }
            }
            i++
        }
        if (inWord) out += cur.toString()
        return out
    }
    /** Runs a printed command line, unmodified, through the command line's own parser; its g-code (its -o) back. */
    fun runPrinted(line: String): Path {
        val w = words(line)
        w.first() shouldBe "plymeld"
        val said = ArrayList<String>()
        val status = Commands.invoke(w.drop(1), { said += it }, { said += it })
        withClue("$line\n${said.joinToString("\n")}") { status shouldBe 0 }
        return Path.of(w[w.indexOf("-o") + 1])
    }

    "every plate a result suggests is made as printed, run through the command parser unmodified: from a first plate, a fine plate, and a confirm plate" {
        // a first plate (0, then the tool's lowest to a ceiling of 0.1 s, each a fixed ratio below the next), read with the
        // true value at its lowest flip: 0 and the lowest value bracket it, the case a fixed step pushed below 0
        val firstPlate = plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=0.1 s")
        val first = values(fill(sheet(firstPlate), truth(0.0005)))
        val fromFirst = suggested(first)
        fromFirst.size shouldBe 2
        first.report.none { it.endsWith(" ") } shouldBe true
        val made = fromFirst.map { runPrinted(it) }
        made.map { it.fileName.toString() }.all { it.endsWith("-fine.gcode") || it.endsWith("-confirm.gcode") } shouldBe true
        // each made with the first plate's own options (config, machine, tool, filament, build plate), not the defaults
        fun madeWith(g: Path) = Files.readString(g).lines().single { it.startsWith("; made_with = ") }
        for (g in made) withClue(g.fileName) { madeWith(g) shouldBe madeWith(firstPlate) }
        // the fine plate it made, read, and its own suggestions
        val fine = values(fill(sheet(made.first { it.fileName.toString().endsWith("-fine.gcode") }), truth(0.0006)))
        val fromFine = suggested(fine)
        fromFine.size shouldBe 2
        val madeFine = fromFine.map { runPrinted(it) }
        // the confirm plate suggests none
        val c = values(fill(sheet(madeFine.first { it.fileName.toString().endsWith("-confirm.gcode") }), truth(fine.value!!)))
        suggested(c) shouldBe emptyList()
    }

    "the default first plate on a bed too short for the ceiling's walls: its top the highest value whose walls fit, said; a longer bed takes the ceiling (control); a lowest value that does not fit is refused" {
        fun conditions(g: Path) = Files.readString(g).lines().filter { it.startsWith("; ") }
        fun valuesOf(g: Path) = Regex("""; values = (.*) s""").find(Files.readString(g))!!.groupValues[1].split(", ").map { it.toDouble() }
        // the sample's bed: 250 x 250 mm, the generic ceiling
        val g = plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s")
        val top = conditions(g).single { it.startsWith("; top = ") }
        top shouldContain "fit"
        top shouldContain "would need walls"
        (valuesOf(g).max() < 1.0) shouldBe true
        // the plate fits: its walls end inside the bed's 250 mm
        val ys = Files.readString(g).lines().mapNotNull { Regex("""^G1 X[-0-9.]+ Y([-0-9.]+) E""").find(it)?.groupValues?.get(1)?.toDouble() }
        (ys.max() <= 250.0) shouldBe true
        // control: a bed long enough for the ceiling's walls takes the ceiling
        val long = plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s", "-cv", "hardware.machine.example-corexy.build.y=700 mm")
        valuesOf(long).max() shouldBe (1.0 plusOrMinus 1e-9)
        conditions(long).single { it.startsWith("; top = ") } shouldContain "the tool's ceiling"
        // the tool's lowest value above what fits: refused, saying why
        shouldThrow<IllegalArgumentException> { plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s", "-cv", "hardware.tool.t1.calibrate.pressure_advance.min=0.9 s") }.message!! shouldContain "from the tool's lowest value"
    }

    "the extruder's push: with a highest filament speed set, the first plate's top is the highest value whose corner from rest keeps within the share of it, said; unset, no push limit, said" {
        fun conditions(g: Path) = Files.readString(g).lines().filter { it.startsWith("; ") }
        val limited = plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s", "-cv", "hardware.tool.t1.extrude.speed.max=20 mm/s")
        val c = conditions(limited)
        val top = c.single { it.startsWith("; top = ") }
        top shouldContain "the extruder's push"
        // the expected top worked out here from the plate's own conditions and Klipper's documented smoothing: a triangular
        // weighting over 0.04 s, so a change of dv made in ta = dv / a (shorter than the window) pushes at most dv (2 / T - ta / T^2)
        val walls = c.single { it.startsWith("; walls = ") }; val speeds = c.single { it.startsWith("; speeds = ") }
        val wMm = Regex("""([0-9.]+) mm lines ([0-9.]+) mm high""").find(walls)!!.groupValues.drop(1).map { it.toDouble() }
        val a = Regex("""acceleration ([0-9.]+) mm/s2""").find(walls)!!.groupValues[1].toDouble()
        val flowRatio = Regex("""flow_ratio ([0-9.]+)""").find(walls)!!.groupValues[1].toDouble()
        val fast = Regex("""fast ([0-9.]+) mm/s""").find(speeds)!!.groupValues[1].toDouble()
        val d = Regex("""filament ([0-9.]+) mm""").find(walls)!!.groupValues[1].toDouble()
        val r = wMm[1] * (wMm[0] - wMm[1] * (1 - Math.PI / 4)) * flowRatio / (Math.PI * d * d / 4)
        val t = 0.04; val ta = fast / a
        val k = (0.75 * 20 / r - fast) / (fast * (2 / t - ta / (t * t)))
        val expected = Math.floor(k * 10_000 + 1e-6) / 10_000
        Regex("""^; top = ([0-9.]+) s""").find(top)!!.groupValues[1].toDouble() shouldBe (expected plusOrMinus 1e-9)
        c.single { it.startsWith("; limits = ") } shouldContain "15 mm/s of filament"
        // control: unset, the plate applies no push limit and says so; its top is the bed's
        val free = conditions(plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s"))
        free.single { it.startsWith("; limits = ") } shouldContain "no push limit"
        free.single { it.startsWith("; top = ") } shouldContain "fit"
    }

    "the first layer at the first-layer line width the config sets, stated in the plate's conditions; another width set, that one (control)" {
        fun firstWidths(g: Path): Set<String> {
            // the width in force at each of the base's first-layer printing moves (a ;WIDTH: line stands until the next)
            val out = HashSet<String>(); var layer = -1; var cell = ""; var width = ""
            for (l in Files.readString(g).lines()) {
                if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) layer++
                if (l.startsWith("; CELL: ")) cell = l.removePrefix("; CELL: ")
                if (l.startsWith(";WIDTH:") || l.startsWith("; LINE_WIDTH:")) width = l.substringAfter(":").trim()
                if (layer == 0 && cell == "base" && l.startsWith("G1 ") && " E" in l && (" X" in l || " Y" in l)) out += width
            }
            return out
        }
        fun stated(g: Path) = Regex("""; first_layer = ([0-9.]+) mm lines""").find(Files.readString(g))!!.groupValues[1]
        val c = Config(ConfigStack.load(listOf(dir.resolve("0*.toml").toString()), emptyList()), emptyList())
        val set = PressureAdvanceRecord.n(c.quantity(c.entry(listOf("plan", "mechanism", "adhesion", "first_layer", "line", "width"))!!, Dimension.LENGTH, Site(machine = "example-corexy", tool = "t1", filament = "pla-white")))
        val g = plate(dir)
        firstWidths(g) shouldBe setOf(set)
        stated(g) shouldBe set
        val other = plate(dir, "-cv", "plan.mechanism.adhesion.first_layer.line.width=0.55 mm")
        firstWidths(other) shouldBe setOf("0.55")
        stated(other) shouldBe "0.55"
    }

    "a bundled direct-drive model's toolhead gives the ceiling (0.2 s) and, on the Creator 5, the extruder's push limit; a tool's own ceiling wins" {
        fun conditions(g: Path) = Files.readString(g).lines().filter { it.startsWith("; ") }
        val q2 = plate(dir, "-cv", "hardware.machine.example-corexy.build.y=700 mm")
        // the sample machine is a Q2 (model qidi-q2): its toolhead's ceiling, no push limit known
        conditions(q2).single { it.startsWith("; top = ") } shouldContain "toolhead.0.calibrate.pressure_advance.max, condition; `plymeld config` shows its evidence"
        // the evidence itself stays in the config: not in the conditions, nor the page
        Files.readString(q2).substringAfter("; CALIBRATION_PLATE_START").substringBefore("; CALIBRATION_PLATE_END").contains("Bowden") shouldBe false
        Files.readString(q2.resolveSibling(q2.fileName.toString().removeSuffix(".gcode") + ".md")).contains("Bowden") shouldBe false
        Regex("""; values = (.*) s""").find(Files.readString(q2))!!.groupValues[1].split(", ").map { it.toDouble() }.max() shouldBe (0.2 plusOrMinus 1e-9)
        conditions(q2).single { it.startsWith("; limits = ") } shouldContain "no push limit"
        // control: the tool's own ceiling wins over its toolhead's
        val own = plate(dir, "-cv", "hardware.machine.example-corexy.build.y=700 mm", "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=0.3 s")
        Regex("""; values = (.*) s""").find(Files.readString(own))!!.groupValues[1].split(", ").map { it.toDouble() }.max() shouldBe (0.3 plusOrMinus 1e-9)
        // the Creator 5's four toolheads: the ceiling and the push limit, with their evidence; the Q2's no push limit
        val c5 = plymeld.config.Models.bundled.forModel("flashforge-c5").toolheads
        c5.size shouldBe 4
        for (th in c5) { th.paCeilingS shouldBe (0.2 plusOrMinus 1e-9); th.extrudeSpeedMax shouldBe (50.7 plusOrMinus 1e-9); th.extrudeSpeedEvidence!! shouldContain "measured"; th.paCeilingEvidence!! shouldContain "direct-drive" }
        plymeld.config.Models.bundled.forModel("qidi-q2").toolheads.single().extrudeSpeedMax shouldBe null
        // a push limit through a model's toolhead (here the sample machine's model's) reaches the plate
        val limited = plate(dir, "-cv", "hardware.model.qidi-q2.toolhead.0.extrude.speed.max=50 mm/s", "-cv", "hardware.machine.example-corexy.build.y=700 mm")
        conditions(limited).single { it.startsWith("; limits = ") } shouldContain "37.5 mm/s of filament, 75 % of the extruder's 50 mm/s"
    }

    "every wall's value is at its label's resolution (0.1 ms): a fine plate's even values and the first plate's ratio values" {
        for (g in listOf(plate(dir, "--bracket", "0.0123-0.0456"), plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=0.1 s"))) {
            val vs = Regex("""; wall \d+\.\d+ = ([0-9.]+) s""").findAll(Files.readString(g)).map { it.groupValues[1].toDouble() }.toList()
            (vs.size > 10) shouldBe true
            for (v in vs) withClue("$v s") { Math.abs(v * 10_000 - Math.round(v * 10_000)) shouldBe (0.0 plusOrMinus 1e-6) }
        }
    }

    "the first plate's values: 0, then the tool's lowest to its ceiling, each a fixed ratio below the next; rows one place apart are accepted, two refused" {
        val g = plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=0.2 s", "-cv", "hardware.tool.t1.calibrate.pressure_advance.min=0.004 s")
        val vs = Regex("""; values = (.*) s""").find(Files.readString(g))!!.groupValues[1].split(", ").map { it.toDouble() }
        vs.size shouldBe plymeld.core.calibrate.PressureAdvanceGeometry.values
        vs.first() shouldBe 0.0
        vs[1] shouldBe (0.004 plusOrMinus 1e-9)
        vs.last() shouldBe (0.2 plusOrMinus 1e-9)
        val ratios = vs.drop(1).zipWithNext { a, b -> b / a }
        for (r in ratios) r shouldBe (Math.pow(0.2 / 0.004, 1.0 / (vs.size - 2)) plusOrMinus 0.05)
        // the rows a place apart (the front flips between places 5 and 6, the back between 6 and 7): accepted, though the steps differ in size
        val b = sheet(g)
        values(fill(b) { r, v, s -> truth(if (r == "front") (vs[5] + vs[6]) / 2 else (vs[6] + vs[7]) / 2)(r, v, s) }).value shouldBe (plymeld.core.calibrate.PressureAdvanceSpec.atLabel((vs[5] + vs[7]) / 2) plusOrMinus 1e-9)
        shouldThrow<IllegalArgumentException> { values(fill(b) { r, v, s -> truth(if (r == "front") (vs[5] + vs[6]) / 2 else (vs[7] + vs[8]) / 2)(r, v, s) }) }.message!! shouldContain "more than one step"
    }

    /** Runs `calibrate pressure-advance` on the sample config with [args]: its status and what it printed, line by line. */
    fun said(vararg args: String): Pair<Int, List<String>> {
        val lines = ArrayList<String>()
        val status = Commands.invoke(listOf("calibrate", "pressure-advance", "-cf", dir.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2") + args.toList() +
            listOf("-o", dir.resolve("said.gcode").toString()), { lines += it }, { lines += it })
        return status to lines.flatMap { it.lines() }
    }

    "the first plate's top and why are said once" {
        // the sample's 250 mm bed and the generic ceiling: the top the bed's, with the length the ceiling would need
        val (status, lines) = said("-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s")
        status shouldBe 0
        lines.count { "would need" in it } shouldBe 1
        // control: the same top's reason is in the plate's conditions and its page, once each
        Files.readString(dir.resolve("said.gcode")).lines().count { "would need" in it } shouldBe 1
    }

    // a first plate read with its flip inside a bracket whose middle and half fall between the labels' tenths of a
    // millisecond (an odd number of them apart): its report's text and suggested lines
    fun units(v: Double) = Math.round(v * 10_000)
    fun planned(p: Path) = Regex("""; values = (.*) s""").find(Files.readString(p))!!.groupValues[1].split(", ").map { it.toDouble() }
    val oddRead by lazy {
        val g = plate(dir)
        val vs = planned(g)
        val k = (1 until vs.size - 1).first { (units(vs[it + 1]) - units(vs[it])) % 2 == 1L }
        values(fill(sheet(g), truth((vs[k] + vs[k + 1]) / 2)))
    }

    "a first plate's confirm suggestion names the neighbors the plate made by the printed line has" {
        val r = oddRead; val text = r.report.joinToString("\n")
        val confirm = planned(runPrinted(suggested(r).single { "--confirm " in it }))
        // the neighbors the report names, or the bracket's ends where it names those
        val named = Regex("""its neighbors the bracket's ends, ([0-9.]+) and ([0-9.]+) s""").find(text)?.groupValues?.let { listOf(it[1].toDouble(), it[2].toDouble()) }
            ?: Regex("""the middle of ([0-9.]+) to ([0-9.]+) s""").find(text)!!.groupValues.let { listOf(it[1].toDouble(), it[2].toDouble()) }
        listOf(confirm.first(), confirm.last()) shouldBe named
        confirm[1] shouldBe r.value
    }

    "a first plate's fine suggestion names the steps between the values of the plate made by the printed line" {
        val r = oddRead; val text = r.report.joinToString("\n")
        val fine = planned(runPrinted(suggested(r).single { "--bracket " in it }))
        val steps = fine.zipWithNext { a, b -> units(b - a) }.distinct().sorted()
        val stated = Regex("""values, ([0-9.]+)(?: to ([0-9.]+))? s apart""").find(text)!!.groupValues.drop(1).filter { it.isNotEmpty() }.map { units(it.toDouble()) }
        listOf(steps.first(), steps.last()).distinct() shouldBe stated
    }

    "the first plate not fitting even from the lowest value: the refusal names the highest whole fast speed below the plate's at which it fits, which makes it and the next does not, or says none does" {
        // values from a lowest value to 1.4 ms above it, so the lowest values' plate is the whole range's: its depth on a long
        // bed with a middle at 160 mm/s (within the plate's share of a filament's maximum flow raised to 40 mm3/s) and at lower fast speeds, for a few lowest and slow speeds; a bed between the depth at
        // 160 mm/s and a shallower one at a lower fast speed fits the lowest values only at a lower fast speed
        fun rangeOf(min: String) = arrayOf("-cv", "hardware.tool.t1.calibrate.pressure_advance.max=${PressureAdvanceRecord.n(min.toDouble() + 0.0014)} s", "-cv", "hardware.tool.t1.calibrate.pressure_advance.min=$min s",
            "-cv", "hardware.filament.pla-white.melt.flow.max=40 mm3/s")
        fun depth(min: String, slowAt: Int, fast: Int): Double { val (status, lines) = said(*rangeOf(min), "-cv", "hardware.machine.example-corexy.build.y=900 mm", "--speeds", "$slowAt,$fast")
            withClue(lines.joinToString("\n")) { status shouldBe 0 }
            return Regex("""^plate: [0-9.]+ x ([0-9.]+) mm""").find(lines.first { it.startsWith("plate: ") })!!.groupValues[1].toDouble() }
        val tried = ArrayList<String>()
        val (case, shallow) = listOf("0.9", "0.7").flatMap { t -> listOf(50, 30).map { t to it } }.asSequence().mapNotNull { (t, sl) ->
            val dTop = depth(t, sl, 160)
            listOf(130, 100).filter { it > sl }.asSequence().map { it to depth(t, sl, it) }.onEach { tried += "$t s, $sl,${it.first}: ${it.second} mm (at 160: $dTop)" }
                .firstOrNull { Math.ceil(it.second + 20) < dTop + 20 }?.let { (t to sl) to it }
        }.firstOrNull() ?: error("no lower fast speed makes the plate shallower than at 160 mm/s by a whole millimeter:\n${tried.joinToString("\n")}")
        val (min, slowAt) = case
        val range = rangeOf(min)
        // the bed: that plate and the head's room to park in front and behind (10 mm each), to the millimeter above
        val high = range + arrayOf("-cv", "hardware.machine.example-corexy.build.y=${PressureAdvanceRecord.n(Math.ceil(shallow.second + 20))} mm")
        val msg = shouldThrow<IllegalArgumentException> { plate(dir, *high, "--speeds", "$slowAt,160") }.message!!
        val m = Regex("""--speeds ([0-9.]+),([0-9.]+)""").find(msg)
        withClue(msg) { (m != null) shouldBe true }
        val slow = m!!.groupValues[1]; val fast = m.groupValues[2].toDouble()
        withClue(msg) { slow shouldBe "$slowAt"; (fast >= shallow.first && fast < 160) shouldBe true }
        // the speed named makes the plate; one faster is refused
        plate(dir, *high, "--speeds", "$slow,${PressureAdvanceRecord.n(fast)}")
        shouldThrow<IllegalArgumentException> { plate(dir, *high, "--speeds", "$slow,${PressureAdvanceRecord.n(fast + 1)}") }.message!! shouldContain "from the tool's lowest value"
        // a lowest value too high for any fast speed on this bed: none named
        val none = shouldThrow<IllegalArgumentException> { plate(dir, "-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s", "-cv", "hardware.tool.t1.calibrate.pressure_advance.min=0.9 s") }.message!!
        none shouldContain "No fast speed from"
        (Regex("""--speeds [0-9]""").containsMatchIn(none)) shouldBe false
        // a bed too short for the walls at their shortest: none named, and that said
        val short = shouldThrow<IllegalArgumentException> { plate(dir, "-cv", "hardware.machine.example-corexy.build.y=60 mm") }.message!!
        short shouldContain "with its shortest walls"
        short shouldContain "mm of Y with the head"
        short.contains("values from") shouldBe false
        (Regex("""--speeds [0-9]""").containsMatchIn(short)) shouldBe false
    }

    fun overLong(lines: List<String>) = OutputLines.overLong(lines)

    "every line the command prints, its refusals, and values' report: one thing a line, none over 200 characters (a path counted as its file name) but a line that is nothing but a command to run; each limit's setting cited once in the output and once on the page" {
        val one = arrayOf("-cv", "hardware.tool.t1.calibrate.pressure_advance.max=1 s")
        // the push-limited plate made last, so its page is the one beside said.gcode
        val runs = listOf(said(*one), said("--confirm", "0.04", "--confirm-step", "0.004"), said(*one, "--values", "0,0.1,0.2,0.3", "-cv", "hardware.tool.t1.extrude.speed.max=20 mm/s"),
            said("-cv", "hardware.machine.example-corexy.build.y=60 mm"), said(*one, "-cv", "hardware.tool.t1.extrude.speed.max=20 mm/s"))
        for ((status, lines) in runs) withClue(lines.joinToString("\n")) { overLong(lines).shouldBeEmpty(); (status == 0 || lines.size == 1) shouldBe true }
        // the push-limited plate: each limit's setting once in the output, once on its page
        val limited = runs[4].second
        limited.count { "calibrate.pressure_advance.share" in it } shouldBe 1
        limited.count { "extrude.speed.max" in it } shouldBe 1
        val page = Files.readString(dir.resolve("said.md"))
        Regex("calibrate\\.pressure_advance\\.share").findAll(page).count() shouldBe 1
        Regex("extrude\\.speed\\.max").findAll(page).count() shouldBe 1
        // the over-push refusal and the bed refusal: refused, one line each
        runs[2].first shouldBe 1; runs[3].first shouldBe 1
        // values' report on a first plate's sheet
        overLong(values(fill(sheet(plate(dir, *one)), truth(0.05))).report).shouldBeEmpty()
        // control: a line as the plate's values line was written, its top's whole reason and the push limit in it, is caught
        val old = "values: 0, 0.01, 0.0128, 0.0163, 0.0209, 0.0267, 0.0341, 0.0436, 0.0558, 0.0713, 0.0927 s, the front row rising from the left, the back row falling; a corner beside each wall " +
            "(0, then the tool's lowest value, 0.01 s (hardware.tool.<key>.calibrate.pressure_advance.min, plymeld default), up to the plate's top, each 1.28 times the one before, to the labels' 0.1 ms; " +
            "the top 0.0927 s: the highest value the extruder's push allows (every move at a wall's value within 38.03 mm/s of filament, 75 % of the extruder's 50.7 mm/s))"
        overLong(listOf(old)) shouldBe listOf(old)
        // control: a refusal as the same-sign one was written, naming the readings file, is caught; its folder is not counted
        val named = "/a/folder/of/the/users/readings.toml: the front row's wall 2 (0.01 s) reads groove at both marks: the slow-down mark must read the opposite of the speed-up mark, " +
            "so something other than pressure advance shows there (the firmware's acceleration limits, a slipping extruder, or a pressure that does not settle within the wall's middle); no value"
        overLong(listOf(named)) shouldBe listOf(named)
        val deep = "/" + "folder/".repeat(40) + "readings.toml: the front row's wall 2 (0.01 s) reads groove at both marks: pressure advance reads the opposite at the slow-down mark; no value"
        overLong(listOf(deep)).shouldBeEmpty()
        // control: a command with a note beside it is held to the limit; the command alone is not
        val command = "`plymeld calibrate pressure-advance " + "-cv x=y ".repeat(30) + "-o pa-fine.gcode`"
        overLong(listOf("  next, a finer value: $command (11 values)", "    $command")) shouldBe listOf("  next, a finer value: $command (11 values)")
    }

    fun badComments(lines: List<String>) = OutputLines.badComments(lines)

    "with a machine, filament, and tool key of 40 characters or more: every line the plate, values, the plates its suggestions make, and their refusals print within the limit, and every comment values writes into the config within the config files' width" {
        val machine = OutputLines.MACHINE; val filament = OutputLines.FILAMENT; val tool = OutputLines.TOOL
        for (k in listOf(machine, filament, tool)) withClue(k) { (k.length >= 40) shouldBe true }
        // the sample config with those keys in place of its own
        val d = OutputLines.longNamed(samples)
        val config = arrayOf("-cf", d.resolve("0*.toml").toString())
        // a push limit through the model's toolhead, so the toolhead's setting is said
        val base = config + arrayOf("-cv", "hardware.machine.$machine.model=qidi-q2", "-cv", "hardware.model.qidi-q2.toolhead.0.extrude.speed.max=50 mm/s")
        val all = ArrayList<Pair<String, List<String>>>()
        /** Runs the command line [args], which must end with status [want]; what it printed, line by line, kept in [all]. */
        fun run(want: Int, vararg args: String) {
            val lines = ArrayList<String>()
            val status = Commands.invoke(args.toList(), { lines += it }, { lines += it })
            all += args.joinToString(" ") to lines.flatMap { it.lines() }
            withClue(all.last().let { (a, l) -> "$a\n${l.joinToString("\n")}" }) { status shouldBe want }
        }
        val first = d.resolve("first.gcode")
        run(0, "calibrate", "pressure-advance", *base, "-o", first.toString())
        Files.readString(first) shouldContain filament
        // the tool's own limits, set on the command line under its key
        run(0, "calibrate", "pressure-advance", *base, "-cv", "hardware.tool.$tool.extrude.speed.max=60 mm/s", "-cv", "hardware.tool.$tool.calibrate.pressure_advance.max=0.15 s", "-o", d.resolve("own.gcode").toString())
        all.last().second.any { Regex("""hardware\.tool\.\S+\.extrude\.speed\.max""").containsMatchIn(it) } shouldBe true
        // the over-push refusal and the bed refusal
        run(1, "calibrate", "pressure-advance", *base, "--values", "0,0.1,0.2,0.3", "-cv", "hardware.tool.$tool.extrude.speed.max=20 mm/s", "-o", d.resolve("refused.gcode").toString())
        run(1, "calibrate", "pressure-advance", *base, "-cv", "hardware.machine.$machine.build.y=60 mm", "-o", d.resolve("refused.gcode").toString())
        // values on the first plate's sheet: its report, the change it writes into the config, the plates its suggestions make
        val vs = planned(first)
        val readings = d.resolve("readings.toml")
        Files.writeString(readings, fill(sheet(first), truth((vs[4] + vs[5]) / 2)))
        run(0, "calibrate", "pressure-advance", "values", *config, "--journal", d.resolve("journal.json").toString(), readings.toString())
        val written = Files.readString(d.resolve("01_FILAMENTS.toml"))
        written shouldContain "pressure_advance = "
        val commented = written.lines().takeWhile { !it.startsWith("pressure_advance = ") }.takeLastWhile { it.startsWith("#") }
        withClue(written) { (commented.size >= 3) shouldBe true }
        val suggestions = suggested(values(Files.readString(readings)))
        suggestions.size shouldBe 2
        for (line in suggestions) {
            val w = words(line)
            w.first() shouldBe "plymeld"
            withClue(line) { run(0, *w.drop(1).toTypedArray()) }
        }
        // the same-sign refusal: one wall, then two, reading alike at both marks
        for (alike in listOf(listOf(vs[1]), listOf(vs[1], vs[2]))) {
            Files.writeString(readings, fill(sheet(first)) { r, v, s -> truth((vs[4] + vs[5]) / 2)(r, v, s).let { t -> if (alike.any { Math.abs(it - v!!) < 1e-9 }) t.copy(second = t.first) else t } })
            run(1, "calibrate", "pressure-advance", "values", *config, "--journal", d.resolve("journal.json").toString(), readings.toString())
        }
        all.any { (_, lines) -> lines.any { "at both marks" in it } } shouldBe true
        // every line within its limit, all of them gathered: what each run printed (the change values shows, its comments as
        // the file has them, under the two spaces it is indented by) and the comments written into the config
        val over = all.flatMap { (args, lines) -> (overLong(lines) + badComments(lines.map { it.removePrefix("  ") })).map { "$args\n    ${it.length}: $it" } } +
            badComments(written.lines()).map { "01_FILAMENTS.toml\n    ${it.length}: $it" }
        withClue(over.joinToString("\n")) { over.shouldBeEmpty() }
        // control: a comment line one column over the width, and one without its space, are caught
        val wide = "# " + "x".repeat(TomlEdit.COMMENT_WIDTH - 1)
        badComments(listOf(wide, "#x", "# " + "x".repeat(TomlEdit.COMMENT_WIDTH - 2))) shouldBe listOf(wide, "#x")
    }

    "the bed refusal states the plate's own size: the same plate on a bed it fits is that size" {
        val range = arrayOf("-cv", "hardware.tool.t1.calibrate.pressure_advance.max=0.1 s")
        // at 0.1 s the walls are their shortest (10 and 30 mm) at these speeds, so the plate that fits is the refusal's
        val (status, lines) = said(*range)
        status shouldBe 0
        val made = Regex("""^plate: ([0-9.]+) x ([0-9.]+) mm""").find(lines.first { it.startsWith("plate: ") })!!.groupValues.drop(1)
        Files.readString(dir.resolve("said.gcode")).contains("lead-in 10 mm at") shouldBe true
        val refusal = shouldThrow<IllegalArgumentException> { plate(dir, *range, "-cv", "hardware.machine.example-corexy.build.y=60 mm") }.message!!
        Regex("""it is ([0-9.]+) x ([0-9.]+) mm""").find(refusal)!!.groupValues.drop(1) shouldBe made
    }
})
