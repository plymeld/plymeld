package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.mesh.StlReader
import plymeld.mesh.ThreeMfPart
import plymeld.mesh.ThreeMfReader
import plymeld.mesh.TriangleSoup
import plymeld.config.OrcaConfig
import plymeld.config.OrcaImport
import plymeld.model.Setup
import plymeld.model.withObjectSetups
import java.nio.file.Path

/**
 * One object from a model file: its parts, the name used in g-code markers, the object's own
 * settings as the file wrote them (a project's model_settings.config keys; empty for STL), and a
 * note for the user.
 */
/**
 * [partNames] and [partSlots]: each part's name and 1-based filament slot, in the order of [parts]
 * (a part's own `extruder`, else its object's, else 1).
 */
class ModelInput(val parts: List<TriangleSoup>, val objectName: String, val note: String?, val settings: Map<String, String> = emptyMap(), val source: String = "",
                 val partNames: List<String> = parts.indices.map { "part ${it + 1}" }, val partSlots: List<Int> = parts.map { 1 },
                 /** Each part's own setting keys as the file wrote them (a project's per-volume keys). */
                 val partSettings: List<Map<String, String>> = parts.map { emptyMap() })

/**
 * [setup] with one object per input, each object's own settings from its model file laid over the
 * intent as that object's overrides (read like the project's own keys: OrcaImport.objectSetup).
 * [report] gets one line per object that has settings of its own.
 */
fun withInputs(setup: Setup, cfg: OrcaConfig, inputs: List<ModelInput>, report: (String) -> Unit): Setup {
    val intent = setup.objects.first().intent
    return setup.withObjectSetups(inputs.map { i ->
        val oi = OrcaImport.objectSetup(cfg, i.source, i.objectName, i.settings, intent)
        val says = oi.setup.overrides.ids.isNotEmpty() || oi.unmapped.isNotEmpty() || oi.setup.spool != "spool 1"
        if (says) report("'${i.objectName}': its own settings from ${i.source}: " +
            oi.setup.overrides.ids.joinToString().ifEmpty { "no setting overrides" } + (if (oi.setup.spool != "spool 1") "; ${oi.setup.spool}" else "") +
            if (oi.unmapped.isEmpty()) "" else "; not mapped (ignored): ${oi.unmapped.joinToString()}")
        // each part on its own spool (the concept model's PartSetup); the object's spool stays the
        // object's own `extruder` (or the first filament)
        val parts = i.partNames.indices.map { k ->
            val pn = i.partNames[k]; val slot = i.partSlots[k]
            setup.spools.getOrNull(slot - 1) ?: throw UsageError("part '$pn' of '${i.objectName}' uses filament $slot, but ${i.source} has ${setup.spools.size} filament slot(s)")
            // the part's own settings over its object's (PartSetup overrides win in Resolver)
            val (over, unmapped) = OrcaImport.partOverrides(cfg, i.source, i.objectName, i.settings, pn, i.partSettings[k])
            if (over.ids.isNotEmpty() || unmapped.isNotEmpty()) report("'${i.objectName}' part '$pn': its own settings: " +
                over.ids.joinToString().ifEmpty { "no setting overrides" } + if (unmapped.isEmpty()) "" else "; not mapped (ignored): ${unmapped.joinToString()}")
            plymeld.model.PartSetup(pn, "spool $slot", over)
        }
        oi.setup.let { o -> plymeld.model.ObjectSetup(o.name, o.intent, o.spool, o.overrides, parts) }
    })
}

/**
 * The objects of a model file. An STL is one object. A 3MF gives one object per build item on one
 * plate: the only plate with objects, or [plate] (1-based, as the project numbers them) when the
 * project has several. Parts other than plain geometry (modifier, negative, support blocker ...)
 * are refused by name: slicing them as solid would print material where the file says there is none.
 */
/**
 * The objects of a model file, read once per file version: a job opened again after an edit (the window) gets the same
 * [ModelInput]s back without parsing the file (their meshes never change after reading). The last few files are kept.
 */
fun loadModel(model: Path, plate: Int? = null): List<ModelInput> {
    val abs = model.toAbsolutePath().normalize()
    val stamp = try { val a = java.nio.file.Files.readAttributes(abs, java.nio.file.attribute.BasicFileAttributes::class.java); "${a.size()}:${a.lastModifiedTime().toMillis()}" } catch (_: Exception) { return readModel(model, plate) }
    val key = "$abs#$plate"
    synchronized(modelCache) { modelCache[key]?.let { (s, v) -> if (s == stamp) return v } }
    val v = readModel(model, plate)
    synchronized(modelCache) { if (modelCache.size >= 4) modelCache.remove(modelCache.keys.first()); modelCache[key] = stamp to v }
    return v
}
private val modelCache = LinkedHashMap<String, Pair<String, List<ModelInput>>>()

private fun readModel(model: Path, plate: Int?): List<ModelInput> = when {
    model.toString().endsWith(".stl", ignoreCase = true) -> {
        if (plate != null) throw UsageError("--plate applies to 3MF projects, not $model")
        listOf(ModelInput(listOf(StlReader.read(model)), model.fileName.toString(), null, source = model.fileName.toString()))
    }
    model.toString().endsWith(".3mf", ignoreCase = true) -> {
        val m = ThreeMfReader.read(model)
        val withObjects = m.plates.filter { it.objectIds.isNotEmpty() }
        val chosen = when {
            plate != null -> m.plates.firstOrNull { it.id == plate }?.also { if (it.objectIds.isEmpty()) throw UsageError("plate $plate of $model has no objects") }
                ?: throw UsageError("$model has no plate $plate (plates: ${m.plates.joinToString { it.id.toString() }.ifEmpty { "none described" }})")
            withObjects.size > 1 -> throw UsageError("$model has ${withObjects.size} plates with objects (" +
                withObjects.joinToString("; ") { p -> "plate ${p.id}: " + m.parts.filter { it.itemObjectId in p.objectIds }.map { it.group ?: it.name }.distinct().joinToString() } +
                "); choose one with --plate")
            else -> withObjects.singleOrNull()
        }
        val parts = if (chosen == null) m.parts else m.parts.filter { it.itemObjectId in chosen.objectIds }
        if (parts.isEmpty()) throw UsageError("$model has no objects to print")
        parts.firstOrNull { it.subtype != "normal_part" }?.let {
            throw UsageError("part '${it.name}' of '${it.group ?: it.name}' in $model is a ${it.subtype}: not supported yet (slicing it as solid would print material the file says is not there)")
        }
        parts.groupBy { it.item }.values.map { ps -> objectInput(ps, model, chosen?.id) }
    }
    else -> throw UsageError("unsupported model type: $model")
}

/**
 * The pauses a project stores for its plate (Metadata/custom_gcode_per_layer.xml, entries of type 1
 * = pause print), as the layer tops they sit at (mm); other kinds (color change, custom g-code) are
 * returned as notes, not applied. [plate]: the plate sliced (null = the only one described).
 */
fun projectPauses(model: Path, plate: Int?): Pair<List<Double>, List<String>> {
    if (!model.toString().endsWith(".3mf", ignoreCase = true)) return emptyList<Double>() to emptyList()
    val xml = java.util.zip.ZipFile(model.toFile()).use { z ->
        z.getEntry("Metadata/custom_gcode_per_layer.xml")?.let { e -> z.getInputStream(e).readBytes() }
    } ?: return emptyList<Double>() to emptyList()
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(java.io.ByteArrayInputStream(xml))
    val plates = doc.getElementsByTagName("plate").let { nl -> (0 until nl.length).map { nl.item(it) as org.w3c.dom.Element } }
    fun idOf(p: org.w3c.dom.Element) = (p.getElementsByTagName("plate_info").item(0) as org.w3c.dom.Element?)?.getAttribute("id")?.toIntOrNull()
    val chosen = when {
        plate != null -> plates.firstOrNull { idOf(it) == plate }
        plates.size == 1 -> plates.single()
        else -> null
    } ?: return emptyList<Double>() to emptyList()
    val zs = ArrayList<Double>(); val notes = ArrayList<String>()
    val layers = chosen.getElementsByTagName("layer")
    for (k in 0 until layers.length) {
        val e = layers.item(k) as org.w3c.dom.Element
        val z = e.getAttribute("top_z").toDoubleOrNull() ?: continue
        if (e.getAttribute("type") == "1") zs.add(z)
        else notes.add("project custom g-code at z $z (type ${e.getAttribute("type")}, '${e.getAttribute("gcode")}') not applied: only pauses are")
    }
    return zs to notes
}

private fun objectInput(ps: List<ThreeMfPart>, model: Path, plate: Int?): ModelInput {
    val first = ps.first()
    val name = first.group ?: first.name
    val partKeys = ps.flatMap { it.partSettings.keys }.toSortedSet() - "extruder"
    // filament slot of each part (a part's own `extruder`, else its object's; 1 when neither says)
    val partSlots = ps.map { p -> (p.partSettings["extruder"] ?: p.objectSettings["extruder"] ?: "1").let { e ->
        e.toIntOrNull()?.takeIf { it >= 1 } ?: throw UsageError("part '${p.name}' of '$name' in $model: extruder '$e' is not a filament slot") } }
    val slots = partSlots.distinct().sorted()
    val notes = listOfNotNull(
        plate?.takeIf { it != 1 }?.let { "plate $it of ${model.fileName}" },
        if (ps.size > 1 && partSlots.distinct().size == 1) "${ps.size} parts (${ps.joinToString { it.name }}) sliced as one body" else if (ps.size > 1) "${ps.size} parts (${ps.joinToString { it.name }})" else null,
        if (slots.size > 1) "multi-color: parts on filaments ${slots.joinToString()}" else null,
        if (partKeys.isNotEmpty()) "part settings: ${partKeys.joinToString()}" else null,
    )
    return ModelInput(ps.map { it.soup }, name, notes.takeIf { it.isNotEmpty() }?.let { "'$name': " + it.joinToString("; ") }, first.objectSettings, model.fileName.toString(),
        ps.map { it.name }, partSlots, ps.map { it.partSettings })
}

/**
 * `--pose`: derive (default) or keep the model's own orientation ("as-given").
 * `--placement`: where it then sits: "center" (bottom on the bed, footprint centered) or "as-given"
 * (the model's own position). Unset: as-given with `--pose as-given`, center with a derived pose.
 */
fun poseOverride(pose: String, placement: String? = null): Pose? {
    if (placement != null && placement != "center" && placement != "as-given")
        throw UsageError("--placement must be 'center' or 'as-given' (got '$placement')")
    return when (pose) {
        "derive" -> if (placement == "as-given") throw UsageError("--placement as-given needs --pose as-given: a derived pose turns the model, so it has no position of its own") else null
        "as-given" -> if (placement == "center") Pose.asGivenCentered() else Pose.asGiven()
        else -> throw UsageError("--pose must be 'derive' or 'as-given' (got '$pose')")
    }
}

/**
 * `--technique`: derive (default; chosen with the pose), one (flat layers of one height), or follow (flat layers following
 * the surfaces).
 */
fun techniqueOverride(s: String): plymeld.core.decide.FlatLayers.Heights? = when (s) {
    "derive" -> null
    "one" -> plymeld.core.decide.FlatLayers.Heights.ONE
    "follow" -> plymeld.core.decide.FlatLayers.Heights.FOLLOW
    else -> throw UsageError("--technique must be 'derive', 'one', or 'follow' (got '$s')")
}

fun overrides(pose: String, placement: String? = null, technique: String = "derive") =
    Plan.Companion.Overrides(pose = poseOverride(pose, placement), flatHeights = techniqueOverride(technique))

/**
 * `--set id=value` for every intent of [setup] (plymeld ids and values; the setting's kind decides the type). An id is the
 * config key that sets it, without the names you give; an old id is refused, naming the new one.
 */
fun applySetOverrides(setup: Setup, sets: List<String>): Setup {
    for (a in sets) {
        val (id, raw) = a.split('=', limit = 2).takeIf { it.size == 2 } ?: throw UsageError("--set wants id=value, got '$a'")
        val def = plymeld.model.Catalog.settings.firstOrNull { it.id == id }
            ?: throw UsageError("--set: no setting '$id' (plymeld investigate config lists them)")
        if (!def.overridable) throw UsageError("--set $id: a ${def.entity.label} setting, not one an object can override (--set sets the print's intent)")
        val v: Any = when (def.kind) {
            plymeld.model.Kind.BOOL -> raw.toBooleanStrictOrNull() ?: throw UsageError("--set $id wants true or false")
            plymeld.model.Kind.CHOICE, plymeld.model.Kind.TEXT -> raw
            else -> raw.toDoubleOrNull() ?: throw UsageError("--set $id wants a number (${def.kind.unit})")
        }
        for (intent in setup.intents.values) intent[id] = plymeld.model.Sourced(v, "command line --set")
    }
    return setup
}
