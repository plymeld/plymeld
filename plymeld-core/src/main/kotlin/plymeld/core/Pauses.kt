package plymeld.core

import plymeld.program.LayerStart
import plymeld.program.ObjectStart
import plymeld.program.Pause
import plymeld.program.PrintProgram
import plymeld.program.Step
import java.util.Locale

/**
 * Pauses the user asks for: before or after an object or part, at a height, or at a layer number. Each becomes a [Pause] right after the start of one layer, before
 * anything on that layer prints.
 *
 *  - [AtLayer]: layer [n], counted from 1 as slicer previews count them (the first layer is 1).
 *  - [AtZ]: the first layer whose top is at or above [zMm] (a project's stored pause names its
 *    layer's top this way).
 *  - [Before] / [After]: an object (as the program names it) or a part (by its Z range in the
 *    placed model): before the first layer it prints on / before the layer after its last.
 */
sealed class PauseLock {
    data class AtLayer(val n: Int) : PauseLock()
    data class AtZ(val zMm: Double) : PauseLock()
    /** Before the first layer whose BOTTOM is at or above [zMm]: a planned insert's top, everything below it printed. */
    data class Above(val zMm: Double) : PauseLock()
    data class Before(val name: String) : PauseLock()
    data class After(val name: String) : PauseLock()

    fun describe(): String = when (this) {
        is AtLayer -> "at layer $n"
        is AtZ -> "at z %.3f mm".format(Locale.ROOT, zMm)
        is Above -> "above z %.3f mm".format(Locale.ROOT, zMm)
        is Before -> "before '$name'"
        is After -> "after '$name'"
    }
}

object Pauses {
    /** Z tolerance when comparing a layer top with a height: a geometric tolerance, not a print parameter. */
    private const val EPS_UM = 1L

    /**
     * [partRangesMm]: each part's Z range in the placed model (min, max), for part names; object
     * names are read from the program's own object blocks. Returns the program with the pauses and,
     * per lock, the layer it landed on ("layer 421 (top z 33.560)"). Several locks on one
     * layer make one pause.
     */
    fun insert(p: PrintProgram, locks: List<PauseLock>, partRangesMm: Map<String, DoubleArray> = emptyMap()): Insertion {
        if (locks.isEmpty()) return placeSwaps(p.steps).let { (s, w) -> Insertion(PrintProgram(s, p.objects, p.exact, p.selfPrimed), emptyList(), w) }
        val starts = p.steps.withIndex().filter { it.value is LayerStart }.map { it.index }
        val layers = starts.map { p.steps[it] as LayerStart }
        require(layers.isNotEmpty()) { "the program has no layers to pause at" }
        // objects printing on each layer (their blocks between this layer start and the next)
        val objectsOn = starts.indices.map { k ->
            val end = if (k + 1 < starts.size) starts[k + 1] else p.steps.size
            (starts[k] until end).mapNotNull { (p.steps[it] as? ObjectStart)?.name }.toSet()
        }
        fun top(k: Int) = layers[k].zUm
        fun bottom(k: Int) = layers[k].zUm - layers[k].heightUm
        fun layerOf(r: PauseLock): Int = when (r) {
            is PauseLock.AtLayer -> {
                require(r.n in 1..layers.size) { "pause at layer ${r.n}: the print has layers 1..${layers.size}" }
                r.n - 1
            }
            is PauseLock.AtZ -> {
                val z = Math.round(r.zMm * 1000)
                layers.indices.firstOrNull { top(it) >= z - EPS_UM } ?: error("pause at z ${r.zMm}: the print ends at z %.3f".format(Locale.ROOT, top(layers.size - 1) / 1000.0))
            }
            is PauseLock.Above -> {
                val z = Math.round(r.zMm * 1000)
                layers.indices.firstOrNull { bottom(it) >= z - EPS_UM } ?: error("pause above z ${r.zMm}: no layer starts at or above it (the print ends at z %.3f)".format(Locale.ROOT, top(layers.size - 1) / 1000.0))
            }
            is PauseLock.Before, is PauseLock.After -> {
                val name = if (r is PauseLock.Before) r.name else (r as PauseLock.After).name
                val onLayers = layers.indices.filter { name in objectsOn[it] }.takeIf { it.isNotEmpty() } ?: partRangesMm[name]?.let { (lo, hi) ->
                    val loUm = Math.round(lo * 1000); val hiUm = Math.round(hi * 1000)
                    val hit = layers.indices.filter { top(it) > loUm + EPS_UM && bottom(it) < hiUm - EPS_UM }
                    // a part is in one object, so its layers are ONE run: objects printed one after
                    // another repeat the same heights, and a later object's layers are not this part's
                    hit.takeWhile { it - hit.first() == hit.indexOf(it) }
                } ?: error("pause ${r.describe()}: no object or part is called '$name' (objects: ${objectsOn.flatten().toSortedSet().joinToString()}; parts: ${partRangesMm.keys.joinToString()})")
                require(onLayers.isNotEmpty()) { "pause ${r.describe()}: it prints on no layer" }
                if (r is PauseLock.Before) onLayers.first() else {
                    val next = onLayers.last() + 1
                    require(next < layers.size) { "pause ${r.describe()}: it prints until the last layer, so nothing is left to pause before" }
                    next
                }
            }
        }
        val at = LinkedHashMap<Int, MutableList<String>>()
        val landed = locks.map { r ->
            val k = layerOf(r)
            at.getOrPut(k) { ArrayList() }.add(r.describe())
            "${r.describe()}: before layer ${k + 1} (top z %.3f)".format(Locale.ROOT, top(k) / 1000.0)
        }
        val insertAfter = at.keys.associateBy { starts[it] }
        val out = ArrayList<Step>(p.steps.size + at.size)
        for ((i, st) in p.steps.withIndex()) {
            out.add(st)
            insertAfter[i]?.let { k -> out.add(Pause(at.getValue(k).joinToString("; "))) }
        }
        return placeSwaps(out).let { (s, w) -> Insertion(PrintProgram(s, p.objects, p.exact, p.selfPrimed), landed, w) }
    }

    /** [program] with the pauses and the swaps placed; [landed]: where each pause lock landed; [swaps]: where each filament swapped by hand is. */
    data class Insertion(val program: PrintProgram, val landed: List<String>, val swaps: List<String>)

    /**
     * A filament swapped by hand ([plymeld.program.FilamentSwap], marked by MultiFilament.steps where a tool first prints
     * another spool than it last printed with) needs a [Pause] between the old spool's last line on that tool and the
     * swap, where the user makes it; plymeld writes no filament changer yet. Without one the print would carry on with the
     * old filament where the plan wants the new (e.g. a top part on a fifth filament, swapped for the base's on the same
     * tool at the pause after the base). The mark is MOVED to right after that pause: from the pause on,
     * the tool's lines are the new filament's, a purge tower's base laid before the layer's first change included, so
     * the file's accounting and the new temperature start there. The old spool's lines are its object blocks' on that
     * tool; a line outside an object block (the tower) is laid with whatever is loaded.
     */
    fun placeSwaps(steps: List<Step>): Pair<List<Step>, List<String>> {
        if (steps.none { it is plymeld.program.FilamentSwap }) return steps to emptyList()
        val inObject = BooleanArray(steps.size)
        var inside = false
        for ((i, st) in steps.withIndex()) { if (st is ObjectStart) inside = true; inObject[i] = inside; if (st is plymeld.program.ObjectEnd) inside = false }
        val moveTo = LinkedHashMap<Int, Int>()           // swap index -> the pause it is moved after (-1: stays, before the tool's first line)
        val words = ArrayList<String>()
        fun layerBefore(i: Int): LayerStart? { var k = i; while (k >= 0) { (steps[k] as? LayerStart)?.let { return it }; k-- }; return null }
        for ((j, st) in steps.withIndex()) {
            if (st !is plymeld.program.FilamentSwap) continue
            val refusal = "tool ${st.tool + 1} prints ${st.fromSpool} and then ${st.toSpool} (filament ${st.fromId} -> ${st.toId}) with no pause between " +
                "${st.fromSpool}'s last line and ${st.toSpool}'s first: plymeld writes no filament changer, so the swap is yours to make at a pause; " +
                "ask for one there (plan.pause.<key>.after = \"<the last part on ${st.fromSpool}>\", .before = \"<the first part on ${st.toSpool}>\", or " +
                ".at = \"<height>\": when both print on one layer only a height lands between them)"
            var k = j - 1; var printedBefore = false
            while (k >= 0) {
                val s = steps[k]
                if (s is Pause) break
                if (s is plymeld.program.Extrude && s.tool == st.tool && inObject[k]) { printedBefore = true; break }
                k--
            }
            val l = layerBefore(j)
            if (k >= 0 && !printedBefore) {
                moveTo[j] = k
                val lp = layerBefore(k)
                words += "on tool ${st.tool + 1}: ${st.fromSpool} out, ${st.toSpool} in (filament ${st.fromId} -> ${st.toId}), at the pause before layer ${(lp?.index ?: 0) + 1}" +
                    (lp?.let { " (top z %.3f)".format(Locale.ROOT, it.zUm / 1000.0) } ?: "")
            } else if (!printedBefore && steps.subList(0, j).none { it is plymeld.program.Extrude && it.tool == st.tool && inObject[steps.indexOf(it)] }) {
                // the tool's first line is already the new spool's (the loadout holds another): load it before starting
                moveTo[j] = -1
                words += "on tool ${st.tool + 1}: ${st.toSpool} (filament ${st.toId}) in place of ${st.fromSpool} (filament ${st.fromId}, the loadout's) before its first line, " +
                    "layer ${(l?.index ?: 0) + 1}: load it before starting"
            } else throw IllegalArgumentException(refusal)
        }
        val after = moveTo.entries.filter { it.value >= 0 }.groupBy({ it.value }, { it.key })
        val out = ArrayList<Step>(steps.size)
        for ((i, st) in steps.withIndex()) {
            if (moveTo[i]?.let { it >= 0 } == true) continue
            out.add(st)
            after[i]?.forEach { out.add(steps[it]) }
        }
        return out to words
    }
}
