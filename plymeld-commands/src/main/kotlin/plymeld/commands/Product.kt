package plymeld.commands

import com.github.ajalt.clikt.core.UsageError
import plymeld.config.Config
import plymeld.config.ConfigError
import plymeld.config.ConfigFiles
import plymeld.config.ConfigSetup
import plymeld.config.ConfigStack
import plymeld.config.Dimension
import plymeld.config.Fingerprint
import plymeld.config.ModelObject
import plymeld.config.Site
import plymeld.core.decide.PrintTechnique
import plymeld.core.decide.Decision
import plymeld.core.decide.SurfaceTechnique
import plymeld.core.decide.FlatLayers
import plymeld.core.decide.InfillAngle
import plymeld.core.decide.ObjectOrder
import plymeld.core.decide.Plan
import plymeld.core.decide.Pose
import plymeld.core.decide.TechniquePlan
import plymeld.mesh.TriangleSoup
import java.nio.file.Path
import java.util.Locale

/**
 * The product path: models and a layered config in, a plan and a
 * print out. Nothing here reads another slicer's settings or a model file's print settings
 *: a model gives its geometry, its parts, and each part's filament number only.
 */
class Product(given: ConfigStack, modelFiles: List<Path>, machine: String? = null,
              /** Which plate of a 3MF project (1-based, as the project numbers them); null: the only one with objects. */
              plateNumber: Int? = null,
              /**
               * `plymeld plan`: a plan file's lines (the file carrying `plan.source_fingerprint`) are decided again, except
               * the ones written as `"!value"`, which stay locks; `slice` (false) takes every line as a lock, as before.
               */
              private val rePlan: Boolean = false, private val say: (String) -> Unit) {
    private val rePlanned: Pair<ConfigStack, Set<String>> = if (!rePlan) given to given.all.filter { it.keep }.map { it.key }.toSet() else rePlanStack(given, say)
    private val stack: ConfigStack = rePlanned.first
    /** The config the print is made from, and the model files, for the g-code's source block (SourceBlock). */
    internal val usedStack: ConfigStack get() = stack
    /** The config lines this job has read so far: the values the planner and the slice asked for, and what the setup read itself. */
    internal val usedKeys: Set<String> get() = result.setup.used + config.readDirectly
    internal val modelPaths: List<Path> = modelFiles
    /** The keys of the values kept (marked `!`, or in a band, an island, a pose, or a support spot one of whose lines is), for the plan file written back. */
    val keptKeys: Set<String> = rePlanned.second
    /**
     * The boxes of the kept support spots (x0, x1, y0, y1, z0, z1 mm in the posed frame): a spot's number is its survey order, so a kept
     * spot is written back marked by its BOX, wherever it lands in the new numbering.
     */
    private val keptSpotBoxes: List<DoubleArray> = stack.all.filter { it.key in keptKeys && it.path.size == 7 && it.path[0] == "plan" && it.path[1] == "object" && it.path[3] == "support" && it.path[4] == "spot" }
        .groupBy { it.path.take(6) }.values.mapNotNull { es ->
            val (lo, hi) = listOf("min", "max").map { c -> es.firstOrNull { it.path[6] == c }?.value?.let { plymeld.config.Units.point(it, plymeld.config.Dimension.LENGTH, "kept spot").first } }
            if (lo == null || hi == null) null else doubleArrayOf(lo[0], hi[0], lo[1], hi[1], lo[2], hi[2])
        }
    private fun keptSpot(r: plymeld.core.decide.Coordination.Volume.Region) = keptSpotBoxes.any { b ->
        Math.abs(b[0] - r.x0) < 2e-3 && Math.abs(b[1] - r.x1) < 2e-3 && Math.abs(b[2] - r.y0) < 2e-3 && Math.abs(b[3] - r.y1) < 2e-3 && Math.abs(b[4] - r.fromMm) < 2e-3 && Math.abs(b[5] - r.toMm) < 2e-3 }
    /** Each object of the model files, once (a copy is the same model). */
    private val inputs: List<ModelInput> = modelFiles.flatMap { loadModel(it, plateNumber) }
    val config: Config
    val result: ConfigSetup.Result
    /** Each object on the plate, in plate order (copies included), with its model input. */
    val plate: List<Pair<plymeld.config.PlateObject, ModelInput>>

    init {
        inputs.mapNotNull { it.note }.distinct().forEach(say)
        val models = inputs.map { ModelObject(it.objectName, it.partNames, it.partSlots, it.source) }
        try {
            config = Config(stack, models, machine)
            result = ConfigSetup.setup(config)
        } catch (e: ConfigError) {
            throw UsageError("the config has ${e.problems.size} problem(s):\n" + e.problems.joinToString("\n") { "  $it" })
        }
        // every setting the slice needs, for each object and part, before anything is decided: one run names every value to set
        val missing = LinkedHashSet<String>()
        for ((k, o) in result.setup.objects.withIndex()) for (part in listOf<Int?>(null) + o.parts.indices) {
            missing += plymeld.model.SliceSettings.missing(result.setup, plymeld.model.Context(k, part))
        }
        if (missing.isNotEmpty()) throw UsageError("the config has ${missing.size} problem(s):\n" + missing.joinToString("\n") { "  $it" })
        // a kept value in the posed object's frame (a band, a support spot, the infill angle) means nothing in another pose:
        // keeping one keeps the pose too, or it is refused
        if (rePlan) for (e in stack.all.filter { it.key in keptKeys && it.path.size > 4 && it.path[0] == "plan" && it.path[1] == "object" }) {
            val posed = e.path[3] == "band" || e.path[3] == "island" || (e.path[3] == "support" && e.path[4] == "spot") || e.path.drop(3) == listOf("layer", "infill", "angle")
            // the pose is fixed when a surviving entry locks it: kept with !, in a file of the user's own, or plan.pose = "as-given"
            val poseLocked = stack.all.any { it.path == listOf("plan", "object", e.path[2], "pose", "turn") } || stack.all.any { it.path == listOf("plan", "pose") }
            if (posed && !poseLocked) throw UsageError("${e.key} (${e.source}) is kept (!) but the object's pose is not locked: a band, an island, a support spot, and the infill angle " +
                "are in the posed object's frame, so keep ${ConfigFiles.pathText(listOf("plan", "object", e.path[2], "pose", "turn"))} too (\"!...\"), lock the pose in your config, or drop the ! to have it decided again")
        }
        // a keep mark outside a plan file (a file of the user's own, -cv) is a lock as any value: the mark decides nothing there
        run {
            val planFiles = planFilesOf(given)
            for (e in given.all.filter { it.keep && fileOf(it.source) !in planFiles }) say("warning: ${e.key} (${e.source}) is marked ! outside a plan file: it is a lock as any value there; the mark keeps a line of a plan file across re-plans and has no other effect")
        }
        result.notes.forEach { say("config: $it") }
        result.warnings.forEach { say("warning: $it") }
        plate = config.objects.map { o -> o to inputs[models.indexOf(o.model)] }
        val flavor = plymeld.model.Resolver.resolve(result.setup, "hardware.machine.firmware.flavor").value
        if (flavor != "klipper") throw UsageError("only klipper g-code is written so far (hardware.machine.<key>.firmware.flavor = '$flavor')")
    }

    /** Locks from the config's plan (and job.allowed_techniques), for the decisions. */
    fun overrides(): Plan.Companion.Overrides {
        fun plan(rest: String) = config.entry(("plan.$rest").split('.'))?.value as String?
        val pose = when (plan("pose")) {
            "as-given" -> if (plan("placement") == "center") Pose.asGivenCentered() else Pose.asGiven()
            else -> { if (plan("placement") == "as-given") throw UsageError("plan.placement = \"as-given\" needs plan.pose = \"as-given\": a decided pose turns the model, so it has no position of its own"); null }
        }
        @Suppress("UNCHECKED_CAST")
        val allowed = (config.entry("job.allowed_techniques".split('.'))?.value as List<String>?)?.map(::technique)
        val order = when (plan("objects.printing")) { "together" -> ObjectOrder.TOGETHER; "one_at_a_time" -> ObjectOrder.ONE_AT_A_TIME; else -> null }
        // flat layers are the only technique the search tries, so allowing only them locks nothing
        return Plan.Companion.Overrides(pose = pose, technique = allowed?.singleOrNull()?.takeIf { it != PrintTechnique.FLAT_LAYERS }, order = order,
            sequenceAsGiven = plan("objects.order") == "as_given",
            objects = plate.map { (o, _) -> locks(o) },
            decideLayerHeight = true, decidedInfillAngle = plate.map { (o, _) -> !lockedInfillAngle(o) },
            weighedSurfaceTechniques = plate.map { (o, _) -> unlockedSurfaceTechniques(o) })
    }

    /** Whether the job locks nothing under `job.<key>` for [o], neither for the object nor for any of its parts. */
    private fun unlocked(o: plymeld.config.PlateObject, key: String): Boolean {
        val rest = key.split('.')
        return config.scoped("plan", rest, Site(obj = o)) == null && o.partKeys.none { p -> config.scoped("plan", rest, Site(obj = o, part = p)) != null }
    }

    /**
     * The surface techniques the search may weigh against as laid for [o]: those its surfaces have no lock
     * for (`plan.faces.flat_top.technique`, `.sloped_top`, `.wall`, at the object or any part). A lock, `as_laid` included, locks.
     */
    private fun unlockedSurfaceTechniques(o: plymeld.config.PlateObject): Set<SurfaceTechnique> = setOfNotNull(
        if (unlocked(o, "faces.flat_top.technique")) SurfaceTechnique.IRONING else null,
        if (unlocked(o, "faces.sloped_top.technique")) SurfaceTechnique.Z_ANTIALIASING else null,
        if (unlocked(o, "faces.under.technique")) SurfaceTechnique.WAVE_OVERHANG else null,
        if (unlocked(o, "faces.side.technique")) SurfaceTechnique.FUZZY_SKIN else null)

    /** How flat layers set [o]'s heights (plan.technique.flat.heights, the object's else the general one): null = decided. */
    private fun flatHeights(o: plymeld.config.PlateObject): FlatLayers.Heights? =
        when (config.scoped("plan", listOf("technique", "flat", "heights"), Site(obj = o))?.value) {
            "one" -> FlatLayers.Heights.ONE; "follow" -> FlatLayers.Heights.FOLLOW; else -> null
        }

    private fun technique(s: String) = when (s) {
        "flat" -> PrintTechnique.FLAT_LAYERS
        // locked only, for now: no survey proposes it yet
        "curved" -> PrintTechnique.CURVED_LAYERS
        "underside" -> PrintTechnique.CURVED_UNDERSIDE
        "bridge" -> PrintTechnique.LIFTED_BRIDGE
        else -> throw UsageError("no technique '$s'")
    }

    /** One object's locks: `plan.object.<key>.pose.turn` and its bands (the copy's own, else the object's). */
    private fun locks(o: plymeld.config.PlateObject): Plan.Companion.ObjectLocks {
        fun at(vararg rest: String) = listOf(o.key, o.base).distinct().firstNotNullOfOrNull { k -> config.entry(listOf("plan", "object", k) + rest) }
        val pose = at("pose", "turn")?.let { e ->
            Pose((at("pose", "label")?.value as String?) ?: "locked", (e.value as List<*>).map { (it as Number).toDouble() }.toDoubleArray(), doubleArrayOf(0.0, 0.0, 0.0))
        }
        val keys = listOf(o.key, o.base).distinct().firstOrNull { k -> config.values.values.any { it.path.size >= 6 && it.path.subList(0, 4) == listOf("plan", "object", k, "band") } }
        val techniques = keys?.let { k ->
            val bands = config.values.values.filter { it.path.size >= 6 && it.path.subList(0, 4) == listOf("plan", "object", k, "band") }.groupBy { it.path[4] }
            val site = Site(obj = o)
            fun len(e: ConfigFiles.Entry?) = e?.let { config.quantity(it, Dimension.LENGTH, site) }
            // a curved band with depth = "auto": its start worked out on the posed object (CurvedDepth); the only band
            val auto = bands.filter { (_, es) -> es.any { it.path[5] == "depth" && it.value == "auto" } }.keys
            if (auto.isNotEmpty()) {
                val n = auto.single()
                val es = bands.getValue(n)
                if (bands.size > 1 || es.any { it.path[5] == "from" || it.path[5] == "to" } || es.firstOrNull { it.path[5] == "technique" }?.value != "curved")
                    throw UsageError("plan.object.$k.band.$n.depth = \"auto\" is for a curved band on its own (no from, to, or other bands): plymeld splits it into flat layers below and the curved band above")
                return@let TechniquePlan(listOf(TechniquePlan.Banded(0.0, Double.POSITIVE_INFINITY, PrintTechnique.CURVED_LAYERS)), curvedDepthAuto = true) to null
            }
            // a flat band says its heights: "one" with band.<n>.layer.height (or the object's locked
            // plan.technique.flat.layer.height, which every flat band of one height takes), or "follow" without one
            val bandHeights = LinkedHashMap<String, Double>()
            val objectHeight by lazy { lockedLayerHeight(o) }
            val list = bands.map { (n, es) ->
                fun f(name: String) = es.firstOrNull { it.path.drop(5).joinToString(".") == name }
                val band = "plan.object.$k.band.$n"
                val s = f("technique")?.value as String? ?: throw UsageError("$band has no technique")
                val t = technique(s)
                val h = len(f("layer.height"))
                val heights = f("heights")?.value as String?
                if (t != PrintTechnique.FLAT_LAYERS) {
                    if (h != null) throw UsageError("$band.layer.height is for a flat band (this one is '$s')")
                    if (heights != null) throw UsageError("$band.heights is for a flat band (this one is '$s')")
                }
                val follow = t == PrintTechnique.FLAT_LAYERS && when (heights) {
                    null -> throw UsageError("$band is flat but does not say how its layers' heights are set: write $band.heights = \"one\" " +
                        "with $band.layer.height for one layer height throughout the band, or $band.heights = \"follow\" for layers each as thick as the " +
                        "surfaces they cross allow (a flat band without heights used to follow the surfaces)")
                    "follow" -> if (h != null) throw UsageError("$band.heights = \"follow\" with $band.layer.height: a band whose layers follow the surfaces has " +
                        "no one layer height; leave layer.height out, or write heights = \"one\" to keep that height") else true
                    else -> {
                        if (h == null && objectHeight == null) throw UsageError("$band.heights = \"one\" without a layer height: write $band.layer.height, or " +
                            "lock the object's plan.technique.flat.layer.height (every flat band of one height in an object takes it)")
                        false
                    }
                }
                if (h != null) bandHeights[n] = h
                TechniquePlan.Banded(len(f("from")) ?: 0.0, len(f("to")) ?: Double.POSITIVE_INFINITY, t, follow = follow)
            }.sortedBy { it.lo }
            if (bandHeights.values.distinct().size > 1) throw UsageError("plan.object.$k.band: flat bands of different layer heights (" +
                bandHeights.entries.joinToString { "band.${it.key} ${mmText(it.value)}" } + ") are not planned yet: every flat band of one height in an object has the same height, for now")
            try { TechniquePlan(list) to bandHeights.values.firstOrNull() } catch (e: IllegalArgumentException) {
                throw UsageError("plan.object.$k.band: ${e.message} (" + list.joinToString { "%.3f-%s %s".format(Locale.ROOT, it.lo, if (it.hi.isInfinite()) "top" else "%.3f".format(Locale.ROOT, it.hi), it.short) } + ")")
            }
        }
        val locked = lockedLayerHeight(o)
        val bandHeight = techniques?.second
        if (bandHeight != null && locked != null && Math.abs(bandHeight - locked) > 1e-9) throw UsageError(
            "'${o.key}': its flat bands' layer height (${mmText(bandHeight)}) differs from plan.technique.flat.layer.height (${mmText(locked)}): the bands print with one, so give them the same")
        val h = bandHeight ?: locked
        // a plan file's spots carry the config's locked spots already (planText writes them first): with them given, the config's are
        // not added again (they would double)
        val planSpots = lockedSupportSpots(o)
        val seamLock = config.scoped("plan", listOf("seam"), Site(obj = o))?.let { (it.value as String) to "${it.key} (${it.source})" }
        val plainStyle = config.scoped("plan", listOf("technique", "scarf_seam", "plain_style"), Site(obj = o))?.value as String?
        val bondLock = config.scoped("plan", listOf("bond_cooling"), Site(obj = o))?.let { e -> when (e.value) { "on" -> true; "off" -> false; else -> null }?.let { it to "${e.key} (${e.source})" } }
        return Plan.Companion.ObjectLocks(pose, techniques?.first?.at(h), h, flatHeights(o), if (planSpots != null) emptyList() else lockedSpots(o), planSpots, inserts(o),
            seam = seamLock, plainStyle = plainStyle, bondCooling = bondLock, islands = islands(o))
    }

    /** The curved islands a plan locks for [o] (`plan.object.<key>.island.<key>`: x and y required, start and layer.height optional). */
    private fun islands(o: plymeld.config.PlateObject): List<plymeld.core.decide.CurvedIsland.Lock> {
        val k = listOf(o.key, o.base).distinct().firstOrNull { k -> config.values.values.any { it.path.size >= 6 && it.path.subList(0, 4) == listOf("plan", "object", k, "island") } }
            ?: return emptyList()
        val site = Site(obj = o)
        return config.values.values.filter { it.path.size >= 6 && it.path.subList(0, 4) == listOf("plan", "object", k, "island") }.groupBy { it.path[4] }.map { (n, es) ->
            fun len(name: String) = es.firstOrNull { it.path.drop(5).joinToString(".") == name }?.let { config.quantity(it, Dimension.LENGTH, site) }
            val head = "plan.object.$k.island.$n"
            plymeld.core.decide.CurvedIsland.Lock(n, len("x") ?: throw UsageError("$head has no x (the point on its cap, from the posed object's corner)"),
                len("y") ?: throw UsageError("$head has no y (the point on its cap, from the posed object's corner)"), len("start"), len("layer.height"))
        }
    }

    /** The six sides of a box under [head] (`<head>.min` / `.max`, its corners as points), x0, x1, y0, y1, z0, z1; or a UsageError naming what is missing. */
    private fun box(head: List<String>, es: List<ConfigFiles.Entry>): DoubleArray {
        val missing = listOf("min", "max").filter { n -> es.none { it.path.last() == n } }
        if (missing.isNotEmpty()) throw UsageError("${ConfigFiles.pathText(head)}: a box needs min and max (its corners, \"x y z mm\"); missing ${missing.joinToString()}")
        val (lo, hi) = listOf("min", "max").map { n -> es.first { it.path.last() == n }.let { plymeld.config.Units.point(it.value, Dimension.LENGTH, it.key).first } }
        return doubleArrayOf(lo[0], hi[0], lo[1], hi[1], lo[2], hi[2])
    }

    /**
     * The spots the user forces support on for [o]: `plan.support.locked.<key>` for every object, and
     * `plan.object.<key>.support.locked.<key>` for this one (its copy's or its own); each a box in the model's own coordinates.
     */
    private fun lockedSpots(o: plymeld.config.PlateObject): List<plymeld.core.decide.LockedSpot> {
        // the sets add up across scopes (every box applies); a spot keyed alike at two scopes is the narrowest scope's (it was
        // the general one's, against the "narrower scope wins" rule)
        val heads = listOf(o.key, o.base).distinct().map { listOf("plan", "object", it, "support", "locked") } + listOf(listOf("plan", "support", "locked"))
        return heads.flatMap { head ->
            config.values.values.filter { it.path.size == head.size + 2 && it.path.subList(0, head.size) == head }.groupBy { it.path[head.size] }.map { (k, es) ->
                val b = box(head + k, es)
                try { plymeld.core.decide.LockedSpot(k, b[0], b[1], b[2], b[3], b[4], b[5], ConfigFiles.pathText(head + k) + " (${es.first().source})") }
                catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "bad locked spot") }
            }
        }.distinctBy { it.key }
    }

    /**
     * The planned inserts for [o]: `plan.insert.<key>` for every object and `plan.object.<key>.insert.<key>` for this one, each a
     * box in the model's own coordinates with the insert's top (`top`, unset: the box's top). They need the pose locked (plan.pose =
     * "as-given" or the object's pose.turn): the socket must keep opening upward, and the search would turn the model.
     */
    private fun inserts(o: plymeld.config.PlateObject): List<plymeld.core.decide.Insert> {
        val heads = listOf(o.key, o.base).distinct().map { listOf("plan", "object", it, "insert") } + listOf(listOf("plan", "insert"))
        val found = heads.flatMap { head ->
            config.values.values.filter { it.path.size == head.size + 2 && it.path.subList(0, head.size) == head }.groupBy { it.path[head.size] }.map { (k, es) ->
                val b = box(head + k, es.filter { it.path.last() != "top" })
                val top = es.firstOrNull { it.path.last() == "top" }?.let { config.quantity(it, Dimension.LENGTH, Site(obj = o)) } ?: b[5]
                try { plymeld.core.decide.Insert(k, b[0], b[1], b[2], b[3], b[4], b[5], top, ConfigFiles.pathText(head + k) + " (${es.first().source})") }
                catch (e: IllegalArgumentException) { throw UsageError(e.message ?: "bad insert") }
            }
        }.distinctBy { it.key }
        if (found.isNotEmpty()) {
            val poseLocked = listOf(o.key, o.base).distinct().any { k -> config.entry(listOf("plan", "object", k, "pose", "turn")) != null } || config.entry(listOf("plan", "pose"))?.value == "as-given"
            if (!poseLocked) throw UsageError("planned insert '${found.first().key}' (${found.first().source}): its socket must keep opening upward, so the pose cannot be decided; " +
                "set plan.pose = \"as-given\" or lock plan.object.${o.key}.pose.turn (a turn about z only)")
        }
        return found
    }

    /** The support spots a plan locks for [o] (`plan.object.<key>.support.spot.<key>`, what `plan -o` wrote), or null when none. */
    private fun lockedSupportSpots(o: plymeld.config.PlateObject): List<plymeld.core.decide.SupportPlan.Spot>? {
        val head = listOf(o.key, o.base).distinct().map { listOf("plan", "object", it, "support", "spot") }
            .firstOrNull { h -> config.values.values.any { it.path.size == h.size + 2 && it.path.subList(0, h.size) == h } } ?: return null
        return config.values.values.filter { it.path.size == head.size + 2 && it.path.subList(0, head.size) == head }.groupBy { it.path[head.size] }.map { (k, es) ->
            val b = box(head + k, es.filter { it.path.last() != "order" })
            val order = when (val v = es.firstOrNull { it.path.last() == "order" }?.value) {
                null, "interleaved" -> plymeld.core.decide.Coordination.Order.INTERLEAVED
                "first" -> plymeld.core.decide.Coordination.Order.FIRST
                else -> throw UsageError("${ConfigFiles.pathText(head + k)}.order = \"$v\": interleaved or first")
            }
            // a flat underside is a region of no height (min's z = max's z): what the plan writes for it
            if (b[0] >= b[1] || b[2] >= b[3] || b[4] > b[5]) throw UsageError("${ConfigFiles.pathText(head + k)}: min must be below max on every axis (z may be equal: a flat underside)")
            plymeld.core.decide.SupportPlan.Spot(k, plymeld.core.decide.Coordination.Volume.Region(b[4], b[5], b[0], b[1], b[2], b[3]), false, order,
                "the bed or the model (as the plan found it)", b[4], why = "locked by the plan (${es.first().source})")
        }
    }

    private fun mmText(v: Double) = String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.') + " mm"

    /**
     * The keys the plan writes object [k]'s support spots under:
     * the part whose box, as placed, holds the spot's top center, where the object has several parts; else the box's rounded
     * center, `at_x<x>_y<y>_z<z>` (whole mm, in the spot's own frame: x, y from the posed object's corner, z from its bottom).
     * Two spots alike get `_2`, `_3`. A part name that is not a bare TOML key is quoted. The box stays the spot's identity
     * (a kept spot is found by its box, never by its name): the name is for reading.
     */
    private fun spotNames(p: Plan, k: Int, regions: List<plymeld.core.decide.Coordination.Volume.Region>): List<String> {
        val placed = p.layout.placed.getOrNull(p.layout.objectIndex.indexOf(k))
        val names = plate[k].second.partNames
        val bounds = placed?.parts?.map { it.bounds() }
        val ox = bounds?.minOf { it[0] } ?: 0.0; val oy = bounds?.minOf { it[1] } ?: 0.0; val oz = bounds?.minOf { it[2] } ?: 0.0
        val out = ArrayList<String>()
        for (r in regions) {
            val cx = (r.x0 + r.x1) / 2; val cy = (r.y0 + r.y1) / 2; val cz = r.toMm
            var name: String? = null
            if (bounds != null && bounds.size > 1 && names.size == bounds.size) {
                val hit = bounds.indices.filter { i -> val b = bounds[i]
                    cx >= b[0] - ox - 1e-6 && cx <= b[3] - ox + 1e-6 && cy >= b[1] - oy - 1e-6 && cy <= b[4] - oy + 1e-6 && cz >= b[2] - oz - 0.5 && cz <= b[5] - oz + 0.5 }
                    .minByOrNull { i -> val b = bounds[i]; (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]) }
                if (hit != null) name = Config.safeKey(names[hit]).let { if (Regex("[A-Za-z0-9_-]+").matches(it)) it else "\"${it.replace("\"", "'")}\"" }
            }
            if (name == null) name = "at_x%d_y%d_z%d".format(Locale.ROOT, Math.round(cx), Math.round(cy), Math.round(cz))
            var unique = name; var n = 2
            while (unique in out) { unique = if (name.endsWith("\"")) name.dropLast(1) + "_$n\"" else "${name}_$n"; n++ }
            out += unique
        }
        return out
    }

    /**
     * The flat layer height the user's config sets for [o] (the object's, else the general one), in
     * mm: a lock. plymeld's bundled value is only a stand-in, so it locks nothing: the search decides.
     */
    private fun lockedLayerHeight(o: plymeld.config.PlateObject): Double? {
        val e = config.scoped("plan", listOf("technique", "flat", "layer", "height"), Site(obj = o)) ?: return null
        if (e.source.startsWith(ConfigFiles.BUNDLED_SOURCE)) return null
        val tool = result.setup.spool(result.setup.objects[plate.indexOfFirst { it.first === o }].spool).tool
        val toolKey = plymeld.config.ConfigSetup.slots(config, config.machine ?: "", ArrayList())
            .firstOrNull { it.toolIndex - 1 == tool }?.tool
        return config.quantity(e, Dimension.LENGTH, Site(obj = o, tool = toolKey, machine = config.machine))
    }

    /**
     * The tool (0-based) the config's loadout assigns each placed part, in the order of [Plan.parts] (plymeld.config.LoadoutTools):
     * the pre-print check's reference for which tool prints which part.
     */
    fun partTools(p: Plan): List<Int> {
        val byObject = plymeld.config.LoadoutTools.byObject(config)
        return p.layout.placed.withIndex().flatMap { (j, placed) ->
            val o = plate[p.layout.objectIndex[j]].first
            byObject.getValue(o.key).also { check(it.size == placed.parts.size) { "'${o.key}' has ${it.size} parts in the config but ${placed.parts.size} placed" } }
        }
    }

    /** Whether the user's config (not plymeld's bundled stand-in) sets [o]'s infill direction: a lock. */
    private fun lockedInfillAngle(o: plymeld.config.PlateObject): Boolean =
        config.scoped("plan", listOf("layer", "infill", "angle"), Site(obj = o))?.let { !it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) } ?: false

    /** Each placed object's decided infill direction (deg) and its long axis, by setup index; locked objects left out. */
    private fun infillAngles(p: Plan): Map<Int, Pair<Double, Double>> = p.layout.placed.withIndex().mapNotNull { (j, pl) ->
        val k = p.layout.objectIndex[j]
        if (lockedInfillAngle(plate[k].first)) null else k to (InfillAngle.degrees(pl.parts) to InfillAngle.longAxisDeg(pl.parts))
    }.toMap()

    /** Plan values the user's config gives (not plymeld's bundled ones, not fingerprints). */
    private val userPlan = config.values.values.filter { it.path[0] == "plan" && !it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) && it.path.getOrNull(1) != "source_fingerprint" }

    /** Every decision locked: each object's pose and bands, and the object order. */
    val planComplete: Boolean get() = overrides().let { o -> o.order != null && o.objects.all { it.pose != null && it.techniques != null } }

    /**
     * Before slicing: no plan values at all, or a plan made from
     * another model or config, is said; a complete plan is used as it is.
     */
    fun checkPlan() {
        if (userPlan.isEmpty()) { say("no plan given: deciding now; run `plymeld plan -o FILE` to see and keep the decisions"); return }
        val fps = config.fingerprints()
        val made = config.values.values.filter { it.path.size == 4 && it.path.subList(0, 3) == listOf("plan", "source_fingerprint", "config") }
        val changed = made.filter { fps[it.path[3]] != it.value }.map { it.path[3] } + fps.keys.filter { s -> made.isNotEmpty() && made.none { it.path[3] == s } }
        if (changed.isNotEmpty()) say("warning: the config changed since this plan was made (${changed.distinct().joinToString()}); its values still apply as locks — re-run `plymeld plan -o` to re-decide")
        for ((o, input) in plate) {
            val e = config.entry(listOf("plan", "source_fingerprint", "model", o.key)) ?: continue
            if (e.value != geometry(input.parts)) say("warning: the model of '${o.key}' changed since this plan was made (${e.source}); its values still apply as locks — re-run `plymeld plan -o` to re-decide")
        }
        say(if (planComplete) "plan: complete in the config, used as it is (not decided again)" else "plan: partly given; deciding the rest around it")
    }

    /**
     * The decisions; a decided flat layer height, and the surface technique of the flat tops where the search weighed it, go into
     * each object's settings, so the slice prints what was decided (and the plan file, `plan -o`, locks them).
     */
    fun decide(locks: Plan.Companion.Overrides = overrides()): Plan {
        // what the planner reads to weigh its candidates is not what the print uses: the slice that writes the file counts
        result.setup.counting.set(false)
        val p = try { Plan.makeFor(plate.map { (o, i) -> o.key to i.parts }, result.setup, locks) } finally { result.setup.counting.set(true) }
        val techniques = p.decisions.filter { it.value is TechniquePlan }
        for ((k, ob) in plate.withIndex()) {
            val h = (techniques.getOrNull(k)?.value as TechniquePlan?)?.layerHeightMm ?: continue
            result.setup.intents.getValue(ob.first.key)["plan.technique.flat.layer.height"] = plymeld.model.Sourced(h, "decided: the plan's flat layer height")
        }
        for ((k, ob) in plate.withIndex()) {
            if (SurfaceTechnique.IRONING !in (p.weighed.getOrNull(k) ?: emptySet())) continue
            val on = SurfaceTechnique.IRONING in p.surfaceTechniques[k]
            result.setup.intents.getValue(ob.first.key)["plan.technique.ironing.type"] = plymeld.model.Sourced(if (on) "top" else "none",
                "decided: the flat tops' surface technique, weighed against as laid (no plan.faces.flat_top.technique lock): " + (if (on) "ironing" else "as laid"))
        }
        // the infill direction: 45 deg to each object's long axis as posed (InfillAngle), unless the user's config sets it
        for ((k, a) in infillAngles(p)) result.setup.intents.getValue(plate[k].first.key)["plan.layer.infill.angle"] =
            plymeld.model.Sourced(a.first, "decided: 45 deg to the object's long axis as posed (${a.second} deg)")
        // the view on the model's own side, turned with each object's pose: the slice's seams read it
        val poses = p.decisions.filter { it.value is Pose }.map { it.value as Pose }
        if (poses.size == plate.size) for ((k, v) in Plan.posedViews(result.setup, poses).withIndex())
            if (v != null) result.setup.intents.getValue(plate[k].first.key)["plan.view_from"] = plymeld.model.Sourced(v, Plan.POSED_VIEW_WHY)
        // bond-first cooling, the seams, and the in-island retraction, decided per object in the plan
        // (ObjectDecisions): the decided values go into the object's settings, so the slice prints what was decided and
        // `plan -o` writes them as locks; a locked one is in the config already
        for ((k, d) in p.bonds.withIndex()) if (d != null && d.value.on) {
            val v = result.setup.intents.getValue(plate[k].first.key)
            val c = d.value.conditions
            val why = "decided: bond-first cooling (" + (if (d.overridden) "plan.bond_cooling = on" else "weighed by the job's weights") + ")"
            v["hardware.filament.cooling.fan"] = plymeld.model.Sourced(c.fanPct, why)
            v["hardware.filament.cooling.layer.time.min"] = plymeld.model.Sourced(c.minLayerTimeS, why)
            v["hardware.filament.temperature.print"] = plymeld.model.Sourced(c.nozzleC, why)
        }
        for ((k, d) in p.seams.withIndex()) if (d != null && !d.overridden) {
            val v = result.setup.intents.getValue(plate[k].first.key)
            val why = "decided: the seams, weighed by the job's weights (seam_scar against the scarf's extra pass; no plan.seam lock): ${d.value.words()}"
            // the pipeline's two seam settings (the lock plan.seam and scarf_seam.plain_style are what ConfigSetup makes these from)
            v["plan.seam_start"] = plymeld.model.Sourced(d.value.placement.start, why)
            v["plan.technique.scarf_seam.where"] = plymeld.model.Sourced(d.value.scarf, why)
        }
        for ((k, d) in p.retractions.withIndex()) if (d != null && !d.overridden)
            result.setup.intents.getValue(plate[k].first.key)["plan.mechanism.motion.no_retract_in_island"] = plymeld.model.Sourced(d.value,
                "decided: the in-island retraction, weighed by the job's weights on the object's own program (string_risk, restart_starved; plan.mechanism.motion.no_retract_in_island unset)")
        return p
    }

    /**
     * The first layers a first-layer window decided (ConfigSetup.FirstLayerNote), as (step, why) for `plan` and `slice`:
     * the step names the object (and the part, where its filament makes it differ).
     */
    fun firstLayerLines(): List<Pair<String, String>> = result.firstLayer.map { n ->
        val what = listOfNotNull(n.heightMm?.let { plymeld.config.FirstLayerWindow.mm(it) }, n.speedMmS?.let { "at " + plymeld.config.FirstLayerWindow.mms(it) }).joinToString(" ")
        "first layer ('${n.obj}'${n.part?.let { ", part '$it'" } ?: ""})" to (if (what.isEmpty()) "" else "$what, ") + n.why
    }

    /** The pauses asked for, as the slicer takes them: an object by its key, a part by its name. */
    fun pauses(): List<plymeld.core.PauseLock> = result.pauses.map { p ->
        when (p.kind) {
            "at" -> plymeld.core.PauseLock.AtZ(p.value as Double)
            else -> {
                val key = p.value as String
                val name = plate.firstNotNullOfOrNull { (o, i) -> o.partKeys.indexOf(key).takeIf { it >= 0 }?.let { i.partNames[it] } } ?: key
                if (p.kind == "before") plymeld.core.PauseLock.Before(name) else plymeld.core.PauseLock.After(name)
            }
        }
    }

    /**
     * The decisions as a config file: every decision a value under
     * `plan`, with the reason as a comment, plus what it was made from.
     */
    /** The plan as a config file, the kept values marked `!`. */
    fun planFile(p: Plan): String = withKeepMarks(planText(p), keptKeys.filter { ".support.spot." !in it }.toSet())

    private fun planText(p: Plan): String = buildString {
        fun num(v: Double) = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else v.toString()
        fun mm(v: Double) = "\"%s mm\"".format(Locale.ROOT, String.format(Locale.ROOT, "%.6f", v).trimEnd('0').trimEnd('.'))
        fun comment(text: String) = text.lines().joinToString("\n") { "# $it" }
        append("# Written by `plymeld plan`: the decisions it made. For `plymeld slice` every value here is a LOCK: it prints\n")
        append("# as planned and decides nothing again. For `plymeld plan` every value here is decided AGAIN, except one you\n")
        append("# write as \"!value\": that one is kept (a kept band, island, support spot, or infill angle keeps the pose too).\n\n")
        val order = p.order
        append("[plan]\n")
        append(comment(order.why) + "\n")
        append("objects.printing = \"${if (p.layout.order == ObjectOrder.TOGETHER) "together" else "one_at_a_time"}\"\n")
        config.entry("plan.pose".split('.'))?.takeIf { it.value == "as-given" }?.let { append("pose = \"as-given\"\n") }
        config.entry("plan.placement".split('.'))?.let { append("placement = \"${it.value}\"\n") }
        val poses = p.decisions.filter { it.value is Pose }
        val techniques = p.decisions.filter { it.value is TechniquePlan }
        val angles = infillAngles(p)
        for ((k, ob) in plate.withIndex()) {
            val o = ob.first
            @Suppress("UNCHECKED_CAST") val pose = poses[k] as Decision<Pose>
            @Suppress("UNCHECKED_CAST") val st = techniques[k] as Decision<TechniquePlan>
            append("\n[${ConfigFiles.pathText(listOf("plan", "object", o.key))}]\n")
            append(comment(pose.why) + "\n")
            append("pose.label = \"${pose.value.label.replace("\"", "'")}\"\n")
            append("pose.turn = \"${pose.value.r.joinToString(" ") { num(it) }}\"\n")
            append(comment(st.why) + "\n")
            st.value.layerHeightMm?.let { append("technique.flat.layer.height = ${mm(it)}\n") }
            // the first layer as this slice lays it: its height was worked out before the layer height was decided, and slicing
            // with this plan (the decided height set) would work it out again from that
            (plymeld.model.Resolver.resolveOrNull(result.setup, "plan.mechanism.adhesion.first_layer.height", plymeld.model.Context(k))?.value as Number?)
                ?.toDouble()?.let { append("mechanism.adhesion.first_layer.height = ${mm(it)}\n") }
            for ((i, b) in st.value.bands.withIndex()) {
                val n = i + 1
                append("band.$n.technique = \"${b.technique.short}\"\n")
                // a flat band says its heights: one height with its layer height, or following the surfaces with none
                // (the settings' layer height where none was decided, so reading the plan back keeps the band at one height)
                if (b.follow) append("band.$n.heights = \"follow\"\n")
                if (b.oneHeight) {
                    append("band.$n.heights = \"one\"\n")
                    (st.value.layerHeightMm ?: (plymeld.model.Resolver.resolveOrNull(result.setup, "plan.technique.flat.layer.height", plymeld.model.Context(k))?.value as Number?)?.toDouble())
                        ?.let { append("band.$n.layer.height = ${mm(it)}\n") }
                }
                append("band.$n.from = ${mm(b.lo)}\n")
                if (!b.hi.isInfinite()) append("band.$n.to = ${mm(b.hi)}\n")
            }
            // curved islands (locked only, for now): each by the point on its cap, its start, and its own layer height as resolved
            for (isl in st.value.islands) {
                fun line(field: String, v: Double) = append("${ConfigFiles.pathText(listOf("island", isl.key) + field.split('.'))} = ${mm(v)}\n")
                line("x", isl.seedXMm); line("y", isl.seedYMm); line("start", isl.startMm); line("layer.height", isl.layerMm)
            }
            // support decided per overhang: the spots chosen, as locks (the faces needing support within
            // each get it), or the switch locked off when none was; the locked spots stay the job's own requirement
            // written whenever support was decided here, or the plan holds spots (a plan file's own, kept or given: locked by it,
            // not decided again; without this a kept spot vanished from the written plan)
            val decidedHere = p.supportDecided.getOrNull(k) == true
            val locked = st.value.support?.locked.orEmpty(); val chosen = st.value.support?.chosen.orEmpty()
            if (decidedHere || locked.isNotEmpty() || chosen.isNotEmpty()) {
                if (locked.isEmpty() && chosen.isEmpty()) {
                    append(comment("support decided per overhang (plan.support.enabled unset): none chosen") + "\n")
                    append("support.enabled = \"false\"\n")
                } else {
                    // the locked spots are written as the plan's own spots too (first), so `slice` decides nothing again and never meets
                    // the switch locked off beside a locked spot (that plan file was refused by plymeld's own rule)
                    append(comment((if (decidedHere) "support decided per overhang (plan.support.enabled unset): " else "support locked by the plan given (its spots, kept or as given): ") +
                        (if (locked.isEmpty()) "" else "${locked.size} locked spot(s) (plan.support.locked, the job's requirement, written here in the posed frame) and ") +
                        "${chosen.size} spot(s) ${if (decidedHere) "chosen" else "locked"}, each the faces needing support within its box " +
                        "(x, y from the posed object's corner, z from its bottom) and its column's print order beside the model's layers") + "\n")
                    // each spot named by what it holds up: the part whose box holds the spot's top center, when
                    // the object has several parts; else the box's rounded center. The box stays the spot's identity (keeps, re-plans)
                    val names = spotNames(p, k, (locked + chosen).map { it.region })
                    for ((i, sp) in (locked + chosen).withIndex()) {
                        val n = names[i]; val r = sp.region
                        val mark = if (keptSpot(r)) "!" else ""
                        fun q(x: Double, y: Double, z: Double) = "\"$mark" + listOf(x, y, z).joinToString(" ") { mm(it).removeSurrounding("\"").removeSuffix(" mm") } + " mm\""
                        append("support.spot.$n.min = ${q(r.x0, r.y0, r.fromMm)}\nsupport.spot.$n.max = ${q(r.x1, r.y1, r.toMm)}\n")
                        append("support.spot.$n.order = \"$mark${sp.order.id}\"\n")
                    }
                }
            }
            angles[k]?.let { (deg, axis) ->
                append(comment("infill direction: 45 deg to the object's long axis as posed (${num(axis)} deg from +X), so a grid's two line\nfamilies cross it at the same angle and neither runs along it") + "\n")
                append("layer.infill.angle = \"${num(deg)} deg\"\n")
            }
            // the first layer a first-layer window decided (the object's height and speed; a part's own speed where it differs)
            for (n in result.firstLayer.filter { it.obj == o.key }) {
                val at = n.part?.let { "part.${ConfigFiles.pathText(listOf(it))}." } ?: ""
                if (n.heightMm == null && n.speedMmS == null) continue
                append(comment("first layer${n.part?.let { " of part '$it'" } ?: ""}: ${n.why}") + "\n")
                n.heightMm?.let { append("${at}mechanism.adhesion.first_layer.height = ${mm(it)}\n") }
                n.speedMmS?.let { append("${at}mechanism.motion.first_layer.speed = \"${num(it)} mm/s\"\n") }
            }
            // the surface technique of the flat tops the search weighed: the lock that locks what it chose
            if (SurfaceTechnique.IRONING in (p.weighed.getOrNull(k) ?: emptySet())) {
                append(comment("the flat tops' surface technique, weighed against as laid (no plan.faces.flat_top.technique lock): " + (if (SurfaceTechnique.IRONING in p.surfaceTechniques[k]) "ironing won" else "as laid won")) + "\n")
                append("faces.flat_top.technique = \"${if (SurfaceTechnique.IRONING in p.surfaceTechniques[k]) "ironing" else "as_laid"}\"\n")
            }
            // bond-first cooling, weighed: the lock that locks what was chosen (a lock already in force stays as it is)
            p.bonds.getOrNull(k)?.takeIf { !it.overridden }?.let { d ->
                append(comment("bond-first cooling, weighed (no plan.bond_cooling lock): " + (if (d.value.on) "bond-first won" else "the filament's cooling won") + "; ${d.value}") + "\n")
                append("bond_cooling = \"${if (d.value.on) "on" else "off"}\"\n")
            }
            // the seams, weighed: the lock that locks what was chosen (the style; with scarf seams, their loops and the plain
            // style beside them), so `slice` places them so and decides nothing again (a plan file carried none and the
            // slice decided the seams a second time)
            p.seams.getOrNull(k)?.takeIf { !it.overridden }?.let { d ->
                append(comment("the seams, weighed (no plan.seam lock): ${d.value.words()}") + "\n")
                if (d.value.scarf == "none") append("seam = \"${d.value.placement.style}\"\n")
                else append("seam = \"scarf_seam\"\ntechnique.scarf_seam.where = \"${d.value.scarf}\"\ntechnique.scarf_seam.plain_style = \"${d.value.placement.style}\"\n")
            }
            // the in-island retraction, weighed on the object's own program: the lock that locks what was chosen, so a
            // slice with this plan does not decide it again
            p.retractions.getOrNull(k)?.takeIf { !it.overridden }?.let { d ->
                append(comment("the in-island retraction, weighed (plan.mechanism.motion.no_retract_in_island unset): " + (if (d.value) "hidden travels kept unretracted" else "every travel retracts")) + "\n")
                append("mechanism.motion.no_retract_in_island = \"${d.value}\"\n")
            }
        }
        append("\n# What the decisions were made from: `plymeld slice` warns when either has changed.\n")
        append("[plan.source_fingerprint.model]\n")
        for ((o, i) in plate) append("${ConfigFiles.pathText(listOf(o.key))} = \"${geometry(i.parts)}\"\n")
        append("\n[plan.source_fingerprint.config]\n")
        for ((s, h) in config.fingerprints().toSortedMap()) append("$s = \"$h\"\n")
    }

    companion object {
        /**
         * The stack for a re-plan, and the keys kept: a plan file's lines (every file carrying `plan.source_fingerprint`) left out unless
         * kept with `!`; a band (`band.<n>.*`), an island (`island.<n>.*`), and a support spot (`support.spot.<n>.*`) are one unit each, kept whole when any
         * of their lines is (a band without its technique is no band).
         */
        /** The file part of an entry's source ("file:line", or the file itself). */
        fun fileOf(source: String) = Regex("^(.*):\\d+$").find(source)?.groupValues?.get(1) ?: source
        /** The plan files among the stack's sources: every file carrying `plan.source_fingerprint` (what `plymeld plan -o` writes). */
        fun planFilesOf(given: ConfigStack): Set<String> = given.all.filter { it.path.size >= 2 && it.path[0] == "plan" && it.path[1] == "source_fingerprint" }.map { fileOf(it.source) }.toSet()

        private fun rePlanStack(given: ConfigStack, say: (String) -> Unit): Pair<ConfigStack, Set<String>> {
            val planFiles = planFilesOf(given)
            if (planFiles.isEmpty()) {
                if (given.all.any { it.path.size > 2 && it.path[0] == "plan" && it.path[1] == "object" && !it.source.startsWith(ConfigFiles.BUNDLED_SOURCE) })
                    say("plan values given with no plan.source_fingerprint (not a file `plymeld plan -o` wrote, or its source_fingerprint tables removed): read as locks, not decided again")
                return given to given.all.filter { it.keep }.map { it.key }.toSet()
            }
            val fromPlan = given.all.filter { it.path.isNotEmpty() && it.path[0] == "plan" && fileOf(it.source) in planFiles }
            fun unit(e: ConfigFiles.Entry): List<String>? = when {
                e.path.size > 5 && e.path[1] == "object" && (e.path[3] == "band" || e.path[3] == "island") -> e.path.take(5)
                e.path.size > 4 && e.path[1] == "object" && e.path[3] == "pose" -> e.path.take(4)
                e.path.size > 6 && e.path[1] == "object" && e.path[3] == "support" && e.path[4] == "spot" -> e.path.take(6)
                else -> null
            }
            val keptUnits = fromPlan.filter { it.keep }.mapNotNull(::unit).toSet()
            val kept = fromPlan.filter { it.keep || unit(it) in keptUnits }
            val dropped = fromPlan.filter { it !in kept }
            say("plan file${if (planFiles.size > 1) "s" else ""} ${planFiles.joinToString()}: ${kept.size} value(s) kept (!)" +
                (if (kept.isEmpty()) "" else ": " + kept.joinToString { it.key }) + "; ${dropped.count { it.path.getOrNull(1) != "source_fingerprint" }} decided again")
            return ConfigStack(given.all.filter { it !in dropped }) to (kept.map { it.key }.toSet() + given.all.filter { it.keep && it !in fromPlan }.map { it.key })
        }

        /** [text] (a plan file) with `!` on every value whose key is in [kept]: `key = "!value"`. */
        fun withKeepMarks(text: String, kept: Set<String>): String {
            if (kept.isEmpty()) return text
            var table = ""
            val header = Regex("""^\[(.+)]$""")
            val line = Regex("""^((?:"[^"]*"|[A-Za-z0-9_#-]+)(?:\.(?:"[^"]*"|[A-Za-z0-9_#-]+))*)\s*=\s*"(.*)"$""")
            return text.lines().joinToString("\n") { l ->
                header.find(l.trim())?.let { table = it.groupValues[1]; return@joinToString l }
                val m = line.find(l) ?: return@joinToString l
                val key = (if (table.isEmpty()) "" else "$table.") + m.groupValues[1]
                if (key in kept && !m.groupValues[2].startsWith("!")) l.replaceFirst("= \"", "= \"!") else l
            }
        }
        /** A fingerprint of an object's geometry: its triangles as read, not the file's bytes. */
        fun geometry(parts: List<TriangleSoup>): String {
            val buf = java.nio.ByteBuffer.allocate(parts.sumOf { it.count * 9 * 4 + 4 })
            for (p in parts) { buf.putInt(p.count); for (i in 0 until p.count * 9) buf.putFloat(p.coords[i]) }
            return Fingerprint.of(buf.array())
        }
    }
}
