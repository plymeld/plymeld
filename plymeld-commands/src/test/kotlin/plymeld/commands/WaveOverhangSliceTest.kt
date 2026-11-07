package plymeld.commands

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale

/**
 * Wave overhangs end to end: a ledge (a base block with a shelf 8 mm over air) sliced with
 * `plan.faces.under.technique = "wave_overhang"` gets its over-air bottom laid as wave beads from the base outward, at the
 * technique's speed and flow, and the pre-print check passes the file; the control, the same ledge with the underside as
 * laid, is bridged as before and has no wave bead. The beads: about depth / spacing of them, none outside the ledge.
 */
class WaveOverhangSliceTest : StringSpec({
    /** An ASCII STL of boxes, each (x0, y0, z0, x1, y1, z1). */
    fun stl(vararg boxes: DoubleArray): String = buildString {
        append("solid ledge\n")
        for (b in boxes) {
            val v = listOf(doubleArrayOf(b[0], b[1], b[2]), doubleArrayOf(b[3], b[1], b[2]), doubleArrayOf(b[3], b[4], b[2]), doubleArrayOf(b[0], b[4], b[2]),
                doubleArrayOf(b[0], b[1], b[5]), doubleArrayOf(b[3], b[1], b[5]), doubleArrayOf(b[3], b[4], b[5]), doubleArrayOf(b[0], b[4], b[5]))
            val faces = listOf(listOf(0, 2, 1), listOf(0, 3, 2), listOf(4, 5, 6), listOf(4, 6, 7), listOf(0, 1, 5), listOf(0, 5, 4),
                listOf(1, 2, 6), listOf(1, 6, 5), listOf(2, 3, 7), listOf(2, 7, 6), listOf(3, 0, 4), listOf(3, 4, 7))
            for (f in faces) { append("facet normal 0 0 0\nouter loop\n"); for (i in f) append("vertex ${v[i][0]} ${v[i][1]} ${v[i][2]}\n"); append("endloop\nendfacet\n") }
        }
        append("endsolid ledge\n")
    }
    // the base 20 x 10 x 4; the shelf 20 wide, from y 8 (2 mm on the base) to y 18 (8 mm over air), 1.2 mm thick on the base's top
    val ledge = stl(doubleArrayOf(0.0, 0.0, 0.0, 20.0, 10.0, 4.0), doubleArrayOf(0.0, 8.0, 4.0, 20.0, 18.0, 5.2))
    val hardware = """
        [hardware.machine.m]
        model = "flashforge-c5"
        build.x = "250 mm"
        build.y = "250 mm"
        build.z = "250 mm"
        motion.travel.speed = "300 mm/s"
        motion.print.speed = "300 mm/s"
        motion.print.acceleration = "5000 mm/s2"
        motion.travel.acceleration = "= machine.motion.print.acceleration"
        firmware.flavor = "klipper"
        firmware.arcs = "false"
        firmware.exclude_object = "false"
        gcode.start = "M83\nT{initial_tool}\nM109 S{first_layer_nozzle_temperature} T{initial_tool}"
        gcode.end = "M104 S0"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [plan]
        pose = "as-given"
        placement = "center"
        technique.flat.heights = "one"
        technique.flat.layer.height = "0.2 mm"
        support.enabled = "false"
    """.trimIndent()
    fun dir(vararg files: Pair<String, String>): Path = Files.createTempDirectory("plymeld-wave").also { d -> for ((n, t) in files) Files.writeString(d.resolve(n), t) }

    /** Slices through the command (the pre-print check included: a problem fails the slice) and returns the g-code. */
    fun slice(d: Path, cfs: List<String>, name: String): String {
        val out = d.resolve(name)
        val lines = ArrayList<String>()
        val status = Commands.invoke(listOf("slice") + cfs.flatMap { listOf("-cf", d.resolve(it).toString()) } + listOf("-m", d.resolve("ledge.stl").toString(), "-o", out.toString()), lines::add)
        status shouldBe 0
        return Files.readString(out)
    }

    /** Each feature's extruding moves, with their F (mm/min) and E per mm. */
    class Move(val feature: String, val x: Double, val y: Double, val f: Double, val ePerMm: Double)
    fun moves(g: String): List<Move> {
        val out = ArrayList<Move>()
        var feature = ""; var x = 0.0; var y = 0.0; var f = 0.0
        for (line in g.lines()) {
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) { feature = line.substringAfter(':').trim(); continue }
            if (!line.startsWith("G1 ") && !line.startsWith("G0 ")) continue
            fun v(c: Char) = Regex(" $c(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDouble()
            val nx = v('X') ?: x; val ny = v('Y') ?: y; v('F')?.let { f = it }
            val e = v('E')
            if (e != null && e > 0 && (nx != x || ny != y)) out += Move(feature, nx, ny, f, e / Math.hypot(nx - x, ny - y))
            x = nx; y = ny
        }
        return out
    }

    // wave overhangs by default for an underside hanging off one side, never a bridge: unset = waves;
    // the control asks for as_laid
    "the ledge's underside over air is laid as wave beads at the technique's speed and flow, by default; the check passes; the control (as_laid) is bridged" {
        val d = dir("hw.toml" to hardware, "ledge.stl" to ledge, "laid.toml" to "[plan.faces]\nunder.technique = \"as_laid\"\n")
        val g = slice(d, listOf("hw.toml"), "wave.gcode")
        val ms = moves(g)
        val waves = ms.filter { it.feature == "Wave overhang" }
        waves.shouldNotBeEmpty()
        // the technique's speed (2 mm/s = 120 mm/min) and flow: 0.16 mm2 of filament per mm, in E of a 1.75 mm filament
        waves.all { it.f == 120.0 } shouldBe true
        val ePerMm = 0.16 / (Math.PI * 1.75 * 1.75 / 4)
        waves.all { Math.abs(it.ePerMm - ePerMm) < 0.02 * ePerMm } shouldBe true
        // about 8 mm / 0.35 mm of beads across the shelf: the distinct y of the beads' moves on a ledge along x
        val ys = waves.map { Math.round(it.y * 20) / 20.0 }.distinct().size
        println("wave overhang: %d bead moves, about %d bead rows, %.4f mm/mm".format(Locale.ROOT, waves.size, ys, waves[0].ePerMm))
        (ys in 18..28) shouldBe true
        // the beads span the shelf over air (8 mm, placed wherever the bed puts it): from the first bead just past the base to the last within half a bead of the far edge
        val span = waves.maxOf { it.y } - waves.minOf { it.y }
        (span in 6.0..7.9) shouldBe true

        // the over-air bottom is not bridged as well
        ms.filter { it.feature == "Bridge" }.shouldBeEmpty()
        // the control: the underside as laid is a bridge, with no wave bead
        val c = slice(d, listOf("hw.toml", "laid.toml"), "control.gcode")
        val cm = moves(c)
        cm.filter { it.feature == "Wave overhang" }.shouldBeEmpty()
        cm.filter { it.feature == "Bridge" }.shouldNotBeEmpty()
    }

    // wave overhang, support, or nothing for what hangs off one side; bridging stays for a span between two
    // printed supports. A slab across two posts (20 mm apart, every bridge line landing on a post at both ends) is bridged, no
    // waves; the ledge with support switched on is held by the support, no waves (not wave beads laid on the support's
    // interface); control: the same ledge without support is waved (the first test)
    "a span between two posts stays a bridge; a ledge with support under it gets support, not waves" {
        val span = stl(doubleArrayOf(0.0, 0.0, 0.0, 6.0, 10.0, 4.0), doubleArrayOf(20.0, 0.0, 0.0, 26.0, 10.0, 4.0), doubleArrayOf(0.0, 0.0, 4.0, 26.0, 10.0, 5.2))
        val ds = dir("hw.toml" to hardware, "ledge.stl" to span)
        val sm = moves(slice(ds, listOf("hw.toml"), "span.gcode"))
        sm.filter { it.feature == "Wave overhang" }.shouldBeEmpty()
        sm.filter { it.feature == "Bridge" }.shouldNotBeEmpty()
        val dl = dir("hw.toml" to hardware, "ledge.stl" to ledge, "sup.toml" to "[plan.support]\nenabled = \"true\"\n")
        val lm = moves(slice(dl, listOf("hw.toml", "sup.toml"), "supported.gcode"))
        lm.filter { it.feature == "Wave overhang" }.shouldBeEmpty()
        lm.filter { it.feature.startsWith("Support") }.shouldNotBeEmpty()
    }

    /** [g]'s extruding moves with their layer's Z, middle y and F (mm/s). */
    fun zMoves(g: String): List<Triple<Double, Double, Double>> {
        val zMoves = ArrayList<Triple<Double, Double, Double>>()
        var z = 0.0; var x = 0.0; var y = 0.0; var f = 0.0; var feature = ""
        for (line in g.lines()) {
            if (line.startsWith(";Z:")) { z = line.removePrefix(";Z:").trim().toDouble(); continue }
            if (line.startsWith("; Z_HEIGHT:")) { z = line.removePrefix("; Z_HEIGHT:").trim().toDouble(); continue }
            if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) { feature = line.substringAfter(':').trim(); continue }
            if (!line.startsWith("G1 ")) continue
            fun v(c: Char) = Regex(" $c(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDouble()
            v('F')?.let { f = it }
            val nx = v('X') ?: x; val ny = v('Y') ?: y
            val e = v('E')
            if (e != null && e > 0 && (nx != x || ny != y) && feature != "Prime line" && feature != "Brim") zMoves += Triple(z, (y + ny) / 2, Math.round(f / 60 * 100) / 100.0)
            x = nx; y = ny
        }
        return zMoves
    }

    /** [g]'s wave beads, and the speeds (mm/s) of the extruding moves at a layer's Z well inside the beads' span. */
    fun overBeads(g: String): Pair<List<Move>, (Double) -> Set<Double>> {
        val zm = zMoves(g)
        val beads = moves(g).filter { it.feature == "Wave overhang" }
        val yLo = beads.minOf { it.y } + 0.5; val yHi = beads.maxOf { it.y } - 0.5           // well inside the beads' span
        return beads to { zz: Double -> zm.filter { Math.abs(it.first - zz) < 1e-6 && it.second in yLo..yHi }.map { it.third }.toSet() }
    }

    // the bridge ramp over a wave layer (back to the usual speed over a few layers: the same ramp as an arched bridge's, to the
    // bridge speed, starting from the wave speed): the shelf's waves at
    // 4.2 at the beads' 2 mm/s, its next layers over the beads at the arched bridge's whole sequence, 4, 6, 8, 12, then 24 on its
    // top at 5.2; with a shorter ramp (4, then 10 mm/s, then doubling: 20, 30) the usual after; control: over the base, the usual
    "the layers over the wave beads ramp up: 4, 6, 8, 12, 24 mm/s over the beads; a shorter ramp, then the usual; over the base the usual throughout" {
        val d = dir("hw.toml" to hardware, "ledge.stl" to ledge, "wave.toml" to "[plan.faces]\nunder.technique = \"wave_overhang\"\n",
            "short.toml" to "[plan.technique.bridge]\nramp_speeds = \"10 mm/s\"\n")
        val g = slice(d, listOf("hw.toml", "wave.toml"), "ramp.gcode")
        val (beads, over) = overBeads(g)
        listOf(4.4, 4.6, 4.8, 5.0, 5.2).map { over(it) } shouldBe listOf(setOf(4.0), setOf(6.0), setOf(8.0), setOf(12.0), setOf(24.0))
        val (_, overShort) = overBeads(slice(d, listOf("hw.toml", "wave.toml", "short.toml"), "ramp_short.gcode"))
        listOf(4.4, 4.6, 4.8, 5.0).map { overShort(it) } shouldBe listOf(setOf(4.0), setOf(10.0), setOf(20.0), setOf(30.0))
        (overShort(5.2) intersect setOf(2.0, 4.0, 10.0, 20.0, 30.0)).isEmpty() shouldBe true
        // control: over the base (away from the beads), the layer above the waves keeps its usual speeds
        val baseY = beads.minOf { it.y } - 3.0
        (zMoves(g).filter { Math.abs(it.first - 4.4) < 1e-6 && it.second < baseY }.map { it.third }.toSet() intersect setOf(4.0)).isEmpty() shouldBe true
    }

    // the surface technique limited by height; the shelf's first layer over air is
    // the layer from 4.0 to 4.2 mm
    "limited by height: underside.from above the shelf leaves it bridged; from below, or to above it, waves it" {
        val d = dir("hw.toml" to hardware, "ledge.stl" to ledge,
            "above.toml" to "[plan.faces]\nunder.technique = \"wave_overhang\"\nunder.from = \"5 mm\"\n",
            "below.toml" to "[plan.faces]\nunder.technique = \"wave_overhang\"\nunder.from = \"3 mm\"\n",
            "upto.toml" to "[plan.faces]\nunder.technique = \"wave_overhang\"\nunder.to = \"4 mm\"\n",
            "past.toml" to "[plan.faces]\nunder.technique = \"wave_overhang\"\nunder.to = \"4.1 mm\"\n")
        // in the same JVM as the test above, so a cache that left the limit out of its key would hand back its waved layers
        fun features(cf: String) = moves(slice(d, listOf("hw.toml", cf), "$cf.gcode")).map { it.feature }.toSet()
        features("above.toml").let { (("Wave overhang" in it) to ("Bridge" in it)) shouldBe (false to true) }
        features("below.toml").let { (("Wave overhang" in it) to ("Bridge" in it)) shouldBe (true to false) }
        features("upto.toml").let { (("Wave overhang" in it) to ("Bridge" in it)) shouldBe (false to true) }
        features("past.toml").let { (("Wave overhang" in it) to ("Bridge" in it)) shouldBe (true to false) }
    }

    "the pre-print check refuses a waved layer that leaves part of its span unlaid, saying where; control: as sliced, and with one bead row taken out, it passes" {
        val d = dir("hw.toml" to hardware, "ledge.stl" to ledge)
        val g = slice(d, listOf("hw.toml"), "wave.gcode")
        val product = Product(plymeld.config.ConfigStack.load(listOf(d.resolve("hw.toml").toString()), emptyList(), d), listOf(d.resolve("ledge.stl")), null) { }
        val plan = product.decide()
        val zBase = plan.parts.minOf { it.bounds()[2] }
        val rows = moves(g).filter { it.feature == "Wave overhang" }.map { Math.round(it.y * 100) / 100.0 }.distinct().sorted()
        val mid = rows[rows.size / 2]
        /** [g] with the wave beads whose y is in [band] laid as travels (no extrusion). */
        fun without(band: ClosedFloatingPointRange<Double>): String {
            var feature = ""
            return g.lines().joinToString("\n") { line ->
                if (line.startsWith(";TYPE:") || line.startsWith("; FEATURE:")) feature = line.substringAfter(':').trim()
                val y = Regex(" Y(-?[0-9.]+)").find(line)?.groupValues?.get(1)?.toDouble()
                if (feature == "Wave overhang" && line.startsWith("G1 ") && y != null && Math.round(y * 100) / 100.0 in band) line.replace(Regex(" E[0-9.]+"), "") else line
            }
        }
        fun air(text: String) = plymeld.core.check.ModelCheck.check(text, plan.parts, zBase).filter { "over air" in it }
        air(g).shouldBeEmpty()
        air(without(mid..mid)).shouldBeEmpty()
        val pictures = d.resolve("gap.check")
        val gap = plymeld.core.check.ModelCheck.check(without(mid - 0.8..mid + 0.8), plan.parts, zBase, imagesDir = pictures).filter { "over air" in it }
        println(gap)
        gap.single() shouldContain "mm2 of the area over air laid by no line"
        // the layer's picture
        Files.exists(pictures.resolve("z" + gap.single().substringAfter("layer at z ").substringBefore(':').padStart(7, '0') + ".png")) shouldBe true
    }
})
