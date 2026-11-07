package plymeld.commands

import com.github.ajalt.clikt.core.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Lifted bridges (bridges that change height, each printed in order on its own):
 * an object of two parts, the supports on the bed and a strip resting on them, sliced with band.1.technique = "bridge". A ramp:
 * pillars 6 and 12 mm high, 40 mm apart, a 5 mm wide, 1.2 mm thick strip rising straight from one to the other. Worked out
 * on the file's own layer grid (its first layer, then 0.2 mm): each pillar prints to the top of the last layer whose middle
 * is under its height, and the strip's first layer is laid one layer above them, after both (on a 0.28 mm first layer:
 * pillars to 6.08 and 12.08, the strip's first layer 6.28 to 12.28). Controls: the same ramp with flat layers prints over
 * the gap before the tall pillar is done;
 * a flat bridge comes out the same with and without the technique; a strip resting on nothing is refused.
 */
class LiftedBridgeTest : StringSpec({
    // a Creator 5 description; the head values are the test's own
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
        [hardware.machine.m.loadout]
        plate = "pei"
        tool.t1.filament = "pla-generic"
        [hardware.tool.t1]
        machine = "m"
        index = "1"
        bore = "0.4 mm"
        layer.height.min = "= tool.bore / 5"
        layer.height.max = "= tool.bore * 0.75"
        head.nozzle.angle = "45 deg"
        head.nozzle.height = "4 mm"
        head.nozzle.x = "70 mm"
        head.nozzle.y = "40 mm"
        head.size.x = "90 mm"
        head.size.y = "75 mm"
        [plan.technique.flat]
        layer.height = "0.2 mm"
    """.trimIndent()

    /** A closed box (outward triangles, 9 floats each). */
    fun box(x0: Double, y0: Double, z0: Double, x1: Double, y1: Double, z1: Double): List<DoubleArray> {
        val v = arrayOf(doubleArrayOf(x0, y0, z0), doubleArrayOf(x1, y0, z0), doubleArrayOf(x1, y1, z0), doubleArrayOf(x0, y1, z0),
            doubleArrayOf(x0, y0, z1), doubleArrayOf(x1, y0, z1), doubleArrayOf(x1, y1, z1), doubleArrayOf(x0, y1, z1))
        val f = intArrayOf(0, 2, 1, 0, 3, 2, 4, 5, 6, 4, 6, 7, 0, 1, 5, 0, 5, 4, 1, 2, 6, 1, 6, 5, 2, 3, 7, 2, 7, 6, 3, 0, 4, 3, 4, 7)
        return (0 until 12).map { t -> DoubleArray(9) { v[f[3 * t + it / 3]][it % 3] } }
    }
    /** A strip along X whose underside passes through [pts] (x, z), [t] thick, 5 mm wide about y = 0 (outward triangles). */
    fun strip(pts: List<Pair<Double, Double>>, t: Double = 1.2, w: Double = 5.0): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        fun v(i: Int, top: Boolean, s: Int) = doubleArrayOf(pts[i].first, s * w / 2, pts[i].second + if (top) t else 0.0)
        fun tri(a: DoubleArray, b: DoubleArray, c: DoubleArray) { out += a + b + c }
        for (i in 0 until pts.size - 1) {
            tri(v(i, false, -1), v(i, false, 1), v(i + 1, false, 1)); tri(v(i, false, -1), v(i + 1, false, 1), v(i + 1, false, -1))   // under, down
            tri(v(i, true, -1), v(i + 1, true, -1), v(i + 1, true, 1)); tri(v(i, true, -1), v(i + 1, true, 1), v(i, true, 1))         // top, up
            tri(v(i, false, -1), v(i + 1, false, -1), v(i + 1, true, -1)); tri(v(i, false, -1), v(i + 1, true, -1), v(i, true, -1))   // side -y
            tri(v(i, false, 1), v(i + 1, true, 1), v(i + 1, false, 1)); tri(v(i, false, 1), v(i, true, 1), v(i + 1, true, 1))         // side +y
        }
        val n = pts.size - 1
        tri(v(0, false, -1), v(0, true, -1), v(0, true, 1)); tri(v(0, false, -1), v(0, true, 1), v(0, false, 1))                     // end -x
        tri(v(n, false, -1), v(n, true, 1), v(n, true, -1)); tri(v(n, false, -1), v(n, false, 1), v(n, true, 1))                     // end +x
        return out
    }
    /** A 3MF of one object [name] made of the named parts. */
    fun threeMf(path: Path, name: String, parts: List<Pair<String, List<DoubleArray>>>) {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<model unit=\"millimeter\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\"><resources>")
        for ((k, p) in parts.withIndex()) {
            sb.append("<object id=\"${k + 1}\" type=\"model\" name=\"${name}_${p.first}\"><mesh><vertices>")
            for (t in p.second) for (c in 0..2) sb.append("<vertex x=\"${t[3 * c]}\" y=\"${t[3 * c + 1]}\" z=\"${t[3 * c + 2]}\"/>")
            sb.append("</vertices><triangles>")
            for (i in p.second.indices) sb.append("<triangle v1=\"${3 * i}\" v2=\"${3 * i + 1}\" v3=\"${3 * i + 2}\"/>")
            sb.append("</triangles></mesh></object>")
        }
        val g = parts.size + 1
        sb.append("<object id=\"$g\" type=\"model\" name=\"$name\"><components>")
        for (k in parts.indices) sb.append("<component objectid=\"${k + 1}\"/>")
        sb.append("</components></object></resources><build><item objectid=\"$g\"/></build></model>")
        ZipOutputStream(Files.newOutputStream(path)).use { z -> z.putNextEntry(ZipEntry("3D/3dmodel.model")); z.write(sb.toString().toByteArray()); z.closeEntry() }
    }

    val d: Path = Files.createTempDirectory("plymeld-lift")
    Files.writeString(d.resolve("hw.toml"), hardware)
    val ramp = d.resolve("ramp.3mf")
    threeMf(ramp, "ramp", listOf(
        "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0) + box(20.0, -5.0, 0.0, 28.0, 5.0, 12.0),
        "strip" to strip(listOf(-28.0 to 6.0, -20.0 to 6.0, 20.0 to 12.0, 28.0 to 12.0))))
    val flat = d.resolve("flat.3mf")
    threeMf(flat, "flat", listOf(
        "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 10.0) + box(20.0, -5.0, 0.0, 28.0, 5.0, 10.0),
        "strip" to strip(listOf(-28.0 to 10.0, 28.0 to 10.0))))
    // bond-first cooling locked off: these tests hold the filament's own temperatures (the strip's thin section would weigh it on)
    fun lock(obj: String, technique: String) = """
        [plan.object.$obj]
        pose.label = "as given (-Z down)"
        pose.turn = "1 0 0 0 1 0 0 0 1"
        band.1.technique = "$technique"
        bond_cooling = "off"
    """.trimIndent() + (if (technique == "flat") "\nband.1.heights = \"one\"\nband.1.layer.height = \"0.2 mm\"" else "")   // flat layers of one height
    fun slice(model: Path, obj: String, technique: String, out: String): String {
        Files.writeString(d.resolve("$out.toml"), lock(obj, technique))
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$out.toml").toString(), "-m", model.toString(), "-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }

    class Seg(val layer: Int, val feature: String, val x0: Double, val x1: Double, val y0: Double, val y1: Double, val z0: Double, val z1: Double)
    fun segs(g: String): List<Seg> {
        val out = ArrayList<Seg>(); var layer = -1; var feature = ""
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        val w = Regex("([XYZE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(":").trim(); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z
            // the model's lines only: the prime line lies beside the first layer, along the span, on a peel tab whose partial
            // brim lines are labeled Brim (the ramp itself has no brim)
            if (layer >= 0 && feature != "Prime line" && feature != "Brim" && (m["E"] ?: 0.0) > 0 && !x.isNaN() && (nx != x || ny != y)) out += Seg(layer, feature, x, nx, y, ny, z, nz)
            x = nx; y = ny; z = nz
        }
        return out
    }
    fun middle(s: List<Seg>) = (s.minOf { minOf(it.x0, it.x1) } + s.maxOf { maxOf(it.x0, it.x1) }) / 2
    /**
     * The first layer printing anywhere over the gap (a line reaching |x - middle| < 19; the pillars stand at 20 and beyond, and
     * no line joins them before the strip: a line along the strip's rise runs from one kink to the other, its ends over them).
     */
    fun firstOverGap(s: List<Seg>, cx: Double) = s.first { minOf(it.x0, it.x1) < cx + 19 && maxOf(it.x0, it.x1) > cx - 19 }.layer

    val rampG by lazy { slice(ramp, "ramp", "bridge", "ramp.gcode") }

    /**
     * Where a support of height [s] is printed to on a grid of a [first] layer then 0.2 mm: the top of the last layer whose
     * middle lies under [s] (a layer holds what is under its middle). Worked here on its own, not taken from plymeld.
     */
    fun printedTop(s: Double, first: Double): Double {
        var top = first; var mid = first / 2
        if (mid >= s) return 0.0
        while (top + 0.1 < s) { top += 0.2; mid = top - 0.1 }
        return if (mid < s) top else top - 0.2
    }
    "the ramp's strip comes after both pillars: its first layer laid a layer above them, straight from one to the other" {
        val s = segs(rampG); val cx = middle(s)
        val first = s.filter { it.layer == 0 }.maxOf { maxOf(it.z0, it.z1) }   // the file's own first layer
        val lowTop = printedTop(6.0, first); val highTop = printedTop(12.0, first)
        val k = firstOverGap(s, cx)
        val before = s.filter { it.layer < k }
        before.filter { it.x0 < cx - 20.5 }.maxOf { maxOf(it.z0, it.z1) } shouldBe (lowTop plusOrMinus 1e-3)    // the low pillar, finished
        before.filter { it.x0 > cx + 20.5 }.maxOf { maxOf(it.z0, it.z1) } shouldBe (highTop plusOrMinus 1e-3)   // the tall one, finished
        // the strip rests on the tall pillar's printed top; the low one's top falls the same way on the grid (6 mm apart = 30
        // layers), so the strip rests on both: its first layer one layer above each
        val shift = highTop - 12.0
        val first0 = s.filter { it.layer == k }
        first0.minOf { minOf(it.z0, it.z1) } shouldBe ((6.0 + shift + 0.2) plusOrMinus 1e-3)
        first0.maxOf { maxOf(it.z0, it.z1) } shouldBe ((12.0 + shift + 0.2) plusOrMinus 1e-3)
        // on the strip's underside all along every line, sampled every 0.1 mm: flat over the low pillar, the straight rise over
        // the gap, flat over the tall one (line ends alone missed walls cut straight from end to end: symmetric about the
        // middle, they are on the rise there and 0.86 mm under it at the kinks; printed, they cut into the pillar)
        fun under(x: Double) = 6.0 + 6.0 * ((x - (cx - 20)) / 40).coerceIn(0.0, 1.0)
        var worst = 0.0
        for (seg in first0) {
            val n = maxOf(1, Math.ceil(hypot(seg.x1 - seg.x0, seg.y1 - seg.y0) / 0.1).toInt())
            for (i in 0..n) { val t = i.toDouble() / n; worst = maxOf(worst, abs(seg.z0 + (seg.z1 - seg.z0) * t - (under(seg.x0 + (seg.x1 - seg.x0) * t) + shift + 0.2))) }
        }
        worst shouldBe (0.0 plusOrMinus 0.0125)          // the slicing resolution (0.01), plus the file's µm rounding
    }
    "the curved check passes the ramp; control: one wall pass put back as a single straight line, end to end, is refused" {
        rampG.shouldContain(";LAYER_CHANGE")                                                  // sliced: its check passed
        val product = Product(plymeld.config.ConfigStack.load(listOf("hw.toml", "ramp.gcode.toml"), emptyList(), d), listOf(ramp), null) { }
        val plan = product.decide()
        val zBase = plan.parts.minOf { it.bounds()[2] }
        curvedRead(rampG, plan, product.result.setup, zBase)!!.problems shouldBe emptyList()
        // the strip's first layer: its first outer wall's first pass (printing moves running one way in X) made one move
        val ls = rampG.lines().toMutableList()
        val k = firstOverGap(segs(rampG), middle(segs(rampG)))
        val layerAt = ls.indices.filter { ls[it].startsWith(";LAYER_CHANGE") || ls[it].startsWith("; CHANGE_LAYER") }[k]
        val wall = (layerAt until ls.size).first { ls[it].endsWith("Outer wall") }
        fun g(l: String, c: Char) = Regex(" $c(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
        val first = (wall until ls.size).first { ls[it].startsWith("G1 X") && (g(ls[it], 'E') ?: 0.0) > 0 }
        var last = first
        while (ls[last + 1].startsWith("G1 X") && (g(ls[last + 1], 'E') ?: 0.0) > 0 &&
            Math.signum(g(ls[last + 1], 'X')!! - g(ls[last], 'X')!!) == Math.signum(g(ls[first + 1], 'X')!! - g(ls[first], 'X')!!)) last++
        (last - first) shouldBeGreaterThan 1                                                  // the pass is split now
        val e = (first..last).sumOf { g(ls[it], 'E')!! }
        val end = ls[last].replace(Regex(" E[0-9.]+"), " E" + "%.5f".format(java.util.Locale.ROOT, e))
        for (i in last downTo first) ls.removeAt(i)
        ls.add(first, end)
        val p = curvedRead(ls.joinToString("\n"), plan, product.result.setup, zBase)!!.problems
        val off = p.firstNotNullOf { Regex("straying off the layer's surface between their ends, up to ([0-9.]+) mm").find(it)?.groupValues?.get(1)?.toDouble() }
        off shouldBeGreaterThan 0.5
    }
    "its first layer is a bridge along the span" {
        val s = segs(rampG); val cx = middle(s)
        val br = s.filter { it.layer == firstOverGap(s, cx) && it.feature == "Bridge" }
        val len = br.sumOf { hypot(it.x1 - it.x0, it.y1 - it.y0) }
        len shouldBeGreaterThan 100.0
        (br.sumOf { abs(it.x1 - it.x0) } / len) shouldBeGreaterThan 0.9
    }
    "control: the same ramp in flat layers prints over the gap before the tall pillar is finished" {
        val s = segs(slice(ramp, "ramp", "flat", "ramp_flat.gcode")); val cx = middle(s)
        val k = firstOverGap(s, cx)
        val tallTop = s.filter { it.x0 > cx + 20.5 && it.x1 > cx + 20.5 && it.layer < k }.maxOfOrNull { maxOf(it.z0, it.z1) } ?: 0.0
        tallTop shouldBe (6.28 plusOrMinus 0.25)    // nowhere near its 12.08: the gap's first lines come with the low end
    }
    "control: a flat bridge has nothing to lift: the same printed lines as in flat layers" {
        // printed moves only: in the bridge's layers every travel lifts over the layer (curved layers' travels), so the
        // retractions and hops between the lines differ, not the lines
        fun moves(g: String) = g.lines().filter { it.startsWith("G1 X") && Regex(" E[0-9.]+").containsMatchIn(it) }
        // (the fill left at its usual angles: plan.technique.bridge.fill_along and fill_angles = "span" turn it, which flat layers
        // do not; this is about the lift)
        Files.writeString(d.resolve("flat_bridge.gcode.toml"), lock("flat", "bridge") + "\ntechnique.bridge.fill_along = \"false\"\ntechnique.bridge.fill_angles = \"object\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("flat_bridge.gcode.toml").toString(), "-m", flat.toString(), "-o", d.resolve("flat_bridge.gcode").toString()))
        val lifted = moves(Files.readString(d.resolve("flat_bridge.gcode")))
        val plain = moves(slice(flat, "flat", "flat", "flat_one_height.gcode"))
        lifted.size shouldBeGreaterThan 100
        lifted shouldBe plain
    }
    // An arch: the strip rising 2 mm in a circular arc between two 6 mm pillars 40 mm apart, its top in the air
    // above both, so its first layer lies on no layer printed before it (the check took the pillars' tops as the layer under
    // it and refused the arch sweep: 1 to 3 mm "thick" over the gap)
    val archR = (20.0 * 20.0 + 2.0 * 2.0) / (2 * 2.0)
    fun archUnder(x: Double) = if (abs(x) >= 20) 6.0 else 6.0 + Math.sqrt(archR * archR - x * x) - (archR - 2.0)
    val arch = d.resolve("arch.3mf")
    threeMf(arch, "arch", listOf(
        "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0) + box(20.0, -5.0, 0.0, 28.0, 5.0, 6.0),
        "strip" to strip(listOf(-28.0 to 6.0) + (0..80).map { val x = -20.0 + it * 0.5; x to archUnder(x) } + listOf(28.0 to 6.0))))
    val archG by lazy { slice(arch, "arch", "bridge", "arch.gcode") }
    "an arch: its first layer laid along the arc, a layer above it all along, after both pillars; the check passing" {
        archG.shouldContain(";LAYER_CHANGE")                                              // sliced: its check passed
        val s = segs(archG); val cx = middle(s)
        val first = s.filter { it.layer == 0 }.maxOf { maxOf(it.z0, it.z1) }
        val top = printedTop(6.0, first); val shift = top - 6.0
        val k = firstOverGap(s, cx)
        s.filter { it.layer < k }.maxOf { maxOf(it.z0, it.z1) } shouldBe (top plusOrMinus 1e-3)   // both pillars, finished
        var worst = 0.0
        for (seg in s.filter { it.layer == k }) {
            val n = maxOf(1, Math.ceil(hypot(seg.x1 - seg.x0, seg.y1 - seg.y0) / 0.1).toInt())
            for (i in 0..n) { val t = i.toDouble() / n; worst = maxOf(worst, abs(seg.z0 + (seg.z1 - seg.z0) * t - (archUnder(seg.x0 + (seg.x1 - seg.x0) * t - cx) + shift + 0.2))) }
        }
        worst shouldBe (0.0 plusOrMinus 0.0125)
    }
    "control: the arch read with a thickest layer of 0.15 mm is refused, every layer measured 0.2 mm thick (not the rise)" {
        archG.shouldContain(";LAYER_CHANGE")
        val product = Product(plymeld.config.ConfigStack.load(listOf("hw.toml", "arch.gcode.toml"), emptyList(), d), listOf(arch), null) { }
        val plan = product.decide()
        val spec = curvedSpec(plan, product.result.setup, 0)!!
        val lim = spec.limits
        val tight = plymeld.core.check.CurvedCheck.Spec(spec.fromMm, plymeld.core.check.CurvedCheck.Limits(lim.minLayerMm, 0.15, lim.resolutionMm, lim.tip),
            spec.follows, spec.firstMm, spec.layerMm)
        val p = plymeld.core.check.CurvedCheck.read(archG, plan.parts, plan.parts.minOf { it.bounds()[2] }, tight).problems
        val thick = p.mapNotNull { Regex("([0-9.]+) mm thick at .*over the tool's thickest layer 0.150").find(it)?.groupValues?.get(1)?.toDouble() }
        thick.size shouldBeGreaterThan 0
        thick.max() shouldBe (0.2 plusOrMinus 0.002)
    }
    // Tacks (IncrementalSlicer.tacks): on the arch with bridge.tack.layers = 2, layers 2 and 3 of the strip each
    // start with tacks: the outermost lines first, each low where a first layer pulled straight between the pillars would put
    // the layer (the pillars' printed top, plus one layer per strip layer), rising to the layer's own height on the arc.
    fun tackSegs(g: String): List<Seg> {
        val out = ArrayList<Seg>(); var layer = -1; var feature = ""
        var x = Double.NaN; var y = Double.NaN; var z = Double.NaN
        val w = Regex("([XYZE])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { layer++; continue }
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) { feature = l.substringAfter(":").trim(); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z
            if (feature == "Bridge tack" && (m["E"] ?: 0.0) > 0 && (nx != x || ny != y)) out += Seg(layer, feature, x, nx, y, ny, z, nz)
            x = nx; y = ny; z = nz
        }
        return out
    }
    fun lockTack(layers: Int) = lock("arch", "bridge") + "\ntechnique.bridge.tack.layers = \"$layers\"\n"
    fun sliceTacked(layers: Int, out: String): String {
        Files.writeString(d.resolve("$out.toml"), lockTack(layers))
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$out.toml").toString(), "-m", arch.toString(), "-o", d.resolve(out).toString()))
        return Files.readString(d.resolve(out))
    }
    "tacks: layers 2 and 3 of the arch's strip, low over a straight first layer and rising to the layer; the check passing" {
        val g = sliceTacked(2, "arch_tack.gcode")                                           // its check passed
        val s = segs(archG); val cx = middle(s)
        val first = s.filter { it.layer == 0 }.maxOf { maxOf(it.z0, it.z1) }
        val top = printedTop(6.0, first)
        val k1 = firstOverGap(s, cx)
        val t = tackSegs(g)
        t.map { it.layer }.distinct() shouldBe listOf(k1 + 1, k1 + 2)
        for ((n, L) in listOf(k1 + 1, k1 + 2).withIndex()) {
            val low = t.filter { it.layer == L && abs(it.z1 - it.z0) < 1e-9 }                 // the runs at the low height
            low.size shouldBeGreaterThan 1
            // n + 2 = the strip layer: over a straight first layer it would lie the pillars' top plus that many layers up
            for (seg in low) seg.z0 shouldBe ((top + 0.2 * (n + 2)) plusOrMinus 2e-3)
            // the rises: each ends on the layer's own surface, on the arc
            for (seg in t.filter { it.layer == L && it.z1 > it.z0 + 1e-9 }) seg.z1 shouldBe ((archUnder(seg.x1 - cx) + (top - 6.0) + 0.2 * (n + 2)) plusOrMinus 2e-3)
            // the outermost lines first: the layer's first two tacks are its extremes across the strip
            val ys = low.map { it.y0 }
            setOf(ys[0], ys[1]) shouldBe setOf(ys.min(), ys.max())
        }
    }
    "control: no tacks without bridge.tack.layers; the check refuses a tack laid lower than a straight first layer" {
        tackSegs(archG).size shouldBe 0
        val g = sliceTacked(1, "arch_tack1.gcode")
        val lines = g.lines().toMutableList()
        // the first tack's descent, 0.5 mm deeper
        val i = lines.indexOfFirst { it == ";TYPE:Bridge tack" }
        // (the drop carries the bridge's move speed when it has one)
        val dz = (i until lines.size).first { Regex("^G1 Z[0-9.]+( F[0-9.]+)?$").matches(lines[it]) }
        val z = lines[dz].removePrefix("G1 Z").substringBefore(" ").toDouble()
        lines[dz] = "G1 Z" + "%.3f".format(java.util.Locale.ROOT, z - 0.5)
        val ex = (dz until lines.size).first { lines[it].startsWith("G1 X") && " E" in lines[it] }
        lines[ex] = lines[ex].replace(Regex("^G1 X(\\S+) Y(\\S+) E"), "G1 X$1 Y$2 Z" + "%.3f".format(java.util.Locale.ROOT, z - 0.5) + " E")
        val product = Product(plymeld.config.ConfigStack.load(listOf("hw.toml", "arch_tack1.gcode.toml"), emptyList(), d), listOf(arch), null) { }
        val plan = product.decide()
        curvedRead(lines.joinToString("\n"), plan, product.result.setup, plan.parts.minOf { it.bounds()[2] })!!.problems
            .any { "bridge tack line(s) out of bounds" in it && "lower than one layer above the bridge's lowest underside" in it } shouldBe true
    }
    // Aligned with the span (plan.technique.bridge.fill_angles = "span", the default, fill_along off: ending
    // with lines along the span on top, not across it): the arch's strip layers
    // above the first fill along its span (X) and across it, layer by layer, and its top surface along it. All along the bridge
    // (plan.technique.bridge.fill_along = true; the default earlier): every fill along the span like its
    // bridge lines, but the top surface at its usual angle ("Allow for non-parallel motion on the top/finishing layers").
    // Control: the object's own directions (fill_angles = "object"), the solid fill crossing at the usual angles.
    "the strip's fill along and across its span, its top along it; fill_along: all along, the top at its usual angle; control: the object's directions, diagonal solid fill" {
        fun fillAngles(g: String, feature: String): Set<Long> {
            val s = segs(g); val cx = middle(s); val k = firstOverGap(s, cx)
            return s.filter { it.layer > k && it.feature == feature && hypot(it.x1 - it.x0, it.y1 - it.y0) > 1 }
                .map { Math.round(Math.toDegrees(Math.atan2(abs(it.y1 - it.y0), abs(it.x1 - it.x0)))) }.toSet()
        }
        fun sliced(name: String, extra: String): String {
            Files.writeString(d.resolve("$name.gcode.toml"), lock("arch", "bridge") + "\n" + extra + "\n")
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$name.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("$name.gcode").toString()))
            return Files.readString(d.resolve("$name.gcode"))
        }
        withClue("default (span): solid fill along and across the span") { fillAngles(archG, "Internal solid infill") shouldBe setOf(0L, 90L) }
        withClue("default (span): the top along the span") { fillAngles(archG, "Top surface") shouldBe setOf(0L) }
        val along = sliced("arch_along", "technique.bridge.fill_along = \"true\"")
        withClue("fill_along: solid fill all along") { fillAngles(along, "Internal solid infill") shouldBe setOf(0L) }
        withClue("fill_along: the top at its usual angle") { (fillAngles(along, "Top surface") - setOf(0L, 90L)).isNotEmpty() shouldBe true }
        val cross = sliced("arch_cross", "technique.bridge.fill_angles = \"object\"")
        withClue("control: the object's directions, diagonal") { (fillAngles(cross, "Internal solid infill") - setOf(0L, 90L)).isNotEmpty() shouldBe true }
    }
    // The bridge's own speeds (plan.technique.bridge.print.speed / travel.speed: the "bridge" is all of the lifted part's
    // layers, and nothing overrides them): every extrusion in the strip's layers at 5 mm/s, every head move without one
    // (travel, Z move; feed rate modal) at most 9 mm/s. Retractions and their restores are not head moves:
    // they keep the filament's speeds.
    /** The speeds of the strip's extrusions and the fastest other head move from its first layer; with [belowTop], its top layer left out. */
    fun stripMoves(g: String, belowTop: Boolean = false): Pair<Set<Double>, Double> {
        val s = segs(g); val k = firstOverGap(s, middle(s))
        val top = if (belowTop) s.maxOf { it.layer } else Int.MAX_VALUE
        var layer = -1; var f = 0.0; var x = Double.NaN; var y = Double.NaN; var z = Double.NaN; var feature = ""
        val ext = HashSet<Double>(); var fastest = 0.0
        val w = Regex("([XYZEF])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE")) { layer++; continue }
            if (l.startsWith(";TYPE:")) { feature = l.substringAfter(":"); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            m["F"]?.let { f = it }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val nz = m["Z"] ?: z; val e = m["E"] ?: 0.0
            if (layer >= k && layer < top && feature != "Prime line" && !x.isNaN()) {
                if (e > 0 && (nx != x || ny != y)) ext += f / 60
                else if (nx != x || ny != y || nz != z) fastest = maxOf(fastest, f / 60)
            }
            x = nx; y = ny; z = nz
        }
        return ext to fastest
    }
    /** The speeds (mm/s) of the retractions and restores (moves of E only) in the strip's layers below its top. */
    fun stripRetractions(g: String): Set<Double> {
        val s = segs(g); val k = firstOverGap(s, middle(s)); val top = s.maxOf { it.layer }
        var layer = -1; var f = 0.0; val out = HashSet<Double>()
        val w = Regex("([XYZEF])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE")) { layer++; continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            m["F"]?.let { f = it }
            if (layer in k until top && m.keys.none { it in setOf("X", "Y", "Z") } && m["E"] != null) out += f / 60
        }
        return out
    }
    /**
     * Per strip layer (0 = its first, the first layer over the gap), the speeds of its extrusions whose middle is over the gap
     * (|x - middle| < 19) and over the pillars (|x - middle| > 21), mm/s rounded to 0.01.
     */
    fun airSpeeds(g: String): Map<Int, Pair<Set<Double>, Set<Double>>> {
        val s = segs(g); val k = firstOverGap(s, middle(s)); val cx = middle(s)
        var layer = -1; var f = 0.0; var x = Double.NaN; var y = Double.NaN; var feature = ""
        val gap = HashMap<Int, MutableSet<Double>>(); val held = HashMap<Int, MutableSet<Double>>()
        val w = Regex("([XYZEF])(-?[0-9.]+)")
        for (raw in g.lines()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE")) { layer++; continue }
            if (l.startsWith(";TYPE:")) { feature = l.substringAfter(":"); continue }
            val c = l.substringBefore(';').trim()
            if (!c.startsWith("G1 ")) continue
            val m = w.findAll(c).associate { it.groupValues[1] to it.groupValues[2].toDouble() }
            m["F"]?.let { f = it }
            val nx = m["X"] ?: x; val ny = m["Y"] ?: y; val e = m["E"] ?: 0.0
            if (layer >= k && feature != "Prime line" && !x.isNaN() && e > 0 && (nx != x || ny != y)) {
                val mid = abs((x + nx) / 2 - cx); val v = Math.round(f / 60 * 100) / 100.0
                if (mid < 19) gap.getOrPut(layer - k) { HashSet() } += v else if (mid > 21) held.getOrPut(layer - k) { HashSet() } += v
            }
            x = nx; y = ny
        }
        return (gap.keys + held.keys).associateWith { (gap[it] ?: emptySet<Double>()) to (held[it] ?: emptySet<Double>()) }
    }
    // the bridge's slow speed only over air, ramping up as plan.technique.bridge.ramp_speeds
    // (bundled 6, 8, 12, 24 mm/s) and ramp_growth (bundled 2) say to the bridge speed (plan.mechanism.motion.bridge.speed, 30 mm/s),
    // then the usual speeds; over the pillars the usual speeds throughout (it was every extrusion of its layers below its top at
    // plan.technique.bridge.print.speed). Travel still capped. The test strip has 5 layers below its top.
    "the bridge's own speeds over air: its first layer at plan.technique.bridge.print.speed, then the listed ramp speeds, then growing to the bridge speed, then the usual; over the pillars the usual; every other move at most plan.technique.bridge.travel.speed; controls: the bundled 4 mm/s, no ramp" {
        Files.writeString(d.resolve("arch_slow.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.print.speed = \"5 mm/s\"\ntechnique.bridge.travel.speed = \"9 mm/s\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_slow.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_slow.gcode").toString()))
        // (the end template's own moves follow the last layer: only up to the writer's last retraction counts)
        fun body(g: String) = g.substring(0, g.lastIndexOf("G1 E-")) + g.substring(g.lastIndexOf("G1 E-")).lines().first()
        val g = body(Files.readString(d.resolve("arch_slow.gcode")))
        val a = airSpeeds(g)
        // over the gap: 5, then the bundled list
        a.getValue(0).first shouldBe setOf(5.0)
        a.getValue(1).first shouldBe setOf(6.0)
        a.getValue(2).first shouldBe setOf(8.0)
        a.getValue(3).first shouldBe setOf(12.0)
        a.getValue(4).first shouldBe setOf(24.0)
        // over the pillars: the usual speeds from the first layer (the strip's ends were all at 5 before)
        a.getValue(0).second.isNotEmpty() shouldBe true
        (5.0 in a.getValue(0).second) shouldBe false
        (stripMoves(g, belowTop = true).second <= 9.0 + 1e-9) shouldBe true
        // retractions and restores at the filament's speeds, not the 9 mm/s cap
        val rs = stripRetractions(g)
        withClue("the strip's retractions and restores: $rs mm/s") { (rs.isNotEmpty() && rs.all { it > 9.0 + 1e-9 }) shouldBe true }
        // the bundled default (the winning arch approach): 4 mm/s over air on its first layer
        airSpeeds(archG).getValue(0).first shouldBe setOf(4.0)
        // past the list: doubling, capped at the bridge speed, then the usual (seen before that fix: the strip's fifth
        // layer all back at the first layer's speed)
        Files.writeString(d.resolve("arch_grow.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.print.speed = \"5 mm/s\"\ntechnique.bridge.ramp_speeds = \"7 mm/s\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_grow.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_grow.gcode").toString()))
        val gr = airSpeeds(body(Files.readString(d.resolve("arch_grow.gcode"))))
        listOf(0, 1, 2, 3).map { gr.getValue(it).first } shouldBe listOf(setOf(5.0), setOf(7.0), setOf(14.0), setOf(28.0))
        gr.getValue(4).first shouldBe setOf(30.0)
        Files.writeString(d.resolve("arch_short.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.print.speed = \"5 mm/s\"\ntechnique.bridge.ramp_speeds = \"10 mm/s\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_short.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_short.gcode").toString()))
        val sh = airSpeeds(body(Files.readString(d.resolve("arch_short.gcode"))))
        listOf(0, 1, 2, 3).map { sh.getValue(it).first } shouldBe listOf(setOf(5.0), setOf(10.0), setOf(20.0), setOf(30.0))
        withClue("layer 4 over the gap: ${sh.getValue(4).first}") { (sh.getValue(4).first intersect setOf(5.0, 10.0, 20.0)).isEmpty() shouldBe true }
        // no ramp: the usual speeds from its second layer
        Files.writeString(d.resolve("arch_noramp.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.print.speed = \"5 mm/s\"\ntechnique.bridge.ramp_speeds = \"\"\ntechnique.bridge.ramp_growth = \"1\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_noramp.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_noramp.gcode").toString()))
        val nr = airSpeeds(body(Files.readString(d.resolve("arch_noramp.gcode"))))
        nr.getValue(0).first shouldBe setOf(5.0)
        (nr.getValue(1).first intersect setOf(5.0, 6.0, 8.0, 10.0)).isEmpty() shouldBe true
    }
    // the bridge temperature on the bridge's layers only, not the whole object's from its second layer
    "only the arched bridge's own layers print cooler: the pillars at the print temperature, the bridge at plan.technique.bridge.temperature (bundled print - 15 C) waited for before it, its top layer at the print temperature, waited for; control: set, its own; top_layer bridge, no rise" {
        // pla-generic prints at 210 C, its first layer 215 C (bundled)
        fun temps(g: String) = g.lines().filter { Regex("^M10[49] S[1-9]").containsMatchIn(it) }.map { it.substringAfter(" S").substringBefore(" ").toInt() }
        temps(archG) shouldBe listOf(215, 210, 195, 210)
        archG.lines().first { it.startsWith("M10") && " S195" in it }.startsWith("M109") shouldBe true
        archG.lines().last { Regex("^M10[49] S[1-9]").containsMatchIn(it) }.startsWith("M109 S210") shouldBe true
        Files.writeString(d.resolve("arch_t200.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.temperature = \"200 C\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_t200.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_t200.gcode").toString()))
        temps(Files.readString(d.resolve("arch_t200.gcode"))) shouldBe listOf(215, 210, 200, 210)
        Files.writeString(d.resolve("arch_tb.gcode.toml"), lock("arch", "bridge") + "\ntechnique.bridge.top_layer = \"bridge\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_tb.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_tb.gcode").toString()))
        temps(Files.readString(d.resolve("arch_tb.gcode"))) shouldBe listOf(215, 210, 195)
    }
    "only an arched bridge takes the bridge's own speeds and temperature: the ramp (straight between its supports) prints as before" {
        fun temps(g: String) = g.lines().filter { Regex("^M10[49] S[1-9]").containsMatchIn(it) }.map { it.substringAfter(" S").substringBefore(" ").toInt() }
        // the arch: 4 mm/s, 195 C (above); the ramp: the filament's 210 C after its first layer, its strip not all at 4 mm/s
        temps(rampG) shouldBe listOf(215, 210)
        (stripMoves(rampG).first - setOf(4.0)).isNotEmpty() shouldBe true
    }
    "a strip one layer thick over air is bridged (bridge lines), not given a top surface" {
        val thin = d.resolve("thin.3mf")
        threeMf(thin, "thin", listOf(
            "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0) + box(20.0, -5.0, 0.0, 28.0, 5.0, 6.0),
            "strip" to strip(listOf(-28.0 to 6.0, 28.0 to 6.0), t = 0.2)))
        val g = slice(thin, "thin", "bridge", "thin.gcode")
        val s = segs(g); val cx = middle(s)
        // the lines across the middle of the gap (the pillars have none there)
        val over = s.filter { minOf(it.x0, it.x1) < cx && maxOf(it.x0, it.x1) > cx && abs(it.y0 - it.y1) < 1e-6 && abs(it.x1 - it.x0) > 1 }
        over.isNotEmpty() shouldBe true
        // its walls and, between them, bridge lines: no top surface
        over.map { it.feature }.filter { !it.endsWith("wall") }.toSet() shouldBe setOf("Bridge")
    }
    // Several lifted bridges in one object (a tube with an arched strip every 5 mm up, the wall carrying on
    // above each strip's ends). Two pillars 5 mm wide, each a stub to 6 mm, the first strip's end (6 to 7.2), the pillar again
    // to 12, the second strip's end (12 to 13.2), the pillar again to 18. Both strips are the arch above, the second 6 mm higher.
    // Each strip is printed straight after the pillar layer it rests on (6.0 and 12.0 on this grid) and before the pillar
    // carries on above its ends; its first layer is laid along its own arc.
    val stack = d.resolve("stack.3mf")
    threeMf(stack, "stack", listOf(
        "pillars" to listOf(-28.0, 20.0).flatMap { x -> box(x, -2.5, 0.0, x + 8, 2.5, 6.0) + box(x, -2.5, 7.2, x + 8, 2.5, 12.0) + box(x, -2.5, 13.2, x + 8, 2.5, 18.0) },
        "low" to strip(listOf(-28.0 to 6.0) + (0..80).map { val x = -20.0 + it * 0.5; x to archUnder(x) } + listOf(28.0 to 6.0)),
        "high" to strip(listOf(-28.0 to 12.0) + (0..80).map { val x = -20.0 + it * 0.5; x to archUnder(x) + 6.0 } + listOf(28.0 to 12.0))))
    val stackG by lazy { slice(stack, "stack", "bridge", "stack.gcode") }
    "two lifted bridges in one object: each after the pillar layer it rests on, before the pillar above its ends; the check passing" {
        stackG.shouldContain(";LAYER_CHANGE")                                             // sliced: its check passed
        val s = segs(stackG); val cx = middle(s)
        fun gap(it: Seg) = minOf(it.x0, it.x1) < cx + 19 && maxOf(it.x0, it.x1) > cx - 19
        fun pillar(it: Seg) = abs(it.x0 - cx) > 20.5 && abs(it.x1 - cx) > 20.5
        for ((base, name) in listOf(6.0 to "low", 12.0 to "high")) {
            val strip = s.filter { gap(it) && maxOf(it.z0, it.z1) > base && minOf(it.z0, it.z1) < base + 3.3 }
            val layers = strip.map { it.layer }.toSortedSet()
            withClue(name) {
                layers.size shouldBe 6                                                      // 1.2 mm in 0.2 mm layers
                // every pillar line up to the stub's printed top before it, every one above the strip's ends after it
                s.filter { pillar(it) && maxOf(it.z0, it.z1) <= base + 1e-3 }.maxOf { it.layer } shouldBeLessThan layers.first()
                s.filter { pillar(it) && minOf(it.z0, it.z1) > base + 1.2 + 1e-3 && maxOf(it.z0, it.z1) < base + 6 }.minOf { it.layer } shouldBeGreaterThan layers.last()
                // its first layer along its own arc, a layer above it
                var worst = 0.0
                for (seg in s.filter { it.layer == layers.first() }) {
                    val n = maxOf(1, Math.ceil(hypot(seg.x1 - seg.x0, seg.y1 - seg.y0) / 0.1).toInt())
                    for (i in 0..n) { val t = i.toDouble() / n; worst = maxOf(worst, abs(seg.z0 + (seg.z1 - seg.z0) * t - (archUnder(seg.x0 + (seg.x1 - seg.x0) * t - cx) + base - 6.0 + 0.2))) }
                }
                worst shouldBe (0.0 plusOrMinus 0.0125)
            }
        }
    }
    "the pillar layers over each strip's end stand on the strip: no wave bead or bridge line there; control: the strip's end is under them" {
        val s = segs(stackG); val cx = middle(s)
        fun pillar(it: Seg) = abs(it.x0 - cx) > 20.5 && abs(it.x1 - cx) > 20.5
        for (base in listOf(6.0, 12.0)) {
            // the strip's end: lines of the strip's own layers over the pillars, under the pillar layer above it
            s.filter { pillar(it) && minOf(it.z0, it.z1) > base && maxOf(it.z0, it.z1) <= base + 1.2 + 1e-3 }.shouldNotBeEmpty()
            val over = s.filter { pillar(it) && minOf(it.z0, it.z1) > base + 1.2 + 1e-3 && maxOf(it.z0, it.z1) <= base + 1.6 + 1e-3 }
            withClue("over the strip at $base: ${over.map { it.feature }.toSet()}") {
                over.shouldNotBeEmpty()
                over.none { it.feature.startsWith("Wave overhang") || it.feature == "Bridge" } shouldBe true
            }
        }
    }
    "control: two lifted bridges, the high one's third layer without its outer wall, is refused in that bridge's own frame" {
        stackG.shouldContain(";LAYER_CHANGE")
        val product = Product(plymeld.config.ConfigStack.load(listOf("hw.toml", "stack.gcode.toml"), emptyList(), d), listOf(stack), null) { }
        val plan = product.decide()
        val zBase = plan.parts.minOf { it.bounds()[2] }
        fun modelProblems(g: String): List<String> {
            val rd = curvedRead(g, plan, product.result.setup, zBase)!!
            return rd.problems + rd.frames!!.flatMap { fr -> plymeld.core.check.ModelCheck.check(fr.text, fr.parts, zBase).map { "${fr.name}: $it" } }
        }
        modelProblems(stackG) shouldBe emptyList()
        val s = segs(stackG); val cx = middle(s)
        val high = s.filter { minOf(it.x0, it.x1) < cx + 19 && maxOf(it.x0, it.x1) > cx - 19 && minOf(it.z0, it.z1) > 12.0 && minOf(it.z0, it.z1) < 15.3 }.map { it.layer }.toSortedSet().toList()
        val ls = stackG.lines()
        val marks = ls.indices.filter { ls[it].startsWith(";LAYER_CHANGE") || ls[it].startsWith("; CHANGE_LAYER") }
        val from = marks[high[2]]; val to = marks[high[2] + 1]
        // that layer's outer wall printed as moves without plastic
        var feat = ""
        val cut = ls.mapIndexed { i, l ->
            if (l.startsWith(";TYPE:") || l.startsWith("; FEATURE:")) feat = l.substringAfter(':').trim()
            if (i in from until to && feat == "Outer wall" && l.startsWith("G1 X")) l.replace(Regex(" E[0-9.]+"), "") else l
        }.joinToString("\n")
        val p = modelProblems(cut)
        p.any { it.startsWith("bridge 2:") } shouldBe true
        p.none { it.startsWith("bridge 1:") || it.startsWith("supports:") } shouldBe true
    }
    "two lifted bridges on a plate beside a flat bridge, one object at a time and together: the check passing both ways" {
        val both = """
            [plan]
            objects.printing = "one_at_a_time"
        """.trimIndent() + "\n" + lock("stack", "bridge") + "\n" + lock("flat", "flat")
        fun plate(text: String, out: String): String {
            Files.writeString(d.resolve("$out.toml"), text)
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$out.toml").toString(), "-m", stack.toString(), "-m", flat.toString(), "-o", d.resolve(out).toString()))
            return Files.readString(d.resolve(out))
        }
        val g = plate(both, "stack_plate.gcode")                                          // sliced: its check passed
        // both objects' layers: the stack's 102 (as alone) and the flat bridge's 56
        segs(g).map { it.layer }.toSet().size shouldBeGreaterThan 102
        // printed together: the stack's layers in its own order (each bridge after the layer it rests
        // on) merged with the flat bridge's by height; its check passed too, each object read by the file's object markers
        val g2 = plate(both.replace("one_at_a_time", "together"), "stack_together.gcode")
        g2 shouldContain "; object start stack"
        g2 shouldContain "; object start flat"
        // more layers than the flat bridge alone (56), fewer than one at a time (layers topping at one height share a start)
        segs(g2).map { it.layer }.toSet().size shouldBeGreaterThan 56
        segs(g2).map { it.layer }.toSet().size shouldBeLessThan segs(g).map { it.layer }.toSet().size
    }
    // plan.technique.bridge.layer.height (the double helix: its wall at 0.1 mm, its arches at 0.2): the stack's pillars in 0.1 mm
    // layers, its strips' in 0.2 mm ones (each layer's height from the file's own ;HEIGHT); control: a lone lifted bridge with a
    // layer height of its own is refused (it shares the object's layers)
    "two lifted bridges with a layer height of their own: the pillars at the object's 0.1 mm, the strips at 0.2; the check passing; control: a lone bridge refuses one" {
        Files.writeString(d.resolve("stack_fine.gcode.toml"), lock("stack", "bridge") + "\ntechnique.flat.layer.height = \"0.1 mm\"\ntechnique.bridge.layer.height = \"0.2 mm\"\n")
        SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("stack_fine.gcode.toml").toString(), "-m", stack.toString(), "-o", d.resolve("stack_fine.gcode").toString()))
        val g = Files.readString(d.resolve("stack_fine.gcode"))
        val s = segs(g); val cx = middle(s)
        val heights = g.lines().filter { it.startsWith(";HEIGHT:") }.map { it.removePrefix(";HEIGHT:").toDouble() }
        val strip = s.filter { minOf(it.x0, it.x1) < cx + 19 && maxOf(it.x0, it.x1) > cx - 19 }.map { it.layer }.toSortedSet()
        val pillar = s.filter { abs(it.x0 - cx) > 20.5 && abs(it.x1 - cx) > 20.5 }.map { it.layer }.toSortedSet() - strip
        strip.size shouldBe 12                                                            // 2 x 1.2 mm in 0.2 mm layers
        strip.map { heights[it] }.toSet() shouldBe setOf(0.2)
        pillar.drop(1).map { heights[it] }.toSet() shouldBe setOf(0.1)                     // after the first layer
        Files.writeString(d.resolve("arch_fine.gcode.toml"), lock("arch", "bridge") + "\ntechnique.flat.layer.height = \"0.1 mm\"\ntechnique.bridge.layer.height = \"0.2 mm\"\n")
        shouldThrow<Exception> {
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("arch_fine.gcode.toml").toString(), "-m", arch.toString(), "-o", d.resolve("arch_fine.gcode").toString()))
        }.message!! shouldContain "a lone lifted bridge shares the object's layers"
    }
    // A strip on supports off the layer grid: 6.05 mm pillars print to 6.0 on this 0.2 mm grid (a layer holds what
    // is at or under its middle), so the strip is printed 0.05 mm lower than the model has it, resting on them. Its model, lifted
    // for slicing, must move with it: left where the model has it, it sat off the grid and its 1.2 mm came out as six layers and
    // a sliver (the double helix's strips: 0.22 mm as two of 0.11; the arches: a 0.12 mm last layer). 6.1 mm pillars, a layer's
    // middle exactly: the slicer prints them to 6.2, so the strip moves up 0.1 (taken as 6.0, its first layer was laid at the
    // pillars' own last). Each layer's height from the file's ;HEIGHT. Control: a 1.3 mm strip, which no 0.2 mm grid divides,
    // does show another height.
    "a strip on supports off the layer grid prints in whole layers: 1.2 mm as six of 0.2; control: 1.3 mm cannot" {
        fun offGrid(t: Double, h: Double, name: String): List<Double> {
            val m = d.resolve("$name.3mf")
            threeMf(m, name, listOf(
                "supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, h) + box(20.0, -5.0, 0.0, 28.0, 5.0, h),
                "strip" to strip(listOf(-28.0 to h) + (0..80).map { val x = -20.0 + it * 0.5; x to archUnder(x) + h - 6.0 } + listOf(28.0 to h), t = t)))
            val g = slice(m, name, "bridge", "$name.gcode")
            val s = segs(g); val cx = middle(s)
            val heights = g.lines().filter { it.startsWith(";HEIGHT:") }.map { it.removePrefix(";HEIGHT:").toDouble() }
            return s.filter { minOf(it.x0, it.x1) < cx + 19 && maxOf(it.x0, it.x1) > cx - 19 }.map { it.layer }.toSortedSet().map { heights[it] }
        }
        offGrid(1.2, 6.05, "offgrid") shouldBe List(6) { 0.2 }
        offGrid(1.2, 6.1, "offgrid_tie") shouldBe List(6) { 0.2 }
        offGrid(1.3, 6.05, "offgrid13").toSet().size shouldBeGreaterThan 1
    }
    "a strip resting on no support is refused, with the reason" {
        val loose = d.resolve("loose.3mf")
        threeMf(loose, "loose", listOf("supports" to box(-28.0, -5.0, 0.0, -20.0, 5.0, 6.0), "strip" to strip(listOf(0.0 to 8.0, 10.0 to 8.0))))
        shouldThrow<Exception> { slice(loose, "loose", "bridge", "loose.gcode") }.message!! shouldContain "rests on no support"
    }
    // plan.bridge_order = hot_to_cold (every foot first at one temperature, then the bridges hot to cold): two arches on one
    // plate, hot at +X
    "hot_to_cold: every foot first, then the 210 C arch's bridge whole, then the 195 C arch's, one temperature change; the check passes; control: by height, the two bridges interleave" {
        fun moved(ts: List<DoubleArray>, dx: Double, dy: Double) = ts.map { t -> DoubleArray(9) { i -> t[i] + if (i % 3 == 0) dx else if (i % 3 == 1) dy else 0.0 } }
        // pillars 8 x 10 x 6 mm, 20 mm apart, the strip arching 2 mm between them
        val arc = (0..40).map { i -> val x = -10.0 + i * 0.5; val r = (100.0 + 4.0) / 4.0; x to 6.0 + Math.sqrt(r * r - x * x) - (r - 2.0) }
        fun arch(path: Path, name: String, cx: Double) = threeMf(path, name, listOf(
            "supports" to moved(box(-18.0, -5.0, 0.0, -10.0, 5.0, 6.0) + box(10.0, -5.0, 0.0, 18.0, 5.0, 6.0), cx, 125.0),
            "strip" to moved(strip(listOf(-18.0 to 6.0) + arc + listOf(18.0 to 6.0)), cx, 125.0)))
        arch(d.resolve("hot.3mf"), "hot", 160.0)
        arch(d.resolve("cold.3mf"), "cold", 90.0)
        fun plate(order: String?) = """
            [plan]
            pose = "as-given"
            placement = "as-given"
            objects.printing = "together"
            bond_cooling = "off"
            ${order?.let { "bridge_order = \"$it\"" } ?: ""}
            [plan.technique.bridge]
            top_layer = "bridge"
            [plan.object.hot]
            band.1.technique = "bridge"
            technique.bridge.temperature = "210 C"
            [plan.object.cold]
            band.1.technique = "bridge"
            technique.bridge.temperature = "195 C"
        """.trimIndent()
        /**
         * The g-code's object blocks that print something, in order, each (object, the layer's Z), and the nozzle temperatures
         * set after the first layer. (The layers planned flat above the pillars, where the strip is not, are empty blocks:
         * the strip prints in its lifted band.)
         */
        fun read(g: String): Pair<List<Pair<String, Double>>, List<Int>> {
            val blocks = ArrayList<Pair<String, Double>>(); val temps = ArrayList<Int>(); var z = 0.0; var layers = 0
            var open: Pair<String, Double>? = null; var printed = false
            val extrusion = Regex("^G1 .*[XY].* E[0-9.]+")
            for (l in g.lines()) {
                when {
                    l.startsWith(";Z:") -> { z = l.removePrefix(";Z:").toDouble(); layers++ }
                    l.startsWith("; object start ") -> { open = l.removePrefix("; object start ").substringBefore("_id_") to z; printed = false }
                    l.startsWith("; object end") -> { if (printed) blocks += open!!; open = null }
                    open != null && extrusion.containsMatchIn(l) -> printed = true
                    layers > 1 && (l.startsWith("M109") || l.startsWith("M104")) -> temps += Regex("S(\\d+)").find(l)!!.groupValues[1].toInt()
                }
            }
            return blocks to temps
        }
        fun sliced(order: String?, out: String): String {
            Files.writeString(d.resolve("$out.toml"), plate(order))
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$out.toml").toString(),
                "-m", d.resolve("hot.3mf").toString(), "-m", d.resolve("cold.3mf").toString(), "-o", d.resolve(out).toString()))   // the check runs: a problem throws
            return Files.readString(d.resolve(out))
        }
        val (blocks, temps) = read(sliced("hot_to_cold", "hot_to_cold.gcode"))
        val strips = blocks.filter { it.second > 6.1 }
        // every foot layer (to 6.08) before any strip layer
        blocks.indexOfLast { it.second <= 6.1 } shouldBe blocks.size - strips.size - 1
        // the hot arch's strip whole, then the cold one's
        strips.map { it.first }.distinct() shouldBe listOf("hot", "cold")
        strips.indexOfLast { it.first == "hot" } shouldBe strips.indexOfFirst { it.first == "cold" } - 1
        // one change of temperature after the first layer's: down to 195 for the cold bridge (the hot one's is the print temperature)
        temps.filter { it > 0 } shouldBe listOf(210, 195)
        // control: by height, the two strips' layers alternate, and the temperature goes back and forth
        val (byHeight, byTemps) = read(sliced(null, "by_height.gcode"))
        val s2 = byHeight.filter { it.second > 6.1 }.map { it.first }
        s2.zipWithNext().count { (a, b) -> a != b } shouldBeGreaterThan 2
        byTemps.count { it == 195 } shouldBeGreaterThan 1
    }
    // plan.technique.bridge.first_layer = "wave_overhang" with
    // plan.bridge_order = "one_at_a_time" (the feet first, then the bridges one after another)
    "a waved arch: its first layer wave beads, one side from its pillar up to the middle, then the other, none running down; two arches one after the other from the side the head reaches least (+X here; mirrored, -X); the check passes; wave.sides alternate: the sides front by front, still climbing; wave.edges.order last: every edge piece after both sides' beads; one_support: from one pillar over the peak and down to the other; wave.layers 2: the first two layers together; control: lines" {
        fun moved(ts: List<DoubleArray>, dx: Double, dy: Double) = ts.map { t -> DoubleArray(9) { i -> t[i] + if (i % 3 == 0) dx else if (i % 3 == 1) dy else 0.0 } }
        // the strip along X: pillars 8 x 10 x 6 mm, 20 mm apart, arching 2 mm
        val arc = (0..40).map { i -> val x = -10.0 + i * 0.5; val r = 26.0; x to 6.0 + Math.sqrt(r * r - x * x) - (r - 2.0) }
        fun arch(path: Path, name: String, cx: Double) = threeMf(path, name, listOf(
            "supports" to moved(box(-18.0, -5.0, 0.0, -10.0, 5.0, 6.0) + box(10.0, -5.0, 0.0, 18.0, 5.0, 6.0), cx, 125.0),
            "strip" to moved(strip(listOf(-18.0 to 6.0) + arc + listOf(18.0 to 6.0)), cx, 125.0)))
        // across X: the second arch beyond the head's 20 mm reach towards +X from the first
        arch(d.resolve("wa.3mf"), "wa", 80.0)
        arch(d.resolve("wb.3mf"), "wb", 170.0)
        fun sliced(first: String, out: String, sides: String = "one_then_other", edges: String = "with_each_side", layers: String = "1", edgesOn: Boolean = true,
                   cv: List<String> = emptyList(), extra: String = ""): String {
            Files.writeString(d.resolve("$out.toml"), """
                [plan]
                pose = "as-given"
                placement = "as-given"
                objects.printing = "together"
                bridge_order = "one_at_a_time"
                bond_cooling = "off"
                [plan.technique.bridge]
                first_layer = "$first"
                wave.sides = "$sides"
                wave.edges.order = "$edges"
                wave.edges.enabled = "$edgesOn"
                wave.layers = "$layers"
                $extra
                [plan.object.wa]
                band.1.technique = "bridge"
                [plan.object.wb]
                band.1.technique = "bridge"
            """.trimIndent())
            SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("$out.toml").toString(),
                "-m", d.resolve("wa.3mf").toString(), "-m", d.resolve("wb.3mf").toString(), "-o", d.resolve(out).toString()) + cv.flatMap { listOf("-cv", it) })   // the check runs: a problem throws
            return Files.readString(d.resolve(out))
        }
        /** Per object, its wave beads in the order laid, each as (x, z) points; and the objects' strip blocks in order. */
        fun read(g: String): Pair<Map<String, List<List<DoubleArray>>>, List<String>> {
            val beads = LinkedHashMap<String, MutableList<MutableList<DoubleArray>>>(); val order = ArrayList<String>()
            var obj: String? = null; var feature = ""; var x = 0.0; var z = 0.0; var layerZ = 0.0; var cur: MutableList<DoubleArray>? = null
            for (l in g.lines()) {
                when {
                    l.startsWith(";Z:") -> layerZ = l.removePrefix(";Z:").toDouble()
                    l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                    l.startsWith("; object end") -> { cur = null; obj = null }
                    l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                    l.startsWith("G1") -> {
                        val nx = Regex(" X(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: x
                        val nz = Regex(" Z(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: z
                        val ext = Regex(" E([0-9.]+)").containsMatchIn(l) && (" X" in l || " Y" in l)
                        if (obj != null && ext && layerZ > 6.1 && (order.isEmpty() || order.last() != obj)) order += obj
                        if (obj != null && ext && feature.startsWith("Wave overhang")) {
                            if (cur == null) { cur = arrayListOf(doubleArrayOf(x, z)); beads.getOrPut(obj) { ArrayList() } += cur }
                            cur += doubleArrayOf(nx, nz)
                        } else if (" X" in l || " Y" in l) cur = null
                        x = nx; z = nz
                    }
                }
            }
            return beads to order
        }
        val (beads, order) = read(sliced("wave_overhang", "wave_arch.gcode"))
        // each bridge whole, from +X: wb, then wa
        order shouldBe listOf("wb", "wa")
        for ((name, bs) in beads) withClue(name) {
            val cx = if (name == "wa") 80.0 else 170.0
            (bs.size >= 20) shouldBe true
            // each bead level or climbing (its last point no lower than its first, within a hundredth)
            bs.count { b -> b.last()[1] < b.first()[1] - 0.01 } shouldBe 0
            // one side of the middle whole, then the other: the side (x of the bead's middle against the arch's) changes once
            val sides = bs.map { b -> b[b.size / 2][0] < cx }
            sides.zipWithNext().count { (a, b) -> a != b } shouldBe 1
        }
        beads.keys shouldBe setOf("wa", "wb")
        // the order follows the head: this tool's nozzle sits 70 mm from the head's left edge in a 90 mm head (reaching
        // 20 mm towards +X), so the bridges go from +X (wb first); control: the head mirrored (the nozzle 20 mm from its left
        // edge, reaching least towards -X), from -X (wa first)
        read(sliced("wave_overhang", "wave_arch_mirrored.gcode", cv = listOf("hardware.tool.t1.head.nozzle.x=20 mm"))).second shouldBe listOf("wa", "wb")
        // plan.technique.bridge.wave.sides = "alternate" (the sides front by front): the
        // same beads, each still climbing, the side changing at nearly every front
        val (alt, altOrder) = read(sliced("wave_overhang", "wave_alt.gcode", "alternate"))
        altOrder shouldBe listOf("wb", "wa")
        for ((name, bs) in alt) withClue(name) {
            val cx = if (name == "wa") 80.0 else 170.0
            bs.size shouldBe beads.getValue(name).size
            bs.count { b -> b.last()[1] < b.first()[1] - 0.01 } shouldBe 0
            val sides = bs.map { b -> b[b.size / 2][0] < cx }
            sides.zipWithNext().count { (a, b) -> a != b } shouldBeGreaterThan bs.size / 4
        }
        // plan.technique.bridge.wave.edges.order = "last": the edge pieces (beads along the span, which runs
        // along X here) after both sides' beads across, which with them laid per side come right after their side's
        val (last, lastOrder) = read(sliced("wave_overhang", "wave_edges_last.gcode", edges = "last"))
        lastOrder shouldBe listOf("wb", "wa")
        fun alongSpan(b: List<DoubleArray>) = Math.abs(b.last()[0] - b.first()[0]) > 1.0   // the span is along X here
        for ((name, bs) in last) withClue(name) {
            val base = beads.getValue(name)
            bs.size shouldBe base.size
            bs.count { b -> b.last()[1] < b.first()[1] - 0.01 } shouldBe 0
            val n = bs.count(::alongSpan)
            (n >= 2) shouldBe true
            bs.takeLast(n).all(::alongSpan) shouldBe true
            // control: laid with each side, an edge piece comes before the other side's beads across
            base.takeLast(n).all(::alongSpan) shouldBe false
        }
        // plan.technique.bridge.wave.sides = "one_support" (the ascending and descending
        // approach): the fronts from the -X pillar only, so the beads across the strip
        // (x the same at both ends) past the middle step down towards the +X pillar; control: one side then the other, whose
        // beads on that side step up from the +X pillar
        val (one, oneOrder) = read(sliced("wave_overhang", "wave_one.gcode", "one_support"))
        oneOrder shouldBe listOf("wb", "wa")
        fun farSteps(bs: List<List<DoubleArray>>, cx: Double): Pair<Int, Int> {
            val far = bs.filter { b -> Math.abs(b.last()[0] - b.first()[0]) < 0.05 && b[0][0] > cx + 1.0 }.map { it[0][1] }
            return far.zipWithNext().count { (a, b) -> b < a - 1e-3 } to far.zipWithNext().count { (a, b) -> b > a + 1e-3 }
        }
        for ((name, bs) in one) withClue(name) {
            val cx = if (name == "wa") 80.0 else 170.0
            val (down, up) = farSteps(bs, cx)
            (down > 5 && up == 0) shouldBe true
            val (cDown, cUp) = farSteps(beads.getValue(name), cx)
            withClue("control: one side then the other climbs from the far pillar") { (cUp > 5 && cDown == 0) shouldBe true }
        }
        // plan.technique.bridge.wave.layers = "2" with wave.edges.enabled = false (layers 1 and 2 combined, no
        // edges): every bead across the strip (x the same at both ends) laid twice, the second 0.2 mm (a layer) higher,
        // each second-layer bead only once its side's first layer reaches one front further out; the layer above gives way over
        // the gap; control: one waved layer (its next layer is laid over the gap)
        val pairedG = sliced("wave_overhang", "wave_paired.gcode", "alternate", layers = "2", edgesOn = false)
        val (paired, pairedOrder) = read(pairedG)
        pairedOrder shouldBe listOf("wb", "wa")
        fun across(b: List<DoubleArray>) = Math.abs(b.last()[0] - b.first()[0]) < 0.05
        for ((name, bs) in paired) withClue(name) {
            val cx = if (name == "wa") 80.0 else 170.0
            val one = alt.getValue(name).filter(::across)
            withClue("no edge pieces") { bs.all(::across) shouldBe true }
            bs.size shouldBe 2 * one.size
            bs.count { b -> b.last()[1] < b.first()[1] - 0.01 } shouldBe 0
            // each x laid twice, a layer apart
            val atX = bs.withIndex().groupBy { Math.round(it.value[0][0] * 100) }
            withClue("every bead position laid twice") { atX.values.all { it.size == 2 } shouldBe true }
            withClue("a layer apart") { atX.values.all { g -> Math.abs(Math.abs(g[1].value[0][1] - g[0].value[0][1]) - 0.2) < 0.02 } shouldBe true }
            // the upper bead of each position after the lower one, and after the lower bead one front further out on its side
            val upper = atX.values.associate { g -> val (lo, hi) = g.sortedBy { it.value[0][1] }; hi.index to lo.index }
            withClue("upper after lower") { upper.all { (hi, lo) -> hi > lo } shouldBe true }
            for (side in listOf(true, false)) withClue("side ${if (side) "-X" else "+X"}") {
                var lowers = 0; var uppers = 0; val n = bs.count { (it[0][0] < cx) == side } / 2
                for ((k, b) in bs.withIndex()) {
                    if ((b[0][0] < cx) != side) continue
                    if (k in upper.keys) { uppers++; (lowers >= uppers) shouldBe true; if (uppers < n) (lowers > uppers) shouldBe true } else lowers++
                }
            }
        }
        /** Per object, the non-wave extrusion moves within 5 mm of the middle on the strip's second layer (the one after its waved layer). */
        fun secondLayerOverGap(g: String): Map<String, Int> {
            val out = HashMap<String, Int>(); val layers = HashMap<String, ArrayList<Int>>()
            var obj: String? = null; var feature = ""; var x = 0.0; var lz = 0.0; var serial = 0
            for (l in g.lines()) {
                when {
                    l.startsWith(";Z:") -> { lz = l.removePrefix(";Z:").toDouble(); serial++ }   // layers counted, not told apart by their label (two can share one)
                    l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                    l.startsWith("; object end") -> obj = null
                    l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                    l.startsWith("G1") -> {
                        val nx = Regex(" X(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: x
                        val ext = Regex(" E([0-9.]+)").containsMatchIn(l) && (" X" in l || " Y" in l)
                        val o = obj
                        if (o != null && ext && lz > 6.1) {
                            val ls = layers.getOrPut(o) { ArrayList() }
                            if (serial !in ls) ls += serial
                            val cx = if (o == "wa") 80.0 else 170.0
                            if (ls.indexOf(serial) == 1 && !feature.startsWith("Wave overhang") && Math.abs((x + nx) / 2 - cx) < 5) out.merge(o, 1, Int::plus)
                        }
                        x = nx
                    }
                }
            }
            return out
        }
        withClue("the second layer gives way over the gap") { secondLayerOverGap(pairedG) shouldBe emptyMap() }
        // plan.technique.bridge.wave.second_layer = "x" (layer 2 a crosshatch: only full Xs, one over the center
        // point): the first layer's lines across as with one waved layer; the
        // second only diagonals (x changing along them), a layer above the first, each X's two diagonals reaching wave.x_length
        // (1.4 mm, whole fronts) along the span but the middle one, which spans the middle; together they cover the span; each
        // laid only after its side's first layer covers its whole reach; the layer above gives way over the gap. Control: the
        // full second layer has no diagonal (above)
        val xG = sliced("wave_overhang", "wave_x.gcode", "alternate", layers = "2", edgesOn = false, extra = "wave.second_layer = \"x\"\nwave.x_length = \"1.4 mm\"")
        val (xs, xOrder) = read(xG)
        xOrder shouldBe listOf("wb", "wa")
        for ((name, bs) in xs) withClue(name) {
            val cx = if (name == "wa") 80.0 else 170.0
            val lines = bs.withIndex().filter { across(it.value) }
            val diag = bs.withIndex().filter { !across(it.value) }
            withClue("the first layer: the one waved layer's lines across") { lines.size shouldBe alt.getValue(name).count(::across) }
            withClue("diagonals in pairs, at least the middle X") { (diag.size % 2 == 0 && diag.size >= 2) shouldBe true }
            fun zAt(x: Double) = lines.minBy { Math.abs(it.value[0][0] - x) }.value[0][1]
            for ((t, d) in diag) {
                val x0 = minOf(d.first()[0], d.last()[0]); val x1 = maxOf(d.first()[0], d.last()[0])
                val middle = x0 < cx && x1 > cx
                withClue("diagonal $t (x $x0..$x1) a layer above the first") {
                    for (p in d) Math.abs(p[1] - zAt(p[0]) - 0.2) shouldBeLessThan 0.03 }
                if (!middle) withClue("diagonal $t reaches 1.4 mm (whole fronts)") { Math.abs(x1 - x0 - 1.4) shouldBeLessThan 0.2 }
                withClue("diagonal $t after every first-layer line it crosses") {
                    lines.filter { it.value[0][0] in (x0 - 0.01)..(x1 + 0.01) }.all { it.index < t } shouldBe true }
            }
            withClue("one X over the middle") { diag.count { (_, d) -> minOf(d.first()[0], d.last()[0]) < cx && maxOf(d.first()[0], d.last()[0]) > cx } shouldBe 2 }
            // together the Xs cover the span from the first line to the last: no gap along it wider than a line's spacing
            val lineX = lines.map { it.value[0][0] }.sorted()
            val spans = diag.map { (_, d) -> minOf(d.first()[0], d.last()[0]) to maxOf(d.first()[0], d.last()[0]) }.sortedBy { it.first }
            var reach = lineX.first()
            for ((a, b) in spans) { withClue("no gap before x $a") { (a <= reach + 0.01) shouldBe true }; reach = maxOf(reach, b) }
            withClue("the Xs reach the last line") { reach shouldBeGreaterThan lineX.last() - 0.01 }
        }
        withClue("x: the second layer gives way over the gap") { secondLayerOverGap(xG) shouldBe emptyMap() }
        // plan.technique.bridge.wave.start = "edge_strips" (waved strips 2-3 mm wide along each side of the bridge first, then the
        // gap closed), with crossings beside the strip: the first lines within
        // 1.5 mm of the strip's edges (the strip y 122.5..127.5 here: under 124 or over 126), then the gap closed by lines in the middle,
        // some running along the span; the check passes (a crossing beside the strip taken at the feet's height ran under the
        // strips and was refused: the travel now lifts over its whole route). Control: the whole span's first line crosses the middle
        /** Per object, its wave beads in the order laid, each as its (x, y) points. */
        fun wavesXY(g: String): Map<String, List<List<DoubleArray>>> {
            val out = LinkedHashMap<String, MutableList<MutableList<DoubleArray>>>()
            var obj: String? = null; var feature = ""; var x = 0.0; var y = 0.0; var cur: MutableList<DoubleArray>? = null
            for (l in g.lines()) {
                when {
                    l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                    l.startsWith("; object end") -> { cur = null; obj = null }
                    l.startsWith(";TYPE:") -> { feature = l.removePrefix(";TYPE:"); cur = null }
                    l.startsWith("G1") -> {
                        val nx = Regex(" X(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: x
                        val ny = Regex(" Y(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: y
                        val ext = Regex(" E([0-9.]+)").containsMatchIn(l) && (" X" in l || " Y" in l)
                        val o = obj
                        if (o != null && ext && feature == "Wave overhang") {
                            if (cur == null) { cur = arrayListOf(doubleArrayOf(x, y)); out.getOrPut(o) { ArrayList() } += cur }
                            cur += doubleArrayOf(nx, ny)
                        } else if (" X" in l || " Y" in l) cur = null
                        x = nx; y = ny
                    }
                }
            }
            return out
        }
        val stripsG = sliced("wave_overhang", "wave_strips.gcode", "alternate", edgesOn = false,
            extra = "wave.line.direction = \"zigzag\"\nwave.crossing = \"beside\"\nwave.crossing_clearance = \"6 mm\"\nwave.start = \"edge_strips\"\nwave.strip.width = \"1.5 mm\"")
        fun inMiddle(b: List<DoubleArray>) = b.minOf { it[1] } < 125.6 && b.maxOf { it[1] } > 124.4   // its y range reaches the middle band
        for ((name, bs) in wavesXY(stripsG)) withClue("edge strips: $name") {
            val firstMiddle = bs.indexOfFirst(::inMiddle)
            withClue("the gap is closed") { (firstMiddle > 0) shouldBe true }
            withClue("every line before the gap's first lies in an edge strip") {
                bs.take(firstMiddle).all { b -> b.all { Math.abs(it[1] - 125.0) > 1.0 - 0.3 } } shouldBe true }
            withClue("lines in the middle running along the span") { bs.count { b -> inMiddle(b) && b.maxOf { it[0] } - b.minOf { it[0] } > 5.0 } shouldBeGreaterThan 0 }
        }
        // plan.technique.bridge.wave.after_supported (the bridge's attachment to the feet): the strip over the
        // supports ("Bridge" lines on the waved layer) before the first wave line; control: unset, the wave lines first
        /** Per object: whether its waved layer's first "Bridge" line comes before its first wave line. */
        fun bridgeFirst(g: String): Map<String, Boolean> {
            val firstOf = HashMap<String, MutableMap<String, Int>>(); var obj: String? = null; var feature = ""; var n = 0; var lz = 0.0
            for (l in g.lines()) {
                n++
                when {
                    l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").toDouble()
                    l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                    l.startsWith("; object end") -> obj = null
                    l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                    l.startsWith("G1") && Regex(" E[0-9.]+").containsMatchIn(l) && lz > 6.1 -> obj?.let { o ->
                        if (feature == "Bridge" || feature == "Wave overhang") firstOf.getOrPut(o) { HashMap() }.putIfAbsent(feature, n) }
                }
            }
            return firstOf.mapValues { (_, m) -> m.getValue("Bridge") < m.getValue("Wave overhang") }
        }
        withClue("wave.after_supported: the strip over the supports first") {
            bridgeFirst(sliced("wave_overhang", "wave_after.gcode", "alternate", extra = "wave.after_supported = \"true\"")).values.all { it } shouldBe true }
        withClue("control: unset, the wave lines first") {
            bridgeFirst(sliced("wave_overhang", "wave_after_ctl.gcode", "alternate")).values.none { it } shouldBe true }
        withClue("control: the whole span's first line crosses the middle") {
            wavesXY(sliced("wave_overhang", "wave_strips_ctl.gcode", "alternate", edgesOn = false)).values.all { inMiddle(it.first()) } shouldBe true }
        withClue("control: with one waved layer the second is laid over the gap") {
            secondLayerOverGap(sliced("wave_overhang", "wave_alt_ctl.gcode", "alternate")).keys shouldBe setOf("wa", "wb") }
        // a middle-out chevron with its lines across (plan.technique.bridge.wave.chevron.leading = "middle", .lines = "across"):
        // every wave line at one x (parallel to the supports' edges, the strip along x, y 122.5..127.5); the
        // first lines centered on the strip (the middle pieces); after the first edge piece no middle piece; each edge piece from
        // the middle out (its start nearer y 125 than its end), even with wave.line.direction = zigzag reversing every other
        // front. Control: the same chevron along the fronts has lines running diagonally (several mm along x)
        val chevronAcross = "wave.front = \"chevron\"\nwave.chevron.leading = \"middle\"\nwave.line.direction = \"zigzag\""
        for ((name, bs) in wavesXY(sliced("wave_overhang", "wave_chev_across.gcode", "alternate", edgesOn = false,
                extra = chevronAcross + "\nwave.chevron.lines = \"across\""))) withClue("middle-out across: $name") {
            withClue("every line at one x") { bs.all { b -> b.maxOf { it[0] } - b.minOf { it[0] } < 0.01 } shouldBe true }
            fun centered(b: List<DoubleArray>) = Math.abs((b.minOf { it[1] } + b.maxOf { it[1] }) / 2 - 125.0) < 0.05 && b.minOf { it[1] } < 124.9
            withClue("the first line a middle piece") { centered(bs.first()) shouldBe true }
            val firstEdge = bs.indexOfFirst { !centered(it) }
            withClue("edge pieces after the middle ones") { (firstEdge > 0) shouldBe true }
            withClue("no middle piece after the first edge piece") { bs.drop(firstEdge).none(::centered) shouldBe true }
            withClue("each edge piece from the middle out") {
                bs.drop(firstEdge).all { b -> Math.abs(b.first()[1] - 125.0) < Math.abs(b.last()[1] - 125.0) } shouldBe true }
        }
        withClue("control: along the fronts, lines run diagonally") {
            wavesXY(sliced("wave_overhang", "wave_chev_diag.gcode", "alternate", edgesOn = false, extra = chevronAcross)).values
                .all { bs -> bs.any { b -> b.maxOf { it[0] } - b.minOf { it[0] } > 2.0 } } shouldBe true }
        // the head's rule in the order printed: the two-layer arch
        // lays its beads after the fill over the pillars, so its layer reaching 2.2 mm above the pillars (6.2 to the second
        // layer's peak 8.4) passes under a 2.1 mm nozzle height, refused before (every line judged against the whole layer);
        // control: one waved layer lays the fill over the pillars after its beads, reaching 2.0 mm (to 8.2): refused at 1.9
        sliced("wave_overhang", "wave_paired_low.gcode", "alternate", layers = "2", edgesOn = false, cv = listOf("hardware.tool.t1.head.nozzle.height=2.1 mm"))
        val low = shouldThrow<IllegalStateException> { sliced("wave_overhang", "wave_alt_low.gcode", "alternate", cv = listOf("hardware.tool.t1.head.nozzle.height=1.9 mm")) }
        low.message!! shouldContain "not under the nozzle height 1.900 mm"
        // the variations of plate 4's best arch, each on the alternating arch against it (alt, sliced above)
        /** The wave beads' start y relative to the strip (y 125) and every travel's widest y between beads, per object. */
        class Moves(val starts: Map<String, List<Double>>, val travelY: Map<String, Double>, val retracts: Map<String, Set<Double>>, val waits: Map<String, Int>, val wipes: Map<String, Int>)
        fun moves(g: String): Moves {
            val starts = HashMap<String, ArrayList<Double>>(); val far = HashMap<String, Double>(); val ret = HashMap<String, HashSet<Double>>()
            val waits = HashMap<String, Int>(); val wipes = HashMap<String, Int>()
            var obj: String? = null; var feature = ""; var y = 0.0; var lz = 0.0; var inBead = false; var afterBead = false
            for (l in g.lines()) {
                when {
                    l.startsWith(";Z:") -> lz = l.removePrefix(";Z:").toDouble()
                    l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                    l.startsWith("; object end") -> { obj = null; afterBead = false }
                    l.startsWith(";TYPE:") -> { feature = l.removePrefix(";TYPE:"); inBead = false; if (!feature.startsWith("Wave overhang")) afterBead = false }
                    l.startsWith("G4 P") -> obj?.let { o -> if (afterBead) waits.merge(o, 1, Int::plus) }
                    l.startsWith("G1") -> {
                        val o = obj ?: continue
                        val ny = Regex(" Y(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble() ?: y
                        val e = Regex(" E(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
                        val xy = " X" in l || " Y" in l
                        val wave = feature.startsWith("Wave overhang")
                        if (wave && e != null && e > 0 && xy) { if (!inBead) starts.getOrPut(o) { ArrayList() } += y - 125.0; inBead = true; afterBead = true }
                        else if (wave && afterBead && xy && e != null && e < 0) wipes.merge(o, 1, Int::plus)
                        else if (wave && afterBead && e == null && xy) { far.merge(o, Math.abs(ny - 125.0), ::maxOf); inBead = false }
                        if (wave && afterBead && e != null && e < 0 && !xy) ret.getOrPut(o) { HashSet() } += -e
                        y = ny
                    }
                }
            }
            return Moves(starts, far, ret, waits, wipes)
        }
        val altM = moves(Files.readString(d.resolve("wave_alt.gcode")))
        // bead direction: mirrored (unset), the sides start from opposite edges; matched, every bead from one edge; zigzag, by front
        // (edge pieces off: they run along the span and start at both edges whatever the beads across do)
        val mirrored = moves(sliced("wave_overhang", "wave_mirrored.gcode", "alternate", edgesOn = false))
        withClue("control: mirrored, both edges used") { mirrored.starts.getValue("wa").let { st -> st.any { it > 0 } && st.any { it < 0 } } shouldBe true }
        val matched = moves(sliced("wave_overhang", "wave_matched.gcode", "alternate", edgesOn = false, extra = "wave.line.direction = \"matched\""))
        for (o in listOf("wa", "wb")) withClue("matched $o") { matched.starts.getValue(o).map { it > 0 }.distinct().size shouldBe 1 }
        val zig = moves(sliced("wave_overhang", "wave_zigzag.gcode", "alternate", edgesOn = false, extra = "wave.line.direction = \"zigzag\""))
        withClue("zigzag: both edges, changing from front to front (each front: one bead per side)") {
            val st = zig.starts.getValue("wa").filterIndexed { k, _ -> k % 2 == 0 }.map { it > 0 }
            st.zipWithNext().count { (a, b) -> a != b } shouldBeGreaterThan st.size / 2 }
        // crossing beside: the travels between beads reach past the strip's edge (half its width 2.5 + the bundled 3 mm)
        withClue("control: crossing over stays within the strip") { (altM.travelY.getValue("wa") < 2.6) shouldBe true }
        val beside = moves(sliced("wave_overhang", "wave_beside.gcode", "alternate", extra = "wave.crossing = \"beside\""))
        withClue("beside: out past the edge") { (beside.travelY.getValue("wa") > 5.0) shouldBe true }
        // the crossings' own retraction and wipe; control: the filament's retraction, no wipe
        val ret = moves(sliced("wave_overhang", "wave_retract.gcode", "alternate", extra = "wave.retract = \"1.7 mm\""))
        withClue("wave.retract 1.7 mm") { ret.retracts.getValue("wa") shouldBe setOf(1.7) }
        withClue("control: the filament's retraction") { (1.7 in altM.retracts.getValue("wa")) shouldBe false }
        val wipe = moves(sliced("wave_overhang", "wave_wipe.gcode", "alternate", extra = "wave.wipe = \"true\""))
        withClue("wave.wipe: wiping moves after beads") { (wipe.wipes["wa"] ?: 0) shouldBeGreaterThan 10 }
        withClue("control: no wipe") { (altM.wipes["wa"] ?: 0) shouldBe 0 }
        // a wait before each bead but the first
        val wait = moves(sliced("wave_overhang", "wave_wait.gcode", "alternate", extra = "wave.wait = \"3 s\""))
        withClue("wave.wait: a wait before each bead but the first") { wait.waits.getValue("wa") shouldBe wait.starts.getValue("wa").size - 1 }
        withClue("control: no wait") { (altM.waits["wa"] ?: 0) shouldBe 0 }
        // lines evenly spaced along the surface (plan.technique.bridge.wave.spacing = "even"): an odd number
        // of lines across (x the same at both ends), each as far from the next along the laid surface (x, z), the last laid the
        // middle one, running the other way to the two before it (the sides' last, zigzag), the edge pieces after it. Control:
        // as grown, spaced as seen from above, so along the surface wider on the slopes, and the two sides' last apart
        val evenExtra = "wave.line.direction = \"zigzag\"\nwave.spacing = \"even\""
        val evenG = sliced("wave_overhang", "wave_even.gcode", "alternate", extra = evenExtra)
        fun acrossXY(b: List<DoubleArray>) = Math.abs(b.last()[0] - b.first()[0]) < 0.05
        fun surfaceGaps(bs: List<List<DoubleArray>>) = bs.filter(::across).map { it.first() }.sortedBy { it[0] }.zipWithNext().map { (a, b) -> Math.hypot(b[0] - a[0], b[1] - a[1]) }
        val evenXY = wavesXY(evenG)
        for ((name, bs) in read(evenG).first) withClue("even: $name") {
            val cx = if (name == "wa") 80.0 else 170.0
            withClue("an odd number of lines") { bs.count(::across) % 2 shouldBe 1 }
            val gaps = surfaceGaps(bs)
            withClue("equal along the surface: ${gaps.min()}..${gaps.max()}") { (gaps.max() - gaps.min() < 0.005) shouldBe true }
            val xy = evenXY.getValue(name).filter(::acrossXY)
            val last = xy.last()
            withClue("the last line at the middle") { (Math.abs(last[0][0] - cx) < 0.01) shouldBe true }
            withClue("the last against the two before it") { xy.dropLast(1).takeLast(2).all { b -> (b.last()[1] - b.first()[1]) * (last.last()[1] - last.first()[1]) < 0 } shouldBe true }
            withClue("the edge pieces after it") { bs.takeLastWhile { !across(it) }.size shouldBeGreaterThan 0 }
        }
        withClue("control: as grown, unequal along the surface") {
            read(Files.readString(d.resolve("wave_alt.gcode"))).first.values.all { bs -> surfaceGaps(bs).let { it.max() - it.min() } > 0.02 } shouldBe true }
        // the head away between beads across the span (wave.crossing = "away", wave.away.distance), with waits
        // by zone (wave.wait_zones): the travels between beads reach the distance from the object (10 mm: the pillars' half
        // width 5 + 10 = 15 mm off the strip's center line in y), each wait between two beads across the zone's of the first
        // (2 s over the first 40 % of the gap from the -X pillar, 4 s over 40-60 %, none after; none before an edge piece).
        // Control: crossing over (wave_alt above) stays within the strip; no waits unset
        val awayG = sliced("wave_overhang", "wave_away.gcode",
            extra = "wave.line.direction = \"matched\"\nwave.crossing = \"away\"\nwave.away.distance = \"10 mm\"\nwave.wait_zones = \"0-40%: 2 s, 40-60%: 4 s, 60-100%: 0 s\"")
        withClue("away: out to the distance") { (moves(awayG).travelY.getValue("wa") > 14.9) shouldBe true }
        fun g4(g: String): Map<String, List<Long>> {
            val out = HashMap<String, MutableList<Long>>(); var obj: String? = null
            for (l in g.lines()) when {
                l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                l.startsWith("; object end") -> obj = null
                l.startsWith("G4 P") -> obj?.let { out.getOrPut(it) { ArrayList() } += l.removePrefix("G4 P").trim().toLong() }
            }
            return out
        }
        val awayXY = wavesXY(awayG); val awayG4 = g4(awayG)
        for (o in listOf("wa", "wb")) withClue("away waits: $o") {
            val cx = if (o == "wa") 80.0 else 170.0
            val bs = awayXY.getValue(o)
            val want = (0 until bs.size - 1).filter { acrossXY(bs[it]) && acrossXY(bs[it + 1]) }
                .map { k -> val f = (bs[k][0][0] - (cx - 10)) / 20; if (f < 0.4) 2000L else if (f < 0.6) 4000L else 0L }.filter { it > 0 }
            (want.size > 10) shouldBe true
            (awayG4[o] ?: emptyList()).sorted() shouldBe want.sorted()
        }
        withClue("control: no waits unset") { g4(Files.readString(d.resolve("wave_arch.gcode"))).values.flatten().isEmpty() shouldBe true }
        // a second pass over the middle of the span (wave.second_pass.zones, .speed, .approach): the lines
        // across within 25-75 % of the gap laid again after the layer's other lines, labeled so, each over a first-pass line
        // (the same x), the other way to it, 0.1 mm above it over 25-40 / 60-75 % and at its height over 40-60 %, with half and
        // the whole of its plastic per mm, at 20 mm/s, each reached straight down from at least 2 mm above; the check passes
        // (it takes them on their layer up to a layer above). Control: none without the zones
        class Ex(val feature: String, val x0: Double, val y0: Double, val z0: Double, val x1: Double, val y1: Double, val z1: Double, val e: Double, val f: Double, val approach: DoubleArray?)
        fun extrusions(g: String): Map<String, List<Ex>> {
            val out = LinkedHashMap<String, MutableList<Ex>>(); var obj: String? = null; var feature = ""
            var x = 0.0; var y = 0.0; var z = 0.0; var f = 0.0; var pending: DoubleArray? = null   // the last move with no extrusion (dx dy dz) since a line
            for (l in g.lines()) when {
                l.startsWith("; object start ") -> obj = l.removePrefix("; object start ").substringBefore("_id_")
                l.startsWith("; object end") -> obj = null
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:")
                l.startsWith("G1") -> {
                    fun v(c: Char) = Regex(" $c(-?[0-9.]+)").find(l)?.groupValues?.get(1)?.toDouble()
                    val nx = v('X') ?: x; val ny = v('Y') ?: y; val nz = v('Z') ?: z; v('F')?.let { f = it }
                    val e = v('E')
                    if (e != null && e > 0 && (nx != x || ny != y)) { obj?.let { out.getOrPut(it) { ArrayList() } += Ex(feature, x, y, z, nx, ny, nz, e, f, pending) }; pending = null }
                    else if (nx != x || ny != y || nz != z) pending = doubleArrayOf(nx - x, ny - y, nz - z)
                    x = nx; y = ny; z = nz
                }
            }
            return out
        }
        val passG = sliced("wave_overhang", "wave_second.gcode", "alternate", extra = evenExtra +
            "\nwave.second_pass.zones = \"25-40%: 0.1 mm 50%, 40-60%: 0 mm 100%, 60-75%: 0.1 mm 50%\"\nwave.second_pass.speed = \"20 mm/s\"\nwave.second_pass.approach = \"2 mm\"")
        for ((o, es) in extrusions(passG)) withClue("second pass: $o") {
            val cx = if (o == "wa") 80.0 else 170.0
            fun share(x: Double) = (x - (cx - 10)) / 20
            val firsts = es.filter { it.feature == "Wave overhang" && Math.abs(it.x1 - it.x0) < 0.05 }
            val seconds = es.filter { it.feature == "Wave overhang, second pass" }
            withClue("the lines within 25-75 %") { seconds.size shouldBe firsts.count { share(it.x0) >= 0.25 && share(it.x0) < 0.75 } }
            (seconds.size > 20) shouldBe true
            for (s in seconds) {
                val u = firsts.minBy { Math.abs(it.x0 - s.x0) }
                val outer = share(s.x0) < 0.4 || share(s.x0) >= 0.6
                withClue("over a first-pass line at x ${s.x0}") { (Math.abs(u.x0 - s.x0) < 0.002) shouldBe true }
                withClue("the other way to it") { ((s.y1 - s.y0) * (u.y1 - u.y0) < 0) shouldBe true }
                withClue("its height over it") { (Math.abs((s.z0 + s.z1) / 2 - (u.z0 + u.z1) / 2 - (if (outer) 0.1 else 0.0)) < 0.003) shouldBe true }
                withClue("its plastic per mm") { (Math.abs(s.e / Math.hypot(s.x1 - s.x0, s.y1 - s.y0) / (u.e / Math.hypot(u.x1 - u.x0, u.y1 - u.y0)) - (if (outer) 0.5 else 1.0)) < 0.01) shouldBe true }
                withClue("at 20 mm/s") { s.f shouldBe 1200.0 }
                withClue("reached straight down from 2 mm") { s.approach!!.let { a -> a[0] == 0.0 && a[1] == 0.0 && a[2] <= -1.999 } shouldBe true }
            }
            val first = es.indexOfFirst { it.feature == "Wave overhang, second pass" }
            withClue("after the layer's other lines") { (es.indexOfLast { it.feature == "Wave overhang" } < first && es.drop(first).take(seconds.size).all { it.feature == "Wave overhang, second pass" }) shouldBe true }
        }
        withClue("control: none without the zones") { extrusions(evenG).values.flatten().none { it.feature == "Wave overhang, second pass" } shouldBe true }
        // Xs over the waved layer, then a layer along the span over them (wave.x_layer, wave.span_layer: a layer of
        // Xs after layer 1, the sides in turn from the feet to the center, then lines spanning the bridge over them): after
        // every line of the layer, the Xs, each 2.1 mm of the span
        // corner to corner, their ends 0.4 mm inside the strip's edges (y 125 +- 2.1), 0.1 mm over the waved layer at half its
        // plastic per mm and 20 mm/s, the sides in turn from the pillars, the last over the middle; then the span layer: lines
        // along X from pillar to pillar, equally spaced within the strip, each the other way to the one before, 0.3 mm over
        // the waved layer, at 40 mm/s; the check passes (it takes them up to one and two layers above). Control: none unset
        val xsG = sliced("wave_overhang", "wave_xs.gcode", "alternate", extra = evenExtra +
            "\nwave.x_length = \"2.1 mm\"\nwave.x_layer.enabled = \"true\"\nwave.x_layer.raise = \"0.1 mm\"\nwave.x_layer.share = \"50%\"" +
            "\nwave.x_layer.inset = \"0.4 mm\"\nwave.x_layer.speed = \"20 mm/s\"" +
            "\nwave.span_layer.enabled = \"true\"\nwave.span_layer.raise = \"0.3 mm\"\nwave.span_layer.speed = \"40 mm/s\"")
        for ((o, es) in extrusions(xsG)) withClue("Xs and span layer: $o") {
            val cx = if (o == "wa") 80.0 else 170.0
            val firsts = es.filter { it.feature == "Wave overhang" && Math.abs(it.x1 - it.x0) < 0.05 }.sortedBy { it.x0 }
            // the waved layer's height at x, between the two lines across either side of it
            fun under(x: Double): Double {
                val k = firsts.indexOfFirst { it.x0 >= x }.let { if (it <= 0) 1 else it }
                val a = firsts[k - 1]; val b = firsts[k]
                val za = (a.z0 + a.z1) / 2; val zb = (b.z0 + b.z1) / 2
                return za + (zb - za) * (x - a.x0) / (b.x0 - a.x0)
            }
            fun perMm(e: Ex) = e.e / Math.hypot(e.x1 - e.x0, e.y1 - e.y0)
            val waveE = firsts.map(::perMm).average()
            val xs = es.filter { it.feature == "Wave overhang, X layer" }
            val spans = es.filter { it.feature == "Wave overhang, span layer" }
            // each run of pieces, one continuing the last: a diagonal, a span line
            fun runs(ps: List<Ex>) = ps.fold(ArrayList<ArrayList<Ex>>()) { acc, p ->
                if (acc.isEmpty() || Math.abs(acc.last().last().x1 - p.x0) > 1e-3 || Math.abs(acc.last().last().y1 - p.y0) > 1e-3) acc += arrayListOf(p) else acc.last() += p; acc }
            val diags = runs(xs)
            withClue("whole Xs, at least three") { (diags.size % 2 == 0 && diags.size >= 6) shouldBe true }
            withClue("after the layer's lines") { (es.indexOfLast { it.feature == "Wave overhang" } < es.indexOfFirst { it.feature == "Wave overhang, X layer" }) shouldBe true }
            for (dg in diags) {
                withClue("corner to corner, 0.4 mm inside the edges: ${dg.first().y0} -> ${dg.last().y1}") {
                    (Math.abs(Math.abs(dg.first().y0 - 125.0) - 2.1) < 0.005 && Math.abs(Math.abs(dg.last().y1 - 125.0) - 2.1) < 0.005 &&
                        (dg.first().y0 - 125.0) * (dg.last().y1 - 125.0) < 0) shouldBe true }
            }
            // each X but the middle one 2.1 mm along the span (six lines at the even spacing); the middle one takes what is left
            // of both sides, at least half a section on each
            for (dg in diags.dropLast(2)) withClue("about 2.1 mm along the span: ${dg.last().x1 - dg.first().x0}") { (Math.abs(dg.last().x1 - dg.first().x0) in 1.9..2.6) shouldBe true }
            withClue("the middle X: ${diags.last().last().x1 - diags.last().first().x0}") { (Math.abs(diags.last().last().x1 - diags.last().first().x0) in 1.9..5.0) shouldBe true }
            for (p in xs) {
                withClue("0.1 mm over the waved layer at x ${p.x1}") { (Math.abs(p.z1 - under(p.x1) - 0.1) < 0.01) shouldBe true }
                withClue("half its plastic") { (Math.abs(perMm(p) / waveE - 0.5) < 0.01) shouldBe true }
                withClue("at 20 mm/s") { p.f shouldBe 1200.0 }
            }
            // the Xs' middles: the two sides in turn from the pillars inward, the last over the middle
            val mids = diags.chunked(2).map { (it[0].first().x0 + it[0].last().x1) / 2 - cx }
            withClue("sides in turn from the pillars: $mids") {
                (mids.dropLast(1).zipWithNext().all { (p, q) -> p * q < 0 } && Math.abs(mids.last()) < 1.5 &&
                    mids.dropLast(1).filter { it < 0 }.zipWithNext().all { (p, q) -> q > p } && mids.dropLast(1).filter { it > 0 }.zipWithNext().all { (p, q) -> q < p } &&
                    Math.abs(mids.first()) > 7.0) shouldBe true }
            val lines = runs(spans)
            withClue("after the Xs") { (es.indexOfLast { it.feature == "Wave overhang, X layer" } < es.indexOfFirst { it.feature == "Wave overhang, span layer" }) shouldBe true }
            withClue("lines along the span, pillar to pillar") {
                lines.all { l -> Math.abs(minOf(l.first().x0, l.last().x1) - (cx - 10)) < 0.02 && Math.abs(maxOf(l.first().x0, l.last().x1) - (cx + 10)) < 0.02 && l.all { Math.abs(it.y1 - it.y0) < 1e-3 } } shouldBe true }
            withClue("each the other way to the one before") { lines.zipWithNext().all { (a, b) -> (a.last().x1 - a.first().x0) * (b.last().x1 - b.first().x0) < 0 } shouldBe true }
            val ys = lines.map { it.first().y0 }.sorted()
            val gaps = ys.zipWithNext { a, b -> b - a }
            withClue("equally spaced within the strip: $ys") { (lines.size >= 5 && gaps.max() - gaps.min() < 0.003 && ys.first() > 122.5 && ys.last() < 127.5 && Math.abs(ys.first() + ys.last() - 250.0) < 0.003) shouldBe true }
            for (p in spans) {
                if (Math.abs(p.x1 - cx) < 9.5) withClue("0.3 mm over the waved layer at x ${p.x1}") { (Math.abs(p.z1 - under(p.x1) - 0.3) < 0.01) shouldBe true }
                withClue("at 40 mm/s") { p.f shouldBe 2400.0 }
            }
        }
        // the middle X first (wave.x_layer.middle = "first"): the first X over the middle, the rest the sides in turn from the pillars
        // inward; the same Xs as with it last (above: the middle last, the control)
        val firstG = sliced("wave_overhang", "wave_xs_first.gcode", "alternate", extra = evenExtra +
            "\nwave.x_length = \"2.1 mm\"\nwave.x_layer.enabled = \"true\"\nwave.x_layer.raise = \"0.1 mm\"\nwave.x_layer.share = \"50%\"" +
            "\nwave.x_layer.inset = \"0.4 mm\"\nwave.x_layer.speed = \"20 mm/s\"\nwave.x_layer.middle = \"first\"")
        fun xMids(g: String) = extrusions(g).mapValues { (o, es) ->
            val cx = if (o == "wa") 80.0 else 170.0
            val xs = es.filter { it.feature == "Wave overhang, X layer" }
            val diags = xs.fold(ArrayList<ArrayList<Ex>>()) { acc, p ->
                if (acc.isEmpty() || Math.abs(acc.last().last().x1 - p.x0) > 1e-3 || Math.abs(acc.last().last().y1 - p.y0) > 1e-3) acc += arrayListOf(p) else acc.last() += p; acc }
            diags.chunked(2).map { (it[0].first().x0 + it[0].last().x1) / 2 - cx }
        }
        val lastMids = xMids(xsG); val firstMids = xMids(firstG)
        for ((o, mids) in firstMids) withClue("middle X first: $o $mids") {
            (Math.abs(mids.first()) < 1.5 && mids.drop(1).zipWithNext().all { (p, q) -> p * q < 0 } && Math.abs(mids[1]) > 7.0 &&
                mids.drop(1).filter { it < 0 }.zipWithNext().all { (p, q) -> q > p } && mids.drop(1).filter { it > 0 }.zipWithNext().all { (p, q) -> q < p }) shouldBe true
            withClue("the same Xs as with it last") { mids.map { Math.round(it * 1000) }.sorted() shouldBe lastMids.getValue(o).map { Math.round(it * 1000) }.sorted() }
            withClue("control: last there") { (Math.abs(lastMids.getValue(o).first()) > 7.0 && Math.abs(lastMids.getValue(o).last()) < 1.5) shouldBe true }
        }
        // the head's rule within a block of lines: the middle X, laid first after the layer's lines on the pillars,
        // stands 2.1 mm over those and 2.0 mm over the side Xs' starts at the pillars laid after it: under a 2.05 mm nozzle
        // height it passes (counting the whole block refused plate 14's two 40 mm arches for lines laid before the middle
        // X); control: at 1.95 mm it is refused (the side Xs' starts, laid after it)
        val firstExtra = evenExtra + "\nwave.x_length = \"2.1 mm\"\nwave.x_layer.enabled = \"true\"\nwave.x_layer.raise = \"0.1 mm\"\nwave.x_layer.share = \"50%\"" +
            "\nwave.x_layer.inset = \"0.4 mm\"\nwave.x_layer.speed = \"20 mm/s\"\nwave.x_layer.middle = \"first\""
        sliced("wave_overhang", "wave_xs_first_205.gcode", "alternate", extra = firstExtra, cv = listOf("hardware.tool.t1.head.nozzle.height=2.05 mm"))
        val firstLow = shouldThrow<IllegalStateException> { sliced("wave_overhang", "wave_xs_first_195.gcode", "alternate", extra = firstExtra, cv = listOf("hardware.tool.t1.head.nozzle.height=1.95 mm")) }
        firstLow.message!! shouldContain "not under the nozzle height 1.950 mm"
        withClue("control: no Xs or span layer unset") { extrusions(evenG).values.flatten().none { it.feature == "Wave overhang, X layer" || it.feature == "Wave overhang, span layer" } shouldBe true }
        // the zones' text: a malformed entry is refused with the setting's name
        shouldThrow<IllegalArgumentException> { plymeld.model.SliceSettings.parseWaitZones("0-40: five") }.message!! shouldContain "wave.wait_zones"
        shouldThrow<IllegalArgumentException> { plymeld.model.SliceSettings.parseSecondPass("25-40%: 0.1 mm") }.message!! shouldContain "wave.second_pass.zones"
        // control: lines, no wave beads
        val ctl = read(sliced("lines", "lines_arch.gcode")).first
        ctl.isEmpty() shouldBe true
        // a floor joining the pillars under the span, 1 mm off the bed (feet joined so they cannot move apart
        // once off the bed): what holds a lifted bridge up is what reaches its underside, so the floor, 5 mm under the strip, is
        // no anchor: the same wave beads as without it, and the even rows find their two supports (counting everything
        // printed under the bridge as an anchor would make the floor one support of the pillars and the span, and the slice
        // would be refused). The arches rebuilt with the floor, so last
        fun archFloor(path: Path, name: String, cx: Double) = threeMf(path, name, listOf(
            "supports" to moved(box(-18.0, -5.0, 0.0, -10.0, 5.0, 6.0) + box(10.0, -5.0, 0.0, 18.0, 5.0, 6.0) + box(-10.0, -2.5, 0.0, 10.0, 2.5, 1.0), cx, 125.0),
            "strip" to moved(strip(listOf(-18.0 to 6.0) + arc + listOf(18.0 to 6.0)), cx, 125.0)))
        archFloor(d.resolve("wa.3mf"), "wa", 80.0)
        archFloor(d.resolve("wb.3mf"), "wb", 170.0)
        val floorBeads = read(sliced("wave_overhang", "wave_arch_floor.gcode")).first
        for (o in listOf("wa", "wb")) withClue("floor, as grown: $o") { floorBeads.getValue(o).size shouldBe beads.getValue(o).size }
        val floorEven = read(sliced("wave_overhang", "wave_even_floor.gcode", "alternate", extra = evenExtra)).first
        for (o in listOf("wa", "wb")) withClue("floor, even: $o") { floorEven.getValue(o).count(::across) shouldBe read(evenG).first.getValue(o).count(::across) }
    }
    // one at a time by the head's outline from the config (hardware.tool.t1.head.size / head.nozzle.x / .y above: 70 mm towards
    // -X, 20 towards +X). Earlier, the object order got the bundled description only, so the configured outline never
    // reached it and two objects 30 mm apart were refused against the C5's 92 mm clearance radius
    "one at a time, placement as given, spaced by the configured head outline: the right object first passes 30 mm apart; control: the left first is refused by the outline" {
        threeMf(d.resolve("oa.3mf"), "oa", listOf("body" to box(90.0, 115.0, 0.0, 110.0, 135.0, 10.0)))
        threeMf(d.resolve("ob.3mf"), "ob", listOf("body" to box(140.0, 115.0, 0.0, 160.0, 135.0, 10.0)))
        Files.writeString(d.resolve("order.toml"), "[plan]\npose = \"as-given\"\nplacement = \"as-given\"\nobjects.printing = \"one_at_a_time\"\n")
        fun slice(first: String, second: String, out: String) = SliceCommand().parse(listOf("-cf", d.resolve("hw.toml").toString(), "-cf", d.resolve("order.toml").toString(),
            "-m", d.resolve("$first.3mf").toString(), "-m", d.resolve("$second.3mf").toString(), "-o", d.resolve(out).toString()))
        slice("ob", "oa", "order_right_first.gcode")
        val g = Files.readString(d.resolve("order_right_first.gcode"))
        g.indexOf("; object start ob") shouldBeLessThan g.indexOf("; object start oa")
        val e = shouldThrow<Exception> { slice("oa", "ob", "order_left_first.gcode") }
        e.message!! shouldContain "would reach"
    }
})
