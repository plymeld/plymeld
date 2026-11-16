package plymeld.check

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.check.catalog.Catalog
import plymeld.check.catalog.Models
import plymeld.check.catalog.Slicing
import plymeld.check.catalog.Slicing.Written
import plymeld.check.catalog.Slicing.problems
import plymeld.core.check.ModelCheck
import plymeld.mesh.Slicer
import plymeld.mesh.TriangleSoup
import plymeld.model.SliceSettings
import java.util.Locale

/**
 * Walls laid by an area's local width: small models whose areas are one to a few lines wide (the catalog's walls models,
 * [Catalog]), sliced through Job as `slice` writes them and read back from the g-code by [WallCoverage] against each model's
 * own cut of its mesh. 0.08 mm layers, 0.44 mm lines, three walls (TEST VALUES, as the other slicing tests': [Slicing.WALLS]);
 * the first layer is not read (it is drawn in).
 *
 * The models (mm): the lens beside a second part and the lens alone, the wedge, the stepped strip, the ring, the blob, two
 * blobs and a neck, the letter E, the deep lens, the pointed blob, the block, the webs between a hole and the side, the
 * finger of one part inside another ([Models] describes each).
 */
class WallsByWidthTest : StringSpec({
    val cx = Models.CX; val cy = Models.CY
    fun write(parts: List<TriangleSoup>, vararg extra: Pair<String, Any>) = Slicing.write(parts, Slicing.WALLS, *extra)
    val block = Models.block; val lensAlone = Models.lensAlone

    /** The webs between a hole and the block's front (Models.webBlock), by width: room for the two outer lines and a little, for them and a line between, for them and an inner loop each and a line between. */
    val webs = listOf("web 1.0 mm", "web 1.6 mm", "web 2.2 mm")
    val models = HashMap<String, Pair<Written, Slicing.WallReading>>()
    fun model(name: String): Pair<Written, Slicing.WallReading> = models.getOrPut(name) {
        val e = Catalog.byName(name)
        val w = Catalog.write(e)
        w to Slicing.wallReading(name, w, e.walls!!, maps = true)
    }
    val names = listOf("lens beside a second part", "lens alone", "wedge", "strip", "ring", "blob", "two blobs and a neck", "letter E",
        "deep lens", "pointed blob", "block")

    /*
     * Fuzzy skin moves the outer wall's points in and out by up to its thickness, opening gaps between it and the next loop;
     * the pre-print check reads each wall that much wider for the hole rule (as `slice` passes it), and these pass. Control:
     * without the allowance the same files are refused for holes (the gaps are there: 0.25 mm wide on the block and the lens).
     */
    "fuzzy skin: the block and the lens pass the pre-print check" {
        for ((n, parts) in listOf("block" to listOf(block), "lens alone" to listOf(lensAlone))) {
            val w = write(parts, "plan.faces.side.technique" to "fuzzy_skin")
            val st = SliceSettings.of(w.setup)
            withClue(n) { (st.fuzzySkin != "none") shouldBe true; w.g.contains("Outer wall") shouldBe true }
            val fuzz = st.fuzzyThicknessUm / 1000.0
            val with = problems(w, fuzzMm = fuzz)
            val without = problems(w).filter { it.contains("a hole inside the walls") }
            println("fuzzy skin ($fuzz mm), $n: ${with.size} problem(s) ${with.take(3)}; without the allowance ${without.size} hole(s) ${without.take(2)}")
            withClue(n) { with.shouldBeEmpty(); without.isNotEmpty() shouldBe true }
        }
    }

    "the pre-print check passes every model (the strip's first layer aside: see below)" {
        for (n in names) {
            val pr = problems(model(n).first)
            println("$n: the pre-print check: ${pr.size} problem(s) ${pr.take(5)}")
            // the strip's first layer: drawn in by the squish compensation, its 0.2 mm step is narrower than the thinnest
            // line laid, while the check reads the outline drawn in less (an open item of the check, as before this rule)
            pr.filter { !(n == "strip" && it.startsWith("layer at z 0.200:")) }.shouldBeEmpty()
        }
    }

    /*
     * The hole rule's control on a real planner's output: the lens alone as the walls before walls by local width laid it
     * (a loop and a line down its middle; the file that version wrote for this model, kept as a test fixture), whose loop's
     * turn and line's end left a hole 0.25 to 0.29 mm wide on its top layers (z 1.32 and 1.42) that the outline rules
     * passed. Its first layer is refused as well, rightly: the foot laid as one loop round an empty middle 2 by 0.8 mm. The
     * same check on this version's output passes (the test above).
     */
    "control: the old walls' hole inside the lens is refused by the pre-print check" {
        val w = model("lens alone").first
        val old = WallsByWidthTest::class.java.getResource("lens-alone-old-walls.gcode")!!.readText()
        val st = SliceSettings.of(w.setup)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        val pr = ModelCheck.check(old, w.plan.parts, zBase, featureMinMm = (st.minFeatureUm + 2 * st.resolutionUm) / 1000.0, infillMinMm = 2 * st.innerWallWidthUm / 1000.0)
        println("the old walls of the lens alone: ${pr.size} problem(s) $pr")
        val holes = pr.filter { it.contains("a hole inside the walls") }
        (holes.isNotEmpty()) shouldBe true
        // the outline rules alone passed it: every refusal is a hole
        (pr.size == holes.size) shouldBe true
    }

    /*
     * The limits (mm, mm²), each reading on every model's read layers:
     *  - nothing uncovered wider than the floor (feature.min and the slicing tolerance a side), and no uncovered spot wider
     *    than 0.105 mm: what the round ends of beads and corners sharper than a bead can reach leave (the floor is 0.18);
     *  - no plastic farther outside than 0.09: the narrowest line laid in the narrowest place a line is laid in stands out
     *    (0.3 - 0.15) / 2 = 0.075, and the slicing tolerance;
     *  - no path shorter than 0.5 mm (no stub, hook or tiny loop), and at most two paths a layer on the lens;
     *  - plastic laid twice (beyond the usual overlap) at most 0.2 mm² a layer: the round start of a bead coming in where
     *    the count changes, beside the bead that carries on;
     *  - the growing lens: consecutive layers' lines no farther apart than 0.2 mm, but at one switch.
     */
    for (n in names.filter { it != "block" }) "$n: covered edge to edge, nothing far outside, no fragment, little laid twice" {
        val r = model(n).second
        for (m in r.layers) {
            withClue("$n, $m") {
                (m.uncoveredWideMm2 == 0.0) shouldBe true
                (m.widestUncoveredMm <= 0.105) shouldBe true
                (m.outsideMaxMm <= 0.09) shouldBe true
                (m.shortestPathMm >= 0.5) shouldBe true
                (m.doubledMm2 <= 0.2) shouldBe true
                if (n.startsWith("lens")) (m.paths <= 2) shouldBe true
            }
        }
    }

    /*
     * A narrow part of an island wide enough for its walls elsewhere: the web between a hole and the block's front. The set
     * loops that fit there run on through it, and what they leave between them is laid by its width, with no hole the
     * pre-print check refuses and nothing uncovered wider than the floor; at most 0.2 mm² laid twice a layer (as above).
     * The 1.0 and 2.2 mm webs also keep every uncovered spot within 0.105 mm, as the narrow islands do. The 1.6 mm web does
     * not: where its wide middle line turns down into the corner, between that line, the inner loop's turn and the hole's
     * loop, a pocket up to 0.14 mm wide stays bare. It is narrower than the floor, so the fork that would reach into it is
     * trimmed (a line laid there would lie on its neighbors), and no rule yet widens three lines into a pocket the way two
     * loops share a slit; that web is held to the floor alone.
     */
    "a web between a hole and the side is laid edge to edge" {
        val checked = webs.map { n -> problems(model(n).first).also { pr -> println("$n: the pre-print check: ${pr.size} problem(s) ${pr.take(3)}"); println(model(n).second) } }
        for ((i, n) in webs.withIndex()) {
            val (_, r) = model(n)
            val pr = checked[i]
            withClue(n) { pr.shouldBeEmpty() }
            for (m in r.layers) withClue("$n, $m") {
                (m.uncoveredWideMm2 == 0.0) shouldBe true
                if (n != "web 1.6 mm") (m.widestUncoveredMm <= 0.105) shouldBe true
                (m.doubledMm2 <= 0.2) shouldBe true
            }
        }
    }

    /*
     * Where a middle line starts (the wedge's change from two lines to three), the outer loop gives way to its round start,
     * each point moved across the track on its own: a point moved farther than the one before it landed behind it, and the
     * outer line stepped back and out again, a spike 15 to 30 µm long turning about 159 degrees, on every layer (22 on the
     * wedge). Counted here: an outer-wall vertex turning more than 150 degrees beside a step shorter than the outer line's
     * width (a tip's run-in turns back by design, along the axis, and is not on the wedge). Control: the count finds the
     * spike as it was written.
     */
    "no spike on an outer line where the lines beside it give way (the wedge)" {
        fun spikes(g: String): Int {
            var n = 0; var feat = ""; var x = Double.NaN; var y = Double.NaN
            val run = ArrayList<DoubleArray>()
            fun close() {
                for (k in 1 until run.size - 1) {
                    val ax = run[k][0] - run[k - 1][0]; val ay = run[k][1] - run[k - 1][1]; val bx = run[k + 1][0] - run[k][0]; val by = run[k + 1][1] - run[k][1]
                    val la = Math.hypot(ax, ay); val lb = Math.hypot(bx, by)
                    if (la == 0.0 || lb == 0.0) continue
                    val turn = Math.toDegrees(Math.acos(((ax * bx + ay * by) / (la * lb)).coerceIn(-1.0, 1.0)))
                    if (turn > 150 && minOf(la, lb) < 0.44) n++
                }
                run.clear()
            }
            for (raw in g.lines()) {
                val l = raw.trim()
                if (l.startsWith(";TYPE:")) { close(); feat = l.substring(6); continue }
                val w = l.substringBefore(';').trim().split(' ').filter { it.isNotEmpty() }
                if (w.isEmpty() || w[0] != "G1") continue
                var nx = x; var ny = y; var e = 0.0
                for (t in w.drop(1)) when (t[0]) { 'X' -> nx = t.substring(1).toDouble(); 'Y' -> ny = t.substring(1).toDouble(); 'E' -> e = t.substring(1).toDouble() }
                val moved = nx != x || ny != y
                if (e > 0 && moved && feat == "Outer wall") { if (run.isEmpty()) run.add(doubleArrayOf(x, y)); run.add(doubleArrayOf(nx, ny)) }
                else if (moved) close()
                x = nx; y = ny
            }
            close()
            return n
        }
        val wedge = spikes(model("wedge").first.g)
        println("wedge: $wedge outer-line spikes")
        wedge shouldBe 0
        // control: the outer line as the wedge's second layer had it before
        val before = ";TYPE:Outer wall\nG1 X104.772 Y100.759\nG1 X100.867 Y100.368 E0.05493\nG1 X100.834 Y100.302 E0.00118\nG1 X100.791 Y100.337 E0.00091\n" +
            "G1 X100.804 Y100.355 E0.00032\nG1 X100.73 Y100.304 E0.00142\nG1 X100.714 Y100.279 E0.00054\nG1 X98.095 Y100.149 E0.03797\n"
        (spikes(before) >= 1) shouldBe true
    }

    "the growing lens changes from one line to two once, both sides of the change covered" {
        for (n in listOf("lens beside a second part", "lens alone")) {
            val r = model(n).second
            val jumps = r.shifts.withIndex().filter { it.value > 0.2 }
            println("$n: shifts ${r.shifts.joinToString { String.format(Locale.ROOT, "%.3f", it) }}")
            withClue(n) { (jumps.size <= 1) shouldBe true }
            for (j in jumps) for (m in listOf(r.layers[j.index], r.layers[j.index + 1])) withClue("$n, $m") { (m.uncoveredWideMm2 == 0.0) shouldBe true }
        }
    }

    "the harness's readings (development report)" {
        for (n in names) {
            val (w, r) = model(n)
            println(r)
            if (n.startsWith("lens")) for ((k, m) in r.layers.withIndex()) println("  $m; shift from below ${if (k == 0) "-" else String.format(Locale.ROOT, "%.3f", r.shifts[k - 1])}")
            java.nio.file.Files.writeString(reviewRender("walls-by-width-${n.replace(' ', '-')}.gcode"), w.g)
        }
    }

    /** A picture of [w]'s part-0 lines (tool 0) on the layers nearest [zs] from above, at their width from their plastic, the model's cut over them. */
    fun picture(file: String, title: String, w: Written, zs: List<Double>) {
        val st = SliceSettings.of(w.setup)
        val t = TechniqueRender.read(w.g)
        val tp = TechniqueRender.Toolpath(t.moves, t.layerZs, t.text, t.pausedBefore, st.filamentDiameterMm)
        val zBase = w.plan.parts.minOf { it.bounds()[2] }
        var bx0 = Double.MAX_VALUE; var by0 = Double.MAX_VALUE; var bx1 = -Double.MAX_VALUE; var by1 = -Double.MAX_VALUE
        val panels = zs.map { z ->
            val k = tp.nearestLayer(z); val h = tp.layer(k).first().height; val mid = tp.layerZs[k] - h / 2
            val cut = w.plan.parts.flatMap { p -> Slicer.slice(p, doubleArrayOf(mid + zBase))[0].loops }
            val own = Slicer.slice(w.plan.parts[0], doubleArrayOf(mid + zBase))[0].loops
            for (p in own) { val b = p.bounds(); bx0 = minOf(bx0, b[0] / 1000.0); by0 = minOf(by0, b[1] / 1000.0); bx1 = maxOf(bx1, b[2] / 1000.0); by1 = maxOf(by1, b[3] / 1000.0) }
            val lines = cut.flatMap { p -> (0 until p.size).map { i -> val j = (i + 1) % p.size
                doubleArrayOf(p.x(i) / 1000.0, p.y(i) / 1000.0, mid, p.x(j) / 1000.0, p.y(j) / 1000.0, mid) } }
            TechniqueRender.Panel(listOf(String.format(Locale.ROOT, "layer at z %.2f mm", tp.layerZs[k]), "black: the model's cut at the layer's middle"),
                tp, { it.layer == k && it.obj != null && it.tool == 0 }, TechniqueRender.Coloring.ORDER, lines = lines, plasticWidth = true)
        }
        TechniqueRender.render(title, TechniqueRender.View.TOP, panels, reviewRender(file),
            window = TechniqueRender.Window(bx0 - 0.3..bx1 + 0.3, by0 - 0.3..by1 + 0.3), lineShare = 1.0)
    }

    "review pictures: each model's lines from above at their width" {
        picture("walls-by-width-lens.png", "The lens beside a second part: four layers round where it grows past two lines", model("lens beside a second part").first, listOf(0.52, 0.60, 0.84, 1.40))
        picture("walls-by-width-wedge.png", "A wedge from nothing to 2 mm", model("wedge").first, listOf(0.6))
        picture("walls-by-width-strip.png", "A strip in steps from 0.2 to 2.0 mm", model("strip").first, listOf(0.6))
        picture("walls-by-width-ring.png", "A ring 1 mm wide", model("ring").first, listOf(0.6))
        picture("walls-by-width-blob.png", "A blob 2.5 by 1 mm", model("blob").first, listOf(0.6))
        picture("walls-by-width-two-blobs-and-a-neck.png", "Two blobs joined by a neck", model("two blobs and a neck").first, listOf(0.6))
        picture("walls-by-width-letter-E.png", "A letter E of strokes 0.9 mm wide", model("letter E").first, listOf(0.6))
        picture("walls-by-width-deep-lens.png", "A lens 2.4 mm long, 1.4 mm deep", model("deep lens").first, listOf(0.36, 0.6))
        picture("walls-by-width-pointed-blob.png", "A blob narrowing to points", model("pointed blob").first, listOf(0.6))
        for (n in webs) picture("walls-by-width-${n.replace(' ', '-')}.png", "A $n between a hole and the side", model(n).first, listOf(0.6))
    }

    /** [g] with every extrusion that starts and ends inside the box (mm) laid as a travel instead (its E taken out). */
    fun blanked(g: String, x0: Double, y0: Double, x1: Double, y1: Double): String {
        var x = Double.NaN; var y = Double.NaN
        fun inBox(px: Double, py: Double) = px in x0..x1 && py in y0..y1
        return g.lines().joinToString("\n") { line ->
            val ws = line.substringBefore(';').trim().split(Regex("\\s+"))
            if (ws.isEmpty() || ws[0] !in setOf("G0", "G1")) return@joinToString line
            fun num(ch: Char) = ws.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDouble()
            val nx = num('X') ?: x; val ny = num('Y') ?: y; val e = num('E')
            val out = if (e != null && e > 0 && inBox(x, y) && inBox(nx, ny)) ws.filter { it.isEmpty() || it[0] != 'E' }.joinToString(" ") else line
            x = nx; y = ny
            out
        }
    }

    /*
     * A finger of one part hanging into a second part off a band of the first, joined to the band by a neck too narrow for
     * the finger's loop and about as long as it is wide: the neck is a pocket between the band's loop, the finger's loop and
     * the other part, inside the object. Its line is a short outer line beside the other part, often shorter than its width
     * (the rule that leaves such a dot out on the object's surface), but not on the surface: no dot shows there, and left
     * out it left the neck bare, a hole the pre-print check refused on 6 of these 23 layers (on a real plate, a hole 0.45 mm
     * wide between letters). The pre-print check passes it. Control: the same file with the lines in the neck taken out
     * is refused for a hole there.
     */
    "a finger of one part inside another, its neck a short pocket: laid, no hole; control: the neck's lines taken out are refused" {
        // the finger (Models.finger): 1 mm wide, 3 mm long, its neck 0.5 mm long and, layer by layer, 0.36 to 0.58 mm wide
        val w = Catalog.write(Catalog.byName("finger inside a second part"))
        java.nio.file.Files.writeString(reviewRender("walls-by-width-finger.gcode"), w.g)
        val pr = problems(w)
        println("a finger inside a second part: the pre-print check: ${pr.size} problem(s) ${pr.take(5)}")
        pr.shouldBeEmpty()
        // control: the finger's tip (where it is under 0.6 mm wide) with its lines taken out
        val shift = w.plan.layout.placed[0].parts[0].bounds().let { b -> doubleArrayOf(b[0] - (cx - 6), b[1] - (cy - 3)) }
        val bare = blanked(w.g, cx - 0.35 + shift[0], cy - 0.6 + shift[1], cx + 0.35 + shift[0], cy - 0.05 + shift[1])
        val prBare = problems(Written(bare, w.plan, w.setup)).filter { it.contains("a hole inside the walls") }
        println("control, the tip's lines taken out: ${prBare.size} hole(s) ${prBare.take(3)}")
        prBare.isNotEmpty() shouldBe true
    }
})
