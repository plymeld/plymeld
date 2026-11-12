package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import plymeld.config.ConfigStack
import plymeld.core.check.Expectations
import plymeld.core.check.GcodeCheck
import plymeld.core.check.ModelCheck
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path

/**
 * Z anti-aliasing of sloped tops (plan.faces.sloped_top.technique = "z_antialiasing", ZAntiAlias), from the config to the g-code, on wedges whose top
 * rises along X at a known slope: z = 1 + x tan(slope) over 20 mm (the references here are that formula, never the slicer's
 * own reading of the mesh). 0.2 mm layers, the tool's thinnest 0.08 mm (bore / 5), the slicing resolution 0.0125 mm (bore / 32).
 *
 *  - 10 deg (plymeld's choice, within the 10 to 20 deg asked for): every line printed below its layer lies on the wedge's
 *    top, within the resolution and the file's rounding, never under the layer's bottom plus 0.08 mm; control: with ZAA off
 *    every line is at its layer's height;
 *  - the layer reaches out over the slope: its outer wall on the slope stands where the top is 0.08 mm over the layer's
 *    bottom (half a line further in), not where it crosses the layer's middle; control: ZAA off;
 *  - a lowered line's plastic per mm is the bead of its own thickness, t (w - t (1 - pi / 4)), against a flat line's of the
 *    whole layer (the ratio, by hand); control: those lines are thinner than the layer by more than 15 %, so the full layer's
 *    plastic would fail it;
 *  - 60 deg: nothing is lowered. The slope's band within one layer above its thinnest part is (0.2 - 0.08) / tan 60 = 0.07 mm
 *    wide; an outer wall's center stands 0.22 mm in from the edge, where the top is already tan 60 x 0.22 = 0.38 mm higher;
 *  - the checks: the file reads back clean with the ZAA rule; controls: read without it, a lowered line moved 0.03 mm off
 *    the top, a line lowered where the model goes on above the layer, a lowered line moved 1 mm downhill off the layer;
 *  - the stage keys: turning ZAA on re-runs the slice (it reads the setting) and matches a slice from scratch.
 */
class ZAntiAliasTest : StringSpec({
    val tool = """
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        [plan.technique.flat]
        layer.height = "0.2 mm"
        # no scarf seams: their rule lets an outer wall line sit anywhere down to the ramp's start, which would hide the
        # controls below (with scarf seams on, an outer wall line in that band is the scarf rule's, as before ZAA)
        [plan]
        seam = "corners"
        technique.scarf_seam.enabled = "false"
        [plan.object."wedge10.stl"]
        pose.label = "as given"
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "flat"
        band.1.heights = "one"
        band.1.layer.height = "0.2 mm"
        [plan.object."wedge3.stl"]
        pose.label = "as given"
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "flat"
        band.1.heights = "one"
        band.1.layer.height = "0.2 mm"
        [plan.object."wedge60.stl"]
        pose.label = "as given"
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "flat"
        band.1.heights = "one"
        band.1.layer.height = "0.2 mm"
    """.trimIndent()
    val h0 = 1.0
    /**
     * A wedge 10 mm across (Y), its top rising from [h0] along X at [deg] over [len] mm, then flat for [flat] mm (a top with no
     * knife edge, whose last layers are slivers no line fits): its profile in X-Z extruded along Y, every face outward.
     */
    fun wedge(deg: Double, len: Double, flat: Double = 0.0): String {
        val h1 = h0 + len * Math.tan(Math.toRadians(deg))
        val prof = listOf(0.0 to 0.0, len + flat to 0.0, len + flat to h1) + (if (flat > 0) listOf(len to h1) else emptyList()) + listOf(0.0 to h0)
        val sb = StringBuilder("solid wedge\n")
        fun tri(a: Triple<Double, Double, Double>, b: Triple<Double, Double, Double>, c: Triple<Double, Double, Double>) {
            sb.append("facet normal 0 0 0\nouter loop\n")
            for (v in listOf(a, b, c)) sb.append("vertex ${v.first} ${v.second} ${v.third}\n")
            sb.append("endloop\nendfacet\n")
        }
        fun at(i: Int, y: Double) = Triple(prof[i].first, y, prof[i].second)
        val n = prof.size
        // the profile runs counter-clockwise seen from -Y: the front cap (y 0) as a fan faces -Y, the back (y 10) reversed
        for (i in 1 until n - 1) { tri(at(0, 0.0), at(i, 0.0), at(i + 1, 0.0)); tri(at(0, 10.0), at(i + 1, 10.0), at(i, 10.0)) }
        for (i in 0 until n) { val j = (i + 1) % n; tri(at(i, 0.0), at(j, 10.0), at(j, 0.0)); tri(at(i, 0.0), at(i, 10.0), at(j, 10.0)) }
        return sb.append("endsolid wedge\n").toString()
    }
    val d: Path = Files.createTempDirectory("plymeld-zaa").also { d ->
        Files.writeString(d.resolve("hw.toml"), tool)
        Files.writeString(d.resolve("wedge10.stl"), wedge(10.0, 20.0)); Files.writeString(d.resolve("wedge60.stl"), wedge(60.0, 4.0, 4.0))
        Files.writeString(d.resolve("wedge3.stl"), wedge(3.0, 20.0))
    }
    val on = "plan.faces.sloped_top.technique=z_antialiasing"
    fun slice(model: String, out: String, vararg cv: String): String {
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString()) + cv.flatMap { listOf("-cv", it) } +
            listOf("-m", d.resolve(model).toString(), "-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }
    fun product(model: String, vararg cv: String) = Product(ConfigStack.load(listOf("hw.toml"), cv.toList(), d), listOf(d.resolve(model)), null) { }

    /** A printing move of the file: its layer's height and thickness, its ends, its plastic, feature and width. */
    class Move(val layerZ: Double, val layerH: Double, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double,
               val e: Double, val feature: String, val width: Double, val line: Int) {
        val len get() = Math.hypot(x1 - x0, y1 - y0)
        val lowered get() = minOf(z0, z1) < layerZ - 1e-6
    }
    fun moves(g: String): List<Move> {
        val out = ArrayList<Move>()
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var lz = Double.NaN; var lh = 0.0; var feat = ""; var w = 0.0
        for ((i, raw) in g.lines().withIndex()) {
            val l = raw.trim()
            when {
                l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").toDouble()
                l.startsWith(";HEIGHT:") -> lh = l.removePrefix(";HEIGHT:").toDouble()
                l.startsWith(";TYPE:") -> feat = l.removePrefix(";TYPE:")
                l.startsWith(";WIDTH:") -> w = l.removePrefix(";WIDTH:").toDouble()
            }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val ws = c.split(Regex("\\s+"))
            fun num(ch: Char) = ws.drop(1).firstOrNull { it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val nz = num('Z') ?: z; val e = num('E') ?: 0.0
            if (!lz.isNaN() && e > 0 && !x.isNaN() && (nx != x || ny != y)) out += Move(lz, lh, x, y, z, nx, ny, nz, e, feat, w, i + 1)
            x = nx; y = ny; z = nz
        }
        return out
    }

    val g10 by lazy { slice("wedge10.stl", "w10.gcode", on) }
    val g10off by lazy { slice("wedge10.stl", "w10off.gcode") }
    val p10 by lazy { product("wedge10.stl", on) }
    val plan10 by lazy { p10.decide() }
    // where the wedge stands on the bed (plymeld places it): its low end, its bottom
    val xmin by lazy { plan10.parts.minOf { it.bounds()[0] } }
    val zBase by lazy { plan10.parts.minOf { it.bounds()[2] } }
    val tan10 = Math.tan(Math.toRadians(10.0))
    /** The wedge's top over x (g-code Z), by its formula. */
    fun top10(x: Double) = h0 + (x - xmin) * tan10 - zBase
    val min = 0.08; val res = 0.0125; val round = 0.0015

    "10 deg: every line below its layer lies on the wedge's top, never under the layer's bottom plus the thinnest layer" {
        val ms = moves(g10)
        val low = ms.filter { it.lowered }
        // presence: the slope's walls are lowered on the layers it crosses
        low.sumOf { it.len } shouldBeGreaterThan 100.0
        low.map { it.feature }.toSet() shouldContain "Outer wall"
        for (m in low) for (t in listOf(0.0, 0.5, 1.0)) {
            val x = m.x0 + (m.x1 - m.x0) * t; val z = m.z0 + (m.z1 - m.z0) * t
            val want = minOf(m.layerZ, maxOf(top10(x), m.layerZ - m.layerH + min))
            Math.abs(z - want) shouldBeLessThan res + round
        }
        for (m in ms) minOf(m.z0, m.z1) shouldBeGreaterThan m.layerZ - m.layerH + min - round
        println("ZAA 10 deg: ${low.size} lowered moves, ${"%.1f".format(low.sumOf { it.len })} mm (${low.groupBy { it.feature }.mapValues { (_, v) -> "%.1f".format(v.sumOf { it.len }) }})")
    }
    "3 deg: the top surface's own lines are lowered onto the wedge's top too (the band within a layer, 0.12 / tan 3 = 2.3 mm, holds more than the walls)" {
        val g = slice("wedge3.stl", "w3.gcode", on)
        val p = product("wedge3.stl", on).decide().parts
        val x0 = p.minOf { it.bounds()[0] }; val z0 = p.minOf { it.bounds()[2] }
        val tan3 = Math.tan(Math.toRadians(3.0))
        val low = moves(g).filter { it.lowered }
        low.filter { it.feature == "Top surface" }.sumOf { it.len } shouldBeGreaterThan 50.0
        for (m in low) for (t in listOf(0.0, 0.5, 1.0)) {
            val x = m.x0 + (m.x1 - m.x0) * t; val z = m.z0 + (m.z1 - m.z0) * t
            val want = minOf(m.layerZ, maxOf(h0 + (x - x0) * tan3 - z0, m.layerZ - m.layerH + min))
            Math.abs(z - want) shouldBeLessThan res + round
        }
        // control: with ZAA off, the same top surface lines are flat at their layers' heights
        moves(slice("wedge3.stl", "w3off.gcode")).filter { it.feature == "Top surface" && it.lowered }.shouldBeEmpty()
        println("ZAA 3 deg: ${low.size} lowered moves (${low.groupBy { it.feature }.mapValues { (_, v) -> "%.1f".format(v.sumOf { it.len }) }})")
    }
    "control: with ZAA off every line of the 10 deg wedge is at its layer's height" {
        moves(g10off).filter { it.lowered }.shouldBeEmpty()
    }
    "the layer reaches out over the slope: its outer wall where the top is the thinnest layer over its bottom (control: ZAA off)" {
        // the wall on the slope runs along Y, its center half a line (0.22 mm) in from the outline
        fun lowWallX(g: String, layerZ: Double) = moves(g).filter { Math.abs(it.layerZ - layerZ) < 1e-6 && it.feature == "Outer wall" }.minOf { minOf(it.x0, it.x1) }
        // three layers well up the slope (their tops from the file: the first layer's height is the config's)
        val tops = moves(g10off).map { it.layerZ }.distinct().sorted()
        for (k in listOf(8, 12, 16)) {
            val layerZ = tops[k]
            val bottom = layerZ - 0.2
            val onX = lowWallX(g10, layerZ); val offX = lowWallX(g10off, layerZ)
            onX shouldBe ((bottom + min + zBase - h0) / tan10 + xmin + 0.22 plusOrMinus 0.02)
            offX shouldBe ((bottom + 0.1 + zBase - h0) / tan10 + xmin + 0.22 plusOrMinus 0.02)
            (offX - onX) shouldBe ((0.1 - min) / tan10 plusOrMinus 0.02)
        }
    }
    "a lowered line's plastic per mm is the bead of its own thickness (control: the full layer's would be 15 % or more off)" {
        val ms = moves(g10)
        fun area(t: Double, w: Double) = t * (w - t * (1 - Math.PI / 4))
        var n = 0
        for (m in ms.filter { it.lowered && it.len >= 0.3 && it.e >= 0.002 && it.feature == "Outer wall" }) {
            val flat = ms.filter { !it.lowered && it.layerZ == m.layerZ && it.feature == m.feature && it.width == m.width && it.len >= 2.0 }.maxByOrNull { it.len } ?: continue
            val t = ((m.z0 + m.z1) / 2) - (m.layerZ - m.layerH)
            if (t > 0.85 * m.layerH) continue
            val want = area(t, m.width) / area(m.layerH, m.width)
            (m.e / m.len) / (flat.e / flat.len) shouldBe (want plusOrMinus want * 0.015)
            Math.abs(want - 1) shouldBeGreaterThan 0.15
            n++
        }
        n shouldBeGreaterThan 10
    }
    "60 deg: nothing is lowered (the band a line could be lowered in is narrower than a wall's inset)" {
        moves(slice("wedge60.stl", "w60.gcode", on)).filter { it.lowered }.shouldBeEmpty()
    }

    // the checks, on the 10 deg wedge's file, with the rule the slice command makes (from the model and the config)
    val setup by lazy { p10.result.setup }
    val rule by lazy { Expectations.forZaa(setup, listOf(0 to plan10.parts), zBase, false)!! }
    fun gProblems(g: String, withRule: Boolean = true): List<String> {
        val sf = ModelCheck.Surface(plan10.parts, zBase, g, zaaMinMm = rule.minMm)
        return GcodeCheck.check(g, Expectations.forSetup(setup, sf::at, zaa = if (withRule) rule else null))
    }
    fun mProblems(g: String) = ModelCheck.check(g, plan10.parts, zBase, zaa = rule)
    /** [g] with line [n] (1-based) replaced by [f] of it. */
    fun edit(g: String, n: Int, f: (String) -> String) = g.lines().toMutableList().also { it[n - 1] = f(it[n - 1]) }.joinToString("\n")
    fun setZ(line: String, z: Double) = line.replace(Regex(" Z[0-9.]+"), "").replace(" E", " Z${"%.3f".format(java.util.Locale.ROOT, z)} E")

    "the checks read the ZAA file back clean" {
        gProblems(g10).shouldBeEmpty()
        mProblems(g10).shouldBeEmpty()
    }
    "control: read without the ZAA rule, its lowered lines are refused" {
        gProblems(g10, withRule = false).any { "printing at Z" in it } shouldBe true
    }
    "control: a lowered line moved 0.03 mm below the top is refused" {
        val m = moves(g10).first { it.lowered && it.z1 < it.layerZ - 0.05 && it.len > 0.2 }
        gProblems(edit(g10, m.line) { setZ(it, m.z1 - 0.03) }).any { "off the model's top" in it } shouldBe true
    }
    "control: a line lowered where the model goes on above the layer is refused" {
        // an inner wall at the wedge's high end, 2 mm in, where the top is several layers higher
        val m = moves(g10).first { !it.lowered && it.feature == "Inner wall" && it.layerZ > 2.0 && minOf(it.x0, it.x1) > xmin + 15 && it.len > 1.0 }
        gProblems(edit(g10, m.line) { setZ(it, m.layerZ - 0.05) }).any { "off the model's top" in it } shouldBe true
    }
    "control: a lowered line moved 1 mm downhill, off its layer, is refused by the model check" {
        val m = moves(g10).first { it.lowered && it.feature == "Outer wall" && Math.abs(it.x1 - it.x0) < 1e-6 && it.len > 3.0 }
        val shifted = edit(g10, m.line) { it.replace(Regex(" X([0-9.]+)")) { r -> " X${"%.3f".format(java.util.Locale.ROOT, r.groupValues[1].toDouble() - 1.0)}" } }
        mProblems(shifted).any { "outside the model" in it } shouldBe true
    }

    "turning ZAA on re-runs the slice (it reads the setting) and matches a slice from scratch" {
        val off = product("wedge10.stl"); val pl = off.decide()
        val inc = plymeld.core.IncrementalSlicer(pl.parts, "test", "wedge10.stl", technique = pl.layout.techniques.single())
        val first = StringWriter().also { inc.slice(off.result.setup, it) }.toString()
        val onSetup = p10.result.setup
        val again = StringWriter()
        // no "retraction" stage: the plan decided the in-island retraction and wrote it into the settings, so the slice takes the lock
        inc.slice(onSetup, again) shouldBe listOf("slice", "walls", "regions", "paths", "gcode")
        val fresh = StringWriter().also { plymeld.core.IncrementalSlicer(pl.parts, "test", "wedge10.stl", technique = pl.layout.techniques.single()).slice(onSetup, it) }.toString()
        (again.toString() == fresh) shouldBe true
        (again.toString() == first) shouldBe false      // control: the setting changed the print
        moves(again.toString()).count { it.lowered } shouldBeGreaterThan 10
    }
})
