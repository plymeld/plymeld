package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path

/**
 * `plymeld calibrate` with the repo's sample config (test-models/config) and the bundled defaults: every melt plate
 * was refused once the writer's own prime line (a cell no plate part knew) and its lifts over printed strips (which
 * left the plate's "no lift" controls nothing to catch) arrived; the first-layer plate was refused for a control that cannot apply
 * when the end template never moves the head across (the samples' end: heaters off). Each plate is written only when it prints as
 * specified AND its own controls are caught (the command refuses otherwise), so a written plate is the test; the melt plate is
 * checked to carry its own prime line before each column, and to lay its base at the filament's own temperatures.
 */
class CalibrateSamplesTest : StringSpec({
    val samples = Path.of("..", "test-models", "config").toAbsolutePath().normalize()
    val d = Files.createTempDirectory("plymeld-calibrate-samples")
    fun calibrate(vararg args: String): String {
        val out = d.resolve("${args.joinToString("_").replace(Regex("[^A-Za-z0-9_-]"), "")}.gcode")
        // the kind is the subcommand; the config options belong to it (one subcommand per plate)
        CalibrateCommand().parse(listOf(args[0], "-cf", samples.resolve("0*.toml").toString(), "-cv", "hardware.machine.example-corexy.model=qidi-q2") + args.drop(1) + listOf("-o", out.toString()))
        return Files.readString(out)
    }
    "the melt plate on the samples is written, its controls caught, with its own prime line before each column" {
        val g = calibrate("melt", "--temps", "200-220")
        for (c in 1..3) g shouldContain "melt prime $c"
        g.lines().any { it.trim() == "M83" || it.startsWith("M83 ") } shouldBe true
    }
    "the melt plate's base prints at the filament's own temperatures; every column, the first too, waited for after it" {
        // the samples' pla-white: temperature.first_layer 210, temperature.print 205; columns 190 and 200, both below them
        val g = calibrate("melt", "--temps", "190-200")
        val temps = g.lines().filter { it.startsWith("M104 S") || it.startsWith("M109 S") }.map { it.substringBefore(" T") }.filter { it != "M104 S0" }
        val firstMove = g.lines().indexOfFirst { it.startsWith("G1 ") && it.contains(" E") && it.contains("X") }
        // the waits before the plate's first line: the first-layer temperature, never a column's (the control: 190 there)
        g.lines().take(firstMove).filter { it.startsWith("M109 S") }.map { it.substringBefore(" T") }.last() shouldBe "M109 S210"
        temps.takeLast(3) shouldBe listOf("M104 S205", "M109 S190", "M109 S200")
    }
    // the flow plate's g-code read on its own: per patch cell on the top layer, the temperature set while it printed (and
    // whether the last command setting it was a wait), its filament per mm, and the Y of its lines along X; per patch cell
    // below the top, the temperatures it printed at
    class Top(var temp: Int = 0, var waited: Boolean = false, var e: Double = 0.0, var len: Double = 0.0, val ys: MutableSet<Long> = sortedSetOf())
    fun flowTops(g: String): Pair<Map<String, Top>, Map<String, Set<Int>>> {
        var temp = 0; var waited = false; var cell: String? = null; var x = Double.NaN; var y = Double.NaN; var z = 0.0
        val layers = HashMap<String, MutableMap<Double, Top>>()
        for (l in g.lines()) {
            Regex("^(M104|M109) S(\\d+)").find(l)?.let { temp = it.groupValues[2].toInt(); waited = it.groupValues[1] == "M109" }
            if (l.startsWith("; CELL: ")) { cell = l.removePrefix("; CELL: "); continue }
            if (l == "; CELL END") { cell = null; continue }
            if (!l.startsWith("G1 ")) continue
            fun num(c: Char) = Regex(" $c([-0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
            num('Z')?.let { z = it }
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E') ?: 0.0
            val c = cell
            // real moves only: an un-retract (E with no XY) is not a patch's line
            if (c != null && c.matches(Regex("flow \\d+\\.\\d+")) && e > 0 && !x.isNaN() && (nx != x || ny != y)) {
                val t = layers.getOrPut(c) { HashMap() }.getOrPut(z) { Top(temp, waited) }
                t.e += e; t.len += Math.hypot(nx - x, ny - y)
                if (Math.abs(ny - y) < 1e-9 && Math.abs(nx - x) > 5) t.ys += Math.round(ny * 1000)
            }
            x = nx; y = ny
        }
        return layers.mapValues { (_, byZ) -> byZ.getValue(byZ.keys.max()) } to layers.mapValues { (_, byZ) -> byZ.filterKeys { it < byZ.keys.max() }.values.map { it.temp }.toSet() }
    }
    // what the flow plate must show, from the options given and the samples' pla-white (temperature.print 205), worked out
    // here: each column's top at its own temperature after a wait, the layers under it at 205; each patch's filament per mm
    // in proportion to its ratio; the top's lines the bead spacing apart (0.44 mm lines, 0.2 mm layers)
    fun flowProblems(g: String, temps: List<Int>, ratios: List<Double>): List<String> {
        val out = ArrayList<String>()
        val (tops, below) = flowTops(g)
        val sp = 0.44 - 0.2 * (1 - Math.PI / 4)
        for ((c, tc) in temps.withIndex()) for ((r, ratio) in ratios.withIndex()) {
            val name = "flow ${c + 1}.${r + 1}"
            val t = tops[name] ?: run { out += "$name: no top"; null } ?: continue
            if (t.temp != tc || !t.waited) out += "$name: top at ${t.temp} C (${if (t.waited) "waited" else "not waited"}), not $tc after a wait"
            if (below.getValue(name) != setOf(205)) out += "$name: layers under the top at ${below.getValue(name)}, not 205 C"
            val ref = tops.getValue("flow ${c + 1}.1")
            val want = ratio / ratios[0]; val got = (t.e / t.len) / (ref.e / ref.len)
            if (Math.abs(got - want) > want * 0.003) out += "$name: filament per mm ${"%.4f".format(got)} x patch 1's, not ${"%.4f".format(want)}"
            val gaps = t.ys.toList().zipWithNext { a, b -> (b - a) / 1000.0 }
            if (gaps.isEmpty() || gaps.any { Math.abs(it - sp) > 0.012 }) out += "$name: top lines ${gaps.distinct()} mm apart, not $sp"
        }
        return out
    }
    "the flow plate: each column's top at its temperature after a wait, each patch at its ratio, the bead spacing; controls caught" {
        val temps = listOf(200, 210, 220); val ratios = listOf(0.9, 1.0, 1.1)
        val g = calibrate("flow", "--temps", "200-220", "--flow-ratios", "0.9,1,1.1")
        flowProblems(g, temps, ratios) shouldBe emptyList()
        for (c in 1..3) g shouldContain "flow prime $c"
        // controls: column 2's wait taken out (its top then at column 1's temperature); one top line of patch 1.3 given 40 % more
        // (one line of about thirty: the patch's filament per mm about 1 % off, past the check's 0.3 %)
        flowProblems(g.lines().filterNot { it.startsWith("M109 S210") }.joinToString("\n"), temps, ratios).any { it.startsWith("flow 2.") } shouldBe true
        val ls = g.lines().toMutableList()
        val at = ls.indices.last { ls[it] == "; CELL: flow 1.3" }
        val k = (at until ls.size).first { Regex("G1 X[-0-9.]+ Y[-0-9.]+ E[0-9.]+.*").matches(ls[it]) }
        val e = ls[k].substringAfter(" E").substringBefore(' ')
        ls[k] = ls[k].replace(" E$e", " E" + String.format(java.util.Locale.ROOT, "%.5f", e.toDouble() * 1.4))
        flowProblems(ls.joinToString("\n"), temps, ratios).any { it.startsWith("flow 1.3: filament per mm") } shouldBe true
    }
    "the first-layer plate on the samples is written, its controls caught (no end-lift control: the end never moves across)" {
        calibrate("first-layer") shouldContain "; CELL:"
    }
})
