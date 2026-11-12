package plymeld.core.check

import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.Setup
import plymeld.model.SliceSettings

/** What the config says a print on this machine must be (GcodeCheck.Expect), from the setup's settings. */
object Expectations {
    /**
     * [onSurface]: the model's outer-surface test (ModelCheck.Surface.at) when there is a model; [partTools], [allowedTools]: the
     * tools the config's loadout gives the parts, and those and the support's (plymeld.config.LoadoutTools); [zaa]: the model's
     * top for lines lowered by Z anti-aliasing ([forZaa]); see GcodeCheck.Expect.
     */
    fun forSetup(setup: Setup, onSurface: ((Double, Double, Double, Double) -> Boolean)? = null,
                 partTools: Set<Int>? = null, allowedTools: Set<Int>? = null, liftedBridge: Boolean = false, zaa: ZaaRule? = null,
                 /** A scarf ramp's starting share the print has whatever the job locks: the seam plate prints scarf seams by its own specification. Null = the config's. */
                 scarfStartOverride: Double? = null): GcodeCheck.Expect {
        val s = SliceSettings.of(setup)
        fun bed(k: String) = (Resolver.resolve(setup, "hardware.machine.build.$k").value as Number).toDouble()
        // every retraction length a spool in the print may use: each object's, and each part's
        val lengths = setup.objects.indices.flatMap { o ->
            (listOf<Int?>(null) + setup.objects[o].parts.indices.toList()).map { p -> (Resolver.resolve(setup, "hardware.filament.retract.length", Context(o, p)).value as Number).toDouble() } +
                // a waved bridge's own retraction after each bead (plan.technique.bridge.wave.retract)
                listOfNotNull((Resolver.resolveOrNull(setup, "plan.technique.bridge.wave.retract", Context(o))?.value as Number?)?.toDouble())
        }.toSet()
        // scarf seams: the lowest starting share of any object that scarfs its outer walls (plan.technique.scarf_seam.where), or none
        val scarfStart = setup.objects.indices.mapNotNull { o ->
            val mode = Resolver.resolveOrNull(setup, "plan.technique.scarf_seam.where", Context(o))?.value as String?
            if (mode == null || mode == "none") null else (Resolver.resolveOrNull(setup, "plan.technique.scarf_seam.start", Context(o))?.value as Number?)?.toDouble()
        }.minOrNull()
        // a restore spread over a path's start (hardware.filament.retract.return.distance, with the tool's extrude.section.max):
        // the longest any object or part spreads, and the tightest limit
        val contexts = setup.objects.indices.flatMap { o -> listOf(Context(o)) + setup.objects[o].parts.indices.map { Context(o, it) } }
        val sections = contexts.mapNotNull { c -> (Resolver.resolveOrNull(setup, "hardware.tool.extrude.section.max", c)?.value as Number?)?.toDouble() }
        val spreads = contexts.mapNotNull { c ->
            if (Resolver.resolveOrNull(setup, "hardware.tool.extrude.section.max", c) == null) null
            else (Resolver.resolveOrNull(setup, "hardware.filament.retract.return.distance", c)?.value as Number?)?.toDouble()?.takeIf { it > 0 }
        }
        return GcodeCheck.Expect(bed("left"), bed("front"), bed("right"), bed("back"), s.maxPrintHeightMm,
            maxPrintSpeed = s.hiddenSpeed, maxTravelSpeed = s.travelSpeed, maxAccel = maxOf(s.defaultAccel, s.travelAccel),
            retractLengths = lengths, startTemplate = s.startGcode, endTemplate = s.endGcode, onSurface = onSurface,
            partTools = partTools, allowedTools = allowedTools, scarfStart = scarfStartOverride ?: scarfStart, tacksAllowed = liftedBridge, zaa = zaa,
            returnSpreadMm = spreads.maxOrNull(), extrudeSectionMaxMm2 = sections.minOrNull(),
            filamentAreaMm2 = Math.PI * s.filamentDiameterMm * s.filamentDiameterMm / 4)
    }

    /**
     * The Z anti-aliasing rule for a print of [objects] (each a setup object's index and its parts as placed, the bed at [zBase]
     * in their frame) when any object has the sloped tops' surface technique Z anti-aliasing
     * (plan.faces.sloped_top.technique = "z_antialiasing"), else null. Its top is the union of the parts of the objects that
     * have it on, every part of each (a part's face another part of its object covers is not a top), so a line lowered in an
     * object that has it off is refused; the thinnest lowered line, the least of those objects' (plymeld.core.ZAntiAlias.minMm);
     * the plate's slicing resolution; runs of solid closer than that resolution are one, for a print with a lifted bridge
     * ([liftedBridge]) closer than where a bridge counts as resting on a support (as the slicer joins them).
     */
    fun forZaa(setup: Setup, objects: List<Pair<Int, List<plymeld.mesh.TriangleSoup>>>, zBase: Double, liftedBridge: Boolean): ZaaRule? {
        val on = objects.filter { (o, _) -> Resolver.resolveOrNull(setup, "plan.faces.sloped_top.z_antialiasing", Context(o))?.value == true }
        if (on.isEmpty()) return null
        val min = on.minOf { (o, _) -> plymeld.core.ZAntiAlias.minMm(setup, o)
            ?: error("Z anti-aliasing needs hardware.tool.<key>.layer.height.min (the thinnest a lowered line may be)") }
        val res = SliceSettings.of(setup).resolutionUm / 1000.0
        val join = if (liftedBridge) maxOf(res, plymeld.mesh.LiftWarp.CONTACT_MM) else res
        return ZaaRule(on.flatMap { it.second }, zBase, min, res, join)
    }
}
