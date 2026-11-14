package plymeld.config

import plymeld.config.ConfigFiles.Entry

/**
 * Settings renamed or removed in a release, so a config file, a plan file, or a g-code's source block written before
 * reads as it did: each old path is read as its new one, its value converted, with a note naming both (a warning of
 * the config); a removed one is left out, with a note saying why. `plymeld migrate` rewrites a user's files to the new
 * names. A setting's scoped forms follow it: `plan.object.<key>.…` and `plan.object.<key>.part.<part>.…` for a `plan.`
 * or `job.` path; `<key>` in a path stands for any key (a filament's, a machine's).
 *
 * Each change names the last release that knew the old path. An entry stays at least until the next release whose
 * version starts a new year (0.2028… after a 0.2027… change), so a file is read for a year after its setting changed.
 */
object Migrations {
    /** One change: [old] (a path, `<key>` for any key), the last release that read it ([lastIn]), and why it changed. */
    sealed class Change(val old: String, val lastIn: String, val why: String) {
        val oldSegments: List<String> = old.split('.')
    }

    /** [old] is now [new]; its value as [convert] gives it (the value as written: its text, or a typed value). */
    class Renamed(old: String, val new: String, lastIn: String, why: String, val convert: (Any) -> Any = { it }) : Change(old, lastIn, why)

    /** [old] is gone: its value is left out. */
    class Removed(old: String, lastIn: String, why: String) : Change(old, lastIn, why)

    /** Every change, oldest first. */
    val CHANGES: List<Change> = listOf(
        Renamed("plan.layer.walls.inner_first", "plan.layer.walls.order", "0.20261109.1",
            "the walls' order became a choice of three: inner_outer_inner, inner_first, outer_first") { v ->
            if (v.toString().trim().toBoolean()) "inner_first" else "outer_first"
        },
    )

    /** What [e] reads as after the changes: the entry (itself when none applies; null when removed) and the note to show (null: none). */
    class Result(val entry: Entry?, val note: String?)

    fun migrate(e: Entry): Result {
        for (c in CHANGES) {
            val at = match(e.path, c.oldSegments) ?: continue
            return when (c) {
                is Renamed -> {
                    // under a scope, the new path after its root (plan.object.<key>. + layer.walls.order)
                    val newPath = at.fill(c.new.split('.')).let { if (at.scope.isEmpty()) it else at.scope + it.drop(1) }
                    val value = c.convert(e.value)
                    val shown = ConfigFiles.pathText(newPath)
                    // `plymeld migrate` rewrites a file of the user's: not plymeld's own values, nor a g-code's source block
                    val yours = !e.source.startsWith(ConfigFiles.BUNDLED_SOURCE) && !e.source.contains(" (in ")
                    Result(Entry(newPath, value, e.source, e.keep),
                        "${e.key} (${e.source}): renamed after plymeld ${c.lastIn}, ${c.why}; read as $shown = \"$value\"" +
                            (if (yours) " (`plymeld migrate` rewrites the file)" else ""))
                }
                is Removed -> Result(null, "${e.key} (${e.source}): removed after plymeld ${c.lastIn}, ${c.why}; left out")
            }
        }
        return Result(e, null)
    }

    /** Where a path matched a change: the scope it was set under (put back before the new path), and the keys `<key>` stood for. */
    class Match(val scope: List<String>, private val keys: List<String>) {
        /** [segments] with each `<key>` the key it matched, in order. */
        fun fill(segments: List<String>): List<String> { var k = 0; return segments.map { s -> if (s == KEY) keys.getOrNull(k++) ?: s else s } }
    }

    private const val KEY = "<key>"

    /** Whether [path] is [old] (each `<key>` any key), as written or under an object's or a part's scope (for `plan.` and `job.`). */
    fun match(path: List<String>, old: List<String>): Match? {
        fun same(at: List<String>): List<String>? {
            if (at.size != old.size) return null
            val keys = ArrayList<String>()
            for (i in old.indices) if (old[i] == KEY) keys += at[i] else if (old[i] != at[i]) return null
            return keys
        }
        same(path)?.let { return Match(emptyList(), it) }
        val root = old.first()
        if (root != "plan" && root != "job" || path.size < 3 || path[0] != root || path[1] != "object") return null
        // plan.object.<key>.rest, or plan.object.<key>.part.<part>.rest: the rest is the old path after its root
        for (scopeLen in listOf(3, 5)) {
            if (path.size <= scopeLen || (scopeLen == 5 && path[3] != "part")) continue
            val rest = listOf(root) + path.drop(scopeLen)
            same(rest)?.let { return Match(path.take(scopeLen), it) }
        }
        return null
    }
}
