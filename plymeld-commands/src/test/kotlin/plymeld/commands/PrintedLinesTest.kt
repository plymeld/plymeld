package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

/**
 * PrintedLines: a long line broken where its facts part (sentences, then clauses, then items, then words), every piece
 * within the width, nothing lost; a short line, a path's folder, and a line that is only a command left as they are.
 */
class PrintedLinesTest : StringSpec({
    fun words(lines: List<String>) = lines.joinToString(" ").split(Regex("\\s+")).filter { it.isNotEmpty() }

    "a long line breaks at its clauses before its items, each piece within the width, its words all kept and in order" {
        val clause = { k: Int -> "clause $k: " + (1..6).joinToString(", ") { "item $k.$it with some words" } }
        val line = "  plate: " + (1..8).joinToString("; ") { clause(it) }
        val out = PrintedLines.of(line)
        (out.size > 1) shouldBe true
        out.all { it.length <= PrintedLines.WIDTH } shouldBe true
        words(out) shouldBe words(listOf(line))
        // every break at a clause's end: each line but the last ends with a semicolon, and the lines after the first are indented past it
        out.dropLast(1).all { it.endsWith(";") } shouldBe true
        out.drop(1).all { it.startsWith("    ") } shouldBe true
        // control: the same line printed whole runs over
        (line.length > PrintedLines.WIDTH) shouldBe true
    }

    "a clause too long on its own breaks at its items; a sentence ends a line before a semicolon does" {
        val items = "check: " + (1..40).joinToString(", ") { "the thing number $it" }
        PrintedLines.of(items).dropLast(1).all { it.endsWith(",") } shouldBe true
        val two = "check: " + "a fact; ".repeat(15) + "the last fact. Controls caught: " + (1..20).joinToString(", ") { "control $it" }
        PrintedLines.of(two).any { it.trimStart().startsWith("Controls caught:") } shouldBe true
    }

    "left as they are: a line within the width, one within it with a path's folder not counted, one that is only a command" {
        val short = "plate: 149 x 163.6 mm"
        PrintedLines.of(short) shouldBe listOf(short)
        val deep = "-> /" + "folder/".repeat(60) + "plate.gcode"
        PrintedLines.of(deep) shouldBe listOf(deep)
        val command = "    `plymeld calibrate pressure-advance " + "-cv a=b ".repeat(40) + "-o pa-fine.gcode`"
        PrintedLines.of(command) shouldBe listOf(command)
        // control: the command with a note beside it is broken
        (PrintedLines.of("$command (11 values)").size > 1) shouldBe true
    }
})
