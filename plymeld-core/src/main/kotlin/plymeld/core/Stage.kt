package plymeld.core

/**
 * The pipeline is a graph of stages. Each stage declares the *keys* it depends on —
 * a mesh hash, the settings it actually reads, and the outputs of the stages before
 * it — and its result is cached under those keys. Re-running the pipeline after a
 * change re-computes only the stages whose keys changed (incremental re-slicing: a density change
 * must not re-slice the mesh).
 *
 * Keys are plain strings so they can be logged and compared; values are whatever the
 * stage produces. The cache is per pipeline instance and holds the latest result per
 * stage only — the goal is "cheap re-slice after a tweak", not a build system.
 */
class Stage<T>(val name: String, private val keyOf: () -> String, private val compute: () -> T) {
    private var cachedKey: String? = null
    private var cached: T? = null
    var runs: Int = 0
        private set

    /** Current key, computed from this stage's declared inputs. */
    fun key(): String = keyOf()

    /** Returns the cached value when the key is unchanged, otherwise recomputes. */
    fun get(): T {
        val k = keyOf()
        if (k != cachedKey) {
            cached = compute()
            cachedKey = k
            runs++
        }
        @Suppress("UNCHECKED_CAST")
        return cached as T
    }

    fun invalidate() { cachedKey = null; cached = null }
}

/** Settings snapshot with change tracking: stages build their keys from the values they read. */
class Settings(initial: Map<String, String> = emptyMap()) {
    private val values = HashMap(initial)

    operator fun get(key: String): String = values[key] ?: error("setting '$key' is not defined")
    operator fun set(key: String, value: String) { values[key] = value }
    fun has(key: String) = values.containsKey(key)

    /** A key fragment naming exactly the settings a stage reads, with their values. */
    fun keyOf(vararg keys: String): String = keys.joinToString(";") { "$it=${get(it)}" }
}
