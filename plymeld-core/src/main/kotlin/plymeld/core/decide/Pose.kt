package plymeld.core.decide

import plymeld.mesh.TriangleSoup
import plymeld.model.Overhang
import java.util.Locale

/** A rigid placement: p' = R·p + t (R row-major 3x3, mm). */
class Pose(val label: String, val r: DoubleArray, val t: DoubleArray) {
    fun apply(s: TriangleSoup): TriangleSoup {
        val c = FloatArray(s.count * 9)
        for (i in 0 until s.count * 3) {
            val x = s.coords[3 * i].toDouble(); val y = s.coords[3 * i + 1].toDouble(); val z = s.coords[3 * i + 2].toDouble()
            for (k in 0 until 3) c[3 * i + k] = (r[3 * k] * x + r[3 * k + 1] * y + r[3 * k + 2] * z + t[k]).toFloat()
        }
        return TriangleSoup(c, s.count)
    }

    companion object {
        val IDENTITY = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0)
        fun asGiven() = Pose("as given", IDENTITY, doubleArrayOf(0.0, 0.0, 0.0))
        /** The model's own orientation, moved onto the bed: bottom at z = 0, footprint centered (PoseDecision.place). */
        fun asGivenCentered() = Pose("as given, centered on the bed", IDENTITY, doubleArrayOf(0.0, 0.0, 0.0))
    }
}

/** The facts the pose decision reads. */
class PoseFacts(
    val bedMinX: Double, val bedMinY: Double, val bedMaxX: Double, val bedMaxY: Double, val maxZ: Double,
    /** How far the filament's lines hold out over air: a face that steps out further per layer cannot hold itself up. */
    val overhang: Overhang,
    val overhangSource: String,
    /** The intent's goal weights. */
    val weights: Weights = Weights.parse("quality=5,speed=2,material=0,reliability=1", "test"),
    /**
     * The support switch (plan.support.enabled) as a lock: true = support under
     * every overhang too steep to hold itself up; false = never (unsupported area prints over air); null = unset, the
     * search decides per overhang (support's proposals, weighed), with [lockedSpots] always supported.
     */
    val supportEnabled: Boolean? = false,
    /** Settings the quick estimates read (null = that estimate is not measured): layer height (mm), maximum volumetric flow (mm³/s), minimum layer time (s). */
    val layerHeightMm: Double? = null, val flowMaxMm3s: Double? = null, val minLayerTimeS: Double? = null,
    /** method.search.time.max (s): the search stops measuring candidates when it is reached; null = no limit. */
    val timeLimitS: Double? = null,
    /** Layer settings the techniques read (null = not set): first layer height, the tool's thinnest and thickest layer, the largest stair-step of flat layers following the surfaces (mm). */
    val firstLayerMm: Double? = null, val minLayerMm: Double? = null, val maxLayerMm: Double? = null, val maxStepMm: Double? = null,
    /** method.search.zero_scale: the user's reference scales for counts whose best value is 0 (Scoring.rank); the rest are derived from the model. */
    val zeroScale: Map<Metric, Double> = emptyMap(), val zeroScaleSource: String = "",
    /** What the quick time estimate reads per layer beyond flow (null = flow and minimum layer time only). */
    val units: QuickUnits? = null,
    /** Fuzzy skin is on for the object (its texture depends on the layer height: flat layers cannot follow the surfaces, FlatLayers.followBlocked). */
    val fuzzySkin: Boolean = false,
    /**
     * Flat layer heights to try (one height) in every pose (mm), when the layer height is decided rather than set
     *: each is a candidate plan, scored with the rest. Null: the
     * settings' layer height only. Made by FlatLayers.coarse from [layerHeightControl].
     */
    val layerHeightCandidates: List<Double>? = null,
    /**
     * Flat layers' layer height as a control: its range (the tool's thinnest to thickest layer) or its lock.
     * With a continuous range the search refines it, round by round, next to the leading candidate where an interval
     * could still win, up to [layerHeightBudget] heights per pose (method.search.layer.heights). Null: not decided (the
     * settings' layer height).
     */
    val layerHeightControl: Control? = null, val layerHeightBudget: Int = layerHeightCandidates?.size ?: 1,
    /** Refinement rounds in a row that leave the winner as it was before the rounds stop (method.search.settle_rounds); 0 = no such stop. */
    val settleRounds: Int = 0,
    /**
     * Density (g/cm³) of the filament that prints the support, with where it came from: support's plastic is compared by
     * mass (support.mass). Null: compared by volume (support.volume), and [supportDensitySource] says why.
     */
    val supportDensity: Double? = null, val supportDensitySource: String = "no density given",
    /**
     * The baseline's layer height (mm): flat layers of this one height are what every candidate's differences are
     * measured against; [baselineWhat] says where it came from (e.g. "50 % of the 0.4 mm nozzle"). Null: no baseline can be
     * measured (no nozzle diameter), and the decision scores against its best candidate and says so.
     */
    val baselineLayerMm: Double? = null, val baselineWhat: String = "no nozzle diameter known",
    /** plan.technique.flat.height_ratio: the transition between layer heights (LayerPlan.composite); null or 0 = none. */
    val heightRatio: Double? = null,
    /** Planned inserts (plan.insert.<key>): the model's faces in a socket above its insert's top are covered, not over air. */
    val inserts: List<Insert> = emptyList(),
    /**
     * The surface techniques the search may weigh against as laid: those the job locks nothing for.
     * Empty = every surface technique locked by a lock (or, under another slicer's settings, by them). Only a surface technique with a predicted
     * outcome ([SurfaceTechnique.outcomes]) is weighed; ironing needs [ironingSpacingMm] and [ironingSpeedMmS].
     */
    val weighSurfaceTechniques: Set<SurfaceTechnique> = emptySet(),
    /** Ironing's own values its outcome model reads (plan.technique.ironing.spacing, mm; .speed, mm/s), with where they came from; null = ironing cannot be weighed (said). */
    val ironingSpacingMm: Double? = null, val ironingSpeedMmS: Double? = null, val ironingSource: String = "",
    /** The spots the user forces support on (plan.support.locked.<key>): requirements, in the model's own coordinates. */
    val lockedSpots: List<LockedSpot> = emptyList(),
    /**
     * Support spots a plan locks (plan.object.<key>.support.spot.<key>, what `plan -o` wrote): the overhang faces needing
     * support within each are supported, nothing else is searched; null = none locked (the search decides where the switch is unset).
     */
    val supportSpotsLocked: List<SupportPlan.Spot>? = null,
    /** Support's controls, each locked at the setting (density, gap, interface, angle: the bundled values are stand-ins until measured), for the record. */
    val supportControls: List<Control> = emptyList(),
    /** plan.support.column.width (mm): the narrowest column of support the generator lays; null = its rule, a tree tip ([Support.columnWidthMm]). */
    val supportColumnWidthMm: Double? = null,
    /**
     * How far the nozzle tip sits below the rest of the head (hardware.tool.<key>.head.nozzle.height, mm): a support column no
     * taller than this may be finished before the model's layers beside it (Coordination.Order.FIRST, Support.firstBlocked);
     * null = not known, with [nozzleHeightSource] saying so.
     */
    val nozzleHeightMm: Double? = null, val nozzleHeightSource: String = "hardware.tool.<key>.head.nozzle.height not set",
    /** The machine's travel speed (hardware.machine.motion.travel.speed, mm/s): what finishing a column first saves (Support.firstSavingS); null = the order is not weighed. */
    val travelSpeedMmS: Double? = null,
    /** method.search.pose.keep_margin (a score): the as-given pose stays unless another beats its best candidate by more; null or 0 = the best score wins. */
    val poseKeepMargin: Double? = null,
    /** hardware.filament.bridge.span.max (mm): a bridge longer than this needs support under its middle; null = any span bridges. */
    val bridgeMaxSpanMm: Double? = null,
    /** The visibility map every quality measure is weighted by (method.quality.visibility.*); null = every face counts alike. */
    val visibility: Visibility? = null,
    /** plan.layer.walls.outer.line.width (mm): a curved island's border is one line wide (Metric.CURVED_BORDER); null = the border not measured. */
    val outerLineWidthMm: Double? = null,
    /**
     * method.quality.stair_step.depth_power: the stair-step measure weighs each terrace edge by its depth to this power
     * ([PoseDecision.stairStep]), with where the value came from. The bundled 2 is plymeld's choice, not measured.
     */
    val stairStepPower: Double = 2.0, val stairStepPowerSource: String = "",
) {
    /** Support decided per overhang (the switch unset, nothing locked by a plan): support's survey runs and its proposals are weighed. */
    val supportSearched: Boolean get() = supportEnabled == null && supportSpotsLocked == null
}

/** What one locked spot holds in one pose ([PoseDecision.Measure.lockedStats]): its downward area (over air, above the first layer), the air column under it, what it stands on. */
class LockedStat(val spot: LockedSpot, val region: Coordination.Volume.Region, val areaMm2: Double, val columnMm3: Double, val standsOnMm: Double?,
                 /** The model surface its column stands on (mm², the support interface's bottom; 0 on the bed). */
                 val baseMm2: Double = 0.0) {
    val standsOn: String get() = standsOnMm?.let { "the model's surface %.2f mm up".format(Locale.ROOT, it) } ?: "the bed"
    val columnMm: Double get() = region.fromMm - (standsOnMm ?: 0.0)
}

/**
 * A finalist's full prediction: its g-code's predicted [timeS] (null = it could not be
 * predicted, [why] says why) and its filament ([filament]: by mass or volume, the plate's basis), everything the g-code
 * extrudes (ironing's and support's plastic included).
 */
class FullPrediction(val timeS: Double?, val filament: Pair<Metric, Double>?, val why: String,
                     /** seam_scar of the planned layers (SeamScar); null = not measured. */ val seamScarMm2: Double? = null,
                     /** string_risk and restart_starved of the program as written (StringRisk); null = not measured. */
                     val stringMm: Double? = null, val restartStarvedMm: Double? = null) {
    /** The measures read off the written program, by metric (those measured). */
    val measured: Map<Metric, Double> get() = listOfNotNull(seamScarMm2?.let { Metric.SEAM_SCAR to it }, stringMm?.let { Metric.STRING_RISK to it },
        restartStarvedMm?.let { Metric.RESTART_STARVED to it }).toMap()
}

/**
 * Settings the quick time estimate reads per layer for one group of parts (all mm, mm/s): walls
 * and sparse infill print at the slower of their speed and what the flow allows at the layer's
 * height, so the number of layers counts, not only the volume.
 */
class QuickSpeeds(val wallLoops: Int, val outerWallSpeed: Double, val innerWallSpeed: Double, val wallWidthMm: Double,
                  val sparseSpeed: Double, val sparseDensity: Double, val sparseWidthMm: Double,
                  /** Top / bottom shells: this thick (the layers follow from the layer height); printed at [solidSpeed] with lines [solidWidthMm] wide. */
                  val topMm: Double = 0.0, val bottomMm: Double = 0.0,
                  val solidSpeed: Double = 0.0, val solidWidthMm: Double = 0.0)

/**
 * The object's parts in groups that print with the same settings and filament (as the slicer
 * groups them): [partUnit] gives each part's group, in the order of the parts given to the
 * decision; each group has its [speeds] and [spools]; each filament change purges [purgeMm3].
 */
class QuickUnits(val partUnit: IntArray, val speeds: List<QuickSpeeds>, val spools: List<String>, val purgeMm3: Double,
                 /** The machine's own time per tool change (hardware.machine.tool_change.time); 0 = not counted. */
                 val changeS: Double = 0.0,
                 /** The object's own settings: top and bottom shells are judged on the whole object (as the slicer does), with these. */
                 val whole: QuickSpeeds = speeds.first())

/**
 * Pose: how the object sits relative to gravity and the print head.
 *
 * Candidates: the six orientations that put a principal axis of the model's own frame downward
 * (the model as given is one of them), plus resting on each of the largest flat faces of the mesh
 * (a face the part cannot rest on — not on its convex hull — measures no bed contact and is
 * rejected). Each is measured on the mesh itself: flat area resting on the bed, downward-facing
 * area steeper than the self-support limit, height, and whether it fits the machine. Ranked by the
 * intent's priorities. NOT YET: loads, cosmetic faces, rotation about Z.
 */
object PoseDecision {
    /** Geometric tolerances of the measurement, not print parameters. */
    private const val ON_BED_MM = 0.01
    private const val FLAT_COS = 0.999   // a face within ~2.6° of facing straight down counts as flat on the bed

    /**
     * [tipOffsetMm]: how far the center of mass lies beyond the bed contact (outside the convex hull
     * of the contact faces; 0 when over it). Parts are adhered to the bed, so this is a RISK to weigh
     *, not a reason to reject a pose. [stable] = the offset is 0.
     */
    class Measure(val contactMm2: Double, val needsSupportMm2: Double, val heightMm: Double, val sizeX: Double, val sizeY: Double, val tipOffsetMm: Double = 0.0,
                  /** Part of [needsSupportMm2] on a sealed internal cavity (Cavities): bridged, or support trapped inside. */
                  val sealedMm2: Double = 0.0,
                  /** Quick estimates (null = not measured): see the Metric of the same name. */
                  val surfaceStepMm: Double? = null, val weakestBondMm2: Double? = null, val timeEstimateS: Double? = null, val supportVolumeMm3: Double = 0.0,
                  /** The techniques the estimates assume (by height band), and their layer tops in this pose (null = no layer facts). */
                  val plan: TechniquePlan = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS), val layerTops: DoubleArray? = null,
                  val layerLinesMm: Double? = null,
                  /** Flat tops above the first layer (mm²): what ironing would pass over (SurfaceTechniques.ironingTimeS) and what is rough as laid (Metric.ROUGH_TOP_AREA). */
                  val flatTopMm2: Double = 0.0,
                  /** The surface techniques this candidate is laid with (a candidate is a plan AND its surface techniques; empty = as laid, or the surface techniques locked by the job's locks, which every candidate shares). */
                  val surfaceTechniques: Set<SurfaceTechnique> = emptySet(),
                  /**
                   * Support decided per spot: of [needsSupportMm2], the area the plan's spots (chosen, locked or locked) hold
                   * up ([heldMm2]) with its air column ([heldVolumeMm3]); the downward area in locked spots that needs no support but is
                   * supported anyway ([lockedExtraMm2], its column [lockedExtraVolumeMm3]); the overhang regions support's survey found
                   * under this plan's layers ([overhangs], where the search decides); each locked spot as found in this pose ([lockedStats]).
                   */
                  val heldMm2: Double = 0.0, val heldVolumeMm3: Double = 0.0, val lockedExtraMm2: Double = 0.0, val lockedExtraVolumeMm3: Double = 0.0,
                  val overhangs: List<OverhangRegion>? = null, val lockedStats: List<LockedStat>? = null,
                  /** The support interface's bottoms: the model surface the held regions' columns stand on ([heldBaseMm2]) and the locked spots' ([lockedBaseMm2]); 0 on the bed. */
                  val heldBaseMm2: Double = 0.0, val lockedBaseMm2: Double = 0.0,
                  /** Droop severity: [needsSupportMm2] weighted by how far past the allowed hang each face steps out ([droopSeverityMm2]), and the held part of it ([heldSeverityMm2]). */
                  val droopSeverityMm2: Double = 0.0, val heldSeverityMm2: Double = 0.0,
                  /** height over the square root of the bed contact; the first layer's islands; the flat faces' distance to the layer grid (mm, null = no layer facts). */
                  val slenderness: Double = 0.0, val firstLayerIslands: Int = 1, val heightErrorMm: Double? = null,
                  /**
                   * The visibility map ([PoseFacts.visibility]): the model's surface ([surfaceMm2]) and how much of it counts as seen
                   * ([visibleMm2]); the support areas weighted by it for quality's measures ([needsSupportVisibleMm2], [heldVisibleMm2],
                   * [lockedExtraVisibleMm2]: the unweighted ones stay for support's plastic and the plan). Without a map, equal to the unweighted.
                   */
                  val surfaceMm2: Double = 0.0, val visibleMm2: Double = surfaceMm2,
                  val needsSupportVisibleMm2: Double = needsSupportMm2, val heldVisibleMm2: Double = heldMm2, val lockedExtraVisibleMm2: Double = lockedExtraMm2,
                  /** The plan's curved islands' border (Metric.CURVED_BORDER, mm²): 0 without islands; null = not measured (no line width known). */
                  val curvedBorderMm2: Double? = null,
                  /**
                   * This plan's stair-step over each curved island's own sloped faces, for every island any plan measured with it has
                   * (key: the island's): the faces' seen area (mm²), their share of the model's seen sloped area, and the mean
                   * stair-step this plan gives them, split into the faces along the island's layers and the rest. Where the
                   * measure's change comes from (IslandDecision's facts).
                   */
                  val islandFaces: Map<String, IslandFaces> = emptyMap()) {
        val stable: Boolean get() = tipOffsetMm == 0.0
        fun with(bond: Double?, time: Double?) = Measure(contactMm2, needsSupportMm2, heightMm, sizeX, sizeY, tipOffsetMm, sealedMm2, surfaceStepMm, bond, time, supportVolumeMm3, plan, layerTops, layerLinesMm, flatTopMm2, surfaceTechniques,
            heldMm2, heldVolumeMm3, lockedExtraMm2, lockedExtraVolumeMm3, overhangs, lockedStats, heldBaseMm2, lockedBaseMm2, droopSeverityMm2, heldSeverityMm2, slenderness, firstLayerIslands, heightErrorMm,
            surfaceMm2, visibleMm2, needsSupportVisibleMm2, heldVisibleMm2, lockedExtraVisibleMm2, curvedBorderMm2, islandFaces)
        /** The same candidate laid with [fs]. */
        fun withSurfaceTechniques(fs: Set<SurfaceTechnique>) = Measure(contactMm2, needsSupportMm2, heightMm, sizeX, sizeY, tipOffsetMm, sealedMm2, surfaceStepMm, weakestBondMm2, timeEstimateS, supportVolumeMm3, plan, layerTops, layerLinesMm, flatTopMm2, fs,
            heldMm2, heldVolumeMm3, lockedExtraMm2, lockedExtraVolumeMm3, overhangs, lockedStats, heldBaseMm2, lockedBaseMm2, droopSeverityMm2, heldSeverityMm2, slenderness, firstLayerIslands, heightErrorMm,
            surfaceMm2, visibleMm2, needsSupportVisibleMm2, heldVisibleMm2, lockedExtraVisibleMm2, curvedBorderMm2, islandFaces)
        /** The share of the surface that counts as seen (1 without a map). */
        val seenShare: Double get() = if (surfaceMm2 <= 0) 1.0 else visibleMm2 / surfaceMm2
        /** The same plan with the same surface techniques: one candidate (in one pose). */
        fun same(o: Measure): Boolean = plan == o.plan && surfaceTechniques == o.surfaceTechniques
    }

    /**
     * The layers [plan] prints [p] (in its print pose) with, or null when a fact it reads is not
     * set. Flat layers of one height throughout: the plan's layer height, else the intent's. Otherwise
     * LayerPlan.composite: bands following the surfaces with the tool's limits and the largest stair-step,
     * bands of one height at the layer height, landing on every band boundary. [lim]: the model's limits
     * for following the surfaces, when already known.
     */
    fun layers(plan: TechniquePlan, p: TriangleSoup, f: PoseFacts, lim: plymeld.mesh.LayerPlan.Limits? = null): plymeld.mesh.LayerPlan? {
        val set = f.layerHeightMm ?: return null
        val h = plan.layerHeightMm ?: set
        val first = f.firstLayerMm ?: h
        val b = p.bounds()
        if (plan.techniques.any { it != PrintTechnique.FLAT_LAYERS }) return null
        val min = f.minLayerMm ?: return null
        if (plan.oneHeight) return plymeld.mesh.LayerPlan.uniform(b[2], b[5], first, h, min)
        val max = f.maxLayerMm ?: return null
        // bands following the surfaces keep their own largest step (the same as [lim]'s): only bands of one height take the plan's height
        return plymeld.mesh.LayerPlan.composite(p, first, plan.absolute(b[2], b[5]), h, min, max, f.maxStepMm ?: set, limits = lim, heightRatio = f.heightRatio ?: 0.0)
    }

    /** The model's limits for layers following the surfaces in this pose, when the settings for them are known. */
    fun limits(p: TriangleSoup, f: PoseFacts): plymeld.mesh.LayerPlan.Limits? {
        val h = f.layerHeightMm ?: return null
        return plymeld.mesh.LayerPlan.limits(p, f.minLayerMm ?: return null, f.maxLayerMm ?: return null, f.maxStepMm ?: h)
    }

    /** Heights sampled per pose for the section-based estimates: search effort, not a print parameter (to become a setting). */
    private const val SECTION_SAMPLES = 40

    /**
     * [covered]: per triangle, the share another shell of the same object covers ([Cavities.coveredShare]);
     * null = one shell, nothing covered. A downward face with solid of the same object against it
     * (e.g. a plate sitting on a slab, a part buried in another) is not over air. [layerMm]: the layer
     * height overhangs are judged at when neither the plan nor [f] gives one.
     */
    fun measure(s: TriangleSoup, pose: Pose, overhang: Overhang, cavity: BooleanArray? = null, covered: FloatArray? = null, f: PoseFacts? = null, withSections: Boolean = true,
                plan: TechniquePlan = TechniquePlan.whole(PrintTechnique.FLAT_LAYERS), layerMm: Double? = null): Measure =
        measureAll(s, pose, overhang, cavity, covered, f, withSections, { _, _ -> listOf(plan) }, layerMm = layerMm).single()

    /**
     * The stair-step one face shows (Metric.SURFACE_STEP): its terrace edges per mm, x each edge's
     * depth to the power [power] (method.quality.stair_step.depth_power). [layerMm] is the layer's height there (vertical),
     * [lean] the face's |normal's vertical part|, [cosToLayer] the cosine of the angle between the face and its layer.
     * - Each edge's depth: the layer's height x [lean]. Each layer's edge is laid upright, so where a face crosses layers the
     *   sawtooth is that deep whatever the layers' tilt (a vertical wall is flush under tilted layers as under flat ones).
     * - Edges per mm along the face: the sine of its angle to its layer / the layer's height. A face along its layer crosses
     *   none (the cap a curved island follows); flat layers on a gentle slope cross few, far apart.
     * Depth alone (the measure earlier) charged a gentle slope nearly a whole layer whatever the edges' spacing, so a
     * curved island a few degrees off its cap counted as stepped as flat layers, and no island on the Benchy or a larger test model
     * removed any. Edges alone would favor thick layers everywhere (fewer edges); the power keeps a deep edge costing more
     * than a shallow one (2: an edge half as deep counts a quarter), so thinner layers still score better.
     */
    /**
     * A curved island's own sloped faces under one plan ([Measure.islandFaces]): their seen area (mm²) and share of the model's
     * seen sloped area; of it, the faces along the island's layers (within the flat angle of them, [alongMm2]), the other
     * faces facing up more than [UP_SIN] from vertical ([upMm2]: the cap's surface the layers miss) and the rest (walls and
     * undersides); the mean stair-step this plan gives each ([stepAlong], [stepUp], [stepRest]) and all of them ([step]).
     */
    class IslandFaces(val areaMm2: Double, val share: Double, val alongMm2: Double, val upMm2: Double, val step: Double, val stepAlong: Double, val stepUp: Double, val stepRest: Double)

    /** An island's face facing up more than ~5 degrees from vertical counts as its cap's surface in [IslandFaces] (plymeld's choice, a log's split). */
    const val UP_SIN = 0.087

    fun stairStep(layerMm: Double, lean: Double, cosToLayer: Double, power: Double): Double {
        val sin = Math.sqrt((1 - cosToLayer * cosToLayer).coerceAtLeast(0.0))
        return sin / layerMm * Math.pow(layerMm * lean, power)
    }

    /**
     * A sloped face of a curved island above its start (unit normal [nx], [ny], [nz], centroid [x], [y], [z]; [w] the island's
     * warp): its stair-step ([stairStep], to [power]) and layer line height in the island's OWN layers there. The layer's tilt
     * (the share of the dip its height has reached) gives the face's angle to its layer; within the flat angle the layer's top
     * lies on the face (the cap the island follows): neither. Else its layer's height there (vertical: the island's own,
     * thinner where it dips).
     */
    private fun islandStep(isl: CurvedIsland, w: plymeld.mesh.ShiftWarp, nx: Double, ny: Double, nz: Double, x: Double, y: Double, z: Double, power: Double): Pair<Double, Double> {
        val c = cosToIslandLayer(isl, w, nx, ny, nz, x, y, z)
        if (c >= FLAT_COS) return 0.0 to 0.0
        val hz = isl.layerMm * w.thicknessFactor(x, y)
        return stairStep(hz, Math.abs(nz), c, power) to hz
    }

    /** The cosine of the angle between a face (unit normal [nx], [ny], [nz] at [x], [y], [z]) and island [isl]'s layer there ([w] its warp). */
    private fun cosToIslandLayer(isl: CurvedIsland, w: plymeld.mesh.ShiftWarp, nx: Double, ny: Double, nz: Double, x: Double, y: Double, z: Double): Double {
        val g = ((w.warp(x, y, z) - w.zStart) / (w.zTop - w.zStart)).coerceIn(0.0, 1.0)
        val h = isl.cellMm / 2
        val gx = g * (isl.dipAt(x + h, y) - isl.dipAt(x - h, y)) / (2 * h); val gy = g * (isl.dipAt(x, y + h) - isl.dipAt(x, y - h)) / (2 * h)
        // the layer surface: real z = warped z - dip x g, so its normal leans along the dip's slope, scaled by g
        return Math.abs(nx * gx + ny * gy + nz) / Math.sqrt(gx * gx + gy * gy + 1)
    }

    /**
     * Curved island [isl]'s border on posed soup [p] (Metric.CURVED_BORDER, mm²): its loops at its start (the model cut there,
     * the loops the island holds), each edge's length x [lineMm], weighted by how much a side facing out there is seen ([vis]).
     */
    private fun islandBorderMm2(p: TriangleSoup, isl: CurvedIsland, zMin: Double, lineMm: Double, vis: Visibility?): Double {
        val cut = plymeld.mesh.Slicer.slice(p, doubleArrayOf(zMin + isl.startMm + BORDER_ABOVE_MM))[0].loops.filter { isl.holds(it) }
        var sum = 0.0
        for (lp in cut) for (i in 0 until lp.size) {
            val ax = lp.x(i) / 1000.0; val ay = lp.y(i) / 1000.0; val bx = lp.x((i + 1) % lp.size) / 1000.0; val by = lp.y((i + 1) % lp.size) / 1000.0
            val len = Math.hypot(bx - ax, by - ay)
            if (len <= 0) continue
            // material on an edge's left (outer loops counter-clockwise, holes clockwise): it faces out to its right
            val ox = (by - ay) / len; val oy = -(bx - ax) / len
            val seen = vis?.of(0.0, false, 0.0, vis.awayDot(ox, oy, 0.0)) ?: 1.0
            sum += len * lineMm * seen
        }
        return sum
    }
    /** Where an island's border is cut, above its start (mm): clear of a face lying at the start itself. plymeld's choice. */
    private const val BORDER_ABOVE_MM = 0.01

    /**
     * [pose] measured once for its geometry, and under each plan [plansFor] gives for the posed model
     * (with its limits for following the surfaces, when known: what the survey reads) for the estimates that depend on
     * how it is built (stair-step and time follow each plan's own layer heights, and so does what needs
     * support: a face holds where it steps out no further per layer than [overhang] allows, so a
     * thinner layer holds a flatter face).
     */
    fun measureAll(s: TriangleSoup, pose: Pose, overhang: Overhang, cavity: BooleanArray? = null, covered: FloatArray? = null, f: PoseFacts? = null, withSections: Boolean = true,
                   plansFor: (TriangleSoup, plymeld.mesh.LayerPlan.Limits?) -> List<TechniquePlan>,
                   /** Triangles per part, in [s]'s order (s = the parts merged): the quick time estimate slices each group of parts ([PoseFacts.units]). */
                   partTriangles: IntArray? = null, layerMm: Double? = null,
                   /** Survey the overhang regions even where the search does not decide support ([unheldWithSupportOff]). */
                   surveyAnyway: Boolean = false): List<Measure> {
        val p = pose.apply(s)
        val b = p.bounds()
        val zMin = b[2]
        var contact = 0.0
        var slopedArea = 0.0
        // flat tops above the first layer: what ironing passes over (the Planner irons nothing on the first layer)
        var flatTop = 0.0
        val firstLayer = f?.firstLayerMm ?: f?.layerHeightMm ?: layerMm ?: 0.0
        val lim = f?.let { limits(p, it) }
        val techniques = plansFor(p, lim)
        // each plan's layers planned in parallel, kept in the plans' order (one after another on the main thread,
        // the flat-layer planning was most of a 3DBenchy's search rounds)
        val tops = inOrder(techniques.size, s.count.toLong() * 36) { k -> f?.let { layers(techniques[k], p, it, lim)?.tops } }
        val stepSum = DoubleArray(techniques.size)
        val lineSum = DoubleArray(techniques.size)
        // height error: flat horizontal faces above the bed against each plan's layer tops
        val errSum = DoubleArray(techniques.size); var flatSum = 0.0
        val support = DoubleArray(techniques.size); val sealed = DoubleArray(techniques.size); val supportVol = DoubleArray(techniques.size)
        // support decided per spot: what each plan's spots hold up, what the locked spots add, and (where the
        // search decides) the faces needing support under each plan, for support's survey
        val decided = f != null && f.supportEnabled == null
        val lockedPosed = f?.lockedSpots.orEmpty().map { it to it.posed(pose, b) }
        val anyLocked = lockedPosed.isNotEmpty()
        // planned inserts: a face in a socket at or above its insert's top is covered by the insert, not over air
        val insertsPosed = f?.inserts.orEmpty().map { it.posed(pose, b) }
        val held = DoubleArray(techniques.size); val heldVol = DoubleArray(techniques.size)
        val droopSev = DoubleArray(techniques.size); val heldSev = DoubleArray(techniques.size)
        val extra = DoubleArray(techniques.size); val extraVol = DoubleArray(techniques.size)
        // the visibility map: quality's areas weighted by how much each face is seen; the droop and scar sums by the
        // direction term only (their areas already leave out what another part covers, overAir); null map = every face 1
        // the view is on the model's own side: turned with this candidate's pose
        val vis = f?.visibility?.posed(pose.r)
        // the stair-step measure's depth power (method.quality.stair_step.depth_power; stairStep)
        val power = f?.stairStepPower ?: 2.0
        var surfaceAll = 0.0; var surfaceSeen = 0.0
        val supportVis = DoubleArray(techniques.size); val heldVis = DoubleArray(techniques.size); val extraVis = DoubleArray(techniques.size)
        val lockedArea = DoubleArray(lockedPosed.size); val lockedVol = DoubleArray(lockedPosed.size)
        val survey = f != null && (f.supportSearched || surveyAnyway)
        val needsOf = if (survey) Array(techniques.size) { BooleanArray(p.count) } else null
        val areaOf = if (survey) DoubleArray(p.count) else null
        val zcOf = if (survey) DoubleArray(p.count) else null
        val sevOfK = if (survey) Array(techniques.size) { DoubleArray(p.count) } else null
        fun spotsOf(k: Int): List<SupportPlan.Spot> = (techniques[k].support?.chosen.orEmpty()) + f?.supportSpotsLocked.orEmpty()
        /** The height of the layer of [t] that holds z (the last layer above the model's top). */
        fun heightAt(t: DoubleArray, z: Double): Double {
            var i = java.util.Arrays.binarySearch(t, z).let { if (it < 0) -it - 1 else it }
            if (i >= t.size) i = t.size - 1
            return t[i] - (if (i == 0) zMin else t[i - 1])
        }
        /** The layer height plan [k] prints height [z] at: its own layers, else its one flat height, else the settings'. */
        fun layerAt(k: Int, z: Double): Double = tops[k]?.let { heightAt(it, z) } ?: techniques[k].layerHeightMm ?: f?.layerHeightMm ?: layerMm
            ?: error("no layer height to judge overhangs at (pose '${pose.label}')")
        val c = p.coords
        // curved islands: each plan's, placed on the posed object, with
        // its warp; a sloped face in one above its start is judged against its own layer there (islandStep)
        val islandsOf = techniques.map { st -> st.islands.map { it.placed(b[0], b[1]) } }
        val islandWarps = islandsOf.map { ls -> ls.map { it.warp(zMin) } }
        // every plan's stair-step over each island's own faces (Measure.islandFaces), for every island of any plan here
        val probe = islandsOf.flatten().distinctBy { it.key }
        val probeWarps = probe.map { it.warp(zMin) }
        val probeArea = DoubleArray(probe.size); val probeAlong = DoubleArray(probe.size); val probeUp = DoubleArray(probe.size)
        val probeStep = Array(techniques.size) { DoubleArray(probe.size) }; val probeStepAlong = Array(techniques.size) { DoubleArray(probe.size) }
        val probeStepUp = Array(techniques.size) { DoubleArray(probe.size) }
        val inProbe = BooleanArray(probe.size); val alongProbe = BooleanArray(probe.size); val upProbe = BooleanArray(probe.size)
        fun overAir(o: Int): Double = if (covered == null) 1.0 else 1.0 - covered[o / 9]
        val contactPts = ArrayList<DoubleArray>()
        var vol = 0.0; var mx = 0.0; var my = 0.0   // center of mass (x, y) of the solid, by signed tetrahedra from the origin
        for (i in 0 until p.count) {
            val o = 9 * i
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0.0) continue
            val area = len / 2
            val v6 = c[o].toDouble() * (c[o + 4].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 7]) -
                c[o + 1].toDouble() * (c[o + 3].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 6]) +
                c[o + 2].toDouble() * (c[o + 3].toDouble() * c[o + 7] - c[o + 4].toDouble() * c[o + 6])
            vol += v6; mx += v6 * (c[o] + c[o + 3] + c[o + 6]) / 4; my += v6 * (c[o + 1] + c[o + 4] + c[o + 7]) / 4
            val down = -nz / len                                        // 1 = facing straight down
            // stair-steps (stairStep): a face's terrace edges per mm x each edge's depth (h x |nz|) to the depth power; flat
            // faces (horizontal) and vertical ones show none
            val zc = (c[o + 2] + c[o + 5] + c[o + 8]) / 3.0
            val onBed = maxOf(c[o + 2], c[o + 5], c[o + 8]) - zMin < ON_BED_MM
            val bedContact = onBed && down > FLAT_COS
            // how much of the face is seen (1 without a map): the sloped and vertical faces' sums weigh by it, so
            // surface_step and layer_lines are means over the SEEN sloped area; the direction term alone for support
            val awayDot = vis?.awayDot(nx / len, ny / len, nz / len) ?: 0.0
            val seen = vis?.of(down, bedContact, 1.0 - overAir(o), awayDot) ?: 1.0
            val seenDir = vis?.let { it.direction(down) * it.awayTerm(awayDot) } ?: 1.0
            surfaceAll += area; surfaceSeen += area * seen
            if (Math.abs(down) < FLAT_COS) {
                slopedArea += area * seen
                val fx = (c[o] + c[o + 3] + c[o + 6]) / 3.0; val fy = (c[o + 1] + c[o + 4] + c[o + 7]) / 3.0
                for (q in probe.indices) {
                    inProbe[q] = probe[q].covers(fx, fy) && zc - zMin > probe[q].startMm && zc - zMin <= probe[q].topMm + 1e-6
                    alongProbe[q] = inProbe[q] && cosToIslandLayer(probe[q], probeWarps[q], nx / len, ny / len, nz / len, fx, fy, zc) >= FLAT_COS
                    upProbe[q] = inProbe[q] && !alongProbe[q] && nz / len > UP_SIN
                    if (inProbe[q]) probeArea[q] += area * seen
                    if (alongProbe[q]) probeAlong[q] += area * seen
                    if (upProbe[q]) probeUp[q] += area * seen
                }
                for (k in tops.indices) tops[k]?.let { t ->
                    val j = islandsOf[k].indexOfFirst { it.covers(fx, fy) && zc - zMin > it.startMm && zc - zMin <= it.topMm + 1e-6 }
                    // flat layers: the face's angle to its layer is its angle to level (cos = |down|)
                    val v: Double
                    if (j < 0) { val hz = heightAt(t, zc); v = stairStep(hz, Math.abs(down), Math.abs(down), power); lineSum[k] += area * seen * hz }
                    else { val (st, ln) = islandStep(islandsOf[k][j], islandWarps[k][j], nx / len, ny / len, nz / len, fx, fy, zc, power); v = st; lineSum[k] += area * seen * ln }
                    stepSum[k] += area * seen * v
                    for (q in probe.indices) if (inProbe[q]) {
                        probeStep[k][q] += area * seen * v
                        if (alongProbe[q]) probeStepAlong[k][q] += area * seen * v
                        if (upProbe[q]) probeStepUp[k][q] += area * seen * v
                    }
                }
            } else if (down < 0 && zc - zMin > firstLayer + 1e-6) flatTop += area * overAir(o)
            if (Math.abs(down) >= FLAT_COS && zc - zMin > ON_BED_MM) {
                flatSum += area
                for (k in tops.indices) tops[k]?.let { t ->
                    var i = java.util.Arrays.binarySearch(t, zc).let { if (it < 0) -it - 1 else it }
                    val above = if (i < t.size) Math.abs(t[i] - zc) else Double.POSITIVE_INFINITY
                    val below = if (i > 0) Math.abs(zc - t[i - 1]) else Math.abs(zc - zMin)
                    errSum[k] += area * minOf(above, below)
                }
            }
            if (bedContact) { contact += area; for (k in 0 until 3) contactPts.add(doubleArrayOf(c[o + 3 * k].toDouble(), c[o + 3 * k + 1].toDouble())) }
            else if (down > 0) {
                // the face's center, relative to the posed object's corner and bottom (the frame of a spot's region)
                val cx = (c[o] + c[o + 3] + c[o + 6]) / 3.0 - b[0]; val cy = (c[o + 1] + c[o + 4] + c[o + 7]) / 3.0 - b[1]; val cz = zc - zMin
                val a = if (insertsPosed.any { it.covers(cx, cy, cz) }) 0.0 else area * overAir(o)
                val lockedAt = if (!anyLocked || cz <= firstLayer + 1e-6 || a <= 0) -1 else lockedPosed.indexOfFirst { it.second.contains(cx, cy, cz) }
                if (lockedAt >= 0) { lockedArea[lockedAt] += a; lockedVol[lockedAt] += a * (zc - zMin) }
                if (areaOf != null) { areaOf[i] = a; zcOf!![i] = zc }
                for (k in techniques.indices) {
                    val h = layerAt(k, zc)
                    val needs = overhang.needsSupport(down, h)
                    if (needs) {
                        support[k] += a; supportVis[k] += a * seenDir; if (cavity != null && cavity[i]) sealed[k] += a
                        supportVol[k] += a * (zc - zMin)   // a column down to the bed (stand-in: ignores part below)
                        // droop severity: how far past the allowed hang the face's layers step out, 1 at the limit,
                        // SEVERITY_CAP for a horizontal face over nothing; weighted by how much the face is seen
                        val sev = severity(down, h, overhang)
                        droopSev[k] += a * sev * seenDir
                        // the survey's regions: faces no locked spot holds (a locked spot supports its faces already, so a
                        // survey region never claims the same faces as a locked one)
                        if (lockedAt < 0) { needsOf?.get(k)?.set(i, true); sevOfK?.get(k)?.set(i, sev) }
                        if (lockedAt >= 0 || (decided && spotsOf(k).any { it.region.contains(cx, cy, cz) })) { held[k] += a; heldVis[k] += a * seenDir; heldVol[k] += a * (zc - zMin); heldSev[k] += a * sev * seenDir }
                    } else if (lockedAt >= 0) { extra[k] += a; extraVis[k] += a * seenDir; extraVol[k] += a * (zc - zMin) }
                }
            }
        }
        // support's survey (where the search decides): the overhang regions under each plan's layers
        val vertexIds = if (survey && needsOf!!.any { n -> n.any { it } }) Overhangs.vertexIds(p) else null
        val regionsOf: List<List<OverhangRegion>?> = inOrder(techniques.size, s.count.toLong() * 36) { k ->
            if (!survey) null else if (vertexIds == null) emptyList() else Overhangs.survey(p, b, vertexIds, needsOf!![k], areaOf!!, zcOf!!, pose.label, Support.minRegionWidthMm(f.supportControls), sevOfK!![k],
                columnWidthMm = Support.columnWidthMm(f.supportControls, f.supportColumnWidthMm))
        }
        val lockedStats: List<LockedStat>? = if (!anyLocked) null else lockedPosed.mapIndexed { j, (spot, region) ->
            val so = if (lockedArea[j] <= 0) null else Overhangs.standsOn(p, b[0] + (region.x0 + region.x1) / 2, b[1] + (region.y0 + region.y1) / 2, zMin + region.fromMm)?.let { it - zMin }
            LockedStat(spot, region, lockedArea[j], lockedVol[j], so, Overhangs.baseArea(p, b, region, so, pose.label))
        }
        // the support interface's bottoms: the model surface the held regions' columns stand on, and the locked spots'
        val heldBase = DoubleArray(techniques.size) { k -> regionsOf[k]?.filter { r -> spotsOf(k).any { it.region == r.region } }?.sumOf { it.baseMm2 } ?: 0.0 }
        val lockedBase = lockedStats?.sumOf { it.baseMm2 } ?: 0.0
        val tip = if (vol == 0.0 || contactPts.size < 3) Double.POSITIVE_INFINITY else hullDistance(contactPts, mx / vol, my / vol)
        // how tall and narrow the print stands, and how many islands its first layer is
        val slender = (b[5] - b[2]) / Math.sqrt(maxOf(contact, 1e-6))
        val islands = plymeld.mesh.Slicer.slice(p, doubleArrayOf(zMin + maxOf(firstLayer, 0.1) / 2))[0].loops.count { it.signedAreaMm2() > 0 }.coerceAtLeast(1)
        // each plan's curved islands' border (Metric.CURVED_BORDER): 0 without islands; not measured without a line width
        val border = f?.outerLineWidthMm?.let { w -> islandsOf.map { ls -> ls.sumOf { islandBorderMm2(p, it, zMin, w, vis) } } }
        val geometry = techniques.indices.map { k ->
            val step = if (tops[k] == null) null else if (slopedArea > 0) stepSum[k] / slopedArea else 0.0
            val lines = if (tops[k] == null || slopedArea <= 0) null else lineSum[k] / slopedArea
            Measure(contact, support[k], b[5] - b[2], b[3] - b[0], b[4] - b[1], tip, sealed[k], step, null, null, supportVol[k], techniques[k], tops[k], lines, flatTop,
                heldMm2 = held[k], heldVolumeMm3 = heldVol[k], lockedExtraMm2 = extra[k], lockedExtraVolumeMm3 = extraVol[k], overhangs = regionsOf[k], lockedStats = lockedStats,
                heldBaseMm2 = heldBase[k], lockedBaseMm2 = lockedBase, droopSeverityMm2 = droopSev[k], heldSeverityMm2 = heldSev[k],
                slenderness = slender, firstLayerIslands = islands, heightErrorMm = if (tops[k] == null) null else if (flatSum <= 0) 0.0 else errSum[k] / flatSum,
                surfaceMm2 = surfaceAll, visibleMm2 = surfaceSeen, needsSupportVisibleMm2 = supportVis[k], heldVisibleMm2 = heldVis[k], lockedExtraVisibleMm2 = extraVis[k], curvedBorderMm2 = border?.get(k),
                islandFaces = probe.indices.filter { probeArea[it] > 0 }.associate { q -> val rest = probeArea[q] - probeAlong[q] - probeUp[q]
                    fun mean(sum: Double, a: Double) = if (a > 0) sum / a else 0.0
                    probe[q].key to IslandFaces(probeArea[q], if (slopedArea > 0) probeArea[q] / slopedArea else 0.0, probeAlong[q], probeUp[q], probeStep[k][q] / probeArea[q],
                        mean(probeStepAlong[k][q], probeAlong[q]), mean(probeStepUp[k][q], probeUp[q]), mean(probeStep[k][q] - probeStepAlong[k][q] - probeStepUp[k][q], rest)) })
        }
        return if (f == null || !withSections) geometry else posedWithSections(p, geometry, f, partTriangles)
    }

    /**
     * The air column support fills in candidate [m] (mm³), what the quick time estimate prints at the maximum flow: with
     * the switch on, every face needing support (and what the locked spots add); off, the same column as before
     * (the estimate always added it, support on or off: unchanged, so a locked decision is as before); decided per spot,
     * what the plan's spots hold up and the locked spots add.
     */
    fun supportVolumeFor(m: Measure, f: PoseFacts): Double = when (f.supportEnabled) {
        true -> m.supportVolumeMm3 + m.lockedExtraVolumeMm3
        false -> m.supportVolumeMm3
        null -> m.heldVolumeMm3 + m.lockedExtraVolumeMm3
    }

    /**
     * What finishing support columns first saves candidate [m] (s, Support.firstSavingS): for each spot of its plan in
     * Coordination.Order.FIRST, the travel between the model's center and the column every layer of its height, at the
     * machine's travel speed; 0 without a travel speed (then no column is first).
     */
    fun orderSavingS(m: Measure, f: PoseFacts): Double {
        val spots = m.plan.support?.spots?.filter { it.order == Coordination.Order.FIRST } ?: return 0.0
        val h = m.plan.layerHeightMm ?: f.layerHeightMm ?: return 0.0
        return spots.sumOf { s ->
            val layers = Math.ceil(s.columnMm / h - 1e-9).toInt().coerceAtLeast(1)
            val dx = (s.region.x0 + s.region.x1) / 2 - m.sizeX / 2; val dy = (s.region.y0 + s.region.y1) / 2 - m.sizeY / 2
            Support.firstSavingS(layers, Math.hypot(dx, dy), f.travelSpeedMmS) ?: 0.0
        }
    }

    /** [ms] (one pose, one per technique) with the section-based estimates added (the expensive second pass of the search). */
    fun withSections(s: TriangleSoup, pose: Pose, ms: List<Measure>, f: PoseFacts, partTriangles: IntArray? = null): List<Measure> =
        posedWithSections(pose.apply(s), ms, f, partTriangles)

    private fun posedWithSections(p: TriangleSoup, ms: List<Measure>, f: PoseFacts, partTriangles: IntArray?): List<Measure> {
        val b = p.bounds()
        val sec = sections(p, b[2], b[5], f.overhang.widthMm, f.units, partTriangles?.takeIf { f.units != null && f.units.partUnit.size == it.size }) ?: return ms
        return ms.map { m -> m.with(sec.weakest, sec.time(m.layerTops, f, supportVolumeFor(m, f))) }
    }

    /**
     * Sections at [SECTION_SAMPLES] evenly spaced heights of one pose: what the strength and time
     * estimates read. [areas] / [perimeters]: per group of parts ([QuickUnits]; one group, the
     * whole object, without), per sample.
     */
    private class Sections(val zMin: Double, val height: Double, val areas: List<DoubleArray>, val perimeters: List<DoubleArray>, val weakest: Double?,
                           val units: QuickUnits?, val exposed: Pair<DoubleArray, DoubleArray>?) {
        /**
         * Quick time estimate for layers with these [tops], from the nearest sampled section of each
         * layer. With [PoseFacts.units], per group of parts present: the walls (outline length x
         * loops) and the sparse infill (the rest of the area x density / line width; with infill
         * combination, a share of a combined layer) each at the slower of their speed and what the
         * maximum flow allows at the layer's height; at least the minimum layer time; plus a purge
         * and the machine's own change time for each filament change (the filaments present, less
         * one); plus the top and bottom shells over the whole object. Without: the layer's volume at
         * the maximum flow, at least the minimum layer time. Plus support at the maximum flow. Null
         * when a fact is missing.
         */
        fun time(tops: DoubleArray?, f: PoseFacts, supportVolMm3: Double): Double? {
            val flow = f.flowMaxMm3s; val tMin = f.minLayerTimeS
            if (tops == null || flow == null || flow <= 0 || tMin == null) return null
            var t = 0.0
            for (i in tops.indices) {
                val bottom = if (i == 0) zMin else tops[i - 1]
                val h = tops[i] - bottom
                val k = (((bottom + tops[i]) / 2 - zMin) / height * SECTION_SAMPLES).toInt().coerceIn(0, SECTION_SAMPLES - 1)
                val q = units
                if (q == null) { t += maxOf(areas[0][k] * h / flow, tMin); continue }
                var layer = 0.0
                val spools = HashSet<String>()
                for ((g, s) in q.speeds.withIndex()) {
                    val area = areas[g][k]; val per = perimeters[g][k]
                    if (area <= 0) continue
                    spools.add(q.spools[g])
                    fun speed(v: Double, w: Double, hh: Double) = minOf(v, flow / (w * hh))
                    layer += per * (1 / speed(s.outerWallSpeed, s.wallWidthMm, h) + (s.wallLoops - 1).coerceAtLeast(0) / speed(s.innerWallSpeed, s.wallWidthMm, h))
                    val inner = maxOf(0.0, area - per * s.wallLoops * s.wallWidthMm)
                    layer += inner * s.sparseDensity / s.sparseWidthMm / speed(s.sparseSpeed, s.sparseWidthMm, h)
                }
                t += maxOf(layer, tMin) + (spools.size - 1).coerceAtLeast(0) * (q.purgeMm3 / flow + q.changeS)
            }
            // top / bottom shells: each sample's facing-up (down) area x the shell layers at its height,
            // in solid lines at the solid speed (or what the flow allows at that height)
            val ex = exposed; val w = units?.whole
            if (ex != null && w != null && w.solidSpeed > 0 && w.solidWidthMm > 0) {
                fun heightAt(z: Double): Double {
                    var i = java.util.Arrays.binarySearch(tops, z).let { if (it < 0) -it - 1 else it }
                    if (i >= tops.size) i = tops.size - 1
                    return tops[i] - (if (i == 0) zMin else tops[i - 1])
                }
                for (k in 0 until SECTION_SAMPLES) {
                    val h = heightAt(zMin + height * (k + 0.5) / SECTION_SAMPLES)
                    val v = minOf(w.solidSpeed, flow / (w.solidWidthMm * h))
                    t += (ex.first[k] * Math.ceil(w.topMm / h - 1e-9).toInt() +
                        ex.second[k] * Math.ceil(w.bottomMm / h - 1e-9).toInt()) / (w.solidWidthMm * v)
                }
            }
            return t + supportVolMm3 / flow
        }
    }

    /**
     * The weakest bond between layers of [p] as placed (mm²), as bond.area measures it: the smallest island of a sampled
     * section with material above it, islands narrower than a line ([lineMm]) squared left out; null when there is none.
     * What bond-first cooling (BondCooling) reads for an object as the plan placed it.
     */
    fun weakestBondMm2(p: TriangleSoup, lineMm: Double): Double? { val b = p.bounds(); return sections(p, b[2], b[5], lineMm)?.weakest }

    /**
     * Sections at [SECTION_SAMPLES] heights: each one's island area, and the weakest bond between
     * layers (the smallest island with material above it at the next sampled height).
     */
    private fun sections(p: TriangleSoup, zMin: Double, zMax: Double, lineMm: Double, units: QuickUnits? = null, partTriangles: IntArray? = null): Sections? {
        val height = zMax - zMin
        if (height <= 0) return null
        val zs = DoubleArray(SECTION_SAMPLES) { zMin + height * (it + 0.5) / SECTION_SAMPLES }
        val unions = plymeld.mesh.Slicer.slice(p, zs).map { layer -> if (layer.loops.isEmpty()) layer.loops else plymeld.geom.Booleans.unionPositive(layer.loops) }
        var weakest = Double.POSITIVE_INFINITY
        for (k in 0 until unions.size - 1) {
            if (unions[k + 1].isEmpty()) continue
            for ((o, hs) in plymeld.geom.Polygons.group(unions[k])) {
                val island = listOf(o) + hs
                // an "island" smaller than one line's width squared cannot be printed, so it bonds nothing (plymeld's choice):
                // such slivers are kernel noise where two parts' shells touch (5e-7 mm² on the
                // two-color Benchy), and on a log scale they would read as a bond many times weaker or stronger
                val area = o.signedAreaMm2() + hs.sumOf { it.signedAreaMm2() }
                if (area < lineMm * lineMm) continue
                if (plymeld.geom.Booleans.intersection(island, unions[k + 1]).isEmpty()) continue
                weakest = minOf(weakest, area)
            }
        }
        fun areas(us: List<List<plymeld.geom.Polygon>>) = DoubleArray(us.size) { us[it].sumOf { q -> q.signedAreaMm2() } }
        fun perimeters(us: List<List<plymeld.geom.Polygon>>) = DoubleArray(us.size) { us[it].sumOf { q -> q.perimeterMm() } }
        if (units == null) return Sections(zMin, height, listOf(areas(unions)), listOf(perimeters(unions)), if (weakest.isInfinite()) null else weakest, null, null)
        // one group: the whole object's sections are its sections
        if (units.speeds.size == 1 || partTriangles == null) return Sections(zMin, height, listOf(areas(unions)), listOf(perimeters(unions)), if (weakest.isInfinite()) null else weakest,
            QuickUnits(intArrayOf(0), listOf(units.speeds[0]), listOf(units.spools[0]), units.purgeMm3, units.changeS, units.whole), exposed(unions))
        // each group of parts sliced on its own: its own walls, infill, and filament
        val starts = IntArray(partTriangles.size).also { var a = 0; for (i in partTriangles.indices) { it[i] = a; a += partTriangles[i] } }
        val groups = units.speeds.indices.map { g ->
            val parts = partTriangles.indices.filter { units.partUnit[it] == g }
            val c = FloatArray(parts.sumOf { partTriangles[it] } * 9); var w = 0
            for (i in parts) { System.arraycopy(p.coords, starts[i] * 9, c, w, partTriangles[i] * 9); w += partTriangles[i] * 9 }
            val us = if (c.isEmpty()) List(zs.size) { emptyList() } else
                plymeld.mesh.Slicer.slice(TriangleSoup(c, c.size / 9), zs).map { layer -> if (layer.loops.isEmpty()) layer.loops else plymeld.geom.Booleans.unionPositive(layer.loops) }
            areas(us) to perimeters(us)
        }
        return Sections(zMin, height, groups.map { it.first }, groups.map { it.second }, if (weakest.isInfinite()) null else weakest, units, exposed(unions))
    }

    /**
     * Area facing up and facing down between neighboring samples of the whole object (mm²): what
     * top / bottom shells cover there. [0] = up (the part of sample k not under sample k+1; the last
     * sample all up), [1] = down (the part of sample k not over sample k-1; the first sample all down).
     * Judged on the whole object, so where two parts touch is neither.
     */
    private fun exposed(unions: List<List<plymeld.geom.Polygon>>): Pair<DoubleArray, DoubleArray> {
        fun area(ps: List<plymeld.geom.Polygon>) = ps.sumOf { it.signedAreaMm2() }
        val n = unions.size
        val up = DoubleArray(n) { k -> if (k + 1 >= n) area(unions[k]) else if (unions[k].isEmpty()) 0.0 else area(plymeld.geom.Booleans.difference(unions[k], unions[k + 1])) }
        val down = DoubleArray(n) { k -> if (k == 0) area(unions[k]) else if (unions[k].isEmpty()) 0.0 else area(plymeld.geom.Booleans.difference(unions[k], unions[k - 1])) }
        return up to down
    }

    /** Distance from (x, y) to the convex hull of pts: 0 inside or on it (monotone chain). */
    private fun hullDistance(pts: List<DoubleArray>, x: Double, y: Double): Double {
        val s = pts.sortedWith(compareBy<DoubleArray>({ it[0] }, { it[1] }))
        fun cross(o: DoubleArray, a: DoubleArray, b: DoubleArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
        val h = ArrayList<DoubleArray>()
        for (pass in 0..1) {
            val start = h.size
            for (p in if (pass == 0) s else s.asReversed()) {
                while (h.size >= start + 2 && cross(h[h.size - 2], h[h.size - 1], p) <= 0) h.removeAt(h.size - 1)
                h.add(p)
            }
            h.removeAt(h.size - 1)
        }
        if (h.size < 3) return Double.POSITIVE_INFINITY
        val q = doubleArrayOf(x, y)
        if (h.indices.all { i -> cross(h[i], h[(i + 1) % h.size], q) >= -1e-9 }) return 0.0
        return h.indices.minOf { i ->
            val a = h[i]; val b = h[(i + 1) % h.size]
            val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
            val u = if (l2 == 0.0) 0.0 else (((x - a[0]) * dx + (y - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
            Math.hypot(x - a[0] - u * dx, y - a[1] - u * dy)
        }
    }

    /**
     * What the pose decision can measure. With support off: unsupported_area (droop); support.interface and support's plastic
     * are 0 by definition, not measured. With support on: support.interface (scars) and support's plastic, by mass with the
     * support filament's density, else by volume; unsupported_area is 0 by definition, not measured. Since every measured
     * metric takes a share of its goal's weight, a measure that is 0 by definition is left out rather than given
     * a share it can never use (the support-needing area counts under quality once, either way).
     */
    fun poseMetrics(f: PoseFacts) = (when (f.supportEnabled) { true -> listOf(Metric.SUPPORT_INTERFACE); false -> listOf(Metric.UNSUPPORTED_AREA)
            // decided per spot: both can be anything, and both are measured
            null -> listOf(Metric.UNSUPPORTED_AREA, Metric.SUPPORT_INTERFACE) }) +
        listOf(Metric.HEIGHT, Metric.BED_CONTACT, Metric.TIP_OFFSET, Metric.SLENDERNESS, Metric.FIRST_LAYER_ISLANDS, Metric.SURFACE_STEP, Metric.LAYER_LINES, Metric.HEIGHT_ERROR, Metric.WEAKEST_BOND, Metric.TIME_ESTIMATE) +
        (if (f.supportEnabled == false) emptyList() else listOf(if (f.supportDensity != null) Metric.SUPPORT_MASS else Metric.SUPPORT_VOLUME)) +
        (if (ironingWeighed(f)) listOf(Metric.ROUGH_TOP_AREA) else emptyList())

    /**
     * Whether the search weighs ironing against as laid: the job locks nothing for the flat tops
     * and ironing's spacing and speed are known. Then rough_top_area is measured (0 when ironed) and ironing's pass counts
     * in time.estimate; with the surface technique locked (either way) neither changes, so a locked decision is as before.
     */
    fun ironingWeighed(f: PoseFacts): Boolean = SurfaceTechnique.IRONING in f.weighSurfaceTechniques && f.ironingSpacingMm != null && f.ironingSpeedMmS != null

    /** Why ironing is not weighed although nothing is locked for the flat tops, or null when it is (or a lock locks it). */
    fun ironingNotWeighed(f: PoseFacts): String? = if (SurfaceTechnique.IRONING !in f.weighSurfaceTechniques || ironingWeighed(f)) null
        else "ironing not weighed: its outcome needs plan.technique.ironing.spacing and .speed (${f.ironingSource}); the flat tops stay as laid"

    /**
     * The weight in effect in the pose decision: the sum of the goals' weights that have a measure here (a goal with none
     * takes no part; each scored metric's share is a part of this). Scores divided by it are per unit of weight, the same
     * at any total: what method.search.pose.keep_margin is read against (a weight is a share). 1 when nothing weighs.
     */
    fun weightInEffect(f: PoseFacts): Double = f.weights.effective(poseMetrics(f)).values.sum().takeIf { it > 0 } ?: 1.0

    /** How much of the model's surface counts toward quality in candidate [m] under the visibility map; null without a map. */
    fun seenFact(m: Measure, f: PoseFacts): String? = f.visibility?.let {
        "seen: %.0f %% of the model's %.0f mm² surface counts toward quality in this pose (%s)".format(Locale.ROOT, 100 * m.seenShare, m.surfaceMm2, it.words()) }

    /**
     * What support counts in one candidate (support costs under material AND quality, through two
     * different measures). With support on, the area that needs it gets support: its interface with the model
     * (support.interface, quality: the whole patch under each overhang held, the tops of the trees, plus the model surface
     * their columns stand on, the bottoms) and plastic (support.mass or support.volume, material); nothing prints
     * over air (unsupported_area 0). With support off, that area droops (unsupported_area, quality) and support counts
     * nothing. So the overhang area is counted under quality once, either way. Values rounded (0.01 in their unit; mass
     * from the rounded volume) so symmetric poses tie exactly.
     */
    fun supportValues(m: Measure, f: PoseFacts): Map<Metric, Double> {
        fun r(v: Double) = Math.round(v * 100) / 100.0
        // decided per spot: what the plan's spots hold up is scarred, the rest of what needs support droops;
        // the locked spots' other downward faces are scarred too (support under every face in them)
        // the scar's and droop's areas as seen (the visibility map; the columns' bases are upward faces, seen in full)
        val (droop, scar, vol) = when (f.supportEnabled) {
            true -> Triple(0.0, r(m.needsSupportVisibleMm2 + m.lockedExtraVisibleMm2 + (m.overhangs?.sumOf { it.baseMm2 } ?: 0.0) + m.lockedBaseMm2), r(m.supportVolumeMm3 + m.lockedExtraVolumeMm3))
            false -> Triple(r(m.droopSeverityMm2), 0.0, 0.0)
            null -> Triple(r(m.droopSeverityMm2 - m.heldSeverityMm2), r(m.heldVisibleMm2 + m.lockedExtraVisibleMm2 + m.heldBaseMm2 + m.lockedBaseMm2), r(m.heldVolumeMm3 + m.lockedExtraVolumeMm3))
        }
        return linkedMapOf(Metric.UNSUPPORTED_AREA to droop, Metric.SUPPORT_INTERFACE to scar) +
            if (f.supportDensity != null) mapOf(Metric.SUPPORT_MASS to vol * f.supportDensity / 1000)
            else mapOf(Metric.SUPPORT_VOLUME to vol)
    }

    /**
     * A measured candidate's value of each measure the pose decision compares (poseMetrics), those it has: the estimates
     * it was not measured for are left out. Rounded so symmetric poses tie exactly: 0.01 in each unit, the stair-step and
     * layer lines 0.0001 mm. What the decision scores, and what a technique's outcomes are (TechniqueModel.outcomes).
     */
    fun valuesOf(m: Measure, f: PoseFacts): Map<Metric, Double> {
        fun r(v: Double) = Math.round(v * 100) / 100.0
        // measures on the log scale must stay positive: rounding never turns a positive value into 0 (a pose
        // touching the bed by 0.004 mm² is usable, and its bed contact is that, not 0)
        fun p(v: Double) = r(v).let { if (it <= 0.0 && v > 0) v else it }
        val out = LinkedHashMap<Metric, Double>(supportValues(m, f))
        out[Metric.HEIGHT] = p(m.heightMm)
        out[Metric.BED_CONTACT] = p(m.contactMm2)
        // no bed contact: no tip offset to round (rounding infinity read as 9.2e16 mm, a baseline every pose beat by 100 %)
        out[Metric.TIP_OFFSET] = if (m.tipOffsetMm.isInfinite()) m.tipOffsetMm else r(m.tipOffsetMm)
        m.surfaceStepMm?.let { out[Metric.SURFACE_STEP] = Math.round(it * 10000) / 10000.0 }
        out[Metric.SLENDERNESS] = p(m.slenderness)
        out[Metric.FIRST_LAYER_ISLANDS] = m.firstLayerIslands.toDouble()
        m.heightErrorMm?.let { out[Metric.HEIGHT_ERROR] = Math.round(it * 10000) / 10000.0 }
        m.layerLinesMm?.let { v -> out[Metric.LAYER_LINES] = (Math.round(v * 10000) / 10000.0).let { if (it <= 0.0 && v > 0) v else it } }
        m.weakestBondMm2?.let { out[Metric.WEAKEST_BOND] = p(it) }
        // the surface technique of the flat tops, where weighed: ironing's pass adds to the time estimate and
        // leaves no rough flat top; as laid adds nothing and leaves them all rough
        val ironed = ironingWeighed(f) && SurfaceTechnique.IRONING in m.surfaceTechniques
        val ironS = if (ironed) SurfaceTechniques.ironingTimeS(m.flatTopMm2, f.ironingSpacingMm!!, f.ironingSpeedMmS!!) else 0.0
        // a support column finished first saves the travel to and from it every layer (Support.firstSavingS)
        m.timeEstimateS?.let { out[Metric.TIME_ESTIMATE] = p(maxOf(0.0, it + ironS - orderSavingS(m, f))) }
        if (ironingWeighed(f)) out[Metric.ROUGH_TOP_AREA] = if (ironed) 0.0 else r(m.flatTopMm2)
        val measured = poseMetrics(f)
        return measured.filter { it in out }.associateWithTo(LinkedHashMap()) { out.getValue(it) }
    }

    /** How many of the largest flat faces are tried as resting faces: a search budget, not a print parameter. */
    private const val FACE_CANDIDATES = 12

    /** Axis poses, then the largest flat faces of the mesh (grouped by normal) turned to face down. */
    fun candidates(s: TriangleSoup): List<Pose> {
        val axis = candidates()
        val groups = HashMap<Triple<Long, Long, Long>, DoubleArray>()   // quantised unit normal -> [area, nx, ny, nz]
        val c = s.coords
        for (i in 0 until s.count) {
            val o = 9 * i
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0.0) continue
            val k = Triple(Math.round(nx / len * 1000), Math.round(ny / len * 1000), Math.round(nz / len * 1000))
            val g = groups.getOrPut(k) { DoubleArray(4) }
            g[0] += len / 2; g[1] += nx / 2; g[2] += ny / 2; g[3] += nz / 2
        }
        val axisNormals = listOf(doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(1.0, 0.0, 0.0))
        val faces = groups.entries.sortedWith(compareByDescending<Map.Entry<Triple<Long, Long, Long>, DoubleArray>> { it.value[0] }.thenBy { it.key.first }.thenBy { it.key.second }.thenBy { it.key.third })
            .map { e -> val g = e.value; val l = Math.sqrt(g[1] * g[1] + g[2] * g[2] + g[3] * g[3]); doubleArrayOf(g[1] / l, g[2] / l, g[3] / l) to g[0] }
            .filter { (n, _) -> axisNormals.none { a -> a[0] * n[0] + a[1] * n[1] + a[2] * n[2] > 0.9999 } }
            .take(FACE_CANDIDATES)
        return axis + faces.map { (n, area) -> Pose("flat face n=(%.3f, %.3f, %.3f) down, %.1f mm²".format(Locale.ROOT, n[0], n[1], n[2], area), toFaceDown(n), doubleArrayOf(0.0, 0.0, 0.0)) }
    }

    /** Rotation taking unit normal n to (0, 0, -1) (Rodrigues). */
    private fun toFaceDown(n: DoubleArray): DoubleArray {
        val tx = -1.0 // target (0,0,-1)
        val ax = n[1] * tx; val ay = -n[0] * tx; val az = 0.0          // n x t
        val s = Math.sqrt(ax * ax + ay * ay + az * az); val cs = -n[2]   // n . t
        if (s < 1e-12) return if (cs > 0) Pose.IDENTITY else doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0)
        val kx = ax / s; val ky = ay / s; val kz = az / s; val v = 1 - cs
        return doubleArrayOf(
            cs + kx * kx * v, kx * ky * v - kz * s, kx * kz * v + ky * s,
            ky * kx * v + kz * s, cs + ky * ky * v, ky * kz * v - kx * s,
            kz * kx * v - ky * s, kz * ky * v + kx * s, cs + kz * kz * v)
    }

    /** The six "axis down" orientations of the model's frame; the first is the model as given (-Z down). */
    fun candidates(): List<Pose> {
        val z = doubleArrayOf(0.0, 0.0, 0.0)
        return listOf(
            Pose("as given (-Z down)", Pose.IDENTITY, z),
            Pose("+Z down (upside down)", doubleArrayOf(1.0, 0.0, 0.0, 0.0, -1.0, 0.0, 0.0, 0.0, -1.0), z),
            Pose("-Y down", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, -1.0, 0.0, 1.0, 0.0), z),
            Pose("+Y down", doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, -1.0, 0.0), z),
            Pose("-X down", doubleArrayOf(0.0, 0.0, -1.0, 0.0, 1.0, 0.0, 1.0, 0.0, 0.0), z),
            Pose("+X down", doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 0.0, -1.0, 0.0, 0.0), z),
        )
    }

    fun decide(parts: List<TriangleSoup>, f: PoseFacts, override: Pose? = null, deadlineNanos: Long? = null): Decision<Pose> =
        decideWithTechnique(parts, f, listOf(PrintTechnique.FLAT_LAYERS to null), override, null, deadlineNanos, heights = FlatLayers.Heights.ONE).pose

    /**
     * The pose and the techniques by height band, chosen together, and the coordinator's record of
     * how ([explain], for `--explain`: it decides nothing).
     */
    class Posed(val pose: Decision<Pose>, val technique: Decision<TechniquePlan>, val explain: TechniqueExplain,
                /** The surface techniques the search chose by weight, of those it was free to weigh (PoseFacts.weighSurfaceTechniques). */
                val surfaceTechniques: Set<SurfaceTechnique> = emptySet(),
                /** The weakest bond between layers of the chosen pose and plan as the search measured it (mm²); null when nothing was measured (a locked pose). */
                val weakestBondMm2: Double? = null) {
        /** How the search stopped, what the search log records. */
        val stop: TechniqueExplain.Stop get() = explain.decision.stop
    }

    /** The stop record of a decision with nothing to search. */
    private fun lockedStop(what: String) = TechniqueExplain.Stop(TechniqueExplain.StopReason.LOCKED, 0.0, 0, emptyList(), what)

    /**
     * The coordinator's record of one pose: every proposal there; the combinations each
     * candidate plan was made from; per layer height, each survey proposal alone at the volume it keeps (its sample), the
     * conflict groups and the sets the dynamic program chose.
     */
    private class Coordinated(val proposals: MutableList<Coordination.Proposal> = ArrayList(),
                              val combos: LinkedHashMap<TechniquePlan, MutableList<Coordination.Combination>> = LinkedHashMap(),
                              val singles: LinkedHashMap<Double, List<Coordination.Use>> = LinkedHashMap(),
                              val groups: LinkedHashMap<Double, List<Coordination.Group>> = LinkedHashMap(),
                              val chosen: LinkedHashMap<Double, List<Coordination.Chosen>> = LinkedHashMap(),
                              /** Support's proposals: the spot each one is, and its outcomes' differences [area, column mm³, time saved by its order] for its linearised cost. */
                              val spotOf: HashMap<Coordination.Proposal, SupportPlan.Spot> = HashMap(),
                              val supportStats: HashMap<Coordination.Proposal, DoubleArray> = HashMap(),
                              /** Each support proposal's linearised cost, once costed (for its fate in the record). */
                              val supportCost: HashMap<Coordination.Proposal, Double> = HashMap())

    /** How many of the best combinations (by the exact score) go through the full pipeline, and how many sets the dynamic program keeps per height: search effort, not a print parameter (plymeld's choice). */
    const val FINALISTS = 3

    /**
     * The heap one unit of the search's parallel work (a pose measured, a plan's layers worked out) is allowed, in turned copies
     * of the model: a 3DBenchy's units held about 11 copies each (18 units, 461 MB live, of which 74 MB its
     * copies; a class histogram in a 512 MB heap just before it ran out); 16 with a margin. Search effort, not a print
     * parameter (plymeld's choice).
     */
    const val UNIT_COPIES = 16L

    /** The share of the heap the search's parallel units may use together (1 / this); the rest stays for what it keeps. */
    const val PARALLEL_HEAP_SHARE = 2L

    /**
     * The search's parallel work shares one budget of units across its levels (the first round's poses and each
     * pose's plans, each sized to the cores, ran out of a 512 MB heap together): as many units as [PARALLEL_HEAP_SHARE] of
     * the heap holds at [UNIT_COPIES] copies of the model each, at most the cores. A level takes what is left; given one or
     * none, it works one after another, inside the unit that called it.
     */
    private object Units {
        private val inUse = java.util.concurrent.atomic.AtomicInteger(0)

        fun capacity(copyBytes: Long): Int = maxOf(1L, minOf(Runtime.getRuntime().availableProcessors().toLong(),
            Runtime.getRuntime().maxMemory() / PARALLEL_HEAP_SHARE / maxOf(1L, UNIT_COPIES * copyBytes))).toInt()

        /** Up to [want] units of [cap], or 0 when fewer than two are free (one unit is no parallelism). */
        fun take(want: Int, cap: Int): Int {
            while (true) {
                val used = inUse.get()
                val grant = minOf(want, cap - used)
                if (grant < 2) return 0
                if (inUse.compareAndSet(used, used + grant)) return grant
            }
        }

        fun give(n: Int) { if (n > 0) inUse.addAndGet(-n) }
    }

    /**
     * [n] results of [one] in index order, worked out [Units] at a time on the shared pool (each must touch only its own state
     * or synchronized caches); one after another when fewer than two units are free. [copyBytes]: one turned copy of the model.
     */
    internal fun <T> inOrder(n: Int, copyBytes: Long, one: (Int) -> T): List<T> {
        val k = if (n <= 1) 0 else Units.take(n, Units.capacity(copyBytes))
        if (k == 0) return List(n) { one(it) }
        try {
            val out = arrayOfNulls<Any?>(n)
            for (s in 0 until n step k) java.util.stream.IntStream.range(s, minOf(n, s + k)).parallel().forEach { out[it] = one(it) }
            @Suppress("UNCHECKED_CAST") return out.toList() as List<T>
        } finally {
            Units.give(k)
        }
    }

    /**
     * The proposal over the whole object for one way of laying it: flat layers propose
     * the whole object each way (they can lay any volume); another technique only where it is locked (none is proposed
     * by a survey yet).
     */
    private fun wholeProposal(k: Pair<PrintTechnique, Boolean>): Coordination.Proposal = when {
        k.first == PrintTechnique.FLAT_LAYERS && k.second -> Coordination.Proposal(TechniquePlan.FOLLOW_SHORT, k.first, TechniquePlan.FOLLOW_SHORT,
            Coordination.Volume.WHOLE, "every layer as thick as the surfaces it crosses allow, over the whole object", "flat layers (the whole object)")
        k.first == PrintTechnique.FLAT_LAYERS -> Coordination.Proposal(k.first.short, k.first, null, Coordination.Volume.WHOLE,
            "flat layers can lay any volume: one layer height over the whole object (the fallback that always exists)", "flat layers (the whole object)")
        else -> Coordination.Proposal(k.first.short, k.first, null, Coordination.Volume.WHOLE,
            "the only technique allowed for it (locked)", "the lock", locked = true)
    }

    /** A locked plan's bands as proposals (requirements, always chosen): each technique stands behind its band. */
    private fun lockedProposals(plan: TechniquePlan): List<Coordination.Proposal> = plan.portions().mapIndexed { i, p ->
        Coordination.Proposal("locked band ${i + 1}", p.technique, p.variant, p.volume,
            if (plan.curvedDepthAuto && p.technique == PrintTechnique.CURVED_LAYERS) "locked, its start worked out once the object is posed (band.<n>.depth = \"auto\")"
            else "locked by the plan (plan.object.<key>.band.<n>)", "the plan", locked = true)
    }

    /**
     * Pose × techniques, the joint search.
     * In each candidate pose the usable techniques in [techniques] (each with null, or why it cannot be used)
     * are sampled: flat layers of one height and flat layers following the surfaces over the whole object, and each band
     * flat layers' survey proposes ALONE on flat layers of the same height (each proposal on its own volume, against flat
     * layers over the same volume), at the coarse layer heights. Pose by pose, best first, the section estimates are
     * added, each proposal's contribution is linearised at the baseline, the proposals form conflict groups and a dynamic
     * program along the print order picks the best [FINALISTS] sets per height, which are measured and scored exactly
     * with the rest (the log scale; a surface technique the job did not lock weighed against as laid as a variant of every
     * candidate). Then rounds: the interval between tried heights whose bound (each measure's best at its ends) could
     * beat the winner is sampled at its midpoint, the sets re-chosen, the winner re-scored; until no unexplored interval
     * could win (short-circuited), the time limit, the per-pose budget (method.search.layer.heights), or nothing is left.
     * The best [FINALISTS] by the exact score go through the full pipeline ([predict]) when
     * one is given, and those numbers decide. Every decision records how it stopped ([Posed.stop]).
     */
    fun decideWithTechnique(parts: List<TriangleSoup>, f: PoseFacts, techniques: List<Pair<PrintTechnique, String?>>, override: Pose? = null,
                           overrideTechnique: PrintTechnique? = null, deadlineNanos: Long? = null,
                           /** A locked plan of techniques by height (a plan file's bands); wins over [overrideTechnique]. */
                           overridePlan: TechniquePlan? = null,
                           /** Flat layers' heights locked (plan.technique.flat.heights): one height throughout, or following the surfaces throughout; null = both, and the survey's mixes. */
                           heights: FlatLayers.Heights? = null,
                           /** The full pipeline for a finalist: its pose, plan, and surface techniques to a prediction; null = the quick estimates decide. */
                           predict: ((Pose, TechniquePlan, Set<SurfaceTechnique>) -> FullPrediction)? = null): Posed {
        val soup = merged(parts)
        val facts = listOf(
            "bed %.0f..%.0f x %.0f..%.0f mm, max height %.0f mm (machine)".format(Locale.ROOT, f.bedMinX, f.bedMaxX, f.bedMinY, f.bedMaxY, f.maxZ),
            "overhangs: ${f.overhang} (${f.overhangSource}); flatter faces cannot hold themselves up: " +
                listOfNotNull(f.minLayerMm, f.layerHeightMm, f.maxLayerMm).distinct().sorted()
                    .joinToString { "%.0f° at %.2f mm layers".format(Locale.ROOT, f.overhang.angleDeg(it), it) }.ifEmpty { "(no layer height known)" },
            when (f.supportEnabled) {
                true -> "support on: unsupported area gets support (intent)"; false -> "support OFF: unsupported area prints over air (intent)"
                null -> if (f.supportSpotsLocked != null) "support locked by the plan's spots (plan.object.<key>.support.spot): the faces needing support within them get it, nothing else"
                    else "support decided per overhang (plan.support.enabled unset, coordination phase 3): support's survey proposes each overhang region too steep to hold itself up, " +
                        "the weights choose between its scars and plastic (support.interface, support.mass) and the droop without it (unsupported_area); locked spots (plan.support.locked) are always supported"
            } + (if (f.lockedSpots.isEmpty()) "" else "; locked spots: " + f.lockedSpots.joinToString("; ") { "'${it.key}' (${it.words}; ${it.source})" }) +
                (if (f.supportControls.isEmpty()) "" else "; support's controls, locked at the settings: " + f.supportControls.joinToString("; ") { it.describe() }) +
                Support.minRegionWidthMm(f.supportControls).let { if (it > 0) "; an overhang region narrower than %.2f mm (a tip's diameter with the XY gap on both sides) is left to droop: no tree can stand in it".format(Locale.ROOT, it) else "" },
            "weights: ${f.weights}",
            "metrics: " + poseMetrics(f).joinToString { "${it.id} -> ${it.goal?.id ?: "(reported only)"}" },
            "weights in effect (each measure's share of its goal, job.priorities.measure.<measure>): " + f.weights.effective(poseMetrics(f)).filterValues { it > 0 }.entries.joinToString { (m, w) -> "%s %.3g".format(Locale.ROOT, m.id, w) },
            "support counts under quality (support.interface: where it meets the model, the patch under each overhang it holds and the surface its columns stand on; unsupported_area with support off: droop, never both) and under material (its plastic): " +
                (f.supportDensity?.let { "by mass, density %s g/cm³ (%s)".format(Locale.ROOT, it, f.supportDensitySource) }
                    ?: "by volume, not mass: ${f.supportDensitySource} (equal to mass up to density; exact for one filament)"),
            "quick estimates read: layer height ${f.layerHeightMm ?: "unknown"} mm, max flow ${f.flowMaxMm3s ?: "unknown"} mm³/s, minimum layer time ${f.minLayerTimeS ?: "unknown"} s (settings)",
            "mesh: ${soup.count} triangles",
            "no load facts: strength is the weakest-bond stand-in, surface technique is the stair-step estimate",
            // the visibility map: every quality measure is weighted by how much each face is seen
            f.visibility?.let { "visibility: quality's measures (surface_step, layer_lines, unsupported_area, support.interface) count each face by how much it is seen: ${it.words()}" }
                ?: "visibility: not known (no method.quality.visibility.* values); every face counts alike",
        )
        val standIns = listOf("placement: centered on the bed", "a sealed cavity's downward area counts as unsupported like any other (not weighed differently yet)",
            "bond.area: no loads known, so the thinnest bond between layers stands in for strength",
            "time.estimate and support.mass / support.volume: quick estimates for ranking poses, not the full prediction; time.estimate samples 40 sections (shells between samples are placed at the sample), leaves out travel, acceleration, and internal solid infill, and lets every layer take the full minimum layer time (the slow-down stops at its minimum speed); it overstates the saving of flat layers following the surfaces against the full prediction (13.9 % against 8.7 % on one multi-part model)",
            if (f.visibility == null) "every face counts alike toward quality: which faces are seen is not known"
            else "visibility: bed faces by a placeholder weight (method.quality.visibility.bed), undersides by one measured on a seam plate (.underside); occlusion by the object itself, by neighbors on the plate, and a view direction are not weighed yet",
            "surface_step: each face's terrace edges per mm x each edge's depth to %s (method.quality.stair_step.depth_power: %s): how fast a shallower edge fades from view is not measured".format(Locale.ROOT, f.stairStepPower, f.stairStepPowerSource.ifEmpty { "2, unset" }))
        // a locked spot is a requirement: with support switched off it cannot be met, so the decision refuses
        if (f.supportEnabled == false && f.lockedSpots.isNotEmpty()) error("plan.support.enabled = false forbids support, but support is locked on " +
            f.lockedSpots.joinToString { "spot '${it.key}' (${it.source})" } + ": a locked spot is a requirement; leave the switch unset (decided per overhang) or set it to true, or remove the spot")
        if (overrideTechnique != null) techniques.firstOrNull { it.first == overrideTechnique }?.second?.let { error("technique '${overrideTechnique.label}' cannot be used: $it") }
        overridePlan?.techniques?.forEach { s -> techniques.firstOrNull { it.first == s }?.second?.let { error("technique '${s.label}' cannot be used: $it") } }
        // curved layers are locked only (a plan's band), never searched: no survey proposes where they suit yet
        // support never lays the object (it is a side-by-side portion of a plan, SupportPlan), so it is not a way of laying it
        val usable = techniques.filter { it.second == null }.map { it.first }.filter { overrideTechnique == null || it == overrideTechnique }
            .filter { (it != PrintTechnique.CURVED_LAYERS && it != PrintTechnique.CURVED_UNDERSIDE && it != PrintTechnique.LIFTED_BRIDGE) || overrideTechnique == it }
            .filter { it != PrintTechnique.SUPPORT }
            .ifEmpty { overrideTechnique?.let { listOf(it) } ?: error("no technique is usable: " + techniques.joinToString { "${it.first.label}: ${it.second}" }) }
        // flat layers' two ways of setting heights: one height, and following the surfaces (which needs the
        // tool's thinnest and thickest layer, and no fuzzy skin), unless flat layers' heights are locked to one of them
        val flat = PrintTechnique.FLAT_LAYERS in usable
        val followWhy = FlatLayers.followBlocked(f)
        if ((flat && heights == FlatLayers.Heights.FOLLOW || overridePlan?.follows == true) && followWhy != null) error("flat layers following the surfaces cannot be used: $followWhy")
        val oneOk = flat && heights != FlatLayers.Heights.FOLLOW
        val followOk = flat && heights != FlatLayers.Heights.ONE && followWhy == null
        /** What is tried over the whole object: each usable technique, flat layers as one height and following the surfaces. */
        val kinds: List<Pair<PrintTechnique, Boolean>> = usable.flatMap { t ->
            if (t != PrintTechnique.FLAT_LAYERS) listOf(t to false) else listOfNotNull(if (oneOk) t to false else null, if (followOk) t to true else null)
        }
        /**
         * Support every plan starts with in a pose: the locked spots (requirements, interleaved until the search
         * orders them) and the plan's locked spots; null where the switch decides and nothing is locked (as before).
         * [b]: the posed model's bounds.
         */
        fun baseSupport(pose: Pose, b: DoubleArray): SupportPlan? {
            if (f.lockedSpots.isEmpty() && f.supportSpotsLocked.isNullOrEmpty()) return null
            val locked = f.lockedSpots.map { s -> val r = s.posed(pose, b)
                SupportPlan.Spot(s.key, r, true, Coordination.Order.INTERLEAVED, "the bed or the model (found once measured)", r.fromMm) }
            return SupportPlan(locked + f.supportSpotsLocked.orEmpty())
        }
        fun planOf(k: Pair<PrintTechnique, Boolean>, h: Double?) = TechniquePlan.whole(k.first, k.second).at(if (k.second) null else h)
        /** The locked spots that hold no downward face in [m]'s pose: support cannot be generated there, so the decision refuses. */
        fun refuseUnsupportable(pose: Pose, m: Measure) {
            val none = m.lockedStats.orEmpty().filter { it.areaMm2 <= 0 }
            if (none.isEmpty()) return
            error(none.joinToString("; ") { st -> "locked support spot '${st.spot.key}' (${st.spot.words}; ${st.spot.source}) holds no downward face of the model above the first layer in pose '${pose.label}' " +
                "(there it is ${st.region.words}): nothing to support, so support cannot be generated there" } + "; move or remove the spot")
        }
        /** The ways of laying the object not tried, and why: techniques that cannot be used, flat layers' heights not usable or locked away. */
        fun notTried(lockedAway: (PrintTechnique) -> Boolean): List<Alternative> = techniques.flatMap { (s, why) ->
            if (s != PrintTechnique.FLAT_LAYERS) listOfNotNull(if (why != null || lockedAway(s)) Alternative(s.label, "", why ?: "not tried: technique locked", chosen = false) else null)
            else if (why != null || lockedAway(s)) listOf(Alternative(s.label, "", why ?: "not tried: technique locked", chosen = false))
            else listOfNotNull(
                if (!oneOk) Alternative(TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, s)), "", "not tried: flat layers' heights locked to follow the surfaces (plan.technique.flat.heights)", chosen = false) else null,
                if (!followOk) Alternative(TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, s, true)), "",
                    followWhy ?: "not tried: flat layers' heights locked to one height (plan.technique.flat.heights)", chosen = false) else null)
        }
        val techniqueFacts = listOf("chosen together with the pose: flat layers of one height and following the surfaces over the whole object, and the bands flat layers' survey proposes; " +
            "each candidate plan is measured with its own layer heights",
            "layer settings: first layer ${f.firstLayerMm?.let(Control::fmt) ?: "unknown"} mm, tool's thinnest ${f.minLayerMm?.let(Control::fmt) ?: "unknown"} mm and thickest ${f.maxLayerMm?.let(Control::fmt) ?: "unknown"} mm, " +
                "flat layers' largest stair-step where they follow the surfaces ${f.maxStepMm?.let { "$it mm" } ?: "the layer height (plan.technique.flat.step.max not set)"}, " +
                "the transition between layer heights " + (f.heightRatio?.takeIf { it > 1.0 }?.let { "at most ${Control.fmt(it)}x a layer to the next (plan.technique.flat.height_ratio)" } ?: "none: a jump at a band's boundary (plan.technique.flat.height_ratio 0 or unset)"),
            "flat layers' layer height: " + (f.layerHeightControl?.describe() ?: "none: the layer height is the settings' (plan.technique.flat.layer.height ${f.layerHeightMm ?: "unknown"} mm), not decided") +
                (heights?.let { "; heights locked: ${it.id} (plan.technique.flat.heights)" } ?: ""))
        val techniqueStandIns = listOf("regions are height bands only: side-by-side regions (a hidden core under a visible shell) are not built yet",
            "survey built: flat layers' (the bands where following the surfaces changes the layer count); one height takes what it does not claim")
        // the coordinator's record, from what is measured below: it decides nothing
        val explainScreen = techniques.flatMap { (s, why) ->
            when {
                s == PrintTechnique.FLAT_LAYERS && why == null && s in usable -> listOf(
                    TechniqueExplain.Screen(TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, s)), oneOk,
                        if (oneOk) null else "not tried: flat layers' heights locked to follow the surfaces (plan.technique.flat.heights)"),
                    TechniqueExplain.Screen(TechniquePlan.labelOf(TechniquePlan.Banded(0.0, 1.0, s, true)), followOk,
                        if (followOk) null else followWhy ?: "not tried: flat layers' heights locked to one height (plan.technique.flat.heights)"))
                why != null -> listOf(TechniqueExplain.Screen(s.label, false, why))
                s in usable -> listOf(TechniqueExplain.Screen(s.label, true, null))
                overridePlan?.techniques?.contains(s) == true -> listOf(TechniqueExplain.Screen(s.label, true, "locked by the plan's band"))
                overrideTechnique != null -> listOf(TechniqueExplain.Screen(s.label, false, "not tried: technique locked"))
                else -> listOf(TechniqueExplain.Screen(s.label, false, "locked only: no survey proposes where it suits yet (a plan's band locks it)"))
            }
        }
        val explainControls = listOfNotNull(f.layerHeightControl?.describe() ?: f.layerHeightMm?.let { "layer.height: the settings' (plan.technique.flat.layer.height $it mm), not decided" },
            FlatLayers.maxStep(f)?.takeIf { followOk }?.describe(), heights?.let { "heights locked: ${it.id} (plan.technique.flat.heights)" })
        val explainLocks = listOfNotNull(override?.let { "pose locked: '${it.label}'" }, overrideTechnique?.let { "technique locked: ${it.label}" },
            overridePlan?.let { "the plan's bands locked (requirements): ${it.label}" }, heights?.let { "flat layers' heights locked: ${it.id}" },
            f.layerHeightControl?.takeIf { it.locked }?.let { "locked: ${it.describe()}" },
            f.supportEnabled?.let { "support locked ${if (it) "on" else "off"}: plan.support.enabled = $it" },
            f.supportSpotsLocked?.let { s -> "support spots locked by the plan (requirements): " + s.joinToString("; ") { Support.words(it) } }) +
            f.lockedSpots.map { "locked support (a requirement, never weighed away): spot '${it.key}', ${it.words} (${it.source})" }
        /** Support's part of the record: how it was decided, each locked spot as found in the chosen pose, each column's order and why. */
        fun supportOut(m: Measure): TechniqueExplain.SupportOut {
            val spots = m.plan.support?.spots.orEmpty()
            val how = when {
                f.supportEnabled == true -> "locked on (plan.support.enabled = true): support under every face too steep to hold itself up" +
                    (if (m.lockedExtraMm2 > 0) ", and under every downward face of the locked spots (%.1f mm² more)".format(Locale.ROOT, m.lockedExtraMm2) else "")
                f.supportEnabled == false -> "locked off (plan.support.enabled = false): nothing is supported, %.1f mm² prints over air".format(Locale.ROOT, m.needsSupportMm2)
                f.supportSpotsLocked != null -> "locked by the plan's ${f.supportSpotsLocked.size} spot(s): %.1f mm² of the %.1f mm² needing support is held up".format(Locale.ROOT, m.heldMm2, m.needsSupportMm2)
                else -> "decided per overhang (plan.support.enabled unset): ${m.overhangs?.size ?: 0} overhang region(s) under the winner's layers, " +
                    "${spots.count { !it.locked }} supported by choice and ${spots.count { it.locked }} locked; %.1f mm² of the %.1f mm² needing support is held up, %.1f mm² prints over air; ".format(Locale.ROOT, m.heldMm2, m.needsSupportMm2, m.needsSupportMm2 - m.heldMm2) +
                    "each region weighed on its scars (support.interface) and plastic (${if (f.supportDensity != null) "support.mass" else "support.volume"}) and time against the droop it prevents (unsupported_area), at the weights' exchange rate"
            }
            val locked = m.lockedStats.orEmpty().map { st -> "'${st.spot.key}' (${st.spot.words}): in this pose ${st.region.words}, %.1f mm² of downward faces, standing on %s, a column %.2f mm tall".format(Locale.ROOT, st.areaMm2, st.standsOn, st.columnMm) }
            val orders = spots.map { s -> "${if (s.locked) "locked spot '${s.id}'" else s.id}: ${s.order.id} (${s.order.words}): ${s.why}" }
            return TechniqueExplain.SupportOut(how, locked, orders)
        }
        fun chosenWhole(p: Coordination.Proposal, why: String) = TechniqueExplain.ProposalOut(p, TechniqueExplain.Fate.CHOSEN, why)
        /**
         * A locked decision's plan with its support: the locked and locked spots, each locked spot checked to hold
         * something in the locked pose (nothing is searched here, so the columns are interleaved), with the measure the
         * record reads.
         */
        fun lockedWithSupport(plan: TechniquePlan): Pair<TechniquePlan, Measure?> {
            val b = override!!.apply(soup).bounds()
            val base = baseSupport(override, b) ?: return plan to null
            val m = measureAll(soup, override, f.overhang, null, Cavities.manifoldShells(soup).takeIf { ids -> ids.toSet().size > 1 }?.let { Cavities.coveredShare(soup, it) }, f,
                withSections = false, plansFor = { _, _ -> listOf(plan.withSupport(base)) }, partTriangles = parts.map { it.count }.toIntArray()).single()
            refuseUnsupportable(override, m)
            val stats = m.lockedStats.orEmpty().associateBy { it.spot.key }
            val spots = base.spots.map { s -> stats[s.id]?.let { st -> SupportPlan.Spot(s.id, st.region, true, Coordination.Order.INTERLEAVED, st.standsOn, st.columnMm, st.standsOnMm,
                "nothing searched (the pose and the plan are locked): the safe order") } ?: s }
            val withSupport = plan.withSupport(SupportPlan(spots))
            return withSupport to Measure(m.contactMm2, m.needsSupportMm2, m.heightMm, m.sizeX, m.sizeY, m.tipOffsetMm, m.sealedMm2, m.surfaceStepMm, null, null, m.supportVolumeMm3, withSupport, m.layerTops,
                m.layerLinesMm, m.flatTopMm2, emptySet(), m.heldMm2, m.heldVolumeMm3, m.lockedExtraMm2, m.lockedExtraVolumeMm3, m.overhangs, m.lockedStats, m.heldBaseMm2, m.lockedBaseMm2, m.droopSeverityMm2, m.heldSeverityMm2, m.slenderness, m.firstLayerIslands, m.heightErrorMm,
                m.surfaceMm2, m.visibleMm2, m.needsSupportVisibleMm2, m.heldVisibleMm2, m.lockedExtraVisibleMm2)
        }
        fun lockedExplain(plan: TechniquePlan, proposals: List<TechniqueExplain.ProposalOut>, why: String, m: Measure?) = TechniqueExplain("", override!!.label,
            proposals, plan.portions(), Coordination.fill(Coordination.Volume.WHOLE, proposals.filter { it.fate != TechniqueExplain.Fate.REJECTED }.map { it.now }),
            explainScreen, explainControls, null, emptyList(), why, null, emptyList(), 0,
            TechniqueExplain.DecisionOut("pose '${override.label}' · ${plan.label}", null, null, emptyList(), explainLocks, emptyList(), lockedStop(why), support = m?.let(::supportOut)))
        /** A locked plan's support spots as proposals (requirements). */
        fun spotProposals(plan: TechniquePlan): List<TechniqueExplain.ProposalOut> = plan.support?.spots.orEmpty().map { s ->
            chosenWhole(Coordination.Proposal(if (s.locked) "locked spot '${s.id}'" else s.id, PrintTechnique.SUPPORT, null, Coordination.Volume.Under(s.region, s.standsOn),
                if (s.locked) "locked by the user (plan.support.locked): every downward face in it gets support" else "locked by the plan (plan.object.<key>.support.spot)", "the user's requirement", locked = true, order = s.order),
                "a requirement: a locked proposal is always in the chosen set")
        }
        // A locked pose with a locked plan: nothing to measure
        if (override != null && overridePlan != null) {
            val (plan, pm) = lockedWithSupport(overridePlan)
            return Posed(Decision("pose", place(soup, override, f), "pose '${override.label}'", facts, overridden = true),
                Decision("technique", plan, plan.label, techniqueFacts, emptyList(), techniqueStandIns, overridden = true),
                lockedExplain(plan, lockedProposals(overridePlan).map { chosenWhole(it, "a requirement: a locked proposal is always in the chosen set") } + spotProposals(plan) +
                    kinds.map { TechniqueExplain.ProposalOut(wholeProposal(it), TechniqueExplain.Fate.REJECTED, "dropped: the plan's locked bands take the object (requirements)") },
                    "the pose and the plan are locked: nothing to choose", pm))
        }
        // A locked pose with one way of laying it and at most one layer height: nothing to measure
        if (override != null && kinds.size == 1 && (f.layerHeightCandidates?.size ?: 1) <= 1) {
            val st = kinds.single()
            val (plan, pm) = lockedWithSupport(planOf(st, f.layerHeightCandidates?.singleOrNull()))
            return Posed(Decision("pose", place(soup, override, f), "pose '${override.label}'", facts, overridden = true),
                Decision("technique", plan, TechniquePlan.labelOf(plan.bands[0]) + (if (overrideTechnique != null || heights != null) "" else " (the only usable technique)"), techniqueFacts,
                    listOf(Alternative(TechniquePlan.labelOf(plan.bands[0]), "", null, chosen = true)) + notTried { overrideTechnique != null && it != overrideTechnique }, techniqueStandIns,
                    overridden = overrideTechnique != null || heights != null),
                lockedExplain(plan, listOf(chosenWhole(wholeProposal(st), "the only way allowed here, at one point of its controls: nothing to compare")) + spotProposals(plan),
                    "the pose is locked and only one way, at one point of its controls, is allowed: nothing to compare", pm))
        }
        // the survey, per pose: flat layers' proposals (at the first height sampled, for the decision's log)
        // concurrent: round 1 measures several poses at once, each writing only its own pose's entries
        val proposalsOf = java.util.concurrent.ConcurrentHashMap<Pose, List<Proposal>>()
        val wholes = kinds.map(::wholeProposal)
        val coordinated = java.util.concurrent.ConcurrentHashMap<Pose, Coordinated>()
        /** The plan a set of uses makes: flat layers following the surfaces in those bands, one height [h] elsewhere. */
        fun planOfUses(uses: List<Coordination.Use>, h: Double?): TechniquePlan =
            TechniquePlan.mixed(uses.map { (it.volume as Coordination.Volume.ZBand).let { v -> v.fromMm to v.toMm } }).at(h)
        /**
         * The plans to measure in [pose] at each height of [hs] (null = the settings' layer height): under a locked plan,
         * only it; else the wholes, and each proposal of flat layers' survey at that height ALONE on flat layers of that
         * height (a proposal sampled on its own volume, against flat layers over the same volume: the whole).
         * The coordinator's record of the pose ([Coordinated]) gains the proposals and what made each plan (two can make
         * the same plan: a survey band over the whole object is the whole).
         */
        fun plansFor(pose: Pose, hs: List<Double?>): (TriangleSoup, plymeld.mesh.LayerPlan.Limits?) -> List<TechniquePlan> = if (overridePlan != null) { posed, _ ->
            val locked = lockedProposals(overridePlan)
            val plan = overridePlan.withSupport(baseSupport(pose, posed.bounds()))
            coordinated.getOrPut(pose) { Coordinated() }.let { c -> c.proposals.clear(); c.proposals += locked + wholes
                c.combos[plan] = mutableListOf(Coordination.Combination(locked.map { Coordination.Use(it, it.volume) })) }
            listOf(plan)
        } else { posed, lim ->
            val plans = ArrayList<TechniquePlan>()
            val base = baseSupport(pose, posed.bounds())
            val co = coordinated.getOrPut(pose) { Coordinated().also { it.proposals += wholes } }
            for (hc in hs) {
                val h = hc ?: f.layerHeightMm; val max = f.maxLayerMm
                val proposals = if (oneOk && followOk && lim != null && h != null && max != null) Surveys.flat(lim, h, max) else emptyList()
                proposalsOf.putIfAbsent(pose, proposals)
                val survey = proposals.mapIndexed { i, p ->
                    Coordination.Proposal("survey %s #%d".format(Locale.ROOT, Control.fmt(h!!), i + 1), PrintTechnique.FLAT_LAYERS, TechniquePlan.FOLLOW_SHORT,
                        Coordination.Volume.ZBand(p.lo, p.hi), (if (p.suited) "" else "might be suited: ") + p.reason,
                        "flat layers' survey, against layers of one height %s mm".format(Locale.ROOT, Control.fmt(h)), Math.abs(p.layersChange),
                        trimNote = "flat layers move its edges onto their own layer grid (the first layer plus whole layers of one height), " +
                            "so the bands of one height around it keep their height, and run a band that reaches the top to the top")
                }
                co.proposals += survey
                // flat layers' own trim, which they allow anywhere (the coordinator does not know layers): a band reaching
                // the model's top runs to the top (no empty band above it); other boundaries snap to the one-height layer
                // grid (first layer + whole layer heights), so bands of one height keep exactly the layer height
                // (evened-out 0.195 mm layers on a test model overlapped inner walls and sparse infill more than 0.2 mm ones);
                // nothing is left of a band shorter than a layer
                fun trim(p: Coordination.Proposal): Coordination.Volume? {
                    val v = p.volume as Coordination.Volume.ZBand
                    val top = lim!!.zMax - lim.zMin
                    val first = f.firstLayerMm ?: h!!
                    fun grid(z: Double) = if (z <= 0.0) 0.0 else first + Math.round((z - first) / h!!) * h
                    val lo = grid(v.fromMm); val hi = if (v.toMm >= top - 1e-6) Double.POSITIVE_INFINITY else grid(v.toMm)
                    return if (hi > lo + 1e-9 && lo < top) Coordination.Volume.ZBand(lo, hi) else null
                }
                for (w in wholes) {
                    val plan = planOf(w.technique to (w.variant == TechniquePlan.FOLLOW_SHORT), hc).withSupport(base)
                    plans += plan; co.combos.getOrPut(plan) { ArrayList() } += Coordination.Combination(listOf(Coordination.Use(w, w.volume)))
                }
                val uses = survey.mapNotNull { p -> trim(p)?.let { Coordination.use(p, it) } }
                for (u in uses) {
                    val plan = planOfUses(listOf(u), hc).withSupport(base)
                    plans += plan; co.combos.getOrPut(plan) { ArrayList() } += Coordination.Combination(listOf(u))
                }
                if (uses.isNotEmpty()) co.singles[h!!] = uses
            }
            plans.distinct()
        }
        /** The plan [uses] make at height [h] with support beside it: the bands' plan, and the support uses' spots (the locked ones with the order chosen for them) over [base]'s locked spots. */
        fun planWithSupport(co: Coordinated, uses: List<Coordination.Use>, h: Double?, base: SupportPlan?, bands: TechniquePlan = planOfUses(uses.filter { it.volume is Coordination.Volume.ZBand }, h)): TechniquePlan {
            val spots = uses.filter { it.volume is Coordination.Volume.Under }.map { co.spotOf.getValue(it.proposal) }
            val sp = if (base == null && spots.isEmpty()) null else SupportPlan(spots.filter { it.locked } + f.supportSpotsLocked.orEmpty() + spots.filter { !it.locked })
            return bands.withSupport(sp)
        }
        /**
         * Support's proposals in [pose] at height [h]: the air under each overhang region of
         * [sample] (the flat plan's survey there, [OverhangRegion]) and under each locked spot, each in the print orders
         * the head's clearance allows (Support.firstBlocked): interleaved, and finished first where it saves travel. A
         * locked spot's proposals are requirements sharing one requirement, so one order of it is always chosen. Their
         * outcomes' differences are kept for the linearised cost ([Coordinated.supportStats]).
         */
        // the cantilever check: a region's reach over air from its lowest layer, cached by pose, region, and layer height
        val posedOf0 = HashMap<String, Pair<TriangleSoup, DoubleArray>>()
        val reachOf = HashMap<String, Cantilever.Reach?>()
        // the reach by pose, region, and layer height, the height in 0.05 mm buckets: the search tries up to nine heights per pose,
        // and a region's lowest layer reaches the same over air to within a fraction of a layer (a cut per bucket, not per height)
        fun reachBucket(layerMm: Double) = Math.max(0.05, Math.round(layerMm / 0.05) * 0.05)
        fun reachKey(pose: Pose, r: OverhangRegion, layerMm: Double) = "${pose.label}|${r.index}|${Math.round(reachBucket(layerMm) * 1e4)}"
        /** The reach of every region of [regions] in [pose] at [layerMm] not yet known, cut in ONE sweep of the posed model (a Benchy has 176 regions). */
        fun reachAll(pose: Pose, regions: List<OverhangRegion>, layerMm0: Double) {
            val layerMm = reachBucket(layerMm0)
            val todo = regions.filter { reachKey(pose, it, layerMm) !in reachOf }
            if (todo.isEmpty()) return
            val (posed, b) = posedOf0.getOrPut(pose.label) { pose.apply(soup).let { it to it.bounds() } }
            val cuttable = todo.filter { layerMm > 0 && it.region.fromMm >= layerMm - 1e-9 }
            for (r in todo - cuttable.toSet()) reachOf[reachKey(pose, r, layerMm)] = null
            if (cuttable.isEmpty()) return
            val zs = cuttable.flatMap { listOf(Cantilever.belowZ(b, it.region, layerMm), Cantilever.layerZ(b, it.region, layerMm)) }.distinct().sorted()
            val cut = plymeld.mesh.Slicer.slice(posed, zs.toDoubleArray())
            val loopsAt = zs.indices.associate { zs[it] to cut[it].loops }
            // the areas (edges on a grid) are built two at a time and dropped: a Benchy pose cuts hundreds of planes
            for (r in cuttable) reachOf[reachKey(pose, r, layerMm)] =
                Cantilever.reach(Cantilever.Area(loopsAt.getValue(Cantilever.layerZ(b, r.region, layerMm))), Cantilever.Area(loopsAt.getValue(Cantilever.belowZ(b, r.region, layerMm))), b, r.region)
        }
        fun cantileverReach(pose: Pose, r: OverhangRegion, layerMm: Double): Cantilever.Reach? {
            reachAll(pose, listOf(r), layerMm)
            return reachOf[reachKey(pose, r, layerMm)]
        }
        fun isCantilever(reach: Cantilever.Reach?) = reach != null && reach.reachMm > f.overhang.widthMm + 1e-9 &&
            (!reach.bridged || (f.bridgeMaxSpanMm != null && 2 * reach.reachMm > f.bridgeMaxSpanMm + 1e-9))
        /** Why nothing but support can lay a region whose reach [isCantilever]: a cantilever, or a bridge past the filament's span. */
        fun cantileverWords(reach: Cantilever.Reach) = if (!reach.bridged)
            "a cantilever: its lowest layer reaches %.1f mm over air, anchored on one side only, which nothing but support can lay".format(Locale.ROOT, reach.reachMm)
            else "a bridge spanning about %.0f mm, past the filament's bridging limit of %.0f mm (hardware.filament.bridge.span.max): nothing holds its middle".format(Locale.ROOT, 2 * reach.reachMm, f.bridgeMaxSpanMm!!)
        fun supportProposals(pose: Pose, h: Double?, sample: Measure, under: String): List<Coordination.Use> {
            if (!f.supportSearched && f.lockedSpots.isEmpty()) return emptyList()
            val co = coordinated.getValue(pose)
            val hh = h ?: f.layerHeightMm
            val out = ArrayList<Coordination.Use>()
            fun add(id: String, region: Coordination.Volume.Region, locked: Boolean, standsOn: String, standsOnMm: Double?, columnMm: Double, area: Double, vol: Double, base: Double, sev: Double, reason: String, by: String, requirement: String?) {
                val blocked = Support.firstBlocked(columnMm, f.nozzleHeightMm, f.nozzleHeightSource)
                val layers = hh?.let { Math.ceil(columnMm / it - 1e-9).toInt().coerceAtLeast(1) } ?: 1
                val dx = (region.x0 + region.x1) / 2 - sample.sizeX / 2; val dy = (region.y0 + region.y1) / 2 - sample.sizeY / 2
                val saving = Support.firstSavingS(layers, Math.hypot(dx, dy), f.travelSpeedMmS)
                val firstWhy = "finishing it first would save about %.1f s of travel (%d layers, %.1f mm from the model's center, at %s mm/s)".format(Locale.ROOT, saving ?: 0.0, layers, Math.hypot(dx, dy), f.travelSpeedMmS?.let { Control.fmt(it) } ?: "?")
                val interleavedWhy = blocked ?: if (saving == null) "the travel speed is not known (hardware.machine.motion.travel.speed): finishing it first has nothing to weigh"
                    else "$firstWhy, which the weights' exchange rate did not take (speed has no weight here, or the saving rounds to nothing)"
                val orders = listOfNotNull(Coordination.Order.INTERLEAVED to interleavedWhy, if (blocked == null && saving != null && saving > 0) Coordination.Order.FIRST to firstWhy else null)
                for ((order, why) in orders) {
                    val p = Coordination.Proposal("$id, ${order.id}" + (if (under.isEmpty()) "" else " ($under)"), PrintTechnique.SUPPORT, null, Coordination.Volume.Under(region, standsOn),
                        reason + "; ${order.words}" + (if (order == Coordination.Order.FIRST) "" else blocked?.let { ": $it" } ?: ""), by, rank = area, locked = locked, requirement = requirement, order = order)
                    co.proposals += p
                    co.spotOf[p] = SupportPlan.Spot(if (locked) id.removePrefix("locked spot '").removeSuffix("'") else id, region, locked, order, standsOn, columnMm, standsOnMm, why)
                    co.supportStats[p] = doubleArrayOf(area, vol, if (order == Coordination.Order.FIRST) saving ?: 0.0 else 0.0, base, sev)
                    out += Coordination.Use(p, p.volume)
                }
            }
            for (st in sample.lockedStats.orEmpty()) if (st.areaMm2 > 0) add("locked spot '${st.spot.key}'", st.region, true, st.standsOn, st.standsOnMm, st.columnMm, st.areaMm2, st.columnMm3, st.baseMm2, 0.0,
                "locked by the user (${st.spot.source}): every downward face in it gets support, %.1f mm² here".format(Locale.ROOT, st.areaMm2), "the user's requirement (plan.support.locked)", "locked:${st.spot.key}")
            val hReach = hh ?: f.layerHeightMm ?: f.firstLayerMm ?: 0.0
            if (f.supportSearched) reachAll(pose, sample.overhangs.orEmpty(), hReach)
            if (f.supportSearched) for (r in sample.overhangs.orEmpty()) {
                // a cantilever: nothing but support can lay it, so its support is a requirement, whatever the weights
                val reach = cantileverReach(pose, r, hReach)
                val cant = isCantilever(reach)
                add("${r.id} at ${hh?.let { Control.fmt(it) } ?: "the settings'"} mm", r.region, false, r.standsOn, r.standsOnMm, r.columnMm, r.areaMm2, r.columnMm3, r.baseMm2, r.severityMm2,
                    r.words + (if (cant) "; " + cantileverWords(reach!!) + ": required" else ""),
                    "support's survey: the faces needing support under flat layers of ${hh?.let { Control.fmt(it) + " mm" } ?: "the settings' height"}, clustered where they share a vertex",
                    if (cant) "cantilever:${r.id}" else null)
            }
            return out
        }
        // a locked pose skips the sealed-cavity labels (they only annotate the report); what one part
        // covers of another still counts, or buried faces would read as overhangs (support and time
        // estimates 30x high on a large model)
        val cavity = if (override != null) null else Cavities.mask(soup)
        val covered = Cavities.manifoldShells(soup).takeIf { ids -> ids.toSet().size > 1 }?.let { Cavities.coveredShare(soup, it) }
        // Best effort within the time limit, coarse to fine (the joint search): round 1 measures every pose's
        // geometry under the wholes and each
        // proposal alone at the coarse heights (cheap); then, best-first until the deadline, a pose gets the section
        // estimates (strength, time), its proposals' contributions, the dynamic program's sets, and those measured.
        // The choice is made among the candidates with every estimate, so like is compared with like.
        val t0 = System.nanoTime()
        fun late() = deadlineNanos != null && System.nanoTime() > deadlineNanos
        val all = if (override != null) listOf(override) else candidates(soup)
        val partTriangles = parts.map { it.count }.toIntArray()
        val scored = ArrayList<Pair<Pose, Measure>>()
        /** The layer heights sampled in each pose, in order (null = the settings'): the search's own record. */
        val heightsTried = LinkedHashMap<Pose, MutableList<Double?>>()
        val coarseHeights: List<Double?> = f.layerHeightCandidates ?: listOf(null)
        var posesMeasured = 0
        // the poses measured several at a time (one after another this was most of a large model's plan), as many as the
        // search's parallel budget allows ([Units]; each pose's plans then take what is left), in batches on the shared pool (a
        // pool of its own per search, sized to the cores, oversubscribed the machine wherever searches already ran side by
        // side); kept in their order up to the first the deadline skipped, as one after another (the first is always measured)
        val roundOne = inOrder(all.size, soup.count.toLong() * 36) { i ->
            if (i > 0 && late()) null
            else measureAll(soup, all[i], f.overhang, cavity, covered, f, withSections = false, plansFor = plansFor(all[i], coarseHeights), partTriangles = partTriangles)
        }
        for ((i, cand) in all.withIndex()) {
            val ms = roundOne[i] ?: break
            heightsTried[cand] = coarseHeights.toMutableList()
            ms.forEach { scored.add(cand to it) }
            posesMeasured++
        }
        val t1 = System.nanoTime()
        val notReached = all.drop(posesMeasured)
        val several = scored.map { it.second.plan }.distinct().size > 1
        val weighIroning = ironingWeighed(f)
        /** The surface technique part of a candidate's label, where the flat tops' surface technique is weighed. */
        fun surfaceTechniqueWords(m: Measure) = if (!weighIroning) "" else if (SurfaceTechnique.IRONING in m.surfaceTechniques) " · ironing" else " · flat tops as laid"
        fun label(p: Pose, m: Measure) = (if (several) "${p.label} · ${m.plan.label}" else p.label) + surfaceTechniqueWords(m)
        fun sealedNote(m: Measure) = if (m.sealedMm2 > 0.005) " (%.1f mm² of it inside a sealed cavity)".format(Locale.ROOT, m.sealedMm2) else ""
        // the hard check before the weights: an overhang region whose lowest layer
        // reaches past a line width over air and is anchored on one side only (a cantilever, Cantilever.reach) that no
        // support spot holds cannot be laid, whatever its score. Only where the search surveys the overhangs (support
        // decided per overhang); locked on, everything is held; locked off, the plan says what prints over air. Applied to
        // the FINAL set (rejectedFinal): the coarse first pass carries no support yet (the joint search proposes it per
        // overhang in the rounds), so there it only names the rejection in the alternatives.
        fun layerAtRegion(m: Measure, r: OverhangRegion, zMin: Double): Double {
            val t = m.layerTops
            if (t != null && t.isNotEmpty()) {
                val z = zMin + r.region.fromMm + 1e-6
                var i = java.util.Arrays.binarySearch(t, z).let { if (it < 0) -it - 1 else it }
                if (i >= t.size) i = t.size - 1
                return t[i] - (if (i == 0) zMin else t[i - 1])
            }
            return m.plan.layerHeightMm ?: f.layerHeightMm ?: f.firstLayerMm ?: 0.0
        }
        fun cantilever(p: Pose, m: Measure): String? {
            val regions = m.overhangs ?: return null
            val b = posedOf0.getOrPut(p.label) { p.apply(soup).let { it to it.bounds() } }.second
            val held = (m.plan.support?.chosen.orEmpty() + f.supportSpotsLocked.orEmpty()).map { it.region }
            val boxes = m.lockedStats.orEmpty().map { it.region }
            val unheld = regions.filter { r -> held.none { it == r.region } && boxes.none { it.overlaps(r.region) } }
            // one sweep per layer height for every unheld region of this candidate
            for ((h, rs) in unheld.groupBy { Math.round(layerAtRegion(m, it, b[2]) * 1e4) }) reachAll(p, rs, h / 1e4)
            for (r in unheld) {
                val reach = cantileverReach(p, r, Math.round(layerAtRegion(m, r, b[2]) * 1e4) / 1e4) ?: continue
                if (isCantilever(reach))
                    return ("${r.id} (${r.region.words}) reaches %.1f mm over air from its lowest layer (at %.2f, %.2f): " + cantileverWords(reach).replace("a cantilever:", "a cantilever no technique holds up:") +
                        "; a line more than one line width (%.2f mm) past the layer below touches nothing (support would hold it; a bridge needs anchors on two sides within the filament's span)")
                        .format(Locale.ROOT, reach.reachMm, reach.x, reach.y, f.overhang.widthMm)
            }
            return null
        }
        fun rejected(p: Pose, m: Measure): String? = when {
            m.sizeX > f.bedMaxX - f.bedMinX || m.sizeY > f.bedMaxY - f.bedMinY -> "footprint %.1f x %.1f mm exceeds the bed".format(Locale.ROOT, m.sizeX, m.sizeY)
            m.heightMm > f.maxZ -> "height %.1f mm exceeds %.0f".format(Locale.ROOT, m.heightMm, f.maxZ)
            m.contactMm2 <= 0.0 -> "no flat face on the bed: nothing to adhere"
            // a locked spot is a requirement: a pose in which it holds nothing to support cannot meet it
            m.lockedStats.orEmpty().any { it.areaMm2 <= 0 } -> m.lockedStats!!.filter { it.areaMm2 <= 0 }.joinToString("; ") { st ->
                "locked support spot '${st.spot.key}' (${st.spot.words}; ${st.spot.source}) holds no downward face of the model above the first layer in this pose (there it is ${st.region.words}): nothing to support, so support cannot be generated there" }
            else -> null
        }
        fun rejectedFinal(p: Pose, m: Measure): String? = rejected(p, m) ?: cantilever(p, m)
        val ok = scored.filter { rejected(it.first, it.second) == null }
        if (ok.isEmpty()) error("no candidate pose can be used: " + scored.distinctBy { it.first }.joinToString("; ") { "${it.first.label}: ${rejected(it.first, it.second)}" } +
            (if (f.lockedSpots.isNotEmpty()) "; move or remove the locked spot" else ""))
        // weighted, normalized metrics; an estimate counts only when every candidate being compared has it
        fun valuesFor(set: List<Pair<Pose, Measure>>): Map<Metric, (Pair<Pose, Measure>) -> Double> {
            val has = set.map { valuesOf(it.second, f).keys }.reduce { a, b -> a intersect b }
            return poseMetrics(f).filter { it in has }.associateWith { k -> { p: Pair<Pose, Measure> -> valuesOf(p.second, f).getValue(k) } }
        }
        val zero = zeroScales(soup, f)
        // The baseline: flat layers at method.search.baseline.layer.height_share, measured like any candidate. ONE per
        // decision, so every candidate is scored on the same scale: where poses compete, in the model's as-given pose
        // (plymeld's choice); where the pose is locked, in that pose.
        val basePose = override ?: candidates()[0]
        // the baseline carries the requirements too (the locked and locked spots), so their cost is common to every candidate
        val baseline: Pair<Pose, Measure>? = f.baselineLayerMm?.let { h ->
            basePose to measureAll(soup, basePose, f.overhang, cavity, covered, f, withSections = true,
                plansFor = { posed, _ -> listOf(FlatLayers.plan(h).withSupport(baseSupport(basePose, posed.bounds()))) }, partTriangles = partTriangles).single()
        }
        /** The baseline's value of each metric in [vals] it has (a metric it lacks is scored from 0, as a zero baseline). */
        fun baselineOf(vals: Map<Metric, (Pair<Pose, Measure>) -> Double>): Map<Metric, Double>? = baseline?.let { b ->
            val has = valuesFor(listOf(b)).keys
            vals.filterKeys { it in has }.mapValues { it.value(b) }
        }
        val firstPass = Scoring.rank(ok, valuesFor(ok), f.weights, zero, baselineOf(valuesFor(ok)))
        val baseAll: Map<Metric, Double>? = baseline?.let { valuesOf(it.second, f) }
        val measuredMetrics = poseMetrics(f)
        // the section estimates, computed once per pose (the expensive pass) and attached to every candidate measured there
        val posedOf = HashMap<Pose, TriangleSoup>()
        val sectionsOf = HashMap<Pose, Sections?>()
        fun sectioned(pose: Pose, ms: List<Measure>): List<Measure> {
            val p = posedOf.getOrPut(pose) { pose.apply(soup) }
            val sec = sectionsOf.getOrPut(pose) { val b = p.bounds(); sections(p, b[2], b[5], f.overhang.widthMm, f.units, partTriangles.takeIf { f.units != null && f.units.partUnit.size == it.size }) }
            return if (sec == null) ms else ms.map { m -> m.with(sec.weakest, sec.time(m.layerTops, f, supportVolumeFor(m, f))) }
        }
        /** Every candidate in [ms] also laid with ironing, where the flat tops' surface technique is weighed: the exact score decides between the variants. */
        fun withSurfaceTechniqueVariants(ms: List<Measure>): List<Measure> = if (!weighIroning) ms else ms.flatMap { m -> listOf(m, m.withSurfaceTechniques(setOf(SurfaceTechnique.IRONING))) }
        val detailed = LinkedHashMap<Pose, MutableList<Measure>>()
        /**
         * A proposal's contribution LINEARISED at the baseline: its sample [single] (alone on flat layers of
         * one height) against [flat] (flat layers of that height over the whole), each metric's difference at the weights'
         * exchange rate on the baseline's scale. In one pose only the measures that follow the layers differ.
         */
        fun contribution(single: Measure, flat: Measure): Double {
            val a = valuesOf(single, f); val b = valuesOf(flat, f)
            return Scoring.linearised(a.keys.filter { it in b }.associateWith { a.getValue(it) - b.getValue(it) }, f.weights, measuredMetrics, zero, baseAll)
        }
        /**
         * A support proposal's contribution LINEARISED at the baseline: the region's droop becomes
         * its interface (unsupported_area down by its area, support.interface up by its area and the surface its column stands on), its column costs plastic and time at the maximum
         * flow, finishing it first saves travel. A locked spot prevents no droop that is weighed: it is a requirement.
         */
        fun supportCost(co: Coordinated, u: Coordination.Use): Double {
            val st = co.supportStats.getValue(u.proposal)
            val area = st[0]; val vol = st[1]; val saving = st[2]; val base = st[3]; val sev = st[4]
            val delta = LinkedHashMap<Metric, Double>()
            if (!u.proposal.locked) delta[Metric.UNSUPPORTED_AREA] = -sev
            delta[Metric.SUPPORT_INTERFACE] = area + base
            if (f.supportDensity != null) delta[Metric.SUPPORT_MASS] = vol * f.supportDensity / 1000 else delta[Metric.SUPPORT_VOLUME] = vol
            val flow = f.flowMaxMm3s?.takeIf { it > 0 }
            if (flow != null) delta[Metric.TIME_ESTIMATE] = vol / flow - saving else if (saving > 0) delta[Metric.TIME_ESTIMATE] = -saving
            return Scoring.linearised(delta, f.weights, measuredMetrics, zero, baseAll).also { co.supportCost[u.proposal] = it }
        }
        /**
         * In [pose] (its sections known) at height [h] (null = the settings'): the survey proposals' conflict groups, the
         * dynamic program's best sets on their linearised contributions, and the sets not measured yet measured exactly
         * (with the section estimates and the surface technique variants) and added to the candidates.
         */
        fun chooseAt(pose: Pose, h: Double?) {
            val co = coordinated[pose] ?: return
            val key = h ?: f.layerHeightMm ?: return
            val uses = co.singles[key] ?: emptyList()
            val ms = detailed.getValue(pose)
            val base = baseSupport(pose, posedOf.getOrPut(pose) { pose.apply(soup) }.bounds())
            val flatPlan = planOf(PrintTechnique.FLAT_LAYERS to false, h).withSupport(base)
            val flat = ms.firstOrNull { it.surfaceTechniques.isEmpty() && it.plan == flatPlan } ?: return
            fun sampleOf(u: Coordination.Use) = ms.first { it.surfaceTechniques.isEmpty() && it.plan == planOfUses(listOf(u), h).withSupport(base) }
            // support's proposals, beside the survey's bands: the overhang regions under flat layers of this height
            val supportUses = supportProposals(pose, h, flat, "")
            if (uses.isEmpty() && supportUses.isEmpty()) return
            val groups = Coordination.groups(uses + supportUses) { u -> if (u.volume is Coordination.Volume.Under) supportCost(co, u) else contribution(sampleOf(u), flat) }
            val chosen = Coordination.chooseSets(groups, FINALISTS)
            co.groups[key] = groups; co.chosen[key] = chosen
            val fresh = ArrayList<TechniquePlan>()
            for (c in chosen) {
                if (c.combination.uses.isEmpty()) continue
                val plan = planWithSupport(co, c.combination.uses, h, base)
                co.combos.getOrPut(plan) { ArrayList() } += c.combination
                if (ms.none { it.surfaceTechniques.isEmpty() && it.plan == plan } && plan !in fresh) fresh += plan
            }
            // flat layers following the surfaces, with their own overhang regions supported where the weights choose
            val followPlan = planOf(PrintTechnique.FLAT_LAYERS to true, h).withSupport(base)
            val follow = ms.firstOrNull { it.surfaceTechniques.isEmpty() && it.plan == followPlan }
            if (follow != null && h == f.layerHeightCandidates?.firstOrNull()) {
                val fu = supportProposals(pose, h, follow, "under ${TechniquePlan.FOLLOW_SHORT}")
                if (fu.isNotEmpty()) {
                    val fg = Coordination.groups(fu) { u -> supportCost(co, u) }
                    val fc = Coordination.chooseSets(fg, 1)
                    co.groups[-(key)] = fg; co.chosen[-(key)] = fc
                    for (c in fc) {
                        if (c.combination.uses.isEmpty()) continue
                        val plan = planWithSupport(co, c.combination.uses, null, base, followPlan)
                        co.combos.getOrPut(plan) { ArrayList() } += c.combination
                        if (ms.none { it.surfaceTechniques.isEmpty() && it.plan == plan } && plan !in fresh) fresh += plan
                    }
                }
            }
            if (fresh.isEmpty()) return
            val measured = withSurfaceTechniqueVariants(sectioned(pose, measureAll(soup, pose, f.overhang, cavity, covered, f, withSections = false, plansFor = { _, _ -> fresh }, partTriangles = partTriangles)))
            for (x in measured) { scored += pose to x; ms += x }
        }
        for (s in firstPass) {
            val pose = s.item.first
            if (pose in detailed) continue
            if (late() && detailed.isNotEmpty()) break
            detailed[pose] = withSurfaceTechniqueVariants(sectioned(pose, ok.filter { it.first === pose }.map { it.second })).toMutableList()
            for (h in heightsTried.getValue(pose)) chooseAt(pose, h)
        }
        val t2 = System.nanoTime()
        fun finalOf(): List<Pair<Pose, Measure>> {
            val all = detailed.flatMap { (p, ms) -> ms.map { p to it } }
            val kept = all.filter { rejectedFinal(it.first, it.second) == null }
            if (kept.isEmpty()) error("no candidate pose can be used: every candidate leaves a face in the air that nothing holds up: " +
                all.distinctBy { it.first }.joinToString("; ") { "${it.first.label}: ${rejectedFinal(it.first, it.second)}" })
            return kept
        }
        var finalSet = finalOf()
        var values = valuesFor(finalSet)
        var baseValues = baselineOf(values)
        var ranked = Scoring.rank(finalSet, values, f.weights, zero, baseValues)
        val scoresByRound = arrayListOf(ranked.first().total)
        var rounds = 1
        // Rounds: an unexplored interval between two tried heights of a pose can still win only if its
        // bound, for the best way of laying the object there, each scored measure's best among that way's candidates at
        // the interval's ends (assumed its best within the interval: the measures follow the layer height one way between
        // neighboring points), beats the winner. Each round refines the leading combinations' ranges: the interval next
        // to the best-ranked candidate (the best bound among those) is sampled at its midpoint, the sets re-chosen there,
        // everything re-scored; until no interval could win (short-circuited), method.search.settle_rounds rounds in a row
        // leave the winner as it was (settled), the time limit, the per-pose budget, or nothing is left.
        val control = f.layerHeightControl
        val refined = ArrayList<Pair<Pose, Double>>()
        class Interval(val pose: Pose, val a: Double, val b: Double, val bound: Double, val lead: Int)
        fun near(x: Double, y: Double) = Math.abs(x - y) < 5e-4
        fun intervals(): List<Interval> = detailed.keys.flatMap { p ->
            heightsTried.getValue(p).filterNotNull().distinct().sorted().zipWithNext().map { (a, b) ->
                val at = ranked.withIndex().filter { (_, s) -> s.item.first === p && s.item.second.plan.layerHeightMm?.let { h -> near(h, a) || near(h, b) } == true }
                // the bound per way of laying the object: each measure's best within one family (the same plan
                // but for its height and support, the same surface techniques) at the ends, the best family's sum; mixing
                // families' bests made a bound no candidate can reach, so intervals stayed open that none could win
                val families = at.groupBy { it.value.item.second.plan.at(null).withSupport(null) to it.value.item.second.surfaceTechniques }.values
                val bound = families.minOfOrNull { fam -> fam.flatMap { it.value.parts.keys }.distinct().sumOf { m -> fam.minOf { it.value.parts[m] ?: 0.0 } } }
                Interval(p, a, b, bound ?: Double.POSITIVE_INFINITY, at.minOfOrNull { it.index } ?: Int.MAX_VALUE)
            }
        }
        val stop: TechniqueExplain.Stop
        val tStart = t0
        fun elapsed() = (System.nanoTime() - tStart) / 1e9
        fun stopped(r: TechniqueExplain.StopReason, what: String) = TechniqueExplain.Stop(r, elapsed(), rounds, scoresByRound, what)
        val continuous = control != null && control.range is Control.Range.Continuous && overridePlan == null
        // the winner as a choice (pose, plan with its height, surface techniques); rounds in a row that kept it
        fun winnerOf(s: Scoring.Scored<Pair<Pose, Measure>>) = Triple(s.item.first, s.item.second.plan, s.item.second.surfaceTechniques)
        var unchanged = 0
        while (true) {
            val w = ranked.first()
            if (!continuous) {
                stop = stopped(TechniqueExplain.StopReason.EXHAUSTED, if (overridePlan != null) "the plan is locked; every pose reached was measured under it"
                    else if (control == null) "the layer height is the settings' (not decided): one point of every way of laying the object, in every pose reached"
                    else "the layer height is locked at one point: every way of laying the object measured there, in every pose reached"); break
            }
            val ivs = intervals()
            if (ivs.isEmpty()) { stop = stopped(TechniqueExplain.StopReason.EXHAUSTED, "no interval between tried heights is left to explore"); break }
            val could = ivs.filter { it.bound < w.total - 1e-9 }.sortedWith(compareBy({ it.lead }, { it.bound }, { all.indexOf(it.pose) }, { it.a }))
            if (could.isEmpty()) {
                val bestBound = ivs.minOf { it.bound }
                stop = stopped(TechniqueExplain.StopReason.SHORT_CIRCUIT, "the best bound over the ${ivs.size} unexplored interval(s) of layer height is %.3f, not below the winner's %.3f".format(Locale.ROOT, bestBound, w.total)); break
            }
            if (f.settleRounds > 0 && unchanged >= f.settleRounds) {
                stop = stopped(TechniqueExplain.StopReason.SETTLED, "the last $unchanged round(s) left the winner as it was; ${could.size} interval(s) could still win (the best bound %.3f against the winner's %.3f)".format(Locale.ROOT, could.first().bound, w.total)); break
            }
            if (late()) { stop = stopped(TechniqueExplain.StopReason.TIME_LIMIT, "${could.size} interval(s) could still win (the best bound %.3f against the winner's %.3f)".format(Locale.ROOT, could.first().bound, w.total)); break }
            val pick = could.firstOrNull { heightsTried.getValue(it.pose).size < f.layerHeightBudget && it.b - it.a > 2e-3 }
            if (pick == null) {
                stop = if (could.all { it.b - it.a <= 2e-3 }) stopped(TechniqueExplain.StopReason.EXHAUSTED, "the ${could.size} interval(s) that could still win are narrower than two microns")
                    else stopped(TechniqueExplain.StopReason.BUDGET, "${could.count { it.b - it.a > 2e-3 }} interval(s) could still win (the best bound %.3f against the winner's %.3f), but their poses have ${f.layerHeightBudget} heights tried".format(Locale.ROOT, could.first().bound, w.total))
                break
            }
            val h = FlatLayers.micron((pick.a + pick.b) / 2)
            heightsTried.getValue(pick.pose) += h
            val ms = withSurfaceTechniqueVariants(sectioned(pick.pose, measureAll(soup, pick.pose, f.overhang, cavity, covered, f, withSections = false, plansFor = plansFor(pick.pose, listOf(h)), partTriangles = partTriangles)))
            for (x in ms) scored += pick.pose to x
            detailed.getValue(pick.pose) += ms
            chooseAt(pick.pose, h)
            refined += pick.pose to h
            finalSet = finalOf()
            values = valuesFor(finalSet); baseValues = baselineOf(values)
            ranked = Scoring.rank(finalSet, values, f.weights, zero, baseValues)
            rounds++; scoresByRound += ranked.first().total
            if (winnerOf(ranked.first()) == winnerOf(w)) unchanged++ else unchanged = 0
        }
        val posesOk = ok.map { it.first }.distinct().size
        val cutPoses = notReached.isNotEmpty() || detailed.size < posesOk
        // poses not reached are unexplored options with no bound: the search did not finish
        val stopFinal = if (!cutPoses || stop.reason == TechniqueExplain.StopReason.TIME_LIMIT) stop
            else TechniqueExplain.Stop(TechniqueExplain.StopReason.TIME_LIMIT, stop.elapsedS, stop.rounds, stop.winnerScoreByRound,
                "${notReached.size} pose(s) not reached and ${posesOk - detailed.size} without the section estimates; the rounds over the rest: ${stop.what}")
        val baselineFact = if (baseValues == null) "baseline: none (${f.baselineWhat}): scored against the best candidate instead (at most 1 per measure)"
            else "baseline: flat layers %.2f mm = %s, %s: %s".format(Locale.ROOT, f.baselineLayerMm, f.baselineWhat,
                if (override == null) "in the as-given pose (one baseline for every pose: plymeld's choice)" else "in the locked pose", Scoring.describeBaseline(baseValues))
        // The finalists through the full pipeline: the best few by the exact score
        // are sliced and predicted (time, filament), the baseline too, and ranked on those numbers with the measured rest;
        // they decide when the baseline and at least two finalists could be predicted within the time limit.
        var best = ranked.first().item
        var finalists: TechniqueExplain.Finalists? = null
        var finalRanked: List<Scoring.Scored<Pair<Pose, Measure>>> = ranked
        if (predict != null && ranked.size > 1 && baseline != null) {
            val top = ranked.take(FINALISTS)
            val basePred = predict(baseline.first, baseline.second.plan, emptySet())
            val preds = LinkedHashMap<Pair<Pose, Measure>, FullPrediction>()
            val notPredicted = ArrayList<String>()
            for (s in top) {
                val name = label(s.item.first, s.item.second)
                if (late()) { notPredicted += "$name: not reached within the time limit (method.search.time.max)"; continue }
                val p = predict(s.item.first, s.item.second.plan, s.item.second.surfaceTechniques)
                if (p.timeS == null) notPredicted += "$name: ${p.why}" else preds[s.item] = p
            }
            val baseOut = listOfNotNull(basePred.timeS?.let { TechniqueExplain.Outcome(Metric.TIME, Math.round(it).toDouble(), "predicted") },
                basePred.filament?.let { TechniqueExplain.Outcome(it.first, it.second, "predicted") }) +
                basePred.measured.map { (m, v) -> TechniqueExplain.Outcome(m, Math.round(v * 100) / 100.0, "measured on what was written") }
            if (basePred.timeS == null) finalists = TechniqueExplain.Finalists(false, "the baseline's time cannot be predicted (${basePred.why}): the quick estimates decide", baseOut, emptyList(), notPredicted)
            else if (preds.size < 2) finalists = TechniqueExplain.Finalists(false, "${preds.size} finalist(s) predicted, two are needed to compare: the quick estimates decide", baseOut, emptyList(), notPredicted)
            else {
                val items = preds.keys.toList()
                val fil = preds.values.first().filament?.first?.takeIf { m -> preds.values.all { it.filament?.first == m } && basePred.filament?.first == m }
                val fvals = LinkedHashMap<Metric, (Pair<Pose, Measure>) -> Double>()
                for ((k, v) in values) if (k != Metric.TIME_ESTIMATE) fvals[k] = v
                fvals[Metric.TIME] = { c -> Math.round(preds.getValue(c).timeS!!).toDouble() }
                if (fil != null) fvals[fil] = { c -> preds.getValue(c).filament!!.second }
                // the measures read off what each finalist wrote (seam_scar on the planned layers, string_risk and
                // restart_starved on the program): each counts under quality here, where a slice exists, and nowhere earlier
                val written = basePred.measured.keys.filter { m -> preds.values.all { m in it.measured } }
                for (m in written) fvals[m] = { c -> Math.round(preds.getValue(c).measured.getValue(m) * 100) / 100.0 }
                val fbase = LinkedHashMap<Metric, Double>()
                baseValues?.forEach { (k, v) -> if (k != Metric.TIME_ESTIMATE) fbase[k] = v }
                fbase[Metric.TIME] = Math.round(basePred.timeS).toDouble()
                if (fil != null) fbase[fil] = basePred.filament!!.second
                for (m in written) fbase[m] = Math.round(basePred.measured.getValue(m) * 100) / 100.0
                val fr = Scoring.rank(items, fvals, f.weights, zero, fbase)
                best = fr.first().item
                finalRanked = fr
                finalists = TechniqueExplain.Finalists(true, "${preds.size} of ${top.size} finalists sliced and predicted in full: predicted time" +
                    (if (fil != null) " and ${fil.id}" else "") + " in place of the quick estimate" + (if (written.isNotEmpty()) ", " + written.joinToString { it.id } + " measured on what each one wrote (quality)" else "") +
                    ", with the measured rest; the baseline predicted the same way",
                    baseOut, fr.mapIndexed { i, s -> TechniqueExplain.ScoredOut(i + 1, "${s.item.first.label} · ${s.item.second.plan.short}${surfaceTechniqueWords(s.item.second)}", s.total,
                        s.parts.map { (k, v) -> TechniqueExplain.Part(k, v, s.plain[k]) }, s.unscored) }, notPredicted)
            }
        }
        // the model's own orientation has standing (item 1): another pose must beat the as-given pose's best
        // candidate by more than method.search.pose.keep_margin to turn the model
        var marginNote: String? = null
        if (override == null && f.poseKeepMargin != null && f.poseKeepMargin > 0 && !best.first.label.startsWith("as given")) {
            val asGiven = finalRanked.firstOrNull { it.item.first.label.startsWith("as given") && rejectedFinal(it.item.first, it.item.second) == null }
            val bestScored = finalRanked.firstOrNull { it.item === best }
            if (asGiven != null && bestScored != null) {
                // per unit of weight in effect (a weight is a share, never a magic number): the same margin at
                // any total, 0.1 = about a 10 % difference on one fully weighted measure whether the weights sum to 6 or 600
                val gap = (asGiven.total - bestScored.total) / weightInEffect(f)
                if (gap <= f.poseKeepMargin) {
                    marginNote = "kept as given: '%s' scores better by only %.3f, within method.search.pose.keep_margin %.3g (the model's own orientation has standing)".format(Locale.ROOT, best.first.label, gap, f.poseKeepMargin)
                    best = asGiven.item
                } else marginNote = "turned from as given: '%s' scores better by %.3f, more than method.search.pose.keep_margin %.3g".format(Locale.ROOT, best.first.label, gap, f.poseKeepMargin)
            }
        }
        // a locked spot that holds nothing in the chosen pose: support cannot be generated there (refuse, say why)
        refuseUnsupportable(best.first, best.second)
        val scoreOf = ranked.associate { Triple(it.item.first, it.item.second.plan, it.item.second.surfaceTechniques) to it }
        fun est(m: Measure) = listOfNotNull(
            m.layerTops?.let { "${it.size} layers" },
            m.surfaceStepMm?.let { "stair-step %.3f mm".format(Locale.ROOT, it) },
            m.layerLinesMm?.let { "layer lines %.3f mm".format(Locale.ROOT, it) },
            m.weakestBondMm2?.let { "weakest bond %.1f mm²".format(Locale.ROOT, it) },
            m.timeEstimateS?.let { "time ~%.0f min (quick estimate)".format(Locale.ROOT, it / 60) },
            when (f.supportEnabled) {
                true -> "support ~%.0f mm³".format(Locale.ROOT, m.supportVolumeMm3) + (f.supportDensity?.let { " (~%.2f g)".format(Locale.ROOT, m.supportVolumeMm3 * it / 1000) } ?: "")
                false -> null
                null -> "support under ${m.plan.support?.spots?.size ?: 0} spot(s), ~%.0f mm³ (%.1f of %.1f mm² held up)".format(Locale.ROOT, supportVolumeFor(m, f), m.heldMm2 + m.lockedExtraMm2, m.needsSupportMm2 + m.lockedExtraMm2) +
                    (f.supportDensity?.let { " (~%.2f g)".format(Locale.ROOT, supportVolumeFor(m, f) * it / 1000) } ?: "")
            }).joinToString(", ")
        fun isBest(p: Pose, m: Measure) = p === best.first && m.same(best.second)
        /** The chosen candidate's full prediction, where the finalists decided (item 10): the number that decided, beside the quick estimate. */
        fun predictedWords(): String = finalists?.takeIf { it.decided }?.scored?.firstOrNull { it.rank == 1 }?.let { s ->
            val t = s.parts.firstOrNull { it.metric == Metric.TIME }?.plain ?: "?"
            "; predicted in full by the finalists' slice (time %s against the baseline's %.0f s)".format(Locale.ROOT, t, finalists.baseline.firstOrNull { it.metric == Metric.TIME }?.value ?: 0.0) } ?: ""
        /** how much of the quick estimate is layers held at the filament's minimum layer time, so a shorter print by layer count is read for what it is. */
        fun coolingNote(m: Measure): String? = f.minLayerTimeS?.takeIf { it > 0 }?.let { mlt -> m.layerTops?.let { tops -> m.timeEstimateS?.let { t ->
            "the quick time estimate holds every layer at the filament's minimum layer time at least (%.0f s): %d layers x %.0f s = %.0f s of its ~%.0f s; a pose with fewer layers is faster by that floor (one object printing alone waits it out; objects printed together share it)".format(Locale.ROOT, mlt, tops.size, mlt, tops.size * mlt, t) } } }
        // every candidate, pose by pose in the order measured: a detailed pose's candidates with every estimate (the surface technique
        // variants and the dynamic program's sets included), another's with the geometry only
        val alts = scored.map { it.first }.distinct().flatMap { p ->
            val partial = p !in detailed
            (detailed[p] ?: scored.filter { it.first === p }.map { it.second }).map { m ->
                Alternative(label(p, m), "unsupported %.1f mm²%s, on bed %.1f mm², height %.1f mm, center of mass %s".format(Locale.ROOT, m.needsSupportMm2, sealedNote(m), m.contactMm2, m.heightMm,
                    if (m.stable) "over the contact" else if (m.tipOffsetMm.isInfinite()) "(no bed contact)" else "%.1f mm beyond the contact".format(Locale.ROOT, m.tipOffsetMm)) +
                    est(m).let { if (it.isEmpty()) "" else "; $it" } +
                    (scoreOf[Triple(p, m.plan, m.surfaceTechniques)]?.let { "; " + Scoring.explain(it) } ?: ""),
                    rejectedFinal(p, m) ?: if (partial && rejected(p, m) == null) "strength and time estimates not reached within the time limit (method.search.time.max)" else null, chosen = isBest(p, m))
            }
        } + notReached.map { Alternative(it.label, "not measured", "not reached within the time limit (method.search.time.max)", chosen = false) }
        val unmeasured = f.weights.unmeasured(values.keys - ranked.first().unscored.keys)
        val m = best.second
        val search = "search: geometry of %d of %d poses%s in %.1f s; strength and time estimates for %d (best first) in %.1f s; %d round(s) in %.1f s, stopped: %s (%s)%s".format(Locale.ROOT,
            posesMeasured, all.size, if (several) " under ${scored.size / maxOf(1, posesMeasured)} plans each (about)" else "", (t1 - t0) / 1e9, detailed.size, (t2 - t1) / 1e9,
            stopFinal.rounds, stopFinal.elapsedS, stopFinal.reason.id, stopFinal.what, f.timeLimitS?.let { "; time limit %.0f s".format(Locale.ROOT, it) } ?: "")
        val finalistFact = finalists?.let { fs -> "finalists through the full pipeline: ${fs.why}" + (if (fs.decided) "; these numbers decided" else "") }
        val cut = listOfNotNull(if (!cutPoses) null else "search cut short by the time limit: the choice is among the ${detailed.size} poses with every estimate",
            if (stop.reason == TechniqueExplain.StopReason.TIME_LIMIT) "the rounds stopped at the time limit: ${stop.what}" else null)
        val poseDecision = if (override != null) Decision("pose", place(soup, override, f), "pose '${override.label}'", facts, overridden = true)
            else Decision("pose", place(soup, best.first, f),
                "pose '${best.first.label}'${if (several) " with ${m.plan.label}" else ""}: %.1f mm² unsupported%s (%s), %.1f mm² flat on the bed, center of mass %s".format(Locale.ROOT, m.needsSupportMm2, sealedNote(m),
                    when (f.supportEnabled) { true -> "gets support"; false -> "prints over air: support is off"
                        null -> "%.1f mm² of it gets support, decided per overhang".format(Locale.ROOT, m.heldMm2) }, m.contactMm2,
                    if (m.stable) "over the contact" else "%.1f mm beyond it (held by adhesion)".format(Locale.ROOT, m.tipOffsetMm)) +
                    est(m).let { if (it.isEmpty()) "" else "; $it" } + predictedWords(),
                facts + unmeasured.map { "goal '${it.id}' has weight but nothing this decision measures counts toward it" } + search + cut + listOfNotNull(finalistFact, marginNote, coolingNote(m), seenFact(m, f)) + baselineFact + zeroFact(zero, f) + listOfNotNull(Scoring.unscoredFact(ranked)),
                alts, standIns)
        // the technique decision: the surveys' proposals in the chosen pose, every plan measured there,
        // and the techniques that could not take part
        // where on flat layers' layer height range the search looked, and where it chose
        val rangeFacts = if (control == null || !oneOk) emptyList() else {
            fun mm(v: Double) = Control.fmt(v)
            val chosenH = m.plan.layerHeightMm?.takeIf { m.plan.oneHeight }
            listOf("flat layers' layer height, sampled coarse to fine: in every pose " + (f.layerHeightCandidates ?: emptyList()).joinToString { mm(it) } + " mm" +
                (if (control.locked) " (locked)" else " (the range's ends and the baseline where inside it, else the middle)") +
                (if (refined.isEmpty()) "" else "; refined where an unexplored interval could still win (its bound: for the best way of laying the object, each measure's best at the interval's ends): " +
                    refined.joinToString { (p, h) -> mm(h) + " mm" + (if (several || all.size > 1) " in '${p.label}'" else "") }) +
                "; stopped: ${stopFinal.reason.id}; " +
                (chosenH?.let { h -> "chose %s mm%s: the lowest score below (the weights' exchange rates on each measure's difference from the baseline)".format(Locale.ROOT,
                    mm(h), control.share(h)?.let { " (%.0f %% of the way from the thinnest to the thickest)".format(Locale.ROOT, it * 100) } ?: "") }
                    ?: "the chosen plan is not flat layers of one height throughout (${m.plan.short})"))
        }
        val supportFacts = run {
            val so = supportOut(m)
            listOf("support: ${so.how}") + so.locked.map { "locked spot $it" } + so.orders.map { "side-by-side order: $it" }
        }
        val finishFacts = listOfNotNull(ironingNotWeighed(f), if (!weighIroning) null else
            "the flat tops' surface technique weighed (no lock, plan.faces.flat_top.technique unset): ironing (${f.ironingSource}) against as laid, as a variant of every candidate; " +
                (if (SurfaceTechnique.IRONING in m.surfaceTechniques) "ironing won: the rough flat-top area it removes outweighs its pass's time at the weights' exchange rate"
                else "as laid won: ironing's pass costs more time than the rough flat-top area it removes is worth at the weights' exchange rate"))
        val surveyFacts = (proposalsOf[best.first] ?: emptyList()).let { ps ->
            if (!oneOk || !followOk) listOf("survey: not run (flat layers cannot both keep one height and follow the surfaces here, or their heights are locked)")
            else if (ps.isEmpty()) listOf("survey (flat layers): no height band where following the surfaces differs from the layer height by a whole layer")
            else ps.map { "survey proposes ${it.describe()}" }
        }
        val locked = overrideTechnique != null || heights != null
        // the search measures and scores the winner again each round, so the same plan with the same measures is in
        // `ranked` once per round it survived (seen: the chosen plan five times): each distinct plan once,
        // chosen when any of its entries is
        val planAlts = ranked.filter { it.item.first === best.first }.map { s ->
            Alternative(s.item.second.plan.label + surfaceTechniqueWords(s.item.second), "${est(s.item.second)}; ${Scoring.explain(s)}", null, chosen = s.item.second.same(m))
        }.groupBy { it.label + "\u0000" + it.score }.values.map { same -> same.first().let { Alternative(it.label, it.score, it.rejected, chosen = same.any { a -> a.chosen }) } } +
            notTried { overrideTechnique != null && it != overrideTechnique }
        val techniqueDecision = Decision("technique", m.plan,
            m.plan.label + surfaceTechniqueWords(m) + when {
                locked -> ""
                several -> " (weighted score, together with the pose)"
                else -> " (the only usable technique)"
            } + est(m).let { if (it.isEmpty()) "" else ": $it" },
            techniqueFacts + rangeFacts + finishFacts + supportFacts + surveyFacts + (if (override == null) emptyList() else facts + search + cut) + listOfNotNull(finalistFact) + baselineFact + zeroFact(zero, f) + listOfNotNull(Scoring.unscoredFact(ranked)), planAlts, techniqueStandIns, overridden = locked)
        return Posed(poseDecision, techniqueDecision, explainOf(best.first, m, ranked, scored, detailed, coordinated[best.first], wholes, f, baseline,
            { p, x -> rejectedFinal(p, x) ?: if (p !in detailed) "strength and time estimates not reached within the time limit (method.search.time.max)" else null },
            explainScreen, explainControls + finishFacts + f.supportControls.map { "support: " + it.describe() }, explainLocks, refined, notReached, posesOk, stopFinal, finalists, ::surfaceTechniqueWords, supportOut(m)),
            m.surfaceTechniques, m.weakestBondMm2)
    }

    /**
     * The coordinator's record of a measured decision: every proposal in the chosen pose
     * [pose] with its fate (chosen, trimmed by its own technique, or rejected: its best candidate against the winner,
     * on the measures that decided), the fill, the candidates there with every measure beside the baseline, the best
     * [TechniqueExplain.SHOWN] scores and the decision. Read from what the search measured; it decides nothing.
     */
    private fun explainOf(pose: Pose, m: Measure, ranked: List<Scoring.Scored<Pair<Pose, Measure>>>, scored: List<Pair<Pose, Measure>>,
                          detailed: Map<Pose, List<Measure>>, coordinated: Coordinated?, wholes: List<Coordination.Proposal>,
                          f: PoseFacts, baseline: Pair<Pose, Measure>?, rejectedWhy: (Pose, Measure) -> String?,
                          screen: List<TechniqueExplain.Screen>, controls: List<String>, locks: List<String>,
                          refined: List<Pair<Pose, Double>>, notReached: List<Pose>, posesOk: Int, stop: TechniqueExplain.Stop,
                          finalists: TechniqueExplain.Finalists?, surfaceTechniqueWords: (Measure) -> String, support: TechniqueExplain.SupportOut): TechniqueExplain {
        fun full(p: Pose, x: Measure) = "${p.label} · ${x.plan.short}" + surfaceTechniqueWords(x)
        val proposals = coordinated?.proposals ?: wholes
        val combos = coordinated?.combos ?: emptyMap()
        // the combinations that make [plan]; a height refined is flat layers' whole-object proposal at another point of its control
        fun combosOf(plan: TechniquePlan): List<Coordination.Combination> = combos[plan] ?: listOfNotNull(wholes.firstOrNull {
            plan.oneHeight && plan.bands.size == 1 && it.technique == PrintTechnique.FLAT_LAYERS && it.variant == null }?.let { Coordination.Combination(listOf(Coordination.Use(it, it.volume))) })
        val chosenAll = combosOf(m.plan)
        val chosen = chosenAll.firstOrNull()
        // the winner by the exact score (the finalists' full prediction may have chosen another than the first ranked)
        val winner = ranked.first { it.item.first === pose && it.item.second.same(m) }
        val inPose = ranked.filter { it.item.first === pose }
        /** The measures on which [s] lost to the winner, most first. */
        fun lostOn(s: Scoring.Scored<*>): String = (s.parts.keys + winner.parts.keys).distinct()
            .map { k -> k to (s.parts[k] ?: 0.0) - (winner.parts[k] ?: 0.0) }.filter { it.second > 1e-9 }.sortedByDescending { it.second }.take(2)
            .joinToString(", ") { (k, d) -> "%s (+%.3f)".format(Locale.ROOT, k.id, d) }.ifEmpty { "an exact tie, kept in the order measured" }
        val outs = proposals.distinctBy { it.id }.map { p ->
            val use = chosenAll.firstNotNullOfOrNull { c -> c.uses.firstOrNull { it.proposal === p } }
            when {
                use != null && use.trimmed -> TechniqueExplain.ProposalOut(p, TechniqueExplain.Fate.TRIMMED,
                    "in the winner; trimmed where its technique allows (cuts ${p.cuts.words})" + (p.trimNote?.let { ": $it" } ?: ""), use.volume)
                use != null -> TechniqueExplain.ProposalOut(p, TechniqueExplain.Fate.CHOSEN, if (p.locked) "a requirement: a locked proposal is always in the chosen set" else "in the winner, whole")
                else -> {
                    val s = inPose.firstOrNull { c -> combosOf(c.item.second.plan).any { cb -> cb.uses.any { it.proposal === p } } }
                    val supportCost = coordinated?.supportCost?.get(p)
                    TechniqueExplain.ProposalOut(p, TechniqueExplain.Fate.REJECTED,
                        if (s == null && p.technique == PrintTechnique.SUPPORT && supportCost != null)
                            "not taken by the dynamic program: its contribution linearised at the baseline is %.3f (scars, plastic, and time against the droop it prevents, at the weights' exchange rate)%s".format(Locale.ROOT, supportCost,
                                if (p.locked) ": a requirement, but its other print order costs less" else if (supportCost >= -1e-9) ", not below none's 0: the droop costs no more than the scars under quality (the same area counts either way), so support wins only where the weights name unsupported_area or a spot is locked" else ", beaten by another order or set")
                        else if (s == null && p.technique == PrintTechnique.SUPPORT) "not weighed: no candidate was built with it"
                        else if (s == null && proposals.any { it.locked && it.technique != PrintTechnique.SUPPORT }) "dropped: the plan's locked bands take the object (requirements)"
                        else if (s == null) "no candidate kept it: nothing was left of it on its technique's trim, or it was not measured"
                        else "its best candidate, %s (score %.3f), lost to the winner, %s (score %.3f), on %s".format(Locale.ROOT,
                            s.item.second.plan.short, s.total, m.plan.short, winner.total, lostOn(s)))
                }
            }
        }
        val fill = Coordination.fill(Coordination.Volume.WHOLE, chosen?.uses?.map { it.volume } ?: emptyList())
        val scoreOf = ranked.associateBy { Triple(it.item.first, it.item.second.plan, it.item.second.surfaceTechniques) }
        // every candidate in the chosen pose: with every estimate where the pose was detailed (the surface technique variants and the
        // dynamic program's sets included), else the geometry only
        val candidates = (detailed[pose] ?: scored.filter { it.first === pose }.map { it.second }).map { x ->
            TechniqueExplain.Candidate(full(pose, x), valuesOf(x, f).map { (k, v) -> TechniqueExplain.Outcome(k, v, TechniqueExplain.sourceOf(k)) },
                rejectedWhy(pose, x), x.same(m), scoreOf[Triple(pose, x.plan, x.surfaceTechniques)]?.total)
        }
        val base = baseline?.let { (bp, bm) -> TechniqueExplain.Baseline("flat layers %s mm over the same volume (the whole object), in pose '%s' (%s)".format(Locale.ROOT,
            Control.fmt(f.baselineLayerMm!!), bp.label, f.baselineWhat), valuesOf(bm, f).map { (k, v) -> TechniqueExplain.Outcome(k, v, TechniqueExplain.sourceOf(k)) }) }
        val scoring = ranked.take(TechniqueExplain.SHOWN).mapIndexed { i, s ->
            TechniqueExplain.ScoredOut(i + 1, full(s.item.first, s.item.second), s.total, s.parts.map { (k, v) -> TechniqueExplain.Part(k, v, s.plain[k]) }, s.unscored)
        }
        // the runner-up: by the full prediction where that decided, else the next by the exact score
        val runnerFull = finalists?.takeIf { it.decided }?.scored?.getOrNull(1)
        val runner = ranked.firstOrNull { it !== winner }
        val deciding = if (runner == null) emptyList() else (winner.parts.keys + runner.parts.keys).distinct()
            .map { k -> TechniqueExplain.Deciding(k, winner.parts[k] ?: 0.0, runner.parts[k] ?: 0.0) }
            .filter { it.runnerUp - it.winner > 1e-9 }.sortedByDescending { it.runnerUp - it.winner }.take(3)
        val points = f.layerHeightCandidates?.let { hs -> "layer heights " + hs.joinToString { Control.fmt(it) } + " mm in every pose" +
            (if (refined.isEmpty()) "" else "; refined where an unexplored interval could still win: " + refined.joinToString { (p, h) -> Control.fmt(h) + " mm in '${p.label}'" }) }
        // the dynamic program's record in the chosen pose, per height: the conflict groups, their options with the
        // linearised costs, and the sets it kept
        val sets = coordinated?.groups.orEmpty().map { (h, gs) ->
            (if (h < 0) "at ${Control.fmt(-h)} mm under ${TechniquePlan.FOLLOW_SHORT} (support only): " else "at ${Control.fmt(h)} mm: ") +
                (if (gs.isEmpty()) "no proposal" else gs.joinToString("; ") { g -> val beside = g.members.any { it.volume is Coordination.Volume.Under }
                    "group ${g.words}: " + g.options.joinToString(", ") { "%s %.3f".format(Locale.ROOT, it.label(beside), it.cost) } }) +
                "; chosen: " + coordinated?.chosen?.get(h).orEmpty().joinToString("; ") { c -> "%s (%.3f)".format(Locale.ROOT, c.options.joinToString(" / "), c.cost) }.ifEmpty { "none" }
        }
        val cut = listOfNotNull(
            if (notReached.isEmpty()) null else "${notReached.size} pose(s) not reached: " + notReached.joinToString { "'${it.label}'" },
            if (detailed.size < posesOk) "strength and time estimates for ${detailed.size} of $posesOk poses (best first)" else null,
            if (stop.reason == TechniqueExplain.StopReason.TIME_LIMIT) "the rounds: ${stop.what}" else null,
            finalists?.notPredicted?.takeIf { it.isNotEmpty() }?.let { "finalists not predicted: " + it.joinToString("; ") })
        return TechniqueExplain("", pose.label, outs, m.plan.portions(), fill, screen, controls, points, candidates, null, base, scoring, ranked.size,
            TechniqueExplain.DecisionOut(full(pose, m), runnerFull?.label ?: runner?.let { full(it.item.first, it.item.second) },
                runnerFull?.let { it.total - finalists.scored.first().total } ?: runner?.let { it.total - winner.total }, deciding, locks, cut, stop, finalists, support), sets = sets)
    }

    /**
     * Reference scales for counts whose baseline value is 0: method.search.zero_scale where set,
     * else derived from the model — area counts (the rough flat tops' too) against its surface area, support volume against
     * its volume (support mass against its volume in the support filament), tip offset against its
     * largest dimension, stair-step against the layer height, bed contact against the model's largest flat face
     * ([largestFlatFaceMm2]: the only scale bed contact has besides method.search.zero_scale).
     * Nothing derives a scale for bond.area, time.estimate, layer_lines, or height: they stay unscored at a zero baseline.
     */
    fun zeroScales(soup: TriangleSoup, f: PoseFacts): Map<Metric, Double> {
        var area = 0.0; var vol = 0.0
        val c = soup.coords
        for (i in 0 until soup.count) {
            val o = 9 * i
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            area += Math.sqrt((uy * vz - uz * vy).let { it * it } + (uz * vx - ux * vz).let { it * it } + (ux * vy - uy * vx).let { it * it }) / 2
            vol += (c[o].toDouble() * (c[o + 4].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 7]) -
                c[o + 1].toDouble() * (c[o + 3].toDouble() * c[o + 8] - c[o + 5].toDouble() * c[o + 6]) +
                c[o + 2].toDouble() * (c[o + 3].toDouble() * c[o + 7] - c[o + 4].toDouble() * c[o + 6])) / 6
        }
        val b = soup.bounds()
        val derived = LinkedHashMap<Metric, Double>()
        // support's units: one tree pad (the tip spacing squared) for the areas, one pad's column PAD_COLUMN_MM tall
        // for the plastic; the tip spacing from support's controls, else a stand-in
        val pad = padMm2(f)
        derived[Metric.UNSUPPORTED_AREA] = pad; derived[Metric.SUPPORT_INTERFACE] = pad; derived[Metric.ROUGH_TOP_AREA] = area
        if (f.supportDensity != null) derived[Metric.SUPPORT_MASS] = pad * PAD_COLUMN_MM * f.supportDensity / 1000 else derived[Metric.SUPPORT_VOLUME] = pad * PAD_COLUMN_MM
        derived[Metric.TIP_OFFSET] = maxOf(b[3] - b[0], b[4] - b[1], b[5] - b[2])
        // the stair-step's unit is mm to the depth power less one (stairStep): the layer height at the bundled power 2
        f.layerHeightMm?.let { derived[Metric.SURFACE_STEP] = Math.pow(it, f.stairStepPower - 1); derived[Metric.HEIGHT_ERROR] = it }
        // bed contact: where the baseline pose has no flat face on the bed, against the most any
        // pose could put there, the model's largest flat face
        derived[Metric.BED_CONTACT] = largestFlatFaceMm2(soup)
        return derived.filterValues { it > 0 } + f.zeroScale
    }

    /** The height of the pad's column that is one unit of support's plastic (mm): a stand-in. */
    const val PAD_COLUMN_MM = 10.0
    /** Droop severity's cap: a horizontal face over nothing counts this many times its area (plymeld's choice). */
    const val SEVERITY_CAP = 10.0
    /**
     * Droop severity: how far a face that needs support steps out per layer past what a line may hang
     * ([plymeld.model.Overhang.stepMm]), 1 at the limit, [SEVERITY_CAP] at most (a horizontal face steps out everything at once).
     */
    fun severity(down: Double, layerMm: Double, overhang: Overhang): Double {
        val sin = Math.sqrt(maxOf(0.0, 1 - down * down))
        if (sin < 1e-9) return SEVERITY_CAP
        val step = layerMm * down / sin
        return minOf(SEVERITY_CAP, maxOf(1.0, step / overhang.stepMm))
    }
    /** One tree pad (mm²): the tip spacing squared (plan.support.tree.tip_spacing, from support's controls); a stand-in of 4 mm² without it. */
    fun padMm2(f: PoseFacts): Double = f.supportControls.firstOrNull { it.id == "plan.support.tree.tip_spacing" }
        ?.let { (it.range as? Control.Range.Steps)?.values?.singleOrNull() }?.let { it * it } ?: 4.0

    /** How each derived zero scale is made from the model or the settings (what [zeroScales] does), for the decision's log. */
    private val ZERO_SCALE_HOW = mapOf(
        Metric.UNSUPPORTED_AREA to "one tree pad, the tip spacing squared (the unit of ln(1 + area / unit))", Metric.SUPPORT_INTERFACE to "one tree pad, the tip spacing squared (the unit of ln(1 + area / unit))", Metric.ROUGH_TOP_AREA to "the model's surface area",
        Metric.SUPPORT_MASS to "one pad's column %.0f mm tall in the support filament (the unit of ln(1 + mass / unit))".format(Locale.ROOT, PAD_COLUMN_MM), Metric.SUPPORT_VOLUME to "one pad's column %.0f mm tall (the unit of ln(1 + volume / unit))".format(Locale.ROOT, PAD_COLUMN_MM),
        Metric.TIP_OFFSET to "the model's largest dimension", Metric.SURFACE_STEP to "the layer height, plan.technique.flat.layer.height, to method.quality.stair_step.depth_power less one", Metric.HEIGHT_ERROR to "the layer height, plan.technique.flat.layer.height",
        Metric.BED_CONTACT to "the model's largest flat face: its triangles grouped by plane, unit normals agreeing to 0.001 in each " +
            "component and plane offsets within %s mm, the largest group's area".format(Locale.ROOT, ON_BED_MM))

    /**
     * The area (mm²) of the model's largest flat face: the largest planar region of the mesh, its
     * coplanar triangles grouped. Triangles are coplanar when their unit normals agree to 0.001 in each component (the
     * grouping [candidates] uses for resting faces) and their planes' offsets along that normal lie within [ON_BED_MM] of
     * each other (chained: sorted by offset, a gap wider than that starts a new face; the tolerance "on the bed" uses).
     * Coplanar faces count together whether or not they touch, as they would on the bed (a table's four feet). Any
     * orientation: the pose only turns the model, so this is the most bed contact any pose could have. 0 for an empty mesh.
     */
    fun largestFlatFaceMm2(soup: TriangleSoup): Double {
        val n = soup.count
        val key = LongArray(n); val offset = DoubleArray(n); val area = DoubleArray(n)
        val c = soup.coords
        var m = 0
        for (i in 0 until n) {
            val o = 9 * i
            val ux = (c[o + 3] - c[o]).toDouble(); val uy = (c[o + 4] - c[o + 1]).toDouble(); val uz = (c[o + 5] - c[o + 2]).toDouble()
            val vx = (c[o + 6] - c[o]).toDouble(); val vy = (c[o + 7] - c[o + 1]).toDouble(); val vz = (c[o + 8] - c[o + 2]).toDouble()
            val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
            val len = Math.sqrt(nx * nx + ny * ny + nz * nz)
            if (len == 0.0) continue
            val qx = Math.round(nx / len * 1000); val qy = Math.round(ny / len * 1000); val qz = Math.round(nz / len * 1000)
            key[m] = ((qx + 1000) shl 22) or ((qy + 1000) shl 11) or (qz + 1000)
            offset[m] = (nx * c[o] + ny * c[o + 1] + nz * c[o + 2]) / len
            area[m] = len / 2
            m++
        }
        val order = (0 until m).sortedWith(compareBy<Int>({ key[it] }, { offset[it] }))
        var best = 0.0; var sum = 0.0
        for ((k, i) in order.withIndex()) {
            val prev = if (k == 0) -1 else order[k - 1]
            if (prev < 0 || key[prev] != key[i] || offset[i] - offset[prev] > ON_BED_MM) sum = 0.0
            sum += area[i]
            if (sum > best) best = sum
        }
        return best
    }

    private fun zeroFact(zero: Map<Metric, Double>, f: PoseFacts) = "scoring by the size of the difference" +
        (if (f.baselineLayerMm == null) "; where the best" else " from the baseline: weight x ln(value / baseline's), " +
            "linear for measures that can be 0 (" + Scoring.ZEROABLE.joinToString { it.id } + "); where the baseline's") + " value is 0, against: " +
        zero.entries.joinToString { (m, v) -> "%s %.4g %s (%s)".format(Locale.ROOT, m.id, v, m.unit,
            if (m in f.zeroScale) f.zeroScaleSource else "derived: " + (ZERO_SCALE_HOW[m] ?: "from the model")) }

    /** The pose with its translation: bottom on the bed (z = 0), footprint centered on the bed. */
    private fun place(soup: TriangleSoup, p: Pose, f: PoseFacts): Pose {
        if (p.label == "as given") return p
        val b = p.apply(soup).bounds()
        val t = doubleArrayOf(p.t[0] + (f.bedMinX + f.bedMaxX) / 2 - (b[0] + b[3]) / 2, p.t[1] + (f.bedMinY + f.bedMaxY) / 2 - (b[1] + b[4]) / 2, p.t[2] - b[2])
        return Pose(p.label, p.r, t)
    }

    fun merged(parts: List<TriangleSoup>): TriangleSoup = if (parts.size == 1) parts[0] else {
        val c = FloatArray(parts.sumOf { it.count } * 9); var w = 0
        for (q in parts) { System.arraycopy(q.coords, 0, c, w, q.count * 9); w += q.count * 9 }
        TriangleSoup(c, parts.sumOf { it.count })
    }

    /**
     * Support locked off (plan.support.enabled = false): the
     * overhang regions of [parts] in the chosen [pose] under [plan] that nothing but support can lay, by the cantilever rule (a
     * cantilever reaching past a line width over air, anchored on one side only, or a bridge past the filament's span), one
     * warning each. Where support is decided per overhang such a region is a requirement and a candidate leaving it unheld
     * is rejected; locked off, the plan stands and the region prints over air, so it is said, never refused.
     */
    fun unheldWithSupportOff(parts: List<TriangleSoup>, pose: Pose, plan: TechniquePlan, f: PoseFacts, wave: Boolean = false): List<String> {
        if (f.supportEnabled != false) return emptyList()
        val soup = merged(parts)
        val covered = Cavities.manifoldShells(soup).takeIf { ids -> ids.toSet().size > 1 }?.let { Cavities.coveredShare(soup, it) }
        val m = measureAll(soup, pose, f.overhang, null, covered, f, withSections = false, plansFor = { _, _ -> listOf(plan) }, surveyAnyway = true).single()
        val regions = m.overhangs.orEmpty()
        if (regions.isEmpty()) return emptyList()
        val posed = pose.apply(soup)
        val b = posed.bounds()
        // the layer a region's lowest face is printed in, in 0.05 mm buckets as the decision's own check cuts it
        fun layerOf(r: OverhangRegion): Double {
            val t = m.layerTops
            val h = if (t != null && t.isNotEmpty()) {
                var i = java.util.Arrays.binarySearch(t, b[2] + r.region.fromMm + 1e-6).let { if (it < 0) -it - 1 else it }
                if (i >= t.size) i = t.size - 1
                t[i] - (if (i == 0) b[2] else t[i - 1])
            } else plan.layerHeightMm ?: f.layerHeightMm ?: f.firstLayerMm ?: 0.0
            return Math.max(0.05, Math.round(h / 0.05) * 0.05)
        }
        val out = ArrayList<String>()
        for ((h, rs) in regions.groupBy { layerOf(it) }) {
            val cuttable = rs.filter { it.region.fromMm >= h - 1e-9 }
            if (cuttable.isEmpty()) continue
            val zs = cuttable.flatMap { listOf(Cantilever.belowZ(b, it.region, h), Cantilever.layerZ(b, it.region, h)) }.distinct().sorted()
            val cut = plymeld.mesh.Slicer.slice(posed, zs.toDoubleArray())
            val loopsAt = zs.indices.associate { zs[it] to cut[it].loops }
            for (r in cuttable) {
                val reach = Cantilever.reach(Cantilever.Area(loopsAt.getValue(Cantilever.layerZ(b, r.region, h))), Cantilever.Area(loopsAt.getValue(Cantilever.belowZ(b, r.region, h))), b, r.region) ?: continue
                val bridgeLimit = f.bridgeMaxSpanMm
                if (reach.reachMm <= f.overhang.widthMm + 1e-9 || (reach.bridged && (bridgeLimit == null || 2 * reach.reachMm <= bridgeLimit + 1e-9))) continue
                out += "support is off (plan.support.enabled = false), and ${r.id} (${r.region.words}) has nothing under it: " +
                    (if (!reach.bridged) "a cantilever, its lowest layer reaching %.1f mm over air from one side only (at %.2f, %.2f)".format(Locale.ROOT, reach.reachMm, reach.x, reach.y)
                    else "a bridge spanning about %.0f mm, past the filament's bridging limit of %.0f mm (hardware.filament.bridge.span.max; at %.2f, %.2f)".format(Locale.ROOT, 2 * reach.reachMm, bridgeLimit!!, reach.x, reach.y)) +
                    // a cantilever with wave overhangs on (wave overhang, support, or nothing) is grown from its supported side
                    (if (!reach.bridged && wave) "; it will be laid as wave overhangs grown from its supported side (plan.faces.under.technique), with no support under it"
                    else "; it will print over air (only support lays it)")
            }
        }
        return out
    }
}
