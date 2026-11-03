package plymeld.config

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/** Formulas. Expected values worked by hand. */
class FormulaTest : StringSpec({
    val mm = Formula.Dims(length = 1); val c = Formula.Dims(temperature = 1)
    val values = mapOf(
        "tool.bore" to Formula.Q(0.5, mm), "tool.layer.height.min" to Formula.Q(0.1, mm), "tool.layer.height.max" to Formula.Q(0.3, mm),
        "filament.temperature.print" to Formula.Q(210.0, c),
        "filament.melt.flow.max" to Formula.Q(15.0, Formula.Dims(length = 3, time = -1)),
        "hardware.plate.cool-pei.bed.PLA.temperature.print" to Formula.Q(60.0, c),
        "zero" to Formula.Q(0.0, mm))
    val read: (List<String>) -> Formula.Q = { p -> values[p.joinToString(".")] ?: throw IllegalArgumentException("no value '${p.joinToString(".")}'") }
    fun len(f: String) = Formula.evaluate(f, Dimension.LENGTH, "test", read)

    "a value starting with = after trimming is a formula; control: other text is not" {
        Formula.isFormula("  = tool.bore / 2") shouldBe true
        Formula.isFormula("0.2 mm") shouldBe false
        Formula.isFormula(0.2) shouldBe false
    }
    "references, arithmetic, and units on numbers" {
        len("= tool.bore / 2") shouldBe (0.25 plusOrMinus 1e-12)
        len("=3*tool.bore") shouldBe (1.5 plusOrMinus 1e-12)
        len("= tool.bore + 0.05 mm") shouldBe (0.55 plusOrMinus 1e-12)
        len("= tool.bore + 0.05mm - (0.1 mm)") shouldBe (0.45 plusOrMinus 1e-12)
        len("= -tool.bore + 1 mm") shouldBe (0.5 plusOrMinus 1e-12)
        Formula.evaluate("= filament.temperature.print + 5 C", Dimension.TEMPERATURE, "t", read) shouldBe (215.0 plusOrMinus 1e-12)
        Formula.evaluate("= filament.temperature.print - 40 C", Dimension.TEMPERATURE, "t", read) shouldBe (170.0 plusOrMinus 1e-12)
        Formula.evaluate("= 15%", Dimension.RATIO, "t", read) shouldBe (0.15 plusOrMinus 1e-12)
    }
    "a key with a hyphen is one path segment" {
        Formula.evaluate("= hardware.plate.cool-pei.bed.PLA.temperature.print", Dimension.TEMPERATURE, "t", read) shouldBe (60.0 plusOrMinus 1e-12)
    }
    "units combine through * and /: flow / (width x height) is a speed" {
        Formula.evaluate("= filament.melt.flow.max / (0.5 mm * 0.2 mm)", Dimension.SPEED, "t", read) shouldBe (150.0 plusOrMinus 1e-9)
    }
    "min, max, clamp" {
        len("= min(tool.layer.height.max, 0.25 mm)") shouldBe (0.25 plusOrMinus 1e-12)
        len("= max(tool.layer.height.min, tool.bore / 4)") shouldBe (0.125 plusOrMinus 1e-12)
        len("= clamp(tool.bore, tool.layer.height.min, tool.layer.height.max)") shouldBe (0.3 plusOrMinus 1e-12)      // above: the high end
        len("= clamp(0.05 mm, tool.layer.height.min, tool.layer.height.max)") shouldBe (0.1 plusOrMinus 1e-12)        // below: the low end
        len("= clamp(0.2 mm, tool.layer.height.min, tool.layer.height.max)") shouldBe (0.2 plusOrMinus 1e-12)         // inside: itself
    }
    "round, floor, ceil — to a whole number of the unit, or to a multiple of a step" {
        len("= clamp(round(tool.bore / 2, 0.04 mm), tool.layer.height.min, tool.layer.height.max)") shouldBe (0.24 plusOrMinus 1e-12)   // 0.25 -> 0.24
        len("= floor(0.27 mm, 0.04 mm)") shouldBe (0.24 plusOrMinus 1e-12)
        len("= ceil(0.25 mm, 0.04 mm)") shouldBe (0.28 plusOrMinus 1e-12)
        Formula.evaluate("= floor(64.7 mm / 0.2 mm)", Dimension.COUNT, "n", read) shouldBe (323.0 plusOrMinus 1e-12)
        Formula.evaluate("= ceil(64.7 mm / 0.2 mm)", Dimension.COUNT, "n", read) shouldBe (324.0 plusOrMinus 1e-12)
    }
    "errors say what is wrong: units that do not fit, a wrong result, a missing name, division by zero, an unknown function" {
        shouldThrow<IllegalArgumentException> { len("= tool.bore + 5 C") }.message!! shouldContain "cannot add"
        shouldThrow<IllegalArgumentException> { len("= filament.temperature.print") }.message!! shouldContain "comes out as C"
        shouldThrow<IllegalArgumentException> { len("= nozzle.size / 2") }.message!! shouldContain "no value 'nozzle.size'"
        shouldThrow<IllegalArgumentException> { len("= tool.bore / (zero / 1 mm)") }.message!! shouldContain "division by zero"
        shouldThrow<IllegalArgumentException> { len("= sqrt(tool.bore)") }.message!! shouldContain "no function 'sqrt'"
        shouldThrow<IllegalArgumentException> { len("= min(tool.bore, 5 C)") }.message!! shouldContain "same kind"
        shouldThrow<IllegalArgumentException> { len("= round(tool.bore, 0.04 s)") }.message!! shouldContain "step"
        shouldThrow<IllegalArgumentException> { len("= (tool.bore / 2") }.message!! shouldContain "')' expected"
    }

    // switches: true / false, not / and / or, comparisons, words, if()
    val more: Map<String, Formula.V> = values + mapOf("machine.firmware.arcs" to Formula.B(false), "filament.kind" to Formula.T("PETG"),
        "machine.firmware.exclude_object" to Formula.B(true))
    val readAny: (List<String>) -> Formula.V = { p -> more[p.joinToString(".")] ?: throw IllegalArgumentException("no value '${p.joinToString(".")}'") }
    fun sw(f: String) = Formula.evaluateSwitch(f, "test", readAny)

    "a switch formula: true / false, not, and, or, and a reference to another switch" {
        sw("= true") shouldBe true
        sw("= not machine.firmware.arcs") shouldBe true
        sw("= machine.firmware.exclude_object and not machine.firmware.arcs") shouldBe true
        sw("= machine.firmware.arcs or false") shouldBe false
        sw("= not (machine.firmware.arcs or machine.firmware.exclude_object)") shouldBe false
    }
    "comparisons: two quantities of one kind, two words, two switches" {
        sw("= filament.temperature.print > 200 C") shouldBe true
        sw("= filament.temperature.print >= 210 C") shouldBe true
        sw("= filament.temperature.print < 210 C") shouldBe false
        sw("= tool.bore == 0.5 mm") shouldBe true
        sw("= tool.bore != 0.4 mm") shouldBe true
        sw("= filament.kind == 'PETG'") shouldBe true
        sw("= filament.kind != 'PLA'") shouldBe true
        sw("= machine.firmware.arcs == false") shouldBe true
    }
    "if(condition, a, b) picks a value of either kind" {
        Formula.evaluate("= if(filament.kind == 'PETG', 60 mm/s, 80 mm/s)", Dimension.SPEED, "t", readAny) shouldBe (60.0 plusOrMinus 1e-12)
        sw("= if(machine.firmware.arcs, false, true)") shouldBe true
    }
    "controls: mixed kinds and wrong results are refused, never guessed" {
        shouldThrow<IllegalArgumentException> { sw("= filament.temperature.print > 1 mm") }.message!! shouldContain "cannot compare"
        shouldThrow<IllegalArgumentException> { sw("= tool.bore and true") }.message!! shouldContain "'and' takes true or false"
        shouldThrow<IllegalArgumentException> { sw("= filament.kind < 'PLA'") }.message!! shouldContain "'==' or '!=' only"
        shouldThrow<IllegalArgumentException> { sw("= tool.bore") }.message!! shouldContain "but the value is true or false"
        fun lenAny(f: String) = Formula.evaluate(f, Dimension.LENGTH, "test", readAny)
        shouldThrow<IllegalArgumentException> { lenAny("= not machine.firmware.arcs") }.message!! shouldContain "comes out as true or false"
        shouldThrow<IllegalArgumentException> { lenAny("= if(true, 1 mm, 2 C)") }.message!! shouldContain "same kind"
        shouldThrow<IllegalArgumentException> { lenAny("= tool.bore + machine.firmware.arcs") }.message!! shouldContain "takes quantities"
    }
})
