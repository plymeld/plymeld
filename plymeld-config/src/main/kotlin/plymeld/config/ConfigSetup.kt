package plymeld.config

import plymeld.model.Catalog
import plymeld.model.Entity
import plymeld.model.Kind
import plymeld.model.ObjectSetup
import plymeld.model.PartSetup
import plymeld.model.Setup
import plymeld.model.Sourced
import plymeld.model.Spool
import plymeld.model.Values

/**
 * A resolved [Config] as today's pipeline takes it (plymeld.model.Setup): each declared path that
 * feeds a pipeline setting is worked out where that setting lives — the machine, each tool in use,
 * each filament, each slot, each filament in each tool, each object and part — so a formula such as
 * `= tool.bore / 2` gives every part half of its own nozzle. A few settings are made from several
 * values (wall lines from the wall's thickness, bed temperatures from the plate and the filament's kind, the
 * first layer's speed from the plan, the filament's first-layer window, the filament and the machine, and its height
 * from the plan and the window); those are listed in [derived].
 */
object ConfigSetup {
    /** A pause asked for in `plan.pause.<key>`: [kind] is at, before, or after; [value] a height (mm) or an object / part key. */
    class Pause(val key: String, val kind: String, val value: Any)

    /**
     * [warnings]: things about the print worth a warning (a plate that does not suit a filament, a first-layer window not
     * used); [notes]: about the config itself; [firstLayer]: each object's (and part's, where it differs) first layer
     * decided from a first-layer window.
     */
    class Result(val setup: Setup, val pauses: List<Pause>, val notes: List<String>, val warnings: List<String> = emptyList(),
                 val firstLayer: List<FirstLayerNote> = emptyList())

    /**
     * The first layer of object [obj] (or its part [part]) as a first-layer window decided it: [heightMm] (null: locked by
     * the plan, or a part, whose layers are its object's), [speedMmS] (null: locked by the plan), and why.
     */
    class FirstLayerNote(val obj: String, val part: String?, val heightMm: Double?, val speedMmS: Double?, val why: String)

    /** A first-layer window at a site: its [choice], or null and [warning] (why none applies, when the filament has one). */
    class WindowUse(val choice: FirstLayerWindow.Choice?, val warning: String?, val window: FirstLayerWindow.Window?, val tempC: Double?)

    /** One filament slot: the number a model's parts name, the tool and filament in it. */
    class Slot(val number: Int, val tool: String, val toolIndex: Int, val filament: String)

    /** A tool without a narrowest line (line.width.min): this share of its bore stands in. plymeld's choice, see where it is used. */
    private const val STAND_IN_NARROWEST = 0.75

    /** Choices renamed in the schema, as the pipeline knows them. */
    private val CHOICES = mapOf(
        "plan.layer.walls.style" to mapOf("fixed_width" to "classic", "variable_width" to "arachne"),
        "plan.mechanism.adhesion.brim.where" to mapOf("outer" to "outer_only"))

    fun setup(c: Config): Result {
        // what the setup reads to lay out the job (the loadout, the plate, the first-layer windows) is read for the print; a
        // value worked out for a setting is read only when the print asks for it (its lines are in its Sourced)
        c.countReads()
        val notes = ArrayList<String>()
        val problems = ArrayList<String>()
        val machine = c.machine ?: throw ConfigError(listOf(c.machineProblem ?: "no machine"))
        val plate = c.loadout("plate")?.let { it.value as? String }
        if (plate == null) problems += "hardware.machine.$machine.loadout.plate: no build plate on machine '$machine' (the bed temperatures are the plate's; there are: ${c.keysOf("plate").joinToString()})"
        val slots = slots(c, machine, problems)
        if (slots.isEmpty()) problems += "hardware.machine.$machine.loadout: no filament loaded (loadout.tool.<tool>.filament or loadout.slot.<n>.filament)"
        if (problems.isNotEmpty()) throw ConfigError(problems)
        val base = Site(machine = machine, plate = plate, tool = slots.first().tool, filament = slots.first().filament)

        // the entities of the pipeline's setup
        val machineV = Values(Entity.MACHINE, machine)
        val maxIndex = slots.maxOf { it.toolIndex }
        val tools = (1..maxIndex).map { i -> Values(Entity.TOOL, slots.firstOrNull { it.toolIndex == i }?.tool ?: "tool $i (not loaded)") }
        val materials = slots.map { it.filament }.distinct().associateWith { Values(Entity.MATERIAL, it) }
        val spools = slots.map { s -> Spool("spool ${s.number}", s.filament, s.toolIndex - 1, Values(Entity.SPOOL, "slot ${s.number}")) }
        val materialTool = slots.map { it.filament to it.toolIndex }.distinct().associate { (f, t) -> (f to t - 1) to Values(Entity.MATERIAL_TOOL, "$f in tool $t") }

        fun slotSite(s: Slot) = base.copy(tool = s.tool, filament = s.filament)
        fun slotOf(n: Int, what: String): Slot = slots.firstOrNull { it.number == n } ?: throw ConfigError(listOf(notLoaded(what, n, slots, machine)))

        // objects: each part's slot ([partSlot]: its own lock, else the model's number through the slot map)
        val objectSetups = c.objects.map { o ->
            val parts = o.model.partNames.indices.map { k ->
                val pk = o.partKeys[k]
                val n = partSlot(c, o, k, base)
                slotOf(n, "part '${o.model.partNames[k]}' of '${o.key}'")
                PartSetup(o.model.partNames[k], "spool $n", Values(Entity.INTENT, "${o.key} part $pk"))
            }
            ObjectSetup(o.key, o.key, parts.firstOrNull()?.spool ?: "spool ${slots.first().number}", Values(Entity.INTENT, "${o.key} overrides"), parts)
        }
        val intents = c.objects.associate { it.key to Values(Entity.INTENT, it.key) }
        // the plate's own values of the per-object settings, before any object overrides them (decisions about the whole plate)
        val plateV = Values(Entity.INTENT, "the plate")

        // the plate against each printed filament's kind (hardware.plate.<key>.suits.<kind> = false); no objects
        // (a calibration plate) = every loaded filament
        val used = objectSetups.flatMap { o -> listOf(o.spool) + o.parts.map { it.spool } }.toSet()
        val warnings = slots.filter { c.objects.isEmpty() || "spool ${it.number}" in used }.map { it.filament }.distinct().mapNotNull { f ->
            val kind = c.entry(listOf("hardware", "filament", f, "kind"))?.value as? String ?: return@mapNotNull null
            val e = plate?.let { c.entry(listOf("hardware", "plate", it, "suits", kind)) }
            if (e == null || c.boolean(e, base.copy(filament = f))) null
            else "build plate '$plate' does not suit $kind (filament '$f'; hardware.plate.$plate.suits.$kind = false, ${e.source}): it may not stick, or may bond hard enough to damage the surface"
        }

        // every path that feeds a pipeline setting, worked out where the setting lives; a formula naming a value nothing
        // sets is noted and the rest worked out, so one run names every value to set
        val missing = LinkedHashSet<String>()
        for (d in Schema.decls) try { c.reading {
            val id = d.feeds ?: return@reading
            val setting = Catalog[id]
            fun put(v: Values, site: Site) { value(c, d, site, id)?.let { v[id] = it } }
            when (setting.entity) {
                Entity.MACHINE -> put(machineV, base)
                Entity.TOOL -> for (s in slots.distinctBy { it.toolIndex }) put(tools[s.toolIndex - 1], slotSite(s))
                Entity.MATERIAL -> for (s in slots.distinctBy { it.filament }) put(materials.getValue(s.filament), slotSite(s))
                Entity.SPOOL -> for ((i, s) in slots.withIndex()) put(spools[i].values, slotSite(s))
                Entity.MATERIAL_TOOL -> for (s in slots.distinctBy { it.filament to it.toolIndex }) put(materialTool.getValue(s.filament to s.toolIndex - 1), slotSite(s))
                Entity.INTENT -> {
                    withMachine(c, d, base, id, value(c, d, base, id))?.let { plateV[id] = it }
                    for ((k, o) in c.objects.withIndex()) {
                    val os = objectSetups[k]
                    val s0 = slotOf(os.spool.removePrefix("spool ").toInt(), o.key)
                    val objectValue = withMachine(c, d, slotSite(s0).copy(obj = o), id, withFilament(c, d, slotSite(s0).copy(obj = o), id, value(c, d, slotSite(s0).copy(obj = o), id)))
                    objectValue?.let { intents.getValue(o.key)[id] = it }
                    // a part differs where its own scope or its own tool / filament makes the value differ
                    for ((pi, p) in os.parts.withIndex()) {
                        val s = slotOf(p.spool.removePrefix("spool ").toInt(), o.key)
                        val ps = slotSite(s).copy(obj = o, part = o.partKeys[pi])
                        val pv = withMachine(c, d, ps, id, withFilament(c, d, ps, id, value(c, d, ps, id))) ?: continue
                        if (objectValue == null || pv.value != objectValue.value) p.overrides[id] = pv
                    }
                }
                }
            }
        } } catch (e: MissingReference) {
            missing += e.message!!
        }
        val (windowWarnings, firstLayer) = try { c.reading { derived(c, machineV, materials, slots, objectSetups, intents, base) }.first }
            catch (e: ConfigError) { throw ConfigError(missing.toList() + e.problems) }
        if (missing.isNotEmpty()) throw ConfigError(missing.toList())
        val pauses = c.keysOf("pause").flatMap { k ->
            listOf("at", "before", "after").mapNotNull { kind ->
                val e = c.entry(listOf("plan", "pause", k, kind)) ?: return@mapNotNull null
                Pause(k, kind, if (kind == "at") c.quantity(e, Dimension.LENGTH, base) else e.value)
            }
        }
        priorities(c, base)?.let { (v, from) -> plateV["job.priorities"] = Sourced(v, from) }
        return Result(Setup(machineV, tools, materials, spools, materialTool, intents, objectSetups, keysOf = Schema::keysOf, plate = plateV, models = Models.library(c)),
            pauses, notes + c.warnings, warnings + windowWarnings, firstLayer)
    }

    /**
     * The slot part [k] of [o] prints from: its own `model.object.<key>.part.<part>.slot` (on the copy, else its base); else
     * the model file's filament number for it through the slot map: `model.object.<key>.slots` (the copy's, else its base's),
     * then `model.slots`, each its entry for that number, else its "*" entry (every number); else the model's number.
     * [site] is where the values are read (the machine; the object and part are filled in here).
     */
    fun partSlot(c: Config, o: PlateObject, k: Int, site: Site): Int {
        val pk = o.partKeys[k]
        val at = site.copy(obj = o, part = pk)
        fun count(e: ConfigFiles.Entry) = c.quantity(e, Dimension.COUNT, at).toInt()
        val own = c.entry(listOf("model", "object", o.key, "part", pk, "slot")) ?: c.entry(listOf("model", "object", o.base, "part", pk, "slot"))
        if (own != null) return count(own)
        val n = o.model.partSlots[k]
        val maps = listOf(listOf("model", "object", o.key, "slots"), listOf("model", "object", o.base, "slots"), listOf("model", "slots")).distinct()
        for (m in maps) for (key in listOf(n.toString(), "*")) c.entry(m + key)?.let { return count(it) }
        return n
    }

    /** The refusal of a part on a filament number the loadout lacks: the slots it has, and how to map the model's numbers to them. */
    fun notLoaded(what: String, n: Int, slots: List<Slot>, machine: String): String =
        "$what uses filament $n, but the loadout has ${slots.joinToString { it.number.toString() }} (hardware.machine.$machine.loadout); " +
            "map the model's filaments to the loaded ones with model.slots (e.g. model.slots = { $n = ${slots.first().number} }), or print every part from one with --slot ${slots.first().number}"

    /** The filament slots of [machine]'s loadout (`hardware.machine.<machine>.loadout`), by number; an empty one ("") is no slot. */
    fun slots(c: Config, machine: String, problems: MutableList<String>): List<Slot> {
        val out = ArrayList<Slot>()
        val onMachine = c.keysOf("tool").filter { (c.entry(listOf("hardware", "tool", it, "machine"))?.value as? String) == machine }
        val lo = listOf("hardware", "machine", machine, "loadout")
        fun under(kind: String) = c.values.values.filter { it.path.size == 7 && it.path.subList(0, 5) == lo + kind && it.path[6] == "filament" && it.value != "" }
        for (f in under("tool")) {
            val tk = f.path[5]
            if (tk !in onMachine) problems += "${f.key} (${f.source}): tool '$tk' is not on machine '$machine' (hardware.tool.$tk.machine)"
            val idx = c.entry(listOf("hardware", "tool", tk, "index"))?.let { c.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1
            out += Slot(idx, tk, idx, f.value as String)
        }
        for (e in under("slot")) {
            val n = e.path[5].toIntOrNull()?.takeIf { it >= 1 } ?: run { problems += "${e.key} (${e.source}): a slot is a number from 1"; continue }
            val tk = (c.entry(lo + listOf("slot", e.path[5], "tool"))?.value as? String) ?: onMachine.singleOrNull() ?: run {
                problems += "${e.key} (${e.source}): which tool does slot $n feed? machine '$machine' has ${if (onMachine.isEmpty()) "no tools" else "tools ${onMachine.joinToString()}"} (set hardware.machine.$machine.loadout.slot.$n.tool)"; continue }
            if (tk !in onMachine) problems += "${e.key} (${e.source}): slot $n feeds tool '$tk', which is not on machine '$machine' (hardware.tool.$tk.machine)"
            val idx = c.entry(listOf("hardware", "tool", tk, "index"))?.let { c.quantity(it, Dimension.COUNT, Site(tool = tk)).toInt() } ?: 1
            out += Slot(n, tk, idx, e.value as String)
        }
        out.groupBy { it.number }.filterValues { it.size > 1 }.forEach { (n, ss) -> problems += "filament $n is loaded twice: ${ss.joinToString { "${it.filament} in ${it.tool}" }}" }
        return out.sortedBy { it.number }
    }

    /** The value of [d] for setting [id] at [site], in the pipeline's unit, with where it came from; null when nothing sets it. */
    private fun value(c: Config, d: Schema.Decl, site: Site, id: String): Sourced? = valueOf(c, d, entryAt(c, d, site) ?: return null, site, id)

    /**
     * [e]'s value read by decl [d]'s type for setting [id] (a choice mapped to the pipeline's word, a percent scaled), with its
     * source and the lines behind it: [e]'s own (through `like`) and every value a formula in it reads.
     */
    private fun valueOf(c: Config, d: Schema.Decl, e: ConfigFiles.Entry, site: Site, id: String): Sourced {
        val kind = Catalog[id].kind
        val (v, reads) = c.reading {
            when (val t = d.type) {
                is Schema.Type.Quantity -> c.quantity(e, t.dim, site).let { x -> if (kind == Kind.PERCENT) x * 100 else x }
                is Schema.Type.Choice -> CHOICES[id]?.get(e.value as String) ?: e.value
                is Schema.Type.Bool -> c.boolean(e, site)
                else -> e.value
            }
        }
        return Sourced(v, e.source, c.origins(e) + reads)
    }

    /**
     * A technique value (plan.technique.<technique>.<setting>) as the site's filament sets it (hardware.filament.<key>.technique.…,
     * for its tool or machine first, through like), over [plan] where that is the bundled
     * default or nothing; a plan value a config file of yours sets, at any scope, keeps winning (the first-layer speed's rule:
     * the job's word over the filament's); [plan] otherwise.
     */
    private fun withFilament(c: Config, d: Schema.Decl, site: Site, id: String, plan: Sourced?): Sourced? {
        val seg = d.segments
        if (seg.size < 3 || seg[0] != "plan" || seg[1] != "technique") return plan
        if (plan != null && !plan.from.startsWith(ConfigFiles.BUNDLED_SOURCE)) return plan
        val fil = site.filament ?: return plan
        val e = c.filamentEntry(fil, listOf("technique") + seg.drop(2), site.machine, site.tool) ?: return plan
        return valueOf(c, d, e, site, id)
    }

    /** The visible lines' motion, which a machine may set from its motion plate (`hardware.machine.<key>.motion.visible.*`). */
    private val MACHINE_MOTION = setOf("plan.mechanism.motion.visible.speed", "plan.mechanism.motion.visible.acceleration")

    /**
     * The visible lines' speed or acceleration ([MACHINE_MOTION]) as the site's machine sets it (hardware.machine.<key>.motion.visible.speed
     * / .visible.acceleration, its motion plate's reading), over [plan] where that is plymeld's bundled stand-in or nothing;
     * a plan value a config file of yours sets keeps winning (the first-layer speed's rule: the job's word over the
     * machine's); [plan] otherwise, and for every other setting.
     */
    private fun withMachine(c: Config, d: Schema.Decl, site: Site, id: String, plan: Sourced?): Sourced? {
        if (id !in MACHINE_MOTION) return plan
        if (plan != null && !plan.from.startsWith(ConfigFiles.BUNDLED_SOURCE)) return plan
        val m = site.machine ?: return plan
        val e = c.entry(listOf("hardware", "machine", m, "motion") + id.removePrefix("plan.mechanism.motion.").split('.')) ?: return plan
        return valueOf(c, d, e, site, id)
    }

    /**
     * The entry [d] stands for at [site]: an entry's key from the site (a filament's value for the site's machine or tool
     * where it may be set for them: [Config.filamentEntry]), a job / plan value at its scope.
     */
    fun entryAt(c: Config, d: Schema.Decl, site: Site): ConfigFiles.Entry? {
        val seg = d.segments
        return when (seg[0]) {
            "job", "plan" -> c.scoped(seg[0], seg.drop(1), site)
            "hardware" -> {
                val key = when (seg[1]) { "machine" -> site.machine; "plate" -> site.plate; "tool" -> site.tool; "filament" -> site.filament; else -> null }
                if (seg.size > 2 && seg[2] == "<key>") key?.let {
                    if (seg[1] == "filament") c.filamentEntry(it, seg.drop(3), site.machine, site.tool) else c.entry(listOf(seg[0], seg[1], it) + seg.drop(3))
                } else c.entry(seg)
            }
            else -> c.entry(seg)
        }
    }

    /**
     * The entry the melt cap (the filament's `melt.flow.max`) comes from at [site]: the filament's for the site's tool, else
     * for its machine, else its own ([Config.filamentEntry]); null when none is set.
     */
    fun meltEntry(c: Config, site: Site): ConfigFiles.Entry? =
        site.filament?.let { c.filamentEntry(it, listOf("melt", "flow", "max"), site.machine, site.tool) }

    /** Settings made from several values; the first-layer windows' warnings and decisions. */
    private fun derived(c: Config, machineV: Values, materials: Map<String, Values>, slots: List<Slot>, objects: List<ObjectSetup>,
                        intents: Map<String, Values>, base: Site): Pair<List<String>, List<FirstLayerNote>> {
        val windowWarnings = LinkedHashSet<String>()
        val firstLayer = ArrayList<FirstLayerNote>()
        val m = base.machine!!
        fun hw(kind: String, key: String?, rest: String) = key?.let { c.entry(listOf("hardware", kind, it) + rest.split('.')) }
        /** The lines of a first-layer window's table. */
        fun windowLines(w: FirstLayerWindow.Window?): List<String> = w?.let { c.values.values.filter { e -> e.key.startsWith(it.key + ".") }.flatMap(c::origins) }.orEmpty()
        // the bed: build area and where 0,0 is
        val bx = hw("machine", m, "build.x"); val by = hw("machine", m, "build.y")
        if (bx != null && by != null) {
            val x = c.quantity(bx, Dimension.LENGTH, base); val y = c.quantity(by, Dimension.LENGTH, base)
            val center = (hw("machine", m, "build.origin")?.value as? String) == "center"
            val kx = c.linesBehind(bx, base) + listOfNotNull(hw("machine", m, "build.origin")?.key)
            val ky = c.linesBehind(by, base) + listOfNotNull(hw("machine", m, "build.origin")?.key)
            machineV["hardware.machine.build.left"] = Sourced(if (center) -x / 2 else 0.0, bx.source, kx); machineV["hardware.machine.build.right"] = Sourced(if (center) x / 2 else x, bx.source, kx)
            machineV["hardware.machine.build.front"] = Sourced(if (center) -y / 2 else 0.0, by.source, ky); machineV["hardware.machine.build.back"] = Sourced(if (center) y / 2 else y, by.source, ky)
        }
        // bed temperatures, stored on the plate and filament kind: the filament's own for this plate
        // (hardware.filament.<key>.bed.<plate>), else the plate's for the filament's kind
        // (hardware.plate.<key>.bed.<kind>); the first layer's, else that temperature
        val plate = base.plate!!
        val problems = ArrayList<String>()
        for (s in slots.distinctBy { it.filament }) {
            val site = base.copy(tool = s.tool, filament = s.filament)
            val kind = hw("filament", s.filament, "kind")?.value as? String
            val own = hw("filament", s.filament, "bed.$plate.temperature.print")
            val ofKind = kind?.let { hw("plate", plate, "bed.$it.temperature.print") }
            val temp = own ?: ofKind
            if (temp == null) {
                problems += "no bed temperature for filament '${s.filament}' on build plate '$plate': set hardware.plate.$plate.bed.${kind ?: "<kind>"}.temperature.print" +
                    (if (kind == null) " (the filament has no kind)" else "") + ", or hardware.filament.${s.filament}.bed.$plate.temperature.print" +
                    (if (kind != null && hw("plate", plate, "suits.$kind")?.let { !c.boolean(it, site) } == true) " (the plate says it does not suit $kind)" else "")
                continue
            }
            val first = hw("filament", s.filament, "bed.$plate.temperature.first_layer") ?: (if (own != null) null else kind?.let { hw("plate", plate, "bed.$it.temperature.first_layer") }) ?: temp
            val kindKey = listOfNotNull(hw("filament", s.filament, "kind")?.let { c.origins(it) }).flatten()
            materials.getValue(s.filament)["hardware.filament.bed.temperature.print"] = Sourced(c.quantity(temp, Dimension.TEMPERATURE, site), temp.source,
                c.linesBehind(temp, site) + (if (temp === own) emptyList() else kindKey))
            materials.getValue(s.filament)["hardware.filament.bed.temperature.first_layer"] = Sourced(c.quantity(first, Dimension.TEMPERATURE, site), first.source + (if (first === temp) " (the first layer as the rest)" else ""),
                c.linesBehind(first, site) + (if (first === own || first === hw("filament", s.filament, "bed.$plate.temperature.first_layer")) emptyList() else kindKey))
        }
        // a filament's bed temperature for a plate the config does not have (a typo would be ignored silently)
        val plates = c.keysOf("plate").toSet()
        c.values.values.map { it.path }.filter { it.size > 4 && it[0] == "hardware" && it[1] == "filament" && it[3] == "bed" && it[4] !in plates }
            .map { it.take(5).joinToString(".") }.distinct()
            .forEach { problems += "$it: there is no build plate '${it.substringAfterLast('.')}' (hardware.plate.<key>: ${plates.joinToString()})" }
        if (problems.isNotEmpty()) throw ConfigError(problems)
        // per object (and part, where its own scope or tool makes it differ)
        for ((k, o) in c.objects.withIndex()) {
            val os = objects[k]
            fun slot(spool: String) = slots.first { "spool ${it.number}" == spool }
            val sites = listOf(Triple(intents.getValue(o.key), base.copy(tool = slot(os.spool).tool, filament = slot(os.spool).filament, obj = o), true)) +
                os.parts.mapIndexed { pi, p -> Triple(p.overrides, base.copy(tool = slot(p.spool).tool, filament = slot(p.spool).filament, obj = o, part = o.partKeys[pi]), false) }
            val objectValues = HashMap<String, Any>()
            var objectWindowSpeed: Double? = null
            for ((v, site, isObject) in sites) {
                // [es]: the entries that decided the value (their lines, a formula's inputs too, are what the print reads for it)
                fun set(id: String, x: Any, from: String, vararg es: ConfigFiles.Entry?, extra: Collection<String> = emptyList()) {
                    val s = Sourced(x, from, es.filterNotNull().flatMap { c.linesBehind(it, site) } + extra)
                    if (isObject) { objectValues[id] = x; v[id] = s } else if (objectValues[id] != x) v[id] = s
                }
                fun pl(rest: String) = c.scoped("plan", rest.split('.'), site)
                fun jb(rest: String) = c.scoped("job", rest.split('.'), site)
                /** A plan entry at this site: the locks (surface techniques, seams, pauses, support spots) live in the plan (one word, "locked"). */
                fun pb(rest: String) = c.scoped("plan", rest.split('.'), site)
                // wall lines from the wall's thickness: the outer line, then inner lines to make up the rest
                val side = pl("layer.walls.thickness"); val ow = pl("layer.walls.outer.line.width"); val iw = pl("layer.walls.inner.line.width")
                if (side != null && ow != null && iw != null) {
                    val t = c.quantity(side, Dimension.LENGTH, site); val a = c.quantity(ow, Dimension.LENGTH, site); val b = c.quantity(iw, Dimension.LENGTH, site)
                    set("plan.layer.walls.count", maxOf(1.0, 1.0 + Math.round((t - a) / b)), "from ${side.key} (${side.source}) and the line widths", side, ow, iw)
                }
                // switches and the choices beside them: the pipeline's setting holds the choice, "none" when off
                // scarf seams locked on or off: on the outer walls their where names, "none" off (where the loops start is
                // plan.seam's, read as it is)
                pl("technique.scarf_seam.enabled")?.let { e ->
                    val on = c.boolean(e, site)
                    val where = pl("technique.scarf_seam.where")
                    if (on && where == null) throw ConfigError(listOf("${e.key} (${e.source}): scarf seams on, but on which outer walls? set plan.technique.scarf_seam.where (smooth or all)"))
                    set("plan.technique.scarf_seam.where", if (on) where!!.value as String else "none",
                        if (on) listOf(e.source, where!!.source).distinct().joinToString(", ") else e.source, e, if (on) where else null)
                }
                pl("mechanism.adhesion.brim.enabled")?.let { e ->
                    val on = c.boolean(e, site)
                    val where = pl("mechanism.adhesion.brim.where")
                    if (on && where == null) throw ConfigError(listOf("${e.key} (${e.source}): a brim, but where? set ${ConfigFiles.pathText(e.path.dropLast(1) + "where")} (outer)"))
                    set("plan.mechanism.adhesion.brim.where", if (on) CHOICES.getValue("plan.mechanism.adhesion.brim.where").getValue(where!!.value as String) else "no_brim",
                        if (on) listOf(e.source, where!!.source).distinct().joinToString(", ") else e.source, e, if (on) where else null)
                }
                // the prime line: off, or where (unset: by the print, PrimeLine); on without a place takes the tab (near the model,
                // with a brim, by default)
                val primeOn = pl("mechanism.adhesion.prime.enabled"); val primeLine = pl("mechanism.adhesion.prime.line")
                val primeOnV = primeOn?.let { c.boolean(it, site) }
                when {
                    primeOnV == false -> set("plan.mechanism.adhesion.prime.line", "none", primeOn.source, primeOn)
                    primeLine != null -> set("plan.mechanism.adhesion.prime.line", primeLine.value as String, primeLine.source, primeLine, primeOn)
                    primeOnV == true -> set("plan.mechanism.adhesion.prime.line", "tab", "${primeOn.source} (on, no prime.line: on a peel tab)", primeOn)
                }
                // variable-width walls' thresholds, from the tool's narrowest line:
                // thin areas print down to that line; an area thinner cannot hold even it; a line shorter
                // than two widths is a dot; a change in line count is spread over one line's width
                // (stand-in: the shortest stretch that can hold the change, until a print test)
                // a tool that gives no narrowest line: three quarters of its bore stands in (variable-width walls are the
                // default; a placeholder).
                // plymeld's choice: a strand can leave the opening thinner than it (pushed less while moving), and three
                // quarters of the bore stays wider than a layer is thick at the usual half-bore layer, so it is still a
                // flattened bead; the whole bore left ledges under 0.4 mm unprinted on a 0.4 mm nozzle
                val narrowE = hw("tool", site.tool, "line.width.min") ?: hw("tool", site.tool, "bore")
                val narrowest = hw("tool", site.tool, "line.width.min")?.let { e -> c.quantity(e, Dimension.LENGTH, site) to "derived from the tool's narrowest line (${e.key}, ${e.source})" }
                    ?: hw("tool", site.tool, "bore")?.let { e -> c.quantity(e, Dimension.LENGTH, site) * STAND_IN_NARROWEST to
                        "stand-in: the tool gives no narrowest line (line.width.min), so three quarters of its bore (${e.key}, ${e.source})" }
                narrowest?.let { (w, from) ->
                    fun set(id: String, x: Any, from: String) = set(id, x, from, narrowE)
                    set("plan.layer.walls.line.width.min", w, "$from: the narrowest line")
                    // half the line (plymeld's choice): an area from half the narrowest line thick gets that
                    // line, over-full by at most twice; at the full line, ledges and frame edges thinner than the bore
                    // (a two-color Benchy's cabin) were left unprinted, their outline with no wall
                    set("plan.layer.walls.feature.min", w / 2, "$from: half that line; an area thinner gets nothing (one of at least half the line gets the line)")
                    set("plan.layer.walls.length_factor.min", 2.0, "$from: a line shorter than two widths is a dot")
                    set("plan.layer.walls.transition_length", w, "$from: a change in line count spread over one line's width (stand-in)")
                }
                // the surface techniques locked: each a lock of its technique on the surfaces it serves
                pb("faces.side.technique")?.let { e ->
                    val fw = if (e.value == "fuzzy_skin") pl("technique.fuzzy_skin.where") else null
                    val where = if (e.value == "fuzzy_skin") (fw?.value as? String) ?: "external" else "none"
                    set("plan.technique.fuzzy_skin.where", where, e.source, e, fw)
                } ?: set("plan.technique.fuzzy_skin.where", "none", "no fuzzy skin locked")
                val flatTop = pb("faces.flat_top.technique")
                set("plan.technique.ironing.type", if (flatTop?.value == "ironing") "top" else "none", flatTop?.source ?: "no ironing locked", flatTop)
                pb("faces.sloped_top.technique")?.let { e -> set("plan.faces.sloped_top.z_antialiasing", e.value == "z_antialiasing", e.source, e) }
                pb("faces.under.technique")?.let { e -> set("plan.faces.under.wave_overhang", e.value == "wave_overhang", e.source, e) }
                // goal weights: the priorities given, in the goals' order (Schema.GOALS)
                c.reading { priorities(c, site) }.let { (p, read) -> p?.let { (w, from) -> set("job.priorities", w, from, extra = read) } }
                // the first-layer speed: a machine default the filament (or the filament it is
                // like) may override. A plan value from a config locks it; else the filament's own
                // (motion.first_layer.speed, taken over through like), else the machine's (motion.first_layer.speed), else plymeld's
                // bundled plan value, the stand-in for a machine and filament that set none. A first-layer window
                // (hardware.filament.<key>.first_layer.window.<plate>) decides the speed below a plan
                // lock and over the rest, and the first layer's height below a plan lock (per object: its layers are one stack)
                val use = windowAt(c, site)
                use.warning?.let { windowWarnings += it }
                val speedLock = speedLock(c, site); val heightLock = heightLock(c, site)
                val choice = use.choice
                val fromWindow = if (choice == null) "" else "the first-layer window ${use.window!!.key} (${use.window.source}) at ${fmtC(use.tempC!!)}, the first layer's nozzle temperature, ${choice.why}"
                if (choice != null && speedLock == null) set("plan.mechanism.motion.first_layer.speed", choice.speedMmS, fromWindow, extra = windowLines(use.window))
                else firstLayerSpeed(c, site)?.let { set("plan.mechanism.motion.first_layer.speed", c.quantity(it, Dimension.SPEED, site), "${it.key} (${it.source})", it) }
                if (choice != null && heightLock == null && isObject) set("plan.mechanism.adhesion.first_layer.height", choice.heightMm, fromWindow, extra = windowLines(use.window))
                // the note: the object's always (what the window gave, or that a plan value locked over it); a part's only
                // where its own filament's window gives it another speed than the object's
                val noteSpeed = choice?.speedMmS?.takeIf { speedLock == null }
                if (choice != null && (isObject || (noteSpeed != null && noteSpeed != objectWindowSpeed))) {
                    val locks = listOfNotNull(heightLock?.takeIf { isObject }?.let { "the height locked by ${it.key} (${it.source})" }, speedLock?.let { "the speed locked by ${it.key} (${it.source})" })
                    val gives = "${FirstLayerWindow.mm(choice.heightMm)} at ${FirstLayerWindow.mms(choice.speedMmS)}"
                    // with a lock, the note ends on what the first layer actually gets, the window's own choice named as
                    // what it would have been (the tail "which gives 0.28 mm at 100 mm/s" under a lock of
                    // 0.32 mm at 60 mm/s read as the outcome)
                    val usedH = heightLock?.takeIf { isObject }?.let { c.quantity(it, Dimension.LENGTH, site) } ?: choice.heightMm
                    val usedS = speedLock?.let { c.quantity(it, Dimension.SPEED, site) } ?: choice.speedMmS
                    firstLayer += FirstLayerNote(o.key, if (isObject) null else site.part, choice.heightMm.takeIf { heightLock == null && isObject }, noteSpeed,
                        if (locks.isEmpty()) fromWindow else "${locks.joinToString(" and ")}, over $fromWindow (which would have given $gives): the first layer prints at ${FirstLayerWindow.mm(usedH)} at ${FirstLayerWindow.mms(usedS)}")
                }
                if (isObject) objectWindowSpeed = noteSpeed
                // the first layer's height against what the tool lays as a first layer (first_layer.height.max, else layer.height.max):
                // warned about, not refused (a value set in a config is the user's to keep)
                if (isObject) (v["plan.mechanism.adhesion.first_layer.height"]?.value as? Double)?.let { fh ->
                    val (lo, hi, names) = firstLayerRange(c, site)
                    if ((hi != null && fh > hi + 1e-9) || (lo != null && fh < lo - 1e-9))
                        windowWarnings += "'${o.key}': its first layer is ${FirstLayerWindow.mm(fh)} (${v["plan.mechanism.adhesion.first_layer.height"]?.from}), outside what tool '${site.tool}' lays as a first layer, " +
                            "${lo?.let { FirstLayerWindow.mm(it) } ?: "any"} to ${hi?.let { FirstLayerWindow.mm(it) } ?: "any"} ($names)"
                }
                // the nozzle temperature for this object or part over the filament's (plan.mechanism.temperature.*), warned when
                // outside the filament's own range
                for ((rest, id) in listOf("print" to "hardware.filament.temperature.print", "first_layer" to "hardware.filament.temperature.first_layer")) pl("mechanism.temperature.$rest")?.let { e ->
                    val t = c.quantity(e, Dimension.TEMPERATURE, site)
                    set(id, t, "${e.key} (${e.source})", e)
                    val lo = hw("filament", site.filament, "temperature.min")?.let { c.quantity(it, Dimension.TEMPERATURE, site) }
                    val hi = hw("filament", site.filament, "temperature.max")?.let { c.quantity(it, Dimension.TEMPERATURE, site) }
                    if ((lo != null && t < lo - 1e-9) || (hi != null && t > hi + 1e-9))
                        windowWarnings += "'${o.key}': ${e.key} ${fmtC(t)} is outside filament '${site.filament}''s range ${lo?.let { fmtC(it) } ?: "?"} to ${hi?.let { fmtC(it) } ?: "?"}"
                }
                // the part fan and the minimum layer time for this object or part over the filament's (plan.mechanism.cooling.*,
                // what bond-first cooling sets, or a config of the user's). The overhang fan is its own setting
                // (hardware.filament.overhang.fan, the filament's cooling.fan by default) and is not changed by these.
                pl("mechanism.cooling.fan")?.let { e -> set("hardware.filament.cooling.fan", c.quantity(e, Dimension.RATIO, site) * 100, "${e.key} (${e.source})", e) }
                pl("mechanism.cooling.layer.time.min")?.let { e -> set("hardware.filament.cooling.layer.time.min", c.quantity(e, Dimension.TIME, site), "${e.key} (${e.source})", e) }
                // and over air (the arched-bridge calibration plate: half the fan over air on some specimens of a plate)
                pl("mechanism.cooling.overhang_fan")?.let { e -> set("hardware.filament.overhang.fan", c.quantity(e, Dimension.RATIO, site) * 100, "${e.key} (${e.source})", e) }
                // an idle nozzle's drop: its filament's print temperature less its idle one
                val pr = hw("filament", site.filament, "temperature.print"); val idle = hw("filament", site.filament, "temperature.idle")
                if (pr != null && idle != null) set("hardware.filament.temperature.idle_drop", c.quantity(pr, Dimension.TEMPERATURE, site) - c.quantity(idle, Dimension.TEMPERATURE, site), idle.source, pr, idle)
            }
        }
        // the search's zero scales: one per measure, in its unit
        val zs = c.values.values.filter { it.path.size >= 4 && it.path.subList(0, 3) == listOf("method", "search", "zero_scale") }
        if (zs.isNotEmpty()) {
            val text = zs.joinToString(",") { e -> "${e.path.drop(3).joinToString(".")}=${fmt(anyUnit(e))}" }
            for (v in intents.values) v["method.search.zero_scale"] = Sourced(text, zs.map { it.source }.distinct().joinToString(), zs.map { it.key })
        }
        return windowWarnings.toList() to firstLayer
    }

    /** A `plan.mechanism.motion.first_layer.speed` set in a config (not plymeld's bundled stand-in) at [site]: a lock. */
    fun speedLock(c: Config, site: Site): ConfigFiles.Entry? =
        c.scoped("plan", listOf("mechanism", "motion", "first_layer", "speed"), site)?.takeUnless { it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) }

    /** A `plan.mechanism.adhesion.first_layer.height` set in a config (not plymeld's bundled stand-in) at [site]: a lock. */
    fun heightLock(c: Config, site: Site): ConfigFiles.Entry? =
        c.scoped("plan", listOf("mechanism", "adhesion", "first_layer", "height"), site)?.takeUnless { it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) }

    /**
     * The first-layer window at [site] (its filament on the build plate of the machine's loadout) and what it decides there, at the first
     * layer's nozzle temperature (the filament's temperature.first_layer, else temperature.print), for the tool's bore and
     * layer range. No window for the filament: nothing, silently. A window that cannot be used (another plate only, another
     * bore, a temperature outside the measured ones, a lowest good height above what the tool lays) gives a warning instead, and the first layer falls back
     * to the filament's, the machine's, or plymeld's values.
     */
    fun windowAt(c: Config, site: Site): WindowUse {
        val f = site.filament ?: return WindowUse(null, null, null, null)
        val all = c.firstLayerWindows[f] ?: return WindowUse(null, null, null, null)
        val plate = site.plate
        val w = plate?.let { all[it] } ?: return WindowUse(null,
            "filament '$f' has a first-layer window for build plate ${all.keys.joinToString { "'$it'" }}, not for '${plate ?: "(none)"}' (machine '${site.machine}' has it loaded): not used (adhesion depends on the surface)", null, null)
        fun hw(kind: String, key: String?, rest: String) = key?.let { c.entry(listOf("hardware", kind, it) + rest.split('.')) }
        val boreE = hw("tool", site.tool, "bore")
        val bore = boreE?.let { c.quantity(it, Dimension.LENGTH, site) }
        if (bore == null || Math.abs(bore - w.boreMm) > 1e-6)
            return WindowUse(null, "${w.key} (${w.source}) was measured with a ${fmtMm(w.boreMm)} nozzle; tool '${site.tool}' has ${bore?.let { fmtMm(it) + " (" + boreE.key + ")" } ?: "no bore set"}: not used", w, null)
        val tE = hw("filament", f, "temperature.first_layer") ?: hw("filament", f, "temperature.print")
            ?: return WindowUse(null, "${w.key}: filament '$f' has no temperature (temperature.first_layer / .print) to read its first-layer window at: not used", w, null)
        val t = c.quantity(tE, Dimension.TEMPERATURE, site)
        val r = FirstLayerWindow.at(w, t) ?: return WindowUse(null,
            "${w.key} (${w.source}) was measured at ${w.readings.joinToString { fmtC(it.tempC) }}; the first layer prints at ${fmtC(t)} (${tE.key}), outside that: not used", w, t)
        val (lo, hi, names) = firstLayerRange(c, site)
        val range = "${lo?.let { fmtMm(it) } ?: "any"} to ${hi?.let { fmtMm(it) } ?: "any"}, $names"
        // a speed the user set for this filament on this plate: the window gives only the height there
        val plateSpeed = hw("filament", f, "bed.$plate.first_layer.speed")
        if (plateSpeed != null) {
            val sp = c.quantity(plateSpeed, Dimension.SPEED, site)
            val at = FirstLayerWindow.decideAt(r, sp, lo, hi, rangeFrom = names) ?: return WindowUse(null,
                if (sp > r.speedMaxMmS + 1e-9) "${w.key} (${w.source}) at ${fmtC(t)}: ${FirstLayerWindow.mms(sp)} (${plateSpeed.key}) is past its fastest good speed, ${FirstLayerWindow.mms(r.speedMaxMmS)}: the window is not used; the speed stands"
                else "${w.key} (${w.source}) at ${fmtC(t)}: its lowest good height, ${fmtMm(r.heightMinMm)}, is above what tool '${site.tool}' lays ($range): the window is not used; the speed stands", w, t)
            return WindowUse(at, null, w, t)
        }
        val choice = FirstLayerWindow.decide(r, lo, hi, rangeFrom = names) ?: return WindowUse(null,
            "${w.key} (${w.source}) at ${fmtC(t)}: its lowest good height, ${fmtMm(r.heightMinMm)}, is above what tool '${site.tool}' lays as a first layer ($range): not used", w, t)
        return WindowUse(choice, null, w, t)
    }

    /**
     * The first-layer heights the tool at [site] lays (mm, null = not set): its `layer.height.min`, and its
     * `first_layer.height.max` (for the first layer only), else its `layer.height.max`;
     * and which values those are.
     */
    fun firstLayerRange(c: Config, site: Site): Triple<Double?, Double?, String> {
        fun tool(rest: String) = site.tool?.let { c.entry(listOf("hardware", "tool", it) + rest.split('.')) }
        val lo = tool("layer.height.min")?.let { c.quantity(it, Dimension.LENGTH, site) }
        val first = tool("first_layer.height.max")
        val hi = (first ?: tool("layer.height.max"))?.let { c.quantity(it, Dimension.LENGTH, site) }
        return Triple(lo, hi, if (first != null) "its layer.height.min and first_layer.height.max" else "its layer.height.min and layer.height.max; no first_layer.height.max set")
    }

    private fun fmtC(v: Double) = fmt(v) + " C"
    private fun fmtMm(v: Double) = FirstLayerWindow.mm(v)

    /**
     * The entry the first-layer speed comes from at [site] (its object / part, machine, and filament): a
     * `plan.mechanism.motion.first_layer.speed` set in a config (not plymeld's bundled one) locks it; else the
     * filament's `motion.first_layer.speed`; else the machine's `motion.first_layer.speed`; else the bundled plan value,
     * the stand-in for a machine and filament that set none. Null when none of them is set. A first-layer window
     * ([windowAt]) comes between the plan's lock and the filament's value; [derived] puts it there.
     */
    fun firstLayerSpeed(c: Config, site: Site): ConfigFiles.Entry? {
        val plan = c.scoped("plan", listOf("mechanism", "motion", "first_layer", "speed"), site)
        return plan?.takeUnless { it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) }
            // the filament's speed on the plate in use, over its own and the machine's
            ?: site.filament?.let { f -> site.plate?.let { p -> c.entry(listOf("hardware", "filament", f, "bed", p, "first_layer", "speed")) } }
            ?: site.filament?.let { c.entry(listOf("hardware", "filament", it, "motion", "first_layer", "speed")) }
            ?: site.machine?.let { c.entry(listOf("hardware", "machine", it, "motion", "first_layer", "speed")) }
            ?: plan
    }

    /** A number with any unit of length, area, volume, or time, in that dimension's base unit. */
    private fun anyUnit(e: ConfigFiles.Entry): Double {
        for (d in listOf(Dimension.AREA, Dimension.LENGTH, Dimension.VOLUME, Dimension.TIME, Dimension.MASS))
            runCatching { Units.parse(e.value, d, e.key) }.getOrNull()?.takeIf { it.warning == null }?.let { return it.value }
        throw ConfigError(listOf("${e.key} (${e.source}): '${e.value}' is not a number with a unit of area, length, volume, time, or mass"))
    }

    /**
     * The goal weights given at [site], in the goals' order (Schema.GOALS), with where they came from; null when none is given.
     * Measures alone (the bundled layer_lines): the goals keep the catalog's equal weights.
     */
    private fun priorities(c: Config, site: Site): Pair<String, String>? {
        val goals = (Schema.GOALS.map { it to "priorities.$it" } + Schema.MEASURES.map { it to "priorities.measure.$it" })
            .mapNotNull { (g, key) -> c.scoped("job", key.split('.'), site)?.let { g to it } }
        if (goals.isEmpty()) return null
        val given = goals.map { (g, e) -> "$g=${fmt(c.quantity(e, Dimension.WEIGHT, site))}" }
        val withGoals = if (goals.any { it.first in Schema.GOALS }) given else Schema.GOALS.map { "$it=1" } + given
        return withGoals.joinToString(",") to (if (goals.any { it.first in Schema.GOALS }) emptyList() else listOf("plymeld default: equal goal weights"))
            .plus(goals.map { it.second.source }).distinct().joinToString()
    }

    private fun fmt(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
}
