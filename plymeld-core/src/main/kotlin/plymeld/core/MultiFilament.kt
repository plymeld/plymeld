package plymeld.core

import plymeld.program.Progress
import plymeld.program.Counter
import plymeld.geom.Booleans
import plymeld.geom.Polygon
import plymeld.model.SliceSettings

/**
 * Multi-color step 2: one object whose parts are on different spools, planned per filament.
 *
 *  - each layer is split into non-overlapping areas per filament ([FilamentAreas], overlap by
 *    [precedence]);
 *  - the WHOLE object is planned once (walls, regions) only as the reference for what each
 *    filament's paths rest on, are covered by, and bridge over;
 *  - each filament's areas get their own walls (a crisp edge where two colors meet), regions and
 *    paths, judged against the whole object;
 *  - brim and supports are made once, in the pass of [objectSpool] (the object's own spool);
 *  - the filaments' order on each layer ([layerOrder]), then the print steps ([steps]): a tool change
 *    between object blocks, each followed by the new tool's purge into the [PurgeTower].
 * The g-code itself is the writer's (plymeld-gcode).
 */
object MultiFilament {
    /** plan.mechanism.purge.into: where the purge after a change goes ([steps]). */
    val PURGE_INTO = listOf("tower", "infill", "infill_and_object")
    /** What a filament lays first after a change on a layer: its infill, hidden inside the model (sparse and internal solid). */
    private val HIDDEN_FIRST = setOf(Feature.SPARSE, Feature.SOLID)
    /** ... and on a layer where it lays no infill: its inner walls, before any outer wall. */
    private val HIDDEN_FIRST_NO_INFILL = setOf(Feature.INNER_WALL)
    /**
     * One filament's share of the object, or one settings group's share of a filament ([unit]:
     * parts on one spool with the same own settings), with its layers' areas and planned paths.
     */
    class FilamentPlan(val spool: String, val areas: List<List<Polygon>>, val layers: List<LayerPlanOut>, val unit: String = spool)

    /**
     * [timingMs]: wall time of each phase of [plan] (split, whole object, then each filament's walls/regions/paths).
     * [support] / [supportInterface]: per layer, the object's support paths, kept apart from every
     * filament's paths: which filament prints them is decided when the layer is ordered ([steps]).
     */
    class Result(val filaments: List<FilamentPlan>, val overlapUm2PerLayer: DoubleArray, val whole: Planner.Whole,
                 val timingMs: Map<String, Long> = emptyMap(),
                 val support: List<List<ToolPath>> = emptyList(), val supportInterface: List<List<ToolPath>> = emptyList())

    /**
     * The spools with paths on each layer: every filament's, plus [supportSpool] / [interfaceSpool]
     * where that layer has support / interface (null = whichever filament is loaded: no spool of its own).
     */
    fun present(r: Result, supportSpool: String?, interfaceSpool: String?): List<Set<String>> =
        r.overlapUm2PerLayer.indices.map { i ->
            r.filaments.filter { it.layers[i].paths.isNotEmpty() }.map { it.spool }.toSet() +
                listOfNotNull(supportSpool?.takeIf { r.support.getOrNull(i)?.isNotEmpty() == true },
                    interfaceSpool?.takeIf { r.supportInterface.getOrNull(i)?.isNotEmpty() == true })
        }

    /**
     * [partLayers]: per part, per layer, its (cleaned) outline; [spools]: each part's spool;
     * [precedence]: part indices, the first takes contested area first.
     * Per-part settings: [units] names each part's settings group (default: its spool, i.e. one group
     * per filament) and [unitSettings] the settings a group is planned with (default [s], the
     * object's). Groups on one spool are separate areas, each with its own walls, regions, and paths.
     * The object-wide things (brim, supports) are planned once, with the object's settings, in the
     * unit named [objectSpool].
     */
    fun plan(s: SliceSettings, partLayers: List<List<List<Polygon>>>, spools: List<String>, precedence: List<Int>,
             layerTopsUm: LongArray, objectSpool: String, units: List<String> = spools, unitSettings: Map<String, SliceSettings> = emptyMap(),
             /** Layers of varying height (adaptive): shells are counted layer by layer (Planner.shellCounts). */
             varyingLayers: Boolean = false,
             /** Where support goes when it is decided per spot (the plan's SupportPlan as Planner.paths takes it); null = the switch alone decides. Without it the plan's spots were never printed for a multi-color object. */
             supportSpots: Supports.Regions? = null,
             /** Planned inserts as Planner.paths takes them: the layer over each laid onto it. */
             covers: List<Supports.Box> = emptyList(), progress: Progress = Progress.NONE,
             /** By layer, what else it is laid onto (Planner.paths' standsOn). */
             standsOn: Map<Int, List<plymeld.geom.Polygon>> = emptyMap(),
             /** Per layer, areas whose paths are left out (Planner.paths' omit: curved islands, laid in their own layers); null = none. */
             omit: List<List<Polygon>>? = null): Result {
        val n = layerTopsUm.size
        require(partLayers.all { it.size == n }) { "every part needs one outline per layer" }
        require(units.size == spools.size) { "${units.size} units for ${spools.size} parts" }
        val spoolOf = HashMap<String, String>()
        for ((u, sp) in units.zip(spools)) require(spoolOf.getOrPut(u) { sp } == sp) { "unit '$u' spans spools ${spoolOf[u]} and $sp" }
        require(spoolOf[objectSpool].let { it == null || it == objectSpool }) { "unit '$objectSpool' must be on spool $objectSpool" }
        spoolOf[objectSpool] = objectSpool
        val ms = LinkedHashMap<String, Long>()
        fun <T> timed(name: String, body: () -> T): T { val t = System.nanoTime(); val r = body(); ms[name] = (ms[name] ?: 0L) + (System.nanoTime() - t) / 1_000_000; return r }
        // the thinnest area the walls print: a filament's area thinner than it goes to a neighbor (FilamentAreas.handOverSlivers).
        // Variable-width walls print an area down to the smallest feature (with a line of the narrowest width); handing
        // over everything under the narrowest LINE split a two-color Benchy's thin cabin strips so neither color
        // printed them
        val narrowest = if (s.wallGenerator == "arachne") s.minFeatureUm else s.outerWallWidthUm
        val splits = timed("split") { Parallel.map(n, progress.Counter("filament areas", n)) { i -> FilamentAreas.split(partLayers.map { it[i] }, units, precedence, narrowest) } }
        // The parts' union, closed like any slice (slice_closing_radius): parts sliced one by one meet
        // at hairline seams, which read as "exposed from below" and, grown two nozzle widths as bridge
        // anchors, bridged 1,393 mm2 of a raised QR code over solid material.
        val union = timed("union") { Parallel.map(n) { i ->
            val u = Booleans.normalize(partLayers.flatMap { it[i] })
            // united with the raw union: a closing only ever adds, but its round joins, as polygons,
            // can shave a sliver off a convex corner
            if (s.closingRadiusUm <= 0 || u.isEmpty()) u
            else Booleans.union(u, plymeld.geom.Offset.offset(plymeld.geom.Offset.offset(u, s.closingRadiusUm, plymeld.geom.Offset.Join.ROUND), -s.closingRadiusUm, plymeld.geom.Offset.Join.ROUND))
        } }
        // The whole object's reference walls, which the vertical-shell cover test reads, are built with
        // EACH GROUP'S OWN wall settings: built with the object's, a part with fewer walls (an inner
        // core part: 2 of the object's 4) had two wall widths of its infill read as uncovered, solid
        // on every layer. One set per distinct wall configuration.
        val refWalls = HashMap<List<Long>, Planner.WallsOut>()
        fun wholeFor(st: SliceSettings) = Planner.Whole(union, refWalls.getOrPut(
            listOf(st.wallLoops.toLong(), st.outerWallWidthUm, st.innerWallWidthUm, st.firstLayerWidthUm, st.elephantFootUm)) {
            timed("whole walls") { Planner.walls(st, union, layerTopsUm, reference = true) } })
        val wholeW = wholeFor(s).w
        // the object-wide unit plans brim and supports even when no part is in it
        val order = (units.distinct() + objectSpool).distinct()
        class Mid(val u: String, val st: SliceSettings, val areas: List<List<Polygon>>, val w: Planner.WallsOut, val r: List<LayerRegions>)
        val mids = order.map { u ->
            val st = unitSettings[u] ?: s
            val areas = splits.map { it.bySpool[u] ?: emptyList() }
            val w = timed("walls") { Planner.walls(st, areas, layerTopsUm, wholeFirst = union[0], progress = progress) }
            // where this group's own parts carry on to the next layer: what stands on them there is not another part
            val mine = units.indices.filter { units[it] == u }
            val selfCover = if (!st.topUnderParts) null else timed("self cover") { Parallel.map(n) { i ->
                if (i + 1 >= n) emptyList() else Booleans.normalize(mine.flatMap { k ->
                    val a = partLayers[k][i]; val b = partLayers[k][i + 1]
                    if (a.isEmpty() || b.isEmpty()) emptyList() else Booleans.intersection(a, b) })
            } }
            Mid(u, st, areas, w, timed("regions") { Planner.regions(st, w, wholeFor(st), if (varyingLayers) Planner.shellCounts(st, layerTopsUm) else null, selfCover, progress) })
        }
        // the sparse infill actually printed on each layer: every unit's (they do not overlap)
        val hs = Planner.heights(layerTopsUm)
        // the parts' sparse lines laid out from the whole object's middle, as each part's own paths lay them (Planner.patternCenter)
        val center = Planner.patternCenter(union)
        val whole = Planner.Whole(union, wholeW, List(n) { i -> mids.flatMap { it.r[i].sparse } },
            List(n) { i -> mids.filter { it.r[i].sparse.isNotEmpty() }.flatMap { Planner.sparseFamilies(it.st, i, layerTopsUm[i], hs[i], center = center) }
                .distinctBy { Triple(it.angleDeg, it.spacingUm, it.offsetUm) } })
        val support = MutableList<List<ToolPath>>(n) { emptyList() }
        val supportInterface = MutableList<List<ToolPath>>(n) { emptyList() }
        val plans = mids.map { m ->
            var layers = timed("paths") { Planner.paths(m.st, m.areas, m.w, m.r, layerTopsUm, whole, objectWide = m.u == objectSpool, support = supportSpots, covers = covers, standsOn = standsOn, progress = progress, omit = omit) }
            if (m.u == objectSpool) layers = layers.mapIndexed { i, l ->
                // supports leave the object-wide unit: the filament that prints them is chosen per layer
                support[i] = l.paths.filter { it.feature == Feature.SUPPORT }
                supportInterface[i] = l.paths.filter { it.feature == Feature.SUPPORT_INTERFACE }
                LayerPlanOut(l.index, l.topZUm, l.heightUm, l.paths.filter { it.feature != Feature.SUPPORT && it.feature != Feature.SUPPORT_INTERFACE })
            }
            FilamentPlan(spoolOf.getValue(m.u), m.areas, layers, m.u)
        }
        return Result(plans, DoubleArray(n) { splits[it].overlapUm2 }, whole, ms, support, supportInterface)
    }

    /**
     * Step 3 — the order of filaments within each layer ([present]: the spools with paths on each
     * layer). Each layer starts with the filament the previous one ended with, when it prints
     * there; the others follow in [spoolOrder]. Layer 0 starts with [firstSpool] (the one carrying
     * the brim) when it prints there. That makes each layer's changes the minimum (filaments − 1),
     * plus one between layers only when the last filament of a layer does not print on the next.
     */
    fun layerOrder(present: List<Set<String>>, spoolOrder: List<String>, firstSpool: String): List<List<String>> {
        var last = firstSpool
        return present.map { here ->
            val seq = spoolOrder.filter { it in here }.let { s -> if (last in s) listOf(last) + (s - last) else s }
            if (seq.isNotEmpty()) last = seq.last()
            seq
        }
    }

    /** Tool changes along [order] when printing starts with [startTool] loaded. */
    fun changes(order: List<List<String>>, toolOf: (String) -> Int, startTool: Int): Int {
        var t = startTool; var n = 0
        for (sp in order.flatten()) { val nt = toolOf(sp); if (nt != t) { n++; t = nt } }
        return n
    }

    /** Tool changes on each layer along [order], starting with [startTool] loaded. */
    fun changesPerLayer(order: List<List<String>>, toolOf: (String) -> Int, startTool: Int): IntArray {
        var t = startTool
        return IntArray(order.size) { i -> var n = 0; for (sp in order[i]) { val nt = toolOf(sp); if (nt != t) { n++; t = nt } }; n }
    }

    /**
     * Purges on each layer along [order]: a tool change, and a spool swapped by hand on a tool (the spool printed differs from
     * the one the tool holds, [loadedOn] at the start, then what it last printed: a swap is purged too).
     * The tower's bands are sized by this; [steps] purges the same way.
     */
    fun purgesPerLayer(order: List<List<String>>, toolOf: (String) -> Int, startTool: Int, loadedOn: Map<Int, String> = emptyMap()): IntArray {
        var t = startTool
        val held = HashMap<Int, String>(loadedOn)
        return IntArray(order.size) { i -> var n = 0
            for (sp in order[i]) { val nt = toolOf(sp); val last = held[nt]; if (nt != t || (last != null && last != sp)) n++; t = nt; held[nt] = sp }
            n }
    }

    /**
     * One object's lines for [plateSteps]: its [filaments] (each a spool's layers, by layer index; an object shorter than the
     * plate has fewer), its support and interface paths per layer with the spools that print them (null = whichever
     * filament is loaded), under its program [name].
     */
    class Laid(val name: String, val filaments: List<FilamentPlan>, val support: List<List<ToolPath>> = emptyList(),
               val supportInterface: List<List<ToolPath>> = emptyList(), val supportSpool: String? = null, val interfaceSpool: String? = null) {
        /** The spools with paths on layer [i] ([present], for one object of a plate). */
        fun present(i: Int): Set<String> = filaments.filter { it.layers.getOrNull(i)?.paths?.isNotEmpty() == true }.map { it.spool }.toSet() +
            listOfNotNull(supportSpool?.takeIf { support.getOrNull(i)?.isNotEmpty() == true }, interfaceSpool?.takeIf { supportInterface.getOrNull(i)?.isNotEmpty() == true })
    }

    /**
     * The tower's layer each of the layers topping at [layerTopsUm] is printed with: layers topping at one height (objects on
     * different layer heights meeting there) share one, so the tower has a layer per height.
     */
    fun towerLayers(layerTopsUm: LongArray): IntArray { var t = -1; return IntArray(layerTopsUm.size) { i -> if (i == 0 || layerTopsUm[i] != layerTopsUm[i - 1]) t++; t } }

    /** One multi-filament object as print steps ([plateSteps] with that one object). */
    fun steps(r: Result, order: List<List<String>>, toolOf: (String) -> Int, objectName: String, layerTopsUm: LongArray, startTool: Int,
              tower: PurgeTower? = null,
              tempOf: (String, Int) -> Int? = { _, _ -> null }, idleOf: (String, Int) -> Int? = { _, _ -> null },
              supportSpool: String? = null, interfaceSpool: String? = null,
              numberOf: (String) -> Int = { sp -> sp.removePrefix("spool ").trim().toIntOrNull() ?: 0 },
              loadedOn: Map<Int, String> = emptyMap(),
              purgeInto: String = "tower"): List<plymeld.program.Step> =
        plateSteps(listOf(Laid(objectName, r.filaments, r.support, r.supportInterface, supportSpool, interfaceSpool)), order, toolOf, layerTopsUm, startTool, tower,
            tempOf, idleOf, numberOf, loadedOn, purgeInto)

    /**
     * Objects laid per filament as print steps, layer by layer in [order] (from [layerOrder] over every object's spools):
     * on each layer each filament in turn lays its lines of every object, object after object, so a layer costs one change
     * per filament however many objects stand on the plate. A
     * tool change goes BETWEEN object blocks, never inside one: with exclude-object, moves inside a
     * canceled object's block are skipped, and a tool change must not be.
     * [tower]: after each change the NEW tool purges its band; the tower's base paths (wall and
     * spare bands, as its layout gives them) are printed at the layer's first visit (before its first
     * change) or, on a layer without a change, at the layer's end — up to the tower's top. After a change, the new
     * filament's first lines on each object are its infill on the layer, hidden inside it (its inner walls where it has no
     * infill there); then the rest in its order.
     */
    fun plateSteps(objects: List<Laid>, order: List<List<String>>, toolOf: (String) -> Int, layerTopsUm: LongArray, startTool: Int,
              tower: PurgeTower? = null,
              /** Printing temperature of a spool on a layer, and the idle temperature of the tool put down (null = no command). */
              tempOf: (String, Int) -> Int? = { _, _ -> null }, idleOf: (String, Int) -> Int? = { _, _ -> null },
              /** A spool's filament number (the loadout's slot: "spool 5" is filament 5), for a FilamentSwap's note. */
              numberOf: (String) -> Int = { sp -> sp.removePrefix("spool ").trim().toIntOrNull() ?: 0 },
              /** The spool each tool holds when the print starts (the loadout's own filament per tool): a tool whose first spool differs swaps before its first line. */
              loadedOn: Map<Int, String> = emptyMap(),
              /**
               * Where the purge after a change goes (plan.mechanism.purge.into): "tower" = all of it into the tower;
               * "infill" = the new spool's own sparse infill on the layer is printed first, right after the change (the dirtiest
               * plastic hidden), its volume counted against the purge, and the tower takes what is left; "infill_and_object" =
               * its sparse, then its hidden solid infill, then the tower.
               */
              purgeInto: String = "tower",
              /**
               * Each layer's thickness (µm), when not the step from the layer before (a plate of objects on different layer
               * heights: [layerTopsUm] then holds a height more than once, a layer of each thickness that tops there), and the
               * layer of its object(s) it is (for the layer start); null = one stack of layers.
               */
              heightsUm: LongArray? = null, objectLayers: IntArray? = null): List<plymeld.program.Step> {
        require(purgeInto in PURGE_INTO) { "plan.mechanism.purge.into '$purgeInto' is not known (${PURGE_INTO.joinToString()})" }
        val hs = heightsUm ?: Planner.heights(layerTopsUm)
        val byspool = objects.map { o -> o.filaments.groupBy { it.spool } }
        val perLayer = purgesPerLayer(order, toolOf, startTool, loadedOn)
        // layers topping at one height share one layer of the tower ([towerLayers]): its base once, their purges in turn
        val towerOf = towerLayers(layerTopsUm)
        val perTower = IntArray((towerOf.lastOrNull() ?: -1) + 1).also { a -> for (i in perLayer.indices) a[towerOf[i]] += perLayer[i] }
        val out = ArrayList<plymeld.program.Step>()
        var tool = startTool; var spoolNow: String? = null
        val spoolOnTool = HashMap<Int, String>(loadedOn)      // the spool each tool holds: the loadout's, then what it last printed with
        fun extrude(p: ToolPath, i: Int, t: Int) {
            out += Scarf.extrudes(p, layerTopsUm[i], t)
        }
        /** A path of the tower: on its first layer (its brim included) no faster than the tower's first-layer speed, when set. */
        fun towerExtrude(p: ToolPath, i: Int, t: Int) {
            val cap = tower?.firstLayerSpeed
            if (towerOf[i] != 0 || cap == null) { extrude(p, i, t); return }
            val m = p.xy.size / 2
            out += plymeld.program.Extrude(p.feature.label, p.xy, p.closed, layerTopsUm[i], p.widthUm, p.heightUm, t, p.overhangShare, p.ePerMmOverride,
                p.retractBefore, p.widths, hopOnly = p.hopOnly, speedMax = DoubleArray(if (p.closed) m else m - 1) { cap })
        }
        /** Object [o]'s planned paths of [sp] on layer [i]. */
        fun pathsOf(o: Int, sp: String, i: Int): List<ToolPath> = byspool[o][sp].orEmpty().flatMap { f -> f.layers.getOrNull(i)?.paths.orEmpty() }
        /** The paths of [sp] on layer [i] in object [o] that take the purge first (none with the tower alone). */
        fun purgePaths(o: Int, sp: String, i: Int): List<ToolPath> = if (purgeInto == "tower") emptyList() else {
            val into = if (purgeInto == "infill") setOf(Feature.SPARSE) else setOf(Feature.SPARSE, Feature.SOLID)
            pathsOf(o, sp, i).filter { it.feature in into }
        }
        /** Plastic (mm³) in [ps]: each bead's cross-section (as the tower sizes its own lines) times its length. */
        fun mm3(ps: List<ToolPath>) = ps.sumOf { p ->
            val m = p.xy.size / 2; val segs = if (p.closed) m else m - 1
            val len = (0 until segs).sumOf { k -> val j = (k + 1) % m; Math.hypot((p.xy[2 * j] - p.xy[2 * k]).toDouble(), (p.xy[2 * j + 1] - p.xy[2 * k + 1]).toDouble()) } / 1000.0
            val h = p.heightUm / 1000.0; val w = p.widthUm / 1000.0
            len * h * (w - h * (1 - Math.PI / 4))
        }
        var tl: PurgeTower.Layer? = null; var baseDone = true; var k = 0
        for (i in layerTopsUm.indices) {
            out += plymeld.program.LayerStart(i, layerTopsUm[i], hs[i], objectLayer = objectLayers?.get(i) ?: i)
            val ti = towerOf[i]
            if (i == 0 || towerOf[i - 1] != ti) {
                // what each change on this layer of the tower (every layer topping here) still purges into the tower after
                // the new spool's own infill took its share
                val needs: List<Double>? = if (tower == null || purgeInto == "tower") null else {
                    var tl0 = tool; val held = HashMap(spoolOnTool); val ns = ArrayList<Double>()
                    var j = i
                    while (j < layerTopsUm.size && towerOf[j] == ti) {
                        for (sp in order[j]) {
                            val t = toolOf(sp); val last = held[t]
                            if (t != tl0 || (last != null && last != sp)) ns += Math.max(0.0, tower.purgeMm3 - objects.indices.sumOf { o -> mm3(purgePaths(o, sp, j)) })
                            tl0 = t; held[t] = sp
                        }
                        j++
                    }
                    ns
                }
                tl = if (tower != null && ti <= tower.top) tower.layer(ti, perTower[ti], needs) else null
                baseDone = tl == null; k = 0
            }
            val tlNow = tl
            fun base() { if (!baseDone) { tlNow!!.base.forEach { towerExtrude(it, i, tool) }; baseDone = true } }
            var first = true
            for (sp in order[i]) {
                val t = toolOf(sp)
                var changed = false
                val purged = HashSet<ToolPath>()
                if (t != tool) {
                    base()
                    out += plymeld.program.ToolChange(t, sp, tempOf(sp, i), tool, spoolNow?.let { idleOf(it, i) }); tool = t; changed = true
                }
                // the tool holds another spool than it last printed with: a filament swapped by hand at a pause (several spools
                // on one tool; Pauses.insert requires the pause). Its temperature set at the swap whatever came before: the mark
                // may be moved to the pause, where the tool change's M109 is not the one in force
                val last = spoolOnTool[t]
                val swapped = last != null && last != sp
                if (swapped) { if (!changed) base(); out += plymeld.program.FilamentSwap(t, last, sp, numberOf(last), numberOf(sp), tempOf(sp, i)) }
                spoolOnTool[t] = sp
                spoolNow = sp
                if (changed || swapped) {
                    // the purge after a change, and after a swap by hand (the old filament is in the nozzle
                    // as after a change): after the swap's note, so the note stays at the pause; into the new
                    // spool's own infill first, each in its object's block (plan.mechanism.purge.into), then the tower's share
                    for ((o, obj) in objects.withIndex()) {
                        val into = purgePaths(o, sp, i)
                        if (into.isEmpty()) continue
                        out += plymeld.program.ObjectStart(obj.name)
                        for (p in into) { extrude(p, i, t); purged += p }
                        out += plymeld.program.ObjectEnd(obj.name)
                    }
                    tlNow?.purges?.get(k)?.forEach { towerExtrude(it, i, t) }
                    k++
                }
                for ((o, obj) in objects.withIndex()) {
                    val sup = obj.support.getOrNull(i).orEmpty(); val itf = obj.supportInterface.getOrNull(i).orEmpty()
                    val supHere = obj.supportSpool == sp || (obj.supportSpool == null && first)
                    val itfHere = obj.interfaceSpool == sp || (obj.interfaceSpool == null && first)
                    val planned = pathsOf(o, sp, i)
                    // on a plate, an object with nothing of this filament on the layer gets no block
                    if (objects.size > 1 && planned.isEmpty() && !(supHere && sup.isNotEmpty()) && !(itfHere && itf.isNotEmpty())) continue
                    out += plymeld.program.ObjectStart(obj.name)
                    // support first on the layer (it holds up what this layer's model prints over)
                    if (supHere) for (p in sup) extrude(p, i, t)
                    if (itfHere) for (p in itf) extrude(p, i, t)
                    // after a change (or a swap by hand), the new filament's first lines on the model are its hidden infill (sparse and
                    // internal solid), in their planned order, then everything else in its order: a nozzle back from the dock
                    // (from its idle temperature, past the purge) lays its first, least even lines where they do not show. Its
                    // outer wall had come first: on a two-color nameplate every change back to the base color began on the
                    // lettering's visible face. On a layer where it lays no infill, its inner walls come first (hidden, though next
                    // to the face), before any outer wall; with neither, its first line is whatever comes first.
                    if (!changed && !swapped) { for (p in planned) if (p !in purged) extrude(p, i, t) } else {
                        val rest = planned.filter { it !in purged }
                        val firstKind = if (rest.any { it.feature in HIDDEN_FIRST }) HIDDEN_FIRST else HIDDEN_FIRST_NO_INFILL
                        val laid = rest.filter { it.feature in firstKind } + rest.filter { it.feature !in firstKind }
                        // a line whose predecessor is not the one it was planned after retracts on the travel into it: whether that
                        // travel retracts was decided for the planned order (inside an island, crossing no wall), and moved lines'
                        // travels can cross open air (unretracted, they quadrupled the strings over air on that nameplate)
                        val plannedBefore = java.util.IdentityHashMap<ToolPath, ToolPath?>()
                        for ((q, p) in planned.withIndex()) plannedBefore[p] = planned.getOrNull(q - 1)
                        for ((q, p) in laid.withIndex()) {
                            val before = laid.getOrNull(q - 1)
                            extrude(if (plannedBefore[p] === before || p.retractBefore) p else p.withRetract(true), i, t)
                        }
                    }
                    out += plymeld.program.ObjectEnd(obj.name)
                }
                first = false
            }
            // a layer with support but no model paths: print it with whatever is loaded
            if (first) for (obj in objects) {
                val sup = obj.support.getOrNull(i).orEmpty(); val itf = obj.supportInterface.getOrNull(i).orEmpty()
                if (obj.supportSpool == null && sup.isNotEmpty() || obj.interfaceSpool == null && itf.isNotEmpty()) {
                    out += plymeld.program.ObjectStart(obj.name)
                    if (obj.supportSpool == null) for (p in sup) extrude(p, i, tool)
                    if (obj.interfaceSpool == null) for (p in itf) extrude(p, i, tool)
                    out += plymeld.program.ObjectEnd(obj.name)
                }
            }
            // the tower's base on a layer of it with no change: at the end of the last layer topping there
            if (i == layerTopsUm.size - 1 || towerOf[i + 1] != ti) base()
        }
        return out
    }
}
