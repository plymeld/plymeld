package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import plymeld.config.FirstLayerWindow.Grade
import plymeld.model.Context
import plymeld.model.Resolver

/**
 * The first-layer window (FirstLayerWindow): the table format, the rule that picks a first layer from it, and where it
 * stands among the other first-layer values. The grids are a printed first-layer plate's (white PLA+ on textured PEI, a
 * 0.4 mm nozzle: 0.20 mm graded bad, 0.24 mm marginal, all others good; the tiles left out by the plate's g-code);
 * expected values worked out by hand from those tables.
 */
class FirstLayerWindowTest : StringSpec({
    val t210 = """
        speed \ height   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm
        300 mm/s         over      over      over      over      over
        260 mm/s         over      over      over      over      over
        220 mm/s         bad       over      over      over      over
        180 mm/s         bad       marginal  over      over      over
        140 mm/s         bad       marginal  good      good      good
        100 mm/s         bad       marginal  good      good      good
         60 mm/s         bad       marginal  good      good      good
    """.trimIndent()
    val t215 = """
        speed \ height   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm
        300 mm/s         over      over      over      over      over
        260 mm/s         bad       over      over      over      over
        220 mm/s         bad       marginal  over      over      over
        180 mm/s         bad       marginal  good      over      over
        140 mm/s         bad       marginal  good      good      good
        100 mm/s         bad       marginal  good      good      good
         60 mm/s         bad       marginal  good      good      good
    """.trimIndent()
    val t220 = t215.lines().map { if (it.startsWith("180")) "180 mm/s         bad       marginal  good      good      over" else it }.joinToString("\n")
    fun grid(text: String, t: Double = 210.0): FirstLayerWindow.Grid {
        val (g, p) = FirstLayerWindow.parse(text, t, "w.\"${t.toInt()} C\"", "test.toml:10")
        p shouldBe emptyList()
        return g!!
    }
    fun problems(text: String) = FirstLayerWindow.parse(text, 210.0, "w.\"210 C\"", "test.toml:10").let { (g, p) -> g shouldBe null; p }

    "a table reads by its axis labels: speeds and heights with units, the fastest row at the top" {
        val g = grid(t210)
        g.speeds.toList() shouldBe listOf(60.0, 100.0, 140.0, 180.0, 220.0, 260.0, 300.0)
        g.heights.toList() shouldBe listOf(0.2, 0.24, 0.28, 0.32, 0.36)
        g.grade(0, 0) shouldBe Grade.BAD          // 60 mm/s, 0.20 mm
        g.grade(3, 1) shouldBe Grade.MARGINAL     // 180 mm/s, 0.24 mm
        g.grade(4, 1) shouldBe Grade.OVER         // 220 mm/s, 0.24 mm
        g.grade(2, 4) shouldBe Grade.GOOD         // 140 mm/s, 0.36 mm
        // the same table turned (heights as rows), its rows in another order: the same grid, as the labels say, not the positions
        val turned = """
            height \ speed   300 mm/s  60mm/s  260 mm/s  100 mm/s  220 mm/s  140 mm/s  180 mm/s
            0.36 mm          over      good    over      good      over      good      over
            0.20 mm          over      bad     over      bad       bad       bad       bad
            0.28 mm          over      good    over      good      over      good      over
            0.24 mm          over      marginal over     marginal  over      marginal  marginal
            0.32 mm          over      good    over      good      over      good      over
        """.trimIndent()
        val h = grid(turned)
        h.speeds.toList() shouldBe g.speeds.toList(); h.heights.toList() shouldBe g.heights.toList()
        for (r in g.speeds.indices) for (c in g.heights.indices) h.grade(r, c) shouldBe g.grade(r, c)
        // control: a turned table read by position (its first column as the slowest speed) would differ at 60 mm/s, 0.20 mm
        h.grade(0, 0) shouldNotBe Grade.OVER
    }

    "a malformed table is refused, each problem named with its line; controls for each" {
        val lines = t210.lines()
        fun with(i: Int, line: String) = lines.toMutableList().also { it[i] = line }.joinToString("\n")
        problems(with(7, " 60 mm/s         bad       marginal  great     good      good")).single() shouldContain "test.toml:18"
        problems(with(7, " 60 mm/s         bad       marginal  great     good      good")).single() shouldContain "'great' is not a grade"
        problems(with(7, " 60 mm/s         bad       marginal  good      good")).single() shouldContain "4 grade(s) for 5 column(s)"
        problems(with(7, " 60 mm/s         bad       marginal  ?         good      good")).single() shouldContain "row 60 mm/s, column 0.28 mm: not graded yet"
        problems(with(7, " 60              bad       marginal  good      good      good")).single() shouldContain "a row starts with its speed and unit"
        problems(with(7, "100 mm/s         bad       marginal  good      good      good")).single() shouldContain "the speed 100 mm/s is a row twice"
        problems(with(0, "speed \\ height   0.20 mm   0.24 mm   0.28      0.32 mm   0.36 mm")).single() shouldContain "the column '0.28' has no unit"
        problems(with(0, "speed \\ height   0.20 mm   0.24 mm   0.28 mm   0.28 mm   0.36 mm")).single() shouldContain "the height 0.28 mm is a column twice"
        problems(with(0, "speed \\ height   0.20 mm   0.24 mm   0.28 mm/s 0.32 mm   0.36 mm")).single() shouldContain "'mm/s' is not a unit of length"
        problems(with(0, "            0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm")).single() shouldContain "the first line names the axes"
        problems(with(0, "speed \\ speed   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm")).single() shouldContain "one each"
        // the control: the table as it is reads (grid() asserts no problem)
        grid(with(0, "speed \\ height   0.20 mm   0.24 mm   0.28 mm   0.32 mm   0.36 mm"))
        // with '?' allowed (reading a blank window back) the same '?' reads as ungraded
        val blank = with(7, " 60 mm/s         ?         ?         ?         ?         ?")
        FirstLayerWindow.parse(blank, 210.0, "w", "test.toml:10", ungradedAllowed = true).first!!.grade(0, 2) shouldBe Grade.UNGRADED
    }

    "between two measured temperatures a tile is good only where good in both; outside them, no window" {
        val w = FirstLayerWindow.Window("white", "pei", 0.4, null, listOf(grid(t210, 210.0), grid(t215, 215.0)), "w", "test")
        FirstLayerWindow.gridAt(w, 210.0)!!.label shouldContain "210 C table"
        val mid = FirstLayerWindow.gridAt(w, 212.0)!!
        // 180 mm/s at 0.28 mm: good at 215, over at 210 -> not good between; 140 mm/s at 0.28 mm: good in both
        mid.grade(3, 2) shouldNotBe Grade.GOOD
        mid.grade(2, 2) shouldBe Grade.GOOD
        mid.grade(0, 0) shouldBe Grade.BAD
        FirstLayerWindow.gridAt(w, 209.0) shouldBe null
        FirstLayerWindow.gridAt(w, 216.0) shouldBe null
        // control: the 215 C table alone would take 180 mm/s at 0.28 mm
        FirstLayerWindow.gridAt(w, 215.0)!!.grade(3, 2) shouldBe Grade.GOOD
    }

    "the rule on the user's window: the height whose good tiles reach fastest, nearest the middle on a tie, a row inside the edge" {
        fun pick(text: String, t: Double, max: Double?) = FirstLayerWindow.decide(grid(text, t), 0.08, max)!!.let { it.heightMm to it.speedMmS }
        // within the tool's 0.08-0.3 mm (a 0.4 mm nozzle's layer.height.max = bore x 0.75): only 0.28 mm is good
        pick(t210, 210.0, 0.3) shouldBe (0.28 to 100.0)     // reaches 140; 180 is over: one row in
        pick(t215, 215.0, 0.3) shouldBe (0.28 to 140.0)     // reaches 180; 220 is over
        pick(t220, 220.0, 0.3) shouldBe (0.28 to 140.0)
        // with 0.36 mm allowed: 210 C ties 0.28 / 0.32 / 0.36 at 140 -> the middle, 0.32; 215 C: 0.28 alone reaches 180;
        // 220 C: 0.28 and 0.32 reach 180 -> 0.32, nearer the middle of 0.28-0.36
        pick(t210, 210.0, 0.36) shouldBe (0.32 to 100.0)
        pick(t215, 215.0, 0.36) shouldBe (0.28 to 140.0)
        pick(t220, 220.0, 0.36) shouldBe (0.32 to 140.0)
        // the margin hook: none asked
        FirstLayerWindow.decide(grid(t215, 215.0), 0.08, 0.3, marginRows = 0)!!.speedMmS shouldBe 180.0
        // the fastest row tried is good: no edge, no margin; 0.20 to 0.28 mm all reach it: the middle, 0.24 mm
        val allGood = t210.lines().mapIndexed { i, l -> if (i == 0) l else l.substring(0, 17) + " good      good      good      good      good" }.joinToString("\n")
        FirstLayerWindow.decide(grid(allGood), 0.08, 0.3)!!.let { (it.heightMm to it.speedMmS) shouldBe (0.24 to 300.0) }
        // nothing good within the tool's range: no choice
        FirstLayerWindow.decide(grid(t210), 0.08, 0.26) shouldBe null
    }

    // ---- in a config

    val machine = listOf(
        "hardware.machine.m.model" to "qidi-q2", "hardware.machine.m.build.x" to "250 mm", "hardware.machine.m.build.y" to "200 mm",
        "hardware.machine.m.build.z" to "250 mm", "hardware.machine.m.motion.travel.speed" to "300 mm/s", "hardware.machine.m.motion.print.speed" to "400 mm/s",
        "hardware.machine.m.motion.print.acceleration" to "10000 mm/s2", "hardware.machine.m.motion.travel.acceleration" to "= machine.motion.print.acceleration",
        "hardware.machine.m.firmware.flavor" to "klipper", "hardware.machine.m.firmware.arcs" to false, "hardware.machine.m.firmware.exclude_object" to true,
        "hardware.machine.m.gcode.start" to "G28", "hardware.machine.m.gcode.end" to "M84",
        "hardware.tool.t1.machine" to "m", "hardware.tool.t1.index" to 1L, "hardware.tool.t1.bore" to "0.4 mm",
        "hardware.tool.t1.layer.height.min" to "= tool.bore / 5", "hardware.tool.t1.layer.height.max" to "= tool.bore * 0.75",
        // white prints its first layer at 215 C (pla-generic's first layer: the print temperature + 5)
        "hardware.filament.white.like" to "pla-generic", "hardware.filament.white.temperature.print" to "210 C",
        "hardware.machine.m.loadout.plate" to "pei", "hardware.machine.m.loadout.tool.t1.filament" to "white")
    val window = listOf("hardware.filament.white.first_layer.window.pei.bore" to "0.4 mm",
        "hardware.filament.white.first_layer.window.pei.\"210 C\"" to t210, "hardware.filament.white.first_layer.window.pei.\"215 C\"" to t215)
    val box = ModelObject("box", listOf("box"), listOf(1), "box.stl")
    fun setup(vararg extra: Pair<String, Any>): ConfigSetup.Result {
        val entries = ConfigFiles.bundled() + (machine + extra).map { (k, v) -> ConfigFiles.Entry(ConfigFiles.value("$k=0").path, v, "test") }
        return ConfigSetup.setup(Config(ConfigStack(entries), listOf(box)))
    }
    fun first(r: ConfigSetup.Result) = (Resolver.resolve(r.setup, "plan.mechanism.adhesion.first_layer.height", Context(0)).value as Double) to (Resolver.resolve(r.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).value as Double)

    "the window decides the first layer below a plan lock and over the filament's, the machine's and plymeld's values" {
        // without a window: plymeld's stand-in height (the bundled plan: the layer height, 0.2 mm) and pla-generic's 80 mm/s
        first(setup()) shouldBe (0.2 to 80.0)
        // with it, at white's first-layer temperature, 215 C: 0.28 mm at 140 mm/s, over the filament's and the machine's speeds
        val w = setup(*window.toTypedArray(), "hardware.filament.white.motion.first_layer.speed" to "70 mm/s", "hardware.machine.m.motion.first_layer.speed" to "50 mm/s")
        first(w).first shouldBe (0.28 plusOrMinus 1e-9); first(w).second shouldBe 140.0
        Resolver.resolve(w.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).from shouldContain "first-layer window"
        w.firstLayer.single().let { it.heightMm shouldBe 0.28; it.speedMmS shouldBe 140.0; it.why shouldContain "215 C" }
        // a plan value in a config locks each over the window
        first(setup(*window.toTypedArray(), "plan.mechanism.motion.first_layer.speed" to "35 mm/s")) shouldBe (0.28 to 35.0)
        first(setup(*window.toTypedArray(), "plan.mechanism.adhesion.first_layer.height" to "0.24 mm")) shouldBe (0.24 to 140.0)
        first(setup(*window.toTypedArray(), "plan.object.box.mechanism.adhesion.first_layer.height" to "0.24 mm")).first shouldBe 0.24
        // between the tables (212 C first layer): good only where good in both: 0.28 mm, its reach 140 (180 is over at 210) -> 100
        first(setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "212 C")) shouldBe (0.28 to 100.0)
    }
    "a speed set for the filament on this plate: the window chooses the height at that speed; a plan lock still wins; no good height at it: a warning, the speed stands" {
        val at100 = setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "100 mm/s", "hardware.filament.white.motion.first_layer.speed" to "70 mm/s")
        first(at100) shouldBe (0.28 to 100.0)
        Resolver.resolve(at100.setup, "plan.mechanism.motion.first_layer.speed", Context(0)).from shouldContain "the speed set for this plate, 100 mm/s"
        at100.firstLayer.single().why shouldContain "100 mm/s row grades 0.28 mm good"
        // a speed between the rows takes the slowest row at or above it
        at100.let { first(setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "90 mm/s")) shouldBe (0.28 to 90.0) }
        first(setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "100 mm/s", "plan.mechanism.motion.first_layer.speed" to "35 mm/s")) shouldBe (0.28 to 35.0)
        // 250 mm/s: the 260 row is bad or over at every height: the window is not used, the plate's speed stands over the filament's 70
        val fast = setup(*window.toTypedArray(), "hardware.filament.white.bed.pei.first_layer.speed" to "250 mm/s", "hardware.filament.white.motion.first_layer.speed" to "70 mm/s")
        first(fast) shouldBe (0.2 to 250.0)
        fast.warnings.any { "no height is graded good at 250 mm/s" in it } shouldBe true
        // control: on another plate the speed means nothing
        first(setup(*window.toTypedArray(), "hardware.filament.white.bed.cool.first_layer.speed" to "100 mm/s")) shouldBe (0.28 to 140.0)
    }

    "the tool's first_layer.height.max widens the first layer's heights only; control: unset, layer.height.max as before" {
        // 215 C, first_max 0.36: 0.28 reaches 180 alone -> still 0.28 at 140; at 210 C 0.28 / 0.32 / 0.36 tie at 140 -> 0.32 at 100
        val wide = arrayOf<Pair<String, Any>>(*window.toTypedArray(), "hardware.tool.t1.first_layer.height.max" to "0.36 mm")
        first(setup(*wide)) shouldBe (0.28 to 140.0)
        val hot = setup(*wide, "hardware.filament.white.temperature.first_layer" to "210 C")
        first(hot).first shouldBe (0.32 plusOrMinus 1e-9); first(hot).second shouldBe 100.0
        hot.firstLayer.single().why shouldContain "as at 0.28 mm, 0.36 mm; the nearest the middle of the good heights 0.28 mm to 0.36 mm"
        // control: unset, the 210 C first layer stays within layer.height.max (0.3 mm): 0.28 mm, and the reason says why
        val narrow = setup(*window.toTypedArray(), "hardware.filament.white.temperature.first_layer" to "210 C")
        first(narrow) shouldBe (0.28 to 100.0)
        narrow.firstLayer.single().why shouldContain "0.32 mm, 0.36 mm not counted: outside the tool's first-layer range, 0.08 mm to 0.30 mm (its layer.height.min and layer.height.max; no first_layer.height.max set)"
        // the layers above keep the tool's layer.height.max
        (Resolver.resolve(hot.setup, "hardware.tool.layer.height.max", Context(0)).value as Double) shouldBe (0.3 plusOrMinus 1e-9)
        // a first-layer height set above what the tool lays as a first layer is warned about; within first_layer.height.max it is not
        setup("plan.mechanism.adhesion.first_layer.height" to "0.32 mm").warnings.single() shouldContain "outside what tool 't1' lays as a first layer, 0.08 mm to 0.30 mm"
        setup("plan.mechanism.adhesion.first_layer.height" to "0.32 mm", "hardware.tool.t1.first_layer.height.max" to "0.36 mm").warnings shouldBe emptyList()
        setup("plan.mechanism.adhesion.first_layer.height" to "0.4 mm", "hardware.tool.t1.first_layer.height.max" to "0.36 mm").warnings.single() shouldContain "0.08 mm to 0.36 mm (its layer.height.min and first_layer.height.max)"
    }

    "a window not used falls back with a warning: another bore, another plate, a temperature outside it" {
        // each: the same first layer as without the window (plymeld's stand-in height, pla-generic's speed), and a warning
        fun fallsBack(says: String, vararg extra: Pair<String, Any>) {
            val r = setup(*window.toTypedArray(), *extra)
            first(r) shouldBe first(setup(*extra)); first(r).second shouldBe 80.0
            r.firstLayer shouldBe emptyList(); r.warnings.single() shouldContain says
        }
        fallsBack("measured with a 0.40 mm nozzle; tool 't1' has 0.60 mm", "hardware.tool.t1.bore" to "0.6 mm")
        fallsBack("for build plate 'pei', not for 'cool'", "hardware.machine.m.loadout.plate" to "cool")
        fallsBack("outside that", "hardware.filament.white.temperature.first_layer" to "230 C")
        // control: as loaded, used and no warning
        setup(*window.toTypedArray()).warnings shouldBe emptyList()
    }

    "a config with a tile left ungraded, no bore or a plate it does not have is refused, naming it" {
        val ungraded = t210.replace(" 60 mm/s         bad       marginal  good", " 60 mm/s         bad       marginal  ?   ")
        shouldThrow<ConfigError> { setup(window[0], "hardware.filament.white.first_layer.window.pei.\"210 C\"" to ungraded) }.message!! shouldContain "row 60 mm/s, column 0.28 mm: not graded yet"
        shouldThrow<ConfigError> { setup("hardware.filament.white.first_layer.window.pei.\"210 C\"" to t210) }.message!! shouldContain "no bore"
        shouldThrow<ConfigError> { setup("hardware.filament.white.first_layer.window.glass.bore" to "0.4 mm", "hardware.filament.white.first_layer.window.glass.\"210 C\"" to t210) }
            .message!! shouldContain "there is no build plate 'glass'"
        shouldThrow<ConfigError> { setup(window[0], "hardware.filament.white.first_layer.window.pei.hot" to t210) }.message!! shouldContain "'hot' is not a temperature"
        // control: the window as recorded is taken
        setup(*window.toTypedArray()).firstLayer.size shouldBe 1
    }
})
