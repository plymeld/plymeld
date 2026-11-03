package plymeld.model

/**
 * plymeld's concept model. Every setting lives on the
 * entity it physically describes; values carry where they came from; resolution follows one rule
 * per entity with no cross-entity fallbacks.
 */
enum class Entity(val label: String) {
    MACHINE("machine"), TOOL("tool"), MATERIAL("material"), SPOOL("spool"),
    MATERIAL_TOOL("material x tool"), INTENT("intent"),
}

/** What a value is. Numbers are stored in the unit named here (mm, mm/s, ...), never in µm. */
enum class Kind(val unit: String) {
    LENGTH("mm"), SPEED("mm/s"), ACCEL("mm/s²"), TEMP("°C"), COUNT(""), FRACTION("0..1"),
    ANGLE("°"), BOOL(""), CHOICE(""), TEXT(""), TIME("s"), FLOW("mm³/s"), PERCENT("%"), VOLUME("mm³"), AREA("mm²"), DENSITY("g/cm³"),
}

/**
 * One declared setting. [default] is only allowed with a [defaultReason]: plymeld never invents a
 * value silently. Only INTENT settings may be overridden per object or part.
 */
class Setting(
    val id: String,
    val entity: Entity,
    val kind: Kind,
    val meaning: String,
    val default: Any? = null,
    val defaultReason: String? = null,
) {
    init {
        require((default == null) == (defaultReason == null)) { "$id: a default needs a reason, and a reason needs a default" }
    }
    /**
     * Whether an object or part may hold its own value (in its intent / overrides; Resolver takes it first): every setting but
     * the machine's (a nozzle temperature per object: nothing calls for a limit).
     */
    val overridable: Boolean get() = entity != Entity.MACHINE
}

/** A value and where it came from, e.g. "imported: QIDIStudio project part.3mf, key wall_loops". */
/** A value and where it came from; [keys]: the config lines behind it (its own, its `like` chain, a formula's inputs). */
class Sourced(val value: Any, val from: String, val keys: Collection<String> = emptyList())

/** The values of one entity instance: the Q2, tool 0, "Elegoo PLA+", a spool, an intent ... */
class Values(val entity: Entity, val name: String) {
    private val map = LinkedHashMap<String, Sourced>()
    val ids: Set<String> get() = map.keys

    operator fun set(id: String, v: Sourced) {
        val s = Catalog[id]
        require(s.entity == entity || (entity == Entity.INTENT && s.overridable)) {
            "$id belongs to the ${s.entity.label}, not the ${entity.label} '$name'"
        }
        map[id] = v
    }
    operator fun get(id: String): Sourced? = map[id]
}

class Spool(val id: String, val material: String, val tool: Int, val values: Values)
class PartSetup(val name: String, val spool: String, val overrides: Values)
class ObjectSetup(val name: String, val intent: String, val spool: String, val overrides: Values, val parts: List<PartSetup> = emptyList())

/**
 * Everything a slice needs to know about the physical and intended print, as plymeld's entities.
 * [foreignTemplateVars]: g-code templates imported from another slicer are written in that
 * slicer's placeholder language; this is that slicer's variable table, kept only to render them
 * and reported as foreign.
 * [keysOf]: where a setting is set, by its id, as whoever built the setup knows it (plymeld's config: its keys, e.g.
 * `hardware.machine.<key>.model`; an import: the other slicer's keys), for messages; null when not known.
 */
class Setup(
    val machine: Values,
    val tools: List<Values>,
    val materials: Map<String, Values>,
    val spools: List<Spool>,
    val materialTool: Map<Pair<String, Int>, Values>,
    val intents: Map<String, Values>,
    val objects: List<ObjectSetup>,
    val foreignTemplateVars: Map<String, List<String>> = emptyMap(),
    val importReport: ImportReport? = null,
    val keysOf: (String) -> String? = { null },
    /**
     * The plate's own values of the per-object settings: what the config sets for the plate, before any object
     * overrides it, for decisions about the whole plate (the object order's weights); null when the setup does not know them
     * (an import), and the first object's value stands in.
     */
    val plate: Values? = null,
    /** The printer models the setup knows (the shipped ones and a user's own), each machine naming one by its key. */
    val models: plymeld.machine.ModelLibrary = plymeld.machine.ModelLibrary.EMPTY,
    /**
     * The config lines the print has read so far: those behind every value [Resolver] has handed out ([Sourced.keys]), and
     * what the config's own reading added. Shared by the setups made from this one; the g-code's source block tells them
     * from the values the print never read.
     */
    val used: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet(),
    /** Whether a value read now counts in [used]: off while the planner weighs its candidates (what it read to choose is not what the print used). */
    val counting: java.util.concurrent.atomic.AtomicBoolean = java.util.concurrent.atomic.AtomicBoolean(true),
) {
    fun spool(id: String): Spool = spools.firstOrNull { it.id == id } ?: error("no spool '$id'")
}

/** [notes]: import decisions the user should see (e.g. a saved value that is not what gets printed). */
class ImportReport(val source: String, val mapped: Set<String>, val unmapped: Set<String>, val notes: List<String> = emptyList())

/** Which print element a value is wanted for. */
/** [record]: a value read counts as used by the print ([Setup.used]); false for a check that reads every setting. */
class Context(val obj: Int = 0, val part: Int? = null, val record: Boolean = true)

class Resolved(val setting: Setting, val value: Any, val entityName: String, val from: String) {
    override fun toString() = "${setting.id} = $value${if (setting.kind.unit.isNotEmpty() && value !is Boolean && value !is String) " " + setting.kind.unit else ""}  [${setting.entity.label} '$entityName': $from]"
}

/** A setting that is needed has no value: nothing sets it and it has no default. [message] says what to set. */
class MissingValue(message: String) : IllegalStateException(message)

object Resolver {
    /** The value of an OPTIONAL setting, or null when nothing (and no default) gives it one. */
    fun resolveOrNull(setup: Setup, id: String, ctx: Context = Context()): Resolved? =
        if (hasValue(setup, id, ctx)) resolve(setup, id, ctx) else null

    private fun hasValue(setup: Setup, id: String, ctx: Context): Boolean {
        val s = Catalog[id]
        if (s.default != null) return true
        val obj = setup.objects.getOrNull(ctx.obj) ?: error("no object ${ctx.obj}")
        val part = ctx.part?.let { obj.parts.getOrNull(it) ?: error("object '${obj.name}' has no part $it") }
        val spool = setup.spool(part?.spool ?: obj.spool)
        if (part?.overrides?.get(id) != null || obj.overrides[id] != null || setup.intents[obj.intent]?.get(id) != null) return true
        return when (s.entity) {
            Entity.MACHINE -> setup.machine[id] != null
            Entity.TOOL -> setup.tools.getOrNull(spool.tool)?.get(id) != null
            Entity.MATERIAL -> setup.materials[spool.material]?.get(id) != null
            Entity.SPOOL -> spool.values[id] != null
            Entity.MATERIAL_TOOL -> setup.materialTool[spool.material to spool.tool]?.get(id) != null
            Entity.INTENT -> part?.overrides?.get(id) != null || obj.overrides[id] != null || setup.intents[obj.intent]?.get(id) != null
        }
    }

    fun resolve(setup: Setup, id: String, ctx: Context = Context()): Resolved {
        val s = Catalog[id]
        val obj = setup.objects.getOrNull(ctx.obj) ?: error("no object ${ctx.obj}")
        val part = ctx.part?.let { obj.parts.getOrNull(it) ?: error("object '${obj.name}' has no part $it") }
        val spool = setup.spool(part?.spool ?: obj.spool)
        fun from(v: Values): Resolved? = v[id]?.let { if (ctx.record && setup.counting.get()) setup.used.addAll(it.keys); Resolved(s, it.value, v.name, it.from) }
        // a part's or object's own value wins whatever entity the setting lives on (no reason a value such as
        // the nozzle temperature cannot differ per object), then the entity's own
        val own = if (s.entity == Entity.INTENT) null
            else part?.let { from(it.overrides) } ?: from(obj.overrides) ?: setup.intents[obj.intent]?.let { from(it) }
        val found = own ?: when (s.entity) {
            Entity.MACHINE -> from(setup.machine)
            Entity.TOOL -> from(setup.tools.getOrNull(spool.tool) ?: error("spool '${spool.id}' is in tool ${spool.tool}, which the machine does not have"))
            Entity.MATERIAL -> from(setup.materials[spool.material] ?: error("no material '${spool.material}'"))
            Entity.SPOOL -> from(spool.values)
            Entity.MATERIAL_TOOL -> from(setup.materialTool[spool.material to spool.tool]
                ?: error("no material x tool values for '${spool.material}' in tool ${spool.tool}"))
            Entity.INTENT -> part?.let { from(it.overrides) } ?: from(obj.overrides)
                ?: from(setup.intents[obj.intent] ?: error("no intent '${obj.intent}'"))
        }
        return found ?: s.default?.let { Resolved(s, it, "catalog", "plymeld default: ${s.defaultReason}") }
            ?: throw MissingValue(noValue(setup, s, obj.name, mapOf("machine" to setup.machine.name,
                "tool" to setup.tools.getOrNull(spool.tool)?.name, "filament" to spool.material)))
    }

    /**
     * A setting's value for a decision about the whole plate (the object order took the first object's own
     * priorities): the plate's own value ([Setup.plate]), else the catalog default; a setup that does not know the plate's
     * values falls back to the first object's.
     */
    fun resolvePlate(setup: Setup, id: String): Resolved {
        val s = Catalog[id]
        val plate = setup.plate ?: return resolve(setup, id)
        return plate[id]?.let { setup.used.addAll(it.keys); Resolved(s, it.value, plate.name, it.from) }
            ?: s.default?.let { Resolved(s, it, "catalog", "plymeld default: ${s.defaultReason}") }
            ?: resolve(setup, id)
    }

    /**
     * Why [s] has no value for object [obj]: what to set (the setup's key for it, e.g. `hardware.machine.<key>.model`, with
     * the entry in play for `<key>` where [inPlay] names it by kind: `hardware.filament.pla-white.flow`) and the setting's id,
     * both, as a message can know only the id.
     */
    fun noValue(setup: Setup, s: Setting, obj: String, inPlay: Map<String, String?> = emptyMap()): String {
        val keys = setup.keysOf(s.id)?.let { k ->
            Regex("hardware\\.(\\w+)\\.<key>").replace(k) { m -> inPlay[m.groupValues[1]]?.takeIf { Regex("[\\w-]+").matches(it) }?.let { "hardware.${m.groupValues[1]}.$it" } ?: m.value }
        }
        return if (keys == null) "${s.entity.label} setting '${s.id}' (${s.meaning}) has no value for object '$obj'"
        else "$keys has no value for object '$obj' (${s.meaning}; setting ${s.id}, on the ${s.entity.label})"
    }
}

/**
 * Pressure advance by tool (0-based): each spool's filament value if it sets one (hardware.filament.pressure_advance),
 * else its tool's (hardware.tool.pressure_advance), else the machine's (hardware.machine.pressure_advance); tools where
 * none is set are left out (the firmware's own applies). Klipper keeps one value per extruder, so two
 * spools sharing a tool that come to different values are refused rather than one silently winning.
 */
fun Setup.pressureAdvanceByTool(): Map<Int, Double> {
    val byTool = HashMap<Int, Pair<String, Double>>()
    for (sp in spools) {
        val v = ((materialTool[sp.material to sp.tool]?.get("hardware.filament.pressure_advance")
            ?: tools.getOrNull(sp.tool)?.get("hardware.tool.pressure_advance") ?: machine["hardware.machine.pressure_advance"])?.value as Number?)?.toDouble() ?: continue
        val had = byTool[sp.tool]
        require(had == null || had.second == v) { "spools '${had!!.first}' and '${sp.id}' share tool ${sp.tool + 1} but want different pressure advance (${had.second} s, $v s): one value per tool" }
        byTool[sp.tool] = sp.id to v
    }
    return byTool.mapValues { it.value.second }
}

/**
 * The same setup with one object per name, each taking the first object's intent and spool and no
 * overrides of its own (a plate of several objects imported with one settings file).
 */
fun Setup.withObjects(names: List<String>): Setup {
    val base = objects.first()
    return withObjectSetups(names.map { ObjectSetup(it, base.intent, base.spool, Values(Entity.INTENT, "object '$it' overrides")) })
}

/** The same setup with these objects in place of its own. */
fun Setup.withObjectSetups(objs: List<ObjectSetup>): Setup {
    for (o in objs) { spool(o.spool); require(o.intent in intents) { "object '${o.name}': no intent '${o.intent}'" } }
    return Setup(machine, tools, materials, spools, materialTool, intents, objs, foreignTemplateVars, importReport, keysOf, models = models, used = used, counting = counting)
}
