package plymeld.check.catalog

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import plymeld.check.TechniqueRender
import plymeld.check.catalog.Baseline.Key
import plymeld.check.catalog.Catalog.BASE
import plymeld.check.catalog.Catalog.Entry
import plymeld.check.catalog.Catalog.Row

/**
 * The catalog's side-effect check: every model of [Catalog] sliced under each of its rows (one after another, each slice
 * on the parallel pool), measured (CatalogMeasures), and compared with the recorded baseline (Baseline): any measure that moved, any
 * measure not recorded and any recorded one no longer measured fails the test, the failure listing each, old -> new, by
 * model and row, and how to accept them. The absolute limits a model has are its technique's own tests' (WallsByWidthTest,
 * ThinRidgeTest, ExposedSliverTest, SpeckNearPartTest); here the pre-print check: each model passes it, or is refused
 * only as its entry names (a finding not yet fixed); the outer wall of the models of straight sides and true arcs: no
 * move shorter than the slicing tolerance and no kink; and the outer wall of the models of narrow islands laid by their
 * beads: no zigzag (a kink where the line goes on the way it came, not a tip's run-in nor a square end's corner).
 *
 * Controls: a setting changed on one model moves its measures and the comparison reports them; a baseline with a value
 * altered, a line missing and a stray line is reported for each. Determinism: every unit measured side by side on the
 * parallel pool as the JVM's first slices, and two models measured again in this JVM, read exactly as in the run.
 */
class CatalogTest : StringSpec({
    val units: List<Pair<Entry, Row>> = Catalog.entries.flatMap { e -> e.rows.map { e to it } }
    fun keyed(e: Entry, row: Row, m: CatalogMeasures.Measured) = m.values.mapKeys { (k, _) -> Key(e.name, row.name, k) }

    /**
     * Every unit measured once for this JVM, one after another, each with the layer cache emptied first (as a `slice` run
     * starts): (entry, row) -> measured, what the baseline holds. Each slice runs on the parallel pool itself. Before them,
     * every unit side by side on the pool as this JVM's first slices ([pooled]), which the determinism test below holds to
     * the same measures.
     */
    var measured: Map<Pair<String, String>, CatalogMeasures.Measured>? = null
    /** Every unit measured side by side on the parallel pool (units of the parallel work), before [run]'s: this JVM's first slices. */
    var pooled: List<CatalogMeasures.Measured> = emptyList()
    fun run(): Map<Pair<String, String>, CatalogMeasures.Measured> {
        measured?.let { return it }
        // the models' meshes first, on this thread: a unit must not wait on a lazy another unit is making
        for (e in Catalog.entries) e.parts()
        plymeld.core.LayerCache.clear()
        val tp = System.nanoTime()
        pooled = plymeld.core.Parallel.map(units.size) { i -> CatalogMeasures.measure(units[i].first, units[i].second) }
        println(String.format(java.util.Locale.ROOT, "side by side on the pool: %d units measured in %.1f s", units.size, (System.nanoTime() - tp) / 1e9))
        val t0 = System.nanoTime()
        val ms = units.map { (e, row) -> plymeld.core.LayerCache.clear(); CatalogMeasures.measure(e, row) }
        println(String.format(java.util.Locale.ROOT, "the catalog: %d models, %d units (model and row) measured in %.1f s", Catalog.entries.size, units.size, (System.nanoTime() - t0) / 1e9))
        return units.indices.associate { (units[it].first.name to units[it].second.name) to ms[it] }.also { measured = it }
    }
    fun all(): Map<Key, String> = run().flatMap { (k, m) -> keyed(Catalog.byName(k.first), Catalog.byName(k.first).rows.first { it.name == k.second }, m).entries }
        .associate { it.key to it.value }

    "every model is named once, says what it is for, and is measured under its base row first" {
        Catalog.entries.map { it.name }.distinct().size shouldBe Catalog.entries.size
        for (e in Catalog.entries) withClue(e.name) {
            e.purpose.isNotBlank() shouldBe true
            e.name.none { it == '\t' || it == '\n' } shouldBe true
            e.rows.first() shouldBe BASE
            e.rows.all { r -> r.name.none { it == '\t' || it == '\n' } } shouldBe true
            // the expected refusals name rows the model has
            e.expect.keys.all { r -> e.rows.any { it.name == r } } shouldBe true
        }
    }

    "the pre-print check: every model passes under every row, or is refused only as its entry names" {
        val wrong = ArrayList<String>()
        for ((e, row) in units) {
            val pr = run().getValue(e.name to row.name).problems
            val expected = e.expect[row.name].orEmpty()
            println("${e.name} / ${row.name}: the pre-print check: ${pr.size} problem(s)${if (expected.isEmpty()) "" else " (expected: $expected)"} ${pr.take(3)}")
            if (expected.isEmpty()) { if (pr.isNotEmpty()) wrong += "${e.name} / ${row.name}: refused: ${pr.take(5)}" }
            else {
                // the named refusal is still there (else the finding was fixed: update the entry), and nothing else is
                if (pr.isEmpty()) wrong += "${e.name} / ${row.name}: passes now; its entry expects a refusal ($expected): update the entry"
                pr.filter { p -> expected.none { it in p } }.forEach { wrong += "${e.name} / ${row.name}: refused for something its entry does not name: $it" }
                expected.filter { x -> pr.none { x in it } }.forEach { wrong += "${e.name} / ${row.name}: the expected refusal '$it' is gone" }
            }
        }
        withClue(wrong.joinToString("\n")) { wrong.shouldBeEmpty() }
    }

    "every measure of every model under every row matches the recorded baseline" {
        val now = all()
        val diff = Baseline.compare(Baseline.read(), now)
        if (Baseline.refreshing()) {
            val p = Baseline.write(now)
            println("the catalog's baseline written: ${now.size} lines to $p" + (if (diff.empty) " (no change)" else "\n" + diff.report()))
        } else {
            println("the catalog's baseline: ${now.size} measures" + (if (diff.empty) ", all as recorded" else "\n" + diff.report()))
            withClue(diff.report()) { diff.empty shouldBe true }
        }
    }

    /*
     * A model whose outline is straight sides and true arcs (a block, posts, a cylinder, round holes and pins, fins, decks,
     * slopes, a dome) gives its outer wall no move shorter than the slicing tolerance and no kink: the outline is simplified
     * to the tolerance and its walls are its offsets, nothing in them turns back. The narrow islands laid by their beads
     * (decision 28: a tip's line runs in and back by design), two parts' meeting and the infill's links along an outline are
     * held by the baseline only.
     */
    "the outer wall of a model of straight sides and true arcs: no move shorter than the tolerance, no kink" {
        val plain = listOf("block", "smooth cylinder", "cornered post", "overhang ladder", "bridges", "sloped top", "domed top", "small pillar",
            "holes", "pins", "two-color piece")
        val wrong = ArrayList<String>()
        for ((e, row) in units) if (e.name in plain) {
            val v = run().getValue(e.name to row.name).values
            for (k in listOf("lines.tiny_moves.outer_wall", "lines.outer_kinks")) if (v.getValue(k) != "0") wrong += "${e.name} / ${row.name}: $k ${v.getValue(k)}"
        }
        plain.all { n -> Catalog.entries.any { it.name == n } } shouldBe true
        withClue(wrong.joinToString("\n")) { wrong.shouldBeEmpty() }
    }

    /*
     * A narrow island laid by its beads (decision 28) turns its outer line back only where it runs into a tip and out, or
     * into a square end's corner as it turns there: no
     * kink where the line goes on the way it came (a zigzag), on the models of narrow islands alone. Their outer loop gives
     * way across the outline where a middle line starts (the bent stroke, the wedge and the lenses change from two lines to
     * three). The two blobs and a neck (an area narrowing fast from four lines to one), the dipped ridges, the finger in a
     * second part and the exposed wedge still have some, held by the baseline.
     */
    "the outer wall of a narrow island laid by its beads: no zigzag" {
        val narrow = listOf("lens beside a second part", "lens alone", "wedge", "strip", "ring", "blob", "letter E", "deep lens", "pointed blob",
            "bent stroke", "teeth under a second part")
        val wrong = ArrayList<String>()
        for ((e, row) in units) if (e.name in narrow) {
            val v = run().getValue(e.name to row.name).values
            if (v.getValue("lines.outer_zigzags") != "0") wrong += "${e.name} / ${row.name}: lines.outer_zigzags ${v.getValue("lines.outer_zigzags")}"
        }
        narrow.all { n -> Catalog.entries.any { it.name == n } } shouldBe true
        withClue(wrong.joinToString("\n")) { wrong.shouldBeEmpty() }
    }

    /*
     * The jagged-line measures count what they say: an outer wall square, 10 mm a side, reads none; with a move of 5 um
     * inserted along a side, one move shorter than the tolerance; with a spike 0.11 mm out and back along another side, one
     * kink (the spike's tip turns 180 deg; where it leaves and rejoins the side, 63 and 117 deg), a zigzag (the side goes on
     * the way it came); with a run 0.5 mm in from that side and 0.5 mm back out over itself (as a loop runs into a narrow
     * island's tip and out), one kink (where it turns back, beside the 0.3 mm move) and no zigzag (it goes back where it came
     * from); with a bar 0.8 mm up from that side, its square end 0.4 mm across, the loop running 0.07 mm into each of the
     * end's corners and back (as a narrow island's loop turns a square end's corners), two kinks and no zigzag; with a spike
     * 0.07 mm out of the square's corner and back, the side turning there but not back, one kink and a zigzag.
     */
    "control: a move shorter than the tolerance, a kink, a zigzag and a run-in inserted in a square's outer wall are each counted" {
        fun square(extraAfterFirst: String = "", extraOnTop: String = "", extraAtCorner: String = "") = TechniqueRender.read(
            "M83\n;LAYER_CHANGE\n;Z:0.2\n;TYPE:Outer wall\nG1 X0 Y0 F600\nG1 X10 Y0 E0.5\n$extraAfterFirst" +
                "G1 X10 Y10 E0.5\n${extraAtCorner}G1 X5 Y10 E0.25\n${extraOnTop}G1 X0 Y10 E0.25\nG1 X0 Y0 E0.5\n").moves
        val plain = CatalogMeasures.jagged(square(), 0.0125, 0.44)
        val tiny = CatalogMeasures.jagged(square(extraAfterFirst = "G1 X10.005 Y0 E0.0003\n"), 0.0125, 0.44)
        val spike = CatalogMeasures.jagged(square(extraOnTop = "G1 X5.05 Y10.1 E0.006\nG1 X5 Y10 E0.006\n"), 0.0125, 0.44)
        val runIn = CatalogMeasures.jagged(square(extraOnTop = "G1 X5 Y9.5 E0.025\nG1 X5 Y9.8 E0.015\nG1 X5 Y10 E0.01\n"), 0.0125, 0.44)
        listOf(plain.tiny, plain.tinyOuter, plain.kinks, plain.zigzags) shouldBe listOf(0, 0, 0, 0)
        listOf(tiny.tiny, tiny.tinyOuter, tiny.kinks, tiny.zigzags) shouldBe listOf(1, 1, 0, 0)
        listOf(spike.tiny, spike.tinyOuter, spike.kinks, spike.zigzags) shouldBe listOf(0, 0, 1, 1)
        listOf(runIn.tiny, runIn.tinyOuter, runIn.kinks, runIn.zigzags) shouldBe listOf(0, 0, 1, 0)
        val squareEnd = CatalogMeasures.jagged(square(extraOnTop = "G1 X5 Y10.8 E0.04\nG1 X5.05 Y10.85 E0.004\nG1 X5 Y10.8 E0.004\n" +
            "G1 X4.6 Y10.8 E0.02\nG1 X4.55 Y10.85 E0.004\nG1 X4.6 Y10.8 E0.004\nG1 X4.6 Y10 E0.04\n"), 0.0125, 0.44)
        val cornerSpike = CatalogMeasures.jagged(square(extraAtCorner = "G1 X10.05 Y10.05 E0.004\nG1 X10 Y10 E0.004\n"), 0.0125, 0.44)
        listOf(squareEnd.tiny, squareEnd.tinyOuter, squareEnd.kinks, squareEnd.zigzags) shouldBe listOf(0, 0, 2, 0)
        listOf(cornerSpike.tiny, cornerSpike.tinyOuter, cornerSpike.kinks, cornerSpike.zigzags) shouldBe listOf(0, 0, 1, 1)
    }

    /*
     * The comparison must see a change. The buried fin 0.5 mm sliced with denser infill (40 % for the rig's 20 %) against its
     * own base row: the filament and the sparse infill's length move, and the report names them under the model's name.
     */
    "control: a setting changed on one model moves its measures, and the comparison reports them" {
        val e = Catalog.byName("buried fin 0.5 mm")
        val base = keyed(e, BASE, run().getValue(e.name to BASE.name))
        val denser = keyed(e, BASE, CatalogMeasures.measure(e, Row("control", listOf("plan.layer.infill.density" to "40%"))))
        val diff = Baseline.compare(base, denser)
        println("control, denser infill on the buried fin:\n" + diff.report())
        val moved = diff.moved.keys.map { it.measure }
        ("filament_mm" in moved) shouldBe true
        ("feature.Sparse infill.length_mm" in moved) shouldBe true
        diff.report().contains("buried fin 0.5 mm / base:") shouldBe true
        diff.report().contains("filament_mm: ${base.getValue(Key(e.name, BASE.name, "filament_mm"))} -> ") shouldBe true
    }

    "control: a value altered, a line missing and a stray line in the baseline are each reported" {
        val now = all()
        val keys = now.keys.sorted()
        val altered = keys.first { now.getValue(it).toDoubleOrNull() != null }
        val missing = keys.last()
        val stray = Key("a model no longer in the catalog", BASE.name, "time_s")
        val recorded = now.toMutableMap().apply {
            put(altered, (getValue(altered).toDouble() + 1).toString()); remove(missing); put(stray, "12.5")
        }
        val diff = Baseline.compare(recorded, now)
        val report = diff.report()
        println("control, a doctored baseline:\n$report")
        diff.moved.keys shouldBe setOf(altered)
        diff.added.keys shouldBe setOf(missing)
        diff.gone.keys shouldBe setOf(stray)
        report.contains("${altered.measure}: ${recorded.getValue(altered)} -> ${now.getValue(altered)}") shouldBe true
        report.contains("${missing.measure}: (not in the baseline)") shouldBe true
        report.contains("a model no longer in the catalog / base:") shouldBe true
        // and the file format reads back what it wrote
        Baseline.parse(Baseline.format(now)) shouldBe now
    }

    /*
     * The same models, settings and code write the same file: two models (a walls case on one tool, and two parts on two
     * tools) measured again in this JVM, the layer cache kept from the run (so their layers come from it), read exactly as
     * in the run; and every unit measured side by side on the parallel pool as this JVM's first slices (before the run, the
     * layer cache emptied) reads exactly as one after another, every measure of every unit.
     *
     * Side by side as a JVM's first work is where a slice once read another's working state: the mesh slicer's crossing
     * point, one buffer shared by every thread, overwritten between its write and its read while the code still ran
     * interpreted (the two-color piece refused for an outline with no wall, the exposed wedge printing nothing on 18
     * layers, in 4 runs of 9, and 2 of 6 in a second count). SlicerThreadsTest holds the slicer itself to it.
     */
    "determinism: every unit reads the same side by side on the parallel pool as one after another, and two measured again in this JVM" {
        val names = listOf("blob", "speck beside a part")
        for (name in names) {
            val e = Catalog.byName(name)
            val again = CatalogMeasures.measure(e, BASE).values
            withClue("$name: measured again in this JVM") { again shouldBe run().getValue(name to BASE.name).values }
            (again.size > 10) shouldBe true
        }
        pooled.size shouldBe units.size
        val moved = units.indices.filter { pooled[it].values != run().getValue(units[it].first.name to units[it].second.name).values }
        val report = moved.joinToString("\n") { i -> val (e, r) = units[i]
            Baseline.compare(keyed(e, r, run().getValue(e.name to r.name)), keyed(e, r, pooled[i])).report().lines().drop(1).take(12).joinToString("\n") }
        withClue("side by side on the pool: ${moved.size} of ${units.size} units read otherwise than one after another (one after another -> side by side)\n$report") {
            moved.shouldBeEmpty()
        }
    }
})
