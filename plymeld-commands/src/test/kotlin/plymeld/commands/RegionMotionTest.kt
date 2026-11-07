package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * A region of an object (plan.object.<key>.region.<key>) and the motion caps set on it: the lines in it print no faster and
 * accelerate no more than the caps, the rest of the object as before, and the object stays one (no lines added, the same
 * plastic). The hole block from the repo's test config (30 x 30 x 4 mm, x and y 120 to 150 in its file), arcs off; the region,
 * its left half (x 120 to 135).
 * - A box: every line in it at most 20 mm/s and 1000 mm/s²; lines outside faster. Control: no region, lines there faster too.
 * - The faces: walls only; the infill in the box keeps its speed. A height range: from 2 mm; the lines in the box below it keep theirs.
 * - Refused: a region outside the object, and a value the code does not read per region.
 */
class RegionMotionTest : StringSpec({
    val root = Path.of("..").toAbsolutePath().normalize()
    val cfs = Files.newDirectoryStream(root.resolve("test-models/config"), "0*.toml").use { it.map { p -> p.toString() }.sorted() }
    val d = Files.createTempDirectory("plymeld-region")
    /** The block sliced with [region] (TOML lines under [plan.object."holeblock.stl".region.left]), or the exit code and output when refused. */
    fun slice(region: String?): Pair<Int, String> {
        val cfg = d.resolve("region-${region.hashCode()}.toml")
        Files.writeString(cfg, if (region == null) "" else "[plan.object.\"holeblock.stl\".region.left]\n$region\n")
        val out = d.resolve("block-${region.hashCode()}.gcode")
        val err = ByteArrayOutputStream()
        val code = Commands.invoke(listOf("slice") + (cfs + cfg.toString()).flatMap { listOf("-cf", it) } +
            listOf("plan.pose=as-given", "plan.placement=center", "hardware.machine.example-corexy.firmware.arcs=false").flatMap { listOf("-cv", it) } +
            listOf("-m", root.resolve("test-models/holeblock.stl").toString(), "-o", out.toString()), { err.write((it + "\n").toByteArray()) })
        return code to (if (code == 0) Files.readString(out) else err.toString())
    }
    /** Each printing move: its feature, the X and Y of its middle, its Z, its feed rate (mm/min), its acceleration, its filament (mm). */
    data class Move(val feature: String, val midX: Double, val midY: Double, val z: Double, val f: Double, val accel: Double, val e: Double)
    fun moves(g: String): List<Move> {
        val out = ArrayList<Move>()
        var x = Double.NaN; var y = Double.NaN; var z = 0.0; var f = 0.0; var a = 0.0; var feature = ""
        for (l in g.lines()) {
            when {
                l.startsWith("; FEATURE: ") -> feature = l.removePrefix("; FEATURE: ")
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                l.startsWith("SET_VELOCITY_LIMIT") -> a = l.substringAfter("ACCEL=").substringBefore(' ').toDouble()
                l.startsWith("G2 ") || l.startsWith("G3 ") -> error("arcs are off: $l")
                l.startsWith("G1 ") -> {
                    val w = l.substringBefore(';').trim().split(' ').drop(1).filter { it.length > 1 }.associate { it[0] to it.substring(1).toDouble() }
                    w['F']?.let { f = it }
                    val nx = w['X'] ?: x; val ny = w['Y'] ?: y; w['Z']?.let { z = it }
                    val e = w['E']
                    if (e != null && e > 0 && !x.isNaN() && (nx != x || ny != y)) out += Move(feature, (x + nx) / 2, (y + ny) / 2, z, f, a, e)
                    x = nx; y = ny
                }
            }
        }
        return out
    }
    /**
     * The block's lines (within its outer walls' extent, as placed: the brim and the prime line are not the object's) well
     * inside the region (x within the block's left half less a line) and well outside it.
     */
    fun split(ms: List<Move>): Pair<List<Move>, List<Move>> {
        val walls = ms.filter { it.feature == "Outer wall" }
        val x0 = walls.minOf { it.midX }; val y0 = walls.minOf { it.midY } - 0.01; val y1 = walls.maxOf { it.midY } + 0.01
        val block = ms.filter { it.midY in y0..y1 && it.midX >= x0 - 0.01 }
        return block.filter { it.midX < x0 + 14.0 } to block.filter { it.midX > x0 + 16.0 }
    }

    val plain = moves(slice(null).second)
    val box = "min = \"120 120 0 mm\"\nmax = \"135 150 4 mm\"\nmechanism.motion.speed.max = \"20 mm/s\"\nmechanism.motion.acceleration.max = \"1000 mm/s2\""

    "a box: the lines in it at most 20 mm/s and 1000 mm/s², the rest faster; the same plastic; control: no region, faster there too" {
        val (code, g) = slice(box)
        code shouldBe 0
        val (inside, outside) = split(moves(g))
        (inside.size > 50) shouldBe true
        inside.all { it.f <= 1200.0 + 1e-6 } shouldBe true
        inside.all { it.accel <= 1000.0 + 1e-6 } shouldBe true
        outside.any { it.f > 1200.0 } shouldBe true
        outside.any { it.accel > 1000.0 } shouldBe true
        moves(g).sumOf { it.e } shouldBe (plain.sumOf { it.e } plusOrMinus 0.01)
        split(plain).first.any { it.f > 1200.0 } shouldBe true
        split(plain).first.any { it.accel > 1000.0 } shouldBe true
    }

    "the faces: walls only, the infill in the box at its own speed; a height range: below it at their own" {
        val walls = split(moves(slice("$box\nfacing = [\"side\"]").second)).first
        walls.filter { it.feature.endsWith("wall") }.all { it.f <= 1200.0 + 1e-6 } shouldBe true
        walls.filter { !it.feature.endsWith("wall") }.any { it.f > 1200.0 } shouldBe true
        val high = split(moves(slice("$box\nfrom = \"2 mm\"").second)).first
        high.filter { it.z >= 2.0 + 0.05 }.all { it.f <= 1200.0 + 1e-6 } shouldBe true
        high.filter { it.z < 2.0 - 0.05 }.any { it.f > 1200.0 } shouldBe true
    }

    "refused: a region outside the object, a value not read per region; control: the box is accepted" {
        slice("min = \"200 200 0 mm\"\nmax = \"210 210 4 mm\"\nmechanism.motion.speed.max = \"20 mm/s\"").let { (code, out) -> (code != 0) shouldBe true; out shouldContain "lies outside the object" }
        slice("$box\nlayer.infill.density = \"40%\"").let { (code, out) -> (code != 0) shouldBe true; out shouldContain "cannot be set for a region" }
        slice(box).first shouldBe 0
    }
})

