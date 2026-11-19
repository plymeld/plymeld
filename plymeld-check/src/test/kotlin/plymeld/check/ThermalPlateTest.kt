package plymeld.check

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.core.calibrate.PathValues
import plymeld.core.calibrate.PlateInstructions
import plymeld.core.calibrate.ThermalCheck
import plymeld.core.calibrate.ThermalGeometry
import plymeld.core.calibrate.ThermalInstructions
import plymeld.core.calibrate.ThermalPlate
import plymeld.core.calibrate.ThermalSpec
import plymeld.model.SliceSettings

/**
 * The thermal plate: five sections on one slab, two gauges joined to it, each banded layer made up to its time by a parked wait;
 * printed exactly as specified, its read-back check can fail (each control below is caught), the same inputs write the same
 * g-code, a band its layer cannot reach is refused, and its page and its isometric picture name every section.
 */
class ThermalPlateTest : StringSpec({
    val setup = testSetup()
    val s = SliceSettings.of(setup)
    fun spec(pinTimes: List<Double> = ThermalGeometry.PIN_TIMES) = ThermalSpec(0, "test", 200, 200, 420, 500, 1.75, 1.0, 210, 215, 100.0,
        adhesion = PathValues(50.0, 10000.0, 1.0, 0.8), frame = PathValues(179.0, 10000.0, 1.0, 0.8), visible = PathValues(100.0, 3000.0, 1.0, 0.8),
        bridge = PathValues(30.0, 3000.0, 0.9, 0.8), pinTimesS = pinTimes, liftUm = 200, originXUm = 40_000, originYUm = 40_000)
    val sp = spec()
    fun gcode(p: ThermalPlate) = p.gcode(s, setup.foreignTemplateVars, "test")
    val text by lazy { gcode(ThermalPlate(sp)) }
    fun ctl(vararg w: String) = gcode(ThermalPlate(sp, without = w.toSet()))
    fun layerS(t: String) = plymeld.core.Predict.gcode(t, s.machineDescription, null, plymeld.core.Predict.limits(s)).layerS
    fun check(t: String) = ThermalCheck.check(sp, t, layerS(t))
    fun expect() = plymeld.core.check.GcodeCheck.Expect(0.0, 0.0, 500.0, 500.0, 300.0, maxPrintSpeed = 1000.0, maxTravelSpeed = 1000.0, maxAccel = 100000.0,
        retractLengths = setOf(0.8, s.retractMm), startTemplate = "", endTemplate = "")
    fun gcheck(t: String) = plymeld.core.check.GcodeCheck.check(t.lines().filterNot { it.startsWith("EXCLUDE_OBJECT_DEFINE") }.joinToString("\n"), expect())

    "the layout: one slab with every section, the gauges in front, fitting a 220 mm bed with the parking room; the zones' heights" {
        // the plate, the slab's chamfered corner its origin: under 160 x 140 mm in all, so 220 x 220 holds it and 10 mm to park in front and behind
        (sp.bounds.w <= 160_000L) shouldBe true; (sp.bounds.d + 2 * 10_000L <= 160_000L) shouldBe true
        sp.layers shouldBe 4 + 150; sp.topZ shouldBe 30_800L
        // the strands lie on the layer above the piers' 6 mm; the pins' bands start at 15 mm; every fin's top under the upper zone
        sp.strandLayer shouldBe 30; sp.pedestalLayers shouldBe 75; sp.pinBandLayers shouldBe 15
        for (k in sp.finTimesS.indices) (sp.finTopAbove(k) <= ThermalGeometry.upperFrom) shouldBe true
        // the bands' layers: each fin's 5, each pin band's 15 (the plate's last layer has nothing above it to wait for)
        sp.bandTargets().size shouldBe 4 * 5 + 5 * 15 - 1
        // the compass's tabs step out tan(50 deg) x 0.2 mm a layer over 6 cos(50 deg) mm of height
        sp.tabStep shouldBe 238L; sp.tabLayers shouldBe 19
        // the feeler's steps: the piers' 6 mm less 2, 1 and 0.5 mm, in whole layers at or under it
        (0..2).map { sp.feelerThick(it) } shouldBe listOf(4_000L, 5_000L, 5_400L)
        // the ring card's holes: each pin plus 0.3 mm
        sp.pinDiametersUm.indices.map { sp.holeText(it) } shouldBe listOf("2.3", "3.3", "4.3", "6.3")
        shouldThrow<IllegalArgumentException> { ThermalSpec(0, "t", 200, 200, 420, 500, 1.75, 1.0, 210, 215, 100.0, sp.adhesion, sp.frame, sp.visible, sp.bridge,
            finTimesS = listOf(45.0, 60.0, 90.0, 120.0, 150.0, 180.0), liftUm = 200) }.message!! shouldContain "rises into the pins' upper zone"
    }

    "the plate as generated passes both checks; independently, each band's layers take their time and wait parked off the plate" {
        check(text).shouldBeEmpty()
        gcheck(text).shouldBeEmpty()
        val ls = layerS(text)
        // pin band 1 (5 s) and fin 1's band (45 s), by the prediction of the file as written
        for (i in sp.pinBandRange(0)) ls[sp.plateLayer(i)] shouldBe (5.0 plusOrMinus 0.01)
        for (i in sp.finBandRange(0)) ls[sp.plateLayer(i)] shouldBe (45.0 plusOrMinus 0.01)
        // every banded layer has a wait (G4); the slab's first layer at 215 C, everything above at 210 C, each waited for
        val lines = text.lines()
        (lines.count { it.startsWith("G4 P") } >= sp.bandTargets().size) shouldBe true
        lines.filter { it.startsWith("M109") }.distinct() shouldBe listOf("M109 S215 T0", "M109 S210 T0")
    }

    "the same inputs write the same g-code, byte for byte" {
        gcode(ThermalPlate(spec())) shouldBe text
    }

    "a band its layers cannot reach is refused, naming the band and both times" {
        val e = shouldThrow<IllegalArgumentException> { gcode(ThermalPlate(spec(listOf(1.0, 6.0, 8.0, 10.0, 15.0)))) }
        e.message!! shouldContain "pin band 1 (1 s): layer 80 takes"
        e.message!! shouldContain "s without a wait, longer than its 1.0 s"
    }

    "controls: the waits, a strand row's fan, the strands' height and reach, a fin's band and fan, a tab, the lag wall's switch and direction, a gauge's tabs, a part off the slab, the settle are caught" {
        check(ctl("waits")).any { it.contains("pin band 1: layer") && it.contains("takes") } shouldBe true
        check(ctl("strandfan")).any { it.contains("strand 2.1: fan 0 %, not its row's 50 %") } shouldBe true
        check(ctl("strandz")).any { it.contains("not on the layer above its piers' top") } shouldBe true
        check(ctl("strandshort")).any { it.contains("not from its left pier") } shouldBe true
        check(ctl("finband")).any { it.contains("fin 1.1: its band is on layers") } shouldBe true
        check(ctl("finfan")).any { it.contains("fin 2.1: a line at fan 0 %, not its row's 50 %") } shouldBe true
        check(ctl("tabs")).any { it.contains("the compass has 7 tab(s), not 8") } shouldBe true
        check(ctl("lagswitch")).any { it.contains("the fan switches at x") && it.contains("upper half") } shouldBe true
        check(ctl("lagdir")).any { it.contains("right to left") } shouldBe true
        check(ctl("gaugetab")).any { it.contains("the card is joined to the slab by 0 break-off tab(s)") } shouldBe true
        check(ctl("offslab")).any { it.contains("'fin post 1.1' is not on the slab") } shouldBe true
        check(ctl("settle")).any { it.contains("was waited for 0.0 s") } shouldBe true
    }

    "controls in the text: a pin's speed, a fin's plastic, a line outside the cells are caught" {
        fun alter(cell: String, change: (String) -> String): String {
            val ls = text.lines().toMutableList()
            val at = ls.indexOfFirst { it == "; CELL: $cell" }; val end = (at until ls.size).first { ls[it] == "; CELL END" }
            for (k in at until end) ls[k] = change(ls[k])
            return ls.joinToString("\n")
        }
        val faster = alter("pin 1") { l -> if (l.startsWith("G1 F")) "G1 F" + Math.round(l.removePrefix("G1 F").toDouble() * 1.5) else l }
        check(faster).any { it.contains("'pin 1': 150.00 mm/s, not 100.00") } shouldBe true
        val richer = alter("fin 1.1") { l -> Regex("(G1 X[-0-9.]+ Y[-0-9.]+ E)([0-9.]+)(.*)").matchEntire(l)?.let { it.groupValues[1] + "%.5f".format(java.util.Locale.ROOT, it.groupValues[2].toDouble() * 2) + it.groupValues[3] } ?: l }
        check(richer).any { it.contains("'fin 1.1'") && it.contains("mm of filament per mm") } shouldBe true
        val stray = text.lines().toMutableList().also { ls -> ls.addAll(ls.indexOfLast { it == "; CELL: lag outer" }, listOf("G1 X40 Y35 F6000", "G1 X45 Y35 E0.2 F1200")) }.joinToString("\n")
        check(stray).any { it.contains("'(none)'") } shouldBe true
    }

    "the page names every section, how to read it, the readings sheet; the isometric picture labels every section from it" {
        val d = java.nio.file.Files.createTempDirectory("plymeld-thermal-page")
        val info = PlateInstructions.Info("test", "test machine", 0, "test", "thermal.gcode", 90.0, "not set", 60, 50.0, 179.0, 0.8)
        ThermalInstructions.write(d.resolve("thermal.md"), sp, ThermalPlate(sp), info)
        val md = java.nio.file.Files.readString(d.resolve("thermal.md"))
        for (h in listOf("### 1. The strands", "### 2. The pins", "### 3. The fins", "### 4. The compass", "### 5. The lag wall", "## Readings sheet")) md shouldContain h
        md shouldContain "| strands, row 50% | the longest span the 5 mm step slides under"
        md shouldContain "| pin 6 mm | the band the 6.3 mm hole stops in"
        md shouldContain "| fins, row 100% | the first fin from the left that snaps"
        md shouldContain "decided before printing"
        md shouldContain "record them on the sheet below"
        (java.nio.file.Files.size(d.resolve("thermal.png")) > 10_000) shouldBe true
        // the isometric picture from the g-code and the page; a page that no longer says what a region's label quotes refuses it
        java.nio.file.Files.writeString(d.resolve("thermal.gcode"), text)
        val log = ArrayList<String>()
        plymeld.check.plate.PlateIso.render(d.resolve("thermal.gcode"), d.resolve("thermal.md"), d.resolve("thermal.iso.png")) { log += it }
        log.any { it.contains("unmapped cells: none") } shouldBe true
        (java.nio.file.Files.size(d.resolve("thermal.iso.png")) > 50_000) shouldBe true
        java.nio.file.Files.writeString(d.resolve("thermal.md"), md.replace("always left to right", "left to right"))
        shouldThrow<IllegalArgumentException> { plymeld.check.plate.PlateIso.render(d.resolve("thermal.gcode"), d.resolve("thermal.md"), d.resolve("bad.png")) {} }.message!! shouldContain "always left to right"
    }
})
