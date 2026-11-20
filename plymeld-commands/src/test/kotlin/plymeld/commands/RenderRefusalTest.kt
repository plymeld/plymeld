package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import plymeld.commands.SyntheticGcode.Companion.render
import java.nio.file.Files

/**
 * `plymeld render` refuses an option that applies to no picture asked for, and two options that cannot both hold, each
 * refusal naming the options involved; and a request the file cannot answer (a name it does not write, a layer it does not
 * have, a width it does not state), naming what is missing. Control: each option is accepted where it applies.
 */
class RenderRefusalTest : StringSpec({
    val dir = Files.createTempDirectory("plymeld-render-refusal")
    val named = SyntheticGcode().layer(0.2, 0.2).feature("Outer wall", 0.5).obj("A").travel(5.0, 10.0).line(25.0, 10.0, 0.5).obj(null)
        .layer(0.4, 0.2).feature("Outer wall", 0.5).obj("A").travel(5.0, 10.0).line(25.0, 10.0, 0.5).obj(null).write(dir.resolve("named.gcode")).toString()
    val bare = SyntheticGcode(markers = false, names = false).layer(0.2, 0.2).travel(5.0, 10.0).line(25.0, 10.0, 0.5).write(dir.resolve("bare.gcode")).toString()
    val out = dir.resolve("out").toString()

    /** Runs a command line that must be refused; the refusal's words. */
    fun refused(vararg args: String): String {
        val (code, _, err) = render(*args, "-o", out)
        code shouldBe 1
        return err.joinToString("\n")
    }
    fun accepted(vararg args: String) { render(*args, "-o", out).first shouldBe 0 }

    val cases = listOf(
        listOf("--layer-step", "2") to listOf("--layer-step", "--layer-range"),
        listOf("--others", "faint") to listOf("--others", "--only-"),
        listOf("--around", "15,10") to listOf("--around", "--span"),
        listOf("--span", "10") to listOf("--span", "--around"),
        listOf("--around", "15,10", "--span", "5", "--bed-window", "0,0,30,20") to listOf("--around", "--bed-window"),
        listOf("--px-per-mm", "10", "--picture-size", "800x600") to listOf("--px-per-mm", "--picture-size"),
        listOf("--list-features", "--layer", "1") to listOf("--list-features", "--layer"),
        listOf("--sheet", "all.png", "--format", "svg") to listOf("--sheet", "--format"),
        listOf("--bed-window", "30,0,0,20") to listOf("--bed-window"),
        listOf("--only-part", "hull") to listOf("--only-part", "names no parts"),
        listOf("--only-feature", "Ironing") to listOf("--only-feature", "'Outer wall'"),
        listOf("--only-object", "B") to listOf("--only-object", "'A'"),
        listOf("--only-tool", "3") to listOf("--only-tool", "0"),
        listOf("--layer", "3") to listOf("--layer 3", "2 layers"),
        listOf("--layer-at-z", "5") to listOf("--layer-at-z", "0.4"),
    )
    for ((args, words) in cases) "refused: ${args.joinToString(" ")}" {
        val said = refused(named, *args.toTypedArray())
        for (w in words) said shouldContain w
    }

    "refused where the file does not state what is asked: feature colors, declared widths, the filament for plastic widths" {
        refused(bare, "--color-by", "feature") shouldContain "--color-by feature"
        refused(bare, "--line-width", "declared") shouldContain "declares no line widths"
        refused(bare, "--line-width", "plastic") shouldContain "diameter"
    }

    "control: each option accepted where it applies" {
        accepted(named, "--layer-range", "1..2", "--layer-step", "2")
        accepted(named, "--only-object", "A", "--others", "faint")
        accepted(named, "--around", "15,10", "--span", "5")
        accepted(named, "--bed-window", "0,0,30,20", "--px-per-mm", "10")
        accepted(named, "--picture-size", "800x600")
        render(named, "--list-features").first shouldBe 0
        accepted(named, "--sheet", "all.svg", "--format", "svg")
        accepted(named, "--only-feature", "Outer wall", "--only-tool", "0")
        accepted(named, "--layer", "2", "--layer-at-z", "0.3")
        accepted(bare, "--color-by", "tool", "--line-width", "center")
    }
})
