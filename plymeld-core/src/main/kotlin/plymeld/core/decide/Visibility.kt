package plymeld.core.decide

import java.util.Locale

/**
 * How much of a face is seen on the finished print, 0..1: the ONE
 * map every quality measure multiplies by, so a stair-step, a support scar, or a seam on a face nobody sees costs less
 * than the same on a show face. Per face of the posed model:
 * - on the bed (a first-layer contact face): [bed] (method.quality.visibility.bed; the print stands on it);
 * - facing down, seen only from below: 1 - (1 - [underside]) x how far down its normal points
 *   (method.quality.visibility.under): a horizontal underside takes the weight in full, a face leaning a little
 *   below level loses almost nothing;
 * - turned away from where the print is looked at from ([view], plan.view_from: front, back, left, right, above; null =
 *   all around, no face turned away): 1 - (1 - [away]) x how squarely it faces away (method.quality.visibility.away),
 *   so a model's back costs less than its front once the job says the front is what is seen (a seam's point instead: its
 *   sampled viewing directions weighed by the quadrant they look from, [look] and [Exposure]);
 * - covered by another shell or part of the same object (Cavities.coveredShare): the covered share counts 0.
 * Flat tops, walls, and sloped tops facing the viewer count in full. A seam's point is also weighed by what hides it
 * nearby, the object's own geometry within a short reach ([Exposure], [sample]: the seam placement and the seam measure).
 * Not yet for the other measures' faces: occlusion by the object itself (a deep slot's floor), by
 * neighbors on the plate, faces the user marks. The bed and away weights are placeholders until measured (named in the
 * decision's stand-ins).
 */
class Visibility(val underside: Double, val bed: Double, val source: String, val away: Double = 1.0,
                 /** The direction the viewer looks along, a unit vector (x, y, z); null = looked at from all around. */
                 val view: DoubleArray? = null,
                 /** How a seam's point is sampled for what hides it nearby ([Exposure]); null = not sampled (every wall open). */
                 val sample: Exposure.Sample? = null,
                 /** How much a seam's sampled viewing directions count by the quadrant around [view] they look from ([look]). */
                 val quadrants: Quadrants = Quadrants.EQUAL) {
    init { require(underside in 0.0..1.0 && bed in 0.0..1.0 && away in 0.0..1.0) { "visibility weights are shares 0..1 (underside $underside, bed $bed, away $away; $source)" } }

    /**
     * The weights of a seam's viewing directions by the quadrant they look from (front 8, left and
     * right 2 each, back 1; with no view set, every side alike), as front, left, right, and back are
     * seen from the viewer's side: the 90 degrees around the print's front, the viewer's left and right, its back.
     */
    class Quadrants(val front: Double, val left: Double, val right: Double, val back: Double) {
        init { require(listOf(front, left, right, back).all { it >= 0 } && front + left + right + back > 0) { "quadrant weights at least 0, not all 0 (front $front, left $left, right $right, back $back)" } }
        fun words(): String = "front x %s, left x %s, right x %s, back x %s".format(Locale.ROOT, fmt(front), fmt(left), fmt(right), fmt(back))
        companion object { val EQUAL = Quadrants(1.0, 1.0, 1.0, 1.0) }
    }

    /**
     * A view's quadrants on the bed: [toward] the viewer and the viewer's [right], unit vectors (x, y), with the weights.
     * [weight]: a direction looking out from the print along (ca, sa) counts its quadrant's weight; one on a quadrant's edge
     * (45 degrees off toward or away) the mean of the two (the sampled directions land on the edges: 16 around, every 22.5).
     */
    class Look(val toward: DoubleArray, val right: DoubleArray, val q: Quadrants) {
        fun weight(ca: Double, sa: Double): Double {
            val c = ca * toward[0] + sa * toward[1]
            val side = if (ca * right[0] + sa * right[1] > 0) q.right else q.left
            val e = Math.sqrt(0.5)
            return when {
                Math.abs(c - e) < EDGE -> (q.front + side) / 2
                Math.abs(c + e) < EDGE -> (q.back + side) / 2
                c > e -> q.front
                c < -e -> q.back
                else -> side
            }
        }
        private companion object { const val EDGE = 1e-9 }
    }

    /** The quadrants around a view from a side (front, back, left, right); null with no view or one from above: every direction alike. */
    val look: Look? get() {
        val v = view ?: return null
        val hl = Math.hypot(v[0], v[1])
        if (hl < 1e-9) return null
        return Look(doubleArrayOf(-v[0] / hl, -v[1] / hl), doubleArrayOf(v[1] / hl, -v[0] / hl), quadrants)
    }

    /**
     * This map for the model turned by [r] (a pose's rotation, row-major 3 x 3): the view turned with it, so it stays on the
     * model's own side (with the pose decided at run time, "front" can only mean the model's own
     * front; read on the bed's axes after the pose, a turn that sent the show face to the back made it count less there).
     */
    fun posed(r: DoubleArray): Visibility {
        val v = view ?: return this
        val x = r[0] * v[0] + r[1] * v[1] + r[2] * v[2]; val y = r[3] * v[0] + r[4] * v[1] + r[5] * v[2]; val z = r[6] * v[0] + r[7] * v[1] + r[8] * v[2]
        val l = Math.sqrt(x * x + y * y + z * z)
        return Visibility(underside, bed, source, away, doubleArrayOf(x / l, y / l, z / l), sample, quadrants)
    }

    /** What hides a seam's point and how much its viewers count ([Exposure]) on these outer-wall [outlines] (per layer) with their [topsUm]; null when not sampled. */
    fun exposure(outlines: List<List<plymeld.geom.Polygon>>, topsUm: LongArray): Exposure? = sample?.let { Exposure(outlines, topsUm, it, look) }

    /** The direction term of a face whose normal's downward component is [down] (1 = facing straight down; 0 or less = level or facing up). */
    fun direction(down: Double): Double = if (down <= 0) 1.0 else 1.0 - (1.0 - underside) * minOf(1.0, down)

    /** The view term of a face whose unit normal's component along [view] is [awayDot] (1 = facing straight away from the viewer; 0 or less = toward). */
    fun awayTerm(awayDot: Double): Double = if (view == null || awayDot <= 0) 1.0 else 1.0 - (1.0 - away) * minOf(1.0, awayDot)

    /** The component of the unit normal ([nx], [ny], [nz]) along the view: how squarely the face turns away; 0 without a view. */
    fun awayDot(nx: Double, ny: Double, nz: Double): Double = view?.let { v -> nx * v[0] + ny * v[1] + nz * v[2] } ?: 0.0

    /**
     * The weight of a face: [down] as above; [bedContact] = it is a contact face on the bed; [covered] = the share another
     * shell covers, 0..1; [awayDot] from [awayDot] (0 = facing the viewer, or no view).
     */
    fun of(down: Double, bedContact: Boolean, covered: Double, awayDot: Double = 0.0): Double =
        (if (bedContact) bed else direction(down) * awayTerm(awayDot)) * (1.0 - covered.coerceIn(0.0, 1.0))

    fun words(): String = "undersides x %s by their downward lean, bed faces x %s, faces covered by another part of the object removed%s%s (%s)"
        .format(Locale.ROOT, fmt(underside), fmt(bed), if (view == null) "; looked at from all around" else ", faces turned away from the viewer x %s by how squarely (%s)".format(Locale.ROOT, fmt(away), viewName ?: "a view"),
            if (look == null) "" else "; a seam's viewing directions by quadrant, " + quadrants.words(), source)

    /** The plan.view_from name of [view], when it is one of the named directions. */
    val viewName: String? get() = view?.let { v -> VIEWS.entries.firstOrNull { (_, d) -> d.contentEquals(v) }?.key }

    companion object {
        /** Every face counts alike: what the measures did before the map (the control in tests). */
        val NONE = Visibility(1.0, 1.0, "every face counts alike", 1.0, null)

        /**
         * The named views (plan.view_from) as the direction the viewer looks along, on the model's own axes as given (the bed's
         * when it prints as given: the printer's front is the small-y side, the door), so "front" looks along +y; "above" looks
         * down. A posed object's view is this turned with its pose ([posed], [posedView]).
         */
        val VIEWS: Map<String, DoubleArray> = linkedMapOf("front" to doubleArrayOf(0.0, 1.0, 0.0), "back" to doubleArrayOf(0.0, -1.0, 0.0),
            "left" to doubleArrayOf(1.0, 0.0, 0.0), "right" to doubleArrayOf(-1.0, 0.0, 0.0), "above" to doubleArrayOf(0.0, 0.0, -1.0))

        /**
         * The view vector of a plan.view_from value; null for all_around (and for an unknown name, which the schema refuses first).
         * A decided value may be the view turned with the object's pose ([posedView]): three numbers, the direction itself.
         */
        fun viewOf(name: String?): DoubleArray? = name?.let { n ->
            VIEWS[n] ?: n.trim().split(Regex("\\s+")).takeIf { it.size == 3 }?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 3 }?.toDoubleArray()
        }

        /**
         * The plan.view_from value [name] (the model's own side) turned with a pose's rotation [r], as the decided value the
         * object's settings carry after the pose (three numbers); null when there is no view or the pose leaves it where it was.
         */
        fun posedView(name: String?, r: DoubleArray): String? {
            val v = viewOf(name) ?: return null
            val t = Visibility(1.0, 1.0, "posed", view = v).posed(r).view!!
            if (Math.abs(t[0] - v[0]) < 1e-9 && Math.abs(t[1] - v[1]) < 1e-9 && Math.abs(t[2] - v[2]) < 1e-9) return null
            return t.joinToString(" ") { String.format(Locale.ROOT, "%.9f", it) }
        }

        /**
         * The map from an object's slice settings (the same five settings as [from], read once there): what the paths stage places
         * seams with. The two are the only ways a map is built, so every stage that weighs visibility (the Planner, the
         * cooling side area) sees the same map, view included.
         */
        fun of(s: plymeld.model.SliceSettings): Visibility = Visibility(s.visibilityUnderside, s.visibilityBed, "settings", s.visibilityAway, viewOf(s.viewFrom),
            Exposure.Sample(s.visibilityAzimuths, s.visibilityElevations, s.visibilityMaxElevationDeg, s.visibilityReachUm, s.visibilityCellUm, s.visibilityShowThroughUm),
            s.visibilityQuadrants.let { q -> Quadrants(q[0], q[1], q[2], q[3]) })

        /** The quadrant weights (method.quality.visibility.quadrant.*: catalog defaults, so always set). */
        fun quadrantsOf(setup: plymeld.model.Setup, ctx: plymeld.model.Context = plymeld.model.Context()): Quadrants {
            fun num(id: String) = (plymeld.model.Resolver.resolve(setup, "method.quality.visibility.quadrant.$id", ctx).value as Number).toDouble()
            return Quadrants(num("front"), num("left"), num("right"), num("back"))
        }

        /** The sampling of what hides a seam nearby (method.quality.visibility.sample.*: catalog defaults, so always set). */
        fun sampleOf(setup: plymeld.model.Setup, ctx: plymeld.model.Context = plymeld.model.Context()): Exposure.Sample {
            fun num(id: String) = (plymeld.model.Resolver.resolve(setup, "method.quality.visibility.sample.$id", ctx).value as Number).toDouble()
            val show = (plymeld.model.Resolver.resolve(setup, "method.quality.visibility.show_through", ctx).value as Number).toDouble()
            return Exposure.Sample(num("azimuths").toInt(), num("elevations").toInt(), num("elevation.max"), Math.round(num("reach") * 1000), Math.round(num("cell") * 1000),
                Math.round(show * 1000))
        }

        /** The map from the config's method.quality.visibility.* and plan.view_from (an object's context [ctx]); null when the underside weight is not set. */
        fun from(setup: plymeld.model.Setup, ctx: plymeld.model.Context = plymeld.model.Context()): Visibility? {
            val u = plymeld.model.Resolver.resolveOrNull(setup, "method.quality.visibility.under", ctx) ?: return null
            val b = plymeld.model.Resolver.resolveOrNull(setup, "method.quality.visibility.bed", ctx)
            val a = plymeld.model.Resolver.resolveOrNull(setup, "method.quality.visibility.away", ctx)
            val v = plymeld.model.Resolver.resolveOrNull(setup, "plan.view_from", ctx)
            return Visibility((u.value as Number).toDouble(), (b?.value as Number?)?.toDouble() ?: 0.0,
                "method.quality.visibility.under: ${u.from}; .bed: ${b?.from ?: "unset, 0"}; .away: ${a?.from ?: "unset, 1"}; plan.view_from: ${v?.let { "${it.value}, ${it.from}" } ?: "all around"}",
                (a?.value as Number?)?.toDouble() ?: 1.0, viewOf(v?.value as String?), sampleOf(setup, ctx), quadrantsOf(setup, ctx))
        }

        /** The seam measure's shares (method.quality.seam.scarf_share, .corner_share: the catalog's defaults when unset, with plan.technique.corner_seam.corner_angle). */
        fun seamShares(setup: plymeld.model.Setup, ctx: plymeld.model.Context = plymeld.model.Context()): plymeld.core.SeamScar.Shares {
            fun num(id: String) = (plymeld.model.Resolver.resolveOrNull(setup, id, ctx)?.value as Number?)?.toDouble()
            fun share(id: String) = (plymeld.model.Resolver.resolve(setup, id, ctx).value as Number).toDouble()
            return plymeld.core.SeamScar.Shares(share("method.quality.seam.scarf_share"), share("method.quality.seam.corner_share"), num("plan.technique.corner_seam.corner_angle"))
        }

        private fun fmt(v: Double) = if (v == Math.floor(v)) v.toLong().toString() else "%.2f".format(Locale.ROOT, v)
    }
}
