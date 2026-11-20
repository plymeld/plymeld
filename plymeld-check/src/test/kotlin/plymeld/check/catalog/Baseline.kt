package plymeld.check.catalog

import java.nio.file.Files
import java.nio.file.Path

/**
 * The catalog's recorded measures: a plain text file, one line per model, row and measure (`model<TAB>row<TAB>measure<TAB>value`),
 * sorted, `#` lines comments. A run is compared with it measure by measure; every measure is exact (see [tolerance]).
 * `-Pplymeld.refreshCatalogBaseline` (the test JVM's system property `plymeld.refreshCatalogBaseline`) writes the run in its
 * place, so the file changes only on purpose and the change shows in the commit's diff.
 */
object Baseline {
    /** A measure's place: the model, the row of settings, the measure's name. */
    data class Key(val model: String, val row: String, val measure: String) : Comparable<Key> {
        override fun compareTo(other: Key) = compareValuesBy(this, other, { it.model }, { it.row }, { it.measure })
    }

    /** The baseline's place in the repository, from the module's directory (the test JVM's working directory). */
    const val SOURCE = "src/test/resources/plymeld/check/catalog/baseline.tsv"
    /** The same file on the test classpath. */
    const val RESOURCE = "/plymeld/check/catalog/baseline.tsv"
    const val REFRESH = "plymeld.refreshCatalogBaseline"
    const val ACCEPT = "./gradlew :plymeld-check:test --tests 'plymeld.check.catalog.CatalogTest' -P$REFRESH"

    /**
     * How far a measure may move and still be the same: nothing, for every measure. The slice is deterministic (the same
     * models and settings write the same bytes, which CatalogTest and the determinism tests check), and every measure is
     * read from the file or from what the writing measured, then printed at fixed decimals: a measure that moves is a slice
     * that changed. (A JDK whose Math functions round differently in the last place could move a slice too; the baseline
     * would then show it.)
     */
    @Suppress("UNUSED_PARAMETER")
    fun tolerance(measure: String): Double = 0.0

    fun format(values: Map<Key, String>): String = buildString {
        append("# The catalog's recorded measures (CatalogTest): model, row, measure, value; sorted. Written by\n")
        append("# $ACCEPT\n")
        for ((k, v) in values.toSortedMap()) { append(k.model).append('\t').append(k.row).append('\t').append(k.measure).append('\t').append(v).append('\n') }
    }

    fun parse(text: String): Map<Key, String> {
        val out = LinkedHashMap<Key, String>()
        for ((i, line) in text.lines().withIndex()) {
            if (line.isBlank() || line.startsWith("#")) continue
            val p = line.split('\t')
            require(p.size == 4) { "baseline line ${i + 1}: ${p.size} fields, not 4: $line" }
            require(out.put(Key(p[0], p[1], p[2]), p[3]) == null) { "baseline line ${i + 1}: ${p[0]} / ${p[1]} / ${p[2]} twice" }
        }
        return out
    }

    /** The recorded baseline (empty when there is none yet). */
    fun read(): Map<Key, String> = Baseline::class.java.getResource(RESOURCE)?.readText()?.let { parse(it) } ?: emptyMap()

    /** Writes [values] as the baseline in the repository (the source file, not the copy on the classpath). */
    fun write(values: Map<Key, String>): Path {
        val p = Path.of(SOURCE).toAbsolutePath()
        Files.createDirectories(p.parent)
        Files.writeString(p, format(values))
        return p
    }

    fun refreshing() = System.getProperty(REFRESH) == "true"

    /** What differs between [recorded] and [run]: measures that moved (old, new), measured but not recorded, recorded but not measured. */
    class Diff(val moved: Map<Key, Pair<String, String>>, val added: Map<Key, String>, val gone: Map<Key, String>) {
        val empty get() = moved.isEmpty() && added.isEmpty() && gone.isEmpty()
        /** Every difference, by model and row, and how to accept them. */
        fun report(): String = buildString {
            append("the catalog's measures differ from the baseline ($SOURCE in plymeld-check): ${moved.size} moved, ${added.size} not in the baseline, ${gone.size} in the baseline but not measured\n")
            val all = (moved.keys + added.keys + gone.keys).groupBy { it.model to it.row }.toSortedMap(compareBy({ it.first }, { it.second }))
            for ((mr, keys) in all) {
                append("  ${mr.first} / ${mr.second}:\n")
                for (k in keys.sorted()) when (k) {
                    in moved -> append("    ${k.measure}: ${moved.getValue(k).first} -> ${moved.getValue(k).second}\n")
                    in added -> append("    ${k.measure}: (not in the baseline) -> ${added.getValue(k)}\n")
                    else -> append("    ${k.measure}: ${gone.getValue(k)} -> (not measured: a model, row or measure no longer in the catalog)\n")
                }
            }
            append("Explain every move (a side effect of the change, or intended). To accept the run as the new baseline: $ACCEPT,\n")
            append("then read the file's diff and commit it with the change.")
        }
    }

    fun compare(recorded: Map<Key, String>, run: Map<Key, String>): Diff {
        val moved = java.util.TreeMap<Key, Pair<String, String>>(); val added = java.util.TreeMap<Key, String>(); val gone = java.util.TreeMap<Key, String>()
        for ((k, v) in run) {
            val old = recorded[k]
            when {
                old == null -> added[k] = v
                old != v && !within(k.measure, old, v) -> moved[k] = old to v
            }
        }
        for ((k, v) in recorded) if (k !in run) gone[k] = v
        return Diff(moved, added, gone)
    }

    /** Whether [a] and [b], both numbers, lie within the measure's [tolerance] of each other (text: never). */
    private fun within(measure: String, a: String, b: String): Boolean {
        val x = a.toDoubleOrNull() ?: return false; val y = b.toDoubleOrNull() ?: return false
        return Math.abs(x - y) <= tolerance(measure)
    }
}
