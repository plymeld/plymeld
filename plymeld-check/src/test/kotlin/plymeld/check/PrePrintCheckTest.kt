package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import plymeld.config.OrcaConfig
import plymeld.core.IncrementalSlicer
import plymeld.mesh.StlReader
import java.io.BufferedReader
import java.io.StringReader
import java.io.StringWriter

/**
 * The safety check (`plymeld investigate check`, limits in another slicer's settings format) must pass plymeld's output,
 * and FAIL deliberately broken files (control: a checker that passes everything is useless). Its limits here are a
 * hand-written settings block of test values: a 270 mm bed with a 10 mm excluded corner, 256 mm tall, nozzle 190-240 C,
 * 600 mm/s, a 75 mm head radius and the gantry 47 mm above the nozzle.
 */
class PrePrintCheckTest : StringSpec({
    val limits = mapOf(
        "printable_area" to listOf("0x0", "270x0", "270x270", "0x270"), "bed_exclude_area" to listOf("0x0", "10x0", "10x10", "0x10"),
        "printable_height" to listOf("256"), "nozzle_temperature_range_low" to listOf("190"), "nozzle_temperature_range_high" to listOf("240"),
        "machine_max_speed_x" to listOf("600"), "machine_max_speed_y" to listOf("600"), "filament_diameter" to listOf("1.75"),
        "nozzle_diameter" to listOf("0.4"), "extruder_clearance_max_radius" to listOf("75"), "extruder_clearance_height_to_rod" to listOf("47"))
    val cfg = OrcaConfig(limits)

    for (m in listOf("testplate", "holeblock")) {
        "plymeld's $m g-code passes" {
            val out = StringWriter()
            IncrementalSlicer(listOf(StlReader.read(testModelsDir().resolve("$m.stl"))), "test", m).slice(testSetup(), out)
            val findings = PrePrintCheck.check(BufferedReader(StringReader(out.toString())), cfg)
            findings.forEach { println("  $m: $it") }
            findings shouldBe emptyList()
        }
    }

    val good = "PRINT_START BED=60 HOTEND=230\nM83\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X100 Y100 Z0.2 F3000\nG1 X110 Y100 E0.4\n"
    fun rules(g: String) = PrePrintCheck.check(BufferedReader(StringReader(g)), cfg).map { it.rule }

    "control: a clean synthetic file passes" { rules(good) shouldBe emptyList() }
    "control: extrusion off the bed is caught" { rules(good + "G1 X300 Y100 E0.4\n") shouldBe listOf("bed-area") }
    "control: extrusion in the excluded corner is caught" { rules(good + "G1 X5 Y5 F3000\nG1 X6 Y6 E0.05\n") shouldBe listOf("excluded-area") }
    "control: extrusion before heating is caught" { rules("M83\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X100 Y100 Z0.2\nG1 X110 Y100 E0.4\n").contains("cold-extrusion") shouldBe true }
    "control: nozzle above the filament's range is caught" { rules(good + "M104 S280\n") shouldBe listOf("nozzle-temp") }
    "control: a unit error in E is caught" { rules(good + "G1 X120 Y100 E40\n") shouldBe listOf("extrusion-per-mm") }
    "control: without the head's clearance in the settings, a layer going down is caught" {
        val noHead = OrcaConfig(limits - "extruder_clearance_max_radius" - "extruder_clearance_height_to_rod")
        PrePrintCheck.check(java.io.BufferedReader(java.io.StringReader(good + "; CHANGE_LAYER\n; Z_HEIGHT: 0.1\n")), noHead).map { it.rule } shouldBe listOf("layer-z-increasing")
    }
    // a 4 mm column at x 100..110, then a new object starting at z 0.2 some distance to the right
    fun column(): String = good + (2..20).joinToString("") { i -> val z = "%.1f".format(java.util.Locale.ROOT, i * 0.2); "; CHANGE_LAYER\n; Z_HEIGHT: $z\nG1 X100 Y100 Z$z F3000\nG1 X110 Y100 E0.4\n" } +
        "G1 Z4.4\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\n"
    "control: a lower layer 50 mm from a taller printed column is caught (head radius 75 mm)" {
        rules(column() + "G1 X160 Y100\nG1 Z0.2\nG1 X170 Y100 E0.4\n") shouldBe listOf("head-clearance")
    }
    "a lower layer 80 mm from the column passes" {
        rules(column() + "G1 X190 Y100\nG1 Z0.2\nG1 X200 Y100 E0.4\n") shouldBe emptyList()
    }
    "control: material printed higher than the rod clearance above the nozzle is caught" {
        val tall = good + "; CHANGE_LAYER\n; Z_HEIGHT: 50\nG1 X100 Y100 Z50 F3000\nG1 X110 Y100 E0.4\nG1 Z50.4\n; CHANGE_LAYER\n; Z_HEIGHT: 0.2\nG1 X250 Y100\nG1 Z0.2\nG1 X255 Y100 E0.4\n"
        rules(tall).contains("gantry-clearance") shouldBe true
    }
    "control: a feed rate above the machine is caught" { rules(good + "G1 X120 Y120 F60000\n") shouldBe listOf("feed-rate") }
    // the numbers as the firmware reads them, the rule plymeld's writer, pre-print check and send apply (BadNumbers)
    "control: a move at F0, a NaN, a negative bed temperature, and an acceleration of 0 are caught as numbers" {
        for (bad in listOf("G1 F0\n", "G1 X120 YNaN E0.1\n", "M140 S-5\n", "SET_VELOCITY_LIMIT ACCEL=0\n"))
            ("numbers" in rules(good + bad)) shouldBe true
    }
})
