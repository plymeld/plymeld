package plymeld.gcode

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The bad-number rule ([BadNumbers]) on hand-written lines, each kind it refuses beside a line of the same command it must
 * pass (the control: a rule that refused everything would fail it), and the lines plymeld's bundled machines' templates and
 * Klipper's extended commands send (text values, lists, names), which it must pass. Also: a template placeholder that is not
 * a finite number fails the render ([Template]), where written it would read as 0.
 */
class BadNumbersTest : StringSpec({
    fun refuses(line: String, why: String) { val r = BadNumbers.inCommand(line); (r ?: "passed") shouldContain why }
    fun passes(line: String) { BadNumbers.inCommand(line) shouldBe null }

    "a parameter that is not a finite number is refused, letter and Klipper forms; control: the same command with a number passes" {
        passes("G1 X10.5 Y-2 E0.03 F1500")
        for (bad in listOf("G1 XNaN Y2", "G1 X10 Y-Infinity", "G1 X1e400", "G1 X1.2.3", "G1 F1500f", "G1 X--5", "M104 SNaN T1", "G1 Ffast"))
            refuses(bad, "not a")
        passes("SET_VELOCITY_LIMIT ACCEL=5000 ACCEL_TO_DECEL=2500")
        refuses("SET_VELOCITY_LIMIT ACCEL=NaN", "'ACCEL=NaN' is not a finite number")
        refuses("SET_PRESSURE_ADVANCE ADVANCE=1.2.3", "'ADVANCE=1.2.3' is not a number")
        passes("SET_PRESSURE_ADVANCE ADVANCE=0.04")
        passes("G1 X1e2 Y2")                                           // an exponent within a double: a number
    }

    "a value missing where one is needed is refused; control: a bare axis where it is a command of its own passes" {
        refuses("G1 X Y10", "'X' has no value")
        refuses("G1 F", "'F' has no value: a feed rate needs a number")
        refuses("M104 S T0", "'S' has no value: a temperature needs a number")
        passes("G28 X Y")
        passes("M84 E")
    }

    "a feed rate of 0 or below on a move is refused; control: F1 passes" {
        refuses("G1 F0", "a move at feed rate F0, no speed")
        refuses("G1 X1 Y2 F-60", "no speed")
        refuses("G0 X1 F0.0", "no speed")
        passes("G1 F1")
        passes("G1 X1 Y2 F1")
    }

    "a temperature, fan, or wait below 0 and an acceleration of 0 or below are refused; control: 0 and above pass where 0 means off" {
        refuses("M104 S-5 T0", "a temperature of -5 °C, below 0"); passes("M104 S0 T0")
        refuses("M109 R-1", "below 0"); refuses("M140 S-60", "below 0"); refuses("M190 S-1", "below 0"); passes("M140 S60")
        refuses("M106 S-1", "a fan speed of -1, below 0"); passes("M106 S0"); passes("M106 P2 S255")
        refuses("G4 P-100", "a wait of -100, below 0"); passes("G4 P0"); passes("G4 P500")
        refuses("M204 S0", "an acceleration of 0, 0 or below"); refuses("M204 P-100 T3000", "0 or below"); passes("M204 S3000")
        refuses("SET_VELOCITY_LIMIT ACCEL=0", "ACCEL=0, 0 or below"); refuses("SET_VELOCITY_LIMIT VELOCITY=-5", "0 or below")
        passes("SET_VELOCITY_LIMIT SQUARE_CORNER_VELOCITY=0")         // 0 is a real corner setting
    }

    "text, lists, names, and comments are not numbers: passed; control: NaN in plymeld's comment is refused by inComment" {
        for (l in listOf("PRINT_START BED=60 HOTEND=210", "M106 P101 S0 ; side fans (left and right) off", "M117 Printing 3 of Infinity",
            "EXCLUDE_OBJECT_DEFINE NAME=Infinity-cube_id_0 CENTER=105,105 POLYGON=[[100,100],[110,100],[110,110]]", "EXCLUDE_OBJECT_START NAME=part_1",
            "M1002 gcode_claim_action : 2", "T0", "G28", "PAUSE", "; FEATURE: Outer wall", "", "G1 X1 Y2 ; F0 in a comment", "M23 file F0.gcode"))
            passes(l)
        BadNumbers.inComment("M104 S210 T1 ; preheat, NaN s of printing before the change")!! shouldContain "one of its estimates is broken"
        BadNumbers.inComment(";Z:Infinity")!! shouldContain "estimates is broken"
        BadNumbers.inComment("M104 S210 T1 ; preheat, 12 s of printing before the change") shouldBe null
        BadNumbers.inComment("; object start Infinity cube") shouldBe null
    }

    "scan reads every line but the source block, numbering from 1; control: a bad line in the source block is not read" {
        val lines = listOf("G28", "G1 F0", "; PLYMELD_SOURCE_BLOCK_START", "G1 F0", "; PLYMELD_SOURCE_BLOCK_END", "M104 S210 ; NaN s")
        BadNumbers.scan(lines.asSequence(), comments = true).map { it.first } shouldBe listOf(2, 6)
        BadNumbers.scan(lines.asSequence(), comments = false).map { it.first } shouldBe listOf(2)
    }

    "a template placeholder that is not a finite number fails the render; control: a finite one renders" {
        Template.render("M104 S{t}", { if (it == "t") Template.num(215.5) else null }) shouldBe "M104 S215.5"
        shouldThrow<IllegalStateException> { Template.render("M104 S{t}", { if (it == "t") Template.num(Double.NaN) else null }) }
            .message!! shouldContain "template placeholder {t} is NaN, not a number"
        shouldThrow<IllegalStateException> { Template.render("G1 Z[z]", { if (it == "z") Template.num(Double.POSITIVE_INFINITY) else null }) }
            .message!! shouldContain "template placeholder [z] is Infinity, not a number"
    }
})
