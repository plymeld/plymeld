package plymeld.check

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.core.check.DoubledCheck
import plymeld.core.check.ModelCheck
import java.util.Locale

/**
 * The pre-print check's rule for plastic laid twice (ModelCheck, DoubledCheck) on a hand-written print of a 10 mm box, two
 * layers 0.2 mm thick, three wall loops 0.44 mm wide 0.40 mm apart (bead contact: 0.44 - 0.2 (1 - pi/4)), and sparse infill
 * lines inside them whose ends reach 0.16 mm into the innermost loop (bead contact and a quarter of their width,
 * plan.layer.infill.wall_overlap = 25 %), read with a slicing tolerance of 0.01 mm. The rule finds and measures (the
 * check's laid-twice measure, DoubledCheck.Summary); while DoubledCheck.REFUSES is off it refuses nothing:
 *  - as written it finds nothing (neighboring loops touch, the infill's ends reach into the walls as planned);
 *  - control: the middle loop laid again on the second layer, as a run of its own: found, an inner wall over an inner wall;
 *  - control: an inner wall line laid along an infill line on the second layer: found where it is, an inner wall over
 *    sparse infill;
 *  - control: the infill's ends laid 0.40 mm further in, through the innermost loop: found, sparse infill over an inner
 *    wall; 0.18 mm further in is not found with the planned reach given and is without it (the allowance's control);
 *  - a line's own turns and a loop's end over its start (one run) are not judged: a loop laid with its last 2 mm laid again
 *    without stopping is not found (the scarf seam's way);
 *  - the switch: with REFUSES off, a file with a spot laid twice passes the check, the spot in its measure; the measure's
 *    words name the spots, their area, the layers, and the widest;
 *  - a layer laid twice over more than the rule keeps (two solid fills over the whole box, over 62.5 mm2): its reading is
 *    marked cut short, and the words say so.
 * And the catalog's inlay on a leaning face, its plate's first layer as an earlier version wrote it (a fixture: the walls'
 * center lines, up to 0.9 mm wide, laid across the infill, DECISIONS 45): found at that layer; as written now, not there.
 */
class DoubledCheckTest : StringSpec({
    val model = listOf(testBox(100f, 100f, 0f, 110f, 110f, 0.4f))
    val start = "M83\nG28\nM109 S210"
    fun f(v: Double) = String.format(Locale.ROOT, "%.3f", v)

    /** A run through [pts] (x, y pairs) after a travel, laid with E per mm for a 0.44 mm line on 0.2 mm. */
    fun path(feature: String, pts: List<DoubleArray>): String {
        val sb = StringBuilder("G1 E-0.8 F2400\nG1 X${f(pts[0][0])} Y${f(pts[0][1])} F18000\nG1 E0.8 F2400\n; FEATURE: $feature\n; LINE_WIDTH: 0.44\nG1 F3000\n")
        for (k in 1 until pts.size) sb.append("G1 X${f(pts[k][0])} Y${f(pts[k][1])} E${f(0.033 * Math.hypot(pts[k][0] - pts[k - 1][0], pts[k][1] - pts[k - 1][1]))}\n")
        return sb.toString()
    }
    fun square(inset: Double): List<DoubleArray> {
        val a = 100 + inset; val b = 110 - inset
        return listOf(doubleArrayOf(a, a), doubleArrayOf(b, a), doubleArrayOf(b, b), doubleArrayOf(a, b), doubleArrayOf(a, a))
    }
    /** One layer; [reach]: how far the infill's ends lie past the innermost loop's center line toward it (mm). */
    fun layer(z: Double, extra: String = "", reach: Double = 0.0, loopAgain: Boolean = false): String {
        val sb = StringBuilder("; CHANGE_LAYER\n; Z_HEIGHT: $z\n; LAYER_HEIGHT: 0.2\nG1 Z$z F600\n")
        sb.append(path("Outer wall", square(0.22)))
        // the middle loop; [loopAgain]: its first 2 mm laid again at its end, without stopping
        sb.append(path("Inner wall", square(0.62).let { if (loopAgain) it + listOf(doubleArrayOf(101.62, 100.62), doubleArrayOf(102.62, 100.62)) else it }))
        sb.append(path("Inner wall", square(1.02)))
        var y = 101.6
        while (y < 108.5) { sb.append(path("Sparse infill", listOf(doubleArrayOf(101.30 - reach, y), doubleArrayOf(108.70 + reach, y)))); y += 1.2 }
        sb.append(extra)
        return sb.toString()
    }
    fun file(second: String) = "$start\nG1 X5 Y5 Z0.3 F6000\nG1 X50 Y5 E5\n" + layer(0.2) + second + "G1 E-0.8 F2400\nG1 Z2 F600\nG1 X150 Y150 F6000\nM104 S0\n"
    fun check(text: String, endOverlap: Double = 0.25) = ModelCheck.checkAll(text, model, 0.0, featureMinMm = 0.17, slicingTolMm = 0.01, endOverlap = endOverlap)
    /** The spots the rule found, each with its layer's height. */
    fun found(r: ModelCheck.Result) = r.laidTwice.layers.flatMap { l -> l.reading.spots.map { l.z to it } }
    fun refusedTwice(r: ModelCheck.Result) = r.problems.filter { it.contains("plastic laid twice") }

    "three loops and sparse infill reaching into them as planned: nothing found, nothing refused" {
        val r = check(file(layer(0.4)))
        r.problems.shouldBeEmpty()
        r.laidTwice.layers.shouldBeEmpty()
        r.laidTwice.words().startsWith("no spot") shouldBe true
    }
    "control: a loop laid again as a run of its own is found, an inner wall over an inner wall" {
        val s = found(check(file(layer(0.4, extra = path("Inner wall", square(0.62))))))
        println("loop again: ${s.map { (z, p) -> "z $z ${p.lines()} ${p.widthMm} mm ${p.areaMm2} mm2" }}")
        s.size shouldBe 1
        (Math.abs(s[0].first - 0.4) < 1e-9) shouldBe true
        s[0].second.lines() shouldBe "a line of inner wall laid over a line of inner wall"
    }
    "control: a wall line laid along an infill line is found where it is, an inner wall over sparse infill" {
        val s = found(check(file(layer(0.4, extra = path("Inner wall", listOf(doubleArrayOf(103.0, 105.2), doubleArrayOf(107.0, 105.2)))))))
        println("wall over infill: ${s.map { (z, p) -> "z $z ${p.lines()} at ${p.x}, ${p.y}" }}")
        s.size shouldBe 1
        s[0].second.lines() shouldBe "a line of inner wall laid over a line of sparse infill"
        (s[0].second.x in 102.8..107.2 && s[0].second.y in 105.0..105.4) shouldBe true
    }
    "control: infill ends laid through the innermost loop are found; ends 0.18 mm further in are not with the planned reach given, and are without it" {
        val through = found(check(file(layer(0.4, reach = 0.40))))
        println("through: ${through.take(1).map { it.second.lines() }}")
        through.isNotEmpty() shouldBe true
        through[0].second.lines() shouldBe "a line of sparse infill laid over a line of inner wall"
        val further = file(layer(0.4, reach = 0.18))
        found(check(further)).shouldBeEmpty()
        val unplanned = found(check(further, endOverlap = 0.0))
        println("no reach given: ${unplanned.size} spot(s)")
        unplanned.isNotEmpty() shouldBe true
    }
    "one run over itself is not judged: a loop's first 2 mm laid again at its end without stopping is not found" {
        val r = check(file(layer(0.4, loopAgain = true)))
        r.problems.shouldBeEmpty()
        found(r).shouldBeEmpty()
    }
    "the switch: the rule reports and does not refuse (DoubledCheck.REFUSES off); a spot laid twice is in the measure, not among the problems" {
        // flipping REFUSES to true (when the catalog, the regression cases and the slow suite's models read zero spots)
        // changes this test: the same file is then refused at z 0.400 for the same spot
        DoubledCheck.REFUSES shouldBe false
        val r = check(file(layer(0.4, extra = path("Inner wall", square(0.62)))))
        refusedTwice(r).shouldBeEmpty()
        r.problems.shouldBeEmpty()
        r.laidTwice.spots shouldBe 1
        r.laidTwice.spotLayers shouldBe 1
        r.laidTwice.cappedLayers shouldBe 0
        val words = r.laidTwice.words()
        println("laid twice: $words")
        words.startsWith("1 spot(s), ") shouldBe true
        words.contains("on 1 layer(s); the widest ") shouldBe true
        words.contains("at z 0.400, ") shouldBe true
        words.contains("a line of inner wall laid over a line of inner wall") shouldBe true
        words.contains("stopped") shouldBe false
    }
    "a layer laid twice over more than the rule keeps is marked cut short, and its words say so" {
        // a second layer of two solid fills over the whole box and nothing else, each one run (a zigzag 0.4 mm apart): laid
        // twice over about 80 mm2, past the 100,000 cells (62.5 mm2) the rule keeps a layer
        val zig = ArrayList<DoubleArray>()
        var y = 100.3; var right = true
        while (y < 109.75) { if (right) { zig += doubleArrayOf(100.3, y); zig += doubleArrayOf(109.7, y) } else { zig += doubleArrayOf(109.7, y); zig += doubleArrayOf(100.3, y) }; right = !right; y += 0.4 }
        fun fills(n: Int) = "; CHANGE_LAYER\n; Z_HEIGHT: 0.4\n; LAYER_HEIGHT: 0.2\nG1 Z0.4 F600\n" + (1..n).joinToString("") { path("Internal solid infill", zig) }
        val r = check(file(fills(2)))
        val l = r.laidTwice.layers.single { Math.abs(it.z - 0.4) < 1e-9 }
        l.reading.capped shouldBe true
        r.laidTwice.cappedLayers shouldBe 1
        val words = r.laidTwice.words()
        println("two fills: $words")
        words.contains("on 1 layer(s) the reading stopped at 100,000 cells laid twice, so more lies twice there than these count") shouldBe true
        // control: one fill alone (one run, not judged over itself) is not cut short
        check(file(fills(1))).laidTwice.cappedLayers shouldBe 0
    }
    "the inlay's walls laid across its plate's infill (the file an earlier version wrote, its one layer) are found; as written now, that layer has none" {
        val e = plymeld.check.catalog.Catalog.byName("inlay on a leaning face")
        val w = plymeld.check.catalog.Catalog.write(e)
        val old = DoubledCheckTest::class.java.getResource("/plymeld/check/inlay-walls-over-infill.gcode")!!.readText()
        val r = plymeld.check.catalog.Slicing.checked(w, old)
        val l = r.laidTwice.layers.filter { it.reading.spots.isNotEmpty() }
        println("inlay, old walls: ${l.map { "z ${it.z}: ${it.reading.spots.size} spots, ${it.reading.spots.sumOf { s -> s.areaMm2 }} mm2, widest ${it.reading.spots[0].lines()}" }}")
        l.size shouldBe 1
        (Math.abs(l[0].z - 0.6) < 1e-6) shouldBe true
        l[0].reading.spots[0].lines().startsWith("a line of inner wall laid over") shouldBe true
        refusedTwice(r).shouldBeEmpty()
        plymeld.check.catalog.Slicing.checked(w).laidTwice.layers.filter { Math.abs(it.z - 0.6) < 1e-6 && it.reading.spots.isNotEmpty() }.shouldBeEmpty()
    }
})
