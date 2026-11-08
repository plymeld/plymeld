package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang
import plymeld.model.Resolver
import plymeld.model.Setup
import plymeld.model.withObjectSetups
import java.util.Locale

/**
 * Runs the decision steps implemented so far: for each object a pose and a
 * technique, then the plate's object order with the arrangement. Each step reads facts from the
 * concept model, the machine description, and the mesh; overrides lock a step.
 */
class Plan(/** Every decision, in the order made: each object's pose and technique, then bond-first cooling, the seams and the in-island retraction per object, the object order last. */
           val decisions: List<Decision<*>>, val layout: Layout,
           /** Each object's technique decision as the coordinator records it (`--explain`), in the setup's order. */
           val explains: List<TechniqueExplain> = emptyList(),
           /** Per object (the setup's order): the surface techniques the search chose by weight, of those it was free to weigh. */
           val surfaceTechniques: List<Set<SurfaceTechnique>> = emptyList(),
           /** Per object: the surface techniques the search really weighed (free to weigh, and with an outcome predicted): the slice prints what was chosen for these. */
           val weighed: List<Set<SurfaceTechnique>> = emptyList(),
           /** What happened around the decisions that is not a decision (the search log not written, and why). */
           val notes: List<String> = emptyList(),
           /** Per object (the setup's order): whether support was decided per overhang (the switch unset, no spots locked): `plan -o` then writes what was chosen. */
           val supportDecided: List<Boolean> = emptyList(),
           /** What the decisions leave for the user to know, said as warnings (support locked off over a cantilever). */
           val warnings: List<String> = emptyList(),
           /** Per object (the setup's order): its weakest bond between layers as the search measured it (mm²), null where it measured nothing (a locked pose): bond-first cooling reads it. */
           val weakestBonds: List<Double?> = emptyList(),
           /** Per object (the setup's order): bond-first cooling, the seams, and the in-island retraction as decided here (ObjectDecisions); null where a decision could not be made for the object. */
           val bonds: List<Decision<BondCooling.Choice>?> = emptyList(), val seams: List<Decision<SeamDecision.Choice>?> = emptyList(),
           val retractions: List<Decision<Boolean>?> = emptyList()) {
    /** The object order decision (its value the layout), by kind. */
    val order: Decision<*> get() = decisions.first { it.kind == Decision.Kind.OBJECT_ORDER }
    fun describe(): String = decisions.joinToString("") { it.describe() } + notes.joinToString("") { "note: $it\n" } + warnings.joinToString("") { "warning: $it\n" } + timeLine() + "\n"
    /** Where the plan's time went, by kind of decision (the timed ones): what `plan` and `slice` print last. */
    fun timeLine(): String {
        val by = Decision.Kind.entries.mapNotNull { k -> decisions.filter { it.kind == k && it.seconds != null }.takeIf { it.isNotEmpty() }?.let { ds -> k to ds.sumOf { it.seconds!! } } }
        return "time: " + by.joinToString(", ") { (k, s) -> "%s %.1f s".format(Locale.ROOT, if (k == Decision.Kind.POSE) "pose and technique" else k.label, s) } +
            "; all the decisions %.1f s".format(Locale.ROOT, by.sumOf { it.second })
    }
    /** All objects' parts where they will be printed. */
    val parts: List<TriangleSoup> get() = layout.placed.flatMap { it.parts }

    companion object {
        /** Locks: [pose], [technique] for every object; [objects] per object, in plate order (a plan file's values), winning over them. */
        class Overrides(val pose: Pose? = null, val technique: PrintTechnique? = null, val order: ObjectOrder? = null, val objects: List<ObjectLocks> = emptyList(),
                        /** One object at a time in the order the objects are given (plan.objects.order = as_given), not shortest first. */
                        val sequenceAsGiven: Boolean = false,
                        /**
                         * Decide each object's flat layer height unless locked: flat layers' layer height ranges over the tool's
                         * thinnest to thickest layer, sampled coarse to fine up to method.search.layer.heights heights; false =
                         * the settings' layer height (it then acts as a lock: `investigate`, with another slicer's settings).
                         */
                        val decideLayerHeight: Boolean = false,
                        /**
                         * Per object (in plate order), whether its infill direction is decided (45 deg to its long axis as
                         * posed, InfillAngle) rather than set: the candidate layouts are predicted with it. Empty: none decided.
                         */
                        val decidedInfillAngle: List<Boolean> = emptyList(),
                        /** Flat layers' heights locked for every object (one height, or following the surfaces); an object's own lock wins. */
                        val flatHeights: FlatLayers.Heights? = null,
                        /**
                         * Per object (in plate order), the surface techniques the search may weigh against as laid:
                         * those the job locks nothing for. Empty, or an object's entry missing: every surface technique locked by its lock
                         * (`investigate`: another slicer's settings lock them).
                         */
                        val weighedSurfaceTechniques: List<Set<SurfaceTechnique>> = emptyList())
        /**
         * One object's locks: its pose (rotation; placed on the bed as decided), its techniques by height, its flat layer
         * height, and how flat layers set its heights (plan.technique.flat.heights).
         */
        class ObjectLocks(val pose: Pose? = null, val techniques: TechniquePlan? = null, /** a locked flat layer height (mm): no candidates */ val layerHeightMm: Double? = null,
                         val flatHeights: FlatLayers.Heights? = null,
                         /** The spots the user forces support on (a region's support = "always"): requirements. */
                         val lockedSpots: List<LockedSpot> = emptyList(),
                         /** Support spots a plan file locks (plan.object.<key>.support.spot.<key>): the faces needing support within them get it, nothing else; null = none locked. */
                         val supportSpots: List<SupportPlan.Spot>? = null,
                         /** Planned inserts (plan.insert.<key>): sockets the print pauses at; requirements. */
                         val inserts: List<Insert> = emptyList(),
                         /** Where the loops start, as the job locks it (plan.seam), and scarf seams (plan.technique.scarf_seam.enabled: their where, "none" off); null = decided. */
                         val seam: SeamDecision.Lock? = null, val scarf: SeamDecision.Lock? = null,
                         /** Bond-first cooling locked on or off (plan.bond_cooling: the value and source); null = decided. */
                         val bondCooling: Pair<Boolean, String>? = null,
                         /** Curved islands a plan locks (plan.object.<key>.island.<key>): each cap's island in its own layers; locked only, for now. */
                         val islands: List<CurvedIsland.Lock> = emptyList(),
                         /** The object's regions (plan.object.<key>.region.<key>): the values set for the lines in each. */
                         val regions: List<Region> = emptyList())

        /**
         * What the quick time estimate reads for object [k] with [partCount] parts: its parts grouped as
         * the slicer groups them (by spool, and by settings of their own; IncrementalSlicer.partUnits),
         * each group's speeds. Null when a setting is not set (the estimate then counts flow only).
         */
        private fun quickUnits(setup: Setup, k: Int, partCount: Int): QuickUnits? = runCatching {
            fun speeds(ctx: plymeld.model.Context): QuickSpeeds {
                val s = plymeld.model.SliceSettings.of(setup, ctx)
                return QuickSpeeds(s.wallLoops, s.visibleSpeed, if (s.innerWallsAsVisible) s.visibleSpeed else s.hiddenSpeed, s.innerWallWidthUm / 1000.0, s.hiddenSpeed, s.sparseDensity,
                    s.sparseWidthUm / 1000.0,
                    s.topShellThicknessUm / 1000.0, s.bottomShellThicknessUm / 1000.0, s.hiddenSpeed, s.solidWidthUm / 1000.0)
            }
            val purge = (Resolver.resolveOrNull(setup, "hardware.filament.purge.volume", plymeld.model.Context(k))?.value as Number?)?.toDouble() ?: 0.0
            val o = setup.objects[k]
            val whole = speeds(plymeld.model.Context(k))
            val change = (Resolver.resolveOrNull(setup, "hardware.machine.tool_change.time", plymeld.model.Context(k))?.value as Number?)?.toDouble() ?: 0.0
            if (o.parts.size != partCount || partCount < 2) return@runCatching QuickUnits(IntArray(partCount) { 0 }, listOf(whole), listOf(o.spool), purge, change, whole)
            val keys = o.parts.map { p -> p.spool + "|" + p.overrides.ids.sorted().joinToString(";") { "$it=${p.overrides[it]?.value}" } }
            val distinct = keys.distinct()
            val firstOf = distinct.map { keys.indexOf(it) }
            QuickUnits(IntArray(partCount) { distinct.indexOf(keys[it]) }, firstOf.map { speeds(plymeld.model.Context(k, it)) }, firstOf.map { o.parts[it].spool }, purge, change, whole)
        }.getOrNull()

        /**
         * [machine] with tool [tool]'s head shape from the user's config for object [k], over the machine description's
         *: the nozzle's angle and height (TIP_CONE: curved layers need it), and the
         * head's XY size with the nozzle's position in it (UNDERSIDE: the reach from the tip towards -X, +X, -Y, +Y).
         * A tier is set only when all its values are; a nozzle outside the head's outline is refused.
         */
        fun headFromConfig(machine: plymeld.machine.MachineDescription, tool: Int, setup: Setup, k: Int, part: Int? = null): plymeld.machine.MachineDescription {
            fun v(id: String) = Resolver.resolveOrNull(setup, id, plymeld.model.Context(k, part))
            fun mm(r: plymeld.model.Resolved) = (r.value as Number).toDouble()
            var m = machine
            val angle = v("hardware.tool.head.nozzle.angle"); val height = v("hardware.tool.head.nozzle.height")
            if (angle != null && height != null)
                m = m.withHead(tool, mapOf("tip_cone_deg" to mm(angle), "head.nozzle.height" to mm(height)), "nozzle angle and height", "${angle.from}; ${height.from}")
            headReach(setup, k, part)?.let { (r, from) ->
                m = m.withHead(tool, mapOf("head_minus_x" to r.minusX, "head_plus_x" to r.plusX, "head_minus_y" to r.minusY, "head_plus_y" to r.plusY),
                    "head size and nozzle position", from)
            }
            return m
        }

        /** [parts] in [pose]. */
        private fun posedParts(parts: List<plymeld.mesh.TriangleSoup>, pose: Pose) = parts.map(pose::apply)

        /**
         * Why curved island [isl] (from the posed object's corner) cannot be laid over [posed] parts of units [partUnit] (an object
         * laid per part): it covers parts of different units (a part is the island's where a vertex of it above the island's
         * start lies in its area), and it is laid as one; null = it can.
         */
        private fun islandUnits(posed: List<plymeld.mesh.TriangleSoup>, isl: CurvedIsland, partUnit: IntArray): String? {
            val bs = posed.map { it.bounds() }
            val x0 = bs.minOf { it[0] }; val y0 = bs.minOf { it[1] }; val z0 = bs.minOf { it[2] }
            val placed = isl.placed(x0, y0)
            val mine = posed.indices.filter { p -> val s = posed[p]
                (0 until s.count).any { t -> (0 until 3).any { c -> s.z(t, c) - z0 > isl.startMm && placed.covers(s.x(t, c).toDouble(), s.y(t, c).toDouble()) } } }
            val us = mine.map { partUnit[it] }.distinct()
            return if (us.size <= 1) null else "it covers parts laid with different filaments or settings (parts ${mine.joinToString { "${it + 1}" }}), and an island is laid as one"
        }

        /**
         * The tools printing object [k], each with the part to read its values for: null for the object's own spool's tool,
         * else the first part printing with that tool.
         */
        fun toolsOf(setup: Setup, k: Int): Map<Int, Int?> {
            val o = setup.objects[k]
            val out = LinkedHashMap<Int, Int?>()
            out[setup.spool(o.spool).tool] = null
            for ((pi, p) in o.parts.withIndex()) out.putIfAbsent(setup.spool(p.spool).tool, pi)
            return out
        }

        /**
         * The loaded head's reach from the nozzle towards -X, +X, -Y, +Y (mm), from the tool's head size and the nozzle's
         * position in it (from the head's -X and -Y edges), with where the values came from; null unless all four are set.
         */
        fun headReach(setup: Setup, k: Int, part: Int? = null): Pair<Arrange.Reach, String>? {
            fun v(id: String) = Resolver.resolveOrNull(setup, id, plymeld.model.Context(k, part))
            fun mm(r: plymeld.model.Resolved) = (r.value as Number).toDouble()
            val sx = v("hardware.tool.head.size.x"); val sy = v("hardware.tool.head.size.y"); val nx = v("hardware.tool.head.nozzle.x"); val ny = v("hardware.tool.head.nozzle.y")
            if (sx == null || sy == null || nx == null || ny == null) return null
            require(mm(nx) in 0.0..mm(sx) && mm(ny) in 0.0..mm(sy)) {
                "the nozzle (hardware.tool.<key>.head.nozzle.x ${mm(nx)}, head.nozzle.y ${mm(ny)} mm) is outside the head (head.size.x ${mm(sx)}, head.size.y ${mm(sy)} mm): ${nx.from}; ${ny.from}" }
            return Arrange.Reach(mm(nx), mm(sx) - mm(nx), mm(ny), mm(sy) - mm(ny)) to listOf(sx, sy, nx, ny).joinToString("; ") { it.from }
        }

        /**
         * The baseline's layer height for object [k] (mm) and what it is: method.search.baseline.layer.height_share (a share)
         * of the nozzle diameter of the tool that prints the object — the tool of the object's own filament (its spool),
         * the one its parts print with unless a part names another filament. Null (and why) when that tool gives no nozzle
         * diameter: the decisions then score against their best candidate and say so.
         */
        fun baselineLayer(setup: Setup, k: Int): Pair<Double?, String> {
            val ctx = plymeld.model.Context(k)
            val tool = setup.spool(setup.objects[k].spool).tool
            val share = Resolver.resolve(setup, "method.search.baseline.layer.height_share", ctx)
            val nozzle = Resolver.resolveOrNull(setup, "hardware.tool.bore", ctx)
                ?: return null to "tool ${tool + 1} (the object's own filament's) gives no nozzle diameter"
            val s = (share.value as Number).toDouble(); val d = (nozzle.value as Number).toDouble()
            return s * d to "%s %% of the %s mm nozzle of tool %d, the object's own filament's (share: %s; nozzle: %s)".format(Locale.ROOT,
                fmt(s * 100), fmt(d), tool + 1, share.from, nozzle.from)
        }

        /**
         * [setup] with object k's layer height (plan.technique.flat.layer.height) set to [hs] k (mm), on the object and on any of its parts that
         * sets its own; a null leaves object k as it is. What a layout is predicted with: the object order's baseline, and
         * each candidate layout at its own objects' decided heights ([layoutHeights]).
         */
        fun withLayerHeights(setup: Setup, hs: List<Double?>, why: String): Setup = withObjectValue(setup, "plan.technique.flat.layer.height", hs, why)

        /**
         * [setup] with setting [id] of object k set to [vs] k, on the object and on any of its parts that sets its own; a
         * null leaves object k as it is.
         */
        /**
         * Each object's plan.view_from (the model's own side) turned with its pose ([Visibility.posedView]),
         * for the decisions and the slice after the pose; null where there is no view or the pose leaves it as it was.
         */
        fun posedViews(setup: Setup, poses: List<Pose>): List<String?> = poses.mapIndexed { k, p ->
            Visibility.posedView(Resolver.resolveOrNull(setup, "plan.view_from", plymeld.model.Context(k))?.value as String?, p.r)
        }
        const val POSED_VIEW_WHY = "decided: plan.view_from on the model's own side, turned with its pose"

        fun withObjectValue(setup: Setup, id: String, vs: List<Any?>, why: String): Setup = setup.withObjectSetups(setup.objects.mapIndexed { k, o ->
            val v0 = vs[k] ?: return@mapIndexed o
            fun copy(v: plymeld.model.Values, always: Boolean) = plymeld.model.Values(v.entity, v.name).also { c ->
                for (i in v.ids) c[i] = v[i]!!
                if (always || v[id] != null) c[id] = plymeld.model.Sourced(v0, why)
            }
            plymeld.model.ObjectSetup(o.name, o.intent, o.spool, copy(o.overrides, true), o.parts.map { p -> plymeld.model.PartSetup(p.name, p.spool, copy(p.overrides, false)) })
        })

        /**
         * Each setup object's flat layer height in [l] (mm), in the setup's order (the layout's techniques are in print
         * order): null where its plan has none (following the surfaces throughout, or the height is the settings').
         */
        fun layoutHeights(l: Layout, objects: Int): List<Double?> {
            val out = arrayOfNulls<Double>(objects)
            for ((j, k) in l.objectIndex.withIndex()) out[k] = l.techniques[j].layerHeightMm
            return out.toList()
        }

        private fun fmt(v: Double) =String.format(Locale.ROOT, "%.4f", v).trimEnd('0').trimEnd('.')

        /** A spool's filament density (g/cm³, hardware.filament.density) with where it came from, or null when its filament gives none. */
        private fun density(setup: Setup, spool: plymeld.model.Spool): Pair<Double, String>? =
            setup.materials[spool.material]?.get("hardware.filament.density")?.let { (it.value as Number).toDouble() to "'${spool.material}': ${it.from}" }

        /**
         * The density of the filament that prints object [k]'s support (plan.support.slot; 0 = whichever of the object's
         * filaments is loaded, so those must agree), with its source; or null and why (support then compares by volume).
         */
        fun supportDensity(setup: Setup, k: Int): Pair<Double?, String> {
            val ctx = plymeld.model.Context(k)
            val n = (Resolver.resolveOrNull(setup, "plan.support.slot", ctx)?.value as Number?)?.toInt() ?: 0
            val spools = if (n > 0) listOfNotNull(setup.spools.getOrNull(n - 1)) else setup.objects[k].let { o -> (listOf(o.spool) + o.parts.map { it.spool }).distinct().map(setup::spool) }
            if (spools.isEmpty()) return null to "plan.support.slot = $n names no loaded slot"
            val ds = spools.map { it to density(setup, it) }
            ds.firstOrNull { it.second == null }?.let { return null to "no density for '${it.first.material}' (hardware.filament.<key>.density)" }
            val values = ds.map { it.second!!.first }.distinct()
            if (values.size > 1) return null to "support prints with whichever of ${spools.joinToString { "'${it.material}'" }} is loaded, and their densities differ"
            return values.single() to ds.joinToString("; ") { it.second!!.second }
        }

        /** How the object order weighs filament: each tool's filaments (every loaded spool in it), by mass or volume ([FilamentBasis.of]). */
        fun filamentBasis(setup: Setup): FilamentBasis {
            val missing = setup.spools.filter { setup.materials[it.material]?.get("hardware.filament.diameter") == null }.map { it.material }.distinct()
            if (missing.isNotEmpty()) return FilamentBasis(null, emptyMap(), "no diameter for ${missing.joinToString { "'$it'" }}")
            return FilamentBasis.of(setup.spools.groupBy { it.tool }.mapValues { (_, ss) ->
                ss.map { FilamentBasis.Filament(it.material, (setup.materials.getValue(it.material)["hardware.filament.diameter"]!!.value as Number).toDouble(), density(setup, it)?.first) }
            })
        }

        fun make(parts: List<TriangleSoup>, setup: Setup, o: Overrides = Overrides()): Plan = makeFor(listOf(setup.objects.first().name to parts), setup, o)

        /** [objects]: name and parts of each object on the plate, in the same order as `setup.objects`. */
        fun makeFor(objects: List<Pair<String, List<TriangleSoup>>>, setup: Setup, o: Overrides = Overrides()): Plan {
            require(objects.size == setup.objects.size) { "${objects.size} objects but the setup describes ${setup.objects.size}" }
            fun r(id: String) = Resolver.resolve(setup, id)
            fun num(id: String) = (r(id).value as Number).toDouble()
            // how far a line holds out over air is the filament's (hardware: filament x cooling), as a share
            // of the object's outer wall line: read per object
            fun overhang(k: Int): Pair<Overhang, String> {
                val share = Resolver.resolve(setup, "hardware.filament.overhang.share", plymeld.model.Context(k))
                val width = Resolver.resolve(setup, "plan.layer.walls.outer.line.width", plymeld.model.Context(k))
                return Overhang((share.value as Number).toDouble(), (width.value as Number).toDouble()) to
                    "share: the filament's, ${share.from}; width: the outer wall's, ${width.from}"
            }
            val oh = overhang(0)
            // the plate's weights (the object order, the pose facts' defaults), not the first object's own
            val wr = Resolver.resolvePlate(setup, "job.priorities")
            val weights = Weights.parse(wr.value as String, wr.from)
            val machine = setup.models.forModel(r("hardware.machine.model").value as String)
            // the support switch is a lock: true / false lock it, unset = decided per overhang
            fun supportSwitch(k: Int?): Boolean? = (if (k == null) Resolver.resolveOrNull(setup, "plan.support.enabled")
                else Resolver.resolveOrNull(setup, "plan.support.enabled", plymeld.model.Context(k)))?.value as Boolean?
            val pf = PoseFacts(num("hardware.machine.build.left"), num("hardware.machine.build.front"), num("hardware.machine.build.right"), num("hardware.machine.build.back"),
                num("hardware.machine.build.z"), oh.first, oh.second,
                weights, supportSwitch(null))
            fun <T> named(d: Decision<T>, name: String) = if (objects.size == 1) d else d.renamed("${d.step.substringBefore(" (")} ($name)")
            // method.search.time.max: wall-clock, from the start of planning
            val limitS = (Resolver.resolveOrNull(setup, "method.search.time.max")?.value as Number?)?.toDouble()
            val deadline = limitS?.let { System.nanoTime() + (it * 1e9).toLong() }
            fun numOrNull(id: String, k: Int) = (Resolver.resolveOrNull(setup, id, plymeld.model.Context(k))?.value as Number?)?.toDouble()
            // method.search.zero_scale: the user's reference scales; the rest are derived per decision
            val zs = Resolver.resolveOrNull(setup, "method.search.zero_scale")
            val zeroScale = Scoring.parseZeroScale(zs?.value as String?, zs?.from ?: "")
            // pose and technique together, per object
            val baselines = objects.indices.map { baselineLayer(setup, it) }
            // flat layers' layer height as a control, with how many heights the search may try: locked by
            // the plan, else the tool's thinnest to thickest layer when it is decided; null = the settings' layer height
            val heightControls = objects.indices.map { k ->
                val lock = o.objects.getOrNull(k)?.layerHeightMm
                val n = numOrNull("method.search.layer.heights", k)?.toInt()
                val lo = Resolver.resolveOrNull(setup, "hardware.tool.layer.height.min", plymeld.model.Context(k))
                val hi = Resolver.resolveOrNull(setup, "hardware.tool.layer.height.max", plymeld.model.Context(k))
                when {
                    !o.decideLayerHeight -> null
                    lock != null -> Control(FlatLayers.LAYER_HEIGHT, "mm", Control.Range.Steps(listOf(lock)), "the plan's lock: plan.technique.flat.layer.height") to 1
                    n != null && lo != null && hi != null -> Control(FlatLayers.LAYER_HEIGHT, "mm",
                        Control.Range.Continuous((lo.value as Number).toDouble(), (hi.value as Number).toDouble()),
                        "the tool's thinnest and thickest layer: hardware.tool.<key>.layer.height.min ${lo.from}; hardware.tool.<key>.layer.height.max ${hi.from}") to n
                    else -> null
                }
            }
            // the full pipeline for a layout: its g-code predicted; a layout whose g-code plymeld
            // refuses to generate yet (e.g. an unimplemented support type) has no prediction, and says why
            fun predictLayout(s: Setup, l: Layout): plymeld.core.Predict.Prediction {
                val out = java.io.StringWriter()
                val job = plymeld.core.Job(l, s, "predict")
                val refused = try { job.write(out); null }
                    catch (e: IllegalArgumentException) { e.message } catch (e: IllegalStateException) { e.message }
                return if (refused != null) plymeld.core.Predict.Prediction(null, 0.0, "the plan's g-code cannot be generated yet: $refused")
                else plymeld.core.Predict.gcode(out.toString(), machine, (Resolver.resolveOrNull(s, "hardware.machine.tool_change.time")?.value as Number?)?.toDouble(), plymeld.core.Predict.limits(plymeld.model.SliceSettings.of(s)))
                    .withSeamScar(job.seamScar()).withStrings(job.strings)   // the seam, stringing, and restart measures of what was written
            }
            val filament = filamentBasis(setup)
            // the search log: one line per decision, where method.search.log says
            val logOn = Resolver.resolveOrNull(setup, "method.search.log.enabled")?.value as Boolean? ?: false
            val logFile = Resolver.resolveOrNull(setup, "method.search.log.file")?.value as String?
            val notes = ArrayList<String>()
            // what the decisions leave for the user to know (support locked off over a cantilever: said, never refused)
            val warnings = ArrayList<String>()
            // each object's decision facts and screened techniques
            val factsOf = ArrayList<Pair<PoseFacts, List<Pair<PrintTechnique, String?>>>>()
            val explains = ArrayList<TechniqueExplain>()
            val surfaceTechniquesOf = ArrayList<Set<SurfaceTechnique>>()
            val bondsOf = ArrayList<Double?>()
            // each object's curved islands decision (null: not made)
            val islandDecisionsOf = ArrayList<Decision<*>?>()
            val chosen = objects.mapIndexed { k, (name, parts) ->
                val ohk = overhang(k)
                val sd = supportDensity(setup, k)
                // the object's own weights (job.object.<key>.priorities win over the general ones)
                val wk = Resolver.resolve(setup, "job.priorities", plymeld.model.Context(k)).let { Weights.parse(it.value as String, it.from) }
                // the surface techniques the job locks nothing for are weighed; ironing's outcome reads its own values
                val ironSpacing = Resolver.resolveOrNull(setup, "plan.technique.ironing.spacing", plymeld.model.Context(k))
                val ironSpeed = Resolver.resolveOrNull(setup, "plan.technique.ironing.speed", plymeld.model.Context(k))
                val locksK = o.objects.getOrNull(k)
                val switchK = supportSwitch(k)
                // a locked spot is a requirement: the switch locked off cannot meet it, so the plan refuses here, before searching
                if (switchK == false && locksK?.lockedSpots?.isNotEmpty() == true) error("'$name': plan.support.enabled = false forbids support, but support is locked on " +
                    locksK.lockedSpots.joinToString { "spot '${it.key}' (${it.source})" } + ": a locked spot is a requirement; leave the switch unset (decided per overhang) or set it to true, or remove the spot")
                // support's controls, locked at the settings (named as their keys): the bundled values are stand-ins until measured
                val supportControls = Support.CONTROL_KEYS.mapNotNull { (id, unit) -> Resolver.resolveOrNull(setup, id, plymeld.model.Context(k))?.let { v ->
                    Control(id, unit, Control.Range.Steps(listOf((v.value as Number).toDouble())), v.from) } }
                val nozzleHeight = Resolver.resolveOrNull(setup, "hardware.tool.head.nozzle.height", plymeld.model.Context(k))
                // the visibility map every quality measure is weighted by: its placeholder weights and the view, with their provenance
                val visibility = Visibility.from(setup, plymeld.model.Context(k))
                // the stair-step measure's depth power (method.quality.stair_step.depth_power; PoseDecision.stairStep), with its provenance
                val stepPower = Resolver.resolveOrNull(setup, "method.quality.stair_step.depth_power", plymeld.model.Context(k))
                val pfk = PoseFacts(pf.bedMinX, pf.bedMinY, pf.bedMaxX, pf.bedMaxY, pf.maxZ, ohk.first, ohk.second, wk, switchK,
                    numOrNull("plan.technique.flat.layer.height", k), numOrNull("hardware.filament.melt.flow.max", k)?.let { it * (1 - (numOrNull("plan.mechanism.motion.melt.buffer", k) ?: 0.0)) }, numOrNull("hardware.filament.cooling.layer.time.min", k), limitS,
                    numOrNull("plan.mechanism.adhesion.first_layer.height", k), numOrNull("hardware.tool.layer.height.min", k), numOrNull("hardware.tool.layer.height.max", k), numOrNull("plan.technique.flat.step.max", k),
                    zeroScale, zs?.from ?: "", quickUnits(setup, k, parts.size),
                    fuzzySkin = (Resolver.resolveOrNull(setup, "plan.technique.fuzzy_skin.where", plymeld.model.Context(k))?.value as String?).let { it != null && it != "none" },
                    layerHeightCandidates = heightControls[k]?.let { (c, n) -> FlatLayers.coarse(c, baselines[k].first, n) },
                    layerHeightControl = heightControls[k]?.first, layerHeightBudget = heightControls[k]?.second ?: 1,
                    settleRounds = numOrNull("method.search.settle_rounds", k)?.toInt() ?: 0,
                    supportDensity = sd.first, supportDensitySource = sd.second,
                    baselineLayerMm = baselines[k].first, baselineWhat = baselines[k].second,
                    heightRatio = numOrNull("plan.technique.flat.height_ratio", k),
                    inserts = locksK?.inserts.orEmpty(),
                    weighSurfaceTechniques = o.weighedSurfaceTechniques.getOrNull(k) ?: emptySet(),
                    ironingSpacingMm = (ironSpacing?.value as Number?)?.toDouble(), ironingSpeedMmS = (ironSpeed?.value as Number?)?.toDouble(),
                    ironingSource = listOfNotNull(ironSpacing?.let { "spacing ${it.value} mm, ${it.from}" } ?: "plan.technique.ironing.spacing unset",
                        ironSpeed?.let { "speed ${it.value} mm/s, ${it.from}" } ?: "plan.technique.ironing.speed unset").joinToString("; "),
                    lockedSpots = locksK?.lockedSpots ?: emptyList(), supportSpotsLocked = locksK?.supportSpots, supportControls = supportControls,
                    blocked = locksK?.regions.orEmpty().filter { it.support == Region.NEVER },
                    supportColumnWidthMm = numOrNull("plan.support.column.width", k),
                    nozzleHeightMm = (nozzleHeight?.value as Number?)?.toDouble(), nozzleHeightSource = nozzleHeight?.let { "hardware.tool.<key>.head.nozzle.height: ${it.from}" } ?: "hardware.tool.<key>.head.nozzle.height not set",
                    travelSpeedMmS = numOrNull("hardware.machine.motion.travel.speed", k),
                    poseKeepMargin = numOrNull("method.search.pose.keep_margin", k), poseShortlistMargin = numOrNull("method.search.pose.shortlist_margin", k), bridgeMaxSpanMm = numOrNull("hardware.filament.bridge.span.max", k),
                    visibility = visibility, outerLineWidthMm = numOrNull("plan.layer.walls.outer.line.width", k),
                    stairStepPower = (stepPower?.value as Number?)?.toDouble() ?: 2.0, stairStepPowerSource = stepPower?.from ?: "unset: 2")
                // every tool printing the object, each head read for the part it prints (the object's first part's
                // tool alone was screened, so a bridge part on a tool with no head shape passed)
                val toolsK = toolsOf(setup, k)
                val machineK = toolsK.entries.fold(machine) { m, (t, part) -> headFromConfig(m, t, setup, k, part) }
                val screened = TechniqueDecision.screen(machineK, toolsK.keys)
                factsOf += pfk to screened
                val locks = locksK
                // a finalist through the full pipeline: the object alone on the bed in that pose,
                // sliced with the finalist's layer height, surface technique, and decided infill direction, its g-code predicted. Only
                // where the search decides (not `investigate`, which keeps another slicer's settings and the quick estimates)
                val predictFinalist: ((Pose, TechniquePlan, Set<SurfaceTechnique>) -> FullPrediction)? = if (!o.decideLayerHeight) null else { pose, plan, fs ->
                    val posedParts = parts.map(pose::apply)
                    val placed = Arrange.rows(listOf(name to posedParts), 0.0, pfk)
                    if (placed == null) FullPrediction(null, null, "the object does not fit the bed in that pose") else {
                        fun <T> only(v: T?): List<T?> = List(objects.size) { if (it == k) v else null }
                        val angle = if (o.decidedInfillAngle.getOrNull(k) == true) InfillAngle.degrees(posedParts) else null
                        val iron = if (PoseDecision.ironingWeighed(pfk)) (if (SurfaceTechnique.IRONING in fs) "top" else "none") else null
                        var s = withObjectValue(withLayerHeights(setup, only(plan.layerHeightMm), "decided: the finalist's flat layer height"), "plan.layer.infill.angle", only(angle), "decided: 45 deg to the object's long axis as posed")
                        s = withObjectValue(s, "plan.technique.ironing.type", only(iron), "decided: the finalist's surface technique of the flat tops, weighed against as laid")
                        // the seams are decided after the plan (SeamDecision): a finalist's slice takes the plainest provisionally
                        if (Resolver.resolveOrNull(setup, "plan.seam", plymeld.model.Context(k)) == null) {
                            s = withObjectValue(s, "plan.seam", only("back"), "provisional for a finalist's slice: the seam decision follows the plan")
                            s = withObjectValue(s, "plan.technique.scarf_seam.where", only("none"), "provisional for a finalist's slice: the seam decision follows the plan")
                        }
                        // the in-island retraction is decided when sliced (RetractionDecision): a finalist's slice keeps the hidden travels provisionally
                        if (Resolver.resolveOrNull(setup, "plan.mechanism.motion.no_retract_in_island", plymeld.model.Context(k)) == null)
                            s = withObjectValue(s, "plan.mechanism.motion.no_retract_in_island", only(true), "provisional for a finalist's slice: the retraction decision is made when the object is sliced")
                        // the object alone: a Job places every object of its setup, so the setup is cut down to this one
                        val p = predictLayout(s.withObjectSetups(listOf(s.objects[k])), Layout(ObjectOrder.TOGETHER, listOf(plan), placed, listOf(0)))
                        val fil = if (p.timeS == null || filament.metric == null || filament.unknownTools(p.filamentByToolMm) != null) null
                            else filament.metric to filament.round(filament.of(p.filamentByToolMm))
                        FullPrediction(p.timeS, fil, p.why, p.seamScar?.scarMm2, p.strings?.stringMm, p.strings?.restartStarvedMm)
                    }
                }
                val tSearch = System.nanoTime()
                val d = PoseDecision.decideWithTechnique(parts, pfk, screened, locks?.pose ?: o.pose, o.technique, deadline, locks?.techniques,
                    locks?.flatHeights ?: o.flatHeights, predictFinalist).let { PoseDecision.Posed(it.pose.took((System.nanoTime() - tSearch) / 1e9), it.technique, it.explain, it.surfaceTechniques, it.weakestBondMm2) }
                explains += d.explain.named(name)
                surfaceTechniquesOf += d.surfaceTechniques
                bondsOf += d.weakestBondMm2
                // support locked off: what only support could lay, said
                val waveK = Resolver.resolveOrNull(setup, "plan.faces.under.wave_overhang", plymeld.model.Context(k))?.value == true
                PoseDecision.unheldWithSupportOff(parts, d.pose.value, d.technique.value, pfk, waveK).forEach { warnings += "'$name': $it" }
                if (logOn) SearchLog.append(logFile ?: "", d.stop.logLine(SearchLog.now(), name))?.let { notes += it }
                // curved islands: weighed on the chosen pose and
                // flat plan, for a lone object whose plan locks none (a plate's objects are read flat by the pre-print check yet).
                // An object whose technique is locked (a plan file's bands) has its islands decided too: the ones it locks, or none
                // (the slice of a plan file re-ran the search, ~380 s single-threaded on a large model, where the plan file
                // promises "it prints as planned and decides nothing again")
                val islandD = if (objects.size != 1 || locks?.islands?.isNotEmpty() == true || locks?.techniques != null || d.technique.value.techniques != listOf(PrintTechnique.FLAT_LAYERS) ||
                    d.technique.value.curvedDepthAuto) null else {
                    val angle = numOrNull("hardware.tool.head.nozzle.angle", k); val nh = numOrNull("hardware.tool.head.nozzle.height", k)
                    val minL = numOrNull("hardware.tool.layer.height.min", k); val maxL = numOrNull("hardware.tool.layer.height.max", k)
                    val layer = d.technique.value.layerHeightMm ?: numOrNull("plan.technique.flat.layer.height", k)
                    // an object of several filaments or part settings is laid per part (IncrementalSlicer.multiProgram): islands there
                    // on one tool only (no tool change for an island yet), each over parts of one unit (it is laid as one)
                    val units = pfk.units
                    val perPart = (units?.partUnit?.distinct()?.size ?: 1) > 1
                    val oneTool = units == null || units.spools.map { setup.spool(it).tool }.distinct().size <= 1
                    if (perPart && !oneTool) Decision("curved islands ($name)", d.technique.value,
                        "not offered: the object's parts are laid on several tools, and a tool change for a curved island is not written yet",
                        emptyList<String>(), overridden = false)
                    else if (angle == null || nh == null || minL == null || maxL == null || layer == null) null else {
                        val look = IslandDecision.Look.of(Resolver.resolveOrNull(setup, "job.faces.sloped_top.look", plymeld.model.Context(k))?.value as String?)
                        IslandDecision.decide(name, parts.map(d.pose.value::apply), d.technique.value,
                            CurvedRegions.Params(angle, nh, layer, minL, minStartMm = numOrNull("plan.mechanism.adhesion.first_layer.height", k) ?: 0.0), maxL, look, wk,
                            measure = { plans -> PoseDecision.measureAll(CurvedDepth.merged(parts), d.pose.value, pfk.overhang, f = pfk, withSections = false, plansFor = { _, _ -> plans }) },
                            predict = predictFinalist?.let { pf -> { plan: TechniquePlan -> pf(d.pose.value, plan, d.surfaceTechniques) } },
                            refuse = if (!perPart) { _ -> null } else posedParts(parts, d.pose.value).let { posed -> { isl: CurvedIsland -> islandUnits(posed, isl, units!!.partUnit) } })
                    }
                }
                islandDecisionsOf += islandD?.let { named(it, name) }
                val technique = if (islandD == null || islandD.value.islands.isEmpty()) d.technique else
                    Decision(d.technique.step, islandD.value, d.technique.why + "; curved islands decided: " + islandD.value.islands.joinToString("; "),
                        d.technique.facts, d.technique.alternatives, d.technique.standIns, d.technique.overridden)
                named(d.pose, name) to named(technique, name)
            }
            val poses = chosen.map { it.first }
            val posed = objects.zip(poses).map { (ob, d) -> ob.first to ob.second.map(d.value::apply) }
            // a curved top band whose start is "auto": worked out on the posed object (CurvedDepth)
            val techniques0 = chosen.mapIndexed { k, (_, d) ->
                if (!d.value.curvedDepthAuto) d else {
                    val first = numOrNull("plan.mechanism.adhesion.first_layer.height", k) ?: error("a curved band's depth needs plan.mechanism.adhesion.first_layer.height")
                    val layer = d.value.layerHeightMm ?: numOrNull("plan.technique.flat.layer.height", k) ?: error("a curved band's depth needs plan.technique.flat.layer.height")
                    val min = numOrNull("hardware.tool.layer.height.min", k) ?: error("a curved band's depth needs hardware.tool.<key>.layer.height.min")
                    val depth = CurvedDepth.decide(posed[k].second, first, layer, min)
                        ?: error("'${objects[k].first}': no curved top band keeps every layer at least the tool's thinnest (%.3f mm), even from the first layer".format(Locale.ROOT, min))
                    val plan = TechniquePlan(listOf(TechniquePlan.Banded(0.0, depth.fromMm, PrintTechnique.FLAT_LAYERS),
                        TechniquePlan.Banded(depth.fromMm, Double.POSITIVE_INFINITY, PrintTechnique.CURVED_LAYERS)), d.value.layerHeightMm, support = d.value.support)
                    // the coordinator's view: the locked curved proposal trimmed along its lower boundary (the one cut curved
                    // layers allow), flat layers filling below it
                    val e = explains[k]
                    val curvedNow = Coordination.Volume.ZBand(depth.fromMm, Double.POSITIVE_INFINITY)
                    explains[k] = e.after(plan.portions(), Coordination.fill(Coordination.Volume.WHOLE, listOf(curvedNow)),
                        "the curved band's start worked out once posed: $depth (CurvedDepth)", e.proposals.map { o ->
                            if (o.p.technique != PrintTechnique.CURVED_LAYERS || !o.p.locked) o
                            else TechniqueExplain.ProposalOut(o.p, TechniqueExplain.Fate.TRIMMED,
                                "a requirement, trimmed along its lower boundary, the one cut curved layers allow: its start is the highest that keeps every layer at least the tool's thinnest (CurvedDepth)", curvedNow)
                        })
                    Decision(d.step, plan, "${d.why}; the curved band from $depth: the highest start below the whole top that keeps every layer at least the tool's thinnest (%.3f mm) where the top is lowest (CurvedDepth)".format(Locale.ROOT, min),
                        d.facts, d.alternatives, d.standIns, d.overridden)
                }
            }
            // planned inserts: each object's, posed with its chosen pose (the pose is locked, Insert.posed refuses a tilt),
            // carried on its technique plan to the slice (the pause, the cover, no support in the socket)
            val techniques1 = techniques0.mapIndexed { k, d ->
                val ins = o.objects.getOrNull(k)?.inserts.orEmpty()
                if (ins.isEmpty()) d else {
                    val bs = posed[k].second.map { it.bounds() }
                    val bounds = doubleArrayOf(bs.minOf { it[0] }, bs.minOf { it[1] }, bs.minOf { it[2] }, bs.maxOf { it[3] }, bs.maxOf { it[4] }, bs.maxOf { it[5] })
                    val pz = ins.map { it.posed(poses[k].value, bounds) }
                    Decision(d.step, d.value.withInserts(pz), d.why + "; planned inserts: " + pz.joinToString("; ") { "'${it.key}' ${it.words}" },
                        d.facts + ins.map { "planned insert '${it.key}' (${it.source}): ${it.words}; the print pauses above its top, its roof is laid onto it, no support in its socket" },
                        d.alternatives, d.standIns, d.overridden)
                }
            }
            // the object's regions: each posed with its chosen pose, carried on its technique plan to the slice (the lines in each
            // get its values: MotionRegions)
            val techniques2 = techniques1.mapIndexed { k, d ->
                val rs = o.objects.getOrNull(k)?.regions.orEmpty()
                if (rs.isEmpty()) d else {
                    val bs = posed[k].second.map { it.bounds() }
                    val bounds = doubleArrayOf(bs.minOf { it[0] }, bs.minOf { it[1] }, bs.minOf { it[2] }, bs.maxOf { it[3] }, bs.maxOf { it[4] }, bs.maxOf { it[5] })
                    val pr = rs.map { r -> try { r.posed(poses[k].value, bounds) } catch (e: IllegalArgumentException) { error("'${objects[k].first}': ${e.message}") } }
                    // support kept off a region that also locks it on: no rule wins over the other
                    for (a in pr.filter { it.support == Region.ALWAYS }) for (b in pr.filter { it.support == Region.NEVER }) if (a.region.overlaps(b.region))
                        error("'${objects[k].first}': region '${a.key}' (${a.source}) locks support on, and region '${b.key}' (${b.source}) keeps it off, over the same faces: move one, or drop one rule")
                    Decision(d.step, d.value.withRegions(pr), d.why,
                        d.facts + rs.map { r -> "region '${r.key}' (${r.source}): ${r.words}; " + listOfNotNull(r.speedMax?.let { "lines in it at most %s mm/s".format(Locale.ROOT, Control.fmt(it)) },
                            r.accelMax?.let { "accelerating at most %s mm/s²".format(Locale.ROOT, Control.fmt(it)) }).joinToString(", ") },
                        d.alternatives, d.standIns, d.overridden)
                }
            }
            // curved islands a plan locks: each found on the posed object by
            // the point on its cap, carried on its technique plan to the slice (its own layers, merged by height with the flat ones)
            val techniques = techniques2.mapIndexed { k, d ->
                val locked = o.objects.getOrNull(k)?.islands.orEmpty()
                if (locked.isEmpty()) d else {
                    val name = objects[k].first
                    if (d.value.techniques != listOf(PrintTechnique.FLAT_LAYERS)) error("'$name': curved islands sit on flat layers; with ${d.value.techniques.joinToString { it.short }} they are not planned yet")
                    fun need(id: String) = numOrNull(id, k) ?: error("'$name': a curved island needs ${id.replace("hardware.tool.", "hardware.tool.<key>.")}")
                    val p = CurvedRegions.Params(need("hardware.tool.head.nozzle.angle"), need("hardware.tool.head.nozzle.height"),
                        d.value.layerHeightMm ?: need("plan.technique.flat.layer.height"), need("hardware.tool.layer.height.min"))
                    val isl = try { CurvedIsland.resolve(posed[k].second, locked, p, p.layerMm, need("hardware.tool.layer.height.max")) }
                        catch (e: IllegalArgumentException) { error("'$name': ${e.message}") }
                    Decision(d.step, d.value.withIslands(isl), d.why + "; curved islands locked: " + isl.joinToString("; "),
                        d.facts + isl.map { "$it: its layers follow its cap, dipping by up to %.2f mm (the nozzle's height at most)".format(Locale.ROOT, it.dip.maxOrNull() ?: 0.0) },
                        d.alternatives, d.standIns, d.overridden)
                }
            }
            // each object keeps its own techniques and layers in either order (a height shared over the plate is
            // gone): printed together, the Job merges them by height in each object's own order
            // over every object, not the first object's alone: the widest outer wall, the thinnest layer, the highest
            // z hop and the widest brim reach
            fun each(id: String) = objects.indices.map { k -> (Resolver.resolve(setup, id, plymeld.model.Context(k)).value as Number).toDouble() }
            val brimReach = objects.indices.maxOf { k ->
                if (Resolver.resolve(setup, "plan.mechanism.adhesion.brim.where", plymeld.model.Context(k)).value as String == "no_brim") 0.0
                else (Resolver.resolve(setup, "plan.mechanism.adhesion.brim.width", plymeld.model.Context(k)).value as Number).toDouble() +
                    (Resolver.resolve(setup, "plan.mechanism.adhesion.brim.gap", plymeld.model.Context(k)).value as Number).toDouble() }
            val jf = JobFacts(pf, each("plan.layer.walls.outer.line.width").max(),
                "stand-in: one outer-wall width (the widest of the objects') so neighboring objects' walls cannot fuse (brims may merge)",
                each("plan.technique.flat.layer.height").min(), each("hardware.filament.retract.z_hop").max(), brimReach)
            // every tool the plate prints with: each object's own spool and its parts' spools (multi-color)
            val tools = setup.objects.flatMap { ob -> listOf(ob.spool) + ob.parts.map { it.spool } }.map { setup.spool(it).tool }.toSet()
            val keep = o.pose != null && o.pose.label == "as given"
            // a layout whose g-code plymeld refuses to generate yet (e.g. an unimplemented support type)
            // has no prediction: the decision records why and falls back to its travel stand-in
            // each layout is predicted at its own objects' decided layer heights and infill directions (the settings' are only
            // the stand-ins the decisions start from; the slice prints the decided ones)
            val infillAngles = objects.indices.map { k -> if (o.decidedInfillAngle.getOrNull(k) == true) InfillAngle.degrees(posed[k].second) else null }
            // the surface technique of each object's flat tops where the search weighed it: what the layouts are predicted with
            val ironingDecided = objects.indices.map { k -> if (!PoseDecision.ironingWeighed(factsOf[k].first)) null else if (SurfaceTechnique.IRONING in surfaceTechniquesOf[k]) "top" else "none" }
            fun predictWith(s0: Setup, l: Layout): plymeld.core.Predict.Prediction {
                var s = withObjectValue(withObjectValue(withLayerHeights(s0, layoutHeights(l, s0.objects.size), "decided: the layout's flat layer height"),
                    "plan.layer.infill.angle", infillAngles, "decided: 45 deg to the object's long axis as posed"),
                    "plan.technique.ironing.type", ironingDecided, "decided: the surface technique of the flat tops, weighed against as laid")
                // the seams and the in-island retraction are decided after the plan: a layout's slice takes the plainest
                // provisionally, as a finalist's does (without it no layout could be predicted at all)
                val noSeam = objects.indices.map { k -> Resolver.resolveOrNull(s0, "plan.seam", plymeld.model.Context(k)) == null }
                if (noSeam.any { it }) {
                    s = withObjectValue(s, "plan.seam", noSeam.map { if (it) "back" else null }, "provisional for a layout's slice: the seam decision follows the plan")
                    s = withObjectValue(s, "plan.technique.scarf_seam.where", noSeam.map { if (it) "none" else null }, "provisional for a layout's slice: the seam decision follows the plan")
                }
                val noRetract = objects.indices.map { k -> Resolver.resolveOrNull(s0, "plan.mechanism.motion.no_retract_in_island", plymeld.model.Context(k)) == null }
                if (noRetract.any { it }) s = withObjectValue(s, "plan.mechanism.motion.no_retract_in_island", noRetract.map { if (it) true else null }, "provisional for a layout's slice: the retraction decision is made when the object is sliced")
                return predictLayout(s, l)
            }
            // the object order's baseline: every object in flat layers at its own baseline height, printed together
            val baseHeights = baselines.map { it.first }.takeIf { hs -> hs.all { it != null } }?.map { it!! }
            val baseWhat = baselines.withIndex().joinToString("; ") { (k, b) -> "'${objects[k].first}': " + (b.first?.let { "%.2f mm = ".format(Locale.ROOT, it) } ?: "none: ") + b.second }
            // each tool's head shape from the config (its outline: one at a time spaces the objects by it), read for the first
            // object printing with that tool; earlier the order got the bundled description only, so a configured
            // outline never reached it and one at a time always fell back to the clearance radius
            val machineHeads = tools.fold(machine) { m, t ->
                objects.indices.firstNotNullOfOrNull { k -> toolsOf(setup, k).let { ts -> if (t in ts) k to ts[t] else null } }
                    ?.let { (k, part) -> headFromConfig(m, t, setup, k, part) } ?: m }
            val tOrder = System.nanoTime()
            val order = ObjectOrderDecision.decide(machineHeads, tools, posed, techniques.map { it.value }, jf, weights, o.order, keepPlacement = keep, sequenceAsGiven = o.sequenceAsGiven,
                predict = { predictWith(setup, it) }, zeroScale = zeroScale, filament = filament,
                baselineLayers = baseHeights, baselineWhat = baseWhat,
                predictBaseline = baseHeights?.let { hs -> { l: Layout -> predictWith(withLayerHeights(setup, hs, "the baseline: $baseWhat"), l) } })
                .took((System.nanoTime() - tOrder) / 1e9)
            // the decisions per object that follow the pose, technique, and order (ObjectDecisions): on the setup as the slice will see
            // it — the decided layer heights, infill direction, and flat-top technique applied — and the object as placed
            val layoutOut = order.value
            val sDecided = withObjectValue(withObjectValue(withObjectValue(withLayerHeights(setup, layoutHeights(layoutOut, objects.size), "decided: the plan's flat layer height"),
                "plan.layer.infill.angle", infillAngles, "decided: 45 deg to the object's long axis as posed"),
                "plan.technique.ironing.type", ironingDecided, "decided: the surface technique of the flat tops, weighed against as laid"),
                "plan.view_from", posedViews(setup, poses.map { it.value }), POSED_VIEW_WHY)
            fun placedParts(k: Int) = layoutOut.objectIndex.indexOf(k).let { j -> if (j < 0) objects[k].second else layoutOut.placed[j].parts }
            fun timed(block: () -> Decision<*>?): Decision<*>? { val t = System.nanoTime(); return block()?.took((System.nanoTime() - t) / 1e9) }
            @Suppress("UNCHECKED_CAST")
            val bonds = objects.indices.map { k -> timed { ObjectDecisions.bond(sDecided, k, objects[k].first, placedParts(k), bondsOf[k], o.objects.getOrNull(k)?.bondCooling) } as Decision<BondCooling.Choice>? }
            val slicers = objects.indices.map { k -> plymeld.core.IncrementalSlicer(placedParts(k), "plan", objects[k].first, k, techniques[k].value) }
            @Suppress("UNCHECKED_CAST")
            val seams = objects.indices.map { k -> timed { ObjectDecisions.seams(sDecided, k, objects[k].first, slicers[k],
                explains[k].decision.finalists?.baseline?.firstOrNull { it.metric == Metric.TIME }?.value, o.objects.getOrNull(k)?.seam, o.objects.getOrNull(k)?.scarf) } as Decision<SeamDecision.Choice>? }
            val seamWhy = "decided: the seams, weighed by the job's weights (seam_scar against the scarf's extra pass)"
            // what the weights decided (a lock's half is the config's already)
            val sSeamed = withObjectValue(withObjectValue(sDecided, "plan.seam", seams.map { d -> d?.takeIf { !it.overridden && !it.value.placementLocked }?.value?.placement?.start }, seamWhy),
                "plan.technique.scarf_seam.where", seams.map { d -> d?.takeIf { !it.overridden && !it.value.scarfLocked }?.value?.scarf }, seamWhy)
            @Suppress("UNCHECKED_CAST")
            val retractions = objects.indices.map { k -> timed { ObjectDecisions.retraction(sSeamed, slicers[k]) } as Decision<Boolean>? }
            // the surface techniques and seams over each object's final volume portions: locked by the job's locks, or, where
            // the job locked nothing, as the search weighed them, for --explain
            for (k in explains.indices) {
                val pfk = factsOf[k].first
                val weighed = pfk.weighSurfaceTechniques.associateWith { fi -> when {
                    fi == SurfaceTechnique.IRONING && PoseDecision.ironingWeighed(pfk) -> SurfaceTechniques.Weighed(fi in surfaceTechniquesOf[k],
                        if (fi in surfaceTechniquesOf[k]) "chosen by the weights over as laid: the rough flat-top area it removes outweighs its pass's time (${fi.outcomes})"
                        else "as laid won: ironing's pass costs more time than the rough flat-top area it removes is worth at the weights' exchange rate (${fi.outcomes})")
                    fi == SurfaceTechnique.IRONING -> SurfaceTechniques.Weighed(false, PoseDecision.ironingNotWeighed(pfk)!!)
                    else -> SurfaceTechniques.Weighed(false, "no outcome is predicted for ${fi.short} yet, so it is chosen only when locked: as laid")
                } }
                explains[k] = explains[k].withSurfaceTechniques(SurfaceTechniques.choose(explains[k].portions, SurfaceTechniques.switches(sSeamed, k, weighed)))
            }
            val weighedOf = factsOf.map { (pfk, _) -> if (PoseDecision.ironingWeighed(pfk)) setOf(SurfaceTechnique.IRONING) else emptySet() }
            return Plan(chosen.indices.flatMap { listOfNotNull(chosen[it].first, techniques[it], islandDecisionsOf.getOrNull(it)) } + bonds.filterNotNull() + seams.filterNotNull() + retractions.filterNotNull() + order,
                order.value, explains, surfaceTechniquesOf, weighedOf, notes, factsOf.map { (pfk, _) -> pfk.supportSearched }, warnings, bondsOf, bonds, seams, retractions)
        }
    }
}
