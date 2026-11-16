package plymeld.check.catalog

import plymeld.check.WallCoverage
import plymeld.check.catalog.Models.CX
import plymeld.check.catalog.Models.CY
import plymeld.check.catalog.Slicing.Rig
import plymeld.check.catalog.Slicing.WallRead
import plymeld.mesh.TriangleSoup

/**
 * The catalog of small models the slicing tests share: each a stable [Entry.name], a one-line [Entry.purpose] (the
 * technique it exercises or the weakness it shows), the parts it is built from ([Models]), the [Rig] it is sliced with, and
 * the [Row]s of settings it is measured under (the first always [BASE], the rig as it is). CatalogTest slices every entry
 * under every row, takes the measures (CatalogMeasures) and compares them with the recorded baseline; the technique tests
 * (WallsByWidthTest, ThinRidgeTest, ExposedSliverTest, SpeckNearPartTest) read the same models against their own limits.
 *
 * Adding a model: build it in [Models] (synthetic, in code; never a user's model), add an [Entry] here with what it is for,
 * its rig and the few rows of settings that matter to it, and accept the baseline (docs: the catalog's section of the
 * testing notes).
 */
object Catalog {
    /** A row of settings a model is measured under: [name] (stable, a baseline key) and the values over the rig. */
    class Row(val name: String, val extra: List<Pair<String, Any>>)
    /** The rig as it is. */
    val BASE = Row("base", emptyList())

    /**
     * A model of the catalog. [walls]: where WallCoverage reads it (a walls case; null: not read); [expect]: the pre-print
     * check's known refusals, by row name, each a fragment of a problem's text that every refusal under that row matches (a
     * finding not yet fixed: the test turns red when the refusal goes, so the entry is updated with the fix).
     */
    class Entry(val name: String, val purpose: String, val rig: Rig, val parts: () -> List<TriangleSoup>,
                val rows: List<Row> = listOf(BASE), val walls: WallRead? = null, val extra: List<Pair<String, Any>> = emptyList(),
                val expect: Map<String, List<String>> = emptyMap()) {
        init { require(rows.first() === BASE) { "$name: the first row is the base" }; require(rows.map { it.name }.distinct().size == rows.size) { "$name: rows named twice" } }
    }

    /** A sliver of one filament on the object's surface handed to its neighbor (the setting's other value). */
    private val SURFACE_TO_NEIGHBOR = Row("sliver at the surface to its neighbor", listOf("plan.layer.sliver.at_surface" to "neighbor"))
    /** Support on (the rigs have none). */
    private val SUPPORT = Row("support", listOf("plan.support.enabled" to true))
    /** The seam models' rows: the rig's back seam, the least visible seam, scarf seams. */
    private val SEAM_ROWS = listOf(BASE, Row("least visible seam", listOf("plan.seam" to "least_visible")), Row("scarf seams", listOf("plan.technique.scarf_seam.enabled" to true)))

    private fun walls(name: String, purpose: String, parts: () -> List<TriangleSoup>, read: WallRead = WallRead(0.25, 1.0)) =
        Entry(name, purpose, Slicing.WALLS, parts, walls = read)
    private fun webWindow(web: Double) = WallRead(0.25, 1.0, window = WallCoverage.Window(CX - 1.2, CY - 3.0 - web - 0.1, CX + 1.2, CY - 2.7))

    val entries: List<Entry> = listOf(
        // walls by local width
        walls("lens beside a second part", "a lens growing from 0.7 to 1.2 mm deep against a second part: one line becoming two, beside another tool",
            { listOf(Models.lens, Models.lensBlock) }, WallRead(0.42, 1.42)),
        walls("lens alone", "the growing lens on a foot of its own: one line becoming two", { listOf(Models.lensAlone) }, WallRead(0.42, 1.42)),
        walls("wedge", "a wedge from nothing to 2 mm wide: lines appearing one by one along it", { listOf(Models.wedge) }),
        walls("strip", "a strip in steps from 0.2 to 2.0 mm wide: each width's walls", { listOf(Models.strip) }, WallRead(0.25, 1.0, cellMm = 0.01)),
        walls("ring", "a ring 1 mm wide: a narrow closed band", { listOf(Models.ring) }),
        walls("blob", "an ellipse 2.5 by 1 mm: a narrow island with round ends", { listOf(Models.blob) }),
        walls("two blobs and a neck", "two blobs joined by a neck 0.5 mm wide: a narrowing between two wider places", { listOf(Models.necked) }),
        walls("letter E", "a letter E of strokes 0.9 mm wide: junctions, corners and ends", { listOf(Models.letter) }),
        walls("deep lens", "a lens 1.4 mm deep: tips, not corners", { listOf(Models.deepLens) }),
        walls("pointed blob", "a blob narrowing to points: wider than three lines in its middle", { listOf(Models.pointedBlob) }),
        walls("block", "a block 10 by 10 mm: an ordinary area, its walls and infill", { listOf(Models.block) }),
        Entry("web 1.0 mm", "a web 1.0 mm wide between a hole and the side: room for the two outer lines and a little", Slicing.WALLS,
            { listOf(Models.webBlock(1.0)) }, walls = webWindow(1.0)),
        Entry("web 1.6 mm", "a web 1.6 mm wide between a hole and the side: the two outer lines and a line between", Slicing.WALLS,
            { listOf(Models.webBlock(1.6)) }, walls = webWindow(1.6)),
        Entry("web 2.2 mm", "a web 2.2 mm wide between a hole and the side: an inner loop each and a line between", Slicing.WALLS,
            { listOf(Models.webBlock(2.2)) }, walls = webWindow(2.2)),
        Entry("finger inside a second part", "a finger of one part in another, its neck a short pocket inside the object", Slicing.WALLS,
            { Models.finger }),
        // a ridge narrowing to a line, then to nothing
        Entry("ridge, flat", "a ridge about as wide as two walls need along much of its length", Slicing.RIDGE, { listOf(Models.flatRidge.part) }),
        Entry("ridge, flat, beside a second part", "the flat ridge against a second part on a second tool", Slicing.RIDGE_TWO,
            { listOf(Models.flatRidge.part, Models.flatRidge.against) }),
        Entry("ridge, wobbly", "the flat ridge, its faces uneven by 10 um: no string of tiny loops", Slicing.RIDGE, { listOf(Models.wobblyRidge.part) }),
        Entry("ridge, wobbly, beside a second part", "the wobbly ridge against a second part", Slicing.RIDGE_TWO,
            { listOf(Models.wobblyRidge.part, Models.wobblyRidge.against) }),
        Entry("ridge, steep", "a ridge whose change from two walls to one is at one place on each layer", Slicing.RIDGE, { listOf(Models.steepRidge.part) }),
        Entry("ridge, dipped", "a ridge lowest in its middle: two walls at its ends, one line between", Slicing.RIDGE, { listOf(Models.dippedRidge.part) }),
        Entry("ridge, dipped, beside a second part", "the dipped ridge against a second part", Slicing.RIDGE_TWO,
            { listOf(Models.dippedRidge.part, Models.dippedRidge.against) }),
        Entry("ridge, pointed", "a ridge whose crest narrows to nothing over millimetres: its tips under the floor left bare", Slicing.RIDGE,
            { listOf(Models.pointedRidge.part) }),
        // two filaments side by side
        Entry("exposed wedge", "a wedge of one filament on another's leaning face, from nothing: a sliver on the object's surface", Slicing.SLIVER,
            { Models.exposedWedge }, rows = listOf(BASE, SURFACE_TO_NEIGHBOR)),
        Entry("buried fin 0.1 mm", "a fin of one filament 0.1 mm thick inside another: a buried sliver, handed to its neighbor", Slicing.SLIVER,
            { Models.buriedFin(0.1) }, extra = listOf("job.part_overlap" to "lower_filament")),
        Entry("buried fin 0.5 mm", "a fin 0.5 mm thick inside another filament: wide enough for a line, its own", Slicing.SLIVER,
            { Models.buriedFin(0.5) }, extra = listOf("job.part_overlap" to "lower_filament")),
        Entry("speck beside a part", "one part's edge 0.1 mm in front of another part's tip: no dot, no line in the other's place", Slicing.TWO_TOOLS,
            { Models.speckBeside }),
        Entry("inlay on a leaning face", "a second filament inlaid flush in a leaning face, and an opening with walls turned from the face's normal down to a buried plate of it",
            Slicing.TWO_TOOLS, { Models.inlay }, rows = listOf(BASE, SUPPORT)),
        Entry("two-color piece", "two halves of two filaments side by side: a tool change and a purge on every layer", Slicing.TWO_TOOLS,
            { Models.twoColorPiece }, rows = listOf(BASE, Row("purge into infill", listOf("plan.mechanism.purge.into" to "infill")))),
        Entry("teeth under a second part", "small teeth of one filament hanging off another's edge over air: islands laid by their beads, nothing bridged or filled over them",
            Slicing.WALLS, { Models.teethUnderBand }, rows = listOf(BASE, SEAM_ROWS[2])),
        // seams
        Entry("smooth cylinder", "a cylinder: no corner to hide a seam in", Slicing.ONE_TOOL, { listOf(Models.smoothCylinder) }, rows = SEAM_ROWS),
        Entry("cornered post", "a square post: four corners to hide a seam in", Slicing.ONE_TOOL, { listOf(Models.corneredPost) }, rows = SEAM_ROWS),
        // overhangs, bridges, tops
        Entry("overhang ladder", "fins leaning out at 30, 45, 55 and 65 deg from vertical: each overhang's walls, and support where asked", Slicing.ONE_TOOL,
            { listOf(Models.overhangLadder) }, rows = listOf(BASE, SUPPORT)),
        Entry("bridges", "decks bridging 5, 10 and 20 mm between piers", Slicing.ONE_TOOL, { listOf(Models.bridges) }),
        Entry("sloped top", "a top sloping at 14 deg: stair-stepped top surfaces", Slicing.ONE_TOOL, { listOf(Models.slopedTop) }),
        Entry("domed top", "a dome on a cylinder: top surfaces shrinking to a point", Slicing.ONE_TOOL, { listOf(Models.domedTop) },
            rows = listOf(BASE, Row("flat tops ironed", listOf("plan.faces.flat_top.technique" to "ironing")))),
        // small layers, holes and pins
        Entry("small pillar", "a pillar 3 mm across: short layers (the minimum layer time) and small loops", Slicing.ONE_TOOL, { listOf(Models.smallPillar) },
            rows = listOf(BASE, Row("no slowdown", listOf("hardware.filament.test-pla.cooling.slowdown.enabled" to false)))),
        Entry("holes", "round holes 1, 2, 3 and 5 mm across through a block", Slicing.ONE_TOOL, { listOf(Models.holes) }),
        Entry("pins", "round pins 1, 2, 3 and 5 mm across on a plate", Slicing.ONE_TOOL, { listOf(Models.pins) }),
    )

    private val byName = entries.associateBy { it.name }.also { m -> require(m.size == entries.size) { "two entries of one name" } }
    fun byName(name: String): Entry = byName[name] ?: error("no catalog entry '$name'")

    /** [e] written under [row] (its rig, its own values, the row's); [seams]: measure the seam scar too. */
    fun write(e: Entry, row: Row = BASE, seams: Boolean = false): Slicing.Written =
        Slicing.write(e.parts(), e.rig, *(e.extra + row.extra).toTypedArray(), seams = seams)
}
