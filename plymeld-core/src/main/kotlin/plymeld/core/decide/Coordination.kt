package plymeld.core.decide

import java.util.Locale

/**
 * Coordinating print techniques.
 *
 * The coordinator works in volumes and in what must exist first. It knows nothing of layers, a
 * technique-level concept: layer heights, layer
 * counts, and layer tops stay inside the techniques (flat layers' survey, its trims to its own layer grid, its plans).
 * Where a technique's detail has to be shown, the technique supplies it as words ([Portion.how], [Proposal.reason]).
 *
 * - A **portion** is a volume of one object laid by one technique, at one step in time ([Step]), in a print [Order]
 *   beside its neighbor where it has one. Its volume is a z band ([Volume.ZBand]), an XY region within a height
 *   range ([Volume.Region]), or the air under one, outside the model ([Volume.Under]: support's, [Support]), side by
 *   side with the model's bands.
 * - A **proposal** is a volume a technique stands behind, with its reason and **the cuts it allows** ([Cuts]).
 * - The coordinator only chooses among proposals, trims one only where its technique allows (and the technique says
 *   where: [use]), and fills what is left with flat layers ([fill]); it never cuts every proposal at
 *   every boundary.
 * - A **handoff contract** says what a technique needs from what came before, what it leaves, and what must exist
 *   before it, as data ([Contract]).
 *
 * - Techniques are typed by the dimension of what they decide ([TechniqueKind]): one
 *   technique per portion PER KIND. The above is the volume techniques', which own all the plastic. Each volume portion
 *   hands its exposed surfaces over with what they face ([handOver], [SurfaceRegion]); each region gets exactly one
 *   **surface technique** ([SurfaceTechnique], or none), chosen among those that can serve what it faces ([chooseSurfaceTechniques]). Each loop's seam
 *   is a **curve** ([seamCurves]) with one seam style ([SeamStyle]).
 *
 * The joint search (PoseDecision.decideWithTechnique): each proposal is sampled on its own volume against
 * flat layers over the same volume, overlapping proposals form conflict [groups], a dynamic program along the print
 * order with the handoff as its state chooses per group on contributions linearised at the baseline ([chooseSets]),
 * the best few are re-scored exactly and the finalists predicted in full; a surface technique the job does not lock is
 * weighed against as laid (SurfaceTechniques).
 */
object Coordination {
    /** Where in a print a portion is: a volume. */
    sealed interface Volume {
        val words: String
        fun tree(): Map<String, Any?>

        /**
         * A z band of the object as posed, [fromMm] to [toMm] above its bottom ([toMm] infinite = to the top).
         */
        class ZBand(val fromMm: Double, val toMm: Double) : Volume {
            override val words: String get() = if (toMm.isInfinite()) "z %.2f mm to the top".format(Locale.ROOT, fromMm)
                else "z %.2f-%.2f mm".format(Locale.ROOT, fromMm, toMm)
            override fun tree() = linkedMapOf<String, Any?>("shape" to "z_band", "from_mm" to fromMm, "to_mm" to toMm.takeIf { it.isFinite() })
            override fun equals(other: Any?) = other is ZBand && Math.abs(fromMm - other.fromMm) < 1e-9 && (toMm == other.toMm || Math.abs(toMm - other.toMm) < 1e-9)
            override fun hashCode() = fromMm.hashCode()
        }

        /**
         * An XY box within a height range of the object as posed (the side-by-side shape): x and y from the posed
         * object's corner (its least x and y), z from its bottom, so the same numbers hold wherever the object is placed.
         */
        class Region(val fromMm: Double, val toMm: Double, val x0: Double, val x1: Double, val y0: Double, val y1: Double) : Volume {
            override val words: String get() = "z %.2f-%.2f, x %.2f-%.2f, y %.2f-%.2f mm (from the posed object's corner)".format(Locale.ROOT, fromMm, toMm, x0, x1, y0, y1)
            override fun tree() = linkedMapOf<String, Any?>("shape" to "region", "from_mm" to fromMm, "to_mm" to toMm, "min_x" to x0, "max_x" to x1, "min_y" to y0, "max_y" to y1)
            /** Whether (x, y, z), in the same frame, lies in the box. */
            fun contains(x: Double, y: Double, z: Double) = x >= x0 - 1e-9 && x <= x1 + 1e-9 && y >= y0 - 1e-9 && y <= y1 + 1e-9 && z >= fromMm - 1e-9 && z <= toMm + 1e-9
            /** Whether the boxes share volume: open in x and y (neighbors touching along a side are independent), closed in z (a flat underside's region has no height, and still overlaps itself). */
            fun overlaps(o: Region) = x0 < o.x1 - 1e-9 && o.x0 < x1 - 1e-9 && y0 < o.y1 - 1e-9 && o.y0 < y1 - 1e-9 && fromMm <= o.toMm + 1e-9 && o.fromMm <= toMm + 1e-9
            private fun near(a: Double, b: Double) = Math.abs(a - b) < 1e-9
            override fun equals(other: Any?) = other is Region && near(fromMm, other.fromMm) && near(toMm, other.toMm) && near(x0, other.x0) && near(x1, other.x1) && near(y0, other.y0) && near(y1, other.y1)
            override fun hashCode() = Math.round(fromMm * 1e6).hashCode() * 31 + Math.round(x0 * 1e6).hashCode()
        }

        /**
         * The air under [region], outside the model: support's volume, from what it stands on ([standsOn], words) up
         * to the overhang's underside. It overlaps no volume of the model's: support never competes for the model's plastic.
         */
        class Under(val region: Region, val standsOn: String) : Volume {
            override val words: String get() = "the air under ${region.words}, standing on $standsOn"
            override fun tree() = linkedMapOf<String, Any?>("shape" to "under", "region" to region.tree(), "stands_on" to standsOn)
            override fun equals(other: Any?) = other is Under && region == other.region
            override fun hashCode() = region.hashCode()
        }

        companion object {
            val WHOLE: Volume = ZBand(0.0, Double.POSITIVE_INFINITY)
            /** Where a volume starts and ends in height (for ordering portions bottom to top). */
            fun lo(v: Volume): Double = when (v) { is ZBand -> v.fromMm; is Region -> v.fromMm; is Under -> v.region.fromMm }
            fun hi(v: Volume): Double = when (v) { is ZBand -> v.toMm; is Region -> v.toMm; is Under -> v.region.toMm }
        }
    }

    /** When a portion is laid: depositing the plastic, or a later pass over plastic already laid (not built yet). */
    enum class Step(val id: String) { DEPOSIT("deposit") }

    /**
     * The print order of a portion beside another (searched, not assumed): its steps interleaved with its
     * neighbor's, layer by layer, or finished before the neighbor's steps over its height start. Today only a support
     * column has a neighbor beside it (the model's layers).
     */
    enum class Order(val id: String, val words: String) {
        INTERLEAVED("interleaved", "printed layer by layer with the model's layers beside it"),
        FIRST("first", "finished before the model's layers beside it"),
    }

    /** Where a technique's volume may be cut (the cuts it allows). */
    enum class Cuts(val id: String, val words: String) {
        ANYWHERE("anywhere", "anywhere in its volume"),
        LOWER_EDGE("lower_edge", "only along its lower boundary, where it starts"),
        NONE_ACROSS_SPAN("none_across_span", "none across its span: anchor to anchor"),
        WHOLE_OBJECT("whole_object", "none: it lays the whole object"),
        REGION_BOUNDARY("region_boundary", "only along the overhang region's own boundary: the region whole, or not at all"),
    }

    fun cutsOf(t: PrintTechnique): Cuts = when (t) {
        PrintTechnique.CURVED_LAYERS -> Cuts.LOWER_EDGE
        PrintTechnique.LIFTED_BRIDGE -> Cuts.NONE_ACROSS_SPAN
        PrintTechnique.CURVED_UNDERSIDE -> Cuts.WHOLE_OBJECT
        PrintTechnique.SUPPORT -> Cuts.REGION_BOUNDARY
        else -> Cuts.ANYWHERE
    }

    /** What a technique starts on and what it leaves. */
    enum class Surface(val id: String, val words: String) {
        BED("bed", "the bed"),
        FLAT_TOP("flat_top", "a flat top"),
        ANCHORS("anchors", "anchors at both ends: what holds it up, already there"),
        WARPED_TOP("warped_top", "a warped surface (the model's own top): nothing built on it"),
        /** Support may stand on the model (resting on it marks that surface). */
        MODEL("model", "a surface of the model (support resting on it marks it)"),
        /** What support leaves: the overhang above starts on it (its interface layers). */
        INTERFACE("interface", "a support interface: the overhang above it starts on it"),
    }

    /**
     * A technique's handoff contract: the surfaces it can start on ([entry]), what it leaves ([exit]), what must exist
     * before it ([first]), and the tool and temperature it starts and ends at.
     */
    class Contract(val entry: List<Surface>, val exit: Surface, val first: String, val toolAndTemperature: String)

    private const val SAME_TOOL = "the object's tool and nozzle temperature, unchanged at the handoff"

    /** Today's techniques' contracts, as built: what their planners assume, written down. */
    fun contractOf(t: PrintTechnique): Contract = when (t) {
        PrintTechnique.CURVED_LAYERS -> Contract(listOf(Surface.BED, Surface.FLAT_TOP), Surface.WARPED_TOP,
            "everything below where it starts; nothing is built above it", SAME_TOOL)
        PrintTechnique.CURVED_UNDERSIDE -> Contract(listOf(Surface.BED), Surface.WARPED_TOP, "nothing: it lays the whole object", SAME_TOOL)
        // the anchors are part of a lifted bridge's own volume: it lays what stands on the bed first, so it starts on the bed
        PrintTechnique.LIFTED_BRIDGE -> Contract(listOf(Surface.BED, Surface.ANCHORS), Surface.WARPED_TOP,
            "its anchors: what stands on the bed is laid first, then each bridge along its underside",
            "the object's tool; a bridge's own temperature and speeds where set (plan.technique.bridge)")
        // support: stands on the bed or the model, leaves an interface the overhang starts on, prints before what it holds up
        PrintTechnique.SUPPORT -> Contract(listOf(Surface.BED, Surface.MODEL), Surface.INTERFACE,
            "what it stands on; it prints before the overhang it holds up, beside the model's layers (its order is searched: interleaved, or finished first)",
            "the support slot's filament where plan.support.slot names one, else the object's; the object's nozzle temperature")
        // flat layers start on the bed, a flat top, or a support interface (the overhang above a support column)
        else -> Contract(listOf(Surface.BED, Surface.FLAT_TOP, Surface.INTERFACE), Surface.FLAT_TOP, "everything below it", SAME_TOOL)
    }

    /** Whether a portion that leaves [exit] can be followed by one whose technique starts on one of [entry]. */
    fun compatible(exit: Surface, entry: List<Surface>): Boolean = exit in entry

    /**
     * A portion: [volume] at [step], laid by [technique] in its [variant] (a way of the technique's own, e.g. flat
     * layers following the surfaces; null = its plain way), with [how] in the technique's words.
     */
    class Portion(val volume: Volume, val step: Step, val technique: PrintTechnique, val variant: String?, val how: String?,
                  /** Its print order beside a neighbor (a support column beside the model's layers); null = in the stack, by height. */
                  val order: Order? = null) {
        val name: String get() = variant ?: technique.short
    }

    /**
     * A proposal: [technique] (in its [variant]) stands behind [volume] for [reason]; [by] says how it was proposed;
     * [rank] orders proposals of one technique by how much it expects of them (largest first); [locked] = a requirement
     * (a plan's lock, a locked spot), always in the chosen set: every option of its group holds a member of its
     * [requirement] (several proposals may share one, e.g. the two print orders of one locked support spot: one of them
     * is always chosen); [order] = the print order it proposes beside its neighbor (support), null = in the stack.
     */
    class Proposal(val id: String, val technique: PrintTechnique, val variant: String?, val volume: Volume, val reason: String,
                   val by: String, val rank: Double = 0.0, val locked: Boolean = false,
                   /** How its technique trims it, in the technique's words (where it allows a cut), or null. */
                   val trimNote: String? = null, requirement: String? = null, val order: Order? = null) {
        val requirement: String? = requirement ?: if (locked) id else null
        val cuts: Cuts get() = cutsOf(technique)
        val name: String get() = variant ?: technique.short
    }

    /** One proposal in a combination, at the volume it keeps (its own, or trimmed where its technique allows). */
    class Use(val proposal: Proposal, val volume: Volume) {
        val trimmed: Boolean get() = volume != proposal.volume
    }

    /** A set of proposals that do not overlap; flat layers fill the rest ([fill]). */
    class Combination(val uses: List<Use>)

    /** [p] at the volume [v] its technique's own trim gives it; refused when the technique does not allow that trim. */
    fun use(p: Proposal, v: Volume): Use {
        require(allows(p, v)) { "'${p.id}' trimmed to ${v.words}: ${p.technique.short} allows cuts ${p.cuts.words}" }
        return Use(p, v)
    }

    /** Whether [p]'s technique allows it to be trimmed to [v]: anywhere; only along its lower boundary; or not at all. */
    fun allows(p: Proposal, v: Volume): Boolean {
        if (v == p.volume) return true
        val a = p.volume as? Volume.ZBand ?: return false; val b = v as? Volume.ZBand ?: return false
        return when (p.cuts) {
            Cuts.ANYWHERE -> true
            Cuts.LOWER_EDGE -> a.toMm == b.toMm || Math.abs(a.toMm - b.toMm) < 1e-9
            Cuts.NONE_ACROSS_SPAN, Cuts.WHOLE_OBJECT, Cuts.REGION_BOUNDARY -> false
        }
    }

    /**
     * What [uses] leave of [whole] (flat layers fill it): for z bands, the gaps between the used bands, bottom to
     * top. Empty when the uses cover the whole. Volumes outside the model (support's) take nothing of it.
     */
    fun fill(whole: Volume, uses: List<Volume>): List<Volume> {
        val w = whole as Volume.ZBand
        val bands = uses.filterIsInstance<Volume.ZBand>().sortedBy { it.fromMm }
        val out = ArrayList<Volume>(); var z = w.fromMm
        for (b in bands) {
            if (b.fromMm > z + 1e-6) out += Volume.ZBand(z, b.fromMm)
            z = maxOf(z, b.toMm)
            if (z.isInfinite()) return out
        }
        if (w.toMm.isInfinite() || z < w.toMm - 1e-6) out += Volume.ZBand(z, w.toMm)
        return out
    }

    /** The handoffs between [portions] in the order they must exist, from the bed: each boundary's exit and entry, checked. */
    fun handoffs(portions: List<Portion>): List<Handoff> {
        val out = ArrayList<Handoff>()
        var exit = Surface.BED; var from = "the bed"
        val stack = portions.filter { it.volume !is Volume.Under }
        for (p in stack) {
            val c = contractOf(p.technique)
            out += Handoff(from, "${p.name} ${p.volume.words}", exit, c, compatible(exit, c.entry))
            exit = c.exit; from = "${p.name} ${p.volume.words}"
        }
        // a support column beside the stack: from what it stands on to it, then from it to the portion whose
        // overhang starts on its interface (the stack portion holding the overhang's lowest point)
        for (p in portions) {
            val u = p.volume as? Volume.Under ?: continue
            val c = contractOf(p.technique)
            val on = if (u.standsOn == "the bed") Surface.BED else Surface.MODEL
            val me = "${p.name} ${p.volume.words}" + (p.order?.let { " (${it.id})" } ?: "")
            out += Handoff(u.standsOn, me, on, c, compatible(on, c.entry))
            val above = stack.firstOrNull { Volume.lo(it.volume) <= u.region.fromMm + 1e-9 && u.region.fromMm < Volume.hi(it.volume) + 1e-9 } ?: stack.lastOrNull()
            if (above != null) {
                val ca = contractOf(above.technique)
                out += Handoff(me, "the overhang it holds up, within ${above.name} ${above.volume.words}", c.exit, ca, compatible(c.exit, ca.entry))
            }
        }
        return out
    }

    /** One boundary between portions: what the earlier one left, what the next needs, compatible or not. */
    class Handoff(val from: String, val to: String, val exit: Surface, val contract: Contract, val compatible: Boolean) {
        val status: String get() = if (compatible) "compatible" else "incompatible"
        /** The handoff's cost in the score ([handoffCost]): what a switch or a wait at this boundary adds. */
        val costValue: Double get() = handoffCost(exit, contract)
        val cost: String get() = if (!compatible) "dropped: the combination is not possible" else "%.3f: %s".format(Locale.ROOT, costValue, HANDOFF_COST_WHY)
    }

    private const val HANDOFF_COST_WHY = "every built technique keeps the object's tool and nozzle temperature at the handoff (no change, no purge, no heat-up wait), so nothing is counted; a switch's time and material will count here once a technique asks for one"

    /**
     * The cost a handoff adds to a combination's score: 0 for every built
     * technique (each keeps the object's tool and temperature, [SAME_TOOL]); infinite where [exit] is not one the next
     * technique starts on (the combination is dropped). A tool change, filament swap, or heat-up wait counts here, in the
     * score's units, once a technique declares one.
     */
    fun handoffCost(exit: Surface, next: Contract): Double = if (compatible(exit, next.entry)) 0.0 else Double.POSITIVE_INFINITY

    // ---- choosing the set: conflict groups and a dynamic program along the print order

    /**
     * One way a conflict group's plastic may be laid: [uses] (empty = none: flat layers fill it), with the group's
     * contribution to the score LINEARISED at the baseline ([cost], Scoring.linearised): the sum of its uses'.
     */
    class Option(val uses: List<Use>, val cost: Double) {
        val none: Boolean get() = uses.isEmpty()
        val label: String get() = if (none) "none (flat layers fill it)" else uses.joinToString(" + ") { it.proposal.id + if (it.trimmed) " trimmed to ${it.volume.words}" else "" }
        /** As [label], but "none" reads right for a group beside the model (nothing fills a support volume: the overhang droops). */
        fun label(beside: Boolean): String = if (none && beside) "none (no support: the overhang prints over air)" else label
    }

    /**
     * A conflict group: proposals whose volumes overlap, directly or through others, in print order. Within it
     * the choice is which of them (or none) take the plastic: [options] are every set of members that do not overlap each
     * other, and none; a locked member (a requirement) is in every option, so a group with one has no "none".
     */
    class Group(val members: List<Use>, val options: List<Option>) {
        val fromMm: Double get() = members.minOf { Volume.lo(it.volume) }
        val toMm: Double get() = members.maxOf { Volume.hi(it.volume) }
        val words: String get() = Volume.ZBand(fromMm, toMm).words + (if (members.any { it.volume is Volume.Under }) " (beside the model)" else "") + ": " + members.joinToString(", ") { it.proposal.id }
    }

    /**
     * Whether two volumes claim the same plastic: z bands of the model where they overlap in height; support volumes
     * (outside the model) only when they are the SAME region (one region's print orders are alternatives): the survey's
     * regions are disjoint face clusters by construction, and a survey region never holds a locked spot's faces, so two
     * regions whose boxes overlap are still different columns, both of which can be supported (grouping them by box made
     * one group of every region on a detailed model, and 2^n options: out of memory on the Benchy); a support
     * volume and a model volume never.
     */
    fun overlap(a: Volume, b: Volume): Boolean = when {
        a is Volume.ZBand && b is Volume.ZBand -> a.fromMm < b.toMm - 1e-9 && b.fromMm < a.toMm - 1e-9
        a is Volume.Under && b is Volume.Under -> a.region === b.region
        else -> false
    }

    /**
     * The conflict groups of [uses] (each proposal at the volume it keeps), bottom to top, with each group's options
     * costed by [cost] (one use's linearised contribution). Proposals that overlap nothing are groups of one: independent.
     */
    fun groups(uses: List<Use>, cost: (Use) -> Double): List<Group> {
        val parent = IntArray(uses.size) { it }
        fun find(i: Int): Int { var x = i; while (parent[x] != x) x = parent[x]; return x }
        for (i in uses.indices) for (j in i + 1 until uses.size) if (overlap(uses[i].volume, uses[j].volume)) parent[find(j)] = find(i)
        val byRoot = uses.indices.groupBy { find(it) }.values.map { ids -> ids.map { uses[it] }.sortedWith(compareBy({ Volume.lo(it.volume) }, { uses.indexOf(it) })) }
        return byRoot.map { members ->
            // the requirements in this group: every option holds one member of each (a locked band; a locked support spot,
            // whose two print orders share one requirement)
            val required = members.mapNotNull { it.proposal.requirement }.distinct()
            val options = ArrayList<Option>()
            if (required.isEmpty()) options += Option(emptyList(), 0.0)
            // every subset of members that do not overlap each other, smallest first (members are few: a survey's bands, or
            // one support region's print orders; a group this large would be an algorithm's fault, so it fails here rather
            // than running out of memory: 2^n options)
            val n = members.size
            check(n <= 16) { "a conflict group of $n proposals (${members.joinToString { it.proposal.id }}): too many to enumerate; proposals that claim different plastic must not be grouped" }
            for (bits in 1 until (1 shl n)) {
                val set = members.filterIndexed { i, _ -> bits and (1 shl i) != 0 }
                if (required.any { r -> set.none { it.proposal.requirement == r } }) continue
                if (set.indices.any { i -> set.indices.any { j -> j > i && overlap(set[i].volume, set[j].volume) } }) continue
                options += Option(set, set.sumOf(cost))
            }
            Group(members, options.sortedBy { it.uses.size })
        }.sortedBy { it.fromMm }
    }

    /** A set the dynamic program chose: the combination, its linearised score (the options' costs and the handoffs'), and the options' words. */
    class Chosen(val combination: Combination, val cost: Double, val options: List<String>)

    /**
     * The best [k] combinations over [groups]: a dynamic program along the print order (bottom to top),
     * whose state is the handoff, what the portions chosen so far leave (their last exit surface and top), and whose
     * cost is each option's linearised contribution plus each boundary's [handoffCost]; flat layers fill every gap
     * ([fillContract]), and a combination whose fill or portion cannot start on what lies below it is dropped. Exact ties
     * keep the order the options were made in, so the result is deterministic. The caller re-scores the result exactly.
     */
    fun chooseSets(groups: List<Group>, k: Int, whole: Volume = Volume.WHOLE, fillContract: Contract = contractOf(PrintTechnique.FLAT_LAYERS)): List<Chosen> {
        require(k >= 1) { "the dynamic program keeps at least one combination (got $k)" }
        class Partial(val uses: List<Use>, val cost: Double, val exit: Surface, val topMm: Double, val words: List<String>, val order: Int)
        val w = whole as Volume.ZBand
        var states = listOf(Partial(emptyList(), 0.0, Surface.BED, w.fromMm, emptyList(), 0))
        var made = 0
        fun keep(ps: List<Partial>): List<Partial> = ps.groupBy { it.exit to Math.round(it.topMm * 1e6) }.values
            .flatMap { same -> same.sortedWith(compareBy({ Math.round(it.cost * 1e9) }, { it.order })).take(k) }
        for (g in groups) {
            val next = ArrayList<Partial>()
            for (p in states) for (o in g.options) {
                var exit = p.exit; var top = p.topMm; var cost = p.cost + o.cost
                var ok = true
                for (u in o.uses.sortedBy { Volume.lo(it.volume) }) {
                    // a volume beside the stack (support): its cost counts, the stack's handoff state is not its
                    val v = u.volume as? Volume.ZBand ?: continue
                    if (v.fromMm > top + 1e-6) {   // a gap below it: flat layers fill it first
                        val c = handoffCost(exit, fillContract); if (c.isInfinite()) { ok = false; break }
                        cost += c; exit = fillContract.exit; top = v.fromMm
                    }
                    val c = handoffCost(exit, contractOf(u.proposal.technique)); if (c.isInfinite()) { ok = false; break }
                    cost += c; exit = contractOf(u.proposal.technique).exit; top = maxOf(top, v.toMm)
                }
                if (ok) next += Partial(p.uses + o.uses, cost, exit, top, p.words + o.label, made++)
            }
            states = keep(next)
        }
        // what is left above the last portion is flat layers' too
        val done = states.filter { p -> !(p.topMm < w.toMm - 1e-6 && handoffCost(p.exit, fillContract).isInfinite()) }
        return done.sortedWith(compareBy({ Math.round(it.cost * 1e9) }, { it.order })).take(k)
            .map { Chosen(Combination(it.uses.sortedWith(compareBy({ u -> u.volume is Volume.Under }, { u -> Volume.lo(u.volume) }))), it.cost, it.words) }
    }

    // ---- surface techniques and seams: every exposed surface region has one surface technique, every loop's seam one style

    /** What an exposed surface faces, as the volume technique hands it over: what decides which surface techniques can serve it. */
    enum class Facing(val id: String, val words: String) {
        FLAT_TOP("flat_top", "flat tops"),
        SLOPED_TOP("sloped_top", "sloped tops"),
        WALL("wall", "walls"),
        UNDERSIDE("underside", "undersides"),
    }

    /** Which walls of an outline: its outside, or its holes' (fuzzy skin's `where` and the seams are told apart by it). */
    enum class Side(val id: String, val words: String) {
        OUTSIDE("outside", "the outside of each outline"),
        HOLES("holes", "holes"),
    }

    /**
     * An exposed surface region: the surfaces facing [facing] (walls: on [side]) that the volume portion
     * [within] leaves. The only shape of surface: no geometry is measured, the coordinator
     * knows what it faces and which portion leaves it.
     */
    class SurfaceRegion(val facing: Facing, val side: Side?, val within: Portion) {
        val words: String get() = facing.words + (side?.let { ": ${it.words}" } ?: "") + ", within ${within.name} ${within.volume.words}"
        fun tree() = linkedMapOf<String, Any?>("faces" to facing.id, "side" to side?.id, "within" to linkedMapOf("technique" to within.technique.short,
            "variant" to within.variant, "volume" to within.volume.tree()))
    }

    /** The regions the volume [portions] hand over, each portion's: flat tops, sloped tops, walls (each outline's outside, holes), undersides. */
    fun handOver(portions: List<Portion>): List<SurfaceRegion> = portions.flatMap { p ->
        listOf(SurfaceRegion(Facing.FLAT_TOP, null, p), SurfaceRegion(Facing.SLOPED_TOP, null, p), SurfaceRegion(Facing.WALL, Side.OUTSIDE, p),
            SurfaceRegion(Facing.WALL, Side.HOLES, p), SurfaceRegion(Facing.UNDERSIDE, null, p))
    }

    /**
     * A surface technique offered for [region]: the job's lock locks it [on] or off, or the search chose it by weight ([lock] says
     * which, and where the lock was set or what the weights decided); [partly] is what of the portion under it its
     * contract gives it, when not all of it (the surface technique's words).
     */
    class SurfaceTechniqueOffer(val surfaceTechnique: SurfaceTechnique, val region: SurfaceRegion, val on: Boolean, val lock: String, val partly: String? = null)

    /** One region's choice: the surface techniques that can serve it ([canServe]), the one chosen ([surface technique], null = none: as laid), why, and what today's toolpaths lay on it beyond that. */
    class RegionChoice(val region: SurfaceRegion, val canServe: List<SurfaceTechniqueOffer>, val surfaceTechnique: SurfaceTechnique?, val why: String, val alsoLaid: String? = null) {
        fun with(also: String?) = RegionChoice(region, canServe, surfaceTechnique, why, also)
    }

    /**
     * The surface technique choice: each region gets exactly one surface technique, the one switched on among those offered for
     * it, or none. A surface technique offered for a region it cannot serve ([serves]: by what the region faces) is refused, and so are
     * two switched on for one region: one surface technique per surface, and a combination worth having would be a named surface technique of its
     * own. With today's surface techniques the locks cannot collide (each serves what no other does); [serves] is a parameter so the
     * rule can be seen to hold for surface techniques that would.
     */
    fun chooseSurfaceTechniques(regions: List<SurfaceRegion>, offers: List<SurfaceTechniqueOffer>,
                       serves: (SurfaceTechnique, Facing) -> Boolean = { f, x -> x in f.serves }): List<RegionChoice> {
        for (o in offers) require(serves(o.surfaceTechnique, o.region.facing)) {
            "${o.surfaceTechnique.short} cannot serve ${o.region.words}: it serves " + Facing.entries.filter { serves(o.surfaceTechnique, it) }.joinToString(" and ") { it.words }
        }
        return regions.map { r ->
            val here = offers.filter { it.region === r }
            val on = here.filter { it.on }
            require(on.size <= 1) {
                "${on.joinToString(" and ") { it.surfaceTechnique.short }} are both switched on for ${r.words}: one surface technique per surface; " +
                    "a combination would be a named surface technique of its own"
            }
            val f = on.singleOrNull()
            RegionChoice(r, here, f?.surfaceTechnique, when {
                f != null -> f.lock + (f.partly?.let { " ($it)" } ?: "")
                here.isEmpty() -> "no surface technique serves ${r.facing.words}: as laid"
                else -> "as laid: " + here.joinToString("; ") { "${it.surfaceTechnique.short}: ${it.lock}" }
            })
        }
    }

    /** Which wall loops a seam curve is on: outer wall loops (each outline's outside, or holes'), or inner ones. */
    enum class Loops(val id: String, val words: String, val outer: Boolean, val side: Side?) {
        OUTER_OUTSIDE("outer_outside", "outer wall loops on the outside of each outline", true, Side.OUTSIDE),
        OUTER_HOLES("outer_holes", "outer wall loops of holes", true, Side.HOLES),
        INNER("inner", "inner wall loops", false, null),
    }

    /** The seams of the [loops] that the volume portion [within] lays: a curve each, where a loop's ends meet. */
    class SeamCurve(val loops: Loops, val within: Portion) {
        val words: String get() = "the seams of ${loops.words}, within ${within.name} ${within.volume.words}"
        fun tree() = linkedMapOf<String, Any?>("loops" to loops.id, "within" to linkedMapOf("technique" to within.technique.short,
            "variant" to within.variant, "volume" to within.volume.tree()))
    }

    /** The seam curves the volume [portions] lay, each portion's: its outer loops' (outside, holes) and inner loops'. */
    fun seamCurves(portions: List<Portion>): List<SeamCurve> = portions.flatMap { p -> Loops.entries.map { SeamCurve(it, p) } }

    /** One seam curve's choice: the styles that can take it, and which takes which of its loops ([owners], in the style's words). */
    class SeamChoice(val curve: SeamCurve, val canTake: List<SeamStyle>, val owners: List<Pair<SeamStyle, String>>, val why: String)

    /**
     * An object's surface techniques and seams: each region's surface technique, each seam curve's style, each technique's lock (on or off, and
     * its source), and the surface techniques no chosen portion could take.
     */
    class SurfaceTechniqueChoice(val regions: List<RegionChoice>, val seams: List<SeamChoice>, val surfaceTechniqueLocks: Map<SurfaceTechnique, Pair<Boolean, String>>,
                       val styleLocks: Map<SeamStyle, Pair<Boolean, String>>, val notProposed: List<String> = emptyList()) {
        /** What became of surface technique [f]: chosen where it is the surface technique of a region, else rejected (off, or serving none). The lock's words say whether a lock locked it or the weights chose. */
        fun fate(f: SurfaceTechnique): Pair<TechniqueExplain.Fate, String> {
            val (on, lock) = surfaceTechniqueLocks.getValue(f)
            val owns = regions.filter { it.surfaceTechnique == f }
            return when {
                owns.isNotEmpty() -> TechniqueExplain.Fate.CHOSEN to "$lock; the surface technique of ${owns.size} region(s)"
                on -> TechniqueExplain.Fate.REJECTED to "on ($lock), but no chosen portion leaves a surface it can serve"
                else -> TechniqueExplain.Fate.REJECTED to "$lock; what it serves is as laid unless another surface technique serves it"
            }
        }

        /** What became of seam style [s]: chosen where it takes some loops, else rejected (off, or left nothing). */
        fun fate(s: SeamStyle): Pair<TechniqueExplain.Fate, String> {
            val (on, lock) = styleLocks.getValue(s)
            val takes = seams.count { c -> c.owners.any { it.first == s } }
            return when {
                takes > 0 -> TechniqueExplain.Fate.CHOSEN to "$lock; takes loops of $takes seam curve(s)"
                on -> TechniqueExplain.Fate.REJECTED to "on ($lock), but no loop is left to it (see the seams)"
                else -> TechniqueExplain.Fate.REJECTED to lock
            }
        }
    }
}
