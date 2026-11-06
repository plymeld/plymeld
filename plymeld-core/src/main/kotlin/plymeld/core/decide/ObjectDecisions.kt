package plymeld.core.decide

import plymeld.core.IncrementalSlicer
import plymeld.mesh.TriangleSoup
import plymeld.model.Context
import plymeld.model.Resolver
import plymeld.model.Setup
import plymeld.model.SliceSettings

/**
 * The decisions made per object after its pose, technique, and the plate's order are known, here with the others
 * rather than in the commands module, and read by kind, never by position: bond-first cooling, the seams on the walls the slice's own
 * stages build, and the in-island retraction on the object's planned layers. Each reads the setup as
 * the slice will see it (the decided layer height, infill direction, and flat-top technique applied); each returns null
 * where it cannot be made here, and says nothing then (the slice decides what it can).
 */
object ObjectDecisions {
    /** Bond-first cooling for object [k] as placed ([parts]); [weakestBond] as the search measured it (null: measured here, unless locked). */
    fun bond(setup: Setup, k: Int, name: String, parts: List<TriangleSoup>, weakestBond: Double?, lock: Pair<Boolean, String>?): Decision<BondCooling.Choice>? {
        val ctx = Context(k)
        fun num(id: String) = (Resolver.resolveOrNull(setup, id, ctx)?.value as Number?)?.toDouble()
        val fan = num("hardware.filament.cooling.fan") ?: return null
        val minT = num("hardware.filament.cooling.layer.time.min") ?: return null
        val printC = num("hardware.filament.temperature.print") ?: return null
        val bFan = num("plan.technique.bond_cooling.fan") ?: return null
        val bMin = num("plan.technique.bond_cooling.layer.time.min") ?: return null
        var bTemp = num("plan.technique.bond_cooling.temperature") ?: return null
        // capped at the filament's temperature.max where it has one
        num("hardware.filament.temperature.max")?.let { bTemp = minOf(bTemp, it) }
        val model = BondCooling.Model(num("method.strength.bond.fan_loss") ?: return null, num("method.strength.bond.temperature_gain") ?: return null,
            num("method.strength.bond.cooling_loss") ?: return null, num("method.strength.bond.cooling_time") ?: return null,
            num("method.strength.bond.enough_area") ?: return null)
        val weights = Resolver.resolve(setup, "job.priorities", ctx).let { Weights.parse(it.value as String, it.from) }
        val line = num("plan.layer.walls.outer.line.width") ?: 0.4
        // surface and side (non-flat) areas summed over the parts as they are (no merged copy: the dual Benchy is 262k triangles);
        // the side area as seen (the visibility map's direction and view terms; what another part covers is not read here yet)
        val vis = Visibility.from(setup, ctx)
        var surface = 0.0; var side = 0.0
        for (part in parts) {
            val cs = part.coords
            for (i in 0 until part.count) {
                val q = 9 * i
                val ux = (cs[q + 3] - cs[q]).toDouble(); val uy = (cs[q + 4] - cs[q + 1]).toDouble(); val uz = (cs[q + 5] - cs[q + 2]).toDouble()
                val vx = (cs[q + 6] - cs[q]).toDouble(); val vy = (cs[q + 7] - cs[q + 1]).toDouble(); val vz = (cs[q + 8] - cs[q + 2]).toDouble()
                val nx = uy * vz - uz * vy; val ny = uz * vx - ux * vz; val nz = ux * vy - uy * vx
                val a = Math.sqrt(nx * nx + ny * ny + nz * nz) / 2
                surface += a
                // non-flat: a face less than ~8 deg from horizontal is a top or a bottom, the rest is side
                if (a > 0 && Math.abs(nz) / (2 * a) < 0.99) side += a * (vis?.let { it.direction(-nz / (2 * a)) * it.awayTerm(it.awayDot(nx / (2 * a), ny / (2 * a), nz / (2 * a))) } ?: 1.0)
            }
        }
        // the weakest bond as the search measured it; measured here only where it measured nothing (a locked pose), and not under a lock
        fun measureBond(): Double? {
            val soup = parts.singleOrNull() ?: run {
                val all = FloatArray(parts.sumOf { it.count } * 9); var at = 0
                for (q in parts) { System.arraycopy(q.coords, 0, all, at, q.count * 9); at += q.count * 9 }
                TriangleSoup(all, all.size / 9)
            }
            return PoseDecision.weakestBondMm2(soup, line)
        }
        return BondCooling.decide(name, BondCooling.Conditions(fan, printC, minT), BondCooling.Conditions(bFan, bTemp, bMin),
            printC, model, weights, if (lock == null) weakestBond ?: measureBond() else null, side, surface, lock?.first, lock?.second)
    }

    /**
     * The seams of object [k]: its walls as the slice's walls stage lays them ([slicer], shared with the
     * retraction decision and the slice's own cache), each candidate placed and measured (SeamDecision), weighed by the
     * object's weights; or locked by plan.seam ([lock]: value and source; [plainStyle] the scarf's plain style). [timeS]:
     * the baseline's predicted print time, the scale the scarf's extra pass is scored against (null: not scored). Null when
     * the object cannot be sliced here (no layer height): then plan.seam must be set, and the slice says so.
     */
    fun seams(setup: Setup, k: Int, name: String, slicer: IncrementalSlicer, timeS: Double?, lock: Pair<String, String>?, plainStyle: String?): Decision<SeamDecision.Choice>? {
        val ctx = Context(k)
        val weights = Resolver.resolve(setup, "job.priorities", ctx).let { Weights.parse(it.value as String, it.from) }
        val s = try { SliceSettings.of(setup, ctx) } catch (e: IllegalStateException) { return null } catch (e: IllegalArgumentException) { return null }
        val visibility = Visibility.from(setup, ctx)
        val shares = Visibility.seamShares(setup, ctx)
        val noWalls = SeamDecision.Walls(emptyList(), emptyList(), LongArray(0), s.outerWallWidthUm, s.innerWallWidthUm, s.firstLayerWidthUm)
        val params = SeamDecision.Params(s.seamCornerDeg, s.scarfLengthUm, s.scarfStart, s.visibleSpeed, visibility, shares, timeS,
            "the baseline's predicted time from the technique decision's finalists")
        if (lock != null) return SeamDecision.decide(name, noWalls, params, weights, lock.first, plainStyle, lock.second)
        // decided already (a plan file from `plan -o`, or `slice` after `plan` in one run carries plan.seam_start with its provenance):
        // kept as it is, not weighed again (the slice re-ran the decision, 8 minutes on a 30-part model)
        val already = Resolver.resolveOrNull(setup, "plan.seam_start", ctx)
        if (already != null) {
            val where = Resolver.resolveOrNull(setup, "plan.technique.scarf_seam.where", ctx)?.value as String? ?: "none"
            val plain = SeamDecision.Placement.entries.firstOrNull { it.start == already.value }?.style ?: "corner_seam"
            val style = if (where != "none") "scarf_seam" else plain
            return SeamDecision.decide(name, noWalls, params, weights, style, plain, "${already.from} (decided earlier, kept)")
        }
        val tPrep = System.nanoTime()
        val (outlines, w, topsUm) = try { slicer.wallsForDecision(setup) } catch (e: IllegalStateException) { return null } catch (e: IllegalArgumentException) { return null }
        if (topsUm.isEmpty()) return null
        val prepS = (System.nanoTime() - tPrep) / 1e9
        val printed = w.effOutlines.mapIndexed { i, e -> if (i == 0) outlines[0] else e }
        val walls = SeamDecision.Walls(w.walls.map { it.walls }, printed, topsUm, s.outerWallWidthUm, s.innerWallWidthUm, s.firstLayerWidthUm)
        return SeamDecision.decide(name, walls, params, weights, null, plainStyle, null, prepS)
    }

    /**
     * The in-island retraction of the object [slicer] plans, decided on its planned layers with [setup]
     * (the seams decided and applied): the slice that follows finds it in the plan as a lock and its layers in the cache.
     * Where plan.mechanism.motion.no_retract_in_island already locks it, the lock, with no layers planned. Null where the object is not planned here (several filaments or part settings; a setting it needs unset).
     */
    fun retraction(setup: Setup, slicer: IncrementalSlicer): Decision<Boolean>? {
        // locked (a plan file locks it): nothing to measure, so the layers are not planned for it; the slice plans them once
        if (!slicer.isMultiFilament(setup)) slicer.lockedRetraction(setup)?.let { return it }
        try { slicer.planned(setup) } catch (e: IllegalArgumentException) { return null } catch (e: IllegalStateException) { return null }
        return slicer.retractionDecision
    }
}
