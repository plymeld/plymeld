package plymeld.model

/**
 * The typed values one slice reads, resolved from the concept model for one object (and later
 * part / position). Lengths in µm (the geometry grid), speeds in mm/s, accelerations in mm/s²,
 * temperatures in °C. Every field is one catalog setting — see [Catalog] for meanings and
 * `plymeld config explain <id>` for where a value came from. Nothing here is read from a foreign
 * file; the importers put it into the model first.
 */
/** One zone of a waved first layer's second pass (plan.technique.bridge.wave.second_pass.zones): from..to as shares of the span, the lines' height over the first pass (µm), their plastic as a share of the first pass's. */
class SecondPassZone(val from: Double, val to: Double, val raiseUm: Long, val share: Double)

class SliceSettings private constructor(r: (String) -> Any, opt: (String) -> Any?, private val models: plymeld.machine.ModelLibrary,
                                        /** Every setting read through [r] or [opt] so far, with its value as text. */
                                        private val recorded: java.util.concurrent.ConcurrentHashMap<String, String>) {
    /**
     * Every setting this read so far with its value, in id order: what a per-layer result depended on (LayerCache). A
     * setting first read later (a lazy field) joins the key from then on; a result stored under a key without it was
     * computed without reading it.
     */
    fun key(): String = recorded.entries.sortedBy { it.key }.joinToString(";") { "${it.key}=${it.value}" }
    /** Reads [id] again (a result taken from a cache read it when it was computed): it counts as read by this print. */
    fun reread(ids: Collection<String>) { for (id in ids) optional(id) }
    /** [id]'s value as text, as [key] records it (null when unset), read through these settings (so it counts as read). */
    fun textOf(id: String): String = optional(id).toString()
    private val optional = opt

    /**
     * A field read from the settings: computed on first use, as `lazy` would, and on every use the settings its computation
     * read join the enclosing [reading], so a cached layer result lists them however often the field was used before it.
     */
    class Setting<T>(private val init: () -> T) {
        @Volatile private var done = false
        private var value: Any? = null
        private var ids: Set<String> = emptySet()
        /** Whether the field was read (a check that every field is read, [readAll]). */
        val isRead: Boolean get() = done
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T {
            if (!done) synchronized(this) { if (!done) { val (v, r) = reading(init); value = v; ids = r; done = true } }
            capture.get()?.addAll(ids)
            @Suppress("UNCHECKED_CAST") return value as T
        }
    }
    private fun <T> setting(init: () -> T) = Setting(init)

    private val num = { id: String -> (r(id) as Number).toDouble() }
    private val um = { id: String -> Math.round(num(id) * 1000) }
    private val int = { id: String -> num(id).toInt() }
    private val bool = { id: String -> r(id) as Boolean }
    private val text = { id: String -> r(id) as String }

    // layers and slicing
    val layerHeightUm by setting { um("plan.technique.flat.layer.height") }
    val firstLayerHeightUm by setting { um("plan.mechanism.adhesion.first_layer.height") }
    val resolutionUm by setting { um("method.slicing.tolerance") }
    val closingRadiusUm by setting { um("method.slicing.close_gaps") }
    // walls
    val wallLoops by setting { int("plan.layer.walls.count") }
    val wallGenerator by setting { text("plan.layer.walls.style") }
    /** Arachne thresholds: read only with plan.layer.walls.style = arachne (a config without them says so then). */
    val minBeadUm by setting { Math.round(num("plan.layer.walls.line.width.min") * 1000) }
    val minFeatureUm by setting { Math.round(num("plan.layer.walls.feature.min") * 1000) }
    val minLengthFactor by setting { num("plan.layer.walls.length_factor.min") }
    val transitionLengthUm by setting { Math.round(num("plan.layer.walls.transition_length") * 1000) }
    val outerWallWidthUm by setting { um("plan.layer.walls.outer.line.width") }
    val innerWallWidthUm by setting { um("plan.layer.walls.inner.line.width") }
    val firstLayerWidthUm by setting { um("plan.mechanism.adhesion.first_layer.line.width") }
    val elephantFootUm by setting { um("hardware.plate.first_layer.squish") }
    val innerWallsFirst by setting { bool("plan.layer.walls.inner_first") }
    /**
     * Where wall loops start (plan.seam_start, from plan.seam or decided): "corners", at corners in line with the layer below (every value but
     * "back", so another slicer's aligned / nearest / random read as corners, the seams kept aligned), or at the
     * back. Null = not decided yet (plan.seam unset and the seam decision not run: `plymeld plan` decides it); the stages
     * that place seams refuse then, the ones before them (walls) do not need it.
     */
    val seamStart: String? by setting { opt("plan.seam_start") as String? }
    private val seamStartSet: String get() = seamStart ?: error("plan.seam_start is not set: the seam placement was not decided (plymeld plan decides it when plan.seam is unset) nor locked (plan.seam)")
    val seamCorners: Boolean get() = seamStartSet == "corners"
    val seamBack: Boolean get() = seamStartSet == "back"
    val seamLeastVisible: Boolean get() = seamStartSet == "least_visible"
    /** The visibility map's weights and the view (method.quality.visibility.*, plan.view_from), for a least-visible seam placed when sliced. */
    val visibilityUnderside by setting { num("method.quality.visibility.under") }
    val visibilityBed by setting { num("method.quality.visibility.bed") }
    val visibilityAway by setting { num("method.quality.visibility.away") }
    /** A seam's viewing directions by the quadrant they look from: front, left, right, back (method.quality.visibility.quadrant.*). */
    val visibilityQuadrants by setting { listOf("front", "left", "right", "back").map { num("method.quality.visibility.quadrant.$it") }.toDoubleArray() }
    /** seam_scar's corner credit, which the least-visible placement counts as the measure does (Seams.place). */
    val seamCornerShare by setting { num("method.quality.seam.corner_share") }
    /** seam_scar's scarf share, which the per-loop scarf rule weighs as the measure does (SeamScar.scarfPays). */
    val seamScarfShare by setting { num("method.quality.seam.scarf_share") }
    val viewFrom by setting { text("plan.view_from") }
    /** How a seam's point is sampled for what hides it nearby (method.quality.visibility.sample.*; plymeld.core.decide.Exposure). */
    val visibilityAzimuths by setting { int("method.quality.visibility.sample.azimuths") }
    val visibilityElevations by setting { int("method.quality.visibility.sample.elevations") }
    val visibilityMaxElevationDeg by setting { num("method.quality.visibility.sample.elevation.max") }
    val visibilityReachUm by setting { um("method.quality.visibility.sample.reach") }
    val visibilityCellUm by setting { um("method.quality.visibility.sample.cell") }
    val visibilityShowThroughUm by setting { um("method.quality.visibility.show_through") }
    /** The smallest turn (degrees) that counts as a corner for the seam; unset (an imported config): no corner seam. */
    val seamCornerDeg: Double? by setting { (opt("plan.technique.corner_seam.corner_angle") as Number?)?.toDouble() }
    /** Scarf seams on outer walls (plan.technique.scarf_seam.where): "none", "smooth" (loops with no corner), "all"; unset: none. */
    val scarf: String by setting { opt("plan.technique.scarf_seam.where") as String? ?: "none" }
    val scarfLengthUm: Long? by setting { (opt("plan.technique.scarf_seam.length") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** The scarf's starting thickness, a share of the layer (0..1). */
    val scarfStart: Double? by setting { (opt("plan.technique.scarf_seam.start") as Number?)?.toDouble() }
    // shells and infill
    /** Top / bottom shell layers at the layer height: as many as the shell's thickness takes (layers of varying height count it layer by layer: Planner.shellCounts). */
    val topLayers by setting { Math.ceil(um("plan.layer.top.thickness") / layerHeightUm.toDouble() - 1e-9).toInt() }
    val bottomLayers by setting { Math.ceil(um("plan.layer.bottom.thickness") / layerHeightUm.toDouble() - 1e-9).toInt() }
    val topShellThicknessUm by setting { um("plan.layer.top.thickness") }
    val bottomShellThicknessUm by setting { um("plan.layer.bottom.thickness") }
    /** The hidden core in thicker layers (layer heights per region in their simplest case): sparse infill laid every this many layers, that many thick; 1 = off. */
    val coreLayers: Int by setting { ((opt("plan.technique.flat.core.layers") as Number?)?.toInt() ?: 1).coerceAtLeast(1) }
    /** The tool's thickest layer (hardware.tool.<key>.layer.height.max), the core's bead at most; null = not set. */
    val maxLayerHeightUm: Long? by setting { (opt("hardware.tool.layer.height.max") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** Where the purge after a tool change goes (plan.mechanism.purge.into): "tower", "infill", "infill_and_object"; unset = tower. */
    val purgeInto: String by setting { opt("plan.mechanism.purge.into") as String? ?: "tower" }
    val sparseDensity by setting { num("plan.layer.infill.density") }
    val sparsePattern by setting { text("plan.layer.infill.pattern") }
    /** How far a curved interior line (the gyroid's) may stray from the traced curve between two vertices (µm); null (a setup without it, e.g. an imported one) = laid as traced. */
    val sparseChordUm: Long? by setting { (opt("plan.layer.infill.chord_tolerance") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val topPattern by setting { text("plan.layer.top.pattern") }
    val bottomPattern by setting { text("plan.layer.bottom.pattern") }
    val sparseWidthUm by setting { um("plan.layer.infill.line.width") }
    val minSparseAreaMm2 by setting { num("plan.layer.infill.area.min") }
    val solidWidthUm by setting { um("plan.layer.solid.line.width") }
    val topWidthUm by setting { um("plan.layer.top.line.width") }
    val infillAngleDeg by setting { num("plan.layer.infill.angle") }
    val infillWallOverlap by setting { num("plan.layer.infill.wall_overlap") }
    /** Another slicer's anchor limit (investigation); null = plymeld's own rule, a few line spacings (Planner). */
    val anchorMaxUm: Long? by setting { (opt("plan.layer.infill.anchor_length.max") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val bridgeSpeed by setting { num("plan.mechanism.motion.bridge.speed") }
    /**
     * Purge tower speed: as fast as the filament melts — the machine's travel
     * speed, which the writer's volumetric cap brings down to the filament's max flow for the bead —
     * but never faster than the machine prints (hardware.machine.motion.print.speed; the Creator 5 travels at 600 mm/s
     * and prints at 300, and a thin tower layer's melt cap still allowed 467: the g-code check).
     */
    val towerSpeed: Double get() = Math.min(travelSpeed, hiddenSpeed)
    val bridgeFlow by setting { num("plan.layer.bridge.flow") }
    val nozzleDiameterUm by setting { um("hardware.tool.bore") }
    val brimType by setting { text("plan.mechanism.adhesion.brim.where") }
    val brimWidthUm by setting { um("plan.mechanism.adhesion.brim.width") }
    val brimGapUm by setting { um("plan.mechanism.adhesion.brim.gap") }
    /** The prime line: "near", "tab" or "none"; null = by the plate (PrimeLine). */
    val primeLine: String? by setting { opt("plan.mechanism.adhesion.prime.line") as String? }
    val primeLengthUm: Long by setting { (opt("plan.mechanism.adhesion.prime.length") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    val primeGapUm: Long by setting { (opt("plan.mechanism.adhesion.prime.gap") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    /**
     * The loaded head's shape for clearance above printed plastic (null where the config does not give it): the nozzle's cone
     * (hardware.tool.head.nozzle.angle, degrees from horizontal), how far the tip sits below the rest of the head (hardware.tool.head.nozzle.height, µm), and
     * the head's reach from the nozzle towards -X, +X, -Y, +Y (µm, from hardware.tool.head.size.x/y and nozzle_x/y).
     */
    val nozzleAngleDeg: Double? by setting { (opt("hardware.tool.head.nozzle.angle") as Number?)?.toDouble() }
    val nozzleHeightUm: Long? by setting { (opt("hardware.tool.head.nozzle.height") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val headReachUm: LongArray? by setting {
        listOf("hardware.tool.head.size.x", "hardware.tool.head.size.y", "hardware.tool.head.nozzle.x", "hardware.tool.head.nozzle.y").map { (opt(it) as Number?)?.toDouble() }
        .takeIf { v -> v.all { it != null } }?.let { (sx, sy, nx, ny) -> doubleArrayOf(nx!!, sx!! - nx, ny!!, sy!! - ny).map { Math.round(it * 1000) }.toLongArray() }
    }
    /** The nozzle temperature on an arched lifted bridge's own layers (°C; null = the print temperature: those layers only). */
    val bridgeTempC: Int? by setting { (opt("plan.technique.bridge.temperature") as Number?)?.let { Math.round(it.toDouble()).toInt() } }
    /**
     * A lifted bridge's own speeds (mm/s) in its layers (null = the usual): [bridgePrintSpeed] the first layer's segments over
     * the hollow, the layers after ramping up from it (bridge.ramp_speeds); [bridgeMoveSpeed] every move that extrudes nothing.
     */
    val bridgePrintSpeed: Double? by setting { (opt("plan.technique.bridge.print.speed") as Number?)?.toDouble() }
    val bridgeMoveSpeed: Double? by setting { (opt("plan.technique.bridge.travel.speed") as Number?)?.toDouble() }
    /** An arched bridge's speeds over air in its layers after its first, as listed (mm/s). */
    val bridgeRampListed: List<Double> by setting { rampSpeeds(text("plan.technique.bridge.ramp_speeds")) }
    /** After the listed speeds, each layer's speed over air this many times the last (<= 1: none). */
    val bridgeRampGrowth: Double by setting { (opt("plan.technique.bridge.ramp_growth") as Number?)?.toDouble() ?: 1.0 }

    /**
     * The speeds over air of the layers after a slow first one laid at [from] (mm/s): the listed ones, then each the last times
     * the growth, every one capped at the bridge speed, ending with the first that reaches it; the usual speeds after.
     */
    fun bridgeRamp(from: Double): List<Double> {
        val out = ArrayList<Double>()
        for (v in bridgeRampListed) { out += minOf(v, bridgeSpeed); if (v >= bridgeSpeed) return out }
        if (bridgeRampGrowth > 1) {
            var v = out.lastOrNull() ?: from
            while (v < bridgeSpeed) { v = minOf(v * bridgeRampGrowth, bridgeSpeed); out += v }
        }
        return out
    }
    /** A lifted bridge's own layer height (µm; null = the object's): several lifted bridges in one object only. */
    val bridgeLayerHeightUm: Long? by setting { (opt("plan.technique.bridge.layer.height") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** An arched lifted bridge's top layer at the usual speeds and the print temperature ("normal"); unset = as its other layers. */
    val bridgeTopNormal: Boolean by setting { opt("plan.technique.bridge.top_layer") == "normal" }
    /** Objects printed together: their layers below each lifted bridge first, then the bridges by bridge temperature, hottest first (Job); unset = merged by height. */
    val bridgeOrderHotToCold: Boolean by setting { opt("plan.bridge_order") == "hot_to_cold" }
    /** Objects printed together: their layers below each lifted bridge first, then each bridge whole, from +X to -X (Job). */
    val bridgeOrderOneAtATime: Boolean by setting { opt("plan.bridge_order") == "one_at_a_time" }
    /** A lifted bridge's first layer as wave beads from every support at once (Planner); unset = bridge lines. */
    val bridgeFirstLayerWave: Boolean by setting { opt("plan.technique.bridge.first_layer") == "wave_overhang" }
    val bridgeWaveAlternate: Boolean by setting { opt("plan.technique.bridge.wave.sides") == "alternate" }
    val bridgeWaveOneSupport: Boolean by setting { opt("plan.technique.bridge.wave.sides") == "one_support" }
    val bridgeWaveEdgesLast: Boolean by setting { opt("plan.technique.bridge.wave.edges.order") == "last" }
    val bridgeWaveEdgesNone: Boolean by setting { opt("plan.technique.bridge.wave.edges.enabled") == false }
    val bridgeWaveLayers: Int by setting { if (opt("plan.technique.bridge.wave.layers") == "2") 2 else 1 }
    /** With wave.layers = "2": the second layer only an X over each section of the first, once laid (plan.technique.bridge.wave.second_layer = "x"). */
    val bridgeWaveSecondX: Boolean by setting { opt("plan.technique.bridge.wave.second_layer") == "x" }
    /** The region a waved first layer lays first (plan.technique.bridge.wave.start; WaveStart): whole (unset), edge_strips, triangles, opposite_triangles. */
    val bridgeWaveStart: String by setting { (opt("plan.technique.bridge.wave.start") as String?) ?: "whole" }
    /** Each edge strip's width (plan.technique.bridge.wave.strip.width); null = not set. */
    val bridgeWaveStartWidthUm: Long? by setting { (opt("plan.technique.bridge.wave.strip.width") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** A waved first layer's fronts as chevrons (plan.technique.bridge.wave.front = "chevron"; WaveStart.chevronFronts / chevronRows). */
    val bridgeWaveChevron: Boolean by setting { opt("plan.technique.bridge.wave.front") == "chevron" }
    /** How far the chevron's leading part leads the rest (plan.technique.bridge.wave.chevron.lead); null = not set. */
    val bridgeWaveChevronLeadUm: Long? by setting { (opt("plan.technique.bridge.wave.chevron.lead") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** A curved band's top laid uphill from each side to its highest line (plan.technique.curved.top_order = "uphill"). */
    val curvedTopUphill: Boolean by setting { opt("plan.technique.curved.top_order") == "uphill" }
    /** The chevron's middle leads, not its edges (plan.technique.bridge.wave.chevron.leading = "middle"). */
    val bridgeWaveChevronMiddle: Boolean by setting { opt("plan.technique.bridge.wave.chevron.leading") == "middle" }
    /** The chevron's lines run across, parallel to the support's edge, not along the fronts (plan.technique.bridge.wave.chevron.lines = "across"). */
    val bridgeWaveChevronAcross: Boolean by setting { opt("plan.technique.bridge.wave.chevron.lines") == "across" }
    /** A waved first layer's lines after what that layer lays on the supports (plan.technique.bridge.wave.after_supported); unset = no. */
    val bridgeWaveAfterSupported: Boolean by setting { opt("plan.technique.bridge.wave.after_supported") == true }
    /** Each X's section along the span (plan.technique.bridge.wave.x_length); null = not set. */
    val bridgeWaveXLengthUm: Long? by setting { (opt("plan.technique.bridge.wave.x_length") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val bridgeWaveDirection: String by setting { (opt("plan.technique.bridge.wave.line.direction") as String?) ?: "mirrored" }
    val bridgeWaveCrossBeside: Boolean by setting { opt("plan.technique.bridge.wave.crossing") == "beside" }
    val bridgeWaveCrossClearanceUm: Long by setting { (opt("plan.technique.bridge.wave.crossing_clearance") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    val bridgeWaveRetractMm: Double? by setting { (opt("plan.technique.bridge.wave.retract") as Number?)?.toDouble() }
    val bridgeWaveWipe: Boolean? by setting { opt("plan.technique.bridge.wave.wipe") as Boolean? }
    val bridgeWaveWaitS: Double by setting { (opt("plan.technique.bridge.wave.wait") as Number?)?.toDouble() ?: 0.0 }
    /** After each bead across the span, the head round the object at a distance (plan.technique.bridge.wave.crossing = "away"). */
    val bridgeWaveCrossAway: Boolean by setting { opt("plan.technique.bridge.wave.crossing") == "away" }
    /** How far from the object the head goes with wave.crossing = away (plan.technique.bridge.wave.away.distance); null = not set. */
    val bridgeWaveAwayUm: Long? by setting { (opt("plan.technique.bridge.wave.away.distance") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /**
     * The waits after the beads across the span by zone along it (plan.technique.bridge.wave.wait_zones): each zone's start
     * and end as shares of the span from the first support (0..1) and its wait (s); empty = not set.
     */
    val bridgeWaveWaitZones: List<Triple<Double, Double, Double>> by setting { (opt("plan.technique.bridge.wave.wait_zones") as String?)?.let(::parseWaitZones) ?: emptyList() }
    /**
     * A waved first layer's second pass by zone (plan.technique.bridge.wave.second_pass.zones): each zone's start and end as
     * shares of the span (0..1), its lines' height over the first pass (µm), and plastic share (0..); empty = none.
     */
    val bridgeWaveSecondPass: List<SecondPassZone> by setting { (opt("plan.technique.bridge.wave.second_pass.zones") as String?)?.let(::parseSecondPass) ?: emptyList() }
    /** The second pass's speed (mm/s) and how far above each line's start it comes down from (µm); null = not set. */
    val bridgeWaveSecondPassSpeed: Double? by setting { (opt("plan.technique.bridge.wave.second_pass.speed") as Number?)?.toDouble() }
    val bridgeWaveSecondPassApproachUm: Long? by setting { (opt("plan.technique.bridge.wave.second_pass.approach") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** Xs over a waved first layer, laid after it (plan.technique.bridge.wave.x_layer.*): on/off, height over the layer (µm), plastic share, inset from the span's edges (µm), speed (mm/s); null = not set. */
    val bridgeWaveXLayer: Boolean by setting { opt("plan.technique.bridge.wave.x_layer.enabled") == true }
    val bridgeWaveXLayerRaiseUm: Long? by setting { (opt("plan.technique.bridge.wave.x_layer.raise") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val bridgeWaveXLayerShare: Double? by setting { (opt("plan.technique.bridge.wave.x_layer.share") as Number?)?.toDouble() }
    val bridgeWaveXLayerInsetUm: Long? by setting { (opt("plan.technique.bridge.wave.x_layer.inset") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val bridgeWaveXLayerSpeed: Double? by setting { (opt("plan.technique.bridge.wave.x_layer.speed") as Number?)?.toDouble() }
    /** The X over the middle laid first (plan.technique.bridge.wave.x_layer.middle = "first"); unset = last. */
    val bridgeWaveXLayerMiddleFirst: Boolean by setting { opt("plan.technique.bridge.wave.x_layer.middle") == "first" }
    /** A layer of lines along the span over a waved first layer, laid after it (plan.technique.bridge.wave.span_layer.*): on/off, height over the layer (µm), speed (mm/s); null = not set. */
    val bridgeWaveSpanLayer: Boolean by setting { opt("plan.technique.bridge.wave.span_layer.enabled") == true }
    val bridgeWaveSpanLayerRaiseUm: Long? by setting { (opt("plan.technique.bridge.wave.span_layer.raise") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val bridgeWaveSpanLayerSpeed: Double? by setting { (opt("plan.technique.bridge.wave.span_layer.speed") as Number?)?.toDouble() }
    /** A waved first layer's lines evenly spaced along the bridge's surface, a middle line last (plan.technique.bridge.wave.spacing = "even"). */
    val bridgeWaveEvenSpacing: Boolean by setting { opt("plan.technique.bridge.wave.spacing") == "even" }
    /** A lifted bridge's fill along and across its span, its top along it (plan.technique.bridge.fill_angles = "span", unset). */
    val bridgeFillSpan: Boolean by setting { opt("plan.technique.bridge.fill_angles") != "object" }
    /** A lifted bridge's fill all along its bridge (Planner.paths); unset = no. */
    val bridgeFillAlong: Boolean by setting { opt("plan.technique.bridge.fill_along") as Boolean? ?: false }
    /** Tacking a lifted bridge's layers to its first (IncrementalSlicer.tacks); 0 layers or unset = none. */
    val tackLayers: Int by setting { (opt("plan.technique.bridge.tack.layers") as Number?)?.toInt() ?: 0 }
    val tackLengthUm: Long by setting { (opt("plan.technique.bridge.tack.length") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    val tackRiseUm: Long by setting { (opt("plan.technique.bridge.tack.rise") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    val tackSpacingUm: Long by setting { (opt("plan.technique.bridge.tack.spacing") as Number?)?.let { Math.round(it.toDouble() * 1000) } ?: 0L }
    /** The printable bed (µm); null where the config does not give it. */
    val bedUm: LongArray? by setting {
        listOf("hardware.machine.build.left", "hardware.machine.build.front", "hardware.machine.build.right", "hardware.machine.build.back")
        .map { (opt(it) as Number?)?.let { v -> Math.round(v.toDouble() * 1000) } }.takeIf { b -> b.all { it != null } }?.map { it!! }?.toLongArray()
    }
    // support
    /** A top surface runs under the other parts standing on it (Regions.classify). */
    val topUnderParts by setting { bool("plan.layer.top.under_parts") }
    /** Z anti-aliasing of sloped tops in flat layers (ZAntiAlias). */
    val zAntialias by setting { bool("plan.faces.sloped_top.z_antialiasing") }
    /** Wave overhangs on the undersides over air (plan.faces.under.technique = "wave_overhang"), and the technique's own values. */
    val waveOverhang by setting { opt("plan.faces.under.wave_overhang") as Boolean? ?: false }
    /** The heights (from the object's bottom, µm) the underside surface technique is limited to (plan.faces.under.from / .to); null = no limit. */
    val undersideFromUm: Long? by setting { (opt("plan.faces.under.from") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val undersideToUm: Long? by setting { (opt("plan.faces.under.to") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val waveSpacingUm by setting { um("plan.technique.wave_overhang.spacing") }
    val waveOverlapUm by setting { um("plan.technique.wave_overhang.overlap") }
    val waveMinNeckUm by setting { um("plan.technique.wave_overhang.neck.min") }
    val waveMinAreaMm2 by setting { num("plan.technique.wave_overhang.area.min") }
    /** The plastic per mm of a wave bead, as the bead's section (mm²): laid in the air, nothing sets it but the flow. */
    val waveLineAreaMm2 by setting { num("plan.technique.wave_overhang.line.area") }
    val waveSpeed by setting { num("plan.technique.wave_overhang.speed") }
    /** Sparse pieces narrower than this many grid cells are narrow; they get one loop, or [narrowFill] solid (Planner). */
    val narrowCells by setting { num("plan.layer.infill.narrow.cells") }
    val narrowFill by setting { text("plan.layer.infill.narrow.fill") }
    /** The support switch as a lock: true = under every overhang, false = never, null (unset) = the plan's support spots, decided per overhang. */
    val enableSupport: Boolean? by setting { opt("plan.support.enabled") as Boolean? }
    /** The object's filament (its own spool, like every filament value here): how far its outer wall line may hang past the one below. */
    val overhang by setting { Overhang(num("hardware.filament.overhang.share"), num("plan.layer.walls.outer.line.width")) }
    /** The conditions [overhang] holds at: the part fan while a line hangs past the one below (%), and the fastest such a line prints (mm/s; null = not known: only the graded slow-down). */
    val overhangFanPct by setting { num("hardware.filament.overhang.fan") }
    val overhangSpeed: Double? by setting { (opt("hardware.filament.overhang.speed") as Number?)?.toDouble() }
    /** Another slicer's gaps (investigation); null = plymeld's rules (Planner): one support line to the side, one layer on top. */
    val supportXyUm: Long? by setting { (opt("plan.support.gap.side") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val supportTopZUm: Long? by setting { (opt("plan.support.gap.top.value") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** The least gap on top of support when gap.top.value is unset (Planner: one layer, at least this). */
    val supportTopZMinUm by setting { um("plan.support.gap.top.min") }
    /** How far support standing on the model stays above it (Supports.clearAboveModel); null = one layer, 0 = laid onto it. */
    val supportBottomZUm: Long? by setting { (opt("plan.support.gap.bottom") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** The narrowest overhang supported, stacked over its own width of height (Supports.dropSpecks); null = plymeld's rule (Planner): a tree tip, two support lines. */
    val supportMinWidthUm: Long? by setting { (opt("plan.support.column.width") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val supportInterfaceTop by setting { int("plan.support.interface.top_layers") }
    val supportInterfaceBottom by setting { int("plan.support.interface.bottom_layers") }
    val supportBaseSpacingUm by setting { um("plan.support.spacing") }
    /** Another slicer's gap between interface lines (investigation); null = plymeld's rule: lines at bead contact (Planner). */
    val supportInterfaceSpacingUm: Long? by setting { (opt("plan.support.interface.spacing") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val supportOnPlateOnly by setting { bool("plan.support.from_bed_only") }
    val supportWidthUm by setting { um("plan.support.line.width") }
    val supportAngleDeg by setting { num("plan.support.angle") }
    // tree support: optional on import (QIDIStudio's configs have no tree_support_tip_diameter), so
    // read only when tree support is used, and a missing one says how to supply it
    private val given = { id: String -> (r(id) as? Number)?.toDouble() ?: error("setting '$id' has no value (the imported config does not carry it): give one with --set $id=<value>") }
    /** Another slicer's lean and tip (investigation); null = plymeld's rules (Planner): the overhang step per layer, two support lines across. */
    val treeBranchAngleDeg: Double? by setting { (opt("plan.support.tree.branch_angle") as Number?)?.toDouble() }
    val treeTipDiameterUm: Long? by setting { (opt("plan.support.tree.tip_diameter") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val treeBranchDiameterUm by setting { Math.round(given("plan.support.tree.branch_diameter") * 1000) }
    val treeDiameterAngleDeg by setting { given("plan.support.tree.widening") }
    val treeBranchDistanceUm by setting { Math.round(given("plan.support.tree.tip_spacing") * 1000) }
    // fuzzy skin, ironing
    val fuzzySkin by setting { text("plan.technique.fuzzy_skin.where") }
    val fuzzyThicknessUm by setting { um("plan.technique.fuzzy_skin.thickness") }
    val fuzzyPointDistUm by setting { um("plan.technique.fuzzy_skin.point_distance") }
    val fuzzyFirstLayer by setting { bool("plan.technique.fuzzy_skin.first_layer") }
    val fuzzyMode by setting { text("plan.technique.fuzzy_skin.mode") }
    val fuzzyNoise by setting { text("plan.technique.fuzzy_skin.noise") }
    val ironingType by setting { text("plan.technique.ironing.type") }
    val ironingSpacingUm by setting { um("plan.technique.ironing.spacing") }
    val ironingFlow by setting { num("plan.technique.ironing.flow") }
    val ironingSpeed by setting { num("plan.technique.ironing.speed") }
    val ironingInsetUm by setting { um("plan.technique.ironing.inset") }
    val ironingAngleDeg by setting { num("plan.technique.ironing.angle") }
    // material, material x tool
    /** The filament's maximum flow as measured (or its placeholder): `melt.flow.max`, before the buffer. */
    val maxVolumetricMeasured by setting { num("hardware.filament.melt.flow.max") }
    /** The share of the maximum flow kept unused: `plan.mechanism.motion.melt.buffer`. */
    val meltBuffer by setting { num("plan.mechanism.motion.melt.buffer") }
    /** The flow every move is capped to: the maximum flow less the buffer. */
    val maxVolumetric by setting { maxVolumetricMeasured * (1 - meltBuffer) }
    val slowDownForCooling by setting { bool("hardware.filament.cooling.slowdown.enabled") }
    val slowDownLayerTimeS by setting { num("hardware.filament.cooling.layer.time.min") }
    /** How the slow-down for the minimum layer time is shared (KlipperWriter): "hidden_first" or "even". */
    val coolingSlowdown by setting { text("plan.mechanism.cooling.slowdown.style") }
    /** A layer still short of its minimum layer time at the minimum speed waits the rest (KlipperWriter's cooling dwell). */
    val coolingDwell by setting { bool("plan.mechanism.cooling.dwell.enabled") }
    /** How it waits: "lift" (straight up, [coolingDwellLiftUm]) or "park" (beside the layer). */
    val coolingDwellStyle by setting { text("plan.mechanism.cooling.dwell.style") }
    val coolingDwellLiftUm by setting { um("plan.mechanism.cooling.dwell.lift") }
    val slowDownMinSpeed by setting { num("hardware.filament.cooling.speed.min") }
    val fanOffFirstLayers by setting { int("hardware.filament.cooling.fan_off_layers") }
    val fanMinPct by setting { num("hardware.filament.cooling.fan") }
    val filamentDiameterMm by setting { num("hardware.filament.diameter") }
    val flowRatio by setting { num("hardware.filament.flow") }
    val nozzleTempC by setting { int("hardware.filament.temperature.print") }
    val nozzleTempFirstC by setting { int("hardware.filament.temperature.first_layer") }
    val bedTempC by setting { int("hardware.filament.bed.temperature.print") }
    val bedTempFirstC by setting { int("hardware.filament.bed.temperature.first_layer") }
    // motion
    /** Visible lines (outer walls, top and bottom surfaces): their own cap. */
    val visibleSpeed by setting { num("plan.mechanism.motion.visible.speed") }
    /** Hidden lines (inner walls, infill, solid, support): the machine's fastest move; the melt rate caps them in the writer. */
    val hiddenSpeed by setting { num("hardware.machine.motion.print.speed") }
    val firstLayerSpeed by setting { num("plan.mechanism.motion.first_layer.speed") }
    val travelSpeed by setting { num("hardware.machine.motion.travel.speed") }
    val defaultAccel by setting { num("hardware.machine.motion.print.acceleration") }
    val visibleAccel by setting { num("plan.mechanism.motion.visible.acceleration") }
    val travelAccel by setting { num("hardware.machine.motion.travel.acceleration") }
    /** The machine's own Z and corner limits, for time predictions; null = its model's (or none). */
    val zSpeedMax: Double? by setting { (opt("hardware.machine.motion.z.speed.max") as Number?)?.toDouble() }
    val zAccelMax: Double? by setting { (opt("hardware.machine.motion.z.acceleration.max") as Number?)?.toDouble() }
    val cornerSpeed: Double? by setting { (opt("hardware.machine.motion.corner.speed") as Number?)?.toDouble() }
    /** The machine's Z step (mm; null = layers as planned): flat layers' tops held to its multiples. */
    val zStepMm: Double? by setting { (opt("hardware.machine.motion.z.step") as Number?)?.toDouble()?.takeIf { it > 0 } }
    // retraction
    val retractMm by setting { num("hardware.filament.retract.length") }
    /** How far a line prints starved after a retraction (hardware.filament.retract.restart_lag, mm): the restart measure's length per visible restart. */
    val restartLagMm by setting { num("hardware.filament.retract.restart_lag") }
    val retractSpeed by setting { num("hardware.filament.retract.speed") }
    val deretractSpeed by setting { num("hardware.filament.retract.return.speed") }
    val retractMinTravelMm by setting { num("hardware.filament.retract.travel.min") }
    /** No retraction or lift for a travel that stays inside its island and crosses no wall (plan.mechanism.motion.no_retract_in_island; Planner). */
    /**
     * plan.mechanism.motion.no_retract_in_island: true / false as set; null = unset, DECIDED per object when sliced
     * (RetractionDecision: the planner marks the hidden travels as if true, the decision keeps or clears the marks).
     */
    val noRetractInIsland: Boolean? by setting { opt("plan.mechanism.motion.no_retract_in_island") as Boolean? }
    val avoidCrossing by setting { bool("plan.mechanism.motion.avoid_crossing") }
    /** The detour's margin outside an island's wall (µm), and its cap: this many times the straight travel plus this much (µm); unset = the router's placeholders. */
    val detourMarginUm: Long? by setting { (opt("plan.mechanism.motion.detour.margin") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val detourMaxRatio: Double? by setting { (opt("plan.mechanism.motion.detour.ratio.max") as Number?)?.toDouble() }
    val detourMaxExtraUm: Long? by setting { (opt("plan.mechanism.motion.detour.extra.max") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** A route along the wall to or from a speck: its own caps (plan.mechanism.motion.detour.speck.ratio.max / speck.extra.max). */
    val detourSpeckMaxRatio: Double? by setting { (opt("plan.mechanism.motion.detour.speck.ratio.max") as Number?)?.toDouble() }
    val detourSpeckMaxExtraUm: Long? by setting { (opt("plan.mechanism.motion.detour.speck.extra.max") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    /** How far inside an island's outer wall a travel within it runs (combing); null = the layer's widest outer wall. */
    val detourInsetUm: Long? by setting { (opt("plan.mechanism.motion.detour.inset") as Number?)?.let { Math.round(it.toDouble() * 1000) } }
    val zHopUm by setting { um("hardware.filament.retract.z_hop") }
    val wipe by setting { bool("hardware.filament.retract.wipe.enabled") }
    val wipeDistanceMm by setting { num("hardware.filament.retract.wipe.distance") }
    val wipeSpeed by setting { num("hardware.filament.retract.wipe.speed") }
    val retractBeforeWipe by setting { num("hardware.filament.retract.wipe.retract_before") }
    // machine
    val gcodeFlavor by setting { text("hardware.machine.firmware.flavor") }
    val printerModel by setting { text("hardware.machine.model") }
    /** The machine's printer model: what it accepts and is (hardware.machine.<key>.model, a hardware.model entry). */
    val machineDescription: plymeld.machine.MachineDescription by setting { models.forModel(printerModel) }
    /** The g-code dialect its files are written in: the machine's own, else its model's. */
    val dialect: plymeld.machine.Dialect by setting { plymeld.machine.Dialect.forMachine(machineDescription, gcodeDialect) }
    /** The machine's own g-code dialect, or null for its model's default (plymeld.machine.Dialect.forMachine). */
    val gcodeDialect: String? by setting { text("hardware.machine.firmware.dialect").takeIf { it.isNotBlank() } }
    val maxPrintHeightMm by setting { num("hardware.machine.build.z") }
    val excludeObject by setting { bool("hardware.machine.firmware.exclude_object") }
    val arcFitting by setting { bool("hardware.machine.firmware.arcs") }
    val startGcode by setting { text("hardware.machine.gcode.start") }
    val endGcode by setting { text("hardware.machine.gcode.end") }
    val layerChangeGcode by setting { text("hardware.machine.gcode.layer_change") }
    /** The machine's pause command, or null when it has none. */
    val pauseGcode: String? by setting { text("hardware.machine.gcode.pause").takeIf { it.isNotBlank() } }
    /** The machine's bed-levelling g-code, or null when plymeld knows none for it. */
    val levelGcode: String? by setting { text("hardware.machine.gcode.level").takeIf { it.isNotBlank() } }
    /** The preview pictures the machine's screen reads from the file (hardware.machine.gcode.pictures), as (width, height) in pixels; empty = none. */
    val pictures: List<Pair<Int, Int>> by setting { pictureSizes(text("hardware.machine.gcode.pictures")) }

    /**
     * Reads every setting (each field reads its setting the first time it is asked for, so what a print reads is what it
     * uses): for the check that names every missing value at once. SliceSettingsTest holds it to every lazy field.
     */
    fun readAll() {
        for (f in listOf<() -> Any?>({ layerHeightUm }, { firstLayerHeightUm }, { resolutionUm }, { closingRadiusUm }, { wallLoops }, { wallGenerator }, { minBeadUm }, { minFeatureUm }, { minLengthFactor }, { transitionLengthUm }, { outerWallWidthUm }, { innerWallWidthUm }, { firstLayerWidthUm }, { elephantFootUm }, { innerWallsFirst }, { seamStart }, { visibilityUnderside }, { visibilityBed }, { visibilityAway }, { visibilityQuadrants }, { seamCornerShare }, { seamScarfShare }, { viewFrom }, { visibilityAzimuths }, { visibilityElevations }, { visibilityMaxElevationDeg }, { visibilityReachUm }, { visibilityCellUm }, { visibilityShowThroughUm }, { seamCornerDeg }, { scarf }, { scarfLengthUm }, { scarfStart }, { topLayers }, { bottomLayers }, { topShellThicknessUm }, { bottomShellThicknessUm }, { coreLayers }, { maxLayerHeightUm }, { purgeInto }, { sparseDensity }, { sparsePattern }, { sparseChordUm }, { topPattern }, { bottomPattern }, { sparseWidthUm }, { minSparseAreaMm2 }, { solidWidthUm }, { topWidthUm }, { infillAngleDeg }, { infillWallOverlap }, { anchorMaxUm }, { bridgeSpeed }, { bridgeFlow }, { nozzleDiameterUm }, { brimType }, { brimWidthUm }, { brimGapUm }, { primeLine }, { primeLengthUm }, { primeGapUm }, { nozzleAngleDeg }, { nozzleHeightUm }, { headReachUm }, { bridgeTempC }, { bridgePrintSpeed }, { bridgeMoveSpeed }, { bridgeRampListed }, { bridgeRampGrowth }, { bridgeLayerHeightUm }, { bridgeTopNormal }, { bridgeOrderHotToCold }, { bridgeOrderOneAtATime }, { bridgeFirstLayerWave }, { bridgeWaveAlternate }, { bridgeWaveOneSupport }, { bridgeWaveEdgesLast }, { bridgeWaveEdgesNone }, { bridgeWaveLayers }, { bridgeWaveSecondX }, { bridgeWaveStart }, { bridgeWaveStartWidthUm }, { bridgeWaveChevron }, { bridgeWaveChevronLeadUm }, { curvedTopUphill }, { bridgeWaveChevronMiddle }, { bridgeWaveChevronAcross }, { bridgeWaveAfterSupported }, { bridgeWaveXLengthUm }, { bridgeWaveDirection }, { bridgeWaveCrossBeside }, { bridgeWaveCrossClearanceUm }, { bridgeWaveRetractMm }, { bridgeWaveWipe }, { bridgeWaveWaitS }, { bridgeWaveCrossAway }, { bridgeWaveAwayUm }, { bridgeWaveWaitZones }, { bridgeWaveSecondPass }, { bridgeWaveSecondPassSpeed }, { bridgeWaveSecondPassApproachUm }, { bridgeWaveXLayer }, { bridgeWaveXLayerRaiseUm }, { bridgeWaveXLayerShare }, { bridgeWaveXLayerInsetUm }, { bridgeWaveXLayerSpeed }, { bridgeWaveXLayerMiddleFirst }, { bridgeWaveSpanLayer }, { bridgeWaveSpanLayerRaiseUm }, { bridgeWaveSpanLayerSpeed }, { bridgeWaveEvenSpacing }, { bridgeFillSpan }, { bridgeFillAlong }, { tackLayers }, { tackLengthUm }, { tackRiseUm }, { tackSpacingUm }, { bedUm }, { topUnderParts }, { zAntialias }, { waveOverhang }, { undersideFromUm }, { undersideToUm }, { waveSpacingUm }, { waveOverlapUm }, { waveMinNeckUm }, { waveMinAreaMm2 }, { waveLineAreaMm2 }, { waveSpeed }, { narrowCells }, { narrowFill }, { enableSupport }, { overhang }, { overhangFanPct }, { overhangSpeed }, { supportXyUm }, { supportTopZUm }, { supportTopZMinUm }, { supportBottomZUm }, { supportMinWidthUm }, { supportInterfaceTop }, { supportInterfaceBottom }, { supportBaseSpacingUm }, { supportInterfaceSpacingUm }, { supportOnPlateOnly }, { supportWidthUm }, { supportAngleDeg }, { treeBranchAngleDeg }, { treeTipDiameterUm }, { treeBranchDiameterUm }, { treeDiameterAngleDeg }, { treeBranchDistanceUm }, { fuzzySkin }, { fuzzyThicknessUm }, { fuzzyPointDistUm }, { fuzzyFirstLayer }, { fuzzyMode }, { fuzzyNoise }, { ironingType }, { ironingSpacingUm }, { ironingFlow }, { ironingSpeed }, { ironingInsetUm }, { ironingAngleDeg }, { maxVolumetric }, { slowDownForCooling }, { slowDownLayerTimeS }, { coolingSlowdown }, { coolingDwell }, { coolingDwellStyle }, { coolingDwellLiftUm }, { slowDownMinSpeed }, { fanOffFirstLayers }, { fanMinPct }, { filamentDiameterMm }, { flowRatio }, { nozzleTempC }, { nozzleTempFirstC }, { bedTempC }, { bedTempFirstC }, { visibleSpeed }, { hiddenSpeed }, { firstLayerSpeed }, { travelSpeed }, { defaultAccel }, { visibleAccel }, { travelAccel }, { zSpeedMax }, { zAccelMax }, { cornerSpeed }, { zStepMm }, { retractMm }, { restartLagMm }, { retractSpeed }, { deretractSpeed }, { retractMinTravelMm }, { noRetractInIsland }, { avoidCrossing }, { detourMarginUm }, { detourMaxRatio }, { detourMaxExtraUm }, { detourSpeckMaxRatio }, { detourSpeckMaxExtraUm }, { detourInsetUm }, { zHopUm }, { wipe }, { wipeDistanceMm }, { wipeSpeed }, { retractBeforeWipe }, { gcodeFlavor }, { printerModel }, { machineDescription }, { dialect }, { gcodeDialect }, { maxPrintHeightMm }, { excludeObject }, { arcFitting }, { startGcode }, { endGcode }, { layerChangeGcode }, { pauseGcode }, { levelGcode }, { pictures })) f()
    }

    companion object {
        /** "6, 8, 12, 24 mm/s" as its speeds (mm/s; the unit optional, once at the end or on each); blank = none. */
        fun rampSpeeds(text: String): List<Double> {
            val t = text.trim().removeSuffix("mm/s")
            return t.split(',').map { it.trim().removeSuffix("mm/s").trim() }.filter { it.isNotEmpty() }.map { e ->
                val v = e.toDoubleOrNull()
                require(v != null && v > 0) { "plan.technique.bridge.ramp_speeds: '$e' is not a speed in mm/s (e.g. \"6, 8, 12, 24 mm/s\")" }
                v
            }
        }

        /**
         * "0-40%: 5 s, 40-60%: 10 s" as its zones (start and end as shares of the span, 0..1, and the wait in s; "%" and "s"
         * optional); blank = none. A malformed entry, or a zone outside 0-100 % or empty, is refused with the setting's name.
         */
        fun parseWaitZones(text: String): List<Triple<Double, Double, Double>> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
            val m = Regex("([0-9.]+)\\s*%?\\s*-\\s*([0-9.]+)\\s*%?\\s*:\\s*([0-9.]+)\\s*s?").matchEntire(e)
            val a = m?.groupValues?.get(1)?.toDoubleOrNull(); val b = m?.groupValues?.get(2)?.toDoubleOrNull(); val w = m?.groupValues?.get(3)?.toDoubleOrNull()
            require(a != null && b != null && w != null && a >= 0 && b <= 100 && b > a) {
                "plan.technique.bridge.wave.wait_zones: '$e' is not a zone FROM-TO%: WAIT s within 0-100 % (e.g. \"0-40%: 5 s\")" }
            Triple(a / 100, b / 100, w)
        }

        /**
         * "25-40%: 0.1 mm 50%, 40-60%: 0 mm 100%" as its zones (start and end as shares of the span, 0..1; the height over the
         * first pass, µm; the plastic as a share of the first pass's); blank = none. A malformed entry is refused with the setting's name.
         */
        fun parseSecondPass(text: String): List<SecondPassZone> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
            val m = Regex("([0-9.]+)\\s*%?\\s*-\\s*([0-9.]+)\\s*%\\s*:\\s*\\+?([0-9.]+)\\s*mm\\s+([0-9.]+)\\s*%").matchEntire(e)
            val a = m?.groupValues?.get(1)?.toDoubleOrNull(); val b = m?.groupValues?.get(2)?.toDoubleOrNull()
            val h = m?.groupValues?.get(3)?.toDoubleOrNull(); val f = m?.groupValues?.get(4)?.toDoubleOrNull()
            require(a != null && b != null && h != null && f != null && a >= 0 && b <= 100 && b > a && f > 0) {
                "plan.technique.bridge.wave.second_pass.zones: '$e' is not a zone FROM-TO%: HEIGHT mm PLASTIC% within 0-100 % (e.g. \"25-40%: 0.1 mm 50%\")" }
            SecondPassZone(a / 100, b / 100, Math.round(h * 1000), f / 100)
        }

        /** "140x110, 300x300" as its sizes; blank = none. A malformed entry is refused with the setting's name. */
        fun pictureSizes(text: String): List<Pair<Int, Int>> = text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { e ->
            val m = Regex("(\\d{1,5})\\s*[xX]\\s*(\\d{1,5})").matchEntire(e)
            val w = m?.groupValues?.get(1)?.toInt(); val h = m?.groupValues?.get(2)?.toInt()
            require(w != null && h != null && w > 0 && h > 0) { "hardware.machine.<key>.gcode.pictures: '$e' is not a picture size WIDTHxHEIGHT in pixels (e.g. 140x110)" }
            w to h
        }

        /** The settings for object [ctx] of [setup]; each is read (and counts as used) the first time it is asked for. */
        fun of(setup: Setup, ctx: Context = Context()): SliceSettings {
            val rec = java.util.concurrent.ConcurrentHashMap<String, String>()
            return SliceSettings({ id -> capture.get()?.add(id); Resolver.resolve(setup, id, ctx).value.also { rec[id] = it.toString() } },
                { id -> capture.get()?.add(id); Resolver.resolveOrNull(setup, id, ctx)?.value.also { rec[id] = it.toString() } }, setup.models, rec)
        }

        private val capture = ThreadLocal<MutableSet<String>?>()

        /**
         * [block]'s result and the ids of the settings read in it on this thread (a cached computation keeps them, to read them
         * again when its result is reused). The ids add to an enclosing [reading]'s.
         */
        fun <T> reading(block: () -> T): Pair<T, Set<String>> {
            val outer = capture.get()
            val mine = HashSet<String>()
            capture.set(mine)
            try { return block() to mine } finally { capture.set(outer); outer?.addAll(mine) }
        }

        /**
         * Every needed setting of object [ctx] with no value: every setting read ([readAll]), without counting as used, each
         * missing one noted and a stand-in given in its place so the rest are read too. A stand-in may trip a later check of
         * the values; what was found missing by then is the answer, and with nothing missing the check's own error stands.
         */
        fun missing(setup: Setup, ctx: Context): List<String> {
            val probe = Context(ctx.obj, ctx.part, record = false)
            val missing = LinkedHashSet<String>()
            val read = { id: String ->
                try { Resolver.resolve(setup, id, probe).value } catch (e: MissingValue) { missing += e.message!!; standIn(Catalog[id].kind) }
            }
            try {
                SliceSettings(read, { id -> Resolver.resolveOrNull(setup, id, probe)?.value }, setup.models, java.util.concurrent.ConcurrentHashMap()).readAll()
            } catch (e: RuntimeException) {
                if (missing.isEmpty()) throw e
            }
            return missing.toList()
        }

        /** A value of [kind] to read on with in place of a missing one (never used: the settings are refused). */
        private fun standIn(kind: Kind): Any = when (kind) {
            Kind.BOOL -> false
            Kind.CHOICE, Kind.TEXT -> ""
            else -> 1.0
        }
    }
}
