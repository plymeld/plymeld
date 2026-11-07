package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path

/**
 * Coasting (hardware.filament.<key>.retract.coast.distance) and the restore spread over a path's start
 * (retract.return.distance, within the tool's extrude.section.max). The hole block from the repo's test config, arcs off
 * so every move is a straight line.
 * - Coasting: every retraction after a path is preceded by that path's last 0.5 mm laid with no filament pushed. Control: unset,
 *   no retraction is.
 * - The spread restore: less is pushed back standing still, no move extrudes a cross-section past the limit, and the
 *   filament pushed in all is the same as with it unset (the restore moved, none lost). Control: unset, the standing
 *   restores are the whole retraction.
 */
class CoastAndReturnTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val filamentArea = Math.PI * 1.75 * 1.75 / 4
    fun slice(vararg cv: String): List<String> {
        val out = Files.createTempDirectory("plymeld-coast").resolve("block.gcode")
        Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", it) } +
            (listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false") + cv).flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), { }) shouldBe 0
        return Files.readAllLines(out)
    }
    /** A G1 line's words (X, Y, Z, E, F) by letter; null for any other line. */
    fun words(l: String): Map<Char, Double>? {
        if (l.startsWith("G2 ") || l.startsWith("G3 ")) error("arcs are off: $l")
        if (!l.startsWith("G1 ")) return null
        return l.substringBefore(';').trim().split(' ').drop(1).filter { it.length > 1 }.associate { it[0] to it.substring(1).toDouble() }
    }
    /** Per retraction after a path, the XY length (mm) moved with no filament pushed right before it. */
    fun coastsBeforeRetractions(g: List<String>): List<Double> {
        val out = ArrayList<Double>()
        var x = Double.NaN; var y = Double.NaN; var dry = 0.0; var printed = false
        for (l in g) {
            val w = words(l) ?: continue
            val nx = w['X'] ?: x; val ny = w['Y'] ?: y; val e = w['E']
            val xy = if (x.isNaN() || nx.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
            when {
                e != null && e < 0 -> { if (printed) out += dry; printed = false; dry = 0.0 }
                e != null && e > 0 && xy > 0 -> { printed = true; dry = 0.0 }
                e == null && xy > 0 -> dry += xy
            }
            x = nx; y = ny
        }
        return out
    }
    /** The filament pushed back standing still (mm), the most cross-section any move extrudes (mm²), and the net filament pushed (mm). */
    fun restores(g: List<String>): Triple<Double, Double, Double> {
        var x = Double.NaN; var y = Double.NaN; var still = 0.0; var maxA = 0.0; var net = 0.0
        for (l in g) {
            val w = words(l) ?: continue
            val nx = w['X'] ?: x; val ny = w['Y'] ?: y; val e = w['E']
            val xy = if (x.isNaN() || nx.isNaN()) 0.0 else Math.hypot(nx - x, ny - y)
            if (e != null) {
                net += e
                if (e > 0 && 'X' !in w && 'Y' !in w && 'Z' !in w) still += e
                if (e > 0 && xy > 0.05) maxA = Math.max(maxA, e * filamentArea / xy)
            }
            x = nx; y = ny
        }
        return Triple(still, maxA, net)
    }

    "coasting: each retraction after a path follows its last 0.5 mm laid dry; control: unset, none" {
        val plain = coastsBeforeRetractions(slice())
        (plain.isNotEmpty()) shouldBe true
        plain.count { it > 0.01 } shouldBe 0
        val coasted = coastsBeforeRetractions(slice("hardware.filament.pla-white.retract.coast.distance=0.5 mm"))
        coasted.size shouldBe plain.size
        // every one but a path no longer than the distance (laid whole)
        (coasted.count { Math.abs(it - 0.5) < 0.002 } >= coasted.size * 9 / 10) shouldBe true
        coasted.all { it < 0.5 + 0.002 } shouldBe true
    }

    "the restore spread over the path's start: less standing still, within the limit, none lost; control: unset, all standing still" {
        val plain = restores(slice())
        val limit = 0.64
        val spread = restores(slice("hardware.filament.pla-white.retract.return.distance=3 mm", "hardware.tool.t1.extrude.section.max=$limit mm2"))
        println("standing restores: %.3f mm unset, %.3f mm spread; most cross-section %.3f / %.3f mm2; net %.3f / %.3f mm".format(
            java.util.Locale.ROOT, plain.first, spread.first, plain.second, spread.second, plain.third, spread.third))
        (plain.first > 1.0) shouldBe true
        (spread.first < plain.first / 2) shouldBe true
        (spread.second <= limit + 0.01) shouldBe true
        spread.third shouldBe (plain.third plusOrMinus 0.05)
        // the distance alone, the limit not known: all standing still, as unset
        restores(slice("hardware.filament.pla-white.retract.return.distance=3 mm")).first shouldBe (plain.first plusOrMinus 1e-6)
    }
})
