package plymeld.config

import plymeld.config.ConfigFiles.Entry

/** Everything wrong with a config, found at once, each naming the path and where it was set. */
class ConfigError(val problems: List<String>) : IllegalArgumentException(problems.joinToString("\n"))

/** An object as a model file gives it: its name, and each part's name and filament number (1-based). */
class ModelObject(val name: String, val partNames: List<String>, val partSlots: List<Int>, val source: String)

/**
 * One object on the plate: [key] is `base` for the first copy and `base#n` for the n-th; a scope on
 * the base applies to every copy, one on `base#n` to that copy only.
 */
class PlateObject(val key: String, val base: String, val copy: Int, val model: ModelObject) {
    /** The part keys, in model order: each part's name made safe. */
    val partKeys: List<String> = model.partNames.map { Config.safeKey(it) }
}

/**
 * Where a value is used, which decides what the context names in a formula stand for: `machine`,
 * `plate`, `tool`, `filament` the entries in play; `object`, `part` (and the scope of `job` and
 * `plan`) the object and part the value is for.
 */
data class Site(val machine: String? = null, val plate: String? = null, val tool: String? = null, val filament: String? = null,
                val obj: PlateObject? = null, val part: String? = null)

/**
 * The config of one run, resolved: the layered values with `like`
 * taken over, every path checked against the schema, keys in one namespace, the objects on the
 * plate keyed, and formulas worked out where each value is used.
 */
class Config(stack: ConfigStack, models: List<ModelObject>, machineArg: String? = null) {
    /** Things to tell the user that do not stop the run (missing units, scopes that match nothing). */
    val warnings = ArrayList<String>()
    private val problems = ArrayList<String>()

    // what is read ([reading], [countReads]); set before init, which reads values itself
    private val capture = ThreadLocal<MutableSet<String>?>()
    /** The lines read outside [reading] once [countReads] is called: the setup's own reads, then everything a command reads. */
    val readDirectly: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    @Volatile private var readsCount = false

    /** Every value by path text, with the ones taken over through `like` (source "... via like K"). */
    val values: Map<String, Entry>
    /** The kind of each key ("filament", "object" ...). */
    val keys = LinkedHashMap<String, String>()
    val objects: List<PlateObject>
    /** The first-layer windows (FirstLayerWindow), by filament then build plate; every one parsed and checked here. */
    val firstLayerWindows: Map<String, Map<String, FirstLayerWindow.Window>>
    /** The machine that prints (--machine, else job.machine, else the only one defined); null = none known, [machineProblem] says why. */
    val machine: String?
    val machineProblem: String?

    /** The keys of the values set in the files themselves (not taken over through `like`). */
    private val ownKeys: Set<String>

    init {
        // the settings read under a new name or left out (Migrations): told, not stopped
        warnings += stack.migrated
        val own = stack.effective.values.map(::normalized)
        ownKeys = own.mapTo(HashSet()) { it.key }
        values = LinkedHashMap<String, Entry>().also { m -> for (e in own) m[e.key] = e; for (e in takenOver(own)) m.putIfAbsent(e.key, e) }
        for ((kind, table) in Schema.ENTRY_TABLES) {
            if (kind == "object") continue
            val prefix = table.split('.')
            for (e in values.values) if (e.path.size > prefix.size && e.path.subList(0, prefix.size) == prefix) claim(e.path[prefix.size], kind, e.source)
        }
        objects = plateObjects(models)
        for (o in objects) { claim(o.key, "object", o.model.source); if (o.copy == 1) claim(o.base, "object", o.model.source) }
        for (e in values.values) check(e)
        firstLayerWindows = FirstLayerWindow.read(values.values, keysOf("plate").toSet(), problems)
        // the machine that prints: named (--machine, else job.machine), else the only one defined;
        // several and none named is not guessed
        val machines = keysOf("machine")
        val named = machineArg?.let { it to "--machine" } ?: entry(listOf("job", "machine"))?.let { (it.value as String) to "${it.key} (${it.source})" }
        if (named != null && named.first !in machines)
            problems += "${named.second}: there is no machine '${named.first}' (hardware.machine.<key>${if (machines.isEmpty()) "" else ": " + machines.joinToString()})"
        machine = named?.first?.takeIf { it in machines } ?: machines.singleOrNull()?.takeIf { named == null }
        machineProblem = when {
            machine != null -> null
            named != null -> "no machine '${named.first}'"
            machines.isEmpty() -> "no machine defined (hardware.machine.<key>)"
            else -> "which machine prints this? the config defines ${machines.joinToString()}: name one with --machine, or job.machine in a config"
        }
        if (problems.isNotEmpty()) throw ConfigError(problems.distinct())
    }

    /** [machine]'s loadout entry at `hardware.machine.<machine>.loadout.<rest>`, or null. */
    fun loadout(vararg rest: String): Entry? = machine?.let { entry(listOf("hardware", "machine", it, "loadout") + rest) }

    private fun claim(key: String, kind: String, where: String) {
        val had = keys.putIfAbsent(key, kind)
        if (had != null && had != kind) problems += "key '$key' names both a $had and a $kind ($where); a key names one thing"
    }

    // ---- like

    /** The values each `like` entry takes over from its base, as written, sourced "via like". */
    private fun takenOver(own: List<Entry>): List<Entry> {
        val out = ArrayList<Entry>()
        for ((kind, table) in Schema.ENTRY_TABLES) {
            if (!table.startsWith("hardware.")) continue
            val prefix = table.split('.')
            val byKey = own.filter { it.path.size > prefix.size + 1 && it.path.subList(0, prefix.size) == prefix }.groupBy { it.path[prefix.size] }
            for (key in byKey.keys) {
                val chain = ArrayList<String>(); var at = key
                while (true) {
                    if (at in chain) { problems += "$kind '$key': `like` loops: ${(chain + at).joinToString(" -> ")}"; break }
                    chain += at
                    val like = byKey[at]?.firstOrNull { it.path.size == prefix.size + 2 && it.path.last() == "like" } ?: break
                    val base = like.value as? String ?: run { problems += "${like.key} (${like.source}): `like` names a $kind by its key"; break }
                    if (base !in byKey) { problems += "${like.key} (${like.source}): there is no $kind '$base'" + (if (own.any { it.path.getOrNull(2) == base }) " (that key is not a $kind)" else ""); break }
                    at = base
                }
                val have = byKey.getValue(key).map { it.path.drop(prefix.size + 1) }.toMutableSet()
                for (b in chain.drop(1)) for (e in byKey[b].orEmpty()) {
                    val rest = e.path.drop(prefix.size + 1)
                    // a machine's loadout is what is on THAT machine now, and its connection is where THAT machine is (two of one
                    // model are two keys, two addresses, two sets of credentials): a machine `like` another takes over neither
                    if (rest == listOf("like") || (kind == "machine" && rest.firstOrNull() in NOT_TAKEN_OVER) || !have.add(rest)) continue
                    out += Entry(prefix + key + rest, e.value, "${e.source} via like $b")
                }
            }
        }
        return out
    }

    // ---- objects

    private fun plateObjects(models: List<ModelObject>): List<PlateObject> {
        val fromOf = HashMap<String, String>()   // model name -> key
        for (e in values.values) if (e.path.size == 4 && e.path[0] == "model" && e.path[1] == "object" && e.path[3] == "from")
            (e.value as? String)?.let { name -> fromOf.put(name, e.path[2])?.let { problems += "${e.key} (${e.source}): two keys take the model object '$name'" } }
        val seen = HashMap<String, ModelObject>()
        val out = ArrayList<PlateObject>()
        for (m in models) {
            val base = fromOf[m.name] ?: safeKey(m.name)
            seen.put(base, m)?.let { problems += "two objects on the plate are keyed '$base' (${it.source}, ${m.source}): give one a key with [model.object.<key>] from = \"${m.name}\"" }
            val countEntry = entry(listOf("model", "object", base, "count")) ?: entry(listOf("model", "count"))
            val count = countEntry?.let { e ->
                runCatching { quantity(e, Dimension.COUNT, Site()).toInt() }.getOrElse { problems += it.message ?: "${e.key}: $it"; 1 }
            } ?: 1
            if (count < 1) problems += "${countEntry!!.key} (${countEntry.source}): a count is at least 1"
            for (c in 1..maxOf(1, count)) out += PlateObject(if (c == 1) base else "$base#$c", base, c, m)
        }
        if (models.isNotEmpty()) for (e in values.values) if (e.path.size > 3 && e.path[0] == "model" && e.path[1] == "object" && e.path[2] !in seen &&
            out.none { it.key == e.path[2] }) warnings += "${e.key} (${e.source}): no object on the plate is keyed '${e.path[2]}'"
        return out
    }

    /**
     * A value as the pipeline reads it (a file's bare boolean or number arrives as its text, ConfigFiles): a Bool's "true" / "false" as a
     * Boolean, a Numbers' "1 0 0 0 1 0 0 0 1" as its numbers; anything else as written (quantities are parsed where they are
     * used, with their units). The pipeline's own entries (tests, importers) may already carry the typed value.
     */
    private fun normalized(given: Entry): Entry {
        // `name#1` is the first copy, the same object as `name`: read as `name`, so every reader sees one key
        val e = if (given.path.size > 2 && given.path[1] == "object" && given.path[0] in setOf("job", "plan", "model") && given.path[2].endsWith("#1"))
            Entry(given.path.toMutableList().also { it[2] = it[2].removeSuffix("#1") }, given.value, given.source, given.keep) else given
        val d = Schema.find(e.path) ?: return e
        val v = e.value
        val n: Any = when (d.type) {
            is Schema.Type.Bool -> if (v == "true" || v == "false") (v as String).toBoolean() else v
            is Schema.Type.Numbers -> if (v is String) v.trim().split(Regex("\\s+")).let { parts -> parts.map { it.toDoubleOrNull() }.takeIf { it.none { x -> x == null } }?.map { it!! } ?: v } else v
            else -> v
        }
        return if (n === v) e else Entry(e.path, n, e.source, e.keep)
    }

    // ---- checks

    private fun check(e: Entry) {
        val decl = Schema.find(e.path) ?: run { problems += "${e.key} (${e.source}): not a setting plymeld knows"; return }
        // a value under a scope the code never reads (an object's pause, a part's priorities) is refused, not ignored
        val scope = Schema.scopeOf(e.path)
        if (scope != Schema.Scope.PLATE && !Schema.declaredAsIs(e.path) && scope !in decl.scopes) {
            problems += "${e.key} (${e.source}): ${decl.path} cannot be set for ${scope.words}; it applies to ${decl.scopes.joinToString(" or ") { it.words }} " +
                ""; return
        }
        Schema.refusedValue(e.path, e.value)?.let { problems += "${e.key} (${e.source}): $it"; return }
        if (!decl.used && !e.source.startsWith(ConfigFiles.BUNDLED_SOURCE)) warnings += "${e.key} (${e.source}): not used yet, so it has no effect (${decl.meaning})"
        // a filament's value for a machine or a tool names one that exists (a typo would never apply, silently)
        if (e.path.size > 5 && e.path[0] == "hardware" && e.path[1] == "filament" && (e.path[3] == "machine" || e.path[3] == "tool") && keys[e.path[4]] != e.path[3])
            problems += "${e.key} (${e.source}): there is no ${e.path[3]} '${e.path[4]}' (hardware.${e.path[3]}.<key>: ${keysOf(e.path[3]).joinToString().ifEmpty { "none" }})"
        // scopes name objects and parts on the plate; one that names nothing is reported, not an error
        if (objects.isNotEmpty() && e.path.size > 3 && (e.path[0] == "job" || e.path[0] == "plan") && e.path[1] == "object") {
            val o = objects.firstOrNull { it.key == e.path[2] || it.base == e.path[2] }
            if (o == null) warnings += "${e.key} (${e.source}): no object on the plate is keyed '${e.path[2]}'"
            else if (e.path[3] == "part" && e.path.size > 5 && e.path[4] !in o.partKeys) warnings += "${e.key} (${e.source}): object '${o.base}' has no part '${e.path[4]}' (it has ${o.partKeys.joinToString()})"
        }
        if (e.path.size > 4 && e.path[0] == "model" && e.path[1] == "object" && e.path[3] == "part") {
            val o = objects.firstOrNull { it.key == e.path[2] || it.base == e.path[2] }
            if (o != null && e.path[4] !in o.partKeys) warnings += "${e.key} (${e.source}): object '${o.base}' has no part '${e.path[4]}' (it has ${o.partKeys.joinToString()})"
        }
        val v = e.value
        val what = "${e.key} (${e.source})"
        when (val t = decl.type) {
            is Schema.Type.Quantity -> if (!Formula.isFormula(v)) runCatching { Units.parse(v, t.dim, what) }.fold(
                { p -> p.warning?.let { warnings += it } }, { problems += it.message ?: what })
            is Schema.Type.Bool -> if (v !is Boolean && !Formula.isFormula(v)) problems += "$what: expected true or false (or a formula, = ...), got '$v'"
            is Schema.Type.Text -> if (v !is String) problems += "$what: expected text, got '$v'"
            is Schema.Type.Choice -> if (v !is String || v !in t.options) problems += "$what: '$v' is not ${t.label}"
            is Schema.Type.Choices -> if (v !is List<*> || v.any { it !in t.options }) problems += "$what: expected ${t.label}, got '$v'"
            is Schema.Type.Numbers -> if (v !is List<*> || v.size != t.size || v.any { it !is Number }) problems += "$what: expected ${t.label} in one quoted value, the numbers separated by spaces, got '$v'"
            is Schema.Type.Point -> runCatching { Units.point(v, t.dim, what) }.fold({ p -> p.second?.let { warnings += it } }, { problems += it.message ?: what })
            is Schema.Type.Ref -> when {
                v !is String -> problems += "$what: expected the key of a ${t.kind}, got '$v'"
                v.isEmpty() && t.none != null -> Unit
                keys[v] == null -> problems += "$what: there is no ${t.kind} '$v'"
                keys[v] != t.kind -> problems += "$what: '$v' is a ${keys[v]}, not a ${t.kind}"
            }
        }
    }

    // ---- reading values

    fun entry(path: List<String>): Entry? = values[ConfigFiles.pathText(path)]?.also(::noteRead)

    // ---- what is read (the g-code's source block tells the values the print read from the rest)

    /** From now on, every value read outside [reading] counts as read by the print ([readDirectly]). */
    fun countReads() { readsCount = true }

    /** [block]'s result and the config lines it read ([origins] of each entry it looked up), kept apart from any outer reading. */
    fun <T> reading(block: () -> T): Pair<T, Set<String>> {
        val outer = capture.get()
        val mine = LinkedHashSet<String>()
        capture.set(mine)
        try { return block() to mine } finally { capture.set(outer) }
    }

    private fun noteRead(e: Entry) {
        val c = capture.get()
        if (c != null) c += origins(e) else if (readsCount) readDirectly += origins(e)
    }

    /** The lines behind [e] at [site]: its own ([origins]) and, for a formula, every value it reads. */
    fun linesBehind(e: Entry, site: Site): List<String> {
        val dim = (Schema.find(e.path)?.type as? Schema.Type.Quantity)?.dim
        val formula = (e.value as? String)?.trimStart()?.startsWith("=") == true
        return origins(e) + (if (dim != null && formula) reading { quantity(e, dim, site) }.second else emptySet())
    }

    /**
     * The keys of the lines in the files behind [e]: its own; for a value taken over through `like`, each `like` line on the
     * way and the line of the base that sets it.
     */
    fun origins(e: Entry): List<String> {
        if (e.key in ownKeys) return listOf(e.key)
        val (kind, table) = Schema.ENTRY_TABLES.entries.firstOrNull { (_, t) ->
            val p = t.split('.'); e.path.size > p.size + 1 && e.path.subList(0, p.size) == p } ?: return listOf(e.key)
        val prefix = table.split('.')
        val rest = e.path.drop(prefix.size + 1)
        val out = ArrayList<String>()
        for (k in likeChain(kind, e.path[prefix.size])) {
            val own = ConfigFiles.pathText(prefix + k + rest)
            if (own in ownKeys) { out += own; break }
            out += ConfigFiles.pathText(prefix + k + "like")
        }
        return out
    }

    /** [key] and the entries of [kind] it is `like`, nearest first (a loop is reported at load; it stops the walk here). */
    fun likeChain(kind: String, key: String): List<String> {
        val table = Schema.ENTRY_TABLES.getValue(kind).split('.')
        val chain = ArrayList<String>(); var at: String? = key
        while (at != null && at !in chain) { chain += at; at = entry(table + listOf(at, "like"))?.value as? String }
        return chain
    }

    /**
     * Filament [filament]'s value at `hardware.filament.<filament>.<rest>` for [machine] and [tool] (as the melt rate, which
     * is scoped to the machine with the tool as an override): where the schema lets [rest] be scoped
     * (`tool.<tool>.<rest>`, `machine.<machine>.<rest>`), the nearest filament in its `like` chain that sets any of them
     * decides, and within it the tool's wins over the machine's, the machine's over its own. A value the filament sets
     * itself thus wins over one its base sets for the machine. The entry is the filament's own (taken over through `like`
     * where its base set it), so a formula in it is worked out for this filament. Other paths: the filament's value.
     */
    fun filamentEntry(filament: String, rest: List<String>, machine: String?, tool: String?): Entry? {
        val f = Schema.ENTRY_TABLES.getValue("filament").split('.')
        val scopes = listOfNotNull(
            tool?.let { listOf("tool", it) + rest }?.takeIf { Schema.find(f + filament + it) != null },
            machine?.let { listOf("machine", it) + rest }?.takeIf { Schema.find(f + filament + it) != null },
            rest)
        if (scopes.size == 1) return entry(f + filament + rest)
        for (k in likeChain("filament", filament)) for (s in scopes)
            if (ConfigFiles.pathText(f + k + s) in ownKeys) return entry(f + filament + s)
        return null
    }

    /** Keys of the entries of [kind] (a key of [Schema.ENTRY_TABLES]). */
    fun keysOf(kind: String): List<String> = keys.filterValues { it == kind }.keys.toList()

    /**
     * The value of `kind.rest` (kind = job or plan) for [site]'s object and part, the most specific
     * scope first: the copy's part, the object's part, the copy, the object, then the general value.
     */
    fun scoped(kind: String, rest: List<String>, site: Site): Entry? {
        val o = site.obj
        val scopes = ArrayList<List<String>>()
        if (o != null) {
            val names = listOf(o.key, o.base).distinct()
            if (site.part != null) for (n in names) scopes += listOf(kind, "object", n, "part", site.part)
            if (o.copy == 1) scopes += listOf(kind, "object", "${o.base}#1")
            for (n in names) scopes += listOf(kind, "object", n)
        }
        scopes += listOf(kind)
        return scopes.firstNotNullOfOrNull { entry(it + rest) }
    }

    /** [e]'s value in the base unit of [dim], a formula worked out at [site]. */
    fun quantity(e: Entry, dim: Dimension, site: Site): Double = quantity(e, dim, site, emptyList())

    private fun quantity(e: Entry, dim: Dimension, site: Site, chain: List<String>): Double {
        val what = "${e.key} (${e.source})"
        if (!Formula.isFormula(e.value)) return Units.parse(e.value, dim, what).value
        if (e.key in chain) throw IllegalArgumentException("formulas form a loop: ${(chain.dropWhile { it != e.key } + e.key).joinToString(" -> ")}")
        val here = siteOf(e.path, site)
        return Formula.evaluate(e.value as String, dim, what) { p -> reference(p, here, chain + e.key, what) }
    }

    /** Switch [e]'s value at [site]: true or false as written, or a formula worked out ([Formula]). */
    fun boolean(e: Entry, site: Site): Boolean = boolean(e, site, emptyList())

    private fun boolean(e: Entry, site: Site, chain: List<String>): Boolean {
        val what = "${e.key} (${e.source})"
        (e.value as? Boolean)?.let { return it }
        if (!Formula.isFormula(e.value)) throw IllegalArgumentException("$what: expected true or false, got '${e.value}'")
        if (e.key in chain) throw IllegalArgumentException("formulas form a loop: ${(chain.dropWhile { it != e.key } + e.key).joinToString(" -> ")}")
        val here = siteOf(e.path, site)
        return Formula.evaluateSwitch(e.value as String, what) { p -> reference(p, here, chain + e.key, what) }
    }

    /**
     * The site for a value at [path]: inside a hardware entry, its own kind names that entry; inside a filament's value for
     * a machine or a tool (`hardware.filament.<f>.machine.<m>.…`, `….tool.<t>.…`), that machine or tool too.
     */
    private fun siteOf(path: List<String>, site: Site): Site {
        if (path.size < 3 || path[0] != "hardware") return site
        val k = path[2]
        fun onTool(s: Site, t: String) = s.copy(tool = t, machine = (entry(listOf("hardware", "tool", t, "machine"))?.value as? String) ?: s.machine)
        return when (path[1]) {
            "machine" -> site.copy(machine = k)
            "plate" -> site.copy(plate = k)
            "tool" -> onTool(site, k)
            "filament" -> site.copy(filament = k).let { s ->
                if (path.size > 5 && path[3] == "machine") s.copy(machine = path[4]) else if (path.size > 5 && path[3] == "tool") onTool(s, path[4]) else s }
            else -> site
        }
    }

    private fun reference(p: List<String>, site: Site, chain: List<String>, what: String): Formula.V {
        fun none(why: String): Nothing = throw IllegalArgumentException("$what: '${p.joinToString(".")}' $why")
        val rest = p.drop(1)
        val target: Entry? = when (p[0]) {
            "machine", "plate", "tool", "filament" -> {
                val key = when (p[0]) { "machine" -> site.machine; "plate" -> site.plate; "tool" -> site.tool; else -> site.filament }
                    ?: none("refers to the ${p[0]} in play, and there is none here")
                // the filament's: for the machine and tool in play, where the value may be set for them
                if (p[0] == "filament") filamentEntry(key, rest, site.machine, site.tool) else entry(listOf("hardware", p[0], key) + rest)
            }
            "object" -> { val o = site.obj ?: none("refers to the object in play, and there is none here"); entry(listOf("model", "object", o.key) + rest) ?: entry(listOf("model", "object", o.base) + rest) }
            "part" -> {
                val o = site.obj ?: none("refers to the part in play, and there is none here"); val pt = site.part ?: none("refers to the part in play, and there is none here")
                entry(listOf("model", "object", o.key, "part", pt) + rest) ?: entry(listOf("model", "object", o.base, "part", pt) + rest)
            }
            "job", "plan" -> scoped(p[0], rest, site)
            else -> entry(p)
        }
        if (target == null) {
            // where the value would be set, when the reference names an entry in play
            val key = when (p[0]) { "machine" -> site.machine; "plate" -> site.plate; "tool" -> site.tool; "filament" -> site.filament; else -> null }
            val set = key?.let { " (set hardware.${p[0]}.$it.${rest.joinToString(".")})" } ?: ""
            throw MissingReference("$what: '${p.joinToString(".")}' names no value$set")
        }
        return when (val t = Schema.find(target.path)?.type) {
            is Schema.Type.Quantity -> Formula.Q(quantity(target, t.dim, siteOf(target.path, site), chain), Formula.dimsOf(t.dim))
            is Schema.Type.Bool -> Formula.B(boolean(target, siteOf(target.path, site), chain))
            is Schema.Type.Choice, is Schema.Type.Text, is Schema.Type.Ref ->
                Formula.T(target.value as? String ?: none("is not a word"))
            else -> none("is not a quantity, a switch, or a word")
        }
    }

    /**
     * A fingerprint per section (hardware, model, job, method) of every value outside `plan` a print on the job's machine
     * reads ([UsedEntries.inPrint]), for `plan.source_fingerprint.config.<section>`: the decisions were made from these.
     */
    fun fingerprints(): Map<String, String> {
        // a plan file's own values (the file that carries plan.source_fingerprint) are decisions, not the config the plan was made
        // from: the job locks it writes for the surface techniques the search weighed are left out, as its plan values are
        fun fileOf(source: String) = source.substringBeforeLast(':')
        val planFiles = values.values.filter { it.path.size > 1 && it.path[0] == "plan" && it.path[1] == "source_fingerprint" }.map { fileOf(it.source) }.toSet()
        return UsedEntries.inPrint(values.values, machine).filter { it.path[0] != "plan" && fileOf(it.source) !in planFiles }.groupBy { it.path[0] }.mapValues { (_, es) ->
            Fingerprint.of(es.sortedBy { it.key }.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    companion object {
        /** A model's name as a key: whitespace runs become '-'. */
        fun safeKey(name: String): String = name.trim().replace(Regex("\\s+"), "-")
        /** A machine's groups that `like` never takes over: what is on it now, and where it is. */
        val NOT_TAKEN_OVER = setOf("loadout", "connection")
    }
}

/** A formula names a value that nothing sets: [message] names the formula's own key and what to set. */
class MissingReference(message: String) : IllegalArgumentException(message)

/** Short stable hashes (SHA-256, first 16 hex digits). */
object Fingerprint {
    fun of(text: String): String = of(text.toByteArray(Charsets.UTF_8))
    fun of(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(8).joinToString("") { "%02x".format(it) }
}
