package plymeld.config

/**
 * The hardware entries a print uses: its machine, the tools on it, what the machine's values name (its model, the plate and
 * filaments in its loadout), what those name in turn, and every entry one of them takes values from through `like`. A record
 * of the config in force (the g-code's source block) lists these and leaves out the rest: the other machines, models,
 * plates, and filaments a config or plymeld's bundled files define say nothing about this print.
 *
 * The references are the schema's ([Schema.Type.Ref]), so a reference added there is followed here. An entry reached only
 * as a `like` base is followed through its own `like` and nothing else: a machine `like` another takes over neither its
 * loadout nor its connection, so the base's loadout is not this print's.
 */
object UsedEntries {
    private val kindOf: Map<String, String> = Schema.ENTRY_TABLES.entries.filter { it.value.startsWith("hardware.") }.associate { (k, t) -> t to k }

    /** The hardware entry (kind, key) [e] is a value of; null outside hardware's keyed tables. */
    private fun entryOf(e: ConfigFiles.Entry): Pair<String, String>? =
        if (e.path.size >= 4 && e.path[0] == "hardware") kindOf["hardware.${e.path[1]}"]?.let { it to e.path[2] } else null

    /** The entry [e]'s value names, when [e] is a reference. */
    private fun named(e: ConfigFiles.Entry): Pair<String, String>? =
        (Schema.find(e.path)?.type as? Schema.Type.Ref)?.let { r -> (e.value as? String)?.let { r.kind to it } }

    /**
     * The config a print on [machine] runs with, of [entries], in order: the hardware it uses ([of]; all of it with no
     * [machine]), without a machine's connection (where the printer is and how to reach it, not what it prints). A value
     * plymeld does not use yet stays: a formula may read it. The g-code's source block lists these, and a plan's fingerprint
     * of its config is taken over them, so slicing again from the block finds the plan's config unchanged, and a change to
     * hardware the print does not use leaves a plan as it was.
     */
    fun inPrint(entries: Collection<ConfigFiles.Entry>, machine: String?): List<ConfigFiles.Entry> {
        val read = entries.filter { !isConnection(it) }
        return if (machine == null) read else of(read, machine)
    }

    /** A machine's connection settings: where the printer is and how to reach it, never part of what was printed. */
    fun isConnection(e: ConfigFiles.Entry) = e.path.size >= 4 && e.path[0] == "hardware" && e.path[1] == "machine" && e.path[3] == "connection"

    /** [entries] with the hardware entries a print on [machine] does not use left out; everything outside hardware kept, in order. */
    fun of(entries: Collection<ConfigFiles.Entry>, machine: String): List<ConfigFiles.Entry> {
        val byEntry = entries.groupBy(::entryOf)
        val followAll = HashMap<Pair<String, String>, Boolean>()   // an entry used, and whether every reference of it is followed
        val queue = ArrayDeque<Pair<String, String>>()
        fun use(id: Pair<String, String>, all: Boolean) {
            if (id !in byEntry) return
            val had = followAll[id]
            if (had == true || (had == false && !all)) return
            followAll[id] = all
            queue += id
        }
        use("machine" to machine, true)
        for ((id, es) in byEntry) if (id?.first == "tool" && es.any { it.path.size == 4 && it.path[3] == "machine" && it.value == machine }) use(id, true)
        for (e in byEntry[null].orEmpty()) named(e)?.let { use(it, true) }
        while (queue.isNotEmpty()) {
            val id = queue.removeFirst()
            val all = followAll.getValue(id)
            for (e in byEntry.getValue(id)) {
                val to = named(e) ?: continue
                val like = e.path.size == 4 && e.path[3] == "like"
                if (like) use(to, false) else if (all) use(to, true)
            }
        }
        return entries.filter { e -> entryOf(e)?.let { it in followAll } ?: true }
    }
}
