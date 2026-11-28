package plymeld.model

/**
 * The nozzle's cone of one tool: the angle of its side from horizontal ([angleDeg]; a surface no steeper than it clears the
 * cone) and how far its tip sits below the rest of the loaded head ([heightMm]), each with where it came from: the tool's
 * hardware.tool.<key>.head.nozzle.angle / .height, which a config's tool takes from its machine model's toolhead for that
 * tool (hardware.model.<key>.toolhead.<index>.head.nozzle.angle / .height) where it sets none (plymeld-config's Config, one
 * rule for every value a tool and its toolhead share). Every reader of the cone takes it this way: the curved techniques
 * and islands, the pre-print check, support's order, and the travel clearance.
 */
class NozzleCone(val angleDeg: Double, val heightMm: Double, val angleFrom: String, val heightFrom: String) {
    companion object {
        /** Where a user sets the cone, for a message naming what is missing. */
        const val WHERE = "hardware.tool.<key>.head.nozzle.angle and head.nozzle.height (the tool's), or " +
            "hardware.model.<key>.toolhead.<index>.head.nozzle.angle and head.nozzle.height (its machine model's, which a tool that sets none takes)"
        /** The machine description's toolhead geometry keys the model's values are kept under (plymeld-config's Models). */
        const val ANGLE_KEY = "tip_cone_deg"
        const val HEIGHT_KEY = "head.nozzle.height"

        /** The cone of the tool printing [ctx]'s part (its object's own filament's without a part); null when a value is missing. */
        fun of(setup: Setup, ctx: Context): NozzleCone? {
            val a = angle(setup, ctx) ?: return null
            val h = height(setup, ctx) ?: return null
            return NozzleCone(a.first, h.first, a.second, h.second)
        }

        /** The angle (deg) of the tool printing [ctx]'s part, with where it came from; null when the tool has none. */
        fun angle(setup: Setup, ctx: Context): Pair<Double, String>? = value(setup, ctx, "hardware.tool.head.nozzle.angle", "angle")

        /** The height (mm) of the tool printing [ctx]'s part, with where it came from; null when the tool has none. */
        fun height(setup: Setup, ctx: Context): Pair<Double, String>? = value(setup, ctx, "hardware.tool.head.nozzle.height", "height")

        /** The 0-based tool printing [ctx]'s part, or its object's own filament's without a part; null when the setup does not say. */
        fun toolOf(setup: Setup, ctx: Context): Int? {
            val o = setup.objects.getOrNull(ctx.obj) ?: return null
            val spool = ctx.part?.let { o.parts.getOrNull(it)?.spool } ?: o.spool
            return setup.spools.firstOrNull { it.id == spool }?.tool
        }

        private fun value(setup: Setup, ctx: Context, id: String, what: String): Pair<Double, String>? =
            Resolver.resolveOrNull(setup, id, ctx)?.let { (it.value as Number).toDouble() to "hardware.tool.<key>.head.nozzle.$what: ${it.from}" }
    }
}
