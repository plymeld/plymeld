package plymeld.core.check

import plymeld.gcode.KlipperWriter
import plymeld.mesh.TriangleSoup

/**
 * The pre-print check of a plate of several objects: each object's layers read against THAT OBJECT alone. ModelCheck
 * compares a layer with the model's cross-section at its height, so read against every object each layer lacked the
 * other's walls (two plain slabs printed one after the other were refused, "no wall along 90.0 mm" at every layer), and
 * objects printed together at their own layer heights have no common cross-section at all.
 *
 * **One object at a time** ([oneAtATime]): the file is split into **runs** from the g-code itself, not from
 * anything the slicer declares: a run starts at a layer whose height is below the layer before it (the head back down for
 * the next object). Each run is the object's whose footprint (its parts' X and Y extent, [FOOTPRINT_MARGIN_MM] more) holds
 * at least [OWNER_SHARE] of the run's printed line ends; for an object with several lifted bridges (it goes back down
 * after each), a run over it straight after one over it, starting higher than that one started, is the same run. Refused:
 * an object no run prints (absent), an object printed by more than one run, a run printing over no one object.
 *
 * **Printed together** ([together]): the file's layers hold every object's layers merged by height,
 * each object's at its own heights (Job), so an object's lines are those inside the writer's markers for it
 * (`; object start NAME` .. `; object end NAME`, [KlipperWriter.OBJECT_START] / [KlipperWriter.OBJECT_END], written whatever the
 * firmware). Objects printed together can stand a wall's width apart, so their lines cannot be told apart by position as
 * runs are; the markers are the writer's word, and the check holds it to the model: a line marked as one object's but
 * lying on another is outside the first's cross-section and refused. For each object the rest of the file is blanked
 * (the other objects' lines; whole layers it does not print on; tool selections kept, so the tool in force is right), so
 * the line numbers in its problems are the file's. Refused: an object no layer prints (absent); a block of an object
 * not on the plate; a line of the model's features outside every object's block.
 *
 * Each run, or each object's lines, is then checked against its object: re-warped first when the object has a curved
 * band ([CurvedCheck], with the object's own warp), then ModelCheck on its own parts (each frame on its own, for several
 * lifted bridges). The whole file's re-warped text, the parts (curved ones warped), and each object's (each frame's)
 * model surface are returned for GcodeCheck.
 */
object PlateCheck {
    /** One placed object: its parts (bed coordinates), each part's tool (null: not judged), its curved band (null: none). */
    class Obj(val name: String, val parts: List<TriangleSoup>, val partTools: List<Int>?, val curved: CurvedCheck.Spec? = null,
              /** The object's section left open ([ModelCheck.Surface]'s cut); null = the whole object. */
              val cut: DoubleArray? = null)
    /** [surfaces]: each run's (each frame's) model surface, for GcodeCheck's travel rule ([ModelCheck.Surfaces]). */
    class Result(val text: String, val parts: List<TriangleSoup>, val problems: List<String>, val curvedLayers: Int, val surfaces: List<ModelCheck.Surface> = emptyList())

    /** How far outside an object's X and Y extent a printed line end may lie and still be over it (mm): the walls' squish, a brim. plymeld's choice. */
    const val FOOTPRINT_MARGIN_MM = 3.0
    /** The share of a run's printed line ends that must lie over one object's footprint for the run to be that object's. plymeld's choice. */
    const val OWNER_SHARE = 0.9
    private val TOOL = Regex("T\\d+")
    /** A move of Z alone (G0 / G1 Z.. with at most a feed): a lift. */
    private val LIFT = Regex("G[01] Z-?[0-9.]+( F[0-9.]+)?")

    /** [zaa]: the Z anti-aliasing rule (Expectations.forZaa), for each run's flat layers; null when no object has it on. */
    fun oneAtATime(text: String, objects: List<Obj>, zBase: Double, imagesDir: java.nio.file.Path? = null, zaa: ZaaRule? = null): Result {
        val lines = text.lines()
        val split = runs(lines)
        val problems = ArrayList<String>()
        if (split.isEmpty()) return Result(text, objects.flatMap { it.parts }, listOf("no layer markers: not a layered print"), 0)
        val boxes = objects.map { o -> val b = merged(o.parts).bounds(); doubleArrayOf(b[0] - FOOTPRINT_MARGIN_MM, b[1] - FOOTPRINT_MARGIN_MM, b[3] + FOOTPRINT_MARGIN_MM, b[4] + FOOTPRINT_MARGIN_MM) }
        // an object with several lifted bridges (from its model) goes back down after each (the support standing on a bridge's
        // ends is printed after the bridge, lower than its top): a run over it straight after one over it, starting higher than
        // that one started, is the same run. Any other object printed twice, or one printed again from its start, stays refused.
        val banded = objects.map { o -> o.curved?.follows == CurvedCheck.Follows.LIFT && CurvedCheck.bridgeGroups(o.parts, zBase).size > 1 }
        val runs = ArrayList<IntRange>(); val owner = ArrayList<Int?>()
        for (r in split) {
            val o = ownerOf(lines, r, boxes)
            if (o != null && owner.lastOrNull() == o && banded[o] && firstZ(lines, r) > firstZ(lines, runs.last()) + 1e-6) runs[runs.size - 1] = runs.last().first..r.last
            else { runs += r; owner += o }
        }
        for ((ri, r) in runs.withIndex()) if (owner[ri] == null)
            problems += "lines ${r.first + 1}-${r.last + 1}: a run of layers printing over no one object (under ${Math.round(OWNER_SHARE * 100)} % of its line ends over any object's footprint)"
        for ((k, o) in objects.withIndex()) {
            val n = owner.count { it == k }
            if (n == 0) problems += "'${o.name}': nothing of it printed (no run of layers over its footprint)"
            if (n > 1) problems += "'${o.name}': printed in $n runs of layers, not one (one object at a time)"
        }
        val out = lines.toMutableList()
        val checkParts = objects.map { it.parts }.toMutableList()
        val surfaces = ArrayList<ModelCheck.Surface>()
        var curved = 0
        for ((ri, r) in runs.withIndex()) {
            val k = owner[ri] ?: continue
            // the tool in force where the run begins (selected before it, in the start or an earlier run) goes with it:
            // read alone, the run would start on T0
            val tool = (r.first - 1 downTo 0).firstOrNull { TOOL.matches(lines[it].substringBefore(';').trim()) }?.let { lines[it].substringBefore(';').trim() }
            curved += readObject(k, objects[k], lines.subList(r.first, r.last + 1), null, r.first, if (tool != null) "$tool\n" else "", zBase, imagesDir, zaa,
                problems, surfaces, checkParts) { rl -> for (i in rl.indices) out[r.first + i] = rl[i] }
        }
        return Result(out.joinToString("\n"), checkParts.flatten(), problems, curved, surfaces)
    }

    /** [zaa]: as for [oneAtATime]. */
    fun together(text: String, objects: List<Obj>, zBase: Double, imagesDir: java.nio.file.Path? = null, zaa: ZaaRule? = null): Result {
        val lines = text.lines()
        val problems = ArrayList<String>()
        // each line's object (-1: outside every object's block) and layer (-1: before the first layer marker)
        val owner = IntArray(lines.size) { -1 }
        val layerOf = IntArray(lines.size) { -1 }
        var layers = 0; var cur = -1; var feature = ""
        var unmarked: String? = null
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) layers++
            layerOf[i] = layers - 1
            when {
                raw.startsWith(KlipperWriter.OBJECT_START) -> {
                    val name = raw.removePrefix(KlipperWriter.OBJECT_START)
                    cur = objects.indexOfFirst { plymeld.core.IncrementalSlicer.namesObject(name, it.name) }
                    if (cur < 0) problems += "line ${i + 1}: a block of an object not on the plate: '$name'"
                }
                l.startsWith(";TYPE:") -> feature = l.removePrefix(";TYPE:").trim()
                l.startsWith("; FEATURE:") -> feature = l.removePrefix("; FEATURE:").trim()
            }
            owner[i] = cur
            if (raw.startsWith(KlipperWriter.OBJECT_END)) cur = -1
            if (owner[i] < 0 && unmarked == null && layers > 0 && feature in ModelCheck.MODEL) {
                val c = l.substringBefore(';').trim()
                if ((c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 ")) && Regex(" E[0-9.]").containsMatchIn(c)) unmarked = "line ${i + 1}: a line of the model ($feature) outside every object's block"
            }
        }
        unmarked?.let { problems += it }
        if (layers == 0) return Result(text, objects.flatMap { it.parts }, listOf("no layer markers: not a layered print"), 0)
        val out = lines.toMutableList()
        val checkParts = objects.map { it.parts }.toMutableList()
        val surfaces = ArrayList<ModelCheck.Surface>()
        var curved = 0
        // which objects print on each layer
        val onLayer = List(layers) { HashSet<Int>() }
        for (i in lines.indices) if (owner[i] >= 0) onLayer[layerOf[i]] += owner[i]
        for ((k, o) in objects.withIndex()) {
            if (onLayer.none { k in it }) { problems += "'${o.name}': nothing of it printed (no layer holds a block of it)"; continue }
            // the object's own view of the file: its lines, every comment (the state the checks read: layer markers, the
            // feature and width in force, which the writer does not repeat when a block starts as the one before ended) and
            // every tool selection, and the rest of the layers it prints on (fan, lifts); the other objects' moves blank, so
            // the line numbers stay the file's; the layers it does not print on are left to it by their markers alone
            // (dropped before the planar check)
            // a lift outside every block (a lone Z move: the head lifted clear of a finished group of bridges, plan.bridge_order =
            // hot_to_cold) is the head's height for every object, on whatever layer it falls: without it the next
            // object read its first travel at its own last height, under the layer it then printed
            val own = lines.mapIndexed { i, raw ->
                val keep = layerOf[i] < 0 || owner[i] == k || (owner[i] < 0 && k in onLayer[layerOf[i]]) || raw.trim().startsWith(";") || TOOL.matches(raw.substringBefore(';').trim()) ||
                    (owner[i] < 0 && LIFT.matches(raw.substringBefore(';').trim()))
                if (keep) raw else ""
            }
            // what comes back re-warped: its own lines, and all of a layer only it prints on (the layer's header in its warp)
            curved += readObject(k, o, own, text, 0, "", zBase, imagesDir, zaa, problems, surfaces, checkParts) { rl ->
                for (i in rl.indices) if (owner[i] == k || (layerOf[i] >= 0 && onLayer[layerOf[i]] == setOf(k) && owner[i] < 0)) out[i] = rl[i] }
        }
        return Result(out.joinToString("\n"), checkParts.flatten(), problems, curved, surfaces)
    }

    /**
     * One object's lines [text] (its run, or its own view of a together plate, then with the whole file as [headText] for
     * the curved check's head rule, and the layers it does not print on dropped before the planar check), starting at the
     * file's line [lineOffset], with [pre] prepended (a tool selection in force): read in the object's own warp when it has a
     * curved band, then ModelCheck against its parts (each frame on its own); its surface(s) added to [surfaces], its
     * (warped) parts set in [checkParts], and the re-warped lines handed to [writeBack]. Returns its curved layers.
     */
    private fun readObject(k: Int, o: Obj, text: List<String>, headText: String?, lineOffset: Int, pre: String, zBase: Double, imagesDir: java.nio.file.Path?, zaa: ZaaRule?,
                           problems: MutableList<String>, surfaces: MutableList<ModelCheck.Surface>, checkParts: MutableList<List<TriangleSoup>>,
                           writeBack: (List<String>) -> Unit): Int {
        var runText = text.joinToString("\n")
        var frames: List<CurvedCheck.Frame>? = null
        var curved = 0
        if (o.curved != null) {
            val rd = CurvedCheck.read(runText, o.parts, zBase, o.curved, lineOffset = lineOffset, headText = headText)
            problems += rd.problems.map { "'${o.name}': $it" }
            curved += rd.curvedLayers
            runText = rd.text
            checkParts[k] = rd.parts
            frames = rd.frames
            writeBack(runText.lines())
        }
        val dir = imagesDir?.resolve(o.name.replace(Regex("[^A-Za-z0-9._-]"), "_"))?.also { java.nio.file.Files.createDirectories(it) }
        // several lifted bridges: each frame (what stands on the bed, each bridge) against its own parts
        for (fr in frames ?: listOf(CurvedCheck.Frame("", runText, checkParts[k].indices.toList(), checkParts[k]))) {
            val z = if (fr.flat) zaa else null
            val planar = pre + (if (headText == null) fr.text else withoutEmptyLayers(fr.text))
            val sf = ModelCheck.Surface(fr.parts, zBase, planar, zaaMinMm = z?.minMm, cut = o.cut).also { surfaces += it }
            val where = if (fr.name.isEmpty()) "'${o.name}'" else "'${o.name}' ${fr.name}"
            problems += ModelCheck.check(planar, fr.parts, zBase, imagesDir = if (fr.name.isEmpty()) dir else dir?.resolve(fr.name.replace(' ', '_'))?.also { java.nio.file.Files.createDirectories(it) },
                surface = sf, partTools = o.partTools?.let { t -> fr.partIndex.map { t[it] } }, zaa = z).map { "$where: $it" }
        }
        return curved
    }

    private fun isLayerMarker(raw: String): Boolean { val l = raw.trim(); return l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER") }
    private fun isHeightLine(l: String) = l.startsWith(";Z:") || l.startsWith("; Z_HEIGHT:") || l.startsWith(";HEIGHT:") || l.startsWith("; LAYER_HEIGHT:")

    /**
     * [text] without the layers in which nothing is extruded: on an object's own view of a together plate, the layers it
     * does not print on. Their comments stay, all but the layer marker and its height lines (`;Z:`, `;HEIGHT:`, which
     * would stand for the layer before them when the next marker closes it): they carry the state the planar check
     * reads (the feature and width in force), which the writer does not repeat when the next block starts as that layer ended.
     */
    private fun withoutEmptyLayers(text: String): String {
        val lines = text.lines()
        val marks = lines.indices.filter { isLayerMarker(lines[it]) }
        if (marks.isEmpty()) return text
        val sb = StringBuilder(text.length)
        for (i in 0 until marks[0]) sb.append(lines[i]).append('\n')
        for ((j, m) in marks.withIndex()) {
            val end = if (j + 1 < marks.size) marks[j + 1] else lines.size
            val extrudes = (m until end).any { i -> val c = lines[i].substringBefore(';').trim(); (c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 ")) && Regex(" E[0-9.]").containsMatchIn(c) }
            if (extrudes) for (i in m until end) sb.append(lines[i]).append('\n')
            else for (i in m + 1 until end) { val l = lines[i].trim(); if (l.startsWith(";") && !isHeightLine(l)) sb.append(lines[i]).append('\n') }
        }
        return sb.toString().trimEnd('\n')
    }

    /** The runs (line ranges, each from a layer marker to the line before the next run's): a new run where a layer is lower than the one before it. */
    fun runs(lines: List<String>): List<IntRange> {
        val starts = ArrayList<Int>()
        var lastZ = Double.NEGATIVE_INFINITY
        var marker = -1
        for ((i, raw) in lines.withIndex()) {
            val l = raw.trim()
            if (l.startsWith(";LAYER_CHANGE") || l.startsWith("; CHANGE_LAYER")) { marker = i; continue }
            val z = when { l.startsWith(";Z:") -> l.removePrefix(";Z:"); l.startsWith("; Z_HEIGHT:") -> l.removePrefix("; Z_HEIGHT:"); else -> null }?.trim()?.toDoubleOrNull() ?: continue
            if (marker < 0) continue
            if (starts.isEmpty() || z < lastZ - 1e-6) starts += marker
            lastZ = z; marker = -1
        }
        return starts.indices.map { j -> starts[j] until (if (j + 1 < starts.size) starts[j + 1] else lines.size) }
    }

    /** The height of run [r]'s first layer (its first ;Z: line). */
    private fun firstZ(lines: List<String>, r: IntRange): Double = r.firstNotNullOfOrNull { i ->
        val l = lines[i].trim()
        when { l.startsWith(";Z:") -> l.removePrefix(";Z:"); l.startsWith("; Z_HEIGHT:") -> l.removePrefix("; Z_HEIGHT:"); else -> null }?.trim()?.toDoubleOrNull()
    } ?: Double.NEGATIVE_INFINITY

    /** The object whose footprint [boxes] holds at least [OWNER_SHARE] of the run's printed line ends, or null. */
    private fun ownerOf(lines: List<String>, r: IntRange, boxes: List<DoubleArray>): Int? {
        var x = Double.NaN; var y = Double.NaN
        val count = IntArray(boxes.size); var total = 0
        for (i in r) {
            val c = lines[i].substringBefore(';').trim()
            if (!(c.startsWith("G1 ") || c.startsWith("G2 ") || c.startsWith("G3 "))) continue
            val w = c.split(Regex("\\s+"))
            fun num(ch: Char) = w.drop(1).firstOrNull { it.isNotEmpty() && it[0] == ch }?.substring(1)?.toDoubleOrNull()
            val nx = num('X') ?: x; val ny = num('Y') ?: y
            if ((num('E') ?: 0.0) > 0 && !x.isNaN() && (nx != x || ny != y)) {
                total++
                for ((k, b) in boxes.withIndex()) if (nx >= b[0] && nx <= b[2] && ny >= b[1] && ny <= b[3]) count[k]++
            }
            x = nx; y = ny
        }
        if (total == 0) return null
        val best = count.indices.maxBy { count[it] }
        return if (count[best] >= OWNER_SHARE * total) best else null
    }

    private fun merged(parts: List<TriangleSoup>): TriangleSoup = if (parts.size == 1) parts[0] else {
        val c = FloatArray(parts.sumOf { it.count } * 9); var at = 0
        for (p in parts) { System.arraycopy(p.coords, 0, c, at, p.count * 9); at += p.count * 9 }
        TriangleSoup(c, parts.sumOf { it.count })
    }
}
