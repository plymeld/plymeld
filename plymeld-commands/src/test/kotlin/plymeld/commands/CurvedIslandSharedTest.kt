package plymeld.commands

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import java.nio.file.Files

/**
 * Curved islands with what lies round them:
 * - stage 6, an island shared with a taller part: the pin of [IslandPin] with a post through its cap up to 20 mm (the cap tops
 *   out at 15 mm), its cap's island locked. The island takes the post's column in, printed level up to its top; a flat layer
 *   lands on that top and the post goes on from it in flat layers: material at the post's center on every layer from the
 *   island's start to 20 mm, layer tops never more than the tool's thickest apart, no top surface in the post on the island's
 *   last layer (it is covered), no bridge or bottom surface in it on the flat layer over it (it rests on the island); the file
 *   passes the pre-print check.
 * - the flat layers under an island read its material over them: on the plain pin no top surface, bridge or solid infill in
 *   the column under the island's start (with the island's loops taken out before the flat layers were planned,
 *   the column got a bridge, solid infill, and a top surface there, hidden under the island).
 */
class CurvedIslandSharedTest : StringSpec({
    val session = SliceSession()
    /** The pin (with a post up to [postTop]) sliced with its cap's island locked at ([x], 15). */
    fun slice(postTop: Double?, x: Double): SliceSession.Written {
        val d = Files.createTempDirectory("plymeld-island-shared")
        Files.writeString(d.resolve("hw.toml"), IslandPin.hardware)
        TechniqueSamples.threeMf(d.resolve("pin.3mf"), "pin", IslandPin.parts(postTop))
        Files.writeString(d.resolve("isl.toml"), "[plan.object.pin]\nisland.cap.x = \"$x mm\"\nisland.cap.y = \"15 mm\"\n")
        val pr = Product(ConfigStack.load(listOf("hw.toml", "isl.toml"), emptyList(), d), listOf(d.resolve("pin.3mf")), null) { }
        return session.write(pr, d.resolve("pin.gcode"), { })
    }
    /** One layer of a file: its Z (as marked), whether it is a curved island's, and its extrusions (feature, x0, y0, x1, y1). */
    class L(val z: Double, val island: Boolean, val moves: List<Pair<String, DoubleArray>>)
    fun layers(text: String): List<L> {
        val out = ArrayList<L>(); var z = Double.NaN; var island = false; var moves = ArrayList<Pair<String, DoubleArray>>()
        var feature = ""; var x = Double.NaN; var y = Double.NaN; var started = false
        fun flush() { if (started) out += L(z, island, moves); moves = ArrayList(); island = false }
        for (l in text.lines()) {
            if (l.startsWith(";Z:") || l.startsWith("; Z_HEIGHT:")) { flush(); started = true; z = l.substringAfter(":").trim().toDouble(); continue }
            if (l.startsWith(plymeld.program.LayerStart.ISLAND_MARK)) { island = true; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(":").trim(); continue }
            if (!l.startsWith("G1 ") && !l.startsWith("G0 ")) continue
            val c = l.substringBefore(";")
            val nx = Regex(" X(-?[0-9.]+)").find(c)?.groupValues?.get(1)?.toDouble() ?: x
            val ny = Regex(" Y(-?[0-9.]+)").find(c)?.groupValues?.get(1)?.toDouble() ?: y
            val e = Regex(" E(-?[0-9.]+)").find(c)?.groupValues?.get(1)?.toDouble()
            if (e != null && e > 0 && !x.isNaN() && (nx != x || ny != y)) moves += feature to doubleArrayOf(x, y, nx, ny)
            x = nx; y = ny
        }
        flush()
        return out
    }
    /** Whether the segment comes within [r] mm of the pin's center (15, 15) (its center-line; the post's radius is 1.2 mm). */
    fun near(m: DoubleArray, r: Double): Boolean {
        val dx = m[2] - m[0]; val dy = m[3] - m[1]; val l2 = dx * dx + dy * dy
        val t = if (l2 == 0.0) 0.0 else (((15 - m[0]) * dx + (15 - m[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return Math.hypot(m[0] + t * dx - 15, m[1] + t * dy - 15) < r
    }

    "an island shared with a taller post: the post printed level in the island's layers, on in flat layers from its top, every layer of it there; the check passes" {
        val w = slice(20.0, 18.0)
        val ls = layers(w.text)
        val isl = ls.filter { it.island }
        isl.size shouldBeGreaterThan 5
        val start = isl.minOf { it.z }; val top = isl.maxOf { it.z }
        println("island layers ${isl.size}, z %.3f..%.3f; layers %d".format(java.util.Locale.ROOT, start, top, ls.size))
        // the cap's top is where the post comes out of it: the dome (radius 5 mm, 3 mm tall over 12 mm) at the post's radius 1.2 mm
        val rr = (5.0 * 5.0 + 3.0 * 3.0) / (2 * 3.0)
        val capTop = 12.0 + Math.sqrt(rr * rr - 1.2 * 1.2) - (rr - 3.0)
        withClue("the island's top %.3f against the cap's %.3f".format(java.util.Locale.ROOT, top, capTop)) { Math.abs(top - capTop) shouldBeLessThan 0.1 }
        // a flat layer lands on the island's top: the post goes on from there with no gap
        withClue("a flat layer at the island's top") { ls.any { !it.island && Math.abs(it.z - top) < 0.002 } shouldBe true }
        // the post stands free above the cap's top: every flat layer over the island up to the post's top lays its walls (a line
        // within 1.3 mm of its center; under the cap's top the post is inside the column, its infill), and those layers, from the
        // island's top, are never more than the tool's thickest (0.3 mm) apart
        val over = ls.filter { !it.island && it.z > top + 1e-6 && it.z < 20.0 - 1e-6 }
        over.size shouldBeGreaterThan 10
        val bare = over.filter { l -> l.moves.none { near(it.second, 1.3) } }
        withClue("flat layers over the island with nothing of the post: " + bare.joinToString { "%.3f".format(java.util.Locale.ROOT, it.z) }) { bare.shouldBeEmpty() }
        (listOf(top) + over.map { it.z }).sorted().zipWithNext().maxOf { (a, b) -> b - a } shouldBeLessThan 0.3 + 1e-6
        // the post is covered on the island's last layer (no top surface in it) and rests on it on the flat layer over it
        val last = isl.maxBy { it.z }
        withClue("top surface in the post on the island's last layer") { last.moves.none { it.first == "Top surface" && near(it.second, 0.9) } shouldBe true }
        val first = over.minBy { it.z }
        withClue("bridge or bottom surface in the post on the flat layer over the island (z ${first.z})") {
            first.moves.none { (it.first == "Bridge" || it.first == "Internal Bridge" || it.first == "Bottom surface") && near(it.second, 0.9) } shouldBe true
        }
        val problems = session.check(SliceSession.Written(w.pr, w.plan, w.setup, w.output, w.text, w.partTools, w.prediction, w.reused), { }).problems
        withClue(problems.joinToString("\n")) { problems.shouldBeEmpty() }
    }

    "the flat layers under the plain pin's island read its material over them: no top surface, bridge or solid infill in the column under its start" {
        val ls = layers(slice(null, 15.0).text)
        val start = ls.filter { it.island }.minOf { it.z }
        val under = ls.filter { !it.island && it.z > 8.0 && it.z < start - 1e-6 }
        under.size shouldBeGreaterThan 5
        val bad = under.flatMap { l -> l.moves.filter { (f, m) -> f in setOf("Top surface", "Bridge", "Internal Bridge", "Internal solid infill") && near(m, 3.5) }.map { l.z to it.first } }.distinct()
        withClue("under the island's start (%.3f mm): %s".format(java.util.Locale.ROOT, start, bad.joinToString())) { bad.shouldBeEmpty() }
    }
})
