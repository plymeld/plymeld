package plymeld.gcode

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * The start / end g-code template engine ([Template]) on synthetic templates and values: every
 * construct its header documents — `[name]` substitution (a vector gives slot 0), `{expr}` output,
 * `{if}..{elsif}..{else}..{endif}` (across lines, nested), numbers that keep their integer / decimal
 * type, strings, `name[i]`, the operators, `min()` / `max()` / `int()` — and the errors. Every expected
 * output is worked by hand from those rules; no other slicer's output and no user model is involved.
 * Control: the expectation on a whole start block is run with one value deliberately wrong (a
 * substitution of the wrong setting) and must fail.
 */
class TemplateTest : StringSpec({
    // a synthetic two-tool setup, values typed as plymeld's writer or an importer hands them over
    val vars: Map<String, Template.Value> = mapOf(
        "nozzle_temperature" to Template.num(215),
        "first_layer_nozzle_temperature" to Template.num(220),
        "first_layer_bed_temperature" to Template.num(60),
        "layers" to Template.num(275),
        "max_z" to Template.num(55.2),
        "max_layer_z" to Template.num(55.2),
        "max_print_height" to Template.num(256),
        "z_hop" to Template.num(0.4),
        "initial_tool" to Template.num(1),
        "max_filament_flow" to Template.parse("12"),
        "filament_type" to Template.Vec(listOf(Template.Str("PLA"), Template.Str("PETG"))),
        "nozzle_diameter" to Template.Vec(listOf(Template.parse("0.4"), Template.parse("0.6"))),
        "machine_name" to Template.Str("test machine"),
        "blank" to Template.Str(""),
        "zero_text" to Template.Str("0"),
        "false_text" to Template.Str("false"),
        "no_tools" to Template.Vec(emptyList()),
    )
    fun changed(vararg kv: Pair<String, Template.Value>): (String) -> Template.Value? { val m = vars + kv; return { m[it] } }
    fun render(t: String) = Template.render(t, vars::get)

    // a start block using every construct at once
    val start = "M140 S[first_layer_bed_temperature]\n" +
        "{if first_layer_nozzle_temperature > 230}M109 S[first_layer_nozzle_temperature]\n" +
        "{elsif first_layer_nozzle_temperature > 210}M104 S{first_layer_nozzle_temperature - 20}\n" +
        "{else}M104 S[nozzle_temperature]\n" +
        "{endif}T[initial_tool] ; {filament_type[initial_tool]} through a {nozzle_diameter[initial_tool]} mm nozzle\n" +
        "{if layers > 100}{if max_z > 50}; tall{else}; short{endif}{endif}\n" +
        "G1 Z{z_hop * 2} F{min(max_filament_flow, 10) * 60}\n"
    fun expectStart(lookup: (String) -> Template.Value?, want: String) { Template.render(start, lookup) shouldBe want }

    "a whole start block: substitution, branches, nesting, indexing and arithmetic together" {
        expectStart(vars::get, "M140 S60\nM104 S200\nT1 ; PETG through a 0.6 mm nozzle\n; tall\nG1 Z0.8 F600\n")
        // each other branch, by changing only the value it tests
        expectStart(changed("first_layer_nozzle_temperature" to Template.num(240)), "M140 S60\nM109 S240\nT1 ; PETG through a 0.6 mm nozzle\n; tall\nG1 Z0.8 F600\n")
        expectStart(changed("first_layer_nozzle_temperature" to Template.num(200)), "M140 S60\nM104 S215\nT1 ; PETG through a 0.6 mm nozzle\n; tall\nG1 Z0.8 F600\n")
        expectStart(changed("max_z" to Template.num(20)), "M140 S60\nM104 S200\nT1 ; PETG through a 0.6 mm nozzle\n; short\nG1 Z0.8 F600\n")
        expectStart(changed("layers" to Template.num(50)), "M140 S60\nM104 S200\nT1 ; PETG through a 0.6 mm nozzle\n\nG1 Z0.8 F600\n")
    }

    "control: the same expectation fails when one value is substituted wrongly" {
        // nozzle_temperature handed over for first_layer_nozzle_temperature: 215 takes the elsif branch too, but 215 - 20 = 195
        val swapped: (String) -> Template.Value? = { n -> if (n == "first_layer_nozzle_temperature") vars["nozzle_temperature"] else vars[n] }
        shouldThrow<AssertionError> { expectStart(swapped, "M140 S60\nM104 S200\nT1 ; PETG through a 0.6 mm nozzle\n; tall\nG1 Z0.8 F600\n") }
            .message!! shouldContain "M104 S195"
        // and a wrong tool: slot 0 instead of slot 1
        shouldThrow<AssertionError> { expectStart(changed("initial_tool" to Template.num(0)), "M140 S60\nM104 S200\nT1 ; PETG through a 0.6 mm nozzle\n; tall\nG1 Z0.8 F600\n") }
            .message!! shouldContain "PLA through a 0.4 mm nozzle"
    }

    "an end block: park above the print, or at the machine's top when there is no room" {
        val end = "{if max_layer_z < max_print_height - 10}G1 Z{max_layer_z + 10}{else}G1 Z{max_print_height}{endif}"
        Template.render(end, vars::get) shouldBe "G1 Z65.2"
        Template.render(end, changed("max_layer_z" to Template.num(250.0))) shouldBe "G1 Z256"
    }

    "[name]: a value, a vector's slot 0; brackets around anything but a name stay text" {
        render("M104 S[nozzle_temperature] T[initial_tool]") shouldBe "M104 S215 T1"
        render("; [filament_type] [nozzle_diameter] [machine_name]") shouldBe "; PLA 0.4 test machine"
        render("M117 [1] [a b] x[ y") shouldBe "M117 [1] [a b] x[ y"
    }

    "numbers keep their type: integer / integer divides as integers, decimals print to 6 places at most" {
        render("{layers}") shouldBe "275"
        render("{max_z}") shouldBe "55.2"
        render("{max_z + z_hop}") shouldBe "55.6"
        render("{24/20} {24/20*60} {24/20.0} {2/3*1.0}") shouldBe "1 60 1.2 0"
        render("{1.5 * 2}") shouldBe "3"               // a whole decimal prints without a point
        render("{1/3.0} {2/3.0}") shouldBe "0.333333 0.666667"
        render("{0 - 0.0000001}") shouldBe "0"         // rounds to 0, never "-0"
        render("{7 % 3} {7.5 % 2}") shouldBe "1 1.5"
    }

    "precedence, parentheses and unary minus" {
        render("{2 + 3 * 4} {(2 + 3) * 4} {-2 + 5} {10 - 4 - 3} {-(1 + 2)}") shouldBe "14 20 3 3 -3"
    }

    "comparisons and logic give 1 or 0, in words or symbols" {
        render("{1 < 2}{2 <= 2}{3 > 4}{3 >= 4}{0.4 == 0.4}{1 != 1}") shouldBe "110010"
        render("{1 and 0}{1 or 0}{not 0}{1 && 1}{0 || 0}{!1}{!(1 != 1)}{true}{false}") shouldBe "011100110"
    }

    "strings: literals, + joins, == and != compare as text; empty, \"0\" and \"false\" are false" {
        render("{\"T\" + initial_tool} {machine_name}") shouldBe "T1 test machine"
        render("{if filament_type[0] == \"PLA\"}cool{else}warm{endif}") shouldBe "cool"
        render("{if filament_type == \"PETG\"}petg{else}slot 0 is {filament_type}{endif}") shouldBe "slot 0 is PLA"
        render("{if filament_type[1] != \"PLA\"}not pla{endif}") shouldBe "not pla"
        render("{if blank}a{else}b{endif}{if zero_text}a{else}b{endif}{if false_text}a{else}b{endif}{if machine_name}c{endif}") shouldBe "bbbc"
    }

    "name[i]: any slot, an expression as the index; a scalar indexed is itself" {
        render("{nozzle_diameter[1]} {nozzle_diameter[initial_tool - 1] * 2} {layers[3]}") shouldBe "0.6 0.8 275"
    }

    "min, max and int (floor), keeping the chosen argument's type" {
        render("{min(nozzle_temperature, 210)} {max(first_layer_bed_temperature, 55, 70)} {min(z_hop, 1)}") shouldBe "210 70 0.4"
        render("{int(max_z)} {int(-0.5)}") shouldBe "55 -1"
    }

    "parse: integers stay integers, decimals are numbers, anything else is text" {
        Template.parse("12") shouldBe Template.Num(12.0, true)
        Template.parse("0.2") shouldBe Template.Num(0.2, false)
        Template.parse("PLA") shouldBe Template.Str("PLA")
    }

    "errors, never a blank: unknown names and functions, bad indexes, malformed blocks and expressions" {
        shouldThrow<IllegalStateException> { Template.render("M104 S[no_such_setting]") { null } }.message!! shouldContain "no_such_setting"
        shouldThrow<IllegalStateException> { Template.render("{no_such + 1}") { null } }.message!! shouldContain "no_such"
        shouldThrow<IllegalStateException> { render("{floor(max_z)}") }.message!! shouldContain "floor"
        shouldThrow<IllegalStateException> { render("{nozzle_diameter[2]}") }.message!! shouldContain "out of range"
        shouldThrow<IllegalStateException> { render("[no_tools]") }.message!! shouldContain "empty"
        shouldThrow<IllegalStateException> { render("{min(machine_name, 1)}") }.message!! shouldContain "takes numbers"
        shouldThrow<IllegalStateException> { render("{\"a\" - 1}") }.message!! shouldContain "on strings"
        shouldThrow<IllegalStateException> { render("{if layers > 1}x") }.message!! shouldContain "unterminated"
        shouldThrow<IllegalStateException> { render("x{endif}") }.message!! shouldContain "unexpected"
        shouldThrow<IllegalStateException> { render("x {1 + 1") }.message!! shouldContain "unbalanced"
        shouldThrow<IllegalStateException> { render("{1 + }") }.message!! shouldContain "ends early"
        shouldThrow<IllegalStateException> { render("{1 2}") }.message!! shouldContain "unexpected"
    }
})
